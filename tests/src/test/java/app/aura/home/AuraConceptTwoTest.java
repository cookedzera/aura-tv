package app.aura.home;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.ColorDrawable;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

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

import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.robolectric.Shadows.shadowOf;

/**
 * Verifies the Concept 2 Aura style upgrades: centered category capsule & corridor clearance,
 * sticky docking on scroll with status fade & top scrim, L1/R1 direct category switching,
 * the sidebar as a small icon rail whose focused icon lifts, and active profile identity on home.
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30, application = AuraApp.class, qualifiers = "w960dp-h540dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AuraConceptTwoTest {
    private Context c;
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

    private static AppRepo.App app(String pkg, String label) {
        AppRepo.App x = new AppRepo.App();
        x.pkg = pkg;
        x.label = label;
        x.icon = new ColorDrawable(0xFF335577);
        x.color = 0xFF335577;
        return x;
    }

    private static void shot(HomeActivity a, String file) throws Exception {
        View root = a.getWindow().getDecorView();
        Bitmap b = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(b));
        try (FileOutputStream out = new FileOutputStream(file)) {
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }

    @Before
    public void setUp() throws Exception {
        c = RuntimeEnvironment.getApplication();
        Profiles.forget();
        Profiles.use(c, Profiles.MAIN);
        c.getSharedPreferences("aura_profiles", Context.MODE_PRIVATE).edit().clear().commit();
        Profiles.forget();
        Prefs.of(c).edit().clear().putString(Prefs.LAYOUT, "aura").putBoolean(Prefs.WATCH, false).commit();
        Prefs.device(c).edit().putBoolean("welcomed", true).putString("set_light", "off").commit();
        org.robolectric.shadows.ShadowChoreographer.setPaused(true);
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(Duration.ofMillis(16));
        Field w = HomeActivity.class.getDeclaredField("warm");
        w.setAccessible(true);
        w.setBoolean(null, true);
        Field cache = HomeActivity.class.getDeclaredField("cache");
        cache.setAccessible(true);
        cache.set(null, null);
    }

    @After
    public void tearDown() {
        for (org.robolectric.android.controller.ActivityController<HomeActivity> ac : controllers) {
            try {
                ac.pause().stop().destroy();
            } catch (Throwable ignored) {
            }
        }
        controllers.clear();
        ShadowLooper.idleMainLooper();
        Profiles.forget();
        Profiles.use(c, Profiles.MAIN);
        c.getSharedPreferences("aura_profiles", Context.MODE_PRIVATE).edit().clear().commit();
        Profiles.forget();
    }

    private HomeActivity auraHome(int video, int tools) throws Exception {
        List<AppRepo.App> fake = new ArrayList<>();
        for (int i = 0; i < video; i++) {
            install("com.example.video" + i, "Video " + i, ApplicationInfo.CATEGORY_VIDEO);
            fake.add(app("com.example.video" + i, "Video " + i));
        }
        for (int i = 0; i < tools; i++) {
            install("com.example.tool" + i, "Tool " + i, ApplicationInfo.CATEGORY_UNDEFINED);
            fake.add(app("com.example.tool" + i, "Tool " + i));
        }
        org.robolectric.android.controller.ActivityController<HomeActivity> ac = Robolectric.buildActivity(HomeActivity.class).setup();
        controllers.add(ac);
        HomeActivity a = ac.get();
        run(1500);
        List<AppRepo.App> apps = field(a, "apps");
        apps.clear();
        apps.addAll(fake);
        call(a, "populate", new Class<?>[]{});
        run(900);
        return a;
    }

    @Test
    public void centeredCategoryCapsuleAndCorridorClearance() throws Exception {
        HomeActivity a = auraHome(3, 12);
        FrameLayout bar = field(a, "homeTabBar");
        TabStrip tabs = field(a, "homeTabs");
        Assert.assertEquals(View.VISIBLE, bar.getVisibility());
        Assert.assertEquals(View.VISIBLE, tabs.getVisibility());
        float barCenter = bar.getWidth() / 2f;
        float tabsCenter = (tabs.getLeft() + tabs.getRight()) / 2f;
        Assert.assertEquals("category capsule is centered horizontally on the stage", barCenter, tabsCenter, Ui.dpf(2));
        float tabsBottom = bar.getTranslationY() + tabs.getBottom();
        float gridTop = Ui.dpf((Integer) call(a, "gridY", new Class<?>[]{}));
        Assert.assertTrue("clear corridor above 1.20x focused row 0 (" + (gridTop - tabsBottom) + "px)",
                gridTop - tabsBottom >= Ui.dpf(14));
    }

    @Test
    public void stickyCategoryDockOnScrollWithTopScrimAndStatusFade() throws Exception {
        HomeActivity a = auraHome(4, 16);
        FrameLayout bar = field(a, "homeTabBar");
        View scrim = field(a, "stickyTopScrim");
        View status = field(a, "status");
        float restY = bar.getTranslationY();
        Assert.assertEquals(0f, scrim.getAlpha(), 0.01f);
        Assert.assertEquals(1f, status.getAlpha(), 0.01f);

        call(a, "scrollTo", new Class<?>[]{float.class}, 320f);
        run(900);
        Assert.assertEquals("category capsule docks cleanly near top", Ui.dpf(26), bar.getTranslationY(), Ui.dpf(1));
        Assert.assertTrue("docks well above its rest position", bar.getTranslationY() < restY - Ui.dpf(150));
        Assert.assertEquals("sticky header scrim fades in", 1f, scrim.getAlpha(), 0.02f);
        Assert.assertEquals("status pill still fades away cleanly while scrolled", 0f, status.getAlpha(), 0.01f);
        shot(a, "target/aura-scrolled.png");

        call(a, "scrollTo", new Class<?>[]{float.class}, 0f);
        run(900);
        Assert.assertEquals("returns smoothly to rest Y", restY, bar.getTranslationY(), Ui.dpf(1));
        Assert.assertEquals(0f, scrim.getAlpha(), 0.02f);
        Assert.assertEquals(1f, status.getAlpha(), 0.01f);
    }

    @Test
    public void shoulderButtonsHopCategoriesDirectlyFromGrid() throws Exception {
        HomeActivity a = auraHome(3, 12);
        Assert.assertEquals("all", field(a, "homeTab"));
        List<Cell> cells = field(a, "cells");
        Assert.assertEquals(15, cells.size());

        call(a, "scrollTo", new Class<?>[]{float.class}, 240f);
        run(700);
        a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_R1));
        a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_R1));
        run(900);
        Assert.assertEquals("video", field(a, "homeTab"));
        Assert.assertEquals(3, cells.size());
        Assert.assertEquals("switching categories returns scrolled list to top", 0f, (Float) field(a, "scrollTarget"), 0.1f);

        a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_L1));
        a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_L1));
        run(900);
        Assert.assertEquals("all", field(a, "homeTab"));
        Assert.assertEquals(15, cells.size());
    }

    @Test
    public void sidebarStaysASmallIconRailAndLiftsTheFocusedIcon() throws Exception {
        HomeActivity a = auraHome(3, 8);
        GlassFrame sidebar = field(a, "sidebar");
        List<Pad> side = field(a, "side");
        Assert.assertEquals("an icon rail, never widening over the page", Ui.dp(58), sidebar.getLayoutParams().width);

        List<Cell> cells = field(a, "cells");
        cells.get(0).card.requestFocusFromTouch();
        org.junit.Assume.assumeFalse("simulated TV left touch mode", cells.get(0).card.isInTouchMode());
        call(a, "focusGrid", new Class<?>[]{int.class}, 0);
        run(100);
        set(a, "held", false);
        a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT));
        a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT));
        run(900);
        Assert.assertEquals(0, (int) field(a, "zone")); // SIDE = 0
        Pad lit = null;
        for (Pad p : side) if (p.isFocused()) lit = p;
        Assert.assertNotNull("an icon of the rail has the focus", lit);
        Assert.assertTrue("the focused icon lifts a little: " + lit.getScaleX(), lit.getScaleX() > 1.03f);
        Assert.assertEquals("the rail itself stays put", Ui.dp(58), sidebar.getWidth());
        shot(a, "target/aura-sidebar-focus.png");

        a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT));
        a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT));
        run(900);
        Assert.assertEquals("the icon settles back", 1f, lit.getScaleX(), 0.01f);
    }

    @Test
    public void profileIdentitySurfacesOnHomeStatusAndPersonalizedGreeting() throws Exception {
        HomeActivity a = auraHome(2, 3);
        TextView hello = field(a, "hello");
        TextView badge = field(a, "stProfile");
        Assert.assertEquals("M", badge.getText().toString());
        Assert.assertEquals(View.VISIBLE, badge.getVisibility());
        Assert.assertFalse("default Me stays clean without suffix", hello.getText().toString().contains(", Me"));

        Profiles.Profile alex = Profiles.add(c, "Alex");
        Profiles.use(c, alex.id);
        call(a, "applySetting", new Class<?>[]{String.class}, "profile");
        run(400);
        Assert.assertEquals("A", badge.getText().toString());
        Assert.assertTrue("named profile personalizes greeting: " + hello.getText(),
                hello.getText().toString().endsWith(", Alex"));
    }
}
