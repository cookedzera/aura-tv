package app.aura.home;

import android.content.Context;
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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * The song, in the screensaver: a quiet player under the quote, lined up with it and set in the
 * same type, so the photograph, the words and the music read as one page. The cover, the app and
 * "now playing", the song, the artist, and a slim line with the time. A new song fades in; when
 * the music stops it fades away by itself. It redraws once a second, and only its line.
 */
final class MusicStrip extends LinearLayout {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ImageView cover;
    private final TextView kicker, title, by, time;
    private final HomeActivity.Meter bar;
    private MediaController controller;
    private String shownTitle, fetching;
    private Bitmap shownArt;
    private Runnable onGone;

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
            stop();
        }
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            progress();
            if (controller != null) handler.postDelayed(this, 1000);
        }
    };

    MusicStrip(Context c) {
        super(c);
        setGravity(Gravity.CENTER_VERTICAL);
        setAlpha(0f);
        cover = new ImageView(c);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setClipToOutline(true);
        cover.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), Ui.dpf(8));
            }
        });
        addView(cover, new LayoutParams(Ui.dp(58), Ui.dp(58)));
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(VERTICAL);
        kicker = Ui.caps(c, 6.5f, 0x8CFFFFFF, 0.3f);
        col.addView(kicker);
        title = Ui.text(c, 17, Ui.semibold, 0xF2FFFFFF);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setMaxWidth(Ui.dp(460));
        col.addView(title, lp(0, 4));
        by = Ui.text(c, 12, Ui.regular, 0xB3FFFFFF);
        by.setSingleLine(true);
        by.setEllipsize(TextUtils.TruncateAt.END);
        by.setMaxWidth(Ui.dp(460));
        col.addView(by, lp(0, 1));
        LinearLayout line = new LinearLayout(c);
        line.setGravity(Gravity.CENTER_VERTICAL);
        bar = new HomeActivity.Meter(c);
        line.addView(bar, new LayoutParams(Ui.dp(220), Ui.dp(2)));
        time = Ui.text(c, 9, Ui.medium, 0x80FFFFFF);
        line.addView(time, lp(10, 0));
        col.addView(line, lp(0, 8));
        LayoutParams cl = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cl.leftMargin = Ui.dp(16);
        addView(col, cl);
    }

    /** Follows {@code c} until its music stops; {@code gone} is told then. */
    void start(MediaController c, Runnable gone) {
        controller = c;
        onGone = gone;
        c.registerCallback(watch, handler);
        show();
        animate().alpha(1f).setDuration(1200).setStartDelay(600).setInterpolator(Ui.SOFT).start();
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    /** The music stopped, or the screensaver ended: it fades and lets go. */
    void stop() {
        handler.removeCallbacks(tick);
        if (controller != null) {
            try {
                controller.unregisterCallback(watch);
            } catch (RuntimeException ignored) {
            }
        }
        controller = null;
        final Runnable gone = onGone;
        onGone = null;
        animate().alpha(0f).setDuration(700).setStartDelay(0).setInterpolator(Ui.SOFT).withEndAction(() -> {
            if (gone != null) gone.run();
        }).start();
    }

    private void show() {
        if (controller == null) return;
        MediaMetadata md = controller.getMetadata();
        if (md == null) return;
        String t = md.getString(MediaMetadata.METADATA_KEY_TITLE);
        if (t == null) t = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        String s = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
        if (s == null) s = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        if (s == null) s = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
        if (shownTitle != null && t != null && !t.equals(shownTitle)) {
            // a new song: the words fade across rather than snapping
            for (View v : new View[]{title, by, cover}) {
                v.setAlpha(0f);
                v.animate().alpha(1f).setDuration(800).setStartDelay(150).setInterpolator(Ui.SOFT).start();
            }
        }
        shownTitle = t;
        title.setText(t != null ? t : "");
        by.setText(s != null ? s : "");
        by.setVisibility(s == null || s.isEmpty() ? GONE : VISIBLE);
        String app = "";
        try {
            app = String.valueOf(getContext().getPackageManager().getApplicationLabel(
                    getContext().getPackageManager().getApplicationInfo(controller.getPackageName(), 0)));
        } catch (Exception ignored) {
        }
        kicker.setText(("Now playing" + (app.isEmpty() ? "" : "  ·  " + app)).toUpperCase(Locale.getDefault()));
        Bitmap b = MediaWatch.extractBitmap(md);
        String uri = MediaWatch.extractArtUri(md);
        Bitmap cached = MediaWatch.cachedArt(uri);
        if (cached != null) b = cached;
        if (b != null) {
            if (b != shownArt) {
                shownArt = b;
                cover.setImageBitmap(b);
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
            final Context app2 = getContext().getApplicationContext();
            new Thread(() -> {
                Bitmap loaded = Continue.load(app2, want);
                if (loaded == null) return;
                MediaWatch.cacheArt(want, loaded);
                handler.post(() -> {
                    if (controller != null && want.equals(MediaWatch.extractArtUri(controller.getMetadata()))) {
                        shownArt = loaded;
                        cover.setImageBitmap(loaded);
                    }
                });
            }, "strip-art").start();
        }
        progress();
    }

    private void progress() {
        if (controller == null) return;
        MediaMetadata md = controller.getMetadata();
        PlaybackState st = controller.getPlaybackState();
        long dur = md != null ? md.getLong(MediaMetadata.METADATA_KEY_DURATION) : 0;
        boolean on = st != null && st.getState() == PlaybackState.STATE_PLAYING;
        if (st == null || dur <= 0) {
            bar.setVisibility(GONE);
            time.setText(on ? "" : "Paused");
            return;
        }
        long pos = st.getPosition();
        if (on) pos += (long) ((SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime()) * st.getPlaybackSpeed());
        pos = Math.max(0, Math.min(dur, pos));
        bar.setVisibility(VISIBLE);
        bar.set(pos / (float) dur);
        time.setText((on ? "" : "Paused  ·  ") + NowPlaying.clock(pos) + " / " + NowPlaying.clock(dur));
    }

    private static LayoutParams lp(int left, int top) {
        LayoutParams p = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.leftMargin = Ui.dp(left);
        p.topMargin = Ui.dp(top);
        return p;
    }
}
