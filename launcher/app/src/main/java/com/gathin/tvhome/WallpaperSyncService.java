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
    private static volatile WallpaperSyncService activeInstance;
    private WallpaperHttpServer server;

    static boolean isRunningNow() {
        WallpaperSyncService inst = activeInstance;
        return inst != null && inst.isRunning();
    }
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
            activeInstance = this;
            String ip = NetworkAddress.localIpv4(this);
            String url = ip != null ? "http://" + ip + ":" + port : "port " + port;
            startForeground(NOTIF_ID, buildNotification(url));
        } catch (IOException e) {
            lastError = e.getMessage();
            server = null;
            if (activeInstance == this) activeInstance = null;
        }
        notifyUi();
    }

    synchronized void stopServer() {
        if (server != null) {
            server.stop();
            server = null;
        }
        if (activeInstance == this) activeInstance = null;
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

    static String rotatePinNow(Context ctx) {
        WallpaperSyncService inst = activeInstance;
        if (inst != null) return inst.rotatePin();
        String pin = generatePin();
        ctx.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_PIN, pin).apply();
        return pin;
    }

    static String currentPinNow(Context ctx) {
        WallpaperSyncService inst = activeInstance;
        if (inst != null) return inst.getPin();
        return loadOrCreatePin(ctx);
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
            boolean[] st = WallpaperSyncService.directStatus(WallpaperSyncService.this);
            return "{\"ok\":true,\"bridgeAvailable\":true,\"homeSet\":" + st[0] + ",\"screensaverSet\":" + st[1] + "}";
        }

        @Override
        public WallpaperHttpServer.Result applyWallpaper(String target, byte[] imageBytes) {
            if (!WallpaperHttpServer.isSupportedImageHeader(imageBytes, imageBytes.length)) {
                return WallpaperHttpServer.Result.fail(415, "Unsupported image format (use JPG, PNG, or WebP)");
            }
            boolean ok = true;
            if ("home".equals(target) || "both".equals(target)) {
                ok &= WallpaperSyncService.directApply(WallpaperSyncService.this, "home", imageBytes);
            }
            if ("screensaver".equals(target) || "both".equals(target)) {
                ok &= WallpaperSyncService.directApply(WallpaperSyncService.this, "screensaver", imageBytes);
            }
            notifyUi();
            return ok ? WallpaperHttpServer.Result.success()
                    : WallpaperHttpServer.Result.fail(500, "Could not save wallpaper");
        }

        @Override
        public WallpaperHttpServer.Result clearWallpaper(String target) {
            boolean ok = true;
            if ("home".equals(target) || "both".equals(target)) {
                ok &= WallpaperSyncService.directClear(WallpaperSyncService.this, "home");
            }
            if ("screensaver".equals(target) || "both".equals(target)) {
                ok &= WallpaperSyncService.directClear(WallpaperSyncService.this, "screensaver");
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
                notifyLauncher(WallpaperSyncService.this);
                notifyUi();
                return WallpaperHttpServer.Result.success();
            }
            return WallpaperHttpServer.Result.fail(400, s.error);
        }
    }

    static final String ACTION_WALLPAPER_UPDATED = "com.gathin.tvhome.ACTION_WALLPAPER_UPDATED";
    static final long MAX_BYTES = 15L * 1024L * 1024L;
    private static final String TARGET_HOME = "home";
    private static final String TARGET_SCREENSAVER = "screensaver";
    private static final String HOME_FILE = "home_wallpaper.jpg";
    private static final String SCREENSAVER_FILE = "screensaver_wallpaper.jpg";

    private static String fileNameFor(String target) {
        if (TARGET_HOME.equals(target)) return HOME_FILE;
        if (TARGET_SCREENSAVER.equals(target)) return SCREENSAVER_FILE;
        return null;
    }

    private static java.io.File resolveStorageDir(Context ctx) {
        java.io.File dir = Config.dir(ctx);
        if (dir != null && (dir.isDirectory() || dir.mkdirs())) return dir;
        java.io.File internal = ctx.getFilesDir();
        if (internal != null && !internal.isDirectory()) internal.mkdirs();
        return internal;
    }

    static boolean[] directStatus(Context ctx) {
        android.content.SharedPreferences prefs = ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        String homePath = prefs.getString(Config.WALLPAPER_CHOICE_KEY, null);
        String ssPath = prefs.getString(Config.SCREENSAVER_WALLPAPER_KEY, null);
        java.io.File homeFile = homePath != null && !"none".equals(homePath) ? new java.io.File(homePath) : null;
        java.io.File ssFile = ssPath != null ? new java.io.File(ssPath) : null;
        return new boolean[]{
                homeFile != null && homeFile.isFile() && homeFile.length() > 0,
                ssFile != null && ssFile.isFile() && ssFile.length() > 0
        };
    }

    static boolean directApply(Context ctx, String target, byte[] imageBytes) {
        String name = fileNameFor(target);
        if (ctx == null || name == null || imageBytes == null || imageBytes.length == 0 || imageBytes.length > MAX_BYTES) {
            return false;
        }
        java.io.File dir = resolveStorageDir(ctx);
        if (dir == null) return false;
        java.io.File tmp = new java.io.File(dir, name + ".tmp");
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
            out.write(imageBytes);
            out.flush();
        } catch (java.io.IOException e) {
            if (tmp.exists()) tmp.delete();
            return false;
        }
        java.io.File dest = new java.io.File(dir, name);
        android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeFile(tmp.getPath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            tmp.delete();
            return false;
        }
        if (dest.exists() && !dest.delete()) {
            tmp.delete();
            return false;
        }
        if (!tmp.renameTo(dest)) {
            tmp.delete();
            return false;
        }
        android.content.SharedPreferences prefs = ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        android.content.SharedPreferences.Editor ed = prefs.edit();
        if (TARGET_HOME.equals(target)) {
            ed.putString(Config.WALLPAPER_CHOICE_KEY, dest.getAbsolutePath());
        } else {
            ed.putString(Config.SCREENSAVER_WALLPAPER_KEY, dest.getAbsolutePath());
            ed.putString(Config.SCREENSAVER_BG_KEY, "wallpaper");
        }
        long rev = prefs.getLong(Config.SETTINGS_REVISION_KEY, 0L) + 1L;
        ed.putLong(Config.SETTINGS_REVISION_KEY, rev).apply();
        Config.invalidateCache();
        notifyLauncher(ctx);
        return true;
    }

    static boolean directClear(Context ctx, String target) {
        String name = fileNameFor(target);
        java.io.File dir = ctx == null ? null : resolveStorageDir(ctx);
        if (name == null || dir == null) return false;
        java.io.File tmp = new java.io.File(dir, name + ".tmp");
        java.io.File dest = new java.io.File(dir, name);
        if (tmp.exists()) tmp.delete();
        if (dest.exists()) dest.delete();
        android.content.SharedPreferences prefs = ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        android.content.SharedPreferences.Editor ed = prefs.edit();
        if (TARGET_HOME.equals(target)) {
            ed.remove(Config.WALLPAPER_CHOICE_KEY);
        } else {
            ed.remove(Config.SCREENSAVER_WALLPAPER_KEY);
            ed.remove(Config.SCREENSAVER_BG_KEY);
        }
        long rev = prefs.getLong(Config.SETTINGS_REVISION_KEY, 0L) + 1L;
        ed.putLong(Config.SETTINGS_REVISION_KEY, rev).apply();
        Config.invalidateCache();
        notifyLauncher(ctx);
        return true;
    }

    static void notifyLauncher(Context ctx) {
        Intent intent = new Intent(ACTION_WALLPAPER_UPDATED).setPackage(ctx.getPackageName());
        ctx.sendBroadcast(intent);
    }
}
