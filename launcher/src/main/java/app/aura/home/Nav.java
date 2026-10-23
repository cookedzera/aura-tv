package app.aura.home;

import android.graphics.Rect;
import android.view.View;

import java.util.List;

/**
 * Geometric focus for irregular layouts (modules of different sizes): the nearest control in the
 * pressed direction, preferring ones that line up with where you are.
 */
final class Nav {
    private static final int[] A = new int[2], B = new int[2];

    private Nav() { }

    static View next(List<View> items, View from, int direction) {
        Rect f = rect(from, A);
        View best = null;
        float bestScore = Float.MAX_VALUE;
        for (View v : items) {
            if (v == from || !v.isShown() || !v.isFocusable()) continue;
            Rect r = rect(v, B);
            float primary, ortho, drift;
            switch (direction) {
                case View.FOCUS_LEFT:
                    if (r.centerX() >= f.centerX() - 2) continue;
                    primary = Math.max(0, f.left - r.right);
                    ortho = gap(r.top, r.bottom, f.top, f.bottom);
                    drift = Math.abs(r.centerY() - f.centerY());
                    break;
                case View.FOCUS_RIGHT:
                    if (r.centerX() <= f.centerX() + 2) continue;
                    primary = Math.max(0, r.left - f.right);
                    ortho = gap(r.top, r.bottom, f.top, f.bottom);
                    drift = Math.abs(r.centerY() - f.centerY());
                    break;
                case View.FOCUS_UP:
                    if (r.centerY() >= f.centerY() - 2) continue;
                    primary = Math.max(0, f.top - r.bottom);
                    ortho = gap(r.left, r.right, f.left, f.right);
                    drift = Math.abs(r.centerX() - f.centerX());
                    break;
                default:
                    if (r.centerY() <= f.centerY() + 2) continue;
                    primary = Math.max(0, r.top - f.bottom);
                    ortho = gap(r.left, r.right, f.left, f.right);
                    drift = Math.abs(r.centerX() - f.centerX());
                    break;
            }
            float score = primary + 3f * ortho + 0.15f * drift;
            if (score < bestScore) {
                bestScore = score;
                best = v;
            }
        }
        return best;
    }

    private static float gap(int a0, int a1, int b0, int b1) {
        return Math.max(0, Math.max(a0 - b1, b0 - a1));
    }

    private static Rect rect(View v, int[] loc) {
        v.getLocationInWindow(loc);
        return new Rect(loc[0], loc[1], loc[0] + v.getWidth(), loc[1] + v.getHeight());
    }
}
