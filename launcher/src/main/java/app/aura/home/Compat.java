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
    /**
     * One thread for work behind the screen (apps, artwork, weather, the stack, photos), at a lower
     * priority than drawing: Android runs it in the same group as the screen, so at the same priority
     * it competed for every frame, worst just after the TV starts, while Android is busy too.
     */
    static java.util.concurrent.ExecutorService background(String name, int priority) {
        return quiet(1, r -> new SafeThread(() -> {
            android.os.Process.setThreadPriority(priority);
            r.run();
        }, name));
    }

    /**
     * A fixed pool whose work handed over after it was shut (a late callback reaching a torn-down
     * home screen, screensaver or phone link) is dropped, not thrown back as a crash.
     */
    private static java.util.concurrent.ExecutorService quiet(int threads, java.util.concurrent.ThreadFactory f) {
        return new java.util.concurrent.ThreadPoolExecutor(threads, threads, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>(), f, new java.util.concurrent.ThreadPoolExecutor.DiscardPolicy());
    }

    /** {@code threads} workers at normal priority, for work someone is waiting on; each a {@link SafeThread}. */
    static java.util.concurrent.ExecutorService pool(String name, int threads) {
        return quiet(threads, r -> new SafeThread(r, name));
    }

    /**
     * A box with no TV inputs to speak of: no live TV support declared (Fire TV sticks, most
     * streaming boxes). Its input service is missing, and asking it throws every time; that's
     * simply no inputs there, not a problem worth a report.
     */
    static boolean noTvInputs(Context c) {
        try {
            return !c.getPackageManager().hasSystemFeature(android.content.pm.PackageManager.FEATURE_LIVE_TV);
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** True for the first minutes after the TV started, while Android is still busy starting everything else. */
    static boolean justBooted() {
        return android.os.SystemClock.elapsedRealtime() < 4 * 60_000L;
    }

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
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                android.net.NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
                return nc != null && (nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                        || wired && nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET));
            }
            android.net.NetworkInfo ni = cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected() && (ni.getType() == ConnectivityManager.TYPE_WIFI
                    || wired && ni.getType() == ConnectivityManager.TYPE_ETHERNET);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** True when any network is up. */
    @SuppressWarnings("deprecation")
    static boolean online(ConnectivityManager cm) {
        if (cm == null) return false;
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                // connected isn't online: a network with no way out (a router without internet) doesn't count
                android.net.NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
                return nc != null && nc.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET);
            }
            android.net.NetworkInfo ni = cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Whether a job with this id is waiting; getPendingJob arrived in Android 7. */
    static boolean pending(JobScheduler js, int id) {
        if (js == null) return false;
        try {
            for (android.app.job.JobInfo j : js.getAllPendingJobs()) if (j.getId() == id) return true;
        } catch (RuntimeException ignored) {
        }
        return false;
    }
}
