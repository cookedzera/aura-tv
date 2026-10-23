package app.aura.home;

import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.ImageDecoder;
import android.graphics.LightingColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Living wallpaper: nature photos drifting in a slow Ken Burns move with long crossfades.
 * It also hands a blurred, identically-transformed copy to glass panels so they frost the real scene.
 */
final class Backdrop extends View implements Choreographer.FrameCallback {

    static final class Wallpaper {
        final String file, place, region, line, author, license, mood;
        /** Imported photographs live on disk at {@code path} and carry their {@code day} (yyyy-MM-dd). */
        final String path, day;
        final int accent;
        /** Sent from a phone (see {@link Mine}); a GIF plays on the wall. */
        final boolean mine, gif;

        Wallpaper(JSONObject o) {
            file = o.optString("file");
            path = o.optString("path");
            day = o.optString("day");
            mine = "mine".equals(o.optString("kind"));
            gif = o.optBoolean("gif");
            mood = o.optString("mood", "day");
            place = o.optString("place");
            region = o.optString("region");
            line = o.optString("line");
            author = o.optString("author");
            license = o.optString("license");
            int a;
            try {
                a = Color.parseColor(o.optString("accent", "#8AA4C8"));
            } catch (IllegalArgumentException e) {
                a = 0xFF8AA4C8;
            }
            accent = a;
        }

        /** A Picture of the Day. */
        boolean daily() {
            return !path.isEmpty() && !mine;
        }

        /** Read from disk rather than the app's assets: imported or sent from a phone. */
        boolean onDisk() {
            return !path.isEmpty();
        }
    }

    interface Listener {
        void onWallpaperChanged(Wallpaper w, float lumaTop, float lumaBottom, boolean first);
    }

    /** Ambient motion that rides the backdrop's own frame cadence instead of forcing extra frames. */
    interface Tick {
        void onTick(long now);
    }

    private static final long SHOW_MS = 36_000, FADE_MS = 2_800, DRIFT_MS = 42_000;
    private static final long INTRO_MS = 1_900, PARALLAX_MS = 950;

    private static final class Slide {
        Wallpaper wall;
        Bitmap sharp, soft, blur;
        /** A GIF's frames, played by the render thread; {@code sharp} keeps its first frame for the glass. */
        Drawable anim;
        ColorFilter animFilter;
        int animAlpha = -1;
        BitmapShader blurShader;
        int sw, sh;
        float lumaTop, lumaBottom;
        long start;
        float alpha;
        float s0, s1, x0, x1, y0, y1;
        final Matrix m = new Matrix();
    }

    private final List<Wallpaper> walls = new ArrayList<>(), builtIn = new ArrayList<>();
    private long wallStamp = -1;
    /** A photograph just sent from a phone, to go on next. */
    private String wanted;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Random rnd = new Random();
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Matrix tmp = new Matrix();
    private final List<View> mirrors = new ArrayList<>();
    private final SharedPreferences prefs;

    private Slide current, incoming;
    private Listener listener;
    private int index;
    private boolean running, loading;
    private long nextAt, introStart = -1, parallaxStart = -10_000;
    private float intro = 1f, parallax, parallaxFrom, parallaxTo;
    private int accent = 0xFF8AA4C8, accentFrom = accent, accentTo = accent;
    private int frame, generation;
    private final List<Tick> ticks = new ArrayList<>();
    private ColorFilter dim;
    private int dimLevel = 64;
    private float defocus, defocusFrom, defocusTo;
    private long defocusStart = -10_000, defocusMs = 1;

    Backdrop(Context c) {
        super(c);
        prefs = c.getSharedPreferences("aura", Context.MODE_PRIVATE);
        try (InputStream in = c.getAssets().open("wallpapers/wallpapers.json")) {
            JSONArray arr = new JSONArray(new String(readAll(in), StandardCharsets.UTF_8));
            for (int i = 0; i < arr.length(); i++) builtIn.add(new Wallpaper(arr.getJSONObject(i)));
        } catch (Exception ignored) {
        }
        refreshWalls();
        // each launch continues with the next photo in the rotation
        index = prefs.getInt("wall", -1) + 1;
    }

    /**
     * Photos sent from a phone, then imported photographs (each newest first), then the built-in
     * set; re-read only when something has been written or a switch has changed.
     */
    private void refreshWalls() {
        Context c = getContext();
        boolean daily = Prefs.on(c, Prefs.DAILY), only = Prefs.on(c, Prefs.MINE_ONLY);
        long stamp = (Daily.stamp(c) * 31 + Mine.stamp(c)) * 4 + (daily ? 1 : 0) + (only ? 2 : 0);
        if (stamp == wallStamp && !walls.isEmpty()) return;
        wallStamp = stamp;
        walls.clear();
        List<Wallpaper> mine = Mine.walls(c);
        walls.addAll(mine);
        // "only your photos" with none left falls back to everything, never to a black screen
        if (only && !mine.isEmpty()) return;
        if (daily) walls.addAll(Daily.walls(c));
        walls.addAll(builtIn);
    }

    /** The newest imported photograph not yet shown: it goes on before anything else, once. */
    private int fresh() {
        String seen = prefs.getString("daily_seen", "");
        for (int i = 0; i < walls.size(); i++) {
            Wallpaper w = walls.get(i);
            // imports come newest first, so the first one is the only candidate
            if (w.daily()) return w.day.compareTo(seen) > 0 ? i : -1;
        }
        return -1;
    }

    /** Puts the photograph at {@code path} on next, straight away: a phone has just sent it. */
    void show(String path) {
        wanted = path;
        if (!loading && incoming == null) nextAt = 0;
    }

    /** The list of photographs changed (a switch, or a photo removed): move on to the new set. */
    void reload() {
        wallStamp = -1;
        skip();
    }

    void setListener(Listener l) {
        listener = l;
    }

    void setIntro(boolean on) {
        if (on) intro = 0f;
    }

    void start() {
        if (running) return;
        running = true;
        long now = SystemClock.uptimeMillis();
        if (current == null && !loading) load(index, true);
        else nextAt = wanted != null ? 0 : Math.max(nextAt, now + 6_000);
        play(current, true);
        Choreographer.getInstance().postFrameCallback(this);
    }

    void stop() {
        running = false;
        Choreographer.getInstance().removeFrameCallback(this);
        if (incoming != null) {
            play(current, false);
            current = incoming;
            current.alpha = 1f;
            incoming = null;
            accent = accentTo;
        }
        play(current, false);
    }

    /** Starts or pauses a GIF; a photograph ignores it. */
    private static void play(Slide s, boolean on) {
        if (s == null || !(s.anim instanceof Animatable)) return;
        if (on) ((Animatable) s.anim).start();
        else ((Animatable) s.anim).stop();
    }

    /** Drop the photos when the system is short on memory; they fade back in on return. */
    void trim(int level) {
        if (running || level < ComponentCallbacks2.TRIM_MEMORY_MODERATE) return;
        play(current, false);
        current = null;
        incoming = null;
        loading = false;
        generation++;
        intro = 0f;
        introStart = -1;
    }

    void skip() {
        if (incoming == null && !loading) nextAt = 0;
    }

    /**
     * Rack focus: 1 pulls the photograph soft and dim behind the interface, 0 brings it back sharp.
     */
    void setDefocus(float to, long ms) {
        if (to == defocusTo && SystemClock.uptimeMillis() - defocusStart >= defocusMs) return;
        defocusFrom = defocus;
        defocusTo = to;
        defocusMs = Math.max(1, ms);
        defocusStart = SystemClock.uptimeMillis();
        if (ms <= 0) defocus = to;
        invalidate();
    }

    void setParallax(float px) {
        parallaxFrom = parallax;
        parallaxTo = px;
        parallaxStart = SystemClock.uptimeMillis();
    }

    int accent() {
        return accent;
    }

    /** Zero-based position of the photograph now on show, for the folio. */
    int index() {
        return index;
    }

    int count() {
        return walls.size();
    }

    /** How long one photograph stays before the next crossfade completes. */
    long cycleMs() {
        return SHOW_MS + FADE_MS;
    }

    boolean hasImage() {
        return current != null;
    }

    /** How far the photograph on show is through its time on screen, 0 to 1. */
    float shown() {
        Slide s = incoming != null ? incoming : current;
        if (s == null) return 0f;
        return Math.min(1f, (SystemClock.uptimeMillis() - s.start) / (float) (SHOW_MS + FADE_MS));
    }

    Wallpaper wallpaper() {
        return incoming != null ? incoming.wall : current != null ? current.wall : null;
    }

    void addTick(Tick t) {
        ticks.add(t);
    }

    /** Replays the fade-in from black, e.g. as the boot screen dissolves. */
    void restartIntro() {
        intro = 0f;
        introStart = current != null ? SystemClock.uptimeMillis() : -1;
        invalidate();
    }

    void addMirror(View v) {
        if (!mirrors.contains(v)) mirrors.add(v);
    }

    void removeMirror(View v) {
        mirrors.remove(v);
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        if (!running) return;
        long now = SystemClock.uptimeMillis();
        boolean busy = incoming != null || intro < 1f || now - parallaxStart < PARALLAX_MS
                || now - defocusStart < defocusMs;
        // the drift alone moves ~1px/s, so a third of the rate is visually identical and spares the GPU
        if (busy || (++frame % 3) == 0) {
            step(now);
            invalidate();
            for (int i = 0; i < ticks.size(); i++) ticks.get(i).onTick(now);
            for (int i = 0; i < mirrors.size(); i++) {
                View v = mirrors.get(i);
                if (v.isShown() && v.getAlpha() > 0f) v.invalidate();
            }
        }
        if (incoming == null && !loading && now >= nextAt && (walls.size() > 1 || wanted != null) && current != null) {
            load(index + 1, false);
        }
        Choreographer.getInstance().postFrameCallback(this);
    }

    private void step(long now) {
        if (introStart >= 0) {
            intro = Math.min(1f, (now - introStart) / (float) INTRO_MS);
            if (intro >= 1f) introStart = -1;
        }
        if (incoming != null) {
            float f = Math.min(1f, (now - incoming.start) / (float) FADE_MS);
            incoming.alpha = f * f * (3 - 2 * f);
            accent = Ui.mix(accentFrom, accentTo, incoming.alpha);
            if (f >= 1f) {
                play(current, false);
                current = incoming;
                incoming = null;
                current.alpha = 1f;
                accent = accentTo;
                nextAt = now + SHOW_MS;
            }
        }
        float p = Math.min(1f, (now - parallaxStart) / (float) PARALLAX_MS);
        float e = 1f - (1f - p) * (1f - p) * (1f - p);
        parallax = parallaxFrom + (parallaxTo - parallaxFrom) * e;
        float q = Math.min(1f, (now - defocusStart) / (float) defocusMs);
        defocus = defocusFrom + (defocusTo - defocusFrom) * (q * q * (3 - 2 * q));
        place(current, now);
        place(incoming, now);
    }

    private void place(Slide s, long now) {
        if (s == null) return;
        float w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        float t = Math.min(1f, (now - s.start) / (float) DRIFT_MS);
        float scale = s.s0 + (s.s1 - s.s0) * t;
        float settle = 1f - intro;
        scale *= 1f + 0.10f * settle * settle;
        float maxX = (scale - 1f) * w / 2f, maxY = (scale - 1f) * h / 2f;
        float tx = Ui.clamp((s.x0 + (s.x1 - s.x0) * t) * maxX + parallax, -maxX, maxX);
        float ty = Ui.clamp((s.y0 + (s.y1 - s.y0) * t) * maxY, -maxY, maxY);
        float k = Math.max(w / s.sw, h / s.sh) * scale;
        s.m.setScale(k, k);
        s.m.postTranslate((w - s.sw * k) / 2f + tx, (h - s.sh * k) / 2f + ty);
    }

    private float introAlpha() {
        if (intro >= 1f) return 1f;
        float f = Math.min(1f, intro * 1.7f);
        return f * f * (3 - 2 * f);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        long now = SystemClock.uptimeMillis();
        place(current, now);
        place(incoming, now);
    }

    @Override
    protected void onDraw(Canvas c) {
        float ia = introAlpha();
        if (current == null || ia < 1f) c.drawColor(Color.BLACK);
        // the rack-focus dimming is a colour filter on the photo itself, not an extra full-screen fill
        int level = Math.round(64 * (1f - 0.30f * defocus));
        if (level != dimLevel) {
            dimLevel = level;
            int g = Math.round(255 * level / 64f);
            dim = level >= 64 ? null : new LightingColorFilter(Color.rgb(g, g, g), 0);
        }
        paint.setColorFilter(dim);
        drawSlide(c, current, ia);
        drawSlide(c, incoming, ia);
    }

    private void drawSlide(Canvas c, Slide s, float ia) {
        if (s == null || s.sharp == null) return;
        // fully defocused, the soft copy covers everything: skip the sharp fill
        if (defocus < 0.995f || s.soft == null) {
            int a = Math.round(255 * s.alpha * ia);
            if (s.anim != null) {
                // the render thread steps the GIF's frames; this only places it
                if (a != s.animAlpha) s.anim.setAlpha(s.animAlpha = a);
                if (dim != s.animFilter) s.anim.setColorFilter(s.animFilter = dim);
                c.save();
                c.concat(s.m);
                s.anim.draw(c);
                c.restore();
            } else {
                paint.setAlpha(a);
                c.drawBitmap(s.sharp, s.m, paint);
            }
        }
        if (defocus > 0.005f && s.soft != null) {
            tmp.set(s.m);
            tmp.preScale(s.sw / (float) s.soft.getWidth(), s.sh / (float) s.soft.getHeight());
            paint.setAlpha(Math.round(255 * s.alpha * ia * defocus));
            c.drawBitmap(s.soft, tmp, paint);
        }
    }

    /** Draws the blurred scene as seen from a view whose top-left sits at (ox, oy) in the window. */
    void drawBlur(Canvas c, float ox, float oy, Paint p) {
        float ia = introAlpha();
        blurSlide(c, current, ox, oy, p, ia);
        blurSlide(c, incoming, ox, oy, p, ia);
    }

    /**
     * The blurred scene filling a rounded rectangle, as seen from a view whose top-left sits at
     * (ox, oy): a single draw with anti-aliased corners and no clip. The paint's colour filter
     * carries the glass tint, so a whole pane costs one pass over its pixels.
     */
    void drawBlurRound(Canvas c, RectF r, float radius, float ox, float oy, Paint p) {
        float ia = introAlpha();
        blurRound(c, current, r, radius, ox, oy, p, ia);
        blurRound(c, incoming, r, radius, ox, oy, p, ia);
        p.setShader(null);
    }

    private void blurRound(Canvas c, Slide s, RectF r, float radius, float ox, float oy, Paint p, float ia) {
        if (s == null || s.blur == null) return;
        if (s.blurShader == null) s.blurShader = new BitmapShader(s.blur, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        tmp.set(s.m);
        tmp.preScale(s.sw / (float) s.blur.getWidth(), s.sh / (float) s.blur.getHeight());
        tmp.postTranslate(-ox, -oy);
        s.blurShader.setLocalMatrix(tmp);
        p.setShader(s.blurShader);
        p.setAlpha(Math.round(255 * s.alpha * ia));
        c.drawRoundRect(r, radius, radius, p);
    }

    private void blurSlide(Canvas c, Slide s, float ox, float oy, Paint p, float ia) {
        if (s == null || s.blur == null) return;
        tmp.set(s.m);
        tmp.preScale(s.sw / (float) s.blur.getWidth(), s.sh / (float) s.blur.getHeight());
        tmp.postTranslate(-ox, -oy);
        p.setAlpha(Math.round(255 * s.alpha * ia));
        c.drawBitmap(s.blur, tmp, p);
    }

    private void load(int i, boolean first) {
        refreshWalls();
        if (walls.isEmpty()) return;
        loading = true;
        int f = wanted != null ? -1 : fresh(), want = -1;
        for (int k = 0; wanted != null && k < walls.size(); k++) if (wanted.equals(walls.get(k).path)) want = k;
        wanted = null;
        final int idx = want >= 0 ? want : f >= 0 ? f : pick(i);
        if (f >= 0) prefs.edit().putString("daily_seen", walls.get(f).day).apply();
        final int gen = generation;
        final Wallpaper w = walls.get(idx);
        io.execute(() -> {
            final Slide s = decode(w);
            main.post(() -> {
                if (gen != generation) return;
                loading = false;
                index = idx;
                if (s == null) {
                    nextAt = SystemClock.uptimeMillis() + 5_000;
                    return;
                }
                prefs.edit().putInt("wall", idx).apply();
                begin(s);
            });
        });
    }

    /** Sky Sync: the next photograph in the rotation whose light matches the sky outside right now. */
    private int pick(int from) {
        int n = walls.size();
        Sun.Phase phase = Sun.phase(System.currentTimeMillis());
        for (int k = 0; k < n; k++) {
            int j = ((from + k) % n + n) % n;
            // the daily photographs are the fresh ones: they take their turn whatever the light
            if (walls.get(j).onDisk() || Sun.suits(walls.get(j).mood, phase)) return j;
        }
        return ((from % n) + n) % n;
    }

    private void begin(Slide s) {
        long now = SystemClock.uptimeMillis();
        s.start = now;
        path(s);
        boolean first = current == null;
        if (first) {
            s.alpha = 1f;
            current = s;
            accent = accentFrom = accentTo = s.wall.accent;
            nextAt = now + SHOW_MS;
            if (intro < 1f) introStart = now;
        } else {
            s.alpha = 0f;
            incoming = s;
            accentFrom = accent;
            accentTo = s.wall.accent;
        }
        place(s, now);
        if (running) play(s, true);
        if (listener != null) listener.onWallpaperChanged(s.wall, s.lumaTop, s.lumaBottom, first);
        invalidate();
    }

    private void path(Slide s) {
        boolean zoomIn = rnd.nextBoolean();
        float lo = 1.05f + rnd.nextFloat() * 0.03f, hi = lo + 0.08f + rnd.nextFloat() * 0.04f;
        s.s0 = zoomIn ? lo : hi;
        s.s1 = zoomIn ? hi : lo;
        s.x0 = rnd.nextFloat() * 1.6f - 0.8f;
        s.x1 = -Math.signum(s.x0) * (0.3f + rnd.nextFloat() * 0.5f);
        s.y0 = rnd.nextFloat() - 0.5f;
        s.y1 = rnd.nextFloat() - 0.5f;
    }

    private Slide decode(Wallpaper w) {
        try (InputStream in = w.onDisk() ? new java.io.FileInputStream(w.path) : getContext().getAssets().open("wallpapers/" + w.file)) {
            Bitmap b = BitmapFactory.decodeStream(in);
            if (b == null) return null;
            b.prepareToDraw();
            Slide s = new Slide();
            s.wall = w;
            s.sharp = b;
            s.sw = b.getWidth();
            s.sh = b.getHeight();
            if (w.gif && Build.VERSION.SDK_INT >= 28) {
                s.anim = gif(w.path, s.sw, s.sh);
                if (s.anim != null) s.anim.setBounds(0, 0, s.sw, s.sh);
            }
            s.blur = blur(b);
            s.soft = soften(b);
            s.lumaTop = luma(s.blur, 0f, 0f, 0.62f, 0.66f);
            s.lumaBottom = luma(s.blur, 0f, 0.6f, 1f, 1f);
            return s;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * A GIF that plays on the render thread, decoded no larger than the screen it covers. Each frame
     * replaces the last in one buffer, so even a long GIF costs about one photograph's memory.
     */
    @android.annotation.TargetApi(28)
    private Drawable gif(String path, int w, int h) {
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int vw = getWidth() > 0 ? getWidth() : dm.widthPixels, vh = getHeight() > 0 ? getHeight() : dm.heightPixels;
        try {
            return ImageDecoder.decodeDrawable(ImageDecoder.createSource(new File(path)), (d, info, src) -> {
                float k = Math.max(vw / (float) w, vh / (float) h);
                if (k < 1f) d.setTargetSize(Math.max(1, Math.round(w * k)), Math.max(1, Math.round(h * k)));
            });
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    static Bitmap blur(Bitmap src) {
        Bitmap quarter = Bitmap.createScaledBitmap(src, Math.max(1, src.getWidth() / 4), Math.max(1, src.getHeight() / 4), true);
        Bitmap small = Bitmap.createScaledBitmap(quarter, 128, 72, true);
        int w = small.getWidth(), h = small.getHeight();
        int[] px = new int[w * h], tmp = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 3; pass++) {
            boxBlur(px, tmp, w, h, 4, true);
            boxBlur(tmp, px, w, h, 4, false);
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
    }

    /** A shallow depth-of-field copy: detail melts but shapes stay readable, like a lens racked to the foreground. */
    static Bitmap soften(Bitmap src) {
        Bitmap small = Bitmap.createScaledBitmap(src, 512, 288, true);
        int w = small.getWidth(), h = small.getHeight();
        int[] px = new int[w * h], tmp = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 3; pass++) {
            boxBlur(px, tmp, w, h, 1, true);
            boxBlur(tmp, px, w, h, 1, false);
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
    }

    private static void boxBlur(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int len = horizontal ? w : h, lines = horizontal ? h : w, div = r * 2 + 1;
        for (int line = 0; line < lines; line++) {
            int sr = 0, sg = 0, sb = 0;
            for (int k = -r; k <= r; k++) {
                int c = in[at(line, Math.max(0, Math.min(len - 1, k)), w, horizontal)];
                sr += (c >> 16) & 0xFF;
                sg += (c >> 8) & 0xFF;
                sb += c & 0xFF;
            }
            for (int i = 0; i < len; i++) {
                out[at(line, i, w, horizontal)] = 0xFF000000 | ((sr / div) << 16) | ((sg / div) << 8) | (sb / div);
                int cOut = in[at(line, Math.max(0, i - r), w, horizontal)];
                int cIn = in[at(line, Math.min(len - 1, i + r + 1), w, horizontal)];
                sr += ((cIn >> 16) & 0xFF) - ((cOut >> 16) & 0xFF);
                sg += ((cIn >> 8) & 0xFF) - ((cOut >> 8) & 0xFF);
                sb += (cIn & 0xFF) - (cOut & 0xFF);
            }
        }
    }

    private static int at(int line, int i, int w, boolean horizontal) {
        return horizontal ? line * w + i : i * w + line;
    }

    private static float luma(Bitmap b, float l, float t, float r, float btm) {
        int x0 = (int) (l * b.getWidth()), x1 = (int) (r * b.getWidth());
        int y0 = (int) (t * b.getHeight()), y1 = (int) (btm * b.getHeight());
        double sum = 0;
        int n = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int c = b.getPixel(x, y);
                sum += (0.2126 * ((c >> 16) & 0xFF) + 0.7152 * ((c >> 8) & 0xFF) + 0.0722 * (c & 0xFF)) / 255.0;
                n++;
            }
        }
        return n == 0 ? 0.5f : (float) (sum / n);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }
}
