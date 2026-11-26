package com.gathin.wallpapersync;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Communicates with com.gathin.tvhome's WallpaperBridgeProvider over Android's ContentResolver
 * (local IPC only; no ADB or storage permissions required).
 */
final class LauncherBridge {
    static final String AUTHORITY = "com.gathin.tvhome.wallpaper_bridge";
    static final long MAX_BYTES = 15L * 1024L * 1024L;

    private static Uri baseUri() {
        return Uri.parse("content://" + AUTHORITY);
    }

    static final String TARGET_HOME = "home";
    static final String TARGET_SCREENSAVER = "screensaver";

    static final class Status {
        final boolean available;
        final boolean homeSet;
        final long homeUpdatedMs;
        final boolean screensaverSet;
        final long screensaverUpdatedMs;

        Status(boolean available, boolean homeSet, long homeUpdatedMs, boolean screensaverSet, long screensaverUpdatedMs) {
            this.available = available;
            this.homeSet = homeSet;
            this.homeUpdatedMs = homeUpdatedMs;
            this.screensaverSet = screensaverSet;
            this.screensaverUpdatedMs = screensaverUpdatedMs;
        }
    }

    private LauncherBridge() {}

    static boolean isSupportedImageHeader(byte[] head, int len) {
        if (head == null || len < 4) return false;
        // JPEG: FF D8 FF
        if ((head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF) {
            return true;
        }
        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if (len >= 8 && (head[0] & 0xFF) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G'
                && head[4] == 0x0D && head[5] == 0x0A && head[6] == 0x1A && head[7] == 0x0A) {
            return true;
        }
        // WebP: RIFF....WEBP
        if (len >= 12 && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P') {
            return true;
        }
        return false;
    }

    static Status status(Context ctx) {
        try {
            Bundle b = ctx.getContentResolver().call(baseUri(), "status", null, null);
            if (b != null && b.getBoolean("ok", false)) {
                return new Status(
                        true,
                        b.getBoolean("home_set", false),
                        b.getLong("home_updated_ms", 0L),
                        b.getBoolean("screensaver_set", false),
                        b.getLong("screensaver_updated_ms", 0L)
                );
            }
        } catch (Exception ignored) {
        }
        return new Status(false, false, 0L, false, 0L);
    }

    static boolean applyImage(Context ctx, String target, byte[] imageBytes) throws IOException {
        if (!TARGET_HOME.equals(target) && !TARGET_SCREENSAVER.equals(target)) return false;
        if (imageBytes == null || imageBytes.length == 0 || imageBytes.length > MAX_BYTES) return false;
        if (!isSupportedImageHeader(imageBytes, imageBytes.length)) return false;

        ContentResolver cr = ctx.getContentResolver();
        Uri base = baseUri();
        Uri uri = base.buildUpon().appendPath(target).build();
        try (OutputStream out = cr.openOutputStream(uri, "w")) {
            if (out == null) return false;
            out.write(imageBytes);
            out.flush();
        } catch (SecurityException e) {
            throw new IOException("Permission denied by launcher bridge", e);
        }
        Bundle res = cr.call(base, "apply", target, null);
        return res != null && res.getBoolean("ok", false);
    }

    static boolean clear(Context ctx, String target) {
        if (!TARGET_HOME.equals(target) && !TARGET_SCREENSAVER.equals(target)) return false;
        try {
            Bundle res = ctx.getContentResolver().call(baseUri(), "clear", target, null);
            return res != null && res.getBoolean("ok", false);
        } catch (Exception e) {
            return false;
        }
    }

    static byte[] readBounded(InputStream in, int contentLength) throws IOException {
        if (contentLength <= 0 || contentLength > MAX_BYTES) {
            throw new IOException("Invalid content length");
        }
        byte[] buf = new byte[contentLength];
        int offset = 0;
        while (offset < contentLength) {
            int n = in.read(buf, offset, contentLength - offset);
            if (n < 0) throw new IOException("Unexpected EOF");
            offset += n;
        }
        return buf;
    }
}
