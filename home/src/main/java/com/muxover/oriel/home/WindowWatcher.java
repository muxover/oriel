package com.muxover.oriel.home;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Bundle;
import android.media.session.MediaController;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Hears from the system whenever a new app window comes on screen and when a remote key is pressed.
 * From that it knows when the TV is idle, and brings up Now Playing or the screen saver.
 */
public class WindowWatcher extends AccessibilityService {
    static volatile String lastPackage;
    static volatile String lastClass;
    static volatile long lastAt;
    /** The app in front, our own included. */
    static volatile String frontPackage;
    static volatile long lastKeyAt = SystemClock.uptimeMillis();

    private static final long MUSIC_IDLE_MS = 60 * 1000L;
    /** Apps that play video: Now Playing never covers them. */
    private static final Set<String> VIDEO = new HashSet<>(Arrays.asList(
        "com.google.android.youtube.tv", "com.watchit.vod", "com.yango.saft.tv", "com.netflix.mediaclient",
        "com.hisilicon.tvui", "com.android.chrome", "com.hikeen.mediaplayer"
    ));

    private final Handler handler = new Handler(Looper.getMainLooper());
    private static WindowWatcher self;

    private final Runnable idleCheck = new Runnable() {
        @Override
        public void run() {
            checkIdle();
            Night.apply(WindowWatcher.this);
            handler.postDelayed(this, 15000);
        }
    };

    /**
     * This TV's system closes the app you leave whenever an Oriel screen opens (Oriel carries the maker's launcher
     * name), so music from Spotify or any other app stopped on the way home. The system reads the app to close
     * from sys.current.packageName and spares a short list of the maker's packages; while an app plays music,
     * that setting names one of those instead, and the music app is left running.
     */
    private static final String SPARED = "com.google.android.katniss";
    private final Runnable keepMusic = new Runnable() {
        @Override
        public void run() {
            spareMusicApp();
            handler.postDelayed(this, 1500);
        }
    };

    private void spareMusicApp() {
        MediaController mc = MediaWatcher.playing();
        if (mc == null) return;
        String pkg = mc.getPackageName();
        // Video apps too: YouTube pauses itself when left, and kept running it continues where it was.
        if (pkg.equals(getPackageName())) return;
        if (!pkg.equals(Sys.prop("sys.current.packageName"))) return;
        Shell.send(this, "sh setprop sys.current.packageName " + SPARED);
        Log.i("Oriel", pkg + " is playing: the TV will leave it running when home opens");
    }

    @Override
    protected void onServiceConnected() {
        self = this;
        lastKeyAt = SystemClock.uptimeMillis(); // the service may have been off a while: start counting idle time now
        Night.apply(this);
        RemoteServer.sync(this);
        Shell.start(this);
        HotkeyReader.start(this);
        handler.postDelayed(idleCheck, 15000);
        handler.postDelayed(keepMusic, 1500);
    }

    @Override
    public void onDestroy() {
        self = null;
        HotkeyReader.stop();
        handler.removeCallbacks(idleCheck);
        handler.removeCallbacks(keepMusic);
        super.onDestroy();
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        lastKeyAt = SystemClock.uptimeMillis();
        if (event.getKeyCode() != KeyEvent.KEYCODE_TV_INPUT) return false;
        if (event.getAction() == KeyEvent.ACTION_UP) InputActivity.open(this);
        return true;
    }

    /** Puts text into the focused text box of the app in front. False when there is none. */
    static boolean setFocusedText(String text) {
        WindowWatcher w = self;
        if (w == null) return false;
        AccessibilityNodeInfo n = w.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (n == null || !n.isEditable()) return false;
        Bundle b = new Bundle();
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
    }

    /** The running service, or null when the user has turned it off. */
    static WindowWatcher self() {
        return self;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        Updater.onEvent(this, e);
        if (e.getEventType() == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            replaceMakerWindows();
            return;
        }
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || e.getPackageName() == null) return;
        String p = e.getPackageName().toString();
        if (makerPowerScreen(p, e.getClassName())) {
            // The maker's power or shutdown dialog: close it and show Oriel's instead.
            performGlobalAction(GLOBAL_ACTION_BACK);
            PowerActivity.open(this);
            return;
        }
        if (!"android".equals(p) && !p.equals("com.android.systemui")) frontPackage = p;
        if (p.equals(getPackageName())) return;
        lastPackage = p;
        lastClass = e.getClassName() != null ? e.getClassName().toString() : "";
        lastAt = SystemClock.uptimeMillis();
    }

    /**
     * The maker's panels that are drawn as system windows rather than app screens (its power box, source
     * list and sleep timer): each is closed and Oriel's own screen opened in its place.
     */
    private void replaceMakerWindows() {
        java.util.List<android.view.accessibility.AccessibilityWindowInfo> ws = getWindows();
        for (android.view.accessibility.AccessibilityWindowInfo w : ws) {
            CharSequence t = w.getTitle();
            if (t == null) continue;
            String title = t.toString();
            String kind = MakerWindows.kindOf(title);
            if (kind == null) continue;
            Log.i("Oriel", "maker window " + title + " replaced with Oriel's " + kind);
            performGlobalAction(GLOBAL_ACTION_BACK);
            MakerWindows.openOurs(this, kind);
            return;
        }
    }

    /** Power, shutdown and standby screens of Android or the TV maker, by their window class names. */
    private static boolean makerPowerScreen(String pkg, CharSequence cls) {
        if (cls == null) return false;
        String c = cls.toString();
        boolean system = pkg.equals("android") || pkg.equals("com.android.systemui") || pkg.contains("hikeen") || pkg.contains("hisilicon");
        if (system && !c.startsWith("android.widget") && !c.startsWith("android.view")) Log.i("Oriel", "system window " + pkg + " " + c);
        return system && (c.contains("GlobalActions") || c.contains("Shutdown") || c.contains("PowerDialog") || c.contains("Standby"));
    }

    private void checkIdle() {
        if (AmbientActivity.showing) return;
        long idle = SystemClock.uptimeMillis() - lastKeyAt;
        String front = frontPackage;
        boolean onHome = getPackageName().equals(front) && HomeActivity.showing;
        MediaController music = MediaWatcher.playing();
        // Only over the home screen: opening it over another app makes this TV's system close that app, which
        // stops the music it is playing (AirPlay's own player was closed this way, cutting off the iPhone).
        boolean musicOk = music != null && onHome && !VIDEO.contains(music.getPackageName());
        int saver = Prefs.saverMinutes(this);
        boolean clock = saver > 0 && music == null && onHome && idle >= saver * 60 * 1000L;
        if ((musicOk && idle >= MUSIC_IDLE_MS) || clock) {
            try {
                startActivity(new Intent(this, AmbientActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException ex) {
                Log.w("Oriel", "could not start the idle screen", ex);
            }
        }
    }

    @Override
    public void onInterrupt() {
    }
}
