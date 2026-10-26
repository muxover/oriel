package com.muxover.oriel.home;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

/**
 * The short "Opening <app>" screen. Everything on it is painted once into pictures before it appears;
 * while it shows, only pictures move and fade, which the graphics chip does almost for free even while
 * the app is starting. It fades as soon as the app's own window is on screen.
 */
final class LaunchOverlay {
    private static final String TAG = "Oriel";
    private static final long MIN_SHOW_MS = 700;
    private static final long GIVE_UP_MS = 8000;

    private static LaunchOverlay instance;
    /** The app most recently opened from our screens, for the return animation. */
    static Entry lastOpened;

    static LaunchOverlay get(Context c) {
        if (instance == null) instance = new LaunchOverlay(c);
        return instance;
    }

    /** Opens an app behind the launch screen. */
    static void open(final Activity from, final Entry e, final Intent intent) {
        String p = intent.getComponent() != null ? intent.getComponent().getPackageName() : intent.getPackage();
        final LaunchOverlay lo = get(from);
        final Runnable start = () -> {
            try {
                from.startActivity(intent);
                from.overridePendingTransition(0, 0);
                lastOpened = e;
                if (from instanceof HomeActivity) ((HomeActivity) from).placeReturnVeil(e);
            } catch (RuntimeException ex) {
                Log.e(TAG, "could not open " + intent, ex);
                lo.dismissNow();
                Toaster.show(from, "Could not open " + e.title);
            }
        };
        if (!lo.begin(from, e, p, start)) start.run();
    }

    /** Pictures for one app's launch screen, painted ahead of time. */
    private static final class Art {
        Bitmap center, backdrop, shine;
        int cw, ch, tw, th, labelTop;
    }

    /** Launch art of the apps chosen last; older art is dropped, so memory stays flat however many apps there are. */
    private static final java.util.Map<String, Art> ART = java.util.Collections.synchronizedMap(
        new java.util.LinkedHashMap<String, Art>(8, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(java.util.Map.Entry<String, Art> eldest) {
                return size() > 6;
            }
        });
    private static final java.util.concurrent.ExecutorService PAINTER = java.util.concurrent.Executors.newSingleThreadExecutor();

    /** Paints an app's launch screen in the background, so pressing OK shows it instantly. */
    static void prepare(final Context c, final Entry e) {
        if (ART.containsKey(e.id)) return;
        final Context app = c.getApplicationContext();
        PAINTER.execute(() -> {
            if (ART.containsKey(e.id)) return;
            try {
                ART.put(e.id, paint(app, e));
            } catch (RuntimeException ex) {
                Log.w(TAG, "could not prepare launch screen for " + e.id, ex);
            }
        });
    }

    private static Art paint(Context c, Entry e) {
        Art a = new Art();
        a.backdrop = backdrop(e.tint);
        int fw = Ui.dp(c, 320), fh = Ui.dp(c, 200);
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setTypeface(Ui.semibold);
        text.setTextSize(Ui.dp(c, 30));
        text.setColor(Ui.PAPER);
        String label = "Opening " + e.title;
        a.tw = Math.round(text.measureText(label));
        int pad = Ui.dp(c, 40);
        a.cw = Math.max(fw, a.tw) + pad * 2;
        int gap = Ui.dp(c, 40);
        a.th = Ui.dp(c, 40);
        a.ch = pad + fh + gap + a.th + Ui.dp(c, 8);
        a.center = Bitmap.createBitmap(a.cw, a.ch, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(a.center);
        float fx = (a.cw - fw) / 2f, fy = pad;
        Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        shadow.setColor(0x99000000);
        shadow.setMaskFilter(new BlurMaskFilter(Ui.dp(c, 22), BlurMaskFilter.Blur.NORMAL));
        cv.drawRoundRect(new RectF(fx + Ui.dp(c, 8), fy + Ui.dp(c, 18), fx + fw - Ui.dp(c, 8), fy + fh + Ui.dp(c, 14)), Ui.dp(c, 24), Ui.dp(c, 24), shadow);
        cv.drawBitmap(Tile.faceBitmap(c, e, fw, fh, Ui.dp(c, 24)), fx, fy, null);
        float baseline = fy + fh + gap + Ui.dp(c, 30);
        Paint halo = new Paint(text);
        halo.setColor(0x59F4F3EF);
        halo.setMaskFilter(new BlurMaskFilter(Ui.dp(c, 8), BlurMaskFilter.Blur.NORMAL));
        cv.drawText(label, (a.cw - a.tw) / 2f, baseline, halo);
        cv.drawText(label, (a.cw - a.tw) / 2f, baseline, text);
        a.labelTop = Math.round(fy + fh + gap - Ui.dp(c, 4));
        // The shine lights the letters only: a white copy of the label, shown through a moving soft band.
        a.shine = Bitmap.createBitmap(a.tw + Ui.dp(c, 16), a.th, Bitmap.Config.ARGB_8888);
        Paint glyphs = new Paint(text);
        glyphs.setColor(0xFFFFFFFF);
        new Canvas(a.shine).drawText(label, Ui.dp(c, 8), Ui.dp(c, 34), glyphs);
        return a;
    }

    private final Context ctx;
    private final WindowManager wm;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SharedPreferences times;
    private final DecelerateInterpolator ease = new DecelerateInterpolator(2f);
    private FrameLayout view;
    private LaunchBar bar;
    private View shine;
    private ValueAnimator crawl;
    private ValueAnimator drift;
    private String pkg;
    private long started;
    private boolean finishing;

    private LaunchOverlay(Context c) {
        ctx = c.getApplicationContext();
        wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        times = ctx.getSharedPreferences("launch_times", Context.MODE_PRIVATE);
    }

    /** Shows the screen, starts the app once the screen's first frame is drawn. False if it cannot show. */
    private boolean begin(Context themed, Entry e, String packageName, final Runnable startApp) {
        if (!Settings.canDrawOverlays(ctx) || packageName == null) {
            Log.w(TAG, "launch screen skipped: overlay=" + Settings.canDrawOverlays(ctx) + " pkg=" + packageName);
            return false;
        }
        dismissNow();
        pkg = packageName;
        finishing = false;
        started = SystemClock.uptimeMillis();
        view = build(themed, e);
        // keep our drawing ahead of the app that is loading, so nothing on this screen stutters
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY);
        add(view);

        long expect = Math.max(MIN_SHOW_MS, times.getLong(pkg, 1200));
        crawl = ValueAnimator.ofFloat(0f, 0.9f);
        crawl.setDuration(expect);
        crawl.setInterpolator(ease);
        crawl.addUpdateListener(a -> setProgress((float) a.getAnimatedValue()));
        crawl.start();

        final View shown = view;
        shown.getViewTreeObserver().addOnDrawListener(new ViewTreeObserver.OnDrawListener() {
            private boolean done;

            @Override
            public void onDraw() {
                if (done) return;
                done = true;
                // our screen is up; now the app may take the processor
                shown.post(() -> {
                    started = SystemClock.uptimeMillis();
                    startApp.run();
                });
            }
        });
        handler.postDelayed(watch, 100);
        return true;
    }

    private final Runnable watch = new Runnable() {
        @Override
        public void run() {
            if (view == null || finishing) return;
            long waited = SystemClock.uptimeMillis() - started;
            boolean up = WindowWatcher.lastAt > started && pkg.equals(WindowWatcher.lastPackage);
            if ((up && waited >= MIN_SHOW_MS) || waited > GIVE_UP_MS) {
                finishing = true;
                if (up) times.edit().putLong(pkg, waited).apply();
                complete();
                return;
            }
            handler.postDelayed(this, 60);
        }
    };

    private void setProgress(float p) {
        if (bar != null) bar.setProgress(p);
        if (shine != null) ((Shine) shine).setProgress(p);
    }

    private void complete() {
        if (crawl != null) crawl.cancel();
        float from = bar != null ? bar.getProgress() : 0f;
        ValueAnimator end = ValueAnimator.ofFloat(from, 1f);
        end.setDuration(180);
        end.setInterpolator(ease);
        end.addUpdateListener(a -> setProgress((float) a.getAnimatedValue()));
        end.start();
        final View v = view;
        // removing the window hands the fade-out to the system's window animator, which keeps running
        // smoothly even while the app is busy loading
        handler.postDelayed(() -> {
            if (v != null && v == view) remove(v);
        }, 200);
    }

    void dismissNow() {
        handler.removeCallbacksAndMessages(null);
        if (crawl != null) crawl.cancel();
        if (view != null) remove(view);
    }

    private void add(View v) {
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.windowAnimations = android.R.style.Animation_Toast;
        wm.addView(v, lp);
    }

    private void remove(View v) {
        if (drift != null) {
            drift.cancel();
            drift = null;
        }
        try {
            wm.removeView(v);
        } catch (IllegalArgumentException ex) {
            Log.w(TAG, "launch screen already gone", ex);
        }
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT);
        if (v == view) {
            view = null;
            bar = null;
            shine = null;
        }
    }

    private FrameLayout build(Context c, Entry e) {
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        int sw = dm.widthPixels, sh = dm.heightPixels;
        FrameLayout root = new FrameLayout(c);
        root.setBackgroundColor(Ui.INK);

        Art art = ART.get(e.id);
        if (art == null) {
            art = paint(c, e);
            ART.put(e.id, art);
        }

        // backdrop: two glows in the app's colour, painted small and shown enlarged (soft by nature),
        // drifting slowly; moving a picture costs the processor nothing
        ImageView glow = new ImageView(c);
        glow.setScaleType(ImageView.ScaleType.FIT_XY);
        glow.setImageBitmap(art.backdrop);
        int gw = Math.round(sw * 1.2f), gh = Math.round(sh * 1.2f);
        root.addView(glow, new FrameLayout.LayoutParams(gw, gh));
        glow.setTranslationX(-(gw - sw) / 2f);
        glow.setTranslationY(-(gh - sh) / 2f);
        final float bx = glow.getTranslationX(), by = glow.getTranslationY();
        final float ax = (gw - sw) / 2f * 0.8f, ay = (gh - sh) / 2f * 0.8f;
        drift = ValueAnimator.ofFloat(0f, (float) (Math.PI * 2));
        drift.setDuration(12000);
        drift.setRepeatCount(ValueAnimator.INFINITE);
        drift.setInterpolator(new LinearInterpolator());
        final ImageView g = glow;
        drift.addUpdateListener(an -> {
            float t = (float) an.getAnimatedValue();
            g.setTranslationX(bx + (float) Math.cos(t) * ax);
            g.setTranslationY(by + (float) Math.sin(t) * ay);
        });
        drift.start();
        root.addView(Ui.grainView(c), new FrameLayout.LayoutParams(-1, -1));

        FrameLayout centerBox = new FrameLayout(c);
        ImageView centerImg = new ImageView(c);
        centerImg.setImageBitmap(art.center);
        centerBox.addView(centerImg, new FrameLayout.LayoutParams(art.cw, art.ch));

        // shine: a soft light sliding across the label's letters in step with the bar
        FrameLayout.LayoutParams sbp = new FrameLayout.LayoutParams(art.shine.getWidth(), art.th);
        sbp.leftMargin = (art.cw - art.tw) / 2 - Ui.dp(c, 8);
        sbp.topMargin = art.labelTop;
        shine = new Shine(c, art.shine, Ui.dp(c, 120));
        centerBox.addView(shine, sbp);

        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(art.cw, art.ch, Gravity.CENTER);
        cp.topMargin = -Ui.dp(c, 24);
        root.addView(centerBox, cp);

        bar = new LaunchBar(c);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(Ui.dp(c, 360), Ui.dp(c, 32), Gravity.CENTER);
        bp.topMargin = art.ch / 2 - Ui.dp(c, 8);
        root.addView(bar, bp);

        // the tile settles in from slightly small: a transform, so it is free
        centerBox.setScaleX(0.94f);
        centerBox.setScaleY(0.94f);
        centerBox.animate().scaleX(1f).scaleY(1f).setDuration(380).setInterpolator(ease).start();
        return root;
    }

    /** Two soft glows in the app's colour on ink, painted at low resolution; enlarging keeps them smooth. */
    private static Bitmap backdrop(int tint) {
        int w = 192, h = 108;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(Ui.INK);
        float[] hsv = new float[3];
        Color.colorToHSV(tint, hsv);
        hsv[1] = Math.max(hsv[1], 0.6f);
        hsv[2] = 0.75f;
        int vivid = Color.HSVToColor(hsv);
        int second = Ui.blend(vivid, Ui.ACCENT, 0.4f);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        p.setShader(new RadialGradient(w * 0.28f, h * 0.32f, w * 0.5f, (0x99 << 24) | (vivid & 0xFFFFFF), 0, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(new RadialGradient(w * 0.76f, h * 0.72f, w * 0.45f, (0x70 << 24) | (second & 0xFFFFFF), 0, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        return b;
    }

    /**
     * The label's letters lit by a soft band of light that crosses them as the bar fills. Only the letters
     * light up: the band is masked by the glyphs, so no box or patch shows around the text.
     */
    private static final class Shine extends View {
        private final Bitmap glyphs;
        private final int band;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final android.graphics.BitmapShader mask;
        private float progress;

        Shine(Context c, Bitmap glyphs, int band) {
            super(c);
            this.glyphs = glyphs;
            this.band = band;
            mask = new android.graphics.BitmapShader(glyphs, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        }

        void setProgress(float p) {
            progress = p;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float x = -band + (getWidth() + band) * progress;
            android.graphics.LinearGradient light = new android.graphics.LinearGradient(x, 0, x + band, 0,
                new int[]{0x00FFFFFF, 0xB3FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP);
            paint.setShader(new android.graphics.ComposeShader(mask, light, android.graphics.PorterDuff.Mode.DST_IN));
            c.drawRect(0, 0, glyphs.getWidth(), glyphs.getHeight(), paint);
        }
    }
}
