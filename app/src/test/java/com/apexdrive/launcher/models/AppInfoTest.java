package com.apexdrive.launcher.models;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class AppInfoTest {

    @Test
    public void threeArgConstructorLeavesActivityNameNull() {
        AppInfo info = new AppInfo("Maps", "net.osmand.plus", null);
        assertEquals("Maps", info.getLabel());
        assertEquals("net.osmand.plus", info.getPackageName());
        assertNull(info.getActivityName());
        assertNull(info.getIcon());
    }

    @Test
    public void fourArgConstructorKeepsActivityName() {
        AppInfo info = new AppInfo("Radio", "com.example.radio", "com.example.radio.Main", null);
        assertEquals("com.example.radio.Main", info.getActivityName());
    }
}
