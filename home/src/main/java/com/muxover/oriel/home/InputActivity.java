package com.muxover.oriel.home;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** The input picker: a bar at the bottom of the screen over whatever is showing. */
public class InputActivity extends Activity {
    /** Source ids of the maker's TV player (HDMI 1 is 9, AV is 3). */
    private static final int[] IDS = {-1, 9, 10, 11, 3};
    private static final String[] NAMES = {"Home", "HDMI 1", "HDMI 2", "HDMI 3", "AV"};

    static void open(Context c) {
        c.startActivity(new Intent(c, InputActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
    }

    /**
     * Switches the TV to a source: Oriel's own viewer when this TV's libraries load here, otherwise the
     * maker's player, whose start needs a system permission, so the root helper starts it.
     */
    static void select(Context c, int id) {
        if (id < 0) {
            c.startActivity(new Intent(c, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        if (TvViewActivity.available(c)) {
            TvViewActivity.open(c, id, nameOf(id));
            return;
        }
        Shell.send(c, "sh am start -n com.hisilicon.tvui/.MainActivity --ei SourceName " + id);
    }

    static String nameOf(int id) {
        for (int i = 0; i < IDS.length; i++) if (IDS[i] == id) return NAMES[i];
        return "Input";
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0x8C050506);
        root.setOnClickListener(v -> finish());
        LinearLayout bar = new LinearLayout(this);
        int p = Ui.dp(this, 16);
        bar.setPadding(p, p, p, p);
        bar.setBackground(Ui.round(Ui.GLASS, Ui.dp(this, Ui.R_PILL)));
        bar.setElevation(Ui.dp(this, 24));
        bar.setClipChildren(false);
        root.setClipChildren(false);
        int last = lastSource();
        View focus = null;
        for (int i = 0; i < IDS.length; i++) {
            final int id = IDS[i];
            String sub = id < 0 ? "Apps" : id == last ? "Last used" : "Input";
            View b = pill(NAMES[i], sub);
            b.setOnClickListener(v -> {
                select(this, id);
                finish();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 168), Ui.dp(this, 88));
            if (i > 0) lp.leftMargin = Ui.dp(this, 12);
            bar.addView(b, lp);
            if (focus == null && id == last) focus = b;
        }
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        bp.bottomMargin = Ui.dp(this, 56);
        root.addView(bar, bp);
        setContentView(root);
        (focus != null ? focus : bar.getChildAt(0)).requestFocus();
        bar.setTranslationY(Ui.dp(this, 24));
        bar.setAlpha(0f);
        bar.animate().translationY(0).alpha(1f).setDuration(Ui.ENTER_MS).setInterpolator(Ui.EASE).start();
    }

    private View pill(String name, String sub) {
        final LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setPadding(Ui.dp(this, 20), 0, Ui.dp(this, 20), 0);
        v.setFocusable(true);
        v.setBackground(Ui.round(0x14F4F3EF, Ui.dp(this, Ui.R_M)));
        final TextView t = Ui.text(this, name, 18, Ui.semibold, Ui.PAPER);
        final TextView s = Ui.text(this, sub, 14, Ui.regular, Ui.TEXT2);
        v.addView(t);
        v.addView(s);
        v.setOnFocusChangeListener((x, has) -> Ui.focusLook(v, has, 0x14F4F3EF, Ui.R_M, t, s));
        return v;
    }

    /** The source the TV used last, kept by the maker in persist.sys.memorysource. */
    private static int lastSource() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            String v = (String) sp.getMethod("get", String.class, String.class).invoke(null, "persist.sys.memorysource", "-1");
            return Integer.parseInt(v.trim());
        } catch (ReflectiveOperationException | NumberFormatException ex) {
            android.util.Log.w("Oriel", "last input unknown", ex);
            return -1;
        }
    }

    @Override
    public boolean dispatchGenericMotionEvent(android.view.MotionEvent e) {
        Ui.hover(this, e);
        return super.dispatchGenericMotionEvent(e);
    }
}
