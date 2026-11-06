package app.aura.home;

import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * App card. Focused, a bright ring hugs it, it glows in the app's own colour and catches a sheen;
 * in the carousel it can also recede (dim) and cast a reflection. It takes focus as measured on
 * tvOS 26: an app tile rises to x1.20 on a quick spring with a small overshoot, a content card
 * ({@link #asContent}) eases to x1.10, and a press sinks either to 0.95 of that. On LOW the rise
 * is a short ease-out and only view properties move.
 */
final class Card extends View {
    interface FocusListener {
        void onFocus(Card c, boolean gained);
    }

    final int color;
    Runnable onClick, onLongClick;
    FocusListener focusListener;
    boolean badge;
    /** Grid cards lift themselves on focus; carousel cards are positioned by the carousel. */
    boolean lift = true;
    /** While focused, drift gently in 3D, like an Apple TV icon catching the light. */
    boolean sway;
    /** The bright ring on focus; the switcher's big cards go without, as on tvOS. */
    boolean bezel = true;
    /** Apple TV tile: borderless rounded card that lifts with a deep drop shadow and glossy sheen. */
    boolean tvos;
    /**
     * The picture's resolution against the card's size. The switcher's near-full-screen cards paint
     * at 0.6 and the GPU scales them up: their pictures are 640x360 anyway, and the texture to
     * upload and sample drops from ~2 MB to ~0.7 MB.
     */
    float res = 1f;
    /**
     * The picture already painted off the main thread at this card's size (Top Shelf, and home's
     * tiles handed over by {@link #take}): used as it is, instead of being painted on the main thread
     * as the card is laid out.
     */
    Bitmap ready;
    /**
     * Home's tiles: the picture is painted behind the screen and handed over ({@link #take}), never
     * on the main thread as the card is laid out; until it lands, the card shows its colour.
     */
    boolean deferred;
    /** Its picture covers it edge to edge with nothing transparent: on LOW it's painted in 16-bit colour. */
    boolean opaque;
    /**
     * Set before the card is attached: it becomes a pane of frosted glass over the photograph,
     * tinted with the app's colour, and its art (a lifted logo) is laid on top.
     */
    Backdrop glass;
    /** Runs on every frame the card rises, settles or is pressed: a cell's name rides the tile's growth. */
    Runnable moved;
    /** Its focus shadow is one of {@link FocusLayer}'s shared views (LOW), so it draws none of its own. */
    boolean layered;
    /** The share of their full durations its last focus change ran at: a quick run shortens them ({@link Ui#speedIn}). */
    float pace = 1f;
    /** When any card last took focus: the time since sets the pace of the next move. */
    static long movedAt = Long.MIN_VALUE / 2;
    private ValueAnimator swayAnim;
    private final Paint blur = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG), pane = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int[] loc = new int[2];
    private int paneKey = -1;

    private Tiles.Painter painter;
    private final float radius;
    private final RectF rect = new RectF();
    private final Paint art = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint plain = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mark = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix glintMatrix = new Matrix(), artMatrix = new Matrix(), spotMatrix = new Matrix();
    private final Paint spot = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** Parallax on focus (Settings): the art moves a layer beneath the glass as the tile tilts. */
    static boolean parallax = true;
    private LinearGradient glintShader;
    private Bitmap bitmap, reflection, liftShadow;
    private final Paint shadowPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Matrix shadowMatrix = new Matrix();
    private float focus, dim, glintPos = -1f, artAlpha = 1f;
    private ValueAnimator focusAnim, glintAnim, tween, pressAnim, artFade;
    private boolean longFired;

    /** A poster or a show to continue rather than an app: x1.10, easing in and letting go slowly. */
    private boolean content;
    /** Room on each side to grow into, in px; below 0, the full token scale. */
    private float room = -1f;
    /** How far it has risen into focus (0 at rest, 1 focused; the spring takes it a touch past), and the press on top. */
    private float risen, pressed = 1f;
    /** Each move of the rise runs from riseFrom to riseTo, carrying the translation from (tx0, ty0) to (tx1, ty1). */
    private float riseFrom, riseTo, tx0, ty0, tx1, ty1;
    /** The lean toward the way focus came, in px. */
    private float leanX, leanY;
    private float glowFrom, glowTo, tweenFrom, tweenTo, pressFrom, pressTo;
    private Spring.Mover spring;
    private float springPace;
    private boolean springContent;
    /** Where in the window the last card to lose focus was, and when: the next one leans away from it. */
    private static long leftAt = Long.MIN_VALUE / 2;
    private static float leftX, leftY;
    /** An app tile's rim and shadow light up over this as it rises (§8.3.3); a logo that lands late fades in as long. */
    private static final int LIGHT_IN = 150;
    /** The glass's colour filter at 65 strengths of focus, shared by every card: none is made as a card lights. */
    private static final ColorMatrixColorFilter[] PANES = new ColorMatrixColorFilter[65];
    /** It let its picture go ({@link #release}), or shows another tile's until its own lands ({@link #standIn}). */
    private boolean released, stale;
    /** No room for its picture last time: its colour until it's let go again. */
    private boolean starved;
    private boolean attached;
    /** What its pictures add to {@link #HELD} while it's attached. */
    private int counted;
    /** The bytes held by the pictures of attached cards, and how many cards hold one: read in Problem reports. */
    private static final AtomicLong HELD = new AtomicLong();
    private static final AtomicInteger PICTURED = new AtomicInteger();

    Card(Context c, Tiles.Painter painter, int color, float radiusDp) {
        super(c);
        this.painter = painter;
        this.color = color;
        this.radius = Ui.dpf(radiusDp);
        setFocusable(true);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
                // a shadow would show through the glass and muddy it; the bloom lights glass instead
                o.setAlpha(glass != null || tvos ? 0f : 1f);
            }
        });
        // coloured shadows arrived in Android 9; earlier ones are simply grey
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            setOutlineSpotShadowColor(color);
            setOutlineAmbientShadowColor(Ui.mix(color, 0xFF000000, 0.4f));
        }
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(Math.max(1f, Ui.dpf(0.75f)));
        rim.setColor(0xFFFFFFFF);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(Ui.dpf(radiusDp > 12 ? 2.2f : 1.8f));
        shade.setColor(0xFF000000);
        plain.setColor(Ui.mix(color, 0xFF000000, 0.55f));
        mark.setColor(0xFFFFFFFF);
        mark.setShadowLayer(Ui.dpf(3), 0, Ui.dpf(1), 0x99000000);
    }

    /** Marks a content card (a poster, a show to continue): it rises to x1.10 and eases in and out like one. */
    Card asContent() {
        content = true;
        return this;
    }

    /**
     * Room on each side the card may grow into, in dp (half the gap to its neighbours): focused, it
     * takes 90 % of it at most, so it never touches them. Unset, it grows to the full token scale.
     */
    void setGrowRoom(float dpEachSide) {
        room = dpEachSide < 0f ? -1f : Ui.dpf(dpEachSide);
        if (risen != 0f) applyScale();
    }

    /** The scale focus brings it to: x1.20 for an app, x1.10 for content, held within its room. */
    float focusedScale() {
        float token = content ? Ui.FOCUS_SCALE_CONTENT : Ui.FOCUS_SCALE_APP;
        int w = getWidth();
        return room < 0f || w <= 0 ? token : Math.max(1f, Math.min(token, 1f + 2f * 0.9f * room / w));
    }

    /** How far it has risen into focus: 0 at rest, 1 focused. */
    float risen() {
        return risen;
    }

    /** The baked shadow for its size (shared by every card that size), or null before it's laid out. */
    Bitmap shadow() {
        return liftShadow;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        rect.set(0, 0, w, h);
        reflection = null;
        if (w > 0 && h > 0) {
            int bw = Math.max(1, Math.round(w * res)), bh = Math.max(1, Math.round(h * res));
            boolean whole = true;
            if (ready != null && ready.getWidth() == bw && ready.getHeight() == bh) {
                bitmap = ready;
            } else if (deferred) {
                // painted behind the screen and handed over (take): its colour until then
                ready = null;
                bitmap = null;
                stale = false;
            } else {
                // painted for another size: let go, the card paints its own
                ready = null;
                try {
                    bitmap = Bitmap.createBitmap(bw, bh, config(opaque));
                    released = false;
                    whole = paintArt(w, h);
                } catch (OutOfMemoryError e) {
                    noRoom();
                }
            }
            if (bitmap != null && whole) showArt(w, h);
            else art.setShader(null);
            // the shadow a raised tile casts where no real one is drawn (glass and tvOS tiles on HIGH,
            // every tile on LOW): baked once per size and shared, never made as focus moves
            liftShadow = lift ? Ui.shadow(w, h, radius) : null;
        }
        // a hairline all the way round the pane, brightest where the light falls on the top edge
        rim.setShader(new LinearGradient(0, 0, w * 0.35f, h,
                new int[]{0x8CFFFDF9, 0x3DFFFDF9, 0x2EFFFFFF}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        ring.setShader(new LinearGradient(0, 0, 0, h, Ui.RING_TOP, Ui.RING_BOTTOM, Shader.TileMode.CLAMP));
        sheen.setShader(new LinearGradient(0, 0, w * 0.55f, h, 0x38FFFDF9, 0x00FFFFFF, Shader.TileMode.CLAMP));
        glintShader = new LinearGradient(0, 0, w * 0.42f, h, new int[]{0x00FFFFFF, 0x66FFFFFF, 0x00FFFFFF},
                new float[]{0.4f, 0.5f, 0.6f}, Shader.TileMode.CLAMP);
        glint.setShader(glintShader);
        // the parallax light: made once, moved by a matrix (a new gradient every frame costs this GPU ~10 ms)
        spot.setShader(new android.graphics.RadialGradient(0, 0, 1, 0xFFFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        account();
    }

    /** 16 bits for an opaque picture on LOW, half the memory and the upload; anything with transparency in 32. */
    private static Bitmap.Config config(boolean opaque) {
        return opaque && Ui.light ? Bitmap.Config.RGB_565 : Bitmap.Config.ARGB_8888;
    }

    /**
     * Paints the picture into {@link #bitmap}; false when a logo it needs is still being lifted. The
     * picture isn't shown until it's whole: it's painted again once the logo lands, over itself, which
     * only a bitmap no frame is drawing from allows. A card taken off screen or given another picture
     * or size by then is left as it is.
     */
    private boolean paintArt(int w, int h) {
        Bitmap b = bitmap;
        Tiles.Painter p = painter;
        Canvas cv = new Canvas(b);
        cv.scale(b.getWidth() / (float) w, b.getHeight() / (float) h);
        return Tiles.paint(p, cv, w, h, () -> {
            if (bitmap != b || painter != p || !isAttachedToWindow()) return;
            b.eraseColor(0);
            try {
                if (paintArt(w, h)) {
                    showArt(w, h);
                    // landed after a cold start: on HIGH it comes up instead of popping in
                    if (!Ui.light) fadeArtIn();
                }
            } catch (OutOfMemoryError e) {
                noRoom();
            }
            reflection = null;
            account();
            invalidate();
        });
    }

    /** The painted picture on the card, scaled up to it from the resolution it was painted at. */
    private void showArt(int w, int h) {
        BitmapShader bs = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        if (bitmap.getWidth() != w || bitmap.getHeight() != h) {
            Matrix up = new Matrix();
            up.setScale(w / (float) bitmap.getWidth(), h / (float) bitmap.getHeight());
            bs.setLocalMatrix(up);
        }
        art.setShader(bs);
    }

    /** The picture fading in over the card's colour in 150 ms, a redraw a frame for that long only. */
    private void fadeArtIn() {
        if (artFade == null) {
            artFade = ValueAnimator.ofFloat(0f, 1f);
            artFade.setDuration(LIGHT_IN);
            artFade.setInterpolator(Ui.EASE_DECEL);
            artFade.addUpdateListener(a -> {
                artAlpha = a.getAnimatedFraction();
                invalidate();
            });
        }
        artFade.cancel();
        artAlpha = 0f;
        artFade.start();
    }

    /**
     * No room for the picture (a 1 GB TV out of memory as a shelf refreshes): the card goes without
     * it (see {@link #onDraw}), and the caches make space for the next one.
     */
    private void noRoom() {
        bitmap = null;
        art.setShader(null);
        Continue.trim();
        Tiles.trim();
        account();
    }

    /** A band of light sweeps once across the card, like a reflection catching glass. */
    void glint() {
        if (glintShader == null || (glintAnim != null && glintAnim.isRunning())) return;
        glintAnim = ValueAnimator.ofFloat(0f, 1f);
        glintAnim.setDuration(1150);
        glintAnim.setInterpolator(Ui.SOFT);
        glintAnim.addUpdateListener(a -> {
            glintPos = a.getAnimatedFraction() >= 1f ? -1f : (float) a.getAnimatedValue();
            invalidate();
        });
        glintAnim.start();
    }

    /** Mirror image of the top of the card fading to nothing, baked once. */
    Bitmap reflection() {
        if (reflection != null || bitmap == null) return reflection;
        int w = bitmap.getWidth(), h = Math.round(bitmap.getHeight() * 0.42f);
        try {
            reflection = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        } catch (OutOfMemoryError e) {
            // the carousel does without its reflection rather than take the home screen down
            return null;
        }
        Canvas c = new Canvas(reflection);
        Matrix flip = new Matrix();
        flip.setScale(1f, -1f);
        flip.postTranslate(0, bitmap.getHeight());
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        BitmapShader s = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        s.setLocalMatrix(flip);
        p.setShader(s);
        c.drawRoundRect(0, -radius, w, h, radius, radius, p);
        Paint fade = new Paint();
        fade.setShader(new LinearGradient(0, 0, 0, h, 0x47FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        fade.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        c.drawRect(0, 0, w, h, fade);
        account();
        return reflection;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // tiles at rest cast no shadow (the focused one's comes with its rise); the switcher's cards,
        // which don't rise, keep a slight one on HIGH, and LOW casts none at all (PC6)
        setElevation(lift || Ui.light ? 0f : Ui.dpf(3));
        // glass repaints with the photograph's drift, like the other glass
        if (glass != null) glass.addMirror(this);
        attached = true;
        account();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (swayAnim != null) swayAnim.cancel();
        // ended at rest rather than cut off midway: the switcher shows its cards again, and one must
        // not come back half lit or half grown
        stopRise();
        if (pressAnim != null) pressAnim.cancel();
        if (focusAnim != null && focusAnim.isStarted()) focusAnim.end();
        if (glintAnim != null) glintAnim.end();
        if (artFade != null && artFade.isStarted()) artFade.end();
        animate().cancel();
        risen = riseFrom = riseTo = 0f;
        tx0 = ty0 = tx1 = ty1 = 0f;
        pressed = 1f;
        setScaleX(1f);
        setScaleY(1f);
        if (lift) {
            setTranslationX(0f);
            setTranslationY(0f);
        }
        setTranslationZ(0f);
        setRotationX(0f);
        setRotationY(0f);
        if (moved != null) moved.run();
        if (glass != null) glass.removeMirror(this);
        attached = false;
        account();
        super.onDetachedFromWindow();
    }

    /** The card's painted picture (for the switcher's zoom to grow from); null until it's painted whole. */
    Bitmap art() {
        return art.getShader() != null ? bitmap : null;
    }

    /** A new picture, painted at once (a switcher card whose screen arrived after it was made). */
    void repaint(Tiles.Painter p) {
        painter = p;
        ready = null;
        int w = getWidth(), h = getHeight();
        if (w > 0 && h > 0) onSizeChanged(w, h, w, h);
        invalidate();
        account();
    }

    /** What its picture is painted from. */
    Tiles.Painter painter() {
        return painter;
    }

    /** Its size in px: as laid out, else as its layout params fix it (home's tiles, before their first layout); null when neither says. */
    int[] size() {
        int w = getWidth(), h = getHeight();
        if (w > 0 && h > 0) return new int[]{w, h};
        ViewGroup.LayoutParams lp = getLayoutParams();
        return lp != null && lp.width > 0 && lp.height > 0 ? new int[]{lp.width, lp.height} : null;
    }

    /**
     * A picture should be painted for it behind the screen: a tile of home's without one, a card that
     * let its own go, or one showing another tile's in its place.
     */
    boolean wantsArt() {
        if (!(deferred || released) || starved) return false;
        if (bitmap != null || ready != null) return stale;
        return size() != null;
    }

    /**
     * Its picture, painted behind the screen at its size (main thread): shown at once if it's laid out
     * at that size, else kept for when it is. Landing over its colour on HIGH it comes up in 150 ms;
     * taking another picture's place it's swapped in place, so nothing flashes.
     */
    void take(Bitmap b) {
        if (b == null) return;
        int w = getWidth(), h = getHeight();
        boolean laid = w > 0 && h > 0;
        // painted for a size it no longer has: it's painted again at this one
        if (laid && (b.getWidth() != Math.max(1, Math.round(w * res)) || b.getHeight() != Math.max(1, Math.round(h * res)))) return;
        boolean had = art.getShader() != null;
        ready = b;
        released = stale = starved = false;
        // its upload to the GPU starts now, on the render thread, before the frame that first draws it
        if (android.os.Build.VERSION.SDK_INT >= 24) b.prepareToDraw();
        if (laid) {
            bitmap = b;
            reflection = null;
            showArt(w, h);
            if (!had && !Ui.light && isShown()) fadeArtIn();
            invalidate();
        }
        account();
    }

    /**
     * Lets go of its picture so its memory can go (far from what's on screen, or behind a video on
     * LOW). Never recycled, since a frame may still be drawing it: the card shows its colour until a
     * new one is painted behind the screen and handed over ({@link #take}).
     */
    void release() {
        // one that had no room is tried again when it next comes near
        starved = false;
        if (bitmap == null && ready == null) return;
        bitmap = null;
        ready = null;
        reflection = null;
        stale = false;
        released = true;
        art.setShader(null);
        if (artFade != null && artFade.isStarted()) artFade.end();
        invalidate();
        account();
    }

    /**
     * Until its own picture lands it shows {@code was}, the picture of the tile it takes the place of
     * (when that was painted at the size it's laid out at): no tile goes blank as home is rebuilt.
     */
    void standIn(Bitmap was) {
        if (was == null || bitmap != null || ready != null) return;
        ready = was;
        stale = true;
        account();
    }

    /** No room for its picture (a 1 GB TV out of memory): its colour until it's let go again, and the caches make space. */
    void starve() {
        starved = true;
        ready = null;
        stale = false;
        noRoom();
    }

    /**
     * {@code p} painted into a new picture for a card {@code w}x{@code h} at {@code res}, in 16 bits
     * where it's {@code opaque} on LOW. On the calling thread, which isn't the main one: logos it needs
     * are lifted right here, and a banner or icon let go behind a video is read back. Null when there's
     * no room for it.
     */
    static Bitmap paintBehind(Tiles.Painter p, int w, int h, float res, boolean opaque) {
        int bw = Math.max(1, Math.round(w * res)), bh = Math.max(1, Math.round(h * res));
        try {
            Bitmap b = Bitmap.createBitmap(bw, bh, config(opaque));
            Canvas cv = new Canvas(b);
            cv.scale(bw / (float) w, bh / (float) h);
            p.paint(cv, w, h);
            return b;
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    /** The bytes the pictures of attached cards hold now (home's tiles on its page, Top Shelf, the switcher's deck): for Problem reports. */
    static long heldBytes() {
        return HELD.get();
    }

    /** How many attached cards hold a picture now. */
    static int heldCount() {
        return PICTURED.get();
    }

    /** Its pictures counted in {@link #HELD} while it's attached, and taken out as they go or it leaves. Main thread. */
    private void account() {
        int now = attached ? bytes(bitmap) + (ready != bitmap ? bytes(ready) : 0) + bytes(reflection) : 0;
        if (now == counted) return;
        HELD.addAndGet(now - counted);
        PICTURED.addAndGet((now > 0 ? 1 : 0) - (counted > 0 ? 1 : 0));
        counted = now;
    }

    private static int bytes(Bitmap b) {
        return b == null || b.isRecycled() ? 0 : b.getAllocationByteCount();
    }

    void setDim(float d) {
        if (Math.abs(d - dim) < 0.004f) return;
        dim = d;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        // raised, the pane casts a soft shadow onto the photograph, falling further as it lifts: on HIGH
        // under glass and tvOS tiles (the others cast a real one), on LOW under every tile, unless home's
        // shared shadow already lies beneath it
        boolean casts = Ui.light ? !layered : glass != null && bezel || tvos;
        if (liftShadow != null && focus > 0f && casts) {
            Ui.drawShadow(c, liftShadow, w, h, Ui.dpf(tvos ? 11 : 9) * focus, Math.round((tvos ? 165 : 120) * focus), shadowPaint, shadowMatrix);
        }
        if (glass != null) {
            // neutral frosted glass, the same on every wallpaper; focused, the pane fills with light
            if (glass.hasImage()) {
                // one slice of the shared, pre-blurred photograph with the tints folded into its filter
                int key = Math.max(0, Math.min(64, Math.round(focus * 64)));
                if (key != paneKey) {
                    paneKey = key;
                    blur.setColorFilter(paneFilter(key));
                }
                getLocationInWindow(loc);
                glass.drawBlurRound(c, rect, radius, loc[0], loc[1], blur);
            } else {
                int smoke = Ui.withAlpha(Ui.SMOKE, Math.round(255 * (0.40f - 0.16f * focus)));
                int frost = Ui.withAlpha(Ui.IVORY, Math.round(255 * (0.11f + 0.21f * focus)));
                pane.setColor(Ui.over(smoke, frost));
                c.drawRoundRect(rect, radius, radius, pane);
            }
        }
        float ry = getRotationY(), rx = getRotationX();
        boolean depth = parallax && sway && focus > 0f && (ry != 0f || rx != 0f);
        if (depth && art.getShader() != null) {
            // the art sits a layer deeper than the glass: it slides against the tilt, a touch
            // enlarged so no edge shows, as an Apple TV icon's layers do
            float k = Ui.dpf(1.6f) * focus;
            artMatrix.setScale(1.04f, 1.04f, w / 2f, h / 2f);
            artMatrix.postTranslate(-ry * k, rx * k);
            Ui.place(art, artMatrix);
        } else if (art.getShader() != null && !artMatrix.isIdentity()) {
            artMatrix.reset();
            Ui.place(art, artMatrix);
        }
        if (art.getShader() != null) {
            if (artAlpha < 1f) {
                // a logo that landed late comes up over the card's colour
                if (glass == null) c.drawRoundRect(rect, radius, radius, plain);
                art.setAlpha(Math.round(255 * artAlpha));
            }
            c.drawRoundRect(rect, radius, radius, art);
            if (artAlpha < 1f) art.setAlpha(255);
        } else if (glass == null) {
            // no picture on show (no room for one, or its logo still being lifted): the card's colour.
            // Never the painter here: painters allocate, and this runs on every frame the card animates
            c.drawRoundRect(rect, radius, radius, plain);
        }
        if (focus > 0f) {
            sheen.setAlpha(Math.round(255 * focus));
            c.drawRoundRect(rect, radius, radius, sheen);
        }
        if (depth) {
            // a soft spot of light that travels the other way, like a lamp catching the glass
            float cx = w * (0.5f + ry / 6f), cy = h * (0.25f - rx / 5f);
            spotMatrix.setScale(w * 0.55f, w * 0.55f);
            spotMatrix.postTranslate(cx, cy);
            Ui.place(spot, spotMatrix);
            spot.setAlpha(Math.round(70 * focus));
            c.drawRoundRect(rect, radius, radius, spot);
        }
        if (glintPos >= 0f) {
            glintMatrix.setTranslate(-w * 1.1f + glintPos * w * 2.2f, 0);
            Ui.place(glint, glintMatrix);
            c.drawRoundRect(rect, radius, radius, glint);
        }
        if (dim > 0f) {
            shade.setAlpha(Math.round(255 * Math.min(0.85f, dim)));
            c.drawRoundRect(rect, radius, radius, shade);
        }
        if (!tvos) {
            rim.setAlpha(Math.round((glass != null ? 255 : 110) * (1f - 0.6f * focus)));
            float i = rim.getStrokeWidth() / 2f;
            c.drawRoundRect(i, i, w - i, h - i, radius, radius, rim);
        }
        if (focus > 0f && bezel && !tvos) {
            // the ring sits just outside the art, a hair of the photo between them, like a lit bezel;
            // it settles outward into place as it lights
            float o = ring.getStrokeWidth() / 2f + Ui.dpf(1f) * (0.4f + 0.6f * focus);
            ring.setAlpha(Math.round(255 * focus));
            c.drawRoundRect(-o, -o, w + o, h + o, radius + o, radius + o, ring);
        }
        if (badge) c.drawCircle(w - Ui.dpf(9), Ui.dpf(9), Ui.dpf(3.5f), mark);
    }

    private static ColorMatrixColorFilter paneFilter(int key) {
        ColorMatrixColorFilter f = PANES[key];
        if (f == null) {
            float k = key / 64f;
            f = Ui.glass(Ui.GLASS_SAT, Ui.withAlpha(Ui.SMOKE, Math.round(255 * (0.40f - 0.16f * k))),
                    Ui.withAlpha(Ui.IVORY, Math.round(255 * (0.11f + 0.21f * k))));
            PANES[key] = f;
        }
        return f;
    }

    @Override
    protected void onFocusChanged(boolean gained, int direction, Rect previous) {
        super.onFocusChanged(gained, direction, previous);
        long now = SystemClock.uptimeMillis(), dt = now - movedAt;
        // a clock started again (a test) reads as a long rest
        if (dt < 0) dt = Long.MAX_VALUE / 4;
        pace = gained ? Ui.speedIn(dt) : Ui.speedOut(dt);
        if (gained) {
            movedAt = now;
        } else {
            getLocationInWindow(loc);
            leftX = loc[0] + getWidth() * getScaleX() / 2f;
            leftY = loc[1] + getHeight() * getScaleY() / 2f;
            leftAt = now;
        }
        glow(gained);
        if (lift) {
            if (gained) {
                lean(direction, previous, now);
                riseIn();
            } else {
                riseOut();
            }
            sway(gained);
        }
        if (!gained) removeCallbacks(hold);
        if (focusListener != null) focusListener.onFocus(this, gained);
    }

    /**
     * The light of focus (ring, rim, sheen, the glass filling, a baked shadow): eased on HIGH; on LOW
     * switched at once, one redraw, so only view properties move while the card rises.
     */
    private void glow(boolean gained) {
        if (focusAnim == null) {
            focusAnim = ValueAnimator.ofFloat(0f, 1f);
            focusAnim.addUpdateListener(a -> {
                focus = glowFrom + (glowTo - glowFrom) * a.getAnimatedFraction();
                invalidate();
            });
        }
        focusAnim.cancel();
        float to = gained ? 1f : 0f;
        if (Ui.light) {
            if (focus != to) {
                focus = to;
                invalidate();
            }
            return;
        }
        glowFrom = focus;
        glowTo = to;
        int ms = gained ? (content ? Ui.CONTENT_FOCUS_IN : LIGHT_IN) : content ? Ui.CONTENT_FOCUS_OUT : Ui.APP_FOCUS_OUT;
        focusAnim.setDuration(Math.round(ms * pace));
        focusAnim.setInterpolator(gained || !content ? Ui.EASE_DECEL : Ui.EASE_STANDARD);
        focusAnim.start();
    }

    /** The 2 dp lean toward the way focus came: away from the card that has just lost it, else Android's direction. */
    private void lean(int direction, Rect previous, long now) {
        float dx = 0f, dy = 0f;
        long since = now - leftAt;
        if (since >= 0 && since <= 50) {
            getLocationInWindow(loc);
            dx = loc[0] + getWidth() * getScaleX() / 2f - leftX;
            dy = loc[1] + getHeight() * getScaleY() / 2f - leftY;
        } else if (previous != null) {
            dx = direction == FOCUS_RIGHT ? 1f : direction == FOCUS_LEFT ? -1f : 0f;
            dy = direction == FOCUS_DOWN ? 1f : direction == FOCUS_UP ? -1f : 0f;
        }
        float n = Ui.dpf(Ui.FOCUS_NUDGE);
        boolean across = Math.abs(dx) >= Math.abs(dy);
        leanX = across ? Math.signum(dx) * n : 0f;
        leanY = across ? 0f : Math.signum(dy) * n;
    }

    /** Rising into focus: HIGH on its spring, LOW in a short ease-out with no overshoot. */
    private void riseIn() {
        stopRise();
        startMove(1f, leanX, leanY);
        if (Ui.light) {
            tweenRise(1f, (content ? Ui.CONTENT_FOCUS_IN : Ui.LOW_FOCUS_IN) * pace, Ui.EASE_DECEL);
            return;
        }
        Spring.Mover m = spring();
        m.jump(risen);
        m.to(1f);
    }

    /** Settling back: 140 ms ease-out for an app, 320 ms ease-in-out for content, on both tiers. */
    private void riseOut() {
        stopRise();
        startMove(0f, 0f, 0f);
        tweenRise(0f, (content ? Ui.CONTENT_FOCUS_OUT : Ui.APP_FOCUS_OUT) * pace, content ? Ui.EASE_STANDARD : Ui.EASE_DECEL);
    }

    private void stopRise() {
        if (spring != null) spring.stop();
        if (tween != null) tween.cancel();
    }

    /** A move of the rise toward {@code to}, from wherever the card is: the translation goes from where it is to (x, y). */
    private void startMove(float to, float x, float y) {
        riseFrom = risen;
        riseTo = to;
        tx0 = getTranslationX();
        ty0 = getTranslationY();
        tx1 = x;
        ty1 = y;
    }

    private void tweenRise(float to, float ms, TimeInterpolator curve) {
        if (tween == null) {
            tween = ValueAnimator.ofFloat(0f, 1f);
            tween.addUpdateListener(a -> {
                risen = tweenFrom + (tweenTo - tweenFrom) * a.getAnimatedFraction();
                applyMotion();
            });
        }
        tweenFrom = risen;
        tweenTo = to;
        tween.setDuration(Math.round(ms));
        tween.setInterpolator(curve);
        tween.start();
    }

    /**
     * The rise's spring for this card's kind at its pace: {@link Spring#app} or {@link Spring#focus},
     * with its duration shortened in a quick run. Made again only when either changes.
     */
    private Spring.Mover spring() {
        float k = Math.round(pace * 10f) / 10f;
        if (spring == null || k != springPace || content != springContent) {
            if (spring != null) spring.stop();
            Spring s;
            if (k >= 1f) s = content ? Spring.focus() : Spring.app();
            else s = content ? new Spring(0.30 * k, 0) : new Spring(0.20 * k, 0.4);
            spring = new Spring.Mover(s, 1f / 500f, risen, v -> {
                risen = v;
                applyMotion();
            });
            springPace = k;
            springContent = content;
        }
        return spring;
    }

    /** The rise moved: the lean follows it, then scale and depth. Properties only, nothing redrawn. */
    private void applyMotion() {
        if (lift) {
            float span = riseTo - riseFrom, f = Math.abs(span) < 1e-4f ? 1f : (risen - riseFrom) / span;
            setTranslationX(tx0 + (tx1 - tx0) * f);
            setTranslationY(ty0 + (ty1 - ty0) * f);
        }
        applyScale();
    }

    /** Scale and depth from how far the card has risen and how far it's pressed in. */
    private void applyScale() {
        if (lift) {
            float s = (1f + (focusedScale() - 1f) * risen) * pressed;
            setScaleX(s);
            setScaleY(s);
            // a real shadow, worked out by the GPU every frame, is HIGH's alone (PC6); a press lowers it
            float sunk = Ui.clamp((1f - pressed) / (1f - Ui.PRESS_FACTOR), 0f, 1f);
            setTranslationZ(Ui.light ? 0f : Ui.dpf(18) * Math.max(0f, risen) * (1f - 0.55f * sunk));
        } else {
            setScaleX(pressed);
            setScaleY(pressed);
        }
        if (moved != null) moved.run();
    }

    /**
     * Aura's own style on HIGH: an entry bank into the step that springs level on the GPU, then one
     * slow tilt that swells and settles, then stillness (an endless loop would keep the TV redrawing
     * every frame for as long as a tile sits focused).
     */
    private void sway(boolean gained) {
        if (swayAnim != null) swayAnim.cancel();
        boolean swaying = gained && sway && !Ui.light;
        if (swaying) {
            setCameraDistance(Ui.density * 5200);
            // tvOS entry bank: the leading edge dips into the step
            if (leanX > 0f) setRotationY(4.2f);
            else if (leanX < 0f) setRotationY(-4.2f);
            else if (leanY > 0f) setRotationX(-3.4f);
            else if (leanY < 0f) setRotationX(3.4f);
        }
        if (swaying || getRotationX() != 0f || getRotationY() != 0f) {
            animate().rotationX(0f).rotationY(0f).setInterpolator(gained ? Ui.SPRING : Ui.DECEL)
                    .setDuration(gained ? 460 : 240).setStartDelay(0).setUpdateListener(null).start();
        }
        if (!swaying) return;
        swayAnim = ValueAnimator.ofFloat(0f, 1f);
        swayAnim.setDuration(3600);
        swayAnim.setInterpolator(new android.view.animation.LinearInterpolator());
        swayAnim.setStartDelay(450);
        swayAnim.addUpdateListener(a -> {
            float f = a.getAnimatedFraction();
            double p = f * Math.PI * 2;
            float amp = (float) Math.sin(Math.PI * f);
            // a little more tilt with parallax on, so the layers can be seen moving
            float more = parallax ? 1.5f : 1f;
            setRotationY(amp * 2.4f * more * (float) Math.sin(p));
            setRotationX(amp * 1.6f * more * (float) Math.sin(2 * p + 0.8));
            if (parallax) invalidate();
        });
        swayAnim.start();
    }

    /**
     * Holding OK is timed from the press itself, not from the remote's key repeats: IR remotes
     * repeat at their own pace (some barely at all), which made the system long-press unreliable.
     */
    private final Runnable hold = () -> {
        if (onLongClick == null || !isFocused()) return;
        longFired = true;
        press(false);
        onLongClick.run();
    };

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (Pad.isConfirm(code)) {
            if (e.getRepeatCount() == 0) {
                longFired = false;
                press(true);
                removeCallbacks(hold);
                if (onLongClick != null) postDelayed(hold, android.view.ViewConfiguration.getLongPressTimeout());
            } else if (!longFired && (e.getFlags() & KeyEvent.FLAG_LONG_PRESS) != 0) {
                removeCallbacks(hold);
                hold.run();
            }
            return true;
        }
        return super.onKeyDown(code, e);
    }

    @Override
    public boolean onKeyUp(int code, KeyEvent e) {
        if (Pad.isConfirm(code)) {
            removeCallbacks(hold);
            if (!longFired) press(false);
            if (!longFired && !e.isCanceled() && onClick != null) onClick.run();
            longFired = false;
            return true;
        }
        return super.onKeyUp(code, e);
    }

    /** OK held down sinks the card to 0.95 of its focused size in 100 ms; let go, it comes back in 250 ms. */
    private void press(boolean down) {
        if (pressAnim == null) {
            pressAnim = ValueAnimator.ofFloat(0f, 1f);
            pressAnim.setInterpolator(Ui.EASE_DECEL);
            pressAnim.addUpdateListener(a -> {
                pressed = pressFrom + (pressTo - pressFrom) * a.getAnimatedFraction();
                applyScale();
            });
        }
        pressAnim.cancel();
        pressFrom = pressed;
        pressTo = down ? Ui.PRESS_FACTOR : 1f;
        pressAnim.setDuration(down ? Ui.PRESS_DOWN : Ui.PRESS_UP);
        pressAnim.start();
    }
}
