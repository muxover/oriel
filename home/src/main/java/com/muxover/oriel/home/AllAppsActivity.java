package com.muxover.oriel.home;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** Every installed app in a grid of lifting tiles. */
public class AllAppsActivity extends Activity implements Tile.Listener {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.INK);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        col.setPadding(Ui.dp(this, 64), Ui.dp(this, 48), Ui.dp(this, 64), Ui.dp(this, 64));
        scroll.addView(col, new FrameLayout.LayoutParams(-1, -2));

        TextView h = Ui.text(this, "All apps", 40, Ui.bold, Ui.PAPER);
        h.setLetterSpacing(-0.02f);
        col.addView(h);

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(5);
        grid.setClipChildren(false);
        grid.setUseDefaultMargins(false);
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(-2, -2);
        gp.topMargin = Ui.dp(this, 40);
        col.addView(grid, gp);

        List<Entry> apps = Apps.all(this);
        Tile first = null;
        for (int i = 0; i < apps.size(); i++) {
            Tile t = new Tile(this, apps.get(i), 192, 108, true, this);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.rightMargin = Ui.dp(this, 32);
            lp.bottomMargin = Ui.dp(this, 32);
            grid.addView(t, lp);
            if (first == null) first = t;
        }
        setContentView(scroll);
        if (first != null) {
            final Tile f = first;
            f.card.post(f.card::requestFocus);
        }
    }

    @Override
    public void onFocused(Tile t) {
        LaunchOverlay.prepare(this, t.entry);
    }

    @Override
    public void onOpen(Tile t) {
        Intent i = t.entry.intent;
        if (i == null) return;
        LaunchOverlay.open(this, t.entry, i);
    }

    @Override
    public boolean dispatchGenericMotionEvent(android.view.MotionEvent e) {
        Ui.hover(this, e);
        return super.dispatchGenericMotionEvent(e);
    }
}
