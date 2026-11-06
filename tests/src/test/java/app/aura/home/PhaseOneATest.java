package app.aura.home;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.robolectric.Shadows.shadowOf;

/**
 * Phase 1A: home answers the vendors' Home categories and reads its apps again only once the app
 * it replaced has settled; a home left alone stops moving (light mode's stack, HIGH's glints) and
 * draws nothing beneath a video; and it measures itself for Problem reports.
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30, application = AuraApp.class)
public class PhaseOneATest {
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

    @SuppressWarnings("unchecked")
    private static <T> T staticField(Class<?> k, String name) throws Exception {
        Field f = k.getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(null);
    }

    private static void set(Object o, String name, Object v) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(o, v);
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

    /** A focusable view of the test's own on top of home, so focus is somewhere known. */
    private static View focusHere(HomeActivity a, View v) throws Exception {
        v.setFocusable(true);
        v.setFocusableInTouchMode(true);
        FrameLayout root = field(a, "root");
        root.addView(v, new FrameLayout.LayoutParams(200, 112));
        run(100);
        Assert.assertTrue(v.requestFocus());
        Assert.assertSame(v, a.getWindow().getCurrentFocus());
        return v;
    }

    @Before
    public void setUp() throws Exception {
        c = RuntimeEnvironment.getApplication();
        Prefs.device(c).edit().clear().putBoolean("welcomed", true).putString("set_light", "on").commit();
        org.robolectric.shadows.ShadowChoreographer.setPaused(true);
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(Duration.ofMillis(16));
        setStatic(HomeActivity.class, "warm", true);
        // these outlive a test but the simulated clock starts again: a press "later" than now would hold everything up
        HomeActivity.lastKeyAt = 0;
        setStatic(Trouble.class, "arrowAt", 0L);
        // a home an earlier test left on show: this one's coming up must count as coming up
        setStatic(Trouble.class, "watching", false);
    }

    private ActivityController<HomeActivity> start() {
        ActivityController<HomeActivity> ac = Robolectric.buildActivity(HomeActivity.class).setup();
        // with frames paused, the window attaches on the first frame: focus can only be given after it
        run(50);
        ac.windowFocusChanged(true);
        run(1_000);
        return ac;
    }

    @Test
    public void homeAnswersTheVendorsHomeCategories() {
        for (String cat : new String[]{Intent.CATEGORY_HOME, "android.intent.category.COMMON_HOME",
                "com.heytap.tv.launcher.category.MODERN_HOME"}) {
            List<ResolveInfo> r = c.getPackageManager().queryIntentActivities(
                    new Intent(Intent.ACTION_MAIN).addCategory(cat).setPackage(c.getPackageName()), 0);
            Assert.assertEquals(cat, 1, r.size());
            Assert.assertEquals(cat, HomeActivity.class.getName(), r.get(0).activityInfo.name);
        }
    }

    @Test
    public void homeTellsAndroidOnceItIsFullyDrawn() throws Exception {
        setStatic(HomeActivity.class, "warm", false);
        ActivityController<HomeActivity> ac = start();
        HomeActivity a = ac.get();
        run(12_000);
        Assert.assertNull("the boot screen has gone", field(a, "splash"));
        Assert.assertTrue(shadowOf(a).getReportFullyDrawn());
        ac.pause().stop().destroy();
    }

    @Test
    public void aHomeLeftAloneHoldsStillInLightMode() throws Exception {
        ActivityController<HomeActivity> ac = start();
        HomeActivity a = ac.get();
        // an app changed while home was behind another: read again once home is back and quiet
        ac.pause();
        set(a, "appsDirty", true);
        ac.resume();
        run(300);
        Assert.assertFalse("the list was read again", (Boolean) field(a, "appsDirty"));

        View veil = field(a, "quietVeil");
        Assert.assertFalse("the quiet screen's fade needs no offscreen layer", veil.hasOverlappingRendering());

        SmartStack stack = field(a, "stack");
        focusHere(a, new View(a));
        call(a, "wake", new Class<?>[]{});
        run(1_000);
        Assert.assertFalse("in use, its card not in focus: it turns", (Boolean) field(stack, "held"));
        run(12_000);
        Assert.assertTrue((Boolean) field(a, "racked"));
        Assert.assertTrue("left alone in light mode: nothing turns over", (Boolean) field(stack, "held"));
        call(a, "wake", new Class<?>[]{});
        Assert.assertFalse("in use again", (Boolean) field(stack, "held"));

        // HIGH keeps turning while home racks
        Prefs.device(c).edit().putString("set_light", "off").commit();
        run(13_000);
        Assert.assertTrue((Boolean) field(a, "racked"));
        Assert.assertFalse((Boolean) field(stack, "held"));
        ac.pause().stop().destroy();
    }

    @Test
    public void aTileGlintsTwiceAtMostEachTimeItTakesFocus() throws Exception {
        Prefs.device(c).edit().putString("set_light", "off").commit();
        ActivityController<HomeActivity> ac = start();
        HomeActivity a = ac.get();
        Card one = (Card) focusHere(a, new Card(a, (cv, w, h) -> cv.drawColor(0xFF223344), 0xFF223344, 14));
        Card two = new Card(a, (cv, w, h) -> cv.drawColor(0xFF443322), 0xFF443322, 14);
        Runnable tick = field(a, "glintTick");
        for (int i = 0; i < 4; i++) tick.run();
        Assert.assertEquals("twice at most for one focus", 2, (int) (Integer) field(a, "glints"));
        focusHere(a, two);
        Assert.assertEquals("a tile newly in focus may glint again", 0, (int) (Integer) field(a, "glints"));
        tick.run();
        Assert.assertEquals(1, (int) (Integer) field(a, "glints"));
        Assert.assertTrue(one.requestFocus());
        tick.run();
        tick.run();
        tick.run();
        Assert.assertEquals("back on the first: twice again, no more", 2, (int) (Integer) field(a, "glints"));
        ac.pause().stop().destroy();
    }

    @Test
    public void aVideoOverHomeStopsWhatItCoversFromDrawing() throws Exception {
        // HIGH, where the coloured light is on as well
        Prefs.device(c).edit().putString("set_light", "off").commit();
        ActivityController<HomeActivity> ac = start();
        HomeActivity a = ac.get();
        MediaSession session = new MediaSession(c, "test");
        session.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, "Blinding Lights")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, 200_000).build());
        session.setPlaybackState(new PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 80_000, 1f).build());
        session.setActive(true);
        View photo = field(a, "backdrop"), light = field(a, "bloom"), shading = field(a, "scrim");
        View[] under = {photo, light, shading};
        for (View v : under) Assert.assertEquals(View.VISIBLE, v.getVisibility());

        call(a, "startScene", new Class<?>[]{MediaController.class}, session.getController());
        MusicScene scene = field(a, "scene");
        Assert.assertNotNull("the scene started", scene);
        scene.onShown.run();
        for (View v : under) Assert.assertEquals("nothing drawn beneath the video", View.INVISIBLE, v.getVisibility());
        scene.onLost.run();
        for (View v : under) Assert.assertEquals("the photograph back once the video goes", View.VISIBLE, v.getVisibility());

        // covered again, then put away mid-change, when the video never says it has gone
        scene.onShown.run();
        call(a, "photoMusic", new Class<?>[]{boolean.class}, false);
        for (View v : under) Assert.assertEquals(View.VISIBLE, v.getVisibility());
        run(500);
        Assert.assertNull(field(a, "scene"));

        // a scene that ends by itself (its player's app closed) gives everything back too
        call(a, "startScene", new Class<?>[]{MediaController.class}, session.getController());
        scene = field(a, "scene");
        scene.onShown.run();
        scene.stop();
        run(500);
        Assert.assertNull(field(a, "scene"));
        for (View v : under) Assert.assertEquals(View.VISIBLE, v.getVisibility());
        session.release();
        ac.pause().stop().destroy();
    }

    @Test
    public void theProblemReportMeasuresHome() throws Exception {
        ActivityController<HomeActivity> ac = start();
        HomeActivity a = ac.get();
        String r = Trouble.report(c);
        Assert.assertTrue(r, r.contains("Native heap: "));
        Assert.assertTrue(r, r.contains("Frames at rest: "));

        // a whole minute untouched, counted from 15 s after home came on show
        long shown = SystemClock.uptimeMillis();
        run(80_000);
        long at = staticField(Trouble.class, "lastRestAt");
        Assert.assertTrue("a minute at rest counted, " + (at - shown) + " ms in", at > shown + 60_000 && at <= SystemClock.uptimeMillis());
        Assert.assertTrue((Long) staticField(Trouble.class, "lastRest") >= 0);

        // a press: no minute it falls in is counted, the next whole one untouched is
        long pressed = SystemClock.uptimeMillis();
        key(a, KeyEvent.KEYCODE_DPAD_UP);
        run(74_000);
        Assert.assertEquals("nothing counted across the press", at, (long) (Long) staticField(Trouble.class, "lastRestAt"));
        run(62_000);
        long next = staticField(Trouble.class, "lastRestAt");
        Assert.assertTrue("the next whole minute, " + (next - pressed) + " ms after the press", next >= pressed + 75_000);

        // the last D-pad burst's frame times, as Android hands them over frame by frame (Android 7+)
        Method took = Trouble.class.getDeclaredMethod("frameTook", long.class);
        took.setAccessible(true);
        run(2_000);
        key(a, KeyEvent.KEYCODE_DPAD_RIGHT);
        for (int i = 0; i < 8; i++) took.invoke(null, 10_000_000L);
        for (int i = 0; i < 2; i++) took.invoke(null, 40_000_000L);
        r = Trouble.report(c);
        Assert.assertTrue(r, r.contains("D-pad frames: p50 10.0 ms, p90 40.0 ms over the last burst's 10 frames"));
        // frames once the burst has settled aren't its own
        run(1_200);
        took.invoke(null, 90_000_000L);
        r = Trouble.report(c);
        Assert.assertTrue(r, r.contains("p90 40.0 ms over the last burst's 10 frames"));
        // the next burst takes the last one's place
        key(a, KeyEvent.KEYCODE_DPAD_LEFT);
        for (int i = 0; i < 3; i++) took.invoke(null, 20_000_000L);
        r = Trouble.report(c);
        Assert.assertTrue(r, r.contains("p50 20.0 ms, p90 20.0 ms over the last burst's 3 frames"));
        ac.pause().stop().destroy();
    }
}
