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
    }

    private static final List<String> DEFAULT_DOCK = Arrays.asList(
            "io.gh.reisxd.tizentube.cobalt", "com.netflix.ninja", "com.amazon.amazonvideo.livingroom",
            "in.startv.hotstar", "com.sonyliv", "com.google.android.youtube.tv", "app.netmirror.newtv");

    private AppRepo() { }

    static List<App> load(Context c) {
        PackageManager pm = c.getPackageManager();
        Map<String, App> apps = new LinkedHashMap<>();
        collect(pm, apps, Intent.CATEGORY_LEANBACK_LAUNCHER, c.getPackageName());
        collect(pm, apps, Intent.CATEGORY_LAUNCHER, c.getPackageName());
        List<App> list = new ArrayList<>(apps.values());
        Collator col = Collator.getInstance();
        java.util.Collections.sort(list, (a, b) -> col.compare(a.label, b.label));
        return list;
    }

    private static void collect(PackageManager pm, Map<String, App> out, String category, String self) {
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(category);
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            ActivityInfo ai = ri.activityInfo;
            if (ai.packageName.equals(self) || out.containsKey(ai.packageName)) continue;
            App a = new App();
            a.pkg = ai.packageName;
            a.label = String.valueOf(ri.loadLabel(pm)).trim();
            a.banner = ai.loadBanner(pm);
            if (a.banner == null) a.banner = ai.applicationInfo.loadBanner(pm);
            a.icon = ri.loadIcon(pm);
            a.color = colorOf(a.banner != null ? a.banner : a.icon);
            a.launch = new Intent(Intent.ACTION_MAIN).addCategory(category)
                    .setClassName(ai.packageName, ai.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            out.put(a.pkg, a);
        }
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
