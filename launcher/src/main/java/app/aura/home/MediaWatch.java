package app.aura.home;

import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.service.notification.NotificationListenerService;

import java.util.List;

/**
 * Lets Aura see which app is playing (Android shares media sessions only with approved
 * notification listeners; setup approves this one over ADB). It reads no notifications: Android
 * just binds it, and Aura asks for the active media session when the home screen needs it.
 */
public class MediaWatch extends NotificationListenerService {

    static final class Playing {
        String title, by, pkg;
        boolean playing;
        MediaController controller;
        /** The episode's or track's own picture, when the app gives one; and how far in (0 to 1, or 0). */
        android.graphics.Bitmap art;
        float progress;
    }

    /** The most relevant media session (playing first, else paused), or null. Main thread is fine: no I/O. */
    static Playing now(Context c) {
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
            if (st.getState() == PlaybackState.STATE_PLAYING) {
                best = m;
                break;
            }
            if (best == null && st.getState() == PlaybackState.STATE_PAUSED) best = m;
        }
        if (best == null) return null;
        MediaMetadata md = best.getMetadata();
        Playing p = new Playing();
        p.title = md.getString(MediaMetadata.METADATA_KEY_TITLE);
        p.by = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
        if (p.by == null) p.by = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
        p.pkg = best.getPackageName();
        p.playing = best.getPlaybackState().getState() == PlaybackState.STATE_PLAYING;
        p.controller = best;
        p.art = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (p.art == null) p.art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (p.art == null) p.art = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        long length = md.getLong(MediaMetadata.METADATA_KEY_DURATION), at = best.getPlaybackState().getPosition();
        p.progress = length > 0 && at > 0 ? Math.min(1f, at / (float) length) : 0f;
        return p.title == null ? null : p;
    }

    /**
     * How long until what's playing ends, at its speed, counting the time since the app last said
     * where it was; -1 when nothing is playing or it has no known length (live TV, a radio stream).
     */
    static long remainingMs(Context c) {
        Playing p = now(c);
        if (p == null || !p.playing) return -1;
        PlaybackState st = p.controller.getPlaybackState();
        MediaMetadata md = p.controller.getMetadata();
        if (st == null || md == null) return -1;
        long length = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
        if (length <= 0) return -1;
        float speed = st.getPlaybackSpeed() > 0 ? st.getPlaybackSpeed() : 1f;
        long since = android.os.SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime();
        long at = st.getPosition() + (st.getLastPositionUpdateTime() > 0 ? (long) (since * speed) : 0);
        long left = (long) ((length - at) / speed);
        return left > 0 ? left : -1;
    }
}
