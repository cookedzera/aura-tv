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
 * The background job's schedule and two helpers the photograph sources share (a headline from a
 * title, the light a photograph belongs to). Wikimedia's Picture of the Day used to arrive here; it
 * made way for {@link Gallery}, and what it left on the TV is cleared on the next run.
 */
final class Daily {
    private static final String DIR = "daily", INDEX = "index.json";
    /** The periodic job and the catch-up job may fire together; imports run one at a time. */
    private static final Object LOCK = new Object();
    static final int JOB_DAILY = 41, JOB_NOW = 42;

    private Daily() { }

    // ---- scheduling --------------------------------------------------------------------------

    /** A check every few hours whenever there's a network, plus one shortly if today's photo isn't in yet. */
    static void schedule(Context c) {
        JobScheduler js = Compat.svc(c, JobScheduler.class);
        if (js == null) return;
        ComponentName job = new ComponentName(c, DailyJob.class);
        if (!Compat.pending(js, JOB_DAILY)) {
            js.schedule(new JobInfo.Builder(JOB_DAILY, job)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(6 * 3_600_000L)
                    .setPersisted(true)
                    .build());
        }
        if ((Gallery.hungry(c) || Picks.stale(c) || Fresh.stale(c)) && !Compat.pending(js, JOB_NOW)) {
            js.schedule(new JobInfo.Builder(JOB_NOW, job)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    // just after the TV started, Android is busy enough: the catch-up waits a few minutes
                    .setMinimumLatency(Compat.justBooted() ? 180_000 : 20_000)
                    .build());
        }
    }

    // ---- reading (for the backdrop) ----------------------------------------------------------

    /** A run in a few seconds, whatever the schedule: the photo sources just changed. */
    static void soon(Context c) {
        JobScheduler js = Compat.svc(c, JobScheduler.class);
        if (js == null) return;
        js.schedule(new JobInfo.Builder(JOB_NOW, new ComponentName(c, DailyJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setMinimumLatency(3_000)
                .build());
    }

    static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
    }

    /** Changes whenever the importer writes, so readers can tell when to look again. */
    static long stamp(Context c) {
        return new File(dir(c), INDEX).lastModified();
    }

    /**
     * Imported photographs, newest first, skipping any whose file has gone. Wikimedia's Picture of the
     * Day has made way for {@link Gallery}: nothing is imported any more, so this is empty.
     */
    static List<Backdrop.Wallpaper> walls(Context c) {
        List<Backdrop.Wallpaper> out = new ArrayList<>();
        JSONArray index = readIndex(c);
        for (int i = 0; i < index.length(); i++) {
            JSONObject o = index.optJSONObject(i);
            if (o != null && new File(o.optString("path")).isFile()) out.add(new Backdrop.Wallpaper(o));
        }
        return out;
    }

    // ---- importing (on the job's thread) -----------------------------------------------------

    /**
     * Fetches today's photograph and any of the last few days not yet tried. Returns false if the
     * network failed part-way, so the job can try again later.
     */
    static boolean run(Context c) {
        synchronized (LOCK) {
            // Wikimedia's Picture of the Day made way for Gallery's sources: what it left on the TV goes
            File[] old = dir(c).listFiles();
            for (int i = 0; old != null && i < old.length; i++) //noinspection ResultOfMethodCallIgnored
                old[i].delete();
            return true;
        }
    }

    /** Which light the photograph belongs to, from its brightness and warmth, for Sky Sync. */
    static String mood(Bitmap b) {
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

    private static String cut(String s, int max) {
        if (s.length() <= max) return s;
        int sp = s.lastIndexOf(' ', max - 1);
        return s.substring(0, sp > max / 2 ? sp : max - 1).trim() + "…";
    }

    // ---- index ---------------------------------------------------------------------------------

    private static File dir(Context c) {
        return new File(c.getFilesDir(), DIR);
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
}
