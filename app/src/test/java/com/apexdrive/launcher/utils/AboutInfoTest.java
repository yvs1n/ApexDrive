package com.apexdrive.launcher.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AboutInfoTest {

    @Test
    public void listsCarAndVendorAppsOnly() {
        assertTrue(AboutInfo.isCarApp("com.zjinnova.zlink"));
        assertTrue(AboutInfo.isCarApp("com.tw.bt"));
        assertTrue(AboutInfo.isCarApp("com.syu.canbus"));
        assertTrue(AboutInfo.isCarApp("com.android.fmradio"));
        assertFalse(AboutInfo.isCarApp("com.spotify.music"));
        assertFalse(AboutInfo.isCarApp("com.google.android.apps.maps"));
        assertFalse(AboutInfo.isCarApp("com.android.settings"));
        assertFalse(AboutInfo.isCarApp("com.android.bluetoothmidiservice"));
    }

    @Test
    public void formatsSizesAndDurations() {
        assertEquals("1.9 GB", AboutInfo.bytes(2_040_000_000L));
        assertEquals("512 MB", AboutInfo.bytes(512L << 20));
        assertEquals("Less than a minute", AboutInfo.duration(30_000));
        assertEquals("2 h 14 min", AboutInfo.duration((2 * 60 + 14) * 60_000L));
        assertEquals("3 days 1 h", AboutInfo.duration(73 * 3_600_000L));
        assertEquals("Unknown", AboutInfo.or("  ", "Unknown"));
    }
}
