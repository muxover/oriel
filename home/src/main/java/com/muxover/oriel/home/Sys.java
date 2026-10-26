package com.muxover.oriel.home;

import android.app.ActivityManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * System jobs done with Oriel's own rights when it is installed as part of the TV's system (a privileged
 * app, granted the permissions in privapp-permissions-oriel.xml), and through the root helper otherwise.
 * Each call checks its permission first, so the same build works installed either way.
 */
final class Sys {
    private static final String TAG = "Oriel";

    private Sys() {}

    /** A system property, or "" when it is not set or cannot be read. */
    static String prop(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            return (String) sp.getMethod("get", String.class, String.class).invoke(null, key, "");
        } catch (ReflectiveOperationException | RuntimeException ex) {
            Log.w(TAG, "could not read " + key, ex);
            return "";
        }
    }

    static boolean granted(Context c, String permission) {
        return c.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    /** True when Oriel runs as a privileged system app. */
    static boolean system(Context c) {
        return granted(c, "android.permission.WRITE_SECURE_SETTINGS");
    }

    static void secure(Context c, String key, String value) {
        if (system(c) && Settings.Secure.putString(c.getContentResolver(), key, value)) return;
        Shell.send(c, "sh settings put secure " + key + " '" + value + "'");
    }

    static void global(Context c, String key, String value) {
        if (system(c) && Settings.Global.putString(c.getContentResolver(), key, value)) return;
        Shell.send(c, "sh settings put global " + key + " '" + value + "'");
    }

    /**
     * Android hides non-public methods of the TV's own HiSilicon libraries from apps. Oriel's HDMI and AV
     * viewer needs them, so their classes (and only those) are listed in Android's exemption setting. Applies
     * to processes started afterwards.
     */
    static void allowTvLibraries(Context c) {
        String key = "hidden_api_blacklist_exemptions", ours = "Lcom/hisilicon/";
        String now = Settings.Global.getString(c.getContentResolver(), key);
        if (now != null && (("," + now + ",").contains("," + ours + ","))) return;
        global(c, key, now == null || now.isEmpty() || now.equals("null") ? ours : now + "," + ours);
        Log.i(TAG, "TV libraries allowed for apps started from now on");
    }

    /** Makes the keyboard with this id (package/class) the one in use, enabling it first. */
    static void keyboard(Context c, String id) {
        if (!system(c)) {
            Shell.send(c, "sh ime enable " + id + " && ime set " + id);
            return;
        }
        String on = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_INPUT_METHODS);
        if (on == null || on.isEmpty()) on = id;
        else if (!(":" + on + ":").contains(":" + id + ":") && !on.startsWith(id + ";") && !on.contains(":" + id + ";")) on = on + ":" + id;
        Settings.Secure.putString(c.getContentResolver(), Settings.Secure.ENABLED_INPUT_METHODS, on);
        Settings.Secure.putString(c.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD, id);
    }

    static void forceStop(Context c, String pkg) {
        if (granted(c, "android.permission.FORCE_STOP_PACKAGES")) {
            try {
                ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
                am.getClass().getMethod("forceStopPackage", String.class).invoke(am, pkg);
                return;
            } catch (ReflectiveOperationException | RuntimeException ex) {
                Log.w(TAG, "force stop through the system failed", ex);
            }
        }
        Shell.send(c, "sh am force-stop " + pkg);
    }

    static void clearCache(Context c, String pkg) {
        if (granted(c, "android.permission.DELETE_CACHE_FILES")) {
            try {
                PackageManager pm = c.getPackageManager();
                Class<?> observer = Class.forName("android.content.pm.IPackageDataObserver");
                pm.getClass().getMethod("deleteApplicationCacheFiles", String.class, observer).invoke(pm, pkg, null);
                return;
            } catch (ReflectiveOperationException | RuntimeException ex) {
                Log.w(TAG, "clear cache through the system failed", ex);
            }
        }
        Shell.send(c, "sh rm -rf /data/data/" + pkg + "/cache/* /data/data/" + pkg + "/code_cache/*");
    }

    /** Clears every app's cache. */
    static void clearAllCaches(Context c) {
        if (granted(c, "android.permission.CLEAR_APP_CACHE")) {
            try {
                PackageManager pm = c.getPackageManager();
                Class<?> observer = Class.forName("android.content.pm.IPackageDataObserver");
                pm.getClass().getMethod("freeStorageAndNotify", String.class, long.class, observer)
                    .invoke(pm, null, Long.MAX_VALUE, null);
                return;
            } catch (ReflectiveOperationException | RuntimeException ex) {
                Log.w(TAG, "clear caches through the system failed", ex);
            }
        }
        Shell.send(c, "sh pm trim-caches 999G");
    }

    static void uninstall(Context c, String pkg) {
        if (granted(c, "android.permission.DELETE_PACKAGES")) {
            try {
                PendingIntent done = PendingIntent.getBroadcast(c, 0, new Intent("com.muxover.oriel.UNINSTALLED").setPackage(c.getPackageName()),
                    PendingIntent.FLAG_IMMUTABLE);
                c.getPackageManager().getPackageInstaller().uninstall(pkg, done.getIntentSender());
                return;
            } catch (RuntimeException ex) {
                Log.w(TAG, "uninstall through the system failed", ex);
            }
        }
        Shell.send(c, "sh pm uninstall " + pkg);
    }

    /** Restarts the TV, or turns it off when restart is false. */
    static void power(Context c, boolean restart) {
        if (granted(c, "android.permission.REBOOT")) {
            try {
                PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
                if (restart) {
                    pm.reboot(null);
                } else {
                    Field f = PowerManager.class.getDeclaredField("mService");
                    f.setAccessible(true);
                    Object service = f.get(pm);
                    Method shutdown = service.getClass().getMethod("shutdown", boolean.class, String.class, boolean.class);
                    shutdown.invoke(service, false, null, false);
                }
                return;
            } catch (ReflectiveOperationException | RuntimeException ex) {
                Log.w(TAG, (restart ? "restart" : "turn off") + " through the system failed", ex);
            }
        }
        Shell.send(c, "sh setprop sys.powerctl " + (restart ? "reboot" : "shutdown"));
    }
}
