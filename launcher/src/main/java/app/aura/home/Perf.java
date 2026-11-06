package app.aura.home;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ConfigurationInfo;

/**
 * Light mode, for TVs with little memory or a slow chip: the home screen keeps its look but stops
 * moving for nothing. No parallax or sway on tiles, no light following focus, no sheen, and the
 * photograph's slow drift redrawn a couple of times a second instead of fifteen. Auto turns it on
 * by itself on a low-end TV (see {@link #lowEnd}); Settings can force it either way.
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

    private static volatile Boolean low;

    /**
     * About 1.5 GB of memory or less, fewer than four cores, Android's own low-RAM flag, or an
     * OpenGL ES 2.0 GPU (Mali-400/450/470: the weakest fill rate on TVs, even on 2 GB boxes).
     * 1600 MiB sits above every 1.5 GB TV's report (at most 1536) and below nearly every 2 GB one.
     */
    static boolean lowEnd(Context c) {
        Boolean l = low;
        if (l == null) low = l = readLowEnd(c);
        return l;
    }

    private static boolean readLowEnd(Context c) {
        ActivityManager am = Compat.svc(c, ActivityManager.class);
        if (am == null) return false;
        if (am.isLowRamDevice()) return true;
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        if (mi.totalMem > 0 && mi.totalMem < (1600L << 20)) return true;
        ConfigurationInfo ci = am.getDeviceConfigurationInfo();
        if (ci != null && ci.reqGlEsVersion > 0 && ci.reqGlEsVersion < 0x30000) return true;
        return Runtime.getRuntime().availableProcessors() < 4;
    }
}
