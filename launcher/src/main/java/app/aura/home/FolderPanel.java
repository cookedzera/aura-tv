package app.aura.home;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * An open folder: its name set large and its apps as tiles on frosted glass, as on Apple TV. OK
 * opens an app, holding OK offers to take it out, Back closes.
 */
final class FolderPanel extends FrameLayout {
    interface Host {
        Tiles.Painter art(AppRepo.App a);

        boolean glass();

        Backdrop backdrop();

        void launch(AppRepo.App a, View from);

        void hold(AppRepo.App a, Card from);
    }

    private static final int COLS = 4, W = 148, H = 84, GAP = 26;

    private final Activity act;
    private final Host host;
    private final TextView title;
    private final android.widget.ScrollView scroll;
    private final LinearLayout grid;
    private boolean shown;
    String openId;

    FolderPanel(Activity a, Host h) {
        super(a);
        act = a;
        host = h;
        setVisibility(GONE);
        setClipChildren(false);
        GlassFrame veil = new GlassFrame(a, h.backdrop(), 0, 0.55f);
        veil.edgeLight = false;
        addView(veil, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setClipChildren(false);
        col.setPadding(Ui.dp(32), Ui.dp(36), Ui.dp(32), Ui.dp(36));
        title = Ui.text(a, 40, Ui.heavy, 0xFFFFFFFF);
        title.setIncludeFontPadding(true);
        title.setLetterSpacing(-0.03f);
        col.addView(title);
        scroll = new android.widget.ScrollView(a);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(Ui.dp(24), Ui.dp(16), Ui.dp(24), Ui.dp(20));
        grid = new LinearLayout(a);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        scroll.addView(grid, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gl.topMargin = Ui.dp(18);
        col.addView(scroll, gl);
        addView(col, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
    }

    boolean isOpen() {
        return shown;
    }

    void open(Folders.Folder f, List<AppRepo.App> apps) {
        shown = true;
        openId = f.id;
        title.setText(f.name);
        grid.removeAllViews();
        scroll.scrollTo(0, 0);
        LinearLayout row = null;
        int n = apps.size();
        Card[] cards = new Card[n];
        for (int i = 0; i < n; i++) {
            if (i % COLS == 0) {
                row = new LinearLayout(act);
                row.setClipChildren(false);
                LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (i > 0) rl.topMargin = Ui.dp(20);
                grid.addView(row, rl);
            }
            final AppRepo.App a = apps.get(i);
            LinearLayout cell = new LinearLayout(act);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setClipChildren(false);
            final Card card = new Card(act, host.art(a), a.color, 14);
            card.setId(View.generateViewId());
            if (host.glass() && a.art == null) card.glass = host.backdrop();
            card.tvos = Prefs.tvos(act) || Prefs.tileStyle(act).equals("tvos");
            card.sway = !Perf.light(act);
            card.onClick = () -> host.launch(a, card);
            card.onLongClick = () -> host.hold(a, card);
            cell.addView(card, new LinearLayout.LayoutParams(Ui.dp(W), Ui.dp(H)));
            TextView label = Ui.text(act, 12, Ui.medium, 0xD9FFFFFF);
            label.setText(a.label);
            label.setSingleLine(true);
            label.setMaxWidth(Ui.dp(W));
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            nl.topMargin = Ui.dp(8);
            cell.addView(label, nl);
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i % COLS > 0) cl.leftMargin = Ui.dp(GAP);
            row.addView(cell, cl);
            cell.setAlpha(0f);
            cell.setTranslationY(Ui.dp(10));
            cell.animate().alpha(1f).translationY(0f).setStartDelay(i * 25L).setDuration(380).setInterpolator(Ui.EMPHASIZED).start();
            cards[i] = card;
        }
        for (int i = 0; i < n; i++) {
            Card c = cards[i];
            int self = c.getId();
            c.setNextFocusLeftId(i % COLS == 0 ? self : cards[i - 1].getId());
            c.setNextFocusRightId(i % COLS == COLS - 1 || i == n - 1 ? self : cards[i + 1].getId());
            c.setNextFocusUpId(i < COLS ? self : cards[i - COLS].getId());
            c.setNextFocusDownId(i + COLS >= n ? (i / COLS < (n - 1) / COLS ? cards[n - 1].getId() : self) : cards[i + COLS].getId());
        }
        setVisibility(VISIBLE);
        animate().cancel();
        setAlpha(0f);
        animate().alpha(1f).setDuration(260).setStartDelay(0).setInterpolator(Ui.SOFT).withEndAction(null).start();
        title.setAlpha(0f);
        title.setTranslationY(Ui.dp(12));
        title.animate().alpha(1f).translationY(0f).setDuration(420).setInterpolator(Ui.EMPHASIZED).start();
        grid.animate().cancel();
        grid.setScaleX(0.88f);
        grid.setScaleY(0.88f);
        grid.animate().scaleX(1f).scaleY(1f).setDuration(520).setInterpolator(Ui.SPRING).start();
        if (n > 0) cards[0].requestFocus();
    }

    void close() {
        if (!shown) return;
        shown = false;
        openId = null;
        grid.animate().scaleX(0.92f).scaleY(0.92f).setDuration(200).setInterpolator(Ui.SOFT).start();
        animate().cancel();
        animate().alpha(0f).setDuration(200).setStartDelay(0).setInterpolator(Ui.SOFT)
                .withEndAction(() -> {
                    if (shown) return;
                    setVisibility(GONE);
                    grid.removeAllViews();
                }).start();
    }
}
