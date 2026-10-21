package com.apexdrive.launcher.utils;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

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

    private static final String KEY_SAVED_TEMP_C = "saved_temp_c";
    private static final String KEY_SAVED_CONDITION = "saved_condition";
    private static final String KEY_MANUAL_TEMP_CALIBRATION = "manual_temp_calibration";

    public float getSavedTempCelsius() {
        return prefs.getFloat(KEY_SAVED_TEMP_C, 32.0f);
    }

    public void setSavedTempCelsius(float temp) {
        prefs.edit().putFloat(KEY_SAVED_TEMP_C, temp).apply();
    }

    public String getSavedCondition() {
        return prefs.getString(KEY_SAVED_CONDITION, "Outside");
    }

    public void setSavedCondition(String condition) {
        prefs.edit().putString(KEY_SAVED_CONDITION, condition).apply();
    }

    public boolean isManualTempCalibration() {
        return prefs.getBoolean(KEY_MANUAL_TEMP_CALIBRATION, false);
    }

    public void setManualTempCalibration(boolean manual) {
        prefs.edit().putBoolean(KEY_MANUAL_TEMP_CALIBRATION, manual).apply();
    }

    private static final String KEY_RADIO_PACKAGE = "radio_package";
    private static final String KEY_RADIO_NAME = "radio_name";
    private static final String KEY_AUTO_WEATHER = "auto_weather";
    private static final String KEY_TEMP_OFFSET = "temp_offset";

    public String getRadioPackage() {
        return prefs.getString(KEY_RADIO_PACKAGE, "");
    }

    public void setRadioPackage(String pkg) {
        prefs.edit().putString(KEY_RADIO_PACKAGE, pkg).apply();
    }

    public String getRadioName() {
        return prefs.getString(KEY_RADIO_NAME, "Built-in Radio");
    }

    public void setRadioName(String name) {
        prefs.edit().putString(KEY_RADIO_NAME, name).apply();
    }

    public boolean isAutoWeatherEnabled() {
        return prefs.getBoolean(KEY_AUTO_WEATHER, true);
    }

    public void setAutoWeatherEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_AUTO_WEATHER, enabled).apply();
    }

    public float getTempOffset() {
        return prefs.getFloat(KEY_TEMP_OFFSET, 0.0f);
    }

    public void setTempOffset(float offset) {
        prefs.edit().putFloat(KEY_TEMP_OFFSET, offset).apply();
    }

    private static final String KEY_MAP_PACKAGE = "map_package";
    private static final String KEY_MAP_NAME = "map_name";

    public String getMapPackage() {
        return prefs.getString(KEY_MAP_PACKAGE, "net.osmand.plus");
    }

    public void setMapPackage(String pkg) {
        prefs.edit().putString(KEY_MAP_PACKAGE, pkg).apply();
    }

    public String getMapName() {
        return prefs.getString(KEY_MAP_NAME, "OsmAnd Offline Maps");
    }

    public void setMapName(String name) {
        prefs.edit().putString(KEY_MAP_NAME, name).apply();
    }

    private static final String KEY_RECENT_APPS = "recent_apps_list";

    public List<String> getRecentApps() {
        String raw = prefs.getString(KEY_RECENT_APPS, "");
        List<String> list = new ArrayList<>();
        if (!raw.isEmpty()) {
            for (String p : raw.split(",")) {
                String trimmed = p.trim();
                if (!trimmed.isEmpty() && !list.contains(trimmed)) {
                    list.add(trimmed);
                }
            }
        }
        return list;
    }

    public void addRecentApp(String pkg) {
        if (pkg == null || pkg.trim().isEmpty()) return;
        List<String> list = getRecentApps();
        list.remove(pkg);
        list.add(0, pkg);
        while (list.size() > 15) {
            list.remove(list.size() - 1);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(list.get(i));
        }
        prefs.edit().putString(KEY_RECENT_APPS, sb.toString()).apply();
    }

    public void removeRecentApp(String pkg) {
        if (pkg == null || pkg.trim().isEmpty()) return;
        List<String> list = getRecentApps();
        if (list.remove(pkg)) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(list.get(i));
            }
            prefs.edit().putString(KEY_RECENT_APPS, sb.toString()).apply();
        }
    }

    public void clearRecentApps() {
        prefs.edit().remove(KEY_RECENT_APPS).apply();
    }
}
