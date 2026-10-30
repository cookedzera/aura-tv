package app.aura.home;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.provider.Settings;

/**
 * Whether the Home button opens Aura, and asking Android to make it so. From Android 10 an app can
 * ask for the home role itself: Android puts a plain yes/no on the screen, which the remote
 * answers, and nothing in Android leaves TVs out. Many TV Settings apps have no "Default home"
 * page at all, so without this, only a PC could change it. After a second No, Android adds
 * "Don't ask again" and then turns every later request away unseen, so Aura only asks when you
 * press for it, never by itself.
 */
final class HomeRole {
    static final int REQUEST = 41;

    private HomeRole() { }

    static boolean held(Context c) {
        if (Build.VERSION.SDK_INT >= 29) {
            android.app.role.RoleManager rm = c.getSystemService(android.app.role.RoleManager.class);
            if (rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME)) {
                return rm.isRoleHeld(android.app.role.RoleManager.ROLE_HOME);
            }
        }
        ResolveInfo r = c.getPackageManager().resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY);
        return r != null && r.activityInfo != null && c.getPackageName().equals(r.activityInfo.packageName);
    }

    /**
     * Android's own question (the answer comes back to the activity's onActivityResult), or on
     * older TVs the home settings page. False when this TV offers neither.
     */
    static boolean ask(Activity a) {
        if (Build.VERSION.SDK_INT >= 29) {
            android.app.role.RoleManager rm = a.getSystemService(android.app.role.RoleManager.class);
            if (rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME)) {
                try {
                    // for result: Android only answers an app it knows asked, and it knows from this
                    a.startActivityForResult(rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_HOME), REQUEST);
                    return true;
                } catch (RuntimeException ignored) {
                }
            }
        }
        try {
            a.startActivity(new Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
