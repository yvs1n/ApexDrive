package com.apexdrive.launcher.services;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.view.KeyEvent;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MediaSyncService extends NotificationListenerService {

    /** @deprecated No longer broadcast (P-40). Observe {@link MediaStateHolder} instead. */
    @Deprecated
    public static final String ACTION_MEDIA_UPDATED = "com.apexdrive.launcher.MEDIA_UPDATED";
    public static final String EXTRA_TRACK_TITLE = "extra_track_title";
    public static final String EXTRA_TRACK_ARTIST = "extra_track_artist";
    public static final String EXTRA_IS_PLAYING = "extra_is_playing";

    // Cleared in onListenerDisconnected / onDestroy so controls never target a dead session.
    private static volatile MediaController sActiveController = null;
    private MediaSessionManager mediaSessionManager;
    private ComponentName listenerComponent;
    private MediaSessionManager.OnActiveSessionsChangedListener sessionsListener;
    private final List<ControllerCallback> callbacks = new ArrayList<>();
    private BroadcastReceiver musicBroadcastReceiver;

    private final MediaStateHolder holder = MediaStateHolder.getInstance();
    private final ExecutorService artExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private int artRequest = 0;
    private String lastArtId = null;
    private Bitmap lastArt = null;

    /** One callback per controller, so an event can be matched to the session it came from. */
    private final class ControllerCallback extends MediaController.Callback {
        final MediaController controller;

        ControllerCallback(MediaController controller) {
            this.controller = controller;
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            if (controller == sActiveController) {
                publishFromController(controller);
            }
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            if (state != null && state.getState() == PlaybackState.STATE_PLAYING
                    && controller != sActiveController) {
                // A different session started playing: follow it.
                sActiveController = controller;
            }
            if (controller == sActiveController) {
                publishFromController(controller);
            }
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mediaSessionManager = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
        listenerComponent = new ComponentName(this, MediaSyncService.class);
        initBroadcastMetadataListener();
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        holder.setListenerConnected(true);
        registerSessionListener();
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        holder.setListenerConnected(false);
        unregisterSessionListener();
        clearSessionState();
        // Ask the system to bind again (API 24+). Does nothing if access was revoked.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                requestRebind(listenerComponent);
            } catch (Exception ignored) {}
        }
    }

    private void registerSessionListener() {
        if (mediaSessionManager == null) return;
        unregisterSessionListener();
        try {
            sessionsListener = this::updateControllers;
            mediaSessionManager.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent);
            updateControllers(mediaSessionManager.getActiveSessions(listenerComponent));
        } catch (SecurityException e) {
            // Notification access not granted (yet).
            sessionsListener = null;
        }
    }

    private void unregisterSessionListener() {
        if (mediaSessionManager != null && sessionsListener != null) {
            try {
                mediaSessionManager.removeOnActiveSessionsChangedListener(sessionsListener);
            } catch (Exception ignored) {}
        }
        sessionsListener = null;
        clearCallbacks();
        sActiveController = null;
    }

    private void clearCallbacks() {
        for (ControllerCallback cb : callbacks) {
            try {
                cb.controller.unregisterCallback(cb);
            } catch (Exception ignored) {}
        }
        callbacks.clear();
    }

    private void updateControllers(List<MediaController> controllers) {
        MediaController previous = sActiveController;
        clearCallbacks();

        if (controllers == null || controllers.isEmpty()) {
            sActiveController = null;
            clearSessionState();
            return;
        }

        // Prefer the session that is playing, then the one we already followed, then the first.
        MediaController chosen = null;
        for (MediaController c : controllers) {
            PlaybackState s = c.getPlaybackState();
            if (s != null && s.getState() == PlaybackState.STATE_PLAYING) {
                chosen = c;
                break;
            }
        }
        if (chosen == null && previous != null) {
            for (MediaController c : controllers) {
                if (c.getSessionToken().equals(previous.getSessionToken())) {
                    chosen = c;
                    break;
                }
            }
        }
        if (chosen == null) chosen = controllers.get(0);
        sActiveController = chosen;

        for (MediaController c : controllers) {
            ControllerCallback cb = new ControllerCallback(c);
            c.registerCallback(cb);
            callbacks.add(cb);
        }
        publishFromController(chosen);
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

                boolean playing = intent.getBooleanExtra("playing", holder.getSnapshot().playing);
                if (intent.hasExtra("isPlaying")) {
                    playing = intent.getBooleanExtra("isPlaying", playing);
                } else if (intent.hasExtra("playstate")) {
                    playing = intent.getBooleanExtra("playstate", playing);
                }

                // A real media session wins over vendor broadcasts while it is playing.
                if (isSessionPlaying()) return;
                if (track != null && !track.trim().isEmpty()) {
                    // No invented artist text and no art: vendor sources only give strings.
                    holder.publish(track, artist, null, playing, null, null);
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

    private boolean isSessionPlaying() {
        MediaController c = sActiveController;
        if (c == null) return false;
        PlaybackState s = c.getPlaybackState();
        return s != null && s.getState() == PlaybackState.STATE_PLAYING;
    }

    /** Drops state that came from a media session, but leaves vendor-broadcast state alone. */
    private void clearSessionState() {
        artRequest++;
        lastArtId = null;
        lastArt = null;
        if (holder.getSnapshot().sourcePackage != null) holder.clear();
    }

    /**
     * Publishes everything from the one controller we follow. Reading title, artist and play
     * state from the same controller each time means a state callback can never overwrite real
     * metadata with a placeholder (P-12), and nothing is invented when a field is missing.
     */
    private void publishFromController(MediaController controller) {
        MediaMetadata md = controller.getMetadata();
        PlaybackState st = controller.getPlaybackState();
        boolean playing = st != null && st.getState() == PlaybackState.STATE_PLAYING;

        String title = null, artist = null, album = null, artUri = null;
        Bitmap art = null;
        if (md != null) {
            title = md.getString(MediaMetadata.METADATA_KEY_TITLE);
            artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
            if (artist == null) artist = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
            album = md.getString(MediaMetadata.METADATA_KEY_ALBUM);

            art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            artUri = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI);
            if (artUri == null) artUri = md.getString(MediaMetadata.METADATA_KEY_ART_URI);
            if (artUri == null) artUri = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI);
        }

        // Keep art across play/pause callbacks for the same track instead of decoding again.
        String artId = title + "|" + artist + "|" + album + "|" + artUri
                + "|" + controller.getPackageName();
        if (art != null) {
            art = scaleDown(art);
            lastArtId = artId;
            lastArt = art;
        } else if (artId.equals(lastArtId)) {
            art = lastArt;
        } else {
            lastArtId = artId;
            lastArt = null;
            artRequest++;
            if (artUri != null) loadArtFromUri(artUri, artRequest);
        }
        holder.publish(title, artist, album, playing, art, controller.getPackageName());
    }

    private void loadArtFromUri(final String uriString, final int request) {
        final Uri uri = Uri.parse(uriString);
        String scheme = uri.getScheme();
        // Local sources only. No network fetch from the dashboard (privacy, no data use).
        if (!"content".equals(scheme) && !"file".equals(scheme)
                && !"android.resource".equals(scheme)) return;
        try {
            artExecutor.execute(() -> {
                final Bitmap bmp = decodeScaled(uri);
                if (bmp == null) return;
                mainHandler.post(() -> {
                    if (request == artRequest) {
                        lastArt = bmp;
                        holder.publishArt(bmp);
                    }
                });
            });
        } catch (Exception ignored) {
            // Executor already shut down.
        }
    }

    private Bitmap decodeScaled(Uri uri) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            InputStream in = getContentResolver().openInputStream(uri);
            if (in == null) return null;
            try {
                BitmapFactory.decodeStream(in, null, bounds);
            } finally {
                in.close();
            }
            int sample = 1;
            int longest = Math.max(bounds.outWidth, bounds.outHeight);
            while (longest / (sample * 2) >= MediaStateHolder.MAX_ART_SIZE) sample *= 2;

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            in = getContentResolver().openInputStream(uri);
            if (in == null) return null;
            try {
                Bitmap decoded = BitmapFactory.decodeStream(in, null, opts);
                return decoded == null ? null : scaleDown(decoded);
            } finally {
                in.close();
            }
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    /**
     * Limits art to 512x512. The old bitmap is deliberately NOT recycled: the UI may still be
     * drawing it, and drawing a recycled bitmap crashes. The GC reclaims it.
     */
    private static Bitmap scaleDown(Bitmap src) {
        int max = MediaStateHolder.MAX_ART_SIZE;
        int w = src.getWidth(), h = src.getHeight();
        if (w <= max && h <= max) return src;
        float f = (float) max / Math.max(w, h);
        try {
            return Bitmap.createScaledBitmap(src, Math.max(1, Math.round(w * f)),
                    Math.max(1, Math.round(h * f)), true);
        } catch (OutOfMemoryError e) {
            return src;
        }
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
        holder.setListenerConnected(false);
        unregisterSessionListener();
        clearSessionState();
        artExecutor.shutdownNow();
        if (musicBroadcastReceiver != null) {
            try {
                unregisterReceiver(musicBroadcastReceiver);
            } catch (Exception ignored) {}
        }
    }
}
