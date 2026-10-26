package com.muxover.oriel.home;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Every team in the leagues Oriel offers, from ESPN's public team lists, so a team is found by typing part of
 * its name. Fetched once and kept on the TV for two weeks.
 */
final class Teams {
    private static final long FRESH_MS = 14L * 24 * 60 * 60 * 1000;
    private static final String FILE = "team-list.json";

    /** A team: its full name, its short name as scoreboards show it, and the league it was found in. */
    static final class Team {
        final String name, shortName, league;

        Team(String name, String shortName, String league) {
            this.name = name;
            this.shortName = shortName;
            this.league = league;
        }
    }

    private static volatile List<Team> all;
    private static volatile boolean loading;

    private Teams() {}

    static boolean ready() {
        return all != null;
    }

    /** Loads the list (from the TV, else from ESPN), then calls back on the main thread; false when it failed. */
    static void load(final Context c, final java.util.function.Consumer<Boolean> done) {
        final Handler main = new Handler(Looper.getMainLooper());
        if (all != null) {
            main.post(() -> done.accept(true));
            return;
        }
        if (loading) return;
        loading = true;
        final File f = new File(c.getFilesDir(), FILE);
        new Thread(() -> {
            List<Team> list = null;
            try {
                if (f.exists() && System.currentTimeMillis() - f.lastModified() < FRESH_MS) {
                    list = parse(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
                }
            } catch (IOException | JSONException ex) {
                Log.w("Oriel", "saved team list not readable", ex);
            }
            if (list == null || list.isEmpty()) list = fetchAll(f);
            final boolean ok = !list.isEmpty();
            if (ok) all = list;
            loading = false;
            main.post(() -> done.accept(ok));
        }, "teams").start();
    }

    /** Teams whose name or short name contains the text, best matches first; at most max. */
    static List<Team> search(String text, int max) {
        List<Team> out = new ArrayList<>();
        List<Team> list = all;
        String q = text.trim().toLowerCase(Locale.ROOT);
        if (list == null || q.isEmpty()) return out;
        List<Team> later = new ArrayList<>();
        for (Team t : list) {
            String n = t.name.toLowerCase(Locale.ROOT), s = t.shortName.toLowerCase(Locale.ROOT);
            if (n.startsWith(q) || s.startsWith(q)) out.add(t);
            else if (n.contains(q) || s.contains(q)) later.add(t);
        }
        out.addAll(later);
        return out.size() > max ? out.subList(0, max) : out;
    }

    private static List<Team> fetchAll(File f) {
        // Team lists per league, a few at a time; a team in several leagues is kept once, under its first league.
        final Map<String, Team> found = new LinkedHashMap<>();
        final Map<String, Integer> rankOf = new java.util.HashMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        int group = 0;
        for (int li = 0; li < Scores.LEAGUES.length; li++) {
            final String[] l = Scores.LEAGUES[li];
            if ("#".equals(l[0])) {
                group++;
                continue;
            }
            final int rank = rank(group, li, l[0]);
            pool.execute(() -> {
                try {
                    JSONObject o = new JSONObject(Weather.fetch("https://site.api.espn.com/apis/site/v2/sports/soccer/" + l[0] + "/teams"));
                    JSONArray teams = o.getJSONArray("sports").getJSONObject(0).getJSONArray("leagues").getJSONObject(0).getJSONArray("teams");
                    synchronized (found) {
                        for (int i = 0; i < teams.length(); i++) {
                            JSONObject t = teams.getJSONObject(i).getJSONObject("team");
                            String name = t.optString("displayName", "");
                            if (name.isEmpty()) continue;
                            Integer had = rankOf.get(name);
                            if (had != null && had <= rank) continue;
                            found.put(name, new Team(name, t.optString("shortDisplayName", name), l[1]));
                            rankOf.put(name, rank);
                        }
                    }
                } catch (IOException | JSONException ex) {
                    Log.w("Oriel", "team list of " + l[0] + " not read", ex);
                }
            });
        }
        pool.shutdown();
        try {
            if (!pool.awaitTermination(60, TimeUnit.SECONDS)) Log.w("Oriel", "team lists still loading after a minute");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        List<Team> list;
        synchronized (found) {
            list = new ArrayList<>(found.values());
            list.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
            JSONArray saved = new JSONArray();
            try {
                for (Team t : list) saved.put(new JSONObject().put("n", t.name).put("s", t.shortName).put("l", t.league));
            } catch (JSONException ex) {
                Log.w("Oriel", "team list not saved", ex);
            }
            if (!list.isEmpty()) {
                try (FileOutputStream out = new FileOutputStream(f)) {
                    out.write(saved.toString().getBytes(StandardCharsets.UTF_8));
                } catch (IOException ex) {
                    Log.w("Oriel", "team list not saved", ex);
                }
            }
        }
        return list;
    }

    /**
     * Which league a team is shown under when it plays in several: its home league first, then club
     * competitions, then national-team competitions; cups after leagues.
     */
    private static int rank(int group, int index, String id) {
        int g = group >= 3 ? 0 : group == 2 ? 1 : 2;
        boolean cup = id.contains("cup") || id.contains("copa") || id.contains("pokal") || id.contains("coupe")
            || id.contains("coppa") || id.contains("kings") || id.endsWith(".fa");
        return g * 1000 + (cup ? 500 : 0) + index;
    }

    private static List<Team> parse(String json) throws JSONException {
        JSONArray a = new JSONArray(json);
        List<Team> list = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            list.add(new Team(o.getString("n"), o.getString("s"), o.getString("l")));
        }
        return list;
    }
}
