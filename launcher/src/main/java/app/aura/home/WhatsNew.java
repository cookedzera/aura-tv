package app.aura.home;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What's new, for someone who has just updated: one screen, once. The version at the left with two
 * buttons; at the right Watch as a wide card (a picture and three posters from its own shelf, when
 * it has been opened before) and four small cards, each a miniature of the thing it names. OK on
 * "Open Watch" goes straight there; "Continue" or Back puts it away, and it never comes again.
 *
 * A fresh install never sees it: the welcome is its introduction. Everything on it is drawn once
 * and then only moved; on LOW it's there whole on its first frame, HIGH brings it in piece by piece.
 */
final class WhatsNew extends FrameLayout {
    interface Host {
        Backdrop backdrop();

        /** "Open Watch" was chosen: put this away and open the Watch screen. */
        void openWatch();

        /** "Continue" was chosen. */
        void done();
    }

    /** The news on show: raised when a release has a screen like this of its own to show. */
    static final int EDITION = 78;
    /** The last edition this TV was shown (or was installed with), kept with the TV's own settings. */
    static final String SEEN = "news_seen";

    private static final int EMBER = Tiles.WATCH, SOFT = 0xBDFFFFFF;
    private static final int[] APP_HUES = {0xFFE5484D, 0xFF3B82F6, 0xFFF59E0B, 0xFF10B981, 0xFF8B5CF6, 0xFFEC4899},
            TOGGLE_HUES = {0xFFFFFFFF, 0xFF3B82F6, 0x29FFFFFF, 0x29FFFFFF}, ROW_HUES = {0xFF3B82F6, 0xFF8B5CF6, 0xFF10B981};
    private static final float[] ROW_BARS = {34.5f, 45f, 28.5f};

    /**
     * Whether this TV has news it hasn't been shown: Aura was updated (not just installed) since the
     * last edition it saw. A TV that has just been installed with this edition is marked as having
     * seen it. Not in a kids' profile, which waits for the grown-ups' own.
     */
    static boolean due(Context c) {
        SharedPreferences p = Prefs.device(c);
        if (p.getInt(SEEN, 0) >= EDITION) return false;
        if (Updater.justInstalled(c)) {
            seen(c);
            return false;
        }
        return !Profiles.kids(c);
    }

    static void seen(Context c) {
        Prefs.device(c).edit().putInt(SEEN, EDITION).apply();
    }

    private final Activity act;
    private final Pad open, go;
    private final WatchCard watch;
    /** Everything that arrives, in order, with how long after the screen each waits (ms). */
    private final List<View> parts = new ArrayList<>();
    private final List<Integer> waits = new ArrayList<>();
    private ValueAnimator fan;
    private boolean shown;

    WhatsNew(Activity a, Host h) {
        super(a);
        act = a;
        setVisibility(GONE);
        // one sheet of frosted glass over the whole screen: the photograph behind, deep enough for white type
        GlassFrame veil = new GlassFrame(a, h.backdrop(), 0, 0.66f);
        veil.edgeLight = false;
        addView(veil, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        boolean hasWatch = Prefs.watch(a);
        LinearLayout words = new LinearLayout(a);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setClipChildren(false);
        TextView kicker = Ui.caps(a, 9, 0x94FFFFFF, 0.34f);
        kicker.setText(a.getString(R.string.whats_new).toUpperCase(Locale.getDefault()));
        words.addView(part(kicker, 80));
        words.addView(part(title(a), 140), below(10));
        TextView line = Ui.text(a, 16, Ui.medium, SOFT);
        line.setText(a.getString(R.string.news_line));
        words.addView(part(line, 220), below(12));

        LinearLayout buttons = new LinearLayout(a);
        buttons.setClipChildren(false);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        open = button(h.backdrop(), a.getString(R.string.news_open_watch), true);
        go = button(h.backdrop(), a.getString(R.string.continue_word), false);
        open.onClick = h::openWatch;
        go.onClick = h::done;
        open.setId(View.generateViewId());
        go.setId(View.generateViewId());
        // the remote moves between the two and nowhere else
        for (Pad p : new Pad[]{open, go}) {
            p.setNextFocusUpId(p.getId());
            p.setNextFocusDownId(p.getId());
            p.setNextFocusLeftId(open.getId());
            p.setNextFocusRightId(go.getId());
        }
        if (hasWatch) {
            buttons.addView(open, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(36)));
        } else {
            // Watch switched off, or a profile without it: there's only going on
            go.setNextFocusLeftId(go.getId());
        }
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(36));
        gl.leftMargin = Ui.dp(hasWatch ? 12 : 0);
        buttons.addView(go, gl);
        words.addView(part(buttons, 320), below(30));
        addView(words, at(310, ViewGroup.LayoutParams.WRAP_CONTENT, 66, 150));

        TextView once = Ui.text(a, 9.5f, Ui.medium, 0x66FFFFFF);
        once.setText(a.getString(R.string.news_once));
        LayoutParams ol = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        ol.leftMargin = Ui.dp(66);
        ol.bottomMargin = Ui.dp(40);
        addView(part(once, 700), ol);

        watch = new WatchCard(a, a.getString(R.string.new_badge) + "  ·  " + a.getString(R.string.beta_caps), a.getString(R.string.watch), a.getString(R.string.news_watch_line));
        addView(part(watch, 160), at(510, 230, 390, 58));
        LinearLayout four = new LinearLayout(a);
        four.setClipChildren(false);
        String[][] said = {
                {a.getString(R.string.cat_all_apps), a.getString(R.string.news_all_apps_line)},
                {a.getString(R.string.control_center_2), a.getString(R.string.news_cc_line)},
                {a.getString(R.string.settings), a.getString(R.string.news_settings_line)},
                {a.getString(R.string.news_smoother), a.getString(R.string.news_smoother_line)}};
        for (int i = 0; i < said.length; i++) {
            LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            if (i > 0) ml.leftMargin = Ui.dp(12);
            four.addView(part(new MiniCard(a, i, said[i][0], said[i][1]), 420 + 80 * i), ml);
        }
        addView(four, at(510, 168, 390, 58 + 230 + 13));
        setClipChildren(false);
    }

    private View part(View v, int wait) {
        parts.add(v);
        waits.add(wait);
        return v;
    }

    private static LayoutParams at(int wDp, int hDp, float leftDp, float topDp) {
        LayoutParams l = new LayoutParams(wDp > 0 ? Ui.dp(wDp) : wDp, hDp > 0 ? Ui.dp(hDp) : hDp, Gravity.TOP | Gravity.START);
        l.leftMargin = Ui.dp(leftDp);
        l.topMargin = Ui.dp(topDp);
        return l;
    }

    private static LinearLayout.LayoutParams below(int topDp) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        l.topMargin = Ui.dp(topDp);
        return l;
    }

    /** "Aura 7.8": the name in white, the version in a light that warms to Watch's ember; shrunk until both fit their column. */
    private static View title(Context c) {
        String name = c.getString(R.string.app_name), version = Updater.installed(c);
        LinearLayout row = new LinearLayout(c);
        row.setClipChildren(false);
        row.setBaselineAligned(true);
        TextView n = Ui.text(c, 66, Ui.heavy, 0xFFFFFFFF), v = Ui.text(c, 66, Ui.heavy, 0xFFFFFFFF);
        float sp = 66, room = Ui.dpf(300);
        float gap = Ui.dpf(14), spare = Ui.dpf(10);
        for (; sp > 34; sp -= 2) {
            n.setTextSize(sp);
            v.setTextSize(sp);
            if (n.getPaint().measureText(name) + gap + v.getPaint().measureText(version) + spare <= room) break;
        }
        for (TextView t : new TextView[]{n, v}) t.setLetterSpacing(-0.04f);
        n.setText(name);
        v.setText(version);
        float w = v.getPaint().measureText(version);
        v.getPaint().setShader(new LinearGradient(0, 0, w, 0, new int[]{0xFFFFFFFF, 0xFFFFD9C6, EMBER}, new float[]{0.1f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        // room past the last figure: tight heavy type reaches beyond its own advance, and a view cuts what does
        v.setPadding(0, 0, Math.round(spare), 0);
        row.addView(n);
        LinearLayout.LayoutParams vl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vl.leftMargin = Math.round(gap);
        row.addView(v, vl);
        return row;
    }

    private Pad button(Backdrop b, String label, boolean play) {
        Pad p = new Pad(act, b, 18, 0.26f);
        p.frost = 0.06f;
        // a pointer (an air mouse, a touch) leaves the window in touch mode: the screen's only two controls still take the focus
        p.setFocusableInTouchMode(true);
        LinearLayout r = new LinearLayout(act);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dp(20), 0, Ui.dp(20), 0);
        if (play) {
            ImageView g = p.tint(ControlCenter.icon(act, R.drawable.ic_play), 1f);
            LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(Ui.dp(11), Ui.dp(11));
            il.rightMargin = Ui.dp(8);
            r.addView(g, il);
        }
        TextView t = p.tint(Ui.text(act, 12.5f, Ui.semibold, 0xFFFFFFFF), 1f);
        t.setText(label);
        r.addView(t);
        p.addView(r, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return p;
    }

    boolean isOpen() {
        return shown;
    }

    /** Its fade goes onto the glass and each piece directly: no screen-sized offscreen pass. */
    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    /** Comes up with the focus on its first button; Watch's pictures are read behind the screen and take their place when they're in. */
    void open() {
        if (shown) return;
        shown = true;
        setVisibility(VISIBLE);
        animate().cancel();
        if (Ui.light) {
            // LOW: whole on its first frame, the posters already fanned
            setAlpha(1f);
            watch.fan(1f);
        } else {
            setAlpha(0f);
            animate().alpha(1f).setStartDelay(0).setDuration(220).setInterpolator(Ui.EASE_DECEL).start();
            for (int i = 0; i < parts.size(); i++) {
                View v = parts.get(i);
                v.setAlpha(0f);
                v.setTranslationY(Ui.dpf(20));
                if (v == watch) {
                    v.setScaleX(0.965f);
                    v.setScaleY(0.965f);
                }
                v.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setStartDelay(waits.get(i)).setDuration(760)
                        .setInterpolator(Ui.EASE_DECEL).start();
            }
            watch.fan(0f);
            fan = ValueAnimator.ofFloat(0f, 1f);
            fan.setStartDelay(520);
            fan.setDuration(1000);
            fan.setInterpolator(new DecelerateInterpolator(2f));
            fan.addUpdateListener(an -> watch.fan((Float) an.getAnimatedValue()));
            fan.start();
        }
        (open.getParent() != null ? open : go).requestFocus();
        String title = act.getString(R.string.whats_new);
        setContentDescription(title);
        announceForAccessibility(title);
        if (!Prefs.watch(act)) return;
        final Context app = act.getApplicationContext();
        final int posterW = Ui.dp(WatchCard.POSTER_W), wide = Ui.dp(510);
        new SafeThread(() -> {
            // what Watch last kept on this TV, and nothing asked of the network: a TV that never opened it shows the card bare
            final List<Bitmap> posters = new ArrayList<>();
            Bitmap back = null;
            for (Picks.Pick p : Watch.shelf(app, Watch.ANY)) {
                if (posters.size() < 3) {
                    Bitmap b = Continue.picture(p.poster, posterW);
                    if (b != null) posters.add(b);
                }
                if (back == null) {
                    File f = Watch.heroKept(app, p);
                    if (f != null) back = Continue.picture(f.getPath(), wide);
                }
                if (posters.size() == 3 && back != null) break;
            }
            final Bitmap wideOne = back;
            post(() -> {
                if (shown) watch.set(wideOne, posters);
            });
        }, "whats-new").start();
    }

    /** Goes, and {@code then} runs once it has (at once on LOW): home takes it out of the window there. */
    void close(Runnable then) {
        if (!shown) return;
        shown = false;
        if (fan != null) fan.cancel();
        for (View v : parts) v.animate().cancel();
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        animate().cancel();
        if (Ui.light) {
            setVisibility(GONE);
            then.run();
        } else {
            animate().alpha(0f).setStartDelay(0).setDuration(Ui.CLOSE).setInterpolator(Ui.EASE_STANDARD).withEndAction(() -> {
                setVisibility(GONE);
                then.run();
            }).start();
        }
    }

    /** A card's rounded shape, cut by the GPU with a soft edge. */
    private static void round(View v, float radiusDp) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), Ui.dpf(radiusDp));
                o.setAlpha(Ui.light ? 0f : 1f);
            }
        });
        v.setClipToOutline(true);
    }

    /** The hairline round a card, brightest along its top. */
    private static void rim(Canvas c, Paint p, RectF box, float radius) {
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1f, Ui.dpf(0.75f)));
        p.setColor(0x33FFFFFF);
        float i = p.getStrokeWidth() / 2f;
        c.drawRoundRect(box.left + i, box.top + i, box.right - i, box.bottom - i, radius, radius, p);
        p.setStyle(Paint.Style.FILL);
    }

    /**
     * Watch's card: its ember light out of the dark, a wide picture from its shelf behind (shaded
     * where the words stand), three of its posters fanned at the right, and "NEW", its name and a line.
     */
    private static final class WatchCard extends View {
        static final int POSTER_W = 99, POSTER_H = 142;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        private final TextPaint type = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF(), poster = new RectF();
        private final Matrix m = new Matrix();
        private final String tag, name, line;
        private Shader ground, glow, shadeAcross, shadeUp;
        private Bitmap back;
        private final List<Bitmap> posters = new ArrayList<>();
        private final List<BitmapShader> shaders = new ArrayList<>();
        private float fan = 1f;

        WatchCard(Context c, String tag, String name, String line) {
            super(c);
            this.tag = tag;
            this.name = name;
            this.line = line;
            round(this, 21);
            setElevation(Ui.light ? 0f : Ui.dpf(14));
        }

        void set(Bitmap wide, List<Bitmap> ps) {
            back = wide;
            posters.clear();
            shaders.clear();
            for (Bitmap b : ps) {
                posters.add(b);
                shaders.add(new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
            }
            invalidate();
        }

        /** How far the posters have fanned out: 0 stacked low, 1 spread. */
        void fan(float f) {
            fan = f;
            invalidate();
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            box.set(0, 0, w, h);
            ground = new LinearGradient(0, 0, w, h, 0xFF2B1410, 0xFF0B0A0E, Shader.TileMode.CLAMP);
            glow = new RadialGradient(w * 0.08f, 0, w * 0.55f, new int[]{Ui.withAlpha(EMBER, 0x8C), 0x00000000}, null, Shader.TileMode.CLAMP);
            shadeAcross = new LinearGradient(0, 0, w * 0.74f, 0, new int[]{0xF51A0B08, 0xC71A0B08, 0x141A0B08, 0x001A0B08},
                    new float[]{0f, 0.46f, 0.94f, 1f}, Shader.TileMode.CLAMP);
            shadeUp = new LinearGradient(0, h, 0, h * 0.45f, 0xB30C0605, 0x000C0605, Shader.TileMode.CLAMP);
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            p.setStyle(Paint.Style.FILL);
            p.setAlpha(255);
            p.setShader(ground);
            c.drawRect(box, p);
            if (back != null) {
                // the wide picture over the whole card, kept to its right: the words have the left
                float s = Math.max(w / (float) back.getWidth(), h / (float) back.getHeight());
                m.setScale(s, s);
                m.postTranslate(w - back.getWidth() * s, (h - back.getHeight() * s) * 0.3f);
                p.setShader(null);
                c.drawBitmap(back, m, p);
                p.setShader(shadeAcross);
                c.drawRect(box, p);
                p.setShader(shadeUp);
                c.drawRect(box, p);
            }
            p.setShader(glow);
            c.drawRect(box, p);
            p.setShader(null);

            float pw = Ui.dpf(POSTER_W), ph = Ui.dpf(POSTER_H), cx = w - Ui.dpf(141), top = Ui.dpf(45);
            if (posters.isEmpty()) {
                // nothing kept yet: Watch's own mark, large
                Tiles.playMark(c, p, cx, h / 2f, Ui.dpf(40), Ui.dpf(2.5f));
            } else {
                // the two behind lean away to either side, the third stands before them a touch larger
                int n = posters.size();
                for (int i = 0; i < n; i++) {
                    boolean front = i == n - 1;
                    float side = front ? 0f : i == 0 ? -1f : 1f;
                    float scale = (0.9f + 0.1f * fan) * (front ? 1f + 0.06f * fan : 1f);
                    c.save();
                    c.translate(side * Ui.dpf(13.5f) * fan, (1f - fan) * Ui.dpf(22) - (front ? Ui.dpf(6) * fan : 0f));
                    c.rotate(side * 13f * fan, cx, top + ph * 1.2f);
                    c.scale(scale, scale, cx, top + ph / 2f);
                    poster.set(cx - pw / 2f, top, cx + pw / 2f, top + ph);
                    float rad = Ui.dpf(9);
                    // its shadow, as three soft steps: a blurred one would be drawn again every frame of the fan
                    p.setShader(null);
                    p.setColor(0x24000000);
                    for (int k = 3; k >= 1; k--) {
                        float g = Ui.dpf(3f * k);
                        c.drawRoundRect(poster.left - g, poster.top - g + Ui.dpf(8), poster.right + g, poster.bottom + g + Ui.dpf(8), rad + g, rad + g, p);
                    }
                    Bitmap b = posters.get(i);
                    float s = Math.max(pw / b.getWidth(), ph / b.getHeight());
                    m.setScale(s, s);
                    m.postTranslate(poster.left + (pw - b.getWidth() * s) / 2f, poster.top + (ph - b.getHeight() * s) / 2f);
                    shaders.get(i).setLocalMatrix(m);
                    p.setColor(0xFFFFFFFF);
                    p.setShader(shaders.get(i));
                    c.drawRoundRect(poster, rad, rad, p);
                    p.setShader(null);
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(Math.max(1f, Ui.dpf(0.75f)));
                    p.setColor(0x33FFFFFF);
                    c.drawRoundRect(poster, rad, rad, p);
                    p.setStyle(Paint.Style.FILL);
                    c.restore();
                }
            }

            // the words, from the foot up: the line, the name, and NEW on its ember pill
            float left = Ui.dpf(25.5f), lineBase = h - Ui.dpf(28);
            type.setShader(null);
            type.setTypeface(Ui.medium);
            type.setTextSize(Ui.dpf(14.5f));
            type.setLetterSpacing(0f);
            type.setColor(0xCCFFFFFF);
            c.drawText(line, left, lineBase, type);
            type.setTypeface(Ui.heavy);
            type.setTextSize(Ui.dpf(40));
            type.setLetterSpacing(-0.04f);
            type.setColor(0xFFFFFFFF);
            float nameBase = lineBase - Ui.dpf(24);
            c.drawText(name, left, nameBase, type);
            type.setTypeface(Ui.heavy);
            type.setTextSize(Ui.dpf(8.5f));
            type.setLetterSpacing(0.2f);
            float tw = type.measureText(tag), th = Ui.dpf(16), tagTop = nameBase - Ui.dpf(40) - th;
            p.setColor(EMBER);
            c.drawRoundRect(left, tagTop, left + tw + Ui.dpf(15), tagTop + th, th / 2f, th / 2f, p);
            type.setColor(0xFF2A0F08);
            c.drawText(tag, left + Ui.dpf(8.5f), tagTop + th * 0.69f, type);
            rim(c, p, box, Ui.dpf(21));
        }
    }

    /** One of the four small cards: a miniature of what it names over its name and a few words. */
    private static final class MiniCard extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint type = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF(), r = new RectF();
        private final Path path = new Path();
        private final int kind;
        private final String name, line;
        private StaticLayout said;
        private Shader ease;

        MiniCard(Context c, int kind, String name, String line) {
            super(c);
            this.kind = kind;
            this.name = name;
            this.line = line;
            round(this, 18);
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            box.set(0, 0, w, h);
            type.setTypeface(Ui.medium);
            type.setTextSize(Ui.dpf(10.5f));
            type.setLetterSpacing(0f);
            type.setColor(0xA8FFFFFF);
            int room = Math.max(1, w - Ui.dp(13.5f) - Ui.dp(9));
            said = new StaticLayout(line, type, room, Layout.Alignment.ALIGN_NORMAL, 1.12f, 0f, false);
            ease = new LinearGradient(0, 0, w, 0, 0x5934D399, 0xFF34D399, Shader.TileMode.CLAMP);
        }

        private void box(Canvas c, float l, float t, float w, float h, float rad, int color) {
            p.setColor(color);
            r.set(l, t, l + w, t + h);
            c.drawRoundRect(r, rad, rad, p);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), d = Ui.dpf(1);
            p.setShader(null);
            p.setStyle(Paint.Style.FILL);
            // clear glass over the frosted screen: a wash of white is all it needs
            p.setColor(0x17FFFFFF);
            c.drawRect(box, p);
            if (kind == 0) {
                // All Apps: its tabs in their capsule, the one on show white, and six apps in two rows
                box(c, 13.5f * d, 15 * d, 63.5f * d, 11.5f * d, 6 * d, 0x1AFFFFFF);
                box(c, 15.75f * d, 17.25f * d, 21 * d, 7 * d, 3.5f * d, 0xFFFFFFFF);
                box(c, 39.75f * d, 17.25f * d, 16.5f * d, 7 * d, 3.5f * d, 0x47FFFFFF);
                box(c, 59.25f * d, 17.25f * d, 16.5f * d, 7 * d, 3.5f * d, 0x47FFFFFF);
                float gap = 5.25f * d, tw = (w - 27 * d - 2 * gap) / 3f;
                for (int i = 0; i < 6; i++) {
                    box(c, 13.5f * d + (i % 3) * (tw + gap), 37.5f * d + (i / 3) * (22.5f * d + gap), tw, 22.5f * d, 6 * d, APP_HUES[i]);
                }
            } else if (kind == 1) {
                // Control Center: four round toggles, two of them on, and the volume beside them
                for (int i = 0; i < 4; i++) {
                    p.setColor(TOGGLE_HUES[i]);
                    c.drawCircle(13.5f * d + 11.25f * d + (i % 2) * 29.25f * d, 19.5f * d + 11.25f * d + (i / 2) * 29.25f * d, 11.25f * d, p);
                }
                float sl = w - 13.5f * d - 27 * d, st = 16.5f * d, sh = 60 * d;
                box(c, sl, st, 27 * d, sh, 12 * d, 0x24FFFFFF);
                c.save();
                path.reset();
                path.addRoundRect(r, 12 * d, 12 * d, Path.Direction.CW);
                c.clipPath(path);
                p.setColor(0xFFFFFFFF);
                c.drawRect(sl, st + sh * 0.54f, sl + 27 * d, st + sh, p);
                c.restore();
            } else if (kind == 2) {
                // Settings: three rows on their card, the middle one in focus, white and a little wider
                float l = 12 * d, cw = w - 24 * d, rh = 21 * d, t = 16.5f * d;
                box(c, l, t, cw, rh * 3, 9 * d, 0x1AFFFFFF);
                for (int i = 0; i < 3; i++) {
                    float y = t + i * rh;
                    boolean lit = i == 1;
                    if (lit) box(c, l - 3.75f * d, y, cw + 7.5f * d, rh, 7.5f * d, 0xFFFFFFFF);
                    box(c, l + 6 * d, y + (rh - 10.5f * d) / 2f, 10.5f * d, 10.5f * d, 3 * d, ROW_HUES[i]);
                    box(c, l + 22.5f * d, y + (rh - 3.75f * d) / 2f, ROW_BARS[i] * d, 3.75f * d, 2 * d, lit ? Pad.DARK : 0x73FFFFFF);
                }
            } else {
                // Smoother: one easy line, and where it is now
                path.reset();
                float sx = w / (118.5f * d);
                path.moveTo(-3 * d * sx, 73.5f * d);
                path.cubicTo(22.5f * d * sx, 73.5f * d, 33 * d * sx, 33 * d, 58.5f * d * sx, 45 * d);
                path.cubicTo(84 * d * sx, 57 * d, 91.5f * d * sx, 69 * d, 121.5f * d * sx, 30 * d);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeCap(Paint.Cap.ROUND);
                p.setStrokeWidth(3 * d);
                // a shader is drawn at its paint's own strength: full, or the line all but vanished
                p.setColor(0xFFFFFFFF);
                p.setShader(ease);
                c.drawPath(path, p);
                p.setShader(null);
                p.setStyle(Paint.Style.FILL);
                p.setColor(0x4034D399);
                c.drawCircle(99 * d * sx, 48 * d, 9.75f * d, p);
                p.setColor(0xFFFFFFFF);
                c.drawCircle(99 * d * sx, 48 * d, 4.5f * d, p);
            }
            type.setTypeface(Ui.bold);
            type.setTextSize(Ui.dpf(13.5f));
            type.setLetterSpacing(-0.02f);
            type.setColor(0xFFFFFFFF);
            c.drawText(name, 13.5f * d, 122.5f * d, type);
            if (said != null) {
                type.setTypeface(Ui.medium);
                type.setTextSize(Ui.dpf(10.5f));
                type.setLetterSpacing(0f);
                type.setColor(0xA8FFFFFF);
                c.save();
                c.translate(13.5f * d, 129.5f * d);
                said.draw(c);
                c.restore();
            }
            rim(c, p, box, Ui.dpf(18));
        }
    }
}
