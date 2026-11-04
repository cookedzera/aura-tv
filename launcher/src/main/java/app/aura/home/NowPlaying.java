package app.aura.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * Now Playing, made for listening: the screen takes the album's own colours in one smooth wash,
 * with a soft glow behind the cover, and everything sits centred like a record on a stand: the
 * cover, the song in light type, the artist, a slim line and quiet buttons. After a few seconds
 * without a press the line and buttons fade away, leaving the cover and the song; any button brings
 * them back. A new song's colours and words fade in over the last. Nothing is blurred, so it stays
 * sharp; a small cover from the app is swapped for its full-size picture when there is one.
 */
final class NowPlaying extends FrameLayout {
    private static final long REST_MS = 6000;
    /** The cover's size: shown at its own sharpness, not stretched from a thumbnail. */
    private static final int COVER = 220;
    /** Covers smaller than this (in pixels) are worth fetching again at full size. */
    private static final int SHARP_PX = 420;
    private static final int[] NO_ART = {0xFF1A1D2E, 0xFF2A2140, 0xFF141722};

    private final Context act;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ImageView art;
    /** Two washes, one fading over the other as the colours change. */
    private final View washA, washB, halo;
    private boolean onA = true;
    private final TextView app, title, by, elapsed, left;
    private final HomeActivity.Meter bar;
    private final LinearLayout controls;
    private final Pad prev, play, next;
    private final ImageView playIcon;
    private MediaController controller;
    private String shownTitle;

    private final MediaController.Callback watch = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            show();
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            show();
        }

        @Override
        public void onSessionDestroyed() {
            close();
        }
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            progress();
            if (isOpen()) handler.postDelayed(this, 500);
        }
    };

    /** Nothing pressed for a while: only the cover and the song stay. */
    private final Runnable rest = this::rest;

    private void rest() {
        if (!isOpen()) return;
        controls.animate().alpha(0f).translationY(Ui.dp(10)).setDuration(900).setStartDelay(0).setInterpolator(Ui.SOFT).start();
        app.animate().alpha(0.45f).setDuration(900).setStartDelay(0).start();
    }

    NowPlaying(Context a, Backdrop b) {
        super(a);
        act = a;
        setVisibility(GONE);
        setBackgroundColor(0xFF000000);
        washA = new View(a);
        washB = new View(a);
        washA.setBackground(wash(NO_ART));
        washB.setAlpha(0f);
        addView(washA, full());
        addView(washB, full());

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setClipChildren(false);
        col.setClipToPadding(false);

        // a soft light of the cover's colour behind it, so it sits in the scene rather than on it
        FrameLayout stand = new FrameLayout(a);
        stand.setClipChildren(false);
        halo = new View(a);
        halo.setBackground(glow(0xFFFFFFFF));
        halo.setAlpha(0.5f);
        stand.addView(halo, new LayoutParams(Ui.dp(COVER + 220), Ui.dp(COVER + 220), Gravity.CENTER));
        art = new ImageView(a);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setClipToOutline(true);
        art.setElevation(Ui.dpf(20));
        art.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), Ui.dpf(14));
            }
        });
        stand.addView(art, new LayoutParams(Ui.dp(COVER), Ui.dp(COVER), Gravity.CENTER));
        col.addView(stand, new LinearLayout.LayoutParams(Ui.dp(COVER + 40), Ui.dp(COVER + 20)));

        app = Ui.caps(a, 7.5f, 0x99FFFFFF, 0.32f);
        col.addView(app, lp(0, 22));
        title = Ui.text(a, 30, Ui.light, 0xFFFFFFFF);
        title.setIncludeFontPadding(true);
        title.setLetterSpacing(-0.02f);
        title.setSingleLine(true);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        title.setMaxWidth(Ui.dp(640));
        title.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(title, lp(0, 8));
        by = Ui.text(a, 15, Ui.medium, 0xCCFFFFFF);
        by.setSingleLine(true);
        by.setGravity(Gravity.CENTER_HORIZONTAL);
        by.setMaxWidth(Ui.dp(560));
        by.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(by, lp(0, 6));

        controls = new LinearLayout(a);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setGravity(Gravity.CENTER_HORIZONTAL);
        controls.setClipChildren(false);
        LinearLayout line = new LinearLayout(a);
        line.setGravity(Gravity.CENTER_VERTICAL);
        elapsed = Ui.text(a, 10, Ui.medium, 0x8CFFFFFF);
        elapsed.setGravity(Gravity.END);
        left = Ui.text(a, 10, Ui.medium, 0x8CFFFFFF);
        line.addView(elapsed, new LinearLayout.LayoutParams(Ui.dp(44), ViewGroup.LayoutParams.WRAP_CONTENT));
        bar = new HomeActivity.Meter(a);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(Ui.dp(360), Ui.dp(3));
        bl.leftMargin = bl.rightMargin = Ui.dp(12);
        line.addView(bar, bl);
        line.addView(left, new LinearLayout.LayoutParams(Ui.dp(44), ViewGroup.LayoutParams.WRAP_CONTENT));
        controls.addView(line, lp(0, 0));

        LinearLayout buttons = new LinearLayout(a);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        buttons.setClipChildren(false);
        prev = button(R.drawable.ic_prev, 42);
        play = button(R.drawable.ic_pause, 52);
        next = button(R.drawable.ic_next, 42);
        playIcon = (ImageView) play.getChildAt(0);
        prev.onClick = () -> transport(t -> t.skipToPrevious());
        next.onClick = () -> transport(t -> t.skipToNext());
        play.onClick = () -> {
            PlaybackState st = controller != null ? MediaWatch.state(controller) : null;
            boolean playing = st != null && st.getState() == PlaybackState.STATE_PLAYING;
            transport(t -> {
                if (playing) t.pause();
                else t.play();
            });
        };
        buttons.addView(prev, buttonLp(42, 0));
        buttons.addView(play, buttonLp(52, 26));
        buttons.addView(next, buttonLp(42, 26));
        controls.addView(buttons, lp(0, 16));
        col.addView(controls, lp(0, 24));

        addView(col, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
    }

    boolean isOpen() {
        return getVisibility() == VISIBLE;
    }

    /** Opens on {@code c}'s session; false if nothing is playing any more. */
    boolean open(MediaController c) {
        if (c == null) return false;
        controller = c;
        controller.registerCallback(watch, handler);
        shownTitle = null;
        show();
        setVisibility(VISIBLE);
        setAlpha(0f);
        animate().alpha(1f).setDuration(420).setStartDelay(0).setInterpolator(Ui.SOFT).start();
        art.setTranslationY(Ui.dp(16));
        art.setAlpha(0f);
        art.animate().translationY(0f).alpha(1f).setDuration(820).setStartDelay(80).setInterpolator(Ui.EMPHASIZED).start();
        play.requestFocus();
        poke();
        handler.removeCallbacks(tick);
        handler.post(tick);
        return true;
    }

    void close() {
        if (!isOpen()) return;
        handler.removeCallbacks(tick);
        handler.removeCallbacks(rest);
        if (controller != null) controller.unregisterCallback(watch);
        controller = null;
        lastArt = null;
        lastArtUri = null;
        artFor = null;
        animate().alpha(0f).setDuration(260).setStartDelay(0).setInterpolator(Ui.SOFT)
                .withEndAction(() -> setVisibility(GONE)).start();
    }

    /** A button was pressed: the controls come back, and rest again after a while. */
    void poke() {
        handler.removeCallbacks(rest);
        if (controls.getAlpha() < 1f) {
            controls.animate().alpha(1f).translationY(0f).setDuration(300).setStartDelay(0).setInterpolator(Ui.SOFT).start();
            app.animate().alpha(1f).setDuration(300).setStartDelay(0).start();
        }
        handler.postDelayed(rest, REST_MS);
    }

    private Bitmap lastArt;
    private String lastArtUri, fetching;
    /** The song the cover on show belongs to. */
    private String artFor;

    private interface Action {
        void run(MediaController.TransportControls t);
    }

    private void transport(Action a) {
        if (controller != null) a.run(controller.getTransportControls());
    }

    /** Artwork, words and the play/pause icon from the session as it is now. */
    private void show() {
        if (controller == null) return;
        MediaMetadata md = MediaWatch.meta(controller);
        if (md != null) {
            String t = md.getString(MediaMetadata.METADATA_KEY_TITLE);
            if (t == null) t = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
            String s = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
            if (s == null) s = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
            if (s == null) s = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
            // a new song: its words fade in rather than snapping over the last one's
            if (shownTitle != null && t != null && !t.equals(shownTitle)) {
                for (View v : new View[]{title, by}) {
                    v.setAlpha(0f);
                    v.animate().alpha(1f).setDuration(600).setStartDelay(150).setInterpolator(Ui.SOFT).start();
                }
            }
            shownTitle = t;
            title.setText(t != null ? t : "");
            by.setText(s != null ? s : "");
            by.setVisibility(s == null || s.isEmpty() ? GONE : VISIBLE);
            Bitmap b = MediaWatch.extractBitmap(md);
            String uri = MediaWatch.extractArtUri(md);
            Bitmap cached = MediaWatch.cachedArt(uri);
            // the app's own picture is often a thumbnail: the full-size one from its link, when there is one
            if (cached != null && (b == null || cached.getWidth() > b.getWidth())) b = cached;
            if (b != null) {
                setArt(b);
                artFor = t;
            } else if (lastArt == null || uri == null && !java.util.Objects.equals(t, artFor)) {
                // a new song with no cover at all: the last song's cover and colours don't stay behind
                lastArt = null;
                artFor = t;
                appIcon();
            }
            if (uri != null && (b == null || b.getWidth() < SHARP_PX) && !uri.equals(fetching) && !uri.equals(lastArtUri)) {
                fetching = uri;
                final String want = uri;
                new SafeThread(() -> {
                    Bitmap loaded = Continue.load(act, want);
                    handler.post(() -> {
                        if (want.equals(fetching)) fetching = null;
                        boolean current = isOpen() && want.equals(MediaWatch.extractArtUri(controller != null ? MediaWatch.meta(controller) : null));
                        if (loaded == null) {
                            // its cover couldn't be had: the last song's isn't left standing in for it
                            if (current && lastArt != null && !java.util.Objects.equals(shownTitle, artFor)) {
                                lastArt = null;
                                artFor = shownTitle;
                                appIcon();
                            }
                            return;
                        }
                        lastArtUri = want;
                        MediaWatch.cacheArt(want, loaded);
                        if (current) {
                            setArt(loaded);
                            artFor = shownTitle;
                        }
                    });
                }, "np-art").start();
            }
        }
        try {
            CharSequence name = act.getPackageManager().getApplicationLabel(
                    act.getPackageManager().getApplicationInfo(controller.getPackageName(), 0));
            app.setText((act.getString(R.string.now_playing_caps) + "  ·  " + name).toUpperCase(Locale.getDefault()));
        } catch (Exception e) {
            app.setText(act.getString(R.string.now_playing_caps));
        }
        PlaybackState st = MediaWatch.state(controller);
        boolean playing = st != null && st.getState() == PlaybackState.STATE_PLAYING;
        playIcon.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        progress();
    }

    /** A new cover fades in over the old one, and its colours across the screen with it. */
    private void setArt(Bitmap b) {
        if (b == lastArt || lastArt != null && b.sameAs(lastArt)) return;
        boolean first = lastArt == null;
        lastArt = b;
        art.setImageBitmap(b);
        int[] c = colours(b);
        // washA lies under washB: the new colours go on whichever is hidden, and B fades in or out over A
        washA.animate().cancel();
        washB.animate().cancel();
        if (onA) {
            washB.setBackground(wash(c));
            washB.animate().alpha(1f).setDuration(first ? 0 : 1100).setStartDelay(0).setInterpolator(Ui.SOFT).start();
        } else {
            washA.setBackground(wash(c));
            washB.animate().alpha(0f).setDuration(first ? 0 : 1100).setStartDelay(0).setInterpolator(Ui.SOFT).start();
        }
        onA = !onA;
        halo.setBackground(glow(c[1]));
        if (!first) {
            art.setAlpha(0.3f);
            art.animate().alpha(1f).setDuration(600).setStartDelay(0).setInterpolator(Ui.SOFT).start();
        }
    }

    /** No cover at all: the app's own icon, over calm night colours. */
    private void appIcon() {
        try {
            art.setImageDrawable(act.getPackageManager().getApplicationIcon(controller.getPackageName()));
        } catch (Exception ignored) {
        }
        washA.setBackground(wash(NO_ART));
        washB.setAlpha(0f);
        onA = true;
        halo.setBackground(glow(0xFF8090C0));
    }

    /**
     * Three colours from the cover (its top left, middle and bottom right), deepened so white type
     * always reads on them and lifted a little so a grey cover still gives some colour.
     */
    private static int[] colours(Bitmap b) {
        try {
            Bitmap s = Bitmap.createScaledBitmap(b, 3, 3, true);
            int[] out = {tone(s.getPixel(0, 0), 0.42f), tone(s.getPixel(1, 1), 0.5f), tone(s.getPixel(2, 2), 0.28f)};
            if (s != b) s.recycle();
            return out;
        } catch (RuntimeException e) {
            return NO_ART;
        }
    }

    private static int tone(int c, float value) {
        float[] hsv = new float[3];
        Color.colorToHSV(c, hsv);
        hsv[1] = Math.min(1f, hsv[1] * 1.15f + 0.08f);
        hsv[2] = Math.min(hsv[2], value) * 0.6f + value * 0.4f;
        return Color.HSVToColor(hsv);
    }

    private static GradientDrawable wash(int[] c) {
        return new GradientDrawable(GradientDrawable.Orientation.TL_BR, c);
    }

    private static GradientDrawable glow(int c) {
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setGradientRadius(Ui.dpf(COVER + 110) / 2f * 1.0f);
        g.setColors(new int[]{(c & 0x00FFFFFF) | 0x80000000, c & 0x00FFFFFF});
        return g;
    }

    /** Where it is now, estimated between the app's own updates from its last position and speed. */
    private void progress() {
        if (controller == null) return;
        MediaMetadata md = MediaWatch.meta(controller);
        PlaybackState st = MediaWatch.state(controller);
        long dur = md != null ? md.getLong(MediaMetadata.METADATA_KEY_DURATION) : 0;
        if (st == null || dur <= 0) {
            bar.setVisibility(INVISIBLE);
            elapsed.setText("");
            left.setText("");
            return;
        }
        long pos = st.getPosition();
        if (st.getState() == PlaybackState.STATE_PLAYING) {
            pos += (long) ((SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime()) * st.getPlaybackSpeed());
        }
        pos = Math.max(0, Math.min(dur, pos));
        bar.setVisibility(VISIBLE);
        bar.set(pos / (float) dur);
        elapsed.setText(clock(pos));
        left.setText("-" + clock(dur - pos));
    }

    static String clock(long ms) {
        long s = ms / 1000, h = s / 3600, m = (s / 60) % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s % 60) : String.format(Locale.ROOT, "%d:%02d", m, s % 60);
    }

    private Pad button(int icon, int sizeDp) {
        Pad p = new Pad(act, null, sizeDp / 2f, 0f);
        p.plainColor = 0x1AFFFFFF;
        p.edgeLight = false;
        p.focusScale = 1.12f;
        ImageView iv = p.tint(new ImageView(act), 1f);
        iv.setImageResource(icon);
        int s = Math.round(sizeDp * 0.4f);
        p.addView(iv, new LayoutParams(Ui.dp(s), Ui.dp(s), Gravity.CENTER));
        return p;
    }

    private static LayoutParams full() {
        return new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private static LinearLayout.LayoutParams buttonLp(int sizeDp, int leftDp) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(Ui.dp(sizeDp), Ui.dp(sizeDp));
        l.leftMargin = Ui.dp(leftDp);
        return l;
    }

    private static LinearLayout.LayoutParams lp(int left, int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.leftMargin = Ui.dp(left);
        p.topMargin = Ui.dp(top);
        return p;
    }
}
