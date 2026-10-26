package com.muxover.oriel.home;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

/**
 * One-press app updates: opens Google Play's Updates page and presses its "Update all" button through
 * Oriel's accessibility service. Play keeps updating in the background after that.
 */
final class Updater {
    private static final String TAG = "Oriel";
    private static final String PLAY = "com.android.vending";
    private static final long GIVE_UP_MS = 25000;

    /** Set while a run is in progress: 0 none, 1 on Play's manage page, 2 on its Updates page. */
    private static volatile int step;
    private static volatile long startedAt;

    private Updater() {}

    static void updateAll(Context c) {
        WindowWatcher w = WindowWatcher.self();
        if (w == null) {
            Toaster.show(c, "Press Update all in Google Play");
        }
        try {
            c.startActivity(new Intent("com.google.android.finsky.VIEW_MY_DOWNLOADS").setPackage(PLAY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        } catch (RuntimeException ex) {
            Log.w(TAG, "Play's updates page did not open", ex);
            Toaster.show(c, "Google Play is not available on this TV");
            return;
        }
        if (w == null) return;
        step = 1;
        startedAt = SystemClock.uptimeMillis();
        watchContent(w, true);
    }

    /** Called by the accessibility service for every event while a run is in progress. */
    static void onEvent(WindowWatcher w, AccessibilityEvent e) {
        if (step == 0) return;
        if (SystemClock.uptimeMillis() - startedAt > GIVE_UP_MS) {
            Log.w(TAG, "update all: Play did not show the expected buttons in time");
            finish(w, null);
            return;
        }
        if (e.getPackageName() == null || !PLAY.contentEquals(e.getPackageName())) return;
        AccessibilityNodeInfo root = w.getRootInActiveWindow();
        if (root == null) return;
        if (step == 1) {
            if (press(root, "Update all")) { // Play may open straight on the Updates page
                finish(w, "Updating all apps. Play keeps going in the background.");
            } else if (press(root, "Updates")) {
                step = 2;
            } else if (!root.findAccessibilityNodeInfosByText("No updates").isEmpty()) {
                finish(w, "All apps are up to date");
            }
        } else if (press(root, "Update all")) {
            finish(w, "Updating all apps. Play keeps going in the background.");
        } else if (!root.findAccessibilityNodeInfosByText("up to date").isEmpty()
            || !root.findAccessibilityNodeInfosByText("No updates").isEmpty()) {
            finish(w, "All apps are up to date");
        }
    }

    private static void finish(WindowWatcher w, String message) {
        step = 0;
        watchContent(w, false);
        if (message != null) Toaster.show(w, message);
    }

    /** Presses the first on-screen control whose text is exactly this. */
    private static boolean press(AccessibilityNodeInfo root, String text) {
        List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByText(text);
        for (AccessibilityNodeInfo n : found) {
            CharSequence t = n.getText();
            if (t == null || !text.contentEquals(t)) continue;
            for (AccessibilityNodeInfo p = n; p != null; p = p.getParent()) {
                if (p.isClickable()) {
                    Log.i(TAG, "update all: pressed " + text);
                    return p.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                }
                if (p.isFocusable()) {
                    // Leanback rows take OK on the focused item rather than a click.
                    p.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                    Shell.send(WindowWatcher.self(), "key 23");
                    return true;
                }
            }
        }
        return false;
    }

    /** Content-change events are only asked for during a run, so the TV is not flooded with them otherwise. */
    private static void watchContent(WindowWatcher w, boolean on) {
        AccessibilityServiceInfo info = w.getServiceInfo();
        if (info == null) return;
        int content = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
        info.eventTypes = on ? info.eventTypes | content : info.eventTypes & ~content;
        w.setServiceInfo(info);
    }
}
