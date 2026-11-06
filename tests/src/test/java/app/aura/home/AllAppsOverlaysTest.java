package app.aura.home;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.ColorDrawable;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import org.junit.Assert;
import org.junit.Before;
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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Home's panels over All apps: while Control Center, Find or Settings is up, no tile of the page
 * beneath takes the focus; once it closes, the page has the focus back and home stays hidden and
 * out of focus under it. The switcher closing as the Apps key opens the page leaves it so too.
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30, application = AuraApp.class, qualifiers = "w960dp-h540dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AllAppsOverlaysTest {
    private Context c;

    private static void run(long ms) {
        for (long t = 0; t < ms; t += 50) ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS);
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object o, String name) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(o);
    }

    private static void set(Object o, String name, Object v) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(o, v);
    }

    private static Object call(Object o, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = o.getClass().getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(o, args);
    }

    private static void key(HomeActivity a, int code) {
        a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
        a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
    }

    private static boolean within(View v, View group) {
        if (v == null) return false;
        for (ViewParent p = v.getParent(); p != null; p = p.getParent()) if (p == group) return true;
        return false;
    }

    @Before
    public void setUp() throws Exception {
        c = RuntimeEnvironment.getApplication();
        Prefs.device(c).edit().clear().putBoolean("welcomed", true).putString("set_light", "on").commit();
        org.robolectric.shadows.ShadowChoreographer.setPaused(true);
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(Duration.ofMillis(16));
        Field w = HomeActivity.class.getDeclaredField("warm");
        w.setAccessible(true);
        w.setBoolean(null, true);
    }

    /** Home up with twelve apps of the test's own, and All apps opened with the remote's Apps key. */
    private ActivityController<HomeActivity> start() throws Exception {
        ActivityController<HomeActivity> ac = Robolectric.buildActivity(HomeActivity.class).setup();
        run(50);
        ac.windowFocusChanged(true);
        HomeActivity a = ac.get();
        for (int i = 0; i < 300 && !(Boolean) field(a, "appsRead"); i++) {
            run(50);
            Thread.sleep(20);
        }
        run(1_000);
        List<AppRepo.App> apps = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            AppRepo.App x = new AppRepo.App();
            x.pkg = String.format(Locale.ROOT, "com.test.b%02d", i);
            x.label = "App " + i;
            x.icon = new ColorDrawable(0xFF446688);
            x.color = 0xFF446688;
            apps.add(x);
        }
        set(a, "apps", apps);
        call(a, "populate", new Class<?>[]{});
        run(500);
        // a TV has no touch mode, which keeps the focus from anything not made for touch
        a.getWindow().getDecorView().requestFocusFromTouch();
        org.junit.Assume.assumeFalse("the simulated TV left touch mode", a.getWindow().getDecorView().isInTouchMode());
        a.onNewIntent(new Intent(Intent.ACTION_MAIN).putExtra("aura_apps", ""));
        run(1_000);
        AllApps page = field(a, "allApps");
        Assert.assertTrue(page.isOpen());
        Assert.assertTrue("a tile of the page has the focus, not " + a.getWindow().getCurrentFocus() + " (touch mode "
                + page.isInTouchMode() + ", rows " + ((List<?>) field(page, "rows")).size() + ")", within(a.getWindow().getCurrentFocus(), page));
        return ac;
    }

    /** The page as openAllApps leaves it: the focus on its tile, home hidden, still and out of focus beneath. */
    private static void pageInCharge(HomeActivity a, String after) throws Exception {
        AllApps page = field(a, "allApps");
        ViewGroup home = field(a, "home");
        Assert.assertTrue(after + ": the page is still open", page.isOpen());
        Assert.assertEquals(after + ": the page shows", View.VISIBLE, page.getVisibility());
        Assert.assertTrue(after + ": the page has the focus back", within(a.getWindow().getCurrentFocus(), page));
        Assert.assertEquals(after + ": home takes no focus beneath it", ViewGroup.FOCUS_BLOCK_DESCENDANTS, home.getDescendantFocusability());
        Assert.assertNotEquals(after + ": home isn't drawn beneath it", View.VISIBLE, home.getVisibility());
    }

    /** Arrows every way, some past the panel's edges (where Android's own search takes over): never onto a tile beneath. */
    private static void arrowsStayOff(HomeActivity a, String over) throws Exception {
        AllApps page = field(a, "allApps");
        Assert.assertEquals(over + ": the page takes no focus", ViewGroup.FOCUS_BLOCK_DESCENDANTS, page.getDescendantFocusability());
        int[] codes = {KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP};
        for (int code : codes) {
            for (int i = 0; i < 6; i++) {
                key(a, code);
                run(50);
                Assert.assertFalse(over + ": the focus went onto a tile beneath it", within(a.getWindow().getCurrentFocus(), page));
            }
        }
    }

    @Test
    public void panelsOverAllAppsKeepItsTilesOutAndLeaveHomeHiddenAsTheyClose() throws Exception {
        ActivityController<HomeActivity> ac = start();
        HomeActivity a = ac.get();
        pageInCharge(a, "opened");

        call(a, "openCc", new Class<?>[]{boolean.class}, false);
        run(800);
        arrowsStayOff(a, "Control Center");
        call(a, "closeCc", new Class<?>[]{boolean.class}, true);
        run(800);
        pageInCharge(a, "Control Center closed");

        call(a, "openFind", new Class<?>[]{String.class}, "");
        run(600);
        arrowsStayOff(a, "Find");
        key(a, KeyEvent.KEYCODE_BACK);
        run(600);
        pageInCharge(a, "Find closed");

        call(a, "openSettings", new Class<?>[]{});
        run(600);
        arrowsStayOff(a, "Settings");
        call(a, "closeSettings", new Class<?>[]{});
        run(600);
        pageInCharge(a, "Settings closed");

        // home's tile asked for meanwhile (an app installed): the focus stays where the panel has it
        call(a, "openFind", new Class<?>[]{String.class}, "");
        run(600);
        View inFind = a.getWindow().getCurrentFocus();
        call(a, "focusGrid", new Class<?>[]{int.class}, 0);
        Assert.assertSame("Find keeps the focus", inFind, a.getWindow().getCurrentFocus());
        key(a, KeyEvent.KEYCODE_BACK);
        run(600);

        // and the page closing gives home back
        key(a, KeyEvent.KEYCODE_BACK);
        key(a, KeyEvent.KEYCODE_BACK);
        run(800);
        AllApps page = field(a, "allApps");
        Assert.assertFalse(page.isOpen());
        ViewGroup home = field(a, "home");
        Assert.assertEquals(View.VISIBLE, home.getVisibility());
        Assert.assertTrue("home has the focus", within(a.getWindow().getCurrentFocus(), home));
        ac.pause().stop().destroy();
    }

    @Test
    public void theAppsKeyOverTheSwitcherLeavesThePageInCharge() throws Exception {
        ActivityController<HomeActivity> ac = start();
        HomeActivity a = ac.get();
        // back to home, then the recent apps
        key(a, KeyEvent.KEYCODE_BACK);
        key(a, KeyEvent.KEYCODE_BACK);
        run(800);
        List<AppRepo.App> apps = field(a, "apps");
        LinkedHashMap<String, Long> recent = new LinkedHashMap<>();
        recent.put(apps.get(0).pkg, System.currentTimeMillis());
        set(a, "recentCache", recent);
        a.onNewIntent(new Intent(Intent.ACTION_MAIN).putExtra("aura_recents", ""));
        Switcher s = field(a, "switcher");
        Assert.assertTrue(s.isOpen());
        run(800);
        // the Apps key with the switcher up: it closes, All apps opens, and the switcher's last word comes after
        a.onNewIntent(new Intent(Intent.ACTION_MAIN).putExtra("aura_apps", ""));
        run(1_500);
        Assert.assertFalse(s.isOpen());
        pageInCharge(a, "the switcher closed");
        ac.pause().stop().destroy();
    }
}
