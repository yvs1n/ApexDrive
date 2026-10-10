package com.apexdrive.launcher;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.PorterDuff;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.CountDownTimer;
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
import android.widget.LinearLayout;
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
import com.apexdrive.launcher.services.CarThermometerWatcher;
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
    private CarThermometerWatcher thermometerWatcher;

    private CountDownTimer deviceSelectorTimer;
    private AlertDialog deviceSelectorDialog;
    private boolean isBtConnecting = false;
    private AppsGridAdapter appsAdapter;

    private FrameLayout viewContainer;
    private TextView tvClock, tvHeaderBluetooth, tvWeatherText, tvWeatherIcon;
    private View pillHeaderWeather;
    private ImageView ivHeaderBtIcon;
    private View pillHeaderBluetooth;

    private View railBtnDash, railBtnCarPlay, railBtnMaps, railBtnAudio, railBtnApps, railBtnSetup;
    private ImageView ivDashIcon, ivCarPlayIcon, ivMapsIcon, ivAudioIcon, ivAppsIcon, ivSetupIcon;
    private TextView tvDashLabel, tvCarPlayLabel, tvMapsLabel, tvAudioLabel, tvAppsLabel, tvSetupLabel;
    private View currentActiveRailBtn;

    private View viewDash, viewCarPlay, viewMaps, viewAudio, viewApps, viewSetup;

    private TextView tvSpeed, tvSpeedUnit, tvHeading, tvTripDistance, tvTripTime;
    private TextView tvBtDeviceName, tvBtProfileDetail, tvBtStatusBadge;
    private TextView tvTrackTitle, tvTrackArtist, tvCarPlayProtocol;
    private Button btnMediaPlayPause;
    private Button btnSelectCarPlayApp;
    private Button btnToggleUnit;
    private Button btnSelectDriverPhone;

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
        initCarThermometer();
        initMediaReceiver();

        showView(viewDash, railBtnDash);
        applyAccentColor(prefHelper.getAccentColor());

        new Handler(Looper.getMainLooper()).postDelayed(() -> showDeviceSelectorDialog(true), 600);
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

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        makeFullScreen();
        if (currentActiveRailBtn != railBtnDash) {
            showView(viewDash, railBtnDash);
        }
    }

    @Override
    public void onBackPressed() {
        if (currentActiveRailBtn != railBtnDash) {
            showView(viewDash, railBtnDash);
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

        if (pillHeaderBluetooth != null) {
            pillHeaderBluetooth.setOnClickListener(v -> showDeviceSelectorDialog(false));
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

        if (tvSpeed != null) {
            tvSpeed.setOnClickListener(v -> checkAndOpenLocationSettings());
        }

        if (tvHeading != null) {
            tvHeading.setOnClickListener(v -> checkAndOpenLocationSettings());
        }

        if (tvSpeedUnit != null) {
            tvSpeedUnit.setOnClickListener(v -> toggleSpeedUnit());
        }

        View rowTripContainer = viewDash.findViewById(R.id.rowTripContainer);
        if (rowTripContainer != null) {
            rowTripContainer.setOnClickListener(v -> resetTrip());
        }

        View cardBluetooth = viewDash.findViewById(R.id.cardBluetooth);
        tvBtDeviceName = viewDash.findViewById(R.id.tvBtDeviceName);
        tvBtProfileDetail = viewDash.findViewById(R.id.tvBtProfileDetail);
        tvBtStatusBadge = viewDash.findViewById(R.id.tvBtStatusBadge);

        if (cardBluetooth != null) {
            cardBluetooth.setOnClickListener(v -> showDeviceSelectorDialog(false));
        }

        Button btnCarPlay = viewDash.findViewById(R.id.btnLaunchCarPlay);
        if (btnCarPlay != null) {
            btnCarPlay.setOnClickListener(v -> launchCarPlay());
        }

        tvTrackTitle = viewDash.findViewById(R.id.tvTrackTitle);
        tvTrackArtist = viewDash.findViewById(R.id.tvTrackArtist);
        btnMediaPlayPause = viewDash.findViewById(R.id.btnMediaPlayPause);

        viewDash.findViewById(R.id.btnMediaPrev).setOnClickListener(v ->
                MediaSyncService.sendMediaKey(this, KeyEvent.KEYCODE_MEDIA_PREVIOUS));

        btnMediaPlayPause.setOnClickListener(v -> MediaSyncService.togglePlayPause(this));

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

        if (tvNavCoordinates != null) {
            tvNavCoordinates.setOnClickListener(v -> checkAndOpenLocationSettings());
        }
        if (tvNavAccuracy != null) {
            tvNavAccuracy.setOnClickListener(v -> checkAndOpenLocationSettings());
        }

        Button btnNavCarPlay = viewMaps.findViewById(R.id.btnNavCarPlayRoute);
        if (btnNavCarPlay != null) {
            btnNavCarPlay.setOnClickListener(v -> launchCarPlay());
        }

        viewMaps.findViewById(R.id.btnNavFuel).setOnClickListener(v -> launchPoiSearch("gas station"));
        viewMaps.findViewById(R.id.btnNavParking).setOnClickListener(v -> launchPoiSearch("parking"));
        viewMaps.findViewById(R.id.btnNavCoffee).setOnClickListener(v -> launchPoiSearch("coffee"));
        viewMaps.findViewById(R.id.btnNavHospital).setOnClickListener(v -> launchPoiSearch("hospital"));

        viewMaps.findViewById(R.id.btnOpenGoogleMaps).setOnClickListener(v -> {
            if (!launchAppPackage("com.google.android.apps.maps")) {
                if (launchAppPackage("com.waze")) {
                    Toast.makeText(this, "Google Maps not found · Opened Waze", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "Google Maps is not installed. Tap APPS or connect CarPlay.", Toast.LENGTH_LONG).show();
                }
            }
        });

        viewMaps.findViewById(R.id.btnOpenWaze).setOnClickListener(v -> {
            if (!launchAppPackage("com.waze")) {
                if (launchAppPackage("com.google.android.apps.maps")) {
                    Toast.makeText(this, "Waze not found · Opened Google Maps", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "Waze is not installed. Tap APPS or connect CarPlay.", Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private void checkAndOpenLocationSettings() {
        try {
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (lm != null && !lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                Toast.makeText(this, "GPS is off · Opening Location settings", Toast.LENGTH_SHORT).show();
                startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } else {
                Toast.makeText(this, "GPS Telemetry Active · Real-time 20Hz", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Cannot open Location settings", Toast.LENGTH_SHORT).show();
        }
    }

    private void launchPoiSearch(String query) {
        try {
            Uri gmmIntentUri = Uri.parse("geo:0,0?q=" + Uri.encode(query));
            Intent mapIntent = new Intent(Intent.ACTION_VIEW, gmmIntentUri);
            mapIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (mapIntent.resolveActivity(getPackageManager()) != null) {
                startActivity(mapIntent);
                return;
            }
        } catch (Exception ignored) {}

        if (launchAppPackage("com.google.android.apps.maps")) return;
        if (launchAppPackage("com.waze")) return;
        if (launchAppPackage("com.autonavi.amapauto")) return;
        if (launchAppPackage("com.google.android.apps.mapslite")) return;

        new AlertDialog.Builder(this)
                .setTitle("NAVIGATION APP NOT FOUND")
                .setMessage("No navigation engine installed for searching \"" + query.toUpperCase() + "\".\n\nWould you like to search in your web browser or launch CarPlay Navigation?")
                .setPositiveButton("LAUNCH CARPLAY", (d, w) -> launchCarPlay())
                .setNeutralButton("BROWSER MAPS", (d, w) -> {
                    try {
                        Uri webUri = Uri.parse("https://www.google.com/maps/search/" + Uri.encode(query));
                        startActivity(new Intent(Intent.ACTION_VIEW, webUri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (Exception e) {
                        Toast.makeText(this, "No web browser installed", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("CANCEL", null)
                .show();
    }

    private void setupAudioWidgets() {
        final TextView tvActive = viewAudio.findViewById(R.id.tvAudioActiveSource);
        final TextView tvFreq = viewAudio.findViewById(R.id.tvRadioFreq);

        viewAudio.findViewById(R.id.btnSrcRadio).setOnClickListener(v -> {
            tvActive.setText("FM RADIO TUNER (SI473X IC)");
            String[] radioPackages = {"com.ts.radio", "com.microntek.radio", "com.syu.radio", "com.android.radio", "com.autonavi.radio", "com.car.radio"};
            for (String pkg : radioPackages) {
                if (launchAppPackage(pkg)) return;
            }
            try {
                Intent musicIntent = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC);
                musicIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(musicIntent);
                Toast.makeText(this, "Built-in FM Radio app not found · Opened media player", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "Radio app not found on this head unit firmware", Toast.LENGTH_LONG).show();
            }
        });

        viewAudio.findViewById(R.id.btnSrcSpotify).setOnClickListener(v -> {
            if (!launchAppPackage("com.spotify.music")) {
                try {
                    Intent musicIntent = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC);
                    musicIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(musicIntent);
                    Toast.makeText(this, "Spotify not installed · Opened media player", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(this, "Spotify is not installed. Tap APPS in rail to open installed audio players.", Toast.LENGTH_LONG).show();
                }
            }
        });

        viewAudio.findViewById(R.id.btnSrcBluetooth).setOnClickListener(v -> {
            tvActive.setText("BLUETOOTH AUDIO STREAM");
            if (btWatcher != null && btWatcher.getConnectedDevice() == null) {
                Toast.makeText(this, "No phone connected · Tap to pair or connect", Toast.LENGTH_SHORT).show();
                showDeviceSelectorDialog(false);
            } else {
                Toast.makeText(this, "Streaming via Bluetooth A2DP", Toast.LENGTH_SHORT).show();
            }
        });

        viewAudio.findViewById(R.id.btnSrcUsb).setOnClickListener(v -> {
            tvActive.setText("USB MASS STORAGE");
            String[] usbPackages = {"com.ts.music", "com.microntek.music", "com.syu.music", "com.android.music", "com.android.musicfx"};
            for (String pkg : usbPackages) {
                if (launchAppPackage(pkg)) return;
            }
            try {
                Intent musicIntent = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC);
                musicIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(musicIntent);
                Toast.makeText(this, "USB player app not found · Opened default music app", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "No USB music player found", Toast.LENGTH_SHORT).show();
            }
        });

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

        appsAdapter = new AppsGridAdapter(this, new ArrayList<>());
        gvApps.setAdapter(appsAdapter);

        appsAdapter.setOnAppClickListener(this::onAppItemClicked);
        gvApps.setOnItemClickListener((parent, view, position, id) -> onAppItemClicked(appsAdapter.getItem(position)));

        loadInstalledAppsAsync();

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (appsAdapter != null) {
                    appsAdapter.filter(s.toString());
                }
            }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    private void onAppItemClicked(AppInfo app) {
        if (app == null) return;
        boolean launched = launchApp(app);
        if (!launched) {
            Toast.makeText(this, "Cannot launch " + app.getLabel().toUpperCase() + ". App may be disabled or uninstalled.", Toast.LENGTH_LONG).show();
            loadInstalledAppsAsync();
        }
    }

    private void loadInstalledAppsAsync() {
        new Thread(() -> {
            List<AppInfo> apps = loadInstalledApps();
            runOnUiThread(() -> {
                if (appsAdapter != null) {
                    appsAdapter.updateData(apps);
                }
            });
        }).start();
    }

    private void setupSetupWidgets() {
        viewSetup.findViewById(R.id.btnColorAmber).setOnClickListener(v -> applyAccentColor(PreferenceHelper.COLOR_AMBER));
        viewSetup.findViewById(R.id.btnColorBlue).setOnClickListener(v -> applyAccentColor(PreferenceHelper.COLOR_BLUE));
        viewSetup.findViewById(R.id.btnColorRed).setOnClickListener(v -> applyAccentColor(PreferenceHelper.COLOR_RED));
        viewSetup.findViewById(R.id.btnColorGreen).setOnClickListener(v -> applyAccentColor(PreferenceHelper.COLOR_GREEN));
        viewSetup.findViewById(R.id.btnColorWhite).setOnClickListener(v -> applyAccentColor(PreferenceHelper.COLOR_WHITE));

        btnSelectDriverPhone = viewSetup.findViewById(R.id.btnSelectDriverPhone);
        if (btnSelectDriverPhone != null) {
            btnSelectDriverPhone.setOnClickListener(v -> showDeviceSelectorDialog(false));
        }

        btnSelectCarPlayApp = viewSetup.findViewById(R.id.btnSelectCarPlayApp);
        btnSelectCarPlayApp.setText(prefHelper.getCarPlayName());
        btnSelectCarPlayApp.setOnClickListener(v -> showCarPlayPicker());

        btnToggleUnit = viewSetup.findViewById(R.id.btnToggleUnit);
        btnToggleUnit.setText(prefHelper.getSpeedUnit());
        btnToggleUnit.setOnClickListener(v -> toggleSpeedUnit());

        Button btnGpsRefresh = viewSetup.findViewById(R.id.btnGpsRefreshRate);
        if (btnGpsRefresh != null) {
            btnGpsRefresh.setOnClickListener(v -> {
                if (gpsManager != null) {
                    gpsManager.start();
                }
                Toast.makeText(this, "GPS Stream refreshed at 20Hz", Toast.LENGTH_SHORT).show();
            });
        }

        viewSetup.findViewById(R.id.btnShortcutFactorySettings).setOnClickListener(v -> openFactorySettings());
        viewSetup.findViewById(R.id.btnShortcutAndroidSettings).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        viewSetup.findViewById(R.id.btnShortcutAudioEq).setOnClickListener(v -> openAudioEqualizer());
        viewSetup.findViewById(R.id.btnShortcutBluetooth).setOnClickListener(v -> showDeviceSelectorDialog(false));
        viewSetup.findViewById(R.id.btnRestartLauncher).setOnClickListener(v -> recreate());
    }

    private void toggleSpeedUnit() {
        String current = prefHelper.getSpeedUnit();
        String next = current.equals(PreferenceHelper.UNIT_KMH) ? PreferenceHelper.UNIT_MPH : PreferenceHelper.UNIT_KMH;
        prefHelper.setSpeedUnit(next);
        if (btnToggleUnit != null) btnToggleUnit.setText(next);
        if (tvSpeedUnit != null) tvSpeedUnit.setText(next);
        if (gpsManager != null) {
            gpsManager.setUseMph(next.equals(PreferenceHelper.UNIT_MPH));
        }
        if (thermometerWatcher != null) {
            thermometerWatcher.setUseFahrenheit(next.equals(PreferenceHelper.UNIT_MPH));
        }
        Toast.makeText(this, "Speedometer set to " + next, Toast.LENGTH_SHORT).show();
    }

    private void resetTrip() {
        if (gpsManager != null) {
            gpsManager.resetTrip();
            boolean isMph = prefHelper.getSpeedUnit().equals(PreferenceHelper.UNIT_MPH);
            String distText = isMph ? "TRIP A: 0.0 MI" : "TRIP A: 0.0 KM";
            if (tvTripDistance != null) tvTripDistance.setText(distText);
            if (tvTripTime != null) tvTripTime.setText("TIME: 0 MIN · RESET");
            if (tvNavTripDist != null) tvNavTripDist.setText(distText);
            if (tvNavTripDuration != null) tvNavTripDuration.setText("DURATION: 0 MIN");
            Toast.makeText(this, "Trip distance & time reset", Toast.LENGTH_SHORT).show();
        }
    }

    private void openFactorySettings() {
        new AlertDialog.Builder(this)
                .setTitle("Car Factory Settings")
                .setMessage("Factory PIN: 123456\nAlternative PIN: 7890\n\nCANbus: 03: Simple Soft (XP)\nProfile: 61: Hyundai Kia / 01: 13 All new Santafe")
                .setPositiveButton("Open Settings", (dialog, which) -> {
                    String[] factoryIntents = {
                            "com.ts.factory",
                            "com.microntek.factorysettings",
                            "com.syu.settings",
                            "com.android.settings"
                    };
                    for (String pkg : factoryIntents) {
                        if (launchAppPackage(pkg)) return;
                    }
                    startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
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
            startActivity(new Intent(Settings.ACTION_SOUND_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
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
            startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
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
        if (tvWeatherIcon != null) tvWeatherIcon.setTextColor(color);

        // Dashboard View
        if (viewDash != null) {
            TextView speedUnit = viewDash.findViewById(R.id.tvSpeedUnit);
            if (speedUnit != null) speedUnit.setTextColor(color);

            TextView carPlayProtocol = viewDash.findViewById(R.id.tvCarPlayProtocol);
            if (carPlayProtocol != null) carPlayProtocol.setTextColor(color);

            Button playPause = viewDash.findViewById(R.id.btnMediaPlayPause);
            if (playPause != null) playPause.setTextColor(color);
        }

        if (viewCarPlay != null) {
            Button btnLaunchFull = viewCarPlay.findViewById(R.id.btnLaunchCarPlayFull);
            if (btnLaunchFull != null) btnLaunchFull.setTextColor(color);
        }

        if (viewMaps != null) {
            TextView navHeading = viewMaps.findViewById(R.id.tvNavHeadingBig);
            if (navHeading != null) navHeading.setTextColor(color);
        }

        if (viewAudio != null) {
            Button btnRadio = viewAudio.findViewById(R.id.btnSrcRadio);
            if (btnRadio != null) btnRadio.setTextColor(color);

            TextView presetBadge = viewAudio.findViewById(R.id.tvAudioPresetBadge);
            if (presetBadge != null) presetBadge.setTextColor(color);

            TextView freqUnit = viewAudio.findViewById(R.id.tvRadioFreqUnit);
            if (freqUnit != null) freqUnit.setTextColor(color);
        }

        if (viewApps != null) {
            TextView appsTitle = viewApps.findViewById(R.id.tvInstalledAppsTitle);
            if (appsTitle != null) appsTitle.setTextColor(color);
        }

        if (viewSetup != null) {
            if (btnSelectCarPlayApp != null) btnSelectCarPlayApp.setTextColor(color);
            if (btnSelectDriverPhone != null) btnSelectDriverPhone.setTextColor(color);

            Button btnFactory = viewSetup.findViewById(R.id.btnShortcutFactorySettings);
            if (btnFactory != null) btnFactory.setTextColor(color);
        }

        updateRailAppearance(currentActiveRailBtn);
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
        applyAccentColor(prefHelper.getAccentColor());
    }

    private void updateRailAppearance(View activeRailBtn) {
        int textSec = ContextCompat.getColor(this, R.color.cockpit_text_secondary);
        int accent = prefHelper.getAccentColor();

        tvDashLabel.setTextColor(textSec);
        tvCarPlayLabel.setTextColor(textSec);
        tvMapsLabel.setTextColor(textSec);
        tvAudioLabel.setTextColor(textSec);
        tvAppsLabel.setTextColor(textSec);
        tvSetupLabel.setTextColor(textSec);

        ivDashIcon.setColorFilter(textSec, PorterDuff.Mode.SRC_IN);
        ivCarPlayIcon.setColorFilter(textSec, PorterDuff.Mode.SRC_IN);
        ivMapsIcon.setColorFilter(textSec, PorterDuff.Mode.SRC_IN);
        ivAudioIcon.setColorFilter(textSec, PorterDuff.Mode.SRC_IN);
        ivAppsIcon.setColorFilter(textSec, PorterDuff.Mode.SRC_IN);
        ivSetupIcon.setColorFilter(textSec, PorterDuff.Mode.SRC_IN);

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
                        String currentSpeed = (tvSpeed != null) ? tvSpeed.getText().toString() : "0";
                        String currentUnit = (tvSpeedUnit != null) ? tvSpeedUnit.getText().toString() : "KM/H";
                        tvNavAltitude.setText(String.format(Locale.US, "Alt: %.0f m  ·  Speed: %s %s",
                                altitudeMeters, currentSpeed, currentUnit));
                    }
                    if (tvNavAccuracy != null) {
                        tvNavAccuracy.setText(String.format(Locale.US, "● 3D Fix · ±%.1fm", accuracyMeters));
                    }
                });
            }

            @Override
            public void onTripUpdated(double distanceKm, long elapsedMinutes) {
                runOnUiThread(() -> {
                    boolean isMph = prefHelper.getSpeedUnit().equals(PreferenceHelper.UNIT_MPH);
                    String distText;
                    if (isMph) {
                        distText = String.format(Locale.US, "TRIP A: %.1f MI", distanceKm * 0.621371);
                    } else {
                        distText = String.format(Locale.US, "TRIP A: %.1f KM", distanceKm);
                    }
                    tvTripDistance.setText(distText);
                    tvTripTime.setText(String.format(Locale.US, "TIME: %d MIN · RESET", elapsedMinutes));
                    if (tvNavTripDist != null) {
                        tvNavTripDist.setText(distText);
                    }
                    if (tvNavTripDuration != null) {
                        tvNavTripDuration.setText(String.format(Locale.US, "DURATION: %d MIN", elapsedMinutes));
                    }
                });
            }

            @Override
            public void onGpsStatusChanged(boolean hasFix, int satelliteCount) {}
        });
        gpsManager.start();
    }

    private void initBluetooth() {
        btWatcher = new BluetoothWatcher(this);
        btWatcher.setListener((isConnected, deviceName, details) -> runOnUiThread(() -> {
            if (tvHeaderBluetooth != null) {
                tvHeaderBluetooth.setText(isConnected ? deviceName.toUpperCase() : "NOT CONNECTED");
                tvHeaderBluetooth.setTextColor(isConnected ? prefHelper.getAccentColor() : ContextCompat.getColor(MainActivity.this, R.color.cockpit_text_secondary));
            }
            if (ivHeaderBtIcon != null) {
                ivHeaderBtIcon.setColorFilter(
                        isConnected ? prefHelper.getAccentColor() : ContextCompat.getColor(MainActivity.this, R.color.cockpit_text_tertiary),
                        PorterDuff.Mode.SRC_IN
                );
            }

            if (tvBtDeviceName != null) {
                tvBtDeviceName.setText(isConnected ? deviceName.toUpperCase() : "NO DEVICE CONNECTED");
            }
            if (tvBtProfileDetail != null) {
                tvBtProfileDetail.setText(details != null ? details.toUpperCase() : "A2DP AUDIO · TELEPHONY LINK");
            }
            if (tvBtStatusBadge != null) {
                if (isConnected) {
                    tvBtStatusBadge.setText("CONNECTED");
                    tvBtStatusBadge.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.status_active));
                } else {
                    tvBtStatusBadge.setText("READY");
                    tvBtStatusBadge.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.cockpit_text_secondary));
                }
            }
        }));
        btWatcher.start();
    }

    private void initCarThermometer() {
        thermometerWatcher = new CarThermometerWatcher(this, (tempFormatted, tempCelsius) -> runOnUiThread(() -> {
            if (tvWeatherText != null) {
                tvWeatherText.setText(tempFormatted != null ? tempFormatted.toUpperCase() : "26°C · OUTSIDE");
            }
            if (tvWeatherIcon != null) {
                tvWeatherIcon.setText("TEMP");
            }
        }));
        thermometerWatcher.setUseFahrenheit(prefHelper.getSpeedUnit().equals(PreferenceHelper.UNIT_MPH));
        thermometerWatcher.start();
    }

    private void cycleWeatherState() {
        if (thermometerWatcher != null) {
            thermometerWatcher.stop();
            thermometerWatcher.start();
            Toast.makeText(this, "Refreshed live vehicle thermometer", Toast.LENGTH_SHORT).show();
        }
    }

    private void showDeviceSelectorDialog(boolean isAutoDismiss) {
        if (isFinishing()) return;
        if (deviceSelectorDialog != null && deviceSelectorDialog.isShowing()) {
            deviceSelectorDialog.dismiss();
        }
        if (deviceSelectorTimer != null) {
            deviceSelectorTimer.cancel();
            deviceSelectorTimer = null;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_device_selector, null);
        builder.setView(dialogView);
        deviceSelectorDialog = builder.create();

        if (deviceSelectorDialog.getWindow() != null) {
            deviceSelectorDialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        TextView tvCountdown = dialogView.findViewById(R.id.tvCountdownTimer);
        LinearLayout deviceContainer = dialogView.findViewById(R.id.deviceListContainer);
        Button btnPair = dialogView.findViewById(R.id.btnPairNewDevice);
        Button btnSkip = dialogView.findViewById(R.id.btnSkipSelector);

        int accentColor = prefHelper.getAccentColor();
        if (btnSkip != null) btnSkip.setTextColor(accentColor);
        if (tvCountdown != null) tvCountdown.setTextColor(accentColor);

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        @SuppressLint("MissingPermission")
        java.util.Set<BluetoothDevice> pairedDevices = (adapter != null && adapter.isEnabled()) ? adapter.getBondedDevices() : null;

        LayoutInflater inflater = LayoutInflater.from(this);

        if (pairedDevices != null && !pairedDevices.isEmpty()) {
            for (BluetoothDevice device : pairedDevices) {
                View card = inflater.inflate(R.layout.item_device_card, deviceContainer, false);
                TextView tvName = card.findViewById(R.id.tvDialogDeviceName);
                TextView tvStatus = card.findViewById(R.id.tvDialogDeviceStatus);
                TextView btnConnect = card.findViewById(R.id.btnConnectPill);

                @SuppressLint("MissingPermission")
                String name = (device.getName() != null && !device.getName().isEmpty()) ? device.getName() : "Phone (" + device.getAddress() + ")";
                tvName.setText(name.toUpperCase());

                boolean isActuallyConnected = (btWatcher != null && btWatcher.isDeviceCurrentlyConnected(device));
                if (isActuallyConnected) {
                    tvStatus.setText("CONNECTED · ACTIVE FOR CARPLAY & AUDIO");
                    tvStatus.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.status_active));
                    tvName.setTextColor(accentColor);
                    btnConnect.setText("ACTIVE");
                    btnConnect.setTextColor(accentColor);
                } else {
                    tvStatus.setText("PAIRED · READY TO CONNECT");
                    tvStatus.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.cockpit_text_secondary));
                    btnConnect.setText("CONNECT");
                    btnConnect.setTextColor(accentColor);
                }

                View.OnClickListener selectListener = v -> {
                    if (isBtConnecting) return;

                    if (deviceSelectorTimer != null) {
                        deviceSelectorTimer.cancel();
                        deviceSelectorTimer = null;
                        if (tvCountdown != null) tvCountdown.setVisibility(View.GONE);
                    }

                    if (btWatcher != null && btWatcher.isDeviceCurrentlyConnected(device)) {
                        prefHelper.setSelectedDriverDevice(device.getAddress());
                        Toast.makeText(MainActivity.this, name + " is already active", Toast.LENGTH_SHORT).show();
                        if (deviceSelectorDialog != null && deviceSelectorDialog.isShowing()) {
                            deviceSelectorDialog.dismiss();
                        }
                        launchCarPlay();
                        return;
                    }

                    if (btWatcher == null) {
                        Toast.makeText(MainActivity.this, "Bluetooth service unavailable", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    isBtConnecting = true;
                    tvStatus.setText("CONNECTING TO " + name.toUpperCase() + "...");
                    tvStatus.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.cockpit_text_secondary));
                    btnConnect.setText("CONNECTING...");
                    btnConnect.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.cockpit_text_secondary));

                    btWatcher.connectToDevice(device, new BluetoothWatcher.DeviceConnectionCallback() {
                        @Override
                        public void onConnecting(BluetoothDevice dev) {
                            runOnUiThread(() -> {
                                tvStatus.setText("ESTABLISHING BLUETOOTH LINK...");
                                btnConnect.setText("CONNECTING...");
                            });
                        }

                        @Override
                        public void onSuccess(BluetoothDevice dev) {
                            runOnUiThread(() -> {
                                isBtConnecting = false;
                                prefHelper.setSelectedDriverDevice(dev.getAddress());
                                tvStatus.setText("CONNECTED · ACTIVE FOR CARPLAY & AUDIO");
                                tvStatus.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.status_active));
                                tvName.setTextColor(accentColor);
                                btnConnect.setText("ACTIVE");
                                btnConnect.setTextColor(accentColor);
                                Toast.makeText(MainActivity.this, "Connected to " + name, Toast.LENGTH_SHORT).show();

                                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                                    if (deviceSelectorDialog != null && deviceSelectorDialog.isShowing()) {
                                        deviceSelectorDialog.dismiss();
                                    }
                                    launchCarPlay();
                                }, 600);
                            });
                        }

                        @Override
                        public void onFailure(BluetoothDevice dev, String errorMessage) {
                            runOnUiThread(() -> {
                                isBtConnecting = false;
                                tvStatus.setText("CONNECTION FAILED · TAP TO RETRY");
                                tvStatus.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.accent_sport_red));
                                btnConnect.setText("RETRY");
                                btnConnect.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.accent_sport_red));
                                Toast.makeText(MainActivity.this, "Connection failed to " + name + ". Please verify phone Bluetooth is ON and try again.", Toast.LENGTH_LONG).show();
                            });
                        }
                    });
                };

                card.setOnClickListener(selectListener);
                btnConnect.setOnClickListener(selectListener);
                deviceContainer.addView(card);
            }
        } else {
            TextView tvEmpty = new TextView(this);
            tvEmpty.setText("No paired phones detected in range.\nTap \"Pair New Phone\" to link your phone via Bluetooth.");
            tvEmpty.setTextColor(ContextCompat.getColor(this, R.color.cockpit_text_secondary));
            tvEmpty.setTextSize(13);
            tvEmpty.setPadding(16, 24, 16, 24);
            tvEmpty.setGravity(android.view.Gravity.CENTER);
            deviceContainer.addView(tvEmpty);
        }

        if (btnPair != null) {
            btnPair.setOnClickListener(v -> {
                if (deviceSelectorTimer != null) deviceSelectorTimer.cancel();
                if (deviceSelectorDialog != null) deviceSelectorDialog.dismiss();
                openBluetoothManager();
            });
        }

        if (btnSkip != null) {
            btnSkip.setOnClickListener(v -> {
                if (deviceSelectorTimer != null) deviceSelectorTimer.cancel();
                if (deviceSelectorDialog != null) deviceSelectorDialog.dismiss();
            });
        }

        if (isAutoDismiss) {
            deviceSelectorTimer = new CountDownTimer(8000, 1000) {
                @Override
                public void onTick(long millisUntilFinished) {
                    long seconds = (millisUntilFinished / 1000) + 1;
                    if (tvCountdown != null) {
                        tvCountdown.setText("Auto in " + seconds + "s");
                    }
                }

                @Override
                public void onFinish() {
                    if (deviceSelectorDialog != null && deviceSelectorDialog.isShowing()) {
                        deviceSelectorDialog.dismiss();
                    }
                }
            }.start();
        } else {
            if (tvCountdown != null) tvCountdown.setVisibility(View.GONE);
        }

        deviceSelectorDialog.setOnDismissListener(dialog -> {
            if (deviceSelectorTimer != null) {
                deviceSelectorTimer.cancel();
                deviceSelectorTimer = null;
            }
            if (isBtConnecting && btWatcher != null) {
                btWatcher.cancelPendingConnection();
                isBtConnecting = false;
            }
        });

        deviceSelectorDialog.show();
    }

    private void initMediaReceiver() {
        mediaReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (MediaSyncService.ACTION_MEDIA_UPDATED.equals(intent.getAction())) {
                    String title = intent.getStringExtra(MediaSyncService.EXTRA_TRACK_TITLE);
                    String artist = intent.getStringExtra(MediaSyncService.EXTRA_TRACK_ARTIST);
                    boolean isPlaying = intent.getBooleanExtra(MediaSyncService.EXTRA_IS_PLAYING, false);

                    if (tvTrackTitle != null && title != null) tvTrackTitle.setText(title);
                    if (tvTrackArtist != null && artist != null) tvTrackArtist.setText(artist);
                    if (btnMediaPlayPause != null) {
                        btnMediaPlayPause.setText(isPlaying ? "PAUSE" : "PLAY");
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
            String[] commonCarPlayPackages = {
                    "com.zlink.carplay",
                    "com.speedplay.carplay",
                    "cn.manstep.phonemirrorbox",
                    "com.zlink",
                    "com.autokit",
                    "com.carlinke",
                    "com.sygic.aura",
                    "com.carplay"
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
                new AlertDialog.Builder(this)
                        .setTitle("CARPLAY APP NOT FOUND")
                        .setMessage("Could not find " + prefHelper.getCarPlayName() + " or any standard CarPlay projection app (Zlink, SpeedPlay, AutoKit).\n\nSelect a projection bridge from your installed apps or open Android Settings.")
                        .setPositiveButton("SELECT APP", (dialog, which) -> showCarPlayPicker())
                        .setNeutralButton("OPEN SETTINGS", (dialog, which) -> {
                            try {
                                startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                            } catch (Exception ignored) {}
                        })
                        .setNegativeButton("CANCEL", null)
                        .show();
            }
        }
    }

    private boolean launchApp(AppInfo app) {
        if (app == null) return false;
        try {
            if (app.getActivityName() != null && !app.getActivityName().isEmpty()) {
                Intent intent = new Intent(Intent.ACTION_MAIN);
                intent.addCategory(Intent.CATEGORY_LAUNCHER);
                intent.setComponent(new ComponentName(app.getPackageName(), app.getActivityName()));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                startActivity(intent);
                return true;
            }
        } catch (Exception ignored) {}
        return launchAppPackage(app.getPackageName());
    }

    private boolean launchAppPackage(String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) return false;
        try {
            PackageManager pm = getPackageManager();
            Intent intent = pm.getLaunchIntentForPackage(packageName);
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                startActivity(intent);
                return true;
            }

            Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
            mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
            mainIntent.setPackage(packageName);
            List<ResolveInfo> activities = pm.queryIntentActivities(mainIntent, 0);
            if (activities != null && !activities.isEmpty()) {
                ResolveInfo info = activities.get(0);
                ComponentName cn = new ComponentName(info.activityInfo.packageName, info.activityInfo.name);
                Intent launchIntent = new Intent(Intent.ACTION_MAIN);
                launchIntent.addCategory(Intent.CATEGORY_LAUNCHER);
                launchIntent.setComponent(cn);
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                startActivity(launchIntent);
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
                continue;
            }
            String label = ri.loadLabel(pm).toString();
            list.add(new AppInfo(label, ri.activityInfo.packageName, ri.activityInfo.name, ri.loadIcon(pm)));
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
        if (thermometerWatcher != null) thermometerWatcher.stop();
        if (deviceSelectorTimer != null) deviceSelectorTimer.cancel();
        if (deviceSelectorDialog != null && deviceSelectorDialog.isShowing()) {
            deviceSelectorDialog.dismiss();
        }
        if (mediaReceiver != null) {
            try {
                unregisterReceiver(mediaReceiver);
            } catch (Exception ignored) {}
        }
    }
}
