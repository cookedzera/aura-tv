package app.aura.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Camera;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Your photographs as an editorial spread: one large on the left, two stacked on the right, on
 * near-black with fine gaps. Every few seconds one tile folds over like a turned card to show the
 * next photograph, and each drifts slowly inside its frame. Photos sent from the phone come first,
 * then the Pictures of the Day, then the built-in set; each is decoded at its tile's size.
 */
final class Mosaic extends Scene {
    private static final long TURN_MS = 7_000, FOLD_MS = 1_100;

    private static final class Tile {
        final RectF box = new RectF();
        Bitmap now, next;
        Backdrop.Wallpaper wall, nextWall;
        long foldAt = -1, shownAt;
        final Matrix m = new Matrix();
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Tile[] tiles = {new Tile(), new Tile(), new Tile()};
    private final List<Backdrop.Wallpaper> deck = new ArrayList<>();
    private final Paint pic = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG), shade = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Camera camera = new Camera();
    private final Matrix fold = new Matrix();
    private int dealt, turn, generation;
    private boolean running;
    private long started, nextTurn;

    Mosaic(Context c) {
        super(c);
        setBackgroundColor(0xFF050608);
    }

    /** Is there anything to show? (The built-in set is always there.) */
    static boolean available() {
        return true;
    }

    @Override
    void start() {
        running = true;
        generation++;
        started = SystemClock.uptimeMillis();
        nextTurn = started + 4_000;
        deck.clear();
        Context c = getContext();
        deck.addAll(Mine.walls(c));
        List<Backdrop.Wallpaper> daily = Daily.walls(c), built = builtIn(c);
        Collections.shuffle(daily);
        Collections.shuffle(built);
        deck.addAll(daily);
        deck.addAll(built);
        dealt = 0;
        layout(getWidth(), getHeight());
        if (getWidth() == 0) {
            post(this::start);
            return;
        }
        for (Tile t : tiles) load(t, true);
        invalidate();
    }

    @Override
    void stop() {
        running = false;
        generation++;
        main.post(() -> {
            for (Tile t : tiles) {
                if (t.now != null) t.now.recycle();
                if (t.next != null) t.next.recycle();
                t.now = t.next = null;
            }
        });
    }

    private static List<Backdrop.Wallpaper> builtIn(Context c) {
        List<Backdrop.Wallpaper> out = new ArrayList<>();
        try (InputStream in = c.getAssets().open("wallpapers/wallpapers.json")) {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
            JSONArray a = new JSONArray(new String(b.toByteArray(), "UTF-8"));
            for (int i = 0; i < a.length(); i++) out.add(new Backdrop.Wallpaper(a.getJSONObject(i)));
        } catch (Exception ignored) {
        }
        return out;
    }

    /** One large tile on the left, two on the right: the proportions of a magazine spread. */
    private void layout(int w, int h) {
        float s = h / 720f, m = 48 * s, gap = 12 * s;
        float leftW = (w - 2 * m - gap) * 0.62f;
        tiles[0].box.set(m, m, m + leftW, h - m);
        float rx = m + leftW + gap, half = (h - 2 * m - gap) / 2;
        tiles[1].box.set(rx, m, w - m, m + half);
        tiles[2].box.set(rx, m + half + gap, w - m, h - m);
    }

    /** The next photograph for {@code t}, decoded off the main thread at the tile's size. */
    private void load(Tile t, boolean first) {
        if (deck.isEmpty()) return;
        final Backdrop.Wallpaper w = deck.get(dealt++ % deck.size());
        final int tw = Math.round(t.box.width()), th = Math.round(t.box.height()), gen = generation;
        final Context app = getContext().getApplicationContext();
        worker.execute(() -> {
            Bitmap b = decode(app, w, tw, th);
            main.post(() -> {
                if (b == null) return;
                if (gen != generation || !running) {
                    b.recycle();
                    return;
                }
                if (first || t.now == null) {
                    t.now = b;
                    t.wall = w;
                    t.shownAt = SystemClock.uptimeMillis();
                    if (t == tiles[0]) caption(w);
                } else {
                    t.next = b;
                    t.nextWall = w;
                    t.foldAt = SystemClock.uptimeMillis();
                }
                invalidate();
            });
        });
    }

    private static Bitmap decode(Context c, Backdrop.Wallpaper w, int tw, int th) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            open(c, w, o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= tw * 1.1f && o.outHeight / (sample * 2) >= th * 1.1f) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            return open(c, w, o);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    private static Bitmap open(Context c, Backdrop.Wallpaper w, BitmapFactory.Options o) throws java.io.IOException {
        if (w.onDisk()) return BitmapFactory.decodeFile(w.path, o);
        try (InputStream in = c.getAssets().open("wallpapers/" + w.file)) {
            return BitmapFactory.decodeStream(in, null, o);
        }
    }

    private void caption(Backdrop.Wallpaper w) {
        if (listener == null || w == null) return;
        String by = w.author.isEmpty() ? w.license : "Photo by " + w.author;
        listener.onStory(w.mine ? "From your phone" : w.region.replace(" · ", ", "), w.place.isEmpty() ? "Your photo" : w.place, "", by, 14_000);
    }

    @Override
    protected void onDraw(Canvas c) {
        long now = SystemClock.uptimeMillis();
        float s = getHeight() / 720f;
        if (running && now >= nextTurn) {
            nextTurn = now + TURN_MS;
            Tile t = tiles[turn++ % tiles.length];
            if (t.foldAt < 0 && t.now != null) load(t, false);
        }
        boolean moving = false;
        for (Tile t : tiles) {
            if (t.foldAt >= 0) {
                float f = Math.min(1f, (now - t.foldAt) / (float) FOLD_MS);
                moving = true;
                // the old picture folds away around the tile's middle, the new one unfolds into place
                boolean firstHalf = f < 0.5f;
                float ang = firstHalf ? 90 * Ui.SOFT.getInterpolation(f * 2) : -90 * (1 - Ui.SOFT.getInterpolation((f - 0.5f) * 2));
                draw(c, t, firstHalf ? t.now : t.next, ang, now, s);
                if (f >= 1f) {
                    if (t.now != null) t.now.recycle();
                    t.now = t.next;
                    t.wall = t.nextWall;
                    t.next = null;
                    t.foldAt = -1;
                    t.shownAt = now;
                    if (t == tiles[0]) caption(t.wall);
                }
            } else if (t.now != null) {
                draw(c, t, t.now, 0, now, s);
            }
        }
        if (running) {
            if (moving) postInvalidateOnAnimation();
            else postInvalidateDelayed(66);
        }
    }

    /** One tile: the photograph covering it, drifting slowly, folded by {@code angle} around its middle. */
    private void draw(Canvas c, Tile t, Bitmap b, float angle, long now, float s) {
        if (b == null) return;
        RectF box = t.box;
        float age = (now - t.shownAt) / 1000f;
        float k = Math.max(box.width() / b.getWidth(), box.height() / b.getHeight()) * (1.06f + 0.04f * (float) Math.sin(age / 9));
        float dx = (box.width() - b.getWidth() * k) / 2f + (float) Math.sin(age / 13) * 10 * s;
        float dy = (box.height() - b.getHeight() * k) / 2f;
        t.m.setScale(k, k);
        t.m.postTranslate(box.left + dx, box.top + dy);
        BitmapShader sh = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        sh.setLocalMatrix(t.m);
        pic.setShader(sh);
        c.save();
        if (angle != 0) {
            camera.save();
            camera.rotateX(angle);
            camera.getMatrix(fold);
            camera.restore();
            fold.preTranslate(-box.centerX(), -box.centerY());
            fold.postTranslate(box.centerX(), box.centerY());
            c.concat(fold);
        }
        float r = 10 * s;
        c.drawRoundRect(box, r, r, pic);
        if (angle != 0) {
            // the turning face darkens as it tilts away from the light
            shade.setColor(Ui.withAlpha(0xFF000000, Math.round(200 * Math.abs(angle) / 90f)));
            c.drawRoundRect(box, r, r, shade);
        }
        c.restore();
        pic.setShader(null);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        layout(w, h);
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        worker.shutdown();
        super.onDetachedFromWindow();
    }
}
