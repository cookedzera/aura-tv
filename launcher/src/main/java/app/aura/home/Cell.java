package app.aura.home;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Grid entry: the card, with its name surfacing beneath according to the chosen name mode. On HIGH
 * the name rides the tile's growth; on LOW it is put where it will show and only fades.
 */
final class Cell extends LinearLayout {
    final Card card;
    final TextView label;
    final String tag;
    /** What the tile was painted from: one painted from the same can be kept as the grid changes. */
    String sig;
    /** The category row it's in ({@link Categories}), or null on the grid: an app can be in two rows. */
    String row;

    /** The name fades in over this once its tile is 80 % up, and out over this (§8.3.3); in a quick run it waits for the run to stop. */
    private static final int NAME_IN = 120, NAME_OUT = 100, RUN_REST = 180;
    /**
     * White at 33 strengths, made once. On HIGH the name keeps its soft shadow and fades by its colour:
     * a fading view with a shadow layer would go offscreen every frame (PC13).
     */
    private static final ColorStateList[] INK = new ColorStateList[33];

    private String nameMode = "focus";
    private boolean inDock;
    /** How strongly the name shows, 0 to 1, and how: 1 by its colour with a shadow (HIGH), 0 by the view's alpha (LOW). */
    private float named;
    private int styled = -1;
    /** Focus has arrived; the name waits for the tile to be 80 % up. */
    private boolean rising;
    private ValueAnimator fade;
    private float fadeFrom, fadeTo;
    private final Runnable reveal = () -> fadeName(targetAlpha(true), NAME_IN);

    /** Its key among home's tiles: the app (or folder, or input), and on category rows its row too. */
    String key() {
        return row == null ? tag : row + "|" + tag;
    }

    Cell(Context c, Tiles.Painter painter, int color, String name, String tag, int wDp, int hDp) {
        super(c);
        this.tag = tag;
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        setClipChildren(false);
        setClipToPadding(false);

        card = new Card(c, painter, color, wDp >= 140 ? 15 : 10);
        // TalkBack says the app's name as its tile takes focus
        card.setContentDescription(name);
        addView(card, new LayoutParams(Ui.dp(wDp), Ui.dp(hDp)));

        label = Ui.text(c, 12, Ui.medium, 0xFFFFFFFF);
        label.setText(name);
        label.setGravity(Gravity.CENTER);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        // the card says it already
        label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LayoutParams ll = new LayoutParams(Ui.dp(wDp), ViewGroup.LayoutParams.WRAP_CONTENT);
        ll.topMargin = Ui.dp(12);
        addView(label, ll);
        showName(0f);

        card.focusListener = (cd, gained) -> focused(gained);
        card.moved = this::ride;
    }

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
        removeCallbacks(reveal);
        rising = false;
        if (fade != null) fade.cancel();
        showName(targetAlpha(card.isFocused()));
        place();
    }

    /** Focus came (the name waits for the tile to be 80 % up, see {@link #ride}) or went (it fades now). */
    private void focused(boolean gained) {
        removeCallbacks(reveal);
        rising = gained;
        if (gained) place();
        else fadeName(targetAlpha(false), Math.round(NAME_OUT * card.pace));
    }

    /** Each frame the tile moves: on HIGH the name rides its growth; once the tile is 80 % up, the name comes. */
    private void ride() {
        if (!Ui.light) label.setTranslationY(rideFor(card.getScaleY()));
        if (rising && card.risen() >= 0.8f) {
            rising = false;
            // a quick run (a held key): no names until it stops
            if (card.pace < 1f) postDelayed(reveal, RUN_REST);
            else reveal.run();
        }
    }

    /**
     * Where the name sits: on HIGH riding the tile; on LOW where it will show, put there at once, or
     * left still when it stays on show at rest.
     */
    private void place() {
        if (!Ui.light) label.setTranslationY(rideFor(card.getScaleY()));
        else label.setTranslationY(targetAlpha(false) > 0f ? 0f : rideFor(card.focusedScale()));
    }

    /** How far the name moves down as its tile grows to {@code scale}: LABEL_OFFSET at the app scale. */
    private static float rideFor(float scale) {
        return Math.max(0f, Ui.dpf(Ui.LABEL_OFFSET) * (scale - 1f) / (Ui.FOCUS_SCALE_APP - 1f));
    }

    private void fadeName(float to, long ms) {
        if (fade == null) {
            fade = ValueAnimator.ofFloat(0f, 1f);
            fade.setInterpolator(Ui.EASE_DECEL);
            fade.addUpdateListener(a -> showName(fadeFrom + (fadeTo - fadeFrom) * a.getAnimatedFraction()));
        }
        fade.cancel();
        if (to == named) {
            showName(to);
            return;
        }
        fadeFrom = named;
        fadeTo = to;
        fade.setDuration(ms);
        fade.start();
    }

    /**
     * The name at strength {@code a}. HIGH keeps its soft shadow and fades it with the colour; LOW draws
     * no text shadow (PC6) and fades the view, which redraws nothing.
     */
    private void showName(float a) {
        named = a;
        int style = Ui.light ? 0 : 1;
        if (style != styled) {
            styled = style;
            if (style == 0) {
                label.setShadowLayer(0f, 0f, 0f, 0);
                label.setTextColor(0xFFFFFFFF);
            } else {
                label.setAlpha(1f);
            }
        }
        if (style == 0) {
            label.setAlpha(a);
        } else {
            label.setTextColor(ink(a));
            label.setShadowLayer(Ui.dpf(8), 0f, Ui.dpf(1), Ui.withAlpha(0, Math.round(0x59 * a)));
        }
    }

    private static ColorStateList ink(float a) {
        int i = Math.round(Ui.clamp(a, 0f, 1f) * 32f);
        if (INK[i] == null) INK[i] = ColorStateList.valueOf(Ui.withAlpha(0xFFFFFFFF, Math.round(255f * i / 32f)));
        return INK[i];
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(reveal);
        rising = false;
        if (fade != null && fade.isStarted()) fade.end();
        super.onDetachedFromWindow();
    }

    /** Fades (the intro cascade) apply straight to the card and label: no offscreen layer per tile. */
    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }
}
