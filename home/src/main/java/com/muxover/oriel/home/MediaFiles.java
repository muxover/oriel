package com.muxover.oriel.home;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** What kind of media a file is, and the media files of a folder in name order. */
final class MediaFiles {
    static final int VIDEO = 1, PHOTO = 2, MUSIC = 3;
    private static final Set<String> VIDEOS = new HashSet<>(Arrays.asList("mp4", "mkv", "avi", "mov", "m4v", "webm", "ts", "m2ts", "3gp", "wmv", "flv", "mpg", "mpeg"));
    private static final Set<String> PHOTOS = new HashSet<>(Arrays.asList("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic"));
    private static final Set<String> SONGS = new HashSet<>(Arrays.asList("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma"));

    private MediaFiles() {}

    /** VIDEO, PHOTO, MUSIC, or 0 for anything else. */
    static int kind(File f) {
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        if (dot < 0) return 0;
        String ext = n.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (VIDEOS.contains(ext)) return VIDEO;
        if (PHOTOS.contains(ext)) return PHOTO;
        if (SONGS.contains(ext)) return MUSIC;
        return 0;
    }

    /** Files of one kind next to f, in the order the browser shows them. */
    static ArrayList<String> siblings(File f, int kind) {
        ArrayList<String> out = new ArrayList<>();
        File dir = f.getParentFile();
        File[] all = dir != null ? dir.listFiles() : null;
        if (all == null) {
            out.add(f.getAbsolutePath());
            return out;
        }
        List<File> list = new ArrayList<>();
        for (File x : all) if (!x.getName().startsWith(".") && x.isFile() && kind(x) == kind) list.add(x);
        list.sort((a, b) -> naturalCompare(a.getName(), b.getName()));
        for (File x : list) out.add(x.getAbsolutePath());
        return out;
    }

    /** The name without its extension, for titles. */
    static String title(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** Name order where "Episode 2" comes before "Episode 10". */
    static int naturalCompare(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char x = a.charAt(i), y = b.charAt(j);
            if (Character.isDigit(x) && Character.isDigit(y)) {
                int si = i, sj = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
                while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
                String na = a.substring(si, i).replaceFirst("^0+(?=.)", ""), nb = b.substring(sj, j).replaceFirst("^0+(?=.)", "");
                if (na.length() != nb.length()) return na.length() - nb.length();
                int c = na.compareTo(nb);
                if (c != 0) return c;
            } else {
                int c = Character.compare(Character.toLowerCase(x), Character.toLowerCase(y));
                if (c != 0) return c;
                i++;
                j++;
            }
        }
        return (a.length() - i) - (b.length() - j);
    }

    /** "1:02:03" or "4:05". */
    static String time(long ms) {
        long s = Math.max(0, ms) / 1000;
        if (s >= 3600) return String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }
}
