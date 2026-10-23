package com.apexdrive.launcher.utils;

import java.util.ArrayList;
import java.util.List;

/**
 * Order and visibility of the four Home tiles, saved as one string such as
 * "drive,speed,-media,phone" (a leading '-' hides a tile). Unknown or missing tiles are
 * repaired so all four always appear exactly once.
 */
public final class HomeTiles {

    public static final String DRIVE = "drive", SPEED = "speed", MEDIA = "media", PHONE = "phone";
    public static final String[] ALL = {DRIVE, SPEED, MEDIA, PHONE};
    public static final String DEFAULT = "drive,speed,media,phone";

    private final List<String> order = new ArrayList<>();
    private final List<String> hidden = new ArrayList<>();

    public static HomeTiles parse(String saved) {
        HomeTiles t = new HomeTiles();
        if (saved != null) {
            for (String part : saved.split(",")) {
                String p = part.trim();
                boolean hide = p.startsWith("-");
                if (hide) p = p.substring(1);
                if (indexOf(p) < 0 || t.order.contains(p)) continue;
                t.order.add(p);
                if (hide) t.hidden.add(p);
            }
        }
        for (String id : ALL) if (!t.order.contains(id)) t.order.add(id);
        return t;
    }

    /** Tile ids in display order, hidden ones included. */
    public List<String> order() {
        return new ArrayList<>(order);
    }

    /** Visible tile ids in display order. */
    public List<String> visible() {
        List<String> out = new ArrayList<>();
        for (String id : order) if (!hidden.contains(id)) out.add(id);
        return out;
    }

    public boolean isShown(String id) {
        return !hidden.contains(id);
    }

    public void setShown(String id, boolean shown) {
        hidden.remove(id);
        if (!shown && indexOf(id) >= 0) hidden.add(id);
    }

    /** Moves a tile one place earlier; the first tile wraps to the end. */
    public void moveUp(String id) {
        int i = order.indexOf(id);
        if (i < 0) return;
        order.remove(i);
        if (i == 0) order.add(id);
        else order.add(i - 1, id);
    }

    public String serialize() {
        StringBuilder sb = new StringBuilder();
        for (String id : order) {
            if (sb.length() > 0) sb.append(',');
            if (hidden.contains(id)) sb.append('-');
            sb.append(id);
        }
        return sb.toString();
    }

    private static int indexOf(String id) {
        for (int i = 0; i < ALL.length; i++) if (ALL[i].equals(id)) return i;
        return -1;
    }
}
