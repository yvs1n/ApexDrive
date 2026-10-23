package com.apexdrive.launcher.services;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.SystemClock;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Listens to the car data of TW (Topway) head units. Their CAN-bus values, the outside
 * temperature among them, arrive as messages from the vendor framework class
 * {@code android.tw.john.TWUtil}, not as broadcasts or Settings keys. Which message carries
 * the temperature differs by car, so this records every message for a short time and the
 * head unit report shows them.
 *
 * TWUtil is reached by reflection; on other head units {@link #isAvailable()} is false.
 */
public final class TwCarData {

    private static final String TW_CLASS = "android.tw.john.TWUtil";
    private static final int MAX_VALUES_PER_ID = 8;

    /** What one message id carried while listening. */
    private static final class Seen {
        int count;
        final Set<String> values = new LinkedHashSet<>();
    }

    private TwCarData() {}

    public static boolean isAvailable() {
        try {
            Class.forName(TW_CLASS);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Records every TWUtil message for {@code millis} and returns a text summary, one line per
     * message id. Blocks; call from a worker thread.
     */
    public static String capture(long millis) {
        StringBuilder sb = new StringBuilder();
        final Map<Integer, Seen> seen = new TreeMap<>();
        HandlerThread thread = new HandlerThread("TwCarData");
        thread.start();
        Handler handler = new Handler(thread.getLooper()) {
            @Override
            public void handleMessage(Message msg) {
                synchronized (seen) {
                    Seen s = seen.get(msg.what);
                    if (s == null) {
                        s = new Seen();
                        seen.put(msg.what, s);
                    }
                    s.count++;
                    if (s.values.size() < MAX_VALUES_PER_ID) {
                        s.values.add(msg.arg1 + "," + msg.arg2 + (msg.obj != null ? "," + describe(msg.obj) : ""));
                    }
                }
            }
        };

        Object tw = null;
        Class<?> c;
        try {
            c = Class.forName(TW_CLASS);
            tw = c.getConstructor().newInstance();
            // Every id: the one that carries the temperature is not known yet.
            short[] ids = new short[0x10000];
            for (int i = 0; i < ids.length; i++) ids[i] = (short) i;
            Object opened = c.getMethod("open", short[].class).invoke(tw, (Object) ids);
            sb.append("open=").append(opened).append('\n');
            c.getMethod("addHandler", String.class, Handler.class).invoke(tw, "apexdrive-capture", handler);
            c.getMethod("start").invoke(tw);
            long end = SystemClock.elapsedRealtime() + millis;
            while (SystemClock.elapsedRealtime() < end) {
                SystemClock.sleep(200);
            }
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            sb.append("TWUtil failed: ").append(cause.getClass().getSimpleName())
                    .append(": ").append(cause.getMessage()).append('\n');
        } finally {
            if (tw != null) {
                invokeQuietly(tw, "removeHandler", new Class<?>[]{String.class}, "apexdrive-capture");
                invokeQuietly(tw, "stop", new Class<?>[0]);
                invokeQuietly(tw, "close", new Class<?>[0]);
            }
            thread.quitSafely();
        }

        List<String> lines = new ArrayList<>();
        synchronized (seen) {
            for (Map.Entry<Integer, Seen> e : seen.entrySet()) {
                lines.add(String.format(java.util.Locale.US, "0x%04x x%d %s",
                        e.getKey(), e.getValue().count, e.getValue().values));
            }
        }
        sb.append(lines.size()).append(" message ids in ").append(millis / 1000).append(" s\n");
        for (String l : lines) sb.append(l).append('\n');
        return sb.toString();
    }

    private static void invokeQuietly(Object target, String name, Class<?>[] types, Object... args) {
        try {
            Method m = target.getClass().getMethod(name, types);
            m.invoke(target, args);
        } catch (Throwable ignored) {}
    }

    static String describe(Object o) {
        if (o instanceof byte[]) {
            byte[] b = (byte[]) o;
            StringBuilder hex = new StringBuilder("bytes[");
            for (int i = 0; i < b.length && i < 32; i++) {
                if (i > 0) hex.append(' ');
                hex.append(String.format(java.util.Locale.US, "%02x", b[i] & 0xff));
            }
            if (b.length > 32) hex.append(" …");
            return hex.append(']').toString();
        }
        if (o instanceof int[]) return java.util.Arrays.toString((int[]) o);
        String s = String.valueOf(o);
        return s.length() > 60 ? s.substring(0, 60) + "…" : s;
    }
}
