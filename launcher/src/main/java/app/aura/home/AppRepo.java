package app.aura.home;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Installed apps (TV apps first, phone-style apps as a fallback) and the user's dock order. */
final class AppRepo {
    static final class App {
        String pkg, label;
        Drawable banner, icon;
        int color;
        Intent launch;
        /** Your own picture for its tile, sent from the phone (see {@link Art}); null for its own art. */
        Bitmap art;
        /** Installed since Aura was set up and not opened yet: its tile wears a small dot. */
        boolean fresh;
        /** Its category and activity, and where its package lives (a new path after each update): for the saved copy. */
        String category, cls, src;
    }

    private static final List<String> DEFAULT_DOCK = Arrays.asList(
            "io.gh.reisxd.tizentube.cobalt", "com.netflix.ninja", "com.amazon.amazonvideo.livingroom",
            "in.startv.hotstar", "com.sonyliv", "com.google.android.youtube.tv", "app.netmirror.newtv");

    private AppRepo() { }

    /**
     * Every app with a home-screen entry (its TV entry when it has one), each with its name, its
     * sharpest banner and icon, and its colour. Reading an app's pictures from its own package is
     * the slow part, worst just after the TV starts, when every app's files are cold: it's done on a
     * few threads at once, behind the screen. An app whose package can't be read is left out, rather
     * than leaving the whole grid empty. Off the main thread.
     */
    static List<App> load(Context c) {
        PackageManager pm = c.getPackageManager();
        int maxDensity = bannerDensity(c);
        Map<String, ResolveInfo> found = new LinkedHashMap<>();
        Map<String, String> category = new HashMap<>();
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            List<ResolveInfo> resolved;
            try {
                resolved = pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), 0);
            } catch (RuntimeException e) {
                Trouble.caught("queryIntentActivities", e);
                continue;
            }
            if (resolved == null) continue;
            for (ResolveInfo ri : resolved) {
                if (ri == null || ri.activityInfo == null || ri.activityInfo.applicationInfo == null) continue;
                String pkg = ri.activityInfo.packageName;
                if (pkg == null || pkg.equals(c.getPackageName()) || found.containsKey(pkg)) continue;
                found.put(pkg, ri);
                category.put(pkg, cat);
            }
        }
        int threads = Math.max(1, Math.min(3, Runtime.getRuntime().availableProcessors() - 1));
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads, r -> new SafeThread(() -> {
            // the grid waits on this: at background priority Android gave it a sliver of a TV
            // that's still starting everything else, and the dock stood empty for seconds
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT);
            r.run();
        }, "apps"));
        List<java.util.concurrent.Future<App>> made = new ArrayList<>();
        for (Map.Entry<String, ResolveInfo> e : found.entrySet()) {
            final ResolveInfo ri = e.getValue();
            final String cat = category.get(e.getKey());
            made.add(pool.submit(() -> make(pm, ri, cat, maxDensity)));
        }
        List<App> list = new ArrayList<>();
        try {
            for (java.util.concurrent.Future<App> f : made) {
                try {
                    App a = f.get();
                    if (a != null) list.add(a);
                } catch (java.util.concurrent.ExecutionException e) {
                    // one app's package couldn't be read (being updated, or broken): the rest still come
                    Trouble.caught("reading an app", e.getCause());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            pool.shutdownNow();
        }
        for (App a : list) a.art = Art.load(c, a.pkg);
        NewApps.mark(c, list);
        Collator col = Collator.getInstance();
        java.util.Collections.sort(list, (a, b) -> col.compare(a.label, b.label));
        return list;
    }

    private static int bannerDensity(Context c) {
        // focused tile is 148dp * 1.1 wide; an xhdpi (320dpi) banner is 320px wide, so pick
        // the smallest of 320 / 480 / 640 whose banner width (density px) is at least that wide
        float w = 148f * 1.1f * c.getResources().getDisplayMetrics().density;
        if (w <= DisplayMetrics.DENSITY_XHIGH) return DisplayMetrics.DENSITY_XHIGH;
        if (w <= DisplayMetrics.DENSITY_XXHIGH) return DisplayMetrics.DENSITY_XXHIGH;
        return DisplayMetrics.DENSITY_XXXHIGH;
    }

    private static App make(PackageManager pm, ResolveInfo ri, String category, int maxDensity) {
        ActivityInfo ai = ri.activityInfo;
        App a = new App();
        a.pkg = ai.packageName;
        a.label = String.valueOf(ri.loadLabel(pm)).trim();
        a.banner = loadBannerHighRes(pm, ai, maxDensity);
        a.icon = loadIconHighRes(pm, ri, ai, maxDensity);
        a.color = colorOf(a.banner != null ? a.banner : a.icon);
        a.category = category;
        a.cls = ai.name;
        // never null, so a list read now and the saved one compare alike
        a.src = ai.applicationInfo.sourceDir != null ? ai.applicationInfo.sourceDir : "";
        a.launch = launchFor(a.pkg, a.cls, category);
        return a;
    }

    private static Intent launchFor(String pkg, String cls, String category) {
        return new Intent(Intent.ACTION_MAIN).addCategory(category).setClassName(pkg, cls)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
    }

    // ---- the saved copy, for the moment after the TV starts ----------------------------------

    private static final String SAVED = "apps";
    private static final Object SAVE_LOCK = new Object();

    /**
     * The app list as it was last read, with each banner and icon as a picture of its own: read
     * back in a fraction of the time it takes to open every app's package, which is slowest just
     * after the TV starts, when they're all cold. The grid is shown from it at once and the real
     * list follows. Null when there's none, or it can't be read. Off the main thread.
     */
    static List<App> saved(Context c) {
        synchronized (SAVE_LOCK) {
            File dir = new File(c.getFilesDir(), SAVED);
            try {
                JSONArray a = new JSONArray(read(new File(dir, "index.json")));
                android.content.res.Resources res = c.getResources();
                List<App> out = new ArrayList<>();
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.getJSONObject(i);
                    App x = new App();
                    x.pkg = o.getString("pkg");
                    x.label = o.getString("label");
                    x.cls = o.getString("cls");
                    x.category = o.getString("cat");
                    x.src = o.optString("src", "");
                    x.color = o.getInt("color");
                    x.launch = launchFor(x.pkg, x.cls, x.category);
                    x.banner = picture(res, new File(dir, x.pkg + ".b.png"), o.optInt("bd", 0));
                    x.icon = picture(res, new File(dir, x.pkg + ".i.png"), o.optInt("id", 0));
                    if (x.banner == null && x.icon == null) return null;
                    x.art = Art.load(c, x.pkg);
                    out.add(x);
                }
                if (out.isEmpty()) return null;
                NewApps.flag(c, out);
                return out;
            } catch (IOException | JSONException | RuntimeException | OutOfMemoryError e) {
                return null;
            }
        }
    }

    /** What the saved copy was made from: when this changes, it's made again. */
    private static String signature(List<App> list) {
        StringBuilder s = new StringBuilder();
        for (App a : list) {
            s.append(a.pkg).append('|').append(a.label).append('|').append(a.cls).append('|').append(a.src)
                    .append('|').append(a.banner != null).append('\n');
        }
        return Integer.toHexString(s.toString().hashCode()) + ":" + s.length();
    }

    /** Keeps {@code list} for the next start, unless it's what's kept already. Off the main thread, at low priority. */
    static void save(Context c, List<App> list) {
        if (list == null || list.isEmpty()) return;
        synchronized (SAVE_LOCK) {
            File dir = new File(c.getFilesDir(), SAVED);
            String sig = signature(list);
            File index = new File(dir, "index.json");
            try {
                if (index.isFile() && sig.equals(new JSONObject(read(new File(dir, "sig.json"))).optString("sig"))) return;
            } catch (IOException | JSONException ignored) {
                // made again below
            }
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            try {
                JSONArray a = new JSONArray();
                java.util.Set<String> keep = new java.util.HashSet<>();
                for (App x : list) {
                    if (x.cls == null || x.category == null) continue;
                    JSONObject o = new JSONObject().put("pkg", x.pkg).put("label", x.label).put("cls", x.cls)
                            .put("cat", x.category).put("src", x.src != null ? x.src : "").put("color", x.color);
                    int bd = write(x.banner, new File(dir, x.pkg + ".b.png"), 640, 360);
                    int id = write(x.icon, new File(dir, x.pkg + ".i.png"), 256, 256);
                    if (bd < 0 && id < 0) continue;
                    if (bd >= 0) {
                        o.put("bd", bd);
                        keep.add(x.pkg + ".b.png");
                    }
                    if (id >= 0) {
                        o.put("id", id);
                        keep.add(x.pkg + ".i.png");
                    }
                    a.put(o);
                }
                writeText(index, a.toString());
                writeText(new File(dir, "sig.json"), new JSONObject().put("sig", sig).toString());
                File[] files = dir.listFiles();
                //noinspection ResultOfMethodCallIgnored
                if (files != null) for (File f : files) if (f.getName().endsWith(".png") && !keep.contains(f.getName())) f.delete();
            } catch (IOException | JSONException | RuntimeException | OutOfMemoryError e) {
                // no copy this time: the next start reads the apps as before
                //noinspection ResultOfMethodCallIgnored
                index.delete();
            }
        }
    }

    /**
     * {@code d} as a picture: a bitmap's own pixels as they are (an xxxhdpi icon keeps every one),
     * anything else drawn at up to {@code maxW}×{@code maxH}. Returns the density to read it back
     * at (0 for the screen's), or -1 when there's nothing to keep.
     */
    private static int write(Drawable d, File f, int maxW, int maxH) throws IOException {
        if (d == null) return -1;
        Bitmap b;
        int density = 0;
        boolean own = false;
        if (d instanceof android.graphics.drawable.BitmapDrawable && ((android.graphics.drawable.BitmapDrawable) d).getBitmap() != null) {
            b = ((android.graphics.drawable.BitmapDrawable) d).getBitmap();
            density = b.getDensity() > 0 ? b.getDensity() : 0;
        } else {
            int w = d.getIntrinsicWidth(), h = d.getIntrinsicHeight();
            if (w <= 0 || h <= 0) {
                w = maxW;
                h = maxH;
            }
            // vectors and adaptive icons: drawn twice their size (capped), sharp on the tiles
            float k = Math.min(Math.min(2f, maxW / (float) w), maxH / (float) h);
            w = Math.max(1, Math.round(w * k));
            h = Math.max(1, Math.round(h * k));
            b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Drawable copy = d.getConstantState() != null ? d.getConstantState().newDrawable().mutate() : d;
            copy.setBounds(0, 0, w, h);
            copy.draw(new Canvas(b));
            density = Math.round(DisplayMetrics.DENSITY_DEFAULT * k * Resources.getSystem().getDisplayMetrics().density);
            own = true;
        }
        if (b.isRecycled()) return -1;
        File part = new File(f.getPath() + ".part");
        try (FileOutputStream out = new FileOutputStream(part)) {
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
        } finally {
            if (own) b.recycle();
        }
        if (!part.renameTo(f)) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            return -1;
        }
        return density;
    }

    private static Drawable picture(android.content.res.Resources res, File f, int density) {
        if (!f.isFile()) return null;
        Bitmap b = BitmapFactory.decodeFile(f.getPath());
        if (b == null) return null;
        if (density > 0) b.setDensity(density);
        return new android.graphics.drawable.BitmapDrawable(res, b);
    }

    private static String read(File f) throws IOException {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    private static void writeText(File f, String s) throws IOException {
        File part = new File(f.getPath() + ".part");
        try (FileOutputStream out = new FileOutputStream(part)) {
            out.write(s.getBytes("UTF-8"));
        }
        if (!part.renameTo(f)) throw new IOException("couldn't keep " + f.getName());
    }

    /** Loads the highest-density banner the screen needs, without enlarging lower-density assets. */
    private static Drawable loadBannerHighRes(PackageManager pm, ActivityInfo ai, int maxDensity) {
        int resId = ai.banner != 0 ? ai.banner : ai.applicationInfo.banner;
        if (resId != 0) {
            Drawable d = forDensity(pm, ai.applicationInfo, resId, maxDensity);
            if (d != null) return d;
        }
        Drawable b = ai.loadBanner(pm);
        return b != null ? b : ai.applicationInfo.loadBanner(pm);
    }

    /** Loads the highest-density icon the screen needs from the package's resources. */
    private static Drawable loadIconHighRes(PackageManager pm, ResolveInfo ri, ActivityInfo ai, int maxDensity) {
        int resId = ri.getIconResource();
        if (resId == 0) resId = ai.getIconResource();
        if (resId != 0) {
            Drawable d = forDensity(pm, ai.applicationInfo, resId, maxDensity);
            if (d != null) return d;
        }
        return ri.loadIcon(pm);
    }

    private static Drawable forDensity(PackageManager pm, android.content.pm.ApplicationInfo app, int resId, int maxDensity) {
        try {
            android.content.res.Resources r = pm.getResourcesForApplication(app);
            int[] densities = {
                    DisplayMetrics.DENSITY_XXXHIGH,
                    DisplayMetrics.DENSITY_XXHIGH,
                    DisplayMetrics.DENSITY_XHIGH
            };
            TypedValue v = new TypedValue();
            for (int density : densities) {
                if (density > maxDensity) continue;
                try {
                    // ResourcesImpl scales a lower-density bitmap up to the requested density in RAM;
                    // if the package only has a lower density, ask for its own density to keep it 1:1.
                    r.getValueForDensity(resId, density, v, true);
                    int d = v.density > 0 && v.density != TypedValue.DENSITY_NONE && v.density < density
                            ? v.density : density;
                    Drawable dr = r.getDrawableForDensity(resId, d, null);
                    if (dr != null) return dr;
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** A vivid representative colour for the focus glow (saturation-weighted mean hue). */
    static int colorOf(Drawable d) {
        if (d == null) return 0xFF8AA0C0;
        Bitmap b = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888);
        Drawable copy = d.getConstantState() != null ? d.getConstantState().newDrawable().mutate() : d;
        copy.setBounds(0, 0, 32, 18);
        copy.draw(new Canvas(b));
        int[] px = new int[32 * 18];
        b.getPixels(px, 0, 32, 0, 0, 32, 18);
        b.recycle();
        float[] hsv = new float[3];
        double sx = 0, sy = 0, wsum = 0;
        for (int c : px) {
            if (Color.alpha(c) < 128) continue;
            Color.colorToHSV(c, hsv);
            double w = hsv[1] * hsv[2] * hsv[2];
            sx += Math.cos(Math.toRadians(hsv[0])) * w;
            sy += Math.sin(Math.toRadians(hsv[0])) * w;
            wsum += w;
        }
        if (wsum < 8) return 0xFFB8C4D6;
        float hue = (float) ((Math.toDegrees(Math.atan2(sy, sx)) + 360) % 360);
        return Color.HSVToColor(new float[]{hue, 0.7f, 1f});
    }

    /** The saved grid order as tags (apps, and folders as "folder:id"), or the default one. */
    static List<String> order(SharedPreferences p) {
        String saved = p.getString("dock", null);
        return saved == null ? new ArrayList<>(DEFAULT_DOCK) : new ArrayList<>(Arrays.asList(TextUtils.split(saved, ",")));
    }

    static List<String> dock(SharedPreferences p, List<App> installed) {
        String saved = p.getString("dock", null);
        List<String> order = saved == null ? new ArrayList<>(DEFAULT_DOCK)
                : new ArrayList<>(Arrays.asList(TextUtils.split(saved, ",")));
        List<String> present = new ArrayList<>();
        for (String pkg : order) {
            for (App a : installed) {
                if (a.pkg.equals(pkg)) {
                    present.add(pkg);
                    break;
                }
            }
        }
        return present;
    }

    static void saveDock(SharedPreferences p, List<String> pkgs) {
        p.edit().putString("dock", TextUtils.join(",", pkgs)).apply();
    }
}
