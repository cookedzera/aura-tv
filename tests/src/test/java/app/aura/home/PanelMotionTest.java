package app.aura.home;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowBluetoothAdapter;
import org.robolectric.shadows.ShadowChoreographer;
import org.robolectric.shadows.ShadowLooper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * Controls pop to ×1.08 by way of ×1.12 on HIGH and go straight there on LOW, each move starting
 * where the last got to, and LOW lifts nothing off the glass; Control Center grows in and drops its
 * layers once there; a sheet or menu holds the focus itself as it comes in, hands it to its first row
 * on time, and leaves nothing that takes the focus as it fades out.
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30, application = AuraApp.class, qualifiers = "w960dp-h540dp-xhdpi")
public class PanelMotionTest {
    private Context c;
    private ActivityController<HomeActivity> ac;
    private HomeActivity a;
    private ControlCenter cc;

    /** Frame by frame: the choreographer is paused, so frames and layout barriers clear as the clock moves. */
    private static void run(long ms) {
        for (long t = 0; t < ms; t += 16) ShadowLooper.idleMainLooper(16, TimeUnit.MILLISECONDS);
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object o, String name) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(o);
    }

    private static void call(Object o, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = o.getClass().getDeclaredMethod(name, types);
        m.setAccessible(true);
        m.invoke(o, args);
    }

    private static boolean inside(View v, View parent) {
        for (Object p = v; p instanceof View; p = ((View) p).getParent()) if (p == parent) return true;
        return false;
    }

    private static void listener(MediaWatch w) throws Exception {
        Field f = MediaWatch.class.getDeclaredField("connected");
        f.setAccessible(true);
        f.set(null, w);
    }

    @Before
    public void setUp() throws Exception {
        c = RuntimeEnvironment.getApplication();
        // other tests can leave a kids' profile, a connected listener or a box without Bluetooth behind in static state
        Profiles.forget();
        Profiles.use(c, Profiles.MAIN);
        listener(null);
        ShadowBluetoothAdapter.setIsBluetoothSupported(true);
        ShadowChoreographer.setPaused(true);
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16));
        Field w = HomeActivity.class.getDeclaredField("warm");
        w.setAccessible(true);
        w.setBoolean(null, true);
    }

    @After
    public void tearDown() throws Exception {
        if (ac != null) ac.pause().stop().destroy();
        listener(null);
    }

    /** Home on a LOW ({@code light}) or a HIGH TV, Control Center open and settled. */
    private void openCc(boolean light) throws Exception {
        Prefs.device(c).edit().putBoolean("welcomed", true).putString("set_light", light ? "on" : "off").commit();
        ac = Robolectric.buildActivity(HomeActivity.class).setup();
        a = ac.get();
        // with the choreographer paused the window is attached by the first frame's layout pass, and
        // only an attached window can be given the focus
        run(48);
        ac.windowFocusChanged(true);
        run(2500);
        cc = field(a, "cc");
        call(a, "openCc", new Class<?>[]{boolean.class}, false);
        run(800);
    }

    @Test
    public void highControlsPopAndEaseOutFromWhereTheyAre() throws Exception {
        openCc(false);
        Pad settings = field(cc, "settings"), standby = field(cc, "standby");
        Assert.assertTrue(settings.requestFocus());
        float peak = 0f;
        for (int i = 0; i < 30; i++) {
            run(16);
            peak = Math.max(peak, settings.getScaleX());
        }
        Assert.assertTrue("pops past its size: " + peak, peak > 1.10f && peak < 1.125f);
        Assert.assertEquals("and rests at ×1.08", Ui.FOCUS_SCALE_CONTROL, settings.getScaleX(), 0.002f);
        Assert.assertEquals("lit white", 1f, settings.highlight, 0.001f);

        // the focus moves on mid-way and straight back: each move starts where the last got to
        Assert.assertTrue(standby.requestFocus());
        run(64);
        float mid = settings.getScaleX();
        Assert.assertTrue("easing out: " + mid, mid > 1f && mid < Ui.FOCUS_SCALE_CONTROL);
        Assert.assertTrue(settings.requestFocus());
        for (int i = 0; i < 8; i++) {
            run(16);
            Assert.assertTrue("never drops back first: " + settings.getScaleX(), settings.getScaleX() >= mid - 0.001f);
        }
        run(500);
        Assert.assertEquals(Ui.FOCUS_SCALE_CONTROL, settings.getScaleX(), 0.002f);
        Assert.assertEquals(1f, standby.getScaleX(), 0.002f);
    }

    @Test
    public void lowControlsGoStraightThereAndLiftNothing() throws Exception {
        openCc(true);
        Pad settings = field(cc, "settings");
        Assert.assertFalse("a pad draws its own rounded shape: no clip on LOW", settings.getClipToOutline());
        Assert.assertTrue(settings.requestFocus());
        float peak = 0f;
        for (int i = 0; i < 12; i++) {
            run(16);
            peak = Math.max(peak, settings.getScaleX());
            Assert.assertEquals("nothing lifted off the glass", 0f, settings.getTranslationZ(), 0f);
        }
        Assert.assertTrue("no pop past its size: " + peak, peak <= Ui.FOCUS_SCALE_CONTROL + 0.0005f);
        Assert.assertEquals(Ui.FOCUS_SCALE_CONTROL, settings.getScaleX(), 0.002f);
    }

    @Test
    public void highControlCenterGrowsInAndDropsItsLayers() throws Exception {
        openCc(false);
        View board = field(cc, "board"), header = field(cc, "header");
        a.closeCc(false);
        run(300);
        call(a, "openCc", new Class<?>[]{boolean.class}, false);
        Assert.assertEquals("comes in from nothing", 0f, board.getAlpha(), 0.001f);
        Assert.assertEquals("from ×0.90", 0.9f, board.getScaleX(), 0.001f);
        run(Ui.CC_OPEN + 100);
        Assert.assertEquals(1f, board.getAlpha(), 0.001f);
        Assert.assertEquals(1f, board.getScaleX(), 0.001f);
        Assert.assertEquals(1f, header.getAlpha(), 0.001f);
        Assert.assertEquals("its layer only while it grows", View.LAYER_TYPE_NONE, board.getLayerType());
        Assert.assertEquals(View.LAYER_TYPE_NONE, header.getLayerType());
    }

    /**
     * On a Mali-470 the frost dissolving in over a home screen still drawing made every frame of the
     * opening late: LOW has the frost whole and the home screen at rest from the first frame, and on
     * the way out the frost goes at once, only the clock and the board fading. HIGH dissolves as before.
     */
    @Test
    public void lowFrostIsThereAtOnceAndHomeStopsWithIt() throws Exception {
        openCc(true);
        View veil = field(cc, "veil"), home = field(a, "home"), board = field(cc, "board");
        a.closeCc(false);
        run(300);
        call(a, "openCc", new Class<?>[]{boolean.class}, false);
        Assert.assertEquals("the frost whole on the first frame", 1f, veil.getAlpha(), 0f);
        Assert.assertEquals("the home screen beneath stops drawing with it", View.INVISIBLE, home.getVisibility());
        Assert.assertEquals("the board still fades in", 0f, board.getAlpha(), 0.001f);
        run(Ui.LOW_CC_OPEN + 100);
        Assert.assertEquals(1f, board.getAlpha(), 0.001f);

        a.closeCc(true);
        Assert.assertEquals("the frost goes at once", 0f, veil.getAlpha(), 0f);
        Assert.assertEquals("over the home screen, there again", View.VISIBLE, home.getVisibility());
        run(Ui.CLOSE + 100);
        Assert.assertEquals(View.GONE, cc.panel.getVisibility());
    }

    @Test
    public void highFrostDissolvesInOverAHomeStillShowing() throws Exception {
        openCc(false);
        View veil = field(cc, "veil"), home = field(a, "home");
        a.closeCc(false);
        run(300);
        call(a, "openCc", new Class<?>[]{boolean.class}, false);
        Assert.assertEquals(0f, veil.getAlpha(), 0f);
        Assert.assertEquals(View.VISIBLE, home.getVisibility());
        run(ControlCenter.FROST_MS + 100);
        Assert.assertEquals(1f, veil.getAlpha(), 0.001f);
        Assert.assertEquals(View.INVISIBLE, home.getVisibility());
    }

    /** HIGH: the board's modules come up one after another; the last is still on its way as the first has arrived. */
    @Test
    public void onHighTheModulesArriveOneAfterAnother() throws Exception {
        openCc(false);
        a.closeCc(false);
        run(300);
        ViewGroup board = field(cc, "board");
        View first = null, last = null;
        for (int i = 0; i < board.getChildCount(); i++) {
            View m = board.getChildAt(i);
            if (m.getVisibility() != View.VISIBLE) continue;
            if (first == null) first = m;
            last = m;
        }
        Assert.assertNotNull(first);
        Assert.assertNotSame(first, last);
        call(a, "openCc", new Class<?>[]{boolean.class}, false);
        run(80);
        Assert.assertTrue("the first module has begun: " + first.getAlpha(), first.getAlpha() > 0.05f);
        Assert.assertTrue("the last waits its turn: " + last.getAlpha(), last.getAlpha() < 0.05f);
        run(700);
        for (int i = 0; i < board.getChildCount(); i++) {
            View m = board.getChildAt(i);
            if (m.getVisibility() != View.VISIBLE) continue;
            Assert.assertEquals(1f, m.getAlpha(), 0.001f);
            Assert.assertEquals(0f, m.getTranslationY(), 0.5f);
        }
    }

    /** LOW: the board fades and drops as one; its modules are not staged on their own. */
    @Test
    public void onLowTheBoardArrivesWhole() throws Exception {
        openCc(true);
        a.closeCc(false);
        run(300);
        ViewGroup board = field(cc, "board");
        call(a, "openCc", new Class<?>[]{boolean.class}, false);
        run(16);
        for (int i = 0; i < board.getChildCount(); i++) {
            View m = board.getChildAt(i);
            if (m.getVisibility() != View.VISIBLE) continue;
            Assert.assertEquals("LOW stages nothing of its own: " + i, 1f, m.getAlpha(), 0.001f);
            Assert.assertEquals(0f, m.getTranslationY(), 0.5f);
        }
        run(Ui.LOW_CC_OPEN + 50);
        Assert.assertEquals(1f, board.getAlpha(), 0.001f);
    }

    @Test
    public void aSheetHoldsTheFocusAsItComesInThenHandsItOn() throws Exception {
        openCc(true);
        WifiSheet sheet = field(cc, "wifiSheet");
        ViewGroup board = field(cc, "board");
        View wifi = field(cc, "wifi");

        call(cc, "showWifi", new Class<?>[]{});
        // the window's own answer: Robolectric's Activity.getCurrentFocus() only returns what was last set on it
        Assert.assertSame("the sheet holds the focus as it comes in", sheet, a.getWindow().getCurrentFocus());
        Assert.assertEquals("the board fading behind it takes none", ViewGroup.FOCUS_BLOCK_DESCENDANTS, board.getDescendantFocusability());
        run(Ui.LOW_SHEET_FOCUS_AT + 48);
        View focus = a.getWindow().getCurrentFocus();
        Assert.assertTrue("its first row has it on time", focus != sheet && inside(focus, sheet));
        Assert.assertFalse("and the sheet itself is no stop for the focus", sheet.isFocusable());

        a.onBackPressed();
        Assert.assertFalse(sheet.isOpen());
        Assert.assertTrue("Back leaves the focus on the Wi-Fi button", wifi.isFocused());
        Assert.assertEquals("nothing in the fading sheet takes the focus", ViewGroup.FOCUS_BLOCK_DESCENDANTS, sheet.getDescendantFocusability());
        run(Ui.CLOSE + 64);
        Assert.assertEquals(View.GONE, sheet.getVisibility());
        Assert.assertEquals(1f, board.getAlpha(), 0.001f);

        // Back while it's still coming in: it closes from there, and the focus it held goes nowhere else
        call(cc, "showWifi", new Class<?>[]{});
        run(48);
        Assert.assertSame(sheet, a.getWindow().getCurrentFocus());
        a.onBackPressed();
        Assert.assertTrue(wifi.isFocused());
        Assert.assertFalse("the closing sheet holds on to nothing", sheet.isFocusable());
        run(Ui.LOW_SHEET_FOCUS_AT + Ui.CLOSE);
        Assert.assertTrue("its first row never takes it afterwards", wifi.isFocused());
        Assert.assertEquals(View.GONE, sheet.getVisibility());
    }

    @Test
    public void aMenuHoldsTheFocusThenLeavesNothingFocusableAsItFades() throws Exception {
        openCc(true);
        a.closeCc(false);
        run(300);
        AppMenu menu = field(a, "menu");
        ViewGroup root = (ViewGroup) menu.getParent();
        View tile = new View(a);
        root.addView(tile, 0, new ViewGroup.LayoutParams(200, 100));
        run(48);

        menu.show(tile, "Test", Collections.singletonList(new AppMenu.Item(R.drawable.ic_info, "One", () -> { })));
        Assert.assertTrue(menu.isOpen());
        Assert.assertSame("the menu holds the focus as it comes in", menu, a.getWindow().getCurrentFocus());
        run(Ui.LOW_SHEET_FOCUS_AT + 48);
        View focus = a.getWindow().getCurrentFocus();
        Assert.assertTrue("its first row has it on time", focus != menu && inside(focus, menu));

        menu.hide();
        Assert.assertFalse(menu.isOpen());
        Assert.assertEquals("its rows take no focus as it fades", ViewGroup.FOCUS_BLOCK_DESCENDANTS, menu.getDescendantFocusability());
        run(Ui.CLOSE + 64);
        Assert.assertEquals(View.GONE, menu.getVisibility());
    }
}
