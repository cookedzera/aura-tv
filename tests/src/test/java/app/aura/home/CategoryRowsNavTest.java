package app.aura.home;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.graphics.drawable.ColorDrawable;
import android.view.KeyEvent;

import org.junit.Assert;
import org.junit.Before;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.robolectric.Shadows.shadowOf;

/**
 * Tests for 7.7.1 Category Rows navigation, focus, search suggestions, and edge cases.
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30, application = AuraApp.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class CategoryRowsNavTest {
    private Context c;

    private static void run(long ms) {
        for (long t = 0; t < ms; t += 100) ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS);
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

    private void installPkg(String pkg, String label, int category) {
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

    private AppRepo.App makeApp(String pkg, String label) {
        AppRepo.App x = new AppRepo.App();
        x.pkg = pkg;
        x.label = label;
        x.icon = new ColorDrawable(0xFF335577);
        x.color = 0xFF335577;
        return x;
    }

    @Before
    public void setUp() throws Exception {
        c = RuntimeEnvironment.getApplication();
        Prefs.device(c).edit().clear().putBoolean("welcomed", true).putString("set_light", "on").commit();
        Prefs.of(c).edit().clear().putString(Prefs.LAYOUT, "aura").putBoolean(Categories.ROWS, true).commit();
        org.robolectric.shadows.ShadowChoreographer.setPaused(true);
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(Duration.ofMillis(16));
        Field w = HomeActivity.class.getDeclaredField("warm");
        w.setAccessible(true);
        w.setBoolean(null, true);
        // the apps a test fills in place are the process's cache too: each test starts with none
        Field cache = HomeActivity.class.getDeclaredField("cache");
        cache.setAccessible(true);
        cache.set(null, null);
    }

    private HomeActivity launchWithCategoryApps(int videoCount, int toolCount) throws Exception {
        List<AppRepo.App> fakeApps = new ArrayList<>();
        for (int i = 0; i < videoCount; i++) {
            String pkg = "com.example.video" + i;
            installPkg(pkg, "Video " + i, ApplicationInfo.CATEGORY_VIDEO);
            fakeApps.add(makeApp(pkg, "Video " + i));
        }
        for (int i = 0; i < toolCount; i++) {
            String pkg = "com.example.tool" + i;
            installPkg(pkg, "Tool " + i, ApplicationInfo.CATEGORY_UNDEFINED);
            fakeApps.add(makeApp(pkg, "Tool " + i));
        }
        ActivityController<HomeActivity> ac = Robolectric.buildActivity(HomeActivity.class).setup();
        HomeActivity a = ac.get();
        run(1500);
        List<AppRepo.App> apps = field(a, "apps");
        apps.clear();
        apps.addAll(fakeApps);
        call(a, "populate", new Class<?>[]{});
        run(500);
        return a;
    }

    /** Finding M2: focusTag() lacks a break/return and lands in All apps instead of the first matching row. */
    @Test
    public void focusTagStopsAtFirstRowInsteadOfJumpingToAllApps() throws Exception {
        HomeActivity a = launchWithCategoryApps(2, 2);
        List<?> rowInfo = field(a, "rowInfo");
        Assert.assertTrue("At least two rows built (Video and All apps)", rowInfo.size() >= 2);
        call(a, "focusTag", new Class<?>[]{String.class}, "com.example.video0");
        int gridAt = field(a, "gridAt");
        int row = (Integer) call(a, "rowOf", new Class<?>[]{int.class}, gridAt);
        Assert.assertEquals("focusTag should focus the first matching row (Row 0), but landed in Row " + row, 0, row);
    }

    /** Finding M1: Pressing Down from Widgets in Category Rows mode should land on Row 0 without scrolling past it. */
    @Test
    public void downFromWidgetsLandsOnFirstRowAndStaysScrolledUp() throws Exception {
        HomeActivity a = launchWithCategoryApps(2, 3);
        List<?> rowInfo = field(a, "rowInfo");
        Assert.assertTrue(rowInfo.size() >= 2);
        // User previously visited All apps (Row 1, index 3) before moving up to Widgets
        set(a, "gridAt", 3);
        call(a, "focusWidget", new Class<?>[]{int.class}, 0);
        run(300);
        // the simulated TV runs in touch mode, where no widget takes focus: the zone must be set
        // by focusWidget itself, as it is for a TV whose remote is in touch mode too
        int zone = field(a, "zone");
        Assert.assertEquals("focusWidget should put the zone on the widgets", 1, zone);
        a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN));
        int gridAt = field(a, "gridAt");
        int row = (Integer) call(a, "rowOf", new Class<?>[]{int.class}, gridAt);
        float scrollTarget = field(a, "scrollTarget");
        Assert.assertEquals("Down from Widgets should land in Row 0, but landed in Row " + row, 0, row);
        Assert.assertEquals("Dashboard should stay at top when entering Row 0 from Widgets", 0f, scrollTarget, 0.1f);
    }

    /** Finding M9: Pressing Back from Row 1 when Row 0 has fewer than 5 apps must return to Row 0, not enter Photo mode. */
    @Test
    public void backFromLowerRowReturnsToFirstRowNotPhotoMode() throws Exception {
        HomeActivity a = launchWithCategoryApps(2, 3);
        // Row 0 has 2 apps (indices 0..1); Row 1 starts at index 2 (which is < COLS=5)
        call(a, "focusGrid", new Class<?>[]{int.class}, 2);
        set(a, "scrollTarget", 0f);
        a.onBackPressed();
        boolean photo = field(a, "photo");
        int gridAt = field(a, "gridAt");
        int row = (Integer) call(a, "rowOf", new Class<?>[]{int.class}, gridAt);
        Assert.assertFalse("Back from Row 1 must not enter Photo mode", photo);
        Assert.assertEquals("Back from Row 1 should return focus to Row 0", 0, row);
    }

    /** Finding M4: Page Down / Channel Down in Category Rows mode should move to the next row, not jump +20 tiles. */
    @Test
    public void pageDownMovesToNextRowNotTwentyTilesAhead() throws Exception {
        Categories.addTo(c, Categories.FAV, "com.example.video0");
        HomeActivity a = launchWithCategoryApps(3, 2);
        // Row 0 = Favorites (1), Row 1 = Movies & TV (3), Row 2 = All apps (5)
        call(a, "focusGrid", new Class<?>[]{int.class}, 0);
        a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PAGE_DOWN));
        int gridAt = field(a, "gridAt");
        int row = (Integer) call(a, "rowOf", new Class<?>[]{int.class}, gridAt);
        Assert.assertEquals("PAGE_DOWN from Row 0 should move to Row 1, not jump +20 tiles to the end of All apps", 1, row);
    }

    /** Finding M3: Find's initial suggested apps should not show duplicate tiles when Category Rows are enabled. */
    @Test
    public void findSuggestionsDoNotRepeatAppsAcrossCategoryRows() throws Exception {
        HomeActivity a = launchWithCategoryApps(2, 1);
        call(a, "openFind", new Class<?>[]{String.class}, "");
        run(500);
        Find find = field(a, "findPanel");
        Assert.assertNotNull(find);
        Find.Host host = field(find, "host");
        List<AppRepo.App> suggested = host.suggested();
        Set<String> seenPkgs = new HashSet<>();
        List<String> duplicates = new ArrayList<>();
        for (AppRepo.App app : suggested) {
            if (!seenPkgs.add(app.pkg)) duplicates.add(app.pkg);
        }
        Assert.assertTrue("Suggested apps in Find should be unique, but had duplicates: " + duplicates, duplicates.isEmpty());
    }

    /** Finding C1: When all category rows and all widgets are hidden, D-pad navigation must still reach the sidebar/status controls. */
    @Test
    public void hidingAllAppsAndEveryWidgetStillLeavesReachableFocus() throws Exception {
        // every app hidden (All apps itself can't be hidden any more): nothing on home at all
        Prefs.hide(c, "com.example.tool0", true);
        Prefs.hide(c, "com.example.tool1", true);
        Prefs.of(c).edit().putStringSet(Prefs.WIDGETS_HIDDEN, new HashSet<>(Arrays.asList(HomeActivity.WIDGET_IDS))).commit();
        HomeActivity a = launchWithCategoryApps(0, 2);
        List<?> cells = field(a, "cells");
        List<?> widgets = field(a, "widgets");
        Assert.assertTrue(cells.isEmpty());
        Assert.assertTrue(widgets.isEmpty());
        // the simulated TV runs in touch mode, where the rail's buttons can't take focus: the
        // zone has to be set by focusSide itself, not only by the focus listener
        a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT));
        run(300);
        int zone = field(a, "zone");
        Assert.assertEquals("User must be able to reach SIDE (0) to open Settings when grid and widgets are empty", 0, zone);
    }

    /** Category settings pages ('cats' and 'cat') open cleanly without errors. */
    @Test
    public void categorySettingsPagesOpenCleanly() throws Exception {
        HomeActivity a = launchWithCategoryApps(2, 2);
        Trouble.clear(c);
        call(a, "openSettings", new Class<?>[]{String.class}, "cats");
        run(800);
        Object settings = field(a, "settings");
        Assert.assertEquals("cats", field(settings, "page"));
        Assert.assertEquals(0, Trouble.count(c));
    }
}
