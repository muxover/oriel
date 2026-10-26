package com.muxover.oriel.home;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.reflect.Method;

/**
 * Oriel's own screen for HDMI and AV, in place of the maker's TV player. It drives HiSilicon's TV libraries
 * (com.hisilicon.android and com.hisilicon.android.tvapi), the same calls the maker's player makes: pick the
 * input full-window, and let it go again on the way out. The picture is on the TV's video plane, under
 * Android's graphics, so this window stays transparent apart from Oriel's bar and messages.
 */
public class TvViewActivity extends Activity {
    private static final String TAG = "Oriel";
    private static final int SIGNAL_OK = 0, NO_SIGNAL = 1, UNSUPPORTED = 2;
    private static final long BAR_MS = 4000, POLL_MS = 1500;
    private static final String HOLDER = "HiTvPlayer";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Object sources;   // com.hisilicon.android.tv.TvSourceManager
    private Object video;     // com.hisilicon.android.tvapi.SourceManager
    private int id;
    private String name;
    private boolean selected;
    private LinearLayout bar;
    private TextView barTitle, barDetail, message;

    /** True when this TV's libraries can be loaded here; otherwise the maker's player is used. */
    static boolean available(Context c) {
        try {
            Class.forName("com.hisilicon.android.tv.TvSourceManager", false, c.getClassLoader());
            Class.forName("com.hisilicon.android.tvapi.HitvManager", false, c.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError ex) {
            return false;
        }
    }

    static void open(Context c, int id, String name) {
        c.startActivity(new Intent(c, TvViewActivity.class).putExtra("id", id).putExtra("name", name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        id = getIntent().getIntExtra("id", 9);
        name = getIntent().getStringExtra("name");
        if (name == null) name = "HDMI";
        try {
            Class<?> tsm = Class.forName("com.hisilicon.android.tv.TvSourceManager");
            sources = tsm.getMethod("getInstance").invoke(null);
            Class<?> hitv = Class.forName("com.hisilicon.android.tvapi.HitvManager");
            Object m = hitv.getMethod("getInstance").invoke(null);
            video = hitv.getMethod("getSourceManager").invoke(m);
        } catch (Throwable ex) {
            Log.w(TAG, "TV libraries not usable here, opening the maker's player", ex);
            Shell.send(this, "sh am start -n com.hisilicon.tvui/.MainActivity --ei SourceName " + id);
            finish();
            return;
        }
        FrameLayout root = new FrameLayout(this);
        // The picture is on the TV's own full-screen video plane, under Android's graphics; this window stays
        // transparent apart from Oriel's bar and messages. Tying the picture to a surface instead sizes it from
        // Oriel's menu size, which is smaller than the panel on slow TVs.
        root.setBackgroundColor(0x00000000);

        message = Ui.text(this, "", 28, Ui.semibold, Ui.PAPER);
        message.setGravity(Gravity.CENTER);
        message.setVisibility(View.GONE);
        root.addView(message, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 24);
        bar.setPadding(p, Ui.dp(this, 18), p, Ui.dp(this, 18));
        bar.setBackground(Ui.round(Ui.GLASS, Ui.dp(this, Ui.R_L)));
        barTitle = Ui.text(this, name, 24, Ui.bold, Ui.PAPER);
        barDetail = Ui.text(this, "Connecting", 16, Ui.regular, Ui.TEXT2);
        bar.addView(barTitle);
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(-2, -2);
        dp.topMargin = Ui.dp(this, 4);
        bar.addView(barDetail, dp);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        bp.leftMargin = Ui.dp(this, 48);
        bp.bottomMargin = Ui.dp(this, 48);
        root.addView(bar, bp);
        setContentView(root);
        Prefs.of(this).edit().putInt("last_source", id).apply();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (selected) return;
        try {
            // The TV service routes the picture to the holder named here; the maker's player uses this name.
            call(sources, "setSourceHolder", new Class<?>[]{String.class}, HOLDER);
            call(sources, "selectSource", new Class<?>[]{int.class, int.class}, id, 0);
            call(sources, "setFullWindow", new Class<?>[]{boolean.class}, true);
            selected = true;
            Log.i(TAG, "showing " + name + " (source " + id + ")");
            handler.postDelayed(this::fillPanel, 300);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            Log.w(TAG, "could not switch to " + name, ex);
            Toaster.show(this, "This TV did not switch to " + name);
            finish();
            return;
        }
        showBar();
        handler.post(poll);
    }

    @Override
    protected void onStop() {
        super.onStop();
        release();
        finish();
    }

    /**
     * The TV sizes its full window from Oriel's menu size, which is smaller than the panel on slow TVs (720p
     * menus on a 1080p panel); the window is set to the whole panel instead.
     */
    private void fillPanel() {
        if (!selected) return;
        try {
            android.view.Display.Mode mode = getWindowManager().getDefaultDisplay().getMode();
            int w = mode.getPhysicalWidth(), h = mode.getPhysicalHeight();
            Object r = call(video, "getWindowRect", new Class<?>[]{int.class}, 0);
            int rw = (Integer) r.getClass().getMethod("getW").invoke(r);
            int rh = (Integer) r.getClass().getMethod("getH").invoke(r);
            if (rw >= w && rh >= h) return;
            r.getClass().getMethod("setX", int.class).invoke(r, 0);
            r.getClass().getMethod("setY", int.class).invoke(r, 0);
            r.getClass().getMethod("setW", int.class).invoke(r, w);
            r.getClass().getMethod("setH", int.class).invoke(r, h);
            Class<?> rect = Class.forName("com.hisilicon.android.tvapi.vo.RectInfo");
            call(video, "setWindowRect", new Class<?>[]{rect, int.class, boolean.class}, r, 0, false);
            Log.i(TAG, "video window " + rw + "x" + rh + " set to the panel, " + w + "x" + h);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            Log.w(TAG, "could not size the video window", ex);
        }
    }

    /** Lets the input go so Android's own picture comes back. */
    private void release() {
        handler.removeCallbacksAndMessages(null);
        if (!selected) return;
        selected = false;
        try {
            call(sources, "setSourceHolder", new Class<?>[]{String.class}, "null");
            call(sources, "deselectSource", new Class<?>[]{int.class, boolean.class}, id, true);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            Log.w(TAG, "could not let go of " + name, ex);
        }
    }

    /** Signal and picture size, every POLL_MS: the message in the middle and the bar's second line. */
    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            int signal = -1;
            String size = null;
            try {
                signal = (Integer) call(video, "getSignalStatus", new Class<?>[0]);
                Object t = call(video, "getTimingInfo", new Class<?>[0]);
                if (t != null) {
                    int w = (Integer) t.getClass().getMethod("getiWidth").invoke(t);
                    int hgt = (Integer) t.getClass().getMethod("getiHeight").invoke(t);
                    int fps = (Integer) t.getClass().getMethod("getiFrame").invoke(t);
                    if (w > 0 && hgt > 0) size = w + " × " + hgt + (fps > 0 ? " · " + (fps > 1000 ? fps / 1000 : fps) + " Hz" : "");
                }
            } catch (ReflectiveOperationException | RuntimeException ex) {
                Log.w(TAG, "could not read the signal", ex);
            }
            if (signal == NO_SIGNAL) {
                message.setText("No signal on " + name + "\nCheck the cable and that the device is on");
                message.setVisibility(View.VISIBLE);
                barDetail.setText("No signal");
            } else if (signal == UNSUPPORTED) {
                message.setText("This TV can't show the picture format coming in on " + name);
                message.setVisibility(View.VISIBLE);
                barDetail.setText("Format not supported");
            } else {
                message.setVisibility(View.GONE);
                barDetail.setText(signal == SIGNAL_OK && size != null ? size : "Connecting");
            }
            handler.postDelayed(this, POLL_MS);
        }
    };

    private final Runnable hideBar = () -> bar.animate().alpha(0f).setDuration(Ui.ENTER_MS).start();

    private void showBar() {
        bar.animate().cancel();
        bar.setAlpha(1f);
        handler.removeCallbacks(hideBar);
        handler.postDelayed(hideBar, BAR_MS);
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_INFO
            || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
            showBar();
            return true;
        }
        return super.onKeyDown(code, e);
    }

    private static Object call(Object target, String method, Class<?>[] types, Object... args) throws ReflectiveOperationException {
        Method m = target.getClass().getMethod(method, types);
        return m.invoke(target, args);
    }
}
