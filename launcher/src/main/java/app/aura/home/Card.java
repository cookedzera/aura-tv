package app.aura.home;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewOutlineProvider;

/**
 * App card. Focused, a bright ring hugs it, it glows in the app's own colour and catches a sheen;
 * in the carousel it can also recede (dim) and cast a reflection.
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
    /**
     * The picture's resolution against the card's size. The switcher's near-full-screen cards paint
     * at 0.6 and the GPU scales them up: their pictures are 640x360 anyway, and the texture to
     * upload and sample drops from ~2 MB to ~0.7 MB.
     */
    float res = 1f;
    /**
     * Set before the card is attached: it becomes a pane of frosted glass over the photograph,
     * tinted with the app's colour, and its art (a lifted logo) is laid on top.
     */
    Backdrop glass;
    private ValueAnimator swayAnim;
    private final Paint blur = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG), pane = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int[] loc = new int[2];
    private int paneKey = -1;

    private final Tiles.Painter painter;
    private final float radius;
    private final RectF rect = new RectF();
    private final Paint art = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mark = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix glintMatrix = new Matrix(), artMatrix = new Matrix(), spotMatrix = new Matrix();
    private final Paint spot = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** Parallax on focus (Settings): the art moves a layer beneath the glass as the tile tilts. */
    static boolean parallax = true;
    private LinearGradient glintShader;
    private Bitmap bitmap, reflection;
    private float focus, dim, glintPos = -1f;
    private ValueAnimator focusAnim, glintAnim;
    private boolean longFired;

    Card(Context c, Tiles.Painter painter, int color, float radiusDp) {
        super(c);
        this.painter = painter;
        this.color = color;
        this.radius = Ui.dpf(radiusDp);
        setFocusable(true);
        setElevation(Ui.dpf(3));
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
                // a shadow would show through the glass and muddy it; the bloom lights glass instead
                o.setAlpha(glass != null ? 0f : 1f);
            }
        });
        // coloured shadows arrived in Android 9; earlier ones are simply grey
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            setOutlineSpotShadowColor(color);
            setOutlineAmbientShadowColor(Ui.mix(color, 0xFF000000, 0.4f));
        }
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(Ui.hair());
        rim.setColor(0xFFFFFFFF);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(Ui.dpf(radiusDp > 12 ? 2.5f : 2f));
        ring.setColor(0xFFFFFFFF);
        shade.setColor(0xFF000000);
        mark.setColor(0xFFFFFFFF);
        mark.setShadowLayer(Ui.dpf(3), 0, Ui.dpf(1), 0x99000000);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        rect.set(0, 0, w, h);
        reflection = null;
        if (w > 0 && h > 0) {
            int bw = Math.max(1, Math.round(w * res)), bh = Math.max(1, Math.round(h * res));
            bitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(bitmap);
            cv.scale(bw / (float) w, bh / (float) h);
            painter.paint(cv, w, h);
            BitmapShader bs = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            if (bw != w || bh != h) {
                Matrix up = new Matrix();
                up.setScale(w / (float) bw, h / (float) bh);
                bs.setLocalMatrix(up);
            }
            art.setShader(bs);
        }
        sheen.setShader(new LinearGradient(0, 0, w * 0.55f, h, 0x4DFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        glintShader = new LinearGradient(0, 0, w * 0.42f, h, new int[]{0x00FFFFFF, 0x66FFFFFF, 0x00FFFFFF},
                new float[]{0.4f, 0.5f, 0.6f}, Shader.TileMode.CLAMP);
        glint.setShader(glintShader);
        // the parallax light: made once, moved by a matrix (a new gradient every frame costs this GPU ~10 ms)
        spot.setShader(new android.graphics.RadialGradient(0, 0, 1, 0xFFFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
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
        reflection = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
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
        return reflection;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // glass repaints with the photograph's drift, like the other glass
        if (glass != null) glass.addMirror(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        if (swayAnim != null) swayAnim.cancel();
        if (glass != null) glass.removeMirror(this);
        super.onDetachedFromWindow();
    }

    /** The card's painted picture (for the switcher's zoom to grow from); null before its first layout. */
    Bitmap art() {
        return bitmap;
    }

    void setDim(float d) {
        if (Math.abs(d - dim) < 0.004f) return;
        dim = d;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (glass != null) {
            // a little smoke for contrast, then a clean wash of the app's colour; focused, the pane lights up
            int smoke = Ui.withAlpha(0xFF0E1116, Math.round(255 * 0.18f * (1f - focus)));
            int wash = Ui.withAlpha(color, Math.round(255 * (0.15f + 0.1f * focus)));
            int frost = Ui.withAlpha(0xFFFFFFFF, Math.round(255 * (0.07f + 0.15f * focus)));
            if (glass.hasImage()) {
                // one slice of the shared, pre-blurred photograph with the tints folded into its filter
                int key = Math.round(focus * 64);
                if (key != paneKey) {
                    paneKey = key;
                    blur.setColorFilter(Ui.glass(1.6f, smoke, wash, frost));
                }
                getLocationInWindow(loc);
                glass.drawBlurRound(c, rect, radius, loc[0], loc[1], blur);
            } else {
                pane.setColor(Ui.over(Ui.over(smoke, wash), frost));
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
            art.getShader().setLocalMatrix(artMatrix);
        } else if (art.getShader() != null && !artMatrix.isIdentity()) {
            artMatrix.reset();
            art.getShader().setLocalMatrix(artMatrix);
        }
        c.drawRoundRect(rect, radius, radius, art);
        if (focus > 0f) {
            sheen.setAlpha(Math.round(255 * focus));
            c.drawRoundRect(rect, radius, radius, sheen);
        }
        if (depth) {
            // a soft spot of light that travels the other way, like a lamp catching the glass
            float cx = w * (0.5f + ry / 6f), cy = h * (0.25f - rx / 5f);
            spotMatrix.setScale(w * 0.55f, w * 0.55f);
            spotMatrix.postTranslate(cx, cy);
            spot.getShader().setLocalMatrix(spotMatrix);
            spot.setAlpha(Math.round(70 * focus));
            c.drawRoundRect(rect, radius, radius, spot);
        }
        if (glintPos >= 0f) {
            glintMatrix.setTranslate(-w * 1.1f + glintPos * w * 2.2f, 0);
            glintShader.setLocalMatrix(glintMatrix);
            c.drawRoundRect(rect, radius, radius, glint);
        }
        if (dim > 0f) {
            shade.setAlpha(Math.round(255 * Math.min(0.85f, dim)));
            c.drawRoundRect(rect, radius, radius, shade);
        }
        rim.setAlpha(Math.round((glass != null ? 72 : 46) * (1f - focus)));
        float i = rim.getStrokeWidth() / 2f;
        c.drawRoundRect(i, i, w - i, h - i, radius, radius, rim);
        if (focus > 0f && bezel) {
            // the ring sits just outside the art, like a lit bezel
            float o = ring.getStrokeWidth() / 2f + Ui.dpf(1f);
            ring.setAlpha(Math.round(245 * focus));
            c.drawRoundRect(-o, -o, w + o, h + o, radius + o, radius + o, ring);
        }
        if (badge) c.drawCircle(w - Ui.dpf(9), Ui.dpf(9), Ui.dpf(3.5f), mark);
    }

    @Override
    protected void onFocusChanged(boolean gained, int direction, Rect previous) {
        super.onFocusChanged(gained, direction, previous);
        if (focusAnim != null) focusAnim.cancel();
        focusAnim = ValueAnimator.ofFloat(focus, gained ? 1f : 0f);
        focusAnim.setDuration(gained ? 320 : 200);
        focusAnim.setInterpolator(Ui.DECEL);
        focusAnim.addUpdateListener(a -> {
            focus = (float) a.getAnimatedValue();
            invalidate();
        });
        focusAnim.start();
        if (lift) {
            if (swayAnim != null) swayAnim.cancel();
            animate().scaleX(gained ? 1.1f : 1f).scaleY(gained ? 1.1f : 1f).translationZ(gained ? Ui.dpf(18) : 0f)
                    .rotationX(0f).rotationY(0f)
                    .setInterpolator(gained ? Ui.SPRING : Ui.DECEL).setDuration(gained ? 460 : 240)
                    .setStartDelay(0).setUpdateListener(null).start();
            if (gained && sway) {
                setCameraDistance(Ui.density * 5200);
                // one slow tilt that swells and settles, then stillness: an endless loop would keep
                // the TV redrawing every frame for as long as a tile sits focused
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
        }
        if (!gained) removeCallbacks(hold);
        if (focusListener != null) focusListener.onFocus(this, gained);
    }

    /**
     * Holding OK is timed from the press itself, not from the remote's key repeats: IR remotes
     * repeat at their own pace (some barely at all), which made the system long-press unreliable.
     */
    private final Runnable hold = () -> {
        if (onLongClick == null || !isFocused()) return;
        longFired = true;
        onLongClick.run();
    };

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (Pad.isConfirm(code)) {
            if (e.getRepeatCount() == 0) {
                longFired = false;
                removeCallbacks(hold);
                if (onLongClick != null) postDelayed(hold, android.view.ViewConfiguration.getLongPressTimeout());
            }
            return true;
        }
        return super.onKeyDown(code, e);
    }

    @Override
    public boolean onKeyUp(int code, KeyEvent e) {
        if (Pad.isConfirm(code)) {
            removeCallbacks(hold);
            if (!longFired && !e.isCanceled() && onClick != null) onClick.run();
            longFired = false;
            return true;
        }
        return super.onKeyUp(code, e);
    }
}
