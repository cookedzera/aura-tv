package app.aura.home;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * New today on your apps: episodes released on streaming services in TVmaze's free schedule
 * (no account or key), kept only for services installed on this TV, each with the show's wide
 * background art. Fetched once a day in the background; the Smart Stack reads it from disk.
 */
final class Fresh {
    static final class Show {
        final String name, episode, channel, pkg, art;

        Show(JSONObject o) {
            name = o.optString("name");
            episode = o.optString("episode");
            channel = o.optString("channel");
            pkg = o.optString("pkg");
            art = o.optString("art");
        }
    }

    /** Service name as TVmaze writes it, then the Android TV packages that play it. */
    private static final String[][] SERVICES = {
            {"netflix", "com.netflix.ninja", "com.netflix.mediaclient"},
            {"prime video", "com.amazon.amazonvideo.livingroom", "com.amazon.avod.thirdpartyclient"},
            {"amazon", "com.amazon.amazonvideo.livingroom", "com.amazon.avod.thirdpartyclient"},
            {"hotstar", "in.startv.hotstar", "in.startv.hotstartvonly"},
            {"jiocinema", "com.jio.media.stb.ondemand", "in.startv.hotstar"},
            {"sonyliv", "com.sonyliv"},
            {"zee5", "com.graymatrix.did"},
            {"youtube", "com.google.android.youtube.tv", "io.gh.reisxd.tizentube.cobalt"},
            {"apple tv", "com.apple.atve.androidtv.appletv"},
            {"mx player", "com.mxtech.videoplayer.television"},
            {"crunchyroll", "com.crunchyroll.crunchyroid"},
    };
    // index renamed when the choosing rules change, so an older day's list is fetched again
    private static final String DIR = "fresh", INDEX = "fresh2.json";
    private static final String API = "https://api.tvmaze.com/";
    private static final String UA = "AuraTV/7 (open-source Android TV home screen)";
    private static final int KEEP = 6;

    private Fresh() { }

    /** True when today's list hasn't been fetched yet. */
    static boolean stale(Context c) {
        File f = new File(dir(c), INDEX);
        return !f.isFile() || !day(new Date(f.lastModified())).equals(day(new Date()));
    }

    static List<Show> load(Context c) {
        List<Show> out = new ArrayList<>();
        File f = new File(dir(c), INDEX);
        if (!f.isFile()) return out;
        try (InputStream in = new FileInputStream(f)) {
            JSONArray a = new JSONArray(new String(readAll(in), StandardCharsets.UTF_8));
            for (int i = 0; i < a.length(); i++) {
                Show s = new Show(a.getJSONObject(i));
                if (installed(c, s.pkg)) out.add(s);
            }
        } catch (IOException | JSONException ignored) {
        }
        return out;
    }

    /** The art, small enough for a stack card: about 640 px wide, 16-bit colour. */
    static Bitmap art(Show s) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(s.art, o);
        o.inSampleSize = o.outWidth > 1280 ? 2 : 1;
        o.inJustDecodeBounds = false;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(s.art, o);
    }

    /** Today's and yesterday's releases (a midnight release can carry either date), newest first. */
    static void refresh(Context c) throws IOException, InterruptedException {
        if (!stale(c)) return;
        File dir = dir(c);
        if (!dir.isDirectory() && !dir.mkdirs()) return;
        JSONArray out = new JSONArray();
        Set<Integer> seen = new HashSet<>();
        List<JSONObject[]> picks = new ArrayList<>();
        long now = System.currentTimeMillis();
        try {
            for (long d : new long[]{now, now - 86_400_000L}) {
                JSONArray eps = new JSONArray(new String(get(API + "schedule/web?date=" + day(new Date(d))), StandardCharsets.UTF_8));
                for (int i = 0; i < eps.length(); i++) {
                    JSONObject ep = eps.getJSONObject(i);
                    JSONObject show = ep.optJSONObject("_embedded") != null ? ep.getJSONObject("_embedded").optJSONObject("show") : null;
                    if (show == null || !seen.add(show.optInt("id")) || score(show) < 0) continue;
                    JSONObject ch = show.optJSONObject("webChannel");
                    if (ch == null || packageFor(c, ch.optString("name")) == null) continue;
                    picks.add(new JSONObject[]{ep, show});
                }
            }
            // what's from here first, then what most people are watching
            java.util.Collections.sort(picks, (a, b) -> Integer.compare(score(b[1]), score(a[1])));
            for (JSONObject[] p : picks) {
                if (out.length() >= KEEP) break;
                JSONObject ep = p[0], show = p[1], ch = show.getJSONObject("webChannel");
                File img = new File(dir, "s" + show.optInt("id") + ".jpg");
                if (!img.isFile() && !download(background(show), img)) continue;
                JSONObject e = new JSONObject();
                e.put("name", show.optString("name"));
                e.put("episode", episode(ep));
                e.put("channel", ch.optString("name"));
                e.put("pkg", packageFor(c, ch.optString("name")));
                e.put("art", img.getAbsolutePath());
                out.put(e);
                Thread.sleep(600); // TVmaze asks for gentle request rates
            }
        } catch (JSONException e) {
            return;
        }
        String keep = out.toString();
        File[] old = dir.listFiles((d, n) -> n.endsWith(".jpg"));
        if (old != null) for (File f : old) if (!keep.contains(f.getName())) //noinspection ResultOfMethodCallIgnored
            f.delete();
        File tmp = new File(dir, INDEX + ".tmp");
        try (FileOutputStream fo = new FileOutputStream(tmp)) {
            fo.write(keep.getBytes(StandardCharsets.UTF_8));
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(new File(dir, INDEX));
    }

    /** India's languages, and the TV's own: anything else isn't something you'd sit down to watch. */
    private static final Set<String> INDIAN = new HashSet<>(java.util.Arrays.asList("hindi", "tamil", "telugu", "malayalam",
            "kannada", "marathi", "bengali", "punjabi", "gujarati", "urdu", "odia", "assamese"));

    /**
     * How much a show belongs on your stack, or -1 to leave it out. Indian shows lead, then
     * English (and the TV's own language), each ranked by TVmaze's popularity weight; weak
     * matches in a foreign language never make it.
     */
    private static int score(JSONObject show) {
        String lang = show.optString("language", "").toLowerCase(Locale.ROOT);
        JSONObject ch = show.optJSONObject("webChannel");
        JSONObject country = ch != null ? ch.optJSONObject("country") : null;
        boolean indian = INDIAN.contains(lang) || country != null && "IN".equals(country.optString("code"));
        String mine = Locale.getDefault().getDisplayLanguage(Locale.ENGLISH).toLowerCase(Locale.ROOT);
        boolean understood = indian || "english".equals(lang) || lang.equals(mine);
        if (!understood) return -1;
        int weight = show.optInt("weight", 0);
        // a niche English web show isn't news; an Indian one is, whatever its weight
        if (!indian && weight < 60) return -1;
        return (indian ? 1000 : 0) + weight;
    }

    /** "S2 · E5 · Episode title", whatever parts TVmaze has. */
    private static String episode(JSONObject ep) {
        StringBuilder b = new StringBuilder();
        if (ep.optInt("season") > 0) b.append("S").append(ep.optInt("season"));
        if (ep.optInt("number") > 0) b.append(b.length() > 0 ? " · " : "").append("E").append(ep.optInt("number"));
        String t = ep.optString("name", "");
        // placeholder names ("Episode 9", "TBA") say nothing; leave them out
        if (!t.isEmpty() && !t.matches("(?i)episode \\d+|tba|tbd|to be announced|untitled")) b.append(b.length() > 0 ? "  ·  " : "").append(t);
        return b.toString();
    }

    /** The show's widest background image, else its poster. */
    private static String background(JSONObject show) throws IOException, JSONException {
        JSONArray imgs = new JSONArray(new String(get(API + "shows/" + show.optInt("id") + "/images"), StandardCharsets.UTF_8));
        String best = null;
        for (int i = 0; i < imgs.length(); i++) {
            JSONObject im = imgs.getJSONObject(i);
            if (!"background".equals(im.optString("type"))) continue;
            JSONObject res = im.optJSONObject("resolutions");
            JSONObject pick = res == null ? null : res.optJSONObject("medium") != null ? res.optJSONObject("medium") : res.optJSONObject("original");
            if (pick != null) {
                best = pick.optString("url");
                if (im.optBoolean("main")) break;
            }
        }
        if (best == null && show.optJSONObject("image") != null) best = show.getJSONObject("image").optString("original");
        return best;
    }

    private static boolean download(String url, File to) {
        if (url == null || url.isEmpty()) return false;
        try (FileOutputStream fo = new FileOutputStream(to)) {
            fo.write(get(url));
            return true;
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            to.delete();
            return false;
        }
    }

    private static String packageFor(Context c, String service) {
        String s = service.toLowerCase(Locale.ROOT).replace("+", "").replace(" ", "");
        for (String[] row : SERVICES) {
            if (!s.contains(row[0].replace(" ", ""))) continue;
            for (int i = 1; i < row.length; i++) if (installed(c, row[i])) return row[i];
        }
        return null;
    }

    private static boolean installed(Context c, String pkg) {
        try {
            c.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private static String day(Date d) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(d);
    }

    private static File dir(Context c) {
        return new File(c.getFilesDir(), DIR);
    }

    private static byte[] get(String url) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setRequestProperty("User-Agent", UA);
        con.setConnectTimeout(15_000);
        con.setReadTimeout(30_000);
        try {
            if (con.getResponseCode() != 200) throw new IOException("HTTP " + con.getResponseCode());
            try (InputStream in = con.getInputStream()) {
                return readAll(in);
            }
        } finally {
            con.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        for (int n; (n = in.read(buf)) > 0; ) bo.write(buf, 0, n);
        return bo.toByteArray();
    }
}
