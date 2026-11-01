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
 * Standby uses a force-lock-only device admin granted over ADB; without it, the Home button
 * service (Android 9+) or the TV's own debugging link, if Aura has either.
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

    private static boolean admin(Context c) {
        DevicePolicyManager dpm = Compat.svc(c, DevicePolicyManager.class);
        return dpm != null && dpm.isAdminActive(new ComponentName(c, SleepAdmin.class));
    }

    /** Whether Aura can put the TV to standby at all: without a way, a sleep timer would only fade the sound. */
    static boolean canSleep(Context c) {
        return admin(c) || android.os.Build.VERSION.SDK_INT >= 28 && HomeKeys.live || Adb.on(c);
    }

    static boolean screenOff(Context c) {
        if (admin(c)) {
            Compat.svc(c, DevicePolicyManager.class).lockNow();
            return true;
        }
        if (HomeKeys.sleep()) return true;
        if (!Adb.on(c)) return false;
        // the remote's own Sleep key, through the TV's debugging link
        final Context app = c.getApplicationContext();
        new Thread(() -> Adb.run(app, "input keyevent 223"), "standby").start();
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

    /** Whether an alarm of this kind is still set: after a restart there are none, whatever was saved. */
    private static boolean pending(Context c, String action) {
        Intent i = new Intent(c, SleepReceiver.class).setAction(action);
        return PendingIntent.getBroadcast(c, 7, i, (android.os.Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0)
                | PendingIntent.FLAG_NO_CREATE) != null;
    }

    /**
     * Sleep when what's playing ends: its end, plus a little for the credits to start. When the
     * fade begins it looks again (a pause or a skip moves the end) and waits on if it isn't near.
     */
    static boolean setSleepAtEnd(Context c) {
        long left = MediaWatch.remainingMs(c);
        if (left < 2 * 60_000L || left > 6 * 3_600_000L) return false;
        setSleep(c, 0);
        long at = System.currentTimeMillis() + left + 20_000;
        schedule(c, FADE, 0, Math.max(System.currentTimeMillis() + 1_000, at - FADE_MS));
        prefs(c).edit().putLong("sleepAt", at).putBoolean("sleepEnd", true).apply();
        return true;
    }

    static boolean atEnd(Context c) {
        return prefs(c).getBoolean("sleepEnd", false) && prefs(c).contains("sleepAt");
    }

    static void setSleep(Context c, int minutes) {
        prefs(c).edit().remove("sleepEnd").apply();
        AlarmManager am = Compat.svc(c, AlarmManager.class);
        for (String a : new String[]{FADE, SLEEP, RESTORE}) {
            PendingIntent pi = intent(c, a, 0);
            am.cancel(pi);
            pi.cancel();
        }
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
        // sleeping when it ends: paused or skipped back meanwhile, the end has moved, so wait for it
        if (step == 0 && p.getBoolean("sleepEnd", false)) {
            long left = MediaWatch.remainingMs(c);
            if (left > FADE_MS + 30_000) {
                long at = System.currentTimeMillis() + left + 20_000;
                p.edit().putLong("sleepAt", at).apply();
                schedule(c, FADE, 0, at - FADE_MS);
                return;
            }
        }
        if (am.isVolumeFixed()) {
            long at = p.getLong("sleepAt", 0);
            if (at > System.currentTimeMillis() + 1_000) schedule(c, SLEEP, 0, at);
            else sleepNow(c);
            return;
        }
        if (step == 0) p.edit().putInt("sleepVol", am.getStreamVolume(AudioManager.STREAM_MUSIC)).apply();
        // turned up during the fade: someone's still watching, so the timer stops and the sound stays
        int set = p.getInt("sleepSet", -1);
        if (step > 0 && set >= 0 && am.getStreamVolume(AudioManager.STREAM_MUSIC) > set) {
            p.edit().remove("sleepVol").remove("sleepSet").apply();
            setSleep(c, 0);
            return;
        }
        int start = p.getInt("sleepVol", am.getStreamVolume(AudioManager.STREAM_MUSIC));
        float left = 1f - (step + 1) / (float) FADE_STEPS;
        // ease-out: most of the drop happens early, the last steps are barely audible changes
        int to = Math.round(start * left * left);
        try {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, to, 0);
        } catch (SecurityException ignored) {
        }
        p.edit().putInt("sleepSet", to).apply();
        long next = System.currentTimeMillis() + FADE_MS / FADE_STEPS;
        if (step + 1 < FADE_STEPS) schedule(c, FADE, step + 1, next);
        else schedule(c, SLEEP, 0, next);
    }

    static void sleepNow(Context c) {
        prefs(c).edit().remove("sleepAt").remove("sleepEnd").apply();
        // put the volume back once the panel is off, so it's normal the next time the TV wakes. Had
        // nothing switched it off, the sound stays faded rather than coming back loud in the night:
        // it's put back when Aura is next on screen
        if (screenOff(c)) schedule(c, RESTORE, 0, System.currentTimeMillis() + 4_000);
    }

    /** A fade that ended without standby left the volume low: back to normal now someone's here. */
    static void recover(Context c) {
        SharedPreferences p = prefs(c);
        if (p.contains("sleepVol") && !p.contains("sleepAt") && !pending(c, FADE) && !pending(c, SLEEP)) restoreVolume(c);
    }

    static void restoreVolume(Context c) {
        SharedPreferences p = prefs(c);
        if (!p.contains("sleepVol")) return;
        try {
            Compat.svc(c, AudioManager.class).setStreamVolume(AudioManager.STREAM_MUSIC, p.getInt("sleepVol", 0), 0);
        } catch (SecurityException ignored) {
        }
        p.edit().remove("sleepVol").apply();
    }

    static long sleepLeftMs(Context c) {
        SharedPreferences p = prefs(c);
        if (!p.contains("sleepAt")) return 0;
        long left = p.getLong("sleepAt", 0) - System.currentTimeMillis();
        // the TV restarted since it was set: the alarms went with it
        if (left <= 0 || !pending(c, FADE) && !pending(c, SLEEP)) {
            p.edit().remove("sleepAt").apply();
            return 0;
        }
        return left;
    }
}
