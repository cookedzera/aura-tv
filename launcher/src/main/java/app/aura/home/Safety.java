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
                long now = System.currentTimeMillis();
                if (from(e, SAVER)) {
                    // the screensaver shares Aura's process, but its troubles are its own: three of
                    // them within half an hour and it falls back to plain photographs, and the Home
                    // button, which the user never saw fail, stays as it is
                    List<String> times = recent(p.getString("saver_crashes", ""), now - AFTER_MS);
                    times.add(String.valueOf(now));
                    p.edit().putString("saver_crashes", TextUtils.join(",", times)).commit();
                    if (times.size() >= CRASHES) Prefs.of(c).edit().putString(Prefs.SAVER, "photos").commit();
                } else if (!beside(e)) {
                    List<String> times = recent(p.getString("crashes", ""), now - AFTER_MS);
                    times.add(String.valueOf(now));
                    p.edit().putString("crashes", TextUtils.join(",", times)).commit();
                }
            } catch (Throwable ignored) {
            }
            if (before != null) before.uncaughtException(t, e);
        });
    }

    /**
     * The screensaver's own classes; and the parts of Aura that run beside the home screen, on their
     * own: the media listener, phones and computers playing on the TV or sending to it, the remote's
     * log reader, the debugging link, the sleep timer, updates, the restart and the background jobs.
     */
    private static final String[] SAVER = {"Ambient", "AerialView", "Sayings", "Story"},
            BESIDE = {"MediaWatch", "Renderer", "Cast", "Drop", "ProfileButton", "Adb", "AdbTls", "Power", "SleepReceiver",
                    "SleepAdmin", "Updater", "Release", "Boot", "DailyJob", "Gallery", "Picks", "Fresh", "Weather"};

    /** Whether {@code e} (or what caused it) went through one of these classes, or the screensaver is on screen. */
    private static boolean from(Throwable e, String[] classes) {
        if (classes == SAVER && Ambient.showing) return true;
        for (Throwable x = e; x != null; x = x.getCause()) {
            for (StackTraceElement f : x.getStackTrace()) {
                String cls = f.getClassName();
                for (String k : classes) if (cls.startsWith("app.aura.home." + k)) return true;
            }
        }
        return false;
    }

    /**
     * Whether {@code e} broke out of one of the parts beside the home screen: the part is the one
     * Android (or a message on the main thread) called into, the outermost of Aura's own frames.
     * The home screen calling into one of them and failing there is still the home screen failing;
     * where Android wrapped the error in its own ("Unable to start activity"), the cause tells.
     */
    static boolean beside(Throwable e) {
        for (Throwable x = e; x != null; x = x.getCause()) {
            StackTraceElement[] st = x.getStackTrace();
            for (int i = st.length - 1; i >= 0; i--) {
                String cls = st[i].getClassName();
                if (!cls.startsWith("app.aura.home.")) continue;
                cls = cls.substring("app.aura.home.".length());
                // its inner classes and lambdas too ("Drop$1", "Drop$$ExternalSyntheticLambda0"), not
                // another class that starts the same ("WeatherSheet" is the home screen's)
                for (String k : BESIDE) if (cls.equals(k) || cls.startsWith(k + "$")) return true;
                return false;
            }
        }
        return false;
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
            // every profile's, not just the one in use: a crash on switching profiles is a crash too
            for (Profiles.Profile pr : Profiles.all(c)) {
                String file = Profiles.MAIN.equals(pr.id) ? "aura" : "aura_" + pr.id;
                c.getSharedPreferences(file, Context.MODE_PRIVATE).edit()
                        .putBoolean(Prefs.PARALLAX, false).putBoolean(Prefs.TOP_SHELF, false).putString(Prefs.SAVER, "photos").apply();
            }
            Previews.set(c, false);
            // a heal cut short would otherwise switch it straight back on at the next start
            Prefs.device(c).edit().putBoolean("keys_healing", false).commit();
            HomeKeys.set(c, false);
            return "safe";
        }
        // a home taken off for this user can't be seen by original(): the retired name, or apps
        // Free up memory switched off, are reason enough to hand things back
        if ((original(c) == null && !switchedOff(c)) || !Adb.on(c)) return null;
        final Context app = c.getApplicationContext();
        // Aura's own Home button must not drag the crashing home screen back over the TV's own
        HomeKeys.setTakeover(app, false);
        final boolean keysOn = HomeKeys.on(app) && !HomeKeys.set(app, false);
        handedBack = null;
        new SafeThread(() -> handedBack = restoreHome(app, keysOn), "give-home").start();
        return "home";
    }

    /** Whether {@link #check}'s hand-back ran (null while it's still going): the home screen's message waits for it. */
    static volatile Boolean handedBack;

    /**
     * Re-enables any retired system launcher (and Google TV's setup recovery home) and hands the
     * Home button back to the TV's own launcher over one ADB connection. Off the main thread.
     * True when the commands ran (debugging was reachable).
     */
    static boolean restoreHome(Context c) {
        return restoreHome(c, false);
    }

    /** As {@link #restoreHome(Context)}; with {@code keysOff} the Home button service is switched off over ADB too. */
    static boolean restoreHome(Context c, boolean keysOff) {
        String home = original(c);
        List<String> cmds = new ArrayList<>();
        PackageManager pm = c.getPackageManager();
        // the setup app's recovery home screens, switched off one by one on Android 8 and earlier
        if (android.os.Build.VERSION.SDK_INT < 28) {
            for (String comp : setupHomes(c)) {
                android.content.ComponentName cn = android.content.ComponentName.unflattenFromString(comp);
                if (cn != null && pm.getComponentEnabledSetting(cn) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                    cmds.add("pm enable --user 0 " + comp);
                }
            }
        }
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
        if (home != null && android.os.Build.VERSION.SDK_INT >= 24) cmds.add("cmd package set-home-activity " + home);
        if (keysOff) cmds.add(HomeKeys.disableCommand(c));
        if (cmds.isEmpty()) return true;
        List<String> out = Adb.run(c, cmds.toArray(new String[0]));
        return out != null && !out.isEmpty();
    }

    /** Google TV's setup app's own Home screens, as "package/class", where it has any. */
    private static List<String> setupHomes(Context c) {
        List<String> out = new ArrayList<>();
        for (ResolveInfo r : c.getPackageManager().queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.GET_DISABLED_COMPONENTS)) {
            if (r.activityInfo == null || !GOOGLE_TV_SETUP.equals(r.activityInfo.packageName)) continue;
            String comp = GOOGLE_TV_SETUP + "/" + r.activityInfo.name;
            if (comp.matches("[A-Za-z0-9_.$/]+")) out.add(comp);
        }
        return out;
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
     * Switches Google TV's own home screen back on over ADB, and its setup app's recovery Home with
     * it; Home stays Aura's. Off the main thread. Null when debugging couldn't be reached, else
     * whether Android took the change. Never switched off ({@link #tvHome}).
     */
    private static Boolean bringBackGoogleHome(Context c) {
        List<String> cmds = new ArrayList<>();
        cmds.add("pm enable " + GOOGLE_TV_HOME);
        // the setup app's recovery home screens (or the whole app, where it has none) went off with it
        // only on Android 8 and earlier, where the shell may switch a single screen
        if (android.os.Build.VERSION.SDK_INT < 28) {
            for (String comp : setupHomes(c)) cmds.add("pm enable --user 0 " + comp);
            cmds.add("pm enable " + GOOGLE_TV_SETUP);
        }
        cmds.add("cmd package set-home-activity " + c.getPackageName() + "/" + HomeActivity.class.getName());
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

    /** A home taken off for this user, or apps Free up memory switched off: remembered, as only ADB brings them back. */
    private static boolean switchedOff(Context c) {
        return Prefs.device(c).getString(RETIRED, null) != null
                || !Prefs.device(c).getStringSet(Debloat.MINE, new java.util.HashSet<>()).isEmpty();
    }

    /** Xiaomi's PatchWall, a prefix: its international sets have it as com.mitv.tvhome.atv. */
    private static final String XIAOMI_HOME = "com.mitv.tvhome";

    /**
     * The package of the TV's own home screen that can be switched off (or back on), or null.
     * Google TV's, else whatever system home the TV came with (Android TV's, a maker's). Never Fire
     * TV's: Amazon's home is also where its settings live. Google TV's and Xiaomi's only once they're
     * off, to bring back: switched off, they have left TVs black, restarting, or needing a factory reset.
     */
    static String tvHome(Context c) {
        if (Trouble.fireTv(c)) return null;
        String pkg = hasGoogleTvHome(c) ? GOOGLE_TV_HOME : Prefs.device(c).getString(RETIRED, null);
        if (pkg == null) {
            String home = original(c);
            if (home != null) pkg = home.substring(0, home.indexOf('/'));
        }
        return pkg != null && neverOff(pkg) && enabled(c, pkg) ? null : pkg;
    }

    private static boolean neverOff(String pkg) {
        return pkg.equals(GOOGLE_TV_HOME) || pkg.startsWith(XIAOMI_HOME);
    }

    private static boolean enabled(Context c, String pkg) {
        try {
            return c.getPackageManager().getApplicationInfo(pkg, 0).enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** That home is there and switched on. */
    static boolean tvHomeOn(Context c) {
        String pkg = tvHome(c);
        return pkg != null && enabled(c, pkg);
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
     * thread. Null when debugging couldn't be reached, else whether it took; Google TV's and Xiaomi's
     * are only ever brought back.
     */
    static Boolean retireTvHome(Context c, boolean retire) {
        String pkg = tvHome(c);
        if (pkg == null || !pkg.matches("[A-Za-z0-9_.]+") || (retire && neverOff(pkg))) return false;
        if (pkg.equals(GOOGLE_TV_HOME)) return bringBackGoogleHome(c);
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

    /**
     * Whether the TV's own home screen, or an app Free up memory switched off, was switched off over
     * ADB and needs re-enabling before Aura leaves.
     */
    static boolean originalDisabled(Context c) {
        // a home taken off for this user: Android can't show it, so the remembered name has to say
        if (switchedOff(c)) return true;
        String home = original(c);
        if (home == null) return false;
        try {
            ApplicationInfo ai = c.getPackageManager().getApplicationInfo(home.substring(0, home.indexOf('/')), PackageManager.GET_UNINSTALLED_PACKAGES);
            return !ai.enabled || (ai.flags & ApplicationInfo.FLAG_INSTALLED) == 0;
        } catch (PackageManager.NameNotFoundException e) {
            // it answered Home a moment ago, so it's there, only not for this user: switched off
            return true;
        }
    }

    /**
     * The TV's own home screen: a system app answering Home, other than Aura and any fallback, found
     * even while it's switched off or taken off for this user (so it can be switched back on).
     */
    static String original(Context c) {
        PackageManager pm = c.getPackageManager();
        ResolveInfo settings = pm.resolveActivity(new Intent(Settings.ACTION_SETTINGS), PackageManager.MATCH_DEFAULT_ONLY);
        String skip = settings != null && settings.activityInfo != null ? settings.activityInfo.packageName : "";
        for (ResolveInfo r : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.GET_DISABLED_COMPONENTS | PackageManager.GET_UNINSTALLED_PACKAGES)) {
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
