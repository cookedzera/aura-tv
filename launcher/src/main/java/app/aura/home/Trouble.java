package app.aura.home;

import android.annotation.TargetApi;
import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What went wrong, kept on the TV so it can be sent when someone reports a problem: crashes (the
 * error and where it happened), errors Aura caught and lived through, the home screen freezing,
 * Android's own record of why Aura stopped (Android 11+: not responding, out of memory, a native
 * crash), and what the TV's installer said about an update.
 *
 * Only what went wrong and what TV this is; nothing leaves the TV by itself. Settings → Problem
 * reports shows the count and opens a page on the phone with the whole report, to copy or save.
 * A small file, newest last, trimmed to the last {@link #MAX_ENTRIES} entries.
 */
final class Trouble {
    private static final String FILE = "trouble.log", SEP = "\n\u001e\n";
    private static final int MAX_ENTRIES = 30, MAX_CHARS = 60_000, FRAMES = 24, CAUSE_FRAMES = 10;
    /** The same caught error is noted once an hour at most: a loop failing every second can't fill the log. */
    private static final long REPEAT_MS = 60 * 60_000L;
    private static final Map<String, Long> lastNoted = new HashMap<>();
    private static volatile Context app;
    /** How many entries the file holds, once read: Settings asks on every redraw. */
    private static volatile int known = -1;

    private Trouble() { }

    /** First thing as the process starts (Aura's Application). */
    static void init(Context c) {
        app = c.getApplicationContext();
    }

    // ---- noting ------------------------------------------------------------------------------

    /** A crash: written at once, the process is on its way out. */
    static void crash(Thread t, Throwable e) {
        write("CRASH", "on " + t.getName(), stack(e));
    }

    /**
     * An error caught and lived through: a background job that failed, a misbehaving app or
     * provider. Aura goes on; the error is kept, once an hour at most for the same one.
     */
    static void caught(String where, Throwable e) {
        if (e == null) return;
        StackTraceElement[] st = e.getStackTrace();
        String key = where + "|" + e.getClass().getName() + "|" + (st.length > 0 ? st[0].toString() : "");
        long now = SystemClock.elapsedRealtime();
        synchronized (lastNoted) {
            Long at = lastNoted.get(key);
            if (at != null && now - at < REPEAT_MS) return;
            lastNoted.put(key, now);
        }
        write("CAUGHT", "in " + where, stack(e));
    }

    /** A plain note: what the installer said, how long the boot took. */
    static void note(String kind, String text) {
        write(kind, null, text);
    }

    /** Entries noted on the main thread, not yet on disk: readers see them all the same. */
    private static final List<String> pending = new ArrayList<>();
    private static java.util.concurrent.ExecutorService saver;

    private static void write(String kind, String where, String body) {
        final Context c = app;
        if (c == null) return;
        final String entry;
        try {
            StringBuilder b = new StringBuilder();
            b.append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date()))
                    .append("  ").append(kind);
            if (where != null) b.append("  ").append(where);
            b.append("  (Aura ").append(version(c)).append(", up ").append(minutes(SystemClock.elapsedRealtime())).append(")");
            if (body != null && !body.isEmpty()) b.append('\n').append(body);
            // also in Android's own log, for tools/diagnose.ps1 (adb logcat -s Aura)
            android.util.Log.w("Aura", b.toString());
            entry = b.toString();
        } catch (Throwable ignored) {
            return;
        }
        // on the main thread (a key press, an app that wouldn't open) the file is written behind
        // the screen: reading, rewriting and syncing it there held the remote. A crash is written
        // at once: the process is on its way out
        if (!"CRASH".equals(kind) && Looper.myLooper() == Looper.getMainLooper()) {
            java.util.concurrent.ExecutorService s;
            synchronized (Trouble.class) {
                pending.add(entry);
                if (saver == null) saver = Compat.background("trouble", android.os.Process.THREAD_PRIORITY_BACKGROUND);
                s = saver;
            }
            s.execute(() -> save(c, entry, true));
        } else {
            save(c, entry, false);
        }
    }

    private static synchronized void save(Context c, String entry, boolean queued) {
        // cleared (Settings → Problem reports) while it waited: it goes with the rest
        if (queued && !pending.remove(entry)) return;
        try {
            List<String> entries = saved(c);
            // written at once (a crash, or off the main thread): any still waiting go first, in order,
            // so a crash just after a noted error doesn't take that note with it
            if (!queued) {
                entries.addAll(pending);
                pending.clear();
            }
            entries.add(entry);
            while (entries.size() > MAX_ENTRIES) entries.remove(0);
            StringBuilder all = new StringBuilder();
            for (String e : entries) all.append(all.length() > 0 ? SEP : "").append(e);
            // the oldest go first should it grow too long
            while (all.length() > MAX_CHARS && entries.size() > 1) {
                entries.remove(0);
                all.setLength(0);
                for (String e : entries) all.append(all.length() > 0 ? SEP : "").append(e);
            }
            File f = file(c), part = new File(f.getPath() + ".part");
            try (FileOutputStream out = new FileOutputStream(part)) {
                out.write(all.toString().getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            // Android's rename replaces the old file; a desktop JVM's (the unit tests on Windows) won't
            if (!part.renameTo(f) && (!f.delete() || !part.renameTo(f))) part.delete();
            known = entries.size();
        } catch (Throwable ignored) {
            // nothing more can be done about a problem with the problem log
        }
    }

    /**
     * The error as text: its message and the frames that matter, then each cause. Written out here
     * rather than with Log's helper, which leaves out the whole trace for some network errors.
     */
    static String stack(Throwable e) {
        StringBuilder b = new StringBuilder();
        int depth = 0;
        for (Throwable t = e; t != null && depth < 6; t = t.getCause(), depth++) {
            if (depth > 0) b.append("\nCaused by: ");
            b.append(t.toString());
            StackTraceElement[] st = t.getStackTrace();
            int max = depth == 0 ? FRAMES : CAUSE_FRAMES;
            for (int i = 0; i < st.length && i < max; i++) b.append("\n    at ").append(st[i]);
            if (st.length > max) b.append("\n    … ").append(st.length - max).append(" more");
            if (t.getCause() == t) break;
        }
        return b.toString();
    }

    // ---- reading -----------------------------------------------------------------------------

    private static File file(Context c) {
        return new File(c.getFilesDir(), FILE);
    }

    /** Every entry: those on disk, then any still on their way there. */
    private static synchronized List<String> entries(Context c) {
        List<String> out = saved(c);
        out.addAll(pending);
        while (out.size() > MAX_ENTRIES) out.remove(0);
        return out;
    }

    private static List<String> saved(Context c) {
        List<String> out = new ArrayList<>();
        File f = file(c);
        if (!f.isFile()) return out;
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) Math.min(f.length(), MAX_CHARS * 4L)];
            int n = 0;
            for (int r; n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0; ) n += r;
            for (String e : new String(buf, 0, n, StandardCharsets.UTF_8).split(SEP)) if (!e.trim().isEmpty()) out.add(e);
        } catch (IOException ignored) {
        }
        return out;
    }

    /** How many entries are kept: Settings shows it. */
    static synchronized int count(Context c) {
        if (known < 0) known = saved(c).size();
        return Math.min(MAX_ENTRIES, known + pending.size());
    }

    static synchronized void clear(Context c) {
        //noinspection ResultOfMethodCallIgnored
        file(c).delete();
        pending.clear();
        known = 0;
        Prefs.device(c).edit().putInt("trouble_bg_kills", 0).apply();
    }

    /** The whole report: what TV this is, then every entry, newest first. Off the main thread. */
    static synchronized String report(Context c) {
        StringBuilder b = new StringBuilder();
        b.append("Aura problem report\n");
        b.append(about(c));
        List<String> entries = entries(c);
        b.append("\n").append(entries.isEmpty() ? "Nothing has gone wrong lately." : entries.size() + " entries, newest first:").append("\n");
        for (int i = entries.size() - 1; i >= 0; i--) b.append("\n").append(entries.get(i)).append("\n");
        return b.toString();
    }

    /** What TV this is and how Aura is set up on it: the questions every report starts with. */
    private static String about(Context c) {
        StringBuilder b = new StringBuilder();
        line(b, "Aura", version(c) + " (" + code(c) + ")");
        line(b, "Android", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        line(b, "TV", Build.MANUFACTURER + " " + Build.MODEL + " (" + Build.DEVICE + ")");
        line(b, "Build", Build.DISPLAY);
        if (fireTv(c)) {
            String fire = firstProp("ro.build.version.name", "ro.build.version.fireos", "ro.build.version.number");
            line(b, "Fire TV", fire.isEmpty() ? "yes" : fire);
        }
        try {
            ActivityManager am = Compat.svc(c, ActivityManager.class);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            line(b, "Memory", (mi.totalMem >> 20) + " MB, " + (mi.availMem >> 20) + " MB free"
                    + (am.isLowRamDevice() ? ", low-RAM device" : "") + ", app limit " + am.getMemoryClass() + " MB");
        } catch (RuntimeException ignored) {
        }
        line(b, "Cores", String.valueOf(Runtime.getRuntime().availableProcessors()));
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        line(b, "Screen", dm.widthPixels + "×" + dm.heightPixels + " at " + dm.densityDpi + " dpi");
        line(b, "Light mode", Perf.mode(c) + (Perf.light(c) ? " (on)" : " (off)"));
        line(b, "Set up", (Adb.on(c) ? "debugging connected" : "no debugging")
                + (HomeKeys.wanted(c) ? ", Home button service wanted" + (HomeKeys.live ? " and running" : " but not running") : "")
                + (HomeRole.held(c) ? ", Aura is the home app" : ""));
        line(b, "Allowed", (Compat.granted(c, "android.permission.READ_TV_LISTINGS") ? "TV listings" : "no TV listings")
                + (Habits.granted(c) ? ", usage access" : ", no usage access"));
        line(b, "Up", "TV " + minutes(SystemClock.elapsedRealtime()));
        String starts = Prefs.device(c).getString("trouble_starts", "");
        if (!starts.isEmpty()) b.append("Last starts (newest first):\n").append(starts);
        int kills = Prefs.device(c).getInt("trouble_bg_kills", 0);
        if (kills > 0) line(b, "Memory", "Android closed Aura in the background to free memory " + kills + " times (a slower return to home each time)");
        return b.toString();
    }

    /**
     * How long a start from scratch took (the boot screen until home was on show), and what it
     * waited for: kept with the report, the last five, not counted as a problem. A TV that takes
     * long to show home says here whether its apps, its photograph or the TV itself was slow.
     */
    static void started(Context c, long shownMs, long appsMs, long appsLoadMs, int apps, long wallMs) {
        String line = "  " + new SimpleDateFormat("MM-dd HH:mm", Locale.ROOT).format(new Date())
                + ": home in " + shownMs + " ms; apps ready at " + appsMs + " ms (" + apps + " read in " + appsLoadMs
                + " ms); photo at " + wallMs + " ms; TV up " + SystemClock.elapsedRealtime() / 1000 + " s\n";
        android.util.Log.i("Aura", "start:" + line.trim());
        SharedPreferences p = Prefs.device(c);
        String[] old = p.getString("trouble_starts", "").split("\n");
        StringBuilder b = new StringBuilder(line);
        for (int i = 0, n = 1; i < old.length && n < 5; i++) {
            if (old[i].trim().isEmpty()) continue;
            b.append(old[i]).append('\n');
            n++;
        }
        p.edit().putString("trouble_starts", b.toString()).apply();
    }

    private static void line(StringBuilder b, String k, String v) {
        b.append(k).append(": ").append(v).append('\n');
    }

    static boolean fireTv(Context c) {
        return c.getPackageManager().hasSystemFeature("amazon.hardware.fire_tv")
                || "Amazon".equalsIgnoreCase(Build.MANUFACTURER) && Build.MODEL != null && Build.MODEL.startsWith("AFT");
    }

    private static String firstProp(String... names) {
        for (String n : names) {
            String v = prop(n);
            if (!v.isEmpty()) return v;
        }
        return "";
    }

    /** A system property, or "" (read the same way Android's own code does; allowed for every app). */
    private static String prop(String name) {
        try {
            Object v = Class.forName("android.os.SystemProperties").getMethod("get", String.class).invoke(null, name);
            return v instanceof String ? (String) v : "";
        } catch (Throwable e) {
            return "";
        }
    }

    private static String version(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    @SuppressWarnings("deprecation")
    private static int code(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    private static String minutes(long ms) {
        long m = ms / 60_000;
        return m < 60 ? m + " min" : m / 60 + " h " + m % 60 + " min";
    }

    // ---- Android's own record (Android 11+) ---------------------------------------------------

    /**
     * Why Aura's earlier runs ended, as Android recorded it: not responding (with what the main
     * thread was doing), a native crash, out of memory while on screen, too much work. Java crashes
     * are already noted by Aura itself. Kills while Aura sat in the background, to free memory for
     * the app in use, are normal on a TV: they're only counted. Each exit is read once. Off the main thread.
     */
    static void exits(Context c) {
        if (Build.VERSION.SDK_INT < 30) return;
        try {
            readExits(c);
        } catch (Throwable e) {
            caught("exits", e);
        }
    }

    @TargetApi(30)
    private static void readExits(Context c) throws IOException {
        ActivityManager am = Compat.svc(c, ActivityManager.class);
        if (am == null) return;
        SharedPreferences p = Prefs.device(c);
        long seen = p.getLong("trouble_exit_seen", 0), newest = seen;
        int kills = p.getInt("trouble_bg_kills", 0);
        List<android.app.ApplicationExitInfo> list = am.getHistoricalProcessExitReasons(c.getPackageName(), 0, 12);
        // oldest first, so the log reads in order
        for (int i = list.size() - 1; i >= 0; i--) {
            android.app.ApplicationExitInfo x = list.get(i);
            if (x.getTimestamp() <= seen) continue;
            newest = Math.max(newest, x.getTimestamp());
            int r = x.getReason();
            boolean onScreen = x.getImportance() <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE;
            String when = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date(x.getTimestamp()));
            String mem = "pss " + (x.getPss() >> 10) + " MB, rss " + (x.getRss() >> 10) + " MB";
            if (r == android.app.ApplicationExitInfo.REASON_ANR) {
                String trace = mainThread(x.getTraceInputStream());
                note("NOT RESPONDING", "at " + when + (onScreen ? ", on screen" : ", in the background") + ", " + mem
                        + (x.getDescription() != null ? "\n" + x.getDescription() : "") + (trace.isEmpty() ? "" : "\n" + trace));
            } else if (r == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE) {
                note("NATIVE CRASH", "at " + when + ", " + mem + (x.getDescription() != null ? "\n" + x.getDescription() : ""));
            } else if (r == android.app.ApplicationExitInfo.REASON_LOW_MEMORY
                    || r == android.app.ApplicationExitInfo.REASON_SIGNALED && x.getStatus() == 9) {
                if (onScreen) note("OUT OF MEMORY", "at " + when + ", while on screen, " + mem);
                else kills++;
            } else if (r == android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE
                    || r == android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE) {
                note("STOPPED BY ANDROID", "at " + when + ", reason " + r + ", " + mem
                        + (x.getDescription() != null ? "\n" + x.getDescription() : ""));
            }
        }
        p.edit().putLong("trouble_exit_seen", newest).putInt("trouble_bg_kills", kills).apply();
    }

    /** From an "app not responding" trace, the main thread's part: what it was stuck on. */
    private static String mainThread(InputStream in) throws IOException {
        if (in == null) return "";
        StringBuilder b = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            boolean inMain = false;
            int lines = 0;
            for (String s; (s = r.readLine()) != null && lines < 40; ) {
                if (s.startsWith("\"main\"")) inMain = true;
                else if (inMain && s.trim().isEmpty()) break;
                if (inMain) {
                    b.append(b.length() > 0 ? "\n" : "").append(s);
                    lines++;
                }
            }
        }
        return b.toString();
    }

    // ---- freezes -----------------------------------------------------------------------------

    /** How long the home screen may be unresponsive before it counts as a freeze. */
    private static final long FREEZE_MS = 2500;
    private static Thread dog;
    private static volatile boolean watching, answered;
    private static final Runnable ANSWER = () -> answered = true;

    /**
     * While the home screen is on show: a quiet check every couple of seconds that its main thread
     * still answers. One that doesn't for {@link #FREEZE_MS} is caught in the act: what it was
     * doing is noted, and how long it stayed stuck. Costs one tiny message every two seconds.
     */
    static synchronized void watchMain(boolean on) {
        watching = on;
        if (!on || dog != null) return;
        final Handler main = new Handler(Looper.getMainLooper());
        final Thread ui = Looper.getMainLooper().getThread();
        dog = new SafeThread(() -> {
            long lastNote = 0;
            try {
                while (watching) {
                    long asked = SystemClock.uptimeMillis();
                    answered = false;
                    main.post(ANSWER);
                    StackTraceElement[] stuck = null;
                    while (watching && !answered && SystemClock.uptimeMillis() - asked < 20_000) {
                        Thread.sleep(200);
                        if (stuck == null && !answered && SystemClock.uptimeMillis() - asked >= FREEZE_MS) stuck = ui.getStackTrace();
                    }
                    long held = SystemClock.uptimeMillis() - asked;
                    // one note a minute at most: a long struggle is one story, not thirty
                    if (stuck != null && SystemClock.uptimeMillis() - lastNote > 60_000) {
                        lastNote = SystemClock.uptimeMillis();
                        // unanswered: past the 20 s wait, or the home screen left while it was still stuck
                        StringBuilder b = new StringBuilder("the home screen didn't answer for ")
                                .append(answered ? held + " ms" : watching ? "over " + held / 1000 + " s" : "at least " + held + " ms")
                                .append(", stuck at:");
                        for (int i = 0; i < stuck.length && i < FRAMES; i++) b.append("\n    at ").append(stuck[i]);
                        note("FREEZE", b.toString());
                    }
                    Thread.sleep(2000);
                }
            } catch (InterruptedException ignored) {
            } finally {
                synchronized (Trouble.class) {
                    dog = null;
                    // switched off and straight back on while this one was leaving: a new one takes over
                    if (watching) watchMain(true);
                }
            }
        }, "freeze-watch");
        dog.setPriority(Thread.MIN_PRIORITY);
        dog.start();
    }
}
