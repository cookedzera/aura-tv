package app.aura.home;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.ActivityManager;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import java.util.Locale;
import java.util.Set;

/**
 * Control Center as a modular board on frosted glass, iOS 18 style: round toggles, a tall volume
 * slider, the inputs (all of them on a sheet), memory and your day, with the time set large beside it.
 */
final class ControlCenter {
    interface Host {
        Backdrop backdrop();

        void hud(int icon, String text);

        void closeCc(boolean animate);

        void photoMode();

        void open(Intent intent, View from);

        /** {@code then}, after the parents' PIN in a kids' profile. */
        void guarded(Runnable then);

        /** Recent apps, where apps holding memory are closed. */
        void recents();

        /** What's playing, large. */
        void nowPlaying();

        /** Turns the screen pitch black while keeping sound playing. */
        void quietScreen();
    }

    private static final int U = 84, G = 14;
    /** The Settings and Standby capsules: shorter than a tile, so the board ends on a lighter note. */
    private static final int PILL_H = 58;
    /** The time in the Aura style's column. */
    private static final int TIME_SP = 56;
    /** How long the frost takes to turn opaque; after that the home screen beneath stops drawing. */
    static final long FROST_MS = 240;
    /** HIGH: each module of the board this many ms after the last, as Control Center opens. */
    private static final int CASCADE = 40;
    private static final int BLUE = 0xFF2F7BFF, ORANGE = 0xFFFF9F0A, GREEN = 0xFF30D158, VIOLET = 0xFF8E6CFF;
    private static final int MODULE = 0x1FFFFFFF;
    private static final int[] SLEEP_STEPS = {0, 15, 30, 60, 90, 120};

    final FrameLayout panel;
    private final Activity act;
    private final Host host;
    /**
     * One thread for every reading (the toggles, the inputs, the day line, the notifications), just
     * below the screen's priority: four threads started together on each refresh competed with the
     * board's own frames on a four-core TV. One worker also lands results in the order they were asked.
     */
    private final java.util.concurrent.ExecutorService reader = Compat.background("cc", android.os.Process.THREAD_PRIORITY_DEFAULT + 4);
    private final GlassFrame veil;
    private final LinearLayout header;
    private final FrameLayout board;
    private final WifiSheet wifiSheet;
    private final BtSheet btSheet;
    private final List<View> focusables = new ArrayList<>();

    /**
     * What's playing (null: nothing): a wide card with the cover takes Tonight's pick's place while
     * there's a song, and gives it back when the music stops. Focus on the card as it goes moves to Wi-Fi.
     */
    void music(MediaWatch.Playing p) {
        if (nowCard == null) return;
        boolean on = p != null;
        if (on) {
            nowTitle.setText(p.title);
            nowBy.setText(p.by != null ? p.by : "");
            // the app's name and icon from the same store as the notification rows: looked up once, not on every song
            Object[] f = p.pkg != null ? face(p.pkg) : null;
            String app = f != null && f[1] != null ? "  ·  " + ((String) f[0]).toUpperCase(Locale.getDefault()) : "";
            nowState.setText((p.playing ? act.getString(R.string.now_playing_caps) : act.getString(R.string.paused)) + app);
            nowBar.set(p.progress);
            nowBar.setVisibility(p.progress > 0f ? View.VISIBLE : View.INVISIBLE);
            if (p.art != null) {
                if (p.art != nowShown) {
                    nowShown = p.art;
                    nowArt.setImageBitmap(p.art);
                }
            } else {
                nowShown = null;
                nowArt.setImageDrawable(f != null ? (Drawable) f[1] : null);
            }
        } else {
            nowShown = null;
        }
        if (on == (nowCard.getVisibility() == View.VISIBLE)) return;
        if (!on && nowCard.hasFocus()) focusBoard();
        nowCard.setVisibility(on ? View.VISIBLE : View.GONE);
        // Tonight's pick steps aside for the song, and comes back when it ends
        if (on) {
            if (pick.hasFocus()) nowCard.requestFocus();
            pick.setVisibility(View.GONE);
        } else if (shownPick != null && Prefs.on(act, Prefs.PICKS) && !top) {
            pick.setVisibility(View.VISIBLE);
        }
    }

    /** The column's own title: shown in the Aura style, where the column has the clock. */
    private TextView headTitle;
    private final TextView time, date, info, todayValue, boostValue, inName, inState;
    private final ImageView inIcon;
    private final Dots inDots;
    private final Pad vpnPill, nowCard;
    private final TextView vpnText, nowState, nowTitle, nowBy;
    private final ImageView nowArt;
    private final HomeActivity.Meter nowBar;
    private Bitmap nowShown;
    private final LinearLayout notesBox;
    private final TextView notesCap;
    /** In the rows' place while Aura may not read the TV's notifications: OK lets it, where Aura can. */
    private final Pad notesOff;
    private final TextView notesOffText;
    private final Pad[] noteRows = new Pad[3];
    private final ImageView[] noteIcons = new ImageView[3];
    private final TextView[] noteKicks = new TextView[3], noteTitles = new TextView[3], noteBodies = new TextView[3];
    private final List<MediaWatch.Note> notesShown = new ArrayList<>();
    /** How many rows are on show: only as many as the column has room for (see its onMeasure). */
    private int noteRoom;
    private final TextView wifiCap, btCap, sleepCap, screenOffCap;
    private final Round wifi, bt, sleep, screenOff;
    private final Slider volume;
    /** The inputs tile: the source that's live, large; OK lays every source out on a sheet. */
    private final Pad inputs;
    private final InputSheet inputSheet;
    private final List<InputSheet.Source> sources = new ArrayList<>();
    private final Pad picture, sound, boost, settings, standby;
    private final Ring ring;
    private final AudioManager audio;
    private final Pad pick;
    private final ImageView pickPoster;
    private final TextView pickName, pickLine, pickAbout, pickKicker;
    /** The pick prepared in the background for the next time the board opens. */
    private volatile Picks.Pick nextPick;
    private volatile android.graphics.Bitmap nextPoster;
    private Picks.Pick shownPick;

    ControlCenter(Activity a, Host h) {
        act = a;
        host = h;
        audio = Compat.svc(a, AudioManager.class);
        panel = HomeActivity.flat(a);
        panel.setClipChildren(false);
        panel.setContentDescription(a.getString(R.string.control_center_2));
        veil = new GlassFrame(a, h.backdrop(), 0, 0.5f);
        veil.edgeLight = false;
        panel.addView(veil, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // the time, set large on the left
        header = new LinearLayout(a) {
            @Override
            public boolean hasOverlappingRendering() {
                return false;
            }

            /**
             * The notifications get only the rows there's room for above the screen's foot: a row past
             * it would be drawn off the screen yet still take the focus. The rest are counted instead.
             */
            @Override
            protected void onMeasure(int w, int h) {
                super.onMeasure(w, h);
                int filled = notesFilled(), was = noteRoom;
                if (MeasureSpec.getMode(h) != MeasureSpec.AT_MOST || filled == 0) return;
                // as tall as its room, it may be holding back: the height it's given is all it reports, its
                // last child squeezed into what's left (never flagged too small): measured again, unbounded
                boolean over = getMeasuredHeight() >= MeasureSpec.getSize(h);
                if (over) super.onMeasure(w, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) noteRows[0].getLayoutParams();
                int row = p.topMargin + p.height, room = MeasureSpec.getSize(h), height = getMeasuredHeight(), fit = was;
                while (fit > 0 && height > room) {
                    fit--;
                    height -= row;
                }
                while (fit < filled && height + row <= room) {
                    fit++;
                    height += row;
                }
                if (fit != was) fitNotes(fit);
                if (fit != was || over) super.onMeasure(w, h);
            }
        };
        header.setOrientation(LinearLayout.VERTICAL);
        TextView title = Ui.caps(a, 8, 0x99FFFFFF, 0.3f);
        headTitle = title;
        title.setText(act.getString(R.string.control_center));
        header.addView(title);
        // semibold, not heavy: the time is read at a glance, the board beside it is where the eye works
        time = Ui.text(a, TIME_SP, Ui.semibold, 0xFFFFFFFF);
        time.setIncludeFontPadding(true);
        time.setLetterSpacing(-0.045f);
        header.addView(time, lp(0, 4));
        date = Ui.text(a, 16, Ui.medium, 0xE6FFFFFF);
        header.addView(date, lp(0, 0));
        info = Ui.text(a, 12, Ui.regular, 0x8CFFFFFF);
        info.setSingleLine(false);
        info.setMaxLines(3);
        info.setLineSpacing(Ui.dpf(3), 1f);
        info.setMaxWidth(Ui.dp(300));
        header.addView(info, lp(0, 14));
        header.setClipChildren(false);
        header.setClipToPadding(false);

        // a VPN at a glance: on or off, and OK opens yours (hold OK picks which, with several)
        vpnPill = new Pad(a, null, 16, 0f);
        vpnPill.plainColor = MODULE;
        vpnPill.row = true;
        LinearLayout vr = new LinearLayout(a);
        vr.setGravity(Gravity.CENTER_VERTICAL);
        vr.setPadding(Ui.dp(12), 0, Ui.dp(14), 0);
        vr.addView(vpnPill.tint(icon(a, R.drawable.ic_vpn), 1f), new LinearLayout.LayoutParams(Ui.dp(15), Ui.dp(15)));
        vpnText = vpnPill.tint(Ui.text(a, 10.5f, Ui.medium, 0xFFFFFFFF), 0.9f);
        vpnText.setSingleLine(true);
        vpnText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        vpnText.setMaxWidth(Ui.dp(240));
        LinearLayout.LayoutParams vtl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vtl.leftMargin = Ui.dp(8);
        vr.addView(vpnText, vtl);
        vpnPill.addView(vr, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        vpnPill.onClick = () -> host.guarded(this::openVpn);
        vpnPill.onLongClick = () -> host.guarded(this::nextVpn);
        vpnPill.setVisibility(View.GONE);
        LinearLayout.LayoutParams vpl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(32));
        vpl.topMargin = Ui.dp(12);
        header.addView(vpnPill, vpl);

        // Tonight's pick: from TVmaze, or TMDB with your own key, to your taste; OK plays its trailer
        pick = new Pad(a, null, 18, 0f);
        pick.plainColor = MODULE;
        pick.row = true;
        LinearLayout pr = new LinearLayout(a);
        pr.setGravity(Gravity.CENTER_VERTICAL);
        pr.setPadding(Ui.dp(9), Ui.dp(9), Ui.dp(14), Ui.dp(9));
        pickPoster = new ImageView(a);
        pickPoster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        pickPoster.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View v, android.graphics.Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), Ui.dpf(9));
            }
        });
        pickPoster.setClipToOutline(true);
        pr.addView(pickPoster, new LinearLayout.LayoutParams(Ui.dp(64), Ui.dp(92)));
        LinearLayout pc = new LinearLayout(a);
        pc.setOrientation(LinearLayout.VERTICAL);
        TextView pk = pick.tint(Ui.caps(a, 6.5f, 0xFFFFFFFF, 0.24f), 0.6f);
        pk.setShadowLayer(0, 0, 0, 0);
        pk.setText(a.getString(R.string.tonights_pick_caps) + "  ·  TVMAZE");
        pickKicker = pk;
        pc.addView(pk, lp(0, 0));
        pickName = pick.tint(Ui.text(a, 15, Ui.heavy, 0xFFFFFFFF), 1f);
        pickName.setIncludeFontPadding(true);
        pickName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        pc.addView(pickName, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        pickLine = pick.tint(Ui.text(a, 10.5f, Ui.semibold, 0xFFFFFFFF), 0.85f);
        pickLine.setEllipsize(android.text.TextUtils.TruncateAt.END);
        pc.addView(pickLine, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        pickAbout = pick.tint(Ui.text(a, 10, Ui.regular, 0xFFFFFFFF), 0.65f);
        pickAbout.setSingleLine(false);
        pickAbout.setMaxLines(2);
        pickAbout.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams al2 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        al2.topMargin = Ui.dp(4);
        pc.addView(pickAbout, al2);
        LinearLayout.LayoutParams pcl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        pcl.leftMargin = Ui.dp(12);
        pr.addView(pc, pcl);
        pick.addView(pr, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        pick.onClick = this::watchTrailer;
        pick.setVisibility(View.GONE);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(110));
        pl.topMargin = Ui.dp(22);
        header.addView(pick, pl);

        // while music plays it takes Tonight's pick's place: a real cover, the song, how far in
        nowCard = new Pad(a, null, 18, 0f);
        nowCard.plainColor = MODULE;
        nowCard.row = true;
        LinearLayout nr = new LinearLayout(a);
        nr.setGravity(Gravity.CENTER_VERTICAL);
        nr.setPadding(Ui.dp(9), Ui.dp(9), Ui.dp(14), Ui.dp(9));
        nowArt = new ImageView(a);
        nowArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        nowArt.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View v, android.graphics.Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), Ui.dpf(10));
            }
        });
        nowArt.setClipToOutline(true);
        nr.addView(nowArt, new LinearLayout.LayoutParams(Ui.dp(92), Ui.dp(92)));
        LinearLayout nc = new LinearLayout(a);
        nc.setOrientation(LinearLayout.VERTICAL);
        nowState = nowCard.tint(Ui.caps(a, 6.5f, 0xFFFFFFFF, 0.24f), 0.6f);
        nowState.setShadowLayer(0, 0, 0, 0);
        nc.addView(nowState, lp(0, 0));
        nowTitle = nowCard.tint(Ui.text(a, 15, Ui.heavy, 0xFFFFFFFF), 1f);
        nowTitle.setIncludeFontPadding(true);
        nowTitle.setSingleLine(false);
        nowTitle.setMaxLines(2);
        nowTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        nc.addView(nowTitle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        nowBy = nowCard.tint(Ui.text(a, 10.5f, Ui.semibold, 0xFFFFFFFF), 0.8f);
        nowBy.setSingleLine(true);
        nowBy.setEllipsize(android.text.TextUtils.TruncateAt.END);
        nc.addView(nowBy, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        nowBar = new HomeActivity.Meter(a);
        LinearLayout.LayoutParams nbl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(3));
        nbl.topMargin = Ui.dp(10);
        nc.addView(nowBar, nbl);
        LinearLayout.LayoutParams ncl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ncl.leftMargin = Ui.dp(12);
        nr.addView(nc, ncl);
        nowCard.addView(nr, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        nowCard.onClick = () -> host.nowPlaying();
        nowCard.setVisibility(View.GONE);
        LinearLayout.LayoutParams ncp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(110));
        ncp.topMargin = Ui.dp(22);
        header.addView(nowCard, ncp);

        // the TV's notifications, the newest few, quietly: OK opens one, hold OK dismisses it
        notesBox = new LinearLayout(a);
        notesBox.setOrientation(LinearLayout.VERTICAL);
        notesBox.setClipChildren(false);
        notesCap = Ui.caps(a, 7, 0x8CFFFFFF, 0.24f);
        notesCap.setText(act.getString(R.string.notifications));
        notesBox.addView(notesCap);
        notesOff = new Pad(a, null, 16, 0f);
        notesOff.plainColor = MODULE;
        notesOff.edgeLight = false;
        notesOff.row = true;
        notesOffText = notesOff.tint(Ui.text(a, 10.5f, Ui.medium, 0xFFFFFFFF), 0.8f);
        notesOffText.setSingleLine(false);
        notesOffText.setMaxLines(2);
        notesOffText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        notesOffText.setPadding(Ui.dp(14), Ui.dp(10), Ui.dp(14), Ui.dp(10));
        notesOff.addView(notesOffText, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        notesOff.onClick = () -> host.guarded(this::allowNotes);
        notesOff.setVisibility(View.GONE);
        LinearLayout.LayoutParams nol = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nol.topMargin = Ui.dp(8);
        notesBox.addView(notesOff, nol);
        for (int k = 0; k < noteRows.length; k++) {
            // a card as on Apple TV: the app's round icon, its name and how long ago in a quiet line,
            // then the title and the text in clear type
            Pad row = new Pad(a, null, 20, 0f);
            row.plainColor = MODULE;
            row.edgeLight = false;
            row.row = true;
            LinearLayout rr = new LinearLayout(a);
            rr.setGravity(Gravity.CENTER_VERTICAL);
            rr.setPadding(Ui.dp(14), 0, Ui.dp(16), 0);
            ImageView ic = new ImageView(a);
            ic.setScaleType(ImageView.ScaleType.CENTER_CROP);
            ic.setClipToOutline(true);
            ic.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View v, android.graphics.Outline o) {
                    o.setOval(0, 0, v.getWidth(), v.getHeight());
                }
            });
            rr.addView(ic, new LinearLayout.LayoutParams(Ui.dp(34), Ui.dp(34)));
            LinearLayout col = new LinearLayout(a);
            col.setOrientation(LinearLayout.VERTICAL);
            TextView kick = row.tint(Ui.text(a, 9.5f, Ui.medium, 0xFFFFFFFF), 0.6f);
            kick.setSingleLine(true);
            kick.setEllipsize(android.text.TextUtils.TruncateAt.END);
            col.addView(kick);
            TextView t = row.tint(Ui.text(a, 12.5f, Ui.semibold, 0xFFFFFFFF), 1f);
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            col.addView(t, lp(0, 1));
            TextView b = row.tint(Ui.text(a, 10.5f, Ui.regular, 0xFFFFFFFF), 0.75f);
            b.setSingleLine(true);
            b.setEllipsize(android.text.TextUtils.TruncateAt.END);
            col.addView(b);
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            cl.leftMargin = Ui.dp(12);
            rr.addView(col, cl);
            row.addView(rr, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            row.setVisibility(View.GONE);
            final int at = k;
            row.onClick = () -> openNote(at);
            row.onLongClick = () -> dismissNote(at);
            noteRows[k] = row;
            noteIcons[k] = ic;
            noteKicks[k] = kick;
            noteTitles[k] = t;
            noteBodies[k] = b;
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(64));
            rl.topMargin = Ui.dp(8);
            notesBox.addView(row, rl);
        }
        notesBox.setVisibility(View.GONE);
        LinearLayout.LayoutParams nbx = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nbx.topMargin = Ui.dp(18);
        header.addView(notesBox, nbx);
        MediaWatch.onNotes = notesChanged;
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(Ui.dp(300), ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.START | Gravity.CENTER_VERTICAL);
        hl.leftMargin = Ui.dp(112);
        // inside the TV's title-safe area: a set that overscans crops what's nearer the edge
        hl.topMargin = hl.bottomMargin = Ui.dp(Ui.TEXT_SAFE_Y);
        panel.addView(header, hl);

        int side = 4 * U + 3 * G;
        // its fades go straight onto each control's draws, never through an offscreen copy
        board = HomeActivity.flat(a);
        board.setClipChildren(false);
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(Ui.dp(side), Ui.dp(side), Gravity.END | Gravity.CENTER_VERTICAL);
        bl.rightMargin = Ui.dp(96);
        panel.addView(board, bl);
        wifiSheet = new WifiSheet(a, h.backdrop(), new WifiSheet.Host() {
            @Override
            public void hud(int icon, String text) {
                host.hud(icon, text);
            }

            @Override
            public void openSettings(Intent intent) {
                host.guarded(() -> launch(intent, wifi));
            }
        });
        FrameLayout.LayoutParams wl = new FrameLayout.LayoutParams(Ui.dp(side), Ui.dp(side), Gravity.END | Gravity.CENTER_VERTICAL);
        wl.rightMargin = Ui.dp(96);
        panel.addView(wifiSheet, wl);
        btSheet = new BtSheet(a, h.backdrop(), new BtSheet.Host() {
            @Override
            public void hud(int icon, String text) {
                host.hud(icon, text);
            }

            @Override
            public void openSettings(Intent intent) {
                host.guarded(() -> launch(intent, bt));
            }
        });
        FrameLayout.LayoutParams btl = new FrameLayout.LayoutParams(Ui.dp(side), Ui.dp(side), Gravity.END | Gravity.CENTER_VERTICAL);
        btl.rightMargin = Ui.dp(96);
        panel.addView(btSheet, btl);

        // 2x2: the round toggles
        FrameLayout connect = place(module(a), 0, 0, 2, 2);
        int half = span(2) / 2;
        wifi = new Round(a, R.drawable.ic_wifi, BLUE);
        bt = new Round(a, R.drawable.ic_bluetooth, BLUE);
        sleep = new Round(a, R.drawable.ic_moon, ORANGE);
        screenOff = new Round(a, R.drawable.ic_screen_off, VIOLET);
        Round[] rounds = {wifi, bt, sleep, screenOff};
        TextView[] caps = new TextView[4];
        for (int k = 0; k < 4; k++) {
            int x = (k % 2) * half, y = (k / 2) * half;
            // 52dp circles in 60dp views: the focus rim needs room inside the view's own bounds
            FrameLayout.LayoutParams rl = new FrameLayout.LayoutParams(Ui.dp(60), Ui.dp(60));
            rl.leftMargin = Ui.dp(x + (half - 60) / 2f);
            rl.topMargin = Ui.dp(y + 9);
            connect.addView(rounds[k], rl);
            caps[k] = Ui.text(a, 11, Ui.medium, 0xD9FFFFFF);
            caps[k].setGravity(Gravity.CENTER);
            caps[k].setEllipsize(android.text.TextUtils.TruncateAt.END);
            caps[k].setPadding(Ui.dp(4), 0, Ui.dp(4), 0);
            Ui.shrinkToWidth(caps[k], 9f);
            FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(Ui.dp(half), ViewGroup.LayoutParams.WRAP_CONTENT);
            cl.leftMargin = Ui.dp(x);
            cl.topMargin = Ui.dp(y + 75);
            connect.addView(caps[k], cl);
            focusables.add(rounds[k]);
        }
        wifiCap = caps[0];
        btCap = caps[1];
        sleepCap = caps[2];
        screenOffCap = caps[3];
        label(screenOff, screenOffCap, act.getString(R.string.screen_off));

        // 1x2: volume
        volume = place(new Slider(a), 2, 0, 1, 2);
        focusables.add(volume);

        // 1x2: the inputs, where the sky was. The source that's live, large; OK opens them all on a sheet
        inputs = place(tile(new Pad(a, null, 26, 0f)), 3, 0, 1, 2);
        LinearLayout ic2 = column(a, 13);
        TextView il = inputs.tint(Ui.caps(a, 7, 0xFFFFFFFF, 0.24f), 0.55f);
        il.setText(act.getString(R.string.inputs));
        ic2.addView(il);
        inIcon = inputs.tint(new ImageView(a), 1f);
        LinearLayout.LayoutParams iil = new LinearLayout.LayoutParams(Ui.dp(26), Ui.dp(26));
        iil.topMargin = Ui.dp(18);
        ic2.addView(inIcon, iil);
        inName = inputs.tint(Ui.text(a, 15, Ui.semibold, 0xFFFFFFFF), 1f);
        inName.setSingleLine(true);
        inName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        Ui.shrinkToWidth(inName, 11f);
        ic2.addView(inName, lp(0, 10));
        inState = inputs.tint(Ui.text(a, 10, Ui.medium, 0xFFFFFFFF), 0.7f);
        inState.setSingleLine(true);
        // "Nichts angeschlossen" is wider than the tile: trailing dots rather than a hard cut
        inState.setEllipsize(android.text.TextUtils.TruncateAt.END);
        ic2.addView(inState, lp(0, 2));
        // one dot a source at the tile's foot, the live ones lit: how many there are, at a glance
        inDots = inputs.tint(new Dots(a, inputs), 1f);
        ic2.addView(new View(a), new LinearLayout.LayoutParams(1, 0, 1f));
        ic2.addView(inDots, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(8)));
        inputs.addView(ic2, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        inputs.onClick = this::showInputs;
        inputs.setVisibility(View.GONE);
        inputSheet = new InputSheet(a, h.backdrop(), (intent, from) -> launch(intent, from));
        FrameLayout.LayoutParams isl = new FrameLayout.LayoutParams(Ui.dp(side), Ui.dp(side), Gravity.END | Gravity.CENTER_VERTICAL);
        isl.rightMargin = Ui.dp(96);
        panel.addView(inputSheet, isl);

        picture = place(button(a, R.drawable.ic_picture, act.getString(R.string.picture)), 0, 2, 1, 1);
        sound = place(button(a, R.drawable.ic_volume, act.getString(R.string.sound)), 1, 2, 1, 1);

        // 1x1: memory, with a ring; OK shows recent apps, where they're closed (a "boost" that kills
        // apps from a launcher is refused by Android 11 and later anyway)
        boost = place(tile(new Pad(a, null, 26, 0f)), 2, 2, 1, 1);
        LinearLayout bc = new LinearLayout(a);
        bc.setOrientation(LinearLayout.VERTICAL);
        bc.setGravity(Gravity.CENTER);
        ring = boost.tint(new Ring(a, boost), 1f);
        bc.addView(ring, new LinearLayout.LayoutParams(Ui.dp(34), Ui.dp(34)));
        boostValue = boost.tint(Ui.text(a, 11, Ui.medium, 0xFFFFFFFF), 0.9f);
        bc.addView(boostValue, fill(lp(0, 6), boostValue, 9f));
        boost.addView(bc, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 1x1: your day
        FrameLayout today = place(module(a), 3, 2, 1, 1);
        LinearLayout tc = new LinearLayout(a);
        tc.setOrientation(LinearLayout.VERTICAL);
        tc.setGravity(Gravity.CENTER);
        todayValue = Ui.text(a, 19, Ui.heavy, 0xFFFFFFFF);
        todayValue.setLetterSpacing(-0.03f);
        tc.addView(todayValue, lp(0, 0));
        TextView tcap = Ui.text(a, 10, Ui.medium, 0x99FFFFFF);
        tcap.setText(act.getString(R.string.in_apps_today));
        tc.addView(tcap, fill(lp(0, 4), tcap, 8.5f));
        today.addView(tc, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        settings = pill(a, R.drawable.ic_settings, act.getString(R.string.settings), 0);
        standby = pill(a, R.drawable.ic_power, act.getString(R.string.standby), 2);
        focusables.addAll(Arrays.asList(inputs, picture, sound, boost, settings, standby, pick, vpnPill, nowCard));
        focusables.addAll(Arrays.asList(noteRows));
        focusables.add(notesOff);

        wifi.onClick = this::showWifi;
        bt.onClick = this::showBt;
        sleep.onClick = this::cycleSleep;
        sleep.onLongClick = host::quietScreen;
        // the screen dark, the sound on (as Standby's hold does): easier found as its own button
        screenOff.onClick = host::quietScreen;
        picture.onClick = () -> host.guarded(() -> launch(new Intent("android.settings.DISPLAY_SETTINGS"), picture));
        sound.onClick = () -> host.guarded(() -> {
            Intent s = new Intent(Settings.ACTION_SOUND_SETTINGS);
            launch(s.resolveActivity(a.getPackageManager()) != null ? s : new Intent(Settings.ACTION_SETTINGS), sound);
        });
        settings.onClick = () -> host.guarded(() -> launch(new Intent(Settings.ACTION_SETTINGS), settings));
        standby.onClick = this::standby;
        standby.onLongClick = host::quietScreen;
        // what's holding memory is best seen, and closed, in recent apps
        boost.onClick = host::recents;
        // the column and the board grow out of the corner they hang from; the column's height is known once laid out
        header.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> anchor());
        anchor();
        panel.setVisibility(View.GONE);
    }

    // ---- layout helpers ----------------------------------------------------------------------

    private static int span(int n) {
        return n * U + (n - 1) * G;
    }

    /** A label across its whole tile, centred, shrinking to fit there rather than running past the edge. */
    private static LinearLayout.LayoutParams fill(LinearLayout.LayoutParams p, TextView t, float minSp) {
        p.width = ViewGroup.LayoutParams.MATCH_PARENT;
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.dp(6), t.getPaddingTop(), Ui.dp(6), t.getPaddingBottom());
        Ui.shrinkToWidth(t, minSp);
        return p;
    }

    private static LinearLayout.LayoutParams lp(int left, int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.leftMargin = Ui.dp(left);
        p.topMargin = Ui.dp(top);
        return p;
    }

    private static LinearLayout column(Context c, int pad) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(Ui.dp(pad), Ui.dp(pad), Ui.dp(pad), Ui.dp(pad));
        return l;
    }

    private static GlassFrame module(Context c) {
        GlassFrame m = tile(new GlassFrame(c, null, 26, 0f));
        // what it holds stays inside it, and it draws its own rounded shape: LOW spares the clip
        if (Ui.light) m.setClipToOutline(false);
        return m;
    }

    /** A tile on the board: plain glass lit from above, its hairline quieter than a pane's. */
    private static <T extends GlassFrame> T tile(T g) {
        g.plainColor = MODULE;
        g.edgeStrength = 0.55f;
        g.sheen = true;
        return g;
    }

    private <T extends View> T place(T v, int col, int row, int w, int h) {
        return place(v, col, row, (float) span(w), (float) span(h));
    }

    /** At a grid cell, but {@code wDp} by {@code hDp} rather than whole cells. */
    private <T extends View> T place(T v, int col, int row, float wDp, float hDp) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(Ui.dp(wDp), Ui.dp(hDp));
        p.leftMargin = Ui.dp(col * (U + G));
        p.topMargin = Ui.dp(row * (U + G));
        board.addView(v, p);
        return v;
    }

    /** A 1x1 button module: glyph over a small label, lighting up white when focused. */
    /**
     * A second, quieter line under a button's name: what the picture and the sound are doing right
     * now. Added the first time, then only its words change.
     */
    private static void caption(Pad p, String line) {
        LinearLayout l = p.getChildCount() > 0 && p.getChildAt(0) instanceof LinearLayout ? (LinearLayout) p.getChildAt(0) : null;
        if (l == null) return;
        TextView t = (TextView) l.findViewWithTag("caption");
        if (t == null) {
            if (line == null || line.isEmpty()) return;
            t = p.tint(Ui.text(p.getContext(), 10, Ui.medium, 0xFFFFFFFF), 0.55f);
            t.setTag("caption");
            // up to two lines: the picture and sound lines put their last detail on the second
            t.setSingleLine(false);
            t.setMaxLines(2);
            t.setLineSpacing(0f, 0.95f);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.setGravity(Gravity.CENTER);
            t.setPadding(Ui.dp(6), 0, Ui.dp(6), 0);
            LinearLayout.LayoutParams cp = lp(0, 2);
            cp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            l.addView(t, cp);
        }
        if (!android.text.TextUtils.equals(t.getText(), line)) t.setText(line);
        t.setVisibility(line == null || line.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /**
     * What the TV is sending to the screen: the output's resolution and refresh rate (the mode the
     * TV runs at, often 4K while the interface itself is drawn at 1080p), and the HDR the screen
     * says it takes. Android tells any app this; hardly any home screen shows it.
     */
    private String pictureLine() {
        try {
            android.view.Display d = act.getWindowManager().getDefaultDisplay();
            int h, hz;
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                android.view.Display.Mode m = d.getMode();
                h = Math.min(m.getPhysicalWidth(), m.getPhysicalHeight());
                hz = Math.round(m.getRefreshRate());
            } else {
                android.graphics.Point pt = new android.graphics.Point();
                d.getRealSize(pt);
                h = Math.min(pt.x, pt.y);
                hz = Math.round(d.getRefreshRate());
            }
            String res = h >= 2100 ? "4K" : h >= 1400 ? "1440p" : h >= 1000 ? "1080p" : h >= 700 ? "720p" : h + "p";
            String hdr = "";
            if (android.os.Build.VERSION.SDK_INT >= 24 && d.getHdrCapabilities() != null) {
                java.util.Set<Integer> types = new java.util.HashSet<>();
                for (int t : d.getHdrCapabilities().getSupportedHdrTypes()) types.add(t);
                hdr = types.contains(android.view.Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION) ? "Dolby Vision"
                        : types.contains(4) ? "HDR10+"
                        : types.contains(android.view.Display.HdrCapabilities.HDR_TYPE_HDR10) ? "HDR10"
                        : types.contains(android.view.Display.HdrCapabilities.HDR_TYPE_HLG) ? "HLG" : "";
            }
            // the HDR on a line of its own: two short lines read whole on the tile, one long one was cut
            return res + " · " + hz + " Hz" + (hdr.isEmpty() ? "" : "\n" + hdr);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Where the sound is going, and the best surround format it takes: a soundbar over HDMI (ARC or
     * eARC), Bluetooth, optical, or the TV's own speakers; Dolby Atmos, Dolby or DTS only when the
     * device lists it outright. Android 6 and later.
     */
    private String soundLine() {
        if (android.os.Build.VERSION.SDK_INT < 23 || audio == null) return null;
        try {
            android.media.AudioDeviceInfo best = null;
            int rank = -1;
            for (android.media.AudioDeviceInfo d : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                int t = d.getType(), r;
                if (t == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) r = 6;
                else if (t == 29 /* HDMI eARC, Android 12 */ || t == android.media.AudioDeviceInfo.TYPE_HDMI_ARC) r = 5;
                else if (t == android.media.AudioDeviceInfo.TYPE_USB_HEADSET || t == android.media.AudioDeviceInfo.TYPE_USB_DEVICE) r = 4;
                else if (t == android.media.AudioDeviceInfo.TYPE_LINE_DIGITAL) r = 3;
                else if (t == android.media.AudioDeviceInfo.TYPE_HDMI) r = 2;
                else if (t == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) r = 1;
                else continue;
                if (r > rank) {
                    rank = r;
                    best = d;
                }
            }
            if (best == null) return null;
            int t = best.getType();
            String where = t == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ? act.getString(R.string.bluetooth)
                    : t == 29 ? act.getString(R.string.soundbar_earc) : t == android.media.AudioDeviceInfo.TYPE_HDMI_ARC ? act.getString(R.string.soundbar_arc)
                    : t == android.media.AudioDeviceInfo.TYPE_LINE_DIGITAL ? act.getString(R.string.optical)
                    : t == android.media.AudioDeviceInfo.TYPE_HDMI ? "HDMI"
                    : t == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ? act.getString(R.string.tv_speakers) : act.getString(R.string.usb_audio);
            java.util.Set<Integer> enc = new java.util.HashSet<>();
            for (int e : best.getEncodings()) enc.add(e);
            String format = enc.contains(18 /* E-AC-3 JOC: Dolby Atmos */) ? "Atmos"
                    : enc.contains(14 /* Dolby TrueHD */) ? "TrueHD"
                    : enc.contains(android.media.AudioFormat.ENCODING_E_AC3) || enc.contains(android.media.AudioFormat.ENCODING_AC3) ? "Dolby"
                    : enc.contains(android.media.AudioFormat.ENCODING_DTS) ? "DTS" : "";
            return format.isEmpty() ? where : where + "\n" + format;
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---- VPN -------------------------------------------------------------------------------------

    private void refreshVpn() {
        List<String> apps = Vpn.apps(act);
        boolean on = Vpn.on(act);
        String mine = Vpn.chosen(act, apps);
        showVpn(apps.isEmpty(), on, mine != null ? Vpn.label(act, mine) : null);
    }

    private void showVpn(boolean noApps, boolean on, String name) {
        if (!on && noApps) {
            if (vpnPill.hasFocus()) focusBoard();
            vpnPill.setVisibility(View.GONE);
            return;
        }
        vpnText.setText("VPN  ·  " + (on ? act.getString(R.string.connected) : act.getString(R.string.off)) + (name != null ? "  ·  " + name : ""));
        vpnText.setAlpha(on ? 1f : 0.75f);
        vpnPill.setVisibility(View.VISIBLE);
    }

    /** Your VPN app, to switch it on or off there: Android lets no other app do it. */
    private void openVpn() {
        String mine = Vpn.chosen(act, Vpn.apps(act));
        Intent i = mine != null ? Vpn.launch(act, mine) : null;
        if (i == null) {
            launch(new Intent("android.net.vpn.SETTINGS"), vpnPill);
            return;
        }
        launch(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), vpnPill);
    }

    /** Several VPN apps: hold OK goes to the next. */
    private void nextVpn() {
        List<String> apps = Vpn.apps(act);
        if (apps.size() < 2) {
            host.hud(R.drawable.ic_vpn, apps.isEmpty() ? act.getString(R.string.no_vpn_app_on_this) : act.getString(R.string.your_vpn_app_is, Vpn.label(act, apps.get(0))));
            return;
        }
        String next = apps.get((apps.indexOf(Vpn.chosen(act, apps)) + 1) % apps.size());
        Vpn.choose(act, next);
        host.hud(R.drawable.ic_vpn, act.getString(R.string.vpn_app_name, Vpn.label(act, next)));
        refreshVpn();
    }

    // ---- notifications -------------------------------------------------------------------------

    /** Kept as a field so it can be let go: a static hook left pointing here would hold the whole home screen. */
    private final Runnable notesChanged = this::notesArrived;

    private final Runnable delayedNotes = this::refreshNotes;

    /**
     * Only the notification rows are read again, a moment after the last change: a download's
     * progress ticking every second used to read the inputs, Wi-Fi, Bluetooth and memory with it.
     */
    private void notesArrived() {
        if (panel.getVisibility() != View.VISIBLE) return;
        panel.removeCallbacks(delayedNotes);
        panel.postDelayed(delayedNotes, 300);
    }

    /** The home screen is going: nothing static keeps pointing back at it. */
    void release() {
        isOpen = false;
        panel.removeCallbacks(delayedRefresh);
        panel.removeCallbacks(delayedBt);
        panel.removeCallbacks(delayedInputs);
        panel.removeCallbacks(delayedNotes);
        inputs.removeCallbacks(inputsRetry);
        if (MediaWatch.onNotes == notesChanged) MediaWatch.onNotes = null;
        // a sheet still open as home goes: its Wi-Fi or Bluetooth listener goes with it
        if (wifiSheet.isOpen()) wifiSheet.close();
        btSheet.release();
        reader.shutdown();
    }

    /** Apps' names and icons for the rows, looked up once: decoding an icon each refresh cost frames as the board opened. */
    private final java.util.Map<String, Object[]> appFaces = new java.util.concurrent.ConcurrentHashMap<>();

    private Object[] face(String pkg) {
        Object[] f = appFaces.get(pkg);
        if (f != null) return f;
        PackageManager pm = act.getPackageManager();
        try {
            f = new Object[]{String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0))), pm.getApplicationIcon(pkg)};
        } catch (Exception e) {
            f = new Object[]{pkg, null};
        }
        if (appFaces.size() > 40) appFaces.clear();
        appFaces.put(pkg, f);
        return f;
    }

    private int notesGen;

    private void refreshNotes() {
        if (panel.getVisibility() != View.VISIBLE) return;
        final int gen = ++notesGen;
        reader.execute(() -> {
            final List<MediaWatch.Note> notes = MediaWatch.notes(act, Integer.MAX_VALUE);
            final java.util.Map<String, Object[]> faces = faces(notes);
            act.runOnUiThread(() -> {
                if (gen != notesGen || act.isDestroyed() || panel.getVisibility() != View.VISIBLE) return;
                keepFaces(faces);
                showNotes(notes);
            });
        });
    }

    /** Off the main thread: the name and icon of each app behind the notes given a row, not already known. */
    private java.util.Map<String, Object[]> faces(List<MediaWatch.Note> notes) {
        java.util.Map<String, Object[]> out = new java.util.HashMap<>();
        PackageManager pm = act.getPackageManager();
        for (MediaWatch.Note n : notes.subList(0, Math.min(notes.size(), noteRows.length))) {
            if (out.containsKey(n.pkg) || appFaces.containsKey(n.pkg)) continue;
            try {
                out.put(n.pkg, new Object[]{String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(n.pkg, 0))), pm.getApplicationIcon(n.pkg)});
            } catch (Exception e) {
                out.put(n.pkg, new Object[]{n.pkg, null});
            }
        }
        return out;
    }

    private void keepFaces(java.util.Map<String, Object[]> faces) {
        for (java.util.Map.Entry<String, Object[]> e : faces.entrySet()) if (!appFaces.containsKey(e.getKey())) appFaces.put(e.getKey(), e.getValue());
    }

    private void showNotes(List<MediaWatch.Note> list) {
        notesShown.clear();
        notesShown.addAll(list);
        int filled = notesFilled();
        long now = System.currentTimeMillis();
        for (int k = 0; k < filled; k++) {
            MediaWatch.Note n = notesShown.get(k);
            Object[] f = face(n.pkg);
            String app = (String) f[0];
            android.graphics.drawable.Drawable icon = (android.graphics.drawable.Drawable) f[1];
            if (noteIcons[k].getDrawable() != icon) noteIcons[k].setImageDrawable(icon);
            // an app that gave no time (or a clock not yet set after a restart) shows just its name
            boolean dated = n.when > 0 && now - n.when >= 0 && now - n.when < 30L * 86_400_000L;
            noteKicks[k].setText(dated ? app + "  ·  " + Habits.ago(now - n.when) : app);
            noteTitles[k].setText(n.title);
            noteBodies[k].setText(n.text);
            noteBodies[k].setVisibility(n.text.isEmpty() ? View.GONE : View.VISIBLE);
        }
        // rows only ever come on show where the column's measure finds room for them
        fitNotes(Math.min(noteRoom, filled));
        if (noteRoom < filled) header.requestLayout();
        // a kids' profile is never asked to set anything up
        boolean off = !MediaWatch.notesReadable() && !Profiles.kids(act), fix = off && Adb.on(act);
        if (notesOff.hasFocus() && !fix) focusBoard();
        notesOff.setFocusable(fix);
        notesOffText.setText(act.getString(fix ? R.string.cant_read_notifications_ok : R.string.cant_read_notifications_setup));
        notesOff.setVisibility(off ? View.VISIBLE : View.GONE);
        notesBox.setVisibility(list.isEmpty() && !off ? View.GONE : View.VISIBLE);
    }

    /** How many rows, from the first, have a notification to show. */
    private int notesFilled() {
        return Math.min(noteRows.length, notesShown.size());
    }

    /** The first {@code n} rows on show, the rest put away; the notifications on none of them are counted. */
    private void fitNotes(int n) {
        noteRoom = n;
        for (int k = 0; k < noteRows.length; k++) {
            if (k >= n && noteRows[k].hasFocus()) focusBoard();
            noteRows[k].setVisibility(k < n ? View.VISIBLE : View.GONE);
        }
        // counted on the title line, which adds no height to the column
        int more = notesShown.size() - n;
        notesCap.setText(more > 0 ? act.getString(R.string.notifications) + "  ·  " + act.getResources().getQuantityString(R.plurals.n_more_caps, more, more)
                : act.getString(R.string.notifications));
    }

    /** Aura approved to read the TV's notifications over the TV's own debugging link, as setup does it. */
    private void allowNotes() {
        final Context app = act.getApplicationContext();
        new SafeThread(() -> {
            // "cmd notification allow_listener" arrived in Android 8.1; before it the setting itself is written
            List<String> out = Adb.run(app, android.os.Build.VERSION.SDK_INT >= 27
                    ? "cmd notification allow_listener " + app.getPackageName() + "/" + MediaWatch.class.getName()
                    : MediaWatch.enableCommand(app));
            // once Android connects the listener, the rows refresh and this line goes by itself
            if (out != null && !out.get(0).contains("Exception") && !out.get(0).contains("Error")) return;
            act.runOnUiThread(() -> {
                if (!act.isDestroyed()) host.hud(R.drawable.ic_info, act.getString(out == null ? R.string.couldnt_reach_the_tvs_debugging : R.string.the_tv_didnt_allow_that));
            });
        }, "cc-notes-allow").start();
    }

    /** What the notification leads to, as the TV's own panel would open it. */
    private void openNote(int k) {
        if (k >= notesShown.size()) return;
        MediaWatch.Note n = notesShown.get(k);
        if (n.open != null) {
            try {
                host.closeCc(false);
                n.open.send();
                return;
            } catch (android.app.PendingIntent.CanceledException ignored) {
                // the app took it back: open the app instead
            }
        }
        Intent i = Vpn.launch(act, n.pkg);
        if (i != null) launch(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), noteRows[k]);
    }

    private void dismissNote(int k) {
        if (k >= notesShown.size()) return;
        MediaWatch.Note n = notesShown.get(k);
        if (!n.clearable) {
            host.hud(R.drawable.ic_info, act.getString(R.string.this_one_stays_until_its));
            return;
        }
        MediaWatch.dismiss(n.key);
        host.hud(R.drawable.ic_check, act.getString(R.string.dismissed));
    }

    private static Pad button(Context c, int iconRes, String label) {
        Pad p = tile(new Pad(c, null, 26, 0f));
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER);
        l.addView(p.tint(icon(c, iconRes), 1f), new LinearLayout.LayoutParams(Ui.dp(21), Ui.dp(21)));
        TextView t = p.tint(Ui.text(c, 11, Ui.medium, 0xFFFFFFFF), 0.9f);
        t.setText(label);
        l.addView(t, fill(lp(0, 6), t, 9f));
        p.addView(l, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return p;
    }

    /** A wide, short capsule along the board's foot: glyph and name side by side, as tvOS sets them. */
    private Pad pill(Context c, int iconRes, String label, int col) {
        Pad p = tile(new Pad(c, null, PILL_H / 2f, 0f));
        LinearLayout l = new LinearLayout(c);
        l.setGravity(Gravity.CENTER);
        l.addView(p.tint(icon(c, iconRes), 1f), new LinearLayout.LayoutParams(Ui.dp(19), Ui.dp(19)));
        TextView t = p.tint(Ui.text(c, 13, Ui.medium, 0xFFFFFFFF), 0.92f);
        t.setText(label);
        t.setSingleLine(true);
        Ui.shrinkToWidth(t, 10f);
        l.addView(t, lp(10, 0));
        p.addView(l, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return place(p, col, 3, (float) span(2), (float) PILL_H);
    }

    static ImageView icon(Context c, int res) {
        ImageView iv = new ImageView(c);
        iv.setImageResource(res);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        return iv;
    }

    /** The tvOS style: the board hangs from the top-right corner, as on Apple TV. */
    private boolean top;

    void top(boolean on) {
        top = on;
        // the tvOS style keeps the board up in the top right corner: a small time and date sit at
        // the top of the column beside it (home's clock is under the frost), then what's live (the
        // song, the VPN, notifications). Never the large CONTROL CENTER title, the day line, or
        // Tonight's pick. It used to hide the column's clock with them, and the column went empty.
        headTitle.setVisibility(on ? View.GONE : View.VISIBLE);
        info.setVisibility(on ? View.GONE : View.VISIBLE);
        time.setVisibility(View.VISIBLE);
        date.setVisibility(View.VISIBLE);
        clock(on);
        if (on) pick.setVisibility(View.GONE);
        FrameLayout.LayoutParams hl = (FrameLayout.LayoutParams) header.getLayoutParams();
        hl.gravity = on ? Gravity.END | Gravity.TOP : Gravity.START | Gravity.CENTER_VERTICAL;
        hl.leftMargin = on ? 0 : Ui.dp(112);
        hl.rightMargin = on ? Ui.dp(40 + 4 * U + 3 * G + 22) : 0;
        hl.topMargin = on ? Ui.dp(84) : Ui.dp(Ui.TEXT_SAFE_Y);
        hl.bottomMargin = Ui.dp(Ui.TEXT_SAFE_Y);
        header.setLayoutParams(hl);
        LinearLayout.LayoutParams vl = (LinearLayout.LayoutParams) vpnPill.getLayoutParams();
        vl.topMargin = Ui.dp(on ? 14 : 12);
        vl.bottomMargin = on ? Ui.dp(10) : 0;
        vpnPill.setLayoutParams(vl);
        LinearLayout.LayoutParams nl = (LinearLayout.LayoutParams) nowCard.getLayoutParams();
        nl.topMargin = Ui.dp(on ? 14 : 22);
        nowCard.setLayoutParams(nl);
        LinearLayout.LayoutParams xl = (LinearLayout.LayoutParams) notesBox.getLayoutParams();
        xl.topMargin = on ? Ui.dp(12) : Ui.dp(18);
        notesBox.setLayoutParams(xl);
        for (View v : new View[]{board, wifiSheet, btSheet, inputSheet}) {
            FrameLayout.LayoutParams l = (FrameLayout.LayoutParams) v.getLayoutParams();
            l.gravity = Gravity.END | (on ? Gravity.TOP : Gravity.CENTER_VERTICAL);
            l.topMargin = on ? Ui.dp(84) : 0;
            l.rightMargin = Ui.dp(on ? 40 : 96);
            v.setLayoutParams(l);
        }
        anchor();
    }

    /**
     * The time and date as the column shows them: large on the left in the Aura style, small and
     * against the board's left edge in the tvOS style, sharing the board's top.
     */
    private void clock(boolean tvos) {
        time.setTextSize(TypedValue.COMPLEX_UNIT_SP, tvos ? 28 : TIME_SP);
        time.setLetterSpacing(tvos ? -0.035f : -0.045f);
        time.setIncludeFontPadding(!tvos);
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, tvos ? 13 : 16);
        int g = tvos ? Gravity.END : Gravity.START;
        time.setGravity(g);
        date.setGravity(g);
        LinearLayout.LayoutParams tp = (LinearLayout.LayoutParams) time.getLayoutParams();
        tp.width = tvos ? ViewGroup.LayoutParams.MATCH_PARENT : ViewGroup.LayoutParams.WRAP_CONTENT;
        tp.gravity = g;
        tp.topMargin = Ui.dp(tvos ? 0 : 4);
        time.setLayoutParams(tp);
        LinearLayout.LayoutParams dp = (LinearLayout.LayoutParams) date.getLayoutParams();
        dp.width = tvos ? ViewGroup.LayoutParams.MATCH_PARENT : ViewGroup.LayoutParams.WRAP_CONTENT;
        dp.gravity = g;
        date.setLayoutParams(dp);
    }

    /**
     * Where the column and the board grow from as Control Center opens: the corner they hang from in
     * the tvOS style (both at the top right), in the Aura style the board's right edge and the
     * column's left one, each at its middle.
     */
    private void anchor() {
        int side = Ui.dp(4 * U + 3 * G);
        board.setPivotX(side);
        board.setPivotY(top ? 0f : side / 2f);
        header.setPivotX(top ? header.getWidth() : 0f);
        header.setPivotY(top ? 0f : header.getHeight() / 2f);
    }

    // ---- open / close / keys -----------------------------------------------------------------

    private boolean isOpen;
    private final Runnable delayedRefresh = this::refresh, delayedBt = this::showBt, delayedInputs = this::showInputs,
            inputsRetry = this::buildSegments;

    void open(boolean inputs) {
        isOpen = true;
        panel.removeCallbacks(delayedRefresh);
        panel.removeCallbacks(delayedBt);
        panel.removeCallbacks(delayedInputs);
        // readings are kept warm while the home screen idles; opening only refreshes them once the
        // board has settled, so no system lookups land on the first frames of the animation
        if (inputsSig == null) {
            refresh();
        } else {
            volume.sync(); // the remote's volume keys may have moved it while the board was closed
            panel.postDelayed(delayedRefresh, 700);
        }
        tick(Calendar.getInstance());
        showPick();
        board.setVisibility(View.VISIBLE);
        board.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        panel.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        panel.animate().cancel();
        panel.setAlpha(1f);
        panel.setVisibility(View.VISIBLE);
        veil.animate().cancel();
        if (Ui.light) {
            // LOW: the frost whole on the first frame, and the home screen beneath stops drawing with it.
            // Dissolving it in drew the photograph, the home screen, the frost and the board over one
            // another for 240 ms, 17 ms of GPU a frame on a Mali-470: every frame of the opening came late
            veil.setAlpha(1f);
        } else {
            // the frost is opaque by the time the home screen beneath stops drawing (FROST_MS)
            veil.setAlpha(0f);
            veil.animate().alpha(1f).setStartDelay(0).setDuration(FROST_MS).setInterpolator(Ui.SOFT).start();
        }
        // the column one frame ahead of the board; HIGH: the board's modules then one after another
        arrive(header, 0);
        arrive(board, Ui.light ? 0 : 16);
        if (!Ui.light) cascade(board);
        panel.announceForAccessibility(panel.getContentDescription());
        wifi.requestFocus();
        // the TV's own Inputs button: straight to the sheet of sources, once the board is there
        if (inputs && !sources.isEmpty()) panel.postDelayed(delayedInputs, Ui.light ? Ui.LOW_CC_OPEN : Ui.CC_OPEN);
    }

    /**
     * HIGH: alpha and ×0.90 to 1 about its anchor in {@link Ui#CC_OPEN} ms, decelerating, on a layer
     * while it grows (scaled text would be drawn again every frame). LOW: alpha and an 8 dp drop in
     * {@link Ui#LOW_CC_OPEN}, no layer, nothing scaled.
     */
    private static void arrive(View v, long delay) {
        boolean low = Ui.light;
        v.animate().cancel();
        v.setAlpha(0f);
        v.setScaleX(low ? 1f : 0.9f);
        v.setScaleY(low ? 1f : 0.9f);
        v.setTranslationX(0f);
        v.setTranslationY(low ? -Ui.dpf(8) : 0f);
        android.view.ViewPropertyAnimator a = v.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                .setStartDelay(delay).setDuration(low ? Ui.LOW_CC_OPEN : Ui.CC_OPEN).setInterpolator(Ui.EASE_DECEL);
        if (!low) a.withLayer();
        a.start();
    }

    /** HIGH: the board's modules come up one after another, {@link #CASCADE} ms apart; LOW arrives whole. */
    private static void cascade(ViewGroup board) {
        int i = 0;
        for (int k = 0; k < board.getChildCount(); k++) {
            View m = board.getChildAt(k);
            if (m.getVisibility() != View.VISIBLE) continue;
            m.animate().cancel();
            m.setAlpha(0f);
            m.setTranslationY(Ui.dpf(8));
            m.animate().alpha(1f).translationY(0f).setStartDelay(40 + i * (long) CASCADE).setDuration(240)
                    .setInterpolator(Ui.EASE_DECEL).start();
            i++;
        }
    }

    /** Modules left mid-cascade sit at rest, so a close or the next opening doesn't inherit their motion. */
    private static void rest(ViewGroup board) {
        for (int k = 0; k < board.getChildCount(); k++) {
            View m = board.getChildAt(k);
            m.animate().cancel();
            m.setAlpha(1f);
            m.setTranslationY(0f);
        }
    }

    void close(boolean animate, Runnable done) {
        isOpen = false;
        panel.removeCallbacks(delayedRefresh);
        panel.removeCallbacks(delayedBt);
        panel.removeCallbacks(delayedInputs);
        inputs.removeCallbacks(inputsRetry);
        // the sheets go with the panel, and the next opening sets the board up again. The Inputs sheet
        // left open came back over the board next time, stale, with the focus underneath it
        if (wifiSheet.isOpen()) wifiSheet.close();
        if (btSheet.isOpen()) btSheet.close();
        if (inputSheet.isOpen()) inputSheet.close();
        rest(board);
        // fading out, nothing in it takes the focus: the arrows on the home screen can't land on it
        panel.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        if (animate && !Ui.light) {
            // HIGH goes back into the corner it came from as it fades
            for (View v : new View[]{header, board}) {
                v.animate().cancel();
                v.animate().scaleX(0.9f).scaleY(0.9f).setStartDelay(0).setDuration(Ui.CLOSE)
                        .setInterpolator(Ui.EASE_STANDARD).withLayer().start();
            }
        }
        // LOW: the frost goes at once and only the clock and the board fade, over the home screen:
        // fading the frost too was one more full screen blended on every frame of the close
        if (animate && Ui.light) {
            veil.animate().cancel();
            veil.setAlpha(0f);
        }
        panel.animate().alpha(0f).setStartDelay(0).setDuration(animate ? Ui.CLOSE : 0).setInterpolator(Ui.EASE_STANDARD)
                .withEndAction(() -> {
                    if (isOpen) return;
                    panel.setVisibility(View.GONE);
                    panel.setAlpha(1f);
                    if (done != null) done.run();
                }).start();
    }

    /** The Wi-Fi sheet grows over the board out of its button; the board stops taking focus until it closes. */
    private void showWifi() {
        if (!isOpen) return;
        if (!wifiSheet.present()) {
            // a wired-only box: its own network settings, rather than a sheet with nothing to ask
            host.guarded(() -> launch(new android.content.Intent(android.provider.Settings.ACTION_SETTINGS), wifi));
            return;
        }
        onlySheet(wifiSheet);
        boardAway(wifiSheet, wifi);
        wifiSheet.open();
    }

    private void hideWifi() {
        wifiSheet.close();
        if (sheetOpen()) return;
        boardBack();
        refreshNetwork();
        wifi.requestFocus();
    }

    /**
     * A sheet is coming over the board: the board fades in the time of a close and takes no focus
     * from now on (it would otherwise until it's hidden), and the sheet grows out of the control
     * that opened it, its pivot on that control's centre (the board and the sheets share one place).
     */
    private void boardAway(View sheet, View from) {
        rest(board);
        board.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        board.animate().cancel();
        board.animate().alpha(0f).scaleX(1f).scaleY(1f).translationY(0f).setStartDelay(0).setDuration(Ui.CLOSE)
                .setInterpolator(Ui.EASE_STANDARD).withEndAction(() -> {
                    if (sheetOpen()) board.setVisibility(View.INVISIBLE);
                }).start();
        float x = from.getWidth() / 2f, y = from.getHeight() / 2f;
        for (View v = from; v != board && v.getParent() instanceof View; v = (View) v.getParent()) {
            x += v.getLeft();
            y += v.getTop();
        }
        sheet.setPivotX(x);
        sheet.setPivotY(y);
    }

    /** The board again as the last sheet goes, fading back in while the sheet fades out. */
    private void boardBack() {
        rest(board);
        board.animate().cancel();
        board.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        board.setVisibility(View.VISIBLE);
        board.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f).setStartDelay(0).setDuration(Ui.CLOSE)
                .setInterpolator(Ui.EASE_DECEL).start();
    }

    /** Back steps out of the Wi-Fi sheet before it closes Control Center. Returns true if it was used. */
    /** Straight to the Bluetooth sheet, once the board has settled (the stack's low-battery card). */
    void showBluetoothSoon() {
        panel.removeCallbacks(delayedBt);
        panel.postDelayed(delayedBt, 500);
    }

    private void showBt() {
        if (!isOpen) return;
        if (BluetoothAdapter.getDefaultAdapter() == null) {
            // a box with no Bluetooth at all: Android's settings, not a sheet that can't switch anything on
            host.guarded(() -> launch(new android.content.Intent(android.provider.Settings.ACTION_SETTINGS), bt));
            return;
        }
        onlySheet(btSheet);
        boardAway(btSheet, bt);
        btSheet.open();
    }

    private void hideBt() {
        btSheet.close();
        if (sheetOpen()) return;
        boardBack();
        refreshNetwork();
        bt.requestFocus();
    }

    boolean back() {
        if (inputSheet.isOpen()) {
            hideInputs();
            return true;
        }
        if (btSheet.isOpen()) {
            hideBt();
            return true;
        }
        if (!wifiSheet.isOpen()) return false;
        if (!wifiSheet.back()) hideWifi();
        return true;
    }

    /** Arrow keys move between modules by geometry; the volume slider keeps up/down for itself. */
    boolean handleKey(int code, View focused) {
        // inside a sheet the list scrolls and focuses itself; taking the key here sent focus to the
        // Wi-Fi button on the hidden board (requestFocus doesn't mind an invisible parent)
        if (wifiSheet.isOpen() || btSheet.isOpen() || inputSheet.isOpen()) return false;
        int dir;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                dir = View.FOCUS_LEFT;
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                dir = View.FOCUS_RIGHT;
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                dir = View.FOCUS_UP;
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                dir = View.FOCUS_DOWN;
                break;
            default:
                return false;
        }
        if (focused == volume && (dir == View.FOCUS_UP || dir == View.FOCUS_DOWN)) return false;
        List<View> all = new ArrayList<>(focusables);
        if (focused == null || !all.contains(focused)) {
            focusBoard();
            return true;
        }
        View next = Nav.next(all, focused, dir);
        if (next != null) next.requestFocus();
        return true;
    }

    // ---- live state --------------------------------------------------------------------------

    /** Each refresh's readings, so an older one arriving late doesn't paint over a newer one. */
    private int readGen;

    /** Location (for Wi-Fi scanning) was just allowed while the Wi-Fi sheet waited on it. */
    void wifiGranted() {
        if (wifiSheet.isOpen()) wifiSheet.granted();
    }

    void refresh() {
        volume.sync();
        refreshSleep();
        // the rest are calls into Android (the picture and sound, VPN apps, notifications and their
        // apps, Wi-Fi, Bluetooth, memory): read off the main thread, then shown. Just after a restart
        // Android answers them slowly, and on the main thread they held the remote for seconds.
        // The board's own readings go first; the inputs, the slowest to answer, last
        final int gen = ++readGen;
        reader.execute(() -> {
            final String pic = pictureLine(), snd = soundLine();
            List<String> apps = Vpn.apps(act);
            final boolean vpnOn = Vpn.on(act), noVpnApps = apps.isEmpty();
            String mine = Vpn.chosen(act, apps);
            final String vpnName = mine != null ? Vpn.label(act, mine) : null;
            final List<MediaWatch.Note> notes = MediaWatch.notes(act, Integer.MAX_VALUE);
            final java.util.Map<String, Object[]> faces = faces(notes);
            final Object[] net = readNetwork();
            final ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            Compat.svc(act, ActivityManager.class).getMemoryInfo(mi);
            act.runOnUiThread(() -> {
                if (gen != readGen || act.isDestroyed()) return;
                // what the picture and the sound are doing now, under their buttons
                caption(picture, pic);
                caption(sound, snd);
                showVpn(noVpnApps, vpnOn, vpnName);
                keepFaces(faces);
                showNotes(notes);
                showNetwork(net);
                showMemory(mi);
            });
        });
        refreshDay();
        buildSegments();
    }

    /** Off the main thread: {@code {online, on Wi-Fi, Bluetooth on, Bluetooth's caption}}. */
    private Object[] readNetwork() {
        boolean online = Compat.onWifi(Compat.svc(act, ConnectivityManager.class), true);
        // a wired box is online but not on Wi-Fi: the button lights, its caption says which
        boolean onWifi = online && Compat.onWifi(Compat.svc(act, ConnectivityManager.class), false);
        BluetoothAdapter ba = BluetoothAdapter.getDefaultAdapter();
        boolean btOn = ba != null && ba.isEnabled();
        String btName = btOn ? btSheet.connectedName() : act.getString(R.string.bluetooth_off);
        return new Object[]{online, onWifi, btOn, btName};
    }

    private void showNetwork(Object[] net) {
        boolean online = (Boolean) net[0], onWifi = (Boolean) net[1], btOn = (Boolean) net[2];
        wifi.setOn(online);
        label(wifi, wifiCap, act.getString(onWifi ? R.string.wi_fi : online ? R.string.ethernet : R.string.offline));
        bt.setOn(btOn);
        label(bt, btCap, (String) net[3]);
    }

    /**
     * After the Wi-Fi or Bluetooth sheet: only what it could have changed is read again, not the
     * inputs, the day and every notification with it. No generation to check: the one reader lands
     * results in the order they were asked, so this is never older than what's on show.
     */
    private void refreshNetwork() {
        reader.execute(() -> {
            final Object[] net = readNetwork();
            act.runOnUiThread(() -> {
                if (!act.isDestroyed()) showNetwork(net);
            });
        });
    }

    void tick(Calendar now) {
        boolean h24 = DateFormat.is24HourFormat(act);
        String t = new SimpleDateFormat(h24 ? "H:mm" : "h:mm", Locale.getDefault()).format(now.getTime());
        time.setText(h24 ? t : t + " " + new java.text.DateFormatSymbols().getAmPmStrings()[now.get(Calendar.AM_PM)]);
        date.setText(Words.date("EEEE, d MMMM", now.getTimeInMillis()));
        refreshSleep();
    }

    private void refreshSleep() {
        long left = Power.sleepLeftMs(act);
        sleep.setOn(left > 0);
        label(sleep, sleepCap, left <= 0 ? act.getString(R.string.sleep_timer) : act.getString(Power.atEnd(act) ? R.string.at_the_end_value : R.string.sleep_in_value, duration((int) Math.ceil(left / 60_000.0))));
    }

    /** A round button's caption, which is also what TalkBack says for the button. */
    private static void label(Round r, TextView cap, String s) {
        cap.setText(s);
        r.setContentDescription(s);
    }

    private void refreshMemory() {
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        Compat.svc(act, ActivityManager.class).getMemoryInfo(mi);
        showMemory(mi);
    }

    private void showMemory(ActivityManager.MemoryInfo mi) {
        ring.set(mi.totalMem > 0 ? mi.availMem / (float) mi.totalMem : 0f);
        boostValue.setText(act.getString(R.string.mb_free, mi.availMem / (1024 * 1024)));
    }

    /** Sky and day, read off-thread: sun times, the light outside, time spent in apps. */
    private void refreshDay() {
        reader.execute(() -> {
            long now = System.currentTimeMillis();
            Calendar c = Calendar.getInstance();
            c.set(Calendar.HOUR_OF_DAY, 0);
            c.set(Calendar.MINUTE, 0);
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            long today = Habits.todayMs(act);
            final String day = today >= 0 ? compact(today) : "–";
            int fresh = Gallery.walls(act).size();
            Weather.Now wx = Weather.now(act);
            String outside = wx == null ? "" : (wx.city.isEmpty() ? act.getString(R.string.outside_weather, wx.temp, Words.inSentence(wx.condition()))
                    : act.getString(R.string.outside_weather_in, wx.temp, Words.inSentence(wx.condition()), wx.city)) + " ";
            final String line = outside + Sun.photosFollow(Sun.phase(now))
                    + (fresh > 0 ? "\n" + act.getResources().getQuantityString(R.plurals.fresh_photos_on_the_wall, fresh, fresh) : "")
                    + (today >= 0 ? "\n" + act.getString(R.string.duration_in_apps_today, Habits.duration(today)) : "");
            preparePick();
            act.runOnUiThread(() -> {
                if (act.isDestroyed()) return;
                if (shownPick == null) showPick(); // first time: no need to wait for the next opening
                todayValue.setText(day);
                info.setText(line);
            });
        });
    }

    /** Off the main thread: the next pick in the pool and its poster, ready for the next opening. */
    private void preparePick() {
        if (nextPick != null) return;
        List<Picks.Pick> all = Picks.load(act);
        if (all.isEmpty()) return;
        android.content.SharedPreferences prefs = act.getSharedPreferences("aura", Context.MODE_PRIVATE);
        int at = prefs.getInt("pick_at", 0);
        Picks.Pick p = all.get(Compat.mod(at, all.size()));
        nextPoster = Picks.poster(p);
        nextPick = p;
        prefs.edit().putInt("pick_at", at + 1).apply();
    }

    /** A different show each time the board opens. */
    private void showPick() {
        if (!Prefs.on(act, Prefs.PICKS)) {
            pick.setVisibility(View.GONE);
            return;
        }
        Picks.Pick p = nextPick;
        if (p == null) return;
        shownPick = p;
        pickPoster.setImageBitmap(nextPoster);
        pickName.setText(p.name);
        pickLine.setText(p.line);
        pickKicker.setText(act.getString(R.string.tonights_pick_caps) + "  ·  " + p.src.toUpperCase(Locale.ROOT));
        pickAbout.setText(p.about);
        pickAbout.setVisibility(p.about.isEmpty() ? View.GONE : View.VISIBLE);
        // a song on takes its place, and the tvOS style has no room for it
        pick.setVisibility(top || nowCard.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
        nextPick = null;
        nextPoster = null;
    }

    /** Its trailer on YouTube, or the TV's own search when YouTube isn't installed. */
    private void watchTrailer() {
        if (shownPick == null) return;
        launch(Picks.watch(act, shownPick), pick);
    }

    /** The pool was made again for a new taste (or switched off): the next opening shows a pick from it. */
    void picksChanged() {
        nextPick = null;
        nextPoster = null;
        shownPick = null;
        if (!Prefs.on(act, Prefs.PICKS)) pick.setVisibility(View.GONE);
    }

    private static String compact(long ms) {
        long min = ms / 60_000;
        return min < 60 ? min + "m" : (min / 60) + "h " + (min % 60) + "m";
    }

    /**
     * The TV's inputs, read off the main thread: asking the input service for each input's state
     * and name (and the CEC service for the box behind it) held the remote for a second or more on
     * a slow TV, every time Control Center opened or a notification came in. What was found is
     * applied on the main thread, and only when an input, its state or its name actually changed.
     */
    private void buildSegments() {
        final int gen = ++inputsGen;
        reader.execute(() -> {
            final Object[] found;
            try {
                found = readInputs();
            } catch (RuntimeException e) {
                // the TV's input service not answering: Control Center opens without the input buttons
                // (a stick without one, like a Fire TV Stick, simply has none: nothing to report)
                if (!Compat.noTvInputs(act)) Trouble.caught("Control Center inputs", e);
                return;
            }
            act.runOnUiThread(() -> {
                if (gen != inputsGen || act.isDestroyed()) return;
                applyInputs(found);
            });
        });
    }

    /** Off the main thread: {@code {signature, sources, lead}}, or null for a TV with no input service. */
    private Object[] readInputs() {
        TvInputManager tim = (TvInputManager) act.getSystemService(Context.TV_INPUT_SERVICE);
        if (tim == null) return null;
        List<TvInputInfo> list = new ArrayList<>();
        for (TvInputInfo info : tim.getTvInputList()) {
            // hiding an input in the TV's settings arrived in Android 7
            if (android.os.Build.VERSION.SDK_INT >= 24 && info.isHidden(act)) continue;
            if (info.isPassthroughInput() || builtInTuner(info)) list.add(info);
        }
        // each input's port and name once: they were looked up again for the sort, the signature and the row
        final java.util.Map<String, String> ports = new java.util.HashMap<>(), labels = new java.util.HashMap<>();
        final java.util.Map<String, Integer> states = new java.util.HashMap<>();
        for (TvInputInfo info : list) {
            String port = port(info);
            ports.put(info.getId(), port);
            labels.put(info.getId(), label(info, port));
            states.put(info.getId(), tim.getInputState(info.getId()));
        }
        // HDMI first, in the order of the ports (a device stays where you remember it), then the
        // TV's own tuners (antenna, cable, satellite), then the rest
        java.util.Collections.sort(list, (x, y) -> {
            int tx = order(x), ty = order(y);
            return tx != ty ? tx - ty : ports.get(x.getId()).compareTo(ports.get(y.getId()));
        });
        StringBuilder sig = new StringBuilder();
        for (TvInputInfo info : list) sig.append(info.getId()).append('=').append(states.get(info.getId())).append(':').append(labels.get(info.getId())).append('|');
        List<InputSheet.Source> out = new ArrayList<>();
        InputSheet.Source lead = null;
        for (TvInputInfo info : list) {
            int state = states.get(info.getId());
            boolean tuner = !info.isPassthroughInput(), hdmi = info.getType() == TvInputInfo.TYPE_HDMI;
            boolean live = !tuner && state == TvInputManager.INPUT_STATE_CONNECTED;
            boolean standby = !tuner && state == TvInputManager.INPUT_STATE_CONNECTED_STANDBY;
            String what = tuner ? act.getString(R.string.input_channels)
                    : live ? act.getString(R.string.input_live)
                    : standby ? act.getString(R.string.input_standby)
                    : act.getString(R.string.input_nothing);
            Intent open = tuner ? tunerIntent(info) : new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(info.getId()));
            String name = labels.get(info.getId()), port = ports.get(info.getId());
            int kind = InputSheet.kindOf(name, hdmi ? Cec.type(act, port) : Cec.UNKNOWN, hdmi, tuner);
            InputSheet.Source src = new InputSheet.Source(info.getId(), name, port, what, kind, live, standby, open);
            out.add(src);
            if (lead == null && live) lead = src;
        }
        if (lead == null && !out.isEmpty()) lead = out.get(0);
        return new Object[]{sig.toString(), out, lead};
    }

    @SuppressWarnings("unchecked")
    private void applyInputs(Object[] found) {
        if (found == null) {
            inputs.setVisibility(View.GONE);
            return;
        }
        List<InputSheet.Source> list = (List<InputSheet.Source>) found[1];
        // Sony and TCL sets can answer with no inputs at all for a while after switching on: asked
        // again a few times before Control Center settles for none
        if (list.isEmpty() && Compat.justBooted() && inputRetries < 5) {
            inputRetries++;
            inputs.removeCallbacks(inputsRetry);
            inputs.postDelayed(inputsRetry, 2000);
        }
        if (found[0].equals(inputsSig)) return;
        inputsSig = (String) found[0];
        sources.clear();
        sources.addAll(list);
        inputs.setVisibility(sources.isEmpty() ? View.GONE : View.VISIBLE);
        InputSheet.Source lead = (InputSheet.Source) found[2];
        if (lead == null) return;
        inIcon.setImageResource(lead.glyph());
        inName.setText(lead.name);
        inState.setText(lead.state);
        boolean[] lit = new boolean[sources.size()];
        for (int i = 0; i < lit.length; i++) lit[i] = sources.get(i).live;
        inDots.set(lit);
        // TalkBack: the count the dots stand for
        inputs.setContentDescription(act.getString(R.string.inputs_title) + ", " + lead.name + ", " + lead.state + ", "
                + act.getResources().getQuantityString(R.plurals.n_sources, sources.size(), sources.size()));
    }

    private int inputRetries, inputsGen;
    private String inputsSig;

    private static int order(TvInputInfo info) {
        return info.getType() == TvInputInfo.TYPE_HDMI ? 0 : info.getType() == TvInputInfo.TYPE_TUNER ? 1 : 2;
    }

    /**
     * The TV's own tuner (antenna, cable, satellite): a tuner input from the system itself, not a
     * channel app (Pluto TV and the like also call themselves tuners).
     */
    static boolean builtInTuner(TvInputInfo info) {
        if (info.isPassthroughInput() || info.getType() != TvInputInfo.TYPE_TUNER) return false;
        android.content.pm.ServiceInfo si = info.getServiceInfo();
        return si != null && si.applicationInfo != null && (si.applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0;
    }

    /**
     * A tuner opens on its channels in the TV's own TV app; with none found yet, on its channel
     * search, so there's something to watch afterwards.
     */
    private Intent tunerIntent(TvInputInfo info) {
        android.net.Uri channels = TvContract.buildChannelsUriForInput(info.getId());
        Intent watch = new Intent(Intent.ACTION_VIEW, channels);
        Intent setup = null;
        try {
            setup = info.createSetupIntent();
        } catch (RuntimeException ignored) {
        }
        boolean none = false;
        try (android.database.Cursor c = act.getContentResolver().query(channels, new String[]{TvContract.Channels._ID}, null, null, null)) {
            none = c != null && c.getCount() == 0;
        } catch (RuntimeException ignored) {
            // channels aren't Aura's to read on this TV: straight to the TV app, which knows
        }
        if (setup != null && (none || !Compat.resolves(act, watch)) && Compat.resolves(act, setup)) return setup;
        return watch;
    }

    /** The port as the TV names it, "HDMI 2" spelt one way whatever the firmware's habit. */
    private String port(TvInputInfo info) {
        return String.valueOf(info.loadLabel(act)).replaceAll("(?i)^hdmi\\s*(\\d)", "HDMI $1");
    }

    /** A name you gave the input wins; otherwise the device's own name over HDMI-CEC; otherwise the port's. */
    private String label(TvInputInfo info, String port) {
        CharSequence custom = android.os.Build.VERSION.SDK_INT >= 24 ? info.loadCustomLabel(act) : null;
        if (custom != null && custom.length() > 0) return String.valueOf(custom);
        return Cec.name(act, port);
    }

    private void showInputs() {
        if (!isOpen || sources.isEmpty()) return;
        onlySheet(inputSheet);
        boardAway(inputSheet, inputs);
        inputSheet.open(sources);
    }

    private void hideInputs() {
        inputSheet.close();
        if (sheetOpen()) return;
        boardBack();
        inputs.requestFocus();
    }

    private boolean sheetOpen() {
        return wifiSheet.isOpen() || btSheet.isOpen() || inputSheet.isOpen();
    }

    /**
     * One sheet at a time, the one asked for last: any other closes, and one still on its way (the
     * Inputs or Bluetooth sheet just after opening) no longer comes. Two in the same place would have
     * Back bring the board up under the other, with the focus on it.
     */
    private void onlySheet(View keep) {
        panel.removeCallbacks(delayedBt);
        panel.removeCallbacks(delayedInputs);
        if (keep != wifiSheet && wifiSheet.isOpen()) wifiSheet.close();
        if (keep != btSheet && btSheet.isOpen()) btSheet.close();
        if (keep != inputSheet && inputSheet.isOpen()) inputSheet.close();
    }

    /**
     * Focus to whatever is showing: the open sheet, or the board's first button. Sending it to the
     * board while a sheet covered it (or while the board faded out under one) left the focus
     * somewhere no one could see.
     */
    private void focusBoard() {
        if (inputSheet.isOpen()) inputSheet.requestFocus();
        else if (btSheet.isOpen()) btSheet.requestFocus();
        else if (wifiSheet.isOpen()) wifiSheet.requestFocus();
        else wifi.requestFocus();
    }

    // ---- actions -----------------------------------------------------------------------------

    private void launch(Intent i, View from) {
        host.closeCc(false);
        host.open(i, from);
    }

    private void standby() {
        host.closeCc(false);
        if (!Power.screenOff(act)) host.hud(R.drawable.ic_info, act.getString(R.string.standby_needs_setup_settings_set));
    }

    void cycleSleep() {
        long left = Power.sleepLeftMs(act);
        // with no way to switch the TV off, a timer would only fade the sound out and leave it on
        if (left <= 0 && !Power.canSleep(act)) {
            host.hud(R.drawable.ic_info, act.getString(R.string.the_sleep_timer_needs_setup));
            return;
        }
        // first, when something with an end is playing: sleep when it ends
        if (left <= 0 && Power.setSleepAtEnd(act)) {
            refreshSleep();
            host.hud(R.drawable.ic_moon, act.getString(R.string.tv_sleeps_when_this_ends, duration((int) Math.ceil(Power.sleepLeftMs(act) / 60_000.0))));
            return;
        }
        // from "when this ends", on to the usual steps
        if (Power.atEnd(act)) left = 0;
        int cur = (int) Math.ceil(left / 60_000.0), next = 0;
        for (int step : SLEEP_STEPS) {
            if (step > cur) {
                next = step;
                break;
            }
        }
        if (cur >= SLEEP_STEPS[SLEEP_STEPS.length - 1]) next = 0;
        Power.setSleep(act, next);
        refreshSleep();
        host.hud(R.drawable.ic_moon, next == 0 ? act.getString(R.string.sleep_timer_off) : act.getString(R.string.tv_will_fade_out_and_sleep, duration(next)));
    }

    private static String duration(int min) {
        return Words.minutes(min);
    }

    // ---- widgets -----------------------------------------------------------------------------

    /** Round toggle: tinted when on, translucent when off; focus lifts it and draws a bright rim. */
    static final class Round extends View {
        Runnable onClick, onLongClick;
        private final Drawable glyph;
        private final int activeColor;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean on, pressed, longFired;
        private float focus;
        private final Pad.Lift lift = new Pad.Lift(this, new Pad.Lift.Host() {
            @Override
            public float lifted() {
                return Ui.FOCUS_SCALE_CONTROL;
            }

            @Override
            public void fill(float f) {
                focus = f;
                invalidate();
            }
        });

        Round(Context c, int iconRes, int activeColor) {
            super(c);
            this.activeColor = activeColor;
            glyph = c.getDrawable(iconRes).mutate();
            setFocusable(true);
            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(Ui.hair());
            rim.setColor(0xFFFFFFFF);
        }

        /** A toggle that's off: darker than the glass around it, so the lit ones stand out. */
        private static final int OFF = 0x38000000;

        void setOn(boolean v) {
            on = v;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float r = Math.min(getWidth(), getHeight()) / 2f - Ui.dpf(4), cx = getWidth() / 2f, cy = getHeight() / 2f;
            // on: a soft halo of its colour behind it (HIGH); off: smoked glass with a hairline
            if (on && !Ui.light && focus < 1f) {
                fill.setColor(Ui.withAlpha(activeColor, Math.round(70 * (1f - focus))));
                c.drawCircle(cx, cy, r + Ui.dpf(3), fill);
            }
            // focused, it lights white as every other control does; its glyph keeps its colour when on
            fill.setColor(Ui.mix(on ? activeColor : OFF, 0xF2FFFFFF, focus));
            c.drawCircle(cx, cy, r, fill);
            if (!on && focus < 1f) {
                rim.setAlpha(Math.round(46 * (1f - focus)));
                c.drawCircle(cx, cy, r - rim.getStrokeWidth() / 2f, rim);
            }
            glyph.setTint(Ui.mix(0xFFFFFFFF, on ? activeColor : Pad.DARK, focus));
            int s = Math.round(r * 0.9f);
            glyph.setBounds(Math.round(cx - s / 2f), Math.round(cy - s / 2f), Math.round(cx + s / 2f), Math.round(cy + s / 2f));
            glyph.draw(c);
        }

        @Override
        protected void onFocusChanged(boolean gained, int direction, Rect previous) {
            super.onFocusChanged(gained, direction, previous);
            if (!gained) pressed = false;
            lift.focus(gained);
        }

        /** Its fade goes straight onto its draws, as the board's own does. */
        @Override
        public boolean hasOverlappingRendering() {
            return false;
        }

        @Override
        protected void onDetachedFromWindow() {
            lift.stop();
            super.onDetachedFromWindow();
        }

        @Override
        public boolean onKeyDown(int code, KeyEvent e) {
            if (Pad.isConfirm(code)) {
                if (e.getRepeatCount() == 0) {
                    e.startTracking();
                    pressed = true;
                    // a hold whose long press moved the focus away never had its release here
                    longFired = false;
                    lift.press(true);
                }
                return true;
            }
            return super.onKeyDown(code, e);
        }

        @Override
        public boolean onKeyLongPress(int code, KeyEvent e) {
            if (Pad.isConfirm(code) && onLongClick != null) {
                longFired = true;
                lift.press(false);
                onLongClick.run();
                return true;
            }
            return super.onKeyLongPress(code, e);
        }

        @Override
        public boolean onKeyUp(int code, KeyEvent e) {
            if (Pad.isConfirm(code)) {
                // a release whose press began elsewhere presses nothing
                boolean click = pressed && !longFired && !e.isCanceled();
                if (pressed && !longFired) lift.press(false);
                pressed = false;
                longFired = false;
                if (click && onClick != null) onClick.run();
                return true;
            }
            return super.onKeyUp(code, e);
        }
    }

    /** Tall volume slider: fills from the bottom; up/down change the volume, OK mutes. */
    final class Slider extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG), hair = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clip = new Path();
        private final Drawable on, off;
        private float shown = -1f, focus;
        private int value;
        private boolean muted;
        private ValueAnimator anim;
        private final Pad.Lift lift = new Pad.Lift(this, new Pad.Lift.Host() {
            @Override
            public float lifted() {
                return Ui.FOCUS_SCALE_CONTROL;
            }

            @Override
            public void fill(float f) {
                focus = f;
                invalidate();
            }
        });

        Slider(Context c) {
            super(c);
            setFocusable(true);
            on = c.getDrawable(R.drawable.ic_volume).mutate();
            off = c.getDrawable(R.drawable.ic_volume_off).mutate();
            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(Ui.dpf(2f));
            rim.setColor(0xFFFFFFFF);
            hair.setStyle(Paint.Style.STROKE);
            hair.setStrokeWidth(Ui.hair());
            hair.setColor(0xFFFFFFFF);
            text.setTypeface(Ui.bold);
            text.setTextSize(12 * Ui.scaled);
            text.setTextAlign(Paint.Align.CENTER);
        }

        /** Its entrance fade applies straight to its draws: no offscreen layer. */
        @Override
        public boolean hasOverlappingRendering() {
            return false;
        }

        void sync() {
            int max = Math.max(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            value = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            // Android 5 can't say whether it's muted, only how loud: silent counts as muted there
            muted = android.os.Build.VERSION.SDK_INT >= 23 ? audio.isStreamMute(AudioManager.STREAM_MUSIC) : value == 0;
            setContentDescription(act.getString(R.string.volume) + " " + (muted ? 0 : value));
            float level = muted ? 0f : value / (float) max;
            if (shown < 0f) {
                shown = level;
                invalidate();
                return;
            }
            if (anim != null) anim.cancel();
            anim = ValueAnimator.ofFloat(shown, level);
            anim.setDuration(200);
            anim.setInterpolator(Ui.DECEL);
            anim.addUpdateListener(a -> {
                shown = (float) a.getAnimatedValue();
                invalidate();
            });
            anim.start();
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            clip.reset();
            clip.addRoundRect(new RectF(0, 0, w, h), Ui.dpf(26), Ui.dpf(26), Path.Direction.CW);
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            float fillTop = h * (1f - Math.max(0f, shown));
            c.save();
            c.clipPath(clip);
            p.setColor(Ui.mix(MODULE, 0x33FFFFFF, focus));
            c.drawRect(0, 0, w, h, p);
            p.setColor(0xF2FFFFFF);
            c.drawRect(0, fillTop, w, h, p);
            c.restore();
            Drawable d = muted || value == 0 ? off : on;
            int s = Ui.dp(24), cx = w / 2, iy = h - Ui.dp(22) - s / 2;
            d.setTint(fillTop < iy ? Pad.DARK : 0xFFFFFFFF);
            d.setBounds(cx - s / 2, iy, cx + s / 2, iy + s);
            d.draw(c);
            float ty = Ui.dpf(26);
            text.setColor(fillTop < ty - Ui.dpf(10) ? Pad.DARK : 0xCCFFFFFF);
            c.drawText(muted ? "–" : String.valueOf(value), cx, ty, text);
            // at rest, the same quiet hairline the tiles beside it have
            if (focus < 1f) {
                float i = hair.getStrokeWidth() / 2f;
                hair.setAlpha(Math.round(40 * (1f - focus)));
                c.drawRoundRect(i, i, w - i, h - i, Ui.dpf(26), Ui.dpf(26), hair);
            }
            if (focus > 0f) {
                rim.setAlpha(Math.round(230 * focus));
                float o = Ui.dpf(3.5f);
                c.drawRoundRect(-o, -o, w + o, h + o, Ui.dpf(26) + o, Ui.dpf(26) + o, rim);
            }
        }

        @Override
        protected void onFocusChanged(boolean gained, int direction, Rect previous) {
            super.onFocusChanged(gained, direction, previous);
            lift.focus(gained);
        }

        @Override
        protected void onDetachedFromWindow() {
            lift.stop();
            super.onDetachedFromWindow();
        }

        @Override
        public boolean onKeyDown(int code, KeyEvent e) {
            if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
                try {
                    audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                            code == KeyEvent.KEYCODE_DPAD_UP ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, 0);
                } catch (RuntimeException ex) {
                    // volume kept by the TV or a soundbar over HDMI: nothing to change from here
                    Trouble.caught("volume", ex);
                }
                sync();
                return true;
            }
            if (Pad.isConfirm(code)) {
                if (e.getRepeatCount() == 0) lift.press(true);
                return true;
            }
            return super.onKeyDown(code, e);
        }

        @Override
        public boolean onKeyUp(int code, KeyEvent e) {
            if (Pad.isConfirm(code)) {
                lift.press(false);
                if (!e.isCanceled()) {
                    try {
                        if (android.os.Build.VERSION.SDK_INT >= 23) {
                            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, 0);
                        } else {
                            //noinspection deprecation
                            audio.setStreamMute(AudioManager.STREAM_MUSIC, !muted);
                        }
                    } catch (RuntimeException ex) {
                        Trouble.caught("mute", ex);
                    }
                    sync();
                }
                return true;
            }
            return super.onKeyUp(code, e);
        }
    }

    /** Memory ring: how much is free, drawn in the module's current content colour. */
    static final class Ring extends View {
        private final Pad host;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private float free;

        Ring(Context c, Pad host) {
            super(c);
            this.host = host;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Ui.dpf(3.5f));
            p.setStrokeCap(Paint.Cap.ROUND);
        }

        void set(float f) {
            free = f;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float s = p.getStrokeWidth() / 2f;
            box.set(s, s, getWidth() - s, getHeight() - s);
            p.setColor(host.content(0.22f));
            c.drawArc(box, 0, 360, false, p);
            p.setColor(host.highlight > 0.5f ? host.content(1f) : GREEN);
            c.drawArc(box, -90, 360 * Math.max(0.02f, free), false, p);
        }
    }

    /** One dot a source, 4 dp apart, from the left: the live ones green (dark on a lit tile), the rest quiet. */
    static final class Dots extends View {
        private final Pad host;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean[] lit = new boolean[0];

        Dots(Context c, Pad host) {
            super(c);
            this.host = host;
        }

        void set(boolean[] lit) {
            this.lit = lit;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float r = Ui.dpf(2.25f), step = Ui.dpf(8.5f), cy = getHeight() / 2f;
            // never past the tile: with more sources than fit, the dots shrink their stride
            if (lit.length > 1) step = Math.min(step, (getWidth() - 2 * r) / (lit.length - 1));
            for (int i = 0; i < lit.length; i++) {
                p.setColor(lit[i] ? (host.highlight > 0.5f ? host.content(1f) : GREEN) : host.content(0.3f));
                c.drawCircle(r + i * step, cy, r, p);
            }
        }
    }
}
