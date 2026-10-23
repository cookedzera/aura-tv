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

    private static final int WIDTH = 232;

    private final GlassFrame panel;
    private final TextView name;
    private final LinearLayout list;

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
        name.setPadding(Ui.dp(12), 0, Ui.dp(12), Ui.dp(8));
        col.addView(name);
        list = new LinearLayout(c);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setClipChildren(false);
        col.addView(list);
        panel.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(panel, new FrameLayout.LayoutParams(Ui.dp(WIDTH), ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    boolean isOpen() {
        return getVisibility() == VISIBLE;
    }

    /** Opens beside {@code anchor} (to its right if there's room, else its left), first row focused. */
    void show(View anchor, String label, List<Item> items) {
        name.setText(label.toUpperCase(Locale.getDefault()));
        list.removeAllViews();
        for (Item it : items) list.addView(row(it), rowParams());
        setVisibility(VISIBLE);
        int w = Ui.dp(WIDTH);
        panel.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        int h = panel.getMeasuredHeight();
        // measured against the screen it covers: this layer was GONE until now, so it has no size of its own yet
        View screen = (View) getParent();
        int sw = screen.getWidth(), sh = screen.getHeight();
        int[] at = new int[2], me = new int[2];
        anchor.getLocationInWindow(at);
        screen.getLocationInWindow(me);
        // the tile's visible box as grown by focus (the window position already includes the scale)
        float aw = anchor.getWidth() * anchor.getScaleX(), ah = anchor.getHeight() * anchor.getScaleY();
        float ax = at[0] - me[0], ay = at[1] - me[1];
        float gap = Ui.dpf(18), edge = Ui.dpf(24);
        float x = ax + aw + gap;
        boolean right = x + w <= sw - edge;
        if (!right) x = ax - w - gap;
        float y = Math.max(edge, Math.min(ay + ah / 2f - h / 2f, sh - h - edge));
        // placed by margins, so the slide-in below can use the translation freely
        FrameLayout.LayoutParams l = (FrameLayout.LayoutParams) panel.getLayoutParams();
        l.leftMargin = Math.round(x);
        l.topMargin = Math.round(y);
        panel.setLayoutParams(l);
        panel.setTranslationY(0f);
        // it slides out from the app's side; text is never scaled mid-animation
        panel.setAlpha(0f);
        panel.setTranslationX(Ui.dpf(right ? -12 : 12));
        panel.animate().alpha(1f).translationX(0f).setDuration(320).setStartDelay(0).setInterpolator(Ui.EMPHASIZED).start();
        if (list.getChildCount() > 0) list.getChildAt(0).requestFocus();
    }

    void hide() {
        if (!isOpen()) return;
        panel.animate().alpha(0f).setDuration(160).setStartDelay(0).setInterpolator(Ui.SOFT)
                .withEndAction(() -> setVisibility(GONE)).start();
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
