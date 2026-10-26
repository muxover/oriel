package com.muxover.oriel.home;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Reads the TV's own remote buttons from the system log when Oriel is a system app (granted READ_LOGS),
 * the same lines the root keeper watches otherwise, and opens Oriel's screen for each one.
 */
final class HotkeyReader {
    private static Thread thread;
    private static Process proc;

    private HotkeyReader() {}

    static synchronized void start(Context c) {
        if (thread != null || !Sys.granted(c, "android.permission.READ_LOGS")) return;
        Context app = c.getApplicationContext();
        thread = new Thread(() -> run(app), "oriel-hotkeys");
        thread.setDaemon(true);
        thread.start();
    }

    static synchronized void stop() {
        if (proc != null) proc.destroy();
        if (thread != null) thread.interrupt();
        proc = null;
        thread = null;
    }

    private static void run(Context c) {
        try {
            // -T 1 starts at the newest line; that one is old news and is skipped.
            Process p = new ProcessBuilder("logcat", "-v", "brief", "-T", "1", "-s", "PhoneWindowManagerEx:D", "WindowManager:D")
                .redirectErrorStream(true).start();
            synchronized (HotkeyReader.class) {
                proc = p;
            }
            Log.i("Oriel", "reading the remote's buttons from the system log");
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = r.readLine();
            while ((line = r.readLine()) != null) {
                int key = keyOf(line);
                if (key == 0) continue;
                c.startActivity(new Intent(c, HotkeyActivity.class).putExtra("key", key)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
            }
        } catch (Exception ex) {
            Log.w("Oriel", "remote button reader stopped", ex);
        } finally {
            synchronized (HotkeyReader.class) {
                thread = null;
                proc = null;
            }
        }
    }

    /** The button in a log line, 0 when the line is about something else. Matches tvkeeper.sh. */
    static int keyOf(String line) {
        if (line.contains("handleKeyEvent  keyCode=2001, down=true")) return HotkeyActivity.SOURCE;
        if (line.contains("handleKeyEvent  keyCode=2010, down=true")) return HotkeyActivity.PICTURE;
        if (line.contains("handleKeyEvent  keyCode=223, down=true")) return HotkeyActivity.SLEEP;
        if (line.contains("startLongPowerMenu")) return HotkeyActivity.LONG_POWER;
        return 0;
    }
}
