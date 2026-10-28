package app.aura.home;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.function.Predicate;

/**
 * Android's own status reports (the ones "dumpsys" prints), read line by line inside Aura. Needs
 * the DUMP permission, granted once over ADB at setup; without it nothing is read. Reading stops
 * as soon as the caller has what it wants, so even a long report costs only the lines it needs.
 */
final class Reports {
    private Reports() { }

    static boolean allowed(Context c) {
        return c.checkSelfPermission("android.permission.DUMP") == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Feeds each line of system service {@code service}'s report to {@code line} until it returns
     * false. Off the main thread. Returns false if the report couldn't be read at all.
     */
    static boolean read(String service, String[] args, Predicate<String> line) {
        try {
            Object b = Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, service);
            if (!(b instanceof IBinder)) return false;
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            ((IBinder) b).dumpAsync(pipe[1].getFileDescriptor(), args);
            pipe[1].close();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(new ParcelFileDescriptor.AutoCloseInputStream(pipe[0])))) {
                for (String s; (s = r.readLine()) != null; ) if (!line.test(s)) break;
            }
            return true;
        } catch (Throwable e) {
            return false;
        }
    }
}
