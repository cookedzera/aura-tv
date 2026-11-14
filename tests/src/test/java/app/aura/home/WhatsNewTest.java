package app.aura.home;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.view.KeyEvent;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;

import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;

/**
 * What's new comes once, to someone who has just updated: never on a fresh install (the welcome is
 * its introduction), never a second time, and not in a kids' profile.
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30, application = AuraApp.class, qualifiers = "w960dp-h540dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class WhatsNewTest {
    private Context c;

    private static void run(long ms) {
        for (long t = 0; t < ms; t += 16) ShadowLooper.idleMainLooper(16, TimeUnit.MILLISECONDS);
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object o, String name) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(o);
    }

    /** Aura as Android has it: first installed at {@code first}, last installed (or updated) at {@code last}. */
    private void installed(long first, long last) {
        PackageInfo p = Shadows.shadowOf(c.getPackageManager()).getInternalMutablePackageInfo(c.getPackageName());
        p.firstInstallTime = first;
        p.lastUpdateTime = last;
    }

    @Before
    public void setUp() throws Exception {
        c = RuntimeEnvironment.getApplication();
        Profiles.forget();
        Profiles.use(c, Profiles.MAIN);
        Prefs.of(c).edit().clear().putBoolean(Prefs.WATCH, true).commit();
        Prefs.device(c).edit().putBoolean("welcomed", true).putString("set_light", "on").commit();
        Field w = HomeActivity.class.getDeclaredField("warm");
        w.setAccessible(true);
        w.setBoolean(null, true);
    }

    @Test
    public void aFreshInstallIsNeverShownIt() {
        installed(1_000, 1_000);
        Assert.assertFalse(WhatsNew.due(c));
        Assert.assertEquals("and it counts as seen from there", WhatsNew.EDITION, Prefs.device(c).getInt(WhatsNew.SEEN, 0));
        // updated later with nothing new to show: still nothing
        installed(1_000, 9_000);
        Assert.assertFalse(WhatsNew.due(c));
    }

    @Test
    public void anUpdateShowsItOnceThenNeverAgain() throws Exception {
        installed(1_000, 9_000);
        Assert.assertTrue(WhatsNew.due(c));
        HomeActivity a = Robolectric.buildActivity(HomeActivity.class).setup().get();
        run(4_500);
        WhatsNew news = field(a, "news");
        Assert.assertNotNull("home at rest after an update: What's new is up", news);
        Assert.assertTrue(news.isOpen());
        Assert.assertNotNull(news.getParent());
        Assert.assertFalse("seen the moment it's on screen, however it's left", WhatsNew.due(c));
        // Watch is on: Open Watch has the focus, Continue is to its right, and the remote goes nowhere else
        Pad open = field(news, "open"), go = field(news, "go");
        Assert.assertTrue("the focus is on " + a.getCurrentFocus(), open.isFocused());
        Assert.assertEquals(go.getId(), open.getNextFocusRightId());
        Assert.assertEquals(open.getId(), go.getNextFocusLeftId());
        Assert.assertEquals(open.getId(), open.getNextFocusDownId());

        // Back puts it away: out of the window and forgotten
        a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK));
        a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK));
        run(400);
        Assert.assertNull(WhatsNewTest.<Object>field(a, "news"));
        Assert.assertNull(news.getParent());
        Assert.assertFalse(news.isOpen());

        // home started again: it doesn't come back
        HomeActivity b = Robolectric.buildActivity(HomeActivity.class).setup().get();
        run(4_500);
        Assert.assertNull(WhatsNewTest.<Object>field(b, "news"));
    }

    @Test
    public void openWatchGoesStraightToWatch() throws Exception {
        installed(1_000, 9_000);
        HomeActivity a = Robolectric.buildActivity(HomeActivity.class).setup().get();
        run(4_500);
        WhatsNew news = field(a, "news");
        Assert.assertNotNull(news);
        Pad open = field(news, "open");
        open.onClick.run();
        run(600);
        Assert.assertNull(WhatsNewTest.<Object>field(a, "news"));
        WatchScreen watch = field(a, "watch");
        Assert.assertNotNull("Open Watch opens it", watch);
        Assert.assertTrue(watch.isOpen());
    }

    @Test
    public void withWatchOffThereIsOnlyContinue() throws Exception {
        installed(1_000, 9_000);
        Prefs.set(c, Prefs.WATCH, false);
        HomeActivity a = Robolectric.buildActivity(HomeActivity.class).setup().get();
        run(4_500);
        WhatsNew news = field(a, "news");
        Assert.assertNotNull(news);
        Pad open = field(news, "open"), go = field(news, "go");
        Assert.assertNull("no Open Watch without Watch", open.getParent());
        Assert.assertTrue("the focus is on " + a.getCurrentFocus(), go.isFocused());
        go.onClick.run();
        run(400);
        Assert.assertNull(WhatsNewTest.<Object>field(a, "news"));
    }

    @Test
    public void aKidsProfileWaitsForTheGrownUps() {
        installed(1_000, 9_000);
        Profiles.Profile kids = Profiles.add(c, "Kids");
        Profiles.setKids(c, kids.id, true);
        Profiles.use(c, kids.id);
        Assert.assertFalse(WhatsNew.due(c));
        Assert.assertEquals("it isn't counted as seen there", 0, Prefs.device(c).getInt(WhatsNew.SEEN, 0));
        Profiles.use(c, Profiles.MAIN);
        Assert.assertTrue(WhatsNew.due(c));
    }
}
