package app.aura.home;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.view.KeyEvent;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowPackageManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.robolectric.Shadows.shadowOf;

/**
 * What a Fire TV Stick 4K owner reported crashing Aura 7.6: starting it, scrolling through home,
 * going to Settings. Fire OS 6 (Android 7.1), with a TV's worth of apps, in both styles: every
 * step done, every Settings page opened, and nothing noted going wrong.
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 25, application = AuraApp.class, shadows = SaneMatrix.class, qualifiers = "w960dp-h540dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class FireStickTest {
    private static void run(long ms) {
        for (long t = 0; t < ms; t += 50) ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS);
    }

    private static void press(HomeActivity a, int code, int times) {
        for (int i = 0; i < times; i++) {
            a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
            a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
            run(120);
        }
    }

    private static Object call(Object o, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = o.getClass().getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(o, args);
    }

    private static void day(String layout) throws Exception {
        Context c = RuntimeEnvironment.getApplication();
        ShadowPackageManager pm = shadowOf(c.getPackageManager());
        pm.setSystemFeature("amazon.hardware.fire_tv", true);
        String[] pkgs = {"com.netflix.ninja", "com.amazon.avod", "com.google.android.youtube.tv", "com.spotify.tv.android",
                "com.disney.disneyplus", "in.startv.hotstar", "com.sonyliv", "com.jio.media.ondemand", "com.plexapp.android",
                "org.xbmc.kodi", "com.amazon.tv.launcher", "com.amazon.venezia", "com.amazon.tv.settings.v2", "tv.twitch.android.app",
                "com.zee5.android", "com.mxtech.videoplayer.ad"};
        for (String p : pkgs) {
            ComponentName cn = new ComponentName(p, p + ".Main");
            pm.addActivityIfNotPresent(cn);
            IntentFilter f = new IntentFilter(Intent.ACTION_MAIN);
            f.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
            pm.addIntentFilterForActivity(cn, f);
        }
        Prefs.device(c).edit().putBoolean("welcomed", true).putString("set_light", "on").commit();
        Prefs.of(c).edit().putString(Prefs.LAYOUT, layout).commit();
        org.robolectric.shadows.ShadowChoreographer.setPaused(true);
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(Duration.ofMillis(16));
        Field w = HomeActivity.class.getDeclaredField("warm");
        w.setAccessible(true);
        w.setBoolean(null, false);
        Field cache = HomeActivity.class.getDeclaredField("cache");
        cache.setAccessible(true);
        cache.set(null, null);
        Trouble.clear(c);

        // started from scratch, boot screen and all
        ActivityController<HomeActivity> ac = Robolectric.buildActivity(HomeActivity.class).setup();
        HomeActivity a = ac.get();
        for (int i = 0; i < 160; i++) {
            run(50);
            Thread.sleep(10);
        }
        // scrolling through home: every row, side to side, up to the widgets and the top
        press(a, KeyEvent.KEYCODE_DPAD_DOWN, 6);
        press(a, KeyEvent.KEYCODE_DPAD_RIGHT, 8);
        press(a, KeyEvent.KEYCODE_DPAD_LEFT, 10);
        press(a, KeyEvent.KEYCODE_DPAD_UP, 8);
        press(a, KeyEvent.KEYCODE_CHANNEL_DOWN, 2);
        press(a, KeyEvent.KEYCODE_CHANNEL_UP, 2);
        press(a, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, 1);
        press(a, KeyEvent.KEYCODE_DPAD_DOWN, 2);
        // Settings: the main page and every page it has
        Object settings;
        Field sf = HomeActivity.class.getDeclaredField("settings");
        sf.setAccessible(true);
        settings = sf.get(a);
        for (String page : new String[]{"", "look", "wallpaper", "widgets", "apps", "home", "keys", "profiles", "updates",
                "about", "phone", "pair", "weather", "screensaver", "privacy"}) {
            call(a, "openSettings", new Class<?>[]{String.class}, page);
            run(700);
            press(a, KeyEvent.KEYCODE_DPAD_DOWN, 4);
            press(a, KeyEvent.KEYCODE_BACK, 1);
            run(400);
        }
        call(settings, "openTrouble", new Class<?>[]{});
        run(500);
        press(a, KeyEvent.KEYCODE_BACK, 3);
        // Control Center (Menu), around it, and away
        press(a, KeyEvent.KEYCODE_MENU, 1);
        run(800);
        press(a, KeyEvent.KEYCODE_DPAD_RIGHT, 3);
        press(a, KeyEvent.KEYCODE_DPAD_DOWN, 3);
        press(a, KeyEvent.KEYCODE_BACK, 1);
        run(600);
        // Home twice: recent apps, and back
        a.onNewIntent(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME));
        a.onNewIntent(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME));
        run(1500);
        press(a, KeyEvent.KEYCODE_BACK, 1);
        run(800);
        // behind an app and back, as after watching something
        ac.pause().stop();
        run(500);
        ac.restart().start().resume();
        run(2000);
        press(a, KeyEvent.KEYCODE_DPAD_RIGHT, 4);
        String report = Trouble.report(c);
        ac.pause().stop().destroy();
        run(300);
        try (java.io.FileWriter f = new java.io.FileWriter("target/firestick-" + layout + ".txt")) {
            f.write(report);
        }
        Assert.assertEquals("nothing went wrong (" + layout + "):\n" + report, 0, Trouble.count(c));
    }

    @Test
    public void tvosStyle() throws Exception {
        day("tvos");
    }

    @Test
    public void auraStyle() throws Exception {
        day("aura");
    }
}
