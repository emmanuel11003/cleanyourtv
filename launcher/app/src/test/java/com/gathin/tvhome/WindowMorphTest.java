package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class WindowMorphTest {

    @Test
    public void interpolateBoundsEndpointsAndMidpoint() {
        int[] out = new int[4];

        WindowMorph.interpolateBounds(100, 50, 260, 140, 0, 0, 1920, 1080, 0f, out);
        assertArrayEquals(new int[]{100, 50, 260, 140}, out);

        WindowMorph.interpolateBounds(100, 50, 260, 140, 0, 0, 1920, 1080, 1f, out);
        assertArrayEquals(new int[]{0, 0, 1920, 1080}, out);

        WindowMorph.interpolateBounds(100, 50, 260, 140, 0, 0, 1920, 1080, 0.5f, out);
        assertArrayEquals(new int[]{50, 25, 1090, 610}, out);
    }

    @Test
    public void interpolateRadiusShrinksCleanlyToTarget() {
        assertEquals(24f, WindowMorph.interpolateRadius(24f, 0f, 0f), 0.01f);
        assertEquals(12f, WindowMorph.interpolateRadius(24f, 0f, 0.5f), 0.01f);
        assertEquals(0f, WindowMorph.interpolateRadius(24f, 0f, 1f), 0.01f);
        assertEquals(26f, WindowMorph.interpolateRadius(16f, 26f, 1f), 0.01f);
    }

    @Test
    public void uniformCoverScaleNeverDistortsOrZeroesOut() {
        float scale = WindowMorph.uniformCoverScale(160, 44, 1920, 1080);
        assertEquals(160f / 1920f, scale, 0.0001f);

        float tinyScale = WindowMorph.uniformCoverScale(1, 1, 10000, 10000);
        assertTrue(tinyScale >= 0.02f);

        assertEquals(1f, WindowMorph.uniformCoverScale(1920, 1080, 1920, 1080), 0.0001f);
    }

    @Test
    public void alphaCurvesAreSynchronizedAndMonotonic() {
        assertEquals(0f, WindowMorph.shellAlpha(0f), 0.001f);
        assertEquals(1f, WindowMorph.shellAlpha(0.2f), 0.001f);
        assertEquals(0f, WindowMorph.scrimAlpha(0f), 0.001f);
        assertEquals(1f, WindowMorph.scrimAlpha(1f), 0.001f);

        assertEquals(0f, WindowMorph.contentRevealAlpha(0.05f, true), 0.001f);
        assertEquals(1f, WindowMorph.contentRevealAlpha(0.9f, true), 0.001f);
        assertTrue(WindowMorph.contentRevealAlpha(0.5f, true) > WindowMorph.contentRevealAlpha(0.5f, false));
    }

    @Test
    public void fallbackBoundsCentersInLowerDock() {
        int[] out = new int[4];
        WindowMorph.fallbackBounds(0, 0, 1920, 1080, 2.0f, out);
        int w = out[2] - out[0];
        int h = out[3] - out[1];
        assertTrue(w > 0 && h > 0);
        assertEquals(960, (out[0] + out[2]) / 2, 2);
        assertTrue((out[1] + out[3]) / 2 > 540);
    }
}
