package app.aura.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * All apps' folder dock (§8.3.1): a glass tray along the foot of the page holding your folders on
 * the rows' six columns, as the tvOS 26 Home holds apps in its dock. Folders come in your own order;
 * more than six slide inside the tray. On HIGH the tray reaches 6 dp further toward an end tile that
 * has focus. The folders are Aura's own ({@link Folders}); no app sits on the dock by itself, as Aura
 * keeps no list of such apps. With no folder yet, a hint says how to make one.
 */
final class FolderDock extends FrameLayout {
    interface Listener {
        void open(Entry e, Card from);

        void hold(Entry e, Card from);

        void focused(int i, boolean gained);
    }

    /** A folder on the dock, with the apps in it that are on the TV and not hidden. */
    static final class Entry {
        final Folders.Folder folder;
        final List<AppRepo.App> inside;

        Entry(Folders.Folder folder, List<AppRepo.App> inside) {
            this.folder = folder;
            this.inside = inside;
        }
    }

    /** dp on the 960 x 540 canvas: the tray, 916 x 111 from x 22, its tiles 20 dp in, on the rows' 150 dp columns. */
    static final float TRAY_X = 22f, TRAY_W = 916f, TRAY_H = 111f, PAD = 20f, PITCH = 150f, STRETCH = 6f;
    static final int TILE_W = 126, TILE_H = 71, SLOTS = 6;

    private final Listener listener;
    private final GlassFrame tray;
    private final Strip strip;
    private final View hint;
    private final List<Entry> entries = new ArrayList<>();
    private final List<Cell> cells = new ArrayList<>();
    /** Tiles the dock no longer holds, kept until the focus has left them. */
    private final List<Cell> leaving = new ArrayList<>();
    private final AllApps.Give give = new AllApps.Give();
    private Spring.Mover slide;
    /** The tile focus is on (or last was), and the first of the six on show: where the slide is heading, and where it is. */
    private int at, first;
    private float shift;

    FolderDock(Context c, Backdrop b, Listener l) {
        super(c);
        listener = l;
        setClipChildren(false);
        tray = new GlassFrame(c, b, Ui.R_TRAY, 0.3f);
        tray.frost = 0.1f;
        addView(tray, at(TRAY_W, TRAY_H, TRAY_X, 0f));
        strip = new Strip(c);
        addView(strip, at(TRAY_W, TRAY_H, TRAY_X, 0f));
        // LOW: the focused folder's shadow, one shared baked view beneath the tiles
        FocusLayer.attach(strip, null);
        FrameLayout card = new FrameLayout(c);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(Ui.dpf(Ui.R_TILE));
        g.setStroke(Math.max(1, Math.round(Ui.dpf(1.5f))), 0x59FFFFFF, Ui.dpf(5), Ui.dpf(4));
        card.setBackground(g);
        TextView t = Ui.text(c, 12, Ui.medium, 0xB3FFFFFF);
        t.setSingleLine(false);
        t.setMaxLines(3);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.dp(14), 0, Ui.dp(14), 0);
        t.setText(R.string.dock_hint);
        card.addView(t, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        card.setVisibility(GONE);
        // two slots wide, where the first folders will stand
        strip.addView(card, at(TILE_W + PITCH, TILE_H, PAD, PAD));
        hint = card;
    }

    /**
     * The folders to hold, in order. A tile painted from the same folder and look is kept as it is;
     * a new one is painted on the painting thread and comes up when its picture is ready.
     */
    void show(List<Entry> list, String style, Backdrop glass) {
        List<Cell> was = new ArrayList<>(cells);
        entries.clear();
        entries.addAll(list);
        cells.clear();
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            String sig = sig(e, style);
            Cell c = null;
            for (int k = 0; k < was.size(); k++) {
                if (sig.equals(was.get(k).sig)) {
                    c = was.remove(k);
                    break;
                }
            }
            if (c == null) c = make(e, style, glass, sig);
            bind(c, e);
            cells.add(c);
        }
        leaving.addAll(was);
        sweep();
        hint.setVisibility(entries.isEmpty() ? VISIBLE : GONE);
        at = Math.max(0, Math.min(at, cells.size() - 1));
        first = Math.max(0, Math.min(first, cells.size() - SLOTS));
        if (slide != null) slide.jump(first);
        shift = first;
        place();
        edges();
    }

    /** The page closed: every tile goes, and its picture with it. */
    void release() {
        leaving.addAll(cells);
        cells.clear();
        entries.clear();
        give.stop();
        sweep();
    }

    /** Tiles the dock let go of leave once the focus isn't on them. */
    void sweep() {
        for (int i = leaving.size() - 1; i >= 0; i--) {
            Cell c = leaving.get(i);
            if (c.card.isFocused()) continue;
            leaving.remove(i);
            if (give.view == c) give.stop();
            strip.removeView(c);
        }
    }

    int size() {
        return cells.size();
    }

    int at() {
        return at;
    }

    String tag(int i) {
        return i >= 0 && i < entries.size() ? entries.get(i).folder.tag() : null;
    }

    int indexOf(String tag) {
        if (tag == null) return -1;
        for (int i = 0; i < entries.size(); i++) if (entries.get(i).folder.tag().equals(tag)) return i;
        return -1;
    }

    boolean holds(View v) {
        for (int i = 0; i < cells.size(); i++) if (cells.get(i).card == v) return true;
        return false;
    }

    /** Where tile {@code i}'s middle is across the screen, in dp, with the tray as it will rest. */
    float centreX(int i) {
        return TRAY_X + PAD + (i - first) * PITCH + TILE_W / 2f;
    }

    /** The tile whose middle is nearest {@code x} dp across the screen; -1 with none. */
    int nearest(float x) {
        int best = -1;
        float gap = Float.MAX_VALUE;
        for (int i = 0; i < cells.size(); i++) {
            float d = Math.abs(centreX(i) - x);
            if (d < gap) {
                gap = d;
                best = i;
            }
        }
        return best;
    }

    /** Focus to tile {@code i}, the tray sliding to show it; false when there's no such tile. */
    boolean focus(int i) {
        if (i < 0 || i >= cells.size()) return false;
        at = i;
        reveal(i);
        Cell c = cells.get(i);
        // still being painted: it paints itself now rather than keep the focus waiting
        if (c.getVisibility() != VISIBLE) c.setVisibility(VISIBLE);
        return c.card.requestFocus();
    }

    /** A fresh press past an end: the tile gives 10 dp that way and comes back. */
    void bounce(int dir) {
        if (at >= cells.size()) return;
        give.start(cells.get(at), Ui.dpf(10) * dir, 0f, this::place);
    }

    /** Down from the dock: nothing is below it, so the tile dips 6 dp and comes back. */
    void dip() {
        if (at >= cells.size()) return;
        give.start(cells.get(at), 0f, Ui.dpf(6), this::place);
    }

    private Cell make(Entry e, String style, Backdrop glass, String sig) {
        boolean glassy = "glass".equals(style);
        final Cell c = new Cell(getContext(), painter(e.folder.name, e.inside, !glassy), 0xFF9AA6BA, e.folder.name,
                e.folder.tag(), TILE_W, TILE_H);
        c.sig = sig;
        c.card.setGrowRoom((PITCH - TILE_W) / 2f);
        c.card.sway = false;
        c.card.tvos = "tvos".equals(style);
        if (glassy) c.card.glass = glass;
        // its name is inside the tile, as on a tvOS folder
        c.setNameMode("hidden", true);
        final Card.FocusListener named = c.card.focusListener;
        c.card.focusListener = (card, gained) -> {
            named.onFocus(card, gained);
            int i = cells.indexOf(c);
            if (gained && i >= 0) {
                at = i;
                reveal(i);
            }
            stretch(gained ? i : -1);
            if (i >= 0) listener.focused(i, gained);
        };
        c.setVisibility(GONE);
        strip.addView(c, new LayoutParams(Ui.dp(TILE_W), ViewGroup.LayoutParams.WRAP_CONTENT));
        AllApps.paintOff(painter(e.folder.name, e.inside, !glassy), Ui.dp(TILE_W), Ui.dp(TILE_H), b -> {
            if (!cells.contains(c) || c.getVisibility() == VISIBLE) return;
            if (b != null) c.card.ready = b;
            c.setVisibility(VISIBLE);
        });
        return c;
    }

    private void bind(final Cell c, final Entry e) {
        c.card.onClick = () -> listener.open(e, c.card);
        c.card.onLongClick = () -> listener.hold(e, c.card);
    }

    /** Six tiles on show: past them, the tray slides on the row spring. */
    private void reveal(int i) {
        int f = first;
        if (i < f) f = i;
        else if (i >= f + SLOTS) f = i - SLOTS + 1;
        if (f == first) return;
        first = f;
        if (slide == null) slide = new Spring.Mover(Spring.row(), 1f / Math.max(1f, Ui.dpf(PITCH)), shift, v -> {
            shift = v;
            place();
        });
        slide.to(f);
        edges();
    }

    /** Each tile at its column as the tray is slid, plus the give of one at an edge. Properties only. */
    private void place() {
        for (int i = 0; i < cells.size(); i++) {
            Cell c = cells.get(i);
            float x = Ui.dpf(PAD + (i - shift) * PITCH), y = Ui.dpf(PAD);
            if (give.view == c) {
                x += give.dx;
                y += give.dy;
            }
            c.setTranslationX(x);
            c.setTranslationY(y);
            // glass samples the photograph where it stands
            if (c.card.glass != null) c.card.invalidate();
        }
    }

    /** HIGH: a 12 dp fade at an end of the tray with more folders beyond it. */
    private void edges() {
        boolean before = first > 0, after = first + SLOTS < cells.size();
        if (before == strip.before && after == strip.after) return;
        strip.before = before;
        strip.after = after;
        strip.invalidate();
    }

    /**
     * HIGH: the tray reaches 6 dp further toward a focused tile at either end of it, pivoted at its far
     * end over 140 ms, and back when the focus moves on (§8.3.1, measured on tvOS). LOW leaves it be.
     */
    private void stretch(int i) {
        if (Ui.light) return;
        int slot = i - first;
        float to = 1f;
        if (i >= 0 && (slot == 0 || slot == SLOTS - 1)) {
            to = (TRAY_W + STRETCH) / TRAY_W;
            tray.setPivotX(slot == 0 ? tray.getWidth() : 0f);
        }
        if (tray.getScaleX() == to) return;
        tray.animate().scaleX(to).setDuration(140).setStartDelay(0).setInterpolator(Ui.EASE_DECEL).start();
    }

    private static String sig(Entry e, String style) {
        StringBuilder s = new StringBuilder("dock|").append(e.folder.tag()).append('|').append(style).append('|').append(e.folder.name);
        for (int i = 0; i < e.inside.size(); i++) s.append('|').append(System.identityHashCode(e.inside.get(i)));
        return s.toString();
    }

    private static LayoutParams at(float w, float h, float x, float y) {
        LayoutParams l = new LayoutParams(Ui.dp(w), Ui.dp(h), Gravity.TOP | Gravity.LEFT);
        l.leftMargin = Ui.dp(x);
        l.topMargin = Ui.dp(y);
        return l;
    }

    /**
     * A folder's tile as on tvOS 26: three of its apps as small banners along the top, and its name
     * low inside the tile over a soft shade. {@code pane}: an opaque ground (every look but glass).
     */
    static Tiles.Painter painter(final String name, final List<AppRepo.App> inside, final boolean pane) {
        return (c, w, h) -> {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
            if (pane) {
                p.setShader(new LinearGradient(0, 0, w * 0.6f, h, 0xFF2A2E36, 0xFF121419, Shader.TileMode.CLAMP));
                c.drawRect(0, 0, w, h, p);
                p.setShader(null);
            }
            float gap = w * 0.04f, mw = (w - 4 * gap) / 3f, mh = mw * 9f / 16f, top = h * 0.11f, rad = mh * 0.16f;
            RectF r = new RectF();
            for (int i = 0; i < 3; i++) {
                float x = gap + i * (mw + gap);
                r.set(x, top, x + mw, top + mh);
                if (i < inside.size()) {
                    mini(c, inside.get(i), r, rad);
                } else {
                    p.setColor(0x1FFFFFFF);
                    c.drawRoundRect(r, rad, rad, p);
                }
            }
            p.setShader(new LinearGradient(0, h * 0.5f, 0, h, 0x00000000, 0x8C000000, Shader.TileMode.CLAMP));
            c.drawRect(0, h * 0.5f, w, h, p);
            p.setShader(null);
            TextPaint t = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            t.setTypeface(Ui.medium);
            t.setTextSize(h * Ui.FLOOR / TILE_H);
            t.setColor(0xF2FFFFFF);
            float x = w * 0.07f;
            c.drawText(TextUtils.ellipsize(name, t, w - 2 * x, TextUtils.TruncateAt.END).toString(), x, h * 0.86f, t);
        };
    }

    /** One app as a small rounded banner: its banner filling it, else its icon on its own colour. */
    private static void mini(Canvas c, AppRepo.App a, RectF r, float rad) {
        c.save();
        Path round = new Path();
        round.addRoundRect(r, rad, rad, Path.Direction.CW);
        c.clipPath(round);
        Drawable banner = a.banner(), icon = a.icon();
        if (banner != null) {
            Drawable d = copy(banner);
            int iw = d.getIntrinsicWidth(), ih = d.getIntrinsicHeight();
            if (iw > 0 && ih > 0) {
                float s = Math.max(r.width() / iw, r.height() / ih), dw = iw * s, dh = ih * s;
                d.setBounds(Math.round(r.centerX() - dw / 2f), Math.round(r.centerY() - dh / 2f),
                        Math.round(r.centerX() + dw / 2f), Math.round(r.centerY() + dh / 2f));
            } else {
                d.setBounds(Math.round(r.left), Math.round(r.top), Math.round(r.right), Math.round(r.bottom));
            }
            d.draw(c);
        } else {
            Paint g = new Paint(Paint.ANTI_ALIAS_FLAG);
            g.setColor(Ui.mix(a.color, 0xFF000000, 0.45f));
            c.drawRect(r, g);
            if (icon != null) {
                Drawable d = copy(icon);
                int s = Math.round(r.height() * 0.66f), x = Math.round(r.centerX()) - s / 2, y = Math.round(r.centerY()) - s / 2;
                d.setBounds(x, y, x + s, y + s);
                d.draw(c);
            }
        }
        c.restore();
    }

    /** A copy of {@code d} to draw: its bounds belong to the views already showing it. */
    private static Drawable copy(Drawable d) {
        Drawable.ConstantState s = d.getConstantState();
        return s != null ? s.newDrawable().mutate() : d;
    }

    /** The tiles' window: clipped to the tray; on HIGH, faded 12 dp at an end with more beyond it. */
    private static final class Strip extends FrameLayout {
        boolean before, after;

        Strip(Context c) {
            super(c);
            setClipChildren(true);
            if (!Ui.light) {
                setWillNotDraw(false);
                setHorizontalFadingEdgeEnabled(true);
                setFadingEdgeLength(Ui.dp(12));
            }
        }

        @Override
        protected float getLeftFadingEdgeStrength() {
            return before ? 1f : 0f;
        }

        @Override
        protected float getRightFadingEdgeStrength() {
            return after ? 1f : 0f;
        }
    }
}
