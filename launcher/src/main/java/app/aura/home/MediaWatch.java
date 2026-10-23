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
    }

    /** The most relevant media session (playing first, else paused), or null. Main thread is fine: no I/O. */
    static Playing now(Context c) {
        MediaSessionManager msm = c.getSystemService(MediaSessionManager.class);
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
        return p.title == null ? null : p;
    }
}
