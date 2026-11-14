package app.aura.home;

import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Rect;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Focusable glass control. On focus it lights up white and its content turns dark, tvOS-style. */
class Pad extends GlassFrame {
    interface FillListener {
        void onFill(float f);
    }

    static final int DARK = 0xFF15171B;

    Runnable onClick, onLongClick;
    FillListener fillListener;
    float focusScale = Ui.FOCUS_SCALE_CONTROL;
    /** A row in a list: focused, it grows by its width instead, ×1.08 up to 260 dp wide and ×1.04 wider. */
    boolean row;

    private final List<View> tinted = new ArrayList<>();
    private final List<Float> tintAlpha = new ArrayList<>();
    private final Lift lift = new Lift(this, new Lift.Host() {
        @Override
        public float lifted() {
            if (!row) return focusScale;
            return getWidth() <= Ui.dp(260) ? Ui.FOCUS_SCALE_ROW : Ui.FOCUS_SCALE_WIDE_ROW;
        }

        @Override
        public void fill(float f) {
            highlight = f;
            // HIGH lifts a lit control off the glass; LOW casts no shadow at all
            setTranslationZ(Ui.light ? 0f : Ui.dpf(10) * f);
            apply();
        }
    });
    private boolean pressed, longFired;

    Pad(Context c, Backdrop b, float radiusDp, float darkness) {
        super(c, b, radiusDp, darkness);
        setFocusable(true);
        // what a pad holds stays inside it, and it draws its own rounded shape: LOW spares the clip
        if (Ui.light) setClipToOutline(false);
    }

    <T extends View> T tint(T v, float alpha) {
        tinted.add(v);
        tintAlpha.add(alpha);
        apply();
        return v;
    }

    int content(float alpha) {
        return Ui.mix(Ui.alpha(0xFFFFFFFF, alpha), Ui.alpha(DARK, Math.min(1f, alpha + 0.12f)), highlight);
    }

    private void apply() {
        for (int i = 0; i < tinted.size(); i++) {
            View v = tinted.get(i);
            int col = content(tintAlpha.get(i));
            if (v instanceof TextView) ((TextView) v).setTextColor(col);
            else if (v instanceof ImageView) ((ImageView) v).setColorFilter(col);
            else v.invalidate();
        }
        if (fillListener != null) fillListener.onFill(highlight);
        invalidate();
    }

    @Override
    protected void onFocusChanged(boolean gained, int direction, Rect previous) {
        super.onFocusChanged(gained, direction, previous);
        if (!gained) pressed = false;
        lift.focus(gained);
    }

    @Override
    protected void onDetachedFromWindow() {
        lift.stop();
        super.onDetachedFromWindow();
    }

    /** What TalkBack says for a pad no one described: its words, in order. */
    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        if (getContentDescription() == null) info.setContentDescription(spoken(this));
    }

    /** The words on show inside {@code v}, as one phrase; null when there are none. */
    static CharSequence spoken(View v) {
        StringBuilder b = new StringBuilder();
        words(v, b);
        return b.length() > 0 ? b.toString() : null;
    }

    private static void words(View v, StringBuilder b) {
        if (v.getVisibility() != View.VISIBLE) return;
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            if (t != null && t.toString().trim().length() > 0) b.append(b.length() > 0 ? ", " : "").append(t);
        } else if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) words(g.getChildAt(i), b);
        }
    }

    static boolean isConfirm(int code) {
        return code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_BUTTON_A;
    }

    static boolean isArrow(int code) {
        return code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN;
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (isConfirm(code)) {
            if (e.getRepeatCount() == 0) {
                e.startTracking();
                pressed = true;
                // a hold whose long press moved the focus away (Standby's quiet screen) never had its release here
                longFired = false;
                lift.press(true);
            }
            return true;
        }
        return super.onKeyDown(code, e);
    }

    @Override
    public boolean onKeyLongPress(int code, KeyEvent e) {
        if (isConfirm(code) && onLongClick != null) {
            longFired = true;
            lift.press(false);
            onLongClick.run();
            return true;
        }
        return super.onKeyLongPress(code, e);
    }

    @Override
    public boolean onKeyUp(int code, KeyEvent e) {
        if (isConfirm(code)) {
            // a release whose press began elsewhere (the focus arrived mid-press) presses nothing
            boolean click = pressed && !longFired && !e.isCanceled();
            if (pressed && !longFired) lift.press(false);
            pressed = false;
            longFired = false;
            if (click && onClick != null) onClick.run();
            return true;
        }
        return super.onKeyUp(code, e);
    }

    /**
     * A control's focus and press. The fill comes in {@link Ui#CTRL_HILITE_IN} ms and goes in
     * {@link Ui#CTRL_HILITE_OUT}; the scale pops through {@link Ui#CTRL_POP_CURVE} to the focused
     * size on HIGH (×1.08 by way of ×1.12) and goes straight there in {@link Ui#CTRL_SCALE_IN} on
     * LOW, back in {@link Ui#CTRL_SCALE_OUT}; a press sinks to {@link Ui#PRESS_FACTOR} of the focused
     * size. Every move starts where the last one got to. Two animators made once; no frame allocates.
     */
    static final class Lift {
        interface Host {
            /** The scale while focused. */
            float lifted();

            void fill(float f);
        }

        private static final int REST = 0, FOCUSED = 1, PRESSED = 2;
        private final View view;
        private final Host host;
        private final ValueAnimator filler = ValueAnimator.ofFloat(0f, 1f), scaler = ValueAnimator.ofFloat(0f, 1f);
        private float lit, litFrom, litTo, scaleFrom;
        private int goal = REST;

        Lift(View view, Host host) {
            this.view = view;
            this.host = host;
            filler.addUpdateListener(a -> {
                lit = litFrom + (litTo - litFrom) * a.getAnimatedFraction();
                host.fill(lit);
            });
            scaler.addUpdateListener(a -> {
                float s = scaleFrom + (target() - scaleFrom) * a.getAnimatedFraction();
                view.setScaleX(s);
                view.setScaleY(s);
            });
        }

        void focus(boolean gained) {
            if (!gained) {
                fillTo(0f, Ui.light ? Ui.CTRL_SCALE_OUT : Ui.CTRL_HILITE_OUT, Ui.EASE_STANDARD);
                scaleTo(REST, Ui.CTRL_SCALE_OUT, Ui.EASE_STANDARD);
                return;
            }
            fillTo(1f, Ui.CTRL_HILITE_IN, Ui.EASE_DECEL);
            if (Ui.light) scaleTo(FOCUSED, Ui.CTRL_SCALE_IN, Ui.EASE_DECEL);
            else scaleTo(FOCUSED, Ui.CTRL_POP, Ui.CTRL_POP_CURVE);
        }

        void press(boolean down) {
            int to = down ? PRESSED : view.isFocused() ? FOCUSED : REST;
            scaleTo(to, down ? Ui.PRESS_DOWN : Ui.PRESS_UP, Ui.EASE_DECEL);
        }

        /** Off the screen: each move ends where it was going, and nothing is left running. */
        void stop() {
            if (filler.isStarted()) filler.end();
            if (scaler.isStarted()) scaler.end();
        }

        private float target() {
            return goal == REST ? 1f : goal == FOCUSED ? host.lifted() : host.lifted() * Ui.PRESS_FACTOR;
        }

        private void fillTo(float to, long ms, TimeInterpolator curve) {
            filler.cancel();
            if (Ui.light && Math.abs(lit - to) < 1e-4f) {
                if (lit != to) {
                    lit = to;
                    host.fill(lit);
                }
                return;
            }
            litFrom = lit;
            litTo = to;
            filler.setDuration(ms);
            filler.setInterpolator(curve);
            filler.start();
        }

        private void scaleTo(int to, long ms, TimeInterpolator curve) {
            scaler.cancel();
            goal = to;
            float dst = target();
            if (Ui.light && Math.abs(view.getScaleX() - dst) < 1e-4f) {
                if (view.getScaleX() != dst) {
                    view.setScaleX(dst);
                    view.setScaleY(dst);
                }
                return;
            }
            scaleFrom = view.getScaleX();
            scaler.setDuration(ms);
            scaler.setInterpolator(curve);
            scaler.start();
        }
    }
}
