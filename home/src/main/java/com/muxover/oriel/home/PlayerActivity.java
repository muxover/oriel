package com.muxover.oriel.home;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.VideoView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Oriel's video player. Continues where you stopped, seeks with left and right (further the longer you hold),
 * plays the next video in the folder when one ends, and shows subtitles from .srt files next to the video or
 * inside it. Down opens subtitles, sound tracks and Start over.
 */
public class PlayerActivity extends Activity {
    private static final String TAG = "Oriel";
    private static final long BAR_MS = 4000, STEP_MS = 10_000, BIG_STEP_MS = 30_000;

    static void play(Context c, ArrayList<String> paths, int index) {
        c.startActivity(new Intent(c, PlayerActivity.class)
            .putStringArrayListExtra("paths", paths)
            .putExtra("index", index)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private VideoView video;
    private MediaPlayer mp;
    private View bar;
    private TextView title, detail, elapsed, remain, subtitle, flash;
    private LaunchBar progress;
    private FrameLayout menu;
    private ArrayList<String> paths;
    private int index;
    private File file;
    private boolean prepared;
    /** Where a run of left and right presses is heading; -1 when not seeking. */
    private long seekTarget = -1;
    private List<Subtitles.Cue> cues;
    private String subtitleName = "Off";
    private int audioTrack = -1;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        video = new VideoView(this);
        root.addView(video, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

        subtitle = Ui.text(this, "", 34, Ui.semibold, Ui.PAPER);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setShadowLayer(Ui.dp(this, 4), 0, Ui.dp(this, 1), 0xE6000000);
        subtitle.setLineSpacing(0, 1.1f);
        subtitle.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        sp.leftMargin = sp.rightMargin = Ui.dp(this, 120);
        sp.bottomMargin = Ui.dp(this, 56);
        root.addView(subtitle, sp);

        bar = buildBar();
        root.addView(bar, new FrameLayout.LayoutParams(-1, -1));
        bar.setAlpha(0f);

        flash = Ui.text(this, "", 22, Ui.semibold, Ui.PAPER);
        flash.setGravity(Gravity.CENTER);
        flash.setPadding(Ui.dp(this, 28), 0, Ui.dp(this, 28), 0);
        flash.setBackground(Ui.round(Ui.GLASS, Ui.dp(this, Ui.R_PILL)));
        flash.setAlpha(0f);
        FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(-2, Ui.dp(this, 56), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        fp.topMargin = Ui.dp(this, 48);
        root.addView(flash, fp);

        menu = new FrameLayout(this);
        menu.setVisibility(View.GONE);
        root.addView(menu, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        load(getIntent());
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        savePlace();
        load(i);
    }

    private void load(Intent i) {
        paths = i.getStringArrayListExtra("paths");
        index = i.getIntExtra("index", 0);
        if (paths == null || paths.isEmpty()) {
            String one = i.getStringExtra("path");
            if (one == null) {
                finish();
                return;
            }
            paths = new ArrayList<>();
            paths.add(one);
            index = 0;
        }
        start(Math.max(0, Math.min(index, paths.size() - 1)));
    }

    private View buildBar() {
        FrameLayout f = new FrameLayout(this);
        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xE6000000, 0x99000000, 0x00000000}));
        f.addView(shade, new FrameLayout.LayoutParams(-1, Ui.dp(this, 300), Gravity.BOTTOM));
        f.addView(Ui.grainView(this), new FrameLayout.LayoutParams(-1, Ui.dp(this, 300), Gravity.BOTTOM));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        detail = Ui.text(this, "", 14, Ui.mono, 0xBFF4F3EF);
        col.addView(detail);
        title = Ui.text(this, "", 36, Ui.bold, Ui.PAPER);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2);
        tp.topMargin = Ui.dp(this, 8);
        col.addView(title, tp);
        progress = new LaunchBar(this);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 32));
        pp.topMargin = Ui.dp(this, 20);
        pp.leftMargin = pp.rightMargin = -Ui.dp(this, 14);
        col.addView(progress, pp);
        LinearLayout times = new LinearLayout(this);
        elapsed = Ui.text(this, "", 15, Ui.mono, 0xCCF4F3EF);
        remain = Ui.text(this, "", 15, Ui.mono, 0xCCF4F3EF);
        times.addView(elapsed, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView hint = Ui.text(this, "OK pause   ·   Left and right skip   ·   Down for subtitles and sound", 15, Ui.regular, 0x99F4F3EF);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-2, -2);
        hp.rightMargin = Ui.dp(this, 32);
        times.addView(hint, hp);
        times.addView(remain);
        col.addView(times);
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        cp.leftMargin = cp.rightMargin = Ui.dp(this, 64);
        cp.bottomMargin = Ui.dp(this, 48);
        f.addView(col, cp);
        return f;
    }

    private void start(int i) {
        index = i;
        file = new File(paths.get(i));
        prepared = false;
        mp = null;
        seekTarget = -1;
        audioTrack = -1;
        subtitle.setText("");
        title.setText(MediaFiles.title(file.getName()));
        detail.setText(paths.size() > 1 ? "Video " + (i + 1) + " of " + paths.size() : "Video");
        // An .srt next to the video is on from the start, the first one in name order.
        cues = null;
        subtitleName = "Off";
        List<File> subs = Subtitles.findFor(file);
        if (!subs.isEmpty()) useSubtitleFile(subs.get(0));
        video.setVideoURI(Uri.fromFile(file));
        video.setOnPreparedListener(p -> {
            mp = p;
            prepared = true;
            long at = Resume.position(this, file.getAbsolutePath());
            if (at > 0) {
                video.seekTo((int) at);
                say("Continuing at " + MediaFiles.time(at) + ". Down, then Start over, to begin again");
            }
            video.start();
            showBar();
        });
        video.setOnCompletionListener(p -> {
            Resume.clear(this, file.getAbsolutePath());
            if (index + 1 < paths.size()) start(index + 1);
            else finish();
        });
        video.setOnErrorListener((p, what, extra) -> {
            Log.e(TAG, "playback failed " + what + "/" + extra + " for " + file.getName());
            Toaster.show(this, "This video cannot be played here");
            if (index + 1 < paths.size()) start(index + 1);
            else finish();
            return true;
        });
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    /** Twice a second: the bar's figures, the subtitle showing, and every ten seconds the saved place. */
    private int ticks;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (prepared) {
                long pos = seekTarget >= 0 ? seekTarget : video.getCurrentPosition();
                long dur = video.getDuration();
                if (dur > 0) {
                    progress.setProgress(pos / (float) dur);
                    elapsed.setText(MediaFiles.time(pos));
                    remain.setText("-" + MediaFiles.time(dur - pos));
                }
                if (cues != null) {
                    Subtitles.Cue c = Subtitles.at(cues, video.getCurrentPosition());
                    String s = c != null ? c.text : "";
                    if (!s.contentEquals(subtitle.getText())) subtitle.setText(s);
                }
                if (++ticks % 20 == 0 && video.isPlaying()) savePlace();
            }
            handler.postDelayed(this, cues != null ? 100 : 500);
        }
    };

    private void savePlace() {
        if (prepared && file != null) Resume.save(this, file.getAbsolutePath(), video.getCurrentPosition(), video.getDuration());
    }

    @Override
    protected void onPause() {
        savePlace();
        if (video.isPlaying()) video.pause();
        handler.removeCallbacks(tick);
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(tick);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        video.stopPlayback();
        super.onDestroy();
    }

    private final Runnable hideBar = () -> {
        if (video.isPlaying()) bar.animate().alpha(0f).setDuration(Ui.ENTER_MS).start();
    };

    private void showBar() {
        bar.animate().alpha(1f).setDuration(Ui.FOCUS_MS).start();
        // subtitles move up out of the bar's way while it shows
        subtitle.animate().translationY(-Ui.dp(this, 150)).setDuration(Ui.FOCUS_MS).start();
        handler.removeCallbacks(hideBar);
        handler.postDelayed(hideBar, BAR_MS);
        handler.removeCallbacks(lowerSubtitle);
        handler.postDelayed(lowerSubtitle, BAR_MS + 50);
    }

    private final Runnable lowerSubtitle = () -> {
        if (video.isPlaying()) subtitle.animate().translationY(0).setDuration(Ui.ENTER_MS).start();
    };

    private void say(String s) {
        flash.setText(s);
        flash.animate().alpha(1f).setDuration(Ui.FOCUS_MS).start();
        handler.removeCallbacks(hideFlash);
        handler.postDelayed(hideFlash, 2500);
    }

    private final Runnable hideFlash = () -> flash.animate().alpha(0f).setDuration(Ui.ENTER_MS).start();

    private final Runnable doSeek = () -> {
        if (seekTarget >= 0) video.seekTo((int) seekTarget);
        seekTarget = -1;
    };

    private void skip(boolean forward, int repeat) {
        if (!prepared) return;
        long dur = video.getDuration();
        long from = seekTarget >= 0 ? seekTarget : video.getCurrentPosition();
        long step = repeat > 6 ? BIG_STEP_MS * 2 : repeat > 2 ? BIG_STEP_MS : STEP_MS;
        seekTarget = Math.max(0, Math.min(dur > 0 ? dur - 1000 : Long.MAX_VALUE, from + (forward ? step : -step)));
        showBar();
        tick.run();
        handler.removeCallbacks(tick);
        handler.removeCallbacks(doSeek);
        handler.postDelayed(doSeek, 450);
        handler.postDelayed(tick, 500);
    }

    private void togglePause() {
        if (!prepared) return;
        if (video.isPlaying()) {
            video.pause();
            savePlace();
            bar.animate().alpha(1f).setDuration(Ui.FOCUS_MS).start();
            handler.removeCallbacks(hideBar);
        } else {
            video.start();
            showBar();
        }
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (menu.getVisibility() == View.VISIBLE) return super.onKeyDown(code, e);
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (e.getRepeatCount() == 0) togglePause();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                if (!video.isPlaying()) togglePause();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (video.isPlaying()) togglePause();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                skip(false, e.getRepeatCount());
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                skip(true, e.getRepeatCount());
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                showBar();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_CAPTIONS:
                if (e.getRepeatCount() == 0) openMenu();
                return true;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_CHANNEL_UP:
                if (index + 1 < paths.size()) {
                    savePlace();
                    start(index + 1);
                }
                return true;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                if (index > 0) {
                    savePlace();
                    start(index - 1);
                }
                return true;
            default:
                return super.onKeyDown(code, e);
        }
    }

    @Override
    public void onBackPressed() {
        if (menu.getVisibility() == View.VISIBLE) {
            closeMenu();
            return;
        }
        super.onBackPressed();
    }

    // ---- menu: start over, subtitles, sound

    private void useSubtitleFile(File f) {
        cues = Subtitles.read(f);
        subtitleName = Subtitles.label(file, f);
        if (cues.isEmpty()) {
            cues = null;
            subtitleName = "Off";
        }
        deselectInnerText();
    }

    /** Subtitle tracks inside the video file, as player track numbers. */
    private List<Integer> innerTextTracks() {
        List<Integer> out = new ArrayList<>();
        if (mp == null) return out;
        try {
            MediaPlayer.TrackInfo[] info = mp.getTrackInfo();
            for (int i = 0; i < info.length; i++) {
                int t = info[i].getTrackType();
                if (t == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT || t == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_SUBTITLE) out.add(i);
            }
        } catch (RuntimeException ex) {
            Log.w(TAG, "tracks not readable", ex);
        }
        return out;
    }

    private List<Integer> audioTracks() {
        List<Integer> out = new ArrayList<>();
        if (mp == null) return out;
        try {
            MediaPlayer.TrackInfo[] info = mp.getTrackInfo();
            for (int i = 0; i < info.length; i++) {
                if (info[i].getTrackType() == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO) out.add(i);
            }
        } catch (RuntimeException ex) {
            Log.w(TAG, "tracks not readable", ex);
        }
        return out;
    }

    private String language(int track) {
        try {
            String l = mp.getTrackInfo()[track].getLanguage();
            if (l == null || l.isEmpty() || "und".equals(l)) return null;
            String name = new Locale(l).getDisplayLanguage(Locale.ENGLISH);
            return name.isEmpty() ? l : name;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private int innerSelected = -1;

    private void deselectInnerText() {
        if (mp == null || innerSelected < 0) return;
        try {
            mp.deselectTrack(innerSelected);
        } catch (RuntimeException ex) {
            Log.w(TAG, "subtitle track not switched off", ex);
        }
        innerSelected = -1;
    }

    private void useInnerText(int track, String name) {
        cues = null;
        subtitle.setText("");
        deselectInnerText();
        try {
            mp.setOnTimedTextListener((p, text) -> subtitle.setText(text != null && text.getText() != null ? text.getText().trim() : ""));
            mp.selectTrack(track);
            innerSelected = track;
            subtitleName = name;
        } catch (RuntimeException ex) {
            Log.w(TAG, "subtitle track not selected", ex);
            Toaster.show(this, "These subtitles cannot be shown");
        }
    }

    private void openMenu() {
        menu.removeAllViews();
        menu.setBackgroundColor(0x80000000);
        ScrollView sc = new ScrollView(this);
        sc.setVerticalScrollBarEnabled(false);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 24);
        card.setPadding(p, p, p, p);
        sc.addView(card);
        sc.setBackground(Ui.round(Ui.GLASS, Ui.dp(this, Ui.R_L)));
        menuLabel(card, "Video");
        View first = menuRow(card, "Start over", false, () -> {
            Resume.clear(this, file.getAbsolutePath());
            video.seekTo(0);
            if (!video.isPlaying()) video.start();
            closeMenu();
        });

        menuLabel(card, "Subtitles");
        menuRow(card, "Off", "Off".equals(subtitleName), () -> {
            cues = null;
            deselectInnerText();
            subtitle.setText("");
            subtitleName = "Off";
            closeMenu();
        });
        for (final File f : Subtitles.findFor(file)) {
            final String name = Subtitles.label(file, f) + "  ·  " + f.getName();
            menuRow(card, name, cues != null && subtitleName.equals(Subtitles.label(file, f)), () -> {
                useSubtitleFile(f);
                closeMenu();
            });
        }
        int n = 1;
        for (final int t : innerTextTracks()) {
            String lang = language(t);
            final String name = "In the video " + n++ + (lang != null ? "  ·  " + lang : "");
            menuRow(card, name, innerSelected == t, () -> {
                useInnerText(t, name);
                closeMenu();
            });
        }

        List<Integer> audio = audioTracks();
        if (audio.size() > 1) {
            menuLabel(card, "Sound");
            int k = 1;
            for (final int t : audio) {
                String lang = language(t);
                boolean on = audioTrack == t || (audioTrack < 0 && k == 1);
                menuRow(card, "Track " + k++ + (lang != null ? "  ·  " + lang : ""), on, () -> {
                    try {
                        mp.selectTrack(t);
                        audioTrack = t;
                    } catch (RuntimeException ex) {
                        Log.w(TAG, "sound track not selected", ex);
                        Toaster.show(this, "This sound track cannot be played");
                    }
                    closeMenu();
                });
            }
        }

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Ui.dp(this, 520), -2, Gravity.END | Gravity.CENTER_VERTICAL);
        lp.rightMargin = Ui.dp(this, 64);
        lp.topMargin = lp.bottomMargin = Ui.dp(this, 48);
        menu.addView(sc, lp);
        menu.setVisibility(View.VISIBLE);
        menu.setAlpha(0f);
        menu.animate().alpha(1f).setDuration(Ui.FOCUS_MS).start();
        sc.setTranslationX(Ui.dp(this, 24));
        sc.animate().translationX(0).setDuration(Ui.ENTER_MS).setInterpolator(Ui.EASE).start();
        if (first != null) first.requestFocus();
    }

    private void closeMenu() {
        menu.animate().alpha(0f).setDuration(Ui.FOCUS_MS).withEndAction(() -> {
            menu.setVisibility(View.GONE);
            menu.removeAllViews();
        }).start();
    }

    private void menuLabel(LinearLayout card, String s) {
        TextView t = Ui.text(this, s.toUpperCase(Locale.US), 12, Ui.medium, Ui.MUTED);
        t.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = Ui.dp(this, 16);
        lp.topMargin = card.getChildCount() == 0 ? 0 : Ui.dp(this, 16);
        lp.bottomMargin = Ui.dp(this, 8);
        card.addView(t, lp);
    }

    private View menuRow(LinearLayout card, String text, boolean on, Runnable action) {
        final LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0);
        r.setFocusable(true);
        final TextView t = Ui.text(this, text, 18, Ui.medium, Ui.PAPER);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        final View mark = new Glyphs.Radio(this, on);
        r.addView(mark, new LinearLayout.LayoutParams(Ui.dp(this, 22), Ui.dp(this, 22)));
        r.setOnFocusChangeListener((v, has) -> Ui.focusLook(r, has, 0x00000000, Ui.R_S, t));
        r.setOnClickListener(v -> action.run());
        card.addView(r, new LinearLayout.LayoutParams(-1, Ui.dp(this, 52)));
        return r;
    }
}
