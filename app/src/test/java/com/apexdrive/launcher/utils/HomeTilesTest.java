package com.apexdrive.launcher.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

import java.util.Arrays;

public class HomeTilesTest {

    @Test
    public void defaultKeepsTodaysOrder() {
        assertEquals(Arrays.asList(HomeTiles.ALL), HomeTiles.parse(HomeTiles.DEFAULT).visible());
        assertEquals(HomeTiles.DEFAULT, HomeTiles.parse(null).serialize());
    }

    @Test
    public void repairsUnknownAndMissingTiles() {
        HomeTiles t = HomeTiles.parse("phone,bogus,phone,-speed");
        assertEquals(Arrays.asList("phone", "speed", "drive", "media"), t.order());
        assertFalse(t.isShown("speed"));
        assertEquals("phone,-speed,drive,media", t.serialize());
    }

    @Test
    public void moveUpAndHide() {
        HomeTiles t = HomeTiles.parse(HomeTiles.DEFAULT);
        t.moveUp("media");
        t.setShown("speed", false);
        assertEquals("drive,media,-speed,phone", t.serialize());
        assertEquals(Arrays.asList("drive", "media", "phone"), t.visible());
        t.moveUp("drive");
        assertEquals(Arrays.asList("media", "phone", "drive"), t.visible());
    }
}
