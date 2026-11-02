package app.aura.home;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
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

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.robolectric.Shadows.shadowOf;

/**
 * The saved app list behind a fast start: kept and read back exactly (names, screens, picture
 * sizes), not written again when nothing changed, shown at start, and replaced by the real list
 * without home holding both. Also draws the paused-music card, to look at.
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30, application = AuraApp.class, qualifiers = "w960dp-h540dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class BootCacheTest {
    private static void run(long ms) {
        for (long t = 0; t < ms; t += 50) ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS);
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object o, String name) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(o);
    }

    private static AppRepo.App app(Context c, String pkg, String label, int bannerDensity) {
        AppRepo.App a = new AppRepo.App();
        a.pkg = pkg;
        a.label = label;
        a.cls = pkg + ".Main";
        a.category = Intent.CATEGORY_LEANBACK_LAUNCHER;
        a.src = "/data/app/" + pkg + "-1/base.apk";
        a.color = 0xFF3366CC;
        if (bannerDensity > 0) {
            Bitmap b = Bitmap.createBitmap(bannerDensity, bannerDensity * 9 / 16, Bitmap.Config.ARGB_8888);
            b.eraseColor(0xFFCC3344);
            b.setDensity(bannerDensity);
            a.banner = new BitmapDrawable(c.getResources(), b);
        }
        // an icon that isn't a bitmap (a vector or adaptive icon, as most are now)
        ColorDrawable icon = new ColorDrawable(0xFF22AA66);
        icon.setBounds(0, 0, 96, 96);
        a.icon = new android.graphics.drawable.InsetDrawable(icon, 0) {
            @Override
            public int getIntrinsicWidth() {
                return 48;
            }

            @Override
            public int getIntrinsicHeight() {
                return 48;
            }
        };
        return a;
    }

    @Test
    public void savedListRoundTrips() throws Exception {
        Context c = RuntimeEnvironment.getApplication();
        List<AppRepo.App> list = new ArrayList<>();
        list.add(app(c, "com.one", "One", 320));
        list.add(app(c, "com.two", "Two", 640));
        list.add(app(c, "com.three", "Three", 0));
        AppRepo.save(c, list);
        File index = new File(new File(c.getFilesDir(), "apps"), "index.json");
        Assert.assertTrue(index.isFile());
        long written = index.lastModified();

        List<AppRepo.App> back = AppRepo.saved(c);
        Assert.assertNotNull(back);
        Assert.assertEquals(3, back.size());
        for (int i = 0; i < 3; i++) {
            AppRepo.App a = list.get(i), b = back.get(i);
            Assert.assertEquals(a.pkg, b.pkg);
            Assert.assertEquals(a.label, b.label);
            Assert.assertEquals(a.color, b.color);
            Assert.assertEquals(new ComponentName(a.pkg, a.cls), b.launch.getComponent());
            Assert.assertTrue(b.launch.hasCategory(Intent.CATEGORY_LEANBACK_LAUNCHER));
            Assert.assertEquals(a.banner != null, b.banner != null);
            if (a.banner != null) {
                // every pixel kept, and the same size on screen as the app's own
                Assert.assertEquals(((BitmapDrawable) a.banner).getBitmap().getWidth(), ((BitmapDrawable) b.banner).getBitmap().getWidth());
                Assert.assertEquals(a.banner.getIntrinsicWidth(), b.banner.getIntrinsicWidth());
            }
            Assert.assertEquals("icon keeps its size on screen", a.icon.getIntrinsicWidth(), b.icon.getIntrinsicWidth(), 1);
            Assert.assertTrue("drawn sharper than its size", ((BitmapDrawable) b.icon).getBitmap().getWidth() >= a.icon.getIntrinsicWidth());
        }

        // the same list again: nothing written
        Thread.sleep(1100);
        AppRepo.save(c, back);
        Assert.assertEquals(written, index.lastModified());
        // an app updated (a new package path): written again, and one app gone takes its pictures with it
        list.get(0).src = "/data/app/com.one-2/base.apk";
        list.remove(2);
        AppRepo.save(c, list);
        Assert.assertNotEquals(written, index.lastModified());
        Assert.assertEquals(2, AppRepo.saved(c).size());
        Assert.assertFalse(new File(new File(c.getFilesDir(), "apps"), "com.three.i.png").exists());

        // a damaged copy is simply not used
        try (FileOutputStream out = new FileOutputStream(index)) {
            out.write("[{\"pkg\":".getBytes());
        }
        Assert.assertNull(AppRepo.saved(c));
    }

    @Test
    public void homeStartsFromTheSavedListAndKeepsOneCopy() throws Exception {
        Context c = RuntimeEnvironment.getApplication();
        ShadowPackageManager pm = shadowOf(c.getPackageManager());
        for (String p : new String[]{"com.tv.alpha", "com.tv.beta", "com.tv.gamma"}) {
            ComponentName cn = new ComponentName(p, p + ".Main");
            pm.addActivityIfNotPresent(cn);
            IntentFilter f = new IntentFilter(Intent.ACTION_MAIN);
            f.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
            pm.addIntentFilterForActivity(cn, f);
        }
        // the copy a previous start left behind
        AppRepo.save(c, AppRepo.load(c));
        Assert.assertNotNull(AppRepo.saved(c));

        Prefs.device(c).edit().putBoolean("welcomed", true).putString("set_light", "on").commit();
        org.robolectric.shadows.ShadowChoreographer.setPaused(true);
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(Duration.ofMillis(16));
        Field w = HomeActivity.class.getDeclaredField("warm");
        w.setAccessible(true);
        w.setBoolean(null, true);
        Field cache = HomeActivity.class.getDeclaredField("cache");
        cache.setAccessible(true);
        cache.set(null, null);
        Trouble.clear(c);
        ActivityController<HomeActivity> ac = Robolectric.buildActivity(HomeActivity.class).setup();
        HomeActivity a = ac.get();
        // the saved list shows first (it's read on a thread of its own); then the real one comes
        List<AppRepo.App> shown = null;
        for (int i = 0; i < 100 && shown == null; i++) {
            run(50);
            Thread.sleep(20);
            List<AppRepo.App> s = field(a, "savedApps");
            if (s != null) shown = s;
            if ((Boolean) field(a, "appsRead")) break;
        }
        for (int i = 0; i < 100 && !(Boolean) field(a, "appsRead"); i++) {
            run(50);
            Thread.sleep(20);
        }
        run(500);
        Assert.assertTrue("the real list was read", (Boolean) field(a, "appsRead"));
        List<AppRepo.App> apps = field(a, "apps");
        Assert.assertEquals(3, apps.size());
        Assert.assertEquals(3, ((List<?>) field(a, "cells")).size());
        String how;
        if (shown != null) {
            // it matched what's installed: home kept the saved copy and let the other go
            Assert.assertSame("one copy of every banner, not two", shown, apps);
            how = "started from the saved list, kept it";
        } else {
            how = "the real list came first";
        }
        Assert.assertNull("nothing left over", field(a, "savedApps"));
        ac.pause().stop().destroy();
        run(200);
        Assert.assertEquals(Trouble.report(c), 0, Trouble.count(c));
        try (java.io.FileWriter fw = new java.io.FileWriter("target/boot-cache.txt")) {
            fw.write(how + "\n");
        }
    }

    /** Home showing the saved list when the real one arrives: kept when it matches, replaced when it doesn't. */
    @Test
    public void savedListMeetsTheRealOne() throws Exception {
        Context c = RuntimeEnvironment.getApplication();
        ShadowPackageManager pm = shadowOf(c.getPackageManager());
        for (String p : new String[]{"com.tv.alpha", "com.tv.beta"}) {
            ComponentName cn = new ComponentName(p, p + ".Main");
            pm.addActivityIfNotPresent(cn);
            IntentFilter f = new IntentFilter(Intent.ACTION_MAIN);
            f.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
            pm.addIntentFilterForActivity(cn, f);
        }
        AppRepo.save(c, AppRepo.load(c));
        Prefs.device(c).edit().putBoolean("welcomed", true).putString("set_light", "on").commit();
        org.robolectric.shadows.ShadowChoreographer.setPaused(true);
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(Duration.ofMillis(16));
        Field w = HomeActivity.class.getDeclaredField("warm");
        w.setAccessible(true);
        w.setBoolean(null, true);
        ActivityController<HomeActivity> ac = Robolectric.buildActivity(HomeActivity.class).setup();
        HomeActivity a = ac.get();
        for (int i = 0; i < 100 && !(Boolean) field(a, "appsRead"); i++) {
            run(50);
            Thread.sleep(20);
        }
        Method populate = HomeActivity.class.getDeclaredMethod("populate");
        populate.setAccessible(true);
        Method reload = HomeActivity.class.getDeclaredMethod("reloadApps", boolean.class);
        reload.setAccessible(true);
        Field appsF = HomeActivity.class.getDeclaredField("apps"), savedF = HomeActivity.class.getDeclaredField("savedApps"),
                readF = HomeActivity.class.getDeclaredField("appsRead");
        appsF.setAccessible(true);
        savedF.setAccessible(true);
        readF.setAccessible(true);

        for (boolean stale : new boolean[]{false, true}) {
            // as at a start: the saved list on show, the real one not read yet
            List<AppRepo.App> saved = AppRepo.saved(c);
            Assert.assertNotNull(saved);
            if (stale) saved.get(0).src = "/data/app/old/base.apk"; // updated while Aura was off
            appsF.set(a, saved);
            savedF.set(a, saved);
            readF.set(a, false);
            populate.invoke(a);
            run(300);
            List<?> cells = field(a, "cells");
            Object firstCard = ((Cell) cells.get(0)).card;
            reload.invoke(a, false);
            for (int i = 0; i < 100 && !(Boolean) field(a, "appsRead"); i++) {
                run(50);
                Thread.sleep(20);
            }
            run(300);
            List<AppRepo.App> now = field(a, "apps");
            Assert.assertNull(field(a, "savedApps"));
            Assert.assertEquals(2, now.size());
            if (!stale) {
                Assert.assertSame("matching: the saved copy stays, the one read is let go", saved, now);
                Assert.assertSame("and no tile is painted again", firstCard, ((Cell) ((List<?>) field(a, "cells")).get(0)).card);
            } else {
                Assert.assertNotSame("stale: the real list replaces it", saved, now);
                Assert.assertNotSame("and its tiles are painted again from it", firstCard, ((Cell) ((List<?>) field(a, "cells")).get(0)).card);
            }
        }
        ac.pause().stop().destroy();
        Assert.assertEquals(Trouble.report(c), 0, Trouble.count(c));
    }

    @Test
    public void pausedMusicCard() throws Exception {
        Context c = RuntimeEnvironment.getApplication();
        Prefs.device(c).edit().putBoolean("welcomed", true).putString("set_light", "on").commit();
        org.robolectric.shadows.ShadowChoreographer.setPaused(true);
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(Duration.ofMillis(16));
        Field w = HomeActivity.class.getDeclaredField("warm");
        w.setAccessible(true);
        w.setBoolean(null, true);
        ActivityController<HomeActivity> ac = Robolectric.buildActivity(HomeActivity.class).setup();
        HomeActivity a = ac.get();
        run(2000);
        View td = field(a, "tdW");
        AppRepo.App owner = new AppRepo.App();
        owner.pkg = "com.spotify.tv.android";
        owner.label = "Spotify";
        owner.color = 0xFF1DB954;
        owner.icon = new ColorDrawable(0xFF1DB954);
        Bitmap art = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888);
        Canvas ac2 = new Canvas(art);
        ac2.drawColor(0xFF8E44AD);
        android.graphics.Paint p = new android.graphics.Paint();
        p.setColor(0xFFF39C12);
        ac2.drawCircle(150, 150, 90, p);
        Method show = HomeActivity.class.getDeclaredMethod("showTodayMusic", MediaWatch.Playing.class, AppRepo.App.class);
        show.setAccessible(true);
        int k = 0;
        for (String title : new String[]{"Blinding Lights", "A Very Long Song Title That Goes On (Extended Remix Version 2024)"}) {
            for (boolean withArt : new boolean[]{true, false}) {
                MediaWatch.Playing m = new MediaWatch.Playing();
                m.title = title;
                m.by = "The Weeknd";
                m.pkg = owner.pkg;
                m.art = withArt ? art : null;
                m.progress = 0.42f;
                show.invoke(a, m, owner);
                int width = td.getWidth() > 0 ? td.getWidth() : Ui.dp(200), height = td.getHeight() > 0 ? td.getHeight() : Ui.dp(150);
                td.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
                td.layout(0, 0, width, height);
                Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                Canvas cv = new Canvas(out);
                cv.drawColor(Color.rgb(28, 34, 48));
                td.draw(cv);
                try (FileOutputStream f = new FileOutputStream("target/today-music-" + (k++) + ".png")) {
                    out.compress(Bitmap.CompressFormat.PNG, 100, f);
                }
                // nothing runs past the card's bottom edge
                View music = field(a, "tdMusic");
                View chip = field(a, "tdChip");
                int[] at = new int[2];
                int bottom = chip.getBottom() + ((View) chip.getParent()).getTop();
                Assert.assertTrue("the Resume button fits (" + bottom + " of " + music.getHeight() + ")", bottom <= music.getHeight() - music.getPaddingBottom() + 1);
            }
        }
        // and back to your day
        show.invoke(a, null, null);
        Assert.assertEquals(View.VISIBLE, ((View) field(a, "tdDay")).getVisibility());
        Assert.assertEquals(View.GONE, ((View) field(a, "tdMusic")).getVisibility());
        ac.pause().stop().destroy();
    }
}
