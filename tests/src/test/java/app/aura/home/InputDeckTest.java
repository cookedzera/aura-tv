package app.aura.home;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Control Center's inputs as a deck of device cards: a device's kind read from its own name, then
 * from what CEC says it is; the cards two to a row in the ports' order, the live one with the focus
 * as the deck opens, those with nothing plugged in sitting back; the Inputs tile with one dot a
 * source; the deck's cards still on the way in on HIGH only.
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30, application = AuraApp.class, qualifiers = "w960dp-h540dp-xhdpi")
public class InputDeckTest {
    private Context c;
    private ActivityController<HomeActivity> ac;
    private HomeActivity a;
    private ControlCenter cc;

    /** Frame by frame: the choreographer is paused, so the cards' entrance advances as the clock moves. */
    private static void run(long ms) {
        for (long t = 0; t < ms; t += 16) ShadowLooper.idleMainLooper(16, TimeUnit.MILLISECONDS);
    }

    /** A moment for Control Center's readings (on its own thread) to land, so none paints over what a test sets. */
    private static void settle() throws Exception {
        for (int i = 0; i < 10; i++) {
            Thread.sleep(30);
            run(48);
        }
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

    private static InputSheet.Source source(String id, String name, String port, boolean live, boolean standby, int kind) {
        String state = live ? "Live" : standby ? "On standby" : "Nothing plugged in";
        return new InputSheet.Source(id, name, port, state, kind, live, standby, new Intent(Intent.ACTION_VIEW));
    }

    /** Four HDMI ports as a living room has them: a console asleep, a box live, an empty port, a soundbar. */
    private static List<InputSheet.Source> livingRoom() {
        return new ArrayList<>(Arrays.asList(
                source("h1", "PlayStation 5", "HDMI 1", false, true, InputSheet.CONSOLE),
                source("h2", "Fire TV", "HDMI 2", true, false, InputSheet.BOX),
                source("h3", "HDMI 3", "HDMI 3", false, false, InputSheet.HDMI),
                source("h4", "Soundbar", "HDMI 4", false, false, InputSheet.AUDIO)));
    }

    @Before
    public void setUp() throws Exception {
        c = RuntimeEnvironment.getApplication();
        Profiles.forget();
        Profiles.use(c, Profiles.MAIN);
        ShadowBluetoothAdapter.setIsBluetoothSupported(true);
        ShadowChoreographer.setPaused(true);
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16));
        Prefs.device(c).edit().putBoolean("welcomed", true).putString("set_light", "on").commit();
        Field w = HomeActivity.class.getDeclaredField("warm");
        w.setAccessible(true);
        w.setBoolean(null, true);
    }

    @After
    public void tearDown() {
        if (ac != null) ac.pause().stop().destroy();
    }

    private void start() throws Exception {
        ac = Robolectric.buildActivity(HomeActivity.class).setup();
        a = ac.get();
        // the window is attached by the first frame's layout, and only then can it have the focus
        run(50);
        ac.windowFocusChanged(true);
        run(2500);
        cc = field(a, "cc");
    }

    /** The sources as if the TV had reported them, the tile and sheet filled from them. */
    private void given(List<InputSheet.Source> sources) throws Exception {
        InputSheet.Source lead = null;
        for (InputSheet.Source s : sources) if (lead == null && s.live) lead = s;
        if (lead == null) lead = sources.get(0);
        call(cc, "applyInputs", new Class<?>[]{Object[].class}, (Object) new Object[]{"test", sources, lead});
        // the simulated TV's own reading (no inputs) is then nothing new, and leaves these in place
        Field sig = ControlCenter.class.getDeclaredField("inputsSig");
        sig.setAccessible(true);
        sig.set(cc, "");
    }

    @Test
    public void aDevicesKindComesFromItsNameThenFromCec() {
        Assert.assertEquals(InputSheet.CONSOLE, InputSheet.kindOf("PlayStation 5", Cec.UNKNOWN, true, false));
        Assert.assertEquals(InputSheet.CONSOLE, InputSheet.kindOf("Xbox Series X", Cec.PLAYBACK, true, false));
        Assert.assertEquals(InputSheet.CONSOLE, InputSheet.kindOf("Nintendo Switch", Cec.UNKNOWN, true, false));
        Assert.assertEquals(InputSheet.BOX, InputSheet.kindOf("Fire TV", Cec.UNKNOWN, true, false));
        Assert.assertEquals(InputSheet.BOX, InputSheet.kindOf("Apple TV", Cec.UNKNOWN, true, false));
        Assert.assertEquals(InputSheet.BOX, InputSheet.kindOf("Tata Play Binge+", Cec.UNKNOWN, true, false));
        Assert.assertEquals(InputSheet.COMPUTER, InputSheet.kindOf("MacBook Pro", Cec.UNKNOWN, true, false));
        Assert.assertEquals(InputSheet.AUDIO, InputSheet.kindOf("HW-Q990C", Cec.UNKNOWN, true, false));
        Assert.assertEquals(InputSheet.AUDIO, InputSheet.kindOf("Sonos Arc", Cec.UNKNOWN, true, false));
        // a name that says nothing: CEC's word on the device
        Assert.assertEquals(InputSheet.AUDIO, InputSheet.kindOf("HDMI 3", Cec.AUDIO, true, false));
        Assert.assertEquals(InputSheet.BOX, InputSheet.kindOf("HDMI 2", Cec.PLAYBACK, true, false));
        Assert.assertEquals(InputSheet.BOX, InputSheet.kindOf("HDMI 2", Cec.TUNER, true, false));
        // nothing known at all: the port itself
        Assert.assertEquals(InputSheet.HDMI, InputSheet.kindOf("HDMI 1", Cec.UNKNOWN, true, false));
        Assert.assertEquals(InputSheet.AV, InputSheet.kindOf("AV", Cec.UNKNOWN, false, false));
        Assert.assertEquals(InputSheet.TUNER, InputSheet.kindOf("Antenna", Cec.UNKNOWN, false, true));
        // CEC's logical addresses say what kind of device took them
        Assert.assertEquals(Cec.PLAYBACK, Cec.typeOfAddress(4));
        Assert.assertEquals(Cec.PLAYBACK, Cec.typeOfAddress(8));
        Assert.assertEquals(Cec.AUDIO, Cec.typeOfAddress(5));
        Assert.assertEquals(Cec.TUNER, Cec.typeOfAddress(3));
        Assert.assertEquals(Cec.RECORDER, Cec.typeOfAddress(1));
        Assert.assertEquals(Cec.UNKNOWN, Cec.typeOfAddress(0));
    }

    @Test
    public void theDeckKeepsThePortsOrderAndTheLiveOneHasTheFocus() throws Exception {
        start();
        given(livingRoom());
        call(a, "openCc", new Class<?>[]{boolean.class}, false);
        run(1000);
        // the opening's own reading has landed by now: the simulated TV has no input service at all,
        // which puts the tile away; a TV with inputs keeps it, as the reading given again does here
        settle();
        given(livingRoom());
        call(cc, "showInputs", new Class<?>[]{});
        run(600);
        InputSheet sheet = field(cc, "inputSheet");
        Assert.assertTrue(sheet.isOpen());
        List<Pad> cards = field(sheet, "cards");
        Assert.assertEquals(4, cards.size());
        // two to a row, in the ports' order: the console stays first though the box is the one live
        ViewGroup list = field(sheet, "list");
        Assert.assertEquals(2, list.getChildCount());
        Assert.assertSame(cards.get(0), ((ViewGroup) list.getChildAt(0)).getChildAt(0));
        Assert.assertSame(cards.get(1), ((ViewGroup) list.getChildAt(0)).getChildAt(1));
        Assert.assertSame(cards.get(2), ((ViewGroup) list.getChildAt(1)).getChildAt(0));
        Assert.assertEquals("PlayStation 5, HDMI 1, On standby", cards.get(0).getContentDescription().toString());
        Assert.assertEquals("Fire TV, HDMI 2, Live", cards.get(1).getContentDescription().toString());
        // a port named only by itself says so once, not "HDMI 3, HDMI 3"
        Assert.assertEquals("HDMI 3, Nothing plugged in", cards.get(2).getContentDescription().toString());
        Assert.assertTrue("the live source's card has the focus", cards.get(1).isFocused());
        // the one with nothing plugged in sits back; a card with a device on it doesn't
        View quiet = ((ViewGroup) cards.get(2)).getChildAt(0), loud = ((ViewGroup) cards.get(0)).getChildAt(0);
        Assert.assertEquals(0.62f, quiet.getAlpha(), 0.01f);
        Assert.assertEquals(1f, loud.getAlpha(), 0.01f);
        // LOW: the cards arrive with the sheet, nothing of their own still on the way
        for (Pad card : cards) Assert.assertEquals(1f, card.getAlpha(), 0.001f);
        // Back puts the deck away and the focus back on the Inputs tile
        a.onBackPressed();
        run(400);
        Assert.assertFalse(sheet.isOpen());
        View inputs = field(cc, "inputs");
        Assert.assertTrue("the focus is back on the Inputs tile", inputs.isFocused());
    }

    /** HIGH: the cards come up through the sheet one after another; the last is still on its way as the first has arrived. */
    @Test
    public void onHighTheCardsArriveOneAfterAnother() throws Exception {
        Prefs.device(c).edit().putString("set_light", "off").commit();
        start();
        given(livingRoom());
        call(a, "openCc", new Class<?>[]{boolean.class}, false);
        run(1000);
        settle();
        given(livingRoom());
        call(cc, "showInputs", new Class<?>[]{});
        // 40 ms in: the first card has begun, the last (at 100 ms) hasn't
        run(50);
        InputSheet sheet = field(cc, "inputSheet");
        List<Pad> cards = field(sheet, "cards");
        Assert.assertTrue("the last card waits its turn", cards.get(3).getAlpha() < 0.05f);
        run(500);
        for (Pad card : cards) {
            Assert.assertEquals(1f, card.getAlpha(), 0.001f);
            Assert.assertEquals(0f, card.getTranslationY(), 0.5f);
        }
    }

    @Test
    public void withNothingLiveTheFirstCardHasTheFocus() throws Exception {
        start();
        List<InputSheet.Source> sources = livingRoom();
        sources.set(1, source("h2", "Fire TV", "HDMI 2", false, true, InputSheet.BOX));
        given(sources);
        call(a, "openCc", new Class<?>[]{boolean.class}, false);
        run(1000);
        settle();
        given(sources);
        call(cc, "showInputs", new Class<?>[]{});
        run(600);
        InputSheet sheet = field(cc, "inputSheet");
        List<Pad> cards = field(sheet, "cards");
        Assert.assertTrue(cards.get(0).isFocused());
        TextView status = field(sheet, "status");
        Assert.assertEquals("nothing live: the count alone", c.getResources().getQuantityString(R.plurals.n_sources, 4, 4), status.getText().toString());
    }

    @Test
    public void theTileShowsTheLiveDeviceAndOneDotASource() throws Exception {
        start();
        given(livingRoom());
        TextView name = field(cc, "inName"), state = field(cc, "inState");
        Assert.assertEquals("Fire TV", name.getText().toString());
        Assert.assertEquals("Live", state.getText().toString());
        Object dots = field(cc, "inDots");
        boolean[] lit = field(dots, "lit");
        Assert.assertArrayEquals(new boolean[]{false, true, false, false}, lit);
        View inputs = field(cc, "inputs");
        String said = inputs.getContentDescription().toString();
        Assert.assertTrue(said, said.contains("Fire TV") && said.contains(c.getResources().getQuantityString(R.plurals.n_sources, 4, 4)));
    }
}
