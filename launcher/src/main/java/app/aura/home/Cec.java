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

    private static void refresh(Context c) {
        if (reading || SystemClock.elapsedRealtime() - readAt < 30_000 || !Reports.allowed(c)) return;
        reading = true;
        new Thread(() -> {
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
