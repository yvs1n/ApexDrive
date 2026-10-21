package com.apexdrive.launcher.services;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.Settings;

import androidx.core.app.NotificationManagerCompat;

import java.util.List;
import java.util.Set;

/**
 * Helper for the "Notification access" grant that live media sync needs. Without it
 * MediaSessionManager.getActiveSessions() throws SecurityException and media stays silently off.
 * This class holds data and intents only; the Setup row that shows it is built in the UI.
 */
public final class NotificationAccess {

    // Literal because Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS exists only from API 22.
    private static final String ACTION_LISTENER_SETTINGS =
            "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS";

    private NotificationAccess() {}

    /** True when the user has granted this app notification access. */
    public static boolean isGranted(Context context) {
        try {
            Set<String> packages = NotificationManagerCompat.getEnabledListenerPackages(context);
            return packages.contains(context.getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    /** Intent for the system settings page, or null if this firmware has no such page. */
    public static Intent createSettingsIntent(Context context) {
        // Verify in the car: many head units strip this settings page.
        Intent intent = new Intent(ACTION_LISTENER_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PackageManager pm = context.getPackageManager();
        List<ResolveInfo> match = pm.queryIntentActivities(intent, 0);
        return (match != null && !match.isEmpty()) ? intent : null;
    }

    /** True if {@link #createSettingsIntent} would open a real page. */
    public static boolean isSettingsPageAvailable(Context context) {
        return createSettingsIntent(context) != null;
    }

    /**
     * Shell command a user can run from a PC when the settings page is missing.
     * Android 8.1+ only for the "cmd notification" form; the settings form works everywhere
     * but replaces any other listeners, so it is not offered here.
     */
    public static String adbGrantCommand(Context context) {
        ComponentName cn = new ComponentName(context, MediaSyncService.class);
        return "adb shell cmd notification allow_listener " + cn.flattenToString();
    }
}
