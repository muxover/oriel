package com.muxover.oriel.home;

import android.app.Activity;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

/**
 * The idle screen. With music playing it is Now Playing (art, title, progress, controls over colours
 * from the album art); otherwise a large clock with weather and the next prayer over drifting colours.
 */
public class AmbientActivity extends Activity {
    static volatile boolean showing;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final DecelerateInterpolator ease = new DecelerateInterpolator(2f);
    private ColorFlow flow;
    private View clockLayer, nowLayer;
    private TextView weather, prayer, score, title, artist, source, elapsed, remain;
    private ImageView art;
    private LaunchBar progress;
    private IconView playIcon;
    private FrameLayout[] buttons;
    private String lastArtKey;
    private boolean nowMode;
    /** Opened for music sent to the TV (AirPlay): it closes when that music ends instead of becoming the clock. */
    private boolean forMusic;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        FrameLayout root = new FrameLayout(this);
        flow = new ColorFlow(this);
        root.addView(flow, new FrameLayout.LayoutParams(-1, -1));
        clockLayer = buildClock();
        nowLayer = buildNow();
        root.addView(clockLayer, new FrameLayout.LayoutParams(-1, -1));
        root.addView(nowLayer, new FrameLayout.LayoutParams(-1, -1));
        nowLayer.setVisibility(View.GONE);
        setContentView(root);
        root.setAlpha(0f);
        root.animate().alpha(1f).setDuration(900).setInterpolator(ease).start();
        forMusic = "com.muxover.oriel.NOW_PLAYING".equals(getIntent().getAction());
    }

    @Override
    protected void onNewIntent(android.content.Intent i) {
        super.onNewIntent(i);
        if ("com.muxover.oriel.NOW_PLAYING".equals(i.getAction())) forMusic = true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        showing = true;
        handler.post(tick);
        weather.setVisibility(View.GONE);
        if (Prefs.saverShows(this, "weather")) Weather.get(this, t -> {
            weather.setText(Prefs.placeName(this) + "  " + t);
            weather.setVisibility(View.VISIBLE);
        });
        handler.post(scores);
    }

    @Override
    protected void onPause() {
        showing = false;
        handler.removeCallbacks(tick);
        handler.removeCallbacks(scores);
        super.onPause();
    }

    /** A live match, if one is on, in its own chip; checked every two minutes. */
    private final Runnable scores = new Runnable() {
        @Override
        public void run() {
            if (!Prefs.saverShows(AmbientActivity.this, "scores")) return;
            Scores.live(AmbientActivity.this, s -> {
                score.setText(s == null ? "" : "\u25CF  " + s);
                score.setVisibility(s == null ? View.GONE : View.VISIBLE);
            });
            handler.postDelayed(this, 2 * 60 * 1000L);
        }
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 1000);
        }
    };

    private void refresh() {
        String[] p = Prefs.saverShows(this, "prayer") ? PrayerTimes.next(this) : null;
        prayer.setVisibility(p == null ? View.GONE : View.VISIBLE);
        if (p != null) prayer.setText(p[0] + "  " + p[1]);
        MediaController mc = MediaWatcher.playing();
        // music sent to the TV stays on screen while paused on the phone
        if (mc == null && forMusic) mc = MediaWatcher.of("com.muxover.oriel.mirror");
        setNowMode(mc != null);
        if (mc == null) return;
        MediaMetadata md = mc.getMetadata();
        PlaybackState st = mc.getPlaybackState();
        title.setText(text(md, MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE));
        artist.setText(text(md, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE));
        source.setText("Playing on " + label(mc.getPackageName()));
        Bitmap b = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (b == null) b = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (b == null) b = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        // The session hands out a new copy of the art on every read: the song decides when the art changes.
        String key = title.getText() + "|" + artist.getText() + "|" + (b != null ? b.getWidth() + "x" + b.getHeight() : "none");
        if (!key.equals(lastArtKey)) {
            lastArtKey = key;
            if (b != null) art.setImageBitmap(b); else art.setImageDrawable(null);
            flow.setColors(ColorFlow.paletteOf(b));
        }
        long dur = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
        long pos = st.getPosition();
        if (st.getState() == PlaybackState.STATE_PLAYING) {
            pos += (long) ((SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime()) * st.getPlaybackSpeed());
        }
        progress.setVisibility(dur > 0 ? View.VISIBLE : View.INVISIBLE);
        if (dur > 0) {
            progress.setProgress(Math.min(1f, pos / (float) dur));
            elapsed.setText(time(pos));
            remain.setText("-" + time(Math.max(0, dur - pos)));
        } else {
            elapsed.setText("");
            remain.setText("");
        }
        playIcon.setKind(st.getState() == PlaybackState.STATE_PLAYING ? IconView.PAUSE : IconView.PLAY);
    }

    /** Music sent to the TV may take a few seconds to start playing; until then Now Playing waits for it. */
    private long musicWaitUntil;

    private void setNowMode(boolean on) {
        if (forMusic && !on) {
            if (musicWaitUntil == 0) musicWaitUntil = SystemClock.uptimeMillis() + 8000;
            if (nowMode || SystemClock.uptimeMillis() > musicWaitUntil) {
                finish();
                return;
            }
        }
        if (on) musicWaitUntil = 0;
        if (on == nowMode) return;
        nowMode = on;
        View in = on ? nowLayer : clockLayer, out = on ? clockLayer : nowLayer;
        out.animate().alpha(0f).setDuration(400).withEndAction(() -> out.setVisibility(View.GONE)).start();
        in.setAlpha(0f);
        in.setVisibility(View.VISIBLE);
        in.animate().alpha(1f).setDuration(600).setInterpolator(ease).start();
        if (on) buttons[1].requestFocus();
        else flow.setColors(new int[]{0xFF3B2A8F, Ui.ACCENT, 0xFF1F6FB2});
    }

    private View buildClock() {
        FrameLayout f = new FrameLayout(this);
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        TextClock t = new TextClock(this);
        Clock.time(this, t, false);
        t.setTextSize(Prefs.clockAmPm(this) && !"24".equals(Prefs.clockFormat(this)) ? 132 : 168);
        t.setTypeface(Ui.light);
        t.setTextColor(Ui.PAPER);
        t.setLetterSpacing(-0.04f);
        t.setIncludeFontPadding(false);
        left.addView(t);
        TextClock d = new TextClock(this);
        Clock.date(this, d);
        d.setTextSize(24);
        d.setTypeface(Ui.regular);
        d.setTextColor(0xCCF4F3EF);
        left.addView(d);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        lp.leftMargin = Ui.dp(this, 96);
        lp.bottomMargin = Ui.dp(this, 88);
        f.addView(left, lp);

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.END);
        weather = chip(right, "");
        prayer = chip(right, "");
        score = chip(right, "");
        score.setVisibility(View.GONE);
        FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        rp.rightMargin = Ui.dp(this, 96);
        rp.bottomMargin = Ui.dp(this, 96);
        f.addView(right, rp);
        return f;
    }

    private TextView chip(LinearLayout parent, String s) {
        TextView t = Ui.text(this, s, 16, Ui.medium, Ui.PAPER);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.dp(this, 20), 0, Ui.dp(this, 20), 0);
        t.setBackground(Ui.round(0x14F4F3EF, Ui.dp(this, 22)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, Ui.dp(this, 44));
        if (parent.getChildCount() > 0) lp.topMargin = Ui.dp(this, 16);
        parent.addView(t, lp);
        return t;
    }

    private View buildNow() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(this, 96), 0, Ui.dp(this, 96), 0);
        row.setClipChildren(false);

        art = new ImageView(this);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setBackground(Ui.round(0x33F4F3EF, Ui.dp(this, 20)));
        Ui.clipRound(art, Ui.dp(this, 20));
        int a = Ui.dp(this, 400);
        row.addView(art, new LinearLayout.LayoutParams(a, a));

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
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2);
        tp.topMargin = Ui.dp(this, 12);
        col.addView(title, tp);
        artist = Ui.text(this, "", 28, Ui.regular, 0xBFF4F3EF);
        artist.setSingleLine(true);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, -2);
        ap.topMargin = Ui.dp(this, 8);
        col.addView(artist, ap);

        progress = new LaunchBar(this);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 32));
        pp.topMargin = Ui.dp(this, 28);
        pp.leftMargin = -Ui.dp(this, 14);
        pp.rightMargin = -Ui.dp(this, 14);
        col.addView(progress, pp);
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
        clp.topMargin = Ui.dp(this, 28);
        col.addView(controls, clp);
        buttons = new FrameLayout[]{button(controls, IconView.PREV, 64), button(controls, IconView.PAUSE, 88), button(controls, IconView.NEXT, 64)};
        playIcon = (IconView) buttons[1].getChildAt(0);
        buttons[0].setOnClickListener(v -> transport(0));
        buttons[1].setOnClickListener(v -> transport(1));
        buttons[2].setOnClickListener(v -> transport(2));
        return row;
    }

    private FrameLayout button(LinearLayout parent, int kind, int sizeDp) {
        final FrameLayout b = new FrameLayout(this);
        b.setFocusable(true);
        b.setBackground(Ui.round(0x00000000, Ui.dp(this, sizeDp / 2f)));
        final IconView ic = new IconView(this, kind, Ui.PAPER);
        int s = Ui.dp(this, sizeDp * 0.42f);
        b.addView(ic, new FrameLayout.LayoutParams(s, s, Gravity.CENTER));
        b.setOnFocusChangeListener((v, has) -> {
            b.setBackground(Ui.round(has ? Ui.PAPER : 0x00000000, Ui.dp(this, sizeDp / 2f)));
            ic.setColor(has ? Ui.INK : Ui.PAPER);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, sizeDp), Ui.dp(this, sizeDp));
        if (parent.getChildCount() > 0) lp.leftMargin = Ui.dp(this, 24);
        parent.addView(b, lp);
        return b;
    }

    private void transport(int which) {
        MediaController mc = MediaWatcher.playing();
        if (mc == null) return;
        MediaController.TransportControls tc = mc.getTransportControls();
        if (which == 0) tc.skipToPrevious();
        else if (which == 2) tc.skipToNext();
        else {
            PlaybackState st = mc.getPlaybackState();
            if (st != null && st.getState() == PlaybackState.STATE_PLAYING) tc.pause(); else tc.play();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (nowMode) {
            if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                transport(1);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                finish();
                return true;
            }
            return super.onKeyDown(keyCode, event);
        }
        // the clock screen goes away on any key, like a screen saver
        finish();
        return true;
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    private static String text(MediaMetadata md, String a, String b) {
        String s = md.getString(a);
        if (s == null || s.isEmpty()) s = md.getString(b);
        return s != null ? s : "";
    }

    private String label(String pkg) {
        try {
            return String.valueOf(getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0)));
        } catch (android.content.pm.PackageManager.NameNotFoundException ex) {
            return pkg;
        }
    }

    private static String time(long ms) {
        long s = ms / 1000;
        return (s / 60) + ":" + String.format(java.util.Locale.US, "%02d", s % 60);
    }
}
