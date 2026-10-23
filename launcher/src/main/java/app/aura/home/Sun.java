package app.aura.home;

import java.util.TimeZone;

/**
 * Offline solar position (accurate to about a degree), so the photographs can follow the real sky
 * outside: auroras at night, first light at dawn, daylight, then golden hour. No location
 * permission: the place starts as the time zone's estimate and becomes the TV's city once
 * {@link Weather} has found it.
 */
final class Sun {
    enum Phase { NIGHT, DAWN, DAY, GOLDEN }

    private static volatile double LAT, LON;
    private static final double HORIZON = -0.833; // sunrise/sunset, with refraction

    static {
        TimeZone tz = TimeZone.getDefault();
        double lat = 25, lon = tz.getRawOffset() / 3_600_000.0 * 15;
        String id = tz.getID();
        if (id.equals("Asia/Kolkata") || id.equals("Asia/Calcutta")) {
            // the middle of India: sunrise and sunset land within ~20 min of anywhere in the country
            lat = 22.6;
            lon = 79.0;
        }
        LAT = lat;
        LON = lon;
    }

    private Sun() { }

    /** Exact coordinates once Weather knows where the TV is; until then the time zone's estimate. */
    static void place(double lat, double lon) {
        if (lat == 0 && lon == 0) return;
        LAT = lat;
        LON = lon;
    }

    /** Sun's height above the horizon in degrees at time {@code t} (epoch ms). */
    static double elevation(long t) {
        double n = t / 86_400_000.0 + 2440587.5 - 2451545.0;
        double g = Math.toRadians(norm(357.528 + 0.9856003 * n));
        double lambda = Math.toRadians(norm(280.460 + 0.9856474 * n) + 1.915 * Math.sin(g) + 0.020 * Math.sin(2 * g));
        double eps = Math.toRadians(23.439 - 0.0000004 * n);
        double ra = Math.atan2(Math.cos(eps) * Math.sin(lambda), Math.cos(lambda));
        double dec = Math.asin(Math.sin(eps) * Math.sin(lambda));
        double gmst = (18.697374558 + 24.06570982441908 * n) % 24;
        double ha = Math.toRadians(gmst * 15 + LON) - ra;
        double lat = Math.toRadians(LAT);
        return Math.toDegrees(Math.asin(Math.sin(lat) * Math.sin(dec) + Math.cos(lat) * Math.cos(dec) * Math.cos(ha)));
    }

    static Phase phase(long t) {
        double el = elevation(t);
        if (el < -6) return Phase.NIGHT;
        if (el > 8) return Phase.DAY;
        return elevation(t + 600_000) > el ? Phase.DAWN : Phase.GOLDEN;
    }

    static boolean isUp(long t) {
        return elevation(t) >= HORIZON;
    }

    /** The next sunrise ({@code rise}) or sunset after {@code t}, or -1 within a day (polar edge cases). */
    static long next(long t, boolean rise) {
        double prev = elevation(t);
        for (long s = t + 120_000; s < t + 26 * 3_600_000L; s += 120_000) {
            double e = elevation(s);
            if (rise ? (prev < HORIZON && e >= HORIZON) : (prev >= HORIZON && e < HORIZON)) return s;
            prev = e;
        }
        return -1;
    }

    /** Which photograph moods suit this light. */
    static boolean suits(String mood, Phase p) {
        switch (p) {
            case NIGHT:
                return "night".equals(mood);
            case DAWN:
            case GOLDEN:
                return "dawn".equals(mood) || "golden".equals(mood);
            default:
                return "day".equals(mood);
        }
    }

    static String label(Phase p) {
        switch (p) {
            case NIGHT:
                return "NIGHT SKY";
            case DAWN:
                return "FIRST LIGHT";
            case GOLDEN:
                return "GOLDEN HOUR";
            default:
                return "DAYLIGHT";
        }
    }

    private static double norm(double deg) {
        double d = deg % 360;
        return d < 0 ? d + 360 : d;
    }
}
