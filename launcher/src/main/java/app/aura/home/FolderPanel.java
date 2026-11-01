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
    private final LinearLayout grid;
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
        title = Ui.text(a, 40, Ui.heavy, 0xFFFFFFFF);
        title.setIncludeFontPadding(true);
        title.setLetterSpacing(-0.03f);
        col.addView(title);
        grid = new LinearLayout(a);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gl.topMargin = Ui.dp(34);
        col.addView(grid, gl);
        addView(col, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
    }

    boolean isOpen() {
        return getVisibility() == VISIBLE;
    }

    void open(Folders.Folder f, List<AppRepo.App> apps) {
        openId = f.id;
        title.setText(f.name);
        grid.removeAllViews();
        LinearLayout row = null;
        Card first = null;
        for (int i = 0; i < apps.size(); i++) {
            if (i % COLS == 0) {
                row = new LinearLayout(act);
                row.setClipChildren(false);
                LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (i > 0) rl.topMargin = Ui.dp(GAP);
                grid.addView(row, rl);
            }
            final AppRepo.App a = apps.get(i);
            final Card card = new Card(act, host.art(a), a.color, 14);
            if (host.glass() && a.art == null) card.glass = host.backdrop();
            card.tvos = Prefs.tvos(act) || Prefs.tileStyle(act).equals("tvos");
            card.sway = !Perf.light(act);
            card.onClick = () -> host.launch(a, card);
            card.onLongClick = () -> host.hold(a, card);
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(Ui.dp(W), Ui.dp(H));
            if (i % COLS > 0) cl.leftMargin = Ui.dp(GAP);
            row.addView(card, cl);
            card.setAlpha(0f);
            card.setTranslationY(Ui.dp(10));
            card.animate().alpha(1f).translationY(0f).setStartDelay(i * 25L).setDuration(380).setInterpolator(Ui.EMPHASIZED).start();
            if (first == null) first = card;
        }
        setVisibility(VISIBLE);
        setAlpha(0f);
        animate().alpha(1f).setDuration(260).setStartDelay(0).setInterpolator(Ui.SOFT).start();
        title.setAlpha(0f);
        title.setTranslationY(Ui.dp(12));
        title.animate().alpha(1f).translationY(0f).setDuration(420).setInterpolator(Ui.EMPHASIZED).start();
        grid.setScaleX(0.88f);
        grid.setScaleY(0.88f);
        grid.animate().scaleX(1f).scaleY(1f).setDuration(520).setInterpolator(Ui.SPRING).start();
        if (first != null) first.requestFocus();
    }

    void close() {
        if (!isOpen()) return;
        openId = null;
        grid.animate().scaleX(0.92f).scaleY(0.92f).setDuration(200).setInterpolator(Ui.SOFT).start();
        animate().alpha(0f).setDuration(200).setStartDelay(0).setInterpolator(Ui.SOFT)
                .withEndAction(() -> {
                    setVisibility(GONE);
                    grid.removeAllViews();
                }).start();
    }
}
