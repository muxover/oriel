package com.muxover.oriel.home;

import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Subtitle files (.srt) next to a video. Read by Oriel itself rather than the player, so files saved in the
 * older Arabic encoding (Windows-1256, common in downloaded subtitles) show correctly too.
 */
final class Subtitles {
    static final class Cue {
        final long start, end;
        final String text;

        Cue(long start, long end, String text) {
            this.start = start;
            this.end = end;
            this.text = text;
        }
    }

    private static final Pattern TIME = Pattern.compile(
        "(\\d+):(\\d{1,2}):(\\d{1,2})[,.](\\d{1,3})\\s*-->\\s*(\\d+):(\\d{1,2}):(\\d{1,2})[,.](\\d{1,3})");

    private Subtitles() {}

    /** .srt files whose name starts with the video's name, e.g. "Film.srt" and "Film.ar.srt". */
    static List<File> findFor(File video) {
        List<File> out = new ArrayList<>();
        File dir = video.getParentFile();
        File[] all = dir != null ? dir.listFiles() : null;
        if (all == null) return out;
        String base = MediaFiles.title(video.getName()).toLowerCase(Locale.ROOT);
        for (File f : all) {
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (n.endsWith(".srt") && n.startsWith(base)) out.add(f);
        }
        out.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return out;
    }

    /** The name shown in the menu: the part between the video's name and ".srt", or the file name. */
    static String label(File video, File sub) {
        String base = MediaFiles.title(video.getName());
        String n = sub.getName();
        String mid = n.length() > base.length() + 4 ? n.substring(base.length(), n.length() - 4).replaceAll("^[ ._-]+", "") : "";
        if (mid.isEmpty()) return "Subtitles";
        switch (mid.toLowerCase(Locale.ROOT)) {
            case "ar": case "ara": case "arabic": return "Arabic";
            case "en": case "eng": case "english": return "English";
            default: return mid;
        }
    }

    static List<Cue> read(File f) {
        List<Cue> cues = new ArrayList<>();
        String text;
        try {
            text = decode(Files.readAllBytes(f.toPath()));
        } catch (IOException ex) {
            Log.w("Oriel", "subtitles not readable: " + f, ex);
            return cues;
        }
        String[] blocks = text.replace("\r\n", "\n").replace('\r', '\n').split("\n\\s*\n");
        for (String b : blocks) {
            Matcher m = TIME.matcher(b);
            if (!m.find()) continue;
            String body = b.substring(m.end()).trim();
            body = body.replaceAll("\\{[^}]*\\}", "").replaceAll("</?[a-zA-Z][^>]*>", "").trim();
            if (body.isEmpty()) continue;
            cues.add(new Cue(ms(m, 1), ms(m, 5), body));
        }
        cues.sort((a, b) -> Long.compare(a.start, b.start));
        return cues;
    }

    /** The cue showing at a moment, or null. */
    static Cue at(List<Cue> cues, long pos) {
        int lo = 0, hi = cues.size() - 1, found = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (cues.get(mid).start <= pos) {
                found = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        // overlapping cues: the latest one that started and has not ended
        for (int i = found; i >= 0 && i > found - 4; i--) {
            Cue c = cues.get(i);
            if (c.end > pos) return c;
        }
        return null;
    }

    private static long ms(Matcher m, int g) {
        String frac = m.group(g + 3);
        while (frac.length() < 3) frac += "0";
        return ((Long.parseLong(m.group(g)) * 60 + Long.parseLong(m.group(g + 1))) * 60 + Long.parseLong(m.group(g + 2))) * 1000
            + Long.parseLong(frac);
    }

    /** UTF-8 (with or without a byte order mark) or UTF-16 when the file is that, else Windows-1256. */
    private static String decode(byte[] b) {
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE) return new String(b, 2, b.length - 2, StandardCharsets.UTF_16LE);
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF) return new String(b, 2, b.length - 2, StandardCharsets.UTF_16BE);
        int off = b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF ? 3 : 0;
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b, off, b.length - off)).toString();
        } catch (CharacterCodingException ex) {
            return new String(b, Charset.forName("windows-1256"));
        }
    }
}
