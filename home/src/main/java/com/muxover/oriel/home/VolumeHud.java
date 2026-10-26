package com.muxover.oriel.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** The volume pill in the top right, drawn over every app. */
final class VolumeHud {
    private static final long SHOW_MS = 1600;
    private static LinearLayout view;
    private static View fill;
    private static Speaker icon;
    private static TextView number;
    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static final Runnable hide = VolumeHud::remove;

    private VolumeHud() {}

    static void show(Context c, int percent, boolean muted) {
        Context app = c.getApplicationContext();
        if (view == null) build(app);
        int w = Ui.dp(app, 160);
        int p = muted ? 0 : Math.max(0, Math.min(100, percent));
        fill.animate().scaleX(Math.max(0.0001f, p / 100f)).setDuration(120).start();
        number.setText(muted ? "" : String.valueOf(p));
        icon.set(muted ? 0 : p);
        handler.removeCallbacks(hide);
        handler.postDelayed(hide, SHOW_MS);
    }

    private static void build(Context c) {
        Ui.init(c);
        view = new LinearLayout(c);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setPadding(Ui.dp(c, 20), 0, Ui.dp(c, 20), 0);
        view.setBackground(Ui.round(Ui.GLASS, Ui.dp(c, Ui.R_PILL)));
        icon = new Speaker(c);
        view.addView(icon, new LinearLayout.LayoutParams(Ui.dp(c, 24), Ui.dp(c, 24)));
        FrameLayout track = new FrameLayout(c);
        track.setBackground(Ui.round(0x33F4F3EF, Ui.dp(c, 3)));
        Ui.clipRound(track, Ui.dp(c, 3));
        fill = new View(c);
        fill.setBackground(Ui.round(Ui.PAPER, Ui.dp(c, 3)));
        fill.setPivotX(0);
        track.addView(fill, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, Ui.dp(c, 6), 1f);
        tp.leftMargin = Ui.dp(c, 16);
        tp.rightMargin = Ui.dp(c, 16);
        view.addView(track, tp);
        number = Ui.text(c, "", 14, Ui.mono, Ui.PAPER);
        number.setGravity(Gravity.END);
        view.addView(number, new LinearLayout.LayoutParams(Ui.dp(c, 28), -2));

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(Ui.dp(c, 280), Ui.dp(c, Ui.PILL_H),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.y = Ui.dp(c, 32);
        lp.windowAnimations = android.R.style.Animation_Toast;
        try {
            ((WindowManager) c.getSystemService(Context.WINDOW_SERVICE)).addView(view, lp);
        } catch (RuntimeException ex) {
            Log.e("Oriel", "volume popup could not be shown", ex);
            view = null;
        }
    }

    private static void remove() {
        if (view == null) return;
        try {
            ((WindowManager) view.getContext().getSystemService(Context.WINDOW_SERVICE)).removeView(view);
        } catch (RuntimeException ex) {
            Log.w("Oriel", "volume popup remove", ex);
        }
        view = null;
    }

    /** Speaker with zero to two waves, or a cross when muted. */
    private static final class Speaker extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path body = new Path();
        private int level;

        Speaker(Context c) {
            super(c);
            p.setColor(Ui.PAPER);
            p.setStrokeWidth(Ui.dp(c, 2));
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
        }

        void set(int l) {
            level = l;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float u = getWidth() / 24f;
            body.reset();
            body.moveTo(11 * u, 5 * u);
            body.lineTo(6 * u, 9 * u);
            body.lineTo(3 * u, 9 * u);
            body.lineTo(3 * u, 15 * u);
            body.lineTo(6 * u, 15 * u);
            body.lineTo(11 * u, 19 * u);
            body.close();
            p.setStyle(Paint.Style.FILL_AND_STROKE);
            c.drawPath(body, p);
            p.setStyle(Paint.Style.STROKE);
            if (level == 0) {
                c.drawLine(16 * u, 9 * u, 22 * u, 15 * u, p);
                c.drawLine(22 * u, 9 * u, 16 * u, 15 * u, p);
                return;
            }
            c.drawArc(10 * u, 7 * u, 18 * u, 17 * u, -50, 100, false, p);
            if (level > 50) c.drawArc(9 * u, 3.5f * u, 23 * u, 20.5f * u, -50, 100, false, p);
        }
    }
}
