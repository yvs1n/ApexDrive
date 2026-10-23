package com.apexdrive.launcher.utils;

import java.util.Locale;

/** Coordinate formatting with correct hemisphere letters (no sign shown twice). */
public final class GeoFormat {

    private GeoFormat() {}

    public static String formatLatitude(double latitude, int decimals) {
        return String.format(Locale.US, "%." + decimals + "f° %s",
                Math.abs(latitude), latitude < 0 ? "S" : "N");
    }

    public static String formatLongitude(double longitude, int decimals) {
        return String.format(Locale.US, "%." + decimals + "f° %s",
                Math.abs(longitude), longitude < 0 ? "W" : "E");
    }

    /** "48.1372° N, 11.5756° E" */
    public static String formatPair(double latitude, double longitude, int decimals) {
        return formatLatitude(latitude, decimals) + ", " + formatLongitude(longitude, decimals);
    }
}
