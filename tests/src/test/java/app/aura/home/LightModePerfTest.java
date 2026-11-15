package app.aura.home;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.widget.FrameLayout;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.robolectric.Shadows.shadowOf;

/** Checks that Light mode optimization gates stop off-budget work while leaving HIGH untouched. */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30, application = AuraApp.class, qualifiers = "w960dp-h540dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class LightModePerfTest {
    private Context c;
    private boolean prevLight;
    private final List<org.robolectric.android.controller.ActivityController<HomeActivity>> controllers = new ArrayList<>();

    private static void run(long ms) {
        for (long t = 0; t < ms; t += 16) ShadowLooper.idleMainLooper(16, TimeUnit.MILLISECONDS);
    }

    private static Object call(Object o, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = o.getClass().getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(o, args);
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

    private void install(String pkg, String label, int category) {
        ApplicationInfo ai = new ApplicationInfo();
        ai.packageName = pkg;
        ai.name = label;
        ai.nonLocalizedLabel = label;
        ai.category = category;
        PackageInfo pi = new PackageInfo();
        pi.packageName = pkg;
        pi.applicationInfo = ai;
        shadowOf(c.getPackageManager()).installPackage(pi);
    }

    @Before
    public void setUp() {
        c = RuntimeEnvironment.getApplication();
        prevLight = Ui.light;
        Ui.init(c);
    }

    @After
    public void tearDown() {
        for (org.robolectric.android.controller.ActivityController<HomeActivity> ac : controllers) {
            try { ac.pause().stop().destroy(); } catch (Throwable ignored) {}
        }
        controllers.clear();
        Ui.light = prevLight;
    }

    @Test
    public void tabStripSnapsLitAndQuantizesDockInLightMode() throws Exception {
        Ui.light = true;
        TabStrip lightStrip = new TabStrip(c, 13f, 12, 6, new TabStrip.Listener() {
            @Override public void pick(int k) {}
            @Override public void hold(int k, View tab) {}
        });
        lightStrip.set(Arrays.asList("All apps", "Favorites", "Video"));
        lightStrip.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY));
        lightStrip.layout(0, 0, 800, 100);
        lightStrip.setDocked(0.10f);
        Assert.assertEquals(0f, ((Number) field(lightStrip, "dockFrac")).floatValue(), 1e-4f);
        lightStrip.setDocked(0.30f);
        Assert.assertEquals(0.25f, ((Number) field(lightStrip, "dockFrac")).floatValue(), 1e-4f);
        FrameLayout host = new FrameLayout(c);
        host.addView(lightStrip);
        lightStrip.tab(0).requestFocus();
        ValueAnimator litAnim = field(lightStrip, "litAnim");
        Assert.assertFalse("Light mode snaps TabStrip litFrac without starting litAnim", litAnim.isRunning());
        Assert.assertEquals(1f, ((Number) field(lightStrip, "litFrac")).floatValue(), 1e-4f);

        Ui.light = false;
        TabStrip highStrip = new TabStrip(c, 13f, 12, 6, new TabStrip.Listener() {
            @Override public void pick(int k) {}
            @Override public void hold(int k, View tab) {}
        });
        highStrip.set(Arrays.asList("All apps", "Favorites"));
        highStrip.setDocked(0.13f);
        Assert.assertEquals(0.13f, ((Number) field(highStrip, "dockFrac")).floatValue(), 1e-4f);
        highStrip.tab(0).requestFocus();
        ValueAnimator highLit = field(highStrip, "litAnim");
        Assert.assertTrue("HIGH mode still runs TabStrip litAnim", highLit.isRunning());
        highLit.cancel();
    }

    @Test
    public void smartStackDotsSnapsLitAndUsesShortGlideInLightMode() throws Exception {
        Ui.light = true;
        SmartStack stack = new SmartStack(c, 26f);
        View dots = field(stack, "dots");
        call(dots, "setCount", new Class<?>[]{int.class}, 3);
        call(dots, "setLit", new Class<?>[]{boolean.class}, true);
        ValueAnimator litAnim = field(dots, "litAnim");
        Assert.assertNull("Light mode snaps Dots litFrac without creating litAnim", litAnim);
        Assert.assertEquals(1f, ((Number) field(dots, "litFrac")).floatValue(), 1e-4f);
        call(dots, "moveTo", new Class<?>[]{int.class, boolean.class}, 1, true);
        ValueAnimator glide = field(dots, "glide");
        Assert.assertNotNull(glide);
        Assert.assertEquals(200L, glide.getDuration());
        glide.cancel();

        Ui.light = false;
        SmartStack highStack = new SmartStack(c, 26f);
        View highDots = field(highStack, "dots");
        call(highDots, "setCount", new Class<?>[]{int.class}, 3);
        call(highDots, "setLit", new Class<?>[]{boolean.class}, true);
        ValueAnimator highLit = field(highDots, "litAnim");
        Assert.assertNotNull(highLit);
        Assert.assertTrue("HIGH mode runs Dots litAnim", highLit.isRunning());
        highLit.cancel();
        call(highDots, "moveTo", new Class<?>[]{int.class, boolean.class}, 1, true);
        ValueAnimator highGlide = field(highDots, "glide");
        Assert.assertEquals(560L, highGlide.getDuration());
        highGlide.cancel();
    }

    @Test
    public void scrollGlassSkipsMidFlightRepaintInLightMode() throws Exception {
        install("demo.a1", "Alpha", ApplicationInfo.CATEGORY_VIDEO);
        install("demo.a2", "Beta", ApplicationInfo.CATEGORY_AUDIO);
        Prefs.device(c).edit()
                .putString("set_light", "on")
                .putString("set_layout", "aura")
                .commit();

        org.robolectric.android.controller.ActivityController<HomeActivity> ac = Robolectric.buildActivity(HomeActivity.class).create().start().resume();
        controllers.add(ac);
        HomeActivity home = ac.get();
        run(300);

        Assert.assertTrue("Ui.light is enabled", Ui.light);
        Assert.assertFalse("AllApps.rich is off in Light mode", AllApps.rich(home));
        set(home, "scrollBlurY", 0f);
        call(home, "repaintScrollGlass", new Class<?>[]{float.class, boolean.class}, -Ui.dpf(12), false);
        Assert.assertEquals("Sub-24dp scroll glass repaint skipped in Light mode", 0f,
                ((Number) field(home, "scrollBlurY")).floatValue(), 1e-4f);
        call(home, "repaintScrollGlass", new Class<?>[]{float.class, boolean.class}, -Ui.dpf(28), false);
        Assert.assertEquals("24dp+ scroll glass repaints mid-scroll in Light mode", -Ui.dpf(28),
                ((Number) field(home, "scrollBlurY")).floatValue(), 1e-4f);
        call(home, "repaintScrollGlass", new Class<?>[]{float.class, boolean.class}, -Ui.dpf(40), true);
        Assert.assertEquals("Settled scroll glass repaints once in Light mode", -Ui.dpf(40),
                ((Number) field(home, "scrollBlurY")).floatValue(), 1e-4f);
    }

    @Test
    public void settingsSkipsVeilFadeAndHidesHomeImmediatelyInLightMode() throws Exception {
        install("demo.a1", "Alpha", ApplicationInfo.CATEGORY_VIDEO);
        Profiles.forget();
        Profiles.use(c, Profiles.MAIN);
        Prefs.of(c).edit().clear().putString(Prefs.LAYOUT, "aura").commit();
        Prefs.device(c).edit()
                .putBoolean("welcomed", true)
                .putString("set_light", "on")
                .commit();
        org.robolectric.shadows.ShadowChoreographer.setPaused(true);
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(java.time.Duration.ofMillis(16));
        try {
            Field w = HomeActivity.class.getDeclaredField("warm");
            w.setAccessible(true);
            w.setBoolean(null, true);

            org.robolectric.android.controller.ActivityController<HomeActivity> ac = Robolectric.buildActivity(HomeActivity.class).setup();
            controllers.add(ac);
            HomeActivity home = ac.get();
            for (int t = 0; t < 300; t += 16) org.robolectric.shadows.ShadowLooper.idleMainLooper(16, java.util.concurrent.TimeUnit.MILLISECONDS);

            SettingsPanel settings = field(home, "settings");
            View homeView = field(home, "home");
            Assert.assertEquals(View.VISIBLE, homeView.getVisibility());

            // Light mode: Settings frost arrives whole and home hides immediately on open()
            Ui.light = true;
            call(home, "openSettingsNow", new Class<?>[]{String.class}, new Object[]{null});
            Assert.assertEquals("Light mode sets Settings panel opaque on frame 0", 1f, settings.panel.getAlpha(), 1e-4f);
            Assert.assertEquals("Light mode keeps Settings veil opaque on frame 0", 1f, settings.veil.getAlpha(), 1e-4f);
            Assert.assertEquals("Light mode hides home views behind Settings immediately", View.INVISIBLE, homeView.getVisibility());

            // Light mode: close keeps veil opaque inside panel so veil and columns fade together over Ui.CLOSE
            call(home, "closeSettings", new Class<?>[0]);
            Assert.assertEquals("Light mode fades Settings veil with panel on close", 1f, settings.veil.getAlpha(), 1e-4f);
            Assert.assertEquals(Ui.CLOSE, settings.panel.animate().getDuration());
            Assert.assertEquals(View.VISIBLE, homeView.getVisibility());
            for (int t = 0; t < 300; t += 16) org.robolectric.shadows.ShadowLooper.idleMainLooper(16, java.util.concurrent.TimeUnit.MILLISECONDS);

            // HIGH mode: unchanged behavior (panel fades from 0, veil stays intact during panel close)
            Prefs.device(c).edit().putString("set_light", "off").commit();
            Ui.light = false;
            call(home, "applyLight", new Class<?>[0]);
            call(home, "openSettingsNow", new Class<?>[]{String.class}, new Object[]{null});
            Assert.assertEquals("HIGH mode fades Settings panel from 0", 0f, settings.panel.getAlpha(), 1e-4f);
            Assert.assertEquals("HIGH mode waits 300 ms before hiding home", View.VISIBLE, homeView.getVisibility());
            for (int t = 0; t < 350; t += 16) org.robolectric.shadows.ShadowLooper.idleMainLooper(16, java.util.concurrent.TimeUnit.MILLISECONDS);
            Assert.assertEquals(View.INVISIBLE, homeView.getVisibility());
            call(home, "closeSettings", new Class<?>[0]);
            Assert.assertEquals("HIGH mode leaves Settings veil alpha at 1 inside panel fade", 1f, settings.veil.getAlpha(), 1e-4f);
            for (int t = 0; t < 300; t += 16) org.robolectric.shadows.ShadowLooper.idleMainLooper(16, java.util.concurrent.TimeUnit.MILLISECONDS);
        } finally {
            org.robolectric.shadows.ShadowChoreographer.setPaused(false);
        }
    }

    private static <T extends View> T findViewByType(View root, Class<T> cls) {
        if (cls.isInstance(root)) return cls.cast(root);
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                T found = findViewByType(g.getChildAt(i), cls);
                if (found != null) return found;
            }
        }
        return null;
    }

    @Test
    public void widgetFocusTopShelfAndSmartStackUseFastPathsInLightMode() throws Exception {
        install("demo.a1", "Alpha", ApplicationInfo.CATEGORY_VIDEO);
        install("demo.a2", "Beta", ApplicationInfo.CATEGORY_AUDIO);
        Profiles.forget();
        Profiles.use(c, Profiles.MAIN);
        Prefs.of(c).edit().clear().putString(Prefs.LAYOUT, "aura").commit();
        Prefs.device(c).edit()
                .putBoolean("welcomed", true)
                .putString("set_light", "on")
                .commit();
        org.robolectric.shadows.ShadowChoreographer.setPaused(true);
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(java.time.Duration.ofMillis(16));
        try {
            Field w = HomeActivity.class.getDeclaredField("warm");
            w.setAccessible(true);
            w.setBoolean(null, true);

            org.robolectric.android.controller.ActivityController<HomeActivity> ac = Robolectric.buildActivity(HomeActivity.class).setup();
            controllers.add(ac);
            HomeActivity home = ac.get();
            for (int t = 0; t < 300; t += 16) org.robolectric.shadows.ShadowLooper.idleMainLooper(16, java.util.concurrent.TimeUnit.MILLISECONDS);

            @SuppressWarnings("unchecked")
            java.util.List<Widget> widgets = field(home, "widgets");
            Assert.assertFalse(widgets.isEmpty());
            Widget widget = widgets.get(0);
            ValueAnimator wAnim = field(widget, "anim");

            // Widget focus/unfocus in Light mode
            Ui.light = true;
            call(widget, "onFocusChanged", new Class<?>[]{boolean.class, int.class, android.graphics.Rect.class},
                    true, View.FOCUS_RIGHT, null);
            Assert.assertEquals(Ui.LOW_FOCUS_IN, wAnim.getDuration());
            Assert.assertEquals(Ui.LOW_FOCUS_IN, widget.animate().getDuration());
            call(widget, "onFocusChanged", new Class<?>[]{boolean.class, int.class, android.graphics.Rect.class},
                    false, View.FOCUS_RIGHT, null);
            Assert.assertEquals(Ui.APP_FOCUS_OUT, wAnim.getDuration());
            Assert.assertEquals(Ui.APP_FOCUS_OUT, widget.animate().getDuration());

            // TopShelf overlapping rendering + show/hide + HomeActivity widget return in Light mode
            TopShelf shelf = field(home, "shelf");
            View shelfRow = field(shelf, "row");
            Assert.assertFalse("TopShelf skips offscreen alpha layer in Light mode", shelf.hasOverlappingRendering());
            Assert.assertFalse("TopShelf row skips offscreen alpha layer in Light mode", shelfRow.hasOverlappingRendering());

            java.lang.reflect.Constructor<TopShelf.Ready> rc = TopShelf.Ready.class.getDeclaredConstructor(
                    java.util.List.class, android.graphics.Bitmap[].class, int[].class, int.class,
                    android.graphics.drawable.Drawable.class, String.class);
            rc.setAccessible(true);
            TopShelf.Ready ready = rc.newInstance(
                    java.util.Collections.emptyList(), new android.graphics.Bitmap[0], new int[0],
                    0xFF223344, null, "sig");
            shelf.show("demo.a1", ready, it -> {}, null);
            Assert.assertEquals(Ui.LOW_FOCUS_IN, shelf.animate().getDuration());
            Assert.assertEquals(Ui.EASE_DECEL, shelf.animate().getInterpolator());
            call(home, "hideShelf", new Class<?>[0]);
            Assert.assertEquals(Ui.CLOSE, shelf.animate().getDuration());
            Assert.assertEquals(Ui.LOW_FOCUS_IN, widget.animate().getDuration());
            Assert.assertEquals(0L, widget.animate().getStartDelay());
            Assert.assertEquals(Ui.EASE_DECEL, widget.animate().getInterpolator());

            // SmartStack flip and RGB_565 fallback art in Light mode
            SmartStack stack = findViewByType(home.getWindow().getDecorView(), SmartStack.class);
            Assert.assertNotNull(stack);
            @SuppressWarnings("unchecked")
            java.util.List<SmartStack.Slide> slides = field(stack, "slides");
            while (slides.size() < 2) {
                SmartStack.Slide s = new SmartStack.Slide();
                s.title = "Slide " + slides.size();
                s.color = 0xFF336699;
                slides.add(s);
            }
            call(stack, "show", new Class<?>[]{int.class, int.class}, 1, 1);
            Object[] pages = field(stack, "pages");
            int front = ((Number) field(stack, "front")).intValue();
            View inPage = (View) pages[front];
            View outPage = (View) pages[1 - front];
            View inArt = field(inPage, "art");
            Assert.assertEquals(200L, inPage.animate().getDuration());
            Assert.assertEquals(0L, inPage.animate().getStartDelay());
            Assert.assertEquals(Ui.CLOSE, outPage.animate().getDuration());
            Assert.assertEquals(0f, inArt.getTranslationY(), 1e-4f);
            android.graphics.Bitmap lightMark = (android.graphics.Bitmap) call(inPage, "markArt",
                    new Class<?>[]{SmartStack.Slide.class, int.class, int.class}, slides.get(0), 32, 32);
            Assert.assertEquals(android.graphics.Bitmap.Config.RGB_565, lightMark.getConfig());

            // HIGH mode: unchanged behavior
            Ui.light = false;
            call(widget, "onFocusChanged", new Class<?>[]{boolean.class, int.class, android.graphics.Rect.class},
                    true, View.FOCUS_RIGHT, null);
            Assert.assertEquals(300L, wAnim.getDuration());
            Assert.assertEquals(460L, widget.animate().getDuration());
            call(widget, "onFocusChanged", new Class<?>[]{boolean.class, int.class, android.graphics.Rect.class},
                    false, View.FOCUS_RIGHT, null);
            Assert.assertEquals(200L, wAnim.getDuration());
            Assert.assertEquals(240L, widget.animate().getDuration());

            Assert.assertTrue(shelf.hasOverlappingRendering());
            shelf.show("demo.a1", ready, it -> {}, null);
            Assert.assertEquals(420L, shelf.animate().getDuration());
            Assert.assertEquals(Ui.EMPHASIZED, shelf.animate().getInterpolator());
            call(home, "hideShelf", new Class<?>[0]);
            Assert.assertEquals(240L, shelf.animate().getDuration());
            Assert.assertEquals(400L, widget.animate().getDuration());
            Assert.assertEquals(90L, widget.animate().getStartDelay());
            Assert.assertEquals(Ui.EMPHASIZED, widget.animate().getInterpolator());

            call(stack, "show", new Class<?>[]{int.class, int.class}, 0, 1);
            front = ((Number) field(stack, "front")).intValue();
            inPage = (View) pages[front];
            outPage = (View) pages[1 - front];
            inArt = field(inPage, "art");
            Assert.assertEquals(640L, inPage.animate().getDuration());
            Assert.assertEquals(60L, inPage.animate().getStartDelay());
            Assert.assertEquals(520L, outPage.animate().getDuration());
            Assert.assertEquals(760L, inArt.animate().getDuration());
            android.graphics.Bitmap highMark = (android.graphics.Bitmap) call(inPage, "markArt",
                    new Class<?>[]{SmartStack.Slide.class, int.class, int.class}, slides.get(0), 32, 32);
            Assert.assertEquals(android.graphics.Bitmap.Config.ARGB_8888, highMark.getConfig());
        } finally {
            org.robolectric.shadows.ShadowChoreographer.setPaused(false);
        }
    }

    @Test
    public void controlCenterSliderSkipsClipPathAndPadAlignsFocusInLightMode() throws Exception {
        install("demo.a1", "Alpha", ApplicationInfo.CATEGORY_VIDEO);
        Profiles.forget();
        Profiles.use(c, Profiles.MAIN);
        Prefs.of(c).edit().clear().putString(Prefs.LAYOUT, "aura").commit();
        Prefs.device(c).edit()
                .putBoolean("welcomed", true)
                .putString("set_light", "on")
                .commit();
        org.robolectric.shadows.ShadowChoreographer.setPaused(true);
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(java.time.Duration.ofMillis(16));
        try {
            Field w = HomeActivity.class.getDeclaredField("warm");
            w.setAccessible(true);
            w.setBoolean(null, true);

            org.robolectric.android.controller.ActivityController<HomeActivity> ac = Robolectric.buildActivity(HomeActivity.class).setup();
            controllers.add(ac);
            HomeActivity home = ac.get();
            for (int t = 0; t < 300; t += 16) org.robolectric.shadows.ShadowLooper.idleMainLooper(16, java.util.concurrent.TimeUnit.MILLISECONDS);

            ControlCenter cc = field(home, "cc");
            ControlCenter.Slider slider = findViewByType(cc.panel, ControlCenter.Slider.class);
            Pad pad = findViewByType(cc.panel, Pad.class);
            Assert.assertNotNull(slider);
            Assert.assertNotNull(pad);

            slider.measure(View.MeasureSpec.makeMeasureSpec(84, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(180, View.MeasureSpec.EXACTLY));
            slider.layout(0, 0, 84, 180);

            final int[] counts = new int[2]; // [0]=clipPath, [1]=clipRect
            android.graphics.Canvas rec = new android.graphics.Canvas() {
                @Override
                public boolean clipPath(android.graphics.Path path) {
                    counts[0]++;
                    return true;
                }

                @Override
                public boolean clipRect(float left, float top, float right, float bottom) {
                    counts[1]++;
                    return true;
                }
            };

            // Light mode: Slider avoids clipPath in favor of scissor clipRect + rounded rects, shorter anim
            Ui.light = true;
            set(slider, "shown", 0.5f);
            slider.onDraw(rec);
            Assert.assertEquals("Light mode skips Canvas.clipPath on Slider", 0, counts[0]);
            Assert.assertTrue("Light mode clips Slider fill via clipRect", counts[1] > 0);
            set(slider, "shown", 0.99f);
            call(slider, "sync", new Class<?>[0]);
            ValueAnimator sAnim = field(slider, "anim");
            Assert.assertNotNull(sAnim);
            Assert.assertEquals(Ui.CTRL_SCALE_IN, sAnim.getDuration());
            sAnim.cancel();

            // Light mode: Pad.Lift skips no-op animations and aligns unfocus fill duration with scale
            Pad.Lift lift = field(pad, "lift");
            ValueAnimator filler = field(lift, "filler");
            ValueAnimator scaler = field(lift, "scaler");
            lift.focus(false);
            Assert.assertFalse("Light mode skips no-op unfocus fill animator when already 0", filler.isStarted());
            Assert.assertFalse("Light mode skips no-op unfocus scale animator when already 1", scaler.isStarted());
            lift.focus(true);
            Assert.assertTrue(filler.isStarted());
            Assert.assertEquals(Ui.CTRL_SCALE_IN, filler.getDuration());
            Assert.assertEquals(Ui.CTRL_SCALE_IN, scaler.getDuration());
            for (int t = 0; t < 200; t += 16) org.robolectric.shadows.ShadowLooper.idleMainLooper(16, java.util.concurrent.TimeUnit.MILLISECONDS);
            lift.focus(false);
            Assert.assertEquals(Ui.CTRL_SCALE_OUT, filler.getDuration());
            Assert.assertEquals(Ui.CTRL_SCALE_OUT, scaler.getDuration());
            for (int t = 0; t < 200; t += 16) org.robolectric.shadows.ShadowLooper.idleMainLooper(16, java.util.concurrent.TimeUnit.MILLISECONDS);

            // HIGH mode: unchanged behavior (Slider uses clipPath + 200ms sync; Pad uses CTRL_HILITE_OUT)
            Ui.light = false;
            counts[0] = 0;
            counts[1] = 0;
            set(slider, "shown", 0.5f);
            slider.onDraw(rec);
            Assert.assertTrue("HIGH mode still uses clipPath on Slider", counts[0] > 0);
            set(slider, "shown", 0.99f);
            call(slider, "sync", new Class<?>[0]);
            sAnim = field(slider, "anim");
            Assert.assertNotNull(sAnim);
            Assert.assertEquals(200L, sAnim.getDuration());
            sAnim.cancel();
            lift.focus(false);
            Assert.assertEquals(Ui.CTRL_HILITE_OUT, filler.getDuration());
            filler.cancel();
            scaler.cancel();
        } finally {
            org.robolectric.shadows.ShadowChoreographer.setPaused(false);
        }
    }

    @Test
    public void bloomSkipsAnimatorsWhenGoneInLightMode() throws Exception {
        Ui.light = true;
        Bloom bloom = new Bloom(c);
        bloom.layout(0, 0, 1000, 500);
        bloom.setVisibility(View.GONE);
        bloom.moveTo(0.25f, 0.75f, 900);
        bloom.setColor(0xFF336699, 700);
        bloom.setStrength(0.45f, 460);
        Assert.assertNull("Gone Bloom must not start moveAnim", field(bloom, "moveAnim"));
        Assert.assertNull("Gone Bloom must not start colorAnim", field(bloom, "colorAnim"));
        Assert.assertNull("Gone Bloom must not start strengthAnim", field(bloom, "strengthAnim"));
        Assert.assertEquals(0.25f, (Float) field(bloom, "cx"), 1e-4f);
        Assert.assertEquals(0.75f, (Float) field(bloom, "cy"), 1e-4f);
        Assert.assertEquals(0xFF336699, ((Integer) field(bloom, "color")).intValue());
        Assert.assertEquals(0.45f, (Float) field(bloom, "strength"), 1e-4f);
        Assert.assertNotEquals("Gone Bloom skips recolor until visible",
                0xFF336699, ((Integer) field(bloom, "filteredFor")).intValue());

        // HomeActivity.glide() stores cx/cy/color on Gone Bloom without starting animators
        org.robolectric.shadows.ShadowChoreographer.setPaused(true);
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(java.time.Duration.ofMillis(16));
        try {
            Prefs.device(c).edit().putString("set_light", "on").commit();
            org.robolectric.android.controller.ActivityController<HomeActivity> ac = Robolectric.buildActivity(HomeActivity.class).setup();
            controllers.add(ac);
            HomeActivity home = ac.get();
            Bloom homeBloom = field(home, "bloom");
            Assert.assertEquals(View.GONE, homeBloom.getVisibility());
            Method glide = HomeActivity.class.getDeclaredMethod("glide", float.class, float.class, int.class);
            glide.setAccessible(true);
            glide.invoke(home, 320f, 180f, 0xFF4488CC);
            Assert.assertNull(field(homeBloom, "moveAnim"));
            Assert.assertNull(field(homeBloom, "colorAnim"));
            Assert.assertEquals(0xFF4488CC, ((Integer) field(homeBloom, "color")).intValue());

            // Becoming visible after Light mode immediately recolors and places the stored state
            Ui.light = false;
            bloom.setVisibility(View.VISIBLE);
            Assert.assertEquals("Visible Bloom immediately recolors to stored color",
                    0xFF336699, ((Integer) field(bloom, "filteredFor")).intValue());
            android.graphics.RectF area = field(bloom, "area");
            Assert.assertTrue("Visible Bloom immediately places stored coordinates", area.width() > 0 && area.height() > 0);
            bloom.moveTo(0.8f, 0.2f, 900);
            bloom.setColor(0xFFAA5500, 700);
            bloom.setStrength(1f, 460);
            ValueAnimator m = field(bloom, "moveAnim");
            ValueAnimator col = field(bloom, "colorAnim");
            ValueAnimator st = field(bloom, "strengthAnim");
            Assert.assertNotNull(m);
            Assert.assertNotNull(col);
            Assert.assertNotNull(st);
            Assert.assertTrue(m.isStarted());
            Assert.assertTrue(col.isStarted());
            Assert.assertTrue(st.isStarted());
            m.cancel();
            col.cancel();
            st.cancel();
        } finally {
            org.robolectric.shadows.ShadowChoreographer.setPaused(false);
        }
    }

    @Test
    public void overlaysUseFastPathsAndNonOverlappingLayersInLightMode() throws Exception {
        org.robolectric.shadows.ShadowChoreographer.setPaused(true);
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(java.time.Duration.ofMillis(16));
        try {
            Prefs.device(c).edit().putString("set_light", "on").commit();
            org.robolectric.android.controller.ActivityController<HomeActivity> ac = Robolectric.buildActivity(HomeActivity.class).setup();
            controllers.add(ac);
            HomeActivity home = ac.get();
            Assert.assertTrue(Ui.light);

            Find find = field(home, "findPanel");
            AppMenu menu = field(home, "menu");
            Widget skyW = field(home, "skyW");
            View homeView = field(home, "home");
            Backdrop backdrop = field(home, "backdrop");
            Assert.assertFalse("Find must avoid offscreen layer in Light mode", find.hasOverlappingRendering());
            Assert.assertFalse("AppMenu must avoid offscreen layer in Light mode", menu.hasOverlappingRendering());
            Assert.assertFalse("Widget must avoid offscreen layer in Light mode", skyW.hasOverlappingRendering());

            // Find opens over Ui.LOW_FOCUS_IN and hides home + stops backdrop at 150ms (not 300ms)
            Method openFind = HomeActivity.class.getDeclaredMethod("openFind", String.class);
            openFind.setAccessible(true);
            openFind.invoke(home, "");
            run(180);
            Assert.assertEquals(1f, find.getAlpha(), 1e-4f);
            Assert.assertEquals(View.INVISIBLE, homeView.getVisibility());
            Assert.assertFalse("Light mode stops backdrop under opaque Find veil", (Boolean) field(backdrop, "running"));

            Method closeFind = HomeActivity.class.getDeclaredMethod("closeFind");
            closeFind.setAccessible(true);
            closeFind.invoke(home);
            Assert.assertEquals(View.VISIBLE, homeView.getVisibility());
            run(180);
            Assert.assertEquals(View.GONE, find.getVisibility());

            // AppMenu skips full-screen parent invalidate() when Light mode forces dim=0
            boolean[] invalidated = {false};
            FrameLayout holder = new FrameLayout(home) {
                @Override
                public void onDescendantInvalidated(View child, View target) {
                    invalidated[0] = true;
                    super.onDescendantInvalidated(child, target);
                }

                @Override
                public android.view.ViewParent invalidateChildInParent(int[] location, android.graphics.Rect dirty) {
                    invalidated[0] = true;
                    return super.invalidateChildInParent(location, dirty);
                }
            };
            AppMenu spyMenu = new AppMenu(home, backdrop);
            spyMenu.removeAllViews();
            spyMenu.setVisibility(View.VISIBLE);
            holder.addView(spyMenu, new FrameLayout.LayoutParams(1280, 720));
            ((android.view.ViewGroup) home.findViewById(android.R.id.content)).addView(holder, new FrameLayout.LayoutParams(1280, 720));
            holder.measure(View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY));
            holder.layout(0, 0, 1280, 720);
            android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888);
            holder.draw(new android.graphics.Canvas(bmp));
            Spring.Sink sink = field(field(spyMenu, "way"), "sink");
            invalidated[0] = false;
            sink.at(0.5f);
            Assert.assertFalse("Light mode skips full-screen AppMenu invalidate when dim=0", invalidated[0]);
            Assert.assertEquals(0f, (Float) field(spyMenu, "dim"), 1e-4f);

            // Switcher in Light mode: keeps up to 8 cached cards, disables overlapping layers on item containers,
            // closes in Ui.CLOSE, and Close all's word still comes when the deck is shut before it (only a reopen drops it)
            final java.util.Map<String, AppRepo.App> map = new java.util.LinkedHashMap<>();
            java.util.LinkedHashMap<String, Long> recent = new java.util.LinkedHashMap<>();
            long now = System.currentTimeMillis();
            for (int k = 0; k < 8; k++) {
                String p = "com.test.p" + k;
                AppRepo.App app = new AppRepo.App();
                app.pkg = p;
                app.label = "App " + k;
                app.icon = new ColorDrawable(0xFF336699);
                app.color = 0xFF336699;
                map.put(p, app);
                recent.put(p, now - k * 1000L);
            }
            final int[] clearedCount = {0};
            Switcher sw = new Switcher(home, backdrop, new Switcher.Host() {
                @Override public AppRepo.App find(String pkg) { return map.get(pkg); }
                @Override public Card card(AppRepo.App a) { return new Card(home, Tiles.launch(a), a.color, 22); }
                @Override public void launch(AppRepo.App a, View from) {}
                @Override public void close(AppRepo.App a) {}
                @Override public void closeAll(List<AppRepo.App> all) {}
                @Override public void cleared(int count) { clearedCount[0] = count; }
                @Override public android.graphics.Bitmap shot(String pkg) { return null; }
            });
            ((android.view.ViewGroup) home.findViewById(android.R.id.content)).addView(sw, new FrameLayout.LayoutParams(1280, 720));
            sw.open(recent, null);
            java.util.Map<?, ?> made = field(sw, "made");
            Assert.assertEquals("Light mode Switcher caches all 8 recent cards", 8, made.size());
            List<View> items = field(sw, "items");
            android.view.ViewGroup itemCol = (android.view.ViewGroup) items.get(0);
            android.view.ViewGroup itemHead = (android.view.ViewGroup) itemCol.getChildAt(0);
            Assert.assertFalse(itemCol.hasOverlappingRendering());
            Assert.assertFalse(itemHead.getChildAt(0).hasOverlappingRendering());
            Assert.assertFalse(itemHead.getChildAt(1).hasOverlappingRendering());
            run(250);
            call(sw, "clearAll", new Class<?>[0]);
            Assert.assertEquals("clearedLater has not fired before Ui.CLOSE", 0, clearedCount[0]);
            sw.close(null);
            run(160);
            Assert.assertEquals("Light mode Switcher finishes closing within Ui.CLOSE", View.GONE, sw.getVisibility());
            run(400);
            Assert.assertEquals("Close all's word arrives after an early close", 8, clearedCount[0]);
            Assert.assertNull(field(sw, "clearedLater"));

            // HIGH mode unchanged
            Ui.light = false;
            sink.at(0.5f);
            Assert.assertTrue("HIGH mode still invalidates AppMenu for dim backdrop", invalidated[0]);
            Assert.assertEquals(0.5f, (Float) field(spyMenu, "dim"), 1e-4f);
            Assert.assertTrue(find.hasOverlappingRendering());
            Assert.assertTrue(menu.hasOverlappingRendering());
            Assert.assertFalse("Widget inherits GlassFrame non-overlapping rendering", skyW.hasOverlappingRendering());
            Assert.assertTrue(itemCol.hasOverlappingRendering());
            sw.open(recent, null);
            Assert.assertEquals("HIGH mode Switcher retains all 8 items", 8, made.size());
            sw.close(null);
            run(160);
            Assert.assertEquals("HIGH mode Switcher stays open past 160ms (closes at 210ms)", View.VISIBLE, sw.getVisibility());
            run(100);
            Assert.assertEquals(View.GONE, sw.getVisibility());
        } finally {
            org.robolectric.shadows.ShadowChoreographer.setPaused(false);
        }
    }

    @Test
    public void glassBakesOneTintedBlurPerStrengthInLightModeAndMatchesHighPixels() throws Exception {
        boolean prevCalm = Backdrop.calm;
        try {
            android.graphics.ColorMatrixColorFilter fWidgetA = Ui.glass(Ui.GLASS_SAT, Ui.withAlpha(Ui.SMOKE, Math.round(255 * 0.42f)), Ui.withAlpha(Ui.IVORY, Math.round(255 * 0.11f)));
            android.graphics.ColorMatrixColorFilter fWidgetB = Ui.glass(Ui.GLASS_SAT, Ui.withAlpha(Ui.SMOKE, Math.round(255 * 0.42f)), Ui.withAlpha(Ui.IVORY, Math.round(255 * 0.11f)));
            android.graphics.ColorMatrixColorFilter fVeil = Ui.glass(0.85f, Ui.withAlpha(Ui.SMOKE, Math.round(255 * 0.50f)), 0);
            Assert.assertSame("Identical glass strengths share one filter instance", fWidgetA, fWidgetB);
            Assert.assertNotSame("Different glass strengths get distinct filter instances", fWidgetA, fVeil);

            Backdrop backdrop = new Backdrop(c);
            GlassFrame veil = new GlassFrame(c, backdrop, 0, 0.5f);
            GlassFrame card = new GlassFrame(c, backdrop, 26, 0.42f);
            Assert.assertFalse("Zero-radius veil disables outline clipping", veil.getClipToOutline());
            Assert.assertTrue("Rounded pane keeps outline clipping", card.getClipToOutline());

            Class<?> slideCls = Class.forName("app.aura.home.Backdrop$Slide");
            java.lang.reflect.Constructor<?> ctor = slideCls.getDeclaredConstructor();
            ctor.setAccessible(true);
            Object slide = ctor.newInstance();
            android.graphics.Bitmap blur = android.graphics.Bitmap.createBitmap(128, 72, android.graphics.Bitmap.Config.ARGB_8888);
            for (int y = 0; y < 72; y++) {
                for (int x = 0; x < 128; x++) {
                    blur.setPixel(x, y, android.graphics.Color.rgb((x * 2) & 0xFF, (y * 3) & 0xFF, ((x + y) * 2) & 0xFF));
                }
            }
            set(slide, "blur", blur);
            set(slide, "sw", 1280);
            set(slide, "sh", 720);
            set(slide, "alpha", 1f);
            set(backdrop, "current", slide);

            android.graphics.RectF box = new android.graphics.RectF(0, 0, 200, 100);
            android.graphics.Paint pHigh = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG | android.graphics.Paint.FILTER_BITMAP_FLAG);
            pHigh.setColorFilter(fWidgetA);
            android.graphics.Bitmap outHigh = android.graphics.Bitmap.createBitmap(200, 100, android.graphics.Bitmap.Config.ARGB_8888);
            outHigh.eraseColor(android.graphics.Color.BLACK);
            Backdrop.calm = false;
            backdrop.drawBlurRound(new android.graphics.Canvas(outHigh), box, 20f, 40f, 60f, pHigh);

            java.util.Map<?, ?> tintedShaders = field(slide, "tintedShaders");
            Assert.assertEquals("HIGH mode does not allocate pre-tinted blur copies", 0, tintedShaders.size());

            android.graphics.Paint pLight = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG | android.graphics.Paint.FILTER_BITMAP_FLAG);
            pLight.setColorFilter(fWidgetA);
            android.graphics.Bitmap outLight = android.graphics.Bitmap.createBitmap(200, 100, android.graphics.Bitmap.Config.ARGB_8888);
            outLight.eraseColor(android.graphics.Color.BLACK);
            Backdrop.calm = true;
            backdrop.drawBlurRound(new android.graphics.Canvas(outLight), box, 20f, 40f, 60f, pLight);
            Assert.assertSame("Caller paint keeps its ColorFilter after drawBlurRound", fWidgetA, pLight.getColorFilter());
            Assert.assertEquals("One strength creates one pre-tinted copy", 1, tintedShaders.size());

            // Drawing again with the same strength reuses the cached copy; a second strength adds a second copy
            android.graphics.Bitmap scratch = android.graphics.Bitmap.createBitmap(200, 100, android.graphics.Bitmap.Config.ARGB_8888);
            pLight.setColorFilter(fWidgetB);
            backdrop.drawBlurRound(new android.graphics.Canvas(scratch), box, 20f, 40f, 60f, pLight);
            Assert.assertEquals(1, tintedShaders.size());
            pLight.setColorFilter(fVeil);
            backdrop.drawBlurRound(new android.graphics.Canvas(scratch), box, 0f, 0f, 0f, pLight);
            Assert.assertEquals("Second strength creates a second pre-tinted copy", 2, tintedShaders.size());

            // Pixel comparison between HIGH and Light mode for the same pane
            int maxDiff = 0, badX = -1, badY = -1, bad1 = 0, bad2 = 0;
            for (int y = 0; y < 100; y++) {
                for (int x = 0; x < 200; x++) {
                    int c1 = outHigh.getPixel(x, y), c2 = outLight.getPixel(x, y);
                    int d = Math.max(Math.abs(android.graphics.Color.red(c1) - android.graphics.Color.red(c2)),
                            Math.max(Math.abs(android.graphics.Color.green(c1) - android.graphics.Color.green(c2)),
                                    Math.abs(android.graphics.Color.blue(c1) - android.graphics.Color.blue(c2))));
                    if (d > maxDiff) {
                        maxDiff = d;
                        badX = x;
                        badY = y;
                        bad1 = c1;
                        bad2 = c2;
                    }
                }
            }
            Assert.assertTrue("Pre-tinted glass matches per-pixel ColorMatrixColorFilter within rounding (maxDiff=" + maxDiff
                    + " at " + badX + "," + badY + " high=" + Integer.toHexString(bad1) + " light=" + Integer.toHexString(bad2) + ")", maxDiff <= 2);

            List<android.graphics.Bitmap> tintedBlurs = field(slide, "tintedBlurs");
            Assert.assertEquals(2, tintedBlurs.size());
            android.graphics.Bitmap tb0 = tintedBlurs.get(0);
            Assert.assertFalse(tb0.isRecycled());
            call(slide, "recycle", new Class<?>[0]);
            Assert.assertTrue("Slide.recycle() frees pre-tinted blur bitmaps", tb0.isRecycled());
            Assert.assertEquals(0, tintedShaders.size());
            backdrop.release();
        } finally {
            Backdrop.calm = prevCalm;
        }
    }

    @Test
    public void hiddenViewsStayInvisibleAndSettingsSegmentsMatchPixelsInLightMode() throws Exception {
        install("demo.a1", "Alpha", ApplicationInfo.CATEGORY_VIDEO);
        Profiles.forget();
        Profiles.use(c, Profiles.MAIN);
        Prefs.of(c).edit().clear().putString(Prefs.LAYOUT, "aura").commit();
        Prefs.device(c).edit()
                .putBoolean("welcomed", true)
                .putString("set_light", "on")
                .commit();
        org.robolectric.shadows.ShadowChoreographer.setPaused(true);
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(java.time.Duration.ofMillis(16));
        try {
            Field w = HomeActivity.class.getDeclaredField("warm");
            w.setAccessible(true);
            w.setBoolean(null, true);
            org.robolectric.android.controller.ActivityController<HomeActivity> ac = Robolectric.buildActivity(HomeActivity.class).setup();
            controllers.add(ac);
            HomeActivity a = ac.get();
            run(300);

            View scrim = field(a, "stickyTopScrim");
            View story = field(a, "story");
            FocusLayer focusLayer = field(a, "focusLayer");
            View[] shades = field(focusLayer, "shades");

            Assert.assertEquals("Story stays INVISIBLE outside photo mode in Light mode", View.INVISIBLE, story.getVisibility());
            Assert.assertEquals("Sticky top scrim stays INVISIBLE at rest in Light mode", View.INVISIBLE, scrim.getVisibility());

            // FocusLayer release hides shades completely from ViewGroup traversal
            focusLayer.hide();
            Assert.assertEquals(View.INVISIBLE, shades[0].getVisibility());
            Assert.assertEquals(View.INVISIBLE, shades[1].getVisibility());

            // Scrolling down reveals stickyTopScrim and holds SmartStack in Light mode; scrolling back hides it again
            SmartStack stack = field(a, "stack");
            call(a, "scrollTo", new Class<?>[]{float.class}, 320f);
            run(900);
            Assert.assertEquals(View.VISIBLE, scrim.getVisibility());
            Assert.assertTrue("SmartStack is held while scrolled past the dashboard in Light mode", (Boolean) field(stack, "held"));
            call(a, "scrollTo", new Class<?>[]{float.class}, 0f);
            run(900);
            Assert.assertEquals(View.INVISIBLE, scrim.getVisibility());
        } finally {
            org.robolectric.shadows.ShadowChoreographer.setPaused(false);
        }

        // SettingsPanel.Segment draws identical pixels in Light mode (drawRect/drawRoundRect) and HIGH mode (drawPath)
        Class<?> segCls = Class.forName("app.aura.home.SettingsPanel$Segment");
        java.lang.reflect.Constructor<?> ctor = segCls.getDeclaredConstructor();
        ctor.setAccessible(true);
        java.lang.reflect.Method set = segCls.getDeclaredMethod("set", boolean.class, boolean.class);
        set.setAccessible(true);
        for (boolean[] ends : new boolean[][]{{false, false}, {true, true}, {true, false}, {false, true}}) {
            android.graphics.drawable.Drawable seg = (android.graphics.drawable.Drawable) ctor.newInstance();
            seg.setBounds(0, 0, 240, 48);
            set.invoke(seg, ends[0], ends[1]);
            android.graphics.Bitmap bHigh = android.graphics.Bitmap.createBitmap(240, 48, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Bitmap bLight = android.graphics.Bitmap.createBitmap(240, 48, android.graphics.Bitmap.Config.ARGB_8888);
            Ui.light = false;
            seg.draw(new android.graphics.Canvas(bHigh));
            Ui.light = true;
            seg.draw(new android.graphics.Canvas(bLight));
            for (int y = 0; y < 48; y++) {
                for (int x = 0; x < 240; x++) {
                    Assert.assertEquals("Segment pixel (" + x + "," + y + ") top=" + ends[0] + " bottom=" + ends[1],
                            bHigh.getPixel(x, y), bLight.getPixel(x, y));
                }
            }
        }
    }
}
