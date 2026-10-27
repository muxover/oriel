package com.muxover.oriel.home;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Mirror: Oriel's own AirPlay receiver is always listening, so this screen only says how to reach it.
 */
public class MirrorActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        FrameLayout root = new FrameLayout(this);
        root.setBackground(Ui.wash(0xFF2A2340, Ui.dp(this, 900), 0.8f, 0.15f));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        col.setPadding(Ui.dp(this, 96), Ui.dp(this, 96), Ui.dp(this, 96), Ui.dp(this, 64));
        TextView title = Ui.text(this, "Mirror to " + Device.name(this), 52, Ui.bold, Ui.PAPER);
        title.setLetterSpacing(-0.025f);
        col.addView(title);
        TextView sub = Ui.text(this, "From an iPhone, iPad, Mac or Windows PC. The TV is always ready; nothing to start here.", 20, Ui.regular, Ui.TEXT2);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2);
        sp.topMargin = Ui.dp(this, 16);
        col.addView(sub, sp);

        LinearLayout cards = new LinearLayout(this);
        cards.setClipChildren(false);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
        cp.topMargin = Ui.dp(this, 64);
        col.addView(cards, cp);
        String name = Device.name(this);
        card(cards, "iPhone and iPad", "Control Center", "Swipe down from the top-right corner, tap Screen Mirroring, then " + name + ". For only a video or a song, use the AirPlay button in the app.");
        card(cards, "Mac", "Control Center", "Click Control Center in the menu bar, then Screen Mirroring, then " + name + ".");
        card(cards, "Windows", "Win + K", "Press Win + K and pick " + name + ". For the sharpest picture, press Win + P and choose Second screen only.");

        TextView note = Ui.text(this, "Press Back on the remote to stop mirroring. Android phones cannot mirror to Oriel.", 16, Ui.regular, Ui.MUTED);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-2, -2);
        np.topMargin = Ui.dp(this, 40);
        col.addView(note, np);
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
    }

    /** A step to read: not focusable, since there is nothing to press here. */
    private void card(LinearLayout parent, String step, String name, String how) {
        final LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 32);
        v.setPadding(p, p, p, p);
        v.setBackground(Ui.round(0x14F4F3EF, Ui.dp(this, Ui.R_L)));
        TextView g = Ui.text(this, step, 15, Ui.mono, Ui.ACCENT_HI);
        TextView t = Ui.text(this, name, 26, Ui.semibold, Ui.PAPER);
        TextView h = Ui.text(this, how, 18, Ui.regular, Ui.TEXT2);
        h.setLineSpacing(0, 1.3f);
        v.addView(g);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.topMargin = Ui.dp(this, 12);
        v.addView(t, tp);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
        hp.topMargin = Ui.dp(this, 16);
        v.addView(h, hp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        if (parent.getChildCount() > 0) lp.leftMargin = Ui.dp(this, 32);
        parent.addView(v, lp);
    }

    @Override
    public boolean dispatchGenericMotionEvent(android.view.MotionEvent e) {
        Ui.hover(this, e);
        return super.dispatchGenericMotionEvent(e);
    }
}
