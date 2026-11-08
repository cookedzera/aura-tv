package app.aura.home;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;

import org.junit.Assert;
import org.junit.Assume;
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
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.robolectric.Shadows.shadowOf;

/**
 * Home with the remote: a held key steps on a time ladder and stops where it's let go, with its
 * row keeping pace; separate presses are never dropped and never restart a slide from rest; no
 * sideways jump out of the rows while the page scrolls; rows recede by their tiles' alpha; focus
 * waits on the page while tiles are rebuilt and comes back to the same tile after Android built
 * home again; strong TVs step home back as an app opens.
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30, application = AuraApp.class, qualifiers = "w960dp-h540dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class HeldKeysTest {
    private Context c;
    private final List<AppRepo.App> fake = new ArrayList<>();

    /** {@code ms} of a TV's time, a frame at a time: one long idle runs a single frame, which only starts an animator. */
    private static void run(long ms) {
        for (long t = 0; t < ms; t += 16) ShadowLooper.idleMainLooper(Math.min(16, ms - t), TimeUnit.MILLISECONDS);
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object o, String name) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(o);
    }

    private static void setStatic(Class<?> k, String name, Object v) throws Exception {
        Field f = k.getDeclaredField(name);
        f.setAccessible(true);
        f.set(null, v);
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

    private static void focusGrid(HomeActivity a, int i) throws Exception {
        call(a, "focusGrid", new Class<?>[]{int.class}, i);
    }

    private static int gridAt(HomeActivity a) throws Exception {
        return field(a, "gridAt");
    }

    /** Each change of a row's place over {@code ms}, with when it came: its frames. */
    private static void watch(Object row, long ms, List<Long> when, List<Float> at) throws Exception {
        float last = field(row, "shift");
        for (long t = 0; t < ms; t += 4) {
            ShadowLooper.idleMainLooper(4, TimeUnit.MILLISECONDS);
            float s = field(row, "shift");
            if (s != last) {
                when.add(SystemClock.uptimeMillis());
                at.add(s);
                last = s;
            }
        }
    }

    private void install(String pkg, String label, int category) {
        ApplicationInfo ai = new ApplicationInfo();
        ai.packageName = pkg;
        ai.name = label;
        ai.nonLocalizedLabel = label;
        ai.category = category;
        PackageInfo pi = new PackageInfo();
        pi.packageName = pkg;
        pi.applicationInfo = ai;
        shadowOf(c.getPackageManager()).installPackage(pi);
        AppRepo.App x = new AppRepo.App();
        x.pkg = pkg;
        x.label = label;
        x.icon = new ColorDrawable(0xFF335577);
        x.color = 0xFF335577;
        fake.add(x);
    }

    @Before
    public void setUp() throws Exception {
        c = RuntimeEnvironment.getApplication();
        // the main profile's file is the device's too: cleared once, or the second clear wipes the first's writes
        Prefs.of(c).edit().clear().commit();
        Prefs.device(c).edit().clear().putBoolean("welcomed", true).putString("set_light", "on").commit();
        Prefs.of(c).edit().putString(Prefs.LAYOUT, "aura").commit();
        org.robolectric.shadows.ShadowChoreographer.setPaused(true);
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(Duration.ofMillis(16));
        setStatic(HomeActivity.class, "warm", true);
        // the apps a test fills in place are the process's cache too: each test starts with none
        setStatic(HomeActivity.class, "cache", null);
        HomeActivity.lastKeyAt = 0;
    }

    /** Home's one grid (no category rows): 30 apps of four kinds. */
    private ActivityController<HomeActivity> start() throws Exception {
        for (int i = 0; i < 24; i++) install("com.example.video" + i, "Video " + i, ApplicationInfo.CATEGORY_VIDEO);
        for (int i = 0; i < 2; i++) install("com.example.music" + i, "Music " + i, ApplicationInfo.CATEGORY_AUDIO);
        for (int i = 0; i < 2; i++) install("com.example.game" + i, "Game " + i, ApplicationInfo.CATEGORY_GAME);
        for (int i = 0; i < 2; i++) install("com.example.news" + i, "News " + i, ApplicationInfo.CATEGORY_NEWS);
        ActivityController<HomeActivity> ac = Robolectric.buildActivity(HomeActivity.class).setup();
        // with frames paused, the window attaches on the first frame: focus can only be given after it
        run(50);
        ac.windowFocusChanged(true);
        run(1500);
        HomeActivity a = ac.get();
        List<AppRepo.App> apps = field(a, "apps");
        apps.clear();
        apps.addAll(fake);
        call(a, "populate", new Class<?>[]{});
        run(1000);
        List<?> cells = field(a, "cells");
        Assert.assertTrue("the grid was built: " + cells.size(), cells.size() >= 24);
        return ac;
    }

    /** A TV has no touch mode: tiles take focus only once the simulator has left it. */
    private static void leaveTouchMode(View v) {
        v.requestFocusFromTouch();
        Assume.assumeFalse("the simulated TV left touch mode", v.isInTouchMode());
    }

    @Test
    public void focusWaitsOnThePageWhileItsTileIsRebuilt() throws Exception {
        ActivityController<HomeActivity> ac = start();
        HomeActivity a = ac.get();
        List<Cell> cells = field(a, "cells");
        Cell before = cells.get(2);
        focusGrid(a, 2);
        leaveTouchMode(before.card);
        Assert.assertSame(before.card, a.getWindow().getCurrentFocus());
        String key = before.key();
        List<View> seen = new ArrayList<>();
        FrameLayout root = field(a, "root");
        root.getViewTreeObserver().addOnGlobalFocusChangeListener((o, n) -> seen.add(n));
        // the app's name changed: its tile is painted again and the old one leaves the page
        for (AppRepo.App x : fake) if (x.pkg.equals(before.tag)) x.label = "Renamed";
        call(a, "populate", new Class<?>[]{});
        View now = a.getWindow().getCurrentFocus();
        Assert.assertEquals("the same app keeps focus", key, cells.get(gridAt(a)).key());
        Assert.assertNotSame("on its new tile", before.card, now);
        Assert.assertSame(cells.get(gridAt(a)).card, now);
        View parking = field(a, "parking");
        for (View v : seen) Assert.assertTrue("focus only waited on the page meanwhile, never elsewhere: " + v, v == parking || v == now);
        Assert.assertFalse("the holder takes focus no more", parking.isFocusable());
        ac.pause().stop().destroy();
    }

    @Test
    public void focusNeverStaysOnTheHolderWhenTheLastTileGoes() throws Exception {
        ActivityController<HomeActivity> ac = start();
        HomeActivity a = ac.get();
        List<Cell> cells = field(a, "cells");
        focusGrid(a, 2);
        leaveTouchMode(cells.get(2).card);
        Assert.assertSame(cells.get(2).card, a.getWindow().getCurrentFocus());
        // every app hidden at once (a kids' profile left empty): the tile in focus goes with them
        List<AppRepo.App> apps = field(a, "apps");
        apps.clear();
        call(a, "populate", new Class<?>[]{});
        Assert.assertTrue("the grid is empty", cells.isEmpty());
        View now = a.getWindow().getCurrentFocus(), parking = field(a, "parking");
        Assert.assertNotNull("focus is somewhere the remote can go on from", now);
        Assert.assertNotSame("never left on the holder, which draws nothing", parking, now);
        Assert.assertFalse("the holder takes focus no more", parking.isFocusable());
        ac.pause().stop().destroy();
    }

    @Test
    public void theSameTileAfterAndroidBuildsHomeAgain() throws Exception {
        ActivityController<HomeActivity> ac = start();
        HomeActivity a = ac.get();
        List<Cell> cells = field(a, "cells");
        focusGrid(a, 7);
        String key = cells.get(7).key();
        Bundle saved = new Bundle();
        ac.saveInstanceState(saved);
        Assert.assertEquals(key, saved.getString("aura_focus_key"));
        ac.pause().stop().destroy();
        run(300);
        try {
            // the apps this process still holds, as when home is built again for a new language
            setStatic(HomeActivity.class, "cache", new ArrayList<>(fake));
            ActivityController<HomeActivity> again = Robolectric.buildActivity(HomeActivity.class).create(saved);
            HomeActivity b = again.get();
            List<Cell> after = field(b, "cells");
            Assert.assertEquals("back on the tile it left", key, after.get(gridAt(b)).key());
            again.destroy();
        } finally {
            setStatic(HomeActivity.class, "cache", null);
        }
    }

    @Test
    public void strongTvsStepHomeBackAsAnAppOpens() throws Exception {
        Prefs.device(c).edit().putString("set_light", "off").commit();
        ActivityController<HomeActivity> ac = start();
        HomeActivity a = ac.get();
        List<Cell> cells = field(a, "cells");
        leaveTouchMode(cells.get(0).card);
        run(500);
        focusGrid(a, 7);
        run(450);
        a.open(new Intent("app.aura.test.SOMEWHERE"), cells.get(7).card);
        run(450);
        View home = field(a, "home"), veil = field(a, "launchVeil");
        Assert.assertEquals("home steps back as the app zooms in", 0.97f, home.getScaleX(), 0.005f);
        Assert.assertEquals(View.VISIBLE, veil.getVisibility());
        Assert.assertEquals(0.35f, veil.getAlpha(), 0.02f);
        ac.pause().stop();
        Assert.assertEquals("out of sight, full size again", 1f, home.getScaleX(), 0.001f);
        Assert.assertEquals(View.GONE, veil.getVisibility());
        ac.restart().start().resume();
        run(500);
        Assert.assertEquals(1f, home.getScaleX(), 0.001f);
        ac.pause().stop().destroy();
    }
}
