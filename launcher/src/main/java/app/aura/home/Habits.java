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
    /** UsageEvents.Event.SCREEN_NON_INTERACTIVE, which Android names from 9 on; older TVs simply never send it. */
    private static final int SCREEN_OFF = 16;

    private Habits() { }

    static boolean granted(Context c) {
        AppOpsManager ops = Compat.svc(c, AppOpsManager.class);
        if (ops == null) return false;
        // the same check under its older name before Android 10
        @SuppressWarnings("deprecation")
        int mode = android.os.Build.VERSION.SDK_INT >= 29
                ? ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName())
                : ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    /**
     * How strongly each app belongs to this hour: visits in the same hour count fully, the
     * neighbouring hours by half, and older days fade (about two weeks' half-life).
     */
    static Map<String, Float> forNow(Context c) {
        Map<String, Float> score = new HashMap<>();
        if (!granted(c)) return score;
        UsageStatsManager usm = Compat.svc(c, UsageStatsManager.class);
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
        UsageStatsManager usm = Compat.svc(c, UsageStatsManager.class);
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

    /**
     * Up to {@code max} apps among {@code allowed}, most recently used first, each with when it was
     * last on screen. For the app switcher. Off the main thread.
     */
    static java.util.LinkedHashMap<String, Long> recentList(Context c, java.util.Set<String> allowed, int max) {
        java.util.LinkedHashMap<String, Long> out = new java.util.LinkedHashMap<>();
        long now = System.currentTimeMillis();
        Map<String, Long> last = new HashMap<>();
        // without usage access, Aura's own list is still there
        if (granted(c)) {
            UsageEvents events = Compat.svc(c, UsageStatsManager.class).queryEvents(now - 7 * DAY, now);
            UsageEvents.Event e = new UsageEvents.Event();
            while (events.hasNextEvent()) {
                events.getNextEvent(e);
                if (e.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND && allowed.contains(e.getPackageName())) {
                    last.put(e.getPackageName(), e.getTimeStamp());
                }
            }
        }
        // Android's history on some TVs only reaches back to the last power-on: Aura's own list
        // of what it opened fills in the rest, and learns from what Android still remembers
        android.content.SharedPreferences p = c.getSharedPreferences("aura_usage", Context.MODE_PRIVATE);
        Map<String, Long> kept = read(p.getString("recent", "{}"));
        for (Map.Entry<String, Long> k : kept.entrySet()) {
            if (allowed.contains(k.getKey()) && (!last.containsKey(k.getKey()) || last.get(k.getKey()) < k.getValue())) last.put(k.getKey(), k.getValue());
        }
        p.edit().putString("recent", write(last)).apply();
        // closed in the switcher: gone until it's used again
        Map<String, Long> closed = read(p.getString("closed", "{}"));
        for (Map.Entry<String, Long> k : closed.entrySet()) {
            Long t = last.get(k.getKey());
            if (t != null && t <= k.getValue()) last.remove(k.getKey());
        }
        java.util.List<Map.Entry<String, Long>> sorted = new java.util.ArrayList<>(last.entrySet());
        java.util.Collections.sort(sorted, (a, b) -> Long.compare(b.getValue(), a.getValue()));
        for (Map.Entry<String, Long> x : sorted) {
            if (out.size() >= max) break;
            out.put(x.getKey(), x.getValue());
        }
        return out;
    }

    /** An app was just opened from Aura: remembered for the switcher even across restarts. */
    static void opened(Context c, String pkg) {
        if (pkg == null) return;
        android.content.SharedPreferences p = c.getSharedPreferences("aura_usage", Context.MODE_PRIVATE);
        Map<String, Long> kept = read(p.getString("recent", "{}"));
        kept.put(pkg, System.currentTimeMillis());
        // the newest few dozen are plenty
        if (kept.size() > 30) {
            String oldest = null;
            for (Map.Entry<String, Long> e : kept.entrySet()) if (oldest == null || e.getValue() < kept.get(oldest)) oldest = e.getKey();
            kept.remove(oldest);
        }
        p.edit().putString("recent", write(kept)).apply();
    }

    /** Closed from the switcher: out of the list until it's next opened. */
    static void close(Context c, String pkg) {
        android.content.SharedPreferences p = c.getSharedPreferences("aura_usage", Context.MODE_PRIVATE);
        Map<String, Long> closed = read(p.getString("closed", "{}"));
        closed.put(pkg, System.currentTimeMillis());
        Map<String, Long> kept = read(p.getString("recent", "{}"));
        kept.remove(pkg);
        p.edit().putString("closed", write(closed)).putString("recent", write(kept)).apply();
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
        for (Map.Entry<String, Long> e : today(c).entrySet()) if (!e.getKey().equals(c.getPackageName())) total += e.getValue();
        return total;
    }

    /** The app with the most time on screen today (at least five minutes), or null. */
    static String topToday(Context c) {
        if (!granted(c)) return null;
        String best = null;
        long most = 5 * 60_000L;
        for (Map.Entry<String, Long> e : today(c).entrySet()) {
            if (e.getKey().equals(c.getPackageName()) || e.getValue() <= most) continue;
            most = e.getValue();
            best = e.getKey();
        }
        return best;
    }

    /**
     * Today's time per app, kept by Aura itself across restarts. Android writes its usage history
     * to disk only every so often, so a TV switched off at the wall loses the last stretch and
     * its own totals drop. Aura counts each boot from the events it's sure of, carries the total
     * from earlier boots today, and never lets a restart take time away.
     */
    private static synchronized Map<String, Long> today(Context c) {
        long now = System.currentTimeMillis(), boot = now - android.os.SystemClock.elapsedRealtime();
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long midnight = cal.getTimeInMillis();
        String day = String.valueOf(midnight);
        android.content.SharedPreferences p = c.getSharedPreferences("aura_usage", Context.MODE_PRIVATE);
        Map<String, Long> thisBoot = foreground(c, Math.max(midnight, boot), now);
        Map<String, Long> carried;
        if (!day.equals(p.getString("day", ""))) {
            // first look today: whatever Android still holds from earlier boots is the starting point
            carried = new HashMap<>();
            UsageStatsManager usm = Compat.svc(c, UsageStatsManager.class);
            for (UsageStats s : usm.queryAndAggregateUsageStats(midnight, now).values()) {
                long before = s.getTotalTimeInForeground() - (thisBoot.containsKey(s.getPackageName()) ? thisBoot.get(s.getPackageName()) : 0);
                if (before > 0) carried.put(s.getPackageName(), before);
            }
        } else {
            carried = read(p.getString("carried", "{}"));
            if (Math.abs(p.getLong("boot", 0) - boot) > 60_000) {
                // the TV restarted: the last boot's time joins what's carried, taking whichever is
                // larger of what Aura last saw and what Android managed to save of it
                Map<String, Long> last = read(p.getString("last", "{}"));
                Map<String, Long> saved = foreground(c, Math.max(midnight, p.getLong("boot", midnight)), boot);
                for (String k : saved.keySet()) if (!last.containsKey(k) || saved.get(k) > last.get(k)) last.put(k, saved.get(k));
                for (Map.Entry<String, Long> e : last.entrySet()) {
                    carried.put(e.getKey(), (carried.containsKey(e.getKey()) ? carried.get(e.getKey()) : 0) + e.getValue());
                }
            }
        }
        p.edit().putString("day", day).putLong("boot", boot).putString("carried", write(carried))
                .putString("last", write(thisBoot)).apply();
        Map<String, Long> total = new HashMap<>(carried);
        for (Map.Entry<String, Long> e : thisBoot.entrySet()) {
            total.put(e.getKey(), (total.containsKey(e.getKey()) ? total.get(e.getKey()) : 0) + e.getValue());
        }
        return total;
    }

    /** Time on screen per app between two moments, from the moments each came and went. */
    private static Map<String, Long> foreground(Context c, long from, long to) {
        Map<String, Long> out = new HashMap<>();
        if (to <= from) return out;
        UsageEvents events = Compat.svc(c, UsageStatsManager.class).queryEvents(from, to);
        UsageEvents.Event e = new UsageEvents.Event();
        String front = null;
        long since = from;
        while (events.hasNextEvent()) {
            events.getNextEvent(e);
            int type = e.getEventType();
            if (type == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                if (front != null && !front.equals(e.getPackageName())) add(out, front, e.getTimeStamp() - since);
                if (front == null || !front.equals(e.getPackageName())) since = e.getTimeStamp();
                front = e.getPackageName();
            } else if (front != null && (type == UsageEvents.Event.MOVE_TO_BACKGROUND && front.equals(e.getPackageName())
                    || type == SCREEN_OFF)) {
                // standby counts as nobody watching
                add(out, front, e.getTimeStamp() - since);
                front = null;
            }
        }
        if (front != null) add(out, front, to - since);
        return out;
    }

    private static void add(Map<String, Long> m, String k, long ms) {
        if (ms > 0) m.put(k, (m.containsKey(k) ? m.get(k) : 0) + ms);
    }

    private static Map<String, Long> read(String json) {
        Map<String, Long> m = new HashMap<>();
        try {
            org.json.JSONObject o = new org.json.JSONObject(json);
            for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                String k = it.next();
                m.put(k, o.optLong(k));
            }
        } catch (org.json.JSONException ignored) {
        }
        return m;
    }

    private static String write(Map<String, Long> m) {
        return new org.json.JSONObject(m).toString();
    }

    static String duration(long ms) {
        long min = ms / 60_000;
        if (min < 60) return min + " min";
        return (min / 60) + " h " + (min % 60) + " min";
    }
}
