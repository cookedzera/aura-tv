package app.aura.home;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "From your phone": a tiny web server inside Aura, open only while its page is on screen. The phone
 * scans a QR code, picks photos or GIFs in its own browser and they arrive on the TV. No app, no
 * account and no cloud: it all stays on the local network, behind a one-time code in the link,
 * and nothing but pictures is accepted.
 */
final class Drop {
    /** Called on the main thread. */
    interface Listener {
        void onProgress(String name, long done, long total, int index, int count);

        void onAdded(JSONObject entry, int index, int count);

        void onFailed(String name, String why);

        void onChanged();

        /** An app arrived and Android is asking, on the TV, whether to install it. */
        void onApk(String name);
    }

    private static final int PORT = 8686;
    private static final long MAX_APK = 500L << 20;
    private static final String ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";

    private final Context app;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final String token;
    private volatile ServerSocket server;
    private String url;

    Drop(Context c, Listener l) {
        app = c.getApplicationContext();
        listener = l;
        SecureRandom r = new SecureRandom();
        StringBuilder t = new StringBuilder();
        for (int i = 0; i < 6; i++) t.append(ALPHABET.charAt(r.nextInt(ALPHABET.length())));
        token = t.toString();
    }

    /** Opens the server; returns the link to show, or null when the TV has no local address. */
    String start() {
        String ip = address();
        if (ip == null) return null;
        ServerSocket ss = null;
        for (int port : new int[]{PORT, 0}) {
            try {
                ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(port));
                break;
            } catch (IOException e) {
                close(ss);
                ss = null;
            }
        }
        if (ss == null) return null;
        server = ss;
        url = "http://" + ip + ":" + ss.getLocalPort() + "/" + token;
        final ServerSocket s = ss;
        Thread t = new Thread(() -> {
            while (server == s) {
                try {
                    Socket c = s.accept();
                    pool.execute(() -> serve(c));
                } catch (IOException e) {
                    break;
                }
            }
        }, "drop");
        t.setDaemon(true);
        t.start();
        return url;
    }

    /** Stops taking new connections; an upload already under way still finishes. */
    void stop() {
        ServerSocket s = server;
        server = null;
        close(s);
        pool.shutdown();
    }

    // ---- requests ----------------------------------------------------------------------------

    private void serve(Socket s) {
        try {
            s.setSoTimeout(30_000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            String[] req = line(in).split(" ");
            Map<String, String> head = new HashMap<>();
            for (String h; !(h = line(in)).isEmpty(); ) {
                int colon = h.indexOf(':');
                if (colon > 0) head.put(h.substring(0, colon).trim().toLowerCase(Locale.ROOT), h.substring(colon + 1).trim());
            }
            if (req.length < 2) return;
            String method = req[0], target = req[1], query = "";
            int q = target.indexOf('?');
            if (q >= 0) {
                query = target.substring(q + 1);
                target = target.substring(0, q);
            }
            String base = "/" + token;
            if (!target.equals(base) && !target.startsWith(base + "/")) {
                send(s, 404, "text/html; charset=utf-8", expired());
                return;
            }
            String rest = target.substring(base.length());
            Map<String, String> args = args(query);
            if (rest.isEmpty()) {
                send(s, 302, "text/plain", new byte[0], "Location: " + base + "/\r\n");
            } else if (rest.equals("/")) {
                send(s, 200, "text/html; charset=utf-8", asset("drop.html"));
            } else if (rest.equals("/list")) {
                JSONObject o = new JSONObject().put("items", Mine.list(app)).put("only", Prefs.on(app, Prefs.MINE_ONLY));
                send(s, 200, "application/json", o.toString().getBytes(StandardCharsets.UTF_8));
            } else if (rest.startsWith("/t/")) {
                File f = Mine.thumb(app, rest.substring(3));
                if (f.isFile()) send(s, 200, "image/jpeg", read(f));
                else send(s, 404, "text/plain", new byte[0]);
            } else if (method.equals("POST") && rest.equals("/up")) {
                upload(s, in, head, args);
            } else if (method.equals("POST") && rest.startsWith("/del/")) {
                boolean ok = Mine.remove(app, rest.substring(5));
                if (ok) main.post(listener::onChanged);
                send(s, ok ? 200 : 404, "application/json", ("{\"ok\":" + ok + "}").getBytes(StandardCharsets.UTF_8));
            } else if (method.equals("POST") && rest.equals("/only")) {
                Prefs.set(app, Prefs.MINE_ONLY, "1".equals(args.get("on")));
                main.post(listener::onChanged);
                send(s, 200, "application/json", "{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
            } else {
                send(s, 404, "text/plain", new byte[0]);
            }
        } catch (IOException | JSONException | RuntimeException ignored) {
        } finally {
            close(s);
        }
    }

    private void upload(Socket s, InputStream in, Map<String, String> head, Map<String, String> args)
            throws IOException, JSONException {
        long len;
        try {
            len = Long.parseLong(head.containsKey("content-length") ? head.get("content-length") : "-1");
        } catch (NumberFormatException e) {
            len = -1;
        }
        String name = args.containsKey("name") ? args.get("name") : "";
        int index = number(args.get("i")), count = Math.max(1, number(args.get("n")));
        if (name.toLowerCase(Locale.ROOT).endsWith(".apk")) {
            apk(s, in, len, name, index, count);
            return;
        }
        if (len <= 0 || len > Mine.MAX_BYTES) {
            fail(s, name, 413, len <= 0 ? "Nothing arrived" : "Too big for the TV (40 MB at most)");
            return;
        }
        File part = Mine.part(app);
        final long total = len;
        try (OutputStream out = new FileOutputStream(part)) {
            byte[] buf = new byte[64 * 1024];
            long done = 0, shown = 0;
            while (done < len) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, len - done));
                if (n < 0) throw new IOException("cut short");
                out.write(buf, 0, n);
                done += n;
                long now = SystemClock.uptimeMillis();
                if (now - shown > 120 || done == len) {
                    shown = now;
                    final long d = done;
                    main.post(() -> listener.onProgress(name, d, total, index, count));
                }
            }
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            main.post(() -> listener.onFailed(name, "The upload stopped part-way"));
            throw e;
        }
        try {
            JSONObject e = Mine.add(app, part, name, args.containsKey("title") ? args.get("title") : "");
            main.post(() -> listener.onAdded(e, index, count));
            send(s, 200, "application/json", new JSONObject().put("ok", true).put("id", e.optString("id"))
                    .put("title", e.optString("place")).toString().getBytes(StandardCharsets.UTF_8));
        } catch (Mine.Unsupported e) {
            fail(s, name, 415, e.getMessage());
        }
    }

    /**
     * An app sent from the phone streams straight into Android's installer, which then asks on
     * the TV whether to install it. Nothing installs without that confirmation.
     */
    private void apk(Socket s, InputStream in, long len, String name, int index, int count) throws IOException, JSONException {
        if (len <= 0 || len > MAX_APK) {
            fail(s, name, 413, len <= 0 ? "Nothing arrived" : "Too big (500 MB at most)");
            return;
        }
        if (!Updater.allowed(app)) {
            fail(s, name, 403, "The TV doesn't let Aura install apps yet: run setup-aura.ps1 once");
            return;
        }
        final long[] shown = {0};
        try {
            Updater.stream(app, in, len, null, (done, total) -> {
                long now = SystemClock.uptimeMillis();
                if (now - shown[0] > 120 || done == total) {
                    shown[0] = now;
                    main.post(() -> listener.onProgress(name, done, total, index, count));
                }
            });
        } catch (IOException e) {
            main.post(() -> listener.onFailed(name, "The upload stopped part-way"));
            throw e;
        }
        main.post(() -> listener.onApk(name));
        send(s, 200, "application/json", new JSONObject().put("ok", true).put("apk", true).put("title", name)
                .toString().getBytes(StandardCharsets.UTF_8));
    }

    private void fail(Socket s, String name, int code, String why) throws IOException, JSONException {
        main.post(() -> listener.onFailed(name, why));
        send(s, code, "application/json", new JSONObject().put("ok", false).put("error", why).toString()
                .getBytes(StandardCharsets.UTF_8));
    }

    private byte[] expired() {
        return ("<!doctype html><meta name=viewport content='width=device-width'><body style='background:#05070c;"
                + "color:#fff;font:16px -apple-system,system-ui,sans-serif;display:grid;place-items:center;height:90vh;"
                + "text-align:center;padding:24px'><div><b style='font-size:22px'>This code has expired</b><p style='opacity:.6'>"
                + "On the TV, open Settings, then From your phone, and scan the new code.</p></div>")
                .getBytes(StandardCharsets.UTF_8);
    }

    // ---- plumbing ----------------------------------------------------------------------------

    /** The TV's address on the local network, preferring Wi-Fi and Ethernet. */
    static String address() {
        String any = null;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || !a.isSiteLocalAddress()) continue;
                    String n = ni.getName();
                    if (n.startsWith("wlan") || n.startsWith("eth")) return a.getHostAddress();
                    if (any == null) any = a.getHostAddress();
                }
            }
        } catch (IOException | RuntimeException ignored) {
        }
        return any;
    }

    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (int c; (c = in.read()) != -1 && c != '\n'; ) {
            if (c != '\r') b.write(c);
            if (b.size() > 8192) throw new IOException("header too long");
        }
        return b.toString("ISO-8859-1");
    }

    private static Map<String, String> args(String query) {
        Map<String, String> m = new HashMap<>();
        for (String kv : query.split("&")) {
            int eq = kv.indexOf('=');
            if (eq <= 0) continue;
            try {
                m.put(kv.substring(0, eq), URLDecoder.decode(kv.substring(eq + 1), "UTF-8"));
            } catch (IOException | IllegalArgumentException ignored) {
            }
        }
        return m;
    }

    private static int number(String s) {
        try {
            return s == null ? 0 : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private byte[] asset(String name) throws IOException {
        try (InputStream in = app.getAssets().open(name)) {
            return readAll(in);
        }
    }

    private static byte[] read(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            return readAll(in);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
        return b.toByteArray();
    }

    private static void send(Socket s, int code, String type, byte[] body) throws IOException {
        send(s, code, type, body, "");
    }

    private static void send(Socket s, int code, String type, byte[] body, String extra) throws IOException {
        String reason = code == 200 ? "OK" : code == 302 ? "Found" : code == 404 ? "Not Found"
                : code == 403 ? "Forbidden" : code == 413 ? "Payload Too Large" : code == 415 ? "Unsupported Media Type" : "Error";
        String h = "HTTP/1.1 " + code + " " + reason + "\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length
                + "\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n" + extra + "\r\n";
        OutputStream o = s.getOutputStream();
        o.write(h.getBytes(StandardCharsets.ISO_8859_1));
        o.write(body);
        o.flush();
    }

    private static void close(java.io.Closeable c) {
        try {
            if (c != null) c.close();
        } catch (IOException ignored) {
        }
    }
}
