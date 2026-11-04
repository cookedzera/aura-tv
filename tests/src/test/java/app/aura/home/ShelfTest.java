package app.aura.home;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.view.View;

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
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.robolectric.Shadows.shadowOf;

/**
 * Top Shelf in the tvOS style: an app with no rows of its own for Android (SonyLIV) still shows
 * what you were watching in it; moving to an app with nothing to show takes the last app's cards
 * away at once; and moving quickly across apps ends on the right app's cards.
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30, application = AuraApp.class, qualifiers = "w960dp-h540dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ShelfTest {
    private static void run(long ms) throws InterruptedException {
        for (long t = 0; t < ms; t += 50) {
            ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS);
            Thread.sleep(5);
        }
    }

    /**
     * The clock standing still, Top Shelf's own thread is given the real time it needs to finish
     * what it was handed (it paints the cards' pictures), then what it handed back runs.
     */
    private static void drain(HomeActivity a) throws Exception {
        java.util.concurrent.ExecutorService shelfWorker = field(a, "shelfWorker");
        shelfWorker.submit(() -> { }).get(10, TimeUnit.SECONDS);
        ShadowLooper.idleMainLooper();
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object o, String name) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(o);
    }

    private static void focus(HomeActivity a, String pkg) throws Exception {
        List<Cell> cells = field(a, "cells");
        for (int i = 0; i < cells.size(); i++) {
            if (cells.get(i).tag.equals(pkg)) {
                // what focus arriving on a tile runs (the simulator is in touch mode, where TV tiles
                // can't take focus)
                Field g = HomeActivity.class.getDeclaredField("gridAt");
                g.setAccessible(true);
                g.setInt(a, i);
                Method m = HomeActivity.class.getDeclaredMethod("shelfFor", int.class);
                m.setAccessible(true);
                m.invoke(a, i);
                return;
            }
        }
        throw new AssertionError(pkg + " not on the grid");
    }

    @Test
    public void shelfFollowsFocus() throws Exception {
        Context c = RuntimeEnvironment.getApplication();
        ShadowPackageManager pm = shadowOf(c.getPackageManager());
        String[] pkgs = {"com.sonyliv", "com.tv.empty", "com.tv.other"};
        for (String p : pkgs) {
            ComponentName cn = new ComponentName(p, p + ".Main");
            pm.addActivityIfNotPresent(cn);
            IntentFilter f = new IntentFilter(Intent.ACTION_MAIN);
            f.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
            pm.addIntentFilterForActivity(cn, f);
            IntentFilter g = new IntentFilter(Intent.ACTION_MAIN);
            g.addCategory(Intent.CATEGORY_LAUNCHER);
            pm.addIntentFilterForActivity(cn, g);
        }
        // SonyLIV publishes no rows; its player told Android what was on
        Watched.forget(c);
        MediaWatch.Playing p = new MediaWatch.Playing();
        p.pkg = "com.sonyliv";
        p.title = "Scam 1992";
        Bitmap art = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888);
        art.eraseColor(0xFF884422);
        p.art = art;
        Watched.note(c, p, 50 * 60_000L, 20 * 60_000L);
        Thread.sleep(500);
        Assert.assertEquals(1, Watched.recent(c, null, 3, "com.sonyliv").size());
        Assert.assertEquals(0, Watched.recent(c, null, 3, "com.tv.empty").size());

        Prefs.device(c).edit().putBoolean("welcomed", true).putString("set_light", "on").commit();
        Prefs.of(c).edit().putString(Prefs.LAYOUT, "tvos").putBoolean(Prefs.DOCK_WIDGETS, true)
                .putString("dock", "com.sonyliv,com.tv.empty,com.tv.other").commit();
        org.robolectric.shadows.ShadowChoreographer.setPaused(true);
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(Duration.ofMillis(16));
        Field w = HomeActivity.class.getDeclaredField("warm");
        w.setAccessible(true);
        w.setBoolean(null, true);
        TopShelf.clearCache();
        ActivityController<HomeActivity> ac = Robolectric.buildActivity(HomeActivity.class).setup();
        HomeActivity a = ac.get();
        run(3500);
        TopShelf shelf = field(a, "shelf");

        // SonyLIV: its own Continue watching
        focus(a, "com.sonyliv");
        run(1500);
        drain(a);
        Assert.assertEquals("SonyLIV's own cards", "com.sonyliv", shelf.shownFor());
        Assert.assertEquals(1, shelf.cards.size());

        // on to an app with nothing: SonyLIV's card fades at once, and goes
        focus(a, "com.tv.empty");
        run(200);
        Assert.assertNull("no longer SonyLIV's", shelf.shownFor());
        Assert.assertTrue("faded straight away", shelf.getAlpha() < 0.5f);
        run(1500);
        Assert.assertFalse(shelf.up());
        for (View v : (List<View>) field(a, "widgets")) Assert.assertEquals("the widgets back", 1f, v.getAlpha(), 0.01f);

        // quickly across the apps and back: ends on SonyLIV's cards, quickly, as they were found a moment ago
        focus(a, "com.tv.other");
        run(100);
        focus(a, "com.tv.empty");
        run(100);
        focus(a, "com.sonyliv");
        run(400);
        drain(a);
        Assert.assertEquals("cards found a moment ago come almost at once", "com.sonyliv", shelf.shownFor());
        run(500);
        Assert.assertEquals("fully in once its fade ends", 1f, shelf.getAlpha(), 0.05f);
        Card card = shelf.cards.get(0);
        Assert.assertNotNull("its picture painted off the main thread", card.ready);
        Assert.assertSame("and used as it is", card.ready, card.art());

        // into an app and back home: the cards on show stay (looked up again, but not made again)
        ac.pause().stop();
        run(500);
        ac.restart().start().resume();
        run(300);
        Assert.assertEquals("still there coming home", "com.sonyliv", shelf.shownFor());
        // what coming home runs with focus on the dock (the simulator is in touch mode: no focus)
        Method refresh = HomeActivity.class.getDeclaredMethod("refreshShelf");
        refresh.setAccessible(true);
        refresh.invoke(a);
        run(2500);
        drain(a);
        Assert.assertTrue("looked up again", TopShelf.cached("com.sonyliv"));
        Assert.assertSame("the very same cards", card, shelf.cards.get(0));
        Assert.assertEquals(1f, shelf.getAlpha(), 0.05f);
        ac.pause().stop().destroy();
        Assert.assertEquals(Trouble.report(c), 0, Trouble.count(c));
    }
}
