package com.muxover.oriel.home;

import android.content.Intent;
import android.graphics.drawable.Drawable;

/** One thing on the home screen: a built-in action or an installed app. */
final class Entry {
    final String id;
    final String title;
    String status;
    String subtitle;
    Drawable banner;
    Drawable icon;
    int iconKind = -1;
    Intent intent;
    int tint;
    Drawable storeIcon;
    int tileColor = 0xFFEDECE8;
    /** The home row the app declares itself for (video, audio, game...), or null. */
    String kind;
    /** Part of the TV's own software and never updated from Play: shown in All apps only. */
    boolean maker;

    Entry(String id, String title) {
        this.id = id;
        this.title = title;
    }

    boolean isBuiltin() {
        return iconKind >= 0;
    }
}
