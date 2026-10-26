package com.muxover.oriel.home;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import java.util.ArrayList;
import java.util.List;

/**
 * The Oriel keyboard, drawn as one view: keys on a grid of units, a lit key that glides between keys.
 * The remote moves the lit key; OK presses it.
 */
final class KeyboardView extends View {
    interface Listener {
        void onKey(Key k);
    }

    static final int CHAR = 0, SHIFT = 1, DEL = 2, MODE = 3, LANG = 4, SPACE = 5, DONE = 6;

    static final class Key {
        final String label;
        final String text;
        final int kind;
        final float units;
        final RectF box = new RectF();

        Key(String label, String text, int kind, float units) {
            this.label = label;
            this.text = text;
            this.kind = kind;
            this.units = units;
        }
    }

    private static final String[][] EN = {
        {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"},
        {"q", "w", "e", "r", "t", "y", "u", "i", "o", "p"},
        {"a", "s", "d", "f", "g", "h", "j", "k", "l", "'"},
        {"z", "x", "c", "v", "b", "n", "m", ",", ".", "-"},
    };
    private static final String[][] AR = {
        {"١", "٢", "٣", "٤", "٥", "٦", "٧", "٨", "٩", "٠", "؟"},
        {"ض", "ص", "ث", "ق", "ف", "غ", "ع", "ه", "خ", "ح", "ج"},
        {"ش", "س", "ي", "ب", "ل", "ا", "ت", "ن", "م", "ك", "ط"},
        {"ئ", "ء", "ؤ", "ر", "لا", "ى", "ة", "و", "ز", "ظ", "د"},
    };
    private static final String[][] SYM = {
        {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"},
        {"@", "#", "$", "%", "&", "*", "(", ")", "+", "="},
        {"!", "?", "/", "\\", ":", ";", "\"", "'", "_", "~"},
        {"[", "]", "{", "}", "<", ">", "^", "|", ",", "."},
    };

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF lit = new RectF();
    private final RectF from = new RectF();
    private final List<List<Key>> rows = new ArrayList<>();
    private final ValueAnimator glide = ValueAnimator.ofFloat(0f, 1f);
    private Listener listener;
    private int row = 1, col = 0;
    private boolean shift, symbols, arabic;
    private String doneLabel = "Done";
    private boolean email;

    KeyboardView(Context c) {
        super(c);
        Ui.init(c);
        text.setTextAlign(Paint.Align.CENTER);
        icon.setStyle(Paint.Style.STROKE);
        icon.setStrokeCap(Paint.Cap.ROUND);
        icon.setStrokeJoin(Paint.Join.ROUND);
        icon.setStrokeWidth(Ui.dp(c, 2));
        glide.setDuration(140);
        glide.setInterpolator(new DecelerateInterpolator(2f));
        glide.addUpdateListener(a -> invalidate());
        build();
    }

    void setListener(Listener l) {
        listener = l;
    }

    /** Called for each new text field: its action label and whether it wants an email or link. */
    void reset(String done, boolean emailOrLink) {
        doneLabel = done;
        email = emailOrLink;
        shift = false;
        symbols = false;
        build();
        row = 1;
        col = 0;
        place(false);
    }

    boolean shifted() {
        return shift;
    }

    void unshift() {
        if (!shift) return;
        shift = false;
        invalidate();
    }

    private void build() {
        rows.clear();
        String[][] grid = symbols ? SYM : arabic ? AR : EN;
        for (int r = 0; r < grid.length; r++) {
            List<Key> keys = new ArrayList<>();
            if (r == 3 && !symbols && !arabic) keys.add(new Key("shift", null, SHIFT, 1.5f));
            for (String s : grid[r]) keys.add(new Key(s, s, CHAR, 1f));
            if (r == 3) keys.add(new Key("del", null, DEL, 1.5f));
            rows.add(keys);
        }
        List<Key> last = new ArrayList<>();
        last.add(new Key(symbols ? (arabic ? "أ ب" : "ABC") : "?123", null, MODE, 1.5f));
        last.add(new Key(arabic ? "EN" : "ع", null, LANG, 1f));
        if (email) last.add(new Key("@", "@", CHAR, 1f));
        last.add(new Key(arabic ? "مسافة" : "space", " ", SPACE, email ? 4f : 5f));
        if (email) last.add(new Key(".com", ".com", CHAR, 1.5f));
        last.add(new Key(doneLabel, null, DONE, email ? 2.5f : 3f));
        rows.add(last);
        requestLayout();
        layoutKeys();
    }

    private float unit() {
        int most = 0;
        for (List<Key> r : rows) {
            float u = 0;
            for (Key k : r) u += k.units;
            most = Math.max(most, (int) Math.ceil(u));
        }
        float gap = Ui.dp(getContext(), 8);
        float avail = Math.min(getWidth(), Ui.dp(getContext(), 960)) - gap;
        return most == 0 ? 0 : avail / most;
    }

    private void layoutKeys() {
        if (getWidth() == 0) return;
        float u = unit();
        float gap = Ui.dp(getContext(), 8);
        float h = Ui.dp(getContext(), 52);
        float top = Ui.dp(getContext(), 24);
        for (int r = 0; r < rows.size(); r++) {
            List<Key> keys = rows.get(r);
            float total = 0;
            for (Key k : keys) total += k.units * u;
            float x = (getWidth() - total) / 2f + gap / 2f;
            if (arabic && r < 4) {
                // right to left: first key on the right
                x = (getWidth() + total) / 2f - gap / 2f;
                for (Key k : keys) {
                    float w = k.units * u;
                    k.box.set(x - w + gap, top + r * (h + gap), x, top + r * (h + gap) + h);
                    x -= w;
                }
                continue;
            }
            for (Key k : keys) {
                float w = k.units * u;
                k.box.set(x, top + r * (h + gap), x + w - gap, top + r * (h + gap) + h);
                x += w;
            }
        }
    }

    @Override
    protected void onMeasure(int w, int h) {
        int height = Ui.dp(getContext(), 24 + 5 * 52 + 4 * 8 + 32);
        setMeasuredDimension(MeasureSpec.getSize(w), height);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        layoutKeys();
        place(false);
    }

    /** Moves the lit key: dr, dc in -1..1. Returns false at an edge it cannot pass. */
    boolean move(int dr, int dc) {
        if (dc != 0) {
            List<Key> r = rows.get(row);
            int step = arabic && row < 4 ? -dc : dc;
            int c = col + step;
            if (c < 0 || c >= r.size()) return false;
            col = c;
        } else {
            int nr = row + dr;
            if (nr < 0 || nr >= rows.size()) return false;
            float cx = rows.get(row).get(col).box.centerX();
            row = nr;
            col = nearest(rows.get(row), cx);
        }
        place(true);
        return true;
    }

    private static int nearest(List<Key> r, float cx) {
        int best = 0;
        float d = Float.MAX_VALUE;
        for (int i = 0; i < r.size(); i++) {
            float dd = Math.abs(r.get(i).box.centerX() - cx);
            if (dd < d) {
                d = dd;
                best = i;
            }
        }
        return best;
    }

    private void place(boolean animate) {
        if (rows.isEmpty()) return;
        row = Math.min(row, rows.size() - 1);
        col = Math.min(col, rows.get(row).size() - 1);
        current(from);
        if (!animate) from.set(rows.get(row).get(col).box);
        glide.cancel();
        glide.start();
    }

    private void current(RectF out) {
        out.set(lit.isEmpty() ? rows.get(row).get(col).box : lit);
    }

    void press() {
        Key k = rows.get(row).get(col);
        switch (k.kind) {
            case SHIFT:
                shift = !shift;
                invalidate();
                return;
            case MODE:
                symbols = !symbols;
                rebuildKeep();
                return;
            case LANG:
                arabic = !arabic;
                symbols = false;
                shift = false;
                rebuildKeep();
                return;
            default:
                if (listener != null) listener.onKey(k);
        }
    }

    private void rebuildKeep() {
        int r = row;
        build();
        row = r;
        col = nearest(rows.get(Math.min(r, rows.size() - 1)), getWidth() / 2f);
        if (r == rows.size() - 1) {
            for (int i = 0; i < rows.get(r).size(); i++) {
                int kind = rows.get(r).get(i).kind;
                if (kind == MODE || kind == LANG) col = i;
            }
        }
        lit.setEmpty();
        place(false);
    }

    @Override
    protected void onDraw(Canvas c) {
        float radius = Ui.dp(getContext(), 24);
        fill.setColor(0xF20A0A0B);
        path.reset();
        path.addRoundRect(0, 0, getWidth(), getHeight() + radius, radius, radius, Path.Direction.CW);
        c.drawPath(path, fill);

        Key target = rows.get(row).get(col);
        float t = glide.isRunning() ? (float) glide.getAnimatedValue() : 1f;
        lit.set(from.left + (target.box.left - from.left) * t, from.top + (target.box.top - from.top) * t,
            from.right + (target.box.right - from.right) * t, from.bottom + (target.box.bottom - from.bottom) * t);

        float kr = Ui.dp(getContext(), 12);
        for (List<Key> r : rows) {
            for (Key k : r) {
                boolean special = k.kind != CHAR && k.kind != SPACE;
                fill.setColor(k.kind == DONE ? Ui.ACCENT : special ? Ui.LINE : Ui.TILE);
                if (k.kind == SHIFT && shift) fill.setColor(Ui.TEXT2);
                c.drawRoundRect(k.box, kr, kr, fill);
            }
        }
        float grow = Ui.dp(getContext(), 3);
        fill.setColor(Ui.PAPER);
        c.drawRoundRect(lit.left - grow, lit.top - grow, lit.right + grow, lit.bottom + grow, kr + grow, kr + grow, fill);

        for (List<Key> r : rows) {
            for (Key k : r) {
                boolean on = k == target && t > 0.5f;
                int color = on ? Ui.INK : k.kind == DONE ? Ui.INK : Ui.PAPER;
                if (k.kind == SHIFT) {
                    drawShift(c, k.box, on || shift ? Ui.INK : Ui.PAPER);
                } else if (k.kind == DEL) {
                    drawDel(c, k.box, on ? Ui.INK : Ui.PAPER);
                } else {
                    boolean word = k.label.length() > 2 && k.kind != CHAR;
                    text.setTypeface(word ? Ui.medium : Ui.regular);
                    text.setTextSize(Ui.dp(getContext(), word ? 16 : 22));
                    text.setColor(color);
                    String s = k.kind == CHAR && shift && !symbols ? k.label.toUpperCase() : k.label;
                    float y = k.box.centerY() - (text.descent() + text.ascent()) / 2f;
                    c.drawText(s, k.box.centerX(), y, text);
                }
            }
        }
        if (glide.isRunning()) invalidate();
    }

    private void drawShift(Canvas c, RectF b, int color) {
        float s = Ui.dp(getContext(), 1);
        float cx = b.centerX(), cy = b.centerY();
        icon.setColor(color);
        path.reset();
        path.moveTo(cx, cy - 9 * s);
        path.lineTo(cx + 8 * s, cy);
        path.lineTo(cx + 4 * s, cy);
        path.lineTo(cx + 4 * s, cy + 8 * s);
        path.lineTo(cx - 4 * s, cy + 8 * s);
        path.lineTo(cx - 4 * s, cy);
        path.lineTo(cx - 8 * s, cy);
        path.close();
        if (shift) {
            icon.setStyle(Paint.Style.FILL_AND_STROKE);
            c.drawPath(path, icon);
            icon.setStyle(Paint.Style.STROKE);
        } else {
            c.drawPath(path, icon);
        }
    }

    private void drawDel(Canvas c, RectF b, int color) {
        float s = Ui.dp(getContext(), 1);
        float cx = b.centerX(), cy = b.centerY();
        icon.setColor(color);
        path.reset();
        path.moveTo(cx - 11 * s, cy);
        path.lineTo(cx - 5 * s, cy - 7 * s);
        path.lineTo(cx + 11 * s, cy - 7 * s);
        path.lineTo(cx + 11 * s, cy + 7 * s);
        path.lineTo(cx - 5 * s, cy + 7 * s);
        path.close();
        c.drawPath(path, icon);
        c.drawLine(cx - 1 * s, cy - 3 * s, cx + 5 * s, cy + 3 * s, icon);
        c.drawLine(cx + 5 * s, cy - 3 * s, cx - 1 * s, cy + 3 * s, icon);
    }
}
