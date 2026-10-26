package com.muxover.oriel.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Downloads each app's official 512 px icon from its Google Play page once, and keeps it on the TV. */
final class IconFetcher {
    private static final String TAG = "Oriel";
    private static final Pattern OG_IMAGE = Pattern.compile(
        "property=\"og:image\"\\s+content=\"(https://play-lh\\.googleusercontent\\.com/[^\"=]+)");
    private static final Pattern DESCRIPTION = Pattern.compile("<meta name=\"description\"[^>]*?content=\"([^\"]+)\"");
    private static boolean busy;

    private IconFetcher() {}

    private static File file(Context c, String pkg) {
        return new File(new File(c.getFilesDir(), "icons"), pkg + ".png");
    }

    private static File textFile(Context c, String pkg) {
        return new File(new File(c.getFilesDir(), "icons"), pkg + ".play");
    }

    /** How long a saved description is used before it is read again from Google Play. */
    private static final long TEXT_FRESH_MS = 30L * 86400000L;

    /**
     * What Google Play says about an app: the developer's one-line summary first, then up to three short
     * sentences from the start of the full description. Null when nothing was saved yet.
     */
    static java.util.List<String> text(Context c, String pkg) {
        File f = textFile(c, pkg);
        if (!f.exists()) return null;
        try {
            String all = new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            java.util.List<String> out = new ArrayList<>(java.util.Arrays.asList(all.split("\n", -1)));
            for (int i = 0; i < out.size(); i++) out.set(i, sentence(out.get(i).trim()));
            return out;
        } catch (IOException ex) {
            Log.w(TAG, "unreadable description for " + pkg, ex);
            return null;
        }
    }

    /** Every line on home ends like a sentence, as Oriel's own do. */
    private static String sentence(String t) {
        if (t.isEmpty()) return t;
        char last = t.charAt(t.length() - 1);
        return last == '.' || last == '!' || last == '?' ? t : t + ".";
    }

    /** True when the icon is not on the TV yet, or the description is missing or a month old. */
    static boolean missing(Context c, String pkg) {
        File t = textFile(c, pkg);
        return !file(c, pkg).exists() || !t.exists() || System.currentTimeMillis() - t.lastModified() > TEXT_FRESH_MS;
    }

    static Drawable cached(Context c, String pkg) {
        File f = file(c, pkg);
        if (!f.exists()) return null;
        Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath());
        if (b == null) {
            Log.w(TAG, "unreadable icon file for " + pkg);
            return null;
        }
        return new BitmapDrawable(c.getResources(), b);
    }

    /** Fetches icons for the given packages that are not on disk yet; runs done on the main thread if any arrived. */
    static synchronized void fetchMissing(final Context c, List<String> pkgs, final Runnable done) {
        if (busy) return;
        final List<String> todo = new ArrayList<>();
        for (String p : pkgs) {
            File none = new File(file(c, p).getPath() + ".none");
            // A package Google Play has no page for is retried once a week, not on every start.
            if (missing(c, p) && !(none.exists() && System.currentTimeMillis() - none.lastModified() < 7L * 86400000L)) todo.add(p);
        }
        if (todo.isEmpty()) return;
        busy = true;
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            int got = 0;
            for (String p : todo) {
                try {
                    if (fetch(app, p)) got++;
                } catch (IOException ex) {
                    Log.w(TAG, "icon fetch failed for " + p + ": " + ex.getMessage());
                    File none = new File(file(app, p).getPath() + ".none");
                    try {
                        if (!none.getParentFile().isDirectory() && !none.getParentFile().mkdirs()) Log.w(TAG, "no icon folder");
                        if (!none.createNewFile() && !none.setLastModified(System.currentTimeMillis())) Log.w(TAG, "could not mark " + p);
                    } catch (IOException ex2) {
                        Log.w(TAG, "could not mark " + p, ex2);
                    }
                }
            }
            synchronized (IconFetcher.class) {
                busy = false;
            }
            if (got > 0) new Handler(Looper.getMainLooper()).post(done);
        }, "icon-fetch").start();
    }

    private static boolean fetch(Context c, String pkg) throws IOException {
        String page = new String(get("https://play.google.com/store/apps/details?id=" + pkg + "&hl=en"), StandardCharsets.UTF_8);
        saveText(c, pkg, page);
        if (file(c, pkg).exists()) return true;
        Matcher m = OG_IMAGE.matcher(page);
        if (!m.find()) {
            Log.i(TAG, "no store icon for " + pkg);
            return false;
        }
        byte[] img = get(m.group(1) + "=s512");
        Bitmap b = BitmapFactory.decodeByteArray(img, 0, img.length);
        if (b == null) {
            Log.w(TAG, "store icon not decodable for " + pkg);
            return false;
        }
        File f = file(c, pkg);
        File dir = f.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        if (!tmp.renameTo(f)) throw new IOException("cannot save " + f);
        Log.i(TAG, "store icon saved for " + pkg + " " + b.getWidth() + "x" + b.getHeight());
        return true;
    }

    private static final Pattern FULL = Pattern.compile("data-g-id=\"description\"[^>]*>(.*?)</div>", Pattern.DOTALL);
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?:])\\s+|\\n+");
    /** Openings that sell rather than say what the app is. */
    private static final String[] PITCH = {"welcome", "get a special", "available", "download", "looking for", "now available",
        "install", "they", "it's", "this is", "new:", "try "};

    /**
     * Saves the one-line summary and the first few short sentences of the full description, one per line. An
     * empty file means the page had none, so it is not fetched again until the month is up.
     */
    private static void saveText(Context c, String pkg, String page) throws IOException {
        StringBuilder out = new StringBuilder();
        Matcher m = DESCRIPTION.matcher(page);
        out.append(m.find() ? plain(m.group(1)) : "");
        Matcher full = FULL.matcher(page);
        if (full.find()) {
            String text = android.text.Html.fromHtml(full.group(1), android.text.Html.FROM_HTML_MODE_LEGACY).toString();
            int kept = 0;
            for (String part : SENTENCE_END.split(text.trim())) {
                if (kept == 3) break;
                String s = shorten(part.replaceAll("\\s+", " ").trim());
                if (!says(s)) continue;
                out.append('\n').append(s);
                kept++;
            }
        }
        File f = textFile(c, pkg);
        File dir = f.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write(out.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String plain(String html) {
        return android.text.Html.fromHtml(html, android.text.Html.FROM_HTML_MODE_LEGACY).toString().replaceAll("\\s+", " ").trim();
    }

    /** A long sentence cut at its first clause, so it fits two lines under the app's name. */
    private static String shorten(String s) {
        if (s.length() <= 100) return s;
        for (String cut : new String[]{" that ", " which ", " with ", " including ", " where ", " - ", ", "}) {
            int i = s.indexOf(cut);
            if (i > 20 && i <= 100) return s.substring(0, i).replaceAll("[,;:]+$", "") + ".";
        }
        return s;
    }

    /** A sentence that says what the app is: short enough, a full sentence, and not a sales opening. */
    private static boolean says(String s) {
        if (s.length() < 20 || s.length() > 100) return false;
        char last = s.charAt(s.length() - 1);
        if (last != '.' && last != '!') return false;
        if (s.equals(s.toUpperCase(java.util.Locale.ROOT))) return false;
        String l = s.toLowerCase(java.util.Locale.ROOT);
        for (String p : PITCH) if (l.startsWith(p)) return false;
        return true;
    }

    private static byte[] get(String url) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(8000);
        con.setReadTimeout(12000);
        con.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36");
        try {
            int code = con.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code + " for " + url);
            try (InputStream in = con.getInputStream(); ByteArrayOutputStream buf = new ByteArrayOutputStream()) {
                byte[] chunk = new byte[16384];
                int n;
                while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
                return buf.toByteArray();
            }
        } finally {
            con.disconnect();
        }
    }
}
