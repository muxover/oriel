package com.muxover.oriel.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Pictures for the media browser: a frame from a tenth of the way into each video, a small copy of each photo,
 * the cover art of each song. Made one at a time in the background, kept in memory and on the TV's storage so
 * a folder opened again shows them at once.
 */
final class Thumbs {
    private static final String TAG = "Oriel";
    private static final LruCache<String, Bitmap> MEM = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override
        protected int sizeOf(String k, Bitmap b) {
            return b.getByteCount();
        }
    };
    /** Durations in ms of videos and songs, by the same key. */
    private static final LruCache<String, Long> DURATIONS = new LruCache<>(2000);
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "thumbs");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** Raised when the browser leaves a folder, so work queued for it is skipped. */
    private static volatile int generation;

    private Thumbs() {}

    static void forget() {
        generation++;
    }

    /** Leaving Media: the pictures in memory go too (the copies on storage stay for next time). */
    static void release() {
        generation++;
        MEM.evictAll();
    }

    private static String key(File f) {
        return f.getAbsolutePath() + "|" + f.length() + "|" + f.lastModified();
    }

    /** Duration of a video or song already measured, or -1. */
    static long duration(File f) {
        Long d = DURATIONS.get(key(f));
        return d != null ? d : -1;
    }

    /**
     * Puts the picture for f into the view when it is ready; then calls done (main thread) so the tile can show
     * the duration. Nothing happens when the view was given another file in the meantime.
     */
    static void load(Context c, final File f, final int kind, final int w, final int h, final ImageView into, final Runnable done) {
        final String k = key(f);
        into.setTag(k);
        Bitmap hit = MEM.get(k);
        if (hit != null) {
            into.setImageBitmap(hit);
            if (done != null) done.run();
            return;
        }
        final File cache = new File(new File(c.getCacheDir(), "thumbs"), Integer.toHexString(k.hashCode()) + "-" + w + ".jpg");
        final int gen = generation;
        final Context app = c.getApplicationContext();
        WORK.execute(() -> {
            if (gen != generation) return;
            Bitmap b = null;
            try {
                if (cache.exists()) b = BitmapFactory.decodeFile(cache.getPath());
                if (b == null || (kind != MediaFiles.PHOTO && DURATIONS.get(k) == null)) {
                    Bitmap made = make(app, f, kind, w, h, k);
                    if (b == null) b = made;
                    if (made != null && !cache.exists()) save(made, cache);
                }
            } catch (RuntimeException ex) {
                Log.w(TAG, "no picture for " + f.getName(), ex);
            }
            final Bitmap result = b;
            if (result != null) MEM.put(k, result);
            MAIN.post(() -> {
                if (!k.equals(into.getTag())) return;
                if (result != null) {
                    into.setAlpha(0f);
                    into.setImageBitmap(result);
                    into.animate().alpha(1f).setDuration(Ui.ENTER_MS).start();
                }
                if (done != null) done.run();
            });
        });
    }

    /** The cover art of a song at full size, for the music player, or null. On a background thread. */
    static void art(final File f, final Consumer<Bitmap> done) {
        WORK.execute(() -> {
            Bitmap b = null;
            MediaMetadataRetriever r = new MediaMetadataRetriever();
            try {
                r.setDataSource(f.getAbsolutePath());
                byte[] pic = r.getEmbeddedPicture();
                if (pic != null) b = decodeSized(pic, 800);
            } catch (RuntimeException ex) {
                Log.w(TAG, "no cover art in " + f.getName(), ex);
            } finally {
                release(r);
            }
            final Bitmap out = b;
            MAIN.post(() -> done.accept(out));
        });
    }

    private static Bitmap make(Context c, File f, int kind, int w, int h, String k) {
        if (kind == MediaFiles.PHOTO) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= w && o.outHeight / (sample * 2) >= h) sample *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = sample;
            Bitmap b = BitmapFactory.decodeFile(f.getPath(), o);
            return b != null ? crop(c, b, w, h) : null;
        }
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(f.getAbsolutePath());
            String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            long dur = d != null ? Long.parseLong(d) : 0;
            DURATIONS.put(k, dur);
            if (kind == MediaFiles.MUSIC) {
                byte[] pic = r.getEmbeddedPicture();
                return pic != null ? crop(c, decodeSized(pic, w), w, h) : null;
            }
            // a tenth of the way in: past black openings and studio logos
            Bitmap frame = r.getScaledFrameAtTime(dur * 100L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, w, h);
            if (frame == null) frame = r.getFrameAtTime();
            return frame != null ? crop(c, frame, w, h) : null;
        } catch (RuntimeException ex) {
            Log.w(TAG, "could not read " + f.getName(), ex);
            return null;
        } finally {
            release(r);
        }
    }

    private static void release(MediaMetadataRetriever r) {
        try {
            r.release();
        } catch (IOException | RuntimeException ex) {
            Log.w(TAG, "retriever release failed", ex);
        }
    }

    private static Bitmap decodeSized(byte[] data, int max) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        int sample = 1;
        while (o.outWidth / (sample * 2) >= max && o.outHeight / (sample * 2) >= max) sample *= 2;
        o.inJustDecodeBounds = false;
        o.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(data, 0, data.length, o);
    }

    /** Scales and centre-crops to exactly w x h, so tiles get a sharp picture of their own size. */
    private static Bitmap crop(Context c, Bitmap src, int w, int h) {
        if (src == null) return null;
        Bitmap out = ((android.graphics.drawable.BitmapDrawable) Ui.sized(c, new android.graphics.drawable.BitmapDrawable(c.getResources(), src), w, h, true)).getBitmap();
        if (out != src) src.recycle();
        return out;
    }

    private static void save(Bitmap b, File to) {
        File dir = to.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "thumbnail folder not made");
            return;
        }
        try (FileOutputStream out = new FileOutputStream(to)) {
            b.compress(Bitmap.CompressFormat.JPEG, 86, out);
        } catch (IOException ex) {
            Log.w(TAG, "thumbnail not saved", ex);
        }
    }
}
