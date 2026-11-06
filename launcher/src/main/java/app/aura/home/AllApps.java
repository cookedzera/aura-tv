package app.aura.home;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * All apps: every app on the TV in category rows (Favorites, the rows that fill themselves,
 * your own, All, then Hidden) scrolling above a glass dock of folders, on the tvOS 26 Home grid with
 * Android's own 16:9 banners: six 126 x 71 dp tiles a row, 24 dp apart, two rows and the next row's
 * title above the dock. It is a view over Aura's own stores (rows from {@link Categories}, your
 * order from {@link AppRepo}, folders from {@link Folders}, hidden apps from {@link Prefs}) and
 * changes them only through their own calls, the ones home's menus and Move already make. Home
 * opens and closes it and hands it the remote's keys ({@link #key}).
 */
final class AllApps extends FrameLayout {
    interface Host {
        /** The apps on the TV, A to Z, hidden ones included (the page leaves them out). */
        List<AppRepo.App> apps();

        /** False until home has read the apps once: neutral tiles stand in meanwhile. */
        boolean ready();

        /** An app's tile in the look chosen: a new painter each call. */
        Tiles.Painter art(AppRepo.App a);

        Backdrop backdrop();

        void launch(AppRepo.App a, View from);

        /** App info and Uninstall: Android's own screens. */
        void open(Intent intent, View from);

        /** Rows, folders or hidden apps changed here: home rebuilds behind the page. */
        void changed();

        void hud(int icon, String text);

        /** Find an app, with {@code typed} already in it. */
        void find(String typed);

        void controlCenter();

        /** Settings, Hidden apps: behind the parents' PIN in a kids' profile. */
        void hiddenApps();

        /** The page closed itself: home takes the focus back. */
        void closed();
    }

    /** What {@link #key} did with a press: not its key (home carries on), all done, or one for the view in focus. */
    static final int PASS = 0, DONE = 1, VIEW = 2;

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT, WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    /** dp on the 960 x 540 canvas: the rows' window between the header and the dock, the first title, the dock. */
    private static final float VIEW_TOP = 57f, VIEW_BOTTOM = 387f, TITLE_Y = 68f, TITLE_TO_TILE = 35f, DOCK_Y = 399f;
    /** The focused row's title moves up as far as its tile grows at x1.20, and is 90 % white against 60 %. */
    private static final float TITLE_LIFT = 7f, TITLE_ON = 0.9f, TITLE_OFF = 0.6f;
    /** The lengths of Spring.row() and Spring.smooth(). */
    private static final long ROW_MS = 350, SMOOTH_MS = 450;
    /** How far Spring.row() trails a target moving at a steady speed (2ζ/ω, in ms of that travel). */
    private static final float ROW_TRAIL_MS = 95f;
    private static final int ROWS = 0, DOCK = 1, HEAD = 2;
    private static final String HIDDEN_ROW = "hidden";
    /** Tiles about to come on screen are painted here, one at a time, just below the screen's priority. */
    private static final ExecutorService PAINTER = Compat.background("all-apps", android.os.Process.THREAD_PRIORITY_DEFAULT + 2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** A stand-in while the apps are read: 8 % white, never a spinner. */
    private static final Tiles.Painter WAITING = (c, w, h) -> c.drawColor(0x14FFFFFF);

    /** One tile's worth: an app, a folder, an empty row's "Add apps", the hidden apps, or a stand-in while loading. */
    private static final class Item {
        static final int APP = 0, FOLDER = 1, GHOST = 2, HIDDEN = 3, WAIT = 4;
        final int kind;
        final String tag;
        final AppRepo.App app;
        final Folders.Folder folder;
        final List<AppRepo.App> inside;
        final int count;

        Item(int kind, String tag, AppRepo.App app, Folders.Folder folder, List<AppRepo.App> inside, int count) {
            this.kind = kind;
            this.tag = tag;
            this.app = app;
            this.folder = folder;
            this.inside = inside;
            this.count = count;
        }

        boolean real() {
            return kind == APP || kind == FOLDER;
        }
    }

    /** A row: its category (null for Hidden), its tiles, where it's slid to, and the tile it last had. */
    private static final class Row {
        final String id, title;
        final Categories.Cat cat;
        final List<Item> items = new ArrayList<>();
        /** Its tiles made just now (the window), left to right. */
        final List<Tile> cells = new ArrayList<>();
        int index, first, col;
        float shift, lit = 1f, litFrom = 1f, litTo = 1f;
        Spring.Glide glide;
        TextView label;
        /** The card focus is on in this row (or last was) and the one before it: the title rides their growth. */
        Card lead, prev;

        Row(String id, String title, Categories.Cat cat) {
            this.id = id;
            this.title = title;
            this.cat = cat;
        }
    }

    /** A tile on screen: its cell, and the row and column it stands at now. */
    private static final class Tile {
        final String key, sig;
        final Cell cell;
        Row row;
        Item item;
        int col, pass;
        /** Its picture has been asked of the painting thread. */
        boolean asked;

        Tile(String key, String sig, Cell cell) {
            this.key = key;
            this.sig = sig;
            this.cell = cell;
        }
    }

    private final Activity act;
    private final Host host;
    private final GlassFrame veil;
    private final FrameLayout content, page;
    private final TextView count;
    private final Pad search, cc;
    private final FolderDock dock;
    private final FolderPanel sheet;
    private final AppMenu menu;
    private final Ask ask;
    private final GlassFrame.Way way;
    private final Give give = new Give();
    private final List<Row> rows = new ArrayList<>();
    private final List<TextView> titles = new ArrayList<>();
    private final Map<String, Tile> tiles = new HashMap<>();
    /** Tiles whose picture changed under them (a new name, a new look), kept until the focus has left them. */
    private final List<Tile> orphans = new ArrayList<>();
    /** The apps on the TV by package, as last read. */
    private final Map<String, AppRepo.App> known = new HashMap<>();
    private final Runnable rewindow = this::window;
    private final Runnable gone = () -> {
        if (isOpen()) return;
        setVisibility(GONE);
        release();
    };
    private GlassFrame empty;
    private Pad emptyButton;
    private boolean shown, held, dim, busy, opening, jumping, trimming, fresh, menuDim, covered;
    private int zone = ROWS, rowAt, headAt, pass, gen;
    private String style = "tvos", nameMode = "focus";
    /** The grid in dp: six 126 x 71 tiles at a 150 dp pitch from x 42, rows 146 dp apart; Large, five 150 x 84. */
    private int cols = 6, tileW = 126, tileH = 71;
    private float x0 = 42f, pitch = 150f, rowPitch = 146f, room = 12f;
    /** The page's travel down its rows, dp: where it is, and where it's heading. */
    private Spring.Glide scroll;
    private float scrollDp, scrollAim, glassAt;
    private long lastStep, lastRepeat;
    private float holdLead;
    private ValueAnimator litAnim, jiggle;
    /** rememberSource: the last move up or down, so the opposite key goes back exactly where it came from. */
    private Row srcFrom, srcTo;
    private int srcCol, srcToCol, srcDir;
    /** The tile the dock was entered from: Up and Back out of the dock return to it. */
    private String dockRow, dockTag;
    private int dockCol;
    /** Where the focus was when the page last closed: it opens there again. */
    private String lastRow, lastTag;
    private int lastCol;
    /** The folder whose sheet is open and the tile it opened from; what a menu or the name card is for. */
    private String sheetTag;
    private View sheetFrom, menuFor, askFor;
    /** Move: the tile being carried, and its row as it was before. */
    private Tile carry;
    private List<Item> carryWas;

    AllApps(Activity a, Host h) {
        super(a);
        act = a;
        host = h;
        setVisibility(GONE);
        setClipChildren(false);
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        Backdrop b = h.backdrop();
        // the photograph blurred and 25 % darker, from home's one pre-blurred copy
        veil = new GlassFrame(a, b, 0, 0.25f);
        veil.edgeLight = false;
        addView(veil, new LayoutParams(MATCH, MATCH));
        content = new FrameLayout(a) {
            // its fade goes straight onto its views: no offscreen copy of the page each frame
            @Override
            public boolean hasOverlappingRendering() {
                return false;
            }
        };
        content.setClipChildren(false);
        addView(content, new LayoutParams(MATCH, MATCH));
        Viewport viewport = new Viewport(a);
        content.addView(viewport, at(MATCH, Ui.dp(VIEW_BOTTOM - VIEW_TOP), 0f, VIEW_TOP));
        page = new FrameLayout(a);
        page.setClipChildren(false);
        viewport.addView(page, new LayoutParams(MATCH, MATCH));
        // LOW: the focused tile's shadow is one shared baked view beneath the tiles
        FocusLayer.attach(page, null);
        LinearLayout head = new LinearLayout(a);
        TextView title = Ui.text(a, Ui.HEADING, Ui.semibold, 0xFFFFFFFF);
        title.setText(R.string.cat_all_apps);
        head.addView(title);
        count = Ui.text(a, Ui.CAPTION, Ui.medium, 0x99FFFFFF);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(WRAP, WRAP);
        cl.leftMargin = Ui.dp(12);
        head.addView(count, cl);
        content.addView(head, at(WRAP, WRAP, 42f, 24f));
        search = button(R.drawable.ic_search, 32, 15, R.string.find_an_app, 842f, 24f, () -> h.find(""));
        cc = button(R.drawable.ic_tune, 34, 16, R.string.control_center_wi_fi_sound, 884f, 23f, h::controlCenter);
        dock = new FolderDock(a, b, new FolderDock.Listener() {
            @Override
            public void open(FolderDock.Entry e, Card from) {
                openFolder(e.folder, e.inside, from);
            }

            @Override
            public void hold(FolderDock.Entry e, Card from) {
                folderMenu(e.folder, null, from);
            }

            @Override
            public void focused(int i, boolean gained) {
                docked(gained);
            }
        });
        content.addView(dock, at(MATCH, Ui.dp(FolderDock.TRAY_H), 0f, DOCK_Y));
        sheet = new FolderPanel(a, new FolderPanel.Host() {
            @Override
            public Tiles.Painter art(AppRepo.App app) {
                return h.art(app);
            }

            @Override
            public boolean glass() {
                return "glass".equals(style);
            }

            @Override
            public Backdrop backdrop() {
                return h.backdrop();
            }

            @Override
            public void launch(AppRepo.App app, View from) {
                closeFolder();
                h.launch(app, from);
            }

            @Override
            public void hold(AppRepo.App app, Card from) {
                insideMenu(app, from);
            }
        });
        addView(sheet, new LayoutParams(MATCH, MATCH));
        menu = new AppMenu(a, b);
        // LOW draws no dim layer behind a menu: the tiles step back instead, all but the one it's for
        menu.behind = on -> {
            menuDim = on;
            for (int i = 0; i < rows.size(); i++) shade(rows.get(i));
        };
        addView(menu, new LayoutParams(MATCH, MATCH));
        ask = new Ask(a, b);
        addView(ask, new LayoutParams(MATCH, MATCH));
        // HIGH comes in a touch small on the snappy spring, LOW cross-fades in 200 ms; out in 150 on both
        way = new GlassFrame.Way(content, p -> {
            float v = Ui.clamp(p, 0f, 1f);
            veil.setAlpha(v);
            content.setAlpha(v);
            float s = Ui.light ? 1f : 0.94f + 0.06f * p;
            content.setScaleX(s);
            content.setScaleY(s);
        });
    }

    boolean isOpen() {
        return shown;
    }

    /** Opens on the tile it was last left on (or beside it, if that one's gone); the first time, the first row's first tile. */
    void open() {
        if (shown) return;
        shown = true;
        gen++;
        // Large (five 150 x 84 tiles) only when it was chosen in Settings: six a row is All apps' own size (decision 2)
        grid(Prefs.of(act).contains(Prefs.GRID) && Prefs.gridCols(act) == 5);
        style = Prefs.tileStyle(act);
        nameMode = Prefs.nameMode(act);
        dim = Categories.dim(act);
        zone = ROWS;
        held = false;
        srcFrom = null;
        dockRow = null;
        covered = false;
        setVisibility(VISIBLE);
        focusable();
        // LOW: the first frame is the glass and the tile in focus; the others are painted behind it and come up as it fades in
        opening = Ui.light;
        jumping = true;
        fresh = true;
        rebuild(lastRow, lastTag, lastCol, null);
        jumping = false;
        opening = false;
        way.jump(0f);
        way.in(Ui.LOW_SHEET_IN, null);
        announceForAccessibility(act.getString(R.string.cat_all_apps));
    }

    /** Closes the page (Back at the top, Home, the remote's Apps key again); home takes the focus back. */
    void close() {
        if (!shown) return;
        cancelEdit();
        if (menu.isOpen()) menu.hide();
        if (sheet.isOpen()) sheet.close();
        if (ask.isOpen()) ask.cancel();
        askFor = null;
        remember();
        shown = false;
        held = false;
        focusable();
        for (int i = 0; i < rows.size(); i++) if (rows.get(i).glide != null) rows.get(i).glide.stop();
        if (scroll != null) scroll.stop();
        give.stop();
        way.out(gone);
        host.closed();
    }

    /**
     * One of home's panels (Control Center, Settings, Find, a PIN card) is over the page: none of its
     * tiles may take the focus, which Android's own search hands to anything focusable beneath.
     */
    void cover(boolean on) {
        covered = on;
        focusable();
    }

    /** The page's tiles take focus only while it's open with nothing over it; its own name card keeps the rows out too. */
    private void focusable() {
        setDescendantFocusability(shown && !covered ? FOCUS_AFTER_DESCENDANTS : FOCUS_BLOCK_DESCENDANTS);
        content.setDescendantFocusability(ask.isOpen() ? FOCUS_BLOCK_DESCENDANTS : FOCUS_AFTER_DESCENDANTS);
    }

    /** Home's apps, folders or rows changed (an app installed, a profile switched): the page follows, the focus kept. */
    void refresh() {
        if (!shown || busy) return;
        cancelEdit();
        if (menu.isOpen()) menu.hide();
        rebuildHere();
        if (sheet.isOpen()) reopenSheet();
    }

    /** Home went behind an app: a Move is cancelled, a menu or the name card put away. */
    void pause() {
        cancelEdit();
        if (menu.isOpen()) menu.hide();
        if (ask.isOpen()) closeAsk();
        released();
    }

    /** Behind an app on LOW: only the tiles on screen keep their pictures; the rest come back as the remote moves. */
    void trim() {
        if (!shown) {
            release();
            return;
        }
        trimming = true;
        window();
        trimming = false;
    }

    /**
     * Home giving the focus back (its focusGrid: Settings, Find or Control Center closing over the
     * page) while the page is open: it comes back here, to where the page says it is.
     */
    void regain() {
        if (shown && !covered && !busy && !menu.isOpen() && !sheet.isOpen() && !ask.isOpen()) land();
    }

    /** Words typed on the phone: into the name card, when this page has it open. */
    boolean type(String text) {
        if (!shown || !ask.isOpen()) return false;
        ask.type(text);
        return true;
    }

    /**
     * A press of the remote while the page is open (key-downs; home calls {@link #released} as an
     * arrow comes up). Arrows, Back, OK, page keys and letters are the page's; Menu and the media
     * keys are home's unless a menu, folder or name card of the page's is open.
     */
    int key(KeyEvent e) {
        if (!shown || e.getAction() != KeyEvent.ACTION_DOWN) return PASS;
        int code = e.getKeyCode();
        boolean first = e.getRepeatCount() == 0;
        boolean shut = code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU;
        // the card closed on nothing typed, which tells no one
        if (askFor != null && !ask.isOpen()) {
            asked();
            if (findFocus() == null) land();
        }
        if (ask.isOpen()) {
            if (code == KeyEvent.KEYCODE_BACK) {
                if (first) closeAsk();
                return DONE;
            }
            return VIEW;
        }
        if (menu.isOpen()) {
            if (shut) {
                if (first) closeMenu();
                return DONE;
            }
            // the press that opened it, still held; or a step sideways out of the list
            if (Pad.isConfirm(code) && !first || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) return DONE;
            return VIEW;
        }
        if (sheet.isOpen()) {
            if (shut) {
                if (first) closeFolder();
                return DONE;
            }
            return VIEW;
        }
        if (findFocus() == null && (Pad.isArrow(code) || Pad.isConfirm(code))) {
            // the focus went somewhere off the page (a name card shut on nothing typed): it comes back first
            if (first) land();
            return DONE;
        }
        if (carry != null) return editKey(code, e);
        if (Pad.isArrow(code)) {
            arrow(code, e);
            return DONE;
        }
        if (code == KeyEvent.KEYCODE_BACK) {
            if (first) back();
            return DONE;
        }
        if (code == KeyEvent.KEYCODE_CHANNEL_UP || code == KeyEvent.KEYCODE_CHANNEL_DOWN
                || code == KeyEvent.KEYCODE_PAGE_UP || code == KeyEvent.KEYCODE_PAGE_DOWN) {
            if (first) pageKey(code);
            return DONE;
        }
        // a letter on a keyboard remote: straight into finding an app, the letter typed
        if (code >= KeyEvent.KEYCODE_A && code <= KeyEvent.KEYCODE_Z) {
            int ch = e.getUnicodeChar();
            if (first) host.find(ch > 0 ? String.valueOf((char) ch) : "");
            return DONE;
        }
        return Pad.isConfirm(code) ? VIEW : PASS;
    }

    /**
     * The arrow held down was let go: its row coasts on to the next whole column (never back) with the
     * tile in view, and on HIGH the tile it stopped on catches the light once.
     */
    void released() {
        if (!held) return;
        held = false;
        if (zone == ROWS && rowAt < rows.size()) {
            Row r = rows.get(rowAt);
            if (r.glide != null && r.glide.moving()) {
                float x = r.glide.value(), aim = r.glide.target();
                int first = r.first;
                if (aim > first && x > first) first = (int) Math.ceil(x - 0.01f);
                else if (aim < first && x < first) first = (int) Math.floor(x + 0.01f);
                r.first = Math.max(Math.max(0, r.col - cols + 1), Math.min(Math.min(r.col, Math.max(0, r.items.size() - cols)), first));
                slide(r, r.first);
            }
        }
        titlesLit();
        View f = findFocus();
        if (f instanceof Card && !Ui.light) ((Card) f).glint();
        window();
    }

    // ---- building -----------------------------------------------------------------------------

    /** The grid's measures: six 126 x 71 dp tiles at a 150 dp pitch; Large, five 150 x 84 at 182.5. */
    private void grid(boolean big) {
        cols = big ? 5 : 6;
        tileW = big ? 150 : 126;
        tileH = big ? 84 : 71;
        pitch = big ? 182.5f : 150f;
        x0 = big ? 40f : 42f;
        rowPitch = big ? 159f : 146f;
        room = (pitch - tileW) / 2f;
    }

    /**
     * The rows and the dock made again from Aura's stores, each row keeping its place and every tile
     * painted from the same thing kept as it is. Focus lands on {@code tag} in row {@code rowId}
     * (else at {@code col} there, else the row it was on), on the dock's {@code dockTag}, or on the
     * header, and is never left on nothing; while a folder or the name card is open it stays there.
     */
    private void rebuild(String rowId, String tag, int col, String dockTag) {
        Map<String, Row> before = new HashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            before.put(r.id, r);
            if (r.glide != null) r.glide.stop();
        }
        rows.clear();
        srcFrom = null;
        List<FolderDock.Entry> docked = new ArrayList<>();
        int apps = model(docked);
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            Row was = before.get(r.id);
            int n = r.items.size();
            r.index = i;
            r.first = was == null ? 0 : Math.min(was.first, Math.max(0, n - cols));
            r.col = was == null ? 0 : Math.min(was.col, n - 1);
            r.shift = r.first;
            if (was != null) r.lit = r.litFrom = r.litTo = was.lit;
        }
        rowAt = Math.max(0, Math.min(rowAt, rows.size() - 1));
        // as tall as its rows: the viewport draws it only inside its own bounds, which scroll with it
        int tall = Ui.dp(titleTop(rows.size()) + rowPitch);
        if (page.getLayoutParams().height != tall) {
            page.getLayoutParams().height = tall;
            page.requestLayout();
        }
        heading(apps, docked.size());
        titles();
        dock.show(docked, style, host.backdrop());
        showEmpty(rows.isEmpty());
        // the focus moves only when it's the page's to move: not out of a folder or the name card, nor
        // from Settings, Control Center or Find open over the page
        boolean ours = jumping || findFocus() != null || act.getWindow().getCurrentFocus() == null;
        if (!ours || sheet.isOpen() || ask.isOpen()) {
            window();
            dock.sweep();
            return;
        }
        if (zone == DOCK && dock.size() > 0) {
            int d = dock.indexOf(dockTag);
            focusDock(d >= 0 ? d : Math.min(dock.at(), dock.size() - 1));
        } else if (zone == HEAD) {
            focusHead(headAt);
        } else if (rows.isEmpty()) {
            zone = ROWS;
            if (emptyButton != null) emptyButton.requestFocus();
        } else {
            Row r = find(rowId);
            int c;
            if (r == null) {
                r = rows.get(rowAt);
                c = r.col;
            } else {
                int i = indexOf(r, tag);
                c = i >= 0 ? i : Math.max(0, Math.min(col, r.items.size() - 1));
            }
            // opening onto Favorites with no favorites yet: the first app instead, its ghost one Up away
            int firstApps = firstApps();
            if (fresh && r.id.equals(Categories.FAV) && !r.items.get(0).real() && firstApps >= 0) {
                r = rows.get(firstApps);
                c = 0;
            }
            focusTile(r, c);
        }
        // a second pass: the tiles the focus has just left behind go too
        window();
        dock.sweep();
    }

    /**
     * The rows in Categories' order, hidden rows left out, each with its apps in its own order and an
     * empty one holding its "Add apps" ghost; All as home's grid has it (your order, then the rest,
     * with your folders); Hidden last when any app is hidden. The dock gets the folders, in your order.
     * Returns how many apps are on show, or -1 while the apps are still being read.
     */
    private int model(List<FolderDock.Entry> docked) {
        List<AppRepo.App> apps = host.apps();
        known.clear();
        for (int i = 0; i < apps.size(); i++) known.put(apps.get(i).pkg, apps.get(i));
        if (apps.isEmpty() && !host.ready()) {
            for (Categories.Cat k : Categories.all(act)) {
                if (k.hidden) continue;
                Row r = new Row(k.id, Categories.name(act, k), k);
                for (int i = 0; i < cols; i++) r.items.add(new Item(Item.WAIT, "wait:" + i, null, null, null, 0));
                rows.add(r);
            }
            return -1;
        }
        Set<String> hidden = Prefs.hidden(act);
        List<String> installed = new ArrayList<>();
        int hiddenApps = 0;
        for (int i = 0; i < apps.size(); i++) {
            String p = apps.get(i).pkg;
            if (hidden.contains(p)) hiddenApps++;
            else installed.add(p);
        }
        if (installed.isEmpty()) return 0;
        Map<String, Folders.Folder> folders = Folders.all(act);
        Map<String, List<AppRepo.App>> in = new HashMap<>();
        Set<String> foldered = new HashSet<>();
        for (Folders.Folder f : folders.values()) {
            foldered.addAll(f.apps);
            in.put(f.id, inside(f, hidden));
        }
        List<String> order = AppRepo.order(Prefs.of(act));
        List<Item> all = new ArrayList<>();
        Set<String> placed = new HashSet<>();
        for (String tag : order) {
            if (tag.startsWith("folder:")) {
                Folders.Folder f = folders.get(tag.substring(7));
                if (f != null && !in.get(f.id).isEmpty() && placed.add(tag)) {
                    all.add(new Item(Item.FOLDER, tag, null, f, in.get(f.id), 0));
                    docked.add(new FolderDock.Entry(f, in.get(f.id)));
                }
            } else {
                AppRepo.App a = known.get(tag);
                if (a != null && !foldered.contains(tag) && !hidden.contains(tag) && placed.add(tag)) all.add(app(a));
            }
        }
        for (int i = 0; i < apps.size(); i++) {
            AppRepo.App a = apps.get(i);
            if (!foldered.contains(a.pkg) && !hidden.contains(a.pkg) && placed.add(a.pkg)) all.add(app(a));
        }
        for (Folders.Folder f : folders.values()) {
            if (!in.get(f.id).isEmpty() && placed.add(f.tag())) {
                all.add(new Item(Item.FOLDER, f.tag(), null, f, in.get(f.id), 0));
                docked.add(new FolderDock.Entry(f, in.get(f.id)));
            }
        }
        for (Categories.Cat k : Categories.all(act)) {
            if (k.hidden) continue;
            Row r = new Row(k.id, Categories.name(act, k), k);
            if (k.id.equals(Categories.ALL)) {
                r.items.addAll(all);
            } else {
                for (String p : Categories.members(act, k, order, installed)) {
                    AppRepo.App a = known.get(p);
                    if (a != null) r.items.add(app(a));
                }
            }
            // a row that fills itself (Movies & TV, Music...) with nothing in it stays off the page, so it never
            // pushes the apps below the first screen; an app's menu (Add to category) still lists it.
            // Favorites and your own rows stay, with a ghost tile to fill them
            if (r.items.isEmpty() && k.auto != null) continue;
            if (r.items.isEmpty()) r.items.add(new Item(Item.GHOST, "ghost:" + k.id, null, null, null, 0));
            rows.add(r);
        }
        if (hiddenApps > 0) {
            Row r = new Row(HIDDEN_ROW, act.getString(R.string.hidden), null);
            r.items.add(new Item(Item.HIDDEN, "hidden", null, null, null, hiddenApps));
            rows.add(r);
        }
        return installed.size();
    }

    private static Item app(AppRepo.App a) {
        return new Item(Item.APP, a.pkg, a, null, null, 0);
    }

    /** A folder's apps that are on the TV and not hidden, in the folder's order. */
    private List<AppRepo.App> inside(Folders.Folder f, Set<String> hidden) {
        List<AppRepo.App> out = new ArrayList<>();
        for (String p : f.apps) {
            AppRepo.App a = known.get(p);
            if (a != null && !hidden.contains(p)) out.add(a);
        }
        return out;
    }

    /** "48 apps · 6 folders" beside the page's title. */
    private void heading(int apps, int folders) {
        if (apps <= 0) {
            count.setText("");
            return;
        }
        String s = getResources().getQuantityString(R.plurals.n_apps, apps, apps);
        if (folders > 0) s += "  ·  " + getResources().getQuantityString(R.plurals.n_folders, folders, folders);
        count.setText(s);
    }

    /** One title a row, 19 dp semibold over its tiles, its count at half strength. */
    private void titles() {
        while (titles.size() < rows.size()) {
            TextView t = Ui.text(act, Ui.HEADLINE, Ui.semibold, 0xFFFFFFFF);
            // a row is said as focus comes into it (focusTile): its title stays quiet
            t.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            page.addView(t, new LayoutParams(WRAP, WRAP));
            titles.add(t);
        }
        for (int i = 0; i < titles.size(); i++) {
            TextView t = titles.get(i);
            t.animate().cancel();
            if (i >= rows.size()) {
                t.setVisibility(GONE);
                continue;
            }
            Row r = rows.get(i);
            r.label = t;
            t.setVisibility(VISIBLE);
            t.setText(title(r));
            t.setTranslationX(Ui.dpf(x0));
            t.setTranslationY(Ui.dpf(titleTop(i)));
            t.setAlpha(zone == ROWS && i == rowAt ? TITLE_ON : TITLE_OFF);
        }
    }

    private static CharSequence title(Row r) {
        int n = r.items.size();
        if (n == 0 || !r.items.get(0).real()) return r.title;
        SpannableString s = new SpannableString(r.title + "  ·  " + n);
        s.setSpan(new ForegroundColorSpan(0x80FFFFFF), r.title.length(), s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return s;
    }

    /** No app to show (every one hidden, an empty kids' profile): a glass card says so, with the way to choose some. */
    private void showEmpty(boolean on) {
        if (on && empty == null) {
            empty = new GlassFrame(act, host.backdrop(), Ui.R_PANEL, 0.45f);
            LinearLayout col = new LinearLayout(act);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER);
            TextView t = Ui.text(act, Ui.CALLOUT, Ui.semibold, 0xFFFFFFFF);
            t.setText(R.string.no_apps_yet);
            col.addView(t);
            emptyButton = new Pad(act, host.backdrop(), 17, 0.3f);
            TextView b = emptyButton.tint(Ui.text(act, 13, Ui.semibold, 0xFFFFFFFF), 1f);
            b.setText(R.string.choose_apps);
            b.setGravity(Gravity.CENTER);
            b.setPadding(Ui.dp(18), 0, Ui.dp(18), 0);
            emptyButton.addView(b, new LayoutParams(WRAP, MATCH, Gravity.CENTER));
            emptyButton.onClick = host::hiddenApps;
            emptyButton.setOnFocusChangeListener((v, has) -> {
                if (has) zone = ROWS;
            });
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(WRAP, Ui.dp(34));
            bl.topMargin = Ui.dp(16);
            col.addView(emptyButton, bl);
            empty.addView(col, new LayoutParams(MATCH, MATCH));
            content.addView(empty, at(Ui.dp(320), Ui.dp(120), 320f, 162f));
        }
        if (empty != null) empty.setVisibility(on ? VISIBLE : GONE);
    }

    // ---- the tiles near the view --------------------------------------------------------------

    /**
     * Makes the tiles near the view and lets the rest go, pictures and all (LOW memory): the
     * rows on screen and one beyond each side, and in each the columns on screen with their peeks,
     * plus one more each side on HIGH. Tiles on screen show at once; the others are painted on the
     * painting thread and come up when their picture is ready. The tile with the focus, or being
     * carried, is never let go.
     */
    private void window() {
        if (!shown) return;
        pass++;
        for (int i = 0; i < rows.size(); i++) rows.get(i).cells.clear();
        if (!rows.isEmpty()) {
            int pre = Ui.light || trimming ? 1 : 2;
            float lo = Math.min(scrollDp, scrollAim), hi = Math.max(scrollDp, scrollAim);
            int a = (int) Math.floor(lo / rowPitch + 0.01f), b = (int) Math.ceil(hi / rowPitch - 0.01f) + 1;
            int from = Math.max(0, trimming ? a : a - 1), to = Math.min(rows.size() - 1, trimming ? b : b + 1);
            for (int k = from; k <= to; k++) {
                Row r = rows.get(k);
                boolean near = k >= a && k <= b;
                int c0 = Math.min(r.first, (int) Math.floor(r.shift)), c1 = Math.max(r.first, (int) Math.ceil(r.shift)) + cols - 1;
                int n = r.items.size();
                for (int c = Math.max(0, c0 - pre); c <= Math.min(n - 1, c1 + pre); c++) {
                    want(r, c, near && c >= c0 - 1 && c <= c1 + 1 && !opening);
                }
            }
        }
        sweep();
    }

    private void want(Row r, int c, boolean now) {
        Item it = r.items.get(c);
        String key = r.id + "|" + it.tag, sig = sig(r, it);
        Tile t = tiles.get(key);
        if (t != null && !t.sig.equals(sig)) {
            // painted from something else now: a fresh tile, the old one going once the focus has left it
            tiles.remove(key);
            orphans.add(t);
            t = null;
        }
        if (t == null) {
            t = make(r, c, it, key, sig);
            tiles.put(key, t);
            place(t);
            shade(t);
        } else if (t.row != r || t.col != c || t.item != it) {
            t.row = r;
            t.col = c;
            t.item = it;
            place(t);
            shade(t);
        }
        t.pass = pass;
        r.cells.add(t);
        if (now) show(t);
        else if (t.cell.getVisibility() != VISIBLE) paintLater(t);
    }

    private void sweep() {
        View focus = findFocus();
        for (Iterator<Tile> i = tiles.values().iterator(); i.hasNext(); ) {
            Tile t = i.next();
            if (t.pass == pass || t == carry || t.cell.card == focus) continue;
            i.remove();
            drop(t);
        }
        for (int i = orphans.size() - 1; i >= 0; i--) {
            Tile t = orphans.get(i);
            if (t.cell.card == focus) continue;
            orphans.remove(i);
            drop(t);
        }
    }

    private void drop(Tile t) {
        Card c = t.cell.card;
        if (t.row != null) {
            if (t.row.lead == c) t.row.lead = null;
            if (t.row.prev == c) t.row.prev = null;
        }
        if (menuFor == c) menuFor = null;
        if (give.view == t.cell) give.stop();
        t.cell.animate().cancel();
        page.removeView(t.cell);
    }

    /** Every tile let go, with its picture: nothing is kept while the page is shut. */
    private void release() {
        gen++;
        for (Tile t : tiles.values()) drop(t);
        tiles.clear();
        for (int i = 0; i < orphans.size(); i++) drop(orphans.get(i));
        orphans.clear();
        // the apps as last read go too (their pictures with them): the next opening reads them again
        for (int i = 0; i < rows.size(); i++) {
            rows.get(i).cells.clear();
            rows.get(i).items.clear();
        }
        known.clear();
        dock.release();
    }

    private Tile make(Row r, int c, Item it, String key, String sig) {
        Cell cell = new Cell(act, art(it), color(it), name(it), it.tag, tileW, tileH);
        final Tile t = new Tile(key, sig, cell);
        t.row = r;
        t.col = c;
        t.item = it;
        cell.row = r.id;
        Card card = cell.card;
        card.setGrowRoom(room);
        // a dense grid: no sway, no parallax
        card.sway = false;
        card.tvos = !it.real() || "tvos".equals(style);
        if ("glass".equals(style) && (it.kind == Item.FOLDER || it.kind == Item.APP && it.app.art == null)) card.glass = host.backdrop();
        card.badge = it.kind == Item.APP && it.app.fresh;
        card.setContentDescription(said(t));
        cell.setNameMode(it.kind == Item.GHOST || it.kind == Item.WAIT ? "hidden" : nameMode, false);
        card.onClick = () -> click(t);
        if (it.kind != Item.WAIT && it.kind != Item.HIDDEN) card.onLongClick = () -> hold(t);
        final Card.FocusListener named = card.focusListener;
        card.focusListener = (cd, gained) -> {
            named.onFocus(cd, gained);
            focused(t, gained);
        };
        final Runnable ride = card.moved;
        card.moved = () -> {
            ride.run();
            lifted(t);
        };
        // shown at once when on screen, else once its picture is ready (show, paintLater)
        cell.setVisibility(GONE);
        page.addView(cell, new LayoutParams(Ui.dp(tileW), WRAP));
        return t;
    }

    private static void show(Tile t) {
        if (t.cell.getVisibility() != VISIBLE) t.cell.setVisibility(VISIBLE);
    }

    /** Its picture painted on the painting thread and handed to the card before it's laid out: no flash, nothing on the main thread. */
    private void paintLater(final Tile t) {
        if (t.asked) return;
        t.asked = true;
        final int g = gen;
        paintOff(art(t.item), Ui.dp(tileW), Ui.dp(tileH), b -> {
            if (g != gen || tiles.get(t.key) != t || t.cell.getVisibility() == VISIBLE) return;
            if (b != null) t.cell.card.ready = b;
            t.cell.setVisibility(VISIBLE);
        });
    }

    /** What a picture painted on the painting thread is handed to, on the main thread; null if it couldn't be made. */
    interface Landed {
        void got(Bitmap b);
    }

    /**
     * Paints {@code p} at {@code w} x {@code h} px off the main thread, starts its upload to the GPU
     * (Android 7 and later) and hands it over on the main thread. {@code p} must be a painter no
     * view draws with: painters keep their own paints and matrices.
     */
    static void paintOff(final Tiles.Painter p, final int w, final int h, final Landed then) {
        PAINTER.execute(() -> {
            Bitmap b;
            try {
                b = Bitmap.createBitmap(Math.max(1, w), Math.max(1, h), Bitmap.Config.ARGB_8888);
                p.paint(new Canvas(b), w, h);
                if (Build.VERSION.SDK_INT >= 24) b.prepareToDraw();
            } catch (OutOfMemoryError | RuntimeException e) {
                // the card paints itself as it's laid out, as everywhere else
                b = null;
            }
            final Bitmap done = b;
            MAIN.post(() -> then.got(done));
        });
    }

    private Tiles.Painter art(Item it) {
        switch (it.kind) {
            case Item.APP:
                return host.art(it.app);
            case Item.FOLDER:
                return FolderDock.painter(it.folder.name, it.inside, !"glass".equals(style));
            case Item.GHOST:
                return ghost(act.getString(R.string.add_apps));
            case Item.HIDDEN:
                return hiddenArt(act, it.count);
            default:
                return WAITING;
        }
    }

    private static int color(Item it) {
        return it.kind == Item.APP ? it.app.color : it.kind == Item.FOLDER ? 0xFF9AA6BA : 0xFF5A6070;
    }

    private String name(Item it) {
        switch (it.kind) {
            case Item.APP:
                return it.app.label;
            case Item.FOLDER:
                return it.folder.name;
            case Item.GHOST:
                return act.getString(R.string.add_apps);
            case Item.HIDDEN:
                return act.getString(R.string.hidden_apps);
            default:
                return "";
        }
    }

    /** What TalkBack says for a tile: the app's or folder's own name. */
    private String said(Tile t) {
        Item it = t.item;
        switch (it.kind) {
            case Item.APP:
                return it.app.label;
            case Item.FOLDER:
                return it.folder.name;
            case Item.GHOST:
                return act.getString(R.string.add_apps_to, t.row.title);
            case Item.HIDDEN:
                return act.getString(R.string.hidden_apps) + ", " + getResources().getQuantityString(R.plurals.n_apps, it.count, it.count);
            default:
                return t.row.title;
        }
    }

    /** What a tile was painted from: one painted from the same is kept as the page is made again. */
    private String sig(Row r, Item it) {
        switch (it.kind) {
            case Item.APP:
                return "a|" + style + "|" + System.identityHashCode(it.app) + "|" + it.app.label;
            case Item.FOLDER: {
                StringBuilder s = new StringBuilder("f|").append(style).append('|').append(it.folder.name);
                for (int i = 0; i < it.inside.size(); i++) s.append('|').append(System.identityHashCode(it.inside.get(i)));
                return s.toString();
            }
            case Item.GHOST:
                return "g|" + r.title;
            case Item.HIDDEN:
                return "h|" + it.count;
            default:
                return "w";
        }
    }

    // ---- placing ------------------------------------------------------------------------------

    /** dp from the top of the rows' window: row {@code k}'s title, and its tiles 35 dp below it. */
    private float titleTop(int k) {
        return TITLE_Y + k * rowPitch - VIEW_TOP;
    }

    private float tileTop(int k) {
        return titleTop(k) + TITLE_TO_TILE;
    }

    private void placeRow(Row r) {
        for (int i = 0; i < r.cells.size(); i++) place(r.cells.get(i));
    }

    /** A tile at its column as its row is slid, plus the give of one at an edge. Properties only. */
    private void place(Tile t) {
        Row r = t.row;
        boolean giving = give.view == t.cell;
        t.cell.setTranslationX(Ui.dpf(x0 + (t.col - r.shift) * pitch) + (giving ? give.dx : 0f));
        t.cell.setTranslationY(Ui.dpf(tileTop(r.index)) + (giving ? give.dy : 0f));
        // glass samples the photograph where it stands
        if (t.cell.card.glass != null) t.cell.card.invalidate();
    }

    private void shade(Row r) {
        for (int i = 0; i < r.cells.size(); i++) shade(r.cells.get(i));
    }

    private void shade(Tile t) {
        float a = t.row.lit;
        if (menuDim && t.cell.card != menuFor) a *= Ui.LOW_MENU_ROWS;
        t.cell.setAlpha(a);
    }

    /**
     * Rows without the focus recede to x0.6 by their tiles' alpha (Settings' Dim other rows):
     * eased over 250 ms on HIGH, at once on LOW or while a key is held. Every row recedes while the
     * dock has the focus; none while the header has it.
     */
    private void light(boolean eased) {
        boolean changed = false;
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            float to = !dim || zone == HEAD || zone == ROWS && i == rowAt ? 1f : Ui.DIM_OTHER_ROWS;
            changed |= r.litTo != to;
            r.litTo = to;
        }
        if (!changed && eased) return;
        if (!eased || Ui.light) {
            if (litAnim != null) litAnim.cancel();
            for (int i = 0; i < rows.size(); i++) {
                Row r = rows.get(i);
                r.lit = r.litTo;
                shade(r);
            }
            return;
        }
        for (int i = 0; i < rows.size(); i++) rows.get(i).litFrom = rows.get(i).lit;
        if (litAnim == null) {
            litAnim = ValueAnimator.ofFloat(0f, 1f);
            litAnim.setDuration(250);
            litAnim.setInterpolator(Ui.DECEL);
            litAnim.addUpdateListener(an -> {
                float k = an.getAnimatedFraction();
                for (int i = 0; i < rows.size(); i++) {
                    Row r = rows.get(i);
                    if (r.litFrom == r.litTo) continue;
                    r.lit = r.litFrom + (r.litTo - r.litFrom) * k;
                    shade(r);
                }
            });
        }
        litAnim.cancel();
        litAnim.start();
    }

    /** The focused row's title at 90 % white, the others at 60 %, over 200 ms (at once while a key is held). */
    private void titlesLit() {
        for (int i = 0; i < rows.size(); i++) {
            TextView t = rows.get(i).label;
            if (t == null) continue;
            float to = zone == ROWS && i == rowAt ? TITLE_ON : TITLE_OFF;
            if (t.getAlpha() == to) continue;
            t.animate().cancel();
            if (held) t.setAlpha(to);
            else t.animate().alpha(to).setDuration(200).setStartDelay(0).setInterpolator(Ui.EASE_STANDARD).start();
        }
    }

    /** A card in a row moved: its row's title rides the growth of the card focus is on, or has just left. */
    private void lifted(Tile t) {
        Row r = t.row;
        Card c = t.cell.card;
        if (r == null || r.label == null || c != r.lead && c != r.prev) return;
        float g = Math.max(growth(r.lead), growth(r.prev));
        r.label.setTranslationY(Ui.dpf(titleTop(r.index)) - Ui.dpf(TITLE_LIFT) * g);
    }

    private static float growth(Card c) {
        if (c == null) return 0f;
        float s = c.focusedScale();
        return s <= 1f ? 0f : Ui.clamp((c.getScaleY() - 1f) / (s - 1f), 0f, 1.2f);
    }

    // ---- focus --------------------------------------------------------------------------------

    /** Focus to row {@code r}'s tile {@code col}: the row slides and the page scrolls to show it, its tile made if it isn't. */
    private void focusTile(Row r, int col) {
        col = Math.max(0, Math.min(r.items.size() - 1, col));
        boolean entering = fresh || zone != ROWS || rowAt != r.index;
        fresh = false;
        zone = ROWS;
        rowAt = r.index;
        r.col = col;
        reveal(r, col);
        scrollRows(scrollFor(r.index));
        window();
        Tile t = tiles.get(r.id + "|" + r.items.get(col).tag);
        if (t != null) {
            show(t);
            // TalkBack: a row's title is said as focus comes into it, then each tile by its own name
            if (entering) t.cell.card.setContentDescription(r.title + ", " + said(t));
            Card c = t.cell.card;
            if (r.lead != c) {
                r.prev = r.lead;
                r.lead = c;
            }
            c.requestFocus();
        }
        light(!held);
        titlesLit();
    }

    private void focusDock(int d) {
        srcFrom = null;
        zone = DOCK;
        dock.focus(d);
        light(!held);
        titlesLit();
    }

    private void focusHead(int i) {
        headAt = Math.max(0, Math.min(1, i));
        zone = HEAD;
        (headAt == 0 ? search : cc).requestFocus();
        light(true);
        titlesLit();
    }

    /** Focus back to where the page says it is: its row's tile, the dock, the header, or the empty page's button. */
    private void land() {
        if (!shown) return;
        if (zone == DOCK && dock.size() > 0) focusDock(Math.min(dock.at(), dock.size() - 1));
        else if (zone == HEAD) focusHead(headAt);
        else if (!rows.isEmpty()) focusTile(rows.get(rowAt), rows.get(rowAt).col);
        else if (emptyButton != null && emptyButton.isShown()) emptyButton.requestFocus();
        else focusHead(0);
    }

    /** A tile took the focus. When it came by another way (a menu or folder closing, TalkBack), the page follows it. */
    private void focused(Tile t, boolean gained) {
        Card c = t.cell.card;
        if (!gained) {
            c.setContentDescription(said(t));
            // the focus went elsewhere mid-Move (Control Center, Home): nothing moves, nothing is saved
            if (t == carry) cancelEdit();
            return;
        }
        // a folder's sheet remaking its grid drops the focused card and Android hands the focus to a
        // tile under the sheet: the page's place stays where the sheet was opened from
        if (sheet.isOpen()) return;
        Row r = t.row;
        if (r.index >= rows.size() || rows.get(r.index) != r) return;
        if (r.lead != c) {
            r.prev = r.lead;
            r.lead = c;
        }
        if (zone == ROWS && rowAt == r.index && r.col == t.col) return;
        zone = ROWS;
        rowAt = r.index;
        r.col = t.col;
        srcFrom = null;
        reveal(r, t.col);
        scrollRows(scrollFor(r.index));
        window();
        light(!held);
        titlesLit();
    }

    private void docked(boolean gained) {
        if (!gained || zone == DOCK) return;
        zone = DOCK;
        srcFrom = null;
        light(true);
        titlesLit();
    }

    /** Remembered for the next opening: the tile the rows last had. */
    private void remember() {
        if (rowAt >= rows.size()) return;
        Row r = rows.get(rowAt);
        lastRow = r.id;
        lastCol = r.col;
        lastTag = r.col < r.items.size() ? r.items.get(r.col).tag : null;
    }

    // ---- scrolling ----------------------------------------------------------------------------

    /** The focused row's title on the first line (y 68); the last row stops on the second, so the page is never half empty. */
    private float scrollFor(int k) {
        return Math.max(0f, Math.min(k * rowPitch, Math.max(0, rows.size() - 2) * rowPitch));
    }

    private void scrollRows(float dp) {
        if (scroll == null) scroll = new Spring.Glide(Spring.smooth(), SMOOTH_MS, 1f / Ui.density, scrollDp, v -> {
            scrollDp = v;
            page.setTranslationY(-Ui.dpf(v));
            glassFollow(v);
        });
        if (jumping) {
            scrollAim = dp;
            scroll.jump(dp);
            return;
        }
        if (dp == scrollAim && (scroll.moving() || scrollDp == dp)) return;
        scrollAim = dp;
        scroll.to(dp, Ui.scrollFloorMs(this, Ui.dpf(Math.abs(dp - scroll.value())), true), rewindow);
    }

    /** Glass tiles sample the photograph where they stand: on the move they repaint every 6 dp, and where they stop. */
    private void glassFollow(float v) {
        if (!"glass".equals(style)) return;
        if (Math.abs(v - glassAt) < 6f && scroll != null && scroll.moving()) return;
        glassAt = v;
        for (int i = 0; i < rows.size(); i++) {
            List<Tile> cs = rows.get(i).cells;
            for (int k = 0; k < cs.size(); k++) if (cs.get(k).cell.card.glass != null) cs.get(k).cell.card.invalidate();
        }
    }

    /** Slides a row so column {@code col} is on screen. */
    private void reveal(Row r, int col) {
        int first = r.first;
        if (col < first) first = col;
        else if (col >= first + cols) first = col - cols + 1;
        int dir = Integer.signum(first - r.first);
        r.first = first;
        if (jumping) {
            if (r.glide != null) r.glide.jump(first);
            r.shift = first;
            placeRow(r);
            return;
        }
        float to = first;
        // held: aimed ahead by what the spring trails a steady target, so the row keeps pace and the
        // tile in focus stays on screen; released() brings it to a whole column once the key is let go
        if (held && dir != 0) to = Math.max(0f, Math.min(Math.max(0, r.items.size() - cols), first + dir * holdLead));
        if (to != r.shift || r.glide != null && r.glide.moving()) slide(r, to);
    }

    private void slide(final Row r, float to) {
        float px = Ui.dpf(pitch);
        if (r.glide == null) r.glide = new Spring.Glide(Spring.row(), ROW_MS, 1f / px, r.shift, v -> {
            // the rows were made again meanwhile: this slide belongs to none of them
            if (r.index >= rows.size() || rows.get(r.index) != r) {
                if (r.glide != null) r.glide.stop();
                return;
            }
            r.shift = v;
            placeRow(r);
        });
        r.glide.to(to, Ui.scrollFloorMs(this, Math.abs(to - r.glide.value()) * px, false), rewindow);
    }

    // ---- keys ---------------------------------------------------------------------------------

    /**
     * Whether this press or repeat moves: every separate press does; a held key steps on
     * {@link Ui#holdStep}'s ladder whatever the remote's repeat rate, and the repeats between go.
     */
    private boolean step(KeyEvent e) {
        long now = SystemClock.uptimeMillis();
        if (e.getRepeatCount() == 0) {
            // a key held before whose release never came here: settled now
            if (held) released();
            lastStep = now;
            lastRepeat = now;
            return true;
        }
        int code = e.getKeyCode();
        boolean vertical = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN;
        long step = Ui.holdStep(now - e.getDownTime(), vertical), gap = now - lastRepeat;
        lastRepeat = now;
        if (now - lastStep < step) return false;
        // on the ladder's beat: a step that came late is made up once
        lastStep = now - lastStep < 2 * step ? lastStep + step : now;
        holdLead = 0.5f + ROW_TRAIL_MS / Math.max(step, gap);
        held = true;
        return true;
    }

    private void arrow(int code, KeyEvent e) {
        if (!step(e)) return;
        View before = findFocus();
        // the focus somewhere the page didn't put it (TalkBack, a sheet closing): the zone follows it
        if (before == search || before == cc) zone = HEAD;
        else if (before != null && dock.holds(before)) zone = DOCK;
        else if (before instanceof Card || before != null && before == emptyButton) zone = ROWS;
        if (zone == HEAD) headKey(code);
        else if (zone == DOCK) dockKey(code);
        else rowsKey(code);
        if (findFocus() != before) tick(code);
    }

    /** The TV's navigation tick, once for each move the focus really made; silent when its sounds are off. */
    private void tick(int code) {
        int dir = code == KeyEvent.KEYCODE_DPAD_LEFT ? FOCUS_LEFT : code == KeyEvent.KEYCODE_DPAD_RIGHT ? FOCUS_RIGHT
                : code == KeyEvent.KEYCODE_DPAD_UP ? FOCUS_UP : FOCUS_DOWN;
        try {
            if (Build.VERSION.SDK_INT >= 33) playSoundEffect(SoundEffectConstants.getConstantForFocusDirection(dir, held));
            else playSoundEffect(SoundEffectConstants.getContantForFocusDirection(dir));
        } catch (RuntimeException ignored) {
            // the TV's sound service refused: the move stays silent
        }
    }

    /**
     * The rows: no wrap; Left and Right along the row, a fresh press at its end giving 10 dp;
     * Up and Down to the same place on screen in the next row, or exactly back where the last move up
     * or down came from; from the first row up to the header button nearest above, from the last down
     * to the dock's nearest folder. A held key never leaves the rows.
     */
    private void rowsKey(int code) {
        if (rows.isEmpty()) {
            if (code == KeyEvent.KEYCODE_DPAD_UP && !held) focusHead(0);
            return;
        }
        Row r = rows.get(rowAt);
        int col = r.col, slot = col - r.first;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                srcFrom = null;
                if (col > 0) focusTile(r, col - 1);
                else if (!held) nudge(r, col, -Ui.dpf(10), 0f);
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                srcFrom = null;
                if (col < r.items.size() - 1) focusTile(r, col + 1);
                else if (!held) nudge(r, col, Ui.dpf(10), 0f);
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (returnTrip(code, r, col)) break;
                if (r.index > 0) {
                    vertical(r, col, rows.get(r.index - 1), slot, code);
                } else if (!held) {
                    float x = x0 + slot * pitch + tileW / 2f;
                    // the header's buttons' middles: Search at 858, Control Center at 901 dp
                    focusHead(Math.abs(x - 858f) <= Math.abs(x - 901f) ? 0 : 1);
                }
                break;
            default:
                if (returnTrip(code, r, col)) break;
                if (r.index < rows.size() - 1) vertical(r, col, rows.get(r.index + 1), slot, code);
                else if (!held) toDock(r, col);
                break;
        }
    }

    private void vertical(Row from, int col, Row to, int slot, int code) {
        int target = Math.max(0, Math.min(to.items.size() - 1, to.first + slot));
        srcFrom = from;
        srcCol = col;
        srcTo = to;
        srcToCol = target;
        srcDir = code;
        focusTile(to, target);
    }

    /** rememberSource: the key opposite to the last move up or down, with the focus still where it landed, goes back exactly. */
    private boolean returnTrip(int code, Row r, int col) {
        if (srcFrom == null || srcDir == code || srcTo != r || srcToCol != col) return false;
        Row back = srcFrom;
        int c = srcCol;
        srcFrom = null;
        if (back.index >= rows.size() || rows.get(back.index) != back || c >= back.items.size()) return false;
        srcFrom = r;
        srcCol = col;
        srcTo = back;
        srcToCol = c;
        srcDir = code;
        focusTile(back, c);
        return true;
    }

    /** Down from the last row: the dock's folder nearest below, remembered so Up comes back to this very tile. */
    private void toDock(Row r, int col) {
        if (dock.size() == 0) {
            nudge(r, col, 0f, Ui.dpf(6));
            return;
        }
        dockRow = r.id;
        dockTag = r.items.get(col).tag;
        dockCol = col;
        focusDock(dock.nearest(x0 + (col - r.first) * pitch + tileW / 2f));
    }

    /** Up or Back out of the dock: the tile it was entered from, else the lowest row on show, the same place across. */
    private void fromDock() {
        if (rows.isEmpty()) {
            land();
            return;
        }
        Row r = find(dockRow);
        if (r != null) {
            int c = indexOf(r, dockTag);
            focusTile(r, c >= 0 ? c : Math.min(dockCol, r.items.size() - 1));
            return;
        }
        Row low = rows.get(Math.min(rows.size() - 1, Math.round(scrollAim / rowPitch) + 1));
        int c = low.first + Math.round((dock.centreX(dock.at()) - x0 - tileW / 2f) / pitch);
        focusTile(low, Math.max(low.first, Math.min(Math.min(low.items.size() - 1, low.first + cols - 1), c)));
    }

    private void dockKey(int code) {
        int d = dock.at();
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (d > 0) focusDock(d - 1);
                else if (!held) dock.bounce(-1);
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (d < dock.size() - 1) focusDock(d + 1);
                else if (!held) dock.bounce(1);
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (!held) fromDock();
                break;
            default:
                if (!held) dock.dip();
                break;
        }
    }

    /** The header: Search and Control Center, no wrap; nothing above (a 6 dp lift); Down back to the rows' tile. */
    private void headKey(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (headAt > 0) focusHead(headAt - 1);
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (headAt < 1) focusHead(headAt + 1);
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (!held) {
                    final View v = headAt == 0 ? search : cc;
                    give.start(v, 0f, -Ui.dpf(6), () -> v.setTranslationY(give.dy));
                }
                break;
            default:
                if (!held) {
                    zone = ROWS;
                    land();
                }
                break;
        }
    }

    /** A fresh press with nowhere to go: the tile gives that far and comes back. */
    private void nudge(Row r, int col, float x, float y) {
        final Tile t = tiles.get(r.id + "|" + r.items.get(col).tag);
        if (t != null) give.start(t.cell, x, y, () -> place(t));
    }

    /**
     * Back: from the header the page closes; from the dock to the rows' tile; from a lower
     * row, or a first row slid along, to the first row's start of view; then the page closes.
     * With no favorites yet the page starts on the first row of apps, and Back counts from there:
     * Favorites' ghost above it is already past the start.
     */
    private void back() {
        if (zone == HEAD || rows.isEmpty()) {
            close();
            return;
        }
        if (zone == DOCK) {
            fromDock();
            return;
        }
        Row first = rows.get(0);
        int start = first.id.equals(Categories.FAV) && !first.items.get(0).real() ? Math.max(0, firstApps()) : 0;
        Row top = rows.get(start);
        srcFrom = null;
        if (rowAt > start) focusTile(top, top.col);
        else if (rowAt == start && top.first > 0) focusTile(top, 0);
        else close();
    }

    /** The first row that starts with an app or a folder, or -1 when none does. */
    private int firstApps() {
        for (int k = 0; k < rows.size(); k++) if (rows.get(k).items.get(0).real()) return k;
        return -1;
    }

    /** Channel and page keys: a row up or down, at the same place on screen. */
    private void pageKey(int code) {
        if (zone != ROWS || rows.isEmpty()) return;
        boolean up = code == KeyEvent.KEYCODE_CHANNEL_UP || code == KeyEvent.KEYCODE_PAGE_UP;
        Row r = rows.get(rowAt);
        int to = Math.max(0, Math.min(rows.size() - 1, rowAt + (up ? -1 : 1)));
        if (to == rowAt) return;
        Row t = rows.get(to);
        srcFrom = null;
        focusTile(t, Math.min(t.items.size() - 1, t.first + r.col - r.first));
    }

    // ---- what a tile does ---------------------------------------------------------------------

    private void click(Tile t) {
        if (carry != null) return;
        Item it = t.item;
        switch (it.kind) {
            case Item.APP:
                host.launch(it.app, t.cell.card);
                break;
            case Item.FOLDER:
                openFolder(it.folder, it.inside, t.cell.card);
                break;
            case Item.GHOST:
                addApps(t);
                break;
            case Item.HIDDEN:
                host.hiddenApps();
                break;
            default:
                break;
        }
    }

    private void hold(Tile t) {
        if (carry != null || menu.isOpen()) return;
        Item it = t.item;
        if (it.kind == Item.GHOST) addApps(t);
        else if (it.kind == Item.FOLDER) folderMenu(it.folder, t, t.cell.card);
        else if (it.kind == Item.APP) appMenu(t);
    }

    private void openFolder(Folders.Folder f, List<AppRepo.App> inside, Card from) {
        if (inside == null || inside.isEmpty()) return;
        sheetFrom = from;
        sheetTag = f.tag();
        sheet.open(f, inside);
    }

    private void closeFolder() {
        if (!sheet.isOpen()) return;
        sheet.close();
        View back = sheetFrom;
        sheetFrom = null;
        sheetTag = null;
        if (back != null && back.isShown()) back.requestFocus();
        else land();
    }

    /** The open folder shows what it holds now; once it holds nothing it closes, and its tile is gone. False then. */
    private boolean reopenSheet() {
        String tag = sheetTag;
        Folders.Folder f = tag == null ? null : Folders.all(act).get(tag.substring(7));
        List<AppRepo.App> in = f == null ? null : inside(f, Prefs.hidden(act));
        if (in == null || in.isEmpty()) {
            closeFolder();
            return false;
        }
        sheet.open(f, in);
        return true;
    }

    private void showMenu(View anchor, String label, List<AppMenu.Item> items) {
        menuFor = anchor;
        menu.show(anchor, label, items);
    }

    /** The menu put away: the focus back on its tile where that's still on show, never left on the fading menu. */
    private void closeMenu() {
        menu.hide();
        View f = findFocus();
        if (f != null && !within(f, menu)) return;
        if (sheet.isOpen()) sheet.requestFocus();
        else land();
    }

    private static boolean within(View v, View group) {
        for (ViewParent p = v.getParent(); p != null; p = p.getParent()) if (p == group) return true;
        return false;
    }

    /** The name card: the TV's keyboard, or one of the suggestions; Back leaves the name as it was. */
    private void askName(String title, String text, List<String> suggestions, final Ask.Done done) {
        askFor = findFocus();
        ask.show(title, text, act.getString(R.string.type_a_name_then_ok), suggestions, got -> {
            // before the change rebuilds the page around wherever the focus is
            asked();
            done.got(got);
            if (findFocus() == null) land();
        });
        focusable();
    }

    /**
     * The name card has gone (a name given, or none): the rows take focus again, on the tile it was
     * asked from, since the card's field let its focus go to whatever Android found first.
     */
    private void asked() {
        View b = askFor;
        askFor = null;
        focusable();
        if (b != null && b.isShown()) b.requestFocus();
    }

    private void closeAsk() {
        ask.cancel();
        focusable();
        View back = askFor;
        askFor = null;
        if (back != null && back.isShown()) back.requestFocus();
        else land();
    }

    /** Something changed here: home rebuilds behind, then the page, the focus kept where it is (or beside it). */
    private void changed() {
        tellHome();
        rebuildHere();
    }

    /** As {@link #changed()}, the focus going to {@code tag} in row {@code rowId}, else to {@code col} there. */
    private void changed(String rowId, String tag, int col) {
        tellHome();
        rebuild(rowId, tag, col, zone == DOCK ? dock.tag(dock.at()) : null);
    }

    /** Home rebuilds behind the page; the refresh that sets off here is the page's own, so it's skipped. */
    private void tellHome() {
        busy = true;
        try {
            host.changed();
        } finally {
            busy = false;
        }
    }

    /** The page made again with the focus where the page says it is. */
    private void rebuildHere() {
        String row = null, tag = null;
        int col = 0;
        if (rowAt < rows.size()) {
            Row r = rows.get(rowAt);
            row = r.id;
            col = r.col;
            tag = r.col < r.items.size() ? r.items.get(r.col).tag : null;
        }
        rebuild(row, tag, col, zone == DOCK ? dock.tag(dock.at()) : null);
    }

    /**
     * Hold OK on an app: Move; into a folder or a row; out of this row and its rename; and,
     * outside a kids' profile, Hide, App info and Uninstall. Each through the stores' own calls.
     */
    private void appMenu(final Tile t) {
        final AppRepo.App a = t.item.app;
        final Row r = t.row;
        final Card card = t.cell.card;
        List<AppMenu.Item> items = new ArrayList<>();
        items.add(new AppMenu.Item(R.drawable.ic_move, act.getString(R.string.move), () -> {
            closeMenu();
            startEdit(t);
        }));
        items.add(new AppMenu.Item(R.drawable.ic_apps, act.getString(R.string.add_to_folder), () -> {
            closeMenu();
            chooseFolder(a, r, card);
        }));
        items.add(new AppMenu.Item(R.drawable.ic_add, act.getString(R.string.add_to_category), () -> {
            closeMenu();
            chooseRow(a, r, card);
        }));
        if (r.cat != null && !r.id.equals(Categories.ALL)) {
            items.add(new AppMenu.Item(R.drawable.ic_hide, act.getString(R.string.remove_from_row, r.title), () -> {
                closeMenu();
                Categories.removeFrom(act, r.id, a.pkg);
                changed(r.id, null, t.col);
                host.hud(R.drawable.ic_check, act.getString(R.string.name_left_row, a.label, r.title));
            }));
            items.add(new AppMenu.Item(R.drawable.ic_settings, act.getString(R.string.rename_row, r.title), () -> {
                closeMenu();
                renameRow(r);
            }));
        }
        if (!Profiles.kids(act)) {
            items.add(new AppMenu.Item(R.drawable.ic_hide, act.getString(R.string.hide_from_home), () -> {
                closeMenu();
                Prefs.hide(act, a.pkg, true);
                changed(r.id, null, t.col);
                host.hud(R.drawable.ic_hide, act.getString(R.string.name_is_hidden_settings, a.label));
            }));
            items.add(new AppMenu.Item(R.drawable.ic_info, act.getString(R.string.app_info), () -> {
                closeMenu();
                host.open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.pkg)), card);
            }));
            if (HomeActivity.removable(act, a.pkg)) items.add(new AppMenu.Item(R.drawable.ic_trash, act.getString(R.string.uninstall), () -> {
                closeMenu();
                // Android asks to confirm; home, then the page, follow once the app is gone
                host.open(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + a.pkg)), card);
            }));
        }
        showMenu(card, a.label, items);
    }

    /** Which folder an app goes into: one of yours, or a new one that takes its place in your order, named straight away. */
    private void chooseFolder(final AppRepo.App a, final Row r, Card card) {
        List<AppMenu.Item> items = new ArrayList<>();
        for (final Folders.Folder f : Folders.all(act).values()) {
            items.add(new AppMenu.Item(R.drawable.ic_apps, f.name, () -> {
                closeMenu();
                Folders.add(act, f.id, a.pkg);
                changed(r.id, a.pkg, r.col);
                host.hud(R.drawable.ic_check, act.getString(R.string.name_is_in_folder, a.label, f.name));
            }));
        }
        items.add(new AppMenu.Item(R.drawable.ic_add, act.getString(R.string.new_folder), () -> {
            closeMenu();
            Folders.Folder f = Folders.create(act, a.pkg);
            List<String> order = AppRepo.order(Prefs.of(act));
            int at = order.indexOf(a.pkg);
            if (at >= 0) order.set(at, f.tag());
            else order.add(f.tag());
            AppRepo.saveDock(Prefs.of(act), order);
            changed(r.id, r.id.equals(Categories.ALL) ? f.tag() : a.pkg, r.col);
            nameFolder(f.id, true);
        }));
        showMenu(card, act.getString(R.string.add_name_to, a.label), items);
    }

    /** Which row an app goes into: one of the rows, or a new one, named straight away. */
    private void chooseRow(final AppRepo.App a, final Row r, Card card) {
        List<AppMenu.Item> items = new ArrayList<>();
        for (Categories.Cat k : Categories.all(act)) {
            if (k.id.equals(Categories.ALL)) continue;
            final String id = k.id, name = Categories.name(act, k);
            items.add(new AppMenu.Item(R.drawable.ic_apps, name, () -> {
                closeMenu();
                Categories.addTo(act, id, a.pkg);
                changed(r.id, a.pkg, r.col);
                host.hud(R.drawable.ic_check, act.getString(R.string.name_is_in_row, a.label, name));
            }));
        }
        items.add(new AppMenu.Item(R.drawable.ic_add, act.getString(R.string.cat_new), () -> {
            closeMenu();
            askName(act.getString(R.string.name_your_new_category), "", Folders.suggestions(act), name -> {
                String id = Categories.create(act, name);
                Categories.addTo(act, id, a.pkg);
                changed(r.id, a.pkg, r.col);
                host.hud(R.drawable.ic_check, act.getString(R.string.name_is_in_row, a.label, name));
            });
        }));
        showMenu(card, act.getString(R.string.add_name_to, a.label), items);
    }

    private void renameRow(final Row r) {
        askName(act.getString(R.string.rename_name, r.title.toUpperCase(Locale.getDefault())), r.title, Folders.suggestions(act), name -> {
            Categories.rename(act, r.id, name);
            changed();
            host.hud(R.drawable.ic_check, act.getString(R.string.renamed_to, name));
        });
    }

    /** Hold OK on a folder: Move (in a row), Rename, Remove folder (its apps go back where it stood, nothing asked). */
    private void folderMenu(final Folders.Folder f, final Tile t, Card card) {
        List<AppMenu.Item> items = new ArrayList<>();
        if (t != null) items.add(new AppMenu.Item(R.drawable.ic_move, act.getString(R.string.move), () -> {
            closeMenu();
            startEdit(t);
        }));
        items.add(new AppMenu.Item(R.drawable.ic_settings, act.getString(R.string.rename), () -> {
            closeMenu();
            nameFolder(f.id, false);
        }));
        items.add(new AppMenu.Item(R.drawable.ic_apps, act.getString(R.string.remove_folder), () -> {
            closeMenu();
            removeFolder(f);
        }));
        showMenu(card, f.name, items);
    }

    /** As home does it: the folder's apps take its place in your order, in the folder's order, then the folder goes. */
    private void removeFolder(Folders.Folder f) {
        Folders.Folder gone = Folders.all(act).get(f.id);
        List<String> order = AppRepo.order(Prefs.of(act));
        int at = order.indexOf(f.tag());
        if (gone != null && at >= 0) {
            order.remove(at);
            order.removeAll(gone.apps);
            order.addAll(Math.min(at, order.size()), gone.apps);
            AppRepo.saveDock(Prefs.of(act), order);
        }
        Folders.dissolve(act, f.id);
        changed();
        host.hud(R.drawable.ic_apps, act.getString(R.string.folder_removed, f.name));
    }

    private void nameFolder(final String id, final boolean made) {
        Folders.Folder f = Folders.all(act).get(id);
        if (f == null) return;
        List<String> names = Folders.suggestions(act);
        names.remove(f.name);
        String title = made ? act.getString(R.string.name_your_new_folder)
                : act.getString(R.string.rename_name, f.name.toUpperCase(Locale.getDefault()));
        askName(title, f.name, names, name -> {
            Folders.rename(act, id, name);
            changed();
            host.hud(R.drawable.ic_check, made ? act.getString(R.string.new_folder_name, name) : act.getString(R.string.renamed_to, name));
        });
    }

    /** Hold OK on an app in an open folder: out of the folder, App info, Rename folder. An emptied folder closes and goes. */
    private void insideMenu(final AppRepo.App a, Card from) {
        final String tag = sheetTag;
        List<AppMenu.Item> items = new ArrayList<>();
        items.add(new AppMenu.Item(R.drawable.ic_apps, act.getString(R.string.take_out_of_folder), () -> {
            closeMenu();
            String folderName = null;
            Folders.Folder f = tag == null ? null : Folders.all(act).get(tag.substring(7));
            if (f != null) folderName = f.name;
            Folders.remove(act, a.pkg);
            changed();
            if (reopenSheet() || folderName == null) {
                host.hud(R.drawable.ic_check, act.getString(R.string.name_is_back_on_the_grid, a.label));
            } else {
                host.hud(R.drawable.ic_apps, act.getString(R.string.folder_removed, folderName));
            }
        }));
        if (!Profiles.kids(act)) items.add(new AppMenu.Item(R.drawable.ic_info, act.getString(R.string.app_info), () -> {
            closeMenu();
            closeFolder();
            host.open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.pkg)), findFocus());
        }));
        items.add(new AppMenu.Item(R.drawable.ic_settings, act.getString(R.string.rename_folder), () -> {
            closeMenu();
            closeFolder();
            if (tag != null) nameFolder(tag.substring(7), false);
        }));
        showMenu(from, a.label, items);
    }

    /** An empty row's ghost: the apps not in it yet, A to Z; the one chosen is added by hand. */
    private void addApps(Tile t) {
        final Row r = t.row;
        if (r.cat == null || r.id.equals(Categories.ALL)) return;
        Set<String> there = new HashSet<>();
        for (int i = 0; i < r.items.size(); i++) if (r.items.get(i).kind == Item.APP) there.add(r.items.get(i).tag);
        Set<String> hidden = Prefs.hidden(act);
        List<AppMenu.Item> items = new ArrayList<>();
        List<AppRepo.App> apps = host.apps();
        for (int i = 0; i < apps.size(); i++) {
            final AppRepo.App a = apps.get(i);
            if (hidden.contains(a.pkg) || there.contains(a.pkg)) continue;
            items.add(new AppMenu.Item(R.drawable.ic_add, a.label, () -> {
                closeMenu();
                Categories.addTo(act, r.id, a.pkg);
                changed(r.id, a.pkg, 0);
                host.hud(R.drawable.ic_check, act.getString(R.string.name_is_in_row, a.label, r.title));
            }));
        }
        if (!items.isEmpty()) showMenu(t.cell.card, act.getString(R.string.add_apps_to, r.title), items);
    }

    // ---- Move ---------------------------------------------------------------------------------

    /**
     * Move, from a tile's menu: it jiggles, Left and Right carry it along its row and the others make
     * room. OK keeps the new order (the same calls home's Move makes), Back puts every tile back. Into
     * another row, onto the dock or into a folder goes by the menu: Aura has no store for a dock of apps.
     */
    private void startEdit(Tile t) {
        Row r = t.row;
        if (carry != null || r == null || r.cat == null || !t.item.real()) return;
        carry = t;
        carryWas = new ArrayList<>(r.items);
        if (jiggle == null) {
            jiggle = ValueAnimator.ofFloat(-1f, 1f);
            jiggle.setDuration(150);
            jiggle.setRepeatCount(ValueAnimator.INFINITE);
            jiggle.setRepeatMode(ValueAnimator.REVERSE);
            jiggle.addUpdateListener(an -> {
                if (carry != null) carry.cell.card.setRotation(1.2f * (float) an.getAnimatedValue());
            });
        }
        jiggle.start();
        host.hud(R.drawable.ic_move, act.getString(R.string.move_with_the_arrows, name(t.item)));
    }

    private int editKey(int code, KeyEvent e) {
        boolean first = e.getRepeatCount() == 0;
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (step(e)) moveCarry(code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
            return DONE;
        }
        if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) return DONE;
        if (code == KeyEvent.KEYCODE_BACK) {
            if (first) cancelEdit();
            return DONE;
        }
        // the OK that started the move came up long ago: only a fresh press keeps the new order
        if (Pad.isConfirm(code)) {
            if (first) dropCarry();
            return DONE;
        }
        // page keys and letters would reach home's own grid: mid-Move they do nothing
        boolean paging = code == KeyEvent.KEYCODE_CHANNEL_UP || code == KeyEvent.KEYCODE_CHANNEL_DOWN
                || code == KeyEvent.KEYCODE_PAGE_UP || code == KeyEvent.KEYCODE_PAGE_DOWN;
        return paging || code >= KeyEvent.KEYCODE_A && code <= KeyEvent.KEYCODE_Z ? DONE : PASS;
    }

    private void moveCarry(int d) {
        Tile t = carry;
        Row r = t.row;
        int i = t.col, j = i + d;
        if (j < 0 || j >= r.items.size()) return;
        Item other = r.items.get(j);
        r.items.set(j, t.item);
        r.items.set(i, other);
        t.col = j;
        Tile o = tiles.get(r.id + "|" + other.tag);
        if (o != null) o.col = i;
        r.col = j;
        shuffle(r);
    }

    /** The row keeps the carried tile in view, at once; its tiles glide to their places on their own. */
    private void shuffle(Row r) {
        int first = r.first;
        if (r.col < first) first = r.col;
        else if (r.col >= first + cols) first = r.col - cols + 1;
        if (r.glide != null) r.glide.stop();
        // its next slide starts afresh from where the tiles are put now
        r.glide = null;
        r.first = first;
        r.shift = first;
        window();
        for (int k = 0; k < r.cells.size(); k++) {
            Tile x = r.cells.get(k);
            x.cell.animate().translationX(Ui.dpf(x0 + (x.col - r.shift) * pitch)).setDuration(x == carry ? 420 : 300)
                    .setStartDelay(0).setInterpolator(x == carry ? Ui.SPRING : Ui.EASE_DECEL).start();
        }
    }

    /** OK: the new order kept. What isn't on the page (a hidden app, one in a folder) keeps its own place in the store. */
    private void dropCarry() {
        Tile t = carry;
        Row r = t.row;
        List<Item> was = carryWas;
        stopJiggle(t);
        carry = null;
        carryWas = null;
        if (was == null || was.equals(r.items)) return;
        List<String> now = new ArrayList<>();
        for (int i = 0; i < r.items.size(); i++) if (r.items.get(i).real()) now.add(r.items.get(i).tag);
        if (r.id.equals(Categories.ALL)) {
            AppRepo.saveDock(Prefs.of(act), merged(AppRepo.order(Prefs.of(act)), now));
        } else {
            Categories.Cat k = Categories.find(Categories.all(act), r.id);
            if (k == null) return;
            Categories.reorder(act, r.id, merged(k.add, now));
        }
        changed(r.id, t.item.tag, t.col);
        host.hud(R.drawable.ic_check, act.getString(R.string.your_apps_are_arranged));
    }

    /** Back, or the focus gone mid-Move: every tile glides back where it was; nothing is saved. */
    private void cancelEdit() {
        Tile t = carry;
        if (t == null) return;
        Row r = t.row;
        List<Item> was = carryWas;
        stopJiggle(t);
        carry = null;
        carryWas = null;
        if (was == null || r.index >= rows.size() || rows.get(r.index) != r) return;
        r.items.clear();
        r.items.addAll(was);
        for (Tile x : tiles.values()) {
            if (x.row != r) continue;
            int c = r.items.indexOf(x.item);
            if (c >= 0) x.col = c;
        }
        r.col = t.col;
        shuffle(r);
    }

    private void stopJiggle(Tile t) {
        if (jiggle != null) jiggle.cancel();
        t.cell.card.setRotation(0f);
    }

    /**
     * {@code now}, a row's order as laid out, laid into the stored order {@code old}: entries the page
     * doesn't show (a hidden app, one inside a folder, one not installed) keep their places, so a Move
     * never drops a row's hidden member or your place for a hidden app.
     */
    static List<String> merged(List<String> old, List<String> now) {
        Set<String> moving = new HashSet<>(now);
        List<String> out = new ArrayList<>(old.size() + now.size());
        int next = 0;
        for (int i = 0; i < old.size(); i++) {
            String s = old.get(i);
            if (!moving.contains(s)) out.add(s);
            else if (next < now.size()) out.add(now.get(next++));
        }
        while (next < now.size()) out.add(now.get(next++));
        return out;
    }

    // ---- small pieces -------------------------------------------------------------------------

    private Row find(String id) {
        if (id == null) return null;
        for (int i = 0; i < rows.size(); i++) if (rows.get(i).id.equals(id)) return rows.get(i);
        return null;
    }

    private static int indexOf(Row r, String tag) {
        if (tag == null) return -1;
        for (int i = 0; i < r.items.size(); i++) if (r.items.get(i).tag.equals(tag)) return i;
        return -1;
    }

    /** A round glass button in the header, its glyph darkening as it lights. */
    private Pad button(int icon, int size, int glyph, int said, float x, float y, Runnable run) {
        Pad p = new Pad(act, host.backdrop(), size / 2f, 0.26f);
        p.frost = 0.06f;
        ImageView iv = p.tint(ControlCenter.icon(act, icon), 1f);
        p.addView(iv, new LayoutParams(Ui.dp(glyph), Ui.dp(glyph), Gravity.CENTER));
        p.setContentDescription(act.getString(said));
        p.onClick = run;
        p.setOnFocusChangeListener((v, has) -> {
            if (!has) return;
            zone = HEAD;
            headAt = v == search ? 0 : 1;
            light(true);
            titlesLit();
        });
        content.addView(p, at(Ui.dp(size), Ui.dp(size), x, y));
        return p;
    }

    /** A child's place: {@code w} x {@code h} px, ({@code x}, {@code y}) dp from the top left. */
    private static LayoutParams at(int w, int h, float x, float y) {
        LayoutParams l = new LayoutParams(w, h, Gravity.TOP | Gravity.LEFT);
        l.leftMargin = Ui.dp(x);
        l.topMargin = Ui.dp(y);
        return l;
    }

    /** An empty row's ghost tile: a dashed outline at 35 % white, a plus, and "Add apps". */
    private static Tiles.Painter ghost(final String caption) {
        return (c, w, h) -> {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Ui.dpf(1.5f));
            p.setColor(0x59FFFFFF);
            p.setPathEffect(new DashPathEffect(new float[]{Ui.dpf(6), Ui.dpf(4)}, 0));
            // the card's own corners (Cell: 15 dp for a large tile, else 10)
            float i = Ui.dpf(0.75f), rad = Ui.dpf(w >= Ui.dp(140) ? 15 : 10);
            c.drawRoundRect(i, i, w - i, h - i, rad, rad, p);
            p.setPathEffect(null);
            p.setStrokeWidth(Ui.dpf(2f));
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setColor(0xB3FFFFFF);
            float cx = w / 2f, cy = h * 0.38f, arm = Ui.dpf(10f);
            c.drawLine(cx - arm, cy, cx + arm, cy, p);
            c.drawLine(cx, cy - arm, cx, cy + arm, p);
            TextPaint t = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            t.setTypeface(Ui.medium);
            t.setTextSize(Ui.dpf(Ui.FLOOR));
            t.setColor(0xB3FFFFFF);
            t.setTextAlign(Paint.Align.CENTER);
            c.drawText(TextUtils.ellipsize(caption, t, w * 0.9f, TextUtils.TruncateAt.END).toString(), cx, h * 0.80f, t);
        };
    }

    /** The hidden apps, folded into one tile: the hide glyph and how many there are. */
    private static Tiles.Painter hiddenArt(final Context ctx, final int n) {
        final String caps = ctx.getResources().getQuantityString(R.plurals.n_apps_caps, n, n);
        return (c, w, h) -> {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(0x26FFFFFF);
            c.drawRect(0, 0, w, h, p);
            Drawable d = ctx.getDrawable(R.drawable.ic_hide);
            if (d != null) {
                d = d.mutate();
                d.setTint(0xD9FFFFFF);
                int s = Math.round(h * 0.30f), cy = Math.round(h * 0.40f);
                d.setBounds((w - s) / 2, cy - s / 2, (w + s) / 2, cy + s / 2);
                d.draw(c);
            }
            TextPaint t = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            t.setTypeface(Ui.semibold);
            t.setTextSize(h * 0.11f);
            t.setLetterSpacing(0.2f);
            t.setColor(0xB3FFFFFF);
            t.setTextAlign(Paint.Align.CENTER);
            c.drawText(caps, w / 2f, h * 0.80f, t);
        };
    }

    @Override
    protected void onDetachedFromWindow() {
        if (litAnim != null) litAnim.cancel();
        if (jiggle != null) jiggle.cancel();
        give.stop();
        super.onDetachedFromWindow();
    }

    /** A nudge at an edge: out and back over 280 ms, added to the view's place by whoever places it. */
    static final class Give {
        private final ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        View view;
        float dx, dy;
        private float ox, oy;
        private Runnable apply;

        Give() {
            anim.setDuration(280);
            anim.setInterpolator(new LinearInterpolator());
            anim.addUpdateListener(a -> {
                float s = (float) Math.sin(Math.PI * a.getAnimatedFraction());
                dx = ox * s;
                dy = oy * s;
                if (apply != null) apply.run();
            });
        }

        /** {@code v} gives ({@code x}, {@code y}) px and comes back; {@code place} puts it where it stands, plus the give. */
        void start(View v, float x, float y, Runnable place) {
            if (view == v && anim.isRunning()) return;
            stop();
            view = v;
            ox = x;
            oy = y;
            apply = place;
            anim.start();
        }

        void stop() {
            anim.cancel();
            dx = 0f;
            dy = 0f;
            Runnable r = apply;
            view = null;
            apply = null;
            if (r != null) r.run();
        }
    }

    /** The rows' window between the header and the dock: clipped there, and on HIGH its last 12 dp fade into the dock. */
    private static final class Viewport extends FrameLayout {
        Viewport(Context c) {
            super(c);
            setClipChildren(true);
            // a fading edge saves a layer only that 12 dp strip high; LOW clips hard
            if (!Ui.light) {
                setWillNotDraw(false);
                setVerticalFadingEdgeEnabled(true);
                setFadingEdgeLength(Ui.dp(12));
            }
        }

        /** The rows end here, above and below: nothing scrolled past draws over the header or the dock. */
        @Override
        protected void dispatchDraw(Canvas canvas) {
            int saved = canvas.save();
            canvas.clipRect(0, 0, getWidth(), getHeight());
            super.dispatchDraw(canvas);
            canvas.restoreToCount(saved);
        }

        @Override
        protected float getTopFadingEdgeStrength() {
            return 0f;
        }

        @Override
        protected float getBottomFadingEdgeStrength() {
            return 1f;
        }
    }
}
