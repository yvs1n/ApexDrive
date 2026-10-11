package com.apexdrive.launcher.services;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import com.apexdrive.launcher.utils.PreferenceHelper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

public class CarThermometerWatcher implements SensorEventListener {

    public interface TemperatureListener {
        void onTemperatureUpdated(String tempFormatted, float tempCelsius, String condition);
    }

    private final Context context;
    private final TemperatureListener listener;
    private final PreferenceHelper prefHelper;
    private SensorManager sensorManager;
    private Sensor tempSensor;
    private BroadcastReceiver canbusReceiver;
    private final Handler pollHandler = new Handler(Looper.getMainLooper());
    private boolean isMph = false;
    private float currentTempC = 32.0f;
    private String currentCondition = "OUTSIDE";
    private long lastOnlineFetchTime = 0;

    public CarThermometerWatcher(Context context, TemperatureListener listener) {
        this.context = context;
        this.listener = listener;
        this.prefHelper = new PreferenceHelper(context);
        this.currentTempC = prefHelper.getSavedTempCelsius();
        this.currentCondition = prefHelper.getSavedCondition();
    }

    public void setUseFahrenheit(boolean useFahrenheit) {
        this.isMph = useFahrenheit;
        notifyTemp(currentTempC);
    }

    public float getCurrentTempCelsius() {
        return currentTempC;
    }

    public String getCurrentCondition() {
        return currentCondition;
    }

    public void setManualTemp(float tempC, String condition) {
        this.currentTempC = tempC;
        if (condition != null) this.currentCondition = condition;
        prefHelper.setSavedTempCelsius(tempC);
        prefHelper.setSavedCondition(this.currentCondition);
        prefHelper.setManualTempCalibration(true);
        notifyTemp(currentTempC);
    }

    public void clearManualCalibration() {
        prefHelper.setManualTempCalibration(false);
    }

    public boolean isManualCalibration() {
        return prefHelper.isManualTempCalibration();
    }

    public void start() {
        notifyTemp(currentTempC);

        try {
            sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
            if (sensorManager != null) {
                tempSensor = sensorManager.getDefaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE);
                if (tempSensor != null) {
                    sensorManager.registerListener(this, tempSensor, SensorManager.SENSOR_DELAY_NORMAL);
                }
            }
        } catch (Exception ignored) {}

        canbusReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (intent == null) return;
                parseCanbusTempIntent(intent);
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction("android.intent.action.AIR_CONDITION_CHANGED");
        filter.addAction("com.android.ecar.cannews");
        filter.addAction("com.canbus.temperature");
        filter.addAction("com.microntek.canbus.display");
        filter.addAction("com.microntek.canbus.temperature");
        filter.addAction("com.ts.can.temperature");
        filter.addAction("action.hct.canbus.temp");
        filter.addAction("com.syu.canbus");
        filter.addAction("com.hiworld.canbus.carinfo");
        filter.addAction("com.raise.canbus");
        filter.addAction("com.android.settings.CANBUS_CAR_INFO");

        try {
            context.registerReceiver(canbusReceiver, filter);
        } catch (Exception ignored) {}

        pollCanSettings();
        checkSystemProperties();
        startPeriodicPoll();
    }

    public void fetchOnlineWeather(double latitude, double longitude) {
        if (prefHelper.isManualTempCalibration()) return;
        long now = System.currentTimeMillis();
        if (now - lastOnlineFetchTime < 180000) return; // 3 minutes debounce
        lastOnlineFetchTime = now;

        new Thread(() -> {
            try {
                String urlStr = String.format(Locale.US,
                        "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current_weather=true",
                        latitude, longitude);
                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(4000);
                conn.connect();

                if (conn.getResponseCode() == 200) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                    reader.close();

                    JSONObject json = new JSONObject(sb.toString());
                    if (json.has("current_weather")) {
                        JSONObject cw = json.getJSONObject("current_weather");
                        float temp = (float) cw.getDouble("temperature");
                        int code = cw.optInt("weathercode", 0);
                        String condition = mapWeatherCode(code);

                        currentTempC = temp;
                        currentCondition = condition;
                        prefHelper.setSavedTempCelsius(temp);
                        prefHelper.setSavedCondition(condition);

                        pollHandler.post(() -> notifyTemp(currentTempC));
                    }
                }
            } catch (Exception ignored) {}
        }).start();
    }

    private String mapWeatherCode(int code) {
        if (code == 0) return "CLEAR";
        if (code >= 1 && code <= 3) return "PARTLY CLOUDY";
        if (code == 45 || code == 48) return "FOG";
        if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)) return "RAIN";
        if ((code >= 71 && code <= 77) || (code >= 85 && code <= 86)) return "SNOW";
        if (code >= 95 && code <= 99) return "THUNDERSTORM";
        return "OUTSIDE";
    }

    private void parseCanbusTempIntent(Intent intent) {
        try {
            String[] keys = {"out_temp", "temperature", "ext_temp", "ambient_temp", "temp", "celsius", "car_temp"};
            for (String key : keys) {
                if (intent.hasExtra(key)) {
                    float val = intent.getFloatExtra(key, -999f);
                    if (val == -999f) {
                        val = intent.getIntExtra(key, -999);
                    }
                    if (val == -999f) {
                        val = (float) intent.getDoubleExtra(key, -999.0);
                    }
                    if (val != -999f && val > -40f && val < 65f) {
                        currentTempC = val;
                        currentCondition = "VEHICLE SENSOR";
                        prefHelper.setSavedTempCelsius(val);
                        prefHelper.setSavedCondition(currentCondition);
                        notifyTemp(currentTempC);
                        return;
                    }
                }
            }

            String strVal = intent.getStringExtra("out_temp");
            if (strVal != null && !strVal.isEmpty()) {
                parseAndSetTemp(strVal);
            }
        } catch (Exception ignored) {}
    }

    private void pollCanSettings() {
        try {
            String[] settingKeys = {
                    "can_temperature",
                    "out_temperature",
                    "car_out_temp",
                    "canbus_temp",
                    "exterior_temp",
                    "mcu_out_temp"
            };
            for (String key : settingKeys) {
                String val = Settings.System.getString(context.getContentResolver(), key);
                if (val != null && !val.trim().isEmpty()) {
                    parseAndSetTemp(val);
                    return;
                }
            }
        } catch (Exception ignored) {}
    }

    private void checkSystemProperties() {
        new Thread(() -> {
            String[] props = {
                    "persist.sys.can.temp",
                    "sys.car.temp",
                    "sys.car.outtemp",
                    "persist.fyt.temp",
                    "sys.canbus.out_temp"
            };
            for (String prop : props) {
                try {
                    Process p = Runtime.getRuntime().exec("getprop " + prop);
                    BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                    String val = r.readLine();
                    r.close();
                    if (val != null && !val.trim().isEmpty()) {
                        pollHandler.post(() -> parseAndSetTemp(val.trim()));
                        return;
                    }
                } catch (Exception ignored) {}
            }
        }).start();
    }

    private void parseAndSetTemp(String strVal) {
        try {
            String cleaned = strVal.replaceAll("[^0-9.-]", "");
            if (!cleaned.isEmpty()) {
                float val = Float.parseFloat(cleaned);
                if (val > -40f && val < 130f) {
                    if (val > 55f) {
                        val = (val - 32f) * 5f / 9f;
                    }
                    currentTempC = val;
                    currentCondition = "VEHICLE SENSOR";
                    prefHelper.setSavedTempCelsius(val);
                    prefHelper.setSavedCondition(currentCondition);
                    notifyTemp(currentTempC);
                }
            }
        } catch (Exception ignored) {}
    }

    private void startPeriodicPoll() {
        pollHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                pollCanSettings();
                checkSystemProperties();
                pollHandler.postDelayed(this, 15000);
            }
        }, 15000);
    }

    private void notifyTemp(float tempC) {
        if (listener == null) return;
        String formatted;
        if (isMph) {
            float tempF = (tempC * 9f / 5f) + 32f;
            formatted = String.format(Locale.US, "%.0f°F · %s", tempF, currentCondition);
        } else {
            formatted = String.format(Locale.US, "%.0f°C · %s", tempC, currentCondition);
        }
        listener.onTemperatureUpdated(formatted, tempC, currentCondition);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_AMBIENT_TEMPERATURE && event.values.length > 0) {
            currentTempC = event.values[0];
            currentCondition = "BUILT-IN SENSOR";
            prefHelper.setSavedTempCelsius(currentTempC);
            prefHelper.setSavedCondition(currentCondition);
            notifyTemp(currentTempC);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    public void stop() {
        pollHandler.removeCallbacksAndMessages(null);
        if (sensorManager != null && tempSensor != null) {
            sensorManager.unregisterListener(this);
        }
        if (canbusReceiver != null) {
            try {
                context.unregisterReceiver(canbusReceiver);
            } catch (Exception ignored) {}
        }
    }
}
