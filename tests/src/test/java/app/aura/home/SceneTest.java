package app.aura.home;

import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.List;
import java.util.Random;

import static org.junit.Assert.*;

/** Music scenes: Apple's aerial videos, which one plays, and the settings that keep them off until asked. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = AuraApp.class)
public class SceneTest {
    @Test
    public void theListIsAppleServerOnly() {
        List<Aerials.Video> all = Aerials.all(RuntimeEnvironment.getApplication());
        assertTrue("about a hundred videos: " + all.size(), all.size() > 100);
        for (Aerials.Video v : all) {
            assertTrue(v.url, v.url.startsWith("https://sylvan.apple.com/"));
            assertTrue(v.url, v.url.endsWith(".mov"));
            assertTrue("the HEVC copy is Apple's too: " + v.hevc, v.hevc == null || v.hevc.startsWith("https://sylvan.apple.com/"));
            assertFalse(v.label.isEmpty());
        }
    }

    @Test
    public void picksKeepToTheKindAndTheLight() {
        List<Aerials.Video> all = Aerials.all(RuntimeEnvironment.getApplication());
        Random rnd = new Random(7);
        for (String kind : Aerials.KINDS) {
            String last = null;
            for (int i = 0; i < 40; i++) {
                Aerials.Video v = Aerials.pick(all, kind, Sun.Phase.NIGHT, last, rnd);
                assertNotNull(kind, v);
                if (!kind.equals("mix")) assertEquals(kind, v.kind);
                assertNotEquals("never the one just shown", last, v.url);
                last = v.url;
            }
        }
        // after dark, a night flight whenever the kind has one
        for (int i = 0; i < 30; i++) assertEquals("night", Aerials.pick(all, "cityscape", Sun.Phase.NIGHT, null, rnd).time);
        for (int i = 0; i < 30; i++) assertEquals("day", Aerials.pick(all, "landscape", Sun.Phase.DAY, null, rnd).time);
    }

    @Test
    public void offUntilAskedFor() {
        Context c = RuntimeEnvironment.getApplication();
        assertFalse("scenes use the network: off until switched on", Prefs.on(c, Prefs.SCENES));
        assertFalse("the glow reads the TV's sound: off until switched on", Prefs.on(c, Prefs.BEAT));
        assertEquals("mix", Aerials.kind(c));
        // the screensaver streams only when asked: Auto keeps to photographs and quotes until aerials are let in
        assertFalse(Prefs.on(c, Prefs.AERIALS_AUTO));
        assertFalse(Prefs.aerialSaver(c, "auto"));
        assertFalse(Prefs.aerialSaver(c, "photos"));
        assertTrue(Prefs.aerialSaver(c, "aerials"));
        assertTrue(Prefs.aerialSaver(c, "aerial_sayings"));
        Prefs.set(c, Prefs.AERIALS_AUTO, true);
        assertTrue(Prefs.aerialSaver(c, "auto"));
        Prefs.set(c, Prefs.AERIALS_AUTO, false);
        Prefs.of(c).edit().putString(Prefs.SCENE_KIND, "nonsense").commit();
        assertEquals("an unknown kind falls back to all of them", "mix", Aerials.kind(c));
    }
}
