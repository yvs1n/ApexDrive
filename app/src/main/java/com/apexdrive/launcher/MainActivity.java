package com.apexdrive.launcher;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;

import com.apexdrive.launcher.adapters.AppsGridAdapter;
import com.apexdrive.launcher.models.AppInfo;
import com.apexdrive.launcher.services.BluetoothWatcher;
import com.apexdrive.launcher.services.CarThermometerWatcher;
import com.apexdrive.launcher.services.GpsTelemetryManager;
import com.apexdrive.launcher.services.MediaSyncService;
import com.apexdrive.launcher.utils.Notifier;
import com.apexdrive.launcher.utils.PreferenceHelper;

import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQ_CODE = 1001;

    private PreferenceHelper prefHelper;
    private GpsTelemetryManager gpsManager;
    private BluetoothWatcher btWatcher;
    private CarThermometerWatcher thermometerWatcher;

    private CountDownTimer deviceSelectorTimer;
    private AlertDialog deviceSelectorDialog;
    private boolean isBtConnecting = false;
    private boolean isCarPlayConnected = false;
    private AppsGridAdapter appsAdapter;

    private FrameLayout viewContainer;
    private View btnHeaderRecents;
    private ImageView ivHeaderRecentsIcon;
    private TextView tvHeaderRecentsText;
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
    private Button btnSelectRadioApp;
    private Button btnSelectMapApp;
    private Button btnOpenOsmAnd;
    private Button btnChooseMapApp;
    private Button btnToggleUnit;
    private Button btnSelectDriverPhone;
    private boolean isRadioPlaying = false;

    private TextView tvNavHeadingBig, tvNavCoordinates, tvNavAltitude, tvNavAccuracy, tvNavTripDist, tvNavTripDuration;

    private Notifier notifier;
    private final Handler clockHandler = new Handler(Looper.getMainLooper());
    private BroadcastReceiver mediaReceiver;

    private View layoutStartupLoader;
    private TextView tvStartupSubtitle, tvStartupStatus;
    private ProgressBar pbStartup;
    private BroadcastReceiver dialogBtReceiver;
    private final Handler dialogPollHandler = new Handler(Looper.getMainLooper());
    private Runnable dialogPollRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES); // until Day/Night auto lands
        super.onCreate(savedInstanceState);
        makeFullScreen();

        prefHelper = new PreferenceHelper(this);
        setContentView(R.layout.activity_main);
        notifier = new Notifier(this);

        initViews();
        initNavigation();
        initClock();
        checkPermissions();
        initTelemetry();
        initBluetooth();
        initCarThermometer();
        initMediaReceiver();

        showView(viewDash, railBtnDash);

        // Instant Cockpit Display: Hide startup loader immediately so interface is ready without delay
        if (layoutStartupLoader != null) {
            layoutStartupLoader.setVisibility(View.GONE);
        }
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
        layoutStartupLoader = findViewById(R.id.layoutStartupLoader);
        tvStartupSubtitle = findViewById(R.id.tvStartupSubtitle);
        tvStartupStatus = findViewById(R.id.tvStartupStatus);
        pbStartup = findViewById(R.id.pbStartup);

        viewContainer = findViewById(R.id.viewContainer);
        btnHeaderRecents = findViewById(R.id.btnHeaderRecents);
        ivHeaderRecentsIcon = findViewById(R.id.ivHeaderRecentsIcon);
        tvHeaderRecentsText = findViewById(R.id.tvHeaderRecentsText);
        if (btnHeaderRecents != null) {
            btnHeaderRecents.setOnClickListener(v -> showActiveAppsSwitcherDialog());
        }

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
            pillHeaderBluetooth.setOnClickListener(v -> {
                boolean isBtActive = isCarPlayConnected || (btWatcher != null && (btWatcher.getConnectedDevice() != null || btWatcher.isAudioA2dpActive() || btWatcher.checkVendorBtConnected()));
                if (isBtActive) {
                    launchCarPlay();
                } else {
                    showDeviceSelectorDialog(false);
                }
            });
            pillHeaderBluetooth.setOnLongClickListener(v -> {
                showDeviceSelectorDialog(false);
                return true;
            });
        }
    }

    private void showActiveAppsSwitcherDialog() {
        if (isFinishing()) return;

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_task_switcher, null);
        builder.setView(dialogView);
        AlertDialog dialog = builder.create();

        ImageView ivSwitcherIcon = dialogView.findViewById(R.id.ivSwitcherIcon);
        TextView tvRecentsBadge = dialogView.findViewById(R.id.tvRecentsBadge);
        TextView tvNoRecentTasks = dialogView.findViewById(R.id.tvNoRecentTasks);
        LinearLayout llTasksList = dialogView.findViewById(R.id.llTasksList);
        Button btnCloseAllTasks = dialogView.findViewById(R.id.btnCloseAllTasks);
        Button btnSystemRecents = dialogView.findViewById(R.id.btnSystemRecents);
        Button btnDismissSwitcher = dialogView.findViewById(R.id.btnDismissSwitcher);

        int accentColor = accent();
        if (ivSwitcherIcon != null) {
            ivSwitcherIcon.setColorFilter(accentColor, PorterDuff.Mode.SRC_IN);
        }
        if (tvRecentsBadge != null) {
            tvRecentsBadge.setTextColor(accentColor);
        }

        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        PackageManager pm = getPackageManager();

        // 1. Collect currently running background processes
        Set<String> runningPackages = new HashSet<>();
        if (am != null) {
            try {
                List<ActivityManager.RunningAppProcessInfo> runningProcesses = am.getRunningAppProcesses();
                if (runningProcesses != null) {
                    for (ActivityManager.RunningAppProcessInfo proc : runningProcesses) {
                        if (proc.pkgList != null) {
                            for (String pkg : proc.pkgList) {
                                if (!isSystemOrSelfPackage(pkg)) {
                                    runningPackages.add(pkg);
                                }
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        // 2. Merge with recent apps saved by launcher
        List<String> storedRecents = prefHelper.getRecentApps();
        LinkedHashSet<String> combinedPackages = new LinkedHashSet<>();
        for (String pkg : runningPackages) {
            combinedPackages.add(pkg);
        }
        for (String pkg : storedRecents) {
            if (!isSystemOrSelfPackage(pkg)) {
                combinedPackages.add(pkg);
            }
        }

        List<String> taskList = new ArrayList<>(combinedPackages);
        if (taskList.size() > 12) {
            taskList = taskList.subList(0, 12);
        }

        llTasksList.removeAllViews();
        int activeCount = 0;

        for (String pkg : taskList) {
            try {
                ApplicationInfo appInfo = pm.getApplicationInfo(pkg, 0);
                CharSequence label = pm.getApplicationLabel(appInfo);
                android.graphics.drawable.Drawable icon = pm.getApplicationIcon(appInfo);

                boolean isRunning = runningPackages.contains(pkg);
                if (isRunning) activeCount++;

                View cardView = LayoutInflater.from(this).inflate(R.layout.item_task_card, llTasksList, false);
                ImageView ivIcon = cardView.findViewById(R.id.ivTaskIcon);
                TextView tvName = cardView.findViewById(R.id.tvTaskName);
                TextView tvStatus = cardView.findViewById(R.id.tvTaskStatus);
                Button btnSwitch = cardView.findViewById(R.id.btnSwitchTask);
                Button btnClose = cardView.findViewById(R.id.btnCloseTask);

                if (ivIcon != null) ivIcon.setImageDrawable(icon);
                if (tvName != null) tvName.setText(label);
                if (tvStatus != null) {
                    if (isRunning) {
                        tvStatus.setText("RUNNING IN BACKGROUND");
                        tvStatus.setTextColor(accentColor);
                    } else {
                        tvStatus.setText("RECENT TASK · READY");
                        tvStatus.setTextColor(ContextCompat.getColor(this, R.color.cockpit_text_secondary));
                    }
                }

                if (btnSwitch != null) {
                    btnSwitch.setTextColor(accentColor);
                    btnSwitch.setOnClickListener(v -> {
                        dialog.dismiss();
                        recordRecentApp(pkg);
                        launchAppPackage(pkg);
                    });
                }

                if (btnClose != null) {
                    btnClose.setOnClickListener(v -> {
                        if (am != null) {
                            try {
                                am.killBackgroundProcesses(pkg);
                            } catch (Exception ignored) {}
                        }
                        try {
                            Runtime.getRuntime().exec("am force-stop " + pkg);
                        } catch (Exception ignored) {}
                        try {
                            sendBroadcast(new Intent("com.microntek.kill").putExtra("pkg", pkg));
                        } catch (Exception ignored) {}
                        prefHelper.removeRecentApp(pkg);
                        llTasksList.removeView(cardView);
                        if (llTasksList.getChildCount() == 0) {
                            if (tvNoRecentTasks != null) tvNoRecentTasks.setVisibility(View.VISIBLE);
                            if (tvRecentsBadge != null) tvRecentsBadge.setText("OPTIMIZED");
                            if (btnCloseAllTasks != null) btnCloseAllTasks.setEnabled(false);
                        } else {
                            if (tvRecentsBadge != null) {
                                tvRecentsBadge.setText(llTasksList.getChildCount() + " TASKS");
                            }
                        }
                    });
                }

                llTasksList.addView(cardView);
            } catch (PackageManager.NameNotFoundException ignored) {
                prefHelper.removeRecentApp(pkg);
            }
        }

        if (llTasksList.getChildCount() == 0) {
            if (tvNoRecentTasks != null) tvNoRecentTasks.setVisibility(View.VISIBLE);
            if (tvRecentsBadge != null) tvRecentsBadge.setText("OPTIMIZED");
            if (btnCloseAllTasks != null) btnCloseAllTasks.setEnabled(false);
        } else {
            if (tvNoRecentTasks != null) tvNoRecentTasks.setVisibility(View.GONE);
            if (tvRecentsBadge != null) {
                tvRecentsBadge.setText(activeCount > 0 ? activeCount + " RUNNING" : llTasksList.getChildCount() + " RECENT");
            }
            if (btnCloseAllTasks != null) {
                btnCloseAllTasks.setEnabled(true);
                final List<String> finalTaskList = new ArrayList<>(taskList);
                btnCloseAllTasks.setOnClickListener(v -> {
                    if (am != null) {
                        for (String p : finalTaskList) {
                            try {
                                am.killBackgroundProcesses(p);
                            } catch (Exception ignored) {}
                            try {
                                Runtime.getRuntime().exec("am force-stop " + p);
                            } catch (Exception ignored) {}
                        }
                    }
                    prefHelper.clearRecentApps();
                    llTasksList.removeAllViews();
                    if (tvNoRecentTasks != null) tvNoRecentTasks.setVisibility(View.VISIBLE);
                    if (tvRecentsBadge != null) tvRecentsBadge.setText("RAM OPTIMIZED");
                    btnCloseAllTasks.setEnabled(false);
                    notifier.show(Notifier.Kind.OK, "Background apps closed");
                });
            }
        }

        if (btnSystemRecents != null) {
            btnSystemRecents.setOnClickListener(v -> {
                dialog.dismiss();
                triggerSystemRecents();
            });
        }

        if (btnDismissSwitcher != null) {
            btnDismissSwitcher.setOnClickListener(v -> dialog.dismiss());
        }

        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            int width = (int) (getResources().getDisplayMetrics().widthPixels * 0.75);
            dialog.getWindow().setLayout(Math.max(width, 540), android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private boolean isSystemOrSelfPackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) return true;
        if (pkg.equals(getPackageName())) return true;
        return pkg.equals("android") ||
                pkg.equals("com.android.systemui") ||
                pkg.startsWith("com.google.android.inputmethod") ||
                pkg.startsWith("com.android.inputmethod") ||
                pkg.equals("com.android.phone");
    }

    private void recordRecentApp(String pkg) {
        if (!isSystemOrSelfPackage(pkg)) {
            prefHelper.addRecentApp(pkg);
        }
    }

    private void triggerSystemRecents() {
        // 1. Direct root / shell keyevent for KEYCODE_APP_SWITCH (187)
        try {
            Runtime.getRuntime().exec("input keyevent 187");
            return;
        } catch (Exception ignored) {}

        // 2. Reflection on IStatusBarService toggleRecentApps()
        try {
            Class<?> serviceManager = Class.forName("android.os.ServiceManager");
            Method getService = serviceManager.getMethod("getService", String.class);
            IBinder binder = (IBinder) getService.invoke(null, "statusbar");
            if (binder != null) {
                Class<?> statusBarStub = Class.forName("com.android.internal.statusbar.IStatusBarService$Stub");
                Method asInterface = statusBarStub.getMethod("asInterface", IBinder.class);
                Object statusBar = asInterface.invoke(null, binder);
                if (statusBar != null) {
                    Method toggleRecents = statusBar.getClass().getMethod("toggleRecentApps");
                    toggleRecents.invoke(statusBar);
                    return;
                }
            }
        } catch (Exception ignored) {}

        // 3. Automotive ROM vendor broadcast intents for Task Switcher / Recent Apps
        String[] vendorRecentActions = {
                "com.android.systemui.recent.action.TOGGLE_RECENTS",
                "action.hct.recents",
                "com.syu.recents",
                "com.ts.recents",
                "com.microntek.recents",
                "com.android.internal.policy.impl.recents"
        };
        for (String action : vendorRecentActions) {
            try {
                sendBroadcast(new Intent(action));
            } catch (Exception ignored) {}
        }
    }

    private void setupDashWidgets() {
        tvSpeed = viewDash.findViewById(R.id.tvSpeed);
        tvSpeedUnit = viewDash.findViewById(R.id.tvSpeedUnit);
        tvHeading = viewDash.findViewById(R.id.tvHeading);
        tvTripDistance = viewDash.findViewById(R.id.tvTripDistance);
        tvTripTime = viewDash.findViewById(R.id.tvTripTime);
        tvCarPlayProtocol = viewDash.findViewById(R.id.tvCarPlayProtocol);

        View tileNowPlaying = viewDash.findViewById(R.id.tileNowPlaying);
        if (tileNowPlaying != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            tileNowPlaying.setClipToOutline(true);
            tileNowPlaying.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
        }
        View btnOpenMapsHome = viewDash.findViewById(R.id.btnOpenMapsHome);
        if (btnOpenMapsHome != null) {
            btnOpenMapsHome.setOnClickListener(v -> showView(viewMaps, railBtnMaps));
        }

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
            cardBluetooth.setOnClickListener(v -> {
                boolean isBtActive = isCarPlayConnected || (btWatcher != null && (btWatcher.getConnectedDevice() != null || btWatcher.isAudioA2dpActive() || btWatcher.checkVendorBtConnected()));
                if (isBtActive) {
                    launchCarPlay();
                } else {
                    showDeviceSelectorDialog(false);
                }
            });
            cardBluetooth.setOnLongClickListener(v -> {
                showDeviceSelectorDialog(false);
                return true;
            });
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

        btnMediaPlayPause.setOnClickListener(v -> {
            if (isRadioPlaying) {
                isRadioPlaying = false;
                Button btnRadio = viewAudio != null ? viewAudio.findViewById(R.id.btnRadioPlay) : null;
                if (btnRadio != null) btnRadio.setText("PLAY RADIO");
                TextView tvPreset = viewAudio != null ? viewAudio.findViewById(R.id.tvAudioPresetBadge) : null;
                if (tvPreset != null) tvPreset.setText("STANDBY");
                sendRadioHardwarePauseBroadcasts();
                MediaSyncService.pauseAllMedia(this);
                btnMediaPlayPause.setText("Play");
            } else if (tvTrackTitle != null && tvTrackTitle.getText() != null && tvTrackTitle.getText().toString().startsWith("FM ")) {
                isRadioPlaying = true;
                Button btnRadio = viewAudio != null ? viewAudio.findViewById(R.id.btnRadioPlay) : null;
                if (btnRadio != null) btnRadio.setText("PAUSE RADIO");
                TextView tvFreq = viewAudio != null ? viewAudio.findViewById(R.id.tvRadioFreq) : null;
                String freq = tvFreq != null ? tvFreq.getText().toString() : "87.5";
                TextView tvPreset = viewAudio != null ? viewAudio.findViewById(R.id.tvAudioPresetBadge) : null;
                if (tvPreset != null) tvPreset.setText("ACTIVE · " + freq + " MHZ");
                sendRadioHardwareBroadcasts(freq);
                btnMediaPlayPause.setText("Pause");
            } else {
                MediaSyncService.togglePlayPause(this);
            }
        });

        btnMediaPlayPause.setOnLongClickListener(v -> {
            MediaSyncService.pauseAllMedia(this);
            notifier.show(Notifier.Kind.INFO, "Media paused");
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

        btnOpenOsmAnd = viewMaps.findViewById(R.id.btnOpenOsmAnd);
        if (btnOpenOsmAnd != null) {
            String mapName = prefHelper.getMapName();
            if (mapName != null && !mapName.isEmpty()) {
                btnOpenOsmAnd.setText("LAUNCH " + mapName.toUpperCase());
            }
            btnOpenOsmAnd.setOnClickListener(v -> launchOfflineMap());
        }

        btnChooseMapApp = viewMaps.findViewById(R.id.btnChooseMapApp);
        if (btnChooseMapApp != null) {
            btnChooseMapApp.setOnClickListener(v -> showMapPicker());
        }
    }

    private void checkAndOpenLocationSettings() {
        try {
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (lm != null && !lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                notifier.show(Notifier.Kind.CAUTION, "GPS is off", "Opening location settings");
                startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } else {
                notifier.show(Notifier.Kind.OK, "GPS is on");
            }
        } catch (Exception e) {
            notifier.show(Notifier.Kind.ALERT, "Can't open location settings");
        }
    }

    private void launchOfflineMap() {
        // 1. Try configured package
        String configuredPkg = prefHelper.getMapPackage();
        if (configuredPkg != null && !configuredPkg.isEmpty()) {
            if (launchAppPackage(configuredPkg)) return;
        }

        // 2. Try known OsmAnd variants (Full/Plus or standard)
        if (launchAppPackage("net.osmand.plus")) {
            prefHelper.setMapPackage("net.osmand.plus");
            prefHelper.setMapName("OsmAnd+");
            return;
        }
        if (launchAppPackage("net.osmand")) {
            prefHelper.setMapPackage("net.osmand");
            prefHelper.setMapName("OsmAnd");
            return;
        }

        // 3. Fallbacks to other offline navigators if installed
        if (launchAppPackage("app.organicmaps")) return;
        if (launchAppPackage("com.mapswithme.maps.pro")) return;
        if (launchAppPackage("com.here.app.maps")) return;
        if (launchAppPackage("ru.dublgis.dgismobile")) return;
        if (launchAppPackage("com.google.android.apps.maps")) return;
        if (launchAppPackage("com.waze")) return;

        // 4. OsmAnd not found - show clear dialog guiding user to install from USB
        new AlertDialog.Builder(this)
                .setTitle("OSMAND NOT INSTALLED")
                .setMessage("OsmAnd 4.5.10 APK was not detected on this head unit.\n\n"
                        + "Please sideload the APK and UAE offline map from your USB drive:\n"
                        + "1. Copy net.osmand.plus APK & United-arab-emirates_asia.obf to USB\n"
                        + "2. Insert USB into car head unit\n"
                        + "3. Open File Manager and install OsmAnd APK")
                .setPositiveButton("OPEN FILE MANAGER", (dialog, which) -> {
                    String[] fmPackages = {
                            "com.android.rk", "com.mediatek.filemanager", "com.estrongs.android.pop",
                            "com.android.documentsui", "com.google.android.documentsui", "com.sec.android.app.myfiles"
                    };
                    for (String fm : fmPackages) {
                        if (launchAppPackage(fm)) return;
                    }
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW).setType("*/*").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (Exception e) {
                        notifier.show(Notifier.Kind.INFO, "Open your car's file manager from Apps");
                    }
                })
                .setNeutralButton("CHOOSE MAP APP", (dialog, which) -> showMapPicker())
                .setNegativeButton("DISMISS", null)
                .show();
    }

    private void launchPoiSearch(String query) {
        String mapPkg = prefHelper.getMapPackage();
        try {
            Uri gmmIntentUri = Uri.parse("geo:0,0?q=" + Uri.encode(query));
            Intent mapIntent = new Intent(Intent.ACTION_VIEW, gmmIntentUri);
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
            mapIntent.setPackage("net.osmand");
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

        if (mapPkg != null && launchAppPackage(mapPkg)) return;
        if (launchAppPackage("net.osmand.plus")) return;
        if (launchAppPackage("net.osmand")) return;
        if (launchAppPackage("app.organicmaps")) return;
        if (launchAppPackage("com.mapswithme.maps.pro")) return;
        if (launchAppPackage("ru.dublgis.dgismobile")) return;
        if (launchAppPackage("com.google.android.apps.maps")) return;
        if (launchAppPackage("com.waze")) return;

        new AlertDialog.Builder(this)
                .setTitle("NAVIGATION APP NOT FOUND")
                .setMessage("No navigation engine installed for searching \"" + query.toUpperCase() + "\".\n\nWould you like to install OsmAnd Offline Maps from USB or launch CarPlay Navigation?")
                .setPositiveButton("LAUNCH CARPLAY", (d, w) -> launchCarPlay())
                .setNeutralButton("OFFLINE MAP SETUP", (d, w) -> launchOfflineMap())
                .setNegativeButton("CANCEL", null)
                .show();
    }

    private void setupAudioWidgets() {
        final TextView tvActive = viewAudio.findViewById(R.id.tvAudioActiveSource);
        final TextView tvFreq = viewAudio.findViewById(R.id.tvRadioFreq);
        final TextView tvPreset = viewAudio.findViewById(R.id.tvAudioPresetBadge);
        final Button btnSrcRadio = viewAudio.findViewById(R.id.btnSrcRadio);
        final Button btnSrcSpotify = viewAudio.findViewById(R.id.btnSrcSpotify);
        final Button btnSrcBluetooth = viewAudio.findViewById(R.id.btnSrcBluetooth);
        final Button btnSrcUsb = viewAudio.findViewById(R.id.btnSrcUsb);
        final Button btnRadioPlay = viewAudio.findViewById(R.id.btnRadioPlay);

        int textPrimary = ContextCompat.getColor(this, R.color.cockpit_text_primary);

        btnSrcRadio.setOnClickListener(v -> {
            tvActive.setText("FM RADIO TUNER (SI473X IC)");
            btnSrcRadio.setTextColor(accent());
            btnSrcSpotify.setTextColor(textPrimary);
            btnSrcBluetooth.setTextColor(textPrimary);
            btnSrcUsb.setTextColor(textPrimary);
            if (tvPreset != null) {
                tvPreset.setText(isRadioPlaying ? "ACTIVE TUNER" : "STANDBY · PRESS PLAY");
            }
            notifier.show(Notifier.Kind.INFO, "Radio selected", "Press play to listen");
        });

        if (btnRadioPlay != null) {
            btnRadioPlay.setOnClickListener(v -> {
                if (!isRadioPlaying) {
                    isRadioPlaying = true;
                    btnRadioPlay.setText("PAUSE RADIO");
                    if (tvPreset != null) {
                        tvPreset.setText("ACTIVE · " + tvFreq.getText() + " MHZ");
                    }

                    // 1. Wake up vendor radio hardware tuner IC in background without switching away from launcher
                    sendRadioHardwareBroadcasts(tvFreq.getText().toString());

                    // 2. Update dashboard track display
                    if (tvTrackTitle != null) tvTrackTitle.setText("FM " + tvFreq.getText() + " MHz");
                    if (tvTrackArtist != null) tvTrackArtist.setText("Radio Tuner Broadcast");
                    if (btnMediaPlayPause != null) btnMediaPlayPause.setText("Pause");

                    notifier.show(Notifier.Kind.INFO, "Radio playing", tvFreq.getText() + " MHz");
                } else {
                    isRadioPlaying = false;
                    btnRadioPlay.setText("PLAY RADIO");
                    if (tvPreset != null) {
                        tvPreset.setText("STANDBY");
                    }
                    sendRadioHardwarePauseBroadcasts();
                    MediaSyncService.pauseAllMedia(this);
                    if (btnMediaPlayPause != null) btnMediaPlayPause.setText("Play");
                    notifier.show(Notifier.Kind.INFO, "Radio paused");
                }
            });

            // Long press to open full tuner app if user explicitly wants full-screen controls
            btnRadioPlay.setOnLongClickListener(v -> {
                launchRadioApp();
                return true;
            });
        }

        // Tap frequency readout to open full tuner app
        if (tvFreq != null) {
            tvFreq.setOnClickListener(v -> launchRadioApp());
        }

        viewAudio.findViewById(R.id.btnSrcSpotify).setOnClickListener(v -> {
            tvActive.setText("SPOTIFY AUDIO STREAM");
            btnSrcSpotify.setTextColor(accent());
            btnSrcRadio.setTextColor(textPrimary);
            btnSrcBluetooth.setTextColor(textPrimary);
            btnSrcUsb.setTextColor(textPrimary);
            if (!launchAppPackage("com.spotify.music")) {
                try {
                    Intent musicIntent = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC);
                    musicIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(musicIntent);
                    notifier.show(Notifier.Kind.CAUTION, "Spotify isn't installed", "Opened the media player");
                } catch (Exception e) {
                    notifier.show(Notifier.Kind.CAUTION, "Spotify isn't installed", "Open Apps to choose an audio player");
                }
            }
        });

        viewAudio.findViewById(R.id.btnSrcBluetooth).setOnClickListener(v -> {
            tvActive.setText("BLUETOOTH AUDIO STREAM");
            btnSrcBluetooth.setTextColor(accent());
            btnSrcRadio.setTextColor(textPrimary);
            btnSrcSpotify.setTextColor(textPrimary);
            btnSrcUsb.setTextColor(textPrimary);
            if (btWatcher != null && btWatcher.getConnectedDevice() == null) {
                notifier.show(Notifier.Kind.CAUTION, "No phone connected", "Pair a phone to stream audio");
                showDeviceSelectorDialog(false);
            } else {
                notifier.show(Notifier.Kind.INFO, "Bluetooth audio");
            }
        });

        viewAudio.findViewById(R.id.btnSrcUsb).setOnClickListener(v -> {
            tvActive.setText("USB MASS STORAGE");
            btnSrcUsb.setTextColor(accent());
            btnSrcRadio.setTextColor(textPrimary);
            btnSrcSpotify.setTextColor(textPrimary);
            btnSrcBluetooth.setTextColor(textPrimary);
            String[] usbPackages = {"com.ts.music", "com.microntek.music", "com.syu.music", "com.android.music", "com.android.musicfx"};
            for (String pkg : usbPackages) {
                if (launchAppPackage(pkg)) return;
            }
            try {
                Intent musicIntent = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC);
                musicIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(musicIntent);
                notifier.show(Notifier.Kind.CAUTION, "USB player not found", "Opened the default music app");
            } catch (Exception e) {
                notifier.show(Notifier.Kind.CAUTION, "No USB music player found");
            }
        });

        viewAudio.findViewById(R.id.btnRadioScanDown).setOnClickListener(v -> {
            try {
                double f = Double.parseDouble(tvFreq.getText().toString()) - 0.1;
                String freqStr = String.format(Locale.US, "%.1f", Math.max(87.5, f));
                tvFreq.setText(freqStr);
                if (isRadioPlaying && tvPreset != null) {
                    tvPreset.setText("ACTIVE · " + freqStr + " MHZ");
                }
                if (isRadioPlaying && tvTrackTitle != null) {
                    tvTrackTitle.setText("FM " + freqStr + " MHz");
                }
                try {
                    int freqKhz = (int) (Double.parseDouble(freqStr) * 1000);
                    sendBroadcast(new Intent("com.ts.radio.tune").putExtra("freq", freqKhz));
                    sendBroadcast(new Intent("com.microntek.radio.tune").putExtra("freq", freqKhz));
                    sendBroadcast(new Intent("com.allwinner.radio.tune").putExtra("freq", freqKhz));
                    sendBroadcast(new Intent("com.syu.radio.tune").putExtra("freq", freqKhz));
                } catch (Exception ignored) {}
            } catch (Exception ignored) {}
        });

        viewAudio.findViewById(R.id.btnRadioScanUp).setOnClickListener(v -> {
            try {
                double f = Double.parseDouble(tvFreq.getText().toString()) + 0.1;
                String freqStr = String.format(Locale.US, "%.1f", Math.min(108.0, f));
                tvFreq.setText(freqStr);
                if (isRadioPlaying && tvPreset != null) {
                    tvPreset.setText("ACTIVE · " + freqStr + " MHZ");
                }
                if (isRadioPlaying && tvTrackTitle != null) {
                    tvTrackTitle.setText("FM " + freqStr + " MHz");
                }
                try {
                    int freqKhz = (int) (Double.parseDouble(freqStr) * 1000);
                    sendBroadcast(new Intent("com.ts.radio.tune").putExtra("freq", freqKhz));
                    sendBroadcast(new Intent("com.microntek.radio.tune").putExtra("freq", freqKhz));
                    sendBroadcast(new Intent("com.allwinner.radio.tune").putExtra("freq", freqKhz));
                    sendBroadcast(new Intent("com.syu.radio.tune").putExtra("freq", freqKhz));
                } catch (Exception ignored) {}
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
            notifier.show(Notifier.Kind.ALERT, "Can't open " + app.getLabel(), "The app may be disabled or uninstalled");
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

        btnSelectDriverPhone = viewSetup.findViewById(R.id.btnSelectDriverPhone);
        if (btnSelectDriverPhone != null) {
            btnSelectDriverPhone.setOnClickListener(v -> showDeviceSelectorDialog(false));
        }

        btnSelectCarPlayApp = viewSetup.findViewById(R.id.btnSelectCarPlayApp);
        btnSelectCarPlayApp.setText(prefHelper.getCarPlayName());
        btnSelectCarPlayApp.setOnClickListener(v -> showCarPlayPicker());

        btnSelectRadioApp = viewSetup.findViewById(R.id.btnSelectRadioApp);
        if (btnSelectRadioApp != null) {
            btnSelectRadioApp.setText(prefHelper.getRadioName());
            btnSelectRadioApp.setOnClickListener(v -> showRadioPicker());
        }

        btnSelectMapApp = viewSetup.findViewById(R.id.btnSelectMapApp);
        if (btnSelectMapApp != null) {
            btnSelectMapApp.setText(prefHelper.getMapName());
            btnSelectMapApp.setOnClickListener(v -> showMapPicker());
        }

        btnToggleUnit = viewSetup.findViewById(R.id.btnToggleUnit);
        btnToggleUnit.setText(prefHelper.getSpeedUnit());
        btnToggleUnit.setOnClickListener(v -> toggleSpeedUnit());

        Button btnGpsRefresh = viewSetup.findViewById(R.id.btnGpsRefreshRate);
        if (btnGpsRefresh != null) {
            btnGpsRefresh.setOnClickListener(v -> {
                if (gpsManager != null) {
                    gpsManager.start();
                }
                notifier.show(Notifier.Kind.INFO, "GPS restarted");
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
        if (tvSpeedUnit != null) tvSpeedUnit.setText(next.toLowerCase(Locale.US));
        if (gpsManager != null) {
            gpsManager.setUseMph(next.equals(PreferenceHelper.UNIT_MPH));
        }
        if (thermometerWatcher != null) {
            thermometerWatcher.setUseFahrenheit(next.equals(PreferenceHelper.UNIT_MPH));
        }
        notifier.show(Notifier.Kind.INFO, "Speedometer set to " + next);
    }

    private void resetTrip() {
        if (gpsManager != null) {
            gpsManager.resetTrip();
            boolean isMph = prefHelper.getSpeedUnit().equals(PreferenceHelper.UNIT_MPH);
            String distText = isMph ? "Trip 0.0 mi" : "Trip 0.0 km";
            if (tvTripDistance != null) tvTripDistance.setText(distText);
            if (tvTripTime != null) tvTripTime.setText("0 min");
            if (tvNavTripDist != null) tvNavTripDist.setText(distText);
            if (tvNavTripDuration != null) tvNavTripDuration.setText("Duration 0 min");
            notifier.show(Notifier.Kind.OK, "Trip reset");
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
            notifier.show(Notifier.Kind.CAUTION, "No equalizer app found");
        }
    }

    private void openBluetoothManager() {
        if (btWatcher != null) {
            btWatcher.forceRefresh();
        }
        String[] btPackages = {
                "com.allwinner.bluetooth",
                "com.allwinner.radio",
                "com.goc.bluetooth",
                "com.android.ecar",
                "com.microntek.bluetooth",
                "com.ts.bt",
                "com.syu.bt",
                "com.forfan.bt",
                "cn.manstep.phonemirrorbox",
                "com.android.bluetooth"
        };
        for (String pkg : btPackages) {
            if (launchAppPackage(pkg)) return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            notifier.show(Notifier.Kind.INFO, "Opening Bluetooth settings");
        }
    }

    private void showCarPlayPicker() {
        final List<AppInfo> apps = loadInstalledApps();
        List<String> names = new ArrayList<>();
        final List<String> packages = new ArrayList<>();

        names.add("DiPlay (Open-Source)");
        packages.add("com.shihab.diplay");

        names.add("TLink / Zlink 5.3 (Factory)");
        packages.add("com.zjinnova.zlink");

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
        notifier.show(Notifier.Kind.OK, "CarPlay app set", name);
    }

    private void showRadioPicker() {
        final List<AppInfo> apps = loadInstalledApps();
        List<String> names = new ArrayList<>();
        final List<String> packages = new ArrayList<>();

        String[] known = {
                "Allwinner Radio (com.allwinner.radio)",
                "SYU Radio (com.syu.radio)",
                "TopWay Radio (com.ts.radio)",
                "Microntek Radio (com.microntek.radio)",
                "Android Radio Tuner (com.android.radio)",
                "Car FM Tuner (com.car.radio)"
        };
        String[] knownPkgs = {
                "com.allwinner.radio",
                "com.syu.radio",
                "com.ts.radio",
                "com.microntek.radio",
                "com.android.radio",
                "com.car.radio"
        };
        for (int i = 0; i < known.length; i++) {
            names.add(known[i]);
            packages.add(knownPkgs[i]);
        }

        for (AppInfo app : apps) {
            if (!packages.contains(app.getPackageName())) {
                names.add(app.getLabel() + " (" + app.getPackageName() + ")");
                packages.add(app.getPackageName());
            }
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Select Radio Tuner App");
        builder.setItems(names.toArray(new String[0]), (dialog, which) -> {
            String selectedPkg = packages.get(which);
            String selectedName = names.get(which).split(" \\(")[0];
            saveRadioApp(selectedPkg, selectedName);
        });
        builder.show();
    }

    private void saveRadioApp(String pkg, String name) {
        prefHelper.setRadioPackage(pkg);
        prefHelper.setRadioName(name);
        if (btnSelectRadioApp != null) {
            btnSelectRadioApp.setText(name);
        }
        notifier.show(Notifier.Kind.OK, "Radio app set", name);
    }

    private void launchRadioApp() {
        String configuredPkg = prefHelper.getRadioPackage();
        boolean launched = false;
        if (configuredPkg != null && !configuredPkg.isEmpty()) {
            launched = launchAppPackage(configuredPkg);
        }
        if (!launched) {
            String detectedPkg = findInstalledRadioPackage();
            if (detectedPkg != null) {
                prefHelper.setRadioPackage(detectedPkg);
                try {
                    ApplicationInfo ai = getPackageManager().getApplicationInfo(detectedPkg, 0);
                    prefHelper.setRadioName(getPackageManager().getApplicationLabel(ai).toString());
                } catch (Exception ignored) {}
                launched = launchAppPackage(detectedPkg);
            }
        }
        if (!launched) {
            notifier.show(Notifier.Kind.CAUTION, "No radio app found", "Choose your car's tuner app in Settings");
            showRadioPicker();
        }
    }

    private void showMapPicker() {
        final List<AppInfo> apps = loadInstalledApps();
        List<String> names = new ArrayList<>();
        final List<String> packages = new ArrayList<>();

        String[] known = {
                "OsmAnd+ Offline (net.osmand.plus)",
                "OsmAnd Standard (net.osmand)",
                "Organic Maps Offline (app.organicmaps)",
                "Maps.Me Offline (com.mapswithme.maps.pro)",
                "HERE WeGo Offline (com.here.app.maps)",
                "2GIS Navigation (ru.dublgis.dgismobile)",
                "Google Maps (com.google.android.apps.maps)",
                "Waze (com.waze)"
        };
        String[] knownPkgs = {
                "net.osmand.plus",
                "net.osmand",
                "app.organicmaps",
                "com.mapswithme.maps.pro",
                "com.here.app.maps",
                "ru.dublgis.dgismobile",
                "com.google.android.apps.maps",
                "com.waze"
        };
        for (int i = 0; i < known.length; i++) {
            names.add(known[i]);
            packages.add(knownPkgs[i]);
        }

        for (AppInfo app : apps) {
            if (!packages.contains(app.getPackageName())) {
                names.add(app.getLabel() + " (" + app.getPackageName() + ")");
                packages.add(app.getPackageName());
            }
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Select Navigation Map Engine");
        builder.setItems(names.toArray(new String[0]), (dialog, which) -> {
            String selectedPkg = packages.get(which);
            String selectedName = names.get(which).split(" \\(")[0];
            saveMapApp(selectedPkg, selectedName);
        });
        builder.show();
    }

    private void saveMapApp(String pkg, String name) {
        prefHelper.setMapPackage(pkg);
        prefHelper.setMapName(name);
        if (btnSelectMapApp != null) {
            btnSelectMapApp.setText(name);
        }
        if (btnOpenOsmAnd != null) {
            btnOpenOsmAnd.setText("LAUNCH " + name.toUpperCase());
        }
        notifier.show(Notifier.Kind.OK, "Map app set", name);
    }

    private String findInstalledRadioPackage() {
        PackageManager pm = getPackageManager();
        String[] knownRadioPackages = {
                "com.allwinner.radio",
                "com.softwinner.radio",
                "com.syu.radio",
                "com.ts.radio",
                "com.microntek.radio",
                "com.microntek.fm",
                "com.android.radio",
                "com.car.radio",
                "com.car.fm",
                "com.autonavi.radio",
                "com.hct.radio",
                "com.xy.radio",
                "com.mediatek.radio",
                "com.nwd.radio",
                "com.yecon.radio",
                "com.forfan.radio",
                "com.sds.radio",
                "cn.flyaudio.radio",
                "com.roadrover.radio",
                "com.chaochuang.radio",
                "com.tw.radio",
                "com.t3.radio",
                "com.dingwei.radio"
        };
        for (String pkg : knownRadioPackages) {
            try {
                pm.getPackageInfo(pkg, 0);
                return pkg;
            } catch (PackageManager.NameNotFoundException ignored) {}
        }

        Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = pm.queryIntentActivities(mainIntent, 0);
        if (apps != null) {
            for (ResolveInfo info : apps) {
                String pkg = info.activityInfo.packageName;
                if (pkg.equals(getPackageName())) continue;
                String label = info.loadLabel(pm).toString().toLowerCase(Locale.US);
                String pkgLower = pkg.toLowerCase(Locale.US);
                if (pkgLower.contains("radio") || pkgLower.contains("tuner") ||
                        label.contains("radio") || label.contains("fm tuner") || label.equals("fm")) {
                    return pkg;
                }
            }
        }
        return null;
    }

    private void sendRadioHardwareBroadcasts(String freqStr) {
        try {
            int freqKhz = 87500;
            try {
                freqKhz = (int) (Double.parseDouble(freqStr) * 1000);
            } catch (Exception ignored) {}

            // Topway / Allwinner T3 hardware tuner broadcasts
            sendBroadcast(new Intent("com.ts.radio.start"));
            sendBroadcast(new Intent("com.ts.radio.play"));
            sendBroadcast(new Intent("com.ts.radio.tune").putExtra("freq", freqKhz));

            // Allwinner specific tuner intents
            sendBroadcast(new Intent("com.allwinner.radio.power"));
            sendBroadcast(new Intent("com.allwinner.radio.play"));
            sendBroadcast(new Intent("com.allwinner.radio.tune").putExtra("freq", freqKhz));

            // Microntek / Rockchip / PX head units
            sendBroadcast(new Intent("com.microntek.radio.power"));
            sendBroadcast(new Intent("com.microntek.radio.tune").putExtra("freq", freqKhz));
            sendBroadcast(new Intent("com.microntek.media.key").putExtra("keycode", KeyEvent.KEYCODE_MEDIA_PLAY));

            // SYU / FYT / Joying head units
            sendBroadcast(new Intent("com.syu.radio.play"));
            sendBroadcast(new Intent("com.syu.radio.tune").putExtra("freq", freqKhz));

            // HCT / MCU hardware broadcast key
            sendBroadcast(new Intent("action.hct.media.key").putExtra("keycode", KeyEvent.KEYCODE_MEDIA_PLAY));

            // Standard Android FM Tuner intent
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

    /** The single design-system accent (Day and Night variants come from resources). */
    private int accent() {
        return ContextCompat.getColor(this, R.color.ad_accent_text);
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
        int inactive = ContextCompat.getColor(this, R.color.ad_label_3);
        int active = accent();
        View[] buttons = {railBtnDash, railBtnCarPlay, railBtnMaps, railBtnAudio, railBtnApps, railBtnSetup};
        ImageView[] icons = {ivDashIcon, ivCarPlayIcon, ivMapsIcon, ivAudioIcon, ivAppsIcon, ivSetupIcon};
        TextView[] labels = {tvDashLabel, tvCarPlayLabel, tvMapsLabel, tvAudioLabel, tvAppsLabel, tvSetupLabel};
        for (int i = 0; i < buttons.length; i++) {
            boolean on = buttons[i] == activeRailBtn;
            int color = on ? active : inactive;
            buttons[i].setSelected(on);
            icons[i].setColorFilter(color, PorterDuff.Mode.SRC_IN);
            labels[i].setTextColor(color);
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
                    tvSpeedUnit.setText(unit.toLowerCase(Locale.US));
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
                    if (thermometerWatcher != null && Math.abs(latitude) > 0.01 && Math.abs(longitude) > 0.01) {
                        thermometerWatcher.onLocationUpdated(latitude, longitude);
                    }
                });
            }

            @Override
            public void onTripUpdated(double distanceKm, long elapsedMinutes) {
                runOnUiThread(() -> {
                    boolean isMph = prefHelper.getSpeedUnit().equals(PreferenceHelper.UNIT_MPH);
                    String distText;
                    if (isMph) {
                        distText = String.format(Locale.US, "Trip %.1f mi", distanceKm * 0.621371);
                    } else {
                        distText = String.format(Locale.US, "Trip %.1f km", distanceKm);
                    }
                    tvTripDistance.setText(distText);
                    tvTripTime.setText(String.format(Locale.US, "%d min", elapsedMinutes));
                    if (tvNavTripDist != null) {
                        tvNavTripDist.setText(distText);
                    }
                    if (tvNavTripDuration != null) {
                        tvNavTripDuration.setText(String.format(Locale.US, "Duration %d min", elapsedMinutes));
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
            isCarPlayConnected = isConnected;
            if (isConnected) {
                updateConnectedPhoneUi(deviceName, details);
            } else {
                updateDisconnectedPhoneUi();
            }
        }));
        btWatcher.start();
    }

    private void updateConnectedPhoneUi(String deviceName, String details) {
        int accent = accent();
        if (tvHeaderBluetooth != null) {
            tvHeaderBluetooth.setText(deviceName != null ? deviceName : "Phone connected");
            tvHeaderBluetooth.setTextColor(accent);
        }
        if (ivHeaderBtIcon != null) {
            ivHeaderBtIcon.setColorFilter(accent, PorterDuff.Mode.SRC_IN);
        }
        if (tvBtDeviceName != null) {
            tvBtDeviceName.setText(deviceName != null ? deviceName.toUpperCase() : "DRIVER PHONE");
        }
        if (tvBtProfileDetail != null) {
            tvBtProfileDetail.setText(details != null ? details.toUpperCase() : "WIRELESS CARPLAY & AUDIO");
        }
        if (tvBtStatusBadge != null) {
            tvBtStatusBadge.setText("● ACTIVE");
            tvBtStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_active));
        }
    }

    private void updateDisconnectedPhoneUi() {
        String savedAddr = prefHelper.getSelectedDriverDevice();
        String savedName = getDeviceNameFromAddress(savedAddr);

        if (isCarPlayConnected && savedName != null) {
            updateConnectedPhoneUi(savedName, "WIRELESS PROJECTION ACTIVE");
            return;
        }

        int textSec = ContextCompat.getColor(this, R.color.cockpit_text_secondary);
        int textTert = ContextCompat.getColor(this, R.color.cockpit_text_tertiary);

        if (savedName != null) {
            if (tvHeaderBluetooth != null) {
                tvHeaderBluetooth.setText(savedName);
                tvHeaderBluetooth.setTextColor(textSec);
            }
            if (ivHeaderBtIcon != null) {
                ivHeaderBtIcon.setColorFilter(textTert, PorterDuff.Mode.SRC_IN);
            }
            if (tvBtDeviceName != null) {
                tvBtDeviceName.setText(savedName.toUpperCase());
            }
            if (tvBtProfileDetail != null) {
                tvBtProfileDetail.setText("CARPLAY READY · TAP TO LAUNCH");
            }
            if (tvBtStatusBadge != null) {
                tvBtStatusBadge.setText("○ READY");
                tvBtStatusBadge.setTextColor(textSec);
            }
        } else {
            if (tvHeaderBluetooth != null) {
                tvHeaderBluetooth.setText("Not connected");
                tvHeaderBluetooth.setTextColor(textSec);
            }
            if (ivHeaderBtIcon != null) {
                ivHeaderBtIcon.setColorFilter(textTert, PorterDuff.Mode.SRC_IN);
            }
            if (tvBtDeviceName != null) {
                tvBtDeviceName.setText("NO DEVICE CONNECTED");
            }
            if (tvBtProfileDetail != null) {
                tvBtProfileDetail.setText("CARPLAY LINK · READY");
            }
            if (tvBtStatusBadge != null) {
                tvBtStatusBadge.setText("○ STANDBY");
                tvBtStatusBadge.setTextColor(textSec);
            }
        }
    }

    @SuppressLint("MissingPermission")
    private String getDeviceNameFromAddress(String address) {
        if (address == null || address.isEmpty()) return null;
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null && adapter.isEnabled()) {
            try {
                for (BluetoothDevice dev : adapter.getBondedDevices()) {
                    if (address.equalsIgnoreCase(dev.getAddress())) {
                        String name = dev.getName();
                        if (name != null && !name.isEmpty()) return name;
                    }
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    private void initCarThermometer() {
        thermometerWatcher = new CarThermometerWatcher(this, (tempFormatted, tempCelsius, condition) -> runOnUiThread(() -> {
            if (tvWeatherText != null) {
                tvWeatherText.setText(tempFormatted != null ? tempFormatted : "--");
            }
            if (tvWeatherIcon != null) {
                tvWeatherIcon.setText(condition != null ? condition : "");
            }
        }));
        thermometerWatcher.setUseFahrenheit(prefHelper.getSpeedUnit().equals(PreferenceHelper.UNIT_MPH));
        thermometerWatcher.start();
    }

    private void cycleWeatherState() {
        showTemperatureCalibrationDialog();
    }

    private void showTemperatureCalibrationDialog() {
        if (isFinishing() || thermometerWatcher == null) return;

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 36, 40, 36);
        layout.setBackgroundColor(0xFF0F172A);

        TextView title = new TextView(this);
        title.setText("VEHICLE THERMOMETER CALIBRATION");
        title.setTextColor(accent());
        title.setTextSize(13);
        title.setTypeface(null, Typeface.BOLD);
        title.setLetterSpacing(0.08f);
        layout.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Calibrate to match your Hyundai instrument cluster outside temperature or sync via online GPS weather.");
        subtitle.setTextColor(0xFF94A3B8);
        subtitle.setTextSize(11);
        subtitle.setPadding(0, 8, 0, 24);
        layout.addView(subtitle);

        final TextView tvCurrentVal = new TextView(this);
        final float[] tempHolder = new float[]{ thermometerWatcher.getCurrentTempCelsius() };
        final boolean isMph = prefHelper.getSpeedUnit().equals(PreferenceHelper.UNIT_MPH);
        updateTempDisplay(tvCurrentVal, tempHolder[0], isMph);
        tvCurrentVal.setTextSize(42);
        tvCurrentVal.setTextColor(0xFFF8FAFC);
        tvCurrentVal.setTypeface(null, Typeface.BOLD);
        tvCurrentVal.setGravity(Gravity.CENTER);
        tvCurrentVal.setPadding(0, 10, 0, 24);
        layout.addView(tvCurrentVal);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER);

        Button btnMinus = new Button(this);
        btnMinus.setText("− 1°");
        btnMinus.setTextSize(16);
        btnMinus.setTextColor(0xFFF8FAFC);
        btnMinus.setBackgroundResource(R.drawable.bg_elevated_btn);
        btnMinus.setLayoutParams(new LinearLayout.LayoutParams(0, 110, 1f));
        btnMinus.setOnClickListener(v -> {
            tempHolder[0] -= 1.0f;
            updateTempDisplay(tvCurrentVal, tempHolder[0], isMph);
        });

        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(24, 110));

        Button btnPlus = new Button(this);
        btnPlus.setText("+ 1°");
        btnPlus.setTextSize(16);
        btnPlus.setTextColor(0xFFF8FAFC);
        btnPlus.setBackgroundResource(R.drawable.bg_elevated_btn);
        btnPlus.setLayoutParams(new LinearLayout.LayoutParams(0, 110, 1f));
        btnPlus.setOnClickListener(v -> {
            tempHolder[0] += 1.0f;
            updateTempDisplay(tvCurrentVal, tempHolder[0], isMph);
        });

        btnRow.addView(btnMinus);
        btnRow.addView(spacer);
        btnRow.addView(btnPlus);
        layout.addView(btnRow);

        Button btnSyncGps = new Button(this);
        btnSyncGps.setText("SYNC LIVE GPS WEATHER");
        btnSyncGps.setTextSize(11);
        btnSyncGps.setTextColor(0xFF94A3B8);
        btnSyncGps.setBackgroundResource(R.drawable.bg_elevated_btn);
        LinearLayout.LayoutParams lpGps = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 110);
        lpGps.setMargins(0, 18, 0, 0);
        btnSyncGps.setLayoutParams(lpGps);

        Button btnSave = new Button(this);
        btnSave.setText("MATCH & LOCK TEMPERATURE");
        btnSave.setTextSize(11);
        btnSave.setTextColor(accent());
        btnSave.setBackgroundResource(R.drawable.bg_elevated_btn);
        LinearLayout.LayoutParams lpSave = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 110);
        lpSave.setMargins(0, 12, 0, 0);
        btnSave.setLayoutParams(lpSave);

        layout.addView(btnSyncGps);
        layout.addView(btnSave);

        builder.setView(layout);
        AlertDialog dialog = builder.create();

        btnSyncGps.setOnClickListener(v -> {
            prefHelper.setAutoWeatherEnabled(true);
            thermometerWatcher.clearManualCalibration();
            Location loc = (gpsManager != null) ? gpsManager.getLastLocation() : null;
            if (loc != null && Math.abs(loc.getLatitude()) > 0.01 && Math.abs(loc.getLongitude()) > 0.01) {
                thermometerWatcher.onLocationUpdated(loc.getLatitude(), loc.getLongitude());
            } else {
                thermometerWatcher.triggerImmediateLocationWeatherSync();
            }
            notifier.show(Notifier.Kind.INFO, "Weather uses GPS location", "Updating for the vehicle's position");
            dialog.dismiss();
        });

        btnSave.setOnClickListener(v -> {
            float raw = thermometerWatcher.getRawTempCelsius();
            float target = tempHolder[0];
            float offset = target - raw;
            thermometerWatcher.setTempOffset(offset);
            notifier.show(Notifier.Kind.OK, "Temperature calibrated", String.format(Locale.US, "Offset %+.1f°C", offset));
            dialog.dismiss();
        });

        dialog.show();
    }

    private void updateTempDisplay(TextView tv, float tempC, boolean isMph) {
        if (isMph) {
            float tempF = (tempC * 9f / 5f) + 32f;
            tv.setText(String.format(Locale.US, "%.0f°F", tempF));
        } else {
            tv.setText(String.format(Locale.US, "%.0f°C", tempC));
        }
    }

    private void cleanupDialogBtReceivers() {
        if (dialogBtReceiver != null) {
            try {
                unregisterReceiver(dialogBtReceiver);
            } catch (Exception ignored) {}
            dialogBtReceiver = null;
        }
        if (dialogPollRunnable != null) {
            dialogPollHandler.removeCallbacks(dialogPollRunnable);
            dialogPollRunnable = null;
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
        cleanupDialogBtReceivers();

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_device_selector, null);
        builder.setView(dialogView);
        deviceSelectorDialog = builder.create();

        if (deviceSelectorDialog.getWindow() != null) {
            deviceSelectorDialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        TextView tvCountdown = dialogView.findViewById(R.id.tvCountdownTimer);
        LinearLayout deviceContainer = dialogView.findViewById(R.id.deviceListContainer);
        View layoutDeviceLoading = dialogView.findViewById(R.id.layoutDeviceLoading);
        ProgressBar pbDeviceLoading = dialogView.findViewById(R.id.pbDeviceLoading);
        TextView tvDeviceLoadingText = dialogView.findViewById(R.id.tvDeviceLoadingText);
        Button btnPair = dialogView.findViewById(R.id.btnPairNewDevice);
        Button btnSkip = dialogView.findViewById(R.id.btnSkipSelector);

        int accentColor = accent();
        if (btnSkip != null) btnSkip.setTextColor(accentColor);
        if (tvCountdown != null) tvCountdown.setTextColor(accentColor);
        if (tvDeviceLoadingText != null) tvDeviceLoadingText.setTextColor(accentColor);
        if (pbDeviceLoading != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            pbDeviceLoading.setIndeterminateTintList(ColorStateList.valueOf(accentColor));
        }

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null && !adapter.isEnabled()) {
            try {
                adapter.enable();
            } catch (Exception ignored) {}
        }

        populateDeviceListWithLoader(dialogView, tvCountdown, deviceContainer, layoutDeviceLoading, adapter, accentColor, 0);

        if (btnPair != null) {
            btnPair.setOnClickListener(v -> {
                cleanupDialogBtReceivers();
                if (deviceSelectorTimer != null) deviceSelectorTimer.cancel();
                if (deviceSelectorDialog != null) deviceSelectorDialog.dismiss();
                openBluetoothManager();
            });
        }

        if (btnSkip != null) {
            btnSkip.setOnClickListener(v -> {
                cleanupDialogBtReceivers();
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
                    cleanupDialogBtReceivers();
                    if (deviceSelectorDialog != null && deviceSelectorDialog.isShowing()) {
                        deviceSelectorDialog.dismiss();
                    }
                    String saved = prefHelper.getSelectedDriverDevice();
                    if (saved != null && !saved.isEmpty() && btWatcher != null) {
                        BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
                        if (a != null && a.isEnabled()) {
                            try {
                                BluetoothDevice dev = a.getRemoteDevice(saved);
                                if (dev != null) {
                                    btWatcher.signalConnect(dev);
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                }
            }.start();
        } else {
            if (tvCountdown != null) tvCountdown.setVisibility(View.GONE);
        }

        deviceSelectorDialog.setOnDismissListener(dialog -> {
            cleanupDialogBtReceivers();
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

    private void populateDeviceListWithLoader(View dialogView, TextView tvCountdown, LinearLayout deviceContainer, View layoutDeviceLoading, BluetoothAdapter adapter, int accentColor, int attempt) {
        @SuppressLint("MissingPermission")
        java.util.Set<BluetoothDevice> pairedDevices = (adapter != null && adapter.isEnabled()) ? adapter.getBondedDevices() : null;

        if (pairedDevices != null && !pairedDevices.isEmpty()) {
            if (layoutDeviceLoading != null) layoutDeviceLoading.setVisibility(View.GONE);
            renderDeviceCards(pairedDevices, deviceContainer, tvCountdown, accentColor);
            return;
        }

        // Bluetooth stack waking up during cold boot: poll up to 8 times (4.8s) and listen for broadcast state changes
        if (attempt < 8) {
            if (layoutDeviceLoading != null) layoutDeviceLoading.setVisibility(View.VISIBLE);
            if (deviceContainer != null) deviceContainer.removeAllViews();

            if (dialogBtReceiver == null) {
                dialogBtReceiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        if (intent == null) return;
                        String action = intent.getAction();
                        if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action) ||
                                BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
                            BluetoothAdapter currentAdapter = BluetoothAdapter.getDefaultAdapter();
                            @SuppressLint("MissingPermission")
                            java.util.Set<BluetoothDevice> devices = (currentAdapter != null && currentAdapter.isEnabled()) ? currentAdapter.getBondedDevices() : null;
                            if (devices != null && !devices.isEmpty()) {
                                cleanupDialogBtReceivers();
                                if (layoutDeviceLoading != null) layoutDeviceLoading.setVisibility(View.GONE);
                                renderDeviceCards(devices, deviceContainer, tvCountdown, accentColor);
                            }
                        }
                    }
                };
                IntentFilter filter = new IntentFilter();
                filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
                filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
                registerReceiver(dialogBtReceiver, filter);
            }

            dialogPollRunnable = () -> {
                if (deviceSelectorDialog != null && deviceSelectorDialog.isShowing()) {
                    BluetoothAdapter currAdapter = BluetoothAdapter.getDefaultAdapter();
                    populateDeviceListWithLoader(dialogView, tvCountdown, deviceContainer, layoutDeviceLoading, currAdapter, accentColor, attempt + 1);
                }
            };
            dialogPollHandler.postDelayed(dialogPollRunnable, 600);
        } else {
            // Polling timeout: display clean empty state
            cleanupDialogBtReceivers();
            if (layoutDeviceLoading != null) layoutDeviceLoading.setVisibility(View.GONE);
            if (deviceContainer != null) {
                deviceContainer.removeAllViews();
                TextView tvEmpty = new TextView(this);
                tvEmpty.setText("No paired phones detected in range.\nTap \"Pair New Phone\" to link your phone via Bluetooth.");
                tvEmpty.setTextColor(ContextCompat.getColor(this, R.color.cockpit_text_secondary));
                tvEmpty.setTextSize(13);
                tvEmpty.setPadding(16, 24, 16, 24);
                tvEmpty.setGravity(android.view.Gravity.CENTER);
                deviceContainer.addView(tvEmpty);
            }
        }
    }

    @SuppressLint("MissingPermission")
    private void renderDeviceCards(java.util.Set<BluetoothDevice> pairedDevices, LinearLayout deviceContainer, TextView tvCountdown, int accentColor) {
        if (deviceContainer == null) return;
        deviceContainer.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        String savedAddr = prefHelper.getSelectedDriverDevice();

        for (BluetoothDevice device : pairedDevices) {
            View card = inflater.inflate(R.layout.item_device_card, deviceContainer, false);
            TextView tvName = card.findViewById(R.id.tvDialogDeviceName);
            TextView tvStatus = card.findViewById(R.id.tvDialogDeviceStatus);
            TextView btnConnect = card.findViewById(R.id.btnConnectPill);

            @SuppressLint("MissingPermission")
            String name = (device.getName() != null && !device.getName().isEmpty()) ? device.getName() : "Phone (" + device.getAddress() + ")";
            tvName.setText(name.toUpperCase());

            boolean isActuallyConnected = (btWatcher != null && btWatcher.isDeviceCurrentlyConnected(device));
            boolean isSavedDriver = (savedAddr != null && savedAddr.equalsIgnoreCase(device.getAddress()));
            if (isActuallyConnected || (isCarPlayConnected && isSavedDriver)) {
                tvStatus.setText("CONNECTED · READY FOR CARPLAY");
                tvStatus.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.status_active));
                tvName.setTextColor(accentColor);
                btnConnect.setText("LAUNCH");
                btnConnect.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.status_active));
            } else if (isSavedDriver) {
                tvStatus.setText("DEFAULT DRIVER · READY TO CONNECT");
                tvStatus.setTextColor(accentColor);
                btnConnect.setText("CONNECT");
                btnConnect.setTextColor(accentColor);
            } else {
                tvStatus.setText("PAIRED · READY TO CONNECT");
                tvStatus.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.cockpit_text_secondary));
                btnConnect.setText("CONNECT");
                btnConnect.setTextColor(accentColor);
            }

            View.OnClickListener selectListener = v -> {
                if (isBtConnecting) return;

                cleanupDialogBtReceivers();
                if (deviceSelectorTimer != null) {
                    deviceSelectorTimer.cancel();
                    deviceSelectorTimer = null;
                    if (tvCountdown != null) tvCountdown.setVisibility(View.GONE);
                }

                // If this device is ALREADY verified as connected (via A2DP, Adapter, Audio routing, or vendor BT)
                if (btWatcher != null && btWatcher.isDeviceCurrentlyConnected(device)) {
                    prefHelper.setSelectedDriverDevice(device.getAddress());
                    isCarPlayConnected = true;
                    updateConnectedPhoneUi(name, "CONNECTED · CARPLAY READY");
                    notifier.show(Notifier.Kind.OK, name + " connected", "Starting CarPlay");
                    if (deviceSelectorDialog != null && deviceSelectorDialog.isShowing()) {
                        deviceSelectorDialog.dismiss();
                    }
                    launchCarPlay();
                    return;
                }

                // Device is not yet confirmed connected: initiate real connection and gate CarPlay launch on success
                isBtConnecting = true;
                prefHelper.setSelectedDriverDevice(device.getAddress());

                tvStatus.setText("CONNECTING TO " + name.toUpperCase() + "...");
                tvStatus.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.ad_accent_text));
                tvName.setTextColor(accentColor);
                btnConnect.setText("CONNECTING...");
                btnConnect.setTextColor(accentColor);

                // Disable all cards while connection is in progress so driver doesn't spam
                for (int i = 0; i < deviceContainer.getChildCount(); i++) {
                    View c = deviceContainer.getChildAt(i);
                    c.setEnabled(false);
                    View b = c.findViewById(R.id.btnConnectPill);
                    if (b != null) b.setEnabled(false);
                }

                updateConnectedPhoneUi(name, "CONNECTING BLUETOOTH...");
                notifier.show(Notifier.Kind.INFO, "Connecting to " + name);

                if (btWatcher != null) {
                    btWatcher.connectToDevice(device, new BluetoothWatcher.DeviceConnectionCallback() {
                        @Override
                        public void onConnecting(BluetoothDevice dev) {
                            runOnUiThread(() -> {
                                if (deviceSelectorDialog != null && deviceSelectorDialog.isShowing()) {
                                    tvStatus.setText("ESTABLISHING LINK TO " + name.toUpperCase() + "...");
                                }
                            });
                        }

                        @Override
                        public void onSuccess(BluetoothDevice dev) {
                            runOnUiThread(() -> {
                                isBtConnecting = false;
                                isCarPlayConnected = true;
                                prefHelper.setSelectedDriverDevice(device.getAddress());

                                if (deviceSelectorDialog != null && deviceSelectorDialog.isShowing()) {
                                    tvStatus.setText("CONNECTED · STARTING CARPLAY");
                                    tvStatus.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.status_active));
                                    btnConnect.setText("CONNECTED");
                                    btnConnect.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.status_active));
                                }

                                updateConnectedPhoneUi(name, "CONNECTED · CARPLAY READY");
                                notifier.show(Notifier.Kind.OK, "Connected to " + name, "Starting CarPlay");

                                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                                    if (deviceSelectorDialog != null && deviceSelectorDialog.isShowing()) {
                                        deviceSelectorDialog.dismiss();
                                    }
                                    launchCarPlay();
                                }, 500);
                            });
                        }

                        @Override
                        public void onFailure(BluetoothDevice dev, String errorMessage) {
                            runOnUiThread(() -> {
                                isBtConnecting = false;

                                if (deviceSelectorDialog != null && deviceSelectorDialog.isShowing()) {
                                    tvStatus.setText("CONNECTED IN CAR? TAP TO LAUNCH ANYWAY");
                                    tvStatus.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.ad_accent_text));
                                    btnConnect.setText("LAUNCH ANYWAY");
                                    btnConnect.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.status_active));

                                    View.OnClickListener launchAnywayListener = fl -> {
                                        prefHelper.setSelectedDriverDevice(device.getAddress());
                                        isCarPlayConnected = true;
                                        updateConnectedPhoneUi(name, "CONNECTED · CARPLAY READY");
                                        if (deviceSelectorDialog != null && deviceSelectorDialog.isShowing()) {
                                            deviceSelectorDialog.dismiss();
                                        }
                                        launchCarPlay();
                                    };

                                    card.setOnClickListener(launchAnywayListener);
                                    btnConnect.setOnClickListener(launchAnywayListener);

                                    // Re-enable cards for retry or choosing another device
                                    for (int i = 0; i < deviceContainer.getChildCount(); i++) {
                                        View c = deviceContainer.getChildAt(i);
                                        c.setEnabled(true);
                                        View b = c.findViewById(R.id.btnConnectPill);
                                        if (b != null) b.setEnabled(true);
                                    }
                                }

                                notifier.show(Notifier.Kind.CAUTION, "Couldn't confirm " + name, "If it's connected in the car, tap Launch anyway or open car Bluetooth");
                            });
                        }
                    });
                } else {
                    isBtConnecting = false;
                    notifier.show(Notifier.Kind.ALERT, "Bluetooth is unavailable");
                }
            };

            card.setOnClickListener(selectListener);
            btnConnect.setOnClickListener(selectListener);
            deviceContainer.addView(card);
        }
    }

    private void initMediaReceiver() {
        mediaReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null || intent.getAction() == null) return;

                String title = null;
                String artist = null;
                boolean isPlaying = false;
                boolean hasPlayState = false;

                if (MediaSyncService.ACTION_MEDIA_UPDATED.equals(intent.getAction())) {
                    title = intent.getStringExtra(MediaSyncService.EXTRA_TRACK_TITLE);
                    artist = intent.getStringExtra(MediaSyncService.EXTRA_TRACK_ARTIST);
                    isPlaying = intent.getBooleanExtra(MediaSyncService.EXTRA_IS_PLAYING, false);
                    hasPlayState = true;
                } else {
                    title = intent.getStringExtra("track");
                    if (title == null) title = intent.getStringExtra("title");
                    if (title == null) title = intent.getStringExtra("song");
                    if (title == null) title = intent.getStringExtra("name");

                    artist = intent.getStringExtra("artist");
                    if (artist == null) artist = intent.getStringExtra("singer");
                    if (artist == null) artist = intent.getStringExtra("album");

                    if (intent.hasExtra("playing")) {
                        isPlaying = intent.getBooleanExtra("playing", false);
                        hasPlayState = true;
                    } else if (intent.hasExtra("isPlaying")) {
                        isPlaying = intent.getBooleanExtra("isPlaying", false);
                        hasPlayState = true;
                    } else if (intent.hasExtra("playstate")) {
                        isPlaying = intent.getBooleanExtra("playstate", false);
                        hasPlayState = true;
                    }
                }

                if (tvTrackTitle != null && title != null && !title.trim().isEmpty()) {
                    tvTrackTitle.setText(title);
                }
                if (tvTrackArtist != null && artist != null && !artist.trim().isEmpty()) {
                    tvTrackArtist.setText(artist);
                }
                if (btnMediaPlayPause != null && hasPlayState) {
                    btnMediaPlayPause.setText(isPlaying ? "PAUSE" : "PLAY");
                }

                String action = intent.getAction();
                boolean isCarPlaySource = action != null && (action.startsWith("com.zlink") || action.startsWith("zjinnova") || action.contains("diplay"));
                if (isCarPlaySource || (isPlaying && title != null && !title.trim().isEmpty() && !title.startsWith("FM "))) {
                    isCarPlayConnected = true;
                    String saved = prefHelper.getSelectedDriverDevice();
                    String savedName = getDeviceNameFromAddress(saved);
                    updateConnectedPhoneUi(savedName != null ? savedName : "CARPLAY", "WIRELESS PROJECTION ACTIVE");
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(MediaSyncService.ACTION_MEDIA_UPDATED);
        filter.addAction("com.android.music.metachanged");
        filter.addAction("com.android.music.playstatechanged");
        filter.addAction("com.android.music.playbackcomplete");
        filter.addAction("com.spotify.music.metadatachanged");
        filter.addAction("com.spotify.music.playbackstatechanged");
        filter.addAction("com.goc.bluetooth.track");
        filter.addAction("com.goc.bluetooth.avrcp");
        filter.addAction("com.microntek.bt.music");
        filter.addAction("com.ts.bt.track");
        filter.addAction("action.hct.bt.track");
        filter.addAction("com.zlink.metadata");
        filter.addAction("com.zlink.music_info");
        filter.addAction("com.shihab.diplay.METADATA");
        filter.addAction("com.shihab.diplay.PLAY_STATE");
        filter.addAction("android.bluetooth.a2dp.profile.action.TRACK_CHANGED");

        try {
            registerReceiver(mediaReceiver, filter);
        } catch (Exception ignored) {}

        try {
            startService(new Intent(this, MediaSyncService.class));
        } catch (Exception ignored) {}
    }

    private void launchCarPlay() {
        isCarPlayConnected = true;
        String savedAddr = prefHelper.getSelectedDriverDevice();
        String savedName = getDeviceNameFromAddress(savedAddr);
        updateConnectedPhoneUi(savedName != null ? savedName : "CARPLAY", "LAUNCHING PROJECTION...");

        String pkg = prefHelper.getCarPlayPackage();

        // 1. Try launching Zlink via its explicit intent first
        try {
            Intent zlinkIntent = new Intent("zjinnova.android.intent.action.ZLINK_MAIN");
            zlinkIntent.putExtra("page", "LINK");
            zlinkIntent.putExtra("feature", "carplay");
            zlinkIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(zlinkIntent);
            return;
        } catch (Exception ignored) {}

        // 2. Try launching package selected by user
        if (launchAppPackage(pkg)) return;

        // 3. Fallback to common CarPlay packages
        String[] commonCarPlayPackages = {
                "com.zjinnova.zlink",
                "com.zlink.carplay",
                "com.speedplay.carplay",
                "cn.manstep.phonemirrorbox",
                "com.zlink",
                "com.shihab.diplay",
                "com.autokit",
                "com.carlinke"
        };
        boolean launched = false;
        for (String fallback : commonCarPlayPackages) {
            if (launchAppPackage(fallback)) {
                prefHelper.setCarPlayPackage(fallback);
                prefHelper.setCarPlayName("TLink 5.3");
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

    private boolean launchApp(AppInfo app) {
        if (app == null) return false;
        try {
            if (app.getActivityName() != null && !app.getActivityName().isEmpty()) {
                Intent intent = new Intent(Intent.ACTION_MAIN);
                intent.addCategory(Intent.CATEGORY_LAUNCHER);
                intent.setComponent(new ComponentName(app.getPackageName(), app.getActivityName()));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                startActivity(intent);
                recordRecentApp(app.getPackageName());
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
                recordRecentApp(packageName);
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
                recordRecentApp(packageName);
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
        cleanupDialogBtReceivers();
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
