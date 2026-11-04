package app.aura.home;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Aura as a DLNA / UPnP media player: phones, computers and media servers on the home network find
 * the TV in their cast lists (BubbleUPnP, Web Video Caster, Windows' Cast to device, Kodi and the
 * like) and send it a video, a song or a photograph, which Aura plays full screen ({@link Cast}).
 * Fire TVs have no Cast at all; this is a way in that every platform already speaks.
 *
 * Three parts, all on the local network: SSDP (UDP multicast on 1900) so it can be found; a small
 * HTTP server with the TV's description, its three services and their SOAP controls; and events,
 * so a controller's play bar follows the TV playing, pausing and stopping. Written from the UPnP
 * Device Architecture and the UPnP AV specifications. Settings switches it off; a kids' profile
 * turns casts away.
 */
final class Renderer {
    static final String DEVICE = "urn:schemas-upnp-org:device:MediaRenderer:1";
    static final String AVT = "urn:schemas-upnp-org:service:AVTransport:1";
    static final String RC = "urn:schemas-upnp-org:service:RenderingControl:1";
    static final String CM = "urn:schemas-upnp-org:service:ConnectionManager:1";
    private static final String GROUP = "239.255.255.250";
    private static final int SSDP_PORT = 1900, HTTP_PORT = 49494, MAX_AGE = 1800;
    /** What Aura's player (Android's own) can take over the network. */
    private static final String SINK = TextUtils.join(",", new String[]{
            "http-get:*:video/mp4:*", "http-get:*:video/x-matroska:*", "http-get:*:video/webm:*", "http-get:*:video/mpeg:*",
            "http-get:*:video/mp2t:*", "http-get:*:video/3gpp:*", "http-get:*:video/quicktime:*", "http-get:*:video/x-msvideo:*",
            "http-get:*:application/vnd.apple.mpegurl:*", "http-get:*:application/x-mpegURL:*",
            "http-get:*:audio/mpeg:*", "http-get:*:audio/mp4:*", "http-get:*:audio/aac:*", "http-get:*:audio/flac:*",
            "http-get:*:audio/x-flac:*", "http-get:*:audio/ogg:*", "http-get:*:audio/wav:*", "http-get:*:audio/x-wav:*",
            "http-get:*:image/jpeg:*", "http-get:*:image/png:*", "http-get:*:image/gif:*", "http-get:*:image/webp:*"});

    /** What's loaded and how far along, kept by {@link Cast} while it plays; read by controllers. */
    static volatile String uri = "", meta = "", title = "", mime = "", by = "";
    static volatile String state = "NO_MEDIA_PRESENT";
    static volatile long posMs, durMs;
    /** The player on screen, when there is one: commands from the network go to it. */
    static volatile Player player;

    interface Player {
        void load(boolean play);

        void play();

        void pause();

        void stop();

        void seek(long ms);
    }

    private static Renderer running;

    private final Context app;
    private final String uuid, name;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Compat.pool("dlna", 3);
    private final Map<String, Sub> subs = new ConcurrentHashMap<>();
    private final Random rnd = new Random();
    private volatile ServerSocket http;
    private volatile MulticastSocket ssdp;
    private volatile boolean on;
    private WifiManager.MulticastLock lock;

    private static final class Sub {
        final String service;
        final List<URL> callbacks;
        volatile long until;
        int seq;

        Sub(String service, List<URL> callbacks, long until) {
            this.service = service;
            this.callbacks = callbacks;
            this.until = until;
        }
    }

    private Renderer(Context c) {
        app = c.getApplicationContext();
        String id = Prefs.device(app).getString("dlna_uuid", null);
        if (id == null) {
            id = UUID.randomUUID().toString();
            Prefs.device(app).edit().putString("dlna_uuid", id).apply();
        }
        uuid = "uuid:" + id;
        String n = null;
        try {
            n = android.provider.Settings.Global.getString(app.getContentResolver(), "device_name");
        } catch (RuntimeException ignored) {
        }
        name = (n == null || n.trim().isEmpty() ? Build.MODEL : n.trim()) + " (Aura)";
    }

    // ---- on and off --------------------------------------------------------------------------

    /** Started, or stopped, to match Settings. Safe to call from anywhere, as often as wanted. */
    static synchronized void apply(Context c) {
        // the tests' simulated TV has no network of its own: nothing to open there
        boolean want = Prefs.on(c, Prefs.DLNA) && !"robolectric".equals(Build.FINGERPRINT);
        if (want && running == null) {
            running = new Renderer(c);
            final Renderer r = running;
            new SafeThread(r::start, "dlna-start").start();
        } else if (!want && running != null) {
            final Renderer r = running;
            running = null;
            new SafeThread(r::stop, "dlna-stop").start();
        }
    }

    private void start() {
        on = true;
        try {
            ServerSocket s = null;
            for (int port : new int[]{HTTP_PORT, 0}) {
                try {
                    s = new ServerSocket();
                    s.setReuseAddress(true);
                    s.bind(new InetSocketAddress(port));
                    break;
                } catch (IOException e) {
                    close(s);
                    s = null;
                }
            }
            if (s == null) return;
            http = s;
            final ServerSocket hs = s;
            new SafeThread(() -> {
                while (on && http == hs) {
                    try {
                        Socket c = hs.accept();
                        pool.execute(() -> serve(c));
                    } catch (IOException e) {
                        break;
                    }
                }
            }, "dlna-http").start();
            WifiManager wm = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                lock = wm.createMulticastLock("aura-dlna");
                lock.setReferenceCounted(false);
                lock.acquire();
            }
            MulticastSocket ms = joinSsdp();
            if (ms == null) return;
            ssdp = ms;
            new SafeThread(this::listen, "dlna-ssdp").start();
            new SafeThread(this::announce, "dlna-notify").start();
        } catch (IOException e) {
            Trouble.caught("DLNA", e);
        }
    }

    /**
     * The discovery group, joined on the TV's network; null if DLNA was switched off meanwhile.
     * Just after the TV starts, its Wi-Fi may not be up yet and Android refuses the join ("no such
     * device"): it's tried again every 15 s until the network is there. Before, DLNA stayed deaf
     * (phones never found the TV) until Aura next started, and a problem was noted at every boot.
     */
    private MulticastSocket joinSsdp() throws IOException {
        IOException last = null;
        for (int attempt = 0; on && attempt < 40; attempt++) {
            MulticastSocket ms = null;
            try {
                // IPv4 by name: an unnamed address opens an IPv6 socket on Android, whose join to this IPv4
                // group failed without a word, so searches reached only the TV's own Cast service on 1900
                ms = new MulticastSocket(null);
                ms.setReuseAddress(true);
                ms.bind(new InetSocketAddress(InetAddress.getByName("0.0.0.0"), SSDP_PORT));
                NetworkInterface ni = lan(app);
                if (ni != null) {
                    ms.setNetworkInterface(ni);
                    ms.joinGroup(new InetSocketAddress(InetAddress.getByName(GROUP), SSDP_PORT), ni);
                } else {
                    ms.joinGroup(InetAddress.getByName(GROUP));
                }
                return ms;
            } catch (IOException e) {
                close(ms);
                last = e;
                try {
                    Thread.sleep(15_000);
                } catch (InterruptedException ie) {
                    return null;
                }
            }
        }
        // ten minutes without a network to join: noted once
        if (last != null && on) throw last;
        return null;
    }

    private void stop() {
        on = false;
        notifyAll("ssdp:byebye");
        close(ssdp);
        close(http);
        ssdp = null;
        http = null;
        if (lock != null && lock.isHeld()) lock.release();
        subs.clear();
        pool.shutdown();
    }

    // ---- being found (SSDP) ------------------------------------------------------------------

    private String[] types() {
        return new String[]{"upnp:rootdevice", uuid, DEVICE, AVT, RC, CM};
    }

    private static String usn(String uuid, String type) {
        return type.equals(uuid) ? uuid : uuid + "::" + type;
    }

    private void listen() {
        MulticastSocket ms = ssdp;
        byte[] buf = new byte[2048];
        while (on && ms != null && ms == ssdp) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                ms.receive(p);
                String msg = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                if (!msg.startsWith("M-SEARCH")) continue;
                Map<String, String> h = headers(msg);
                String st = h.get("st");
                if (st == null) continue;
                List<String> answer = new ArrayList<>();
                if (st.equals("ssdp:all")) for (String t : types()) answer.add(t);
                else for (String t : types()) if (t.equalsIgnoreCase(st)) answer.add(t);
                if (answer.isEmpty()) continue;
                final InetSocketAddress to = (InetSocketAddress) p.getSocketAddress();
                // for tools/diagnose.ps1 and `adb logcat -s Aura`: who looked for the TV, and found it
                android.util.Log.d("Aura", "DLNA search from " + to.getAddress().getHostAddress() + " for " + st);
                // answered within the wait the searcher allowed (MX), a little apart, as the spec asks
                int mx = Math.max(1, Math.min(3, number(h.get("mx"))));
                final List<String> send = answer;
                main.postDelayed(() -> pool.execute(() -> reply(to, send)), rnd.nextInt(mx * 300));
            } catch (IOException e) {
                if (!on) return;
            } catch (RuntimeException e) {
                Trouble.caught("DLNA search", e);
            }
        }
    }

    private void reply(InetSocketAddress to, List<String> types) {
        String loc = location();
        if (loc == null) return;
        try (java.net.DatagramSocket ds = new java.net.DatagramSocket()) {
            for (String t : types) {
                String m = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=" + MAX_AGE + "\r\nDATE: " + date() + "\r\nEXT:\r\n"
                        + "LOCATION: " + loc + "\r\nSERVER: " + server() + "\r\nST: " + t + "\r\nUSN: " + usn(uuid, t) + "\r\n\r\n";
                byte[] b = m.getBytes(StandardCharsets.UTF_8);
                ds.send(new DatagramPacket(b, b.length, to));
            }
        } catch (IOException ignored) {
        }
    }

    /** "Here I am" on start and every few minutes, so lists that only listen still show the TV. */
    private void announce() {
        try {
            while (on) {
                notifyAll("ssdp:alive");
                for (int i = 0; i < 600 && on; i++) Thread.sleep(1000);
            }
        } catch (InterruptedException ignored) {
        }
    }

    private void notifyAll(String nts) {
        String loc = location();
        if (loc == null) return;
        try (java.net.DatagramSocket ds = new java.net.DatagramSocket()) {
            InetAddress group = InetAddress.getByName(GROUP);
            for (String t : types()) {
                String m = "NOTIFY * HTTP/1.1\r\nHOST: " + GROUP + ":" + SSDP_PORT + "\r\nCACHE-CONTROL: max-age=" + MAX_AGE
                        + "\r\nLOCATION: " + loc + "\r\nNT: " + t + "\r\nNTS: " + nts + "\r\nSERVER: " + server()
                        + "\r\nUSN: " + usn(uuid, t) + "\r\n\r\n";
                byte[] b = m.getBytes(StandardCharsets.UTF_8);
                ds.send(new DatagramPacket(b, b.length, group, SSDP_PORT));
            }
        } catch (IOException ignored) {
        }
    }

    private String location() {
        ServerSocket s = http;
        String ip = Drop.address(app);
        return s == null || ip == null ? null : "http://" + ip + ":" + s.getLocalPort() + "/dlna/desc.xml";
    }

    private static NetworkInterface lan(Context c) {
        String ip = Drop.address(c);
        if (ip == null) return null;
        try {
            return NetworkInterface.getByInetAddress(InetAddress.getByName(ip));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static String server() {
        return "Android/" + Build.VERSION.RELEASE + " UPnP/1.0 Aura/" + Build.VERSION.SDK_INT;
    }

    private static String date() {
        SimpleDateFormat f = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("GMT"));
        return f.format(new Date());
    }

    // ---- requests (HTTP) ---------------------------------------------------------------------

    private void serve(Socket s) {
        try {
            s.setSoTimeout(15_000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            String[] req = line(in).split(" ");
            Map<String, String> head = new HashMap<>();
            for (String h; !(h = line(in)).isEmpty(); ) {
                int colon = h.indexOf(':');
                if (colon > 0) head.put(h.substring(0, colon).trim().toLowerCase(Locale.ROOT), h.substring(colon + 1).trim());
            }
            if (req.length < 2) return;
            String method = req[0], path = req[1];
            if ((method.equals("GET") || method.equals("HEAD")) && path.equals("/dlna/desc.xml")) {
                send(s, 200, description(), method.equals("HEAD"));
            } else if (method.equals("GET") && path.equals("/dlna/AVTransport.xml")) {
                send(s, 200, scpd(AVT_ACTIONS, AVT_VARS), false);
            } else if (method.equals("GET") && path.equals("/dlna/RenderingControl.xml")) {
                send(s, 200, scpd(RC_ACTIONS, RC_VARS), false);
            } else if (method.equals("GET") && path.equals("/dlna/ConnectionManager.xml")) {
                send(s, 200, scpd(CM_ACTIONS, CM_VARS), false);
            } else if (method.equals("POST") && path.endsWith("/control")) {
                String service = path.contains("AVTransport") ? AVT : path.contains("RenderingControl") ? RC : CM;
                String action = head.containsKey("soapaction") ? head.get("soapaction").replace("\"", "") : "";
                int hash = action.indexOf('#');
                action = hash >= 0 ? action.substring(hash + 1) : action;
                String body = new String(body(in, head, 256 * 1024), StandardCharsets.UTF_8);
                control(s, service, action, body);
            } else if (method.equals("SUBSCRIBE") && path.endsWith("/event")) {
                subscribe(s, path.contains("AVTransport") ? AVT : path.contains("RenderingControl") ? RC : CM, head);
            } else if (method.equals("UNSUBSCRIBE") && path.endsWith("/event")) {
                String sid = head.get("sid");
                if (sid != null) subs.remove(sid);
                raw(s, "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
            } else {
                raw(s, "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
            }
        } catch (IOException ignored) {
        } catch (RuntimeException e) {
            Trouble.caught("DLNA request", e);
        } finally {
            close(s);
        }
    }

    private String description() {
        String version = "1";
        try {
            version = app.getPackageManager().getPackageInfo(app.getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        StringBuilder b = new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<root xmlns=\"urn:schemas-upnp-org:device-1-0\" xmlns:dlna=\"urn:schemas-dlna-org:device-1-0\">"
                + "<specVersion><major>1</major><minor>0</minor></specVersion><device>"
                + "<deviceType>" + DEVICE + "</deviceType><friendlyName>" + esc(name) + "</friendlyName>"
                + "<manufacturer>cookedzera</manufacturer><manufacturerURL>https://github.com/cookedzera/aura-tv</manufacturerURL>"
                + "<modelDescription>Aura, the home screen on this TV</modelDescription><modelName>Aura</modelName>"
                + "<modelNumber>" + esc(version) + "</modelNumber><UDN>" + uuid + "</UDN>"
                + "<dlna:X_DLNADOC>DMR-1.50</dlna:X_DLNADOC><serviceList>");
        for (String[] sv : new String[][]{{AVT, "AVTransport"}, {RC, "RenderingControl"}, {CM, "ConnectionManager"}}) {
            b.append("<service><serviceType>").append(sv[0]).append("</serviceType><serviceId>urn:upnp-org:serviceId:").append(sv[1])
                    .append("</serviceId><SCPDURL>/dlna/").append(sv[1]).append(".xml</SCPDURL><controlURL>/dlna/").append(sv[1])
                    .append("/control</controlURL><eventSubURL>/dlna/").append(sv[1]).append("/event</eventSubURL></service>");
        }
        return b.append("</serviceList></device></root>").toString();
    }

    // ---- controls (SOAP) ---------------------------------------------------------------------

    private void control(Socket s, String service, String action, String body) throws IOException {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        String fault = null;
        if (service.equals(AVT)) {
            switch (action) {
                case "SetAVTransportURI":
                    if (Profiles.kids(app)) {
                        fault = "701|Not available in a kids' profile";
                        break;
                    }
                    take(arg(body, "CurrentURI"), arg(body, "CurrentURIMetaData"));
                    break;
                case "SetNextAVTransportURI":
                    break;
                case "Play":
                    if (Profiles.kids(app)) fault = "701|Not available in a kids' profile";
                    else if (uri.isEmpty()) fault = "701|Nothing to play";
                    else main.post(this::playNow);
                    break;
                case "Pause":
                    main.post(() -> {
                        Player p = player;
                        if (p != null) p.pause();
                    });
                    break;
                case "Stop":
                    main.post(() -> {
                        Player p = player;
                        if (p != null) p.stop();
                        else setState("STOPPED");
                    });
                    break;
                case "Seek": {
                    final long ms = clock(arg(body, "Target"));
                    if (ms < 0) {
                        fault = "711|Seek target not understood";
                        break;
                    }
                    main.post(() -> {
                        Player p = player;
                        if (p != null) p.seek(ms);
                    });
                    break;
                }
                case "GetTransportInfo":
                    out.put("CurrentTransportState", state);
                    out.put("CurrentTransportStatus", "OK");
                    out.put("CurrentSpeed", "1");
                    break;
                case "GetPositionInfo":
                    out.put("Track", uri.isEmpty() ? "0" : "1");
                    out.put("TrackDuration", clock(durMs));
                    out.put("TrackMetaData", meta.isEmpty() ? "NOT_IMPLEMENTED" : meta);
                    out.put("TrackURI", uri);
                    out.put("RelTime", clock(posMs));
                    out.put("AbsTime", clock(posMs));
                    out.put("RelCount", "2147483647");
                    out.put("AbsCount", "2147483647");
                    break;
                case "GetMediaInfo":
                    out.put("NrTracks", uri.isEmpty() ? "0" : "1");
                    out.put("MediaDuration", clock(durMs));
                    out.put("CurrentURI", uri);
                    out.put("CurrentURIMetaData", meta);
                    out.put("NextURI", "");
                    out.put("NextURIMetaData", "");
                    out.put("PlayMedium", "NETWORK");
                    out.put("RecordMedium", "NOT_IMPLEMENTED");
                    out.put("WriteStatus", "NOT_IMPLEMENTED");
                    break;
                case "GetTransportSettings":
                    out.put("PlayMode", "NORMAL");
                    out.put("RecQualityMode", "NOT_IMPLEMENTED");
                    break;
                case "GetDeviceCapabilities":
                    out.put("PlayMedia", "NETWORK");
                    out.put("RecMedia", "NOT_IMPLEMENTED");
                    out.put("RecQualityModes", "NOT_IMPLEMENTED");
                    break;
                case "GetCurrentTransportActions":
                    out.put("Actions", actions());
                    break;
                default:
                    fault = "401|Invalid Action";
            }
        } else if (service.equals(RC)) {
            AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
            switch (action) {
                case "GetVolume":
                    out.put("CurrentVolume", String.valueOf(volume(am)));
                    break;
                case "SetVolume": {
                    int v = Math.max(0, Math.min(100, number(arg(body, "DesiredVolume"))));
                    if (am != null) {
                        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                        am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(v * max / 100f), AudioManager.FLAG_SHOW_UI);
                    }
                    pool.execute(() -> event(RC));
                    break;
                }
                case "GetMute":
                    out.put("CurrentMute", am != null && muted(am) ? "1" : "0");
                    break;
                case "SetMute": {
                    String m = arg(body, "DesiredMute");
                    boolean mute = "1".equals(m) || "true".equalsIgnoreCase(m);
                    if (am != null) {
                        if (Build.VERSION.SDK_INT >= 23) am.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                                mute ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI);
                        else //noinspection deprecation
                            am.setStreamMute(AudioManager.STREAM_MUSIC, mute);
                    }
                    pool.execute(() -> event(RC));
                    break;
                }
                case "ListPresets":
                    out.put("CurrentPresetNameList", "FactoryDefaults");
                    break;
                case "SelectPreset":
                    break;
                default:
                    fault = "401|Invalid Action";
            }
        } else {
            switch (action) {
                case "GetProtocolInfo":
                    out.put("Source", "");
                    out.put("Sink", SINK);
                    break;
                case "GetCurrentConnectionIDs":
                    out.put("ConnectionIDs", "0");
                    break;
                case "GetCurrentConnectionInfo":
                    out.put("RcsID", "0");
                    out.put("AVTransportID", "0");
                    out.put("ProtocolInfo", "");
                    out.put("PeerConnectionManager", "");
                    out.put("PeerConnectionID", "-1");
                    out.put("Direction", "Input");
                    out.put("Status", "OK");
                    break;
                default:
                    fault = "401|Invalid Action";
            }
        }
        if (fault != null) {
            String[] f = fault.split("\\|", 2);
            send(s, 500, "<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                    + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><s:Fault><faultcode>s:Client</faultcode>"
                    + "<faultstring>UPnPError</faultstring><detail><UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\"><errorCode>"
                    + f[0] + "</errorCode><errorDescription>" + esc(f[1]) + "</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>", false);
            return;
        }
        StringBuilder b = new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:" + action + "Response xmlns:u=\"" + service + "\">");
        for (Map.Entry<String, String> e : out.entrySet()) b.append('<').append(e.getKey()).append('>').append(esc(e.getValue())).append("</").append(e.getKey()).append('>');
        send(s, 200, b.append("</u:").append(action).append("Response></s:Body></s:Envelope>").toString(), false);
    }

    /** A new address to play (and what the sender says it is): the player, if open, loads it. */
    private void take(String u, String m) {
        uri = u == null ? "" : u.trim();
        meta = m == null ? "" : m;
        String didl = meta;
        title = tag(didl, "dc:title");
        by = tag(didl, "upnp:artist");
        if (by.isEmpty()) by = tag(didl, "dc:creator");
        String cls = tag(didl, "upnp:class");
        Matcher pi = Pattern.compile("protocolInfo=\"[^:]*:[^:]*:([^:\"]+)").matcher(didl);
        mime = pi.find() ? pi.group(1) : "";
        if (mime.isEmpty() || mime.equals("*")) {
            String path = uri.toLowerCase(Locale.ROOT);
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);
            mime = path.matches(".*\\.(jpe?g|png|gif|webp)$") ? "image/*" : path.matches(".*\\.(mp3|m4a|aac|flac|ogg|wav)$") ? "audio/*" : "video/*";
        }
        if (cls.contains("imageItem")) mime = mime.startsWith("image/") ? mime : "image/*";
        else if (cls.contains("audioItem") && !mime.startsWith("audio/")) mime = "audio/*";
        if (title.isEmpty()) {
            String path = uri;
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);
            title = path.substring(path.lastIndexOf('/') + 1);
        }
        posMs = 0;
        durMs = 0;
        setState("STOPPED");
        main.post(() -> {
            Player p = player;
            if (p != null) p.load(false);
        });
    }

    private void playNow() {
        Player p = player;
        if (p != null) {
            p.play();
            return;
        }
        setState("TRANSITIONING");
        try {
            app.startActivity(new Intent(app, Cast.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            Trouble.caught("DLNA play", e);
            setState("STOPPED");
        }
    }

    // ---- events (GENA) -----------------------------------------------------------------------

    private void subscribe(Socket s, String service, Map<String, String> head) throws IOException {
        long until = System.currentTimeMillis() + MAX_AGE * 1000L;
        String sid = head.get("sid");
        if (sid != null && head.get("callback") == null) {
            Sub old = subs.get(sid);
            if (old == null) {
                raw(s, "HTTP/1.1 412 Precondition Failed\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
                return;
            }
            old.until = until;
        } else {
            List<URL> cbs = new ArrayList<>();
            Matcher m = Pattern.compile("<([^>]+)>").matcher(head.get("callback") != null ? head.get("callback") : "");
            while (m.find()) {
                try {
                    URL u = new URL(m.group(1));
                    if ("http".equals(u.getProtocol())) cbs.add(u);
                } catch (IOException ignored) {
                }
            }
            if (cbs.isEmpty()) {
                raw(s, "HTTP/1.1 412 Precondition Failed\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
                return;
            }
            sid = "uuid:" + UUID.randomUUID();
            subs.put(sid, new Sub(service, cbs, until));
        }
        raw(s, "HTTP/1.1 200 OK\r\nDATE: " + date() + "\r\nSERVER: " + server() + "\r\nSID: " + sid
                + "\r\nTIMEOUT: Second-" + MAX_AGE + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
        // the first event carries everything, so the controller starts in step
        final String first = sid;
        pool.execute(() -> {
            Sub sub = subs.get(first);
            if (sub != null) deliver(first, sub);
        });
    }

    /** Something changed (playing, paused, stopped, a new address, the volume): every listener hears it. */
    static void changed() {
        Renderer r = running;
        if (r != null) r.pool.execute(() -> r.event(AVT));
    }

    static void setState(String s) {
        if (s.equals(state)) return;
        state = s;
        changed();
    }

    private void event(String service) {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Sub> e : subs.entrySet()) {
            Sub sub = e.getValue();
            if (sub.until < now) {
                subs.remove(e.getKey());
                continue;
            }
            if (sub.service.equals(service)) deliver(e.getKey(), sub);
        }
    }

    private void deliver(String sid, Sub sub) {
        String props;
        if (sub.service.equals(AVT)) {
            props = "<LastChange>" + esc("<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/AVT/\"><InstanceID val=\"0\">"
                    + "<TransportState val=\"" + state + "\"/><TransportStatus val=\"OK\"/><CurrentPlayMode val=\"NORMAL\"/>"
                    + "<TransportPlaySpeed val=\"1\"/><NumberOfTracks val=\"" + (uri.isEmpty() ? 0 : 1) + "\"/>"
                    + "<CurrentTrack val=\"" + (uri.isEmpty() ? 0 : 1) + "\"/><AVTransportURI val=\"" + esc(uri) + "\"/>"
                    + "<CurrentTrackURI val=\"" + esc(uri) + "\"/><AVTransportURIMetaData val=\"" + esc(meta) + "\"/>"
                    + "<CurrentTrackMetaData val=\"" + esc(meta) + "\"/><CurrentMediaDuration val=\"" + clock(durMs) + "\"/>"
                    + "<CurrentTrackDuration val=\"" + clock(durMs) + "\"/><CurrentTransportActions val=\"" + actions() + "\"/>"
                    + "</InstanceID></Event>") + "</LastChange>";
        } else if (sub.service.equals(RC)) {
            AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
            props = "<LastChange>" + esc("<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/RCS/\"><InstanceID val=\"0\">"
                    + "<Volume channel=\"Master\" val=\"" + volume(am) + "\"/><Mute channel=\"Master\" val=\""
                    + (am != null && muted(am) ? 1 : 0) + "\"/><PresetNameList val=\"FactoryDefaults\"/></InstanceID></Event>") + "</LastChange>";
        } else {
            props = "<SourceProtocolInfo></SourceProtocolInfo></e:property><e:property><SinkProtocolInfo>" + esc(SINK)
                    + "</SinkProtocolInfo></e:property><e:property><CurrentConnectionIDs>0</CurrentConnectionIDs>";
        }
        String body = "<?xml version=\"1.0\" encoding=\"utf-8\"?><e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\"><e:property>"
                + props + "</e:property></e:propertyset>";
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        int seq;
        synchronized (sub) {
            seq = sub.seq;
            sub.seq = seq == Integer.MAX_VALUE ? 1 : seq + 1;
        }
        for (URL u : sub.callbacks) {
            int port = u.getPort() > 0 ? u.getPort() : 80;
            try (Socket c = new Socket()) {
                c.connect(new InetSocketAddress(u.getHost(), port), 3000);
                c.setSoTimeout(5000);
                String path = u.getFile().isEmpty() ? "/" : u.getFile();
                String h = "NOTIFY " + path + " HTTP/1.1\r\nHOST: " + u.getHost() + ":" + port
                        + "\r\nCONTENT-TYPE: text/xml; charset=\"utf-8\"\r\nNT: upnp:event\r\nNTS: upnp:propchange\r\nSID: " + sid
                        + "\r\nSEQ: " + seq + "\r\nContent-Length: " + b.length + "\r\nConnection: close\r\n\r\n";
                OutputStream o = c.getOutputStream();
                o.write(h.getBytes(StandardCharsets.ISO_8859_1));
                o.write(b);
                o.flush();
                c.getInputStream().read();
                return;
            } catch (IOException ignored) {
                // that address didn't answer: the next one the controller gave
            }
        }
    }

    private static String actions() {
        switch (state) {
            case "PLAYING":
                return "Pause,Stop,Seek";
            case "PAUSED_PLAYBACK":
                return "Play,Stop,Seek";
            case "STOPPED":
                return uri.isEmpty() ? "" : "Play";
            default:
                return "";
        }
    }

    // ---- the services' descriptions ----------------------------------------------------------

    /** Each action: its name, then "in:Arg:Variable" / "out:Arg:Variable" for its arguments. */
    private static final String[][] AVT_ACTIONS = {
            {"SetAVTransportURI", "in:InstanceID:A_ARG_TYPE_InstanceID", "in:CurrentURI:AVTransportURI", "in:CurrentURIMetaData:AVTransportURIMetaData"},
            {"SetNextAVTransportURI", "in:InstanceID:A_ARG_TYPE_InstanceID", "in:NextURI:NextAVTransportURI", "in:NextURIMetaData:NextAVTransportURIMetaData"},
            {"GetMediaInfo", "in:InstanceID:A_ARG_TYPE_InstanceID", "out:NrTracks:NumberOfTracks", "out:MediaDuration:CurrentMediaDuration",
                    "out:CurrentURI:AVTransportURI", "out:CurrentURIMetaData:AVTransportURIMetaData", "out:NextURI:NextAVTransportURI",
                    "out:NextURIMetaData:NextAVTransportURIMetaData", "out:PlayMedium:PlaybackStorageMedium",
                    "out:RecordMedium:RecordStorageMedium", "out:WriteStatus:RecordMediumWriteStatus"},
            {"GetTransportInfo", "in:InstanceID:A_ARG_TYPE_InstanceID", "out:CurrentTransportState:TransportState",
                    "out:CurrentTransportStatus:TransportStatus", "out:CurrentSpeed:TransportPlaySpeed"},
            {"GetPositionInfo", "in:InstanceID:A_ARG_TYPE_InstanceID", "out:Track:CurrentTrack", "out:TrackDuration:CurrentTrackDuration",
                    "out:TrackMetaData:CurrentTrackMetaData", "out:TrackURI:CurrentTrackURI", "out:RelTime:RelativeTimePosition",
                    "out:AbsTime:AbsoluteTimePosition", "out:RelCount:RelativeCounterPosition", "out:AbsCount:AbsoluteCounterPosition"},
            {"GetDeviceCapabilities", "in:InstanceID:A_ARG_TYPE_InstanceID", "out:PlayMedia:PossiblePlaybackStorageMedia",
                    "out:RecMedia:PossibleRecordStorageMedia", "out:RecQualityModes:PossibleRecordQualityModes"},
            {"GetTransportSettings", "in:InstanceID:A_ARG_TYPE_InstanceID", "out:PlayMode:CurrentPlayMode", "out:RecQualityMode:CurrentRecordQualityMode"},
            {"GetCurrentTransportActions", "in:InstanceID:A_ARG_TYPE_InstanceID", "out:Actions:CurrentTransportActions"},
            {"Stop", "in:InstanceID:A_ARG_TYPE_InstanceID"},
            {"Play", "in:InstanceID:A_ARG_TYPE_InstanceID", "in:Speed:TransportPlaySpeed"},
            {"Pause", "in:InstanceID:A_ARG_TYPE_InstanceID"},
            {"Seek", "in:InstanceID:A_ARG_TYPE_InstanceID", "in:Unit:A_ARG_TYPE_SeekMode", "in:Target:A_ARG_TYPE_SeekTarget"},
    };
    /** Each state variable: "name:type", and "!" first when it's sent as an event. */
    private static final String[] AVT_VARS = {"TransportState:string", "TransportStatus:string", "PlaybackStorageMedium:string",
            "RecordStorageMedium:string", "PossiblePlaybackStorageMedia:string", "PossibleRecordStorageMedia:string",
            "CurrentPlayMode:string", "TransportPlaySpeed:string", "RecordMediumWriteStatus:string", "CurrentRecordQualityMode:string",
            "PossibleRecordQualityModes:string", "NumberOfTracks:ui4", "CurrentTrack:ui4", "CurrentTrackDuration:string",
            "CurrentMediaDuration:string", "CurrentTrackMetaData:string", "CurrentTrackURI:string", "AVTransportURI:string",
            "AVTransportURIMetaData:string", "NextAVTransportURI:string", "NextAVTransportURIMetaData:string",
            "RelativeTimePosition:string", "AbsoluteTimePosition:string", "RelativeCounterPosition:i4", "AbsoluteCounterPosition:i4",
            "CurrentTransportActions:string", "!LastChange:string", "A_ARG_TYPE_SeekMode:string", "A_ARG_TYPE_SeekTarget:string",
            "A_ARG_TYPE_InstanceID:ui4"};
    private static final String[][] RC_ACTIONS = {
            {"ListPresets", "in:InstanceID:A_ARG_TYPE_InstanceID", "out:CurrentPresetNameList:PresetNameList"},
            {"SelectPreset", "in:InstanceID:A_ARG_TYPE_InstanceID", "in:PresetName:A_ARG_TYPE_PresetName"},
            {"GetMute", "in:InstanceID:A_ARG_TYPE_InstanceID", "in:Channel:A_ARG_TYPE_Channel", "out:CurrentMute:Mute"},
            {"SetMute", "in:InstanceID:A_ARG_TYPE_InstanceID", "in:Channel:A_ARG_TYPE_Channel", "in:DesiredMute:Mute"},
            {"GetVolume", "in:InstanceID:A_ARG_TYPE_InstanceID", "in:Channel:A_ARG_TYPE_Channel", "out:CurrentVolume:Volume"},
            {"SetVolume", "in:InstanceID:A_ARG_TYPE_InstanceID", "in:Channel:A_ARG_TYPE_Channel", "in:DesiredVolume:Volume"},
    };
    private static final String[] RC_VARS = {"PresetNameList:string", "!LastChange:string", "Mute:boolean", "Volume:ui2",
            "A_ARG_TYPE_Channel:string", "A_ARG_TYPE_InstanceID:ui4", "A_ARG_TYPE_PresetName:string"};
    private static final String[][] CM_ACTIONS = {
            {"GetProtocolInfo", "out:Source:SourceProtocolInfo", "out:Sink:SinkProtocolInfo"},
            {"GetCurrentConnectionIDs", "out:ConnectionIDs:CurrentConnectionIDs"},
            {"GetCurrentConnectionInfo", "in:ConnectionID:A_ARG_TYPE_ConnectionID", "out:RcsID:A_ARG_TYPE_RcsID",
                    "out:AVTransportID:A_ARG_TYPE_AVTransportID", "out:ProtocolInfo:A_ARG_TYPE_ProtocolInfo",
                    "out:PeerConnectionManager:A_ARG_TYPE_ConnectionManager", "out:PeerConnectionID:A_ARG_TYPE_ConnectionID",
                    "out:Direction:A_ARG_TYPE_Direction", "out:Status:A_ARG_TYPE_ConnectionStatus"},
    };
    private static final String[] CM_VARS = {"!SourceProtocolInfo:string", "!SinkProtocolInfo:string", "!CurrentConnectionIDs:string",
            "A_ARG_TYPE_ConnectionStatus:string", "A_ARG_TYPE_ConnectionManager:string", "A_ARG_TYPE_Direction:string",
            "A_ARG_TYPE_ProtocolInfo:string", "A_ARG_TYPE_ConnectionID:i4", "A_ARG_TYPE_AVTransportID:i4", "A_ARG_TYPE_RcsID:i4"};

    private static String scpd(String[][] actions, String[] vars) {
        StringBuilder b = new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">"
                + "<specVersion><major>1</major><minor>0</minor></specVersion><actionList>");
        for (String[] a : actions) {
            b.append("<action><name>").append(a[0]).append("</name>");
            if (a.length > 1) {
                b.append("<argumentList>");
                for (int i = 1; i < a.length; i++) {
                    String[] p = a[i].split(":");
                    b.append("<argument><name>").append(p[1]).append("</name><direction>").append(p[0])
                            .append("</direction><relatedStateVariable>").append(p[2]).append("</relatedStateVariable></argument>");
                }
                b.append("</argumentList>");
            }
            b.append("</action>");
        }
        b.append("</actionList><serviceStateTable>");
        for (String v : vars) {
            boolean events = v.startsWith("!");
            String[] p = (events ? v.substring(1) : v).split(":");
            b.append("<stateVariable sendEvents=\"").append(events ? "yes" : "no").append("\"><name>").append(p[0])
                    .append("</name><dataType>").append(p[1]).append("</dataType></stateVariable>");
        }
        return b.append("</serviceStateTable></scpd>").toString();
    }

    // ---- small things ------------------------------------------------------------------------

    private static int volume(AudioManager am) {
        if (am == null) return 0;
        int max = Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        return Math.round(am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max);
    }

    private static boolean muted(AudioManager am) {
        return Build.VERSION.SDK_INT >= 23 ? am.isStreamMute(AudioManager.STREAM_MUSIC) : am.getStreamVolume(AudioManager.STREAM_MUSIC) == 0;
    }

    /** An argument from a SOAP request, its XML escaping undone; "" when absent. */
    static String arg(String body, String name) {
        Matcher m = Pattern.compile("<(?:[\\w-]+:)?" + name + "(?:\\s[^>]*)?(?:/>|>(.*?)</(?:[\\w-]+:)?" + name + ">)", Pattern.DOTALL).matcher(body);
        return m.find() && m.group(1) != null ? unesc(m.group(1)) : "";
    }

    /** An element's text from DIDL-Lite metadata (already unescaped once). */
    static String tag(String didl, String name) {
        Matcher m = Pattern.compile("<" + Pattern.quote(name) + "(?:\\s[^>]*)?>(.*?)</" + Pattern.quote(name) + ">", Pattern.DOTALL).matcher(didl);
        return m.find() ? unesc(m.group(1)).trim() : "";
    }

    /** "H:MM:SS" (UPnP's time) from milliseconds. */
    static String clock(long ms) {
        long s = Math.max(0, ms) / 1000;
        return String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60);
    }

    /** Milliseconds from "H:MM:SS[.fff]", or -1. */
    static long clock(String t) {
        if (t == null) return -1;
        Matcher m = Pattern.compile("^\\s*(\\d+):(\\d{1,2}):(\\d{1,2})(?:\\.(\\d+))?").matcher(t);
        if (!m.find()) return -1;
        long ms = (Long.parseLong(m.group(1)) * 3600 + Long.parseLong(m.group(2)) * 60 + Long.parseLong(m.group(3))) * 1000;
        if (m.group(4) != null) ms += Long.parseLong((m.group(4) + "00").substring(0, 3));
        return ms;
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }

    static String unesc(String s) {
        if (s == null) return "";
        if (s.startsWith("<![CDATA[") && s.endsWith("]]>")) return s.substring(9, s.length() - 3);
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
                .replace("&#39;", "'").replace("&amp;", "&");
    }

    private static Map<String, String> headers(String msg) {
        Map<String, String> h = new HashMap<>();
        for (String l : msg.split("\r?\n")) {
            int c = l.indexOf(':');
            if (c > 0) h.put(l.substring(0, c).trim().toLowerCase(Locale.ROOT), l.substring(c + 1).trim());
        }
        return h;
    }

    private static int number(String s) {
        try {
            return s == null ? 0 : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (int c; (c = in.read()) != -1 && c != '\n'; ) {
            if (c != '\r') b.write(c);
            if (b.size() > 8192) throw new IOException("header too long");
        }
        return b.toString("ISO-8859-1");
    }

    private static byte[] body(InputStream in, Map<String, String> head, int max) throws IOException {
        int len = Math.max(0, Math.min(number(head.get("content-length")), max));
        byte[] b = new byte[len];
        int done = 0;
        while (done < len) {
            int n = in.read(b, done, len - done);
            if (n < 0) break;
            done += n;
        }
        return done == len ? b : java.util.Arrays.copyOf(b, done);
    }

    private static void send(Socket s, int code, String xml, boolean headOnly) throws IOException {
        byte[] b = xml.getBytes(StandardCharsets.UTF_8);
        String h = "HTTP/1.1 " + code + (code == 200 ? " OK" : " Internal Server Error") + "\r\nContent-Type: text/xml; charset=\"utf-8\""
                + "\r\nContent-Length: " + b.length + "\r\nEXT:\r\nSERVER: " + server() + "\r\nConnection: close\r\n\r\n";
        OutputStream o = s.getOutputStream();
        o.write(h.getBytes(StandardCharsets.ISO_8859_1));
        if (!headOnly) o.write(b);
        o.flush();
    }

    private static void raw(Socket s, String text) throws IOException {
        OutputStream o = s.getOutputStream();
        o.write(text.getBytes(StandardCharsets.ISO_8859_1));
        o.flush();
    }

    private static void close(java.io.Closeable c) {
        try {
            if (c != null) c.close();
        } catch (IOException ignored) {
        }
    }
}
