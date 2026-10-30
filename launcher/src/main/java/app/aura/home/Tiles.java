package app.aura.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import java.util.HashMap;
import java.util.Map;

/** Painters for card artwork: TV banners, generated icon cards and the Index card. */
final class Tiles {
    interface Painter {
        void paint(Canvas c, int w, int h);
    }

    /** Lifted logos by package and size; null marks a banner that had nothing to lift. */
    private static final Map<String, Bitmap> LOGOS = new HashMap<>();

    private Tiles() { }

    static Painter app(AppRepo.App a) {
        if (a.banner != null) return (c, w, h) -> banner(c, a.banner, w, h);
        return (c, w, h) -> icon(c, a.icon, a.color, w, h);
    }

    /** The "All apps" card: warm charcoal, a fine grid glyph and the app count in tracked capitals. */
    static Painter index(Context ctx, int count) {
        return (c, w, h) -> {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setShader(new LinearGradient(0, 0, w, h, 0xFF34302B, 0xFF151412, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
            p.setShader(new RadialGradient(w * 0.25f, -h * 0.3f, w, 0x26FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
            Drawable d = ctx.getDrawable(R.drawable.ic_apps).mutate();
            d.setTint(0xF2FFFFFF);
            int s = Math.round(h * 0.30f), cy = Math.round(h * 0.40f);
            d.setBounds((w - s) / 2, cy - s / 2, (w + s) / 2, cy + s / 2);
            d.draw(c);
            Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
            t.setTypeface(Ui.semibold);
            t.setTextSize(h * 0.105f);
            t.setLetterSpacing(0.28f);
            t.setColor(0xB3FFFFFF);
            t.setTextAlign(Paint.Align.CENTER);
            c.drawText(count + " APPS", w / 2f, h * 0.80f, t);
        };
    }

    /**
     * For glass tiles: the app's logo lifted off its banner as a white mark (the banner's flat
     * background keyed out), or, when the banner has no flat background to key, the app's own icon.
     */
    static Painter glass(AppRepo.App a) {
        return (c, w, h) -> mark(c, a, w, h);
    }

    /**
     * Vivid: the app's own colour as a rich, saturated gradient, lit from the top left, with its
     * white mark on it, the way app icons look on iOS. Opaque, painted once: as cheap as a tile gets.
     */
    static Painter vivid(AppRepo.App a) {
        return (c, w, h) -> {
            int base = saturate(a.color, 1.25f, 0.62f);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
            p.setShader(new LinearGradient(0, 0, w * 0.7f, h, Ui.mix(base, 0xFFFFFFFF, 0.10f), Ui.mix(base, 0xFF000000, 0.45f),
                    Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
            // a soft light from above, like a lamp on a glossy surface
            p.setShader(new RadialGradient(w * 0.3f, -h * 0.35f, w * 0.85f, 0x3DFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
            mark(c, a, w, h);
        };
    }

    /**
     * Midnight: near-black, with a faint glow of the app's colour rising from below and its white
     * mark. Calm in a dark room, and kind to OLED screens.
     */
    /**
     * A switcher card for an app whose screen can't be pictured (a protected video plays full
     * screen): what's on it instead, laid out as the paused player would show it. The episode's
     * still, edge to edge, darkening towards the bottom, its title and line, and how far in you are.
     */
    static Painter screen(Bitmap art, String title, String line, float progress) {
        return (c, w, h) -> {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
            c.drawColor(0xFF0B0C0F);
            float s = Math.max(w / (float) art.getWidth(), h / (float) art.getHeight());
            Matrix m = new Matrix();
            m.setScale(s, s);
            m.postTranslate((w - art.getWidth() * s) / 2f, (h - art.getHeight() * s) / 2f);
            c.drawBitmap(art, m, p);
            p.setShader(new LinearGradient(0, h * 0.42f, 0, h, 0x00000000, 0xD9000000, Shader.TileMode.CLAMP));
            c.drawRect(0, h * 0.42f, w, h, p);
            p.setShader(null);
            float x = w * 0.055f, room = w * 0.89f;
            android.text.TextPaint t = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
            t.setTypeface(Ui.semibold);
            t.setTextSize(h * 0.068f);
            t.setColor(0xF2FFFFFF);
            if (title != null) {
                c.drawText(android.text.TextUtils.ellipsize(title, t, room, android.text.TextUtils.TruncateAt.END).toString(), x, h * 0.83f, t);
            }
            if (line != null && !line.isEmpty()) {
                t.setTypeface(Ui.regular);
                t.setTextSize(h * 0.044f);
                t.setColor(0xB3FFFFFF);
                c.drawText(android.text.TextUtils.ellipsize(line, t, room, android.text.TextUtils.TruncateAt.END).toString(), x, h * 0.895f, t);
            }
            if (progress > 0f) {
                float y = h * 0.94f, bh = Math.max(2f, h * 0.011f);
                p.setColor(0x40FFFFFF);
                c.drawRoundRect(x, y, x + room, y + bh, bh, bh, p);
                p.setColor(0xF2FFFFFF);
                c.drawRoundRect(x, y, x + room * Math.min(1f, progress), y + bh, bh, bh, p);
            }
        };
    }

    /**
     * A switcher card with no picture of the app: its launch screen, as tvOS shows one. Near-black,
     * a low glow of the app's colour rising from below, and its icon large in the middle.
     */
    static Painter launch(AppRepo.App a) {
        return (c, w, h) -> {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
            p.setShader(new LinearGradient(0, 0, 0, h, 0xFF1A1C21, 0xFF0B0C0F, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
            int glow = saturate(a.color, 1.15f, 0.75f);
            p.setShader(new RadialGradient(w * 0.5f, h * 1.15f, w * 0.7f, Ui.withAlpha(glow, 0x5C), Ui.withAlpha(glow, 0), Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
            if (a.icon == null) return;
            int s = Math.round(h * 0.3f);
            float l = (w - s) / 2f, t = (h - s) / 2f;
            c.save();
            Path round = new Path();
            round.addRoundRect(new RectF(l, t, l + s, t + s), s * 0.22f, s * 0.22f, Path.Direction.CW);
            c.clipPath(round);
            // a copy: the icon's own bounds belong to the views already showing it
            Drawable d = a.icon.getConstantState() != null ? a.icon.getConstantState().newDrawable().mutate() : a.icon;
            d.setBounds(Math.round(l), Math.round(t), Math.round(l) + s, Math.round(t) + s);
            d.draw(c);
            c.restore();
        };
    }

    static Painter midnight(AppRepo.App a) {
        return (c, w, h) -> {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
            p.setShader(new LinearGradient(0, 0, 0, h, 0xFF15181E, 0xFF07080B, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
            int glow = saturate(a.color, 1.2f, 0.7f);
            p.setShader(new RadialGradient(w * 0.5f, h * 1.25f, w * 0.75f, Ui.withAlpha(glow, 0x70), Ui.withAlpha(glow, 0), Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
            mark(c, a, w, h);
        };
    }

    /**
     * A folder: up to four of its apps' icons in a 2x2 grid of small rounded squares, the way an
     * Apple TV folder shows what's inside. {@code pane} paints an opaque ground (for non-glass styles).
     */
    static Painter folder(java.util.List<AppRepo.App> apps, boolean pane) {
        return (c, w, h) -> {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            if (pane) {
                p.setShader(new LinearGradient(0, 0, w * 0.6f, h, 0xFF2A2E36, 0xFF121419, Shader.TileMode.CLAMP));
                c.drawRect(0, 0, w, h, p);
                p.setShader(null);
            }
            int s = Math.round(h * 0.32f), gap = Math.round(h * 0.06f);
            float x0 = (w - 2 * s - gap) / 2f, y0 = (h - 2 * s - gap) / 2f;
            for (int i = 0; i < 4; i++) {
                float x = x0 + (i % 2) * (s + gap), y = y0 + (i / 2) * (s + gap);
                if (i >= apps.size() || apps.get(i).icon == null) {
                    p.setColor(0x1FFFFFFF);
                    c.drawRoundRect(x, y, x + s, y + s, s * 0.24f, s * 0.24f, p);
                    continue;
                }
                android.graphics.drawable.Drawable d = apps.get(i).icon;
                d = d.getConstantState() != null ? d.getConstantState().newDrawable().mutate() : d;
                Bitmap ib = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
                d.setBounds(0, 0, s, s);
                d.draw(new Canvas(ib));
                BitmapShader sh = new BitmapShader(ib, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                Matrix m = new Matrix();
                m.setTranslate(x, y);
                sh.setLocalMatrix(m);
                p.setShader(sh);
                c.drawRoundRect(x, y, x + s, y + s, s * 0.24f, s * 0.24f, p);
                p.setShader(null);
            }
        };
    }

    /** The colour made livelier: saturation scaled, brightness at least {@code minValue}. */
    private static int saturate(int color, float sat, float minValue) {
        float[] hsv = new float[3];
        android.graphics.Color.colorToHSV(color, hsv);
        hsv[1] = Math.min(1f, hsv[1] * sat);
        hsv[2] = Math.max(minValue, hsv[2]);
        return android.graphics.Color.HSVToColor(hsv);
    }

    /** The app's white mark lifted off its banner, or its own icon as a rounded tile, centred. */
    private static void mark(Canvas c, AppRepo.App a, int w, int h) {
        {
            String key = a.pkg + "@" + w + "x" + h;
            if (!LOGOS.containsKey(key)) LOGOS.put(key, a.banner != null ? lift(a.banner, w, h) : null);
            Bitmap logo = LOGOS.get(key);
            if (logo != null) {
                // a little breathing room, so no mark touches the pane's rounded edge
                c.save();
                c.scale(0.8f, 0.8f, w / 2f, h / 2f);
                c.drawBitmap(logo, 0, 0, new Paint(Paint.FILTER_BITMAP_FLAG));
                c.restore();
            } else if (a.icon != null) {
                // the app's own icon as a rounded tile on the glass, edges anti-aliased via a shader
                Drawable icon = a.icon.getConstantState() != null ? a.icon.getConstantState().newDrawable().mutate() : a.icon;
                int s = Math.round(h * 0.62f);
                Bitmap ib = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
                icon.setBounds(0, 0, s, s);
                icon.draw(new Canvas(ib));
                float x = (w - s) / 2f, y = (h - s) / 2f;
                BitmapShader sh = new BitmapShader(ib, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                Matrix m = new Matrix();
                m.setTranslate(x, y);
                sh.setLocalMatrix(m);
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                p.setShader(sh);
                c.drawRoundRect(x, y, x + s, y + s, s * 0.24f, s * 0.24f, p);
            }
        }
    }

    /**
     * Keys out a banner's background: the colour running around its border, if that border is one
     * flat colour. Whatever differs from it becomes white, its opacity following how much it differs,
     * so lettering keeps its soft edges. Null when there is no flat background or no logo on it.
     */
    private static Bitmap lift(Drawable d, int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        banner(new Canvas(b), d, w, h);
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        int[] border = new int[2 * (w + h) * 2];
        int n = 0;
        for (int x = 0; x < w; x++) {
            border[n++] = px[x];
            border[n++] = px[w + x];
            border[n++] = px[(h - 1) * w + x];
            border[n++] = px[(h - 2) * w + x];
        }
        for (int y = 0; y < h; y++) {
            border[n++] = px[y * w];
            border[n++] = px[y * w + 1];
            border[n++] = px[y * w + w - 1];
            border[n++] = px[y * w + w - 2];
        }
        long sr = 0, sg = 0, sb = 0;
        for (int i = 0; i < n; i++) {
            sr += (border[i] >> 16) & 255;
            sg += (border[i] >> 8) & 255;
            sb += border[i] & 255;
        }
        int br = (int) (sr / n), bgr = (int) (sg / n), bb = (int) (sb / n);
        long dev = 0;
        for (int i = 0; i < n; i++) {
            dev += Math.abs(((border[i] >> 16) & 255) - br) + Math.abs(((border[i] >> 8) & 255) - bgr) + Math.abs((border[i] & 255) - bb);
        }
        if (dev / (3.0 * n) > 16) return null; // a photo or a gradient: nothing flat to key out
        int kept = 0;
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int dr = ((p >> 16) & 255) - br, dg = ((p >> 8) & 255) - bgr, db = (p & 255) - bb;
            float a = (float) ((Math.sqrt(dr * dr + dg * dg + db * db) - 40) / 60.0);
            if (a <= 0f) {
                px[i] = 0;
                continue;
            }
            if (a > 1f) a = 1f;
            a = a * a * (3 - 2 * a);
            if (a > 0.5f) kept++;
            px[i] = (Math.round(a * 250) << 24) | 0xFFFFFF;
        }
        float cover = kept / (float) px.length;
        if (cover < 0.01f || cover > 0.6f) return null;
        b.setPixels(px, 0, w, 0, 0, w, h);
        return b;
    }

    /** A live HDMI source on glass: the port glyph, its name and a red LIVE tag. */
    static Painter input(Context ctx, String label) {
        return (c, w, h) -> {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            Drawable d = ctx.getDrawable(label.startsWith("HDMI") ? R.drawable.ic_hdmi : R.drawable.ic_av).mutate();
            d.setTint(0xFFFFFFFF);
            int s = Math.round(h * 0.30f), x = Math.round(w * 0.09f), y = Math.round(h * 0.22f);
            d.setBounds(x, y, x + s, y + s);
            d.draw(c);
            Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
            t.setTypeface(Ui.heavy);
            t.setTextSize(h * 0.2f);
            t.setColor(0xFFFFFFFF);
            t.setLetterSpacing(-0.02f);
            c.drawText(label, x, h * 0.80f, t);
            // LIVE tag, top right
            Paint tag = new Paint(Paint.ANTI_ALIAS_FLAG);
            tag.setTypeface(Ui.bold);
            tag.setTextSize(h * 0.085f);
            tag.setLetterSpacing(0.18f);
            float tw = tag.measureText("LIVE"), ph = h * 0.15f, pw = tw + h * 0.16f;
            float px = w - pw - w * 0.06f, py = h * 0.1f;
            p.setColor(0xFFE5304A);
            c.drawRoundRect(px, py, px + pw, py + ph, ph / 2f, ph / 2f, p);
            tag.setColor(0xFFFFFFFF);
            c.drawText("LIVE", px + h * 0.08f, py + ph * 0.72f, tag);
        };
    }

    private static void banner(Canvas c, Drawable d, int w, int h) {
        c.drawColor(0xFF1E1C1A);
        int iw = d.getIntrinsicWidth(), ih = d.getIntrinsicHeight();
        if (iw > 0 && ih > 0) {
            float s = Math.max(w / (float) iw, h / (float) ih);
            int dw = Math.round(iw * s), dh = Math.round(ih * s);
            d.setBounds((w - dw) / 2, (h - dh) / 2, (w - dw) / 2 + dw, (h - dh) / 2 + dh);
        } else {
            d.setBounds(0, 0, w, h);
        }
        d.draw(c);
    }

    private static void icon(Canvas c, Drawable icon, int color, int w, int h) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, w * 0.6f, h, Ui.mix(color, 0xFF000000, 0.35f), Ui.mix(color, 0xFF000000, 0.8f),
                Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        if (icon == null) return;
        int s = Math.round(h * 0.58f);
        icon.setBounds((w - s) / 2, (h - s) / 2, (w + s) / 2, (h + s) / 2);
        icon.draw(c);
    }
}
