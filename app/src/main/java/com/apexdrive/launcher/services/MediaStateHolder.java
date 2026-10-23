package com.apexdrive.launcher.services;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-process holder for the current now-playing state.
 *
 * Replaces the app-wide MEDIA_UPDATED broadcast: nothing here leaves the process, so other
 * apps cannot read or spoof it, and a Bitmap (album art) can be handed over without the
 * 1 MB Binder limit. Listeners are always called on the main thread.
 *
 * Any field of {@link Snapshot} can be null: FM radio and vendor-broadcast-only sources have
 * no art, and there is no placeholder text. The UI decides what an empty state looks like.
 */
public final class MediaStateHolder {

    /** Longest edge of the stored album art, in pixels. */
    public static final int MAX_ART_SIZE = 512;

    public interface Listener {
        /** Called on the main thread. The snapshot is immutable; its art may be null. */
        void onMediaStateChanged(Snapshot snapshot);
    }

    public static final class Snapshot {
        public final String title;
        public final String artist;
        public final String album;
        public final boolean playing;
        /** Scaled to at most 512x512. Shared with listeners: do NOT recycle it. May be null. */
        public final Bitmap art;
        /** Package of the media session this came from, or null for vendor broadcasts. */
        public final String sourcePackage;

        Snapshot(String title, String artist, String album, boolean playing,
                 Bitmap art, String sourcePackage) {
            this.title = title;
            this.artist = artist;
            this.album = album;
            this.playing = playing;
            this.art = art;
            this.sourcePackage = sourcePackage;
        }

        public boolean hasTrack() {
            return title != null && !title.isEmpty();
        }
    }

    private static final MediaStateHolder INSTANCE = new MediaStateHolder();

    public static MediaStateHolder getInstance() {
        return INSTANCE;
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    private volatile Snapshot current = new Snapshot(null, null, null, false, null, null);
    private volatile boolean listenerConnected = false;

    private MediaStateHolder() {}

    public Snapshot getSnapshot() {
        return current;
    }

    /** True while the notification listener is bound, i.e. access is granted and working. */
    public boolean isListenerConnected() {
        return listenerConnected;
    }

    /** Adds a listener and immediately delivers the current state to it (main thread only). */
    public void addListener(Listener l) {
        if (l == null || listeners.contains(l)) return;
        listeners.add(l);
        l.onMediaStateChanged(current);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    // ---- Mutators, used by MediaSyncService ----

    void setListenerConnected(boolean connected) {
        listenerConnected = connected;
    }

    void publish(String title, String artist, String album, boolean playing,
                 Bitmap art, String sourcePackage) {
        final Snapshot s = new Snapshot(emptyToNull(title), emptyToNull(artist),
                emptyToNull(album), playing, art, sourcePackage);
        current = s;
        dispatch(s);
    }

    /** Updates only the play state. */
    void publishPlaying(boolean playing) {
        Snapshot c = current;
        if (c.playing == playing) return;
        publish(c.title, c.artist, c.album, playing, c.art, c.sourcePackage);
    }

    /** Updates only the art (used when art arrives later, from a URI). */
    void publishArt(Bitmap art) {
        Snapshot c = current;
        publish(c.title, c.artist, c.album, c.playing, art, c.sourcePackage);
    }

    void clear() {
        publish(null, null, null, false, null, null);
    }

    private void dispatch(final Snapshot s) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            for (Listener l : listeners) l.onMediaStateChanged(s);
        } else {
            mainHandler.post(() -> {
                for (Listener l : listeners) l.onMediaStateChanged(s);
            });
        }
    }

    private static String emptyToNull(String s) {
        return (s == null || s.trim().isEmpty()) ? null : s;
    }
}
