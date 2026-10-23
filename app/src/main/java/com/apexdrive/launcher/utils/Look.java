package com.apexdrive.launcher.utils;

/**
 * The look of the launcher: a style preset, accent, corner roundness, card opacity and text
 * size, resolved into one colour per design token for Day or Night.
 *
 * Plain Java (no Android classes) so the palettes can be checked for contrast in unit tests.
 * The Default style with no overrides gives exactly the tokens in res/values and
 * res/values-night (PaletteTest compares them).
 */
public final class Look {

    /** Design tokens, in the order of res/values/colors_ad.xml. */
    public enum Token {
        CANVAS, SURFACE_1, SURFACE_2, SURFACE_3, FILL_PRESSED, SEGMENT_SELECTED,
        LABEL_1, LABEL_2, LABEL_3, LABEL_4, SEPARATOR, OUTLINE,
        ACCENT, ACCENT_PRESSED, ON_ACCENT, ACCENT_TEXT,
        OK, CAUTION, ALERT, ON_OK, ON_CAUTION, ON_ALERT,
        GLASS, GLASS_SOLID, GLASS_EDGE, GLASS_HI, SCRIM, DIM, CLEAR_GLASS
    }

    static final int[] DAY = {
            0xFFE4E8EC, 0xFFFFFFFF, 0xFFF2F4F6, 0xFFE3E7EB, 0xFFD3D9E0, 0xFFFFFFFF,
            0xFF0E1116, 0xFF3D4550, 0xFF56606C, 0xFF9AA1AB, 0xFFD6DBE1, 0xFF6F7886,
            0xFF007A8E, 0xFF006577, 0xFFFFFFFF, 0xFF006E80,
            0xFF08743F, 0xFF8F5D00, 0xFFC42E1C, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF,
            0xCCF7F8FA, 0xFFF4F6F8, 0x1A0E1116, 0xE6FFFFFF, 0x520E1116, 0x8C000000, 0x1FFFFFFF
    };

    static final int[] NIGHT = {
            0xFF0B0E12, 0xFF161A20, 0xFF1E232B, 0xFF2A303A, 0xFF363D49, 0xFF3A414D,
            0xF5FFFFFF, 0xFFB4BCC7, 0xFF929BA8, 0xFF5E6672, 0xFF2C323C, 0xFF717A87,
            0xFF45C4D8, 0xFF34A9BC, 0xFF03181C, 0xFF45C4D8,
            0xFF3DD68C, 0xFFF5B83D, 0xFFFF6B5E, 0xFF04200F, 0xFF2A1B00, 0xFF2A0804,
            0xD1141920, 0xFF1A1F27, 0x1AFFFFFF, 0x29FFFFFF, 0x80000000, 0x8C000000, 0x1FFFFFFF
    };

    // --- Styles ---------------------------------------------------------------------------

    public static final int STYLE_DEFAULT = 0, STYLE_OLED = 1, STYLE_GLASS = 2,
            STYLE_CONTRAST = 3, STYLE_WARM = 4;
    public static final String[] STYLE_NAMES = {"Default", "OLED", "Glass", "Contrast", "Warm"};
    public static final String[] STYLE_DETAILS = {
            "The standard ApexDrive look",
            "True black at night, paper white by day",
            "See-through cards over a wallpaper",
            "Strongest text and outlined cards",
            "Amber accent on warm surfaces"
    };

    /** Settings a style preset brings with it: {radius, opacity, wallpaper}. */
    private static final int[][] STYLE_DEFAULTS = {
            {1, 0, 0},
            {1, 0, 0},
            {2, 2, 1},
            {1, 0, 0},
            {1, 0, 0},
    };

    public static int styleRadius(int style) { return STYLE_DEFAULTS[clampStyle(style)][0]; }
    public static int styleOpacity(int style) { return STYLE_DEFAULTS[clampStyle(style)][1]; }
    public static int styleWallpaper(int style) { return STYLE_DEFAULTS[clampStyle(style)][2]; }

    /** {token ordinal, colour} pairs laid over the Default palette. Index: style, then 0 = Day, 1 = Night. */
    private static int[][][] styleOverrides() {
        return new int[][][]{
                {{}, {}},
                { // OLED
                        pairs(Token.CANVAS, 0xFFFFFFFF, Token.SURFACE_1, 0xFFF3F5F7, Token.SURFACE_2, 0xFFE9ECEF,
                                Token.SURFACE_3, 0xFFDFE3E8, Token.GLASS_SOLID, 0xFFF3F5F7, Token.SEGMENT_SELECTED, 0xFFFFFFFF),
                        pairs(Token.CANVAS, 0xFF000000, Token.SURFACE_1, 0xFF0A0B0D, Token.SURFACE_2, 0xFF141619,
                                Token.SURFACE_3, 0xFF1F2226, Token.FILL_PRESSED, 0xFF2C3036, Token.SEGMENT_SELECTED, 0xFF353A42,
                                Token.SEPARATOR, 0xFF1F2226, Token.GLASS_SOLID, 0xFF0A0B0D, Token.GLASS_EDGE, 0x24FFFFFF)
                },
                { // Glass: the see-through part comes from its card opacity and wallpaper
                        pairs(Token.GLASS_EDGE, 0x290E1116, Token.SURFACE_2, 0xFFF4F6F8),
                        pairs(Token.GLASS_EDGE, 0x2EFFFFFF, Token.SURFACE_1, 0xFF12161C)
                },
                { // High contrast
                        pairs(Token.CANVAS, 0xFFFFFFFF, Token.SURFACE_1, 0xFFFFFFFF, Token.SURFACE_2, 0xFFF0F0F0,
                                Token.SURFACE_3, 0xFFE0E0E0, Token.FILL_PRESSED, 0xFFC7C7C7, Token.SEGMENT_SELECTED, 0xFFFFFFFF,
                                Token.LABEL_1, 0xFF000000, Token.LABEL_2, 0xFF1F1F1F, Token.LABEL_3, 0xFF333333,
                                Token.LABEL_4, 0xFF5C5C5C, Token.SEPARATOR, 0xFF6B6B6B, Token.OUTLINE, 0xFF333333,
                                Token.GLASS_SOLID, 0xFFFFFFFF, Token.GLASS_EDGE, 0xFF333333,
                                Token.ACCENT, 0xFF005A69, Token.ACCENT_PRESSED, 0xFF004450, Token.ACCENT_TEXT, 0xFF004E5B),
                        pairs(Token.CANVAS, 0xFF000000, Token.SURFACE_1, 0xFF000000, Token.SURFACE_2, 0xFF1A1A1A,
                                Token.SURFACE_3, 0xFF2E2E2E, Token.FILL_PRESSED, 0xFF474747, Token.SEGMENT_SELECTED, 0xFF575757,
                                Token.LABEL_1, 0xFFFFFFFF, Token.LABEL_2, 0xFFF0F0F0, Token.LABEL_3, 0xFFD6D6D6,
                                Token.LABEL_4, 0xFFA3A3A3, Token.SEPARATOR, 0xFF8A8A8A, Token.OUTLINE, 0xFFBDBDBD,
                                Token.GLASS_SOLID, 0xFF000000, Token.GLASS_EDGE, 0xFFBDBDBD,
                                Token.ACCENT, 0xFF5CE1F2, Token.ACCENT_PRESSED, 0xFF45C4D8, Token.ACCENT_TEXT, 0xFF5CE1F2,
                                Token.ON_ACCENT, 0xFF000000)
                },
                { // Warm
                        pairs(Token.CANVAS, 0xFFEFE7DC, Token.SURFACE_1, 0xFFFFFBF5, Token.SURFACE_2, 0xFFF7F0E6,
                                Token.SURFACE_3, 0xFFEADFD1, Token.FILL_PRESSED, 0xFFDCCFBF, Token.SEGMENT_SELECTED, 0xFFFFFBF5,
                                Token.LABEL_1, 0xFF1C140C, Token.LABEL_2, 0xFF4A3D30, Token.LABEL_3, 0xFF655749,
                                Token.LABEL_4, 0xFFA39585, Token.SEPARATOR, 0xFFDDD2C4, Token.OUTLINE, 0xFF85786A,
                                Token.GLASS_SOLID, 0xFFF8F2EA, Token.GLASS_EDGE, 0x1A1C140C,
                                Token.ACCENT, 0xFF9A5B00, Token.ACCENT_PRESSED, 0xFF7F4B00, Token.ACCENT_TEXT, 0xFF8A5200,
                                Token.ON_ACCENT, 0xFFFFFFFF),
                        pairs(Token.CANVAS, 0xFF100C08, Token.SURFACE_1, 0xFF1C1611, Token.SURFACE_2, 0xFF261E17,
                                Token.SURFACE_3, 0xFF33291F, Token.FILL_PRESSED, 0xFF41352A, Token.SEGMENT_SELECTED, 0xFF4A3D30,
                                Token.LABEL_1, 0xFFFFF6EC, Token.LABEL_2, 0xFFD2C4B4, Token.LABEL_3, 0xFFAD9F8F,
                                Token.LABEL_4, 0xFF6E6256, Token.SEPARATOR, 0xFF352B22, Token.OUTLINE, 0xFF85786A,
                                Token.GLASS_SOLID, 0xFF1A140F, Token.GLASS_EDGE, 0x1AFFF0E0,
                                Token.ACCENT, 0xFFE5A93C, Token.ACCENT_PRESSED, 0xFFC99130, Token.ACCENT_TEXT, 0xFFE8B04A,
                                Token.ON_ACCENT, 0xFF2A1B00)
                },
        };
    }

    private static int[] pairs(Object... kv) {
        int[] out = new int[kv.length];
        for (int i = 0; i < kv.length; i += 2) {
            out[i] = ((Token) kv[i]).ordinal();
            out[i + 1] = (Integer) kv[i + 1];
        }
        return out;
    }

    // --- Accents --------------------------------------------------------------------------

    /** Keeps the accent of the chosen style. */
    public static final int ACCENT_STYLE = 0;
    public static final int COLOR_TEAL = 0xFF45C4D8;
    public static final int COLOR_VIOLET = 0xFFA78BFA;
    public static final int COLOR_ROSE = 0xFFF472B6;

    /** Accent ids (as saved by PreferenceHelper.setAccentColor) with their Night and Day colours. */
    public static final int[] ACCENT_IDS = {ACCENT_STYLE, COLOR_TEAL, PreferenceHelper.COLOR_AMBER,
            PreferenceHelper.COLOR_BLUE, PreferenceHelper.COLOR_GREEN, PreferenceHelper.COLOR_RED,
            COLOR_VIOLET, COLOR_ROSE, PreferenceHelper.COLOR_WHITE};
    public static final String[] ACCENT_NAMES = {"Style", "Teal", "Amber", "Blue", "Green", "Red",
            "Violet", "Rose", "Platinum"};
    private static final int[] ACCENT_NIGHT = {0, 0xFF45C4D8, 0xFFE5A93C, 0xFF60A5FA, 0xFF34D399, 0xFFF87171,
            0xFFA78BFA, 0xFFF472B6, 0xFFE2E8F0};
    private static final int[] ACCENT_DAY = {0, 0xFF007A8E, 0xFF9A5B00, 0xFF1D5FD1, 0xFF08743F, 0xFFC42E1C,
            0xFF6D28D9, 0xFFBE185D, 0xFF3D4550};

    public static int accentIndex(int id) {
        for (int i = 0; i < ACCENT_IDS.length; i++) if (ACCENT_IDS[i] == id) return i;
        return 0;
    }

    // --- Geometry and type ----------------------------------------------------------------

    public static final String[] RADIUS_NAMES = {"Square", "Default", "Round"};
    private static final float[] RADIUS_SCALE = {0.4f, 1f, 1.35f};
    public static final String[] OPACITY_NAMES = {"Solid", "Frosted", "Clear"};
    private static final float[] OPACITY = {1f, 0.85f, 0.68f};
    public static final String[] TEXT_NAMES = {"Small", "Default", "Large"};
    private static final float[] TEXT_SCALE = {0.9f, 1f, 1.12f};
    public static final String[] CLOCK_NAMES = {"Small", "Default", "Large"};
    private static final int[] CLOCK_SP = {22, 28, 34};

    public static int clockSp(int size) { return CLOCK_SP[clamp(size, CLOCK_SP.length, 1)]; }

    // --- Wallpapers -----------------------------------------------------------------------

    public static final int WALLPAPER_NONE = 0, WALLPAPER_CUSTOM = 8;
    public static final String[] WALLPAPER_NAMES = {"None", "Dusk", "Aurora", "Ember", "Ocean",
            "Graphite", "Forest", "Sunset", "Your image"};
    /** Gradient stops, top-left to bottom-right, for each built-in wallpaper (index 1 to 7). */
    private static final int[][] WALLPAPER_NIGHT = {
            null,
            {0xFF0B1026, 0xFF261A45, 0xFF40214C},
            {0xFF03141A, 0xFF06323A, 0xFF0E4A3D},
            {0xFF120806, 0xFF34130C, 0xFF52210F},
            {0xFF04101C, 0xFF0A2742, 0xFF0F3C64},
            {0xFF0E0F12, 0xFF1C1F25, 0xFF2A2E36},
            {0xFF060E08, 0xFF102414, 0xFF1A361E},
            {0xFF140A1A, 0xFF371330, 0xFF5E2626},
    };
    private static final int[][] WALLPAPER_DAY = {
            null,
            {0xFFE6EAF7, 0xFFE9E1F2, 0xFFF3E4EC},
            {0xFFDDF1EE, 0xFFE1F2E8, 0xFFEAF4E1},
            {0xFFFBEDE4, 0xFFF7E1D3, 0xFFF1D6C4},
            {0xFFE2ECF7, 0xFFD8E5F3, 0xFFCDDFF0},
            {0xFFEEF0F2, 0xFFE1E4E8, 0xFFD3D8DE},
            {0xFFE8F1E4, 0xFFDDEAD6, 0xFFD0E2C7},
            {0xFFFCEEE6, 0xFFF7E0E4, 0xFFEEDAEE},
    };

    /** Gradient stops for a built-in wallpaper, or null for None and Your image. */
    public static int[] wallpaperStops(int wallpaper, boolean night) {
        if (wallpaper <= WALLPAPER_NONE || wallpaper >= WALLPAPER_CUSTOM) return null;
        return (night ? WALLPAPER_NIGHT : WALLPAPER_DAY)[wallpaper].clone();
    }

    /** Soft glow laid over the top-right of a built-in wallpaper. */
    public static int wallpaperGlow(int wallpaper, boolean night) {
        int[] stops = wallpaperStops(wallpaper, night);
        if (stops == null) return 0;
        return night ? withAlpha(stops[2], 0x66) : withAlpha(0xFFFFFFFF, 0x99);
    }

    // --- Instance -------------------------------------------------------------------------

    public final int style, accent, radius, opacity, textSize;
    public final boolean night;
    private final int[] colors;

    public Look(int style, int accent, int radius, int opacity, int textSize, boolean night) {
        this.style = clampStyle(style);
        this.accent = accentIndex(accent) == 0 ? ACCENT_STYLE : accent;
        this.radius = clamp(radius, RADIUS_SCALE.length, 1);
        this.opacity = clamp(opacity, OPACITY.length, 0);
        this.textSize = clamp(textSize, TEXT_SCALE.length, 1);
        this.night = night;
        this.colors = resolve();
    }

    /** The look of a fresh install: exactly the design-system tokens. */
    public static Look defaults(boolean night) {
        return new Look(STYLE_DEFAULT, ACCENT_STYLE, 1, 0, 1, night);
    }

    public int color(Token t) {
        return colors[t.ordinal()];
    }

    public float radiusScale() { return RADIUS_SCALE[radius]; }
    public float cardAlpha() { return OPACITY[opacity]; }
    public float textScale() { return TEXT_SCALE[textSize]; }

    /** High contrast draws an outline around cards so they stand apart from the canvas. */
    public boolean outlinedCards() { return style == STYLE_CONTRAST; }

    /** True when every colour, radius and text size equals the resources, so nothing needs repainting. */
    public boolean isResourceLook() {
        return style == STYLE_DEFAULT && accent == ACCENT_STYLE && radius == 1 && opacity == 0 && textSize == 1;
    }

    public boolean sameSkin(Look o) {
        return o != null && style == o.style && accent == o.accent && radius == o.radius
                && opacity == o.opacity && textSize == o.textSize && night == o.night;
    }

    private int[] resolve() {
        int[] c = (night ? NIGHT : DAY).clone();
        int[] over = styleOverrides()[style][night ? 1 : 0];
        for (int i = 0; i < over.length; i += 2) c[over[i]] = over[i + 1];

        int a = accentIndex(accent);
        if (a != 0) {
            int base = night ? ACCENT_NIGHT[a] : ACCENT_DAY[a];
            c[Token.ACCENT.ordinal()] = base;
            c[Token.ACCENT_TEXT.ordinal()] = night ? base : mix(base, 0xFF000000, 0.1f);
            c[Token.ACCENT_PRESSED.ordinal()] = mix(base, 0xFF000000, night ? 0.14f : 0.18f);
            c[Token.ON_ACCENT.ordinal()] = contrast(0xFFFFFFFF, base) >= contrast(0xFF0B0E12, base)
                    ? 0xFFFFFFFF : 0xFF0B0E12;
        }

        float alpha = OPACITY[opacity];
        if (alpha < 1f) {
            c[Token.SURFACE_1.ordinal()] = scaleAlpha(c[Token.SURFACE_1.ordinal()], alpha);
            c[Token.GLASS_SOLID.ordinal()] = scaleAlpha(c[Token.GLASS_SOLID.ordinal()], Math.min(1f, alpha + 0.14f));
        }
        return c;
    }

    // --- Colour maths (also used by the tests) --------------------------------------------

    public static int withAlpha(int color, int alpha) {
        return (alpha << 24) | (color & 0x00FFFFFF);
    }

    static int scaleAlpha(int color, float f) {
        int a = Math.round(((color >>> 24) & 0xFF) * f);
        return withAlpha(color, a);
    }

    /** Linear mix of two opaque colours; t = 0 gives a, t = 1 gives b. */
    public static int mix(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = Math.round(((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = Math.round((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    /** A translucent colour drawn over an opaque one. */
    public static int over(int top, int bottom) {
        return mix(bottom, top | 0xFF000000, ((top >>> 24) & 0xFF) / 255f);
    }

    public static double luminance(int color) {
        double r = channel((color >> 16) & 0xFF), g = channel((color >> 8) & 0xFF), b = channel(color & 0xFF);
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    private static double channel(int v) {
        double s = v / 255.0;
        return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }

    /** WCAG contrast ratio of two opaque colours. */
    public static double contrast(int a, int b) {
        double la = luminance(a), lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static int clampStyle(int style) {
        return clamp(style, STYLE_NAMES.length, STYLE_DEFAULT);
    }

    private static int clamp(int v, int length, int fallback) {
        return v >= 0 && v < length ? v : fallback;
    }
}
