package app.aura.home;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.provider.Settings;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowPackageManager;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/**
 * Fire TV answers Android's settings links with a stand-in that bounces to Amazon's home. The
 * welcome's setup buttons (About, Developer options) went there straight, so a Fire TV owner
 * following the steps landed back on Amazon's home every time. They go to Amazon's own settings.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 25, application = AuraApp.class)
public class FireSettingsTest {
    private static final ComponentName STAND_IN = new ComponentName("com.amazon.tv.settings.v2",
            "com.amazon.tv.settings.v2.tv.compliance.CTSDummySettingsHandler");
    private static final ComponentName AMAZON = new ComponentName("com.amazon.tv.launcher",
            "com.amazon.tv.launcher.ui.MainSettingsActivity");

    private Context fireTv() {
        Context c = RuntimeEnvironment.getApplication();
        ShadowPackageManager pm = shadowOf(c.getPackageManager());
        pm.setSystemFeature("amazon.hardware.fire_tv", true);
        pm.addActivityIfNotPresent(STAND_IN);
        for (String action : new String[]{Settings.ACTION_DEVICE_INFO_SETTINGS, Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS}) {
            IntentFilter f = new IntentFilter(action);
            f.addCategory(Intent.CATEGORY_DEFAULT);
            pm.addIntentFilterForActivity(STAND_IN, f);
        }
        pm.addActivityIfNotPresent(AMAZON);
        return c;
    }

    @Test
    public void setupStepsOpenAmazonsSettingsNotTheStandIn() throws Exception {
        Context c = fireTv();
        for (String action : new String[]{Settings.ACTION_DEVICE_INFO_SETTINGS, Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS}) {
            Intent sent = HomeActivity.fireSettings(c, new Intent(action));
            assertEquals(action, AMAZON, sent.getComponent());
        }
    }

    @Test
    public void elsewhereTheLinkIsLeftAlone() {
        Context c = RuntimeEnvironment.getApplication();
        Intent wanted = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
        assertSame(wanted, HomeActivity.fireSettings(c, wanted));
    }

    @Test
    public void amazonsXRayIsOnlyThereWhenItIs() {
        Context c = RuntimeEnvironment.getApplication();
        Intent xray = new Intent().setClassName("com.amazon.ssm", "com.amazon.ssm.ControlPanel");
        assertNotNull("Intent.resolveActivity hands a named component back unchecked", xray.resolveActivity(c.getPackageManager()));
        assertNull("so it's asked of the package manager", c.getPackageManager().resolveActivity(xray, 0));
    }
}
