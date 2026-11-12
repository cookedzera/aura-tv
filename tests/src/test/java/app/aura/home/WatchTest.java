package app.aura.home;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
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

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The Watch screen: a genre's shelf kept on the TV is shown without asking the network, the poster
 * in focus says what it is and lights the screen in its own colour, OK opens the title with its
 * choices, a saved title is on that profile's list with a poster of its own and counted on its tab,
 * the screen rests when left alone, a held OK decides, and a kids' profile has no Watch tab.
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30, application = AuraApp.class, qualifiers = "w960dp-h540dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class WatchTest {
    private Context c;

    private static void run(long ms) {
        for (long t = 0; t < ms; t += 16) ShadowLooper.idleMainLooper(16, TimeUnit.MILLISECONDS);
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object o, String name) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(o);
    }

    private static void wipe(File f) {
        File[] in = f.listFiles();
        if (in != null) for (File x : in) wipe(x);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    @Before
    public void setUp() throws Exception {
        c = RuntimeEnvironment.getApplication();
        Profiles.forget();
        Profiles.use(c, Profiles.MAIN);
        Prefs.of(c).edit().clear().putString(Prefs.LAYOUT, "tvos").commit();
        Prefs.device(c).edit().putBoolean("welcomed", true).putString("set_light", "on").commit();
        wipe(new File(c.getFilesDir(), "watch"));
        Field w = HomeActivity.class.getDeclaredField("warm");
        w.setAccessible(true);
        w.setBoolean(null, true);
    }

    /** A shelf of {@code n} titles for {@code genre}, as a fetch a moment ago would have kept it. */
    private void keep(String genre, int n) throws Exception {
        File dir = new File(c.getFilesDir(), "watch");
        Assert.assertTrue(dir.isDirectory() || dir.mkdirs());
        JSONArray a = new JSONArray();
        for (int i = 0; i < n; i++) {
            File img = new File(dir, "wm" + genre + i + ".jpg");
            Bitmap b = Bitmap.createBitmap(60, 90, Bitmap.Config.ARGB_8888);
            b.eraseColor(0xFF204060 + i);
            try (FileOutputStream fo = new FileOutputStream(img)) {
                b.compress(Bitmap.CompressFormat.JPEG, 80, fo);
            }
            a.put(new JSONObject().put("id", "m" + genre + i).put("name", "Film " + i).put("line", "Film  ·  ★ 7." + i + "  ·  Drama  ·  201" + i)
                    .put("about", "About film " + i + ".").put("poster", img.getAbsolutePath()).put("query", "Film " + i + " trailer")
                    .put("tmdb", "10" + i).put("type", "movie"));
        }
        // under the name a build with a TMDB key reads, or one without
        Files.write(new File(dir, (Picks.anyKey(c).isEmpty() ? "g-" : "t-") + genre + ".json").toPath(), a.toString().getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void aTitleSavedIsOnThatProfilesListWithAPosterOfItsOwn() throws Exception {
        keep("any", 3);
        List<Picks.Pick> shelf = Watch.shelf(c, Watch.ANY);
        Assert.assertEquals(3, shelf.size());
        Assert.assertFalse("kept a moment ago: not asked for again", Watch.stale(c, Watch.ANY));
        Assert.assertTrue("a genre never opened is", Watch.stale(c, "comedy"));

        Picks.Pick one = shelf.get(1);
        Assert.assertFalse(Watch.saved(c, one));
        Assert.assertTrue(Watch.toggle(c, one));
        Assert.assertTrue(Watch.saved(c, one));
        List<Picks.Pick> mine = Watch.mine(c);
        Assert.assertEquals(1, mine.size());
        Assert.assertEquals("Film 1", mine.get(0).name);
        Assert.assertEquals("and what's needed to ask where it streams", "101", mine.get(0).tmdb);
        Assert.assertEquals("movie", mine.get(0).type);
        Assert.assertNotEquals("its poster is a copy", one.poster, mine.get(0).poster);

        // the shelf is made again without it: the list keeps its own poster
        Assert.assertTrue(new File(one.poster).delete());
        Assert.assertEquals(1, Watch.mine(c).size());

        // another profile starts with a list of its own
        Profiles.Profile guest = Profiles.add(c, "Guest");
        Profiles.use(c, guest.id);
        Assert.assertEquals(0, Watch.mine(c).size());
        Profiles.use(c, Profiles.MAIN);

        File copy = new File(mine.get(0).poster);
        Assert.assertFalse("off the list again", Watch.toggle(c, mine.get(0)));
        Assert.assertEquals(0, Watch.mine(c).size());
        Assert.assertFalse("and its poster goes with it", copy.exists());
    }

    @Test
    public void aTitlesLineIsTakenApartAndItsColourComesFromItsPoster() {
        Watch.Facts f = new Watch.Facts("Film  ·  ★ 7.8  ·  Drama  ·  Crime  ·  2019");
        Assert.assertEquals(7.8f, f.score, 0.001f);
        Assert.assertEquals("2019", f.year);
        Assert.assertEquals("Film  ·  Drama  ·  Crime", f.kinds);
        Watch.Facts maze = new Watch.Facts("★ 9.2  ·  Drama  ·  2008");
        Assert.assertEquals(9.2f, maze.score, 0.001f);
        Assert.assertEquals("Drama", maze.kinds);
        Assert.assertEquals("a line with no score has none", 0f, new Watch.Facts("Series").score, 0f);

        Bitmap red = Bitmap.createBitmap(60, 90, Bitmap.Config.ARGB_8888);
        red.eraseColor(0xFF991111);
        int aura = Watch.aura(red);
        Assert.assertTrue("a red poster lights the screen red", Color.red(aura) > Color.green(aura) + 60 && Color.red(aura) > Color.blue(aura) + 60);
        Assert.assertTrue("bright enough to read on a dark screen", Color.red(aura) > 200);
        Bitmap grey = Bitmap.createBitmap(60, 90, Bitmap.Config.ARGB_8888);
        grey.eraseColor(0xFF777777);
        Assert.assertEquals("a poster with no colour gives the pale one", Watch.PALE, Watch.aura(grey));
        Assert.assertEquals(Watch.PALE, Watch.aura(null));
    }

    @Test
    public void aShelfKeptOnTheTvShowsAndThePosterInFocusSaysWhatItIs() throws Exception {
        keep("any", 9);
        HomeActivity a = Robolectric.buildActivity(HomeActivity.class).setup().get();
        run(1500);
        final Intent[] opened = new Intent[1];
        WatchScreen pane = new WatchScreen(a, (intent, from) -> opened[0] = intent);
        a.addContentView(pane, new FrameLayout.LayoutParams(1920, 1080));
        pane.open();
        Assert.assertTrue(pane.isOpen());
        // the shelf is read behind the screen and handed over
        Object shelf = null;
        for (int i = 0; i < 150 && shelf == null; i++) {
            Thread.sleep(20);
            run(32);
            shelf = field(pane, "installed");
        }
        Assert.assertNotNull("the kept shelf is on show", shelf);
        View[] posters = field(shelf, "posters");
        Assert.assertEquals(9, posters.length);
        Assert.assertEquals("one row", ((ViewGroup) posters[0].getParent()), posters[8].getParent());
        Object words = field(pane, "words");
        TextView name = field(words, "name"), score = field(words, "score"), line = field(words, "line"), kicker = field(words, "kicker");
        Assert.assertEquals("the first title is what the screen shows", "Film 0", name.getText().toString());
        Assert.assertTrue("and the focus is on it, the screen having opened before its shelf was read", posters[0].isFocused());

        Assert.assertTrue(posters[8].requestFocus());
        run(600);
        Assert.assertEquals("Film 8", name.getText().toString());
        Assert.assertEquals("its score beside its dial", "7.8", score.getText().toString());
        Assert.assertEquals("2018", line.getText().toString());
        Assert.assertEquals("its kind and genre over its name", "FILM  ·  DRAMA", kicker.getText().toString());
        Assert.assertTrue("it grows in focus", posters[8].getScaleX() > 1.05f);
        Assert.assertTrue("the one just passed stays, faint", posters[7].getAlpha() < 0.4f && posters[7].getAlpha() > 0.1f);
        Assert.assertEquals("left is the title before it", posters[7].getId(), posters[8].getNextFocusLeftId());
        TextView headName = field(pane, "headName"), headCount = field(pane, "headCount");
        Assert.assertEquals("the genre on show, over the row", c.getString(R.string.watch_anything).toUpperCase(), headName.getText().toString());
        Assert.assertEquals("09 / 09", headCount.getText().toString());
        ViewGroup row = (ViewGroup) posters[0].getParent();
        Assert.assertEquals("the row has glided so the focus stays where it was", -8f * a.getResources().getDisplayMetrics().density * WatchScreen.STEP,
                row.getTranslationX(), 2f);

        // its picture: with no wide one, its poster in a light of its own, baked behind the screen
        Object hero = field(pane, "hero");
        for (int i = 0; i < 150 && field(hero, "wide") == null; i++) {
            Thread.sleep(20);
            run(32);
        }
        Assert.assertNotNull("the picture behind is the title's", field(hero, "wide"));

        // left alone, the screen rests; a key wakes it
        Assert.assertEquals(0, (int) WatchTest.<Integer>field(pane, "pose"));
        run(WatchScreen.REST_MS + 200);
        Assert.assertEquals("at rest", 1, (int) WatchTest.<Integer>field(pane, "pose"));
        pane.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN));
        Assert.assertEquals("awake again", 0, (int) WatchTest.<Integer>field(pane, "pose"));

        // OK opens the title: its choices take the focus
        pane.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER));
        pane.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER));
        Object page = field(pane, "page");
        Assert.assertTrue("OK opens the title", WatchTest.<Boolean>field(page, "open"));
        run(800);
        Object acts = field(page, "acts");
        TextView[] tabs = field(acts, "tabs");
        Assert.assertTrue("its first choice has the focus", tabs[0].isFocused());
        Assert.assertEquals(c.getString(R.string.watch_trailer), tabs[0].getText().toString());
        Assert.assertEquals(c.getString(R.string.watch_save), tabs[2].getText().toString());
        pane.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER));
        pane.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER));
        Assert.assertNotNull("OK on Trailer asks home to open it", opened[0]);

        // Save: on the list, said so, and counted on My list's tab once the small poster lands
        Assert.assertTrue(tabs[2].requestFocus());
        pane.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER));
        pane.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER));
        Assert.assertEquals(1, Watch.mine(c).size());
        Assert.assertEquals(c.getString(R.string.watch_on_my_list), tabs[2].getText().toString());
        run(1200);
        Assert.assertEquals(1, (int) WatchTest.<Integer>field(pane, "mineCount"));
        TabStrip strip = field(pane, "strip");
        Assert.assertTrue("My list says how many it holds", strip.tab(0).getText().toString().endsWith("1"));

        // Back closes the title, not the screen, and the poster has the focus again
        Assert.assertTrue(pane.back());
        Assert.assertFalse(WatchTest.<Boolean>field(page, "open"));
        run(900);
        Assert.assertTrue(posters[8].isFocused());
        Assert.assertEquals(View.INVISIBLE, ((View) page).getVisibility());
        Assert.assertFalse("with no title open, Back is the screen's to leave", pane.back());

        // a held OK decides: the row spins and lands on another title, far enough to be a surprise
        pane.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER));
        run(android.view.ViewConfiguration.getLongPressTimeout() + 100);
        Assert.assertFalse("the hold was taken: the title it was held on has given up the focus or the row is spinning",
                posters[8].isFocused() && !WatchTest.<Boolean>field(pane, "spinning"));
        pane.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER));
        Assert.assertFalse("letting go of OK opens nothing", WatchTest.<Boolean>field(page, "open"));
        run(3200);
        Assert.assertFalse(WatchTest.<Boolean>field(pane, "spinning"));
        int landed = -1;
        for (int i = 0; i < posters.length; i++) if (posters[i].isFocused()) landed = i;
        Assert.assertTrue("landed at least four titles away, on " + landed, landed >= 0 && landed <= 4);
        Assert.assertEquals("and the screen says that title", "Film " + landed, name.getText().toString());

        pane.close();
        Assert.assertFalse(pane.isOpen());
        pane.release();
    }

    /** Where Watch's tile is among home's tiles, or -1. */
    private static int tileAt(HomeActivity a) throws Exception {
        List<Cell> cells = field(a, "cells");
        for (int i = 0; i < cells.size(); i++) if (cells.get(i).tag.equals(HomeActivity.WATCH_TAG)) return i;
        return -1;
    }

    private static void invoke(HomeActivity a, String name) throws Exception {
        Method m = HomeActivity.class.getDeclaredMethod(name);
        m.setAccessible(true);
        m.invoke(a);
    }

    @Test
    public void watchIsATileThatLeavesNothingBehindAndGoesWhenSwitchedOff() throws Exception {
        keep("any", 4);
        HomeActivity a = Robolectric.buildActivity(HomeActivity.class).setup().get();
        run(1500);
        // there from the start as a tile and nothing more: its screen isn't made until it's opened
        Assert.assertTrue(Prefs.watch(c));
        Assert.assertEquals("no input is live here: Watch opens the grid", 0, tileAt(a));
        Assert.assertNull("nothing of the Watch screen exists until it's opened", WatchTest.<Object>field(a, "watch"));
        // the rail is Find, Inputs, Control Center and Settings: no Watch, no Photo mode, no Apps
        List<View> side = field(a, "side");
        Assert.assertEquals(4, side.size());
        // and All Apps' tabs are the categories alone
        Method tabs = HomeActivity.class.getDeclaredMethod("allAppsTabs");
        tabs.setAccessible(true);
        @SuppressWarnings("unchecked") List<AllApps.Tab> list = (List<AllApps.Tab>) tabs.invoke(a);
        for (AllApps.Tab t : list) Assert.assertNotEquals("watch", t.id);

        Assert.assertEquals("its Top Shelf is read from what was kept", 3, Watch.cards(c, 3).size());

        // OK on the tile makes the screen and opens it
        List<Cell> cells = field(a, "cells");
        cells.get(0).card.onClick.run();
        run(600);
        WatchScreen screen = field(a, "watch");
        Assert.assertNotNull(screen);
        Assert.assertTrue(screen.isOpen());
        Assert.assertNotNull(screen.getParent());

        // closed, it leaves the window and is forgotten, its pictures with it
        invoke(a, "closeWatch");
        Assert.assertFalse(screen.isOpen());
        run(HomeActivity.WATCH_KEPT_MS + 500);
        Assert.assertNull("a closed Watch screen isn't kept", WatchTest.<Object>field(a, "watch"));
        Assert.assertNull(screen.getParent());

        // a kids' profile has no tile, whatever the switch says
        Profiles.Profile kids = Profiles.add(c, "Kids");
        Profiles.setKids(c, kids.id, true);
        Profiles.use(c, kids.id);
        invoke(a, "populate");
        Assert.assertEquals(-1, tileAt(a));
        Assert.assertEquals(0, Watch.cards(c, 3).size());
        Profiles.use(c, Profiles.MAIN);
        invoke(a, "populate");
        run(300);
        Assert.assertEquals(0, tileAt(a));

        // switched off again: the tile goes, and what was fetched leaves the TV; a saved list stays
        Assert.assertTrue(Watch.toggle(c, Watch.shelf(c, Watch.ANY).get(0)));
        cells.get(0).card.onClick.run();
        run(600);
        Assert.assertNotNull(WatchTest.<Object>field(a, "watch"));
        Prefs.set(c, Prefs.WATCH, false);
        invoke(a, "watchOff");
        invoke(a, "populate");
        Assert.assertNull("switched off, the open screen goes at once", WatchTest.<Object>field(a, "watch"));
        Assert.assertEquals(-1, tileAt(a));
        for (int i = 0; i < 100 && !Watch.shelf(c, Watch.ANY).isEmpty(); i++) {
            Thread.sleep(20);
            run(32);
        }
        Assert.assertEquals("the shelves fetched are gone", 0, Watch.shelf(c, Watch.ANY).size());
        Assert.assertEquals("what was saved to My list stays", 1, Watch.mine(c).size());

        // back on, then turned off from the tile itself: hold OK offers it, and it goes altogether
        Prefs.set(c, Prefs.WATCH, true);
        invoke(a, "populate");
        run(300);
        Assert.assertEquals(0, tileAt(a));
        cells.get(0).card.requestFocus();
        run(100);
        invoke(a, "openMenu");
        run(300);
        Runnable off = menuRow(a, c.getString(R.string.watch_turn_off));
        Assert.assertNotNull("hold OK on the tile offers to turn Watch off", off);
        off.run();
        run(400);
        Assert.assertFalse(Prefs.on(c, Prefs.WATCH));
        Assert.assertEquals(-1, tileAt(a));
    }

    /** What OK does on the row of home's menu that says {@code label}, or null when no row does. */
    private static Runnable menuRow(HomeActivity a, String label) throws Exception {
        AppMenu menu = field(a, "menu");
        ViewGroup list = field(menu, "list");
        for (int i = 0; i < list.getChildCount(); i++) {
            View row = list.getChildAt(i);
            if (row instanceof Pad && hasText((ViewGroup) row, label)) return ((Pad) row).onClick;
        }
        return null;
    }

    private static boolean hasText(ViewGroup g, String text) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View v = g.getChildAt(i);
            if (v instanceof TextView && text.contentEquals(((TextView) v).getText())) return true;
            if (v instanceof ViewGroup && hasText((ViewGroup) v, text)) return true;
        }
        return false;
    }
}
