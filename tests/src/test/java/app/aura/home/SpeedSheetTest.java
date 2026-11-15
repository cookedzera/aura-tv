package app.aura.home;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.graphics.drawable.ColorDrawable;
import android.os.SystemClock;
import android.view.KeyEvent;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.robolectric.Shadows.shadowOf;

/**
 * Light mode, offered and kept: a home slow to answer the remote offers it (once a month at most),
 * and switching it off on a small TV asks once more first. Back never changes anything.
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30, application = AuraApp.class, qualifiers = "w960dp-h540dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SpeedSheetTest {
    private Context c;
    private boolean wasLight;
    private org.robolectric.android.controller.ActivityController<HomeActivity> controller;

    private static void run(long ms) {
        for (long t = 0; t < ms; t += 16) ShadowLooper.idleMainLooper(16, TimeUnit.MILLISECONDS);
    }

    private static Object call(Object o, String name) throws Exception {
        Method m = o.getClass().getDeclaredMethod(name);
        m.setAccessible(true);
        return m.invoke(o);
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object o, String name) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(o);
    }

    private static void shot(HomeActivity a, String file) throws Exception {
        android.view.View root = a.getWindow().getDecorView();
        android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(root.getWidth(), root.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
        root.draw(new android.graphics.Canvas(b));
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
            b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
        }
    }

    private static void press(HomeActivity a, int code) {
        a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
        a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
        run(400);
    }

    @Before
    public void setUp() throws Exception {
        c = RuntimeEnvironment.getApplication();
        wasLight = Ui.light;
        Profiles.forget();
        Profiles.use(c, Profiles.MAIN);
        Prefs.of(c).edit().clear().putString(Prefs.LAYOUT, "aura").putBoolean(Prefs.WATCH, false).commit();
        Prefs.device(c).edit().clear().putBoolean("welcomed", true).putString(Perf.KEY, "off").commit();
        org.robolectric.shadows.ShadowChoreographer.setPaused(true);
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(Duration.ofMillis(16));
        Field w = HomeActivity.class.getDeclaredField("warm");
        w.setAccessible(true);
        w.setBoolean(null, true);
        // no press waiting from another test's clock: the frames are looked at at once
        HomeActivity.lastKeyAt = 0;
    }

    @After
    public void tearDown() throws Exception {
        if (controller != null) {
            try {
                controller.pause().stop().destroy();
            } catch (Throwable ignored) {
            }
        }
        ShadowLooper.idleMainLooper();
        Ui.light = wasLight;
    }

    private HomeActivity home() throws Exception {
        List<AppRepo.App> fake = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            String pkg = "com.example.app" + i;
            ApplicationInfo ai = new ApplicationInfo();
            ai.packageName = pkg;
            ai.nonLocalizedLabel = "App " + i;
            PackageInfo pi = new PackageInfo();
            pi.packageName = pkg;
            pi.applicationInfo = ai;
            shadowOf(c.getPackageManager()).installPackage(pi);
            AppRepo.App x = new AppRepo.App();
            x.pkg = pkg;
            x.label = "App " + i;
            x.icon = new ColorDrawable(0xFF335577);
            x.color = 0xFF335577;
            fake.add(x);
        }
        controller = Robolectric.buildActivity(HomeActivity.class).setup();
        HomeActivity a = controller.get();
        run(1500);
        List<AppRepo.App> apps = field(a, "apps");
        apps.clear();
        apps.addAll(fake);
        Method m = HomeActivity.class.getDeclaredMethod("populate");
        m.setAccessible(true);
        m.invoke(a);
        run(900);
        return a;
    }

    @Test
    public void eachSlowBurstIsCountedOnceAndQuickOnesPass() {
        long at = SystemClock.uptimeMillis() + 100_000;
        Trouble.burstForTest(at, 20, 40_000, true);
        Assert.assertEquals("frames of 40 ms: slow", 1, Trouble.slowBursts());
        Assert.assertEquals("the same burst isn't counted twice", 1, Trouble.slowBursts());
        Trouble.burstForTest(at + 5_000, 20, 15_000, false);
        Assert.assertEquals("frames of 15 ms: smooth", 1, Trouble.slowBursts());
        Trouble.burstForTest(at + 10_000, 5, 60_000, false);
        Assert.assertEquals("too few frames to say", 1, Trouble.slowBursts());
    }

    @Test
    public void threeSlowBurstsOfferLightModeAndOkTurnsItOn() throws Exception {
        HomeActivity a = home();
        long at = SystemClock.uptimeMillis() + 200_000;
        for (int i = 0; i < 2; i++) {
            Trouble.burstForTest(at + i * 5_000L, 20, 45_000, i == 0);
            call(a, "checkSpeed");
        }
        Assert.assertNull("two slow bursts aren't enough", field(a, "speedSheet"));
        Trouble.burstForTest(at + 10_000, 20, 45_000, false);
        call(a, "checkSpeed");
        run(600);
        SpeedSheet sheet = field(a, "speedSheet");
        Assert.assertNotNull(sheet);
        Assert.assertTrue("offered on the third", sheet.isOpen());
        shot(a, "target/speed-sheet-offer.png");
        Assert.assertTrue("asked is remembered", Prefs.device(c).getLong(HomeActivity.SPEED_ASKED, 0) > 0);

        Pad first = field(sheet, "first");
        // a TV has no touch mode; the simulated one starts in it, where buttons can't hold the focus
        first.requestFocusFromTouch();
        Assert.assertTrue("the first button has the focus", first.isFocused());
        press(a, KeyEvent.KEYCODE_DPAD_CENTER);
        Assert.assertFalse(sheet.isOpen());
        Assert.assertEquals("OK on Turn on Light mode", "on", Perf.mode(c));
        Assert.assertTrue(Ui.light);
    }

    @Test
    public void notOfferedAgainWithinAMonth() throws Exception {
        Prefs.device(c).edit().putLong(HomeActivity.SPEED_ASKED, System.currentTimeMillis() - 5L * 86_400_000L).commit();
        HomeActivity a = home();
        long at = SystemClock.uptimeMillis() + 300_000;
        for (int i = 0; i < 4; i++) {
            Trouble.burstForTest(at + i * 5_000L, 20, 45_000, i == 0);
            call(a, "checkSpeed");
        }
        SpeedSheet sheet = field(a, "speedSheet");
        Assert.assertTrue("asked five days ago: quiet", sheet == null || !sheet.isOpen());
    }

    @Test
    public void switchingLightModeOffWarnsFirstAndBackKeepsIt() throws Exception {
        Prefs.device(c).edit().putString(Perf.KEY, "on").commit();
        HomeActivity a = home();
        SettingsPanel settings = field(a, "settings");
        SettingsPanel.Host host = field(settings, "host");
        boolean[] off = {false};

        host.warnLightOff(() -> off[0] = true);
        run(600);
        SpeedSheet sheet = field(a, "speedSheet");
        Assert.assertTrue("warned first", sheet.isOpen());
        press(a, KeyEvent.KEYCODE_BACK);
        Assert.assertFalse(sheet.isOpen());
        Assert.assertFalse("Back keeps Light mode", off[0]);

        host.warnLightOff(() -> off[0] = true);
        run(600);
        Pad first = field(sheet, "first");
        // a TV has no touch mode; the simulated one starts in it, where buttons can't hold the focus
        first.requestFocusFromTouch();
        Assert.assertTrue("the first button has the focus", first.isFocused());
        press(a, KeyEvent.KEYCODE_DPAD_RIGHT);
        shot(a, "target/speed-sheet-warn.png");
        press(a, KeyEvent.KEYCODE_DPAD_CENTER);
        Assert.assertTrue("Turn off anyway", off[0]);
        Assert.assertFalse(sheet.isOpen());
    }
}
