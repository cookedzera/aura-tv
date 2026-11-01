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
import java.util.Arrays;
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

        /** Text typed on the phone's key page (a TMDB key): the TV checks and keeps it. */
        default void onKey(String text) {
        }

        /** A saved setup was brought back from the phone: {@code count} settings. */
        default void onRestored(int count) {
        }

        /** A picture for an app's tile arrived and was kept. */
        default void onArt(String pkg) {
        }

        /** The 6-digit code from the TV's wireless debugging, typed on the phone. */
        /** Text typed on the phone's "Type on the TV" field; returns true when Aura handled it directly. */
        default boolean onType(String text) {
            return false;
        }

        default void onPairCode(String code) {
        }
    }

    /** A listener for the small pages (a key, a backup, a tile's picture): the photo calls do nothing. */
    static class Quiet implements Listener {
        @Override
        public void onProgress(String name, long done, long total, int index, int count) {
        }

        @Override
        public void onAdded(JSONObject entry, int index, int count) {
        }

        @Override
        public void onFailed(String name, String why) {
        }

        @Override
        public void onChanged() {
        }

        @Override
        public void onApk(String name) {
        }
    }

    /** The app whose tile the art page is for, while it's open. */
    volatile String artFor;

    /**
     * The routes this server answers, when it was opened for one thing (a key, a pairing code, a
     * tile's picture, a backup): null for the full phone page. A code for pairing then can't also
     * fetch the backup, restore one, or send an app to install.
     */
    private volatile java.util.Set<String> only;

    Drop only(String... routes) {
        only = new java.util.HashSet<>(java.util.Arrays.asList(routes));
        return this;
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
            java.util.Set<String> allowed = only;
            if (allowed != null && !rest.isEmpty() && !allowed.contains(rest)) {
                send(s, 404, "text/plain", new byte[0]);
                return;
            }
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
            } else if (rest.equals("/key")) {
                if (method.equals("POST")) {
                    String text = new String(body(in, head, 4096), StandardCharsets.UTF_8).trim();
                    main.post(() -> listener.onKey(text));
                    send(s, 200, "application/json", "{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
                } else {
                    String page = unsplashKey ? KEY_PAGE.replace("TMDB key", "Unsplash key")
                            .replace("<p>On <a href=\"https://www.themoviedb.org/settings/api\"", "<p>On <a href=\"https://unsplash.com/oauth/applications\"")
                            .replace("themoviedb.org → Settings → API</a> (a free account), copy the <b>API Key</b> or the <b>API Read Access Token</b>",
                                    "unsplash.com → Your apps</a> (a free account), create an app and copy its <b>Access Key</b>") : KEY_PAGE;
                    send(s, 200, "text/html; charset=utf-8", page.getBytes(StandardCharsets.UTF_8));
                }
            } else if (rest.equals("/pair")) {
                if (method.equals("POST")) {
                    String code = new String(body(in, head, 64), StandardCharsets.UTF_8).replaceAll("[^0-9]", "");
                    boolean ok = code.length() == 6;
                    if (ok) main.post(() -> listener.onPairCode(code));
                    send(s, 200, "application/json", ("{\"ok\":" + ok + "}").getBytes(StandardCharsets.UTF_8));
                } else {
                    send(s, 200, "text/html; charset=utf-8", page("The pairing code", "On the TV: Developer options, Wireless debugging, Pair device with pairing code. Leave that open, and type the six digits it shows here.",
                            "<input id=\"k\" inputmode=\"numeric\" autocomplete=\"one-time-code\" maxlength=\"6\" placeholder=\"123456\" style=\"width:100%;box-sizing:border-box;font:600 34px/1 ui-monospace,monospace;"
                                    + "letter-spacing:.3em;text-align:center;padding:16px;border-radius:18px;border:1px solid rgba(255,255,255,.18);background:rgba(255,255,255,.07);color:#fff\">"
                                    + "<button class=\"b\" id=\"b\" style=\"border:0\">Pair</button>",
                            "b.onclick=async()=>{const v=k.value.replace(/[^0-9]/g,'');if(v.length!=6){s.textContent='Six digits, as the TV shows them.';return}"
                                    + "s.textContent='Pairing… look at the TV.';try{const r=await fetch('pair',{method:'POST',body:v});const j=await r.json();"
                                    + "s.textContent=j.ok?'Sent. The TV takes it from here.':'Six digits, as the TV shows them.'}catch(e){s.textContent='Could not reach the TV.'}}"));
                }
            } else if (rest.equals("/backup")) {
                send(s, 200, "text/html; charset=utf-8", page("Your Aura setup", "Save a copy of your profiles, app order, folders, tiles and choices to this phone, or bring one back. Photos stay where they are.",
                        "<a class=\"b\" href=\"backup.json\" download=\"aura-setup.json\">Save a copy</a>"
                                + "<label class=\"b ghost\">Bring one back<input id=\"f\" type=\"file\" accept=\".json,application/json\" hidden></label>",
                        "f.onchange=async()=>{const x=f.files[0];if(!x)return;s.textContent='Sending…';try{const r=await fetch('restore',{method:'POST',body:await x.text()});"
                                + "const j=await r.json();s.textContent=j.ok?'Back on the TV: '+j.count+' settings. Aura is starting again.':'That file is not an Aura setup.'}catch(e){s.textContent='Could not reach the TV.'}}"));
            } else if (rest.equals("/backup.json")) {
                send(s, 200, "application/json", Backup.export(app).getBytes(StandardCharsets.UTF_8),
                        "Content-Disposition: attachment; filename=\"aura-setup.json\"\r\n");
            } else if (method.equals("POST") && rest.equals("/restore")) {
                int n;
                try {
                    n = Backup.restore(app, new String(body(in, head, 2 << 20), StandardCharsets.UTF_8));
                } catch (JSONException e) {
                    n = -1;
                }
                send(s, 200, "application/json", ("{\"ok\":" + (n >= 0) + ",\"count\":" + Math.max(n, 0) + "}").getBytes(StandardCharsets.UTF_8));
                final int count = n;
                if (count >= 0) main.postDelayed(() -> listener.onRestored(count), 600);
            } else if (rest.equals("/art")) {
                String pkg = artFor;
                if (pkg == null) {
                    send(s, 404, "text/plain", new byte[0]);
                } else if (method.equals("POST")) {
                    boolean ok = Art.save(app, pkg, body(in, head, 25 << 20));
                    if (ok) main.post(() -> listener.onArt(pkg));
                    send(s, 200, "application/json", ("{\"ok\":" + ok + "}").getBytes(StandardCharsets.UTF_8));
                } else {
                    send(s, 200, "text/html; charset=utf-8", page("A picture for its tile", "Choose a photo or a banner: it's cropped to fit the tile, wide like a TV.",
                            "<label class=\"b\">Choose a picture<input id=\"f\" type=\"file\" accept=\"image/*\" hidden></label>",
                            "f.onchange=async()=>{const x=f.files[0];if(!x)return;s.textContent='Sending…';try{const r=await fetch('art',{method:'POST',body:x});"
                                    + "const j=await r.json();s.textContent=j.ok?'On the TV now.':'The TV could not read that picture.'}catch(e){s.textContent='Could not reach the TV.'}}"));
                }
            } else if (method.equals("POST") && rest.equals("/type")) {
                String text = new String(body(in, head, 4096), StandardCharsets.UTF_8).trim();
                boolean ok = !text.isEmpty() && typeToTv(text);
                send(s, 200, "application/json", ("{\"ok\":" + ok + "}").getBytes(StandardCharsets.UTF_8));
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

    /** A short request body, up to {@code max} bytes. */
    /** Routes text typed on the phone to Aura's active field/search first, then to Android's ADB input shell. */
    private boolean typeToTv(String text) {
        final boolean[] handled = new boolean[1];
        final Object lock = new Object();
        main.post(() -> {
            try {
                handled[0] = listener.onType(text);
            } finally {
                synchronized (lock) {
                    lock.notifyAll();
                }
            }
        });
        synchronized (lock) {
            try {
                lock.wait(1500);
            } catch (InterruptedException ignored) {
            }
        }
        return handled[0] || Adb.type(app, text);
    }

    private static byte[] body(InputStream in, Map<String, String> head, int max) throws IOException {
        int len;
        try {
            len = Integer.parseInt(head.containsKey("content-length") ? head.get("content-length") : "0");
        } catch (NumberFormatException e) {
            len = 0;
        }
        len = Math.max(0, Math.min(len, max));
        byte[] b = new byte[len];
        int done = 0;
        while (done < len) {
            int n = in.read(b, done, len - done);
            if (n < 0) break;
            done += n;
        }
        return done == len ? b : Arrays.copyOf(b, done);
    }

    /** A small page for the phone in Aura's look: a title, a line, its buttons, a status line and a script. */
    private static byte[] page(String title, String lede, String buttons, String script) {
        return ("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><meta name=\"theme-color\" content=\"#070a12\">"
                + "<title>Aura</title><style>"
                + "body{margin:0;min-height:100vh;padding:32px 18px;box-sizing:border-box;background:radial-gradient(120% 60% at 80% -10%,rgba(255,166,92,.22),transparent 60%),#070a12;"
                + "color:#fff;font:16px/1.45 -apple-system,BlinkMacSystemFont,Inter,'Segoe UI',Roboto,system-ui,sans-serif}"
                + "main{max-width:520px;margin:0 auto}small{color:rgba(255,255,255,.62);font-weight:700;letter-spacing:.28em;text-transform:uppercase;font-size:12px}"
                + "h1{font-size:36px;line-height:1.05;letter-spacing:-.03em;margin:16px 0 10px}p{color:rgba(255,255,255,.62);margin:0 0 22px}"
                + ".b{display:block;box-sizing:border-box;text-align:center;margin-top:12px;width:100%;padding:18px;border-radius:22px;font-weight:750;font-size:17px;"
                + "color:#1b0f05;text-decoration:none;background:linear-gradient(135deg,#ffc27a,#ff8c5a);cursor:pointer}"
                + ".ghost{color:#fff;background:rgba(255,255,255,.08);border:1px solid rgba(255,255,255,.18)}#s{margin-top:18px;min-height:1.4em;color:rgba(255,255,255,.75)}"
                + "</style></head><body><main><small>Aura</small><h1>" + title + "</h1><p>" + lede + "</p>" + buttons
                + "<div id=\"s\"></div></main><script>" + script + "</script></body></html>").getBytes(StandardCharsets.UTF_8);
    }

    /** The key page asks for an Unsplash key rather than a TMDB one (Settings → Wallpaper sources). */
    boolean unsplashKey;

    /** The phone's page for a TMDB key: paste it (copied from TMDB's own settings page), send it to the TV. */
    private static final String KEY_PAGE = "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
            + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><meta name=\"theme-color\" content=\"#070a12\">"
            + "<title>Aura · TMDB key</title><style>"
            + "body{margin:0;min-height:100vh;padding:32px 18px;box-sizing:border-box;background:radial-gradient(120% 60% at 80% -10%,rgba(255,166,92,.22),transparent 60%),#070a12;"
            + "color:#fff;font:16px/1.45 -apple-system,BlinkMacSystemFont,Inter,'Segoe UI',Roboto,system-ui,sans-serif}"
            + "main{max-width:520px;margin:0 auto}small{color:rgba(255,255,255,.62);font-weight:700;letter-spacing:.28em;text-transform:uppercase;font-size:12px}"
            + "h1{font-size:36px;line-height:1.05;letter-spacing:-.03em;margin:16px 0 10px}p{color:rgba(255,255,255,.62);margin:0 0 20px}a{color:#ffc27a}"
            + "textarea{width:100%;box-sizing:border-box;min-height:110px;padding:14px 16px;border-radius:18px;border:1px solid rgba(255,255,255,.18);"
            + "background:rgba(255,255,255,.07);color:#fff;font:15px/1.4 ui-monospace,Menlo,Consolas,monospace;word-break:break-all;resize:vertical}"
            + "button{margin-top:14px;width:100%;padding:18px;border:0;border-radius:22px;font-weight:750;font-size:17px;color:#1b0f05;"
            + "background:linear-gradient(135deg,#ffc27a,#ff8c5a)}#s{margin-top:16px;min-height:1.4em;color:rgba(255,255,255,.75)}</style></head>"
            + "<body><main><small>Aura</small><h1>Your TMDB key</h1>"
            + "<p>On <a href=\"https://www.themoviedb.org/settings/api\" target=\"_blank\" rel=\"noopener\">themoviedb.org → Settings → API</a> "
            + "(a free account), copy the <b>API Key</b> or the <b>API Read Access Token</b> and paste it here.</p>"
            + "<textarea id=\"k\" autocomplete=\"off\" autocapitalize=\"off\" spellcheck=\"false\" placeholder=\"Paste your key\"></textarea>"
            + "<button id=\"b\">Send to the TV</button><div id=\"s\"></div></main><script>"
            + "b.onclick=async()=>{const v=k.value.trim();if(!v){s.textContent='Paste the key first.';return}"
            + "s.textContent='Sending…';try{const r=await fetch(location.pathname,{method:'POST',body:v});"
            + "s.textContent=r.ok?'Sent. The TV checks it now: look at the screen.':'The TV did not take it. Try again.'}"
            + "catch(e){s.textContent='Could not reach the TV. Same Wi-Fi?'}}</script></body></html>";

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
