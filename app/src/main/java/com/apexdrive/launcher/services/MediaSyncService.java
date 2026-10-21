package com.apexdrive.launcher.services;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.SystemClock;
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
    private BroadcastReceiver musicBroadcastReceiver;

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mediaSessionManager = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
            initMediaSessionListener();
        }
        initBroadcastMetadataListener();
    }

    private void initMediaSessionListener() {
        if (mediaSessionManager == null) return;
        try {
            ComponentName componentName = new ComponentName(this, MediaSyncService.class);
            mediaSessionManager.addOnActiveSessionsChangedListener(
                    controllers -> updateActiveController(controllers), componentName);

            List<MediaController> initialControllers = mediaSessionManager.getActiveSessions(componentName);
            updateActiveController(initialControllers);
        } catch (SecurityException ignored) {
            // Notification listener permission not granted yet
        }
    }

    private void updateActiveController(List<MediaController> controllers) {
        if (controllers != null && !controllers.isEmpty()) {
            sActiveController = controllers.get(0);
            sActiveController.registerCallback(new MediaController.Callback() {
                @Override
                public void onMetadataChanged(MediaMetadata metadata) {
                    broadcastMetadata(metadata, (sActiveController != null) ? sActiveController.getPlaybackState() : null);
                }

                @Override
                public void onPlaybackStateChanged(PlaybackState state) {
                    broadcastMetadata((sActiveController != null) ? sActiveController.getMetadata() : null, state);
                }
            });
            broadcastMetadata(sActiveController.getMetadata(), sActiveController.getPlaybackState());
        }
    }

    private void initBroadcastMetadataListener() {
        musicBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null || intent.getAction() == null) return;

                String track = intent.getStringExtra("track");
                if (track == null) track = intent.getStringExtra("title");
                if (track == null) track = intent.getStringExtra("song");
                if (track == null) track = intent.getStringExtra("name");

                String artist = intent.getStringExtra("artist");
                if (artist == null) artist = intent.getStringExtra("singer");
                if (artist == null) artist = intent.getStringExtra("album");

                boolean playing = intent.getBooleanExtra("playing", sIsPlaying);
                if (intent.hasExtra("isPlaying")) {
                    playing = intent.getBooleanExtra("isPlaying", playing);
                } else if (intent.hasExtra("playstate")) {
                    playing = intent.getBooleanExtra("playstate", playing);
                }

                if (track != null && !track.trim().isEmpty()) {
                    sIsPlaying = playing;
                    Intent updateIntent = new Intent(ACTION_MEDIA_UPDATED);
                    updateIntent.putExtra(EXTRA_TRACK_TITLE, track);
                    updateIntent.putExtra(EXTRA_TRACK_ARTIST, (artist != null && !artist.trim().isEmpty()) ? artist : "Bluetooth / CarPlay Audio");
                    updateIntent.putExtra(EXTRA_IS_PLAYING, playing);
                    sendBroadcast(updateIntent);
                }
            }
        };

        IntentFilter filter = new IntentFilter();
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
            registerReceiver(musicBroadcastReceiver, filter);
        } catch (Exception ignored) {}
    }

    private void broadcastMetadata(MediaMetadata metadata, PlaybackState state) {
        String title = "Track / Radio";
        String artist = "Tap Play to resume Spotify, Radio or CarPlay";
        boolean isPlaying = sIsPlaying;

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

    /** Pauses playback. One path: the active session, else a single PAUSE key. */
    public static void pauseAllMedia(Context context) {
        MediaController controller = sActiveController;
        if (controller != null) {
            try {
                controller.getTransportControls().pause();
                return;
            } catch (Exception ignored) {}
        }
        dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_PAUSE);
    }

    /**
     * Toggles playback from the real state (PlaybackState), not from the last tap, and sends
     * exactly one command. Never PLAY followed by PLAY_PAUSE.
     */
    public static void togglePlayPause(Context context) {
        MediaController controller = sActiveController;
        if (controller != null) {
            try {
                PlaybackState state = controller.getPlaybackState();
                boolean playing = state != null && state.getState() == PlaybackState.STATE_PLAYING;
                if (playing) controller.getTransportControls().pause();
                else controller.getTransportControls().play();
                return;
            } catch (Exception ignored) {}
        }
        // No session (vendor or Bluetooth source): the toggle key is the only command that
        // works without knowing the state. Verify in the car how the unit's own player reacts.
        dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
    }

    /**
     * Sends one transport command: through the active session if there is one, otherwise a
     * single key down/up pair. Vendor-specific broadcasts are intentionally gone (P-09, P-76).
     */
    public static void sendMediaKey(Context context, int keyCode) {
        MediaController controller = sActiveController;
        if (controller != null) {
            try {
                MediaController.TransportControls tc = controller.getTransportControls();
                switch (keyCode) {
                    case KeyEvent.KEYCODE_MEDIA_NEXT: tc.skipToNext(); return;
                    case KeyEvent.KEYCODE_MEDIA_PREVIOUS: tc.skipToPrevious(); return;
                    case KeyEvent.KEYCODE_MEDIA_PLAY: tc.play(); return;
                    case KeyEvent.KEYCODE_MEDIA_PAUSE: tc.pause(); return;
                    case KeyEvent.KEYCODE_MEDIA_STOP: tc.stop(); return;
                    case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: togglePlayPause(context); return;
                    default: break;
                }
            } catch (Exception ignored) {}
        }
        dispatchMediaKey(context, keyCode);
    }

    private static void dispatchMediaKey(Context context, int keyCode) {
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) return;
        // KeyEvent expects uptimeMillis, not wall-clock time.
        long now = SystemClock.uptimeMillis();
        try {
            audioManager.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
            audioManager.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0));
        } catch (Exception ignored) {}
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (musicBroadcastReceiver != null) {
            try {
                unregisterReceiver(musicBroadcastReceiver);
            } catch (Exception ignored) {}
        }
    }
}
