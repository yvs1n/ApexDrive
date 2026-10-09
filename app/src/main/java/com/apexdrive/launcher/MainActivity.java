package com.apexdrive.launcher;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.apexdrive.launcher.adapters.AppsGridAdapter;
import com.apexdrive.launcher.models.AppInfo;
import com.apexdrive.launcher.services.BluetoothWatcher;
import com.apexdrive.launcher.services.GpsTelemetryManager;
import com.apexdrive.launcher.services.MediaSyncService;
import com.apexdrive.launcher.utils.PreferenceHelper;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQ_CODE = 1001;

    private PreferenceHelper prefHelper;
    private GpsTelemetryManager gpsManager;
    private BluetoothWatcher btWatcher;

    private FrameLayout viewContainer;
    private TextView tvClock, tvHeaderBluetooth, tvGpsStatus;

    // Rail Buttons & Labels
    private View railBtnDash, railBtnCarPlay, railBtnMaps, railBtnAudio, railBtnApps, railBtnSetup;
    private TextView tvDashLabel, tvCarPlayLabel, tvMapsLabel, tvAudioLabel, tvAppsLabel, tvSetupLabel;

    // View references for dynamically inflated screens
    private View viewDash, viewCarPlay, viewMaps, viewAudio, viewApps, viewSetup;

    // Dashboard widgets
    private TextView tvSpeed, tvSpeedUnit, tvHeading, tvTripDistance, tvTripTime;
    private TextView tvBtDeviceName, tvBtProfileDetail, tvBtStatusBadge;
    private TextView tvTrackTitle, tvTrackArtist, tvCarPlayProtocol;
    private Button btnMediaPlayPause;
    private Button btnSelectCarPlayApp;

    private final Handler clockHandler = new Handler(Looper.getMainLooper());
    private BroadcastReceiver mediaReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        makeFullScreen();

        prefHelper = new PreferenceHelper(this);
        setContentView(R.layout.activity_main);

        initViews();
        initNavigation();
        initClock();
        checkPermissions();
        initTelemetry();
        initBluetooth();
        initMediaReceiver();

        // Start on Dashboard view
        showView(viewDash, railBtnDash);
    }

    private void makeFullScreen() {
        View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            makeFullScreen();
        }
    }

    private void initViews() {
        viewContainer = findViewById(R.id.viewContainer);
        tvClock = findViewById(R.id.tvClock);
        tvHeaderBluetooth = findViewById(R.id.tvHeaderBluetooth);
        tvGpsStatus = findViewById(R.id.tvGpsStatus);

        railBtnDash = findViewById(R.id.railBtnDash);
        railBtnCarPlay = findViewById(R.id.railBtnCarPlay);
        railBtnMaps = findViewById(R.id.railBtnMaps);
        railBtnAudio = findViewById(R.id.railBtnAudio);
        railBtnApps = findViewById(R.id.railBtnApps);
        railBtnSetup = findViewById(R.id.railBtnSetup);

        tvDashLabel = findViewById(R.id.tvDashLabel);
        tvCarPlayLabel = findViewById(R.id.tvCarPlayLabel);
        tvMapsLabel = findViewById(R.id.tvMapsLabel);
        tvAudioLabel = findViewById(R.id.tvAudioLabel);
        tvAppsLabel = findViewById(R.id.tvAppsLabel);
        tvSetupLabel = findViewById(R.id.tvSetupLabel);

        LayoutInflater inflater = LayoutInflater.from(this);
        viewDash = inflater.inflate(R.layout.view_dashboard, viewContainer, false);
        viewCarPlay = inflater.inflate(R.layout.view_carplay, viewContainer, false);
        viewMaps = inflater.inflate(R.layout.view_maps, viewContainer, false);
        viewAudio = inflater.inflate(R.layout.view_audio, viewContainer, false);
        viewApps = inflater.inflate(R.layout.view_apps, viewContainer, false);
        viewSetup = inflater.inflate(R.layout.view_setup, viewContainer, false);

        setupDashWidgets();
        setupCarPlayWidgets();
        setupMapsWidgets();
        setupAudioWidgets();
        setupAppsWidgets();
        setupSetupWidgets();
    }

    private void setupDashWidgets() {
        tvSpeed = viewDash.findViewById(R.id.tvSpeed);
        tvSpeedUnit = viewDash.findViewById(R.id.tvSpeedUnit);
        tvHeading = viewDash.findViewById(R.id.tvHeading);
        tvTripDistance = viewDash.findViewById(R.id.tvTripDistance);
        tvTripTime = viewDash.findViewById(R.id.tvTripTime);
        tvCarPlayProtocol = viewDash.findViewById(R.id.tvCarPlayProtocol);

        if (tvCarPlayProtocol != null) {
            tvCarPlayProtocol.setText(prefHelper.getCarPlayName().toUpperCase());
        }

        tvBtDeviceName = viewDash.findViewById(R.id.tvBtDeviceName);
        tvBtProfileDetail = viewDash.findViewById(R.id.tvBtProfileDetail);
        tvBtStatusBadge = viewDash.findViewById(R.id.tvBtStatusBadge);

        tvTrackTitle = viewDash.findViewById(R.id.tvTrackTitle);
        tvTrackArtist = viewDash.findViewById(R.id.tvTrackArtist);
        btnMediaPlayPause = viewDash.findViewById(R.id.btnMediaPlayPause);

        Button btnLaunchCarPlay = viewDash.findViewById(R.id.btnLaunchCarPlay);
        btnLaunchCarPlay.setOnClickListener(v -> launchCarPlay());

        viewDash.findViewById(R.id.btnMediaPrev).setOnClickListener(v ->
                MediaSyncService.sendMediaKey(this, KeyEvent.KEYCODE_MEDIA_PREVIOUS));
        btnMediaPlayPause.setOnClickListener(v ->
                MediaSyncService.sendMediaKey(this, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
        viewDash.findViewById(R.id.btnMediaNext).setOnClickListener(v ->
                MediaSyncService.sendMediaKey(this, KeyEvent.KEYCODE_MEDIA_NEXT));
    }

    private void setupCarPlayWidgets() {
        Button btnLaunch = viewCarPlay.findViewById(R.id.btnLaunchCarPlayFull);
        btnLaunch.setOnClickListener(v -> launchCarPlay());
    }

    private void setupMapsWidgets() {
        viewMaps.findViewById(R.id.btnOpenGoogleMaps).setOnClickListener(v ->
                launchAppPackage("com.google.android.apps.maps"));
        viewMaps.findViewById(R.id.btnOpenWaze).setOnClickListener(v ->
                launchAppPackage("com.waze"));
    }

    private void setupAudioWidgets() {
        final TextView tvActive = viewAudio.findViewById(R.id.tvAudioActiveSource);
        final TextView tvFreq = viewAudio.findViewById(R.id.tvRadioFreq);

        viewAudio.findViewById(R.id.btnSrcRadio).setOnClickListener(v ->
                tvActive.setText("FM RADIO TUNER (SI473X IC)"));
        viewAudio.findViewById(R.id.btnSrcSpotify).setOnClickListener(v ->
                launchAppPackage("com.spotify.music"));
        viewAudio.findViewById(R.id.btnSrcBluetooth).setOnClickListener(v ->
                tvActive.setText("BLUETOOTH AUDIO STREAM"));
        viewAudio.findViewById(R.id.btnSrcUsb).setOnClickListener(v ->
                tvActive.setText("USB MASS STORAGE"));

        viewAudio.findViewById(R.id.btnRadioScanDown).setOnClickListener(v -> {
            try {
                double f = Double.parseDouble(tvFreq.getText().toString()) - 0.1;
                tvFreq.setText(String.format(Locale.US, "%.1f", Math.max(87.5, f)));
            } catch (Exception ignored) {}
        });

        viewAudio.findViewById(R.id.btnRadioScanUp).setOnClickListener(v -> {
            try {
                double f = Double.parseDouble(tvFreq.getText().toString()) + 0.1;
                tvFreq.setText(String.format(Locale.US, "%.1f", Math.min(108.0, f)));
            } catch (Exception ignored) {}
        });
    }

    private void setupAppsWidgets() {
        GridView gvApps = viewApps.findViewById(R.id.gvApps);
        EditText etSearch = viewApps.findViewById(R.id.etSearchApps);

        List<AppInfo> apps = loadInstalledApps();
        AppsGridAdapter adapter = new AppsGridAdapter(this, apps);
        gvApps.setAdapter(adapter);

        gvApps.setOnItemClickListener((parent, view, position, id) -> {
            AppInfo app = adapter.getItem(position);
            launchAppPackage(app.getPackageName());
        });

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                adapter.filter(s.toString());
            }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    private void setupSetupWidgets() {
        viewSetup.findViewById(R.id.btnColorAmber).setOnClickListener(v -> applyAccentColor(PreferenceHelper.COLOR_AMBER));
        viewSetup.findViewById(R.id.btnColorBlue).setOnClickListener(v -> applyAccentColor(PreferenceHelper.COLOR_BLUE));
        viewSetup.findViewById(R.id.btnColorRed).setOnClickListener(v -> applyAccentColor(PreferenceHelper.COLOR_RED));
        viewSetup.findViewById(R.id.btnColorGreen).setOnClickListener(v -> applyAccentColor(PreferenceHelper.COLOR_GREEN));
        viewSetup.findViewById(R.id.btnColorWhite).setOnClickListener(v -> applyAccentColor(PreferenceHelper.COLOR_WHITE));

        btnSelectCarPlayApp = viewSetup.findViewById(R.id.btnSelectCarPlayApp);
        btnSelectCarPlayApp.setText(prefHelper.getCarPlayName());
        btnSelectCarPlayApp.setOnClickListener(v -> showCarPlayAppChooserDialog());

        Button btnToggleUnit = viewSetup.findViewById(R.id.btnToggleUnit);
        btnToggleUnit.setText(prefHelper.getSpeedUnit());
        btnToggleUnit.setOnClickListener(v -> {
            boolean wasKmh = prefHelper.getSpeedUnit().equals(PreferenceHelper.UNIT_KMH);
            String newUnit = wasKmh ? PreferenceHelper.UNIT_MPH : PreferenceHelper.UNIT_KMH;
            prefHelper.setSpeedUnit(newUnit);
            btnToggleUnit.setText(newUnit);
            tvSpeedUnit.setText(newUnit);
            if (gpsManager != null) gpsManager.setUseMph(!wasKmh);
        });

        viewSetup.findViewById(R.id.btnOpenAndroidSettings).setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (Exception e) {
                Toast.makeText(this, "Cannot open Settings", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void showCarPlayAppChooserDialog() {
        List<AppInfo> allApps = loadInstalledApps();
        final List<String> displayOptions = new ArrayList<>();
        final List<String> targetPackages = new ArrayList<>();
        final List<String> targetNames = new ArrayList<>();

        // 1. Automatically detect any known CarPlay/projection apps ACTUALLY installed on the device
        for (AppInfo app : allApps) {
            String pkg = app.getPackageName().toLowerCase();
            if (pkg.contains("zlink") || pkg.contains("autokit") || pkg.contains("phonemirror")
                    || pkg.contains("tlink") || pkg.contains("speedplay")) {
                displayOptions.add(app.getLabel() + "  [Installed]");
                targetPackages.add(app.getPackageName());
                targetNames.add(app.getLabel());
            }
        }

        // 2. Add quick options for known packages (in case installed without standard launcher intent)
        if (targetPackages.isEmpty()) {
            displayOptions.add("Zlink 5.3 (Default)");
            targetPackages.add("com.zjinnova.zlink");
            targetNames.add("Zlink 5.3");

            displayOptions.add("AutoKit / Carlinkit");
            targetPackages.add("cn.manstep.phonemirrorBox");
            targetNames.add("AutoKit");
        }

        // 3. Option to select ANY installed or custom-built app
        displayOptions.add("📁 Choose from ALL Installed Apps (Custom APK)...");

        new AlertDialog.Builder(this)
                .setTitle("Select CarPlay / Projection App")
                .setItems(displayOptions.toArray(new String[0]), (dialog, which) -> {
                    if (which < targetPackages.size()) {
                        saveCarPlayApp(targetPackages.get(which), targetNames.get(which));
                    } else {
                        showInstalledAppsPicker();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showInstalledAppsPicker() {
        List<AppInfo> apps = loadInstalledApps();
        if (apps.isEmpty()) {
            Toast.makeText(this, "No apps found", Toast.LENGTH_SHORT).show();
            return;
        }

        String[] appLabels = new String[apps.size()];
        for (int i = 0; i < apps.size(); i++) {
            appLabels[i] = apps.get(i).getLabel() + " (" + apps.get(i).getPackageName() + ")";
        }

        new AlertDialog.Builder(this)
                .setTitle("Select Installed App as CarPlay")
                .setItems(appLabels, (dialog, which) -> {
                    AppInfo selected = apps.get(which);
                    saveCarPlayApp(selected.getPackageName(), selected.getLabel());
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void saveCarPlayApp(String pkg, String name) {
        prefHelper.setCarPlayPackage(pkg);
        prefHelper.setCarPlayName(name);
        if (btnSelectCarPlayApp != null) {
            btnSelectCarPlayApp.setText(name);
        }
        if (tvCarPlayProtocol != null) {
            tvCarPlayProtocol.setText(name.toUpperCase());
        }
        Toast.makeText(this, "CarPlay set to: " + name, Toast.LENGTH_SHORT).show();
    }

    private void applyAccentColor(int color) {
        prefHelper.setAccentColor(color);
        tvHeaderBluetooth.setTextColor(color);
        tvSpeedUnit.setTextColor(color);
        if (tvCarPlayProtocol != null) {
            tvCarPlayProtocol.setTextColor(color);
        }
        Toast.makeText(this, "Accent color applied", Toast.LENGTH_SHORT).show();
    }

    private void initNavigation() {
        railBtnDash.setOnClickListener(v -> showView(viewDash, railBtnDash));
        railBtnCarPlay.setOnClickListener(v -> showView(viewCarPlay, railBtnCarPlay));
        railBtnMaps.setOnClickListener(v -> showView(viewMaps, railBtnMaps));
        railBtnAudio.setOnClickListener(v -> showView(viewAudio, railBtnAudio));
        railBtnApps.setOnClickListener(v -> showView(viewApps, railBtnApps));
        railBtnSetup.setOnClickListener(v -> showView(viewSetup, railBtnSetup));
    }

    private void showView(View targetView, View activeRailBtn) {
        viewContainer.removeAllViews();
        viewContainer.addView(targetView);

        // Reset rail buttons appearance
        int textSec = ContextCompat.getColor(this, R.color.cockpit_text_secondary);
        int accent = prefHelper.getAccentColor();

        tvDashLabel.setTextColor(textSec);
        tvCarPlayLabel.setTextColor(textSec);
        tvMapsLabel.setTextColor(textSec);
        tvAudioLabel.setTextColor(textSec);
        tvAppsLabel.setTextColor(textSec);
        tvSetupLabel.setTextColor(textSec);

        if (activeRailBtn == railBtnDash) tvDashLabel.setTextColor(accent);
        else if (activeRailBtn == railBtnCarPlay) tvCarPlayLabel.setTextColor(accent);
        else if (activeRailBtn == railBtnMaps) tvMapsLabel.setTextColor(accent);
        else if (activeRailBtn == railBtnAudio) tvAudioLabel.setTextColor(accent);
        else if (activeRailBtn == railBtnApps) tvAppsLabel.setTextColor(accent);
        else if (activeRailBtn == railBtnSetup) tvSetupLabel.setTextColor(accent);
    }

    private void initClock() {
        final SimpleDateFormat sdf = new SimpleDateFormat("h:mm a", Locale.getDefault());
        clockHandler.post(new Runnable() {
            @Override
            public void run() {
                tvClock.setText(sdf.format(new Date()));
                clockHandler.postDelayed(this, 1000);
            }
        });
    }

    private void initTelemetry() {
        gpsManager = new GpsTelemetryManager(this);
        gpsManager.setUseMph(prefHelper.getSpeedUnit().equals(PreferenceHelper.UNIT_MPH));
        gpsManager.setTelemetryListener(new GpsTelemetryManager.TelemetryListener() {
            @Override
            public void onSpeedUpdated(int speed, String unit) {
                runOnUiThread(() -> {
                    tvSpeed.setText(String.valueOf(speed));
                    tvSpeedUnit.setText(unit);
                });
            }

            @Override
            public void onHeadingUpdated(String headingText) {
                runOnUiThread(() -> tvHeading.setText(headingText));
            }

            @Override
            public void onTripUpdated(double distanceKm, long elapsedMinutes) {
                runOnUiThread(() -> {
                    tvTripDistance.setText(String.format(Locale.US, "Trip: %.1f km", distanceKm));
                    tvTripTime.setText(String.format(Locale.US, "Time: %d min", elapsedMinutes));
                });
            }

            @Override
            public void onGpsStatusChanged(boolean hasFix) {
                runOnUiThread(() -> {
                    tvGpsStatus.setText(hasFix ? "GPS 3D" : "GPS Searching");
                    tvGpsStatus.setTextColor(ContextCompat.getColor(MainActivity.this,
                            hasFix ? R.color.status_active : R.color.status_inactive));
                });
            }
        });
        gpsManager.start();
    }

    private void initBluetooth() {
        btWatcher = new BluetoothWatcher(this);
        btWatcher.setListener((isConnected, deviceName) -> runOnUiThread(() -> {
            if (isConnected) {
                tvHeaderBluetooth.setText("BT: " + deviceName);
                tvBtDeviceName.setText(deviceName);
                tvBtProfileDetail.setText("Handsfree + A2DP Audio Connected");
                tvBtStatusBadge.setText("● CONNECTED");
                tvBtStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_active));
            } else {
                tvHeaderBluetooth.setText("BT: Offline");
                tvBtDeviceName.setText("SantafemR");
                tvBtProfileDetail.setText("Ready to pair...");
                tvBtStatusBadge.setText("DISCONNECTED");
                tvBtStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_inactive));
            }
        }));
        btWatcher.start();
    }

    private void initMediaReceiver() {
        mediaReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String title = intent.getStringExtra(MediaSyncService.EXTRA_TRACK_TITLE);
                String artist = intent.getStringExtra(MediaSyncService.EXTRA_TRACK_ARTIST);
                boolean isPlaying = intent.getBooleanExtra(MediaSyncService.EXTRA_IS_PLAYING, false);

                if (title != null) tvTrackTitle.setText(title);
                if (artist != null) tvTrackArtist.setText(artist);
                btnMediaPlayPause.setText(isPlaying ? "⏸" : "▶");
            }
        };

        IntentFilter filter = new IntentFilter(MediaSyncService.ACTION_MEDIA_UPDATED);
        registerReceiver(mediaReceiver, filter);
    }

    private void launchCarPlay() {
        String customPkg = prefHelper.getCarPlayPackage();
        PackageManager pm = getPackageManager();

        // 1. Try launching the explicitly configured package
        if (customPkg != null && !customPkg.isEmpty()) {
            Intent intent = pm.getLaunchIntentForPackage(customPkg);
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                return;
            }
        }

        // 2. Fallback: Search common automotive CarPlay packages
        String[] fallbackPackages = {
                "com.zjinnova.zlink",
                "cn.manstep.phonemirrorBox",
                "com.syu.tlink",
                "com.suding.speedplay",
                "com.autonavi.zlink"
        };

        for (String pkg : fallbackPackages) {
            Intent intent = pm.getLaunchIntentForPackage(pkg);
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                return;
            }
        }

        // 3. Fallback: Search any package matching zlink/autokit/tlink/speedplay
        List<ResolveInfo> list = pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
        for (ResolveInfo info : list) {
            String pName = info.activityInfo.packageName.toLowerCase();
            if (pName.contains("zlink") || pName.contains("autokit") || pName.contains("tlink") || pName.contains("speedplay")) {
                Intent intent = pm.getLaunchIntentForPackage(info.activityInfo.packageName);
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                    return;
                }
            }
        }

        Toast.makeText(this, "CarPlay app not found. Please select your app in Setup.", Toast.LENGTH_SHORT).show();
    }

    private void launchAppPackage(String pkgName) {
        try {
            Intent intent = getPackageManager().getLaunchIntentForPackage(pkgName);
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } else {
                Toast.makeText(this, "App is not installed: " + pkgName, Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Failed to launch app", Toast.LENGTH_SHORT).show();
        }
    }

    private List<AppInfo> loadInstalledApps() {
        List<AppInfo> apps = new ArrayList<>();
        PackageManager pm = getPackageManager();
        Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);

        List<ResolveInfo> activities = pm.queryIntentActivities(mainIntent, 0);
        for (ResolveInfo ri : activities) {
            if (ri.activityInfo.packageName.equals(getPackageName())) continue;
            String label = ri.loadLabel(pm).toString();
            apps.add(new AppInfo(label, ri.activityInfo.packageName, ri.loadIcon(pm)));
        }

        Collections.sort(apps, (a, b) -> a.getLabel().compareToIgnoreCase(b.getLabel()));
        return apps;
    }

    private void checkPermissions() {
        String[] permissions = {
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
        };

        List<String> needed = new ArrayList<>();
        for (String p : permissions) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                needed.add(p);
            }
        }

        if (!needed.isEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), PERMISSION_REQ_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQ_CODE && gpsManager != null) {
            gpsManager.start();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (gpsManager != null) gpsManager.stop();
        if (btWatcher != null) btWatcher.stop();
        if (mediaReceiver != null) unregisterReceiver(mediaReceiver);
        clockHandler.removeCallbacksAndMessages(null);
    }
}
