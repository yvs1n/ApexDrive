package com.apexdrive.launcher.utils;

/**
 * Pure trip-distance logic, kept free of Android classes so it can be unit tested.
 *
 * Distance between two consecutive GPS fixes is counted when the implied speed is
 * physically plausible. A gap in fixes (tunnel, dropout) is therefore still counted
 * as a straight line instead of being dropped, but a jump after a long pause is not.
 */
public final class TripAccumulator {

    /** Fastest speed a road vehicle is assumed to reach, in metres per second (about 250 km/h). */
    public static final double MAX_PLAUSIBLE_MPS = 70.0;
    /** Longest gap between fixes that is bridged with a straight line. */
    public static final long MAX_GAP_MS = 10 * 60 * 1000L;
    /** Below this speed the vehicle is treated as stationary and GPS jitter is ignored. */
    public static final double MOVING_MPS = 1.0;

    private double distanceMeters;
    private long movingMillis;

    public TripAccumulator() {
        this(0.0, 0L);
    }

    public TripAccumulator(double distanceMeters, long movingMillis) {
        this.distanceMeters = Math.max(0.0, distanceMeters);
        this.movingMillis = Math.max(0L, movingMillis);
    }

    /**
     * @param segmentMeters straight-line distance from the previous fix
     * @param dtMillis      time since the previous fix (monotonic clock)
     * @param speedMps      speed at the new fix, or a negative value when unknown
     * @return true when the segment was added to the trip
     */
    public boolean addSegment(double segmentMeters, long dtMillis, double speedMps) {
        if (dtMillis <= 0 || dtMillis > MAX_GAP_MS || segmentMeters <= 0.0) return false;
        double impliedMps = segmentMeters / (dtMillis / 1000.0);
        if (impliedMps > MAX_PLAUSIBLE_MPS) return false;
        double effectiveMps = speedMps >= 0 ? speedMps : impliedMps;
        if (effectiveMps < MOVING_MPS) return false;
        distanceMeters += segmentMeters;
        movingMillis += dtMillis;
        return true;
    }

    public double getDistanceMeters() {
        return distanceMeters;
    }

    public long getMovingMillis() {
        return movingMillis;
    }

    public long getMovingMinutes() {
        return movingMillis / 60000L;
    }

    public void reset() {
        distanceMeters = 0.0;
        movingMillis = 0L;
    }
}
