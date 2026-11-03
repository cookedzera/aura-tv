package app.aura.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
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

    static void trim() {
        LOGOS.clear();
    }

    private Tiles() { }

    /** Your own picture for an app (see {@link Art}), filling the tile in every look. */
    static Painter picture(Bitmap b) {
        // made once, not on every redraw of a tile that grows and glints as focus passes
        final android.graphics.Matrix m = new android.graphics.Matrix();
        final Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        return (c, w, h) -> {
            float k = Math.max(w / (float) b.getWidth(), h / (float) b.getHeight());
            m.setScale(k, k);
            m.postTranslate((w - b.getWidth() * k) / 2f, (h - b.getHeight() * k) / 2f);
            c.drawBitmap(b, m, p);
        };
    }

    /** An app's tile art in a look ({@link Prefs#TILE_STYLES}); your own picture, where there is one, in any look. */
    static Painter forStyle(AppRepo.App a, String style) {
        if (a.art != null) return picture(a.art);
        if (style.equals("tvos")) return tvos(a);
        return style.equals("glass") ? glass(a) : style.equals("vivid") ? vivid(a) : style.equals("midnight") ? midnight(a) : app(a);
    }

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
            c.drawText(Words.count(R.plurals.n_apps_caps, count), w / 2f, h * 0.80f, t);
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
    /**
     * Apple TV (tvOS): each app keeps its own original banner or icon, finished with tvOS's top-lit
     * dimensional gloss and, for icon-only apps, a vibrant gradient ground and rounded squircle badge.
     */
    static Painter tvos(AppRepo.App a) {
        return (c, w, h) -> {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG | Paint.FILTER_BITMAP_FLAG);
            if (a.banner != null) {
                banner(c, a.banner, w, h);
                p.setShader(new RadialGradient(w * 0.30f, -h * 0.35f, w * 0.90f, 0x24FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
                c.drawRect(0, 0, w, h, p);
                p.setShader(new LinearGradient(0, h * 0.65f, 0, h, 0x00000000, 0x22000000, Shader.TileMode.CLAMP));
                c.drawRect(0, h * 0.65f, w, h, p);
                return;
            }
            int base = saturate(a.color, 1.28f, 0.66f);
            p.setShader(new LinearGradient(0, 0, w * 0.85f, h, Ui.mix(base, 0xFFFFFFFF, 0.16f), Ui.mix(base, 0xFF000000, 0.38f),
                    Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
            p.setShader(new RadialGradient(w * 0.28f, -h * 0.35f, w * 0.90f, 0x2EFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
            p.setShader(null);
            if (a.icon == null) return;
            Drawable d = a.icon.getConstantState() != null ? a.icon.getConstantState().newDrawable().mutate() : a.icon;
            int s = Math.round(h * 0.60f);
            float x = (w - s) / 2f, y = (h - s) / 2f, rad = s * 0.225f;
            Paint sh = new Paint(Paint.ANTI_ALIAS_FLAG);
            sh.setColor(0x33000000);
            sh.setMaskFilter(new BlurMaskFilter( Math.max(1f, h * 0.04f), BlurMaskFilter.Blur.NORMAL));
            c.drawRoundRect(new RectF(x, y + h * 0.025f, x + s, y + s + h * 0.025f), rad, rad, sh);
            Bitmap ib = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, s, s);
            d.draw(new Canvas(ib));
            BitmapShader bs = new BitmapShader(ib, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            Matrix m = new Matrix();
            m.setTranslate(x, y);
            bs.setLocalMatrix(m);
            p.setShader(bs);
            c.drawRoundRect(new RectF(x, y, x + s, y + s), rad, rad, p);
            p.setShader(null);
            ib.recycle();
        };
    }

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
                // drawn: the small copy of the icon isn't needed any more
                ib.recycle();
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

    /** A lifted mark's ivory: white with the faintest warmth, like the focus ring. */
    private static final int MARK = 0xFCFBF8;

    /**
     * The app's mark in white, as the glass look shows every app: lifted off its banner, else off its
     * icon. Failing both, the icon itself, in full colour.
     */
    private static void mark(Canvas c, AppRepo.App a, int w, int h) {
        // a pane not laid out yet (or hidden, as the widgets are in the tvOS style) has nothing to draw into
        if (w <= 0 || h <= 0) return;
        String key = a.pkg + "@" + w + "x" + h;
        if (!LOGOS.containsKey(key)) {
            Bitmap lifted = a.banner != null ? lift(a.banner, w, h) : null;
            if (lifted == null && a.icon != null) lifted = liftIcon(a.icon, h);
            LOGOS.put(key, lifted != null ? withShadow(lifted) : null);
        }
        Bitmap logo = LOGOS.get(key);
        if (logo != null) {
            c.save();
            if (logo.getWidth() == w) {
                // a banner's mark: a little breathing room, so no mark touches the pane's rounded edge
                c.scale(0.8f, 0.8f, w / 2f, h / 2f);
                c.drawBitmap(logo, 0, 0, new Paint(Paint.FILTER_BITMAP_FLAG));
            } else {
                c.drawBitmap(logo, (w - logo.getWidth()) / 2f, (h - logo.getHeight()) / 2f, new Paint(Paint.FILTER_BITMAP_FLAG));
            }
            c.restore();
        } else if (a.icon != null) {
            // nothing clean to lift (a photograph, a pale badge with dark detail): the icon as it is
            Drawable icon = a.icon.getConstantState() != null ? a.icon.getConstantState().newDrawable().mutate() : a.icon;
            int s = Math.round(h * 0.60f);
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
            p.setShader(null);
            ib.recycle();
        }
    }

    /**
     * The mark over a faint, soft shadow of itself, baked in once: it keeps the white legible where
     * the glass is pale (a bright sky, or a pane lit by focus) and costs nothing to draw.
     */
    private static Bitmap withShadow(Bitmap mark) {
        Paint blur = new Paint(Paint.ANTI_ALIAS_FLAG);
        blur.setMaskFilter(new BlurMaskFilter(Ui.dpf(2.5f), BlurMaskFilter.Blur.NORMAL));
        int[] off = new int[2];
        Bitmap shadow = mark.extractAlpha(blur, off);
        Bitmap out = Bitmap.createBitmap(mark.getWidth(), mark.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        p.setColor(0x30000000);
        c.drawBitmap(shadow, off[0], off[1] + Ui.dpf(1), p);
        c.drawBitmap(mark, 0, 0, null);
        shadow.recycle();
        mark.recycle();
        return out;
    }

    /**
     * A banner's mark: its ground keyed out (see {@link #key}). Null when the banner is a photograph
     * (a busy edge) or nothing on it makes a clean mark.
     */
    private static Bitmap lift(Drawable d, int w, int h) {
        if (w < 16 || h < 16) return null;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        banner(new Canvas(b), d, w, h);
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        // a flat or graded ground changes slowly from one border pixel to the next; a photograph doesn't
        long step = 0;
        int steps = 0;
        for (int x = 1; x < w; x++) {
            step += colorStep(px[x], px[x - 1]) + colorStep(px[(h - 1) * w + x], px[(h - 1) * w + x - 1]);
            steps += 2;
        }
        for (int y = 1; y < h; y++) {
            step += colorStep(px[y * w], px[(y - 1) * w]) + colorStep(px[y * w + w - 1], px[(y - 1) * w + w - 1]);
            steps += 2;
        }
        if (step / (double) steps > 14.0) return null;
        int[] out = key(px, w, 1, 1, w - 2, h - 2);
        if (out == null) return null;
        b.setPixels(out, 0, w, 0, 0, w, h);
        return b;
    }

    /**
     * An icon's mark, for an app whose banner gave none: a glyph standing on transparency (Kodi) as
     * its silhouette; a badge's symbol keyed off its ground, flat or graded (JioHotstar's star); a dark
     * badge with light lettering as a white badge with the lettering cut out of it (Sony LIV). Null
     * for the rest, which keep their colours. Square; the caller centres it.
     */
    private static Bitmap liftIcon(Drawable d, int h) {
        int s = Math.round(h * 0.86f);
        if (s < 16) return null;
        Drawable copy = d.getConstantState() != null ? d.getConstantState().newDrawable().mutate() : d;
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        copy.setBounds(0, 0, s, s);
        copy.draw(new Canvas(b));
        int[] px = new int[s * s];
        b.getPixels(px, 0, s, 0, 0, s, s);
        // the opaque shape's bounds, and how much of them it fills: a badge (square, squircle or disc,
        // padded or not) fills most of them; a glyph standing on transparency, much less
        int l = s, t = s, rt = -1, bm = -1, opaque = 0;
        for (int y = 0; y < s; y++) {
            for (int x = 0; x < s; x++) {
                if ((px[y * s + x] >>> 24) <= 128) continue;
                opaque++;
                l = Math.min(l, x);
                rt = Math.max(rt, x);
                t = Math.min(t, y);
                bm = Math.max(bm, y);
            }
        }
        if (opaque == 0) {
            b.recycle();
            return null;
        }
        float fill = opaque / (float) ((rt - l + 1) * (bm - t + 1)), size;
        if (fill < 0.7f) {
            // a glyph: its silhouette, with any near-black detail cut out of it
            for (int i = 0; i < px.length; i++) {
                int p = px[i];
                float a = (p >>> 24) / 255f * smooth((Math.max(r(p), Math.max(g(p), bl(p))) / 255f - 0.08f) / 0.27f);
                px[i] = a <= 0f ? 0 : (Math.round(a * 248) << 24) | MARK;
            }
            size = 0.6f;
        } else {
            // a badge: its ground sampled on a frame well inside the shape, clear of rounded corners
            int ix = Math.max(2, Math.round((rt - l) * 0.16f)), iy = Math.max(2, Math.round((bm - t) * 0.16f));
            int x0 = l + ix, y0 = t + iy, x1 = rt - ix, y1 = bm - iy;
            if (x1 - x0 < 8 || y1 - y0 < 8) {
                b.recycle();
                return null;
            }
            int[] keyed = key(px, s, x0, y0, x1, y1);
            if (keyed != null) {
                px = keyed;
                size = 0.86f;
            } else {
                float ground = 0f;
                for (int x = x0; x <= x1; x++) ground += luma(px[y0 * s + x]) + luma(px[y1 * s + x]);
                if (ground / (2 * (x1 - x0 + 1)) >= 0.22f) {
                    b.recycle();
                    return null;
                }
                // a dark badge with light lettering: the badge in white, the lettering cut out of it,
                // its corners rounded (some badges are drawn square and leave that to the launcher)
                float rr = Math.min(rt - l, bm - t) * 0.22f;
                for (int i = 0; i < px.length; i++) {
                    int p = px[i], x = i % s, y = i / s;
                    float dx = Math.max(0f, Math.max(l + rr - x, x - (rt - rr))), dy = Math.max(0f, Math.max(t + rr - y, y - (bm - rr)));
                    float corner = Ui.clamp(rr - (float) Math.sqrt(dx * dx + dy * dy) + 0.5f, 0f, 1f);
                    float a = corner * (p >>> 24) / 255f * (1f - smooth((luma(p) - 0.32f) / 0.4f));
                    px[i] = a <= 0f ? 0 : (Math.round(a * 248) << 24) | MARK;
                }
                size = 0.62f;
            }
        }
        b.setPixels(px, 0, s, 0, 0, s, s);
        int out = Math.round(h * size);
        if (out == s) return b;
        Bitmap scaled = Bitmap.createScaledBitmap(b, out, out, true);
        if (scaled != b) b.recycle();
        return scaled;
    }

    /**
     * Keys the ground out of the frame (x0, y0)-(x1, y1) of {@code px}, {@code w} wide: one flat colour
     * or a smooth gradient, carried in from the frame's edges (a Coons patch). What differs from it
     * becomes the ivory mark, its opacity following how much it differs, so lettering keeps its soft
     * edges; outside the frame, nothing. Null when what's left is no logo: too little or too much of
     * it, running off the edge, or a soft wash (a swoosh keyed off a gradient) instead of a clean shape.
     */
    private static int[] key(int[] px, int w, int x0, int y0, int x1, int y1) {
        int c00 = px[y0 * w + x0], c10 = px[y0 * w + x1], c01 = px[y1 * w + x0], c11 = px[y1 * w + x1];
        int[] out = new int[px.length];
        int kept = 0, soft = 0, edge = 0;
        for (int y = y0; y <= y1; y++) {
            float v = (y - y0) / (float) Math.max(1, y1 - y0);
            int left = px[y * w + x0], right = px[y * w + x1];
            for (int x = x0; x <= x1; x++) {
                float u = (x - x0) / (float) Math.max(1, x1 - x0);
                float k00 = (1 - u) * (1 - v), k10 = u * (1 - v), k01 = (1 - u) * v, k11 = u * v;
                int top = px[y0 * w + x], bot = px[y1 * w + x], p = px[y * w + x];
                int dr = r(p) - clampByte((1 - u) * r(left) + u * r(right) + (1 - v) * r(top) + v * r(bot)
                        - (k00 * r(c00) + k10 * r(c10) + k01 * r(c01) + k11 * r(c11)));
                int dg = g(p) - clampByte((1 - u) * g(left) + u * g(right) + (1 - v) * g(top) + v * g(bot)
                        - (k00 * g(c00) + k10 * g(c10) + k01 * g(c01) + k11 * g(c11)));
                int db = bl(p) - clampByte((1 - u) * bl(left) + u * bl(right) + (1 - v) * bl(top) + v * bl(bot)
                        - (k00 * bl(c00) + k10 * bl(c10) + k01 * bl(c01) + k11 * bl(c11)));
                float a = smooth((float) (Math.sqrt(dr * dr + dg * dg + db * db) - 36) / 58f) * (p >>> 24) / 255f;
                if (a <= 0f) continue;
                if (a > 0.5f) {
                    kept++;
                    if (x < x0 + 3 || x > x1 - 3 || y < y0 + 3 || y > y1 - 3) edge++;
                }
                if (a > 0.3f && a < 0.7f) soft++;
                out[y * w + x] = (Math.round(a * 248) << 24) | MARK;
            }
        }
        float cover = kept / (float) ((x1 - x0 + 1) * (y1 - y0 + 1));
        if (cover < 0.008f || cover > 0.55f || edge > (x1 - x0 + y1 - y0) * 0.45f || soft > kept * 0.14f) return null;
        // a busy picture keys into specks, lone pixels with hardly a neighbour: no logo, so no mark
        int specks = 0;
        for (int y = y0 + 1; y < y1; y++) {
            for (int x = x0 + 1; x < x1; x++) {
                if ((out[y * w + x] >>> 24) <= 124) continue;
                int near = ((out[y * w + x - 1] >>> 24) > 124 ? 1 : 0) + ((out[y * w + x + 1] >>> 24) > 124 ? 1 : 0)
                        + ((out[(y - 1) * w + x] >>> 24) > 124 ? 1 : 0) + ((out[(y + 1) * w + x] >>> 24) > 124 ? 1 : 0);
                if (near <= 1) specks++;
            }
        }
        return specks > kept * 0.06f ? null : out;
    }

    private static float smooth(float x) {
        if (x <= 0f) return 0f;
        if (x >= 1f) return 1f;
        return x * x * (3 - 2 * x);
    }

    private static float luma(int c) {
        return (0.2126f * r(c) + 0.7152f * g(c) + 0.0722f * bl(c)) / 255f;
    }

    private static int r(int c) { return (c >> 16) & 255; }
    private static int g(int c) { return (c >> 8) & 255; }
    private static int bl(int c) { return c & 255; }
    private static int clampByte(float v) { return Math.max(0, Math.min(255, Math.round(v))); }
    private static int colorStep(int a, int b) {
        return (Math.abs(r(a) - r(b)) + Math.abs(g(a) - g(b)) + Math.abs(bl(a) - bl(b))) / 3;
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
            float tw = tag.measureText(Words.get(R.string.live)), ph = h * 0.15f, pw = tw + h * 0.16f;
            float px = w - pw - w * 0.06f, py = h * 0.1f;
            p.setColor(0xFFE5304A);
            c.drawRoundRect(px, py, px + pw, py + ph, ph / 2f, ph / 2f, p);
            tag.setColor(0xFFFFFFFF);
            c.drawText(Words.get(R.string.live), px + h * 0.08f, py + ph * 0.72f, tag);
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
