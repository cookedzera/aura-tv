package app.aura.home;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.database.ContentObserver;
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
import org.robolectric.shadows.ShadowPackageManager;
import org.robolectric.util.ReflectionHelpers;

import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.TimeUnit;

import static org.robolectric.Shadows.shadowOf;

/**
 * Aura's process follows the TV's list of accessibility services: a service switched on while Aura
 * runs doesn't keep the Home button service from coming back after a force-stop, and the Home
 * button service taken off the list by hand stays off, Aura's own switching aside. A heal leaves a
 * service just switched on its time to start. Xiaomi's international home is guarded as its own is.
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30, application = AuraApp.class)
public class HomeServiceReviewTest {
    private static final String READER = "com.example.reader/.Reader", MAPPER = "com.example.mapper/.Keys";
    private Context c;
    private String me;

    /** A fresh process, as far as the Home button service's statics go (other tests leave theirs). */
    @Before
    public void freshProcess() {
        c = RuntimeEnvironment.getApplication();
        me = new ComponentName(c, HomeKeys.class).flattenToString();
        ReflectionHelpers.setStaticField(HomeKeys.class, "running", null);
        ReflectionHelpers.setStaticField(HomeKeys.class, "heals", 0);
        ReflectionHelpers.setStaticField(HomeKeys.class, "healAt", 0L);
        ReflectionHelpers.setStaticField(HomeKeys.class, "offAt", 0L);
        HomeKeys.live = false;
    }

    /** And left fresh for the tests after these, which share the statics. */
    @After
    public void leaveFresh() {
        freshProcess();
    }

    private static void run(long ms) {
        for (long t = 0; t < ms; t += 50) ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS);
    }

    private void grant() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(android.Manifest.permission.WRITE_SECURE_SETTINGS);
    }

    private void list(String services) {
        Settings.Secure.putString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, services);
    }

    /** Aura's process ends, as a force-stop ends it before Android drops its service: nothing of it hears what follows. */
    private void processEnds() {
        Collection<ContentObserver> watching = shadowOf(c.getContentResolver())
                .getContentObservers(Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES));
        Assert.assertFalse("the list is followed from the start of the process", watching.isEmpty());
        for (ContentObserver o : new ArrayList<>(watching)) c.getContentResolver().unregisterContentObserver(o);
    }

    /** And starts again (the media listener reconnecting, say), following the list afresh as AuraApp does. */
    private void processStarts() {
        freshProcess();
        HomeKeys.watchList(c);
    }

    @Test
    public void aServiceSwitchedOnWhileAuraRunsDoesNotKeepItOffAfterAForceStop() {
        grant();
        list(READER);
        Assert.assertTrue(HomeKeys.set(c, true));
        run(100);
        // another service switched on in Android's settings, with Aura's process running
        list(READER + ":" + me + ":" + MAPPER);
        run(100);
        // Force stop (a cleaner, Xiaomi's standby) takes Aura and the reader: Aura's process ends first,
        // then Android drops both services and writes the rest back in its own form
        processEnds();
        list("com.example.mapper/com.example.mapper.Keys");
        processStarts();
        HomeKeys.revive(c);
        Assert.assertTrue("the Home button comes back", HomeKeys.on(c));
        Assert.assertTrue(Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                .contains("com.example.mapper"));
        run(100);
        Assert.assertTrue(HomeKeys.wanted(c));
    }

    @Test
    public void theServiceTakenOffByHandWhileAuraRunsStaysOff() {
        grant();
        list(READER);
        Assert.assertTrue(HomeKeys.set(c, true));
        run(100);
        // switched off in Android's settings while not running (Android 11's crashed list: no onUnbind)
        list(READER);
        run(100);
        Assert.assertFalse("someone's choice", HomeKeys.wanted(c));
        HomeKeys.revive(c);
        HomeKeys.relist(c);
        Assert.assertFalse("not switched back on behind the user's back", HomeKeys.on(c));
        run(20_000);
        HomeKeys.heal(c);
        Assert.assertFalse(HomeKeys.on(c));
    }

    @Test
    public void aurasOwnSwitchOffIsNotTakenForAChoiceByHand() {
        grant();
        Assert.assertTrue(HomeKeys.set(c, true));
        run(100);
        // Aura's own switch-off, looked at only once the flag saying so is down again
        Prefs.device(c).edit().putBoolean("keys_healing", true).commit();
        Assert.assertTrue(HomeKeys.set(c, false));
        Prefs.device(c).edit().putBoolean("keys_healing", false).commit();
        run(100);
        Assert.assertTrue(HomeKeys.wanted(c));
        HomeKeys.relist(c);
        Assert.assertTrue(HomeKeys.on(c));
    }

    @Test
    public void aHealRightAfterARelistLetsTheServiceStart() {
        grant();
        list(READER);
        Assert.assertTrue(HomeKeys.set(c, true));
        run(100);
        // force-stopped in standby; the process back, and the service back on the list, Android still starting it
        processEnds();
        list(READER);
        processStarts();
        HomeKeys.relist(c);
        Assert.assertTrue(HomeKeys.on(c));
        // the home screen's look a few seconds on (it heals 4 s after it shows)
        run(4_000);
        HomeKeys.heal(c);
        Assert.assertTrue("not switched off while Android starts it", HomeKeys.on(c));
        Assert.assertFalse(Prefs.device(c).getBoolean("keys_healing", false));
        // still not running once its time is up: switched off and on, as before
        run(16_100);
        HomeKeys.heal(c);
        Assert.assertFalse(HomeKeys.on(c));
        run(500);
        Assert.assertTrue(HomeKeys.on(c));
        Assert.assertTrue(HomeKeys.wanted(c));
    }

    @Test
    public void aSwitchBackOnAfterARestartIsGivenItsTimeToo() {
        grant();
        Assert.assertTrue(HomeKeys.set(c, true));
        run(100);
        // the TV went off between Aura's switching the service off and back on
        Prefs.device(c).edit().putBoolean("keys_healing", true).commit();
        Assert.assertTrue(HomeKeys.set(c, false));
        run(100);
        processEnds();
        processStarts();
        HomeKeys.heal(c);
        Assert.assertTrue("on again, first thing", HomeKeys.on(c));
        run(4_000);
        HomeKeys.heal(c);
        Assert.assertTrue("and left to start", HomeKeys.on(c));
    }

    @Test
    public void xiaomisInternationalHomeIsNeverOffered() {
        String pkg = "com.mitv.tvhome.atv";
        ShadowPackageManager pm = shadowOf(c.getPackageManager());
        ApplicationInfo ai = new ApplicationInfo();
        ai.packageName = pkg;
        ai.flags = ApplicationInfo.FLAG_SYSTEM;
        ai.enabled = true;
        PackageInfo pi = new PackageInfo();
        pi.packageName = pkg;
        pi.applicationInfo = ai;
        pm.installPackage(pi);
        ComponentName home = new ComponentName(pkg, pkg + ".HomeActivity");
        pm.addActivityIfNotPresent(home);
        IntentFilter f = new IntentFilter(Intent.ACTION_MAIN);
        f.addCategory(Intent.CATEGORY_HOME);
        f.addCategory(Intent.CATEGORY_DEFAULT);
        pm.addIntentFilterForActivity(home, f);
        Assert.assertNull("PatchWall as Xiaomi's international sets name it", Safety.tvHome(c));
        Assert.assertFalse(Safety.tvHomeOn(c));
        Assert.assertEquals(Boolean.FALSE, Safety.retireTvHome(c, true));
    }
}
