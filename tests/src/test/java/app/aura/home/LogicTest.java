package app.aura.home;

import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = AuraApp.class)
public class LogicTest {
    @Test
    public void tasteLearns() {
        Context c = RuntimeEnvironment.getApplication();
        String kapil = Continue.key("The Great Indian Kapil Show");
        Taste.View v = Taste.view(c);
        assertFalse(v.skip(kapil));
        // shown on 13 visits: not tired yet; the 14th rests it
        for (int i = 0; i < 13; i++) Taste.shown(c, Collections.singleton(kapil));
        assertFalse(Taste.view(c).tired(kapil));
        Taste.shown(c, Collections.singleton(kapil));
        assertTrue(Taste.view(c).tired(kapil));
        // opening it makes it yours and lifts the tiredness
        Taste.opened(c, kapil);
        v = Taste.view(c);
        assertFalse(v.tired(kapil));
        assertTrue(v.yours(kapil));
        assertTrue(Taste.matches("The Great Indian Kapil Show: Season 3", v.openedTitles()));
        assertFalse(Taste.matches("Squid Game", v.openedTitles()));
        // turned down: skipped until forgiven
        int before = Taste.changes;
        Taste.decline(c, Continue.key("Squid Game"));
        assertTrue(Taste.changes > before);
        assertTrue(Taste.view(c).skip(Continue.key("Squid Game")));
        assertEquals(1, Taste.declinedCount(c));
        Taste.forgive(c);
        assertFalse(Taste.view(c).skip(Continue.key("Squid Game")));
        assertEquals(0, Taste.declinedCount(c));
        // apps are kept apart from titles
        Taste.opened(c, Taste.app("com.netflix.ninja"));
        assertFalse(Taste.view(c).openedTitles().contains("app:com.netflix.ninja"));
    }

    @Test
    public void troubleKeepsAndLimits() {
        Context c = RuntimeEnvironment.getApplication();
        Trouble.clear(c);
        assertEquals(0, Trouble.count(c));
        // the same error from the same place within the hour: kept once
        for (int i = 0; i < 2; i++) Trouble.caught("test", new IllegalStateException("one"));
        assertEquals(1, Trouble.count(c));
        Trouble.note("INSTALLER", "status 3 (cancelled)");
        assertEquals(2, Trouble.count(c));
        for (int i = 0; i < 40; i++) Trouble.note("N", "note " + i);
        assertEquals(30, Trouble.count(c));
        String r = Trouble.report(c);
        assertTrue(r.contains("Aura problem report"));
        assertTrue(r.contains("note 39"));
        assertFalse(r.contains("note 9\n"));
        Trouble.started(c, 2100, 800, 650, 42, 1200);
        assertTrue(Trouble.report(c).contains("home in 2100 ms"));
        Trouble.clear(c);
        assertEquals(0, Trouble.count(c));
    }

    @Test
    public void updaterVersions() {
        assertTrue(Updater.newer("7.6.1", "7.6"));
        assertTrue(Updater.newer("7.10", "7.9"));
        assertFalse(Updater.newer("7.6", "7.6.1"));
        assertFalse(Updater.newer("7.6.1", "7.6.1"));
    }

    @Test
    public void hevcFallbackAndProfileCache() {
        Context c = RuntimeEnvironment.getApplication();
        Aerials.Video v = Aerials.all(c).get(0);
        Aerials.disableHevc();
        assertFalse("once a 2K HEVC stream fails on a budget decoder, H.264 is used", v.usesHevc());
        assertEquals(v.url, v.stream());
        int before = Profiles.all(c).size();
        Profiles.Profile added = Profiles.add(c, "Test Room");
        assertEquals(before + 1, Profiles.all(c).size());
        Profiles.remove(c, added.id);
        assertEquals(before, Profiles.all(c).size());
    }

    @Test
    public void systemNoiseFilteredAndSleepCancelledOnManualScreenOff() throws Exception {
        Context c = RuntimeEnvironment.getApplication();
        assertTrue(Habits.isSystemNoise("com.amazon.venezia"));
        assertTrue(Habits.isSystemNoise("com.amazon.vizzini"));
        assertTrue(Habits.isSystemNoise("com.google.android.apps.tv.launcherx"));
        assertTrue(Habits.isSystemNoise("com.realtek.keyintercept"));
        assertFalse(Habits.isSystemNoise("com.netflix.ninja"));

        Habits.opened(c, "com.amazon.venezia");
        assertFalse("Fire OS background noise is excluded from recent apps",
                Habits.recentList(c, Collections.singleton("com.amazon.venezia"), 10).containsKey("com.amazon.venezia"));

        Power.setSleep(c, 30);
        assertTrue(Power.sleepLeftMs(c) >= 29 * 60_000L);
        Power.onScreenOff(c);
        assertEquals("manual screen-off cancels pending sleep timer", 0L, Power.sleepLeftMs(c));

        try (java.net.ServerSocket ss = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            int openPort = ss.getLocalPort();
            assertEquals(openPort, Adb.probeLocalPort(200, 1, openPort));
        }
        assertEquals(-1, Adb.probeLocalPort(80, 1));
    }
}
