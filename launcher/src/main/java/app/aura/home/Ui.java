package app.aura.home;

import android.animation.TimeInterpolator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.TextView;

/** Shared metrics, typography (Inter, variable weights), colour maths and motion curves. */
final class Ui {
    static float density = 1f, scaled = 1f;
    static Typeface thin, light, regular, medium, semibold, bold, heavy;

    /** Under-damped spring: quick lift with a soft overshoot. */
    static final TimeInterpolator SPRING = t -> (float) (1 - Math.exp(-5.2 * t) * Math.cos(8.2 * t));
    static final TimeInterpolator EMPHASIZED = new PathInterpolator(0.2f, 0f, 0f, 1f);
    static final TimeInterpolator SOFT = new PathInterpolator(0.4f, 0f, 0.2f, 1f);
    static final TimeInterpolator DECEL = new DecelerateInterpolator(2f);

    private Ui() { }

    static void init(Context c) {
        density = c.getResources().getDisplayMetrics().density;
        scaled = c.getResources().getDisplayMetrics().scaledDensity;
        if (heavy != null) return;
        // the screensaver's clock: a hairline display cut
        thin = inter(c, 220, 32);
        light = inter(c, 300, 28);
        regular = inter(c, 400, 16);
        medium = inter(c, 500, 16);
        semibold = inter(c, 600, 14);
        bold = inter(c, 700, 16);
        heavy = inter(c, 800, 32);
    }

    private static Typeface inter(Context c, int weight, int opsz) {
        // variable font weights need Android 8; older TVs get the system's own sans at two weights
        if (android.os.Build.VERSION.SDK_INT < 26) {
            return Typeface.create(weight >= 700 ? "sans-serif" : weight >= 500 ? "sans-serif-medium" : "sans-serif",
                    weight >= 700 ? Typeface.BOLD : Typeface.NORMAL);
        }
        try {
            Typeface t = new Typeface.Builder(c.getAssets(), "fonts/Inter.ttf")
                    .setFontVariationSettings("'wght' " + weight + ", 'opsz' " + opsz)
                    .build();
            if (t != null) return t;
        } catch (RuntimeException ignored) {
        }
        return Typeface.create(weight >= 500 ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL);
    }

    static int dp(float v) {
        return Math.round(v * density);
    }

    static float dpf(float v) {
        return v * density;
    }

    /** One physical pixel, never less: the hairline of the editorial look. */
    static int hair() {
        return Math.max(1, Math.round(density * 0.75f));
    }

    static TextView text(Context c, float sp, Typeface tf, int color) {
        TextView t = new TextView(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(tf);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        return t;
    }

    /** Small, widely tracked capitals used for kickers, dates and labels. */
    static TextView caps(Context c, float sp, int color, float tracking) {
        TextView t = text(c, sp, bold, color);
        t.setLetterSpacing(tracking);
        lift(t, 6);
        return t;
    }

    static View rule(Context c, int color) {
        View v = new View(c);
        v.setBackgroundColor(color);
        return v;
    }

    /** Soft drop shadow so type stays legible over bright photographs. */
    static void lift(TextView t, float radiusDp) {
        t.setShadowLayer(dpf(radiusDp), 0, dpf(1), 0x59000000);
    }

    /** Shrinks a single-line headline until it fits the given width. */
    static void fit(TextView t, CharSequence s, float maxWidthPx, float maxSp, float minSp) {
        Paint p = new Paint(t.getPaint());
        float sp = maxSp;
        while (sp > minSp) {
            p.setTextSize(sp * scaled);
            if (p.measureText(s, 0, s.length()) <= maxWidthPx) break;
            sp -= 2f;
        }
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setText(s);
    }

    static int withAlpha(int color, int a) {
        return (Math.max(0, Math.min(255, a)) << 24) | (color & 0xFFFFFF);
    }

    static int alpha(int color, float a) {
        return withAlpha(color, Math.round(Color.alpha(color) * a));
    }

    static int mix(int a, int b, float t) {
        return Color.argb(
                Math.round(Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t),
                Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    /** {@code top} composited over {@code bottom}, both translucent. */
    static int over(int bottom, int top) {
        float ta = Color.alpha(top) / 255f, ba = Color.alpha(bottom) / 255f * (1f - ta), a = ta + ba;
        if (a <= 0f) return 0;
        return Color.argb(Math.round(a * 255),
                Math.round((Color.red(top) * ta + Color.red(bottom) * ba) / a),
                Math.round((Color.green(top) * ta + Color.green(bottom) * ba) / a),
                Math.round((Color.blue(top) * ta + Color.blue(bottom) * ba) / a));
    }

    /**
     * A pane of glass as one colour filter: the photo's colours lifted by {@code saturation}, then
     * each tint layer (its alpha is its opacity) laid over in order. Folding the layers into the
     * filter means a pane is drawn in one pass instead of one per layer.
     */
    static ColorMatrixColorFilter glass(float saturation, int... layers) {
        float k = 1f, r = 0f, g = 0f, b = 0f;
        for (int c : layers) {
            float a = Color.alpha(c) / 255f;
            r = r * (1f - a) + Color.red(c) * a;
            g = g * (1f - a) + Color.green(c) * a;
            b = b * (1f - a) + Color.blue(c) * a;
            k *= 1f - a;
        }
        ColorMatrix m = new ColorMatrix();
        m.setSaturation(saturation);
        m.postConcat(new ColorMatrix(new float[]{k, 0, 0, 0, r, 0, k, 0, 0, g, 0, 0, k, 0, b, 0, 0, 0, 1, 0}));
        return new ColorMatrixColorFilter(m);
    }

    static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }
}
