package com.muxover.oriel.home;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Current temperature from Open-Meteo (free, no account) for the city chosen in Settings, refreshed at most every 30 minutes. */
final class Weather {
    private static final long FRESH_MS = 30 * 60 * 1000L;
    private static final Pattern TEMP = Pattern.compile("\"current\"\\s*:\\s*\\{[^}]*\"temperature_2m\"\\s*:\\s*(-?[0-9.]+)");
    private static volatile String last;
    private static volatile long lastAt;
    private static volatile String lastPlace;

    private Weather() {}

    /** Calls back on the main thread with text like "28°"; never when no city is set or the network is down. */
    static void get(Context c, final Consumer<String> done) {
        final String place = Prefs.placeName(c);
        if (place == null) return;
        final double lat = Prefs.placeLat(c), lng = Prefs.placeLng(c);
        final boolean f = Prefs.fahrenheit(c);
        final String key = place + (f ? "|F" : "|C");
        final Handler main = new Handler(Looper.getMainLooper());
        if (last != null && key.equals(lastPlace) && System.currentTimeMillis() - lastAt < FRESH_MS) {
            main.post(() -> done.accept(last));
            return;
        }
        new Thread(() -> {
            try {
                String json = fetch("https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lng + "&current=temperature_2m"
                    + (f ? "&temperature_unit=fahrenheit" : ""));
                Matcher m = TEMP.matcher(json);
                if (!m.find()) {
                    Log.w("Oriel", "weather reply without temperature");
                    return;
                }
                last = Math.round(Double.parseDouble(m.group(1))) + "°";
                lastAt = System.currentTimeMillis();
                lastPlace = key;
                main.post(() -> done.accept(last));
            } catch (IOException | NumberFormatException ex) {
                Log.w("Oriel", "weather fetch failed", ex);
            }
        }, "weather").start();
    }

    static String fetch(String url) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(8000);
        con.setReadTimeout(8000);
        try (InputStream in = con.getInputStream(); ByteArrayOutputStream buf = new ByteArrayOutputStream()) {
            if (con.getResponseCode() != 200) throw new IOException("HTTP " + con.getResponseCode());
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0) buf.write(b, 0, n);
            return new String(buf.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            con.disconnect();
        }
    }
}
