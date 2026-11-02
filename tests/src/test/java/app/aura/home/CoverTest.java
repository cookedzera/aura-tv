package app.aura.home;

import android.content.Context;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowLooper;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** Fire OS 6/7: the cover over Android's five-second hold after Home, and that it always goes again. */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = {25, 30}, application = AuraApp.class, shadows = SaneMatrix.class)
public class CoverTest {
    private static List<View> views(Context c) {
        WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        org.robolectric.shadows.ShadowWindowManagerImpl s = Shadow.extract(wm);
        return s.getViews();
    }

    @Test
    public void coversTheWaitAndAlwaysGoes() {
        HomeKeys svc = Robolectric.buildService(HomeKeys.class).create().get();
        int before = views(svc).size();
        HomeCover.show(svc);
        ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS);
        if (!HomeCover.needed()) {
            // Android 10 and later: nothing to cover
            assertEquals(before, views(svc).size());
            return;
        }
        List<View> now = views(svc);
        assertEquals(before + 1, now.size());
        View cover = now.get(now.size() - 1);
        // a key meant for Amazon's home underneath is held back
        assertTrue(cover.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)));
        // Back lets it go
        cover.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK));
        ShadowLooper.idleMainLooper(500, TimeUnit.MILLISECONDS);
        assertEquals(before, views(svc).size());
        // and on its own after seven seconds, should home never come
        HomeCover.show(svc);
        ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS);
        assertEquals(before + 1, views(svc).size());
        ShadowLooper.idleMainLooper(7500, TimeUnit.MILLISECONDS);
        assertEquals(before, views(svc).size());
    }
}
