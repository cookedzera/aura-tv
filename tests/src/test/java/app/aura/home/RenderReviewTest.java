package app.aura.home;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.os.SystemClock;
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
import org.robolectric.shadows.ShadowChoreographer;
import org.robolectric.shadows.ShadowLooper;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Rendering and memory: an aerial dipping away still counts as on screen, is always let
 * go and said to be gone, and what's chosen meanwhile loads only once it's gone; a tile with no room
 * for its picture is painted again a few seconds on; the shade baked into Light mode's photograph lies
 * where the photograph stands; and a tile refocused at once shows its name again.
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class RenderReviewTest {
    private boolean wasLight, wasCalm;

    @Before
    public void setUp() throws Exception {
        wasLight = Ui.light;
        wasCalm = Backdrop.calm;
        freshAnimationClock();
        // a long rest before each test's first move, and no card that has just lost focus
        Card.movedAt = Long.MIN_VALUE / 2;
        Field left = Card.class.getDeclaredField("leftAt");
        left.setAccessible(true);
        left.setLong(null, Long.MIN_VALUE / 2);
        ShadowChoreographer.setPaused(true);
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16));
    }

    @After
    public void tearDown() throws Exception {
        Ui.light = wasLight;
        Backdrop.calm = wasCalm;
        freshAnimationClock();
    }

    /** The animation clock's thread state outlives a test: a spring started in a frame would wait for its old time. */
    private static void freshAnimationClock() throws Exception {
        ThreadLocal<?> state = field(android.view.animation.AnimationUtils.class, null, "sAnimationState");
        state.remove();
    }

    private static void run(long ms) {
        for (long t = 0; t < ms; t += 16) ShadowLooper.idleMainLooper(16, TimeUnit.MILLISECONDS);
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Class<?> c, Object o, String name) throws Exception {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(o);
    }

    private static <T> T field(Object o, String name) throws Exception {
        return field(o.getClass(), o, name);
    }

    private static void set(Object o, String name, Object v) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(o, v);
    }

    // ---- aerials -----------------------------------------------------------------------------------

    /** What an aerial view has said. */
    private static final class Said implements AerialView.Listener {
        int shown, lost;

        @Override
        public void shown(Aerials.Video v) {
            shown++;
        }

        @Override
        public void lost() {
            lost++;
        }
    }

    /** Light mode (one video at a time), allowed over a metered network: Robolectric's network is a phone's. */
    private static Activity aerialScreen() {
        Activity act = Robolectric.buildActivity(Activity.class).setup().get();
        Ui.init(act);
        Prefs.device(act).edit().putString(Perf.KEY, "on").commit();
        Prefs.of(act).edit().putBoolean(Prefs.AERIALS_METERED, true).commit();
        return act;
    }

    /** The video loading has drawn its first frame, as its player says, and fills the screen once the veil has dipped. */
    private static Object firstFrame(AerialView av) throws Exception {
        Object coming = field(AerialView.class, av, "coming");
        Assert.assertNotNull("a video loading", coming);
        Method ready = AerialView.class.getDeclaredMethod("ready", coming.getClass());
        ready.setAccessible(true);
        ready.invoke(av, coming);
        run(800);
        Assert.assertSame("on screen", coming, field(AerialView.class, av, "shown"));
        return coming;
    }

    /** The surface a video plays on is gone from the view. */
    private static boolean letGo(Object slot) throws Exception {
        View surface = field(slot, "view");
        return surface.getParent() == null;
    }

    @Test
    public void aSceneLeftWhileItsVideoDipsAwayLetsItGoAndHomesPhotographMovesAgain() throws Exception {
        Activity act = aerialScreen();
        int[] gone = {0}, lost = {0};
        MusicScene scene = new MusicScene(act, () -> gone[0]++);
        scene.onLost = () -> lost[0]++;
        AerialView av = field(MusicScene.class, scene, "aerial");
        av.next("mix");
        Object first = firstFrame(av);

        // a new song: on LOW the video on screen dips away before the next one loads, and home is pressed meanwhile
        av.next("mix");
        run(200);
        Assert.assertTrue("still on screen as it dips away", av.showing());
        scene.stop();
        run(800);
        Assert.assertTrue("home's photograph told to move again", lost[0] > 0);
        Assert.assertTrue("its video let go", letGo(first));
        Assert.assertEquals("only the veil left", 1, av.getChildCount());
        Assert.assertEquals(1, gone[0]);
    }

    @Test
    public void aPickWhileTheLastDipsAwayLoadsOnlyOnceItsGone() throws Exception {
        Activity act = aerialScreen();
        Said said = new Said();
        AerialView av = new AerialView(act, true, said);
        av.next("mix");
        Object first = firstFrame(av);
        Assert.assertEquals(1, said.shown);

        // two new songs in a row while the first video dips away
        av.next("mix");
        run(100);
        av.next("mix");
        String last = field(AerialView.class, av, "lastUrl");
        Assert.assertNull("no second decoder beside the one dipping away", field(AerialView.class, av, "coming"));
        Assert.assertEquals("the veil and the video dipping away", 2, av.getChildCount());
        run(600);
        Assert.assertEquals("gone, and said so", 1, said.lost);
        Assert.assertTrue(letGo(first));
        Object next = field(AerialView.class, av, "coming");
        Assert.assertNotNull("then the next one loads", next);
        Assert.assertEquals("the one chosen last", last, ((Aerials.Video) field(next, "v")).url);
        Assert.assertEquals(2, av.getChildCount());

        // let go mid-dip (the screensaver ending): the one dipping away goes too, and nothing loads after it
        Object second = firstFrame(av);
        av.next("mix");
        run(100);
        av.release();
        Assert.assertEquals(2, said.lost);
        Assert.assertTrue(letGo(second));
        Assert.assertFalse(av.showing());
        run(1000);
        Assert.assertNull("nothing loads after it", field(AerialView.class, av, "coming"));
        Assert.assertEquals(1, av.getChildCount());
    }

    @Test
    public void aVideoThatStopsOnScreenIsLetGoBeforeTheNextLoads() throws Exception {
        Activity act = aerialScreen();
        Said said = new Said();
        AerialView av = new AerialView(act, true, said);
        av.next("mix");
        Object first = firstFrame(av);

        // the network went: the picture froze, and a new song comes as it dips away
        Method failed = AerialView.class.getDeclaredMethod("failed", first.getClass(), String.class, boolean.class);
        failed.setAccessible(true);
        failed.invoke(av, first, "the picture froze", false);
        Assert.assertTrue("still on screen as it dips away", av.showing());
        av.next("mix");
        Assert.assertNull("nothing loads beside it", field(AerialView.class, av, "coming"));
        run(500);
        Assert.assertEquals(1, said.lost);
        Assert.assertTrue(letGo(first));
        Assert.assertNotNull("the one chosen meanwhile loads now", field(AerialView.class, av, "coming"));

        // dimmed away while one dips: it's let go once the screen is dark, and nothing follows it
        Object second = firstFrame(av);
        av.next("mix");
        av.dimOut(300);
        run(400);
        Assert.assertEquals(2, said.lost);
        Assert.assertTrue(letGo(second));
        Assert.assertNull(field(AerialView.class, av, "coming"));
    }

    // ---- tiles -------------------------------------------------------------------------------------

    @Test
    public void aTileWithNoRoomForItsPictureIsPaintedAgainAFewSecondsOn() {
        Activity act = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(act);
        act.setContentView(root);
        Tiles.Painter p = (cv, w, h) -> cv.drawColor(0xFF2266AA);
        Card card = new Card(act, p, 0xFF2266AA, 14);
        card.deferred = true;
        root.addView(card, new FrameLayout.LayoutParams(200, 112));
        run(100);
        Assert.assertTrue(card.wantsArt());

        // no room for it on the painter thread (a 1 GB TV): its colour while the caches make space
        card.starve();
        Assert.assertFalse(card.wantsArt());
        run(4800);
        Assert.assertFalse("not straight back into the same squeeze", card.wantsArt());
        run(400);
        Assert.assertTrue("on screen, where it's never let go, it's tried again", card.wantsArt());
        card.take(Card.paintBehind(p, 200, 112, 1f, true));
        Assert.assertNotNull(card.art());
        Assert.assertFalse(card.wantsArt());
    }

    private static FrameLayout screen() {
        Activity act = Robolectric.buildActivity(Activity.class).setup().get();
        Ui.init(act);
        FrameLayout root = new FrameLayout(act);
        act.setContentView(root);
        // the window's first layout hands focus to the first focusable view: let it pass before any tile exists
        run(32);
        return root;
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

    /** How strongly a cell's name shows: by its colour on HIGH, by its view's alpha on LOW. */
    private static float named(Cell c) {
        return Ui.light ? c.label.getAlpha() : (c.label.getCurrentTextColor() >>> 24) / 255f;
    }

    @Test
    public void aTileRefocusedAtOnceShowsItsNameAgain() {
        for (boolean low : new boolean[]{false, true}) {
            Ui.light = low;
            Card.movedAt = Long.MIN_VALUE / 2;
            String tier = low ? "LOW" : "HIGH";
            FrameLayout root = screen();
            Cell one = cell(root, "Netflix", 0), two = cell(root, "Plex", Ui.dp(200));
            run(100);
            Assert.assertTrue(one.card.requestFocus());
            run(800);
            Assert.assertEquals(tier + ": settled exactly at its size", 1f, one.card.risen(), 0f);
            Assert.assertEquals(tier, 1f, named(one), 0.01f);

            // focus leaves and comes straight back, before a frame of its fall
            Assert.assertTrue(two.card.requestFocus());
            Assert.assertTrue(one.card.requestFocus());
            run(400);
            Assert.assertEquals(tier + ": its name shows again", 1f, named(one), 0.01f);
        }
    }

    // ---- the baked shade -----------------------------------------------------------------------------

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

    /** The largest difference between the two at the headline's side, the floor under the apps, the middle and the top edge. */
    private static double gap(Bitmap want, Bitmap got) {
        int w = want.getWidth(), h = want.getHeight();
        int[][] spots = {{w / 10, h / 2}, {w / 2, h * 9 / 10}, {w / 2, h / 2}, {w * 3 / 4, h / 10}};
        double most = 0;
        for (int[] p : spots) most = Math.max(most, Math.abs(mean(want, p[0], p[1]) - mean(got, p[0], p[1])));
        return most;
    }

    /** A photograph of this grey dimmed as the photograph is at {@code defocus}, under the shade as it lies on the screen. */
    private static Bitmap shaded(Scrim scrim, int grey, float defocus, int w, int h, float lumaTop, float lumaBottom) {
        int level = Math.round(64 * (1f - 0.30f * defocus)), g = Math.round(255 * level / 64f);
        Bitmap out = solid(w, h, Math.round(grey * g / 255f));
        scrim.bake(new Canvas(out), w, h, lumaTop, lumaBottom);
        return out;
    }

    /** A photograph of grey 200 whose drift zooms out across the frame, with a resting copy made before Light mode. */
    private static Object slide(int w, int h, float lumaTop, float lumaBottom) throws Exception {
        Constructor<?> make = Class.forName("app.aura.home.Backdrop$Slide").getDeclaredConstructor();
        make.setAccessible(true);
        Object s = make.newInstance();
        set(s, "sharp", solid(w, h, 200));
        set(s, "soft", solid(w, h, 200));
        set(s, "steady", solid(w, h, 200));
        set(s, "mixed", 0.4f);
        set(s, "sw", w);
        set(s, "sh", h);
        set(s, "alpha", 1f);
        set(s, "lumaTop", lumaTop);
        set(s, "lumaBottom", lumaBottom);
        set(s, "s0", 1.20f);
        set(s, "s1", 1.05f);
        set(s, "x0", 0.8f);
        set(s, "x1", -0.8f);
        set(s, "y0", 0.5f);
        set(s, "y1", -0.5f);
        return s;
    }

    private static void place(Backdrop b, Object s) throws Exception {
        Method place = Backdrop.class.getDeclaredMethod("place", s.getClass(), long.class);
        place.setAccessible(true);
        place.invoke(b, s, SystemClock.uptimeMillis());
    }

    private static float scale(Object s) throws Exception {
        Matrix m = field(s, "m");
        float[] v = new float[9];
        m.getValues(v);
        return v[Matrix.MSCALE_X];
    }

    @Test
    public void theShadeBakedWhenLightModeComesOnLiesWhereThePhotographStopped() throws Exception {
        Activity act = Robolectric.buildActivity(Activity.class).setup().get();
        int w = 320, h = 180;
        float lumaTop = 0.7f, lumaBottom = 0.4f;
        Scrim scrim = new Scrim(act, false);
        lay(scrim, w, h);
        scrim.adapt(lumaTop, lumaBottom, 0);
        Backdrop.calm = true;
        Backdrop b = new Backdrop(act);
        try {
            lay(b, w, h);
            b.setShade(scrim);
            Object s = slide(w, h, lumaTop, lumaBottom);
            // Light mode came on 70 % of the way through the drift, which stands still from then on
            long still = SystemClock.uptimeMillis();
            set(b, "stillSince", still);
            set(s, "drift0", still - 67_200L);
            set(b, "current", s);

            // the resting copy made again, carrying the shade, on the loader
            b.setSteadyDefocus(0.4f);
            for (int i = 0; i < 300 && (Boolean) field(s, "remaking"); i++) {
                ShadowLooper.idleMainLooper(16, TimeUnit.MILLISECONDS);
                Thread.sleep(5);
            }
            Assert.assertTrue("made again carrying the shade", (Boolean) field(s, "baked"));
            set(b, "defocus", 0.4f);
            place(b, s);
            Bitmap want = shaded(scrim, 200, 0.4f, w, h, lumaTop, lumaBottom);
            double off = gap(want, frame(b, w, h));
            Assert.assertTrue("the shade lies where the live one would: " + off, off <= 3.0);

            // baked where the drift began, as before, the shade would lie elsewhere
            Method steady = Backdrop.class.getDeclaredMethod("steady", s.getClass(), Bitmap.class, float.class, Scrim.class,
                    int.class, int.class, float.class, float.class);
            steady.setAccessible(true);
            set(s, "steady", steady.invoke(b, s, solid(w, h, 200), 0.4f, scrim, w, h, 0f, 0f));
            Assert.assertTrue("this photograph's drift shows a misplaced shade", gap(want, frame(b, w, h)) > 3.0);
        } finally {
            b.release();
        }
    }

    @Test
    public void aPhotographCarryingTheShadeHoldsStillThroughTheIntro() throws Exception {
        Activity act = Robolectric.buildActivity(Activity.class).setup().get();
        int w = 320, h = 180;
        Backdrop.calm = true;
        Backdrop b = new Backdrop(act);
        try {
            lay(b, w, h);
            Object s = slide(w, h, 0.5f, 0.5f);
            set(s, "drift0", SystemClock.uptimeMillis());
            place(b, s);
            float rest = scale(s);

            // the boot screen dissolving: the intro plays again, its settle zooming the photograph in from 1.1x
            b.restartIntro();
            set(b, "intro", 0.3f);
            set(s, "baked", true);
            place(b, s);
            Assert.assertEquals("its shade lies on the screen: it holds still", rest, scale(s), 1e-4f);
            set(s, "baked", false);
            place(b, s);
            Assert.assertTrue("with the shade drawn live, it settles as before: " + scale(s), scale(s) > rest * 1.04f);
        } finally {
            b.release();
        }
    }
}
