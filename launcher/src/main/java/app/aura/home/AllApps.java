package app.aura.home;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * Every app on one screen, as on Apple TV: the categories as tabs across the top, a pill gliding
 * under the one chosen, and its apps as a grid on frosted glass. It rises from below when Down is
 * pressed past home's last row in the tvOS style. Left and Right pick a category, Down goes into
 * its apps, Up from the top row goes back to the tabs, and Up from the tabs or Back closes it.
 */
final class AllApps extends FrameLayout {
    interface Host {
        Backdrop backdrop();

        void launch(AppRepo.App a, View from);

        /** Up from the tabs: home asked to close the screen. */
        void close();
    }

    /** A category and its apps, in home's order. */
    static final class Tab {
        final String id, title;
        final List<AppRepo.App> apps;

        Tab(String id, String title, List<AppRepo.App> apps) {
            this.id = id;
            this.title = title;
            this.apps = apps;
        }
    }

    static final int COLS = 6, W = 124, H = 70, GAP = 22, ROW_GAP = 18;
    /** A tab taken by focus waits this long before its apps come in, so sweeping across the tabs builds nothing on the way. */
    static final long PICK_MS = 160;
    /** HIGH: tiles come in one after another this many ms apart as the screen rises... */
    static final int STAGGER = 18;
    /** ...the first this many of them, about a screenful; the rest are off screen anyway. */
    static final int STAGGERED = 18;

    private final Activity act;
    private final Host host;
    /** The tabs in a row, the pill drawn beneath them. */
    private final Tabs tabRow;
    private final ScrollView scroll;
    private final GlassFrame.Way way;
    private final GlassFrame.Hold hold;
    private final Runnable gone;
    /** The tiles' pictures, painted one at a time behind the screen, top rows first: opening never waits on them. */
    private final ExecutorService painter = Compat.background("all-apps", android.os.Process.THREAD_PRIORITY_DEFAULT + 4);
    private final List<TextView> tabViews = new ArrayList<>();
    private final Map<String, Grid> grids = new HashMap<>();
    private List<Tab> tabs = new ArrayList<>();
    private boolean shown;
    /** The tab whose apps are on show. */
    int current = -1;
    /** The tab focus has moved to comes in once it rests there. */
    private final Runnable pick = () -> show(focusedTab(), true);

    /** A category's apps laid out once, kept while the screen is open: going back to a tab costs nothing. */
    private static final class Grid {
        final LinearLayout view;
        final Card[] cards;

        Grid(LinearLayout view, Card[] cards) {
            this.view = view;
            this.cards = cards;
        }
    }

    AllApps(Activity a, Host h) {
        super(a);
        act = a;
        host = h;
        setVisibility(GONE);
        GlassFrame veil = new GlassFrame(a, h.backdrop(), 0, 0.62f);
        veil.edgeLight = false;
        addView(veil, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(0, Ui.dp(Ui.TEXT_SAFE_Y + 6), 0, 0);

        // the tabs, centred
        tabRow = new Tabs(a);
        col.addView(tabRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // the grid scrolls in a window of its own, under the tabs: a tile scrolled up never passes over them
        scroll = new ScrollView(a);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(OVER_SCROLL_NEVER);
        scroll.setClipToPadding(false);
        // room for the tile in focus to grow at the edges, and for the name beneath the last row
        scroll.setPadding(Ui.dp(24), Ui.dp(22), Ui.dp(24), Ui.dp(48));
        if (!Ui.light) {
            // HIGH: the apps fade out under the tabs; LOW keeps away from the offscreen pass it costs
            scroll.setVerticalFadingEdgeEnabled(true);
            scroll.setFadingEdgeLength(Ui.dp(28));
        }
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 0, 1f);
        sl.topMargin = Ui.dp(6);
        col.addView(scroll, sl);
        addView(col, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // HIGH: up from below on the spring; LOW: a cross-fade with a short rise
        way = new GlassFrame.Way(col, p -> {
            setAlpha(Ui.clamp(p, 0f, 1f));
            col.setTranslationY(Ui.dpf(Ui.light ? 24 : 64) * (1f - p));
        });
        hold = new GlassFrame.Hold(this, () -> {
            if (!focusApps() && !tabViews.isEmpty()) tabViews.get(0).requestFocus();
        });
        gone = () -> {
            if (shown) return;
            setVisibility(GONE);
            scroll.removeAllViews();
            grids.clear();
        };
    }

    boolean isOpen() {
        return shown;
    }

    /** Home is going for good: pictures still queued are dropped. */
    void release() {
        painter.shutdown();
    }

    /** Its fades go straight onto the glass and the tiles: no full-screen offscreen pass each frame. */
    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    /**
     * Opens on the first tab, or makes it again in place (an app came or went), on the same tab
     * where it's still there.
     */
    void open(List<Tab> list) {
        boolean again = shown;
        String was = again && current >= 0 ? tabs.get(current).id : null;
        shown = true;
        tabs = list;
        grids.clear();
        scroll.removeAllViews();
        removeCallbacks(pick);
        current = -1;
        buildTabs();
        int at = 0;
        for (int i = 0; was != null && i < tabs.size(); i++) if (tabs.get(i).id.equals(was)) at = i;
        setDescendantFocusability(FOCUS_BEFORE_DESCENDANTS);
        setVisibility(VISIBLE);
        if (tabs.isEmpty()) return;
        show(at, false);
        if (again) {
            if ((!hasFocus() || findFocus() == this) && !focusApps()) tabViews.get(current).requestFocus();
            return;
        }
        if (!Ui.light) stagger(grid(current));
        way.in(Ui.LOW_SHEET_IN, null);
        hold.start();
        String title = act.getString(R.string.cat_all_apps);
        setContentDescription(title);
        announceForAccessibility(title);
    }

    void close() {
        if (!shown) return;
        shown = false;
        removeCallbacks(pick);
        hold.stop();
        // fading out, its tiles take no focus
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        way.out(gone);
    }

    private void buildTabs() {
        tabRow.removeAllViews();
        tabViews.clear();
        for (int i = 0; i < tabs.size(); i++) {
            final int k = i;
            TextView t = Ui.text(act, 15, Ui.semibold, 0xCCFFFFFF);
            t.setText(tabs.get(i).title);
            t.setSingleLine(true);
            t.setPadding(Ui.dp(18), Ui.dp(8), Ui.dp(18), Ui.dp(8));
            t.setFocusable(true);
            t.setId(View.generateViewId());
            t.setOnFocusChangeListener((v, gained) -> {
                paintTabs();
                if (!gained) return;
                tabRow.to(v, true);
                removeCallbacks(pick);
                if (k != current) postDelayed(pick, PICK_MS);
            });
            t.setOnClickListener(v -> commitPick());
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) l.leftMargin = Ui.dp(6);
            tabRow.addView(t, l);
            tabViews.add(t);
        }
        for (int i = 0; i < tabViews.size(); i++) {
            TextView t = tabViews.get(i);
            t.setNextFocusLeftId(i == 0 ? t.getId() : tabViews.get(i - 1).getId());
            t.setNextFocusRightId(i == tabViews.size() - 1 ? t.getId() : tabViews.get(i + 1).getId());
        }
    }

    /** The chosen tab dark on a white pill while the tabs have the focus, light on a faint one once it's in the apps. */
    private void paintTabs() {
        boolean onTabs = focusedTab() >= 0;
        tabRow.lit(onTabs);
        for (int i = 0; i < tabViews.size(); i++) {
            TextView t = tabViews.get(i);
            boolean chosen = onTabs ? t.isFocused() : i == current;
            t.setTextColor(chosen && onTabs ? 0xFF1C1C1E : chosen ? 0xFFFFFFFF : 0x99FFFFFF);
        }
    }

    /** The tab in focus comes in now, not after its rest (OK or Down on it). */
    private void commitPick() {
        removeCallbacks(pick);
        show(focusedTab(), true);
    }

    /** The first app on show takes the focus; false when there's none. */
    private boolean focusApps() {
        Grid g = current >= 0 ? grid(current) : null;
        return g != null && g.cards.length > 0 && g.cards[0].requestFocus();
    }

    /** Tab {@code k}'s apps on show; sliding in from the side it lies on, on HIGH. */
    private void show(int k, boolean animate) {
        if (k < 0 || k >= tabs.size() || k == current) return;
        int dir = Integer.signum(k - current);
        current = k;
        // LOW keeps one tab's pictures, not one set for every tab visited
        if (Ui.light) grids.keySet().retainAll(java.util.Collections.singleton(tabs.get(k).id));
        Grid g = grid(k);
        scroll.removeAllViews();
        // every tab on the same six columns: a short one lines up on the left, as All apps does, not centred
        scroll.addView(g.view, new FrameLayout.LayoutParams(Ui.dp(COLS * W + (COLS - 1) * GAP), ViewGroup.LayoutParams.WRAP_CONTENT));
        scroll.scrollTo(0, 0);
        // opening: straight to its tab (it may still sit where the last visit left it)
        tabRow.to(tabViews.get(k), animate);
        paintTabs();
        scroll.animate().cancel();
        if (animate && !Ui.light) {
            // the window, not the grid (which can be many screens tall), takes the layer
            scroll.setAlpha(0f);
            scroll.setTranslationX(Ui.dpf(28) * dir);
            scroll.animate().alpha(1f).translationX(0f).setStartDelay(0).setDuration(Ui.CONTENT_FOCUS_IN).setInterpolator(Ui.EASE_DECEL).withLayer().start();
        } else {
            scroll.setAlpha(1f);
            scroll.setTranslationX(0f);
        }
    }

    private Grid grid(int k) {
        Tab tab = tabs.get(k);
        Grid g = grids.get(tab.id);
        if (g == null) {
            g = build(tab, tabViews.get(k));
            grids.put(tab.id, g);
            paint(g.cards);
        }
        return g;
    }

    private Grid build(Tab tab, TextView up) {
        LinearLayout grid = new LinearLayout(act);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        int n = tab.apps.size();
        Card[] cards = new Card[n];
        String style = Prefs.tileStyle(act);
        boolean tvos = Prefs.tvos(act) || style.equals("tvos"), glass = style.equals("glass");
        LinearLayout row = null;
        for (int i = 0; i < n; i++) {
            if (i % COLS == 0) {
                row = new LinearLayout(act);
                row.setClipChildren(false);
                LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (i > 0) rl.topMargin = Ui.dp(ROW_GAP);
                grid.addView(row, rl);
            }
            final AppRepo.App a = tab.apps.get(i);
            // home's own tile: the name only under the app in focus, riding down as it grows, as on Apple TV
            Cell cell = new Cell(act, Tiles.forStyle(a, style), a.color, a.label, a.pkg, W, H);
            cell.setNameMode("focus", false);
            final Card card = cell.card;
            card.setId(View.generateViewId());
            if (glass && a.art == null) card.glass = host.backdrop();
            // as on home: every look but glass covers the tile, so LOW paints it in 16 bits
            card.opaque = card.glass == null && (a.art == null || !a.art.hasAlpha());
            card.deferred = true;
            card.tvos = tvos;
            card.sway = !Ui.light;
            // grows no further than halfway into the gap: never over its neighbours
            card.setGrowRoom(GAP / 2f);
            card.onClick = () -> host.launch(a, card);
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i % COLS > 0) cl.leftMargin = Ui.dp(GAP);
            row.addView(cell, cl);
            cards[i] = card;
        }
        for (int i = 0; i < n; i++) {
            Card c = cards[i];
            int self = c.getId();
            c.setNextFocusLeftId(i % COLS == 0 ? self : cards[i - 1].getId());
            c.setNextFocusRightId(i % COLS == COLS - 1 || i == n - 1 ? self : cards[i + 1].getId());
            c.setNextFocusUpId(i < COLS ? up.getId() : cards[i - COLS].getId());
            // the row below is short: down to its last app; the last row: nowhere
            c.setNextFocusDownId(i + COLS >= n ? (i / COLS < (n - 1) / COLS ? cards[n - 1].getId() : self) : cards[i + COLS].getId());
        }
        return new Grid(grid, cards);
    }

    /** Each card's picture painted on the painter thread in grid order (top rows first), then handed over here. */
    private void paint(Card[] cards) {
        for (final Card card : cards) {
            final int[] size = card.size();
            if (size == null) continue;
            final Tiles.Painter p = card.painter();
            final float res = card.res;
            final boolean opaque = card.opaque;
            painter.execute(() -> {
                Bitmap b = null;
                try {
                    b = Card.paintBehind(p, size[0], size[1], res, opaque);
                } catch (RuntimeException e) {
                    Trouble.caught("painting an All Apps tile", e);
                }
                final Bitmap out = b;
                post(() -> {
                    if (card.painter() != p || !card.wantsArt()) return;
                    if (out != null) card.take(out);
                    else card.starve();
                });
            });
        }
    }

    /** HIGH: the first screenful of apps rises in one after another as the screen comes up. */
    private static void stagger(Grid g) {
        for (int i = 0; i < Math.min(STAGGERED, g.cards.length); i++) {
            View cell = (View) g.cards[i].getParent();
            cell.setAlpha(0f);
            cell.setTranslationY(Ui.dpf(16));
            cell.animate().alpha(1f).translationY(0f).setStartDelay(60L + i * (long) STAGGER).setDuration(Ui.SHEET_CONTENT_AT)
                    .setInterpolator(Ui.EASE_DECEL).start();
        }
    }

    /** The tab in focus, or -1 when focus is in the apps (or anywhere else). */
    private int focusedTab() {
        for (int i = 0; i < tabViews.size(); i++) if (tabViews.get(i).isFocused()) return i;
        return -1;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (shown && e.getAction() == KeyEvent.ACTION_DOWN && focusedTab() >= 0) {
            int code = e.getKeyCode();
            if (code == KeyEvent.KEYCODE_DPAD_UP) {
                // above the tabs there's only home again; a held key doesn't carry out of the screen
                if (e.getRepeatCount() == 0) host.close();
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                // Down on a tab picked a moment ago: its apps come in now, and the focus goes to them
                commitPick();
                focusApps();
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
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

    /**
     * The row of tabs, with the pill drawn under the chosen one: its two ends glide on springs to the
     * next tab's place, keeping their speed as focus sweeps across, white while the tabs have the
     * focus, faint once it's in the apps. Drawn by the row itself, beneath its tabs: a separate view
     * laid over them would need a size of its own.
     */
    static final class Tabs extends LinearLayout {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final Spring.Mover left, right;
        private float top, bottom;
        private boolean lit;

        Tabs(Context c) {
            super(c);
            setOrientation(HORIZONTAL);
            setWillNotDraw(false);
            paint.setColor(0x33FFFFFF);
            left = new Spring.Mover(Spring.smooth(), 0.5f, 0f, v -> invalidate());
            right = new Spring.Mover(Spring.smooth(), 0.5f, 0f, v -> invalidate());
        }

        /** To {@code tab}'s place, gliding or there at once. */
        void to(View tab, boolean glide) {
            if (tab.getWidth() == 0) {
                // not laid out yet (the screen's first frame): once it is
                tab.addOnLayoutChangeListener(new OnLayoutChangeListener() {
                    @Override
                    public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                        v.removeOnLayoutChangeListener(this);
                        to(v, false);
                    }
                });
                return;
            }
            top = tab.getTop();
            bottom = tab.getBottom();
            if (glide && right.value() > left.value()) {
                left.to(tab.getLeft());
                right.to(tab.getRight());
            } else {
                left.jump(tab.getLeft());
                right.jump(tab.getRight());
            }
            invalidate();
        }

        void lit(boolean on) {
            if (lit == on) return;
            lit = on;
            paint.setColor(on ? 0xF2FFFFFF : 0x33FFFFFF);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float l = left.value(), rt = right.value();
            if (rt <= l) return;
            r.set(l, top, rt, bottom);
            float rad = (bottom - top) / 2f;
            c.drawRoundRect(r, rad, rad, paint);
        }
    }
}
