package app.aura.home;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.tv.TvContract;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import android.text.TextUtils;

/**
 * Continue Watching from Android TV's own Watch Next list, where apps put what you're partway
 * through. Read on demand (no service); needs the TV listings permission, granted at setup.
 */
final class Continue {
    static final class Item {
        String title, sub, kicker, pkg, desc;
        Bitmap art;
        Intent intent;
        float progress;
        long leftMin;
        boolean nowPlaying, posterCard;

        /** The same item (and the same picture), to change without changing anyone else's. */
        Item copy() {
            Item c = new Item();
            c.title = title;
            c.sub = sub;
            c.kicker = kicker;
            c.pkg = pkg;
            c.desc = desc;
            c.art = art;
            c.intent = intent;
            c.progress = progress;
            c.leftMin = leftMin;
            c.nowPlaying = nowPlaying;
            c.posterCard = posterCard;
            return c;
        }
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
            TvContract.WatchNextPrograms.COLUMN_SHORT_DESCRIPTION,
    };

    private Continue() { }

    /**
     * Up to {@code max} unfinished items, most recently watched first, artwork loaded (wide
     * thumbnails preferred, for a cinematic card). Empty when none or no permission. Off the main thread.
     */
    static List<Item> all(Context c, int max) {
        // pressing Home again and again shouldn't ask the streaming apps each time: a minute's cache
        // one list for everyone asking (the stack wants 4, Top Shelf 8): asking for fewer never
        // throws the minute's cache away, and the artwork with it
        long now = android.os.SystemClock.elapsedRealtime();
        List<Item> all;
        // one reading at a time: the stack and Top Shelf both ask the moment home comes back, and the
        // second waits for the first one's list instead of asking the TV's listings all over again
        synchronized (READING) {
            all = cached != null && max <= READ && now - cachedAt < 60_000 ? cached : null;
            if (all == null) {
                all = read(c, Math.max(READ, max));
                cached = all;
                cachedAt = now;
            }
        }
        // copies: Top Shelf labels its own, and the stack mustn't see a card change under it
        List<Item> out = new ArrayList<>();
        for (int i = 0; i < all.size() && i < max; i++) out.add(all.get(i).copy());
        return out;
    }

    /** As many as anything shows at once. */
    private static final int READ = 8;
    private static final Object READING = new Object();
    private static List<Item> cached;
    private static long cachedAt;

    private static List<Item> read(Context c, int max) {
        List<Item> out = new ArrayList<>();
        List<String> pictures = new ArrayList<>();
        java.util.Set<String> titles = new java.util.HashSet<>();
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
                // an app listing the same show twice (to continue, and its next episode): the newer one
                if (!titles.add(it.pkg + "/" + key(it.title))) continue;
                try {
                    it.intent = Intent.parseUri(intentUri, Intent.URI_INTENT_SCHEME);
                } catch (java.net.URISyntaxException e) {
                    // one app's malformed row doesn't cost every other app's
                    continue;
                }
                it.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                long pos = cur.getLong(6), dur = cur.getLong(7);
                it.progress = dur > 0 ? Math.min(1f, pos / (float) dur) : 0f;
                String shortDesc = cur.getString(10);
                if (shortDesc != null && !shortDesc.trim().isEmpty() && !shortDesc.trim().equalsIgnoreCase(it.title)) {
                    it.desc = shortDesc.trim();
                }
                List<String> parts = new ArrayList<>();
                String season = cur.getString(2), episode = cur.getString(3);
                if (season != null && !season.isEmpty()) parts.add("S" + season);
                if (episode != null && !episode.isEmpty()) parts.add("E" + episode);
                if (dur > 0 && pos > 0) {
                    long left = Math.max(1, (dur - pos) / 60_000);
                    it.leftMin = left;
                    parts.add(left < 60 ? Words.get(R.string.min_left, left) : Words.get(R.string.h_min_left, left / 60, left % 60));
                } else if (cur.getString(1) != null && !cur.getString(1).isEmpty()) {
                    parts.add(cur.getString(1));
                }
                it.sub = TextUtils.join("  ·  ", parts);
                out.add(it);
                pictures.add(cur.getString(5) != null ? cur.getString(5) : cur.getString(4));
            }
        } catch (Exception ignored) {
            // a provider that misbehaves never takes For You down with it
        }
        loadAll(c, out, pictures);
        return out;
    }

    /**
     * Each item's picture, fetched side by side rather than one after another: eight slow
     * servers cost the slowest one's wait, not the sum of them all.
     */
    private static void loadAll(Context c, List<Item> items, List<String> uris) {
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            final Item it = items.get(i);
            final String uri = uris.get(i);
            if (uri == null) continue;
            Thread t = new SafeThread(() -> {
                // behind the screen: these used to compete with every frame, worst just after a restart
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                it.art = load(c, uri);
            }, "continue-art");
            t.start();
            threads.add(t);
        }
        long until = android.os.SystemClock.elapsedRealtime() + 12_000;
        for (Thread t : threads) {
            try {
                t.join(Math.max(1, until - android.os.SystemClock.elapsedRealtime()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * What {@code pkg} offers on its own Android TV home-screen rows (preview channels), newest
     * first, for Top Shelf. Some TVs only let their own launcher read these: then this is empty.
     * YouTube Music and TizenTube share YouTube's preview rows when they publish none of their own.
     */
    private static final Object PREVIEWS_LOCK = new Object();
    private static final Map<String, List<Item>> previewsCached = new HashMap<>();
    private static final Map<String, Long> previewsAt = new HashMap<>();

    static List<Item> previews(Context c, String pkg, int max) {
        List<Item> out = new ArrayList<>();
        if (pkg == null || max <= 0 || android.os.Build.VERSION.SDK_INT < 26 || !Compat.granted(c, "android.permission.READ_TV_LISTINGS")) return out;
        long now = android.os.SystemClock.elapsedRealtime();
        synchronized (PREVIEWS_LOCK) {
            List<Item> hit = previewsCached.get(pkg);
            Long at = previewsAt.get(pkg);
            if (hit != null && at != null && now - at < 60_000 && hit.size() >= max) {
                for (int i = 0; i < hit.size() && i < max; i++) out.add(hit.get(i).copy());
                return out;
            }
        }
        boolean ytMusic = "com.google.android.youtube.tvmusic".equals(pkg);
        boolean ytClient = "io.gh.reisxd.tizentube.cobalt".equals(pkg)
                || "com.liskovsoft.smarttubetv".equals(pkg)
                || "com.liskovsoft.smarttubetv.beta".equals(pkg);
        java.util.Set<Long> musicChannels = ytMusic ? youtubeMusicChannels(c) : Collections.emptySet();
        String[] cols = {TvContract.PreviewPrograms.COLUMN_TITLE, TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION,
                TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI, TvContract.PreviewPrograms.COLUMN_THUMBNAIL_URI,
                TvContract.PreviewPrograms.COLUMN_INTENT_URI, TvContract.BaseTvColumns.COLUMN_PACKAGE_NAME,
                TvContract.PreviewPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS, TvContract.PreviewPrograms.COLUMN_DURATION_MILLIS,
                TvContract.PreviewPrograms.COLUMN_SEASON_DISPLAY_NUMBER, TvContract.PreviewPrograms.COLUMN_EPISODE_DISPLAY_NUMBER,
                TvContract.PreviewPrograms.COLUMN_CHANNEL_ID, TvContract.PreviewPrograms.COLUMN_AUTHOR};
        List<Item> raw = new ArrayList<>(), fallback = new ArrayList<>();
        List<String> uris = new ArrayList<>(), fallbackUris = new ArrayList<>();
        // no selection: Android's TV provider refuses one from any app but its own system ones, and
        // already narrows the rows to those an app with the listings permission may read
        try (Cursor cur = c.getContentResolver().query(TvContract.PreviewPrograms.CONTENT_URI, cols, null, null, null)) {
            if (cur == null) return out;
            while (cur.moveToNext()) {
                String intentUri = cur.getString(4), title = cur.getString(0), rowPkg = cur.getString(5);
                String art = cur.getString(3) != null ? cur.getString(3) : cur.getString(2);
                if (intentUri == null || title == null || art == null || rowPkg == null) continue;
                boolean direct = pkg.equals(rowPkg);
                boolean fromYt = (ytMusic || ytClient) && "com.google.android.youtube.tv".equals(rowPkg);
                if (!direct && !fromYt) continue;
                long channelId = cur.getLong(10);
                String desc = cur.getString(1), author = cur.getString(11);
                boolean musicRow = direct || (ytMusic && (musicChannels.contains(channelId) || looksLikeMusic(title, desc, author)));
                Item it = new Item();
                it.title = title;
                it.pkg = pkg;
                if (desc != null && !desc.trim().isEmpty() && !desc.trim().equalsIgnoreCase(title)) it.desc = desc.trim();
                try {
                    it.intent = Intent.parseUri(intentUri, Intent.URI_INTENT_SCHEME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                } catch (java.net.URISyntaxException e) {
                    // one malformed row doesn't cost the app's others (it used to end the whole read)
                    continue;
                }
                if (ytClient && !direct) {
                    // open YouTube's preview video inside TizenTube / SmartTube
                    it.intent.setComponent(null);
                    it.intent.setPackage(pkg);
                }
                long pos = cur.getLong(6), dur = cur.getLong(7);
                it.progress = dur > 0 && pos > 0 ? Math.min(1f, pos / (float) dur) : 0f;
                List<String> parts = new ArrayList<>();
                String season = cur.getString(8), episode = cur.getString(9);
                if (season != null && !season.isEmpty()) parts.add("S" + season);
                if (episode != null && !episode.isEmpty()) parts.add("E" + episode);
                if (it.progress > 0f) {
                    long left = Math.max(1, (dur - pos) / 60_000);
                    it.leftMin = left;
                    parts.add(left < 60 ? Words.get(R.string.min_left, left) : Words.get(R.string.h_min_left, left / 60, left % 60));
                } else if (desc != null && !desc.isEmpty()) {
                    parts.add(desc);
                } else if (author != null && !author.isEmpty()) {
                    parts.add(author);
                }
                it.sub = TextUtils.join("  ·  ", parts);
                if (direct || musicRow) {
                    raw.add(it);
                    uris.add(art);
                } else {
                    fallback.add(it);
                    fallbackUris.add(art);
                }
            }
        } catch (Exception ignored) {
            // not readable here, or a misbehaving provider: Top Shelf falls back to Watch Next
        }
        if (raw.size() < max && !fallback.isEmpty()) {
            raw.addAll(fallback);
            uris.addAll(fallbackUris);
        }
        // anything partway through in this app's channel rises to the front so you can resume it at once
        List<Item> candidates = new ArrayList<>();
        List<String> candidateUris = new ArrayList<>();
        int want = Math.max(READ, max) + 2;
        for (int pass = 0; pass < 2 && candidates.size() < want; pass++) {
            for (int i = 0; i < raw.size() && candidates.size() < want; i++) {
                Item it = raw.get(i);
                if ((pass == 0) != (it.progress > 0f)) continue;
                candidates.add(it);
                candidateUris.add(uris.get(i));
            }
        }
        loadAll(c, candidates, candidateUris);
        List<Item> kept = new ArrayList<>();
        for (Item it : candidates) if (it.art != null && kept.size() < Math.max(READ, max)) kept.add(it);
        synchronized (PREVIEWS_LOCK) {
            previewsCached.put(pkg, kept);
            previewsAt.put(pkg, now);
        }
        for (int i = 0; i < kept.size() && i < max; i++) out.add(kept.get(i).copy());
        return out;
    }

    /**
     * Asks apps to put their rows on the TV's home screen ({@code only}, or every app that listens).
     * Google TV's own home sends this when an app arrives; switched off, nothing does, and apps that
     * wait for it never publish rows Top Shelf can read. (SonyLIV ignores it: it only feeds Google's
     * Engage.) It isn't a protected broadcast: any app may send it, one app at a time by name. Off
     * the main thread.
     */
    static void askToPublish(Context c, String only) {
        if (android.os.Build.VERSION.SDK_INT < 26) return;
        Intent ask = new Intent(TvContract.ACTION_INITIALIZE_PROGRAMS);
        if (only != null) ask.setPackage(only);
        List<android.content.pm.ResolveInfo> apps;
        try {
            apps = c.getPackageManager().queryBroadcastReceivers(ask, 0);
        } catch (RuntimeException e) {
            return;
        }
        for (android.content.pm.ResolveInfo r : apps) {
            if (r.activityInfo == null || c.getPackageName().equals(r.activityInfo.packageName)) continue;
            try {
                c.sendBroadcast(new Intent(TvContract.ACTION_INITIALIZE_PROGRAMS)
                        .setClassName(r.activityInfo.packageName, r.activityInfo.name));
            } catch (RuntimeException e) {
                // a TV that keeps it for the system after all: noted once, nobody else asked
                Trouble.caught("asking apps for their rows", e);
                return;
            }
        }
    }

    /** Channel IDs published by YouTube whose title is Music (e.g. "Music", "YouTube Music"). */
    private static java.util.Set<Long> youtubeMusicChannels(Context c) {
        java.util.Set<Long> ids = new java.util.HashSet<>();
        String[] cols = {TvContract.Channels._ID, TvContract.Channels.COLUMN_PACKAGE_NAME, TvContract.Channels.COLUMN_DISPLAY_NAME};
        try (Cursor cur = c.getContentResolver().query(TvContract.Channels.CONTENT_URI, cols, null, null, null)) {
            if (cur == null) return ids;
            while (cur.moveToNext()) {
                String pkg = cur.getString(1), name = cur.getString(2);
                if ("com.google.android.youtube.tv".equals(pkg) && name != null
                        && name.toLowerCase(Locale.ROOT).contains("music")) {
                    ids.add(cur.getLong(0));
                }
            }
        } catch (Exception ignored) {
        }
        return ids;
    }

    private static boolean looksLikeMusic(String title, String desc, String author) {
        String s = ((title != null ? title : "") + " " + (desc != null ? desc : "") + " " + (author != null ? author : ""))
                .toLowerCase(Locale.ROOT);
        return s.contains("official music video") || s.contains("official video") || s.contains("official audio")
                || s.contains("lyric") || s.contains("vevo") || s.contains(" - topic") || s.contains("album")
                || s.contains("remix") || s.contains("feat.") || s.contains("ft.");
    }

    /**
     * Your own picks from apps that keep them on their Android TV channel rather than in Watch Next:
     * Netflix, for one, puts what you're partway through and what it picked for your profile there.
     * For each of {@code apps} (most used first): something partway through, else the top of its row;
     * one each, up to {@code max}, leaving out titles in {@code skip} (normalised, see {@link #key}).
     * Pictures are fetched only for those kept. Off the main thread; kept a minute, like Watch Next.
     */
    static List<Item> channels(Context c, List<String> apps, Set<String> skip, int max) {
        if (apps == null || apps.isEmpty() || max <= 0) return Collections.emptyList();
        if (skip == null) skip = Collections.emptySet();
        long now = android.os.SystemClock.elapsedRealtime();
        int id = (apps.hashCode() * 31 + skip.hashCode()) * 31 + max;
        List<Item> all;
        synchronized (CHANNELS_LOCK) {
            all = channelsCached != null && channelsId == id && now - channelsAt < 60_000 ? channelsCached : null;
            if (all == null) {
                all = readChannels(c, apps, skip, max);
                channelsCached = all;
                channelsId = id;
                channelsAt = now;
            }
        }
        List<Item> out = new ArrayList<>(all.size());
        for (int i = 0; i < all.size() && i < max; i++) out.add(all.get(i).copy());
        return out;
    }

    private static final Object CHANNELS_LOCK = new Object();
    private static List<Item> channelsCached;
    private static int channelsId;
    private static long channelsAt;

    private static List<Item> readChannels(Context c, List<String> apps, Set<String> skip, int max) {
        List<Item> out = new ArrayList<>();
        if (max <= 0 || apps.isEmpty() || android.os.Build.VERSION.SDK_INT < 26
                || !Compat.granted(c, "android.permission.READ_TV_LISTINGS")) return out;
        String[] cols = {TvContract.PreviewPrograms.COLUMN_TITLE, TvContract.PreviewPrograms.COLUMN_SEASON_DISPLAY_NUMBER,
                TvContract.PreviewPrograms.COLUMN_EPISODE_DISPLAY_NUMBER, TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION,
                TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI, TvContract.PreviewPrograms.COLUMN_THUMBNAIL_URI,
                TvContract.PreviewPrograms.COLUMN_INTENT_URI, TvContract.BaseTvColumns.COLUMN_PACKAGE_NAME,
                TvContract.PreviewPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS, TvContract.PreviewPrograms.COLUMN_DURATION_MILLIS,
                TvContract.PreviewPrograms.COLUMN_CHANNEL_ID};
        // which of each app's rows are yours (continue watching, your list) and which are its shop window
        Map<Long, Integer> rowRank = rowRanks(c);
        Map<String, Integer> bestRank = new HashMap<>();
        Map<String, Item> best = new HashMap<>();
        Map<String, String> pictures = new HashMap<>();
        // no selection (see previews); the rows come in each app's own order, its first pick first
        try (Cursor cur = c.getContentResolver().query(TvContract.PreviewPrograms.CONTENT_URI, cols, null, null,
                TvContract.PreviewPrograms.COLUMN_WEIGHT + " DESC")) {
            if (cur == null) return out;
            while (cur.moveToNext()) {
                String title = cur.getString(0), intentUri = cur.getString(6), pkg = cur.getString(7);
                String picture = cur.getString(5) != null ? cur.getString(5) : cur.getString(4);
                if (title == null || intentUri == null || pkg == null || picture == null || !apps.contains(pkg)
                        || skip.contains(key(title))) continue;
                long pos = cur.getLong(8), dur = cur.getLong(9);
                Integer r = rowRank.get(cur.getLong(10));
                int rank = r != null ? r : 0;
                Item had = best.get(pkg);
                // an app's first pick from its most personal row; something partway through beats all
                if (had != null) {
                    boolean partway = pos > 0 && dur > 0;
                    if (had.progress > 0f || !partway && rank <= bestRank.get(pkg)) continue;
                }
                Item it = new Item();
                try {
                    it.intent = Intent.parseUri(intentUri, Intent.URI_INTENT_SCHEME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                } catch (java.net.URISyntaxException e) {
                    // one malformed row doesn't cost the rest
                    continue;
                }
                it.title = title;
                it.pkg = pkg;
                it.progress = dur > 0 && pos > 0 ? Math.min(1f, pos / (float) dur) : 0f;
                String shortDesc = cur.getString(3);
                if (shortDesc != null && !shortDesc.trim().isEmpty() && !shortDesc.trim().equalsIgnoreCase(title)) {
                    it.desc = shortDesc.trim();
                }
                List<String> parts = new ArrayList<>();
                String season = cur.getString(1), episode = cur.getString(2);
                if (season != null && !season.isEmpty()) parts.add("S" + season);
                if (episode != null && !episode.isEmpty()) parts.add("E" + episode);
                if (it.progress > 0f) {
                    long left = Math.max(1, (dur - pos) / 60_000);
                    it.leftMin = left;
                    parts.add(left < 60 ? Words.get(R.string.min_left, left) : Words.get(R.string.h_min_left, left / 60, left % 60));
                } else if (parts.isEmpty() && shortDesc != null) {
                    parts.add(shortDesc);
                }
                it.sub = TextUtils.join("  ·  ", parts);
                // what the row is called says what it is, even when the app leaves out the progress
                if (it.progress <= 0f) it.kicker = rank == 3 ? Words.get(R.string.continue_watching_caps) : rank == 2 ? Words.get(R.string.on_your_list) : null;
                best.put(pkg, it);
                bestRank.put(pkg, rank);
                pictures.put(pkg, picture);
            }
        } catch (Exception ignored) {
            // not readable on this TV, or a misbehaving provider: the stack goes on without them
        }
        // something you're partway through first, then by how much you use the app (a stable sort)
        List<String> order = new ArrayList<>();
        for (String pkg : apps) if (best.containsKey(pkg)) order.add(pkg);
        Collections.sort(order, (a, b) -> Boolean.compare(best.get(b).progress > 0f, best.get(a).progress > 0f));
        // a few spare in case a picture won't load, all fetched at once
        List<Item> tried = new ArrayList<>();
        List<String> uris = new ArrayList<>();
        for (int i = 0; i < order.size() && i < max + 2; i++) {
            tried.add(best.get(order.get(i)));
            uris.add(pictures.get(order.get(i)));
        }
        loadAll(c, tried, uris);
        for (Item it : tried) if (it.art != null && out.size() < max) out.add(it);
        return out;
    }

    /**
     * How personal each app row is, by its name: 3 for what you're partway through ("Continue
     * watching", "Resume"), 2 for your own list ("My List", "Watchlist", "Subscriptions"), 1 for
     * picks made for you ("Top picks for you", "Recommended"), 0 for an app's shop window
     * ("Trending now", "New releases"). The rows of every app that lets them be searched can be
     * read, with their names; an app's first row used to win, whatever it was.
     */
    private static Map<Long, Integer> rowRanks(Context c) {
        Map<Long, Integer> out = new HashMap<>();
        String[] cols = {TvContract.Channels._ID, TvContract.Channels.COLUMN_DISPLAY_NAME};
        try (Cursor cur = c.getContentResolver().query(TvContract.Channels.CONTENT_URI, cols, null, null, null)) {
            if (cur == null) return out;
            while (cur.moveToNext()) {
                String name = cur.getString(1);
                if (name == null) continue;
                out.put(cur.getLong(0), rank(name));
            }
        } catch (Exception ignored) {
            // no names here: every row counts the same, as before
        }
        return out;
    }

    static int rank(String rowName) {
        String n = rowName.toLowerCase(Locale.ROOT);
        if (n.contains("continue") || n.contains("resume") || n.contains("keep watching") || n.contains("watch next")
                || n.contains("in progress")) return 3;
        if (n.contains("my list") || n.contains("watchlist") || n.contains("watch list") || n.contains("subscription")
                || n.contains("saved") || n.contains("favourite") || n.contains("favorite") || n.contains("library")) return 2;
        if (n.contains("for you") || n.contains("top picks") || n.contains("recommended") || n.contains("because you")
                || n.contains("picked for") || n.contains("your ")) return 1;
        return 0;
    }

    /** A title as compared across sources: lower case, letters and digits only. */
    static String key(String title) {
        return title == null ? "" : title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /**
     * Pictures already fetched from the web, by address, most recently used last. Watch Next and
     * the apps' rows are read again a minute later (or on the next press of Home) and name the same
     * pictures: they used to be downloaded and decoded again every time. Kept within a share of
     * what Android lets Aura use, and let go when memory runs short. Only web addresses: an app's
     * own content address can show a different picture tomorrow (a music app's "now playing").
     * Posters and show art saved on this TV share it, kept by their file and its date ({@link #picture}).
     */
    private static final java.util.LinkedHashMap<String, Bitmap> ART = new java.util.LinkedHashMap<>(32, 0.75f, true);
    private static long artBytes, artBudget;

    private static Bitmap kept(String uri) {
        synchronized (ART) {
            return ART.get(uri);
        }
    }

    private static void keep(String uri, Bitmap b) {
        synchronized (ART) {
            if (artBudget == 0) {
                // a tenth of the heap Android gives Aura
                int mb = (int) (Runtime.getRuntime().maxMemory() >> 20);
                artBudget = Math.max(6, Math.min(24, mb / 10)) * 1024L * 1024L;
            }
            Bitmap was = ART.put(uri, b);
            if (was != null) artBytes -= was.getByteCount();
            artBytes += b.getByteCount();
            java.util.Iterator<Map.Entry<String, Bitmap>> it = ART.entrySet().iterator();
            while (artBytes > artBudget && it.hasNext()) {
                Map.Entry<String, Bitmap> e = it.next();
                if (e.getKey().equals(uri)) continue;
                artBytes -= e.getValue().getByteCount();
                it.remove();
            }
        }
    }

    /**
     * A picture saved on this TV (a poster, a show's art), halved while it stays at least
     * {@code minWidth} wide, in 16-bit colour. Top Shelf, the stack and Control Center ask for the
     * same few again and again: each is read once, and again only when its file is written again.
     */
    static Bitmap picture(String path, int minWidth) {
        if (path == null || path.isEmpty()) return null;
        String key = "file:" + path + "@" + new java.io.File(path).lastModified();
        Bitmap b = kept(key);
        if (b != null) return b;
        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeFile(path, o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        o.inJustDecodeBounds = false;
        o.inSampleSize = 1;
        while (o.outWidth / (o.inSampleSize * 2) >= minWidth) o.inSampleSize *= 2;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        try {
            b = android.graphics.BitmapFactory.decodeFile(path, o);
        } catch (OutOfMemoryError e) {
            return null;
        }
        if (b != null) keep(key, b);
        return b;
    }

    /** Short on memory: the kept pictures go (cards on show keep their own). */
    static void trim() {
        synchronized (ART) {
            ART.clear();
            artBytes = 0;
        }
    }

    /**
     * A picture, sharp enough for the widest card (half the screen across) and no larger: halved
     * only while it stays at least that wide, so a small thumbnail is never shrunk further.
     */
    static Bitmap load(Context c, String uri) {
        boolean web = uri.startsWith("http");
        Bitmap had = web ? kept(uri) : null;
        if (had != null) return had;
        Bitmap b = fetch(c, uri);
        if (b != null && web) keep(uri, b);
        return b;
    }

    private static Bitmap fetch(Context c, String uri) {
        try {
            byte[] data;
            if (uri.startsWith("http")) {
                HttpURLConnection con = (HttpURLConnection) new URL(uri).openConnection();
                con.setConnectTimeout(8000);
                con.setReadTimeout(10_000);
                try (InputStream in = con.getInputStream()) {
                    data = readAll(in);
                } finally {
                    con.disconnect();
                }
            } else {
                try (InputStream in = c.getContentResolver().openInputStream(Uri.parse(uri))) {
                    data = readAll(in);
                }
            }
            if (data == null) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (o.outWidth <= 0) return null;
            o.inJustDecodeBounds = false;
            o.inSampleSize = 1;
            // half the screen's width is the most a card ever shows of a picture
            int want = Math.max(480, Math.max(c.getResources().getDisplayMetrics().widthPixels,
                    c.getResources().getDisplayMetrics().heightPixels) / 2);
            while (o.outWidth / (o.inSampleSize * 2) >= want) o.inSampleSize *= 2;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        if (in == null) return null;
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        for (int n; (n = in.read(buf)) > 0; ) {
            bo.write(buf, 0, n);
            // no artwork is this big: something else is at that address
            if (bo.size() > 8 << 20) return null;
        }
        return bo.toByteArray();
    }
}
