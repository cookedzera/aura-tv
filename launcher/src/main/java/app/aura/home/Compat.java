package app.aura.home;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.AppOpsManager;
import android.app.admin.DevicePolicyManager;
import android.app.job.JobScheduler;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.session.MediaSessionManager;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.view.inputmethod.InputMethodManager;

import java.util.HashMap;
import java.util.Map;

/**
 * What Android 5.1 (API 22) lacks, done the older way, so the same code runs from 5.1 to the
 * newest TVs. Features 5.1 has no counterpart for are switched off at their call sites.
 */
final class Compat {
    private static final Map<Class<?>, String> NAMES = new HashMap<>();

    static {
        NAMES.put(ActivityManager.class, Context.ACTIVITY_SERVICE);
        NAMES.put(AlarmManager.class, Context.ALARM_SERVICE);
        NAMES.put(AppOpsManager.class, Context.APP_OPS_SERVICE);
        NAMES.put(AudioManager.class, Context.AUDIO_SERVICE);
        NAMES.put(ConnectivityManager.class, Context.CONNECTIVITY_SERVICE);
        NAMES.put(DevicePolicyManager.class, Context.DEVICE_POLICY_SERVICE);
        NAMES.put(InputMethodManager.class, Context.INPUT_METHOD_SERVICE);
        NAMES.put(JobScheduler.class, Context.JOB_SCHEDULER_SERVICE);
        NAMES.put(MediaSessionManager.class, Context.MEDIA_SESSION_SERVICE);
        NAMES.put(TvInputManager.class, Context.TV_INPUT_SERVICE);
        NAMES.put(UsageStatsManager.class, "usagestats");
        NAMES.put(WifiManager.class, Context.WIFI_SERVICE);
    }

    private Compat() { }

    /** getSystemService(Class) arrived in Android 6; before it, services are looked up by name. */
    @SuppressWarnings("unchecked")
    static <T> T svc(Context c, Class<T> k) {
        if (Build.VERSION.SDK_INT >= 23) return c.getSystemService(k);
        String name = NAMES.get(k);
        return name == null ? null : (T) (k == WifiManager.class ? c.getApplicationContext() : c).getSystemService(name);
    }

    /** Whether this app holds a permission; on 5.1 everything in the manifest is granted at install. */
    static boolean granted(Context c, String perm) {
        return c.checkCallingOrSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    /** Math.floorMod, which Android only has from 7.0. */
    static int mod(int a, int n) {
        int m = a % n;
        return m < 0 ? m + n : m;
    }

    /** True when the device's active connection is Wi-Fi (or Ethernet, with {@code wired}). */
    @SuppressWarnings("deprecation")
    static boolean onWifi(ConnectivityManager cm, boolean wired) {
        if (cm == null) return false;
        if (Build.VERSION.SDK_INT >= 23) {
            android.net.NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
            return nc != null && (nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                    || wired && nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET));
        }
        android.net.NetworkInfo ni = cm.getActiveNetworkInfo();
        return ni != null && ni.isConnected() && (ni.getType() == ConnectivityManager.TYPE_WIFI
                || wired && ni.getType() == ConnectivityManager.TYPE_ETHERNET);
    }

    /** True when any network is up. */
    @SuppressWarnings("deprecation")
    static boolean online(ConnectivityManager cm) {
        if (cm == null) return false;
        if (Build.VERSION.SDK_INT >= 23) return cm.getActiveNetwork() != null;
        android.net.NetworkInfo ni = cm.getActiveNetworkInfo();
        return ni != null && ni.isConnected();
    }

    /** Whether a job with this id is waiting; getPendingJob arrived in Android 7. */
    static boolean pending(JobScheduler js, int id) {
        for (android.app.job.JobInfo j : js.getAllPendingJobs()) if (j.getId() == id) return true;
        return false;
    }
}
