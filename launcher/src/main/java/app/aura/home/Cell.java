package app.aura.home;

import android.content.Context;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Grid entry: the card, with its name surfacing beneath only while it has focus, as on tvOS. */
final class Cell extends LinearLayout {
    final Card card;
    final TextView label;
    final String tag;

    Cell(Context c, Tiles.Painter painter, int color, String name, String tag, int wDp, int hDp) {
        super(c);
        this.tag = tag;
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        setClipChildren(false);
        setClipToPadding(false);

        card = new Card(c, painter, color, wDp >= 140 ? 15 : 10);
        addView(card, new LayoutParams(Ui.dp(wDp), Ui.dp(hDp)));

        label = Ui.text(c, 12, Ui.medium, 0xFFFFFFFF);
        label.setText(name);
        label.setGravity(Gravity.CENTER);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        Ui.lift(label, 8);
        label.setAlpha(0f);
        LayoutParams ll = new LayoutParams(Ui.dp(wDp), ViewGroup.LayoutParams.WRAP_CONTENT);
        ll.topMargin = Ui.dp(12);
        addView(label, ll);

        card.focusListener = (card, gained) -> label.animate().alpha(gained ? 1f : 0f)
                .translationY(gained ? Ui.dpf(6) : 0f).setDuration(gained ? 380 : 160)
                .setInterpolator(Ui.EMPHASIZED).setStartDelay(gained ? 60 : 0).start();
    }

    /** Fades (the intro cascade) apply straight to the card and label: no offscreen layer per tile. */
    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }
}
