package app.aura.home;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
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
    }

    private static final List<String> DEFAULT_DOCK = Arrays.asList(
            "io.gh.reisxd.tizentube.cobalt", "com.netflix.ninja", "com.amazon.amazonvideo.livingroom",
            "in.startv.hotstar", "com.sonyliv", "com.google.android.youtube.tv", "app.netmirror.newtv");

    private AppRepo() { }

    static List<App> load(Context c) {
        PackageManager pm = c.getPackageManager();
        int maxDensity = bannerDensity(c);
        Map<String, App> apps = new LinkedHashMap<>();
        collect(pm, apps, Intent.CATEGORY_LEANBACK_LAUNCHER, c.getPackageName(), maxDensity);
        collect(pm, apps, Intent.CATEGORY_LAUNCHER, c.getPackageName(), maxDensity);
        List<App> list = new ArrayList<>(apps.values());
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

    private static void collect(PackageManager pm, Map<String, App> out, String category, String self, int maxDensity) {
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(category);
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            ActivityInfo ai = ri.activityInfo;
            if (ai.packageName.equals(self) || out.containsKey(ai.packageName)) continue;
            App a = new App();
            a.pkg = ai.packageName;
            a.label = String.valueOf(ri.loadLabel(pm)).trim();
            a.banner = loadBannerHighRes(pm, ai, maxDensity);
            a.icon = loadIconHighRes(pm, ri, ai);
            a.color = colorOf(a.banner != null ? a.banner : a.icon);
            a.launch = new Intent(Intent.ACTION_MAIN).addCategory(category)
                    .setClassName(ai.packageName, ai.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            out.put(a.pkg, a);
        }
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

    /** Loads the highest-density icon (640dpi xxxhdpi) available in the package's resources. */
    private static Drawable loadIconHighRes(PackageManager pm, ResolveInfo ri, ActivityInfo ai) {
        int resId = ri.getIconResource();
        if (resId == 0) resId = ai.getIconResource();
        if (resId != 0) {
            Drawable d = forDensity(pm, ai.applicationInfo, resId, DisplayMetrics.DENSITY_XXXHIGH);
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
        float[] hsv = new float[3];
        double sx = 0, sy = 0, wsum = 0;
        for (int y = 0; y < 18; y++) {
            for (int x = 0; x < 32; x++) {
                int c = b.getPixel(x, y);
                if (Color.alpha(c) < 128) continue;
                Color.colorToHSV(c, hsv);
                double w = hsv[1] * hsv[2] * hsv[2];
                sx += Math.cos(Math.toRadians(hsv[0])) * w;
                sy += Math.sin(Math.toRadians(hsv[0])) * w;
                wsum += w;
            }
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
