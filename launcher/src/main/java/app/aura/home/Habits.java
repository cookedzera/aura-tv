package app.aura.home;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.Process;

import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;

/**
 * Viewing habits from Android's own usage history (granted once over ADB, read on-device only):
 * which apps you open at this hour, and how long you've spent in apps today.
 */
final class Habits {
    private static final long DAY = 86_400_000L;

    private Habits() { }

    static boolean granted(Context c) {
        AppOpsManager ops = c.getSystemService(AppOpsManager.class);
        return ops != null && ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(), c.getPackageName()) == AppOpsManager.MODE_ALLOWED;
    }

    /**
     * How strongly each app belongs to this hour: visits in the same hour count fully, the
     * neighbouring hours by half, and older days fade (about two weeks' half-life).
     */
    static Map<String, Float> forNow(Context c) {
        Map<String, Float> score = new HashMap<>();
        if (!granted(c)) return score;
        UsageStatsManager usm = c.getSystemService(UsageStatsManager.class);
        long now = System.currentTimeMillis();
        UsageEvents events = usm.queryEvents(now - 28 * DAY, now);
        UsageEvents.Event e = new UsageEvents.Event();
        Calendar cal = Calendar.getInstance();
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        String self = c.getPackageName(), lastPkg = null;
        long lastT = 0;
        while (events.hasNextEvent()) {
            events.getNextEvent(e);
            if (e.getEventType() != UsageEvents.Event.MOVE_TO_FOREGROUND) continue;
            String p = e.getPackageName();
            long t = e.getTimeStamp();
            if (p.equals(self)) continue;
            // one visit, not every screen inside the app
            boolean sameVisit = p.equals(lastPkg) && t - lastT < 10 * 60_000L;
            lastPkg = p;
            lastT = t;
            if (sameVisit) continue;
            cal.setTimeInMillis(t);
            int dh = Math.abs(cal.get(Calendar.HOUR_OF_DAY) - hour);
            dh = Math.min(dh, 24 - dh);
            float w = dh == 0 ? 1f : dh == 1 ? 0.45f : 0f;
            if (w == 0f) continue;
            w *= (float) Math.exp(-(now - t) / (double) DAY / 14.0);
            Float old = score.get(p);
            score.put(p, old == null ? w : old + w);
        }
        return score;
    }

    /**
     * The most recently opened app among {@code allowed} in the last week, as {package, time},
     * or null. Used for "Jump back in".
     */
    static Object[] recent(Context c, java.util.Set<String> allowed) {
        if (!granted(c)) return null;
        UsageStatsManager usm = c.getSystemService(UsageStatsManager.class);
        long now = System.currentTimeMillis();
        UsageEvents events = usm.queryEvents(now - 7 * DAY, now);
        UsageEvents.Event e = new UsageEvents.Event();
        String pkg = null;
        long when = 0;
        while (events.hasNextEvent()) {
            events.getNextEvent(e);
            if (e.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND && allowed.contains(e.getPackageName())) {
                pkg = e.getPackageName();
                when = e.getTimeStamp();
            }
        }
        return pkg == null ? null : new Object[]{pkg, when};
    }

    static String ago(long ms) {
        long min = Math.max(1, ms / 60_000);
        if (min < 60) return min + " min ago";
        long h = min / 60;
        if (h < 24) return h + (h == 1 ? " hour ago" : " hours ago");
        long d = h / 24;
        return d == 1 ? "yesterday" : d + " days ago";
    }

    /** Foreground time in apps since midnight (Aura itself excluded), or -1 without access. */
    static long todayMs(Context c) {
        if (!granted(c)) return -1;
        long total = 0;
        for (UsageStats s : today(c).values()) {
            if (!s.getPackageName().equals(c.getPackageName())) total += s.getTotalTimeInForeground();
        }
        return total;
    }

    /** The app with the most time on screen today (at least five minutes), or null. */
    static String topToday(Context c) {
        if (!granted(c)) return null;
        String best = null;
        long most = 5 * 60_000L;
        for (UsageStats s : today(c).values()) {
            if (s.getPackageName().equals(c.getPackageName()) || s.getTotalTimeInForeground() <= most) continue;
            most = s.getTotalTimeInForeground();
            best = s.getPackageName();
        }
        return best;
    }

    private static Map<String, UsageStats> today(Context c) {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        UsageStatsManager usm = c.getSystemService(UsageStatsManager.class);
        return usm.queryAndAggregateUsageStats(cal.getTimeInMillis(), System.currentTimeMillis());
    }

    static String duration(long ms) {
        long min = ms / 60_000;
        if (min < 60) return min + " min";
        return (min / 60) + " h " + (min % 60) + " min";
    }
}
