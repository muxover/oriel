package com.muxover.oriel.home;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * Prayer times computed on the TV, no internet needed: the standard astronomical method with the
 * calculation conventions people choose between (twilight angles for Fajr and Isha, or Isha a fixed time after Maghrib).
 */
final class PrayerTimes {
    static final String[] NAMES = {"Fajr", "Dhuhr", "Asr", "Maghrib", "Isha"};

    /** A calculation method: id, name shown in Settings, Fajr angle, Isha angle (0 when Isha is minutes after Maghrib), Isha minutes. */
    static final class Method {
        final String id, name;
        final double fajr, isha, ishaMinutes;

        Method(String id, String name, double fajr, double isha, double ishaMinutes) {
            this.id = id;
            this.name = name;
            this.fajr = fajr;
            this.isha = isha;
            this.ishaMinutes = ishaMinutes;
        }
    }

    static final Method[] METHODS = {
        new Method("mwl", "Muslim World League", 18, 17, 0),
        new Method("egypt", "Egyptian General Authority of Survey", 19.5, 17.5, 0),
        new Method("makkah", "Umm al-Qura, Makkah", 18.5, 0, 90),
        new Method("karachi", "University of Islamic Sciences, Karachi", 18, 18, 0),
        new Method("isna", "Islamic Society of North America", 15, 15, 0),
        new Method("dubai", "Dubai", 18.2, 18.2, 0),
        new Method("kuwait", "Kuwait", 18, 17.5, 0),
        new Method("qatar", "Qatar", 18, 0, 90),
        new Method("turkey", "Diyanet, Turkey", 18, 17, 0),
    };

    static Method method(String id) {
        for (Method m : METHODS) if (m.id.equals(id)) return m;
        return METHODS[0];
    }

    private PrayerTimes() {}

    /** Today's five times in minutes after local midnight. */
    static double[] today(double lat, double lng, Method method, boolean hanafi) {
        Calendar cal = Calendar.getInstance();
        int y = cal.get(Calendar.YEAR), m = cal.get(Calendar.MONTH) + 1, d = cal.get(Calendar.DAY_OF_MONTH);
        double tz = TimeZone.getDefault().getOffset(cal.getTimeInMillis()) / 3600000.0;
        double jd = julian(y, m, d) - lng / (15 * 24.0);
        double base = tz - lng / 15.0;
        double noon = fix(12 - sun(jd + 0.5)[1], 24);
        double dhuhr = noon + base;
        double fajr = noon - hourAngle(jd, method.fajr, lat, 5) + base;
        double sunset = noon + hourAngle(jd, 0.833, lat, 18) + base;
        double isha = method.isha > 0 ? noon + hourAngle(jd, method.isha, lat, 19) + base : sunset + method.ishaMinutes / 60.0;
        double asr = noon + asrAngle(jd, hanafi ? 2 : 1, lat, 15) + base;
        return new double[]{fajr * 60, (dhuhr + 1 / 60.0) * 60, asr * 60, sunset * 60, isha * 60};
    }

    private static double julian(int y, int m, int d) {
        if (m <= 2) {
            y -= 1;
            m += 12;
        }
        double a = Math.floor(y / 100.0);
        double b = 2 - a + Math.floor(a / 4);
        return Math.floor(365.25 * (y + 4716)) + Math.floor(30.6001 * (m + 1)) + d + b - 1524.5;
    }

    /** Sun declination and equation of time for a Julian date. */
    private static double[] sun(double jd) {
        double dd = jd - 2451545.0;
        double g = fix(357.529 + 0.98560028 * dd, 360);
        double q = fix(280.459 + 0.98564736 * dd, 360);
        double l = fix(q + 1.915 * sin(g) + 0.020 * sin(2 * g), 360);
        double e = 23.439 - 0.00000036 * dd;
        double ra = atan2(cos(e) * sin(l), cos(l)) / 15.0;
        double eqt = q / 15.0 - fix(ra, 24);
        double decl = asin(sin(e) * sin(l));
        return new double[]{decl, eqt};
    }

    private static double hourAngle(double jd, double angle, double lat, double t) {
        double decl = sun(jd + t / 24.0)[0];
        double v = (-sin(angle) - sin(decl) * sin(lat)) / (cos(decl) * cos(lat));
        return acos(Math.max(-1, Math.min(1, v))) / 15.0;
    }

    private static double asrAngle(double jd, double factor, double lat, double t) {
        double decl = sun(jd + t / 24.0)[0];
        double angle = -acot(factor + Math.tan(Math.toRadians(Math.abs(lat - decl))));
        return hourAngle(jd, angle, lat, t);
    }

    /** The next prayer for the city and method chosen in Settings: its name and "HH:mm", or null when prayers are off. */
    static String[] next(android.content.Context c) {
        if (!Prefs.prayersOn(c)) return null;
        double[] t = today(Prefs.placeLat(c), Prefs.placeLng(c), method(Prefs.prayerMethod(c)), Prefs.asrHanafi(c));
        Calendar now = Calendar.getInstance();
        double mins = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        for (int i = 0; i < 5; i++) {
            if (t[i] > mins) {
                int total = (int) Math.round(t[i]);
                return new String[]{NAMES[i], String.format(java.util.Locale.US, "%02d:%02d", (total / 60) % 24, total % 60)};
            }
        }
        int f = (int) Math.round(t[0]);
        return new String[]{NAMES[0], String.format(java.util.Locale.US, "%02d:%02d", (f / 60) % 24, f % 60)};
    }

    private static double fix(double a, double b) {
        a = a - b * Math.floor(a / b);
        return a < 0 ? a + b : a;
    }

    private static double sin(double d) { return Math.sin(Math.toRadians(d)); }
    private static double cos(double d) { return Math.cos(Math.toRadians(d)); }
    private static double asin(double x) { return Math.toDegrees(Math.asin(x)); }
    private static double acos(double x) { return Math.toDegrees(Math.acos(x)); }
    private static double atan2(double y, double x) { return Math.toDegrees(Math.atan2(y, x)); }
    private static double acot(double x) { return Math.toDegrees(Math.atan(1 / x)); }
}
