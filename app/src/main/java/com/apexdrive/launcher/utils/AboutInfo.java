package com.apexdrive.launcher.utils;

import android.app.ActivityManager;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;

import com.apexdrive.launcher.BuildConfig;
import com.apexdrive.launcher.R;

import java.io.BufferedReader;
import java.io.FileReader;
import java.lang.reflect.Method;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * What Settings > About shows: the app, the car-side signals the head unit passes on, the head
 * unit itself, its hardware modules and the car apps with their versions. Collected off the main
 * thread; values only the activity knows (phone, temperature, GPS) come in through {@link Live}.
 */
public final class AboutInfo {

    public static final class Row {
        public final int icon;
        public final String title;
        public final String detail;
        /** Optional view id for the detail, so the activity can keep updating it. */
        public final int detailId;

        Row(int icon, String title, String detail, int detailId) {
            this.icon = icon;
            this.title = title;
            this.detail = detail;
            this.detailId = detailId;
        }
    }

    public static final class Section {
        public final String title;
        public final List<Row> rows = new ArrayList<>();

        Section(String title) {
            this.title = title;
        }

        Section add(int icon, String title, String detail) {
            rows.add(new Row(icon, title, detail, 0));
            return this;
        }
    }

    /** Values read on the main thread from the activity's watchers. */
    public static final class Live {
        public String phone = "";
        public String temperature = "";
        public String gps = "";
        public String location = "";
        public String carPlayPackage = "";
        public String mapPackage = "";
        public String radioPackage = "";
    }

    /** Car and head-unit vendor packages worth listing, by package name. */
    static final Pattern CAR_APP = Pattern.compile(
            "(?i).*(canbus|mcu|topway|\\.tw\\.|zlink|tlink|carplay|autokit|carlink|phonelink|"
                    + "fyt|syu|hct|goc|microntek|\\.zj\\.|radio|bluetooth|\\.bt\\b|\\.bt\\.|avin|dvr|reverse|backcar).*");

    /** Properties some T3 / TW firmwares use for the MCU version; the first one set wins. */
    static final String[] MCU_PROPERTIES = {
            "ro.mcu.version", "persist.sys.mcu.version", "sys.mcu.version", "persist.sys.mcuversion",
            "ro.build.mcu.version", "sys.tw.mcu.version", "persist.tw.mcu.version", "ro.tw.mcu.version"
    };
    static final String[] MCU_SETTINGS = {"mcu_version", "mcuVersion", "MCU_VERSION"};

    /** Vendor classes that carry CAN-bus data (see HeadUnitReport). */
    static final String[] CAN_CLASSES = {
            "android.tw.john.TWUtil", "android.tw.john.TWUtilEx", "android.tw.TWUtil",
            "com.tw.john.TWUtil", "android.os.TWUtil", "android.microntek.CarManager"
    };

    private static final int MAX_CAR_APPS = 12;

    private AboutInfo() {}

    public static List<Section> collect(Context context, Live live) {
        PackageManager pm = context.getPackageManager();
        List<Section> out = new ArrayList<>();
        out.add(app(context, pm));
        out.add(car(context, pm, live));
        out.add(headUnit(context));
        out.add(modules(context, pm, live));
        out.add(apps(context, pm, live));
        return out;
    }

    // --- Sections ---------------------------------------------------------------------------

    private static Section app(Context context, PackageManager pm) {
        Section s = new Section("ApexDrive");
        try {
            PackageInfo self = pm.getPackageInfo(context.getPackageName(), 0);
            s.add(R.drawable.ic_sym_info, "Version", self.versionName + " (" + self.versionCode + ") · "
                    + (BuildConfig.DEBUG ? "debug build" : "release build"));
            DateFormat date = DateFormat.getDateInstance(DateFormat.MEDIUM);
            s.add(R.drawable.ic_sym_history, "Installed", "Updated " + date.format(new Date(self.lastUpdateTime))
                    + " · first installed " + date.format(new Date(self.firstInstallTime)));
        } catch (Exception ignored) {}
        ResolveInfo home = pm.resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY);
        boolean isHome = home != null && home.activityInfo != null
                && context.getPackageName().equals(home.activityInfo.packageName);
        s.add(R.drawable.ic_sym_home, "Home app", isHome ? "ApexDrive is the default Home app"
                : "Not the default Home app. Press Home and choose ApexDrive, Always");
        s.add(R.drawable.ic_sym_license, "Licence", "MIT. Fonts: SIL Open Font License");
        return s;
    }

    // The head unit runs Android 8.1, where BLUETOOTH is enough; on 12+ getName throws and is caught.
    @android.annotation.SuppressLint("MissingPermission")
    private static Section car(Context context, PackageManager pm, Live live) {
        Section s = new Section("Car");
        String btName = "";
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter != null) btName = adapter.getName();
        } catch (Exception ignored) {}
        s.add(R.drawable.ic_sym_directions_car, "Car name", btName == null || btName.trim().isEmpty()
                ? "Not reported" : btName.trim() + " (the head unit's Bluetooth name)");
        s.add(R.drawable.ic_sym_phone_iphone, "Phone", or(live.phone, "No phone"));
        s.add(R.drawable.ic_sym_thermostat, "Outside temperature", or(live.temperature, "No reading yet"));
        s.add(R.drawable.ic_sym_developer_board, "CAN bus", canBus(pm));
        s.rows.add(new Row(R.drawable.ic_sym_location_on, "Location", or(live.location, "Waiting for GPS"),
                R.id.tvSetLocation));
        return s;
    }

    private static Section headUnit(Context context) {
        Section s = new Section("Head unit");
        String platform = property("ro.board.platform");
        s.add(R.drawable.ic_sym_smartphone, "Model", Build.MANUFACTURER + " " + Build.MODEL
                + " · " + or(platform, Build.HARDWARE) + " · board " + Build.BOARD);
        String patch = Build.VERSION.SDK_INT >= 23 ? " · security patch " + Build.VERSION.SECURITY_PATCH : "";
        s.add(R.drawable.ic_sym_settings, "Android", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")" + patch);
        s.add(R.drawable.ic_sym_factory, "Firmware", Build.DISPLAY + " · built "
                + DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date(Build.TIME)));
        s.add(R.drawable.ic_sym_developer_board, "MCU", or(mcuVersion(context), "Not reported by this unit"));
        s.add(R.drawable.ic_sym_tune, "Kernel", or(System.getProperty("os.version"), "Unknown"));
        s.add(R.drawable.ic_sym_memory, "Processor", cpu());
        s.add(R.drawable.ic_sym_memory, "Memory", memory(context));
        s.add(R.drawable.ic_sym_hard_drive, "Storage", storage());
        DisplayMetrics dm = new DisplayMetrics();
        ((android.view.WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(dm);
        s.add(R.drawable.ic_sym_aspect_ratio, "Screen", Math.max(dm.widthPixels, dm.heightPixels) + " × "
                + Math.min(dm.widthPixels, dm.heightPixels) + " px · " + dm.densityDpi + " dpi");
        s.add(R.drawable.ic_sym_schedule, "Running for", duration(SystemClock.elapsedRealtime()));
        return s;
    }

    private static Section modules(Context context, PackageManager pm, Live live) {
        Section s = new Section("Modules");
        s.add(R.drawable.ic_sym_my_location, "GPS", pm.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS)
                ? "Present · " + or(live.gps, "status unknown") : "Not present");
        String bt = "Not present";
        if (pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)) {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            bt = "Present · " + (adapter != null && adapter.isEnabled() ? "on" : "off")
                    + " · phones may link through the unit's own module";
        }
        s.add(R.drawable.ic_sym_bluetooth, "Bluetooth", bt);
        s.add(R.drawable.ic_sym_wifi, "Wi-Fi", pm.hasSystemFeature(PackageManager.FEATURE_WIFI)
                ? "Present · " + network(context, ConnectivityManager.TYPE_WIFI) : "Not present");
        s.add(R.drawable.ic_sym_cell_tower, "Mobile data", pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
                ? "Present · " + network(context, ConnectivityManager.TYPE_MOBILE) : "Not present");
        s.add(R.drawable.ic_sym_videocam, "Cameras", cameras(pm));
        s.add(R.drawable.ic_sym_usb, "USB", pm.hasSystemFeature(PackageManager.FEATURE_USB_HOST)
                ? "USB host supported" : "No USB host");
        s.add(R.drawable.ic_sym_sensors, "Sensors", sensors(context));
        s.add(R.drawable.ic_sym_music_note, "Media access", com.apexdrive.launcher.services.NotificationAccess.isGranted(context)
                ? "Allowed: Home shows what's playing" : "Off: allow notification access for track info");
        return s;
    }

    private static Section apps(Context context, PackageManager pm, Live live) {
        Section s = new Section("Car apps");
        s.add(R.drawable.ic_sym_cast, "CarPlay app", appVersion(pm, live.carPlayPackage));
        s.add(R.drawable.ic_sym_map, "Maps app", appVersion(pm, live.mapPackage));
        s.add(R.drawable.ic_sym_radio, "Radio app", appVersion(pm, live.radioPackage));
        List<String[]> found = new ArrayList<>();
        try {
            for (PackageInfo p : pm.getInstalledPackages(0)) {
                if (p.packageName.equals(context.getPackageName())) continue;
                if (p.packageName.equals(live.carPlayPackage) || p.packageName.equals(live.mapPackage)
                        || p.packageName.equals(live.radioPackage)) continue;
                if (!isCarApp(p.packageName)) continue;
                String label = p.applicationInfo != null ? String.valueOf(p.applicationInfo.loadLabel(pm)) : p.packageName;
                boolean system = p.applicationInfo != null
                        && (p.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                found.add(new String[]{label, or(p.versionName, "?") + " · " + p.packageName
                        + (system ? " · built in" : "")});
            }
        } catch (Exception ignored) {}
        Collections.sort(found, (a, b) -> a[0].compareToIgnoreCase(b[0]));
        for (int i = 0; i < found.size() && i < MAX_CAR_APPS; i++) {
            s.add(R.drawable.ic_sym_apps, found.get(i)[0], found.get(i)[1]);
        }
        if (found.size() > MAX_CAR_APPS) {
            s.add(R.drawable.ic_sym_apps, "More", (found.size() - MAX_CAR_APPS)
                    + " more in Settings > Advanced > Head unit report");
        }
        return s;
    }

    // --- Readers ----------------------------------------------------------------------------

    private static String canBus(PackageManager pm) {
        for (String name : CAN_CLASSES) {
            try {
                Class.forName(name);
                return "Vendor interface found (" + name.substring(name.lastIndexOf('.') + 1) + ")";
            } catch (Throwable ignored) {}
        }
        try {
            for (PackageInfo p : pm.getInstalledPackages(0)) {
                if (p.packageName.toLowerCase(Locale.US).contains("canbus")) return "CAN-bus app " + p.packageName;
            }
        } catch (Exception ignored) {}
        return "Not detected: no vendor CAN-bus interface on this unit";
    }

    private static String mcuVersion(Context context) {
        for (String key : MCU_PROPERTIES) {
            String v = property(key);
            if (!v.isEmpty()) return v;
        }
        for (String key : MCU_SETTINGS) {
            try {
                String v = Settings.System.getString(context.getContentResolver(), key);
                if (v != null && !v.trim().isEmpty()) return v.trim();
            } catch (Exception ignored) {}
        }
        return "";
    }

    private static String cpu() {
        String abi = Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "";
        String text = Runtime.getRuntime().availableProcessors() + " cores · " + abi;
        String khz = readLine("/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq");
        try {
            if (!khz.isEmpty()) text += " · up to " + (Long.parseLong(khz) / 1000) + " MHz";
        } catch (NumberFormatException ignored) {}
        return text;
    }

    private static String memory(Context context) {
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            return bytes(mi.totalMem) + " · " + bytes(mi.availMem) + " free";
        } catch (Exception e) {
            return "Unknown";
        }
    }

    private static String storage() {
        try {
            StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
            long total = fs.getBlockCountLong() * fs.getBlockSizeLong();
            long free = fs.getAvailableBlocksLong() * fs.getBlockSizeLong();
            return bytes(free) + " free of " + bytes(total);
        } catch (Exception e) {
            return "Unknown";
        }
    }

    @SuppressWarnings("deprecation")
    private static String network(Context context, int type) {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo info = cm.getActiveNetworkInfo();
            if (info != null && info.isConnected() && info.getType() == type) return "connected";
        } catch (Exception ignored) {}
        return "not connected";
    }

    @SuppressWarnings("deprecation")
    private static String cameras(PackageManager pm) {
        if (!pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) return "None reported (reversing camera may use AV-in)";
        try {
            int n = android.hardware.Camera.getNumberOfCameras();
            return n + (n == 1 ? " camera" : " cameras");
        } catch (Exception e) {
            return "Present";
        }
    }

    private static String sensors(Context context) {
        try {
            SensorManager sm = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
            List<String> names = new ArrayList<>();
            int[] types = {Sensor.TYPE_AMBIENT_TEMPERATURE, Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE,
                    Sensor.TYPE_MAGNETIC_FIELD, Sensor.TYPE_LIGHT, Sensor.TYPE_PRESSURE};
            String[] labels = {"temperature", "accelerometer", "gyroscope", "compass", "light", "pressure"};
            for (int i = 0; i < types.length; i++) {
                if (sm.getDefaultSensor(types[i]) != null) names.add(labels[i]);
            }
            if (names.isEmpty()) return "None";
            String text = android.text.TextUtils.join(", ", names);
            return Character.toUpperCase(text.charAt(0)) + text.substring(1);
        } catch (Exception e) {
            return "Unknown";
        }
    }

    private static String appVersion(PackageManager pm, String pkg) {
        if (pkg == null || pkg.isEmpty()) return "None chosen";
        try {
            PackageInfo p = pm.getPackageInfo(pkg, 0);
            return p.applicationInfo.loadLabel(pm) + " " + or(p.versionName, "") + " · " + pkg;
        } catch (Exception e) {
            return pkg + " · not installed";
        }
    }

    private static String property(String key) {
        try {
            Method get = Class.forName("android.os.SystemProperties").getMethod("get", String.class, String.class);
            Object v = get.invoke(null, key, "");
            return v instanceof String ? ((String) v).trim() : "";
        } catch (Throwable t) {
            return "";
        }
    }

    private static String readLine(String path) {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            String line = r.readLine();
            return line != null ? line.trim() : "";
        } catch (Exception e) {
            return "";
        }
    }

    // --- Formatting (unit tested) -----------------------------------------------------------

    static boolean isCarApp(String packageName) {
        // Stock Android and Google packages match on words like "bluetooth" but are not car apps.
        if ((packageName.startsWith("com.android.") || packageName.startsWith("com.google."))
                && !packageName.contains("radio") && !packageName.contains("canbus")) return false;
        return CAR_APP.matcher(packageName).matches();
    }

    static String bytes(long n) {
        if (n >= 1L << 30) return String.format(Locale.US, "%.1f GB", n / (double) (1L << 30));
        if (n >= 1L << 20) return String.format(Locale.US, "%d MB", n >> 20);
        return String.format(Locale.US, "%d KB", n >> 10);
    }

    static String duration(long ms) {
        long min = ms / 60000;
        if (min < 1) return "Less than a minute";
        if (min < 60) return min + " min";
        long h = min / 60;
        if (h < 48) return h + " h " + (min % 60) + " min";
        return (h / 24) + " days " + (h % 24) + " h";
    }

    public static String or(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }
}
