package com.apexdrive.launcher.services;

/**
 * Whether the head unit's CarPlay app has a phone linked. On TW (Topway) units with TLINK5
 * (Zlink) the iPhone connects to the unit's own Bluetooth module, which Android's Bluetooth
 * stack never sees. TLINK5 sets {@code rw.cp.link.state} to 1 while a phone is linked and
 * {@code zj.phonelink.type} to the kind of link, for example carplay_wireless.
 */
public final class CarPlayLink {

    private CarPlayLink() {}

    public static boolean isActive() {
        return "1".equals(BtHiddenApi.systemProperty("rw.cp.link.state").trim());
    }

    /** "CarPlay", "Wireless CarPlay", "Android Auto" ... or "" when unknown. */
    public static String kind() {
        return describe(BtHiddenApi.systemProperty("zj.phonelink.type"));
    }

    static String describe(String type) {
        if (type == null) return "";
        String t = type.trim().toLowerCase(java.util.Locale.US);
        boolean wireless = t.contains("wireless");
        if (t.contains("carplay")) return wireless ? "Wireless CarPlay" : "CarPlay";
        if (t.contains("auto")) return wireless ? "Wireless Android Auto" : "Android Auto";
        return "";
    }
}
