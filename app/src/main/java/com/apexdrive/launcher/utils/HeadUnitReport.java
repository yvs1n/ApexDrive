package com.apexdrive.launcher.utils;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Plain-text description of the head unit: its firmware, Settings values, system properties,
 * vendor apps and the launcher's own Bluetooth and temperature diagnostics. Head units differ
 * in where they keep the phone connection and the outside temperature; this report shows
 * where this one keeps them, without a computer or adb.
 *
 * Bluetooth and Wi-Fi addresses are masked and keys that could hold secrets are left out.
 * Builds on a worker thread: it starts one getprop process.
 */
public final class HeadUnitReport {

    public static final String FILE_NAME = "headunit-report.txt";

    /** Properties and package names worth reading; the rest is AOSP noise. */
    private static final Pattern INTERESTING = Pattern.compile(
            "(?i).*(can|temp|bt|blue|car|mcu|fyt|syu|hct|goc|zlink|tlink|carplay|link|radio|vendor|tw|zj|rw\\.|"
                    + "ro\\.product|ro\\.build\\.(display|version\\.incremental)|ro\\.board|ro\\.hardware|"
                    + "persist\\.sys|sys\\.).*");
    private static final Pattern SECRET = Pattern.compile(
            "(?i).*(pass|pwd|token|secret|key|imei|serial|ssid|android_id|account|gsf|adid).*");
    private static final Pattern MAC = Pattern.compile("([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}");

    private HeadUnitReport() {}

    public static String build(Context context, String bluetooth, String temperature) {
        StringBuilder sb = new StringBuilder();
        section(sb, "Device");
        sb.append("manufacturer=").append(Build.MANUFACTURER).append(" brand=").append(Build.BRAND)
                .append(" model=").append(Build.MODEL).append('\n');
        sb.append("board=").append(Build.BOARD).append(" hardware=").append(Build.HARDWARE)
                .append(" device=").append(Build.DEVICE).append(" product=").append(Build.PRODUCT).append('\n');
        sb.append("firmware=").append(Build.DISPLAY).append(" android=").append(Build.VERSION.RELEASE)
                .append(" sdk=").append(Build.VERSION.SDK_INT).append('\n');
        try {
            PackageInfo self = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            sb.append("apexdrive=").append(self.versionName).append(" (").append(self.versionCode).append(")\n");
        } catch (Exception ignored) {}

        section(sb, "Temperature");
        sb.append(temperature);
        appendThermalZones(sb);

        section(sb, "Bluetooth");
        sb.append(bluetooth);

        section(sb, "Settings: system");
        appendSettings(sb, context, Settings.System.CONTENT_URI);
        section(sb, "Settings: global");
        appendSettings(sb, context, Settings.Global.CONTENT_URI);

        section(sb, "System properties");
        appendProperties(sb);

        section(sb, "Sensors");
        appendSensors(sb, context);

        section(sb, "Vendor classes");
        appendVendorClasses(sb);

        section(sb, "Vendor apps");
        appendVendorApps(sb, context);
        return sb.toString();
    }

    /** Writes the report to the app's folder on the internal storage; returns the file or null. */
    public static File save(Context context, String report) {
        File dir = context.getExternalFilesDir(null);
        if (dir == null) dir = context.getFilesDir();
        File file = new File(dir, FILE_NAME);
        try (Writer w = new OutputStreamWriter(new FileOutputStream(file), "UTF-8")) {
            w.write(report);
            return file;
        } catch (Exception e) {
            return null;
        }
    }

    private static void section(StringBuilder sb, String title) {
        sb.append("\n== ").append(title).append(" ==\n");
    }

    static String mask(String value) {
        if (value == null) return "null";
        return MAC.matcher(value).replaceAll("xx:xx:xx:xx:xx:xx");
    }

    private static void appendSettings(StringBuilder sb, Context context, Uri table) {
        Cursor c = null;
        try {
            c = context.getContentResolver().query(table, new String[]{"name", "value"}, null, null, null);
            if (c == null) {
                sb.append("not readable\n");
                return;
            }
            List<String> rows = new ArrayList<>();
            while (c.moveToNext()) {
                String name = c.getString(0);
                if (name == null || SECRET.matcher(name).matches()) continue;
                rows.add(name + "=" + mask(c.getString(1)));
            }
            Collections.sort(rows);
            for (String r : rows) sb.append(r).append('\n');
        } catch (Exception e) {
            sb.append("not readable: ").append(e.getClass().getSimpleName()).append('\n');
        } finally {
            if (c != null) c.close();
        }
    }

    private static void appendProperties(StringBuilder sb) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec("getprop");
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) {
                // Lines look like "[name]: [value]".
                int end = line.indexOf(']');
                String name = end > 1 ? line.substring(1, end) : line;
                if (!INTERESTING.matcher(name).matches() || SECRET.matcher(name).matches()) continue;
                sb.append(mask(line)).append('\n');
            }
            r.close();
        } catch (Exception e) {
            sb.append("not readable: ").append(e.getClass().getSimpleName()).append('\n');
        } finally {
            if (p != null) p.destroy();
        }
    }

    /** Chip temperatures, to tell them apart from an outside temperature. */
    private static void appendThermalZones(StringBuilder sb) {
        File[] zones = new File("/sys/class/thermal").listFiles();
        if (zones == null) return;
        for (File z : zones) {
            if (!z.getName().startsWith("thermal_zone")) continue;
            sb.append(z.getName()).append(' ').append(readFirstLine(new File(z, "type")))
                    .append('=').append(readFirstLine(new File(z, "temp"))).append('\n');
        }
    }

    private static String readFirstLine(File f) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new java.io.FileInputStream(f)))) {
            String l = r.readLine();
            return l == null ? "" : l.trim();
        } catch (Exception e) {
            return "?";
        }
    }

    private static void appendSensors(StringBuilder sb, Context context) {
        try {
            SensorManager sm = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
            if (sm == null) return;
            for (Sensor s : sm.getSensorList(Sensor.TYPE_ALL)) {
                sb.append(s.getType()).append(' ').append(s.getName()).append(" (").append(s.getVendor()).append(")\n");
            }
        } catch (Exception ignored) {}
    }

    /**
     * Vendor framework classes that carry car data. TW (Topway) units pass CAN-bus values such
     * as the outside temperature through TWUtil; its constants and methods show how.
     */
    private static final String[] VENDOR_CLASSES = {
            "android.tw.john.TWUtil", "android.tw.john.TWUtilEx", "android.tw.TWUtil",
            "com.tw.john.TWUtil", "android.os.TWUtil", "android.microntek.CarManager",
            "android.os.SystemProperties"
    };

    private static void appendVendorClasses(StringBuilder sb) {
        for (String name : VENDOR_CLASSES) {
            Class<?> c;
            try {
                c = Class.forName(name);
            } catch (Throwable t) {
                continue;
            }
            sb.append(name).append('\n');
            if (name.equals("android.os.SystemProperties")) continue; // only shows it is reachable
            try {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    int m = f.getModifiers();
                    sb.append("  field ").append(f.getType().getSimpleName()).append(' ').append(f.getName());
                    if (java.lang.reflect.Modifier.isStatic(m) && java.lang.reflect.Modifier.isFinal(m)) {
                        try {
                            f.setAccessible(true);
                            sb.append(" = ").append(f.get(null));
                        } catch (Throwable ignored) {}
                    }
                    sb.append('\n');
                }
                for (java.lang.reflect.Constructor<?> k : c.getDeclaredConstructors()) {
                    sb.append("  new(").append(paramNames(k.getParameterTypes())).append(")\n");
                }
                for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                    sb.append("  ").append(m.getReturnType().getSimpleName()).append(' ').append(m.getName())
                            .append('(').append(paramNames(m.getParameterTypes())).append(")\n");
                }
            } catch (Throwable t) {
                sb.append("  not readable: ").append(t.getClass().getSimpleName()).append('\n');
            }
        }
    }

    private static String paramNames(Class<?>[] types) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < types.length; i++) {
            if (i > 0) b.append(", ");
            b.append(types[i].getSimpleName());
        }
        return b.toString();
    }

    /** Apps that are not part of plain Android, with the components that hint at what they do. */
    private static void appendVendorApps(StringBuilder sb, Context context) {
        PackageManager pm = context.getPackageManager();
        List<PackageInfo> packages;
        try {
            packages = pm.getInstalledPackages(PackageManager.GET_PROVIDERS
                    | PackageManager.GET_RECEIVERS | PackageManager.GET_SERVICES);
        } catch (Exception e) {
            // A large package list can exceed the binder limit; retry without components.
            packages = pm.getInstalledPackages(0);
        }
        Collections.sort(packages, (a, b) -> a.packageName.compareTo(b.packageName));
        for (PackageInfo p : packages) {
            String name = p.packageName;
            if (name.startsWith("com.android.") || name.startsWith("com.google.android.")
                    || name.equals("android") || name.equals(context.getPackageName())) continue;
            ApplicationInfo ai = p.applicationInfo;
            boolean system = ai != null && (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            CharSequence label = ai != null ? pm.getApplicationLabel(ai) : "";
            sb.append(name).append(" \"").append(label).append("\" ").append(p.versionName)
                    .append(system ? " system" : "").append('\n');
            if (p.providers != null) {
                for (ProviderInfo pi : p.providers) {
                    if (pi.exported) sb.append("  provider ").append(pi.authority).append('\n');
                }
            }
            appendComponents(sb, "service", p.services);
            appendComponents(sb, "receiver", p.receivers);
        }
    }

    private static void appendComponents(StringBuilder sb, String kind, ComponentInfo[] items) {
        if (items == null) return;
        for (ComponentInfo ci : items) {
            if (ci.exported) sb.append("  ").append(kind).append(' ').append(ci.name).append('\n');
        }
    }
}
