package com.muxover.oriel.home;

import android.os.Handler;
import android.os.Looper;

import java.util.HashMap;
import java.util.Map;

/**
 * The TV's picture and sound settings (HiSilicon boards). Android keeps the TV library out of normal apps,
 * so Oriel's root helper reads and changes them and reports the values back as "pic Name value ..." lines.
 * Without the helper, or on other boards, nothing ever arrives and the Settings page says so.
 */
final class TvPicture {
    static final String[] PICTURE_MODES = {"Standard", "Dynamic", "Soft", "User", "Vivid", "Natural", "Sports", "Eco"};
    static final String[] COLOUR_TEMPS = {"Normal", "Cool", "Warm"};
    static final String[] SOUND_MODES = {"Standard", "News", "Music", "Movie", "Sports", "User"};
    /** The values asked for, in the order the helper reports them. */
    static final String NAMES = "PictureMode Backlight Brightness Contrast Saturation Sharpness ColorTemp SoundMode";

    private static final Map<String, Integer> values = new HashMap<>();
    private static volatile boolean known, unavailable;
    private static Runnable listener;
    private static final Handler main = new Handler(Looper.getMainLooper());

    private TvPicture() {}

    /** True once the helper has reported values; false before that and on TVs without the controls. */
    static boolean known() {
        return known;
    }

    /** True when the helper answered that this TV has no such controls. */
    static boolean unavailable() {
        return unavailable;
    }

    /** Asks the helper for fresh values; onChange runs on the main thread when they arrive. */
    static void read(android.content.Context c, Runnable onChange) {
        listener = onChange;
        Shell.send(c, "pic get " + NAMES);
    }

    /** No answer in time (no root helper on this TV): show the page without the controls. */
    static void giveUp() {
        if (!known) unavailable = true;
    }

    static int get(String name) {
        synchronized (values) {
            Integer v = values.get(name);
            return v != null ? v : -1;
        }
    }

    static void set(android.content.Context c, String name, int value) {
        synchronized (values) {
            values.put(name, value);
        }
        Shell.send(c, "pic set " + name + " " + value);
    }

    /** A line from the helper: "pic Name value Name value ..." or "pic none". */
    static void onHelperLine(String line) {
        String[] p = line.trim().split("\\s+");
        if (p.length == 2 && p[1].equals("none")) {
            unavailable = true;
        } else {
            synchronized (values) {
                for (int i = 1; i + 1 < p.length; i += 2) {
                    try {
                        values.put(p[i], Integer.parseInt(p[i + 1]));
                    } catch (NumberFormatException ex) {
                        android.util.Log.w("Oriel", "helper sent a bad picture value: " + line);
                    }
                }
            }
            known = true;
        }
        Runnable l = listener;
        if (l != null) main.post(l);
    }
}
