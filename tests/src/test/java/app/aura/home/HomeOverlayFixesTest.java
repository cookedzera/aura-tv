package app.aura.home;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.graphics.drawable.ColorDrawable;
import android.view.KeyEvent;
import android.widget.TextView;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowPackageManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.robolectric.Shadows.shadowOf;

/**
 * Panels over home: Who's watching closes with Home and makes way for whatever opens next, and
 * home never drifts into photo mode beneath it; Control Center over an app goes back to the app
 * whichever key puts it away; the TV's inputs are read off the main thread; "Couldn't open" in the
 * TV's language; the saved app list arrives with each app's kind known.
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30, application = AuraApp.class, qualifiers = "w960dp-h540dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class HomeOverlayFixesTest {
    private Context c;

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

    private static Object call(Object o, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = o.getClass().getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(o, args);
    }

    private static void key(HomeActivity a, int code) {
        a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
        a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
    }

    @Before
    public void setUp() throws Exception {
        c = RuntimeEnvironment.getApplication();
        Prefs.device(c).edit().clear().putBoolean("welcomed", true).putString("set_light", "on").commit();
        org.robolectric.shadows.ShadowChoreographer.setPaused(true);
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(Duration.ofMillis(16));
        Field w = HomeActivity.class.getDeclaredField("warm");
        w.setAccessible(true);
        w.setBoolean(null, true);
    }

    /** Home up with the remote's focus, and the real list of apps in: nothing replaces what a test puts on home. */
    private ActivityController<HomeActivity> start() throws Exception {
        ActivityController<HomeActivity> ac = Robolectric.buildActivity(HomeActivity.class).setup();
        // with frames paused, the window attaches on the first frame: focus can only be given after it
        run(50);
        ac.windowFocusChanged(true);
        HomeActivity a = ac.get();
        for (int i = 0; i < 300 && !(Boolean) field(a, "appsRead"); i++) {
            run(50);
            Thread.sleep(20);
        }
        Assert.assertTrue("the real list of apps was read", (Boolean) field(a, "appsRead"));
        run(1_000);
        return ac;
    }

    @Test
    public void whosWatchingKeepsHomeAwakeAndHomeClosesIt() throws Exception {
        ActivityController<HomeActivity> ac = start();
        HomeActivity a = ac.get();
        ProfilePicker who = field(a, "who");
        call(a, "openWho", new Class<?>[]{});
        run(500);
        Assert.assertTrue(who.isOpen());
        Assert.assertTrue("Who's watching counts as a panel over home", (Boolean) call(a, "overlayOpen", new Class<?>[]{}));
        // a press over it, then the remote left alone past the photograph's 45 s
        key(a, KeyEvent.KEYCODE_DPAD_RIGHT);
        run(46_000);
        Assert.assertFalse("no photo mode beneath it: the next press would only wake the photograph", (Boolean) field(a, "photo"));
        Assert.assertTrue(who.isOpen());
        // Home means home
        a.onNewIntent(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME));
        run(500);
        Assert.assertFalse("Home closes it", who.isOpen());
        ac.pause().stop().destroy();
    }

    @Test
    public void recentsClosesWhosWatchingFirst() throws Exception {
        ActivityController<HomeActivity> ac = start();
        HomeActivity a = ac.get();
        ProfilePicker who = field(a, "who");
        call(a, "openWho", new Class<?>[]{});
        run(500);
        Assert.assertTrue(who.isOpen());
        // an app, and the recent apps already read: the switcher opens at once
        AppRepo.App x = new AppRepo.App();
        x.pkg = "com.example.player";
        x.label = "Player";
        x.icon = new ColorDrawable(0xFF446688);
        x.color = 0xFF446688;
        List<AppRepo.App> apps = new ArrayList<>();
        apps.add(x);
        set(a, "apps", apps);
        LinkedHashMap<String, Long> recent = new LinkedHashMap<>();
        recent.put(x.pkg, System.currentTimeMillis());
        set(a, "recentCache", recent);
        // the remote's Recents key, as the Home button service sends it
        a.onNewIntent(new Intent(Intent.ACTION_MAIN).putExtra("aura_recents", ""));
        Switcher s = field(a, "switcher");
        Assert.assertTrue(s.isOpen());
        Assert.assertFalse("the switcher doesn't open beneath it: it closes first", who.isOpen());
        run(800);
        ac.pause().stop().destroy();
    }

    @Test
    public void theProfileButtonClosesWhatIsOpenFirst() throws Exception {
        ActivityController<HomeActivity> ac = start();
        HomeActivity a = ac.get();
        ProfilePicker who = field(a, "who");
        Find find = field(a, "findPanel");
        SettingsPanel settings = field(a, "settings");

        // finding an app, then the profile button as the Home button service sends it
        call(a, "openFind", new Class<?>[]{String.class}, "");
        run(500);
        Assert.assertTrue(find.isOpen());
        a.onNewIntent(new Intent(Intent.ACTION_MAIN).putExtra("aura_profiles", ""));
        run(500);
        Assert.assertTrue(who.isOpen());
        Assert.assertFalse("Find doesn't stay open beneath it, taking the keys", find.isOpen());
        key(a, KeyEvent.KEYCODE_BACK);
        run(500);
        Assert.assertFalse("Back closes it", who.isOpen());
        Assert.assertFalse(find.isOpen());

        // Settings open, and the remote's own profile key reaching home
        call(a, "openSettings", new Class<?>[]{});
        run(500);
        Assert.assertTrue(settings.isOpen());
        key(a, HomeKeys.KEYCODE_PROFILE_SWITCH);
        run(500);
        Assert.assertTrue(who.isOpen());
        Assert.assertFalse("Settings closes beneath it", settings.isOpen());

        // typing from the phone opens Find: Who's watching makes way
        SettingsPanel.Host host = field(settings, "host");
        Assert.assertTrue(host.type("n"));
        run(500);
        Assert.assertTrue(find.isOpen());
        Assert.assertFalse(who.isOpen());
        ac.pause().stop().destroy();
    }

    @Test
    public void controlCenterOverAnAppGoesBackToItWithMenu() throws Exception {
        ShadowPackageManager pm = shadowOf(c.getPackageManager());
        ComponentName cn = new ComponentName("com.example.player", "com.example.player.Main");
        pm.addActivityIfNotPresent(cn);
        IntentFilter f = new IntentFilter(Intent.ACTION_MAIN);
        f.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        f.addCategory(Intent.CATEGORY_LAUNCHER);
        pm.addIntentFilterForActivity(cn, f);
        ActivityController<HomeActivity> ac = start();
        HomeActivity a = ac.get();
        // Home held inside the app: Control Center over it
        a.onNewIntent(new Intent(Intent.ACTION_MAIN).putExtra("aura_cc", "com.example.player"));
        run(500);
        Assert.assertTrue((Boolean) field(a, "ccOpen"));
        Assert.assertEquals("com.example.player", field(a, "ccOver"));
        while (shadowOf(a).getNextStartedActivity() != null) {
            // whatever home started before this
        }
        key(a, KeyEvent.KEYCODE_MENU);
        Intent back = shadowOf(a).getNextStartedActivity();
        Assert.assertNotNull("Menu puts Control Center away as Back does: the app comes back", back);
        Assert.assertEquals("com.example.player", back.getComponent().getPackageName());
        run(600);
        Assert.assertFalse((Boolean) field(a, "ccOpen"));
        ac.pause().stop().destroy();
    }

    @Test
    public void inputsAreReadOffTheMainThreadAndALateAnswerIsDropped() throws Exception {
        ActivityController<HomeActivity> ac = start();
        HomeActivity a = ac.get();
        ExecutorService worker = field(a, "worker");
        List<String[]> live = field(a, "live");
        // a source on show that the input service no longer reports
        live.add(new String[]{"hdmi1", "HDMI 1"});
        call(a, "refreshLive", new Class<?>[]{boolean.class}, false);
        Assert.assertEquals("nothing read or changed on the main thread", 1, live.size());
        // the inputs read again in place (a setting changed them) before that answer arrives: it's dropped
        set(a, "liveGen", (Integer) field(a, "liveGen") + 1);
        worker.submit(() -> { }).get(30, TimeUnit.SECONDS);
        run(100);
        Assert.assertEquals(1, live.size());
        // otherwise the answer is applied on the main thread, and the source goes
        call(a, "refreshLive", new Class<?>[]{boolean.class}, false);
        worker.submit(() -> { }).get(30, TimeUnit.SECONDS);
        run(100);
        Assert.assertEquals(0, live.size());
        ac.pause().stop().destroy();
    }

    @Test
    public void somethingWithNoNameThatWontOpenIsSaidInTheTvsLanguage() throws Exception {
        ActivityController<HomeActivity> ac = start();
        HomeActivity a = ac.get();
        // Control Center's and Settings' links (a settings page this TV doesn't have, say)
        shadowOf(RuntimeEnvironment.getApplication()).checkActivities(true);
        try {
            a.open(new Intent("app.aura.test.NOWHERE"), a.getWindow().getDecorView());
        } finally {
            shadowOf(RuntimeEnvironment.getApplication()).checkActivities(false);
        }
        TextView hud = field(a, "hudText");
        Assert.assertEquals(a.getString(R.string.couldnt_open_that), hud.getText().toString());
        ac.pause().stop().destroy();
    }

    @Test
    @SuppressWarnings("unchecked")
    public void theSavedListKnowsWhatEachAppIsBeforeHomeLaysItOut() throws Exception {
        ApplicationInfo ai = new ApplicationInfo();
        ai.packageName = "com.example.film";
        ai.category = ApplicationInfo.CATEGORY_VIDEO;
        PackageInfo pi = new PackageInfo();
        pi.packageName = ai.packageName;
        pi.applicationInfo = ai;
        shadowOf(c.getPackageManager()).installPackage(pi);
        AppRepo.App x = new AppRepo.App();
        x.pkg = ai.packageName;
        x.label = "Film";
        x.cls = x.pkg + ".Main";
        x.category = Intent.CATEGORY_LEANBACK_LAUNCHER;
        x.src = "/data/app/" + x.pkg + "-1/base.apk";
        x.color = 0xFF3366CC;
        x.icon = new ColorDrawable(0xFF22AA66);
        List<AppRepo.App> list = new ArrayList<>();
        list.add(x);
        AppRepo.save(c, list);
        Field k = Categories.class.getDeclaredField("kinds");
        k.setAccessible(true);
        Map<String, String> kinds = (Map<String, String>) k.get(null);
        synchronized (Categories.class) {
            kinds.remove(x.pkg);
        }
        // read back on its own thread at a start; the rows laid out from it (on the main thread)
        // then never ask Android what each app is
        Assert.assertNotNull(AppRepo.saved(c));
        synchronized (Categories.class) {
            Assert.assertEquals("video", kinds.get(x.pkg));
        }
    }
}
