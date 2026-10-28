package app.aura.home;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Updates from the project's GitHub Releases: check the latest, show its notes, and stream the
 * APK straight into Android's package installer, which asks you to confirm. Nothing runs in the
 * background; it only checks when you open Updates.
 */
final class Updater {
    private static final String LATEST = "https://api.github.com/repos/cookedzera/aura-tv/releases/latest";
    private static final String UA = "AuraTV-updater (https://github.com/cookedzera/aura-tv)";

    static final class Release {
        final String version, notes, apk;
        final long size;

        Release(String version, String notes, String apk, long size) {
            this.version = version;
            this.notes = notes;
            this.apk = apk;
            this.size = size;
        }
    }

    interface Progress {
        void onProgress(long done, long total);
    }

    private Updater() { }

    /** The latest release with an APK attached, or null if there's none (call off the main thread). */
    static Release latest() throws IOException, JSONException {
        HttpURLConnection con = open(LATEST);
        con.setRequestProperty("Accept", "application/vnd.github+json");
        try (InputStream in = con.getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
            JSONObject r = new JSONObject(new String(b.toByteArray(), StandardCharsets.UTF_8));
            JSONArray assets = r.optJSONArray("assets");
            for (int i = 0; assets != null && i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                if (a.optString("name").endsWith(".apk")) {
                    return new Release(r.optString("tag_name").replaceFirst("^[vV]", ""), plain(r.optString("body")),
                            a.optString("browser_download_url"), a.optLong("size"));
                }
            }
            return null;
        } finally {
            con.disconnect();
        }
    }

    /** True when {@code remote} is a later dotted version than {@code local} (7.10 is after 7.9). */
    static boolean newer(String remote, String local) {
        String[] a = remote.split("\\."), b = local.split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? num(a[i]) : 0, y = i < b.length ? num(b[i]) : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    // ---- noticing a new version ----------------------------------------------------------------

    /**
     * From the daily background job: remember the newest version on GitHub, so home can point it
     * out. One small request at most every few hours; never downloads anything by itself.
     */
    static void notice(Context c) {
        try {
            Release r = latest();
            if (r != null) Prefs.of(c).edit().putString("update_ver", r.version).apply();
        } catch (Exception ignored) {
            // offline or rate-limited: the next run tries again
        }
    }

    /** The newer version waiting on GitHub, or null when Aura is up to date. */
    static String pending(Context c) {
        String v = Prefs.of(c).getString("update_ver", null);
        return v != null && newer(v, installed(c)) ? v : null;
    }

    /** The red dot shows until you've opened Updates for this version. */
    static boolean unseen(Context c) {
        String v = pending(c);
        return v != null && !v.equals(Prefs.of(c).getString("update_seen", ""));
    }

    static void seen(Context c) {
        String v = pending(c);
        if (v != null) Prefs.of(c).edit().putString("update_seen", v).apply();
    }

    /** The one-time message: true the first time for each new version, then never again. */
    static boolean tellOnce(Context c) {
        String v = pending(c);
        if (v == null || v.equals(Prefs.of(c).getString("update_told", ""))) return false;
        Prefs.of(c).edit().putString("update_told", v).apply();
        return true;
    }

    static String installed(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "0";
        }
    }

    /** Whether Android lets Aura hand it an APK (Install unknown apps, granted at setup). */
    static boolean allowed(Context c) {
        return c.getPackageManager().canRequestPackageInstalls();
    }

    /** Downloads the release into an install session and commits it; Android then asks to confirm. */
    static void install(Context c, Release r, Progress p) throws IOException {
        HttpURLConnection con = open(r.apk);
        try (InputStream in = con.getInputStream()) {
            long total = con.getContentLengthLong() > 0 ? con.getContentLengthLong() : r.size;
            stream(c, in, total, c.getPackageName(), p);
        } finally {
            con.disconnect();
        }
    }

    /**
     * Streams an APK into an install session and commits it; Android then shows its own confirm
     * screen. {@code pkg} pins the session to one app (Aura's own updates), or null for any app,
     * such as one sent from a phone. Nothing is written to storage first.
     */
    static void stream(Context c, InputStream in, long total, String pkg, Progress p) throws IOException {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        if (pkg != null) params.setAppPackageName(pkg);
        int id = pi.createSession(params);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (OutputStream out = s.openWrite("app.apk", 0, total > 0 ? total : -1)) {
                byte[] buf = new byte[64 * 1024];
                long done = 0;
                while (total <= 0 || done < total) {
                    int n = in.read(buf, 0, total > 0 ? (int) Math.min(buf.length, total - done) : buf.length);
                    if (n < 0) {
                        if (total > 0) throw new IOException("cut short");
                        break;
                    }
                    out.write(buf, 0, n);
                    done += n;
                    p.onProgress(done, total);
                }
                s.fsync(out);
            }
            s.commit(PendingIntent.getBroadcast(c, id, new Intent(c, Done.class), PendingIntent.FLAG_UPDATE_CURRENT).getIntentSender());
        } catch (IOException | RuntimeException e) {
            pi.abandonSession(id);
            throw e;
        }
    }

    /** The installer's answer: show its confirmation screen, or say why it failed. */
    public static final class Done extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } else if (status != PackageInstaller.STATUS_SUCCESS) {
                String why = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                // a build signed with someone else's key can't replace this one
                boolean key = why != null && why.toUpperCase(java.util.Locale.ROOT).contains("SIGNATURE");
                boolean cancelled = status == PackageInstaller.STATUS_FAILURE_ABORTED;
                if (!cancelled) Toast.makeText(c, key ? "Signed with a different key than the installed app: uninstall that first"
                        : "It didn't install" + (why != null ? ": " + why : ""), Toast.LENGTH_LONG).show();
            }
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setRequestProperty("User-Agent", UA);
        con.setConnectTimeout(15_000);
        con.setReadTimeout(30_000);
        con.setInstanceFollowRedirects(true);
        return con;
    }

    /** Release notes as plain lines: Markdown's markers taken off. */
    private static String plain(String md) {
        return md.replaceAll("(?m)^#+\\s*", "").replaceAll("\\*\\*|__|`", "").replaceAll("(?m)^\\s*[-*]\\s+", "·  ")
                .replaceAll("\\[([^]]+)]\\([^)]+\\)", "$1").replaceAll("\\r", "").replaceAll("\\n{3,}", "\n\n").trim();
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9].*$", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
