package app.aura.home;

import android.app.Application;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.hardware.input.InputManager;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.InputDevice;
import android.view.KeyEvent;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowInputDevice;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowSystemClock;
import org.robolectric.util.ReflectionHelpers;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.robolectric.Shadows.shadowOf;

/**
 * The Home button service's safety fixes: no key filter with a mouse in that can't send Home,
 * teaching or not, nor in standby (and back on waking, even when the TV never says it woke); the
 * power keys left to Android; the service back after a force-stop as the media listener
 * reconnects; and on Android 11 a look every quarter of an hour while the service is wanted.
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30, application = AuraApp.class)
public class PhaseZeroServiceTest {
    private static final String NETFLIX = "com.netflix.ninja", READER = "com.example.reader/.Reader";
    private Application app;
    private Context c;

    /** A fresh process, as far as the Home button service's statics go (other tests leave theirs). */
    @Before
    public void freshProcess() {
        app = RuntimeEnvironment.getApplication();
        c = app;
        ReflectionHelpers.setStaticField(HomeKeys.class, "running", null);
        ReflectionHelpers.setStaticField(HomeKeys.class, "heals", 0);
        ReflectionHelpers.setStaticField(HomeKeys.class, "healAt", 0L);
        HomeKeys.live = false;
        HomeKeys.stopTeaching();
        HomeKeys.forgetKeys();
    }

    @After
    public void leaveFresh() {
        freshProcess();
    }

    private static void run(long ms) {
        for (long t = 0; t < ms; t += 50) ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS);
    }

    /** The service as Android connects it, with an app in front, past the minutes after a restart (when it opens home itself). */
    private static HomeKeys connected() {
        ShadowSystemClock.advanceBy(Duration.ofMinutes(4));
        HomeKeys hk = Robolectric.buildService(HomeKeys.class).create().get();
        hk.onServiceConnected();
        ReflectionHelpers.setField(hk, "front", NETFLIX);
        return hk;
    }

    /** The service's receivers listening for the screen going off. */
    private int screenReceivers() {
        int n = 0;
        for (BroadcastReceiver r : shadowOf(app).getReceiversForIntent(new Intent(Intent.ACTION_SCREEN_OFF))) {
            if (r.getClass().getName().startsWith(HomeKeys.class.getName() + "$")) n++;
        }
        return n;
    }

    private static HomeKeys.Device dev(boolean external, boolean virtual, int sources, boolean home, int vendor, int product) {
        return new HomeKeys.Device(external, virtual, sources, home, vendor, product);
    }

    /** An external input device as Android lists it, for the simulated input manager. */
    private static InputDevice device(int id, int sources) {
        InputDevice d = ShadowInputDevice.makeInputDeviceNamed("device " + id);
        ReflectionHelpers.setField(d, "mId", id);
        ReflectionHelpers.setField(d, "mIsExternal", true);
        ReflectionHelpers.setField(d, "mSources", sources);
        return d;
    }

    @Test
    public void onlyAMouseThatCannotSendHomeCounts() {
        int mouse = InputDevice.SOURCE_MOUSE, keys = InputDevice.SOURCE_KEYBOARD | InputDevice.SOURCE_DPAD;
        // the TV's own remote receiver, always there: built in, with the ids built-in receivers report
        HomeKeys.Device ir = dev(false, false, keys, true, 0x0001, 0x0001);
        Assert.assertFalse("the remote alone", HomeKeys.mouseIn(ir));
        Assert.assertTrue("a USB mouse", HomeKeys.mouseIn(ir, dev(true, false, mouse, false, 0x046d, 0xc077)));
        Assert.assertTrue("a Bluetooth mouse, beside a Bluetooth remote that has Home",
                HomeKeys.mouseIn(ir, dev(true, false, keys, true, 0x0957, 0x0004), dev(true, false, mouse, false, 0x046d, 0xb02a)));
        Assert.assertFalse("TCL's built-in gIrTouch_Mouse", HomeKeys.mouseIn(ir, dev(false, false, mouse, false, 0x0001, 0x0001)));
        Assert.assertFalse("an air-mouse dongle whose keyboard half has Home",
                HomeKeys.mouseIn(ir, dev(true, false, mouse, false, 0x1d57, 0xad03), dev(true, false, keys, true, 0x1d57, 0xad03)));
        Assert.assertFalse("an air-mouse remote Android merged into one device",
                HomeKeys.mouseIn(ir, dev(true, false, keys | mouse, true, 0x25a7, 0x5704)));
        Assert.assertFalse("a virtual device", HomeKeys.mouseIn(ir, dev(true, true, mouse, false, 0, 0)));
        Assert.assertTrue("ids built-in receivers report don't make a mouse one with the remote",
                HomeKeys.mouseIn(ir, dev(true, false, mouse, false, 0x0001, 0x0001)));
    }

    @Test
    public void aMousePluggedInLetsTheFilterGoEvenWhileTeaching() {
        HomeKeys hk = connected();
        InputManager im = (InputManager) c.getSystemService(Context.INPUT_SERVICE);
        InputManager.InputDeviceListener devices = ReflectionHelpers.getField(hk, "devices");
        // an air-mouse remote that sends Home: keys still come here
        shadowOf(im).addInputDevice(device(7, InputDevice.SOURCE_KEYBOARD | InputDevice.SOURCE_MOUSE));
        shadowOf(im).addInputDeviceKeys(7, new int[]{KeyEvent.KEYCODE_HOME});
        devices.onInputDeviceAdded(7);
        Assert.assertTrue(hk.wish());
        shadowOf(im).addInputDevice(device(8, InputDevice.SOURCE_MOUSE));
        devices.onInputDeviceAdded(8);
        Assert.assertFalse("a mouse that can't send Home: the filter goes", hk.wish());
        Assert.assertTrue(HomeKeys.teach((code, scan) -> { }));
        Assert.assertFalse("even while a button is being taught", hk.wish());
        HomeKeys.stopTeaching();
        hk.onUnbind(new Intent());
    }

    @Test
    public void standbyLetsTheFilterGoAndWakingBringsItBack() {
        Previews.set(c, false);
        HomeKeys hk = connected();
        // Android connecting it again without unbinding: still heard once
        hk.onServiceConnected();
        Assert.assertEquals(1, screenReceivers());
        Assert.assertTrue("an app in front: keys come here", hk.wish());

        // Home pressed in the app, and the TV goes to standby before the press has played out
        shadowOf(app).clearNextStartedActivities();
        Assert.assertTrue(hk.onKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HOME)));
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        shadowOf(pm).turnScreenOn(false);
        run(2_000);
        Assert.assertFalse("in standby the filter is let go", hk.wish());
        Assert.assertNull("the press cut short opens nothing in the dark", shadowOf(app).getNextStartedActivity());
        Assert.assertTrue("nor goes home", shadowOf(hk).getGlobalActionsPerformed().isEmpty());

        shadowOf(pm).turnScreenOn(true);
        run(1_000);
        Assert.assertTrue("awake again: keys come here", hk.wish());

        // a wake whose broadcast never came: the screen is asked afresh
        shadowOf(pm).turnScreenOn(false);
        run(100);
        shadowOf(pm).setIsInteractive(true);
        Assert.assertTrue(hk.wish());

        hk.onUnbind(new Intent());
        Assert.assertEquals("let go of with the service", 0, screenReceivers());
    }

    @Test
    public void thePowerKeysAreAndroidsAlone() {
        HomeKeys hk = Robolectric.buildService(HomeKeys.class).create().get();
        // even one given an app, or pressed while a button is being taught
        HomeKeys.give(c, KeyEvent.KEYCODE_TV_POWER, 0, NETFLIX);
        Assert.assertTrue(HomeKeys.teach((code, scan) -> { }));
        try {
            for (int k : new int[]{KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_SLEEP, KeyEvent.KEYCODE_SOFT_SLEEP,
                    KeyEvent.KEYCODE_WAKEUP, KeyEvent.KEYCODE_TV_POWER}) {
                Assert.assertFalse("key " + k, hk.onKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, k)));
                Assert.assertFalse("key " + k, hk.onKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, k)));
                Assert.assertFalse("nor taught where it reaches Aura's own window: key " + k, HomeKeys.teachable(k));
            }
            Assert.assertTrue("still waiting for the button to teach", HomeKeys.teaching());
        } finally {
            HomeKeys.stopTeaching();
            HomeKeys.forget(c, HomeKeys.id(KeyEvent.KEYCODE_TV_POWER, 0));
        }
    }

    @Test
    public void theServiceComesBackAsTheMediaListenerReconnectsAfterAForceStop() {
        shadowOf(app).grantPermissions(android.Manifest.permission.WRITE_SECURE_SETTINGS);
        Settings.Secure.putString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, READER);
        Assert.assertTrue(HomeKeys.set(c, true));
        // firmware force-stops Aura in standby: Android drops its service, then connects the listener again, starting Aura
        Settings.Secure.putString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, READER);
        MediaWatch w = Robolectric.buildService(MediaWatch.class).create().get();
        try {
            w.onListenerConnected();
            Assert.assertTrue("the Home button comes back", HomeKeys.on(c));
            Assert.assertTrue(Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                    .contains("com.example.reader"));

            // Home handed back to the TV's own launcher: left off, and still wanted for when Home is Aura's again
            Settings.Secure.putString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, READER);
            HomeKeys.setTakeover(c, false);
            w.onListenerConnected();
            Assert.assertFalse(HomeKeys.on(c));
            Assert.assertTrue(HomeKeys.wanted(c));
        } finally {
            w.onListenerDisconnected();
            run(100);
        }
    }

    @Test
    public void aWantedServiceIsLookedAtEveryQuarterHour() {
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        // a heal can't switch anything without the secure-settings permission: no reason to wake Aura for one
        DailyJob.healing(c, true);
        Assert.assertNull(js.getPendingJob(DailyJob.HEAL_JOB));
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(android.Manifest.permission.WRITE_SECURE_SETTINGS);
        HomeKeys hk = connected();
        JobInfo job = js.getPendingJob(DailyJob.HEAL_JOB);
        Assert.assertNotNull("the service connecting starts the looks", job);
        Assert.assertEquals(new ComponentName(c, DailyJob.class), job.getService());
        Assert.assertTrue(job.isPeriodic());
        Assert.assertTrue("no more often than Android allows", job.getIntervalMillis() >= 15 * 60_000L);
        Assert.assertTrue("kept through a restart", job.isPersisted());
        hk.onUnbind(new Intent());
        DailyJob.healing(c, false);
        Assert.assertNull("no longer wanted: no more looks", js.getPendingJob(DailyJob.HEAL_JOB));
    }
}
