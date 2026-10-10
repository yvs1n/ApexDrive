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
        void onHeadingUpdated(String headingText, float bearingDegrees);
        void onCoordinatesUpdated(double latitude, double longitude, double altitudeMeters, float accuracyMeters);
        void onTripUpdated(double distanceKm, long elapsedMinutes);
        void onGpsStatusChanged(boolean hasFix, int satelliteCount);
    }

    private final Context context;
    private final LocationManager locationManager;
    private TelemetryListener listener;

    private boolean isMph = false;
    private Location lastLocation = null;
    private double totalDistanceMeters = 0.0;
    private long tripStartTime = 0;
    private float lastBearing = 0.0f;

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
            // Request ultra-responsive, zero-latency GPS updates (0ms delay, 0m distance delta)
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        50L,   // High-frequency 20Hz polling
                        0.0f,  // Instant updates even on micro movements
                        this
                );
            }

            // Also register passive/network provider as immediate secondary fallback
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        500L,
                        0.0f,
                        this
                );
            }

            // Read last known location immediately so screen is never blank on start
            Location lastKnown = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (lastKnown == null) {
                lastKnown = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            }
            if (lastKnown != null) {
                onLocationChanged(lastKnown);
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

        listener.onGpsStatusChanged(true, 8);

        // Immediate Speed calculation (location.getSpeed() returns meters/second)
        float speedMps = location.hasSpeed() ? location.getSpeed() : 0.0f;
        
        // If speed is not reported by GPS chip but we have two successive locations
        if (!location.hasSpeed() && lastLocation != null && location.getTime() > lastLocation.getTime()) {
            float dist = lastLocation.distanceTo(location);
            long timeDeltaSec = (location.getTime() - lastLocation.getTime()) / 1000;
            if (timeDeltaSec > 0 && dist < 100) {
                speedMps = dist / timeDeltaSec;
            }
        }

        int displaySpeed;
        if (isMph) {
            displaySpeed = Math.round(speedMps * 2.23694f);
        } else {
            displaySpeed = Math.round(speedMps * 3.6f);
        }

        // Standing still filter: speed jitter < 1.5 km/h is treated as stationary
        if (displaySpeed < 2) {
            displaySpeed = 0;
        }

        listener.onSpeedUpdated(displaySpeed, isMph ? "MPH" : "KM/H");

        // High-frequency Bearing / Compass calculation
        float bearing = lastBearing;
        if (location.hasBearing() && location.getBearing() != 0.0f) {
            bearing = location.getBearing();
            lastBearing = bearing;
        } else if (lastLocation != null && lastLocation.distanceTo(location) >= 1.5f) {
            // Dynamically interpolate bearing between successive GPS coordinates
            bearing = lastLocation.bearingTo(location);
            if (bearing < 0) bearing += 360f;
            lastBearing = bearing;
        }

        String direction = bearingToDirection(bearing);
        listener.onHeadingUpdated(direction + " " + Math.round(bearing) + "°", bearing);

        // Live Coordinates & Altitude for dynamic navigation HUD
        double altitude = location.hasAltitude() ? location.getAltitude() : 0.0;
        float accuracy = location.hasAccuracy() ? location.getAccuracy() : 5.0f;
        listener.onCoordinatesUpdated(location.getLatitude(), location.getLongitude(), altitude, accuracy);

        // Trip distance accumulation
        if (lastLocation != null) {
            float dist = lastLocation.distanceTo(location);
            if (dist > 0.5f && dist < 300.0f) { // Discard GPS teleport spikes
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
        String[] directions = {"N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW", "N"};
        int index = Math.round((bearing % 360f) / 22.5f);
        return directions[index % 16];
    }

    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
    @Override public void onProviderEnabled(String provider) {
        if (listener != null) listener.onGpsStatusChanged(true, 8);
    }
    @Override public void onProviderDisabled(String provider) {
        if (listener != null) listener.onGpsStatusChanged(false, 0);
    }
}
