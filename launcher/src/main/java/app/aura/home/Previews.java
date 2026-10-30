package app.aura.home;

import android.accessibilityservice.AccessibilityService;
import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorSpace;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Picture;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pictures of apps as you left them, for the switcher's cards, as on Apple TV. {@link HomeKeys}
 * takes one (Android 11+) in the moment Home is pressed inside an app; the GPU shrinks it straight
 * to 640×360, so the full frame is never copied, and it's kept as one small JPEG per app in Aura's
 * private storage, never anywhere else. Protected screens (most streaming apps' video) come back
 * black and aren't kept: that card shows what you were watching instead.
 *
 * Each app's picture is replaced by its next one, and deleted when the app is closed in the
 * switcher, drops out of the recent list (uninstalled apps do too), is two days old, or the switch
 * in Settings goes off.
 */
final class Previews {
    static final int W = 640, H = 360;
    private static final String DIR = "previews", KEY = "set_previews";
    private static final long MAX_AGE = 2 * 86_400_000L;
    /** One file at a time, in order: a capture, then a deletion, never interleaved. */
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    /** The newest capture, still in memory for a switcher opening over its app this very moment. */
    private static volatile Bitmap fresh;
    private static volatile String freshPkg;

    private Previews() { }

    /** Android 11 is where accessibility services may take screenshots. Shared by every profile. */
    static boolean on(Context c) {
        return Build.VERSION.SDK_INT >= 30 && Prefs.device(c).getBoolean(KEY, true);
    }

    static void set(Context c, boolean on) {
        Prefs.device(c).edit().putBoolean(KEY, on).apply();
        if (!on) clear(c);
    }

    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), DIR);
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    private static File file(Context c, String pkg) {
        return new File(dir(c), pkg + ".jpg");
    }

    /**
     * {@link HomeKeys}: the screen as {@code pkg} left it. Shrunk on the GPU (only the small result is
     * read back), checked, then written, all off the main thread. The buffer is always released.
     */
    @TargetApi(30)
    static void take(Context c, String pkg, AccessibilityService.ScreenshotResult shot) {
        final HardwareBuffer buffer = shot.getHardwareBuffer();
        final ColorSpace space = shot.getColorSpace();
        IO.execute(() -> {
            Bitmap small;
            try (HardwareBuffer b = buffer) {
                Bitmap full = Bitmap.wrapHardwareBuffer(b, space);
                if (full == null) return;
                // drawn into a picture and rendered at 640×360 by the GPU: 0.9 MB read back, not 3.7
                Picture p = new Picture();
                Canvas cv = p.beginRecording(W, H);
                cv.drawBitmap(full, null, new Rect(0, 0, W, H), new Paint(Paint.FILTER_BITMAP_FLAG));
                p.endRecording();
                small = Bitmap.createBitmap(p, W, H, Bitmap.Config.ARGB_8888);
                full.recycle();
            } catch (RuntimeException | OutOfMemoryError e) {
                android.util.Log.w("AuraKeys", "picture of " + pkg + " unreadable: " + e);
                return;
            }
            // protected: whatever was kept before (or the fallback) stays
            if (small == null || blank(small)) {
                android.util.Log.i("AuraKeys", "picture of " + pkg + " was black (protected): not kept");
                return;
            }
            // the app hadn't drawn yet (some, like YouTube, leave the last app on screen while they
            // start): a picture that's the previous app's screen again isn't this app's
            Bitmap prev = last;
            if (prev != null && !pkg.equals(lastPkg) && same(small, prev)) {
                android.util.Log.i("AuraKeys", "picture of " + pkg + " still showed " + lastPkg + ": not kept");
                return;
            }
            last = small;
            lastPkg = pkg;
            android.util.Log.i("AuraKeys", "picture of " + pkg + " kept");
            fresh = small;
            freshPkg = pkg;
            File f = file(c, pkg), part = new File(f.getPath() + ".part");
            try (FileOutputStream out = new FileOutputStream(part)) {
                small.compress(Bitmap.CompressFormat.JPEG, 82, out);
            } catch (IOException e) {
                part.delete();
                return;
            }
            if (!part.renameTo(f)) part.delete();
        });
    }

    /**
     * A protected video comes back pure black, with at most a few controls lit over it. Pure black
     * alone isn't enough to tell: apps with black pages (NetMirror's rows of posters: 62% pure black,
     * 29% lit) were thrown away as protected. So: mostly pure black, and almost nothing lit.
     * Measured on this TV: night scenes 31–44% black; protected captures 78–100% black, 0% lit.
     */
    static boolean blank(Bitmap b) {
        int dark = 0, lit = 0, n = 0;
        for (int y = 0; y < 18; y++) {
            for (int x = 0; x < 32; x++) {
                int px = b.getPixel((int) ((x + 0.5f) * b.getWidth() / 32f), (int) ((y + 0.5f) * b.getHeight() / 18f));
                int luma = (((px >> 16) & 0xFF) * 299 + ((px >> 8) & 0xFF) * 587 + (px & 0xFF) * 114) / 1000;
                if (luma <= 3) dark++;
                if (luma > 40) lit++;
                n++;
            }
        }
        return dark >= n * 0.6f && lit < n * 0.12f;
    }

    /** The last picture kept, and whose: a new app's first capture is checked against it. */
    private static volatile Bitmap last;
    private static volatile String lastPkg;

    /** Two captures of the same screen: on a 32x18 grid, colours differ by under 4% on average. */
    static boolean same(Bitmap a, Bitmap b) {
        long diff = 0;
        int n = 0;
        for (int y = 0; y < 18; y++) {
            for (int x = 0; x < 32; x++) {
                int pa = a.getPixel((int) ((x + 0.5f) * a.getWidth() / 32f), (int) ((y + 0.5f) * a.getHeight() / 18f));
                int pb = b.getPixel((int) ((x + 0.5f) * b.getWidth() / 32f), (int) ((y + 0.5f) * b.getHeight() / 18f));
                diff += Math.abs(((pa >> 16) & 0xFF) - ((pb >> 16) & 0xFF)) + Math.abs(((pa >> 8) & 0xFF) - ((pb >> 8) & 0xFF))
                        + Math.abs((pa & 0xFF) - (pb & 0xFF));
                n += 3;
            }
        }
        return diff < n * 255L * 4 / 100;
    }

    /** The capture just taken over {@code pkg}, if it's still the newest; for the switcher's fold. */
    static Bitmap fresh(String pkg) {
        return pkg != null && pkg.equals(freshPkg) ? fresh : null;
    }

    /** The in-memory copy has done its job (the switcher closed): let it go. */
    static void dropFresh() {
        fresh = null;
        freshPkg = null;
    }

    /** When {@code pkg}'s picture was taken, or 0: cards painted from an older one are made again. */
    static long stamp(Context c, String pkg) {
        Bitmap f = fresh(pkg);
        File file = file(c, pkg);
        return f != null && !file.exists() ? 1 : file.lastModified();
    }

    /** Off the main thread: {@code pkg}'s picture at a card's size (16-bit, ~130 KB), or null. */
    static Bitmap load(Context c, String pkg, int w, int h) {
        File f = file(c, pkg);
        if (!f.exists()) return null;
        if (System.currentTimeMillis() - f.lastModified() > MAX_AGE) {
            f.delete();
            return null;
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        try {
            Bitmap b = BitmapFactory.decodeFile(f.getPath(), o);
            if (b == null) return null;
            Bitmap s = Bitmap.createScaledBitmap(b, w, h, true);
            if (s != b) b.recycle();
            return s;
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    /** A card's picture from {@code shot}: the screen as you left it, filling the card. */
    static Tiles.Painter painter(Bitmap shot) {
        return (c, w, h) -> {
            float s = Math.max(w / (float) shot.getWidth(), h / (float) shot.getHeight());
            Matrix m = new Matrix();
            m.setScale(s, s);
            m.postTranslate((w - shot.getWidth() * s) / 2f, (h - shot.getHeight() * s) / 2f);
            c.drawBitmap(shot, m, new Paint(Paint.FILTER_BITMAP_FLAG));
        };
    }

    /** Closed in the switcher: its picture goes too. */
    static void forget(Context c, String pkg) {
        if (pkg.equals(freshPkg)) dropFresh();
        IO.execute(() -> file(c, pkg).delete());
    }

    /** Switched off: every picture goes at once. */
    static void clear(Context c) {
        dropFresh();
        IO.execute(() -> {
            File[] fs = dir(c).listFiles();
            if (fs != null) for (File f : fs) f.delete();
        });
    }

    /** Only {@code keep}'s pictures stay (the switcher's list); anything else, or too old, goes. */
    static void prune(Context c, Set<String> keep) {
        IO.execute(() -> {
            File[] fs = dir(c).listFiles();
            if (fs == null) return;
            long now = System.currentTimeMillis();
            for (File f : fs) {
                String pkg = f.getName().replace(".part", "").replace(".jpg", "");
                if (!keep.contains(pkg) || now - f.lastModified() > MAX_AGE || f.getName().endsWith(".part")) f.delete();
            }
        });
    }
}
