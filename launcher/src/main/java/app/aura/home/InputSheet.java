package app.aura.home;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * Control Center's inputs, given room: every source on its own row (HDMI, the TV's own tuners,
 * AV) with its full name and what it's doing, in place of buttons squeezed into one tile. It takes
 * the board's place, as the Wi-Fi and Bluetooth sheets do; Back returns to the board.
 */
final class InputSheet extends GlassFrame {
    interface Host {
        void open(Intent intent, View from);
    }

    /** One source as Control Center read it. */
    static final class Source {
        final String name, state;
        final int glyph;
        final boolean live;
        final Intent intent;

        Source(String name, String state, int glyph, boolean live, Intent intent) {
            this.name = name;
            this.state = state;
            this.glyph = glyph;
            this.live = live;
            this.intent = intent;
        }
    }

    private static final int GREEN = 0xFF30D158;
    private final Activity act;
    private final Host host;
    private final LinearLayout list;
    private final TextView status;
    private final GlassFrame.Hold hold;
    private final GlassFrame.Way way;
    /** Fading out: still on screen for a moment, but no longer open, and nothing in it takes the focus. */
    private boolean closing;
    private final Runnable gone = () -> {
        closing = false;
        setVisibility(View.GONE);
    };

    InputSheet(Activity a, Backdrop b, Host h) {
        super(a, b, 26, 0.5f);
        act = a;
        host = h;
        setClipContent(false);
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(18), Ui.dp(16), Ui.dp(18), Ui.dp(12));
        col.setClipChildren(false);
        col.setClipToPadding(false);
        TextView title = Ui.text(a, 21, Ui.heavy, 0xFFFFFFFF);
        title.setText(a.getString(R.string.inputs_title));
        col.addView(title);
        status = Ui.text(a, 11, Ui.regular, 0xB3FFFFFF);
        col.addView(status);
        ScrollView scroll = new ScrollView(a);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setClipChildren(false);
        scroll.setPadding(0, Ui.dp(6), 0, Ui.dp(6));
        list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setClipChildren(false);
        scroll.addView(list, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        sl.topMargin = Ui.dp(10);
        col.addView(scroll, sl);
        addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setVisibility(View.GONE);
        setContentDescription(a.getString(R.string.inputs_title));
        hold = new GlassFrame.Hold(this, () -> {
            if (list.getChildCount() > 0) list.getChildAt(0).requestFocus();
        });
        way = new GlassFrame.Way(this, GlassFrame.sheetAt(this));
    }

    boolean isOpen() {
        return getVisibility() == View.VISIBLE && !closing;
    }

    void open(List<Source> sources) {
        list.removeAllViews();
        int live = 0;
        for (Source s : sources) if (s.live) live++;
        status.setText(act.getResources().getQuantityString(R.plurals.n_sources, sources.size(), sources.size())
                + (live > 0 ? "  ·  " + act.getString(R.string.n_live, live) : ""));
        for (Source s : sources) list.addView(row(s), rowParams());
        closing = false;
        setDescendantFocusability(FOCUS_BEFORE_DESCENDANTS);
        setVisibility(View.VISIBLE);
        way.in(Ui.LOW_SHEET_IN, null);
        hold.start();
        announceForAccessibility(getContentDescription());
    }

    void close() {
        if (getVisibility() != View.VISIBLE) return;
        hold.stop();
        closing = true;
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        way.out(gone);
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

    private LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(54));
        l.topMargin = Ui.dp(6);
        return l;
    }

    private View row(Source s) {
        Pad p = new Pad(act, null, 16, 0f);
        p.plainColor = 0x1AFFFFFF;
        p.edgeLight = false;
        p.row = true;
        LinearLayout r = new LinearLayout(act);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dp(14), 0, Ui.dp(16), 0);
        ImageView ic = p.tint(new ImageView(act), 1f);
        ic.setImageResource(s.glyph);
        r.addView(ic, new LinearLayout.LayoutParams(Ui.dp(22), Ui.dp(22)));
        LinearLayout text = new LinearLayout(act);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView name = p.tint(Ui.text(act, 13.5f, Ui.semibold, 0xFFFFFFFF), 1f);
        name.setText(s.name);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.addView(name);
        TextView state = p.tint(Ui.text(act, 10.5f, Ui.regular, 0xFFFFFFFF), 0.7f);
        state.setText(s.state);
        text.addView(state);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tl.leftMargin = Ui.dp(14);
        r.addView(text, tl);
        if (s.live) {
            View dot = new View(act);
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            g.setColor(GREEN);
            dot.setBackground(g);
            r.addView(dot, new LinearLayout.LayoutParams(Ui.dp(8), Ui.dp(8)));
        }
        p.addView(r, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        p.onClick = () -> host.open(s.intent, p);
        return p;
    }
}
