package app.aura.home;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * The motion every screen shares: springs that keep their speed when the target moves, the
 * control pop, the held-key ladder and how fast runs shorten focus.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = AuraApp.class)
public class MotionTokensTest {
    @Test
    public void anAppTileOvershootsALittleAndSettlesQuickly() {
        Spring s = Spring.app();
        s.start(0, 0, 1, 0);
        Assert.assertTrue("at size by 100 ms: " + s.pos(100), s.pos(100) >= 0.98);
        double peak = 0;
        long peakAt = 0;
        for (long t = 0; t <= 400; t += 1) {
            if (s.pos(t) > peak) {
                peak = s.pos(t);
                peakAt = t;
            }
        }
        Assert.assertTrue("about 10 % past: " + peak, peak > 1.07 && peak < 1.12);
        Assert.assertTrue("near 125 ms: " + peakAt, peakAt >= 110 && peakAt <= 140);
        Assert.assertFalse(s.atRest(200, 0.002));
        Assert.assertTrue(s.atRest(450, 0.002));
    }

    @Test
    public void noBounceNeverOvershoots() {
        Spring s = Spring.focus();
        s.start(0, 0, 1, 0);
        double last = 0;
        for (long t = 0; t <= 1500; t += 5) {
            double x = s.pos(t);
            Assert.assertTrue("at " + t + ": " + x, x <= 1 + 1e-9 && x >= last - 1e-12);
            last = x;
        }
        Assert.assertTrue("95 % by about 230 ms: " + s.pos(240), s.pos(240) >= 0.95);
    }

    @Test
    public void retargetingKeepsPositionAndSpeed() {
        for (Spring s : new Spring[]{Spring.row(), Spring.smooth()}) {
            s.start(0, 0, 100, 0);
            double p = s.pos(60), v = s.vel(60);
            Assert.assertTrue("moving", v > 0);
            s.retarget(200, 60);
            Assert.assertEquals(p, s.pos(60), 1e-6);
            Assert.assertEquals(v, s.vel(60), 1e-6);
            Assert.assertTrue("keeps going the same way", s.pos(70) > p);
        }
    }

    @Test
    public void velocityMatchesTheSlopeOfPosition() {
        Spring s = Spring.app();
        s.start(0, 3, 1, 0);
        for (long t = 10; t < 300; t += 20) {
            double slope = (s.pos(t + 1) - s.pos(t - 1)) / 0.002;
            Assert.assertEquals("at " + t, slope, s.vel(t), Math.max(0.05, Math.abs(slope) * 0.02));
        }
    }

    @Test
    public void theControlPopPeaksAt112AndRestsAt108() {
        Assert.assertEquals(0f, Ui.CTRL_POP_CURVE.getInterpolation(0f), 1e-6);
        Assert.assertEquals(1f, Ui.CTRL_POP_CURVE.getInterpolation(1f), 1e-6);
        Assert.assertEquals(1.50f, Ui.CTRL_POP_CURVE.getInterpolation(4f / 11f), 1e-4);
        Assert.assertEquals("halfway between two samples", (0.27f + 0.77f) / 2, Ui.CTRL_POP_CURVE.getInterpolation(1.5f / 11f), 1e-4);
    }

    @Test
    public void heldKeysSpeedUpSidewaysOnly() {
        Assert.assertEquals(110, Ui.holdStep(0, false));
        Assert.assertEquals(80, Ui.holdStep(1500, false));
        Assert.assertEquals(60, Ui.holdStep(3500, false));
        Assert.assertEquals(160, Ui.holdStep(3500, true));
    }

    @Test
    public void fastRunsShortenFocus() {
        Assert.assertEquals(1f, Ui.speedIn(1000), 1e-6);
        Assert.assertEquals(0.3f, Ui.speedIn(100), 1e-6);
        Assert.assertEquals(0.5f, Ui.speedIn(450), 1e-6);
        Assert.assertEquals(0.45f, Ui.speedOut(100), 1e-6);
        Assert.assertEquals(1f, Ui.speedOut(450), 1e-6);
    }

    @Test
    public void scrollsTakeTimeByDistance() {
        // one 167 dp card pitch at 1080p (density 2, 320 dpi): about 80 ms, as Android TV's rows
        long ms = Ui.scrollFloorMs(334, 320, 1920);
        Assert.assertTrue("" + ms, ms >= 70 && ms <= 90);
        Assert.assertEquals(0, Ui.scrollFloorMs(0, 320, 1920));
    }
}
