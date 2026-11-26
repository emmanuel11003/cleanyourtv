package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Unit tests for Liquid Glass geometry, Frost safety, and press-dip launch choreography. */
public class LiquidGlassTest {

    @Test
    public void frostDimensionsAndNullGuard() {
        assertEquals(160, Frost.WIDTH);
        assertEquals(90, Frost.HEIGHT);
        assertTrue(Frost.lastLuminance >= 0f && Frost.lastLuminance <= 1f);
        assertNull(Frost.of(null));
    }

    @Test
    public void adaptiveLuminanceVeilSwitchesCleanlyAtThreshold() {
        assertFalse(LiquidGlass.shouldUseDarkVeil(false, 0.25f));
        assertTrue(LiquidGlass.shouldUseDarkVeil(false, 0.65f));
        assertTrue(LiquidGlass.shouldUseDarkVeil(true, 0.10f));
        assertEquals(0.22f, LiquidGlass.adaptiveVeilFraction(false, 0.25f, 0.22f), 0.001f);
        assertEquals(0.42f, LiquidGlass.adaptiveVeilFraction(false, 0.65f, 0.22f), 0.001f);
        assertEquals(0.68f, LiquidGlass.adaptiveVeilFraction(true, 0.25f, 0.22f), 0.001f);
    }

    @Test
    public void liquidGlassPressDipLaunchesCleanlyWhenHeadlessOrReducedMotion() {
        AppLaunchController controller = new AppLaunchController();
        int[] launches = {0};
        boolean[] animated = {true};

        boolean first = controller.requestLaunch(null, null, true, false, 1.16f, true,
                (entry, anim) -> {
                    launches[0]++;
                    animated[0] = anim;
                });
        assertTrue(first);
        assertEquals(1, launches[0]);
        assertFalse(animated[0]);
        assertTrue(controller.isLaunching());

        // Debounce guard holds during active press-dip launch.
        boolean duplicate = controller.requestLaunch(null, null, true, false, 1.16f, true,
                (entry, anim) -> launches[0]++);
        assertFalse(duplicate);
        assertEquals(1, launches[0]);

        controller.onActivityPaused();
        assertFalse(controller.isLaunching());

        // Reduced motion skips dip animation and launches immediately.
        boolean reduced = controller.requestLaunch(null, null, true, true, 1.16f, true,
                (entry, anim) -> launches[0]++);
        assertTrue(reduced);
        assertEquals(2, launches[0]);
    }

    @Test
    public void concentricDockRadiusMatchesTileCornerPlusPadding() {
        float density = 2f;
        int inner = 1920 - 2 * 56 * 2 - 2 * 10 * 2;
        int tile = AppAdapter.tileWidthPx(inner, 5, density);
        float tileCorner = AppAdapter.cornerRadius(tile, density);
        float gap = DockDecoration.evenPaddingPx(inner, 5, tile, 26f * density);
        float bandRadius = DockDecoration.radiusPx(18f * density, gap);
        assertEquals(18f * density + gap, bandRadius, 0.001f);
        assertTrue("Tile corner radius must stay positive", tileCorner > 0f);
    }
}
