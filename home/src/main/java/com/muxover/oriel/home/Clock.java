package com.muxover.oriel.home;

import android.content.Context;
import android.view.View;
import android.widget.TextClock;

/** The time and date formats chosen in Settings, Clock and date, applied to every clock Oriel shows. */
final class Clock {
    /** Date styles: the name shown in Settings and the pattern; an empty pattern hides the date. */
    static final String[][] DATE_STYLES = {
        {"Tuesday 27 October", "EEEE d MMMM"}, {"Tue 27 Oct", "EEE d MMM"}, {"27/10/2026", "dd/MM/yyyy"},
        {"10/27/2026", "MM/dd/yyyy"}, {"2026-10-27", "yyyy-MM-dd"}, {"No date", ""},
    };

    private Clock() {}

    /** The time pattern: hours and minutes, with seconds when asked, in the chosen 12 or 24 hour form. */
    static void time(Context c, TextClock t, boolean seconds) {
        String sec = seconds ? ":ss" : "";
        String twelve = "h:mm" + sec + (Prefs.clockAmPm(c) ? " a" : "");
        String twentyFour = "HH:mm" + sec;
        switch (Prefs.clockFormat(c)) {
            case "12":
                t.setFormat12Hour(twelve);
                t.setFormat24Hour(twelve);
                break;
            case "24":
                t.setFormat12Hour(twentyFour);
                t.setFormat24Hour(twentyFour);
                break;
            default:
                t.setFormat12Hour(twelve);
                t.setFormat24Hour(twentyFour);
        }
    }

    static void date(Context c, TextClock d) {
        int i = Prefs.dateStyle(c);
        String pattern = DATE_STYLES[i >= 0 && i < DATE_STYLES.length ? i : 0][1];
        d.setVisibility(pattern.isEmpty() ? View.GONE : View.VISIBLE);
        if (pattern.isEmpty()) return;
        d.setFormat12Hour(pattern);
        d.setFormat24Hour(pattern);
    }

    /** "12-hour" or "24-hour" as the TV itself is set, for the Settings row. */
    static String tvFormat(Context c) {
        return android.text.format.DateFormat.is24HourFormat(c) ? "24-hour" : "12-hour";
    }
}
