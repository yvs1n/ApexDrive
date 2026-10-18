package com.apexdrive.launcher.services;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

@SuppressLint("MissingPermission")
public class BluetoothWatcher {

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
    private BluetoothStateListener listener;
    private BroadcastReceiver receiver;
    private final Handler pollHandler = new Handler(Looper.getMainLooper());
    private boolean isRunning = false;

    private BluetoothA2dp a2dpProfile = null;
    private BluetoothHeadset headsetProfile = null;

    private BluetoothDevice connectedDevice = null;
    private BluetoothDevice pendingConnectDevice = null;
    private DeviceConnectionCallback pendingCallback = null;

    private final Runnable pendingTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (pendingConnectDevice != null && pendingCallback != null) {
                // Final check: if device is connected via any channel, confirm success
                if (isDeviceCurrentlyConnected(pendingConnectDevice)) {
                    confirmConnected(pendingConnectDevice);
                    return;
                }
                DeviceConnectionCallback cb = pendingCallback;
                BluetoothDevice dev = pendingConnectDevice;
                pendingCallback = null;
                pendingConnectDevice = null;
                cb.onFailure(dev, "Auto-connect timed out. Tap 'LAUNCH ANYWAY' if already connected in car, or open Car Bluetooth.");
            }
        }
    };

    public BluetoothWatcher(Context context) {
        this.context = context;
        this.bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
    }

    public void setListener(BluetoothStateListener listener) {
        this.listener = listener;
    }

    @SuppressLint("MissingPermission")
    public void start() {
        if (isRunning) return;
        isRunning = true;

        // Establish persistent A2DP and Headset profile proxy bindings
        initProfileProxies();

        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (intent == null || intent.getAction() == null) return;
                String action = intent.getAction();

                boolean isProfileChange = "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED".equals(action) ||
                        "android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED".equals(action);
                int profileState = isProfileChange ? intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1) : -1;

                int adapterConnState = -1;
                if (BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED.equals(action)) {
                    adapterConnState = intent.getIntExtra(BluetoothAdapter.EXTRA_CONNECTION_STATE, -1);
                }

                boolean isConnectedAction = BluetoothDevice.ACTION_ACL_CONNECTED.equals(action) ||
                        (isProfileChange && profileState == BluetoothProfile.STATE_CONNECTED) ||
                        (adapterConnState == BluetoothProfile.STATE_CONNECTED) ||
                        "com.goc.bluetooth.connected".equals(action) ||
                        "com.android.ecar.bluetooth.connected".equals(action) ||
                        "com.syu.bt.connected".equals(action) ||
                        "action.hct.bt.connected".equals(action) ||
                        "com.ts.bt.connected".equals(action) ||
                        "com.microntek.bt.connected".equals(action) ||
                        "com.allwinner.bt.connected".equals(action) ||
                        "com.allwinner.bluetooth.connected".equals(action) ||
                        "cn.manstep.phonemirrorbox.bt.connected".equals(action) ||
                        "com.zjinnova.zlink.bt.connected".equals(action);

                boolean isDisconnectedAction = BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action) ||
                        BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED.equals(action) ||
                        (isProfileChange && profileState == BluetoothProfile.STATE_DISCONNECTED) ||
                        (adapterConnState == BluetoothProfile.STATE_DISCONNECTED) ||
                        "com.goc.bluetooth.disconnected".equals(action) ||
                        "com.syu.bt.disconnected".equals(action) ||
                        "action.hct.bt.disconnected".equals(action) ||
                        "com.ts.bt.disconnected".equals(action) ||
                        "com.microntek.bt.disconnected".equals(action) ||
                        "com.allwinner.bt.disconnected".equals(action) ||
                        "com.allwinner.bluetooth.disconnected".equals(action);

                if (isConnectedAction) {
                    BluetoothDevice device = extractDeviceFromIntent(intent);
                    if (device != null) {
                        handleDeviceConnected(device);
                    } else {
                        checkCurrentState();
                    }
                } else if (isDisconnectedAction) {
                    BluetoothDevice device = extractDeviceFromIntent(intent);
                    if (device != null && pendingConnectDevice != null &&
                            device.getAddress().equalsIgnoreCase(pendingConnectDevice.getAddress())) {
                        pollHandler.removeCallbacks(pendingTimeoutRunnable);
                        DeviceConnectionCallback cb = pendingCallback;
                        pendingCallback = null;
                        pendingConnectDevice = null;
                        if (cb != null) {
                            cb.onFailure(device, "Device disconnected.");
                        }
                    }

                    if (device != null && connectedDevice != null &&
                            device.getAddress().equalsIgnoreCase(connectedDevice.getAddress())) {
                        connectedDevice = null;
                    }

                    checkCurrentState();
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED");
        filter.addAction("android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED");
        filter.addAction("com.goc.bluetooth.connected");
        filter.addAction("com.goc.bluetooth.disconnected");
        filter.addAction("com.android.ecar.bluetooth.connected");
        filter.addAction("com.syu.bt.connected");
        filter.addAction("com.syu.bt.disconnected");
        filter.addAction("action.hct.bt.connected");
        filter.addAction("action.hct.bt.disconnected");
        filter.addAction("com.ts.bt.connected");
        filter.addAction("com.ts.bt.disconnected");
        filter.addAction("com.microntek.bt.connected");
        filter.addAction("com.microntek.bt.disconnected");
        filter.addAction("com.allwinner.bt.connected");
        filter.addAction("com.allwinner.bt.disconnected");
        filter.addAction("com.allwinner.bluetooth.connected");
        filter.addAction("cn.manstep.phonemirrorbox.bt.connected");
        filter.addAction("com.zjinnova.zlink.bt.connected");

        try {
            context.registerReceiver(receiver, filter);
        } catch (Exception ignored) {}

        checkCurrentState();
        startPeriodicCheck();
    }

    private void initProfileProxies() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) return;
        try {
            bluetoothAdapter.getProfileProxy(context, new BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    if (profile == BluetoothProfile.A2DP) {
                        a2dpProfile = (BluetoothA2dp) proxy;
                    }
                    checkCurrentState();
                }

                @Override
                public void onServiceDisconnected(int profile) {
                    if (profile == BluetoothProfile.A2DP) {
                        a2dpProfile = null;
                    }
                    checkCurrentState();
                }
            }, BluetoothProfile.A2DP);

            bluetoothAdapter.getProfileProxy(context, new BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    if (profile == BluetoothProfile.HEADSET) {
                        headsetProfile = (BluetoothHeadset) proxy;
                    }
                    checkCurrentState();
                }

                @Override
                public void onServiceDisconnected(int profile) {
                    if (profile == BluetoothProfile.HEADSET) {
                        headsetProfile = null;
                    }
                    checkCurrentState();
                }
            }, BluetoothProfile.HEADSET);
        } catch (Exception ignored) {}
    }

    @SuppressLint("MissingPermission")
    private BluetoothDevice extractDeviceFromIntent(Intent intent) {
        if (intent == null) return null;
        BluetoothDevice dev = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        if (dev != null) return dev;

        String[] macKeys = {"address", "mac", "addr", "deviceAddress"};
        for (String k : macKeys) {
            if (intent.hasExtra(k)) {
                String val = intent.getStringExtra(k);
                if (val != null && bluetoothAdapter != null) {
                    try {
                        return bluetoothAdapter.getRemoteDevice(val);
                    } catch (Exception ignored) {}
                }
            }
        }
        return null;
    }

    private void handleDeviceConnected(BluetoothDevice device) {
        if (device == null) return;

        if (pendingConnectDevice != null && pendingCallback != null &&
                device.getAddress().equalsIgnoreCase(pendingConnectDevice.getAddress())) {
            confirmConnected(device);
            return;
        }

        connectedDevice = device;
        notifyConnected(getDeviceDisplayName(device));
    }

    private void confirmConnected(BluetoothDevice device) {
        pollHandler.removeCallbacks(pendingTimeoutRunnable);
        connectedDevice = device;
        DeviceConnectionCallback cb = pendingCallback;
        pendingCallback = null;
        pendingConnectDevice = null;
        if (cb != null) {
            cb.onSuccess(device);
        }
        notifyConnected(getDeviceDisplayName(device));
    }

    @SuppressLint("MissingPermission")
    public boolean isDeviceCurrentlyConnected(BluetoothDevice device) {
        if (device == null) return false;

        // 1. Matched already verified connected device
        if (connectedDevice != null && device.getAddress().equalsIgnoreCase(connectedDevice.getAddress())) {
            return true;
        }

        // 2. Direct query on persistent A2DP profile proxy
        if (a2dpProfile != null) {
            try {
                List<BluetoothDevice> connected = a2dpProfile.getConnectedDevices();
                if (connected != null) {
                    for (BluetoothDevice d : connected) {
                        if (d != null && d.getAddress().equalsIgnoreCase(device.getAddress())) {
                            connectedDevice = d;
                            return true;
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        // 3. Direct query on persistent Headset profile proxy
        if (headsetProfile != null) {
            try {
                List<BluetoothDevice> connected = headsetProfile.getConnectedDevices();
                if (connected != null) {
                    for (BluetoothDevice d : connected) {
                        if (d != null && d.getAddress().equalsIgnoreCase(device.getAddress())) {
                            connectedDevice = d;
                            return true;
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        // 4. Reflection isConnected() on device
        try {
            Method method = device.getClass().getMethod("isConnected");
            Boolean isConn = (Boolean) method.invoke(device);
            if (isConn != null && isConn) {
                connectedDevice = device;
                return true;
            }
        } catch (Exception ignored) {}

        // 5. System BluetoothAdapter connection state
        boolean isAdapterConnected = false;
        try {
            if (bluetoothAdapter != null) {
                int connState = getAdapterConnectionState();
                int a2dpState = bluetoothAdapter.getProfileConnectionState(BluetoothProfile.A2DP);
                int hfpState = bluetoothAdapter.getProfileConnectionState(BluetoothProfile.HEADSET);
                if (connState == BluetoothProfile.STATE_CONNECTED ||
                    a2dpState == BluetoothProfile.STATE_CONNECTED ||
                    hfpState == BluetoothProfile.STATE_CONNECTED) {
                    isAdapterConnected = true;
                }
            }
        } catch (Exception ignored) {}

        // 6. AudioManager active Bluetooth audio link
        boolean isAudioA2dpOn = isAudioA2dpActive();

        // 7. Head unit vendor proprietary connection detection
        boolean isVendorConnected = checkVendorBtConnected();

        if (isAdapterConnected || isAudioA2dpOn || isVendorConnected) {
            String vendorMac = getVendorBtMac();
            String vendorName = getVendorBtName();

            if (vendorMac != null && vendorMac.equalsIgnoreCase(device.getAddress())) {
                connectedDevice = device;
                return true;
            }
            if (vendorName != null && device.getName() != null && vendorName.equalsIgnoreCase(device.getName())) {
                connectedDevice = device;
                return true;
            }
            if (pendingConnectDevice != null && pendingConnectDevice.getAddress().equalsIgnoreCase(device.getAddress())) {
                connectedDevice = device;
                return true;
            }
            if (bluetoothAdapter != null) {
                Set<BluetoothDevice> bonded = bluetoothAdapter.getBondedDevices();
                if (bonded != null && bonded.size() == 1 && bonded.contains(device)) {
                    connectedDevice = device;
                    return true;
                }
            }
            if (isAudioA2dpOn) {
                connectedDevice = device;
                return true;
            }
        }

        return false;
    }

    public boolean isAudioA2dpActive() {
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                return am.isBluetoothA2dpOn() || am.isBluetoothScoOn();
            }
        } catch (Exception ignored) {}
        return false;
    }

    public boolean checkVendorBtConnected() {
        String[] propKeys = {
                "sys.bt.connected", "persist.sys.bt.connected", "vendor.bt.connected",
                "ts.bt.state", "goc.bt.connected", "sys.fyt.bluetooth",
                "bt.connected.name", "bt.connected.mac"
        };
        for (String key : propKeys) {
            String val = getSystemProperty(key);
            if (val != null && !val.isEmpty() && !"0".equals(val) && !"false".equalsIgnoreCase(val)) {
                return true;
            }
        }

        try {
            ContentResolver cr = context.getContentResolver();
            int btState = Settings.System.getInt(cr, "bluetooth_state", 0);
            if (btState == 1 || btState == 2) return true;

            String devName = Settings.System.getString(cr, "bt_device_name");
            if (devName != null && !devName.isEmpty()) return true;

            String connName = Settings.System.getString(cr, "bt_connected_dev_name");
            if (connName != null && !connName.isEmpty()) return true;

            String connAddr = Settings.System.getString(cr, "current_connect_bt_addr");
            if (connAddr != null && !connAddr.isEmpty()) return true;
        } catch (Exception ignored) {}

        return false;
    }

    public String getVendorBtName() {
        String name = getSystemProperty("bt.connected.name");
        if (name != null && !name.isEmpty()) return name;

        try {
            ContentResolver cr = context.getContentResolver();
            String n = Settings.System.getString(cr, "bt_connected_dev_name");
            if (n != null && !n.isEmpty()) return n;

            n = Settings.System.getString(cr, "current_connect_bt_name");
            if (n != null && !n.isEmpty()) return n;

            n = Settings.System.getString(cr, "bt_device_name");
            if (n != null && !n.isEmpty()) return n;
        } catch (Exception ignored) {}

        return null;
    }

    public String getVendorBtMac() {
        String mac = getSystemProperty("bt.connected.mac");
        if (mac != null && !mac.isEmpty()) return mac;

        try {
            ContentResolver cr = context.getContentResolver();
            String m = Settings.System.getString(cr, "current_connect_bt_addr");
            if (m != null && !m.isEmpty()) return m;
        } catch (Exception ignored) {}

        return null;
    }

    private String getSystemProperty(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method getMethod = sp.getMethod("get", String.class, String.class);
            return (String) getMethod.invoke(null, key, "");
        } catch (Exception e) {
            return "";
        }
    }

    private int getAdapterConnectionState() {
        if (bluetoothAdapter == null) return BluetoothProfile.STATE_DISCONNECTED;
        try {
            Method m = bluetoothAdapter.getClass().getMethod("getConnectionState");
            Integer state = (Integer) m.invoke(bluetoothAdapter);
            return (state != null) ? state : BluetoothProfile.STATE_DISCONNECTED;
        } catch (Exception ignored) {
            return BluetoothProfile.STATE_DISCONNECTED;
        }
    }

    public BluetoothDevice getConnectedDevice() {
        return connectedDevice;
    }

    @SuppressLint("MissingPermission")
    public void signalConnect(final BluetoothDevice target) {
        if (target == null) return;
        broadcastHeadUnitConnect(target);
        try {
            if (target.getBondState() != BluetoothDevice.BOND_BONDED) {
                target.createBond();
            }
        } catch (Exception ignored) {}
        try {
            Method connectMethod = target.getClass().getMethod("connect");
            connectMethod.invoke(target);
        } catch (Exception ignored) {}
    }

    @SuppressLint("MissingPermission")
    public void connectToDevice(final BluetoothDevice target, final DeviceConnectionCallback callback) {
        if (target == null) {
            if (callback != null) callback.onFailure(null, "Invalid target device.");
            return;
        }

        // Fast-path: If target is ALREADY verified as connected, invoke success immediately!
        if (isDeviceCurrentlyConnected(target)) {
            connectedDevice = target;
            if (callback != null) callback.onSuccess(target);
            notifyConnected(getDeviceDisplayName(target));
            return;
        }

        signalConnect(target);

        if (callback == null) return;

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            callback.onFailure(target, "Bluetooth is disabled on this head unit.");
            return;
        }

        // Cancel previous attempt if any
        cancelPendingConnection();

        pendingConnectDevice = target;
        pendingCallback = callback;
        callback.onConnecting(target);

        // Schedule timeout at 9.0 seconds
        pollHandler.postDelayed(pendingTimeoutRunnable, 9000);

        // Active poll every 700ms to detect connection the exact moment it links
        long[] pollDelays = {700, 1400, 2200, 3200, 4400, 5800, 7200, 8500};
        for (long delay : pollDelays) {
            pollHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (pendingConnectDevice != null && pendingCallback != null && target.equals(pendingConnectDevice)) {
                        if (isDeviceCurrentlyConnected(target)) {
                            confirmConnected(target);
                        }
                    }
                }
            }, delay);
        }

        // Profile Proxy connect via reflection on A2DP
        if (a2dpProfile != null) {
            try {
                Method connectMethod = a2dpProfile.getClass().getMethod("connect", BluetoothDevice.class);
                connectMethod.invoke(a2dpProfile, target);
            } catch (Exception ignored) {}
        } else {
            try {
                bluetoothAdapter.getProfileProxy(context, new BluetoothProfile.ServiceListener() {
                    @Override
                    public void onServiceConnected(int profile, BluetoothProfile proxy) {
                        try {
                            Method connectMethod = proxy.getClass().getMethod("connect", BluetoothDevice.class);
                            connectMethod.invoke(proxy, target);
                        } catch (Exception ignored) {}
                    }

                    @Override
                    public void onServiceDisconnected(int profile) {}
                }, BluetoothProfile.A2DP);
            } catch (Exception ignored) {}
        }

        // Profile Proxy connect via reflection on Headset
        if (headsetProfile != null) {
            try {
                Method connectMethod = headsetProfile.getClass().getMethod("connect", BluetoothDevice.class);
                connectMethod.invoke(headsetProfile, target);
            } catch (Exception ignored) {}
        }

        // Head Unit Vendor Connect Broadcasts
        broadcastHeadUnitConnect(target);
    }

    public void cancelPendingConnection() {
        pollHandler.removeCallbacks(pendingTimeoutRunnable);
        if (pendingCallback != null && pendingConnectDevice != null) {
            DeviceConnectionCallback cb = pendingCallback;
            BluetoothDevice dev = pendingConnectDevice;
            pendingCallback = null;
            pendingConnectDevice = null;
            cb.onFailure(dev, "Connection cancelled.");
        }
    }

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
            } catch (Exception ignored) {}
        }
    }

    private void startPeriodicCheck() {
        pollHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isRunning) return;
                checkCurrentState();
                pollHandler.postDelayed(this, 3000);
            }
        }, 3000);
    }

    public void forceRefresh() {
        checkCurrentState();
    }

    @SuppressLint("MissingPermission")
    public void checkCurrentState() {
        if (bluetoothAdapter == null) {
            connectedDevice = null;
            notifyDisconnected("Bluetooth Not Supported");
            return;
        }

        if (!bluetoothAdapter.isEnabled()) {
            connectedDevice = null;
            notifyDisconnected("Bluetooth Off");
            return;
        }

        try {
            // Priority 1: Check cached A2DP proxy connected devices
            if (a2dpProfile != null) {
                List<BluetoothDevice> connected = a2dpProfile.getConnectedDevices();
                if (connected != null && !connected.isEmpty()) {
                    connectedDevice = connected.get(0);
                    notifyConnected(getDeviceDisplayName(connectedDevice));
                    return;
                }
            }

            // Priority 2: Check cached Headset proxy connected devices
            if (headsetProfile != null) {
                List<BluetoothDevice> connected = headsetProfile.getConnectedDevices();
                if (connected != null && !connected.isEmpty()) {
                    connectedDevice = connected.get(0);
                    notifyConnected(getDeviceDisplayName(connectedDevice));
                    return;
                }
            }

            // Priority 3: Check bonded devices with reflection isConnected()
            Set<BluetoothDevice> bonded = bluetoothAdapter.getBondedDevices();
            if (bonded != null && !bonded.isEmpty()) {
                for (BluetoothDevice dev : bonded) {
                    if (dev != null && isDeviceConnectedViaReflection(dev)) {
                        connectedDevice = dev;
                        notifyConnected(getDeviceDisplayName(dev));
                        return;
                    }
                }
            }

            // Priority 4: Check if adapter connection state, audio A2DP, or vendor system state is active
            boolean adapterConn = (getAdapterConnectionState() == BluetoothProfile.STATE_CONNECTED)
                    || (bluetoothAdapter.getProfileConnectionState(BluetoothProfile.A2DP) == BluetoothProfile.STATE_CONNECTED)
                    || (bluetoothAdapter.getProfileConnectionState(BluetoothProfile.HEADSET) == BluetoothProfile.STATE_CONNECTED);
            boolean audioA2dp = isAudioA2dpActive();
            boolean vendorBt = checkVendorBtConnected();

            if (adapterConn || audioA2dp || vendorBt) {
                String vendorName = getVendorBtName();
                String vendorMac = getVendorBtMac();

                if (bonded != null && !bonded.isEmpty()) {
                    for (BluetoothDevice dev : bonded) {
                        if (dev != null) {
                            if (vendorMac != null && vendorMac.equalsIgnoreCase(dev.getAddress())) {
                                connectedDevice = dev;
                                notifyConnected(getDeviceDisplayName(dev));
                                return;
                            }
                            if (vendorName != null && dev.getName() != null && vendorName.equalsIgnoreCase(dev.getName())) {
                                connectedDevice = dev;
                                notifyConnected(getDeviceDisplayName(dev));
                                return;
                            }
                        }
                    }
                    if (connectedDevice != null && bonded.contains(connectedDevice)) {
                        notifyConnected(getDeviceDisplayName(connectedDevice));
                        return;
                    }
                    if (bonded.size() == 1) {
                        connectedDevice = bonded.iterator().next();
                        notifyConnected(getDeviceDisplayName(connectedDevice));
                        return;
                    }
                }

                if (vendorName != null && !vendorName.isEmpty()) {
                    notifyConnected(vendorName);
                    return;
                }

                notifyConnected("Phone Connected");
                return;
            }

            // Genuinely disconnected
            connectedDevice = null;
            if (bonded != null && !bonded.isEmpty()) {
                notifyDisconnected("Ready to Connect (" + bonded.size() + " Paired)");
            } else {
                notifyDisconnected("Pair via SantafemR (PIN 0000)");
            }

        } catch (Exception e) {
            connectedDevice = null;
            notifyDisconnected("Standby");
        }
    }

    @SuppressLint("MissingPermission")
    private boolean isDeviceConnectedViaReflection(BluetoothDevice dev) {
        if (dev == null) return false;
        try {
            Method method = dev.getClass().getMethod("isConnected");
            Boolean res = (Boolean) method.invoke(dev);
            return res != null && res;
        } catch (Exception ignored) {
            return false;
        }
    }

    @SuppressLint("MissingPermission")
    public String getDeviceDisplayName(BluetoothDevice dev) {
        if (dev != null) {
            String name = dev.getName();
            if (name != null && !name.trim().isEmpty()) {
                return name;
            }
            if (dev.getAddress() != null) {
                return "Phone (" + dev.getAddress() + ")";
            }
        }
        return "Connected Phone";
    }

    private void notifyConnected(String deviceName) {
        if (listener != null) {
            listener.onBluetoothStateChanged(true, deviceName, "A2DP Audio & Calls Active");
        }
    }

    private void notifyDisconnected(String detail) {
        if (listener != null) {
            listener.onBluetoothStateChanged(false, "Not Connected", detail);
        }
    }

    public void stop() {
        isRunning = false;
        pollHandler.removeCallbacksAndMessages(null);
        cancelPendingConnection();
        if (receiver != null) {
            try {
                context.unregisterReceiver(receiver);
            } catch (Exception ignored) {}
        }
        try {
            if (bluetoothAdapter != null) {
                if (a2dpProfile != null) {
                    bluetoothAdapter.closeProfileProxy(BluetoothProfile.A2DP, a2dpProfile);
                    a2dpProfile = null;
                }
                if (headsetProfile != null) {
                    bluetoothAdapter.closeProfileProxy(BluetoothProfile.HEADSET, headsetProfile);
                    headsetProfile = null;
                }
            }
        } catch (Exception ignored) {}
    }
}
