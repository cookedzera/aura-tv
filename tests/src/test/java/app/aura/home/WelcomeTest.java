package app.aura.home;

import android.content.Context;
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

/** A fresh install: the welcome, walked with OK, Right and Down until it's done (or 120 presses). */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = {25, 30}, application = AuraApp.class, shadows = SaneMatrix.class)
public class WelcomeTest {
    private static void run(long ms) {
        for (long t = 0; t < ms; t += 100) ShadowLooper.idleMainLooper(100, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private static void key(HomeActivity a, int code) {
        a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
        a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
        run(350);
    }

    @Test
    public void freshInstallWalksThrough() throws Exception {
        Context c = RuntimeEnvironment.getApplication();
        Prefs.device(c).edit().putString("set_light", "on").commit();
        org.robolectric.shadows.ShadowChoreographer.setPaused(true);
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(Duration.ofMillis(16));
        java.lang.reflect.Field w = HomeActivity.class.getDeclaredField("warm");
        w.setAccessible(true);
        w.setBoolean(null, false);
        Trouble.clear(c);
        ActivityController<HomeActivity> ac = Robolectric.buildActivity(HomeActivity.class).setup();
        HomeActivity a = ac.get();
        // the boot screen's longest wait, then the welcome
        for (int i = 0; i < 80; i++) {
            run(100);
            Thread.sleep(50);
        }
        java.lang.reflect.Field wf = HomeActivity.class.getDeclaredField("welcome");
        wf.setAccessible(true);
        StringBuilder log = new StringBuilder("welcome shown: " + (wf.get(a) != null) + "\n");
        int presses = 0;
        int[] walk = {KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_DPAD_DOWN};
        while (wf.get(a) != null && presses < 120) {
            key(a, walk[presses % walk.length]);
            presses++;
        }
        log.append("presses: ").append(presses).append(", welcome still open: ").append(wf.get(a) != null).append('\n');
        // and back out of whatever is left
        for (int i = 0; i < 4; i++) key(a, KeyEvent.KEYCODE_BACK);
        ac.pause().stop().destroy();
        log.append(Trouble.report(c));
        try (java.io.FileWriter fw = new java.io.FileWriter("target/welcome-" + android.os.Build.VERSION.SDK_INT + ".txt")) {
            fw.write(log.toString());
        }
    }
}
