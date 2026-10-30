package app.aura.home;

import android.content.Context;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which apps hold the TV's memory, from Android's process report ("dumpsys activity processes"):
 * the memory each process last used (lastPss), helper processes added to their app. It's the
 * figure Android itself keeps fresh, read in a fraction of a second; the full memory report
 * would take nine seconds on a 1.5 GB TV. Off the main thread.
 */
final class Ram {
    static final class Use {
        final String pkg;
        final long kb;

        Use(String pkg, long kb) {
            this.pkg = pkg;
            this.kb = kb;
        }
    }

    private static final Pattern PROC = Pattern.compile("ProcessRecord\\{\\w+ \\d+:([\\w.]+)(?::[\\w.]+)?/");
    private static final Pattern PSS = Pattern.compile("^\\s*lastPss=([\\d.]+)(KB|MB|GB)");

    private Ram() { }

    /** The heaviest running apps, largest first; empty if the report can't be read. */
    static List<Use> heaviest(Context c) {
        Map<String, Long> by = new HashMap<>();
        final String[] proc = {null};
        Reports.read("activity", new String[]{"processes"}, s -> {
            Matcher m = PROC.matcher(s);
            if (m.find()) {
                proc[0] = m.group(1);
                return true;
            }
            if (proc[0] == null) return true;
            Matcher p = PSS.matcher(s);
            if (p.find()) {
                double v = Double.parseDouble(p.group(1));
                long kb = Math.round("GB".equals(p.group(2)) ? v * 1024 * 1024 : "MB".equals(p.group(2)) ? v * 1024 : v);
                by.put(proc[0], (by.containsKey(proc[0]) ? by.get(proc[0]) : 0) + kb);
                proc[0] = null;
            }
            return true;
        });
        List<Use> out = new ArrayList<>();
        for (Map.Entry<String, Long> e : by.entrySet()) out.add(new Use(e.getKey(), e.getValue()));
        java.util.Collections.sort(out, (a, b) -> Long.compare(b.kb, a.kb));
        return out;
    }
}
