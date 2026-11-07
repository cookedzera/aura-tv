package app.aura.home;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.graphics.drawable.ColorDrawable;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;

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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.robolectric.Shadows.shadowOf;

/**
 * All Apps, the tvOS style's screen past home's last row: a fresh Down there opens it (a held one
 * doesn't, nor does the Aura style); its tabs are every app first, then each category with
 * something in it; the tiles' arrows stay inside it and lead up to their own tab; Back puts home
 * back as it was.
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30, application = AuraApp.class, qualifiers = "w960dp-h540dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AllAppsTest {
    private Context c;

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

    @Before
    public void setUp() throws Exception {
        c = RuntimeEnvironment.getApplication();
        Prefs.device(c).edit().clear().putBoolean("welcomed", true).putString("set_light", "on").commit();
        Prefs.of(c).edit().clear().putString(Prefs.LAYOUT, "tvos").putBoolean(Categories.ROWS, true).commit();
        org.robolectric.shadows.ShadowChoreographer.setPaused(true);
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(Duration.ofMillis(16));
        Field w = HomeActivity.class.getDeclaredField("warm");
        w.setAccessible(true);
        w.setBoolean(null, true);
        Field cache = HomeActivity.class.getDeclaredField("cache");
        cache.setAccessible(true);
        cache.set(null, null);
    }

    /** Home with {@code video} video apps and {@code tools} apps that say nothing of what they are. */
    private HomeActivity home(int video, int tools) throws Exception {
        List<AppRepo.App> fake = new ArrayList<>();
        for (int i = 0; i < video; i++) {
            install("com.example.video" + i, "Video " + i, ApplicationInfo.CATEGORY_VIDEO);
            fake.add(app("com.example.video" + i, "Video " + i));
        }
        for (int i = 0; i < tools; i++) {
            install("com.example.tool" + i, "Tool " + i, ApplicationInfo.CATEGORY_UNDEFINED);
            fake.add(app("com.example.tool" + i, "Tool " + i));
        }
        HomeActivity a = Robolectric.buildActivity(HomeActivity.class).setup().get();
        run(1500);
        List<AppRepo.App> apps = field(a, "apps");
        apps.clear();
        apps.addAll(fake);
        call(a, "populate", new Class<?>[]{});
        run(500);
        return a;
    }

    /** Focus on the last row's first tile, then Down. */
    private static void downFromLastRow(HomeActivity a) throws Exception {
        List<?> rows = field(a, "rowInfo");
        Object last = rows.get(rows.size() - 1);
        call(a, "focusGrid", new Class<?>[]{int.class}, (int) (Integer) field(last, "start"));
        run(100);
        set(a, "held", false);
        a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN));
        a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN));
    }

    private static List<String> ids(AllApps all) throws Exception {
        List<AllApps.Tab> tabs = field(all, "tabs");
        List<String> out = new ArrayList<>();
        for (AllApps.Tab t : tabs) out.add(t.id);
        return out;
    }

    @Test
    public void downPastTheLastRowOpensAllAppsWithEveryAppFirst() throws Exception {
        HomeActivity a = home(2, 3);
        AllApps all = field(a, "allApps");
        Assert.assertFalse(all.isOpen());
        downFromLastRow(a);
        run(400);
        Assert.assertTrue("Down past the last row opens All Apps in the tvOS style", all.isOpen());
        Assert.assertEquals(View.VISIBLE, all.getVisibility());
        List<AllApps.Tab> tabs = field(all, "tabs");
        // every app first, then the categories with something in them: no Favorites, Music, Games or News here
        Assert.assertEquals(Categories.ALL, tabs.get(0).id);
        Assert.assertEquals(5, tabs.get(0).apps.size());
        Assert.assertEquals(Arrays.asList(Categories.ALL, "video"), ids(all));
        Assert.assertEquals(2, tabs.get(1).apps.size());
        Assert.assertEquals("opens on every app", 0, all.current);
        // home is out of reach of the arrows while it's open
        View home = field(a, "home");
        Assert.assertEquals(ViewGroup.FOCUS_BLOCK_DESCENDANTS, ((ViewGroup) home).getDescendantFocusability());
    }

    @Test
    public void aHeldDownStopsAtTheLastRow() throws Exception {
        HomeActivity a = home(2, 3);
        set(a, "held", true);
        call(a, "downPastEnd", new Class<?>[]{});
        run(300);
        Assert.assertFalse("a held Down never carries into All Apps", ((AllApps) field(a, "allApps")).isOpen());
    }

    @Test
    public void theAuraStyleKeepsItsBounce() throws Exception {
        Prefs.of(c).edit().putString(Prefs.LAYOUT, "aura").commit();
        HomeActivity a = home(2, 3);
        downFromLastRow(a);
        run(300);
        Assert.assertFalse(((AllApps) field(a, "allApps")).isOpen());
    }

    @Test
    public void hiddenAppsStayOutOfIt() throws Exception {
        Prefs.hide(c, "com.example.tool0", true);
        HomeActivity a = home(2, 3);
        call(a, "openAllApps", new Class<?>[]{});
        run(300);
        List<AllApps.Tab> tabs = field((AllApps) field(a, "allApps"), "tabs");
        for (AllApps.Tab t : tabs) for (AppRepo.App x : t.apps) Assert.assertNotEquals("com.example.tool0", x.pkg);
        Assert.assertEquals(4, tabs.get(0).apps.size());
    }

    @Test
    public void tilesLeadUpToTheirTabAndStayInsideTheGrid() throws Exception {
        HomeActivity a = home(2, 10);
        call(a, "openAllApps", new Class<?>[]{});
        run(400);
        AllApps all = field(a, "allApps");
        List<TextView> tabViews = field(all, "tabViews");
        Map<String, Object> grids = field(all, "grids");
        Card[] cards = field(grids.get(Categories.ALL), "cards");
        Assert.assertEquals(12, cards.length);
        // the top row leads up to its tab, the rows below to the tile above
        for (int i = 0; i < AllApps.COLS; i++) Assert.assertEquals(tabViews.get(0).getId(), cards[i].getNextFocusUpId());
        Assert.assertEquals(cards[0].getId(), cards[AllApps.COLS].getNextFocusUpId());
        // the edges hold: no arrow leaves the grid for home beneath
        Assert.assertEquals(cards[0].getId(), cards[0].getNextFocusLeftId());
        Assert.assertEquals(cards[AllApps.COLS - 1].getId(), cards[AllApps.COLS - 1].getNextFocusRightId());
        Assert.assertEquals(cards[11].getId(), cards[11].getNextFocusDownId());
        Assert.assertEquals(tabViews.get(0).getId(), tabViews.get(0).getNextFocusLeftId());
    }

    @Test
    public void anotherTabShowsItsOwnApps() throws Exception {
        HomeActivity a = home(2, 4);
        call(a, "openAllApps", new Class<?>[]{});
        run(400);
        AllApps all = field(a, "allApps");
        call(all, "show", new Class<?>[]{int.class, boolean.class}, 1, true);
        run(300);
        Assert.assertEquals(1, all.current);
        Map<String, Object> grids = field(all, "grids");
        Card[] cards = field(grids.get("video"), "cards");
        Assert.assertEquals(2, cards.length);
        ScrollView scroll = field(all, "scroll");
        Assert.assertSame(field(grids.get("video"), "view"), scroll.getChildAt(0));
        run(100);
        // a short tab starts at the same left edge as All apps: nothing jumps sideways between tabs
        call(all, "show", new Class<?>[]{int.class, boolean.class}, 0, false);
        run(100);
        Card[] every = field(grids.get(Categories.ALL), "cards");
        int[] x0 = new int[2];
        every[0].getLocationInWindow(x0);
        call(all, "show", new Class<?>[]{int.class, boolean.class}, 1, false);
        run(100);
        int[] x1 = new int[2];
        // LOW keeps one tab's tiles: the Movies & TV grid is made again
        ((Card[]) field(grids.get("video"), "cards"))[0].getLocationInWindow(x1);
        Assert.assertEquals(x0[0], x1[0]);
    }

    @Test
    public void backPutsHomeBackAsItWas() throws Exception {
        HomeActivity a = home(2, 3);
        downFromLastRow(a);
        run(400);
        AllApps all = field(a, "allApps");
        Assert.assertTrue(all.isOpen());
        int before = field(a, "gridAt");
        a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK));
        a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK));
        run(600);
        Assert.assertFalse(all.isOpen());
        Assert.assertEquals(View.GONE, all.getVisibility());
        View home = field(a, "home");
        Assert.assertEquals(View.VISIBLE, home.getVisibility());
        Assert.assertEquals(1f, home.getAlpha(), 0.01f);
        Assert.assertEquals(0f, home.getTranslationY(), 0.01f);
        Assert.assertEquals(ViewGroup.FOCUS_AFTER_DESCENDANTS, ((ViewGroup) home).getDescendantFocusability());
        Assert.assertEquals("back on the tile it left from", before, (int) (Integer) field(a, "gridAt"));
    }

    @Test
    public void highLiftsHomeAwayAndBringsItBack() throws Exception {
        Prefs.device(c).edit().putString("set_light", "off").commit();
        HomeActivity a = home(2, 3);
        Assert.assertFalse("HIGH for this test", Perf.light(a));
        call(a, "openAllApps", new Class<?>[]{});
        run(200);
        View home = field(a, "home");
        Assert.assertTrue("home lifts as All Apps rises", home.getTranslationY() < 0f);
        run(400);
        call(a, "closeAllApps", new Class<?>[]{boolean.class}, true);
        run(600);
        Assert.assertEquals(1f, home.getAlpha(), 0.01f);
        Assert.assertEquals(0f, home.getTranslationY(), 0.01f);
    }

    @Test
    public void openingAnAppPutsHomeBackAtOnce() throws Exception {
        Prefs.device(c).edit().putString("set_light", "off").commit();
        HomeActivity a = home(2, 3);
        call(a, "openAllApps", new Class<?>[]{});
        run(400);
        // as an app opens over it: no rise of home's rows under the app's zoom
        call(a, "closeAllApps", new Class<?>[]{boolean.class}, false);
        View home = field(a, "home");
        Assert.assertEquals(1f, home.getAlpha(), 0.001f);
        Assert.assertEquals(0f, home.getTranslationY(), 0.001f);
    }

    @Test
    public void tilesArePaintedBehindTheScreen() throws Exception {
        HomeActivity a = home(2, 3);
        call(a, "openAllApps", new Class<?>[]{});
        Map<String, Object> grids = field((AllApps) field(a, "allApps"), "grids");
        Card[] cards = field(grids.get(Categories.ALL), "cards");
        // nothing painted on the main thread as it opens: each picture lands from the painter
        Assert.assertTrue(cards[0].deferred);
        for (int i = 0; i < 40 && cards[cards.length - 1].art() == null; i++) {
            Thread.sleep(25);
            run(32);
        }
        for (Card k : cards) Assert.assertNotNull("every tile gets its picture", k.art());
    }

    @Test
    public void theGridSitsOnScreenUnderCentredTabs() throws Exception {
        HomeActivity a = home(2, 10);
        call(a, "openAllApps", new Class<?>[]{});
        run(600);
        AllApps all = field(a, "allApps");
        ScrollView scroll = field(all, "scroll");
        View bar = field(all, "tabRow");
        int h = all.getHeight(), w = all.getWidth();
        Assert.assertTrue("laid out", h > 0 && w > 0);
        // the bar is the tabs' height, not the screen's: the grid sits right under it, on screen
        Assert.assertTrue("tab bar " + bar.getHeight() + " px tall", bar.getHeight() < h / 6);
        Assert.assertTrue("grid starts at " + scroll.getTop() + " of " + h, scroll.getTop() < h / 4);
        Assert.assertTrue("grid has room", scroll.getHeight() > h / 2);
        // the tabs are centred across the screen, not against its left edge
        int mid = bar.getLeft() + bar.getWidth() / 2;
        Assert.assertEquals(w / 2f, mid, w * 0.02f);
        // the pill lies under the first tab, the tab on show, even with the focus down in the apps
        Spring.Mover l = field(bar, "left"), r = field(bar, "right");
        View tab0 = ((ViewGroup) bar).getChildAt(0);
        Assert.assertEquals(tab0.getLeft(), l.value(), 1f);
        Assert.assertEquals(tab0.getRight(), r.value(), 1f);
        Map<String, Object> grids = field(all, "grids");
        Card[] cards = field(grids.get(Categories.ALL), "cards");
        int[] at = new int[2];
        cards[0].getLocationInWindow(at);
        Assert.assertTrue("first app on screen at y=" + at[1], at[1] > 0 && at[1] < h / 2);
    }
}
