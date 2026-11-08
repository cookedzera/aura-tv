package app.aura.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Settings as it looks, on HIGH and on LOW, its main page and the Home screen page, a row in focus:
 * each opens and is drawn to tests/target/settings/ for the eye.
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30, application = AuraApp.class, qualifiers = "w960dp-h540dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SettingsLookTest {
    private static void run(long ms) {
        for (long t = 0; t < ms; t += 16) ShadowLooper.idleMainLooper(16, TimeUnit.MILLISECONDS);
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object o, String name) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(o);
    }

    private static void call(Object o, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = o.getClass().getDeclaredMethod(name, types);
        m.setAccessible(true);
        m.invoke(o, args);
    }

    private static void shot(HomeActivity a, String name) throws Exception {
        View root = a.getWindow().getDecorView();
        Bitmap b = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(b));
        new File("target/settings").mkdirs();
        try (FileOutputStream f = new FileOutputStream("target/settings/" + name + ".png")) {
            b.compress(Bitmap.CompressFormat.PNG, 100, f);
        }
    }

    private void look(String light, String tier) throws Exception {
        Context c = RuntimeEnvironment.getApplication();
        Prefs.of(c).edit().clear().putString(Prefs.LAYOUT, "tvos").commit();
        Prefs.device(c).edit().putBoolean("welcomed", true).putString("set_light", light).commit();
        org.robolectric.shadows.ShadowChoreographer.setPaused(true);
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(Duration.ofMillis(16));
        Field w = HomeActivity.class.getDeclaredField("warm");
        w.setAccessible(true);
        w.setBoolean(null, true);
        HomeActivity a = Robolectric.buildActivity(HomeActivity.class).setup().get();
        run(1500);
        call(a, "openSettingsNow", new Class<?>[]{String.class}, (Object) null);
        run(900);
        SettingsPanel s = field(a, "settings");
        Assert.assertTrue(s.isOpen());
        android.widget.LinearLayout rows = field(s, "rows");
        // the second row in focus, as a remote would leave it
        for (int i = 0, seen = 0; i < rows.getChildCount(); i++) {
            View r = rows.getChildAt(i);
            if (!r.isFocusable()) continue;
            if (++seen == 2) {
                r.setFocusableInTouchMode(true);
                r.requestFocus();
                break;
            }
        }
        run(700);
        shot(a, "main-" + tier);
        // the first row (Set up on this TV) in focus
        for (int i = 0; i < rows.getChildCount(); i++) {
            View r = rows.getChildAt(i);
            if (!r.isFocusable()) continue;
            r.setFocusableInTouchMode(true);
            r.requestFocus();
            break;
        }
        run(700);
        shot(a, "main-first-" + tier);
        call(s, "go", new Class<?>[]{String.class}, "home");
        run(900);
        shot(a, "home-" + tier);
    }

    @Test
    public void high() throws Exception {
        look("off", "high");
    }

    @Test
    public void low() throws Exception {
        look("on", "low");
    }

    /** Control Center in the Aura style: at rest, then a toggle that's on in focus, then Settings' capsule in focus. */
    private void cc(String light, String tier) throws Exception {
        Context c = RuntimeEnvironment.getApplication();
        Prefs.of(c).edit().clear().putString(Prefs.LAYOUT, "aura").commit();
        Prefs.device(c).edit().putBoolean("welcomed", true).putString("set_light", light).commit();
        org.robolectric.shadows.ShadowChoreographer.setPaused(true);
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(Duration.ofMillis(16));
        Field w = HomeActivity.class.getDeclaredField("warm");
        w.setAccessible(true);
        w.setBoolean(null, true);
        HomeActivity a = Robolectric.buildActivity(HomeActivity.class).setup().get();
        run(1500);
        call(a, "openCc", new Class<?>[]{boolean.class}, false);
        run(900);
        ControlCenter cc = field(a, "cc");
        ControlCenter.Round wifi = field(cc, "wifi");
        wifi.setOn(true);
        Pad sound = field(cc, "sound");
        call(cc, "caption", new Class<?>[]{Pad.class, String.class}, sound, "TV speakers\nAtmos");
        Pad picture = field(cc, "picture");
        call(cc, "caption", new Class<?>[]{Pad.class, String.class}, picture, "720p · 60 Hz\nHDR10");
        run(300);
        shot(a, "cc-" + tier);
        wifi.setFocusableInTouchMode(true);
        wifi.requestFocus();
        run(700);
        shot(a, "cc-wifi-" + tier);
        Pad settings = field(cc, "settings");
        settings.setFocusableInTouchMode(true);
        settings.requestFocus();
        run(700);
        shot(a, "cc-settings-" + tier);
        Assert.assertTrue(settings.getHeight() < picture.getHeight());
    }

    @Test
    public void ccHigh() throws Exception {
        cc("off", "high");
    }

    @Test
    public void ccLow() throws Exception {
        cc("on", "low");
    }
}
