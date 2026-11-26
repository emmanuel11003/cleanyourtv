package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;

import java.util.List;

/**
 * Event-driven MediaSession observer backed by {@link GuardRevival} (no polling, zero extra permissions).
 * Surfaces live track title, artist, playback state, and Play/Pause/Next/Prev/OpenApp controls
 * to Home and Quick Settings.
 */
final class MediaNowPlaying {
    static final class State {
        final String packageName;
        final String title;
        final String artist;
        final boolean playing;

        State(String packageName, String title, String artist, boolean playing) {
            this.packageName = packageName;
            this.title = title;
            this.artist = artist;
            this.playing = playing;
        }

        String displayLine() {
            if (artist != null && !artist.isEmpty()) return title + " \u00B7 " + artist;
            return title;
        }
    }

    interface Listener {
        void onMediaChanged(State state);
    }

    private final Context appCtx;
    private final ComponentName listenerComponent;
    private final MediaSessionManager manager;
    private final Listener listener;
    private MediaController activeController;
    private boolean started;

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener =
            controllers -> LauncherExecutors.main().post(() -> attachBest(controllers));

    private final MediaController.Callback controllerCallback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            notifyListener();
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            notifyListener();
        }

        @Override
        public void onSessionDestroyed() {
            detachController();
            refresh();
        }
    };

    MediaNowPlaying(Context ctx, Listener listener) {
        this.appCtx = ctx.getApplicationContext();
        this.listenerComponent = new ComponentName(appCtx, GuardRevival.class);
        this.manager = (MediaSessionManager) appCtx.getSystemService(Context.MEDIA_SESSION_SERVICE);
        this.listener = listener;
    }

    void start() {
        if (started || manager == null) return;
        started = true;
        try {
            manager.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent);
            attachBest(manager.getActiveSessions(listenerComponent));
        } catch (RuntimeException ignored) {
            notifyListener();
        }
    }

    void stop() {
        if (!started) return;
        started = false;
        if (manager != null) {
            try {
                manager.removeOnActiveSessionsChangedListener(sessionsListener);
            } catch (Throwable ignored) {
            }
        }
        detachController();
    }

    void refresh() {
        if (manager == null) return;
        try {
            attachBest(manager.getActiveSessions(listenerComponent));
        } catch (RuntimeException ignored) {
            detachController();
            notifyListener();
        }
    }

    static State currentState(Context ctx) {
        if (ctx == null) return null;
        MediaSessionManager msm = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
        if (msm == null) return null;
        try {
            List<MediaController> list = msm.getActiveSessions(new ComponentName(ctx, GuardRevival.class));
            MediaController best = pickBest(list);
            return extractState(best);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    State state() {
        return extractState(activeController);
    }

    void togglePlayPause() {
        MediaController c = activeController;
        if (c == null) return;
        try {
            PlaybackState ps = c.getPlaybackState();
            MediaController.TransportControls tc = c.getTransportControls();
            if (tc == null) return;
            if (ps != null && ps.getState() == PlaybackState.STATE_PLAYING) {
                tc.pause();
            } else {
                tc.play();
            }
        } catch (RuntimeException ignored) {
        }
    }

    void next() {
        MediaController c = activeController;
        if (c == null) return;
        try {
            MediaController.TransportControls tc = c.getTransportControls();
            if (tc != null) tc.skipToNext();
        } catch (RuntimeException ignored) {
        }
    }

    void previous() {
        MediaController c = activeController;
        if (c == null) return;
        try {
            MediaController.TransportControls tc = c.getTransportControls();
            if (tc != null) tc.skipToPrevious();
        } catch (RuntimeException ignored) {
        }
    }

    Intent openAppIntent() {
        MediaController c = activeController;
        if (c == null) return null;
        try {
            String pkg = c.getPackageName();
            if (pkg == null || pkg.isEmpty()) return null;
            Intent i = appCtx.getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
            if (i == null) i = appCtx.getPackageManager().getLaunchIntentForPackage(pkg);
            return i;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private void attachBest(List<MediaController> controllers) {
        MediaController best = pickBest(controllers);
        if (best == activeController) {
            notifyListener();
            return;
        }
        detachController();
        activeController = best;
        if (activeController != null) {
            try {
                activeController.registerCallback(controllerCallback);
            } catch (Throwable ignored) {
            }
        }
        notifyListener();
    }

    private void detachController() {
        if (activeController != null) {
            try {
                activeController.unregisterCallback(controllerCallback);
            } catch (Throwable ignored) {
            }
            activeController = null;
        }
    }

    private void notifyListener() {
        if (listener != null) listener.onMediaChanged(extractState(activeController));
    }

    private static MediaController pickBest(List<MediaController> controllers) {
        if (controllers == null || controllers.isEmpty()) return null;
        MediaController fallback = null;
        for (MediaController c : controllers) {
            if (c == null) continue;
            PlaybackState ps = c.getPlaybackState();
            int st = ps != null ? ps.getState() : PlaybackState.STATE_NONE;
            if (st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_BUFFERING) {
                return c;
            }
            if (fallback == null && (st == PlaybackState.STATE_PAUSED || extractState(c) != null)) {
                fallback = c;
            }
        }
        return fallback;
    }

    private static State extractState(MediaController c) {
        if (c == null) return null;
        try {
            PlaybackState ps = c.getPlaybackState();
            int st = ps != null ? ps.getState() : PlaybackState.STATE_NONE;
            if (st == PlaybackState.STATE_NONE || st == PlaybackState.STATE_STOPPED || st == PlaybackState.STATE_ERROR) {
                return null;
            }
            MediaMetadata md = c.getMetadata();
            String title = md != null ? md.getString(MediaMetadata.METADATA_KEY_TITLE) : null;
            if (title == null || title.trim().isEmpty()) {
                title = md != null ? md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) : null;
            }
            String artist = md != null ? md.getString(MediaMetadata.METADATA_KEY_ARTIST) : null;
            if (artist == null || artist.trim().isEmpty()) {
                artist = md != null ? md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) : null;
            }
            String pkg = c.getPackageName();
            if (title == null || title.trim().isEmpty()) {
                title = pkg != null ? pkg : "";
            }
            return new State(pkg, title.trim(), artist == null ? "" : artist.trim(),
                    st == PlaybackState.STATE_PLAYING);
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
