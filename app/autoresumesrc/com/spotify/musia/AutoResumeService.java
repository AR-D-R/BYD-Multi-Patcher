package com.spotify.musia;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaMetadata;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

/**
 * Small BYD restart helper injected into patched Spotify.
 *
 * The OEM BYD/Vivid Spotify integration does not fire play() immediately. It waits for the
 * media browser and the restored media context first. This helper follows the same strategy:
 * retry the MediaBrowser connection, wait for Spotify metadata/session restoration, issue play,
 * and verify that playback actually starts before exiting.
 */
public final class AutoResumeService extends Service {
    private static final String TAG = "AIVI-AutoResume";
    private static final String ACTION_RESTORE = "byd.intent.action.RESTORE_PLAYBACK";
    private static final String CHANNEL_ID = "spotify_autoresume";
    private static final int NOTIFICATION_ID = 9138;

    private static final long HARD_TIMEOUT_MS = 15000L;
    private static final long CONNECT_RETRY_MS = 500L;
    private static final long RESTORE_WAIT_MS = 4500L;
    private static final long STATE_POLL_MS = 200L;
    private static final long RESTORED_CONTEXT_SETTLE_MS = 300L;
    private static final long PLAY_RETRY_MS = 750L;
    private static final int MAX_PLAY_ATTEMPTS = 8;

    // 9.1 first, then 8.9. Only one is present in a given Spotify build.
    private static final String[] MEDIA_BROWSER_SERVICES = new String[] {
            "com.spotify.mediabrowserservice.mediabrowserservice.SpotifyMediaBrowserService",
            "com.spotify.music.libs.mediabrowserservice.SpotifyMediaBrowserService"
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaBrowser browser;
    private MediaController controller;
    private boolean active;
    private int generation;
    private int browserAttemptToken;
    private int connectAttempts;
    private int playAttempts;
    private long startedAt;
    private long connectedAt;
    private long lastPlayAt;
    private long restoredContextAt;
    private String selectedServiceClass;
    private String lastSnapshot = "";

    private final Runnable hardTimeout = new Runnable() {
        @Override public void run() {
            if (!active) return;
            log("TIMEOUT after " + elapsed() + " ms");
            finishResume(false);
        }
    };

    private final Runnable statePoll = new Runnable() {
        @Override public void run() {
            pollRestoredSession();
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, "Spotify+ Auto Resume", NotificationManager.IMPORTANCE_LOW));
        }
        int icon = getApplicationInfo().icon;
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(icon)
                .setContentTitle("Resuming Spotify+")
                .setOngoing(true)
                .build();
        startForeground(NOTIFICATION_ID, notification);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        log("onStartCommand action=" + action);
        if (ACTION_RESTORE.equals(action)) {
            beginResume();
        } else {
            log("Ignoring unexpected action " + action);
            finishResume(false);
        }
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    @Override public void onDestroy() {
        active = false;
        generation++;
        handler.removeCallbacksAndMessages(null);
        safeDisconnect();
        super.onDestroy();
    }

    private void beginResume() {
        generation++;
        active = true;
        connectAttempts = 0;
        playAttempts = 0;
        startedAt = SystemClock.elapsedRealtime();
        connectedAt = 0L;
        lastPlayAt = 0L;
        restoredContextAt = 0L;
        controller = null;
        selectedServiceClass = findMediaBrowserService();
        lastSnapshot = "";
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(hardTimeout, HARD_TIMEOUT_MS);
        log("BEGIN package=" + getPackageName() + " mediaService=" + selectedServiceClass);
        if (selectedServiceClass == null) {
            log("No supported Spotify MediaBrowserService found in this build");
            finishResume(false);
            return;
        }
        connectBrowser(generation);
    }

    private String findMediaBrowserService() {
        // Class lookup is the strongest signal because the helper lives inside Spotify's own classloader.
        for (String cls : MEDIA_BROWSER_SERVICES) {
            try {
                Class.forName(cls, false, getClassLoader());
                return cls;
            } catch (Throwable ignored) {
            }
        }
        PackageManager pm = getPackageManager();
        for (String cls : MEDIA_BROWSER_SERVICES) {
            try {
                pm.getServiceInfo(new ComponentName(getPackageName(), cls), 0);
                return cls;
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        // Keep a fallback because some firmware/package-manager variants can hide service metadata.
        return MEDIA_BROWSER_SERVICES[0];
    }

    private void connectBrowser(final int gen) {
        if (!active || gen != generation || timedOut()) return;
        safeDisconnect();
        final int token = ++browserAttemptToken;
        connectAttempts++;
        ComponentName component = new ComponentName(getPackageName(), selectedServiceClass);
        log("CONNECT attempt=" + connectAttempts + " component=" + component.flattenToShortString());
        browser = new MediaBrowser(this, component, new MediaBrowser.ConnectionCallback() {
            @Override public void onConnected() {
                if (!active || gen != generation || token != browserAttemptToken) return;
                try {
                    MediaSession.Token sessionToken = browser.getSessionToken();
                    controller = new MediaController(AutoResumeService.this, sessionToken);
                    connectedAt = SystemClock.elapsedRealtime();
                    log("CONNECTED after " + elapsed() + " ms");
                    handler.removeCallbacks(statePoll);
                    handler.post(statePoll);
                } catch (Throwable t) {
                    log("onConnected setup failed: " + t);
                    scheduleReconnect(gen, "controller setup failed");
                }
            }

            @Override public void onConnectionSuspended() {
                if (!active || gen != generation || token != browserAttemptToken) return;
                log("CONNECTION_SUSPENDED after " + elapsed() + " ms");
                scheduleReconnect(gen, "suspended");
            }

            @Override public void onConnectionFailed() {
                if (!active || gen != generation || token != browserAttemptToken) return;
                log("CONNECTION_FAILED attempt=" + connectAttempts);
                // If package-manager detection selected the wrong generation, try the other known service once.
                String alternate = alternateService(selectedServiceClass);
                if (connectAttempts == 1 && alternate != null && (classExists(alternate) || serviceExists(alternate))) {
                    selectedServiceClass = alternate;
                    log("Switching MediaBrowserService candidate to " + alternate);
                }
                scheduleReconnect(gen, "failed");
            }
        }, (Bundle) null);
        try {
            browser.connect();
        } catch (Throwable t) {
            log("connect() threw: " + t);
            scheduleReconnect(gen, "exception");
        }
    }

    private void scheduleReconnect(final int gen, String reason) {
        if (!active || gen != generation || timedOut()) return;
        browserAttemptToken++; // invalidate callbacks from the browser we are about to disconnect
        handler.removeCallbacks(statePoll);
        controller = null;
        safeDisconnect();
        log("RETRY connection in " + CONNECT_RETRY_MS + " ms · " + reason);
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                connectBrowser(gen);
            }
        }, CONNECT_RETRY_MS);
    }

    private void pollRestoredSession() {
        if (!active || timedOut()) return;
        MediaController c = controller;
        if (c == null) {
            scheduleReconnect(generation, "controller missing");
            return;
        }

        MediaMetadata metadata = null;
        PlaybackState state = null;
        try { metadata = c.getMetadata(); } catch (Throwable t) { log("getMetadata failed: " + t); }
        try { state = c.getPlaybackState(); } catch (Throwable t) { log("getPlaybackState failed: " + t); }

        String uri = metadata == null ? null : metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_URI);
        String title = metadata == null ? null : metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        int stateCode = state == null ? -1 : state.getState();
        long position = state == null ? -1L : state.getPosition();
        long sinceConnected = connectedAt == 0L ? 0L : SystemClock.elapsedRealtime() - connectedAt;
        boolean restoredContext = !TextUtils.isEmpty(uri) || !TextUtils.isEmpty(title);
        long now = SystemClock.elapsedRealtime();
        if (restoredContext && restoredContextAt == 0L) {
            restoredContextAt = now;
            log("RESTORED_CONTEXT available after " + elapsed() + " ms");
        }

        String snapshot = "state=" + stateName(stateCode)
                + " position=" + position
                + " uri=" + safe(uri)
                + " title=" + safe(title);
        if (!snapshot.equals(lastSnapshot)) {
            lastSnapshot = snapshot;
            log("SESSION " + snapshot);
        }

        if (stateCode == PlaybackState.STATE_PLAYING) {
            log("SUCCESS playing after " + elapsed() + " ms · restoredPosition=" + position);
            // Give Spotify a short moment to settle before the helper disconnects its browser client.
            handler.postDelayed(new Runnable() {
                @Override public void run() { finishResume(true); }
            }, 350L);
            return;
        }

        // Match the OEM behaviour: first allow Spotify to restore its previous media URI/session.
        // If metadata never arrives, fall back after the OEM-like 4.5 s readiness window.
        boolean readyToPlay = (restoredContext && state != null && (position > 0L
                || now - restoredContextAt >= RESTORED_CONTEXT_SETTLE_MS))
                || sinceConnected >= RESTORE_WAIT_MS;
        if (readyToPlay && playAttempts < MAX_PLAY_ATTEMPTS
                && (lastPlayAt == 0L || now - lastPlayAt >= PLAY_RETRY_MS)) {
            playAttempts++;
            lastPlayAt = now;
            log("PLAY attempt=" + playAttempts + " restoredContext=" + restoredContext
                    + " positionBeforePlay=" + position);
            try {
                c.getTransportControls().play();
            } catch (Throwable t) {
                log("play() failed: " + t);
            }
        }

        if (active) handler.postDelayed(statePoll, STATE_POLL_MS);
    }

    private boolean classExists(String cls) {
        try {
            Class.forName(cls, false, getClassLoader());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean serviceExists(String cls) {
        try {
            getPackageManager().getServiceInfo(new ComponentName(getPackageName(), cls), 0);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String alternateService(String current) {
        if (MEDIA_BROWSER_SERVICES[0].equals(current)) return MEDIA_BROWSER_SERVICES[1];
        if (MEDIA_BROWSER_SERVICES[1].equals(current)) return MEDIA_BROWSER_SERVICES[0];
        return null;
    }

    private void finishResume(boolean success) {
        if (!active) {
            stopForeground(true);
            stopSelf();
            return;
        }
        active = false;
        generation++;
        browserAttemptToken++;
        handler.removeCallbacksAndMessages(null);
        safeDisconnect();
        controller = null;
        log("END success=" + success + " elapsed=" + elapsed() + " ms connectAttempts="
                + connectAttempts + " playAttempts=" + playAttempts);
        stopForeground(true);
        stopSelf();
    }

    private void safeDisconnect() {
        MediaBrowser b = browser;
        browser = null;
        if (b != null) {
            try { b.disconnect(); } catch (Throwable ignored) {}
        }
    }

    private boolean timedOut() {
        return SystemClock.elapsedRealtime() - startedAt >= HARD_TIMEOUT_MS;
    }

    private long elapsed() {
        return startedAt == 0L ? 0L : SystemClock.elapsedRealtime() - startedAt;
    }

    private static String safe(String value) {
        if (TextUtils.isEmpty(value)) return "-";
        return value.length() <= 120 ? value : value.substring(0, 120);
    }

    private static String stateName(int state) {
        switch (state) {
            case PlaybackState.STATE_NONE: return "NONE";
            case PlaybackState.STATE_STOPPED: return "STOPPED";
            case PlaybackState.STATE_PAUSED: return "PAUSED";
            case PlaybackState.STATE_PLAYING: return "PLAYING";
            case PlaybackState.STATE_FAST_FORWARDING: return "FAST_FORWARDING";
            case PlaybackState.STATE_REWINDING: return "REWINDING";
            case PlaybackState.STATE_BUFFERING: return "BUFFERING";
            case PlaybackState.STATE_ERROR: return "ERROR";
            case PlaybackState.STATE_CONNECTING: return "CONNECTING";
            case PlaybackState.STATE_SKIPPING_TO_PREVIOUS: return "SKIP_PREV";
            case PlaybackState.STATE_SKIPPING_TO_NEXT: return "SKIP_NEXT";
            case PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM: return "SKIP_QUEUE";
            default: return String.valueOf(state);
        }
    }

    private static void log(String message) {
        Log.i(TAG, message);
    }
}
