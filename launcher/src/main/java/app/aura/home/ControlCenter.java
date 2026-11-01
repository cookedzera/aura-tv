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
import java.util.Locale;
import java.util.Set;

/**
 * Control Center as a modular board on frosted glass, iOS 18 style: round toggles, a tall volume
 * slider, an inputs switcher, the sky and your day, with the time set large beside it.
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
    }

    private static final int U = 84, G = 14;
    /** How long the frost takes to turn opaque; after that the home screen beneath stops drawing. */
    static final long FROST_MS = 240;
    private static final int BLUE = 0xFF2F7BFF, ORANGE = 0xFFFF9F0A, GREEN = 0xFF30D158, VIOLET = 0xFF8E6CFF;
    private static final int MODULE = 0x1FFFFFFF;
    private static final int[] SLEEP_STEPS = {0, 15, 30, 60, 90, 120};

    final FrameLayout panel;
    private final Activity act;
    private final Host host;
    private final GlassFrame veil;
    private final LinearLayout header;
    private final FrameLayout board;
    private final WifiSheet wifiSheet;
    private final BtSheet btSheet;
    private final List<View> modules = new ArrayList<>(), focusables = new ArrayList<>();
    private FrameLayout sky;
    private Pad music;
    private ImageView musicArt;
    private TextView musicState, musicTitle, musicBy;
    private android.graphics.Bitmap musicShown;

    /**
     * What's playing (null: nothing): the Sky gives its place to the song while there is one, and
     * takes it back when the music stops. Focus on the tile as it goes moves to Wi-Fi.
     */
    void music(MediaWatch.Playing p) {
        if (music == null) return;
        boolean on = p != null;
        if (on) {
            musicTitle.setText(p.title);
            musicBy.setText(p.by != null ? p.by : "");
            musicState.setText(p.playing ? "NOW PLAYING" : "PAUSED");
            if (p.art != null) {
                if (p.art != musicShown) {
                    musicShown = p.art;
                    musicArt.setImageBitmap(p.art);
                }
            } else if (musicShown == null) {
                try {
                    musicArt.setImageDrawable(act.getPackageManager().getApplicationIcon(p.pkg));
                } catch (Exception e) {
                    musicArt.setImageDrawable(null);
                }
            }
        } else {
            musicShown = null;
        }
        if (on == (music.getVisibility() == View.VISIBLE)) return;
        if (!on && music.hasFocus()) wifi.requestFocus();
        music.setVisibility(on ? View.VISIBLE : View.GONE);
        sky.setVisibility(on ? View.GONE : View.VISIBLE);
        if (on) focusables.add(music);
        else focusables.remove(music);
    }
    private final TextView time, date, info, phase, eventName, eventTime, other, todayValue, boostValue;
    private final TextView wifiCap, btCap, sleepCap, photoCap;
    private final Round wifi, bt, sleep, photo;
    private final Slider volume;
    private final LinearLayout segments;
    private final Pad picture, sound, boost, settings, standby;
    private final Ring ring;
    private final SkyArc arc;
    private final AudioManager audio;
    private String segmentsSig;
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
        veil = new GlassFrame(a, h.backdrop(), 0, 0.5f);
        veil.edgeLight = false;
        panel.addView(veil, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // the time, set large on the left
        header = new LinearLayout(a) {
            @Override
            public boolean hasOverlappingRendering() {
                return false;
            }
        };
        header.setOrientation(LinearLayout.VERTICAL);
        TextView title = Ui.caps(a, 8, 0x99FFFFFF, 0.3f);
        title.setText("CONTROL CENTER");
        header.addView(title);
        time = Ui.text(a, 62, Ui.heavy, 0xFFFFFFFF);
        time.setIncludeFontPadding(true);
        time.setLetterSpacing(-0.045f);
        header.addView(time, lp(0, 4));
        date = Ui.text(a, 16, Ui.medium, 0xE6FFFFFF);
        header.addView(date, lp(0, 0));
        info = Ui.text(a, 12, Ui.regular, 0xA6FFFFFF);
        info.setSingleLine(false);
        info.setMaxLines(3);
        info.setLineSpacing(Ui.dpf(3), 1f);
        info.setMaxWidth(Ui.dp(300));
        header.addView(info, lp(0, 14));
        header.setClipChildren(false);
        header.setClipToPadding(false);

        // Tonight's pick: from TVmaze, or TMDB with your own key, to your taste; OK plays its trailer
        pick = new Pad(a, null, 18, 0f);
        pick.plainColor = MODULE;
        pick.focusScale = 1.04f;
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
        pk.setText("TONIGHT'S PICK  ·  TVMAZE");
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
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(Ui.dp(300), ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.START | Gravity.CENTER_VERTICAL);
        hl.leftMargin = Ui.dp(112);
        panel.addView(header, hl);

        int side = 4 * U + 3 * G;
        board = new FrameLayout(a);
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
                launch(intent, wifi);
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
                launch(intent, bt);
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
        photo = new Round(a, R.drawable.ic_photo, VIOLET);
        Round[] rounds = {wifi, bt, sleep, photo};
        TextView[] caps = new TextView[4];
        for (int k = 0; k < 4; k++) {
            int x = (k % 2) * half, y = (k / 2) * half;
            // 52dp circles in 60dp views: the focus rim needs room inside the view's own bounds
            FrameLayout.LayoutParams rl = new FrameLayout.LayoutParams(Ui.dp(60), Ui.dp(60));
            rl.leftMargin = Ui.dp(x + (half - 60) / 2f);
            rl.topMargin = Ui.dp(y + 9);
            connect.addView(rounds[k], rl);
            caps[k] = Ui.text(a, 10, Ui.medium, 0xD9FFFFFF);
            caps[k].setGravity(Gravity.CENTER);
            caps[k].setEllipsize(android.text.TextUtils.TruncateAt.END);
            caps[k].setPadding(Ui.dp(4), 0, Ui.dp(4), 0);
            FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(Ui.dp(half), ViewGroup.LayoutParams.WRAP_CONTENT);
            cl.leftMargin = Ui.dp(x);
            cl.topMargin = Ui.dp(y + 75);
            connect.addView(caps[k], cl);
            focusables.add(rounds[k]);
        }
        wifiCap = caps[0];
        btCap = caps[1];
        sleepCap = caps[2];
        photoCap = caps[3];
        photoCap.setText("Photo mode");

        // 1x2: volume
        volume = place(new Slider(a), 2, 0, 1, 2);
        focusables.add(volume);

        // 1x2: the sky
        sky = place(module(a), 3, 0, 1, 2);
        LinearLayout sc = column(a, 13);
        phase = Ui.caps(a, 7, 0xFFFFC98A, 0.2f);
        sc.addView(phase);
        arc = new SkyArc(a);
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(40));
        al.topMargin = Ui.dp(12);
        sc.addView(arc, al);
        eventName = Ui.caps(a, 7, 0x99FFFFFF, 0.2f);
        sc.addView(eventName, lp(0, 14));
        eventTime = Ui.text(a, 17, Ui.heavy, 0xFFFFFFFF);
        eventTime.setLetterSpacing(-0.02f);
        sc.addView(eventTime, lp(0, 3));
        other = Ui.text(a, 9.5f, Ui.medium, 0x99FFFFFF);
        sc.addView(other, lp(0, 8));
        sky.addView(sc);

        // the same place, while music plays: the cover, the song, and OK for Now Playing
        music = place(new Pad(a, null, 26, 0f), 3, 0, 1, 2);
        music.plainColor = MODULE;
        music.focusScale = 1.05f;
        LinearLayout mc = new LinearLayout(a);
        mc.setOrientation(LinearLayout.VERTICAL);
        mc.setPadding(Ui.dp(10), Ui.dp(10), Ui.dp(10), Ui.dp(12));
        musicArt = new ImageView(a);
        musicArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        musicArt.setClipToOutline(true);
        musicArt.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View v, android.graphics.Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), Ui.dpf(14));
            }
        });
        mc.addView(musicArt, new LinearLayout.LayoutParams(Ui.dp(U - 20), Ui.dp(U - 20)));
        musicState = music.tint(Ui.caps(a, 6.5f, 0xFFFFFFFF, 0.2f), 0.6f);
        mc.addView(musicState, lp(0, 10));
        musicTitle = music.tint(Ui.text(a, 11.5f, Ui.semibold, 0xFFFFFFFF), 1f);
        musicTitle.setSingleLine(false);
        musicTitle.setMaxLines(2);
        musicTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mc.addView(musicTitle, lp(0, 3));
        musicBy = music.tint(Ui.text(a, 9.5f, Ui.medium, 0xFFFFFFFF), 0.7f);
        musicBy.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mc.addView(musicBy, lp(0, 2));
        music.addView(mc, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        music.onClick = () -> host.nowPlaying();
        music.setVisibility(View.GONE);

        // 2x1: inputs
        FrameLayout inputs = place(module(a), 0, 2, 2, 1);
        TextView il = Ui.caps(a, 7, 0x8CFFFFFF, 0.24f);
        il.setText("INPUTS");
        FrameLayout.LayoutParams ill = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ill.leftMargin = Ui.dp(14);
        ill.topMargin = Ui.dp(10);
        inputs.addView(il, ill);
        segments = new LinearLayout(a);
        segments.setClipChildren(false);
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(52));
        sl.leftMargin = Ui.dp(8);
        sl.rightMargin = Ui.dp(8);
        sl.topMargin = Ui.dp(25);
        inputs.addView(segments, sl);

        picture = place(button(a, R.drawable.ic_picture, "Picture"), 2, 2, 1, 1);
        sound = place(button(a, R.drawable.ic_volume, "Sound"), 3, 2, 1, 1);

        // 1x1: memory, with a ring; OK shows recent apps, where they're closed (a "boost" that kills
        // apps from a launcher is refused by Android 11 and later anyway)
        boost = place(new Pad(a, null, 26, 0f), 0, 3, 1, 1);
        boost.plainColor = MODULE;
        boost.focusScale = 1.06f;
        LinearLayout bc = new LinearLayout(a);
        bc.setOrientation(LinearLayout.VERTICAL);
        bc.setGravity(Gravity.CENTER);
        ring = boost.tint(new Ring(a, boost), 1f);
        bc.addView(ring, new LinearLayout.LayoutParams(Ui.dp(34), Ui.dp(34)));
        boostValue = boost.tint(Ui.text(a, 10, Ui.medium, 0xFFFFFFFF), 0.9f);
        bc.addView(boostValue, lp(0, 6));
        boost.addView(bc, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 1x1: your day
        FrameLayout today = place(module(a), 1, 3, 1, 1);
        LinearLayout tc = new LinearLayout(a);
        tc.setOrientation(LinearLayout.VERTICAL);
        tc.setGravity(Gravity.CENTER);
        todayValue = Ui.text(a, 19, Ui.heavy, 0xFFFFFFFF);
        todayValue.setLetterSpacing(-0.03f);
        tc.addView(todayValue, lp(0, 0));
        TextView tcap = Ui.text(a, 9, Ui.medium, 0x99FFFFFF);
        tcap.setText("in apps today");
        tc.addView(tcap, lp(0, 4));
        today.addView(tc, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        settings = place(button(a, R.drawable.ic_settings, "Settings"), 2, 3, 1, 1);
        standby = place(button(a, R.drawable.ic_power, "Standby"), 3, 3, 1, 1);
        focusables.addAll(Arrays.asList(picture, sound, boost, settings, standby, pick));

        wifi.onClick = this::showWifi;
        bt.onClick = this::showBt;
        sleep.onClick = this::cycleSleep;
        photo.onClick = () -> {
            host.closeCc(true);
            host.photoMode();
        };
        picture.onClick = () -> launch(new Intent("android.settings.DISPLAY_SETTINGS"), picture);
        sound.onClick = () -> {
            Intent s = new Intent(Settings.ACTION_SOUND_SETTINGS);
            launch(s.resolveActivity(a.getPackageManager()) != null ? s : new Intent(Settings.ACTION_SETTINGS), sound);
        };
        settings.onClick = () -> host.guarded(() -> launch(new Intent(Settings.ACTION_SETTINGS), settings));
        standby.onClick = this::standby;
        // what's holding memory is best seen, and closed, in recent apps
        boost.onClick = host::recents;
        panel.setVisibility(View.GONE);
    }

    // ---- layout helpers ----------------------------------------------------------------------

    private static int span(int n) {
        return n * U + (n - 1) * G;
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
        GlassFrame m = new GlassFrame(c, null, 26, 0f);
        m.plainColor = MODULE;
        return m;
    }

    private <T extends View> T place(T v, int col, int row, int w, int h) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(Ui.dp(span(w)), Ui.dp(span(h)));
        p.leftMargin = Ui.dp(col * (U + G));
        p.topMargin = Ui.dp(row * (U + G));
        board.addView(v, p);
        v.setTag(col + row);
        modules.add(v);
        return v;
    }

    /** A 1x1 button module: glyph over a small label, lighting up white when focused. */
    private static Pad button(Context c, int iconRes, String label) {
        Pad p = new Pad(c, null, 26, 0f);
        p.plainColor = MODULE;
        p.focusScale = 1.06f;
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER);
        l.addView(p.tint(icon(c, iconRes), 1f), new LinearLayout.LayoutParams(Ui.dp(23), Ui.dp(23)));
        TextView t = p.tint(Ui.text(c, 10, Ui.medium, 0xFFFFFFFF), 0.9f);
        t.setText(label);
        l.addView(t, lp(0, 8));
        p.addView(l, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return p;
    }

    static ImageView icon(Context c, int res) {
        ImageView iv = new ImageView(c);
        iv.setImageResource(res);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        return iv;
    }

    /** The tvOS style: the board hangs from the top-right corner, as on Apple TV, without the large clock beside it. */
    private boolean top;

    void top(boolean on) {
        top = on;
        header.setVisibility(on ? View.GONE : View.VISIBLE);
        for (View v : new View[]{board, wifiSheet, btSheet}) {
            FrameLayout.LayoutParams l = (FrameLayout.LayoutParams) v.getLayoutParams();
            l.gravity = Gravity.END | (on ? Gravity.TOP : Gravity.CENTER_VERTICAL);
            l.topMargin = on ? Ui.dp(84) : 0;
            l.rightMargin = Ui.dp(on ? 40 : 96);
            v.setLayoutParams(l);
        }
    }

    // ---- open / close / keys -----------------------------------------------------------------

    void open(boolean inputs) {
        // readings are kept warm while the home screen idles; opening only refreshes them once the
        // board has settled, so no system lookups land on the first frames of the animation
        if (segmentsSig == null) {
            refresh();
        } else {
            volume.sync(); // the remote's volume keys may have moved it while the board was closed
            panel.postDelayed(this::refresh, 700);
        }
        tick(Calendar.getInstance());
        showPick();
        panel.animate().cancel();
        panel.setAlpha(1f);
        panel.setVisibility(View.VISIBLE);
        veil.setAlpha(0f);
        veil.animate().alpha(1f).setStartDelay(0).setDuration(FROST_MS).setInterpolator(Ui.SOFT).start();
        header.setAlpha(0f);
        header.setTranslationX(-Ui.dp(24));
        header.animate().alpha(1f).translationX(0f).setStartDelay(80).setDuration(620).setInterpolator(Ui.EMPHASIZED).start();
        // modules rise into place from the top-left corner of the board. They move rather than
        // scale: scaling re-rasterises every glyph on every frame, which this GPU can't keep up with
        for (View m : modules) {
            int order = (Integer) m.getTag();
            m.setAlpha(0f);
            // from the top-right corner they drop down instead
            m.setTranslationY(Ui.dp(top ? -16 : 16));
            m.animate().alpha(1f).translationY(0f).setStartDelay(60 + order * 30L).setDuration(460)
                    .setInterpolator(Ui.EMPHASIZED).start();
        }
        if (inputs && segments.getChildCount() > 0) segments.getChildAt(0).requestFocus();
        else wifi.requestFocus();
    }

    void close(boolean animate, Runnable done) {
        if (wifiSheet.isOpen()) hideWifi(false);
        if (btSheet.isOpen()) hideBt(false);
        panel.animate().alpha(0f).setStartDelay(0).setDuration(animate ? 240 : 0).setInterpolator(Ui.SOFT)
                .withEndAction(() -> {
                    panel.setVisibility(View.GONE);
                    panel.setAlpha(1f);
                    if (done != null) done.run();
                }).start();
    }

    /** The Wi-Fi sheet slides over the board; the board stops taking focus until it closes. */
    private void showWifi() {
        board.animate().alpha(0f).setStartDelay(0).setDuration(200).setInterpolator(Ui.SOFT)
                .withEndAction(() -> board.setVisibility(View.INVISIBLE)).start();
        wifiSheet.open();
    }

    private void hideWifi(boolean focusToggle) {
        wifiSheet.close();
        board.animate().cancel();
        board.setVisibility(View.VISIBLE);
        board.setAlpha(1f);
        refresh();
        if (focusToggle) wifi.requestFocus();
    }

    /** Back steps out of the Wi-Fi sheet before it closes Control Center. Returns true if it was used. */
    private void showBt() {
        board.animate().alpha(0f).setStartDelay(0).setDuration(200).setInterpolator(Ui.SOFT)
                .withEndAction(() -> board.setVisibility(View.INVISIBLE)).start();
        btSheet.open();
    }

    private void hideBt(boolean focusToggle) {
        btSheet.close();
        board.animate().cancel();
        board.setVisibility(View.VISIBLE);
        board.setAlpha(1f);
        refresh();
        if (focusToggle) bt.requestFocus();
    }

    boolean back() {
        if (btSheet.isOpen()) {
            hideBt(true);
            return true;
        }
        if (!wifiSheet.isOpen()) return false;
        if (!wifiSheet.back()) hideWifi(true);
        return true;
    }

    /** Arrow keys move between modules by geometry; the volume slider keeps up/down for itself. */
    boolean handleKey(int code, View focused) {
        // inside the Wi-Fi sheet the list scrolls and focuses itself
        if (wifiSheet.isOpen() || btSheet.isOpen()) return false;
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
        for (int i = 0; i < segments.getChildCount(); i++) all.add(segments.getChildAt(i));
        if (focused == null || !all.contains(focused)) {
            wifi.requestFocus();
            return true;
        }
        View next = Nav.next(all, focused, dir);
        if (next != null) next.requestFocus();
        return true;
    }

    // ---- live state --------------------------------------------------------------------------

    void refresh() {
        buildSegments();
        volume.sync();
        boolean online = Compat.onWifi(Compat.svc(act, ConnectivityManager.class), true);
        wifi.setOn(online);
        wifiCap.setText(online ? "Wi-Fi" : "Offline");
        BluetoothAdapter ba = BluetoothAdapter.getDefaultAdapter();
        boolean btOn = ba != null && ba.isEnabled();
        bt.setOn(btOn);
        btCap.setText(btOn ? btSheet.connectedName() : "Bluetooth off");
        refreshSleep();
        refreshMemory();
        refreshDay();
    }

    void tick(Calendar now) {
        boolean h24 = DateFormat.is24HourFormat(act);
        String t = new SimpleDateFormat(h24 ? "H:mm" : "h:mm", Locale.getDefault()).format(now.getTime());
        time.setText(h24 ? t : t + (now.get(Calendar.AM_PM) == Calendar.AM ? " AM" : " PM"));
        date.setText(new SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now.getTime()));
        refreshSleep();
    }

    private void refreshSleep() {
        long left = Power.sleepLeftMs(act);
        sleep.setOn(left > 0);
        sleepCap.setText(left <= 0 ? "Sleep timer" : (Power.atEnd(act) ? "At the end · " : "Sleep in ") + duration((int) Math.ceil(left / 60_000.0)));
    }

    private void refreshMemory() {
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        Compat.svc(act, ActivityManager.class).getMemoryInfo(mi);
        ring.set(mi.totalMem > 0 ? mi.availMem / (float) mi.totalMem : 0f);
        boostValue.setText(mi.availMem / (1024 * 1024) + " MB free");
    }

    /** Sky and day, read off-thread: sun times, the light outside, time spent in apps. */
    private void refreshDay() {
        new Thread(() -> {
            long now = System.currentTimeMillis();
            Calendar c = Calendar.getInstance();
            c.set(Calendar.HOUR_OF_DAY, 0);
            c.set(Calendar.MINUTE, 0);
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            long rise = Sun.next(c.getTimeInMillis(), true), set = rise > 0 ? Sun.next(rise, false) : -1;
            boolean up = Sun.isUp(now);
            // by day this is the same sunset the home screen shows, to the minute
            long next = up && set > now ? set : Sun.next(now, !up), after = next > 0 ? Sun.next(next, up) : -1;
            long today = Habits.todayMs(act);
            java.text.DateFormat tf = DateFormat.getTimeFormat(act);
            final String ph = Sun.label(Sun.phase(now));
            final String evName = up ? "SUNSET" : "SUNRISE";
            final String evTime = next > 0 ? tf.format(new Date(next)).toUpperCase(Locale.getDefault()) : "–";
            final String oth = after > 0 ? (up ? "↑ " : "↓ ") + tf.format(new Date(after)).toUpperCase(Locale.getDefault()) : "";
            final String day = today >= 0 ? compact(today) : "–";
            int fresh = Gallery.walls(act).size();
            Weather.Now wx = Weather.now(act);
            String outside = wx == null ? "" : wx.temp + "° and " + wx.condition().toLowerCase(Locale.ROOT)
                    + (wx.city.isEmpty() ? "" : " in " + wx.city) + ". ";
            final String line = outside + "Photos follow the " + ph.toLowerCase(Locale.ROOT) + " outside."
                    + (fresh > 0 ? "\n" + fresh + (fresh == 1 ? " fresh photo" : " fresh photos") + " on the wall, none shown twice." : "")
                    + (today >= 0 ? "\n" + Habits.duration(today) + " in apps today." : "");
            preparePick();
            act.runOnUiThread(() -> {
                if (shownPick == null) showPick(); // first time: no need to wait for the next opening
                arc.set(rise, set, now);
                phase.setText(ph);
                eventName.setText(evName);
                // the sky module is one unit wide: times shrink to fit rather than run off its edge
                Ui.fit(eventTime, evTime, Ui.dp(span(1) - 26), 17, 11);
                Ui.fit(other, oth, Ui.dp(span(1) - 26), 9.5f, 7.5f);
                todayValue.setText(day);
                info.setText(line);
            });
        }, "cc-day").start();
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
        pickKicker.setText("TONIGHT'S PICK  ·  " + p.src.toUpperCase(Locale.ROOT));
        pickAbout.setText(p.about);
        pickAbout.setVisibility(p.about.isEmpty() ? View.GONE : View.VISIBLE);
        pick.setVisibility(View.VISIBLE);
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

    private void buildSegments() {
        TvInputManager tim = (TvInputManager) act.getSystemService(Context.TV_INPUT_SERVICE);
        if (tim == null) return;
        List<TvInputInfo> list = new ArrayList<>();
        for (TvInputInfo info : tim.getTvInputList()) {
            // hiding an input in the TV's settings arrived in Android 7
            if (info.isPassthroughInput() && (android.os.Build.VERSION.SDK_INT < 24 || !info.isHidden(act))) list.add(info);
        }
        java.util.Collections.sort(list, (x, y) -> {
            int tx = x.getType() == TvInputInfo.TYPE_HDMI ? 0 : 1, ty = y.getType() == TvInputInfo.TYPE_HDMI ? 0 : 1;
            return tx != ty ? tx - ty : label(x).compareTo(label(y));
        });
        // the buttons are only rebuilt when an input or its state actually changed
        StringBuilder sig = new StringBuilder();
        for (TvInputInfo info : list) sig.append(info.getId()).append('=').append(tim.getInputState(info.getId())).append('|');
        if (sig.toString().equals(segmentsSig)) return;
        segmentsSig = sig.toString();
        View focused = act.getCurrentFocus();
        int had = focused != null && focused.getParent() == segments ? segments.indexOfChild(focused) : -1;
        segments.removeAllViews();
        for (int i = 0; i < list.size() && i < 3; i++) {
            TvInputInfo info = list.get(i);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            if (i > 0) p.leftMargin = Ui.dp(6);
            segments.addView(segment(info, tim.getInputState(info.getId())), p);
        }
        if (had >= 0 && had < segments.getChildCount()) segments.getChildAt(had).requestFocus();
    }

    private String label(TvInputInfo info) {
        CharSequence custom = android.os.Build.VERSION.SDK_INT >= 24 ? info.loadCustomLabel(act) : null;
        if (custom != null && custom.length() > 0) return String.valueOf(custom);
        // a name you gave the input wins; otherwise the device's own name over HDMI-CEC
        return Cec.name(act, String.valueOf(info.loadLabel(act)).replaceAll("(?i)^hdmi\\s*(\\d)", "HDMI $1"));
    }

    /** One input in the switcher: port glyph, name, and a dot when something is plugged in. */
    private Pad segment(TvInputInfo info, int state) {
        Pad p = new Pad(act, null, 15, 0f);
        p.plainColor = 0x14FFFFFF;
        p.focusScale = 1.08f;
        LinearLayout l = new LinearLayout(act);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER);
        int glyph = info.getType() == TvInputInfo.TYPE_HDMI ? R.drawable.ic_hdmi : R.drawable.ic_av;
        l.addView(p.tint(icon(act, glyph), 1f), new LinearLayout.LayoutParams(Ui.dp(17), Ui.dp(17)));
        TextView t = p.tint(Ui.text(act, 9.5f, Ui.semibold, 0xFFFFFFFF), 0.95f);
        t.setText(label(info));
        l.addView(t, lp(0, 4));
        p.addView(l, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (state != TvInputManager.INPUT_STATE_DISCONNECTED) {
            View dot = new View(act);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(state == TvInputManager.INPUT_STATE_CONNECTED ? GREEN : ORANGE);
            dot.setBackground(g);
            FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(Ui.dp(5), Ui.dp(5), Gravity.TOP | Gravity.END);
            dl.topMargin = Ui.dp(7);
            dl.rightMargin = Ui.dp(7);
            p.addView(dot, dl);
        }
        p.onClick = () -> launch(new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(info.getId())), p);
        return p;
    }

    // ---- actions -----------------------------------------------------------------------------

    private void launch(Intent i, View from) {
        host.closeCc(false);
        host.open(i, from);
    }

    private void standby() {
        host.closeCc(false);
        if (!Power.screenOff(act)) host.hud(R.drawable.ic_info, "Standby needs setup · Settings, Set up on this TV");
    }

    private void cycleSleep() {
        long left = Power.sleepLeftMs(act);
        // with no way to switch the TV off, a timer would only fade the sound out and leave it on
        if (left <= 0 && !Power.canSleep(act)) {
            host.hud(R.drawable.ic_info, "The sleep timer needs setup · Settings, Set up on this TV");
            return;
        }
        // first, when something with an end is playing: sleep when it ends
        if (left <= 0 && Power.setSleepAtEnd(act)) {
            refreshSleep();
            host.hud(R.drawable.ic_moon, "TV sleeps when this ends · in " + duration((int) Math.ceil(Power.sleepLeftMs(act) / 60_000.0)));
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
        host.hud(R.drawable.ic_moon, next == 0 ? "Sleep timer off" : "TV will fade out and sleep in " + duration(next));
    }

    private static String duration(int min) {
        if (min < 60) return min + " min";
        return min % 60 == 0 ? (min / 60) + " h" : (min / 60) + " h " + (min % 60) + " min";
    }

    // ---- widgets -----------------------------------------------------------------------------

    /** Round toggle: tinted when on, translucent when off; focus lifts it and draws a bright rim. */
    static final class Round extends View {
        Runnable onClick;
        private final Drawable glyph;
        private final int activeColor;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean on;
        private float focus;
        private ValueAnimator anim;

        Round(Context c, int iconRes, int activeColor) {
            super(c);
            this.activeColor = activeColor;
            glyph = c.getDrawable(iconRes).mutate();
            setFocusable(true);
            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(Ui.dpf(2f));
            rim.setColor(0xFFFFFFFF);
        }

        void setOn(boolean v) {
            on = v;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float r = Math.min(getWidth(), getHeight()) / 2f - Ui.dpf(4), cx = getWidth() / 2f, cy = getHeight() / 2f;
            fill.setColor(on ? activeColor : Ui.mix(0x2EFFFFFF, 0xF2FFFFFF, focus));
            c.drawCircle(cx, cy, r, fill);
            glyph.setTint(on ? 0xFFFFFFFF : Ui.mix(0xFFFFFFFF, Pad.DARK, focus));
            int s = Math.round(r * 0.9f);
            glyph.setBounds(Math.round(cx - s / 2f), Math.round(cy - s / 2f), Math.round(cx + s / 2f), Math.round(cy + s / 2f));
            glyph.draw(c);
            if (on && focus > 0f) {
                rim.setAlpha(Math.round(240 * focus));
                c.drawCircle(cx, cy, r + Ui.dpf(3.5f), rim);
            }
        }

        @Override
        protected void onFocusChanged(boolean gained, int direction, Rect previous) {
            super.onFocusChanged(gained, direction, previous);
            if (anim != null) anim.cancel();
            anim = ValueAnimator.ofFloat(focus, gained ? 1f : 0f);
            anim.setDuration(gained ? 260 : 180);
            anim.addUpdateListener(a -> {
                focus = (float) a.getAnimatedValue();
                invalidate();
            });
            anim.start();
            animate().scaleX(gained ? 1.12f : 1f).scaleY(gained ? 1.12f : 1f).setInterpolator(gained ? Ui.SPRING : Ui.DECEL)
                    .setDuration(gained ? 440 : 220).setStartDelay(0).start();
        }

        @Override
        public boolean onKeyDown(int code, KeyEvent e) {
            return Pad.isConfirm(code) || super.onKeyDown(code, e);
        }

        @Override
        public boolean onKeyUp(int code, KeyEvent e) {
            if (Pad.isConfirm(code)) {
                if (!e.isCanceled() && onClick != null) onClick.run();
                return true;
            }
            return super.onKeyUp(code, e);
        }
    }

    /** Tall volume slider: fills from the bottom; up/down change the volume, OK mutes. */
    final class Slider extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clip = new Path();
        private final Drawable on, off;
        private float shown = -1f, focus;
        private int value;
        private boolean muted;
        private ValueAnimator anim, focusAnim;

        Slider(Context c) {
            super(c);
            setFocusable(true);
            on = c.getDrawable(R.drawable.ic_volume).mutate();
            off = c.getDrawable(R.drawable.ic_volume_off).mutate();
            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(Ui.dpf(2f));
            rim.setColor(0xFFFFFFFF);
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
            if (focus > 0f) {
                rim.setAlpha(Math.round(230 * focus));
                float o = Ui.dpf(3.5f);
                c.drawRoundRect(-o, -o, w + o, h + o, Ui.dpf(26) + o, Ui.dpf(26) + o, rim);
            }
        }

        @Override
        protected void onFocusChanged(boolean gained, int direction, Rect previous) {
            super.onFocusChanged(gained, direction, previous);
            if (focusAnim != null) focusAnim.cancel();
            focusAnim = ValueAnimator.ofFloat(focus, gained ? 1f : 0f);
            focusAnim.setDuration(240);
            focusAnim.addUpdateListener(a -> {
                focus = (float) a.getAnimatedValue();
                invalidate();
            });
            focusAnim.start();
            animate().scaleX(gained ? 1.04f : 1f).scaleY(gained ? 1.04f : 1f).setInterpolator(gained ? Ui.SPRING : Ui.DECEL)
                    .setDuration(gained ? 440 : 220).setStartDelay(0).start();
        }

        @Override
        public boolean onKeyDown(int code, KeyEvent e) {
            if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                        code == KeyEvent.KEYCODE_DPAD_UP ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, 0);
                sync();
                return true;
            }
            return Pad.isConfirm(code) || super.onKeyDown(code, e);
        }

        @Override
        public boolean onKeyUp(int code, KeyEvent e) {
            if (Pad.isConfirm(code)) {
                if (!e.isCanceled()) {
                    if (android.os.Build.VERSION.SDK_INT >= 23) {
                        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, 0);
                    } else {
                        //noinspection deprecation
                        audio.setStreamMute(AudioManager.STREAM_MUSIC, !muted);
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
}
