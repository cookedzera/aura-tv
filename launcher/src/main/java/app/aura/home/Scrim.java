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
 */
final class Scrim extends View {
    private static final int BW = 160, BH = 90;

    private final Paint side = new Paint(), floor = new Paint(), top = new Paint(), vignette = new Paint(), grain = new Paint();
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Bitmap baked = Bitmap.createBitmap(BW, BH, Bitmap.Config.ARGB_8888);
    private final Canvas bakedCanvas = new Canvas(baked);
    private final Rect dst = new Rect();
    private final boolean cover;
    private float lumaTop = 0.5f, lumaBottom = 0.5f;
    private int lastSa = -1, lastFa = -1, lastTa = -1;
    private ValueAnimator anim;

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
        Bitmap noise = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888);
        Random r = new Random(7);
        int[] px = new int[160 * 160];
        for (int i = 0; i < px.length; i++) {
            int v = r.nextInt(256);
            px[i] = 0xFF000000 | (v << 16) | (v << 8) | v;
        }
        noise.setPixels(px, 0, 160, 0, 0, 160, 160);
        grain.setShader(new BitmapShader(noise, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        grain.setAlpha(13);
        side.setShader(new LinearGradient(0, 0, BW * 0.66f, 0,
                new int[]{0xB3000000, 0x52000000, 0x00000000}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        floor.setShader(new LinearGradient(0, BH * (cover ? 0.35f : 0.48f), 0, BH,
                new int[]{0x00000000, 0x80000000, 0xD1000000}, new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
        top.setShader(new LinearGradient(0, 0, 0, BH * 0.26f, 0x70000000, 0x00000000, Shader.TileMode.CLAMP));
        vignette.setShader(new RadialGradient(BW / 2f, BH / 2f, BW * 0.8f,
                new int[]{0x00000000, 0x00000000, 0x4D000000}, new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
        rebuild();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        dst.set(0, 0, w, h);
    }

    private boolean rebuild() {
        int sa = Math.round(255 * (cover ? 0.55f : 0.7f + 0.3f * lumaTop));
        int fa = Math.round(255 * (0.72f + 0.28f * lumaBottom));
        int ta = Math.round(255 * (0.6f + 0.4f * lumaTop));
        if (sa == lastSa && fa == lastFa && ta == lastTa) return false;
        lastSa = sa;
        lastFa = fa;
        lastTa = ta;
        side.setAlpha(sa);
        floor.setAlpha(fa);
        top.setAlpha(ta);
        baked.eraseColor(0);
        bakedCanvas.drawRect(0, 0, BW, BH, vignette);
        bakedCanvas.drawRect(0, 0, BW, BH, side);
        bakedCanvas.drawRect(0, 0, BW, BH, floor);
        bakedCanvas.drawRect(0, 0, BW, BH, top);
        return true;
    }

    @Override
    protected void onDraw(Canvas c) {
        if (dst.isEmpty()) return;
        c.drawBitmap(baked, null, dst, paint);
        c.drawRect(dst, grain);
    }

    private void step(float t, float b) {
        lumaTop = t;
        lumaBottom = b;
        if (rebuild()) invalidate();
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
}
