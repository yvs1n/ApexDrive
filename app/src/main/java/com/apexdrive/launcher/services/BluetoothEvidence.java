package com.apexdrive.launcher.services;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure decision logic for "is a phone connected?". No Android imports, so it can be unit tested.
 *
 * A device counts as connected only when at least one per-device signal is present:
 * a profile connection state (A2DP or HFP), an ACL link, or the hidden isConnected() flag.
 * Adapter-level flags, audio routing and vendor Settings values are NOT signals; callers may
 * log them but must not feed them here.
 */
public final class BluetoothEvidence {

    public enum Signal {
        A2DP("A2DP profile"),
        HFP("HFP profile"),
        ACL("ACL link"),
        DEVICE_IS_CONNECTED("BluetoothDevice.isConnected()");

        public final String label;

        Signal(String label) {
            this.label = label;
        }
    }

    private final Map<String, EnumSet<Signal>> signals = new LinkedHashMap<>();

    private static String key(String address) {
        return address == null ? "" : address.toUpperCase(Locale.US);
    }

    public synchronized void add(String address, Signal signal) {
        if (address == null || signal == null) return;
        String k = key(address);
        EnumSet<Signal> set = signals.get(k);
        if (set == null) {
            set = EnumSet.noneOf(Signal.class);
            signals.put(k, set);
        }
        set.add(signal);
    }

    public synchronized void remove(String address, Signal signal) {
        if (address == null || signal == null) return;
        String k = key(address);
        EnumSet<Signal> set = signals.get(k);
        if (set == null) return;
        set.remove(signal);
        if (set.isEmpty()) signals.remove(k);
    }

    /** Replaces every address holding {@code signal} with exactly {@code addresses}. */
    public synchronized void replace(Signal signal, Collection<String> addresses) {
        for (EnumSet<Signal> set : signals.values()) set.remove(signal);
        if (addresses != null) {
            for (String a : addresses) add(a, signal);
        }
        List<String> empty = new ArrayList<>();
        for (Map.Entry<String, EnumSet<Signal>> e : signals.entrySet()) {
            if (e.getValue().isEmpty()) empty.add(e.getKey());
        }
        for (String k : empty) signals.remove(k);
    }

    public synchronized void clear() {
        signals.clear();
    }

    public synchronized boolean isConnected(String address) {
        EnumSet<Signal> set = signals.get(key(address));
        return set != null && !set.isEmpty();
    }

    public synchronized EnumSet<Signal> signalsFor(String address) {
        EnumSet<Signal> set = signals.get(key(address));
        return set == null ? EnumSet.noneOf(Signal.class) : EnumSet.copyOf(set);
    }

    /** Connected addresses; devices with an audio or call profile come before link-only ones. */
    public synchronized List<String> connectedAddresses() {
        List<String> withProfile = new ArrayList<>();
        List<String> linkOnly = new ArrayList<>();
        for (Map.Entry<String, EnumSet<Signal>> e : signals.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            if (hasProfile(e.getValue())) withProfile.add(e.getKey());
            else linkOnly.add(e.getKey());
        }
        withProfile.addAll(linkOnly);
        return withProfile;
    }

    /** The device the UI should show, or null when there is no evidence of any connection. */
    public synchronized String primaryAddress() {
        List<String> all = connectedAddresses();
        return all.isEmpty() ? null : all.get(0);
    }

    private static boolean hasProfile(EnumSet<Signal> set) {
        return set.contains(Signal.A2DP) || set.contains(Signal.HFP);
    }

    /** Human readable profile detail built only from signals that were actually seen. */
    public static String describeProfiles(EnumSet<Signal> set) {
        boolean a2dp = set.contains(Signal.A2DP);
        boolean hfp = set.contains(Signal.HFP);
        if (a2dp && hfp) return "A2DP audio + hands-free calls";
        if (a2dp) return "A2DP audio";
        if (hfp) return "Hands-free calls";
        return "Link up, audio and call profiles not confirmed";
    }

    /** Masks all but the last two bytes of a MAC address for logs and bug reports. */
    public static String maskAddress(String address) {
        if (address == null || address.length() < 17) return "??";
        return "xx:xx:xx:xx:" + address.substring(12).toUpperCase(Locale.US);
    }

    /** One line saying which signals fired for which (masked) device. */
    public synchronized String describe() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, EnumSet<Signal>> e : signals.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            if (sb.length() > 0) sb.append("; ");
            sb.append(maskAddress(e.getKey())).append('=');
            boolean first = true;
            for (Signal s : e.getValue()) {
                if (!first) sb.append('+');
                sb.append(s.name());
                first = false;
            }
        }
        return sb.length() == 0 ? "no per-device signal" : sb.toString();
    }
}
