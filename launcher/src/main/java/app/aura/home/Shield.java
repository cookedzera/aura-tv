package app.aura.home;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.SystemClock;
import android.provider.Settings;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Random;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Block ads: points the TV's own Private DNS (DNS over TLS, built into Android 9+) at a filtering
 * resolver, which answers ad and tracker servers with nothing, for every app at once. No VPN and
 * nothing running in the background. If the TV loses the internet with it on, it switches itself
 * back. Changing the setting needs WRITE_SECURE_SETTINGS, granted once at setup over ADB.
 */
final class Shield {
    /** hostname, name, what it blocks. Measured from India: the first is as strong as AdGuard and faster. */
    static final String[][] FILTERS = {
            {"x-hagezi-proplus.freedns.controld.com", "Hagezi Pro++", "Strongest, and fast"},
            {"dns.adguard-dns.com", "AdGuard", "Ads and trackers"},
            {"x-hagezi-pro.freedns.controld.com", "Hagezi Pro", "Strong, fewer surprises"},
            {"p2.freedns.controld.com", "Control D", "Ads, trackers, malware"},
            {"adblock.dns.mullvad.net", "Mullvad", "Private, no logs"},
            {"family.adguard-dns.com", "AdGuard Family", "Also hides adult sites"},
    };
    private static final String MODE = "private_dns_mode", SPECIFIER = "private_dns_specifier";

    /** One resolver's measured speed, and whether it answered an ad server with nothing. */
    static final class Probe {
        final long ms;
        final boolean blocks;

        Probe(long ms, boolean blocks) {
            this.ms = ms;
            this.blocks = blocks;
        }
    }

    private Shield() { }

    static boolean allowed(Context c) {
        return c.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
    }

    /** The resolver chosen in Aura: one of {@link #FILTERS}, or one you typed. */
    static String host(Context c) {
        String chosen = Prefs.of(c).getString("shield_host", null);
        if (chosen != null) return chosen;
        // from before there was a choice: keep whichever filter the TV already uses
        String current = Settings.Global.getString(c.getContentResolver(), SPECIFIER);
        for (String[] f : FILTERS) if (f[0].equals(current)) return current;
        return FILTERS[0][0];
    }

    static String name(Context c) {
        String h = host(c);
        for (String[] f : FILTERS) if (f[0].equals(h)) return f[1];
        return h;
    }

    static boolean on(Context c) {
        ContentResolver r = c.getContentResolver();
        return "hostname".equals(Settings.Global.getString(r, MODE)) && host(c).equals(Settings.Global.getString(r, SPECIFIER));
    }

    /** Turns blocking on, remembering what was set before, or puts that back. */
    static void set(Context c, boolean on) {
        ContentResolver r = c.getContentResolver();
        if (on) {
            // remember the TV's own setting the first time only, not one of ours
            if (!ours(Settings.Global.getString(r, SPECIFIER), c) || !"hostname".equals(Settings.Global.getString(r, MODE))) {
                Prefs.of(c).edit().putString("shield_prev_mode", String.valueOf(Settings.Global.getString(r, MODE)))
                        .putString("shield_prev_host", String.valueOf(Settings.Global.getString(r, SPECIFIER))).apply();
            }
            Settings.Global.putString(r, SPECIFIER, host(c));
            Settings.Global.putString(r, MODE, "hostname");
        } else {
            String mode = Prefs.of(c).getString("shield_prev_mode", "null"), host = Prefs.of(c).getString("shield_prev_host", "null");
            boolean mine = "null".equals(host) || ours(host, c);
            Settings.Global.putString(r, MODE, "null".equals(mode) || "hostname".equals(mode) && mine ? "opportunistic" : mode);
            Settings.Global.putString(r, SPECIFIER, mine ? null : host);
        }
    }

    /** Picks the resolver; if blocking is on, the TV switches to it straight away. */
    static void choose(Context c, String host) {
        boolean was = on(c);
        Prefs.of(c).edit().putString("shield_host", host.trim().toLowerCase(java.util.Locale.ROOT)).apply();
        if (was) set(c, true);
    }

    private static boolean ours(String host, Context c) {
        if (host == null) return false;
        if (host.equals(Prefs.of(c).getString("shield_host", ""))) return true;
        for (String[] f : FILTERS) if (f[0].equals(host)) return true;
        return false;
    }

    /**
     * After switching on (off the main thread): true once the TV is online through the chosen
     * resolver; false if it can't be reached, so the caller switches straight back.
     */
    static boolean verify(Context c) {
        ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
        String want = host(c);
        long until = SystemClock.uptimeMillis() + 15_000;
        while (SystemClock.uptimeMillis() < until) {
            Network n = cm.getActiveNetwork();
            LinkProperties lp = n != null ? cm.getLinkProperties(n) : null;
            NetworkCapabilities nc = n != null ? cm.getNetworkCapabilities(n) : null;
            boolean dnsUp = lp != null && lp.isPrivateDnsActive() && want.equals(lp.getPrivateDnsServerName());
            boolean online = nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            if (dnsUp && online) return true;
            SystemClock.sleep(1_000);
        }
        return false;
    }

    /**
     * Measures a resolver from this TV, the way Private DNS talks to it (DNS over TLS on port
     * 853): the median of three queries on one connection, and whether an ad server comes back
     * empty. Null if it can't be reached. Off the main thread.
     */
    static Probe probe(String host) {
        try (SSLSocket s = (SSLSocket) SSLSocketFactory.getDefault().createSocket(host, 853)) {
            s.setSoTimeout(5_000);
            s.startHandshake();
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, s.getSession())) return null;
            OutputStream out = s.getOutputStream();
            DataInputStream in = new DataInputStream(s.getInputStream());
            long[] t = new long[3];
            String[] names = {"www.google.com", "www.youtube.com", "api.hotstar.com"};
            for (int i = 0; i < 3; i++) {
                long a = SystemClock.elapsedRealtime();
                ask(out, in, names[i]);
                t[i] = SystemClock.elapsedRealtime() - a;
            }
            Arrays.sort(t);
            byte[] ad = ask(out, in, "pubads.g.doubleclick.net");
            return new Probe(t[1], empty(ad));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** One DNS question for an A record, framed for TCP/TLS; returns the raw answer. */
    private static byte[] ask(OutputStream out, DataInputStream in, String name) throws IOException {
        java.io.ByteArrayOutputStream q = new java.io.ByteArrayOutputStream();
        int id = new Random().nextInt(65536);
        q.write(new byte[]{(byte) (id >> 8), (byte) id, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0});
        for (String part : name.split("\\.")) {
            q.write(part.length());
            q.write(part.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        }
        q.write(new byte[]{0, 0, 1, 0, 1});
        byte[] b = q.toByteArray();
        out.write(new byte[]{(byte) (b.length >> 8), (byte) b.length});
        out.write(b);
        out.flush();
        byte[] answer = new byte[in.readUnsignedShort()];
        in.readFully(answer);
        return answer;
    }

    /** NXDOMAIN, no answers, or only 0.0.0.0: the resolver blocked it. */
    private static boolean empty(byte[] m) {
        if (m.length < 12) return true;
        if ((m[3] & 0x0F) == 3) return true;
        int answers = (m[6] & 0xFF) << 8 | (m[7] & 0xFF);
        if (answers == 0) return true;
        int i = 12;
        while (i < m.length && m[i] != 0) i += (m[i] & 0xFF) + 1;
        i += 5;
        for (int k = 0; k < answers && i + 10 <= m.length; k++) {
            i += (m[i] & 0xC0) == 0xC0 ? 2 : skipName(m, i);
            int type = (m[i] & 0xFF) << 8 | (m[i + 1] & 0xFF), len = (m[i + 8] & 0xFF) << 8 | (m[i + 9] & 0xFF);
            i += 10;
            if (type == 1 && len == 4 && i + 4 <= m.length && (m[i] | m[i + 1] | m[i + 2] | m[i + 3]) != 0) return false;
            i += len;
        }
        return true;
    }

    private static int skipName(byte[] m, int i) {
        int start = i;
        while (i < m.length && m[i] != 0) {
            if ((m[i] & 0xC0) == 0xC0) return i + 2 - start;
            i += (m[i] & 0xFF) + 1;
        }
        return i + 1 - start;
    }
}
