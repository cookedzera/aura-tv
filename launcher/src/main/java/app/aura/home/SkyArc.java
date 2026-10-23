package app.aura.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/** The day as an arc: sunrise at the left foot, sunset at the right, and the sun where it is right now. */
final class SkyArc extends View {
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), lit = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sun = new Paint(Paint.ANTI_ALIAS_FLAG), horizon = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint moon = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arc = new Path(), done = new Path(), crescent = new Path();
    private final PathMeasure measure = new PathMeasure();
    private final float[] pos = new float[2];
    private float progress = -1f;

    SkyArc(Context c) {
        super(c);
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeWidth(Ui.dpf(1.4f));
        track.setColor(0x47FFFFFF);
        track.setPathEffect(new DashPathEffect(new float[]{Ui.dpf(2.5f), Ui.dpf(4f)}, 0));
        lit.setStyle(Paint.Style.STROKE);
        lit.setStrokeWidth(Ui.dpf(2.2f));
        lit.setStrokeCap(Paint.Cap.ROUND);
        sun.setColor(0xFFFFF4D6);
        sun.setShadowLayer(Ui.dpf(9), 0, 0, 0xFFFFB347);
        horizon.setColor(0x24FFFFFF);
        moon.setColor(0xE6FFFFFF);
        moon.setShadowLayer(Ui.dpf(6), 0, 0, 0x99B7C6FF);
    }

    /** Places the sun between today's sunrise and sunset; outside daylight it shows a moon instead. */
    void set(long rise, long set, long now) {
        progress = set > rise && now >= rise && now <= set ? (now - rise) / (float) (set - rise) : -1f;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        float pad = Ui.dpf(7), base = h - Ui.dpf(3), top = Ui.dpf(7);
        arc.reset();
        arc.arcTo(new RectF(pad, top, w - pad, base + (base - top)), 180, 180, true);
        lit.setShader(new LinearGradient(pad, 0, w - pad, 0, 0xFFFFA65C, 0xFFFFF0C8, Shader.TileMode.CLAMP));
        float r = Ui.dpf(5.5f), cx = pad + Ui.dpf(4), cy = base - Ui.dpf(10);
        Path a = new Path(), b = new Path();
        a.addCircle(cx, cy, r, Path.Direction.CW);
        b.addCircle(cx + r * 0.55f, cy - r * 0.35f, r * 0.9f, Path.Direction.CW);
        crescent.reset();
        crescent.op(a, b, Path.Op.DIFFERENCE);
    }

    @Override
    protected void onDraw(Canvas c) {
        float base = getHeight() - Ui.dpf(3);
        c.drawRect(0, base, getWidth(), base + Ui.hair(), horizon);
        c.drawPath(arc, track);
        if (progress >= 0f) {
            measure.setPath(arc, false);
            float len = measure.getLength() * progress;
            done.reset();
            measure.getSegment(0, len, done, true);
            c.drawPath(done, lit);
            measure.getPosTan(len, pos, null);
            c.drawCircle(pos[0], pos[1], Ui.dpf(5f), sun);
        } else {
            c.drawPath(crescent, moon);
        }
    }
}
