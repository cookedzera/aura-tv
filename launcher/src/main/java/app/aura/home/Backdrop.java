package app.aura.home;

import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.ImageDecoder;
import android.graphics.LightingColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
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
        /** Pure black instead of a photograph, for OLED screens. */
        final boolean black;

        Wallpaper(JSONObject o) {
            file = o.optString("file");
            path = o.optString("path");
            day = o.optString("day");
            mine = "mine".equals(o.optString("kind"));
            gif = o.optBoolean("gif");
            black = "black".equals(o.optString("kind"));
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

    // a photograph stays a minute and a half, drifting slowly the whole while: calmer to rest your eyes
    // on, and fewer crossfades, which are the heaviest frames the home screen draws
    private static final long FADE_MS = 2_800, DRIFT_MS = 96_000;
    /** How long a photograph stays: 90 seconds on the home screen; the screensaver moves on sooner. */
    private long showMs = 90_000;

    void setShowMs(long ms) {
        showMs = ms;
    }
    private static final long INTRO_MS = 1_900, PARALLAX_MS = 950;
    /** Light mode: no photograph changes within ten seconds of a press, and in photo mode each stays three minutes. */
    private static final long QUIET_MS = 10_000, PHOTO_SHOW_MS = 180_000;
    /** HIGH: the drift rests ten minutes after the last press outside photo mode. */
    private static final long STILL_MS = 10 * 60_000;
    /** Rows of a photograph decoded at a time for a 16-bit copy (see {@link #bands}): a few hundred KB at full colour. */
    private static final int BAND = 64;

    private static final class Slide {
        Wallpaper wall;
        /** {@code soft} is only an ingredient of {@code steady}, and goes once that's made (see {@link #drawSlide}). */
        Bitmap sharp, soft, blur;
        /** Sharp and soft already mixed at {@code mixed}, the home screen's steady softness, screen-sized; null for GIFs. */
        Bitmap steady;
        float mixed;
        /** Light mode: {@code steady} carries the shade and its grain, baked for a {@code bakedW}x{@code bakedH} screen. */
        boolean baked;
        int bakedW, bakedH;
        /** {@code steady} is being made again on the loader, which reads {@code sharp}: freeing waits for it. */
        boolean remaking, dropped;
        /** A GIF's frames, played by the render thread; {@code sharp} keeps its first frame for the glass. */
        Drawable anim;
        ColorFilter animFilter;
        int animAlpha = -1;
        BitmapShader blurShader;
        /** Light mode: pre-tinted copies of {@code blur} keyed by each pane's glass filter, so panes draw without a per-pixel colour matrix. */
        final java.util.IdentityHashMap<ColorFilter, BitmapShader> tintedShaders = new java.util.IdentityHashMap<>();
        final List<Bitmap> tintedBlurs = new ArrayList<>();
        int sw, sh;
        float lumaTop, lumaBottom;
        long start;
        /** When its drift began, on the drift's own clock (see {@link Backdrop#driftNow}). */
        long drift0;
        float alpha;
        float s0, s1, x0, x1, y0, y1;
        final Matrix m = new Matrix();

        synchronized BitmapShader tintedShader(ColorFilter cf) {
            if (blur == null || cf == null) return null;
            BitmapShader sh = tintedShaders.get(cf);
            if (sh != null) return sh;
            Bitmap b = Bitmap.createBitmap(blur.getWidth(), blur.getHeight(), Bitmap.Config.ARGB_8888);
            Paint p = new Paint();
            p.setColorFilter(cf);
            new Canvas(b).drawBitmap(blur, 0f, 0f, p);
            b.prepareToDraw();
            sh = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            tintedBlurs.add(b);
            tintedShaders.put(cf, sh);
            return sh;
        }

        /** Frees the large full-screen bitmaps as soon as the slide leaves the screen; {@code blur} stays for {@link #lastBlur}. */
        void recycle() {
            if (remaking) {
                dropped = true;
                return;
            }
            if (steady != null && steady != sharp && steady != soft) steady.recycle();
            if (soft != null && soft != sharp) soft.recycle();
            if (sharp != null) sharp.recycle();
            steady = soft = sharp = null;
            synchronized (this) {
                tintedShaders.clear();
                for (int i = 0; i < tintedBlurs.size(); i++) tintedBlurs.get(i).recycle();
                tintedBlurs.clear();
            }
        }
    }

    private final List<Wallpaper> walls = new ArrayList<>(), builtIn = new ArrayList<>();
    private final java.util.concurrent.CopyOnWriteArraySet<ColorFilter> glassFilters = new java.util.concurrent.CopyOnWriteArraySet<>();
    private long wallStamp = -1;
    /** A photograph just sent from a phone, to go on next. */
    private String wanted;
    /** The photo on show may have been removed or switched off: replace it as soon as possible. */
    private boolean stale;
    // just below the screen's priority: the first photograph is waited for, but never at a frame's expense
    private final ExecutorService io = Compat.background("photos", android.os.Process.THREAD_PRIORITY_DEFAULT + 4);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Random rnd = new Random();
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    /** On a TV with little memory the photograph is kept in 16-bit colour: half the room, dithered so a sky stays smooth. */
    private Boolean thrifty;
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
    /** Photographs in a row that couldn't be read: the first few are skipped at once, then it waits. */
    private int failures;
    private final List<Tick> ticks = new ArrayList<>();
    private ColorFilter dim;
    private int dimLevel = 64;
    private float defocus, defocusFrom, defocusTo;
    private long defocusStart = -10_000, defocusMs = 1;
    /** The editorial shade above the photograph, which Light mode draws as part of it (see {@link #setShade}). */
    private Scrim shade;
    /** Light mode: a photograph decoded while the remote was in use, put on once it rests. */
    private Slide pending;
    /** Home's last key press, -1 before the first (and always in the screensaver); and photo mode. */
    private long keyAt = -1;
    private boolean photoMode;
    /** How long the drift has stood still in all, and since when it stands still now (-1 while it moves). */
    private long stillMs, stillSince = -1;
    /** How much of the finished frame is drawn so far (see {@link #onDraw}). */
    private float drawn;

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
        boolean daily = Prefs.on(c, Prefs.DAILY), only = Prefs.on(c, Prefs.MINE_ONLY), black = Prefs.on(c, Prefs.BLACK);
        long stamp = (((Daily.stamp(c) * 31 + Gallery.stamp(c)) * 31 + Mine.stamp(c)) * 4 + (daily ? 1 : 0) + (only ? 2 : 0)) * 2 + (black ? 1 : 0);
        if (stamp == wallStamp && !walls.isEmpty()) return;
        wallStamp = stamp;
        walls.clear();
        if (black) {
            try {
                walls.add(new Wallpaper(new JSONObject().put("kind", "black").put("accent", "#5A6A8C")));
            } catch (org.json.JSONException ignored) {
            }
            return;
        }
        List<Wallpaper> mine = Mine.walls(c);
        walls.addAll(mine);
        // "only your photos" with none left falls back to everything, never to a black screen
        if (only && !mine.isEmpty()) return;
        if (daily) {
            walls.addAll(Daily.walls(c));
            walls.addAll(Gallery.walls(c));
        }
        // The bundled northern lights is the wall only while nothing else is there: a first start, no internet
        if (walls.isEmpty()) walls.addAll(builtIn);
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
        if (!loading && incoming == null && pending == null) nextAt = 0;
        kick();
    }

    /**
     * The list of photographs changed (a switch, or a photo removed): move on to the new set. If
     * a crossfade or load is under way, the move happens the moment it ends, not a cycle later.
     */
    void reload() {
        wallStamp = -1;
        stale = true;
        skip();
    }

    void setListener(Listener l) {
        listener = l;
    }

    /**
     * The shade over the photograph ({@link Scrim}). In Light mode the photograph draws it itself:
     * baked with its grain into each resting copy, so a resting frame is one opaque layer instead of
     * three, and drawn over the sharp copy while the focus racks, the resting copy laid over it,
     * which comes out exactly as the shade over the two would. The shade view then draws nothing;
     * out of Light mode it draws as before.
     */
    void setShade(Scrim s) {
        shade = s;
        invalidate();
    }

    /** A press of home's remote: Light mode changes no photograph for a while after one, and a resting drift (HIGH) moves again. */
    void keyed(long at) {
        keyAt = at;
        if (stillSince >= 0 && !calm) kick();
    }

    /** Photo mode keeps HIGH's drift going without presses, and in Light mode each photograph stays three minutes. */
    void setPhotoMode(boolean on) {
        photoMode = on;
        if (stillSince >= 0 && !calm) kick();
    }

    void setIntro(boolean on) {
        if (on) intro = 0f;
    }

    /** Light mode: the drift redrawn twice a second; slow enough that its steps can't be seen. */
    static boolean calm;

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
            if (current != null) current.recycle();
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
        keepStandIn();
        play(current, false);
        play(incoming, false);
        if (current != null) current.recycle();
        if (incoming != null) incoming.recycle();
        if (pending != null) pending.recycle();
        current = null;
        incoming = null;
        pending = null;
        loading = false;
        generation++;
        // with the small copy on screen there's no rise from black: the photograph fades in over it
        intro = standIn != null ? 1f : 0f;
        introStart = -1;
    }

    /**
     * What Home shows the instant it's back after Android took memory back (an HDMI input, a big
     * app): a third-size copy of the photograph on screen (0.4 MB, not 4), kept when the photograph
     * itself is let go, so Home comes back to its picture rather than black, the photograph fading
     * in over it once it's decoded again.
     */
    private Bitmap standIn;
    private long standInFadeAt = -1;
    private static final long STAND_IN_FADE_MS = 280;

    private void keepStandIn() {
        if (standIn != null || current == null) return;
        // never a resting copy with the shade baked in: the shade is drawn over the small copy
        Bitmap src = current.steady != null && !current.baked && !current.steady.isRecycled() ? current.steady : current.sharp;
        if (src == null || src.isRecycled()) return;
        try {
            standIn = Bitmap.createScaledBitmap(src, Math.max(1, src.getWidth() / 3), Math.max(1, src.getHeight() / 3), true);
        } catch (RuntimeException | OutOfMemoryError e) {
            standIn = null;
        }
        standInFadeAt = -1;
    }

    private void dropStandIn() {
        if (standIn != null) standIn.recycle();
        standIn = null;
        standInFadeAt = -1;
    }

    /** The small copy, filling the screen as the photograph does; how much of it still shows (1 = all). */
    private float drawStandIn(Canvas c) {
        if (standIn == null) return 0f;
        float k = Math.max(getWidth() / (float) standIn.getWidth(), getHeight() / (float) standIn.getHeight()) * 1.08f;
        tmp.setScale(k, k);
        tmp.postTranslate((getWidth() - standIn.getWidth() * k) / 2f, (getHeight() - standIn.getHeight() * k) / 2f);
        paint.setAlpha(255);
        c.drawBitmap(standIn, tmp, paint);
        if (current == null) return 1f;
        long now = SystemClock.uptimeMillis();
        if (standInFadeAt < 0) standInFadeAt = now;
        float f = (now - standInFadeAt) / (float) STAND_IN_FADE_MS;
        if (f >= 1f) {
            dropStandIn();
            return 0f;
        }
        return 1f - f;
    }

    /** Releases the background loader, callbacks and bitmaps when the host window is torn down. */
    void release() {
        stop();
        main.removeCallbacksAndMessages(null);
        trim(ComponentCallbacks2.TRIM_MEMORY_COMPLETE);
        dropStandIn();
        mirrors.clear();
        ticks.clear();
        glassFilters.clear();
        listener = null;
        io.shutdownNow();
    }

    void skip() {
        if (incoming == null && pending == null && !loading) nextAt = 0;
        kick();
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
        kick();
    }

    void setParallax(float px) {
        parallaxFrom = parallax;
        parallaxTo = px;
        parallaxStart = SystemClock.uptimeMillis();
        kick();
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
        return showMs + FADE_MS;
    }

    boolean hasImage() {
        return current != null;
    }

    /** How far the photograph on show is through its time on screen, 0 to 1. */
    float shown() {
        Slide s = incoming != null ? incoming : current;
        if (s == null) return 0f;
        return Math.min(1f, (SystemClock.uptimeMillis() - s.start) / (float) (showMs + FADE_MS));
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
        kick();
    }

    void addMirror(View v) {
        if (!mirrors.contains(v)) mirrors.add(v);
    }

    void removeMirror(View v) {
        mirrors.remove(v);
    }

    /** When the drift last moved and the glass last followed: paced by the clock, not by counting frames. */
    private long lastDrift, lastMirror;

    /**
     * One frame. While something moves (a fade, the parallax, the rack focus, the intro) it asks for
     * the very next frame. Otherwise only the slow drift is left, which moves about a pixel a second:
     * it sleeps until its next step (15 a second, or 2 in Light mode) instead of waking the main
     * thread at every one of the screen's 60 refreshes to decide there was nothing to do.
     */
    @Override
    public void doFrame(long frameTimeNanos) {
        if (!running) return;
        long now = SystemClock.uptimeMillis();
        // a crossfade (or the intro) changes what the glass shows; the rack focus changes only the photograph
        boolean cross = incoming != null || intro < 1f || standIn != null;
        boolean fade = cross || now - defocusStart < defocusMs;
        boolean parallax = now - parallaxStart < PARALLAX_MS;
        // Light mode: the photograph holds still (see path), so nothing is redrawn while nothing
        // changes. On a slow TV each redraw of the photograph and its glass took the GPU 15 ms, and
        // the drift's two a second landed in the middle of remote presses as stutter. HIGH: it drifts
        // while home is in use and in photo mode; ten minutes after the last press anywhere else
        // (under a panel, editing) it rests until the next one, its clock stopped with it
        boolean still = calm || keyAt >= 0 && !photoMode && now - keyAt >= STILL_MS;
        if (still != (stillSince >= 0)) {
            if (still) {
                stillSince = now;
            } else {
                stillMs += now - stillSince;
                stillSince = -1;
            }
        }
        long driftEvery = 66;
        boolean drift = !still && now - lastDrift >= driftEvery;
        boolean busy = fade || parallax || drift;

        if (busy) {
            frame++;
            if (drift) lastDrift = now;
            step(now);
            invalidate();
            for (int i = 0; i < ticks.size(); i++) ticks.get(i).onTick(now);
            // blur is 1/22 scale, so a 4dp parallax nudge is invisible inside glass; keep mirrors cached while focus animates
            // through a crossfade the glass follows every third frame: its blur changes too slowly for
            // the difference to show, and two dozen panes redrawn per frame is what made fades stutter.
            // Not through a rack focus: the glass's blur is the same at any focus, and every move of the
            // focus between the apps and the widgets had all of it redrawn twenty times a second
            if (cross ? frame % 3 == 0 : !parallax && now - lastMirror >= (calm ? 500 : 200)) {
                lastMirror = now;
                for (int i = 0; i < mirrors.size(); i++) {
                    View v = mirrors.get(i);
                    if (v.isShown() && v.getAlpha() > 0f) v.invalidate();
                }
            }
        }
        // a resting copy made for another softness, Light mode switched either way, or another screen
        fit(current);
        fit(incoming);
        // Light mode: no decode and no crossfade within ten seconds of a press, so neither lands in
        // a run of them; a photograph that arrived meanwhile goes on once the remote rests
        boolean quiet = !calm || keyAt < 0 || now - keyAt >= QUIET_MS;
        if (pending != null && incoming == null && quiet) {
            Slide p = pending;
            pending = null;
            // it asks for the crossfade's frames itself
            begin(p);
            return;
        }
        long due = nextAt + (calm && photoMode && wanted == null && !stale ? Math.max(0, PHOTO_SHOW_MS - showMs) : 0);
        if (incoming == null && pending == null && !loading && now >= due && quiet && current != null) {
            // the list looked at again each turn (a file date or two): with a single photograph on
            // the wall nothing loaded, so nothing ever re-read it, and a source switched off (its
            // photographs removed) or new ones imported went unseen until Aura started again
            refreshWalls();
            if (!walls.isEmpty() && (walls.size() > 1 || wanted != null || stale || !onWall(current.wall))) load(index + 1, false);
            else nextAt = now + showMs;
        }
        Choreographer ch = Choreographer.getInstance();
        if (fade || parallax || incoming != null) ch.postFrameCallback(this);
        // still, only the next photograph's time is looked out for
        else if (still) ch.postFrameCallbackDelayed(this, 1000);
        else ch.postFrameCallbackDelayed(this, Math.max(0, lastDrift + driftEvery - SystemClock.uptimeMillis() - 4));
    }

    /** Something has started to move: frames at once, not at the drift's next step. */
    private void kick() {
        if (!running) return;
        Choreographer ch = Choreographer.getInstance();
        ch.removeFrameCallback(this);
        ch.postFrameCallback(this);
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
                if (current != null) current.recycle();
                current = incoming;
                incoming = null;
                current.alpha = 1f;
                accent = accentTo;
                // a photo sent from the phone meanwhile goes straight on next
                nextAt = now + (wanted != null || stale ? 0 : showMs);
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
        // a copy carrying the shade holds still through the intro: the shade lies on the screen, and
        // the intro's settle would carry it along with the photograph
        position(s, w, h, drift(s, now), s.baked ? 0f : 1f - intro, parallax, s.m);
    }

    /** How far through its drift {@code s} is. */
    private float drift(Slide s, long now) {
        return Ui.clamp((driftNow(now) - s.drift0) / (float) DRIFT_MS, 0f, 1f);
    }

    /** Where {@code s} lies on a {@code w}x{@code h} screen, {@code t} of the way through its drift. */
    private static void position(Slide s, float w, float h, float t, float settle, float parallax, Matrix out) {
        float scale = s.s0 + (s.s1 - s.s0) * t;
        scale *= 1f + 0.10f * settle * settle;
        float maxX = (scale - 1f) * w / 2f, maxY = (scale - 1f) * h / 2f;
        float tx = Ui.clamp((s.x0 + (s.x1 - s.x0) * t) * maxX + parallax, -maxX, maxX);
        float ty = Ui.clamp((s.y0 + (s.y1 - s.y0) * t) * maxY, -maxY, maxY);
        float k = Math.max(w / s.sw, h / s.sh) * scale;
        out.setScale(k, k);
        out.postTranslate((w - s.sw * k) / 2f + tx, (h - s.sh * k) / 2f + ty);
    }

    /** The drift's own clock: the time it stood still left out, so it moves on from where it stopped. */
    private long driftNow(long now) {
        return now - stillMs - (stillSince >= 0 ? now - stillSince : 0);
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
        fit(current);
        fit(incoming);
    }

    @Override
    protected void onDraw(Canvas c) {
        float ia = introAlpha();
        // the rack-focus dimming is a colour filter on the photo itself, not an extra full-screen fill
        int level = Math.round(64 * (1f - 0.30f * defocus));
        if (level != dimLevel) {
            dimLevel = level;
            int g = Math.round(255 * level / 64f);
            dim = level >= 64 ? null : new LightingColorFilter(Color.rgb(g, g, g), 0);
        }
        paint.setColorFilter(dim);
        boolean shaded = shaded();
        if (shade != null) shade.setBelow(shaded);
        float under = drawStandIn(c);
        if (under == 0f && (current == null || ia < 1f)) c.drawColor(Color.BLACK);
        // over the small copy the photograph fades in; with nothing under it, as it always did
        float over = under > 0f ? ia * (1f - under) : ia;
        // each slide's share of the finished frame (the incoming one lies over the current one),
        // and what's left of whatever lies under them both
        float in = incoming != null ? incoming.alpha * over : 0f;
        float cur = current != null ? current.alpha * over * (1f - in) : 0f;
        drawn = 1f - cur - in;
        drawSlide(c, current, cur, false, shaded);
        drawSlide(c, incoming, in, false, shaded);
        if (!shaded) return;
        // Light mode: the shade over everything live, then the resting copies that carry their own
        if (drawn > 0.002f) shade.drawOver(c);
        drawSlide(c, current, cur, true, true);
        drawSlide(c, incoming, in, true, true);
    }

    /**
     * The softness the home screen rests at. At exactly that level each photograph is drawn from
     * its pre-mixed copy: one full-screen pass instead of two, which on this GPU is the difference
     * between a frame inside 16 ms and one that misses it. Negative: no pre-mixed copies (screensaver).
     */
    private float steadyDefocus = -1f;

    void setSteadyDefocus(float d) {
        if (d == steadyDefocus) return;
        steadyDefocus = d;
        // the photographs on screen were pre-mixed at the old softness: mixed again on the loader,
        // so the resting picture doesn't jump when the setting changes (new ones come mixed right)
        fit(current);
        fit(incoming);
        invalidate();
    }

    /**
     * Draws {@code s}'s parts, {@code w} being its share of the finished frame: with {@code baked}
     * false the live ones (the sharp copy or a GIF, a soft copy, a resting copy without the shade),
     * with it true a resting copy that carries the shade. For a defocus d up to the softness s the
     * resting copy was mixed at, sharp·(1−d/s) + steady·(d/s) is exactly sharp·(1−d) + soft·d,
     * so no soft copy needs keeping.
     */
    private void drawSlide(Canvas c, Slide s, float w, boolean baked, boolean shaded) {
        if (s == null || s.sharp == null || w <= 0f) return;
        Bitmap st = s.steady;
        if (st != null && s.anim == null && (shaded || !s.baked) && drawable(c, st)) {
            // at exactly the resting softness, one full-screen pass instead of two
            float t = Math.abs(defocus - s.mixed) < 0.004f ? 1f : Ui.clamp(defocus / s.mixed, 0f, 1f);
            if (!baked) part(c, s, s.sharp, w * (1f - t));
            if (baked == s.baked) part(c, s, st, w * t);
            return;
        }
        if (baked) return;
        // no resting copy to use: the sharp copy (or the GIF) with the soft one over it, as far as defocused
        float d = s.soft != null && s.soft != s.sharp ? defocus : 0f;
        part(c, s, s.anim != null ? null : s.sharp, w * (1f - d));
        if (d > 0f) part(c, s, s.soft, w * d);
    }

    /**
     * One part of a slide (null: its GIF), {@code q} being its share of the finished frame. Laid at
     * that share of everything drawn so far, every part ends up with its own weight.
     */
    private void part(Canvas c, Slide s, Bitmap b, float q) {
        if (q < 0.002f) return;
        drawn += q;
        int a = Math.round(255 * q / drawn);
        if (b == null) {
            // the render thread steps the GIF's frames; this only places it
            if (a != s.animAlpha) s.anim.setAlpha(s.animAlpha = a);
            if (dim != s.animFilter) s.anim.setColorFilter(s.animFilter = dim);
            c.save();
            c.concat(s.m);
            s.anim.draw(c);
            c.restore();
            return;
        }
        paint.setAlpha(a);
        if (b == s.sharp) {
            c.drawBitmap(b, s.m, paint);
            return;
        }
        tmp.set(s.m);
        tmp.preScale(s.sw / (float) b.getWidth(), s.sh / (float) b.getHeight());
        c.drawBitmap(b, tmp, paint);
    }

    /** A hardware bitmap can't be drawn into a software canvas (a screenshot, a test). */
    private static boolean drawable(Canvas c, Bitmap b) {
        return c.isHardwareAccelerated() || Build.VERSION.SDK_INT < 26 || b.getConfig() != Bitmap.Config.HARDWARE;
    }

    /** Light mode with the shade attached: the photograph carries it (see {@link #setShade}). */
    private boolean shaded() {
        return shade != null && calm;
    }

    /** The size the photograph fills: this view's, or before its first layout the screen's. */
    private int viewW() {
        return getWidth() > 0 ? getWidth() : getResources().getDisplayMetrics().widthPixels;
    }

    private int viewH() {
        return getHeight() > 0 ? getHeight() : getResources().getDisplayMetrics().heightPixels;
    }

    /** Has {@code s}'s resting copy made again when it was made for another softness, Light mode or screen. */
    private void fit(Slide s) {
        if (s == null || s.steady == null || s.remaking || steadyDefocus <= 0f) return;
        boolean bake = shaded();
        if (s.mixed != steadyDefocus || s.baked != bake || bake && (s.bakedW != viewW() || s.bakedH != viewH())) remake(s);
    }

    /**
     * Makes {@code s}'s resting copy again on the loader, the soft copy with it if it's gone; the
     * slide draws with what it has meanwhile. Its sharp copy, which the loader reads, isn't freed
     * before that's done (see {@link Slide#recycle}). No room: the slide goes without one.
     */
    private void remake(final Slide s) {
        if (io.isShutdown()) return;
        s.remaking = true;
        final float d = steadyDefocus;
        final Scrim bake = shaded() ? shade : null;
        final int vw = viewW(), vh = viewH();
        final float px = parallaxTo;
        // Light mode turned on mid-drift: the drift stands still where it was, and the shade lies there
        final float t = drift(s, SystemClock.uptimeMillis());
        io.execute(() -> {
            Bitmap made = null;
            try {
                Bitmap soft = s.soft != null ? s.soft : soften(s.sharp);
                made = steady(s, soft, d, bake, vw, vh, px, t);
                if (soft != s.soft) soft.recycle();
            } catch (Throwable e) {
                Trouble.caught("photo (resting copy)", e);
            }
            final Bitmap copy = made;
            main.post(() -> {
                s.remaking = false;
                if (s.dropped) {
                    if (copy != null) copy.recycle();
                    s.recycle();
                    return;
                }
                Bitmap old = s.steady;
                s.steady = copy;
                s.mixed = d;
                s.baked = bake != null && copy != null;
                s.bakedW = vw;
                s.bakedH = vh;
                if (old != null && old != s.sharp && old != s.soft) old.recycle();
                if (copy != null && s.soft != null && s.soft != s.sharp) {
                    s.soft.recycle();
                    s.soft = null;
                }
                invalidate();
            });
        });
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
        ColorFilter cf = p.getColorFilter();
        blurRound(c, current, r, radius, ox, oy, p, ia, cf);
        blurRound(c, incoming, r, radius, ox, oy, p, ia, cf);
        p.setShader(null);
        p.setColorFilter(cf);
    }

    private void blurRound(Canvas c, Slide s, RectF r, float radius, float ox, float oy, Paint p, float ia, ColorFilter cf) {
        if (s == null || s.blur == null) return;
        BitmapShader sh = null;
        if (calm && cf != null) {
            if (glassFilters.size() < 16) glassFilters.add(cf);
            sh = s.tintedShader(cf);
        }
        if (sh != null) {
            p.setColorFilter(null);
        } else {
            if (s.blurShader == null) s.blurShader = new BitmapShader(s.blur, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            sh = s.blurShader;
            p.setColorFilter(cf);
        }
        tmp.set(s.m);
        tmp.preScale(s.sw / (float) s.blur.getWidth(), s.sh / (float) s.blur.getHeight());
        tmp.postTranslate(-ox, -oy);
        sh.setLocalMatrix(tmp);
        p.setShader(sh);
        p.setAlpha(Math.round(255 * s.alpha * ia));
        if (calm && radius <= 0f) c.drawRect(r, p);
        else c.drawRoundRect(r, radius, radius, p);
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
        // released (the screensaver ended): a late draw asking for the next photo must not reach the closed loader
        if (walls.isEmpty() || io.isShutdown()) return;
        loading = true;
        // this load comes from the fresh list, so whatever made the old one stale is handled
        stale = false;
        int f = wanted != null ? -1 : fresh(), want = -1;
        for (int k = 0; wanted != null && k < walls.size(); k++) if (wanted.equals(walls.get(k).path)) want = k;
        wanted = null;
        final int idx = want >= 0 ? want : f >= 0 ? f : deal();
        if (f >= 0) prefs.edit().putString("daily_seen", walls.get(f).day).apply();
        final int gen = generation;
        final Wallpaper w = walls.get(idx);
        final Scrim bake = shaded() ? shade : null;
        final int vw = viewW(), vh = viewH();
        final float px = parallaxTo;
        io.execute(() -> {
            Slide made;
            try {
                made = decode(w, bake, vw, vh, px);
            } catch (Throwable e) {
                // whatever happened, the answer still goes back: "loading" must never stay stuck on
                Trouble.caught("photo", e);
                made = null;
            }
            final Slide s = made;
            main.post(() -> {
                if (gen != generation) {
                    if (s != null) s.recycle();
                    return;
                }
                loading = false;
                index = idx;
                if (s == null) {
                    // nothing on screen yet (the boot screen is waiting for it): the next photograph
                    // at once, a few times, rather than five seconds of waiting each
                    failures++;
                    nextAt = SystemClock.uptimeMillis() + (current == null && failures < Math.min(4, walls.size()) ? 0 : 5_000);
                    if (nextAt <= SystemClock.uptimeMillis() && running) load(idx + 1, false);
                    return;
                }
                failures = 0;
                prefs.edit().putInt("wall", idx).apply();
                // Light mode: one that arrives while the remote is in use waits for it to rest (doFrame)
                if (current != null && calm && keyAt >= 0 && SystemClock.uptimeMillis() - keyAt < QUIET_MS) {
                    if (pending != null) pending.recycle();
                    pending = s;
                } else {
                    begin(s);
                }
            });
        });
    }

    private static String key(Wallpaper w) {
        return w.onDisk() ? w.path : w.black ? "black" : w.file;
    }

    /** {@code w} is still among the photographs (not removed with its source, or by hand). */
    private boolean onWall(Wallpaper w) {
        if (w == null) return true;
        String k = key(w);
        for (Wallpaper x : walls) if (k.equals(key(x))) return true;
        return false;
    }

    /**
     * The next photograph, from a shuffled deck the home screen and the screensaver share. Each kept
     * its own place in the rotation before, and the home screen wrote its (older) place back over
     * the screensaver's, so the screensaver kept starting from the same few photographs. Now the
     * deck lives in the settings and is read afresh every turn: nothing repeats until everything
     * has been shown, restarts included. New photographs join at random places. Sky Sync deals the
     * next card whose light suits the sky outside; the others wait their turn in the deck.
     */
    private int deal() {
        int n = walls.size();
        java.util.Map<String, Integer> at = new java.util.HashMap<>();
        for (int k = 0; k < n; k++) at.put(key(walls.get(k)), k);
        String[] saved = android.text.TextUtils.split(prefs.getString("wall_deck", ""), "\n");
        java.util.Set<String> known = new java.util.HashSet<>(java.util.Arrays.asList(
                android.text.TextUtils.split(prefs.getString("wall_known", ""), "\n")));
        List<String> deck = new ArrayList<>();
        for (String k : saved) if (at.containsKey(k)) deck.add(k);
        List<String> recent = new ArrayList<>();
        for (String k : android.text.TextUtils.split(prefs.getString("wall_recent", ""), "\n")) if (at.containsKey(k)) recent.add(k);
        for (String k : at.keySet()) {
            if (!known.contains(k)) {
                deck.add(rnd.nextInt(deck.size() + 1), k);
                known.add(k);
            }
        }
        Sun.Phase phase = Sun.phase(System.currentTimeMillis());
        int pick = suiting(deck, at, phase);
        if (pick < 0) {
            // the deck is spent (or holds nothing for this light): a fresh shuffle, with the dozen
            // shown most recently at the back, oldest first, so none of them comes straight back
            deck.clear();
            for (String k : at.keySet()) if (!recent.contains(k)) deck.add(k);
            java.util.Collections.shuffle(deck, rnd);
            int unseen = deck.size();
            deck.addAll(recent);
            known.clear();
            known.addAll(at.keySet());
            // the light's own cards first, but never one just shown: with a single night photograph
            // in the collection, every reshuffle dealt it again and it stayed on all evening
            pick = suiting(deck.subList(0, unseen), at, phase);
            if (pick < 0) pick = 0;
        }
        String k = deck.remove(pick);
        recent.remove(k);
        recent.add(k);
        int keep = Math.max(1, Math.min(12, n / 2));
        while (recent.size() > keep) recent.remove(0);
        prefs.edit().putString("wall_deck", android.text.TextUtils.join("\n", deck))
                .putString("wall_known", android.text.TextUtils.join("\n", known))
                .putString("wall_recent", android.text.TextUtils.join("\n", recent)).apply();
        return at.get(k);
    }

    /** The first card in {@code deck} whose light suits {@code phase} (your own photos always do), or -1. */
    private int suiting(List<String> deck, java.util.Map<String, Integer> at, Sun.Phase phase) {
        for (int k = 0; k < deck.size(); k++) {
            Wallpaper w = walls.get(at.get(deck.get(k)));
            if (w.mine || w.black || Sun.suits(w.mood, phase)) return k;
        }
        return -1;
    }

    /** The photograph on show's tiny frosted copy, for the cover over a slow Home on older Fire TVs ({@link HomeCover}). */
    static volatile Bitmap lastBlur;

    private void begin(Slide s) {
        lastBlur = s.blur;
        long now = SystemClock.uptimeMillis();
        s.start = now;
        s.drift0 = driftNow(now);
        boolean first = current == null;
        if (first) {
            s.alpha = 1f;
            current = s;
            accent = accentFrom = accentTo = s.wall.accent;
            nextAt = now + showMs;
            if (intro < 1f) introStart = now;
        } else {
            if (incoming != null) {
                play(incoming, false);
                incoming.recycle();
            }
            s.alpha = 0f;
            incoming = s;
            accentFrom = accent;
            accentTo = s.wall.accent;
        }
        place(s, now);
        fit(s);
        if (running) play(s, true);
        // a crossfade (or the first photograph) needs every frame from now
        kick();
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
        // Light mode: framed once and held, so a redraw for any other reason doesn't move it either
        if (calm) {
            s.s1 = s.s0;
            s.x1 = s.x0;
            s.y1 = s.y0;
        }
    }

    /**
     * Pure black with the faintest night-blue glow from the top left, so the glass still has
     * something to frost; on an OLED screen almost every pixel stays off.
     */
    private static Bitmap blackWall() {
        int w = 640, h = 360;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(Color.BLACK);
        Paint p = new Paint(Paint.DITHER_FLAG);
        p.setShader(new android.graphics.RadialGradient(w * 0.22f, -h * 0.1f, w * 0.85f,
                new int[]{0xFF101827, 0xFF06080E, 0xFF000000}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        return b;
    }

    /**
     * Reads {@code w} into a slide, framed (see path); given {@code bake}, its resting copy carries that
     * shade as it will lie over a {@code vw}x{@code vh} screen with the photograph {@code px} aside.
     */
    private Slide decode(Wallpaper w, Scrim bake, int vw, int vh, float px) {
        if (w.black) {
            Bitmap b = blackWall();
            Slide s = new Slide();
            s.wall = w;
            s.sharp = b;
            s.sw = b.getWidth();
            s.sh = b.getHeight();
            s.blur = blur(b);
            if (calm) for (ColorFilter cf : glassFilters) s.tintedShader(cf);
            s.soft = b;
            s.lumaTop = s.lumaBottom = 0f;
            path(s);
            return s;
        }
        try {
            Bitmap b = read(w);
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
            path(s);
            s.blur = blur(b);
            if (calm) for (ColorFilter cf : glassFilters) s.tintedShader(cf);
            s.lumaTop = luma(s.blur, 0f, 0f, 0.62f, 0.66f);
            s.lumaBottom = luma(s.blur, 0f, 0.6f, 1f, 1f);
            // the soft copy is an ingredient of the resting copy (and of a GIF's defocus) and goes
            // once that's made; the screensaver, with no resting softness, never draws it at all
            float d = steadyDefocus;
            if (d > 0f) {
                s.soft = soften(b);
                Bitmap st = s.anim == null ? steady(s, s.soft, d, bake, vw, vh, px, 0f) : null;
                if (st != null) {
                    s.steady = st;
                    s.mixed = d;
                    s.baked = bake != null;
                    s.bakedW = vw;
                    s.bakedH = vh;
                    s.soft.recycle();
                    s.soft = null;
                }
            }
            return s;
        } catch (IOException | RuntimeException e) {
            return null;
        } catch (OutOfMemoryError e) {
            // a 1 GB TV short of room: this photograph is skipped, the home screen stays
            Trouble.caught("photo (memory)", e);
            return null;
        }
    }

    private java.io.InputStream open(Wallpaper w) throws IOException {
        return w.onDisk() ? new java.io.FileInputStream(w.path) : getContext().getAssets().open("wallpapers/" + w.file);
    }

    /**
     * The photograph, no larger than the screen at the drift's deepest zoom (1.2x; Light mode,
     * which holds it still, 1.1x): a 1080p photograph on a 720p TV would hold twice the memory for
     * detail no one can see. The decoder halves it as often as it can first, so the scale after
     * that starts from at most twice the size kept, never from the whole picture. Kept in 16 bits,
     * it's decoded in strips at full colour and dithered in (see {@link #bands}).
     */
    private Bitmap read(Wallpaper w) throws IOException {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = open(w)) {
            BitmapFactory.decodeStream(in, null, o);
        }
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int iw = o.outWidth, ih = o.outHeight, tw = iw, th = ih;
        float k = (calm ? 1.1f : 1.2f) * Math.max(Math.max(dm.widthPixels, dm.heightPixels) / (float) iw,
                Math.min(dm.widthPixels, dm.heightPixels) / (float) ih);
        if (k < 0.95f) {
            tw = Math.max(1, Math.round(iw * k));
            th = Math.max(1, Math.round(ih * k));
        }
        int sample = 1;
        while (iw / (sample * 2) >= tw && ih / (sample * 2) >= th) sample *= 2;
        Bitmap b = thrifty() && !w.gif ? banded(w, sample, tw, th) : null;
        if (b == null) {
            o.inJustDecodeBounds = false;
            o.inSampleSize = sample;
            if (tw != iw) {
                o.inScaled = true;
                o.inDensity = (iw + sample - 1) / sample;
                o.inTargetDensity = tw;
            }
            if (thrifty()) o.inPreferredConfig = Bitmap.Config.RGB_565;
            try (InputStream in = open(w)) {
                b = BitmapFactory.decodeStream(in, null, o);
            }
        }
        // drawn through matrices only: no density of its own to scale by anywhere
        if (b != null) b.setDensity(Bitmap.DENSITY_NONE);
        return b;
    }

    /** A 16-bit photograph decoded in strips (see {@link #bands}); null where this image can't be read that way. */
    private Bitmap banded(Wallpaper w, int sample, int tw, int th) {
        Bitmap out = null;
        BitmapRegionDecoder rd = null;
        try (InputStream in = open(w)) {
            rd = BitmapRegionDecoder.newInstance(in, false);
            out = Bitmap.createBitmap(tw, th, Bitmap.Config.RGB_565);
            Matrix m = new Matrix();
            m.setScale(tw / (float) rd.getWidth(), th / (float) rd.getHeight());
            if (bands(rd, sample, m, out)) return out;
        } catch (IOException | RuntimeException e) {
            // a format the strips can't be read from: decoded whole instead
        } finally {
            if (rd != null) rd.recycle();
        }
        if (out != null) out.recycle();
        return null;
    }

    /**
     * Draws the image {@code rd} reads into {@code into} through {@code m} (image pixels to its
     * pixels: a scale and a shift), about {@link #BAND} decoded rows at a time, each strip decoded at
     * full colour (every {@code sample}th pixel) and drawn through a dithering shader. A 16-bit copy
     * made this way keeps its skies smooth: the decoder's own 16-bit output truncates, and a plain
     * same-size copy into 16 bits takes a path that doesn't dither. False if a strip won't decode.
     */
    static boolean bands(BitmapRegionDecoder rd, int sample, Matrix m, Bitmap into) {
        int iw = rd.getWidth(), ih = rd.getHeight(), w = into.getWidth(), h = into.getHeight();
        Matrix inv = new Matrix(), local = new Matrix();
        if (!m.invert(inv)) return false;
        float[] v = new float[9];
        m.getValues(v);
        int step = Math.max(8, Math.round(BAND * sample * v[Matrix.MSCALE_Y]));
        // a few rows and columns beyond each strip, so the filter never reaches past its edge
        int pad = 2 * sample;
        Canvas c = new Canvas(into);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        RectF r = new RectF();
        Rect src = new Rect();
        for (int y = 0; y < h; y += step) {
            int y1 = Math.min(h, y + step);
            r.set(0, y, w, y1);
            inv.mapRect(r);
            // starting on whole steps of the sample, every strip samples the same grid of the image
            src.set(Math.max(0, ((int) Math.floor(r.left) - pad) / sample * sample),
                    Math.max(0, ((int) Math.floor(r.top) - pad) / sample * sample),
                    Math.min(iw, (int) Math.ceil(r.right) + pad), Math.min(ih, (int) Math.ceil(r.bottom) + pad));
            if (src.isEmpty()) continue;
            Bitmap band = rd.decodeRegion(src, o);
            if (band == null) return false;
            local.setScale(src.width() / (float) band.getWidth(), src.height() / (float) band.getHeight());
            local.postTranslate(src.left, src.top);
            local.postConcat(m);
            BitmapShader sh = new BitmapShader(band, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            sh.setLocalMatrix(local);
            p.setShader(sh);
            c.drawRect(0, y, w, y1, p);
            p.setShader(null);
            band.recycle();
        }
        return true;
    }

    private boolean thrifty() {
        if (thrifty == null) thrifty = Perf.lowEnd(getContext());
        return thrifty;
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

    /**
     * {@code sharp} with {@code soft} laid over it at {@code k}, exactly as the two draws would
     * composite, at no more than the screen's size (it is only ever drawn filling the screen).
     */
    private Bitmap mix(Bitmap sharp, Bitmap soft, float k) {
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int sw = Math.max(dm.widthPixels, dm.heightPixels), sh = Math.min(dm.widthPixels, dm.heightPixels);
        float sc = Math.min(1f, Math.max(sw / (float) sharp.getWidth(), sh / (float) sharp.getHeight()));
        int w = Math.max(1, Math.round(sharp.getWidth() * sc)), h = Math.max(1, Math.round(sharp.getHeight() * sc));
        try {
            Bitmap out = Bitmap.createBitmap(w, h, thrifty() ? Bitmap.Config.RGB_565 : Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
            android.graphics.Rect all = new android.graphics.Rect(0, 0, w, h);
            c.drawBitmap(sharp, null, all, p);
            p.setAlpha(Math.round(255 * k));
            c.drawBitmap(soft, null, all, p);
            return out;
        } catch (OutOfMemoryError e) {
            // without it, the two draws still work
            return null;
        }
    }

    /**
     * The resting copy: {@code soft} mixed over the sharp copy at {@code d} and, given {@code bake},
     * that shade and its grain over it as they'll lie on a {@code vw}x{@code vh} screen, the
     * photograph held {@code t} of the way through its drift (Light mode holds it still), {@code px}
     * aside. Baked, it's a hardware bitmap on Android 9 and later, with no copy left in the app's
     * memory. Null without room.
     */
    private Bitmap steady(Slide s, Bitmap soft, float d, Scrim bake, int vw, int vh, float px, float t) {
        Bitmap out = mix(s.sharp, soft, d);
        if (out == null || bake == null) {
            if (out != null) out.prepareToDraw();
            return out;
        }
        Matrix m = new Matrix(), inv = new Matrix();
        position(s, vw, vh, t, 0f, px, m);
        m.preScale(s.sw / (float) out.getWidth(), s.sh / (float) out.getHeight());
        if (!m.invert(inv)) {
            out.recycle();
            return null;
        }
        Canvas c = new Canvas(out);
        c.concat(inv);
        bake.bake(c, vw, vh, s.lumaTop, s.lumaBottom);
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                Bitmap hw = out.copy(Bitmap.Config.HARDWARE, false);
                // off a device (a test's runtime) the "copy" comes back as an empty software bitmap
                if (hw != null && hw.getConfig() == Bitmap.Config.HARDWARE) {
                    out.recycle();
                    return hw;
                }
                if (hw != null) hw.recycle();
            } catch (RuntimeException | OutOfMemoryError e) {
                // it stays in software
            }
        }
        out.prepareToDraw();
        return out;
    }

    static Bitmap blur(Bitmap src) {
        Bitmap quarter = Bitmap.createScaledBitmap(src, Math.max(1, src.getWidth() / 4), Math.max(1, src.getHeight() / 4), true);
        Bitmap small = Bitmap.createScaledBitmap(quarter, 128, 72, true);
        if (quarter != src && quarter != small) quarter.recycle();
        int w = small.getWidth(), h = small.getHeight();
        int[] px = new int[w * h], tmp = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        if (small != src) small.recycle();
        for (int pass = 0; pass < 3; pass++) {
            boxBlur(px, tmp, w, h, 4, true);
            boxBlur(tmp, px, w, h, 4, false);
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
    }

    /** A shallow depth-of-field copy: detail melts but shapes stay readable, like a lens racked to the foreground. */
    static Bitmap soften(Bitmap src) {
        Bitmap small = Bitmap.createScaledBitmap(src, 768, 432, true);
        int w = small.getWidth(), h = small.getHeight();
        int[] px = new int[w * h], tmp = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        if (small != src) small.recycle();
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
