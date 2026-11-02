package app.aura.home;

import android.content.Context;
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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * The home screen's style switched both ways while scrolled down, every widget hidden and shown
 * again, and a switch to a kids' profile: everything that should show does, and recent apps never
 * offers what the kids' profile hides.
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30, application = AuraApp.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class StyleProfileTest {
    private static void run(long ms) {
        for (long t = 0; t < ms; t += 100) ShadowLooper.idleMainLooper(100, java.util.concurrent.TimeUnit.MILLISECONDS);
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

    private static void apply(HomeActivity a, String what) throws Exception {
        call(a, "applySetting", new Class<?>[]{String.class}, what);
        run(1500);
    }

    @Test
    public void styleWidgetsAndProfiles() throws Exception {
        Context c = RuntimeEnvironment.getApplication();
        Prefs.device(c).edit().putBoolean("welcomed", true).putString("set_light", "on").commit();
        Prefs.of(c).edit().putString(Prefs.LAYOUT, "aura").commit();
        org.robolectric.shadows.ShadowChoreographer.setPaused(true);
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(Duration.ofMillis(16));
        Field w = HomeActivity.class.getDeclaredField("warm");
        w.setAccessible(true);
        w.setBoolean(null, true);
        Trouble.clear(c);
        ActivityController<HomeActivity> ac = Robolectric.buildActivity(HomeActivity.class).setup();
        HomeActivity a = ac.get();
        run(3000);
        View status = field(a, "status");

        // scrolled down in the Aura style: the status bar fades out with the greeting
        call(a, "scrollTo", new Class<?>[]{float.class}, 420f);
        run(1000);
        Assert.assertEquals(0f, status.getAlpha(), 0.01f);
        // tvOS style from there: the dock's status bar shows
        Prefs.of(c).edit().putString(Prefs.LAYOUT, "tvos").commit();
        apply(a, "layout");
        // the dock: its glass and hint show; the status pill waits until focus moves up to it
        View dockGlass = field(a, "dockGlass"), hint = field(a, "tvosHint");
        Assert.assertEquals(View.VISIBLE, dockGlass.getVisibility());
        Assert.assertEquals(1f, dockGlass.getAlpha(), 0.01f);
        Assert.assertEquals(1f, hint.getAlpha(), 0.01f);
        Assert.assertEquals(0f, status.getAlpha(), 0.01f);

        // scrolled down on the dock, then back to the Aura style: greeting, status and widgets show
        call(a, "scrollTo", new Class<?>[]{float.class}, 300f);
        run(1000);
        Prefs.of(c).edit().putString(Prefs.LAYOUT, "aura").commit();
        apply(a, "layout");
        View greeting = field(a, "greeting");
        Assert.assertEquals(1f, greeting.getAlpha(), 0.01f);
        Assert.assertEquals(1f, status.getAlpha(), 0.01f);
        List<View> widgets = field(a, "widgets");
        Assert.assertEquals(3, widgets.size());
        for (View v : widgets) {
            Assert.assertEquals(View.VISIBLE, v.getVisibility());
            Assert.assertEquals(1f, v.getAlpha(), 0.01f);
        }

        // every widget hidden, then all back
        Prefs.of(c).edit().putStringSet(Prefs.WIDGETS_HIDDEN, new HashSet<>(Arrays.asList(HomeActivity.WIDGET_IDS))).commit();
        apply(a, "widgets");
        Assert.assertEquals(0, ((List<?>) field(a, "widgets")).size());
        Prefs.of(c).edit().putStringSet(Prefs.WIDGETS_HIDDEN, new HashSet<>()).commit();
        apply(a, "widgets");
        widgets = field(a, "widgets");
        Assert.assertEquals(3, widgets.size());
        for (View v : widgets) {
            Assert.assertEquals(View.VISIBLE, v.getVisibility());
            Assert.assertEquals(1f, v.getAlpha(), 0.01f);
        }

        // two apps; recent apps read while the grown-up profile was in use has both
        List<AppRepo.App> apps = field(a, "apps");
        for (String p : new String[]{"com.allowed", "com.hidden"}) {
            AppRepo.App x = new AppRepo.App();
            x.pkg = p;
            x.label = p;
            x.icon = new ColorDrawable(0xFF446688);
            x.color = 0xFF446688;
            apps.add(x);
        }
        LinkedHashMap<String, Long> recent = new LinkedHashMap<>();
        recent.put("com.hidden", System.currentTimeMillis());
        recent.put("com.allowed", System.currentTimeMillis() - 60_000);
        set(a, "recentCache", recent);

        // a kids' profile that hides one of them
        Profiles.Profile kid = Profiles.add(c, "Kid");
        Profiles.setKids(c, kid.id, true);
        Profiles.use(c, kid.id);
        Prefs.of(c).edit().putStringSet(Prefs.HIDDEN, new HashSet<>(Arrays.asList("com.hidden"))).commit();
        apply(a, "profile");
        java.util.Map<String, Long> after = field(a, "recentCache");
        Assert.assertTrue("the last person's recent apps aren't kept: " + after, after == null || !after.containsKey("com.hidden"));
        // even a list read before the switch never shows what the kids' profile hides
        call(a, "showSwitcher", new Class<?>[]{java.util.Map.class}, recent);
        run(1200);
        Switcher s = field(a, "switcher");
        List<AppRepo.App> shown = field(s, "apps");
        StringBuilder names = new StringBuilder();
        for (AppRepo.App x : shown) names.append(x.pkg).append(' ');
        Assert.assertEquals("[com.allowed] only, was: " + names, 1, shown.size());
        Assert.assertEquals("com.allowed", shown.get(0).pkg);
        call(a, "closeSwitcher", new Class<?>[]{});
        run(800);

        // back to the main profile, which has its own (Aura) style
        Profiles.use(c, Profiles.MAIN);
        apply(a, "profile");
        Assert.assertEquals(1f, status.getAlpha(), 0.01f);
        Assert.assertEquals(1f, greeting.getAlpha(), 0.01f);

        String report = Trouble.report(c);
        ac.pause().stop().destroy();
        run(300);
        try (java.io.FileWriter fw = new java.io.FileWriter("target/style-profile.txt")) {
            fw.write(report);
        }
        Assert.assertEquals("no problems recorded:\n" + report, 0, Trouble.count(c));
    }
}
