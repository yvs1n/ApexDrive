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

    private static MediaController sActiveController = null;
    private static boolean sIsPlaying = false;
    private MediaSessionManager mediaSessionManager;

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
            // Notification listener permissions not yet accepted by user
        }
    }

    private void updateActiveController(List<MediaController> controllers) {
        if (controllers != null && !controllers.isEmpty()) {
            sActiveController = controllers.get(0);
            sActiveController.registerCallback(new MediaController.Callback() {
                @Override
                public void onMetadataChanged(MediaMetadata metadata) {
                    broadcastMetadata(metadata, sActiveController.getPlaybackState());
                }

                @Override
                public void onPlaybackStateChanged(PlaybackState state) {
                    broadcastMetadata(sActiveController.getMetadata(), state);
                }
            });
            broadcastMetadata(sActiveController.getMetadata(), sActiveController.getPlaybackState());
        }
    }

    private void broadcastMetadata(MediaMetadata metadata, PlaybackState state) {
        String title = "Track / Radio";
        String artist = "Tap Play to resume Spotify, Radio or CarPlay";
        boolean isPlaying = false;

        if (metadata != null) {
            String t = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
            String a = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
            if (t != null && !t.isEmpty()) title = t;
            if (a != null && !a.isEmpty()) artist = a;
        }

        if (state != null) {
            isPlaying = (state.getState() == PlaybackState.STATE_PLAYING);
        }

        sIsPlaying = isPlaying;

        Intent intent = new Intent(ACTION_MEDIA_UPDATED);
        intent.putExtra(EXTRA_TRACK_TITLE, title);
        intent.putExtra(EXTRA_TRACK_ARTIST, artist);
        intent.putExtra(EXTRA_IS_PLAYING, isPlaying);
        sendBroadcast(intent);
    }

    /**
     * Pauses whatever is currently playing, whether from CarPlay (Zlink), Spotify,
     * local USB audio, or head unit Radio.
     */
    public static void pauseAllMedia(Context context) {
        sIsPlaying = false;

        // 1. If active media session exists, dispatch pause directly
        if (sActiveController != null) {
            try {
                sActiveController.getTransportControls().pause();
            } catch (Exception ignored) {}
        }

        // 2. Dispatch hardware KEYCODE_MEDIA_PAUSE to AudioManager
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            long now = System.currentTimeMillis();
            audioManager.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE, 0));
            audioManager.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE, 0));
        }

        // 3. Send explicit automotive broadcasts (Zlink, Android Music, MCU Audio)
        try {
            Intent musicIntent = new Intent("com.android.music.musicservicecommand");
            musicIntent.putExtra("command", "pause");
            context.sendBroadcast(musicIntent);
        } catch (Exception ignored) {}

        try {
            Intent zlinkIntent = new Intent("com.zlink.command");
            zlinkIntent.putExtra("action", "pause");
            context.sendBroadcast(zlinkIntent);
        } catch (Exception ignored) {}

        // 4. Audio focus trick: transient focus forces any background CarPlay or stream to halt
        if (audioManager != null) {
            audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
            audioManager.abandonAudioFocus(null);
        }
    }

    /**
     * Toggles playback: pauses if currently playing, or resumes if paused.
     */
    public static void togglePlayPause(Context context) {
        if (sIsPlaying) {
            pauseAllMedia(context);
            return;
        }

        sIsPlaying = true;

        if (sActiveController != null) {
            try {
                sActiveController.getTransportControls().play();
                return;
            } catch (Exception ignored) {}
        }

        sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);

        try {
            Intent musicIntent = new Intent("com.android.music.musicservicecommand");
            musicIntent.putExtra("command", "togglepause");
            context.sendBroadcast(musicIntent);
        } catch (Exception ignored) {}
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

        try {
            Intent btnIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
            btnIntent.putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
            context.sendBroadcast(btnIntent);
        } catch (Exception ignored) {}
    }
}
