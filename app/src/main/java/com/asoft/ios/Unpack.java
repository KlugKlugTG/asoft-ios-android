package com.asoft.ios;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * What the PC installers do with blacktop/ipsw (`ipsw fw aea`, `ipsw img4 im4p extract`, `ipsw kernel dec`),
 * here in Java with the decoding in native code (unpack.c: Apple's LZFSE):
 *  - AEA (Apple Encrypted Archive, profile 1, as pkg/aea of ipsw 3.1.739): the key from the header's own
 *    "encryption_key", else Apple's FCS: the header's HPKE-wrapped key (fcs-response) opened with the private
 *    key its fcs-key-url names (DHKEM P-256, HKDF-SHA256, AES-256-GCM); then per cluster and segment HKDF keys,
 *    HMAC checks, AES-CTR and LZFSE. The clusters are written in order with a progress file, so a decrypt that
 *    stopped goes on from its last whole cluster, and the encrypted bytes behind it are freed as it goes.
 *  - IM4P: the payload of the DER wrapper, LZFSE ("bvx2") or LZSS ("complzss") unpacked.
 */
final class Unpack {
    private Unpack() {}

    static {
        System.loadLibrary("unpack");
    }

    static native long lzfse(ByteBuffer src, int off, int len, ByteBuffer dst, int dcap);

    static native int punchHole(String path, long off, long len);

    // -- HKDF, HMAC, AES ----------------------------------------------------------------------------

    private static byte[] hmac(byte[] key, byte[]... parts) throws IOException {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key.length == 0 ? new byte[32] : key, "HmacSHA256"));
            for (byte[] p : parts) {
                m.update(p);
            }
            return m.doFinal();
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    static byte[] hkdfExtract(byte[] salt, byte[] ikm) throws IOException {
        return hmac(salt == null || salt.length == 0 ? new byte[32] : salt, ikm);
    }

    static byte[] hkdfExpand(byte[] prk, byte[] info, int n) throws IOException {
        byte[] out = new byte[n];
        byte[] t = new byte[0];
        int at = 0;
        for (int i = 1; at < n; i++) {
            t = hmac(prk, t, info, new byte[] {(byte) i});
            int k = Math.min(t.length, n - at);
            System.arraycopy(t, 0, out, at, k);
            at += k;
        }
        return out;
    }

    static byte[] hkdf(byte[] ikm, byte[] salt, byte[] info, int n) throws IOException {
        return hkdfExpand(hkdfExtract(salt, ikm), info, n);
    }

    private static byte[] cat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) {
            n += p.length;
        }
        byte[] o = new byte[n];
        int at = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, o, at, p.length);
            at += p.length;
        }
        return o;
    }

    private static byte[] le32(long v) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt((int) v).array();
    }

    private static byte[] le64(long v) {
        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array();
    }

    /** HKDF material (MAC 32, key 32, IV 16) for @info. */
    private static byte[][] material(byte[] key, String info, byte[] more) throws IOException {
        byte[] b = hkdf(key, new byte[0], cat(info.getBytes(StandardCharsets.US_ASCII), more), 80);
        return new byte[][] {Arrays.copyOfRange(b, 0, 32), Arrays.copyOfRange(b, 32, 64), Arrays.copyOfRange(b, 64, 80)};
    }

    /** AEA's HMAC: mac(key, salt || data || le64(len(salt))). */
    private static byte[] aeaMac(byte[] key, byte[] data, int off, int len, byte[] salt) throws IOException {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            m.update(salt);
            m.update(data, off, len);
            m.update(le64(salt.length));
            return m.doFinal();
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    private static void ctr(byte[] key, byte[] iv, byte[] data, int off, int len) throws IOException {
        try {
            Cipher c = Cipher.getInstance("AES/CTR/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            int n = c.doFinal(data, off, len, data, off);
            if (n != len) {
                throw new IOException("AES-CTR gave " + n + " of " + len + " bytes");
            }
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    // -- AEA: the header and its key ----------------------------------------------------------------

    /** An AEA file's header fields. */
    static final class AeaHead {
        int profile;            // the low 24 bits; 1 = symmetric encryption
        int profileWord;
        byte[] authData;
        final Map<String, byte[]> meta = new LinkedHashMap<>();
        long dataAt;            // after the root header: the first cluster
        byte[] salt;
        byte[] rootMac, rootData, firstClusterMac;
    }

    static AeaHead head(File f) throws IOException {
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            byte[] h = new byte[12];
            r.readFully(h);
            if (h[0] != 'A' || h[1] != 'E' || h[2] != 'A' || h[3] != '1') {
                throw new IOException(f.getName() + " is no AEA file");
            }
            ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
            AeaHead a = new AeaHead();
            a.profileWord = b.getInt(4);
            a.profile = a.profileWord & 0xffffff;
            int alen = b.getInt(8);
            if (alen < 0 || alen > 1 << 20) {
                throw new IOException(f.getName() + ": auth data of " + alen + " bytes");
            }
            a.authData = new byte[alen];
            r.readFully(a.authData);
            ByteBuffer ad = ByteBuffer.wrap(a.authData).order(ByteOrder.LITTLE_ENDIAN);
            while (ad.remaining() >= 4) {
                int n = ad.getInt();
                if (n < 4 || n - 4 > ad.remaining()) {
                    break;
                }
                byte[] kv = new byte[n - 4];
                ad.get(kv);
                int z = 0;
                while (z < kv.length && kv[z] != 0) {
                    z++;
                }
                a.meta.put(new String(kv, 0, z, StandardCharsets.UTF_8), Arrays.copyOfRange(kv, Math.min(kv.length, z + 1), kv.length));
            }
            a.salt = new byte[32];
            r.readFully(a.salt);
            a.rootMac = new byte[32];
            a.rootData = new byte[48];
            a.firstClusterMac = new byte[32];
            r.readFully(a.rootMac);
            r.readFully(a.rootData);
            r.readFully(a.firstClusterMac);
            a.dataAt = r.getFilePointer();
            return a;
        }
    }

    interface KeySource {
        /** The PEM private key FCS names @keyName (the last part of its fcs-key-url), from @url or elsewhere. */
        byte[] privateKey(String url, String keyName) throws IOException;
    }

    /** The AEA's symmetric key: its own, or Apple's FCS-wrapped one. */
    static byte[] key(AeaHead a, KeySource keys) throws IOException {
        byte[] own = a.meta.get("encryption_key");
        if (own != null) {
            return hex(new String(own, StandardCharsets.US_ASCII).trim());
        }
        byte[] resp = a.meta.get("com.apple.wkms.fcs-response");
        byte[] url = a.meta.get("com.apple.wkms.fcs-key-url");
        if (resp == null || url == null) {
            throw new IOException("the AEA header names no key (" + a.meta.keySet() + ")");
        }
        String u = new String(url, StandardCharsets.UTF_8).trim();
        String name = u.substring(u.lastIndexOf('/') + 1);
        int q = name.indexOf('?');
        if (q >= 0) {
            name = name.substring(0, q);
        }
        try {
            org.json.JSONObject j = new org.json.JSONObject(new String(resp, StandardCharsets.UTF_8));
            byte[] enc = java.util.Base64.getDecoder().decode(j.getString("enc-request"));
            byte[] wrapped = java.util.Base64.getDecoder().decode(j.getString("wrapped-key"));
            return hpkeOpen(keys.privateKey(u, name), enc, wrapped);
        } catch (org.json.JSONException e) {
            throw new IOException("the AEA's fcs-response: " + e.getMessage());
        }
    }

    static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return b;
    }

    // -- HPKE (RFC 9180) base mode, DHKEM(P-256, HKDF-SHA256), HKDF-SHA256, AES-256-GCM, empty info --------

    private static final byte[] V1 = "HPKE-v1".getBytes(StandardCharsets.US_ASCII);

    private static byte[] labeledExtract(byte[] suite, byte[] salt, String label, byte[] ikm) throws IOException {
        return hkdfExtract(salt, cat(V1, suite, label.getBytes(StandardCharsets.US_ASCII), ikm));
    }

    private static byte[] labeledExpand(byte[] suite, byte[] prk, String label, byte[] info, int n) throws IOException {
        return hkdfExpand(prk, cat(new byte[] {(byte) (n >> 8), (byte) n}, V1, suite, label.getBytes(StandardCharsets.US_ASCII), info), n);
    }

    static byte[] hpkeOpen(byte[] pem, byte[] enc, byte[] ct) throws IOException {
        try {
            String text = new String(pem, StandardCharsets.US_ASCII);
            int a = text.indexOf("-----BEGIN"), b = text.indexOf("-----END");
            if (a < 0 || b < 0) {
                throw new IOException("the FCS private key is no PEM");
            }
            String body = text.substring(text.indexOf('\n', a) + 1, b).replaceAll("\\s", "");
            byte[] der = java.util.Base64.getDecoder().decode(body);
            KeyFactory kf = KeyFactory.getInstance("EC");
            ECPrivateKey sk = (ECPrivateKey) kf.generatePrivate(new PKCS8EncodedKeySpec(der));
            ECParameterSpec spec = sk.getParams();
            // the recipient's public key: skR * G (P-256), serialized uncompressed
            ECPoint pkR = multiply(spec, sk.getS());
            byte[] pkRm = point(pkR);
            if (enc.length != 65 || enc[0] != 4) {
                throw new IOException("enc-request is no uncompressed P-256 point");
            }
            ECPoint pkE = new ECPoint(new BigInteger(1, Arrays.copyOfRange(enc, 1, 33)), new BigInteger(1, Arrays.copyOfRange(enc, 33, 65)));
            KeyAgreement ka = KeyAgreement.getInstance("ECDH");
            ka.init(sk);
            ka.doPhase(kf.generatePublic(new ECPublicKeySpec(pkE, spec)), true);
            byte[] dh = ka.generateSecret();
            byte[] kemSuite = cat("KEM".getBytes(StandardCharsets.US_ASCII), new byte[] {0x00, 0x10});
            byte[] eae = labeledExtract(kemSuite, new byte[0], "eae_prk", dh);
            byte[] shared = labeledExpand(kemSuite, eae, "shared_secret", cat(enc, pkRm), 32);
            byte[] suite = cat("HPKE".getBytes(StandardCharsets.US_ASCII), new byte[] {0x00, 0x10, 0x00, 0x01, 0x00, 0x02});
            byte[] pskIdHash = labeledExtract(suite, new byte[0], "psk_id_hash", new byte[0]);
            byte[] infoHash = labeledExtract(suite, new byte[0], "info_hash", new byte[0]);
            byte[] ctx = cat(new byte[] {0x00}, pskIdHash, infoHash);
            byte[] secret = labeledExtract(suite, shared, "secret", new byte[0]);
            byte[] key = labeledExpand(suite, secret, "key", ctx, 32);
            byte[] nonce = labeledExpand(suite, secret, "base_nonce", ctx, 12);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            return c.doFinal(ct);
        } catch (java.security.GeneralSecurityException | IllegalArgumentException e) {
            throw new IOException("opening the AEA's wrapped key failed: " + e);
        }
    }

    private static byte[] point(ECPoint p) {
        byte[] o = new byte[65];
        o[0] = 4;
        byte[] x = p.getAffineX().toByteArray(), y = p.getAffineY().toByteArray();
        System.arraycopy(x, Math.max(0, x.length - 32), o, 1 + Math.max(0, 32 - x.length), Math.min(32, x.length));
        System.arraycopy(y, Math.max(0, y.length - 32), o, 33 + Math.max(0, 32 - y.length), Math.min(32, y.length));
        return o;
    }

    /** k * G on the curve (affine double-and-add; once per key, so speed does not matter). */
    private static ECPoint multiply(ECParameterSpec s, BigInteger k) {
        BigInteger p = ((java.security.spec.ECFieldFp) s.getCurve().getField()).getP();
        BigInteger a = s.getCurve().getA();
        ECPoint r = ECPoint.POINT_INFINITY, q = s.getGenerator();
        for (int i = k.bitLength() - 1; i >= 0; i--) {
            r = add(r, r, p, a);
            if (k.testBit(i)) {
                r = add(r, q, p, a);
            }
        }
        return r;
    }

    private static ECPoint add(ECPoint u, ECPoint v, BigInteger p, BigInteger a) {
        if (u.equals(ECPoint.POINT_INFINITY)) {
            return v;
        }
        if (v.equals(ECPoint.POINT_INFINITY)) {
            return u;
        }
        BigInteger x1 = u.getAffineX(), y1 = u.getAffineY(), x2 = v.getAffineX(), y2 = v.getAffineY();
        BigInteger l;
        if (x1.equals(x2)) {
            if (!y1.equals(y2) || y1.signum() == 0) {
                return ECPoint.POINT_INFINITY;
            }
            l = x1.pow(2).multiply(BigInteger.valueOf(3)).add(a).multiply(y1.shiftLeft(1).modInverse(p)).mod(p);
        } else {
            l = y2.subtract(y1).multiply(x2.subtract(x1).modInverse(p)).mod(p);
        }
        BigInteger x3 = l.pow(2).subtract(x1).subtract(x2).mod(p);
        BigInteger y3 = l.multiply(x1.subtract(x3)).subtract(y1).mod(p);
        return new ECPoint(x3, y3);
    }

    // -- AEA: the clusters -------------------------------------------------------------------------

    /**
     * Decrypts @in into @out (the plain image). @progress is told the plaintext bytes done. A file
     * out + ".progress" keeps where a stopped decrypt goes on ("cluster inputOffset clusterMacHex
     * plainDone"); with @freeInput the encrypted bytes of every finished cluster are freed (the file
     * keeps its size). Returns the plain size.
     */
    static long decryptAea(File in, File out, byte[] symKey, boolean freeInput, LongConsumer progress, BooleanSupplier cancelled)
            throws IOException {
        AeaHead a = head(in);
        if (a.profile != 1) {
            throw new IOException(in.getName() + ": AEA profile " + a.profile + ", not 1 (symmetric encryption)");
        }
        byte[] main = hkdf(symKey, a.salt, cat("AEA_AMK".getBytes(StandardCharsets.US_ASCII), le32(a.profileWord)), 32);
        byte[][] rk = material(main, "AEA_RHEK", new byte[0]);
        byte[] calc = aeaMac(rk[0], a.rootData, 0, 48, cat(a.firstClusterMac, a.authData));
        if (!MessageDigest.isEqual(calc, a.rootMac)) {
            throw new IOException(in.getName() + ": the key does not fit (root header HMAC)");
        }
        byte[] rh = a.rootData.clone();
        ctr(rk[1], rk[2], rh, 0, 48);
        ByteBuffer r = ByteBuffer.wrap(rh).order(ByteOrder.LITTLE_ENDIAN);
        long fsize = r.getLong(0);
        int segSize = r.getInt(16), nseg = r.getInt(20);
        char comp = (char) (rh[24] & 0xff);
        int cks = rh[25] & 0xff;
        int cksz = cks == 0 ? 0 : cks == 1 ? 8 : 32;
        if (comp != 'e' && comp != '-') {
            throw new IOException(in.getName() + ": compression '" + comp + "' (only LZFSE and none are decoded here)");
        }
        int entry = 8 + cksz;
        File prog = new File(out.getPath() + ".progress");
        long inOff = a.dataAt, plain = 0;
        int cindex = 0;
        byte[] clusterMac = a.firstClusterMac;
        if (prog.isFile() && out.isFile()) {
            String[] p = new String(java.nio.file.Files.readAllBytes(prog.toPath()), StandardCharsets.US_ASCII).trim().split(" ");
            if (p.length == 4) {
                cindex = Integer.parseInt(p[0]);
                inOff = Long.parseLong(p[1]);
                clusterMac = hex(p[2]);
                plain = Long.parseLong(p[3]);
            }
        } else {
            out.delete();
        }
        int workers = Math.max(2, Math.min(6, Runtime.getRuntime().availableProcessors() - 2));
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try (FileChannel src = FileChannel.open(in.toPath(), StandardOpenOption.READ);
             FileChannel dst = FileChannel.open(out.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            byte[] hdr = new byte[entry * nseg];
            byte[] macs = new byte[32 + 32 * nseg];
            while (plain < fsize) {
                if (cancelled.getAsBoolean()) {
                    throw new IOException("stopped");
                }
                byte[] ck = hkdf(main, new byte[0], cat("AEA_CK".getBytes(StandardCharsets.US_ASCII), le32(cindex)), 32);
                byte[][] chk = material(ck, "AEA_CHEK", new byte[0]);
                readAt(src, inOff, hdr);
                readAt(src, inOff + hdr.length, macs);
                byte[] mac = aeaMac(chk[0], hdr, 0, hdr.length, macs);
                if (!MessageDigest.isEqual(mac, clusterMac)) {
                    throw new IOException(in.getName() + ": cluster " + cindex + " is damaged (HMAC); download it again");
                }
                byte[] next = Arrays.copyOfRange(macs, 0, 32);
                byte[] seg = hdr.clone();
                ctr(chk[1], chk[2], seg, 0, seg.length);
                ByteBuffer sb = ByteBuffer.wrap(seg).order(ByteOrder.LITTLE_ENDIAN);
                long at = inOff + hdr.length + macs.length;
                List<Future<Long>> jobs = new ArrayList<>();
                for (int i = 0; i < nseg; i++) {
                    long dsize = sb.getInt(i * entry) & 0xffffffffL, csize = sb.getInt(i * entry + 4) & 0xffffffffL;
                    if (dsize == 0) {
                        continue;
                    }
                    final int idx = i;
                    final long from = at;
                    final long pos = ((long) cindex * nseg + i) * segSize;
                    final byte[] segMac = Arrays.copyOfRange(macs, 32 + 32 * i, 64 + 32 * i);
                    jobs.add(pool.submit(() -> segment(src, dst, ck, idx, from, (int) csize, (int) dsize, pos, segMac, comp)));
                    at += csize;
                }
                long got = 0;
                try {
                    for (Future<Long> f : jobs) {
                        got += f.get();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("stopped");
                } catch (java.util.concurrent.ExecutionException e) {
                    Throwable c = e.getCause();
                    throw c instanceof IOException ? (IOException) c : new IOException(c);
                }
                dst.force(false);
                if (freeInput) {
                    punchHole(in.getPath(), inOff & ~0xfffffL, (at & ~0xfffffL) - (inOff & ~0xfffffL));
                }
                plain += got;
                inOff = at;
                cindex++;
                clusterMac = next;
                java.nio.file.Files.write(prog.toPath(), (cindex + " " + inOff + " " + toHex(clusterMac) + " " + plain)
                        .getBytes(StandardCharsets.US_ASCII));
                progress.accept(plain);
            }
            dst.truncate(fsize);
            dst.force(true);
        } finally {
            pool.shutdownNow();
        }
        if (out.length() != fsize) {
            throw new IOException(out.getName() + ": " + out.length() + " bytes, not " + fsize);
        }
        prog.delete();
        return fsize;
    }

    static String toHex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) {
            s.append(String.format("%02x", x & 0xff));
        }
        return s.toString();
    }

    private static void readAt(FileChannel ch, long at, byte[] b) throws IOException {
        ByteBuffer bb = ByteBuffer.wrap(b);
        while (bb.hasRemaining()) {
            if (ch.read(bb, at + bb.position()) < 0) {
                throw new IOException("the AEA file ends early (at " + (at + bb.position()) + ")");
            }
        }
    }

    private static final ThreadLocal<ByteBuffer[]> BUFS = new ThreadLocal<>();

    private static long segment(FileChannel src, FileChannel dst, byte[] ck, int idx, long from, int csize, int dsize, long pos,
            byte[] wantMac, char comp) throws IOException {
        byte[][] k = material(ck, "AEA_SK", le32(idx));
        byte[] data = new byte[csize];
        readAt(src, from, data);
        byte[] mac = aeaMac(k[0], data, 0, csize, new byte[0]);
        if (!MessageDigest.isEqual(mac, wantMac)) {
            throw new IOException("segment " + idx + " is damaged (HMAC); download the file again");
        }
        ctr(k[1], k[2], data, 0, csize);
        ByteBuffer outBuf;
        if (csize == dsize || comp == '-') {
            outBuf = ByteBuffer.wrap(data, 0, dsize);
        } else {
            ByteBuffer[] b = BUFS.get();
            if (b == null || b[0].capacity() < csize || b[1].capacity() < dsize + 1) {
                b = new ByteBuffer[] {ByteBuffer.allocateDirect(Math.max(csize, 1 << 20)), ByteBuffer.allocateDirect(Math.max(dsize + 1, 1 << 20))};
                BUFS.set(b);
            }
            b[0].clear();
            b[0].put(data, 0, csize);
            long n = lzfse(b[0], 0, csize, b[1], dsize + 1);
            if (n != dsize) {
                throw new IOException("segment " + idx + ": LZFSE gave " + n + " bytes, not " + dsize);
            }
            outBuf = b[1].duplicate();
            outBuf.clear();
            outBuf.limit(dsize);
        }
        while (outBuf.hasRemaining()) {
            dst.write(outBuf, pos + (dsize - outBuf.remaining()));
        }
        return dsize;
    }

    // -- IM4P ---------------------------------------------------------------------------------------

    /** A DER element: tag, where its content starts, its length. */
    private static long[] der(RandomAccessFile f, long at) throws IOException {
        f.seek(at);
        int tag = f.readUnsignedByte();
        int l = f.readUnsignedByte();
        long len;
        if (l < 0x80) {
            len = l;
        } else {
            int n = l & 0x7f;
            len = 0;
            for (int i = 0; i < n; i++) {
                len = len << 8 | f.readUnsignedByte();
            }
        }
        return new long[] {tag, f.getFilePointer(), len};
    }

    /**
     * The payload of IM4P @in into @out, unpacked when LZFSE or LZSS packed it (as `ipsw img4 im4p extract`
     * and `ipsw kernel dec` leave it). Returns what it was: "raw", "lzfse" or "lzss".
     */
    static String im4p(File in, File out) throws IOException {
        long payAt, payLen;
        long usize = -1;
        try (RandomAccessFile f = new RandomAccessFile(in, "r")) {
            long[] seq = der(f, 0);
            if (seq[0] != 0x30) {
                throw new IOException(in.getName() + " is no IM4P");
            }
            long at = seq[1];
            long[] e = der(f, at);
            byte[] magic = new byte[(int) e[2]];
            f.seek(e[1]);
            f.readFully(magic);
            if (!"IM4P".equals(new String(magic, StandardCharsets.US_ASCII))) {
                throw new IOException(in.getName() + " is no IM4P");
            }
            at = e[1] + e[2];
            for (int i = 0; i < 2; i++) {          // type, description
                e = der(f, at);
                at = e[1] + e[2];
            }
            e = der(f, at);
            if (e[0] != 0x04) {
                throw new IOException(in.getName() + ": no payload");
            }
            payAt = e[1];
            payLen = e[2];
            at = e[1] + e[2];
            // the optional compression info: SEQUENCE { INTEGER algorithm, INTEGER size }
            if (at < seq[1] + seq[2]) {
                long[] c = der(f, at);
                if (c[0] == 0x30) {
                    long[] alg = der(f, c[1]);
                    long[] sz = der(f, alg[1] + alg[2]);
                    if (sz[0] == 0x02 && sz[2] <= 8) {
                        f.seek(sz[1]);
                        usize = 0;
                        for (int i = 0; i < sz[2]; i++) {
                            usize = usize << 8 | f.readUnsignedByte();
                        }
                    }
                }
            }
            byte[] m = new byte[8];
            f.seek(payAt);
            f.readFully(m, 0, (int) Math.min(8, payLen));
            String mg = new String(m, StandardCharsets.ISO_8859_1);
            File part = new File(out.getPath() + ".part");
            if (mg.startsWith("bvx2") || mg.startsWith("bvx1") || mg.startsWith("bvxn") || mg.startsWith("bvx-")) {
                if (payLen > Integer.MAX_VALUE - 16) {
                    throw new IOException(in.getName() + ": LZFSE payload too big");
                }
                ByteBuffer s = ByteBuffer.allocateDirect((int) payLen);
                try (FileChannel ch = FileChannel.open(in.toPath(), StandardOpenOption.READ)) {
                    while (s.hasRemaining()) {
                        if (ch.read(s, payAt + s.position()) < 0) {
                            throw new IOException(in.getName() + " ends early");
                        }
                    }
                }
                long cap = usize > 0 ? usize + 1 : Math.min(Integer.MAX_VALUE - 16, payLen * 8 + (1 << 20));
                ByteBuffer d = ByteBuffer.allocateDirect((int) cap);
                long n = lzfse(s, 0, (int) payLen, d, (int) cap);
                if (n < 0 || (usize > 0 && n != usize)) {
                    throw new IOException(in.getName() + ": LZFSE gave " + n + " bytes" + (usize > 0 ? ", not " + usize : ""));
                }
                d.limit((int) n);
                write(part, d);
                rename(part, out);
                return "lzfse";
            }
            if (mg.equals("complzss")) {
                byte[] h = new byte[0x180];
                f.seek(payAt);
                f.readFully(h);
                ByteBuffer hb = ByteBuffer.wrap(h);
                int un = hb.getInt(12), cn = hb.getInt(16);
                byte[] src = new byte[cn];
                f.seek(payAt + 0x180);
                f.readFully(src);
                byte[] dst = lzss(src, un);
                write(part, ByteBuffer.wrap(dst));
                rename(part, out);
                return "lzss";
            }
        }
        // raw: copied out as it is
        try (FileChannel s = FileChannel.open(in.toPath(), StandardOpenOption.READ);
             FileChannel d = FileChannel.open(new File(out.getPath() + ".part").toPath(), StandardOpenOption.CREATE,
                     StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            long done = 0;
            while (done < payLen) {
                done += s.transferTo(payAt + done, payLen - done, d);
            }
            d.force(true);
        }
        rename(new File(out.getPath() + ".part"), out);
        return "raw";
    }

    private static void write(File f, ByteBuffer b) throws IOException {
        try (FileChannel d = FileChannel.open(f.toPath(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            while (b.hasRemaining()) {
                d.write(b);
            }
            d.force(true);
        }
    }

    private static void rename(File from, File to) throws IOException {
        to.delete();
        if (!from.renameTo(to)) {
            throw new IOException("cannot write " + to);
        }
    }

    /** Apple's LZSS (the kernelcache's "complzss": 4 KiB window, 18-byte matches, ring start 4078). */
    static byte[] lzss(byte[] src, int outLen) {
        byte[] out = new byte[outLen];
        byte[] ring = new byte[4096 + 17];
        Arrays.fill(ring, 0, 4096 - 18, (byte) ' ');
        int r = 4096 - 18, flags = 0, o = 0, i = 0;
        while (i < src.length && o < outLen) {
            if (((flags >>= 1) & 0x100) == 0) {
                flags = (src[i++] & 0xff) | 0xff00;
                if (i >= src.length) {
                    break;
                }
            }
            if ((flags & 1) != 0) {
                if (i >= src.length) {
                    break;
                }
                byte c = src[i++];
                out[o++] = c;
                ring[r++] = c;
                r &= 4095;
            } else {
                if (i + 1 >= src.length) {
                    break;
                }
                int a = src[i++] & 0xff, b = src[i++] & 0xff;
                a |= (b & 0xf0) << 4;
                b = (b & 0x0f) + 2;
                for (int k = 0; k <= b && o < outLen; k++) {
                    byte c = ring[(a + k) & 4095];
                    out[o++] = c;
                    ring[r++] = c;
                    r &= 4095;
                }
            }
        }
        return out;
    }

    // -- the FCS keys ----------------------------------------------------------------------------------

    /**
     * Where an FCS private key comes from: the URL the AEA header names (Apple's key service; the header's
     * wkms.sd.apple.com is tried as wkms-public.apple.com too), else the key list blacktop/ipsw 3.1.739
     * carries (the one the PC installers decrypt with), downloaded once from its release tag and checked.
     */
    static KeySource keySource(File cache, String dbUrl, String dbSha256, java.util.function.Consumer<String> log) {
        return (url, name) -> {
            List<String> tries = new ArrayList<>();
            tries.add(url);
            if (url.contains("wkms.sd.apple.com")) {
                tries.add(url.replace("wkms.sd.apple.com", "wkms-public.apple.com"));
            }
            for (String u : tries) {
                try {
                    byte[] k = get(u, 1 << 16);
                    if (new String(k, StandardCharsets.US_ASCII).contains("PRIVATE KEY")) {
                        log.accept("FCS key " + name + " from " + new URL(u).getHost());
                        return k;
                    }
                } catch (IOException e) {
                    log.accept("FCS key from " + u + ": " + e.getMessage());
                }
            }
            File db = new File(cache, "fcs-keys.json.gz");
            if (!db.isFile() || !dbSha256.equalsIgnoreCase(sha256(db))) {
                byte[] b = get(dbUrl, 4 << 20);
                java.nio.file.Files.write(db.toPath(), b);
                if (!dbSha256.equalsIgnoreCase(sha256(db))) {
                    db.delete();
                    throw new IOException("the FCS key list has another SHA-256 than the pinned one");
                }
            }
            try (InputStream in = new java.util.zip.GZIPInputStream(new java.io.FileInputStream(db))) {
                org.json.JSONObject j = new org.json.JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                for (java.util.Iterator<String> it = j.keys(); it.hasNext(); ) {
                    String k = it.next();
                    if (k.equalsIgnoreCase(name)) {
                        log.accept("FCS key " + name + " from ipsw's key list");
                        return java.util.Base64.getDecoder().decode(j.getString(k));
                    }
                }
            } catch (org.json.JSONException | IllegalArgumentException e) {
                throw new IOException("the FCS key list: " + e.getMessage());
            }
            throw new IOException("no FCS key " + name + " (Apple's key service did not answer and ipsw's list lacks it)");
        };
    }

    static String sha256(File f) throws IOException {
        try (InputStream in = new java.io.FileInputStream(f)) {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            byte[] b = new byte[1 << 20];
            int n;
            while ((n = in.read(b)) > 0) {
                d.update(b, 0, n);
            }
            return toHex(d.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    /** A small HTTPS GET (redirects followed), at most @max bytes. */
    static byte[] get(String url, int max) throws IOException {
        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(20_000);
            c.setReadTimeout(30_000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", "ASoftware-iOS-setup");
            int code = c.getResponseCode();
            if (code / 100 == 3) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) {
                    throw new IOException("HTTP " + code + " without a Location");
                }
                url = new URL(new URL(url), loc).toString();
                continue;
            }
            if (code != 200) {
                c.disconnect();
                throw new IOException("HTTP " + code);
            }
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                byte[] b = new byte[1 << 16];
                int n;
                while ((n = in.read(b)) > 0) {
                    o.write(b, 0, n);
                    if (o.size() > max) {
                        throw new IOException("more than " + max + " bytes");
                    }
                }
                return o.toByteArray();
            } finally {
                c.disconnect();
            }
        }
        throw new IOException("too many redirects");
    }
}
