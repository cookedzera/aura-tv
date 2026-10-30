package app.aura.home;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;

/**
 * Standby and the Gentle Sleep timer: in the last minute the volume eases down in small steps,
 * then the TV goes to standby and the volume is quietly put back for next time.
 * Standby uses a force-lock-only device admin granted over ADB.
 */
final class Power {
    static final String FADE = "app.aura.home.action.FADE", SLEEP = "app.aura.home.action.SLEEP",
            RESTORE = "app.aura.home.action.RESTORE";
    static final long FADE_MS = 60_000;
    static final int FADE_STEPS = 12;

    private Power() { }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("aura", Context.MODE_PRIVATE);
    }

    static boolean screenOff(Context c) {
        DevicePolicyManager dpm = Compat.svc(c, DevicePolicyManager.class);
        ComponentName admin = new ComponentName(c, SleepAdmin.class);
        if (dpm == null || !dpm.isAdminActive(admin)) return false;
        dpm.lockNow();
        return true;
    }

    static PendingIntent intent(Context c, String action, int step) {
        Intent i = new Intent(c, SleepReceiver.class).setAction(action).putExtra("step", step);
        return PendingIntent.getBroadcast(c, 7, i, (android.os.Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0) | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static void schedule(Context c, String action, int step, long at) {
        AlarmManager am = Compat.svc(c, AlarmManager.class);
        // Doze, and the alarm that pierces it, arrived in Android 6
        if (android.os.Build.VERSION.SDK_INT >= 23) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent(c, action, step));
        else am.setExact(AlarmManager.RTC_WAKEUP, at, intent(c, action, step));
    }

    static void setSleep(Context c, int minutes) {
        AlarmManager am = Compat.svc(c, AlarmManager.class);
        am.cancel(intent(c, FADE, 0));
        am.cancel(intent(c, SLEEP, 0));
        restoreVolume(c);
        if (minutes <= 0) {
            prefs(c).edit().remove("sleepAt").apply();
            return;
        }
        long now = System.currentTimeMillis(), at = now + minutes * 60_000L;
        schedule(c, FADE, 0, Math.max(now + 1_000, at - FADE_MS));
        prefs(c).edit().putLong("sleepAt", at).apply();
    }

    /** One step of the fade; the first step remembers the volume to come back to. */
    static void fadeStep(Context c, int step) {
        AudioManager am = Compat.svc(c, AudioManager.class);
        SharedPreferences p = prefs(c);
        if (step == 0) p.edit().putInt("sleepVol", am.getStreamVolume(AudioManager.STREAM_MUSIC)).apply();
        int start = p.getInt("sleepVol", am.getStreamVolume(AudioManager.STREAM_MUSIC));
        float left = 1f - (step + 1) / (float) FADE_STEPS;
        // ease-out: most of the drop happens early, the last steps are barely audible changes
        am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(start * left * left), 0);
        long next = System.currentTimeMillis() + FADE_MS / FADE_STEPS;
        if (step + 1 < FADE_STEPS) schedule(c, FADE, step + 1, next);
        else schedule(c, SLEEP, 0, next);
    }

    static void sleepNow(Context c) {
        prefs(c).edit().remove("sleepAt").apply();
        screenOff(c);
        // put the volume back once the panel is off, so it's normal the next time the TV wakes
        schedule(c, RESTORE, 0, System.currentTimeMillis() + 4_000);
    }

    static void restoreVolume(Context c) {
        SharedPreferences p = prefs(c);
        if (!p.contains("sleepVol")) return;
        Compat.svc(c, AudioManager.class).setStreamVolume(AudioManager.STREAM_MUSIC, p.getInt("sleepVol", 0), 0);
        p.edit().remove("sleepVol").apply();
    }

    static long sleepLeftMs(Context c) {
        SharedPreferences p = prefs(c);
        long left = p.getLong("sleepAt", 0) - System.currentTimeMillis();
        if (left <= 0) {
            p.edit().remove("sleepAt").apply();
            return 0;
        }
        return left;
    }
}
