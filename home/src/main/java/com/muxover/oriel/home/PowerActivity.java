package com.muxover.oriel.home;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Oriel's power screen, in place of the maker's: turn off, restart, screen off. Turning off and restarting
 * show Oriel's own screen and then go straight to the system's power-off, so no maker dialog appears.
 */
public class PowerActivity extends Activity {
    private static final long GOODBYE_MS = 900;

    static void open(Context c) {
        c.startActivity(new Intent(c, PowerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
    }

    private FrameLayout root;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        root = new FrameLayout(this);
        root.setBackgroundColor(0xB3050506);
        root.setOnClickListener(v -> finish());

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 32);
        box.setPadding(p, p, p, p);
        box.setBackground(Ui.round(Ui.GLASS, Ui.dp(this, Ui.R_L)));
        box.setElevation(Ui.dp(this, 24));
        TextView title = Ui.text(this, "Power", 28, Ui.bold, Ui.PAPER);
        box.addView(title);
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-1, Ui.dp(this, 20));
        box.addView(new View(this), gap);
        if (!Shell.connected && !Sys.granted(this, "android.permission.REBOOT")) {
            TextView no = Ui.text(this, "Turning off and restarting need full system access, which this TV does not give Oriel. Use the remote's Power button.", 16, Ui.regular, Ui.TEXT2);
            box.addView(no, new LinearLayout.LayoutParams(-1, -2));
            box.addView(new View(this), new LinearLayout.LayoutParams(-1, Ui.dp(this, 16)));
            option(box, "Close", null, this::finish).requestFocus();
            root.addView(box, new FrameLayout.LayoutParams(Ui.dp(this, 520), -2, Gravity.CENTER));
            setContentView(root);
            return;
        }
        View first = option(box, "Turn off", "Everything stops; the remote's power button starts it again.", () -> goodbye("Turning off", false));
        option(box, "Restart", "Takes about a minute.", () -> goodbye("Restarting", true));
        option(box, "Screen off", "Music and downloads keep going.", () -> {
            finish();
            Shell.send(this, "key 223");
        });
        option(box, "Cancel", null, this::finish);
        root.addView(box, new FrameLayout.LayoutParams(Ui.dp(this, 520), -2, Gravity.CENTER));
        setContentView(root);
        first.requestFocus();
        box.setAlpha(0f);
        box.setTranslationY(Ui.dp(this, 16));
        box.animate().alpha(1f).translationY(0).setDuration(Ui.ENTER_MS).setInterpolator(Ui.EASE).start();
    }

    private View option(LinearLayout parent, String name, String sub, Runnable action) {
        final LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setPadding(Ui.dp(this, 20), 0, Ui.dp(this, 20), 0);
        v.setFocusable(true);
        v.setBackground(Ui.round(0x14F4F3EF, Ui.dp(this, Ui.R_M)));
        final TextView t = Ui.text(this, name, 20, Ui.semibold, Ui.PAPER);
        v.addView(t);
        final TextView s = sub != null ? Ui.text(this, sub, 15, Ui.regular, Ui.TEXT2) : null;
        if (s != null) {
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2);
            sp.topMargin = Ui.dp(this, 4);
            v.addView(s, sp);
        }
        v.setOnFocusChangeListener((x, has) -> Ui.focusLook(v, has, 0x14F4F3EF, Ui.R_M, t, s));
        v.setOnClickListener(x -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(this, sub != null ? 76 : 56));
        if (parent.getChildCount() > 2) lp.topMargin = Ui.dp(this, 10);
        parent.addView(v, lp);
        return v;
    }

    /** Oriel's last screen, then the system's own restart or power-off. */
    private void goodbye(String text, boolean restart) {
        root.removeAllViews();
        root.setOnClickListener(null);
        root.setBackgroundColor(Ui.INK);
        TextView t = Ui.text(this, text, 28, Ui.semibold, Ui.PAPER);
        root.addView(t, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        t.setAlpha(0f);
        t.animate().alpha(1f).setDuration(Ui.ENTER_MS).start();
        new Handler(Looper.getMainLooper()).postDelayed(() -> Sys.power(this, restart), GOODBYE_MS);
    }

    @Override
    public boolean dispatchGenericMotionEvent(android.view.MotionEvent e) {
        Ui.hover(this, e);
        return super.dispatchGenericMotionEvent(e);
    }
}
