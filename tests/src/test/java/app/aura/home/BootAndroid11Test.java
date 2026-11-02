package app.aura.home;

import android.os.Looper;
import android.view.KeyEvent;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;

import java.time.Duration;

/** Starts the real home screen, lets it run, and walks the main screens with the remote's keys. */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30, application = AuraApp.class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
public class BootAndroid11Test {
    private static void run(long ms) {
        for (long t = 0; t < ms; t += 100) {
            ShadowLooper.idleMainLooper(100, java.util.concurrent.TimeUnit.MILLISECONDS);
            try { Thread.sleep(2); } catch (InterruptedException ignored) { }
        }
    }

    private static void key(HomeActivity a, int code) {
        a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
        a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
        run(400);
    }

    private static void log(String what) {
        try (java.io.FileWriter w = new java.io.FileWriter("target/progress-" + android.os.Build.VERSION.SDK_INT + ".txt", true)) {
            w.write(System.currentTimeMillis() + " " + what + "\n");
        } catch (java.io.IOException ignored) {
        }
    }

    @Test
    public void startsAndWalks() {
        android.content.Context c = RuntimeEnvironment.getApplication();
        // a TV Aura already welcomed, in Light mode: the photograph's drift doesn't redraw every frame
        Prefs.device(c).edit().putBoolean("welcomed", true).putString("set_light", "on").commit();
        // frames at a TV's 60 Hz on the simulated clock, not back to back
        org.robolectric.shadows.ShadowChoreographer.setPaused(true);
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(Duration.ofMillis(16));
        log("start");
        // a fresh process each time: the boot screen's "already shown" and the app list start empty
        try {
            java.lang.reflect.Field f = HomeActivity.class.getDeclaredField("warm");
            f.setAccessible(true);
            f.setBoolean(null, false);
            java.lang.reflect.Field k = HomeActivity.class.getDeclaredField("cache");
            k.setAccessible(true);
            k.set(null, null);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        ActivityController<HomeActivity> ac = Robolectric.buildActivity(HomeActivity.class).setup();
        HomeActivity a = ac.get();
        log("created");
        run(4_000);
        log("idled");
        // around the home screen
        for (int code : new int[]{KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_LEFT}) key(a, code);
        // Control Center, then back
        log("walked");
        key(a, KeyEvent.KEYCODE_MENU);
        run(1500);
        key(a, KeyEvent.KEYCODE_BACK);
        // leave and come back, as after watching something
        log("control center");
        ac.pause().stop();
        run(1000);
        ac.restart().start().resume();
        run(4000);
        log("back");
        ac.pause().stop().destroy();
        run(500);
        log("destroyed");
        String report = Trouble.report(RuntimeEnvironment.getApplication());
        try (java.io.FileWriter w = new java.io.FileWriter("target/report-" + android.os.Build.VERSION.SDK_INT + ".txt")) {
            w.write(report);
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void quickReturnAfterBackgroundClose() {
        android.content.Context c = RuntimeEnvironment.getApplication();
        Prefs.device(c).edit().putBoolean("welcomed", true).putString("set_light", "on").commit();
        org.robolectric.shadows.ShadowChoreographer.setPaused(true);
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(Duration.ofMillis(16));
        // the TV has been on a while: this start is Aura coming back after Android closed it
        org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofMinutes(6));
        // a fresh process each time: the boot screen's "already shown" and the app list start empty
        try {
            java.lang.reflect.Field f = HomeActivity.class.getDeclaredField("warm");
            f.setAccessible(true);
            f.setBoolean(null, false);
            java.lang.reflect.Field k = HomeActivity.class.getDeclaredField("cache");
            k.setAccessible(true);
            k.set(null, null);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        ActivityController<HomeActivity> ac = Robolectric.buildActivity(HomeActivity.class).setup();
        run(4_000);
        ac.pause().stop().destroy();
        String report = Trouble.report(c);
        try (java.io.FileWriter w = new java.io.FileWriter("target/report-quick.txt")) {
            w.write(report);
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }
}
