package app.aura.home;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * Boot screen in the home screen's own language: the sun climbs the same dashed sky arc as the Sky
 * widget, and that climb is the progress bar, easing between real loading milestones while the sky
 * warms around it. Once the home screen is genuinely ready the maker's credit shows for a moment,
 * then the sun flares and the screen dissolves into the photograph.
 */
final class Splash extends View {
    private static final String WORD = "Aura", CREDIT = "coded by iravi :)";
    /** How long the credit holds once loading has landed, before the dissolve. */
    private static final long CREDIT_MS = 1500;

    private final Paint word = new Paint(Paint.ANTI_ALIAS_FLAG), caps = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint credit = new Paint(Paint.ANTI_ALIAS_FLAG), track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lit = new Paint(Paint.ANTI_ALIAS_FLAG), sun = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint horizon = new Paint(), glow = new Paint(), ground = new Paint(), glimmer = new Paint();
    private final RectF oval = new RectF();
    private final Matrix m = new Matrix();
    private final ValueAnimator loop;
    private RadialGradient warm;
    private float cx, sky, radius, target = 0.04f, shown, statusAlpha = 1f, creditAlpha, flare;
    private String status = "WAKING UP", next;
    private long last, landedAt;
    private boolean finishing, exiting;
    private Runnable onExit, onGone;

    Splash(Context c) {
        super(c);
        word.setTypeface(Ui.heavy);
        word.setTextSize(30 * Ui.scaled);
        word.setLetterSpacing(-0.03f);
        word.setTextAlign(Paint.Align.CENTER);
        word.setColor(0xF2FFFFFF);
        caps.setTypeface(Ui.bold);
        caps.setTextSize(7.5f * Ui.scaled);
        caps.setLetterSpacing(0.32f);
        caps.setTextAlign(Paint.Align.CENTER);
        caps.setColor(0xFFFFFFFF);
        credit.setTypeface(Typeface.MONOSPACE);
        credit.setTextSize(10 * Ui.scaled);
        credit.setTextAlign(Paint.Align.CENTER);
        credit.setColor(0xFFFFFFFF);
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeWidth(Ui.dpf(1.4f));
        track.setColor(0x47FFFFFF);
        track.setPathEffect(new DashPathEffect(new float[]{Ui.dpf(2.5f), Ui.dpf(4f)}, 0));
        lit.setStyle(Paint.Style.STROKE);
        lit.setStrokeWidth(Ui.dpf(2.4f));
        lit.setStrokeCap(Paint.Cap.ROUND);
        sun.setColor(0xFFFFF4D6);
        sun.setShadowLayer(Ui.dpf(12), 0, 0, 0xFFFFB347);
        warm = new RadialGradient(0, 0, 1f, new int[]{0x80FFB067, 0x26FF8A3D, 0x00FF8A3D},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
        glow.setShader(warm);
        loop = ValueAnimator.ofFloat(0f, 1f);
        loop.setDuration(1000);
        loop.setRepeatCount(ValueAnimator.INFINITE);
        loop.setInterpolator(new LinearInterpolator());
        loop.addUpdateListener(a -> {
            step();
            invalidate();
        });
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        loop.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        loop.cancel();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        cx = w / 2f;
        sky = h * 0.54f;
        radius = Ui.dpf(92);
        oval.set(cx - radius, sky - radius, cx + radius, sky + radius);
        lit.setShader(new LinearGradient(cx - radius, 0, cx + radius, 0, 0xFFFFA65C, 0xFFFFF0C8, Shader.TileMode.CLAMP));
        float hw = Ui.dpf(170);
        horizon.setShader(new LinearGradient(cx - hw, 0, cx + hw, 0, new int[]{0x00FFFFFF, 0x4DFFFFFF, 0x00FFFFFF},
                null, Shader.TileMode.CLAMP));
        ground.setShader(new LinearGradient(0, sky, 0, h, 0x00000000, 0xB3000000, Shader.TileMode.CLAMP));
        glimmer.setShader(new LinearGradient(0, sky, 0, sky + Ui.dpf(22), 0x59FFE2B8, 0x00FFE2B8, Shader.TileMode.CLAMP));
    }

    /** Raise the sun to at least {@code p} along its arc and, if given, swap the status line. */
    void setProgress(float p, String s) {
        target = Math.max(target, Math.min(1f, p));
        if (s != null && !s.equals(status) && !s.equals(next)) next = s;
    }

    /** Carry the sun home; once it lands and the credit has shown, {@code exit} runs as the dissolve begins, {@code gone} when it ends. */
    void finish(String s, Runnable exit, Runnable gone) {
        setProgress(1f, s);
        onExit = exit;
        onGone = gone;
        finishing = true;
    }

    private void step() {
        long now = SystemClock.uptimeMillis();
        float dt = last == 0 ? 0.016f : Math.min(0.05f, (now - last) / 1000f);
        last = now;
        // ease toward the target, with a slow creep so the sun never looks stalled
        float gap = target - shown;
        shown += Math.max(gap * Math.min(1f, dt * 4f), Math.min(gap, dt * 0.05f));
        if (next != null) {
            statusAlpha -= dt * 6f;
            if (statusAlpha <= 0f) {
                statusAlpha = 0f;
                status = next;
                next = null;
            }
        } else if (statusAlpha < 1f) {
            statusAlpha = Math.min(1f, statusAlpha + dt * 4f);
        }
        if (finishing && landedAt == 0 && shown > 0.996f && next == null) landedAt = now;
        if (landedAt > 0) {
            creditAlpha = Math.min(1f, (now - landedAt) / 500f);
            if (!exiting && now - landedAt >= CREDIT_MS) {
                exiting = true;
                dissolve();
            }
        }
    }

    private void dissolve() {
        if (onExit != null) onExit.run();
        ValueAnimator f = ValueAnimator.ofFloat(0f, 1f);
        f.setDuration(900);
        f.setInterpolator(Ui.SOFT);
        f.addUpdateListener(a -> flare = (float) a.getAnimatedValue());
        f.start();
        animate().alpha(0f).scaleX(1.04f).scaleY(1.04f).setStartDelay(120).setDuration(900).setInterpolator(Ui.SOFT)
                .withEndAction(() -> {
                    loop.cancel();
                    if (onGone != null) onGone.run();
                }).start();
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        float p = shown;
        // night lifting toward first light as the sun climbs
        c.drawColor(Ui.mix(0xFF04050B, 0xFF0C1630, 0.75f * p));

        double a = Math.toRadians(180 + 180 * p);
        float sx = cx + radius * (float) Math.cos(a), sy = sky + radius * (float) Math.sin(a);
        // warm light around the sun, swelling as it rises and flaring as the screen dissolves
        float gr = Ui.dpf(210) * (1f + flare * 1.6f);
        m.setScale(gr, gr);
        m.postTranslate(sx, sy);
        warm.setLocalMatrix(m);
        glow.setAlpha(Math.round(255 * Math.min(1f, 0.35f + 0.5f * p + flare)));
        c.drawRect(sx - gr, sy - gr, sx + gr, sy + gr, glow);
        c.drawRect(0, sky, w, h, ground);

        c.drawRect(cx - Ui.dpf(170), sky, cx + Ui.dpf(170), sky + Ui.hair(), horizon);
        c.drawArc(oval, 180, 180, false, track);
        if (p > 0.002f) c.drawArc(oval, 180, 180 * p, false, lit);
        c.drawCircle(sx, sy, Ui.dpf(5.5f), sun);
        // its reflection: a faint glimmer on still water, kept short of the wordmark
        c.drawRect(sx - Ui.dpf(1.2f), sky + Ui.dpf(3), sx + Ui.dpf(1.2f), sky + Ui.dpf(22), glimmer);

        c.drawText(WORD, cx, sky + Ui.dpf(52), word);
        caps.setAlpha(Math.round(140 * statusAlpha));
        c.drawText(status, cx, sky + Ui.dpf(76), caps);
        if (creditAlpha > 0f) {
            credit.setAlpha(Math.round(165 * creditAlpha));
            c.drawText(CREDIT, cx, h - Ui.dpf(38), credit);
        }
    }
}
