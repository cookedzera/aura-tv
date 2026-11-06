package app.aura.home;

import android.app.Activity;
import android.app.role.RoleManager;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowRoleManager;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/**
 * A TV that says yes and still opens its own launcher on Home (an Nvidia Shield): Android counts
 * Aura as the home app, so asking again gets an instant yes with nothing on screen. Asking on every
 * yes went round and round, the Home button row flickering and the remote crawling. Aura asks once
 * and then hands Home to its Home button service.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = AuraApp.class)
public class HomeChoiceTest {
    private static final String REQUEST_ROLE = "android.app.role.action.REQUEST_ROLE";
    private Activity act;

    @Before
    public void otherLauncherHasHome() {
        act = Robolectric.buildActivity(Activity.class).create().get();
        ResolveInfo other = new ResolveInfo();
        other.activityInfo = new ActivityInfo();
        other.activityInfo.packageName = "com.nvidia.shield.launcher";
        other.activityInfo.name = "com.nvidia.shield.launcher.Home";
        other.activityInfo.applicationInfo = new ApplicationInfo();
        other.activityInfo.applicationInfo.packageName = other.activityInfo.packageName;
        other.activityInfo.applicationInfo.flags = ApplicationInfo.FLAG_SYSTEM;
        shadowOf(act.getPackageManager()).addResolveInfoForIntent(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), other);
        roles().addAvailableRole(RoleManager.ROLE_HOME);
    }

    private ShadowRoleManager roles() {
        return org.robolectric.shadow.api.Shadow.extract(act.getSystemService(RoleManager.class));
    }

    private boolean asked() {
        for (Intent i; (i = shadowOf(act).getNextStartedActivity()) != null; ) {
            if (REQUEST_ROLE.equals(i.getAction())) return true;
        }
        return false;
    }

    @Test
    public void aTvThatHasntSaidYesIsAsked() {
        assertFalse(HomeRole.held(act));
        assertFalse(HomeRole.ignoresChoice(act));
        assertNull("Android is asking on screen", HomeRole.make(act));
        assertTrue(asked());
    }

    @Test
    public void aTvThatSaidYesAndStillDidntChangeIsNotAskedAgain() {
        roles().addHeldRole(RoleManager.ROLE_HOME);
        assertFalse("Home still opens the other launcher", HomeRole.held(act));
        assertTrue(HomeRole.ignoresChoice(act));
        assertTrue("the Home button service's route", HomeRole.needsSetup(act));
        assertNotNull("says what to do instead of asking", HomeRole.make(act));
        assertFalse(asked());
    }

    @Test
    public void aYesThatChangedNothingIsRemembered() {
        // the role isn't Aura's (yet) as far as this TV says, but it answered yes and Home didn't move
        HomeRole.choiceIgnored(act);
        assertTrue(HomeRole.ignoresChoice(act));
        for (int i = 0; i < 3; i++) assertNotNull(HomeRole.make(act));
        assertFalse("never round again", asked());
    }
}
