package app.aura.home;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
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

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Tile art on a weak TV: logos lifted off the main thread and painted in once they land, a card with
 * no room for its picture never painting on every frame, and a card taken off screen coming back at rest.
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class TileArtFixesTest {
    private static void run(long ms) {
        for (long t = 0; t < ms; t += 16) ShadowLooper.idleMainLooper(16, TimeUnit.MILLISECONDS);
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Class<?> c, Object o, String name) throws Exception {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(o);
    }

    private static FrameLayout screen() {
        org.robolectric.shadows.ShadowChoreographer.setPaused(true);
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(Duration.ofMillis(16));
        Activity act = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(act);
        act.setContentView(root);
        return root;
    }

    /** A banner with a flat ground and a white block on it: a clean mark to lift. */
    private static AppRepo.App app(Activity act, String pkg) {
        Bitmap b = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888);
        b.eraseColor(0xFF1E3A5F);
        Paint white = new Paint();
        white.setColor(0xFFFFFFFF);
        new Canvas(b).drawRect(112, 66, 208, 114, white);
        AppRepo.App a = new AppRepo.App();
        a.pkg = pkg;
        a.label = pkg;
        a.banner = new BitmapDrawable(act.getResources(), b);
        a.icon = new ColorDrawable(0xFF336699);
        a.color = 0xFF336699;
        return a;
    }

    @Test
    public void logoLiftedBehindTheScreen() throws Exception {
        FrameLayout root = screen();
        Activity act = (Activity) root.getContext();
        AppRepo.App a = app(act, "com.test.lifted");
        Tiles.trim();
        // the lifting thread held up, so what the cards show before the logo lands can be seen
        ExecutorService lifter = field(Tiles.class, null, "LIFTER");
        CountDownLatch gate = new CountDownLatch(1);
        lifter.execute(() -> {
            try {
                gate.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        Card kept = new Card(act, Tiles.glass(a), a.color, 14);
        Card gone = new Card(act, Tiles.glass(a), a.color, 14);
        root.addView(kept, new FrameLayout.LayoutParams(300, 169));
        root.addView(gone, new FrameLayout.LayoutParams(300, 169));
        run(100);

        Assert.assertEquals(300, kept.getWidth());
        Assert.assertNull("nothing on show until the logo lands", kept.art());
        Map<String, List<Runnable>> lifting = field(Tiles.class, null, "LIFTING");
        List<Runnable> waiting = lifting.get("com.test.lifted@300x169");
        Assert.assertNotNull("lifted behind the screen", waiting);
        Assert.assertEquals("one lift for both cards", 2, waiting.size());

        root.removeView(gone);
        gate.countDown();
        lifter.submit(() -> { }).get(10, TimeUnit.SECONDS);
        // the removal's layout holds plain messages back until the next frame, as on a TV
        run(100);

        Assert.assertFalse(lifting.containsKey("com.test.lifted@300x169"));
        Bitmap art = kept.art();
        Assert.assertNotNull("painted in once it landed", art);
        Assert.assertTrue("its mark in the middle", Color.alpha(art.getPixel(150, 84)) > 200);
        Assert.assertNull("a card taken off screen is left as it was", gone.art());
    }

    @Test
    public void noRoomPaintsPlainWithoutThePainter() {
        FrameLayout root = screen();
        int[] calls = {0};
        Tiles.Painter starved = (c, w, h) -> {
            calls[0]++;
            throw new OutOfMemoryError("no room");
        };
        Card card = new Card(root.getContext(), starved, 0xFF3366FF, 14);
        card.measure(android.view.View.MeasureSpec.makeMeasureSpec(200, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(112, android.view.View.MeasureSpec.EXACTLY));
        card.layout(0, 0, 200, 112);
        Assert.assertEquals(1, calls[0]);
        Assert.assertNull(card.art());

        Bitmap screen = Bitmap.createBitmap(200, 112, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(screen);
        for (int i = 0; i < 5; i++) card.draw(c);
        Assert.assertEquals("never painted again frame by frame", 1, calls[0]);
        Assert.assertEquals("the card's colour instead", Ui.mix(0xFF3366FF, 0xFF000000, 0.55f), screen.getPixel(100, 56));
    }

    @Test
    public void detachedCardComesBackAtRest() throws Exception {
        FrameLayout root = screen();
        Card card = new Card(root.getContext(), (c, w, h) -> c.drawColor(0xFF223344), 0xFF223344, 14);
        card.setFocusableInTouchMode(true);
        root.addView(card, new FrameLayout.LayoutParams(200, 112));
        run(100);
        Assert.assertTrue(card.requestFocus());
        run(600);
        Assert.assertTrue("grown on focus", card.getScaleX() > 1.05f);
        card.glint();

        // taken away mid-animation (focus leaves as it goes), as the switcher's cards are
        root.removeView(card);
        ValueAnimator focusAnim = field(Card.class, card, "focusAnim");
        ValueAnimator glintAnim = field(Card.class, card, "glintAnim");
        float focus = field(Card.class, card, "focus"), glintPos = field(Card.class, card, "glintPos");
        Assert.assertFalse("no focus animation left running", focusAnim.isStarted());
        Assert.assertFalse("no glint left running", glintAnim.isStarted());
        Assert.assertEquals("unlit", 0f, focus, 0f);
        Assert.assertEquals(-1f, glintPos, 0f);
        Assert.assertEquals(1f, card.getScaleX(), 0f);
        Assert.assertEquals(0f, card.getTranslationZ(), 0f);
        run(500);
        Assert.assertEquals("and stays so", 1f, card.getScaleX(), 0f);
    }
}
