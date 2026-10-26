package com.muxover.oriel.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Small drawn glyphs for Settings rows: an on/off switch and a Wi-Fi signal with an optional lock. */
final class Glyphs {
    private Glyphs() {}

    static final class Switch extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final boolean on;

        Switch(Context c, boolean on) {
            super(c);
            this.on = on;
        }

        @Override
        protected void onDraw(Canvas c) {
            float h = getHeight(), w = getWidth();
            r.set(0, 0, w, h);
            p.setColor(on ? Ui.ACCENT : 0xFF3A3A40);
            c.drawRoundRect(r, h / 2, h / 2, p);
            float k = h - 6 * getResources().getDisplayMetrics().density / 1.5f;
            float pad = (h - k) / 2;
            p.setColor(Ui.PAPER);
            float cx = on ? w - pad - k / 2 : pad + k / 2;
            c.drawCircle(cx, h / 2, k / 2, p);
        }
    }

    /** A round colour sample; ringed when it is the chosen one. */
    static final class Dot extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int color;
        private final boolean on;

        Dot(Context c, int color, boolean on) {
            super(c);
            this.color = color;
            this.on = on;
        }

        @Override
        protected void onDraw(Canvas c) {
            float r = Math.min(getWidth(), getHeight()) / 2f, d = getResources().getDisplayMetrics().density;
            p.setStyle(Paint.Style.FILL);
            p.setColor(color);
            c.drawCircle(getWidth() / 2f, getHeight() / 2f, on ? r - 5 * d : r - 2 * d, p);
            if (!on) return;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2 * d);
            c.drawCircle(getWidth() / 2f, getHeight() / 2f, r - d, p);
        }
    }

    static final class Signal extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final int level;
        private final boolean locked;
        private int color = Ui.MUTED;

        /** level 0 to 3. */
        Signal(Context c, int level, boolean locked) {
            super(c);
            this.level = level;
            this.locked = locked;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
        }

        void setColor(int c) {
            color = c;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float u = getHeight() / 24f;
            p.setStrokeWidth(2 * u);
            float x0 = locked ? 0 : -24 * u;
            if (locked) {
                p.setColor(color);
                p.setStyle(Paint.Style.FILL);
                r.set(5 * u, 11 * u, 17 * u, 20 * u);
                c.drawRoundRect(r, 2 * u, 2 * u, p);
                p.setStyle(Paint.Style.STROKE);
                r.set(7.5f * u, 5 * u, 14.5f * u, 14 * u);
                c.drawArc(r, 180, 180, false, p);
                c.drawLine(7.5f * u, 9.5f * u, 7.5f * u, 11 * u, p);
                c.drawLine(14.5f * u, 9.5f * u, 14.5f * u, 11 * u, p);
            }
            float ox = x0 + 32 * u, oy = 20 * u;
            for (int i = 0; i < 3; i++) {
                float rad = (5 + i * 5) * u;
                p.setColor(i < level ? color : (color & 0x00FFFFFF) | 0x4D000000);
                r.set(ox - rad, oy - rad, ox + rad, oy + rad);
                c.drawArc(r, 225, 90, false, p);
            }
            p.setStyle(Paint.Style.FILL);
            p.setColor(color);
            c.drawCircle(ox, oy - 0.5f * u, 1.6f * u, p);
            p.setStyle(Paint.Style.STROKE);
        }
    }

    /** A radio mark: an empty ring, or a violet ring with a dot when chosen. */
    static final class Radio extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final boolean on;

        Radio(Context c, boolean on) {
            super(c);
            this.on = on;
            p.setStrokeWidth(2 * c.getResources().getDisplayMetrics().density);
        }

        @Override
        protected void onDraw(Canvas c) {
            float r = Math.min(getWidth(), getHeight()) / 2f - p.getStrokeWidth();
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            p.setStyle(Paint.Style.STROKE);
            p.setColor(on ? Ui.ACCENT : Ui.MUTED);
            c.drawCircle(cx, cy, r, p);
            if (!on) return;
            p.setStyle(Paint.Style.FILL);
            c.drawCircle(cx, cy, r * 0.5f, p);
        }
    }
}
