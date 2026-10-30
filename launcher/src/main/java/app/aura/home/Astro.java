package app.aura.home;

/**
 * Where things are in the sky, from plain formulas and no network: the stars' places for any
 * moment and city, the Sun, the Moon and its phase (the Astronomical Almanac's low-precision
 * series, good to a few tenths of a degree), and the bright planets from NASA JPL's approximate
 * Keplerian elements (valid 1800–2050). Angles are in degrees; azimuth runs from north through east.
 */
final class Astro {
    private static final double D = Math.PI / 180;

    private Astro() { }

    /** Julian date of a moment in epoch milliseconds. */
    static double jd(long ms) {
        return ms / 86_400_000.0 + 2_440_587.5;
    }

    /** Local sidereal time in degrees at longitude {@code lon} (east positive). */
    static double lst(double jd, double lon) {
        double d = jd - 2_451_545.0, t = d / 36_525.0;
        double gmst = 280.46061837 + 360.98564736629 * d + 0.000387933 * t * t - t * t * t / 38_710_000.0;
        return norm(gmst + lon);
    }

    /** Altitude and azimuth of a body at right ascension {@code ra} and declination {@code dec}. */
    static void horizontal(double ra, double dec, double lst, double lat, double[] out) {
        double h = (lst - ra) * D, d = dec * D, p = lat * D;
        double sinAlt = Math.sin(p) * Math.sin(d) + Math.cos(p) * Math.cos(d) * Math.cos(h);
        double alt = Math.asin(Math.max(-1, Math.min(1, sinAlt)));
        double az = Math.atan2(-Math.cos(d) * Math.sin(h), Math.sin(d) * Math.cos(p) - Math.cos(d) * Math.sin(p) * Math.cos(h));
        out[0] = alt / D;
        out[1] = norm(az / D);
    }

    /**
     * The IAU 1976 precession from the catalogue's J2000 to the date: Earth's axis has turned about
     * a third of a degree since, which would otherwise shift every star. A 3×3 matrix, row-major.
     */
    static double[] precession(double jd) {
        double t = (jd - 2_451_545.0) / 36_525.0, as = D / 3600;
        double zeta = (2306.2181 * t + 0.30188 * t * t + 0.017998 * t * t * t) * as;
        double z = (2306.2181 * t + 1.09468 * t * t + 0.018203 * t * t * t) * as;
        double th = (2004.3109 * t - 0.42665 * t * t - 0.041833 * t * t * t) * as;
        double cz = Math.cos(z), sz = Math.sin(z), ct = Math.cos(th), st = Math.sin(th), cq = Math.cos(zeta), sq = Math.sin(zeta);
        return new double[]{
                cz * ct * cq - sz * sq, -cz * ct * sq - sz * cq, -cz * st,
                sz * ct * cq + cz * sq, -sz * ct * sq + cz * cq, -sz * st,
                st * cq, -st * sq, ct};
    }

    /** J2000 right ascension and declination moved to the date with {@code p} (see {@link #precession}). */
    static void toDate(double ra, double dec, double[] p, double[] out) {
        double r = ra * D, d = dec * D;
        double x = Math.cos(d) * Math.cos(r), y = Math.cos(d) * Math.sin(r), z = Math.sin(d);
        double xx = p[0] * x + p[1] * y + p[2] * z, yy = p[3] * x + p[4] * y + p[5] * z, zz = p[6] * x + p[7] * y + p[8] * z;
        out[0] = norm(Math.atan2(yy, xx) / D);
        out[1] = Math.asin(Math.max(-1, Math.min(1, zz))) / D;
    }

    /**
     * The Moon as seen from the ground rather than from Earth's centre: it's near enough that
     * where you stand lowers it by up to a degree (its horizontal parallax, ~0.95°).
     */
    static double moonFromGround(double alt) {
        return alt - 0.95 * Math.cos(alt * D);
    }

    private static double obliquity(double jd) {
        return 23.439291 - 0.0130042 * (jd - 2_451_545.0) / 36_525.0;
    }

    /** Ecliptic longitude and latitude to right ascension and declination. */
    private static void equatorial(double lon, double lat, double jd, double[] out) {
        double e = obliquity(jd) * D, l = lon * D, b = lat * D;
        double x = Math.cos(b) * Math.cos(l);
        double y = Math.cos(e) * Math.cos(b) * Math.sin(l) - Math.sin(e) * Math.sin(b);
        double z = Math.sin(e) * Math.cos(b) * Math.sin(l) + Math.cos(e) * Math.sin(b);
        out[0] = norm(Math.atan2(y, x) / D);
        out[1] = Math.asin(z) / D;
    }

    /** The Sun's ecliptic longitude (degrees). */
    static double sunLongitude(double jd) {
        double n = jd - 2_451_545.0;
        double l = 280.460 + 0.9856474 * n, g = (357.528 + 0.9856003 * n) * D;
        return norm(l + 1.915 * Math.sin(g) + 0.020 * Math.sin(2 * g));
    }

    /** The Sun's right ascension and declination. */
    static void sun(double jd, double[] out) {
        equatorial(sunLongitude(jd), 0, jd, out);
    }

    /**
     * The Moon: {@code out} gets right ascension, declination, ecliptic longitude and latitude
     * (the Almanac's low-precision series; geocentric, which for a screensaver is plenty).
     */
    static void moon(double jd, double[] out) {
        double t = (jd - 2_451_545.0) / 36_525.0;
        double lon = 218.32 + 481_267.881 * t
                + 6.29 * sin(135.0 + 477_198.87 * t) - 1.27 * sin(259.3 - 413_335.36 * t)
                + 0.66 * sin(235.7 + 890_534.22 * t) + 0.21 * sin(269.9 + 954_397.74 * t)
                - 0.19 * sin(357.5 + 35_999.05 * t) - 0.11 * sin(186.5 + 966_404.03 * t);
        double lat = 5.13 * sin(93.3 + 483_202.02 * t) + 0.28 * sin(228.2 + 960_400.89 * t)
                - 0.28 * sin(318.3 + 6_003.15 * t) - 0.17 * sin(217.6 - 407_332.21 * t);
        lon = norm(lon);
        equatorial(lon, lat, jd, out);
        out[2] = lon;
        out[3] = lat;
    }

    /** How much of the Moon is lit, 0–1. */
    static double moonLit(double jd) {
        double[] m = new double[4];
        moon(jd, m);
        double e = Math.acos(Math.cos((m[2] - sunLongitude(jd)) * D) * Math.cos(m[3] * D));
        return (1 - Math.cos(e)) / 2;
    }

    /** True while the Moon is growing towards full. */
    static boolean moonWaxing(double jd) {
        double[] m = new double[4];
        moon(jd, m);
        return norm(m[2] - sunLongitude(jd)) < 180;
    }

    /** a, e, I, L, long. of perihelion, long. of node; then their rates per century (JPL Table 1). */
    private static final double[][] ELEMENTS = {
            {0.38709927, 0.20563593, 7.00497902, 252.25032350, 77.45779628, 48.33076593,
                    0.00000037, 0.00001906, -0.00594749, 149472.67411175, 0.16047689, -0.12534081},
            {0.72333566, 0.00677672, 3.39467605, 181.97909950, 131.60246718, 76.67984255,
                    0.00000390, -0.00004107, -0.00078890, 58517.81538729, 0.00268329, -0.27769418},
            {1.00000261, 0.01671123, -0.00001531, 100.46457166, 102.93768193, 0.0,
                    0.00000562, -0.00004392, -0.01294668, 35999.37244981, 0.32327364, 0.0},
            {1.52371034, 0.09339410, 1.84969142, -4.55343205, -23.94362959, 49.55953891,
                    0.00001847, 0.00007882, -0.00813131, 19140.30268499, 0.44441088, -0.29257343},
            {5.20288700, 0.04838624, 1.30439695, 34.39644051, 14.72847983, 100.47390909,
                    -0.00011607, -0.00013253, -0.00183714, 3034.74612775, 0.21252668, 0.20469106},
            {9.53667594, 0.05386179, 2.48599187, 49.95424423, 92.59887831, 113.66242448,
                    -0.00125060, -0.00050991, 0.00193609, 1222.49362201, -0.41897216, -0.28867794},
    };
    static final int MERCURY = 0, VENUS = 1, EARTH = 2, MARS = 3, JUPITER = 4, SATURN = 5;
    static final String[] PLANET_NAMES = {"Mercury", "Venus", "Earth", "Mars", "Jupiter", "Saturn"};

    /** Heliocentric ecliptic x, y, z (au, J2000 ecliptic) of planet {@code p}. */
    private static void helio(int p, double jd, double[] out) {
        double t = (jd - 2_451_545.0) / 36_525.0;
        double[] k = ELEMENTS[p];
        double a = k[0] + k[6] * t, e = k[1] + k[7] * t, inc = (k[2] + k[8] * t) * D;
        double l = k[3] + k[9] * t, peri = k[4] + k[10] * t, node = (k[5] + k[11] * t);
        double w = (peri - node) * D, m = norm(l - peri);
        if (m > 180) m -= 360;
        m *= D;
        double ea = m + e * Math.sin(m);
        for (int i = 0; i < 8; i++) ea -= (ea - e * Math.sin(ea) - m) / (1 - e * Math.cos(ea));
        double xp = a * (Math.cos(ea) - e), yp = a * Math.sqrt(1 - e * e) * Math.sin(ea);
        double o = node * D, cw = Math.cos(w), sw = Math.sin(w), co = Math.cos(o), so = Math.sin(o), ci = Math.cos(inc), si = Math.sin(inc);
        out[0] = (cw * co - sw * so * ci) * xp + (-sw * co - cw * so * ci) * yp;
        out[1] = (cw * so + sw * co * ci) * xp + (-sw * so + cw * co * ci) * yp;
        out[2] = (sw * si) * xp + (cw * si) * yp;
    }

    /** Planet {@code p} seen from Earth: right ascension, declination and distance in au. */
    static void planet(int p, double jd, double[] out) {
        double[] pl = new double[3], ea = new double[3];
        helio(p, jd, pl);
        helio(EARTH, jd, ea);
        double x = pl[0] - ea[0], y = pl[1] - ea[1], z = pl[2] - ea[2];
        // J2000 ecliptic to equator
        double e = 23.43928 * D;
        double ye = Math.cos(e) * y - Math.sin(e) * z, ze = Math.sin(e) * y + Math.cos(e) * z;
        out[0] = norm(Math.atan2(ye, x) / D);
        out[1] = Math.atan2(ze, Math.sqrt(x * x + ye * ye)) / D;
        out[2] = Math.sqrt(x * x + y * y + z * z);
    }

    private static double sin(double deg) {
        return Math.sin(deg * D);
    }

    static double norm(double deg) {
        double r = deg % 360;
        return r < 0 ? r + 360 : r;
    }
}
