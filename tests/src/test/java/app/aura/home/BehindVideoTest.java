package app.aura.home;

import android.app.Activity;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.widget.FrameLayout;

import org.junit.After;
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

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Aura's tiles in front and behind a video on a small TV: pictures painted behind the screen and
 * handed over, opaque ones in 16 bits on LOW, far ones let go and painted again on the way back;
 * banners and icons let go where the saved copy brings them back; the lifted logos down to 16; and
 * home keeping only its first screen while an app plays, painting the rest again on return.
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30, application = AuraApp.class, qualifiers = "w960dp-h540dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class BehindVideoTest {
    private Context c;
    private boolean wasLight;

    @Before
    public void setUp() throws Exception {
        // Robolectric keeps the animation clock's last time across tests: a spring started in the
        // first frame would begin in the future and never move
        Field clock = android.view.animation.AnimationUtils.class.getDeclaredField("sAnimationState");
        clock.setAccessible(true);
        ((ThreadLocal<?>) clock.get(null)).remove();
        c = RuntimeEnvironment.getApplication();
        // Prefs.of and Prefs.device are one file for the main profile: cleared once
        Prefs.device(c).edit().clear().putBoolean("welcomed", true).putString(Perf.KEY, "on").commit();
        org.robolectric.shadows.ShadowChoreographer.setPaused(true);
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(Duration.ofMillis(16));
        Field w = HomeActivity.class.getDeclaredField("warm");
        w.setAccessible(true);
        w.setBoolean(null, true);
        wasLight = Ui.light;
    }

    @After
    public void tearDown() {
        Ui.light = wasLight;
    }

    private static void run(long ms) {
        for (long t = 0; t < ms; t += 16) ShadowLooper.idleMainLooper(16, TimeUnit.MILLISECONDS);
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

    private static void call(Object o, String name) throws Exception {
        Method m = o.getClass().getDeclaredMethod(name);
        m.setAccessible(true);
        m.invoke(o);
    }

    /** An app as read in Light mode: a flat opaque banner of {@code color} as many pixels wide as hdpi's dpi, and an icon. */
    private static AppRepo.App app(Context c, String pkg, int color) {
        AppRepo.App a = new AppRepo.App();
        a.pkg = pkg;
        a.label = pkg;
        a.cls = pkg + ".Main";
        a.category = Intent.CATEGORY_LEANBACK_LAUNCHER;
        a.src = "/data/app/" + pkg + "-1/base.apk";
        a.color = color;
        Bitmap b = Bitmap.createBitmap(240, 135, Bitmap.Config.ARGB_8888);
        b.eraseColor(color);
        b.setDensity(240);
        a.banner = new BitmapDrawable(c.getResources(), b);
        a.icon = new ColorDrawable(0xFF22AA66);
        a.light = true;
        return a;
    }

    /** The same colour within a step of 16-bit colour (and its dithering). */
    private static void near(String what, int expected, int actual) {
        Assert.assertTrue(what + ": " + Integer.toHexString(expected) + " against " + Integer.toHexString(actual),
                Math.abs(Color.red(expected) - Color.red(actual)) <= 9
                        && Math.abs(Color.green(expected) - Color.green(actual)) <= 5
                        && Math.abs(Color.blue(expected) - Color.blue(actual)) <= 9);
    }

    /** Every card from {@code from} to {@code to} shows a picture ({@code shown}), or none. */
    private static boolean all(List<Cell> cells, int from, int to, boolean shown) {
        for (int i = from; i < to; i++) if ((cells.get(i).card.art() != null) != shown) return false;
        return true;
    }

    /** The main thread run and the painter thread given real time until {@link #all} holds, for up to ten seconds. */
    private static boolean await(List<Cell> cells, int from, int to, boolean shown) throws InterruptedException {
        for (int t = 0; t < 400 && !all(cells, from, to, shown); t++) {
            run(32);
            Thread.sleep(10);
        }
        return all(cells, from, to, shown);
    }

    @Test
    public void aTilePaintedBehindTheScreenIsHandedOverAndLetGo() throws Exception {
        Ui.light = true;
        Activity act = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(act);
        act.setContentView(root);
        int[] calls = {0};
        Tiles.Painter p = (cv, w, h) -> {
            calls[0]++;
            cv.drawColor(0xFF2266AA);
        };
        Card card = new Card(act, p, 0xFF2266AA, 14);
        card.deferred = true;
        card.opaque = true;
        long before = Card.heldBytes();
        root.addView(card, new FrameLayout.LayoutParams(200, 112));
        run(100);

        Assert.assertEquals(200, card.getWidth());
        Assert.assertEquals("never painted on the main thread as it's laid out", 0, calls[0]);
        Assert.assertNull("its colour until its picture lands", card.art());
        Assert.assertTrue(card.wantsArt());
        Assert.assertEquals(before, Card.heldBytes());

        ExecutorService painter = Executors.newSingleThreadExecutor();
        try {
            Bitmap b = painter.submit(() -> Card.paintBehind(p, 200, 112, 1f, true)).get(10, TimeUnit.SECONDS);
            Assert.assertEquals("opaque on LOW: 16 bits", Bitmap.Config.RGB_565, b.getConfig());
            Assert.assertEquals(200, b.getWidth());
            Assert.assertEquals(112, b.getHeight());
            card.take(b);
            Assert.assertSame("handed over and shown", b, card.art());
            Assert.assertFalse(card.wantsArt());
            Assert.assertEquals("counted while it's held", before + b.getAllocationByteCount(), Card.heldBytes());

            card.release();
            Assert.assertNull("let go", card.art());
            Assert.assertFalse("never recycled: a frame may still be drawing it", b.isRecycled());
            Assert.assertTrue("painted again when it comes near", card.wantsArt());
            Assert.assertEquals("and no longer counted", before, Card.heldBytes());

            // a tile taking another's place shows that one's picture until its own lands: nothing flashes
            Card next = new Card(act, p, 0xFF2266AA, 14);
            next.deferred = true;
            next.standIn(b);
            root.addView(next, new FrameLayout.LayoutParams(200, 112));
            run(100);
            Assert.assertSame("the picture it replaces stands in", b, next.art());
            Assert.assertTrue("its own is still wanted", next.wantsArt());
            Bitmap own = painter.submit(() -> Card.paintBehind(p, 200, 112, 1f, true)).get(10, TimeUnit.SECONDS);
            next.take(own);
            Assert.assertSame(own, next.art());
            Assert.assertFalse(next.wantsArt());

            // glass panes and inputs keep their transparency
            Bitmap glass = painter.submit(() -> Card.paintBehind(p, 200, 112, 1f, false)).get(10, TimeUnit.SECONDS);
            Assert.assertEquals(Bitmap.Config.ARGB_8888, glass.getConfig());
            Assert.assertEquals("nothing painted on the main thread at all", 3, calls[0]);
        } finally {
            painter.shutdownNow();
        }
    }

    @Test
    public void bannersAndIconsLetGoComeBackFromTheSavedCopy() throws Exception {
        AppRepo.App a = app(c, "com.behind.one", 0xFF1E3A5F);
        List<AppRepo.App> list = new ArrayList<>();
        list.add(a);
        AppRepo.save(c, list);
        File dir = new File(c.getFilesDir(), "apps");
        Assert.assertTrue(new File(dir, a.pkg + ".b.png").isFile());
        Assert.assertTrue("it knows where its pictures were kept", a.bannerAt >= 0 && a.iconAt >= 0);

        Assert.assertTrue(a.letGo(false));
        Assert.assertNull("the banner let go", a.banner);
        Assert.assertNull("and the icon", a.icon);
        Assert.assertTrue("it still has a banner, for the saved copy and the grid", a.hasBanner());
        Assert.assertTrue(a.gone());

        // the same list: nothing written; another app arrives: written again, the pictures let go kept
        File index = new File(dir, "index.json");
        long written = index.lastModified();
        Thread.sleep(1100);
        AppRepo.save(c, list);
        Assert.assertEquals("nothing written", written, index.lastModified());
        list.add(app(c, "com.behind.two", 0xFFCC3344));
        AppRepo.save(c, list);
        Assert.assertNotEquals(written, index.lastModified());
        Assert.assertTrue("its banner kept, not written again from nothing", new File(dir, a.pkg + ".b.png").isFile());
        Assert.assertTrue(new File(dir, a.pkg + ".i.png").isFile());
        List<AppRepo.App> back = AppRepo.saved(c);
        Assert.assertNotNull(back);
        Assert.assertEquals(2, back.size());
        Assert.assertNotNull("read back at a start as before", back.get(0).banner);

        // a tile painted behind the screen reads it back there
        ExecutorService painter = Executors.newSingleThreadExecutor();
        try {
            Bitmap tile = painter.submit(() -> Card.paintBehind(Tiles.app(a), 160, 90, 1f, true)).get(10, TimeUnit.SECONDS);
            near("its banner, read back", 0xFF1E3A5F, tile.getPixel(80, 45));
        } finally {
            painter.shutdownNow();
        }
        Assert.assertNotNull("back for good", a.banner);
        Assert.assertEquals(240, ((BitmapDrawable) a.banner).getBitmap().getWidth());
        Assert.assertNotNull(a.icon());
        Assert.assertFalse(a.gone());

        // the switcher's apps keep their icons; an app with no saved copy keeps everything
        Assert.assertTrue(a.letGo(true));
        Assert.assertNull(a.banner);
        Assert.assertNotNull("kept for the switcher", a.icon);
        AppRepo.App unsaved = app(c, "com.behind.unsaved", 0xFF448822);
        Assert.assertFalse(unsaved.letGo(false));
        Assert.assertNotNull(unsaved.banner);
        Assert.assertNotNull(unsaved.icon);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void theLiftedLogosComeDownToTheSixteenUsedLast() throws Exception {
        Field f = Tiles.class.getDeclaredField("LOGOS");
        f.setAccessible(true);
        Map<String, Bitmap> logos = (Map<String, Bitmap>) f.get(null);
        Tiles.trim();
        synchronized (logos) {
            for (int i = 0; i < 30; i++) logos.put("com.logo" + i + "@10x10", null);
            // used a moment ago
            logos.get("com.logo0@10x10");
        }
        Tiles.cap(16);
        synchronized (logos) {
            Assert.assertEquals(16, logos.size());
            Assert.assertTrue("the one used last stays", logos.containsKey("com.logo0@10x10"));
            Assert.assertTrue(logos.containsKey("com.logo29@10x10"));
            Assert.assertFalse("the oldest go", logos.containsKey("com.logo1@10x10"));
        }
        Tiles.trim();
    }

    @Test
    public void homeBehindAVideoKeepsItsFirstScreenAndPaintsTheRestOnReturn() throws Exception {
        Prefs.of(c).edit().putString(Prefs.LAYOUT, "aura").putString(Prefs.TILE_STYLE, "original")
                .putBoolean(Prefs.GRID_PAGES, false).commit();
        Trouble.clear(c);
        ActivityController<HomeActivity> ac = Robolectric.buildActivity(HomeActivity.class).setup();
        HomeActivity a = ac.get();
        for (int i = 0; i < 300 && !(Boolean) field(a, "appsRead"); i++) {
            run(50);
            Thread.sleep(20);
        }
        Assert.assertTrue("the real list of apps was read", (Boolean) field(a, "appsRead"));
        run(500);

        // twenty apps, five a row: two rows on screen beneath the widgets, the third just below it, the fourth further
        List<AppRepo.App> mine = new ArrayList<>();
        for (int i = 0; i < 20; i++) mine.add(app(c, "com.behind.app" + (char) ('a' + i), 0xFF203040 + i * 0x050403));
        set(a, "apps", mine);
        call(a, "populate");
        List<Cell> cells = field(a, "cells");
        Assert.assertEquals(20, cells.size());

        Assert.assertTrue("the first screen painted behind the screen", await(cells, 0, 10, true));
        Assert.assertEquals("opaque tiles on LOW: 16 bits", Bitmap.Config.RGB_565, cells.get(0).card.art().getConfig());
        Assert.assertTrue("the row just below painted once the remote rests", await(cells, 10, 15, true));
        run(1000);
        Assert.assertTrue("rows further away never painted", all(cells, 15, 20, false));
        long held = Card.heldBytes();

        // an app in front: only the first screen keeps its pictures
        ac.pause().stop();
        a.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN);
        Assert.assertTrue("the first screen kept", all(cells, 0, 10, true));
        Assert.assertTrue("the rest let go", all(cells, 10, 20, false));
        Assert.assertTrue("what they held let go: " + Card.heldBytes() + " of " + held, Card.heldBytes() < held);
        run(2000);
        Assert.assertTrue("nothing painted behind the video", all(cells, 10, 20, false));

        // back home: what's near the screen is painted again behind it
        ac.restart().start().resume();
        Assert.assertTrue("painted again on return", await(cells, 10, 15, true));
        Assert.assertTrue(all(cells, 0, 10, true));
        Assert.assertTrue(all(cells, 15, 20, false));
        ac.pause().stop().destroy();
        Assert.assertEquals(Trouble.report(c), 0, Trouble.count(c));
    }
}
