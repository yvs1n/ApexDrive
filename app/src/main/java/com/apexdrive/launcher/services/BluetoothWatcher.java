package com.apexdrive.launcher.services;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

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

    private BluetoothDevice connectedDevice = null;
    private BluetoothDevice pendingConnectDevice = null;
    private DeviceConnectionCallback pendingCallback = null;

    private final Runnable pendingTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (pendingConnectDevice != null && pendingCallback != null) {
                // Check if device actually connected before declaring timeout failure
                if (isDeviceCurrentlyConnected(pendingConnectDevice)) {
                    confirmConnected(pendingConnectDevice);
                    return;
                }
                DeviceConnectionCallback cb = pendingCallback;
                BluetoothDevice dev = pendingConnectDevice;
                pendingCallback = null;
                pendingConnectDevice = null;
                cb.onFailure(dev, "Connection timed out. Ensure " + getDeviceDisplayName(dev) + " is in range with Bluetooth turned on.");
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

        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (intent == null || intent.getAction() == null) return;
                String action = intent.getAction();

                if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action) ||
                        "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED".equals(action) ||
                        "android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED".equals(action) ||
                        "com.goc.bluetooth.connected".equals(action) ||
                        "com.android.ecar.bluetooth.connected".equals(action) ||
                        "com.syu.bt.connected".equals(action) ||
                        "action.hct.bt.connected".equals(action)) {

                    BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (device == null && intent.hasExtra("address")) {
                        String addr = intent.getStringExtra("address");
                        if (addr != null && bluetoothAdapter != null) {
                            try {
                                device = bluetoothAdapter.getRemoteDevice(addr);
                            } catch (Exception ignored) {}
                        }
                    }

                    if (device != null) {
                        handleDeviceConnected(device);
                    } else {
                        checkCurrentState();
                    }

                } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action) ||
                        "com.goc.bluetooth.disconnected".equals(action) ||
                        "com.syu.bt.disconnected".equals(action) ||
                        BluetoothAdapter.ACTION_STATE_CHANGED.equals(action) ||
                        BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED.equals(action)) {

                    BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (device != null && pendingConnectDevice != null &&
                            device.getAddress().equalsIgnoreCase(pendingConnectDevice.getAddress())) {
                        pollHandler.removeCallbacks(pendingTimeoutRunnable);
                        DeviceConnectionCallback cb = pendingCallback;
                        pendingCallback = null;
                        pendingConnectDevice = null;
                        if (cb != null) {
                            cb.onFailure(device, "Device rejected Bluetooth link or disconnected.");
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

        try {
            context.registerReceiver(receiver, filter);
        } catch (Exception ignored) {}

        checkCurrentState();
        startPeriodicCheck();
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
        if (device == null || bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            return false;
        }

        if (connectedDevice != null && device.getAddress().equalsIgnoreCase(connectedDevice.getAddress())) {
            return true;
        }

        try {
            Method method = device.getClass().getMethod("isConnected");
            Boolean isConn = (Boolean) method.invoke(device);
            if (isConn != null && isConn) {
                connectedDevice = device;
                return true;
            }
        } catch (Exception ignored) {}

        return false;
    }

    public BluetoothDevice getConnectedDevice() {
        return connectedDevice;
    }

    @SuppressLint("MissingPermission")
    public void connectToDevice(final BluetoothDevice target, final DeviceConnectionCallback callback) {
        if (callback == null) return;

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            callback.onFailure(target, "Bluetooth is disabled on this head unit.");
            return;
        }

        if (target == null) {
            callback.onFailure(null, "Invalid target device.");
            return;
        }

        if (isDeviceCurrentlyConnected(target)) {
            connectedDevice = target;
            callback.onSuccess(target);
            notifyConnected(getDeviceDisplayName(target));
            return;
        }

        // Cancel previous attempt if any
        cancelPendingConnection();

        pendingConnectDevice = target;
        pendingCallback = callback;
        callback.onConnecting(target);

        // Schedule timeout at 7.5 seconds
        pollHandler.postDelayed(pendingTimeoutRunnable, 7500);

        // Check intermittently at 2s and 4s in case reflection detects connection before broadcast arrives
        pollHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (pendingConnectDevice != null && pendingCallback != null && target.equals(pendingConnectDevice)) {
                    if (isDeviceCurrentlyConnected(target)) {
                        confirmConnected(target);
                    }
                }
            }
        }, 2200);

        pollHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (pendingConnectDevice != null && pendingCallback != null && target.equals(pendingConnectDevice)) {
                    if (isDeviceCurrentlyConnected(target)) {
                        confirmConnected(target);
                    }
                }
            }
        }, 4400);

        // Attempt 1: A2DP Profile Proxy connect via reflection
        try {
            bluetoothAdapter.getProfileProxy(context, new BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    try {
                        Method connectMethod = proxy.getClass().getMethod("connect", BluetoothDevice.class);
                        connectMethod.invoke(proxy, target);
                    } catch (Exception ignored) {}
                    try {
                        bluetoothAdapter.closeProfileProxy(profile, proxy);
                    } catch (Exception ignored) {}
                }

                @Override
                public void onServiceDisconnected(int profile) {}
            }, BluetoothProfile.A2DP);
        } catch (Exception ignored) {}

        // Attempt 2: Headset Profile Proxy connect via reflection
        try {
            bluetoothAdapter.getProfileProxy(context, new BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    try {
                        Method connectMethod = proxy.getClass().getMethod("connect", BluetoothDevice.class);
                        connectMethod.invoke(proxy, target);
                    } catch (Exception ignored) {}
                    try {
                        bluetoothAdapter.closeProfileProxy(profile, proxy);
                    } catch (Exception ignored) {}
                }

                @Override
                public void onServiceDisconnected(int profile) {}
            }, BluetoothProfile.HEADSET);
        } catch (Exception ignored) {}

        // Attempt 3: Vendor Automotive Head Unit Broadcast Intents
        broadcastHeadUnitConnect(target);

        // Attempt 4: If device is not yet bonded, create bond
        try {
            if (target.getBondState() != BluetoothDevice.BOND_BONDED) {
                target.createBond();
            }
        } catch (Exception ignored) {}
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
                "com.goc.bluetooth.connect",
                "com.android.ecar.bluetooth.connect",
                "com.syu.bt.connect",
                "action.hct.bt.connect",
                "com.ts.bt.connect",
                "com.microntek.bt.connect"
        };

        for (String action : actions) {
            try {
                Intent intent = new Intent(action);
                intent.putExtra("address", address);
                intent.putExtra("mac", address);
                intent.putExtra("name", name);
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
            // First: check bonded devices via reflection isConnected()
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

            // Second: query A2DP profile proxy
            int a2dpState = bluetoothAdapter.getProfileConnectionState(BluetoothProfile.A2DP);
            int headsetState = bluetoothAdapter.getProfileConnectionState(BluetoothProfile.HEADSET);

            if (a2dpState == BluetoothAdapter.STATE_CONNECTED || headsetState == BluetoothAdapter.STATE_CONNECTED) {
                bluetoothAdapter.getProfileProxy(context, new BluetoothProfile.ServiceListener() {
                    @Override
                    public void onServiceConnected(int profile, BluetoothProfile proxy) {
                        try {
                            List<BluetoothDevice> connected = proxy.getConnectedDevices();
                            if (connected != null && !connected.isEmpty()) {
                                connectedDevice = connected.get(0);
                                notifyConnected(getDeviceDisplayName(connectedDevice));
                            }
                        } catch (Exception ignored) {}
                        try {
                            bluetoothAdapter.closeProfileProxy(profile, proxy);
                        } catch (Exception ignored) {}
                    }

                    @Override
                    public void onServiceDisconnected(int profile) {}
                }, BluetoothProfile.A2DP);

                if (connectedDevice != null) {
                    notifyConnected(getDeviceDisplayName(connectedDevice));
                    return;
                }
            }

            // If no profile or device is actually connected:
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
    }
}
