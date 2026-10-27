package com.muxover.oriel.home;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Photos, full screen: left and right go through the folder, OK starts or stops a slideshow. Also opens what
 * the phone remote sends: a photo here, a video in the player, a song in the music player.
 */
public class ViewerActivity extends Activity {
    private static final long SLIDE_MS = 6000;

    /** A file sent from the phone remote. */
    static void show(Context c, File f, boolean video) {
        if (video) {
            ArrayList<String> one = new ArrayList<>();
            one.add(f.getAbsolutePath());
            PlayerActivity.play(c, one, 0);
            return;
        }
        ArrayList<String> one = new ArrayList<>();
        one.add(f.getAbsolutePath());
        showAll(c, one, 0);
    }

    /** A song sent from the phone remote, shown with its name. */
    static void play(Context c, File f, String title) {
        ArrayList<String> one = new ArrayList<>();
        one.add(f.getAbsolutePath());
        MusicActivity.play(c, one, 0, title);
    }

    static void showAll(Context c, ArrayList<String> paths, int index) {
        c.startActivity(new Intent(c, ViewerActivity.class)
            .putStringArrayListExtra("paths", paths)
            .putExtra("index", index)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private FrameLayout root;
    private ImageView front, back;
    private TextView info;
    private ArrayList<String> paths;
    private int index;
    private boolean slideshow;
    private int width, height;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        back = image();
        front = image();
        info = Ui.text(this, "", 16, Ui.medium, Ui.PAPER);
        info.setGravity(Gravity.CENTER);
        info.setPadding(Ui.dp(this, 24), 0, Ui.dp(this, 24), 0);
        info.setBackground(Ui.round(Ui.GLASS, Ui.dp(this, 22)));
        info.setSingleLine(true);
        info.setAlpha(0f);
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(-2, Ui.dp(this, 44), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        ip.bottomMargin = Ui.dp(this, 40);
        root.addView(info, ip);
        setContentView(root);
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        width = Math.max(dm.widthPixels, 1920);
        height = Math.max(dm.heightPixels, 1080);
        load(getIntent());
    }

    private ImageView image() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(v, new FrameLayout.LayoutParams(-1, -1));
        return v;
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        load(i);
    }

    private void load(Intent i) {
        paths = i.getStringArrayListExtra("paths");
        if (paths == null || paths.isEmpty()) {
            finish();
            return;
        }
        index = Math.max(0, Math.min(i.getIntExtra("index", 0), paths.size() - 1));
        showPhoto(true);
    }

    private void showPhoto(boolean withInfo) {
        final int want = index;
        final String path = paths.get(want);
        decoder.execute(() -> {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= width || o.outHeight / (sample * 2) >= height) sample *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = sample;
            final Bitmap b = BitmapFactory.decodeFile(path, o);
            handler.post(() -> {
                if (want != index || isDestroyed()) return;
                if (b == null) {
                    Log.e("Oriel", "photo could not be read: " + path);
                    Toaster.show(this, "This photo cannot be shown");
                    return;
                }
                // cross-fade: the new photo comes in over the old one
                ImageView in = back;
                back = front;
                front = in;
                in.setImageBitmap(b);
                in.setAlpha(0f);
                in.bringToFront();
                info.bringToFront();
                in.animate().alpha(1f).setDuration(slideshow ? 900 : 260).start();
                if (withInfo) flashInfo();
            });
        });
    }

    private void flashInfo() {
        String name = new File(paths.get(index)).getName();
        String where = paths.size() > 1 ? (index + 1) + " of " + paths.size() + "   ·   " : "";
        info.setText(where + MediaFiles.title(name) + (slideshow ? "   ·   Slideshow" : paths.size() > 1 ? "   ·   OK for a slideshow" : ""));
        info.animate().alpha(1f).setDuration(Ui.FOCUS_MS).start();
        handler.removeCallbacks(hideInfo);
        handler.postDelayed(hideInfo, 3000);
    }

    private final Runnable hideInfo = () -> info.animate().alpha(0f).setDuration(Ui.ENTER_MS).start();

    private final Runnable slide = new Runnable() {
        @Override
        public void run() {
            if (!slideshow) return;
            index = (index + 1) % paths.size();
            showPhoto(false);
            handler.postDelayed(this, SLIDE_MS);
        }
    };

    private void step(int by) {
        if (paths.size() < 2) return;
        index = (index + by + paths.size()) % paths.size();
        showPhoto(true);
        if (slideshow) {
            handler.removeCallbacks(slide);
            handler.postDelayed(slide, SLIDE_MS);
        }
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                if (e.getRepeatCount() % 3 == 0) step(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                if (e.getRepeatCount() % 3 == 0) step(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (e.getRepeatCount() > 0 || paths.size() < 2) return true;
                slideshow = !slideshow;
                handler.removeCallbacks(slide);
                if (slideshow) handler.postDelayed(slide, SLIDE_MS);
                flashInfo();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                flashInfo();
                return true;
            default:
                return super.onKeyDown(code, e);
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        decoder.shutdownNow();
        super.onDestroy();
    }
}
