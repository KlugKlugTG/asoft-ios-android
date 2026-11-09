package com.asoft.ios;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLSocket;

/**
 * adb's pairing (Android 11's "Pair device with pairing code"), the client's side: over TLS, both ends prove
 * with SPAKE2 that they know the six digits and this session's exported key, and under the key that gives
 * (HKDF-SHA256 to AES-128-GCM) the client hands its adb key over and gets the phone's id back. After it the
 * phone lists the key under "Paired devices" and lets it connect (AdbConnection).
 *
 * Every packet: version 1, a type (0: SPAKE2's message, 1: the peer's info), the length big-endian, the bytes.
 */
final class AdbPairing {
    /** adb's names for the two sides, each with its closing zero byte. */
    static final byte[] CLIENT = "adb pair client\u0000".getBytes(StandardCharsets.US_ASCII);
    static final byte[] SERVER = "adb pair server\u0000".getBytes(StandardCharsets.US_ASCII);
    static final int SPAKE2_MSG = 0, PEER_INFO = 1;
    /** adb's PeerInfo: one byte of type, then the text, zeros up to this size. */
    static final int PEER_INFO_SIZE = 8192;
    static final int RSA_PUB_KEY = 0, DEVICE_GUID = 1;

    private AdbPairing() {}

    /** A wrong code: the phone closes the session, or its answer does not open. */
    static final class WrongCode extends IOException {
        WrongCode(Exception how) {
            super("the pairing code did not match", how);
        }
    }

    /**
     * Pairs @key, listed on the phone as @name, with the pairing dialog at @host:@port. Returns the phone's id.
     * A wrong code ends the phone's dialog too: the next try needs a new code.
     */
    static String pair(AdbKey key, String name, String host, int port, String code) throws IOException {
        Socket plain = new Socket();
        try {
            plain.connect(new InetSocketAddress(host, port), 10_000);
            plain.setSoTimeout(20_000);
            plain.setTcpNoDelay(true);
            try (SSLSocket tls = AdbTls.client(plain, key)) {
                return exchange(new DataInputStream(tls.getInputStream()), tls.getOutputStream(), true,
                        password(code, AdbTls.exported(tls)), info(RSA_PUB_KEY, key.publicLine(name)));
            }
        } finally {
            plain.close();
        }
    }

    static byte[] password(String code, byte[] exported) {
        byte[] digits = code.getBytes(StandardCharsets.US_ASCII);
        byte[] out = new byte[digits.length + exported.length];
        System.arraycopy(digits, 0, out, 0, digits.length);
        System.arraycopy(exported, 0, out, digits.length, exported.length);
        return out;
    }

    static byte[] info(int type, String text) {
        byte[] out = new byte[PEER_INFO_SIZE];
        byte[] t = text.getBytes(StandardCharsets.UTF_8);
        out[0] = (byte) type;
        System.arraycopy(t, 0, out, 1, Math.min(t.length, PEER_INFO_SIZE - 2));
        return out;
    }

    /** The two rounds, from either side (@client false: the PC test's stand-in for the phone). Returns the peer's text. */
    static String exchange(DataInputStream in, OutputStream out, boolean client, byte[] password, byte[] myInfo)
            throws IOException {
        try {
            Spake2 spake = new Spake2(client, client ? CLIENT : SERVER, client ? SERVER : CLIENT);
            write(out, SPAKE2_MSG, spake.message(password));
            byte[] secret = spake.key(read(in, SPAKE2_MSG));
            if (secret == null) {
                throw new IOException("the pairing's SPAKE2 message was not a point");
            }
            SecretKeySpec aes = new SecretKeySpec(hkdf(secret, "adb pairing_auth aes-128-gcm key", 16), "AES");
            write(out, PEER_INFO, gcm(Cipher.ENCRYPT_MODE, aes, 0, myInfo));
            byte[] theirs;
            try {
                theirs = gcm(Cipher.DECRYPT_MODE, aes, 0, read(in, PEER_INFO));
            } catch (java.net.SocketTimeoutException e) {
                throw e;
            } catch (IOException | GeneralSecurityException e) {
                // the peer could not open our packet and hung up, or we cannot open its one: the keys differ
                throw new WrongCode(e);
            }
            int end = 1;
            while (end < theirs.length && theirs[end] != 0) {
                end++;
            }
            return new String(theirs, 1, end - 1, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    private static void write(OutputStream out, int type, byte[] payload) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(6 + payload.length).order(ByteOrder.BIG_ENDIAN);
        b.put((byte) 1).put((byte) type).putInt(payload.length).put(payload);
        out.write(b.array());
        out.flush();
    }

    private static byte[] read(DataInputStream in, int type) throws IOException {
        int version = in.readUnsignedByte(), got = in.readUnsignedByte(), size = in.readInt();
        if (version != 1 || got != type || size <= 0 || size > 2 * PEER_INFO_SIZE) {
            throw new IOException("pairing: unexpected packet (version " + version + ", type " + got + ", " + size + " bytes)");
        }
        byte[] b = new byte[size];
        in.readFully(b);
        return b;
    }

    /** RFC 5869 without a salt, one block of output at most. */
    private static byte[] hkdf(byte[] secret, String info, int size) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(new byte[32], "HmacSHA256"));
        byte[] prk = mac.doFinal(secret);
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        mac.update(info.getBytes(StandardCharsets.US_ASCII));
        mac.update((byte) 1);
        return java.util.Arrays.copyOf(mac.doFinal(), size);
    }

    /** adb's nonce: the packet's number in its direction (each side sends one), little-endian in twelve bytes. */
    private static byte[] gcm(int mode, SecretKeySpec key, long sequence, byte[] data) throws GeneralSecurityException {
        byte[] nonce = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putLong(sequence).array();
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(mode, key, new GCMParameterSpec(128, nonce));
        return c.doFinal(data);
    }
}
