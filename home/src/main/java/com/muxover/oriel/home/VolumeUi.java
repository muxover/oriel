package com.muxover.oriel.home;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/** Turns volume events from the root helper into our own popup. */
final class VolumeUi {
    private static final int FLAG_SHOW_UI = 1;
    private static final Handler main = new Handler(Looper.getMainLooper());

    private VolumeUi() {}

    /** A line "vol <stream> <flags>" from the helper, on any thread. */
    static void event(String line) {
        String[] p = line.split(" ");
        if (p.length < 3) return;
        final int stream, flags;
        try {
            stream = Integer.parseInt(p[1]);
            flags = Integer.parseInt(p[2]);
        } catch (NumberFormatException ex) {
            Log.w("Oriel", "bad volume event " + line, ex);
            return;
        }
        if (stream != AudioManager.STREAM_MUSIC || (flags & FLAG_SHOW_UI) == 0) return;
        main.post(VolumeUi::changed);
    }

    private static void changed() {
        Context c = Shell.app;
        if (c == null) return;
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        int s = AudioManager.STREAM_MUSIC;
        int max = Math.max(1, am.getStreamMaxVolume(s));
        VolumeHud.show(c, am.getStreamVolume(s) * 100 / max, am.isStreamMute(s));
    }
}
