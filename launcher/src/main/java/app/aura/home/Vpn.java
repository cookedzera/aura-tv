package app.aura.home;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;

import java.util.ArrayList;
import java.util.List;

/**
 * A VPN at a glance: whether one is up (Android says so to any app), and which VPN apps are on the
 * TV, so Control Center can show it and open yours. Android lets no app switch another app's VPN
 * on or off: that stays in the VPN app, one OK away.
 */
final class Vpn {
    private static final String KEY = "vpn_app";

    private Vpn() { }

    /** A VPN is carrying the TV's traffic right now. */
    @SuppressWarnings("deprecation")
    static boolean on(Context c) {
        ConnectivityManager cm = Compat.svc(c, ConnectivityManager.class);
        if (cm == null) return false;
        try {
            for (Network n : cm.getAllNetworks()) {
                if (Build.VERSION.SDK_INT >= 23) {
                    NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                    if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true;
                } else {
                    android.net.NetworkInfo ni = cm.getNetworkInfo(n);
                    if (ni != null && ni.getType() == ConnectivityManager.TYPE_VPN && ni.isConnected()) return true;
                }
            }
        } catch (RuntimeException ignored) {
        }
        return false;
    }

    private static List<String> kept;
    private static long keptAt;

    /** Apps were installed or removed: the list is read again next time. */
    static synchronized void forget() {
        kept = null;
    }

    /**
     * The VPN apps on this TV: every app offering Android's VPN service, by package. Asking Android
     * is a search of every app's services: kept for a minute, and dropped when apps change.
     */
    static synchronized List<String> apps(Context c) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (kept != null && now - keptAt < 60_000) return new ArrayList<>(kept);
        kept = read(c);
        keptAt = now;
        return new ArrayList<>(kept);
    }

    private static List<String> read(Context c) {
        List<String> out = new ArrayList<>();
        try {
            for (ResolveInfo r : c.getPackageManager().queryIntentServices(new Intent("android.net.VpnService"), 0)) {
                String p = r.serviceInfo != null ? r.serviceInfo.packageName : null;
                if (p != null && !out.contains(p) && launch(c, p) != null) out.add(p);
            }
        } catch (RuntimeException ignored) {
        }
        return out;
    }

    /** Yours: the one chosen (hold OK on the VPN pill), else the TV's always-on VPN, else the first. */
    static String chosen(Context c, List<String> apps) {
        if (apps.isEmpty()) return null;
        String mine = Prefs.device(c).getString(KEY, null);
        if (mine != null && apps.contains(mine)) return mine;
        try {
            String always = android.provider.Settings.Secure.getString(c.getContentResolver(), "always_on_vpn_app");
            if (always != null && apps.contains(always)) return always;
        } catch (RuntimeException ignored) {
        }
        return apps.get(0);
    }

    static void choose(Context c, String pkg) {
        Prefs.device(c).edit().putString(KEY, pkg).apply();
    }

    static String label(Context c, String pkg) {
        try {
            PackageManager pm = c.getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (Exception e) {
            return "VPN";
        }
    }

    static Intent launch(Context c, String pkg) {
        PackageManager pm = c.getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        return i;
    }
}
