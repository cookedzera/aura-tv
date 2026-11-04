package app.aura.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Music scenes: while a song plays and home rests, one of Apple's aerial videos fills the screen
 * and a quiet player sits low on the left. The video streams from Apple's server and is never
 * saved; the TV's video chip plays it on a layer of its own, so the rest of Aura barely works.
 *
 * Resting, there is only the song: its cover, its name, a hairline of progress, coloured by the
 * cover, and after half a minute even that fades to a whisper. Any key brings it back with the
 * controls (previous, play, next, the sleep timer), what plays next, and the kinds of scene along
 * the top; Back puts them away, and Back again leaves for home. Each new song brings a new scene.
 */
final class MusicScene extends FrameLayout {
    private static final long WHISPER_MS = 30_000, CONTROLS_MS = 7_000, KIND_SETTLE_MS = 1_200;
    private static final int[] SLEEP_STEPS = {0, 15, 30, 60};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random rnd = new Random();
    private final Runnable gone;

    // the scene: the aerial video, the song's colour, the glow
    private final AerialView aerial;
    private final View wash;
    private BeatGlow glow;

    // the player
    private final View shade;
    private final LinearLayout player, chips;
    private final ImageView cover;
    private final TextView kicker, title, by, time, clock, date, credit, nextHead, nextTitle;
    private final LinearLayout nextBox, buttons, words;
    private final Line bar;
    private final Button prev, play, next, sleep;
    private final TextView[] kinds = new TextView[Aerials.KINDS.length];

    private MediaController controller;
    private MediaMetadata md;
    private PlaybackState st;
    private String shownTitle, fetching;
    private Bitmap shownArt;
    private int tone = 0xFFFFE2B8;

    private boolean controls, onChips;
    private int button = 1, kindAt;

    MusicScene(Context c, Runnable gone) {
        super(c);
        this.gone = gone;
        setClipChildren(false);
        aerial = new AerialView(c, true, new AerialView.Listener() {
            @Override
            public void shown(Aerials.Video v) {
                credit.setText(getContext().getString(R.string.aerial_credit, v.label));
                // over a video the beat brightens the picture; a light would only lie on it as a haze
                if (glow != null) glow.light = false;
            }

            @Override
            public void lost() {
                credit.setText("");
                if (glow != null) glow.light = true;
            }
        });
        addView(aerial, match());
        wash = new View(c);
        addView(wash, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(260), Gravity.BOTTOM));
        shade = new View(c);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xC0080C10, 0x66080C10, 0x00080C10}));
        shade.setAlpha(0f);
        addView(shade, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(330), Gravity.BOTTOM));

        // top: the kinds of scene (with the controls), the time
        chips = new LinearLayout(c);
        chips.setGravity(Gravity.CENTER_VERTICAL);
        chips.setAlpha(0f);
        // says where the kinds are: up from the controls
        TextView scenesHint = Ui.caps(c, 6.5f, 0x99FFFFFF, 0.3f);
        scenesHint.setText("▲  " + c.getString(R.string.scenes_up));
        chips.addView(scenesHint, wrap(0, 0));
        for (int i = 0; i < kinds.length; i++) {
            TextView k = Ui.text(c, 11, Ui.medium, 0xCCFFFFFF);
            k.setText(c.getString(Aerials.KIND_NAMES[i]));
            k.setPadding(Ui.dp(12), Ui.dp(5), Ui.dp(12), Ui.dp(5));
            kinds[i] = k;
            chips.addView(k, wrap(i == 0 ? 12 : 6, 0));
        }
        addView(chips, new LayoutParams(WRAP, WRAP, Gravity.TOP | Gravity.START) {{
            leftMargin = Ui.dp(48);
            topMargin = Ui.dp(32);
        }});
        LinearLayout when = new LinearLayout(c);
        when.setOrientation(LinearLayout.VERTICAL);
        when.setGravity(Gravity.END);
        clock = Ui.text(c, 30, Ui.light, 0xF2FFFFFF);
        when.addView(clock);
        date = Ui.text(c, 11, Ui.regular, 0xB3FFFFFF);
        when.addView(date);
        addView(when, new LayoutParams(WRAP, WRAP, Gravity.TOP | Gravity.END) {{
            rightMargin = Ui.dp(48);
            topMargin = Ui.dp(26);
        }});

        // bottom left: the player
        player = new LinearLayout(c);
        player.setGravity(Gravity.CENTER_VERTICAL);
        cover = new ImageView(c);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setClipToOutline(true);
        cover.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), Ui.dpf(10));
            }
        });
        cover.setPivotX(0f);
        cover.setPivotY(Ui.dpf(64));
        player.addView(cover, new LinearLayout.LayoutParams(Ui.dp(64), Ui.dp(64)));
        LinearLayout col = new LinearLayout(c);
        words = col;
        col.setOrientation(LinearLayout.VERTICAL);
        kicker = Ui.caps(c, 6.5f, 0xCCFFFFFF, 0.3f);
        col.addView(kicker);
        title = Ui.text(c, 18, Ui.semibold, 0xF7FFFFFF);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setMaxWidth(Ui.dp(420));
        col.addView(title, wrap(0, 3));
        by = Ui.text(c, 12.5f, Ui.regular, 0xC0FFFFFF);
        by.setSingleLine(true);
        by.setEllipsize(TextUtils.TruncateAt.END);
        by.setMaxWidth(Ui.dp(420));
        col.addView(by, wrap(0, 1));
        LinearLayout line = new LinearLayout(c);
        line.setGravity(Gravity.CENTER_VERTICAL);
        bar = new Line(c);
        line.addView(bar, new LinearLayout.LayoutParams(Ui.dp(230), Ui.dp(3)));
        time = Ui.text(c, 9.5f, Ui.medium, 0x99FFFFFF);
        line.addView(time, wrap(10, 0));
        col.addView(line, wrap(0, 9));
        buttons = new LinearLayout(c);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        buttons.setAlpha(0f);
        buttons.setVisibility(GONE);
        prev = new Button(c, R.drawable.ic_prev, 34);
        play = new Button(c, R.drawable.ic_pause, 42);
        next = new Button(c, R.drawable.ic_next, 34);
        sleep = new Button(c, R.drawable.ic_moon, 34);
        buttons.addView(prev, wrap(0, 0));
        buttons.addView(play, wrap(12, 0));
        buttons.addView(next, wrap(12, 0));
        buttons.addView(sleep, wrap(22, 0));
        col.addView(buttons, wrap(0, 12));
        player.addView(col, new LinearLayout.LayoutParams(WRAP, WRAP) {{
            leftMargin = Ui.dp(18);
        }});
        addView(player, new LayoutParams(WRAP, WRAP, Gravity.BOTTOM | Gravity.START) {{
            leftMargin = Ui.dp(48);
            bottomMargin = Ui.dp(40);
        }});

        // bottom right: where the video was filmed, or what plays next
        credit = Ui.text(c, 10, Ui.regular, 0x99FFFFFF);
        addView(credit, new LayoutParams(WRAP, WRAP, Gravity.BOTTOM | Gravity.END) {{
            rightMargin = Ui.dp(48);
            bottomMargin = Ui.dp(42);
        }});
        nextBox = new LinearLayout(c);
        nextBox.setOrientation(LinearLayout.VERTICAL);
        nextBox.setGravity(Gravity.END);
        nextBox.setAlpha(0f);
        nextHead = Ui.caps(c, 6.5f, 0x99FFFFFF, 0.3f);
        nextHead.setText(c.getString(R.string.up_next));
        nextBox.addView(nextHead);
        nextTitle = Ui.text(c, 12.5f, Ui.medium, 0xE6FFFFFF);
        nextTitle.setSingleLine(true);
        nextTitle.setEllipsize(TextUtils.TruncateAt.END);
        nextTitle.setMaxWidth(Ui.dp(260));
        nextBox.addView(nextTitle, wrap(0, 3));
        addView(nextBox, new LayoutParams(WRAP, WRAP, Gravity.BOTTOM | Gravity.END) {{
            rightMargin = Ui.dp(48);
            bottomMargin = Ui.dp(44);
        }});
        for (View v : new View[]{player, chips, clock, date, credit}) v.setAlpha(0f);
        kindAt = java.util.Arrays.asList(Aerials.KINDS).indexOf(Aerials.kind(c));
    }

    // ---- start and stop ------------------------------------------------------------------------

    /** Follows {@code c}'s song; the first scene loads out of sight and takes the photograph's place when ready. */
    void start(MediaController c) {
        controller = c;
        c.registerCallback(watch, handler);
        md = c.getMetadata();
        st = c.getPlaybackState();
        queue(c.getQueue());
        show();
        tickClock();
        // a song can run longer than the TV's wait for its screensaver: while a scene shows, it stays away
        setKeepScreenOn(true);
        for (View v : new View[]{player, clock, date, credit}) {
            v.animate().alpha(1f).setDuration(1200).setStartDelay(500).setInterpolator(Ui.SOFT).start();
        }
        if (Prefs.on(getContext(), Prefs.BEAT) && !Perf.light(getContext()) && BeatGlow.allowed(getContext())) {
            glow = new BeatGlow(getContext());
            glow.setColor(tone);
            glow.pulse = aerial::pulse;
            addView(glow, indexOfChild(wash) + 1, match());
            if (!glow.start()) {
                removeView(glow);
                glow = null;
            }
        }
        // after dark the scene stays a little dimmed, and with the beat it rests dimmed enough to brighten
        aerial.setRest((night() ? 0.26f : 0f) + (glow != null ? 0.16f : 0f));
        aerial.next(Aerials.KINDS[Math.max(0, kindAt)]);
        handler.removeCallbacks(tick);
        handler.post(tick);
        handler.postDelayed(whisper, WHISPER_MS);
    }

    /** Home is back (or the music stopped): everything is let go, the photograph shows again. */
    void stop() {
        if (stopping) return;
        stopping = true;
        handler.removeCallbacksAndMessages(null);
        setKeepScreenOn(false);
        if (controller != null) {
            try {
                controller.unregisterCallback(watch);
            } catch (RuntimeException ignored) {
            }
        }
        controller = null;
        if (glow != null) glow.stop();
        // the scene dims away in a quarter of a second while home fades in beneath, rather than
        // vanishing at once with the video
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            if (v != aerial) v.animate().alpha(0f).setDuration(240).setStartDelay(0).setInterpolator(Ui.SOFT).start();
        }
        aerial.dimOut(240);
        handler.postDelayed(() -> {
            aerial.release();
            if (gone != null) gone.run();
        }, 240);
    }

    private boolean stopping;

    private static boolean night() {
        return Sun.phase(System.currentTimeMillis()) == Sun.Phase.NIGHT;
    }

    // ---- the song ------------------------------------------------------------------------------

    private final MediaController.Callback watch = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            st = state;
            show();
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            md = metadata;
            show();
        }

        @Override
        public void onQueueChanged(List<MediaSession.QueueItem> q) {
            queue(q);
        }

        @Override
        public void onSessionDestroyed() {
            stop();
        }
    };

    private void show() {
        if (controller == null || md == null) return;
        String t = md.getString(MediaMetadata.METADATA_KEY_TITLE);
        if (t == null) t = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        String artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
        if (artist == null) artist = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        if (artist == null) artist = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
        String album = md.getString(MediaMetadata.METADATA_KEY_ALBUM);
        boolean newSong = shownTitle != null && t != null && !t.equals(shownTitle);
        if (newSong) {
            // a new song: its words and cover slide in softly, and a new scene drifts in behind
            for (View v : new View[]{title, by, cover}) {
                v.setAlpha(0f);
                v.setTranslationX(Ui.dpf(10));
                v.animate().alpha(1f).translationX(0f).setDuration(900).setStartDelay(150).setInterpolator(Ui.EMPHASIZED).start();
            }
            shownArt = null;
            aerial.next(Aerials.KINDS[Math.max(0, kindAt)]);
        }
        shownTitle = t;
        title.setText(t != null ? Ui.songTitle(t) : "");
        String line = artist != null ? artist : "";
        if (controls && album != null && !album.isEmpty() && !album.equals(t)) line = line.isEmpty() ? album : line + "  ·  " + album;
        by.setText(line);
        by.setVisibility(line.isEmpty() ? GONE : VISIBLE);
        String app = "";
        try {
            app = String.valueOf(getContext().getPackageManager().getApplicationLabel(
                    getContext().getPackageManager().getApplicationInfo(controller.getPackageName(), 0)));
        } catch (Exception ignored) {
        }
        kicker.setText((getContext().getString(R.string.now_playing) + (app.isEmpty() ? "" : "  ·  " + app)).toUpperCase(Locale.getDefault()));
        boolean on = st != null && st.getState() == PlaybackState.STATE_PLAYING;
        play.setIcon(on ? R.drawable.ic_pause : R.drawable.ic_play);
        art();
        progress();
    }

    /** The cover, and the colour the whole scene takes from it. */
    private void art() {
        Bitmap b = MediaWatch.extractBitmap(md);
        String uri = MediaWatch.extractArtUri(md);
        Bitmap cached = MediaWatch.cachedArt(uri);
        if (cached != null) b = cached;
        if (b != null) {
            if (b != shownArt) {
                shownArt = b;
                cover.setImageBitmap(b);
                tint(Ui.tone(b, 0xFFFFE2B8));
            }
        } else if (shownArt == null) {
            try {
                cover.setImageDrawable(getContext().getPackageManager().getApplicationIcon(controller.getPackageName()));
            } catch (Exception ignored) {
            }
        }
        if (b == null && uri != null && !uri.equals(fetching)) {
            fetching = uri;
            final String want = uri;
            final Context app = getContext().getApplicationContext();
            new SafeThread(() -> {
                Bitmap loaded = Continue.load(app, want);
                if (loaded == null) return;
                MediaWatch.cacheArt(want, loaded);
                handler.post(() -> {
                    if (controller != null && want.equals(MediaWatch.extractArtUri(md))) {
                        shownArt = loaded;
                        cover.setImageBitmap(loaded);
                        tint(Ui.tone(loaded, 0xFFFFE2B8));
                    }
                });
            }, "scene-art").start();
        }
    }

    private void tint(int c) {
        tone = c;
        int deep = Ui.mix(c, 0xFF000000, 0.55f), soft = Ui.mix(c, 0xFFFFFFFF, 0.45f);
        wash.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{Ui.withAlpha(deep, night() ? 0x66 : 0x8C), Ui.withAlpha(deep, 0x26), Ui.withAlpha(deep, 0)}));
        bar.color = soft;
        bar.invalidate();
        kicker.setTextColor(Ui.withAlpha(soft, 0xE6));
        if (glow != null) glow.setColor(c);
    }

    private void queue(List<MediaSession.QueueItem> q) {
        CharSequence what = null;
        if (q != null && st != null) {
            long now = st.getActiveQueueItemId();
            for (int i = 0; i + 1 < q.size(); i++) {
                if (q.get(i).getQueueId() == now) {
                    what = q.get(i + 1).getDescription().getTitle();
                    break;
                }
            }
        }
        nextTitle.setText(what != null ? what : "");
        if (controls) nextBox.animate().alpha(what != null ? 1f : 0f).setDuration(300).start();
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            progress();
            tickClock();
            handler.postDelayed(this, 1000);
        }
    };

    /** Where the song is, worked out from what the app last said: nothing is asked of it each second. */
    private void progress() {
        long dur = md != null ? md.getLong(MediaMetadata.METADATA_KEY_DURATION) : 0;
        boolean on = st != null && st.getState() == PlaybackState.STATE_PLAYING;
        if (st == null || dur <= 0) {
            bar.setVisibility(INVISIBLE);
            time.setText(st != null && !on ? getContext().getString(R.string.paused_2) : "");
            return;
        }
        long pos = st.getPosition();
        if (on) pos += (long) ((SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime()) * st.getPlaybackSpeed());
        pos = Math.max(0, Math.min(dur, pos));
        bar.setVisibility(VISIBLE);
        bar.set(pos / (float) dur);
        String clockText = NowPlaying.clock(pos) + " / " + NowPlaying.clock(dur);
        time.setText(on ? clockText : getContext().getString(R.string.paused_value, clockText));
    }

    private int shownMinute = -1;

    private void tickClock() {
        Calendar now = Calendar.getInstance();
        int m = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        if (m == shownMinute) return;
        shownMinute = m;
        boolean h24 = DateFormat.is24HourFormat(getContext());
        clock.setText(new java.text.SimpleDateFormat(h24 ? "H:mm" : "h:mm", Locale.getDefault()).format(now.getTime()));
        date.setText(Words.date("EEEE, d MMMM", now.getTimeInMillis()));
        sleep.label(sleepLabel());
    }

    // ---- quiet and the controls ----------------------------------------------------------------

    /** Left alone, the player and the clock fade to a whisper: the scene has the screen. */
    private final Runnable whisper = this::whisper;

    private void whisper() {
        for (View v : new View[]{player, clock, date, credit}) v.animate().alpha(0.32f).setDuration(2400).setStartDelay(0).setInterpolator(Ui.SOFT).start();
    }

    private final Runnable hideControls = () -> showControls(false);

    private void showControls(boolean on) {
        controls = on;
        handler.removeCallbacks(whisper);
        handler.removeCallbacks(hideControls);
        for (View v : new View[]{player, clock, date}) v.animate().alpha(1f).setDuration(300).setStartDelay(0).start();
        shade.animate().alpha(on ? 1f : 0f).setDuration(on ? 380 : 600).setStartDelay(0).start();
        chips.animate().alpha(on ? 1f : 0f).setDuration(on ? 380 : 500).setStartDelay(0).start();
        credit.animate().alpha(on ? 0f : 1f).setDuration(300).setStartDelay(on ? 0 : 200).start();
        nextBox.animate().alpha(on && nextTitle.length() > 0 ? 1f : 0f).setDuration(380).setStartDelay(0).start();
        cover.animate().scaleX(on ? 1.34f : 1f).scaleY(on ? 1.34f : 1f).setDuration(520).setStartDelay(0).setInterpolator(Ui.EMPHASIZED).start();
        // the cover grows from its left edge: the words step aside by as much, rather than lie under it
        words.animate().translationX(on ? Ui.dpf(64 * 0.34f) : 0f).setDuration(520).setStartDelay(0).setInterpolator(Ui.EMPHASIZED).start();
        if (on) {
            buttons.setVisibility(VISIBLE);
            buttons.animate().alpha(1f).setDuration(380).setStartDelay(80).withEndAction(null).start();
            handler.postDelayed(hideControls, CONTROLS_MS);
        } else {
            onChips = false;
            buttons.animate().alpha(0f).setDuration(300).setStartDelay(0).withEndAction(() -> {
                if (!controls) buttons.setVisibility(GONE);
            }).start();
            handler.postDelayed(whisper, WHISPER_MS);
        }
        // the album joins the artist with the controls
        show();
        mark();
    }

    /**
     * A press of the remote. Media keys work at once; the first other key brings the controls;
     * with them up, left and right move along a row, up and down change rows, OK chooses. Back
     * puts the controls away; with them away it returns false, and home comes back.
     */
    boolean key(int code) {
        MediaController.TransportControls t = controller != null ? controller.getTransportControls() : null;
        switch (code) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                toggle();
                showControls(true);
                return true;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                if (t != null) t.skipToNext();
                showControls(true);
                return true;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                if (t != null) t.skipToPrevious();
                showControls(true);
                return true;
            default:
                break;
        }
        if (code == KeyEvent.KEYCODE_BACK) {
            if (!controls) return false;
            showControls(false);
            return true;
        }
        if (!controls) {
            showControls(true);
            return true;
        }
        handler.removeCallbacks(hideControls);
        handler.postDelayed(hideControls, CONTROLS_MS);
        if (code == KeyEvent.KEYCODE_DPAD_UP) onChips = true;
        else if (code == KeyEvent.KEYCODE_DPAD_DOWN) onChips = false;
        else if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            int d = code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1;
            if (onChips) {
                kindAt = Math.max(0, Math.min(kinds.length - 1, kindAt + d));
                handler.removeCallbacks(settleKind);
                handler.postDelayed(settleKind, KIND_SETTLE_MS);
            } else {
                button = Math.max(0, Math.min(sleep.getVisibility() == VISIBLE ? 3 : 2, button + d));
            }
        } else if (Pad.isConfirm(code)) {
            if (onChips) {
                handler.removeCallbacks(settleKind);
                settleKind.run();
            } else {
                press();
            }
        }
        mark();
        return true;
    }

    /** A kind chosen with the remote: kept, and its first scene drifts in once the choice settles. */
    private final Runnable settleKind = this::settleKind;

    private void settleKind() {
        String k = Aerials.KINDS[kindAt];
        if (k.equals(Aerials.kind(getContext()))) return;
        Prefs.of(getContext()).edit().putString(Prefs.SCENE_KIND, k).apply();
        aerial.next(k);
    }

    private void press() {
        MediaController.TransportControls t = controller != null ? controller.getTransportControls() : null;
        if (button == 0 && t != null) t.skipToPrevious();
        else if (button == 1) toggle();
        else if (button == 2 && t != null) t.skipToNext();
        else if (button == 3) {
            // through 15, 30 and 60 minutes, then off; the sound fades in the last minute as always
            long left = Power.sleepLeftMs(getContext());
            int nowMin = left <= 0 ? 0 : (int) Math.ceil(left / 60_000.0);
            int at = 0;
            for (int i = 0; i < SLEEP_STEPS.length; i++) if (SLEEP_STEPS[i] >= nowMin) {
                at = i;
                break;
            }
            int nextMin = SLEEP_STEPS[(at + 1) % SLEEP_STEPS.length];
            Power.setSleep(getContext(), nextMin);
            sleep.label(sleepLabel());
        }
    }

    private void toggle() {
        if (controller == null) return;
        boolean on = st != null && st.getState() == PlaybackState.STATE_PLAYING;
        if (on) controller.getTransportControls().pause();
        else controller.getTransportControls().play();
    }

    private String sleepLabel() {
        long left = Power.sleepLeftMs(getContext());
        return left <= 0 ? getContext().getString(R.string.sleep_timer)
                : getContext().getString(R.string.sleep_in_value, Words.minutes((left + 59_999) / 60_000));
    }

    /** Where the remote is: the chosen kind, the button in reach. */
    private void mark() {
        for (int i = 0; i < kinds.length; i++) {
            boolean chosen = i == kindAt, here = chosen && onChips && controls;
            kinds[i].setTextColor(chosen ? (here ? 0xFF0B0E16 : 0xFFFFFFFF) : 0xB3FFFFFF);
            GradientDrawable pill = new GradientDrawable();
            pill.setCornerRadius(Ui.dpf(14));
            pill.setColor(here ? 0xF2FFFFFF : chosen ? 0x33FFFFFF : 0x00000000);
            kinds[i].setBackground(pill);
        }
        Button[] all = {prev, play, next, sleep};
        for (int i = 0; i < all.length; i++) all[i].setLit(controls && !onChips && i == button);
        sleep.setVisibility(Power.canSleep(getContext()) ? VISIBLE : GONE);
    }

    // ---- small parts ---------------------------------------------------------------------------

    /** A round button of the player: lit white when the remote is on it. */
    private static final class Button extends LinearLayout {
        private final ImageView icon;
        private final TextView text;
        private final int size;

        Button(Context c, int iconRes, int sizeDp) {
            super(c);
            size = sizeDp;
            setGravity(Gravity.CENTER_VERTICAL);
            icon = new ImageView(c);
            icon.setScaleType(ImageView.ScaleType.CENTER);
            addView(icon, new LayoutParams(Ui.dp(sizeDp), Ui.dp(sizeDp)));
            text = Ui.text(c, 11, Ui.medium, 0xCCFFFFFF);
            text.setVisibility(GONE);
            addView(text, new LayoutParams(WRAP, WRAP) {{
                leftMargin = Ui.dp(8);
            }});
            setIcon(iconRes);
            setLit(false);
        }

        void setIcon(int res) {
            icon.setImageResource(res);
        }

        void label(String s) {
            text.setText(s);
            text.setVisibility(s == null || s.isEmpty() ? GONE : VISIBLE);
        }

        void setLit(boolean lit) {
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(lit ? 0xF2FFFFFF : 0x26FFFFFF);
            icon.setBackground(dot);
            icon.setColorFilter(lit ? 0xFF0B0E16 : 0xFFFFFFFF);
            icon.setPadding(Ui.dp(size / 4), Ui.dp(size / 4), Ui.dp(size / 4), Ui.dp(size / 4));
        }
    }

    /** The hairline of progress, in the song's colour. */
    private static final class Line extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        int color = 0xE6FFFFFF;
        private float value;

        Line(Context c) {
            super(c);
        }

        void set(float v) {
            if (Math.abs(v - value) < 0.0005f) return;
            value = Ui.clamp(v, 0f, 1f);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float h = getHeight(), r = h / 2f;
            p.setColor(0x33FFFFFF);
            c.drawRoundRect(0, 0, getWidth(), h, r, r, p);
            p.setColor(color);
            c.drawRoundRect(0, 0, Math.max(h, getWidth() * value), h, r, r, p);
        }
    }

    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private static LayoutParams match() {
        return new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private static LinearLayout.LayoutParams wrap(int left, int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(WRAP, WRAP);
        p.leftMargin = Ui.dp(left);
        p.topMargin = Ui.dp(top);
        return p;
    }
}
