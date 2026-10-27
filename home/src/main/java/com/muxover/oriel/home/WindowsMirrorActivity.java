package com.muxover.oriel.home;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The screen shown while a Windows PC mirrors to the TV. The picture is on the TV's video plane, under Android's
 * graphics, so this window stays transparent apart from a bar that names the PC and fades. Back ends the session.
 */
public class WindowsMirrorActivity extends Activity {
    /** Sent by WindowsReceiver when the PC stops or the connection drops. */
    static final String ACTION_END = "com.muxover.oriel.WINDOWS_MIRROR_END";
    private static final long BAR_MS = 4000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout bar;
    private boolean ending;

    private final BroadcastReceiver ended = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            ending = true;
            finish();
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0x00000000);

        bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 24);
        bar.setPadding(p, Ui.dp(this, 18), p, Ui.dp(this, 18));
        bar.setBackground(Ui.round(Ui.GLASS, Ui.dp(this, Ui.R_L)));
        String peer = getIntent().getStringExtra("peer");
        bar.addView(Ui.text(this, peer == null || peer.isEmpty() ? "Windows PC" : peer, 24, Ui.bold, Ui.PAPER));
        TextView hint = Ui.text(this, "Mirroring. Press Back to stop", 16, Ui.regular, Ui.TEXT2);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-2, -2);
        hp.topMargin = Ui.dp(this, 4);
        bar.addView(hint, hp);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        bp.leftMargin = Ui.dp(this, 48);
        bp.bottomMargin = Ui.dp(this, 48);
        root.addView(bar, bp);
        setContentView(root);
        registerReceiver(ended, new IntentFilter(ACTION_END));
        showBar();
    }

    private final Runnable hideBar = () -> bar.animate().alpha(0f).setDuration(Ui.ENTER_MS).start();

    private void showBar() {
        bar.animate().cancel();
        bar.setAlpha(1f);
        handler.removeCallbacks(hideBar);
        handler.postDelayed(hideBar, BAR_MS);
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_BACK) {
            if (!ending) Shell.send(this, "sh touch " + WindowsReceiver.STOP);
            ending = true;
            finish();
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_INFO) {
            showBar();
            return true;
        }
        return super.onKeyDown(code, e);
    }

    /** Leaving the screen (Home, another app) ends the session too, so the PC is never left mirroring unseen. */
    @Override
    protected void onStop() {
        super.onStop();
        if (!ending) Shell.send(this, "sh touch " + WindowsReceiver.STOP);
        ending = true;
        finish();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        unregisterReceiver(ended);
        super.onDestroy();
    }
}
