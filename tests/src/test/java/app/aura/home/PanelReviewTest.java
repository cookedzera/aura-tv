package app.aura.home;

import android.app.Activity;
import android.app.Notification;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.SystemClock;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Assert;
import org.junit.Assume;
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
import org.robolectric.shadows.ShadowBluetoothAdapter;
import org.robolectric.shadows.ShadowChoreographer;
import org.robolectric.shadows.ShadowLooper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import static org.robolectric.Shadows.shadowOf;

/**
 * Control Center's notifications take only the rows there's room for above the screen's foot, at
 * 720p and 1080p, and count the rest; a hold whose long press moved the focus away leaves the next
 * press as any other; Play reaches the player that's playing before one paused since.
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30, application = AuraApp.class, qualifiers = "w960dp-h540dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PanelReviewTest {
    private static final String NETFLIX = "com.netflix.ninja";
    private Context c;
    private ActivityController<HomeActivity> home;
    private ActivityController<Activity> plain;
    private MediaWatch ws;

    /** Frame by frame: the choreographer is paused, so frames and layout barriers clear as the clock moves. */
    private static void run(long ms) {
        for (long t = 0; t < ms; t += 16) ShadowLooper.idleMainLooper(16, TimeUnit.MILLISECONDS);
    }

    /** Work on threads of its own (Control Center's readings): waits up to three seconds for it. */
    private static void until(Callable<Boolean> done) throws Exception {
        for (int i = 0; i < 60 && !done.call(); i++) {
            Thread.sleep(50);
            run(48);
        }
    }

    /** A moment for Control Center's readings to land, so nothing later paints over what's set. */
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

    private static void set(Object o, String name, Object value) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(o, value);
    }

    private static void call(Object o, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = o.getClass().getDeclaredMethod(name, types);
        m.setAccessible(true);
        m.invoke(o, args);
    }

    private static void listener(MediaWatch w) throws Exception {
        Field f = MediaWatch.class.getDeclaredField("connected");
        f.setAccessible(true);
        f.set(null, w);
    }

    @Before
    public void setUp() throws Exception {
        c = RuntimeEnvironment.getApplication();
        // the animation clock keeps the last test's time: a spring started in the first frame would wait for it
        Field clock = android.view.animation.AnimationUtils.class.getDeclaredField("sAnimationState");
        clock.setAccessible(true);
        ((ThreadLocal<?>) clock.get(null)).remove();
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
        if (home != null) home.pause().stop().destroy();
        if (plain != null) plain.pause().stop().destroy();
        if (ws != null) {
            ws.onListenerDisconnected();
            run(100);
        }
        listener(null);
    }

    /** Home started and settled, Control Center not yet open. */
    private ControlCenter startHome() throws Exception {
        Prefs.device(c).edit().putBoolean("welcomed", true).putString("set_light", "on").commit();
        home = Robolectric.buildActivity(HomeActivity.class).setup();
        // the window is attached by the first frame's layout, and only then can it have the focus
        run(50);
        home.windowFocusChanged(true);
        run(2500);
        return field(home.get(), "cc");
    }

    private void openCc() throws Exception {
        call(home.get(), "openCc", new Class<?>[]{boolean.class}, false);
    }

    private Notification note(String title, String text) {
        return new Notification.Builder(c, "x").setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title).setContentText(text).build();
    }

    /** The TV's notifications, read by Aura's listener. */
    private void connect(Notification... said) throws Exception {
        ws = Robolectric.buildService(MediaWatch.class).create().get();
        ws.onListenerConnected();
        for (int i = 0; i < said.length; i++) shadowOf(ws).addActiveNotification(NETFLIX, i + 1, said[i]);
    }

    /** A TV has no touch mode: controls take the focus only once the simulator has left it. */
    private static void leaveTouchMode(View v) {
        v.requestFocusFromTouch();
        Assume.assumeFalse("the simulated TV left touch mode", v.isInTouchMode());
    }

    private static int shown(View[] rows) {
        int n = 0;
        for (View r : rows) if (r.isShown()) n++;
        return n;
    }

    /** Where {@code v}'s foot is laid out in the panel, its scale and movement aside. */
    private static int foot(View v, View panel) {
        int y = v.getBottom();
        for (View p = (View) v.getParent(); p != panel; p = (View) p.getParent()) y += p.getTop();
        return y;
    }

    /** Each row on show ends inside the title-safe area; one is put away only when it wouldn't; the notes on none are counted. */
    private void fits(ControlCenter cc, Pad[] rows, View header, TextView cap, int notes) {
        int safe = Ui.dp(Ui.TEXT_SAFE_Y);
        for (Pad r : rows) {
            // never squeezed: the column's last child held to what's left would spill its rows past its foot
            if (r.isShown()) Assert.assertTrue("a row on show ends inside its column", foot(r, cc.panel) <= header.getBottom());
            if (r.isShown()) Assert.assertTrue("a row on show ends inside the title-safe area: foot " + foot(r, cc.panel) + ", panel "
                    + cc.panel.getHeight() + ", safe " + safe + ", column " + header.getTop() + "-" + header.getBottom(),
                    foot(r, cc.panel) <= cc.panel.getHeight() - safe);
        }
        int on = shown(rows);
        ViewGroup.MarginLayoutParams p = (ViewGroup.MarginLayoutParams) rows[0].getLayoutParams();
        if (on < Math.min(rows.length, notes)) {
            Assert.assertTrue("put away only for want of room", header.getHeight() + p.topMargin + p.height > cc.panel.getHeight() - 2 * safe);
        }
        String want = c.getString(R.string.notifications)
                + (notes > on ? "  ·  " + c.getResources().getQuantityString(R.plurals.n_more_caps, notes - on, notes - on) : "");
        Assert.assertEquals("the notifications on no row are counted", want, cap.getText().toString());
    }

    /** The VPN pill on show, Tonight's pick on, four notifications: no row past the screen's foot, nor a stop for the focus. */
    private void notesFitTheScreen() throws Exception {
        connect(note("Episode 4 is ready", "Severance"), note("3 new films", "Added to your list"),
                note("Download finished", "Season 2"), note("Recording tonight", "20:15 on BBC One"));
        ControlCenter cc = startHome();
        // Tonight's pick, as it's on by default: one ready for the next opening
        set(cc, "nextPick", new Picks.Pick(new JSONObject().put("name", "Severance").put("line", "Drama  ·  2022")
                .put("about", "Mark leads a team of office workers whose memories have been surgically divided.")));
        openCc();
        Pad[] rows = field(cc, "noteRows");
        until(() -> rows[0].isShown());
        settle();
        View pick = field(cc, "pick"), pill = field(cc, "vpnPill"), header = field(cc, "header");
        TextView info = field(cc, "info"), cap = field(cc, "notesCap");
        Assert.assertTrue("Tonight's pick on show", pick.isShown());
        pill.setVisibility(View.VISIBLE);
        String one = "Clear, 14° outside.", two = one + "\n4 new photos on the wall", three = two + "\n2 h 10 m in apps today";
        for (String line : new String[]{one, two, three}) {
            info.setText(line);
            run(48);
            fits(cc, rows, header, cap, 4);
        }
        info.setText(two);
        run(48);
        Assert.assertTrue("with the pill and the pick, the third row has no room", shown(rows) < 3);
        int before = shown(rows);

        // DOWN from the lowest row on show stays on what's on the screen
        View last = rows[before - 1];
        leaveTouchMode(last);
        Assert.assertTrue(last.isFocused());
        run(48);
        cc.handleKey(KeyEvent.KEYCODE_DPAD_DOWN, last);
        run(48);
        View focus = home.get().getWindow().getCurrentFocus();
        Assert.assertTrue("DOWN lands on something on show", focus != null && focus.isShown());
        Assert.assertTrue("and on the screen", foot(focus, cc.panel) <= cc.panel.getHeight());

        // the room back (no pill, one line): the row put away comes back
        pill.setVisibility(View.GONE);
        info.setText(one);
        run(48);
        fits(cc, rows, header, cap, 4);
        Assert.assertTrue("a row comes back with the room", shown(rows) > before);
    }

    @Test
    public void notesPastTheScreensFootNeverShowNorTakeTheFocusAt1080p() throws Exception {
        notesFitTheScreen();
    }

    @Test
    @Config(qualifiers = "w960dp-h540dp-tvdpi")
    public void notesPastTheScreensFootNeverShowNorTakeTheFocusAt720p() throws Exception {
        notesFitTheScreen();
    }

    @Test
    public void aHoldThatMovedTheFocusAwayLeavesTheNextPressAsAnyOther() throws Exception {
        plain = Robolectric.buildActivity(Activity.class).setup();
        Activity act = plain.get();
        Ui.init(act);
        FrameLayout root = new FrameLayout(act);
        act.setContentView(root);
        View away = new View(act);
        away.setFocusable(true);
        root.addView(away, new FrameLayout.LayoutParams(Ui.dp(120), Ui.dp(60)));
        Pad pad = new Pad(act, null, 26, 0f);
        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(Ui.dp(84), Ui.dp(84));
        pl.leftMargin = Ui.dp(200);
        root.addView(pad, pl);
        ControlCenter.Round round = new ControlCenter.Round(act, R.drawable.ic_moon, 0xFFFF9F0A);
        FrameLayout.LayoutParams rl = new FrameLayout.LayoutParams(Ui.dp(60), Ui.dp(60));
        rl.leftMargin = Ui.dp(400);
        root.addView(round, rl);
        run(50);
        plain.windowFocusChanged(true);
        run(50);
        int[] clicks = new int[2], holds = new int[2];
        // as Standby's and Sleep's holds do: the quiet screen closes Control Center, taking the focus with it
        pad.onClick = () -> clicks[0]++;
        pad.onLongClick = () -> {
            holds[0]++;
            away.requestFocus();
        };
        round.onClick = () -> clicks[1]++;
        round.onLongClick = () -> {
            holds[1]++;
            away.requestFocus();
        };
        View[] both = {pad, round};
        for (int i = 0; i < both.length; i++) {
            View v = both[i];
            leaveTouchMode(v);
            Assert.assertTrue(v.isFocused());
            run(600);
            long down = SystemClock.uptimeMillis();
            act.dispatchKeyEvent(new KeyEvent(down, down, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0));
            run(500);
            act.dispatchKeyEvent(new KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 1,
                    0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_LONG_PRESS));
            Assert.assertEquals("the hold", 1, holds[i]);
            Assert.assertTrue("took the focus away", away.isFocused());
            // the release goes where the focus is now
            act.dispatchKeyEvent(new KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0));

            // back on it later, a plain press: it clicks, and lets go to its focused size
            Assert.assertTrue(v.requestFocus());
            run(600);
            down = SystemClock.uptimeMillis();
            act.dispatchKeyEvent(new KeyEvent(down, down, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0));
            run(100);
            act.dispatchKeyEvent(new KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0));
            run(400);
            Assert.assertEquals("the next press clicks", 1, clicks[i]);
            Assert.assertEquals("and doesn't stay shrunk", Ui.FOCUS_SCALE_CONTROL, v.getScaleX(), 0.002f);
        }
    }

    private static PlaybackState state(int s) {
        return new PlaybackState.Builder().setState(s, 0, 1f).build();
    }

    @Test
    public void playReachesThePlayerPlayingBeforeOnePausedSince() throws Exception {
        MediaSession music = new MediaSession(c, "music"), video = new MediaSession(c, "video");
        try {
            MediaController a = music.getController(), b = video.getController();
            List<MediaController> both = Arrays.asList(a, b);
            Method report = MediaWatch.class.getDeclaredMethod("remember", MediaController.class, MediaMetadata.class, PlaybackState.class);
            report.setAccessible(true);
            // the music plays on; the video starts later, then pauses: Play reaches the music
            report.invoke(null, a, null, state(PlaybackState.STATE_PLAYING));
            run(100);
            report.invoke(null, b, null, state(PlaybackState.STATE_PLAYING));
            run(100);
            report.invoke(null, b, null, state(PlaybackState.STATE_PAUSED));
            Assert.assertSame("the one playing, though the other started later", a, MediaWatch.buttonSession(c, both));

            // both paused: the last to start
            report.invoke(null, a, null, state(PlaybackState.STATE_PAUSED));
            Assert.assertSame(b, MediaWatch.buttonSession(c, both));

            // both playing: the last to start
            run(100);
            report.invoke(null, a, null, state(PlaybackState.STATE_PLAYING));
            run(100);
            report.invoke(null, b, null, state(PlaybackState.STATE_PLAYING));
            Assert.assertSame(b, MediaWatch.buttonSession(c, both));
        } finally {
            music.release();
            video.release();
        }
    }
}
