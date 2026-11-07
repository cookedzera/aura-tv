package app.aura.home;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * Every app on one screen, as on Apple TV: the categories as tabs in a glass capsule across the
 * top, a pill gliding under the one chosen, and its apps five to a row, the size of home's own
 * tiles. It rises from below when Down is pressed past home's last row in the tvOS style. Left and
 * Right pick a category (the apps slide out one way and the next in from the other), Down goes into
 * its apps, the grid glides on a spring to keep the row in focus in view, Up from the top row goes
 * back to the tabs, and Up from the tabs or Back closes it. On HIGH a soft light in the colour of
 * the app in focus follows it across the glass.
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

    /** Home's own tile size, five to a row: the same apps look the same on both screens. */
    static final int COLS = 5, W = 148, H = 84, GAP = 28, ROW_GAP = 18;
    /** dp: room above the first row for its tile in focus to grow. */
    static final int TOP = 22;
    /** A tab taken by focus waits this long before its apps come in, so sweeping across the tabs builds nothing on the way. */
    static final long PICK_MS = 160;
    /** The apps on show slide out over this, the next tab's in over IN_MS. */
    static final int OUT_MS = 110, IN_MS = 280;
    /** LOW: how long the screen takes to rise (HIGH rides the spring). */
    static final int LOW_OPEN = 300;
    /** Opening: each row comes this many ms after the one above, the first this many rows. */
    static final int ROW_STAGGER = 55, STAGGERED_ROWS = 4;

    private final Activity act;
    private final Host host;
    /** The tabs in their capsule, the pill drawn beneath them. */
    private final Tabs tabRow;
    /** The apps' window under the tabs: the grid glides inside it, clipped to it. */
    private final FrameLayout viewport;
    /** HIGH: the light behind the app in focus, made the first time it's wanted; null until then. */
    private Glow glow;
    private final GlassFrame.Way way;
    private final GlassFrame.Hold hold;
    private final Runnable gone;
    /** The tiles' pictures, painted one at a time behind the screen, top rows first: opening never waits on them. */
    private final ExecutorService painter = Compat.background("all-apps", android.os.Process.THREAD_PRIORITY_DEFAULT + 4);
    private final List<TextView> tabViews = new ArrayList<>();
    private final Map<String, Grid> grids = new HashMap<>();
    private List<Tab> tabs = new ArrayList<>();
    private boolean shown;
    /** The tab whose apps are on show (or sliding in). */
    int current = -1;
    /** The tab focus has moved to comes in once it rests there. */
    private final Runnable pick = () -> show(focusedTab(), true);
    /** The grid in the window, and the next one while the last slides out. */
    private Grid installed, incoming;
    private int incomingDir;
    /** How far the grid has glided up, in px (its value). */
    private final Spring.Glide glide;
    /** The grid glides as long as home's own page does. */
    static final long GLIDE_MS = 450;

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
        // softer glass than a sheet's: the photograph still reads through it. Its own darkness is all
        // the shade it has: a second full-screen layer over it was drawn again with every frame, and
        // a 1 GB TV's GPU paid for each one
        GlassFrame veil = new GlassFrame(a, h.backdrop(), 0, 0.56f);
        veil.edgeLight = false;
        addView(veil, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(0, Ui.dp(Ui.TEXT_SAFE_Y + 4), 0, 0);
        tabRow = new Tabs(a);
        col.addView(tabRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        viewport = new FrameLayout(a) {
            /** The grid at its whole height, not cut to the window's: rows below glide into view whole. */
            @Override
            protected void measureChildWithMargins(View child, int ws, int wUsed, int hs, int hUsed) {
                MarginLayoutParams l = (MarginLayoutParams) child.getLayoutParams();
                if (l.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
                    super.measureChildWithMargins(child, ws, wUsed, hs, hUsed);
                    return;
                }
                child.measure(getChildMeasureSpec(ws, getPaddingLeft() + getPaddingRight() + l.leftMargin + l.rightMargin + wUsed, l.width),
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            }
        };
        // Android clips a view to its own bounds where its parent clips its children: a window that
        // clipped would cut the grid at its edges, and a tile in focus at either side with it. The
        // column clips the window instead, at the window's bounds: the whole width, under the tabs
        viewport.setClipChildren(false);
        LinearLayout.LayoutParams vl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        vl.topMargin = Ui.dp(10);
        col.addView(viewport, vl);
        addView(col, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        glide = new Spring.Glide(Spring.smooth(), GLIDE_MS, 1f, 0f, v -> {
            if (installed != null) installed.view.setTranslationY(Ui.dpf(TOP) - v);
        });
        // up from below, growing out of a touch smaller: on the spring on HIGH, eased on LOW
        way = new GlassFrame.Way(col, p -> {
            setAlpha(Ui.clamp(p, 0f, 1f));
            col.setTranslationY(Ui.dpf(Ui.light ? 40 : 56) * (1f - p));
            float s = 0.94f + 0.06f * p;
            col.setScaleX(s);
            col.setScaleY(s);
        });
        hold = new GlassFrame.Hold(this, () -> {
            if (!focusApps() && !tabViews.isEmpty()) tabViews.get(0).requestFocus();
        });
        gone = () -> {
            if (shown) return;
            setVisibility(GONE);
            viewport.removeAllViews();
            installed = incoming = null;
            if (glow != null) glow.off(false);
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
        // the same apps as last time: each tab's grid (and its tabs) as it was, nothing built or painted again
        boolean same = !again && same(tabs, list) && !grids.isEmpty();
        tabs = list;
        if (!same) grids.clear();
        viewport.animate().cancel();
        viewport.removeAllViews();
        installed = incoming = null;
        removeCallbacks(pick);
        current = -1;
        if (!same) buildTabs();
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
        rowsIn(installed);
        way.in(LOW_OPEN, null);
        hold.start();
        String title = act.getString(R.string.cat_all_apps);
        setContentDescription(title);
        announceForAccessibility(title);
    }

    /** The same tabs with the same apps (the very same entries), in the same order. */
    private static boolean same(List<Tab> a, List<Tab> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            Tab x = a.get(i), y = b.get(i);
            if (!x.id.equals(y.id) || !x.title.equals(y.title) || x.apps.size() != y.apps.size()) return false;
            for (int j = 0; j < x.apps.size(); j++) if (x.apps.get(j) != y.apps.get(j)) return false;
        }
        return true;
    }

    void close() {
        if (!shown) return;
        shown = false;
        removeCallbacks(pick);
        hold.stop();
        if (glow != null) glow.off(true);
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
            t.setPadding(Ui.dp(20), Ui.dp(8), Ui.dp(20), Ui.dp(8));
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
            if (i > 0) l.leftMargin = Ui.dp(2);
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
        settle();
        return installed != null && installed.cards.length > 0 && installed.cards[0].requestFocus();
    }

    /**
     * Tab {@code k}'s apps on show. Animated, the apps on show slide out toward the side it lies on
     * and its own slide in from the other (LOW: in at once, sliding the last bit of the way); at once
     * otherwise (opening, made again).
     */
    private void show(int k, boolean animate) {
        if (k < 0 || k >= tabs.size() || k == current) return;
        int dir = Integer.signum(k - current);
        current = k;
        Grid g = grid(k);
        tabRow.to(tabViews.get(k), animate);
        paintTabs();
        viewport.animate().cancel();
        if (!animate || installed == null) {
            incoming = null;
            viewport.setAlpha(1f);
            viewport.setTranslationX(0f);
            install(g);
            return;
        }
        incoming = g;
        incomingDir = dir;
        if (Ui.light) {
            settle();
            return;
        }
        viewport.animate().alpha(0f).translationX(-Ui.dpf(24) * dir).setStartDelay(0).setDuration(OUT_MS).setInterpolator(Ui.EASE_STANDARD)
                .withLayer().withEndAction(this::settle).start();
    }

    /**
     * The grid sliding in lands now: its slide-out cut short where something needs its apps at once.
     * HIGH fades it in on a layer; LOW only moves it (no layer the size of the window).
     */
    private void settle() {
        Grid g = incoming;
        if (g == null) return;
        incoming = null;
        viewport.animate().cancel();
        install(g);
        viewport.setTranslationX(Ui.dpf(36) * incomingDir);
        android.view.ViewPropertyAnimator in = viewport.animate().translationX(0f).setStartDelay(0).setDuration(IN_MS).setInterpolator(Ui.EASE_DECEL);
        if (!Ui.light) {
            viewport.setAlpha(0f);
            in.alpha(1f).withLayer();
        }
        in.start();
    }

    /** {@code g} in the window, from its top. */
    private void install(Grid g) {
        viewport.removeAllViews();
        FrameLayout.LayoutParams l = new FrameLayout.LayoutParams(Ui.dp(COLS * W + (COLS - 1) * GAP), ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL);
        viewport.addView(g.view, l);
        installed = g;
        // its sink puts the grid at its top
        glide.jump(0f);
        paint(g.cards);
    }

    private Grid grid(int k) {
        Tab tab = tabs.get(k);
        Grid g = grids.get(tab.id);
        if (g == null) {
            g = build(tab, tabViews.get(k));
            grids.put(tab.id, g);
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
        boolean tvos = Prefs.tvos(act) || style.equals("tvos"), glass = style.equals("glass"), lowEnd = Perf.lowEnd(act);
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
            card.setTag(a);
            if (glass && a.art == null) card.glass = host.backdrop();
            // as on home: every look but glass covers the tile, so LOW paints it in 16 bits
            card.opaque = card.glass == null && (a.art == null || !a.art.hasAlpha());
            card.deferred = true;
            card.tvos = tvos;
            // the slow tilt keeps the screen drawing for seconds after each move: not on a low-end TV,
            // even with HIGH chosen by hand
            card.sway = !Ui.light && !lowEnd;
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

    /**
     * The picture of each card that wants one (new, or let go while away) painted on the painter
     * thread in grid order (top rows first), then handed over here.
     */
    private void paint(Card[] cards) {
        for (final Card card : cards) {
            final int[] size = card.size();
            if (size == null || !card.wantsArt()) continue;
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

    /**
     * Opening: the first rows rise in one after another, by position only. The panel's own fade
     * carries them, so no layer is drawn inside the one the rising panel is on.
     */
    private static void rowsIn(Grid g) {
        for (int r = 0; r < Math.min(STAGGERED_ROWS, g.view.getChildCount()); r++) {
            View row = g.view.getChildAt(r);
            row.setTranslationY(Ui.dpf(28));
            row.animate().translationY(0f).setStartDelay(50L + r * (long) ROW_STAGGER).setDuration(320).setInterpolator(Ui.EASE_DECEL).start();
        }
    }

    /** The distance from one row's top to the next, in px: the tile, the name's room under it, the gap. */
    private float pitch() {
        View row = installed != null && installed.view.getChildCount() > 0 ? installed.view.getChildAt(0) : null;
        float tall = row != null && row.getHeight() > 0 ? row.getHeight() : Ui.dp(H + 30);
        return tall + Ui.dp(ROW_GAP);
    }

    /**
     * The grid glides to keep row {@code r} in view as on Apple TV: the first two rows from the top,
     * after that the row in focus always second from the top, the one above it whole above it. It
     * doesn't stop short at the last row: a stop short left a sliver of a row under the tabs.
     */
    private void scrollToRow(int r) {
        float to = Math.max(0f, (r - 1) * pitch());
        if (to == glide.target()) return;
        glide.to(to, Ui.scrollFloorMs(viewport, Math.abs(to - glide.value()), true));
    }

    /** Focus has moved inside: the grid glides to the app's row, and on HIGH the light follows it there. */
    @Override
    public void requestChildFocus(View child, View focused) {
        super.requestChildFocus(child, focused);
        if (installed == null || !(focused instanceof Card)) return;
        Card[] cards = installed.cards;
        for (int i = 0; i < cards.length; i++) {
            if (cards[i] != focused) continue;
            scrollToRow(i / COLS);
            light(i, ((AppRepo.App) focused.getTag()).color);
            return;
        }
    }

    /**
     * HIGH on a TV with the hardware for it: the light glides to app {@code i}'s tile, where it will
     * be once the grid has glided there, worked out from the layout (not from where things are
     * mid-slide or mid-rise). The tier is read now, as it can change while home runs. LOW, and HIGH
     * chosen by hand on a low-end TV, have no light: a wide gradient filled with every frame is the
     * fill a weak GPU can't spare.
     */
    private void light(int i, int color) {
        if (Ui.light || Perf.lowEnd(act)) {
            if (glow != null) glow.off(false);
            return;
        }
        if (glow == null) {
            glow = new Glow(act);
            // over the glass, under the tabs and the apps
            addView(glow, 1, new LayoutParams(Math.round(2 * Glow.R), Math.round(2 * Glow.R)));
        }
        float gridW = Ui.dp(COLS * W + (COLS - 1) * GAP);
        float x = viewport.getLeft() + (viewport.getWidth() - gridW) / 2f + (i % COLS) * Ui.dpf(W + GAP) + Ui.dpf(W) / 2f;
        float y = viewport.getTop() + Ui.dpf(TOP) - glide.target() + (i / COLS) * pitch() + Ui.dpf(H) / 2f;
        glow.to(x, y, color);
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
     * The row of tabs in a glass capsule, with the pill drawn under the chosen one: its two ends
     * glide on springs to the next tab's place, keeping their speed as focus sweeps across, white
     * while the tabs have the focus, faint once it's in the apps. Drawn by the row itself, beneath
     * its tabs: a separate view laid over them would need a size of its own.
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
            int in = Ui.dp(4);
            setPadding(in, in, in, in);
            paint.setColor(0x2EFFFFFF);
            // the capsule: a pane of light glass with a hairline round it, its own background
            GradientDrawable capsule = new GradientDrawable();
            capsule.setColor(0x1FFFFFFF);
            capsule.setStroke(Math.max(1, Ui.dp(0.75f)), 0x33FFFFFF);
            capsule.setCornerRadius(Ui.dpf(999));
            setBackground(capsule);
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
            paint.setColor(on ? 0xF7FFFFFF : 0x2EFFFFFF);
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

    /**
     * HIGH: a wide soft light in the colour of the app in focus, behind the grid, gliding after it
     * as focus moves, as Apple TV tints its glass from what's chosen. One white light at its own
     * size, drawn once and tinted by a filter; the springs move the view itself, so nothing else on
     * screen is drawn again as it glides.
     */
    static final class Glow extends View {
        static final float R = Ui.dpf(340);
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        private final Spring.Mover x, y;
        private int color;
        private boolean on;

        Glow(Context c) {
            super(c);
            setAlpha(0f);
            paint.setShader(new RadialGradient(R, R, R, new int[]{0x52FFFFFF, 0x1FFFFFFF, 0x00FFFFFF}, new float[]{0f, 0.45f, 1f},
                    Shader.TileMode.CLAMP));
            x = new Spring.Mover(Spring.smooth(), 0.5f, 0f, v -> setTranslationX(v - R));
            y = new Spring.Mover(Spring.smooth(), 0.5f, 0f, v -> setTranslationY(v - R));
        }

        void to(float cx, float cy, int c) {
            if (on) {
                x.to(cx);
                y.to(cy);
            } else {
                on = true;
                x.jump(cx);
                y.jump(cy);
                setTranslationX(cx - R);
                setTranslationY(cy - R);
                animate().alpha(1f).setStartDelay(120).setDuration(420).setInterpolator(Ui.EASE_DECEL).start();
            }
            if (c != color) {
                color = c;
                paint.setColorFilter(new android.graphics.PorterDuffColorFilter(Ui.mix(c, 0xFFFFFFFF, 0.15f) | 0xFF000000,
                        android.graphics.PorterDuff.Mode.SRC_IN));
                invalidate();
            }
        }

        /** Its fade goes straight onto its one gradient: no offscreen pass. */
        @Override
        public boolean hasOverlappingRendering() {
            return false;
        }

        void off(boolean fade) {
            on = false;
            animate().cancel();
            if (fade) animate().alpha(0f).setStartDelay(0).setDuration(Ui.CLOSE).start();
            else setAlpha(0f);
        }

        @Override
        protected void onDraw(Canvas c) {
            c.drawCircle(R, R, R, paint);
        }
    }
}
