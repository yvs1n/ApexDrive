package com.apexdrive.launcher.services;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "ApexDriveBoot";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null && Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            // Launcher lifecycle is natively managed by Android's WindowManager and ActivityManager
            // based on the user's HOME settings. We intentionally do NOT force startActivity() here
            // to prevent stealing screen focus from boot apps or causing duplicate activity loads.
            Log.d(TAG, "BOOT_COMPLETED received. Keeping foreground intact.");
        }
    }
}
