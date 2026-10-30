package app.aura.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.net.ConnectivityManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.Random;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The real wind around the TV's city, right now, as flowing light: a few thousand particles drift
 * through the measured wind field over a dark map, leaving fading trails, teal where it's calm,
 * warm where it's strong. The field comes from Open-Meteo (free, no key) on a 3° grid, at most once
 * an hour and only while the screensaver runs; between fetches it's kept, so a rest starts at once.
 *
 * Light to draw: each particle remembers its last few positions, and every trail of the same age
 * and speed goes to the GPU in one batched call, a few dozen calls a frame.
 */
final class Wind extends Scene {
    private static final double STEP = 3.0, SPAN_LON = 46;
    private static final long FRESH_MS = 60 * 60_000L, STORY_MS = 40_000;
    private static final int N = 2400, TRAIL = 10, BUCKETS = 3;
    private static final String UA = "AuraTV/7.4 (open-source Android TV home screen; live wind screensaver)";
    private static final int[] COLOURS = {0xFF3FD2D0, 0xFFB4F0DC, 0xFFFFD08A};

    private static float[][] land, borders;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Random rnd = new Random();
    private final float[] px = new float[N * TRAIL], py = new float[N * TRAIL], life = new float[N], speed = new float[N];
    private final float[][] seg = new float[BUCKETS * TRAIL][];
    private final int[] segN = new int[BUCKETS * TRAIL];
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), plain = new Paint(Paint.FILTER_BITMAP_FLAG),
            dot = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);
    // the field, km/h east and north, on a grid from (lon0, lat0)
    private volatile float[][] u, v;
    private volatile long fetchedAt;
    private double lon0, lat0, lonSpan, latSpan;
    private Bitmap map;
    private boolean running;
    private int generation, storyN;
    private long started, storyAt;

    Wind(Context c) {
        super(c);
        setBackgroundColor(0xFF03060D);
        line.setStrokeCap(Paint.Cap.ROUND);
        text.setColor(0xFFFFFFFF);
        text.setTypeface(Ui.medium);
        for (int i = 0; i < seg.length; i++) seg[i] = new float[N * 4];
    }

    /** Worth choosing: there's a field kept from earlier, or the internet to fetch one. */
    static boolean available(Context c) {
        if (System.currentTimeMillis() - Prefs.device(c).getLong("wind_at", 0) < 6 * FRESH_MS) return true;
        return Compat.online(Compat.svc(c, ConnectivityManager.class));
    }

    @Override
    void start() {
        running = true;
        generation++;
        started = SystemClock.uptimeMillis();
        int w = getWidth(), h = getHeight();
        if (w == 0) {
            post(this::start);
            return;
        }
        // a box around the city, a little more of it to the south, as wide as a country
        double clat = Sun.lat(), clon = Sun.lon();
        lonSpan = SPAN_LON;
        latSpan = lonSpan * h / w * Math.cos(Math.toRadians(clat));
        lon0 = clon - lonSpan / 2;
        lat0 = clat - latSpan * 0.66;
        for (int i = 0; i < N; i++) spawn(i, true);
        final int gen = generation;
        final Context app = getContext().getApplicationContext();
        worker.execute(() -> {
            Bitmap m = paintMap(app, w, h);
            restore(app);
            main.post(() -> {
                if (gen != generation || !running) {
                    m.recycle();
                    return;
                }
                map = m;
                story();
                invalidate();
            });
            if (System.currentTimeMillis() - fetchedAt > FRESH_MS) fetch(app, gen);
        });
        invalidate();
    }

    @Override
    void stop() {
        running = false;
        generation++;
        main.post(() -> {
            if (map != null) map.recycle();
            map = null;
        });
    }

    // ---- the field ----------------------------------------------------------------------------

    private int cols() {
        return (int) Math.ceil(lonSpan / STEP) + 1;
    }

    private int rows() {
        return (int) Math.ceil(latSpan / STEP) + 1;
    }

    /** The field kept from the last fetch, if it covers this box. */
    private void restore(Context c) {
        SharedPreferences p = Prefs.device(c);
        try {
            if (Math.abs(p.getFloat("wind_lon0", 999) - lon0) > 0.01 || Math.abs(p.getFloat("wind_lat0", 999) - lat0) > 0.01) return;
            JSONArray a = new JSONArray(p.getString("wind_field", "[]"));
            int r = rows(), cl = cols();
            if (a.length() != r * cl * 2) return;
            float[][] uu = new float[r][cl], vv = new float[r][cl];
            for (int y = 0; y < r; y++) {
                for (int x = 0; x < cl; x++) {
                    uu[y][x] = (float) a.getDouble((y * cl + x) * 2);
                    vv[y][x] = (float) a.getDouble((y * cl + x) * 2 + 1);
                }
            }
            u = uu;
            v = vv;
            fetchedAt = p.getLong("wind_at", 0);
        } catch (org.json.JSONException ignored) {
        }
    }

    /** Off the main thread: the current wind at every grid point, in requests of 80 places. */
    private void fetch(Context c, int gen) {
        int r = rows(), cl = cols(), n = r * cl;
        float[][] uu = new float[r][cl], vv = new float[r][cl];
        try {
            for (int from = 0; from < n; from += 80) {
                StringBuilder la = new StringBuilder(), lo = new StringBuilder();
                int to = Math.min(n, from + 80);
                for (int k = from; k < to; k++) {
                    if (k > from) {
                        la.append(',');
                        lo.append(',');
                    }
                    la.append(String.format(Locale.ROOT, "%.1f", lat0 + (k / cl) * STEP));
                    lo.append(String.format(Locale.ROOT, "%.1f", lon0 + (k % cl) * STEP));
                }
                String url = "https://api.open-meteo.com/v1/forecast?latitude=" + la + "&longitude=" + lo
                        + "&current=wind_speed_10m,wind_direction_10m";
                JSONArray res = new JSONArray(get(url));
                for (int k = from; k < to; k++) {
                    JSONObject cur = res.getJSONObject(k - from).getJSONObject("current");
                    double sp = cur.getDouble("wind_speed_10m"), dir = Math.toRadians(cur.getDouble("wind_direction_10m"));
                    // the direction is where it blows from
                    uu[k / cl][k % cl] = (float) (-sp * Math.sin(dir));
                    vv[k / cl][k % cl] = (float) (-sp * Math.cos(dir));
                }
                if (gen != generation) return;
                Thread.sleep(400);
            }
        } catch (IOException | org.json.JSONException | InterruptedException | RuntimeException e) {
            // keep what we had; try again in a few minutes while the scene is up
            main.postDelayed(() -> {
                if (running && gen == generation) worker.execute(() -> fetch(c, gen));
            }, 5 * 60_000L);
            return;
        }
        u = uu;
        v = vv;
        fetchedAt = System.currentTimeMillis();
        JSONArray keep = new JSONArray();
        try {
            for (int y = 0; y < r; y++) {
                for (int x = 0; x < cl; x++) {
                    keep.put(Math.round(uu[y][x] * 10) / 10.0);
                    keep.put(Math.round(vv[y][x] * 10) / 10.0);
                }
            }
        } catch (org.json.JSONException ignored) {
        }
        Prefs.device(c).edit().putString("wind_field", keep.toString()).putLong("wind_at", fetchedAt)
                .putFloat("wind_lon0", (float) lon0).putFloat("wind_lat0", (float) lat0).apply();
        main.post(() -> {
            if (running && gen == generation) story();
        });
        main.postDelayed(() -> {
            if (running && gen == generation) worker.execute(() -> fetch(c, gen));
        }, FRESH_MS);
    }

    private static String get(String url) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setRequestProperty("User-Agent", UA);
        con.setConnectTimeout(15_000);
        con.setReadTimeout(20_000);
        try {
            if (con.getResponseCode() != 200) throw new IOException("HTTP " + con.getResponseCode());
            try (InputStream in = con.getInputStream()) {
                java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                for (int k; (k = in.read(buf)) > 0; ) b.write(buf, 0, k);
                return new String(b.toByteArray(), "UTF-8");
            }
        } finally {
            con.disconnect();
        }
    }

    /** The wind (km/h east, north) at a place, smoothly between grid points; zero outside. */
    private void at(double lon, double lat, float[] out) {
        float[][] uu = u, vv = v;
        if (uu == null) {
            out[0] = out[1] = 0;
            return;
        }
        double gx = (lon - lon0) / STEP, gy = (lat - lat0) / STEP;
        int x0 = (int) Math.floor(gx), y0 = (int) Math.floor(gy);
        if (x0 < 0 || y0 < 0 || x0 >= uu[0].length - 1 || y0 >= uu.length - 1) {
            out[0] = out[1] = 0;
            return;
        }
        float tx = (float) (gx - x0), ty = (float) (gy - y0);
        out[0] = lerp2(uu, x0, y0, tx, ty);
        out[1] = lerp2(vv, x0, y0, tx, ty);
    }

    private static float lerp2(float[][] f, int x, int y, float tx, float ty) {
        return f[y][x] * (1 - tx) * (1 - ty) + f[y][x + 1] * tx * (1 - ty) + f[y + 1][x] * (1 - tx) * ty + f[y + 1][x + 1] * tx * ty;
    }

    // ---- the map, painted once ----------------------------------------------------------------

    private float sx(double lon) {
        return (float) ((lon - lon0) / lonSpan * getWidth());
    }

    private float sy(double lat) {
        return (float) ((lat0 + latSpan - lat) / latSpan * getHeight());
    }

    private static synchronized void loadMap(Context c) throws IOException {
        if (land != null) return;
        land = rings(c, "map/land.bin");
        borders = rings(c, "map/borders.bin");
    }

    private static float[][] rings(Context c, String asset) throws IOException {
        try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(c.getAssets().open(asset)))) {
            float[][] out = new float[in.readInt()][];
            for (int i = 0; i < out.length; i++) {
                float[] r = new float[in.readInt() * 2];
                for (int k = 0; k < r.length; k++) r[k] = in.readFloat();
                out[i] = r;
            }
            return out;
        }
    }

    private Bitmap paintMap(Context c, int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, 0, h, 0xFF03060D, 0xFF060B16, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, w, h, p);
        p.setShader(null);
        try {
            loadMap(c);
        } catch (IOException e) {
            return b;
        }
        p.setColor(0xFF0B111D);
        Paint coast = new Paint(Paint.ANTI_ALIAS_FLAG);
        coast.setStyle(Paint.Style.STROKE);
        coast.setStrokeWidth(Math.max(1f, h / 720f));
        coast.setColor(0x26C8DCFF);
        for (float[] r : land) {
            Path path = path(r, true);
            if (path == null) continue;
            cv.drawPath(path, p);
            cv.drawPath(path, coast);
        }
        coast.setColor(0x33C8DCFF);
        for (float[] r : borders) {
            Path path = path(r, false);
            if (path != null) cv.drawPath(path, coast);
        }
        return b;
    }

    /** A ring or line in screen space, or null when it's nowhere near the box. */
    private Path path(float[] r, boolean closed) {
        boolean near = false;
        for (int k = 0; k < r.length; k += 2) {
            if (r[k] > lon0 - 30 && r[k] < lon0 + lonSpan + 30 && r[k + 1] > lat0 - 30 && r[k + 1] < lat0 + latSpan + 30) {
                near = true;
                break;
            }
        }
        if (!near) return null;
        Path p = new Path();
        p.moveTo(sx(r[0]), sy(r[1]));
        for (int k = 2; k < r.length; k += 2) p.lineTo(sx(r[k]), sy(r[k + 1]));
        if (closed) p.close();
        return p;
    }

    // ---- the particles ------------------------------------------------------------------------

    private void spawn(int i, boolean anywhere) {
        float x = rnd.nextFloat() * getWidth(), y = rnd.nextFloat() * getHeight();
        for (int k = 0; k < TRAIL; k++) {
            px[i * TRAIL + k] = x;
            py[i * TRAIL + k] = y;
        }
        life[i] = (anywhere ? rnd.nextFloat() : 1f) * (80 + rnd.nextInt(140));
    }

    private final float[] wv = new float[2];

    private void step(float dt) {
        int w = getWidth(), h = getHeight();
        // pixels per frame per km/h: 15 km/h travels about a pixel a frame at 30 fps
        float k = w / 1280f * 0.065f * dt * 30;
        for (int i = 0; i < N; i++) {
            int o = i * TRAIL;
            System.arraycopy(px, o, px, o + 1, TRAIL - 1);
            System.arraycopy(py, o, py, o + 1, TRAIL - 1);
            double lon = lon0 + px[o] / w * lonSpan, lat = lat0 + latSpan - py[o] / h * latSpan;
            at(lon, lat, wv);
            float s = (float) Math.hypot(wv[0], wv[1]);
            speed[i] = s;
            px[o] += wv[0] * k / (float) Math.cos(Math.toRadians(lat)) * (float) (latSpan / lonSpan * w / h);
            py[o] -= wv[1] * k;
            life[i] -= dt * 30;
            if (life[i] <= 0 || s < 0.3f || px[o] < 0 || px[o] > w || py[o] < 0 || py[o] > h) spawn(i, false);
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (map == null) {
            if (running) postInvalidateDelayed(100);
            return;
        }
        long now = SystemClock.uptimeMillis();
        float s = h / 720f;
        c.drawBitmap(map, 0, 0, plain);
        if (u != null) {
            step(1f);
            for (int i = 0; i < segN.length; i++) segN[i] = 0;
            for (int i = 0; i < N; i++) {
                int b = speed[i] < 10 ? 0 : speed[i] < 25 ? 1 : 2, o = i * TRAIL;
                for (int a = 0; a < TRAIL - 1; a++) {
                    float x0 = px[o + a], y0 = py[o + a], x1 = px[o + a + 1], y1 = py[o + a + 1];
                    if (x0 == x1 && y0 == y1) continue;
                    float[] arr = seg[b * TRAIL + a];
                    int n = segN[b * TRAIL + a];
                    arr[n] = x0;
                    arr[n + 1] = y0;
                    arr[n + 2] = x1;
                    arr[n + 3] = y1;
                    segN[b * TRAIL + a] = n + 4;
                }
            }
            // a soft halo under the newest part of each trail, then the trails themselves, fading with age
            for (int pass = 0; pass < 2; pass++) {
                line.setStrokeWidth((pass == 0 ? 3.2f : 1.1f) * s);
                for (int b = 0; b < BUCKETS; b++) {
                    for (int a = 0; a < TRAIL - 1; a++) {
                        int n = segN[b * TRAIL + a];
                        if (n == 0 || pass == 0 && a > 3) continue;
                        float fade = 1f - a / (float) (TRAIL - 1);
                        line.setColor(COLOURS[b]);
                        line.setAlpha(Math.round((pass == 0 ? 40 : 210) * fade * fade));
                        c.drawLines(seg[b * TRAIL + a], 0, n, line);
                    }
                }
            }
        }
        // the city, marked
        float cx = sx(Sun.lon()), cy = sy(Sun.lat());
        dot.setColor(0xE6FFFFFF);
        c.drawCircle(cx, cy, 4 * s, dot);
        dot.setStyle(Paint.Style.STROKE);
        dot.setStrokeWidth(Math.max(1f, s));
        dot.setColor(0x59FFFFFF);
        c.drawCircle(cx, cy, 12 * s, dot);
        dot.setStyle(Paint.Style.FILL);
        if (running && now - storyAt > STORY_MS) story();
        if (running) postInvalidateDelayed(33);
    }

    // ---- what to say --------------------------------------------------------------------------

    private void story() {
        if (listener == null) return;
        storyAt = SystemClock.uptimeMillis();
        String city = Weather.now(getContext()).city;
        String where = TimeZone.getDefault().getID().matches("Asia/(Kolkata|Calcutta)") ? "India" : city == null || city.isEmpty() ? "you" : city;
        String when = fetchedAt == 0 ? "" : "Updated " + android.text.format.DateFormat.getTimeFormat(getContext()).format(new java.util.Date(fetchedAt));
        if (u == null) {
            listener.onStory("Open-Meteo", "The wind over " + where, "Fetching the wind as it blows right now", "", STORY_MS);
            return;
        }
        int pick = storyN++ % 3;
        if (pick == 1 && city != null && !city.isEmpty()) {
            at(Sun.lon(), Sun.lat(), wv);
            double sp = Math.hypot(wv[0], wv[1]);
            listener.onStory(when, city + ", right now", Math.round(sp) + " km/h from the " + from(wv[0], wv[1]), "Open-Meteo", STORY_MS);
            return;
        }
        if (pick == 2) {
            float best = 0;
            double bl = 0, bt = 0;
            for (int y = 0; y < u.length; y++) {
                for (int x = 0; x < u[0].length; x++) {
                    float sp = (float) Math.hypot(u[y][x], v[y][x]);
                    if (sp > best) {
                        best = sp;
                        bl = lon0 + x * STEP;
                        bt = lat0 + y * STEP;
                    }
                }
            }
            double km = 111 * Math.hypot((bl - Sun.lon()) * Math.cos(Math.toRadians(Sun.lat())), bt - Sun.lat());
            String dir = compass(Math.toDegrees(Math.atan2((bl - Sun.lon()) * Math.cos(Math.toRadians(Sun.lat())), bt - Sun.lat())));
            listener.onStory(when, "Strongest on the map: " + Math.round(best) + " km/h",
                    "About " + (Math.round(km / 50) * 50) + " km " + dir + " of " + (city == null || city.isEmpty() ? "you" : city), "Open-Meteo", STORY_MS);
            return;
        }
        listener.onStory(when, "The wind over " + where + ", right now", "Every line is real wind, measured within the hour", "Open-Meteo", STORY_MS);
    }

    /** Where a wind of (east, north) km/h blows from, as a compass point. */
    private static String from(float e, float n) {
        return compass(Math.toDegrees(Math.atan2(-e, -n)));
    }

    private static String compass(double deg) {
        String[] d = {"north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west"};
        return d[(int) Math.round(((deg % 360) + 360) % 360 / 45) % 8];
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        worker.shutdown();
        super.onDetachedFromWindow();
    }
}
