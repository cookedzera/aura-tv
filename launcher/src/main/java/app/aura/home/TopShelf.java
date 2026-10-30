package app.aura.home;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Top Shelf, as on Apple TV: rest on an app in the top row and the row of widgets above becomes
 * that app's own content: what it shows on its Android TV home-screen rows, what you're partway
 * through in it, or what's new in it today. Up moves into the shelf, OK opens straight to it.
 */
final class TopShelf extends FrameLayout {
    private static final int MAX = 3;
    /** Per app: what was found and when, so resting on an app again costs nothing for a while. */
    private static final Map<String, Object[]> CACHE = new HashMap<>();

    final List<Card> cards = new ArrayList<>();
    private final LinearLayout row;
    private String shown;

    TopShelf(Context c) {
        super(c);
        setVisibility(GONE);
        setClipChildren(false);
        row = new LinearLayout(c);
        row.setClipChildren(false);
        addView(row, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    String shownFor() {
        return isShown() ? shown : null;
    }

    /** Off the main thread: up to three things to show for {@code pkg}, cached for two minutes. */
    @SuppressWarnings("unchecked")
    static List<Continue.Item> items(Context c, String pkg) {
        long now = SystemClock.elapsedRealtime();
        synchronized (CACHE) {
            Object[] hit = CACHE.get(pkg);
            if (hit != null && now - (Long) hit[0] < 120_000) return (List<Continue.Item>) hit[1];
        }
        List<Continue.Item> out = new ArrayList<>();
        // what you're partway through in this app comes first, then what the app itself features
        for (Continue.Item it : Continue.all(c, 8)) if (pkg.equals(it.pkg) && it.art != null && out.size() < MAX) out.add(it);
        for (Continue.Item it : Continue.previews(c, pkg, MAX * 2)) if (out.size() < MAX) out.add(it);
        if (out.size() < MAX) {
            for (Fresh.Show s : Fresh.load(c)) {
                if (!pkg.equals(s.pkg) || out.size() >= MAX) continue;
                Continue.Item it = new Continue.Item();
                it.title = s.name;
                it.sub = s.episode.isEmpty() ? "New today" : "New today  ·  " + s.episode;
                it.art = Fresh.art(s);
                it.pkg = pkg;
                if (it.art != null) out.add(it);
            }
        }
        synchronized (CACHE) {
            CACHE.put(pkg, new Object[]{now, out});
        }
        return out;
    }

    /** Lays out up to three cards across the row; {@code open} runs when one is chosen. */
    void show(String pkg, List<Continue.Item> items, int color, Opener open) {
        shown = pkg;
        row.removeAllViews();
        cards.clear();
        int n = items.size();
        for (int i = 0; i < n; i++) {
            final Continue.Item it = items.get(i);
            Card card = new Card(getContext(), paint(it), color, 22);
            card.onClick = () -> open.open(it);
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            if (i > 0) l.leftMargin = Ui.dp(18);
            row.addView(card, l);
            cards.add(card);
        }
        if (getVisibility() != VISIBLE) {
            setVisibility(VISIBLE);
            setAlpha(0f);
            setTranslationY(Ui.dp(10));
        }
        animate().alpha(1f).translationY(0f).setDuration(420).setStartDelay(0).setInterpolator(Ui.EMPHASIZED).withEndAction(null).start();
    }

    void hide() {
        shown = null;
        if (getVisibility() != VISIBLE) return;
        animate().alpha(0f).translationY(Ui.dp(6)).setDuration(240).setStartDelay(0).setInterpolator(Ui.SOFT)
                .withEndAction(() -> {
                    setVisibility(GONE);
                    row.removeAllViews();
                    cards.clear();
                }).start();
    }

    interface Opener {
        void open(Continue.Item it);
    }

    /** A card's picture: the artwork filling it, a shade along the bottom, the title and line on it. */
    static Tiles.Painter paint(Continue.Item it) {
        return (c, w, h) -> {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            c.drawColor(0xFF101216);
            Bitmap b = it.art;
            if (b != null) {
                float s = Math.max(w / (float) b.getWidth(), h / (float) b.getHeight());
                Matrix m = new Matrix();
                m.setScale(s, s);
                m.postTranslate((w - b.getWidth() * s) / 2f, (h - b.getHeight() * s) / 2f);
                c.drawBitmap(b, m, p);
            }
            p.setShader(new LinearGradient(0, h * 0.35f, 0, h, 0x00000000, 0xD9000000, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
            p.setShader(null);
            Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
            t.setTypeface(Ui.heavy);
            t.setColor(0xFFFFFFFF);
            t.setTextSize(h * 0.135f);
            float x = w * 0.07f, base = it.sub != null && !it.sub.isEmpty() ? h * 0.80f : h * 0.88f;
            c.drawText(TextUtils.ellipsize(it.title, new android.text.TextPaint(t), w * 0.86f, TextUtils.TruncateAt.END).toString(), x, base, t);
            if (it.sub != null && !it.sub.isEmpty()) {
                t.setTypeface(Ui.medium);
                t.setTextSize(h * 0.085f);
                t.setColor(0xCCFFFFFF);
                c.drawText(TextUtils.ellipsize(it.sub, new android.text.TextPaint(t), w * 0.86f, TextUtils.TruncateAt.END).toString(), x, h * 0.91f, t);
            }
            if (it.progress > 0f) {
                p.setColor(0x40FFFFFF);
                float y = h - Ui.dpf(4);
                c.drawRect(0, y, w, h, p);
                p.setColor(0xFFFFFFFF);
                c.drawRect(0, y, w * it.progress, h, p);
            }
        };
    }

    static Intent launchFor(Continue.Item it, AppRepo.App app) {
        return it.intent != null ? it.intent : app != null ? app.launch : null;
    }
}
