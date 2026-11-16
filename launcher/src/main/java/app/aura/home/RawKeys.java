package app.aura.home;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The remote's own presses and releases, read with {@code getevent} over Aura's link to the TV's
 * own ADB, for one job: a volume button held inside an app. With the Home button service's key
 * filter on (an app in front), Android TV sends volume keys straight to the volume and repeats
 * none of them, so a held button moved the volume one step. {@link HomeKeys} steps it while it's
 * held, but must be told when it's pressed and let go: some firmware logs that (see
 * {@link ProfileButton}), many (MediaTek) log nothing at all. The remote's receiver always tells.
 * Which of its buttons are volume isn't written anywhere an app may read: a button is learned as
 * volume up or down when the volume moves just after it goes down. Only while the filter is on;
 * without ADB, nothing changes.
 */
final class RawKeys {
    private RawKeys() { }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Thread reader;
    private static Adb.Link link;
    private static volatile boolean wanted;
    private static Learner learner;

    /** Reading while {@code on} (the key filter is on: an app in front), not otherwise. Main thread. */
    static synchronized void watch(Context c, boolean on) {
        wanted = on;
        if (!on) {
            Adb.Link l = link;
            link = null;
            // closing the link ends the reader's wait for the next line
            if (l != null) l.close();
            return;
        }
        if (reader != null && reader.isAlive() || !Adb.on(c)) return;
        final Context app = c.getApplicationContext();
        if (learner == null) learner = new Learner(audio(app), HomeKeys::onRawVolumeKey, MAIN);
        reader = new SafeThread(() -> read(app), "raw-keys");
        reader.setDaemon(true);
        reader.start();
    }

    /** One connection after another while wanted: a dropped one comes back, a little later each time it drops quickly. */
    private static void read(Context c) {
        long wait = 1000;
        while (wanted) {
            Adb.Link l = Adb.quietLink(c);
            // ADB isn't letting Aura in: held volume stays as it was
            if (l == null) return;
            synchronized (RawKeys.class) {
                if (!wanted) {
                    l.close();
                    return;
                }
                link = l;
            }
            long began = SystemClock.elapsedRealtime();
            try {
                l.stream("getevent -lq", line -> {
                    Key k = parse(line);
                    if (k != null) MAIN.post(() -> learner.key(k.name, k.down));
                    return wanted;
                });
            } catch (IOException | RuntimeException ignored) {
                // closed by watch(false), or adbd went away
            } finally {
                l.close();
                synchronized (RawKeys.class) {
                    if (link == l) link = null;
                }
            }
            if (!wanted) return;
            if (SystemClock.elapsedRealtime() - began > 60_000) wait = 1000;
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
        if (line == null) return null;
        String[] t = line.trim().split("\\s+");
        if (t.length < 4 || !"EV_KEY".equals(t[1])) return null;
        boolean down = "DOWN".equals(t[3]);
        if (!down && !"UP".equals(t[3])) return null;
        return new Key(t[0] + t[2], down);
    }

    interface Audio {
        int level();

        int max();
    }

    interface Out {
        void volume(int keyCode, boolean up);
    }

    private static Audio audio(Context c) {
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        return new Audio() {
            @Override
            public int level() {
                return am == null ? 0 : am.getStreamVolume(AudioManager.STREAM_MUSIC);
            }

            @Override
            public int max() {
                return am == null ? 0 : am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            }
        };
    }

    /**
     * Which buttons are volume, learned as they're pressed, and their presses and releases passed
     * on. Main thread. A button that moved nothing is let be for a while (asked again later: the
     * volume may simply have been at its end), so arrows and OK aren't looked at on every press.
     */
    static final class Learner {
        /** How soon after a press Android has stepped the volume itself. */
        static final long LEARN_MS = 250;
        static final long RELEARN_MS = 10 * 60_000L;
        private final Audio audio;
        private final Out out;
        private final Handler handler;
        private final Map<String, Integer> dir = new HashMap<>();
        private final Map<String, Long> notVolumeAt = new HashMap<>();
        private final Set<String> held = new HashSet<>();

        Learner(Audio audio, Out out, Handler handler) {
            this.audio = audio;
            this.out = out;
            this.handler = handler;
        }

        void key(String name, boolean down) {
            Integer d = dir.get(name);
            if (!down) {
                held.remove(name);
                if (d != null) out.volume(code(d), true);
                return;
            }
            held.add(name);
            if (d != null) {
                out.volume(code(d), false);
                return;
            }
            long now = SystemClock.uptimeMillis();
            Long no = notVolumeAt.get(name);
            if (no != null && now - no < RELEARN_MS) return;
            final int before = audio.level();
            handler.postDelayed(() -> {
                int after = audio.level();
                if (after == before) {
                    // at either end the volume can't move: that says nothing about the button
                    if (before > 0 && before < audio.max()) notVolumeAt.put(name, SystemClock.uptimeMillis());
                    return;
                }
                int learned = after > before ? 1 : -1;
                dir.put(name, learned);
                // still held: the hold is stepped from here
                if (held.contains(name)) out.volume(code(learned), false);
            }, LEARN_MS);
        }

        private static int code(int d) {
            return d > 0 ? KeyEvent.KEYCODE_VOLUME_UP : KeyEvent.KEYCODE_VOLUME_DOWN;
        }
    }
}
