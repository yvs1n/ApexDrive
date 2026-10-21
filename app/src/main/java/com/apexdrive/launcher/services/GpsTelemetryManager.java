package com.apexdrive.launcher.services;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.GnssStatus;
import android.location.GpsSatellite;
import android.location.GpsStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.core.content.ContextCompat;

import com.apexdrive.launcher.utils.PreferenceHelper;
import com.apexdrive.launcher.utils.TripAccumulator;
import com.apexdrive.launcher.utils.UnitMath;

/**
 * Speed, heading, coordinates and trip from the GPS provider only.
 *
 * Network and passive fixes have no speed and a poor position, so they are never used
 * for speed, heading or trip (P-19). They are used only by {@link #getLastLocation()}
 * as a coarse position for weather when no GPS fix exists yet.
 *
 * All listener callbacks arrive on the main thread. Call {@link #start()} once the
 * location permission is granted and {@link #stop()} when the manager is no longer needed.
 * While the launcher is behind another app call {@link #setUiVisible(boolean)} with false:
 * the trip keeps counting but no UI callbacks are made.
 */
public class GpsTelemetryManager implements LocationListener {

    public interface TelemetryListener {
        void onSpeedUpdated(int speed, String unit);
        void onHeadingUpdated(String headingText, float bearingDegrees);
        /** altitudeMeters and accuracyMeters are {@link Double#NaN}/{@link Float#NaN} when the fix has none. */
        void onCoordinatesUpdated(double latitude, double longitude, double altitudeMeters, float accuracyMeters);
        void onTripUpdated(double distanceKm, long elapsedMinutes);
        /** satelliteCount is the number of satellites used in the fix, or -1 when the platform does not report it. */
        void onGpsStatusChanged(boolean hasFix, int satelliteCount);
    }

    /** Real receiver state, so the UI can tell "no signal yet" from "permission denied" from "GPS off". */
    public enum GpsState { NO_PERMISSION, PROVIDER_OFF, SEARCHING, FIX }

    public interface StateListener {
        void onGpsStateChanged(GpsState state, int satellitesUsed);
    }

    /** Fixes less accurate than this are ignored for speed, heading and trip. */
    private static final float MAX_ACCURACY_M = 50f;
    /** Fixes older than this (monotonic clock) are ignored. */
    private static final long MAX_FIX_AGE_MS = 10_000L;
    /** No fix for this long means the receiver is searching again. */
    private static final long FIX_LOST_MS = 8_000L;
    private static final long GPS_INTERVAL_MS = 1000L;
    /** Trip is written to preferences at most this often, and on stop. */
    private static final long TRIP_SAVE_INTERVAL_MS = 5_000L;

    private final Context context;
    private final LocationManager locationManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private TelemetryListener listener;
    private StateListener stateListener;

    private boolean isMph = false;
    private boolean started = false;
    private boolean uiVisible = true;

    private Location lastLocation = null;   // last accepted GPS fix
    private Location prevFix = null;        // baseline for the next trip segment
    private final PreferenceHelper prefHelper;
    private final TripAccumulator trip;
    private long lastTripSaveMs = 0L;
    private float lastBearing = 0.0f;
    private float lastSpeedMps = 0.0f;

    private GpsState state = GpsState.SEARCHING;
    private int satellitesUsed = -1;
    private Object gnssCallback;            // GnssStatus.Callback on API 24+
    private GpsStatus.Listener legacyStatusListener; // API 21 to 23

    private final Runnable fixLost = new Runnable() {
        @Override public void run() {
            if (state == GpsState.FIX) setState(GpsState.SEARCHING);
        }
    };

    public GpsTelemetryManager(Context context) {
        this.context = context.getApplicationContext();
        this.locationManager = (LocationManager) this.context.getSystemService(Context.LOCATION_SERVICE);
        // Trip survives process death and recreate() (P-20).
        this.prefHelper = new PreferenceHelper(this.context);
        this.trip = new TripAccumulator(prefHelper.getTripDistanceMeters(), prefHelper.getTripMovingMillis());
    }

    public void setTelemetryListener(TelemetryListener listener) {
        this.listener = listener;
    }

    public void setStateListener(StateListener stateListener) {
        this.stateListener = stateListener;
    }

    public GpsState getGpsState() {
        return state;
    }

    public void setUseMph(boolean useMph) {
        this.isMph = useMph;
        if (listener != null && uiVisible && lastLocation != null) {
            listener.onSpeedUpdated(UnitMath.speedForDisplay(lastSpeedMps, isMph), isMph ? "MPH" : "KM/H");
        }
    }

    /**
     * Last GPS fix, or a coarse last-known position from another provider when there is none.
     * The coarse position is meant for weather only.
     */
    @SuppressLint("MissingPermission")
    public Location getLastLocation() {
        if (lastLocation != null) return lastLocation;
        if (locationManager == null || !hasPermission()) return null;
        try {
            Location l = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (l == null) l = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (l == null) l = locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER);
            return l;
        } catch (Exception e) {
            return null;
        }
    }

    public void resetTrip() {
        trip.reset();
        saveTrip();
        prevFix = null;
        publishTrip();
    }

    /**
     * Show or hide telemetry. While hidden, GPS keeps running (the trip must keep counting
     * while a navigation app is in front) but no UI callbacks are made.
     */
    public void setUiVisible(boolean visible) {
        if (uiVisible == visible) return;
        uiVisible = visible;
        if (visible) publishAll();
    }

    private boolean hasPermission() {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Safe to call repeatedly (for example from onResume and after a permission result). */
    @SuppressLint("MissingPermission")
    public void start() {
        if (locationManager == null || started) return;
        if (!hasPermission()) {
            setState(GpsState.NO_PERMISSION);
            return;
        }
        try {
            locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, GPS_INTERVAL_MS, 0.0f, this, Looper.getMainLooper());
            started = true;
            registerSatelliteStatus();
            publishTrip(); // show the restored trip before the first fix
            setState(locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    ? GpsState.SEARCHING : GpsState.PROVIDER_OFF);
        } catch (SecurityException e) {
            setState(GpsState.NO_PERMISSION);
        } catch (Exception e) {
            // No GPS provider on this unit.
            setState(GpsState.PROVIDER_OFF);
        }
    }

    public void stop() {
        mainHandler.removeCallbacks(fixLost);
        if (locationManager != null) {
            try {
                locationManager.removeUpdates(this);
            } catch (Exception ignored) {
            }
            unregisterSatelliteStatus();
        }
        started = false;
        prevFix = null; // do not bridge the time while updates were off
        saveTrip();
    }

    @SuppressLint("MissingPermission")
    @SuppressWarnings("deprecation")
    private void registerSatelliteStatus() {
        try {
            if (Build.VERSION.SDK_INT >= 24) {
                GnssStatus.Callback cb = new GnssStatus.Callback() {
                    @Override public void onSatelliteStatusChanged(GnssStatus status) {
                        int used = 0;
                        for (int i = 0; i < status.getSatelliteCount(); i++) {
                            if (status.usedInFix(i)) used++;
                        }
                        onSatellitesUsed(used);
                    }
                };
                gnssCallback = cb;
                locationManager.registerGnssStatusCallback(cb);
            } else {
                legacyStatusListener = new GpsStatus.Listener() {
                    @Override public void onGpsStatusChanged(int event) {
                        if (event != GpsStatus.GPS_EVENT_SATELLITE_STATUS) return;
                        try {
                            GpsStatus gs = locationManager.getGpsStatus(null);
                            if (gs == null) return;
                            int used = 0;
                            for (GpsSatellite s : gs.getSatellites()) {
                                if (s.usedInFix()) used++;
                            }
                            onSatellitesUsed(used);
                        } catch (Exception ignored) {
                        }
                    }
                };
                locationManager.addGpsStatusListener(legacyStatusListener);
            }
        } catch (Exception ignored) {
            // Satellite count stays unknown (-1).
        }
    }

    @SuppressWarnings("deprecation")
    private void unregisterSatelliteStatus() {
        try {
            if (Build.VERSION.SDK_INT >= 24) {
                if (gnssCallback != null) {
                    locationManager.unregisterGnssStatusCallback((GnssStatus.Callback) gnssCallback);
                }
            } else if (legacyStatusListener != null) {
                locationManager.removeGpsStatusListener(legacyStatusListener);
            }
        } catch (Exception ignored) {
        }
        gnssCallback = null;
        legacyStatusListener = null;
        satellitesUsed = -1;
    }

    private void onSatellitesUsed(int used) {
        if (used == satellitesUsed) return;
        satellitesUsed = used;
        notifyState();
    }

    private void setState(GpsState newState) {
        if (state == newState) return;
        state = newState;
        notifyState();
    }

    private void notifyState() {
        if (stateListener != null) stateListener.onGpsStateChanged(state, satellitesUsed);
        if (listener != null) listener.onGpsStatusChanged(state == GpsState.FIX, satellitesUsed);
    }

    private static long ageMs(Location l) {
        return (SystemClock.elapsedRealtimeNanos() - l.getElapsedRealtimeNanos()) / 1_000_000L;
    }

    @Override
    public void onLocationChanged(Location location) {
        if (location == null) return;
        if (!LocationManager.GPS_PROVIDER.equals(location.getProvider())) return;
        if (ageMs(location) > MAX_FIX_AGE_MS) return;
        if (location.hasAccuracy() && location.getAccuracy() > MAX_ACCURACY_M) return;

        mainHandler.removeCallbacks(fixLost);
        mainHandler.postDelayed(fixLost, FIX_LOST_MS);
        setState(GpsState.FIX);

        float speedMps = -1f;
        if (location.hasSpeed()) {
            speedMps = location.getSpeed();
        } else if (prevFix != null) {
            long dtMs = (location.getElapsedRealtimeNanos() - prevFix.getElapsedRealtimeNanos()) / 1_000_000L;
            if (dtMs > 0 && dtMs <= 5000) {
                speedMps = prevFix.distanceTo(location) / (dtMs / 1000f);
            }
        }
        lastSpeedMps = Math.max(0f, speedMps);

        boolean moving = lastSpeedMps >= (float) TripAccumulator.MOVING_MPS;
        if (location.hasBearing() && moving) {
            lastBearing = location.getBearing();
        } else if (prevFix != null && moving && prevFix.distanceTo(location) >= 1.5f) {
            float b = prevFix.bearingTo(location);
            lastBearing = b < 0 ? b + 360f : b;
        }

        if (prevFix != null) {
            long dtMs = (location.getElapsedRealtimeNanos() - prevFix.getElapsedRealtimeNanos()) / 1_000_000L;
            trip.addSegment(prevFix.distanceTo(location), dtMs, speedMps);
            long nowMs = SystemClock.elapsedRealtime();
            if (nowMs - lastTripSaveMs >= TRIP_SAVE_INTERVAL_MS) {
                lastTripSaveMs = nowMs;
                saveTrip();
            }
        }
        prevFix = location;
        lastLocation = location;

        if (uiVisible) publishAll();
    }

    private void publishAll() {
        if (listener == null || lastLocation == null) return;
        listener.onSpeedUpdated(UnitMath.speedForDisplay(lastSpeedMps, isMph), isMph ? "MPH" : "KM/H");
        listener.onHeadingUpdated(bearingToDirection(lastBearing) + " " + Math.round(lastBearing) + "°", lastBearing);
        Location l = lastLocation;
        listener.onCoordinatesUpdated(l.getLatitude(), l.getLongitude(),
                l.hasAltitude() ? l.getAltitude() : Double.NaN,
                l.hasAccuracy() ? l.getAccuracy() : Float.NaN);
        publishTrip();
    }

    private void saveTrip() {
        prefHelper.setTrip(trip.getDistanceMeters(), trip.getMovingMillis());
    }

    private void publishTrip() {
        if (listener == null || !uiVisible) return;
        listener.onTripUpdated(trip.getDistanceMeters() / 1000.0, trip.getMovingMinutes());
    }

    private String bearingToDirection(float bearing) {
        if (bearing < 0) bearing += 360f;
        String[] directions = {"N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW", "N"};
        int index = Math.round((bearing % 360f) / 22.5f);
        return directions[index % 16];
    }

    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}

    @Override public void onProviderEnabled(String provider) {
        if (LocationManager.GPS_PROVIDER.equals(provider)) setState(GpsState.SEARCHING);
    }

    @Override public void onProviderDisabled(String provider) {
        if (LocationManager.GPS_PROVIDER.equals(provider)) {
            mainHandler.removeCallbacks(fixLost);
            setState(GpsState.PROVIDER_OFF);
        }
    }
}
