package app.aura.home;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Tiles taking focus as measured on tvOS 26: an app tile springs a touch past x1.20 on HIGH and
 * eases there on LOW, content eases to x1.10 and lets go slowly, a press sinks to 0.95 of the
 * focused size, a tile never grows into its neighbours and leans toward the way focus came, its
 * name rides the growth, and on LOW one shared shadow follows the focused tile.
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class TileFocusTest {
    private boolean wasLight;

    private static void run(long ms) {
        for (long t = 0; t < ms; t += 16) ShadowLooper.idleMainLooper(16, TimeUnit.MILLISECONDS);
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Class<?> c, Object o, String name) throws Exception {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(o);
    }

    @Before
    public void setUp() throws Exception {
        wasLight = Ui.light;
        freshAnimationClock();
        // a long rest before each test's first move, and no card that has just lost focus
        Card.movedAt = Long.MIN_VALUE / 2;
        Field left = Card.class.getDeclaredField("leftAt");
        left.setAccessible(true);
        left.setLong(null, Long.MIN_VALUE / 2);
    }

    @After
    public void tearDown() throws Exception {
        Ui.light = wasLight;
        freshAnimationClock();
    }

    /**
     * The simulated clock starts again at every test, but the animation clock's thread state does not:
     * it never runs backwards, so it would read the previous test's last time, seconds ahead, and a
     * spring started inside a frame (Spring.Mover) would wait that long before moving. A TV's clock
     * never runs backwards, so this is the test's clock to put right, not the card.
     */
    private static void freshAnimationClock() throws Exception {
        ThreadLocal<?> state = field(android.view.animation.AnimationUtils.class, null, "sAnimationState");
        state.remove();
    }

    private static FrameLayout screen() {
        org.robolectric.shadows.ShadowChoreographer.setPaused(true);
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(Duration.ofMillis(16));
        Activity act = Robolectric.buildActivity(Activity.class).setup().get();
        Ui.init(act);
        FrameLayout root = new FrameLayout(act);
        act.setContentView(root);
        // the window's first layout hands focus to the first focusable view: let it pass before any
        // tile exists, so a tile takes focus when the test asks, not a frame earlier
        run(32);
        return root;
    }

    /** A home-sized tile (148 x 84 dp) at {@code x}. */
    private static Card tile(FrameLayout root, int x) {
        Card c = new Card(root.getContext(), (cv, w, h) -> cv.drawColor(0xFF223344), 0xFF223344, 13);
        c.setFocusableInTouchMode(true);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Ui.dp(148), Ui.dp(84));
        lp.leftMargin = x;
        lp.topMargin = Ui.dp(100);
        root.addView(c, lp);
        return c;
    }

    /** A home cell placed by translation, as the grid places them. */
    private static Cell cell(FrameLayout root, String name, int x) {
        Cell c = new Cell(root.getContext(), (cv, w, h) -> cv.drawColor(0xFF223344), 0xFF223344, name, name, 148, 84);
        c.card.setFocusableInTouchMode(true);
        c.setTranslationX(x);
        c.setTranslationY(Ui.dp(100));
        root.addView(c, new FrameLayout.LayoutParams(Ui.dp(148), ViewGroup.LayoutParams.WRAP_CONTENT));
        return c;
    }

    @Test
    public void anAppTileSpringsATouchPastItsSizeOnHigh() {
        Ui.light = false;
        FrameLayout root = screen();
        Card a = tile(root, 0), b = tile(root, Ui.dp(200));
        run(100);
        Assert.assertFalse("no focus until the test gives it", a.isFocused());
        Assert.assertTrue(a.requestFocus());
        float peak = 0f;
        for (int i = 0; i < 25; i++) {
            run(16);
            peak = Math.max(peak, a.getScaleX());
        }
        Assert.assertTrue("a touch past x1.20: " + peak, peak > 1.205f && peak < 1.235f);
        run(800);
        Assert.assertEquals("at rest at x1.20", Ui.FOCUS_SCALE_APP, a.getScaleX(), 0.002f);
        Assert.assertTrue("a real shadow on HIGH", a.getTranslationZ() > Ui.dpf(17));

        Assert.assertTrue(b.requestFocus());
        run(48);
        Assert.assertTrue("letting go: " + a.getScaleX(), a.getScaleX() > 1.001f && a.getScaleX() < 1.19f);
        run(200);
        Assert.assertEquals("back in 140 ms", 1f, a.getScaleX(), 0f);
        Assert.assertEquals(0f, a.getTranslationZ(), 0f);
    }

    @Test
    public void onLowAnAppTileEasesUpAtOnceLitAndCastsNoRealShadow() throws Exception {
        Ui.light = true;
        FrameLayout root = screen();
        Card a = tile(root, 0);
        run(100);
        Assert.assertTrue(a.requestFocus());
        float lit = field(Card.class, a, "focus");
        ValueAnimator light = field(Card.class, a, "focusAnim");
        Assert.assertEquals("lit at once: one redraw", 1f, lit, 0f);
        Assert.assertFalse("no light redrawn frame by frame", light.isStarted());
        float peak = 0f;
        for (int i = 0; i < 16; i++) {
            run(16);
            peak = Math.max(peak, a.getScaleX());
        }
        Assert.assertTrue("no overshoot: " + peak, peak <= Ui.FOCUS_SCALE_APP + 1e-4f);
        Assert.assertEquals("there within 150 ms", Ui.FOCUS_SCALE_APP, a.getScaleX(), 1e-4f);
        Assert.assertEquals("no real shadow on LOW", 0f, a.getTranslationZ(), 0f);
        Assert.assertEquals(0f, a.getElevation(), 0f);
    }

    @Test
    public void contentEasesToX110AndLetsGoSlowly() {
        Ui.light = false;
        FrameLayout root = screen();
        Card a = tile(root, 0).asContent(), b = tile(root, Ui.dp(200)).asContent();
        run(100);
        Assert.assertTrue(a.requestFocus());
        float peak = 0f;
        for (int i = 0; i < 50; i++) {
            run(16);
            peak = Math.max(peak, a.getScaleX());
        }
        Assert.assertTrue("no overshoot: " + peak, peak <= Ui.FOCUS_SCALE_CONTENT + 1e-3f);
        Assert.assertEquals(Ui.FOCUS_SCALE_CONTENT, a.getScaleX(), 0.002f);

        Assert.assertTrue(b.requestFocus());
        // an app tile is all but back by now (140 ms); content takes 320
        run(128);
        Assert.assertTrue("still letting go: " + a.getScaleX(), a.getScaleX() > 1.02f);
        run(320);
        Assert.assertEquals("gone within 320 ms", 1f, a.getScaleX(), 0f);
    }

    @Test
    public void aTileNeverGrowsIntoItsNeighbours() {
        Ui.light = false;
        FrameLayout root = screen();
        Card a = tile(root, 0);
        // today's grid: 148 dp tiles 19 dp apart, 9.5 dp of room on each side
        a.setGrowRoom(9.5f);
        run(100);
        Assert.assertTrue(a.requestFocus());
        run(800);
        float s = a.getScaleX(), grown = (s - 1f) * a.getWidth() / 2f;
        Assert.assertEquals(1f + 1.8f * 9.5f / 148f, s, 0.002f);
        Assert.assertTrue("within 90 % of its room: " + grown, grown <= Ui.dpf(9.5f) * 0.9f + 0.5f);
    }

    @Test
    public void aPressSinksTo95PercentOfTheFocusedSize() {
        Ui.light = false;
        FrameLayout root = screen();
        Card a = tile(root, 0);
        run(100);
        Assert.assertTrue(a.requestFocus());
        run(800);
        a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER));
        run(160);
        Assert.assertEquals(Ui.PRESS_FACTOR * Ui.FOCUS_SCALE_APP, a.getScaleX(), 0.002f);
        a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER));
        run(320);
        Assert.assertEquals(Ui.FOCUS_SCALE_APP, a.getScaleX(), 0.002f);
    }

    @Test
    public void aTileLeansTowardTheWayFocusCame() {
        Ui.light = false;
        FrameLayout root = screen();
        Card a = tile(root, 0), b = tile(root, Ui.dp(200));
        run(100);
        Assert.assertTrue(a.requestFocus());
        run(800);
        Assert.assertEquals("nothing to lean from yet", 0f, a.getTranslationX(), 0.01f);
        Assert.assertTrue(b.requestFocus());
        run(800);
        Assert.assertEquals("moved right: leans right", Ui.dpf(Ui.FOCUS_NUDGE), b.getTranslationX(), 0.05f);
        Assert.assertEquals(0f, b.getTranslationY(), 0.01f);
        Assert.assertEquals("the one left settles back", 0f, a.getTranslationX(), 0.01f);
        Assert.assertTrue(a.requestFocus());
        run(800);
        Assert.assertEquals("back left: leans left", -Ui.dpf(Ui.FOCUS_NUDGE), a.getTranslationX(), 0.05f);
    }

    @Test
    public void onHighTheNameRidesTheGrowthAndFadesByItsColour() {
        Ui.light = false;
        FrameLayout root = screen();
        Cell one = cell(root, "Netflix", 0), two = cell(root, "Plex", Ui.dp(200));
        run(100);
        Assert.assertEquals("TalkBack names the tile", "Netflix", String.valueOf(one.card.getContentDescription()));
        Assert.assertTrue(one.card.requestFocus());
        run(800);
        Assert.assertEquals("the view itself never fades (PC13)", 1f, one.label.getAlpha(), 0f);
        Assert.assertEquals("shown by its colour", 255, one.label.getCurrentTextColor() >>> 24);
        Assert.assertTrue("keeps its soft shadow", one.label.getShadowRadius() > 0f);
        Assert.assertEquals("7 dp down at x1.20", Ui.dpf(Ui.LABEL_OFFSET), one.label.getTranslationY(), 0.05f);

        Assert.assertTrue(two.card.requestFocus());
        run(400);
        Assert.assertEquals("faded out", 0, one.label.getCurrentTextColor() >>> 24);
        Assert.assertEquals(0f, one.label.getTranslationY(), 0.01f);
    }

    @Test
    public void onLowTheNameWaitsAtItsSpotAndOnlyFades() {
        Ui.light = true;
        FrameLayout root = screen();
        Cell one = cell(root, "Netflix", 0), two = cell(root, "Plex", Ui.dp(200));
        run(100);
        Assert.assertEquals("no text shadow layer on LOW", 0f, one.label.getShadowRadius(), 0f);
        Assert.assertTrue(one.card.requestFocus());
        Assert.assertEquals("put where it will show", Ui.dpf(Ui.LABEL_OFFSET), one.label.getTranslationY(), 0.05f);
        run(400);
        Assert.assertEquals(1f, one.label.getAlpha(), 0.001f);
        Assert.assertEquals(0xFFFFFFFF, one.label.getCurrentTextColor());

        Assert.assertTrue(two.card.requestFocus());
        run(400);
        Assert.assertEquals(0f, one.label.getAlpha(), 0.001f);
    }

    @Test
    public void onLowOneSharedShadowFollowsTheFocusedTile() throws Exception {
        Ui.light = true;
        FrameLayout root = screen();
        View below = new View(root.getContext());
        root.addView(below, new FrameLayout.LayoutParams(1, 1));
        FocusLayer layer = FocusLayer.attach(root, below);
        Cell one = cell(root, "Netflix", 0), two = cell(root, "Plex", Ui.dp(200));
        run(100);
        View[] shades = field(FocusLayer.class, layer, "shades");
        Assert.assertTrue("beneath the tiles", root.indexOfChild(shades[1]) < root.indexOfChild(one));

        Assert.assertTrue(one.card.requestFocus());
        run(300);
        View lit = shades[0].getAlpha() > 0f ? shades[0] : shades[1];
        Assert.assertEquals("the baked shadow at its strength", 120 / 255f, lit.getAlpha(), 0.01f);
        Assert.assertEquals("one at a time", 0f, (lit == shades[0] ? shades[1] : shades[0]).getAlpha(), 0f);
        Assert.assertEquals("centred under the tile", Ui.dp(148) / 2f, centreX(lit), 1f);
        Assert.assertTrue("the tile draws none of its own", one.card.layered);

        Assert.assertTrue(two.card.requestFocus());
        run(400);
        View now = shades[0].getAlpha() > 0f ? shades[0] : shades[1];
        Assert.assertEquals("the old one gone with its tile", 0f, (now == shades[0] ? shades[1] : shades[0]).getAlpha(), 0f);
        Assert.assertEquals("under the new tile, leaning with it", Ui.dp(200) + Ui.dp(148) / 2f + Ui.dpf(Ui.FOCUS_NUDGE),
                centreX(now), 1f);
    }

    @Test
    public void onHighTheSharedShadowStaysAway() throws Exception {
        Ui.light = false;
        FrameLayout root = screen();
        FocusLayer layer = FocusLayer.attach(root, null);
        Cell one = cell(root, "Netflix", 0);
        run(100);
        Assert.assertTrue(one.card.requestFocus());
        run(300);
        View[] shades = field(FocusLayer.class, layer, "shades");
        for (View s : shades) Assert.assertEquals(0f, s.getAlpha(), 0f);
        Assert.assertFalse("HIGH tiles keep their own", one.card.layered);
    }

    @Test
    public void aLateLogoComesUpOnHighAndIsStraightInOnLow() throws Exception {
        for (boolean low : new boolean[]{false, true}) {
            Ui.light = low;
            FrameLayout root = screen();
            Activity act = (Activity) root.getContext();
            AppRepo.App a = app(act, low ? "com.test.fade.low" : "com.test.fade.high");
            // the lifting thread held up, so the logo lands after the card is on screen, as after a cold start
            ExecutorService lifter = field(Tiles.class, null, "LIFTER");
            CountDownLatch gate = new CountDownLatch(1);
            lifter.execute(() -> {
                try {
                    gate.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
            Card card = new Card(act, Tiles.glass(a), a.color, 14);
            root.addView(card, new FrameLayout.LayoutParams(300, 169));
            run(100);
            Assert.assertNull(card.art());
            gate.countDown();
            lifter.submit(() -> { }).get(10, TimeUnit.SECONDS);
            run(48);
            Assert.assertNotNull("painted in once it landed", card.art());
            float alpha = field(Card.class, card, "artAlpha");
            if (low) Assert.assertEquals("LOW: straight in", 1f, alpha, 0f);
            else Assert.assertTrue("HIGH: coming up, not popped in: " + alpha, alpha < 1f);
            run(300);
            float after = field(Card.class, card, "artAlpha");
            Assert.assertEquals(1f, after, 0f);
        }
    }

    @Test
    public void continueCardsSayHowFarThrough() {
        FrameLayout root = screen();
        Words.init(root.getContext());
        Continue.Item it = new Continue.Item();
        it.title = "Severance";
        Assert.assertEquals("not started: its title", "Severance", Continue.spoken(it));
        it.progress = 0.6f;
        it.leftMin = 20;
        String said = Continue.spoken(it);
        Assert.assertTrue(said, said.contains("Severance") && said.contains("20"));
    }

    private static float centreX(View v) {
        return v.getLeft() + v.getTranslationX() + v.getWidth() / 2f;
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
}
