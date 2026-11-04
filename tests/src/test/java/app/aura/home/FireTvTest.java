package app.aura.home;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.provider.Settings;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowPackageManager;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** A Fire TV, as its launcher's manifest describes it: Settings goes to Amazon's own page, not the stand-in. */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 28, application = AuraApp.class, shadows = SaneMatrix.class)
public class FireTvTest {
    private static void activity(ShadowPackageManager pm, String pkg, String cls, String action, String category) throws Exception {
        ComponentName cn = new ComponentName(pkg, cls);
        pm.addActivityIfNotPresent(cn);
        IntentFilter f = new IntentFilter(action);
        f.addCategory(category);
        pm.addIntentFilterForActivity(cn, f);
    }

    @Test
    public void settingsOpenAmazonsOwnPage() throws Exception {
        Context c = RuntimeEnvironment.getApplication();
        ShadowPackageManager pm = shadowOf(c.getPackageManager());
        pm.setSystemFeature("amazon.hardware.fire_tv", true);
        // what Fire OS answers android.settings.SETTINGS with, and the launcher's real page
        activity(pm, "com.amazon.tv.settings.v2", "com.amazon.tv.settings.v2.compliance.CTSDummySettingsHandler",
                Settings.ACTION_SETTINGS, Intent.CATEGORY_DEFAULT);
        activity(pm, "com.amazon.tv.launcher", "com.amazon.tv.launcher.ui.MainSettingsActivity",
                "amazon.intent.action.MAINSETTINGS", Intent.CATEGORY_DEFAULT);
        Prefs.device(c).edit().putBoolean("welcomed", true).putString("set_light", "on").commit();
        org.robolectric.shadows.ShadowChoreographer.setPaused(true);
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(java.time.Duration.ofMillis(16));
        java.lang.reflect.Field w = HomeActivity.class.getDeclaredField("warm");
        w.setAccessible(true);
        w.setBoolean(null, true);
        ActivityController<HomeActivity> ac = Robolectric.buildActivity(HomeActivity.class).setup();
        HomeActivity a = ac.get();
        ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS);
        Method open = HomeActivity.class.getDeclaredMethod("open", Intent.class, android.view.View.class);
        open.invoke(a, new Intent(Settings.ACTION_SETTINGS), a.getWindow().getDecorView());
        Intent started = shadowOf(a).getNextStartedActivity();
        assertNotNull(started);
        assertEquals("com.amazon.tv.launcher.ui.MainSettingsActivity", started.getComponent().getClassName());
        // and a TV that isn't a Fire TV keeps Android's own link
        pm.setSystemFeature("amazon.hardware.fire_tv", false);
        open.invoke(a, new Intent(Settings.ACTION_SETTINGS), a.getWindow().getDecorView());
        Intent plain = shadowOf(a).getNextStartedActivity();
        assertEquals(Settings.ACTION_SETTINGS, plain.getAction());
        assertNull(plain.getComponent());
        ac.pause().stop().destroy();
    }

    @Test
    public void fireTvUpdateAndRemoteAndYoutubePaths() throws Exception {
        Context c = RuntimeEnvironment.getApplication();
        ShadowPackageManager pm = shadowOf(c.getPackageManager());
        pm.setSystemFeature("amazon.hardware.fire_tv", true);
        // Fire OS answers Developer Options with CTSDummySettingsHandler; Aura should open Amazon's MainSettingsActivity instead
        activity(pm, "com.amazon.tv.settings.v2", "com.amazon.tv.settings.v2.compliance.CTSDummySettingsHandler",
                Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, Intent.CATEGORY_DEFAULT);
        activity(pm, "com.amazon.tv.launcher", "com.amazon.tv.launcher.ui.MainSettingsActivity",
                "amazon.intent.action.MAINSETTINGS", Intent.CATEGORY_DEFAULT);
        // Fire TV YouTube package for Tonight's Pick trailers
        ComponentName ytCn = new ComponentName("com.amazon.firetv.youtube", "com.amazon.firetv.youtube.MainActivity");
        pm.addActivityIfNotPresent(ytCn);
        IntentFilter yf = new IntentFilter(Intent.ACTION_VIEW);
        yf.addCategory(Intent.CATEGORY_DEFAULT);
        yf.addDataScheme("https");
        yf.addDataAuthority("www.youtube.com", null);
        pm.addIntentFilterForActivity(ytCn, yf);

        org.json.JSONObject jo = new org.json.JSONObject();
        jo.put("name", "Severance");
        jo.put("yt", "xEQP4VVuyrY");
        jo.put("query", "Severance");
        Picks.Pick pick = new Picks.Pick(jo);
        Intent trailer = Picks.watch(c, pick);
        assertEquals("com.amazon.firetv.youtube", trailer.getPackage());

        // NowPlaying responds to Fire TV remote Play/Pause, Rewind, and Fast-Forward keys
        Backdrop b = new Backdrop(c);
        NowPlaying np = new NowPlaying(c, b);
        assertTrue(np.key( android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
        assertTrue(np.key(android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD));
        assertTrue(np.key(android.view.KeyEvent.KEYCODE_MEDIA_REWIND));
        assertFalse(np.key(android.view.KeyEvent.KEYCODE_DPAD_UP));
        b.release();
    }
}
