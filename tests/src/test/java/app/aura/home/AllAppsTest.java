package app.aura.home;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.ColorDrawable;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.widget.FrameLayout;

import org.junit.After;
import org.junit.Assert;
import org.junit.Assume;
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
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * All apps on a simulated 960 x 540 dp TV: the rows sit on the tvOS grid above the folder dock;
 * Up and Down keep the place on screen and come back exactly; the header and the dock are round
 * trips; Back climbs to the first row, then closes; a held key steps on the ladder and stops where
 * it's let go; LOW keeps only the tiles near the view; TalkBack hears a row as focus enters it; Move
 * saves through the stores without losing a hidden member; an emptied folder goes; an empty row
 * keeps an "Add apps" ghost.
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30, qualifiers = "w960dp-h540dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AllAppsTest {
    private Context c;
    private boolean wasLight;
    private final List<AppRepo.App> apps = new ArrayList<>();
    private final List<String> said = new ArrayList<>();
    private Activity act;
    private AllApps page;
    private String mine, folder;

    /** {@code ms} of a TV's time, a frame at a time. */
    private static void run(long ms) {
        for (long t = 0; t < ms; t += 16) ShadowLooper.idleMainLooper(Math.min(16, ms - t), TimeUnit.MILLISECONDS);
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object o, String name) throws Exception {
        for (Class<?> k = o.getClass(); k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return (T) f.get(o);
            } catch (NoSuchFieldException ignored) {
                // a field of the class it extends
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static String pkg(int i) {
        return String.format(Locale.ROOT, "com.test.a%02d", i);
    }

    private static AppRepo.App app(int i) {
        AppRepo.App a = new AppRepo.App();
        a.pkg = pkg(i);
        a.label = String.format(Locale.ROOT, "App %02d", i);
        a.icon = new ColorDrawable(0xFF335577);
        a.color = 0xFF335577;
        return a;
    }

    private final class Fake implements AllApps.Host {
        @Override public List<AppRepo.App> apps() { return apps; }
        @Override public boolean ready() { return true; }
        @Override public Tiles.Painter art(AppRepo.App a) {
            final int color = a.color;
            return (cv, w, h) -> cv.drawColor(color);
        }
        @Override public Backdrop backdrop() { return null; }
        @Override public void launch(AppRepo.App a, View from) { said.add("launch " + a.pkg); }
        @Override public void open(Intent intent, View from) { said.add("open"); }
        @Override public void changed() { said.add("changed"); }
        @Override public void hud(int icon, String text) { said.add("hud " + text); }
        @Override public void find(String typed) { said.add("find " + typed); }
        @Override public void controlCenter() { said.add("cc"); }
        @Override public void hiddenApps() { said.add("hidden"); }
        @Override public void closed() { said.add("closed"); }
    }

    @Before
    public void setUp() throws Exception {
        c = RuntimeEnvironment.getApplication();
        wasLight = Ui.light;
        // the main profile's settings file is the device's too: cleared once
        Prefs.of(c).edit().clear().putString(Prefs.TILE_STYLE, "tvos").commit();
        org.robolectric.shadows.ShadowChoreographer.setPaused(true);
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(Duration.ofMillis(16));
        // the animation clock's thread state outlives a test: a spring would start in the last test's future
        Field state = AnimationUtils.class.getDeclaredField("sAnimationState");
        state.setAccessible(true);
        ((ThreadLocal<?>) state.get(null)).remove();
        Card.movedAt = Long.MIN_VALUE / 2;
        apps.clear();
        said.clear();
        for (int i = 0; i < 40; i++) apps.add(app(i));
        // rows: Favorites (3 apps), Mine (10), All apps, Hidden; the rows that fill themselves are off
        for (String id : new String[]{"video", "music", "games", "news"}) Categories.setHidden(c, id, true);
        for (int i = 0; i < 3; i++) Categories.addTo(c, Categories.FAV, pkg(i));
        mine = Categories.create(c, "Mine");
        for (int i = 3; i < 13; i++) Categories.addTo(c, mine, pkg(i));
        Prefs.hide(c, pkg(39), true);
        folder = Folders.create(c, pkg(20)).id;
        Folders.add(c, folder, pkg(21));
    }

    @After
    public void tearDown() {
        Ui.light = wasLight;
    }

    /** The page on a TV of the tier asked for, open, the focus on its first tile. */
    private void open(boolean light) {
        Ui.light = light;
        act = Robolectric.buildActivity(Activity.class).setup().get();
        Ui.init(act);
        FrameLayout root = new FrameLayout(act);
        act.setContentView(root);
        // with frames paused, the window attaches on the first frame
        run(50);
        page = new AllApps(act, new Fake());
        root.addView(page, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        run(32);
        // a TV has no touch mode, which keeps the focus from anything not made for touch
        root.requestFocusFromTouch();
        Assume.assumeFalse("the simulated TV left touch mode", root.isInTouchMode());
        page.open();
        run(600);
    }

    private int press(int code) {
        return page.key(new KeyEvent(KeyEvent.ACTION_DOWN, code));
    }

    /** What has the focus: a tile by its app's package (or folder, ghost, hidden tag), else its description. */
    private String focus() {
        View f = act.getWindow().getCurrentFocus();
        if (f == null) return null;
        if (f.getParent() instanceof Cell) return ((Cell) f.getParent()).tag;
        return String.valueOf(f.getContentDescription());
    }

    private Object tile(String key) throws Exception {
        Map<String, ?> tiles = field(page, "tiles");
        return tiles.get(key);
    }

    @Test
    public void rowsSitOnTheTvosGridAboveTheFolderDock() throws Exception {
        open(true);
        List<?> rows = field(page, "rows");
        Assert.assertEquals("Favorites, Mine, All apps, Hidden", 4, rows.size());
        Assert.assertEquals(c.getString(R.string.cat_favorites), field(rows.get(0), "title"));
        Assert.assertEquals("Mine", field(rows.get(1), "title"));
        Assert.assertEquals(c.getString(R.string.cat_all_apps), field(rows.get(2), "title"));
        Assert.assertEquals(c.getString(R.string.hidden), field(rows.get(3), "title"));
        List<?> all = field(rows.get(2), "items");
        Assert.assertEquals("every app on show once, two of them in their folder", 38, all.size());

        Assert.assertEquals("the first row's first tile has the focus", pkg(0), focus());
        View card = act.getWindow().getCurrentFocus();
        View cell = (View) card.getParent();
        Assert.assertEquals("126 x 71 dp", Ui.dp(126), card.getWidth());
        Assert.assertEquals(Ui.dp(71), card.getHeight());
        Assert.assertEquals("42 dp in", Ui.dpf(42), cell.getTranslationX(), 0.5f);
        // the rows' window starts at y 57: tiles at y 103 on screen
        Assert.assertEquals(Ui.dpf(103 - 57), cell.getTranslationY(), 0.5f);
        View next = field(tile(Categories.FAV + "|" + pkg(1)), "cell");
        Assert.assertEquals("a column every 150 dp", Ui.dpf(192), next.getTranslationX(), 0.5f);
        View below = field(tile(mine + "|" + pkg(3)), "cell");
        Assert.assertEquals("a row every 146 dp", Ui.dpf(103 + 146 - 57), below.getTranslationY(), 0.5f);

        FolderDock dock = field(page, "dock");
        Assert.assertEquals("the folder sits on the dock", 1, dock.size());
        Assert.assertEquals("folder:" + folder, dock.tag(0));
        Assert.assertEquals("the tray along the foot of the page", Ui.dp(399), ((FrameLayout.LayoutParams) dock.getLayoutParams()).topMargin);
    }

    @Test
    public void upAndDownKeepTheSlotAndComeBackExactly() {
        open(true);
        Assert.assertEquals(AllApps.DONE, press(KeyEvent.KEYCODE_DPAD_DOWN));
        Assert.assertEquals("Mine, its first tile", pkg(3), focus());
        for (int i = 0; i < 7; i++) press(KeyEvent.KEYCODE_DPAD_RIGHT);
        Assert.assertEquals(pkg(10), focus());
        // Mine has slid two tiles along, so its eighth tile is in the sixth place; Favorites holds three
        press(KeyEvent.KEYCODE_DPAD_UP);
        Assert.assertEquals("the same place on screen, or the row's last tile", pkg(2), focus());
        press(KeyEvent.KEYCODE_DPAD_DOWN);
        Assert.assertEquals("back exactly where it came from, not by the place on screen", pkg(10), focus());
        press(KeyEvent.KEYCODE_DPAD_DOWN);
        Assert.assertEquals("All apps, the same place on screen", pkg(5), focus());
        press(KeyEvent.KEYCODE_DPAD_UP);
        Assert.assertEquals(pkg(10), focus());
        press(KeyEvent.KEYCODE_DPAD_UP);
        Assert.assertEquals(pkg(2), focus());
        // no wrap at either end of a row
        press(KeyEvent.KEYCODE_DPAD_RIGHT);
        Assert.assertEquals(pkg(2), focus());
        for (int i = 0; i < 3; i++) press(KeyEvent.KEYCODE_DPAD_LEFT);
        Assert.assertEquals(pkg(0), focus());
        // page keys: a row at a time, at the same place on screen (Mine is still slid two tiles along)
        press(KeyEvent.KEYCODE_CHANNEL_DOWN);
        Assert.assertEquals(pkg(5), focus());
    }

    @Test
    public void headerAndDockAreRoundTripsAndBackClimbsThenCloses() throws Exception {
        open(true);
        press(KeyEvent.KEYCODE_DPAD_RIGHT);
        press(KeyEvent.KEYCODE_DPAD_UP);
        View search = field(page, "search");
        Assert.assertSame("up to the header's button nearest above", search, act.getWindow().getCurrentFocus());
        press(KeyEvent.KEYCODE_DPAD_UP);
        Assert.assertSame("nothing above it", search, act.getWindow().getCurrentFocus());
        press(KeyEvent.KEYCODE_DPAD_DOWN);
        Assert.assertEquals("back on the tile it came from", pkg(1), focus());

        for (int i = 0; i < 3; i++) press(KeyEvent.KEYCODE_DPAD_DOWN);
        Assert.assertEquals("the last row: the hidden apps' tile", "hidden", focus());
        press(KeyEvent.KEYCODE_DPAD_DOWN);
        Assert.assertEquals("down onto the dock's folder", "folder:" + folder, focus());
        press(KeyEvent.KEYCODE_DPAD_DOWN);
        Assert.assertEquals("nothing below the dock", "folder:" + folder, focus());
        press(KeyEvent.KEYCODE_DPAD_UP);
        Assert.assertEquals("up to the very tile it came from", "hidden", focus());

        press(KeyEvent.KEYCODE_DPAD_DOWN);
        Assert.assertEquals(AllApps.DONE, press(KeyEvent.KEYCODE_BACK));
        Assert.assertEquals("Back leaves the dock for the rows", "hidden", focus());
        press(KeyEvent.KEYCODE_BACK);
        Assert.assertEquals("then the first row, on its own tile", pkg(1), focus());
        Assert.assertTrue(page.isOpen());
        Assert.assertFalse(said.contains("closed"));
        press(KeyEvent.KEYCODE_BACK);
        Assert.assertFalse("then the page closes", page.isOpen());
        Assert.assertTrue("and home takes the focus back", said.contains("closed"));
        run(400);
        Assert.assertEquals(View.GONE, page.getVisibility());
        Map<String, ?> tiles = field(page, "tiles");
        Assert.assertTrue("nothing kept while it's shut", tiles.isEmpty());
    }

    @Test
    public void aHeldKeyStepsOnTheLadderAndStopsWhereItIsLetGo() throws Exception {
        open(true);
        press(KeyEvent.KEYCODE_DPAD_DOWN);
        press(KeyEvent.KEYCODE_DPAD_DOWN);
        Assert.assertEquals("All apps", pkg(0), focus());
        run(1000);
        long down = SystemClock.uptimeMillis();
        page.key(new KeyEvent(down, down, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, 0));
        Assert.assertEquals("the press moves at once", pkg(1), focus());
        String last = focus();
        int early = 0, later = 0;
        // Android's repeats: the first after 500 ms, then one every 50 ms, for two seconds in all
        for (int i = 1; i <= 30; i++) {
            run(i == 1 ? 500 : 50);
            long t = SystemClock.uptimeMillis();
            page.key(new KeyEvent(down, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, i));
            String now = focus();
            if (!now.equals(last)) {
                if (t - down < 1000) early++;
                else later++;
                last = now;
            }
        }
        Assert.assertTrue("a step about every 110 ms in the first second: " + early, early >= 4 && early <= 6);
        Assert.assertTrue("about every 80 ms after it: " + later, later >= 10 && later <= 14);
        Object all = ((List<?>) field(page, "rows")).get(2);
        int col = field(all, "col");
        float shift = field(all, "shift");
        Assert.assertTrue("the row keeps pace: the tile in focus is on screen, " + (col - shift) + " columns in", col - shift < 5.5f);

        page.released();
        run(1500);
        Assert.assertEquals("focus stops where the key was let go", last, focus());
        int first = field(all, "first");
        shift = field(all, "shift");
        Assert.assertEquals("the row comes to rest on a whole column", first, shift, 0.01f);
        Assert.assertTrue("with the tile in view: first " + first + ", tile " + col, col - first >= 0 && col - first <= 5);
    }

    @Test
    public void onLowOnlyTheTilesNearTheViewAreKept() throws Exception {
        open(true);
        press(KeyEvent.KEYCODE_DPAD_DOWN);
        press(KeyEvent.KEYCODE_DPAD_DOWN);
        for (int i = 0; i < 30; i++) {
            press(KeyEvent.KEYCODE_DPAD_RIGHT);
            run(48);
        }
        run(1500);
        // All apps' 31st tile: a00-a19, then a22 on (a20 and a21 are in the folder)
        Assert.assertEquals(pkg(32), focus());
        Map<String, ?> tiles = field(page, "tiles");
        // the rows in view and one either side (Mine, All apps, Hidden), each six columns and a peek either side
        Assert.assertTrue("no more than the tiles near the view: " + tiles.size(), tiles.size() <= 3 * 8);
        Assert.assertFalse("the first row, out of view, is let go", tiles.containsKey(Categories.FAV + "|" + pkg(0)));
        Assert.assertFalse("so are All apps' first tiles", tiles.containsKey(Categories.ALL + "|" + pkg(0)));
        Assert.assertTrue(tiles.containsKey(Categories.ALL + "|" + pkg(32)));
        ViewGroup grid = field(page, "page");
        int cells = 0;
        for (int i = 0; i < grid.getChildCount(); i++) if (grid.getChildAt(i) instanceof Cell) cells++;
        Assert.assertEquals("no tile left on the page beyond them", tiles.size(), cells);
    }

    @Test
    public void talkBackHearsTheRowAsFocusEntersItThenEachTileByName() {
        open(true);
        View first = act.getWindow().getCurrentFocus();
        Assert.assertEquals("Favorites, App 00", String.valueOf(first.getContentDescription()));
        press(KeyEvent.KEYCODE_DPAD_RIGHT);
        Assert.assertEquals("App 01", String.valueOf(act.getWindow().getCurrentFocus().getContentDescription()));
        Assert.assertEquals("the tile left is its own name again", "App 00", String.valueOf(first.getContentDescription()));
        press(KeyEvent.KEYCODE_DPAD_DOWN);
        Assert.assertEquals("Mine, App 04", String.valueOf(act.getWindow().getCurrentFocus().getContentDescription()));
    }

    @Test
    public void moveKeepsTheNewOrderWithoutLosingAHiddenMember() throws Exception {
        // a hidden app in Favorites: not on the page, still in the row
        Categories.addTo(c, Categories.FAV, pkg(39));
        open(true);
        Object carried = tile(Categories.FAV + "|" + pkg(0));
        Method start = AllApps.class.getDeclaredMethod("startEdit", carried.getClass());
        start.setAccessible(true);
        start.invoke(page, carried);
        press(KeyEvent.KEYCODE_DPAD_RIGHT);
        Assert.assertEquals("the carried app keeps the focus", pkg(0), focus());
        Assert.assertEquals(AllApps.DONE, press(KeyEvent.KEYCODE_DPAD_CENTER));
        List<String> fav = Categories.find(Categories.all(c), Categories.FAV).add;
        Assert.assertEquals(Arrays.asList(pkg(1), pkg(0), pkg(2), pkg(39)), fav);
        Assert.assertTrue("home rebuilt behind the page", said.contains("changed"));

        // carried, then put back with Back: nothing saved
        said.clear();
        start.invoke(page, tile(Categories.FAV + "|" + pkg(0)));
        press(KeyEvent.KEYCODE_DPAD_RIGHT);
        press(KeyEvent.KEYCODE_BACK);
        Assert.assertEquals(Arrays.asList(pkg(1), pkg(0), pkg(2), pkg(39)), Categories.find(Categories.all(c), Categories.FAV).add);
        Assert.assertFalse(said.contains("changed"));
        Assert.assertTrue("Back ends the move, not the page", page.isOpen());
        Assert.assertEquals(pkg(0), focus());
    }

    @Test
    public void anEmptiedFolderLeavesTheDockAndTheRows() throws Exception {
        open(true);
        FolderDock dock = field(page, "dock");
        Assert.assertEquals(1, dock.size());
        Folders.remove(c, pkg(20));
        Folders.remove(c, pkg(21));
        page.refresh();
        run(300);
        Assert.assertEquals("an empty folder goes", 0, dock.size());
        List<?> all = field(((List<?>) field(page, "rows")).get(2), "items");
        for (Object it : all) Assert.assertNotEquals("folder:" + folder, field(it, "tag"));
        View f = act.getWindow().getCurrentFocus();
        Assert.assertNotNull("the focus isn't lost", f);
        Assert.assertTrue("it rests on something on show", f.isShown());
    }

    @Test
    public void anEmptyRowKeepsAnAddAppsGhost() throws Exception {
        String empty = Categories.create(c, "Empty");
        open(true);
        press(KeyEvent.KEYCODE_DPAD_DOWN);
        press(KeyEvent.KEYCODE_DPAD_DOWN);
        Assert.assertEquals("the empty row is shown, its ghost focusable", "ghost:" + empty, focus());
        String name = String.valueOf(act.getWindow().getCurrentFocus().getContentDescription());
        Assert.assertTrue(name, name.contains(c.getString(R.string.add_apps_to, "Empty")));
    }

    /**
     * A device draws a view only inside its own bounds (Robolectric doesn't clip): every tile on show
     * must sit inside the rows' page, every row whose tiles are in the window must show one, and
     * every app is reached by walking the rows.
     */
    @Test
    public void everyRowInTheWindowShowsItsTilesAndEveryAppIsReached() throws Exception {
        open(true);
        View grid = field(page, "page");
        View window = (View) grid.getParent();
        List<?> rows = field(page, "rows");
        List<String> reached = new ArrayList<>();
        for (int k = 0; k < rows.size(); k++) {
            if (k > 0) press(KeyEvent.KEYCODE_DPAD_DOWN);
            run(800);
            Object row = rows.get(k);
            int n = ((List<?>) field(row, "items")).size();
            // Down keeps the place on screen: to the row's start first
            for (int i = 0; i < n; i++) press(KeyEvent.KEYCODE_DPAD_LEFT);
            run(800);
            for (int i = 0; i < n; i++) {
                if (i > 0) {
                    press(KeyEvent.KEYCODE_DPAD_RIGHT);
                    run(48);
                }
                reached.add(focus());
            }
            run(800);
            for (int r = 0; r < rows.size(); r++) {
                String id = field(rows.get(r), "id");
                boolean inWindow = false, showing = false;
                for (int i = 0; i < ((ViewGroup) grid).getChildCount(); i++) {
                    View v = ((ViewGroup) grid).getChildAt(i);
                    if (!(v instanceof Cell) || !id.equals(((Cell) v).row) || v.getVisibility() != View.VISIBLE) continue;
                    float top = v.getTop() + v.getTranslationY();
                    Assert.assertTrue("row " + id + ": a tile drawn inside the page's bounds, at " + top + " of " + grid.getHeight(),
                            top >= 0 && top + v.getHeight() <= grid.getHeight());
                    float onScreen = grid.getTranslationY() + top;
                    if (onScreen >= 0 && onScreen + ((Cell) v).card.getHeight() <= window.getHeight()) inWindow = true;
                    showing = true;
                }
                float tilesAt = grid.getTranslationY() + Ui.dpf(103 - 57 + r * 146);
                if (tilesAt >= 0 && tilesAt + Ui.dp(71) <= window.getHeight()) {
                    Assert.assertTrue("row " + id + " is in the window, with its tiles on show", showing && inWindow);
                }
            }
        }
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 39; i++) if (i != 20 && i != 21) expected.add(pkg(i));
        for (String p : expected) Assert.assertTrue(p + " is reached", reached.contains(p));
        Assert.assertTrue("and the folder", reached.contains("folder:" + folder));
    }

    private void focusTile(int row, int col) throws Exception {
        Object r = ((List<?>) field(page, "rows")).get(row);
        Method m = AllApps.class.getDeclaredMethod("focusTile", r.getClass(), int.class);
        m.setAccessible(true);
        m.invoke(page, r, col);
        run(400);
    }

    @Test
    public void theNameCardKeepsTheRowsOutAndGivesTheFocusBackToItsTile() throws Exception {
        open(true);
        press(KeyEvent.KEYCODE_DPAD_DOWN);
        press(KeyEvent.KEYCODE_DPAD_RIGHT);
        press(KeyEvent.KEYCODE_DPAD_RIGHT);
        run(400);
        Assert.assertEquals(pkg(5), focus());
        int rowAt = field(page, "rowAt");
        final List<String> atDone = new ArrayList<>();
        Method askName = AllApps.class.getDeclaredMethod("askName", String.class, String.class, List.class, Ask.Done.class);
        askName.setAccessible(true);
        askName.invoke(page, "Name it", "", new ArrayList<String>(), (Ask.Done) got -> atDone.add(focus()));
        run(400);
        Ask ask = field(page, "ask");
        Assert.assertTrue(ask.isOpen());
        ViewGroup content = field(page, "content");
        Assert.assertEquals("the rows take no focus under the card", ViewGroup.FOCUS_BLOCK_DESCENDANTS, content.getDescendantFocusability());
        for (int code : new int[]{KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP}) {
            if (press(code) == AllApps.VIEW) act.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
            View f = act.getWindow().getCurrentFocus();
            Assert.assertFalse("an arrow on the card never reaches a tile", f != null && f.getParent() instanceof Cell);
        }
        Method finish = Ask.class.getDeclaredMethod("finish", String.class);
        finish.setAccessible(true);
        finish.invoke(ask, "Shows");
        Assert.assertEquals("the change is made with the focus back on its tile", Arrays.asList(pkg(5)), atDone);
        run(400);
        Assert.assertEquals(pkg(5), focus());
        Assert.assertEquals("the page's place kept", rowAt, (int) field(page, "rowAt"));
        Assert.assertEquals(ViewGroup.FOCUS_AFTER_DESCENDANTS, content.getDescendantFocusability());
    }

    @Test
    public void aFolderChangedWhileOpenClosesBackOntoItsTile() throws Exception {
        open(true);
        List<?> rows = field(page, "rows");
        int last = ((List<?>) field(rows.get(2), "items")).size() - 1;
        focusTile(2, last);
        Assert.assertEquals("folder:" + folder, focus());
        act.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER));
        act.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER));
        run(600);
        FolderPanel sheet = field(page, "sheet");
        Assert.assertTrue("the folder is open", sheet.isOpen());
        // an app joins it while it's open: the sheet is made again around it
        Folders.add(c, folder, pkg(22));
        page.refresh();
        run(600);
        Assert.assertTrue(sheet.isOpen());
        Assert.assertEquals("the page's place stays on the folder's row", 2, (int) field(page, "rowAt"));
        press(KeyEvent.KEYCODE_BACK);
        run(600);
        Assert.assertFalse(sheet.isOpen());
        Assert.assertEquals("back on the folder's own tile", "folder:" + folder, focus());
    }

    @Test
    public void closedThePageKeepsNoAppsOrPictures() throws Exception {
        open(true);
        page.close();
        run(600);
        Map<String, ?> known = field(page, "known");
        Assert.assertTrue("the apps as last read are let go", known.isEmpty());
        for (Object r : (List<?>) field(page, "rows")) Assert.assertTrue(((List<?>) field(r, "items")).isEmpty());
        page.open();
        run(600);
        Assert.assertEquals("and read again as it opens", pkg(0), focus());
    }

    @Test
    public void anEmptyRowThatFillsItselfStaysOffThePage() throws Exception {
        // Movies & TV on, with none of the apps a video app; Favorites emptied, a row of your own empty
        Categories.setHidden(c, "video", false);
        for (int i = 0; i < 3; i++) Categories.removeFrom(c, Categories.FAV, pkg(i));
        String empty = Categories.create(c, "Empty");
        open(true);
        List<String> ids = new ArrayList<>();
        for (Object r : (List<?>) field(page, "rows")) ids.add(field(r, "id"));
        Assert.assertFalse("no row of ghosts pushing the apps down: " + ids, ids.contains("video"));
        Assert.assertTrue("Favorites stays, to be filled", ids.contains(Categories.FAV));
        Assert.assertTrue("so does a row of your own", ids.contains(empty));
    }

    @Test
    public void openingWithNoFavoritesStartsOnTheFirstAppAndUpReachesTheGhost() throws Exception {
        for (int i = 0; i < 3; i++) Categories.removeFrom(c, Categories.FAV, pkg(i));
        open(true);
        Assert.assertEquals("Favorites holds only its ghost: the first app of the first row with apps (Mine)", pkg(3), focus());
        press(KeyEvent.KEYCODE_DPAD_UP);
        Assert.assertEquals("Favorites' ghost one Up away", "ghost:" + Categories.FAV, focus());
    }

    @Test
    public void withNoFavoritesBackCountsFromTheFirstRowOfApps() throws Exception {
        for (int i = 0; i < 3; i++) Categories.removeFrom(c, Categories.FAV, pkg(i));
        open(true);
        Assert.assertEquals(pkg(3), focus());
        press(KeyEvent.KEYCODE_BACK);
        Assert.assertFalse("opened and Back at once: the page closes, never a stop on Favorites' ghost", page.isOpen());
        Assert.assertTrue(said.contains("closed"));
        run(400);

        said.clear();
        open(true);
        press(KeyEvent.KEYCODE_DPAD_DOWN);
        Assert.assertNotEquals(pkg(3), focus());
        press(KeyEvent.KEYCODE_BACK);
        Assert.assertEquals("from a lower row, back to the first row of apps", pkg(3), focus());
        Assert.assertTrue(page.isOpen());
        press(KeyEvent.KEYCODE_DPAD_UP);
        Assert.assertEquals("ghost:" + Categories.FAV, focus());
        press(KeyEvent.KEYCODE_BACK);
        Assert.assertFalse("from the ghost above the start, the page closes", page.isOpen());
    }

    @Test
    public void openingWithFavoritesStartsOnTheFirstFavorite() {
        open(true);
        Assert.assertEquals(pkg(0), focus());
        press(KeyEvent.KEYCODE_DPAD_DOWN);
        Assert.assertEquals(pkg(3), focus());
    }

    @Test
    public void highFadesTheRowsIntoTheDockAndStretchesItsTray() throws Exception {
        open(false);
        View grid = field(page, "page");
        Assert.assertTrue("a 12 dp fade above the dock", ((View) grid.getParent()).isVerticalFadingEdgeEnabled());
        for (int i = 0; i < 4; i++) press(KeyEvent.KEYCODE_DPAD_DOWN);
        Assert.assertEquals("folder:" + folder, focus());
        run(400);
        FolderDock dock = field(page, "dock");
        View tray = field(dock, "tray");
        Assert.assertEquals("the tray reaches 6 dp toward the end tile", (916f + 6f) / 916f, tray.getScaleX(), 0.001f);
        Assert.assertEquals("pivoted at its far end", tray.getWidth(), tray.getPivotX(), 0.5f);
        page.close();
        run(400);

        open(true);
        grid = field(page, "page");
        Assert.assertFalse("LOW clips hard", ((View) grid.getParent()).isVerticalFadingEdgeEnabled());
    }
}
