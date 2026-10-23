package app.aura.home;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * A smart stack, like Apple's: one card at a time, each full-bleed (a show's artwork, or an app's
 * own colour with its white mark), changing on its own every few seconds with a crossfade. It
 * holds still while focused, so OK always opens the card you're looking at. Only the moment of a
 * change animates; between changes it costs nothing.
 */
final class SmartStack extends FrameLayout {
    static final class Slide {
        String kicker, title, sub;
        Bitmap art;          // a show's artwork, drawn edge to edge
        Tiles.Painter mark;  // or an app's mark, on its colour
        int color = 0xFF8AA0C0;
        float progress;
        Intent intent;
        String label;
    }

    interface Listener {
        void onSlide(Slide s);
    }

    private static final long EVERY_MS = 7000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Page[] pages = new Page[2];
    private final LinearLayout dots;
    private final List<Slide> slides = new ArrayList<>();
    private int at, front;
    private boolean held;
    Listener listener;

    private final Runnable advance = new Runnable() {
        @Override
        public void run() {
            if (!held && slides.size() > 1 && isShown()) show(at + 1, true);
            handler.postDelayed(this, EVERY_MS);
        }
    };

    SmartStack(Context c, float radiusDp) {
        super(c);
        final float r = Ui.dpf(radiusDp);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r);
            }
        });
        setClipToOutline(true);
        for (int i = 0; i < 2; i++) {
            pages[i] = new Page(c);
            addView(pages[i], new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        pages[1].setAlpha(0f);
        dots = new LinearLayout(c);
        dots.setGravity(Gravity.CENTER_VERTICAL);
        LayoutParams dl = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        dl.setMargins(0, Ui.dp(16), Ui.dp(16), 0);
        addView(dots, dl);
    }

    /** Fades between pages without an offscreen layer, like the other glass. */
    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    void setSlides(List<Slide> next) {
        Slide keep = current();
        slides.clear();
        slides.addAll(next);
        int to = 0;
        for (int i = 0; keep != null && i < slides.size(); i++) {
            if (TextUtils.equals(slides.get(i).title, keep.title) && TextUtils.equals(slides.get(i).kicker, keep.kicker)) to = i;
        }
        dots.removeAllViews();
        if (slides.size() > 1) {
            for (int i = 0; i < slides.size(); i++) {
                View d = new View(getContext());
                GradientDrawable g = new GradientDrawable();
                g.setShape(GradientDrawable.OVAL);
                g.setColor(0xFFFFFFFF);
                d.setBackground(g);
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(Ui.dp(5), Ui.dp(5));
                p.leftMargin = Ui.dp(5);
                dots.addView(d, p);
            }
        }
        show(to, false);
    }

    Slide current() {
        return slides.isEmpty() ? null : slides.get(Math.floorMod(at, slides.size()));
    }

    /** While focused (or covered) the stack holds still. */
    void hold(boolean h) {
        held = h;
    }

    void start() {
        handler.removeCallbacks(advance);
        handler.postDelayed(advance, EVERY_MS);
    }

    void stop() {
        handler.removeCallbacks(advance);
    }

    private void show(int i, boolean animate) {
        if (slides.isEmpty()) return;
        at = Math.floorMod(i, slides.size());
        Slide s = slides.get(at);
        for (int d = 0; d < dots.getChildCount(); d++) dots.getChildAt(d).setAlpha(d == at ? 1f : 0.35f);
        if (!animate) {
            pages[front].bind(s);
            pages[front].setAlpha(1f);
            pages[1 - front].setAlpha(0f);
        } else {
            Page in = pages[1 - front], out = pages[front];
            in.bind(s);
            in.bringToFront();
            dots.bringToFront();
            in.setAlpha(0f);
            in.content.setTranslationX(Ui.dp(14));
            in.animate().alpha(1f).setStartDelay(0).setDuration(620).setInterpolator(Ui.SOFT)
                    .withEndAction(() -> out.setAlpha(0f)).start();
            in.content.animate().translationX(0f).setStartDelay(80).setDuration(700).setInterpolator(Ui.EMPHASIZED).start();
            front = 1 - front;
        }
        if (listener != null) listener.onSlide(s);
    }

    /** One card: the picture, a shade for the words, the words, and how far along. */
    private static final class Page extends FrameLayout {
        final ImageView art;
        final View shade;
        final LinearLayout content;
        final TextView kicker, title, sub;
        final HomeActivity.Meter bar;

        Page(Context c) {
            super(c);
            art = new ImageView(c);
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            addView(art, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            shade = new View(c);
            shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[]{0xD9000000, 0x8C000000, 0x00000000}));
            addView(shade, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            content = new LinearLayout(c);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setGravity(Gravity.BOTTOM);
            content.setPadding(Ui.dp(18), Ui.dp(16), Ui.dp(18), Ui.dp(16));
            kicker = Ui.caps(c, 7.5f, 0xCCFFFFFF, 0.24f);
            kicker.setShadowLayer(0, 0, 0, 0);
            LinearLayout.LayoutParams kl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 0, 1f);
            content.addView(kicker, kl);
            title = Ui.text(c, 20, Ui.heavy, 0xFFFFFFFF);
            title.setIncludeFontPadding(true);
            title.setLetterSpacing(-0.02f);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setMaxWidth(Ui.dp(230));
            content.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            sub = Ui.text(c, 11, Ui.medium, 0xCCFFFFFF);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            sub.setMaxWidth(Ui.dp(240));
            content.addView(sub, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            bar = new HomeActivity.Meter(c);
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(3));
            bl.topMargin = Ui.dp(10);
            content.addView(bar, bl);
            addView(content, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        @Override
        public boolean hasOverlappingRendering() {
            return false;
        }

        void bind(Slide s) {
            kicker.setText(s.kicker);
            Ui.fit(title, s.title == null ? "" : s.title, Ui.dp(230), 20, 14);
            sub.setText(s.sub == null ? "" : s.sub);
            sub.setVisibility(s.sub == null || s.sub.isEmpty() ? View.GONE : View.VISIBLE);
            bar.set(s.progress);
            bar.setVisibility(s.progress > 0f ? View.VISIBLE : View.GONE);
            if (s.art != null) {
                art.setImageBitmap(s.art);
            } else {
                // an app card's picture is drawn once, then kept on the slide and reused
                post(() -> {
                    if (s.art == null) s.art = markArt(s, Math.max(1, getWidth()), Math.max(1, getHeight()));
                    art.setImageBitmap(s.art);
                });
            }
        }

        /** An app's card: its colour as a deep gradient, its white mark on the right. */
        private static Bitmap markArt(Slide s, int w, int h) {
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            Paint p = new Paint();
            p.setShader(new LinearGradient(0, 0, w, h, Ui.mix(s.color, 0xFF000000, 0.72f), Ui.mix(s.color, 0xFF000000, 0.35f),
                    Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
            if (s.mark != null) {
                int mw = Math.round(w * 0.46f), mh = Math.round(mw * 9 / 16f);
                c.save();
                c.translate(w - mw - w * 0.06f, (h - mh) / 2f);
                s.mark.paint(c, mw, mh);
                c.restore();
            }
            return b;
        }
    }
}
