package app.aura.home;

import android.content.Context;
import android.os.SystemClock;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The names your HDMI devices give themselves over HDMI-CEC ("PlayStation 5", "Tata Play"), from
 * the TV's own CEC report. A device's physical address starts with the TV port it's plugged
 * into (the CEC standard), so "HDMI 2" can be shown as the box on port 2. Read in the background
 * and kept for half a minute; until then, and on TVs without CEC, inputs keep their usual names.
 */
final class Cec {
    private static final Pattern DEVICE = Pattern.compile(
            "logical_address: 0x([0-9A-Fa-f]+).*?display_name: (.+?) power_status: \\S+ physical_address: 0x([0-9A-Fa-f])");
    private static volatile Map<Integer, String> names = new HashMap<>();
    private static volatile long readAt = -60_000;
    private static volatile boolean reading;

    private Cec() { }

    /** "HDMI 2" becomes the device's own name when one is known; anything else is left alone. */
    static String name(Context c, String label) {
        refresh(c);
        Matcher m = Pattern.compile("^HDMI (\\d)$").matcher(label);
        if (!m.find()) return label;
        String n = names.get(Integer.parseInt(m.group(1)));
        return n != null ? n : label;
    }

    /**
     * True when the TV's HDMI-CEC service reports an external HDMI device (physical address > 0) as
     * the active source right now (e.g. a console woke the TV via One Touch Play).
     */
    static boolean externalActiveSource(Context c) {
        try {
            Object mgr = c.getSystemService("hdmi_control");
            if (mgr == null) return false;
            Object tv = mgr.getClass().getMethod("getTvClient").invoke(mgr);
            if (tv == null) return false;
            Object src = tv.getClass().getMethod("getActiveSource").invoke(tv);
            if (src == null) return false;
            int phys = (Integer) src.getClass().getMethod("getPhysicalAddress").invoke(src);
            return phys > 0 && phys != 0xFFFF;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Map<Integer, String> tryHdmiManager(Context c) {
        try {
            Object mgr = c.getSystemService("hdmi_control");
            if (mgr == null) return null;
            Object tv = mgr.getClass().getMethod("getTvClient").invoke(mgr);
            if (tv == null) return null;
            Object list = tv.getClass().getMethod("getDeviceList").invoke(tv);
            if (!(list instanceof java.util.List<?>)) return null;
            Map<Integer, String> found = new HashMap<>();
            for (Object d : (java.util.List<?>) list) {
                if (d == null) continue;
                int logical = (Integer) d.getClass().getMethod("getLogicalAddress").invoke(d);
                if (logical == 0) continue;
                int phys = (Integer) d.getClass().getMethod("getPhysicalAddress").invoke(d);
                int port = (phys >> 12) & 0xF;
                Object disp = d.getClass().getMethod("getDisplayName").invoke(d);
                String name = disp != null ? disp.toString().trim() : "";
                if (port > 0 && !name.isEmpty()) found.put(port, name);
            }
            return found;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void refresh(Context c) {
        if (reading || SystemClock.elapsedRealtime() - readAt < 30_000) return;
        Map<Integer, String> direct = tryHdmiManager(c);
        if (direct != null && !direct.isEmpty()) {
            names = direct;
            readAt = SystemClock.elapsedRealtime();
            return;
        }
        if (!Reports.allowed(c)) return;
        reading = true;
        new SafeThread(() -> {
            Map<Integer, String> found = new HashMap<>();
            Reports.read("hdmi_control", new String[0], s -> {
                Matcher m = DEVICE.matcher(s);
                // logical address 0 is the TV itself
                if (m.find() && Integer.parseInt(m.group(1), 16) != 0) {
                    int port = Integer.parseInt(m.group(3), 16);
                    String name = m.group(2).trim();
                    if (port > 0 && !name.isEmpty()) found.put(port, name);
                }
                return true;
            });
            names = found;
            readAt = SystemClock.elapsedRealtime();
            reading = false;
        }, "cec").start();
    }
}
