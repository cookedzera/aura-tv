package app.aura.home;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Android compiles apps ahead of time only while a device sits idle and charging, a moment a TV
 * switched off at the wall may never reach; until then the apps you use run partly interpreted and
 * open slower. While the screensaver rests (on a TV Aura is set up on), the apps you used most this
 * week are compiled with the profiles Android gathered as you used them: one at a time, a minute
 * after the screensaver starts, stopping the moment you're back, and once a day at most.
 */
final class Warm {
    private static final long DAY = 24 * 60 * 60_000L, START_MS = 60_000;
    private static final int APPS = 6;
    private static volatile boolean resting;
    private static Thread running;

    private Warm() { }

    static synchronized void start(Context ctx) {
        final Context c = ctx.getApplicationContext();
        resting = true;
        if (running != null || !Adb.on(c)) return;
        if (System.currentTimeMillis() - Prefs.device(c).getLong("warm_at", 0) < DAY - 60 * 60_000L) return;
        running = new Thread(() -> {
            try {
                // a screensaver that ends at once costs nothing
                Thread.sleep(START_MS);
                List<String> top = new ArrayList<>(Habits.recentList(c, openable(c), APPS).keySet());
                int done = 0;
                for (String p : top) {
                    if (!resting) return;
                    if (!p.matches("[A-Za-z0-9_.]+")) continue;
                    // already compiled this way: Android answers at once
                    List<String> out = Adb.run(c, "cmd package compile -m speed-profile " + p);
                    if (out == null) return;
                    if (out.get(0).contains("Success")) done++;
                }
                Prefs.device(c).edit().putLong("warm_at", System.currentTimeMillis()).putInt("warm_n", done).apply();
            } catch (InterruptedException ignored) {
            } finally {
                synchronized (Warm.class) {
                    running = null;
                }
            }
        }, "warm");
        running.setPriority(Thread.MIN_PRIORITY);
        running.start();
    }

    /** Back from the screensaver: the app being compiled finishes, the rest wait for next time. */
    static synchronized void stop() {
        resting = false;
        if (running != null) running.interrupt();
    }

    private static Set<String> openable(Context c) {
        Set<String> out = new HashSet<>();
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo r : c.getPackageManager().queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
                out.add(r.activityInfo.packageName);
            }
        }
        out.remove(c.getPackageName());
        return out;
    }
}
