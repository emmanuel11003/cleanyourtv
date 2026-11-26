package com.gathin.wallpapersync;

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

/** Foreground service hosting the local Wi-Fi HTTP server while the user is syncing wallpapers. */
public class SyncServerService extends Service {
    static final String ACTION_STATE_CHANGED = "com.emmanuel.wallpapersync.STATE_CHANGED";
    static final String ACTION_STOP = "com.emmanuel.wallpapersync.STOP";
    private static final String CHANNEL_ID = "wallpaper_sync_server";
    private static final int NOTIF_ID = 4102;
    private static final int DEFAULT_PORT = 8765;
    private static final String PREFS = "wallpaper_sync";
    private static final String KEY_PIN = "pairing_pin";

    private final IBinder binder = new LocalBinder();
    private WallpaperHttpServer server;
    private String currentPin;
    private String lastError;

    final class LocalBinder extends Binder {
        SyncServerService service() {
            return SyncServerService.this;
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
        startServer();
        return START_STICKY;
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
        WallpaperHttpServer s = new WallpaperHttpServer(new BridgeHandler(), currentPin);
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
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent contentPi = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, SyncServerService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(getString(R.string.notif_body, url))
                .setContentIntent(contentPi)
                .addAction(new Notification.Action.Builder(null, getString(R.string.btn_stop_server), stopPi).build())
                .setOngoing(true)
                .build();
    }

    private void ensureChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW);
        nm.createNotificationChannel(ch);
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
        int num = new SecureRandom().nextInt(10_000);
        return String.format(Locale.ROOT, "%04d", num);
    }

    private final class BridgeHandler implements WallpaperHttpServer.Handler {
        @Override
        public String statusJson() {
            LauncherBridge.Status st = LauncherBridge.status(SyncServerService.this);
            return "{\"ok\":true,\"bridgeAvailable\":" + st.available
                    + ",\"homeSet\":" + st.homeSet
                    + ",\"screensaverSet\":" + st.screensaverSet + "}";
        }

        @Override
        public WallpaperHttpServer.Result applyWallpaper(String target, byte[] imageBytes) {
            if (!LauncherBridge.isSupportedImageHeader(imageBytes, imageBytes.length)) {
                return WallpaperHttpServer.Result.fail(415, "Unsupported image format (use JPG, PNG, or WebP)");
            }
            try {
                boolean ok = true;
                if ("home".equals(target) || "both".equals(target)) {
                    ok &= LauncherBridge.applyImage(SyncServerService.this, LauncherBridge.TARGET_HOME, imageBytes);
                }
                if ("screensaver".equals(target) || "both".equals(target)) {
                    ok &= LauncherBridge.applyImage(SyncServerService.this, LauncherBridge.TARGET_SCREENSAVER, imageBytes);
                }
                notifyUi();
                return ok ? WallpaperHttpServer.Result.success()
                        : WallpaperHttpServer.Result.fail(502, "Launcher bridge refused or is not installed");
            } catch (IOException e) {
                return WallpaperHttpServer.Result.fail(502, e.getMessage());
            }
        }

        @Override
        public WallpaperHttpServer.Result clearWallpaper(String target) {
            boolean ok = true;
            if ("home".equals(target) || "both".equals(target)) {
                ok &= LauncherBridge.clear(SyncServerService.this, LauncherBridge.TARGET_HOME);
            }
            if ("screensaver".equals(target) || "both".equals(target)) {
                ok &= LauncherBridge.clear(SyncServerService.this, LauncherBridge.TARGET_SCREENSAVER);
            }
            notifyUi();
            return ok ? WallpaperHttpServer.Result.success()
                    : WallpaperHttpServer.Result.fail(502, "Launcher bridge unavailable");
        }
    }
}
