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
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.net.Uri;
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
import android.widget.ImageView;
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
    private TextView tvClock, tvHeaderBluetooth, tvWeatherText, tvWeatherIcon;
    private View pillHeaderWeather;
    private ImageView ivHeaderBtIcon;
    private View pillHeaderBluetooth;

    // Driver Rail Buttons, Icons & Labels
    private View railBtnDash, railBtnCarPlay, railBtnMaps, railBtnAudio, railBtnApps, railBtnSetup;
    private ImageView ivDashIcon, ivCarPlayIcon, ivMapsIcon, ivAudioIcon, ivAppsIcon, ivSetupIcon;
    private TextView tvDashLabel, tvCarPlayLabel, tvMapsLabel, tvAudioLabel, tvAppsLabel, tvSetupLabel;
    private View currentActiveRailBtn;

    // Inflated screen views
    private View viewDash, viewCarPlay, viewMaps, viewAudio, viewApps, viewSetup;

    // Dashboard widgets
    private TextView tvSpeed, tvSpeedUnit, tvHeading, tvTripDistance, tvTripTime;
    private View cardBluetooth;
    private TextView tvBtDeviceName, tvBtProfileDetail, tvBtStatusBadge;
    private TextView tvTrackTitle, tvTrackArtist, tvCarPlayProtocol;
    private Button btnMediaPlayPause;
    private Button btnSelectCarPlayApp;
    private Button btnToggleUnit;

    // Dynamic Maps widgets
    private TextView tvNavHeadingBig, tvNavCoordinates, tvNavAltitude, tvNavAccuracy, tvNavTripDist, tvNavTripDuration;

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

        // Default to Dashboard
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
        ivHeaderBtIcon = findViewById(R.id.ivHeaderBtIcon);
        pillHeaderBluetooth = findViewById(R.id.pillHeaderBluetooth);
        tvWeatherText = findViewById(R.id.tvWeatherText);
        tvWeatherIcon = findViewById(R.id.tvWeatherIcon);
        pillHeaderWeather = findViewById(R.id.pillHeaderWeather);
        if (pillHeaderWeather != null) {
            pillHeaderWeather.setOnClickListener(v -> cycleWeatherState());
        }

        railBtnDash = findViewById(R.id.railBtnDash);
        railBtnCarPlay = findViewById(R.id.railBtnCarPlay);
        railBtnMaps = findViewById(R.id.railBtnMaps);
        railBtnAudio = findViewById(R.id.railBtnAudio);
        railBtnApps = findViewById(R.id.railBtnApps);
        railBtnSetup = findViewById(R.id.railBtnSetup);

        ivDashIcon = findViewById(R.id.ivDashIcon);
        ivCarPlayIcon = findViewById(R.id.ivCarPlayIcon);
        ivMapsIcon = findViewById(R.id.ivMapsIcon);
        ivAudioIcon = findViewById(R.id.ivAudioIcon);
        ivAppsIcon = findViewById(R.id.ivAppsIcon);
        ivSetupIcon = findViewById(R.id.ivSetupIcon);

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

        // Header Bluetooth click
        if (pillHeaderBluetooth != null) {
            pillHeaderBluetooth.setOnClickListener(v -> openBluetoothManager());
        }
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

        // Bluetooth Card
        cardBluetooth = viewDash.findViewById(R.id.cardBluetooth);
        tvBtDeviceName = viewDash.findViewById(R.id.tvBtDeviceName);
        tvBtProfileDetail = viewDash.findViewById(R.id.tvBtProfileDetail);
        tvBtStatusBadge = viewDash.findViewById(R.id.tvBtStatusBadge);

        if (cardBluetooth != null) {
            cardBluetooth.setOnClickListener(v -> openBluetoothManager());
        }

        // CarPlay Launch Button
        Button btnCarPlay = viewDash.findViewById(R.id.btnLaunchCarPlay);
        if (btnCarPlay != null) {
            btnCarPlay.setOnClickListener(v -> launchCarPlay());
        }

        // Universal Media Controls
        tvTrackTitle = viewDash.findViewById(R.id.tvTrackTitle);
        tvTrackArtist = viewDash.findViewById(R.id.tvTrackArtist);
        btnMediaPlayPause = viewDash.findViewById(R.id.btnMediaPlayPause);

        viewDash.findViewById(R.id.btnMediaPrev).setOnClickListener(v ->
                MediaSyncService.sendMediaKey(this, KeyEvent.KEYCODE_MEDIA_PREVIOUS));

        // Tapping Play/Pause pauses ANY active playback (CarPlay, Spotify, Radio) or resumes
        btnMediaPlayPause.setOnClickListener(v -> MediaSyncService.togglePlayPause(this));

        // Long click is an instant panic hard-pause for all media
        btnMediaPlayPause.setOnLongClickListener(v -> {
            MediaSyncService.pauseAllMedia(this);
            Toast.makeText(this, "Paused all media", Toast.LENGTH_SHORT).show();
            return true;
        });

        viewDash.findViewById(R.id.btnMediaNext).setOnClickListener(v ->
                MediaSyncService.sendMediaKey(this, KeyEvent.KEYCODE_MEDIA_NEXT));
    }

    private void setupCarPlayWidgets() {
        Button btnLaunch = viewCarPlay.findViewById(R.id.btnLaunchCarPlayFull);
        if (btnLaunch != null) {
            btnLaunch.setOnClickListener(v -> launchCarPlay());
        }
    }

    private void setupMapsWidgets() {
        tvNavHeadingBig = viewMaps.findViewById(R.id.tvNavHeadingBig);
        tvNavCoordinates = viewMaps.findViewById(R.id.tvNavCoordinates);
        tvNavAltitude = viewMaps.findViewById(R.id.tvNavAltitude);
        tvNavAccuracy = viewMaps.findViewById(R.id.tvNavAccuracy);
        tvNavTripDist = viewMaps.findViewById(R.id.tvNavTripDist);
        tvNavTripDuration = viewMaps.findViewById(R.id.tvNavTripDuration);

        // CarPlay Navigation launch
        Button btnNavCarPlay = viewMaps.findViewById(R.id.btnNavCarPlayRoute);
        if (btnNavCarPlay != null) {
            btnNavCarPlay.setOnClickListener(v -> launchCarPlay());
        }

        // Quick POI Destination triggers
        viewMaps.findViewById(R.id.btnNavFuel).setOnClickListener(v -> launchPoiSearch("gas station"));
        viewMaps.findViewById(R.id.btnNavParking).setOnClickListener(v -> launchPoiSearch("parking"));
        viewMaps.findViewById(R.id.btnNavCoffee).setOnClickListener(v -> launchPoiSearch("coffee"));
        viewMaps.findViewById(R.id.btnNavHospital).setOnClickListener(v -> launchPoiSearch("hospital"));

        // Direct App Launches
        viewMaps.findViewById(R.id.btnOpenGoogleMaps).setOnClickListener(v ->
                launchAppPackage("com.google.android.apps.maps"));
        viewMaps.findViewById(R.id.btnOpenWaze).setOnClickListener(v ->
                launchAppPackage("com.waze"));
    }

    private void launchPoiSearch(String query) {
        try {
            Uri gmmIntentUri = Uri.parse("geo:0,0?q=" + Uri.encode(query));
            Intent mapIntent = new Intent(Intent.ACTION_VIEW, gmmIntentUri);
            if (mapIntent.resolveActivity(getPackageManager()) != null) {
                startActivity(mapIntent);
                return;
            }
        } catch (Exception ignored) {}

        // Fallback: open Google Maps or Waze directly
        if (!launchAppPackage("com.google.android.apps.maps")) {
            launchAppPackage("com.waze");
        }
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

        // Direct callback inside adapter guaranteeing app launches
        adapter.setOnAppClickListener(app -> launchAppPackage(app.getPackageName()));

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
        // Accent Colors
        viewSetup.findViewById(R.id.btnColorAmber).setOnClickListener(v -> applyAccentColor(PreferenceHelper.COLOR_AMBER));
        viewSetup.findViewById(R.id.btnColorBlue).setOnClickListener(v -> applyAccentColor(PreferenceHelper.COLOR_BLUE));
        viewSetup.findViewById(R.id.btnColorRed).setOnClickListener(v -> applyAccentColor(PreferenceHelper.COLOR_RED));
        viewSetup.findViewById(R.id.btnColorGreen).setOnClickListener(v -> applyAccentColor(PreferenceHelper.COLOR_GREEN));
        viewSetup.findViewById(R.id.btnColorWhite).setOnClickListener(v -> applyAccentColor(PreferenceHelper.COLOR_WHITE));

        // CarPlay App Selector
        btnSelectCarPlayApp = viewSetup.findViewById(R.id.btnSelectCarPlayApp);
        btnSelectCarPlayApp.setText(prefHelper.getCarPlayName());
        btnSelectCarPlayApp.setOnClickListener(v -> showCarPlayPicker());

        // Speedometer Unit Toggle
        btnToggleUnit = viewSetup.findViewById(R.id.btnToggleUnit);
        btnToggleUnit.setText(prefHelper.getSpeedUnit());
        btnToggleUnit.setOnClickListener(v -> {
            String current = prefHelper.getSpeedUnit();
            String next = current.equals(PreferenceHelper.UNIT_KMH) ? PreferenceHelper.UNIT_MPH : PreferenceHelper.UNIT_KMH;
            prefHelper.setSpeedUnit(next);
            btnToggleUnit.setText(next);
            tvSpeedUnit.setText(next);
            if (gpsManager != null) {
                gpsManager.setUseMph(next.equals(PreferenceHelper.UNIT_MPH));
            }
            Toast.makeText(this, "Speed unit set to " + next, Toast.LENGTH_SHORT).show();
        });

        // GPS Refresh Rate Toggle
        Button btnGpsRefresh = viewSetup.findViewById(R.id.btnGpsRefreshRate);
        if (btnGpsRefresh != null) {
            btnGpsRefresh.setOnClickListener(v -> {
                if (gpsManager != null) {
                    gpsManager.start();
                }
                Toast.makeText(this, "GPS Stream refreshed at 20Hz", Toast.LENGTH_SHORT).show();
            });
        }

        // Automotive Shortcuts
        viewSetup.findViewById(R.id.btnShortcutFactorySettings).setOnClickListener(v -> openFactorySettings());
        viewSetup.findViewById(R.id.btnShortcutAndroidSettings).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_SETTINGS)));
        viewSetup.findViewById(R.id.btnShortcutAudioEq).setOnClickListener(v -> openAudioEqualizer());
        viewSetup.findViewById(R.id.btnShortcutBluetooth).setOnClickListener(v -> openBluetoothManager());
        viewSetup.findViewById(R.id.btnRestartLauncher).setOnClickListener(v -> recreate());
    }

    private void openFactorySettings() {
        new AlertDialog.Builder(this)
                .setTitle("Car Factory Settings")
                .setMessage("Factory PIN: 123456\nAlternative PIN: 7890\n\nCANbus: 03: Simple Soft (XP)\nProfile: 61: Hyundai Kia / 01: 13 All new Santafe")
                .setPositiveButton("Open Settings", (dialog, which) -> {
                    // Try automotive factory settings intents
                    String[] factoryIntents = {
                            "com.ts.factory",
                            "com.microntek.factorysettings",
                            "com.syu.settings",
                            "com.android.settings"
                    };
                    for (String pkg : factoryIntents) {
                        if (launchAppPackage(pkg)) return;
                    }
                    startActivity(new Intent(Settings.ACTION_SETTINGS));
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void openAudioEqualizer() {
        String[] eqPackages = {
                "com.ts.eq",
                "com.microntek.eq",
                "com.syu.eq",
                "com.android.sound"
        };
        for (String pkg : eqPackages) {
            if (launchAppPackage(pkg)) return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_SOUND_SETTINGS));
        } catch (Exception e) {
            Toast.makeText(this, "Equalizer app not found", Toast.LENGTH_SHORT).show();
        }
    }

    private void openBluetoothManager() {
        if (btWatcher != null) {
            btWatcher.forceRefresh();
        }
        String[] btPackages = {
                "com.goc.bluetooth",
                "com.android.ecar",
                "com.microntek.bluetooth"
        };
        for (String pkg : btPackages) {
            if (launchAppPackage(pkg)) return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
        } catch (Exception e) {
            Toast.makeText(this, "Opening Bluetooth settings", Toast.LENGTH_SHORT).show();
        }
    }

    private void showCarPlayPicker() {
        final List<AppInfo> apps = loadInstalledApps();
        List<String> names = new ArrayList<>();
        final List<String> packages = new ArrayList<>();

        names.add("Zlink (Default)");
        packages.add("com.zlink.carplay");

        names.add("SpeedPlay");
        packages.add("com.speedplay.carplay");

        names.add("AutoKit");
        packages.add("cn.manstep.phonemirrorbox");

        for (AppInfo app : apps) {
            if (!packages.contains(app.getPackageName())) {
                names.add(app.getLabel() + " (" + app.getPackageName() + ")");
                packages.add(app.getPackageName());
            }
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Select CarPlay / Phone Bridge App");
        builder.setItems(names.toArray(new String[0]), (dialog, which) -> {
            String selectedPkg = packages.get(which);
            String selectedName = names.get(which).split(" \\(")[0];
            saveCarPlayApp(selectedPkg, selectedName);
        });
        builder.show();
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

        // Header & Bluetooth
        if (tvHeaderBluetooth != null) tvHeaderBluetooth.setTextColor(color);
        if (ivHeaderBtIcon != null) ivHeaderBtIcon.setColorFilter(color, PorterDuff.Mode.SRC_IN);

        // Dashboard
        if (tvSpeedUnit != null) tvSpeedUnit.setTextColor(color);
        if (tvCarPlayProtocol != null) tvCarPlayProtocol.setTextColor(color);
        if (btnMediaPlayPause != null) btnMediaPlayPause.setTextColor(color);

        // Maps
        if (tvNavHeadingBig != null) tvNavHeadingBig.setTextColor(color);

        // Setup
        if (btnSelectCarPlayApp != null) btnSelectCarPlayApp.setTextColor(color);
        Button btnFactory = viewSetup.findViewById(R.id.btnShortcutFactorySettings);
        if (btnFactory != null) btnFactory.setTextColor(color);

        // Update active rail icon and label
        updateRailAppearance(currentActiveRailBtn);

        Toast.makeText(this, "Swiss accent color applied", Toast.LENGTH_SHORT).show();
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
        currentActiveRailBtn = activeRailBtn;
        viewContainer.removeAllViews();
        viewContainer.addView(targetView);
        updateRailAppearance(activeRailBtn);
    }

    private void updateRailAppearance(View activeRailBtn) {
        int textSec = ContextCompat.getColor(this, R.color.cockpit_text_secondary);
        int accent = prefHelper.getAccentColor();

        // Reset all labels
        tvDashLabel.setTextColor(textSec);
        tvCarPlayLabel.setTextColor(textSec);
        tvMapsLabel.setTextColor(textSec);
        tvAudioLabel.setTextColor(textSec);
        tvAppsLabel.setTextColor(textSec);
        tvSetupLabel.setTextColor(textSec);

        // Reset all icons
        ivDashIcon.setColorFilter(textSec, PorterDuff.Mode.SRC_IN);
        ivCarPlayIcon.setColorFilter(textSec, PorterDuff.Mode.SRC_IN);
        ivMapsIcon.setColorFilter(textSec, PorterDuff.Mode.SRC_IN);
        ivAudioIcon.setColorFilter(textSec, PorterDuff.Mode.SRC_IN);
        ivAppsIcon.setColorFilter(textSec, PorterDuff.Mode.SRC_IN);
        ivSetupIcon.setColorFilter(textSec, PorterDuff.Mode.SRC_IN);

        // Highlight active button
        if (activeRailBtn == railBtnDash) {
            tvDashLabel.setTextColor(accent);
            ivDashIcon.setColorFilter(accent, PorterDuff.Mode.SRC_IN);
        } else if (activeRailBtn == railBtnCarPlay) {
            tvCarPlayLabel.setTextColor(accent);
            ivCarPlayIcon.setColorFilter(accent, PorterDuff.Mode.SRC_IN);
        } else if (activeRailBtn == railBtnMaps) {
            tvMapsLabel.setTextColor(accent);
            ivMapsIcon.setColorFilter(accent, PorterDuff.Mode.SRC_IN);
        } else if (activeRailBtn == railBtnAudio) {
            tvAudioLabel.setTextColor(accent);
            ivAudioIcon.setColorFilter(accent, PorterDuff.Mode.SRC_IN);
        } else if (activeRailBtn == railBtnApps) {
            tvAppsLabel.setTextColor(accent);
            ivAppsIcon.setColorFilter(accent, PorterDuff.Mode.SRC_IN);
        } else if (activeRailBtn == railBtnSetup) {
            tvSetupLabel.setTextColor(accent);
            ivSetupIcon.setColorFilter(accent, PorterDuff.Mode.SRC_IN);
        }
    }

    private void initClock() {
        final SimpleDateFormat sdf = new SimpleDateFormat("h:mm", Locale.getDefault());
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
            public void onHeadingUpdated(String headingText, float bearingDegrees) {
                runOnUiThread(() -> {
                    tvHeading.setText(headingText);
                    if (tvNavHeadingBig != null) {
                        tvNavHeadingBig.setText(headingText);
                    }
                });
            }

            @Override
            public void onCoordinatesUpdated(double latitude, double longitude, double altitudeMeters, float accuracyMeters) {
                runOnUiThread(() -> {
                    if (tvNavCoordinates != null) {
                        tvNavCoordinates.setText(String.format(Locale.US, "%.4f° N, %.4f° E", latitude, longitude));
                    }
                    if (tvNavAltitude != null) {
                        tvNavAltitude.setText(String.format(Locale.US, "Alt: %.0f m  ·  Speed: %s %s",
                                altitudeMeters, tvSpeed.getText(), tvSpeedUnit.getText()));
                    }
                    if (tvNavAccuracy != null) {
                        tvNavAccuracy.setText(String.format(Locale.US, "● 3D Fix · ±%.1fm", accuracyMeters));
                    }
                });
            }

            @Override
            public void onTripUpdated(double distanceKm, long elapsedMinutes) {
                runOnUiThread(() -> {
                    tvTripDistance.setText(String.format(Locale.US, "Trip: %.1f km", distanceKm));
                    tvTripTime.setText(String.format(Locale.US, "Time: %d min", elapsedMinutes));
                    if (tvNavTripDist != null) {
                        tvNavTripDist.setText(String.format(Locale.US, "Trip: %.1f km", distanceKm));
                    }
                    if (tvNavTripDuration != null) {
                        tvNavTripDuration.setText(String.format(Locale.US, "Duration: %d min", elapsedMinutes));
                    }
                });
            }

            @Override
            public void onGpsStatusChanged(boolean hasFix, int satelliteCount) {
                // GPS fix status is displayed dynamically in the Maps tab HUD
            }
        });
        gpsManager.start();
    }

    private void initBluetooth() {
        btWatcher = new BluetoothWatcher(this);
        btWatcher.setListener((isConnected, deviceName, details) -> runOnUiThread(() -> {
            // Header status: Show connected phone name (e.g., iPhone) or "Not Connected"
            if (tvHeaderBluetooth != null) {
                tvHeaderBluetooth.setText(isConnected ? deviceName : "Not Connected");
                tvHeaderBluetooth.setTextColor(isConnected ? prefHelper.getAccentColor() : ContextCompat.getColor(MainActivity.this, R.color.cockpit_text_secondary));
            }
            if (ivHeaderBtIcon != null) {
                ivHeaderBtIcon.setColorFilter(
                        isConnected ? prefHelper.getAccentColor() : ContextCompat.getColor(MainActivity.this, R.color.cockpit_text_tertiary),
                        PorterDuff.Mode.SRC_IN
                );
            }

            // Dashboard card status
            if (tvBtDeviceName != null) {
                tvBtDeviceName.setText(isConnected ? deviceName : "No Device Connected");
            }
            if (tvBtProfileDetail != null) {
                tvBtProfileDetail.setText(details);
            }
            if (tvBtStatusBadge != null) {
                if (isConnected) {
                    tvBtStatusBadge.setText("● CONNECTED");
                    tvBtStatusBadge.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.status_active));
                } else {
                    tvBtStatusBadge.setText("○ READY");
                    tvBtStatusBadge.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.cockpit_text_secondary));
                }
            }
        }));
        btWatcher.start();
    }

    private int weatherIndex = 0;
    private final String[][] weatherPresets = {
            {"☀️", "28°C · Sunny"},
            {"🌤️", "26°C · Partly Cloudy"},
            {"☁️", "22°C · Overcast"},
            {"🌧️", "19°C · Rain"},
            {"🌙", "21°C · Clear Night"}
    };

    private void cycleWeatherState() {
        weatherIndex = (weatherIndex + 1) % weatherPresets.length;
        if (tvWeatherIcon != null) tvWeatherIcon.setText(weatherPresets[weatherIndex][0]);
        if (tvWeatherText != null) tvWeatherText.setText(weatherPresets[weatherIndex][1]);
        Toast.makeText(this, "Weather updated: " + weatherPresets[weatherIndex][1], Toast.LENGTH_SHORT).show();
    }

    private void initMediaReceiver() {
        mediaReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (MediaSyncService.ACTION_MEDIA_UPDATED.equals(intent.getAction())) {
                    String title = intent.getStringExtra(MediaSyncService.EXTRA_TRACK_TITLE);
                    String artist = intent.getStringExtra(MediaSyncService.EXTRA_TRACK_ARTIST);
                    boolean isPlaying = intent.getBooleanExtra(MediaSyncService.EXTRA_IS_PLAYING, false);

                    if (tvTrackTitle != null) tvTrackTitle.setText(title);
                    if (tvTrackArtist != null) tvTrackArtist.setText(artist);
                    if (btnMediaPlayPause != null) {
                        btnMediaPlayPause.setText(isPlaying ? "⏸  PAUSE" : "▶  PLAY");
                    }
                }
            }
        };

        IntentFilter filter = new IntentFilter(MediaSyncService.ACTION_MEDIA_UPDATED);
        registerReceiver(mediaReceiver, filter);
    }

    private void launchCarPlay() {
        String pkg = prefHelper.getCarPlayPackage();
        if (!launchAppPackage(pkg)) {
            // Try fallback packages
            String[] commonCarPlayPackages = {
                    "com.zlink.carplay",
                    "com.speedplay.carplay",
                    "cn.manstep.phonemirrorbox",
                    "com.zlink"
            };
            boolean launched = false;
            for (String fallback : commonCarPlayPackages) {
                if (launchAppPackage(fallback)) {
                    prefHelper.setCarPlayPackage(fallback);
                    launched = true;
                    break;
                }
            }
            if (!launched) {
                Toast.makeText(this, "CarPlay app (" + prefHelper.getCarPlayName() + ") not found", Toast.LENGTH_LONG).show();
            }
        }
    }

    private boolean launchAppPackage(String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) return false;
        try {
            PackageManager pm = getPackageManager();
            Intent intent = pm.getLaunchIntentForPackage(packageName);
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                return true;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    private List<AppInfo> loadInstalledApps() {
        List<AppInfo> list = new ArrayList<>();
        PackageManager pm = getPackageManager();
        Intent intent = new Intent(Intent.ACTION_MAIN, null);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);

        List<ResolveInfo> resolvedApps = pm.queryIntentActivities(intent, 0);
        for (ResolveInfo ri : resolvedApps) {
            if (ri.activityInfo.packageName.equals(getPackageName())) {
                continue; // Do not show ourselves in the app drawer
            }
            String label = ri.loadLabel(pm).toString();
            list.add(new AppInfo(label, ri.activityInfo.packageName, ri.loadIcon(pm)));
        }

        Collections.sort(list, (a, b) -> a.getLabel().compareToIgnoreCase(b.getLabel()));
        return list;
    }

    private void checkPermissions() {
        String[] permissions = {
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN
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
    protected void onResume() {
        super.onResume();
        makeFullScreen();
        if (gpsManager != null) gpsManager.start();
        if (btWatcher != null) btWatcher.forceRefresh();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        clockHandler.removeCallbacksAndMessages(null);
        if (gpsManager != null) gpsManager.stop();
        if (btWatcher != null) btWatcher.stop();
        if (mediaReceiver != null) {
            try {
                unregisterReceiver(mediaReceiver);
            } catch (Exception ignored) {}
        }
    }
}
