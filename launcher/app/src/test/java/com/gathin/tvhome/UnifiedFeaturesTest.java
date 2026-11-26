package com.gathin.tvhome;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class UnifiedFeaturesTest {

    @Test
    public void spotlightFuzzyRanking() {
        assertEquals(4, SpotlightDialog.matchScore("YouTube", "you"));
        assertEquals(3, SpotlightDialog.matchScore("Launcher Settings", "set"));
        assertEquals(2, SpotlightDialog.matchScore("SmartTube", "tube"));
        assertEquals(1, SpotlightDialog.matchScore("Wallpaper Sync", "ws"));
        assertEquals(0, SpotlightDialog.matchScore("Netflix", "xyz"));
    }

    @Test
    public void memoryBoosterProtectsCoreAndMedia() {
        assertTrue(MemoryBooster.isProtected("com.gathin.tvhome", "com.gathin.tvhome", null));
        assertTrue(MemoryBooster.isProtected("com.spotify.tv.android", "com.gathin.tvhome", "com.spotify.tv.android"));
        assertTrue(MemoryBooster.isProtected("com.android.systemui", "com.gathin.tvhome", null));
        assertTrue(MemoryBooster.isProtected("com.mediatek.tvinput", "com.gathin.tvhome", null));
        assertFalse(MemoryBooster.isProtected("org.smarttube.stable", "com.gathin.tvhome", null));
    }

    @Test
    public void appCustomizationsRoundTrip() {
        Set<String> hidden = new HashSet<>(Arrays.asList("com.example.a", "com.example.b"));
        assertEquals(hidden, AppCustomizations.decodeSet(AppCustomizations.encodeSet(hidden)));

        Map<String, String> labels = new HashMap<>();
        labels.put("org.smarttube.stable", "YouTube Pro");
        labels.put("com.sonyliv", "Sports");
        assertEquals(labels, AppCustomizations.decodeMap(AppCustomizations.encodeMap(labels)));
    }

    @Test
    public void wallpaperHttpHelpersAndQrWork() {
        byte[] jpg = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        byte[] bad = new byte[]{'T', 'E', 'X', 'T'};
        assertTrue(WallpaperHttpServer.isSupportedImageHeader(jpg, jpg.length));
        assertTrue(WallpaperHttpServer.isSupportedImageHeader(png, png.length));
        assertFalse(WallpaperHttpServer.isSupportedImageHeader(bad, bad.length));
        assertEquals("home", WallpaperHttpServer.queryParam("target=home&x=1", "target"));

        boolean[][] qr = Qr.encode("http://192.168.1.50:8765");
        assertNotNull(qr);
        assertTrue(qr.length >= 21);
    }

    @Test
    public void remoteKeysSupportsSearchAction() {
        assertTrue(RemoteKeys.isAction("search"));
        assertTrue(RemoteKeys.isAction("settings"));
        assertTrue(RemoteKeys.isAction("library"));
    }

    @Test
    public void frameRateLockSelectsExact60HzMatchingResolution() {
        int[] ws = {1920, 3840, 3840, 3840, 3840};
        int[] hs = {1080, 2160, 2160, 2160, 2160};
        float[] rates = {60.0f, 24.0f, 30.0f, 59.94f, 120.0f};
        assertEquals(3, FrameRateLock.pick60HzModeIndex(3840, 2160, ws, hs, rates));

        // Falls back to highest rate <= 60Hz when exact 60Hz is absent
        float[] palRates = {24.0f, 25.0f, 50.0f};
        int[] palW = {1920, 1920, 1920};
        int[] palH = {1080, 1080, 1080};
        assertEquals(2, FrameRateLock.pick60HzModeIndex(1920, 1080, palW, palH, palRates));

        assertEquals(-1, FrameRateLock.pick60HzModeIndex(1920, 1080, null, palH, palRates));
    }

    @Test
    public void heroArtSlidingWindowBlurPreservesUniformImage() {
        int w = 16, h = 12;
        int[] src = new int[w * h];
        int[] tmp = new int[w * h];
        int[] dst = new int[w * h];
        Arrays.fill(src, 0xFF336699);
        HeroArt.boxBlur(src, tmp, w, h, 2, true);
        HeroArt.boxBlur(tmp, dst, w, h, 2, false);
        for (int p : dst) {
            assertEquals(0xFF336699, p);
        }
    }

    @Test
    public void lerpColorEndpointsAndMidpoint() {
        assertEquals(0xFF000000, MainActivity.lerpColor(0xFF000000, 0xFFFFFFFF, 0f));
        assertEquals(0xFFFFFFFF, MainActivity.lerpColor(0xFF000000, 0xFFFFFFFF, 1f));
        assertEquals(0xFF808080, MainActivity.lerpColor(0xFF000000, 0xFFFFFFFF, 0.5f));
    }

    @Test
    public void ditheredRadialGlowEliminatesBandingAndStaysOpaque() {
        int w = 64, h = 36;
        int[] pixels = new int[w * h];
        ClockScene.DitheredRadialGlowDrawable.renderPixels(pixels, w, h, 0xFF2B5CFF, 0.5f, 0.5f);
        // Every pixel must be fully opaque (0xFF alpha)
        for (int p : pixels) {
            assertEquals(0xFF000000, p & 0xFF000000);
        }
        // Center must carry blue glow, far corners must be pure black
        int center = pixels[(h / 2) * w + (w / 2)];
        assertTrue((center & 0xFF) >= 55);
        assertEquals(0xFF000000, pixels[0]);
        assertEquals(0xFF000000, pixels[pixels.length - 1]);
    }
}
