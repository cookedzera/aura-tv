package app.aura.home;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Apps installed since Aura first saw the TV wear a small dot on their tile until they're first
 * opened, as on iPhone. What was there at the start never gets one. Kept for the whole TV.
 */
final class NewApps {
    private static final String KNOWN = "known_pkgs", FRESH = "new_pkgs";

    private NewApps() { }

    /** Off the main thread, as the app list is read: notes what's new and marks it on each app. */
    /** The dots alone, from what's already known (a saved list shown while the real one is read). */
    static synchronized void flag(Context c, List<AppRepo.App> apps) {
        Set<String> fresh = Prefs.device(c).getStringSet(FRESH, new HashSet<>());
        for (AppRepo.App a : apps) a.fresh = fresh.contains(a.pkg);
    }

    static synchronized void mark(Context c, List<AppRepo.App> apps) {
        SharedPreferences p = Prefs.device(c);
        Set<String> now = new HashSet<>();
        for (AppRepo.App a : apps) now.add(a.pkg);
        Set<String> known = p.getStringSet(KNOWN, null), fresh = new HashSet<>(p.getStringSet(FRESH, new HashSet<>()));
        if (known == null) {
            // the first look: everything already here is simply there
            p.edit().putStringSet(KNOWN, now).apply();
            return;
        }
        boolean changed = false;
        for (String pkg : now) {
            if (!known.contains(pkg)) {
                fresh.add(pkg);
                changed = true;
            }
        }
        // uninstalled ones are forgotten, so a reinstall counts as new again
        changed |= fresh.retainAll(now) | !known.equals(now);
        if (changed) p.edit().putStringSet(KNOWN, now).putStringSet(FRESH, fresh).apply();
        for (AppRepo.App a : apps) a.fresh = fresh.contains(a.pkg);
    }

    /** Opened: the dot goes. */
    static synchronized void opened(Context c, String pkg) {
        if (pkg == null) return;
        SharedPreferences p = Prefs.device(c);
        Set<String> fresh = new HashSet<>(p.getStringSet(FRESH, new HashSet<>()));
        if (fresh.remove(pkg)) p.edit().putStringSet(FRESH, fresh).apply();
    }
}
