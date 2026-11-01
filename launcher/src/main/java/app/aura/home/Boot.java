package app.aura.home;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

/**
 * After a restart, on a TV where Aura's Home button service is what makes Aura home (Google TV):
 * some updates and restarts leave the service switched off, or switched on but never started
 * (Projectivy's users met both). Aura isn't the home Android opens, so nothing of Aura would run
 * to notice: this does, once the TV has started.
 */
public class Boot extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())) return;
        final Context app = c.getApplicationContext();
        // the TV went off in the moment the service was being switched off and on again: back on
        if (Prefs.device(app).getBoolean("keys_healing", false)) {
            HomeKeys.set(app, true);
            Prefs.device(app).edit().putBoolean("keys_healing", false).apply();
            return;
        }
        if (!HomeKeys.wanted(app) || HomeRole.held(app) || !HomeKeys.takeover(app)) return;
        if (!HomeKeys.on(app)) {
            HomeKeys.set(app, true);
            return;
        }
        // on, yet it may not have started: given a few seconds, then switched off and on again
        final PendingResult wait = goAsync();
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (!HomeKeys.live) HomeKeys.heal(app);
            wait.finish();
        }, 8000);
    }
}
