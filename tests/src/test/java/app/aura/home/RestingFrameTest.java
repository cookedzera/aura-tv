package app.aura.home;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.SystemClock;
import android.view.View;
import android.widget.FrameLayout;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowSystemClock;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The resting frame on a small TV: the shade baked into the photograph looks like the live one,
 * the photograph at rest is one copy with the shade view standing down, racking focus keeps the
 * look, HIGH's drift rests ten minutes after the last key, a held Smart Stack keeps no clock, and
 * aerials keep to one decoder in Light mode.
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class RestingFrameTest {
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

    private static void lay(View v, int w, int h) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        v.layout(0, 0, w, h);
    }

    private static Bitmap solid(int w, int h, int grey) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        b.eraseColor(0xFF000000 | grey << 16 | grey << 8 | grey);
        return b;
    }

    private static Bitmap frame(View v, int w, int h) {
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        v.draw(new Canvas(out));
        return out;
    }

    /** The mean of one channel over a 9x9 block: the grain averages out, the shade doesn't. */
    private static double mean(Bitmap b, int x, int y) {
        double sum = 0;
        int n = 0;
        for (int j = Math.max(0, y - 4); j <= Math.min(b.getHeight() - 1, y + 4); j++) {
            for (int i = Math.max(0, x - 4); i <= Math.min(b.getWidth() - 1, x + 4); i++) {
                sum += (b.getPixel(i, j) >> 8) & 0xFF;
                n++;
            }
        }
        return sum / n;
    }

    /** The headline's side, the floor under the apps, the middle and the top edge. */
    private static void same(Bitmap want, Bitmap got, String what) {
        int w = want.getWidth(), h = want.getHeight();
        int[][] spots = {{w / 10, h / 2}, {w / 2, h * 9 / 10}, {w / 2, h / 2}, {w * 3 / 4, h / 10}};
        for (int[] p : spots) {
            Assert.assertEquals(what + " at " + p[0] + "," + p[1], mean(want, p[0], p[1]), mean(got, p[0], p[1]), 3.0);
        }
    }

    /** A photograph of this grey dimmed as the photograph is at {@code defocus}, under the shade. */
    private static Bitmap shaded(Scrim scrim, int grey, float defocus, int w, int h, float lumaTop, float lumaBottom) {
        int level = Math.round(64 * (1f - 0.30f * defocus)), g = Math.round(255 * level / 64f);
        Bitmap out = solid(w, h, Math.round(grey * g / 255f));
        scrim.bake(new Canvas(out), w, h, lumaTop, lumaBottom);
        return out;
    }

    @Test
    public void bakedShadeLooksLikeTheLiveOne() {
        Activity act = Robolectric.buildActivity(Activity.class).setup().get();
        int w = 480, h = 270;
        Scrim scrim = new Scrim(act, false);
        lay(scrim, w, h);
        scrim.adapt(0.8f, 0.3f, 0);
        Bitmap live = solid(w, h, 128);
        scrim.draw(new Canvas(live));
        Bitmap baked = solid(w, h, 128);
        scrim.bake(new Canvas(baked), w, h, 0.8f, 0.3f);
        same(live, baked, "baked against live");
        Assert.assertTrue("the headline's side is shaded deeper than the middle", mean(baked, w / 20, h / 2) < mean(baked, w / 2, h / 2) - 10);

        // the photograph carrying the shade, the view draws nothing; given back, it draws again
        scrim.setBelow(true);
        Bitmap none = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        scrim.draw(new Canvas(none));
        Assert.assertEquals(0, none.getPixel(w / 10, h / 2) >>> 24);
        scrim.setBelow(false);
        Bitmap back = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        scrim.draw(new Canvas(back));
        Assert.assertTrue(back.getPixel(w / 10, h / 2) >>> 24 > 0);
    }

    @Test
    public void restingFrameIsOneBakedCopyAndRackingKeepsTheLook() throws Exception {
        Activity act = Robolectric.buildActivity(Activity.class).setup().get();
        int w = 320, h = 180;
        float lumaTop = 0.7f, lumaBottom = 0.4f;
        Scrim scrim = new Scrim(act, false);
        lay(scrim, w, h);
        scrim.adapt(lumaTop, lumaBottom, 0);
        boolean was = Backdrop.calm;
        Backdrop.calm = true;
        Backdrop b = new Backdrop(act);
        try {
            lay(b, w, h);
            b.setShade(scrim);
            // a sharp copy of grey 200 and a soft one of grey 40, mixed at 0.4: grey 136, shade baked in
            Bitmap resting = solid(w, h, 136);
            scrim.bake(new Canvas(resting), w, h, lumaTop, lumaBottom);
            Constructor<?> make = Class.forName("app.aura.home.Backdrop$Slide").getDeclaredConstructor();
            make.setAccessible(true);
            Object s = make.newInstance();
            set(s, "sharp", solid(w, h, 200));
            set(s, "steady", resting);
            set(s, "mixed", 0.4f);
            set(s, "baked", true);
            set(s, "sw", w);
            set(s, "sh", h);
            set(s, "alpha", 1f);
            set(b, "current", s);

            // at rest: the baked copy alone, and the shade view above it stands down
            set(b, "defocus", 0.4f);
            Bitmap rest = frame(b, w, h);
            Assert.assertTrue("the shade view draws nothing", (Boolean) field(scrim, "below"));
            same(shaded(scrim, 136, 0.4f, w, h, lumaTop, lumaBottom), rest, "at rest");

            // half-way through racking focus: as the shade over sharp and soft blended at 0.2
            set(b, "defocus", 0.2f);
            same(shaded(scrim, 168, 0.2f, w, h, lumaTop, lumaBottom), frame(b, w, h), "racking");

            // out of Light mode the shade view draws again
            Backdrop.calm = false;
            frame(b, w, h);
            Assert.assertFalse((Boolean) field(scrim, "below"));
        } finally {
            Backdrop.calm = was;
            b.release();
        }
    }

    @Test
    public void highDriftRestsTenMinutesAfterTheLastKey() throws Exception {
        Activity act = Robolectric.buildActivity(Activity.class).setup().get();
        boolean was = Backdrop.calm;
        Backdrop.calm = false;
        Backdrop b = new Backdrop(act);
        set(b, "running", true);
        try {
            b.keyed(SystemClock.uptimeMillis());
            b.doFrame(0);
            Assert.assertFalse("it drifts while home is in use", still(b));
            ShadowSystemClock.advanceBy(Duration.ofMinutes(10).plusSeconds(1));
            b.doFrame(0);
            Assert.assertTrue("it rests ten minutes after the last key", still(b));
            b.setPhotoMode(true);
            b.doFrame(0);
            Assert.assertFalse("photo mode keeps it drifting", still(b));
            b.setPhotoMode(false);
            b.doFrame(0);
            Assert.assertTrue(still(b));
            b.keyed(SystemClock.uptimeMillis());
            b.doFrame(0);
            Assert.assertFalse("the next key wakes it", still(b));
        } finally {
            Backdrop.calm = was;
            b.release();
        }
    }

    private static boolean still(Backdrop b) throws Exception {
        long since = field(b, "stillSince");
        return since >= 0;
    }

    @Test
    public void heldStackKeepsNoClockAndEndsItsFlip() throws Exception {
        Activity act = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(act);
        act.setContentView(root);
        SmartStack stack = new SmartStack(act, 26);
        root.addView(stack, new FrameLayout.LayoutParams(600, 300));
        run(100);
        List<SmartStack.Slide> slides = new ArrayList<>();
        for (String title : new String[]{"One", "Two", "Three"}) {
            SmartStack.Slide s = new SmartStack.Slide();
            s.kicker = "TONIGHT";
            s.title = title;
            s.art = Bitmap.createBitmap(64, 36, Bitmap.Config.ARGB_8888);
            slides.add(s);
        }
        stack.setSlides(slides);
        stack.start();
        run(100);
        Handler handler = field(stack, "handler");
        Runnable advance = field(stack, "advance");

        // held (home at rest, or the stack focused): no card turns and no clock is left to wake it
        SmartStack.Slide first = stack.current();
        stack.hold(true);
        Assert.assertFalse("no clock left running", handler.hasCallbacks(advance));
        run(20_000);
        Assert.assertSame(first, stack.current());

        // let go, it turns again on its own
        stack.hold(false);
        Assert.assertTrue(handler.hasCallbacks(advance));
        run(8_000);
        Assert.assertNotSame(first, stack.current());

        // held mid-flip: the flip ends at once, the new card in front and the pill on its dot
        stack.flip();
        ValueAnimator glide = field(field(stack, "dots"), "glide");
        Assert.assertTrue(glide.isRunning());
        stack.hold(true);
        Assert.assertFalse("nothing left moving", glide.isRunning());
        Object[] pages = field(stack, "pages");
        int front = field(stack, "front");
        Assert.assertEquals(1f, ((View) pages[front]).getAlpha(), 0.001f);
        Assert.assertEquals(0f, ((View) pages[1 - front]).getAlpha(), 0.001f);
        Assert.assertEquals(0f, ((View) pages[front]).getTranslationY(), 0.001f);
        stack.stop();
    }

    @Test
    public void aerialsKeepToOneDecoderInLightMode() throws Exception {
        Activity act = Robolectric.buildActivity(Activity.class).setup().get();
        AerialView.Listener none = new AerialView.Listener() {
            @Override
            public void shown(Aerials.Video v) {
            }

            @Override
            public void lost() {
            }
        };
        Prefs.device(act).edit().putString(Perf.KEY, "on").commit();
        AerialView light = new AerialView(act, true, none);
        Assert.assertTrue("one video at a time from the start", (Boolean) field(light, "oneAtATime"));
        View veil = field(light, "veil");
        Assert.assertFalse("the black veil fades without an offscreen layer", veil.hasOverlappingRendering());

        Prefs.device(act).edit().putString(Perf.KEY, "off").commit();
        AerialView high = new AerialView(act, true, none);
        Assert.assertFalse("HIGH may load the next video beside the one playing", (Boolean) field(high, "oneAtATime"));
    }
}
