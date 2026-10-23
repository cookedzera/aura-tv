package app.aura.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LightingColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.media.ExifInterface;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Photographs and GIFs sent from a phone: sized for the TV, filed with a title, and shown ahead of
 * everything else in the rotation. A portrait shot isn't cropped to a sliver: it sits whole on a
 * blurred, enlarged copy of itself. GIFs are kept as they are and play on the wall.
 */
final class Mine {
    private static final String DIR = "mine", INDEX = "index.json";
    static final int KEEP = 60;
    static final long MAX_BYTES = 40L << 20;
    private static final int W = 1536, H = 864, TW = 320, TH = 180;
    private static final Object LOCK = new Object();
    private static final Random RND = new Random();

    private Mine() { }

    static final class Unsupported extends IOException {
        Unsupported(String why) {
            super(why);
        }
    }

    // ---- reading -----------------------------------------------------------------------------

    /** Changes whenever a photo is added or removed, so readers can tell when to look again. */
    static long stamp(Context c) {
        return new File(dir(c), INDEX).lastModified();
    }

    /** Newest first, skipping any whose file has gone. */
    static List<Backdrop.Wallpaper> walls(Context c) {
        List<Backdrop.Wallpaper> out = new ArrayList<>();
        JSONArray index = readIndex(c);
        for (int i = 0; i < index.length(); i++) {
            JSONObject o = index.optJSONObject(i);
            if (o != null && new File(o.optString("path")).isFile()) out.add(new Backdrop.Wallpaper(o));
        }
        return out;
    }

    static int count(Context c) {
        return readIndex(c).length();
    }

    /** What the phone's page lists: id, title and whether it moves. */
    static JSONArray list(Context c) {
        JSONArray index = readIndex(c), out = new JSONArray();
        for (int i = 0; i < index.length(); i++) {
            JSONObject o = index.optJSONObject(i);
            try {
                out.put(new JSONObject().put("id", o.optString("id")).put("title", o.optString("place")).put("gif", o.optBoolean("gif")));
            } catch (JSONException ignored) {
            }
        }
        return out;
    }

    static File thumb(Context c, String id) {
        return new File(dir(c), safe(id) + "_t.jpg");
    }

    /** Where an upload is written while it arrives. */
    static File part(Context c) {
        File d = dir(c);
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return new File(d, Long.toString(System.currentTimeMillis(), 36) + RND.nextInt(1000) + ".part");
    }

    // ---- adding (on the server's thread) -----------------------------------------------------

    /**
     * Files a received upload: a GIF as it is, anything else re-encoded for the TV. Returns its
     * index entry; throws {@link Unsupported} when it isn't a picture Android can read.
     */
    static JSONObject add(Context c, File part, String name, String title) throws IOException {
        try {
            String id = Long.toString(System.currentTimeMillis(), 36) + Integer.toString(RND.nextInt(1296), 36);
            boolean gif = isGif(part);
            Bitmap still = decode(part);
            if (still == null) throw new Unsupported("Not a photo the TV can read");
            File out = new File(dir(c), id + (gif ? ".gif" : ".jpg"));
            Bitmap shown = gif ? still : compose(rotate(still, orientation(part)));
            if (gif) {
                if (!part.renameTo(out)) throw new IOException("couldn't keep the GIF");
            } else {
                save(shown, out, 90);
            }
            save(cover(shown, TW, TH), thumb(c, id), 82);
            JSONObject e = new JSONObject();
            e.put("id", id);
            e.put("kind", "mine");
            e.put("gif", gif);
            e.put("file", out.getName());
            e.put("path", out.getAbsolutePath());
            e.put("day", Daily.today());
            e.put("added", System.currentTimeMillis());
            e.put("place", headline(title, name));
            e.put("region", (gif ? "Your GIF" : "Your photo") + " · "
                    + new SimpleDateFormat("d MMMM", Locale.getDefault()).format(new Date()));
            e.put("line", "");
            e.put("mood", "day");
            e.put("accent", String.format(Locale.ROOT, "#%06X",
                    AppRepo.colorOf(new BitmapDrawable(c.getResources(), cover(shown, 96, 54))) & 0xFFFFFF));
            synchronized (LOCK) {
                JSONArray index = readIndex(c), next = new JSONArray().put(e);
                for (int i = 0; i < index.length(); i++) {
                    if (next.length() < KEEP) next.put(index.optJSONObject(i));
                    else forget(c, index.optJSONObject(i));
                }
                write(c, next);
            }
            return e;
        } catch (JSONException e) {
            throw new IOException(e);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
        }
    }

    static boolean remove(Context c, String id) {
        synchronized (LOCK) {
            JSONArray index = readIndex(c), next = new JSONArray();
            boolean found = false;
            for (int i = 0; i < index.length(); i++) {
                JSONObject o = index.optJSONObject(i);
                if (o.optString("id").equals(id)) {
                    forget(c, o);
                    found = true;
                } else {
                    next.put(o);
                }
            }
            try {
                if (found) write(c, next);
            } catch (IOException e) {
                return false;
            }
            return found;
        }
    }

    static void clear(Context c) {
        synchronized (LOCK) {
            JSONArray index = readIndex(c);
            for (int i = 0; i < index.length(); i++) forget(c, index.optJSONObject(i));
            try {
                write(c, new JSONArray());
            } catch (IOException ignored) {
            }
        }
    }

    private static void forget(Context c, JSONObject o) {
        //noinspection ResultOfMethodCallIgnored
        new File(o.optString("path")).delete();
        //noinspection ResultOfMethodCallIgnored
        thumb(c, o.optString("id")).delete();
    }

    /** The title typed on the phone; else the file's name, unless that's a camera's name for it. */
    private static String headline(String title, String name) {
        String t = title == null ? "" : title.trim().replaceAll("\\s+", " ");
        if (t.isEmpty()) {
            t = Daily.place(name == null ? "" : name, "");
            if (t.matches("(?i).*\\b(IMG|DSC|DCIM|PXL|DJI|MVIMG|image|photo|screenshot|whatsapp|download|giphy|tenor)\\b.*")
                    || t.matches(".*\\d{4,}.*") || t.length() < 2) t = "";
        }
        if (t.length() > 40) t = t.substring(0, t.lastIndexOf(' ', 40) > 20 ? t.lastIndexOf(' ', 40) : 40).trim();
        return t.isEmpty() ? "From your phone" : t;
    }

    // ---- pictures ----------------------------------------------------------------------------

    private static boolean isGif(File f) throws IOException {
        byte[] head = new byte[4];
        try (InputStream in = new FileInputStream(f)) {
            return in.read(head) == 4 && head[0] == 'G' && head[1] == 'I' && head[2] == 'F' && head[3] == '8';
        }
    }

    /** Decoded no larger than it needs to be to fill the TV (a GIF gives its first frame). */
    private static Bitmap decode(File f) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        int lo = Math.min(o.outWidth, o.outHeight), hi = Math.max(o.outWidth, o.outHeight), sample = 1;
        while (hi / (sample * 2) >= W && lo / (sample * 2) >= H) sample *= 2;
        o.inJustDecodeBounds = false;
        o.inSampleSize = sample;
        try {
            return BitmapFactory.decodeFile(f.getPath(), o);
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    private static int orientation(File f) {
        try {
            return new ExifInterface(f.getPath()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (IOException | RuntimeException e) {
            return ExifInterface.ORIENTATION_NORMAL;
        }
    }

    private static Bitmap rotate(Bitmap b, int orientation) {
        int deg = orientation == ExifInterface.ORIENTATION_ROTATE_90 ? 90
                : orientation == ExifInterface.ORIENTATION_ROTATE_180 ? 180
                : orientation == ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
        if (deg == 0) return b;
        Matrix m = new Matrix();
        m.setRotate(deg);
        return Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
    }

    /**
     * Wide shots fill the screen. Anything taller sits whole, with rounded corners and a soft
     * shadow, on a blurred and dimmed copy of itself, the way photos are framed on Apple TV.
     */
    private static Bitmap compose(Bitmap b) {
        float aspect = b.getWidth() / (float) b.getHeight();
        if (aspect >= 1.45f) return cover(b, W, H);
        Bitmap out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        Bitmap bg = Backdrop.blur(cover(b, 384, 216));
        p.setColorFilter(new LightingColorFilter(0x7A7A7A, 0));
        Matrix m = new Matrix();
        m.setScale(W / (float) bg.getWidth(), H / (float) bg.getHeight());
        cv.drawBitmap(bg, m, p);
        p.setColorFilter(null);

        // small enough that the backdrop's slow zoom never crops it
        float fh = H * 0.8f, fw = fh * aspect;
        if (fw > W * 0.86f) {
            fw = W * 0.86f;
            fh = fw / aspect;
        }
        RectF r = new RectF((W - fw) / 2f, (H - fh) / 2f, (W + fw) / 2f, (H + fh) / 2f);
        float radius = 22f;
        Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        shadow.setColor(0x73000000);
        shadow.setMaskFilter(new BlurMaskFilter(36f, BlurMaskFilter.Blur.NORMAL));
        cv.drawRoundRect(r.left, r.top + 14f, r.right, r.bottom + 14f, radius, radius, shadow);
        BitmapShader sh = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        Matrix sm = new Matrix();
        sm.setScale(fw / b.getWidth(), fh / b.getHeight());
        sm.postTranslate(r.left, r.top);
        sh.setLocalMatrix(sm);
        p.setShader(sh);
        cv.drawRoundRect(r, radius, radius, p);
        return out;
    }

    private static Bitmap cover(Bitmap b, int w, int h) {
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        float s = Math.max(w / (float) b.getWidth(), h / (float) b.getHeight());
        Matrix m = new Matrix();
        m.setScale(s, s);
        m.postTranslate((w - b.getWidth() * s) / 2f, (h - b.getHeight() * s) / 2f);
        new Canvas(out).drawBitmap(b, m, new Paint(Paint.FILTER_BITMAP_FLAG));
        return out;
    }

    private static void save(Bitmap b, File f, int quality) throws IOException {
        try (FileOutputStream fo = new FileOutputStream(f)) {
            if (!b.compress(Bitmap.CompressFormat.JPEG, quality, fo)) throw new IOException("couldn't save");
        }
    }

    // ---- index ---------------------------------------------------------------------------------

    private static String safe(String id) {
        return id.replaceAll("[^a-z0-9]", "");
    }

    private static File dir(Context c) {
        return new File(c.getFilesDir(), DIR);
    }

    private static JSONArray readIndex(Context c) {
        File f = new File(dir(c), INDEX);
        if (!f.isFile()) return new JSONArray();
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) bo.write(buf, 0, n);
            return new JSONArray(new String(bo.toByteArray(), StandardCharsets.UTF_8));
        } catch (IOException | JSONException e) {
            return new JSONArray();
        }
    }

    private static void write(Context c, JSONArray index) throws IOException {
        File d = dir(c);
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        File f = new File(d, INDEX), tmp = new File(d, INDEX + ".tmp");
        try (FileOutputStream fo = new FileOutputStream(tmp)) {
            fo.write(index.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(f)) throw new IOException("couldn't write the index");
    }
}
