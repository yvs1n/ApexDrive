package com.apexdrive.launcher.utils;

import android.content.Context;
import android.content.SharedPreferences;

public class PreferenceHelper {
    private static final String PREF_NAME = "apexdrive_settings";
    private static final String KEY_ACCENT_COLOR = "accent_color";
    private static final String KEY_SPEED_UNIT = "speed_unit";
    private static final String KEY_CARPLAY_PACKAGE = "carplay_package";
    private static final String KEY_CARPLAY_NAME = "carplay_name";
    private static final String KEY_SELECTED_DRIVER_DEVICE = "selected_driver_device";

    public static final String UNIT_KMH = "KM/H";
    public static final String UNIT_MPH = "MPH";

    public static final int COLOR_AMBER = 0xFFE5A93C;
    public static final int COLOR_BLUE = 0xFF3B82F6;
    public static final int COLOR_RED = 0xFFEF4444;
    public static final int COLOR_GREEN = 0xFF10B981;
    public static final int COLOR_WHITE = 0xFFE2E8F0;

    public static final String DEFAULT_CARPLAY_PKG = "com.zjinnova.zlink";
    public static final String DEFAULT_CARPLAY_NAME = "Zlink 5.3";

    private final SharedPreferences prefs;

    public PreferenceHelper(Context context) {
        this.prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public int getAccentColor() {
        return prefs.getInt(KEY_ACCENT_COLOR, COLOR_AMBER);
    }

    public void setAccentColor(int color) {
        prefs.edit().putInt(KEY_ACCENT_COLOR, color).apply();
    }

    public String getSpeedUnit() {
        return prefs.getString(KEY_SPEED_UNIT, UNIT_KMH);
    }

    public void setSpeedUnit(String unit) {
        prefs.edit().putString(KEY_SPEED_UNIT, unit).apply();
    }

    public String getCarPlayPackage() {
        return prefs.getString(KEY_CARPLAY_PACKAGE, DEFAULT_CARPLAY_PKG);
    }

    public void setCarPlayPackage(String pkgName) {
        prefs.edit().putString(KEY_CARPLAY_PACKAGE, pkgName).apply();
    }

    public String getCarPlayName() {
        return prefs.getString(KEY_CARPLAY_NAME, DEFAULT_CARPLAY_NAME);
    }

    public void setCarPlayName(String name) {
        prefs.edit().putString(KEY_CARPLAY_NAME, name).apply();
    }

    public String getSelectedDriverDevice() {
        return prefs.getString(KEY_SELECTED_DRIVER_DEVICE, "");
    }

    public void setSelectedDriverDevice(String deviceAddress) {
        prefs.edit().putString(KEY_SELECTED_DRIVER_DEVICE, deviceAddress).apply();
    }
}
