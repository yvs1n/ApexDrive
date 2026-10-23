package com.apexdrive.launcher.utils;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * Sunrise and sunset from a position and date, worked out on the device (NOAA's simplified
 * solar equations, accurate to a few minutes). Used by the automatic day and night theme;
 * no network is needed.
 */
public final class SunTimes {

    /** Used when no position is known: the theme turns dark at 18:30 and light at 06:30. */
    public static final int FALLBACK_SUNRISE_MIN = 6 * 60 + 30;
    public static final int FALLBACK_SUNSET_MIN = 18 * 60 + 30;

    private SunTimes() {}

    /**
     * Sunrise and sunset in minutes after local midnight, as {sunrise, sunset}, or null for
     * polar day or night.
     */
    public static int[] localMinutes(double lat, double lon, Calendar day) {
        int doy = day.get(Calendar.DAY_OF_YEAR);
        double gamma = 2 * Math.PI / 365.0 * (doy - 1);
        double eqTime = 229.18 * (0.000075 + 0.001868 * Math.cos(gamma) - 0.032077 * Math.sin(gamma)
                - 0.014615 * Math.cos(2 * gamma) - 0.040849 * Math.sin(2 * gamma));
        double decl = 0.006918 - 0.399912 * Math.cos(gamma) + 0.070257 * Math.sin(gamma)
                - 0.006758 * Math.cos(2 * gamma) + 0.000907 * Math.sin(2 * gamma)
                - 0.002697 * Math.cos(3 * gamma) + 0.00148 * Math.sin(3 * gamma);
        double latR = Math.toRadians(lat);
        double cosHa = Math.cos(Math.toRadians(90.833)) / (Math.cos(latR) * Math.cos(decl))
                - Math.tan(latR) * Math.tan(decl);
        if (cosHa > 1 || cosHa < -1) return null;
        double ha = Math.toDegrees(Math.acos(cosHa));
        double sunriseUtc = 720 - 4 * (lon + ha) - eqTime;
        double sunsetUtc = 720 - 4 * (lon - ha) - eqTime;
        TimeZone tz = day.getTimeZone();
        int offsetMin = tz.getOffset(day.getTimeInMillis()) / 60000;
        return new int[]{wrap((int) Math.round(sunriseUtc) + offsetMin), wrap((int) Math.round(sunsetUtc) + offsetMin)};
    }

    /** True between sunset and sunrise. A NaN position uses the fallback times. */
    public static boolean isNight(double lat, double lon, Calendar now) {
        int[] t = Double.isNaN(lat) || Double.isNaN(lon) ? null : localMinutes(lat, lon, now);
        int sunrise = t != null ? t[0] : FALLBACK_SUNRISE_MIN;
        int sunset = t != null ? t[1] : FALLBACK_SUNSET_MIN;
        int m = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        return sunrise < sunset ? (m < sunrise || m >= sunset) : (m >= sunset && m < sunrise);
    }

    private static int wrap(int minutes) {
        return ((minutes % 1440) + 1440) % 1440;
    }
}
