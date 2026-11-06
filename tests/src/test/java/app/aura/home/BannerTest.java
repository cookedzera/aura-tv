package app.aura.home;

import android.app.Activity;
import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
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
import org.robolectric.shadows.ShadowPackageManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import static org.robolectric.Shadows.shadowOf;

/**
 * Notification banners: one at a time in the top right corner, never taking the focus nor covering
 * what has it, never in the bottom fifth, gone after five seconds; HIGH glides in, LOW only fades;
 * the newest wins and counts the others, a muted app never shows, one that comes without room
 * waits a little. Control Center's hold offers the mute and Google TV's own panel where it answers;
 * Now Playing is the player the remote's Play key would reach.
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30, application = AuraApp.class, qualifiers = "w960dp-h540dp-xhdpi")
public class BannerTest {
    private static final String NETFLIX = "com.netflix.ninja", PLEX = "com.plexapp.android";
    private Context c;
    private boolean wasLight;
    private ActivityController<Activity> ac;
    private ActivityController<HomeActivity> home;
    private FrameLayout root;
    private View button;
    private Banner banner;
    private boolean room = true;

    /** Frame by frame: the choreographer is paused, so frames and layout barriers clear as the clock moves. */
    private static void run(long ms) {
        for (long t = 0; t < ms; t += 16) ShadowLooper.idleMainLooper(16, TimeUnit.MILLISECONDS);
    }

    /** Work on threads of its own (an app's name, Control Center's readings): waits up to three seconds for it. */
    private static void until(Callable<Boolean> done) throws Exception {
        for (int i = 0; i < 60 && !done.call(); i++) {
            Thread.sleep(50);
            run(48);
        }
    }

    /** A moment for Control Center's readings to land, when what's checked is that something stays away. */
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

    private static void listener(MediaWatch w) throws Exception {
        Field f = MediaWatch.class.getDeclaredField("connected");
        f.setAccessible(true);
        f.set(null, w);
    }

    @Before
    public void setUp() throws Exception {
        c = RuntimeEnvironment.getApplication();
        wasLight = Ui.light;
        // the animation clock keeps the last test's time: a spring started in the first frame would wait for it
        Field clock = android.view.animation.AnimationUtils.class.getDeclaredField("sAnimationState");
        clock.setAccessible(true);
        ((ThreadLocal<?>) clock.get(null)).remove();
        // other tests can leave a kids' profile or a connected listener behind in static state
        Profiles.forget();
        Profiles.use(c, Profiles.MAIN);
        listener(null);
        ShadowChoreographer.setPaused(true);
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16));
    }

    @After
    public void tearDown() throws Exception {
        if (ac != null) ac.pause().stop().destroy();
        if (home != null) home.pause().stop().destroy();
        listener(null);
        MediaWatch.mute(c, NETFLIX, false);
        MediaWatch.mute(c, PLEX, false);
        Ui.light = wasLight;
    }

    /** A plain screen: a button on the left with the focus, and the banner where home puts it. */
    private void screen(boolean light) throws Exception {
        Ui.light = light;
        ac = Robolectric.buildActivity(Activity.class).setup();
        Activity act = ac.get();
        Ui.init(act);
        root = new FrameLayout(act);
        act.setContentView(root);
        button = new View(act);
        button.setFocusable(true);
        button.setFocusableInTouchMode(true);
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(Ui.dp(120), Ui.dp(60));
        bl.leftMargin = Ui.dp(100);
        bl.topMargin = Ui.dp(200);
        root.addView(button, bl);
        banner = new Banner(act, null, new Banner.Host() {
            @Override
            public boolean bannerRoom() {
                return room;
            }

            @Override
            public void bannerShown(boolean shown) {
            }
        });
        root.addView(banner, Banner.place());
        // the window is attached by the first frame's layout, and only then can it have the focus
        run(50);
        ac.windowFocusChanged(true);
        run(50);
        Assert.assertTrue(button.requestFocus());
        // the apps' names known already: a banner comes up at once, not after a lookup on its thread
        Map<String, Object[]> faces = field(banner, "faces");
        faces.put(NETFLIX, new Object[]{"Netflix", null});
        faces.put(PLEX, new Object[]{"Plex", null});
    }

    private static MediaWatch.Note note(String pkg, String key, String title, String text) {
        MediaWatch.Note n = new MediaWatch.Note();
        n.pkg = pkg;
        n.key = key;
        n.title = title;
        n.text = text;
        n.clearable = true;
        return n;
    }

    @Test
    public void oneBannerTopRightNeverTakingTheFocusForFiveSeconds() throws Exception {
        screen(false);
        banner.arrived(note(NETFLIX, "n1", "Episode 4 is ready", "Severance"));
        run(16);
        Assert.assertEquals(View.VISIBLE, banner.getVisibility());
        Assert.assertTrue("comes down from above as it fades in", banner.getTranslationY() < 0f);
        run(600);
        Assert.assertEquals(1f, banner.getAlpha(), 0.01f);
        Assert.assertEquals("settled in its place", 0f, banner.getTranslationY(), 0.5f);
        Assert.assertSame("the focus stays where it was", button, ac.get().getWindow().getCurrentFocus());
        Assert.assertFalse("never a stop for the focus", banner.isFocusable() || banner.hasFocusable());
        // top right, 300 dp wide, 23 dp down and 40 dp in from the right, well clear of the subtitles
        Assert.assertEquals(Ui.dp(Banner.W), banner.getWidth());
        Assert.assertEquals(root.getWidth() - Ui.dp(Banner.RIGHT), banner.getRight());
        Assert.assertEquals(Ui.dp(Banner.TOP), banner.getTop());
        Assert.assertTrue(banner.getHeight() >= Ui.dp(Banner.H));
        Assert.assertTrue("never in the bottom fifth", banner.getBottom() <= root.getHeight() * 0.8f);
        TextView title = field(banner, "title"), body = field(banner, "body");
        Assert.assertEquals("Episode 4 is ready", title.getText().toString());
        Assert.assertEquals("Severance", body.getText().toString());
        View line = field(banner, "timeLine");
        Assert.assertEquals("HIGH counts the time down", View.VISIBLE, line.getVisibility());
        // five seconds, then out in 150 ms
        run(Banner.SHOW_MS - 616 - 100);
        Assert.assertEquals("still there just before five seconds", 1f, banner.getAlpha(), 0.01f);
        run(100 + Ui.CLOSE + 64);
        Assert.assertEquals(0f, banner.getAlpha(), 0.01f);
        Assert.assertEquals(View.INVISIBLE, banner.getVisibility());
        Assert.assertSame(button, ac.get().getWindow().getCurrentFocus());
    }

    @Test
    public void lowOnlyFadesIn() throws Exception {
        screen(true);
        banner.arrived(note(NETFLIX, "n1", "Download finished", ""));
        float last = 0f;
        for (int i = 0; i < 12; i++) {
            run(16);
            Assert.assertEquals("LOW never moves it", 0f, banner.getTranslationY(), 0f);
            Assert.assertTrue("only ever fading in", banner.getAlpha() >= last);
            last = banner.getAlpha();
        }
        run(48);
        Assert.assertEquals("in within 200 ms", 1f, banner.getAlpha(), 0.001f);
        View line = field(banner, "timeLine");
        Assert.assertEquals("no hairline counting down: nothing to draw while it rests", View.GONE, line.getVisibility());
        TextView body = field(banner, "body");
        Assert.assertEquals("no text, no line for it", View.GONE, body.getVisibility());
    }

    @Test
    public void theNewestWinsAndCountsTheOthers() throws Exception {
        screen(false);
        banner.arrived(note(NETFLIX, "n1", "Episode 4 is ready", ""));
        run(1000);
        banner.arrived(note(PLEX, "p1", "3 new films", ""));
        run(200);
        TextView title = field(banner, "title"), count = field(banner, "count");
        Assert.assertEquals("3 new films", title.getText().toString());
        Assert.assertEquals(View.VISIBLE, count.getVisibility());
        Assert.assertEquals("+1", count.getText().toString());
        // its app changing the one on show: the words change in place, nothing more is counted
        banner.arrived(note(PLEX, "p1", "4 new films", ""));
        Assert.assertEquals("4 new films", title.getText().toString());
        Assert.assertEquals("+1", count.getText().toString());
        // five seconds again from the newest
        run(Banner.SHOW_MS - 400);
        Assert.assertEquals(1f, banner.getAlpha(), 0.01f);
        run(400 + Ui.CLOSE + 64);
        Assert.assertEquals(View.INVISIBLE, banner.getVisibility());
        Integer replaced = field(banner, "replaced");
        Assert.assertEquals("the count goes with it", 0, replaced.intValue());
    }

    @Test
    public void withoutRoomTheNewestWaitsAWhile() throws Exception {
        screen(false);
        room = false;
        banner.arrived(note(NETFLIX, "n1", "First", ""));
        banner.arrived(note(PLEX, "p1", "Second", ""));
        run(1500);
        Assert.assertEquals("nothing over a panel", View.INVISIBLE, banner.getVisibility());
        room = true;
        run(1100);
        Assert.assertEquals(View.VISIBLE, banner.getVisibility());
        TextView title = field(banner, "title"), count = field(banner, "count");
        Assert.assertEquals("the newest", "Second", title.getText().toString());
        Assert.assertEquals("+1", count.getText().toString());
        banner.clear();
        Assert.assertEquals("cleared at once", View.INVISIBLE, banner.getVisibility());
        // one that waited longer than half a minute is let go
        room = false;
        banner.arrived(note(NETFLIX, "n2", "Stale", ""));
        run(Banner.WAIT_MS + 1000);
        room = true;
        run(1500);
        Assert.assertEquals(View.INVISIBLE, banner.getVisibility());
    }

    @Test
    public void neverOverWhatHasTheFocus() throws Exception {
        screen(false);
        // a button up in the corner, as the tvOS style's top buttons are
        View top = new View(ac.get());
        top.setFocusable(true);
        top.setFocusableInTouchMode(true);
        root.addView(top, new FrameLayout.LayoutParams(Ui.dp(120), Ui.dp(40), Gravity.TOP | Gravity.END));
        run(50);
        Assert.assertTrue(top.requestFocus());
        banner.arrived(note(NETFLIX, "n1", "Episode 4 is ready", ""));
        run(500);
        Assert.assertEquals("waits while the focus is up there", View.INVISIBLE, banner.getVisibility());
        Assert.assertTrue(button.requestFocus());
        run(1100);
        Assert.assertEquals(View.VISIBLE, banner.getVisibility());
        Assert.assertSame(button, ac.get().getWindow().getCurrentFocus());
        // the focus going up under it sends it away
        Assert.assertTrue(top.requestFocus());
        run(Ui.CLOSE + 64);
        Assert.assertEquals(View.INVISIBLE, banner.getVisibility());
        Assert.assertSame(top, ac.get().getWindow().getCurrentFocus());
    }

    /** {@code title} from {@code pkg}, as Android tells the listener of it. */
    private void post(MediaWatch ws, String pkg, int id, String title, boolean ongoing) {
        Notification n = new Notification.Builder(c, "x").setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title).setOngoing(ongoing).build();
        shadowOf(ws).addActiveNotification(pkg, id, n);
        for (StatusBarNotification sbn : ws.getActiveNotifications()) {
            if (sbn.getPackageName().equals(pkg) && sbn.getId() == id) ws.onNotificationPosted(sbn);
        }
    }

    @Test
    public void theListenerFeedsItAndAMutedAppNeverShows() throws Exception {
        MediaWatch ws = Robolectric.buildService(MediaWatch.class).create().get();
        ws.onListenerConnected();
        try {
            screen(false);
            Assert.assertNotNull("the banner hears of new notifications", MediaWatch.onPosted);
            MediaWatch.mute(c, NETFLIX, true);
            post(ws, NETFLIX, 1, "Episode 4 is ready", false);
            run(500);
            Assert.assertEquals("a muted app never shows", View.INVISIBLE, banner.getVisibility());
            // nor what's ongoing, nor Aura's own
            post(ws, PLEX, 2, "Syncing", true);
            post(ws, c.getPackageName(), 3, "Aura", false);
            run(500);
            Assert.assertEquals(View.INVISIBLE, banner.getVisibility());
            post(ws, PLEX, 4, "3 new films", false);
            until(() -> banner.getVisibility() == View.VISIBLE);
            TextView title = field(banner, "title");
            Assert.assertEquals("3 new films", title.getText().toString());
        } finally {
            ws.onListenerDisconnected();
            run(100);
        }
    }

    /** Home started and settled, Control Center not yet open. */
    private ControlCenter startHome() throws Exception {
        Prefs.device(c).edit().putBoolean("welcomed", true).putString("set_light", "on").commit();
        ShadowBluetoothAdapter.setIsBluetoothSupported(true);
        Field w = HomeActivity.class.getDeclaredField("warm");
        w.setAccessible(true);
        w.setBoolean(null, true);
        home = Robolectric.buildActivity(HomeActivity.class).setup();
        run(50);
        home.windowFocusChanged(true);
        run(2500);
        return field(home.get(), "cc");
    }

    @Test
    public void aHeldNotificationOffersToMuteItsApp() throws Exception {
        MediaWatch ws = Robolectric.buildService(MediaWatch.class).create().get();
        ws.onListenerConnected();
        try {
            for (int i = 1; i <= 2; i++) {
                Notification n = new Notification.Builder(c, "x").setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle("Episode " + i + " is ready").build();
                shadowOf(ws).addActiveNotification(NETFLIX, i, n);
            }
            ControlCenter cc = startHome();
            Pad[] rows = field(cc, "noteRows");
            TextView[] titles = field(cc, "noteTitles"), bodies = field(cc, "noteBodies");
            call(home.get(), "openCc", new Class<?>[]{boolean.class}, false);
            until(() -> rows[1].isShown());
            String second = titles[1].getText().toString();

            // hold OK on the first: dismissed as before, and its row stays to offer the mute
            rows[0].onLongClick.run();
            run(100);
            Assert.assertTrue(rows[0].isShown());
            Assert.assertEquals(c.getString(R.string.dismissed), titles[0].getText().toString());
            Assert.assertEquals(c.getString(R.string.ok_mutes_banners, NETFLIX), bodies[0].getText().toString());
            Assert.assertEquals("the other one stays below it", second, titles[1].getText().toString());

            // OK there mutes the app's banners; OK again brings them back
            rows[0].onClick.run();
            Assert.assertTrue(MediaWatch.muted(c, NETFLIX));
            Assert.assertEquals(c.getString(R.string.ok_unmutes_banners, NETFLIX), bodies[0].getText().toString());
            rows[0].onClick.run();
            Assert.assertFalse(MediaWatch.muted(c, NETFLIX));

            // the list read again (the dismissed one perhaps still in it) keeps the offer where it was
            cc.refresh();
            settle();
            Assert.assertEquals(c.getString(R.string.dismissed), titles[0].getText().toString());
            Assert.assertEquals(second, titles[1].getText().toString());

            // opened again, the row is a notification once more
            home.get().closeCc(false);
            run(300);
            call(home.get(), "openCc", new Class<?>[]{boolean.class}, false);
            run(100);
            Assert.assertNotEquals(c.getString(R.string.dismissed), titles[0].getText().toString());
        } finally {
            ws.onListenerDisconnected();
            run(100);
        }
    }

    @Test
    public void googleTvsOwnPanelIsOfferedWhereItAnswers() throws Exception {
        ControlCenter cc = startHome();
        Pad pill = field(cc, "googlePill");
        final ControlCenter.SystemPanel wired = cc.systemPanel;
        Assert.assertNotNull("home lets Control Center ask for Google's panel", wired);
        call(home.get(), "openCc", new Class<?>[]{boolean.class}, false);
        cc.refresh();
        settle();
        Assert.assertEquals("no Google TV here: no pill", View.GONE, pill.getVisibility());

        // Google TV's home with its panel, and the Home button service on
        ShadowPackageManager pm = shadowOf(c.getPackageManager());
        ApplicationInfo ai = new ApplicationInfo();
        ai.packageName = Safety.GOOGLE_TV_HOME;
        ai.flags = ApplicationInfo.FLAG_SYSTEM;
        ai.enabled = true;
        PackageInfo pi = new PackageInfo();
        pi.packageName = Safety.GOOGLE_TV_HOME;
        pi.applicationInfo = ai;
        pm.installPackage(pi);
        ComponentName panel = new ComponentName(Safety.GOOGLE_TV_HOME, Safety.GOOGLE_TV_HOME + ".dashboard.DashboardHandler");
        pm.addActivityIfNotPresent(panel);
        IntentFilter f = new IntentFilter("android.app.action.TOGGLE_NOTIFICATION_HANDLER_PANEL");
        f.addCategory(Intent.CATEGORY_DEFAULT);
        pm.addIntentFilterForActivity(panel, f);
        String services = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        Settings.Secure.putString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                new ComponentName(c, HomeKeys.class).flattenToString());
        final int[] opened = {0};
        try {
            cc.systemPanel = null;
            cc.refresh();
            settle();
            Assert.assertEquals("home can't ask for it: no pill", View.GONE, pill.getVisibility());

            // Google's home switched off: its panel went with it
            cc.systemPanel = wired;
            ai.enabled = false;
            cc.refresh();
            settle();
            Assert.assertEquals("Google's home off: no pill", View.GONE, pill.getVisibility());
            ai.enabled = true;

            cc.systemPanel = () -> {
                opened[0]++;
                return true;
            };
            cc.refresh();
            until(() -> pill.getVisibility() == View.VISIBLE);
            Assert.assertEquals(View.VISIBLE, pill.getVisibility());
            pill.onClick.run();
            run(300);
            Assert.assertEquals("asked for once", 1, opened[0]);
            Boolean open = field(home.get(), "ccOpen");
            Assert.assertFalse("Control Center makes way for it", open);
        } finally {
            cc.systemPanel = wired;
            Settings.Secure.putString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, services);
        }
    }

    private static PlaybackState state(int s) {
        return new PlaybackState.Builder().setState(s, 0, 1f).build();
    }

    @Test
    public void nowPlayingIsThePlayerPlayWouldReach() throws Exception {
        MediaSession music = new MediaSession(c, "music"), video = new MediaSession(c, "video");
        try {
            MediaController a = music.getController(), b = video.getController();
            List<MediaController> both = Arrays.asList(a, b);
            Method report = MediaWatch.class.getDeclaredMethod("remember", MediaController.class, MediaMetadata.class, PlaybackState.class);
            report.setAccessible(true);
            report.invoke(null, a, null, state(PlaybackState.STATE_PAUSED));
            report.invoke(null, b, null, state(PlaybackState.STATE_PAUSED));
            Assert.assertNull("neither has played: nothing tells them apart", MediaWatch.buttonSession(c, both));

            // the music plays, then the video; both pause: Play goes to the video, the last to play
            report.invoke(null, a, null, state(PlaybackState.STATE_PLAYING));
            run(100);
            report.invoke(null, b, null, state(PlaybackState.STATE_PLAYING));
            run(100);
            report.invoke(null, a, null, state(PlaybackState.STATE_PAUSED));
            report.invoke(null, b, null, state(PlaybackState.STATE_PAUSED));
            Assert.assertSame(b, MediaWatch.buttonSession(c, both));

            // the music resumed, then paused again: now it's the music
            run(100);
            report.invoke(null, a, null, state(PlaybackState.STATE_PLAYING));
            report.invoke(null, a, null, state(PlaybackState.STATE_PAUSED));
            Assert.assertSame(a, MediaWatch.buttonSession(c, both));
        } finally {
            music.release();
            video.release();
        }
    }
}
