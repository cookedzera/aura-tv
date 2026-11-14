package app.aura.home;

import android.annotation.TargetApi;
import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.FrameMetrics;
import android.view.ViewTreeObserver;
import android.view.Window;

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
import java.util.Arrays;
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
 * Only what went wrong, what TV this is and how smoothly home draws on it; nothing leaves the TV by itself. Settings → Problem
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
    /** How many problems the file holds, once read (what was only noted isn't counted): Settings asks on every redraw. */
    private static volatile int known = -1, pendingCount;

    private Trouble() { }

    /** First thing as the process starts (Aura's Application). */
    static void init(Context c) {
        app = c.getApplicationContext();
        if (known < 0) new SafeThread(() -> count(app), "trouble-init").start();
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
    /**
     * Two locks, so the main thread never waits on the file: {@code QUEUE} guards the pending
     * list (taken for an instant, on any thread), {@code DISK} the file itself (held through a
     * read, a rewrite and a sync, on background threads only). {@code DISK} may take {@code QUEUE}
     * inside it, never the other way round.
     */
    private static final Object QUEUE = new Object(), DISK = new Object();

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
            synchronized (QUEUE) {
                pending.add(entry);
                pendingCount = problems(pending);
                if (saver == null) saver = Compat.background("trouble", android.os.Process.THREAD_PRIORITY_BACKGROUND);
                s = saver;
            }
            s.execute(() -> save(c, entry, true));
        } else {
            save(c, entry, false);
        }
    }

    private static void save(Context c, String entry, boolean queued) {
        synchronized (QUEUE) {
            // cleared (Settings → Problem reports) while it waited: it goes with the rest
            if (queued && !pending.contains(entry)) return;
        }
        synchronized (DISK) {
            try {
                List<String> entries = saved(c);
                // written at once (a crash, or off the main thread): any still waiting go first, in order,
                // so a crash just after a noted error doesn't take that note with it
                if (!queued) {
                    synchronized (QUEUE) {
                        entries.addAll(pending);
                        pending.clear();
                        pendingCount = 0;
                    }
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
                synchronized (QUEUE) {
                    // off the waiting list and onto the count in one step, so a reader between the
                    // two never sees the entry in neither (or both)
                    if (!queued || pending.remove(entry)) known = problems(entries);
                    pendingCount = problems(pending);
                }
            } catch (Throwable ignored) {
                // nothing more can be done about a problem with the problem log
            }
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
    private static List<String> entries(Context c) {
        List<String> out;
        synchronized (DISK) {
            out = saved(c);
        }
        synchronized (QUEUE) {
            out.addAll(pending);
        }
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

    /**
     * Whether an entry is a problem, or only something noted on the way: an install that went
     * through, was asked about or was called off, home taking the remote back by itself, a phone
     * page closed after wrong codes. Those stay in the report for whoever reads it, uncounted.
     */
    static boolean problem(String entry) {
        int at = entry.indexOf("  ");
        if (at < 0) return true;
        String rest = entry.substring(at + 2);
        if (rest.startsWith("FOCUS  ") || rest.startsWith("PHONE LINK  ")) return false;
        if (!rest.startsWith("INSTALLER  ")) return true;
        int nl = rest.indexOf('\n');
        String said = nl < 0 ? "" : rest.substring(nl + 1);
        return !(said.contains("(installed)") || said.contains("(asking to confirm)") || said.contains("(cancelled)"));
    }

    private static int problems(List<String> entries) {
        int n = 0;
        for (String e : entries) if (problem(e)) n++;
        return n;
    }

    /** One thing that happened, however many times: a line on the TV's page, a card on the phone's. */
    static final class Item {
        /** CRASH, CAUGHT, FREEZE…; where it happened ("" when the kind says it all); when, last; what was said, last. */
        final String kind, where;
        String when, detail;
        final boolean problem;
        int times = 1;
        /** Dated before an entry that came earlier: the TV hadn't set its clock yet (the first seconds after a boot). */
        boolean clockUnset;

        Item(String entry) {
            int nl = entry.indexOf('\n');
            String head = nl < 0 ? entry : entry.substring(0, nl);
            detail = nl < 0 ? "" : entry.substring(nl + 1);
            when = head.length() >= 19 ? head.substring(0, 19) : head;
            int end = head.lastIndexOf("  (Aura ");
            String what = head.length() > 21 ? head.substring(21, end > 21 ? end : head.length()) : "";
            int two = what.indexOf("  ");
            kind = two < 0 ? what : what.substring(0, two);
            where = two < 0 ? "" : what.substring(two + 2);
            problem = Trouble.problem(entry);
        }

        /** The first line of what was said: the error itself, without its trace. */
        String first() {
            int nl = detail.indexOf('\n');
            return nl < 0 ? detail : detail.substring(0, nl);
        }

        /** The day and the minute, and how often when more than once. */
        String seen() {
            String day = when.length() >= 16 ? when.substring(5, 16) : when;
            return (times > 1 ? times + "× · " : "") + day;
        }
    }

    /**
     * What happened, the same thing told once with how often: problems first, then what was only
     * noted, each newest first. Reads the file: not for every redraw.
     */
    static List<Item> items(Context c) {
        Map<String, Item> by = new java.util.LinkedHashMap<>();
        String latest = "";
        for (String e : entries(c)) {
            Item i = new Item(e);
            if (i.when.compareTo(latest) < 0) i.clockUnset = true;
            else latest = i.when;
            String key = i.kind + "|" + i.where + "|" + i.first();
            Item was = by.remove(key);
            if (was != null) i.times = was.times + 1;
            by.put(key, i);
        }
        List<Item> out = new ArrayList<>();
        for (Item i : by.values()) if (i.problem) out.add(0, i);
        int problems = out.size();
        for (Item i : by.values()) if (!i.problem) out.add(problems, i);
        return out;
    }

    /** What a problem is, in the TV's language; for what was only noted, the note itself. */
    static String title(Context c, Item i) {
        if (!i.problem) return i.first().isEmpty() ? i.kind : i.first();
        switch (i.kind) {
            case "CRASH":
            case "NATIVE CRASH": return c.getString(R.string.trouble_crashed);
            case "CAUGHT": return c.getString(R.string.trouble_error, i.where.startsWith("in ") ? i.where.substring(3) : i.where);
            case "FREEZE": return c.getString(R.string.trouble_froze);
            case "NOT RESPONDING": return c.getString(R.string.trouble_not_answering);
            case "OUT OF MEMORY": return c.getString(R.string.trouble_out_of_memory);
            case "STOPPED BY ANDROID": return c.getString(R.string.trouble_stopped_by_android);
            case "INSTALLER": return c.getString(R.string.trouble_app_didnt_install);
            case "UPDATE": return c.getString(R.string.trouble_update_didnt_install);
            case "CAST": return c.getString(R.string.trouble_cast);
            case "AERIAL": return c.getString(R.string.trouble_aerial);
            default: return i.kind;
        }
    }

    /** How many problems are kept: Settings shows it. */
    static int count(Context c) {
        synchronized (QUEUE) {
            if (known >= 0) return Math.min(MAX_ENTRIES, known + pendingCount);
        }
        synchronized (DISK) {
            if (known < 0) known = problems(saved(c));
            synchronized (QUEUE) {
                return Math.min(MAX_ENTRIES, known + pendingCount);
            }
        }
    }

    static void clear(Context c) {
        synchronized (DISK) {
            //noinspection ResultOfMethodCallIgnored
            file(c).delete();
            synchronized (QUEUE) {
                pending.clear();
                pendingCount = 0;
            }
            known = 0;
        }
        Prefs.device(c).edit().putInt("trouble_bg_kills", 0).apply();
    }

    /**
     * The whole report: what TV this is, the problems (the same one once, with how often), then
     * what was only noted. Newest first. Off the main thread.
     */
    static String report(Context c) {
        return report(c, items(c));
    }

    static String report(Context c, List<Item> items) {
        StringBuilder b = new StringBuilder();
        b.append("Aura problem report\n");
        b.append(about(c));
        int problems = 0;
        for (Item i : items) if (i.problem) problems++;
        b.append("\n").append(problems == 0 ? "Nothing has gone wrong lately." : problems + (problems == 1 ? " problem" : " problems") + ", newest first:").append("\n");
        for (Item i : items) if (i.problem) told(b.append("\n"), i);
        if (problems < items.size()) {
            b.append("\nAlso noted (not problems):\n");
            for (Item i : items) if (!i.problem) told(b.append("\n"), i);
        }
        return b.toString();
    }

    private static void told(StringBuilder b, Item i) {
        b.append(i.when).append("  ").append(i.kind);
        if (!i.where.isEmpty()) b.append("  ").append(i.where);
        if (i.times > 1) b.append("  (").append(i.times).append(" times, this the last)");
        if (i.clockUnset) b.append("  (the TV's clock wasn't set yet)");
        if (!i.detail.isEmpty()) b.append('\n').append(i.detail);
        b.append('\n');
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
        line(b, "Native heap", (Debug.getNativeHeapAllocatedSize() >> 20) + " MB in use by Aura");
        line(b, "Tile pictures", (Card.heldBytes() >> 10) + " KB in " + Card.heldCount() + " cards");
        line(b, "Cores", String.valueOf(Runtime.getRuntime().availableProcessors()));
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        line(b, "Screen", dm.widthPixels + "×" + dm.heightPixels + " at " + dm.densityDpi + " dpi");
        line(b, "Light mode", Perf.mode(c) + (Perf.light(c) ? " (on)" : " (off)"));
        frames(b);
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
     * Home's minutes at rest (see {@link #frames}) are only counted while it's on show, too.
     */
    static synchronized void watchMain(boolean on) {
        if (on != watching) restClock(on);
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

    // ---- frames ------------------------------------------------------------------------------

    /**
     * Home left alone this long is at rest; arrow presses further apart than this are separate
     * bursts, and a burst's frames run on this long after its last press (the slide it started).
     */
    private static final long REST_MS = 15_000, BURST_GAP_MS = 1_000, BURST_TAIL_MS = 600;
    /** Frames home has drawn while on show; the count and the time when the minute being counted began (-1: none). Main thread only. */
    private static long drawn, restMark, restFrom = -1;
    /** Frames in the last whole minute home was left alone (-1 before there's been one), and when it ended. */
    private static volatile long lastRest = -1, lastRestAt;
    /** The last D-pad burst's frame times in µs, its latest 120 at most; it guards the fields after it. */
    private static final int[] burst = new int[120];
    private static int burstN;
    private static long arrowAt, burstAt, burstTimed, burstEnd;
    private static Handler mainHandler;
    private static final ViewTreeObserver.OnDrawListener DREW = () -> {
        if (watching) drawn++;
    };

    /** Each whole minute home is left alone, the frames it drew in it: the aim is none. */
    private static final Runnable REST = new Runnable() {
        @Override
        public void run() {
            long now = SystemClock.uptimeMillis(), from = HomeActivity.lastKeyAt + REST_MS;
            if (now < from) {
                mainHandler().postDelayed(this, from - now);
                return;
            }
            // counting began after the last press settled: the minute was untouched throughout
            if (restFrom >= from) {
                lastRest = drawn - restMark;
                lastRestAt = now;
            }
            restFrom = now;
            restMark = drawn;
            mainHandler().postDelayed(this, 60_000);
        }
    };

    private static Handler mainHandler() {
        if (mainHandler == null) mainHandler = new Handler(Looper.getMainLooper());
        return mainHandler;
    }

    /** Home came on show, or went (main thread): a minute at rest counts only whole, from 15 s after it's back. */
    private static void restClock(boolean on) {
        mainHandler().removeCallbacks(REST);
        restFrom = -1;
        if (on) mainHandler().postDelayed(REST, REST_MS);
    }

    /** Counts home's frames from now on: each one it draws, and on Android 7+ how long each took. */
    static void watchFrames(Window w) {
        w.getDecorView().getViewTreeObserver().addOnDrawListener(DREW);
        if (Build.VERSION.SDK_INT >= 24) {
            try {
                w.addOnFrameMetricsAvailableListener(new FrameTimes(), mainHandler());
            } catch (RuntimeException | LinkageError ignored) {
                // a window Android won't time: the counts still come
            }
        }
    }

    /** An arrow pressed on home (main thread): a burst begins, or goes on. */
    static void arrow(long at) {
        synchronized (burst) {
            if (at - arrowAt > BURST_GAP_MS) burstAt = at;
            arrowAt = at;
        }
    }

    /** One frame's time from Android (main thread), kept if a D-pad burst is under way; a new burst lets the last one's go. */
    private static void frameTook(long nanos) {
        long now = SystemClock.uptimeMillis();
        synchronized (burst) {
            if (arrowAt == 0 || now - arrowAt > BURST_TAIL_MS) return;
            if (burstTimed != burstAt) {
                burstTimed = burstAt;
                burstN = 0;
            }
            burst[burstN++ % burst.length] = (int) Math.min(Integer.MAX_VALUE, nanos / 1000);
            burstEnd = now;
        }
    }

    @TargetApi(24)
    private static final class FrameTimes implements Window.OnFrameMetricsAvailableListener {
        @Override
        public void onFrameMetricsAvailable(Window w, FrameMetrics m, int dropped) {
            frameTook(m.getMetric(FrameMetrics.TOTAL_DURATION));
        }
    }

    /** How long after its last press a D-pad burst is over and all its frames are in. */
    static final long BURST_DONE_MS = BURST_GAP_MS + BURST_TAIL_MS;
    /** The last burst judged by {@link #slowBursts}, and how many judged slow so far this run. */
    private static long burstJudged;
    private static int slow;

    /**
     * D-pad bursts this run that were slow to draw, the last one judged now if it hasn't been: a
     * dozen frames timed at least, and the slowest tenth of them past 34 ms (two frames missed and
     * more). Main thread, once the remote has rested {@link #BURST_DONE_MS}.
     */
    static int slowBursts() {
        int[] t;
        synchronized (burst) {
            if (burstTimed == 0 || burstTimed == burstJudged) return slow;
            burstJudged = burstTimed;
            t = lastBurst();
        }
        if (t.length >= 12 && pct(t, 90) > 34_000) slow++;
        return slow;
    }

    /** The last burst's frame times, sorted (under the burst's lock). */
    private static int[] lastBurst() {
        int[] t = Arrays.copyOf(burst, Math.min(burstN, burst.length));
        Arrays.sort(t);
        return t;
    }

    /** For tests: a D-pad burst of {@code n} frames of {@code micros} each, as Android would time them; {@code fresh} forgets the count. */
    static void burstForTest(long at, int n, int micros, boolean fresh) {
        synchronized (burst) {
            if (fresh) slow = 0;
            burstAt = burstTimed = arrowAt = at;
            burstN = 0;
            for (int i = 0; i < n; i++) burst[burstN++ % burst.length] = micros;
        }
    }

    /** Home's frames for the report: how many while left alone, and (Android 7+) how long the last D-pad burst's took. */
    private static void frames(StringBuilder b) {
        long now = SystemClock.uptimeMillis(), rest = lastRest;
        line(b, "Frames at rest", rest < 0 ? "none measured yet: home hasn't been left alone for a whole minute"
                : rest + " in the last whole minute home was left alone, " + minutes(now - lastRestAt) + " ago");
        if (Build.VERSION.SDK_INT < 24) return;
        int[] t;
        long end;
        synchronized (burst) {
            t = lastBurst();
            end = burstEnd;
        }
        if (t.length == 0) return;
        line(b, "D-pad frames", "p50 " + ms(pct(t, 50)) + " ms, p90 " + ms(pct(t, 90)) + " ms over the last burst's "
                + t.length + " frames, " + minutes(now - end) + " ago");
    }

    /** The {@code p}th percentile of sorted values, by nearest rank. */
    private static int pct(int[] sorted, int p) {
        return sorted[Math.max(0, (sorted.length * p + 99) / 100 - 1)];
    }

    private static String ms(int micros) {
        return String.format(Locale.ROOT, "%.1f", micros / 1000f);
    }
}
