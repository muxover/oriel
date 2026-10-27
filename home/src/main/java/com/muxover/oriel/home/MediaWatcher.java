package com.muxover.oriel.home;

import android.content.ComponentName;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.service.notification.NotificationListenerService;
import android.util.Log;

import java.util.List;

/**
 * Lets the TV see what any app is playing (title, artist, album art, position) through the system's
 * media sessions. Android only shares these with an enabled notification listener, hence this service.
 */
public class MediaWatcher extends NotificationListenerService {
    private static volatile MediaWatcher instance;

    @Override
    public void onListenerConnected() {
        instance = this;
    }

    @Override
    public void onListenerDisconnected() {
        instance = null;
    }

    /** The session that is playing right now, or null. */
    static MediaController playing() {
        MediaWatcher w = instance;
        if (w == null) return null;
        try {
            MediaSessionManager msm = (MediaSessionManager) w.getSystemService(MEDIA_SESSION_SERVICE);
            List<MediaController> list = msm.getActiveSessions(new ComponentName(w, MediaWatcher.class));
            for (MediaController mc : list) {
                PlaybackState st = mc.getPlaybackState();
                MediaMetadata md = mc.getMetadata();
                if (st != null && st.getState() == PlaybackState.STATE_PLAYING && md != null) return mc;
            }
        } catch (SecurityException ex) {
            Log.w("Oriel", "media sessions not readable", ex);
        }
        return null;
    }

    /** The active session of one app, playing or paused, or null. */
    static MediaController of(String pkg) {
        MediaWatcher w = instance;
        if (w == null) return null;
        try {
            MediaSessionManager msm = (MediaSessionManager) w.getSystemService(MEDIA_SESSION_SERVICE);
            for (MediaController mc : msm.getActiveSessions(new ComponentName(w, MediaWatcher.class))) {
                if (pkg.equals(mc.getPackageName()) && mc.getMetadata() != null && mc.getPlaybackState() != null) return mc;
            }
        } catch (SecurityException ex) {
            Log.w("Oriel", "media sessions not readable", ex);
        }
        return null;
    }
}
