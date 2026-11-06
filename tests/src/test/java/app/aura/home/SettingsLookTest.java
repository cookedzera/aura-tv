package app.aura.home;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.XmlResourceParser;
import android.graphics.Bitmap;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.widget.TextView;

import org.junit.After;
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
import org.robolectric.shadows.ShadowChoreographer;
import org.robolectric.shadows.ShadowLooper;
import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * Settings on tvOS terms: rows are 40 dp capsules 6 dp apart; the main list is five groups of at
 * most seven with every row it had still on it; each trust page opens from its row and Back lands
 * on that row again; destructive rows are red and still ask twice; Permissions lists exactly what
 * Android reports as granted; the accessibility statement says what its file says; Privacy names
 * the servers and its switches are the real ones; the Photos page keeps forty small thumbnails.
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30, application = AuraApp.class, qualifiers = "w960dp-h540dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SettingsLookTest {
    private Context c;
    private ActivityController<HomeActivity> ac;
    private HomeActivity a;
    private SettingsPanel s;

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

    @SuppressWarnings("unchecked")
    private static <T> T staticField(Class<?> k, String name) throws Exception {
        Field f = k.getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(null);
    }

    private static Object call(Object o, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = o.getClass().getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(o, args);
    }

    /** Everything a row says, a line each: its name, then its value. */
    private static String lines(View v) {
        if (v instanceof TextView) return ((TextView) v).getText() + "\n";
        StringBuilder b = new StringBuilder();
        if (v instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) b.append(lines(((ViewGroup) v).getChildAt(i)));
        }
        return b.toString();
    }

    /** A row's name: the first words in it. */
    private static TextView name(View v) {
        if (v instanceof TextView) return (TextView) v;
        if (v instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
                TextView t = name(((ViewGroup) v).getChildAt(i));
                if (t != null) return t;
            }
        }
        return null;
    }

    private void key(int code) {
        a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
        a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
    }

    @Before
    public void setUp() throws Exception {
        c = RuntimeEnvironment.getApplication();
        // the animation clock's thread state outlives a test and never runs backwards: a fresh one
        ThreadLocal<?> clock = staticField(android.view.animation.AnimationUtils.class, "sAnimationState");
        clock.remove();
        // the main profile's settings and the TV's are one file: cleared once, or a second clear wipes the first's writes
        Prefs.device(c).edit().clear().putBoolean("welcomed", true).putString("set_light", "on").putString(Prefs.LAYOUT, "aura").commit();
        Profiles.forget();
        ShadowChoreographer.setPaused(true);
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16));
        Field w = HomeActivity.class.getDeclaredField("warm");
        w.setAccessible(true);
        w.setBoolean(null, true);
    }

    @After
    public void tearDown() {
        if (ac != null) ac.pause().stop().destroy();
    }

    /** Home, settled, then Settings opened on {@code page} ("" for the main list); its rows. */
    private ViewGroup open(String page) throws Exception {
        ac = Robolectric.buildActivity(HomeActivity.class).setup();
        a = ac.get();
        // with frames paused the window attaches on the first frame: focus can only be given after it
        run(50);
        ac.windowFocusChanged(true);
        run(1500);
        s = field(a, "settings");
        call(a, "openSettings", new Class<?>[]{String.class}, page);
        run(800);
        return field(s, "rows");
    }

    /** A TV has no touch mode: rows take focus only once the simulator has left it. */
    private static void remote(ViewGroup rows, String tag) {
        View v = rows.findViewWithTag(tag);
        assertNotNull(tag, v);
        v.requestFocusFromTouch();
        Assume.assumeFalse("the simulated TV left touch mode", v.isInTouchMode());
    }

    /** Waits, in simulated and real time, for work behind the screen to land. */
    private static void await(Field f, Object o) throws Exception {
        for (int i = 0; i < 200 && f.get(o) == null; i++) {
            run(48);
            Thread.sleep(20);
        }
        run(100);
    }

    @Test
    public void theMainListIsFiveGroupsOfCapsulesWithEveryRowStillOnIt() throws Exception {
        ViewGroup rows = open("");
        List<Integer> groups = new ArrayList<>();
        Set<Object> tags = new HashSet<>();
        for (int i = 0; i < rows.getChildCount(); i++) {
            View v = rows.getChildAt(i);
            if (v instanceof TextView) {
                groups.add(0);
                continue;
            }
            if (!v.isFocusable()) continue;
            tags.add(v.getTag());
            if (!groups.isEmpty()) groups.set(groups.size() - 1, groups.get(groups.size() - 1) + 1);
            // the setup card stands apart, above the groups
            if ("tv".equals(v.getTag())) continue;
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
            assertEquals("40 dp tall: " + v.getTag(), Ui.dp(40), lp.height);
            assertEquals("6 dp apart: " + v.getTag(), Ui.dp(6), lp.topMargin);
            assertEquals("a capsule: " + v.getTag(), Ui.dpf(20), ((Pad) v).radius, 0.01f);
        }
        assertEquals("You, Home screen, This TV, Apps and data, Aura: " + groups, 5, groups.size());
        for (int n : groups) assertTrue("one to seven rows a group: " + groups, n >= 1 && n <= 7);
        for (String t : new String[]{"profiles", "wallpaper", "drop", Prefs.DLNA, "home", "hidden", "picks", Prefs.WEATHER, "city",
                "wx:unit", "shield", "anim", "android", "backup", "trouble", "updates", "remove",
                TrustPages.PRIVACY, TrustPages.PERMS, TrustPages.A11Y, TrustPages.DEBUGGING, TrustPages.ABOUT}) {
            assertTrue("on the main list: " + t, tags.contains(t));
        }
    }

    @Test
    public void eachTrustPageOpensFromItsRowAndBackLandsOnItAgain() throws Exception {
        ViewGroup rows = open("");
        remote(rows, TrustPages.PRIVACY);
        for (String page : new String[]{TrustPages.PRIVACY, TrustPages.PERMS, TrustPages.A11Y, TrustPages.DEBUGGING, TrustPages.ABOUT}) {
            call(s, "focusTag", new Class<?>[]{String.class}, page);
            run(100);
            // the window's own answer: Robolectric's Activity.getCurrentFocus() only returns what was last set on it
            assertEquals(page, a.getWindow().getCurrentFocus().getTag());
            key(KeyEvent.KEYCODE_DPAD_CENTER);
            run(600);
            assertEquals(page, field(s, "page"));
            View inside = a.getWindow().getCurrentFocus();
            assertNotNull("a row of " + page + " has the focus", inside);
            assertSame("a row of " + page + " has the focus, not " + inside, rows, inside.getParent());
            key(KeyEvent.KEYCODE_BACK);
            run(600);
            assertEquals("", field(s, "page"));
            assertEquals("Back lands on the row that opened " + page, page, a.getWindow().getCurrentFocus().getTag());
        }
    }

    @Test
    public void destructiveRowsAreRedAndStillAskTwice() throws Exception {
        ViewGroup rows = open("");
        assertEquals(SettingsPanel.DANGER, name(rows.findViewWithTag("remove")).getCurrentTextColor());
        call(s, "removeAura", new Class<?>[]{});
        assertTrue("one OK only arms it", (Boolean) field(s, "confirmRemove"));
        View armed = rows.findViewWithTag("remove");
        assertTrue(lines(armed).contains(a.getString(R.string.press_ok_again)));
        assertEquals(SettingsPanel.DANGER, name(armed).getCurrentTextColor());
    }

    @Test
    public void permissionsAreWhatAndroidReportsAndTheStatementIsItsFile() throws Exception {
        ViewGroup rows = open(TrustPages.PERMS);
        Object trust = field(s, "trust");
        Field perms = trust.getClass().getDeclaredField("perms");
        perms.setAccessible(true);
        await(perms, trust);
        assertNotNull("read behind the screen", perms.get(trust));
        PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), PackageManager.GET_PERMISSIONS);
        Map<String, String> help = field(s, "rowHelp");
        int[] flags = pi.requestedPermissionsFlags;
        for (int i = 0; pi.requestedPermissions != null && i < pi.requestedPermissions.length; i++) {
            String tag = "pm:" + pi.requestedPermissions[i];
            boolean granted = flags != null && i < flags.length && (flags[i] & PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0;
            assertEquals("listed exactly when granted: " + tag, granted, rows.findViewWithTag(tag) != null);
            if (granted) assertTrue("with what it's for: " + tag, help.get(tag).indexOf("\n\n") > 0);
        }

        Map<String, String> xml = new HashMap<>();
        XmlResourceParser x = c.getResources().getXml(R.xml.home_keys);
        for (int e = x.getEventType(); e != XmlPullParser.END_DOCUMENT; e = x.next()) {
            if (e != XmlPullParser.START_TAG || !"accessibility-service".equals(x.getName())) continue;
            for (int i = 0; i < x.getAttributeCount(); i++) {
                String n = x.getAttributeName(i);
                xml.put(n.substring(n.indexOf(':') + 1), x.getAttributeValue(i));
            }
        }
        assertEquals("the Home button service can't read the screen", "false", xml.get("canRetrieveWindowContent"));
        assertEquals("true", xml.get("canRequestFilterKeyEvents"));
        assertEquals("true", xml.get("canTakeScreenshot"));
        String events = xml.get("accessibilityEventTypes");
        assertTrue(events, "typeWindowStateChanged".equals(events) || Integer.decode(events) == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        call(s, "go", new Class<?>[]{String.class}, TrustPages.A11Y);
        run(400);
        assertEquals("canRetrieveWindowContent\nfalse\n", lines(rows.findViewWithTag("ax:screen")));
        assertEquals("accessibilityEventTypes\ntypeWindowStateChanged\n", lines(rows.findViewWithTag("ax:window")));
        assertEquals("canRequestFilterKeyEvents\ntrue\n", lines(rows.findViewWithTag("ax:keys")));
        assertEquals("canTakeScreenshot\ntrue\n", lines(rows.findViewWithTag("ax:shot")));
    }

    @Test
    public void privacyNamesEachServerAndItsSwitchesAreTheRealOnes() throws Exception {
        ViewGroup rows = open(TrustPages.PRIVACY);
        Map<String, String> help = field(s, "rowHelp");
        for (String host : new String[]{"api.open-meteo.com", "geocoding-api.open-meteo.com", "get.geojs.io", "fd.api.iris.microsoft.com",
                "www.bing.com", "bing.npanuhin.me", "commons.wikimedia.org", "wallhaven.cc", "api.unsplash.com", "api.tvmaze.com",
                "api.themoviedb.org", "image.tmdb.org", "zenquotes.io", "dummyjson.com", "sylvan.apple.com", "api.github.com",
                "speed.cloudflare.com"}) {
            boolean named = false;
            for (String h : help.values()) named |= h.contains(host);
            assertTrue("named on Privacy: " + host, named);
        }
        // a photo source's switch: flipping the weather's would ask the network for a forecast
        boolean was = Prefs.on(c, Prefs.SRC_WIKIMEDIA);
        ((Pad) rows.findViewWithTag("pv:wikimedia")).onClick.run();
        run(300);
        assertEquals("the source's own switch", !was, Prefs.on(c, Prefs.SRC_WIKIMEDIA));
        assertTrue(lines(rows.findViewWithTag("pv:wikimedia")).contains(a.getString(was ? R.string.off : R.string.on)));
    }

    @Test
    public void aboutWorksOutTheFingerprintsBehindTheScreen() throws Exception {
        ViewGroup rows = open(TrustPages.ABOUT);
        Field apk = TrustPages.class.getDeclaredField("apkSha");
        apk.setAccessible(true);
        await(apk, null);
        String sha = (String) apk.get(null);
        assertNotNull("worked out", sha);
        assertTrue("64 hex digits, or nothing where the APK can't be read: " + sha, sha.isEmpty() || sha.matches("[0-9a-f]{64}"));
        assertFalse(lines(rows.findViewWithTag("ab:apk")).contains(a.getString(R.string.checking)));
    }

    @Test
    public void photoThumbnailsAreReadAtTheirShownSizeAndFortyAtMostAreKept() throws Exception {
        File stored = Mine.thumb(c, "p1");
        assertTrue(stored.getParentFile().isDirectory() || stored.getParentFile().mkdirs());
        Bitmap big = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888);
        big.eraseColor(0xFF336699);
        try (FileOutputStream out = new FileOutputStream(stored)) {
            assertTrue(big.compress(Bitmap.CompressFormat.JPEG, 82, out));
        }
        Bitmap small = Mine.smallThumb(c, "p1");
        assertNotNull(small);
        assertEquals(160, small.getWidth());
        assertEquals(90, small.getHeight());
        assertEquals(Bitmap.Config.RGB_565, small.getConfig());

        open("");
        Map<String, Bitmap> thumbs = field(s, "thumbs");
        for (int i = 0; i < 50; i++) thumbs.put("t" + i, small);
        assertEquals(40, thumbs.size());
        assertFalse("the least recently shown go first", thumbs.containsKey("t0"));
        assertTrue(thumbs.containsKey("t49"));
    }
}
