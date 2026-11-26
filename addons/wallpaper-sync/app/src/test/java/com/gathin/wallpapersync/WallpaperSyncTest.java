package com.gathin.wallpapersync;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class WallpaperSyncTest {
    @Test
    public void qrEncodesLocalUrl() {
        boolean[][] modules = Qr.encode("http://192.168.1.42:8765");
        assertNotNull(modules);
        assertTrue(modules.length >= 21);
        assertEquals(modules.length, modules[0].length);
    }

    @Test
    public void imageHeaderValidationAcceptsJpgPngWebpAndRejectsOthers() {
        assertTrue(LauncherBridge.isSupportedImageHeader(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00}, 4));
        assertTrue(LauncherBridge.isSupportedImageHeader(
                new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}, 8));
        assertTrue(LauncherBridge.isSupportedImageHeader(
                new byte[]{'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'}, 12));
        assertFalse(LauncherBridge.isSupportedImageHeader(new byte[]{'G', 'I', 'F', '8', '9', 'a'}, 6));
        assertFalse(LauncherBridge.isSupportedImageHeader(new byte[]{1, 2}, 2));
    }

    @Test
    public void httpServerEnforcesPinAndRoutesTargets() throws Exception {
        final String[] lastTarget = new String[1];
        final byte[][] lastBytes = new byte[1][];
        WallpaperHttpServer server = new WallpaperHttpServer(new WallpaperHttpServer.Handler() {
            @Override
            public String statusJson() {
                return "{\"ok\":true,\"homeSet\":false,\"screensaverSet\":false}";
            }

            @Override
            public WallpaperHttpServer.Result applyWallpaper(String target, byte[] imageBytes) {
                lastTarget[0] = target;
                lastBytes[0] = imageBytes;
                return WallpaperHttpServer.Result.success();
            }

            @Override
            public WallpaperHttpServer.Result clearWallpaper(String target) {
                lastTarget[0] = "clear:" + target;
                return WallpaperHttpServer.Result.success();
            }
        }, "4829");

        // Wrong PIN -> 403
        ByteArrayOutputStream out1 = new ByteArrayOutputStream();
        Map<String, String> h1 = new HashMap<>();
        h1.put("x-pin", "0000");
        h1.put("content-length", "4");
        server.dispatch("POST", "/api/wallpaper?target=home", h1,
                new ByteArrayInputStream(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0}), out1);
        assertTrue(out1.toString(StandardCharsets.UTF_8.name()).startsWith("HTTP/1.1 403"));

        // Right PIN -> 200 and forwards bytes
        ByteArrayOutputStream out2 = new ByteArrayOutputStream();
        Map<String, String> h2 = new HashMap<>();
        h2.put("x-pin", "4829");
        h2.put("content-length", "4");
        byte[] jpg = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};
        server.dispatch("POST", "/api/wallpaper?target=screensaver", h2,
                new ByteArrayInputStream(jpg), out2);
        assertTrue(out2.toString(StandardCharsets.UTF_8.name()).startsWith("HTTP/1.1 200"));
        assertEquals("screensaver", lastTarget[0]);
        assertEquals(4, lastBytes[0].length);
    }
}
