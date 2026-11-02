package app.aura.home;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;

import java.util.Calendar;

/**
 * Fire OS 6 and 7 (Android 7 to 9). Home there opens Amazon's home screen, and Android then holds
 * back any other app's screen for five seconds: its "app switch" guard, which only system apps, the
 * recents screen and the voice assistant are spared (ActivityManagerService.checkAppSwitchAllowedLocked).
 * Aura's home was asked for at once and still came up to five seconds late, with Amazon's home and
 * its rows live underneath in the meantime.
 *
 * An accessibility service may draw over everything straight away, so the wait is covered: the
 * photograph's frosted colours and the time, where Aura's home is about to be. Keys are held back,
 * so nothing is pressed on Amazon's home underneath, and Back lets the cover go. It fades the moment
 * the home screen is up, or after seven seconds whatever happens. Android 10 and later (Fire OS 8,
 * Google TV) don't hold Aura back, and don't get it.
 */
final class HomeCover {
    private static final long MAX_MS = 7_000;
    private static View shown;
    /** The cover fading out, until it's removed. */
    private static View fading;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final Runnable giveUp = () -> hide(true);

    private HomeCover() { }

    /** Only where Android holds Aura back after Home. */
    static boolean needed() {
        return Build.VERSION.SDK_INT <= 28;
    }

    /** From the Home button service, as it asks for Aura's home. Main thread. */
    static void show(AccessibilityService s) {
        if (!needed() || shown != null) return;
        // one still fading from the last Home press goes at once: never two covers on screen
        dropFading();
        WindowManager wm = (WindowManager) s.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) return;
        Cover v = new Cover(s);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_FULLSCREEN
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.OPAQUE);
        lp.windowAnimations = 0;
        try {
            wm.addView(v, lp);
        } catch (RuntimeException e) {
            // this TV won't have it: Amazon's home shows for the wait, as before
            Trouble.caught("home cover", e);
            return;
        }
        shown = v;
        v.setAlpha(0f);
        v.animate().alpha(1f).setDuration(120).start();
        v.requestFocus();
        main.removeCallbacks(giveUp);
        main.postDelayed(giveUp, MAX_MS);
    }

    /** The home screen is up (or Back was pressed, or it took too long): the cover goes. Main thread. */
    static void hide(boolean fade) {
        // asked to go at once (the service is going): one still fading goes with it
        if (!fade) dropFading();
        final View v = shown;
        if (v == null) return;
        shown = null;
        main.removeCallbacks(giveUp);
        if (fade) {
            fading = v;
            v.animate().alpha(0f).setDuration(180).withEndAction(() -> {
                if (fading == v) fading = null;
                remove(v);
            }).start();
        } else {
            remove(v);
        }
    }

    private static void dropFading() {
        View f = fading;
        if (f == null) return;
        fading = null;
        f.animate().cancel();
        remove(f);
    }

    private static void remove(View v) {
        try {
            WindowManager wm = (WindowManager) v.getContext().getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) wm.removeView(v);
        } catch (RuntimeException ignored) {
            // already gone with its service
        }
    }

    /** The frosted photograph, darkened, with the time where the greeting will be. */
    private static final class Cover extends View {
        private final Paint pic = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint shade = new Paint();
        private final Paint time = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Matrix m = new Matrix();
        private final Bitmap blur = Backdrop.lastBlur;
        private final String clock;

        Cover(Context c) {
            super(c);
            setFocusable(true);
            setFocusableInTouchMode(true);
            time.setColor(0xE6FFFFFF);
            time.setTextAlign(Paint.Align.CENTER);
            time.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            time.setShadowLayer(12f, 0f, 2f, 0x66000000);
            Calendar now = Calendar.getInstance();
            clock = DateFormat.format(DateFormat.is24HourFormat(c) ? "H:mm" : "h:mm", now).toString();
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            shade.setShader(new LinearGradient(0, 0, 0, h, 0x8C05070C, 0xD905070C, Shader.TileMode.CLAMP));
            time.setTextSize(h * 0.16f);
            if (blur != null && blur.getWidth() > 0 && blur.getHeight() > 0) {
                float k = Math.max(w / (float) blur.getWidth(), h / (float) blur.getHeight());
                m.setScale(k, k);
                m.postTranslate((w - blur.getWidth() * k) / 2f, (h - blur.getHeight() * k) / 2f);
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            c.drawColor(Color.rgb(5, 7, 12));
            if (blur != null && !blur.isRecycled()) c.drawBitmap(blur, m, pic);
            c.drawRect(0, 0, getWidth(), getHeight(), shade);
            c.drawText(clock, getWidth() / 2f, getHeight() * 0.5f, time);
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent e) {
            int code = e.getKeyCode();
            // the volume still answers while the cover waits, as it does over Aura's own screens
            if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_MUTE) return false;
            // Back lets it go; nothing else reaches Amazon's home underneath while Aura's comes up
            if (code == KeyEvent.KEYCODE_BACK && e.getAction() == KeyEvent.ACTION_UP) hide(true);
            return true;
        }
    }
}
