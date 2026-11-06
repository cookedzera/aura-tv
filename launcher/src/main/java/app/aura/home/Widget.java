package app.aura.home;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/** A glass dashboard card. Focused, it lifts a little and gains a bright rim; OK runs its action. */
final class Widget extends GlassFrame {
    Runnable onClick, onLongClick;
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final android.graphics.Matrix shadowMatrix = new android.graphics.Matrix();
    private final ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
    private android.graphics.Bitmap shadow;
    private float focus, focusFrom, focusTo;
    private boolean longFired;

    Widget(Context c, Backdrop b) {
        super(c, b, 26, 0.42f);
        frost = 0.11f;
        setFocusable(true);
        setClipContent(false);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(Ui.dpf(2f));
        anim.setInterpolator(Ui.DECEL);
        anim.addUpdateListener(a -> {
            focus = focusFrom + (focusTo - focusFrom) * a.getAnimatedFraction();
            invalidate();
        });
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        ring.setShader(new android.graphics.LinearGradient(0, 0, 0, h, Ui.RING_TOP, Ui.RING_BOTTOM,
                android.graphics.Shader.TileMode.CLAMP));
        shadow = w > 0 && h > 0 ? Ui.shadow(w, h, radius) : null;
    }

    @Override
    protected void onDraw(Canvas c) {
        // raised, the card casts a soft shadow onto the photograph, as the app tiles do
        if (shadow != null && focus > 0f) {
            Ui.drawShadow(c, shadow, getWidth(), getHeight(), Ui.dpf(8) * focus, Math.round(100 * focus), shadowPaint, shadowMatrix);
        }
        super.onDraw(c);
    }

    @Override
    protected void dispatchDraw(Canvas c) {
        super.dispatchDraw(c);
        if (focus > 0f) {
            // settles outward into place as it lights
            float o = Ui.dpf(2.5f + focus);
            ring.setAlpha(Math.round(255 * focus));
            c.drawRoundRect(-o, -o, getWidth() + o, getHeight() + o, radius + o, radius + o, ring);
        }
    }

    @Override
    protected void onFocusChanged(boolean gained, int direction, Rect previous) {
        super.onFocusChanged(gained, direction, previous);
        if (!gained) removeCallbacks(hold);
        anim.cancel();
        focusFrom = focus;
        focusTo = gained ? 1f : 0f;
        anim.setDuration(gained ? 300 : 200);
        anim.start();
        // LOW: no elevation (the baked shadow above is the only one) and no overshoot
        boolean low = Ui.light;
        animate().scaleX(gained ? 1.035f : 1f).scaleY(gained ? 1.035f : 1f).translationZ(gained && !low ? Ui.dpf(14) : 0f)
                .setInterpolator(!gained ? Ui.DECEL : low ? Ui.EASE_DECEL : Ui.SPRING)
                .setDuration(!gained ? 240 : low ? Ui.LOW_FOCUS_IN : 460).setStartDelay(0)
                .setUpdateListener(null).start();
    }

    @Override
    protected void onDetachedFromWindow() {
        // the rim ends where it was going: stopped halfway, it would still glow when the card came back
        if (anim.isStarted()) anim.end();
        super.onDetachedFromWindow();
    }

    /** What TalkBack says for a card no one described: its words, in order. */
    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        if (getContentDescription() == null) info.setContentDescription(Pad.spoken(this));
    }

    /** Holding OK, timed from the press as on app tiles: IR remotes barely repeat. */
    private final Runnable hold = () -> {
        if (onLongClick == null || !isFocused()) return;
        longFired = true;
        press(false);
        onLongClick.run();
    };

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (Pad.isConfirm(code)) {
            if (e.getRepeatCount() == 0) {
                longFired = false;
                press(true);
                removeCallbacks(hold);
                if (onLongClick != null) postDelayed(hold, android.view.ViewConfiguration.getLongPressTimeout());
            } else if (!longFired && (e.getFlags() & KeyEvent.FLAG_LONG_PRESS) != 0) {
                removeCallbacks(hold);
                hold.run();
            }
            return true;
        }
        return super.onKeyDown(code, e);
    }

    @Override
    public boolean onKeyUp(int code, KeyEvent e) {
        if (Pad.isConfirm(code)) {
            removeCallbacks(hold);
            if (!longFired) press(false);
            if (!longFired && !e.isCanceled() && onClick != null) onClick.run();
            longFired = false;
            return true;
        }
        return super.onKeyUp(code, e);
    }

    private void press(boolean down) {
        boolean low = Ui.light;
        float s = down ? 0.99f : (isFocused() ? 1.035f : 1f);
        float z = low ? 0f : down ? Ui.dpf(6) : (isFocused() ? Ui.dpf(14) : 0f);
        animate().scaleX(s).scaleY(s).translationZ(z)
                .setDuration(down ? 90 : 300).setStartDelay(0)
                .setInterpolator(down || low ? Ui.DECEL : Ui.SPRING).setUpdateListener(null).start();
    }
}
