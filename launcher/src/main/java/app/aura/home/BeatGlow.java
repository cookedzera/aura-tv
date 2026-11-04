package app.aura.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.media.audiofx.Visualizer;
import android.view.View;

/**
 * Beat glow: the scene breathes with the bass. Over a photograph, a soft light low on the screen,
 * in the song's own colour, swells with it; over a video a light would only lie on it as a haze,
 * so there the picture itself brightens on each beat instead ({@link Pulse}), from a little dimmed.
 * The bass is read from Android's visualizer of what the TV plays: a few numbers about the low
 * notes, twenty times a second at most, never the sound itself, and nothing is kept. Fast to rise,
 * slow to fall, so it breathes rather than flickers; it redraws only while the light is moving.
 */
final class BeatGlow extends View {
    /** Up to this pitch counts as bass: the kick drum and the bass line, not the voice. */
    private static final float BASS_HZ = 160f;

    private final Paint paint = new Paint();
    private Visualizer viz;
    private int color = 0xFFFFE2B8;
    private float level, target, peak = 1f;

    /** Told the bass's strength (0 to 1) on each frame while it moves. */
    interface Pulse {
        void level(float l);
    }

    Pulse pulse;
    /** Whether the light itself is drawn (over a photograph), or only the {@link Pulse} told. */
    boolean light = true;

    BeatGlow(Context c) {
        super(c);
        setWillNotDraw(false);
    }

    /** Allowed to read the TV's sound: setup grants it, or it was asked for on screen. */
    static boolean allowed(Context c) {
        return Compat.granted(c, android.Manifest.permission.RECORD_AUDIO);
    }

    /** Starts listening; false when this TV won't share its sound (the light then simply rests). */
    boolean start() {
        stop();
        try {
            viz = new Visualizer(0);
            viz.setEnabled(false);
            int[] range = Visualizer.getCaptureSizeRange();
            viz.setCaptureSize(Math.max(range[0], Math.min(range[1], 1024)));
            viz.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override
                public void onWaveFormDataCapture(Visualizer v, byte[] wave, int rate) {
                }

                @Override
                public void onFftDataCapture(Visualizer v, byte[] fft, int rate) {
                    heard(fft, rate);
                }
            }, Math.min(Visualizer.getMaxCaptureRate(), 20_000), false, true);
            viz.setEnabled(true);
            return true;
        } catch (RuntimeException e) {
            // not allowed, or this TV's sound can't be read (some send it straight to the speakers)
            stop();
            return false;
        }
    }

    void stop() {
        if (viz == null) return;
        try {
            viz.setEnabled(false);
            viz.release();
        } catch (RuntimeException ignored) {
        }
        viz = null;
    }

    /** The light takes the song's colour (from its cover). */
    void setColor(int c) {
        if (c == color) return;
        color = c;
        shade(getWidth(), getHeight());
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        shade(w, h);
    }

    private void shade(int w, int h) {
        if (w <= 0 || h <= 0) return;
        paint.setShader(new RadialGradient(w / 2f, h * 1.08f, w * 0.62f,
                new int[]{Ui.withAlpha(color, 0xFF), Ui.withAlpha(color, 0x55), Ui.withAlpha(color, 0)},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
    }

    /** How strong the bass is now, against the loudest it has been lately (so quiet songs breathe too). */
    private void heard(byte[] fft, int rateMilliHz) {
        int n = fft.length / 2;
        if (n < 2 || rateMilliHz <= 0) return;
        float binHz = rateMilliHz / 1000f / fft.length;
        float sum = 0;
        for (int k = 1; k < n && k * binHz <= BASS_HZ; k++) {
            float re = fft[2 * k], im = fft[2 * k + 1];
            sum += (float) Math.sqrt(re * re + im * im);
        }
        peak = Math.max(peak * 0.995f, Math.max(sum, 1f));
        target = Math.min(1f, sum / peak);
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas c) {
        level += (target - level) * (target > level ? 0.45f : 0.07f);
        if (light) {
            paint.setAlpha(Math.round(255 * (0.06f + 0.26f * level * level)));
            c.drawRect(0, 0, getWidth(), getHeight(), paint);
        }
        if (pulse != null) pulse.level(level);
        if (Math.abs(target - level) > 0.01f) postInvalidateOnAnimation();
    }
}
