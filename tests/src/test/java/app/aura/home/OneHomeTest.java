package app.aura.home;

import android.content.Context;
import android.content.Intent;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/**
 * There is one home screen. Anything that brings it forward with something to show (the switcher
 * or Control Center over an app, a remote button's picker, a notification, the TV's own panel
 * buttons) asks for it as Home where Aura is the home app: named directly, Android 10 and later
 * built a second copy each time, and on a TV a hundred and more of them piled up in a day.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = AuraApp.class)
public class OneHomeTest {
    @Test
    public void homeIsAskedForAsHome() {
        Context c = RuntimeEnvironment.getApplication();
        Intent i = HomeActivity.intentFor(c);
        assertTrue("from a service or a notification it needs a task of its own to land in",
                (i.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
        if (HomeRole.held(c)) {
            assertEquals(Intent.ACTION_MAIN, i.getAction());
            assertTrue(i.hasCategory(Intent.CATEGORY_HOME));
            assertNull("named directly, Android doesn't count it as the home screen", i.getComponent());
            assertEquals(c.getPackageName(), i.getPackage());
        } else {
            assertEquals(HomeActivity.class.getName(), i.getComponent().getClassName());
        }
    }

    @Test
    public void theTvsPanelButtonGoesToTheOneHome() {
        Intent asked = new Intent("android.app.action.TOGGLE_NOTIFICATION_HANDLER_PANEL");
        PanelBridge b = Robolectric.buildActivity(PanelBridge.class, asked).create().get();
        Intent sent = shadowOf(b).getNextStartedActivity();
        assertNotNull("handed on", sent);
        assertEquals("its action carried along", "android.app.action.TOGGLE_NOTIFICATION_HANDLER_PANEL", sent.getStringExtra("aura_panel"));
        assertTrue("and gone at once", b.isFinishing());
        if (HomeRole.held(b)) assertTrue(sent.hasCategory(Intent.CATEGORY_HOME));
    }
}
