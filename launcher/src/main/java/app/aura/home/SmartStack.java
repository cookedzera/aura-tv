package app.aura.home;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
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
 * own colour with its white mark), turning to the next on its own. Cards flip vertically: the old
 * one lifts away as the new one rises in, its picture moving a little further than its words for
 * depth, while a pill slides down the indicator on the right. Up on the remote flips by hand. It
 * holds still while focused, so OK always opens the card you're looking at, and between flips it
 * costs nothing: no animation runs while a card is simply on show.
 */
final class SmartStack extends FrameLayout {
    static final class Slide {
        String kicker, title, sub;
        Bitmap art;          // a show's artwork, drawn edge to edge
        Tiles.Painter mark;  // or an app's mark, on its colour
        Drawable icon;       // the app's own icon, in the chip beside the kicker
        int color = 0xFF8AA0C0;
        float progress;
        Intent intent;
        String label;
        /** Something new (an episode out today): a small badge says so. */
        boolean fresh;
        /** How long it stays before the stack turns; things you're watching stay longer. */
        long dwellMs = 7_000;
        /** The shade's colour, taken from the artwork once and kept. */
        int tone;
    }

    interface Listener {
        void onSlide(Slide s);
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Page[] pages = new Page[2];
    private final Dots dots;
    private final List<Slide> slides = new ArrayList<>();
    private int at, front;
    private boolean held, running;
    Listener listener;

    private final Runnable advance = new Runnable() {
        @Override
        public void run() {
            if (!held && slides.size() > 1 && isShown()) show(at + 1, 1);
            schedule();
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
        setBackgroundColor(0xFF0B0D12);
        for (int i = 0; i < 2; i++) {
            pages[i] = new Page(c);
            addView(pages[i], new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        pages[1].setAlpha(0f);
        dots = new Dots(c);
        LayoutParams dl = new LayoutParams(Ui.dp(6), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END);
        dl.rightMargin = Ui.dp(12);
        addView(dots, dl);
    }

    /** Fades between pages without an offscreen layer, like the other glass. */
    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    /**
     * New cards. The same cards in the same order (a routine refresh) keep the one on show
     * exactly as it is: no flicker, no restart, only a progress bar that moved is redrawn.
     */
    void setSlides(List<Slide> next) {
        Slide keep = current();
        boolean same = next.size() == slides.size();
        for (int i = 0; same && i < next.size(); i++) same = sameCard(next.get(i), slides.get(i));
        if (same && !slides.isEmpty()) {
            for (int i = 0; i < next.size(); i++) {
                Slide old = slides.get(i), n = next.get(i);
                // an app card's picture and every card's tone are worked out once; keep them
                if (n.art == null && n.mark != null) n.art = old.art;
                if (n.tone == 0) n.tone = old.tone;
            }
            slides.clear();
            slides.addAll(next);
            pages[front].refresh(slides.get(at));
            return;
        }
        slides.clear();
        slides.addAll(next);
        int to = 0;
        for (int i = 0; keep != null && i < slides.size(); i++) if (sameCard(slides.get(i), keep)) to = i;
        dots.setCount(slides.size());
        show(to, 0);
        if (running) schedule();
    }

    private static boolean sameCard(Slide a, Slide b) {
        return TextUtils.equals(a.title, b.title) && TextUtils.equals(a.kicker, b.kicker) && TextUtils.equals(a.sub, b.sub);
    }

    Slide current() {
        return slides.isEmpty() ? null : slides.get(Math.floorMod(at, slides.size()));
    }

    /** While focused (or covered) the stack holds still. */
    void hold(boolean h) {
        held = h;
        dots.setLit(h);
    }

    /** Up on the remote: the next card now, and the clock starts again from it. */
    void flip() {
        if (slides.size() < 2) return;
        show(at + 1, 1);
        if (running) schedule();
    }

    void start() {
        running = true;
        schedule();
    }

    void stop() {
        running = false;
        handler.removeCallbacks(advance);
    }

    private void schedule() {
        handler.removeCallbacks(advance);
        Slide s = current();
        handler.postDelayed(advance, s != null ? s.dwellMs : 7_000);
    }

    /** Shows card {@code i}; {@code dir} 1 flips up to it, 0 shows it in place. */
    private void show(int i, int dir) {
        if (slides.isEmpty()) return;
        at = Math.floorMod(i, slides.size());
        Slide s = slides.get(at);
        dots.moveTo(at, dir != 0);
        if (dir == 0) {
            // straight to it, even if a flip was under way: both pages back to rest first
            for (Page pg : pages) {
                pg.animate().cancel();
                pg.art.animate().cancel();
                pg.settle();
            }
            pages[front].bind(s);
            pages[front].setAlpha(1f);
            pages[1 - front].setAlpha(0f);
        } else {
            Page in = pages[1 - front], out = pages[front];
            in.animate().cancel();
            out.animate().cancel();
            in.bind(s);
            in.bringToFront();
            dots.bringToFront();
            float lift = Ui.dpf(26);
            // the old card lifts away as the new one rises from just below. Only position and
            // fade move: no scaling and no offscreen layers, which cost this TV's GPU a frame each
            out.animate().translationY(-lift).alpha(0f).setStartDelay(0)
                    .setDuration(520).setInterpolator(Ui.EMPHASIZED).withEndAction(out::settle).start();
            in.setTranslationY(lift);
            in.setAlpha(0f);
            in.animate().translationY(0f).alpha(1f).setStartDelay(60)
                    .setDuration(640).setInterpolator(Ui.EMPHASIZED).start();
            // the picture travels further than the words: depth without any extra drawing later
            in.art.setTranslationY(lift * 0.6f);
            in.art.animate().translationY(0f).setStartDelay(60).setDuration(760).setInterpolator(Ui.EMPHASIZED).start();
            front = 1 - front;
        }
        if (listener != null) listener.onSlide(s);
    }

    /** One card: the picture, a shade in its own colour, a header chip, the words, and how far along. */
    private static final class Page extends FrameLayout {
        final ImageView art, icon;
        final View tint, floor;
        final TextView kicker, badge, title, sub;
        final HomeActivity.Meter bar;

        Page(Context c) {
            super(c);
            art = new ImageView(c);
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            addView(art, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            // two soft shades: the card's own colour from the left, and a darker floor for the words
            tint = new View(c);
            addView(tint, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            floor = new View(c);
            floor.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xB3000000, 0x33000000, 0x00000000}));
            addView(floor, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            LinearLayout content = new LinearLayout(c);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(Ui.dp(18), Ui.dp(15), Ui.dp(30), Ui.dp(16));

            LinearLayout head = new LinearLayout(c);
            head.setGravity(Gravity.CENTER_VERTICAL);
            icon = new ImageView(c);
            icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
            icon.setClipToOutline(true);
            icon.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View v, Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), Ui.dpf(4.5f));
                }
            });
            LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(Ui.dp(16), Ui.dp(16));
            il.rightMargin = Ui.dp(7);
            head.addView(icon, il);
            kicker = Ui.caps(c, 7.5f, 0xD9FFFFFF, 0.22f);
            kicker.setShadowLayer(0, 0, 0, 0);
            kicker.setEllipsize(TextUtils.TruncateAt.END);
            head.addView(kicker, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            badge = Ui.text(c, 7, Ui.heavy, 0xFF15171B);
            badge.setText("NEW");
            badge.setLetterSpacing(0.14f);
            badge.setPadding(Ui.dp(6), Ui.dp(2), Ui.dp(6), Ui.dp(2));
            GradientDrawable pill = new GradientDrawable();
            pill.setColor(0xF2FFFFFF);
            pill.setCornerRadius(Ui.dpf(8));
            badge.setBackground(pill);
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bl.leftMargin = Ui.dp(8);
            head.addView(badge, bl);
            content.addView(head, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            content.addView(new View(c), new LinearLayout.LayoutParams(1, 0, 1f));
            title = Ui.text(c, 20, Ui.heavy, 0xFFFFFFFF);
            title.setIncludeFontPadding(true);
            title.setLetterSpacing(-0.02f);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setMaxWidth(Ui.dp(250));
            content.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            sub = Ui.text(c, 11, Ui.medium, 0xD9FFFFFF);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            sub.setMaxWidth(Ui.dp(250));
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sl.topMargin = Ui.dp(2);
            content.addView(sub, sl);
            bar = new HomeActivity.Meter(c);
            LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(Ui.dp(150), Ui.dp(3));
            pl.topMargin = Ui.dp(9);
            content.addView(bar, pl);
            addView(content, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        @Override
        public boolean hasOverlappingRendering() {
            return false;
        }

        /** Back to rest after a flip, ready to be used again. */
        void settle() {
            setTranslationY(0f);
            setScaleX(1f);
            setScaleY(1f);
            art.setTranslationY(0f);
        }

        void bind(Slide s) {
            kicker.setText(s.kicker);
            badge.setVisibility(s.fresh ? View.VISIBLE : View.GONE);
            // its own copy: a drawable shared with the app's tile would fight over bounds and redraws
            Drawable.ConstantState cs = s.icon != null ? s.icon.getConstantState() : null;
            icon.setImageDrawable(cs != null ? cs.newDrawable(getResources()).mutate() : s.icon);
            icon.setVisibility(s.icon != null ? View.VISIBLE : View.GONE);
            Ui.fit(title, s.title == null ? "" : s.title, Ui.dp(250), 20, 14);
            refresh(s);
            if (s.art != null) {
                art.setImageBitmap(s.art);
                shade(s);
            } else {
                // an app card's picture is drawn once, then kept on the slide and reused
                post(() -> {
                    if (s.art == null) s.art = markArt(s, Math.max(1, getWidth()), Math.max(1, getHeight()));
                    art.setImageBitmap(s.art);
                    shade(s);
                });
            }
        }

        /** What can change on a card while it's on show: its line and how far along. */
        void refresh(Slide s) {
            sub.setText(s.sub == null ? "" : s.sub);
            sub.setVisibility(s.sub == null || s.sub.isEmpty() ? View.GONE : View.VISIBLE);
            bar.set(s.progress);
            bar.setVisibility(s.progress > 0f ? View.VISIBLE : View.GONE);
        }

        /** The left shade takes the artwork's own colour, deepened, so each card has its mood. */
        private void shade(Slide s) {
            // an app card is already its own colour; a show's artwork gives up its average
            if (s.tone == 0) s.tone = s.mark != null ? s.color : tone(s.art, s.color);
            int deep = Ui.mix(s.tone, 0xFF000000, 0.62f);
            tint.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{
                    Ui.withAlpha(deep, 0xF0), Ui.withAlpha(deep, 0xA6), Ui.withAlpha(deep, 0x1F)}));
        }

        /** The artwork's average colour, nudged towards something with a little life in it. */
        private static int tone(Bitmap b, int fallback) {
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

        /** An app's card: its colour as a deep gradient, its white mark on the right. */
        private static Bitmap markArt(Slide s, int w, int h) {
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            Paint p = new Paint();
            p.setShader(new LinearGradient(0, 0, w, h, Ui.mix(s.color, 0xFF000000, 0.72f), Ui.mix(s.color, 0xFF000000, 0.35f),
                    Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
            if (s.mark != null) {
                int mw = Math.round(w * 0.42f), mh = Math.round(mw * 9 / 16f);
                c.save();
                c.translate(w - mw - w * 0.09f, (h - mh) / 2f);
                s.mark.paint(c, mw, mh);
                c.restore();
            }
            return b;
        }
    }

    /**
     * The page indicator on the right edge, as on iOS: small dots in a column, the current card a
     * longer pill that glides to its place. It brightens while the stack is focused.
     */
    private static final class Dots extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private int count;
        private float pos;
        private boolean lit;
        private ValueAnimator glide;

        Dots(Context c) {
            super(c);
        }

        void setCount(int n) {
            count = n;
            setVisibility(n > 1 ? VISIBLE : GONE);
            invalidate();
        }

        void setLit(boolean on) {
            if (lit == on) return;
            lit = on;
            invalidate();
        }

        void moveTo(int i, boolean animate) {
            if (glide != null) glide.cancel();
            if (!animate || count < 2) {
                pos = i;
                invalidate();
                return;
            }
            // the pill always glides forward, wrapping from the last card to the first
            float from = pos, to = i;
            if (to < from) from = -1;
            glide = ValueAnimator.ofFloat(from, to);
            glide.setDuration(560);
            glide.setInterpolator(Ui.EMPHASIZED);
            glide.addUpdateListener(a -> {
                pos = (float) a.getAnimatedValue();
                invalidate();
            });
            glide.start();
        }

        @Override
        protected void onDraw(Canvas c) {
            if (count < 2) return;
            float w = getWidth(), dot = Ui.dpf(4.5f), gap = Ui.dpf(6), pill = Ui.dpf(14);
            float total = (count - 1) * (dot + gap) + pill;
            float y = (getHeight() - total) / 2f, cx = w / 2f;
            for (int i = 0; i < count; i++) {
                // how much of the pill sits on this dot right now, 0 to 1
                float k = Math.max(0f, 1f - Math.abs(pos - i));
                float h = dot + (pill - dot) * k;
                p.setColor(Ui.withAlpha(0xFFFFFFFF, Math.round((lit ? 110 : 80) + (lit ? 145 : 150) * k)));
                r.set(cx - dot / 2f, y, cx + dot / 2f, y + h);
                c.drawRoundRect(r, dot / 2f, dot / 2f, p);
                y += h + gap;
            }
        }
    }
}
