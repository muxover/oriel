package com.muxover.oriel.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** The laptop-to-TV drawing on the Mirror top shelf, on a 420 by 260 grid. */
final class MirrorArt extends View {
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wave = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Path p = new Path();

    MirrorArt(Context c) {
        super(c);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeWidth(2);
        fill.setColor(0x0FF4F3EF);
        wave.setStyle(Paint.Style.STROKE);
        wave.setStrokeCap(Paint.Cap.ROUND);
        wave.setStrokeWidth(2);
        wave.setColor(Ui.ACCENT_HI);
        wave.setPathEffect(new DashPathEffect(new float[]{2, 8}, 0));
    }

    @Override
    protected void onDraw(Canvas c) {
        float s = Math.min(getWidth() / 420f, getHeight() / 260f);
        c.save();
        c.scale(s, s);
        line.setColor(0xD9F4F3EF);
        r.set(150, 20, 400, 170);
        c.drawRoundRect(r, 10, 10, line);
        r.set(160, 30, 390, 160);
        c.drawRoundRect(r, 4, 4, fill);
        c.drawLine(240, 192, 310, 192, line);
        c.drawLine(275, 170, 275, 192, line);
        line.setColor(0x99F4F3EF);
        r.set(20, 120, 140, 198);
        c.drawRoundRect(r, 6, 6, line);
        c.drawLine(8, 206, 152, 206, line);
        p.reset();
        p.moveTo(118, 104);
        p.cubicTo(136, 78, 160, 64, 188, 60);
        wave.setAlpha(255);
        c.drawPath(p, wave);
        p.reset();
        p.moveTo(104, 92);
        p.cubicTo(128, 56, 162, 36, 202, 32);
        wave.setAlpha(150);
        c.drawPath(p, wave);
        c.restore();
    }
}
