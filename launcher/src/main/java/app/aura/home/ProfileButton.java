package app.aura.home;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Listens for two things Android's key filter hides from {@link HomeKeys}:
 * <ul>
 *   <li><b>The remote's profile button</b>, on TVs whose firmware takes it below Android's key
 *       handling and opens Google TV's profile picker by name: Aura opens its own picker instead.</li>
 *   <li><b>Held volume buttons and background volume bounces</b>: on Android TV
 *       ({@code mUseTvRouting}), {@code KeyboardInterceptor} intercepts {@code KEYCODE_VOLUME_UP}
 *       and {@code KEYCODE_VOLUME_DOWN} inside {@code AccessibilityInputFilter} and routes them
 *       straight to {@code AudioService.handleVolumeKey}, returning {@code -1} before
 *       {@code InputDispatcher} can synthesize key repeats or call {@code onKeyEvent}. Watching
 *       {@code KeyEventManager}, {@code volume_changed} and {@code vol.Events} lets {@link HomeKeys}
 *       step held volume buttons smoothly and clamp stale background {@code setStreamVolume} bounces
 *       (such as Spotify TV echoing old levels during a ramp).</li>
 * </ul>
 * Needs {@code READ_LOGS}, granted over ADB at setup. One {@code logcat} process filtered in C++
 * to those four tags only, sleeping between them; stopped with the Home button service.
 */
final class ProfileButton {
    private static final String PICKER = "launcherx/.profile.chooser.ProfileChooserActivity";
    private static Process proc;
    private static long lastAt;
    private static boolean grantTried;

    private ProfileButton() { }

    static boolean granted(Context c) {
        return Compat.granted(c, "android.permission.READ_LOGS");
    }

    /** Starts listening if Aura may read the log (granting {@code READ_LOGS} over local ADB if needed). */
    static synchronized void start(Context ctx) {
        if (proc != null) return;
        // from Android 13 every read of the log asks the viewer first, in a system window over
        // whatever's on screen: the profile button and held volume keep to what the TV does there
        if (android.os.Build.VERSION.SDK_INT >= 33) return;
        final Context c = ctx.getApplicationContext();
        if (!granted(c)) {
            if (!grantTried && Adb.on(c)) {
                grantTried = true;
                new SafeThread(() -> {
                    Adb.run(c, "pm grant " + c.getPackageName() + " android.permission.READ_LOGS");
                    if (granted(c) && HomeKeys.live) start(c);
                }, "grant-logs").start();
            }
            return;
        }
        final Handler main = new Handler(Looper.getMainLooper());
        final Process p;
        try {
            // -T 1: from now on only; the tag filter keeps every other line out of this process
            p = new ProcessBuilder("logcat", "-b", "main", "-b", "events", "-v", "brief", "-T", "1",
                    "ActivityTaskManager:I", "KeyEventManager:D", "volume_changed:I", "vol.Events:I", "*:S")
                    .redirectErrorStream(true).start();
        } catch (Exception e) {
            return;
        }
        proc = p;
        Thread t = new SafeThread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                for (String line; (line = in.readLine()) != null; ) {
                    parseLine(c, main, line);
                }
            } catch (Exception ignored) {
                // stopped, or the log went away: the button just does what the firmware does
            }
            synchronized (ProfileButton.class) {
                if (proc == p) proc = null;
            }
        }, "profile-button");
        t.setDaemon(true);
        t.start();
    }

    static void parseLine(Context c, Handler main, String line) {
        if (line == null) return;
        if (line.contains("START u") && line.contains(PICKER)) {
            long now = SystemClock.uptimeMillis();
            // one press can try twice (Google TV re-asks for the account): one picker
            if (now - lastAt < 1500) return;
            lastAt = now;
            main.post(() -> open(c));
            return;
        }
        if (line.contains("KEY_EVENT_ACTION_DOWN: key=")) {
            int key = parseKeyCode(line, "KEY_EVENT_ACTION_DOWN: key=");
            if (key == KeyEvent.KEYCODE_VOLUME_UP || key == KeyEvent.KEYCODE_VOLUME_DOWN) {
                main.post(() -> HomeKeys.onVolumeKey(key, false, true));
            }
            return;
        }
        if (line.contains("KEY_EVENT_ACTION_UP: key=")) {
            int key = parseKeyCode(line, "KEY_EVENT_ACTION_UP: key=");
            if (key == KeyEvent.KEYCODE_VOLUME_UP || key == KeyEvent.KEYCODE_VOLUME_DOWN) {
                main.post(() -> HomeKeys.onVolumeKey(key, true, true));
            }
            return;
        }
        if (line.contains("volume_changed") && line.contains("[3,")) {
            int open = line.indexOf("[3,");
            int close = line.indexOf(']', open);
            if (open >= 0 && close > open) {
                String[] parts = line.substring(open + 1, close).split(",", 5);
                if (parts.length == 5) {
                    try {
                        int oldVal = Integer.parseInt(parts[1].trim());
                        int newVal = Integer.parseInt(parts[2].trim());
                        int maxVal = Integer.parseInt(parts[3].trim());
                        String caller = parts[4].trim();
                        main.post(() -> HomeKeys.onSystemVolumeChanged(oldVal, newVal, maxVal, caller));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            return;
        }
        if (line.contains("vol.Events")) {
            if (line.contains("writeEvent key STREAM_MUSIC")) {
                main.post(() -> HomeKeys.onSystemVolumeDialogEvent(true));
            } else if (line.contains("writeEvent show_dialog volume_changed")) {
                main.post(() -> HomeKeys.onSystemVolumeDialogEvent(false));
            }
        }
    }

    private static int parseKeyCode(String line, String marker) {
        int i = line.indexOf(marker);
        if (i < 0) return -1;
        String tail = line.substring(i + marker.length()).trim();
        int end = 0;
        while (end < tail.length() && Character.isDigit(tail.charAt(end))) end++;
        if (end == 0) return -1;
        try {
            return Integer.parseInt(tail.substring(0, end));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static synchronized void stop() {
        if (proc != null) proc.destroy();
        proc = null;
    }

    private static void open(Context c) {
        try {
            c.startActivity(new Intent(c, HomeActivity.class).putExtra("aura_profiles", "")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        } catch (RuntimeException ignored) {
        }
    }
}
