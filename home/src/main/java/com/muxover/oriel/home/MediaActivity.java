package com.muxover.oriel.home;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Oriel's media browser: USB drives and the TV's own storage as a grid of pictures. Videos show a frame and
 * their length, with a bar for how far you got; photos and songs show themselves. Videos you stopped part way
 * wait under Continue watching. OK opens, Back goes up a folder.
 */
public class MediaActivity extends Activity {
    private static final String TAG = "Oriel";
    private static final int MAX_ITEMS = 600;
    private static final int COLS = 5;

    private LinearLayout grid;
    private ScrollView scroll;
    private TextView heading, crumb;
    /** The folder showing, or null for the drives. */
    private File folder;
    private final List<File> roots = new ArrayList<>();
    private int tileW, tileH;
    /** Tiles added to the row being filled; a new row starts every COLS tiles. */
    private LinearLayout line;
    private int inLine;

    private final BroadcastReceiver drives = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            // A drive came or went: back to the drives, since the open folder may be gone.
            folder = null;
            show(null);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        FrameLayout root = new FrameLayout(this);
        root.setBackground(Ui.wash(0xFF3A2A12, Ui.dp(this, 900), 0.85f, 0.0f));
        root.addView(Ui.grainView(this), new FrameLayout.LayoutParams(-1, -1));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(this, 40), Ui.dp(this, 48), Ui.dp(this, 40), 0);
        heading = Ui.text(this, "Media", 40, Ui.bold, Ui.PAPER);
        heading.setLetterSpacing(-0.02f);
        heading.setSingleLine(true);
        heading.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        heading.setPadding(Ui.dp(this, 24), 0, Ui.dp(this, 24), 0);
        col.addView(heading);
        crumb = Ui.text(this, "", 16, Ui.regular, Ui.TEXT2);
        crumb.setSingleLine(true);
        crumb.setEllipsize(TextUtils.TruncateAt.START);
        crumb.setPadding(Ui.dp(this, 24), 0, Ui.dp(this, 24), 0);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
        cp.topMargin = Ui.dp(this, 8);
        cp.bottomMargin = Ui.dp(this, 8);
        col.addView(crumb, cp);
        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setClipChildren(false);
        // side padding gives a chosen tile room to grow; rows cut by the top or bottom fade out instead
        scroll.setPadding(Ui.dp(this, 24), Ui.dp(this, 20), Ui.dp(this, 24), Ui.dp(this, 48));
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(Ui.dp(this, 40));
        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        scroll.addView(grid, new FrameLayout.LayoutParams(-1, -2));
        col.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        int width = getResources().getDisplayMetrics().widthPixels - Ui.dp(this, 128);
        tileW = (width - (COLS - 1) * Ui.dp(this, 24)) / COLS;
        tileH = tileW * 9 / 16;
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 1);
        }
        show(null);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        show(null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter f = new IntentFilter(Intent.ACTION_MEDIA_MOUNTED);
        f.addAction(Intent.ACTION_MEDIA_REMOVED);
        f.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        f.addDataScheme("file");
        registerReceiver(drives, f);
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        // Back from a video: its bar and Continue watching changed. Stay on the same tile.
        View f = getCurrentFocus();
        show(f != null && f.getTag() instanceof String ? (String) f.getTag() : null);
    }

    @Override
    protected void onPause() {
        unregisterReceiver(drives);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        Thumbs.release();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (folder == null) {
            super.onBackPressed();
            return;
        }
        File up = folder.getParentFile();
        String was = folder.getAbsolutePath();
        folder = isRoot(folder) || up == null ? null : up;
        show(was);
    }

    private boolean isRoot(File f) {
        for (File r : roots) if (r.equals(f)) return true;
        return false;
    }

    /** Draws the drives or the open folder, with focus on the tile for focusPath when given. */
    private void show(String focusPath) {
        Thumbs.forget();
        grid.removeAllViews();
        line = null;
        if (folder == null) showDrives(); else showFolder(folder);
        View target = null;
        if (focusPath != null) target = grid.findViewWithTag(focusPath);
        if (target == null) target = firstTile(grid);
        final View t = target;
        if (t != null) t.post(() -> {
            t.requestFocus();
            scrollTo(t, false);
        });
        else scroll.scrollTo(0, 0);
    }

    private static View firstTile(ViewGroup g) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View v = g.getChildAt(i);
            if (v.isFocusable()) return v;
            if (v instanceof ViewGroup) {
                View in = firstTile((ViewGroup) v);
                if (in != null) return in;
            }
        }
        return null;
    }

    private void showDrives() {
        heading.setText("Media");
        crumb.setText("Videos, photos and music on USB drives and this TV");
        List<String> recent = Resume.recent(this);
        if (!recent.isEmpty()) {
            section("Continue watching");
            for (int i = 0; i < recent.size() && i < COLS * 2; i++) {
                final File f = new File(recent.get(i));
                fileTile(f, MediaFiles.VIDEO);
            }
        }
        section("Drives");
        roots.clear();
        StorageManager sm = (StorageManager) getSystemService(STORAGE_SERVICE);
        if (sm != null) {
            for (StorageVolume v : sm.getStorageVolumes()) {
                if (!Environment.MEDIA_MOUNTED.equals(v.getState()) && !Environment.MEDIA_MOUNTED_READ_ONLY.equals(v.getState())) continue;
                final File dir = directory(v);
                if (dir == null) continue;
                roots.add(dir);
                boolean tv = v.isPrimary();
                glyphTile(dir.getAbsolutePath(), tv ? IconView.TV : IconView.USB, tv ? new int[]{0xFF4B4B55, 0xFF232328} : new int[]{0xFFF59E0B, 0xFF9A4A07},
                    tv ? "This TV" : v.getDescription(this), tv ? "Downloads and copied files" : "USB drive", () -> open(dir));
            }
        }
        if (roots.size() <= 1) note("No USB drive in the TV. Plug one in and it shows here.");
    }

    private static File directory(StorageVolume v) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return v.getDirectory();
        if (v.isPrimary()) return Environment.getExternalStorageDirectory();
        String uuid = v.getUuid();
        return uuid != null ? new File("/storage/" + uuid) : null;
    }

    private void open(File dir) {
        folder = dir;
        show(null);
    }

    private String place(File dir) {
        String p = dir.getAbsolutePath();
        for (File r : roots) {
            if (p.startsWith(r.getAbsolutePath())) {
                String rest = p.substring(r.getAbsolutePath().length()).replace("/", "  /  ");
                String name = r.getAbsolutePath().startsWith("/storage/emulated") ? "This TV" : "USB drive";
                return name + rest;
            }
        }
        return p;
    }

    private void showFolder(File dir) {
        heading.setText(isRoot(dir) ? (dir.getAbsolutePath().startsWith("/storage/emulated") ? "This TV" : "USB drive") : dir.getName());
        File[] all = dir.listFiles();
        if (all == null) {
            Log.w(TAG, "media: cannot read " + dir);
            crumb.setText(place(dir));
            note("This folder cannot be read.");
            return;
        }
        List<File> dirs = new ArrayList<>(), files = new ArrayList<>();
        int videos = 0, photos = 0, songs = 0;
        for (File f : all) {
            if (f.getName().startsWith(".")) continue;
            if (f.isDirectory()) {
                if (!"Android".equals(f.getName()) || !isRoot(dir)) dirs.add(f);
                continue;
            }
            int k = MediaFiles.kind(f);
            if (k == 0) continue;
            files.add(f);
            if (k == MediaFiles.VIDEO) videos++;
            else if (k == MediaFiles.PHOTO) photos++;
            else songs++;
        }
        dirs.sort((a, b) -> MediaFiles.naturalCompare(a.getName(), b.getName()));
        files.sort((a, b) -> MediaFiles.naturalCompare(a.getName(), b.getName()));
        List<String> counts = new ArrayList<>();
        if (!dirs.isEmpty()) counts.add(count(dirs.size(), "folder"));
        if (videos > 0) counts.add(count(videos, "video"));
        if (photos > 0) counts.add(count(photos, "photo"));
        if (songs > 0) counts.add(count(songs, "song"));
        crumb.setText(place(dir) + (counts.isEmpty() ? "" : "   ·   " + TextUtils.join(", ", counts)));
        // Folders, then videos, music and photos, each under its own title when the folder mixes them.
        int kinds = (dirs.isEmpty() ? 0 : 1) + (videos > 0 ? 1 : 0) + (songs > 0 ? 1 : 0) + (photos > 0 ? 1 : 0);
        int n = 0;
        if (kinds > 1 && !dirs.isEmpty()) section("Folders");
        for (final File d : dirs) {
            if (n++ >= MAX_ITEMS) break;
            glyphTile(d.getAbsolutePath(), IconView.FOLDER, new int[]{0xFF2C2C33, 0xFF18181C}, d.getName(), null, () -> open(d));
        }
        int[] order = {MediaFiles.VIDEO, MediaFiles.MUSIC, MediaFiles.PHOTO};
        String[] titles = {"Videos", "Music", "Photos"};
        for (int k = 0; k < order.length; k++) {
            boolean titled = false;
            for (File f : files) {
                if (MediaFiles.kind(f) != order[k]) continue;
                if (n++ >= MAX_ITEMS) break;
                if (kinds > 1 && !titled) {
                    section(titles[k]);
                    titled = true;
                }
                fileTile(f, order[k]);
            }
        }
        if (n == 0) note("No videos, photos or music in this folder.");
    }

    private static String count(int n, String what) {
        return n + " " + what + (n == 1 ? "" : "s");
    }

    // ---- building blocks

    private void section(String s) {
        line = null;
        TextView t = Ui.text(this, s, 18, Ui.semibold, Ui.TEXT2);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = grid.getChildCount() == 0 ? 0 : Ui.dp(this, 16);
        lp.bottomMargin = Ui.dp(this, 16);
        grid.addView(t, lp);
    }

    private void note(String s) {
        line = null;
        TextView t = Ui.text(this, s, 18, Ui.regular, Ui.MUTED);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(this, 8);
        grid.addView(t, lp);
    }

    /** The next free cell: rows of COLS tiles. */
    private LinearLayout cell() {
        if (line == null || inLine == COLS) {
            line = new LinearLayout(this);
            line.setClipChildren(false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = Ui.dp(this, 20);
            grid.addView(line, lp);
            inLine = 0;
        }
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setClipChildren(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(tileW, -2);
        if (inLine > 0) lp.leftMargin = Ui.dp(this, 24);
        line.addView(c, lp);
        inLine++;
        return c;
    }

    /** A tile: the picture area (focusable, lifts and rings when chosen), then a title and a detail line. */
    private FrameLayout tile(String key, String title, String detail, Runnable action, TextView[] texts) {
        LinearLayout c = cell();
        final FrameLayout pic = new FrameLayout(this);
        pic.setFocusable(true);
        pic.setTag(key);
        pic.setBackground(Ui.round(Ui.GRAPHITE, Ui.dp(this, Ui.R_M)));
        Ui.clipRound(pic, Ui.dp(this, Ui.R_M));
        final View ring = new View(this);
        GradientDrawable g = Ui.round(0, Ui.dp(this, Ui.R_M));
        g.setStroke(Ui.dp(this, 3), Ui.PAPER);
        ring.setBackground(g);
        ring.setAlpha(0f);
        c.addView(pic, new LinearLayout.LayoutParams(tileW, tileH));
        final TextView t = Ui.text(this, title, 16, Ui.medium, Ui.TEXT2);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2);
        tp.topMargin = Ui.dp(this, 12);
        c.addView(t, tp);
        final TextView d = Ui.text(this, detail == null ? "" : detail, 14, Ui.regular, Ui.MUTED);
        d.setSingleLine(true);
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(-1, -2);
        dp.topMargin = Ui.dp(this, 4);
        c.addView(d, dp);
        texts[0] = t;
        texts[1] = d;
        // the ring goes on top of whatever the caller puts in the tile, so it is added once they are done
        pic.post(() -> pic.addView(ring, new FrameLayout.LayoutParams(-1, -1)));
        pic.setOnFocusChangeListener((v, has) -> {
            pic.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f).setDuration(Ui.FOCUS_MS).setInterpolator(Ui.EASE).start();
            ring.animate().alpha(has ? 1f : 0f).setDuration(Ui.FOCUS_MS).start();
            pic.setElevation(has ? 1 : 0);
            t.setTextColor(has ? Ui.PAPER : Ui.TEXT2);
            t.setEllipsize(has ? TextUtils.TruncateAt.MARQUEE : TextUtils.TruncateAt.END);
            t.setSelected(has);
            if (has) scrollTo(pic, true);
        });
        pic.setOnClickListener(v -> action.run());
        return pic;
    }

    private void glyphTile(String key, int glyph, int[] colors, String title, String detail, Runnable action) {
        FrameLayout pic = tile(key, title, detail, action, new TextView[2]);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, colors);
        g.setCornerRadius(Ui.dp(this, Ui.R_M));
        pic.setBackground(g);
        IconView ic = new IconView(this, glyph, Ui.PAPER);
        int s = Math.round(tileH * 0.36f);
        pic.addView(ic, new FrameLayout.LayoutParams(s, s, Gravity.CENTER));
    }

    private void fileTile(final File f, final int kind) {
        final String path = f.getAbsolutePath();
        final TextView[] texts = new TextView[2];
        String detail = kind == MediaFiles.PHOTO ? "Photo" : kind == MediaFiles.MUSIC ? "Song" : "Video";
        FrameLayout pic = tile(path, MediaFiles.title(f.getName()), detail, () -> play(f, kind), texts);
        IconView ic = new IconView(this, kind == MediaFiles.PHOTO ? IconView.PHOTO : kind == MediaFiles.MUSIC ? IconView.MUSIC : IconView.MEDIA, 0x66F4F3EF);
        int s = Math.round(tileH * 0.3f);
        pic.addView(ic, new FrameLayout.LayoutParams(s, s, Gravity.CENTER));
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        pic.addView(img, new FrameLayout.LayoutParams(-1, -1));
        final View shade;
        final float done = kind == MediaFiles.VIDEO ? Resume.progress(this, path) : -1f;
        if (done >= 0) {
            // how far you got: a bar along the bottom of the picture
            shade = new View(this);
            shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xB3000000, 0x00000000}));
            pic.addView(shade, new FrameLayout.LayoutParams(-1, Ui.dp(this, 36), Gravity.BOTTOM));
            FrameLayout track = new FrameLayout(this);
            track.setBackground(Ui.round(0x4DF4F3EF, Ui.dp(this, 2)));
            View bar = new View(this);
            bar.setBackground(Ui.round(Ui.ACCENT, Ui.dp(this, 2)));
            int full = tileW - Ui.dp(this, 24);
            track.addView(bar, new FrameLayout.LayoutParams(Math.max(Ui.dp(this, 4), Math.round(full * done)), -1));
            FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(full, Ui.dp(this, 4), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            tp.bottomMargin = Ui.dp(this, 10);
            pic.addView(track, tp);
        }
        Thumbs.load(this, f, kind, tileW, tileH, img, () -> {
            long dur = Thumbs.duration(f);
            if (dur <= 0) return;
            String what = kind == MediaFiles.MUSIC ? "Song" : "Video";
            if (done >= 0) texts[1].setText(MediaFiles.time(dur - Math.round(dur * done)) + " left");
            else texts[1].setText(what + "  ·  " + MediaFiles.time(dur));
        });
    }

    /** Scrolls only as far as needed to show the whole row of the tile, with a little room around it. */
    private void scrollTo(View v, boolean smooth) {
        View row = v;
        while (row.getParent() != grid && row.getParent() instanceof View) row = (View) row.getParent();
        if (row.getParent() != grid) return;
        int room = Ui.dp(this, 28);
        int top = row.getTop(), bottom = row.getBottom();
        // a section title right above the first row of a section scrolls into view with it
        int i = grid.indexOfChild(row);
        if (i > 0 && grid.getChildAt(i - 1) instanceof TextView) top = grid.getChildAt(i - 1).getTop();
        int visible = scroll.getHeight() - scroll.getPaddingTop() - scroll.getPaddingBottom();
        int y = scroll.getScrollY(), target = y;
        if (top - room < y) target = Math.max(0, top - room);
        else if (bottom + room > y + visible) target = bottom + room - visible;
        if (target == y) return;
        if (smooth) scroll.smoothScrollTo(0, target); else scroll.scrollTo(0, target);
    }

    private void play(File f, int kind) {
        if (!f.exists()) {
            Toaster.show(this, "That file is not there any more");
            show(null);
            return;
        }
        if (kind == MediaFiles.PHOTO) {
            ArrayList<String> list = MediaFiles.siblings(f, MediaFiles.PHOTO);
            ViewerActivity.showAll(this, list, Math.max(0, list.indexOf(f.getAbsolutePath())));
        } else if (kind == MediaFiles.MUSIC) {
            ArrayList<String> list = MediaFiles.siblings(f, MediaFiles.MUSIC);
            MusicActivity.play(this, list, Math.max(0, list.indexOf(f.getAbsolutePath())), null);
        } else {
            ArrayList<String> list = MediaFiles.siblings(f, MediaFiles.VIDEO);
            PlayerActivity.play(this, list, Math.max(0, list.indexOf(f.getAbsolutePath())));
        }
    }

    @Override
    public boolean dispatchGenericMotionEvent(android.view.MotionEvent e) {
        Ui.hover(this, e);
        return super.dispatchGenericMotionEvent(e);
    }
}
