package com.muxover.oriel.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Bold line icons drawn in code on a 24-unit grid, so they stay sharp at any size. */
final class IconView extends View {
    static final int MIRROR = 0, HDMI = 1, MEDIA = 2, SETTINGS = 3, APPS = 4, PLAY = 5, PAUSE = 6, PREV = 7, NEXT = 8, UPDATES = 9,
        FOLDER = 10, MUSIC = 11, PHOTO = 12, USB = 13, TV = 14, SHUFFLE = 15, REPEAT = 16, REPEAT_ONE = 17;

    /** Tile colours per built-in icon: top-left and bottom-right of the gradient. */
    static int[] colors(int kind) {
        switch (kind) {
            case MIRROR: return new int[]{0xFF7C4DFF, 0xFF4A1FB8};
            case HDMI: return new int[]{0xFF2F80ED, 0xFF14407A};
            case MEDIA: return new int[]{0xFFF59E0B, 0xFFB45309};
            case SETTINGS: return new int[]{0xFF6B6B74, 0xFF2E2E34};
            case UPDATES: return new int[]{0xFF10B981, 0xFF047857};
            default: return new int[]{0xFF3F3F46, 0xFF1F1F23};
        }
    }

    private int kind;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private float stroke = 2f;

    IconView(Context c, int kind, int color) {
        super(c);
        this.kind = kind;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(color);
    }

    void setKind(int k) {
        kind = k;
        invalidate();
    }

    void setColor(int color) {
        paint.setColor(color);
        invalidate();
    }

    void setStroke(float units) {
        stroke = units;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float s = Math.min(getWidth(), getHeight()) / 24f;
        c.save();
        c.translate((getWidth() - 24 * s) / 2f, (getHeight() - 24 * s) / 2f);
        c.scale(s, s);
        paint.setStrokeWidth(stroke);
        paint.setStyle(Paint.Style.STROKE);
        path.reset();
        switch (kind) {
            case MIRROR:
                rect.set(2.5f, 4, 21.5f, 16.5f);
                c.drawRoundRect(rect, 2.5f, 2.5f, paint);
                c.drawLine(8, 20.5f, 16, 20.5f, paint);
                c.drawLine(12, 16.5f, 12, 20.5f, paint);
                rect.set(6.5f, 8, 16.5f, 18);
                c.drawArc(rect, 180, 90, false, paint);
                paint.setStyle(Paint.Style.FILL);
                c.drawCircle(7.5f, 13, 1.4f, paint);
                break;
            case HDMI:
                path.moveTo(2.5f, 8);
                path.lineTo(21.5f, 8);
                path.lineTo(21.5f, 12.5f);
                path.lineTo(18.5f, 16);
                path.lineTo(5.5f, 16);
                path.lineTo(2.5f, 12.5f);
                path.close();
                c.drawPath(path, paint);
                c.drawLine(7, 11.5f, 17, 11.5f, paint);
                break;
            case MEDIA:
                path.moveTo(9, 6.5f);
                path.lineTo(18.5f, 12);
                path.lineTo(9, 17.5f);
                path.close();
                paint.setStyle(Paint.Style.FILL);
                c.drawPath(path, paint);
                paint.setStyle(Paint.Style.STROKE);
                c.drawCircle(12, 12, 10, paint);
                break;
            case SETTINGS:
                c.drawLine(4, 7, 20, 7, paint);
                c.drawLine(4, 12, 20, 12, paint);
                c.drawLine(4, 17, 20, 17, paint);
                paint.setStyle(Paint.Style.FILL);
                c.drawCircle(9, 7, 2.6f, paint);
                c.drawCircle(15, 12, 2.6f, paint);
                c.drawCircle(8, 17, 2.6f, paint);
                break;
            case UPDATES:
                // a down arrow into a tray
                c.drawLine(12, 3.5f, 12, 14.5f, paint);
                path.moveTo(7.5f, 10);
                path.lineTo(12, 14.5f);
                path.lineTo(16.5f, 10);
                c.drawPath(path, paint);
                path.reset();
                path.moveTo(4, 15.5f);
                path.lineTo(4, 19.5f);
                path.lineTo(20, 19.5f);
                path.lineTo(20, 15.5f);
                c.drawPath(path, paint);
                break;
            case APPS:
                paint.setStyle(Paint.Style.FILL);
                for (int i = 0; i < 3; i++) {
                    for (int j = 0; j < 3; j++) {
                        rect.set(3.5f + i * 6.5f, 3.5f + j * 6.5f, 7.5f + i * 6.5f, 7.5f + j * 6.5f);
                        c.drawRoundRect(rect, 1.2f, 1.2f, paint);
                    }
                }
                break;
            case PLAY:
                paint.setStyle(Paint.Style.FILL);
                path.moveTo(7, 4.5f); path.lineTo(19.5f, 12); path.lineTo(7, 19.5f); path.close();
                c.drawPath(path, paint);
                break;
            case PAUSE:
                paint.setStyle(Paint.Style.FILL);
                rect.set(6, 4.5f, 10, 19.5f);
                c.drawRoundRect(rect, 1, 1, paint);
                rect.set(14, 4.5f, 18, 19.5f);
                c.drawRoundRect(rect, 1, 1, paint);
                break;
            case PREV:
                paint.setStyle(Paint.Style.FILL);
                path.moveTo(12, 6); path.lineTo(12, 18); path.lineTo(3, 12); path.close();
                path.moveTo(21, 6); path.lineTo(21, 18); path.lineTo(12, 12); path.close();
                c.drawPath(path, paint);
                break;
            case NEXT:
                paint.setStyle(Paint.Style.FILL);
                path.moveTo(3, 6); path.lineTo(3, 18); path.lineTo(12, 12); path.close();
                path.moveTo(12, 6); path.lineTo(12, 18); path.lineTo(21, 12); path.close();
                c.drawPath(path, paint);
                break;
            case FOLDER:
                path.moveTo(2.5f, 7);
                path.lineTo(2.5f, 18.5f);
                path.lineTo(21.5f, 18.5f);
                path.lineTo(21.5f, 8.5f);
                path.lineTo(11.5f, 8.5f);
                path.lineTo(9.5f, 5.5f);
                path.lineTo(4, 5.5f);
                path.close();
                c.drawPath(path, paint);
                break;
            case MUSIC:
                c.drawLine(9, 17, 9, 5, paint);
                c.drawLine(9, 5, 19, 3.5f, paint);
                c.drawLine(19, 3.5f, 19, 15.5f, paint);
                paint.setStyle(Paint.Style.FILL);
                c.drawCircle(6.5f, 17.5f, 3, paint);
                c.drawCircle(16.5f, 16, 3, paint);
                break;
            case PHOTO:
                rect.set(2.5f, 4.5f, 21.5f, 19.5f);
                c.drawRoundRect(rect, 2.5f, 2.5f, paint);
                path.moveTo(3, 17);
                path.lineTo(9, 11);
                path.lineTo(14, 16);
                path.lineTo(16.5f, 13.5f);
                path.lineTo(21, 18);
                c.drawPath(path, paint);
                paint.setStyle(Paint.Style.FILL);
                c.drawCircle(16, 9, 1.6f, paint);
                break;
            case USB:
                rect.set(7, 9, 17, 21.5f);
                c.drawRoundRect(rect, 2, 2, paint);
                rect.set(9, 2.5f, 15, 9);
                c.drawRect(rect, paint);
                break;
            case TV:
                rect.set(2.5f, 4.5f, 21.5f, 16.5f);
                c.drawRoundRect(rect, 2, 2, paint);
                c.drawLine(8, 20, 16, 20, paint);
                break;
            case SHUFFLE:
                path.moveTo(3, 7); path.cubicTo(11, 7, 12, 17, 20, 17);
                path.moveTo(3, 17); path.cubicTo(11, 17, 12, 7, 20, 7);
                path.moveTo(17, 4); path.lineTo(20.5f, 7); path.lineTo(17, 10);
                path.moveTo(17, 14); path.lineTo(20.5f, 17); path.lineTo(17, 20);
                c.drawPath(path, paint);
                break;
            case REPEAT:
            case REPEAT_ONE:
                path.moveTo(4, 11); path.lineTo(4, 9); path.quadTo(4, 6.5f, 6.5f, 6.5f); path.lineTo(19.5f, 6.5f);
                path.moveTo(16.5f, 3.5f); path.lineTo(19.5f, 6.5f); path.lineTo(16.5f, 9.5f);
                path.moveTo(20, 13); path.lineTo(20, 15); path.quadTo(20, 17.5f, 17.5f, 17.5f); path.lineTo(4.5f, 17.5f);
                path.moveTo(7.5f, 14.5f); path.lineTo(4.5f, 17.5f); path.lineTo(7.5f, 20.5f);
                c.drawPath(path, paint);
                if (kind == REPEAT_ONE) {
                    paint.setStyle(Paint.Style.FILL);
                    c.drawCircle(12, 12, 2, paint);
                }
                break;
            default:
                break;
        }
        c.restore();
    }
}
