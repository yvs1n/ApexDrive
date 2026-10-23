package com.apexdrive.launcher.utils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Calendar;
import java.util.TimeZone;

public class SunTimesTest {

    private static Calendar dubai(int month, int day, int hour, int minute) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("Asia/Dubai"));
        c.clear();
        c.set(2026, month, day, hour, minute);
        return c;
    }

    @Test
    public void dubaiSunTimesAreWithinFiveMinutes() {
        // 23 October 2026 in Dubai: sunrise about 06:17, sunset about 17:50.
        int[] t = SunTimes.localMinutes(25.20, 55.27, dubai(Calendar.OCTOBER, 23, 12, 0));
        assertTrue("sunrise " + t[0], Math.abs(t[0] - (6 * 60 + 17)) <= 5);
        assertTrue("sunset " + t[1], Math.abs(t[1] - (17 * 60 + 50)) <= 5);
    }

    @Test
    public void nightIsBetweenSunsetAndSunrise() {
        assertFalse(SunTimes.isNight(25.20, 55.27, dubai(Calendar.OCTOBER, 23, 12, 0)));
        assertTrue(SunTimes.isNight(25.20, 55.27, dubai(Calendar.OCTOBER, 23, 19, 0)));
        assertTrue(SunTimes.isNight(25.20, 55.27, dubai(Calendar.OCTOBER, 23, 5, 0)));
    }

    @Test
    public void unknownPositionUsesFallbackTimes() {
        assertFalse(SunTimes.isNight(Double.NaN, Double.NaN, dubai(Calendar.JUNE, 1, 18, 0)));
        assertTrue(SunTimes.isNight(Double.NaN, Double.NaN, dubai(Calendar.JUNE, 1, 18, 45)));
    }
}
