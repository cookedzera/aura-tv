package app.aura.home;

import android.content.Context;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAKeyGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.Cipher;

/**
 * Aura talking to its own TV the way a computer does. With network debugging on, Android's
 * debugging service listens on port 5555 at every address, the TV's own included, so Aura can
 * connect to itself. Android asks once ("Allow debugging?"); after Always allow, Aura can run what
 * the setup script runs from a PC: grant its own permissions, become home, compile itself ahead of
 * time, and stop an app for real. The key never leaves the app's private storage.
 *
 * It also tells when a TV answers without asking at all, which leaves it open to anything on the
 * same Wi-Fi (how the ADB.Miner botnet spread across TVs in 2018).
 */
final class Adb {
    static final int PORT = 5555;
    // the protocol: 24-byte little-endian headers, then the payload
    private static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f, OKAY = 0x59414b4f,
            CLSE = 0x45534c43, WRTE = 0x45545257;
    private static final int VERSION = 0x01000001, MAX_DATA = 256 * 1024;
    private static final int TOKEN = 1, SIGNATURE = 2, PUBLIC_KEY = 3;
    /** ASN.1 DigestInfo for SHA-1: Android checks the token as if it were a SHA-1 digest. */
    private static final byte[] SHA1_INFO = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};
    private static final Charset UTF8 = Charset.forName("UTF-8");
    // compiling a big app ahead of time can take minutes on a slow TV, printing nothing until done
    // finding the remote and reading Android's question takes a while (a minute wasn't enough on test)
    private static final int REPLY_MS = 8000, ASK_MS = 180_000, RUN_MS = 600_000;

    /** How a connection ended: in, in without being asked, debugging off, refused, or something else. */
    static final String OK = "ok", OPEN_DOOR = "open", OFF = "off", DENIED = "denied", FAILED = "failed";

    private Adb() { }

    /** Android said no, or nobody answered its question in time. */
    static final class Denied extends IOException {
        Denied(String m) {
            super(m);
        }
    }

    // ---- one connection ---------------------------------------------------------------------

    static final class Link implements Closeable {
        private final Socket socket;
        private final DataInputStream in;
        private final OutputStream out;
        private int nextId = 1;
        /** The TV let us in without asking for any key. */
        boolean unguarded;

        private Link(Socket s) throws IOException {
            socket = s;
            in = new DataInputStream(s.getInputStream());
            out = s.getOutputStream();
        }

        /**
         * Connects and signs in. With {@code mayAsk}, a key Android doesn't know yet is offered, and
         * Android puts its question on the TV; without, an unknown key just fails, quietly.
         */
        static Link open(String host, int port, KeyPair key, String name, boolean mayAsk) throws IOException {
            Socket s = new Socket();
            s.connect(new InetSocketAddress(host, port), 3000);
            Link l = new Link(s);
            try {
                s.setSoTimeout(REPLY_MS);
                l.send(CNXN, VERSION, MAX_DATA, "host::\0".getBytes(UTF8));
                boolean signed = false, offered = false, asked = false;
                while (true) {
                    Msg m = l.read();
                    if (m.cmd == CNXN) {
                        l.unguarded = !asked;
                        return l;
                    }
                    if (m.cmd != AUTH || m.arg0 != TOKEN) continue;
                    asked = true;
                    if (!signed) {
                        signed = true;
                        l.send(AUTH, SIGNATURE, 0, sign(key.getPrivate(), m.data));
                    } else if (mayAsk && !offered) {
                        offered = true;
                        l.send(AUTH, PUBLIC_KEY, 0, (base64(publicKey((RSAPublicKey) key.getPublic())) + " " + name + "\0").getBytes(UTF8));
                        // the person has to find the question and answer it
                        s.setSoTimeout(ASK_MS);
                    } else {
                        throw new Denied("key not accepted");
                    }
                }
            } catch (SocketTimeoutException | EOFException e) {
                l.close();
                throw new Denied("no answer");
            } catch (IOException | RuntimeException e) {
                l.close();
                throw e;
            }
        }

        /** Runs one shell command and returns what it printed. */
        String run(String command) throws IOException {
            int local = nextId++, remote = 0;
            socket.setSoTimeout(RUN_MS);
            send(OPEN, local, 0, ("shell:" + command + "\0").getBytes(UTF8));
            ByteArrayOutputStream got = new ByteArrayOutputStream();
            while (true) {
                Msg m = read();
                if (m.arg1 != local) continue;
                if (m.cmd == OKAY) {
                    remote = m.arg0;
                } else if (m.cmd == WRTE) {
                    got.write(m.data);
                    send(OKAY, local, m.arg0, null);
                } else if (m.cmd == CLSE) {
                    if (remote != 0) send(CLSE, local, remote, null);
                    return got.toString("UTF-8");
                }
            }
        }

        private void send(int cmd, int arg0, int arg1, byte[] data) throws IOException {
            int n = data == null ? 0 : data.length, sum = 0;
            for (int i = 0; i < n; i++) sum += data[i] & 0xff;
            ByteBuffer b = ByteBuffer.allocate(24 + n).order(ByteOrder.LITTLE_ENDIAN);
            b.putInt(cmd).putInt(arg0).putInt(arg1).putInt(n).putInt(sum).putInt(~cmd);
            if (n > 0) b.put(data);
            out.write(b.array());
            out.flush();
        }

        private Msg read() throws IOException {
            byte[] h = new byte[24];
            in.readFully(h);
            ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
            Msg m = new Msg();
            m.cmd = b.getInt();
            m.arg0 = b.getInt();
            m.arg1 = b.getInt();
            int n = b.getInt();
            if (n < 0 || n > MAX_DATA * 4) throw new IOException("bad length " + n);
            m.data = new byte[n];
            in.readFully(m.data);
            return m;
        }

        @Override
        public void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static final class Msg {
        int cmd, arg0, arg1;
        byte[] data;
    }

    // ---- the key ------------------------------------------------------------------------------

    /** PKCS#1 v1.5 over SHA-1's DigestInfo and the 20-byte token: what adbd's RSA_verify expects. */
    static byte[] sign(PrivateKey key, byte[] token) throws IOException {
        try {
            Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            c.init(Cipher.ENCRYPT_MODE, key);
            byte[] all = new byte[SHA1_INFO.length + token.length];
            System.arraycopy(SHA1_INFO, 0, all, 0, SHA1_INFO.length);
            System.arraycopy(token, 0, all, SHA1_INFO.length, token.length);
            return c.doFinal(all);
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    /**
     * The public key in Android's own layout (as in adbkey.pub): word count, n0inv, the modulus and
     * R² mod n little-endian, then the exponent.
     */
    static byte[] publicKey(RSAPublicKey k) {
        BigInteger n = k.getModulus();
        int bytes = (n.bitLength() + 7) / 8;
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = r32.subtract(n.mod(r32).modInverse(r32));
        BigInteger rr = BigInteger.ONE.shiftLeft(bytes * 16).mod(n);
        ByteBuffer b = ByteBuffer.allocate(12 + bytes * 2).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(bytes / 4).putInt(n0inv.intValue()).put(little(n, bytes)).put(little(rr, bytes));
        b.putInt(k.getPublicExponent().intValue());
        return b.array();
    }

    private static byte[] little(BigInteger v, int size) {
        byte[] big = v.toByteArray(), out = new byte[size];
        for (int i = 0; i < size && i < big.length; i++) out[i] = big[big.length - 1 - i];
        return out;
    }

    private static final char[] B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    /** Plain base64 with padding, one line (android.util.Base64 isn't there on a desktop test). */
    static String base64(byte[] d) {
        StringBuilder s = new StringBuilder((d.length + 2) / 3 * 4);
        for (int i = 0; i < d.length; i += 3) {
            int v = (d[i] & 0xff) << 16 | (i + 1 < d.length ? (d[i + 1] & 0xff) << 8 : 0) | (i + 2 < d.length ? d[i + 2] & 0xff : 0);
            s.append(B64[v >> 18 & 63]).append(B64[v >> 12 & 63]);
            s.append(i + 1 < d.length ? B64[v >> 6 & 63] : '=').append(i + 2 < d.length ? B64[v & 63] : '=');
        }
        return s.toString();
    }

    /** Aura's own key, made once (a second or two on a slow TV) and kept in private storage. */
    static synchronized KeyPair key(File dir) throws IOException {
        File priv = new File(dir, "key"), pub = new File(dir, "key.pub");
        try {
            KeyFactory f = KeyFactory.getInstance("RSA");
            if (priv.isFile() && pub.isFile()) {
                return new KeyPair(f.generatePublic(new X509EncodedKeySpec(readAll(pub))),
                        f.generatePrivate(new PKCS8EncodedKeySpec(readAll(priv))));
            }
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(new RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4));
            KeyPair kp = g.generateKeyPair();
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("no key folder");
            writeAll(priv, kp.getPrivate().getEncoded());
            writeAll(pub, kp.getPublic().getEncoded());
            return kp;
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    private static byte[] readAll(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) > 0; ) o.write(buf, 0, n);
            return o.toByteArray();
        }
    }

    private static void writeAll(File f, byte[] d) throws IOException {
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write(d);
        }
    }

    // ---- for Aura -----------------------------------------------------------------------------

    private static File dir(Context c) {
        return new File(c.getFilesDir(), "adb");
    }

    private static Link link(Context c, boolean mayAsk) throws IOException {
        return Link.open("127.0.0.1", PORT, key(dir(c)), "Aura@" + Build.MODEL.replace(' ', '_'), mayAsk);
    }

    /** Whether Android let Aura in last time. */
    static boolean on(Context c) {
        return Prefs.device(c).getBoolean("adb_ok", false);
    }

    /** Whether the last connection came in without Android asking for a key. */
    static boolean unguarded(Context c) {
        return Prefs.device(c).getBoolean("adb_open", false);
    }

    /**
     * Connects (off the main thread), asking Android to trust Aura's key if it doesn't yet: Android
     * shows its question on the TV and this waits up to a minute for the answer.
     */
    static String connect(Context c) {
        String state;
        try (Link l = link(c, true)) {
            state = l.unguarded ? OPEN_DOOR : OK;
        } catch (ConnectException e) {
            state = OFF;
        } catch (Denied e) {
            state = DENIED;
        } catch (IOException | RuntimeException e) {
            state = FAILED;
        }
        boolean in = state.equals(OK) || state.equals(OPEN_DOOR);
        Prefs.device(c).edit().putBoolean("adb_ok", in).putBoolean("adb_open", state.equals(OPEN_DOOR)).apply();
        return state;
    }

    /**
     * Runs commands over one connection, off the main thread, never putting a question on the
     * screen. Null when Aura isn't let in (debugging switched off, or Android forgot the key).
     */
    static List<String> run(Context c, String... commands) {
        if (!on(c)) return null;
        try (Link l = link(c, false)) {
            List<String> out = new ArrayList<>();
            for (String cmd : commands) out.add(l.run(cmd));
            return out;
        } catch (Denied e) {
            Prefs.device(c).edit().putBoolean("adb_ok", false).apply();
            return null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** What setup-aura.ps1 does from a PC, minus installing: each step with what it switches on. */
    static String[][] setup(Context c) {
        String p = c.getPackageName();
        List<String[]> s = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 24) s.add(new String[]{"Home", "cmd package set-home-activity " + p + "/" + HomeActivity.class.getName()});
        s.add(new String[]{"Block ads, animations", "pm grant " + p + " android.permission.WRITE_SECURE_SETTINGS"});
        s.add(new String[]{"Wi-Fi name", "pm grant " + p + " android.permission.DUMP"});
        s.add(new String[]{"Memory", "pm grant " + p + " android.permission.PACKAGE_USAGE_STATS"});
        s.add(new String[]{"Smart Stack, Today", "appops set " + p + " GET_USAGE_STATS allow"});
        if (Build.VERSION.SDK_INT >= 23) s.add(new String[]{"Wi-Fi sheet", "pm grant " + p + " android.permission.ACCESS_FINE_LOCATION"});
        if (Build.VERSION.SDK_INT >= 23) s.add(new String[]{"Continue watching", "pm grant " + p + " android.permission.READ_TV_LISTINGS"});
        if (Build.VERSION.SDK_INT >= 24) s.add(new String[]{"Now playing", "cmd notification allow_listener " + p + "/" + MediaWatch.class.getName()});
        s.add(new String[]{"Standby", "dpm set-active-admin --user 0 " + p + "/" + SleepAdmin.class.getName()});
        s.add(new String[]{"Screensaver", "settings put secure screensaver_components " + p + "/" + Ambient.class.getName()});
        // the slowest: Aura compiled ahead of time opens and scrolls quicker
        if (Build.VERSION.SDK_INT >= 24) s.add(new String[]{"Speed", "cmd package compile -m speed -f " + p});
        // very last, and only when needed: Android restarts an app whose permission to install changes
        if (Build.VERSION.SDK_INT >= 26 && !c.getPackageManager().canRequestPackageInstalls()) {
            s.add(new String[]{"Updates", "appops set " + p + " REQUEST_INSTALL_PACKAGES allow"});
        }
        return s.toArray(new String[0][]);
    }

    /** Stops an app outright, as Android's own Force stop does. False when Aura can't. */
    static boolean forceStop(Context c, String pkg) {
        // a package name, and nothing a shell would read as more
        if (!pkg.matches("[A-Za-z0-9_.]+")) return false;
        return run(c, "am force-stop " + pkg) != null;
    }
}
