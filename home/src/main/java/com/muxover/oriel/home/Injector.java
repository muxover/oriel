package com.muxover.oriel.home;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

/**
 * Runs as root through app_process, started by Shell. Reads one command per line on stdin:
 * "key <keycode>", "text <text>", "tap <x> <y>", "swipe <x1> <y1> <x2> <y2> <ms>", "sh <command>", and for the phone's
 * mouse "move <dx> <dy>", "button <left|right> <down|up|click>", "wheel <notches>". Also takes over the system volume
 * controller, so the maker's volume bar never shows, and prints "vol <stream> <flags>" for the app to draw its own.
 */
public final class Injector {
    private static Object inputManager;
    private static Method inject;

    private Injector() {}

    public static void main(String[] args) throws Exception {
        inputManager = Class.forName("android.hardware.input.InputManager").getMethod("getInstance").invoke(null);
        inject = inputManager.getClass().getMethod("injectInputEvent", InputEvent.class, int.class);
        KeyCharacterMap kcm = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);
        startVolumeController();
        say("ready");
        BufferedReader r = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = readLine(r)) != null) {
            try {
                if (line.startsWith("key ")) {
                    press(Integer.parseInt(line.substring(4).trim()));
                } else if (line.startsWith("text ")) {
                    KeyEvent[] events = kcm.getEvents(line.substring(5).toCharArray());
                    if (events == null) {
                        say("text has characters the keyboard map cannot type");
                        continue;
                    }
                    for (KeyEvent e : events) send(KeyEvent.changeTimeRepeat(e, SystemClock.uptimeMillis(), 0));
                } else if (line.startsWith("tap ")) {
                    String[] a = line.substring(4).trim().split(" ");
                    float x = Float.parseFloat(a[0]), y = Float.parseFloat(a[1]);
                    swipe(x, y, x, y, 40);
                } else if (line.startsWith("swipe ")) {
                    String[] a = line.substring(6).trim().split(" ");
                    swipe(Float.parseFloat(a[0]), Float.parseFloat(a[1]), Float.parseFloat(a[2]), Float.parseFloat(a[3]), Integer.parseInt(a[4]));
                } else if (line.startsWith("move ")) {
                    String[] a = line.substring(5).trim().split(" ");
                    Mouse.move(Integer.parseInt(a[0]), Integer.parseInt(a[1]));
                } else if (line.startsWith("button ")) {
                    String[] a = line.substring(7).trim().split(" ");
                    Mouse.button(a[0], a[1]);
                } else if (line.startsWith("wheel ")) {
                    Mouse.wheel(Integer.parseInt(line.substring(6).trim()));
                } else if (line.startsWith("pic ")) {
                    picture(line.substring(4).trim().split("\\s+"));
                } else if (line.startsWith("sh ")) {
                    Process p = new ProcessBuilder("sh", "-c", line.substring(3)).redirectErrorStream(true).start();
                    p.getInputStream().close();
                    p.waitFor();
                }
            } catch (Exception ex) {
                say("failed: " + line + ": " + ex);
            }
        }
    }

    private static synchronized void say(String s) {
        System.out.println(s);
        System.out.flush();
    }

    private static final String VC = "android.media.IVolumeController";

    /** Our IVolumeController, answering the AIDL transactions by hand (codes from framework.jar on this TV). */
    private static final Binder controller = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code == INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(VC);
                return true;
            }
            data.enforceInterface(VC);
            switch (code) {
                case 2: // volumeChanged(int streamType, int flags)
                    int stream = data.readInt();
                    say("vol " + stream + " " + data.readInt());
                    return true;
                case 3: // masterMuteChanged(int flags)
                    say("vol 3 " + data.readInt());
                    return true;
                case 1: // displaySafeVolumeWarning
                case 4: // setLayoutDirection
                case 5: // dismiss
                case 6: // setA11yMode
                    return true;
                default:
                    return false;
            }
        }
    };

    /** Registers our controller now and every ten seconds, in case the maker's TV service registers its own again. */
    private static void startVolumeController() {
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    IBinder audio = (IBinder) Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "audio");
                    Object svc = Class.forName("android.media.IAudioService$Stub").getMethod("asInterface", IBinder.class).invoke(null, audio);
                    Object proxy = Class.forName(VC + "$Stub").getMethod("asInterface", IBinder.class).invoke(null, controller);
                    svc.getClass().getMethod("setVolumeController", Class.forName(VC)).invoke(svc, proxy);
                    // Report our popup as always visible: AudioService then applies every press, held presses included,
                    // instead of swallowing presses while it thinks no volume UI is up.
                    svc.getClass().getMethod("notifyVolumeControllerVisible", Class.forName(VC), boolean.class).invoke(svc, proxy, true);
                    // The maker's TV service skips its mute badge while this is set (its only use besides channel scans).
                    Class.forName("android.os.SystemProperties").getMethod("set", String.class, String.class).invoke(null, "hikeen.prop.scanning", "true");
                } catch (Exception ex) {
                    say("volume controller: " + ex);
                }
                SystemClock.sleep(10000);
            }
        }, "volume");
        t.setDaemon(true);
        t.start();
    }

    private static void press(int code) throws Exception {
        long now = SystemClock.uptimeMillis();
        send(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD));
        send(new KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD));
    }

    /** A finger press from one point to another on the screen; the same point makes a tap. */
    private static void swipe(float x1, float y1, float x2, float y2, int ms) throws Exception {
        long down = SystemClock.uptimeMillis();
        touch(down, down, MotionEvent.ACTION_DOWN, x1, y1);
        int steps = Math.max(1, ms / 16);
        for (int i = 1; i <= steps; i++) {
            Thread.sleep(ms / steps);
            float t = i / (float) steps;
            touch(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, x1 + (x2 - x1) * t, y1 + (y2 - y1) * t);
        }
        touch(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x2, y2);
    }

    private static void touch(long down, long when, int action, float x, float y) throws Exception {
        MotionEvent e = MotionEvent.obtain(down, when, action, x, y, 0);
        e.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        send(e);
        e.recycle();
    }

    private static Object tvPicture, tvAudio;

    /**
     * "pic get Name..." or "pic set Name value": the TV's picture and sound settings through HiSilicon's TV
     * library, which loads here (a system process) though not in an app. Answers "pic Name value ..." or "pic none".
     */
    private static void picture(String[] a) {
        try {
            if (tvPicture == null) {
                Class<?> hitv = Class.forName("com.hisilicon.android.tvapi.HitvManager");
                Object m = hitv.getMethod("getInstance").invoke(null);
                tvPicture = hitv.getMethod("getPicture").invoke(m);
                tvAudio = hitv.getMethod("getAudio").invoke(m);
            }
            StringBuilder out = new StringBuilder("pic");
            if (a.length >= 3 && a[0].equals("set")) {
                Object t = a[1].equals("SoundMode") ? tvAudio : tvPicture;
                t.getClass().getMethod("set" + a[1], int.class).invoke(t, Integer.parseInt(a[2]));
                a = new String[]{"get", a[1]};
            }
            for (int i = 1; i < a.length; i++) {
                Object t = a[i].equals("SoundMode") ? tvAudio : tvPicture;
                out.append(' ').append(a[i]).append(' ').append(t.getClass().getMethod("get" + a[i]).invoke(t));
            }
            say(out.toString());
        } catch (Throwable ex) {
            say("pic none");
            say("picture controls not available: " + ex);
        }
    }

    /**
     * A virtual USB mouse, made with Android's own uinput tool (/system/bin/uinput). Android treats it like a
     * plugged-in mouse: it draws its own pointer and every app gets real mouse moves, clicks and wheel turns.
     * The device lasts as long as the tool's input stays open. Prints "mouse ok" or "mouse none".
     */
    private static final class Mouse {
        private static final int EV_SYN = 0, EV_KEY = 1, EV_REL = 2;
        private static final int BTN_LEFT = 272, BTN_RIGHT = 273, REL_X = 0, REL_Y = 1, REL_WHEEL = 8;
        private static final String NAME = "Oriel Remote";
        private static final String REGISTER = "{\"id\":1,\"command\":\"register\",\"name\":\"" + NAME + "\","
            + "\"vid\":4660,\"pid\":22136,\"bus\":\"usb\",\"configuration\":["
            + "{\"type\":100,\"data\":[" + EV_SYN + "," + EV_KEY + "," + EV_REL + "]},"
            + "{\"type\":101,\"data\":[" + BTN_LEFT + "," + BTN_RIGHT + ",274]},"
            + "{\"type\":102,\"data\":[" + REL_X + "," + REL_Y + "," + REL_WHEEL + "]}]}";
        private static Process tool;
        private static java.io.Writer to;
        private static int failures;

        static void move(int dx, int dy) throws Exception {
            if (dx == 0 && dy == 0) return;
            inject(EV_REL, REL_X, dx, EV_REL, REL_Y, dy, EV_SYN, 0, 0);
        }

        static void button(String which, String how) throws Exception {
            int code = which.equals("right") ? BTN_RIGHT : BTN_LEFT;
            if (!how.equals("up")) inject(EV_KEY, code, 1, EV_SYN, 0, 0);
            if (!how.equals("down")) inject(EV_KEY, code, 0, EV_SYN, 0, 0);
        }

        static void wheel(int notches) throws Exception {
            if (notches != 0) inject(EV_REL, REL_WHEEL, notches, EV_SYN, 0, 0);
        }

        private static void inject(int... events) throws Exception {
            if (!open()) throw new java.io.IOException("no virtual mouse");
            StringBuilder b = new StringBuilder("{\"id\":1,\"command\":\"inject\",\"events\":[");
            for (int i = 0; i < events.length; i++) b.append(i == 0 ? "" : ",").append(events[i]);
            to.write(b.append("]}\n").toString());
            to.flush();
        }

        /** True once Android lists a device of ours that was not there before (an old one may still be closing). */
        private static boolean listed(java.util.Set<Integer> before) {
            for (int id : InputDevice.getDeviceIds()) {
                if (before.contains(id)) continue;
                InputDevice d = InputDevice.getDevice(id);
                if (d != null && NAME.equals(d.getName())) return true;
            }
            return false;
        }

        private static boolean open() {
            if (tool != null && tool.isAlive()) return true;
            if (failures >= 3) return false;
            try {
                java.util.Set<Integer> before = new java.util.HashSet<>();
                for (int id : InputDevice.getDeviceIds()) before.add(id);
                tool = new ProcessBuilder("/system/bin/uinput", "-").redirectErrorStream(true).start();
                final Process p = tool;
                Thread t = new Thread(() -> {
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                        String l;
                        while ((l = r.readLine()) != null) say("mouse tool: " + l);
                    } catch (java.io.IOException ex) {
                        say("mouse tool output: " + ex);
                    }
                }, "mouse");
                t.setDaemon(true);
                t.start();
                to = new java.io.OutputStreamWriter(tool.getOutputStream(), StandardCharsets.UTF_8);
                to.write(REGISTER + "\n");
                to.flush();
                // Events sent before Android has opened the new device are lost, so wait until it lists it.
                long until = SystemClock.uptimeMillis() + 3000;
                while (!listed(before) && SystemClock.uptimeMillis() < until && tool.isAlive()) SystemClock.sleep(50);
                if (!tool.isAlive()) throw new java.io.IOException("uinput ended with " + tool.exitValue());
                if (!listed(before)) say("virtual mouse not listed by Android after 3 s, sending anyway");
                // Listed comes a little before Android routes the device's moves to its pointer.
                SystemClock.sleep(300);
                failures = 0;
                say("mouse ok");
                return true;
            } catch (java.io.IOException ex) {
                failures++;
                tool = null;
                say("mouse none");
                say("virtual mouse failed: " + ex);
                return false;
            }
        }
    }

    /** The next command, or null once Oriel is gone (its end of the pipe closes, which reads as an I/O error). */
    private static String readLine(BufferedReader r) {
        try {
            return r.readLine();
        } catch (java.io.IOException ex) {
            return null;
        }
    }

    private static void send(InputEvent e) throws Exception {
        inject.invoke(inputManager, e, 0);
    }
}
