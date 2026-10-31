package app.aura.home;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/**
 * SPAKE2 over Ed25519, exactly as BoringSSL does it (crypto/curve25519/spake25519.cc), which is what
 * Android's wireless debugging pairs with: both sides prove they know the 6-digit code without
 * sending it, and end with the same 64-byte key. Plain Java, so it runs (and is checked) anywhere.
 *
 * The curve arithmetic is BigInteger in extended coordinates: slow by crypto standards, a few
 * hundred milliseconds once per pairing, which is fine.
 */
final class Spake2 {
    static final BigInteger Q = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));
    static final BigInteger L = BigInteger.ONE.shiftLeft(252).add(new BigInteger("27742317777372353535851937790883648493"));
    private static final BigInteger D = BigInteger.valueOf(-121665).multiply(BigInteger.valueOf(121666).modInverse(Q)).mod(Q);
    private static final BigInteger I = BigInteger.valueOf(2).modPow(Q.subtract(BigInteger.ONE).shiftRight(2), Q);
    private static final BigInteger TWO = BigInteger.valueOf(2);

    /** A point in extended coordinates (X:Y:Z:T), x = X/Z, y = Y/Z, xy = T/Z. */
    static final class Point {
        final BigInteger x, y, z, t;

        Point(BigInteger x, BigInteger y, BigInteger z, BigInteger t) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.t = t;
        }

        static Point affine(BigInteger x, BigInteger y) {
            return new Point(x.mod(Q), y.mod(Q), BigInteger.ONE, x.multiply(y).mod(Q));
        }

        Point add(Point o) {
            // add-2008-hwcd-3 for a = -1
            BigInteger a = y.subtract(x).multiply(o.y.subtract(o.x)).mod(Q);
            BigInteger b = y.add(x).multiply(o.y.add(o.x)).mod(Q);
            BigInteger c = t.multiply(TWO).multiply(D).multiply(o.t).mod(Q);
            BigInteger d = z.multiply(TWO).multiply(o.z).mod(Q);
            BigInteger e = b.subtract(a), f = d.subtract(c), g = d.add(c), h = b.add(a);
            return new Point(e.multiply(f).mod(Q), g.multiply(h).mod(Q), f.multiply(g).mod(Q), e.multiply(h).mod(Q));
        }

        Point negate() {
            return new Point(x.negate().mod(Q), y, z, t.negate().mod(Q));
        }

        /** {@code k}·P, for any k ≥ 0 (no reduction: the bits are used as they are). */
        Point times(BigInteger k) {
            Point r = Point.affine(BigInteger.ZERO, BigInteger.ONE), p = this;
            for (int i = 0; i < k.bitLength(); i++) {
                if (k.testBit(i)) r = r.add(p);
                p = p.add(p);
            }
            return r;
        }

        BigInteger[] toAffine() {
            BigInteger zi = z.modInverse(Q);
            return new BigInteger[]{x.multiply(zi).mod(Q), y.multiply(zi).mod(Q)};
        }

        /** The 32-byte encoding: y little-endian, the sign of x in the top bit. */
        byte[] encode() {
            BigInteger[] a = toAffine();
            byte[] out = le(a[1], 32);
            if (a[0].testBit(0)) out[31] |= (byte) 0x80;
            return out;
        }
    }

    static final Point B;
    /** The two points derived from fixed seeds, as BoringSSL's comment and tables have them. */
    static final Point M, N;

    static {
        BigInteger by = BigInteger.valueOf(4).multiply(BigInteger.valueOf(5).modInverse(Q)).mod(Q);
        B = Point.affine(xrecover(by), by);
        M = genpoint("edwards25519 point generation seed (M)");
        N = genpoint("edwards25519 point generation seed (N)");
    }

    /** ed25519.py's xrecover: the even x for y (not checked to be on the curve). */
    private static BigInteger xrecover(BigInteger y) {
        BigInteger xx = y.multiply(y).subtract(BigInteger.ONE).multiply(D.multiply(y).multiply(y).add(BigInteger.ONE).modInverse(Q)).mod(Q);
        BigInteger x = xx.modPow(Q.add(BigInteger.valueOf(3)).shiftRight(3), Q);
        if (!x.multiply(x).subtract(xx).mod(Q).equals(BigInteger.ZERO)) x = x.multiply(I).mod(Q);
        if (x.testBit(0)) x = Q.subtract(x);
        return x;
    }

    private static boolean onCurve(BigInteger x, BigInteger y) {
        BigInteger x2 = x.multiply(x), y2 = y.multiply(y);
        return x2.negate().add(y2).subtract(BigInteger.ONE).subtract(D.multiply(x2).multiply(y2)).mod(Q).equals(BigInteger.ZERO);
    }

    /** ed25519.py's decodepoint; null when the bytes aren't a point on the curve. */
    static Point decode(byte[] s) {
        byte[] c = s.clone();
        boolean sign = (c[31] & 0x80) != 0;
        c[31] &= 0x7f;
        BigInteger y = fromLe(c);
        BigInteger x = xrecover(y);
        if (x.testBit(0) != sign) x = Q.subtract(x);
        if (!onCurve(x, y)) return null;
        return Point.affine(x, y);
    }

    /** The script in spake25519.cc: SHA-256 of the seed, hashed again until it decodes to a point. */
    private static Point genpoint(String seed) {
        byte[] v = sha(seed.getBytes(StandardCharsets.US_ASCII), "SHA-256");
        while (true) {
            Point p = decode(v);
            if (p != null) return p;
            v = sha(v, "SHA-256");
        }
    }

    // ---- the protocol --------------------------------------------------------------------------

    private final boolean alice;
    private final byte[] myName, theirName, myMsg = new byte[32], passwordHash;
    private final BigInteger privateKey, passwordScalar;

    /** {@code alice} is adb's pairing client (Aura), bob its server (the TV's wireless debugging). */
    Spake2(boolean alice, byte[] myName, byte[] theirName, byte[] password, SecureRandom rnd) {
        this.alice = alice;
        this.myName = myName;
        this.theirName = theirName;
        byte[] r = new byte[64];
        rnd.nextBytes(r);
        // x25519_sc_reduce, then the cofactor (8) multiplied in, as BoringSSL does
        privateKey = fromLe(r).mod(L).shiftLeft(3);
        passwordHash = sha(password, "SHA-512");
        BigInteger ps = fromLe(passwordHash).mod(L);
        // BoringSSL's "password scalar hack": multiples of the order added until it's a multiple of eight
        if (ps.testBit(0)) ps = ps.add(L);
        if (ps.testBit(1)) ps = ps.add(L.shiftLeft(1));
        if (ps.testBit(2)) ps = ps.add(L.shiftLeft(2));
        passwordScalar = ps;
        Point mask = (alice ? M : N).times(passwordScalar);
        System.arraycopy(B.times(privateKey).add(mask).encode(), 0, myMsg, 0, 32);
    }

    byte[] message() {
        return myMsg.clone();
    }

    /** The shared 64-byte key from the other side's message; null when it isn't a point. */
    byte[] finish(byte[] theirMsg) {
        Point q = decode(theirMsg);
        if (q == null) return null;
        Point unmasked = q.add((alice ? N : M).times(passwordScalar).negate());
        byte[] shared = unmasked.times(privateKey).encode();
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-512");
            if (alice) {
                prefixed(sha, myName);
                prefixed(sha, theirName);
                prefixed(sha, myMsg);
                prefixed(sha, theirMsg);
            } else {
                prefixed(sha, theirName);
                prefixed(sha, myName);
                prefixed(sha, theirMsg);
                prefixed(sha, myMsg);
            }
            prefixed(sha, shared);
            prefixed(sha, passwordHash);
            return sha.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void prefixed(MessageDigest sha, byte[] data) {
        long n = data.length;
        byte[] len = new byte[8];
        for (int i = 0; i < 8; i++) len[i] = (byte) (n >>> (8 * i));
        sha.update(len);
        sha.update(data);
    }

    // ---- bytes ---------------------------------------------------------------------------------

    static byte[] sha(byte[] d, String alg) {
        try {
            return MessageDigest.getInstance(alg).digest(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static BigInteger fromLe(byte[] b) {
        byte[] be = new byte[b.length + 1];
        for (int i = 0; i < b.length; i++) be[b.length - i] = b[i];
        return new BigInteger(be);
    }

    static byte[] le(BigInteger v, int size) {
        byte[] big = v.toByteArray(), out = new byte[size];
        for (int i = 0; i < size && i < big.length; i++) out[i] = big[big.length - 1 - i];
        return out;
    }
}
