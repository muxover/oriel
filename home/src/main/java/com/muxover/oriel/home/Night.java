package com.muxover.oriel.home;

import android.content.Context;
import android.graphics.PixelFormat;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;

import java.util.Calendar;

/**
 * Night mode: a warm, dim layer over everything during the night hours. It is one flat colour the
 * screen blends in, so it costs almost nothing, and it never takes the remote's input.
 */
final class Night {
    private static View layer;
    private static int shownStrength;

    private Night() {}

    /** Shows or hides the layer for the current time and settings. Safe to call often. */
    static void apply(Context c) {
        boolean want = Prefs.nightOn(c) && inWindow(Prefs.nightStart(c), Prefs.nightEnd(c));
        int strength = Prefs.nightStrength(c);
        if (want && layer != null && shownStrength == strength) return;
        if (!want && layer == null) return;
        WindowManager wm = (WindowManager) c.getApplicationContext().getSystemService(Context.WINDOW_SERVICE);
        if (layer != null) {
            try {
                wm.removeView(layer);
            } catch (IllegalArgumentException ex) {
                Log.w("Oriel", "night layer already gone", ex);
            }
            layer = null;
        }
        if (!want || !Settings.canDrawOverlays(c)) return;
        View v = new View(c.getApplicationContext());
        int[] alpha = {0, 0x2E, 0x4D, 0x70};
        v.setBackgroundColor((alpha[Math.max(1, Math.min(3, strength))] << 24) | 0x2A1400);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT);
        wm.addView(v, lp);
        layer = v;
        shownStrength = strength;
    }

    static boolean inWindow(int start, int end) {
        Calendar now = Calendar.getInstance();
        int m = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        return start <= end ? (m >= start && m < end) : (m >= start || m < end);
    }
}
