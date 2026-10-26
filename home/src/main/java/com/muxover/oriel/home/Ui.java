package com.muxover.oriel.home;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.TextView;

import com.muxover.oriel.home.R;

/** Colors, fonts and small view helpers shared by every screen. */
final class Ui {
    static final int INK = 0xFF050506;
    static final int GRAPHITE = 0xFF141416;
    static final int TILE = 0xFF1C1C20;
    static final int LINE = 0xFF26262A;
    static final int PAPER = 0xFFF4F3EF;
    static final int PAPER_INK = 0xFF5C5C64;
    static final int TEXT2 = 0xFFB4B4BC;
    static final int MUTED = 0xFF8A8A93;
    /** The accent colour chosen in Settings, Appearance; read again by init() on every screen. */
    static int ACCENT = 0xFF8B5CF6;
    static int ACCENT_HI = 0xFFA383FA;
    /** Accent choices: name, colour, lighter colour for text on ink. */
    static final Object[][] ACCENTS = {
        {"Violet", 0xFF8B5CF6, 0xFFA383FA}, {"Blue", 0xFF3B82F6, 0xFF7AA9F9}, {"Teal", 0xFF14B8A6, 0xFF4FD1C2},
        {"Green", 0xFF22C55E, 0xFF62DB8E}, {"Amber", 0xFFF59E0B, 0xFFF8BB4D}, {"Rose", 0xFFF43F5E, 0xFFF77A8F},
    };
    /** Popups and bars floating over other apps. */
    static final int GLASS = 0xE00A0A0B;
    static final int GOOD = 0xFF34D399;
    static final int WARN = 0xFFF59E0B;

    // Shared measures, in dp. Every screen uses these.
    static final int R_S = 12, R_M = 16, R_L = 24, R_PILL = 28;
    static final int ROW_H = 64, PILL_H = 56;
    /** Focus: only home tiles grow, since they have room around them. Rows, pills and cards stay in their
     * bounds and show focus by colour alone, so nothing is ever cut off by the list or card around it. */
    static final float LIFT_TILE = 1.14f;
    static final int FOCUS_MS = 160, ENTER_MS = 220;
    static final android.view.animation.Interpolator EASE = new android.view.animation.DecelerateInterpolator(2f);

    /** The standard focus change: paper fill and ink text, inside the view's own bounds. */
    static void focusLook(View v, boolean has, int restColor, float radiusDp, TextView... texts) {
        v.setBackground(round(has ? PAPER : restColor, dp(v.getContext(), radiusDp)));
        for (int i = 0; i < texts.length; i++) {
            if (texts[i] == null) continue;
            texts[i].setTextColor(has ? (i == 0 ? INK : PAPER_INK) : (i == 0 ? PAPER : MUTED));
        }
    }

    static Typeface light, regular, medium, semibold, bold, mono;

    private Ui() {}

    static void init(Context c) {
        String accent = Prefs.of(c).getString("accent", "Violet");
        for (Object[] a : ACCENTS) {
            if (a[0].equals(accent)) {
                ACCENT = (Integer) a[1];
                ACCENT_HI = (Integer) a[2];
            }
        }
        if (regular != null) return;
        Resources r = c.getResources();
        light = r.getFont(R.font.geist_extralight);
        regular = r.getFont(R.font.geist_regular);
        medium = r.getFont(R.font.geist_medium);
        semibold = r.getFont(R.font.geist_semibold);
        bold = r.getFont(R.font.geist_bold);
        mono = r.getFont(R.font.geistmono_regular);
    }

    /**
     * Mouse pointer over an Oriel screen: the focusable view under it takes focus, as the remote would give it,
     * so the lit item follows the pointer and a click opens what is lit. Call from dispatchGenericMotionEvent.
     */
    static void hover(android.app.Activity a, android.view.MotionEvent e) {
        if (e.getActionMasked() != android.view.MotionEvent.ACTION_HOVER_MOVE
            && e.getActionMasked() != android.view.MotionEvent.ACTION_HOVER_ENTER) return;
        View v = focusableAt(a.getWindow().getDecorView(), (int) e.getRawX(), (int) e.getRawY());
        if (v == null || v.isFocused()) return;
        // A focus change can scroll a row under a still-moving pointer; a short pause stops focus running ahead.
        long now = android.os.SystemClock.uptimeMillis();
        if (now - hoverFocusAt < HOVER_PAUSE_MS) return;
        hoverFocusAt = now;
        // A mouse puts the screen in touch mode, where only these views take focus; the click that follows then
        // opens the item at once, since it is already lit.
        v.setFocusableInTouchMode(true);
        v.requestFocus();
    }

    private static final int[] at = new int[2];
    private static final long HOVER_PAUSE_MS = 220;
    private static long hoverFocusAt;

    /** The innermost visible, focusable view at a screen point, or null. */
    private static View focusableAt(View v, int x, int y) {
        if (v.getVisibility() != View.VISIBLE || !v.isEnabled()) return null;
        v.getLocationOnScreen(at);
        if (x < at[0] || y < at[1] || x >= at[0] + v.getWidth() || y >= at[1] + v.getHeight()) return null;
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = g.getChildCount() - 1; i >= 0; i--) {
                View hit = focusableAt(g.getChildAt(i), x, y);
                if (hit != null) return hit;
            }
        }
        // Text fields are left alone: focus there would bring up the keyboard just by passing over them.
        return v.isFocusable() && !(v instanceof android.widget.EditText) ? v : null;
    }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static TextView text(Context c, String s, float size, Typeface tf, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, size);
        t.setTypeface(tf);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        // Even line lengths, so a wrapped text never ends on a lone word or two.
        t.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_BALANCED);
        t.setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE);
        return t;
    }

    static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    /** A soft radial wash like the top shelf: tint in the upper right fading to ink. */
    static GradientDrawable wash(int tint, float radiusPx, float cx, float cy) {
        int mid = blend(tint, INK, 0.72f);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{tint, mid, INK});
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setGradientRadius(radiusPx);
        g.setGradientCenter(cx, cy);
        return g;
    }

    private static Bitmap grainTile;

    /**
     * Fine film grain laid over a gradient. Dark gradients on an 8-bit panel step into visible bands; a faint
     * random speckle, finer than the eye resolves, breaks the steps up so the gradient reads as smooth.
     */
    static Drawable grain(Context c) {
        if (grainTile == null) {
            int n = 128;
            int[] px = new int[n * n];
            java.util.Random r = new java.util.Random(7);
            for (int i = 0; i < px.length; i++) {
                int a = r.nextInt(7);
                px[i] = r.nextBoolean() ? Color.argb(a, 255, 255, 255) : Color.argb(a + 2, 0, 0, 0);
            }
            grainTile = Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888);
        }
        BitmapDrawable d = new BitmapDrawable(c.getResources(), grainTile);
        d.setTileModeXY(android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT);
        d.setFilterBitmap(false);
        return d;
    }

    /** A full-size view showing grain(), for laying over a backdrop. */
    static View grainView(Context c) {
        View v = new View(c);
        v.setBackground(grain(c));
        return v;
    }

    static int blend(int a, int b, float t) {
        int r = Math.round(Color.red(a) * (1 - t) + Color.red(b) * t);
        int g = Math.round(Color.green(a) * (1 - t) + Color.green(b) * t);
        int bl = Math.round(Color.blue(a) * (1 - t) + Color.blue(b) * t);
        return Color.rgb(r, g, bl);
    }

    static void clipRound(View v, final float radiusPx) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radiusPx);
            }
        });
        v.setClipToOutline(true);
    }

    /** A private copy, so drawing it at another size never disturbs the tile showing the original. */
    private static Drawable copy(Drawable d) {
        Drawable.ConstantState cs = d.getConstantState();
        return cs != null ? cs.newDrawable().mutate() : d;
    }

    /**
     * The backdrop tint for a logo: its strongest real colour, darkened. Black, white and grey are skipped,
     * so a logo on black (OSN+) takes its magenta, not black. A logo with no colour at all gets the fallback.
     */
    static int tintOf(Drawable d, int fallback) {
        if (d == null) return fallback;
        d = copy(d);
        final int w = 48, h = 28;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, w, h);
        d.draw(new Canvas(b));
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        b.recycle();
        // Weigh colourful pixels into 24 hue buckets and keep the heaviest bucket's average colour.
        float[] weight = new float[24], sat = new float[24], x = new float[24], y = new float[24];
        float[] hsv = new float[3];
        for (int p : px) {
            if (Color.alpha(p) < 128) continue;
            Color.colorToHSV(p, hsv);
            if (hsv[1] < 0.25f || hsv[2] < 0.2f) continue;
            float wt = hsv[1] * hsv[2];
            int k = Math.min(23, (int) (hsv[0] / 15f));
            weight[k] += wt;
            sat[k] += hsv[1] * wt;
            double rad = Math.toRadians(hsv[0]);
            x[k] += (float) Math.cos(rad) * wt;
            y[k] += (float) Math.sin(rad) * wt;
        }
        int best = -1;
        for (int k = 0; k < 24; k++) {
            float around = weight[k] + 0.5f * (weight[(k + 23) % 24] + weight[(k + 1) % 24]);
            if (weight[k] > 0 && (best < 0 || around > weight[best] + 0.5f * (weight[(best + 23) % 24] + weight[(best + 1) % 24]))) best = k;
        }
        // A few stray coloured pixels in a grey logo do not count as its colour.
        if (best < 0 || weight[best] < px.length * 0.01f) return fallback;
        float hue = (float) Math.toDegrees(Math.atan2(y[best], x[best]));
        if (hue < 0) hue += 360;
        float s = Math.max(0.45f, Math.min(0.85f, sat[best] / weight[best]));
        return Color.HSVToColor(new float[]{hue, s, 0.42f});
    }

    /** A soft, heavily blurred copy of artwork for the top shelf backdrop, made by shrinking then enlarging. */
    static Drawable blurred(Context c, Drawable d) {
        if (d == null) return null;
        d = copy(d);
        Bitmap tiny = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(tiny);
        d.setBounds(0, 0, 32, 18);
        d.draw(cv);
        Bitmap mid = Bitmap.createScaledBitmap(tiny, 8, 5, true);
        Bitmap big = Bitmap.createScaledBitmap(mid, 160, 90, true);
        tiny.recycle();
        mid.recycle();
        // richer color, much darker, so text on top always reads
        ColorMatrix sat = new ColorMatrix();
        sat.setSaturation(1.6f);
        ColorMatrix dim = new ColorMatrix();
        dim.setScale(0.38f, 0.38f, 0.38f, 1f);
        sat.postConcat(dim);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        p.setColorFilter(new ColorMatrixColorFilter(sat));
        Bitmap soft = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888);
        new Canvas(soft).drawBitmap(big, 0, 0, p);
        big.recycle();
        BitmapDrawable out = new BitmapDrawable(c.getResources(), soft);
        out.setFilterBitmap(true);
        return out;
    }

    /**
     * Renders artwork at exactly the pixel size it will be shown, cropping to fill.
     * Big bitmaps are halved step by step first, which keeps edges clean instead of jagged.
     */
    static Drawable sized(Context c, Drawable d, int w, int h, boolean crop) {
        if (d == null || w <= 0 || h <= 0) return d;
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        if (d instanceof BitmapDrawable && ((BitmapDrawable) d).getBitmap() != null) {
            Bitmap src = ((BitmapDrawable) d).getBitmap();
            int sw = src.getWidth(), sh = src.getHeight();
            float scale = crop ? Math.max((float) w / sw, (float) h / sh) : Math.min((float) w / sw, (float) h / sh);
            Bitmap step = src;
            while (step.getWidth() * scale < step.getWidth() / 2f && step.getWidth() > 2) {
                Bitmap half = Bitmap.createScaledBitmap(step, step.getWidth() / 2, step.getHeight() / 2, true);
                if (step != src) step.recycle();
                scale *= 2f;
                step = half;
            }
            float dw = step.getWidth() * scale, dh = step.getHeight() * scale;
            android.graphics.RectF dst = new android.graphics.RectF((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f);
            cv.drawBitmap(step, null, dst, p);
            if (step != src) step.recycle();
        } else {
            Drawable cp = copy(d);
            int iw = cp.getIntrinsicWidth() > 0 ? cp.getIntrinsicWidth() : w;
            int ih = cp.getIntrinsicHeight() > 0 ? cp.getIntrinsicHeight() : h;
            float scale = crop ? Math.max((float) w / iw, (float) h / ih) : Math.min((float) w / iw, (float) h / ih);
            int dw = Math.round(iw * scale), dh = Math.round(ih * scale);
            cp.setBounds((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2);
            cp.draw(cv);
        }
        BitmapDrawable bd = new BitmapDrawable(c.getResources(), out);
        bd.setFilterBitmap(true);
        return bd;
    }

    /** The colour around the edge of an icon, so its tile background continues the icon seamlessly. */
    static int edgeColor(Drawable d, int fallback) {
        if (d == null) return fallback;
        d = copy(d);
        Bitmap b = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, 32, 32);
        d.draw(new Canvas(b));
        int[] pts = {b.getPixel(1, 1), b.getPixel(30, 1), b.getPixel(1, 30), b.getPixel(30, 30), b.getPixel(16, 1), b.getPixel(1, 16)};
        b.recycle();
        int r = 0, g = 0, bl = 0, n = 0;
        for (int px : pts) {
            if (Color.alpha(px) < 200) continue;
            r += Color.red(px);
            g += Color.green(px);
            bl += Color.blue(px);
            n++;
        }
        return n == 0 ? fallback : Color.rgb(r / n, g / n, bl / n);
    }

    /**
     * False when an app's TV banner would look wrong on a tile: too small to be sharp, or a logo on a plain
     * background that sits off-centre or runs into an edge. Full-picture banners always pass.
     */
    static boolean bannerLooksRight(Drawable d) {
        if (d.getIntrinsicWidth() > 0 && d.getIntrinsicWidth() < 240) return false;
        final int w = 96, h = 54;
        Drawable c = copy(d);
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        c.setBounds(0, 0, w, h);
        c.draw(new Canvas(b));
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        b.recycle();
        int[] corners = {px[0], px[w - 1], px[(h - 1) * w], px[h * w - 1]};
        boolean clear = true;
        for (int k : corners) if (Color.alpha(k) >= 200) clear = false;
        int bg = corners[0];
        if (!clear) {
            for (int k : corners) if (Color.alpha(k) < 200 || colorGap(k, bg) > 24) return true; // a picture, not a logo on a plain colour
        }
        int minX = w, minY = h, maxX = -1, maxY = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = px[y * w + x];
                boolean ink = clear ? Color.alpha(p) > 60 : colorGap(p, bg) > 48;
                if (!ink) continue;
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
            }
        }
        if (maxX < 0) return false;
        if (minX == 0 || minY == 0 || maxX == w - 1 || maxY == h - 1) return false;
        float cx = (minX + maxX) / 2f, cy = (minY + maxY) / 2f;
        return Math.abs(cx - w / 2f) <= w * 0.1f && Math.abs(cy - h / 2f) <= h * 0.14f;
    }

    private static int colorGap(int a, int b) {
        return Math.max(Math.abs(Color.red(a) - Color.red(b)), Math.max(Math.abs(Color.green(a) - Color.green(b)), Math.abs(Color.blue(a) - Color.blue(b))));
    }

    /** True when the icon is a full square with solid corners, like store icons, so it can run edge to edge. */
    static boolean opaqueCorners(Drawable d) {
        if (!(d instanceof BitmapDrawable)) return false;
        Bitmap b = ((BitmapDrawable) d).getBitmap();
        if (b == null) return false;
        int w = b.getWidth() - 1, h = b.getHeight() - 1;
        return Color.alpha(b.getPixel(0, 0)) == 255 && Color.alpha(b.getPixel(w, 0)) == 255
            && Color.alpha(b.getPixel(0, h)) == 255 && Color.alpha(b.getPixel(w, h)) == 255;
    }

    /**
     * A square icon filling a wide tile: the icon fills the full height in the middle and its outermost
     * columns are stretched sideways, so backgrounds and gradients continue seamlessly to the tile edges.
     */
    static Drawable iconFill(Context c, Drawable icon, int w, int h) {
        Drawable sq = sized(c, icon, h, h, true);
        Bitmap s = ((BitmapDrawable) sq).getBitmap();
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        int x0 = (w - h) / 2;
        cv.drawBitmap(s, new android.graphics.Rect(0, 0, 1, h), new android.graphics.Rect(0, 0, x0 + 1, h), p);
        cv.drawBitmap(s, new android.graphics.Rect(h - 1, 0, h, h), new android.graphics.Rect(x0 + h - 1, 0, w, h), p);
        cv.drawBitmap(s, x0, 0, p);
        BitmapDrawable bd = new BitmapDrawable(c.getResources(), out);
        bd.setFilterBitmap(true);
        return bd;
    }
}
