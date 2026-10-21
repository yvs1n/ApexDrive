package com.apexdrive.launcher.utils;

/**
 * Decides whether the car counts as driving, from speed samples.
 * Enters driving after the speed stays above {@link #ENTER_KMH} for {@link #ENTER_HOLD_MS},
 * and leaves it after the speed stays below {@link #EXIT_KMH} for {@link #EXIT_HOLD_MS}.
 * Pure logic with an injected clock so it can be unit tested.
 */
public final class DrivingState {

    public interface Listener {
        void onDrivingChanged(boolean driving);
    }

    public static final float ENTER_KMH = 8f;
    public static final float EXIT_KMH = 3f;
    public static final long ENTER_HOLD_MS = 3000;
    public static final long EXIT_HOLD_MS = 10000;

    private boolean driving;
    private long candidateSince = -1;
    private Listener listener;

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public boolean isDriving() {
        return driving;
    }

    /** Feed a speed sample in km/h with a monotonic time in milliseconds. */
    public void onSpeed(float speedKmh, long nowMs) {
        if (!driving) {
            if (speedKmh > ENTER_KMH) {
                if (candidateSince < 0) candidateSince = nowMs;
                if (nowMs - candidateSince >= ENTER_HOLD_MS) set(true);
            } else {
                candidateSince = -1;
            }
        } else {
            if (speedKmh < EXIT_KMH) {
                if (candidateSince < 0) candidateSince = nowMs;
                if (nowMs - candidateSince >= EXIT_HOLD_MS) set(false);
            } else {
                candidateSince = -1;
            }
        }
    }

    /** Called when there is no fix: keep the current state but restart the timers. */
    public void onNoFix() {
        candidateSince = -1;
    }

    private void set(boolean value) {
        driving = value;
        candidateSince = -1;
        if (listener != null) listener.onDrivingChanged(value);
    }
}
