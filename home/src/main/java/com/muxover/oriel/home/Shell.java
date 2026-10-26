package com.muxover.oriel.home;

import android.content.Context;
import android.util.Log;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Talks to the TV's own ADB service on 127.0.0.1:5555 and keeps one helper process
 * (Injector, running as the shell user) alive. Through it the app can press remote keys,
 * type text and run shell commands, which an ordinary app cannot do.
 */
final class Shell {
    private static final String TAG = "Oriel";
    private static final int CNXN = 0x4e584e43, OPEN = 0x4e45504f, OKAY = 0x59414b4f, WRTE = 0x45545257, CLSE = 0x45534c43;
    private static final int LOCAL_ID = 1;
    /** Keeps the maker's remote-as-mouse mode off and the AirPlay receiver running (script pushed to the TV). */
    private static final String KEEPER = "sh setsid sh /data/local/tmp/tvkeeper.sh >/dev/null 2>&1 < /dev/null &";

    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static final LinkedBlockingQueue<String> lines = new LinkedBlockingQueue<>();
    private static volatile boolean running;
    /** True while the root helper is connected: TVs without root never get here. */
    static volatile boolean connected;
    /** True when the helper could not be started at all (no root, or ADB off): helper-only features step aside. */
    static volatile boolean unavailable;
    private static volatile boolean everConnected;
    /** False once the helper has said this TV cannot make a virtual mouse; mouse mode then draws its own pointer. */
    static volatile boolean mouse = true;
    /**
     * Starts Oriel's Windows mirroring receiver as root on boards that have the HiSilicon Wi-Fi Display engine, once
     * per Oriel build: a receiver from an older build is replaced. Oriel goes on the boot class path so the system's
     * libsink_jni.so and its libraries can load. The receiver keeps its own log; the engine's console output is dropped.
     */
    private static String windowsReceiver(String apk) {
        String t = "/data/local/tmp", match = "'app_process / com.muxover.oriel.home.WindowsReceive[r]'";
        // The class name is split through $c so this shell's own command line never matches the pattern.
        return "sh [ -f /system/lib/libsink_jni.so ] || exit 0; c=com.muxover.oriel.home.Windows; "
            + "if [ \"$(cat " + t + "/oriel-wfd.apk 2>/dev/null)\" = '" + apk + "' ] && pgrep -f " + match + " >/dev/null; then exit 0; fi; "
            + "pkill -f " + match + "; echo '" + apk + "' > " + t + "/oriel-wfd.apk; "
            + "BOOTCLASSPATH=$BOOTCLASSPATH:" + apk + " setsid app_process / ${c}Receiver >/dev/null 2>&1 < /dev/null &";
    }

    /** ADB lets one packet be in flight per stream: a permit means the last one was acknowledged. */
    private static final Semaphore ready = new Semaphore(0);

    private Shell() {}

    /** Queue one line for the helper: "key <code>", "text <utf8>" or "sh <command>". */
    static void send(Context c, String line) {
        if (lines.size() > 64) lines.clear();
        lines.offer(line);
        start(c);
    }

    /** The app, for the volume popup that helper events drive. */
    static volatile Context app;

    static synchronized void start(Context c) {
        app = c.getApplicationContext();
        if (running) return;
        running = true;
        final String apk = c.getApplicationInfo().sourceDir;
        worker.execute(() -> {
            try {
                session(apk);
            } catch (IOException | InterruptedException ex) {
                Log.w(TAG, "shell helper stopped", ex);
                if (!everConnected) unavailable = true;
            } finally {
                running = false;
                connected = false;
            }
        });
    }

    private static void session(String apk) throws IOException, InterruptedException {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", 5555), 3000);
            s.setTcpNoDelay(true);
            DataInputStream in = new DataInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            write(out, CNXN, 0x01000000, 256 * 1024, "host::\0".getBytes(StandardCharsets.US_ASCII));
            Msg m = read(in);
            if (m.cmd != CNXN) throw new IOException("adb answered " + Integer.toHexString(m.cmd) + " instead of connecting");
            String cmd = "exec:su 0 sh -c 'CLASSPATH=" + apk + " exec app_process / com.muxover.oriel.home.Injector'\0";
            write(out, OPEN, LOCAL_ID, 0, cmd.getBytes(StandardCharsets.UTF_8));
            m = read(in);
            if (m.cmd != OKAY) throw new IOException("adb refused the helper");
            int remote = m.arg0;
            Log.i(TAG, "shell helper connected");
            connected = true;
            everConnected = true;
            unavailable = false;
            ready.drainPermits();
            ready.release();
            s.setSoTimeout(0);
            lines.add(KEEPER);
            lines.add(windowsReceiver(apk));
            Thread reader = new Thread(() -> drain(in, out, remote), "shell-read");
            reader.setDaemon(true);
            reader.start();
            while (reader.isAlive()) {
                String line = lines.poll(30, TimeUnit.SECONDS);
                if (line == null) continue;
                if (!ready.tryAcquire(5, TimeUnit.SECONDS)) throw new IOException("shell helper stopped answering");
                synchronized (out) {
                    write(out, WRTE, LOCAL_ID, remote, (line.replace('\n', ' ') + "\n").getBytes(StandardCharsets.UTF_8));
                }
            }
        }
    }

    /** Reads what the helper prints and acknowledges every packet; ends when the stream closes. */
    private static void drain(DataInputStream in, OutputStream out, int remote) {
        try {
            while (true) {
                Msg m = read(in);
                if (m.cmd == OKAY) {
                    ready.release();
                } else if (m.cmd == WRTE) {
                    synchronized (out) {
                        write(out, OKAY, LOCAL_ID, remote, new byte[0]);
                    }
                    for (String line : new String(m.data, StandardCharsets.UTF_8).split("\n")) {
                        line = line.trim();
                        if (line.startsWith("vol ")) {
                            VolumeUi.event(line);
                        } else if (line.equals("mouse ok") || line.equals("mouse none")) {
                            mouse = line.equals("mouse ok");
                            Log.i(TAG, "virtual mouse " + (mouse ? "ready" : "not available, using the drawn pointer"));
                        } else if (line.startsWith("pic ")) {
                            TvPicture.onHelperLine(line);
                        } else if (!line.isEmpty()) {
                            Log.i(TAG, "helper: " + line);
                        }
                    }
                } else if (m.cmd == CLSE) {
                    Log.w(TAG, "shell helper closed");
                    return;
                }
            }
        } catch (IOException ex) {
            Log.w(TAG, "shell helper connection lost", ex);
        }
    }

    private static final class Msg {
        int cmd, arg0, arg1;
        byte[] data;
    }

    private static void write(OutputStream out, int cmd, int a0, int a1, byte[] data) throws IOException {
        int sum = 0;
        for (byte b : data) sum += b & 0xff;
        ByteBuffer b = ByteBuffer.allocate(24 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(cmd).putInt(a0).putInt(a1).putInt(data.length).putInt(sum).putInt(~cmd).put(data);
        out.write(b.array());
        out.flush();
    }

    private static Msg read(DataInputStream in) throws IOException {
        byte[] h = new byte[24];
        in.readFully(h);
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = b.getInt();
        m.arg0 = b.getInt();
        m.arg1 = b.getInt();
        int len = b.getInt();
        if (len < 0 || len > 1024 * 1024) throw new IOException("bad adb packet length " + len);
        m.data = new byte[len];
        in.readFully(m.data);
        return m;
    }
}
