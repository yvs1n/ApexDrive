package com.apexdrive.launcher;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.apexdrive.launcher.services.MediaSyncService;
import com.apexdrive.launcher.services.GpsTelemetryManager;
import com.apexdrive.launcher.utils.PreferenceHelper;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * CarPlayActivity - Standalone Apple CarPlay Re-creation Experience for Android Head Units
 * Optimized specifically for 2015 Hyundai Santa Fe (Allwinner T3, 1024x600, Mali-400 GPU).
 */
public class CarPlayActivity extends AppCompatActivity {

    private PreferenceHelper prefHelper;
    private GpsTelemetryManager gpsManager;

    private View layoutDashboard;
    private View layoutSpringboard;
    private ImageButton btnCpHome;

    private TextView tvCpClock;
    private TextView tvCpSpeed;
    private TextView tvCpEtaTime;

    private TextView tvCpMediaTitle;
    private TextView tvCpMediaArtist;
    private TextView tvCpMediaSource;
    private TextView ivCpMediaArt;

    private Button btnCpMediaPlayPause;
    private Button btnCpMediaPrev;
    private Button btnCpMediaNext;
    private Button btnCpMediaSourceToggle;

    private boolean isSpringboardVisible = false;
    private boolean isRadioPlaying = false;
    private boolean isRadioMode = true;
    private double currentRadioFreq = 98.5;

    private final Handler clockHandler = new Handler(Looper.getMainLooper());
    private BroadcastReceiver mediaReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        makeFullScreen();

        prefHelper = new PreferenceHelper(this);
        setContentView(R.layout.activity_carplay);

        initViews();
        initClock();
        initTelemetry();
        initMediaReceiver();
        initDockControls();
        initDashboardControls();
        initSpringboardControls();
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
    public void onBackPressed() {
        if (isSpringboardVisible) {
            showDashboardView();
        } else {
            // Stay in launcher
        }
    }

    private void initViews() {
        layoutDashboard = findViewById(R.id.includeDashboard);
        layoutSpringboard = findViewById(R.id.includeSpringboard);
        btnCpHome = findViewById(R.id.btnCpHome);

        tvCpClock = findViewById(R.id.tvCpClock);
        tvCpSpeed = findViewById(R.id.tvCpSpeed);
        tvCpEtaTime = findViewById(R.id.tvCpEtaTime);

        tvCpMediaTitle = findViewById(R.id.tvCpMediaTitle);
        tvCpMediaArtist = findViewById(R.id.tvCpMediaArtist);
        tvCpMediaSource = findViewById(R.id.tvCpMediaSource);
        ivCpMediaArt = findViewById(R.id.ivCpMediaArt);

        btnCpMediaPlayPause = findViewById(R.id.btnCpMediaPlayPause);
        btnCpMediaPrev = findViewById(R.id.btnCpMediaPrev);
        btnCpMediaNext = findViewById(R.id.btnCpMediaNext);
        btnCpMediaSourceToggle = findViewById(R.id.btnCpMediaSourceToggle);
    }

    private void initDockControls() {
        // Iconic Apple CarPlay Home Button Toggle
        btnCpHome.setOnClickListener(v -> {
            if (isSpringboardVisible) {
                showDashboardView();
            } else {
                showSpringboardView();
            }
        });

        // Dock Recent Apps
        findViewById(R.id.btnDockMap).setOnClickListener(v -> {
            showDashboardView();
            launchOfflineMap();
        });

        findViewById(R.id.btnDockRadio).setOnClickListener(v -> {
            showDashboardView();
            toggleRadioPlayback();
        });

        findViewById(R.id.btnDockPhone).setOnClickListener(v -> {
            showPhoneDialog();
        });
    }

    private void showDashboardView() {
        isSpringboardVisible = false;
        layoutSpringboard.setVisibility(View.GONE);
        layoutDashboard.setVisibility(View.VISIBLE);
        layoutDashboard.setAlpha(0f);
        layoutDashboard.animate().alpha(1f).setDuration(160).start();
        btnCpHome.setImageResource(R.drawable.ic_cp_dots);
    }

    private void showSpringboardView() {
        isSpringboardVisible = true;
        layoutDashboard.setVisibility(View.GONE);
        layoutSpringboard.setVisibility(View.VISIBLE);
        layoutSpringboard.setAlpha(0f);
        layoutSpringboard.animate().alpha(1f).setDuration(160).start();
        btnCpHome.setImageResource(R.drawable.ic_cp_dashboard);
    }

    private void initDashboardControls() {
        // Map Card and Turn Banner click -> OsmAnd
        View cardMap = findViewById(R.id.cardCpMap);
        if (cardMap != null) {
            cardMap.setOnClickListener(v -> launchOfflineMap());
        }

        View maneuverBanner = findViewById(R.id.llCpManeuver);
        if (maneuverBanner != null) {
            maneuverBanner.setOnClickListener(v -> launchOfflineMap());
        }

        View btnOsmAnd = findViewById(R.id.btnCpOsmAndStatus);
        if (btnOsmAnd != null) {
            btnOsmAnd.setOnClickListener(v -> launchOfflineMap());
        }

        // Quick POI Buttons (routes to OsmAnd offline POI lookups)
        findViewById(R.id.btnCpPoiFuel).setOnClickListener(v -> launchPoiSearch("gas station"));
        findViewById(R.id.btnCpPoiPark).setOnClickListener(v -> launchPoiSearch("parking"));
        findViewById(R.id.btnCpPoiCoffee).setOnClickListener(v -> launchPoiSearch("coffee"));
        findViewById(R.id.btnCpPoiClinic).setOnClickListener(v -> launchPoiSearch("hospital"));

        // Media Transport Controls
        btnCpMediaPlayPause.setOnClickListener(v -> {
            if (isRadioMode) {
                toggleRadioPlayback();
            } else {
                MediaSyncService.togglePlayPause(this);
            }
        });

        btnCpMediaPrev.setOnClickListener(v -> {
            if (isRadioMode) {
                tuneRadio(-0.1);
            } else {
                MediaSyncService.sendMediaKey(this, KeyEvent.KEYCODE_MEDIA_PREVIOUS);
            }
        });

        btnCpMediaNext.setOnClickListener(v -> {
            if (isRadioMode) {
                tuneRadio(0.1);
            } else {
                MediaSyncService.sendMediaKey(this, KeyEvent.KEYCODE_MEDIA_NEXT);
            }
        });

        btnCpMediaSourceToggle.setOnClickListener(v -> toggleAudioSource());
    }

    private void initSpringboardControls() {
        findViewById(R.id.btnAppPhone).setOnClickListener(v -> showPhoneDialog());

        findViewById(R.id.btnAppMaps).setOnClickListener(v -> {
            showDashboardView();
            launchOfflineMap();
        });

        findViewById(R.id.btnAppRadio).setOnClickListener(v -> {
            showDashboardView();
            isRadioMode = true;
            updateMediaSourceUi();
            toggleRadioPlayback();
        });

        findViewById(R.id.btnAppSpotify).setOnClickListener(v -> {
            if (!launchAppPackage("com.spotify.music")) {
                Toast.makeText(this, "Spotify not installed", Toast.LENGTH_SHORT).show();
            }
        });

        findViewById(R.id.btnAppMessages).setOnClickListener(v -> {
            Toast.makeText(this, "No new unread messages", Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.btnAppNowPlaying).setOnClickListener(v -> showDashboardView());

        findViewById(R.id.btnAppSantaFe).setOnClickListener(v -> openFactorySettings());

        findViewById(R.id.btnAppSettings).setOnClickListener(v -> openCockpitSettings());
    }

    private void toggleRadioPlayback() {
        if (!isRadioPlaying) {
            isRadioPlaying = true;
            btnCpMediaPlayPause.setText("PAUSE");
            sendRadioHardwareBroadcasts(String.format(Locale.US, "%.1f", currentRadioFreq));
            tvCpMediaTitle.setText(String.format(Locale.US, "FM %.1f MHz", currentRadioFreq));
            tvCpMediaArtist.setText("Radio Tuner Broadcast");
            Toast.makeText(this, "Radio Tuner Playing · " + currentRadioFreq + " MHz", Toast.LENGTH_SHORT).show();
        } else {
            isRadioPlaying = false;
            btnCpMediaPlayPause.setText("PLAY");
            sendRadioHardwarePauseBroadcasts();
            MediaSyncService.pauseAllMedia(this);
            Toast.makeText(this, "Radio Tuner Paused", Toast.LENGTH_SHORT).show();
        }
    }

    private void tuneRadio(double delta) {
        currentRadioFreq = Math.round((currentRadioFreq + delta) * 10) / 10.0;
        if (currentRadioFreq < 87.5) currentRadioFreq = 108.0;
        if (currentRadioFreq > 108.0) currentRadioFreq = 87.5;

        String freqStr = String.format(Locale.US, "%.1f", currentRadioFreq);
        tvCpMediaTitle.setText("FM " + freqStr + " MHz");

        int freqKhz = (int) (currentRadioFreq * 1000);
        try {
            sendBroadcast(new Intent("com.ts.radio.tune").putExtra("freq", freqKhz));
            sendBroadcast(new Intent("com.microntek.radio.tune").putExtra("freq", freqKhz));
            sendBroadcast(new Intent("com.allwinner.radio.tune").putExtra("freq", freqKhz));
            sendBroadcast(new Intent("com.syu.radio.tune").putExtra("freq", freqKhz));
        } catch (Exception ignored) {}
    }

    private void toggleAudioSource() {
        isRadioMode = !isRadioMode;
        updateMediaSourceUi();
    }

    private void updateMediaSourceUi() {
        if (isRadioMode) {
            ivCpMediaArt.setBackgroundResource(R.drawable.bg_cp_squircle_radio);
            ivCpMediaArt.setText("📻");
            tvCpMediaTitle.setText(String.format(Locale.US, "FM %.1f MHz", currentRadioFreq));
            tvCpMediaArtist.setText("Radio Tuner Broadcast");
            tvCpMediaSource.setText("● HARDWARE TUNER (SI473X)");
            tvCpMediaSource.setTextColor(0xFFFF9F0A);
            btnCpMediaPlayPause.setText(isRadioPlaying ? "PAUSE" : "PLAY");
            Toast.makeText(this, "Switched to FM Radio Tuner", Toast.LENGTH_SHORT).show();
        } else {
            ivCpMediaArt.setBackgroundResource(R.drawable.bg_cp_squircle_spotify);
            ivCpMediaArt.setText("🎵");
            tvCpMediaTitle.setText("Spotify Audio");
            tvCpMediaArtist.setText("Connected Bluetooth / Media Stream");
            tvCpMediaSource.setText("● SPOTIFY / MEDIA STREAM");
            tvCpMediaSource.setTextColor(0xFF30D158);
            btnCpMediaPlayPause.setText("PAUSE");
            Toast.makeText(this, "Switched to Media Stream", Toast.LENGTH_SHORT).show();
        }
    }

    private void launchOfflineMap() {
        String configuredPkg = prefHelper.getMapPackage();
        if (configuredPkg != null && !configuredPkg.isEmpty()) {
            if (launchAppPackage(configuredPkg)) return;
        }

        if (launchAppPackage("net.osmand.plus")) return;
        if (launchAppPackage("net.osmand")) return;
        if (launchAppPackage("app.organicmaps")) return;
        if (launchAppPackage("com.mapswithme.maps.pro")) return;
        if (launchAppPackage("ru.dublgis.dgismobile")) return;
        if (launchAppPackage("com.google.android.apps.maps")) return;

        new AlertDialog.Builder(this)
                .setTitle("OSMAND OFFLINE MAP")
                .setMessage("OsmAnd 4.5.10 APK is not installed.\n\nPlease install the APK and UAE map from your USB drive.")
                .setPositiveButton("OPEN FILE MANAGER", (dialog, which) -> {
                    String[] fmPackages = {
                            "com.android.rk", "com.mediatek.filemanager", "com.estrongs.android.pop",
                            "com.android.documentsui", "com.google.android.documentsui"
                    };
                    for (String fm : fmPackages) {
                        if (launchAppPackage(fm)) return;
                    }
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW).setType("*/*").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (Exception ignored) {}
                })
                .setNegativeButton("DISMISS", null)
                .show();
    }

    private void launchPoiSearch(String query) {
        String mapPkg = prefHelper.getMapPackage();
        try {
            Uri geoUri = Uri.parse("geo:0,0?q=" + Uri.encode(query));
            Intent mapIntent = new Intent(Intent.ACTION_VIEW, geoUri);
            mapIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (mapPkg != null && !mapPkg.isEmpty()) {
                mapIntent.setPackage(mapPkg);
                if (mapIntent.resolveActivity(getPackageManager()) != null) {
                    startActivity(mapIntent);
                    return;
                }
            }
            mapIntent.setPackage("net.osmand.plus");
            if (mapIntent.resolveActivity(getPackageManager()) != null) {
                startActivity(mapIntent);
                return;
            }
            mapIntent.setPackage(null);
            if (mapIntent.resolveActivity(getPackageManager()) != null) {
                startActivity(mapIntent);
                return;
            }
        } catch (Exception ignored) {}

        launchOfflineMap();
    }

    private void showPhoneDialog() {
        Intent intent = new Intent(Intent.ACTION_DIAL);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (intent.resolveActivity(getPackageManager()) != null) {
            startActivity(intent);
        } else {
            Toast.makeText(this, "Opening Bluetooth Phone interface...", Toast.LENGTH_SHORT).show();
            launchAppPackage("com.android.bluetooth");
        }
    }

    private void openFactorySettings() {
        new AlertDialog.Builder(this)
                .setTitle("SANTA FE CANBUS SETTINGS")
                .setMessage("Access PIN: 123456 (or 7890)\n\nCANbus Decoder:\n• Box: 03 Simple Soft (XP)\n• Car: 61 Hyundai Kia / 01 13 All new Santafe")
                .setPositiveButton("OPEN ANDROID SETTINGS", (d, w) -> {
                    startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                })
                .setNegativeButton("CLOSE", null)
                .show();
    }

    private void openCockpitSettings() {
        new AlertDialog.Builder(this)
                .setTitle("CARPLAY BETA OPTIONS")
                .setMessage("You are running ApexDrive CarPlay Beta.\n\nWould you like to switch to the Classic Cockpit launcher?")
                .setPositiveButton("LAUNCH CLASSIC COCKPIT", (d, w) -> {
                    Intent intent = new Intent(this, MainActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                })
                .setNegativeButton("DISMISS", null)
                .show();
    }

    private boolean launchAppPackage(String packageName) {
        if (packageName == null || packageName.isEmpty()) return false;
        try {
            Intent intent = getPackageManager().getLaunchIntentForPackage(packageName);
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                startActivity(intent);
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private void sendRadioHardwareBroadcasts(String freqStr) {
        try {
            int freqKhz = 87500;
            try {
                freqKhz = (int) (Double.parseDouble(freqStr) * 1000);
            } catch (Exception ignored) {}

            sendBroadcast(new Intent("com.ts.radio.start"));
            sendBroadcast(new Intent("com.ts.radio.play"));
            sendBroadcast(new Intent("com.ts.radio.tune").putExtra("freq", freqKhz));

            sendBroadcast(new Intent("com.allwinner.radio.power"));
            sendBroadcast(new Intent("com.allwinner.radio.play"));
            sendBroadcast(new Intent("com.allwinner.radio.tune").putExtra("freq", freqKhz));

            sendBroadcast(new Intent("com.microntek.radio.power"));
            sendBroadcast(new Intent("com.microntek.radio.tune").putExtra("freq", freqKhz));
            sendBroadcast(new Intent("com.microntek.media.key").putExtra("keycode", KeyEvent.KEYCODE_MEDIA_PLAY));

            sendBroadcast(new Intent("com.syu.radio.play"));
            sendBroadcast(new Intent("com.syu.radio.tune").putExtra("freq", freqKhz));

            sendBroadcast(new Intent("action.hct.media.key").putExtra("keycode", KeyEvent.KEYCODE_MEDIA_PLAY));
            sendBroadcast(new Intent("com.android.fm.play"));
            sendBroadcast(new Intent("com.android.radio.PLAY"));
        } catch (Exception ignored) {}
    }

    private void sendRadioHardwarePauseBroadcasts() {
        try {
            sendBroadcast(new Intent("com.ts.radio.stop"));
            sendBroadcast(new Intent("com.ts.radio.pause"));
            sendBroadcast(new Intent("com.allwinner.radio.stop"));
            sendBroadcast(new Intent("com.allwinner.radio.pause"));
            sendBroadcast(new Intent("com.microntek.radio.power"));
            sendBroadcast(new Intent("com.microntek.radio.stop"));
            sendBroadcast(new Intent("com.microntek.media.key").putExtra("keycode", KeyEvent.KEYCODE_MEDIA_PAUSE));
            sendBroadcast(new Intent("com.syu.radio.pause"));
            sendBroadcast(new Intent("action.hct.media.key").putExtra("keycode", KeyEvent.KEYCODE_MEDIA_PAUSE));
            sendBroadcast(new Intent("com.android.fm.stop"));
        } catch (Exception ignored) {}
    }

    private void initClock() {
        final SimpleDateFormat sdf = new SimpleDateFormat("h:mm", Locale.getDefault());
        clockHandler.post(new Runnable() {
            @Override
            public void run() {
                Date now = new Date();
                tvCpClock.setText(sdf.format(now));

                Calendar cal = Calendar.getInstance();
                cal.setTime(now);
                cal.add(Calendar.MINUTE, 14);
                if (tvCpEtaTime != null) {
                    tvCpEtaTime.setText(sdf.format(cal.getTime()));
                }

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
                    if (tvCpSpeed != null) {
                        tvCpSpeed.setText(String.valueOf(speed));
                    }
                });
            }

            @Override
            public void onHeadingUpdated(String headingText, float bearingDegrees) {}

            @Override
            public void onCoordinatesUpdated(double latitude, double longitude, double altitudeMeters, float accuracyMeters) {}

            @Override
            public void onTripUpdated(double distanceKm, long elapsedMinutes) {}

            @Override
            public void onGpsStatusChanged(boolean hasFix, int satelliteCount) {}
        });
        gpsManager.start();
    }

    private void initMediaReceiver() {
        mediaReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null || intent.getAction() == null) return;
                if (!isRadioMode) {
                    String title = intent.getStringExtra("title");
                    String artist = intent.getStringExtra("artist");
                    boolean isPlaying = intent.getBooleanExtra("isPlaying", false);

                    if (title != null && !title.isEmpty()) {
                        tvCpMediaTitle.setText(title);
                    }
                    if (artist != null && !artist.isEmpty()) {
                        tvCpMediaArtist.setText(artist);
                    }
                    btnCpMediaPlayPause.setText(isPlaying ? "PAUSE" : "PLAY");
                }
            }
        };

        IntentFilter filter = new IntentFilter("com.apexdrive.launcher.MEDIA_STATUS");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            registerReceiver(mediaReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(mediaReceiver, filter);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (gpsManager != null) gpsManager.stop();
        if (mediaReceiver != null) {
            try {
                unregisterReceiver(mediaReceiver);
            } catch (Exception ignored) {}
        }
        clockHandler.removeCallbacksAndMessages(null);
    }
}
