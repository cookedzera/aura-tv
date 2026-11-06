package app.aura.home;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ResolveInfo;
import android.provider.Settings;

import org.json.JSONObject;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowPackageManager;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.robolectric.Shadows.shadowOf;

/**
 * Tests for 7.7.1 HomeRole, Debloat, Fire TV Settings, Backup isolation, and Kids Profile security.
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30, application = AuraApp.class)
public class SystemFixesTest {
    private Context c;

    @SuppressWarnings("unchecked")
    private static <T> T field(Object o, String name) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(o);
    }

    @Before
    public void setUp() throws Exception {
        c = RuntimeEnvironment.getApplication();
        Prefs.device(c).edit().clear().putBoolean("welcomed", true).putString("set_light", "on").commit();
        Prefs.of(c).edit().clear().commit();
        Field w = HomeActivity.class.getDeclaredField("warm");
        w.setAccessible(true);
        w.setBoolean(null, true);
    }

    /** Finding M6: Once the competing stock launcher is disabled/removed, ignoresChoice() should clear even before Home is chosen in Android's ResolverActivity. */
    @Test
    public void ignoresChoiceClearsOnceCompetingLauncherIsRemoved() {
        Activity act = Robolectric.buildActivity(Activity.class).create().get();
        HomeRole.choiceIgnored(act);
        // No other launcher is registered in PackageManager (HomeRole.others(act) is empty),
        // and resolveActivity returns Android's ResolverActivity ("android") until the user picks Aura.
        ResolveInfo resolver = new ResolveInfo();
        resolver.activityInfo = new ActivityInfo();
        resolver.activityInfo.packageName = "android";
        resolver.activityInfo.name = "com.android.internal.app.ResolverActivity";
        shadowOf(act.getPackageManager()).addResolveInfoForIntent(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), resolver);

        Assert.assertTrue("No competing third-party or OEM launcher remains", HomeRole.others(act).isEmpty());
        Assert.assertFalse("ignoresChoice() should clear when no competing launcher exists", HomeRole.ignoresChoice(act));
    }

    /** Finding m2: Categories launch frequency/recency should be isolated per profile, not shared globally. */
    @Test
    public void categoriesUsageIsIsolatedPerProfile() {
        for (String pkg : new String[]{"com.example.a", "com.example.b"}) {
            ApplicationInfo ai = new ApplicationInfo();
            ai.packageName = pkg;
            ai.name = pkg;
            ai.nonLocalizedLabel = pkg;
            ai.category = ApplicationInfo.CATEGORY_VIDEO;
            PackageInfo pi = new PackageInfo();
            pi.packageName = pkg;
            pi.applicationInfo = ai;
            shadowOf(c.getPackageManager()).installPackage(pi);
        }
        Profiles.use(c, Profiles.MAIN);
        Categories.edit(c, "video", (cats, k) -> k.sort = "most");
        Categories.launched(c, "com.example.b");
        Categories.launched(c, "com.example.b");

        Profiles.Profile kid = Profiles.add(c, "Kid");
        Profiles.use(c, kid.id);
        Categories.edit(c, "video", (cats, k) -> k.sort = "most");
        List<String> installed = Arrays.asList("com.example.a", "com.example.b");
        List<String> order = Categories.members(c, Categories.find(Categories.all(c), "video"), installed, installed);
        Assert.assertEquals("New profile with no launches should keep installed order [a, b], not inherit Main profile's launch counts",
                Arrays.asList("com.example.a", "com.example.b"), order);
    }

    /** Findings m6 & S4: Backup.export() should exclude device-specific HomeRole flags and personal API keys. */
    @Test
    public void backupKeepsThisTvsHomeStateLocal() throws Exception {
        Prefs.device(c).edit()
                .putBoolean("home_choice_ignored", true)
                .putString("orig_home", "com.nvidia.shield.launcher/.Home")
                .putString("retired_home", "com.google.android.tvlauncher")
                .putStringSet("debloated", new java.util.HashSet<>(java.util.Arrays.asList("tv.anoki.acr.controller")))
                .commit();
        JSONObject root = new JSONObject(Backup.export(c));
        JSONObject aura = root.getJSONObject("files").getJSONObject("aura");
        // this TV's own home handling and switched-off apps stay with this TV
        for (String k : new String[]{"home_choice_ignored", "orig_home", "retired_home", "debloated"}) {
            Assert.assertFalse(k + " belongs to this TV and must not be exported", aura.has(k));
        }
    }

    /** Finding S1: Sending aura_welcome=true to HomeActivity while in a Kids profile must not open Welcome without PIN verification. */
    @Test
    public void kidsProfileBlocksUnauthenticatedWelcomeIntent() throws Exception {
        Profiles.setPin(c, "1234");
        Profiles.Profile kid = Profiles.add(c, "Kid");
        Profiles.setKids(c, kid.id, true);
        Profiles.use(c, kid.id);

        ActivityController<HomeActivity> ac = Robolectric.buildActivity(HomeActivity.class).setup();
        HomeActivity a = ac.get();
        a.onNewIntent(new Intent(c, HomeActivity.class).putExtra("aura_welcome", true));
        ShadowLooper.idleMainLooper(600, TimeUnit.MILLISECONDS);
        Object welcome = field(a, "welcome");
        Assert.assertNull("Kids profile must not open Welcome wizard from unauthenticated aura_welcome intent", welcome);
    }

    /** Finding M11: On Fire TV, if a Settings intent resolves to an activity guarded by an internal Amazon permission, fireSettings() should fall back to MainSettingsActivity. */
    @Test
    public void fireSettingsFallsBackWhenTargetRequiresInternalPermission() {
        ShadowPackageManager pm = shadowOf(c.getPackageManager());
        pm.setSystemFeature("amazon.hardware.fire_tv", true);

        ComponentName locked = new ComponentName("com.amazon.tv.settings.v2",
                "com.amazon.tv.settings.v2.tv.device.DeveloperOptionsActivity");
        ActivityInfo ai = pm.addActivityIfNotPresent(locked);
        ai.permission = "com.amazon.tv.permission.INTERNAL_SETTINGS";
        IntentFilter f = new IntentFilter(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
        f.addCategory(Intent.CATEGORY_DEFAULT);
        pm.addIntentFilterForActivity(locked, f);

        ComponentName amazonMain = new ComponentName("com.amazon.tv.launcher",
                "com.amazon.tv.launcher.ui.MainSettingsActivity");
        pm.addActivityIfNotPresent(amazonMain);

        Intent out = HomeActivity.fireSettings(c, new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
        Assert.assertEquals("Should redirect to Amazon MainSettingsActivity when target requires an unheld permission",
                amazonMain, out.getComponent());
    }

    /** Finding M11b: On Fire TV, CTSDummyIntentHandler is Amazon's real trampoline for ACTION_ACCESSIBILITY_SETTINGS, whereas com.amazon.tv.launcher.ui.SettingsActivity bounces ACTION_SETTINGS to Amazon Home. */
    @Test
    public void fireSettingsKeepsAccessibilityTrampolineAndRedirectsLauncherSettingsBounce() {
        ShadowPackageManager pm = shadowOf(c.getPackageManager());
        pm.setSystemFeature("amazon.hardware.fire_tv", true);

        ComponentName a11yTrampoline = new ComponentName("com.amazon.tv.settings.v2",
                "com.amazon.tv.settings.v2.system.CTSDummyIntentHandler");
        pm.addActivityIfNotPresent(a11yTrampoline);
        IntentFilter a11yFilter = new IntentFilter(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        a11yFilter.addCategory(Intent.CATEGORY_DEFAULT);
        pm.addIntentFilterForActivity(a11yTrampoline, a11yFilter);

        ComponentName bounceSettings = new ComponentName("com.amazon.tv.launcher",
                "com.amazon.tv.launcher.ui.SettingsActivity");
        pm.addActivityIfNotPresent(bounceSettings);
        IntentFilter settingsFilter = new IntentFilter(Settings.ACTION_SETTINGS);
        settingsFilter.addCategory(Intent.CATEGORY_DEFAULT);
        pm.addIntentFilterForActivity(bounceSettings, settingsFilter);

        ComponentName amazonMain = new ComponentName("com.amazon.tv.launcher",
                "com.amazon.tv.launcher.ui.MainSettingsActivity");
        pm.addActivityIfNotPresent(amazonMain);

        Intent a11yOut = HomeActivity.fireSettings(c, new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        Assert.assertEquals("CTSDummyIntentHandler launches AccessibilityActivity internally on Fire TV and must not be replaced with MainSettingsActivity",
                Settings.ACTION_ACCESSIBILITY_SETTINGS, a11yOut.getAction());

        Intent rootOut = HomeActivity.fireSettings(c, new Intent(Settings.ACTION_SETTINGS));
        Assert.assertEquals("com.amazon.tv.launcher.ui.SettingsActivity bounces to Amazon Home and must be replaced with MainSettingsActivity",
                amazonMain, rootOut.getComponent());
    }

    /** Finding M12: Fire OS 7 and 8 (SDK 28-30) store devOptionsEnabled in com.amazon.tv.settings.v2 private prefs and never set Settings.Global.DEVELOPMENT_SETTINGS_ENABLED=1. */
    @Test
    public void fireTvDevOptionsOnRecognizesFireOs7And8() {
        ShadowPackageManager pm = shadowOf(c.getPackageManager());
        pm.setSystemFeature("amazon.hardware.fire_tv", true);
        Settings.Global.putInt(c.getContentResolver(), Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0);
        Settings.Global.putInt(c.getContentResolver(), Settings.Global.ADB_ENABLED, 0);

        Assert.assertTrue("Fire OS 7/8 never writes DEVELOPMENT_SETTINGS_ENABLED=1; devOptionsOn must not stay false and trap Settings -> Developer options in About",
                Adb.devOptionsOn(c));
    }

    /** Finding M14: On Android TV 5.1-9 (SDK < 29), com.android.tv.settings maps ACTION_HOME_SETTINGS to EmptyStubActivity (which immediately finishes). HomeRole must not treat EmptyStubActivity as a real Home settings UI. */
    @Test
    @Config(sdk = 28, application = AuraApp.class)
    public void homeRoleDoesNotTreatAndroidTvEmptyStubActivityAsRealSettingsPage() {
        ShadowPackageManager pm = shadowOf(c.getPackageManager());
        ComponentName emptyStub = new ComponentName("com.android.tv.settings",
                "com.android.tv.settings.EmptyStubActivity");
        pm.addActivityIfNotPresent(emptyStub);
        IntentFilter homeSettingsFilter = new IntentFilter(Settings.ACTION_HOME_SETTINGS);
        homeSettingsFilter.addCategory(Intent.CATEGORY_DEFAULT);
        pm.addIntentFilterForActivity(emptyStub, homeSettingsFilter);

        Assert.assertFalse("com.android.tv.settings.EmptyStubActivity immediately finishes in onCreate and is not a real settings page",
                HomeRole.real(c, new Intent(Settings.ACTION_HOME_SETTINGS)));
        Activity act = Robolectric.buildActivity(Activity.class).create().get();
        Assert.assertFalse("HomeRole.ask() must not launch EmptyStubActivity and claim a Home picker opened",
                HomeRole.ask(act));
    }
}
