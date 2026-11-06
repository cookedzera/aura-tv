package app.aura.home;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.provider.Settings;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Whether the Home button opens Aura, and asking Android to make it so. From Android 10 an app can
 * ask for the home role itself: Android puts a plain yes/no on the screen, which the remote
 * answers, and nothing in Android leaves TVs out. Many TV Settings apps have no "Default home"
 * page at all, so without this, only a PC could change it. After a second No, Android adds
 * "Don't ask again" and then turns every later request away unseen, so Aura only asks when you
 * press for it, never by itself.
 *
 * Google TV (an onn box, a Chromecast) and Fire TV keep Home for their own launcher whatever is
 * chosen. There, the Home button service ({@link HomeKeys}) takes Home before Android does and
 * opens Aura instead: the only way past it short of switching the launcher off over ADB.
 */
final class HomeRole {
    static final int REQUEST = 41;
    /** Android once answered yes and Home still didn't open Aura (see ignoresChoice). */
    private static final String IGNORED = "home_choice_ignored";
    /** Launchers that keep Home whatever the role says: Google TV's, then Fire TV's. */
    private static final String[] STUBBORN = {
            "com.google.android.apps.tv.launcherx",
            "com.amazon.tv.launcher",
            "com.amazon.firehomestarter",
            "com.amazon.tv.leanbacklauncher"
    };

    private HomeRole() { }

    private static Intent home() {
        return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
    }

    /**
     * Whether Home really opens Aura. Read from what Android resolves Home to, not from the role:
     * Google TV can hand Aura the role and still open its own launcher on Home.
     */
    static boolean held(Context c) {
        ResolveInfo r = c.getPackageManager().resolveActivity(home(), PackageManager.MATCH_DEFAULT_ONLY);
        return r != null && r.activityInfo != null && c.getPackageName().equals(r.activityInfo.packageName);
    }

    /** A launcher that ignores the choice is installed and switched on: asking Android can't help. */
    static boolean stubborn(Context c) {
        return stubbornPackage(c) != null;
    }

    private static String stubbornPackage(Context c) {
        for (String pkg : STUBBORN) {
            try {
                if (c.getPackageManager().getApplicationInfo(pkg, 0).enabled) return pkg;
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        return null;
    }

    /** The same, opening a sentence ("The TV's own home ..."). */
    static String otherAtStart(Context c) {
        String s = other(c);
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.getDefault()) + s.substring(1);
    }

    /** What Home opens instead of Aura, by name: "Google TV", "Fire TV" or the TV's own. */
    static String other(Context c) {
        String s = stubbornPackage(c);
        return s == null ? c.getString(R.string.the_tvs_own_home) : s.contains("amazon") ? "Fire TV" : "Google TV";
    }

    /**
     * The other home screens' activities as "package/class" (an alias under both its names), so a
     * home screen coming up can be told from the rest of its app. Fallbacks (blank stand-ins that
     * only show when there's no home at all) are left out.
     */
    static Set<String> others(Context c) {
        Set<String> out = new HashSet<>();
        for (ResolveInfo r : c.getPackageManager().queryIntentActivities(home(), 0)) {
            if (r.activityInfo == null || c.getPackageName().equals(r.activityInfo.packageName)) continue;
            if (r.activityInfo.name.toLowerCase(Locale.ROOT).contains("fallback")) continue;
            if ((r.activityInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0) continue;
            out.add(r.activityInfo.packageName + "/" + r.activityInfo.name);
            if (r.activityInfo.targetActivity != null) out.add(r.activityInfo.packageName + "/" + r.activityInfo.targetActivity);
        }
        return out;
    }

    /**
     * Android's own question (the answer comes back to the activity's onActivityResult), or on
     * older TVs the home settings page. False when this TV offers neither.
     */
    static boolean ask(Activity a) {
        if (Build.VERSION.SDK_INT >= 29) {
            android.app.role.RoleManager rm = a.getSystemService(android.app.role.RoleManager.class);
            if (rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME)) {
                try {
                    // for result: Android only answers an app it knows asked, and it knows from this
                    a.startActivityForResult(rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_HOME), REQUEST);
                    return true;
                } catch (RuntimeException ignored) {
                }
            }
        }
        try {
            HomeKeys.letTvHomePass();
            a.startActivity(new Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Make Home open Aura, the best way this TV allows, and say what happened (null: Android is
     * asking on screen). Where Android won't hand Home over, the Home button service does it:
     * switched on by Aura itself once setup has allowed that, else on Android's Accessibility page.
     */
    static String make(Activity a) {
        if (held(a)) return a.getString(R.string.home_already_opens_aura);
        if (HomeKeys.on(a) && HomeKeys.live) {
            HomeKeys.setTakeover(a, true);
            return a.getString(R.string.home_now_opens_aura);
        }
        if (keepsHome(a) || !askable(a)) {
            HomeKeys.setTakeover(a, true);
            if (HomeKeys.set(a, true)) return a.getString(R.string.home_now_opens_aura);
            // the message first, then Android's page, where it's switched on by hand; Google TV's
            // usual Accessibility link leads nowhere, so without its own page, setup does it
            if (!byHand(a)) return restricted(a) ? a.getString(R.string.android_locks_that_switch)
                    : a.getString(R.string.setup_makes_home_open_aura);
            waiting = true;
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                if (!openAccessibility(a)) waiting = false;
            }, 2200);
            return a.getString(R.string.next_aura_switch_it_on, other(a));
        }
        return ask(a) ? null : a.getString(R.string.press_home_on_the_remote);
    }

    /** Sent to Android's Accessibility page to switch the service on: Aura says so once it's running. */
    static boolean waiting;

    /** Android 10's question exists on this TV. */
    private static boolean askable(Context c) {
        if (Build.VERSION.SDK_INT < 29) return true;
        android.app.role.RoleManager rm = c.getSystemService(android.app.role.RoleManager.class);
        return rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME);
    }

    /**
     * Android already counts Aura as the home app, yet Home opens another launcher (an Nvidia Shield):
     * asking again only gets an instant yes with nothing on screen, and asking on that yes went round
     * and round, the row flickering and the remote crawling until Home was pressed a few times.
     * Remembered once seen, as a TV that said yes and still didn't change is the same TV next time.
     */
    static boolean ignoresChoice(Context c) {
        if (held(c)) return false;
        if (Prefs.device(c).getBoolean(IGNORED, false)) return true;
        if (Build.VERSION.SDK_INT < 29) return false;
        try {
            android.app.role.RoleManager rm = c.getSystemService(android.app.role.RoleManager.class);
            return rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME)
                    && rm.isRoleHeld(android.app.role.RoleManager.ROLE_HOME);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Home stays with another launcher whatever is chosen: only the Home button service gets past it. */
    static boolean keepsHome(Context c) {
        return stubborn(c) || ignoresChoice(c);
    }

    /** Android answered yes and Home still doesn't open Aura: from now on the Home button service does it. */
    static void choiceIgnored(Context c) {
        Prefs.device(c).edit().putBoolean(IGNORED, true).apply();
    }

    /** Android won't hand Home over and Aura can't take it yet: the welcome walks through setting it up. */
    static boolean needsSetup(Context c) {
        return !held(c) && (keepsHome(c) || !askable(c)) && !(HomeKeys.on(c) && HomeKeys.live);
    }

    /**
     * The TV's real Accessibility page, or null. Google TV answers the usual link with a stub that
     * shows nothing (frameworkpackagestubs); its own Settings app keeps the page under a TV link.
     */
    static Intent accessibilityPage(Context c) {
        for (String action : new String[]{"android.settings.ACCESSIBILITY_TV_OEM_LINK", Settings.ACTION_ACCESSIBILITY_SETTINGS}) {
            Intent i = new Intent(action);
            if (real(c, i)) return i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        return null;
    }

    /** An Android settings page that exists here, and isn't a stub standing in for one. */
    static boolean real(Context c, Intent i) {
        ResolveInfo r = c.getPackageManager().resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY);
        return r != null && r.activityInfo != null && !r.activityInfo.packageName.contains("stub")
                && !"android".equals(r.activityInfo.packageName);
    }

    /**
     * Android 13+ greys out the Accessibility switch of an app installed from a file (a "restricted
     * setting"), and a TV has no menu to lift it: then only setup over debugging can switch it on.
     */
    static boolean restricted(Context c) {
        if (Build.VERSION.SDK_INT < 33) return false;
        try {
            android.app.AppOpsManager ops = c.getSystemService(android.app.AppOpsManager.class);
            return ops != null && ops.unsafeCheckOpNoThrow("android:access_restricted_settings", android.os.Process.myUid(),
                    c.getPackageName()) == android.app.AppOpsManager.MODE_ERRORED;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** The Home button service can be switched on by hand here: a real page, and a switch that isn't locked. */
    static boolean byHand(Context c) {
        return accessibilityPage(c) != null && !restricted(c);
    }

    /** Android's Accessibility page, where the Home button service is switched on by hand. */
    static boolean openAccessibility(Activity a) {
        Intent i = accessibilityPage(a);
        if (i == null) return false;
        try {
            HomeKeys.letTvHomePass();
            a.startActivity(i);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
