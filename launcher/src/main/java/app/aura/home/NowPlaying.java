package app.aura.home;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Outline;
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
 * Now Playing, as on Apple TV: whatever is playing (or paused) shown large on frosted glass, with
 * its artwork, how far along it is, and previous, play/pause and next. It follows the session as
 * it changes, and only ticks its progress bar while open.
 */
final class NowPlaying extends FrameLayout {
    private final Activity act;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ImageView art, glow;
    private final TextView app, title, by, elapsed, left;
    private final HomeActivity.Meter bar;
    private final Pad prev, play, next;
    private final ImageView playIcon;
    private MediaController controller;

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

    NowPlaying(Activity a, Backdrop b) {
        super(a);
        act = a;
        setVisibility(GONE);
        GlassFrame veil = new GlassFrame(a, b, 0, 0.6f);
        veil.edgeLight = false;
        addView(veil, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // the artwork's own colour, blurred huge behind everything, like tvOS's ambient light
        glow = new ImageView(a);
        glow.setScaleType(ImageView.ScaleType.CENTER_CROP);
        glow.setAlpha(0.35f);
        addView(glow, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClipChildren(false);
        art = new ImageView(a);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setClipToOutline(true);
        art.setElevation(Ui.dpf(18));
        art.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), Ui.dpf(22));
            }
        });
        row.addView(art, new LinearLayout.LayoutParams(Ui.dp(300), Ui.dp(300)));

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        app = Ui.caps(a, 8, 0xB3FFFFFF, 0.26f);
        col.addView(app);
        title = Ui.text(a, 34, Ui.heavy, 0xFFFFFFFF);
        title.setIncludeFontPadding(true);
        title.setLetterSpacing(-0.03f);
        title.setSingleLine(false);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(title, lp(0, 8));
        by = Ui.text(a, 15, Ui.medium, 0xCCFFFFFF);
        by.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(by, lp(0, 6));
        bar = new HomeActivity.Meter(a);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(5));
        bl.topMargin = Ui.dp(34);
        col.addView(bar, bl);
        LinearLayout times = new LinearLayout(a);
        elapsed = Ui.text(a, 11, Ui.medium, 0x99FFFFFF);
        left = Ui.text(a, 11, Ui.medium, 0x99FFFFFF);
        left.setGravity(Gravity.END);
        times.addView(elapsed, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        times.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        col.addView(times, lp(0, 8));

        LinearLayout buttons = new LinearLayout(a);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        buttons.setClipChildren(false);
        prev = button(R.drawable.ic_prev, 52);
        play = button(R.drawable.ic_pause, 64);
        next = button(R.drawable.ic_next, 52);
        playIcon = (ImageView) play.getChildAt(0);
        prev.onClick = () -> transport(t -> t.skipToPrevious());
        next.onClick = () -> transport(t -> t.skipToNext());
        play.onClick = () -> {
            PlaybackState st = controller != null ? controller.getPlaybackState() : null;
            boolean playing = st != null && st.getState() == PlaybackState.STATE_PLAYING;
            transport(t -> {
                if (playing) t.pause();
                else t.play();
            });
        };
        buttons.addView(prev, buttonLp(52, 0));
        buttons.addView(play, buttonLp(64, 22));
        buttons.addView(next, buttonLp(52, 22));
        LinearLayout.LayoutParams btl = lp(0, 30);
        btl.gravity = Gravity.CENTER_HORIZONTAL;
        col.addView(buttons, btl);

        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(Ui.dp(470), ViewGroup.LayoutParams.WRAP_CONTENT);
        cl.leftMargin = Ui.dp(56);
        row.addView(col, cl);
        addView(row, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
    }

    boolean isOpen() {
        return getVisibility() == VISIBLE;
    }

    /** Opens on {@code c}'s session; false if nothing is playing any more. */
    boolean open(MediaController c) {
        if (c == null) return false;
        controller = c;
        controller.registerCallback(watch, handler);
        show();
        setVisibility(VISIBLE);
        setAlpha(0f);
        animate().alpha(1f).setDuration(260).setStartDelay(0).setInterpolator(Ui.SOFT).start();
        art.setTranslationY(Ui.dp(24));
        art.animate().translationY(0f).setDuration(620).setInterpolator(Ui.EMPHASIZED).start();
        play.requestFocus();
        handler.removeCallbacks(tick);
        handler.post(tick);
        return true;
    }

    void close() {
        if (!isOpen()) return;
        handler.removeCallbacks(tick);
        if (controller != null) controller.unregisterCallback(watch);
        controller = null;
        animate().alpha(0f).setDuration(200).setStartDelay(0).setInterpolator(Ui.SOFT)
                .withEndAction(() -> setVisibility(GONE)).start();
    }

    private interface Action {
        void run(MediaController.TransportControls t);
    }

    private void transport(Action a) {
        if (controller != null) a.run(controller.getTransportControls());
    }

    /** Artwork, words and the play/pause icon from the session as it is now. */
    private void show() {
        if (controller == null) return;
        MediaMetadata md = controller.getMetadata();
        if (md != null) {
            String t = md.getString(MediaMetadata.METADATA_KEY_TITLE);
            String s = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
            if (s == null) s = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
            title.setText(t != null ? t : "");
            by.setText(s != null ? s : "");
            by.setVisibility(s == null || s.isEmpty() ? GONE : VISIBLE);
            Bitmap b = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (b == null) b = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (b == null) b = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            if (b != null) {
                art.setImageBitmap(b);
                glow.setImageBitmap(Backdrop.blur(b));
            } else {
                // no artwork: the app's own icon, large
                try {
                    art.setImageDrawable(act.getPackageManager().getApplicationIcon(controller.getPackageName()));
                } catch (Exception ignored) {
                }
                glow.setImageDrawable(null);
            }
        }
        try {
            CharSequence name = act.getPackageManager().getApplicationLabel(
                    act.getPackageManager().getApplicationInfo(controller.getPackageName(), 0));
            app.setText(("NOW PLAYING  ·  " + name).toUpperCase(Locale.getDefault()));
        } catch (Exception e) {
            app.setText("NOW PLAYING");
        }
        PlaybackState st = controller.getPlaybackState();
        boolean playing = st != null && st.getState() == PlaybackState.STATE_PLAYING;
        playIcon.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        progress();
    }

    /** Where it is now, estimated between the app's own updates from its last position and speed. */
    private void progress() {
        if (controller == null) return;
        MediaMetadata md = controller.getMetadata();
        PlaybackState st = controller.getPlaybackState();
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

    private static String clock(long ms) {
        long s = ms / 1000, h = s / 3600, m = (s / 60) % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s % 60) : String.format(Locale.ROOT, "%d:%02d", m, s % 60);
    }

    private Pad button(int icon, int sizeDp) {
        Pad p = new Pad(act, null, sizeDp / 2f, 0f);
        p.plainColor = 0x26FFFFFF;
        p.edgeLight = false;
        p.focusScale = 1.12f;
        ImageView iv = p.tint(new ImageView(act), 1f);
        iv.setImageResource(icon);
        int s = Math.round(sizeDp * 0.42f);
        p.addView(iv, new LayoutParams(Ui.dp(s), Ui.dp(s), Gravity.CENTER));
        return p;
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
