package app.aura.home;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * A small QR code encoder, enough for the link a phone scans: byte mode, error correction level M,
 * versions 1 to 10 (up to 213 bytes). It follows ISO/IEC 18004 step by step, the way Project
 * Nayuki's reference encoder lays it out, and picks the mask with the lowest penalty.
 */
final class Qr {
    // per version, error correction level M
    private static final int[] ECC_PER_BLOCK = {-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26};
    private static final int[] BLOCKS = {-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5};
    private static final int LEVEL_M = 0;

    final int size;
    private final boolean[][] dark, fixed;

    static Qr encode(String text) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        for (int ver = 1; ver <= 10; ver++) {
            if (4 + (ver <= 9 ? 8 : 16) + data.length * 8 <= dataCodewords(ver) * 8) return new Qr(ver, data);
        }
        throw new IllegalArgumentException("too long for a QR code");
    }

    boolean at(int x, int y) {
        return dark[y][x];
    }

    private Qr(int ver, byte[] data) {
        size = ver * 4 + 17;
        dark = new boolean[size][size];
        fixed = new boolean[size][size];

        // the bit stream: mode, length, bytes, terminator, then alternating pad bytes
        int cap = dataCodewords(ver);
        byte[] cw = new byte[cap];
        int[] n = {0};
        put(cw, n, 4, 4);
        put(cw, n, data.length, ver <= 9 ? 8 : 16);
        for (byte b : data) put(cw, n, b & 0xFF, 8);
        put(cw, n, 0, Math.min(4, cap * 8 - n[0]));
        put(cw, n, 0, (8 - n[0] % 8) % 8);
        for (int pad = 0xEC; n[0] < cap * 8; pad ^= 0xEC ^ 0x11) put(cw, n, pad, 8);

        patterns(ver);
        place(withEcc(ver, cw));
        int best = 0;
        long least = Long.MAX_VALUE;
        for (int m = 0; m < 8; m++) {
            mask(m);
            format(m);
            long p = penalty();
            if (p < least) {
                least = p;
                best = m;
            }
            mask(m);
        }
        mask(best);
        format(best);
    }

    private static void put(byte[] out, int[] n, int value, int bits) {
        for (int i = bits - 1; i >= 0; i--, n[0]++) {
            if (((value >>> i) & 1) != 0) out[n[0] >>> 3] |= (byte) (0x80 >>> (n[0] & 7));
        }
    }

    // ---- capacity ------------------------------------------------------------------------------

    private static int rawModules(int ver) {
        int r = (16 * ver + 128) * ver + 64;
        if (ver >= 2) {
            int na = ver / 7 + 2;
            r -= (25 * na - 10) * na - 55;
            if (ver >= 7) r -= 36;
        }
        return r;
    }

    private static int dataCodewords(int ver) {
        return rawModules(ver) / 8 - ECC_PER_BLOCK[ver] * BLOCKS[ver];
    }

    // ---- error correction --------------------------------------------------------------------

    /** Splits the data into blocks, adds Reed-Solomon codewords to each, and interleaves them. */
    private static byte[] withEcc(int ver, byte[] data) {
        int blocks = BLOCKS[ver], ecc = ECC_PER_BLOCK[ver], raw = rawModules(ver) / 8;
        int shortBlocks = blocks - raw % blocks, shortLen = raw / blocks;
        byte[] div = divisor(ecc);
        byte[][] bl = new byte[blocks][];
        for (int i = 0, k = 0; i < blocks; i++) {
            int len = shortLen - ecc + (i < shortBlocks ? 0 : 1);
            byte[] dat = Arrays.copyOfRange(data, k, k + len);
            k += len;
            byte[] b = new byte[shortLen + 1];
            System.arraycopy(dat, 0, b, 0, len);
            System.arraycopy(remainder(dat, div), 0, b, b.length - ecc, ecc);
            bl[i] = b;
        }
        byte[] out = new byte[raw];
        int o = 0;
        for (int i = 0; i <= shortLen; i++) {
            for (int j = 0; j < blocks; j++) {
                // short blocks have a gap where the long ones carry one more data byte
                if (i != shortLen - ecc || j >= shortBlocks) out[o++] = bl[j][i];
            }
        }
        return out;
    }

    private static byte[] divisor(int degree) {
        byte[] r = new byte[degree];
        r[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < degree; j++) {
                r[j] = (byte) mul(r[j] & 0xFF, root);
                if (j + 1 < degree) r[j] ^= r[j + 1];
            }
            root = mul(root, 0x02);
        }
        return r;
    }

    private static byte[] remainder(byte[] data, byte[] div) {
        byte[] r = new byte[div.length];
        for (byte b : data) {
            int f = (b ^ r[0]) & 0xFF;
            System.arraycopy(r, 1, r, 0, r.length - 1);
            r[r.length - 1] = 0;
            for (int i = 0; i < r.length; i++) r[i] ^= (byte) mul(div[i] & 0xFF, f);
        }
        return r;
    }

    /** Multiplication in GF(2^8) modulo x^8 + x^4 + x^3 + x^2 + 1. */
    private static int mul(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z;
    }

    // ---- layout ------------------------------------------------------------------------------

    private void set(int x, int y, boolean d) {
        dark[y][x] = d;
        fixed[y][x] = true;
    }

    private void patterns(int ver) {
        for (int i = 0; i < size; i++) {
            set(6, i, i % 2 == 0);
            set(i, 6, i % 2 == 0);
        }
        finder(3, 3);
        finder(size - 4, 3);
        finder(3, size - 4);
        int[] al = alignment(ver);
        int n = al.length;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i == 0 && j == 0 || i == 0 && j == n - 1 || i == n - 1 && j == 0) continue;
                for (int dy = -2; dy <= 2; dy++) {
                    for (int dx = -2; dx <= 2; dx++) set(al[i] + dx, al[j] + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
                }
            }
        }
        format(0);
        if (ver >= 7) {
            int rem = ver;
            for (int i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
            int bits = ver << 12 | rem;
            for (int i = 0; i < 18; i++) {
                boolean b = ((bits >>> i) & 1) != 0;
                int a = size - 11 + i % 3, c = i / 3;
                set(a, c, b);
                set(c, a, b);
            }
        }
    }

    private void finder(int x, int y) {
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int d = Math.max(Math.abs(dx), Math.abs(dy)), xx = x + dx, yy = y + dy;
                if (xx >= 0 && xx < size && yy >= 0 && yy < size) set(xx, yy, d != 2 && d != 4);
            }
        }
    }

    private int[] alignment(int ver) {
        if (ver == 1) return new int[0];
        int n = ver / 7 + 2;
        int step = (ver * 4 + n * 2 + 1) / (n * 2 - 2) * 2;
        int[] r = new int[n];
        r[0] = 6;
        for (int i = n - 1, pos = size - 7; i >= 1; i--, pos -= step) r[i] = pos;
        return r;
    }

    private void format(int mask) {
        int data = LEVEL_M << 3 | mask, rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = (data << 10 | rem) ^ 0x5412;
        for (int i = 0; i <= 5; i++) set(8, i, bit(bits, i));
        set(8, 7, bit(bits, 6));
        set(8, 8, bit(bits, 7));
        set(7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) set(14 - i, 8, bit(bits, i));
        for (int i = 0; i < 8; i++) set(size - 1 - i, 8, bit(bits, i));
        for (int i = 8; i < 15; i++) set(8, size - 15 + i, bit(bits, i));
        set(8, size - 8, true);
    }

    /** Codewords in the zigzag of two-module columns, right to left, skipping the timing column. */
    private void place(byte[] data) {
        int i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int v = 0; v < size; v++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    int y = ((right + 1) & 2) == 0 ? size - 1 - v : v;
                    if (!fixed[y][x] && i < data.length * 8) {
                        dark[y][x] = bit(data[i >>> 3], 7 - (i & 7));
                        i++;
                    }
                }
            }
        }
    }

    private void mask(int m) {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                boolean inv;
                switch (m) {
                    case 0: inv = (x + y) % 2 == 0; break;
                    case 1: inv = y % 2 == 0; break;
                    case 2: inv = x % 3 == 0; break;
                    case 3: inv = (x + y) % 3 == 0; break;
                    case 4: inv = (x / 3 + y / 2) % 2 == 0; break;
                    case 5: inv = x * y % 2 + x * y % 3 == 0; break;
                    case 6: inv = (x * y % 2 + x * y % 3) % 2 == 0; break;
                    default: inv = ((x + y) % 2 + x * y % 3) % 2 == 0; break;
                }
                if (inv && !fixed[y][x]) dark[y][x] = !dark[y][x];
            }
        }
    }

    /** Long runs, 2x2 blocks and an uneven dark/light balance all make a code harder to read. */
    private long penalty() {
        long p = 0;
        int darkCount = 0;
        for (int a = 0; a < size; a++) {
            int rowRun = 0, colRun = 0;
            for (int b = 0; b < size; b++) {
                if (dark[a][b]) darkCount++;
                rowRun = b > 0 && dark[a][b] == dark[a][b - 1] ? rowRun + 1 : 1;
                colRun = b > 0 && dark[b][a] == dark[b - 1][a] ? colRun + 1 : 1;
                if (rowRun == 5) p += 3;
                else if (rowRun > 5) p++;
                if (colRun == 5) p += 3;
                else if (colRun > 5) p++;
                if (a > 0 && b > 0) {
                    boolean c = dark[a][b];
                    if (c == dark[a - 1][b] && c == dark[a][b - 1] && c == dark[a - 1][b - 1]) p += 3;
                }
            }
        }
        int total = size * size;
        int k = (Math.abs(darkCount * 20 - total * 10) + total - 1) / total - 1;
        return p + Math.max(0, k) * 10L;
    }

    private static boolean bit(int x, int i) {
        return ((x >>> i) & 1) != 0;
    }
}
