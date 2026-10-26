package com.muxover.oriel.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/**
 * Mouse mode for the phone remote: a pointer drawn over every app, moved by drags on the phone.
 * A click or scroll is a finger touch injected at the pointer by the root helper.
 */
final class Pointer {
    private static final int SIZE_DP = 26;
    private static final long HIDE_AFTER_MS = 5000;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static View view;
    private static WindowManager.LayoutParams lp;
    private static float x = -1, y = -1;
    private static final Runnable hide = Pointer::hideNow;

    private Pointer() {}

    /** Moves the pointer by phone pixels scaled to the TV; shows it first if needed. Main thread. */
    static void move(Context c, float dx, float dy) {
        if (!show(c)) return;
        Point s = screen(c);
        x = Math.max(0, Math.min(s.x - 1, x + dx));
        y = Math.max(0, Math.min(s.y - 1, y + dy));
        place(c);
    }

    static void click(Context c) {
        if (!show(c)) return;
        Shell.send(c, "tap " + Math.round(x) + " " + Math.round(y));
    }

    /** Scrolls the page under the pointer: positive dy moves the content up, like a finger drag. */
    static void scroll(Context c, float dy) {
        if (!show(c)) return;
        Point s = screen(c);
        float to = Math.max(1, Math.min(s.y - 2, y - dy));
        Shell.send(c, "swipe " + Math.round(x) + " " + Math.round(y) + " " + Math.round(x) + " " + Math.round(to) + " 120");
    }

    private static boolean show(Context c) {
        main.removeCallbacks(hide);
        main.postDelayed(hide, HIDE_AFTER_MS);
        if (view != null) return true;
        Context app = c.getApplicationContext();
        if (!Settings.canDrawOverlays(app)) {
            Log.w("Oriel", "mouse mode needs the draw-over-apps permission");
            return false;
        }
        Point s = screen(app);
        if (x < 0) {
            x = s.x / 2f;
            y = s.y / 2f;
        }
        int px = Ui.dp(app, SIZE_DP);
        view = new Arrow(app);
        lp = new WindowManager.LayoutParams(px, px,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        // At most 80% opaque, so Android still lets the injected touches through to the app below.
        lp.alpha = 0.8f;
        place(app);
        ((WindowManager) app.getSystemService(Context.WINDOW_SERVICE)).addView(view, lp);
        return true;
    }

    private static void place(Context c) {
        if (lp == null) return;
        lp.x = Math.round(x);
        lp.y = Math.round(y);
        if (view != null && view.isAttachedToWindow()) {
            ((WindowManager) c.getApplicationContext().getSystemService(Context.WINDOW_SERVICE)).updateViewLayout(view, lp);
        }
    }

    private static void hideNow() {
        if (view == null) return;
        try {
            ((WindowManager) view.getContext().getSystemService(Context.WINDOW_SERVICE)).removeView(view);
        } catch (IllegalArgumentException ex) {
            Log.w("Oriel", "pointer already gone", ex);
        }
        view = null;
    }

    @SuppressWarnings("deprecation")
    private static Point screen(Context c) {
        Point p = new Point();
        ((WindowManager) c.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealSize(p);
        return p;
    }

    /** A plain arrow with its tip at the top-left corner, white with a dark edge so it shows on any picture. */
    private static final class Arrow extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        Arrow(Context c) {
            super(c);
            fill.setColor(0xFFF4F3EF);
            edge.setColor(0xFF050506);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        protected void onDraw(Canvas c) {
            float s = getWidth() / 24f;
            edge.setStrokeWidth(1.6f * s);
            path.reset();
            path.moveTo(1.5f * s, 1.5f * s);
            path.lineTo(1.5f * s, 19 * s);
            path.lineTo(6.5f * s, 14.5f * s);
            path.lineTo(10 * s, 22 * s);
            path.lineTo(13.5f * s, 20.5f * s);
            path.lineTo(10 * s, 13 * s);
            path.lineTo(16.5f * s, 13 * s);
            path.close();
            c.drawPath(path, fill);
            c.drawPath(path, edge);
        }
    }
}
