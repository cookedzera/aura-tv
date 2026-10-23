package app.aura.home;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

import java.util.Random;

/**
 * The editorial shade: a left-hand gradient for the headline, a floor for the app row, a soft top
 * edge and a whisper of film grain. Gradients deepen over bright photographs and relax over dark ones.
 */
final class Scrim extends View {
    private final Paint side = new Paint(), floor = new Paint(), top = new Paint(), vignette = new Paint(), grain = new Paint();
    private final boolean cover;
    private float lumaTop = 0.5f, lumaBottom = 0.5f;
    private ValueAnimator anim;

    /** @param cover screensaver layout (headline bottom-left) instead of the home layout */
    Scrim(Context c, boolean cover) {
        super(c);
        this.cover = cover;
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
        // cached as one texture and composited each frame, instead of re-filling every gradient
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        side.setShader(new LinearGradient(0, 0, w * 0.66f, 0,
                new int[]{0xB3000000, 0x52000000, 0x00000000}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        floor.setShader(new LinearGradient(0, h * (cover ? 0.35f : 0.48f), 0, h,
                new int[]{0x00000000, 0x80000000, 0xD1000000}, new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
        top.setShader(new LinearGradient(0, 0, 0, h * 0.26f, 0x70000000, 0x00000000, Shader.TileMode.CLAMP));
        vignette.setShader(new RadialGradient(w / 2f, h / 2f, w * 0.8f,
                new int[]{0x00000000, 0x00000000, 0x4D000000}, new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        side.setAlpha(Math.round(255 * (cover ? 0.55f : 0.7f + 0.3f * lumaTop)));
        floor.setAlpha(Math.round(255 * (0.72f + 0.28f * lumaBottom)));
        top.setAlpha(Math.round(255 * (0.6f + 0.4f * lumaTop)));
        c.drawRect(0, 0, w, h, vignette);
        c.drawRect(0, 0, w, h, side);
        c.drawRect(0, 0, w, h, floor);
        c.drawRect(0, 0, w, h, top);
        c.drawRect(0, 0, w, h, grain);
    }

    void adapt(float t, float b, long ms) {
        if (anim != null) anim.cancel();
        final float t0 = lumaTop, b0 = lumaBottom;
        if (ms <= 0) {
            lumaTop = t;
            lumaBottom = b;
            invalidate();
            return;
        }
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(ms);
        anim.addUpdateListener(a -> {
            float f = a.getAnimatedFraction();
            lumaTop = t0 + (t - t0) * f;
            lumaBottom = b0 + (b - b0) * f;
            invalidate();
        });
        anim.start();
    }
}
