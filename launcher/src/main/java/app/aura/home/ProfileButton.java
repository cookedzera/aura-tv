package app.aura.home;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * The remote's profile button, on TVs whose firmware takes it. Realtek's key service (on this
 * Dixon/Acer Google TV) hooks the button below Android's key handling, ahead of any app or
 * accessibility service, and opens Google TV's profile picker by name; with Google TV's home
 * switched off that picker is gone and the service fails silently. Either way Android logs the
 * attempt the moment it's made, so Aura listens for that one line and opens its own picker: with
 * Google TV's home off, nothing of Google's ever shows.
 *
 * Needs READ_LOGS, granted over ADB at setup. One logcat process, filtered by Android to the
 * activity manager's lines only, sleeping between them; stopped with the Home button service.
 */
final class ProfileButton {
    private static final String PICKER = "launcherx/.profile.chooser.ProfileChooserActivity";
    private static Process proc;
    private static long lastAt;

    private ProfileButton() { }

    static boolean granted(Context c) {
        return Compat.granted(c, "android.permission.READ_LOGS");
    }

    /** Starts listening, if this TV has Google TV's picker to listen for and Aura may read the log. */
    static synchronized void start(Context ctx) {
        if (proc != null || !granted(ctx) || !Safety.hasGoogleTvHome(ctx)) return;
        final Context c = ctx.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        final Process p;
        try {
            // -T 1: from now on only; the tag filter keeps every other line out of this process
            p = new ProcessBuilder("logcat", "-v", "brief", "-T", "1", "ActivityTaskManager:I", "*:S")
                    .redirectErrorStream(true).start();
        } catch (Exception e) {
            return;
        }
        proc = p;
        Thread t = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                for (String line; (line = in.readLine()) != null; ) {
                    if (!line.contains("START u") || !line.contains(PICKER)) continue;
                    long now = SystemClock.uptimeMillis();
                    // one press can try twice (Google TV re-asks for the account): one picker
                    if (now - lastAt < 1500) continue;
                    lastAt = now;
                    main.post(() -> open(c));
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
