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
import androidx.core.widget.ImageViewCompat;
import com.apexdrive.launcher.ui.Skin;
import com.apexdrive.launcher.utils.HomeTiles;
import com.apexdrive.launcher.utils.Look;
import com.apexdrive.launcher.utils.WallpaperStore;

import com.apexdrive.launcher.adapters.AppsGridAdapter;
import com.apexdrive.launcher.models.AppInfo;
import com.apexdrive.launcher.services.BluetoothWatcher;
import com.apexdrive.launcher.services.CarPlayLink;
import com.apexdrive.launcher.services.CarThermometerWatcher;
import com.apexdrive.launcher.services.GpsTelemetryManager;
import com.apexdrive.launcher.services.MediaStateHolder;
import com.apexdrive.launcher.services.MediaSyncService;
import com.apexdrive.launcher.utils.Notifier;
import com.apexdrive.launcher.utils.HeadUnitReport;
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
    private Skin skin;
    private android.graphics.Bitmap wallpaperImage;
    private boolean clockSeconds;
    private static final int REQ_WALLPAPER = 1002;
    private GpsTelemetryManager gpsManager;
    private BluetoothWatcher btWatcher;
    private CarThermometerWatcher thermometerWatcher;

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
    private android.widget.ImageButton btnMediaPlayPause;
    private TextView btnSelectCarPlayApp;
    private TextView btnSelectRadioApp;
    private TextView btnSelectMapApp;
    private Button btnOpenOsmAnd;
    private Button btnChooseMapApp;
    private TextView btnSelectDriverPhone;
    private String settingsGroup = "Phone";
    private boolean isRadioPlaying = false;

    private TextView tvNavHeadingBig, tvNavAccuracy, tvNavTripDist, tvNavTripDuration;

    private Notifier notifier;
    private final Handler clockHandler = new Handler(Looper.getMainLooper());
    private BroadcastReceiver mediaReceiver;

    private BroadcastReceiver dialogBtReceiver;
    private final Handler dialogPollHandler = new Handler(Looper.getMainLooper());
    private Runnable dialogPollRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AppCompatDelegate.setDefaultNightMode(isNightTheme() ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO);
        prefHelper = new PreferenceHelper(this);
        // Installed before AppCompat's own factory, which it wraps: views follow Settings > Look as they are inflated.
        skin = new Skin(this, getDelegate(), prefHelper.getLook(isNightTheme()));
        androidx.core.view.LayoutInflaterCompat.setFactory2(getLayoutInflater(), skin);
        super.onCreate(savedInstanceState);
        makeFullScreen();

        setContentView(R.layout.activity_main);
        notifier = new Notifier(this);
        initViews();
        applyLook(false);
        initNavigation();
        initClock();
        checkPermissions();
        initTelemetry();
        initBluetooth();
        initCarThermometer();
        initMediaReceiver();

        showView(viewDash, railBtnDash);
        android.content.SharedPreferences display = getSharedPreferences("ad_display", MODE_PRIVATE);
        if (display.getBoolean("reopen_settings", false)) {
            display.edit().remove("reopen_settings").apply();
            showView(screen(SCREEN_SETUP), railBtnSetup);
        }
        buildRemainingScreensWhenIdle();
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

        // Only Home is built here; the other screens are built after the first frame (see screen()).
        screen(SCREEN_DASH);

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
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setDimAmount(0.5f);
        }

        TextView tvRecentsBadge = dialogView.findViewById(R.id.tvRecentsBadge);
        TextView tvNoRecentTasks = dialogView.findViewById(R.id.tvNoRecentTasks);
        LinearLayout llTasksList = dialogView.findViewById(R.id.llTasksList);
        Button btnCloseAllTasks = dialogView.findViewById(R.id.btnCloseAllTasks);
        Button btnSystemRecents = dialogView.findViewById(R.id.btnSystemRecents);
        View btnDismissSwitcher = dialogView.findViewById(R.id.btnDismissSwitcher);

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
                        tvStatus.setText("Running");
                    } else {
                        tvStatus.setText("Recent");
                    }
                }

                if (btnSwitch != null) {
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
                            if (tvRecentsBadge != null) tvRecentsBadge.setText("");
                            if (btnCloseAllTasks != null) btnCloseAllTasks.setEnabled(false);
                        } else {
                            if (tvRecentsBadge != null) {
                                tvRecentsBadge.setText(llTasksList.getChildCount() + " recent");
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
            if (tvRecentsBadge != null) tvRecentsBadge.setText("");
            if (btnCloseAllTasks != null) btnCloseAllTasks.setEnabled(false);
        } else {
            if (tvNoRecentTasks != null) tvNoRecentTasks.setVisibility(View.GONE);
            if (tvRecentsBadge != null) {
                tvRecentsBadge.setText(activeCount > 0 ? activeCount + " running" : llTasksList.getChildCount() + " recent");
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
                    if (tvRecentsBadge != null) tvRecentsBadge.setText("");
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
        View placeHome = viewDash.findViewById(R.id.btnPlaceHome);
        if (placeHome != null) placeHome.setOnClickListener(v -> launchPoiSearch("Home"));
        View placeWork = viewDash.findViewById(R.id.btnPlaceWork);
        if (placeWork != null) placeWork.setOnClickListener(v -> launchPoiSearch("Work"));
        View placeFuel = viewDash.findViewById(R.id.btnPlaceFuel);
        if (placeFuel != null) placeFuel.setOnClickListener(v -> launchPoiSearch("fuel"));
        View btnOpenMapsHome = viewDash.findViewById(R.id.btnOpenMapsHome);
        if (btnOpenMapsHome != null) {
            btnOpenMapsHome.setOnClickListener(v -> launchOfflineMap());
        }

        if (tvCarPlayProtocol != null) {
            tvCarPlayProtocol.setText(carPlayAppName());
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
                if (btnRadio != null) btnRadio.setText("Play radio");
                setRadioButtonIcon(false);
                TextView tvPreset = viewAudio != null ? viewAudio.findViewById(R.id.tvAudioPresetBadge) : null;
                if (tvPreset != null) tvPreset.setText("Standby");
                sendRadioHardwarePauseBroadcasts();
                MediaSyncService.pauseAllMedia(this);
                setPlayState(false);
            } else if (tvTrackTitle != null && tvTrackTitle.getText() != null && tvTrackTitle.getText().toString().startsWith("FM ")) {
                isRadioPlaying = true;
                Button btnRadio = viewAudio != null ? viewAudio.findViewById(R.id.btnRadioPlay) : null;
                if (btnRadio != null) btnRadio.setText("Pause radio");
                setRadioButtonIcon(true);
                TextView tvFreq = viewAudio != null ? viewAudio.findViewById(R.id.tvRadioFreq) : null;
                String freq = tvFreq != null ? tvFreq.getText().toString() : "87.5";
                TextView tvPreset = viewAudio != null ? viewAudio.findViewById(R.id.tvAudioPresetBadge) : null;
                if (tvPreset != null) tvPreset.setText("Active · " + freq + " MHz");
                sendRadioHardwareBroadcasts(freq);
                setPlayState(true);
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
        View rowAuto = viewCarPlay.findViewById(R.id.rowAutoOpen);
        androidx.appcompat.widget.SwitchCompat swAuto = viewCarPlay.findViewById(R.id.switchAutoOpen);
        if (swAuto != null) swAuto.setChecked(isAutoOpenCarPlay());
        if (rowAuto != null) {
            rowAuto.setOnClickListener(v -> {
                boolean next = !isAutoOpenCarPlay();
                getSharedPreferences("ad_display", MODE_PRIVATE).edit().putBoolean("auto_carplay", next).apply();
                if (swAuto != null) swAuto.setChecked(next);
            });
        }
        View rowSettings = viewCarPlay.findViewById(R.id.rowPhoneSettings);
        if (rowSettings != null) rowSettings.setOnClickListener(v -> openBluetoothManager());
        View rowPair = viewCarPlay.findViewById(R.id.rowPairPhone);
        if (rowPair != null) rowPair.setOnClickListener(v -> openBluetoothManager());
        View steps = viewCarPlay.findViewById(R.id.btnShowSteps);
        if (steps != null) steps.setOnClickListener(v -> showCarPlaySteps());
        refreshOtherPhones();
    }

    private boolean isAutoOpenCarPlay() {
        return getSharedPreferences("ad_display", MODE_PRIVATE).getBoolean("auto_carplay", false);
    }

    private void showCarPlaySteps() {
        new AlertDialog.Builder(this)
                .setTitle("CarPlay not starting?")
                .setMessage("1. Unlock your phone.\n"
                        + "2. Check that Bluetooth is on and the phone is connected.\n"
                        + "3. If CarPlay uses a cable, plug it in again.\n"
                        + "4. Choose your CarPlay app in Settings, then try Open CarPlay.")
                .setPositiveButton("Done", null)
                .show();
    }

    /** Lists paired phones other than the current one in the Phone screen. */
    @SuppressLint("MissingPermission")
    private void refreshOtherPhones() {
        if (viewCarPlay == null) return;
        LinearLayout list = viewCarPlay.findViewById(R.id.llOtherPhones);
        if (list == null) return;
        list.removeAllViews();
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) return;
        String current = prefHelper.getSelectedDriverDevice();
        int shown = 0;
        try {
            for (BluetoothDevice dev : adapter.getBondedDevices()) {
                if (shown >= 3) break;
                if (current != null && current.equalsIgnoreCase(dev.getAddress())) continue;
                String name = dev.getName();
                if (name == null || name.isEmpty()) continue;
                View row = getLayoutInflater().inflate(R.layout.item_phone_row, list, false);
                ((TextView) row.findViewById(R.id.tvPhoneRowName)).setText(name);
                ((TextView) row.findViewById(R.id.tvPhoneRowDetail)).setText("Paired");
                row.setOnClickListener(v -> showDeviceSelectorDialog(false));
                list.addView(row);
                shown++;
            }
        } catch (Exception ignored) {}
    }

    private void setupMapsWidgets() {
        tvNavHeadingBig = viewMaps.findViewById(R.id.tvNavHeadingBig);
        tvNavAccuracy = viewMaps.findViewById(R.id.tvNavAccuracy);
        tvNavTripDist = viewMaps.findViewById(R.id.tvNavTripDist);
        tvNavTripDuration = viewMaps.findViewById(R.id.tvNavTripDuration);

        if (tvNavAccuracy != null) {
            tvNavAccuracy.setOnClickListener(v -> checkAndOpenLocationSettings());
        }

        viewMaps.findViewById(R.id.btnNavHome).setOnClickListener(v -> launchPoiSearch("Home"));
        viewMaps.findViewById(R.id.btnNavWork).setOnClickListener(v -> launchPoiSearch("Work"));
        viewMaps.findViewById(R.id.btnNavFuel).setOnClickListener(v -> launchPoiSearch("gas station"));
        viewMaps.findViewById(R.id.btnNavParking).setOnClickListener(v -> launchPoiSearch("parking"));
        viewMaps.findViewById(R.id.btnNavCoffee).setOnClickListener(v -> launchPoiSearch("coffee"));
        viewMaps.findViewById(R.id.btnNavHospital).setOnClickListener(v -> launchPoiSearch("hospital"));

        btnOpenOsmAnd = viewMaps.findViewById(R.id.btnOpenOsmAnd);
        if (btnOpenOsmAnd != null) {
            btnOpenOsmAnd.setOnClickListener(v -> launchOfflineMap());
        }
        TextView mapBadge = viewMaps.findViewById(R.id.tvMapStatusBadge);
        if (mapBadge != null) {
            String mapName = prefHelper.getMapName();
            mapBadge.setText(mapName != null && !mapName.isEmpty() ? mapName : "No map app chosen");
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
                .setTitle("No map app installed")
                .setMessage("Install OsmAnd for offline maps:\n"
                        + "1. Copy the OsmAnd APK and your region's .obf map file to a USB drive.\n"
                        + "2. Plug the USB drive into the head unit.\n"
                        + "3. Open the file manager and install the APK.")
                .setPositiveButton("Open file manager", (dialog, which) -> {
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
                .setNeutralButton("Choose map app", (dialog, which) -> showMapPicker())
                .setNegativeButton("Close", null)
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
                .setTitle("No map app found")
                .setMessage("Install a map app such as OsmAnd to search for \"" + query + "\", or use maps in CarPlay.")
                .setPositiveButton("Open CarPlay", (d, w) -> launchCarPlay())
                .setNeutralButton("Map setup", (d, w) -> launchOfflineMap())
                .setNegativeButton("Cancel", null)
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


        btnSrcRadio.setOnClickListener(v -> {
            tvActive.setText("Radio");
            selectAudioSource(btnSrcRadio);
            if (tvPreset != null) {
                tvPreset.setText(isRadioPlaying ? "Active tuner" : "Standby · press play");
            }
            notifier.show(Notifier.Kind.INFO, "Radio selected", "Press play to listen");
        });

        if (btnRadioPlay != null) {
            btnRadioPlay.setOnClickListener(v -> {
                if (!isRadioPlaying) {
                    isRadioPlaying = true;
                    btnRadioPlay.setText("Pause radio");
                    setRadioButtonIcon(true);
                    if (tvPreset != null) {
                        tvPreset.setText("Active · " + tvFreq.getText() + " MHz");
                    }

                    // 1. Wake up vendor radio hardware tuner IC in background without switching away from launcher
                    sendRadioHardwareBroadcasts(tvFreq.getText().toString());

                    // 2. Update dashboard track display
                    setTrackText("FM " + tvFreq.getText() + " MHz", "Radio");
                    if (btnMediaPlayPause != null) setPlayState(true);

                    notifier.show(Notifier.Kind.INFO, "Radio playing", tvFreq.getText() + " MHz");
                } else {
                    isRadioPlaying = false;
                    btnRadioPlay.setText("Play radio");
                    setRadioButtonIcon(false);
                    if (tvPreset != null) {
                        tvPreset.setText("Standby");
                    }
                    sendRadioHardwarePauseBroadcasts();
                    MediaSyncService.pauseAllMedia(this);
                    if (btnMediaPlayPause != null) setPlayState(false);
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
        View openRadio = viewAudio.findViewById(R.id.btnOpenRadioApp);
        if (openRadio != null) openRadio.setOnClickListener(v -> launchRadioApp());

        // Transport mirrors the Home now-playing tile.
        viewAudio.findViewById(R.id.btnAudioPrev).setOnClickListener(v ->
                MediaSyncService.sendMediaKey(this, KeyEvent.KEYCODE_MEDIA_PREVIOUS));
        viewAudio.findViewById(R.id.btnAudioNext).setOnClickListener(v ->
                MediaSyncService.sendMediaKey(this, KeyEvent.KEYCODE_MEDIA_NEXT));
        viewAudio.findViewById(R.id.btnAudioPlayPause).setOnClickListener(v -> {
            if (btnMediaPlayPause != null) btnMediaPlayPause.performClick();
        });

        viewAudio.findViewById(R.id.btnSrcSpotify).setOnClickListener(v -> {
            tvActive.setText("Spotify");
            selectAudioSource(btnSrcSpotify);
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
            tvActive.setText("Phone · Bluetooth");
            selectAudioSource(btnSrcBluetooth);
            if (btWatcher != null && btWatcher.getConnectedDevice() == null) {
                notifier.show(Notifier.Kind.CAUTION, "No phone connected", "Pair a phone to stream audio");
                showDeviceSelectorDialog(false);
            } else {
                notifier.show(Notifier.Kind.INFO, "Bluetooth audio");
            }
        });

        viewAudio.findViewById(R.id.btnSrcUsb).setOnClickListener(v -> {
            tvActive.setText("USB");
            selectAudioSource(btnSrcUsb);
            String[] usbPackages = {"com.tw.music", "com.ts.music", "com.microntek.music", "com.syu.music", "com.android.music", "com.android.musicfx"};
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
                    tvPreset.setText("Active · " + freqStr + " MHz");
                }
                if (isRadioPlaying && tvTrackTitle != null) {
                    setTrackText("FM " + freqStr + " MHz", null);
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
                    tvPreset.setText("Active · " + freqStr + " MHz");
                }
                if (isRadioPlaying && tvTrackTitle != null) {
                    setTrackText("FM " + freqStr + " MHz", null);
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
        registerPackageReceiver();

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

    private BroadcastReceiver packageReceiver;

    /** Reloads the app grid when an app is installed, updated or removed. */
    private void registerPackageReceiver() {
        if (packageReceiver != null) return;
        packageReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                loadInstalledAppsAsync();
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        filter.addDataScheme("package");
        try {
            registerReceiver(packageReceiver, filter);
        } catch (Exception ignored) {}
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

    private static final String[] SETTINGS_GROUPS = {"Phone", "Navigation", "Audio", "Display", "Look", "Advanced", "About"};

    private void setupSetupWidgets() {
        for (String key : SETTINGS_GROUPS) {
            View item = settingsView("setGrp" + key);
            if (item != null) item.setOnClickListener(v -> showSettingsGroup(key));
        }
        showSettingsGroup(getSharedPreferences("ad_display", MODE_PRIVATE).getString("settings_group", "Phone"));

        // Phone and CarPlay
        btnSelectDriverPhone = settingsView("tvSetDriverPhone");
        String driver = getDeviceNameFromAddress(prefHelper.getSelectedDriverDevice());
        if (driver != null) btnSelectDriverPhone.setText(driver);
        onSettingsRow("rowSetDriverPhone", () -> showDeviceSelectorDialog(false));
        btnSelectCarPlayApp = settingsView("tvSetCarPlayApp");
        btnSelectCarPlayApp.setText(carPlayAppName());
        onSettingsRow("rowSetCarPlayApp", this::showCarPlayPicker);
        androidx.appcompat.widget.SwitchCompat swAuto = settingsView("switchSetAutoOpen");
        swAuto.setChecked(isAutoOpenCarPlay());
        onSettingsRow("rowSetAutoOpen", () -> {
            boolean next = !isAutoOpenCarPlay();
            getSharedPreferences("ad_display", MODE_PRIVATE).edit().putBoolean("auto_carplay", next).apply();
            swAuto.setChecked(next);
            if (viewCarPlay != null) {
                androidx.appcompat.widget.SwitchCompat other = viewCarPlay.findViewById(R.id.switchAutoOpen);
                if (other != null) other.setChecked(next);
            }
        });

        // Navigation
        btnSelectMapApp = settingsView("tvSetMapApp");
        btnSelectMapApp.setText(prefHelper.getMapName());
        onSettingsRow("rowSetMapApp", this::showMapPicker);
        onSettingsRow("rowSetGps", () -> {
            if (gpsManager != null) gpsManager.start();
            notifier.show(Notifier.Kind.INFO, "GPS restarted");
        });

        // Audio
        btnSelectRadioApp = settingsView("tvSetRadioApp");
        btnSelectRadioApp.setText(prefHelper.getRadioName());
        onSettingsRow("rowSetRadioApp", this::showRadioPicker);
        onSettingsRow("rowSetEqualiser", this::openAudioEqualizer);

        // Display
        androidx.appcompat.widget.SwitchCompat swNight = settingsView("switchSetNight");
        swNight.setChecked(isNightTheme());
        onSettingsRow("rowSetNight", this::toggleTheme);
        View kmh = settingsView("segUnitKmh");
        View mph = settingsView("segUnitMph");
        kmh.setOnClickListener(v -> { if (prefHelper.getSpeedUnit().equals(PreferenceHelper.UNIT_MPH)) toggleSpeedUnit(); });
        mph.setOnClickListener(v -> { if (!prefHelper.getSpeedUnit().equals(PreferenceHelper.UNIT_MPH)) toggleSpeedUnit(); });
        onSettingsRow("rowSetUnits", this::toggleSpeedUnit);
        updateUnitSegment();
        androidx.appcompat.widget.SwitchCompat swClock = settingsView("switchSetClock");
        swClock.setChecked(is24HourClock());
        updateClockDetail();
        onSettingsRow("rowSetClock", () -> {
            getSharedPreferences("ad_display", MODE_PRIVATE).edit().putBoolean("clock_24", !is24HourClock()).apply();
            swClock.setChecked(is24HourClock());
            updateClockDetail();
        });

        // Advanced
        onSettingsRow("rowSetFactory", this::openFactorySettings);
        onSettingsRow("rowSetAndroid", () -> {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e) {
                notifier.show(Notifier.Kind.ALERT, "Can't open Android settings");
            }
        });
        onSettingsRow("rowSetBluetooth", this::openBluetoothManager);
        onSettingsRow("rowSetRestart", this::recreate);
        onSettingsRow("rowSetReport", this::showHeadUnitReport);

        // About
        TextView version = settingsView("tvSetVersion");
        try {
            version.setText(getPackageManager().getPackageInfo(getPackageName(), 0).versionName);
        } catch (Exception ignored) {}
    }

    @SuppressWarnings("unchecked")
    private <T extends View> T settingsView(String idName) {
        if (viewSetup == null) return null;
        return (T) viewSetup.findViewById(getResources().getIdentifier(idName, "id", getPackageName()));
    }

    private void onSettingsRow(String idName, Runnable action) {
        View row = settingsView(idName);
        if (row == null) return;
        row.setOnClickListener(v -> action.run());
    }

    private void showSettingsGroup(String key) {
        settingsGroup = key;
        getSharedPreferences("ad_display", MODE_PRIVATE).edit().putString("settings_group", key).apply();
        for (String k : SETTINGS_GROUPS) {
            View item = settingsView("setGrp" + k);
            View page = settingsView("setPage" + k);
            boolean on = k.equals(key);
            if (on && page == null && "Look".equals(k)) page = inflateLookPage();
            if (item != null) {
                item.setSelected(on);
                ((TextView) item).setTypeface(androidx.core.content.res.ResourcesCompat.getFont(this,
                        on ? R.font.atkinson_next_semibold : R.font.atkinson_next_medium));
            }
            if (page != null) page.setVisibility(on ? View.VISIBLE : View.GONE);
        }
    }

    private void updateUnitSegment() {
        if (viewSetup == null) return;
        boolean mph = prefHelper.getSpeedUnit().equals(PreferenceHelper.UNIT_MPH);
        TextView kmh = settingsView("segUnitKmh");
        TextView mphView = settingsView("segUnitMph");
        TextView detail = settingsView("tvSetUnits");
        kmh.setSelected(!mph);
        mphView.setSelected(mph);
        detail.setText(mph ? "Miles and °F" : "Kilometres and °C");
    }

    private boolean is24HourClock() {
        return getSharedPreferences("ad_display", MODE_PRIVATE)
                .getBoolean("clock_24", android.text.format.DateFormat.is24HourFormat(this));
    }

    private void updateClockDetail() {
        TextView detail = settingsView("tvSetClock");
        if (detail != null) detail.setText(is24HourClock() ? "Shows 17:30" : "Shows 5:30 PM");
    }

    private void toggleSpeedUnit() {
        String current = prefHelper.getSpeedUnit();
        String next = current.equals(PreferenceHelper.UNIT_KMH) ? PreferenceHelper.UNIT_MPH : PreferenceHelper.UNIT_KMH;
        prefHelper.setSpeedUnit(next);
        updateUnitSegment();
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
            if (tvNavTripDuration != null) tvNavTripDuration.setText("0 min");
            notifier.show(Notifier.Kind.OK, "Trip reset");
        }
    }

    /**
     * Collects the head unit report on a worker thread, saves it and shows it. Head units keep
     * the phone connection and outside temperature in vendor places; this shows where.
     */
    private void showHeadUnitReport() {
        notifier.show(Notifier.Kind.INFO, "Making the report", "This takes a few seconds");
        final String bt = btWatcher != null ? btWatcher.getDiagnostics() : "Bluetooth watcher not running\n";
        final String temp = thermometerWatcher != null ? thermometerWatcher.getDiagnostics() : "Thermometer not running\n";
        final Context app = getApplicationContext();
        new Thread(() -> {
            final String report = HeadUnitReport.build(app, bt, temp);
            final java.io.File file = HeadUnitReport.save(app, report);
            runOnUiThread(() -> {
                if (isFinishing()) return;
                TextView body = new TextView(this);
                body.setText(report);
                body.setTextSize(12);
                body.setTypeface(android.graphics.Typeface.MONOSPACE);
                body.setTextIsSelectable(true);
                int pad = Math.round(16 * getResources().getDisplayMetrics().density);
                body.setPadding(pad, pad, pad, pad);
                android.widget.ScrollView scroll = new android.widget.ScrollView(this);
                scroll.addView(body);
                AlertDialog.Builder b = new AlertDialog.Builder(this)
                        .setTitle(file != null ? "Saved to " + file.getAbsolutePath() : "Head unit report")
                        .setView(scroll)
                        .setNegativeButton("Close", null);
                Intent share = new Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, "ApexDrive head unit report")
                        .putExtra(Intent.EXTRA_TEXT, report.length() > 90000 ? report.substring(0, 90000) : report);
                if (share.resolveActivity(getPackageManager()) != null) {
                    b.setPositiveButton("Share", (d, w) -> {
                        try {
                            startActivity(Intent.createChooser(share, "Send report"));
                        } catch (Exception e) {
                            notifier.show(Notifier.Kind.ALERT, "Can't share the report", "Copy it from " + (file != null ? file.getAbsolutePath() : "the screen"));
                        }
                    });
                }
                b.show();
            });
        }, "HeadUnitReport").start();
    }

    private void openFactorySettings() {
        new AlertDialog.Builder(this)
                .setTitle("Open factory settings?")
                .setMessage("These are the head unit's own setup screens. Wrong values can stop the radio, camera or steering wheel buttons working.")
                .setPositiveButton("Open", (dialog, which) -> {
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
                "com.tw.eq",
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
                "com.tw.bt",
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
        // Unknown firmware: look for the head unit's own Bluetooth phone app by its name.
        String found = findHeadUnitBluetoothApp();
        if (found != null && launchAppPackage(found)) return;
        // Android's Bluetooth settings page crashes ("Settings keeps stopping") on a unit
        // without a Bluetooth adapter, so check first.
        if (BluetoothAdapter.getDefaultAdapter() == null) {
            notifier.show(Notifier.Kind.ALERT, "No Bluetooth on this head unit", "Phone settings need Bluetooth");
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            notifier.show(Notifier.Kind.ALERT, "Can't open Bluetooth settings", "Open them from the head unit's own menu");
        }
    }

    /**
     * Finds a launcher app that looks like the head unit's Bluetooth phone app, such as one
     * labelled "Bluetooth" or "BT". Android's own Settings is skipped: its Bluetooth page
     * can't pair phones through the head unit's Bluetooth module.
     */
    private String findHeadUnitBluetoothApp() {
        try {
            Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            for (ResolveInfo ri : getPackageManager().queryIntentActivities(main, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getPackageName()) || pkg.equals("com.android.settings")) continue;
                String label = String.valueOf(ri.loadLabel(getPackageManager())).trim().toLowerCase(Locale.US);
                String p = pkg.toLowerCase(Locale.US);
                if (label.contains("bluetooth") || label.equals("bt") || label.startsWith("bt ")
                        || label.contains("蓝牙") || p.contains("bluetooth") || p.endsWith(".bt")) {
                    return pkg;
                }
            }
        } catch (Exception ignored) {}
        return null;
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
            tvCarPlayProtocol.setText(name);
        }
        notifier.show(Notifier.Kind.OK, "CarPlay app set", name);
    }

    private void showRadioPicker() {
        final List<AppInfo> apps = loadInstalledApps();
        List<String> names = new ArrayList<>();
        final List<String> packages = new ArrayList<>();

        String[] known = {
                "Radio (com.tw.radio)",
                "Allwinner Radio (com.allwinner.radio)",
                "SYU Radio (com.syu.radio)",
                "TopWay Radio (com.ts.radio)",
                "Microntek Radio (com.microntek.radio)",
                "Android Radio Tuner (com.android.radio)",
                "Car FM Tuner (com.car.radio)"
        };
        String[] knownPkgs = {
                "com.tw.radio",
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
        TextView mapBadge = viewMaps != null ? viewMaps.findViewById(R.id.tvMapStatusBadge) : null;
        if (mapBadge != null) mapBadge.setText(name);
        notifier.show(Notifier.Kind.OK, "Map app set", name);
    }

    private String findInstalledRadioPackage() {
        PackageManager pm = getPackageManager();
        String[] knownRadioPackages = {
                "com.tw.radio",
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

    private boolean isNightTheme() {
        return getSharedPreferences("ad_display", MODE_PRIVATE).getBoolean("night", true);
    }

    private void toggleTheme() {
        getSharedPreferences("ad_display", MODE_PRIVATE).edit()
                .putBoolean("night", !isNightTheme()).putBoolean("reopen_settings", true).apply();
        recreate();
    }

    // --- Look (Settings > Look) -----------------------------------------------------------

    private interface IntPref { int get(); }
    private interface IntSetter { void set(int value); }
    private interface BoolPref { boolean get(); }
    private interface BoolSetter { void set(boolean value); }

    private static final String[] TILE_KEYS = {"Drive", "Speed", "Media", "Phone"};
    private static final String[] TILE_IDS = {HomeTiles.DRIVE, HomeTiles.SPEED, HomeTiles.MEDIA, HomeTiles.PHONE};
    private String appliedHomeTiles = HomeTiles.DEFAULT;
    private java.util.Map<String, View> homeTileViews;
    private boolean wallpaperLoading;

    /**
     * Applies Settings > Look to the whole launcher at once, without restarting. At start-up
     * (repaint false) the views were already painted while they were inflated, so only the
     * wallpaper, status bar, rail labels and Home tiles are set.
     */
    private void applyLook(boolean repaint) {
        Look look = prefHelper.getLook(isNightTheme());
        boolean changed = !look.sameSkin(skin.look());
        skin.setLook(look);
        if (repaint && changed) {
            skin.apply(findViewById(R.id.appRoot));
            for (View v : new View[]{viewDash, viewCarPlay, viewMaps, viewAudio, viewApps, viewSetup}) {
                if (v != null && v.getParent() == null) skin.apply(v);
            }
            // Hidden Home tiles are detached from Home; repaint them too so they match when shown again.
            if (homeTileViews != null) {
                for (View tile : homeTileViews.values()) if (tile.getParent() == null) skin.apply(tile);
            }
            if (currentActiveRailBtn != null) updateRailAppearance(currentActiveRailBtn);
            if (lastPhoneName != null) {
                showPhoneState(lastPhoneName, lastPhoneDetail, lastPhoneStatus, lastPhoneStatusColor, lastPhoneConnected);
            }
        }
        applyWallpaper();
        applyStatusBar();
        int labels = prefHelper.isRailLabels() ? View.VISIBLE : View.GONE;
        // Rail labels keep the caption size at every text size: the 80 dp rail has no room for more.
        float caption = getResources().getDimension(R.dimen.ad_text_caption);
        for (TextView t : new TextView[]{tvDashLabel, tvCarPlayLabel, tvMapsLabel, tvAudioLabel, tvAppsLabel, tvSetupLabel}) {
            t.setVisibility(labels);
            t.setTag(R.id.skin_text_px, caption / look.textScale());
            t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, caption);
        }
        applyHomeTiles();
        if (viewSetup != null) refreshLookPage();
    }

    private void applyWallpaper() {
        int wp = prefHelper.getLookWallpaper();
        if (wp == Look.WALLPAPER_CUSTOM && wallpaperImage == null) loadWallpaperImage();
        findViewById(R.id.appRoot).setBackground(skin.wallpaper(wp, wallpaperImage));
        View preview = viewSetup != null ? viewSetup.findViewById(R.id.lookPreview) : null;
        if (preview != null) preview.setBackground(skin.wallpaper(wp, wallpaperImage));
    }

    /** Reads the saved wallpaper image off the main thread; the canvas colour shows until then. */
    private void loadWallpaperImage() {
        if (wallpaperLoading) return;
        wallpaperLoading = true;
        final Context app = getApplicationContext();
        new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            final android.graphics.Bitmap bmp = WallpaperStore.load(app);
            runOnUiThread(() -> {
                wallpaperLoading = false;
                if (bmp == null || isFinishing()) return;
                wallpaperImage = bmp;
                applyWallpaper();
                if (viewSetup != null) refreshLookPage();
            });
        }, "wallpaper").start();
    }

    private void pickWallpaperImage() {
        Intent pick = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(pick, "Choose a wallpaper"), REQ_WALLPAPER);
        } catch (Exception e) {
            notifier.show(Notifier.Kind.ALERT, "No app here can choose an image");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_WALLPAPER || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        final Context app = getApplicationContext();
        final android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        final int w = Math.max(dm.widthPixels, dm.heightPixels), h = Math.min(dm.widthPixels, dm.heightPixels);
        new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            final android.graphics.Bitmap bmp = WallpaperStore.save(app, uri, w, h) ? WallpaperStore.load(app) : null;
            runOnUiThread(() -> {
                if (isFinishing()) return;
                if (bmp == null) {
                    notifier.show(Notifier.Kind.ALERT, "Can't use that image");
                    return;
                }
                wallpaperImage = bmp;
                prefHelper.setLookWallpaper(Look.WALLPAPER_CUSTOM);
                applyLook(true);
            });
        }, "wallpaper").start();
    }

    private void applyStatusBar() {
        setShown(pillHeaderWeather, prefHelper.isStatusTemp());
        setShown(findViewById(R.id.statusGps), prefHelper.isStatusGps());
        setShown(pillHeaderBluetooth, prefHelper.isStatusPhone());
        clockSeconds = prefHelper.isClockSeconds();
        float px = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,
                Look.clockSp(prefHelper.getClockSize()), getResources().getDisplayMetrics());
        tvClock.setTag(R.id.skin_text_px, px);
        tvClock.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px * skin.look().textScale());
    }

    private static void setShown(View v, boolean shown) {
        if (v != null) v.setVisibility(shown ? View.VISIBLE : View.GONE);
    }

    /** Lays out the visible Home tiles in their saved order: two per row, or one per row for two or fewer. */
    private void applyHomeTiles() {
        if (viewDash == null) return;
        HomeTiles saved = prefHelper.getHomeTiles();
        if (saved.serialize().equals(appliedHomeTiles)) return;
        appliedHomeTiles = saved.serialize();
        LinearLayout top = viewDash.findViewById(R.id.rowHomeTop);
        LinearLayout bottom = viewDash.findViewById(R.id.rowHomeBottom);
        // Kept here because a hidden tile is detached and can no longer be found in the tree.
        if (homeTileViews == null) {
            homeTileViews = new java.util.HashMap<>();
            homeTileViews.put(HomeTiles.DRIVE, viewDash.findViewById(R.id.tileDrive));
            homeTileViews.put(HomeTiles.SPEED, viewDash.findViewById(R.id.tileSpeed));
            homeTileViews.put(HomeTiles.MEDIA, viewDash.findViewById(R.id.tileNowPlaying));
            homeTileViews.put(HomeTiles.PHONE, viewDash.findViewById(R.id.cardBluetooth));
        }
        java.util.Map<String, View> tiles = homeTileViews;
        top.removeAllViews();
        bottom.removeAllViews();
        List<String> shown = saved.visible();
        int perRow = shown.size() > 2 ? 2 : 1;
        int gap = getResources().getDimensionPixelSize(R.dimen.ad_tile_gap);
        for (int i = 0; i < shown.size(); i++) {
            LinearLayout row = i < perRow ? top : bottom;
            View tile = tiles.get(shown.get(i));
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) tile.getLayoutParams();
            lp.setMarginStart(row.getChildCount() == 0 ? 0 : gap);
            row.addView(tile, lp);
        }
        bottom.setVisibility(bottom.getChildCount() == 0 ? View.GONE : View.VISIBLE);
    }

    /** The Look page is built the first time it is opened, so Settings itself stays as light as before. */
    private View inflateLookPage() {
        android.view.ViewStub stub = settingsView("setPageLookStub");
        if (stub == null) return null;
        View page = stub.inflate();
        setupLookPage();
        return page;
    }

    private void setupLookPage() {
        for (int i = 0; i < Look.STYLE_NAMES.length; i++) {
            final int style = i;
            settingsView("segLookStyle" + i).setOnClickListener(v -> {
                prefHelper.applyLookStyle(style);
                applyLook(true);
            });
        }
        lookSegments("segLookRadius", "rowLookRadius", Look.RADIUS_NAMES.length, prefHelper::getLookRadius, prefHelper::setLookRadius);
        lookSegments("segLookOpacity", "rowLookOpacity", Look.OPACITY_NAMES.length, prefHelper::getLookOpacity, prefHelper::setLookOpacity);
        lookSegments("segLookText", "rowLookText", Look.TEXT_NAMES.length, prefHelper::getLookTextSize, prefHelper::setLookTextSize);
        lookSegments("segLookClock", "rowLookClockSize", Look.CLOCK_NAMES.length, prefHelper::getClockSize, prefHelper::setClockSize);
        ((TextView) settingsView("tvLookRadius")).setText("Cards and buttons");
        ((TextView) settingsView("tvLookOpacity")).setText("See-through amount");
        ((TextView) settingsView("tvLookText")).setText("All text");
        ((TextView) settingsView("tvLookClockSize")).setText("Status bar clock");

        lookSwitch("rowLookSeconds", prefHelper::isClockSeconds, prefHelper::setClockSeconds);
        lookSwitch("rowLookStatusTemp", prefHelper::isStatusTemp, prefHelper::setStatusTemp);
        lookSwitch("rowLookStatusGps", prefHelper::isStatusGps, prefHelper::setStatusGps);
        lookSwitch("rowLookStatusPhone", prefHelper::isStatusPhone, prefHelper::setStatusPhone);
        lookSwitch("rowLookRailLabels", prefHelper::isRailLabels, prefHelper::setRailLabels);

        for (int i = 0; i < TILE_KEYS.length; i++) {
            final String id = TILE_IDS[i];
            onSettingsRow("rowLookTile" + TILE_KEYS[i], () -> {
                HomeTiles tiles = prefHelper.getHomeTiles();
                if (tiles.isShown(id) && tiles.visible().size() == 1) {
                    notifier.show(Notifier.Kind.INFO, "Home needs at least one tile");
                    return;
                }
                tiles.setShown(id, !tiles.isShown(id));
                prefHelper.setHomeTiles(tiles);
                applyLook(true);
            });
            settingsView("btnLookTileUp" + TILE_KEYS[i]).setOnClickListener(v -> {
                HomeTiles tiles = prefHelper.getHomeTiles();
                tiles.moveUp(id);
                prefHelper.setHomeTiles(tiles);
                applyLook(true);
            });
        }
        onSettingsRow("rowLookReset", () -> {
            prefHelper.resetLook();
            applyLook(true);
        });

        // The preview's wallpaper is clipped to the art corner radius.
        View preview = settingsView("lookPreview");
        preview.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                        getResources().getDimension(R.dimen.ad_radius_control) * skin.look().radiusScale());
            }
        });
        preview.setClipToOutline(true);
        applyWallpaper();
        refreshLookPage();
    }

    /** Segments set a value at once; tapping the rest of the row steps to the next value. */
    private void lookSegments(String prefix, String rowId, int count, IntPref getter, IntSetter setter) {
        for (int i = 0; i < count; i++) {
            final int value = i;
            settingsView(prefix + i).setOnClickListener(v -> {
                setter.set(value);
                applyLook(true);
            });
        }
        onSettingsRow(rowId, () -> {
            setter.set((getter.get() + 1) % count);
            applyLook(true);
        });
    }

    private void lookSwitch(String rowId, BoolPref getter, BoolSetter setter) {
        onSettingsRow(rowId, () -> {
            setter.set(!getter.get());
            applyLook(true);
        });
    }

    /** Shows the saved Look settings on the Look page: segments, switches, swatches and tile order. */
    private void refreshLookPage() {
        if (viewSetup == null || settingsView("setPageLook") == null) return;
        Look look = skin.look();
        selectSegment("segLookStyle", Look.STYLE_NAMES.length, look.style);
        selectSegment("segLookRadius", Look.RADIUS_NAMES.length, look.radius);
        selectSegment("segLookOpacity", Look.OPACITY_NAMES.length, look.opacity);
        selectSegment("segLookText", Look.TEXT_NAMES.length, look.textSize);
        selectSegment("segLookClock", Look.CLOCK_NAMES.length, prefHelper.getClockSize());
        ((TextView) settingsView("tvLookStyle")).setText(Look.STYLE_DETAILS[look.style]);
        int wp = prefHelper.getLookWallpaper();
        ((TextView) settingsView("tvLookWallpaper")).setText(wp == Look.WALLPAPER_CUSTOM
                ? "Your image. Tap it again to choose another" : Look.WALLPAPER_NAMES[wp]);
        int accentIndex = Look.accentIndex(look.accent);
        ((TextView) settingsView("tvLookAccent")).setText(accentIndex == 0
                ? "From the style" : Look.ACCENT_NAMES[accentIndex]);
        ((androidx.appcompat.widget.SwitchCompat) settingsView("switchLookSeconds")).setChecked(prefHelper.isClockSeconds());
        ((androidx.appcompat.widget.SwitchCompat) settingsView("switchLookStatusTemp")).setChecked(prefHelper.isStatusTemp());
        ((androidx.appcompat.widget.SwitchCompat) settingsView("switchLookStatusGps")).setChecked(prefHelper.isStatusGps());
        ((androidx.appcompat.widget.SwitchCompat) settingsView("switchLookStatusPhone")).setChecked(prefHelper.isStatusPhone());
        ((androidx.appcompat.widget.SwitchCompat) settingsView("switchLookRailLabels")).setChecked(prefHelper.isRailLabels());
        ((TextView) settingsView("tvLookSeconds")).setText(is24HourClock() ? "Shows 17:30:05" : "Shows 5:30:05 PM");

        // Tile rows follow the Home order.
        HomeTiles tiles = prefHelper.getHomeTiles();
        View first = settingsView("rowLookTileDrive");
        LinearLayout page = (LinearLayout) first.getParent();
        int at = page.getChildCount();
        View[] rows = new View[TILE_KEYS.length];
        for (int i = 0; i < TILE_KEYS.length; i++) {
            rows[i] = settingsView("rowLookTile" + TILE_KEYS[i]);
            at = Math.min(at, page.indexOfChild(rows[i]));
        }
        for (View row : rows) page.removeView(row);
        List<String> order = tiles.order();
        List<String> visible = tiles.visible();
        for (int n = 0; n < order.size(); n++) {
            int i = java.util.Arrays.asList(TILE_IDS).indexOf(order.get(n));
            page.addView(rows[i], at + n);
            boolean shown = tiles.isShown(TILE_IDS[i]);
            ((androidx.appcompat.widget.SwitchCompat) settingsView("switchLookTile" + TILE_KEYS[i])).setChecked(shown);
            ((TextView) settingsView("tvLookTile" + TILE_KEYS[i])).setText(shown
                    ? "Shown, place " + (visible.indexOf(TILE_IDS[i]) + 1) + " of " + visible.size() : "Hidden");
        }

        buildAccentSwatches(accentIndex);
        buildWallpaperSwatches(wp);
    }

    private void selectSegment(String prefix, int count, int selected) {
        for (int i = 0; i < count; i++) {
            View seg = settingsView(prefix + i);
            if (seg != null) seg.setSelected(i == selected);
        }
    }

    private void buildAccentSwatches(int selected) {
        LinearLayout strip = settingsView("lookAccents");
        strip.removeAllViews();
        int size = dp(52), gap = dp(7);
        for (int i = 0; i < Look.ACCENT_IDS.length; i++) {
            final int id = Look.ACCENT_IDS[i];
            Look sample = new Look(skin.look().style, id, 1, 0, 1, skin.look().night);
            int fill = sample.color(Look.Token.ACCENT);
            ImageView sw = new ImageView(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            if (i > 0) lp.setMarginStart(gap);
            sw.setLayoutParams(lp);
            sw.setBackground(swatch(fill, i == selected));
            sw.setScaleType(ImageView.ScaleType.CENTER);
            if (i == 0 || i == selected) {
                sw.setImageResource(i == selected ? R.drawable.ic_sym_check : R.drawable.ic_sym_palette);
                ImageViewCompat.setImageTintList(sw, ColorStateList.valueOf(sample.color(Look.Token.ON_ACCENT)));
            }
            sw.setContentDescription(i == 0 ? "Accent from the style" : Look.ACCENT_NAMES[i] + " accent");
            sw.setOnClickListener(v -> {
                prefHelper.setAccentColor(id);
                applyLook(true);
            });
            strip.addView(sw);
        }
    }

    private void buildWallpaperSwatches(int selected) {
        LinearLayout strip = settingsView("lookWallpapers");
        strip.removeAllViews();
        int w = dp(96), h = dp(64), gap = dp(12);
        float radius = dp(12);
        boolean hasImage = wallpaperImage != null || WallpaperStore.exists(this);
        for (int i = 0; i < Look.WALLPAPER_NAMES.length; i++) {
            final int wp = i;
            FrameLayout sw = new FrameLayout(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
            if (i > 0) lp.setMarginStart(gap);
            sw.setLayoutParams(lp);
            android.graphics.drawable.Drawable art = i == Look.WALLPAPER_CUSTOM && wallpaperImage == null
                    ? new android.graphics.drawable.ColorDrawable(color(R.color.ad_surface_3))
                    : skin.wallpaper(i, wallpaperImage);
            sw.setBackground(art);
            sw.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            sw.setClipToOutline(true);
            android.graphics.drawable.GradientDrawable ring = new android.graphics.drawable.GradientDrawable();
            ring.setCornerRadius(radius);
            ring.setStroke(dp(i == selected ? 4 : 1), color(i == selected ? R.color.ad_accent : R.color.ad_separator));
            sw.setForeground(ring);
            if (i == Look.WALLPAPER_CUSTOM && (wallpaperImage == null || i == selected)) {
                ImageView icon = new ImageView(this);
                icon.setImageResource(hasImage && wallpaperImage != null ? R.drawable.ic_sym_wallpaper : R.drawable.ic_sym_add);
                ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(color(R.color.ad_label_1)));
                sw.addView(icon, new FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER));
            }
            sw.setContentDescription(Look.WALLPAPER_NAMES[i] + " wallpaper");
            sw.setOnClickListener(v -> {
                if (wp == Look.WALLPAPER_CUSTOM && (wp == prefHelper.getLookWallpaper() || !WallpaperStore.exists(this))) {
                    pickWallpaperImage();
                    return;
                }
                prefHelper.setLookWallpaper(wp);
                applyLook(true);
            });
            strip.addView(sw);
        }
    }

    /** A round colour swatch; the selected one gets a ring in the label colour. */
    private android.graphics.drawable.Drawable swatch(int fill, boolean selected) {
        android.graphics.drawable.GradientDrawable dot = new android.graphics.drawable.GradientDrawable();
        dot.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        dot.setColor(fill);
        if (!selected) return new android.graphics.drawable.InsetDrawable(dot, dp(4));
        android.graphics.drawable.GradientDrawable ring = new android.graphics.drawable.GradientDrawable();
        ring.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        ring.setStroke(dp(3), color(R.color.ad_label_1));
        return new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{
                ring, new android.graphics.drawable.InsetDrawable(dot, dp(6))});
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** The design-system accent in the current look. */
    private int accent() {
        return color(R.color.ad_accent_text);
    }

    private void initNavigation() {
        railBtnDash.setOnClickListener(v -> showView(viewDash, railBtnDash));
        railBtnCarPlay.setOnClickListener(v -> showView(screen(SCREEN_CARPLAY), railBtnCarPlay));
        railBtnMaps.setOnClickListener(v -> showView(screen(SCREEN_MAPS), railBtnMaps));
        railBtnAudio.setOnClickListener(v -> showView(screen(SCREEN_AUDIO), railBtnAudio));
        railBtnApps.setOnClickListener(v -> showView(screen(SCREEN_APPS), railBtnApps));
        railBtnSetup.setOnClickListener(v -> showView(screen(SCREEN_SETUP), railBtnSetup));
    }

    private static final int SCREEN_DASH = 0, SCREEN_CARPLAY = 1, SCREEN_MAPS = 2,
            SCREEN_AUDIO = 3, SCREEN_APPS = 4, SCREEN_SETUP = 5;

    /** Returns a screen, building it the first time it is needed. */
    private View screen(int which) {
        LayoutInflater inflater = getLayoutInflater();
        switch (which) {
            case SCREEN_CARPLAY:
                if (viewCarPlay == null) {
                    viewCarPlay = inflater.inflate(R.layout.view_carplay, viewContainer, false);
                    setupCarPlayWidgets();
                    if (lastPhoneName != null) {
                        showPhoneState(lastPhoneName, lastPhoneDetail, lastPhoneStatus, lastPhoneStatusColor, lastPhoneConnected);
                    }
                }
                return viewCarPlay;
            case SCREEN_MAPS:
                if (viewMaps == null) {
                    viewMaps = inflater.inflate(R.layout.view_maps, viewContainer, false);
                    setupMapsWidgets();
                }
                return viewMaps;
            case SCREEN_AUDIO:
                if (viewAudio == null) {
                    viewAudio = inflater.inflate(R.layout.view_audio, viewContainer, false);
                    setupAudioWidgets();
                    MediaStateHolder.Snapshot s = MediaStateHolder.getInstance().getSnapshot();
                    setTrackText(s.title, s.artist);
                    if (s.hasTrack()) setPlayState(s.playing);
                    setRadioButtonIcon(isRadioPlaying);
                }
                return viewAudio;
            case SCREEN_APPS:
                if (viewApps == null) {
                    viewApps = inflater.inflate(R.layout.view_apps, viewContainer, false);
                    setupAppsWidgets();
                }
                return viewApps;
            case SCREEN_SETUP:
                if (viewSetup == null) {
                    viewSetup = inflater.inflate(R.layout.view_setup, viewContainer, false);
                    setupSetupWidgets();
                }
                return viewSetup;
            default:
                if (viewDash == null) {
                    viewDash = inflater.inflate(R.layout.view_dashboard, viewContainer, false);
                    setupDashWidgets();
                }
                return viewDash;
        }
    }

    /**
     * Builds the other screens one at a time while the main thread is idle, after Home is
     * on screen, so the first launch only pays for Home and later taps are still instant.
     */
    private void buildRemainingScreensWhenIdle() {
        final int[] order = {SCREEN_CARPLAY, SCREEN_AUDIO, SCREEN_MAPS, SCREEN_APPS, SCREEN_SETUP};
        final int[] next = {0};
        getWindow().getDecorView().post(() -> Looper.myQueue().addIdleHandler(() -> {
            if (isFinishing()) return false;
            screen(order[next[0]++]);
            return next[0] < order.length;
        }));
    }

    private void showView(View targetView, View activeRailBtn) {
        currentActiveRailBtn = activeRailBtn;
        viewContainer.removeAllViews();
        viewContainer.addView(targetView);
        updateRailAppearance(activeRailBtn);
    }

    private void updateRailAppearance(View activeRailBtn) {
        int normal = color(R.color.ad_label_1);
        int selected = color(R.color.ad_on_accent);
        View[] buttons = {railBtnDash, railBtnCarPlay, railBtnMaps, railBtnAudio, railBtnApps, railBtnSetup};
        ImageView[] icons = {ivDashIcon, ivCarPlayIcon, ivMapsIcon, ivAudioIcon, ivAppsIcon, ivSetupIcon};
        TextView[] labels = {tvDashLabel, tvCarPlayLabel, tvMapsLabel, tvAudioLabel, tvAppsLabel, tvSetupLabel};
        int[] outline = {R.drawable.ic_sym_home, R.drawable.ic_sym_phone_iphone, R.drawable.ic_sym_map,
                R.drawable.ic_sym_music_note, R.drawable.ic_sym_apps, R.drawable.ic_sym_settings};
        int[] filled = {R.drawable.ic_sym_home_fill, R.drawable.ic_sym_phone_iphone, R.drawable.ic_sym_map_fill,
                R.drawable.ic_sym_music_note_fill, R.drawable.ic_sym_apps_fill, R.drawable.ic_sym_settings_fill};
        android.graphics.Typeface semibold = androidx.core.content.res.ResourcesCompat.getFont(this, R.font.atkinson_next_semibold);
        android.graphics.Typeface medium = androidx.core.content.res.ResourcesCompat.getFont(this, R.font.atkinson_next_medium);
        for (int i = 0; i < buttons.length; i++) {
            boolean on = buttons[i] == activeRailBtn;
            buttons[i].setSelected(on);
            icons[i].setImageResource(on ? filled[i] : outline[i]);
            icons[i].setColorFilter(on ? selected : normal, PorterDuff.Mode.SRC_IN);
            labels[i].setTextColor(on ? selected : normal);
            labels[i].setTypeface(on ? semibold : medium);
        }
    }

    private void setPlayState(boolean playing) {
        if (btnMediaPlayPause == null) return;
        btnMediaPlayPause.setImageResource(playing ? R.drawable.ic_sym_pause_fill : R.drawable.ic_sym_play_arrow_fill);
        btnMediaPlayPause.setContentDescription(playing ? "Pause" : "Play");
        android.widget.ImageButton audio = viewAudio != null ? viewAudio.findViewById(R.id.btnAudioPlayPause) : null;
        if (audio != null) {
            audio.setImageResource(playing ? R.drawable.ic_sym_pause_fill : R.drawable.ic_sym_play_arrow_fill);
            audio.setContentDescription(playing ? "Pause" : "Play");
        }
    }

    private void selectAudioSource(View chosen) {
        if (viewAudio == null) return;
        int[] ids = {R.id.btnSrcRadio, R.id.btnSrcBluetooth, R.id.btnSrcSpotify, R.id.btnSrcUsb};
        for (int id : ids) {
            View src = viewAudio.findViewById(id);
            if (src != null) src.setSelected(src == chosen);
        }
    }

    /** Updates the track on Home and Audio; null keeps the current text. */
    private void setTrackText(String title, String artist) {
        TextView audioTitle = viewAudio != null ? viewAudio.findViewById(R.id.tvAudioTrackTitle) : null;
        TextView audioArtist = viewAudio != null ? viewAudio.findViewById(R.id.tvAudioTrackArtist) : null;
        if (title != null) {
            if (tvTrackTitle != null) tvTrackTitle.setText(title);
            if (audioTitle != null) audioTitle.setText(title);
        }
        if (artist != null) {
            if (tvTrackArtist != null) tvTrackArtist.setText(artist);
            if (audioArtist != null) audioArtist.setText(artist);
        }
    }

    private void setRadioButtonIcon(boolean playing) {
        TextView btn = viewAudio != null ? viewAudio.findViewById(R.id.btnRadioPlay) : null;
        if (btn != null) {
            androidx.core.widget.TextViewCompat.setCompoundDrawablesRelativeWithIntrinsicBounds(btn,
                    playing ? R.drawable.ic_btn_pause : R.drawable.ic_btn_play_arrow, 0, 0, 0);
        }
    }

    private void setDot(View dot, int colorRes) {
        if (dot instanceof ImageView) {
            // Kept as the view's tint resource so a Look change repaints the dot in its current state.
            dot.setTag(R.id.skin_tint, colorRes);
            ImageViewCompat.setImageTintList((ImageView) dot, ColorStateList.valueOf(color(colorRes)));
        }
    }

    /** A design-system colour in the current look (Settings > Look). */
    private int color(int colorRes) {
        return skin.color(colorRes);
    }

    private void initClock() {
        final SimpleDateFormat sdf24 = new SimpleDateFormat("H:mm", Locale.getDefault());
        final SimpleDateFormat sdf12 = new SimpleDateFormat("h:mm a", Locale.getDefault());
        final SimpleDateFormat sdf24s = new SimpleDateFormat("H:mm:ss", Locale.getDefault());
        final SimpleDateFormat sdf12s = new SimpleDateFormat("h:mm:ss a", Locale.getDefault());
        clockHandler.post(new Runnable() {
            @Override
            public void run() {
                boolean h24 = is24HourClock();
                long now = System.currentTimeMillis();
                tvClock.setText((clockSeconds ? (h24 ? sdf24s : sdf12s) : (h24 ? sdf24 : sdf12)).format(new Date(now)));
                // Tick just after each new second so seconds never skip.
                clockHandler.postDelayed(this, 1000 - now % 1000 + 5);
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
                    boolean mph = prefHelper.getSpeedUnit().equals(PreferenceHelper.UNIT_MPH);
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
                    setDot(findViewById(R.id.ivGpsDot), R.color.ad_ok);
                    TextView about = viewSetup != null ? viewSetup.findViewById(R.id.tvSetLocation) : null;
                    if (about != null) {
                        about.setText(String.format(Locale.US, "%.4f° %s, %.4f° %s · %.0f m · ±%.0f m",
                                Math.abs(latitude), latitude >= 0 ? "N" : "S",
                                Math.abs(longitude), longitude >= 0 ? "E" : "W", altitudeMeters, accuracyMeters));
                    }
                    if (tvNavAccuracy != null) {
                        tvNavAccuracy.setText(accuracyMeters <= 10 ? "GPS good" : accuracyMeters <= 30 ? "GPS weak" : "GPS poor");
                        if (viewMaps != null) setDot(viewMaps.findViewById(R.id.ivNavGpsDot),
                                accuracyMeters <= 30 ? R.color.ad_ok : R.color.ad_caution);
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
                    TextView avg = viewDash != null ? viewDash.findViewById(R.id.tvAvgSpeed) : null;
                    if (avg != null) {
                        double hours = elapsedMinutes / 60.0;
                        double km = isMph ? distanceKm * 0.621371 : distanceKm;
                        avg.setText(hours > 0.05 ? String.format(Locale.US, "Avg %.0f", km / hours) : "Avg –");
                    }
                    if (tvNavTripDist != null) {
                        tvNavTripDist.setText(distText);
                    }
                    if (tvNavTripDuration != null) {
                        tvNavTripDuration.setText(String.format(Locale.US, "%d min", elapsedMinutes));
                    }
                });
            }

            @Override
            public void onGpsStatusChanged(boolean hasFix, int satelliteCount) {
                runOnUiThread(() -> {
                    setDot(findViewById(R.id.ivGpsDot), hasFix ? R.color.ad_ok : R.color.ad_label_3);
                    if (!hasFix && tvNavAccuracy != null) {
                        tvNavAccuracy.setText("GPS none");
                        if (viewMaps != null) setDot(viewMaps.findViewById(R.id.ivNavGpsDot), R.color.ad_label_3);
                    }
                });
            }
        });
        gpsManager.start();
    }

    private void initBluetooth() {
        btWatcher = new BluetoothWatcher(this);
        btWatcher.setListener((isConnected, deviceName, details) -> runOnUiThread(() -> {
            boolean wasConnected = isCarPlayConnected;
            isCarPlayConnected = isConnected;
            if (isConnected && !wasConnected && isAutoOpenCarPlay() && isSavedPhoneConnected()) {
                launchCarPlay();
            }
            if (isConnected) {
                updateConnectedPhoneUi(deviceName, details);
            } else {
                updateDisconnectedPhoneUi();
            }
        }));
        btWatcher.start();
    }

    @SuppressLint("MissingPermission")
    private boolean isSavedPhoneConnected() {
        String saved = prefHelper.getSelectedDriverDevice();
        if (saved == null || saved.isEmpty() || btWatcher == null) return false;
        BluetoothDevice dev = btWatcher.getConnectedDevice();
        return dev != null && saved.equalsIgnoreCase(dev.getAddress());
    }

    /** True while the CarPlay app has a phone linked (see {@link CarPlayLink}). */
    private boolean carPlayLinked;
    private static final long CARPLAY_LINK_POLL_MS = 3000;
    private final Runnable carPlayLinkPoll = new Runnable() {
        @Override
        public void run() {
            boolean linked = CarPlayLink.isActive();
            if (linked != carPlayLinked) {
                carPlayLinked = linked;
                // Android's Bluetooth evidence wins when it has a phone; otherwise show the link.
                if (!isCarPlayConnected) updateDisconnectedPhoneUi();
            }
            if (activityResumed) clockHandler.postDelayed(this, CARPLAY_LINK_POLL_MS);
        }
    };

    private String lastPhoneName, lastPhoneDetail, lastPhoneStatus;
    private int lastPhoneStatusColor;
    private boolean lastPhoneConnected;

    private void updateConnectedPhoneUi(String deviceName, String details) {
        String name = deviceName != null ? deviceName : "Phone";
        showPhoneState(name, details != null ? details : "Connected", "Connected", R.color.ad_ok, true);
    }

    private void updateDisconnectedPhoneUi() {
        String savedAddr = prefHelper.getSelectedDriverDevice();
        String savedName = getDeviceNameFromAddress(savedAddr);

        if (carPlayLinked) {
            String kind = CarPlayLink.kind();
            updateConnectedPhoneUi(savedName != null ? savedName : "iPhone", kind.isEmpty() ? "CarPlay" : kind);
            return;
        }

        if (isCarPlayConnected && savedName != null) {
            updateConnectedPhoneUi(savedName, "Projection active");
            return;
        }
        if (savedName != null) {
            showPhoneState(savedName, "Not connected", "Open CarPlay to start", R.color.ad_label_2, false);
        } else {
            showPhoneState("No phone", "", "Choose a phone to connect", R.color.ad_label_2, false);
        }
    }

    /** Writes one phone state to the status bar chip, the Home tile and the Phone screen. */
    private void showPhoneState(String name, String detail, String status, int statusColor, boolean connected) {
        // Kept so the Phone screen shows the current state when it is built later.
        lastPhoneName = name;
        lastPhoneDetail = detail;
        lastPhoneStatus = status;
        lastPhoneStatusColor = statusColor;
        lastPhoneConnected = connected;
        int accent = accent();
        int inactive = color(R.color.ad_label_3);
        if (tvHeaderBluetooth != null) tvHeaderBluetooth.setText(connected ? name : "No phone");
        setDot(ivHeaderBtIcon, connected ? R.color.ad_ok : R.color.ad_label_3);
        if (viewDash != null) setDot(viewDash.findViewById(R.id.ivBtStatusDot), connected ? R.color.ad_ok : R.color.ad_label_3);
        if (tvBtDeviceName != null) tvBtDeviceName.setText(name);
        if (tvBtProfileDetail != null) tvBtProfileDetail.setText(detail);
        if (tvBtStatusBadge != null) tvBtStatusBadge.setText(connected ? "Connected" : "Not connected");
        if (viewCarPlay != null) {
            TextView device = viewCarPlay.findViewById(R.id.tvConnectDevice);
            TextView line = viewCarPlay.findViewById(R.id.tvConnectStatus);
            TextView body = viewCarPlay.findViewById(R.id.tvConnectBody);
            TextView auto = viewCarPlay.findViewById(R.id.tvAutoOpenDetail);
            // A saved but disconnected phone is named, as on the Home tile.
            if (device != null) device.setText(connected ? name + " is connected"
                    : "No phone".equals(name) ? "No phone connected" : name);
            if (line != null) line.setText(connected ? "Calls and audio" : "Not connected");
            setDot(viewCarPlay.findViewById(R.id.ivConnectDot), connected ? R.color.ad_ok : R.color.ad_label_3);
            if (body != null) {
                body.setText(connected
                        ? "CarPlay opens in " + carPlayAppName() + "."
                        : "Connect your phone with Bluetooth, then open CarPlay.");
            }
            if (auto != null) auto.setText(connected ? "When " + name + " connects" : "When your phone connects");
            refreshOtherPhones();
        }
    }

    private String getDeviceNameFromAddress(String address) {
        if (address == null || address.isEmpty()) return null;
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null && adapter.isEnabled()) {
            try {
                for (BluetoothDevice dev : adapter.getBondedDevices()) {
                    if (address.equalsIgnoreCase(dev.getAddress())) {
                        String name = dev.getName();
                        if (name != null && !name.isEmpty()) {
                            // Phones chosen before names were saved get theirs now.
                            if (address.equalsIgnoreCase(prefHelper.getSelectedDriverDevice())
                                    && !name.equals(prefHelper.getSelectedDriverName())) {
                                prefHelper.setSelectedDriverDevice(address, name);
                            }
                            return name;
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        // Fall back to the name saved when the driver phone was chosen.
        if (address.equalsIgnoreCase(prefHelper.getSelectedDriverDevice())) {
            String saved = prefHelper.getSelectedDriverName();
            if (!saved.isEmpty()) return saved;
        }
        return null;
    }

    private void initCarThermometer() {
        thermometerWatcher = new CarThermometerWatcher(this, (tempFormatted, tempCelsius, condition) -> runOnUiThread(() -> {
            if (tvWeatherText != null) {
                tvWeatherText.setText(tempFormatted != null ? tempFormatted : "–");
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
        layout.setPadding(32, 32, 32, 32);
        layout.setBackgroundResource(R.drawable.ad_bg_notification);

        TextView title = new TextView(this);
        title.setText("Calibrate temperature");
        title.setTextColor(ContextCompat.getColor(this, R.color.ad_label_1));
        title.setTextSize(22);
        title.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(this, R.font.atkinson_next_semibold));
        layout.addView(title);

        // With no reading there is nothing to calibrate: the driver sets the temperature instead.
        final boolean hasReading = thermometerWatcher.hasReading();
        TextView subtitle = new TextView(this);
        subtitle.setText(hasReading
                ? "Match the outside temperature on your instrument cluster, or use GPS weather."
                : "No temperature reading. Set it from your instrument cluster with − and +, or use GPS weather.");
        subtitle.setTextColor(ContextCompat.getColor(this, R.color.ad_label_2));
        subtitle.setTextSize(16);
        subtitle.setPadding(0, 8, 0, 16);
        layout.addView(subtitle);

        final TextView tvCurrentVal = new TextView(this);
        final float[] tempHolder = new float[]{ thermometerWatcher.getCurrentTempCelsius() };
        final boolean isMph = prefHelper.getSpeedUnit().equals(PreferenceHelper.UNIT_MPH);
        updateTempDisplay(tvCurrentVal, tempHolder[0], isMph);
        tvCurrentVal.setTextSize(48);
        tvCurrentVal.setTextColor(ContextCompat.getColor(this, R.color.ad_label_1));
        tvCurrentVal.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(this, R.font.atkinson_next_semibold));
        tvCurrentVal.setGravity(Gravity.CENTER);
        tvCurrentVal.setPadding(0, 0, 0, 16);
        layout.addView(tvCurrentVal);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER);

        // The first press with no reading starts from a mild 20 °C rather than from nothing.
        Button btnMinus = new Button(this);
        btnMinus.setText("− 1°");
        btnMinus.setTextSize(24);
        btnMinus.setTextColor(ContextCompat.getColor(this, R.color.ad_label_1));
        btnMinus.setBackgroundResource(R.drawable.ad_bg_btn_secondary);
        btnMinus.setLayoutParams(new LinearLayout.LayoutParams(0, 72, 1f));
        btnMinus.setOnClickListener(v -> {
            tempHolder[0] = Float.isNaN(tempHolder[0]) ? 20f : tempHolder[0] - 1.0f;
            updateTempDisplay(tvCurrentVal, tempHolder[0], isMph);
        });

        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(16, 72));

        Button btnPlus = new Button(this);
        btnPlus.setText("+ 1°");
        btnPlus.setTextSize(24);
        btnPlus.setTextColor(ContextCompat.getColor(this, R.color.ad_label_1));
        btnPlus.setBackgroundResource(R.drawable.ad_bg_btn_secondary);
        btnPlus.setLayoutParams(new LinearLayout.LayoutParams(0, 72, 1f));
        btnPlus.setOnClickListener(v -> {
            tempHolder[0] = Float.isNaN(tempHolder[0]) ? 20f : tempHolder[0] + 1.0f;
            updateTempDisplay(tvCurrentVal, tempHolder[0], isMph);
        });

        btnRow.addView(btnMinus);
        btnRow.addView(spacer);
        btnRow.addView(btnPlus);
        layout.addView(btnRow);

        Button btnSyncGps = new Button(this);
        btnSyncGps.setText("Use GPS weather");
        btnSyncGps.setAllCaps(false);
        btnSyncGps.setTextSize(18);
        btnSyncGps.setTextColor(ContextCompat.getColor(this, R.color.ad_label_1));
        btnSyncGps.setBackgroundResource(R.drawable.ad_bg_btn_secondary);
        LinearLayout.LayoutParams lpGps = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 64);
        lpGps.setMargins(0, 16, 0, 0);
        btnSyncGps.setLayoutParams(lpGps);

        Button btnSave = new Button(this);
        btnSave.setText("Save temperature");
        btnSave.setAllCaps(false);
        btnSave.setTextSize(18);
        btnSave.setTextColor(ContextCompat.getColor(this, R.color.ad_on_accent));
        btnSave.setBackgroundResource(R.drawable.ad_bg_btn_primary);
        LinearLayout.LayoutParams lpSave = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 64);
        lpSave.setMargins(0, 12, 0, 0);
        btnSave.setLayoutParams(lpSave);

        layout.addView(btnSyncGps);
        layout.addView(btnSave);

        // Scrolls rather than cutting off Save if the dialog is taller than the 600 px screen.
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(layout);
        builder.setView(scroll);
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
            float target = tempHolder[0];
            if (Float.isNaN(target)) {
                notifier.show(Notifier.Kind.INFO, "Set a temperature first", "Use − or + to match your instrument cluster");
                return;
            }
            if (thermometerWatcher.calibrateDisplayedTo(target)) {
                float offset = target - thermometerWatcher.getRawTempCelsius();
                notifier.show(Notifier.Kind.OK, "Temperature calibrated", String.format(Locale.US, "Offset %+.1f°C", offset));
            } else {
                // No reading to offset: keep the driver's value as a manual temperature.
                thermometerWatcher.setManualTemp(target, "Manual");
                notifier.show(Notifier.Kind.OK, "Temperature set", "Tap the temperature to change it or switch to GPS weather");
            }
            dialog.dismiss();
        });

        dialog.show();
    }

    private void updateTempDisplay(TextView tv, float tempC, boolean isMph) {
        if (Float.isNaN(tempC)) {
            tv.setText(isMph ? "–°F" : "–°C");
        } else if (isMph) {
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
        cleanupDialogBtReceivers();

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_device_selector, null);
        builder.setView(dialogView);
        deviceSelectorDialog = builder.create();

        if (deviceSelectorDialog.getWindow() != null) {
            deviceSelectorDialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            deviceSelectorDialog.getWindow().setDimAmount(0.5f);
        }

        LinearLayout deviceContainer = dialogView.findViewById(R.id.deviceListContainer);
        View layoutDeviceLoading = dialogView.findViewById(R.id.layoutDeviceLoading);
        ProgressBar pbDeviceLoading = dialogView.findViewById(R.id.pbDeviceLoading);
        TextView tvDeviceLoadingText = dialogView.findViewById(R.id.tvDeviceLoadingText);
        Button btnPair = dialogView.findViewById(R.id.btnPairNewDevice);
        View btnSkip = dialogView.findViewById(R.id.btnSkipSelector);

        int accentColor = accent();
        if (pbDeviceLoading != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            pbDeviceLoading.setIndeterminateTintList(ColorStateList.valueOf(accentColor));
        }

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        populateDeviceListWithLoader(dialogView, deviceContainer, layoutDeviceLoading, adapter, accentColor, 0);

        if (btnPair != null) {
            btnPair.setOnClickListener(v -> {
                cleanupDialogBtReceivers();
                if (deviceSelectorDialog != null) deviceSelectorDialog.dismiss();
                openBluetoothManager();
            });
        }

        if (btnSkip != null) {
            btnSkip.setOnClickListener(v -> {
                cleanupDialogBtReceivers();
                if (deviceSelectorDialog != null) deviceSelectorDialog.dismiss();
            });
        }

        deviceSelectorDialog.setOnDismissListener(dialog -> {
            cleanupDialogBtReceivers();
            if (isBtConnecting && btWatcher != null) {
                btWatcher.cancelPendingConnection();
                isBtConnecting = false;
            }
        });

        deviceSelectorDialog.show();
    }

    private void populateDeviceListWithLoader(View dialogView, LinearLayout deviceContainer, View layoutDeviceLoading, BluetoothAdapter adapter, int accentColor, int attempt) {
        @SuppressLint("MissingPermission")
        java.util.Set<BluetoothDevice> pairedDevices = (adapter != null && adapter.isEnabled()) ? adapter.getBondedDevices() : null;

        if (pairedDevices != null && !pairedDevices.isEmpty()) {
            if (layoutDeviceLoading != null) layoutDeviceLoading.setVisibility(View.GONE);
            renderDeviceCards(pairedDevices, deviceContainer, accentColor);
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
                                renderDeviceCards(devices, deviceContainer, accentColor);
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
                    populateDeviceListWithLoader(dialogView, deviceContainer, layoutDeviceLoading, currAdapter, accentColor, attempt + 1);
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
                BluetoothAdapter btNow = BluetoothAdapter.getDefaultAdapter();
                tvEmpty.setText(btNow != null && !btNow.isEnabled()
                        ? "Bluetooth is off. Turn it on in Phone settings, then try again."
                        : "No paired phones. Pair a phone to use it here.");
                tvEmpty.setTextColor(color(R.color.ad_label_2));
                tvEmpty.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.ad_text_label));
                tvEmpty.setPadding(16, 24, 16, 24);
                tvEmpty.setGravity(android.view.Gravity.CENTER);
                deviceContainer.addView(tvEmpty);
            }
        }
    }

    @SuppressLint("MissingPermission")
    private void renderDeviceCards(java.util.Set<BluetoothDevice> pairedDevices, LinearLayout deviceContainer, int accentColor) {
        if (deviceContainer == null) return;
        deviceContainer.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        String savedAddr = prefHelper.getSelectedDriverDevice();

        for (BluetoothDevice device : pairedDevices) {
            View card = inflater.inflate(R.layout.item_device_card, deviceContainer, false);
            TextView tvName = card.findViewById(R.id.tvDialogDeviceName);
            TextView tvStatus = card.findViewById(R.id.tvDialogDeviceStatus);
            TextView btnConnect = card.findViewById(R.id.btnConnectPill);
            View ivConnected = card.findViewById(R.id.ivDialogConnected);
            ImageView ivIcon = card.findViewById(R.id.ivDialogDeviceIcon);
            int label2 = color(R.color.ad_label_2);

            @SuppressLint("MissingPermission")
            String name = (device.getName() != null && !device.getName().isEmpty()) ? device.getName() : "Phone (" + device.getAddress() + ")";
            tvName.setText(name);

            boolean isActuallyConnected = (btWatcher != null && btWatcher.isDeviceCurrentlyConnected(device));
            boolean isSavedDriver = (savedAddr != null && savedAddr.equalsIgnoreCase(device.getAddress()));
            String lower = name.toLowerCase(Locale.US);
            if (ivIcon != null && (lower.contains("iphone") || lower.contains("ipad"))) {
                ivIcon.setImageResource(R.drawable.ic_sym_phone_iphone);
            }
            tvStatus.setTextColor(label2);
            if (isActuallyConnected) {
                tvStatus.setText("Connected");
                btnConnect.setVisibility(View.GONE);
                if (ivConnected != null) ivConnected.setVisibility(View.VISIBLE);
            } else {
                tvStatus.setText(isSavedDriver ? "Your phone" : "Paired");
                btnConnect.setText("Connect");
            }

            View.OnClickListener selectListener = v -> {
                if (isBtConnecting) return;

                cleanupDialogBtReceivers();

                // If this device is ALREADY verified as connected (via A2DP, Adapter, Audio routing, or vendor BT)
                if (btWatcher != null && btWatcher.isDeviceCurrentlyConnected(device)) {
                    prefHelper.setSelectedDriverDevice(device.getAddress(), device.getName());
                    isCarPlayConnected = true;
                    updateConnectedPhoneUi(name, "Connected");
                    notifier.show(Notifier.Kind.OK, name + " connected", "Starting CarPlay");
                    if (deviceSelectorDialog != null && deviceSelectorDialog.isShowing()) {
                        deviceSelectorDialog.dismiss();
                    }
                    launchCarPlay();
                    return;
                }

                // Device is not yet confirmed connected: initiate real connection and gate CarPlay launch on success
                isBtConnecting = true;
                prefHelper.setSelectedDriverDevice(device.getAddress(), device.getName());

                tvStatus.setText("Connecting…");
                btnConnect.setText("Connecting…");

                // Disable all cards while connection is in progress so driver doesn't spam
                for (int i = 0; i < deviceContainer.getChildCount(); i++) {
                    View c = deviceContainer.getChildAt(i);
                    c.setEnabled(false);
                    View b = c.findViewById(R.id.btnConnectPill);
                    if (b != null) b.setEnabled(false);
                }

                updateConnectedPhoneUi(name, "Connecting");
                notifier.show(Notifier.Kind.INFO, "Connecting to " + name);

                if (btWatcher != null) {
                    btWatcher.connectToDevice(device, new BluetoothWatcher.DeviceConnectionCallback() {
                        @Override
                        public void onConnecting(BluetoothDevice dev) {
                            runOnUiThread(() -> {
                                if (deviceSelectorDialog != null && deviceSelectorDialog.isShowing()) {
                                    tvStatus.setText("Connecting…");
                                }
                            });
                        }

                        @Override
                        public void onSuccess(BluetoothDevice dev) {
                            runOnUiThread(() -> {
                                isBtConnecting = false;
                                isCarPlayConnected = true;
                                prefHelper.setSelectedDriverDevice(device.getAddress(), device.getName());

                                if (deviceSelectorDialog != null && deviceSelectorDialog.isShowing()) {
                                    tvStatus.setText("Connected, opening CarPlay");
                                    btnConnect.setVisibility(View.GONE);
                                    if (ivConnected != null) ivConnected.setVisibility(View.VISIBLE);
                                }

                                updateConnectedPhoneUi(name, "Connected");
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
                                    tvStatus.setText("Couldn't confirm. Already connected?");
                                    btnConnect.setText("Open CarPlay");

                                    View.OnClickListener launchAnywayListener = fl -> {
                                        prefHelper.setSelectedDriverDevice(device.getAddress(), device.getName());
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

                                notifier.show(Notifier.Kind.CAUTION, "Couldn't confirm " + name, "If it's connected, tap Open CarPlay, or check Phone settings");
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

                setTrackText(title != null && !title.trim().isEmpty() ? title : null,
                        artist != null && !artist.trim().isEmpty() ? artist : null);
                if (btnMediaPlayPause != null && hasPlayState) {
                    setPlayState(isPlaying);
                }

                String action = intent.getAction();
                boolean isCarPlaySource = action != null && (action.startsWith("com.zlink") || action.startsWith("zjinnova") || action.contains("diplay"));
                if (isCarPlaySource && isPlaying) {
                    isCarPlayConnected = true;
                    String savedName = getDeviceNameFromAddress(prefHelper.getSelectedDriverDevice());
                    updateConnectedPhoneUi(savedName != null ? savedName : "Phone", "CarPlay active");
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

        // In-process now-playing updates (the MEDIA_UPDATED broadcast is gone).
        MediaStateHolder.getInstance().addListener(mediaStateListener);
    }

    private final MediaStateHolder.Listener mediaStateListener = s -> {
        setTrackText(s.title, s.artist);
        if (s.hasTrack()) setPlayState(s.playing);
        if (viewDash != null) {
            ImageView art = viewDash.findViewById(R.id.ivAlbumArt);
            View placeholder = viewDash.findViewById(R.id.ivAlbumArtPlaceholder);
            if (art != null) art.setImageBitmap(s.art);
            if (placeholder != null) placeholder.setVisibility(s.art != null ? View.GONE : View.VISIBLE);
            TextView source = viewDash.findViewById(R.id.tvTrackSource);
            if (source != null && s.sourcePackage != null) source.setText(appLabel(s.sourcePackage));
        }
        // Only a CarPlay bridge playing means projection is running; any other player does not.
        if (s.playing && isCarPlayBridge(s.sourcePackage)) {
            isCarPlayConnected = true;
            String savedName = getDeviceNameFromAddress(prefHelper.getSelectedDriverDevice());
            updateConnectedPhoneUi(savedName != null ? savedName : "Phone", "CarPlay active");
        }
    };

    private boolean isCarPlayBridge(String pkg) {
        if (pkg == null) return false;
        return pkg.equals(prefHelper.getCarPlayPackage()) || pkg.contains("zlink") || pkg.contains("zjinnova")
                || pkg.contains("diplay") || pkg.contains("carplay") || pkg.contains("autokit") || pkg.contains("carlinke");
    }

    private void launchCarPlay() {
        // 1. The app the user chose in Settings.
        if (launchAppPackage(prefHelper.getCarPlayPackage())) {
            checkCarPlayOpened(carPlayAppName());
            return;
        }

        // 2. Zlink's explicit entry point.
        try {
            Intent zlinkIntent = new Intent("zjinnova.android.intent.action.ZLINK_MAIN");
            zlinkIntent.putExtra("page", "LINK");
            zlinkIntent.putExtra("feature", "carplay");
            zlinkIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (zlinkIntent.resolveActivity(getPackageManager()) != null) {
                startActivity(zlinkIntent);
                checkCarPlayOpened("Zlink");
                return;
            }
        } catch (Exception ignored) {}

        // 3. Common CarPlay bridge apps; remember the one that worked.
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
        for (String fallback : commonCarPlayPackages) {
            if (launchAppPackage(fallback)) {
                prefHelper.setCarPlayPackage(fallback);
                prefHelper.setCarPlayName(appLabel(fallback));
                checkCarPlayOpened(appLabel(fallback));
                return;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("No CarPlay app found")
                .setMessage("Couldn't open " + prefHelper.getCarPlayName() + " or another CarPlay app. Choose the app that runs CarPlay on this head unit.")
                .setPositiveButton("Choose app", (dialog, which) -> showCarPlayPicker())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private boolean activityResumed;
    private String pendingCarPlayApp = "";
    private final Runnable carPlayOpenCheck = () -> {
        if (!activityResumed || isFinishing()) return;
        notifier.show(Notifier.Kind.ALERT, pendingCarPlayApp + " didn't open CarPlay",
                "Connect your iPhone in the head unit's Bluetooth app first", "Bluetooth", this::openBluetoothManager);
    };

    /**
     * Some CarPlay apps (Zlink, TLINK) close straight away when no phone is connected, so
     * the launcher comes back and nothing seems to happen. If it is still in front a moment
     * after the launch, say so instead of failing silently.
     */
    private void checkCarPlayOpened(String appName) {
        pendingCarPlayApp = appName;
        clockHandler.removeCallbacks(carPlayOpenCheck);
        clockHandler.postDelayed(carPlayOpenCheck, 2500);
    }

    /** The CarPlay app's name as installed, or the saved name when it is not installed. */
    private String carPlayAppName() {
        String pkg = prefHelper.getCarPlayPackage();
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return prefHelper.getCarPlayName();
        }
    }

    private String appLabel(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
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
        activityResumed = true;
        makeFullScreen();
        if (gpsManager != null) gpsManager.start();
        if (btWatcher != null) btWatcher.forceRefresh();
        clockHandler.removeCallbacks(carPlayLinkPoll);
        clockHandler.post(carPlayLinkPoll);
    }

    @Override
    protected void onPause() {
        super.onPause();
        activityResumed = false;
        clockHandler.removeCallbacks(carPlayLinkPoll);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        clockHandler.removeCallbacksAndMessages(null);
        if (gpsManager != null) gpsManager.stop();
        if (btWatcher != null) btWatcher.stop();
        if (thermometerWatcher != null) thermometerWatcher.stop();
        cleanupDialogBtReceivers();
        if (deviceSelectorDialog != null && deviceSelectorDialog.isShowing()) {
            deviceSelectorDialog.dismiss();
        }
        MediaStateHolder.getInstance().removeListener(mediaStateListener);
        if (mediaReceiver != null) {
            try {
                unregisterReceiver(mediaReceiver);
            } catch (Exception ignored) {}
        }
        if (packageReceiver != null) {
            try {
                unregisterReceiver(packageReceiver);
            } catch (Exception ignored) {}
        }
    }
}
