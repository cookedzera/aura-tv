package app.aura.home;

import android.content.Context;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.util.Random;

/**
 * Apple's aerial videos, one after another, for Music scenes and the screensaver. Each streams from
 * Apple's server as it plays and is never saved; the TV's video chip draws it on a layer of its own.
 * A video loads out of sight while what's on screen stays (the photograph, or the scene before), so
 * there's never a black wait; once its first frame is drawn, a brief dim and it fills the screen.
 * Whatever lies beneath this view shows until then, and again if the network goes.
 */
final class AerialView extends FrameLayout {
    private static final long GIVE_UP_MS = 60_000;
    private static final java.util.concurrent.ExecutorService RELEASE = Compat.background("aerial-release", android.os.Process.THREAD_PRIORITY_BACKGROUND);

    interface Listener {
        /** A video now fills the screen. */
        void shown(Aerials.Video v);

        /** None fills it any more (it couldn't load, or stopped): what lies beneath shows again. */
        void lost();
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random rnd = new Random();
    private final boolean loop;
    private final Listener listener;
    private final View veil;
    private Slot shown, coming;
    private String kind = "mix", lastUrl;
    /** This TV wouldn't play two videos at once: the last one goes before the next one loads. */
    private boolean oneAtATime;
    private int failures;
    private boolean veilBusy;
    private float rest;

    /** With {@code loop}, a video plays on until {@link #next}; without, the next follows when it ends. */
    AerialView(Context c, boolean loop, Listener l) {
        super(c);
        this.loop = loop;
        listener = l;
        veil = new View(c);
        veil.setBackgroundColor(0xFF000000);
        veil.setAlpha(0f);
        addView(veil, match());
    }

    /** Another video of {@code kind} ("mix" for any) that suits the light outside, never the last one. */
    void next(String kind) {
        this.kind = kind;
        if (failures >= 3) return;
        Aerials.Video v = Aerials.pick(Aerials.all(getContext()), kind, Sun.phase(System.currentTimeMillis()), lastUrl, rnd);
        if (v == null) return;
        lastUrl = v.url;
        if (coming != null) {
            coming.release();
            coming = null;
        }
        if (oneAtATime && shown != null) {
            // the video on screen gives way to what's beneath first, then the next one loads
            final Slot old = shown;
            final Aerials.Video nextVideo = v;
            shown = null;
            veil(1f, 500, () -> {
                old.release();
                listener.lost();
                veil(0f, 700, null);
                startComing(nextVideo, false);
            });
            return;
        }
        startComing(v, false);
    }

    private void startComing(Aerials.Video v, boolean forceH264) {
        if (coming != null) coming.release();
        coming = new Slot(v, forceH264);
        handler.removeCallbacks(giveUp);
        handler.postDelayed(giveUp, GIVE_UP_MS);
    }

    /** How dim the video rests (after dark, or with room for the beat to brighten it). */
    void setRest(float dim) {
        rest = dim;
        if (shown != null && !veilBusy) veil.setAlpha(dim);
    }

    /** The bass (0 to 1): the video brightens from its resting dim on each beat. */
    void pulse(float level) {
        if (shown == null || veilBusy) return;
        veil.setAlpha(rest * (1f - 0.85f * level));
    }

    boolean showing() {
        return shown != null;
    }

    /** The video dims away over {@code ms}, as what's next arrives (home, or the photographs). */
    void dimOut(long ms) {
        handler.removeCallbacksAndMessages(null);
        veil(1f, ms, null);
    }

    /**
     * Smoothly hands the screen back to what lies beneath (the photograph or home): if a video is
     * on screen, dips briefly to black, removes the video surface so the photograph underneath is
     * uncovered, and fades the veil back open. If no video is showing yet, lets go immediately
     * without flashing black.
     */
    void exit(Runnable done) {
        handler.removeCallbacksAndMessages(null);
        if (coming != null) {
            coming.release();
            coming = null;
        }
        if (shown == null) {
            veil.animate().cancel();
            veil.setAlpha(0f);
            if (done != null) done.run();
            return;
        }
        final Slot old = shown;
        shown = null;
        veil(1f, 150, () -> {
            old.release();
            veil(0f, 240, done);
        });
    }

    void release() {
        handler.removeCallbacksAndMessages(null);
        veil.animate().cancel();
        if (coming != null) coming.release();
        if (shown != null) shown.release();
        coming = shown = null;
    }

    // ---- one video -----------------------------------------------------------------------------

    /** One video and the surface it plays on: a single pixel, out of sight, until it's ready. */
    private final class Slot implements SurfaceHolder.Callback {
        final Aerials.Video v;
        final boolean usedHevc;
        final String streamUrl;
        final SurfaceView view;
        MediaPlayer mp;

        Slot(Aerials.Video v, boolean forceH264) {
            this.v = v;
            usedHevc = !forceH264 && v.usesHevc();
            streamUrl = usedHevc ? v.hevc : v.url;
            view = new SurfaceView(getContext());
            view.getHolder().addCallback(this);
            // beneath the veil: the video chip draws it, everything above sits on top
            addView(view, 0, new LayoutParams(1, 1));
        }

        @Override
        public void surfaceCreated(SurfaceHolder h) {
            if (mp != null) return;
            try {
                MediaPlayer m = new MediaPlayer();
                mp = m;
                m.setSurface(h.getSurface());
                m.setDataSource(getContext(), Uri.parse(streamUrl));
                m.setLooping(loop);
                m.setVolume(0f, 0f);
                m.setOnPreparedListener(x -> {
                    x.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
                    x.start();
                });
                m.setOnInfoListener((x, what, extra) -> {
                    if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) ready(this);
                    return false;
                });
                // played through (the screensaver): the next one, while this one holds its last frame
                m.setOnCompletionListener(x -> {
                    if (this == shown) next(kind);
                });
                m.setOnErrorListener((x, what, extra) -> {
                    failed(this, "error " + what + "/" + extra);
                    return true;
                });
                m.prepareAsync();
            } catch (Exception e) {
                failed(this, e.toString());
            }
        }

        @Override
        public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) {
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder h) {
            releasePlayer();
        }

        void grow() {
            view.setLayoutParams(match());
        }

        /**
         * Letting go of a stream waits on its network connection and the video chip: on the main
         * thread it held up the very key press that left the scene, so it's done to one side.
         */
        private void releasePlayer() {
            final MediaPlayer m = mp;
            mp = null;
            if (m == null) return;
            m.setOnPreparedListener(null);
            m.setOnInfoListener(null);
            m.setOnCompletionListener(null);
            m.setOnErrorListener(null);
            RELEASE.execute(() -> {
                try {
                    m.setSurface(null);
                } catch (RuntimeException ignored) {
                }
                try {
                    m.release();
                } catch (RuntimeException ignored) {
                }
            });
        }

        void release() {
            view.getHolder().removeCallback(this);
            releasePlayer();
            removeView(view);
        }
    }

    private void ready(Slot s) {
        if (s != coming) return;
        handler.removeCallbacks(giveUp);
        failures = 0;
        // a brief dim, the new video fills the screen, and it fades up
        veil(1f, shown == null ? 650 : 500, () -> {
            if (s != coming) {
                s.release();
                return;
            }
            if (shown != null) shown.release();
            shown = s;
            coming = null;
            s.grow();
            listener.shown(s.v);
            veil(rest, 1400, null);
        });
    }

    private void failed(Slot s, String why) {
        Trouble.note("AERIAL", s.v.label + ": " + why);
        if (s == coming) {
            coming = null;
            s.release();
            handler.removeCallbacks(giveUp);
            if (s.usedHevc && s.v.url != null && !s.v.url.equals(s.streamUrl)) {
                // this TV's HEVC decoder refused the HEVC copy after all: the H.264 one, right away
                Aerials.disableHevc();
                startComing(s.v, true);
                return;
            }
            failures++;
            if (shown != null && !oneAtATime) {
                // likely two videos at once was too much for this TV: one at a time from now on
                oneAtATime = true;
                lastUrl = null;
            }
            if (failures >= 3 && shown == null) listener.lost();
            else handler.postDelayed(() -> next(kind), 1500);
        } else if (s == shown) {
            // the video on screen stopped (the network went): what's beneath comes back
            shown = null;
            veil(1f, 400, () -> {
                s.release();
                listener.lost();
                veil(0f, 900, null);
            });
        }
    }

    private final Runnable giveUp = () -> {
        if (coming != null) failed(coming, "no picture within " + GIVE_UP_MS / 1000 + " s");
    };

    private void veil(float to, long ms, Runnable then) {
        veilBusy = true;
        veil.animate().alpha(to).setDuration(ms).setStartDelay(0).setInterpolator(Ui.SOFT).withEndAction(() -> {
            veilBusy = false;
            if (then != null) then.run();
        }).start();
    }

    private static LayoutParams match() {
        return new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }
}
