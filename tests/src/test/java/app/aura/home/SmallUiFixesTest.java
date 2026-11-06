package app.aura.home;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Bitmap;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowInputMethodManager;
import org.robolectric.shadows.ShadowLooper;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.robolectric.Shadows.shadowOf;

/**
 * Find shut at once leaves no keyboard behind, an empty app switcher still holds the focus, and
 * nothing keeps animating a view that has stopped or gone.
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SmallUiFixesTest {
    private static void run(long ms) {
        for (long t = 0; t < ms; t += 16) ShadowLooper.idleMainLooper(16, TimeUnit.MILLISECONDS);
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object o, String name) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(o);
    }

    private static FrameLayout screen(Activity act) {
        FrameLayout root = new FrameLayout(act);
        act.setContentView(root);
        return root;
    }

    private static FrameLayout.LayoutParams full() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private static boolean inside(View v, View group) {
        for (ViewParent p = v.getParent(); p != null; p = p.getParent()) if (p == group) return true;
        return false;
    }

    private static final class FindHost implements Find.Host {
        @Override public List<AppRepo.App> apps() { return Collections.emptyList(); }
        @Override public List<AppRepo.App> suggested() { return Collections.emptyList(); }
        @Override public Tiles.Painter art(AppRepo.App a) { return Tiles.launch(a); }
        @Override public boolean glass() { return false; }
        @Override public Backdrop backdrop() { return null; }
        @Override public void open(AppRepo.App a, View from) { }
        @Override public void everywhere(String text) { }
    }

    private static final class SwitcherHost implements Switcher.Host {
        private final Activity act;

        SwitcherHost(Activity a) {
            act = a;
        }

        // every recent app filtered out, as a kids' profile does
        @Override public AppRepo.App find(String pkg) { return null; }
        @Override public Card card(AppRepo.App a) { return new Card(act, Tiles.launch(a), a.color, 22); }
        @Override public void launch(AppRepo.App a, View from) { }
        @Override public void close(AppRepo.App a) { }
        @Override public void closeAll(List<AppRepo.App> all) { }
        @Override public void cleared(int count) { }
        @Override public Bitmap shot(String pkg) { return null; }
    }

    @Test
    public void findShutAtOnceLeavesNoKeyboard() {
        Activity act = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = screen(act);
        Find find = new Find(act, new FindHost());
        root.addView(find, full());
        run(100);
        ShadowInputMethodManager imm = shadowOf(act.getSystemService(InputMethodManager.class));

        // a letter on the grid, then Back before the keyboard's turn came: it never comes up over home
        find.open("n");
        find.close();
        run(500);
        Assert.assertFalse("no keyboard once Find is shut", imm.isSoftInputVisible());

        // left open, it does come up, and goes with Find
        find.open("n");
        run(300);
        Assert.assertTrue(imm.isSoftInputVisible());
        find.close();
        run(300);
        Assert.assertFalse(imm.isSoftInputVisible());
    }

    @Test
    public void emptySwitcherStillHoldsTheFocus() {
        Activity act = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = screen(act);
        Switcher s = new Switcher(act, null, new SwitcherHost(act));
        root.addView(s, full());
        run(100);
        // a TV has no touch mode, which keeps the focus from anything not made for touch
        root.requestFocusFromTouch();

        LinkedHashMap<String, Long> recent = new LinkedHashMap<>();
        recent.put("com.hidden.app", System.currentTimeMillis());
        s.open(recent, null);
        run(100);
        Assert.assertTrue(s.isOpen());
        Assume.assumeFalse("the simulated TV left touch mode", root.isInTouchMode());
        // Robolectric's Activity.getCurrentFocus() only echoes setCurrentFocus(); the platform asks the window
        View focus = act.getWindow().getCurrentFocus();
        Assert.assertNotNull("the focus isn't lost", focus);
        Assert.assertTrue("it rests in the switcher, on something on show", inside(focus, s) && focus.isShown());
        Assert.assertTrue("the arrows are taken, with nowhere to go", s.key(KeyEvent.KEYCODE_DPAD_RIGHT));

        s.close(null);
        run(400);
        Assert.assertFalse(s.isOpen());
        Assert.assertEquals(View.GONE, s.getVisibility());
    }

    @Test
    public void stoppedStackLeavesNoGlideRunning() throws Exception {
        Activity act = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = screen(act);
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
        run(100);

        stack.flip();
        Object dots = field(stack, "dots");
        ValueAnimator glide = field(dots, "glide");
        Assert.assertTrue("the pill glides to the next card", glide.isRunning());
        // Home left mid-glide
        stack.stop();
        Assert.assertFalse("nothing left redrawing", glide.isRunning());
        float pos = field(dots, "pos");
        Assert.assertEquals("the pill stands on its card's dot", 1f, pos, 0.001f);
    }

    @Test
    public void shadeAndLightStopWhenTheirViewsGo() throws Exception {
        Activity act = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = screen(act);
        Scrim scrim = new Scrim(act, false);
        Bloom bloom = new Bloom(act);
        root.addView(scrim, full());
        root.addView(bloom, full());
        run(100);
        scrim.adapt(0.1f, 0.9f, 2800);
        bloom.moveTo(0.6f, 0.4f, 900);
        bloom.setColor(0xFFCC3355, 700);
        bloom.setStrength(0.4f, 2000);
        ValueAnimator shade = field(scrim, "anim"), move = field(bloom, "moveAnim"),
                color = field(bloom, "colorAnim"), strength = field(bloom, "strengthAnim");
        ValueAnimator[] all = {shade, move, color, strength};
        for (ValueAnimator a : all) Assert.assertTrue(a.isRunning());

        // the home screen torn down while they move (a language change starts it again)
        root.removeView(scrim);
        root.removeView(bloom);
        for (ValueAnimator a : all) Assert.assertFalse("no animation outlives its view", a.isRunning());
    }

    @Test
    public void widgetGoneStopsItsRim() throws Exception {
        Activity act = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = screen(act);
        Widget w = new Widget(act, null);
        root.addView(w, new FrameLayout.LayoutParams(400, 200));
        run(100);
        w.requestFocusFromTouch();
        run(500);
        Assume.assumeTrue("the simulated TV left touch mode", w.isFocused());

        // removed while focused: losing the focus starts its rim fading, which mustn't outlive it
        root.removeView(w);
        ValueAnimator anim = field(w, "anim");
        Assert.assertFalse(anim.isRunning());
    }
}
