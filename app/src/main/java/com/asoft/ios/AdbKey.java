package com.asoft.ios;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * This app's adb key: the RSA key the phone's adbd learns at pairing and knows again at every connection
 * (WirelessAdb). adbd takes the key out of a TLS client certificate, so the key comes with a self-signed one,
 * built here the way adb builds its own (version 3, CA, ten years); neither side checks more of it than the key.
 * Both files stay in the app's private folder (the no-backup one: a key restored onto another phone is paired
 * nowhere).
 */
final class AdbKey {
    final PrivateKey key;
    final X509Certificate cert;

    private AdbKey(PrivateKey key, X509Certificate cert) {
        this.key = key;
        this.cert = cert;
    }

    /** The key in @dir, made at the first call. */
    static synchronized AdbKey of(File dir) throws IOException, GeneralSecurityException {
        File k = new File(dir, "adbkey.pk8"), c = new File(dir, "adbkey.crt");
        if (k.isFile() && c.isFile()) {
            try {
                PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(k.toPath())));
                return new AdbKey(key, parse(Files.readAllBytes(c.toPath())));
            } catch (GeneralSecurityException e) {
                // unreadable: a new key, to be paired again
            }
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair pair = g.generateKeyPair();
        byte[] der = certificate(pair);
        dir.mkdirs();
        Files.write(k.toPath(), pair.getPrivate().getEncoded());
        Files.write(c.toPath(), der);
        return new AdbKey(pair.getPrivate(), parse(der));
    }

    static void delete(File dir) {
        new File(dir, "adbkey.pk8").delete();
        new File(dir, "adbkey.crt").delete();
    }

    private static X509Certificate parse(byte[] der) throws GeneralSecurityException {
        return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(der));
    }

    /**
     * The key as adb writes one into adbkey.pub and as pairing hands it over: adb's own RSA layout (the modulus
     * and R² little-endian, -1/n mod 2³²), base64, then the name the phone lists under "Paired devices".
     */
    String publicLine(String name) {
        RSAPublicKey pub = (RSAPublicKey) cert.getPublicKey();
        BigInteger n = pub.getModulus(), r32 = BigInteger.ONE.shiftLeft(32);
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + 256 + 256 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(256 / 4);
        b.putInt(r32.subtract(n.mod(r32).modInverse(r32)).intValue());
        b.put(little(n, 256));
        b.put(little(BigInteger.ONE.shiftLeft(2 * 2048).mod(n), 256));
        b.putInt(pub.getPublicExponent().intValue());
        return Base64.getEncoder().encodeToString(b.array()) + " " + name;
    }

    private static byte[] little(BigInteger v, int size) {
        byte[] big = v.toByteArray(), out = new byte[size];
        for (int i = 0; i < size && i < big.length; i++) {
            out[i] = big[big.length - 1 - i];
        }
        return out;
    }

    // -- the certificate, DER by hand: Android has no public API that writes one for a key of ours

    private static byte[] certificate(KeyPair pair) throws GeneralSecurityException {
        byte[] sha256Rsa = seq(oid(1, 2, 840, 113549, 1, 1, 11), tag(0x05));
        byte[] name = seq(rdn(oid(2, 5, 4, 6), tag(0x13, ascii("US"))), rdn(oid(2, 5, 4, 10), tag(0x0c, ascii("Android"))),
                rdn(oid(2, 5, 4, 3), tag(0x0c, ascii("Adb"))));
        long now = System.currentTimeMillis();
        byte[] extensions = seq(
                // basicConstraints, critical: CA
                seq(oid(2, 5, 29, 19), tag(0x01, new byte[] {(byte) 0xff}), tag(0x04, seq(tag(0x01, new byte[] {(byte) 0xff})))),
                // keyUsage, critical: digitalSignature, keyCertSign, cRLSign
                seq(oid(2, 5, 29, 15), tag(0x01, new byte[] {(byte) 0xff}), tag(0x04, tag(0x03, new byte[] {1, (byte) 0x86}))));
        byte[] tbs = seq(
                tag(0xa0, tag(0x02, new byte[] {2})),       // version 3
                tag(0x02, new byte[] {1}),                  // serial
                sha256Rsa,
                name,                                       // issuer
                seq(time(new Date(now - 86_400_000L)), time(new Date(now + 3650L * 86_400_000L))),
                name,                                       // subject
                pair.getPublic().getEncoded(),
                tag(0xa3, extensions));
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(pair.getPrivate());
        s.update(tbs);
        return seq(tbs, sha256Rsa, tag(0x03, cat(new byte[] {0}, s.sign())));
    }

    private static byte[] rdn(byte[] type, byte[] value) {
        return tag(0x31, seq(type, value));
    }

    /** UTCTime up to 2049, GeneralizedTime after. */
    private static byte[] time(Date d) {
        SimpleDateFormat year = new SimpleDateFormat("yyyy", Locale.ROOT);
        year.setTimeZone(TimeZone.getTimeZone("UTC"));
        boolean utc = Integer.parseInt(year.format(d)) < 2050;
        SimpleDateFormat f = new SimpleDateFormat(utc ? "yyMMddHHmmss'Z'" : "yyyyMMddHHmmss'Z'", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return tag(utc ? 0x17 : 0x18, ascii(f.format(d)));
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] oid(int... arcs) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(arcs[0] * 40 + arcs[1]);
        for (int i = 2; i < arcs.length; i++) {
            int v = arcs[i], n = 1;
            while (v >>> (7 * n) != 0) {
                n++;
            }
            for (int j = n - 1; j >= 0; j--) {
                o.write((v >>> (7 * j) & 0x7f) | (j > 0 ? 0x80 : 0));
            }
        }
        return tag(0x06, o.toByteArray());
    }

    private static byte[] seq(byte[]... parts) {
        return tag(0x30, cat(parts));
    }

    private static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            o.write(p, 0, p.length);
        }
        return o.toByteArray();
    }

    private static byte[] tag(int tag, byte[]... content) {
        byte[] c = cat(content);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(tag);
        if (c.length < 0x80) {
            o.write(c.length);
        } else if (c.length < 0x100) {
            o.write(0x81);
            o.write(c.length);
        } else {
            o.write(0x82);
            o.write(c.length >>> 8);
            o.write(c.length);
        }
        o.write(c, 0, c.length);
        return o.toByteArray();
    }
}
