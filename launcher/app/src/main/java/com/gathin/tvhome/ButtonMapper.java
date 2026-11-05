package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.Settings;
import android.view.KeyEvent;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Lightweight remote button mapper for TV remotes.
 * Allows mapping color keys (Red, Green, Yellow, Blue), Guide, TV Input, Bookmark,
 * or any custom remote button to launch apps or launcher actions.
 */
public final class ButtonMapper {
    public static final String PREFS_NAME = "button_mappings";
    public static final String ACTION_QUICK_SETTINGS = "action:quick_settings";
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

    private ButtonMapper() {}

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

    public static boolean isMapped(Context ctx, int keyCode) {
        if (isProtectedKey(keyCode)) return false;
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.contains("kc_" + keyCode);
    }

    public static String getMapping(Context ctx, int keyCode) {
        if (isProtectedKey(keyCode)) return null;
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString("kc_" + keyCode, null);
    }

    public static void setMapping(Context ctx, int keyCode, String target) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (target == null || target.isEmpty()) {
            prefs.edit().remove("kc_" + keyCode).apply();
        } else {
            prefs.edit().putString("kc_" + keyCode, target).apply();
        }
    }

    public static int getMappedCount(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        int count = 0;
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith("kc_")) count++;
        }
        return count;
    }

    public static List<Integer> getAllMappedKeys(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        List<Integer> keys = new ArrayList<>();
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith("kc_")) {
                try {
                    keys.add(Integer.parseInt(key.substring("kc_".length())));
                } catch (NumberFormatException ignored) {}
            }
        }
        return keys;
    }

    public static void clearAll(Context ctx) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply();
    }

    public static boolean handleKeyEvent(Context ctx, KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (isProtectedKey(keyCode)) return false;
        String target = getMapping(ctx, keyCode);
        if (target == null) return false;

        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
            executeTarget(ctx, target);
        }
        return true;
    }

    public static void executeTarget(Context ctx, String target) {
        if (target == null) return;
        if (target.startsWith("action:")) {
            switch (target) {
                case ACTION_QUICK_SETTINGS:
                    ctx.startActivity(new Intent(ctx, QuickSettingsActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
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
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    }
                    break;
                case ACTION_SCREENSAVER:
                    RemoteKeys.launch(ctx, "sleep");
                    break;
                case ACTION_LIBRARY:
                    if (ctx instanceof MainActivity) {
                        ((MainActivity) ctx).onLibraryRequested();
                    } else {
                        ctx.startActivity(new Intent(ctx, MainActivity.class)
                                .setAction(MainActivity.ACTION_LAUNCHER_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    }
                    break;
                case ACTION_TV_SETTINGS:
                    ctx.startActivity(SystemSettings.intent(ctx, Settings.ACTION_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    break;
                case ACTION_RECENTS:
                    // Try native Android Recents overview first via the accessibility service;
                    // fall back to the custom CleanYourTV switcher.
                    if (!HomeKeyService.isServiceBound() || !HomeKeyService.tryNativeRecents()) {
                        ctx.startActivity(new Intent(ctx, RecentAppsActivity.class)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
                    }
                    break;
            }
        } else {
            RemoteKeys.launch(ctx, target);
        }
    }

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

    public static String getTargetLabel(Context ctx, String target) {
        if (target == null) return ctx.getString(R.string.button_mapper_reset);
        switch (target) {
            case ACTION_QUICK_SETTINGS: return ctx.getString(R.string.button_mapper_action_quick_settings);
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
                items[i + 1] = label + "  →  " + getTargetLabel(activity, mapping);
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

    public static void showActionPickerForKey(Activity activity, int keyCode, Runnable onUpdated) {
        String keyLabel = getKeyLabel(keyCode);
        String current = getMapping(activity, keyCode);

        String[] options = {
            activity.getString(R.string.button_mapper_reset),
            activity.getString(R.string.button_mapper_action_quick_settings),
            activity.getString(R.string.button_mapper_action_recents),
            activity.getString(R.string.button_mapper_action_inputs),
            activity.getString(R.string.button_mapper_action_reorder),
            activity.getString(R.string.button_mapper_action_screensaver),
            activity.getString(R.string.button_mapper_action_library),
            activity.getString(R.string.button_mapper_action_tv_settings),
            activity.getString(R.string.button_mapper_launch_app)
        };

        new AlertDialog.Builder(activity)
                .setTitle(keyLabel + (current != null ? " (" + getTargetLabel(activity, current) + ")" : ""))
                .setItems(options, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            setMapping(activity, keyCode, null);
                            Toast.makeText(activity, keyLabel + " → " + activity.getString(R.string.button_mapper_reset), Toast.LENGTH_SHORT).show();
                            if (onUpdated != null) onUpdated.run();
                            break;
                        case 1:
                            setMapping(activity, keyCode, ACTION_QUICK_SETTINGS);
                            Toast.makeText(activity, keyLabel + " → " + activity.getString(R.string.button_mapper_action_quick_settings), Toast.LENGTH_SHORT).show();
                            if (onUpdated != null) onUpdated.run();
                            break;
                        case 2:
                            setMapping(activity, keyCode, ACTION_RECENTS);
                            Toast.makeText(activity, keyLabel + " → " + activity.getString(R.string.button_mapper_action_recents), Toast.LENGTH_SHORT).show();
                            if (onUpdated != null) onUpdated.run();
                            break;
                        case 3:
                            setMapping(activity, keyCode, ACTION_INPUTS);
                            Toast.makeText(activity, keyLabel + " → " + activity.getString(R.string.button_mapper_action_inputs), Toast.LENGTH_SHORT).show();
                            if (onUpdated != null) onUpdated.run();
                            break;
                        case 4:
                            setMapping(activity, keyCode, ACTION_REORDER);
                            Toast.makeText(activity, keyLabel + " → " + activity.getString(R.string.button_mapper_action_reorder), Toast.LENGTH_SHORT).show();
                            if (onUpdated != null) onUpdated.run();
                            break;
                        case 5:
                            setMapping(activity, keyCode, ACTION_SCREENSAVER);
                            Toast.makeText(activity, keyLabel + " → " + activity.getString(R.string.button_mapper_action_screensaver), Toast.LENGTH_SHORT).show();
                            if (onUpdated != null) onUpdated.run();
                            break;
                        case 6:
                            setMapping(activity, keyCode, ACTION_LIBRARY);
                            Toast.makeText(activity, keyLabel + " → " + activity.getString(R.string.button_mapper_action_library), Toast.LENGTH_SHORT).show();
                            if (onUpdated != null) onUpdated.run();
                            break;
                        case 7:
                            setMapping(activity, keyCode, ACTION_TV_SETTINGS);
                            Toast.makeText(activity, keyLabel + " → " + activity.getString(R.string.button_mapper_action_tv_settings), Toast.LENGTH_SHORT).show();
                            if (onUpdated != null) onUpdated.run();
                            break;
                        case 8:
                            showAppPickerForKey(activity, keyCode, onUpdated);
                            break;
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

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
                    Toast.makeText(activity, getKeyLabel(keyCode) + " → " + label, Toast.LENGTH_SHORT).show();
                    if (onUpdated != null) onUpdated.run();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
