package app.aura.home;

import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;

import org.json.JSONArray;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Light mode's art diet: banners read no sharper than the focused tile needs, the opaque ones kept in
 * 16-bit colour and the transparent ones as they are, and the saved copy behind a fast start keeping
 * them so, without ever handing Light mode's smaller banners to the full mode.
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30, application = AuraApp.class, qualifiers = "w960dp-h540dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ArtDietTest {
    private static final int[] DENSITIES = {DisplayMetrics.DENSITY_HIGH, DisplayMetrics.DENSITY_XHIGH,
            DisplayMetrics.DENSITY_XXHIGH, DisplayMetrics.DENSITY_XXXHIGH};

    private static void light(Context c, boolean on) {
        Prefs.device(c).edit().putString(Perf.KEY, on ? "on" : "off").commit();
    }

    /** A flat banner as many pixels wide as {@code density}'s dpi, as an app's own is; it may be transparent, it says. */
    private static Bitmap banner(int density, int color) {
        Bitmap b = Bitmap.createBitmap(density, density * 9 / 16, Bitmap.Config.ARGB_8888);
        b.eraseColor(color);
        b.setDensity(density);
        return b;
    }

    /** The same with rounded corners: transparent at its edges. */
    private static Bitmap rounded(int density, int color) {
        Bitmap b = Bitmap.createBitmap(density, density * 9 / 16, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        new Canvas(b).drawRoundRect(0, 0, b.getWidth(), b.getHeight(), 24, 24, p);
        b.setDensity(density);
        return b;
    }

    private static AppRepo.App app(String pkg, Drawable banner, boolean light) {
        AppRepo.App a = new AppRepo.App();
        a.pkg = pkg;
        a.label = pkg;
        a.cls = pkg + ".Main";
        a.category = Intent.CATEGORY_LEANBACK_LAUNCHER;
        a.src = "/data/app/" + pkg + "-1/base.apk";
        a.color = 0xFF3366CC;
        a.banner = banner;
        a.icon = new ColorDrawable(0xFF22AA66);
        a.light = light;
        return a;
    }

    private static Bitmap bitmap(Drawable d) {
        return ((BitmapDrawable) d).getBitmap();
    }

    private static File index(Context c) {
        return new File(new File(c.getFilesDir(), "apps"), "index.json");
    }

    /** The same colour within a step of 16-bit colour: 8 levels of red and blue, 4 of green. */
    private static void near(String what, int expected, int actual) {
        Assert.assertTrue(what + ": " + Integer.toHexString(expected) + " against " + Integer.toHexString(actual),
                Math.abs(Color.red(expected) - Color.red(actual)) <= 9
                        && Math.abs(Color.green(expected) - Color.green(actual)) <= 5
                        && Math.abs(Color.blue(expected) - Color.blue(actual)) <= 9);
    }

    @Test
    public void lightModeReadsBannersNoSharperThanTheFocusedTile() {
        // the full mode as before: xhdpi on a 720p screen, xxhdpi at 1080p, xxxhdpi on a 4K one
        Assert.assertEquals(DisplayMetrics.DENSITY_XHIGH, AppRepo.bannerDensity(1.33125f, false));
        Assert.assertEquals(DisplayMetrics.DENSITY_XXHIGH, AppRepo.bannerDensity(2f, false));
        Assert.assertEquals(DisplayMetrics.DENSITY_XXXHIGH, AppRepo.bannerDensity(4f, false));
        // Light mode: hdpi on a 720p screen, xhdpi at 1080p
        Assert.assertEquals(DisplayMetrics.DENSITY_HIGH, AppRepo.bannerDensity(1.33125f, true));
        Assert.assertEquals(DisplayMetrics.DENSITY_XHIGH, AppRepo.bannerDensity(2f, true));
        for (float d : new float[]{1f, 1.33125f, 1.5f, 2f, 2.5f, 3f, 4f}) {
            float need = 0.95f * 148f * 1.1f * d;
            int pick = AppRepo.bannerDensity(d, true);
            // a banner is as many pixels wide as its density's dpi
            if (pick < DisplayMetrics.DENSITY_XXXHIGH) Assert.assertTrue(d + ": wide enough", pick >= need);
            for (int s : DENSITIES) if (s < pick) Assert.assertTrue(d + ": the smallest that is", s < need);
            Assert.assertTrue(d + ": never sharper than the full mode's", pick <= AppRepo.bannerDensity(d, false));
        }
    }

    @Test
    public void opaqueBannersGoTo16BitsAndTransparentOnesStayAsTheyAre() {
        Context c = RuntimeEnvironment.getApplication();
        Resources res = c.getResources();
        Bitmap opaque = banner(240, 0xFF1E3A5F);
        opaque.setHasAlpha(false);
        Bitmap unused = banner(320, 0xFFCC3344);
        Assert.assertTrue("an alpha channel it never uses", unused.hasAlpha());
        for (Bitmap b : new Bitmap[]{opaque, unused}) {
            BitmapDrawable d = new BitmapDrawable(res, b);
            Drawable s = AppRepo.slim(res, d);
            Bitmap out = bitmap(s);
            Assert.assertEquals(Bitmap.Config.RGB_565, out.getConfig());
            Assert.assertEquals(b.getWidth(), out.getWidth());
            Assert.assertEquals(b.getHeight(), out.getHeight());
            Assert.assertEquals("the same size on screen", d.getIntrinsicWidth(), s.getIntrinsicWidth());
            Assert.assertEquals(d.getIntrinsicHeight(), s.getIntrinsicHeight());
            near("the same colour", b.getPixel(b.getWidth() / 2, b.getHeight() / 2), out.getPixel(out.getWidth() / 2, out.getHeight() / 2));
            Assert.assertFalse("the original is left to the app's resources, not recycled", b.isRecycled());
            Assert.assertSame("16 bits already: nothing more to do", s, AppRepo.slim(res, s));
        }
        BitmapDrawable round = new BitmapDrawable(res, rounded(320, 0xFF2266AA));
        Assert.assertSame("transparent corners: kept as it is", round, AppRepo.slim(res, round));
        ColorDrawable notAPicture = new ColorDrawable(0xFF336699);
        Assert.assertSame("not a picture: as it is", notAPicture, AppRepo.slim(res, notAPicture));
        Assert.assertNull(AppRepo.slim(res, null));
    }

    @Test
    public void theSavedCopyKeepsLightModesBannersAndNeverHandsThemToTheFullMode() throws Exception {
        Context c = RuntimeEnvironment.getApplication();
        Resources res = c.getResources();
        light(c, true);
        Drawable flat = AppRepo.slim(res, new BitmapDrawable(res, banner(240, 0xFF1E3A5F)));
        Assert.assertEquals(Bitmap.Config.RGB_565, bitmap(flat).getConfig());
        List<AppRepo.App> list = new ArrayList<>();
        list.add(app("com.diet.flat", flat, true));
        list.add(app("com.diet.round", new BitmapDrawable(res, rounded(240, 0xFF2266AA)), true));
        AppRepo.save(c, list);
        File index = index(c);
        Assert.assertTrue(index.isFile());

        List<AppRepo.App> back = AppRepo.saved(c);
        Assert.assertNotNull(back);
        Assert.assertEquals(2, back.size());
        AppRepo.App flatBack = back.get(0), roundBack = back.get(1);
        Assert.assertTrue("read for Light mode, it says", flatBack.light);
        Bitmap fb = bitmap(flatBack.banner);
        Assert.assertEquals("kept in 16 bits, read back in 16", Bitmap.Config.RGB_565, fb.getConfig());
        Assert.assertEquals(240, fb.getWidth());
        Assert.assertEquals("the same size on screen", flat.getIntrinsicWidth(), flatBack.banner.getIntrinsicWidth());
        near("its colour", bitmap(flat).getPixel(120, 67), fb.getPixel(120, 67));
        Bitmap rb = bitmap(roundBack.banner);
        Assert.assertEquals("a transparent banner keeps its 32 bits", Bitmap.Config.ARGB_8888, rb.getConfig());
        Assert.assertEquals(0, Color.alpha(rb.getPixel(0, 0)));

        // the full mode never starts from Light mode's smaller banners: it reads the real list, and keeps that
        light(c, false);
        Assert.assertNull(AppRepo.saved(c));
        long written = index.lastModified();
        Thread.sleep(1100);
        for (AppRepo.App a : list) a.light = false;
        AppRepo.save(c, list);
        Assert.assertNotEquals("made again for the full mode", written, index.lastModified());
        Assert.assertNotNull(AppRepo.saved(c));
    }

    @Test
    public void aFullModeCopyStartsLightModeIn16BitsAndIsNotWrittenAgain() throws Exception {
        Context c = RuntimeEnvironment.getApplication();
        Resources res = c.getResources();
        light(c, false);
        List<AppRepo.App> list = new ArrayList<>();
        list.add(app("com.diet.full", new BitmapDrawable(res, banner(320, 0xFFCC3344)), false));
        AppRepo.save(c, list);
        File index = index(c);
        long written = index.lastModified();

        light(c, true);
        List<AppRepo.App> back = AppRepo.saved(c);
        Assert.assertNotNull("a copy made for the full mode still starts Light mode", back);
        AppRepo.App a = back.get(0);
        Assert.assertFalse("and says what it was made for", a.light);
        Bitmap b = bitmap(a.banner);
        Assert.assertEquals("its opaque banner in 16 bits", Bitmap.Config.RGB_565, b.getConfig());
        Assert.assertEquals(320, b.getWidth());
        near("its colour", 0xFFCC3344, b.getPixel(160, 90));
        Thread.sleep(1100);
        AppRepo.save(c, back);
        Assert.assertEquals("the same copy: nothing written", written, index.lastModified());
    }

    @Test
    public void aCopyFromBeforeLightModeReadItsOwnBannersIsReadAgainInLightModeOnly() throws Exception {
        Context c = RuntimeEnvironment.getApplication();
        Resources res = c.getResources();
        light(c, false);
        List<AppRepo.App> list = new ArrayList<>();
        list.add(app("com.diet.old", new BitmapDrawable(res, banner(480, 0xFF447722)), false));
        AppRepo.save(c, list);
        // as an Aura from before wrote it: no word of the mode
        File index = index(c);
        JSONArray saved = new JSONArray(new String(Files.readAllBytes(index.toPath()), StandardCharsets.UTF_8));
        for (int i = 0; i < saved.length(); i++) saved.getJSONObject(i).remove("light");
        Files.write(index.toPath(), saved.toString().getBytes(StandardCharsets.UTF_8));

        Assert.assertNotNull("the full mode starts from it as before", AppRepo.saved(c));
        light(c, true);
        Assert.assertNull("Light mode reads the real list instead: its banners are bigger than it needs", AppRepo.saved(c));
    }
}
