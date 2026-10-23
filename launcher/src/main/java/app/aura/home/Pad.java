package app.aura.home;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Rect;
import android.view.KeyEvent;
import android.view.View;
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
    float focusScale = 1.06f;

    private final List<View> tinted = new ArrayList<>();
    private final List<Float> tintAlpha = new ArrayList<>();
    private ValueAnimator anim;
    private boolean longFired;

    Pad(Context c, Backdrop b, float radiusDp, float darkness) {
        super(c, b, radiusDp, darkness);
        setFocusable(true);
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
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(highlight, gained ? 1f : 0f);
        anim.setDuration(gained ? 240 : 180);
        anim.setInterpolator(Ui.DECEL);
        anim.addUpdateListener(a -> {
            highlight = (float) a.getAnimatedValue();
            apply();
        });
        anim.start();
        animate().scaleX(gained ? focusScale : 1f).scaleY(gained ? focusScale : 1f)
                .translationZ(gained ? Ui.dpf(10) : 0f)
                .setInterpolator(gained ? Ui.SPRING : Ui.DECEL)
                .setDuration(gained ? 420 : 220)
                .setStartDelay(0)
                .setUpdateListener(null)
                .start();
    }

    static boolean isConfirm(int code) {
        return code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_BUTTON_A;
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (isConfirm(code)) {
            if (e.getRepeatCount() == 0) e.startTracking();
            return true;
        }
        return super.onKeyDown(code, e);
    }

    @Override
    public boolean onKeyLongPress(int code, KeyEvent e) {
        if (isConfirm(code) && onLongClick != null) {
            longFired = true;
            onLongClick.run();
            return true;
        }
        return super.onKeyLongPress(code, e);
    }

    @Override
    public boolean onKeyUp(int code, KeyEvent e) {
        if (isConfirm(code)) {
            if (!longFired && !e.isCanceled() && onClick != null) {
                animate().scaleX(focusScale * 0.95f).scaleY(focusScale * 0.95f).setDuration(70).setStartDelay(0)
                        .setInterpolator(Ui.DECEL).setUpdateListener(null)
                        .withEndAction(() -> animate().scaleX(isFocused() ? focusScale : 1f)
                                .scaleY(isFocused() ? focusScale : 1f).setDuration(260).setInterpolator(Ui.SPRING).start())
                        .start();
                onClick.run();
            }
            longFired = false;
            return true;
        }
        return super.onKeyUp(code, e);
    }
}
