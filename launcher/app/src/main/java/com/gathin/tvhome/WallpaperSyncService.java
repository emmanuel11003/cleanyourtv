package com.gathin.tvhome;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.IBinder;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * On-demand foreground service hosting the built-in local Wi-Fi Wallpaper & Backup Sync server.
 * Consumes 0% CPU and opens no port when stopped.
 */
public final class WallpaperSyncService extends Service {
    static final String ACTION_STATE_CHANGED = "com.gathin.tvhome.SYNC_STATE_CHANGED";
    static final String ACTION_STOP = "com.gathin.tvhome.SYNC_STOP";
    private static final String CHANNEL_ID = "tvhome_wifi_sync";
    private static final int NOTIF_ID = 4102;
    private static final int DEFAULT_PORT = 8765;
    private static final String PREFS = "wallpaper_sync";
    private static final String KEY_PIN = "pairing_pin";

    private final IBinder binder = new LocalBinder();
    private WallpaperHttpServer server;
    private String currentPin;
    private String lastError;

    final class LocalBinder extends Binder {
        WallpaperSyncService service() {
            return WallpaperSyncService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        currentPin = loadOrCreatePin(this);
        ensureChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopServer();
            stopSelf();
            return START_NOT_STICKY;
        }
        // Satisfy Android 8+ startForegroundService() contract immediately before binding socket.
        try {
            startForeground(NOTIF_ID, buildNotification(getString(R.string.sync_running)));
        } catch (Throwable ignored) {
        }
        startServer();
        if (!isRunning()) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        stopServer();
        super.onDestroy();
    }

    synchronized void startServer() {
        if (server != null && server.isRunning()) return;
        lastError = null;
        WallpaperHttpServer s = new WallpaperHttpServer(new DirectHandler(), currentPin);
        try {
            int port = s.start(DEFAULT_PORT);
            server = s;
            String ip = NetworkAddress.localIpv4(this);
            String url = ip != null ? "http://" + ip + ":" + port : "port " + port;
            startForeground(NOTIF_ID, buildNotification(url));
        } catch (IOException e) {
            lastError = e.getMessage();
            server = null;
        }
        notifyUi();
    }

    synchronized void stopServer() {
        if (server != null) {
            server.stop();
            server = null;
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        notifyUi();
    }

    synchronized boolean isRunning() {
        return server != null && server.isRunning();
    }

    synchronized int getPort() {
        return server != null ? server.getPort() : DEFAULT_PORT;
    }

    synchronized String getPin() {
        return currentPin;
    }

    synchronized String rotatePin() {
        currentPin = generatePin();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_PIN, currentPin).apply();
        if (server != null) server.setPin(currentPin);
        notifyUi();
        return currentPin;
    }

    synchronized String getLastError() {
        return lastError;
    }

    private void notifyUi() {
        sendBroadcast(new Intent(ACTION_STATE_CHANGED).setPackage(getPackageName()));
    }

    private Notification buildNotification(String url) {
        Intent openIntent = new Intent(this, WallpaperSyncActivity.class);
        PendingIntent contentPi = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, WallpaperSyncService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_sync)
                .setContentTitle(getString(R.string.wallpaper_sync_title))
                .setContentText(url)
                .setContentIntent(contentPi)
                .addAction(new Notification.Action.Builder(null, getString(R.string.sync_stop), stopPi).build())
                .setOngoing(true)
                .build();
    }

    private void ensureChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, getString(R.string.wallpaper_sync_title), NotificationManager.IMPORTANCE_LOW));
    }

    static String loadOrCreatePin(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, MODE_PRIVATE);
        String existing = prefs.getString(KEY_PIN, "");
        if (existing.length() == 4) return existing;
        String pin = generatePin();
        prefs.edit().putString(KEY_PIN, pin).apply();
        return pin;
    }

    private static String generatePin() {
        return String.format(Locale.ROOT, "%04d", new SecureRandom().nextInt(10_000));
    }

    private final class DirectHandler implements WallpaperHttpServer.Handler {
        @Override
        public String statusJson() {
            boolean[] st = WallpaperBridgeProvider.directStatus(WallpaperSyncService.this);
            return "{\"ok\":true,\"bridgeAvailable\":true,\"homeSet\":" + st[0] + ",\"screensaverSet\":" + st[1] + "}";
        }

        @Override
        public WallpaperHttpServer.Result applyWallpaper(String target, byte[] imageBytes) {
            if (!WallpaperHttpServer.isSupportedImageHeader(imageBytes, imageBytes.length)) {
                return WallpaperHttpServer.Result.fail(415, "Unsupported image format (use JPG, PNG, or WebP)");
            }
            boolean ok = true;
            if ("home".equals(target) || "both".equals(target)) {
                ok &= WallpaperBridgeProvider.directApply(WallpaperSyncService.this, "home", imageBytes);
            }
            if ("screensaver".equals(target) || "both".equals(target)) {
                ok &= WallpaperBridgeProvider.directApply(WallpaperSyncService.this, "screensaver", imageBytes);
            }
            notifyUi();
            return ok ? WallpaperHttpServer.Result.success()
                    : WallpaperHttpServer.Result.fail(500, "Could not save wallpaper");
        }

        @Override
        public WallpaperHttpServer.Result clearWallpaper(String target) {
            boolean ok = true;
            if ("home".equals(target) || "both".equals(target)) {
                ok &= WallpaperBridgeProvider.directClear(WallpaperSyncService.this, "home");
            }
            if ("screensaver".equals(target) || "both".equals(target)) {
                ok &= WallpaperBridgeProvider.directClear(WallpaperSyncService.this, "screensaver");
            }
            notifyUi();
            return ok ? WallpaperHttpServer.Result.success()
                    : WallpaperHttpServer.Result.fail(500, "Could not clear wallpaper");
        }

        @Override
        public String exportBackupJson() {
            try {
                return SettingsBackup.exportJson(WallpaperSyncService.this);
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        public WallpaperHttpServer.Result importBackupJson(String json) {
            SettingsBackup.Summary s = SettingsBackup.importJson(WallpaperSyncService.this, json);
            if (s.ok) {
                WallpaperBridgeProvider.notifyLauncher(WallpaperSyncService.this);
                notifyUi();
                return WallpaperHttpServer.Result.success();
            }
            return WallpaperHttpServer.Result.fail(400, s.error);
        }
    }
}
