package app.aura.home;

import android.app.ActivityManager;
import android.content.Context;

/**
 * Light mode, for TVs with little memory or a slow chip: the home screen keeps its look but stops
 * moving for nothing. No parallax or sway on tiles, no light following focus, no sheen, and the
 * photograph's slow drift redrawn a couple of times a second instead of fifteen. Auto turns it on
 * by itself on a low-end TV (about 1 GB of memory, or two cores); Settings can force it either way.
 */
final class Perf {
    static final String KEY = "set_light";
    static final String[] MODES = {"auto", "on", "off"};

    private Perf() { }

    static String mode(Context c) {
        String m = Prefs.device(c).getString(KEY, "auto");
        return java.util.Arrays.asList(MODES).contains(m) ? m : "auto";
    }

    static boolean light(Context c) {
        String m = mode(c);
        return m.equals("on") || m.equals("auto") && lowEnd(c);
    }

    /** About a gigabyte of memory or less, two cores or fewer, or Android's own low-RAM flag. */
    static boolean lowEnd(Context c) {
        ActivityManager am = Compat.svc(c, ActivityManager.class);
        if (am == null) return false;
        if (am.isLowRamDevice()) return true;
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return mi.totalMem > 0 && mi.totalMem < (1300L << 20) || Runtime.getRuntime().availableProcessors() <= 2;
    }
}
