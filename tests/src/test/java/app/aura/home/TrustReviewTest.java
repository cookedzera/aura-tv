package app.aura.home;

import android.app.AppOpsManager;
import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.os.Process;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.junit.After;
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
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowAppOpsManager;
import org.robolectric.shadows.ShadowChoreographer;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowPackageManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/**
 * The trust pages: revoking usage access puts its app-op back too, and usage access
 * the app-op alone allows is listed; READ_LOGS revoked there is never granted again behind your
 * back; Privacy names every DNS filter it may ask and Microsoft's image servers, and shows no photo
 * source On while Fresh HD photographs is off; Developer options lead where they are, or say how
 * to switch them on.
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30, application = AuraApp.class, qualifiers = "w960dp-h540dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class TrustReviewTest {
    private static final String USAGE = "android.permission.PACKAGE_USAGE_STATS", LOGS = "android.permission.READ_LOGS";
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

    @Before
    public void setUp() throws Exception {
        c = RuntimeEnvironment.getApplication();
        // the animation clock's thread state outlives a test and never runs backwards: a fresh one
        Field clock = android.view.animation.AnimationUtils.class.getDeclaredField("sAnimationState");
        clock.setAccessible(true);
        ((ThreadLocal<?>) clock.get(null)).remove();
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

    /** Home, settled, then Settings opened on {@code page}; its rows. */
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

    /** Waits, in simulated and real time, for work behind the screen to land. */
    private static void await(Field f, Object o) throws Exception {
        for (int i = 0; i < 200 && f.get(o) == null; i++) {
            run(48);
            Thread.sleep(20);
        }
        run(100);
    }

    /** The page's rows once what it reads behind the screen ({@code read}, a field of the trust pages) is in. */
    private ViewGroup settled(String read) throws Exception {
        Object trust = field(s, "trust");
        Field f = trust.getClass().getDeclaredField(read);
        f.setAccessible(true);
        await(f, trust);
        assertNotNull(read, f.get(trust));
        return field(s, "rows");
    }

    /** {@code page} visited afresh, Settings opened again if OK on a row closed it. */
    private ViewGroup visit(String page, String read) throws Exception {
        if (s.isOpen()) {
            call(s, "go", new Class<?>[]{String.class}, TrustPages.A11Y);
            call(s, "go", new Class<?>[]{String.class}, page);
        } else {
            call(a, "openSettings", new Class<?>[]{String.class}, page);
        }
        run(300);
        return settled(read);
    }

    /** Whether Android reports PACKAGE_USAGE_STATS as granted to Aura, and how the usage app-op stands. */
    private void usage(boolean permission, int mode) {
        ShadowAppOpsManager ops = Shadow.extract(c.getSystemService(AppOpsManager.class));
        ops.setMode(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName(), mode);
        PackageInfo pi = shadowOf(c.getPackageManager()).getInternalMutablePackageInfo(c.getPackageName());
        if (pi.requestedPermissionsFlags == null) pi.requestedPermissionsFlags = new int[pi.requestedPermissions.length];
        for (int i = 0; i < pi.requestedPermissions.length; i++) {
            if (!USAGE.equals(pi.requestedPermissions[i])) continue;
            if (permission) pi.requestedPermissionsFlags[i] |= PackageInfo.REQUESTED_PERMISSION_GRANTED;
            else pi.requestedPermissionsFlags[i] &= ~PackageInfo.REQUESTED_PERMISSION_GRANTED;
        }
    }

    @Test
    public void revokingUsageAccessPutsItsAppOpBackFirst() {
        String pkg = c.getPackageName();
        // Android may restart Aura as the permission goes: the app-op can't wait until after
        String[] usage = {"appops set " + pkg + " GET_USAGE_STATS default", "pm revoke " + pkg + " " + USAGE};
        assertArrayEquals(usage, TrustPages.revokeCommands(pkg, USAGE));
        assertArrayEquals("allowed by the app-op alone, taken back the same way", usage, TrustPages.revokeCommands(pkg, TrustPages.USAGE_OP));
        assertArrayEquals(new String[]{"pm revoke " + pkg + " " + LOGS}, TrustPages.revokeCommands(pkg, LOGS));
    }

    @Test
    public void usageAccessIsListedWhicheverWayItWasGranted() throws Exception {
        String op = "pm:" + TrustPages.USAGE_OP, perm = "pm:" + USAGE;
        usage(false, AppOpsManager.MODE_ALLOWED);
        open(TrustPages.PERMS);
        ViewGroup rows = settled("perms");
        View row = rows.findViewWithTag(op);
        assertNotNull("allowed by its app-op alone, still listed", row);
        assertNull(rows.findViewWithTag(perm));
        assertTrue(lines(row), lines(row).startsWith(a.getString(R.string.usage_access) + "\n"));
        Map<String, String> help = field(s, "rowHelp");
        assertTrue(help.get(op), help.get(op).endsWith("\n\n" + TrustPages.USAGE_OP));
        // with the link up it can be taken back, as the permission can
        Prefs.device(c).edit().putBoolean("adb_ok", true).commit();
        s.build();
        assertTrue(lines(rows.findViewWithTag(op)).contains(a.getString(R.string.revoke)));
        Prefs.device(c).edit().putBoolean("adb_ok", false).commit();

        usage(true, AppOpsManager.MODE_ALLOWED);
        rows = visit(TrustPages.PERMS, "perms");
        assertNotNull(rows.findViewWithTag(perm));
        assertNull("granted both ways: listed once", rows.findViewWithTag(op));

        usage(false, AppOpsManager.MODE_DEFAULT);
        rows = visit(TrustPages.PERMS, "perms");
        assertNull(rows.findViewWithTag(perm));
        assertNull("granted neither way: not listed", rows.findViewWithTag(op));
    }

    @Test
    public void readLogsRevokedThereIsNeverGrantedAgainOnItsOwn() throws Exception {
        open(TrustPages.PERMS);
        settled("perms");
        Object trust = field(s, "trust");
        Field perms = trust.getClass().getDeclaredField("perms");
        perms.setAccessible(true);
        Method revoke = trust.getClass().getDeclaredMethod("revoke", TrustPages.Perm.class);
        revoke.setAccessible(true);
        TrustPages.Perm logs = new TrustPages.Perm(LOGS, "Read logs", "");
        revoke.invoke(trust, logs);
        assertFalse("one OK only asks again", Prefs.device(c).getBoolean(ProfileButton.REVOKED, false));
        // the list is read again once Android has been asked: then the revoke has run its course
        perms.set(trust, null);
        revoke.invoke(trust, logs);
        for (int i = 0; i < 200 && (perms.get(trust) == null || !Prefs.device(c).getBoolean(ProfileButton.REVOKED, false)); i++) {
            run(48);
            Thread.sleep(20);
        }
        assertTrue("kept even though debugging never answered", Prefs.device(c).getBoolean(ProfileButton.REVOKED, false));

        // Android 12 or lower, the permission gone and debugging connected: nothing grants it back
        shadowOf((Application) c).denyPermissions(LOGS);
        Field tried = ProfileButton.class.getDeclaredField("grantTried");
        tried.setAccessible(true);
        tried.setBoolean(null, false);
        Prefs.device(c).edit().putBoolean("adb_ok", true).commit();
        try {
            ProfileButton.start(c);
            assertFalse("not granted again behind your back", tried.getBoolean(null));
            // unrevoked, it would have been
            Prefs.device(c).edit().remove(ProfileButton.REVOKED).commit();
            ProfileButton.start(c);
            assertTrue(tried.getBoolean(null));
        } finally {
            tried.setBoolean(null, false);
            Prefs.device(c).edit().putBoolean("adb_ok", false).commit();
        }
    }

    @Test
    public void privacyNamesEveryFilterItMayAskAndMicrosoftsImageServers() throws Exception {
        open(TrustPages.PRIVACY);
        Map<String, String> help = field(s, "rowHelp");
        assertEquals("fd.api.iris.microsoft.com\n\n" + a.getString(R.string.privacy_spotlight_help), help.get("pv:spotlight"));
        String dns = help.get("pv:dns"), hosts = "\n" + dns.substring(0, dns.indexOf("\n\n")) + "\n";
        for (String[] f : Shield.FILTERS) {
            String parent = f[0].substring(f[0].indexOf('.') + 1);
            assertTrue("named on Privacy: " + f[0] + hosts, hosts.contains("\n" + f[0] + "\n") || hosts.contains("\n*." + parent + "\n"));
        }
        assertTrue("all in sight, the sentence beside the list having five lines:" + hosts, hosts.trim().split("\n").length <= 5);
        // one you typed yourself, first
        Prefs.device(c).edit().putString("shield_host", "dns.example.org").commit();
        s.build();
        assertTrue(help.get("pv:dns"), help.get("pv:dns").startsWith("dns.example.org\n"));
    }

    @Test
    public void noPhotoSourceIsOnWhileFreshHdPhotographsIsOff() throws Exception {
        Prefs.set(c, Prefs.DAILY, false);
        Prefs.set(c, Prefs.SRC_WIKIMEDIA, true);
        Prefs.set(c, Prefs.SRC_UNSPLASH, true);
        Prefs.device(c).edit().putString(Prefs.UNSPLASH_KEY, "key").commit();
        ViewGroup rows = open(TrustPages.PRIVACY);
        String[] sources = {"pv:spotlight", "pv:bing", "pv:wikimedia", "pv:wallhaven", "pv:unsplash"};
        for (String tag : sources) assertTrue(tag, lines(rows.findViewWithTag(tag)).endsWith("\n" + a.getString(R.string.off) + "\n"));
        // no switch there while none would do anything
        ((Pad) rows.findViewWithTag("pv:wikimedia")).onClick.run();
        run(300);
        assertTrue(Prefs.on(c, Prefs.SRC_WIKIMEDIA));

        Prefs.set(c, Prefs.DAILY, true);
        s.build();
        for (String tag : sources) {
            // Wallhaven waits to be asked for
            String want = a.getString(tag.equals("pv:wallhaven") ? R.string.off : R.string.on);
            assertTrue(tag, lines(rows.findViewWithTag(tag)).endsWith("\n" + want + "\n"));
        }
    }

    @Test
    public void developerOptionsLeadWhereTheyAreOrSayHowToSwitchThemOn() throws Exception {
        Settings.Global.putInt(c.getContentResolver(), Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0);
        Settings.Global.putInt(c.getContentResolver(), Settings.Global.ADB_ENABLED, 0);
        open(TrustPages.DEBUGGING);
        String off = a.getString(R.string.off_about_press_build_7);
        // off, on a TV without an About page of its own: its Settings, with the way to switch them on
        dev(off, Settings.ACTION_SETTINGS);
        page(Settings.ACTION_DEVICE_INFO_SETTINGS, "com.android.tv.settings.about.AboutActivity");
        dev(off, Settings.ACTION_DEVICE_INFO_SETTINGS);
        // on, but with no page for them here: it says where OK goes instead
        Settings.Global.putInt(c.getContentResolver(), Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 1);
        dev(a.getString(R.string.android_settings), Settings.ACTION_SETTINGS);
        page(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, "com.android.tv.settings.system.development.DevelopmentActivity");
        dev(a.getString(R.string.usb_debugging_is_here), Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
    }

    /** A settings page this TV has, answering {@code action}. */
    private void page(String action, String cls) {
        ShadowPackageManager pm = shadowOf(c.getPackageManager());
        ComponentName at = new ComponentName("com.android.tv.settings", cls);
        pm.addActivityIfNotPresent(at);
        IntentFilter f = new IntentFilter(action);
        f.addCategory(Intent.CATEGORY_DEFAULT);
        pm.addIntentFilterForActivity(at, f);
    }

    /** Network debugging visited afresh: Developer options says {@code says}, and OK opens {@code opens}. */
    private void dev(String says, String opens) throws Exception {
        View row = visit(TrustPages.DEBUGGING, "door").findViewWithTag("nd:dev");
        assertEquals(a.getString(R.string.developer_options) + "\n" + says + "\n", lines(row));
        while (shadowOf(a).getNextStartedActivity() != null) {
            // what was opened before this
        }
        ((Pad) row).onClick.run();
        run(300);
        Intent started = shadowOf(a).getNextStartedActivity();
        assertNotNull(says, started);
        assertEquals(says, opens, started.getAction());
    }
}
