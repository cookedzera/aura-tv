package app.aura.home;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The remote's own presses and releases, read with {@code getevent} over Aura's link to the TV's
 * own ADB, for one job: a volume button held inside an app. With the Home button service's key
 * filter on (an app in front), Android TV sends volume keys straight to the volume and repeats
 * none of them (AOSP's KeyboardInterceptor and PhoneWindowManager.dispatchDirectAudioEvent), so a
 * held button moved the volume one step. {@link HomeKeys} steps it while it's held, but must be
 * told when it's pressed and let go: some firmware logs that (see {@link ProfileButton}), many
 * (MediaTek) log nothing at all. The remote's receiver always tells. A button the driver names a
 * volume key is one; any other (KEY_A on a MediaTek remote) is learned as volume up or down when
 * the volume moves just after it goes down. Only while the filter is on (and a little after, so a
 * quick trip home doesn't reconnect); without ADB, nothing changes.
 */
final class RawKeys {
    private RawKeys() { }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** Kept open this long after the filter goes off: home and back to the app reuse the link. */
    static final long LINGER_MS = 20_000;
    /** Key lines only, filtered on the TV (no motion from an air mouse sent over); an older grep falls back to all. */
    static final String COMMAND = "getevent -lq | grep --line-buffered EV_KEY || getevent -lq";
    private static final Pattern SPACE = Pattern.compile("\\s+");

    private static Thread reader;
    private static Adb.Link link;
    private static volatile boolean wanted;
    private static Learner learner;
    private static final Runnable STOP = RawKeys::stop;

    /** Reading while {@code on} (the key filter is on: an app in front), not otherwise. Main thread. */
    static synchronized void watch(Context c, boolean on) {
        if (!on) {
            if (wanted) MAIN.postDelayed(STOP, LINGER_MS);
            return;
        }
        MAIN.removeCallbacks(STOP);
        wanted = true;
        if (reader != null && reader.isAlive() || !Adb.on(c)) return;
        final Context app = c.getApplicationContext();
        if (learner == null) learner = new Learner(app, HomeKeys::onRawVolumeKey);
        reader = new SafeThread(() -> read(app), "raw-keys");
        reader.setDaemon(true);
        reader.start();
    }

    private static synchronized void stop() {
        wanted = false;
        Adb.Link l = link;
        link = null;
        // closing the link ends the reader's wait for the next line
        if (l != null) l.close();
    }

    /**
     * One connection after another while wanted: one that drops, or ADB not letting Aura in, is
     * tried again a little later each time (up to half a minute), not on every window that opens.
     */
    private static void read(Context c) {
        long wait = 1000;
        while (wanted) {
            Adb.Link l = Adb.quietLink(c);
            boolean heard = false;
            if (l != null) {
                synchronized (RawKeys.class) {
                    if (!wanted) {
                        l.close();
                        return;
                    }
                    link = l;
                }
                final boolean[] got = {false};
                try {
                    l.stream(COMMAND, line -> {
                        got[0] = true;
                        Key k = parse(line);
                        if (k != null && learner.wants(k.name)) MAIN.post(() -> learner.key(k.name, k.down));
                        return true;
                    });
                } catch (IOException | RuntimeException ignored) {
                    // closed by stop(), or adbd went away
                } finally {
                    l.close();
                    synchronized (RawKeys.class) {
                        if (link == l) link = null;
                    }
                }
                heard = got[0];
            }
            if (!wanted) return;
            // a link that carried presses was a good one: the next try comes soon
            if (heard) wait = 1000;
            SystemClock.sleep(wait);
            wait = Math.min(wait * 2, 30_000);
        }
    }

    /** A button and whether it went down (else up). */
    static final class Key {
        final String name;
        final boolean down;

        Key(String name, boolean down) {
            this.name = name;
            this.down = down;
        }
    }

    /**
     * One line of {@code getevent -lq}: {@code /dev/input/event0: EV_KEY  KEY_A  DOWN}. A button is
     * its device and its code (a keyboard's A isn't a remote's volume); repeats are left out.
     */
    static Key parse(String line) {
        if (line == null || line.indexOf("EV_KEY") < 0) return null;
        String[] t = SPACE.split(line.trim());
        if (t.length < 4 || !"EV_KEY".equals(t[1])) return null;
        boolean down = "DOWN".equals(t[3]);
        if (!down && !"UP".equals(t[3])) return null;
        return new Key(t[0] + t[2], down);
    }

    interface Out {
        void volume(int keyCode, boolean up);
    }

    /**
     * Which buttons are volume, and their presses and releases passed on. Main thread, apart from
     * {@link #wants}. A button the driver names a volume key is one at once; the arrows, OK and
     * the like never are; any other is learned from the volume moving just after it goes down. One
     * that moved nothing is let be for a while (asked again later: the volume may have been at its
     * end), so most presses aren't even passed to the main thread.
     */
    static final class Learner {
        /** How soon after a press Android has stepped the volume itself. */
        static final long LEARN_MS = 250;
        static final long RELEARN_MS = 10 * 60_000L;
        /** Buttons that are never volume, whatever moves the volume as they're pressed. */
        private static final Set<String> NEVER = new HashSet<>(Arrays.asList("KEY_UP", "KEY_DOWN", "KEY_LEFT",
                "KEY_RIGHT", "KEY_ENTER", "KEY_OK", "KEY_SELECT", "KEY_BACK", "KEY_HOME", "KEY_ESC", "KEY_MENU"));
        private final AudioManager audio;
        private final Out out;
        private final Map<String, Integer> dir = new HashMap<>();
        /** Buttons not worth passing on now: read by the reader thread, written here. */
        private final Set<String> skip = Collections.newSetFromMap(new ConcurrentHashMap<>());
        private final Set<String> held = new HashSet<>();

        Learner(Context c, Out out) {
            this.audio = Compat.svc(c, AudioManager.class);
            this.out = out;
        }

        /** Whether a press of {@code name} could matter (any thread). */
        boolean wants(String name) {
            return !skip.contains(name);
        }

        void key(String name, boolean down) {
            Integer d = dir.get(name);
            if (!down) {
                held.remove(name);
                if (d != null) out.volume(code(d), true);
                return;
            }
            held.add(name);
            if (d == null) d = named(name);
            if (d != null) {
                dir.put(name, d);
                out.volume(code(d), false);
                return;
            }
            if (never(name)) {
                skip.add(name);
                return;
            }
            final int before = level();
            MAIN.postDelayed(() -> {
                int after = level();
                if (after == before) {
                    // at either end the volume can't move: that says nothing about the button
                    if (before > 0 && audio != null && before < audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)) {
                        skip.add(name);
                        MAIN.postDelayed(() -> skip.remove(name), RELEARN_MS);
                    }
                    return;
                }
                int learned = after > before ? 1 : -1;
                dir.put(name, learned);
                // still held: the hold is stepped from here
                if (held.contains(name)) out.volume(code(learned), false);
            }, LEARN_MS);
        }

        private int level() {
            return audio == null ? 0 : audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        }

        /** +1 or -1 for a button its driver names a volume key, else null. */
        private static Integer named(String name) {
            if (name.endsWith("KEY_VOLUMEUP")) return 1;
            if (name.endsWith("KEY_VOLUMEDOWN")) return -1;
            return null;
        }

        private static boolean never(String name) {
            int at = name.lastIndexOf(':');
            return NEVER.contains(at >= 0 ? name.substring(at + 1) : name);
        }

        private static int code(int d) {
            return d > 0 ? KeyEvent.KEYCODE_VOLUME_UP : KeyEvent.KEYCODE_VOLUME_DOWN;
        }
    }
}
