package com.apexdrive.launcher.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

/** Guards the public defaults other code and the docs rely on. No Android framework needed. */
public class PreferenceDefaultsTest {

    @Test
    public void speedUnitsAreDistinct() {
        assertNotEquals(PreferenceHelper.UNIT_KMH, PreferenceHelper.UNIT_MPH);
    }

    @Test
    public void accentColorsAreOpaqueAndDistinct() {
        int[] colors = {
                PreferenceHelper.COLOR_AMBER, PreferenceHelper.COLOR_BLUE,
                PreferenceHelper.COLOR_RED, PreferenceHelper.COLOR_GREEN,
                PreferenceHelper.COLOR_WHITE
        };
        for (int i = 0; i < colors.length; i++) {
            assertEquals("alpha of colour " + i, 0xFF, (colors[i] >>> 24) & 0xFF);
            for (int j = i + 1; j < colors.length; j++) {
                assertNotEquals(colors[i], colors[j]);
            }
        }
    }

    @Test
    public void defaultCarPlayAppIsZlink() {
        assertEquals("com.zjinnova.zlink", PreferenceHelper.DEFAULT_CARPLAY_PKG);
    }
}
