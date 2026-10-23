package com.apexdrive.launcher.services;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reports which phone (if any) is connected to the head unit.
 *
 * A phone is reported as connected ONLY on per-device evidence (see {@link BluetoothEvidence}):
 * the A2DP/HFP profile proxies, profile and ACL broadcasts, and the hidden isConnected() flag.
 * Adapter-level state, audio routing and vendor Settings/properties are never treated as proof;
 * they are only written to the diagnostics string ({@link #getDiagnostics()}).
 *
 * All Bluetooth work runs on one worker thread. Listener and connection callbacks are delivered
 * on the main thread. State is event driven; a slow fallback poll catches missed broadcasts.
 *
 * Verify in the car (Audit 1, trip item 1): with the phone off, paired but not connected, and
 * connected, read getDiagnostics() and compare it with what the phone says. Everything that
 * depends on the real Bluetooth stack of the unit (profile proxy contents, whether ACL and
 * profile broadcasts arrive, hidden-API availability) can only be confirmed there.
 */
@SuppressLint("MissingPermission")
public class BluetoothWatcher {

    private static final String TAG = "ApexBt";
    private static final long FALLBACK_POLL_MS = 20000;
    private static final long CONNECT_TIMEOUT_MS = 9000;
    private static final long[] CONNECT_CHECK_DELAYS_MS = {700, 1400, 2200, 3200, 4400, 5800, 7200, 8500};
    private static final int LOG_LINES = 50;

    private static final String ACTION_A2DP_STATE = "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED";
    private static final String ACTION_HFP_STATE = "android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED";

    /** Vendor head-unit broadcasts. They only trigger a re-check; they are not evidence. */
    private static final String[] VENDOR_STATE_ACTIONS = {
            "com.goc.bluetooth.connected", "com.goc.bluetooth.disconnected",
            "com.android.ecar.bluetooth.connected",
            "com.syu.bt.connected", "com.syu.bt.disconnected",
            "action.hct.bt.connected", "action.hct.bt.disconnected",
            "com.ts.bt.connected", "com.ts.bt.disconnected",
            "com.microntek.bt.connected", "com.microntek.bt.disconnected",
            "com.allwinner.bt.connected", "com.allwinner.bt.disconnected",
            "com.allwinner.bluetooth.connected", "com.allwinner.bluetooth.disconnected",
            "cn.manstep.phonemirrorbox.bt.connected", "com.zjinnova.zlink.bt.connected"
    };

    public interface BluetoothStateListener {
        void onBluetoothStateChanged(boolean isConnected, String deviceName, String details);
    }

    public interface DeviceConnectionCallback {
        void onConnecting(BluetoothDevice device);
        void onSuccess(BluetoothDevice device);
        void onFailure(BluetoothDevice device, String errorMessage);
    }

    private final Context context;
    private final BluetoothAdapter bluetoothAdapter;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final BluetoothEvidence evidence = new BluetoothEvidence();
    private final Map<String, BluetoothDevice> knownDevices = new ConcurrentHashMap<>();
    private final ArrayDeque<String> log = new ArrayDeque<>();

    private volatile BluetoothStateListener listener;
    private volatile boolean isRunning = false;
    private volatile Handler work;
    private HandlerThread workThread;
    private BroadcastReceiver receiver;

    // Worker-thread state
    private BluetoothA2dp a2dpProfile;
    private BluetoothHeadset headsetProfile;
    private boolean a2dpRequested;
    private boolean headsetRequested;
    private BluetoothDevice pendingConnectDevice;
    private DeviceConnectionCallback pendingCallback;
    private boolean hasPublished;
    private boolean lastConnected;
    private String lastName;
    private String lastDetail;

    private volatile BluetoothDevice connectedDevice = null;
    private volatile String stateDiagnostics = "not started";
    private volatile String hintDiagnostics = "";

    private final Runnable fallbackPoll = new Runnable() {
        @Override
        public void run() {
            Handler w = work;
            if (!isRunning || w == null) return;
            refresh("fallback poll", false);
            w.postDelayed(this, FALLBACK_POLL_MS);
        }
    };

    private final Runnable pendingTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (pendingConnectDevice == null || pendingCallback == null) return;
            refresh("connect timeout check", false);
            if (pendingConnectDevice == null) return; // refresh confirmed it
            failPending("Connect timed out. Tap 'LAUNCH ANYWAY' if already connected in car, or open Car Bluetooth.");
        }
    };

    public BluetoothWatcher(Context context) {
        this.context = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        this.bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
    }

    public void setListener(BluetoothStateListener listener) {
        this.listener = listener;
    }

    // ------------------------------------------------------------------ lifecycle

    public void start() {
        if (isRunning) return;
        isRunning = true;

        workThread = new HandlerThread("ApexBluetooth");
        workThread.start();
        final Handler w = new Handler(workThread.getLooper());
        work = w;

        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                handleBroadcast(intent);
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(ACTION_A2DP_STATE);
        filter.addAction(ACTION_HFP_STATE);
        for (String a : VENDOR_STATE_ACTIONS) filter.addAction(a);

        try {
            // Delivered on the worker thread, so broadcasts never touch the UI thread.
            context.registerReceiver(receiver, filter, null, w);
        } catch (Exception e) {
            Log.w(TAG, "registerReceiver failed", e);
            addLog("registerReceiver failed: " + e.getClass().getSimpleName());
        }

        w.post(new Runnable() {
            @Override
            public void run() {
                evidence.clear();
                refresh("start", true);
            }
        });
        w.postDelayed(fallbackPoll, FALLBACK_POLL_MS);
    }

    public void stop() {
        if (!isRunning) return;
        isRunning = false;
        final Handler w = work;
        final HandlerThread t = workThread;
        work = null;
        workThread = null;

        if (receiver != null) {
            try {
                context.unregisterReceiver(receiver);
            } catch (Exception ignored) {}
            receiver = null;
        }
        if (w != null) {
            w.removeCallbacksAndMessages(null);
            w.post(new Runnable() {
                @Override
                public void run() {
                    failPending("Connection cancelled.");
                    releaseProxies();
                    evidence.clear();
                    knownDevices.clear();
                    connectedDevice = null;
                    hasPublished = false;
                    if (t != null) t.quitSafely();
                }
            });
        } else if (t != null) {
            t.quitSafely();
        }
    }

    // ------------------------------------------------------------------ broadcasts (worker thread)

    private void handleBroadcast(Intent intent) {
        if (intent == null || intent.getAction() == null || !isRunning) return;
        String action = intent.getAction();

        if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
            if (state == BluetoothAdapter.STATE_ON) {
                // P-05: the stack came up after start(); bind the profile proxies now.
                initProfileProxies();
                refresh("adapter STATE_ON", false);
            } else if (state == BluetoothAdapter.STATE_TURNING_OFF || state == BluetoothAdapter.STATE_OFF) {
                releaseProxies();
                evidence.clear();
                refresh("adapter off", false);
            }
            return;
        }

        BluetoothDevice device = deviceExtra(intent);

        if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
            if (device != null && (isPhoneLike(device) || isPending(device))) {
                remember(device);
                evidence.add(device.getAddress(), BluetoothEvidence.Signal.ACL);
            }
            refresh("ACL_CONNECTED", false);
        } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)
                || BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED.equals(action)) {
            if (device != null) {
                evidence.remove(device.getAddress(), BluetoothEvidence.Signal.ACL);
                if (isPending(device) && BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
                    failPending("Device disconnected.");
                }
            }
            refresh("ACL_DISCONNECTED", false);
        } else if (ACTION_A2DP_STATE.equals(action) || ACTION_HFP_STATE.equals(action)) {
            BluetoothEvidence.Signal signal = ACTION_A2DP_STATE.equals(action)
                    ? BluetoothEvidence.Signal.A2DP : BluetoothEvidence.Signal.HFP;
            int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1);
            if (device != null) {
                if (state == BluetoothProfile.STATE_CONNECTED) {
                    remember(device);
                    evidence.add(device.getAddress(), signal);
                } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                    evidence.remove(device.getAddress(), signal);
                }
            }
            refresh(signal.name() + " state=" + state, false);
        } else {
            // Bond change, adapter-level connection change, vendor actions: re-check only.
            refresh("broadcast " + action, false);
        }
    }

    private static BluetoothDevice deviceExtra(Intent intent) {
        try {
            return intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        } catch (Exception e) {
            return null;
        }
    }

    /** ACL links also exist for watches, keyboards and the like; only phone-like devices count. */
    private static boolean isPhoneLike(BluetoothDevice device) {
        try {
            BluetoothClass cls = device.getBluetoothClass();
            if (cls == null) return true;
            int major = cls.getMajorDeviceClass();
            return major == BluetoothClass.Device.Major.PHONE
                    || major == BluetoothClass.Device.Major.UNCATEGORIZED;
        } catch (Exception e) {
            return true;
        }
    }

    private boolean isPending(BluetoothDevice device) {
        return pendingConnectDevice != null && device != null
                && pendingConnectDevice.getAddress().equalsIgnoreCase(device.getAddress());
    }

    private void remember(BluetoothDevice device) {
        if (device != null && device.getAddress() != null) {
            knownDevices.put(device.getAddress().toUpperCase(Locale.US), device);
        }
    }

    // ------------------------------------------------------------------ profile proxies (worker thread)

    private final class ProxyListener implements BluetoothProfile.ServiceListener {
        @Override
        public void onServiceConnected(final int profile, final BluetoothProfile proxy) {
            Handler w = work;
            boolean posted = isRunning && w != null && w.post(new Runnable() {
                @Override
                public void run() {
                    if (!isRunning) {
                        closeProxy(profile, proxy);
                        return;
                    }
                    if (profile == BluetoothProfile.A2DP) a2dpProfile = (BluetoothA2dp) proxy;
                    else if (profile == BluetoothProfile.HEADSET) headsetProfile = (BluetoothHeadset) proxy;
                    addLog("proxy connected: " + (profile == BluetoothProfile.A2DP ? "A2DP" : "HFP"));
                    refresh("proxy connected", false);
                }
            });
            if (!posted) closeProxy(profile, proxy);
        }

        @Override
        public void onServiceDisconnected(final int profile) {
            Handler w = work;
            if (!isRunning || w == null) return;
            w.post(new Runnable() {
                @Override
                public void run() {
                    if (profile == BluetoothProfile.A2DP) {
                        a2dpProfile = null;
                        a2dpRequested = false;
                    } else if (profile == BluetoothProfile.HEADSET) {
                        headsetProfile = null;
                        headsetRequested = false;
                    }
                    refresh("proxy disconnected", false);
                }
            });
        }
    }

    private void closeProxy(int profile, BluetoothProfile proxy) {
        try {
            if (bluetoothAdapter != null) bluetoothAdapter.closeProfileProxy(profile, proxy);
        } catch (Exception e) {
            Log.w(TAG, "closeProfileProxy failed", e);
        }
    }

    private void initProfileProxies() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) return;
        try {
            if (!a2dpRequested) {
                a2dpRequested = bluetoothAdapter.getProfileProxy(context, new ProxyListener(), BluetoothProfile.A2DP);
            }
            if (!headsetRequested) {
                headsetRequested = bluetoothAdapter.getProfileProxy(context, new ProxyListener(), BluetoothProfile.HEADSET);
            }
        } catch (Exception e) {
            Log.w(TAG, "getProfileProxy failed", e);
            addLog("getProfileProxy failed: " + e.getClass().getSimpleName());
        }
    }

    private void releaseProxies() {
        if (a2dpProfile != null) closeProxy(BluetoothProfile.A2DP, a2dpProfile);
        if (headsetProfile != null) closeProxy(BluetoothProfile.HEADSET, headsetProfile);
        a2dpProfile = null;
        headsetProfile = null;
        a2dpRequested = false;
        headsetRequested = false;
    }

    // ------------------------------------------------------------------ state evaluation (worker thread)

    private void postRefresh(final String reason, final boolean force) {
        Handler w = work;
        if (!isRunning || w == null) return;
        w.post(new Runnable() {
            @Override
            public void run() {
                refresh(reason, force);
            }
        });
    }

    /** Re-reads every per-device signal and notifies the listener if the state changed. */
    private void refresh(String reason, boolean force) {
        if (!isRunning) return;

        if (bluetoothAdapter == null) {
            evidence.clear();
            publish(reason, force, false, null, "Not Connected", "Bluetooth Not Supported", 0);
            return;
        }
        if (!bluetoothAdapter.isEnabled()) {
            evidence.clear();
            publish(reason, force, false, null, "Not Connected", "Bluetooth Off", 0);
            return;
        }

        initProfileProxies();

        Set<BluetoothDevice> bonded = null;
        try {
            // Proxy contents are authoritative for their profile when the proxy is bound.
            if (a2dpProfile != null) {
                evidence.replace(BluetoothEvidence.Signal.A2DP, addressesOf(a2dpProfile.getConnectedDevices()));
            }
            if (headsetProfile != null) {
                evidence.replace(BluetoothEvidence.Signal.HFP, addressesOf(headsetProfile.getConnectedDevices()));
            }
            bonded = bluetoothAdapter.getBondedDevices();
        } catch (Exception e) {
            Log.w(TAG, "profile query failed", e);
            addLog("profile query failed: " + e.getClass().getSimpleName());
        }

        // Hidden isConnected() on bonded devices: per-device, but a non-SDK call.
        List<String> viaIsConnected = new ArrayList<>();
        if (bonded != null) {
            for (BluetoothDevice dev : bonded) {
                if (dev == null) continue;
                Boolean c = BtHiddenApi.deviceIsConnected(dev);
                if (Boolean.TRUE.equals(c)) {
                    remember(dev);
                    viaIsConnected.add(dev.getAddress());
                } else if (Boolean.FALSE.equals(c)) {
                    // An explicit "not connected" outweighs a link-only (ACL) signal that may be stale.
                    EnumSet<BluetoothEvidence.Signal> s = evidence.signalsFor(dev.getAddress());
                    if (!s.contains(BluetoothEvidence.Signal.A2DP) && !s.contains(BluetoothEvidence.Signal.HFP)) {
                        evidence.remove(dev.getAddress(), BluetoothEvidence.Signal.ACL);
                    }
                }
            }
        }
        evidence.replace(BluetoothEvidence.Signal.DEVICE_IS_CONNECTED, viaIsConnected);

        int bondedCount = bonded == null ? 0 : bonded.size();
        String primary = evidence.primaryAddress();
        BluetoothDevice device = primary == null ? null : knownDevices.get(primary);
        if (primary != null && device == null && bonded != null) {
            for (BluetoothDevice d : bonded) {
                if (d != null && primary.equalsIgnoreCase(d.getAddress())) device = d;
            }
        }
        if (primary != null && device == null) {
            try {
                device = bluetoothAdapter.getRemoteDevice(primary);
            } catch (Exception ignored) {}
        }

        if (pendingConnectDevice != null && evidence.isConnected(pendingConnectDevice.getAddress())) {
            confirmPending();
        }

        if (device != null) {
            publish(reason, force, true, device, getDeviceDisplayName(device),
                    BluetoothEvidence.describeProfiles(evidence.signalsFor(primary)), bondedCount);
        } else {
            String detail = bondedCount > 0
                    ? "Ready to Connect (" + bondedCount + " Paired)"
                    : "No paired phone. Pair from the head unit's Bluetooth settings";
            publish(reason, force, false, null, "Not Connected", detail, bondedCount);
        }
    }

    private static List<String> addressesOf(List<BluetoothDevice> devices) {
        List<String> out = new ArrayList<>();
        if (devices != null) {
            for (BluetoothDevice d : devices) {
                if (d != null && d.getAddress() != null) out.add(d.getAddress());
            }
        }
        return out;
    }

    private void publish(String reason, boolean force, boolean connected, BluetoothDevice device,
                         String name, String detail, int bondedCount) {
        connectedDevice = device;
        boolean changed = !hasPublished || connected != lastConnected
                || !name.equals(lastName) || !detail.equals(lastDetail);
        if (!changed && !force) return;

        hasPublished = true;
        lastConnected = connected;
        lastName = name;
        lastDetail = detail;

        String line = (connected ? "CONNECTED" : "NOT CONNECTED") + " via [" + evidence.describe()
                + "] reason=" + reason;
        stateDiagnostics = line;
        hintDiagnostics = describeHints();
        addLog(line);
        Log.i(TAG, line);

        final BluetoothStateListener l = listener;
        if (l != null) {
            final boolean c = connected;
            final String n = name;
            final String d = detail;
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    l.onBluetoothStateChanged(c, n, d);
                }
            });
        }
    }

    // ------------------------------------------------------------------ public queries

    /** Cheap and safe on the UI thread: reads the evidence gathered by the worker. */
    public boolean isDeviceCurrentlyConnected(BluetoothDevice device) {
        return device != null && evidence.isConnected(device.getAddress());
    }

    public BluetoothDevice getConnectedDevice() {
        return connectedDevice;
    }

    /**
     * Whether audio is currently routed to Bluetooth. This is NOT proof that a phone is connected
     * (a head unit can route to its own Bluetooth module); do not use it as a connection test.
     */
    public boolean isAudioA2dpActive() {
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                return am.isBluetoothA2dpOn() || am.isBluetoothScoOn();
            }
        } catch (Exception e) {
            Log.w(TAG, "AudioManager query failed", e);
        }
        return false;
    }

    /**
     * @deprecated Vendor Settings and properties are no longer trusted. Kept so existing call
     * sites compile; it now answers from per-device evidence only.
     */
    @Deprecated
    public boolean checkVendorBtConnected() {
        return connectedDevice != null;
    }

    @SuppressLint("MissingPermission")
    public String getDeviceDisplayName(BluetoothDevice dev) {
        if (dev != null) {
            String name = dev.getName();
            if (name != null && !name.trim().isEmpty()) {
                return name;
            }
            if (dev.getAddress() != null) {
                return "Phone (" + BluetoothEvidence.maskAddress(dev.getAddress()) + ")";
            }
        }
        return "Connected Phone";
    }

    public void forceRefresh() {
        postRefresh("forceRefresh", true);
    }

    public void checkCurrentState() {
        postRefresh("checkCurrentState", false);
    }

    // ------------------------------------------------------------------ connecting

    /** Asks the stack and the head unit to connect to an already paired phone. Never pairs. */
    public void signalConnect(final BluetoothDevice target) {
        Handler w = work;
        if (target == null || !isRunning || w == null) return;
        w.post(new Runnable() {
            @Override
            public void run() {
                if (target.getBondState() != BluetoothDevice.BOND_BONDED) {
                    addLog("signalConnect ignored: device not bonded");
                    return;
                }
                sendConnectSignals(target);
            }
        });
    }

    private void sendConnectSignals(BluetoothDevice target) {
        broadcastHeadUnitConnect(target);
        BtHiddenApi.deviceConnect(target);
    }

    public void connectToDevice(final BluetoothDevice target, final DeviceConnectionCallback callback) {
        Handler w = work;
        if (!isRunning || w == null) {
            deliverFailure(callback, target, "Bluetooth monitor is not running.");
            return;
        }
        w.post(new Runnable() {
            @Override
            public void run() {
                startConnect(target, callback);
            }
        });
    }

    private void startConnect(final BluetoothDevice target, final DeviceConnectionCallback callback) {
        if (target == null) {
            deliverFailure(callback, null, "Invalid target device.");
            return;
        }
        remember(target);

        // Fast path: per-device evidence says it is already connected.
        refresh("connect request", false);
        if (evidence.isConnected(target.getAddress())) {
            deliverSuccess(callback, target);
            refresh("connect request (already connected)", true);
            return;
        }

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            deliverFailure(callback, target, "Bluetooth is disabled on this head unit.");
            return;
        }
        if (target.getBondState() != BluetoothDevice.BOND_BONDED) {
            // P-07: never start pairing from a connect attempt.
            deliverFailure(callback, target, "Phone is not paired. Pair it from the head unit's Bluetooth settings.");
            return;
        }

        failPending("Connection cancelled.");

        // One burst of vendor broadcasts and one hidden device.connect() per attempt (P-08).
        sendConnectSignals(target);

        if (callback != null) {
            pendingConnectDevice = target;
            pendingCallback = callback;
            deliverConnecting(callback, target);
            Handler w = work;
            if (w != null) {
                w.postDelayed(pendingTimeoutRunnable, CONNECT_TIMEOUT_MS);
                for (long delay : CONNECT_CHECK_DELAYS_MS) {
                    w.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (isPending(target)) refresh("connect poll", false);
                        }
                    }, delay);
                }
            }
        }

        connectViaProfile(BluetoothProfile.A2DP, a2dpProfile, target);
        connectViaProfile(BluetoothProfile.HEADSET, headsetProfile, target);
    }

    private void connectViaProfile(final int profile, BluetoothProfile proxy, final BluetoothDevice target) {
        if (proxy != null) {
            BtHiddenApi.profileConnect(proxy, target);
            return;
        }
        if (bluetoothAdapter == null || profile != BluetoothProfile.A2DP) return;
        // No persistent proxy yet: use a temporary one and always close it (P-08 leak).
        try {
            bluetoothAdapter.getProfileProxy(context, new BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int p, BluetoothProfile temp) {
                    try {
                        BtHiddenApi.profileConnect(temp, target);
                    } finally {
                        closeProxy(p, temp);
                    }
                }

                @Override
                public void onServiceDisconnected(int p) {}
            }, profile);
        } catch (Exception e) {
            Log.w(TAG, "temporary proxy failed", e);
        }
    }

    public void cancelPendingConnection() {
        Handler w = work;
        if (!isRunning || w == null) return;
        w.post(new Runnable() {
            @Override
            public void run() {
                failPending("Connection cancelled.");
            }
        });
    }

    private void confirmPending() {
        BluetoothDevice dev = pendingConnectDevice;
        DeviceConnectionCallback cb = pendingCallback;
        clearPending();
        if (dev != null) deliverSuccess(cb, dev);
    }

    private void failPending(String message) {
        BluetoothDevice dev = pendingConnectDevice;
        DeviceConnectionCallback cb = pendingCallback;
        clearPending();
        if (dev != null && cb != null) deliverFailure(cb, dev, message);
    }

    private void clearPending() {
        Handler w = work;
        if (w != null) w.removeCallbacks(pendingTimeoutRunnable);
        pendingConnectDevice = null;
        pendingCallback = null;
    }

    private void deliverConnecting(final DeviceConnectionCallback cb, final BluetoothDevice d) {
        if (cb == null) return;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                cb.onConnecting(d);
            }
        });
    }

    private void deliverSuccess(final DeviceConnectionCallback cb, final BluetoothDevice d) {
        if (cb == null) return;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                cb.onSuccess(d);
            }
        });
    }

    private void deliverFailure(final DeviceConnectionCallback cb, final BluetoothDevice d, final String msg) {
        if (cb == null) return;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                cb.onFailure(d, msg);
            }
        });
    }

    /**
     * Vendor "connect" broadcasts for head units that listen for them.
     * TODO(P-39/P-76): restrict to the receiver named by a vehicle profile; today these are
     * implicit, public and carry the phone's MAC and name. Verify in the car which one (if any)
     * the unit reacts to.
     */
    private void broadcastHeadUnitConnect(BluetoothDevice target) {
        if (target == null) return;
        String address = target.getAddress();
        String name = (target.getName() != null) ? target.getName() : "";

        String[] actions = {
                "com.allwinner.bt.connect",
                "com.allwinner.bluetooth.connect",
                "com.goc.bluetooth.connect",
                "com.android.ecar.bluetooth.connect",
                "com.syu.bt.connect",
                "action.hct.bt.connect",
                "com.ts.bt.connect",
                "com.microntek.bt.connect",
                "cn.manstep.phonemirrorbox.bt.connect",
                "com.zjinnova.zlink.bt.connect",
                "com.forfan.bt.connect"
        };

        for (String action : actions) {
            try {
                Intent intent = new Intent(action);
                intent.putExtra("address", address);
                intent.putExtra("mac", address);
                intent.putExtra("name", name);
                intent.putExtra("dev_name", name);
                intent.putExtra("device", target);
                context.sendBroadcast(intent);
            } catch (Exception e) {
                Log.w(TAG, "vendor broadcast failed: " + action, e);
            }
        }
    }

    // ------------------------------------------------------------------ diagnostics

    private void addLog(String line) {
        String stamped = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + " " + line;
        synchronized (log) {
            if (log.size() >= LOG_LINES) log.removeFirst();
            log.addLast(stamped);
        }
    }

    /**
     * Multi-line report for bug reports from a car: the signal behind the current state, the
     * hidden-API outcomes, the vendor hints (never used as evidence) and the recent history.
     * Phone addresses are masked and names are not included.
     */
    public String getDiagnostics() {
        StringBuilder sb = new StringBuilder();
        sb.append("Bluetooth state: ").append(stateDiagnostics).append('\n');
        sb.append("Hidden APIs: ").append(BtHiddenApi.outcomeSummary()).append('\n');
        sb.append("Hints (not evidence): ").append(hintDiagnostics).append('\n');
        synchronized (log) {
            for (String l : log) sb.append(l).append('\n');
        }
        return sb.toString();
    }

    /** Reads adapter, audio and vendor values for the diagnostics only. Worker thread only. */
    private String describeHints() {
        StringBuilder sb = new StringBuilder();
        sb.append("sdk=").append(Build.VERSION.SDK_INT);
        try {
            if (bluetoothAdapter != null) {
                sb.append(" adapterOn=").append(bluetoothAdapter.isEnabled());
                sb.append(" adapterA2dp=").append(bluetoothAdapter.getProfileConnectionState(BluetoothProfile.A2DP));
                sb.append(" adapterHfp=").append(bluetoothAdapter.getProfileConnectionState(BluetoothProfile.HEADSET));
                sb.append(" adapterConn=").append(BtHiddenApi.adapterConnectionState(bluetoothAdapter));
            }
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                sb.append(" a2dpOn=").append(am.isBluetoothA2dpOn()).append(" scoOn=").append(am.isBluetoothScoOn());
            }
            ContentResolver cr = context.getContentResolver();
            sb.append(" bluetooth_state=").append(Settings.System.getInt(cr, "bluetooth_state", -1));
            sb.append(" bt_device_name=").append(present(Settings.System.getString(cr, "bt_device_name")));
            sb.append(" bt_connected_dev_name=").append(present(Settings.System.getString(cr, "bt_connected_dev_name")));
            String addr = Settings.System.getString(cr, "current_connect_bt_addr");
            sb.append(" current_connect_bt_addr=").append(addr == null || addr.isEmpty()
                    ? "empty" : BluetoothEvidence.maskAddress(addr));
        } catch (Exception e) {
            sb.append(" (hint read failed: ").append(e.getClass().getSimpleName()).append(')');
        }
        String[] props = {"sys.bt.connected", "persist.sys.bt.connected", "vendor.bt.connected",
                "ts.bt.state", "goc.bt.connected", "sys.fyt.bluetooth", "bt.connected.name", "bt.connected.mac"};
        for (String p : props) {
            String v = BtHiddenApi.systemProperty(p);
            if (v != null && !v.isEmpty()) sb.append(' ').append(p).append("=set");
        }
        return sb.toString();
    }

    private static String present(String s) {
        return s == null || s.isEmpty() ? "empty" : "set";
    }
}
