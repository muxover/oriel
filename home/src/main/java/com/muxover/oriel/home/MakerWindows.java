package com.muxover.oriel.home;

import android.content.Context;

/** The TV maker's system-window panels, by window title, and the Oriel screen that replaces each. */
final class MakerWindows {
    static final String POWER = "power", INPUTS = "inputs";

    private MakerWindows() {}

    /** Which Oriel screen replaces this window, or null when it is not one of the maker's panels. */
    static String kindOf(String title) {
        // Hikeen boards: the power box (hold Power) is a window titled after its factory menu app.
        if (title.equals("com.hikeen.tv.factorymenu")) return POWER;
        return null;
    }

    static void openOurs(Context c, String kind) {
        switch (kind) {
            case POWER: PowerActivity.open(c); break;
            case INPUTS: InputActivity.open(c); break;
            default: break;
        }
    }
}
