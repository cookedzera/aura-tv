package app.aura.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
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
    private int tintKey, frostKey, highlightKey = -1;

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
        if (tint != tintKey || frostC != frostKey || hi != highlightKey) {
            tintKey = tint;
            frostKey = frostC;
            highlightKey = hi;
            blurPaint.setColorFilter(Ui.glass(saturation, tint, frostC, hi));
        }
        getLocationInWindow(loc);
        backdrop.drawBlurRound(c, box, radius, loc[0], loc[1], blurPaint);
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
}
