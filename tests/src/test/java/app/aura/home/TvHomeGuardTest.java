package app.aura.home;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowPackageManager;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/**
 * Google TV's own home and Xiaomi's are never offered to be switched off (switched off, they have
 * left TVs black, restarting or needing a factory reset), only brought back where they're already
 * off; the stock Android TV home is still offered.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = AuraApp.class)
public class TvHomeGuardTest {
    private static final String XIAOMI = "com.mitv.tvhome";
    private Context c;

    @Before
    public void setUp() {
        c = RuntimeEnvironment.getApplication();
        // debugging never reachable: an emulator's own adbd may be listening on 127.0.0.1:5555
        Prefs.device(c).edit().clear().commit();
    }

    /** A system app answering Home, switched on or off. */
    private void systemHome(String pkg, String cls, boolean on) {
        ShadowPackageManager pm = shadowOf(c.getPackageManager());
        ApplicationInfo ai = new ApplicationInfo();
        ai.packageName = pkg;
        ai.flags = ApplicationInfo.FLAG_SYSTEM;
        ai.enabled = on;
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
    }

    @Test
    public void googleTvHomeIsNeverOffered() {
        systemHome(Safety.GOOGLE_TV_HOME, Safety.GOOGLE_TV_HOME + ".home.HomeActivity", true);
        assertNull(Safety.tvHome(c));
        assertFalse("neither the welcome nor Settings asks", Safety.tvHomeOn(c));
        assertEquals(Boolean.FALSE, Safety.retireTvHome(c, true));
    }

    @Test
    public void xiaomiHomeIsNeverOffered() {
        systemHome(XIAOMI, XIAOMI + ".HomeActivity", true);
        assertNull(Safety.tvHome(c));
        assertFalse(Safety.tvHomeOn(c));
        assertEquals(Boolean.FALSE, Safety.retireTvHome(c, true));
        // put back over ADB (Aura crashing, or leaving) with its name still remembered: still not offered
        Prefs.device(c).edit().putString("retired_home", XIAOMI).commit();
        assertNull(Safety.tvHome(c));
    }

    @Test
    public void stockAndroidTvHomeIsStillOffered() {
        systemHome("com.google.android.tvlauncher", "com.google.android.tvlauncher.MainActivity", true);
        assertEquals("com.google.android.tvlauncher", Safety.tvHome(c));
        assertTrue(Safety.tvHomeOn(c));
    }

    @Test
    public void googleTvHomeAlreadyOffIsOnlyBroughtBack() {
        systemHome(Safety.GOOGLE_TV_HOME, Safety.GOOGLE_TV_HOME + ".home.HomeActivity", false);
        assertEquals("Settings shows Bring back", Safety.GOOGLE_TV_HOME, Safety.tvHome(c));
        assertFalse(Safety.tvHomeOn(c));
        assertEquals("refused before debugging is asked", Boolean.FALSE, Safety.retireTvHome(c, true));
        assertNull("bringing it back goes to debugging, out of reach here", Safety.retireTvHome(c, false));
    }

    @Test
    public void xiaomiHomeAlreadyOffIsOnlyBroughtBack() {
        systemHome(XIAOMI, XIAOMI + ".HomeActivity", false);
        assertEquals(XIAOMI, Safety.tvHome(c));
        assertFalse(Safety.tvHomeOn(c));
        assertEquals(Boolean.FALSE, Safety.retireTvHome(c, true));
        assertNull(Safety.retireTvHome(c, false));
    }
}
