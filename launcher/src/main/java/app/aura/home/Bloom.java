package app.aura.home;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

import java.util.Random;

/**
 * Coloured light behind whatever has focus: it glides to the focused tile, takes the app's colour,
 * breathes slowly and carries a few motes of dust. Screen-blended; moves on the backdrop's cadence.
 */
final class Bloom extends View implements Backdrop.Tick {
    private static final int MOTES = 12;
    private static final float RX = 0.30f, RY = 0.46f;

    private final Paint glow = new Paint(), mote = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Matrix local = new Matrix();
    private final ArgbEvaluator argb = new ArgbEvaluator();
    private final Random rnd = new Random(11);
    private final Bitmap dot;
    private final RectF area = new RectF(), dst = new RectF();
    private final float[] mx = new float[MOTES], my = new float[MOTES], mr = new float[MOTES],
            ma = new float[MOTES], ms = new float[MOTES], mp = new float[MOTES];
    private RadialGradient shader;
    private int color = 0xFF8AA0C0;
    private float strength = 1f, cx = 0.25f, cy = 0.75f, breath = 1f;
    private long last, now;
    private ValueAnimator colorAnim, strengthAnim, moveAnim;

    Bloom(Context c) {
        super(c);
        PorterDuffXfermode screen = new PorterDuffXfermode(PorterDuff.Mode.SCREEN);
        glow.setXfermode(screen);
        mote.setXfermode(screen);
        int s = 48;
        dot = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new RadialGradient(s / 2f, s / 2f, s / 2f, new int[]{0xFFFFFFFF, 0x66FFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.35f, 1f}, Shader.TileMode.CLAMP));
        new Canvas(dot).drawCircle(s / 2f, s / 2f, s / 2f, p);
        for (int i = 0; i < MOTES; i++) spawn(i, rnd.nextFloat());
        recolor();
    }

    /** Motes live in coordinates relative to the light's centre, so they travel with it. */
    private void spawn(int i, float life) {
        mx[i] = (rnd.nextFloat() - 0.5f) * 1.6f * RX;
        my[i] = RY * (0.7f - life * 1.4f);
        mr[i] = 1.2f + rnd.nextFloat() * 2.4f;
        ma[i] = 0.25f + rnd.nextFloat() * 0.5f;
        ms[i] = 0.01f + rnd.nextFloat() * 0.02f;
        mp[i] = rnd.nextFloat() * 6.28f;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        shader = new RadialGradient(0, 0, 1f, new int[]{0xD6FFFFFF, 0x5CFFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
        glow.setShader(shader);
        place();
    }

    private void place() {
        int w = getWidth(), h = getHeight();
        if (w == 0 || shader == null) return;
        local.setScale(w * RX * breath, h * RY * breath);
        local.postTranslate(cx * w, cy * h);
        shader.setLocalMatrix(local);
        area.set(Math.max(0, (cx - RX * 1.1f) * w), Math.max(0, (cy - RY * 1.1f) * h),
                Math.min(w, (cx + RX * 1.1f) * w), Math.min(h, (cy + RY * 1.1f) * h));
        invalidate();
    }

    /** Glides the light to a point given as fractions of the screen. */
    void moveTo(float x, float y, long ms) {
        if (moveAnim != null) moveAnim.cancel();
        final float x0 = cx, y0 = cy;
        moveAnim = ValueAnimator.ofFloat(0f, 1f);
        moveAnim.setDuration(ms);
        moveAnim.setInterpolator(Ui.EMPHASIZED);
        moveAnim.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            cx = x0 + (x - x0) * t;
            cy = y0 + (y - y0) * t;
            place();
        });
        moveAnim.start();
    }

    void setColor(int to, long ms) {
        if (colorAnim != null) colorAnim.cancel();
        if (ms <= 0) {
            color = to;
            recolor();
            return;
        }
        final int from = color;
        colorAnim = ValueAnimator.ofFloat(0f, 1f);
        colorAnim.setDuration(ms);
        colorAnim.setInterpolator(Ui.SOFT);
        colorAnim.addUpdateListener(a -> {
            color = (int) argb.evaluate(a.getAnimatedFraction(), from, to);
            recolor();
        });
        colorAnim.start();
    }

    void setStrength(float to, long ms) {
        if (strengthAnim != null) strengthAnim.cancel();
        strengthAnim = ValueAnimator.ofFloat(strength, to);
        strengthAnim.setDuration(Math.max(1, ms));
        strengthAnim.setInterpolator(Ui.SOFT);
        strengthAnim.addUpdateListener(a -> {
            strength = (float) a.getAnimatedValue();
            recolor();
        });
        strengthAnim.start();
    }

    /** A colour-filter swap: far cheaper than rebuilding the gradient every frame. */
    private void recolor() {
        glow.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
        glow.setAlpha(Math.round(255 * strength));
        mote.setColorFilter(new PorterDuffColorFilter(Ui.mix(color, 0xFFFFFFFF, 0.6f), PorterDuff.Mode.SRC_IN));
        invalidate();
    }

    @Override
    public void onTick(long t) {
        float dt = last == 0 ? 0f : Math.min(0.1f, (t - last) / 1000f);
        last = t;
        now = t;
        breath = 1f + 0.06f * (float) Math.sin(t / 7000.0 * Math.PI * 2);
        for (int i = 0; i < MOTES; i++) {
            my[i] -= ms[i] * dt;
            if (my[i] < -RY * 0.75f) spawn(i, 0f);
        }
        if (strength > 0.02f) place();
    }

    @Override
    protected void onDraw(Canvas c) {
        if (strength <= 0.01f) return;
        c.drawRect(area, glow);
        int w = getWidth(), h = getHeight();
        for (int i = 0; i < MOTES; i++) {
            float x = (cx + mx[i] + 0.015f * (float) Math.sin(now / 2600.0 + mp[i])) * w, y = (cy + my[i]) * h;
            // fade in near the light's heart, out as the mote drifts away from it
            float d = Math.abs(my[i]) / (RY * 0.75f);
            float r = Ui.dpf(mr[i]) * 3f;
            mote.setAlpha(Math.round(255 * ma[i] * strength * Math.max(0f, 1f - d)));
            dst.set(x - r, y - r, x + r, y + r);
            c.drawBitmap(dot, null, dst, mote);
        }
    }
}
