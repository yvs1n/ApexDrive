package com.apexdrive.launcher.services;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.os.Build;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The only place that reaches non-SDK Bluetooth and system APIs by reflection.
 *
 * Android 9+ restricts non-SDK interfaces, and head-unit ROMs differ, so every lookup is cached
 * and its outcome (ok / missing / blocked) is logged once and kept for the diagnostics string.
 * Every method has a safe fallback: "unknown" never means "connected".
 *
 * Verify in the car: read {@link #outcomeSummary()} (part of BluetoothWatcher.getDiagnostics())
 * to see which hidden calls work on the unit.
 */
final class BtHiddenApi {

    private static final String TAG = "ApexBtHidden";

    private static final Map<String, Object> methods = new ConcurrentHashMap<>();
    private static final Map<String, String> outcomes = new ConcurrentHashMap<>();
    private static final Object MISSING = new Object();

    private BtHiddenApi() {}

    private static Method lookup(String feature, Class<?> owner, String name, Class<?>... params) {
        String cacheKey = feature + "@" + owner.getName();
        Object cached = methods.get(cacheKey);
        if (cached != null) return cached == MISSING ? null : (Method) cached;
        try {
            Method m = owner.getMethod(name, params);
            methods.put(cacheKey, m);
            record(feature, "lookup ok");
            return m;
        } catch (NoSuchMethodException e) {
            methods.put(cacheKey, MISSING);
            record(feature, "missing");
        } catch (Throwable t) {
            methods.put(cacheKey, MISSING);
            record(feature, "blocked: " + t.getClass().getSimpleName());
        }
        return null;
    }

    private static void record(String feature, String outcome) {
        String prev = outcomes.put(feature, outcome);
        if (!outcome.equals(prev)) Log.i(TAG, feature + ": " + outcome);
    }

    /** Hidden BluetoothDevice.isConnected(); null when unavailable. */
    static Boolean deviceIsConnected(BluetoothDevice device) {
        if (device == null) return null;
        Method m = lookup("device.isConnected", BluetoothDevice.class, "isConnected");
        if (m == null) return null;
        try {
            return (Boolean) m.invoke(device);
        } catch (Throwable t) {
            record("device.isConnected", "invoke failed: " + t.getClass().getSimpleName());
            return null;
        }
    }

    /** Hidden BluetoothDevice.connect(); true only if the call was made without error. */
    static boolean deviceConnect(BluetoothDevice device) {
        if (device == null) return false;
        Method m = lookup("device.connect", BluetoothDevice.class, "connect");
        if (m == null) return false;
        try {
            m.invoke(device);
            record("device.connect", "invoked");
            return true;
        } catch (Throwable t) {
            record("device.connect", "invoke failed: " + t.getClass().getSimpleName());
            return false;
        }
    }

    /** Hidden BluetoothAdapter.getConnectionState(); STATE_DISCONNECTED when unavailable. */
    static int adapterConnectionState(BluetoothAdapter adapter) {
        if (adapter == null) return BluetoothProfile.STATE_DISCONNECTED;
        Method m = lookup("adapter.getConnectionState", BluetoothAdapter.class, "getConnectionState");
        if (m == null) return BluetoothProfile.STATE_DISCONNECTED;
        try {
            Object r = m.invoke(adapter);
            return r instanceof Integer ? (Integer) r : BluetoothProfile.STATE_DISCONNECTED;
        } catch (Throwable t) {
            record("adapter.getConnectionState", "invoke failed: " + t.getClass().getSimpleName());
            return BluetoothProfile.STATE_DISCONNECTED;
        }
    }

    /** Hidden connect(BluetoothDevice) on an A2DP or Headset proxy. */
    static boolean profileConnect(BluetoothProfile proxy, BluetoothDevice device) {
        if (proxy == null || device == null) return false;
        Method m = lookup("profile.connect", proxy.getClass(), "connect", BluetoothDevice.class);
        if (m == null) return false;
        try {
            m.invoke(proxy, device);
            record("profile.connect", "invoked");
            return true;
        } catch (Throwable t) {
            record("profile.connect", "invoke failed: " + t.getClass().getSimpleName());
            return false;
        }
    }

    /** android.os.SystemProperties.get; "" when unavailable. Diagnostics only. */
    static String systemProperty(String key) {
        try {
            Method m = lookup("SystemProperties.get", Class.forName("android.os.SystemProperties"),
                    "get", String.class, String.class);
            if (m == null) return "";
            Object r = m.invoke(null, key, "");
            return r instanceof String ? (String) r : "";
        } catch (Throwable t) {
            record("SystemProperties.get", "failed: " + t.getClass().getSimpleName());
            return "";
        }
    }

    static String outcomeSummary() {
        StringBuilder sb = new StringBuilder("API ").append(Build.VERSION.SDK_INT);
        for (Map.Entry<String, String> e : new TreeMap<>(outcomes).entrySet()) {
            sb.append(" | ").append(e.getKey()).append(": ").append(e.getValue());
        }
        return sb.toString();
    }
}
