package app.aura.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * A row of tabs in a glass capsule, as on Apple TV, shared by All Apps and the Aura style's home:
 * the pill under the chosen tab stretches toward the next (its leading edge ahead of the trailing
 * one) and gathers there, the tab in focus pops a touch larger with the pill, white while the tabs
 * have the focus and faint once it's gone below. A tab focus rests on is picked after
 * {@link #PICK_MS}, so sweeping across builds nothing on the way; OK picks at once, and holding OK
 * asks for the tab's menu, timed here, as an IR remote's own long-press often never arrives.
 */
final class TabStrip extends LinearLayout {
    interface Listener {
        /** Tab {@code k} chosen: its content in now. */
        void pick(int k);

        /** OK held on tab {@code k}. */
        void hold(int k, View tab);
    }

    /** A tab taken by focus waits this long before it's picked. */
    static final long PICK_MS = 160;
    /** A tab in focus grows to this, with the pop of the other controls. */
    static final float GROW = 1.06f;

    private final Listener listener;
    private final float textSp;
    private final int padX, padY;
    private final List<TextView> tabs = new ArrayList<>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Spring.Mover left, right;
    /** The left edge leads (heading left), on {@link Spring#pillLead}; the right one otherwise. */
    private boolean leftLeads;
    /** The tab the pill sits on: it grows with that tab. */
    private View on;
    private float top, bottom;
    private boolean lit;
    /** The tab chosen (whose content is on show), -1 before any. */
    private int current = -1;
    /** Off while the screen it's on goes away: no picks, and tabs losing focus snap back to size. */
    private boolean live = true;
    /** OK went down on a tab and hasn't yet been let go or held long enough for its menu. */
    private boolean armed;
    private final Runnable pick = this::pickFocused;
    private final Runnable held = this::held;

    TabStrip(Context c, float textSp, int padX, int padY, Listener l) {
        super(c);
        listener = l;
        this.textSp = textSp;
        this.padX = padX;
        this.padY = padY;
        setOrientation(HORIZONTAL);
        setWillNotDraw(false);
        int in = Ui.dp(4);
        setPadding(in, in, in, in);
        paint.setColor(0x2EFFFFFF);
        // the capsule: a pane of light glass with a hairline round it, its own background
        GradientDrawable capsule = new GradientDrawable();
        capsule.setColor(0x1FFFFFFF);
        capsule.setStroke(Math.max(1, Ui.dp(0.75f)), 0x33FFFFFF);
        capsule.setCornerRadius(Ui.dpf(999));
        setBackground(capsule);
        left = new Spring.Mover(Spring.pillTrail(), 0.5f, 0f, v -> invalidate());
        right = new Spring.Mover(Spring.pillLead(), 0.5f, 0f, v -> invalidate());
    }

    /** New tabs with these names, none chosen yet. */
    void set(List<String> titles) {
        removeCallbacks(pick);
        removeAllViews();
        tabs.clear();
        current = -1;
        on = null;
        for (int i = 0; i < titles.size(); i++) {
            final int k = i;
            TextView t = Ui.text(getContext(), textSp, Ui.semibold, 0xCCFFFFFF);
            t.setText(titles.get(i));
            t.setSingleLine(true);
            t.setPadding(Ui.dp(padX), Ui.dp(padY), Ui.dp(padX), Ui.dp(padY));
            t.setFocusable(true);
            t.setId(View.generateViewId());
            t.setOnFocusChangeListener((v, gained) -> focusChanged(k, v, gained));
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) l.leftMargin = Ui.dp(2);
            addView(t, l);
            tabs.add(t);
        }
        for (int i = 0; i < tabs.size(); i++) {
            TextView t = tabs.get(i);
            t.setNextFocusLeftId(i == 0 ? t.getId() : tabs.get(i - 1).getId());
            t.setNextFocusRightId(i == tabs.size() - 1 ? t.getId() : tabs.get(i + 1).getId());
        }
    }

    private void focusChanged(int k, View v, boolean gained) {
        paint();
        // the pill grows with it: the row draws it, so it's drawn again as the tab grows
        if (!live) {
            // going away: back to size at once, not moving inside the layer the screen leaves on
            v.animate().cancel();
            v.setScaleX(1f);
            v.setScaleY(1f);
            invalidate();
            return;
        }
        float s = gained ? GROW : 1f;
        boolean pop = gained && !Ui.light;
        v.animate().scaleX(s).scaleY(s).setStartDelay(0)
                .setDuration(pop ? Ui.CTRL_POP : gained ? Ui.CTRL_SCALE_IN : Ui.CTRL_SCALE_OUT)
                .setInterpolator(pop ? Ui.CTRL_POP_CURVE : gained ? Ui.EASE_DECEL : Ui.EASE_STANDARD)
                .setUpdateListener(a -> invalidate()).start();
        if (!gained) return;
        glideTo(v, true);
        removeCallbacks(pick);
        if (k != current) postDelayed(pick, PICK_MS);
    }

    int size() {
        return tabs.size();
    }

    TextView tab(int k) {
        return tabs.get(k);
    }

    /** The pill's tab was relabelled and hasn't been laid out since: the one listener waiting on that. */
    private View relaidOn;
    private OnLayoutChangeListener relaid;

    /** Tab {@code k} reads {@code text} from now on; the pill follows the tabs to where they sit once that's laid out. */
    void relabel(int k, CharSequence text) {
        if (k < 0 || k >= tabs.size()) return;
        tabs.get(k).setText(text);
        if (on == null) return;
        // a label as wide as the last moves nothing and no layout change comes: its listener is replaced, not added to
        if (relaid != null) relaidOn.removeOnLayoutChangeListener(relaid);
        relaidOn = on;
        relaid = new OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                v.removeOnLayoutChangeListener(this);
                relaid = null;
                relaidOn = null;
                // to the tab the pill is on now: a label no wider than the last brings no layout, and this
                // waited on the tab it was on then, through any number of others chosen since
                if (on != null) glideTo(on, false);
            }
        };
        on.addOnLayoutChangeListener(relaid);
    }

    /** The tab in focus, or -1 when focus is elsewhere. */
    int focused() {
        for (int i = 0; i < tabs.size(); i++) if (tabs.get(i).isFocused()) return i;
        return -1;
    }

    /** Tab {@code k} is the chosen one: the pill goes to it, gliding or there at once. */
    void choose(int k, boolean glide) {
        if (k < 0 || k >= tabs.size()) return;
        current = k;
        glideTo(tabs.get(k), glide);
        paint();
    }

    /** The tab in focus is picked now, not after its rest (OK, or Down into what it shows). */
    void pickNow() {
        removeCallbacks(pick);
        pick.run();
    }

    private void pickFocused() {
        int k = focused();
        if (k >= 0) listener.pick(k);
    }

    /** Off as its screen goes away (no picks, no hold, tabs snap back to size); on again as it comes. */
    void live(boolean on) {
        live = on;
        if (on) return;
        removeCallbacks(pick);
        removeCallbacks(held);
        armed = false;
    }

    /** The chosen tab dark on a white pill while the tabs have the focus, light on a faint one once it's gone. */
    private void paint() {
        boolean onTabs = focused() >= 0;
        if (lit != onTabs) {
            lit = onTabs;
            paint.setColor(onTabs ? 0xF7FFFFFF : 0x2EFFFFFF);
            invalidate();
        }
        for (int i = 0; i < tabs.size(); i++) {
            TextView t = tabs.get(i);
            boolean chosen = onTabs ? t.isFocused() : i == current;
            t.setTextColor(chosen && onTabs ? 0xFF1C1C1E : chosen ? 0xFFFFFFFF : 0x99FFFFFF);
        }
    }

    private void held() {
        int k = focused();
        if (!armed || k < 0) return;
        armed = false;
        listener.hold(k, tabs.get(k));
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (live && focused() >= 0 && Pad.isConfirm(e.getKeyCode())) {
            // OK on a tab: a press picks it, a hold asks for its menu
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (e.getRepeatCount() == 0) {
                    armed = true;
                    postDelayed(held, android.view.ViewConfiguration.getLongPressTimeout());
                } else if ((e.getFlags() & KeyEvent.FLAG_LONG_PRESS) != 0) {
                    // the remote's own long-press arrived first
                    removeCallbacks(held);
                    held();
                }
                return true;
            }
            removeCallbacks(held);
            if (armed && !e.isCanceled()) pickNow();
            armed = false;
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    /** The pill to {@code tab}'s place, gliding or there at once. */
    private void glideTo(View tab, boolean glide) {
        if (tab.getWidth() == 0) {
            // not laid out yet (the screen's first frame): once it is
            tab.addOnLayoutChangeListener(new OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                    v.removeOnLayoutChangeListener(this);
                    glideTo(v, false);
                }
            });
            return;
        }
        on = tab;
        top = tab.getTop();
        bottom = tab.getBottom();
        if (glide && right.value() > left.value()) {
            boolean toLeft = tab.getLeft() < left.target();
            if (toLeft != leftLeads) {
                // turning round: the other edge leads now, each keeping its speed
                leftLeads = toLeft;
                left.respring(toLeft ? Spring.pillLead() : Spring.pillTrail());
                right.respring(toLeft ? Spring.pillTrail() : Spring.pillLead());
            }
            left.to(tab.getLeft());
            right.to(tab.getRight());
        } else {
            left.jump(tab.getLeft());
            right.jump(tab.getRight());
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float l = left.value(), rt = right.value();
        if (rt <= l) return;
        r.set(l, top, rt, bottom);
        // grown with its tab, about the tab's own centre
        float s = on != null ? on.getScaleX() : 1f;
        if (s != 1f) r.inset(-(rt - l) * (s - 1f) / 2f, -(bottom - top) * (s - 1f) / 2f);
        float rad = r.height() / 2f;
        c.drawRoundRect(r, rad, rad, paint);
    }
}
