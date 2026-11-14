package app.aura.home;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.SystemClock;
import android.view.View;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

import static org.robolectric.Shadows.shadowOf;

/**
 * A kids' profile stays a kids' profile: the parents' PIN unlocks Settings only while it's open,
 * a backup carries the PIN with the profiles it guards, and nothing on the home screen opens what
 * the parents hid (YouTube through a trailer, every app through the TV's own search).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = AuraApp.class)
public class KidsGuardTest {
    private static final String YT = "com.google.android.youtube.tv";
    private Context c;
    private ActivityController<HomeActivity> ac;

    @Before
    public void setUp() throws Exception {
        c = RuntimeEnvironment.getApplication();
        Profiles.forget();
        Profiles.use(c, Profiles.MAIN);
        Prefs.device(c).edit().putBoolean("welcomed", true).putString("set_light", "on").commit();
        Field w = HomeActivity.class.getDeclaredField("warm");
        w.setAccessible(true);
        w.setBoolean(null, true);
    }

    @After
    public void tearDown() {
        if (ac != null) ac.pause().stop().destroy();
        c.getSharedPreferences("aura", Context.MODE_PRIVATE).edit().remove(Profiles.PIN).commit();
        Profiles.forget();
        Profiles.use(c, Profiles.MAIN);
    }

    /** A kids' profile with the parents' PIN set, in use. */
    private String kids() {
        Profiles.Profile p = Profiles.add(c, "Kid");
        Profiles.setKids(c, p.id, true);
        Profiles.setPin(c, "1234");
        Profiles.forget();
        Profiles.use(c, p.id);
        return p.id;
    }

    private static void run(long ms) {
        for (long t = 0; t < ms; t += 50) ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS);
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object o, String name) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(o);
    }

    private static void set(Object o, String name, Object v) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(o, v);
    }

    private static void call(Object o, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = o.getClass().getDeclaredMethod(name, types);
        m.setAccessible(true);
        m.invoke(o, args);
    }

    private HomeActivity start() {
        ac = Robolectric.buildActivity(HomeActivity.class).setup();
        ac.windowFocusChanged(true);
        run(2500);
        return ac.get();
    }

    @Test
    public void thePinUnlocksSettingsOnlyWhileItsOpen() throws Exception {
        kids();
        HomeActivity a = start();
        SettingsPanel settings = field(a, "settings");

        // the PIN just typed: Settings opens without asking again
        set(a, "unlockedUntil", SystemClock.uptimeMillis() + 10 * 60_000L);
        call(a, "openSettings", new Class<?>[]{});
        run(500);
        Assert.assertTrue(settings.isOpen());

        // closed, it's locked: opening it again asks for the PIN rather than opening
        call(a, "closeSettings", new Class<?>[]{});
        run(500);
        Assert.assertEquals(0L, (long) (Long) field(a, "unlockedUntil"));
        call(a, "openSettings", new Class<?>[]{});
        run(500);
        Assert.assertFalse("Settings waits for the PIN again", settings.isOpen());
    }

    @Test
    public void leavingAuraLocksAKidsProfile() throws Exception {
        kids();
        HomeActivity a = start();
        // unlocked for an errand in the TV's own settings (Control Center's Picture, say)
        set(a, "unlockedUntil", SystemClock.uptimeMillis() + 10 * 60_000L);
        ac.pause().stop();
        Assert.assertEquals("back from that app, the profile is locked", 0L, (long) (Long) field(a, "unlockedUntil"));
        ac.start().resume();
    }

    @Test
    public void aBackupCarriesTheParentsPinAndAnOldOneNeverTakesItAway() throws Exception {
        String id = kids();
        Profiles.use(c, Profiles.MAIN);
        String backup = Backup.export(c);

        // onto another TV, with no PIN yet: the kids' profile arrives locked by the same PIN
        c.getSharedPreferences("aura", Context.MODE_PRIVATE).edit().remove(Profiles.PIN).commit();
        Assert.assertFalse(Profiles.hasPin(c));
        Backup.restore(c, backup);
        Assert.assertTrue(Profiles.hasPin(c));
        Assert.assertTrue(Profiles.pinOk(c, "1234"));

        // a backup made before there was a PIN leaves the one this TV has
        JSONObject old = new JSONObject(backup);
        old.getJSONObject("files").getJSONObject("aura").remove(Profiles.PIN);
        Profiles.setPin(c, "4321");
        Backup.restore(c, old.toString());
        Assert.assertTrue("the TV's PIN stays", Profiles.pinOk(c, "4321"));
        Profiles.forget();
        Profiles.use(c, id);
        Assert.assertTrue(Profiles.kids(c));
    }

    @Test
    public void tonightsPickIsntOfferedInAKidsProfile() {
        Prefs.set(c, Prefs.PICKS, true);
        Assert.assertTrue(Prefs.picks(c));
        kids();
        Prefs.set(c, Prefs.PICKS, true);
        Assert.assertFalse("as with Watch, nothing vouches for what a pick is", Prefs.picks(c));
    }

    @Test
    public void aTrailerNeverOpensAnAppTheProfileHides() throws Exception {
        Picks.Pick p = new Picks.Pick(new JSONObject().put("name", "A film").put("query", "A film").put("yt", "abc"));
        Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=abc")).setPackage(YT);
        ResolveInfo ri = new ResolveInfo();
        ri.activityInfo = new ActivityInfo();
        ri.activityInfo.packageName = YT;
        ri.activityInfo.name = YT + ".Main";
        shadowOf(c.getPackageManager()).addResolveInfoForIntent(view, ri);

        Assert.assertEquals(YT, Picks.watch(c, p).getPackage());
        kids();
        Prefs.hide(c, YT, true);
        Intent i = Picks.watch(c, p);
        Assert.assertNotEquals("YouTube is hidden in this profile", YT, i.getPackage());
        Assert.assertTrue(i.getComponent() == null || !YT.equals(i.getComponent().getPackageName()));
    }

    /** Find opened with something typed that no app is called: is "Search everywhere" offered? */
    private int everywhereRow() throws Exception {
        HomeActivity a = start();
        call(a, "openFind", new Class<?>[]{String.class}, "zzz");
        run(300);
        View row = field(field(a, "findPanel"), "everywhereRow");
        return row.getVisibility();
    }

    @Test
    public void searchEverywhereIsntOfferedInAKidsProfile() throws Exception {
        kids();
        Assert.assertEquals("the TV's search reaches the hidden apps too", View.GONE, everywhereRow());
    }

    @Test
    public void searchEverywhereStaysForGrownUps() throws Exception {
        Assert.assertEquals(View.VISIBLE, everywhereRow());
    }
}
