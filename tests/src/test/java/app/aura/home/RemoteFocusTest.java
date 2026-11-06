package app.aura.home;

import android.content.Context;
import android.view.WindowManager;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;

import java.util.concurrent.TimeUnit;

/**
 * The remote never goes dead on home. On Android 11 a window learns it has focus from a message
 * that Android throws away if an accessibility service changes its key filter at that moment: the
 * Home button service waits for the window in front to settle first, and home that ends up on
 * screen without focus asks for it again.
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30, application = AuraApp.class)
public class RemoteFocusTest {
    private static void run(long ms) {
        for (long t = 0; t < ms; t += 50) ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS);
    }

    private static boolean stepped(HomeActivity a) {
        return (a.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0;
    }

    @Test
    public void theKeyFilterWaitsForTheWindowToSettle() {
        Assert.assertEquals("no window seen yet: at once", 0, HomeKeys.settleLeft(5_000, 0));
        Assert.assertEquals("a window that just came up: the rest of the wait", 600, HomeKeys.settleLeft(5_100, 5_000));
        Assert.assertEquals("settled", 0, HomeKeys.settleLeft(5_700, 5_000));
        Assert.assertEquals(0, HomeKeys.settleLeft(9_000, 5_000));
    }

    @Test
    public void homeWithoutFocusAsksForItAgain() throws Exception {
        Context c = RuntimeEnvironment.getApplication();
        Prefs.device(c).edit().putBoolean("welcomed", true).putString("set_light", "on").commit();
        java.lang.reflect.Field warm = HomeActivity.class.getDeclaredField("warm");
        warm.setAccessible(true);
        warm.setBoolean(null, true);
        ActivityController<HomeActivity> ac = Robolectric.buildActivity(HomeActivity.class).setup();
        HomeActivity a = ac.get();
        ac.windowFocusChanged(true);
        run(3_000);
        Assert.assertFalse("with focus, nothing is touched", stepped(a));

        // back from an app, and the message saying "you have focus" never came
        ac.pause();
        ac.windowFocusChanged(false);
        ac.resume();
        Trouble.clear(c);
        StringBuilder seen = new StringBuilder();
        long out = -1, back = -1;
        for (long t = 50; t <= 2_000; t += 50) {
            run(50);
            boolean s = stepped(a);
            seen.append(t).append(s ? "S " : ". ");
            if (s && out < 0) out = t;
            if (!s && out >= 0 && back < 0) back = t;
        }
        Assert.assertFalse("no focus all along", a.hasWindowFocus());
        Assert.assertTrue("home lets the key filter settle first, then steps out of focus: " + seen, out >= 700 && out <= 1_600);
        Assert.assertTrue("and straight back, so Android tells it again: " + seen, back > out && back - out <= 250);

        ac.windowFocusChanged(true);
        run(2_600);
        Assert.assertTrue("the repair is noted for Problem reports:\n" + Trouble.report(c), Trouble.report(c).contains("FOCUS"));

        // a system panel holding focus by right: asked twice, then left alone
        ac.pause();
        ac.windowFocusChanged(false);
        ac.resume();
        Trouble.clear(c);
        run(10_000);
        Assert.assertFalse(stepped(a));
        Assert.assertEquals("nothing noted when focus didn't come back", 0, Trouble.count(c));
        ac.pause().stop().destroy();
    }
}
