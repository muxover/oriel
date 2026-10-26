package com.muxover.oriel.home;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Where input on the TV meets: whether the phone remote is open, and the text field being typed into in any app.
 * The Oriel keyboard reports each field it is attached to; the phone reads it from /state and writes into it,
 * so typing from the phone works in every app that takes text, and the TV keyboard steps aside while the phone
 * is open.
 */
final class InputHub {
    /** The phone page asks for /state every couple of seconds while it is open; this long without it means closed. */
    private static final long ONLINE_MS = 8000;
    private static final Handler main = new Handler(Looper.getMainLooper());

    interface Listener {
        /** Main thread. */
        void onRemoteChanged(boolean online);
    }

    /** A text field the Oriel keyboard is attached to. */
    static final class Field {
        final int id;
        final String text, hint, action, app;
        final boolean secret;

        Field(int id, String text, String hint, String action, String app, boolean secret) {
            this.id = id;
            this.text = text;
            this.hint = hint;
            this.action = action;
            this.app = app;
            this.secret = secret;
        }
    }

    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private static volatile long lastSeen = -ONLINE_MS;
    private static volatile boolean online;
    private static volatile Field field;
    private static volatile KeyboardService keyboard;
    private static int nextId = 1;

    private InputHub() {}

    static void listen(Listener l) {
        listeners.addIfAbsent(l);
    }

    static void unlisten(Listener l) {
        listeners.remove(l);
    }

    static boolean remoteOnline() {
        return online;
    }

    /** Any request from the phone page. */
    static void seen() {
        lastSeen = SystemClock.uptimeMillis();
        if (online) return;
        online = true;
        main.post(() -> {
            tell(true);
            main.removeCallbacks(check);
            main.postDelayed(check, ONLINE_MS);
        });
    }

    /** The page was closed or put away on the phone. */
    static void gone() {
        lastSeen = -ONLINE_MS;
        main.post(check);
    }

    private static final Runnable check = new Runnable() {
        @Override
        public void run() {
            long idle = SystemClock.uptimeMillis() - lastSeen;
            if (idle < ONLINE_MS) {
                main.postDelayed(this, ONLINE_MS - idle);
                return;
            }
            if (!online) return;
            online = false;
            tell(false);
        }
    };

    private static void tell(boolean on) {
        for (Listener l : listeners) l.onRemoteChanged(on);
    }

    static Field field() {
        return field;
    }

    /** The keyboard is attached to a new field; returns its id. Main thread. */
    static synchronized int attach(KeyboardService k, String text, String hint, String action, String app, boolean secret) {
        keyboard = k;
        int id = nextId++;
        field = new Field(id, text, hint, action, app, secret);
        return id;
    }

    /** The field's text changed on the TV (typed there, or set from the phone). */
    static synchronized void changed(int id, String text) {
        Field f = field;
        if (f == null || f.id != id) return;
        field = new Field(id, text, f.hint, f.action, f.app, f.secret);
    }

    static synchronized void detach(KeyboardService k) {
        if (keyboard != k) return;
        field = null;
    }

    static void keyboardGone(KeyboardService k) {
        if (keyboard == k) keyboard = null;
        detach(k);
    }

    /** The phone's text box, put into the TV's field. False when no app field is open to the keyboard. */
    static boolean setText(final String text) {
        final KeyboardService k = keyboard;
        if (k == null || field == null) return false;
        main.post(() -> k.replaceText(text));
        return true;
    }

    /** Enter from the phone: the field's own action (Search, Go, Send) when one is open. */
    static boolean submit() {
        final KeyboardService k = keyboard;
        if (k == null || field == null) return false;
        main.post(k::submit);
        return true;
    }
}
