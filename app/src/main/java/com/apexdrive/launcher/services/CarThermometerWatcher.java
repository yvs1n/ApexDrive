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

import java.util.Locale;

public class CarThermometerWatcher implements SensorEventListener {

    public interface TemperatureListener {
        void onTemperatureUpdated(String tempFormatted, float tempCelsius);
    }

    private final Context context;
    private final TemperatureListener listener;
    private SensorManager sensorManager;
    private Sensor tempSensor;
    private BroadcastReceiver canbusReceiver;
    private final Handler pollHandler = new Handler(Looper.getMainLooper());
    private boolean isMph = false;
    private float currentTempC = 26.0f;

    public CarThermometerWatcher(Context context, TemperatureListener listener) {
        this.context = context;
        this.listener = listener;
    }

    public void setUseFahrenheit(boolean useFahrenheit) {
        this.isMph = useFahrenheit;
        notifyTemp(currentTempC);
    }

    public void start() {
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
        filter.addAction("com.ts.can.temperature");
        filter.addAction("com.syu.canbus");
        filter.addAction("com.android.settings.CANBUS_CAR_INFO");

        try {
            context.registerReceiver(canbusReceiver, filter);
        } catch (Exception ignored) {}

        pollCanSettings();
        startPeriodicPoll();
    }

    private void parseCanbusTempIntent(Intent intent) {
        try {
            String[] keys = {"out_temp", "temperature", "ext_temp", "ambient_temp", "temp", "celsius"};
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
                    "exterior_temp"
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
                pollHandler.postDelayed(this, 10000);
            }
        }, 10000);
    }

    private void notifyTemp(float tempC) {
        if (listener == null) return;
        String formatted;
        if (isMph) {
            float tempF = (tempC * 9f / 5f) + 32f;
            formatted = String.format(Locale.US, "%.0f°F · Outside", tempF);
        } else {
            formatted = String.format(Locale.US, "%.0f°C · Outside", tempC);
        }
        listener.onTemperatureUpdated(formatted, tempC);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_AMBIENT_TEMPERATURE && event.values.length > 0) {
            currentTempC = event.values[0];
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
