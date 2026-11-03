package app.aura.home;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Who's watching: everyone's profile as a softly lit circle in their own colour, on frosted glass
 * over the wall, the one in use ringed in ivory. The remote's profile button opens it, and so does
 * Settings, Profiles. Left and right move, OK switches (a kids' profile asks the parents' PIN to
 * leave), Back puts it away with nothing changed. Nothing moves while you look, beyond the circles
 * rising in as it opens and the one in focus lifting toward you.
 */
final class ProfilePicker extends FrameLayout {
    interface Host {
        /** Runs {@code then} once a grown-up has said so: at once unless a kids' profile is in use. */
        void guarded(Runnable then);

        /** {@code id} is now the profile in use: everything rebuilt for them. */
        void switched(Profiles.Profile p);

        /** Profiles' own page in Settings, to add, remove or set a PIN. */
        void manage();

        void close();
    }

    private static final int SIZE = 128;

    private final Activity act;
    private final Host host;
    private final LinearLayout body, row;
    private final TextView hint;
    private final List<View> faces = new ArrayList<>();
    private final List<Runnable> picks = new ArrayList<>();
    private int at;

    ProfilePicker(Activity a, Backdrop b, Host h) {
        super(a);
        act = a;
        host = h;
        setVisibility(GONE);
        setClipChildren(false);
        GlassFrame veil = new GlassFrame(a, b, 0, 0.62f);
        veil.edgeLight = false;
        addView(veil, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER_HORIZONTAL);
        body.setClipChildren(false);
        TextView kicker = Ui.caps(a, 8, 0x99FFFFFF, 0.4f);
        kicker.setText("AURA");
        body.addView(kicker);
        TextView title = Ui.text(a, 40, Ui.light, 0xFFFFFFFF);
        title.setLetterSpacing(-0.03f);
        title.setText(act.getString(R.string.whos_watching));
        body.addView(title, lp(0, 6));
        row = new LinearLayout(a);
        row.setGravity(Gravity.TOP);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        body.addView(row, lp(0, 44));
        hint = Ui.text(a, 13, Ui.medium, 0x80FFFFFF);
        body.addView(hint, lp(0, 40));
        addView(body, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
    }

    boolean isOpen() {
        return getVisibility() == VISIBLE;
    }

    void open() {
        build();
        setVisibility(VISIBLE);
        setAlpha(0f);
        animate().alpha(1f).setDuration(320).setStartDelay(0).setInterpolator(Ui.SOFT).start();
        // each circle rises in a beat after the one before: calm, not a cascade
        for (int i = 0; i < faces.size(); i++) {
            View f = faces.get(i);
            f.setAlpha(0f);
            f.setTranslationY(Ui.dp(22));
            f.animate().alpha(1f).translationY(0f).setStartDelay(90 + i * 55L).setDuration(640).setInterpolator(Ui.EMPHASIZED).start();
        }
        hint.setAlpha(0f);
        hint.animate().alpha(1f).setStartDelay(420).setDuration(500).setInterpolator(Ui.SOFT).start();
        String cur = Profiles.current(act);
        List<Profiles.Profile> all = Profiles.all(act);
        int start = 0;
        for (int i = 0; i < all.size(); i++) if (all.get(i).id.equals(cur)) start = i;
        focus(start);
    }

    void close() {
        if (!isOpen()) return;
        animate().alpha(0f).setDuration(220).setStartDelay(0).setInterpolator(Ui.SOFT).withEndAction(() -> setVisibility(GONE)).start();
    }

    /** Every key while it's open. */
    boolean key(int code) {
        if (code == KeyEvent.KEYCODE_DPAD_LEFT) focus(Math.max(0, at - 1));
        else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) focus(Math.min(faces.size() - 1, at + 1));
        else if (Pad.isConfirm(code)) {
            if (at < picks.size()) picks.get(at).run();
        } else if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU || code == HomeKeys.KEYCODE_PROFILE_SWITCH) {
            host.close();
        }
        return true;
    }

    private void build() {
        row.removeAllViews();
        faces.clear();
        picks.clear();
        String cur = Profiles.current(act);
        for (Profiles.Profile p : Profiles.all(act)) {
            boolean now = p.id.equals(cur);
            add(face(initial(p.name), p.color, now), p.name, now ? act.getString(R.string.watching) : p.kids ? act.getString(R.string.kids) : "", () -> {
                if (p.id.equals(Profiles.current(act))) {
                    host.close();
                    return;
                }
                // leaving a kids' profile is a grown-up thing; arriving in one never is
                Runnable go = () -> host.switched(p);
                if (p.kids) go.run();
                else host.guarded(go);
            });
        }
        // a quiet way to the rest, kept to the end of the row
        add(face("+", 0xFF8C93A6, false), Profiles.all(act).size() < Profiles.MAX ? act.getString(R.string.add_or_edit) : act.getString(R.string.edit), "", host::manage);
        hint.setText(act.getString(R.string.ok_to_switch_back_to));
    }

    private void add(Face f, String name, String tag, Runnable run) {
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setClipChildren(false);
        col.addView(f, new LinearLayout.LayoutParams(Ui.dp(SIZE), Ui.dp(SIZE)));
        TextView n = Ui.text(act, 16, Ui.semibold, 0xFFFFFFFF);
        n.setText(name);
        n.setSingleLine(true);
        n.setEllipsize(android.text.TextUtils.TruncateAt.END);
        n.setMaxWidth(Ui.dp(SIZE + 10));
        col.addView(n, lp(0, 4));
        TextView t = Ui.caps(act, 7, 0x8CFFFFFF, 0.3f);
        t.setText(tag);
        col.addView(t, lp(0, 5));
        col.setTag(f);
        col.setFocusable(true);
        col.setOnFocusChangeListener((v, has) -> lift(col, f, n, has));
        n.setAlpha(0.6f);
        f.setAlpha(0.72f);
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(Ui.dp(SIZE + 10), ViewGroup.LayoutParams.WRAP_CONTENT);
        l.leftMargin = l.rightMargin = Ui.dp(6);
        row.addView(col, l);
        faces.add(col);
        picks.add(run);
    }

    /** The circle in focus comes toward you and brightens; the rest settle back, a little dimmer. */
    private static void lift(View col, Face f, TextView name, boolean has) {
        f.animate().scaleX(has ? 1.12f : 1f).scaleY(has ? 1.12f : 1f).alpha(has ? 1f : 0.72f)
                .setDuration(380).setStartDelay(0).setInterpolator(Ui.EMPHASIZED).start();
        name.animate().alpha(has ? 1f : 0.6f).translationY(has ? Ui.dp(6) : 0f).setDuration(380).setStartDelay(0)
                .setInterpolator(Ui.EMPHASIZED).start();
        f.lit(has);
    }

    private void focus(int i) {
        if (faces.isEmpty()) return;
        at = i;
        faces.get(i).requestFocus();
    }

    private static String initial(String name) {
        String s = name == null ? "" : name.trim();
        return s.isEmpty() ? "?" : s.substring(0, s.offsetByCodePoints(0, 1)).toUpperCase(Locale.getDefault());
    }

    private Face face(String letter, int color, boolean current) {
        return new Face(act, letter, color, current);
    }

    private static LinearLayout.LayoutParams lp(int left, int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.leftMargin = Ui.dp(left);
        p.topMargin = Ui.dp(top);
        return p;
    }

    /**
     * One person: their colour lit from the upper left like a pearl, their initial in light type,
     * an ivory ring when it's the profile in use, and a soft halo of their colour while in focus.
     */
    private static final class Face extends View {
        private final String letter;
        private final int color;
        private final boolean current;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG),
                halo = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float glow;
        private static final float HALO = 1.3f;

        Face(Activity a, String letter, int color, boolean current) {
            super(a);
            this.letter = letter;
            this.color = color;
            this.current = current;
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(Ui.dpf(2.5f));
            ring.setColor(0xFFF4EFE6);
            text.setColor(0xFFFFFFFF);
            text.setTypeface(Ui.light);
            text.setTextAlign(Paint.Align.CENTER);
        }

        void lit(boolean on) {
            android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(glow, on ? 1f : 0f);
            va.setDuration(380);
            va.setInterpolator(Ui.SOFT);
            va.addUpdateListener(v -> {
                glow = (float) v.getAnimatedValue();
                invalidate();
            });
            va.start();
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            // the halo needs room around the circle: the circle keeps to the middle of the view
            float r = Math.min(w, h) / 2f / HALO;
            fill.setShader(new RadialGradient(w / 2f - r * 0.32f, h / 2f - r * 0.44f, r * 1.5f,
                    new int[]{mix(color, 0xFFFFFFFF, 0.28f), color, mix(color, 0xFF000000, 0.45f)},
                    new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            halo.setShader(new RadialGradient(w / 2f, h / 2f, r * HALO,
                    new int[]{(color & 0x00FFFFFF) | 0x66000000, color & 0x00FFFFFF}, new float[]{0.72f, 1f}, Shader.TileMode.CLAMP));
            text.setTextSize(r * 0.86f);
        }

        @Override
        protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, r = Math.min(cx, cy) / HALO;
            if (glow > 0f) {
                halo.setAlpha((int) (255 * glow));
                c.drawCircle(cx, cy, r * HALO, halo);
            }
            c.drawCircle(cx, cy, r, fill);
            if (current) c.drawCircle(cx, cy, r + Ui.dpf(5), ring);
            Paint.FontMetrics m = text.getFontMetrics();
            c.drawText(letter, cx, cy - (m.ascent + m.descent) / 2f, text);
        }

        private static int mix(int a, int b, float t) {
            return Color.rgb((int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                    (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                    (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
        }
    }
}
