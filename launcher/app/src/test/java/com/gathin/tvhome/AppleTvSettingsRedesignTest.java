package com.gathin.tvhome;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Pure-JVM unit tests for Apple TV Settings redesign & Quick Settings Edit Controls contracts.
 */
public class AppleTvSettingsRedesignTest {

    @Test
    public void quickSettingsUpgradePreservesAllTilesVisible() {
        Set<String> hidden = QuickSettingsPrefs.decodeSet("");
        assertTrue(hidden.isEmpty());
        assertEquals(QuickSettingsPrefs.ALL_TILES.size(), QuickSettingsPrefs.visibleTileCount(hidden));
        for (String tileId : QuickSettingsPrefs.ALL_TILES) {
            assertTrue("Expected tile visible on upgrade: " + tileId, QuickSettingsPrefs.isTileVisible(hidden, tileId));
        }
    }

    @Test
    public void quickSettingsHideAllOptionalTilesLeavesZeroOptionalVisible() {
        Set<String> hiddenAll = new HashSet<>(QuickSettingsPrefs.ALL_TILES);
        String encoded = QuickSettingsPrefs.encodeSet(hiddenAll);
        Set<String> decoded = QuickSettingsPrefs.decodeSet(encoded);

        assertEquals(QuickSettingsPrefs.ALL_TILES.size(), decoded.size());
        assertEquals(0, QuickSettingsPrefs.visibleTileCount(decoded));
        for (String tileId : QuickSettingsPrefs.ALL_TILES) {
            assertFalse(QuickSettingsPrefs.isTileVisible(decoded, tileId));
        }
    }

    @Test
    public void quickSettingsEncodeDecodeRoundTripPreservesPartialVisibility() {
        Set<String> hidden = new HashSet<>(Arrays.asList(
                QuickSettingsPrefs.TILE_DISPLAY,
                QuickSettingsPrefs.TILE_BLUETOOTH,
                QuickSettingsPrefs.TILE_BOOST_RAM
        ));
        Set<String> decoded = QuickSettingsPrefs.decodeSet(QuickSettingsPrefs.encodeSet(hidden));
        assertEquals(hidden, decoded);
        assertEquals(QuickSettingsPrefs.ALL_TILES.size() - 3, QuickSettingsPrefs.visibleTileCount(decoded));
        assertTrue(QuickSettingsPrefs.isTileVisible(decoded, QuickSettingsPrefs.TILE_SCREENSAVER));
        assertTrue(QuickSettingsPrefs.isTileVisible(decoded, QuickSettingsPrefs.TILE_INPUTS));
        assertFalse(QuickSettingsPrefs.isTileVisible(decoded, QuickSettingsPrefs.TILE_DISPLAY));
        assertFalse(QuickSettingsPrefs.isTileVisible(decoded, QuickSettingsPrefs.TILE_BLUETOOTH));
        assertFalse(QuickSettingsPrefs.isTileVisible(decoded, QuickSettingsPrefs.TILE_BOOST_RAM));
    }

    @Test
    public void reduceTransparencySelectsDistinctOpaqueDrawablesAndColors() {
        assertNotEquals(AppleTvUi.panelBgRes(false), AppleTvUi.panelBgRes(true));
        assertNotEquals(AppleTvUi.rowBgRes(false), AppleTvUi.rowBgRes(true));
        assertNotEquals(AppleTvUi.tileBgRes(false), AppleTvUi.tileBgRes(true));
        assertNotEquals(AppleTvUi.rowTitleColorRes(false), AppleTvUi.rowTitleColorRes(true));
        assertNotEquals(AppleTvUi.rowValueColorRes(false), AppleTvUi.rowValueColorRes(true));
        assertNotEquals(AppleTvUi.tileTextColorRes(false), AppleTvUi.tileTextColorRes(true));
        assertNotEquals(AppleTvUi.tileIconColorRes(false), AppleTvUi.tileIconColorRes(true));
    }

    @Test
    public void sleepTimerStepsMatchSpecification() {
        int[] expected = {0, 15, 30, 45, 60, 90, 120};
        assertEquals(expected.length, SleepTimer.STEPS.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], SleepTimer.STEPS[i]);
        }
        assertEquals(15, SleepTimer.next(0));
        assertEquals(0, SleepTimer.next(120));
    }

    @Test
    public void preferenceKeysAreDistinctAndStable() {
        Set<String> keys = new HashSet<>(Arrays.asList(
                Config.REDUCE_TRANSPARENCY_KEY,
                Config.SHOW_NOW_PLAYING_HOME_KEY,
                Config.SHOW_NOW_PLAYING_QS_KEY,
                Config.STARTUP_DEST_KEY,
                Config.QS_HIDDEN_TILES_KEY,
                Config.CLOCK_FORMAT_KEY,
                Config.SCREENSAVER_BG_KEY,
                Config.SCREENSAVER_STYLE_KEY,
                OpenOnStart.KEY
        ));
        assertEquals(9, keys.size());
        assertNotEquals(Config.SHOW_NOW_PLAYING_HOME_KEY, Config.SHOW_NOW_PLAYING_QS_KEY);
    }
}
