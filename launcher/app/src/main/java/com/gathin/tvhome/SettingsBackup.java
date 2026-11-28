package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Single-file JSON backup and restore for CleanYourTV launcher layout, settings,
 * custom app labels, hidden apps, app library selections, and optional config.json overlay
 * (including custom remote mappings).
 */
final class SettingsBackup {
    static final String BACKUP_FILE_NAME = "cleanyourtv-backup.json";
    static final int SCHEMA_VERSION = 1;
    static final int MAX_BACKUP_BYTES = 512 * 1024;

    private SettingsBackup() {}

    static final class Summary {
        final boolean ok;
        final String path;
        final int settingCount;
        final int orderCount;
        final String error;

        Summary(boolean ok, String path, int settingCount, int orderCount, String error) {
            this.ok = ok;
            this.path = path;
            this.settingCount = settingCount;
            this.orderCount = orderCount;
            this.error = error;
        }
    }

    static File primaryFile(Context ctx) {
        File dir = Config.dir(ctx);
        if (dir != null && (dir.isDirectory() || dir.mkdirs())) {
            return new File(dir, BACKUP_FILE_NAME);
        }
        return new File(ctx.getFilesDir(), BACKUP_FILE_NAME);
    }

    static File downloadMirrorFile() {
        try {
            File dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (dl != null && (dl.isDirectory() || dl.mkdirs())) {
                return new File(dl, BACKUP_FILE_NAME);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** Serializes the full launcher configuration into a portable JSON string. */
    static String exportJson(Context ctx) throws JSONException {
        JSONObject root = new JSONObject();
        root.put("schema", SCHEMA_VERSION);
        root.put("app", ctx.getPackageName());
        root.put("exported_ms", System.currentTimeMillis());

        // 1. Launcher settings SharedPreferences (preserving exact types)
        SharedPreferences prefs = ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        JSONObject settingsObj = new JSONObject();
        JSONObject typesObj = new JSONObject();
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            String key = e.getKey();
            Object val = e.getValue();
            if (key == null || val == null || Config.SETTINGS_REVISION_KEY.equals(key)) continue;
            if (val instanceof Boolean) {
                settingsObj.put(key, val);
                typesObj.put(key, "bool");
            } else if (val instanceof Integer) {
                settingsObj.put(key, val);
                typesObj.put(key, "int");
            } else if (val instanceof Long) {
                settingsObj.put(key, val);
                typesObj.put(key, "long");
            } else if (val instanceof Float) {
                settingsObj.put(key, ((Float) val).doubleValue());
                typesObj.put(key, "float");
            } else if (val instanceof String) {
                settingsObj.put(key, val);
                typesObj.put(key, "string");
            }
        }
        root.put("settings", settingsObj);
        root.put("setting_types", typesObj);

        // 2. Tile order (order.txt)
        Order order = Order.load(ctx);
        if (order != null) {
            root.put("order", Order.encode(order));
        }

        // 3. App library selections
        JSONArray libAdded = new JSONArray();
        for (String pkg : LibraryApps.load(ctx)) libAdded.put(pkg);
        JSONArray libRemoved = new JSONArray();
        for (String pkg : LibraryApps.loadRemoved(ctx)) libRemoved.put(pkg);
        root.put("library_added", libAdded);
        root.put("library_removed", libRemoved);

        // 4. Optional config.json overlay (includes custom remote mappings if present)
        File dir = Config.dir(ctx);
        File cfgFile = dir != null ? new File(dir, Config.FILE_NAME) : null;
        if (cfgFile != null && cfgFile.isFile() && cfgFile.length() > 0 && cfgFile.length() <= MAX_BACKUP_BYTES) {
            try {
                String rawCfg = readText(cfgFile);
                if (rawCfg != null) {
                    root.put("config_overlay", new JSONObject(rawCfg));
                }
            } catch (Exception ignored) {
            }
        }

        // 5. Optional custom remote button mappings (backward compatible optional section in v1 backups)
        SharedPreferences btnPrefs = ctx.getSharedPreferences(ButtonMapper.PREFS_NAME, Context.MODE_PRIVATE);
        JSONObject btnObj = new JSONObject();
        for (Map.Entry<String, ?> e : btnPrefs.getAll().entrySet()) {
            if (e.getKey() != null && e.getKey().startsWith("kc_") && e.getValue() instanceof String) {
                btnObj.put(e.getKey(), e.getValue());
            }
        }
        if (btnObj.length() > 0) {
            root.put("button_mappings", btnObj);
        }

        return root.toString(2);
    }

    /** Writes backup JSON to app external dir (and mirrors to Download if writable). */
    static Summary exportToDisk(Context ctx) {
        try {
            String json = exportJson(ctx);
            JSONObject parsed = new JSONObject(json);
            int settingsCount = parsed.optJSONObject("settings") != null ? parsed.optJSONObject("settings").length() : 0;
            Order order = Order.decode(parsed.optString("order", null));
            int orderCount = order != null ? order.keys.size() : 0;

            File primary = primaryFile(ctx);
            writeText(primary, json);
            File mirror = downloadMirrorFile();
            if (mirror != null && !mirror.equals(primary)) {
                try {
                    writeText(mirror, json);
                } catch (IOException ignored) {
                }
            }
            return new Summary(true, primary.getAbsolutePath(), settingsCount, orderCount, null);
        } catch (Exception e) {
            return new Summary(false, null, 0, 0, e.getMessage());
        }
    }

    /** Restores from the newest available backup file on disk. */
    static Summary restoreFromDisk(Context ctx) {
        File primary = primaryFile(ctx);
        File mirror = downloadMirrorFile();
        File src = null;
        if (primary.isFile() && primary.length() > 0) src = primary;
        if (mirror != null && mirror.isFile() && mirror.length() > 0) {
            if (src == null || mirror.lastModified() > src.lastModified()) src = mirror;
        }
        if (src == null) {
            return new Summary(false, primary.getAbsolutePath(), 0, 0, "No backup file found");
        }
        try {
            String json = readText(src);
            Summary res = importJson(ctx, json);
            return new Summary(res.ok, src.getAbsolutePath(), res.settingCount, res.orderCount, res.error);
        } catch (Exception e) {
            return new Summary(false, src.getAbsolutePath(), 0, 0, e.getMessage());
        }
    }

    /** Validates and applies a backup JSON payload safely without corrupting existing remote configs. */
    static Summary importJson(Context ctx, String rawJson) {
        if (rawJson == null || rawJson.trim().isEmpty()) {
            return new Summary(false, null, 0, 0, "Empty backup payload");
        }
        try {
            JSONObject root = new JSONObject(rawJson);
            int schema = root.optInt("schema", -1);
            if (schema != SCHEMA_VERSION) {
                return new Summary(false, null, 0, 0, "Unsupported backup version");
            }

            // Validate order before mutating anything.
            Order decodedOrder = null;
            if (root.has("order") && !root.isNull("order")) {
                decodedOrder = Order.decode(root.optString("order", ""));
                if (decodedOrder == null) {
                    return new Summary(false, null, 0, 0, "Invalid tile order in backup");
                }
            }

            // 1. Restore launcher_settings safely (merge/apply validated entries)
            int restoredSettings = 0;
            JSONObject settingsObj = root.optJSONObject("settings");
            JSONObject typesObj = root.optJSONObject("setting_types");
            SharedPreferences prefs = ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
            SharedPreferences.Editor ed = prefs.edit();
            if (settingsObj != null) {
                Iterator<String> it = settingsObj.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    if (k == null || k.isEmpty() || Config.SETTINGS_REVISION_KEY.equals(k)) continue;
                    String type = typesObj != null ? typesObj.optString(k, "") : "";
                    Object v = settingsObj.opt(k);
                    if (v == null || v == JSONObject.NULL) continue;
                    if ("bool".equals(type) || v instanceof Boolean) {
                        ed.putBoolean(k, settingsObj.optBoolean(k));
                        restoredSettings++;
                    } else if ("int".equals(type)) {
                        ed.putInt(k, settingsObj.optInt(k));
                        restoredSettings++;
                    } else if ("long".equals(type)) {
                        ed.putLong(k, settingsObj.optLong(k));
                        restoredSettings++;
                    } else if ("float".equals(type)) {
                        ed.putFloat(k, (float) settingsObj.optDouble(k));
                        restoredSettings++;
                    } else if (v instanceof Number) {
                        ed.putInt(k, ((Number) v).intValue());
                        restoredSettings++;
                    } else if (v instanceof String) {
                        ed.putString(k, (String) v);
                        restoredSettings++;
                    }
                }
            }

            // 2. Restore order.txt atomically
            int restoredOrder = 0;
            if (decodedOrder != null) {
                if (Order.save(Order.file(ctx), decodedOrder)) {
                    restoredOrder = decodedOrder.keys.size();
                }
            }

            // 3. Restore LibraryApps sets if present
            JSONArray libAdded = root.optJSONArray("library_added");
            if (libAdded != null) {
                for (int i = 0; i < libAdded.length(); i++) {
                    String pkg = libAdded.optString(i, "").trim();
                    if (!pkg.isEmpty()) LibraryApps.save(ctx, pkg, true);
                }
            }
            JSONArray libRemoved = root.optJSONArray("library_removed");
            if (libRemoved != null) {
                for (int i = 0; i < libRemoved.length(); i++) {
                    String pkg = libRemoved.optString(i, "").trim();
                    if (!pkg.isEmpty()) LibraryApps.save(ctx, pkg, false);
                }
            }

            // 4. Restore validated config_overlay JSON if present (preserving remote key arrays)
            JSONObject overlay = root.optJSONObject("config_overlay");
            if (overlay != null) {
                File dir = Config.dir(ctx);
                if (dir != null && (dir.isDirectory() || dir.mkdirs())) {
                    try {
                        writeText(new File(dir, Config.FILE_NAME), overlay.toString(2));
                    } catch (IOException ignored) {
                    }
                }
            }

            // 5. Restore custom remote button mappings if present in backup
            JSONObject btnObj = root.optJSONObject("button_mappings");
            if (btnObj != null) {
                Map<Integer, String> parsed = ButtonMapper.parseMappingsJson(btnObj);
                for (Map.Entry<Integer, String> entry : parsed.entrySet()) {
                    ButtonMapper.setMapping(ctx, entry.getKey(), entry.getValue());
                }
            }

            long rev = prefs.getLong(Config.SETTINGS_REVISION_KEY, 0L) + 1L;
            ed.putLong(Config.SETTINGS_REVISION_KEY, rev).apply();
            Config.invalidateCache();
            UiFeedback.refresh(ctx);
            return new Summary(true, null, restoredSettings, restoredOrder, null);
        } catch (Exception e) {
            return new Summary(false, null, 0, 0, e.getMessage());
        }
    }

    private static String readText(File f) throws IOException {
        if (f.length() > MAX_BACKUP_BYTES) throw new IOException("Backup file too large");
        try (FileInputStream in = new FileInputStream(f);
             ByteArrayOutputStream out = new ByteArrayOutputStream((int) f.length())) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void writeText(File dest, String text) throws IOException {
        File tmp = new File(dest.getParentFile(), dest.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
        if (dest.exists() && !dest.delete()) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("Cannot replace " + dest.getName());
        }
        if (!tmp.renameTo(dest)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("Cannot finalize " + dest.getName());
        }
    }
}
