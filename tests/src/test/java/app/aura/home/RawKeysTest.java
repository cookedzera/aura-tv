package app.aura.home;

import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A volume button held in an app on a TV whose firmware logs no key presses (MediaTek): the remote's
 * own presses and releases (getevent) are read, its volume buttons learned from the volume moving
 * just after them, and the hold handed to HomeKeys from press to release.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = AuraApp.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class RawKeysTest {
    /** A TV's music volume: Android steps it on a volume button's press, as the filter lets it. */
    private static final class Tv implements RawKeys.Audio {
        int level = 7;

        @Override
        public int level() {
            return level;
        }

        @Override
        public int max() {
            return 15;
        }
    }

    private final List<String> out = new ArrayList<>();
    private final Tv tv = new Tv();
    private final RawKeys.Learner learner = new RawKeys.Learner(tv,
            (code, up) -> out.add((code == KeyEvent.KEYCODE_VOLUME_UP ? "up" : "down") + (up ? " released" : " pressed")),
            new Handler(Looper.getMainLooper()));

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
        tv.level = 8;
        after(RawKeys.Learner.LEARN_MS + 10);
        Assert.assertEquals("learned while still held: the hold is passed on", java.util.Arrays.asList("up pressed"), out);
        learner.key("event0:KEY_A", false);
        Assert.assertEquals("up released", out.get(1));

        // pressed again: known at once, no waiting
        out.clear();
        learner.key("event0:KEY_A", true);
        learner.key("event0:KEY_A", false);
        Assert.assertEquals(java.util.Arrays.asList("up pressed", "up released"), out);

        // volume down (KEY_F) learned the same way
        out.clear();
        learner.key("event0:KEY_F", true);
        tv.level = 7;
        after(RawKeys.Learner.LEARN_MS + 10);
        learner.key("event0:KEY_F", false);
        Assert.assertEquals(java.util.Arrays.asList("down pressed", "down released"), out);
    }

    @Test
    public void aQuickTapIsLearnedWithoutBecomingAHold() {
        learner.key("event0:KEY_A", true);
        tv.level = 8;
        learner.key("event0:KEY_A", false);
        after(RawKeys.Learner.LEARN_MS + 10);
        Assert.assertTrue("let go before it was learned: nothing to step", out.isEmpty());
        learner.key("event0:KEY_A", true);
        Assert.assertEquals("learned for next time", java.util.Arrays.asList("up pressed"), out);
    }

    @Test
    public void arrowsAreLeftAloneAndAButtonAtTheVolumesEndIsAskedAgain() {
        // an arrow moves no volume: passed on never, and not looked at again on every press
        learner.key("event0:KEY_DOWN", true);
        after(RawKeys.Learner.LEARN_MS + 10);
        learner.key("event0:KEY_DOWN", false);
        Assert.assertTrue(out.isEmpty());

        // volume up at full volume moves nothing either, yet it's still a volume button
        tv.level = 15;
        learner.key("event0:KEY_A", true);
        after(RawKeys.Learner.LEARN_MS + 10);
        learner.key("event0:KEY_A", false);
        Assert.assertTrue(out.isEmpty());
        tv.level = 10;
        learner.key("event0:KEY_A", true);
        tv.level = 11;
        after(RawKeys.Learner.LEARN_MS + 10);
        Assert.assertEquals("asked again below full volume, and learned", java.util.Arrays.asList("up pressed"), out);
    }
}
