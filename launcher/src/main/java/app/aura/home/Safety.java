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
            // what went wrong and where, kept for Settings → Problem reports
            Trouble.crash(t, e);
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
        boolean again = now - p.getLong("safe_at", 0) < AFTER_MS;
        List<String> lately = recent(p.getString("crashes", ""), now - (again ? AFTER_MS : WINDOW_MS));
        if (lately.size() < CRASHES) return null;
        p.edit().putString("crashes", "").apply();
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
        new SafeThread(() -> restoreHome(app), "give-home").start();
        return "home";
    }

    /**
     * Re-enables any retired system launcher (and Google TV's setup recovery home) and hands the
     * Home button back to the TV's own launcher over one ADB connection. Off the main thread.
     */
    static void restoreHome(Context c) {
        String home = original(c);
        List<String> cmds = new ArrayList<>();
        PackageManager pm = c.getPackageManager();
        String origPkg = home != null ? home.substring(0, home.indexOf('/')) : null;
        for (String pkg : new String[]{origPkg, "com.google.android.apps.tv.launcherx",
                "com.google.android.tungsten.setupwraith", "com.amazon.tv.launcher"}) {
            if (pkg == null || !pkg.matches("[A-Za-z0-9_.]+")) continue;
            try {
                if (!pm.getApplicationInfo(pkg, 0).enabled) {
                    String cmd = "pm enable " + pkg;
                    if (!cmds.contains(cmd)) cmds.add(cmd);
                }
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        // every app Free up memory switched off, back on
        for (String pkg : Prefs.device(c).getStringSet(Debloat.MINE, new java.util.HashSet<>())) {
            if (pkg.matches("[A-Za-z0-9_.]+")) cmds.add("pm enable " + pkg);
        }
        // a home taken off for this user can't be seen any more: it's put back by name
        String retired = Prefs.device(c).getString(RETIRED, null);
        if (retired != null && retired.matches("[A-Za-z0-9_.]+")) {
            cmds.add(0, "pm install-existing " + retired);
            cmds.add(1, "pm enable " + retired);
        }
        if (home != null) cmds.add("cmd package set-home-activity " + home);
        if (!cmds.isEmpty()) Adb.run(c, cmds.toArray(new String[0]));
    }

    /**
     * Once, after updating from 7.5 or earlier: the screensaver's flyover videos and museum
     * paintings (scenes 7.6 no longer has) are deleted, so they don't keep filling the TV's storage.
     * Off the main thread.
     */
    static void tidy(Context ctx) {
        final Context c = ctx.getApplicationContext();
        if (Prefs.device(c).getBoolean("tidied_76", false)) return;
        new SafeThread(() -> {
            for (String dir : new String[]{"flyovers", "museum"}) wipe(new java.io.File(c.getFilesDir(), dir));
            Prefs.device(c).edit().putBoolean("tidied_76", true).apply();
        }, "tidy").start();
    }

    private static void wipe(java.io.File f) {
        java.io.File[] kids = f.listFiles();
        if (kids != null) for (java.io.File k : kids) wipe(k);
        if (f.exists() && !f.delete()) f.deleteOnExit();
    }

    static final String GOOGLE_TV_HOME ="com.google.android.apps.tv.launcherx", GOOGLE_TV_SETUP = "com.google.android.tungsten.setupwraith";

    /** Google TV's own home screen is on this TV, switched on or off. */
    static boolean hasGoogleTvHome(Context c) {
        try {
            c.getPackageManager().getApplicationInfo(GOOGLE_TV_HOME, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    static boolean googleTvHomeOn(Context c) {
        try {
            return c.getPackageManager().getApplicationInfo(GOOGLE_TV_HOME, 0).enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * Switches Google TV's own home screen (and its setup app's recovery Home, which would take the
     * Home button otherwise) off, or back on, over ADB; Home goes to Aura either way. Off the main
     * thread. Null when debugging couldn't be reached, else whether Android took the change. Nothing
     * is uninstalled: {@link #restoreHome} switches it back on should Aura be removed or keep crashing.
     */
    static Boolean retireGoogleHome(Context c, boolean retire) {
        String me = c.getPackageName() + "/" + HomeActivity.class.getName();
        // the setup app also pairs remotes, adds accounts and resets the TV: only its recovery home
        // screens go, where Android allows it; on a TV that refuses that, the whole app as before
        List<String> setupOff = new ArrayList<>(), setupOn = new ArrayList<>();
        for (ResolveInfo r : c.getPackageManager().queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.GET_DISABLED_COMPONENTS)) {
            if (r.activityInfo == null || !GOOGLE_TV_SETUP.equals(r.activityInfo.packageName)) continue;
            String comp = GOOGLE_TV_SETUP + "/" + r.activityInfo.name;
            if (!comp.matches("[A-Za-z0-9_.$/]+")) continue;
            setupOff.add("pm disable --user 0 " + comp);
            setupOn.add("pm enable --user 0 " + comp);
        }
        if (setupOff.isEmpty()) setupOff.add("pm disable-user --user 0 " + GOOGLE_TV_SETUP);
        setupOn.add("pm enable " + GOOGLE_TV_SETUP);
        List<String> cmds = new ArrayList<>();
        if (retire) {
            cmds.add("am force-stop " + GOOGLE_TV_HOME);
            cmds.add("pm disable-user --user 0 " + GOOGLE_TV_HOME);
            cmds.addAll(setupOff);
        } else {
            cmds.add("pm enable " + GOOGLE_TV_HOME);
            cmds.addAll(setupOn);
        }
        cmds.add("cmd package set-home-activity " + me);
        List<String> out = Adb.run(c, cmds.toArray(new String[0]));
        if (out == null || out.isEmpty()) return null;
        boolean ok = true;
        // the setup app is missing on some boxes: only the home screen itself has to take it
        for (String s : out) if ((s.contains("Failure") || s.contains("Exception")) && !s.contains(GOOGLE_TV_SETUP)) ok = false;
        // only once the TV took it: a failed call changed nothing, so nothing to take over from
        if (ok) HomeKeys.setTakeover(c, true);
        return ok;
    }

    /** The TV's own home that Aura switched off, remembered: taken off for this user, it can't be found any more. */
    private static final String RETIRED = "retired_home";

    /**
     * The package of the TV's own home screen that can be switched off, or null. Google TV's, else
     * whatever system home the TV came with (Android TV's, a maker's). Never Fire TV's: Amazon's
     * home is also where its settings live.
     */
    static String tvHome(Context c) {
        if (Trouble.fireTv(c)) return null;
        if (hasGoogleTvHome(c)) return GOOGLE_TV_HOME;
        String retired = Prefs.device(c).getString(RETIRED, null);
        if (retired != null) return retired;
        String home = original(c);
        return home == null ? null : home.substring(0, home.indexOf('/'));
    }

    /** That home is there and switched on. */
    static boolean tvHomeOn(Context c) {
        String pkg = tvHome(c);
        if (pkg == null) return false;
        try {
            return c.getPackageManager().getApplicationInfo(pkg, 0).enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Its name, as the TV calls it ("Android TV Home"). */
    static String tvHomeName(Context c) {
        String pkg = tvHome(c);
        if (pkg == null) return "";
        PackageManager pm = c.getPackageManager();
        try {
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, PackageManager.GET_UNINSTALLED_PACKAGES)));
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    /**
     * Switches the TV's own home screen off, or back on, over ADB: Home then only ever opens Aura,
     * and the memory it held comes back. Disabled where the TV allows it; where it refuses (some
     * makers protect their launcher), taken off for this user only, the app staying on the TV, so
     * Bring back (or {@link #restoreHome}, should Aura leave) puts it back exactly. Off the main
     * thread. Null when debugging couldn't be reached, else whether it took.
     */
    static Boolean retireTvHome(Context c, boolean retire) {
        String pkg = tvHome(c);
        if (pkg == null || !pkg.matches("[A-Za-z0-9_.]+")) return false;
        if (pkg.equals(GOOGLE_TV_HOME)) return retireGoogleHome(c, retire);
        String me = c.getPackageName() + "/" + HomeActivity.class.getName();
        if (!retire) {
            List<String> out = Adb.run(c, "pm install-existing " + pkg, "pm enable " + pkg);
            if (out == null || out.isEmpty()) return null;
            boolean ok = !out.get(out.size() - 1).contains("Failure") && !out.get(out.size() - 1).contains("Exception");
            if (ok) Prefs.device(c).edit().remove(RETIRED).apply();
            return ok;
        }
        List<String> out = Adb.run(c, "am force-stop " + pkg, "pm disable-user --user 0 " + pkg);
        if (out == null || out.isEmpty()) return null;
        boolean ok = out.get(out.size() - 1).contains("disabled-user");
        if (!ok) {
            out = Adb.run(c, "pm uninstall -k --user 0 " + pkg);
            if (out == null || out.isEmpty()) return null;
            ok = out.get(out.size() - 1).contains("Success");
        }
        if (ok) {
            Prefs.device(c).edit().putString(RETIRED, pkg).apply();
            HomeKeys.setTakeover(c, true);
            if (android.os.Build.VERSION.SDK_INT >= 24) Adb.run(c, "cmd package set-home-activity " + me);
        }
        return ok;
    }

    /** Whether the TV's own home screen was switched off over ADB and needs re-enabling before Aura leaves. */
    static boolean originalDisabled(Context c) {
        String home = original(c);
        if (home == null) return false;
        try {
            return !c.getPackageManager().getApplicationInfo(home.substring(0, home.indexOf('/')), 0).enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * The TV's own home screen: a system app answering Home, other than Aura and any fallback, found
     * even while it's switched off (so it can be switched back on).
     */
    static String original(Context c) {
        PackageManager pm = c.getPackageManager();
        ResolveInfo settings = pm.resolveActivity(new Intent(Settings.ACTION_SETTINGS), PackageManager.MATCH_DEFAULT_ONLY);
        String skip = settings != null && settings.activityInfo != null ? settings.activityInfo.packageName : "";
        for (ResolveInfo r : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.GET_DISABLED_COMPONENTS)) {
            String pkg = r.activityInfo.packageName;
            if (pkg.equals(c.getPackageName()) || pkg.equals(skip) || pkg.equals("com.google.android.tungsten.setupwraith")) continue;
            if ((r.activityInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0) continue;
            // a fallback (Settings' or a chip maker's, as Realtek's) is a blank screen, not a home
            if (r.activityInfo.name.toLowerCase(java.util.Locale.ROOT).contains("fallback")) continue;
            String name = pkg + "/" + r.activityInfo.name;
            if (name.matches("[A-Za-z0-9_.$/]+")) return name;
        }
        return null;
    }
}
