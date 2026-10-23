package com.apexdrive.launcher.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The Default look must equal the resource tokens, and every look must keep text readable. */
public class LookPaletteTest {

    private static final Set<String> failures = new LinkedHashSet<>();

    @Test
    public void defaultLookMatchesResourceTokens() throws Exception {
        assertTokens("src/main/res/values/colors_ad.xml", Look.defaults(false));
        assertTokens("src/main/res/values-night/colors_ad.xml", Look.defaults(true));
        assertTrue(Look.defaults(true).isResourceLook());
    }

    private static void assertTokens(String path, Look look) throws Exception {
        String xml = new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("<color name=\"ad_([a-z_0-9]+)\">#([0-9A-Fa-f]{6,8})</color>").matcher(xml);
        int count = 0;
        while (m.find()) {
            Look.Token t = Look.Token.valueOf(m.group(1).toUpperCase(Locale.ROOT));
            String hex = m.group(2);
            int value = (int) Long.parseLong(hex.length() == 6 ? "FF" + hex : hex, 16);
            assertEquals(path + " " + t, Integer.toHexString(value), Integer.toHexString(look.color(t)));
            count++;
        }
        assertEquals(Look.Token.values().length, count);
    }

    @Test
    public void everyLookKeepsTextReadable() {
        failures.clear();
        for (int night = 0; night < 2; night++) {
            for (int style = 0; style < Look.STYLE_NAMES.length; style++) {
                for (int accent : Look.ACCENT_IDS) {
                    for (int opacity = 0; opacity < Look.OPACITY_NAMES.length; opacity++) {
                        Look look = new Look(style, accent, 1, opacity, 1, night == 1);
                        for (int wp = 0; wp < Look.WALLPAPER_CUSTOM; wp++) {
                            checkLook(look, wp);
                        }
                    }
                }
            }
        }
        assertTrue(failures.size() + " problems:\n" + String.join("\n", failures), failures.isEmpty());
    }

    private static void checkLook(Look look, int wallpaper) {
        String name = Look.STYLE_NAMES[look.style] + (look.night ? " night" : " day")
                + " accent " + Integer.toHexString(look.accent) + " opacity " + look.opacity + " wallpaper " + wallpaper;
        int[] stops = Look.wallpaperStops(wallpaper, look.night);
        int[] behind = stops != null ? stops : new int[]{look.color(Look.Token.CANVAS)};
        for (int back : behind) {
            int card = Look.over(look.color(Look.Token.SURFACE_1), back);
            int glass = Look.over(look.color(Look.Token.GLASS_SOLID), back);
            int row = Look.over(look.color(Look.Token.SURFACE_2), card);
            for (int bg : new int[]{card, glass, row}) {
                require(name + " label 1", Look.over(look.color(Look.Token.LABEL_1), bg), bg, 7.0);
                require(name + " label 2", look.color(Look.Token.LABEL_2), bg, 4.5);
                require(name + " label 3", look.color(Look.Token.LABEL_3), bg, 3.0);
            }
            require(name + " accent text", look.color(Look.Token.ACCENT_TEXT), card, 4.5);
            require(name + " accent fill", look.color(Look.Token.ACCENT), card, 3.0);
        }
        require(name + " on accent", look.color(Look.Token.ON_ACCENT), look.color(Look.Token.ACCENT), 4.5);
        int segment = look.color(Look.Token.SEGMENT_SELECTED);
        require(name + " segment", Look.over(look.color(Look.Token.LABEL_1), segment), segment, 7.0);
    }

    private static void require(String what, int fg, int bg, double min) {
        double c = Look.contrast(fg, bg);
        if (c < min) failures.add(what.replaceAll(" wallpaper \\d+", "") + String.format(Locale.ROOT, " contrast %.2f < %.1f (%08x on %08x)", c, min, fg, bg));
    }
}
