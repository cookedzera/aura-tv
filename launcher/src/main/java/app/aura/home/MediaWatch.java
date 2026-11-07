package app.aura.home;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    /** The players being watched, in Android's order (the most recently active first); read from any thread. */
    private static final List<MediaController> watched = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static final Map<MediaController, MediaController.Callback> callbacks = new HashMap<>();
    /**
     * What each watched player last reported, as its own report brought it. Asking a player again
     * is a call across to its app, and its metadata carries the cover picture: done every second
     * for the song's progress, and again for each of its many reports, it held up the main thread.
     */
    private static final Map<android.media.session.MediaSession.Token, MediaMetadata> metaOf = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<android.media.session.MediaSession.Token, PlaybackState> stateOf = new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * When each player last started playing (elapsedRealtime), kept as the list of players changes:
     * Android sends the remote's Play to the latest still playing, else the latest. Those found
     * playing as the list is read count as starting together, so the list's own order (the latest
     * first) decides between them.
     */
    private static final Map<MediaSession.Token, Long> playingSince = new java.util.concurrent.ConcurrentHashMap<>();
    /** Players playing at their last report. */
    private static final Set<MediaSession.Token> playingNow = Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<MediaSession.Token, Boolean>());
    /** While the list of players is read, the moment it was read; otherwise 0. */
    private static long bindAt;
    private static MediaSessionManager.OnActiveSessionsChangedListener sessionListener;
    private static volatile boolean listening;
    private static Playing lastAny;
    private static volatile String cachedUri;
    private static volatile Bitmap cachedBmp;

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        connected = this;
        ensureWatching(getApplicationContext());
        notesChanged();
        // Android connects this listener again soon after firmware force-stops Aura (Xiaomi's does in
        // standby), which starts Aura's process: the force-stop took the Home button service off the list
        HomeKeys.revive(getApplicationContext());
    }

    @Override
    public void onListenerDisconnected() {
        if (connected == this) connected = null;
        stopWatching(getApplicationContext());
        super.onListenerDisconnected();
    }

    // ---- notifications, for Control Center -----------------------------------------------------

    /** This listener while Android has it connected: what's in the TV's notifications can be read. */
    private static volatile MediaWatch connected;
    /** Told (on the main thread) when a notification arrives or goes: Control Center's list. */
    static volatile Runnable onNotes;

    static final class Note {
        String key, pkg, title, text;
        long when;
        boolean clearable;
        android.app.PendingIntent open;
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        notesChanged();
    }

    @Override
    public void onNotificationRemoved(android.service.notification.StatusBarNotification sbn) {
        notesChanged();
    }

    private static void notesChanged() {
        final Runnable r = onNotes;
        if (r != null) main.post(() -> {
            try {
                r.run();
            } catch (RuntimeException e) {
                Trouble.caught("notifications", e);
            }
        });
    }

    /** Whether Aura can read the TV's notifications (approved as a listener, as for Now Playing). */
    static boolean notesReadable() {
        return connected != null;
    }

    /**
     * The newest notifications worth a look, up to {@code max}: what an app said, not what it keeps
     * up while it runs (ongoing ones, players), nor Aura's own, nor a group's summary line.
     */
    static List<Note> notes(Context c, int max) {
        List<Note> out = new ArrayList<>();
        MediaWatch w = connected;
        if (w == null) return out;
        StatusBarNotification[] all;
        try {
            all = w.getActiveNotifications();
        } catch (RuntimeException e) {
            return out;
        }
        if (all == null) return out;
        java.util.Arrays.sort(all, (a, b) -> Long.compare(b.getPostTime(), a.getPostTime()));
        for (StatusBarNotification sbn : all) {
            if (out.size() >= max) break;
            Note note = note(c, sbn);
            if (note != null) out.add(note);
        }
        return out;
    }

    /** What an app said in {@code sbn}, or null when it isn't worth a look (see {@link #notes}); a kids' profile skips hidden apps. */
    private static Note note(Context c, StatusBarNotification sbn) {
        android.app.Notification n = sbn.getNotification();
        if (n == null || sbn.isOngoing() || sbn.getPackageName().equals(c.getPackageName())
                || !HomeKeys.allowed(c, sbn.getPackageName())) return null;
        if ((n.flags & android.app.Notification.FLAG_GROUP_SUMMARY) != 0) return null;
        android.os.Bundle x = n.extras;
        if (x == null) return null;
        String template = x.getString(android.app.Notification.EXTRA_TEMPLATE, "");
        if (template.contains("MediaStyle")) return null;
        CharSequence title = x.getCharSequence(android.app.Notification.EXTRA_TITLE);
        CharSequence text = x.getCharSequence(android.app.Notification.EXTRA_TEXT);
        if (title == null || title.toString().trim().isEmpty()) return null;
        Note note = new Note();
        note.key = sbn.getKey();
        note.pkg = sbn.getPackageName();
        note.title = title.toString().trim();
        note.text = text != null ? text.toString().trim() : "";
        note.when = sbn.getPostTime();
        note.clearable = sbn.isClearable();
        note.open = n.contentIntent;
        return note;
    }

    /** Dismissed from Control Center: gone from the TV's notifications, as a swipe would. */
    static void dismiss(String key) {
        MediaWatch w = connected;
        if (w == null || key == null) return;
        try {
            w.cancelNotification(key);
        } catch (RuntimeException ignored) {
        }
    }

    /**
     * Before Android 8.1 there's no shell command to approve a notification listener: the setting
     * itself is written, with every listener already on it kept. Read here, run over ADB at setup.
     */
    static String enableCommand(Context c) {
        String me = new ComponentName(c, MediaWatch.class).flattenToString();
        String list = null;
        try {
            list = android.provider.Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        } catch (RuntimeException ignored) {
        }
        java.util.List<String> keep = new java.util.ArrayList<>();
        if (list != null) {
            for (String s : android.text.TextUtils.split(list, ":")) {
                ComponentName cn = ComponentName.unflattenFromString(s);
                if (!s.isEmpty() && (cn == null || !cn.equals(new ComponentName(c, MediaWatch.class)))) keep.add(s);
            }
        }
        keep.add(me);
        return "settings put secure enabled_notification_listeners '" + android.text.TextUtils.join(":", keep).replace("'", "") + "'";
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
            metaOf.clear();
            stateOf.clear();
            playingSince.clear();
            playingNow.clear();
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
        metaOf.clear();
        stateOf.clear();
        if (list == null) list = Collections.emptyList();
        bindAt = SystemClock.elapsedRealtime();
        try {
            for (final MediaController m : list) {
                if (m == null) continue;
                MediaController.Callback cb = new MediaController.Callback() {
                    // another app's player, read over Android's binder: it can die, or send something
                    // malformed, mid-call; that report is let go rather than Aura with it
                    @Override
                    public void onPlaybackStateChanged(PlaybackState state) {
                        safeRecord(c, m, meta(m), state);
                    }

                    @Override
                    public void onMetadataChanged(MediaMetadata metadata) {
                        safeRecord(c, m, metadata, state(m));
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
        } finally {
            bindAt = 0;
        }
        // a player gone from the list is forgotten; the rest keep when they last started playing
        Set<MediaSession.Token> live = new HashSet<>();
        for (MediaController m : watched) live.add(m.getSessionToken());
        playingSince.keySet().retainAll(live);
        playingNow.retainAll(live);
    }

    /** Captures a live controller's metadata and progress into memory (and preferences) while it plays. */
    private static Playing record(final Context c, MediaController m) {
        if (m == null) return null;
        MediaMetadata md = m.getMetadata();
        PlaybackState st = m.getPlaybackState();
        remember(m, md, st);
        return record(c, m, md, st);
    }

    /**
     * What a player reported, kept for {@link #meta} and {@link #state}. Only its own reports (and
     * asking it directly) write here, never a reader on another thread: that could put back an
     * older report over the one that just came.
     */
    private static void remember(MediaController m, MediaMetadata md, PlaybackState st) {
        if (md != null) metaOf.put(m.getSessionToken(), md);
        else metaOf.remove(m.getSessionToken());
        if (st != null) stateOf.put(m.getSessionToken(), st);
        else stateOf.remove(m.getSessionToken());
        started(m.getSessionToken(), st);
    }

    /** Notes the moment a player starts playing. */
    private static void started(MediaSession.Token t, PlaybackState st) {
        if (!playing(st)) playingNow.remove(t);
        else if (playingNow.add(t)) playingSince.put(t, bindAt > 0 ? bindAt : SystemClock.elapsedRealtime());
    }

    private static boolean playing(PlaybackState st) {
        int s = st != null ? st.getState() : PlaybackState.STATE_NONE;
        return s == PlaybackState.STATE_PLAYING || s == PlaybackState.STATE_BUFFERING;
    }

    /** A player with something to show: playing, or paused or stopped where it was. */
    private static boolean showable(PlaybackState st) {
        int s = st != null ? st.getState() : PlaybackState.STATE_NONE;
        return s == PlaybackState.STATE_PLAYING || s == PlaybackState.STATE_BUFFERING
                || s == PlaybackState.STATE_PAUSED || s == PlaybackState.STATE_STOPPED;
    }

    /** The same, from what the player reported: nothing is asked of it again. */
    private static Playing record(final Context c, MediaController m, MediaMetadata md, PlaybackState st) {
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
            float speed = MediaWatch.speed(st);
            at += (long) ((SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime()) * speed);
        }
        p.progress = length > 0 && at > 0 ? Math.min(1f, Math.max(0f, at / (float) length)) : 0f;
        p.updatedAt = System.currentTimeMillis();
        // a programme (not a song): kept for the stack's Continue watching, where Watch Next is missing
        Watched.note(c, p, length, at);

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
            // a player not heard from in six hours is forgotten, with its picture: nothing shows it any more
            long now = System.currentTimeMillis();
            java.util.Iterator<Map.Entry<String, Playing>> it = byPkg.entrySet().iterator();
            while (it.hasNext()) {
                Playing old = it.next().getValue();
                if (old != snap && now - old.updatedAt > REMEMBER_MS) it.remove();
            }
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
    /** Players on screen that follow the one the remote's Play key would reach (Now Playing, the music strip). */
    private static final List<Runnable> followers = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** {@code r} is told, on the main thread, whenever {@link #changed} is; until it's let go with {@code on} false. */
    static void follow(Runnable r, boolean on) {
        if (!on) followers.remove(r);
        else if (!followers.contains(r)) followers.add(r);
    }

    /**
     * When another player is playing and the remote's Play key would reach it now, {@code cb} moves
     * there from {@code from}: that player's controller, or null when nothing moved.
     */
    static MediaController moveTo(Context c, MediaController from, MediaController.Callback cb, Handler h) {
        Playing p = now(c);
        if (p == null || !p.playing || p.controller == null || p.controller.getSessionToken().equals(from.getSessionToken())) return null;
        try {
            p.controller.registerCallback(cb, h);
        } catch (RuntimeException e) {
            return null;
        }
        try {
            from.unregisterCallback(cb);
        } catch (RuntimeException ignored) {
        }
        return p.controller;
    }

    private static void changed() {
        final Runnable r = onChange;
        if (r == null && followers.isEmpty()) return;
        main.post(() -> {
            try {
                if (r != null) r.run();
            } catch (RuntimeException e) {
                Trouble.caught("now playing", e);
            }
            for (Runnable f : followers) {
                try {
                    f.run();
                } catch (RuntimeException e) {
                    Trouble.caught("now playing", e);
                }
            }
        });
    }

    private static void safeRecord(Context c, MediaController m, MediaMetadata md, PlaybackState st) {
        try {
            remember(m, md, st);
            record(c, m, md, st);
        } catch (RuntimeException e) {
            Trouble.caught("a player's report", e);
        }
    }
    private static long savedAt;

    private static void prefetchArt(final Context c, final String uri, final String pkg, final String title) {
        if (uri.equals(cachedUri) && cachedBmp != null) return;
        // one download per picture, however many playback reports arrive while it's on its way
        synchronized (byPkg) {
            if (uri.equals(fetching)) return;
            fetching = uri;
        }
        new SafeThread(() -> {
            Bitmap b = Continue.load(c, uri, Continue.COVER_PX);
            synchronized (byPkg) {
                if (uri.equals(fetching)) fetching = null;
            }
            if (b == null) return;
            cachedUri = uri;
            cachedBmp = b;
            boolean updated = false;
            synchronized (byPkg) {
                Playing cur = byPkg.get(pkg);
                if (cur != null && title.equals(cur.title) && cur.art == null) {
                    cur.art = b;
                    updated = true;
                }
                Playing ytm = byPkg.get("com.google.android.youtube.tvmusic");
                if (ytm != null && title.equals(ytm.title) && ytm.art == null) ytm.art = b;
                if (lastAny != null && title.equals(lastAny.title) && lastAny.art == null) lastAny.art = b;
            }
            if (updated) changed();
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
        Bitmap b = Continue.load(c, p.artUri, Continue.COVER_PX);
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

    /**
     * The player's last reported metadata (with its picture), from its own reports while it's
     * watched; asked of the player only when it isn't in the watched list.
     */
    static MediaMetadata meta(MediaController m) {
        if (m == null) return null;
        MediaMetadata md = metaOf.get(m.getSessionToken());
        return md != null || watching(m) ? md : m.getMetadata();
    }

    /** The player's last reported state (where it was, and when), the same way. */
    static PlaybackState state(MediaController m) {
        if (m == null) return null;
        PlaybackState st = stateOf.get(m.getSessionToken());
        return st != null || watching(m) ? st : m.getPlaybackState();
    }

    /**
     * Whether this player's session is among those watched. By its session, not the controller
     * object: Android hands out a new controller for the same session each time the list changes,
     * and a card holding an older one would otherwise ask the app across the binder on every tick.
     */
    private static boolean watching(MediaController m) {
        android.media.session.MediaSession.Token t = m.getSessionToken();
        for (MediaController w : watched) if (t.equals(w.getSessionToken())) return true;
        return false;
    }

    /**
     * The player the remote's Play key would reach, or null when none has anything to show: on
     * Android 13 and later the one Android names, before that the one playing that most recently
     * started, else the last to play (Android sends media keys to the app playing, else the last
     * app that played; see {@link #buttonSession}). With nothing to tell them
     * apart, the one playing, else the first paused. Main thread is fine: no I/O (on 13+, with
     * several players, one quick question to Android).
     */
    static Playing now(Context c) {
        ensureWatching(c != null ? c.getApplicationContext() : null);
        List<MediaController> live = new ArrayList<>();
        List<PlaybackState> states = new ArrayList<>();
        if (listening) {
            // the players are watched: what they last reported is known, with nothing asked of them
            for (MediaController m : watched) {
                if (c != null && !HomeKeys.allowed(c, m.getPackageName())) continue;
                PlaybackState st = stateOf.get(m.getSessionToken());
                if (metaOf.get(m.getSessionToken()) == null || !showable(st)) continue;
                live.add(m);
                states.add(st);
            }
            MediaController best = pick(c, live, states);
            if (best == null) return null;
            MediaMetadata md = metaOf.get(best.getSessionToken());
            PlaybackState st = stateOf.get(best.getSessionToken());
            if (md == null || st == null) return null;
            Playing snap;
            synchronized (byPkg) {
                Playing cur = byPkg.get(best.getPackageName());
                snap = cur != null ? cur.copy() : null;
            }
            if (snap == null) return record(c, best, md, st);
            int s = st.getState();
            snap.playing = s == PlaybackState.STATE_PLAYING || s == PlaybackState.STATE_BUFFERING;
            snap.controller = best;
            long length = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
            long at = st.getPosition();
            if (s == PlaybackState.STATE_PLAYING && st.getLastPositionUpdateTime() > 0) {
                float speed = MediaWatch.speed(st);
                at += (long) (Math.max(0, SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime()) * speed);
            }
            if (length > 0 && at > 0) snap.progress = Math.min(1f, Math.max(0f, at / (float) length));
            return snap;
        }
        MediaSessionManager msm = Compat.svc(c, MediaSessionManager.class);
        if (msm == null) return null;
        List<MediaController> list;
        try {
            list = msm.getActiveSessions(new ComponentName(c, MediaWatch.class));
        } catch (SecurityException e) {
            return null; // not approved as a listener
        }
        for (MediaController m : list) {
            if (c != null && !HomeKeys.allowed(c, m.getPackageName())) continue;
            PlaybackState st = m.getPlaybackState();
            if (m.getMetadata() == null || !showable(st)) continue;
            live.add(m);
            states.add(st);
        }
        MediaController best = pick(c, live, states);
        return best != null ? record(c, best) : null;
    }

    /** Of the players with something to show (each with its state), the one the remote's Play key would reach. */
    private static MediaController pick(Context c, List<MediaController> live, List<PlaybackState> states) {
        if (live.isEmpty()) return null;
        if (live.size() > 1) {
            MediaController b = buttonSession(c, live);
            if (b != null) return b;
        }
        // nothing tells them apart: the one playing, else the first paused (Android's order, latest first)
        for (int i = 0; i < live.size(); i++) if (playing(states.get(i))) return live.get(i);
        return live.get(0);
    }

    /**
     * Of {@code live}, the player Android would send Play and Pause to: on Android 13 and later the
     * one it names (the listener's approval lets Aura ask), before that the one playing that most
     * recently started, else (none playing) the one that most recently started. Null when that
     * can't be told.
     */
    static MediaController buttonSession(Context c, List<MediaController> live) {
        if (Build.VERSION.SDK_INT >= 33 && c != null) {
            MediaSessionManager msm = Compat.svc(c, MediaSessionManager.class);
            try {
                MediaSession.Token t = msm != null ? msm.getMediaKeyEventSession() : null;
                if (t != null) for (MediaController m : live) if (t.equals(m.getSessionToken())) return m;
            } catch (RuntimeException e) {
                // the listener's approval taken away (a SecurityException), or Android not answering
            }
        }
        MediaController best = null;
        long bestAt = -1;
        boolean bestPlays = false;
        for (MediaController m : live) {
            Long at = playingSince.get(m.getSessionToken());
            if (at == null) continue;
            boolean plays = playingNow.contains(m.getSessionToken());
            if (plays && !bestPlays || plays == bestPlays && at > bestAt) {
                best = m;
                bestAt = at;
                bestPlays = plays;
            }
        }
        return best;
    }

    /**
     * A media key with no player in hand: Android delivers it where the remote's own would go, to
     * the player Play would reach (whose media button can start it again).
     */
    static void dispatchKey(Context c, int code) {
        AudioManager am = Compat.svc(c, AudioManager.class);
        if (am == null) return;
        long t = SystemClock.uptimeMillis();
        try {
            am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0));
            am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0));
        } catch (RuntimeException ignored) {
            // a TV's own tuner holding the keys (a global-priority session), or no media service
        }
    }

    /** The live session if one is active, else the most recently played track/video (within 6 hours). */
    static Playing nowOrLast(Context c) {
        Playing live = now(c);
        if (live != null) return live;
        long now = System.currentTimeMillis();
        synchronized (byPkg) {
            if (lastAny != null && now - lastAny.updatedAt <= REMEMBER_MS
                    && (c == null || HomeKeys.allowed(c, lastAny.pkg))) return lastAny.copy();
        }
        Playing saved = loadSaved(c);
        if (saved != null) {
            synchronized (byPkg) {
                lastAny = saved;
                byPkg.put(saved.pkg, saved);
            }
            if (c == null || HomeKeys.allowed(c, saved.pkg)) return saved.copy();
        }
        return null;
    }

    /** Live or remembered playback belonging to {@code pkg} (mapping YouTube Music to YouTube/Cobalt). */
    static Playing forApp(Context c, String pkg) {
        if (pkg == null || c != null && !HomeKeys.allowed(c, pkg)) return null;
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
        PlaybackState st = state(p.controller);
        MediaMetadata md = meta(p.controller);
        if (st == null || md == null) return -1;
        long length = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
        if (length <= 0) return -1;
        float speed = MediaWatch.speed(st);
        long since = SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime();
        long at = st.getPosition() + (st.getLastPositionUpdateTime() > 0 ? (long) (since * speed) : 0);
        // rewinding, there's no telling when it ends
        if (speed <= 0f) return -1;
        long left = (long) ((length - at) / speed);
        return left > 0 ? left : -1;
    }

    /**
     * The player's rate of play as it affects the position: 1 when it doesn't say, negative while
     * rewinding (it used to count as playing forward), and never a wild number from a broken report.
     */
    static float speed(PlaybackState st) {
        float s = st.getPlaybackSpeed();
        if (s == 0f || Float.isNaN(s) || Float.isInfinite(s)) return 1f;
        return Math.max(-32f, Math.min(32f, s));
    }
}
