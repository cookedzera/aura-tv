package app.aura.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Your own picture for an app's tile, sent from the phone: cropped to the tile's 16:9 and kept small
 * (480 x 270, one JPEG per app) in Aura's private storage. It replaces the app's art in every look.
 */
final class Art {
    private static final int W = 480, H = 270;

    private Art() { }

    private static File file(Context c, String pkg) {
        File d = new File(c.getFilesDir(), "art");
        if (!d.isDirectory()) //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        return new File(d, pkg.replaceAll("[^A-Za-z0-9_.]", "_") + ".jpg");
    }

    /** Off the main thread: a picture as it arrived, cropped and kept. False when it isn't one Android can read. */
    static boolean save(Context c, String pkg, byte[] data) {
        if (data == null || data.length == 0) return false;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return false;
        // decoded no bigger than needed: a phone photo is 12 MP
        int sample = 1;
        while (o.outWidth / (sample * 2) >= W && o.outHeight / (sample * 2) >= H) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        Bitmap src;
        try {
            src = BitmapFactory.decodeByteArray(data, 0, data.length, d);
        } catch (OutOfMemoryError e) {
            return false;
        }
        if (src == null) return false;
        // the middle of it, at the tile's shape
        float k = Math.max(W / (float) src.getWidth(), H / (float) src.getHeight());
        int cw = Math.round(W / k), ch = Math.round(H / k);
        int x = (src.getWidth() - cw) / 2, y = (src.getHeight() - ch) / 2;
        Bitmap out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        new Canvas(out).drawBitmap(src, new Rect(x, y, x + cw, y + ch), new Rect(0, 0, W, H), new Paint(Paint.FILTER_BITMAP_FLAG));
        src.recycle();
        File f = file(c, pkg), part = new File(f.getPath() + ".part");
        try (FileOutputStream fo = new FileOutputStream(part)) {
            out.compress(Bitmap.CompressFormat.JPEG, 88, fo);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            return false;
        } finally {
            out.recycle();
        }
        return part.renameTo(f);
    }

    /** Off the main thread: the app's own picture, or null. */
    static Bitmap load(Context c, String pkg) {
        File f = file(c, pkg);
        return f.isFile() ? BitmapFactory.decodeFile(f.getPath()) : null;
    }

    static boolean has(Context c, String pkg) {
        return file(c, pkg).isFile();
    }

    /** Back to the app's own art. */
    static void remove(Context c, String pkg) {
        //noinspection ResultOfMethodCallIgnored
        file(c, pkg).delete();
    }
}
