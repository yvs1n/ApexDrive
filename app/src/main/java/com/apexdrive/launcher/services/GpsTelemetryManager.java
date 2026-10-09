package com.apexdrive.launcher.services;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;

public class GpsTelemetryManager implements LocationListener {

    public interface TelemetryListener {
        void onSpeedUpdated(int speed, String unit);
        void onHeadingUpdated(String headingText);
        void onTripUpdated(double distanceKm, long elapsedMinutes);
        void onGpsStatusChanged(boolean hasFix);
    }

    private final Context context;
    private final LocationManager locationManager;
    private TelemetryListener listener;

    private boolean isMph = false;
    private Location lastLocation = null;
    private double totalDistanceMeters = 0.0;
    private long tripStartTime = 0;

    public GpsTelemetryManager(Context context) {
        this.context = context;
        this.locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
    }

    public void setTelemetryListener(TelemetryListener listener) {
        this.listener = listener;
    }

    public void setUseMph(boolean useMph) {
        this.isMph = useMph;
    }

    @SuppressLint("MissingPermission")
    public void start() {
        if (locationManager == null) return;
        tripStartTime = System.currentTimeMillis();

        try {
            // Request high accuracy GPS updates every 500ms
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        500L,
                        0.5f,
                        this
                );
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void stop() {
        if (locationManager != null) {
            try {
                locationManager.removeUpdates(this);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        if (location == null || listener == null) return;

        listener.onGpsStatusChanged(true);

        // Speed calculation (location.getSpeed() returns meters/second)
        float speedMps = location.hasSpeed() ? location.getSpeed() : 0.0f;
        int displaySpeed;
        if (isMph) {
            displaySpeed = Math.round(speedMps * 2.23694f);
        } else {
            displaySpeed = Math.round(speedMps * 3.6f);
        }

        // Noise filter: standstill jitter < 2 km/h is treated as 0
        if (displaySpeed < 2) {
            displaySpeed = 0;
        }

        listener.onSpeedUpdated(displaySpeed, isMph ? "MPH" : "KM/H");

        // Heading / Bearing calculation
        if (location.hasBearing()) {
            float bearing = location.getBearing();
            String direction = bearingToDirection(bearing);
            listener.onHeadingUpdated(direction + " " + Math.round(bearing) + "°");
        }

        // Trip distance accumulation
        if (lastLocation != null) {
            float dist = lastLocation.distanceTo(location);
            if (dist > 1.0f && dist < 500.0f) { // Discard GPS teleport spikes
                totalDistanceMeters += dist;
            }
        }
        lastLocation = location;

        long elapsedMinutes = (System.currentTimeMillis() - tripStartTime) / (1000 * 60);
        double distanceKm = totalDistanceMeters / 1000.0;
        listener.onTripUpdated(distanceKm, elapsedMinutes);
    }

    private String bearingToDirection(float bearing) {
        if (bearing < 0) bearing += 360f;
        String[] directions = {"N", "NE", "E", "SE", "S", "SW", "W", "NW", "N"};
        int index = Math.round(bearing / 45f);
        return directions[index % 8];
    }

    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
    @Override public void onProviderEnabled(String provider) {
        if (listener != null) listener.onGpsStatusChanged(true);
    }
    @Override public void onProviderDisabled(String provider) {
        if (listener != null) listener.onGpsStatusChanged(false);
    }
}
