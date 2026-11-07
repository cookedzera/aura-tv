package app.aura.home;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Control Center's inputs as a deck of device cards, two to a row: each source with the glyph of
 * what's plugged in (a console, a box, a computer, a soundbar, the TV's own tuner), the device's own
 * name over HDMI-CEC, its port and whether it's live. Cards keep the ports' order, so a device is
 * always where you remember it; the one that's live has the focus as the deck opens. It takes the
 * board's place, as the Wi-Fi and Bluetooth sheets do; Back returns to the board.
 */
final class InputSheet extends GlassFrame {
    interface Host {
        void open(Intent intent, View from);
    }

    /** What's on a port, for its glyph: told from the device's own name, else from what CEC says it is. */
    static final int HDMI = 0, CONSOLE = 1, BOX = 2, COMPUTER = 3, AUDIO = 4, TUNER = 5, AV = 6;

    private static final Pattern CONSOLES = Pattern.compile("playstation|\\bps ?[345]\\b|xbox|nintendo|switch|steam ?deck|rog ally|\\bwii\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern COMPUTERS = Pattern.compile("macbook|\\bmac\\b|imac|laptop|\\bpc\\b|windows|thinkpad|desktop|\\bnuc\\b|chromebook", Pattern.CASE_INSENSITIVE);
    private static final Pattern SPEAKERS = Pattern.compile("sound ?bar|sonos|bose|denon|yamaha|marantz|onkyo|\\be?arc\\b|receiver|home ?theat|\\bhw-|\\bjbl\\b|\\bavr\\b|amplif|heos", Pattern.CASE_INSENSITIVE);
    private static final Pattern BOXES = Pattern.compile("apple ?tv|fire ?tv|fire ?stick|chromecast|google ?tv|roku|shield|mi ?box|\\bstick\\b|tata ?(play|sky)|airtel|\\bdish\\b|d2h|sun direct|videocon|set.?top|\\bstb\\b|decoder|sky ?q|humax|tivo|blu-?ray|\\bbd\\b|\\bdvd\\b|\\bdth\\b|hathway|jio ?fiber|android box", Pattern.CASE_INSENSITIVE);

    /** One source as Control Center read it. */
    static final class Source {
        final String id;
        /** The device's own name where CEC gives one, else the port's ("HDMI 2", "Antenna"). */
        final String name;
        /** The port as the TV names it ("HDMI 2"); empty when the name is already the port. */
        final String port;
        final String state;
        final int kind;
        final boolean live, standby;
        final Intent intent;

        Source(String id, String name, String port, String state, int kind, boolean live, boolean standby, Intent intent) {
            this.id = id;
            this.name = name;
            this.port = name.equals(port) ? "" : port;
            this.state = state;
            this.kind = kind;
            this.live = live;
            this.standby = standby;
            this.intent = intent;
        }

        int glyph() {
            return glyphOf(kind);
        }
    }

    /**
     * The kind of device behind {@code name}: its own name first (a "PlayStation 5" is a console
     * whatever CEC calls it), then what CEC says it is ({@link Cec#type}), then the port itself.
     */
    static int kindOf(String name, int cecType, boolean hdmi, boolean tuner) {
        if (tuner) return TUNER;
        if (name != null) {
            if (CONSOLES.matcher(name).find()) return CONSOLE;
            if (COMPUTERS.matcher(name).find()) return COMPUTER;
            if (SPEAKERS.matcher(name).find()) return AUDIO;
            if (BOXES.matcher(name).find()) return BOX;
        }
        if (cecType == Cec.AUDIO) return AUDIO;
        if (cecType == Cec.PLAYBACK || cecType == Cec.TUNER || cecType == Cec.RECORDER) return BOX;
        return hdmi ? HDMI : AV;
    }

    static int glyphOf(int kind) {
        switch (kind) {
            case CONSOLE: return R.drawable.ic_console;
            case BOX: return R.drawable.ic_box;
            case COMPUTER: return R.drawable.ic_laptop;
            case AUDIO: return R.drawable.ic_soundbar;
            case TUNER: return R.drawable.ic_antenna;
            case AV: return R.drawable.ic_av;
            default: return R.drawable.ic_hdmi;
        }
    }

    static final int GREEN = 0xFF30D158, AMBER = 0xFFFF9F0A;
    /** dp: two cards a row, 86 tall, 10 apart; a card's padding. */
    private static final int CARD_H = 86, GAP = 10, PAD = 12;
    /** A source with nothing plugged in sits back, this much of its light. */
    private static final float QUIET = 0.62f;
    private final Activity act;
    private final Host host;
    private final LinearLayout list;
    private final TextView status;
    private final List<Pad> cards = new ArrayList<>();
    private Pad lead;
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
        // room for a focused card to grow into at the deck's edges
        scroll.setPadding(Ui.dp(4), Ui.dp(6), Ui.dp(4), Ui.dp(6));
        list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setClipChildren(false);
        list.setClipToPadding(false);
        scroll.addView(list, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        sl.topMargin = Ui.dp(6);
        sl.leftMargin = sl.rightMargin = -Ui.dp(4);
        col.addView(scroll, sl);
        addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setVisibility(View.GONE);
        setContentDescription(a.getString(R.string.inputs_title));
        hold = new GlassFrame.Hold(this, this::focusLead);
        way = new GlassFrame.Way(this, GlassFrame.sheetAt(this));
    }

    boolean isOpen() {
        return getVisibility() == View.VISIBLE && !closing;
    }

    /** The live source's card, else the first: where the focus lands as the deck opens. */
    private void focusLead() {
        View to = lead != null ? lead : cards.isEmpty() ? null : cards.get(0);
        if (to != null) to.requestFocus();
    }

    void open(List<Source> sources) {
        for (Pad c : cards) c.animate().cancel();
        list.removeAllViews();
        cards.clear();
        lead = null;
        int live = 0;
        for (Source s : sources) if (s.live) live++;
        status.setText(act.getResources().getQuantityString(R.plurals.n_sources, sources.size(), sources.size())
                + (live > 0 ? "  ·  " + act.getString(R.string.n_live, live) : ""));
        LinearLayout row = null;
        for (int i = 0; i < sources.size(); i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(act);
                row.setClipChildren(false);
                row.setClipToPadding(false);
                LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(CARD_H));
                if (i > 0) rl.topMargin = Ui.dp(GAP);
                list.addView(row, rl);
            }
            Pad card = card(sources.get(i));
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            if (i % 2 == 1) cl.leftMargin = Ui.dp(GAP);
            row.addView(card, cl);
            cards.add(card);
            if (lead == null && sources.get(i).live) lead = card;
        }
        // an odd last card keeps its size: an empty twin holds the other half of the row
        if (row != null && row.getChildCount() == 1) {
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            cl.leftMargin = Ui.dp(GAP);
            row.addView(new View(act), cl);
        }
        closing = false;
        setDescendantFocusability(FOCUS_BEFORE_DESCENDANTS);
        setVisibility(View.VISIBLE);
        way.in(Ui.LOW_SHEET_IN, null);
        // HIGH: the cards come up through the sheet one after another, 20 ms apart; LOW arrives whole
        if (!Ui.light) {
            for (int i = 0; i < cards.size(); i++) {
                Pad c = cards.get(i);
                c.setAlpha(0f);
                c.setTranslationY(Ui.dpf(10));
                c.animate().alpha(1f).translationY(0f).setStartDelay(40 + i * 20L).setDuration(240).setInterpolator(Ui.EASE_DECEL).start();
            }
        }
        hold.start();
        announceForAccessibility(getContentDescription());
    }

    void close() {
        if (getVisibility() != View.VISIBLE) return;
        hold.stop();
        closing = true;
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        for (Pad c : cards) c.animate().cancel();
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

    /**
     * A device's card: its glyph and a dot for its state along the top, its name, and the port with
     * the state in words along the foot. One with nothing plugged in sits back until it has the focus.
     */
    private Pad card(Source s) {
        Pad p = new Pad(act, null, 20, 0f);
        p.plainColor = 0x1AFFFFFF;
        p.edgeLight = false;
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(PAD), Ui.dp(PAD), Ui.dp(PAD), Ui.dp(PAD - 2));
        LinearLayout top = new LinearLayout(act);
        top.setGravity(Gravity.CENTER_VERTICAL);
        ImageView glyph = p.tint(new ImageView(act), 1f);
        glyph.setImageResource(s.glyph());
        glyph.setScaleType(ImageView.ScaleType.FIT_CENTER);
        top.addView(glyph, new LinearLayout.LayoutParams(Ui.dp(20), Ui.dp(20)));
        top.addView(new View(act), new LinearLayout.LayoutParams(0, 1, 1f));
        View dot = new View(act);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(s.live ? GREEN : s.standby ? AMBER : 0x59FFFFFF);
        dot.setBackground(g);
        dot.setVisibility(s.kind == TUNER ? View.INVISIBLE : View.VISIBLE);
        top.addView(dot, new LinearLayout.LayoutParams(Ui.dp(7), Ui.dp(7)));
        col.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView name = p.tint(Ui.text(act, 13.5f, Ui.semibold, 0xFFFFFFFF), 1f);
        name.setText(s.name);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nl.topMargin = Ui.dp(8);
        col.addView(name, nl);
        TextView foot = p.tint(Ui.text(act, 9.5f, Ui.medium, 0xFFFFFFFF), 0.65f);
        foot.setText(s.port.isEmpty() ? s.state : s.port.toUpperCase(Locale.getDefault()) + "  ·  " + s.state);
        foot.setSingleLine(true);
        foot.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fl.topMargin = Ui.dp(3);
        col.addView(foot, fl);
        p.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (!s.live && !s.standby && s.kind != TUNER) {
            // its words and glyph sit back (the card's own alpha is the deck's entrance)
            col.setAlpha(QUIET);
            p.fillListener = f -> col.setAlpha(QUIET + (1f - QUIET) * f);
        }
        p.setContentDescription(s.name + (s.port.isEmpty() ? "" : ", " + s.port) + ", " + s.state);
        p.onClick = () -> host.open(s.intent, p);
        return p;
    }
}
