package com.muxover.oriel.home;

import android.os.SystemClock;

import com.hisilicon.miracast.ndk.SinkNative;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Receives a Windows PC's screen (Win + K) with the TV's own Wi-Fi Display engine. Runs as root through
 * app_process with Oriel on the boot class path, so the system's libsink_jni.so can load. The TV keeps a Wi-Fi
 * Direct group on the router's channel, so it stays on the home Wi-Fi; a PC that asks to join is let in, given an
 * address, and the receiver plays its stream on the video plane under Oriel's transparent mirror screen.
 * Writes one line per state change to /data/local/tmp/oriel-wfd.log. Stops the session when the stop file appears.
 */
public final class WindowsReceiver {
    private static final String CLI = "/vendor/bin/wpa_cli";
    private static final String SOCKETS = "/data/vendor/wifi/wpa/sockets";
    private static final String TMP = "/data/local/tmp";
    private static final String LEASES = TMP + "/oriel-wfd.leases";
    private static final String LOG = TMP + "/oriel-wfd.log";
    static final String STOP = TMP + "/oriel-wfd-stop";
    private static final String GO_ADDR = "192.168.49.1";
    /** Wi-Fi Display device info: primary sink, session available, control port 7236, 50 Mbit/s. */
    private static final String WFD_INFO = "000600111c440032";
    private static final int SOURCE_PORT = 7236;
    /** Non-HDCP mode in /etc/miracast/hdcp.xml terms; the maker's mode 3 crashes when started from here. */
    private static final int HDCP_OFF = 1;

    private static SinkNative sink;
    private static volatile boolean playing;
    private static volatile String peer = "";
    private static Process dhcp;

    private WindowsReceiver() {}

    public static void main(String[] args) throws Exception {
        android.os.Looper.prepareMainLooper();
        new File(LOG).delete();
        System.load("/system/lib/libsink_jni.so");
        sink = new SinkNative(WindowsReceiver::onSinkEvent);
        say("ready");
        Thread stopWatch = new Thread(WindowsReceiver::watchStop, "wfd-stop");
        stopWatch.setDaemon(true);
        stopWatch.start();
        while (true) {
            try {
                serve();
            } catch (IOException | RuntimeException ex) {
                say("receiver error, restarting in 10 s: " + ex);
            }
            stopSession("restart");
            SystemClock.sleep(10000);
        }
    }

    /** Keeps the group up and answers Wi-Fi Direct events, read from wpa_supplicant's log, until the log ends. */
    private static void serve() throws IOException {
        String name = setting("device_name");
        cli("set", "device_name", name.isEmpty() ? "Oriel TV" : name);
        cli("set", "wifi_display", "1");
        cli("wfd_subelem_set", "0", WFD_INFO);
        startGroup();
        // A receiver that was stopped (Oriel updated, restarted) leaves its log reader behind, and every reader
        // wakes for every line the TV logs: only this one may run.
        killAll("logcat", "wpa_supplicant:I");
        Process events = new ProcessBuilder("logcat", "-v", "brief", "-T", "1", "-s", "wpa_supplicant:I").redirectErrorStream(true).start();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(events.getInputStream(), StandardCharsets.UTF_8))) {
            String l;
            while ((l = r.readLine()) != null) {
                if (l.contains("P2P-PROV-DISC-PBC-REQ")) {
                    peer = field(l, "name='", "'");
                    say("join request from " + peer);
                    cli("wps_pbc");
                } else if (l.contains("AP-STA-CONNECTED")) {
                    say("joined: " + peer);
                    new Thread(WindowsReceiver::startSession, "wfd-start").start();
                } else if (l.contains("AP-STA-DISCONNECTED")) {
                    stopSession("PC left");
                } else if (l.contains("P2P-GROUP-REMOVED")) {
                    say("group removed, starting it again");
                    startGroup();
                }
            }
        } finally {
            events.destroy();
        }
    }

    /** The group sits on the router's channel: this chip runs both on one channel only. */
    private static void startGroup() throws IOException {
        String freq = field(run(CLI, "-p", SOCKETS, "-i", "wlan0", "status"), "freq=", "\n");
        boolean fresh = !cli("status").contains("mode=P2P GO");
        if (fresh) {
            cli("p2p_group_add", freq.isEmpty() ? "" : "freq=" + freq);
            SystemClock.sleep(3000);
        }
        run("ip", "addr", "replace", GO_ADDR + "/24", "dev", "p2p0");
        if (fresh || dhcp == null || !dhcp.isAlive()) {
            // A new group is a new p2p0: a DHCP server bound to the old one spins at full CPU, and one left by an
            // earlier receiver (Oriel updated, receiver restarted) is still running. Only one may run.
            stopDhcp();
            dhcp = new ProcessBuilder("/system/bin/dnsmasq", "--keep-in-foreground", "--no-resolv", "--no-poll", "--port=0",
                "--interface=p2p0", "--bind-interfaces", "--dhcp-range=192.168.49.10,192.168.49.60,1h",
                "--dhcp-leasefile=" + LEASES, "--pid-file=" + TMP + "/oriel-wfd-dhcp.pid").redirectErrorStream(true).redirectOutput(new File(TMP + "/oriel-wfd-dhcp.log")).start();
        }
        say("group up on " + (freq.isEmpty() ? "the default channel" : freq + " MHz") + " as " + field(cli("get", "device_name") + "\n", "", "\n"));
    }

    /** Waits for the PC's address, then starts the receiver and Oriel's mirror screen. */
    private static void startSession() {
        String ip = "";
        for (int i = 0; i < 30 && ip.isEmpty(); i++) {
            ip = leasedAddress();
            if (ip.isEmpty()) SystemClock.sleep(500);
        }
        if (ip.isEmpty()) {
            say("the PC got no address");
            return;
        }
        synchronized (WindowsReceiver.class) {
            if (playing) return;
            playing = true;
            new File(STOP).delete();
            sink.releaseSink();
            sink.setHdcpMode(HDCP_OFF);
            sink.initSink(ip, SOURCE_PORT);
            sink.setAudioMode("AAC");
            sink.startSink();
        }
        say("receiving from " + ip);
        try {
            run("am", "start", "-n", "com.zeasn.launcher/com.muxover.oriel.home.WindowsMirrorActivity", "--es", "peer", peer);
        } catch (IOException ex) {
            say("could not open the mirror screen: " + ex);
        }
    }

    private static void stopSession(String why) {
        synchronized (WindowsReceiver.class) {
            if (!playing) return;
            playing = false;
            sink.stopSink();
            sink.releaseSink();
        }
        say("stopped: " + why);
        try {
            run("am", "broadcast", "-a", WindowsMirrorActivity.ACTION_END, "-p", "com.zeasn.launcher");
        } catch (IOException ex) {
            say("could not close the mirror screen: " + ex);
        }
    }

    private static void onSinkEvent(int what, int arg1, int arg2) {
        if (what == 3011) {
            sink.initPlayer();
            say("playing");
        } else if (what == 3001 || what == 3003 || what == 3301) {
            new Thread(() -> stopSession("receiver event " + what), "wfd-stop").start();
        }
    }

    /** Back on the mirror screen asks for a stop by creating the stop file through the root helper. */
    private static void watchStop() {
        while (true) {
            SystemClock.sleep(500);
            if (new File(STOP).delete()) stopSession("closed on the TV");
        }
    }

    private static String leasedAddress() {
        File f = new File(LEASES);
        if (!f.exists()) return "";
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String l, last = "";
            while ((l = r.readLine()) != null) {
                String[] p = l.split(" ");
                if (p.length > 2) last = p[2];
            }
            return last;
        } catch (IOException ex) {
            say("could not read the leases: " + ex);
            return "";
        }
    }

    private static String setting(String key) {
        try {
            String v = run("settings", "get", "global", key).trim();
            return v.equals("null") ? "" : v;
        } catch (IOException ex) {
            return "";
        }
    }

    private static String cli(String... args) throws IOException {
        String[] cmd = new String[args.length + 5];
        cmd[0] = CLI;
        cmd[1] = "-p";
        cmd[2] = SOCKETS;
        cmd[3] = "-i";
        cmd[4] = "p2p0";
        System.arraycopy(args, 0, cmd, 5, args.length);
        return run(cmd);
    }

    /** Stops every DHCP server on p2p0: this receiver's, and any an earlier receiver left behind. */
    private static void stopDhcp() {
        if (dhcp != null) dhcp.destroy();
        dhcp = null;
        killAll("dnsmasq", "--interface=p2p0");
        SystemClock.sleep(300);
    }

    /** Kills every process whose command line holds both words; returns how many. */
    private static int killAll(String program, String marker) {
        File[] procs = new File("/proc").listFiles();
        if (procs == null) return 0;
        int n = 0;
        for (File p : procs) {
            if (!p.getName().matches("\\d+")) continue;
            String cmd;
            try {
                cmd = new String(java.nio.file.Files.readAllBytes(new File(p, "cmdline").toPath()), StandardCharsets.UTF_8);
            } catch (IOException ex) {
                continue; // the process ended while the list was read
            }
            if (cmd.startsWith(program) || cmd.contains("/" + program + "\u0000")) {
                if (!cmd.contains(marker)) continue;
                android.os.Process.killProcess(Integer.parseInt(p.getName()));
                n++;
            }
        }
        if (n > 0) say("stopped " + n + " old " + program + " process" + (n == 1 ? "" : "es"));
        return n;
    }

    private static String run(String... cmd) throws IOException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        byte[] out = readAll(p);
        try {
            p.waitFor();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        return new String(out, StandardCharsets.UTF_8);
    }

    private static byte[] readAll(Process p) throws IOException {
        try (java.io.InputStream in = p.getInputStream()) {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toByteArray();
        }
    }

    /** The text after start up to end, or "" when start is not there. */
    private static String field(String s, String start, String end) {
        int i = s.indexOf(start);
        if (i < 0) return "";
        i += start.length();
        int j = s.indexOf(end, i);
        return (j < 0 ? s.substring(i) : s.substring(i, j)).trim();
    }

    /** One line in the receiver's log; stdout is left to the engine, which prints a line per bad packet. */
    private static synchronized void say(String s) {
        try (java.io.FileWriter w = new java.io.FileWriter(LOG, true)) {
            w.write(new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(new java.util.Date()) + " " + s + "\n");
        } catch (IOException ex) {
            System.err.println("receiver log: " + ex);
        }
    }
}
