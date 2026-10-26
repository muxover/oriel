package com.muxover.oriel.home;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/** A live football score from ESPN's public scoreboards, refreshed at most every two minutes. */
final class Scores {
    /**
     * Leagues offered in Settings, grouped: a row starting with "#" is a group title. Ids are ESPN scoreboard ids,
     * each checked to answer on 2026-10-26. ESPN has no Egyptian, UAE, Qatari or Moroccan league.
     */
    static final String[][] LEAGUES = {
        {"#", "World and continents"},
        {"fifa.world", "FIFA World Cup"}, {"fifa.cwc", "FIFA Club World Cup"}, {"fifa.worldq.caf", "World Cup qualifying, Africa"},
        {"fifa.worldq.afc", "World Cup qualifying, Asia"}, {"fifa.worldq.uefa", "World Cup qualifying, Europe"},
        {"fifa.friendly", "International friendlies"}, {"caf.nations", "Africa Cup of Nations"}, {"afc.asian.cup", "AFC Asian Cup"},
        {"uefa.euro", "UEFA Euro"}, {"uefa.nations", "UEFA Nations League"}, {"conmebol.america", "Copa America"},
        {"#", "Club competitions"},
        {"uefa.champions", "Champions League"}, {"uefa.europa", "Europa League"}, {"uefa.europa.conf", "Conference League"},
        {"uefa.super_cup", "UEFA Super Cup"}, {"caf.champions", "CAF Champions League"}, {"caf.confed", "CAF Confederation Cup"},
        {"afc.champions", "AFC Champions League Elite"}, {"conmebol.libertadores", "Copa Libertadores"},
        {"#", "Europe"},
        {"eng.1", "Premier League"}, {"eng.2", "Championship"}, {"eng.fa", "FA Cup"}, {"eng.league_cup", "Carabao Cup"},
        {"esp.1", "LaLiga"}, {"esp.copa_del_rey", "Copa del Rey"}, {"ita.1", "Serie A"}, {"ita.coppa_italia", "Coppa Italia"},
        {"ger.1", "Bundesliga"}, {"ger.dfb_pokal", "DFB-Pokal"}, {"fra.1", "Ligue 1"}, {"fra.coupe_de_france", "Coupe de France"},
        {"ned.1", "Eredivisie"}, {"por.1", "Primeira Liga"}, {"sco.1", "Scottish Premiership"}, {"tur.1", "Super Lig"},
        {"bel.1", "Belgian Pro League"}, {"gre.1", "Greek Super League"},
        {"#", "Middle East, Africa and Asia"},
        {"ksa.1", "Saudi Pro League"}, {"ksa.kings.cup", "King's Cup"}, {"rsa.1", "South African Premiership"},
        {"jpn.1", "J1 League"}, {"chn.1", "Chinese Super League"}, {"ind.1", "Indian Super League"}, {"aus.1", "A-League"},
        {"#", "Americas"},
        {"usa.1", "MLS"}, {"mex.1", "Liga MX"}, {"bra.1", "Brasileirao"}, {"arg.1", "Argentine Liga Profesional"},
    };
    private static final long FRESH_MS = 2 * 60 * 1000L;
    private static volatile String last;
    private static volatile long lastAt;
    private static volatile String lastKey;

    private Scores() {}

    /** Calls back on the main thread with text like "Arsenal  1 – 0  Chelsea   63'", or null when nothing followed is live. */
    static void live(android.content.Context c, final Consumer<String> done) {
        final java.util.Set<String> leagues = Prefs.leagues(c);
        final java.util.List<String> teams = Prefs.teams(c);
        final Handler main = new Handler(Looper.getMainLooper());
        if (leagues.isEmpty()) {
            main.post(() -> done.accept(null));
            return;
        }
        final String key = leagues + "|" + teams;
        if (lastAt > 0 && key.equals(lastKey) && System.currentTimeMillis() - lastAt < FRESH_MS) {
            final String s = last;
            main.post(() -> done.accept(s));
            return;
        }
        new Thread(() -> {
            String found = null;
            for (String league : leagues) {
                try {
                    found = firstLive(fetch("https://site.api.espn.com/apis/site/v2/sports/soccer/" + league + "/scoreboard"), teams);
                } catch (IOException | JSONException ex) {
                    Log.w("Oriel", "scores fetch failed for " + league, ex);
                }
                if (found != null) break;
            }
            last = found;
            lastAt = System.currentTimeMillis();
            lastKey = key;
            final String s = found;
            main.post(() -> done.accept(s));
        }, "scores").start();
    }

    private static String firstLive(String json, java.util.List<String> follow) throws JSONException {
        JSONArray events = new JSONObject(json).optJSONArray("events");
        if (events == null) return null;
        for (int i = 0; i < events.length(); i++) {
            JSONObject comp = events.getJSONObject(i).getJSONArray("competitions").getJSONObject(0);
            JSONObject status = comp.getJSONObject("status");
            if (!"in".equals(status.getJSONObject("type").optString("state"))) continue;
            JSONArray teams = comp.getJSONArray("competitors");
            String home = "", away = "", hs = "", as = "", homeFull = "", awayFull = "";
            for (int t = 0; t < teams.length(); t++) {
                JSONObject c = teams.getJSONObject(t);
                String full = c.getJSONObject("team").optString("displayName");
                String name = c.getJSONObject("team").optString("shortDisplayName", full);
                if ("home".equals(c.optString("homeAway"))) {
                    homeFull = full;
                    home = name;
                    hs = c.optString("score", "0");
                } else {
                    awayFull = full;
                    away = name;
                    as = c.optString("score", "0");
                }
            }
            if (!follow.isEmpty() && !follows(follow, home) && !follows(follow, away)
                && !follows(follow, homeFull) && !follows(follow, awayFull)) continue;
            String clock = status.getJSONObject("type").optString("shortDetail", status.optString("displayClock"));
            return home + "  " + hs + " – " + as + "  " + away + "   " + clock;
        }
        return null;
    }

    private static boolean follows(java.util.List<String> follow, String team) {
        String t = team.toLowerCase(java.util.Locale.ROOT);
        if (t.isEmpty()) return false;
        for (String f : follow) if (t.equals(f) || t.contains(f)) return true;
        return false;
    }

    private static String fetch(String url) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(8000);
        con.setReadTimeout(8000);
        try (InputStream in = con.getInputStream(); ByteArrayOutputStream buf = new ByteArrayOutputStream()) {
            if (con.getResponseCode() != 200) throw new IOException("HTTP " + con.getResponseCode());
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) buf.write(b, 0, n);
            return new String(buf.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            con.disconnect();
        }
    }
}
