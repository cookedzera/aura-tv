package app.aura.home;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

/**
 * Hold OK on an app: a small frosted menu opens beside it, the way Apple TV offers an app's
 * options. Its rows are whatever the home screen hands it; Back closes it.
 */
final class AppMenu extends FrameLayout {
    static final class Item {
        final int icon;
        final String label;
        final Runnable run;

        Item(int icon, String label, Runnable run) {
            this.icon = icon;
            this.label = label;
            this.run = run;
        }
    }

    private static final int WIDTH = 244;

    private final GlassFrame panel;
    private final TextView name, detail;
    private final android.widget.ScrollView scroll;
    private final LinearLayout list;
    private boolean shown;

    AppMenu(Context c, Backdrop b) {
        super(c);
        setVisibility(GONE);
        panel = new GlassFrame(c, b, 20, 0.45f);
        panel.setClipContent(false);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(8), Ui.dp(12), Ui.dp(8), Ui.dp(8));
        col.setClipChildren(false);
        name = Ui.caps(c, 7.5f, 0x99FFFFFF, 0.24f);
        name.setPadding(Ui.dp(12), 0, Ui.dp(12), Ui.dp(6));
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(name);
        detail = Ui.text(c, 12f, Ui.regular, 0xCCFFFFFF);
        detail.setLineSpacing(0, 1.18f);
        detail.setMaxLines(3);
        detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
        detail.setPadding(Ui.dp(12), 0, Ui.dp(12), Ui.dp(8));
        detail.setVisibility(GONE);
        col.addView(detail);
        scroll = new android.widget.ScrollView(c);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        list = new LinearLayout(c);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setClipChildren(false);
        scroll.addView(list, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(panel, new FrameLayout.LayoutParams(Ui.dp(WIDTH), ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    boolean isOpen() {
        return shown;
    }

    /** Opens beside {@code anchor} (to its right if there's room, else its left), first row focused. */
    void show(View anchor, String label, List<Item> items) {
        show(anchor, label, null, items);
    }

    /** As {@link #show(View, String, List)}, with an optional short note (such as an episode synopsis) above the choices. */
    void show(View anchor, String label, String note, List<Item> items) {
        shown = true;
        name.setText(label.toUpperCase(Locale.getDefault()));
        if (note != null && !note.trim().isEmpty()) {
            detail.setText(note.trim());
            detail.setVisibility(VISIBLE);
        } else {
            detail.setText("");
            detail.setVisibility(GONE);
        }
        list.removeAllViews();
        int n = items.size();
        View[] rows = new View[n];
        for (int i = 0; i < n; i++) {
            View r = row(items.get(i));
            r.setId(View.generateViewId());
            rows[i] = r;
            list.addView(r, rowParams());
        }
        for (int i = 0; i < n; i++) {
            View r = rows[i];
            int self = r.getId();
            r.setNextFocusLeftId(self);
            r.setNextFocusRightId(self);
            r.setNextFocusUpId(rows[(i - 1 + n) % n].getId());
            r.setNextFocusDownId(rows[(i + 1) % n].getId());
        }
        setVisibility(VISIBLE);
        View screen = (View) getParent();
        int sw = screen.getWidth(), sh = screen.getHeight();
        float edge = Ui.dpf(24);
        int maxH = sh > 0 ? Math.max(Ui.dp(180), Math.round(sh - edge * 2)) : Integer.MAX_VALUE;
        int w = Ui.dp(WIDTH);
        LinearLayout.LayoutParams sl = (LinearLayout.LayoutParams) scroll.getLayoutParams();
        sl.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        scroll.setLayoutParams(sl);
        panel.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        int h = panel.getMeasuredHeight();
        if (h > maxH) {
            int chrome = h - scroll.getMeasuredHeight();
            sl.height = Math.max(Ui.dp(120), maxH - chrome);
            scroll.setLayoutParams(sl);
            panel.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST));
            h = panel.getMeasuredHeight();
        }
        int[] at = new int[2], me = new int[2];
        anchor.getLocationInWindow(at);
        screen.getLocationInWindow(me);
        // the tile's visible box as grown by focus (the window position already includes the scale)
        float aw = anchor.getWidth() * anchor.getScaleX(), ah = anchor.getHeight() * anchor.getScaleY();
        float ax = at[0] - me[0], ay = at[1] - me[1];
        float gap = Ui.dpf(18);
        float x = ax + aw + gap, y;
        boolean right = x + w <= sw - edge, beside = right || ax - w - gap >= edge, below = false;
        if (beside) {
            if (!right) x = ax - w - gap;
            y = Math.max(edge, Math.min(ay + ah / 2f - h / 2f, sh - h - edge));
        } else {
            // no room on either side (a lone widget stretched across the row): just below it, else above
            x = Math.max(edge, Math.min(ax + Ui.dpf(24), sw - w - edge));
            below = ay + ah + gap + h <= sh - edge;
            y = below ? ay + ah + gap : Math.max(edge, ay - h - gap);
        }
        // placed by margins, so the slide-in below can use the translation freely
        FrameLayout.LayoutParams l = (FrameLayout.LayoutParams) panel.getLayoutParams();
        l.leftMargin = Math.round(x);
        l.topMargin = Math.round(y);
        panel.setLayoutParams(l);
        // it slides out from the tile's side, or from under or over it; text is never scaled mid-animation
        panel.animate().cancel();
        panel.setAlpha(0f);
        panel.setTranslationX(beside ? Ui.dpf(right ? -16 : 16) : 0f);
        panel.setTranslationY(beside ? 0f : Ui.dpf(below ? -16 : 16));
        panel.animate().alpha(1f).translationX(0f).translationY(0f).setDuration(380).setStartDelay(0)
                .setInterpolator(Ui.SPRING).withEndAction(null).start();
        if (list.getChildCount() > 0) list.getChildAt(0).requestFocus();
    }

    void hide() {
        if (!shown) return;
        shown = false;
        panel.animate().cancel();
        panel.animate().alpha(0f).setDuration(150).setStartDelay(0).setInterpolator(Ui.SOFT)
                .withEndAction(() -> {
                    if (!shown) setVisibility(GONE);
                }).start();
    }

    private View row(Item it) {
        Pad p = new Pad(getContext(), null, 13, 0f);
        p.plainColor = 0x00FFFFFF;
        p.edgeLight = false;
        p.focusScale = 1.03f;
        LinearLayout r = new LinearLayout(getContext());
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dp(12), 0, Ui.dp(12), 0);
        ImageView icon = p.tint(new ImageView(getContext()), 0.85f);
        icon.setImageResource(it.icon);
        r.addView(icon, new LinearLayout.LayoutParams(Ui.dp(18), Ui.dp(18)));
        TextView t = p.tint(Ui.text(getContext(), 13.5f, Ui.semibold, 0xFFFFFFFF), 1f);
        t.setText(it.label);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.leftMargin = Ui.dp(12);
        r.addView(t, tl);
        p.addView(r, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        p.onClick = it.run;
        return p;
    }

    private static LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(40));
        l.topMargin = Ui.dp(3);
        return l;
    }
}
