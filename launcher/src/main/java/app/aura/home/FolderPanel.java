package app.aura.home;

import android.app.Activity;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
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
    private final LinearLayout col;
    private final android.widget.ScrollView scroll;
    private final LinearLayout grid;
    private final GlassFrame.Way way;
    private final GlassFrame.Hold hold;
    private final Runnable gone;
    private boolean shown;
    /** The first tile, which takes the focus once the folder is there. */
    private Card first;
    /** Where the folder's own tile was, on this panel (HIGH zooms out of it); negative for the middle. */
    private float fromX = -1, fromY;
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
        col = new LinearLayout(a);
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
        // the pivot follows the content's place, which is known only once it's laid out
        col.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> pivot());
        // HIGH: out of its tile from ×0.6 on the spring; LOW: a cross-fade with a 16 dp rise
        way = new GlassFrame.Way(col, p -> {
            setAlpha(Ui.clamp(p, 0f, 1f));
            float s = Ui.light ? 1f : 0.6f + 0.4f * p;
            col.setScaleX(s);
            col.setScaleY(s);
            col.setTranslationY(Ui.light ? Ui.dpf(16) * (1f - p) : 0f);
        });
        hold = new GlassFrame.Hold(this, () -> {
            if (first != null) first.requestFocus();
        });
        gone = () -> {
            if (shown) return;
            setVisibility(GONE);
            grid.removeAllViews();
        };
    }

    boolean isOpen() {
        return shown;
    }

    /** Its fades go straight onto the glass and the tiles: no full-screen offscreen pass each frame. */
    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    void open(Folders.Folder f, List<AppRepo.App> apps) {
        // open already, made again in place (an app came or went): no second entrance
        boolean again = shown;
        if (!again) from(act.getWindow().getCurrentFocus());
        shown = true;
        openId = f.id;
        title.setText(f.name);
        grid.removeAllViews();
        scroll.scrollTo(0, 0);
        LinearLayout row = null;
        int n = apps.size();
        Card[] cards = new Card[n];
        // HIGH: the tiles fade in 15 ms apart as the folder grows
        boolean stagger = !again && !Ui.light;
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
            card.setContentDescription(a.label);
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
            if (stagger) {
                cell.setAlpha(0f);
                cell.animate().alpha(1f).setStartDelay(i * 15L).setDuration(Ui.SHEET_CONTENT_AT).setInterpolator(Ui.EASE_DECEL).start();
            }
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
        first = n > 0 ? cards[0] : null;
        setDescendantFocusability(FOCUS_BEFORE_DESCENDANTS);
        setVisibility(VISIBLE);
        if (again) {
            // the panel itself may still hold the focus as it comes in: the first tile gets it then
            if (first != null && !isFocused()) first.requestFocus();
            return;
        }
        pivot();
        way.in(Ui.LOW_SHEET_IN, null);
        hold.start();
        setContentDescription(f.name);
        announceForAccessibility(f.name);
    }

    void close() {
        if (!shown) return;
        shown = false;
        openId = null;
        first = null;
        hold.stop();
        // fading out, its tiles take no focus: the arrows from the folder's own tile can't land on them
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        way.out(gone);
    }

    /** The tile it opens from: the one in focus, on the home screen (opened from a menu, it grows from the middle). */
    private void from(View focus) {
        fromX = -1;
        if (!(focus instanceof Card) || inside(focus)) return;
        int[] at = new int[2], me = new int[2];
        focus.getLocationInWindow(at);
        getLocationInWindow(me);
        // the window position already includes the tile's focus scale
        fromX = at[0] - me[0] + focus.getWidth() * focus.getScaleX() / 2f;
        fromY = at[1] - me[1] + focus.getHeight() * focus.getScaleY() / 2f;
    }

    private boolean inside(View v) {
        for (ViewParent p = v.getParent(); p != null; p = p.getParent()) if (p == this) return true;
        return false;
    }

    private void pivot() {
        col.setPivotX(fromX < 0 ? col.getWidth() / 2f : fromX - col.getLeft());
        col.setPivotY(fromX < 0 ? col.getHeight() / 2f : fromY - col.getTop());
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        return hold.down(code) || super.onKeyDown(code, e);
    }

    @Override
    public boolean onKeyUp(int code, KeyEvent e) {
        return hold.up(code) || super.onKeyUp(code, e);
    }

    @Override
    protected void onFocusChanged(boolean gained, int direction, Rect previous) {
        super.onFocusChanged(gained, direction, previous);
        if (!gained) hold.stop();
    }
}
