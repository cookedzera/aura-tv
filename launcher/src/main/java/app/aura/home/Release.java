package app.aura.home;

import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

/**
 * Drops Aura's standby admin and any sleep timer so Aura can be uninstalled. Guarded by the DUMP
 * permission, so only the system or the ADB shell (the restore script) can trigger it.
 */
public class Release extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        Power.setSleep(c, 0);
        DevicePolicyManager dpm = c.getSystemService(DevicePolicyManager.class);
        ComponentName admin = new ComponentName(c, SleepAdmin.class);
        if (dpm != null && dpm.isAdminActive(admin)) dpm.removeActiveAdmin(admin);
    }
}
