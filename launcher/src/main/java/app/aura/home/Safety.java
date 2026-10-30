package app.aura.home;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.Settings;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * A home screen that keeps crashing leaves a TV with nothing to come back to. Three crashes within
 * five minutes and Aura starts again with its riskier extras off (parallax, Top Shelf, switcher
 * pictures, the Home button in apps, the screensaver's scenes). Three more within half an hour of
 * that, on a TV Aura is set up on, and the Home button goes back to the TV's own home screen.
 */
final class Safety {
    private static final long WINDOW_MS = 5 * 60_000L, AFTER_MS = 30 * 60_000L;
    private static final int CRASHES = 3;
    private static boolean watching;

    private Safety() { }

    /** Notes each crash (synchronously: the process is on its way out) before Android's own handling. */
    static void watch(Context ctx) {
        if (watching) return;
        watching = true;
        final Context c = ctx.getApplicationContext();
        final Thread.UncaughtExceptionHandler before = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                SharedPreferences p = Prefs.device(c);
                List<String> times = recent(p.getString("crashes", ""), System.currentTimeMillis() - AFTER_MS);
                times.add(String.valueOf(System.currentTimeMillis()));
                p.edit().putString("crashes", TextUtils.join(",", times)).commit();
            } catch (Throwable ignored) {
            }
            if (before != null) before.uncaughtException(t, e);
        });
    }

    private static List<String> recent(String list, long since) {
        List<String> out = new ArrayList<>();
        for (String s : TextUtils.split(list, ",")) {
            try {
                if (Long.parseLong(s) >= since) out.add(s);
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    /**
     * Called first thing as Aura starts. Returns what it did, for a message once the home screen is
     * up: "safe" (extras off), "home" (Home handed back), or null.
     */
    static String check(Context c) {
        SharedPreferences p = Prefs.device(c);
        long now = System.currentTimeMillis();
        List<String> lately = recent(p.getString("crashes", ""), now - WINDOW_MS);
        if (lately.size() < CRASHES) return null;
        p.edit().putString("crashes", "").apply();
        boolean again = now - p.getLong("safe_at", 0) < AFTER_MS;
        if (!again) {
            p.edit().putLong("safe_at", now).apply();
            Prefs.set(c, Prefs.PARALLAX, false);
            Prefs.set(c, Prefs.TOP_SHELF, false);
            Prefs.of(c).edit().putString(Prefs.SAVER, "photos").apply();
            Previews.set(c, false);
            HomeKeys.set(c, false);
            return "safe";
        }
        final String home = original(c);
        if (home == null || !Adb.on(c)) return null;
        final Context app = c.getApplicationContext();
        new Thread(() -> Adb.run(app, "cmd package set-home-activity " + home), "give-home").start();
        return "home";
    }

    /**
     * The TV's own home screen: a system app answering Home, other than Aura and any fallback.
     * None (the factory launcher was switched off): Home stays with Aura, safe mode is all there is.
     */
    private static String original(Context c) {
        PackageManager pm = c.getPackageManager();
        ResolveInfo settings = pm.resolveActivity(new Intent(Settings.ACTION_SETTINGS), PackageManager.MATCH_DEFAULT_ONLY);
        String skip = settings != null && settings.activityInfo != null ? settings.activityInfo.packageName : "";
        for (ResolveInfo r : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)) {
            String pkg = r.activityInfo.packageName;
            if (pkg.equals(c.getPackageName()) || pkg.equals(skip)) continue;
            if ((r.activityInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0) continue;
            // a fallback (Settings' or a chip maker's, as Realtek's) is a blank screen, not a home
            if (r.activityInfo.name.toLowerCase(java.util.Locale.ROOT).contains("fallback")) continue;
            String name = pkg + "/" + r.activityInfo.name;
            if (name.matches("[A-Za-z0-9_.$/]+")) return name;
        }
        return null;
    }
}
