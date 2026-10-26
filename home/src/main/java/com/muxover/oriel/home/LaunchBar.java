package com.muxover.oriel.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.widget.FrameLayout;
import android.widget.ImageView;

/**
 * A glowing progress bar that costs nothing to animate: the track and the glowing fill are painted once
 * into pictures, and progress only slides the fill picture sideways inside a clipped frame.
 */
final class LaunchBar extends FrameLayout {
    private final ImageView track;
    private final ImageView fill;
    private float progress;

    LaunchBar(Context c) {
        super(c);
        // The fill slides sideways; clipping children only keeps each child inside its own moved bounds, so the
        // bar clips to its own outline, or the hidden part of the fill shows past the bar's start.
        setClipChildren(true);
        setOutlineProvider(android.view.ViewOutlineProvider.BOUNDS);
        setClipToOutline(true);
        track = new ImageView(c);
        fill = new ImageView(c);
        track.setScaleType(ImageView.ScaleType.MATRIX);
        fill.setScaleType(ImageView.ScaleType.MATRIX);
        addView(track, new LayoutParams(-1, -1));
        addView(fill, new LayoutParams(-1, -1));
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (w <= 0 || h <= 0) return;
        Context c = getContext();
        float pad = Ui.dp(c, 14);
        float bh = Ui.dp(c, 4);
        float top = (h - bh) / 2f;

        Bitmap t = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        tp.setColor(0x33F4F3EF);
        new Canvas(t).drawRoundRect(new RectF(pad, top, w - pad, top + bh), bh / 2, bh / 2, tp);
        track.setImageBitmap(t);

        Bitmap f = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas fc = new Canvas(f);
        RectF bar = new RectF(pad, top, w - pad, top + bh);
        Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        glow.setColor(0x80F4F3EF);
        glow.setMaskFilter(new BlurMaskFilter(Ui.dp(c, 6), BlurMaskFilter.Blur.NORMAL));
        fc.drawRoundRect(bar, bh / 2, bh / 2, glow);
        Paint fp = new Paint(Paint.ANTI_ALIAS_FLAG);
        fp.setShader(new LinearGradient(pad, 0, w - pad, 0, 0x99F4F3EF, 0xFFFFFFFF, Shader.TileMode.CLAMP));
        fc.drawRoundRect(bar, bh / 2, bh / 2, fp);
        Paint head = new Paint(Paint.ANTI_ALIAS_FLAG);
        head.setColor(0xFFFFFFFF);
        head.setMaskFilter(new BlurMaskFilter(Ui.dp(c, 5), BlurMaskFilter.Blur.NORMAL));
        fc.drawCircle(w - pad, top + bh / 2, bh * 1.6f, head);
        head.setMaskFilter(null);
        fc.drawCircle(w - pad, top + bh / 2, bh * 0.9f, head);
        fill.setImageBitmap(f);
        apply();
    }

    void setProgress(float p) {
        progress = Math.max(0f, Math.min(1f, p));
        apply();
    }

    float getProgress() {
        return progress;
    }

    private void apply() {
        float pad = Ui.dp(getContext(), 14);
        float span = getWidth() - pad * 2;
        fill.setTranslationX(-(1f - progress) * span - (progress <= 0f ? pad * 2 : 0));
    }
}
