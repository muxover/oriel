package com.muxover.oriel.home;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.usage.NetworkStats;
import android.app.usage.NetworkStatsManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.DhcpInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.os.SystemClock;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.Settings;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Oriel Settings: sections on the left, the chosen section's rows on the right, sub pages inside a section. */
public class SettingsActivity extends Activity {
    private static final String TAG = "Oriel";
    private static final String[] SECTIONS = {"Network", "Bluetooth", "Screen mirroring",
        "Appearance", "Clock and date", "Screen saver", "Night mode", "Name and place",
        "Prayer times", "Football",
        "Remote and keyboard", "Picture and sound", "Apps", "Storage", "About"};
    static final int NETWORK = 0, BLUETOOTH = 1, MIRRORING = 2, APPEARANCE = 3, CLOCK = 4, SAVER = 5, NIGHT = 6,
        PLACE = 7, PRAYERS = 8, MATCHES = 9, REMOTE = 10, PICTURE = 11, APPS = 12, STORAGE = 13, ABOUT = 14;
    /** Group titles in the section list, by the first section under each. */
    private static final Map<Integer, String> GROUPS = new HashMap<>();
    static {
        GROUPS.put(NETWORK, "Connections");
        GROUPS.put(APPEARANCE, "Look and feel");
        GROUPS.put(PRAYERS, "On the screen saver");
        GROUPS.put(REMOTE, "This TV");
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout pane;
    private ScrollView paneScroll;
    private TextView crumb;
    private final List<TextView> navItems = new ArrayList<>();
    private int current = -1;
    /** Rebuilds the open sub page, or null when a section's main page shows. */
    private Runnable subPage;
    private WifiManager wifi;
    private BluetoothAdapter bt;
    private final Map<String, BluetoothDevice> found = new HashMap<>();
    private String speedResult;
    private String pendingUninstall;
    private boolean confirmWifiOff, confirmForget;
    private Runnable pendingSection = () -> { };
    private FrameLayout overlay;
    /** Rows are added to this card until a label or note closes it. */
    private LinearLayout group;

    private final BroadcastReceiver radios = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (BluetoothDevice.ACTION_FOUND.equals(i.getAction())) {
                BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (d != null && d.getName() != null) found.put(d.getAddress(), d);
            }
            String a = i.getAction() != null ? i.getAction() : "";
            if (WifiManager.WIFI_STATE_CHANGED_ACTION.equals(a) && wifi != null && wifi.isWifiEnabled()) wifi.startScan();
            if (a.startsWith("android.intent.action.PACKAGE_")) apps = null;
            boolean radio = a.startsWith("android.net.wifi.") || a.startsWith("android.bluetooth.");
            boolean media = a.startsWith("android.intent.action.MEDIA_");
            if ((radio && (current == NETWORK || current == BLUETOOTH))
                || (media && current == STORAGE)
                || (a.startsWith("android.intent.action.PACKAGE_") && current == APPS)) {
                liveRefresh();
            }
        }
    };

    /** Network changes that the Wi-Fi broadcasts miss (getting an address, internet coming up). */
    private final android.net.ConnectivityManager.NetworkCallback network = new android.net.ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(android.net.Network n) {
            handler.post(() -> { if (current == NETWORK) liveRefresh(); });
        }

        @Override
        public void onLost(android.net.Network n) {
            handler.post(() -> { if (current == NETWORK) liveRefresh(); });
        }

        @Override
        public void onLinkPropertiesChanged(android.net.Network n, android.net.LinkProperties lp) {
            handler.post(() -> { if (current == NETWORK) liveRefresh(); });
        }
    };

    /** Pages whose figures change on their own (memory, storage, uptime, data used) are redrawn this often. */
    private static final long TICK_MS = 5000;
    private int ticks;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (current == ABOUT || current == STORAGE || (current == NETWORK && subPage != null)) liveRefresh();
            // New networks appear without leaving the page: a fresh scan every third tick (the results redraw it).
            if (current == NETWORK && subPage == null && wifi != null && wifi.isWifiEnabled() && ++ticks % 3 == 0) wifi.startScan();
            handler.postDelayed(this, TICK_MS);
        }
    };

    /**
     * Redraws the page showing because something it shows changed. Changes arriving close together are drawn
     * once; the remote stays on the same row, and nothing is redrawn while text is being typed or a popup is open.
     */
    private final Runnable live = () -> {
        View f = getCurrentFocus();
        if (overlay.getVisibility() == View.VISIBLE || f instanceof EditText) {
            liveRefresh();
            return;
        }
        refresh();
    };

    private void liveRefresh() {
        handler.removeCallbacks(live);
        handler.postDelayed(live, 350);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        bt = BluetoothAdapter.getDefaultAdapter();

        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Ui.INK);
        LinearLayout root = new LinearLayout(this);
        frame.addView(root, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.VERTICAL);
        nav.setPadding(Ui.dp(this, 64), Ui.dp(this, 48), Ui.dp(this, 32), Ui.dp(this, 32));
        TextView h = Ui.text(this, "Settings", 40, Ui.bold, Ui.PAPER);
        h.setLetterSpacing(-0.02f);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-2, -2);
        hp.bottomMargin = Ui.dp(this, 24);
        hp.leftMargin = Ui.dp(this, 16);
        nav.addView(h, hp);
        ScrollView navScroll = new ScrollView(this);
        navScroll.setVerticalScrollBarEnabled(false);
        LinearLayout navList = new LinearLayout(this);
        navList.setOrientation(LinearLayout.VERTICAL);
        navScroll.addView(navList, new FrameLayout.LayoutParams(-1, -2));
        nav.addView(navScroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        for (int i = 0; i < SECTIONS.length; i++) {
            final int idx = i;
            String group = GROUPS.get(i);
            if (group != null) {
                TextView g = Ui.text(this, group.toUpperCase(Locale.US), 12, Ui.medium, Ui.MUTED);
                g.setLetterSpacing(0.08f);
                g.setPadding(Ui.dp(this, 16), 0, 0, 0);
                LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(-2, -2);
                gp.topMargin = Ui.dp(this, i == 0 ? 0 : 20);
                gp.bottomMargin = Ui.dp(this, 8);
                navList.addView(g, gp);
            }
            TextView t = Ui.text(this, SECTIONS[i], 18, Ui.regular, Ui.TEXT2);
            t.setGravity(Gravity.CENTER_VERTICAL);
            t.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0);
            t.setFocusable(true);
            t.setOnFocusChangeListener((v, has) -> {
                if (rebuilding) return;
                if (has) Log.i(TAG, "settings focus section " + SECTIONS[idx]);
                if (has && (idx != current || subPage != null)) {
                    // Build the section a moment after the remote stops on it, so scrolling the list stays smooth.
                    current = idx;
                    subPage = null;
                    crumb.setText(SECTIONS[idx]);
                    handler.removeCallbacks(pendingSection);
                    pendingSection = () -> showSection(idx);
                    handler.postDelayed(pendingSection, 140);
                }
                styleNav();
            });
            t.setOnClickListener(v -> {
                handler.removeCallbacks(pendingSection);
                if (pane.getChildCount() == 0) showSection(idx);
                focusPane();
            });
            // Right from the list always lands on the section's first row, not whichever row is level with it.
            t.setOnKeyListener((v, code, e) -> {
                if (code != KeyEvent.KEYCODE_DPAD_RIGHT || e.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (handler.hasCallbacks(pendingSection) || pane.getChildCount() == 0) {
                    handler.removeCallbacks(pendingSection);
                    showSection(idx);
                }
                focusPane();
                return true;
            });
            navList.addView(t, new LinearLayout.LayoutParams(Ui.dp(this, 300), Ui.dp(this, 42)));
            navItems.add(t);
        }
        root.addView(nav, new LinearLayout.LayoutParams(Ui.dp(this, 400), -1));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        crumb = Ui.text(this, "", 28, Ui.semibold, Ui.PAPER);
        crumb.setLetterSpacing(-0.01f);
        crumb.setPadding(Ui.dp(this, 40), Ui.dp(this, 60), 0, Ui.dp(this, 20));
        right.addView(crumb, new LinearLayout.LayoutParams(-1, -2));
        paneScroll = new ScrollView(this);
        paneScroll.setVerticalScrollBarEnabled(false);
        paneScroll.setClipChildren(false);
        paneScroll.setClipToPadding(false);
        pane = new LinearLayout(this);
        pane.setOrientation(LinearLayout.VERTICAL);
        pane.setClipChildren(false);
        pane.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 64), Ui.dp(this, 48));
        paneScroll.addView(pane, new FrameLayout.LayoutParams(-1, -2));
        right.addView(paneScroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        root.addView(right, new LinearLayout.LayoutParams(0, -1, 1f));
        overlay = new FrameLayout(this);
        overlay.setVisibility(View.GONE);
        frame.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        setContentView(frame);
        // After a restart (rare, as input changes no longer restart it) come back to the same section.
        int start = state != null ? state.getInt("section", NETWORK) : getIntent().getIntExtra("section", NETWORK);
        navItems.get(start).requestFocus();
        showSection(start);
        final String row = getIntent().getStringExtra("focus_row");
        if (row != null) {
            getIntent().removeExtra("focus_row");
            // after onResume has redrawn the page, so the row is not swapped out from under the focus
            handler.postDelayed(() -> {
                View v = pane.findViewWithTag(row);
                if (v != null) v.requestFocus(); else focusPane();
                styleNav();
            }, 300);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("section", current);
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter f = new IntentFilter();
        f.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        f.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        f.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        f.addAction(BluetoothDevice.ACTION_FOUND);
        f.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        registerReceiver(radios, f);
        IntentFilter drives = new IntentFilter(Intent.ACTION_MEDIA_MOUNTED);
        drives.addAction(Intent.ACTION_MEDIA_REMOVED);
        drives.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        drives.addDataScheme("file");
        registerReceiver(radios, drives);
        IntentFilter packages = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        packages.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packages.addAction(Intent.ACTION_PACKAGE_REPLACED);
        packages.addDataScheme("package");
        registerReceiver(radios, packages);
        android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm != null) cm.registerDefaultNetworkCallback(network);
        handler.postDelayed(tick, TICK_MS);
        // Coming back from another screen (a sub-activity, the keyboard, Android settings): show what changed.
        refresh();
        if (wifi != null && !wifi.startScan()) Log.i(TAG, "wifi scan not started (throttled)");
    }

    @Override
    protected void onPause() {
        unregisterReceiver(radios);
        handler.removeCallbacks(tick);
        handler.removeCallbacks(live);
        android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm != null) cm.unregisterNetworkCallback(network);
        if (bt != null && bt.isDiscovering()) bt.cancelDiscovery();
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        if (overlay.getVisibility() == View.VISIBLE) {
            closeOverlay();
            return;
        }
        if (subPage != null) {
            showSection(current);
            focusPane();
            return;
        }
        View f = getCurrentFocus();
        if (f != null && !navItems.contains(f)) {
            navItems.get(current).requestFocus();
            return;
        }
        super.onBackPressed();
    }

    private void focusPane() {
        List<View> rows = rows();
        if (!rows.isEmpty()) rows.get(0).requestFocus();
    }

    /** Every focusable row in the pane, in order, inside cards or not. */
    private List<View> rows() {
        List<View> out = new ArrayList<>();
        collect(pane, out);
        return out;
    }

    private static void collect(android.view.ViewGroup g, List<View> out) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View v = g.getChildAt(i);
            if (v.isFocusable()) out.add(v);
            else if (v instanceof android.view.ViewGroup) collect((android.view.ViewGroup) v, out);
        }
    }

    private boolean paneHasFocus() {
        View f = getCurrentFocus();
        return f != null && !navItems.contains(f);
    }

    private void styleNav() {
        for (int i = 0; i < navItems.size(); i++) {
            TextView t = navItems.get(i);
            boolean on = i == current;
            t.setTypeface(on ? Ui.semibold : Ui.regular);
            if (t.hasFocus()) {
                t.setBackground(Ui.round(Ui.PAPER, Ui.dp(this, Ui.R_S)));
                t.setTextColor(Ui.INK);
            } else {
                t.setBackground(on ? Ui.round(0x1AF4F3EF, Ui.dp(this, Ui.R_S)) : null);
                t.setTextColor(on ? Ui.PAPER : Ui.TEXT2);
            }
        }
    }

    private void showSection(int idx) {
        current = idx;
        subPage = null;
        confirmWifiOff = false;
        crumb.setText(SECTIONS[idx]);
        fill(sectionBuilder(idx), true);
        styleNav();
    }

    private Runnable sectionBuilder(final int idx) {
        return () -> {
            switch (idx) {
                case NETWORK: networkPage(); break;
                case BLUETOOTH: bluetoothPage(); break;
                case MIRRORING: mirroringPage(); break;
                case APPEARANCE: appearancePage(); break;
                case CLOCK: clockPage(); break;
                case PLACE: placePage(); break;
                case PRAYERS: prayersPage(); break;
                case MATCHES: matchesPage(); break;
                case NIGHT: nightPage(); break;
                case SAVER: saverPage(); break;
                case REMOTE: remotePage(); break;
                case PICTURE: picturePage(); break;
                case APPS: appsPage(); break;
                case STORAGE: storagePage(); break;
                default: aboutPage(); break;
            }
        };
    }

    /** Opens a page inside the current section; Back returns to the section. */
    private void openSub(String title, Runnable build) {
        subPage = build;
        confirmForget = false;
        pendingUninstall = null;
        crumb.setText(title);
        fill(build, true);
        focusPane();
    }

    /** Rebuilds what is showing, keeping focus on the same row index where possible. */
    /** True while refresh() puts focus back: the row is already where it was, so nothing scrolls. */
    private boolean quietFocus;

    private void refresh() {
        int scrollY = paneScroll.getScrollY();
        quietFocus = true;
        try {
            refreshKeepingFocus();
        } finally {
            quietFocus = false;
        }
        paneScroll.scrollTo(0, scrollY);
    }

    private void refreshKeepingFocus() {
        View f = getCurrentFocus();
        List<View> before = rows();
        boolean inPane = f != null && before.contains(f);
        Object key = inPane ? f.getTag() : null;
        int idx = inPane ? before.indexOf(f) : -1;
        if (subPage != null) fill(subPage, false); else fill(sectionBuilder(current), false);
        if (!inPane) return;
        // Keep the remote on the same row: by its title first, by position only if that row is gone.
        List<View> after = rows();
        for (View v : after) {
            if (key != null && key.equals(v.getTag())) {
                v.requestFocus();
                return;
            }
        }
        if (idx >= 0 && idx < after.size()) after.get(idx).requestFocus();
        else navItems.get(current).requestFocus();
    }

    /** True while the pane is rebuilt: Android moves focus to the section list when the focused row is removed. */
    private boolean rebuilding;

    private void fill(Runnable build, boolean fade) {
        rebuilding = true;
        pane.removeAllViews();
        group = null;
        build.run();
        group = null;
        rebuilding = false;
        if (fade) {
            pane.setAlpha(0f);
            pane.setTranslationX(Ui.dp(this, 16));
            pane.animate().alpha(1f).translationX(0).setDuration(Ui.ENTER_MS).setInterpolator(Ui.EASE).start();
        }
    }

    // ---- building blocks

    private void label(String s) {
        group = null;
        if (s.isEmpty()) {
            pane.addView(new View(this), new LinearLayout.LayoutParams(1, Ui.dp(this, 16)));
            return;
        }
        TextView t = Ui.text(this, s.toUpperCase(Locale.US), 13, Ui.medium, Ui.MUTED);
        t.setLetterSpacing(0.06f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = Ui.dp(this, 24);
        lp.bottomMargin = Ui.dp(this, 12);
        lp.leftMargin = Ui.dp(this, 24);
        pane.addView(t, lp);
    }

    private void note(String s) {
        group = null;
        TextView t = Ui.text(this, s, 16, Ui.regular, Ui.MUTED);
        t.setLineSpacing(0, 1.3f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.leftMargin = Ui.dp(this, 24);
        lp.rightMargin = Ui.dp(this, 24);
        lp.topMargin = Ui.dp(this, 8);
        lp.bottomMargin = Ui.dp(this, 8);
        pane.addView(t, lp);
    }

    /** A row; "On" and "Off" values draw a switch. */
    private View row(String title, String value, Runnable action) {
        View right = null;
        if ("On".equals(value) || "Off".equals(value)) {
            right = new Glyphs.Switch(this, "On".equals(value));
            right.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 32)));
            value = null;
        }
        return row(title, value, right, action);
    }

    private View row(String title, String value, final View right, final Runnable action) {
        final LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dp(this, 24), 0, Ui.dp(this, 24), 0);
        r.setBackground(Ui.round(Ui.GRAPHITE, Ui.dp(this, Ui.R_M)));
        r.setFocusable(true);
        r.setTag(title);
        final TextView t = Ui.text(this, title, 20, Ui.medium, Ui.PAPER);
        t.setSingleLine(true);
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        final TextView v = Ui.text(this, value == null ? "" : value, 16, Ui.regular, Ui.MUTED);
        v.setSingleLine(true);
        r.addView(v);
        if (right != null) {
            LinearLayout.LayoutParams rp = right.getLayoutParams() instanceof LinearLayout.LayoutParams
                ? (LinearLayout.LayoutParams) right.getLayoutParams() : new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 24));
            rp.leftMargin = Ui.dp(this, 16);
            r.addView(right, rp);
        }
        if (action != null) {
            ImageChevron ch = new ImageChevron(this);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(Ui.dp(this, 16), Ui.dp(this, 16));
            cp.leftMargin = Ui.dp(this, 12);
            if (right == null) r.addView(ch, cp);
        }
        r.setOnFocusChangeListener((view, has) -> {
            if (has) Log.i(TAG, "settings focus " + title);
            Ui.focusLook(r, has, Ui.GRAPHITE, Ui.R_M, t, v);
            if (right instanceof Glyphs.Signal) ((Glyphs.Signal) right).setColor(has ? Ui.PAPER_INK : Ui.MUTED);
            for (int i = 0; i < r.getChildCount(); i++) {
                if (r.getChildAt(i) instanceof ImageChevron) ((ImageChevron) r.getChildAt(i)).setColor(has ? Ui.PAPER_INK : Ui.MUTED);
            }
        });
        if (action != null) r.setOnClickListener(x -> action.run());
        r.setBackground(null);
        r.setOnFocusChangeListener(chain(r.getOnFocusChangeListener(), r));
        card().addView(r, new LinearLayout.LayoutParams(-1, Ui.dp(this, Ui.ROW_H)));
        return r;
    }

    /** One option of several: a radio mark on the right, no chevron, since OK picks it rather than opening anything. */
    private View choice(String title, boolean on, Runnable action) {
        View radio = new Glyphs.Radio(this, on);
        radio.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24)));
        return row(title, null, radio, action);
    }

    /** A plain fact row: not focusable, so the remote skips it. */
    private void fact(String title, String value) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dp(this, 24), 0, Ui.dp(this, 24), 0);
        TextView t = Ui.text(this, title, 18, Ui.regular, Ui.TEXT2);
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        final TextView v = Ui.text(this, value, 18, Ui.medium, Ui.PAPER);
        v.setSingleLine(true);
        r.addView(v);
        // Focusable so the remote can scroll through long pages; it lights quietly and does nothing on OK.
        r.setFocusable(true);
        r.setTag(title);
        r.setOnFocusChangeListener((x, has) -> {
            r.setBackground(has ? Ui.round(Ui.LINE, Ui.dp(this, Ui.R_M)) : null);
            if (has && !quietFocus) r.post(() -> paneScroll.smoothScrollTo(0, Math.max(0, top(r) - paneScroll.getHeight() / 3)));
        });
        card().addView(r, new LinearLayout.LayoutParams(-1, Ui.dp(this, 56)));
    }

    /** The card rows are going into: graphite, rounded, rows divided by hairlines. */
    private LinearLayout card() {
        if (group == null) {
            group = new LinearLayout(this);
            group.setOrientation(LinearLayout.VERTICAL);
            group.setBackground(Ui.round(Ui.GRAPHITE, Ui.dp(this, Ui.R_M)));
            group.setClipChildren(false);
            group.setClipToPadding(false);
            group.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
            android.graphics.drawable.GradientDrawable line = Ui.round(Ui.LINE, 0);
            line.setSize(1, Math.max(1, Ui.dp(this, 1) / 1));
            group.setDividerDrawable(new android.graphics.drawable.InsetDrawable(line, Ui.dp(this, 24), 0, 0, 0));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = Ui.dp(this, 8);
            pane.addView(group, lp);
        }
        return group;
    }

    /** A focused row inside a card lifts above it with its own paper surface and a shadow. */
    private View.OnFocusChangeListener chain(final View.OnFocusChangeListener inner, final View r) {
        return (v, has) -> {
            inner.onFocusChange(v, has);
            if (!has) r.setBackground(null);
            r.setElevation(has ? Ui.dp(this, 12) : 0);
            if (has && !quietFocus) r.post(() -> paneScroll.smoothScrollTo(0, Math.max(0, top(r) - paneScroll.getHeight() / 3)));
        };
    }

    private int top(View v) {
        int y = 0;
        while (v != null && v != pane) {
            y += v.getTop();
            v = (View) v.getParent();
        }
        return y;
    }

    /** A text field in the Oriel style; the Oriel keyboard opens when it is chosen. */
    private EditText field(String hint, boolean password, Runnable done) {
        final EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(Ui.MUTED);
        e.setTextColor(Ui.PAPER);
        e.setTypeface(Ui.medium);
        e.setTextSize(20);
        e.setSingleLine(true);
        e.setPadding(Ui.dp(this, 24), 0, Ui.dp(this, 24), 0);
        // The field sits in a frame that draws the card and, when chosen, a violet ring; the field itself stays
        // unscaled and transparent so nothing it draws (scrolling text, cursor) can shift or clip the outline.
        e.setBackground(null);
        final FrameLayout frame = new FrameLayout(this);
        frame.setBackground(Ui.round(Ui.GRAPHITE, Ui.dp(this, Ui.R_M)));
        frame.addView(e, new FrameLayout.LayoutParams(-1, -1));
        e.setInputType(InputType.TYPE_CLASS_TEXT | (password ? InputType.TYPE_TEXT_VARIATION_PASSWORD : 0));
        e.setTypeface(Ui.medium); // password fields switch to monospace otherwise
        e.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        e.setOnFocusChangeListener((v, has) -> {
            android.graphics.drawable.GradientDrawable g = Ui.round(Ui.GRAPHITE, Ui.dp(this, Ui.R_M));
            if (has) g.setStroke(Ui.dp(this, 2), Ui.ACCENT);
            frame.setBackground(g);
        });
        e.setOnEditorActionListener((v, action, ev) -> {
            if (done != null) done.run();
            return done != null;
        });
        e.setOnKeyListener((v, code, ev) -> {
            if (code == KeyEvent.KEYCODE_DPAD_CENTER && ev.getAction() == KeyEvent.ACTION_UP) {
                ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(e, 0);
                return true;
            }
            return false;
        });
        group = null;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(this, Ui.ROW_H));
        lp.bottomMargin = Ui.dp(this, 8);
        pane.addView(frame, lp);
        return e;
    }

    // ---- Network

    @SuppressWarnings("deprecation")
    private void networkPage() {
        if (wifi == null) {
            note("This TV has no Wi-Fi.");
            return;
        }
        final boolean on = wifi.isWifiEnabled();
        row(confirmWifiOff ? "Press OK again to turn Wi-Fi off" : "Wi-Fi", on ? "On" : "Off", () -> {
            if (on && !confirmWifiOff) {
                // Turning Wi-Fi off also cuts the phone remote and mirroring, so it takes a second press.
                confirmWifiOff = true;
                refresh();
                return;
            }
            confirmWifiOff = false;
            if (!wifi.setWifiEnabled(!on)) Shell.send(this, "sh svc wifi " + (on ? "disable" : "enable"));
            handler.postDelayed(this::refresh, 1500);
        });
        if (!on) return;
        WifiInfo info = wifi.getConnectionInfo();
        final String ssid = connectedSsid();
        if (ssid != null) {
            label("Connected");
            int level = WifiManager.calculateSignalLevel(info.getRssi(), 4);
            View sig = new Glyphs.Signal(this, Math.max(1, level), false);
            sig.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 24)));
            row(ssid, QUALITY[clamp(level)] + " · " + band(info.getFrequency()), sig, () -> openSub(ssid, this::wifiDetails));
            row("Test internet speed", speedResult != null ? speedResult : "Uses about 10 MB", this::speedPopup);
        }
        label("Other networks");
        List<ScanResult> list = networks(ssid);
        if (list.isEmpty()) note("Searching for networks");
        for (final ScanResult r : list) {
            final boolean secured = secured(r);
            View sig = new Glyphs.Signal(this, Math.max(1, WifiManager.calculateSignalLevel(r.level, 4)), secured);
            sig.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 24)));
            row(r.SSID, null, sig, () -> join(r.SSID, secured));
        }
    }

    private static final String[] QUALITY = {"Weak", "Fair", "Good", "Excellent"};

    private static int clamp(int level) {
        return Math.max(0, Math.min(3, level));
    }

    private String connectedSsid() {
        WifiInfo info = wifi.getConnectionInfo();
        if (info == null || info.getNetworkId() == -1) return null;
        String s = info.getSSID().replace("\"", "");
        return s.startsWith("<") ? null : s;
    }

    private List<ScanResult> networks(String skip) {
        Map<String, ScanResult> best = new HashMap<>();
        try {
            for (ScanResult r : wifi.getScanResults()) {
                if (r.SSID == null || r.SSID.isEmpty() || r.SSID.equals(skip)) continue;
                ScanResult b = best.get(r.SSID);
                if (b == null || r.level > b.level) best.put(r.SSID, r);
            }
        } catch (SecurityException ex) {
            Log.w(TAG, "no permission to list networks", ex);
        }
        List<ScanResult> list = new ArrayList<>(best.values());
        list.sort((a, b) -> b.level - a.level);
        return list;
    }

    private static boolean secured(ScanResult r) {
        return r.capabilities.contains("WPA") || r.capabilities.contains("WEP") || r.capabilities.contains("SAE");
    }

    private static String security(String caps) {
        if (caps == null) return "Unknown";
        if (caps.contains("SAE")) return "WPA3";
        if (caps.contains("WPA2") || caps.contains("RSN")) return "WPA2";
        if (caps.contains("WPA")) return "WPA";
        if (caps.contains("WEP")) return "WEP";
        return "Open";
    }

    private static String band(int mhz) {
        if (mhz > 4900) return "5 GHz";
        return "2.4 GHz";
    }

    private static String channel(int mhz) {
        if (mhz >= 2412 && mhz <= 2484) return String.valueOf(mhz == 2484 ? 14 : (mhz - 2407) / 5);
        if (mhz >= 5000) return String.valueOf((mhz - 5000) / 5);
        return "?";
    }

    private static String ip(int a) {
        return String.format(Locale.US, "%d.%d.%d.%d", a & 0xff, (a >> 8) & 0xff, (a >> 16) & 0xff, (a >> 24) & 0xff);
    }

    @SuppressWarnings("deprecation")
    private void wifiDetails() {
        WifiInfo info = wifi.getConnectionInfo();
        if (info == null || connectedSsid() == null) {
            note("Not connected any more.");
            return;
        }
        DhcpInfo d = wifi.getDhcpInfo();
        String caps = null;
        for (ScanResult r : networks(null)) {
            if (r.SSID.equals(connectedSsid())) caps = r.capabilities;
        }
        int level = WifiManager.calculateSignalLevel(info.getRssi(), 4);
        label("Connection");
        fact("Status", "Connected · " + QUALITY[clamp(level)]);
        fact("Signal", info.getRssi() + " dBm");
        fact("Link speed", info.getLinkSpeed() + " Mbps");
        fact("Band", band(info.getFrequency()) + " · channel " + channel(info.getFrequency()));
        fact("Security", security(caps));
        label("Addresses");
        fact("IP address", ip(info.getIpAddress()));
        if (d != null) {
            fact("Router", ip(d.gateway));
            fact("Subnet mask", d.netmask != 0 ? ip(d.netmask) : "255.255.255.0");
            fact("DNS", ip(d.dns1) + (d.dns2 != 0 ? ", " + ip(d.dns2) : ""));
        }
        label("Data used on Wi-Fi");
        final TextView today = factLive("Today");
        final TextView month = factLive("This month");
        new Thread(() -> {
            String t = usage(startOfDay()), m = usage(startOfMonth());
            handler.post(() -> {
                today.setText(t);
                month.setText(m);
            });
        }, "usage").start();
        label("");
        final int netId = info.getNetworkId();
        row(confirmForget ? "Press OK again to forget it" : "Forget this network", null, () -> {
            if (!confirmForget) {
                confirmForget = true;
                refresh();
                return;
            }
            confirmForget = false;
            Shell.send(this, "sh cmd wifi forget-network " + netId);
            Toaster.show(this, "Forgetting " + connectedSsid());
            handler.postDelayed(() -> {
                showSection(NETWORK);
                focusPane();
            }, 1500);
        });
    }

    private TextView factLive(String title) {
        fact(title, "Counting");
        LinearLayout r = (LinearLayout) group.getChildAt(group.getChildCount() - 1);
        return (TextView) r.getChildAt(1);
    }

    private static long startOfDay() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private static long startOfMonth() {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(startOfDay());
        c.set(Calendar.DAY_OF_MONTH, 1);
        return c.getTimeInMillis();
    }

    @SuppressWarnings("deprecation")
    private String usage(long from) {
        try {
            NetworkStatsManager nsm = (NetworkStatsManager) getSystemService(Context.NETWORK_STATS_SERVICE);
            NetworkStats.Bucket b = nsm.querySummaryForDevice(ConnectivityManager.TYPE_WIFI, "", from, System.currentTimeMillis());
            return size(b.getRxBytes() + b.getTxBytes());
        } catch (android.os.RemoteException | RuntimeException ex) {
            Log.w(TAG, "data usage not readable", ex);
            return "Not available";
        }
    }

    static String size(long bytes) {
        if (bytes >= 1L << 30) return String.format(Locale.US, "%.1f GB", bytes / (double) (1L << 30));
        if (bytes >= 1L << 20) return String.format(Locale.US, "%.0f MB", bytes / (double) (1L << 20));
        return String.format(Locale.US, "%.0f KB", bytes / 1024.0);
    }

    private volatile boolean testing;

    /** The speed test, in a card over Settings: live download speed, then download and ping. */
    private void speedPopup() {
        overlay.removeAllViews();
        overlay.setBackgroundColor(0xB3050506);
        overlay.setClickable(true);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        int p = Ui.dp(this, 48);
        card.setPadding(p, Ui.dp(this, 40), p, Ui.dp(this, 32));
        card.setBackground(Ui.round(Ui.GRAPHITE, Ui.dp(this, Ui.R_L)));
        card.setElevation(Ui.dp(this, 24));
        TextView title = Ui.text(this, "Internet speed", 20, Ui.medium, Ui.TEXT2);
        card.addView(title);
        final TextView big = Ui.text(this, "0", 120, Ui.light, Ui.PAPER);
        big.setLetterSpacing(-0.04f);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2);
        bp.topMargin = Ui.dp(this, 16);
        card.addView(big, bp);
        final TextView unit = Ui.text(this, "Mbps download", 18, Ui.regular, Ui.MUTED);
        card.addView(unit);
        final LaunchBar bar = new LaunchBar(this);
        LinearLayout.LayoutParams barp = new LinearLayout.LayoutParams(Ui.dp(this, 440), Ui.dp(this, 6));
        barp.topMargin = Ui.dp(this, 32);
        card.addView(bar, barp);
        final TextView facts = Ui.text(this, "Measuring", 18, Ui.medium, Ui.TEXT2);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-2, -2);
        fp.topMargin = Ui.dp(this, 20);
        card.addView(facts, fp);
        LinearLayout buttons = new LinearLayout(this);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
        blp.topMargin = Ui.dp(this, 32);
        card.addView(buttons, blp);
        final View again = pill(buttons, "Test again", () -> runSpeed(big, bar, facts));
        pill(buttons, "Done", this::closeOverlay);
        overlay.addView(card, new FrameLayout.LayoutParams(Ui.dp(this, 620), -2, Gravity.CENTER));
        overlay.setVisibility(View.VISIBLE);
        overlay.setAlpha(0f);
        card.setScaleX(0.96f);
        card.setScaleY(0.96f);
        overlay.animate().alpha(1f).setDuration(Ui.ENTER_MS).setInterpolator(Ui.EASE).start();
        card.animate().scaleX(1f).scaleY(1f).setDuration(Ui.ENTER_MS).setInterpolator(Ui.EASE).start();
        again.requestFocus();
        runSpeed(big, bar, facts);
    }

    private View pill(LinearLayout parent, String text, Runnable action) {
        final TextView b = Ui.text(this, text, 18, Ui.semibold, Ui.PAPER);
        b.setGravity(Gravity.CENTER);
        b.setPadding(Ui.dp(this, 32), 0, Ui.dp(this, 32), 0);
        b.setBackground(Ui.round(Ui.LINE, Ui.dp(this, Ui.R_PILL)));
        b.setFocusable(true);
        b.setOnFocusChangeListener((v, has) -> Ui.focusLook(b, has, Ui.LINE, Ui.R_PILL, b));
        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, Ui.dp(this, Ui.PILL_H));
        if (parent.getChildCount() > 0) lp.leftMargin = Ui.dp(this, 16);
        parent.addView(b, lp);
        return b;
    }

    private void closeOverlay() {
        testing = false;
        overlay.animate().alpha(0f).setDuration(160).withEndAction(() -> {
            overlay.setVisibility(View.GONE);
            overlay.removeAllViews();
            refresh();
            focusPane();
        }).start();
    }

    private void runSpeed(final TextView big, final LaunchBar bar, final TextView facts) {
        if (testing) return;
        testing = true;
        big.setText("0");
        facts.setText("Measuring");
        bar.setProgress(0f);
        new Thread(() -> {
            String result;
            try {
                // Ping: the best of four tiny requests; the first also pays for the TLS handshake, so it rarely wins.
                long pingMs = Long.MAX_VALUE;
                for (int i = 0; i < 4; i++) {
                    long t0 = SystemClock.elapsedRealtime();
                    HttpURLConnection ping = (HttpURLConnection) new URL("https://speed.cloudflare.com/__down?bytes=0").openConnection();
                    ping.setConnectTimeout(8000);
                    try (InputStream in = ping.getInputStream()) {
                        while (in.read() >= 0) { /* drain so the connection is reused */ }
                    }
                    pingMs = Math.min(pingMs, SystemClock.elapsedRealtime() - t0);
                }
                final long size = 10_000_000L;
                HttpURLConnection c = (HttpURLConnection) new URL("https://speed.cloudflare.com/__down?bytes=" + size).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(15000);
                long start = SystemClock.elapsedRealtime(), total = 0, shown = start;
                try (InputStream in = c.getInputStream()) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while (testing && (n = in.read(buf)) > 0) {
                        total += n;
                        long now = SystemClock.elapsedRealtime();
                        if (now - shown > 200) {
                            shown = now;
                            final double mbps = total * 8 / Math.max(0.001, (now - start) / 1000.0) / 1e6;
                            final float prog = total / (float) size;
                            handler.post(() -> {
                                big.setText(String.format(Locale.US, "%.1f", mbps));
                                bar.setProgress(prog);
                            });
                        }
                    }
                } finally {
                    c.disconnect();
                }
                double secs = Math.max(0.001, (SystemClock.elapsedRealtime() - start) / 1000.0);
                final double mbps = total * 8 / secs / 1e6;
                result = String.format(Locale.US, "%.1f Mbps", mbps);
                final String line = String.format(Locale.US, "Ping %d ms  ·  %s used", pingMs, size(total));
                handler.post(() -> {
                    big.setText(String.format(Locale.US, "%.1f", mbps));
                    bar.setProgress(1f);
                    facts.setText(line);
                });
            } catch (IOException ex) {
                Log.w(TAG, "speed test failed", ex);
                result = "No internet";
                handler.post(() -> facts.setText("No internet connection"));
            }
            final String r = result;
            handler.post(() -> {
                speedResult = r;
                testing = false;
            });
        }, "speed").start();
    }

    @SuppressWarnings("deprecation")
    private void join(final String ssid, boolean secured) {
        for (WifiConfiguration c : configured()) {
            if (("\"" + ssid + "\"").equals(c.SSID)) {
                wifi.enableNetwork(c.networkId, true);
                Toaster.show(this, "Connecting to " + ssid);
                return;
            }
        }
        if (!secured) {
            connect(ssid, null);
            return;
        }
        openSub(ssid, () -> {
            note("Enter the password for " + ssid + ".");
            final EditText pass = field("Password", true, null);
            final boolean[] shown = {false};
            row("Show password", "Off", null).setOnClickListener(v -> {
                shown[0] = !shown[0];
                pass.setInputType(InputType.TYPE_CLASS_TEXT | (shown[0] ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD : InputType.TYPE_TEXT_VARIATION_PASSWORD));
                pass.setTypeface(Ui.medium);
                pass.setSelection(pass.getText().length());
                LinearLayout r = (LinearLayout) v;
                View old = r.getChildAt(r.getChildCount() - 1);
                r.removeView(old);
                Glyphs.Switch sw = new Glyphs.Switch(this, shown[0]);
                r.addView(sw, old.getLayoutParams());
            });
            final View joinRow = row("Join", null, () -> {
                if (pass.getText().length() < 8) {
                    Toaster.show(this, "Wi-Fi passwords have at least 8 characters");
                    return;
                }
                connect(ssid, pass.getText().toString());
                showSection(NETWORK);
                focusPane();
            });
            pass.setOnEditorActionListener((v, a, ev) -> {
                joinRow.requestFocus();
                return true;
            });
            pass.requestFocus();
        });
    }

    @SuppressWarnings("deprecation")
    private List<WifiConfiguration> configured() {
        try {
            List<WifiConfiguration> l = wifi.getConfiguredNetworks();
            return l != null ? l : new ArrayList<>();
        } catch (SecurityException ex) {
            Log.w(TAG, "saved networks not readable", ex);
            return new ArrayList<>();
        }
    }

    @SuppressWarnings("deprecation")
    private void connect(String ssid, String password) {
        WifiConfiguration c = new WifiConfiguration();
        c.SSID = "\"" + ssid + "\"";
        if (password == null) {
            c.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        } else {
            c.preSharedKey = "\"" + password + "\"";
        }
        int id = wifi.addNetwork(c);
        if (id < 0 || !wifi.enableNetwork(id, true)) {
            Toaster.show(this, "Could not join " + ssid);
            return;
        }
        Toaster.show(this, "Joining " + ssid);
    }

    // ---- Bluetooth

    private void bluetoothPage() {
        if (bt == null) {
            note("This TV has no Bluetooth.");
            return;
        }
        if (Prefs.of(this).getBoolean("bt_broken", false)) {
            fact("Bluetooth", "Not available");
            note("Bluetooth did not start on this TV. Android lists it, but the TV may have no Bluetooth chip, or its driver fails.");
            row("Try again", null, () -> {
                Prefs.of(this).edit().putBoolean("bt_broken", false).apply();
                switchBluetooth(true);
            });
            return;
        }
        boolean on = bt.isEnabled();
        row(btStarting ? "Turning Bluetooth on" : "Bluetooth", on ? "On" : "Off", () -> switchBluetooth(!bt.isEnabled()));
        if (!on) {
            note("Headphones, speakers, game controllers and keyboards connect here.");
            return;
        }
        try {
            label("My devices");
            if (bt.getBondedDevices().isEmpty()) note("No devices yet. Put a device in pairing mode, then search.");
            for (BluetoothDevice d : bt.getBondedDevices()) row(d.getName(), "Paired", null);
            label("Nearby");
            row(bt.isDiscovering() ? "Searching" : "Search for devices", null, () -> {
                found.clear();
                bt.startDiscovery();
                handler.postDelayed(this::refresh, 400);
            });
            for (final BluetoothDevice d : found.values()) {
                if (d.getBondState() == BluetoothDevice.BOND_BONDED) continue;
                row(d.getName(), d.getBondState() == BluetoothDevice.BOND_BONDING ? "Pairing" : "Pair", () -> {
                    bt.cancelDiscovery();
                    if (!d.createBond()) Toaster.show(this, "Could not pair with " + d.getName());
                });
            }
        } catch (SecurityException ex) {
            Log.w(TAG, "bluetooth not readable", ex);
            note("Bluetooth devices cannot be listed.");
        }
    }

    private boolean btStarting;

    /** Turns Bluetooth on or off; if it has not come on after eight seconds it is marked as not available and switched back off. */
    private void switchBluetooth(boolean on) {
        try {
            if (!on) {
                bt.disable();
                handler.postDelayed(this::refresh, 1000);
                return;
            }
            bt.enable();
        } catch (SecurityException ex) {
            Log.w(TAG, "bluetooth switch refused", ex);
            Toaster.show(this, "The TV did not let Oriel switch Bluetooth");
            return;
        }
        btStarting = true;
        refresh();
        final long until = SystemClock.uptimeMillis() + 8000;
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (bt.isEnabled()) {
                    btStarting = false;
                    refresh();
                } else if (SystemClock.uptimeMillis() < until) {
                    handler.postDelayed(this, 1000);
                } else {
                    btStarting = false;
                    Prefs.of(SettingsActivity.this).edit().putBoolean("bt_broken", true).apply();
                    bt.disable();
                    Toaster.show(SettingsActivity.this, "Bluetooth did not start on this TV");
                    refresh();
                }
            }
        });
    }

    // ---- Screen mirroring

    private void mirroringPage() {
        boolean airplay = installed("com.muxover.oriel.mirror");
        String name = Device.name(this);
        label("iPhone, iPad and Mac");
        fact("AirPlay", airplay ? "Ready as " + name : "Not installed");
        note("Control Center, Screen Mirroring, then " + name + ". Works from any screen; the TV does not need to be on Mirror.");
        label("Windows");
        fact("Miracast", "Ready as " + name);
        note("Press Win + K on the PC and pick " + name + ". Win + P, Second screen only, gives the sharpest picture. Back on the remote stops it.");
        label("");
        note("Android phones cannot mirror to Oriel. The name is set in Name and place.");
        row("Open Mirror", null, () -> startActivity(new Intent(this, MirrorActivity.class)));
    }

    // ---- Appearance

    private void appearancePage() {
        label("Accent colour");
        String now = Prefs.of(this).getString("accent", "Violet");
        for (final Object[] a : Ui.ACCENTS) {
            View dot = new Glyphs.Dot(this, (Integer) a[1], a[0].equals(now));
            dot.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24)));
            row((String) a[0], null, dot, () -> {
                Prefs.of(this).edit().putString("accent", (String) a[0]).apply();
                Ui.init(this);
                recreateKeeping(APPEARANCE, (String) a[0]);
            });
        }
        note("Switches, the keyboard's Done key, progress bars and the home screen's highlights take this colour.");
        label("Home screen");
        row("Clock", Prefs.of(this).getBoolean("home_clock", true) ? "On" : "Off", () -> {
            Prefs.of(this).edit().putBoolean("home_clock", !Prefs.of(this).getBoolean("home_clock", true)).apply();
            refresh();
        });
        row("App names under tiles", Prefs.of(this).getBoolean("captions", false) ? "On" : "Off", () -> {
            Prefs.of(this).edit().putBoolean("captions", !Prefs.of(this).getBoolean("captions", false)).apply();
            refresh();
        });
        note("With names off, an app's name shows only on the tile you are on.");
    }

    /** Builds this screen again in the new colours, on the same section and row. */
    private void recreateKeeping(int section, String row) {
        getIntent().putExtra("section", section);
        getIntent().putExtra("focus_row", row);
        recreate();
    }

    // ---- Clock and date

    private void clockPage() {
        String fmt = Prefs.clockFormat(this);
        boolean ampm = Prefs.clockAmPm(this);
        java.util.Date now = new java.util.Date();
        boolean twelve = "12".equals(fmt) || ("auto".equals(fmt) && !android.text.format.DateFormat.is24HourFormat(this));
        String timeText = new java.text.SimpleDateFormat(twelve ? "h:mm" + (ampm ? " a" : "") : "HH:mm", Locale.US).format(now);
        int ds = Prefs.dateStyle(this);
        String datePattern = Clock.DATE_STYLES[Math.max(0, Math.min(Clock.DATE_STYLES.length - 1, ds))][1];
        fact("Shows as", timeText + (datePattern.isEmpty() ? "" : "  ·  " + new java.text.SimpleDateFormat(datePattern, Locale.US).format(now)));
        label("Time");
        String[][] formats = {{"auto", "Same as the TV (" + Clock.tvFormat(this) + ")"}, {"12", "12-hour"}, {"24", "24-hour"}};
        for (final String[] f : formats) {
            choice(f[1], f[0].equals(fmt), () -> {
                Prefs.of(this).edit().putString("clock_format", f[0]).apply();
                refresh();
            });
        }
        if (twelve) {
            row("Show AM and PM", ampm ? "On" : "Off", () -> {
                Prefs.of(this).edit().putBoolean("clock_ampm", !Prefs.clockAmPm(this)).apply();
                refresh();
            });
        }
        label("Date");
        for (int i = 0; i < Clock.DATE_STYLES.length; i++) {
            final int k = i;
            String example = Clock.DATE_STYLES[i][1].isEmpty() ? Clock.DATE_STYLES[i][0]
                : new java.text.SimpleDateFormat(Clock.DATE_STYLES[i][1], Locale.US).format(now);
            choice(example, ds == i, () -> {
                Prefs.of(this).edit().putInt("date_style", k).apply();
                refresh();
            });
        }
        label("Temperature");
        boolean f = Prefs.fahrenheit(this);
        choice("Celsius", !f, () -> {
            Prefs.of(this).edit().putBoolean("temp_f", false).apply();
            refresh();
        });
        choice("Fahrenheit", f, () -> {
            Prefs.of(this).edit().putBoolean("temp_f", true).apply();
            refresh();
        });
        note("Used by the home screen and the screen saver.");
    }

    private boolean installed(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException ex) {
            return false;
        }
    }

    // ---- Name and place

    private void placePage() {
        label("This TV");
        row("Name", Device.name(this), () -> openSub("Name", () -> {
            note("Phones and computers see this name when they mirror or use the phone remote.");
            final EditText name = field("TV name", false, null);
            name.setText(Device.name(this));
            name.setSelection(name.getText().length());
            final View save = row("Save", null, () -> {
                Device.rename(this, name.getText().toString());
                Toaster.show(this, "This TV is now " + Device.name(this));
                showSection(PLACE);
                focusPane();
            });
            name.setOnEditorActionListener((v, a, ev) -> {
                save.requestFocus();
                return true;
            });
            name.requestFocus();
        }));
        String place = Prefs.placeName(this);
        row("City", place != null ? place : "Not set", () -> openSub("City", this::cityPage));
        note("Used for the weather on the screen saver and, if you turn them on, prayer times.");
    }

    /** City search through Open-Meteo's free geocoding service. */
    private void cityPage() {
        note("Type a city, then choose it from the list.");
        final EditText q = field("City", false, null);
        final View go = row("Search", null, null);
        final LinearLayout results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        pane.addView(results, new LinearLayout.LayoutParams(-1, -2));
        go.setOnClickListener(v -> {
            final String text = q.getText().toString().trim();
            if (text.isEmpty()) return;
            results.removeAllViews();
            new Thread(() -> {
                final List<String[]> found = new ArrayList<>();
                try {
                    String json = Weather.fetch("https://geocoding-api.open-meteo.com/v1/search?count=8&language=en&name="
                        + java.net.URLEncoder.encode(text, "UTF-8"));
                    org.json.JSONArray arr = new org.json.JSONObject(json).optJSONArray("results");
                    for (int i = 0; arr != null && i < arr.length(); i++) {
                        org.json.JSONObject o = arr.getJSONObject(i);
                        String where = o.optString("admin1", "");
                        String country = o.optString("country", "");
                        found.add(new String[]{o.getString("name"), (where.isEmpty() ? "" : where + ", ") + country,
                            String.valueOf(o.getDouble("latitude")), String.valueOf(o.getDouble("longitude"))});
                    }
                } catch (IOException | org.json.JSONException ex) {
                    Log.w(TAG, "city search failed", ex);
                }
                handler.post(() -> {
                    if (found.isEmpty()) {
                        Toaster.show(this, "No city found for " + text);
                        return;
                    }
                    // Rows go into a card inside the results area: point the card builder at it while adding them.
                    LinearLayout card = new LinearLayout(this);
                    card.setOrientation(LinearLayout.VERTICAL);
                    card.setBackground(Ui.round(Ui.GRAPHITE, Ui.dp(this, Ui.R_M)));
                    results.addView(card, new LinearLayout.LayoutParams(-1, -2));
                    LinearLayout keep = group;
                    group = card;
                    View first = null;
                    for (final String[] f : found) {
                        View r = row(f[0], f[1], () -> {
                            Prefs.setPlace(this, f[0], Double.parseDouble(f[2]), Double.parseDouble(f[3]));
                            Toaster.show(this, "City set to " + f[0]);
                            showSection(PLACE);
                            focusPane();
                        });
                        if (first == null) first = r;
                    }
                    group = keep;
                    first.requestFocus();
                });
            }, "city").start();
        });
        q.setOnEditorActionListener((v, a, ev) -> {
            go.performClick();
            return true;
        });
        q.requestFocus();
    }

    // ---- Prayer times

    private void prayersPage() {
        boolean on = Prefs.of(this).getBoolean("prayers_on", false);
        row("Prayer times", on ? "On" : "Off", () -> {
            Prefs.of(this).edit().putBoolean("prayers_on", !Prefs.of(this).getBoolean("prayers_on", false)).apply();
            refresh();
        });
        note("Shows the next prayer on the screen saver. Worked out on the TV, no account or internet needed.");
        if (!on) return;
        if (Prefs.placeName(this) == null) {
            row("Choose a city first", null, () -> {
                showSection(PLACE);
                focusPane();
            });
            return;
        }
        String[] next = PrayerTimes.next(this);
        if (next != null) fact("Next", next[0] + "  " + next[1]);
        label("Calculation method");
        String chosen = Prefs.prayerMethod(this);
        for (final PrayerTimes.Method m : PrayerTimes.METHODS) {
            choice(m.name, m.id.equals(chosen), () -> {
                Prefs.of(this).edit().putString("prayer_method", m.id).apply();
                refresh();
            });
        }
        label("Asr");
        boolean hanafi = Prefs.asrHanafi(this);
        choice("Standard (Shafi'i, Maliki, Hanbali)", !hanafi, () -> {
            Prefs.of(this).edit().putBoolean("asr_hanafi", false).apply();
            refresh();
        });
        choice("Hanafi", hanafi, () -> {
            Prefs.of(this).edit().putBoolean("asr_hanafi", true).apply();
            refresh();
        });
    }

    // ---- Matches

    private void matchesPage() {
        note("A live match from the leagues you turn on shows on the screen saver. Pick teams to see only their matches.");
        List<String> teams = Prefs.teamNames(this);
        row("Teams", teams.isEmpty() ? "All teams" : teams.size() == 1 ? teams.get(0) : teams.size() + " teams",
            () -> openSub("Teams", this::teamsPage));
        final java.util.Set<String> on = Prefs.leagues(this);
        for (final String[] l : Scores.LEAGUES) {
            if ("#".equals(l[0])) {
                label(l[1]);
                continue;
            }
            row(l[1], on.contains(l[0]) ? "On" : "Off", () -> {
                java.util.Set<String> now = Prefs.leagues(this);
                if (!now.remove(l[0])) now.add(l[0]);
                Prefs.of(this).edit().putStringSet("leagues", now).apply();
                refresh();
            });
        }
    }

    /** The teams followed, each removable, and a search that finds any team in the offered leagues as you type. */
    private void teamsPage() {
        List<String> teams = Prefs.teamNames(this);
        if (!teams.isEmpty()) {
            label("Following");
            for (final String t : teams) {
                row(t, "Remove", null, () -> {
                    List<String> now = Prefs.teamNames(this);
                    now.remove(t);
                    Prefs.setTeams(this, now);
                    refresh();
                });
            }
        }
        label("Add a team");
        final EditText q = field("Type a team name", false, null);
        final LinearLayout results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        pane.addView(results, new LinearLayout.LayoutParams(-1, -2));
        final Runnable show = () -> {
            results.removeAllViews();
            String text = q.getText().toString();
            if (text.trim().length() < 2) return;
            if (!Teams.ready()) {
                TextView wait = Ui.text(this, "Getting the team list", 16, Ui.regular, Ui.MUTED);
                wait.setPadding(Ui.dp(this, 24), Ui.dp(this, 8), 0, 0);
                results.addView(wait);
                return;
            }
            List<Teams.Team> found = Teams.search(text, 12);
            if (found.isEmpty()) {
                TextView none = Ui.text(this, "No team called " + text.trim() + " in the leagues Oriel follows", 16, Ui.regular, Ui.MUTED);
                none.setPadding(Ui.dp(this, 24), Ui.dp(this, 8), 0, 0);
                results.addView(none);
                return;
            }
            LinearLayout keep = group;
            group = null;
            LinearLayout card = card();
            pane.removeView(card);
            results.addView(card, new LinearLayout.LayoutParams(-1, -2));
            group = card;
            List<String> following = Prefs.teamNames(this);
            for (final Teams.Team t : found) {
                boolean has = following.contains(t.name);
                row(t.name, has ? "Following" : t.league, null, has ? null : () -> {
                    List<String> now = Prefs.teamNames(this);
                    if (!now.contains(t.name)) now.add(t.name);
                    Prefs.setTeams(this, now);
                    Toaster.show(this, "Following " + t.name);
                    openSub("Teams", this::teamsPage);
                });
            }
            group = keep;
        };
        q.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence x, int a, int b, int d) { }

            @Override
            public void onTextChanged(CharSequence x, int a, int b, int d) { }

            @Override
            public void afterTextChanged(android.text.Editable e) {
                handler.removeCallbacks(show);
                handler.postDelayed(show, 250);
            }
        });
        q.setOnEditorActionListener((v, a, ev) -> {
            List<View> rows = new ArrayList<>();
            collect(results, rows);
            if (!rows.isEmpty()) rows.get(0).requestFocus();
            return true;
        });
        Teams.load(this, ok -> {
            if (!ok) Toaster.show(this, "The team list could not be loaded. Check the internet connection.");
            else if (subPage != null) show.run();
        });
        q.requestFocus();
    }

    // ---- Night mode

    private void nightPage() {
        boolean on = Prefs.nightOn(this);
        row("Night mode", on ? "On" : "Off", () -> {
            Prefs.of(this).edit().putBoolean("night_on", !Prefs.nightOn(this)).apply();
            Night.apply(this);
            refresh();
        });
        note("Warms and dims the picture at night, over every app.");
        if (!on) return;
        label("Hours");
        final int[][] windows = {{22 * 60, 7 * 60}, {23 * 60, 7 * 60}, {0, 8 * 60}};
        for (final int[] w : windows) {
            boolean sel = Prefs.nightStart(this) == w[0] && Prefs.nightEnd(this) == w[1];
            choice(String.format(Locale.US, "%02d:00 to %02d:00", w[0] / 60, w[1] / 60), sel, () -> {
                Prefs.of(this).edit().putInt("night_start", w[0]).putInt("night_end", w[1]).apply();
                Night.apply(this);
                refresh();
            });
        }
        label("Strength");
        String[] names = {"Gentle", "Medium", "Strong"};
        for (int i = 1; i <= 3; i++) {
            final int s = i;
            choice(names[i - 1], Prefs.nightStrength(this) == i, () -> {
                Prefs.of(this).edit().putInt("night_strength", s).apply();
                Night.apply(this);
                refresh();
            });
        }
    }

    // ---- Screen saver

    private void saverPage() {
        note("A large clock over slow colours when the TV is left on the home screen. Music playing shows Now Playing instead.");
        label("Starts");
        int[] opts = {5, 10, 15, 30, 0};
        for (final int m : opts) {
            choice(m == 0 ? "Never" : "After " + m + " minutes", Prefs.saverMinutes(this) == m, () -> {
                Prefs.of(this).edit().putInt("saver_min", m).apply();
                refresh();
            });
        }
        label("Shows");
        String[][] parts = {{"weather", "Weather"}, {"prayer", "Next prayer"}, {"scores", "Live football"}};
        for (final String[] p : parts) {
            row(p[1], Prefs.saverShows(this, p[0]) ? "On" : "Off", () -> {
                Prefs.of(this).edit().putBoolean("saver_" + p[0], !Prefs.saverShows(this, p[0])).apply();
                refresh();
            });
        }
        note("Weather needs a city, prayer times need to be on, and football needs a league to follow.");
        label("");
        row("Show it now", null, () -> startActivity(new Intent(this, AmbientActivity.class)));
    }

    // ---- Remote and keyboard

    private void remotePage() {
        String ip = RemoteServer.address(this);
        label("Phone remote");
        row("Phone remote", Prefs.remoteOn(this) ? "On" : "Off", () -> {
            Prefs.of(this).edit().putBoolean("remote_on", !Prefs.remoteOn(this)).apply();
            RemoteServer.sync(this);
            refresh();
        });
        if (Prefs.remoteOn(this)) {
            fact("Address", ip != null ? "http://" + ip + ":" + RemoteServer.PORT : "Not on Wi-Fi");
            note("Open the address on a phone on the same Wi-Fi. Arrows or a mouse, typing into any app, volume, and Send to TV for links and files.");
        }
        label("Keyboard");
        fact("Keyboard", "Oriel");
        note("Used in every app. English and Arabic. Move with the remote, OK types, up from the top row closes it. While the phone remote is open, type on the phone instead, or press OK for the TV keyboard.");
        label("Remote");
        fact("Mouse mode", "Off");
        note("The remote never turns into a mouse pointer.");
    }

    // ---- Picture and sound

    private void picturePage() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        int max = Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        label("Sound");
        row("Volume", am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max + "  ·  left and right change it", null)
            .setOnKeyListener((v, code, e) -> {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, code == KeyEvent.KEYCODE_DPAD_RIGHT ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
                handler.postDelayed(this::refresh, 100);
                return true;
            });
        if (TvPicture.unavailable()) {
            label("Picture");
            note("This TV does not let Oriel change its picture. Android's own settings have what it offers.");
            row("Android settings", null, () -> open(new ComponentName("com.android.tv.settings", "com.android.tv.settings.MainSettings")));
            return;
        }
        if (!TvPicture.known()) {
            label("Picture");
            note("Reading the TV's picture settings");
            Runnable again = () -> {
                if (current == PICTURE && subPage == null) refresh();
            };
            TvPicture.read(this, again);
            handler.postDelayed(() -> {
                TvPicture.giveUp();
                again.run();
            }, 4000);
            return;
        }
        modeRow("Sound mode", "SoundMode", TvPicture.SOUND_MODES);
        label("Picture");
        modeRow("Picture mode", "PictureMode", TvPicture.PICTURE_MODES);
        stepRow("Backlight", "Backlight");
        stepRow("Brightness", "Brightness");
        stepRow("Contrast", "Contrast");
        stepRow("Colour", "Saturation");
        stepRow("Sharpness", "Sharpness");
        modeRow("Colour temperature", "ColorTemp", TvPicture.COLOUR_TEMPS);
        note("Changes apply at once and stay for every app and input.");
    }

    /** A setting with named choices: the row shows the current one, OK opens the list. */
    private void modeRow(String title, String key, String[] names) {
        int now = TvPicture.get(key);
        row(title, now >= 0 && now < names.length ? names[now] : "Unknown", () -> openSub(title, () -> {
            int cur = TvPicture.get(key);
            for (int i = 0; i < names.length; i++) {
                final int v = i;
                choice(names[i], i == cur, () -> {
                    TvPicture.set(this, key, v);
                    refresh();
                });
            }
        }));
    }

    /** A 0 to 100 setting: left and right change it in steps of 5. */
    private void stepRow(String title, String key) {
        int now = TvPicture.get(key);
        row(title, (now >= 0 ? String.valueOf(now) : "Unknown") + "  ·  left and right change it", null)
            .setOnKeyListener((v, code, e) -> {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
                int cur = TvPicture.get(key);
                if (cur < 0) return true;
                int next = Math.max(0, Math.min(100, cur + (code == KeyEvent.KEYCODE_DPAD_RIGHT ? 5 : -5)));
                if (next != cur) TvPicture.set(this, key, next);
                handler.postDelayed(this::refresh, 60);
                return true;
            });
    }

    private void open(ComponentName cn) {
        try {
            startActivity(new Intent().setComponent(cn).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException ex) {
            Log.w(TAG, "could not open " + cn, ex);
            Toaster.show(this, "Not available on this TV");
        }
    }

    // ---- Apps

    /** Installed apps with their labels, read off the main thread once per visit. */
    private List<String[]> apps;

    private void appsPage() {
        if (apps == null) {
            note("Loading apps");
            new Thread(() -> {
                PackageManager pm = getPackageManager();
                List<String[]> list = new ArrayList<>();
                for (PackageInfo p : pm.getInstalledPackages(0)) {
                    if (p.applicationInfo == null || (p.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
                    if (p.packageName.equals(getPackageName())) continue;
                    list.add(new String[]{p.packageName, label(pm, p), p.versionName != null ? p.versionName : ""});
                }
                list.sort((x, y) -> x[1].compareToIgnoreCase(y[1]));
                handler.post(() -> {
                    apps = list;
                    if (current == APPS && subPage == null) refresh();
                });
            }, "apps").start();
            return;
        }
        note(apps.size() + " apps you installed.");
        for (final String[] a : apps) {
            row(a[1], a[2], () -> openSub(a[1], () -> appPage(a[0], a[1])));
        }
    }

    private static String label(PackageManager pm, PackageInfo p) {
        return p.applicationInfo.loadLabel(pm).toString();
    }

    private void appPage(final String pkg, final String name) {
        PackageManager pm = getPackageManager();
        try {
            PackageInfo p = pm.getPackageInfo(pkg, 0);
            fact("Version", p.versionName != null ? p.versionName : "?");
            fact("Size", size(new File(p.applicationInfo.sourceDir).length()));
            fact("Installed", android.text.format.DateFormat.format("d MMMM yyyy", p.firstInstallTime).toString());
        } catch (PackageManager.NameNotFoundException ex) {
            note("Not installed any more.");
            return;
        }
        label("");
        Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (launch == null) launch = pm.getLaunchIntentForPackage(pkg);
        final Intent go = launch;
        if (go != null) row("Open", null, () -> startActivity(go.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        row("Force stop", null, () -> {
            Sys.forceStop(this, pkg);
            Toaster.show(this, name + " stopped");
        });
        row("Clear cache", null, () -> {
            Sys.clearCache(this, pkg);
            Toaster.show(this, name + " cache cleared");
        });
        row(pkg.equals(pendingUninstall) ? "Press OK again to uninstall" : "Uninstall", null, () -> {
            if (!pkg.equals(pendingUninstall)) {
                pendingUninstall = pkg;
                refresh();
                return;
            }
            pendingUninstall = null;
            Sys.uninstall(this, pkg);
            Toaster.show(this, name + " uninstalled");
            Apps.invalidate();
            apps = null;
            handler.postDelayed(() -> {
                showSection(APPS);
                focusPane();
            }, 1500);
        });
    }

    // ---- Storage

    private void storagePage() {
        StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
        long total = fs.getTotalBytes(), free = fs.getAvailableBytes();
        label("This TV");
        fact("Free", size(free) + " of " + size(total));
        meter((total - free) / (float) total);
        StorageManager sm = (StorageManager) getSystemService(STORAGE_SERVICE);
        boolean usb = false;
        for (StorageVolume v : sm.getStorageVolumes()) {
            if (!v.isRemovable() || v.getDirectory() == null) continue;
            if (!usb) label("USB drives");
            usb = true;
            StatFs u = new StatFs(v.getDirectory().getPath());
            fact(v.getDescription(this), size(u.getAvailableBytes()) + " free of " + size(u.getTotalBytes()));
        }
        if (!usb) note("No USB drive plugged in.");
        label("");
        row("Clear all app caches", null, () -> {
            Sys.clearAllCaches(this);
            Toaster.show(this, "Clearing caches");
            handler.postDelayed(this::refresh, 3000);
        });
    }

    private void meter(float used) {
        FrameLayout track = new FrameLayout(this);
        track.setBackground(Ui.round(Ui.LINE, Ui.dp(this, 4)));
        Ui.clipRound(track, Ui.dp(this, 4));
        View bar = new View(this);
        bar.setBackground(Ui.round(used > 0.9f ? Ui.WARN : Ui.ACCENT, Ui.dp(this, 4)));
        bar.setPivotX(0);
        bar.setScaleX(Math.max(0.01f, Math.min(1f, used)));
        track.addView(bar, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 6));
        lp.leftMargin = Ui.dp(this, 24);
        lp.rightMargin = Ui.dp(this, 24);
        lp.bottomMargin = Ui.dp(this, 16);
        // under the row it measures, in one block with it, so the card's dividers do not split them
        LinearLayout card = card();
        View row = card.getChildAt(card.getChildCount() - 1);
        card.removeView(row);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(row, row.getLayoutParams());
        box.addView(track, lp);
        card.addView(box, new LinearLayout.LayoutParams(-1, -2));
    }

    // ---- About

    private void aboutPage() {
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        ((ActivityManager) getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(mi);
        String version = "";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException ex) {
            Log.w(TAG, "own version unknown", ex);
        }
        StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
        long up = SystemClock.elapsedRealtime() / 60000;
        fact("Name", Device.name(this));
        fact("System", "Oriel " + version);
        fact("Android", Build.VERSION.RELEASE + " · security patch " + Build.VERSION.SECURITY_PATCH);
        // Many TV boards report only a codename, so the chip model shows only when the TV states it.
        String arch = System.getProperty("os.arch", "");
        String kind = arch.startsWith("aarch64") ? "ARM 64-bit" : arch.startsWith("arm") ? "ARMv7" : arch.startsWith("x86_64") ? "x86-64" : arch.isEmpty() ? "Processor" : arch;
        String model = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !Build.SOC_MODEL.equals(Build.UNKNOWN) ? Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL + " · " : "";
        fact("Processor", model + kind + " · " + Runtime.getRuntime().availableProcessors() + " cores");
        fact("Memory", size(mi.availMem) + " free of " + size(mi.totalMem));
        meter(1f - mi.availMem / (float) mi.totalMem);
        fact("Storage", size(fs.getAvailableBytes()) + " free of " + size(fs.getTotalBytes()));
        String ip = RemoteServer.address(this);
        fact("IP address", ip != null ? ip : "Not on Wi-Fi");
        fact("Up for", up >= 60 ? (up / 60) + " h " + (up % 60) + " min" : up + " min");
        label("");
        row("Free up memory", null, () -> {
            Shell.send(this, "sh am kill-all");
            Toaster.show(this, "Closing apps running in the background");
            handler.postDelayed(this::refresh, 2500);
        });
        row("Turn off or restart", null, () -> PowerActivity.open(this));
        label("Remove Oriel");
        note("Run Remove Oriel.bat on the PC Oriel was installed from. It puts back the TV's own home screen, settings and boot screens.");
    }

    /** A small right-pointing chevron for rows that open something. */
    static final class ImageChevron extends View {
        private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        ImageChevron(Context c) {
            super(c);
            p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeWidth(Ui.dp(c, 2));
            p.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            p.setColor(Ui.MUTED);
        }

        void setColor(int c) {
            p.setColor(c);
            invalidate();
        }

        @Override
        protected void onDraw(android.graphics.Canvas c) {
            float w = getWidth(), h = getHeight();
            c.drawLine(w * 0.35f, h * 0.2f, w * 0.7f, h * 0.5f, p);
            c.drawLine(w * 0.7f, h * 0.5f, w * 0.35f, h * 0.8f, p);
        }
    }


    @Override
    public boolean dispatchGenericMotionEvent(android.view.MotionEvent e) {
        Ui.hover(this, e);
        return super.dispatchGenericMotionEvent(e);
    }
}
