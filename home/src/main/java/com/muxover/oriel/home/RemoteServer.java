package com.muxover.oriel.home;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;

import com.muxover.oriel.home.R;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The phone remote: a small web page and the requests it sends, on port 8080. */
final class RemoteServer {
    static final int PORT = 8080;
    /** TV pixels moved per phone pixel dragged in mouse mode. */
    private static final float MOUSE_SPEED = 1.6f;
    /** The same for the virtual mouse, lower because Android speeds up fast moves itself. */
    private static final float HID_SPEED = 1.2f;
    /** Phone pixels dragged on the scroll strip per wheel notch. */
    private static final float WHEEL_STEP = 28f;
    private static final String TAG = "Oriel";
    private static final long MAX_FILE = 2L * 1024 * 1024 * 1024;
    private static final Map<String, Integer> KEYS = new HashMap<>();

    static {
        KEYS.put("up", KeyEvent.KEYCODE_DPAD_UP);
        KEYS.put("down", KeyEvent.KEYCODE_DPAD_DOWN);
        KEYS.put("left", KeyEvent.KEYCODE_DPAD_LEFT);
        KEYS.put("right", KeyEvent.KEYCODE_DPAD_RIGHT);
        KEYS.put("ok", KeyEvent.KEYCODE_DPAD_CENTER);
        KEYS.put("back", KeyEvent.KEYCODE_BACK);
        KEYS.put("home", KeyEvent.KEYCODE_HOME);
        KEYS.put("play", KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
        KEYS.put("volup", KeyEvent.KEYCODE_VOLUME_UP);
        KEYS.put("voldown", KeyEvent.KEYCODE_VOLUME_DOWN);
        KEYS.put("mute", KeyEvent.KEYCODE_VOLUME_MUTE);
        KEYS.put("power", KeyEvent.KEYCODE_POWER);
        KEYS.put("enter", KeyEvent.KEYCODE_ENTER);
        KEYS.put("del", KeyEvent.KEYCODE_DEL);
    }

    private static ServerSocket server;
    private static final ExecutorService pool = Executors.newFixedThreadPool(3);
    private static final Handler main = new Handler(Looper.getMainLooper());

    private RemoteServer() {}

    /** Starts or stops the server to match the setting. */
    static synchronized void sync(Context c) {
        final Context app = c.getApplicationContext();
        boolean want = Prefs.remoteOn(app);
        if (want && server == null) {
            try {
                server = new ServerSocket(PORT);
            } catch (IOException ex) {
                Log.e(TAG, "phone remote could not open port " + PORT, ex);
                return;
            }
            final ServerSocket s = server;
            Thread t = new Thread(() -> accept(app, s), "remote");
            t.setDaemon(true);
            t.start();
            Shell.start(app);
            Log.i(TAG, "phone remote on port " + PORT);
        } else if (!want && server != null) {
            try {
                server.close();
            } catch (IOException ex) {
                Log.w(TAG, "phone remote close", ex);
            }
            server = null;
        }
    }

    @SuppressWarnings("deprecation")
    static String address(Context c) {
        WifiManager w = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        int ip = w != null && w.getConnectionInfo() != null ? w.getConnectionInfo().getIpAddress() : 0;
        if (ip == 0) return null;
        return String.format(Locale.US, "%d.%d.%d.%d", ip & 0xff, (ip >> 8) & 0xff, (ip >> 16) & 0xff, (ip >> 24) & 0xff);
    }

    private static void accept(Context c, ServerSocket s) {
        while (!s.isClosed()) {
            try {
                final Socket sock = s.accept();
                pool.execute(() -> handle(c, sock));
            } catch (SocketException ex) {
                if (!s.isClosed()) Log.w(TAG, "phone remote socket", ex);
            } catch (IOException ex) {
                Log.w(TAG, "phone remote accept", ex);
            }
        }
    }

    private static void handle(Context c, Socket sock) {
        try (Socket so = sock) {
            so.setSoTimeout(60000);
            InputStream in = new BufferedInputStream(so.getInputStream());
            String request = line(in);
            if (request == null) return;
            Map<String, String> headers = new HashMap<>();
            String h;
            while ((h = line(in)) != null && !h.isEmpty()) {
                int i = h.indexOf(':');
                if (i > 0) headers.put(h.substring(0, i).trim().toLowerCase(Locale.US), h.substring(i + 1).trim());
            }
            String[] parts = request.split(" ");
            if (parts.length < 2) return;
            String method = parts[0];
            String path = parts[1];
            Map<String, String> query = new HashMap<>();
            int q = path.indexOf('?');
            if (q >= 0) {
                for (String kv : path.substring(q + 1).split("&")) {
                    int e = kv.indexOf('=');
                    if (e > 0) query.put(kv.substring(0, e), URLDecoder.decode(kv.substring(e + 1), "UTF-8"));
                }
                path = path.substring(0, q);
            }
            long length = headers.containsKey("content-length") ? Long.parseLong(headers.get("content-length")) : 0;
            OutputStream out = so.getOutputStream();
            if (path.equals("/bye")) {
                InputHub.gone();
                reply(out, 204, null, null);
                return;
            }
            InputHub.seen();

            if (method.equals("GET") && path.equals("/")) {
                reply(out, 200, "text/html; charset=utf-8", page(c));
            } else if (method.equals("GET") && path.equals("/state")) {
                reply(out, 200, "application/json", state(c).getBytes(StandardCharsets.UTF_8));
            } else if (method.equals("POST") && path.equals("/key")) {
                Integer code = KEYS.get(query.get("k"));
                if (code == null) {
                    reply(out, 400, "text/plain", "unknown key".getBytes(StandardCharsets.UTF_8));
                    return;
                }
                android.os.PowerManager pm = (android.os.PowerManager) c.getSystemService(Context.POWER_SERVICE);
                if (code == KeyEvent.KEYCODE_POWER && pm != null && pm.isInteractive()) {
                    main.post(() -> PowerActivity.open(c)); // Oriel's power screen, not the maker's
                } else if (code == KeyEvent.KEYCODE_ENTER && InputHub.submit()) {
                    Log.i(TAG, "phone sent the field's action");
                } else {
                    Shell.send(c, "key " + code);
                }
                reply(out, 204, null, null);
            } else if (method.equals("POST") && path.equals("/text")) {
                final String text = new String(body(in, length), StandardCharsets.UTF_8);
                if ("1".equals(query.get("fresh"))) main.post(() -> keyed = "");
                else main.post(() -> type(c, text));
                reply(out, 204, null, null);
            } else if (method.equals("POST") && path.equals("/open")) {
                final String url = new String(body(in, length), StandardCharsets.UTF_8).trim();
                main.post(() -> openLink(c, url));
                reply(out, 204, null, null);
            } else if (method.equals("POST") && path.equals("/file")) {
                if (length <= 0 || length > MAX_FILE) {
                    reply(out, 413, "text/plain", "file too big".getBytes(StandardCharsets.UTF_8));
                    return;
                }
                String type = headers.containsKey("content-type") ? headers.get("content-type") : "application/octet-stream";
                String name = query.containsKey("name") ? query.get("name") : "";
                boolean open = !"0".equals(query.get("open"));
                if (type.startsWith("image/") || type.startsWith("video/") || type.startsWith("audio/")) {
                    final File f = save(c, in, length, type);
                    final boolean moving = !type.startsWith("image/");
                    final String title = type.startsWith("audio/") ? Received.title(name) : null;
                    if (open) main.post(() -> {
                        if (title != null) ViewerActivity.play(c, f, title);
                        else ViewerActivity.show(c, f, moving);
                    });
                    reply(out, 204, null, null);
                } else {
                    final Uri saved = Received.save(c, in, length, type, name);
                    if (open) main.post(() -> Received.open(c, saved, type));
                    reply(out, 204, null, null);
                }
            } else if (method.equals("POST") && path.equals("/mouse")) {
                final float dx = number(query.get("dx")), dy = number(query.get("dy"));
                if (hid()) {
                    Shell.send(c, "move " + Math.round(dx * HID_SPEED) + " " + Math.round(dy * HID_SPEED));
                } else {
                    main.post(() -> Pointer.move(c, dx * MOUSE_SPEED, dy * MOUSE_SPEED));
                }
                reply(out, 204, null, null);
            } else if (method.equals("POST") && path.equals("/click")) {
                String which = "right".equals(query.get("b")) ? "right" : "left";
                if (hid()) {
                    Shell.send(c, "button " + which + " click");
                } else if (which.equals("right")) {
                    Shell.send(c, "key " + KeyEvent.KEYCODE_BACK);
                } else {
                    main.post(() -> Pointer.click(c));
                }
                reply(out, 204, null, null);
            } else if (method.equals("POST") && path.equals("/scroll")) {
                final float dy = number(query.get("dy"));
                if (hid()) {
                    int notches = wheel(dy);
                    if (notches != 0) Shell.send(c, "wheel " + notches);
                } else {
                    main.post(() -> Pointer.scroll(c, dy * MOUSE_SPEED * 2));
                }
                reply(out, 204, null, null);
            } else {
                reply(out, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException | RuntimeException ex) {
            Log.w(TAG, "phone remote request failed", ex);
        }
    }

    /** A query number, or 0 when it is missing or not a number. */
    private static float number(String v) {
        if (v == null) return 0;
        try {
            float f = Float.parseFloat(v);
            return Float.isNaN(f) || Math.abs(f) > 4000 ? 0 : f;
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    /** An app's name as the TV shows it, or "" when the package is unknown. */
    private static String appName(Context c, String pkg) {
        if (pkg.isEmpty() || pkg.equals(c.getPackageName())) return "";
        try {
            return c.getPackageManager().getApplicationLabel(c.getPackageManager().getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException ex) {
            return "";
        }
    }

    /** The virtual mouse, unless this TV has no root helper or the helper said it cannot make one. Moves sent while
     * the helper is still starting wait in its queue rather than going to the drawn pointer. */
    private static boolean hid() {
        return Shell.mouse && !Shell.unavailable;
    }

    private static float wheelLeft;

    /** Wheel notches for a drag on the scroll strip, keeping the remainder; dragging up scrolls down, like a finger. */
    private static synchronized int wheel(float dy) {
        wheelLeft += dy;
        int n = (int) (wheelLeft / WHEEL_STEP);
        wheelLeft -= n * WHEEL_STEP;
        return n;
    }

    /**
     * Typed text goes into the field the Oriel keyboard is attached to, in any app; with another keyboard in use,
     * into the focused text box found through accessibility, else it is typed key by key.
     */
    private static void type(Context c, String text) {
        if (InputHub.setText(text) || WindowWatcher.setFocusedText(text)) {
            keyed = "";
            return;
        }
        // Apps with their own on-screen keyboard (no text field Android knows of) get key presses. The phone sends
        // its whole box on every change, so only the difference is pressed: Delete for what went, then what is new.
        long now = android.os.SystemClock.uptimeMillis();
        if (now - keyedAt > KEYED_FORGET_MS) keyed = "";
        keyedAt = now;
        int same = 0, max = Math.min(keyed.length(), text.length());
        while (same < max && keyed.charAt(same) == text.charAt(same)) same++;
        for (int i = keyed.length(); i > same; i--) Shell.send(c, "key " + KeyEvent.KEYCODE_DEL);
        if (same < text.length()) Shell.send(c, "text " + text.substring(same));
        keyed = text;
    }

    /** What the phone's box last typed key by key, and when; forgotten after a pause or a fresh box. */
    private static String keyed = "";
    private static long keyedAt;
    private static final long KEYED_FORGET_MS = 30000;

    /**
     * Opens a sent link in the app made for it when one claims it (a video site's own app, say), else in the
     * browser. Android's app picker is never shown: it is hard to use from a remote.
     */
    private static void openLink(Context c, String url) {
        String low = url.toLowerCase(Locale.US);
        if (!low.startsWith("http://") && !low.startsWith("https://")) url = "https://" + url;
        Uri uri = Uri.parse(url);
        PackageManager pm = c.getPackageManager();
        Intent i = new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Intent web = new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).addCategory(Intent.CATEGORY_BROWSABLE);
        java.util.Set<String> browsers = new java.util.HashSet<>();
        for (ResolveInfo r : pm.queryIntentActivities(web, PackageManager.MATCH_ALL)) browsers.add(r.activityInfo.packageName);
        String pick = null;
        for (ResolveInfo r : pm.queryIntentActivities(i, PackageManager.MATCH_ALL)) {
            String p = r.activityInfo.packageName;
            if (!browsers.contains(p) && !p.equals("android")) {
                pick = p;
                break;
            }
        }
        if (pick == null) {
            ResolveInfo def = pm.resolveActivity(web, PackageManager.MATCH_DEFAULT_ONLY);
            if (def != null && def.activityInfo != null && !"android".equals(def.activityInfo.packageName)) pick = def.activityInfo.packageName;
            else if (!browsers.isEmpty()) pick = browsers.iterator().next();
        }
        if (pick != null) i.setPackage(pick);
        try {
            c.startActivity(i);
        } catch (RuntimeException ex) {
            Log.e(TAG, "nothing on the TV opens " + url, ex);
            Toaster.show(c, "No app on this TV opens that link");
        }
    }

    private static String state(Context c) {
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        int vol = am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        String title = "";
        MediaController m = MediaWatcher.playing();
        if (m != null && m.getMetadata() != null) {
            CharSequence t = m.getMetadata().getText(MediaMetadata.METADATA_KEY_TITLE);
            if (t != null) title = t.toString();
        }
        InputHub.Field f = InputHub.field();
        String field = f == null ? "null" : "{\"id\":" + f.id + ",\"text\":\"" + json(f.secret ? "" : f.text) + "\",\"hint\":\""
            + json(f.hint) + "\",\"action\":\"" + json(f.action) + "\",\"app\":\"" + json(appName(c, f.app)) + "\",\"secret\":" + f.secret + "}";
        return "{\"volume\":" + vol + ",\"name\":\"" + json(Device.name(c)) + "\",\"playing\":\"" + json(title)
            + "\",\"field\":" + field + "}";
    }

    private static String json(String v) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < v.length(); i++) {
            char ch = v.charAt(i);
            if (ch == '"' || ch == '\\') b.append('\\').append(ch);
            else if (ch < 0x20) b.append(String.format(Locale.US, "\\u%04x", (int) ch));
            else b.append(ch);
        }
        return b.toString();
    }

    private static File save(Context c, InputStream in, long length, String type) throws IOException {
        File dir = new File(c.getCacheDir(), "sent");
        // Earlier sends are cleared after a while, not at once, so a few photos sent together all arrive.
        File[] old = dir.listFiles();
        long cutoff = System.currentTimeMillis() - 30 * 60 * 1000;
        if (old != null) {
            for (File f : old) {
                if (f.lastModified() < cutoff && !f.delete()) Log.w(TAG, "could not clear " + f);
            }
        }
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot make " + dir);
        String ext = type.contains("/") ? type.substring(type.indexOf('/') + 1).replaceAll("[^a-z0-9]", "") : "bin";
        File f = new File(dir, "sent-" + System.currentTimeMillis() + "." + ext);
        byte[] buf = new byte[64 * 1024];
        long left = length;
        try (FileOutputStream o = new FileOutputStream(f)) {
            while (left > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) throw new IOException("upload ended early");
                o.write(buf, 0, n);
                left -= n;
            }
        }
        return f;
    }

    private static byte[] body(InputStream in, long length) throws IOException {
        if (length > 64 * 1024) throw new IOException("body too long");
        byte[] b = new byte[(int) length];
        int off = 0;
        while (off < b.length) {
            int n = in.read(b, off, b.length - off);
            if (n < 0) throw new IOException("body ended early");
            off += n;
        }
        return b;
    }

    private static byte[] cachedPage;

    private static synchronized byte[] page(Context c) throws IOException {
        if (cachedPage != null) return cachedPage;
        try (InputStream in = c.getResources().openRawResource(R.raw.remote)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            cachedPage = b.toByteArray();
        }
        return cachedPage;
    }

    private static String line(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int ch;
        while ((ch = in.read()) >= 0) {
            if (ch == '\n') return sb.toString();
            if (ch != '\r') sb.append((char) ch);
            if (sb.length() > 8192) throw new IOException("header line too long");
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    private static void reply(OutputStream out, int code, String type, byte[] body) throws IOException {
        StringBuilder h = new StringBuilder("HTTP/1.1 ").append(code).append(code == 200 ? " OK" : code == 204 ? " No Content" : " Error").append("\r\n");
        h.append("Connection: close\r\nCache-Control: no-store\r\n");
        if (type != null) h.append("Content-Type: ").append(type).append("\r\n");
        h.append("Content-Length: ").append(body == null ? 0 : body.length).append("\r\n\r\n");
        out.write(h.toString().getBytes(StandardCharsets.US_ASCII));
        if (body != null) out.write(body);
        out.flush();
    }
}
