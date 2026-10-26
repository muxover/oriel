package com.muxover.oriel.home;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;

/** The TV's name as phones and PCs see it. One name everywhere: AirPlay, Wi-Fi Direct, the phone remote. */
final class Device {
    private Device() {}

    static String name(Context c) {
        String saved = Prefs.of(c).getString("device_name", null);
        if (saved != null && !saved.trim().isEmpty()) return saved;
        String sys = Settings.Global.getString(c.getContentResolver(), "device_name");
        if (sys != null && !sys.trim().isEmpty()) return sys;
        return Build.MODEL != null && !Build.MODEL.trim().isEmpty() ? Build.MODEL : "Oriel TV";
    }

    /** Hands the saved name to the system again when it lost it (a rename made while the root helper was not running). */
    static void keepInSync(Context c) {
        String saved = Prefs.of(c).getString("device_name", null);
        if (saved == null || saved.trim().isEmpty()) return;
        if (!saved.equals(Settings.Global.getString(c.getContentResolver(), "device_name"))) rename(c, saved);
    }

    /** Saves a new name and hands it to the system, Wi-Fi Direct (Windows mirroring) and Oriel Mirror. */
    static void rename(Context c, String name) {
        String n = name.trim().replaceAll("[\\r\\n'\"\\\\]", "");
        if (n.isEmpty()) return;
        Prefs.of(c).edit().putString("device_name", n).apply();
        Sys.global(c, "device_name", n);
        Sys.global(c, "wifi_p2p_device_name", n);
        // The Windows receiver's Wi-Fi Direct group takes the new name at once, without a restart.
        Shell.send(c, "sh [ -S /data/vendor/wifi/wpa/sockets/p2p0 ] && /vendor/bin/wpa_cli -p /data/vendor/wifi/wpa/sockets -i p2p0 set device_name '" + n + "'");
        c.sendBroadcast(new Intent("io.github.jqssun.airplay.SET_SETTING")
            .setClassName("com.muxover.oriel.mirror", "io.github.jqssun.airplay.SettingsReceiver")
            .putExtra("key", "server_name").putExtra("value", n));
    }
}
