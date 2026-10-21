package com.apexdrive.launcher.utils;

/** Unit conversion and sanity checks for speed and temperature. */
public final class UnitMath {

    /** Plausible range for an outside air temperature reading, in degrees Celsius. */
    public static final float MIN_OUTSIDE_C = -40f;
    public static final float MAX_OUTSIDE_C = 60f;

    private UnitMath() {}

    public static int speedForDisplay(float speedMps, boolean mph) {
        int v = Math.round(speedMps * (mph ? 2.23694f : 3.6f));
        return v < 2 ? 0 : v;
    }

    public static double kmToMiles(double km) {
        return km * 0.621371;
    }

    public static float celsiusToFahrenheit(float c) {
        return c * 9f / 5f + 32f;
    }

    public static boolean isPlausibleOutsideCelsius(float c) {
        return !Float.isNaN(c) && c >= MIN_OUTSIDE_C && c <= MAX_OUTSIDE_C;
    }

    /** Offset is applied exactly once, on top of the raw reading of the active source. */
    public static float applyOffset(float rawC, float offsetC) {
        return rawC + offsetC;
    }
}
