package app.aura.home;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.VideoView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * What a phone or computer sent over DLNA ({@link Renderer}), full screen: a video or a song through
 * Android's own player, or a photograph. The remote works as on any player: OK plays and pauses,
 * left and right skip ten seconds, Back stops. A slim bar shows what it is and how far along, and
 * fades while it plays. The sender's own play bar follows along.
 */
public class Cast extends Activity implements Renderer.Player {
    private static final long SKIP_MS = 10_000, BAR_MS = 3_500;

    private final Handler main = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private VideoView video;
    private ImageView image;
    private LinearLayout bar;
    private TextView kicker, titleText, timeText;
    private HomeActivity.Meter progress;
    private MediaPlayer mp;
    private boolean prepared, photo, playWhenReady;
    /** Which load this is: a photograph still downloading for an earlier one is let go. */
    private int loadGen;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (prepared && video != null) {
                try {
                    Renderer.posMs = video.getCurrentPosition();
                    Renderer.durMs = Math.max(0, video.getDuration());
                } catch (IllegalStateException ignored) {
                }
                showTime();
            }
            main.postDelayed(this, 500);
        }
    };
    private final Runnable hideBar = () -> bar.animate().alpha(0f).setDuration(400).start();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        Ui.init(this);
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        video = new VideoView(this);
        root.addView(video, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setVisibility(View.GONE);
        root.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        buildBar();
        setContentView(root);
        video.setOnPreparedListener(m -> {
            mp = m;
            prepared = true;
            Renderer.durMs = Math.max(0, video.getDuration());
            long seek = Renderer.pendingSeekMs;
            if (seek >= 0) {
                Renderer.pendingSeekMs = -1;
                long to = Math.max(0, Math.min(seek, Math.max(0, video.getDuration() - 1000)));
                video.seekTo((int) to);
                Renderer.posMs = to;
            }
            if (playWhenReady) play();
            else Renderer.setState("STOPPED");
        });
        video.setOnCompletionListener(m -> {
            Renderer.posMs = Renderer.durMs;
            Renderer.setState("STOPPED");
            finish();
        });
        video.setOnErrorListener((m, what, extra) -> {
            Trouble.note("CAST", "couldn't play " + Renderer.mime + " (" + what + ", " + extra + ")");
            fail(getString(R.string.this_cant_be_played_here));
            return true;
        });
        Renderer.player = this;
        load(true);
    }

    private void buildBar() {
        bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setPadding(Ui.dp(48), Ui.dp(26), Ui.dp(48), Ui.dp(30));
        GradientDrawable shade = new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xCC000000, 0x00000000});
        bar.setBackground(shade);
        kicker = Ui.caps(this, 8f, 0xB3FFFFFF, 0.24f);
        bar.addView(kicker);
        titleText = Ui.text(this, 22, Ui.semibold, 0xFFFFFFFF);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.topMargin = Ui.dp(6);
        bar.addView(titleText, tl);
        progress = new HomeActivity.Meter(this);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(3));
        pl.topMargin = Ui.dp(14);
        bar.addView(progress, pl);
        timeText = Ui.text(this, 10f, Ui.medium, 0xB3FFFFFF);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ml.topMargin = Ui.dp(8);
        bar.addView(timeText, ml);
        root.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
    }

    // ---- Renderer.Player (main thread) -------------------------------------------------------

    @Override
    public void load(boolean play) {
        final int gen = ++loadGen;
        playWhenReady = play;
        prepared = false;
        mp = null;
        String uri = Renderer.uri;
        photo = Renderer.mime.startsWith("image/");
        boolean audio = Renderer.mime.startsWith("audio/");
        kicker.setText(photo ? getString(R.string.photo_from_your_phone) : audio ? getString(R.string.playing_from_your_phone) : getString(R.string.cast_from_your_phone));
        titleText.setText(Renderer.by.isEmpty() ? Renderer.title : Renderer.title + "  ·  " + Renderer.by);
        timeText.setText("");
        progress.set(0f);
        showBar();
        if (photo) {
            video.stopPlayback();
            video.setVisibility(View.GONE);
            image.setVisibility(View.VISIBLE);
            Renderer.setState("TRANSITIONING");
            final int w = getResources().getDisplayMetrics().widthPixels, h = getResources().getDisplayMetrics().heightPixels;
            new SafeThread(() -> {
                Bitmap b = fetch(uri, w, h);
                runOnUiThread(() -> {
                    if (isDestroyed() || gen != loadGen) return;
                    if (b == null) {
                        fail(getString(R.string.this_photo_couldnt_be_opened));
                        return;
                    }
                    image.setImageBitmap(b);
                    Renderer.setState("PLAYING");
                    main.postDelayed(hideBar, BAR_MS);
                });
            }, "cast-photo").start();
        } else {
            image.setImageDrawable(null);
            image.setVisibility(audio ? View.VISIBLE : View.GONE);
            video.setVisibility(View.VISIBLE);
            Renderer.setState("TRANSITIONING");
            video.setVideoURI(Uri.parse(uri));
        }
    }

    @Override
    public void play() {
        if (photo) {
            Renderer.setState("PLAYING");
            return;
        }
        if (!prepared) {
            playWhenReady = true;
            return;
        }
        video.start();
        Renderer.setState("PLAYING");
        showBar();
        main.removeCallbacks(hideBar);
        main.postDelayed(hideBar, BAR_MS);
    }

    @Override
    public void pause() {
        if (prepared && video.isPlaying()) video.pause();
        playWhenReady = false;
        Renderer.setState("PAUSED_PLAYBACK");
        showBar();
        main.removeCallbacks(hideBar);
    }

    @Override
    public void stop() {
        Renderer.setState("STOPPED");
        finish();
    }

    @Override
    public void seek(long ms) {
        if (!prepared) {
            Renderer.pendingSeekMs = ms;
            Renderer.posMs = Math.max(0, ms);
            showTime();
            return;
        }
        long to = Math.max(0, Math.min(ms, Math.max(0, video.getDuration() - 1000)));
        video.seekTo((int) to);
        Renderer.posMs = to;
        showTime();
        showBar();
        main.removeCallbacks(hideBar);
        if (video.isPlaying()) main.postDelayed(hideBar, BAR_MS);
        Renderer.changed();
    }

    // ---- the remote --------------------------------------------------------------------------

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (prepared && video.isPlaying()) pause();
                else play();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                play();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                pause();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                if (prepared) seek(video.getCurrentPosition() - SKIP_MS);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                if (prepared) seek(video.getCurrentPosition() + SKIP_MS);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                showBar();
                main.removeCallbacks(hideBar);
                if (prepared && video.isPlaying() || photo) main.postDelayed(hideBar, BAR_MS);
                return true;
            case KeyEvent.KEYCODE_MEDIA_STOP:
                stop();
                return true;
            default:
                return super.onKeyDown(code, e);
        }
    }

    @Override
    public void onBackPressed() {
        stop();
    }

    @Override
    protected void onStart() {
        super.onStart();
        main.post(tick);
    }

    @Override
    protected void onStop() {
        super.onStop();
        main.removeCallbacks(tick);
        // another app took the screen: what was playing stops, as on any TV
        if (!isFinishing()) {
            Renderer.setState("STOPPED");
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        video.stopPlayback();
        if (Renderer.player == this) Renderer.player = null;
        if (!"STOPPED".equals(Renderer.state) && !"NO_MEDIA_PRESENT".equals(Renderer.state)) Renderer.setState("STOPPED");
        super.onDestroy();
    }

    // ---- small things ------------------------------------------------------------------------

    private void showBar() {
        bar.animate().cancel();
        bar.setAlpha(1f);
    }

    private void showTime() {
        long pos = Renderer.posMs, dur = Renderer.durMs;
        timeText.setText(dur > 0 ? Renderer.clock(pos) + "  /  " + Renderer.clock(dur) : Renderer.clock(pos));
        progress.set(dur > 0 ? Math.min(1f, pos / (float) dur) : 0f);
    }

    private void fail(String why) {
        kicker.setText(getString(R.string.couldnt_play));
        titleText.setText(why);
        showBar();
        Renderer.setState("STOPPED");
        main.postDelayed(this::finish, 4000);
    }

    /** A photograph from the network, decoded no larger than the screen. Off the main thread. */
    private static Bitmap fetch(String url, int w, int h) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(15000);
            byte[] data;
            try (InputStream in = c.getInputStream()) {
                java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[32 * 1024];
                for (int n; (n = in.read(buf)) > 0; ) {
                    b.write(buf, 0, n);
                    if (b.size() > 40 << 20) return null;
                }
                data = b.toByteArray();
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= w && o.outHeight / (sample * 2) >= h) sample *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
