package app.aura.home;

import android.content.Context;
import android.media.AudioManager;
import android.view.KeyEvent;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A volume button held in an app on a TV whose firmware logs no key presses (MediaTek): the remote's
 * own presses and releases (getevent) are read, its volume buttons known by name or learned from the
 * volume moving just after them, and the hold handed to HomeKeys from press to release.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = AuraApp.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class RawKeysTest {
    private final List<String> out = new ArrayList<>();
    private AudioManager am;
    private RawKeys.Learner learner;

    @Before
    public void setUp() {
        Context c = RuntimeEnvironment.getApplication();
        am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        volume(7);
        learner = new RawKeys.Learner(c,
                (code, up) -> out.add((code == KeyEvent.KEYCODE_VOLUME_UP ? "up" : "down") + (up ? " released" : " pressed")));
    }

    /** Where Android leaves the volume (as its own step on a volume button's press would). */
    private void volume(int level) {
        am.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0);
    }

    private static void after(long ms) {
        ShadowLooper.idleMainLooper(ms, TimeUnit.MILLISECONDS);
    }

    @Test
    public void getEventLinesAreReadAsPressesAndReleases() {
        RawKeys.Key down = RawKeys.parse("/dev/input/event0: EV_KEY       KEY_A                DOWN");
        Assert.assertNotNull(down);
        Assert.assertTrue(down.down);
        Assert.assertEquals("/dev/input/event0:KEY_A", down.name);
        RawKeys.Key up = RawKeys.parse("/dev/input/event0: EV_KEY       KEY_A                UP  ");
        Assert.assertNotNull(up);
        Assert.assertFalse(up.down);
        Assert.assertNull("a repeat isn't a press", RawKeys.parse("/dev/input/event3: EV_KEY       KEY_VOLUMEUP         REPEAT"));
        Assert.assertNull("sync lines aren't keys", RawKeys.parse("/dev/input/event0: EV_SYN       SYN_REPORT           00000000"));
        Assert.assertNull(RawKeys.parse("could not get driver version for /dev/input/mice, Inappropriate ioctl for device"));
        Assert.assertNull(RawKeys.parse(""));
    }

    @Test
    public void aButtonThatMovesTheVolumeIsLearnedAndItsHoldPassedOn() {
        // first press of the remote's volume up (KEY_A on this remote): Android steps the volume itself
        learner.key("event0:KEY_A", true);
        volume(8);
        after(RawKeys.Learner.LEARN_MS + 10);
        Assert.assertEquals("learned while still held: the hold is passed on", Arrays.asList("up pressed"), out);
        learner.key("event0:KEY_A", false);
        Assert.assertEquals("up released", out.get(1));

        // pressed again: known at once, no waiting
        out.clear();
        learner.key("event0:KEY_A", true);
        learner.key("event0:KEY_A", false);
        Assert.assertEquals(Arrays.asList("up pressed", "up released"), out);

        // volume down (KEY_F) learned the same way
        out.clear();
        learner.key("event0:KEY_F", true);
        volume(7);
        after(RawKeys.Learner.LEARN_MS + 10);
        learner.key("event0:KEY_F", false);
        Assert.assertEquals(Arrays.asList("down pressed", "down released"), out);
    }

    @Test
    public void aButtonItsDriverCallsVolumeIsKnownAtOnce() {
        learner.key("event4:KEY_VOLUMEDOWN", true);
        learner.key("event4:KEY_VOLUMEDOWN", false);
        Assert.assertEquals("no learning needed", Arrays.asList("down pressed", "down released"), out);
    }

    @Test
    public void aQuickTapIsLearnedWithoutBecomingAHold() {
        learner.key("event0:KEY_A", true);
        volume(8);
        learner.key("event0:KEY_A", false);
        after(RawKeys.Learner.LEARN_MS + 10);
        Assert.assertTrue("let go before it was learned: nothing to step", out.isEmpty());
        learner.key("event0:KEY_A", true);
        Assert.assertEquals("learned for next time", Arrays.asList("up pressed"), out);
    }

    @Test
    public void arrowsAreNeverVolumeAndAButtonAtTheVolumesEndIsAskedAgain() {
        // an arrow is never volume, even if something moves the volume as it's pressed
        learner.key("event0:KEY_DOWN", true);
        volume(6);
        after(RawKeys.Learner.LEARN_MS + 10);
        learner.key("event0:KEY_DOWN", false);
        Assert.assertTrue(out.isEmpty());
        Assert.assertFalse("not even passed to the main thread again", learner.wants("event0:KEY_DOWN"));

        // another button that moved nothing is let be for a while
        learner.key("event0:KEY_G", true);
        after(RawKeys.Learner.LEARN_MS + 10);
        Assert.assertFalse(learner.wants("event0:KEY_G"));

        // volume up at full volume moves nothing either, yet it's still a volume button
        volume(am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        learner.key("event0:KEY_A", true);
        after(RawKeys.Learner.LEARN_MS + 10);
        learner.key("event0:KEY_A", false);
        Assert.assertTrue(out.isEmpty());
        Assert.assertTrue("asked again", learner.wants("event0:KEY_A"));
        volume(5);
        learner.key("event0:KEY_A", true);
        volume(6);
        after(RawKeys.Learner.LEARN_MS + 10);
        Assert.assertEquals("learned below full volume", Arrays.asList("up pressed"), out);
    }

    @Test
    public void muteAndJumpsAreNeverLearnedAsVolume() {
        // Mute drops the volume to nothing as it goes down: not volume down
        learner.key("event0:KEY_MUTE", true);
        volume(0);
        after(RawKeys.Learner.LEARN_MS + 10);
        learner.key("event0:KEY_MUTE", false);
        Assert.assertTrue(out.isEmpty());
        Assert.assertFalse(learner.wants("event0:KEY_MUTE"));

        // a button the volume jumped several steps after (an app, the TV over HDMI) isn't learned either
        volume(7);
        learner.key("event0:KEY_B", true);
        volume(2);
        after(RawKeys.Learner.LEARN_MS + 10);
        learner.key("event0:KEY_B", false);
        learner.key("event0:KEY_B", true);
        Assert.assertTrue("still unknown", out.isEmpty());
    }

    @Test
    public void aButtonHeldAsTheLinkGoesIsLetGo() {
        learner.key("event4:KEY_VOLUMEUP", true);
        learner.letGo();
        Assert.assertEquals("its release never came: the hold stops anyway", Arrays.asList("up pressed", "up released"), out);
        out.clear();
        learner.letGo();
        Assert.assertTrue("nothing left held", out.isEmpty());
    }

    @Test
    public void getEventRunsStraightOnTheTerminal() {
        // through a pipe its output would be held back in 4 KB blocks: presses would come dozens at a time
        Assert.assertFalse(RawKeys.COMMAND.contains("|"));
    }
}
