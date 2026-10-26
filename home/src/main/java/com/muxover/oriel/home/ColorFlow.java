package com.muxover.oriel.home;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

/**
 * Slow, continuous colour fields, Apple Music style. Each field is a small soft picture painted once and
 * shown very large; animation only moves and scales them, so it runs smoothly on this chip for hours.
 */
final class ColorFlow extends FrameLayout {
    private final ImageView[] blobs = new ImageView[3];
    private final ValueAnimator clock;
    private final View scrim;

    ColorFlow(Context c) {
        super(c);
        setBackgroundColor(Ui.INK);
        setClipChildren(true);
        for (int i = 0; i < 3; i++) {
            blobs[i] = new ImageView(c);
            blobs[i].setScaleType(ImageView.ScaleType.FIT_XY);
            addView(blobs[i], new LayoutParams(Ui.dp(c, 1000), Ui.dp(c, 1000)));
        }
        scrim = new View(c);
        scrim.setBackgroundColor(0x33050506);
        addView(scrim, new LayoutParams(-1, -1));
        addView(Ui.grainView(c), new LayoutParams(-1, -1));
        clock = ValueAnimator.ofFloat(0f, (float) (Math.PI * 2));
        clock.setDuration(40000);
        clock.setRepeatCount(ValueAnimator.INFINITE);
        clock.setInterpolator(new LinearInterpolator());
        clock.addUpdateListener(a -> place((float) a.getAnimatedValue()));
        setColors(new int[]{0xFF3B2A8F, Ui.ACCENT, 0xFF1F6FB2});
    }

    /** Three colours; they cross-fade in so a new song changes the mood gently. */
    void setColors(int[] colors) {
        for (int i = 0; i < 3; i++) {
            final ImageView v = blobs[i];
            final Bitmap b = blob(colors[i % colors.length]);
            final float target = i == 2 ? 0.75f : 0.85f;
            v.animate().alpha(0f).setDuration(500).withEndAction(() -> {
                v.setImageBitmap(b);
                v.animate().alpha(target).setDuration(900).start();
            }).start();
        }
    }

    private void place(float t) {
        int w = getWidth(), h = getHeight();
        if (w == 0) return;
        float s = blobs[0].getWidth();
        blobs[0].setTranslationX(-s * 0.25f + (float) Math.cos(t) * w * 0.12f);
        blobs[0].setTranslationY(-s * 0.35f + (float) Math.sin(t) * h * 0.10f);
        blobs[1].setTranslationX(w - s * 0.70f + (float) Math.sin(t * 1.3f) * w * 0.10f);
        blobs[1].setTranslationY(-s * 0.40f + (float) Math.cos(t * 1.3f) * h * 0.12f);
        blobs[2].setTranslationX(w * 0.30f + (float) Math.cos(t * 0.7f + 1) * w * 0.14f);
        blobs[2].setTranslationY(h - s * 0.45f + (float) Math.sin(t * 0.7f + 1) * h * 0.08f);
        float k = 1f + 0.08f * (float) Math.sin(t * 2);
        blobs[0].setScaleX(k);
        blobs[0].setScaleY(k);
        blobs[1].setScaleX(2f - k);
        blobs[1].setScaleY(2f - k);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        clock.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        clock.cancel();
        super.onDetachedFromWindow();
    }

    private static Bitmap blob(int color) {
        int n = 256;
        Bitmap b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        p.setShader(new RadialGradient(n / 2f, n / 2f, n / 2f,
            new int[]{color, (0x80 << 24) | (color & 0xFFFFFF), 0}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        new Canvas(b).drawCircle(n / 2f, n / 2f, n / 2f, p);
        return b;
    }

    /** Three rich colours from a picture: the most common hues, brightened for the glow. */
    static int[] paletteOf(Bitmap art) {
        if (art == null) return new int[]{0xFF3B2A8F, Ui.ACCENT, 0xFF1F6FB2};
        Bitmap s = Bitmap.createScaledBitmap(art, 24, 24, true);
        int[] bins = new int[12];
        float[][] acc = new float[12][3];
        float[] hsv = new float[3];
        for (int y = 0; y < 24; y++) {
            for (int x = 0; x < 24; x++) {
                int p = s.getPixel(x, y);
                android.graphics.Color.colorToHSV(p, hsv);
                if (hsv[1] < 0.15f || hsv[2] < 0.15f) continue;
                int bin = Math.min(11, (int) (hsv[0] / 30f));
                bins[bin]++;
                acc[bin][0] += hsv[0];
                acc[bin][1] += hsv[1];
                acc[bin][2] += hsv[2];
            }
        }
        int[] out = new int[3];
        int found = 0;
        for (int k = 0; k < 3; k++) {
            int best = -1;
            for (int i = 0; i < 12; i++) if (bins[i] > 0 && (best < 0 || bins[i] > bins[best])) best = i;
            if (best < 0) break;
            float n = bins[best];
            float[] c = {acc[best][0] / n, Math.max(0.55f, acc[best][1] / n), 0.8f};
            out[k] = android.graphics.Color.HSVToColor(c);
            bins[best] = 0;
            found++;
        }
        if (found == 0) return new int[]{0xFF3B2A8F, Ui.ACCENT, 0xFF1F6FB2};
        for (int k = found; k < 3; k++) out[k] = Ui.blend(out[0], Ui.ACCENT, 0.4f * k);
        return out;
    }
}
