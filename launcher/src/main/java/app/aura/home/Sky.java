package app.aura.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tonight's sky, as it is right now above the TV's city: 4,000 stars in their true places, the
 * constellations, the Milky Way, the Moon at its real phase with its lit side towards the Sun, and
 * Venus, Mars, Jupiter and Saturn where they actually are, over a dark horizon. Twilight colours
 * follow the real Sun; a shooting star crosses now and then, many more on the nights of the great
 * meteor showers, streaming from their real radiant.
 *
 * Cheap to show: the sky is painted off the main thread into one picture every 30 seconds (the
 * stars move half a pixel in that time) and cross-faded; only the brightest stars' twinkle, the
 * planets, the Moon, names and meteors are drawn live, at about 15 frames a second.
 */
final class Sky extends Scene {

    private static final double D = Math.PI / 180;
    private static final float FOV = 110f, LOOK_UP = 22f;
    private static final long PLATE_MS = 30_000, FADE_MS = 2_000, STORY_MS = 40_000;
    private static final String CREDIT = "Stars from Hipparcos, planets from NASA JPL";

    // ---- the catalogue: loaded once, about 70 KB --------------------------------------------

    private static float[] stars, lines;
    private static final List<Label> CONSTELLATIONS = new ArrayList<>(), NAMED = new ArrayList<>();

    private static final class Label {
        final String name;
        final float ra, dec, mag;
        final int rank;

        Label(String name, float ra, float dec, float mag, int rank) {
            this.name = name;
            this.ra = ra;
            this.dec = dec;
            this.mag = mag;
            this.rank = rank;
        }
    }

    /** How far the brightest named stars are, in light-years: "the light you see set out in …". */
    private static final String[] LY_NAMES = {"Sirius", "Canopus", "Arcturus", "Vega", "Capella", "Rigel", "Procyon",
            "Achernar", "Betelgeuse", "Hadar", "Altair", "Acrux", "Aldebaran", "Antares", "Spica", "Pollux",
            "Fomalhaut", "Deneb", "Mimosa", "Regulus", "Adhara", "Castor", "Gacrux", "Rigil Kentaurus", "Toliman"};
    private static final float[] LY = {8.6f, 310, 37, 25, 43, 860, 11.5f, 139, 550, 390, 16.7f, 320, 65, 550, 250, 34,
            25, 2600, 280, 79, 430, 51, 88, 4.4f, 4.4f};

    /** Name, peak month (1–12), day, radiant RA, Dec, zenithal hourly rate, constellation. */
    private static final Object[][] SHOWERS = {
            {"Quadrantids", 1, 3, 230.1, 48.5, 110, "Boötes"},
            {"Lyrids", 4, 22, 271.4, 33.6, 18, "Lyra"},
            {"Eta Aquariids", 5, 6, 338.0, -1.0, 50, "Aquarius"},
            {"Perseids", 8, 12, 48.2, 58.1, 100, "Perseus"},
            {"Orionids", 10, 21, 95.4, 15.9, 20, "Orion"},
            {"Leonids", 11, 17, 152.3, 22.0, 15, "Leo"},
            {"Geminids", 12, 14, 113.2, 32.5, 150, "Gemini"},
    };

    static synchronized boolean load(Context c) {
        if (stars != null) return true;
        try {
            stars = floats(c, "sky/stars.bin");
            lines = floats(c, "sky/lines.bin");
            JSONObject o;
            try (InputStream in = c.getAssets().open("sky/names.json")) {
                java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
                o = new JSONObject(new String(b.toByteArray(), "UTF-8"));
            }
            JSONArray cs = o.getJSONArray("constellations"), ns = o.getJSONArray("stars");
            for (int i = 0; i < cs.length(); i++) {
                JSONObject x = cs.getJSONObject(i);
                CONSTELLATIONS.add(new Label(x.getString("n"), (float) x.getDouble("ra"), (float) x.getDouble("dec"), 0, x.getInt("rank")));
            }
            for (int i = 0; i < ns.length(); i++) {
                JSONObject x = ns.getJSONObject(i);
                NAMED.add(new Label(x.getString("n"), (float) x.getDouble("ra"), (float) x.getDouble("dec"), (float) x.getDouble("mag"), 1));
            }
            return true;
        } catch (IOException | org.json.JSONException e) {
            stars = null;
            return false;
        }
    }

    private static float[] floats(Context c, String asset) throws IOException {
        try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(c.getAssets().open(asset)))) {
            int n = in.readInt();
            float[] out = new float[n * 4];
            for (int i = 0; i < out.length; i++) out[i] = in.readFloat();
            return out;
        }
    }

    // ---- one moment of the sky, painted off the main thread ------------------------------------

    private static final class Plate {
        Bitmap bitmap;
        /** x, y, radius, colour (bits), seed: the bright stars drawn live, twinkling. */
        float[] bright = new float[0];
        int nBright;
        final List<float[]> planets = new ArrayList<>();
        final List<String> planetNames = new ArrayList<>();
        final List<float[]> labels = new ArrayList<>();
        final List<String> labelNames = new ArrayList<>();
        boolean moon;
        float moonX, moonY, moonAngle;
        Bitmap moonBitmap;
        boolean radiant;
        float radiantX, radiantY;
        int showerRate;
        final List<String[]> stories = new ArrayList<>();
        String kicker = "";

        void recycle() {
            if (bitmap != null) bitmap.recycle();
            if (moonBitmap != null) moonBitmap.recycle();
            bitmap = moonBitmap = null;
        }
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Random rnd = new Random();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix drift = new Matrix(), m = new Matrix();
    private final float[] pt = new float[2];
    private Bitmap glow;
    private boolean running;
    private int generation;
    private float az0 = 180f, alt0 = LOOK_UP;
    private Plate plate, prev;
    private long started, fadeAt = -1, storyAt;
    private int storyIndex;
    // meteors: start, direction (unit), length, when, how long
    private long meteorAt = -1, nextMeteor, meteorMs;
    private float mx, my, mdx, mdy, mlen;

    Sky(Context c) {
        super(c);
        setBackgroundColor(0xFF01030A);
        text.setColor(0xFFFFFFFF);
        text.setTypeface(Ui.medium);
        text.setLetterSpacing(0.22f);
        int s = 64;
        glow = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new RadialGradient(s / 2f, s / 2f, s / 2f, new int[]{0xFFFFFFFF, 0x55FFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.18f, 1f}, Shader.TileMode.CLAMP));
        new Canvas(glow).drawCircle(s / 2f, s / 2f, s / 2f, p);
    }

    /** Ready to show: the catalogue is there (it always is, unless the APK is damaged). */
    static boolean available(Context c) {
        return load(c);
    }

    @Override
    void start() {
        if (running) return;
        running = true;
        started = SystemClock.uptimeMillis();
        nextMeteor = started + 8_000 + rnd.nextInt(10_000);
        double[] look = chooseView(System.currentTimeMillis());
        az0 = (float) look[0];
        alt0 = (float) look[1];
        main.post(paintSoon);
        invalidate();
    }

    @Override
    void stop() {
        running = false;
        generation++;
        main.removeCallbacks(paintSoon);
        main.post(() -> {
            if (plate != null) plate.recycle();
            if (prev != null) prev.recycle();
            plate = prev = null;
        });
    }

    private final Runnable paintSoon = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            final int gen = generation, w = getWidth(), h = getHeight();
            if (w == 0 || h == 0) {
                main.postDelayed(this, 200);
                return;
            }
            final Context app = getContext().getApplicationContext();
            final long now = System.currentTimeMillis();
            worker.execute(() -> {
                Plate p;
                try {
                    p = paint(app, now, w, h);
                } catch (RuntimeException | OutOfMemoryError e) {
                    p = null;
                }
                final Plate made = p;
                main.post(() -> {
                    if (made == null) return;
                    if (gen != generation || !running) {
                        made.recycle();
                        return;
                    }
                    if (prev != null) prev.recycle();
                    prev = plate;
                    plate = made;
                    fadeAt = SystemClock.uptimeMillis();
                    if (prev == null) story(true);
                    invalidate();
                });
            });
            main.postDelayed(this, PLATE_MS);
        }
    };

    /**
     * Faces whatever is best tonight: the brightest planet, else the Moon, else the equator's side
     * of the sky. Azimuth, then how far up to look: low enough for the horizon, a little higher
     * when the best thing in the sky is high.
     */
    private static double[] chooseView(long t) {
        double jd = Astro.jd(t), lst = Astro.lst(jd, Sun.lon()), lat = Sun.lat();
        double[] o = new double[4], h = new double[2], pm = Astro.precession(jd);
        for (int p : new int[]{Astro.VENUS, Astro.JUPITER, Astro.SATURN, Astro.MARS}) {
            Astro.planet(p, jd, o);
            Astro.toDate(o[0], o[1], pm, o);
            Astro.horizontal(o[0], o[1], lst, lat, h);
            if (h[0] > 8 && h[0] < 75) return new double[]{h[1], tilt(h[0])};
        }
        Astro.moon(jd, o);
        Astro.horizontal(o[0], o[1], lst, lat, h);
        if (h[0] > 8 && h[0] < 75) return new double[]{h[1], tilt(h[0])};
        return new double[]{lat >= 0 ? 180 : 0, LOOK_UP};
    }

    /** Up just far enough for something high, never so far the hills leave the bottom of the screen. */
    private static double tilt(double alt) {
        return alt <= 40 ? LOOK_UP : Math.min(32, alt - 24);
    }

    // ---- painting a plate (worker thread) -----------------------------------------------------

    /** Where we look and how wide: a stereographic view, horizon low on the screen. */
    private static final class Look {
        final double az0, alt0, scale, cx, cy;

        Look(double az0, double alt0, int w, int h) {
            this.az0 = az0;
            this.alt0 = alt0;
            this.scale = (w / 2.0) / (2 * Math.tan(FOV / 4 * D));
            this.cx = w / 2.0;
            this.cy = h / 2.0;
        }

        /** Screen x, y of altitude {@code alt}, azimuth {@code az}; false when it's behind us. */
        boolean at(double alt, double az, float[] out, double behind) {
            double a = alt * D, z = (az - az0) * D, a0 = alt0 * D;
            double cosc = Math.sin(a0) * Math.sin(a) + Math.cos(a0) * Math.cos(a) * Math.cos(z);
            if (cosc < behind) return false;
            double k = 2 / (1 + cosc);
            out[0] = (float) (cx + k * Math.cos(a) * Math.sin(z) * scale);
            out[1] = (float) (cy - k * (Math.cos(a0) * Math.sin(a) - Math.sin(a0) * Math.cos(a) * Math.cos(z)) * scale);
            return true;
        }
    }

    private Plate paint(Context c, long t, int w, int h) {
        Plate p = new Plate();
        float s = h / 720f;
        double jd = Astro.jd(t), lst = Astro.lst(jd, Sun.lon()), lat = Sun.lat();
        double[] pm = Astro.precession(jd), o = new double[4], hz = new double[2];
        Look v = new Look(az0, alt0, w, h);
        float[] q = new float[2];

        Astro.sun(jd, o);
        Astro.horizontal(o[0], o[1], lst, lat, hz);
        double sunAlt = hz[0], sunAz = hz[1];

        p.bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(p.bitmap);
        Paint pa = new Paint(Paint.ANTI_ALIAS_FLAG);

        // the sky itself: night blue, or the real twilight while the Sun is near the horizon
        v.at(0, az0, q, -1);
        float horizonY = q[1];
        int[] sky = skyColours(sunAlt);
        pa.setShader(new LinearGradient(0, 0, 0, horizonY, sky[0], sky[1], Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, w, h, pa);
        if (sunAlt > -18 && v.at(0, sunAz, q, -0.6)) {
            float k = (float) Math.max(0, Math.min(1, (sunAlt + 18) / 18));
            pa.setShader(new RadialGradient(q[0], horizonY, w * 0.7f, Ui.withAlpha(0xFFFF9A5C, Math.round(150 * k * k)),
                    0x00FF9A5C, Shader.TileMode.CLAMP));
            cv.drawRect(0, 0, w, h, pa);
        }
        // the glow of towns along the horizon
        pa.setShader(new LinearGradient(0, horizonY - 110 * s, 0, horizonY, 0x00000000, 0x33A07858, Shader.TileMode.CLAMP));
        cv.drawRect(0, horizonY - 110 * s, w, horizonY + 2, pa);
        pa.setShader(null);

        milkyWay(cv, v, lst, lat, pm, w, h);

        // constellation figures, faint, under the stars
        Paint ln = new Paint(Paint.ANTI_ALIAS_FLAG);
        ln.setStrokeWidth(Math.max(1f, 1.1f * s));
        ln.setColor(0x24CFE0FF);
        float[] a = new float[2], b = new float[2];
        for (int i = 0; i < lines.length; i += 4) {
            if (!place(lines[i], lines[i + 1], pm, lst, lat, v, a, o, hz, -2)) continue;
            if (!place(lines[i + 2], lines[i + 3], pm, lst, lat, v, b, o, hz, -2)) continue;
            if (Math.abs(a[0] - b[0]) > w * 0.6f) continue;
            cv.drawLine(a[0], a[1], b[0], b[1], ln);
        }

        // faint stars in the picture; the bright ones are drawn live, so they can twinkle
        float[] bright = new float[5 * 200];
        int nb = 0;
        int visible = 0;
        for (int i = 0; i < stars.length; i += 4) {
            float mag = stars[i + 2];
            Astro.toDate(stars[i], stars[i + 1], pm, o);
            Astro.horizontal(o[0], o[1], lst, lat, hz);
            if (hz[0] < -1 || !v.at(hz[0], hz[1], q, 0)) continue;
            if (q[0] < -10 || q[0] > w + 10 || q[1] < -10 || q[1] > h + 10) continue;
            visible++;
            float ext = (float) Math.max(0.2, Math.min(1, (hz[0] + 0.5) / 10));
            float r = Math.max(0.55f, 2.7f - 0.42f * mag) * s;
            int col = starColour(stars[i + 3]);
            if (mag <= 2.6f && nb < 200) {
                bright[nb * 5] = q[0];
                bright[nb * 5 + 1] = q[1];
                bright[nb * 5 + 2] = r * 1.15f;
                bright[nb * 5 + 3] = Float.intBitsToFloat(Ui.withAlpha(col, Math.round(255 * ext)));
                bright[nb * 5 + 4] = rnd.nextFloat() * 6.28f;
                nb++;
                continue;
            }
            pa.setColor(Ui.withAlpha(col, Math.round(255 * ext * Math.max(0.28f, Math.min(1f, 1.3f - mag * 0.17f)))));
            cv.drawCircle(q[0], q[1], r, pa);
        }
        p.bright = bright;
        p.nBright = nb;

        horizon(cv, v, w, h, s);

        // planets
        float[] planetMag = {0, -4.2f, 0, 0.6f, -2.4f, 0.7f};
        int[] planetCol = {0, 0xFFFFFBE8, 0, 0xFFFFB08A, 0xFFFFF0D2, 0xFFFFE6AE};
        List<String[]> planetStories = new ArrayList<>();
        for (int pl : new int[]{Astro.VENUS, Astro.JUPITER, Astro.MARS, Astro.SATURN}) {
            Astro.planet(pl, jd, o);
            double au = o[2];
            Astro.toDate(o[0], o[1], pm, o);
            Astro.horizontal(o[0], o[1], lst, lat, hz);
            if (hz[0] < 0.5 || !v.at(hz[0], hz[1], q, 0) || q[0] < 20 || q[0] > w - 20 || q[1] < 20 || q[1] > h) continue;
            float r = Math.max(2.2f, 3.4f - 0.35f * planetMag[pl]) * s;
            p.planets.add(new float[]{q[0], q[1], r, Float.intBitsToFloat(planetCol[pl])});
            p.planetNames.add(Astro.PLANET_NAMES[pl].toUpperCase(java.util.Locale.ROOT));
            long mins = Math.round(au * 8.317);
            planetStories.add(new String[]{Astro.PLANET_NAMES[pl] + ", " + where(hz[0], hz[1]),
                    "Its light left it " + (mins >= 60 ? (mins / 60) + " h " + (mins % 60) + " min" : mins + " minutes")
                            + " ago, from " + Math.round(au * 149.6) + " million km away."});
        }

        // the Moon, its lit side towards the Sun
        Astro.moon(jd, o);
        Astro.horizontal(o[0], o[1], lst, lat, hz);
        double moonAlt = Astro.moonFromGround(hz[0]), moonAz = hz[1];
        double lit = Astro.moonLit(jd);
        boolean waxing = Astro.moonWaxing(jd);
        String[] moonStory = null;
        if (moonAlt > -0.3 && v.at(moonAlt, moonAz, q, 0) && q[0] > 30 && q[0] < w - 30 && q[1] > 30 && q[1] < h) {
            p.moon = true;
            p.moonX = q[0];
            p.moonY = q[1];
            float mxs = q[0], mys = q[1];
            // which way the Sun lies on screen: a step from the Moon along the sky towards it
            float ang = 0;
            if (v.at(moonAlt + (sunAlt - moonAlt) * 0.05, moonAz + Ui.clamp((float) (((sunAz - moonAz + 540) % 360) - 180), -90, 90) * 0.05, q, -1)) {
                ang = (float) Math.atan2(q[1] - mys, q[0] - mxs);
            }
            p.moonAngle = (float) Math.toDegrees(ang);
            p.moonBitmap = moonPicture(Math.round(15 * s), lit);
            moonStory = new String[]{phaseName(lit, waxing), Math.round(lit * 100) + "% lit, " + where(moonAlt, moonAz) + "."};
        }

        // constellation names worth showing: the well-known ones, comfortably above the horizon
        for (int pass = 1; pass <= 2 && p.labels.size() < 4; pass++) {
            for (Label l : CONSTELLATIONS) {
                if (l.rank != pass || p.labels.size() >= 4) continue;
                if (!place(l.ra, l.dec, pm, lst, lat, v, q, o, hz, 0) || hz[0] < 12) continue;
                if (q[0] < 80 * s || q[0] > w - 160 * s || q[1] < 60 * s || q[1] > horizonY - 60 * s) continue;
                p.labels.add(new float[]{q[0], q[1], rnd.nextFloat()});
                p.labelNames.add(l.name.toUpperCase(java.util.Locale.ROOT));
            }
        }

        // a meteor shower tonight?
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(t);
        String[] showerStory = null;
        for (Object[] sh : SHOWERS) {
            Calendar peak = Calendar.getInstance();
            peak.setTimeInMillis(t);
            peak.set(Calendar.MONTH, (Integer) sh[1] - 1);
            peak.set(Calendar.DAY_OF_MONTH, (Integer) sh[2]);
            long days = Math.abs((t - peak.getTimeInMillis()) / 86_400_000L);
            if (days > 2) continue;
            Astro.toDate((Double) sh[3], (Double) sh[4], pm, o);
            Astro.horizontal(o[0], o[1], lst, lat, hz);
            if (hz[0] < 0) continue;
            p.radiant = v.at(hz[0], hz[1], q, -0.8);
            p.radiantX = q[0];
            p.radiantY = q[1];
            p.showerRate = (Integer) sh[5];
            showerStory = new String[]{"The " + sh[0] + (days == 0 ? " peak tonight" : " are falling"),
                    "Up to " + sh[5] + " shooting stars an hour at their best, streaming from " + sh[6] + "."};
            break;
        }

        // what to say, best first
        if (showerStory != null) p.stories.add(showerStory);
        p.stories.addAll(planetStories);
        if (moonStory != null) p.stories.add(moonStory);
        Label best = null;
        double bestAlt = 0;
        for (Label l : NAMED) {
            if (!place(l.ra, l.dec, pm, lst, lat, v, q, o, hz, 0) || hz[0] < 10 || q[0] < 0 || q[0] > w || q[1] < 0 || q[1] > horizonY) continue;
            if (best == null || l.mag < best.mag) {
                best = l;
                bestAlt = hz[0];
                pt[0] = (float) hz[1];
            }
        }
        if (best != null) p.stories.add(new String[]{best.name + ", " + where(bestAlt, pt[0]), lightLine(best.name, cal.get(Calendar.YEAR))});
        if (!p.labelNames.isEmpty()) {
            String n = p.labelNames.get(0);
            p.stories.add(new String[]{n.charAt(0) + n.substring(1).toLowerCase(java.util.Locale.ROOT), "One of the " + visible + " stars above this horizon right now."});
        }
        if (p.stories.isEmpty()) p.stories.add(new String[]{"The stars above you", visible + " stars over the horizon right now."});

        String city = Weather.now(c).city;
        Weather.Now wx = Weather.now(c);
        boolean clouds = wx != null && wx.code >= 2;
        String when = sunAlt < -6 ? (clouds ? "Above tonight's clouds" : "Tonight's sky")
                : sunAlt < 0 ? (sunAz < 180 ? "The sky at dawn" : "The sky at dusk") : "The stars behind the daylight";
        p.kicker = when + (city == null || city.isEmpty() ? "" : "  ·  " + city);
        return p;
    }

    private static boolean place(double ra, double dec, double[] pm, double lst, double lat, Look v, float[] out,
                                 double[] o, double[] hz, double minAlt) {
        Astro.toDate(ra, dec, pm, o);
        Astro.horizontal(o[0], o[1], lst, lat, hz);
        return hz[0] >= minAlt && v.at(hz[0], hz[1], out, 0);
    }

    /** Zenith and horizon colours for the Sun's height: night, the three twilights, then dusk's glow. */
    private static int[] skyColours(double sunAlt) {
        int[][] stops = {
                {-90, 0xFF01030A, 0xFF0A1430},
                {-18, 0xFF01030A, 0xFF0B1632},
                {-12, 0xFF040A1C, 0xFF1A2850},
                {-6, 0xFF08143A, 0xFF3D3F6E},
                {-2, 0xFF183766, 0xFFB86A50},
                {6, 0xFF2E5C96, 0xFFE09A6A},
        };
        for (int i = 1; i < stops.length; i++) {
            if (sunAlt <= stops[i][0]) {
                float f = (float) ((sunAlt - stops[i - 1][0]) / (stops[i][0] - stops[i - 1][0]));
                return new int[]{Ui.mix(stops[i - 1][1], stops[i][1], f), Ui.mix(stops[i - 1][2], stops[i][2], f)};
            }
        }
        // in daylight the stars are still there, just hidden: show them on the evening's blue
        return new int[]{0xFF040A1C, 0xFF1A2850};
    }

    /** B−V colour index to a star's tint: blue-white for hot stars, through white, to orange and red. */
    private static int starColour(float bv) {
        if (bv < 0f) return Ui.mix(0xFFAFC8FF, 0xFFDDE6FF, Ui.clamp(bv + 0.4f, 0, 0.4f) / 0.4f);
        if (bv < 0.6f) return Ui.mix(0xFFDDE6FF, 0xFFFFF6E8, bv / 0.6f);
        if (bv < 1.4f) return Ui.mix(0xFFFFF6E8, 0xFFFFC98A, (bv - 0.6f) / 0.8f);
        return 0xFFFFB070;
    }

    /**
     * The Milky Way: soft light along the galactic plane, brightest towards the galactic centre in
     * Sagittarius and through Cygnus, painted small and enlarged, which also softens it.
     */
    private void milkyWay(Canvas cv, Look v, double lst, double lat, double[] pm, int w, int h) {
        int sw = Math.max(1, w / 4), shh = Math.max(1, h / 4);
        Bitmap small = Bitmap.createBitmap(sw, shh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(small);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Random r = new Random(7);
        double[] o = new double[2], hz = new double[2];
        float[] q = new float[2];
        double degPx = v.scale * 2 * Math.tan(0.5 * D) / 4; // pixels per degree in the small picture, at the centre
        for (double l = 0; l < 360; l += 1.5) {
            double dc = Math.min(Math.abs(l), 360 - Math.abs(l));
            double dens = 0.25 + 0.75 * Math.exp(-Math.pow(dc / 45, 2)) + 0.3 * Math.exp(-Math.pow((l - 80) / 15, 2));
            for (int k = 0; k < 3; k++) {
                double b = r.nextGaussian() * (4 + 3 * Math.exp(-Math.pow(dc / 30, 2)));
                galactic(l, b, o);
                Astro.toDate(o[0], o[1], pm, o);
                Astro.horizontal(o[0], o[1], lst, lat, hz);
                if (hz[0] < -2 || !v.at(hz[0], hz[1], q, 0)) continue;
                float x = q[0] / 4, y = q[1] / 4;
                if (x < -40 || x > sw + 40 || y < -40 || y > shh + 40) continue;
                // a dark rift splits it from Cygnus to Sagittarius
                double rift = (l < 60 || l > 340) && Math.abs(b - 1.5) < 1.6 ? 0.45 : 1;
                float ext = (float) Math.max(0.15, Math.min(1, (hz[0] + 1) / 15));
                float rad = (float) (degPx * (3 + r.nextFloat() * 4));
                int a = (int) Math.round(255 * 0.10 * dens * rift * ext * (0.6 + r.nextFloat() * 0.8));
                int col = Ui.mix(0xFFAFC0FF, 0xFFFFE2C2, (float) Math.exp(-Math.pow(dc / 35, 2)));
                p.setShader(new RadialGradient(x, y, Math.max(1, rad), Ui.withAlpha(col, Math.min(255, a)), Ui.withAlpha(col, 0), Shader.TileMode.CLAMP));
                c.drawCircle(x, y, rad, p);
            }
        }
        Paint up = new Paint(Paint.FILTER_BITMAP_FLAG);
        cv.drawBitmap(small, null, new android.graphics.Rect(0, 0, w, h), up);
        small.recycle();
    }

    /** Galactic longitude and latitude to J2000 right ascension and declination. */
    private static void galactic(double l, double b, double[] out) {
        double lr = l * D, br = b * D;
        double x = Math.cos(br) * Math.cos(lr), y = Math.cos(br) * Math.sin(lr), z = Math.sin(br);
        // the transpose of the J2000 equatorial-to-galactic rotation
        double ex = -0.0548755604 * x + 0.4941094279 * y - 0.8676661490 * z;
        double ey = -0.8734370902 * x - 0.4448296300 * y - 0.1980763734 * z;
        double ez = -0.4838350155 * x + 0.7469822445 * y + 0.4559837762 * z;
        out[0] = Astro.norm(Math.atan2(ey, ex) / D);
        out[1] = Math.asin(Math.max(-1, Math.min(1, ez))) / D;
    }

    /** A dark ridge of hills along the true horizon, so the sky sits over somewhere. */
    private void horizon(Canvas cv, Look v, int w, int h, float s) {
        Path path = new Path();
        float[] q = new float[2];
        boolean first = true;
        float lastX = 0;
        for (double az = az0 - 85; az <= az0 + 85; az += 1.2) {
            if (!v.at(0, az, q, -0.95)) continue;
            double hill = (Math.sin(az * 0.071 + 1.3) * 0.5 + Math.sin(az * 0.193 + 0.4) * 0.3 + Math.sin(az * 0.47) * 0.2);
            float y = (float) (q[1] - (8 + 10 * hill) * s);
            if (first) {
                path.moveTo(q[0], h);
                path.lineTo(q[0], y);
                first = false;
            } else {
                path.lineTo(q[0], y);
            }
            lastX = q[0];
        }
        if (first) return;
        path.lineTo(lastX, h);
        path.close();
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, h * 0.75f, 0, h, 0xFF06080E, 0xFF010204, Shader.TileMode.CLAMP));
        cv.drawPath(path, p);
    }

    /** The Moon, lit from the right (+x) by {@code lit}; it's turned towards the Sun when drawn. */
    private static Bitmap moonPicture(int r, double lit) {
        int size = r * 2 + 4;
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        float cx = size / 2f;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        // the unlit part, faint with earthshine
        p.setColor(0x33AAB4C8);
        c.drawCircle(cx, cx, r, p);
        // the lit part: the right half, bounded by the terminator's ellipse
        Path lit2 = new Path();
        int steps = 32;
        for (int i = 0; i <= steps; i++) {
            double a = -Math.PI / 2 + Math.PI * i / steps;
            float x = (float) (cx + r * Math.cos(a)), y = (float) (cx + r * Math.sin(a));
            if (i == 0) lit2.moveTo(x, y);
            else lit2.lineTo(x, y);
        }
        double k = 1 - 2 * lit;
        for (int i = steps; i >= 0; i--) {
            double a = -Math.PI / 2 + Math.PI * i / steps;
            float x = (float) (cx + k * r * Math.cos(a)), y = (float) (cx + r * Math.sin(a));
            lit2.lineTo(x, y);
        }
        lit2.close();
        p.setShader(new RadialGradient(cx + r * 0.25f, cx - r * 0.2f, r * 1.3f, 0xFFFFFCF2, 0xFFE4DECF, Shader.TileMode.CLAMP));
        c.drawPath(lit2, p);
        p.setShader(null);
        // a few soft seas
        p.setColor(0x22000000);
        c.drawCircle(cx - r * 0.25f, cx - r * 0.3f, r * 0.28f, p);
        c.drawCircle(cx + r * 0.2f, cx - r * 0.1f, r * 0.2f, p);
        c.drawCircle(cx + r * 0.05f, cx + r * 0.35f, r * 0.22f, p);
        return b;
    }

    private static String phaseName(double lit, boolean waxing) {
        if (lit < 0.03) return "A new moon";
        if (lit > 0.97) return "A full moon";
        if (Math.abs(lit - 0.5) < 0.06) return waxing ? "A first-quarter moon" : "A last-quarter moon";
        return (waxing ? "A waxing " : "A waning ") + (lit < 0.5 ? "crescent" : "gibbous") + " moon";
    }

    /** "high in the south-east", "low in the west", from altitude and azimuth. */
    private static String where(double alt, double az) {
        String[] dirs = {"north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west"};
        String d = dirs[(int) Math.round(Astro.norm(az) / 45) % 8];
        return (alt > 55 ? "high in the " : alt < 18 ? "low in the " : "in the ") + d;
    }

    /** "The light you see set out in 2001." from the star's distance in light-years. */
    private static String lightLine(String name, int year) {
        for (int i = 0; i < LY_NAMES.length; i++) {
            if (!LY_NAMES[i].equals(name)) continue;
            float ly = LY[i];
            int then = Math.round(year - ly);
            String when = then > 0 ? "in " + then : "around " + (-then + 1) + " BC";
            return "The light you're seeing set out " + when + ", " + (ly < 20 ? String.format(java.util.Locale.ROOT, "%.1f", ly) : String.valueOf(Math.round(ly)))
                    + " light-years away.";
        }
        return "One of the brightest stars in the sky.";
    }

    // ---- live drawing (main thread) -----------------------------------------------------------

    private void story(boolean first) {
        Plate p = plate;
        if (p == null || listener == null || p.stories.isEmpty()) return;
        String[] st = p.stories.get(storyIndex % p.stories.size());
        storyIndex++;
        storyAt = SystemClock.uptimeMillis();
        listener.onStory(p.kicker, st[0], st[1], CREDIT, STORY_MS);
    }

    @Override
    protected void onDraw(Canvas c) {
        long now = SystemClock.uptimeMillis();
        int w = getWidth(), h = getHeight();
        if (plate == null || plate.bitmap == null) {
            if (running) postInvalidateDelayed(200);
            return;
        }
        // the slowest drift, in and out over four minutes, so nothing sits on the same pixels
        float f = (float) ((Math.sin((now - started) / 240_000.0 * Math.PI * 2) + 1) / 2);
        float sc = 1f + 0.035f * f;
        drift.setScale(sc, sc, w / 2f, h * 0.8f);
        drift.postTranslate((float) Math.sin((now - started) / 410_000.0 * Math.PI * 2) * 10f * h / 720f, 0);
        c.save();
        c.concat(drift);
        float fade = fadeAt < 0 ? 1f : Math.min(1f, (now - fadeAt) / (float) FADE_MS);
        if (prev != null && prev.bitmap != null && fade < 1f) {
            paint.setAlpha(255);
            c.drawBitmap(prev.bitmap, 0, 0, paint);
            live(c, prev, 1f - fade, now);
        }
        paint.setAlpha(Math.round(255 * fade));
        c.drawBitmap(plate.bitmap, 0, 0, paint);
        paint.setAlpha(255);
        live(c, plate, fade, now);
        meteor(c, now, h / 720f);
        c.restore();
        if (fade >= 1f && prev != null) {
            prev.recycle();
            prev = null;
        }
        if (running && now - storyAt > STORY_MS) story(false);
        if (!running) return;
        if (fade < 1f || meteorAt >= 0) postInvalidateOnAnimation();
        else postInvalidateDelayed(66);
    }

    private void live(Canvas c, Plate p, float alpha, long now) {
        float s = getHeight() / 720f;
        // bright stars, twinkling, more so near the horizon where the air is thick
        for (int i = 0; i < p.nBright; i++) {
            float x = p.bright[i * 5], y = p.bright[i * 5 + 1], r = p.bright[i * 5 + 2], seed = p.bright[i * 5 + 4];
            int col = Float.floatToRawIntBits(p.bright[i * 5 + 3]);
            float depth = Ui.clamp((y / getHeight() - 0.3f) / 0.5f, 0f, 1f);
            float tw = 1f - (0.12f + 0.25f * depth) * (0.5f + 0.5f * (float) Math.sin(now * 0.0021 * (1 + seed * 0.3) + seed * 7));
            int a = Math.round(Color.alpha(col) * alpha * tw);
            paint.setColorFilter(new android.graphics.PorterDuffColorFilter(col | 0xFF000000, android.graphics.PorterDuff.Mode.SRC_IN));
            paint.setAlpha(Math.round(a * 0.55f));
            float g = r * 5f;
            c.drawBitmap(glow, null, new android.graphics.RectF(x - g, y - g, x + g, y + g), paint);
            paint.setColorFilter(null);
            paint.setColor(col);
            paint.setAlpha(a);
            c.drawCircle(x, y, r, paint);
        }
        // planets: steady light, and their names
        text.setTextSize(9f * s);
        for (int i = 0; i < p.planets.size(); i++) {
            float[] q = p.planets.get(i);
            int col = Float.floatToRawIntBits(q[3]);
            paint.setColorFilter(new android.graphics.PorterDuffColorFilter(col, android.graphics.PorterDuff.Mode.SRC_IN));
            paint.setAlpha(Math.round(170 * alpha));
            float g = q[2] * 6f;
            c.drawBitmap(glow, null, new android.graphics.RectF(q[0] - g, q[1] - g, q[0] + g, q[1] + g), paint);
            paint.setColorFilter(null);
            paint.setColor(col);
            paint.setAlpha(Math.round(255 * alpha));
            c.drawCircle(q[0], q[1], q[2], paint);
            text.setAlpha(Math.round(150 * alpha));
            c.drawText(p.planetNames.get(i), q[0] + q[2] + 10 * s, q[1] + 3 * s, text);
        }
        // the Moon
        if (p.moon && p.moonBitmap != null) {
            paint.setColorFilter(null);
            paint.setAlpha(Math.round(90 * alpha));
            float g = p.moonBitmap.getWidth() * 2.4f;
            c.drawBitmap(glow, null, new android.graphics.RectF(p.moonX - g, p.moonY - g, p.moonX + g, p.moonY + g), paint);
            paint.setAlpha(Math.round(255 * alpha));
            m.setTranslate(-p.moonBitmap.getWidth() / 2f, -p.moonBitmap.getHeight() / 2f);
            m.postRotate(p.moonAngle);
            m.postTranslate(p.moonX, p.moonY);
            c.drawBitmap(p.moonBitmap, m, paint);
        }
        // constellation names, each fading in and out on its own slow cycle
        text.setTextSize(8.5f * s);
        for (int i = 0; i < p.labels.size(); i++) {
            float[] q = p.labels.get(i);
            double ph = ((now - started) / 26_000.0 + q[2]) % 1.0;
            float a = (float) Math.max(0, Math.sin(ph * Math.PI * 2));
            if (a <= 0.01f) continue;
            text.setAlpha(Math.round(110 * a * alpha));
            c.drawText(p.labelNames.get(i), q[0], q[1], text);
        }
        paint.setAlpha(255);
    }

    /** Now and then a shooting star: a bright head and a fading trail, away from the shower's radiant on its nights. */
    private void meteor(Canvas c, long now, float s) {
        int w = getWidth(), h = getHeight();
        Plate p = plate;
        if (meteorAt < 0 && now >= nextMeteor && p != null) {
            meteorAt = now;
            meteorMs = 520 + rnd.nextInt(420);
            mlen = (150 + rnd.nextInt(170)) * s;
            float hy = h * 0.62f;
            if (p.radiant) {
                // anywhere in the sky, travelling directly away from the radiant
                mx = w * (0.15f + rnd.nextFloat() * 0.7f);
                my = h * (0.08f + rnd.nextFloat() * 0.45f);
                float dx = mx - p.radiantX, dy = my - p.radiantY, n = (float) Math.hypot(dx, dy);
                mdx = n > 1 ? dx / n : 1;
                mdy = n > 1 ? dy / n : 0;
            } else {
                mx = w * (0.1f + rnd.nextFloat() * 0.8f);
                my = h * (0.06f + rnd.nextFloat() * 0.35f);
                double ang = Math.toRadians(20 + rnd.nextInt(40)) * (rnd.nextBoolean() ? 1 : -1);
                mdx = (float) Math.cos(ang) * (rnd.nextBoolean() ? 1 : -1);
                mdy = (float) Math.abs(Math.sin(ang));
            }
            if (my > hy) my = hy;
            int rate = p.radiant ? Math.max(20, p.showerRate) : 6;
            long gap = 3_600_000L / rate;
            nextMeteor = now + gap / 2 + (long) (rnd.nextFloat() * gap);
        }
        if (meteorAt < 0) return;
        float t = (now - meteorAt) / (float) meteorMs;
        if (t >= 1f) {
            meteorAt = -1;
            return;
        }
        float head = (float) (1 - Math.pow(1 - t, 2)) * mlen, tail = Math.max(0, head - mlen * 0.55f * Math.min(1, t * 2.5f));
        float fade = t < 0.75f ? 1f : (1 - t) / 0.25f;
        paint.setStrokeCap(Paint.Cap.ROUND);
        // the trail as a few strokes, brightest at the head
        int seg = 6;
        for (int i = 0; i < seg; i++) {
            float a0 = tail + (head - tail) * i / seg, a1 = tail + (head - tail) * (i + 1) / seg;
            paint.setColor(0xFFFFFFFF);
            paint.setAlpha(Math.round(230 * fade * (i + 1) / seg));
            paint.setStrokeWidth((0.8f + 1.4f * (i + 1) / seg) * s);
            c.drawLine(mx + mdx * a0, my + mdy * a0, mx + mdx * a1, my + mdy * a1, paint);
        }
        paint.setStrokeWidth(0);
        paint.setAlpha(Math.round(200 * fade));
        float g = 9 * s;
        float hx = mx + mdx * head, hy2 = my + mdy * head;
        c.drawBitmap(glow, null, new android.graphics.RectF(hx - g, hy2 - g, hx + g, hy2 + g), paint);
        paint.setAlpha(255);
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        worker.shutdown();
        super.onDetachedFromWindow();
    }
}
