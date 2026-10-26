package com.muxover.oriel.home;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Where each video was left, so it continues from there, and the list for Continue watching. */
final class Resume {
    /** Shorter than this into a video, or this close to its end, nothing is kept. */
    private static final long MIN_MS = 30_000, END_MS = 60_000;
    private static final int KEEP = 40;

    private Resume() {}

    private static SharedPreferences of(Context c) {
        return c.getSharedPreferences("resume", Context.MODE_PRIVATE);
    }

    static void save(Context c, String path, long pos, long dur) {
        if (dur <= 0) return;
        if (pos < MIN_MS || pos > dur - END_MS) {
            of(c).edit().remove(path).apply();
            return;
        }
        of(c).edit().putString(path, pos + "/" + dur + "/" + System.currentTimeMillis()).apply();
        trim(c);
    }

    static void clear(Context c, String path) {
        of(c).edit().remove(path).apply();
    }

    /** Saved position in ms, or 0. */
    static long position(Context c, String path) {
        long[] v = read(of(c).getString(path, null));
        return v != null ? v[0] : 0;
    }

    /** How far through, 0 to 1, or -1 when the video has no saved place. */
    static float progress(Context c, String path) {
        long[] v = read(of(c).getString(path, null));
        return v != null && v[1] > 0 ? v[0] / (float) v[1] : -1f;
    }

    /** Videos with a saved place that are still on the TV or a plugged-in drive, latest first. */
    static List<String> recent(Context c) {
        List<Object[]> all = new ArrayList<>();
        for (Map.Entry<String, ?> e : of(c).getAll().entrySet()) {
            long[] v = read(String.valueOf(e.getValue()));
            if (v != null && new File(e.getKey()).exists()) all.add(new Object[]{e.getKey(), v[2]});
        }
        all.sort((a, b) -> Long.compare((Long) b[1], (Long) a[1]));
        List<String> out = new ArrayList<>();
        for (Object[] o : all) out.add((String) o[0]);
        return out;
    }

    private static void trim(Context c) {
        Map<String, ?> all = of(c).getAll();
        if (all.size() <= KEEP) return;
        String oldest = null;
        long at = Long.MAX_VALUE;
        for (Map.Entry<String, ?> e : all.entrySet()) {
            long[] v = read(String.valueOf(e.getValue()));
            if (v == null || v[2] < at) {
                oldest = e.getKey();
                at = v == null ? 0 : v[2];
            }
        }
        if (oldest != null) of(c).edit().remove(oldest).apply();
    }

    private static long[] read(String s) {
        if (s == null) return null;
        String[] p = s.split("/");
        if (p.length != 3) return null;
        try {
            return new long[]{Long.parseLong(p[0]), Long.parseLong(p[1]), Long.parseLong(p[2])};
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
