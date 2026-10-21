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
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;

import com.apexdrive.launcher.utils.PreferenceHelper;
import com.apexdrive.launcher.utils.UnitMath;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * Outside temperature from, in priority order: a manual value, the vehicle (CAN broadcast,
 * vendor Settings key or system property), the built-in ambient sensor, and online weather.
 *
 * The raw reading of each source is kept separately and the calibration offset is applied
 * exactly once, when the value is published. With no reading at all the listener receives
 * "--" and a NaN temperature; nothing is invented.
 *
 * All state is read and written on the main thread. Worker threads (weather) hand their
 * result over with a single post to the main handler.
 */
public class CarThermometerWatcher implements SensorEventListener {

    public interface TemperatureListener {
        /** tempCelsius is {@link Float#NaN} when there is no reading. */
        void onTemperatureUpdated(String tempFormatted, float tempCelsius, String condition);
    }

    private enum Source { MANUAL, VEHICLE, SENSOR, WEATHER }

    /** A vehicle reading older than this is no longer shown. */
    private static final long VEHICLE_MAX_AGE_MS = 15 * 60 * 1000L;
    /** A weather reading older than this is no longer shown. */
    private static final long WEATHER_MAX_AGE_MS = 45 * 60 * 1000L;

    private final Context context;
    private final TemperatureListener listener;
    private final PreferenceHelper prefHelper;
    private SensorManager sensorManager;
    private Sensor tempSensor;
    private BroadcastReceiver canbusReceiver;
    private final Handler pollHandler = new Handler(Looper.getMainLooper());
    private boolean useFahrenheit = false;
    private boolean stopped = false;

    // Per-source raw readings (indexed by Source.ordinal()).
    private final float[] rawC = {Float.NaN, Float.NaN, Float.NaN, Float.NaN};
    private final long[] readAt = new long[4];
    private final String[] label = {"MANUAL", "VEHICLE SENSOR", "BUILT-IN SENSOR", "OUTSIDE"};
    private float offsetC;

    // What is currently shown. Volatile so the getters are safe from any thread.
    private volatile float displayedC = Float.NaN;
    private volatile float displayedRawC = Float.NaN;
    private volatile String displayedCondition = "NO DATA";
    private volatile String displayedCity = "";

    private static final long POLL_INTERVAL_MS = 60 * 1000L;
    private long lastBroadcastAt = 0;

    private String weatherCity = "";
    // Weather scheduling. Times are SystemClock.elapsedRealtime(); 0 means "never".
    private static final long WEATHER_REFRESH_MS = 10 * 60 * 1000L;
    private static final long WEATHER_RETRY_BASE_MS = 30 * 1000L;
    private double lastWeatherLat = 0.0;
    private double lastWeatherLon = 0.0;
    private long lastWeatherSuccessAt = 0;
    private long lastWeatherAttemptAt = 0;
    private int weatherFailures = 0;
    private boolean weatherFetching = false;
    private double lastFetchLat = 0.0;
    private double lastFetchLon = 0.0;

    public CarThermometerWatcher(Context context, TemperatureListener listener) {
        this.context = context;
        this.listener = listener;
        this.prefHelper = new PreferenceHelper(context);
        this.offsetC = prefHelper.getTempOffset();
        this.useFahrenheit = prefHelper.isTempFahrenheit();
        // Only a value the driver typed in is restored. Sensor, vehicle and weather
        // readings are never replayed from a previous run as if they were live.
        float saved = prefHelper.getSavedTempCelsius();
        if (prefHelper.isManualTempCalibration() && !Float.isNaN(saved)) {
            rawC[Source.MANUAL.ordinal()] = saved;
            label[Source.MANUAL.ordinal()] = prefHelper.getSavedCondition();
        }
    }

    /** Temperature unit, independent of the speed unit. Persisted. */
    public void setUseFahrenheit(boolean useFahrenheit) {
        this.useFahrenheit = useFahrenheit;
        prefHelper.setTempFahrenheit(useFahrenheit);
        publish();
    }

    public boolean isFahrenheit() {
        return useFahrenheit;
    }

    public boolean hasReading() {
        return !Float.isNaN(displayedC);
    }

    /** Displayed temperature including the calibration offset, or {@link Float#NaN} when there is none. */
    public float getCurrentTempCelsius() {
        return displayedC;
    }

    /** Raw reading of the source being displayed, before the offset, or NaN when there is none. */
    public float getRawTempCelsius() {
        return displayedRawC;
    }

    public String getCurrentCondition() {
        return displayedCondition;
    }

    public String getCurrentCity() {
        return displayedCity;
    }

    public void setManualTemp(float tempC, String condition) {
        rawC[Source.MANUAL.ordinal()] = tempC;
        readAt[Source.MANUAL.ordinal()] = SystemClock.elapsedRealtime();
        if (condition != null) label[Source.MANUAL.ordinal()] = condition;
        prefHelper.setSavedTempCelsius(tempC);
        prefHelper.setSavedCondition(label[Source.MANUAL.ordinal()]);
        prefHelper.setManualTempCalibration(true);
        publish();
    }

    public void setTempOffset(float offset) {
        if (Float.isNaN(offset)) return;
        offsetC = offset;
        prefHelper.setTempOffset(offset);
        publish();
    }

    /**
     * Sets the offset so that the displayed source shows {@code targetCelsius}.
     * @return false when there is no reading to calibrate against
     */
    public boolean calibrateDisplayedTo(float targetCelsius) {
        if (Float.isNaN(displayedRawC) || Float.isNaN(targetCelsius)) return false;
        setTempOffset(targetCelsius - displayedRawC);
        return true;
    }

    public void clearManualCalibration() {
        prefHelper.setManualTempCalibration(false);
        prefHelper.setTempOffset(0.0f);
        offsetC = 0.0f;
        rawC[Source.MANUAL.ordinal()] = Float.NaN;
        publish();
    }

    public boolean isManualCalibration() {
        return prefHelper.isManualTempCalibration();
    }

    public void start() {
        stopped = false;
        publish();

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

        // Vendor actions: names collected from common Chinese head-unit firmwares.
        // [verify in car] which of these the unit really sends, and with which extras.
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
        if (isOnlineWeatherEnabled()) {
            triggerImmediateLocationWeatherSync();
        }
    }

    public void triggerImmediateLocationWeatherSync() {
        if (!isOnlineWeatherEnabled()) return;
        // Only an existing location is used: the last known fix, else the saved coarse
        // location. There is no IP-based lookup (P-37).
        Location last = null;
        try {
            LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            if (lm != null) {
                last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (last == null) last = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                if (last == null) last = lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER);
            }
        } catch (Exception ignored) {
            // No permission yet: fall back to the saved location below.
        }
        if (last != null && (last.getLatitude() != 0.0 || last.getLongitude() != 0.0)) {
            fetchOnlineWeather(last.getLatitude(), last.getLongitude());
        } else if (prefHelper.hasSavedWeatherLocation()) {
            fetchOnlineWeather(prefHelper.getSavedWeatherLat(), prefHelper.getSavedWeatherLon());
        }
    }

    /** Weather makes network calls only when the user has allowed it and auto weather is on. */
    private boolean isOnlineWeatherEnabled() {
        return prefHelper.isOnlineWeatherAllowed() && prefHelper.isAutoWeatherEnabled();
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    public void onLocationUpdated(double latitude, double longitude) {
        if (latitude == 0.0 && longitude == 0.0) return;
        if (!isOnlineWeatherEnabled()) return;
        double rLat = round2(latitude), rLon = round2(longitude);
        if (!prefHelper.hasSavedWeatherLocation()
                || (float) rLat != (float) prefHelper.getSavedWeatherLat()
                || (float) rLon != (float) prefHelper.getSavedWeatherLon()) {
            prefHelper.setSavedWeatherLocation(rLat, rLon); // written only when the 1 km cell changes
        }

        lastFetchLat = latitude;
        lastFetchLon = longitude;
        boolean moved = lastWeatherSuccessAt != 0
                && (Math.abs(latitude - lastWeatherLat) > 0.03 || Math.abs(longitude - lastWeatherLon) > 0.03); // ~3 km
        if (moved) {
            fetchOnlineWeather(latitude, longitude);
        } else {
            refreshWeatherIfDue();
        }
    }

    /**
     * Fetches when the last successful reading is old, or after a failure once the
     * backoff (30 s doubling to 10 min) has passed. A failed attempt no longer blocks
     * retries for 10 minutes.
     */
    private void refreshWeatherIfDue() {
        if (!isOnlineWeatherEnabled() || weatherFetching) return;
        if (lastFetchLat == 0.0 && lastFetchLon == 0.0) return;
        long now = SystemClock.elapsedRealtime();
        if (weatherFailures > 0) {
            long backoff = Math.min(WEATHER_REFRESH_MS, WEATHER_RETRY_BASE_MS << Math.min(weatherFailures - 1, 5));
            if (now - lastWeatherAttemptAt < backoff) return;
        } else if (lastWeatherSuccessAt != 0 && now - lastWeatherSuccessAt < WEATHER_REFRESH_MS) {
            return;
        }
        fetchOnlineWeather(lastFetchLat, lastFetchLon);
    }

    public void fetchOnlineWeather(double latitude, double longitude) {
        if (!isOnlineWeatherEnabled() || weatherFetching) return;
        if (latitude == 0.0 && longitude == 0.0) return;
        lastWeatherAttemptAt = SystemClock.elapsedRealtime();
        weatherFetching = true;
        lastFetchLat = latitude;
        lastFetchLon = longitude;

        final String knownCity = weatherCity;
        final boolean needCity = weatherCity.isEmpty()
                || Math.abs(latitude - lastWeatherLat) > 0.03 || Math.abs(longitude - lastWeatherLon) > 0.03;
        new Thread(() -> {
            try {
                String urlStr = String.format(Locale.US,
                        "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f&current_weather=true",
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
                        final float temp = (float) cw.getDouble("temperature");
                        final String condition = mapWeatherCode(cw.optInt("weathercode", 0));
                        final String city = needCity ? resolveCityName(latitude, longitude) : knownCity;
                        pollHandler.post(() -> onWeatherResult(temp, condition, city, latitude, longitude));
                        return;
                    }
                }
            } catch (Exception ignored) {}
            pollHandler.post(this::onWeatherFailed); // reached on any failure
        }).start();
    }

    /** Runs on the main thread: all weather fields change together. */
    private void onWeatherResult(float tempC, String condition, String city, double lat, double lon) {
        weatherFetching = false;
        if (stopped) return;
        if (!UnitMath.isPlausibleOutsideCelsius(tempC)) {
            onWeatherFailed();
            return;
        }
        weatherFailures = 0;
        lastWeatherSuccessAt = SystemClock.elapsedRealtime();
        lastWeatherLat = lat;
        lastWeatherLon = lon;
        weatherCity = city; // "" when unknown, so a city from an earlier place is not kept
        label[Source.WEATHER.ordinal()] = condition;
        setReading(Source.WEATHER, tempC);
    }

    /** Runs on the main thread. The previous value ages out instead of staying "live". */
    private void onWeatherFailed() {
        weatherFetching = false;
        if (stopped) return;
        weatherFailures++;
    }

    /** Blocking; call from a worker thread. Returns "" when unknown. */
    private String resolveCityName(double latitude, double longitude) {
        try {
            String geoUrl = String.format(Locale.US,
                    "https://api.bigdatacloud.net/data/reverse-geocode-client?latitude=%.2f&longitude=%.2f&localityLanguage=en",
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
                return city.toUpperCase(Locale.US);
            }
        } catch (Exception ignored) {}
        return "";
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

    /**
     * Vehicle broadcast. Only extras that name an outside temperature are read; generic
     * names such as "temp" or "temperature" could be coolant or cabin air, so they are
     * ignored. Values are taken as degrees Celsius. [verify in car] key names and unit.
     */
    private void parseCanbusTempIntent(Intent intent) {
        lastBroadcastAt = SystemClock.elapsedRealtime();
        try {
            Bundle extras = intent.getExtras();
            if (extras == null) return;
            String[] keys = {"out_temp", "ext_temp", "ambient_temp"};
            for (String key : keys) {
                Object o = extras.get(key);
                if (o instanceof Number) {
                    acceptVehicleValue(((Number) o).floatValue());
                    return;
                }
                if (o instanceof String) {
                    parseAndSetTemp((String) o);
                    return;
                }
            }
        } catch (Exception ignored) {}
    }

    private void pollCanSettings() {
        try {
            // [verify in car] vendor Settings.System keys; unknown on most units.
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

    private static java.lang.reflect.Method sysPropGet;
    private static boolean sysPropUnavailable;

    /**
     * Reads vendor system properties in-process (no "getprop" process is spawned, P-48).
     * android.os.SystemProperties is a hidden API; on failure this stays silent and the
     * properties are simply not used. [verify in car] which property the unit sets.
     */
    private void checkSystemProperties() {
        if (sysPropUnavailable) return;
        String[] props = {
                "persist.sys.can.temp",
                "sys.car.temp",
                "sys.car.outtemp",
                "persist.fyt.temp",
                "sys.canbus.out_temp"
        };
        try {
            if (sysPropGet == null) {
                Class<?> c = Class.forName("android.os.SystemProperties");
                sysPropGet = c.getMethod("get", String.class);
            }
            for (String prop : props) {
                String val = (String) sysPropGet.invoke(null, prop);
                if (val != null && !val.trim().isEmpty()) {
                    parseAndSetTemp(val.trim());
                    return;
                }
            }
        } catch (Throwable t) {
            sysPropUnavailable = true;
        }
    }

    /**
     * Vendor string value (Settings key or property). Taken as degrees Celsius: the unit
     * cannot be told from the number, so a value outside the plausible range is rejected
     * rather than guessed to be Fahrenheit. [verify in car] unit used by the head unit.
     */
    private void parseAndSetTemp(String strVal) {
        try {
            String cleaned = strVal.replaceAll("[^0-9.-]", "");
            if (!cleaned.isEmpty()) {
                acceptVehicleValue(Float.parseFloat(cleaned));
            }
        } catch (Exception ignored) {}
    }

    private void acceptVehicleValue(float valueC) {
        if (!UnitMath.isPlausibleOutsideCelsius(valueC)) return;
        setReading(Source.VEHICLE, valueC);
    }

    private void startPeriodicPoll() {
        pollHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                // Polling is only a fallback: once the unit broadcasts, rely on that.
                long sinceBroadcast = SystemClock.elapsedRealtime() - lastBroadcastAt;
                if (lastBroadcastAt == 0 || sinceBroadcast > VEHICLE_MAX_AGE_MS) {
                    pollCanSettings();
                    checkSystemProperties();
                }
                publish(); // lets a reading that has become stale drop out
                refreshWeatherIfDue();
                pollHandler.postDelayed(this, POLL_INTERVAL_MS);
            }
        }, POLL_INTERVAL_MS);
    }

    private void setReading(Source s, float valueC) {
        rawC[s.ordinal()] = valueC;
        readAt[s.ordinal()] = SystemClock.elapsedRealtime();
        publish();
    }

    private boolean isUsable(Source s) {
        int i = s.ordinal();
        if (Float.isNaN(rawC[i])) return false;
        long age = SystemClock.elapsedRealtime() - readAt[i];
        if (s == Source.VEHICLE) return age <= VEHICLE_MAX_AGE_MS;
        if (s == Source.WEATHER) return age <= WEATHER_MAX_AGE_MS;
        return true;
    }

    /** Picks the highest-priority usable source, applies the offset once and notifies. */
    private void publish() {
        Source active = null;
        for (Source s : Source.values()) {
            if (isUsable(s)) { active = s; break; }
        }
        String city = "";
        if (active == null) {
            displayedRawC = Float.NaN;
            displayedC = Float.NaN;
            displayedCondition = "NO DATA";
        } else {
            float raw = rawC[active.ordinal()];
            displayedRawC = raw;
            displayedC = (active == Source.MANUAL) ? raw : UnitMath.applyOffset(raw, offsetC);
            displayedCondition = label[active.ordinal()];
            if (active == Source.WEATHER) city = weatherCity;
        }
        displayedCity = city;
        notifyListener();
    }

    private void notifyListener() {
        if (listener == null) return;
        float tempC = displayedC;
        String desc = !displayedCity.isEmpty() ? displayedCity + " · " + displayedCondition : displayedCondition;
        String formatted;
        if (Float.isNaN(tempC)) {
            formatted = String.format(Locale.US, "--°%s · %s", useFahrenheit ? "F" : "C", desc);
        } else if (useFahrenheit) {
            formatted = String.format(Locale.US, "%.0f°F · %s", UnitMath.celsiusToFahrenheit(tempC), desc);
        } else {
            formatted = String.format(Locale.US, "%.0f°C · %s", tempC, desc);
        }
        listener.onTemperatureUpdated(formatted, tempC, displayedCondition);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_AMBIENT_TEMPERATURE && event.values.length > 0) {
            float v = event.values[0];
            if (!UnitMath.isPlausibleOutsideCelsius(v)) return;
            setReading(Source.SENSOR, v);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    public void stop() {
        stopped = true;
        weatherFetching = false; // a pending result is discarded with the handler queue
        pollHandler.removeCallbacksAndMessages(null);
        if (sensorManager != null && tempSensor != null) {
            sensorManager.unregisterListener(this);
        }
        if (canbusReceiver != null) {
            try {
                context.unregisterReceiver(canbusReceiver);
            } catch (Exception ignored) {}
            canbusReceiver = null;
        }
    }
}
