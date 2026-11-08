package app.aura.home;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.graphics.drawable.ColorDrawable;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
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
        // the profile's settings first: on this TV they share a file with the device's, and clearing
        // them after would take LOW away again (each test runs on LOW, as a 1 GB TV does, unless it says)
        Prefs.of(c).edit().clear().putString(Prefs.LAYOUT, "tvos").commit();
        Prefs.device(c).edit().putBoolean("welcomed", true).putString("set_light", "on").commit();
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

    /** Focus on home's last tile, then Down. */
    private static void downFromLastRow(HomeActivity a) throws Exception {
        List<?> cells = field(a, "cells");
        call(a, "focusGrid", new Class<?>[]{int.class}, cells.size() - 1);
        run(100);
        set(a, "held", false);
        a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN));
        a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN));
    }

    private static boolean hasText(ViewGroup g, String text) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View v = g.getChildAt(i);
            if (v instanceof TextView && text.contentEquals(((TextView) v).getText())) return true;
            if (v instanceof ViewGroup && hasText((ViewGroup) v, text)) return true;
        }
        return false;
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
        FrameLayout viewport = field(all, "viewport");
        Assert.assertSame(field(grids.get("video"), "view"), viewport.getChildAt(0));
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
        // the Movies & TV grid as it is now
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
    public void homeOnlySinksUnderAllAppsOnHigh() throws Exception {
        Prefs.device(c).edit().putString("set_light", "off").commit();
        HomeActivity a = home(2, 3);
        Assert.assertFalse("HIGH for this test", Perf.light(a));
        call(a, "openAllApps", new Class<?>[]{});
        run(200);
        View home = field(a, "home");
        // only its position moves: no layer of the whole home, nothing fading or scaling it
        Assert.assertTrue("it sinks", home.getTranslationY() > 0f);
        Assert.assertEquals(View.LAYER_TYPE_NONE, home.getLayerType());
        Assert.assertEquals(1f, home.getScaleX(), 0.001f);
        Assert.assertEquals(1f, home.getAlpha(), 0.001f);
        run(400);
        call(a, "closeAllApps", new Class<?>[]{});
        Assert.assertEquals(View.VISIBLE, home.getVisibility());
    }

    @Test
    public void reopeningKeepsEachTabsTilesWhileTheAppsAreTheSame() throws Exception {
        HomeActivity a = home(2, 4);
        call(a, "openAllApps", new Class<?>[]{});
        run(600);
        AllApps all = field(a, "allApps");
        Map<String, Object> grids = field(all, "grids");
        Object first = grids.get(Categories.ALL);
        call(a, "closeAllApps", new Class<?>[]{});
        run(600);
        call(a, "openAllApps", new Class<?>[]{});
        run(600);
        Map<String, Object> again = field(all, "grids");
        Assert.assertSame("the same tiles, nothing built or painted again", first, again.get(Categories.ALL));
        // their tabs too: the top row still leads up to a tab that's on screen
        Card[] cards = field(first, "cards");
        List<TextView> tabViews = field(all, "tabViews");
        Assert.assertEquals(tabViews.get(0).getId(), cards[0].getNextFocusUpId());
        Assert.assertSame(tabViews.get(0).getParent(), field(all, "tabRow"));
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
        FrameLayout scroll = field(all, "viewport");
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

    @Test
    public void theGridGlidesToKeepTheRowInFocusInView() throws Exception {
        HomeActivity a = home(2, 28);
        call(a, "openAllApps", new Class<?>[]{});
        run(600);
        AllApps all = field(a, "allApps");
        Map<String, Object> grids = field(all, "grids");
        Card[] cards = field(grids.get(Categories.ALL), "cards");
        // six rows of five: down to the fourth row, the grid glides up and the row stays in the window
        call(all, "requestChildFocus", new Class<?>[]{View.class, View.class}, cards[15], cards[15]);
        run(900);
        float scrolled = ((Spring.Glide) field(all, "glide")).value();
        Assert.assertTrue("glided up by " + scrolled, scrolled > 0f);
        // nothing in the window clips the grid to its own bounds (a tile in focus at its edge was cut
        // there); the column around the window clips it, at the window's edges
        FrameLayout vp = field(all, "viewport");
        Assert.assertFalse(vp.getClipChildren());
        Assert.assertTrue(((ViewGroup) vp.getParent()).getClipChildren());
        View grid0 = (View) ((View) cards[0].getParent()).getParent().getParent();
        Assert.assertFalse(((ViewGroup) grid0).getClipChildren());
        Assert.assertFalse(((ViewGroup) cards[0].getParent().getParent()).getClipChildren());
        FrameLayout viewport = field(all, "viewport");
        int[] v = new int[2], c4 = new int[2];
        viewport.getLocationInWindow(v);
        cards[15].getLocationInWindow(c4);
        Assert.assertTrue("the fourth row inside the window", c4[1] >= v[1] && c4[1] + cards[15].getHeight() <= v[1] + viewport.getHeight());
        // the last row: second from the top like every row, whole inside the window (not cut at the grid's
        // first height), and nothing of the rows above it showing but the one right above
        Card last = cards[cards.length - 1];
        call(all, "requestChildFocus", new Class<?>[]{View.class, View.class}, last, last);
        run(900);
        int[] cl = new int[2];
        last.getLocationInWindow(cl);
        Assert.assertTrue("the last row's tiles end at " + (cl[1] + last.getHeight()) + ", the window at " + (v[1] + viewport.getHeight()),
                cl[1] + last.getHeight() <= v[1] + viewport.getHeight());
        int[] above = new int[2];
        cards[cards.length - 1 - AllApps.COLS * 2].getLocationInWindow(above);
        Assert.assertTrue("two rows up is out of the window", above[1] + cards[0].getHeight() <= v[1]);
        View grid = (View) ((View) last.getParent()).getParent().getParent();
        Assert.assertTrue("the grid laid out at its whole height", grid.getHeight() > viewport.getHeight());
        // back to the first row: the grid glides back to its top
        call(all, "requestChildFocus", new Class<?>[]{View.class, View.class}, cards[0], cards[0]);
        run(900);
        Assert.assertEquals(0f, ((Spring.Glide) field(all, "glide")).value(), 0.5f);
    }

    @Test
    public void aTabSwitchSlidesTheAppsOutThenTheNextIn() throws Exception {
        Prefs.device(c).edit().putString("set_light", "off").commit();
        HomeActivity a = home(2, 4);
        call(a, "openAllApps", new Class<?>[]{});
        run(600);
        AllApps all = field(a, "allApps");
        Map<String, Object> grids = field(all, "grids");
        Object every = grids.get(Categories.ALL);
        call(all, "show", new Class<?>[]{int.class, boolean.class}, 1, true);
        FrameLayout viewport = field(all, "viewport");
        // the apps on show stay while they slide out; the next tab's come in after
        Assert.assertSame(field(every, "view"), viewport.getChildAt(0));
        run(AllApps.OUT_MS + 40);
        Assert.assertSame(field(grids.get("video"), "view"), viewport.getChildAt(0));
        run(AllApps.IN_MS + 100);
        Assert.assertEquals(1f, viewport.getAlpha(), 0.01f);
        Assert.assertEquals(0f, viewport.getTranslationX(), 0.5f);
    }

    @Test
    public void downOnATabMidSlideLandsOnItsApps() throws Exception {
        HomeActivity a = home(2, 4);
        call(a, "openAllApps", new Class<?>[]{});
        run(600);
        AllApps all = field(a, "allApps");
        call(all, "show", new Class<?>[]{int.class, boolean.class}, 1, true);
        // Down before the slide-out ends: the next tab's apps are put in place at once, focus on them
        call(all, "focusApps", new Class<?>[]{});
        Map<String, Object> grids = field(all, "grids");
        FrameLayout viewport = field(all, "viewport");
        Assert.assertSame(field(grids.get("video"), "view"), viewport.getChildAt(0));
        Assert.assertNull(field(all, "incoming"));
    }

    @Test
    public void highLightsTheGlassInTheColourOfTheAppInFocus() throws Exception {
        Prefs.device(c).edit().putString("set_light", "off").commit();
        HomeActivity a = home(2, 6);
        Assert.assertFalse("HIGH for this test", Perf.light(a));
        call(a, "openAllApps", new Class<?>[]{});
        run(600);
        AllApps all = field(a, "allApps");
        Map<String, Object> grids = field(all, "grids");
        Card[] cards = field(grids.get(Categories.ALL), "cards");
        call(all, "requestChildFocus", new Class<?>[]{View.class, View.class}, cards[1], cards[1]);
        run(800);
        View glow = field(all, "glow");
        Assert.assertNotNull("HIGH has the light once an app has the focus", glow);
        // centred on the second tile, as laid out (the light is a view its own size, moved by its translation)
        int[] g = new int[2], t = new int[2];
        glow.getLocationInWindow(g);
        cards[1].getLocationInWindow(t);
        Assert.assertEquals(t[0] + cards[1].getWidth() / 2f, g[0] + glow.getWidth() / 2f, 3f);
        Assert.assertEquals(((AppRepo.App) cards[1].getTag()).color, (int) (Integer) field(glow, "color"));
        Assert.assertTrue("it shows", glow.getAlpha() > 0.9f);
        // closing: the light goes with the screen
        call(a, "closeAllApps", new Class<?>[]{});
        run(600);
        Assert.assertEquals(0f, glow.getAlpha(), 0.01f);
    }

    @Test
    public void lowHasNoLightBehindTheApps() throws Exception {
        HomeActivity a = home(2, 3);
        Assert.assertNull(field((AllApps) field(a, "allApps"), "glow"));
    }

    @Test
    public void appsWithoutABannerAreGivenOneWithTheirName() throws Exception {
        AppRepo.App x = app("com.example.plain", "Plain App");
        android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(296, 168, android.graphics.Bitmap.Config.ARGB_8888);
        Tiles.tvos(x).paint(new android.graphics.Canvas(b), 296, 168);
        // a deep pane in the app's colour, not its vivid colour: darker than the colour itself
        int px = b.getPixel(280, 150);
        Assert.assertTrue("deep ground", android.graphics.Color.red(px) + android.graphics.Color.green(px) + android.graphics.Color.blue(px)
                < android.graphics.Color.red(0xFF335577) + android.graphics.Color.green(0xFF335577) + android.graphics.Color.blue(0xFF335577));
        // the name set to the right of the icon: white pixels there
        boolean text = false;
        for (int xx = 150; xx < 280 && !text; xx++) for (int yy = 60; yy < 110 && !text; yy++) text = b.getPixel(xx, yy) == 0xFFFFFFFF
                || android.graphics.Color.red(b.getPixel(xx, yy)) > 230 && android.graphics.Color.blue(b.getPixel(xx, yy)) > 230;
        Assert.assertTrue("its name beside the icon", text);
    }

    @Test
    public void lowSwapsTabsAtOnceAndSlidesThemInByPositionOnly() throws Exception {
        HomeActivity a = home(2, 4);
        call(a, "openAllApps", new Class<?>[]{});
        run(600);
        AllApps all = field(a, "allApps");
        call(all, "show", new Class<?>[]{int.class, boolean.class}, 1, true);
        Map<String, Object> grids = field(all, "grids");
        FrameLayout viewport = field(all, "viewport");
        // no slide-out on LOW: the next tab's apps are there at once, sliding the last bit of the way
        Assert.assertSame(field(grids.get("video"), "view"), viewport.getChildAt(0));
        Assert.assertEquals("no fade (no layer the size of the window)", 1f, viewport.getAlpha(), 0.001f);
        Assert.assertTrue(viewport.getTranslationX() > 0f);
        run(AllApps.IN_MS + 100);
        Assert.assertEquals(0f, viewport.getTranslationX(), 0.5f);
    }

    @Test
    public void thePhotographRestsWhileAllAppsCoversIt() throws Exception {
        HomeActivity a = home(2, 3);
        Backdrop backdrop = field(a, "backdrop");
        Assert.assertTrue("drifting on home", (boolean) (Boolean) field(backdrop, "running"));
        call(a, "openAllApps", new Class<?>[]{});
        run(500);
        Assert.assertFalse("at rest under the glass", (boolean) (Boolean) field(backdrop, "running"));
        call(a, "closeAllApps", new Class<?>[]{});
        Assert.assertTrue("back as All Apps goes", (boolean) (Boolean) field(backdrop, "running"));
    }

    @Test
    public void badgeGroundsAreRichNotMuddy() {
        float[] hsv = new float[3];
        // an olive Android placeholder green: calmed toward graphite, not a muddy olive
        android.graphics.Color.colorToHSV(Tiles.ground(0xFF8BC34A)[0], hsv);
        Assert.assertTrue("olive calmed: saturation " + hsv[1], hsv[1] < 0.35f);
        // a grey icon: clean graphite
        Assert.assertEquals(0xFF3C4048, Tiles.ground(0xFF9E9E9E)[0]);
        // a blue icon: a rich blue, light at the top to deep at the foot
        int[] blue = Tiles.ground(0xFF2196F3);
        android.graphics.Color.colorToHSV(blue[0], hsv);
        Assert.assertTrue(hsv[1] >= 0.42f && hsv[0] > 190f && hsv[0] < 220f);
        float top = hsv[2];
        android.graphics.Color.colorToHSV(blue[1], hsv);
        Assert.assertTrue("deeper at the foot", hsv[2] < top);
    }

    @Test
    public void holdingOkOnAnAppOpensItsMenuOverAllApps() throws Exception {
        HomeActivity a = home(2, 4);
        call(a, "openAllApps", new Class<?>[]{});
        run(600);
        AllApps all = field(a, "allApps");
        Map<String, Object> grids = field(all, "grids");
        Card[] cards = field(grids.get(Categories.ALL), "cards");
        cards[2].onLongClick.run();
        run(300);
        AppMenu menu = field(a, "menu");
        Assert.assertTrue("the app's menu", menu.isOpen());
        Assert.assertTrue("All Apps stays under it", all.isOpen());
        // Back closes the menu only: All Apps stays, home stays out of reach
        a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK));
        a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK));
        run(300);
        Assert.assertFalse(menu.isOpen());
        Assert.assertTrue(all.isOpen());
        View home = field(a, "home");
        Assert.assertEquals(ViewGroup.FOCUS_BLOCK_DESCENDANTS, ((ViewGroup) home).getDescendantFocusability());
    }

    @Test
    public void hidingAnAppFromItsMenuTakesItOutOfAllApps() throws Exception {
        HomeActivity a = home(2, 4);
        call(a, "openAllApps", new Class<?>[]{});
        run(600);
        AllApps all = field(a, "allApps");
        Map<String, Object> grids = field(all, "grids");
        Card[] cards = field(grids.get(Categories.ALL), "cards");
        String pkg = ((AppRepo.App) cards[3].getTag()).pkg;
        call(a, "allAppsMenu", new Class<?>[]{AppRepo.App.class, Card.class}, cards[3].getTag(), cards[3]);
        Runnable hide = menuRow(a, c.getString(R.string.hide_from_home));
        Assert.assertNotNull("Hide from home is offered", hide);
        hide.run();
        run(600);
        Assert.assertTrue(all.isOpen());
        List<AllApps.Tab> tabs = field(all, "tabs");
        for (AppRepo.App x : tabs.get(0).apps) Assert.assertNotEquals(pkg, x.pkg);
    }

    private static Runnable menuRow(HomeActivity a, String label) throws Exception {
        AppMenu menu = field(a, "menu");
        ViewGroup list = field(menu, "list");
        for (int i = 0; i < list.getChildCount(); i++) {
            View row = list.getChildAt(i);
            if (row instanceof Pad && hasText((ViewGroup) row, label)) return ((Pad) row).onClick;
        }
        return null;
    }

    private static AllApps openedAllApps(HomeActivity a) throws Exception {
        call(a, "openAllApps", new Class<?>[]{});
        run(600);
        return field(a, "allApps");
    }

    private static TextView tabFor(AllApps all, String id) throws Exception {
        List<AllApps.Tab> tabs = field(all, "tabs");
        List<TextView> views = field(all, "tabViews");
        for (int i = 0; i < tabs.size(); i++) if (tabs.get(i).id.equals(id)) return views.get(i);
        return null;
    }

    /** Where one edge of the tab pill is now. */
    private static float edge(AllApps.Tabs tabs, String name) throws Exception {
        return ((Spring.Mover) field(tabs, name)).value();
    }

    private static List<String> catIds() {
        List<String> out = new ArrayList<>();
        for (Categories.Cat k : Categories.all(RuntimeEnvironment.getApplication())) out.add(k.id);
        return out;
    }

    @Test
    public void homeIsTwoRowsWithNoCategoriesAndAllAppsHoldsTheRest() throws Exception {
        HomeActivity a = home(6, 14);
        List<?> cells = field(a, "cells");
        int cols = field(a, "COLS");
        Assert.assertEquals("the dock and one row under it", 2 * cols, cells.size());
        Assert.assertTrue("no category rows on home", ((List<?>) field(a, "rowInfo")).isEmpty());
        // Down from the second row: All Apps, with every app
        set(a, "held", false);
        call(a, "focusGrid", new Class<?>[]{int.class}, cols + 1);
        run(100);
        a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN));
        a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN));
        run(500);
        AllApps all = field(a, "allApps");
        Assert.assertTrue("Down past the second row opens All Apps", all.isOpen());
        List<AllApps.Tab> tabs = field(all, "tabs");
        Assert.assertEquals(20, tabs.get(0).apps.size());
        Assert.assertTrue("the categories are its tabs", ids(all).contains("video"));
    }

    @Test
    public void theAuraStyleGridKeepsEveryApp() throws Exception {
        Prefs.of(c).edit().putString(Prefs.LAYOUT, "aura").commit();
        HomeActivity a = home(6, 14);
        Assert.assertEquals(20, ((List<?>) field(a, "cells")).size());
        Assert.assertTrue(((List<?>) field(a, "rowInfo")).isEmpty());
    }

    @Test
    public void arrangingTheTwoRowsKeepsTheOrderOfTheAppsAfterThem() throws Exception {
        HomeActivity a = home(6, 14);
        List<String> before = new ArrayList<>();
        List<AppRepo.App> apps = field(a, "apps");
        for (AppRepo.App x : apps) before.add(x.pkg);
        AppRepo.saveDock(Prefs.of(c), before);
        call(a, "populate", new Class<?>[]{});
        call(a, "focusGrid", new Class<?>[]{int.class}, 0);
        set(a, "editing", true);
        call(a, "stopEdit", new Class<?>[]{});
        Assert.assertEquals("nothing past the second row lost its place", before, AppRepo.order(Prefs.of(c)));
    }

    @Test
    public void holdingOkOnATabOpensItsCategory() throws Exception {
        HomeActivity a = home(2, 3);
        AllApps all = openedAllApps(a);
        TextView tab = tabFor(all, "video");
        // the test starts in touch mode, where a D-pad focus only lands after a key; a remote is never in it
        tab.setFocusableInTouchMode(true);
        Assert.assertTrue(tab.requestFocus());
        a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER));
        run(android.view.ViewConfiguration.getLongPressTimeout() + 80);
        AppMenu menu = field(a, "menu");
        Assert.assertTrue("Hold OK on a tab opens its category", menu.isOpen());
        Assert.assertNotNull(menuRow(a, c.getString(R.string.rename)));
        Assert.assertNotNull("a built-in category can be hidden", menuRow(a, c.getString(R.string.hide)));
        Assert.assertNull("All apps always leads: nothing moves before it", menuRow(a, c.getString(R.string.move_left)));
        Assert.assertNull("a built-in category can't be deleted", menuRow(a, c.getString(R.string.delete_category)));
        a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER));
        run(100);
        Assert.assertTrue("letting go of the hold presses nothing in the menu", menu.isOpen());
    }

    @Test
    public void aShortOkOnATabOpensNoMenu() throws Exception {
        HomeActivity a = home(2, 3);
        AllApps all = openedAllApps(a);
        TextView tab = tabFor(all, "video");
        tab.setFocusableInTouchMode(true);
        Assert.assertTrue(tab.requestFocus());
        a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER));
        run(80);
        a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER));
        run(android.view.ViewConfiguration.getLongPressTimeout() + 80);
        Assert.assertFalse(((AppMenu) field(a, "menu")).isOpen());
        Assert.assertEquals("its apps come in", "video", all.currentId());
    }

    @Test
    public void hidingABuiltInCategoryTakesItsTabAway() throws Exception {
        HomeActivity a = home(2, 3);
        AllApps all = openedAllApps(a);
        call(a, "categoryMenu", new Class<?>[]{String.class, View.class}, "video", tabFor(all, "video"));
        menuRow(a, c.getString(R.string.hide)).run();
        run(400);
        Assert.assertTrue(all.isOpen());
        Assert.assertFalse(ids(all).contains("video"));
        Assert.assertTrue(Categories.find(Categories.all(c), "video").hidden);
    }

    @Test
    public void movingAndDeletingACategoryOfYourOwn() throws Exception {
        String id = Categories.create(c, "Mine");
        Categories.addTo(c, id, "com.example.tool0");
        HomeActivity a = home(2, 3);
        AllApps all = openedAllApps(a);
        Assert.assertEquals(Arrays.asList(Categories.ALL, "video", id), ids(all));
        call(a, "categoryMenu", new Class<?>[]{String.class, View.class}, id, tabFor(all, id));
        Assert.assertNull("the last tab moves no further right", menuRow(a, c.getString(R.string.move_right)));
        menuRow(a, c.getString(R.string.move_left)).run();
        run(400);
        Assert.assertEquals("one tab along, past nothing unseen", Arrays.asList(Categories.ALL, id, "video"), ids(all));
        call(a, "categoryMenu", new Class<?>[]{String.class, View.class}, id, tabFor(all, id));
        menuRow(a, c.getString(R.string.delete_category)).run();
        run(200);
        Assert.assertNotNull("the first OK asks again", menuRow(a, c.getString(R.string.press_ok_again)));
        Assert.assertTrue(catIds().contains(id));
        menuRow(a, c.getString(R.string.press_ok_again)).run();
        run(400);
        Assert.assertFalse(catIds().contains(id));
        Assert.assertFalse(ids(all).contains(id));
        Assert.assertTrue(all.isOpen());
    }

    @Test
    public void anAppComesOutOfTheCategoryOnShow() throws Exception {
        HomeActivity a = home(2, 3);
        AllApps all = openedAllApps(a);
        Map<String, Object> grids = field(all, "grids");
        // on All apps there's nothing to come out of
        Card[] every = field(grids.get(Categories.ALL), "cards");
        call(a, "allAppsMenu", new Class<?>[]{AppRepo.App.class, Card.class}, every[0].getTag(), every[0]);
        String allName = Categories.name(c, Categories.find(Categories.all(c), Categories.ALL));
        Assert.assertNull(menuRow(a, c.getString(R.string.remove_from_row, allName)));
        call(a, "closeMenu", new Class<?>[]{View.class}, every[0]);
        call(all, "show", new Class<?>[]{int.class, boolean.class}, 1, false);
        Card[] video = field(grids.get("video"), "cards");
        String pkg = ((AppRepo.App) video[0].getTag()).pkg;
        call(a, "allAppsMenu", new Class<?>[]{AppRepo.App.class, Card.class}, video[0].getTag(), video[0]);
        String name = Categories.name(c, Categories.find(Categories.all(c), "video"));
        menuRow(a, c.getString(R.string.remove_from_row, name)).run();
        run(400);
        List<AllApps.Tab> tabs = field(all, "tabs");
        for (AllApps.Tab t : tabs) {
            if (!t.id.equals("video")) continue;
            for (AppRepo.App x : t.apps) Assert.assertNotEquals(pkg, x.pkg);
        }
        Assert.assertEquals("still in All apps", 5, tabs.get(0).apps.size());
    }

    @Test
    public void homeSinksAsAllAppsRisesAndComesBackAsItFalls() throws Exception {
        HomeActivity a = home(2, 4);
        View home = field(a, "home");
        call(a, "openAllApps", new Class<?>[]{});
        run(600);
        Assert.assertEquals("home falls back under the glass", Ui.dpf(14), home.getTranslationY(), 1f);
        AllApps all = field(a, "allApps");
        call(a, "closeAllApps", new Class<?>[]{});
        run(AllApps.CLOSE_MS / 2);
        float mid = home.getTranslationY();
        Assert.assertTrue("and rises as All Apps falls away: " + mid, mid > 0.5f && mid < Ui.dpf(14));
        Assert.assertTrue("the screen is still on its way out", all.getAlpha() > 0f && all.getVisibility() == View.VISIBLE);
        run(900);
        Assert.assertEquals(0f, home.getTranslationY(), 0.5f);
        Assert.assertEquals(View.GONE, all.getVisibility());
    }

    @Test
    public void closingFallsQuickestAsItGoes() throws Exception {
        Prefs.device(c).edit().putString("set_light", "off").commit();
        HomeActivity a = home(2, 4);
        call(a, "openAllApps", new Class<?>[]{});
        run(800);
        AllApps all = field(a, "allApps");
        call(a, "closeAllApps", new Class<?>[]{});
        run(AllApps.CLOSE_MS / 3);
        float early = 1f - all.getAlpha();
        run(AllApps.CLOSE_MS / 3);
        float later = 1f - all.getAlpha() - early;
        Assert.assertTrue("slow to leave, quicker as it goes: " + early + " then " + later, later > early);
    }

    @Test
    public void thePillStretchesTowardTheNextTabAndGathersThere() throws Exception {
        String id = Categories.create(c, "Mine");
        Categories.addTo(c, id, "com.example.tool0");
        HomeActivity a = home(2, 3);
        AllApps all = openedAllApps(a);
        AllApps.Tabs tabs = field(all, "tabRow");
        TextView from = tabFor(all, Categories.ALL), to = tabFor(all, id);
        float l0 = edge(tabs, "left"), r0 = edge(tabs, "right");
        tabs.to(to, true);
        run(64);
        float l = edge(tabs, "left"), r = edge(tabs, "right");
        float lead = (r - r0) / (to.getRight() - r0), trail = (l - l0) / (to.getLeft() - l0);
        Assert.assertTrue("heading right, the right edge leads: " + lead + " vs " + trail, lead > trail + 0.05f);
        Assert.assertTrue("so the pill is wider than either tab on the way", r - l > Math.max(from.getWidth(), to.getWidth()));
        run(900);
        Assert.assertEquals(to.getLeft(), edge(tabs, "left"), 0.5f);
        Assert.assertEquals(to.getRight(), edge(tabs, "right"), 0.5f);
        // and back the other way: now the left edge leads
        tabs.to(from, true);
        run(64);
        l = edge(tabs, "left");
        r = edge(tabs, "right");
        lead = (to.getLeft() - l) / (float) (to.getLeft() - from.getLeft());
        trail = (to.getRight() - r) / (float) (to.getRight() - from.getRight());
        Assert.assertTrue("heading left, the left edge leads: " + lead + " vs " + trail, lead > trail + 0.05f);
    }

    @Test
    public void aTabInFocusGrowsAndSettlesBackOnceFocusGoesToTheApps() throws Exception {
        HomeActivity a = home(2, 3);
        AllApps all = openedAllApps(a);
        TextView tab = tabFor(all, "video");
        tab.setFocusableInTouchMode(true);
        Assert.assertTrue(tab.requestFocus());
        run(600);
        Assert.assertEquals(AllApps.TAB_GROW, tab.getScaleX(), 0.005f);
        Map<String, Object> grids = field(all, "grids");
        Card first = ((Card[]) field(grids.get("video"), "cards"))[0];
        first.setFocusableInTouchMode(true);
        Assert.assertTrue(first.requestFocus());
        run(600);
        Assert.assertEquals(1f, tab.getScaleX(), 0.005f);
    }

    @Test
    public void highATabsFirstRowLeadsTheRowsUnderIt() throws Exception {
        Prefs.device(c).edit().putString("set_light", "off").commit();
        HomeActivity a = home(2, 16);
        AllApps all = openedAllApps(a);
        call(all, "show", new Class<?>[]{int.class, boolean.class}, 1, true);
        call(all, "show", new Class<?>[]{int.class, boolean.class}, 0, true);
        run(AllApps.OUT_MS + 20 + 60);
        Map<String, Object> grids = field(all, "grids");
        LinearLayout grid = field(grids.get(Categories.ALL), "view");
        Assert.assertTrue("several rows", grid.getChildCount() >= 3);
        float r0 = Math.abs(grid.getChildAt(0).getTranslationX()), r1 = Math.abs(grid.getChildAt(1).getTranslationX()),
                r2 = Math.abs(grid.getChildAt(2).getTranslationX());
        Assert.assertTrue("each row trails the one above: " + r0 + ", " + r1 + ", " + r2, r0 < r1 && r1 < r2);
        run(900);
        for (int i = 0; i < grid.getChildCount(); i++) Assert.assertEquals(0f, grid.getChildAt(i).getTranslationX(), 0.5f);
    }
}
