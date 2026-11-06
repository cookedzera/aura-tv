package app.aura.home;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashSet;
import java.util.concurrent.TimeUnit;

import static org.robolectric.Shadows.shadowOf;

/**
 * Only the home screen's own crashes count towards handing Home back; the Home button service is
 * switched off and on again after a try that couldn't, but a switch-off by hand stands; and the
 * sleep timer brings the sound back only once the TV has really gone to standby.
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30, application = AuraApp.class)
public class HomeSafetyFixesTest {
    private static final String A = "app.aura.home.";
    private Context c;

    /** A fresh process, as far as the Home button service's statics go (other tests leave theirs). */
    @Before
    public void freshProcess() throws Exception {
        c = RuntimeEnvironment.getApplication();
        reset("heals", 0);
        reset("healAt", 0L);
        reset("running", null);
        HomeKeys.live = false;
    }

    /** And left fresh for the tests after these, which share the statics. */
    @After
    public void leaveFresh() throws Exception {
        freshProcess();
    }

    private static void reset(String name, Object value) throws Exception {
        Field f = HomeKeys.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(null, value);
    }

    private static void run(long ms) {
        for (long t = 0; t < ms; t += 50) ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS);
    }

    /** An error whose stack is these classes, innermost first, as Android hands it to the crash watch. */
    private static Throwable thrown(Throwable cause, String... classes) {
        Throwable t = new RuntimeException("test", cause);
        StackTraceElement[] st = new StackTraceElement[classes.length];
        for (int i = 0; i < classes.length; i++) st[i] = new StackTraceElement(classes[i], "run", null, -1);
        t.setStackTrace(st);
        return t;
    }

    @Test
    public void onlyTheHomeScreensOwnCrashesCount() {
        // what Android called was the media listener, a message from the phone link, the debugging link
        Assert.assertTrue(Safety.beside(thrown(null, A + "Prefs", A + "MediaWatch", "android.os.Handler", "android.os.Looper")));
        Assert.assertTrue(Safety.beside(thrown(null, A + "SettingsPanel", A + "Drop$$ExternalSyntheticLambda2", "android.os.Handler")));
        Assert.assertTrue(Safety.beside(thrown(null, A + "AdbTls$1", "android.os.Handler")));
        // the home screen calling into one of those and failing there is the home screen failing
        Assert.assertFalse(Safety.beside(thrown(null, A + "MediaWatch", A + "HomeActivity", "android.app.Activity", "android.os.Looper")));
        Assert.assertFalse(Safety.beside(thrown(null, A + "Weather", A + "HomeActivity$$ExternalSyntheticLambda7", "android.os.Handler")));
        // a sheet on the home screen isn't the weather job whose name it starts with
        Assert.assertFalse(Safety.beside(thrown(null, A + "WeatherSheet", "android.view.View", "android.view.Choreographer")));
        // the Home button service is part of home
        Assert.assertFalse(Safety.beside(thrown(null, A + "HomeKeys$$ExternalSyntheticLambda0", "android.os.Handler")));
        // drawing home failed inside Android, with no frame of Aura's: still counted
        Assert.assertFalse(Safety.beside(thrown(null, "android.graphics.BaseCanvas", "android.view.View", "android.view.Choreographer")));
        // Android's own "Unable to start activity" around it: the cause says whose it was
        Assert.assertFalse(Safety.beside(thrown(thrown(null, A + "HomeActivity", "android.app.Instrumentation"),
                "android.app.ActivityThread", "android.os.Looper")));
        Assert.assertTrue(Safety.beside(thrown(thrown(null, A + "Cast", "android.app.Instrumentation"),
                "android.app.ActivityThread", "android.os.Looper")));
    }

    @Test
    public void appsFreeUpMemorySwitchedOffAskForDebuggingBeforeAuraLeaves() {
        Assert.assertFalse(Safety.originalDisabled(c));
        Prefs.device(c).edit().putStringSet(Debloat.MINE, new HashSet<>(Arrays.asList("com.google.android.tvrecommendations"))).commit();
        Assert.assertTrue("only debugging can switch them back on", Safety.originalDisabled(c));
    }

    @Test
    public void aHealThatCouldNotSwitchLeavesTheNextOneFree() {
        String me = new ComponentName(c, HomeKeys.class).flattenToString();
        // switched on by hand before setup let Aura switch it: on, yet not running
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(android.Manifest.permission.WRITE_SECURE_SETTINGS);
        Settings.Secure.putString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, me);
        HomeKeys.heal(c);
        Assert.assertTrue("nothing could be switched, and nothing is left half done", HomeKeys.on(c));
        Assert.assertFalse(Prefs.device(c).getBoolean("keys_healing", false));

        // setup grants the secure settings: the next heal switches it off and on again
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(android.Manifest.permission.WRITE_SECURE_SETTINGS);
        HomeKeys.heal(c);
        Assert.assertFalse(HomeKeys.on(c));
        run(500);
        Assert.assertTrue(HomeKeys.on(c));
        Assert.assertFalse(Prefs.device(c).getBoolean("keys_healing", false));
        Assert.assertTrue(HomeKeys.wanted(c));

        // still not running: tried again a few times, never left switched off, then left be
        run(70_000);
        Assert.assertTrue(HomeKeys.on(c));
        HomeKeys.heal(c);
        Assert.assertTrue("its tries used up, this start of the process leaves it as it is", HomeKeys.on(c));
    }

    @Test
    public void droppedWithAuraForceStoppedItComesBack() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(android.Manifest.permission.WRITE_SECURE_SETTINGS);
        Settings.Secure.putString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "com.example.reader/.Reader");
        Assert.assertTrue(HomeKeys.set(c, true));
        // firmware force-stopping Aura in standby: Android drops only Aura's entry, and writes the rest back in its own form
        Settings.Secure.putString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "com.example.reader/com.example.reader.Reader");
        HomeKeys.relist(c);
        Assert.assertTrue("the Home button comes back after standby", HomeKeys.on(c));
        Assert.assertTrue(Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                .contains("com.example.reader"));
    }

    @Test
    public void anEditByHandToTheListStands() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(android.Manifest.permission.WRITE_SECURE_SETTINGS);
        Settings.Secure.putString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "com.example.reader/.Reader");
        Assert.assertTrue(HomeKeys.set(c, true));
        Assert.assertTrue(HomeKeys.wanted(c));
        // in Android's settings: Aura off, another service on (no onUnbind while Aura was on the crashed list)
        Settings.Secure.putString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "com.example.reader/.Reader:com.example.mapper/.Keys");
        HomeKeys.relist(c);
        Assert.assertFalse("not switched back on behind the user's back", HomeKeys.on(c));
        Assert.assertFalse(HomeKeys.wanted(c));
        HomeKeys.heal(c);
        Assert.assertFalse(HomeKeys.on(c));
    }

    @Test
    public void aurasOwnSwitchOffCutShortIsUndone() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(android.Manifest.permission.WRITE_SECURE_SETTINGS);
        Assert.assertTrue(HomeKeys.set(c, true));
        // heal's switch-off, then its switch back on lost
        Prefs.device(c).edit().putBoolean("keys_healing", true).commit();
        Assert.assertTrue(HomeKeys.set(c, false));
        Prefs.device(c).edit().putBoolean("keys_healing", false).commit();
        Assert.assertTrue("Aura's own switching isn't the user's choice", HomeKeys.wanted(c));
        HomeKeys.relist(c);
        Assert.assertTrue(HomeKeys.on(c));
    }

    @Test
    public void whatFollowsStandbyWaitsForIt() {
        final boolean[] off = {false};
        // no way to switch the TV off here: nothing that follows standby runs
        Assert.assertFalse(Power.screenOff(c, () -> off[0] = true));
        run(500);
        Assert.assertFalse(off[0]);
        // the standby admin: off at once, and what follows with it
        DevicePolicyManager dpm = (DevicePolicyManager) c.getSystemService(Context.DEVICE_POLICY_SERVICE);
        shadowOf(dpm).setActiveAdmin(new ComponentName(c, SleepAdmin.class));
        Assert.assertTrue(Power.screenOff(c, () -> off[0] = true));
        Assert.assertTrue(off[0]);
    }
}
