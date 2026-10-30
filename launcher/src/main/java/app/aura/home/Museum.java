package app.aura.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.net.ConnectivityManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.format.DateFormat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A museum on the wall: masterpieces from the Art Institute of Chicago's open-access collection
 * (public domain, CC0), one room a rest: Rajasthan and the Mughal court, the Impressionists, the
 * Post-Impressionists, Japanese prints. Each work hangs in a room lit for it (the wall takes its
 * colours, a light falls from above), in a thin frame on an ivory mat, with a museum's wall label
 * and a small clock beside it. A new work every minute or so, cross-fading.
 *
 * The catalogue is fetched once and kept for two weeks; images come one at a time at the museum's
 * recommended size (843 px wide) and the last sixteen are kept on the TV.
 */
final class Museum extends Scene {
    /** Room name, then searches; every work must also match the room's filter (see {@link #fits}). */
    private static final String[][] ROOMS = {
            {"Rajasthan and the Mughal court", "Jodhpur", "Mewar", "Kota", "Bundi", "Kishangarh", "Bikaner", "Mughal", "Rajput"},
            {"The Impressionists", "Monet", "Renoir", "Degas", "Pissarro", "Caillebotte", "Cassatt", "Morisot", "Sisley"},
            {"After Impressionism", "van Gogh", "Seurat", "Gauguin", "Cézanne", "Toulouse-Lautrec", "Bonnard", "Vuillard"},
            {"Japanese prints", "Hokusai", "Hiroshige", "Utamaro", "Kuniyoshi", "Hasui"},
    };
    private static final long SHOW_MS = 70_000, FADE_MS = 1_800, INDEX_MS = 14 * 86_400_000L;
    private static final int KEEP = 16;
    private static final String API = "https://api.artic.edu/api/v1/artworks/search?";
    private static final String UA = "AuraTV/7.4 (open-source Android TV home screen; museum screensaver)";

    private static final class Work {
        String id, image, title, artist, date, medium;
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Paint plain = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final TextPaint artistP = new TextPaint(Paint.ANTI_ALIAS_FLAG), titleP = new TextPaint(Paint.ANTI_ALIAS_FLAG),
            smallP = new TextPaint(Paint.ANTI_ALIAS_FLAG), timeP = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final List<Work> deck = new ArrayList<>();
    private Bitmap room, prevRoom;
    private Work work, prevWork;
    private String roomName = "";
    private boolean running;
    private int generation, dealt;
    private long shownAt, fadeAt = -1;

    Museum(Context c) {
        super(c);
        setBackgroundColor(0xFF141210);
        artistP.setTypeface(Ui.semibold);
        artistP.setColor(0xFFF2EEE6);
        titleP.setTypeface(Ui.regular);
        titleP.setColor(0xFFEAE6DE);
        smallP.setTypeface(Ui.regular);
        timeP.setTypeface(Ui.thin);
        timeP.setColor(0xF5F5F2EB);
    }

    @Override
    boolean ownClock() {
        return true;
    }

    /** Worth choosing: a catalogue and a picture are on the TV, or the internet can fetch them. */
    static boolean available(Context c) {
        File[] imgs = dir(c).listFiles((d, n) -> n.endsWith(".jpg"));
        if (new File(dir(c), "index.json").isFile() && imgs != null && imgs.length > 0) return true;
        return Compat.online(Compat.svc(c, ConnectivityManager.class));
    }

    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), "museum");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    @Override
    void start() {
        running = true;
        generation++;
        final int gen = generation;
        final Context app = getContext().getApplicationContext();
        // one room a rest, in turn
        int turn = Prefs.device(app).getInt("museum_room", 0);
        Prefs.device(app).edit().putInt("museum_room", turn + 1).apply();
        final int r = Compat.mod(turn, ROOMS.length);
        roomName = ROOMS[r][0];
        worker.execute(() -> {
            List<Work> works = catalogue(app, r);
            Collections.shuffle(works);
            // what's already on the TV first, so a rest without internet still has a show
            List<Work> kept = new ArrayList<>(), rest = new ArrayList<>();
            for (Work w : works) (new File(dir(app), w.image + ".jpg").isFile() ? kept : rest).add(w);
            kept.addAll(rest);
            main.post(() -> {
                if (gen != generation) return;
                deck.clear();
                deck.addAll(kept);
                dealt = 0;
                next();
            });
        });
        invalidate();
    }

    @Override
    void stop() {
        running = false;
        generation++;
        main.removeCallbacks(advance);
        main.post(() -> {
            if (room != null) room.recycle();
            if (prevRoom != null) prevRoom.recycle();
            room = prevRoom = null;
        });
    }

    private final Runnable advance = this::next;

    /** The next work: its picture fetched (or found on the TV) and hung, off the main thread. */
    private void next() {
        if (!running || deck.isEmpty()) return;
        final int gen = generation, w = getWidth(), h = getHeight();
        if (w == 0) {
            main.postDelayed(advance, 200);
            return;
        }
        final Work wk = deck.get(dealt++ % deck.size());
        final Context app = getContext().getApplicationContext();
        worker.execute(() -> {
            Bitmap art = image(app, wk);
            final Bitmap hung = art == null ? null : hang(art, w, h);
            if (art != null) art.recycle();
            main.post(() -> {
                if (gen != generation || !running) {
                    if (hung != null) hung.recycle();
                    return;
                }
                if (hung == null) {
                    // couldn't fetch this one: on to the next soon
                    main.postDelayed(advance, 3_000);
                    return;
                }
                if (prevRoom != null) prevRoom.recycle();
                prevRoom = room;
                prevWork = work;
                room = hung;
                work = wk;
                fadeAt = SystemClock.uptimeMillis();
                shownAt = fadeAt;
                invalidate();
                main.postDelayed(advance, SHOW_MS);
            });
        });
    }

    // ---- the catalogue ------------------------------------------------------------------------

    /** Room {@code r}'s works: from the TV if fresh, else from the museum's API (and kept). */
    private static List<Work> catalogue(Context c, int r) {
        File f = new File(dir(c), "index.json");
        JSONObject all = null;
        try {
            if (f.isFile() && System.currentTimeMillis() - f.lastModified() < INDEX_MS) all = new JSONObject(read(new java.io.FileInputStream(f)));
        } catch (IOException | org.json.JSONException ignored) {
        }
        try {
            if (all == null || !all.has(String.valueOf(r))) {
                JSONObject fresh = all == null ? new JSONObject() : all;
                fresh.put(String.valueOf(r), fetchRoom(r));
                try (OutputStream out = new FileOutputStream(f)) {
                    out.write(fresh.toString().getBytes("UTF-8"));
                }
                all = fresh;
            }
        } catch (IOException | org.json.JSONException | InterruptedException ignored) {
        }
        List<Work> out = new ArrayList<>();
        if (all == null) return out;
        JSONArray a = all.optJSONArray(String.valueOf(r));
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Work w = new Work();
            w.id = o.optString("id");
            w.image = o.optString("image");
            w.title = o.optString("title");
            w.artist = o.optString("artist");
            w.date = o.optString("date");
            w.medium = o.optString("medium");
            if (!w.image.isEmpty()) out.add(w);
        }
        return out;
    }

    private static JSONArray fetchRoom(int r) throws IOException, org.json.JSONException, InterruptedException {
        JSONArray out = new JSONArray();
        List<String> seen = new ArrayList<>();
        for (int q = 1; q < ROOMS[r].length; q++) {
            String url = API + "q=" + URLEncoder.encode(ROOMS[r][q], "UTF-8")
                    + "&query%5Bterm%5D%5Bis_public_domain%5D=true&limit=20"
                    + "&fields=id,title,image_id,artist_title,artist_display,date_display,medium_display,place_of_origin,artwork_type_title";
            JSONArray data = new JSONObject(get(url)).optJSONArray("data");
            for (int i = 0; data != null && i < data.length(); i++) {
                JSONObject o = data.getJSONObject(i);
                String image = o.optString("image_id", "");
                if (image.isEmpty() || image.equals("null") || seen.contains(image) || !fits(r, o)) continue;
                seen.add(image);
                String artist = o.optString("artist_title", "");
                // court painters are mostly unnamed: the museum's attribution says where, which reads better
                if (artist.isEmpty() || artist.equals("null") || artist.equals("India")) {
                    artist = o.optString("artist_display", "").split("\n")[0].trim();
                    if (artist.startsWith("India ")) artist = artist.substring(6) + "  ·  India";
                }
                out.put(new JSONObject().put("id", o.optString("id")).put("image", image).put("title", o.optString("title"))
                        .put("artist", artist).put("date", o.optString("date_display")).put("medium", o.optString("medium_display")));
            }
            // the museum asks for gentle use
            Thread.sleep(700);
        }
        return out;
    }

    /** Keeps each room to what it promises: Indian paintings, paintings, or prints. */
    private static boolean fits(int r, JSONObject o) {
        String type = o.optString("artwork_type_title", ""), origin = o.optString("place_of_origin", "") + " " + o.optString("artist_display", "");
        if (r == 0) return type.equals("Painting") && origin.contains("India");
        if (r == 3) return type.equals("Print") && origin.contains("Japan");
        return type.equals("Painting");
    }

    private static Bitmap image(Context c, Work w) {
        File f = new File(dir(c), w.image + ".jpg");
        try {
            if (!f.isFile()) {
                HttpURLConnection con = open("https://www.artic.edu/iiif/2/" + w.image + "/full/843,/0/default.jpg");
                File part = new File(f.getPath() + ".part");
                try (InputStream in = con.getInputStream(); OutputStream out = new FileOutputStream(part)) {
                    byte[] buf = new byte[16 * 1024];
                    for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                } finally {
                    con.disconnect();
                }
                if (!part.renameTo(f)) return null;
                prune(c);
            } else {
                // recently shown: kept longest
                f.setLastModified(System.currentTimeMillis());
            }
            return BitmapFactory.decodeFile(f.getPath());
        } catch (IOException | OutOfMemoryError e) {
            return null;
        }
    }

    /** Only the sixteen most recently shown pictures stay on the TV. */
    private static void prune(Context c) {
        File[] fs = dir(c).listFiles((d, n) -> n.endsWith(".jpg"));
        if (fs == null || fs.length <= KEEP) return;
        Arrays.sort(fs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (int i = KEEP; i < fs.length; i++) fs[i].delete();
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setRequestProperty("User-Agent", UA);
        con.setRequestProperty("AIC-User-Agent", UA);
        con.setConnectTimeout(15_000);
        con.setReadTimeout(30_000);
        if (con.getResponseCode() != 200) {
            con.disconnect();
            throw new IOException("HTTP " + con.getResponseCode());
        }
        return con;
    }

    private static String get(String url) throws IOException {
        HttpURLConnection con = open(url);
        try {
            return read(con.getInputStream());
        } finally {
            con.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        try (InputStream i = in) {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = i.read(buf)) > 0; ) b.write(buf, 0, n);
            return new String(b.toByteArray(), "UTF-8");
        }
    }

    // ---- hanging a work (worker thread) -------------------------------------------------------

    /**
     * The room for one work: a wall in the work's own colours, darkened and quietened, lit from
     * above; the work in a thin dark frame on an ivory mat, casting a soft shadow down the wall.
     */
    private Bitmap hang(Bitmap art, int w, int h) {
        float s = h / 720f;
        // the work's average colour, mostly grey, quite dark: a room that suits it without competing
        Bitmap tiny = Bitmap.createScaledBitmap(art, 16, 16, true);
        long rs = 0, gs = 0, bs = 0;
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                int p = tiny.getPixel(x, y);
                rs += Color.red(p);
                gs += Color.green(p);
                bs += Color.blue(p);
            }
        }
        tiny.recycle();
        float mr = rs / 256f, mg = gs / 256f, mb = bs / 256f, grey = (mr + mg + mb) / 3;
        int wall = Color.rgb(Math.round((mr * 0.35f + grey * 0.65f) * 0.42f), Math.round((mg * 0.35f + grey * 0.65f) * 0.42f),
                Math.round((mb * 0.35f + grey * 0.65f) * 0.42f));
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(Ui.mix(wall, 0xFF000000, 0.55f));
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new RadialGradient(w * 0.38f, h * 0.18f, w * 0.85f, new int[]{Ui.mix(wall, 0xFFFFFFFF, 0.12f), wall, Ui.withAlpha(wall, 0)},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);
        // the work, as large as the wall allows, left of the label column
        float maxW = w * 0.56f, maxH = h * 0.70f;
        float k = Math.min(maxW / art.getWidth(), maxH / art.getHeight());
        float aw = art.getWidth() * k, ah = art.getHeight() * k;
        float cx = w * 0.38f, cy = h * 0.46f, frame = 10 * s * 1.33f, mat = 5 * s * 1.33f;
        RectF box = new RectF(cx - aw / 2, cy - ah / 2, cx + aw / 2, cy + ah / 2);
        // a shadow below and behind it
        Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        for (int i = 6; i >= 1; i--) {
            shadow.setColor(Ui.withAlpha(0xFF000000, 18));
            float g = i * 6 * s;
            c.drawRoundRect(box.left - frame - g + 4 * s, box.top - frame - g + 18 * s, box.right + frame + g + 4 * s, box.bottom + frame + g + 22 * s, g, g, shadow);
        }
        p.setColor(0xFF1C1612);
        c.drawRect(box.left - frame, box.top - frame, box.right + frame, box.bottom + frame, p);
        p.setColor(0xFFECE6D8);
        c.drawRect(box.left - frame + 3 * s, box.top - frame + 3 * s, box.right + frame - 3 * s, box.bottom + frame - 3 * s, p);
        RectF in = new RectF(box.left + mat, box.top + mat, box.right - mat, box.bottom - mat);
        c.drawBitmap(art, null, in, new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
        return b;
    }

    // ---- drawing (main thread) ----------------------------------------------------------------

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (room == null) {
            if (running) postInvalidateDelayed(200);
            return;
        }
        long now = SystemClock.uptimeMillis();
        float fade = fadeAt < 0 ? 1f : Math.min(1f, (now - fadeAt) / (float) FADE_MS);
        if (prevRoom != null && fade < 1f) {
            plain.setAlpha(255);
            c.drawBitmap(prevRoom, 0, 0, plain);
            label(c, prevWork, 1f - fade);
        }
        plain.setAlpha(Math.round(255 * fade));
        c.drawBitmap(room, 0, 0, plain);
        plain.setAlpha(255);
        label(c, work, fade);
        clock(c, w, h);
        if (fade >= 1f && prevRoom != null) {
            prevRoom.recycle();
            prevRoom = null;
        }
        if (running) {
            if (fade < 1f) postInvalidateOnAnimation();
            else postInvalidateDelayed(1_000);
        }
    }

    /** The wall label: artist, title, date, then medium and the collection, as a museum prints them. */
    private void label(Canvas c, Work wk, float alpha) {
        if (wk == null) return;
        int w = getWidth(), h = getHeight();
        float s = h / 720f, d = 1.33f * s;
        float x = w * 0.72f, width = w * 0.24f;
        float y = h * 0.28f;
        int a = Math.round(255 * alpha);
        smallP.setTextSize(10.5f * d);
        smallP.setColor(Ui.withAlpha(0xFFB4AEA4, a));
        c.drawText(roomName, x, h * 0.16f, smallP);
        artistP.setTextSize(13.5f * d);
        artistP.setAlpha(a);
        y = draw(c, wk.artist, artistP, x, y, width, 2);
        titleP.setTextSize(13.5f * d);
        titleP.setAlpha(Math.round(a * 0.92f));
        y = draw(c, wk.title, titleP, x, y + 6 * d, width, 3);
        smallP.setTextSize(11.5f * d);
        smallP.setColor(Ui.withAlpha(0xFFCFC9BF, a));
        y = draw(c, wk.date, smallP, x, y + 4 * d, width, 1);
        smallP.setTextSize(10.5f * d);
        smallP.setColor(Ui.withAlpha(0xFFA9A398, a));
        y = draw(c, wk.medium, smallP, x, y + 14 * d, width, 2);
        smallP.setColor(Ui.withAlpha(0xFF928C82, a));
        draw(c, "Art Institute of Chicago  ·  Public domain", smallP, x, y + 4 * d, width, 2);
    }

    /** Text wrapped to {@code width}, at most {@code lines}; returns where the next line starts. */
    @SuppressWarnings("deprecation")
    private float draw(Canvas c, String t, TextPaint p, float x, float y, float width, int lines) {
        if (t == null || t.isEmpty()) return y;
        StaticLayout l = new StaticLayout(t, p, Math.round(width), Layout.Alignment.ALIGN_NORMAL, 1.15f, 0, false);
        int n = Math.min(lines, l.getLineCount());
        c.save();
        c.translate(x, y);
        c.clipRect(0, 0, width, l.getLineBottom(n - 1));
        l.draw(c);
        c.restore();
        return y + l.getLineBottom(n - 1);
    }

    /** A small clock at the foot of the label column. */
    private void clock(Canvas c, int w, int h) {
        float s = h / 720f, d = 1.33f * s, x = w * 0.72f, yb = h - 52 * d;
        Calendar now = Calendar.getInstance();
        boolean h24 = DateFormat.is24HourFormat(getContext());
        timeP.setTextSize(52 * d);
        c.drawText(new SimpleDateFormat(h24 ? "H:mm" : "h:mm", Locale.getDefault()).format(now.getTime()), x, yb - 24 * d, timeP);
        smallP.setTextSize(12.5f * d);
        smallP.setColor(0xD9F2EEE6);
        c.drawText(new SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(now.getTime()), x, yb, smallP);
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        worker.shutdown();
        super.onDetachedFromWindow();
    }
}
