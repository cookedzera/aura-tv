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
            CLSE = 0x45534c43, WRTE = 0x45545257, STLS = 0x534c5453, STLS_VERSION = 0x01000000;
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
        private Socket socket;
        private DataInputStream in;
        private OutputStream out;
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
            InetSocketAddress at = new InetSocketAddress(host, port);
            s.connect(at, 3000);
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
                    // wireless debugging: the rest goes over TLS, with the key the TV knows from pairing
                    if (m.cmd == STLS && android.os.Build.VERSION.SDK_INT >= 30) {
                        l.send(STLS, STLS_VERSION, 0, null);
                        javax.net.ssl.SSLSocket t = AdbTls.wrap(l.socket, at, key);
                        l.socket = t;
                        l.in = new DataInputStream(t.getInputStream());
                        l.out = t.getOutputStream();
                        // paired by code, never an open door
                        asked = true;
                        continue;
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
                // only an unanswered prompt is a refusal; a background call that timed out keeps the key trusted
                throw mayAsk ? new Denied("no answer") : e;
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

    private static String name() {
        return "Aura@" + Build.MODEL.replace(' ', '_');
    }

    /**
     * Network debugging on port 5555 where the TV offers it; else wireless debugging (Android 11+),
     * once paired, at whatever port it has now: the last one tried first, then found over mDNS.
     */
    private static Link link(Context c, boolean mayAsk) throws IOException {
        try {
            return Link.open("127.0.0.1", PORT, key(dir(c)), name(), mayAsk);
        } catch (IOException e) {
            if (Build.VERSION.SDK_INT < 30 || !Prefs.device(c).getBoolean("adb_paired", false)) throw e;
        }
        int last = Prefs.device(c).getInt("adb_tls_port", 0);
        if (last > 0) {
            try {
                return Link.open("127.0.0.1", last, key(dir(c)), name(), false);
            } catch (IOException ignored) {
            }
        }
        InetSocketAddress at = AdbTls.find(c, AdbTls.CONNECT, 4000);
        if (at == null) throw new ConnectException("wireless debugging is off");
        Prefs.device(c).edit().putInt("adb_tls_port", at.getPort()).apply();
        return Link.open(at.getAddress().getHostAddress(), at.getPort(), key(dir(c)), name(), false);
    }

    /**
     * Pairs with the TV's wireless debugging while its "Pair device with pairing code" dialog is
     * open, with the code it shows (typed on the phone: the dialog closes if you leave it). Off the
     * main thread. Then connects, as {@link #connect} does.
     */
    static String pair(Context c, String code) {
        if (Build.VERSION.SDK_INT < 30 || !code.matches("[0-9]{6}")) return FAILED;
        InetSocketAddress at = AdbTls.find(c, AdbTls.PAIRING, 6000);
        if (at == null) return OFF;
        try {
            AdbTls.pair(at, code, key(dir(c)), name());
        } catch (Denied e) {
            return DENIED;
        } catch (IOException | RuntimeException e) {
            return FAILED;
        }
        Prefs.device(c).edit().putBoolean("adb_paired", true).apply();
        // the dialog closes on its own; the connection service may take a moment to appear
        for (int i = 0; i < 5; i++) {
            String s = connect(c);
            if (!s.equals(OFF)) return s;
            android.os.SystemClock.sleep(1500);
        }
        return OFF;
    }

    /** Whether this TV has wireless debugging (Android 11+) to pair with, should network debugging be missing. */
    static boolean wireless() {
        return Build.VERSION.SDK_INT >= 30;
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
        if (Build.VERSION.SDK_INT >= 24) s.add(new String[]{c.getString(R.string.home), "cmd package set-home-activity " + p + "/" + HomeActivity.class.getName()});
        // Google TV and Fire TV keep Home for their own launcher regardless: the Home button service takes it
        if (HomeRole.stubborn(c) && !HomeKeys.on(c)) s.add(new String[]{c.getString(R.string.home_button), HomeKeys.enableCommand(c)});
        // Android 13+ greys out the Accessibility switch of an app installed from a file ("restricted
        // setting"); this lifts it, so the Home button can be switched by hand later too
        if (Build.VERSION.SDK_INT >= 33) s.add(new String[]{c.getString(R.string.accessibility), "appops set " + p + " ACCESS_RESTRICTED_SETTINGS allow"});
        s.add(new String[]{c.getString(R.string.block_ads_animations), "pm grant " + p + " android.permission.WRITE_SECURE_SETTINGS"});
        s.add(new String[]{c.getString(R.string.wi_fi_name), "pm grant " + p + " android.permission.DUMP"});
        // the firmware takes the remote's profile button for Google TV's picker: the log is how Aura knows
        if (Safety.hasGoogleTvHome(c)) s.add(new String[]{c.getString(R.string.profile_button), "pm grant " + p + " android.permission.READ_LOGS"});
        s.add(new String[]{c.getString(R.string.memory), "pm grant " + p + " android.permission.PACKAGE_USAGE_STATS"});
        s.add(new String[]{c.getString(R.string.smart_stack_today), "appops set " + p + " GET_USAGE_STATS allow"});
        if (Build.VERSION.SDK_INT >= 23) s.add(new String[]{c.getString(R.string.wi_fi_sheet), "pm grant " + p + " android.permission.ACCESS_FINE_LOCATION"});
        if (Build.VERSION.SDK_INT >= 23) s.add(new String[]{c.getString(R.string.continue_watching), "pm grant " + p + " android.permission.READ_TV_LISTINGS"});
        // the beat glow reads the TV's sound (Music scenes): granted here so Android needn't ask
        if (Build.VERSION.SDK_INT >= 23) s.add(new String[]{c.getString(R.string.beat_glow), "pm grant " + p + " android.permission.RECORD_AUDIO"});
        // "cmd notification allow_listener" arrived in Android 8.1: before it (Fire OS 6 is 7.1) this
        // step always failed and Now playing never came on. There, Aura joins the list itself
        if (Build.VERSION.SDK_INT >= 27) s.add(new String[]{c.getString(R.string.now_playing), "cmd notification allow_listener " + p + "/" + MediaWatch.class.getName()});
        else s.add(new String[]{c.getString(R.string.now_playing), MediaWatch.enableCommand(c)});
        s.add(new String[]{c.getString(R.string.standby), "dpm set-active-admin --user 0 " + p + "/" + SleepAdmin.class.getName()});
        s.add(new String[]{c.getString(R.string.screensaver), "settings put secure screensaver_components " + p + "/" + Ambient.class.getName()});
        // the slowest: Aura compiled ahead of time opens and scrolls quicker
        if (Build.VERSION.SDK_INT >= 31) s.add(new String[]{c.getString(R.string.bluetooth), "pm grant " + p + " android.permission.BLUETOOTH_CONNECT"});
        if (Build.VERSION.SDK_INT >= 24) s.add(new String[]{c.getString(R.string.adb_speed), "cmd package compile -m speed -f " + p});
        // very last, and only when needed: Android restarts an app whose permission to install changes
        if (Build.VERSION.SDK_INT >= 26 && !c.getPackageManager().canRequestPackageInstalls()) {
            s.add(new String[]{c.getString(R.string.updates), "appops set " + p + " REQUEST_INSTALL_PACKAGES allow"});
        }
        return s.toArray(new String[0][]);
    }

    /** Told how setup goes, on its own thread. */
    interface Progress {
        /** Step {@code i} of {@code n} (from 0) is about to run. */
        void step(int i, int n, String name);

        /** The last step makes Android restart Aura: whatever must outlive that is kept now. */
        default void restarting(int ok, int n, List<String> failed) {
        }

        /** Finished: how many steps worked, and the names of those that didn't. */
        void done(int ok, int n, List<String> failed);
    }

    /** Runs {@link #setup}'s steps one by one, off the main thread; a step that fails is named. */
    static void runSetup(Context c, Progress p) {
        final Context app = c.getApplicationContext();
        final String[][] steps = setup(app);
        new SafeThread(() -> {
            int ok = 0;
            List<String> failed = new ArrayList<>();
            for (int i = 0; i < steps.length; i++) {
                p.step(i, steps.length, steps[i][0]);
                if (i == steps.length - 1 && steps[i][1].contains("REQUEST_INSTALL_PACKAGES")) p.restarting(ok + 1, steps.length, failed);
                List<String> out = run(app, steps[i][1]);
                String o = out == null ? null : out.get(0);
                // pm and settings say nothing when it works; the rest print Success; "already" is fine too
                if (o != null && (!o.contains("Exception") && !o.contains("Error") || o.contains("already"))) ok++;
                else failed.add(steps[i][0]);
                if (out == null) break;
            }
            p.done(ok, steps.length, failed);
        }, "adb-setup").start();
    }

    /** Types {@code text} into whatever field is focused on the TV via Android's input shell. */
    static boolean type(Context c, String text) {
        if (text == null || text.isEmpty() || !on(c)) return false;
        StringBuilder sb = new StringBuilder("input text '");
        int start = sb.length();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == ' ') sb.append("%s");
            else if (ch == '\'') sb.append("'\\''");
            // input text turns %s into a space; splitting % and s across two calls keeps a literal %s intact
            else if (ch == '%' && i + 1 < text.length() && text.charAt(i + 1) == 's') sb.append("%' && input text '");
            else if (ch >= 0x21 && ch <= 0x7e) sb.append(ch);
        }
        if (sb.length() == start) return false;
        sb.append('\'');
        return run(c, sb.toString()) != null;
    }

    /** Stops one or more apps outright over a single connection, as Android's own Force stop does. False when Aura can't. */
    static boolean forceStop(Context c, String... pkgs) {
        List<String> cmds = new ArrayList<>();
        // a package name, and nothing a shell would read as more
        for (String pkg : pkgs) {
            if (pkg != null && pkg.matches("[A-Za-z0-9_.]+")) cmds.add("am force-stop " + pkg);
        }
        return !cmds.isEmpty() && run(c, cmds.toArray(new String[0])) != null;
    }
}
