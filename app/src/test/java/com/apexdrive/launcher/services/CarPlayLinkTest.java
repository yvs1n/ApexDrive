package com.apexdrive.launcher.services;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class CarPlayLinkTest {

    @Test
    public void linkTypeIsNamed() {
        assertEquals("Wireless CarPlay", CarPlayLink.describe("carplay_wireless"));
        assertEquals("CarPlay", CarPlayLink.describe("carplay_wired"));
        assertEquals("Wireless Android Auto", CarPlayLink.describe("auto_wireless"));
        assertEquals("", CarPlayLink.describe(""));
        assertEquals("", CarPlayLink.describe(null));
    }
}
