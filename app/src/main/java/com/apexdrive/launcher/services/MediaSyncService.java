package com.apexdrive.launcher.services;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.service.notification.NotificationListenerService;
import android.view.KeyEvent;

import java.util.List;

public class MediaSyncService extends NotificationListenerService {

    public static final String ACTION_MEDIA_UPDATED = "com.apexdrive.launcher.MEDIA_UPDATED";
    public static final String EXTRA_TRACK_TITLE = "extra_track_title";
    public static final String EXTRA_TRACK_ARTIST = "extra_track_artist";
    public static final String EXTRA_IS_PLAYING = "extra_is_playing";

    private MediaSessionManager mediaSessionManager;
    private MediaController activeController;

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mediaSessionManager = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
            initMediaSessionListener();
        }
    }

    private void initMediaSessionListener() {
        if (mediaSessionManager == null) return;
        try {
            ComponentName componentName = new ComponentName(this, MediaSyncService.class);
            mediaSessionManager.addOnActiveSessionsChangedListener(
                    new MediaSessionManager.OnActiveSessionsChangedListener() {
                        @Override
                        public void onActiveSessionsChanged(List<MediaController> controllers) {
                            updateActiveController(controllers);
                        }
                    }, componentName);

            List<MediaController> initialControllers = mediaSessionManager.getActiveSessions(componentName);
            updateActiveController(initialControllers);
        } catch (SecurityException ignored) {
            // Notification access not yet granted
        }
    }

    private void updateActiveController(List<MediaController> controllers) {
        if (controllers != null && !controllers.isEmpty()) {
            activeController = controllers.get(0);
            activeController.registerCallback(new MediaController.Callback() {
                @Override
                public void onMetadataChanged(MediaMetadata metadata) {
                    broadcastMetadata(metadata, activeController.getPlaybackState());
                }

                @Override
                public void onPlaybackStateChanged(PlaybackState state) {
                    broadcastMetadata(activeController.getMetadata(), state);
                }
            });
            broadcastMetadata(activeController.getMetadata(), activeController.getPlaybackState());
        }
    }

    private void broadcastMetadata(MediaMetadata metadata, PlaybackState state) {
        String title = "No Media Playing";
        String artist = "Tap Play to resume Spotify or Radio";
        boolean isPlaying = false;

        if (metadata != null) {
            String t = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
            String a = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
            if (t != null && !t.isEmpty()) title = t;
            if (a != null && !a.isEmpty()) artist = a;
        }

        if (state != null) {
            isPlaying = state.getState() == PlaybackState.STATE_PLAYING;
        }

        Intent intent = new Intent(ACTION_MEDIA_UPDATED);
        intent.putExtra(EXTRA_TRACK_TITLE, title);
        intent.putExtra(EXTRA_TRACK_ARTIST, artist);
        intent.putExtra(EXTRA_IS_PLAYING, isPlaying);
        sendBroadcast(intent);
    }

    public static void sendMediaKey(Context context, int keyCode) {
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            long eventTime = System.currentTimeMillis();
            KeyEvent downEvent = new KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0);
            KeyEvent upEvent = new KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0);
            audioManager.dispatchMediaKeyEvent(downEvent);
            audioManager.dispatchMediaKeyEvent(upEvent);
        }
    }
}
