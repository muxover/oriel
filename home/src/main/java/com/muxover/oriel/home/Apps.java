package com.muxover.oriel.home;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Finds installed apps and builds the home row. */
final class Apps {
    private static final String TAG = "Oriel";


    /*
     * Home rows. Only installed apps are shown, each in exactly one row. Known apps go to their row in the
     * order listed here; any other app goes by the kind it declares (video, audio, game, news, photos),
     * and the rest that the user installed go to More apps. The maker's own apps only appear in All apps.
     */
    private static final String WATCH = "Watch", LISTEN = "Listen", SPORTS = "Sports", NEWS = "News and talks",
        KIDS = "Kids", LIBRARY = "Your media", GAMES = "Games", MORE = "More apps";
    /** Row order on the home screen. */
    private static final String[] ROW_ORDER = {WATCH, LISTEN, SPORTS, NEWS, KIDS, LIBRARY, GAMES, MORE};
    /** Known apps: package, row, the short description shown when the app is chosen. */
    private static final String[][] KNOWN = {
        {"com.google.android.youtube.tv", WATCH, "Videos, music and live streams."},
        {"com.netflix.ninja", WATCH, "Series, films and documentaries."},
        {"com.amazon.amazonvideo.livingroom", WATCH, "Films, series and sport from Amazon."},
        {"com.apple.atve.androidtv.appletv", WATCH, "Apple TV+ originals, and films to rent or buy."},
        {"com.disney.disneyplus", WATCH, "Disney, Pixar, Marvel, Star Wars and National Geographic."},
        {"com.wbd.stream", WATCH, "HBO series, Warner Bros. films and more."},
        {"com.osn.go", WATCH, "HBO, Hollywood, Arabic and Turkish series and films."},
        {"net.mbc.shahidTV", WATCH, "Arabic series, films and live channels from MBC."},
        {"com.parsifal.starz", WATCH, "Series, films and sport in Arabic and English."},
        {"com.yango.saft.tv", WATCH, "Series, anime and films."},
        {"com.watchit.vod", WATCH, "Egyptian and Arabic series and films."},
        {"com.cbs.ott", WATCH, "Paramount and CBS series and films."},
        {"com.crunchyroll.crunchyroid", WATCH, "Anime series and films, subtitled and dubbed."},
        {"com.mubi", WATCH, "Hand-picked films from around the world."},
        {"com.viki.android", WATCH, "Korean and Asian dramas with subtitles."},
        {"tv.pluto.android", WATCH, "Free live channels and films."},
        {"com.tubitv", WATCH, "Free films and series, with ads."},
        {"tv.twitch.android.app", WATCH, "Live streams, mostly gaming."},
        {"com.spotify.tv.android", LISTEN, "Music and podcasts."},
        {"com.anghami", LISTEN, "Arabic and international music and podcasts."},
        {"com.google.android.apps.youtube.music", LISTEN, "Songs, albums and music videos."},
        {"com.apple.android.music", LISTEN, "Songs, albums and radio from Apple."},
        {"deezer.android.app", LISTEN, "Music, playlists and podcasts."},
        {"com.soundcloud.android", LISTEN, "Tracks and mixes from artists and DJs."},
        {"com.aspiro.tidal", LISTEN, "High-quality music streaming."},
        {"com.amazon.mp3", LISTEN, "Music and podcasts from Amazon."},
        {"tunein.player", LISTEN, "Live radio stations and podcasts."},
        {"com.dazn", SPORTS, "Live and on-demand sport."},
        {"com.todtv.tod", SPORTS, "beIN sport, films and series."},
        {"net.aljazeera.english", NEWS, "News from Al Jazeera English, live and on demand."},
        {"com.ted.android.tv", NEWS, "Short talks on ideas, from science to art."},
        {"com.google.android.youtube.tvkids", KIDS, "Videos made for children."},
        {"com.plexapp.android", LIBRARY, "Your own films and music, plus free channels."},
        {"org.jellyfin.androidtv", LIBRARY, "Your own media server, free and open source."},
        {"tv.emby.embyatv", LIBRARY, "Your own media server."},
        {"org.xbmc.kodi", LIBRARY, "Media centre for your own files."},
        {"org.videolan.vlc", LIBRARY, "Plays almost any video or audio file."},
        {"com.stremio.one", LIBRARY, "Films and series from add-ons, in one place."},
    };
    /** Descriptions of apps outside the rows. */
    private static final String[][] OWN_ROW_TEXT = {
        {"com.android.vending", "Get apps and games for this TV."},
        {"com.android.chrome", "Browse the web."},
        {"cn.zeasn.overseas.tv.hikeen.hisi352", "The TV maker's own app store."},
    };
    /** Apps that sit in the System row instead. */
    private static final String[] OWN_ROW_APPS = {"com.android.vending", "cn.zeasn.overseas.tv.hikeen.hisi352", "com.android.chrome"};

    /** A titled row of home tiles. */
    static final class Row {
        final String title;
        final List<Entry> entries = new ArrayList<>();

        Row(String title) {
            this.title = title;
        }
    }

    /** Never shown: this app, the old launcher, tools covered by built-in tiles, and the phone Netflix that cannot sign in here. */
    private static final Set<String> HIDDEN = new HashSet<>(Arrays.asList(
        "com.muxover.oriel",
        "com.muxover.oriel.mirror",
        "com.zeasn.launcher",
        "com.zeasn.hikeen",
        "com.hisilicon.miracast",
        "com.hisilicon.tvui",
        "com.hikeen.mediaplayer",
        "com.hikeen.settingbase",
        "com.android.tv.settings",
        "com.netflix.mediaclient",
        "com.adups.fota"
    ));

    private static List<Entry> cache;

    private Apps() {}

    /** Drops the cached list so the next call reloads it; call when apps are installed or removed. */
    static synchronized void invalidate() {
        cache = null;
    }

    /** Every launchable app, by label. Loaded once and kept, since loading artwork is the slow part. */
    static synchronized List<Entry> all(Context c) {
        if (cache == null) cache = load(c);
        return cache;
    }

    /** The sharpest version of a resource drawable the app ships, falling back to the normal one. */
    private static Drawable sharpest(PackageManager pm, ActivityInfo ai, int res) {
        if (res == 0) return null;
        try {
            Resources r = pm.getResourcesForApplication(ai.applicationInfo);
            return r.getDrawableForDensity(res, DisplayMetrics.DENSITY_XXXHIGH, null);
        } catch (PackageManager.NameNotFoundException | Resources.NotFoundException ex) {
            Log.w(TAG, "no sharp drawable for " + ai.packageName, ex);
            return null;
        }
    }

    private static List<Entry> load(Context c) {
        PackageManager pm = c.getPackageManager();
        Map<String, Entry> out = new LinkedHashMap<>();
        collect(c, pm, Intent.CATEGORY_LEANBACK_LAUNCHER, out);
        collect(c, pm, Intent.CATEGORY_LAUNCHER, out);
        List<Entry> list = new ArrayList<>(out.values());
        list.sort((a, b) -> a.title.compareToIgnoreCase(b.title));
        return list;
    }

    private static void collect(Context c, PackageManager pm, String category, Map<String, Entry> out) {
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(category);
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            ActivityInfo ai = ri.activityInfo;
            if (HIDDEN.contains(ai.packageName) || out.containsKey(ai.packageName)) continue;
            Entry e = new Entry(ai.packageName, String.valueOf(ri.loadLabel(pm)));
            Intent launch = new Intent(Intent.ACTION_MAIN).addCategory(category)
                .setComponent(new ComponentName(ai.packageName, ai.name))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            e.intent = launch;
            // Every app shows its official store icon, the same look for all. The app's own TV banner is
            // only a stand-in until that icon is downloaded, or for apps Google Play has no page for.
            e.storeIcon = IconFetcher.cached(c, ai.packageName);
            if (e.storeIcon == null) {
                int bannerRes = ai.banner != 0 ? ai.banner : ai.applicationInfo.banner;
                e.banner = sharpest(pm, ai, bannerRes);
                if (e.banner == null && bannerRes != 0) e.banner = ai.loadBanner(pm);
                if (e.banner != null && !Ui.bannerLooksRight(e.banner)) e.banner = null;
            }
            e.kind = kindOf(ai.applicationInfo);
            e.subtitle = describe(c, pm, ai.applicationInfo, e.kind);
            e.maker = (ai.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                && (ai.applicationInfo.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
            e.icon = sharpest(pm, ai, ri.getIconResource());
            if (e.icon == null) e.icon = ri.loadIcon(pm);
            Log.i(TAG, "art " + ai.packageName + " banner=" + (e.banner == null ? "none" : e.banner.getIntrinsicWidth() + "x" + e.banner.getIntrinsicHeight() + " " + e.banner.getClass().getSimpleName())
                + " icon=" + e.icon.getIntrinsicWidth() + "x" + e.icon.getIntrinsicHeight() + " " + e.icon.getClass().getSimpleName());
            if (e.banner == null) e.tileColor = Ui.edgeColor(e.storeIcon != null ? e.storeIcon : e.icon, 0xFFEDECE8);
            e.tint = Ui.tintOf(e.banner != null ? e.banner : (e.storeIcon != null ? e.storeIcon : e.icon), 0xFF2A2340);
            out.put(ai.packageName, e);
        }
    }

    /** A one-line description from Google Play when it has a good one, else Oriel's own, else the app's own, else its kind. */
    private static String describe(Context c, PackageManager pm, ApplicationInfo ai, String kind) {
        String mine = null;
        for (String[] k : KNOWN) if (k[0].equals(ai.packageName)) mine = k[2];
        for (String[] k : OWN_ROW_TEXT) if (k[0].equals(ai.packageName)) mine = k[1];
        // Google Play first, for every app including ones installed later: a sentence naming the app, else
        // Oriel's own line, else Play's first sentence, else the developer's one-line summary.
        java.util.List<String> play = IconFetcher.text(c, ai.packageName);
        if (play != null && !play.isEmpty()) {
            String name = ai.loadLabel(pm).toString().trim().split("\\s+")[0].toLowerCase(java.util.Locale.ROOT);
            for (int i = 1; i < play.size(); i++) {
                if (name.length() >= 3 && play.get(i).toLowerCase(java.util.Locale.ROOT).contains(name)) return play.get(i);
            }
            if (mine != null) return mine;
            if (play.size() > 1 && !play.get(1).isEmpty()) return play.get(1);
            if (play.get(0).length() >= 12) return play.get(0);
        }
        if (mine != null) return mine;
        CharSequence own = ai.loadDescription(pm);
        if (own != null) {
            String t = own.toString().trim();
            int end = t.indexOf(". ");
            if (end > 0) t = t.substring(0, end + 1);
            if (!t.isEmpty() && t.length() <= 90) return t;
        }
        if (kind == null) return "An app on this TV.";
        switch (kind) {
            case WATCH: return "Films, series and video.";
            case LISTEN: return "Music and audio.";
            case NEWS: return "News and talks.";
            case LIBRARY: return "Photos and your own media.";
            case GAMES: return "A game.";
            default: return "An app on this TV.";
        }
    }

    /** The row an app declares itself for, or null when it declares nothing that fits a row. */
    @SuppressWarnings("deprecation")
    private static String kindOf(ApplicationInfo ai) {
        if (ai.category == ApplicationInfo.CATEGORY_GAME || (ai.flags & ApplicationInfo.FLAG_IS_GAME) != 0) return GAMES;
        switch (ai.category) {
            case ApplicationInfo.CATEGORY_VIDEO: return WATCH;
            case ApplicationInfo.CATEGORY_AUDIO: return LISTEN;
            case ApplicationInfo.CATEGORY_NEWS: return NEWS;
            case ApplicationInfo.CATEGORY_IMAGE: return LIBRARY;
            default: return null;
        }
    }

    /** The home screen: one row per kind of app that is installed, then Connect and System. */
    static List<Row> homeRows(Context c) {
        Map<String, Entry> byPkg = new LinkedHashMap<>();
        for (Entry e : all(c)) byPkg.put(e.id, e);
        Set<String> own = new HashSet<>(Arrays.asList(OWN_ROW_APPS));
        Map<String, Row> byTitle = new LinkedHashMap<>();
        for (String t : ROW_ORDER) byTitle.put(t, new Row(t));
        Set<String> placed = new HashSet<>();
        for (String[] k : KNOWN) {
            Entry e = byPkg.get(k[0]);
            if (e == null) continue;
            byTitle.get(k[1]).entries.add(e);
            placed.add(e.id);
        }
        for (Entry e : byPkg.values()) {
            if (placed.contains(e.id) || own.contains(e.id) || e.maker) continue;
            byTitle.get(e.kind != null ? e.kind : MORE).entries.add(e);
        }
        List<Row> rows = new ArrayList<>();
        for (Row r : byTitle.values()) if (!r.entries.isEmpty()) rows.add(r);

        // Oriel's own tiles: what plugs into or casts to the TV, then the TV itself.
        Row connect = new Row("Connect");
        Entry mirror = new Entry("mirror", "Mirror");
        mirror.iconKind = IconView.MIRROR;
        mirror.status = "Ready as " + Device.name(c);
        mirror.subtitle = "iPhone, iPad or Mac: Screen Mirroring. Windows: Win + K. Then " + Device.name(c) + ".";
        mirror.tint = 0xFF2A2340;
        connect.entries.add(mirror);

        Entry hdmi = new Entry("hdmi", "Inputs");
        hdmi.iconKind = IconView.HDMI;
        hdmi.status = "HDMI and AV";
        hdmi.subtitle = "Your receiver, console or laptop.";
        hdmi.tint = 0xFF1B2A3A;
        // Input switching goes through the maker's TV player, which only HiSilicon TV boards have.
        if (isInstalled(c, "com.hisilicon.tvui")) connect.entries.add(hdmi);

        Entry media = new Entry("media", "Media");
        media.iconKind = IconView.MEDIA;
        media.status = "USB and this TV";
        media.subtitle = "Videos, photos and music from a USB drive.";
        media.intent = new Intent(c, MediaActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        media.tint = 0xFF2A2A1B;
        connect.entries.add(media);
        rows.add(connect);

        Row system = new Row("System");
        Entry apps = new Entry("apps", "All apps");
        apps.iconKind = IconView.APPS;
        apps.status = "Library";
        apps.subtitle = "Everything installed on this TV.";
        apps.tint = 0xFF222226;
        system.entries.add(apps);

        Entry updates = new Entry("updates", "Update apps");
        updates.iconKind = IconView.UPDATES;
        updates.status = "Google Play";
        updates.subtitle = "Press OK to update every app that has an update.";
        updates.tint = 0xFF12302A;
        system.entries.add(updates);

        for (String p : OWN_ROW_APPS) {
            Entry e = byPkg.get(p);
            if (e != null) system.entries.add(e);
        }

        Entry settings = new Entry("settings", "Settings");
        settings.iconKind = IconView.SETTINGS;
        settings.status = "System";
        settings.subtitle = "Wi-Fi, Bluetooth, keyboard, storage and more.";
        settings.tint = 0xFF222226;
        system.entries.add(settings);
        rows.add(system);
        return rows;
    }

    /** Installed apps whose official store icon is not on the TV yet. */
    static List<String> missingIcons(Context c) {
        List<String> out = new ArrayList<>();
        for (Entry e : all(c)) if (e.storeIcon == null || IconFetcher.missing(c, e.id)) out.add(e.id);
        return out;
    }

    static boolean isInstalled(Context c, String pkg) {
        try {
            c.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException ex) {
            return false;
        }
    }

    static Intent launchIntent(Context c, String pkg) {
        Intent i = c.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) {
            Log.w(TAG, "no launch intent for " + pkg);
            return null;
        }
        return i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }
}
