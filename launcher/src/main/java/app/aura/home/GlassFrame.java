package app.aura.home;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;

/**
 * Frosted glass: the live wallpaper blurred and saturated behind it, tinted with the photo's colour,
 * lit along the top edge. Blur and every tint layer go down in one rounded draw (the tints live in
 * the paint's colour filter), so a pane costs a single pass over its pixels.
 */
class GlassFrame extends FrameLayout {
    final Backdrop backdrop;
    final float radius;
    float darkness;
    /** White frosting laid over the blur, for clear light glass rather than smoked glass. */
    float frost;
    /** How much of the photograph's colour comes through: panes are neutral, full-screen veils keep its mood. */
    float saturation;
    int plainColor = 0x1AFFFFFF;
    boolean edgeLight = true;
    float highlight;

    private final Paint blurPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final int[] loc = new int[2];
    private int tintKey, frostKey = -1;

    GlassFrame(Context c, Backdrop backdrop, float radiusDp, float darkness) {
        super(c);
        this.backdrop = backdrop;
        this.radius = Ui.dpf(radiusDp);
        this.darkness = darkness;
        saturation = radiusDp > 0 ? Ui.GLASS_SAT : 0.85f;
        setWillNotDraw(false);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(Math.max(1f, Ui.dpf(0.75f)));
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
                // LOW: no pane casts a shadow, whatever lifts it (soft edges are filled every frame they draw)
                o.setAlpha(Ui.light ? 0f : 1f);
            }
        });
        setClipToOutline(true);
    }

    /**
     * With false, children (a focused button that grows, its shadow) may spill past the rounded
     * edge instead of being sliced off at the corners. The glass itself is always drawn rounded.
     */
    void setClipContent(boolean clip) {
        setClipToOutline(clip);
        setClipChildren(clip);
    }

    /**
     * Fading glass applies its alpha to each draw directly. Overlapping rendering would first paint
     * the pane into an offscreen layer every frame of the fade, which doubles its cost on this GPU.
     */
    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (backdrop != null) backdrop.addMirror(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        if (backdrop != null) backdrop.removeMirror(this);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        box.set(0, 0, w, h);
        // a hairline all the way round, brightest where the light falls on the top edge
        edge.setShader(new LinearGradient(0, 0, w * 0.35f, h,
                new int[]{0x8CFFFDF9, 0x3DFFFDF9, 0x2EFFFFFF}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas c) {
        int frostC = Ui.withAlpha(Ui.IVORY, Math.round(255 * frost));
        int hi = Ui.withAlpha(0xFFFFFFFF, Math.round(245 * highlight));
        if (backdrop == null) {
            fill.setColor(Ui.over(Ui.over(plainColor, frostC), hi));
            c.drawRoundRect(box, radius, radius, fill);
            return;
        }
        int tint = Ui.withAlpha(Ui.SMOKE, Math.round(255 * darkness));
        if (!backdrop.hasImage()) {
            fill.setColor(Ui.over(Ui.over(tint, frostC), hi));
            c.drawRoundRect(box, radius, radius, fill);
            return;
        }
        if (tint != tintKey || frostC != frostKey) {
            tintKey = tint;
            frostKey = frostC;
            blurPaint.setColorFilter(Ui.glass(saturation, tint, frostC));
        }
        getLocationInWindow(loc);
        backdrop.drawBlurRound(c, box, radius, loc[0], loc[1], blurPaint);
        // the focus light as a pass of its own: folded into the filter, it made a new filter every frame of a focus change
        if (highlight > 0f) {
            fill.setColor(hi);
            c.drawRoundRect(box, radius, radius, fill);
        }
    }

    @Override
    protected void dispatchDraw(Canvas c) {
        super.dispatchDraw(c);
        if (edgeLight) {
            float i = edge.getStrokeWidth() / 2f;
            edge.setAlpha(Math.round(255 * (1f - highlight * 0.7f)));
            c.drawRoundRect(i, i, getWidth() - i, getHeight() - i, radius, radius, edge);
        }
    }

    /** A sheet's look at {@code p} (0 gone, 1 in place): faded, and HIGH a little smaller about its pivot, LOW 12 dp low. */
    static Spring.Sink sheetAt(View v) {
        return p -> {
            v.setAlpha(Ui.clamp(p, 0f, 1f));
            float s = Ui.light ? 1f : 0.94f + 0.06f * p;
            v.setScaleX(s);
            v.setScaleY(s);
            v.setTranslationY(Ui.light ? Ui.dpf(12) * (1f - p) : 0f);
        };
    }

    /**
     * A sheet on its way in holds the focus itself, unseen, until its content may take it
     * ({@link Ui#SHEET_CONTENT_AT}, LOW {@link Ui#LOW_SHEET_FOCUS_AT}): the focus never stays on what
     * fades out behind it, nor lands on content that isn't there yet. An arrow meanwhile hands the
     * focus on at once and moves on from there; OK hands it on at its release, so it presses nothing.
     * The sheet passes its own key and focus calls here, which reach it only while it holds the focus.
     */
    static final class Hold implements Runnable {
        private final ViewGroup sheet;
        private final Runnable first;
        private boolean on, ok;

        Hold(ViewGroup sheet, Runnable first) {
            this.sheet = sheet;
            this.first = first;
        }

        void start() {
            sheet.removeCallbacks(this);
            on = true;
            ok = false;
            sheet.setFocusable(true);
            sheet.requestFocus();
            sheet.postDelayed(this, Ui.light ? Ui.LOW_SHEET_FOCUS_AT : Ui.SHEET_CONTENT_AT);
        }

        @Override
        public void run() {
            if (!ok) release();
        }

        /** The content takes the focus now. */
        void release() {
            if (!on) return;
            stop();
            first.run();
        }

        /**
         * Nothing more to hand on: closing, or the sheet's own focus went. A sheet still holding the
         * focus stays focusable until it moves on (dropping it here would send it anywhere first).
         */
        void stop() {
            on = false;
            ok = false;
            sheet.removeCallbacks(this);
            if (!sheet.isFocused()) sheet.setFocusable(false);
        }

        boolean down(int code) {
            if (!on) return false;
            if (Pad.isConfirm(code)) {
                ok = true;
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                    || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) release();
            return false;
        }

        boolean up(int code) {
            if (!ok || !Pad.isConfirm(code)) return false;
            release();
            return true;
        }
    }

    /**
     * A pane's way in and out as one number, 0 (gone) to 1 (in place), which the pane turns into its
     * alpha, scale and offset. HIGH comes in on the snappy spring, LOW in the time given; a way out
     * takes {@link Ui#CLOSE} ms. The view given keeps a hardware layer, HIGH only, while it moves, so
     * its text isn't drawn again at each scale. Each move starts where the last one got to.
     */
    static final class Way {
        private final View layered;
        private final Spring.Sink sink;
        private final Spring.Mover spring;
        private final ValueAnimator timed = ValueAnimator.ofFloat(0f, 1f);
        private final Runnable arrive = this::arrived;
        private float at, from, to;
        private boolean halting;
        private Runnable end;

        Way(View layered, Spring.Sink sink) {
            this.layered = layered;
            this.sink = sink;
            spring = new Spring.Mover(Spring.snappy(), 1f / 256, 0f, this::set);
            timed.addUpdateListener(a -> set(from + (to - from) * a.getAnimatedFraction()));
            timed.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator a) {
                    if (!halting) arrived();
                }
            });
        }

        /** In from where it is: on the spring on HIGH, in {@code lowMs} ms on LOW; {@code settled} runs once it's there. */
        void in(long lowMs, Runnable settled) {
            halt();
            end = settled;
            layer(true);
            if (Ui.light) {
                run(1f, lowMs, Ui.EASE_DECEL);
                return;
            }
            spring.jump(at);
            spring.to(1f);
            if (spring.moving()) spring.then(arrive);
            else arrived();
        }

        /** Out from where it is, in {@link Ui#CLOSE} ms; {@code gone} runs once it's there. */
        void out(Runnable gone) {
            out(gone, Ui.CLOSE, Ui.EASE_STANDARD);
        }

        /** Out from where it is, in {@code ms} on {@code curve}; {@code gone} runs once it's there. */
        void out(Runnable gone, long ms, TimeInterpolator curve) {
            halt();
            end = gone;
            layer(true);
            run(0f, ms, curve);
        }

        /** There at once, nothing moving and nothing left to run. */
        void jump(float v) {
            halt();
            layer(false);
            set(v);
        }

        private void run(float target, long ms, TimeInterpolator curve) {
            from = at;
            to = target;
            timed.setDuration(ms);
            timed.setInterpolator(curve);
            timed.start();
        }

        private void halt() {
            end = null;
            halting = true;
            timed.cancel();
            halting = false;
            spring.stop();
        }

        private void arrived() {
            layer(false);
            Runnable r = end;
            end = null;
            if (r != null) r.run();
        }

        private void set(float v) {
            at = v;
            sink.at(v);
        }

        private void layer(boolean on) {
            if (layered == null) return;
            int type = on && !Ui.light ? View.LAYER_TYPE_HARDWARE : View.LAYER_TYPE_NONE;
            if (layered.getLayerType() != type) layered.setLayerType(type, null);
        }
    }
}
