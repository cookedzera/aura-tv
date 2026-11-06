package app.aura.home;

import android.app.Activity;
import android.content.Context;
import android.os.Looper;
import android.widget.TextView;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.robolectric.Shadows.shadowOf;

/**
 * Setup work that breaks on its own thread never leaves a button busy for good: "Switch everything
 * on" still says how it went, and wireless pairing lets the next code from the phone in.
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30, application = AuraApp.class)
public class WelcomeAdbFixesTest {
    private Context c;

    @Before
    public void setUp() {
        c = RuntimeEnvironment.getApplication();
        // Aura not let in: setup's commands stop before any connection is tried
        Prefs.device(c).edit().putBoolean("adb_ok", false).commit();
    }

    /** Runs what the setup thread posts to the main thread until {@code got} is filled, five seconds at most. */
    private static void waitForDone(AtomicReference<String> got) throws InterruptedException {
        for (int i = 0; i < 250 && got.get() == null; i++) {
            Thread.sleep(20);
            shadowOf(Looper.getMainLooper()).idle();
        }
    }

    private static Adb.Progress progress(AtomicReference<String> got, boolean breakAtFirstStep) {
        return new Adb.Progress() {
            @Override
            public void step(int i, int n, String name) {
                if (breakAtFirstStep) throw new Error("setup thread broke");
            }

            @Override
            public void done(int ok, int n, List<String> failed) {
                got.set(ok + " of " + n + ", not: " + failed);
            }
        };
    }

    /** Without debugging let in, setup stops at its first step and names it. */
    @Test
    public void setupWithoutDebuggingNamesItsFirstStep() throws Exception {
        String[][] steps = Adb.setup(c);
        AtomicReference<String> got = new AtomicReference<>();
        Adb.runSetup(c, progress(got, false));
        waitForDone(got);
        Assert.assertEquals("0 of " + steps.length + ", not: [" + steps[0][0] + "]", got.get());
    }

    /** A setup thread that throws still ends in done(), naming the step it broke on: Settings and the welcome stay busy until then. */
    @Test
    public void setupThatBreaksStillSaysHowItWent() throws Exception {
        String[][] steps = Adb.setup(c);
        AtomicReference<String> got = new AtomicReference<>();
        Adb.runSetup(c, progress(got, true));
        waitForDone(got);
        Assert.assertEquals("done() never came: the caller's button would stay busy for good",
                "0 of " + steps.length + ", not: [" + steps[0][0] + "]", got.get());
    }

    /** Wireless pairing that throws on its thread: the next code from the phone is let in, and the page says to try again. */
    @Test
    public void pairingThatBreaksLetsTheNextCodeIn() throws Exception {
        Activity act = Robolectric.buildActivity(Activity.class).create().get();
        Welcome w = new Welcome(act, new Backdrop(act), new Welcome.Host() {
            @Override
            public List<AppRepo.App> sample() {
                return new ArrayList<>();
            }

            @Override
            public void applied(String what) {
            }

            @Override
            public void done() {
            }

            @Override
            public void homeHint(String text) {
            }
        });
        TextView status = new TextView(act);
        Field page = Welcome.class.getDeclaredField("page"), setupStatus = Welcome.class.getDeclaredField("setupStatus"),
                pairing = Welcome.class.getDeclaredField("pairing");
        page.setAccessible(true);
        setupStatus.setAccessible(true);
        pairing.setAccessible(true);
        page.setInt(w, 7);
        setupStatus.set(w, status);
        Method pairWith = Welcome.class.getDeclaredMethod("pairWith", String.class);
        pairWith.setAccessible(true);
        // Android 11's pairing reads the code before anything else: none at all throws on the pairing thread
        pairWith.invoke(w, (Object) null);
        Assert.assertTrue("the wireless page should take the code", pairing.getBoolean(w));
        for (int i = 0; i < 250 && pairing.getBoolean(w); i++) {
            Thread.sleep(20);
            shadowOf(Looper.getMainLooper()).idle();
        }
        Assert.assertFalse("pairing stayed busy: every later code from the phone would be ignored", pairing.getBoolean(w));
        Assert.assertEquals(act.getString(R.string.pairing_didnt_finish_try_again), status.getText().toString());
    }
}
