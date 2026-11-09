package com.asoft.ios;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/**
 * SPAKE2 over edwards25519 the way BoringSSL's spake25519 does it, which is what adb's pairing speaks
 * (AdbPairing): the points M and N from BoringSSL's seeds, the private scalar times eight, the password
 * scalar made a multiple of eight by adding the group order (BoringSSL's fix for its own missing shift),
 * and SHA-512 over the length-prefixed transcript as the key.
 *
 * Plain big-number arithmetic on affine points: four scalar multiplications per pairing, tens of
 * milliseconds, and nothing here needs constant time (the password is a code shown once, for one try).
 */
final class Spake2 {
    private static final BigInteger P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));
    /** The order of the prime-order subgroup. */
    private static final BigInteger L = BigInteger.ONE.shiftLeft(252)
            .add(new BigInteger("27742317777372353535851937790883648493"));
    private static final BigInteger D = BigInteger.valueOf(-121665)
            .multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P);
    private static final BigInteger SQRT_M1 = BigInteger.valueOf(2).modPow(P.subtract(BigInteger.ONE).shiftRight(2), P);
    private static final BigInteger[] BASE = decode(hex("5866666666666666666666666666666666666666666666666666666666666666"));
    /** BoringSSL's "edwards25519 point generation seed (M)" and "(N)" points, as it encodes them. */
    private static final BigInteger[] M = decode(hex("5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e"));
    private static final BigInteger[] N = decode(hex("10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778"));

    private final boolean alice;
    private final byte[] myName, theirName;
    private BigInteger privateKey, passwordScalar;
    private byte[] passwordHash, myMsg;

    /** @alice: the side that speaks first (adb's client); the names go into the key, each side's own first. */
    Spake2(boolean alice, byte[] myName, byte[] theirName) {
        this.alice = alice;
        this.myName = myName;
        this.theirName = theirName;
    }

    /** This side's 32-byte message for the other one. */
    byte[] message(byte[] password) {
        byte[] random = new byte[64];
        new SecureRandom().nextBytes(random);
        return message(password, random);
    }

    /** (@random: 64 bytes, given by the tests.) */
    byte[] message(byte[] password, byte[] random) {
        // times the cofactor, so that it goes away in the other side's point
        privateKey = little(random).mod(L).shiftLeft(3);
        passwordHash = sha512(password);
        BigInteger s = little(passwordHash).mod(L);
        // BoringSSL's way to a multiple of eight: the order is odd, so adding it flips the lowest bit, twice it
        // the next one, four times it the third; the point it stands for in the prime-order group stays
        if (s.testBit(0)) {
            s = s.add(L);
        }
        if (s.testBit(1)) {
            s = s.add(L.shiftLeft(1));
        }
        if (s.testBit(2)) {
            s = s.add(L.shiftLeft(2));
        }
        passwordScalar = s;
        myMsg = encode(add(mul(privateKey, BASE), mul(passwordScalar, alice ? M : N)));
        return myMsg.clone();
    }

    /** The 64-byte key both sides get when the passwords were the same; null when @theirMsg is no point. */
    byte[] key(byte[] theirMsg) {
        if (myMsg == null || theirMsg.length != 32) {
            return null;
        }
        BigInteger[] q = decode(theirMsg);
        if (q == null) {
            return null;
        }
        BigInteger[] mask = mul(passwordScalar, alice ? N : M);
        BigInteger[] shared = mul(privateKey, add(q, new BigInteger[] {P.subtract(mask[0]).mod(P), mask[1]}));
        MessageDigest sha = digest();
        prefixed(sha, alice ? myName : theirName);
        prefixed(sha, alice ? theirName : myName);
        prefixed(sha, alice ? myMsg : theirMsg);
        prefixed(sha, alice ? theirMsg : myMsg);
        prefixed(sha, encode(shared));
        prefixed(sha, passwordHash);
        return sha.digest();
    }

    private static void prefixed(MessageDigest sha, byte[] data) {
        byte[] len = new byte[8];
        for (int i = 0; i < 8; i++) {
            len[i] = (byte) ((long) data.length >>> (8 * i));
        }
        sha.update(len);
        sha.update(data);
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-512");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] sha512(byte[] data) {
        return digest().digest(data);
    }

    /** -x² + y² = 1 + d·x²·y²; the formula holds for every pair of points on this curve, the neutral one too. */
    private static BigInteger[] add(BigInteger[] a, BigInteger[] b) {
        BigInteger xx = a[0].multiply(b[0]).mod(P), yy = a[1].multiply(b[1]).mod(P);
        BigInteger t = D.multiply(xx).mod(P).multiply(yy).mod(P);
        BigInteger x = a[0].multiply(b[1]).add(a[1].multiply(b[0])).multiply(BigInteger.ONE.add(t).modInverse(P)).mod(P);
        BigInteger y = yy.add(xx).multiply(BigInteger.ONE.subtract(t).mod(P).modInverse(P)).mod(P);
        return new BigInteger[] {x, y};
    }

    private static BigInteger[] mul(BigInteger k, BigInteger[] p) {
        BigInteger[] r = {BigInteger.ZERO, BigInteger.ONE};
        for (int i = k.bitLength() - 1; i >= 0; i--) {
            r = add(r, r);
            if (k.testBit(i)) {
                r = add(r, p);
            }
        }
        return r;
    }

    /** The point's 32 bytes: y, little-endian, with x's lowest bit on top. */
    private static byte[] encode(BigInteger[] p) {
        byte[] out = new byte[32];
        byte[] y = p[1].toByteArray();
        for (int i = 0; i < y.length && i < 32; i++) {
            out[i] = y[y.length - 1 - i];
        }
        if (p[0].testBit(0)) {
            out[31] |= (byte) 0x80;
        }
        return out;
    }

    /** null when the bytes are no point of the curve. */
    private static BigInteger[] decode(byte[] s) {
        byte[] yb = s.clone();
        boolean odd = (yb[31] & 0x80) != 0;
        yb[31] &= 0x7f;
        BigInteger y = little(yb);
        if (y.compareTo(P) >= 0) {
            return null;
        }
        BigInteger yy = y.multiply(y).mod(P);
        BigInteger xx = yy.subtract(BigInteger.ONE).multiply(D.multiply(yy).add(BigInteger.ONE).modInverse(P)).mod(P);
        BigInteger x = xx.modPow(P.add(BigInteger.valueOf(3)).shiftRight(3), P);
        if (!x.multiply(x).mod(P).equals(xx)) {
            x = x.multiply(SQRT_M1).mod(P);
        }
        if (!x.multiply(x).mod(P).equals(xx)) {
            return null;
        }
        if (x.testBit(0) != odd) {
            x = P.subtract(x).mod(P);
        }
        return new BigInteger[] {x, y};
    }

    private static BigInteger little(byte[] b) {
        byte[] big = new byte[b.length];
        for (int i = 0; i < b.length; i++) {
            big[i] = b[b.length - 1 - i];
        }
        return new BigInteger(1, big);
    }

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }

    /** For the PC test: x and y of BoringSSL's M and N, to compare with the numbers in its source. */
    static String points() {
        return "M " + M[0] + " " + M[1] + "\nN " + N[0] + " " + N[1];
    }
}
