package app.aura.home;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Drives the Gentle Sleep timer: fade steps, standby, then restoring the volume. */
public class SleepReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        String a = i.getAction();
        if (Power.FADE.equals(a)) Power.fadeStep(c, i.getIntExtra("step", 0));
        else if (Power.RESTORE.equals(a)) Power.restoreVolume(c);
        else Power.sleepNow(c);
    }
}
