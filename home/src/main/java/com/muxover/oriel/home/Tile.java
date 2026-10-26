package com.muxover.oriel.home;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** A rounded tile that lifts when focused, Apple TV style, with its name shown underneath. */
final class Tile extends LinearLayout {
    interface Listener {
        void onFocused(Tile t);
        void onOpen(Tile t);
    }

    static final float LIFT = Ui.LIFT_TILE;

    final Entry entry;
    final FrameLayout card;
    private final TextView caption;
    private final boolean captionAlways;
    private final DecelerateInterpolator ease = new DecelerateInterpolator(2f);

    Tile(Context c, Entry e, int widthDp, int heightDp, boolean captionAlways, final Listener l) {
        super(c);
        this.entry = e;
        this.captionAlways = captionAlways;
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        setClipChildren(false);
        setClipToPadding(false);

        int w = Ui.dp(c, widthDp), h = Ui.dp(c, heightDp);
        card = face(c, e, w, h, Ui.dp(c, 16), LIFT);
        card.setFocusable(true);
        card.setFocusableInTouchMode(true);
        card.setContentDescription(e.title);
        addView(card, new LayoutParams(w, h));

        caption = Ui.text(c, e.title, 15, Ui.medium, Ui.PAPER);
        caption.setSingleLine(true);
        caption.setAlpha(captionAlways ? 0.6f : 0f);
        LayoutParams cp = new LayoutParams(-2, -2);
        cp.topMargin = Ui.dp(c, 20);
        addView(caption, cp);

        card.setOnFocusChangeListener((v, has) -> {
            setLifted(has);
            if (has) l.onFocused(this);
        });
        card.setOnClickListener(v -> l.onOpen(this));
    }

    /**
     * The artwork of a tile at a given pixel size: TV artwork full bleed, an official icon on its own colour,
     * or a built-in glyph on a colour gradient. Images are rendered for the size times maxScale so lifting stays sharp.
     */
    static FrameLayout face(Context c, Entry e, int w, int h, float radius, float maxScale) {
        FrameLayout f = new FrameLayout(c);
        Ui.clipRound(f, radius);
        int rw = Math.round(w * maxScale), rh = Math.round(h * maxScale);
        if (e.isBuiltin()) {
            int[] col = IconView.colors(e.iconKind);
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, col);
            g.setCornerRadius(radius);
            f.setBackground(g);
            IconView iv = new IconView(c, e.iconKind, Ui.PAPER);
            iv.setStroke(2f);
            int s = Math.round(Math.min(w, h) * 0.46f);
            f.addView(iv, new FrameLayout.LayoutParams(s, s, Gravity.CENTER));
        } else if (e.banner != null) {
            f.setBackground(Ui.round(Ui.TILE, radius));
            ImageView iv = new ImageView(c);
            iv.setScaleType(ImageView.ScaleType.FIT_XY);
            iv.setImageDrawable(Ui.sized(c, e.banner, rw, rh, true));
            f.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        } else if (e.storeIcon != null && Ui.opaqueCorners(e.storeIcon) && w > h) {
            ImageView iv = new ImageView(c);
            iv.setScaleType(ImageView.ScaleType.FIT_XY);
            iv.setImageDrawable(Ui.iconFill(c, e.storeIcon, rw, rh));
            f.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        } else {
            f.setBackground(Ui.round(e.tileColor, radius));
            ImageView iv = new ImageView(c);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int s = Math.round(Math.min(w, h) * 0.62f);
            int rs = Math.round(s * maxScale);
            iv.setImageDrawable(Ui.sized(c, e.storeIcon != null ? e.storeIcon : e.icon, rs, rs, false));
            if (e.storeIcon != null) Ui.clipRound(iv, s * 0.22f);
            f.addView(iv, new FrameLayout.LayoutParams(s, s, Gravity.CENTER));
        }
        return f;
    }

    /** The same artwork as face(), painted straight into a picture with rounded corners, for screens drawn once. */
    static android.graphics.Bitmap faceBitmap(Context c, Entry e, int w, int h, float radius) {
        android.graphics.Bitmap out = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas cv = new android.graphics.Canvas(out);
        android.graphics.Path clip = new android.graphics.Path();
        clip.addRoundRect(new android.graphics.RectF(0, 0, w, h), radius, radius, android.graphics.Path.Direction.CW);
        cv.clipPath(clip);
        if (e.isBuiltin()) {
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, IconView.colors(e.iconKind));
            g.setBounds(0, 0, w, h);
            g.draw(cv);
            IconView iv = new IconView(c, e.iconKind, Ui.PAPER);
            int s = Math.round(Math.min(w, h) * 0.46f);
            iv.layout(0, 0, s, s);
            cv.save();
            cv.translate((w - s) / 2f, (h - s) / 2f);
            iv.draw(cv);
            cv.restore();
        } else {
            android.graphics.drawable.Drawable d;
            if (e.banner != null) {
                d = Ui.sized(c, e.banner, w, h, true);
            } else if (e.storeIcon != null && Ui.opaqueCorners(e.storeIcon) && w > h) {
                d = Ui.iconFill(c, e.storeIcon, w, h);
            } else {
                cv.drawColor(e.tileColor);
                int s = Math.round(Math.min(w, h) * 0.62f);
                d = Ui.sized(c, e.storeIcon != null ? e.storeIcon : e.icon, s, s, false);
                d.setBounds((w - s) / 2, (h - s) / 2, (w + s) / 2, (h + s) / 2);
                d.draw(cv);
                d = null;
            }
            if (d != null) {
                d.setBounds(0, 0, w, h);
                d.draw(cv);
            }
        }
        return out;
    }

    private void setLifted(boolean on) {
        Context c = getContext();
        // No elevation shadow: on this TV's Mali-450 a moving shadow costs more than the whole tile.
        card.animate().scaleX(on ? LIFT : 1f).scaleY(on ? LIFT : 1f)
            .setDuration(on ? 200 : 150).setInterpolator(ease).start();
        caption.animate().alpha(on ? 1f : (captionAlways ? 0.6f : 0f))
            .translationY(on ? Ui.dp(c, 6) : 0).setDuration(on ? 200 : 120).setInterpolator(ease).start();
    }
}
