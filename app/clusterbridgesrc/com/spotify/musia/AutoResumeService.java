package com.spotify.musia;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Direct BYD instrument-cluster relay for cloned Spotify packages.
 *
 * Lives inside com.spotify.musia / com.spotify.musib, connects to the clone's own
 * MediaBrowserService and mirrors title, playback state and progress to
 * BYDAutoInstrumentDevice using the values recovered from BYD's factory Spotify wrapper.
 * Stock com.spotify.music is never modified or replaced.
 *
 * The source DEX is compiled as com.spotify.musia and retargeted by the patcher to the
 * selected clone package. The AUTOR marker is changed in-place when Auto Resume is disabled.
 */
public final class AutoResumeService extends Service {
    private static final String ACTION_RESTORE = "byd.intent.action.RESTORE_PLAYBACK";
    private static final String ACTION_START_CLUSTER = "com.bydspotifymanager.action.START_CLUSTER";
    private static final String AUTO_RESUME_CONFIG = "AIVI_CLUSTER_AUTOR=1";

    private static final String CHANNEL_ID = "spotify_byd_cluster";
    private static final int NOTIFICATION_ID = 9142;
    private static final long CONNECT_RETRY_MS = 800L;
    private static final long PROGRESS_TICK_MS = 1000L;
    private static final long RESTORE_WAIT_MS = 4500L;
    private static final long RESTORED_CONTEXT_SETTLE_MS = 300L;
    private static final long PLAY_RETRY_MS = 750L;
    private static final long RESUME_TIMEOUT_MS = 15000L;
    private static final int MAX_PLAY_ATTEMPTS = 8;
    private static final int BYD_MUSIC_SOURCE_SPOTIFY = 18;
    private static final int BYD_MUSIC_PLAY = 1;
    private static final int BYD_MUSIC_PAUSE = 2;
    private static final int BYD_MUSIC_STOP = 3;
    private static final String OEM_PACKAGE = "com.byd.data.collect";

    private static final String[] MEDIA_BROWSER_SERVICES = new String[] {
            "com.spotify.mediabrowserservice.mediabrowserservice.SpotifyMediaBrowserService",
            "com.spotify.music.libs.mediabrowserservice.SpotifyMediaBrowserService"
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaBrowser browser;
    private MediaController controller;
    private String selectedServiceClass;
    private int browserGeneration;
    private boolean running;

    private boolean resumeActive;
    private long resumeStartedAt;
    private long connectedAt;
    private long restoredContextAt;
    private long lastPlayAt;
    private int playAttempts;

    private Object bydDevice;
    private Class<?> bydDeviceClass;
    private boolean bydInitAttempted;
    private String lastTitle;
    private String lastObservedTitle;
    private int lastObservedState = Integer.MIN_VALUE;
    private int lastObservedProgressBucket = Integer.MIN_VALUE;
    private int lastBydState = Integer.MIN_VALUE;
    private int lastProgress = Integer.MIN_VALUE;
    private boolean sourceSent;

    private final MediaController.Callback controllerCallback = new MediaController.Callback() {
        @Override public void onMetadataChanged(MediaMetadata metadata) {
            syncToCluster("metadata");
        }

        @Override public void onPlaybackStateChanged(PlaybackState state) {
            syncToCluster("state");
        }

        @Override public void onSessionDestroyed() {
            trace("SESSION destroyed; reconnecting");
            controller = null;
            scheduleReconnect();
        }
    };

    private final Runnable reconnect = new Runnable() {
        @Override public void run() {
            if (running) connectBrowser();
        }
    };

    private final Runnable progressTick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            syncToCluster("tick");
            maybeResumePlayback();
            handler.postDelayed(this, PROGRESS_TICK_MS);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, "Spotify+ instrument cluster", NotificationManager.IMPORTANCE_LOW));
        }
        int icon = getApplicationInfo().icon;
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(icon)
                .setContentTitle("Spotify+ instrument cluster")
                .setOngoing(true)
                .build();
        startForeground(NOTIFICATION_ID, notification);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        trace("START action=" + action);
        if (ACTION_RESTORE.equals(action) || ACTION_START_CLUSTER.equals(action) || action == null) {
            beginBridge(ACTION_RESTORE.equals(action) && autoResumeEnabled());
        } else {
            // An explicit start from a firmware variant is still useful for cluster mirroring.
            beginBridge(false);
        }
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    @Override public void onDestroy() {
        running = false;
        resumeActive = false;
        handler.removeCallbacksAndMessages(null);
        unregisterController();
        disconnectBrowser();
        sendBydState(BYD_MUSIC_STOP);
        super.onDestroy();
    }

    private void beginBridge(boolean resumeRequested) {
        running = true;
        if (resumeRequested) {
            resumeActive = true;
            resumeStartedAt = SystemClock.elapsedRealtime();
            connectedAt = 0L;
            restoredContextAt = 0L;
            lastPlayAt = 0L;
            playAttempts = 0;
            trace("AUTO_RESUME armed");
        }
        selectedServiceClass = findMediaBrowserService();
        if (selectedServiceClass == null) {
            trace("No supported Spotify MediaBrowserService found");
            scheduleReconnect();
            return;
        }
        connectBrowser();
        handler.removeCallbacks(progressTick);
        handler.post(progressTick);
    }

    private void connectBrowser() {
        handler.removeCallbacks(reconnect);
        if (!running) return;
        if (browser != null && browser.isConnected() && controller != null) return;
        if (selectedServiceClass == null) selectedServiceClass = findMediaBrowserService();
        if (selectedServiceClass == null) {
            scheduleReconnect();
            return;
        }

        unregisterController();
        disconnectBrowser();
        final int generation = ++browserGeneration;
        final ComponentName component = new ComponentName(getPackageName(), selectedServiceClass);
        trace("MEDIA_CONNECT component=" + component.flattenToShortString());
        browser = new MediaBrowser(this, component, new MediaBrowser.ConnectionCallback() {
            @Override public void onConnected() {
                if (!running || generation != browserGeneration || browser == null) return;
                try {
                    MediaSession.Token token = browser.getSessionToken();
                    controller = new MediaController(AutoResumeService.this, token);
                    controller.registerCallback(controllerCallback, handler);
                    connectedAt = SystemClock.elapsedRealtime();
                    trace("MEDIA_CONNECTED");
                    syncToCluster("connected");
                    maybeResumePlayback();
                } catch (Throwable t) {
                    trace("MEDIA_SETUP_FAILED " + describe(t));
                    scheduleReconnect();
                }
            }

            @Override public void onConnectionSuspended() {
                if (generation != browserGeneration) return;
                trace("MEDIA_SUSPENDED");
                scheduleReconnect();
            }

            @Override public void onConnectionFailed() {
                if (generation != browserGeneration) return;
                trace("MEDIA_FAILED " + component.flattenToShortString());
                String alternate = alternateService(selectedServiceClass);
                if (alternate != null && (classExists(alternate) || serviceExists(alternate))) {
                    selectedServiceClass = alternate;
                    trace("Trying alternate media service " + alternate);
                }
                scheduleReconnect();
            }
        }, (Bundle) null);
        try {
            browser.connect();
        } catch (Throwable t) {
            trace("MEDIA_CONNECT_EXCEPTION " + describe(t));
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        browserGeneration++;
        unregisterController();
        disconnectBrowser();
        handler.removeCallbacks(reconnect);
        if (running) handler.postDelayed(reconnect, CONNECT_RETRY_MS);
    }

    private void unregisterController() {
        MediaController c = controller;
        controller = null;
        if (c != null) {
            try { c.unregisterCallback(controllerCallback); } catch (Throwable ignored) {}
        }
    }

    private void disconnectBrowser() {
        MediaBrowser b = browser;
        browser = null;
        if (b != null) {
            try { b.disconnect(); } catch (Throwable ignored) {}
        }
    }

    private void syncToCluster(String reason) {
        MediaController c = controller;
        if (c == null) return;
        MediaMetadata metadata = null;
        PlaybackState state = null;
        try { metadata = c.getMetadata(); } catch (Throwable t) { trace("metadata failed " + describe(t)); }
        try { state = c.getPlaybackState(); } catch (Throwable t) { trace("state failed " + describe(t)); }

        if (metadata != null) {
            String title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
            String artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
            if (!TextUtils.equals(title, lastObservedTitle)) {
                lastObservedTitle = title;
                lastObservedProgressBucket = Integer.MIN_VALUE;
                trace("MEDIA title='" + safe(title) + "' artist='" + safe(artist) + "' reason=" + reason);
            }
            if (!TextUtils.isEmpty(title) && !title.equals(lastTitle)) {
                if (ensureBydDevice()) {
                    sendBydSource();
                    Integer result = callInt("sendMusicName", new Class<?>[]{String.class}, title);
                    if (result != null) {
                        lastTitle = title;
                        trace("BYD title='" + safe(title) + "' result=" + result + " reason=" + reason);
                    }
                }
            }
        }

        if (state != null) {
            int androidState = state.getState();
            int bydState = mapState(androidState);
            if (androidState != lastObservedState) {
                lastObservedState = androidState;
                trace("MEDIA state=" + playbackStateName(androidState) + "(" + androidState + ") mappedBYD=" + bydState
                        + " positionMs=" + state.getPosition() + " speed=" + state.getPlaybackSpeed());
            }
            if (bydState != 0 && bydState != lastBydState) sendBydState(bydState);

            if (metadata != null) {
                long duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
                if (duration > 0L) {
                    long position = estimatedPosition(state);
                    int progress = (int) Math.max(0L, Math.min(100L, (position * 100L) / duration));
                    int progressBucket = progress / 10;
                    if (progressBucket != lastObservedProgressBucket) {
                        lastObservedProgressBucket = progressBucket;
                        trace("MEDIA progress=" + progress + "% positionMs=" + position + " durationMs=" + duration);
                    }
                    if (progress != lastProgress && ensureBydDevice()) {
                        Integer result = callInt("sendMusicPlaybackProgress", new Class<?>[]{int.class}, progress);
                        if (result != null) {
                            lastProgress = progress;
                            trace("BYD progress=" + progress + " result=" + result);
                        }
                    }
                }
            }
        }
    }

    private long estimatedPosition(PlaybackState state) {
        long position = Math.max(0L, state.getPosition());
        if (state.getState() == PlaybackState.STATE_PLAYING && state.getPlaybackSpeed() != 0f) {
            long updated = state.getLastPositionUpdateTime();
            if (updated > 0L) {
                long delta = Math.max(0L, SystemClock.elapsedRealtime() - updated);
                position += (long) (delta * state.getPlaybackSpeed());
            }
        }
        return Math.max(0L, position);
    }

    private void maybeResumePlayback() {
        if (!resumeActive) return;
        long now = SystemClock.elapsedRealtime();
        if (now - resumeStartedAt >= RESUME_TIMEOUT_MS) {
            trace("AUTO_RESUME timeout");
            resumeActive = false;
            return;
        }
        MediaController c = controller;
        if (c == null) return;

        MediaMetadata metadata = null;
        PlaybackState state = null;
        try { metadata = c.getMetadata(); } catch (Throwable ignored) {}
        try { state = c.getPlaybackState(); } catch (Throwable ignored) {}
        int stateCode = state == null ? -1 : state.getState();
        if (stateCode == PlaybackState.STATE_PLAYING) {
            trace("AUTO_RESUME success");
            resumeActive = false;
            return;
        }

        String uri = metadata == null ? null : metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_URI);
        String title = metadata == null ? null : metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        boolean restoredContext = !TextUtils.isEmpty(uri) || !TextUtils.isEmpty(title);
        if (restoredContext && restoredContextAt == 0L) restoredContextAt = now;
        long sinceConnected = connectedAt == 0L ? 0L : now - connectedAt;
        long position = state == null ? -1L : state.getPosition();
        boolean ready = (restoredContext && state != null && (position > 0L
                || now - restoredContextAt >= RESTORED_CONTEXT_SETTLE_MS))
                || sinceConnected >= RESTORE_WAIT_MS;
        if (ready && playAttempts < MAX_PLAY_ATTEMPTS
                && (lastPlayAt == 0L || now - lastPlayAt >= PLAY_RETRY_MS)) {
            playAttempts++;
            lastPlayAt = now;
            try {
                trace("AUTO_RESUME play attempt=" + playAttempts);
                c.getTransportControls().play();
            } catch (Throwable t) {
                trace("AUTO_RESUME play failed " + describe(t));
            }
        }
    }

    private boolean ensureBydDevice() {
        if (bydDevice != null && bydDeviceClass != null) return true;
        if (bydInitAttempted) return false;
        bydInitAttempted = true;
        try {
            bydDeviceClass = loadBydClass("android.hardware.bydauto.instrument.BYDAutoInstrumentDevice");
            Context compatibility = new BydInstrumentContext(getApplicationContext());
            Method getInstance = bydDeviceClass.getMethod("getInstance", Context.class);
            bydDevice = getInstance.invoke(null, compatibility);
            if (bydDevice == null) throw new IllegalStateException("BYD returned null instrument device");
            sourceSent = false;
            trace("BYD instrument API connected");
            return true;
        } catch (Throwable t) {
            bydDevice = null;
            bydDeviceClass = null;
            trace("BYD instrument API unavailable: " + describe(t));
            return false;
        }
    }

    private Class<?> loadBydClass(String name) throws Exception {
        try {
            return Class.forName(name, true, getClassLoader());
        } catch (ClassNotFoundException missing) {
            ApplicationInfo info = getPackageManager().getApplicationInfo(OEM_PACKAGE, 0);
            if ((info.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) == 0) {
                throw new SecurityException("BYD provider package is not a system app");
            }
            Context oem = createPackageContext(OEM_PACKAGE,
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
            return Class.forName(name, true, oem.getClassLoader());
        }
    }

    private void sendBydSource() {
        if (sourceSent || !ensureBydDevice()) return;
        Integer result = callInt("sendMusicSource", new Class<?>[]{int.class}, BYD_MUSIC_SOURCE_SPOTIFY);
        if (result != null) {
            sourceSent = true;
            trace("BYD source=18 result=" + result);
        }
    }

    private void sendBydState(int state) {
        if (state == lastBydState) return;
        if (!ensureBydDevice()) return;
        sendBydSource();
        Integer result = callInt("sendMusicState", new Class<?>[]{int.class}, state);
        if (result != null) {
            lastBydState = state;
            trace("BYD state=" + state + " result=" + result);
        }
    }

    private Integer callInt(String name, Class<?>[] types, Object... args) {
        if (bydDevice == null || bydDeviceClass == null) return null;
        try {
            Method method = bydDeviceClass.getMethod(name, types);
            Object raw = method.invoke(bydDevice, args);
            return raw instanceof Number ? ((Number) raw).intValue() : 0;
        } catch (Throwable t) {
            Throwable cause = unwrap(t);
            trace("BYD " + name + " failed: " + describe(cause));
            if (cause instanceof SecurityException) {
                // Keep the service alive for logs but do not hammer a permanently denied API.
                bydDevice = null;
                bydDeviceClass = null;
                bydInitAttempted = true;
            }
            return null;
        }
    }

    private static String playbackStateName(int state) {
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
            case PlaybackState.STATE_SKIPPING_TO_PREVIOUS: return "SKIPPING_PREVIOUS";
            case PlaybackState.STATE_SKIPPING_TO_NEXT: return "SKIPPING_NEXT";
            case PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM: return "SKIPPING_QUEUE_ITEM";
            default: return "UNKNOWN";
        }
    }

    private int mapState(int state) {
        switch (state) {
            case PlaybackState.STATE_PLAYING:
                return BYD_MUSIC_PLAY;
            case PlaybackState.STATE_PAUSED:
            case PlaybackState.STATE_BUFFERING:
            case PlaybackState.STATE_CONNECTING:
                return BYD_MUSIC_PAUSE;
            case PlaybackState.STATE_NONE:
            case PlaybackState.STATE_STOPPED:
            case PlaybackState.STATE_ERROR:
                return BYD_MUSIC_STOP;
            default:
                return 0;
        }
    }

    private String findMediaBrowserService() {
        for (String cls : MEDIA_BROWSER_SERVICES) if (classExists(cls)) return cls;
        for (String cls : MEDIA_BROWSER_SERVICES) if (serviceExists(cls)) return cls;
        return MEDIA_BROWSER_SERVICES[0];
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

    private static boolean autoResumeEnabled() {
        return AUTO_RESUME_CONFIG.endsWith("1");
    }

    private static Throwable unwrap(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof InvocationTargetException || cause instanceof ExceptionInInitializerError)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static String describe(Throwable error) {
        Throwable t = unwrap(error);
        String msg = t.getMessage();
        return t.getClass().getSimpleName() + (TextUtils.isEmpty(msg) ? "" : ": " + msg);
    }

    private static String safe(String value) {
        if (TextUtils.isEmpty(value)) return "-";
        return value.length() <= 120 ? value : value.substring(0, 120);
    }

    /** Production build: relay diagnostics are intentionally disabled. */
    private static void trace(String ignored) {
        // No Logcat or persistent file logging in the release helper.
    }

    /**
     * BYD SDK builds commonly perform permission checks on the Context passed to getInstance().
     * This only bypasses those client-side checks. Any service/Binder-side permission remains
     * enforced by the head unit, so firmware/service-side permission enforcement is still authoritative.
     */
    private static final class BydInstrumentContext extends ContextWrapper {
        BydInstrumentContext(Context base) { super(base); }

        private static boolean isInstrumentPermission(String permission) {
            return "android.permission.BYDAUTO_INSTRUMENT_COMMON".equals(permission)
                    || "android.permission.BYDAUTO_INSTRUMENT_GET".equals(permission)
                    || "android.permission.BYDAUTO_INSTRUMENT_SET".equals(permission);
        }

        @Override public int checkPermission(String permission, int pid, int uid) {
            return isInstrumentPermission(permission) ? PackageManager.PERMISSION_GRANTED
                    : super.checkPermission(permission, pid, uid);
        }

        @Override public int checkCallingPermission(String permission) {
            return isInstrumentPermission(permission) ? PackageManager.PERMISSION_GRANTED
                    : super.checkCallingPermission(permission);
        }

        @Override public int checkCallingOrSelfPermission(String permission) {
            return isInstrumentPermission(permission) ? PackageManager.PERMISSION_GRANTED
                    : super.checkCallingOrSelfPermission(permission);
        }

        @Override public int checkSelfPermission(String permission) {
            return isInstrumentPermission(permission) ? PackageManager.PERMISSION_GRANTED
                    : super.checkSelfPermission(permission);
        }

        @Override public void enforcePermission(String permission, int pid, int uid, String message) {
            if (!isInstrumentPermission(permission)) super.enforcePermission(permission, pid, uid, message);
        }

        @Override public void enforceCallingPermission(String permission, String message) {
            if (!isInstrumentPermission(permission)) super.enforceCallingPermission(permission, message);
        }

        @Override public void enforceCallingOrSelfPermission(String permission, String message) {
            if (!isInstrumentPermission(permission)) super.enforceCallingOrSelfPermission(permission, message);
        }
    }
}
