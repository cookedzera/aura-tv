package app.aura.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
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
        return (c, w, h) -> {
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
        };
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
