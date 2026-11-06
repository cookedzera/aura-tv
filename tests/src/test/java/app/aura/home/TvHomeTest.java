package app.aura.home;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowPackageManager;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/**
 * Which of the TV's own home screens Aura offers to switch off: Google TV's, any other system home
 * (stock Android TV's, a maker's), and never Fire TV's, whose home is also where its settings live.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = AuraApp.class)
public class TvHomeTest {
    private Context systemHome(String pkg, String cls) {
        Context c = RuntimeEnvironment.getApplication();
        ShadowPackageManager pm = shadowOf(c.getPackageManager());
        ApplicationInfo ai = new ApplicationInfo();
        ai.packageName = pkg;
        ai.flags = ApplicationInfo.FLAG_SYSTEM;
        ai.enabled = true;
        PackageInfo pi = new PackageInfo();
        pi.packageName = pkg;
        pi.applicationInfo = ai;
        pm.installPackage(pi);
        ComponentName home = new ComponentName(pkg, cls);
        pm.addActivityIfNotPresent(home);
        IntentFilter f = new IntentFilter(Intent.ACTION_MAIN);
        f.addCategory(Intent.CATEGORY_HOME);
        f.addCategory(Intent.CATEGORY_DEFAULT);
        pm.addIntentFilterForActivity(home, f);
        return c;
    }

    @Test
    public void stockAndroidTvHomeIsOffered() {
        Context c = systemHome("com.google.android.tvlauncher", "com.google.android.tvlauncher.MainActivity");
        assertEquals("com.google.android.tvlauncher", Safety.tvHome(c));
        assertTrue(Safety.tvHomeOn(c));
    }

    @Test
    public void googleTvHomeIsOfferedFirst() {
        Context c = systemHome("com.google.android.apps.tv.launcherx", "com.google.android.apps.tv.launcherx.home.HomeActivity");
        assertEquals(Safety.GOOGLE_TV_HOME, Safety.tvHome(c));
    }

    @Test
    public void neverOnFireTv() {
        Context c = systemHome("com.amazon.tv.launcher", "com.amazon.tv.launcher.ui.HomeActivity_vNext");
        shadowOf(c.getPackageManager()).setSystemFeature("amazon.hardware.fire_tv", true);
        assertNull("Amazon's home holds Fire TV's settings", Safety.tvHome(c));
        assertFalse(Safety.tvHomeOn(c));
    }
}
