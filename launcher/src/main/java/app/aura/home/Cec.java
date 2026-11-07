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
    /** What kind of device each port holds, as CEC has it: {@link #PLAYBACK}, {@link #TUNER}, {@link #RECORDER} or {@link #AUDIO}. */
    private static volatile Map<Integer, Integer> types = new HashMap<>();
    private static volatile long readAt = -60_000;
    private static volatile boolean reading;
    private static final Pattern PORT = Pattern.compile("^HDMI (\\d)$");

    /** CEC device types (the standard's numbering, as HdmiDeviceInfo has them); {@link #UNKNOWN} when CEC says nothing. */
    static final int UNKNOWN = -1, RECORDER = 1, TUNER = 3, PLAYBACK = 4, AUDIO = 5;

    private Cec() { }

    /** "HDMI 2" becomes the device's own name when one is known; anything else is left alone. */
    static String name(Context c, String label) {
        refresh(c);
        Integer port = port(label);
        String n = port != null ? names.get(port) : null;
        return n != null ? n : label;
    }

    /** The kind of device on "HDMI n" as CEC reports it, or {@link #UNKNOWN} (another label, no CEC, or a device that keeps quiet). */
    static int type(Context c, String label) {
        refresh(c);
        Integer port = port(label);
        Integer t = port != null ? types.get(port) : null;
        return t != null ? t : UNKNOWN;
    }

    private static Integer port(String label) {
        Matcher m = PORT.matcher(label);
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }

    /** A CEC logical address's device type: the standard fixes which addresses each kind of device may take. */
    static int typeOfAddress(int logical) {
        switch (logical) {
            case 1: case 2: case 9: return RECORDER;
            case 3: case 6: case 7: case 10: return TUNER;
            case 4: case 8: case 11: return PLAYBACK;
            case 5: return AUDIO;
            default: return UNKNOWN;
        }
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

    /** The devices CEC knows, by port: each one's name and kind. Null when the CEC service won't talk to Aura. */
    private static Map<Integer, Object[]> tryHdmiManager(Context c) {
        try {
            Object mgr = c.getSystemService("hdmi_control");
            if (mgr == null) return null;
            Object tv = mgr.getClass().getMethod("getTvClient").invoke(mgr);
            if (tv == null) return null;
            Object list = tv.getClass().getMethod("getDeviceList").invoke(tv);
            if (!(list instanceof java.util.List<?>)) return null;
            Map<Integer, Object[]> found = new HashMap<>();
            for (Object d : (java.util.List<?>) list) {
                if (d == null) continue;
                int logical = (Integer) d.getClass().getMethod("getLogicalAddress").invoke(d);
                if (logical == 0) continue;
                int phys = (Integer) d.getClass().getMethod("getPhysicalAddress").invoke(d);
                int port = (phys >> 12) & 0xF;
                Object disp = d.getClass().getMethod("getDisplayName").invoke(d);
                String name = disp != null ? disp.toString().trim() : "";
                int type = typeOfAddress(logical);
                try {
                    type = (Integer) d.getClass().getMethod("getDeviceType").invoke(d);
                } catch (Throwable ignored) {
                    // older Android: the logical address says what kind it is
                }
                if (port > 0 && !name.isEmpty()) found.put(port, new Object[]{name, type});
            }
            return found;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void keep(Map<Integer, Object[]> found) {
        Map<Integer, String> n = new HashMap<>();
        Map<Integer, Integer> t = new HashMap<>();
        for (Map.Entry<Integer, Object[]> e : found.entrySet()) {
            n.put(e.getKey(), (String) e.getValue()[0]);
            t.put(e.getKey(), (Integer) e.getValue()[1]);
        }
        names = n;
        types = t;
    }

    private static void refresh(Context c) {
        if (reading || SystemClock.elapsedRealtime() - readAt < 30_000) return;
        // marked read now, whatever comes of it: the CEC service refuses most apps outright, and
        // a refusal every call (one per input, on every opening) was never given its half minute
        readAt = SystemClock.elapsedRealtime();
        Map<Integer, Object[]> direct = tryHdmiManager(c);
        if (direct != null && !direct.isEmpty()) {
            keep(direct);
            return;
        }
        if (!Reports.allowed(c)) return;
        reading = true;
        new SafeThread(() -> {
            Map<Integer, Object[]> found = new HashMap<>();
            try {
                Reports.read("hdmi_control", new String[0], s -> {
                    Matcher m = DEVICE.matcher(s);
                    // logical address 0 is the TV itself
                    if (m.find() && Integer.parseInt(m.group(1), 16) != 0) {
                        int port = Integer.parseInt(m.group(3), 16);
                        String name = m.group(2).trim();
                        if (port > 0 && !name.isEmpty()) found.put(port, new Object[]{name, typeOfAddress(Integer.parseInt(m.group(1), 16))});
                    }
                    return true;
                });
                keep(found);
            } finally {
                reading = false;
            }
        }, "cec").start();
    }
}
