package com.apexdrive.launcher.services;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class HeadingTest {

    @Test
    public void bearingStaysWithinOneTurn() {
        assertEquals(0, GpsTelemetryManager.roundedBearing(359.6f));
        assertEquals(0, GpsTelemetryManager.roundedBearing(360f));
        assertEquals(4, GpsTelemetryManager.roundedBearing(364f));
        assertEquals(350, GpsTelemetryManager.roundedBearing(-10f));
        assertEquals(304, GpsTelemetryManager.roundedBearing(304.2f));
        assertEquals(0, GpsTelemetryManager.roundedBearing(Float.NaN));
    }

    @Test
    public void directionMatchesDegrees() {
        assertEquals("N", GpsTelemetryManager.bearingToDirection(0));
        assertEquals("N", GpsTelemetryManager.bearingToDirection(355));
        assertEquals("WNW", GpsTelemetryManager.bearingToDirection(294));
        assertEquals("NW", GpsTelemetryManager.bearingToDirection(315));
        assertEquals("S", GpsTelemetryManager.bearingToDirection(180));
    }
}
