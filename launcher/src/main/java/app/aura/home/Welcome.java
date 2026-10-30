package app.aura.home;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The first time Aura opens on a TV: a short welcome on frosted glass over the photograph. A look
 * for the tiles, shown with your own apps; then the features, each with a small live preview, on
 * or off; then three things worth knowing. Every choice applies at once and can be changed later
 * in Settings. Built only when shown, and let go of when done.
 */
final class Welcome extends FrameLayout {
    interface Host {
        /** A few installed apps, for showing the tile looks with real art. */
        List<AppRepo.App> sample();

        void applied(String what);

        void done();

        /** This TV can't be asked: say how to choose Aura by hand. */
        void homeHint();
    }

    /** Pref key, label, what it does, preview, what to refresh. */
    private static final String[][] FEATURES = {
            {Prefs.TOP_SHELF, "Top Shelf", "Rest on an app and its shows line up above it, ready to play.", "shelf", "shelf"},
            {Prefs.PARALLAX, "Parallax icons", "Tiles tilt toward you and catch the light as you move between them.", "parallax", "parallax"},
            {Prefs.STACK_AUTO, "Smart Stack", "Continue watching, new episodes and tonight's pick, turning on their own.", "stack", "stack"},
            {Prefs.WEATHER, "Weather", "The temperature and the sky in the corner, and the week ahead in Control Center.", "weather", "weather"},
            {Prefs.DAILY, "Picture of the Day", "A new photograph from Wikimedia Commons on your wall every morning.", "daily", "daily"},
            {Prefs.PICKS, "Tonight's pick", "A film or show worth your evening, chosen fresh each day.", "picks", "picks"},
            {Prefs.FLYOVERS, "Screensaver flyovers", "Earth from the space station while the TV rests, a new clip each day, over Wi-Fi.", "flyovers", "flyovers"},
    };
    private static final String[] LOOK_LINES = {"Frosted, over your photo", "Bold colour", "Dark and quiet", "Each app's own banner"};

    private final Activity act;
    private final Backdrop backdrop;
    private final Host host;
    private final FrameLayout stage;
    private final LinearLayout dots;
    private final List<String[]> features = new ArrayList<>();
    private View pageView;
    private int page = -1;

    // the page's focusable things, moved through by hand
    private final List<View> targets = new ArrayList<>();
    private final List<Runnable> actions = new ArrayList<>();
    private int at;
    private boolean horizontal;
    private Preview preview;
    private TextView about;

    Welcome(Activity a, Backdrop b, Host h) {
        super(a);
        act = a;
        backdrop = b;
        host = h;
        for (String[] f : FEATURES) if (!Prefs.FLYOVERS.equals(f[0]) || Flyovers.supported()) features.add(f);
        GlassFrame veil = new GlassFrame(a, b, 0, 0.62f);
        veil.edgeLight = false;
        addView(veil, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        stage = new FrameLayout(a);
        stage.setClipChildren(false);
        addView(stage, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        dots = new LinearLayout(a);
        dots.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < 4; i++) {
            View d = new View(a);
            d.setBackground(pill(0xFFFFFFFF, 3));
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(Ui.dp(6), Ui.dp(6));
            dl.leftMargin = dl.rightMargin = Ui.dp(4);
            dots.addView(d, dl);
        }
        LayoutParams dl = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        dl.bottomMargin = Ui.dp(34);
        addView(dots, dl);
    }

    /** Only on a fresh install, never after an update: the first install and the last one are the same moment. */
    static boolean due(Context c) {
        if (Prefs.device(c).getBoolean("welcomed", false)) return false;
        try {
            PackageInfo p = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            if (p.firstInstallTime == p.lastUpdateTime) return true;
        } catch (Exception ignored) {
        }
        Prefs.device(c).edit().putBoolean("welcomed", true).apply();
        return false;
    }

    boolean isOpen() {
        return getVisibility() == VISIBLE && page >= 0;
    }

    void open() {
        setVisibility(VISIBLE);
        setAlpha(0f);
        animate().alpha(1f).setDuration(700).setStartDelay(0).setInterpolator(Ui.SOFT).start();
        show(0, 0);
    }

    private void finish() {
        Prefs.device(act).edit().putBoolean("welcomed", true).apply();
        page = -1;
        if (preview != null) preview.stop();
        animate().alpha(0f).setDuration(420).setStartDelay(0).setInterpolator(Ui.SOFT).withEndAction(() -> {
            setVisibility(GONE);
            stage.removeAllViews();
            host.done();
        }).start();
    }

    // ---- keys --------------------------------------------------------------------------------

    /** Everything while it's open: arrows move, OK acts, Back goes a step back. */
    boolean key(int code) {
        if (page < 0) return true;
        if (code == KeyEvent.KEYCODE_BACK) {
            if (page > 0) show(page - 1, -1);
            return true;
        }
        if (Pad.isConfirm(code)) {
            if (at < actions.size()) actions.get(at).run();
            return true;
        }
        int step = 0;
        if (horizontal) {
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) step = -1;
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT) step = 1;
        } else {
            if (code == KeyEvent.KEYCODE_DPAD_UP) step = -1;
            if (code == KeyEvent.KEYCODE_DPAD_DOWN) step = 1;
        }
        if (step != 0) focus(Math.max(0, Math.min(targets.size() - 1, at + step)));
        return true;
    }

    private void focus(int i) {
        if (targets.isEmpty()) return;
        at = i;
        targets.get(i).requestFocus();
        if (page == 2 && i < features.size()) {
            String[] f = features.get(i);
            about.setText(f[2]);
            preview.show(f[3]);
        }
    }

    // ---- pages -------------------------------------------------------------------------------

    /** Slides to page {@code p}: forward from the right, back from the left. */
    private void show(int p, int dir) {
        View old = pageView;
        if (old != null) {
            old.animate().alpha(0f).translationX(-dir * Ui.dp(48)).setDuration(260).setStartDelay(0).setInterpolator(Ui.SOFT)
                    .withEndAction(() -> stage.removeView(old)).start();
        }
        if (preview != null) preview.stop();
        page = p;
        targets.clear();
        actions.clear();
        preview = null;
        View v = p == 0 ? hello() : p == 1 ? look() : p == 2 ? choices() : ready();
        pageView = v;
        stage.addView(v, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        v.setAlpha(0f);
        v.setTranslationX(dir * Ui.dp(64));
        v.animate().alpha(1f).translationX(0f).setDuration(620).setStartDelay(old != null ? 140 : 300).setInterpolator(Ui.EMPHASIZED).start();
        for (int i = 0; i < dots.getChildCount(); i++) {
            View d = dots.getChildAt(i);
            LinearLayout.LayoutParams dl = (LinearLayout.LayoutParams) d.getLayoutParams();
            dl.width = Ui.dp(i == p ? 20 : 6);
            d.setLayoutParams(dl);
            d.setAlpha(i == p ? 0.95f : 0.35f);
        }
        int start = p == 1 ? Math.max(0, java.util.Arrays.asList(Prefs.TILE_STYLES).indexOf(Prefs.tileStyle(act))) : 0;
        v.post(() -> focus(Math.min(start, targets.size() - 1)));
    }

    private View hello() {
        LinearLayout col = column(Gravity.CENTER_HORIZONTAL);
        TextView kicker = Ui.caps(act, 8, 0xB3FFFFFF, 0.4f);
        kicker.setText("AURA");
        col.addView(kicker, wrap(0));
        TextView title = Ui.text(act, 64, Ui.heavy, 0xFFFFFFFF);
        title.setLetterSpacing(-0.045f);
        title.setText("Welcome home");
        col.addView(title, wrap(6));
        TextView body = Ui.text(act, 16, Ui.regular, 0xCCFFFFFF);
        body.setSingleLine(false);
        body.setGravity(Gravity.CENTER_HORIZONTAL);
        body.setMaxWidth(Ui.dp(520));
        body.setLineSpacing(Ui.dpf(4), 1f);
        body.setText("A calm, quick home for your TV. Two choices and you're in, and everything can be changed later in Settings.");
        col.addView(body, wrap(14));
        LinearLayout buttons = new LinearLayout(act);
        buttons.addView(button("Get started", true, () -> show(1, 1)), wrap(0));
        LinearLayout.LayoutParams sl = wrap(0);
        sl.leftMargin = Ui.dp(14);
        buttons.addView(button("Keep the defaults", false, this::finish), sl);
        col.addView(buttons, wrap(40));
        horizontal = true;
        return centred(col);
    }

    private View look() {
        LinearLayout col = column(Gravity.CENTER_HORIZONTAL);
        col.addView(kicker("STEP 1 OF 2"), wrap(0));
        col.addView(title("Choose a look"), wrap(6));
        col.addView(line("How your apps sit on the glass. OK picks one."), wrap(8));
        LinearLayout row = new LinearLayout(act);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        List<AppRepo.App> apps = host.sample();
        AppRepo.App app = apps.isEmpty() ? null : apps.get(0);
        String current = Prefs.tileStyle(act);
        for (int i = 0; i < Prefs.TILE_STYLES.length; i++) {
            String style = Prefs.TILE_STYLES[i];
            LinearLayout item = column(Gravity.CENTER_HORIZONTAL);
            item.setClipChildren(false);
            View tile;
            if (app != null) {
                Tiles.Painter art = style.equals("glass") ? Tiles.glass(app) : style.equals("vivid") ? Tiles.vivid(app)
                        : style.equals("midnight") ? Tiles.midnight(app) : Tiles.app(app);
                Card card = new Card(act, art, app.color, 14);
                if (style.equals("glass")) card.glass = backdrop;
                tile = card;
            } else {
                tile = new View(act);
                tile.setFocusable(true);
                tile.setBackground(pill(0x33FFFFFF, 14));
            }
            item.addView(tile, new LinearLayout.LayoutParams(Ui.dp(176), Ui.dp(99)));
            TextView name = Ui.text(act, 15, Ui.semibold, 0xFFFFFFFF);
            name.setText((style.equals(current) ? "✓  " : "") + Prefs.TILE_NAMES[i].replace(" banners", ""));
            item.addView(name, wrap(20));
            TextView sub = Ui.text(act, 11.5f, Ui.regular, 0x99FFFFFF);
            sub.setText(LOOK_LINES[i]);
            item.addView(sub, wrap(3));
            LinearLayout.LayoutParams il = wrap(0);
            il.leftMargin = il.rightMargin = Ui.dp(12);
            row.addView(item, il);
            targets.add(tile);
            actions.add(() -> {
                Prefs.of(act).edit().putString(Prefs.TILE_STYLE, style).apply();
                host.applied("tiles");
                show(2, 1);
            });
        }
        col.addView(row, wrap(40));
        horizontal = true;
        return centred(col);
    }

    private View choices() {
        FrameLayout f = new FrameLayout(act);
        LinearLayout left = column(Gravity.START);
        left.addView(kicker("STEP 2 OF 2"), wrap(0));
        left.addView(title("Make it yours"), wrap(6));
        FrameLayout box = new FrameLayout(act);
        box.setBackground(pill(0x26000000, 18));
        box.setClipToOutline(true);
        preview = new Preview(act);
        box.addView(preview, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(Ui.dp(360), Ui.dp(203));
        bl.topMargin = Ui.dp(22);
        left.addView(box, bl);
        about = Ui.text(act, 14, Ui.regular, 0xCCFFFFFF);
        about.setSingleLine(false);
        about.setMaxLines(3);
        about.setMaxWidth(Ui.dp(360));
        about.setLineSpacing(Ui.dpf(3), 1f);
        about.setMinLines(2);
        left.addView(about, wrap(16));
        LayoutParams ll = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START | Gravity.CENTER_VERTICAL);
        ll.leftMargin = Ui.dp(96);
        f.addView(left, ll);

        LinearLayout list = column(Gravity.START);
        for (String[] feature : features) {
            Row r = new Row(act, feature[1]);
            r.set(Prefs.on(act, feature[0]));
            list.addView(r, rowLp());
            targets.add(r);
            actions.add(() -> {
                boolean on = !Prefs.on(act, feature[0]);
                Prefs.set(act, feature[0], on);
                r.set(on);
                if (Prefs.FLYOVERS.equals(feature[0])) {
                    if (on) Flyovers.schedule(act);
                    else Flyovers.clear(act);
                }
                host.applied(feature[4]);
                preview.show(feature[3]);
            });
        }
        Row next = new Row(act, "Continue");
        next.arrow();
        LinearLayout.LayoutParams nl = rowLp();
        nl.topMargin = Ui.dp(14);
        list.addView(next, nl);
        targets.add(next);
        actions.add(() -> show(3, 1));
        LayoutParams rl = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.CENTER_VERTICAL);
        rl.rightMargin = Ui.dp(96);
        f.addView(list, rl);
        horizontal = false;
        return f;
    }

    private View ready() {
        LinearLayout col = column(Gravity.CENTER_HORIZONTAL);
        col.addView(title("You're all set"), wrap(0));
        col.addView(line("Three things worth knowing."), wrap(8));
        LinearLayout tips = column(Gravity.START);
        tips.addView(tip("HOME  ×2", "Your recent apps, side by side"), wrap(0));
        tips.addView(tip("HOLD  OK", "Move an app, hide it, or make a folder"), wrap(14));
        tips.addView(tip("MENU", "Control Center: Wi-Fi, sound, sleep and more"), wrap(14));
        col.addView(tips, wrap(34));
        if (HomeRole.held(act)) {
            col.addView(button("Start watching", true, this::finish), wrap(40));
        } else {
            // the Home button still opens the old home screen: Android asks once, on the screen
            LinearLayout buttons = new LinearLayout(act);
            buttons.addView(button("Make Aura your home", true, () -> {
                finish();
                if (!HomeRole.ask(act)) host.homeHint();
            }), wrap(0));
            LinearLayout.LayoutParams sl = wrap(0);
            sl.leftMargin = Ui.dp(14);
            buttons.addView(button("Not now", false, this::finish), sl);
            col.addView(buttons, wrap(40));
        }
        horizontal = true;
        return centred(col);
    }

    // ---- pieces ------------------------------------------------------------------------------

    private View tip(String keys, String what) {
        LinearLayout r = new LinearLayout(act);
        r.setGravity(Gravity.CENTER_VERTICAL);
        TextView chip = Ui.caps(act, 7.5f, 0xFFFFFFFF, 0.24f);
        chip.setShadowLayer(0, 0, 0, 0);
        chip.setText(keys);
        chip.setGravity(Gravity.CENTER);
        chip.setBackground(pill(0x2EFFFFFF, 8));
        chip.setPadding(Ui.dp(10), Ui.dp(6), Ui.dp(10), Ui.dp(6));
        chip.setMinWidth(Ui.dp(92));
        r.addView(chip, wrap(0));
        TextView t = Ui.text(act, 15, Ui.medium, 0xE6FFFFFF);
        t.setText(what);
        LinearLayout.LayoutParams tl = wrap(0);
        tl.leftMargin = Ui.dp(16);
        r.addView(t, tl);
        return r;
    }

    private TextView button(String text, boolean primary, Runnable run) {
        TextView b = Ui.text(act, 15, Ui.semibold, 0xFFFFFFFF);
        b.setText(text);
        b.setGravity(Gravity.CENTER);
        b.setPadding(Ui.dp(26), Ui.dp(12), Ui.dp(26), Ui.dp(12));
        b.setFocusable(true);
        GradientDrawable idle = pill(primary ? 0x40FFFFFF : 0x1FFFFFFF, 24), lit = pill(0xFFFFFFFF, 24);
        b.setBackground(idle);
        b.setOnFocusChangeListener((v, has) -> {
            b.setBackground(has ? lit : idle);
            b.setTextColor(has ? 0xFF111111 : 0xFFFFFFFF);
            b.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f).setDuration(260).setStartDelay(0).setInterpolator(Ui.EMPHASIZED).start();
        });
        targets.add(b);
        actions.add(run);
        return b;
    }

    private TextView kicker(String s) {
        TextView t = Ui.caps(act, 8, 0x99FFFFFF, 0.4f);
        t.setText(s);
        return t;
    }

    private TextView title(String s) {
        TextView t = Ui.text(act, 44, Ui.heavy, 0xFFFFFFFF);
        t.setLetterSpacing(-0.04f);
        t.setText(s);
        return t;
    }

    private TextView line(String s) {
        TextView t = Ui.text(act, 15, Ui.regular, 0xB3FFFFFF);
        t.setText(s);
        return t;
    }

    private LinearLayout column(int gravity) {
        LinearLayout l = new LinearLayout(act);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(gravity);
        l.setClipChildren(false);
        l.setClipToPadding(false);
        return l;
    }

    private View centred(View v) {
        FrameLayout f = new FrameLayout(act);
        f.setClipChildren(false);
        f.addView(v, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        return f;
    }

    private static LinearLayout.LayoutParams wrap(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = Ui.dp(top);
        return p;
    }

    private static LinearLayout.LayoutParams rowLp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(Ui.dp(330), Ui.dp(38));
        p.topMargin = Ui.dp(5);
        return p;
    }

    private static GradientDrawable pill(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(Ui.dpf(radiusDp));
        return g;
    }

    /** A feature's row: its name, and a switch that slides; white when focused, like Settings. */
    private static final class Row extends FrameLayout {
        private final TextView label;
        private final View track, knob;
        private final GradientDrawable idle = pill(0x1AFFFFFF, 19), lit = pill(0xFFFFFFFF, 19);
        private boolean on, chevron;

        Row(Context c, String text) {
            super(c);
            setFocusable(true);
            setBackground(idle);
            label = Ui.text(c, 15, Ui.semibold, 0xFFFFFFFF);
            label.setText(text);
            LayoutParams ll = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START | Gravity.CENTER_VERTICAL);
            ll.leftMargin = Ui.dp(18);
            addView(label, ll);
            track = new View(c);
            LayoutParams tl = new LayoutParams(Ui.dp(34), Ui.dp(20), Gravity.END | Gravity.CENTER_VERTICAL);
            tl.rightMargin = Ui.dp(14);
            addView(track, tl);
            knob = new View(c);
            knob.setBackground(pill(0xFFFFFFFF, 8));
            knob.setElevation(Ui.dpf(1));
            LayoutParams kl = new LayoutParams(Ui.dp(16), Ui.dp(16), Gravity.END | Gravity.CENTER_VERTICAL);
            kl.rightMargin = Ui.dp(16);
            addView(knob, kl);
            setOnFocusChangeListener((v, has) -> paint(has));
        }

        void arrow() {
            chevron = true;
            removeView(knob);
            ((LayoutParams) track.getLayoutParams()).width = Ui.dp(9);
            ((LayoutParams) track.getLayoutParams()).height = Ui.dp(14);
            paint(false);
        }

        void set(boolean v) {
            on = v;
            knob.animate().translationX(on ? 0 : -Ui.dp(14)).setDuration(220).setStartDelay(0).setInterpolator(Ui.EMPHASIZED).start();
            paint(hasFocus());
        }

        private void paint(boolean focused) {
            setBackground(focused ? lit : idle);
            label.setTextColor(focused ? 0xFF111111 : 0xFFFFFFFF);
            if (chevron) {
                android.graphics.drawable.Drawable d = getContext().getDrawable(R.drawable.ic_chevron).mutate();
                d.setTint(focused ? 0xFF111111 : 0xFFFFFFFF);
                track.setBackground(d);
                return;
            }
            // green when on; off, a grey that reads on the white focused row and on the glass alike
            track.setBackground(pill(on ? 0xFF34C759 : focused ? 0x33000000 : 0x40FFFFFF, 10));
        }
    }

    /**
     * A small moving picture of each feature, drawn rather than loaded: shapes and gradients, so it
     * costs nothing to keep. It moves once each time its row is reached or switched, then rests.
     */
    private static final class Preview extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final Path path = new Path();
        private final Random rnd = new Random(7);
        private final float[] stars = new float[60];
        private final android.graphics.Camera cam = new android.graphics.Camera();
        private final android.graphics.Matrix matrix = new android.graphics.Matrix();
        private final android.graphics.Matrix sm = new android.graphics.Matrix();
        /** Gradients are made once and moved with a matrix: a fresh large one each frame costs this GPU ~15 ms. */
        private final java.util.HashMap<String, Shader> shaders = new java.util.HashMap<>();
        private final float[] cities = new float[120];
        private String kind = "", weatherText = "";
        private float t;
        private ValueAnimator anim;

        Preview(Context c) {
            super(c);
            for (int i = 0; i < stars.length; i++) stars[i] = rnd.nextFloat();
            for (int i = 0; i < cities.length; i++) cities[i] = rnd.nextFloat();
        }

        private Shader linear(int c1, int c2, boolean vertical) {
            String k = "l" + c1 + ":" + c2 + vertical;
            Shader g = shaders.get(k);
            if (g == null) shaders.put(k, g = new LinearGradient(0, 0, vertical ? 0 : 1, 1, c1, c2, Shader.TileMode.CLAMP));
            return g;
        }

        private Shader glow(int color) {
            String k = "r" + color;
            Shader g = shaders.get(k);
            if (g == null) shaders.put(k, g = new RadialGradient(0, 0, 1, color, color & 0x00FFFFFF, Shader.TileMode.CLAMP));
            return g;
        }

        /** {@code g} stretched over the box, at {@code alpha}. */
        private void paint(Canvas c, Shader g, float l, float tp, float rt, float b, float rad, int alpha) {
            sm.setScale(Math.max(1, rt - l), Math.max(1, b - tp));
            sm.postTranslate(l, tp);
            g.setLocalMatrix(sm);
            p.setShader(g);
            p.setColor(0xFF000000);
            p.setAlpha(alpha);
            r.set(l, tp, rt, b);
            if (rad > 0) c.drawRoundRect(r, rad, rad, p);
            else c.drawRect(r, p);
            p.setShader(null);
            p.setAlpha(255);
        }

        private void spot(Canvas c, int color, float cx, float cy, float radius) {
            Shader g = glow(color);
            sm.setScale(radius, radius);
            sm.postTranslate(cx, cy);
            g.setLocalMatrix(sm);
            p.setShader(g);
            c.drawCircle(cx, cy, radius, p);
            p.setShader(null);
        }

        void show(String k) {
            if (!k.equals(kind)) {
                kind = k;
                fresh(k);
                setAlpha(0f);
                animate().alpha(1f).setDuration(320).setStartDelay(0).setInterpolator(Ui.SOFT).start();
            }
            // one easing pass, then still: under the full-screen frost every frame costs this GPU ~18 ms
            if (anim == null) {
                anim = ValueAnimator.ofFloat(0f, 1f);
                anim.setDuration(3600);
                anim.setInterpolator(Ui.SOFT);
                anim.addUpdateListener(a -> {
                    t = (float) a.getAnimatedValue();
                    invalidate();
                });
            }
            anim.cancel();
            anim.start();
        }

        private void fresh(String k) {
            if (k.equals("weather")) {
                Weather.Now now = Weather.now(getContext());
                weatherText = (now != null ? now.temp : 24) + "\u00B0   "
                        + android.text.format.DateFormat.getTimeFormat(getContext()).format(new java.util.Date());
            }
        }

        void stop() {
            if (anim != null) anim.cancel();
        }

        @Override
        protected void onDetachedFromWindow() {
            stop();
            super.onDetachedFromWindow();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            if (w == 0) return;
            boolean on = true;
            switch (kind) {
                case "shelf":
                    shelf(c, w, h);
                    break;
                case "parallax":
                    parallax(c, w, h);
                    break;
                case "stack":
                    stack(c, w, h);
                    break;
                case "weather":
                    weather(c, w, h);
                    break;
                case "daily":
                    daily(c, w, h);
                    break;
                case "picks":
                    picks(c, w, h);
                    break;
                case "flyovers":
                    flyovers(c, w, h);
                    break;
                default:
                    on = false;
            }
            if (!on) c.drawColor(0);
        }

        private float dp(float v) {
            return Ui.dpf(v);
        }

        private void round(Canvas c, float l, float tp, float rt, float b, float rad, int color) {
            p.setShader(null);
            p.setColor(color);
            r.set(l, tp, rt, b);
            c.drawRoundRect(r, rad, rad, p);
        }

        private void grad(Canvas c, float l, float tp, float rt, float b, float rad, int c1, int c2) {
            paint(c, linear(c1, c2, false), l, tp, rt, b, rad, 255);
        }

        /** Shows above a row of tiles, the first tile lit; the shelf breathes in and out. */
        private void shelf(Canvas c, float w, float h) {
            float pad = dp(16), gap = dp(8), cw = (w - pad * 2 - gap * 2) / 3f;
            float rise = (float) Math.sin(t * Math.PI * 2) * dp(2);
            int[][] posters = {{0xFF6A3093, 0xFFA044FF}, {0xFFE65C00, 0xFFF9D423}, {0xFF1D976C, 0xFF93F9B9}};
            for (int i = 0; i < 3; i++) {
                float l = pad + i * (cw + gap);
                grad(c, l, pad + rise, l + cw, pad + cw * 0.88f + rise, dp(8), posters[i][0], posters[i][1]);
                round(c, l + dp(8), pad + cw * 0.88f - dp(14) + rise, l + cw * 0.6f, pad + cw * 0.88f - dp(10) + rise, dp(2), 0xE6FFFFFF);
            }
            float tw = (w - pad * 2 - gap * 3) / 4f, top = h - pad - tw * 0.56f;
            int[] tiles = {0xFFE50914, 0xFF1E88E5, 0xFF7E57C2, 0xFF26A69A};
            for (int i = 0; i < 4; i++) {
                float l = pad + i * (tw + gap);
                round(c, l, top, l + tw, top + tw * 0.56f, dp(7), Ui.alpha(tiles[i], i == 0 ? 1f : 0.55f));
            }
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(2));
            round(c, pad - dp(2), top - dp(2), pad + tw + dp(2), top + tw * 0.56f + dp(2), dp(9), 0xFFFFFFFF);
            p.setStyle(Paint.Style.FILL);
        }

        /** One tile turning toward you, its light sliding across. */
        private void parallax(Canvas c, float w, float h) {
            float a = (float) Math.sin(t * Math.PI * 2);
            float tw = w * 0.5f, th = tw * 0.56f, cx = w / 2f, cy = h / 2f;
            c.save();
            android.graphics.Matrix m = matrix;
            cam.save();
            cam.rotateY(a * 12f);
            cam.rotateX(-a * 4f);
            cam.getMatrix(m);
            cam.restore();
            m.preTranslate(-cx, -cy);
            m.postTranslate(cx, cy);
            c.concat(m);
            grad(c, cx - tw / 2, cy - th / 2, cx + tw / 2, cy + th / 2, dp(12), 0xFF2B5876, 0xFF4E4376);
            // the art floats a layer above the glass and moves a little more
            c.translate(a * dp(6), 0);
            p.setColor(0xFFFFFFFF);
            c.drawCircle(cx, cy, th * 0.22f, p);
            c.translate(-a * dp(6), 0);
            c.save();
            r.set(cx - tw / 2, cy - th / 2, cx + tw / 2, cy + th / 2);
            path.reset();
            path.addRoundRect(r, dp(12), dp(12), Path.Direction.CW);
            c.clipPath(path);
            spot(c, 0x55FFFFFF, cx + a * tw * 0.4f, cy - th * 0.3f, tw * 0.6f);
            c.restore();
            c.restore();
        }

        /** Three cards stacked; the front one slides away to show the next. */
        private void stack(Canvas c, float w, float h) {
            float cw = w * 0.62f, ch = h * 0.56f, cx = w / 2f, cy = h / 2f + dp(6);
            int[][] cards = {{0xFF0F2027, 0xFF2C5364}, {0xFF8E2DE2, 0xFF4A00E0}, {0xFFF7971E, 0xFFFFD200}};
            float slide = Math.max(0f, (t % 0.5f) * 2f - 0.6f) / 0.4f;
            int front = (int) (t * 2) % 3;
            for (int k = 2; k >= 0; k--) {
                int i = (front + k) % 3;
                float off = k * dp(8) - (k == 0 ? 0 : slide * dp(8));
                float y = cy - off - (k == 0 ? slide * ch * 1.2f : 0);
                float shrink = k * dp(10);
                paint(c, linear(cards[i][0], cards[i][1], false), cx - cw / 2 + shrink, y - ch / 2, cx + cw / 2 - shrink, y + ch / 2,
                        dp(12), k == 0 ? (int) (255 * (1f - slide)) : 255);
            }
            for (int i = 0; i < 3; i++) round(c, w - dp(18), cy - dp(10) + i * dp(9), w - dp(14), cy - dp(5) + i * dp(9), dp(2),
                    i == front ? 0xFFFFFFFF : 0x66FFFFFF);
        }

        /** The corner pill with tonight's temperature, and a sun that rises and sets behind it. */
        private void weather(Canvas c, float w, float h) {
            paint(c, linear(0xFF1C3B6E, 0xFF5B86C4, true), 0, 0, w, h, 0, 255);
            float sx = w * (0.2f + 0.45f * t), sy = h * 0.9f - (float) Math.sin(t * Math.PI / 2) * h * 0.55f;
            spot(c, 0xFFFFE08A, sx, sy, dp(40));
            round(c, w - dp(150), dp(16), w - dp(16), dp(46), dp(15), 0x40FFFFFF);
            p.setColor(0xFFFFFFFF);
            p.setTypeface(Ui.semibold);
            p.setTextSize(dp(14));
            c.drawText(weatherText, w - dp(136), dp(36), p);
        }

        /** Hills under a morning sky, the photograph's caption beneath. */
        private void daily(Canvas c, float w, float h) {
            paint(c, linear(0xFFFFB88C, 0xFFDE6262, true), 0, 0, w, h, 0, 255);
            float drift = t * dp(30);
            hills(c, w, h, h * 0.55f, 0xFF7B3F61, drift * 0.5f);
            hills(c, w, h, h * 0.7f, 0xFF3E2047, drift);
            round(c, dp(18), h - dp(30), dp(150), h - dp(24), dp(3), 0xE6FFFFFF);
            round(c, dp(18), h - dp(20), dp(100), h - dp(16), dp(2), 0x99FFFFFF);
        }

        private void hills(Canvas c, float w, float h, float base, int color, float shift) {
            path.reset();
            path.moveTo(0, h);
            for (float x = 0; ; x = Math.min(w, x + dp(8))) {
                path.lineTo(x, base - (float) (Math.sin((x + shift) / dp(60)) * dp(14) + Math.sin((x + shift) / dp(23)) * dp(5)));
                if (x >= w) break;
            }
            path.lineTo(w, h);
            path.close();
            p.setColor(color);
            c.drawPath(path, p);
        }

        /** A poster with a slow sheen, rated and ready. */
        private void picks(Canvas c, float w, float h) {
            float ph = h - dp(32), pw = ph * 0.68f, l = dp(24), tp = dp(16);
            grad(c, l, tp, l + pw, tp + ph, dp(10), 0xFF41295A, 0xFF2F0743);
            float sx = l + (t * 2f - 0.5f) * pw;
            c.save();
            r.set(l, tp, l + pw, tp + ph);
            path.reset();
            path.addRoundRect(r, dp(10), dp(10), Path.Direction.CW);
            c.clipPath(path);
            paint(c, linear(0x00FFFFFF, 0x33FFFFFF, false), sx - dp(30), tp, sx, tp + ph, 0, 255);
            paint(c, linear(0x33FFFFFF, 0x00FFFFFF, false), sx, tp, sx + dp(30), tp + ph, 0, 255);
            c.restore();
            float x = l + pw + dp(20);
            round(c, x, tp + dp(30), x + dp(140), tp + dp(44), dp(4), 0xFFFFFFFF);
            round(c, x, tp + dp(54), x + dp(90), tp + dp(62), dp(3), 0x99FFFFFF);
            p.setColor(0xFFFFC857);
            p.setTypeface(Ui.semibold);
            p.setTextSize(dp(13));
            c.drawText("★ 8.2", x, tp + dp(86), p);
        }

        /** Earth's night side turning under the station: a lit rim, cities and stars. */
        private void flyovers(Canvas c, float w, float h) {
            c.drawColor(0xFF02040A);
            p.setColor(0xFFFFFFFF);
            for (int i = 0; i < stars.length; i += 2) {
                p.setAlpha((int) (80 + 120 * stars[i + 1]));
                c.drawCircle(stars[i] * w, stars[i + 1] * h * 0.5f, dp(0.8f), p);
            }
            p.setAlpha(255);
            float rad = w * 1.4f, cx = w / 2f, cy = h + rad - h * 0.38f;
            String key = "earth" + (int) w;
            Shader limb = shaders.get(key);
            if (limb == null) {
                shaders.put(key, limb = new RadialGradient(cx, cy, rad + dp(14), new int[]{0xFF0B1426, 0xFF0B1426, 0xFF3A7BD5, 0x003A7BD5},
                        new float[]{0f, (rad - dp(4)) / (rad + dp(14)), rad / (rad + dp(14)), 1f}, Shader.TileMode.CLAMP));
            }
            p.setShader(limb);
            c.drawCircle(cx, cy, rad + dp(14), p);
            p.setShader(null);
            p.setColor(0xFFFFC46B);
            for (int i = 0; i < cities.length; i += 3) {
                float x = (cities[i] * 1.6f - 0.3f + t * 0.3f) % 1.3f * w;
                float y = h * 0.66f + cities[i + 1] * h * 0.32f;
                p.setAlpha((int) (120 + cities[i + 2] * 135));
                c.drawCircle(x, y, dp(0.6f + cities[i + 2]), p);
            }
            p.setAlpha(255);
        }
    }
}
