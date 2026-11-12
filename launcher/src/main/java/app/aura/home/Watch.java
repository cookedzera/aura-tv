package app.aura.home;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Something to watch, for the evening nobody can decide: a shelf of films and series for each genre
 * ({@link Picks#browse}), and your own list of the ones you saved. A genre's shelf is fetched the
 * first time someone opens it, with small posters, and kept on the TV for a week, so the screen
 * only ever reads from disk and nothing is asked of the network until the Watch tab is opened.
 * My list is each profile's own and never leaves the TV; its posters are copies, so a shelf made
 * again doesn't take them away.
 */
final class Watch {
    /** The shelf of every genre together, and your own list: the two that aren't one of {@link Picks#GENRES}. */
    static final String ANY = "any", MINE = "mine";
    private static final String DIR = "watch", KEPT = "mine";
    private static final long WEEK = 7L * 24 * 3_600_000;
    /** Six rows of six. */
    static final int MOST = 36;
    /** The files: a shelf written, the posters swept, a list read or changed. Never held across the network: the screen reads My list under it. */
    private static final Object LOCK = new Object();
    /** One fetch at a time. */
    private static final Object FETCHING = new Object();

    private Watch() { }

    /** Where the shelves, their posters and wide pictures, and each profile's list are kept. */
    static File dir(Context c) {
        return new File(c.getFilesDir(), DIR);
    }

    /**
     * Watch was switched off: the shelves fetched and their pictures leave the TV (they're fetched
     * again if it's switched back on). What each profile saved to its list stays, with its posters.
     * Off the main thread.
     */
    static void clear(Context c) {
        synchronized (LOCK) {
            File[] all = dir(c).listFiles();
            if (all == null) return;
            for (File f : all) {
                String n = f.getName();
                boolean fetched = n.endsWith(".jpg") || n.endsWith(".part") || (n.startsWith("g-") || n.startsWith("t-")) && n.endsWith(".json");
                if (fetched && f.isFile()) //noinspection ResultOfMethodCallIgnored
                    f.delete();
            }
        }
        WHERE.clear();
    }

    /**
     * The Watch tile's Top Shelf: the first {@code n} titles of the shelf of every genre as cards,
     * read from what the screen last fetched and never asked of the network. Empty until Watch has
     * been opened once. Off the main thread.
     */
    static List<Continue.Item> cards(Context c, int n) {
        List<Continue.Item> out = new ArrayList<>();
        if (!Prefs.watch(c)) return out;
        String kicker = "▶  " + Words.get(R.string.watch).toUpperCase(java.util.Locale.getDefault());
        for (Picks.Pick p : shelf(c, ANY)) {
            if (out.size() >= n) break;
            Bitmap b = Picks.poster(p);
            if (b != null) out.add(TopShelf.posterCard(p, b, kicker));
        }
        return out;
    }

    private static File shelfFile(Context c, String genre) {
        // a shelf made from TVmaze (no key) is not the one TMDB gives: each is kept under its own name, so a key arriving shows its own at once
        return new File(dir(c), (Picks.anyKey(c).isEmpty() ? "g-" : "t-") + genre + ".json");
    }

    private static File mineFile(Context c) {
        return new File(dir(c), "mine-" + Profiles.current(c) + ".json");
    }

    // ---- a genre's shelf -----------------------------------------------------------------------

    /** The shelf as it was last fetched; empty when it never was. Reads a small file: off the main thread. */
    static List<Picks.Pick> shelf(Context c, String genre) {
        return read(shelfFile(c, genre));
    }

    /** Never fetched, or a week old. */
    static boolean stale(Context c, String genre) {
        File f = shelfFile(c, genre);
        return !f.isFile() || System.currentTimeMillis() - f.lastModified() > WEEK;
    }

    /**
     * Fetches {@code genre}'s shelf and its posters and keeps them. False when nothing could be
     * fetched: what was kept before stays. Off the main thread.
     */
    static boolean fetch(Context c, String genre) {
        synchronized (FETCHING) {
            File dir = dir(c);
            if (!dir.isDirectory() && !dir.mkdirs()) return false;
            List<JSONObject> found;
            try {
                found = Picks.browse(c, ANY.equals(genre) ? "" : genre);
            } catch (IOException | JSONException e) {
                return false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            if (found.size() > MOST) found = found.subList(0, MOST);
            // the posters a few at a time: thirty-six one after another kept the shelf waiting
            java.util.concurrent.ExecutorService pool = Compat.pool("watch-posters", 4);
            List<java.util.concurrent.Future<Boolean>> got = new ArrayList<>();
            for (final JSONObject s : found) {
                got.add(pool.submit(() -> {
                    File img = new File(dir, "w" + s.optString("id") + ".jpg");
                    if (!img.isFile()) {
                        if (!keep(img, Picks.fetch(s.getString("art"))) && !img.isFile()) return false;
                    }
                    s.put("poster", img.getAbsolutePath());
                    s.remove("art");
                    return true;
                }));
            }
            pool.shutdown();
            JSONArray out = new JSONArray();
            for (int i = 0; i < found.size(); i++) {
                try {
                    if (got.get(i).get()) out.put(found.get(i));
                } catch (java.util.concurrent.ExecutionException e) {
                    // one poster didn't come: the rest of the shelf does
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            if (out.length() == 0) return false;
            synchronized (LOCK) {
                // switched off while this was on its way: nothing of it is kept
                if (!Prefs.on(c, Prefs.WATCH)) return false;
                if (!write(shelfFile(c, genre), out)) return false;
                sweep(dir);
            }
            return true;
        }
    }

    /** Posters no shelf shows any more go. */
    private static void sweep(File dir) {
        StringBuilder all = new StringBuilder();
        File[] shelves = dir.listFiles((d, n) -> (n.startsWith("g-") || n.startsWith("t-")) && n.endsWith(".json"));
        if (shelves == null) return;
        for (File f : shelves) {
            try {
                all.append(text(f));
            } catch (IOException e) {
                return; // a shelf that can't be read may still be showing its posters
            }
        }
        // a path in JSON has its slashes escaped: the file's name alone is looked for
        File[] posters = dir.listFiles((d, n) -> n.endsWith(".jpg"));
        // (a title's wide picture is named after its poster, with an h before it)
        if (posters != null) for (File p : posters) if (all.indexOf(p.getName().startsWith("h") ? p.getName().substring(1) : p.getName()) < 0) //noinspection ResultOfMethodCallIgnored
            p.delete();
        // parts left by a home that was stopped mid-write; one being written now is minutes younger
        long old = System.currentTimeMillis() - 10 * 60_000L;
        File[] parts = dir.listFiles((d, n) -> n.endsWith(".part"));
        if (parts != null) for (File p : parts) if (p.lastModified() < old) //noinspection ResultOfMethodCallIgnored
            p.delete();
    }

    // ---- the wide picture behind the title in focus ---------------------------------------------

    /**
     * {@code p}'s wide picture for the Watch screen's hero, fetched the first time that title is in
     * focus and kept beside the posters (a shelf made again sweeps those no title names). Null when
     * it has none or it couldn't be fetched: the hero shows the poster instead. Off the main thread.
     */
    static File hero(Context c, Picks.Pick p) {
        if (p.back.isEmpty()) return null;
        File f = new File(dir(c), "h" + id(p));
        if (f.isFile()) return f;
        try {
            // a 720p screen (most low-end TVs draw at that) is given the 780-pixel picture: half the bytes to fetch and to read
            boolean small = c.getResources().getDisplayMetrics().widthPixels <= 1280;
            byte[] data = Picks.fetch(small ? p.back.replace("/w1280/", "/w780/") : p.back);
            // kept by another screen meanwhile counts too: a screen let go and a new one may fetch the same title at once
            return keep(f, data) || f.isFile() ? f : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** {@code p}'s wide picture when it's already on the TV, null when it was never fetched: nothing is asked of the network. */
    static File heroKept(Context c, Picks.Pick p) {
        File f = new File(dir(c), "h" + id(p));
        return f.isFile() ? f : null;
    }

    // ---- what the screen says of a title ---------------------------------------------------------

    /** A title's line ("Film · ★ 7.8 · Drama · Crime · 2019") taken apart: its score for the dial, its year, and the rest for the line over its name. */
    static final class Facts {
        /** Out of ten; 0 when the line names none. */
        final float score;
        final String year, kinds;

        Facts(String line) {
            float s = 0f;
            String y = "";
            StringBuilder k = new StringBuilder();
            for (String part : line.split("·")) {
                part = part.trim();
                if (part.startsWith("★")) {
                    try {
                        s = Float.parseFloat(part.substring(1).trim());
                    } catch (NumberFormatException ignored) {
                    }
                } else if (part.matches("(19|20)\\d\\d")) {
                    y = part;
                } else if (!part.isEmpty()) {
                    if (k.length() > 0) k.append("  ·  ");
                    k.append(part);
                }
            }
            score = Math.max(0f, Math.min(10f, s));
            year = y;
            kinds = k.toString();
        }
    }

    /** The colour of a title whose poster has none to speak of (black and white, or all but): a pale, cool white. */
    static final int PALE = 0xFFCDD6EA;

    /**
     * A title's own colour, taken from its poster: the hue that carries the most vivid paint on it,
     * lifted to a brightness that reads on a dark screen. The Watch screen lights itself with it.
     * Off the main thread.
     */
    static int aura(Bitmap poster) {
        if (poster == null || poster.getWidth() == 0) return PALE;
        final int w = 24, h = 34, bins = 12;
        Bitmap small = Bitmap.createScaledBitmap(poster, w, h, true);
        int[] px = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        if (small != poster) small.recycle();
        float[] weight = new float[bins], hue = new float[bins], sat = new float[bins], hsv = new float[3];
        for (int p : px) {
            Color.colorToHSV(p, hsv);
            // the dark and the grey of a poster say nothing of its colour
            if (hsv[2] < 0.22f || hsv[1] < 0.28f) continue;
            float k = hsv[1] * hsv[1] * hsv[2];
            int bin = Math.min(bins - 1, (int) (hsv[0] / (360f / bins)));
            weight[bin] += k;
            hue[bin] += hsv[0] * k;
            sat[bin] += hsv[1] * k;
        }
        int top = 0;
        for (int i = 1; i < bins; i++) if (weight[i] > weight[top]) top = i;
        if (weight[top] < 6f) return PALE;
        return hsl(hue[top] / weight[top], Math.max(0.58f, Math.min(0.9f, sat[top] / weight[top])), 0.64f);
    }

    private static int hsl(float h, float s, float l) {
        float c = (1f - Math.abs(2f * l - 1f)) * s, x = c * (1f - Math.abs(h / 60f % 2f - 1f)), m = l - c / 2f;
        int sixth = (int) (h / 60f) % 6;
        float r = sixth == 0 || sixth == 5 ? c : sixth == 1 || sixth == 4 ? x : 0f;
        float g = sixth == 1 || sixth == 2 ? c : sixth == 0 || sixth == 3 ? x : 0f;
        float b = sixth == 3 || sixth == 4 ? c : sixth == 2 || sixth == 5 ? x : 0f;
        return Color.rgb(Math.round((r + m) * 255f), Math.round((g + m) * 255f), Math.round((b + m) * 255f));
    }

    /** An app on this TV that streams a title: its package and its name. */
    static final class App {
        final String pkg, label;

        App(String pkg, String label) {
            this.pkg = pkg;
            this.label = label;
        }
    }

    /** {@link #where}'s answer for a title no app here streams. */
    static final App NONE = new App("", "");
    /** Where each title asked about streams, kept while home runs: a title is asked about once. */
    private static final Map<String, App> WHERE = new ConcurrentHashMap<>();

    /** The app here that streams {@code p} ({@link Picks#where}), {@link #NONE} when none does, or null when the network didn't answer. Off the main thread. */
    static App where(Context c, Picks.Pick p) {
        App a = WHERE.get(id(p));
        if (a == null) {
            String pkg = Picks.where(c, p);
            // the network didn't answer: asked again the next time this title is in focus
            if (pkg == null) return null;
            a = NONE;
            if (!pkg.isEmpty()) {
                PackageManager pm = c.getPackageManager();
                try {
                    a = new App(pkg, String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0))));
                } catch (PackageManager.NameNotFoundException ignored) {
                }
            }
            WHERE.put(id(p), a);
        }
        return a;
    }

    // ---- my list -------------------------------------------------------------------------------

    /** What this profile saved, newest first. */
    static List<Picks.Pick> mine(Context c) {
        synchronized (LOCK) {
            return read(mineFile(c));
        }
    }

    /** A title is the same title whichever shelf it was on: by its poster's file, named after its id. */
    private static String id(Picks.Pick p) {
        return new File(p.poster).getName();
    }

    static boolean saved(Context c, Picks.Pick p) {
        for (Picks.Pick m : mine(c)) if (id(m).equals(id(p))) return true;
        return false;
    }

    /** Saves {@code p} to this profile's list with a copy of its poster, or takes it off again. True when it's on the list now. */
    static boolean toggle(Context c, Picks.Pick p) {
        synchronized (LOCK) {
            List<Picks.Pick> list = read(mineFile(c));
            JSONArray out = new JSONArray();
            boolean had = false;
            try {
                for (Picks.Pick m : list) {
                    if (id(m).equals(id(p))) had = true;
                    else out.put(json(m, m.poster));
                }
                if (!had) {
                    File kept = new File(dir(c), KEPT);
                    if (!kept.isDirectory() && !kept.mkdirs()) return false;
                    File copy = new File(kept, id(p));
                    if (!copy.isFile()) copy(new File(p.poster), copy);
                    JSONArray first = new JSONArray().put(json(p, copy.getAbsolutePath()));
                    for (int i = 0; i < out.length(); i++) first.put(out.get(i));
                    out = first;
                }
            } catch (IOException | JSONException e) {
                return had;
            }
            if (!write(mineFile(c), out)) return had;
            if (had) dropKept(c, id(p));
            return !had;
        }
    }

    /** A poster copy no profile's list holds any more goes. */
    private static void dropKept(Context c, String name) {
        File[] lists = dir(c).listFiles((d, n) -> n.startsWith("mine-") && n.endsWith(".json"));
        if (lists == null) return;
        for (File f : lists) {
            try {
                if (text(f).contains(name)) return;
            } catch (IOException e) {
                return;
            }
        }
        //noinspection ResultOfMethodCallIgnored
        new File(new File(dir(c), KEPT), name).delete();
    }

    // ---- files ---------------------------------------------------------------------------------

    private static JSONObject json(Picks.Pick p, String poster) throws JSONException {
        return new JSONObject().put("name", p.name).put("line", p.line).put("about", p.about).put("poster", poster)
                .put("query", p.query).put("why", p.why).put("yt", p.yt).put("src", p.src).put("back", p.back)
                .put("tmdb", p.tmdb).put("type", p.type);
    }

    private static List<Picks.Pick> read(File f) {
        List<Picks.Pick> out = new ArrayList<>();
        if (!f.isFile()) return out;
        try {
            JSONArray a = new JSONArray(text(f));
            for (int i = 0; i < a.length(); i++) {
                Picks.Pick p = new Picks.Pick(a.getJSONObject(i));
                if (new File(p.poster).isFile()) out.add(p);
            }
        } catch (IOException | JSONException ignored) {
        }
        return out;
    }

    private static String text(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) bo.write(buf, 0, n);
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static boolean write(File f, JSONArray a) {
        return keep(f, a.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * {@code data} becomes {@code f} whole or not at all: written to a part of its own first (two
     * threads keeping the same file never write into one part), then put in its place. False when
     * it couldn't be; no part is left behind.
     */
    private static boolean keep(File f, byte[] data) {
        File part;
        try {
            part = File.createTempFile(f.getName() + ".", ".part", f.getParentFile());
        } catch (IOException e) {
            return false;
        }
        boolean written = false;
        try (FileOutputStream fo = new FileOutputStream(part)) {
            fo.write(data);
            written = true;
        } catch (IOException ignored) {
        }
        if (written && part.renameTo(f)) return true;
        //noinspection ResultOfMethodCallIgnored
        part.delete();
        return false;
    }

    private static void copy(File from, File to) throws IOException {
        File part = new File(to.getPath() + ".part");
        try (FileInputStream in = new FileInputStream(from); FileOutputStream fo = new FileOutputStream(part)) {
            byte[] buf = new byte[16 * 1024];
            for (int n; (n = in.read(buf)) > 0; ) fo.write(buf, 0, n);
        }
        if (!part.renameTo(to)) throw new IOException("couldn't keep " + to.getName());
    }
}
