package app.aura.home;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.KeyEvent;

/** A glass dashboard card. Focused, it lifts a little and gains a bright rim; OK runs its action. */
final class Widget extends GlassFrame {
    Runnable onClick, onLongClick;
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float focus;
    private boolean longFired;
    private ValueAnimator anim;

    Widget(Context c, Backdrop b) {
        super(c, b, 26, 0.34f);
        frost = 0.05f;
        setFocusable(true);
        setClipContent(false);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(Ui.dpf(2f));
        ring.setColor(0xFFFFFFFF);
    }

    @Override
    protected void dispatchDraw(Canvas c) {
        super.dispatchDraw(c);
        if (focus > 0f) {
            float o = Ui.dpf(3.5f);
            ring.setAlpha(Math.round(235 * focus));
            c.drawRoundRect(-o, -o, getWidth() + o, getHeight() + o, radius + o, radius + o, ring);
        }
    }

    @Override
    protected void onFocusChanged(boolean gained, int direction, Rect previous) {
        super.onFocusChanged(gained, direction, previous);
        if (!gained) removeCallbacks(hold);
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(focus, gained ? 1f : 0f);
        anim.setDuration(gained ? 300 : 200);
        anim.setInterpolator(Ui.DECEL);
        anim.addUpdateListener(a -> {
            focus = (float) a.getAnimatedValue();
            invalidate();
        });
        anim.start();
        animate().scaleX(gained ? 1.035f : 1f).scaleY(gained ? 1.035f : 1f).translationZ(gained ? Ui.dpf(14) : 0f)
                .setInterpolator(gained ? Ui.SPRING : Ui.DECEL).setDuration(gained ? 460 : 240).setStartDelay(0)
                .setUpdateListener(a -> invalidate()).start();
    }

    /** Holding OK, timed from the press as on app tiles: IR remotes barely repeat. */
    private final Runnable hold = () -> {
        if (onLongClick == null || !isFocused()) return;
        longFired = true;
        onLongClick.run();
    };

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (Pad.isConfirm(code)) {
            if (e.getRepeatCount() == 0) {
                longFired = false;
                removeCallbacks(hold);
                if (onLongClick != null) postDelayed(hold, android.view.ViewConfiguration.getLongPressTimeout());
            }
            return true;
        }
        return super.onKeyDown(code, e);
    }

    @Override
    public boolean onKeyUp(int code, KeyEvent e) {
        if (Pad.isConfirm(code)) {
            removeCallbacks(hold);
            if (!longFired && !e.isCanceled() && onClick != null) onClick.run();
            longFired = false;
            return true;
        }
        return super.onKeyUp(code, e);
    }
}
