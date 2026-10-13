package com.apexdrive.launcher.services;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationManager;
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
    private float rawTempC = 32.0f;
    private String currentCondition = "OUTSIDE";
    private String currentCity = "";
    private long lastOnlineFetchTime = 0;
    private double lastFetchLat = 0.0;
    private double lastFetchLon = 0.0;

    public CarThermometerWatcher(Context context, TemperatureListener listener) {
        this.context = context;
        this.listener = listener;
        this.prefHelper = new PreferenceHelper(context);
        this.currentTempC = prefHelper.getSavedTempCelsius();
        this.rawTempC = this.currentTempC;
        this.currentCondition = prefHelper.getSavedCondition();
    }

    public void setUseFahrenheit(boolean useFahrenheit) {
        this.isMph = useFahrenheit;
        notifyTemp(currentTempC);
    }

    public float getCurrentTempCelsius() {
        return currentTempC;
    }

    public float getRawTempCelsius() {
        return rawTempC;
    }

    public String getCurrentCondition() {
        return currentCondition;
    }

    public String getCurrentCity() {
        return currentCity;
    }

    public void setManualTemp(float tempC, String condition) {
        this.currentTempC = tempC;
        if (condition != null) this.currentCondition = condition;
        prefHelper.setSavedTempCelsius(tempC);
        prefHelper.setSavedCondition(this.currentCondition);
        prefHelper.setManualTempCalibration(true);
        notifyTemp(currentTempC);
    }

    public void setTempOffset(float offset) {
        prefHelper.setTempOffset(offset);
        this.currentTempC = this.rawTempC + offset;
        prefHelper.setSavedTempCelsius(this.currentTempC);
        notifyTemp(this.currentTempC);
    }

    public void clearManualCalibration() {
        prefHelper.setManualTempCalibration(false);
        prefHelper.setTempOffset(0.0f);
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

        // Immediate automatic location-based weather sync on ignition / boot
        if (prefHelper.isAutoWeatherEnabled()) {
            triggerImmediateLocationWeatherSync();
        }
    }

    public void triggerImmediateLocationWeatherSync() {
        try {
            LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            Location last = null;
            if (lm != null) {
                last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (last == null) last = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                if (last == null) last = lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER);
            }
            if (last != null && (last.getLatitude() != 0.0 || last.getLongitude() != 0.0)) {
                fetchOnlineWeather(last.getLatitude(), last.getLongitude());
            } else {
                fetchIpGeolocationAndWeather();
            }
        } catch (Exception e) {
            fetchIpGeolocationAndWeather();
        }
    }

    public void onLocationUpdated(double latitude, double longitude) {
        if (latitude == 0.0 && longitude == 0.0) return;
        if (!prefHelper.isAutoWeatherEnabled() && prefHelper.isManualTempCalibration()) return;

        long now = System.currentTimeMillis();
        boolean timeElapsed = (now - lastOnlineFetchTime > 10 * 60 * 1000); // 10 minutes debounce
        boolean distMoved = (Math.abs(latitude - lastFetchLat) > 0.03 || Math.abs(longitude - lastFetchLon) > 0.03); // ~3km
        if (lastOnlineFetchTime == 0 || timeElapsed || distMoved) {
            fetchOnlineWeather(latitude, longitude);
        }
    }

    public void fetchIpGeolocationAndWeather() {
        new Thread(() -> {
            try {
                URL url = new URL("http://ip-api.com/json");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(4000);
                if (conn.getResponseCode() == 200) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                    reader.close();
                    JSONObject json = new JSONObject(sb.toString());
                    if ("success".equalsIgnoreCase(json.optString("status"))) {
                        double lat = json.optDouble("lat", 0.0);
                        double lon = json.optDouble("lon", 0.0);
                        String city = json.optString("city", "");
                        if (!city.isEmpty()) {
                            currentCity = city.toUpperCase(Locale.US);
                        }
                        if (lat != 0.0 && lon != 0.0) {
                            fetchOnlineWeather(lat, lon);
                        }
                    }
                }
            } catch (Exception ignored) {}
        }).start();
    }

    public void fetchOnlineWeather(double latitude, double longitude) {
        if (latitude == 0.0 && longitude == 0.0) return;
        lastOnlineFetchTime = System.currentTimeMillis();
        lastFetchLat = latitude;
        lastFetchLon = longitude;

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

                        rawTempC = temp;
                        float offset = prefHelper.getTempOffset();
                        currentTempC = temp + offset;
                        currentCondition = condition;
                        prefHelper.setSavedTempCelsius(currentTempC);
                        prefHelper.setSavedCondition(condition);

                        if (currentCity.isEmpty()) {
                            resolveCityName(latitude, longitude);
                        }

                        pollHandler.post(() -> notifyTemp(currentTempC));
                    }
                }
            } catch (Exception ignored) {}
        }).start();
    }

    private void resolveCityName(double latitude, double longitude) {
        try {
            String geoUrl = String.format(Locale.US,
                    "https://api.bigdatacloud.net/data/reverse-geocode-client?latitude=%.4f&longitude=%.4f&localityLanguage=en",
                    latitude, longitude);
            URL url = new URL(geoUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            if (conn.getResponseCode() == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();
                JSONObject obj = new JSONObject(sb.toString());
                String city = obj.optString("city", "");
                if (city.isEmpty()) city = obj.optString("locality", "");
                if (city.isEmpty()) city = obj.optString("principalSubdivision", "");
                if (!city.isEmpty()) {
                    currentCity = city.toUpperCase(Locale.US);
                    pollHandler.post(() -> notifyTemp(currentTempC));
                }
            }
        } catch (Exception ignored) {}
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
        String desc = !currentCity.isEmpty() ? currentCity + " · " + currentCondition : currentCondition;
        if (isMph) {
            float tempF = (tempC * 9f / 5f) + 32f;
            formatted = String.format(Locale.US, "%.0f°F · %s", tempF, desc);
        } else {
            formatted = String.format(Locale.US, "%.0f°C · %s", tempC, desc);
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
