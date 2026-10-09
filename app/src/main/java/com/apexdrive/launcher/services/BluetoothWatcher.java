package com.apexdrive.launcher.services;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

public class BluetoothWatcher {

    public interface BluetoothStateListener {
        void onBluetoothStateChanged(boolean isConnected, String deviceName);
    }

    private final Context context;
    private final BluetoothAdapter bluetoothAdapter;
    private BluetoothStateListener listener;
    private BroadcastReceiver receiver;

    public BluetoothWatcher(Context context) {
        this.context = context;
        this.bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
    }

    public void setListener(BluetoothStateListener listener) {
        this.listener = listener;
    }

    @SuppressLint("MissingPermission")
    public void start() {
        if (bluetoothAdapter == null) return;

        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                String action = intent.getAction();
                if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
                    BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    String name = (device != null && device.getName() != null) ? device.getName() : "SantafemR";
                    if (listener != null) listener.onBluetoothStateChanged(true, name);
                } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
                    if (listener != null) listener.onBluetoothStateChanged(false, "No Device");
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        context.registerReceiver(receiver, filter);

        // Initial check: if adapter is enabled and connected
        checkCurrentState();
    }

    @SuppressLint("MissingPermission")
    private void checkCurrentState() {
        if (bluetoothAdapter != null && bluetoothAdapter.isEnabled()) {
            bluetoothAdapter.getProfileProxy(context, new BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    if (profile == BluetoothProfile.A2DP || profile == BluetoothProfile.HEADSET) {
                        if (!proxy.getConnectedDevices().isEmpty()) {
                            BluetoothDevice dev = proxy.getConnectedDevices().get(0);
                            String name = dev.getName() != null ? dev.getName() : "SantafemR";
                            if (listener != null) listener.onBluetoothStateChanged(true, name);
                        } else {
                            if (listener != null) listener.onBluetoothStateChanged(false, "No Device");
                        }
                    }
                    bluetoothAdapter.closeProfileProxy(profile, proxy);
                }

                @Override
                public void onServiceDisconnected(int profile) {}
            }, BluetoothProfile.A2DP);
        } else {
            if (listener != null) listener.onBluetoothStateChanged(false, "No Device");
        }
    }

    public void stop() {
        if (receiver != null) {
            try {
                context.unregisterReceiver(receiver);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}
