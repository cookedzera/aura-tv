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
        call(home, "repaintScrollGlass", new Class<?>[]{float.class, boolean.class}, -Ui.dpf(40), false);
        Assert.assertEquals("Mid-scroll glass repaint skipped in Light mode", 0f,
                ((Number) field(home, "scrollBlurY")).floatValue(), 1e-4f);
        call(home, "repaintScrollGlass", new Class<?>[]{float.class, boolean.class}, -Ui.dpf(40), true);
        Assert.assertEquals("Settled scroll glass repaints once in Light mode", -Ui.dpf(40),
                ((Number) field(home, "scrollBlurY")).floatValue(), 1e-4f);
    }
}
