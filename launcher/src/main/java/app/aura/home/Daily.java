package app.aura.home;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Fresh photographs every day: Wikimedia Commons' Picture of the Day, fetched in the background with
 * no account or API key, sized for the TV and filed with its title, description, photographer and
 * licence. Landscape photographs only; the newest few dozen are kept and the rest deleted.
 * Requests are spaced out and back off when Wikimedia asks, as its API etiquette requires.
 */
final class Daily {
    private static final String DIR = "daily", INDEX = "index.json";
    private static final String FEED = "https://api.wikimedia.org/feed/v1/wikipedia/en/featured/";
    private static final String UA = "AuraTV/5.6 (open-source Android TV home screen; Wikimedia Picture of the Day)";
    private static final int KEEP = 40, BACKLOG_DAYS = 30, W = 1536, H = 864;
    /** The periodic job and the catch-up job may fire together; imports run one at a time. */
    private static final Object LOCK = new Object();
    static final int JOB_DAILY = 41, JOB_NOW = 42;
    private static final long GAP_MS = 6_000;

    private Daily() { }

    // ---- scheduling --------------------------------------------------------------------------

    /** A check every few hours whenever there's a network, plus one shortly if today's photo isn't in yet. */
    static void schedule(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null) return;
        ComponentName job = new ComponentName(c, DailyJob.class);
        if (js.getPendingJob(JOB_DAILY) == null) {
            js.schedule(new JobInfo.Builder(JOB_DAILY, job)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(6 * 3_600_000L)
                    .setPersisted(true)
                    .build());
        }
        if ((!have(c, today()) || Picks.stale(c) || Fresh.stale(c)) && js.getPendingJob(JOB_NOW) == null) {
            js.schedule(new JobInfo.Builder(JOB_NOW, job)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setMinimumLatency(20_000)
                    .build());
        }
    }

    // ---- reading (for the backdrop) ----------------------------------------------------------

    static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
    }

    /** Changes whenever the importer writes, so readers can tell when to look again. */
    static long stamp(Context c) {
        return new File(dir(c), INDEX).lastModified();
    }

    /** Imported photographs, newest first, skipping any whose file has gone. */
    static List<Backdrop.Wallpaper> walls(Context c) {
        List<Backdrop.Wallpaper> out = new ArrayList<>();
        JSONArray index = readIndex(c);
        for (int i = 0; i < index.length(); i++) {
            JSONObject o = index.optJSONObject(i);
            if (o != null && new File(o.optString("path")).isFile()) out.add(new Backdrop.Wallpaper(o));
        }
        return out;
    }

    private static boolean have(Context c, String day) {
        JSONArray index = readIndex(c);
        for (int i = 0; i < index.length(); i++) {
            if (day.equals(index.optJSONObject(i).optString("day"))) return true;
        }
        return prefs(c).getStringSet("daily_tried", new HashSet<>()).contains(day);
    }

    // ---- importing (on the job's thread) -----------------------------------------------------

    /**
     * Fetches today's photograph and any of the last few days not yet tried. Returns false if the
     * network failed part-way, so the job can try again later.
     */
    static boolean run(Context c) {
        synchronized (LOCK) {
            return runLocked(c);
        }
    }

    private static boolean runLocked(Context c) {
        if (!Prefs.on(c, Prefs.DAILY)) return true; // switched off in Settings
        File dir = dir(c);
        if (!dir.isDirectory() && !dir.mkdirs()) return true;
        JSONArray index = readIndex(c);
        Set<String> done = new HashSet<>(), tried = new HashSet<>(prefs(c).getStringSet("daily_tried", new HashSet<>()));
        for (int i = 0; i < index.length(); i++) done.add(index.optJSONObject(i).optString("day"));
        Calendar cal = Calendar.getInstance();
        SimpleDateFormat key = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT), path = new SimpleDateFormat("yyyy/MM/dd", Locale.ROOT);
        boolean complete = true, first = true;
        for (int d = 0; d < BACKLOG_DAYS; d++, cal.add(Calendar.DAY_OF_MONTH, -1)) {
            String day = key.format(cal.getTime());
            if (done.contains(day) || tried.contains(day)) continue;
            try {
                if (!first) Thread.sleep(GAP_MS);
                first = false;
                JSONObject entry = fetch(c, day, path.format(cal.getTime()), cal.getTime(), dir);
                if (entry != null) {
                    index.put(entry);
                    write(c, prune(c, index));
                }
                tried.add(day);
            } catch (InterruptedException e) {
                complete = false;
                break;
            } catch (IOException | JSONException | RuntimeException e) {
                complete = false;
                break;
            }
        }
        // remember only recent attempts, so the set stays small
        Set<String> recent = new HashSet<>();
        cal = Calendar.getInstance();
        for (int d = 0; d < BACKLOG_DAYS + 2; d++, cal.add(Calendar.DAY_OF_MONTH, -1)) {
            String day = key.format(cal.getTime());
            if (tried.contains(day)) recent.add(day);
        }
        prefs(c).edit().putStringSet("daily_tried", recent).apply();
        return complete;
    }

    /** One day's Picture of the Day, filed on disk; null if that day's picture isn't a landscape photograph. */
    private static JSONObject fetch(Context c, String day, String datePath, Date date, File dir)
            throws IOException, JSONException, InterruptedException {
        byte[] body = get(FEED + datePath);
        if (body == null) return null;
        JSONObject img = new JSONObject(new String(body, StandardCharsets.UTF_8)).optJSONObject("image");
        if (img == null) return null;
        JSONObject orig = img.optJSONObject("image");
        if (orig == null) return null;
        int ow = orig.optInt("width"), oh = orig.optInt("height");
        String src = orig.optString("source");
        int q = src.indexOf('?');
        if (q >= 0) src = src.substring(0, q);
        // TV-shaped photographs only: 5:4 or wider, large, and a JPEG (diagrams and scans tend to be PNG/TIFF/SVG)
        if (ow < 1920 || ow < oh * 1.24f || !src.toLowerCase(Locale.ROOT).matches(".+\\.jpe?g")) return null;

        Thread.sleep(GAP_MS);
        byte[] data = get(thumb(src, 1920));
        if (data == null) {
            Thread.sleep(GAP_MS);
            data = get(thumb(src, 1280));
        }
        if (data == null) return null;
        Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length);
        if (b == null) return null;
        Bitmap fit = cover(b);
        b.recycle();

        File out = new File(dir, day + ".jpg"), tmp = new File(dir, day + ".tmp");
        try (FileOutputStream fo = new FileOutputStream(tmp)) {
            fit.compress(Bitmap.CompressFormat.JPEG, 90, fo);
        }
        if (!tmp.renameTo(out)) throw new IOException("couldn't file " + out);

        String caption = clean(img.optJSONObject("structured") != null && img.optJSONObject("structured").optJSONObject("captions") != null
                ? img.optJSONObject("structured").optJSONObject("captions").optString("en") : "");
        String description = clean(img.optJSONObject("description") != null ? img.optJSONObject("description").optString("text") : "");
        JSONObject e = new JSONObject();
        e.put("file", out.getName());
        e.put("path", out.getAbsolutePath());
        e.put("day", day);
        e.put("place", place(img.optString("title"), caption.isEmpty() ? description : caption));
        e.put("region", "Picture of the day · " + new SimpleDateFormat("d MMMM", Locale.getDefault()).format(date));
        e.put("line", sentence(caption.isEmpty() ? description : caption, 150));
        e.put("author", cut(clean(img.optJSONObject("artist") != null ? img.optJSONObject("artist").optString("text") : ""), 40));
        e.put("license", img.optJSONObject("license") != null ? img.optJSONObject("license").optString("type") : "");
        e.put("source", img.optString("file_page"));
        e.put("mood", mood(fit));
        e.put("accent", String.format(Locale.ROOT, "#%06X", AppRepo.colorOf(new BitmapDrawable(c.getResources(), fit)) & 0xFFFFFF));
        fit.recycle();
        return e;
    }

    /**
     * GET with Wikimedia's etiquette: a descriptive User-Agent, and on 429/503 a pause (Retry-After
     * if given) before trying again. Null for a missing page; IOException when the network fails.
     */
    private static byte[] get(String url) throws IOException, InterruptedException {
        for (int attempt = 0; ; attempt++) {
            HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
            con.setRequestProperty("User-Agent", UA);
            con.setConnectTimeout(15_000);
            con.setReadTimeout(30_000);
            try {
                int code = con.getResponseCode();
                if (code == 429 || code == 503) {
                    if (attempt >= 3) throw new IOException("rate limited");
                    long wait = 20_000L * (attempt + 1);
                    try {
                        wait = Math.max(wait, Long.parseLong(con.getHeaderField("Retry-After")) * 1000);
                    } catch (NumberFormatException ignored) {
                    }
                    Thread.sleep(Math.min(wait, 120_000));
                    continue;
                }
                if (code == 404 || code == 400) return null;
                if (code != 200) throw new IOException("HTTP " + code);
                try (InputStream in = con.getInputStream()) {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] buf = new byte[16 * 1024];
                    for (int n; (n = in.read(buf)) > 0; ) {
                        if (Thread.interrupted()) throw new InterruptedIOException();
                        bo.write(buf, 0, n);
                    }
                    return bo.toByteArray();
                }
            } finally {
                con.disconnect();
            }
        }
    }

    /** Commons thumbnail URL: .../commons/5/51/Name.jpg becomes .../commons/thumb/5/51/Name.jpg/1920px-Name.jpg */
    private static String thumb(String src, int width) {
        int at = src.indexOf("/commons/");
        if (at < 0) return src;
        String head = src.substring(0, at + 9), tail = src.substring(at + 9);
        return head + "thumb/" + tail + "/" + width + "px-" + tail.substring(tail.lastIndexOf('/') + 1);
    }

    /** Center-cropped to the TV's photograph size, filtered. */
    private static Bitmap cover(Bitmap b) {
        Bitmap out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        float s = Math.max(W / (float) b.getWidth(), H / (float) b.getHeight());
        Matrix m = new Matrix();
        m.setScale(s, s);
        m.postTranslate((W - b.getWidth() * s) / 2f, (H - b.getHeight() * s) / 2f);
        new Canvas(out).drawBitmap(b, m, new Paint(Paint.FILTER_BITMAP_FLAG));
        return out;
    }

    /** Which light the photograph belongs to, from its brightness and warmth, for Sky Sync. */
    private static String mood(Bitmap b) {
        Bitmap s = Bitmap.createScaledBitmap(b, 48, 27, true);
        float luma = 0, warmth = 0;
        int n = s.getWidth() * s.getHeight();
        for (int y = 0; y < s.getHeight(); y++) {
            for (int x = 0; x < s.getWidth(); x++) {
                int p = s.getPixel(x, y);
                luma += (0.2126f * Color.red(p) + 0.7152f * Color.green(p) + 0.0722f * Color.blue(p)) / 255f;
                warmth += (Color.red(p) - Color.blue(p)) / 255f;
            }
        }
        s.recycle();
        luma /= n;
        warmth /= n;
        if (luma < 0.2f) return "night";
        if (warmth > 0.1f && luma < 0.62f) return "golden";
        return "day";
    }

    /** A headline from the file name, unless that's a camera's name for it; then the caption's opening words. */
    static String place(String title, String caption) {
        String t = title.replaceFirst("^File:", "").replaceFirst("\\.[A-Za-z0-9]+$", "").replace('_', ' ')
                .replaceAll("\\s*\\([^)]*\\)", "").trim();
        int comma = t.indexOf(',');
        if (comma > 8) t = t.substring(0, comma).trim();
        boolean camera = t.matches("(?i).*\\b(DSC|IMG|DJI|P\\d{6,}|\\d{6,}).*");
        if ((camera || t.length() > 40 || t.isEmpty()) && !caption.isEmpty()) {
            t = caption;
            comma = t.indexOf(',');
            if (comma > 8) t = t.substring(0, comma);
            t = t.replaceAll("[.]$", "").trim();
        }
        // a headline, not a sentence: end on a whole word, never on "in"/"of"/"the", and no ellipsis
        // (the full caption is shown just below it)
        if (t.length() > 40) {
            t = t.substring(0, t.lastIndexOf(' ', 40) > 20 ? t.lastIndexOf(' ', 40) : 40).trim();
            t = t.replaceAll("(?i)\\s+(in|at|of|on|the|and|with|from|a|an|to|by|its|their)$", "").trim();
        }
        return t;
    }

    private static String clean(String s) {
        return s == null ? "" : s.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim();
    }

    private static String sentence(String s, int max) {
        int dot = s.indexOf(". ");
        if (dot > 20) s = s.substring(0, dot + 1);
        return cut(s, max);
    }

    private static String cut(String s, int max) {
        if (s.length() <= max) return s;
        int sp = s.lastIndexOf(' ', max - 1);
        return s.substring(0, sp > max / 2 ? sp : max - 1).trim() + "…";
    }

    // ---- index ---------------------------------------------------------------------------------

    private static File dir(Context c) {
        return new File(c.getFilesDir(), DIR);
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("aura", Context.MODE_PRIVATE);
    }

    private static JSONArray readIndex(Context c) {
        File f = new File(dir(c), INDEX);
        if (!f.isFile()) return new JSONArray();
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) bo.write(buf, 0, n);
            return new JSONArray(new String(bo.toByteArray(), StandardCharsets.UTF_8));
        } catch (IOException | JSONException e) {
            return new JSONArray();
        }
    }

    /** Newest first, at most {@link #KEEP}; photographs that fall off the end are deleted. */
    private static JSONArray prune(Context c, JSONArray index) {
        List<JSONObject> all = new ArrayList<>();
        for (int i = 0; i < index.length(); i++) all.add(index.optJSONObject(i));
        all.sort((a, b) -> b.optString("day").compareTo(a.optString("day")));
        JSONArray out = new JSONArray();
        for (int i = 0; i < all.size(); i++) {
            if (i < KEEP) out.put(all.get(i));
            else //noinspection ResultOfMethodCallIgnored
                new File(all.get(i).optString("path")).delete();
        }
        return out;
    }

    private static void write(Context c, JSONArray index) throws IOException {
        File f = new File(dir(c), INDEX), tmp = new File(dir(c), INDEX + ".tmp");
        try (FileOutputStream fo = new FileOutputStream(tmp)) {
            fo.write(index.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(f)) throw new IOException("couldn't write the index");
    }
}
