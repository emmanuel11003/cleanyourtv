package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * On-TV per-app customizations persisted in {@link Config#SETTINGS_NAME}:
 * user-hidden packages and user-defined custom tile labels.
 * Uses compact newline-delimited encoding (zero JSON parser allocation on startup).
 */
final class AppCustomizations {
    static final String KEY_USER_HIDDEN = "user_hidden_apps";
    static final String KEY_CUSTOM_LABELS = "custom_app_labels";

    private AppCustomizations() {}

    static Set<String> loadHidden(Context ctx) {
        if (ctx == null) return Collections.emptySet();
        return decodeSet(ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_USER_HIDDEN, null));
    }

    static Map<String, String> loadLabels(Context ctx) {
        if (ctx == null) return Collections.emptyMap();
        return decodeMap(ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_CUSTOM_LABELS, null));
    }

    static boolean setHidden(Context ctx, String pkg, boolean hide) {
        if (ctx == null || pkg == null || pkg.isEmpty()) return false;
        SharedPreferences prefs = ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        Set<String> set = new HashSet<>(decodeSet(prefs.getString(KEY_USER_HIDDEN, null)));
        boolean changed = hide ? set.add(pkg) : set.remove(pkg);
        if (!changed) return false;
        long rev = prefs.getLong(Config.SETTINGS_REVISION_KEY, 0L) + 1L;
        prefs.edit()
                .putString(KEY_USER_HIDDEN, encodeSet(set))
                .putLong(Config.SETTINGS_REVISION_KEY, rev)
                .apply();
        Config.invalidateCache();
        return true;
    }

    static boolean setCustomLabel(Context ctx, String pkg, String label) {
        if (ctx == null || pkg == null || pkg.isEmpty()) return false;
        SharedPreferences prefs = ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        Map<String, String> map = new HashMap<>(decodeMap(prefs.getString(KEY_CUSTOM_LABELS, null)));
        String clean = label == null ? "" : label.replace('\n', ' ').replace('\r', ' ').trim();
        if (clean.isEmpty()) {
            if (map.remove(pkg) == null) return false;
        } else {
            if (clean.equals(map.get(pkg))) return false;
            map.put(pkg, clean);
        }
        long rev = prefs.getLong(Config.SETTINGS_REVISION_KEY, 0L) + 1L;
        prefs.edit()
                .putString(KEY_CUSTOM_LABELS, encodeMap(map))
                .putLong(Config.SETTINGS_REVISION_KEY, rev)
                .apply();
        Config.invalidateCache();
        return true;
    }

    static Set<String> decodeSet(String raw) {
        if (raw == null || raw.isEmpty()) return Collections.emptySet();
        Set<String> out = new HashSet<>();
        for (String line : raw.split("\n")) {
            String v = line.trim();
            if (!v.isEmpty()) out.add(v);
        }
        return out;
    }

    static String encodeSet(Set<String> values) {
        StringBuilder sb = new StringBuilder();
        for (String v : values) {
            if (v != null && !v.isEmpty()) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(v.trim());
            }
        }
        return sb.toString();
    }

    static Map<String, String> decodeMap(String raw) {
        if (raw == null || raw.isEmpty()) return Collections.emptyMap();
        Map<String, String> out = new HashMap<>();
        for (String line : raw.split("\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String k = line.substring(0, eq).trim();
            String v = line.substring(eq + 1).trim();
            if (!k.isEmpty() && !v.isEmpty()) out.put(k, v);
        }
        return out;
    }

    static String encodeMap(Map<String, String> values) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : values.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            String k = e.getKey().trim();
            String v = e.getValue().replace('\n', ' ').replace('\r', ' ').trim();
            if (!k.isEmpty() && !v.isEmpty()) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(k).append('=').append(v);
            }
        }
        return sb.toString();
    }
}
