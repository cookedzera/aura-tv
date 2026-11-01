package app.aura.home;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lets Aura see which app is playing (Android shares media sessions only with approved
 * notification listeners; setup approves this one over ADB). It also watches active sessions
 * while you're inside an app so that apps which tear down their MediaSession the moment you press
 * Home (YouTube, YouTube Music, TizenTube) still keep their "Continue Playing" card on the shelf.
 */
public class MediaWatch extends NotificationListenerService {

    static final class Playing {
        String title, by, pkg, artUri;
        boolean playing;
        MediaController controller;
        /** The episode's or track's own picture, when the app gives one; and how far in (0 to 1, or 0). */
        Bitmap art;
        float progress;
        long updatedAt;

        Playing copy() {
            Playing c = new Playing();
            c.title = title;
            c.by = by;
            c.pkg = pkg;
            c.artUri = artUri;
            c.playing = playing;
            c.controller = controller;
            c.art = art;
            c.progress = progress;
            c.updatedAt = updatedAt;
            return c;
        }
    }

    private static final long REMEMBER_MS = 6 * 3600_000L;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final Map<String, Playing> byPkg = new HashMap<>();
    private static final List<MediaController> watched = new ArrayList<>();
    private static final Map<MediaController, MediaController.Callback> callbacks = new HashMap<>();
    private static MediaSessionManager.OnActiveSessionsChangedListener sessionListener;
    private static boolean listening;
    private static Playing lastAny;
    private static String cachedUri;
    private static Bitmap cachedBmp;

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        ensureWatching(getApplicationContext());
    }

    @Override
    public void onListenerDisconnected() {
        stopWatching(getApplicationContext());
        super.onListenerDisconnected();
    }

    static void ensureWatching(Context ctx) {
        if (listening || ctx == null) return;
        // held by a static listener for as long as Aura runs: never an activity
        final Context c = ctx.getApplicationContext();
        final MediaSessionManager msm = Compat.svc(c, MediaSessionManager.class);
        if (msm == null) return;
        main.post(() -> {
            if (listening) return;
            ComponentName cn = new ComponentName(c, MediaWatch.class);
            try {
                sessionListener = controllers -> {
                    bindControllers(c, controllers);
                    changed();
                };
                msm.addOnActiveSessionsChangedListener(sessionListener, cn, main);
                listening = true;
                bindControllers(c, msm.getActiveSessions(cn));
            } catch (SecurityException ignored) {
                sessionListener = null;
                listening = false;
            }
        });
    }

    private static void stopWatching(Context c) {
        main.post(() -> {
            // the old listener goes too, or each reconnect would add another beside it
            MediaSessionManager msm = c != null ? Compat.svc(c, MediaSessionManager.class) : null;
            if (msm != null && sessionListener != null) {
                try {
                    msm.removeOnActiveSessionsChangedListener(sessionListener);
                } catch (RuntimeException ignored) {
                }
            }
            sessionListener = null;
            for (MediaController m : watched) {
                MediaController.Callback cb = callbacks.get(m);
                if (cb != null) {
                    try {
                        m.unregisterCallback(cb);
                    } catch (RuntimeException ignored) {
                    }
                }
            }
            watched.clear();
            callbacks.clear();
            listening = false;
        });
    }

    private static void bindControllers(final Context c, List<MediaController> list) {
        for (MediaController m : watched) {
            MediaController.Callback cb = callbacks.get(m);
            if (cb != null) {
                try {
                    m.unregisterCallback(cb);
                } catch (RuntimeException ignored) {
                }
            }
        }
        watched.clear();
        callbacks.clear();
        if (list == null) return;
        for (final MediaController m : list) {
            if (m == null) continue;
            MediaController.Callback cb = new MediaController.Callback() {
                @Override
                public void onPlaybackStateChanged(PlaybackState state) {
                    record(c, m);
                }

                @Override
                public void onMetadataChanged(MediaMetadata metadata) {
                    record(c, m);
                }
            };
            try {
                m.registerCallback(cb, main);
                watched.add(m);
                callbacks.put(m, cb);
                record(c, m);
            } catch (RuntimeException ignored) {
            }
        }
    }

    /** Captures a live controller's metadata and progress into memory (and preferences) while it plays. */
    private static Playing record(final Context c, MediaController m) {
        if (m == null) return null;
        MediaMetadata md = m.getMetadata();
        PlaybackState st = m.getPlaybackState();
        if (md == null) return null;
        String title = md.getString(MediaMetadata.METADATA_KEY_TITLE);
        if (title == null || title.isEmpty()) title = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        if (title == null || title.isEmpty()) return null;
        String by = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
        if (by == null || by.isEmpty()) by = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        if (by == null || by.isEmpty()) by = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
        if (by == null || by.isEmpty()) by = md.getString(MediaMetadata.METADATA_KEY_WRITER);

        Playing p = new Playing();
        p.title = title;
        p.by = by;
        p.pkg = m.getPackageName();
        int state = st != null ? st.getState() : PlaybackState.STATE_NONE;
        p.playing = state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING;
        p.controller = m;
        p.art = extractBitmap(md);
        p.artUri = extractArtUri(md);
        if (p.art == null && p.artUri != null && p.artUri.equals(cachedUri)) p.art = cachedBmp;

        long length = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
        long at = st != null ? st.getPosition() : 0;
        if (st != null && state == PlaybackState.STATE_PLAYING && st.getLastPositionUpdateTime() > 0) {
            float speed = st.getPlaybackSpeed() > 0 ? st.getPlaybackSpeed() : 1f;
            at += (long) ((SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime()) * speed);
        }
        p.progress = length > 0 && at > 0 ? Math.min(1f, Math.max(0f, at / (float) length)) : 0f;
        p.updatedAt = System.currentTimeMillis();

        synchronized (byPkg) {
            Playing prev = byPkg.get(p.pkg);
            if (p.art == null && prev != null && title.equals(prev.title)) p.art = prev.art;
            if (p.progress <= 0f && prev != null && title.equals(prev.title)) p.progress = prev.progress;
            Playing snap = p.copy();
            snap.playing = false;
            snap.controller = null;
            byPkg.put(p.pkg, snap);
            // YouTube Music on Android TV delegates playback to YouTube / Cobalt; tag it for YouTube Music too
            if (isYoutubeEngine(p.pkg) && "com.google.android.youtube.tvmusic".equals(HomeKeys.lastApp)) {
                Playing ytm = snap.copy();
                ytm.pkg = "com.google.android.youtube.tvmusic";
                byPkg.put("com.google.android.youtube.tvmusic", ytm);
            }
            lastAny = snap;
        }
        // a song started, changed, paused or resumed: whoever shows it hears once, not at every report
        String key = p.pkg + "|" + p.title + "|" + p.playing;
        if (!key.equals(shownKey)) {
            shownKey = key;
            changed();
        }
        if (c != null) {
            // playback reports arrive many times a minute: the file is written for a new track, or every 15 s
            long now = SystemClock.elapsedRealtime();
            if (!p.title.equals(savedTitle) || now - savedAt > 15_000) {
                savedTitle = p.title;
                savedAt = now;
                saveLast(c, p);
            }
            if (p.art == null && p.artUri != null) prefetchArt(c.getApplicationContext(), p.artUri, p.pkg, p.title);
        }
        return p;
    }

    static Bitmap extractBitmap(MediaMetadata md) {
        if (md == null) return null;
        Bitmap b = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (b == null) b = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (b == null) b = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        return b;
    }

    static String extractArtUri(MediaMetadata md) {
        if (md == null) return null;
        String u = md.getString(MediaMetadata.METADATA_KEY_ART_URI);
        if (u == null || u.isEmpty()) u = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI);
        if (u == null || u.isEmpty()) u = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI);
        return u;
    }

    static Bitmap cachedArt(String uri) {
        return uri != null && uri.equals(cachedUri) ? cachedBmp : null;
    }

    static void cacheArt(String uri, Bitmap b) {
        if (uri != null && b != null) {
            cachedUri = uri;
            cachedBmp = b;
        }
    }

    private static String savedTitle, fetching, shownKey;

    /** Told (on the main thread) when what's playing changes: the home screen's card, Control Center's tile. */
    static Runnable onChange;

    private static void changed() {
        Runnable r = onChange;
        if (r != null) main.post(r);
    }
    private static long savedAt;

    private static void prefetchArt(final Context c, final String uri, final String pkg, final String title) {
        if (uri.equals(cachedUri) && cachedBmp != null) return;
        // one download per picture, however many playback reports arrive while it's on its way
        synchronized (byPkg) {
            if (uri.equals(fetching)) return;
            fetching = uri;
        }
        new Thread(() -> {
            Bitmap b = Continue.load(c, uri);
            synchronized (byPkg) {
                if (uri.equals(fetching)) fetching = null;
            }
            if (b == null) return;
            cachedUri = uri;
            cachedBmp = b;
            synchronized (byPkg) {
                Playing cur = byPkg.get(pkg);
                if (cur != null && title.equals(cur.title) && cur.art == null) cur.art = b;
                Playing ytm = byPkg.get("com.google.android.youtube.tvmusic");
                if (ytm != null && title.equals(ytm.title) && ytm.art == null) ytm.art = b;
                if (lastAny != null && title.equals(lastAny.title) && lastAny.art == null) lastAny.art = b;
            }
        }, "media-art").start();
    }

    /** Off the main thread: ensures {@code p.art} is decoded if the session only supplied an artwork URI. */
    static Bitmap resolveArt(Context c, Playing p) {
        if (p == null) return null;
        if (p.art != null) return p.art;
        if (p.artUri == null || p.artUri.isEmpty()) return null;
        if (p.artUri.equals(cachedUri) && cachedBmp != null) {
            p.art = cachedBmp;
            return cachedBmp;
        }
        Bitmap b = Continue.load(c, p.artUri);
        if (b != null) {
            cachedUri = p.artUri;
            cachedBmp = b;
            p.art = b;
        }
        return b;
    }

    private static void saveLast(Context c, Playing p) {
        try {
            String pkg = isYoutubeEngine(p.pkg) && "com.google.android.youtube.tvmusic".equals(HomeKeys.lastApp)
                    ? "com.google.android.youtube.tvmusic" : p.pkg;
            Prefs.device(c).edit()
                    .putString("media_pkg", pkg)
                    .putString("media_engine", p.pkg)
                    .putString("media_title", p.title)
                    .putString("media_by", p.by != null ? p.by : "")
                    .putString("media_uri", p.artUri != null ? p.artUri : "")
                    .putFloat("media_prog", p.progress)
                    .putLong("media_at", p.updatedAt)
                    .apply();
        } catch (RuntimeException ignored) {
        }
    }

    private static Playing loadSaved(Context c) {
        if (c == null) return null;
        SharedPreferences sp = Prefs.device(c);
        long at = sp.getLong("media_at", 0);
        if (at <= 0 || System.currentTimeMillis() - at > REMEMBER_MS) return null;
        String title = sp.getString("media_title", null);
        String pkg = sp.getString("media_pkg", null);
        if (title == null || title.isEmpty() || pkg == null || pkg.isEmpty()) return null;
        Playing p = new Playing();
        p.title = title;
        p.by = sp.getString("media_by", "");
        if (p.by.isEmpty()) p.by = null;
        p.pkg = pkg;
        p.artUri = sp.getString("media_uri", "");
        if (p.artUri.isEmpty()) p.artUri = null;
        if (p.artUri != null && p.artUri.equals(cachedUri)) p.art = cachedBmp;
        p.progress = sp.getFloat("media_prog", 0f);
        p.updatedAt = at;
        return p;
    }

    static boolean isYoutubeEngine(String pkg) {
        return "com.google.android.youtube.tv".equals(pkg)
                || "io.gh.reisxd.tizentube.cobalt".equals(pkg)
                || "com.liskovsoft.smarttubetv".equals(pkg)
                || "com.liskovsoft.smarttubetv.beta".equals(pkg)
                || "com.ycngmn.nobook".equals(pkg);
    }

    static boolean matchesApp(String tilePkg, String mediaPkg) {
        if (tilePkg == null || mediaPkg == null) return false;
        if (tilePkg.equals(mediaPkg)) return true;
        if ("com.google.android.youtube.tvmusic".equals(tilePkg)) return isYoutubeEngine(mediaPkg);
        return false;
    }

    /** The most relevant live media session (playing first, else paused), or null. Main thread is fine: no I/O. */
    static Playing now(Context c) {
        ensureWatching(c != null ? c.getApplicationContext() : null);
        MediaSessionManager msm = Compat.svc(c, MediaSessionManager.class);
        if (msm == null) return null;
        List<MediaController> list;
        try {
            list = msm.getActiveSessions(new ComponentName(c, MediaWatch.class));
        } catch (SecurityException e) {
            return null; // not approved as a listener
        }
        MediaController best = null;
        for (MediaController m : list) {
            PlaybackState st = m.getPlaybackState();
            if (m.getMetadata() == null || st == null) continue;
            int s = st.getState();
            if (s == PlaybackState.STATE_PLAYING || s == PlaybackState.STATE_BUFFERING) {
                best = m;
                break;
            }
            if (best == null && (s == PlaybackState.STATE_PAUSED || s == PlaybackState.STATE_STOPPED)) best = m;
        }
        return best != null ? record(c, best) : null;
    }

    /** The live session if one is active, else the most recently played track/video (within 6 hours). */
    static Playing nowOrLast(Context c) {
        Playing live = now(c);
        if (live != null) return live;
        long now = System.currentTimeMillis();
        synchronized (byPkg) {
            if (lastAny != null && now - lastAny.updatedAt <= REMEMBER_MS) return lastAny.copy();
        }
        Playing saved = loadSaved(c);
        if (saved != null) {
            synchronized (byPkg) {
                lastAny = saved;
                byPkg.put(saved.pkg, saved);
            }
            return saved.copy();
        }
        return null;
    }

    /** Live or remembered playback belonging to {@code pkg} (mapping YouTube Music to YouTube/Cobalt). */
    static Playing forApp(Context c, String pkg) {
        if (pkg == null) return null;
        Playing live = now(c);
        if (live != null && matchesApp(pkg, live.pkg)) return live;
        long now = System.currentTimeMillis();
        synchronized (byPkg) {
            Playing direct = byPkg.get(pkg);
            if (direct != null && now - direct.updatedAt <= REMEMBER_MS) return direct.copy();
            if ("com.google.android.youtube.tvmusic".equals(pkg)) {
                Playing best = null;
                for (Map.Entry<String, Playing> e : byPkg.entrySet()) {
                    if (isYoutubeEngine(e.getKey()) && now - e.getValue().updatedAt <= REMEMBER_MS) {
                        if (best == null || e.getValue().updatedAt > best.updatedAt) best = e.getValue();
                    }
                }
                if (best != null) return best.copy();
            }
        }
        Playing saved = loadSaved(c);
        if (saved != null && matchesApp(pkg, saved.pkg)) return saved.copy();
        return null;
    }

    /**
     * How long until what's playing ends, at its speed, counting the time since the app last said
     * where it was; -1 when nothing is playing or it has no known length (live TV, a radio stream).
     */
    static long remainingMs(Context c) {
        Playing p = now(c);
        if (p == null || !p.playing || p.controller == null) return -1;
        PlaybackState st = p.controller.getPlaybackState();
        MediaMetadata md = p.controller.getMetadata();
        if (st == null || md == null) return -1;
        long length = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
        if (length <= 0) return -1;
        float speed = st.getPlaybackSpeed() > 0 ? st.getPlaybackSpeed() : 1f;
        long since = SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime();
        long at = st.getPosition() + (st.getLastPositionUpdateTime() > 0 ? (long) (since * speed) : 0);
        long left = (long) ((length - at) / speed);
        return left > 0 ? left : -1;
    }
}
