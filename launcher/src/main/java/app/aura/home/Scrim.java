package app.aura.home;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.view.View;

import java.util.Random;

/**
 * The editorial shade: a left-hand gradient for the headline, a floor for the app row, a soft top
 * edge and a whisper of film grain. Gradients deepen over bright photographs and relax over dark ones.
 * In Light mode the photograph beneath draws it instead (see {@link Backdrop#setShade}).
 */
final class Scrim extends View {
    private static final int BW = 160, BH = 90;

    private final Paint side = new Paint(), floor = new Paint(), top = new Paint(), vignette = new Paint(), grain = new Paint();
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Bitmap baked = Bitmap.createBitmap(BW, BH, Bitmap.Config.ARGB_8888);
    private final Canvas bakedCanvas = new Canvas(baked);
    /** The grain's tile, never changed once made: a photograph being baked on another thread reads it too. */
    private final Bitmap noise;
    private final Rect dst = new Rect();
    private final boolean cover;
    private float lumaTop = 0.5f, lumaBottom = 0.5f;
    private int lastSa = -1, lastFa = -1, lastTa = -1;
    private ValueAnimator anim;
    /** The photograph beneath draws this shade itself: nothing to draw here. */
    private boolean below;

    /** @param cover screensaver layout (headline bottom-left) instead of the home layout */
    Scrim(Context c, boolean cover) {
        super(c);
        this.cover = cover;
        // dither the gradients as they are rasterised into the 160x90 bitmap where banding forms
        side.setDither(true);
        floor.setDither(true);
        top.setDither(true);
        vignette.setDither(true);
        paint.setDither(true);
        Random r = new Random(7);
        int[] px = new int[160 * 160];
        for (int i = 0; i < px.length; i++) {
            int v = r.nextInt(256);
            px[i] = 0xFF000000 | (v << 16) | (v << 8) | v;
        }
        noise = Bitmap.createBitmap(px, 160, 160, Bitmap.Config.ARGB_8888);
        grain.setShader(new BitmapShader(noise, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        grain.setAlpha(13);
        gradients(side, floor, top, vignette, cover);
        rebuild();
    }

    /** The four gradients, laid out on the {@link #BW}x{@link #BH} grid. */
    private static void gradients(Paint side, Paint floor, Paint top, Paint vignette, boolean cover) {
        side.setShader(new LinearGradient(0, 0, BW * 0.66f, 0,
                new int[]{0xB3000000, 0x52000000, 0x00000000}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        floor.setShader(new LinearGradient(0, BH * (cover ? 0.35f : 0.48f), 0, BH,
                new int[]{0x00000000, 0x80000000, 0xD1000000}, new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
        top.setShader(new LinearGradient(0, 0, 0, BH * 0.26f, 0x70000000, 0x00000000, Shader.TileMode.CLAMP));
        vignette.setShader(new RadialGradient(BW / 2f, BH / 2f, BW * 0.8f,
                new int[]{0x00000000, 0x00000000, 0x4D000000}, new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
    }

    /** How deep the side, floor and top gradients go over a photograph this bright. */
    private int sideAlpha(float t) {
        return Math.round(255 * (cover ? 0.55f : 0.7f + 0.3f * t));
    }

    private static int floorAlpha(float b) {
        return Math.round(255 * (0.72f + 0.28f * b));
    }

    private static int topAlpha(float t) {
        return Math.round(255 * (0.6f + 0.4f * t));
    }

    /** A little past the edges (the gradients clamp): baked into a photograph, the edge pixels' neighbours are shaded too. */
    private static void layers(Canvas c, Paint side, Paint floor, Paint top, Paint vignette) {
        c.drawRect(-1, -1, BW + 1, BH + 1, vignette);
        c.drawRect(-1, -1, BW + 1, BH + 1, side);
        c.drawRect(-1, -1, BW + 1, BH + 1, floor);
        c.drawRect(-1, -1, BW + 1, BH + 1, top);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        dst.set(0, 0, w, h);
    }

    private boolean rebuild() {
        int sa = sideAlpha(lumaTop), fa = floorAlpha(lumaBottom), ta = topAlpha(lumaTop);
        if (sa == lastSa && fa == lastFa && ta == lastTa) return false;
        lastSa = sa;
        lastFa = fa;
        lastTa = ta;
        side.setAlpha(sa);
        floor.setAlpha(fa);
        top.setAlpha(ta);
        baked.eraseColor(0);
        layers(bakedCanvas, side, floor, top, vignette);
        return true;
    }

    /**
     * The shade for a photograph of these lumas, and its grain, drawn once into {@code c} as they lie
     * over a {@code w}x{@code h} screen: the same gradients, worked out at full size rather than
     * stretched from the small copy. Safe on any thread: nothing here is changed.
     */
    void bake(Canvas c, int w, int h, float lumaTop, float lumaBottom) {
        bake(c, w, h, lumaTop, lumaBottom, null);
    }

    void bake(Canvas c, int w, int h, float lumaTop, float lumaBottom, android.graphics.ColorFilter cf) {
        Paint s = new Paint(Paint.DITHER_FLAG), f = new Paint(Paint.DITHER_FLAG), t = new Paint(Paint.DITHER_FLAG),
                v = new Paint(Paint.DITHER_FLAG);
        gradients(s, f, t, v, cover);
        s.setAlpha(sideAlpha(lumaTop));
        f.setAlpha(floorAlpha(lumaBottom));
        t.setAlpha(topAlpha(lumaTop));
        c.save();
        c.scale(w / (float) BW, h / (float) BH);
        layers(c, s, f, t, v);
        c.restore();
        Paint g = new Paint();
        g.setShader(new BitmapShader(noise, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        g.setAlpha(13);
        if (cf != null) g.setColorFilter(cf);
        c.drawRect(-8, -8, w + 8, h + 8, g);
    }

    /** True while the photograph beneath draws this shade itself (Light mode, see {@link Backdrop#setShade}). */
    void setBelow(boolean on) {
        if (below == on) return;
        below = on;
        invalidate();
    }

    /** The shade as it is now, drawn into {@code c}: the photograph's canvas, the same size as this view. */
    void drawOver(Canvas c) {
        if (dst.isEmpty()) return;
        c.drawBitmap(baked, null, dst, paint);
        c.drawRect(dst, grain);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (!below) drawOver(c);
    }

    private void step(float t, float b) {
        lumaTop = t;
        lumaBottom = b;
        if (rebuild() && !below) invalidate();
    }

    void adapt(float t, float b, long ms) {
        if (anim != null) anim.cancel();
        final float t0 = lumaTop, b0 = lumaBottom;
        if (ms <= 0) {
            step(t, b);
            return;
        }
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(ms);
        anim.addUpdateListener(a -> {
            float f = a.getAnimatedFraction();
            step(t0 + (t - t0) * f, b0 + (b - b0) * f);
        });
        anim.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (anim != null) anim.cancel();
        super.onDetachedFromWindow();
    }
}
