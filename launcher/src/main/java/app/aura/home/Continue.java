package app.aura.home;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.tv.TvContract;
import android.net.Uri;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import android.text.TextUtils;

/**
 * Continue Watching from Android TV's own Watch Next list, where apps put what you're partway
 * through. Read on demand (no service); needs the TV listings permission, granted at setup.
 */
final class Continue {
    static final class Item {
        String title, sub, pkg;
        Bitmap art;
        Intent intent;
        float progress;
    }

    private static final String[] COLUMNS = {
            TvContract.WatchNextPrograms.COLUMN_TITLE,
            TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE,
            TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER,
            TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER,
            TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI,
            TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_URI,
            TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_INTENT_URI,
            TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME,
    };

    private Continue() { }

    /**
     * Up to {@code max} unfinished items, most recently watched first, artwork loaded (wide
     * thumbnails preferred, for a cinematic card). Empty when none or no permission. Off the main thread.
     */
    static List<Item> all(Context c, int max) {
        // pressing Home again and again shouldn't ask the streaming apps each time: a minute's cache
        long now = android.os.SystemClock.elapsedRealtime();
        synchronized (Continue.class) {
            if (cached != null && cachedMax == max && now - cachedAt < 60_000) return cached;
        }
        List<Item> out = read(c, max);
        synchronized (Continue.class) {
            cached = out;
            cachedMax = max;
            cachedAt = now;
        }
        return out;
    }

    private static List<Item> cached;
    private static int cachedMax;
    private static long cachedAt;

    private static List<Item> read(Context c, int max) {
        List<Item> out = new ArrayList<>();
        // the Watch Next list arrived with Android 8
        if (android.os.Build.VERSION.SDK_INT < 26 || !Compat.granted(c, "android.permission.READ_TV_LISTINGS")) return out;
        try (Cursor cur = c.getContentResolver().query(TvContract.WatchNextPrograms.CONTENT_URI, COLUMNS, null, null,
                TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS + " DESC")) {
            if (cur == null) return out;
            while (cur.moveToNext() && out.size() < max) {
                String intentUri = cur.getString(8);
                if (intentUri == null) continue;
                Item it = new Item();
                it.title = cur.getString(0);
                it.pkg = cur.getString(9);
                it.intent = Intent.parseUri(intentUri, Intent.URI_INTENT_SCHEME);
                it.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                long pos = cur.getLong(6), dur = cur.getLong(7);
                it.progress = dur > 0 ? Math.min(1f, pos / (float) dur) : 0f;
                List<String> parts = new ArrayList<>();
                String season = cur.getString(2), episode = cur.getString(3);
                if (season != null && !season.isEmpty()) parts.add("S" + season);
                if (episode != null && !episode.isEmpty()) parts.add("E" + episode);
                if (dur > 0 && pos > 0) {
                    long left = Math.max(1, (dur - pos) / 60_000);
                    parts.add(left < 60 ? left + " min left" : String.format(Locale.ROOT, "%d h %d min left", left / 60, left % 60));
                } else if (cur.getString(1) != null && !cur.getString(1).isEmpty()) {
                    parts.add(cur.getString(1));
                }
                it.sub = TextUtils.join("  ·  ", parts);
                String art = cur.getString(5) != null ? cur.getString(5) : cur.getString(4);
                it.art = art == null ? null : load(c, art);
                out.add(it);
            }
        } catch (Exception ignored) {
            // a provider that misbehaves never takes For You down with it
        }
        return out;
    }

    /**
     * What {@code pkg} offers on its own Android TV home-screen rows (preview channels), newest
     * first, for Top Shelf. Some TVs only let their own launcher read these: then this is empty.
     */
    static List<Item> previews(Context c, String pkg, int max) {
        List<Item> out = new ArrayList<>();
        if (android.os.Build.VERSION.SDK_INT < 26 || !Compat.granted(c, "android.permission.READ_TV_LISTINGS")) return out;
        String[] cols = {TvContract.PreviewPrograms.COLUMN_TITLE, TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION,
                TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI, TvContract.PreviewPrograms.COLUMN_THUMBNAIL_URI,
                TvContract.PreviewPrograms.COLUMN_INTENT_URI};
        try (Cursor cur = c.getContentResolver().query(TvContract.PreviewPrograms.CONTENT_URI, cols,
                TvContract.BaseTvColumns.COLUMN_PACKAGE_NAME + "=?", new String[]{pkg}, null)) {
            if (cur == null) return out;
            while (cur.moveToNext() && out.size() < max) {
                String intentUri = cur.getString(4), title = cur.getString(0);
                if (intentUri == null || title == null) continue;
                Item it = new Item();
                it.title = title;
                it.pkg = pkg;
                it.sub = cur.getString(1);
                it.intent = Intent.parseUri(intentUri, Intent.URI_INTENT_SCHEME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                String art = cur.getString(3) != null ? cur.getString(3) : cur.getString(2);
                it.art = art == null ? null : load(c, art);
                if (it.art != null) out.add(it);
            }
        } catch (Exception ignored) {
            // not readable here, or a misbehaving provider: Top Shelf falls back to Watch Next
        }
        return out;
    }

    static Bitmap load(Context c, String uri) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = 2;
        try {
            if (uri.startsWith("http")) {
                HttpURLConnection con = (HttpURLConnection) new URL(uri).openConnection();
                con.setConnectTimeout(8000);
                con.setReadTimeout(10_000);
                try (InputStream in = con.getInputStream()) {
                    return BitmapFactory.decodeStream(in, null, o);
                } finally {
                    con.disconnect();
                }
            }
            try (InputStream in = c.getContentResolver().openInputStream(Uri.parse(uri))) {
                return BitmapFactory.decodeStream(in, null, o);
            }
        } catch (Exception e) {
            return null;
        }
    }
}
