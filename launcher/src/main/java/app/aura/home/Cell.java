package app.aura.home;

import android.content.Context;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Grid entry: the card, with its name surfacing beneath according to the chosen name mode. */
final class Cell extends LinearLayout {
    final Card card;
    final TextView label;
    final String tag;
    /** What the tile was painted from: one painted from the same can be kept as the grid changes. */
    String sig;

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

        card.focusListener = (card, gained) -> label.animate().alpha(targetAlpha(gained))
                .translationY(gained ? Ui.dpf(6) : 0f).setDuration(gained ? 380 : 160)
                .setInterpolator(Ui.EMPHASIZED).setStartDelay(gained ? 60 : 0).start();
    }

    private String nameMode = "focus";
    private boolean inDock;

    private float targetAlpha(boolean focused) {
        if ("hidden".equals(nameMode)) return 0f;
        if ("always".equals(nameMode)) return focused ? 1f : 0.75f;
        if ("tvos".equals(nameMode) && inDock) return 0f;
        return focused ? 1f : 0f;
    }

    /** Show the name under every tile, on focus, hidden on the Apple TV dock tray, or hidden everywhere. */
    void setNameMode(String mode, boolean dockRow) {
        nameMode = mode;
        inDock = dockRow;
        label.setAlpha(targetAlpha(card.isFocused()));
    }

    /** Fades (the intro cascade) apply straight to the card and label: no offscreen layer per tile. */
    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }
}
