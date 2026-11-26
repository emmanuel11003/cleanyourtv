package com.gathin.tvhome;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Environment;
import android.provider.Settings;
import android.view.KeyEvent;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lightweight remote button mapper for TV remotes.
 * Allows mapping color keys (Red, Green, Yellow, Blue), Guide, TV Input, Bookmark,
 * or any custom remote button to launch apps or launcher actions.
 */
public final class ButtonMapper {
    public static final String PREFS_NAME = "button_mappings";
    private static final String KEY_USER_CLEARED = "_user_cleared";
    private static final String BACKUP_FILE_NAME = "button_mappings.json";
    private static final String PUBLIC_BACKUP_DIR = "CleanYourTV";
    private static final Object BACKUP_LOCK = new Object();
    private static volatile boolean restoredChecked;
    private static volatile Map<Integer, String> cachedMappings;
    public static final String ACTION_QUICK_SETTINGS = "action:quick_settings";
    public static final String ACTION_NOTIFICATIONS = "action:notifications";
    public static final String ACTION_INPUTS = "action:inputs";
    public static final String ACTION_REORDER = "action:reorder";
    public static final String ACTION_SCREENSAVER = "action:screensaver";
    public static final String ACTION_LIBRARY = "action:library";
    public static final String ACTION_TV_SETTINGS = "action:tv_settings";
    public static final String ACTION_RECENTS = "action:recents";

    public static final int[] COMMON_KEYS = {
        KeyEvent.KEYCODE_APP_SWITCH,
        KeyEvent.KEYCODE_PROG_RED,
        KeyEvent.KEYCODE_PROG_GREEN,
        KeyEvent.KEYCODE_PROG_YELLOW,
        KeyEvent.KEYCODE_PROG_BLUE,
        KeyEvent.KEYCODE_GUIDE,
        KeyEvent.KEYCODE_TV_INPUT,
        KeyEvent.KEYCODE_BOOKMARK,
        KeyEvent.KEYCODE_INFO,
        KeyEvent.KEYCODE_SETTINGS,
        KeyEvent.KEYCODE_MENU,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
        KeyEvent.KEYCODE_MEDIA_REWIND,
        KeyEvent.KEYCODE_CHANNEL_UP,
        KeyEvent.KEYCODE_CHANNEL_DOWN,
        KeyEvent.KEYCODE_HELP
    };

    private static int lastHandledKeyCode = -1;
    private static long lastHandledEventTime = -1;

    private ButtonMapper() {}

    /** Navigation, Home, Volume, and Power keys stay protected so the remote cannot lock the user out. */
    public static boolean isProtectedKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_HOME:
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
            case KeyEvent.KEYCODE_POWER:
                return true;
            default:
                return false;
        }
    }

    /** True when the given key code has a non-protected custom mapping stored. */
    public static boolean isMapped(Context ctx, int keyCode) {
        if (isProtectedKey(keyCode)) return false;
        return getCachedMappings(ctx).containsKey(keyCode);
    }

    /** Returns the mapped target package or action string for a key code, or null when unmapped. */
    public static String getMapping(Context ctx, int keyCode) {
        if (isProtectedKey(keyCode)) return null;
        return getCachedMappings(ctx).get(keyCode);
    }

    /** Stores or clears a custom target for a key code and persists the external backup. */
    public static void setMapping(Context ctx, int keyCode, String target) {
        ensureRestored(ctx);
        final Map<Integer, String> snapshot;
        synchronized (BACKUP_LOCK) {
            SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit().putBoolean(KEY_USER_CLEARED, false);
            if (target == null || target.isEmpty()) {
                editor.remove("kc_" + keyCode);
            } else {
                editor.putString("kc_" + keyCode, target);
            }
            editor.apply();
            snapshot = Collections.unmodifiableMap(collectMappings(prefs));
            cachedMappings = snapshot;
        }
        final Context appCtx = ctx.getApplicationContext();
        LauncherExecutors.io().execute(() -> {
            synchronized (BACKUP_LOCK) {
                writeBackupMappings(appCtx, snapshot);
            }
        });
    }

    /** Counts how many keys currently have custom mappings saved. */
    public static int getMappedCount(Context ctx) {
        return getCachedMappings(ctx).size();
    }

    /** Lists all key codes that currently have custom mappings saved. */
    public static List<Integer> getAllMappedKeys(Context ctx) {
        return new ArrayList<>(getCachedMappings(ctx).keySet());
    }

    /** Removes every custom button mapping from preferences and clears external backups. */
    public static void clearAll(Context ctx) {
        synchronized (BACKUP_LOCK) {
            restoredChecked = true;
            cachedMappings = Collections.emptyMap();
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .clear()
                    .putBoolean(KEY_USER_CLEARED, true)
                    .apply();
        }
        final Context appCtx = ctx.getApplicationContext();
        LauncherExecutors.io().execute(() -> {
            synchronized (BACKUP_LOCK) {
                writeBackupMappings(appCtx, Collections.emptyMap());
            }
        });
    }

    private static Map<Integer, String> getCachedMappings(Context ctx) {
        Map<Integer, String> map = cachedMappings;
        if (map != null) return map;
        if (ctx == null) return Collections.emptyMap();
        ensureRestored(ctx);
        synchronized (BACKUP_LOCK) {
            if (cachedMappings != null) return cachedMappings;
            SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            Map<Integer, String> loaded = Collections.unmodifiableMap(collectMappings(prefs));
            cachedMappings = loaded;
            return loaded;
        }
    }

    private static void ensureRestored(Context ctx) {
        if (ctx == null || restoredChecked) return;
        synchronized (BACKUP_LOCK) {
            if (restoredChecked) return;
            SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            if (hasStoredMappings(prefs) || prefs.getBoolean(KEY_USER_CLEARED, false)) {
                restoredChecked = true;
                return;
            }
            Map<Integer, String> restored = readBackupMappings(ctx);
            if (!restored.isEmpty()) {
                SharedPreferences.Editor editor = prefs.edit().putBoolean(KEY_USER_CLEARED, false);
                for (Map.Entry<Integer, String> entry : restored.entrySet()) {
                    editor.putString("kc_" + entry.getKey(), entry.getValue());
                }
                editor.apply();
            }
            restoredChecked = true;
        }
    }

    private static boolean hasStoredMappings(SharedPreferences prefs) {
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith("kc_")) return true;
        }
        return false;
    }

    private static Map<Integer, String> collectMappings(SharedPreferences prefs) {
        Map<Integer, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith("kc_") || !(entry.getValue() instanceof String)) continue;
            try {
                int kc = Integer.parseInt(key.substring("kc_".length()));
                String target = (String) entry.getValue();
                if (!target.isEmpty() && !isProtectedKey(kc)) out.put(kc, target);
            } catch (NumberFormatException ignored) {}
        }
        return out;
    }

    private static List<File> backupFiles(Context ctx) {
        List<File> files = new ArrayList<>(2);
        File appDir = Config.dir(ctx);
        if (appDir != null) files.add(new File(appDir, BACKUP_FILE_NAME));
        try {
            File extRoot = Environment.getExternalStorageDirectory();
            if (extRoot != null) files.add(new File(new File(extRoot, PUBLIC_BACKUP_DIR), BACKUP_FILE_NAME));
        } catch (Throwable ignored) {}
        return files;
    }

    private static Map<Integer, String> readBackupMappings(Context ctx) {
        for (File file : backupFiles(ctx)) {
            JSONObject obj = readJson(file);
            Map<Integer, String> parsed = parseMappingsJson(obj);
            if (!parsed.isEmpty()) return parsed;
        }
        File appDir = Config.dir(ctx);
        if (appDir != null) {
            JSONObject cfgJson = readJson(new File(appDir, Config.FILE_NAME));
            if (cfgJson != null) {
                Map<Integer, String> parsed = parseMappingsJson(cfgJson.optJSONObject(PREFS_NAME));
                if (!parsed.isEmpty()) return parsed;
            }
        }
        return Collections.emptyMap();
    }

    static Map<Integer, String> parseMappingsJson(JSONObject obj) {
        if (obj == null) return Collections.emptyMap();
        Map<Integer, String> out = new LinkedHashMap<>();
        Iterator<String> keys = obj.keys();
        while (keys.hasNext()) {
            String rawKey = keys.next();
            String normalized = rawKey.startsWith("kc_") ? rawKey.substring("kc_".length()) : rawKey;
            try {
                int kc = Integer.parseInt(normalized.trim());
                if (isProtectedKey(kc)) continue;
                Object val = obj.opt(rawKey);
                if (val instanceof String) {
                    String target = ((String) val).trim();
                    if (!target.isEmpty()) out.put(kc, target);
                }
            } catch (NumberFormatException ignored) {}
        }
        return out;
    }

    private static void writeBackupMappings(Context ctx, Map<Integer, String> mappings) {
        JSONObject obj = new JSONObject();
        try {
            for (Map.Entry<Integer, String> entry : mappings.entrySet()) {
                obj.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        } catch (Throwable ignored) {
            return;
        }
        byte[] data = obj.toString().getBytes(StandardCharsets.UTF_8);
        for (File target : backupFiles(ctx)) {
            writeBytes(target, data);
        }
    }

    private static JSONObject readJson(File file) {
        if (file == null || !file.isFile() || file.length() > 64 * 1024) return null;
        try (InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[(int) file.length()];
            int n = 0, r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
            String text = new String(buf, 0, n, StandardCharsets.UTF_8);
            if (text.startsWith("\uFEFF")) text = text.substring(1);
            return new JSONObject(text);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void writeBytes(File file, byte[] data) {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(data);
                out.flush();
            }
            if (!tmp.renameTo(file)) tmp.delete();
        } catch (Throwable ignored) {}
    }

    /** Intercepts a mapped key event and executes its configured target once per release across services. */
    public static boolean handleKeyEvent(Context ctx, KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (isProtectedKey(keyCode)) return false;
        String target = getMapping(ctx, keyCode);
        if (target == null) return false;

        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
            long eventTime = event.getEventTime();
            if (keyCode != lastHandledKeyCode || eventTime != lastHandledEventTime) {
                lastHandledKeyCode = keyCode;
                lastHandledEventTime = eventTime;
                executeTarget(ctx, target);
            }
        }
        return true;
    }

    /** Executes an action constant or launches the target application package. */
    public static void executeTarget(Context ctx, String target) {
        if (target == null) return;
        if (target.startsWith("action:")) {
            switch (target) {
                case ACTION_QUICK_SETTINGS:
                    try {
                        ctx.startActivity(new Intent(ctx, QuickSettingsActivity.class)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                                ActivityOptions.makeCustomAnimation(ctx, R.anim.app_open_enter, R.anim.app_open_exit).toBundle());
                    } catch (ActivityNotFoundException | SecurityException e) {
                        Toast.makeText(ctx, R.string.not_available, Toast.LENGTH_SHORT).show();
                    }
                    break;
                case ACTION_NOTIFICATIONS:
                    try {
                        ctx.startActivity(new Intent(ctx, NotificationPanelActivity.class)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                                ActivityOptions.makeCustomAnimation(ctx, R.anim.app_open_enter, R.anim.app_open_exit).toBundle());
                    } catch (ActivityNotFoundException | SecurityException e) {
                        Toast.makeText(ctx, R.string.not_available, Toast.LENGTH_SHORT).show();
                    }
                    break;
                case ACTION_INPUTS:
                    RemoteKeys.launch(ctx, RemoteKeys.INPUTS);
                    break;
                case ACTION_REORDER:
                    if (ctx instanceof MainActivity) {
                        ((MainActivity) ctx).toggleReorder();
                    } else {
                        ctx.startActivity(new Intent(ctx, MainActivity.class)
                                .setAction(MainActivity.ACTION_REORDER)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
                    }
                    break;
                case ACTION_SCREENSAVER:
                    RemoteKeys.launch(ctx, "screensaver");
                    break;
                case ACTION_LIBRARY:
                    if (ctx instanceof MainActivity) {
                        ((MainActivity) ctx).onLibraryRequested();
                    } else {
                        ctx.startActivity(new Intent(ctx, MainActivity.class)
                                .setAction(MainActivity.ACTION_APP_LIBRARY)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
                    }
                    break;
                case ACTION_TV_SETTINGS:
                    try {
                        ctx.startActivity(SystemSettings.intent(ctx, Settings.ACTION_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (ActivityNotFoundException | SecurityException e) {
                        Toast.makeText(ctx, R.string.not_available, Toast.LENGTH_SHORT).show();
                    }
                    break;
                case ACTION_RECENTS:
                    RemoteKeys.launch(ctx, "recents");
                    break;
            }
        } else {
            RemoteKeys.launch(ctx, target);
        }
    }

    /** Returns a friendly display label for a key code. */
    public static String getKeyLabel(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_APP_SWITCH: return "App Switch / Recents";
            case KeyEvent.KEYCODE_PROG_RED: return "Red Button";
            case KeyEvent.KEYCODE_PROG_GREEN: return "Green Button";
            case KeyEvent.KEYCODE_PROG_YELLOW: return "Yellow Button";
            case KeyEvent.KEYCODE_PROG_BLUE: return "Blue Button";
            case KeyEvent.KEYCODE_GUIDE: return "Guide / EPG";
            case KeyEvent.KEYCODE_BOOKMARK:
            case 207: // KEYCODE_FAVORITES
                return "Bookmark / Favorite";
            case KeyEvent.KEYCODE_HELP: return "Help";
            case KeyEvent.KEYCODE_INFO: return "Info";
            case KeyEvent.KEYCODE_TV_INPUT: return "TV Input / Source";
            case KeyEvent.KEYCODE_SETTINGS:
            case KeyEvent.KEYCODE_NOTIFICATION:
                return "Settings Button";
            case KeyEvent.KEYCODE_MENU: return "Menu Button";
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: return "Play / Pause";
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: return "Fast Forward";
            case KeyEvent.KEYCODE_MEDIA_REWIND: return "Rewind";
            case KeyEvent.KEYCODE_CHANNEL_UP: return "Channel Up";
            case KeyEvent.KEYCODE_CHANNEL_DOWN: return "Channel Down";
            default:
                String name = KeyEvent.keyCodeToString(keyCode);
                if (name != null && name.startsWith("KEYCODE_")) {
                    name = name.substring("KEYCODE_".length()).replace('_', ' ');
                }
                return name != null ? name + " (" + keyCode + ")" : "Key " + keyCode;
        }
    }

    /** Returns the user-visible label for an action constant or installed package name. */
    public static String getTargetLabel(Context ctx, String target) {
        if (target == null) return ctx.getString(R.string.button_mapper_reset);
        switch (target) {
            case ACTION_QUICK_SETTINGS: return ctx.getString(R.string.button_mapper_action_quick_settings);
            case ACTION_NOTIFICATIONS: return ctx.getString(R.string.button_mapper_action_notifications);
            case ACTION_INPUTS: return ctx.getString(R.string.button_mapper_action_inputs);
            case ACTION_REORDER: return ctx.getString(R.string.button_mapper_action_reorder);
            case ACTION_SCREENSAVER: return ctx.getString(R.string.button_mapper_action_screensaver);
            case ACTION_LIBRARY: return ctx.getString(R.string.button_mapper_action_library);
            case ACTION_TV_SETTINGS: return ctx.getString(R.string.button_mapper_action_tv_settings);
            case ACTION_RECENTS: return ctx.getString(R.string.button_mapper_action_recents);
            default:
                try {
                    PackageManager pm = ctx.getPackageManager();
                    return pm.getApplicationLabel(pm.getApplicationInfo(target, 0)).toString();
                } catch (Exception e) {
                    return target;
                }
        }
    }

    /** Displays the main Button Mapper menu listing common keys, custom mapped keys, and Clear All. */
    public static void showButtonMapperMenu(Activity activity, Runnable onUpdated) {
        List<Integer> keys = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (int k : COMMON_KEYS) {
            keys.add(k);
            seen.add(k);
        }
        for (int k : getAllMappedKeys(activity)) {
            if (!seen.contains(k)) {
                keys.add(k);
                seen.add(k);
            }
        }

        int count = getMappedCount(activity);
        boolean hasClearAll = count > 0;
        int totalItems = 1 + keys.size() + (hasClearAll ? 1 : 0);
        String[] items = new String[totalItems];
        items[0] = activity.getString(R.string.button_mapper_learn);

        for (int i = 0; i < keys.size(); i++) {
            int kc = keys.get(i);
            String mapping = getMapping(activity, kc);
            String label = getKeyLabel(kc);
            if (mapping != null) {
                items[i + 1] = label + "  \u2192  " + getTargetLabel(activity, mapping);
            } else {
                items[i + 1] = label + "  (" + activity.getString(R.string.button_mapper_reset) + ")";
            }
        }

        if (hasClearAll) {
            items[totalItems - 1] = activity.getString(R.string.button_mapper_clear_all);
        }

        new AlertDialog.Builder(activity)
                .setTitle(R.string.button_mapper)
                .setItems(items, (dialog, which) -> {
                    if (which == 0) {
                        showDetectButtonDialog(activity, onUpdated);
                    } else if (hasClearAll && which == totalItems - 1) {
                        new AlertDialog.Builder(activity)
                                .setTitle(R.string.button_mapper_clear_all)
                                .setMessage(R.string.button_mapper_clear_all_confirm)
                                .setPositiveButton(R.string.button_mapper_clear_all, (d, w) -> {
                                    clearAll(activity);
                                    Toast.makeText(activity, R.string.button_mapper_clear_all, Toast.LENGTH_SHORT).show();
                                    if (onUpdated != null) onUpdated.run();
                                })
                                .setNegativeButton(android.R.string.cancel, null)
                                .show();
                    } else {
                        int selectedKeyCode = keys.get(which - 1);
                        showActionPickerForKey(activity, selectedKeyCode, onUpdated);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Captures any non-protected remote button press and opens its action picker. */
    public static void showDetectButtonDialog(Activity activity, Runnable onUpdated) {
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.button_mapper_learn)
                .setMessage(R.string.button_mapper_learn_prompt)
                .setNegativeButton(android.R.string.cancel, null)
                .create();

        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK) return false;
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                if (isProtectedKey(keyCode)) {
                    Toast.makeText(activity, R.string.button_mapper_key_cannot_remap, Toast.LENGTH_SHORT).show();
                    return true;
                }
                d.dismiss();
                showActionPickerForKey(activity, keyCode, onUpdated);
                return true;
            }
            return true;
        });

        dialog.show();
    }

    /** Displays available launcher actions or app selection for a specific remote key code. */
    public static void showActionPickerForKey(Activity activity, int keyCode, Runnable onUpdated) {
        String keyLabel = getKeyLabel(keyCode);
        String current = getMapping(activity, keyCode);

        final String[] targets = {
            null,
            ACTION_QUICK_SETTINGS,
            ACTION_NOTIFICATIONS,
            ACTION_RECENTS,
            ACTION_INPUTS,
            ACTION_REORDER,
            ACTION_SCREENSAVER,
            ACTION_LIBRARY,
            ACTION_TV_SETTINGS
        };
        final int[] labelRes = {
            R.string.button_mapper_reset,
            R.string.button_mapper_action_quick_settings,
            R.string.button_mapper_action_notifications,
            R.string.button_mapper_action_recents,
            R.string.button_mapper_action_inputs,
            R.string.button_mapper_action_reorder,
            R.string.button_mapper_action_screensaver,
            R.string.button_mapper_action_library,
            R.string.button_mapper_action_tv_settings
        };

        String[] options = new String[labelRes.length + 1];
        for (int i = 0; i < labelRes.length; i++) {
            options[i] = activity.getString(labelRes[i]);
        }
        options[labelRes.length] = activity.getString(R.string.button_mapper_launch_app);

        new AlertDialog.Builder(activity)
                .setTitle(keyLabel + (current != null ? " (" + getTargetLabel(activity, current) + ")" : ""))
                .setItems(options, (dialog, which) -> {
                    if (which == labelRes.length) {
                        showAppPickerForKey(activity, keyCode, onUpdated);
                        return;
                    }
                    setMapping(activity, keyCode, targets[which]);
                    Toast.makeText(activity, keyLabel + " \u2192 " + options[which], Toast.LENGTH_SHORT).show();
                    if (onUpdated != null) onUpdated.run();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Displays installed TV and phone apps to assign as a remote button launch target. */
    public static void showAppPickerForKey(Activity activity, int keyCode, Runnable onUpdated) {
        PackageManager pm = activity.getPackageManager();
        List<ResolveInfo> allApps = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        Intent tvIntent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo r : pm.queryIntentActivities(tvIntent, 0)) {
            String pkg = r.activityInfo.packageName;
            if (!pkg.equals(activity.getPackageName()) && seen.add(pkg)) {
                allApps.add(r);
            }
        }

        Intent mainIntent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo r : pm.queryIntentActivities(mainIntent, 0)) {
            String pkg = r.activityInfo.packageName;
            if (!pkg.equals(activity.getPackageName()) && seen.add(pkg)) {
                allApps.add(r);
            }
        }

        Collections.sort(allApps, (a, b) ->
                a.loadLabel(pm).toString().compareToIgnoreCase(b.loadLabel(pm).toString()));

        String[] appNames = new String[allApps.size()];
        for (int i = 0; i < allApps.size(); i++) {
            appNames[i] = allApps.get(i).loadLabel(pm).toString();
        }

        new AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.button_mapper_launch_app) + " (" + getKeyLabel(keyCode) + ")")
                .setItems(appNames, (dialog, which) -> {
                    ResolveInfo chosen = allApps.get(which);
                    String pkg = chosen.activityInfo.packageName;
                    String label = chosen.loadLabel(pm).toString();
                    setMapping(activity, keyCode, pkg);
                    Toast.makeText(activity, getKeyLabel(keyCode) + " \u2192 " + label, Toast.LENGTH_SHORT).show();
                    if (onUpdated != null) onUpdated.run();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
