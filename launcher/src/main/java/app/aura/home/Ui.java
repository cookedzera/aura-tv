package app.aura.home;

import android.animation.TimeInterpolator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
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

    /** Calm hydraulic spring: quick lift with a gentle, weightless settle. */
    static final TimeInterpolator SPRING = t -> (float) (1 - Math.exp(-6.0 * t) * Math.cos(4.2 * t));
    static final TimeInterpolator EMPHASIZED = new PathInterpolator(0.2f, 0f, 0f, 1f);
    static final TimeInterpolator SOFT = new PathInterpolator(0.4f, 0f, 0.2f, 1f);
    static final TimeInterpolator DECEL = new DecelerateInterpolator(2f);

    /**
     * The glass: the photograph behind it mostly drained of colour, so every pane reads as neutral
     * frosted glass on any wallpaper, between a smoke that holds back bright skies and an ivory frost.
     */
    static final float GLASS_SAT = 0.4f;
    static final int SMOKE = 0xFF101215, IVORY = 0xFFFFFDF9;
    /** The focus ring: warm ivory, a touch brighter at the top, as if lit from above. */
    static final int RING_TOP = 0xFFFBF2E7, RING_BOTTOM = 0xFFEADCCA;
    /** How far a raised pane's shadow ({@link #shadow}) reaches past the pane, in dp. */
    static final float SHADOW_REACH = 24f;
    private static final java.util.HashMap<String, Bitmap> SHADOWS = new java.util.HashMap<>();

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

    /**
     * {@code s} on at most {@code lines} lines {@code widthPx} wide: as large as it can be, from
     * {@code maxSp} down to {@code minSp}, wrapping by words as it will be drawn. Only a text too
     * long even then is shortened at its end.
     */
    static void fitLines(TextView t, CharSequence s, float widthPx, int lines, float maxSp, float minSp) {
        android.text.TextPaint p = new android.text.TextPaint(t.getPaint());
        int w = Math.max(1, Math.round(widthPx));
        float sp = maxSp;
        while (sp > minSp) {
            p.setTextSize(sp * scaled);
            @SuppressWarnings("deprecation")
            android.text.StaticLayout l = new android.text.StaticLayout(s, p, w, android.text.Layout.Alignment.ALIGN_NORMAL,
                    t.getLineSpacingMultiplier(), t.getLineSpacingExtra(), t.getIncludeFontPadding());
            if (l.getLineCount() <= lines) break;
            sp -= 0.5f;
        }
        t.setSingleLine(lines == 1);
        t.setMaxLines(lines);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, Math.max(sp, minSp));
        t.setText(s);
    }

    /**
     * A single line whose width comes from its place (a row, a button): once laid out, it shrinks
     * from its size down to {@code minSp} until it fits, and only then is shortened at its end. Some
     * languages need half as many letters again as English for the same words.
     */
    static void shrinkToWidth(TextView t, float minSp) {
        final float maxSp = t.getTextSize() / scaled;
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        // every layout pass calls this: the same words in the same room are worked out only once
        final Paint p = new Paint();
        final String[] last = {null};
        t.addOnLayoutChangeListener((v, l, top, r, b, ol, ot, or, ob) -> {
            int room = r - l - t.getCompoundPaddingLeft() - t.getCompoundPaddingRight();
            CharSequence s = t.getText();
            if (room <= 0 || s.length() == 0) return;
            String now = room + "|" + t.getTextSize() + "|" + s;
            if (now.equals(last[0])) return;
            last[0] = now;
            p.set(t.getPaint());
            float sp = maxSp;
            while (sp > minSp) {
                p.setTextSize(sp * scaled);
                if (p.measureText(s, 0, s.length()) <= room) break;
                sp -= 0.5f;
            }
            final float want = Math.max(sp, minSp);
            if (Math.abs(t.getTextSize() - want * scaled) > 0.5f) t.post(() -> t.setTextSize(TypedValue.COMPLEX_UNIT_SP, want));
        });
    }

    private static final java.util.regex.Pattern NOISE = java.util.regex.Pattern.compile(
            "\\s*(?:[-–—]\\s*(?:\\d{4}\\s+)?remaster(?:ed)?(?:\\s+(?:version|\\d{4}))*"
                    + "|[(\\[](?:official|remaster|lyric|audio|visuali[sz]er|hd|4k)[^)\\]]*[)\\]])\\s*$",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    /** A song's name without the release's noise ("- Remastered 2011", "(Official Video)"), which crowds a small card. */
    static String songTitle(String s) {
        if (s == null) return "";
        String out = s.trim();
        for (int i = 0; i < 3; i++) {
            String next = NOISE.matcher(out).replaceFirst("").trim();
            if (next.equals(out) || next.isEmpty()) break;
            out = next;
        }
        return out;
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

    /**
     * The soft shadow a raised pane of {@code w} x {@code h} px casts, as an alpha mask a quarter of
     * the size, drawn stretched over the pane's area plus {@link #SHADOW_REACH} on every side: a blur
     * is smooth, so the small mask can't be told apart and costs a sixteenth to make and keep. Made
     * once per size, at layout, and shared by every pane of that size.
     */
    static Bitmap shadow(int w, int h, float radius) {
        String key = w + "x" + h + "r" + Math.round(radius);
        Bitmap b = SHADOWS.get(key);
        if (b != null) return b;
        float reach = dpf(SHADOW_REACH) / 4f;
        b = Bitmap.createBitmap(Math.max(1, Math.round(w / 4f + 2 * reach)), Math.max(1, Math.round(h / 4f + 2 * reach)),
                Bitmap.Config.ALPHA_8);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setMaskFilter(new BlurMaskFilter(Math.max(1f, dpf(10) / 4f), BlurMaskFilter.Blur.NORMAL));
        // a touch narrower than the pane, as beneath a card held up off a table
        float in = dpf(8) / 4f;
        new Canvas(b).drawRoundRect(reach + in, reach + in / 2f, reach + w / 4f - in, reach + h / 4f, radius / 4f, radius / 4f, p);
        SHADOWS.put(key, b);
        return b;
    }

    /** Lays {@link #shadow} under a pane {@code w} x {@code h}, dropped {@code drop} px and at {@code alpha}. */
    static void drawShadow(Canvas c, Bitmap shadow, int w, int h, float drop, int alpha, Paint p, android.graphics.Matrix m) {
        float reach = dpf(SHADOW_REACH);
        m.setScale((w + 2 * reach) / shadow.getWidth(), (h + 2 * reach) / shadow.getHeight());
        m.postTranslate(-reach, -reach + drop);
        p.setAlpha(alpha);
        c.drawBitmap(shadow, m, p);
    }

    static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }

    /** A picture's average colour, nudged towards something with a little life in it (a cover, a show's art). */
    static int tone(Bitmap b, int fallback) {
        if (b == null) return fallback;
        Bitmap one = Bitmap.createScaledBitmap(b, 6, 4, true);
        long r = 0, g = 0, bl = 0;
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 6; x++) {
                int p = one.getPixel(x, y);
                r += Color.red(p);
                g += Color.green(p);
                bl += Color.blue(p);
            }
        }
        if (one != b) one.recycle();
        float[] hsv = new float[3];
        Color.RGBToHSV((int) (r / 24), (int) (g / 24), (int) (bl / 24), hsv);
        hsv[1] = Math.min(1f, Math.max(0.35f, hsv[1] * 1.3f));
        hsv[2] = Math.max(0.45f, hsv[2]);
        return Color.HSVToColor(hsv);
    }
}
