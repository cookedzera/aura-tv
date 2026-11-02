package app.aura.home;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * What you watched, as the apps themselves said while they played (their media sessions, which
 * {@link MediaWatch} hears): the title, the app, how far in and how long, and a small copy of the
 * picture. Fire TV has no Watch Next list for Aura to read, older Android TVs neither, and many apps
 * never fill it: this is Continue watching from any app that plays through Android's media
 * sessions (Netflix, Prime Video and YouTube among them).
 *
 * Only what's long enough to be a programme (12 minutes or more) from apps that aren't for music or
 * books, unfinished, and from the last four days. The last twelve are kept, on the TV only.
 */
final class Watched {
    private static final String KEY = "watched", DIR = "watched";
    private static final long KEEP_MS = 4 * 86_400_000L, MIN_LENGTH = 12 * 60_000L;
    private static final int KEEP = 12;
    /** Apps whose long sessions are albums, podcasts and books, not something you're watching. */
    private static final Set<String> LISTENING = new HashSet<>(Arrays.asList(
            "com.spotify.tv.android", "com.spotify.music", "com.google.android.youtube.tvmusic",
            "com.amazon.mp3", "com.apple.android.music", "com.jio.media.jiobeats", "com.gaana",
            "com.bsbportal.music", "com.pandora.android", "com.deezer.android.tv", "com.aspiro.tidal",
            "com.soundcloud.android", "com.audible.application", "com.amazon.kindle", "tunein.player",
            "com.google.android.apps.podcasts", "com.pocketcasts.android"));
    private static final ExecutorService IO = Compat.background("watched", android.os.Process.THREAD_PRIORITY_BACKGROUND);
    /** The last write, so a session reporting every few seconds doesn't rewrite the list each time. */
    private static String lastKey;
    private static long lastAt;
    private static boolean lastPartway;

    private Watched() { }

    /**
     * {@link MediaWatch} heard a playback report: noted if it's a programme. Main thread; the list and
     * the picture are written behind it.
     */
    static void note(Context ctx, MediaWatch.Playing p, long length, long position) {
        if (ctx == null || p == null || p.title == null || p.title.trim().isEmpty() || p.pkg == null) return;
        if (length < MIN_LENGTH || position <= 0 || LISTENING.contains(p.pkg) || p.pkg.equals(ctx.getPackageName())) return;
        String key = p.pkg + "|" + Continue.key(p.title);
        long now = System.currentTimeMillis();
        float progress = Math.min(1f, position / (float) length);
        boolean partway = progress >= 0.02f && progress <= 0.95f;
        // a minute apart at most while it plays: enough to resume within a minute of where you were,
        // while crossing into or out of the list (past 2%, or finished past 95%) is written at once
        if (key.equals(lastKey) && partway == lastPartway && now - lastAt < 60_000) return;
        lastKey = key;
        lastAt = now;
        lastPartway = partway;
        final Context c = ctx.getApplicationContext();
        final String pkg = p.pkg, title = p.title.trim(), by = p.by;
        final Bitmap art = p.art;
        IO.execute(() -> write(c, pkg, title, by, length, position, now, art));
    }

    private static synchronized void write(Context c, String pkg, String title, String by, long length, long position,
                                           long at, Bitmap art) {
        try {
            JSONArray old = list(c), out = new JSONArray();
            String key = pkg + "|" + Continue.key(title);
            String pic = null;
            for (int i = 0; i < old.length(); i++) {
                JSONObject o = old.getJSONObject(i);
                if (key.equals(o.optString("pkg") + "|" + Continue.key(o.optString("title")))) pic = o.optString("art", null);
            }
            if (art != null && pic == null) pic = savePicture(c, key, art);
            JSONObject e = new JSONObject().put("pkg", pkg).put("title", title).put("by", by != null ? by : "")
                    .put("length", length).put("position", position).put("at", at);
            if (pic != null) e.put("art", pic);
            out.put(e);
            Set<String> kept = new HashSet<>();
            kept.add(key);
            for (int i = 0; i < old.length() && out.length() < KEEP; i++) {
                JSONObject o = old.getJSONObject(i);
                String k = o.optString("pkg") + "|" + Continue.key(o.optString("title"));
                if (kept.add(k) && at - o.optLong("at") < KEEP_MS) out.put(o);
            }
            prefs(c).edit().putString(KEY, out.toString()).apply();
            tidy(c, out);
        } catch (JSONException e) {
            Trouble.caught("watched", e);
        }
    }

    /** A small copy of the picture: wide enough for a stack card, kept beside the list. */
    private static String savePicture(Context c, String key, Bitmap art) {
        if (art.isRecycled() || art.getWidth() <= 0 || art.getHeight() <= 0) return null;
        File dir = new File(c.getFilesDir(), DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) return null;
        File f = new File(dir, Integer.toHexString(key.hashCode()) + ".jpg");
        try (FileOutputStream out = new FileOutputStream(f)) {
            int w = Math.min(640, art.getWidth()), h = Math.max(1, Math.round(art.getHeight() * (w / (float) art.getWidth())));
            Bitmap small = w == art.getWidth() ? art : Bitmap.createScaledBitmap(art, w, h, true);
            small.compress(Bitmap.CompressFormat.JPEG, 82, out);
            if (small != art) small.recycle();
            return f.getName();
        } catch (IOException | RuntimeException e) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            return null;
        }
    }

    /** Pictures of titles no longer on the list go. */
    private static void tidy(Context c, JSONArray list) {
        File[] files = new File(c.getFilesDir(), DIR).listFiles();
        if (files == null) return;
        Set<String> keep = new HashSet<>();
        for (int i = 0; i < list.length(); i++) {
            String a = list.optJSONObject(i) != null ? list.optJSONObject(i).optString("art", "") : "";
            if (!a.isEmpty()) keep.add(a);
        }
        //noinspection ResultOfMethodCallIgnored
        for (File f : files) if (!keep.contains(f.getName())) f.delete();
    }

    private static SharedPreferences prefs(Context c) {
        return Prefs.device(c);
    }

    private static JSONArray list(Context c) {
        try {
            return new JSONArray(prefs(c).getString(KEY, "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    /**
     * Up to {@code max} things you're partway through, newest first, as cards: what Watch Next would
     * have said, had the app told it. Leaves out titles in {@code skip} ({@link Continue#key}s), apps
     * no longer installed and anything nearly finished. Off the main thread.
     */
    static List<Continue.Item> recent(Context c, Set<String> skip, int max) {
        return recent(c, skip, max, null);
    }

    /** The same, for one app only ({@code only}), or every app (null). */
    static List<Continue.Item> recent(Context c, Set<String> skip, int max, String only) {
        List<Continue.Item> out = new ArrayList<>();
        JSONArray a = list(c);
        long now = System.currentTimeMillis();
        for (int i = 0; i < a.length() && out.size() < max; i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            String pkg = o.optString("pkg"), title = o.optString("title");
            if (only != null && !only.equals(pkg)) continue;
            long length = o.optLong("length"), position = o.optLong("position");
            if (now - o.optLong("at") > KEEP_MS || length <= 0 || !HomeKeys.allowed(c, pkg)
                    || skip != null && skip.contains(Continue.key(title))) continue;
            float progress = Math.min(1f, position / (float) length);
            // just started, or as good as watched: not something to come back to
            if (progress < 0.02f || progress > 0.95f) continue;
            Intent open = c.getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
            if (open == null) open = c.getPackageManager().getLaunchIntentForPackage(pkg);
            if (open == null) continue;
            Continue.Item it = new Continue.Item();
            it.title = title;
            it.pkg = pkg;
            it.intent = open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            it.progress = progress;
            long left = Math.max(1, (length - position) / 60_000);
            String by = o.optString("by", "");
            it.sub = (by.isEmpty() || by.equalsIgnoreCase(title) ? "" : by + "  ·  ")
                    + (left < 60 ? left + " min left" : String.format(Locale.ROOT, "%d h %d min left", left / 60, left % 60));
            String pic = o.optString("art", "");
            if (!pic.isEmpty()) {
                BitmapFactory.Options opt = new BitmapFactory.Options();
                opt.inPreferredConfig = Bitmap.Config.RGB_565;
                it.art = BitmapFactory.decodeFile(new File(new File(c.getFilesDir(), DIR), pic).getPath(), opt);
            }
            out.add(it);
        }
        return out;
    }

    /** Settings → forget what was watched (with the stack's other learning). */
    static synchronized void forget(Context c) {
        lastKey = null;
        lastAt = 0;
        lastPartway = false;
        prefs(c).edit().remove(KEY).apply();
        tidy(c, new JSONArray());
    }
}
