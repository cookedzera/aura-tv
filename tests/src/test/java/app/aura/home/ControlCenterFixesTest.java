package app.aura.home;

import android.app.Notification;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.ImageView;
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
import org.robolectric.shadows.ShadowLooper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import static org.robolectric.Shadows.shadowOf;

/**
 * Control Center's sheets one at a time, the latest asked for winning (a sheet still on its way
 * never slides in over it, nor into a later opening); notifications that can't be read said so,
 * and those past the three rows counted; the playing app's icon looked up once, not per report.
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30, application = AuraApp.class, qualifiers = "w960dp-h540dp-xhdpi")
public class ControlCenterFixesTest {
    private Context c;
    private ActivityController<HomeActivity> ac;
    private HomeActivity a;
    private ControlCenter cc;

    private static void run(long ms) {
        for (long t = 0; t < ms; t += 50) ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS);
    }

    /** Control Center reads on its own threads: waits (up to three seconds) for what they bring. */
    private static void until(Callable<Boolean> done) throws Exception {
        for (int i = 0; i < 60 && !done.call(); i++) {
            Thread.sleep(50);
            run(50);
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
        Prefs.device(c).edit().putBoolean("welcomed", true).putString("set_light", "on").commit();
        Field w = HomeActivity.class.getDeclaredField("warm");
        w.setAccessible(true);
        w.setBoolean(null, true);
    }

    @After
    public void tearDown() throws Exception {
        if (ac != null) ac.pause().stop().destroy();
        listener(null);
    }

    private void start() throws Exception {
        ac = Robolectric.buildActivity(HomeActivity.class).setup();
        a = ac.get();
        ac.windowFocusChanged(true);
        run(2500);
        cc = field(a, "cc");
    }

    private void openCc(boolean inputs) throws Exception {
        call(a, "openCc", new Class<?>[]{boolean.class}, inputs);
    }

    @Test
    public void oneSheetAtATimeTheLatestWins() throws Exception {
        start();
        WifiSheet wifiSheet = field(cc, "wifiSheet");
        BtSheet btSheet = field(cc, "btSheet");
        View board = field(cc, "board"), wifi = field(cc, "wifi");
        openCc(false);
        run(1000);

        // Bluetooth's sheet open, then Wi-Fi's asked for: Bluetooth's goes, the focus is in Wi-Fi's
        call(cc, "showBt", new Class<?>[]{});
        run(500);
        Assert.assertTrue(btSheet.isOpen());
        call(cc, "showWifi", new Class<?>[]{});
        run(500);
        Assert.assertTrue(wifiSheet.isOpen());
        Assert.assertFalse("never two sheets in the same place", btSheet.isOpen());
        // the window's own answer: Robolectric's Activity.getCurrentFocus() only returns what was last set on it
        Assert.assertTrue("the focus is on the sheet in front", inside(a.getWindow().getCurrentFocus(), wifiSheet));
        a.onBackPressed();
        run(300);
        Assert.assertFalse(wifiSheet.isOpen() || btSheet.isOpen());
        Assert.assertEquals(View.VISIBLE, board.getVisibility());
        Assert.assertTrue("Back leaves the focus on the board, nothing over it", wifi.isFocused());

        // the low-battery card's Bluetooth sheet still on its way when Wi-Fi is picked: it stays away
        cc.showBluetoothSoon();
        run(100);
        call(cc, "showWifi", new Class<?>[]{});
        run(800);
        Assert.assertTrue(wifiSheet.isOpen());
        Assert.assertFalse("the Bluetooth sheet no longer slides in over Wi-Fi's", btSheet.isOpen());
        a.onBackPressed();
        run(300);
        Assert.assertFalse(wifiSheet.isOpen() || btSheet.isOpen());
        Assert.assertEquals(View.VISIBLE, board.getVisibility());
        Assert.assertTrue(wifi.isFocused());
    }

    @Test
    public void theInputsSheetComesOnlyToTheOpeningThatAskedForIt() throws Exception {
        start();
        // a TV with one HDMI input (the simulated TV has none); the same readings keep it there
        Field sig = ControlCenter.class.getDeclaredField("inputsSig");
        sig.setAccessible(true);
        sig.set(cc, "");
        List<InputSheet.Source> sources = field(cc, "sources");
        sources.add(new InputSheet.Source("HDMI 1", "Live", R.drawable.ic_hdmi, true, new Intent(Intent.ACTION_VIEW)));
        InputSheet inputSheet = field(cc, "inputSheet");

        // the TV's Inputs button: the sheet of sources slides in once the board has settled
        openCc(true);
        run(600);
        Assert.assertTrue(inputSheet.isOpen());
        a.closeCc(false);
        run(300);

        // opened with Inputs, shut at once, opened again plainly: no sheet of sources this time
        openCc(true);
        run(100);
        a.closeCc(true);
        run(50);
        openCc(false);
        run(800);
        Assert.assertFalse("the first opening's Inputs sheet stays away", inputSheet.isOpen());
    }

    @Test
    public void notificationsThatCantBeReadSaySo() throws Exception {
        start();
        View box = field(cc, "notesBox"), off = field(cc, "notesOff");
        openCc(false);
        until(() -> off.getVisibility() == View.VISIBLE);
        Assert.assertTrue("no access reads as no access, not as no notifications", box.isShown() && off.isShown());
        Assert.assertFalse("nothing OK could do here without the TV's debugging link", off.isFocusable());

        // Aura let in over the TV's debugging: OK can switch the access on
        Prefs.device(c).edit().putBoolean("adb_ok", true).commit();
        cc.refresh();
        until(off::isFocusable);
        Assert.assertTrue(off.isFocusable());

        // Android connects the listener: the line goes, and with no notifications so does the list
        MediaWatch ws = Robolectric.buildService(MediaWatch.class).create().get();
        ws.onListenerConnected();
        try {
            until(() -> off.getVisibility() != View.VISIBLE);
            Assert.assertEquals(View.GONE, off.getVisibility());
            Assert.assertEquals(View.GONE, box.getVisibility());
        } finally {
            ws.onListenerDisconnected();
            run(100);
        }
    }

    @Test
    public void notificationsPastTheRowsAreCounted() throws Exception {
        MediaWatch ws = Robolectric.buildService(MediaWatch.class).create().get();
        ws.onListenerConnected();
        try {
            for (int i = 1; i <= 5; i++) {
                Notification n = new Notification.Builder(c, "x").setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle("Episode " + i + " is ready").setContentText("Season 2").build();
                shadowOf(ws).addActiveNotification("com.netflix.ninja", i, n);
            }
            start();
            View[] rows = field(cc, "noteRows");
            TextView cap = field(cc, "notesCap");
            String want = a.getString(R.string.notifications) + "  ·  " + a.getResources().getQuantityString(R.plurals.n_more_caps, 2, 2);
            openCc(false);
            until(() -> rows[2].isShown() && want.contentEquals(cap.getText()));
            for (View r : rows) Assert.assertTrue("three rows, as before", r.isShown());
            Assert.assertEquals("the two past the rows are counted", want, cap.getText().toString());
            Assert.assertFalse("a count, never another stop for the focus", cap.isFocusable());
        } finally {
            ws.onListenerDisconnected();
            run(100);
        }
    }

    @Test
    public void thePlayingAppsIconIsLookedUpOnce() throws Exception {
        start();
        Drawable first = new ColorDrawable(0xFFFF0000);
        shadowOf(c.getPackageManager()).setApplicationIcon(c.getPackageName(), first);
        MediaWatch.Playing p = new MediaWatch.Playing();
        p.title = "Episode 12";
        p.pkg = c.getPackageName();
        p.playing = true;
        ImageView art = field(cc, "nowArt");
        cc.music(p);
        Assert.assertSame(first, art.getDrawable());
        // asked again, Android would now answer with another icon: a report without a cover keeps the one found first
        shadowOf(c.getPackageManager()).setApplicationIcon(c.getPackageName(), new ColorDrawable(0xFF0000FF));
        cc.music(p);
        Assert.assertSame("the icon comes from what was looked up once, not from Android on every report", first, art.getDrawable());
    }
}
