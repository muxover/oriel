package com.muxover.oriel.home;

import android.content.Context;
import android.content.SharedPreferences;

/** The home's own settings, kept on the TV. */
final class Prefs {
    private Prefs() {}

    static SharedPreferences of(Context c) {
        return c.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    /** Minutes of no remote use before the clock screen saver; 0 means never. */
    static int saverMinutes(Context c) {
        return of(c).getInt("saver_min", 5);
    }

    static boolean nightOn(Context c) {
        return of(c).getBoolean("night_on", false);
    }

    /** Night mode window as minutes after midnight. */
    static int nightStart(Context c) {
        return of(c).getInt("night_start", 23 * 60);
    }

    static int nightEnd(Context c) {
        return of(c).getInt("night_end", 7 * 60);
    }

    /** 1 gentle, 2 medium, 3 strong. */
    static int nightStrength(Context c) {
        return of(c).getInt("night_strength", 2);
    }

    static boolean remoteOn(Context c) {
        return of(c).getBoolean("remote_on", true);
    }

    /** The chosen city for weather and prayer times; null until the user picks one. */
    static String placeName(Context c) {
        return of(c).getString("place_name", null);
    }

    static double placeLat(Context c) {
        return Double.longBitsToDouble(of(c).getLong("place_lat", Double.doubleToLongBits(0)));
    }

    static double placeLng(Context c) {
        return Double.longBitsToDouble(of(c).getLong("place_lng", Double.doubleToLongBits(0)));
    }

    static void setPlace(Context c, String name, double lat, double lng) {
        of(c).edit().putString("place_name", name)
            .putLong("place_lat", Double.doubleToLongBits(lat))
            .putLong("place_lng", Double.doubleToLongBits(lng)).apply();
    }

    /** Prayer times are off until turned on; they also need a city. */
    static boolean prayersOn(Context c) {
        return of(c).getBoolean("prayers_on", false) && placeName(c) != null;
    }

    static String prayerMethod(Context c) {
        return of(c).getString("prayer_method", "mwl");
    }

    static boolean asrHanafi(Context c) {
        return of(c).getBoolean("asr_hanafi", false);
    }

    /** Football leagues followed, as ESPN scoreboard ids; empty means matches are off. */
    static java.util.Set<String> leagues(Context c) {
        return new java.util.LinkedHashSet<>(of(c).getStringSet("leagues", new java.util.HashSet<>()));
    }

    /** Teams to follow; when not empty only their live matches show. Lower case. */
    static java.util.List<String> teams(Context c) {
        String raw = of(c).getString("teams", "");
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String t : raw.split(",")) if (!t.trim().isEmpty()) out.add(t.trim().toLowerCase(java.util.Locale.ROOT));
        return out;
    }

    /** "auto" follows the TV's own 12 or 24 hour setting; "12" or "24" set it for Oriel. */
    static String clockFormat(Context c) {
        return of(c).getString("clock_format", "auto");
    }

    static boolean clockAmPm(Context c) {
        return of(c).getBoolean("clock_ampm", false);
    }

    /** Index into Clock.DATE_STYLES. */
    static int dateStyle(Context c) {
        return of(c).getInt("date_style", 0);
    }

    static boolean fahrenheit(Context c) {
        return of(c).getBoolean("temp_f", false);
    }

    /** What the clock screen saver shows besides the time: "weather", "prayer" or "scores". */
    static boolean saverShows(Context c, String what) {
        return of(c).getBoolean("saver_" + what, true);
    }

    /** Teams followed, as shown in Settings. */
    static java.util.List<String> teamNames(Context c) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String t : of(c).getString("teams", "").split(",")) if (!t.trim().isEmpty()) out.add(t.trim());
        return out;
    }

    static void setTeams(Context c, java.util.List<String> teams) {
        of(c).edit().putString("teams", android.text.TextUtils.join(",", teams)).apply();
    }
}
