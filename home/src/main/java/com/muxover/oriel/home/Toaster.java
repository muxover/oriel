package com.muxover.oriel.home;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

/** Short messages in the Oriel style, bottom centre, over every app. Replaces system toasts. */
final class Toaster {
    private static final long SHOW_MS = 2400;
    private static TextView view;
    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static final Runnable hide = Toaster::remove;

    private Toaster() {}

    static void show(Context c, String text) {
        Context app = c.getApplicationContext();
        Ui.init(app);
        if (view == null) {
            view = Ui.text(app, "", 18, Ui.medium, Ui.PAPER);
            view.setGravity(Gravity.CENTER);
            view.setPadding(Ui.dp(app, 28), 0, Ui.dp(app, 28), 0);
            view.setBackground(Ui.round(Ui.GLASS, Ui.dp(app, Ui.R_PILL)));
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,
                Ui.dp(app, Ui.PILL_H), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            lp.y = Ui.dp(app, 64);
            lp.windowAnimations = android.R.style.Animation_Toast;
            try {
                ((WindowManager) app.getSystemService(Context.WINDOW_SERVICE)).addView(view, lp);
            } catch (RuntimeException ex) {
                Log.e("Oriel", "message could not be shown: " + text, ex);
                view = null;
                return;
            }
        }
        view.setText(text);
        handler.removeCallbacks(hide);
        handler.postDelayed(hide, SHOW_MS);
    }

    private static void remove() {
        if (view == null) return;
        try {
            ((WindowManager) view.getContext().getSystemService(Context.WINDOW_SERVICE)).removeView(view);
        } catch (RuntimeException ex) {
            Log.w("Oriel", "message remove", ex);
        }
        view = null;
    }
}
