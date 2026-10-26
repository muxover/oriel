package com.muxover.oriel.home;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import java.util.List;

/** The home screen: a top shelf that follows focus, and rows of lifting tiles that slide up as you move down. */
public class HomeActivity extends Activity implements Tile.Listener {
    private static final String TAG = "Oriel";
    /** True while the home screen itself is in front, not Settings or another Oriel screen. */
    static volatile boolean showing;

    private FrameLayout root;
    private View shelfA, shelfB;
    private boolean shelfAFront = true;
    private LinearLayout shelfText;
    private TextView status, title, subtitle;
    private TextClock time, date;
    private View clockBox;
    private boolean captions;
    /** The accent and captions this screen was built with; a change in Settings rebuilds it. */
    private int builtAccent;
    private View statusDot;
    private FrameLayout art;
    private LinearLayout rows;
    private int rowIndex = -1;
    private static final int ROWS_TOP = 424, ROW_STEP = 226;
    private Entry shown;
    private final DecelerateInterpolator ease = new DecelerateInterpolator(2f);

    private final BroadcastReceiver packagesChanged = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            Apps.invalidate();
            fillRows();
            fetchIcons();
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        builtAccent = Ui.ACCENT;
        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.INK);
        root.setClipChildren(false);

        int shelfH = -1;
        shelfA = new View(this);
        shelfB = new View(this);
        shelfB.setAlpha(0f);
        root.addView(shelfA, new FrameLayout.LayoutParams(-1, shelfH));
        root.addView(shelfB, new FrameLayout.LayoutParams(-1, shelfH));
        root.addView(Ui.grainView(this), new FrameLayout.LayoutParams(-1, -1));
        art = new FrameLayout(this);
        art.setClipChildren(false);
        FrameLayout.LayoutParams ap = new FrameLayout.LayoutParams(Ui.dp(this, 400), Ui.dp(this, 250), Gravity.END | Gravity.TOP);
        ap.topMargin = Ui.dp(this, 128);
        ap.rightMargin = Ui.dp(this, 96);
        root.addView(art, ap);

        shelfText = new LinearLayout(this);
        shelfText.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(Ui.dp(this, 540), -2);
        tp.leftMargin = Ui.dp(this, 64);
        tp.topMargin = Ui.dp(this, 168);
        root.addView(shelfText, tp);

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusDot = new View(this);
        statusDot.setBackground(Ui.round(Ui.ACCENT, Ui.dp(this, 4)));
        LinearLayout.LayoutParams dp8 = new LinearLayout.LayoutParams(Ui.dp(this, 8), Ui.dp(this, 8));
        dp8.rightMargin = Ui.dp(this, 8);
        statusRow.addView(statusDot, dp8);
        status = Ui.text(this, "", 14, Ui.mono, Ui.ACCENT_HI);
        statusRow.addView(status);
        shelfText.addView(statusRow);

        title = Ui.text(this, "", 52, Ui.bold, Ui.PAPER);
        title.setLetterSpacing(-0.025f);
        title.setLineSpacing(0, 0.92f);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, -2);
        tl.topMargin = Ui.dp(this, 16);
        shelfText.addView(title, tl);

        subtitle = Ui.text(this, "", 20, Ui.regular, Ui.TEXT2);
        subtitle.setLineSpacing(0, 1.2f);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
        sl.topMargin = Ui.dp(this, 16);
        shelfText.addView(subtitle, sl);

        LinearLayout clock = new LinearLayout(this);
        clock.setGravity(Gravity.CENTER_VERTICAL);
        date = new TextClock(this);
        date.setTextSize(16);
        date.setTypeface(Ui.regular);
        date.setTextColor(Ui.TEXT2);
        clock.addView(date);
        time = new TextClock(this);
        time.setTextSize(20);
        time.setTypeface(Ui.medium);
        time.setTextColor(Ui.PAPER);
        LinearLayout.LayoutParams tm = new LinearLayout.LayoutParams(-2, -2);
        tm.leftMargin = Ui.dp(this, 16);
        clock.addView(time, tm);
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.TOP);
        cp.topMargin = Ui.dp(this, 40);
        cp.rightMargin = Ui.dp(this, 64);
        root.addView(clock, cp);
        clockBox = clock;

        // Rows sit under the shelf; moving down slides them up so the chosen row is always in the same place.
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setClipChildren(false);
        rows.setClipToPadding(false);
        FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        rp.topMargin = Ui.dp(this, ROWS_TOP);
        root.addView(rows, rp);

        setContentView(root);
        holdForMenuSize();
        // The root helper (volume popup, mouse-mode block, menu size) and the phone remote run whenever
        // Oriel does, also right after Oriel itself was updated.
        Shell.start(this);
        if (getPackageManager().hasSystemFeature("android.software.leanback") && Apps.isInstalled(this, "com.hisilicon.tv.service")) {
            Sys.allowTvLibraries(this);
        }
        // The keeper starts Oriel's own screens for the TV's remote buttons; it needs to know Oriel's package.
        Shell.send(this, "sh echo " + getPackageName() + " > /data/local/tmp/oriel-home");
        RemoteServer.sync(this);
        fillRows();
        fetchIcons();

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(packagesChanged, f);
        getContentResolver().registerContentObserver(android.provider.Settings.Secure.getUriFor(
            android.provider.Settings.Secure.DEFAULT_INPUT_METHOD), false, keyboardChanged);
    }

    /**
     * The TV drops Oriel's accessibility service from the enabled list now and then (after an update of
     * Oriel, or when the maker's remote service starts). It drives typing from the phone, one-press
     * updates, Now Playing and the screen saver, so it is switched back on through the root helper.
     */
    private void keepWatcherOn() {
        String me = new android.content.ComponentName(this, WindowWatcher.class).flattenToShortString();
        String on = android.provider.Settings.Secure.getString(getContentResolver(), android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (on != null && (on.contains(me) || on.contains(new android.content.ComponentName(this, WindowWatcher.class).flattenToString()))) return;
        String list = on == null || on.isEmpty() || on.equals("null") ? me : on + ":" + me;
        Log.i(TAG, "accessibility service was off, turning it back on");
        Sys.secure(this, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list);
        Sys.secure(this, android.provider.Settings.Secure.ACCESSIBILITY_ENABLED, "1");
    }

    private final android.database.ContentObserver keyboardChanged = new android.database.ContentObserver(new android.os.Handler(android.os.Looper.getMainLooper())) {
        @Override
        public void onChange(boolean self) {
            keepKeyboard();
        }
    };

    /** Android falls back to its stock keyboard when Oriel is updated or stopped, and apps can switch it; switch back. */
    private void keepKeyboard() {
        String ours = new android.content.ComponentName(this, KeyboardService.class).flattenToShortString();
        String now = android.provider.Settings.Secure.getString(getContentResolver(), android.provider.Settings.Secure.DEFAULT_INPUT_METHOD);
        if (now != null && (now.equals(ours) || now.equals(new android.content.ComponentName(this, KeyboardService.class).flattenToString()))) return;
        Log.i(TAG, "keyboard was " + now + ", switching back to Oriel's");
        Sys.keyboard(this, ours);
    }

    /** Gets the official store icon for installed apps that have no usable TV artwork, then redraws. */
    private void fetchIcons() {
        IconFetcher.fetchMissing(this, Apps.missingIcons(this), () -> {
            Apps.invalidate();
            fillRows();
        });
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packagesChanged);
        getContentResolver().unregisterContentObserver(keyboardChanged);
        super.onDestroy();
    }

    /**
     * Right after a restart, the keeper switches the menus to a smaller size once home is up (slow graphics
     * chips). Home stays black until that size is in place, so the switch is never seen; the switch restarts
     * this screen, and the new one fades in at the right size. After 8 s it shows anyway.
     */
    private boolean revealed;

    private void holdForMenuSize() {
        // The keeper announces the size; it starts after this screen, so the last announced size is kept too.
        String target = Sys.prop("debug.oriel.menu_size");
        if (!target.isEmpty()) Prefs.of(this).edit().putString("menu_size", target).apply();
        else target = Prefs.of(this).getString("menu_size", "");
        // "1280x720 160": width x height, then density. The size and the density change one after the other,
        // and each rebuilds this screen, so it stays black until both are in place.
        int w = -1, dpi = -1;
        try {
            String[] p = target.trim().split("[x ]+");
            if (p.length >= 1 && !p[0].isEmpty()) w = Integer.parseInt(p[0]);
            if (p.length >= 3) dpi = Integer.parseInt(p[2]);
        } catch (NumberFormatException ex) {
            Log.w(TAG, "menu size property not understood: " + target);
        }
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        if (w > 0 && (dm.widthPixels != w || (dpi > 0 && dm.densityDpi != dpi))) {
            Log.i(TAG, "home waits for the menu size " + target);
            root.setVisibility(View.INVISIBLE);
            root.postDelayed(this::reveal, 8000);
            return;
        }
        reveal();
    }

    private void reveal() {
        if (revealed) return;
        revealed = true;
        root.setAlpha(0f);
        root.setVisibility(View.VISIBLE);
        root.animate().alpha(1f).setDuration(Ui.ENTER_MS).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        showing = true;
        if (builtAccent != 0 && (builtAccent != Ui.ACCENT || captions != Prefs.of(this).getBoolean("captions", false))) {
            recreate();
            return;
        }
        root.removeCallbacks(watchPlaying);
        root.postDelayed(watchPlaying, 1000);
        Clock.time(this, time, false);
        clockBox.setVisibility(Prefs.of(this).getBoolean("home_clock", true) ? View.VISIBLE : View.GONE);
        Clock.date(this, date);
        keepWatcherOn();
        keepKeyboard();
        Device.keepInSync(this);
        LaunchOverlay.get(this).dismissNow();
        LaunchOverlay.lastOpened = null;
        liftReturnVeil();
    }

    private FrameLayout veil;
    private LaunchBar veilBar;

    /**
     * Placed the moment an app opens, hidden behind the launch screen, so the home's very first frame on
     * the way back already shows it: the app's colour with "Home" and a bar. No flash of the bare home.
     */
    void placeReturnVeil(Entry e) {
        if (veil != null) root.removeView(veil);
        veil = new FrameLayout(this);
        veil.setBackground(Ui.wash(Ui.blend(e.tint, 0xFFFFFFFF, 0.1f), Ui.dp(this, 700), 0.5f, 0.45f));
        veil.setClickable(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView label = Ui.text(this, "Home", 30, Ui.semibold, Ui.PAPER);
        label.setShadowLayer(Ui.dp(this, 10), 0, 0, 0x66F4F3EF);
        col.addView(label);
        veilBar = new LaunchBar(this);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(Ui.dp(this, 360), Ui.dp(this, 32));
        bp.topMargin = Ui.dp(this, 16);
        col.addView(veilBar, bp);
        veil.addView(col, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        // hidden while the home is still on screen; shown in onStop once the app has covered it
        veil.setVisibility(View.INVISIBLE);
        root.addView(veil, new FrameLayout.LayoutParams(-1, -1));
    }

    @Override
    protected void onStop() {
        super.onStop();
        root.removeCallbacks(watchPlaying);
        showing = false;
        if (veil != null) veil.setVisibility(View.VISIBLE);
    }

    /** Back home: the bar runs quickly, then the veil lifts to reveal the home, the tile you came from selected. */
    private void liftReturnVeil() {
        final FrameLayout v = veil;
        if (v == null) return;
        if (v.getVisibility() != View.VISIBLE) {
            // the app never covered the home (it failed or closed at once), so there is nothing to lift
            root.removeView(v);
            veil = null;
            return;
        }
        veil = null;
        final LaunchBar b = veilBar;
        android.animation.ValueAnimator quick = android.animation.ValueAnimator.ofFloat(0f, 1f);
        quick.setDuration(380);
        quick.setInterpolator(ease);
        quick.addUpdateListener(a -> b.setProgress((float) a.getAnimatedValue()));
        quick.start();
        v.animate().alpha(0f).setStartDelay(420).setDuration(300).setInterpolator(ease).withLayer()
            .withEndAction(() -> root.removeView(v)).start();
    }

    private void fillRows() {
        String keep = shown != null ? shown.id : getSharedPreferences("home", MODE_PRIVATE).getString("focus", null);
        rows.removeAllViews();
        captions = Prefs.of(this).getBoolean("captions", false);
        Tile focus = null;
        List<Apps.Row> all = new java.util.ArrayList<>(Apps.homeRows(this));
        Apps.Row playing = nowPlayingRow();
        if (playing != null) all.add(0, playing);
        for (Apps.Row r : all) {
            LinearLayout block = new LinearLayout(this);
            block.setOrientation(LinearLayout.VERTICAL);
            block.setClipChildren(false);
            block.setClipToPadding(false);
            TextView label = Ui.text(this, r.title, 18, Ui.semibold, Ui.TEXT2);
            label.setPadding(Ui.dp(this, 64), 0, 0, 0);
            block.addView(label);
            HorizontalScrollView sc = new HorizontalScrollView(this) {
                @Override
                protected int computeScrollDeltaToGetChildRectOnScreen(android.graphics.Rect rect) {
                    // no default jump-to-edge scroll; onFocused scrolls smoothly with a margin instead
                    return 0;
                }
            };
            sc.setHorizontalScrollBarEnabled(false);
            sc.setClipChildren(false);
            sc.setClipToPadding(false);
            sc.setOverScrollMode(View.OVER_SCROLL_NEVER);
            LinearLayout line = new LinearLayout(this);
            line.setClipChildren(false);
            line.setClipToPadding(false);
            line.setPadding(Ui.dp(this, 64), Ui.dp(this, 20), Ui.dp(this, 64), Ui.dp(this, 8));
            sc.addView(line, new ViewGroup.LayoutParams(-2, -2));
            block.addView(sc, new LinearLayout.LayoutParams(-1, -2));
            for (int i = 0; i < r.entries.size(); i++) {
                Entry e = r.entries.get(i);
                Tile t = new Tile(this, e, 192, 120, captions, this);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                if (i > 0) lp.leftMargin = Ui.dp(this, 36);
                line.addView(t, lp);
                if (focus == null || e.id.equals(keep)) focus = t;
            }
            rows.addView(block, new LinearLayout.LayoutParams(-1, Ui.dp(this, ROW_STEP)));
        }
        // As tall as all its rows, not the screen: rows slid up still lie inside it, so mouse clicks reach them.
        rows.getLayoutParams().height = (rows.getChildCount() + 1) * Ui.dp(this, ROW_STEP);
        rows.requestLayout();
        rowIndex = -1;
        if (focus != null) {
            final Tile f = focus;
            f.card.post(f.card::requestFocus);
            showShelf(f.entry);
        }
    }

    @Override
    public void onFocused(Tile t) {
        showShelf(t.entry);
        getSharedPreferences("home", MODE_PRIVATE).edit().putString("focus", t.entry.id).apply();
        Log.i(TAG, "focus " + t.entry.id);
        if (!t.entry.isBuiltin() || t.entry.intent != null) LaunchOverlay.prepare(this, t.entry);
        // keep one tile of room on the left so the focused tile never touches an edge
        LinearLayout line = (LinearLayout) t.getParent();
        HorizontalScrollView sc = (HorizontalScrollView) line.getParent();
        int target = t.getLeft() - line.getPaddingLeft() - t.getWidth() - Ui.dp(this, 36);
        int max = Math.max(0, line.getWidth() - sc.getWidth());
        sc.smoothScrollTo(Math.max(0, Math.min(target, max)), 0);
        int idx = rows.indexOfChild((View) sc.getParent());
        if (idx != rowIndex) {
            rowIndex = idx;
            rows.animate().translationY(-idx * Ui.dp(this, ROW_STEP)).setDuration(Ui.ENTER_MS + 80).setInterpolator(ease).start();
            for (int i = 0; i < rows.getChildCount(); i++) {
                View b = rows.getChildAt(i);
                b.animate().alpha(i < idx ? 0f : i == idx ? 1f : 0.7f).setDuration(Ui.ENTER_MS).start();
            }
        }
    }

    /** What is playing as shown on home, so a change (music starts, stops, next song) redraws the rows. */
    private String playingKey = "";

    /** Music playing in any app, or sent over AirPlay: a row on top of home that opens Now Playing again. */
    private Apps.Row nowPlayingRow() {
        android.media.session.MediaController mc = MediaWatcher.playing();
        if (mc == null) mc = MediaWatcher.of("com.muxover.oriel.mirror");
        playingKey = "";
        if (mc == null || mc.getMetadata() == null) return null;
        android.media.MediaMetadata md = mc.getMetadata();
        String song = md.getString(android.media.MediaMetadata.METADATA_KEY_TITLE);
        String artist = md.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST);
        if (song == null || song.isEmpty()) return null;
        playingKey = mc.getPackageName() + "|" + song;
        Entry e = new Entry("nowplaying", song);
        e.status = "Now playing";
        e.subtitle = artist != null && !artist.isEmpty() ? artist : "Press OK for Now Playing.";
        e.tint = 0xFF2A1E3A;
        android.graphics.Bitmap art = md.getBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (art == null) art = md.getBitmap(android.media.MediaMetadata.METADATA_KEY_ART);
        if (art != null) {
            e.banner = new android.graphics.drawable.BitmapDrawable(getResources(), art);
            e.storeIcon = e.banner;
        } else {
            e.iconKind = IconView.MUSIC;
        }
        Apps.Row r = new Apps.Row("Now playing");
        r.entries.add(e);
        return r;
    }

    /** While home shows, music starting, stopping or changing song updates the Now playing row. */
    private final Runnable watchPlaying = new Runnable() {
        @Override
        public void run() {
            android.media.session.MediaController mc = MediaWatcher.playing();
            if (mc == null) mc = MediaWatcher.of("com.muxover.oriel.mirror");
            String key = "";
            if (mc != null && mc.getMetadata() != null) {
                String song = mc.getMetadata().getString(android.media.MediaMetadata.METADATA_KEY_TITLE);
                if (song != null && !song.isEmpty()) key = mc.getPackageName() + "|" + song;
            }
            if (!key.equals(playingKey)) fillRows();
            root.postDelayed(this, 3000);
        }
    };

    /** Shelf backdrops of the tiles visited last; each is half a megabyte, so only a few are kept. */
    private final java.util.Map<String, android.graphics.drawable.Drawable> backdrops =
        new java.util.LinkedHashMap<String, android.graphics.drawable.Drawable>(12, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(java.util.Map.Entry<String, android.graphics.drawable.Drawable> eldest) {
                return size() > 8;
            }
        };

    private void showShelf(Entry e) {
        if (e == shown) return;
        shown = e;
        View front = shelfAFront ? shelfA : shelfB;
        View back = shelfAFront ? shelfB : shelfA;
        android.graphics.drawable.Drawable pic = backdrops.get(e.id);
        if (pic == null) {
            pic = shelfPicture(e);
            // the Now playing tile changes with every song, so its picture is never reused
            if (!"nowplaying".equals(e.id)) backdrops.put(e.id, pic);
        }
        back.setBackground(pic);
        back.setVisibility(View.VISIBLE);
        back.animate().alpha(1f).setDuration(420).setInterpolator(ease).start();
        // the old picture leaves the screen entirely once faded, so the GPU draws one full-screen layer, not two
        front.animate().alpha(0f).setDuration(420).setInterpolator(ease).withEndAction(() -> {
            if (front.getAlpha() == 0f) front.setVisibility(View.INVISIBLE);
        }).start();
        shelfAFront = !shelfAFront;

        boolean live = "mirror".equals(e.id);
        statusDot.setVisibility(live ? View.VISIBLE : View.GONE);
        status.setTextColor(live ? Ui.ACCENT_HI : Ui.MUTED);
        status.setText(e.status != null ? e.status : "");
        ((View) status.getParent()).setVisibility(e.status != null ? View.VISIBLE : View.GONE);
        title.setText(titleFor(e));
        subtitle.setText(e.subtitle != null ? e.subtitle : "");
        subtitle.setVisibility(e.subtitle != null ? View.VISIBLE : View.GONE);
        shelfText.setAlpha(0f);
        shelfText.setTranslationY(Ui.dp(this, 12));
        shelfText.animate().alpha(1f).translationY(0).setDuration(320).setInterpolator(ease).start();

        art.removeAllViews();
        View a;
        if (live) {
            a = new MirrorArt(this);
            art.addView(a, new FrameLayout.LayoutParams(-1, -1));
        } else {
            int fw = Ui.dp(this, 400), fh = Ui.dp(this, 250);
            a = Tile.face(this, e, fw, fh, Ui.dp(this, 24), 1f);
            art.addView(a, new FrameLayout.LayoutParams(fw, fh, Gravity.CENTER));
        }
        a.setAlpha(0f);
        a.setScaleX(0.97f);
        a.setScaleY(0.97f);
        a.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(ease).start();
    }

    /**
     * The whole shelf background as one small picture, scaled up: the app's blurred art or a colour wash,
     * with both darkening gradients baked in. One full-screen layer instead of four keeps the Mali-450 fast.
     */
    private android.graphics.drawable.Drawable shelfPicture(Entry e) {
        int w = 480, h = 270;
        android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(b);
        c.drawColor(Ui.INK);
        android.graphics.drawable.Drawable base;
        if (e.isBuiltin()) {
            float scale = w / (float) getResources().getDisplayMetrics().widthPixels;
            base = Ui.wash(e.tint, Ui.dp(this, 640) * scale, 0.78f, 0.22f);
        } else {
            base = Ui.blurred(this, e.banner != null ? e.banner : e.storeIcon != null ? e.storeIcon : e.icon);
        }
        if (base != null) {
            base.setBounds(0, 0, w, h);
            base.draw(c);
        }
        GradientDrawable sh = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xE6050506, 0x8C050506, 0x26050506});
        sh.setBounds(0, 0, w, h);
        sh.draw(c);
        GradientDrawable sv = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00050506, 0x1A050506, 0xFF050506, 0xFF050506});
        sv.setBounds(0, 0, w, h);
        sv.draw(c);
        android.graphics.drawable.BitmapDrawable d = new android.graphics.drawable.BitmapDrawable(getResources(), b);
        d.setFilterBitmap(true);
        return d;
    }

    private static String titleFor(Entry e) {
        switch (e.id) {
            case "mirror": return "Mirror any screen";
            case "hdmi": return "Inputs";
            case "media": return "Media from USB";
            case "settings": return "Settings";
            case "apps": return "All apps";
            case "updates": return "Update every app";
            default: return e.title;
        }
    }

    @Override
    public void onOpen(Tile t) {
        Entry e = t.entry;
        try {
            switch (e.id) {
                case "mirror":
                    startActivity(new Intent(this, MirrorActivity.class));
                    return;
                case "apps":
                    startActivity(new Intent(this, AllAppsActivity.class));
                    return;
                case "hdmi":
                    InputActivity.open(this);
                    return;
                case "media":
                    startActivity(new Intent(this, MediaActivity.class));
                    return;
                case "settings":
                    startActivity(new Intent(this, SettingsActivity.class));
                    return;
                case "nowplaying":
                    startActivity(new Intent("com.muxover.oriel.NOW_PLAYING").setPackage(getPackageName()));
                    return;
                case "updates":
                    Updater.updateAll(this);
                    return;
                default:
                    if (e.intent == null) {
                        Toaster.show(this, e.title + " is not available");
                        return;
                    }
                    LaunchOverlay.open(this, e, e.intent);
            }
        } catch (RuntimeException ex) {
            Log.e(TAG, "could not open " + e.id, ex);
            Toaster.show(this, "Could not open " + e.title);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) return true;
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(android.view.MotionEvent e) {
        Ui.hover(this, e);
        return super.dispatchGenericMotionEvent(e);
    }
}
