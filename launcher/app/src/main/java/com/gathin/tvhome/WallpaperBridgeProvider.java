package com.gathin.tvhome;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.List;

/**
 * Local-only bridge for the Wallpaper Sync companion add-on (no network here).
 * Accepts image writes for "home" and "screensaver" targets, then applies or clears them
 * in launcher_settings without requiring ADB or WRITE_SECURE_SETTINGS.
 */
public class WallpaperBridgeProvider extends ContentProvider {
    static final String ACTION_WALLPAPER_UPDATED = "com.gathin.tvhome.ACTION_WALLPAPER_UPDATED";
    static final long MAX_BYTES = 15L * 1024L * 1024L;

    private static final String TARGET_HOME = "home";
    private static final String TARGET_SCREENSAVER = "screensaver";
    private static final String HOME_FILE = "home_wallpaper.jpg";
    private static final String SCREENSAVER_FILE = "screensaver_wallpaper.jpg";

    private static String targetFromUri(Uri uri) {
        if (uri == null) return null;
        List<String> segments = uri.getPathSegments();
        if (segments == null || segments.size() != 1) return null;
        String seg = segments.get(0);
        if (TARGET_HOME.equals(seg) || TARGET_SCREENSAVER.equals(seg)) return seg;
        return null;
    }

    private static String fileNameFor(String target) {
        if (TARGET_HOME.equals(target)) return HOME_FILE;
        if (TARGET_SCREENSAVER.equals(target)) return SCREENSAVER_FILE;
        return null;
    }

    static boolean[] directStatus(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        String homePath = prefs.getString(Config.WALLPAPER_CHOICE_KEY, null);
        String ssPath = prefs.getString(Config.SCREENSAVER_WALLPAPER_KEY, null);
        File homeFile = homePath != null && !"none".equals(homePath) ? new File(homePath) : null;
        File ssFile = ssPath != null ? new File(ssPath) : null;
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
        File dir = resolveStorageDir(ctx);
        if (dir == null) return false;
        File tmp = new File(dir, name + ".tmp");
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
            out.write(imageBytes);
            out.flush();
        } catch (java.io.IOException e) {
            if (tmp.exists()) tmp.delete();
            return false;
        }
        return applyTargetStatic(ctx, target);
    }

    static boolean directClear(Context ctx, String target) {
        return clearTargetStatic(ctx, target);
    }

    private static File resolveStorageDir(Context ctx) {
        File dir = Config.dir(ctx);
        if (dir != null && (dir.isDirectory() || dir.mkdirs())) return dir;
        File internal = ctx.getFilesDir();
        if (internal != null && !internal.isDirectory()) internal.mkdirs();
        return internal;
    }

    private File storageDir(Context ctx) {
        return resolveStorageDir(ctx);
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        Context ctx = getContext();
        String target = targetFromUri(uri);
        String name = fileNameFor(target);
        if (ctx == null || name == null) throw new FileNotFoundException(String.valueOf(uri));
        File dir = storageDir(ctx);
        if (dir == null) throw new FileNotFoundException(String.valueOf(uri));
        if (mode != null && mode.contains("w")) {
            File tmp = new File(dir, name + ".tmp");
            return ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_WRITE_ONLY
                    | ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE);
        }
        File file = new File(dir, name);
        if (!file.isFile()) throw new FileNotFoundException(String.valueOf(uri));
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Context ctx = getContext();
        Bundle result = new Bundle();
        if (ctx == null) {
            result.putBoolean("ok", false);
            return result;
        }
        if ("status".equals(method)) {
            return buildStatus(ctx);
        }
        if ("apply".equals(method)) {
            boolean ok = applyTarget(ctx, arg);
            result.putBoolean("ok", ok);
            return result;
        }
        if ("clear".equals(method)) {
            boolean ok = clearTarget(ctx, arg);
            result.putBoolean("ok", ok);
            return result;
        }
        result.putBoolean("ok", false);
        return result;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        Context ctx = getContext();
        String target = targetFromUri(uri);
        if (ctx == null || target == null) return 0;
        return clearTarget(ctx, target) ? 1 : 0;
    }

    private Bundle buildStatus(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        String homePath = prefs.getString(Config.WALLPAPER_CHOICE_KEY, null);
        String ssPath = prefs.getString(Config.SCREENSAVER_WALLPAPER_KEY, null);
        File homeFile = homePath != null && !"none".equals(homePath) ? new File(homePath) : null;
        File ssFile = ssPath != null ? new File(ssPath) : null;

        Bundle b = new Bundle();
        b.putBoolean("ok", true);
        b.putBoolean("home_set", homeFile != null && homeFile.isFile() && homeFile.length() > 0);
        b.putLong("home_updated_ms", homeFile != null && homeFile.isFile() ? homeFile.lastModified() : 0L);
        b.putBoolean("screensaver_set", ssFile != null && ssFile.isFile() && ssFile.length() > 0);
        b.putLong("screensaver_updated_ms", ssFile != null && ssFile.isFile() ? ssFile.lastModified() : 0L);
        return b;
    }

    private boolean applyTarget(Context ctx, String target) {
        return applyTargetStatic(ctx, target);
    }

    private static boolean applyTargetStatic(Context ctx, String target) {
        String name = fileNameFor(target);
        File dir = resolveStorageDir(ctx);
        if (name == null || dir == null) return false;

        File tmp = new File(dir, name + ".tmp");
        File dest = new File(dir, name);
        if (!tmp.isFile() || tmp.length() <= 0 || tmp.length() > MAX_BYTES) {
            if (tmp.exists()) tmp.delete();
            return false;
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(tmp.getPath(), bounds);
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

        SharedPreferences prefs = ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor ed = prefs.edit();
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

    private boolean clearTarget(Context ctx, String target) {
        return clearTargetStatic(ctx, target);
    }

    private static boolean clearTargetStatic(Context ctx, String target) {
        String name = fileNameFor(target);
        File dir = resolveStorageDir(ctx);
        if (name == null || dir == null) return false;

        File tmp = new File(dir, name + ".tmp");
        File dest = new File(dir, name);
        if (tmp.exists()) tmp.delete();
        if (dest.exists()) dest.delete();

        SharedPreferences prefs = ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor ed = prefs.edit();
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

    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Cursor query(Uri uri, String[] p, String s, String[] a, String o) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
