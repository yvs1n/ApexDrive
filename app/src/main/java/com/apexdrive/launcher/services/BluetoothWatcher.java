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

import java.util.Set;

public class BluetoothWatcher {

    public interface BluetoothStateListener {
        void onBluetoothStateChanged(boolean isConnected, String deviceName, String details);
    }

    private final Context context;
    private final BluetoothAdapter bluetoothAdapter;
    private BluetoothStateListener listener;
    private BroadcastReceiver receiver;
    private final Handler pollHandler = new Handler(Looper.getMainLooper());
    private boolean isRunning = false;

    private static final String DEFAULT_CAR_NAME = "SantafemR";

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
                        "com.goc.bluetooth.connected".equals(action) ||
                        "com.android.ecar.bluetooth.connected".equals(action)) {
                    
                    BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    String name = getDeviceDisplayName(device);
                    notifyConnected(name);

                } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action) ||
                        "com.goc.bluetooth.disconnected".equals(action)) {
                    
                    checkCurrentState();
                } else if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action) ||
                        BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED.equals(action)) {
                    
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

        try {
            context.registerReceiver(receiver, filter);
        } catch (Exception e) {
            e.printStackTrace();
        }

        // Run initial check and start periodic heartbeat (every 3 seconds)
        checkCurrentState();
        startPeriodicCheck();
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
            notifyDisconnected("Bluetooth Not Supported");
            return;
        }

        if (!bluetoothAdapter.isEnabled()) {
            notifyDisconnected("Bluetooth Off");
            return;
        }

        try {
            // Check direct A2DP or Headset profile state
            int a2dpState = bluetoothAdapter.getProfileConnectionState(BluetoothProfile.A2DP);
            int headsetState = bluetoothAdapter.getProfileConnectionState(BluetoothProfile.HEADSET);

            if (a2dpState == BluetoothAdapter.STATE_CONNECTED || headsetState == BluetoothAdapter.STATE_CONNECTED) {
                // Find bonded device
                Set<BluetoothDevice> bonded = bluetoothAdapter.getBondedDevices();
                String connectedName = "Connected Phone";
                if (bonded != null && !bonded.isEmpty()) {
                    for (BluetoothDevice dev : bonded) {
                        if (dev != null && dev.getName() != null) {
                            connectedName = dev.getName();
                            break;
                        }
                    }
                }
                notifyConnected(connectedName);
                return;
            }

            // Also inspect bonded devices
            Set<BluetoothDevice> bonded = bluetoothAdapter.getBondedDevices();
            if (bonded != null && !bonded.isEmpty()) {
                BluetoothDevice dev = bonded.iterator().next();
                String name = dev.getName() != null ? dev.getName() : "Paired Device";
                notifyDisconnected("Ready to Connect · " + name);
            } else {
                notifyDisconnected("Pair via SantafemR (PIN 0000)");
            }

        } catch (Exception e) {
            notifyDisconnected("Standby");
        }
    }

    @SuppressLint("MissingPermission")
    private String getDeviceDisplayName(BluetoothDevice dev) {
        if (dev != null) {
            String name = dev.getName();
            if (name != null && !name.trim().isEmpty()) {
                return name;
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
        if (receiver != null) {
            try {
                context.unregisterReceiver(receiver);
            } catch (Exception ignored) {}
        }
    }
}
