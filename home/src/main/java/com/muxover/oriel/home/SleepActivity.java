package com.muxover.oriel.home;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Oriel's sleep timer, in place of the maker's: the TV goes to standby after the chosen time. */
public class SleepActivity extends Activity {
    private static final int[] MINUTES = {0, 15, 30, 45, 60, 90, 120};

    static void open(Context c) {
        c.startActivity(new Intent(c, SleepActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
    }

    /** Elapsed-realtime when the TV goes to standby, or 0 when no timer is set. */
    static long endsAt(Context c) {
        long at = Prefs.of(c).getLong("sleep_at", 0);
        return at > SystemClock.elapsedRealtime() ? at : 0;
    }

    static void set(Context c, int minutes) {
        AlarmManager am = (AlarmManager) c.getSystemService(ALARM_SERVICE);
        PendingIntent pi = PendingIntent.getBroadcast(c, 0, new Intent(c, Fire.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        am.cancel(pi);
        long at = minutes > 0 ? SystemClock.elapsedRealtime() + minutes * 60_000L : 0;
        Prefs.of(c).edit().putLong("sleep_at", at).apply();
        if (at > 0) am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
    }

    /** The timer ran out: the same standby as a short press of the remote's Power button. */
    public static class Fire extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            Prefs.of(c).edit().putLong("sleep_at", 0).apply();
            Shell.send(c, "key 26");
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xB3050506);
        root.setOnClickListener(v -> finish());
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 32);
        box.setPadding(p, p, p, p);
        box.setBackground(Ui.round(Ui.GLASS, Ui.dp(this, Ui.R_L)));
        box.setElevation(Ui.dp(this, 24));
        box.addView(Ui.text(this, "Sleep timer", 28, Ui.bold, Ui.PAPER));
        long left = endsAt(this);
        String now = left == 0 ? "Off" : "Standby in " + Math.max(1, (left - SystemClock.elapsedRealtime()) / 60_000L) + " min";
        TextView sub = Ui.text(this, now, 16, Ui.regular, Ui.TEXT2);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2);
        sp.topMargin = Ui.dp(this, 6);
        sp.bottomMargin = Ui.dp(this, 20);
        box.addView(sub, sp);
        View focus = null;
        for (int m : MINUTES) {
            View o = option(box, m == 0 ? "Off" : m + " minutes", () -> {
                set(this, m);
                Toaster.show(this, m == 0 ? "Sleep timer off" : "The TV goes to standby in " + m + " minutes");
                finish();
            });
            if (focus == null) focus = o;
        }
        root.addView(box, new FrameLayout.LayoutParams(Ui.dp(this, 420), -2, Gravity.CENTER));
        setContentView(root);
        focus.requestFocus();
    }

    private View option(LinearLayout parent, String name, Runnable action) {
        final LinearLayout v = new LinearLayout(this);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setPadding(Ui.dp(this, 20), 0, Ui.dp(this, 20), 0);
        v.setFocusable(true);
        v.setBackground(Ui.round(0x14F4F3EF, Ui.dp(this, Ui.R_M)));
        final TextView t = Ui.text(this, name, 18, Ui.semibold, Ui.PAPER);
        v.addView(t);
        v.setOnFocusChangeListener((x, has) -> Ui.focusLook(v, has, 0x14F4F3EF, Ui.R_M, t));
        v.setOnClickListener(x -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 52));
        if (parent.getChildCount() > 2) lp.topMargin = Ui.dp(this, 8);
        parent.addView(v, lp);
        return v;
    }

    @Override
    public boolean dispatchGenericMotionEvent(android.view.MotionEvent e) {
        Ui.hover(this, e);
        return super.dispatchGenericMotionEvent(e);
    }
}
