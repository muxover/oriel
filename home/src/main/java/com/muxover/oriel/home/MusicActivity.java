package com.muxover.oriel.home;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Oriel's music player: the songs of a folder in order, with cover art, title and artist read from each file,
 * over colours taken from the art. Shuffle and repeat; left and right on the progress bar skip through a song.
 */
public class MusicActivity extends Activity {
    private static final String TAG = "Oriel";

    /** Plays the songs from index; title, when set, names a single song sent from the phone. */
    static void play(Context c, ArrayList<String> paths, int index, String title) {
        c.startActivity(new Intent(c, MusicActivity.class)
            .putStringArrayListExtra("paths", paths)
            .putExtra("index", index)
            .putExtra("title", title)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ColorFlow flow;
    private ImageView art;
    private IconView artGlyph;
    private TextView source, title, artist, album, elapsed, remain, upNext;
    private LaunchBar progress;
    private FrameLayout seekRow;
    private IconView playIcon, shuffleIcon, repeatIcon;
    private MediaPlayer player;
    private boolean prepared;
    /** The songs in the order they play: the folder's order, or shuffled. */
    private final List<String> order = new ArrayList<>();
    private ArrayList<String> folderOrder;
    private int at;
    private boolean shuffle;
    /** 0 off, 1 the whole list, 2 this song. */
    private int repeat;
    private String sentTitle;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        FrameLayout root = new FrameLayout(this);
        flow = new ColorFlow(this);
        root.addView(flow, new FrameLayout.LayoutParams(-1, -1));
        root.addView(build(), new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        shuffle = Prefs.of(this).getBoolean("music_shuffle", false);
        repeat = Prefs.of(this).getInt("music_repeat", 0);
        styleModes();
        load(getIntent());
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        load(i);
    }

    private void load(Intent i) {
        folderOrder = i.getStringArrayListExtra("paths");
        sentTitle = i.getStringExtra("title");
        if (folderOrder == null || folderOrder.isEmpty()) {
            finish();
            return;
        }
        int start = Math.max(0, Math.min(i.getIntExtra("index", 0), folderOrder.size() - 1));
        String first = folderOrder.get(start);
        makeOrder(first);
        startSong(order.indexOf(first));
    }

    /** The play order with first at its place: the folder's order, or shuffled with first at the front. */
    private void makeOrder(String first) {
        order.clear();
        order.addAll(folderOrder);
        if (shuffle) {
            order.remove(first);
            Collections.shuffle(order, new Random());
            order.add(0, first);
        }
    }

    private View build() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(this, 96), 0, Ui.dp(this, 96), 0);
        row.setClipChildren(false);

        FrameLayout artBox = new FrameLayout(this);
        artBox.setBackground(Ui.round(0x26F4F3EF, Ui.dp(this, 20)));
        Ui.clipRound(artBox, Ui.dp(this, 20));
        artGlyph = new IconView(this, IconView.MUSIC, 0x80F4F3EF);
        artBox.addView(artGlyph, new FrameLayout.LayoutParams(Ui.dp(this, 120), Ui.dp(this, 120), Gravity.CENTER));
        art = new ImageView(this);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        artBox.addView(art, new FrameLayout.LayoutParams(-1, -1));
        int a = Ui.dp(this, 400);
        row.addView(artBox, new LinearLayout.LayoutParams(a, a));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, -2, 1f);
        cp.leftMargin = Ui.dp(this, 72);
        row.addView(col, cp);

        source = Ui.text(this, "", 14, Ui.mono, 0xBFF4F3EF);
        col.addView(source);
        title = Ui.text(this, "", 52, Ui.bold, Ui.PAPER);
        title.setLetterSpacing(-0.025f);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2);
        tp.topMargin = Ui.dp(this, 12);
        col.addView(title, tp);
        artist = Ui.text(this, "", 28, Ui.regular, 0xD9F4F3EF);
        artist.setSingleLine(true);
        artist.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, -2);
        ap.topMargin = Ui.dp(this, 8);
        col.addView(artist, ap);
        album = Ui.text(this, "", 18, Ui.regular, 0x99F4F3EF);
        album.setSingleLine(true);
        album.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-1, -2);
        alp.topMargin = Ui.dp(this, 6);
        col.addView(album, alp);

        // the progress bar takes focus: left and right then skip through the song
        seekRow = new FrameLayout(this);
        seekRow.setFocusable(true);
        progress = new LaunchBar(this);
        seekRow.addView(progress, new FrameLayout.LayoutParams(-1, -1));
        seekRow.setAlpha(0.6f);
        seekRow.setOnFocusChangeListener((v, has) -> {
            seekRow.animate().alpha(has ? 1f : 0.6f).setDuration(Ui.FOCUS_MS).start();
        });
        seekRow.setOnKeyListener((v, code, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                seek(code == KeyEvent.KEYCODE_DPAD_RIGHT, e.getRepeatCount());
                return true;
            }
            return false;
        });
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 32));
        pp.topMargin = Ui.dp(this, 28);
        pp.leftMargin = pp.rightMargin = -Ui.dp(this, 14);
        col.addView(seekRow, pp);
        LinearLayout times = new LinearLayout(this);
        elapsed = Ui.text(this, "", 14, Ui.mono, 0xB3F4F3EF);
        remain = Ui.text(this, "", 14, Ui.mono, 0xB3F4F3EF);
        times.addView(elapsed, new LinearLayout.LayoutParams(0, -2, 1f));
        times.addView(remain);
        col.addView(times);

        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setClipChildren(false);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
        clp.topMargin = Ui.dp(this, 24);
        col.addView(controls, clp);
        FrameLayout sh = button(controls, IconView.SHUFFLE, 56, this::toggleShuffle);
        button(controls, IconView.PREV, 64, this::previous);
        FrameLayout pl = button(controls, IconView.PAUSE, 88, this::togglePlay);
        button(controls, IconView.NEXT, 64, () -> next(true));
        FrameLayout rp = button(controls, IconView.REPEAT, 56, this::cycleRepeat);
        shuffleIcon = (IconView) sh.getChildAt(0);
        playIcon = (IconView) pl.getChildAt(0);
        repeatIcon = (IconView) rp.getChildAt(0);
        pl.post(pl::requestFocus);

        upNext = Ui.text(this, "", 16, Ui.regular, 0x99F4F3EF);
        upNext.setSingleLine(true);
        upNext.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams up = new LinearLayout.LayoutParams(-1, -2);
        up.topMargin = Ui.dp(this, 28);
        col.addView(upNext, up);
        return row;
    }

    private FrameLayout button(LinearLayout parent, int kind, int sizeDp, Runnable action) {
        final FrameLayout b = new FrameLayout(this);
        b.setFocusable(true);
        final IconView ic = new IconView(this, kind, Ui.PAPER);
        int s = Ui.dp(this, sizeDp * 0.42f);
        b.addView(ic, new FrameLayout.LayoutParams(s, s, Gravity.CENTER));
        b.setOnFocusChangeListener((v, has) -> {
            b.setBackground(has ? Ui.round(Ui.PAPER, Ui.dp(this, sizeDp / 2f)) : null);
            ic.setColor(has ? Ui.INK : (Boolean.TRUE.equals(ic.getTag()) ? Ui.ACCENT_HI : Ui.PAPER));
        });
        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, sizeDp), Ui.dp(this, sizeDp));
        if (parent.getChildCount() > 0) lp.leftMargin = Ui.dp(this, 20);
        parent.addView(b, lp);
        return b;
    }

    private void startSong(int i) {
        at = i;
        prepared = false;
        handler.removeCallbacks(tick);
        if (player != null) {
            player.release();
            player = null;
        }
        final File f = new File(order.get(i));
        readTags(f);
        progress.setProgress(0);
        elapsed.setText("");
        remain.setText("");
        playIcon.setKind(IconView.PAUSE);
        player = new MediaPlayer();
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
        player.setOnPreparedListener(p -> {
            prepared = true;
            p.start();
            handler.post(tick);
        });
        player.setOnCompletionListener(p -> next(false));
        player.setOnErrorListener((p, what, extra) -> {
            Log.e(TAG, "song failed " + what + "/" + extra + " " + f.getName());
            Toaster.show(this, f.getName() + " cannot be played");
            handler.postDelayed(() -> next(false), 600);
            return true;
        });
        try {
            player.setDataSource(f.getAbsolutePath());
            player.prepareAsync();
        } catch (IOException | RuntimeException ex) {
            Log.e(TAG, "song not readable " + f, ex);
            Toaster.show(this, f.getName() + " cannot be played");
        }
    }

    private void readTags(File f) {
        String t = null, ar = null, al = null;
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(f.getAbsolutePath());
            t = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
            ar = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
            if (ar == null) ar = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST);
            al = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM);
        } catch (RuntimeException ex) {
            Log.w(TAG, "no tags in " + f.getName(), ex);
        } finally {
            try {
                r.release();
            } catch (IOException | RuntimeException ex) {
                Log.w(TAG, "retriever release failed", ex);
            }
        }
        if (t == null || t.trim().isEmpty()) t = sentTitle != null && order.size() == 1 ? sentTitle : MediaFiles.title(f.getName());
        title.setText(t.trim());
        artist.setText(ar != null ? ar.trim() : "");
        artist.setVisibility(ar != null && !ar.trim().isEmpty() ? View.VISIBLE : View.GONE);
        album.setText(al != null ? al.trim() : "");
        album.setVisibility(al != null && !al.trim().isEmpty() ? View.VISIBLE : View.GONE);
        source.setText(order.size() > 1 ? "Song " + (at + 1) + " of " + order.size() + (shuffle ? "  ·  Shuffled" : "") : "Music");
        int n = nextIndex(false);
        upNext.setText(n >= 0 && n != at ? "Next: " + MediaFiles.title(new File(order.get(n)).getName()) : "");
        art.setImageDrawable(null);
        artGlyph.setVisibility(View.VISIBLE);
        Thumbs.art(f, b -> {
            if (!f.getAbsolutePath().equals(order.get(at))) return;
            if (b != null) {
                art.setAlpha(0f);
                art.setImageBitmap(b);
                art.animate().alpha(1f).setDuration(400).start();
                artGlyph.setVisibility(View.GONE);
            }
            flow.setColors(ColorFlow.paletteOf(b));
        });
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (player == null || !prepared) return;
            long pos = seekTarget >= 0 ? seekTarget : player.getCurrentPosition(), dur = player.getDuration();
            if (dur > 0) {
                progress.setProgress(pos / (float) dur);
                elapsed.setText(MediaFiles.time(pos));
                remain.setText("-" + MediaFiles.time(dur - pos));
            }
            handler.postDelayed(this, 500);
        }
    };

    private long seekTarget = -1;
    private final Runnable doSeek = () -> {
        if (player != null && prepared && seekTarget >= 0) player.seekTo((int) seekTarget);
        seekTarget = -1;
    };

    private void seek(boolean forward, int repeatCount) {
        if (player == null || !prepared) return;
        long dur = player.getDuration();
        long from = seekTarget >= 0 ? seekTarget : player.getCurrentPosition();
        long step = repeatCount > 2 ? 30_000 : 10_000;
        seekTarget = Math.max(0, Math.min(dur - 500, from + (forward ? step : -step)));
        tick.run();
        handler.removeCallbacks(doSeek);
        handler.postDelayed(doSeek, 400);
    }

    private void togglePlay() {
        if (player == null || !prepared) return;
        if (player.isPlaying()) {
            player.pause();
            playIcon.setKind(IconView.PLAY);
        } else {
            player.start();
            playIcon.setKind(IconView.PAUSE);
        }
    }

    /** The song after this one, or -1 at the end of the list without repeat. */
    private int nextIndex(boolean pressed) {
        if (repeat == 2 && !pressed) return at;
        if (at + 1 < order.size()) return at + 1;
        return repeat == 1 || pressed ? 0 : -1;
    }

    private void next(boolean pressed) {
        int n = nextIndex(pressed);
        if (n < 0) {
            playIcon.setKind(IconView.PLAY);
            if (player != null && prepared) player.seekTo(0);
            if (player != null) player.pause();
            return;
        }
        startSong(n);
    }

    /** Back to the start of the song, or to the one before when it has just begun. */
    private void previous() {
        if (player != null && prepared && player.getCurrentPosition() > 4000) {
            player.seekTo(0);
            return;
        }
        startSong(at > 0 ? at - 1 : order.size() - 1);
    }

    private void toggleShuffle() {
        shuffle = !shuffle;
        Prefs.of(this).edit().putBoolean("music_shuffle", shuffle).apply();
        String now = order.get(at);
        makeOrder(now);
        at = order.indexOf(now);
        styleModes();
        int n = nextIndex(false);
        upNext.setText(n >= 0 && n != at ? "Next: " + MediaFiles.title(new File(order.get(n)).getName()) : "");
        source.setText(order.size() > 1 ? "Song " + (at + 1) + " of " + order.size() + (shuffle ? "  ·  Shuffled" : "") : "Music");
    }

    private void cycleRepeat() {
        repeat = (repeat + 1) % 3;
        Prefs.of(this).edit().putInt("music_repeat", repeat).apply();
        styleModes();
        Toaster.show(this, repeat == 0 ? "Repeat off" : repeat == 1 ? "Repeating all songs" : "Repeating this song");
    }

    /** Shuffle and repeat show in the accent colour when on. */
    private void styleModes() {
        if (shuffleIcon == null) return;
        shuffleIcon.setTag(shuffle);
        repeatIcon.setTag(repeat != 0);
        repeatIcon.setKind(repeat == 2 ? IconView.REPEAT_ONE : IconView.REPEAT);
        if (!((View) shuffleIcon.getParent()).hasFocus()) shuffleIcon.setColor(shuffle ? Ui.ACCENT_HI : Ui.PAPER);
        if (!((View) repeatIcon.getParent()).hasFocus()) repeatIcon.setColor(repeat != 0 ? Ui.ACCENT_HI : Ui.PAPER);
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        switch (code) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                togglePlay();
                return true;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                next(true);
                return true;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                previous();
                return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                seek(code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, e.getRepeatCount());
                return true;
            default:
                return super.onKeyDown(code, e);
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }

    @Override
    public boolean dispatchGenericMotionEvent(android.view.MotionEvent e) {
        Ui.hover(this, e);
        return super.dispatchGenericMotionEvent(e);
    }
}
