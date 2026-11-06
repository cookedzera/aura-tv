package app.aura.home;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Free up memory: apps the TV came with that sit in memory for things a TV with Aura doesn't need
 * (promotions, telemetry, Google's own screensaver, a shop demo), switched off over the TV's own
 * debugging, and back on, one by one. Only apps known to be safe to switch off are offered, never
 * voice search, setup, the tuners or Android TV's core services (Continue watching reads those);
 * the few whose loss you'd notice say what stops working and ask twice. Nothing is uninstalled.
 */
final class Debloat {
    static final class Item {
        /** A package, or the start of one ending in a dot (the auto-installer has a suffix per maker). */
        final String pkg;
        final int what, lose;
        final boolean recommended;

        Item(String pkg, int what, int lose, boolean recommended) {
            this.pkg = pkg;
            this.what = what;
            this.lose = lose;
            this.recommended = recommended;
        }
    }

    static final Item[] LIST = {
            new Item("android.autoinstalls.config.", R.string.db_autoinstall, 0, true),
            new Item("tv.anoki.", R.string.db_acr, 0, true),
            new Item("com.google.android.leanbacklauncher.partnercustomizer", R.string.db_partnerrows, 0, true),
            new Item("com.google.android.partnersetup", R.string.db_partnersetup, 0, true),
            new Item("com.google.android.feedback", R.string.db_feedback, 0, true),
            new Item("com.google.android.apps.tv.dreamx", R.string.db_screensaver, 0, true),
            new Item("com.google.android.backdrop", R.string.db_screensaver, 0, true),
            new Item("com.android.printspooler", R.string.db_print, 0, true),
            new Item("com.google.android.syncadapters.calendar", R.string.db_calendar, 0, true),
            new Item("com.google.android.play.games", R.string.db_games, 0, true),
            new Item("com.google.android.videos", R.string.db_store, 0, true),
            new Item("com.realtek.retailmode", R.string.db_demo, 0, true),
            new Item("com.google.android.retaildemo", R.string.db_demo, 0, true),
            new Item("com.vewd.core.service", R.string.db_vewd, 0, true),
            new Item("com.google.android.apps.mediashell", R.string.db_cast, R.string.db_cast_lose, false),
            new Item("com.google.android.tv.remote.service", R.string.db_remote, R.string.db_remote_lose, false),
    };

    private Debloat() { }

    /** An entry as found on this TV: its package, its name, whether it's on. */
    static final class Found {
        final Item item;
        final String pkg, label;
        boolean on;

        Found(Item item, String pkg, String label, boolean on) {
            this.item = item;
            this.pkg = pkg;
            this.label = label;
            this.on = on;
        }
    }

    /** The listed apps installed here (switched off ones too), in the list's order. */
    static List<Found> present(Context c) {
        PackageManager pm = c.getPackageManager();
        List<ApplicationInfo> all = pm.getInstalledApplications(PackageManager.GET_DISABLED_COMPONENTS);
        List<Found> out = new ArrayList<>();
        for (Item it : LIST) {
            for (ApplicationInfo ai : all) {
                if (!(it.pkg.endsWith(".") ? ai.packageName.startsWith(it.pkg) : ai.packageName.equals(it.pkg))) continue;
                out.add(new Found(it, ai.packageName, String.valueOf(pm.getApplicationLabel(ai)), ai.enabled));
            }
        }
        return out;
    }

    /**
     * Switches an app off (stopped first, so its memory comes back at once) or on again. Off the
     * main thread. Null when debugging couldn't be reached, else whether Android took it.
     */
    static Boolean set(Context c, String pkg, boolean off) {
        if (!pkg.matches("[A-Za-z0-9_.]+")) return false;
        List<String> out = off ? Adb.run(c, "am force-stop " + pkg, "pm disable-user --user 0 " + pkg) : Adb.run(c, "pm enable " + pkg);
        if (out == null || out.isEmpty()) return null;
        String last = out.get(out.size() - 1);
        return off ? last.contains("disabled-user") : last.contains("enabled");
    }

    /**
     * How much memory each app uses now, in MB, from Android's own report (needs the permission
     * setup grants). Off the main thread; empty when it can't be read.
     */
    static Map<String, Integer> memory() {
        final Map<String, Integer> mb = new HashMap<>();
        // compact lines: proc,<state>,<process>,<pid>,<pss kB>,...
        Reports.read("meminfo", new String[]{"-c"}, s -> {
            if (!s.startsWith("proc,")) return true;
            String[] f = s.split(",");
            if (f.length < 5) return true;
            String name = f[2];
            int colon = name.indexOf(':');
            if (colon > 0) name = name.substring(0, colon);
            try {
                int kb = Integer.parseInt(f[4].trim());
                Integer had = mb.get(name);
                mb.put(name, (had == null ? 0 : had) + Math.round(kb / 1024f));
            } catch (NumberFormatException ignored) {
            }
            return true;
        });
        return mb;
    }
}
