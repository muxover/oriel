package com.muxover.oriel.home;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Files sent from the phone that are not photos, videos or songs (documents, archives, app packages): kept in
 * the TV's Download/Oriel folder, where file managers and other apps find them, and opened in the app made for
 * them when the TV has one.
 */
final class Received {
    private static final String TAG = "Oriel";
    private static final String FOLDER = "Download/Oriel";

    private Received() {}

    /** A file name safe to store, from the name the phone gave. */
    static String clean(String name, String type) {
        String n = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (n.isEmpty() || n.startsWith(".")) n = "sent-" + System.currentTimeMillis() + n;
        if (n.length() > 120) n = n.substring(n.length() - 120);
        if (!n.contains(".") && type.contains("/")) n = n + "." + type.substring(type.indexOf('/') + 1).replaceAll("[^a-z0-9]", "");
        return n;
    }

    /** The song or video name shown while it plays: the file name without its extension. */
    static String title(String name) {
        String n = name == null ? "" : name.trim();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n.isEmpty() ? "From your phone" : n;
    }

    static Uri save(Context c, InputStream in, long length, String type, String name) throws IOException {
        ContentResolver r = c.getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, clean(name, type));
        v.put(MediaStore.MediaColumns.MIME_TYPE, type);
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, FOLDER);
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new IOException("the TV's storage did not take a new file");
        byte[] buf = new byte[64 * 1024];
        long left = length;
        try (OutputStream o = r.openOutputStream(uri)) {
            if (o == null) throw new IOException("cannot write " + uri);
            while (left > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) throw new IOException("upload ended early");
                o.write(buf, 0, n);
                left -= n;
            }
        } catch (IOException ex) {
            if (r.delete(uri, null, null) == 0) Log.w(TAG, "could not remove the half-sent " + uri);
            throw ex;
        }
        v.clear();
        v.put(MediaStore.MediaColumns.IS_PENDING, 0);
        r.update(uri, v, null, null);
        return uri;
    }

    /** Opens the file in the app made for it, or says where it was kept. Main thread. */
    static void open(Context c, Uri uri, String type) {
        Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, type)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        ResolveInfo app = c.getPackageManager().resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY);
        if (app == null) {
            Toaster.show(c, "Saved in Downloads, in the Oriel folder");
            return;
        }
        try {
            c.startActivity(i);
        } catch (RuntimeException ex) {
            Log.w(TAG, "could not open " + uri, ex);
            Toaster.show(c, "Saved in Downloads, in the Oriel folder");
        }
    }
}
