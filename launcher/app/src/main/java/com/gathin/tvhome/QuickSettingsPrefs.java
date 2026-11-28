package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Persists user visibility choices for Quick Settings optional tiles in {@link Config#SETTINGS_NAME}
 * so they survive restarts and participate in {@link SettingsBackup}.
 * All optional tiles default to visible on upgrade so existing installations keep their full grid.
 */
final class QuickSettingsPrefs {
    static final String TILE_SCREENSAVER = "screensaver";
    static final String TILE_INPUTS = "inputs";
    static final String TILE_DISPLAY = "display";
    static final String TILE_SOUND = "sound";
    static final String TILE_WIFI = "wifi";
    static final String TILE_BLUETOOTH = "bluetooth";
    static final String TILE_SLEEP_TIMER = "sleep_timer";
    static final String TILE_REARRANGE = "rearrange";
    static final String TILE_NOTIFICATIONS = "notifications";
    static final String TILE_BOOST_RAM = "boost_ram";

    static final List<String> ALL_TILES = Collections.unmodifiableList(Arrays.asList(
            TILE_SCREENSAVER,
            TILE_INPUTS,
            TILE_DISPLAY,
            TILE_SOUND,
            TILE_WIFI,
            TILE_BLUETOOTH,
            TILE_SLEEP_TIMER,
            TILE_REARRANGE,
            TILE_NOTIFICATIONS,
            TILE_BOOST_RAM
    ));

    private QuickSettingsPrefs() {}

    static Set<String> loadHiddenTiles(Context ctx) {
        if (ctx == null) return Collections.emptySet();
        SharedPreferences prefs = ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        return decodeSet(prefs.getString(Config.QS_HIDDEN_TILES_KEY, ""));
    }

    static boolean isTileVisible(Set<String> hiddenTiles, String tileId) {
        if (tileId == null || tileId.isEmpty()) return false;
        return hiddenTiles == null || !hiddenTiles.contains(tileId);
    }

    static boolean isTileVisible(Context ctx, String tileId) {
        return isTileVisible(loadHiddenTiles(ctx), tileId);
    }

    static void setTileVisible(Context ctx, String tileId, boolean visible) {
        if (ctx == null || tileId == null || tileId.isEmpty()) return;
        SharedPreferences prefs = ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        Set<String> hidden = new LinkedHashSet<>(decodeSet(prefs.getString(Config.QS_HIDDEN_TILES_KEY, "")));
        boolean changed = visible ? hidden.remove(tileId) : hidden.add(tileId);
        if (!changed) return;
        long rev = prefs.getLong(Config.SETTINGS_REVISION_KEY, 0L) + 1L;
        prefs.edit()
                .putString(Config.QS_HIDDEN_TILES_KEY, encodeSet(hidden))
                .putLong(Config.SETTINGS_REVISION_KEY, rev)
                .apply();
        Config.invalidateCache();
    }

    static void resetDefaults(Context ctx) {
        if (ctx == null) return;
        SharedPreferences prefs = ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        long rev = prefs.getLong(Config.SETTINGS_REVISION_KEY, 0L) + 1L;
        prefs.edit()
                .remove(Config.QS_HIDDEN_TILES_KEY)
                .putLong(Config.SETTINGS_REVISION_KEY, rev)
                .apply();
        Config.invalidateCache();
    }

    static int visibleTileCount(Set<String> hiddenTiles) {
        int count = 0;
        for (String id : ALL_TILES) {
            if (isTileVisible(hiddenTiles, id)) count++;
        }
        return count;
    }

    static Set<String> decodeSet(String raw) {
        if (raw == null || raw.trim().isEmpty()) return Collections.emptySet();
        Set<String> out = new LinkedHashSet<>();
        for (String token : raw.split("[,\\n]")) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty()) out.add(trimmed);
        }
        return out;
    }

    static String encodeSet(Set<String> hidden) {
        if (hidden == null || hidden.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String id : hidden) {
            if (id == null) continue;
            String t = id.trim();
            if (t.isEmpty()) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(t);
        }
        return sb.toString();
    }
}
