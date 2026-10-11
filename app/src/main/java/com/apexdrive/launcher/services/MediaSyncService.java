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

    public static void pauseAllMedia(Context context) {
        sIsPlaying = false;

        // 1. Active MediaController if available
        if (sActiveController != null) {
            try {
                sActiveController.getTransportControls().pause();
            } catch (Exception ignored) {}
        }

        // 2. Dispatch pause media key event to system audio
        sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PAUSE);

        // 3. Send explicit pause broadcasts to Zlink & DiPlay
        try {
            context.sendBroadcast(new Intent("com.zlink.command").putExtra("action", "pause"));
            context.sendBroadcast(new Intent("com.shihab.diplay.MEDIA_CONTROL").putExtra("command", "pause"));
            context.sendBroadcast(new Intent("com.android.music.musicservicecommand").putExtra("command", "pause"));
        } catch (Exception ignored) {}

        // 4. Send vendor automotive commands
        try {
            context.sendBroadcast(new Intent("com.goc.bluetooth.avrcp").putExtra("action", "pause"));
            context.sendBroadcast(new Intent("action.hct.media.key").putExtra("keycode", KeyEvent.KEYCODE_MEDIA_PAUSE));
            context.sendBroadcast(new Intent("com.ts.media.key").putExtra("keycode", KeyEvent.KEYCODE_MEDIA_PAUSE));
        } catch (Exception ignored) {}

        Intent intent = new Intent(ACTION_MEDIA_UPDATED);
        intent.putExtra(EXTRA_IS_PLAYING, false);
        context.sendBroadcast(intent);
    }

    public static void togglePlayPause(Context context) {
        if (sIsPlaying) {
            pauseAllMedia(context);
            return;
        }

        sIsPlaying = true;

        // 1. Active MediaController
        if (sActiveController != null) {
            try {
                sActiveController.getTransportControls().play();
            } catch (Exception ignored) {}
        }

        // 2. Dispatch explicit PLAY key (126) and PLAY_PAUSE toggle (85)
        sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY);
        sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);

        // 3. Send explicit play/unpause commands to Zlink & DiPlay
        try {
            context.sendBroadcast(new Intent("com.zlink.command").putExtra("action", "play"));
            context.sendBroadcast(new Intent("com.shihab.diplay.MEDIA_CONTROL").putExtra("command", "play"));
            context.sendBroadcast(new Intent("com.android.music.musicservicecommand").putExtra("command", "play"));
        } catch (Exception ignored) {}

        // 4. Send vendor automotive commands
        try {
            context.sendBroadcast(new Intent("com.goc.bluetooth.avrcp").putExtra("action", "play"));
            context.sendBroadcast(new Intent("action.hct.media.key").putExtra("keycode", KeyEvent.KEYCODE_MEDIA_PLAY));
            context.sendBroadcast(new Intent("com.ts.media.key").putExtra("keycode", KeyEvent.KEYCODE_MEDIA_PLAY));
        } catch (Exception ignored) {}

        Intent intent = new Intent(ACTION_MEDIA_UPDATED);
        intent.putExtra(EXTRA_IS_PLAYING, true);
        context.sendBroadcast(intent);
    }

    public static void sendMediaKey(Context context, int keyCode) {
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        long eventTime = System.currentTimeMillis();

        // 1. AudioManager hardware dispatch
        if (audioManager != null) {
            try {
                audioManager.dispatchMediaKeyEvent(new KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0));
                audioManager.dispatchMediaKeyEvent(new KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0));
            } catch (Exception ignored) {}
        }

        // 2. Standard ACTION_MEDIA_BUTTON broadcasts
        try {
            Intent down = new Intent(Intent.ACTION_MEDIA_BUTTON);
            down.putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0));
            context.sendBroadcast(down);

            Intent up = new Intent(Intent.ACTION_MEDIA_BUTTON);
            up.putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0));
            context.sendBroadcast(up);
        } catch (Exception ignored) {}

        // 3. String command mapping for CarPlay bridges (Zlink, DiPlay, GOC)
        String actionName = null;
        if (keyCode == KeyEvent.KEYCODE_MEDIA_NEXT) actionName = "next";
        else if (keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS) actionName = "prev";
        else if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY) actionName = "play";
        else if (keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE) actionName = "pause";
        else if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) actionName = "toggle";

        if (actionName != null) {
            try {
                context.sendBroadcast(new Intent("com.zlink.command").putExtra("action", actionName));
                context.sendBroadcast(new Intent("com.shihab.diplay.MEDIA_CONTROL").putExtra("command", actionName));
                context.sendBroadcast(new Intent("com.goc.bluetooth.avrcp").putExtra("action", actionName));
            } catch (Exception ignored) {}
        }

        // 4. Vendor Head Unit key broadcasts
        try {
            context.sendBroadcast(new Intent("action.hct.media.key").putExtra("keycode", keyCode));
            context.sendBroadcast(new Intent("com.ts.media.key").putExtra("keycode", keyCode));
            context.sendBroadcast(new Intent("com.microntek.media.key").putExtra("keycode", keyCode));
            context.sendBroadcast(new Intent("com.syu.music.key").putExtra("keycode", keyCode));
        } catch (Exception ignored) {}

        // 5. System Input Keyevent injection via permissive shell (emulates steering wheel hardware key)
        new Thread(() -> {
            try {
                Runtime.getRuntime().exec("input keyevent " + keyCode);
            } catch (Exception ignored) {}
        }).start();
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
