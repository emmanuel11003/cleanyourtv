package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Remote-friendly settings owned by the launcher; system settings stay one row away. */
public final class LauncherSettingsActivity extends Activity {
    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));   // locale
    }

    private static final int PICK_WALLPAPER_REQUEST = 43;
    private static final int PICK_SCREENSAVER_WALLPAPER_REQUEST = 44;
    private static final long MAX_WALLPAPER_BYTES = 15L * 1024L * 1024L;
    static final String EXTRA_REORDER = "com.gathin.tvhome.extra.REORDER_FROM_SETTINGS";
    private SharedPreferences choices;
    private volatile boolean wallpaperBusy;
    private final TextView[] columnChoices = new TextView[4];
    private static final int[] DOCK_OPACITIES = {100, 70, 50, 30};
    private final TextView[] dockChoices = new TextView[DOCK_OPACITIES.length];
    private static final String[] CLOCK_FORMATS = {"system", "12", "24"};
    private final TextView[] clockChoices = new TextView[CLOCK_FORMATS.length];
    private String clockFormat;
    private LinearLayout wallpaperChoice;
    private TextView wallpaperSwitch;
    private TextView wallpaperSummary;
    private LinearLayout chooseWallpaperRow;
    private LinearLayout screensaverRow;
    private TextView screensaverSwitch;
    private TextView screensaverSummary;
    private LinearLayout screensaverCalendarRow;
    private TextView screensaverCalendarSwitch;
    private LinearLayout animationsChoice;
    private TextView animationsSwitch;
    private LinearLayout advancedChoice;
    private TextView advancedSwitch;
    private LinearLayout statsRow;
    private TextView statsSwitch;
    private LinearLayout inputsPillRow;
    private TextView inputsPillSwitch;
    private LinearLayout networkPillRow;
    private TextView networkPillSwitch;
    private LinearLayout uiSoundsRow;
    private TextView uiSoundsSwitch;
    private TextView hiddenAppsSummary;
    private TextView followThemeChoice;
    private TextView openOnStartChoice;
    private int columns;
    private int dockOpacity;
    private boolean animations;
    private boolean advancedEffects;
    private boolean animateTransitions;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Config config = Config.load(this);
        setContentView(R.layout.activity_launcher_settings);
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        View reorderChoice = findViewById(R.id.reorderChoice);
        if (reorderChoice != null) {
            reorderChoice.setVisibility(View.VISIBLE);
            reorderChoice.setOnClickListener(v -> {
                setResult(RESULT_OK, new Intent().putExtra(EXTRA_REORDER, true));
                finishWithMorph();
            });
        }
        columns = config.columns;
        dockOpacity = config.dockOpacity;
        animations = config.animations;
        advancedEffects = config.animations && config.advancedEffects;

        int[] ids = {R.id.columns4, R.id.columns5, R.id.columns6, R.id.columns7};
        for (int i = 0; i < ids.length; i++) {
            final int value = i + 4;
            columnChoices[i] = findViewById(ids[i]);
            columnChoices[i].setOnClickListener(v -> {
                if (columns == value) return;
                columns = value;
                choices.edit().putInt("columns", value).apply();
                changed();
                refresh();
            });
        }
        int[] dockIds = {R.id.dock100, R.id.dock70, R.id.dock50, R.id.dock30};
        for (int i = 0; i < dockIds.length; i++) {
            final int value = DOCK_OPACITIES[i];
            dockChoices[i] = findViewById(dockIds[i]);
            dockChoices[i].setOnClickListener(v -> {
                if (dockOpacity == value) return;
                dockOpacity = value;
                choices.edit().putInt(Config.DOCK_OPACITY_KEY, value).apply();
                changed();
                refresh();
            });
        }
        // Mirror text alignment without globally opting the launcher into RTL. Numeric rows
        // still need explicit ordering in Android's no-supportsRtl compatibility mode.
        if (getResources().getBoolean(R.bool.rtl_menu_bar))
            findViewById(android.R.id.content).setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        clockFormat = config.clockFormat;
        int[] clockIds = {R.id.clockSystem, R.id.clock12, R.id.clock24};
        for (int i = 0; i < clockIds.length; i++) {
            final String value = CLOCK_FORMATS[i];
            clockChoices[i] = findViewById(clockIds[i]);
            if (clockChoices[i] != null) {
                clockChoices[i].setOnClickListener(v -> {
                    if (value.equals(clockFormat)) return;
                    clockFormat = value;
                    choices.edit().putString(Config.CLOCK_FORMAT_KEY, value).apply();
                    changed();
                    refresh();
                });
            }
        }
        if (getResources().getBoolean(R.bool.rtl_app_grid)) {
            reverseRow(columnChoices);
            reverseRow(dockChoices);
            reverseRow(clockChoices);
        }
        // 1. Wallpaper Row & Switch (Click toggles ON/OFF; dedicated row or long-press browses)
        wallpaperChoice = findViewById(R.id.wallpaperChoice);
        wallpaperSwitch = findViewById(R.id.wallpaperSwitch);
        wallpaperSummary = findViewById(R.id.wallpaperSummary);
        chooseWallpaperRow = findViewById(R.id.chooseWallpaperRow);
        View.OnClickListener wallpaperToggle = v -> {
            if (wallpaperBusy) return;
            wallpaperBusy = true;
            executor().execute(() -> {
                List<File> available = null;
                Config nextConfig = null;
                boolean modified = false;
                try {
                    Config currentConfig = Config.load(this);
                    boolean isOn = currentConfig.wallpaperEnabled && !currentConfig.wallpapers.isEmpty();
                    if (isOn) {
                        choices.edit().putString(Config.WALLPAPER_CHOICE_KEY, "none").apply();
                        nextConfig = Config.load(this);
                        modified = true;
                    } else {
                        choices.edit().remove(Config.WALLPAPER_CHOICE_KEY).apply();
                        Config reloaded = Config.load(this);
                        if (!reloaded.wallpaperEnabled || reloaded.wallpapers.isEmpty()) {
                            available = collectWallpaperFiles();
                            if (!available.isEmpty()) {
                                choices.edit().putString(Config.WALLPAPER_CHOICE_KEY,
                                        available.get(0).getAbsolutePath()).apply();
                                nextConfig = Config.load(this);
                                modified = true;
                            }
                        } else {
                            nextConfig = reloaded;
                            modified = true;
                        }
                    }
                } finally {
                    final Config resolvedConfig = nextConfig;
                    final List<File> resolvedFiles = available;
                    final boolean didModify = modified;
                    runOnUiThread(() -> {
                        wallpaperBusy = false;
                        if (isFinishing() || isDestroyed()) return;
                        if (didModify) {
                            changed();
                            refresh(resolvedConfig != null ? resolvedConfig : Config.load(this));
                        } else if (resolvedFiles != null) {
                            showWallpaperDialog(resolvedFiles);
                        }
                    });
                }
            });
        };
        if (wallpaperChoice != null) {
            wallpaperChoice.setOnClickListener(wallpaperToggle);
            wallpaperChoice.setOnLongClickListener(v -> {
                showWallpaperPicker();
                return true;
            });
        }
        if (wallpaperSwitch != null) {
            wallpaperSwitch.setOnClickListener(wallpaperToggle);
        }
        if (chooseWallpaperRow != null) {
            chooseWallpaperRow.setOnClickListener(v -> showWallpaperPicker());
        }

        // 2. Screensaver Row & Switch (Click toggles ON/OFF; long-press opens styles)
        screensaverRow = findViewById(R.id.screensaverRow);
        screensaverSwitch = findViewById(R.id.screensaverSwitch);
        screensaverSummary = findViewById(R.id.screensaverSummary);
        View.OnClickListener screensaverToggle = v -> {
            boolean ssOn = choices.getBoolean("screensaver_enabled", config.homeIdleClockMinutes > 0);
            choices.edit().putBoolean("screensaver_enabled", !ssOn).apply();
            changed();
            refresh();
        };
        if (screensaverRow != null) {
            screensaverRow.setOnClickListener(screensaverToggle);
            screensaverRow.setOnLongClickListener(v -> {
                showScreensaverPicker();
                return true;
            });
        }
        if (screensaverSwitch != null) {
            screensaverSwitch.setOnClickListener(screensaverToggle);
        }

        // 3. Screensaver Month Calendar Row & Switch
        screensaverCalendarRow = findViewById(R.id.screensaverCalendarRow);
        screensaverCalendarSwitch = findViewById(R.id.screensaverCalendarSwitch);
        View.OnClickListener calendarToggle = v -> {
            String ssStyle = choices.getString(Config.SCREENSAVER_STYLE_KEY, "calendar");
            boolean isCalendar = !"clock".equals(ssStyle);
            choices.edit().putString(Config.SCREENSAVER_STYLE_KEY, isCalendar ? "clock" : "calendar").apply();
            changed();
            refresh();
        };
        if (screensaverCalendarRow != null) {
            screensaverCalendarRow.setOnClickListener(calendarToggle);
        }
        if (screensaverCalendarSwitch != null) {
            screensaverCalendarSwitch.setOnClickListener(calendarToggle);
        }

        // 4. Animations Row & Switch
        animationsChoice = findViewById(R.id.animationsChoice);
        animationsSwitch = findViewById(R.id.animationsSwitch);
        View.OnClickListener animationsToggle = v -> {
            animations = !animations;
            if (!animations) advancedEffects = false;
            choices.edit().putBoolean("animations", animations)
                    .putBoolean("advanced_effects", advancedEffects).apply();
            changed();
            refresh();
        };
        if (animationsChoice != null) {
            animationsChoice.setOnClickListener(animationsToggle);
        }
        if (animationsSwitch != null) {
            animationsSwitch.setOnClickListener(animationsToggle);
        }

        // 5. Advanced Effects Row & Switch
        advancedChoice = findViewById(R.id.advancedChoice);
        advancedSwitch = findViewById(R.id.advancedSwitch);
        View.OnClickListener advancedToggle = v -> {
            if (!animations) {
                Toast.makeText(this, R.string.animations_summary, Toast.LENGTH_SHORT).show();
                return;
            }
            if (advancedEffects) {
                setAdvanced(false);
            } else {
                new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                        .setTitle(R.string.advanced_effects)
                        .setMessage(R.string.advanced_effects_warning)
                        .setPositiveButton(R.string.enable, (dialog, which) -> setAdvanced(true))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            }
        };
        if (advancedChoice != null) {
            advancedChoice.setOnClickListener(advancedToggle);
        }
        if (advancedSwitch != null) {
            advancedSwitch.setOnClickListener(advancedToggle);
        }

        // 6. Top Bar Stats Row & Switch
        statsRow = findViewById(R.id.statsRow);
        statsSwitch = findViewById(R.id.statsSwitch);
        View.OnClickListener statsToggle = v -> {
            boolean statsOn = choices.getBoolean("show_stats", config.showStats);
            choices.edit().putBoolean("show_stats", !statsOn).apply();
            changed();
            refresh();
        };
        if (statsRow != null) {
            statsRow.setOnClickListener(statsToggle);
        }
        if (statsSwitch != null) {
            statsSwitch.setOnClickListener(statsToggle);
        }

        // 7. Inputs Pill Row & Switch
        inputsPillRow = findViewById(R.id.inputsPillRow);
        inputsPillSwitch = findViewById(R.id.inputsPillSwitch);
        View.OnClickListener inputsToggle = v -> {
            boolean inputsOn = choices.getBoolean("show_inputs_pill", config.showInputsPill);
            choices.edit().putBoolean("show_inputs_pill", !inputsOn).apply();
            changed();
            refresh();
        };
        if (inputsPillRow != null) {
            inputsPillRow.setOnClickListener(inputsToggle);
        }
        if (inputsPillSwitch != null) {
            inputsPillSwitch.setOnClickListener(inputsToggle);
        }

        // 9. Network Pill Row & Switch
        networkPillRow = findViewById(R.id.networkPillRow);
        networkPillSwitch = findViewById(R.id.networkPillSwitch);
        View.OnClickListener networkToggle = v -> {
            boolean networkOn = choices.getBoolean("show_network_pill", config.showNetworkPill);
            choices.edit().putBoolean("show_network_pill", !networkOn).apply();
            changed();
            refresh();
        };
        if (networkPillRow != null) {
            networkPillRow.setOnClickListener(networkToggle);
        }
        if (networkPillSwitch != null) {
            networkPillSwitch.setOnClickListener(networkToggle);
        }

        // 10. Focus Parallax Tilt Row hidden (upstream animations only)
        View tiltRow = findViewById(R.id.tiltFocusRow);
        if (tiltRow != null) tiltRow.setVisibility(View.GONE);

        // 11. Navigation Click Sounds Row & Switch (Feature #10)
        uiSoundsRow = findViewById(R.id.uiSoundsRow);
        uiSoundsSwitch = findViewById(R.id.uiSoundsSwitch);
        View.OnClickListener soundsToggle = v -> {
            boolean cur = UiFeedback.isSoundsEnabled(this);
            choices.edit().putBoolean(UiFeedback.KEY_UI_SOUNDS, !cur).apply();
            UiFeedback.refresh(this);
            if (!cur) UiFeedback.playClick(v);
            changed();
            refresh();
        };
        if (uiSoundsRow != null) uiSoundsRow.setOnClickListener(soundsToggle);
        if (uiSoundsSwitch != null) uiSoundsSwitch.setOnClickListener(soundsToggle);

        // 12. Built-in Wi-Fi Wallpaper & Backup Sync (Consolidated app)
        View wifiSyncRow = findViewById(R.id.wifiSyncRow);
        if (wifiSyncRow != null) {
            wifiSyncRow.setOnClickListener(v -> startActivity(new Intent(this, WallpaperSyncActivity.class)));
        }

        // 13. Hidden Apps Manager (Feature #4)
        View hiddenAppsRow = findViewById(R.id.hiddenAppsRow);
        hiddenAppsSummary = findViewById(R.id.hiddenAppsSummary);
        if (hiddenAppsRow != null) {
            hiddenAppsRow.setOnClickListener(v -> showHiddenAppsDialog());
        }

        // 14. Backup & Restore Settings JSON (Feature #9)
        View backupRow = findViewById(R.id.backupRow);
        if (backupRow != null) {
            backupRow.setOnClickListener(v -> showBackupDialog());
        }

        openOnStartChoice = findViewById(R.id.openOnStartChoice);
        if (config.openOnStartChoice) {
            openOnStartChoice.setVisibility(View.VISIBLE);
            openOnStartChoice.setOnClickListener(v -> showOpenOnStartPicker());
        }
        followThemeChoice = findViewById(R.id.followThemeChoice);
        followThemeChoice.setOnClickListener(v -> {
            if (!hasOnTvChoices()) return;
            Config.clearOnTvChoices(choices.edit()).apply();
            Config current = Config.load(this);
            columns = current.columns;
            dockOpacity = current.dockOpacity;
            clockFormat = current.clockFormat;
            animations = current.animations;
            advancedEffects = current.animations && current.advancedEffects;
            changed();
            refresh(current);
        });
        findViewById(R.id.defaultHomeChoice).setOnClickListener(v -> {
            Toast.makeText(this, R.string.home_settings_help, Toast.LENGTH_LONG).show();
            open(new Intent(Settings.ACTION_HOME_SETTINGS));
        });
        findViewById(R.id.tvSettingsChoice).setOnClickListener(v ->
                open(SystemSettings.intent(this, Settings.ACTION_SETTINGS)));

        boolean smoothFocus = animations && !config.reducedMotion;
        animateTransitions = smoothFocus;
        Motion.applyFadeTransition(this, smoothFocus);
        int[] smoothRows = {
                R.id.reorderChoice, R.id.wallpaperChoice, R.id.chooseWallpaperRow,
                R.id.screensaverRow, R.id.screensaverCalendarRow, R.id.animationsChoice,
                R.id.advancedChoice, R.id.statsRow, R.id.inputsPillRow,
                R.id.networkPillRow, R.id.uiSoundsRow,
                R.id.wifiSyncRow, R.id.hiddenAppsRow, R.id.backupRow,
                R.id.openOnStartChoice, R.id.followThemeChoice,
                R.id.defaultHomeChoice, R.id.tvSettingsChoice
        };
        for (int id : smoothRows) {
            Motion.attachSmoothFocus(findViewById(id), smoothFocus, 1.02f);
        }
        for (TextView chip : columnChoices) Motion.attachSmoothFocus(chip, smoothFocus, 1.05f);
        for (TextView chip : dockChoices) Motion.attachSmoothFocus(chip, smoothFocus, 1.05f);
        for (TextView chip : clockChoices) Motion.attachSmoothFocus(chip, smoothFocus, 1.05f);
        refresh(config);
        if (config.launcherSettingsButton && reorderChoice != null) reorderChoice.requestFocus();
        else columnChoices[Math.max(0, Math.min(columns - 4, 3))].requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    private void reverseRow(TextView[] views) {
        LinearLayout row = (LinearLayout) views[0].getParent();
        row.removeAllViews();
        for (int i = views.length - 1; i >= 0; i--) row.addView(views[i]);
    }

    private ExecutorService executor() {
        return LauncherExecutors.io();
    }

    private List<File> collectWallpaperFiles() {
        File dir = Config.dir(this);
        List<File> images = new java.util.ArrayList<>(WallpaperRotation.images(
                dir == null ? null : new File(dir, "wallpapers")));
        File custom = dir == null ? null : new File(dir, "custom_wallpaper.jpg");
        if (custom != null && custom.isFile() && !images.contains(custom)) images.add(0, custom);
        File single = dir == null ? null : new File(dir, Config.WALLPAPER_NAME);
        if (single != null && single.isFile() && !images.contains(single)) images.add(0, single);
        File customSs = dir == null ? null : new File(dir, "custom_screensaver_wallpaper.jpg");
        if (customSs != null && customSs.isFile() && !images.contains(customSs)) images.add(0, customSs);
        File bridgeSs = dir == null ? null : new File(dir, "screensaver_wallpaper.jpg");
        if (bridgeSs != null && bridgeSs.isFile() && !images.contains(bridgeSs)) images.add(0, bridgeSs);

        File extRoot = Environment.getExternalStorageDirectory();
        scanImages(new File(extRoot, "Pictures"), images);
        scanImages(new File(extRoot, "Download"), images);
        scanImages(new File(extRoot, "Wallpapers"), images);
        if (dir != null) scanImages(new File(dir, "custom"), images);
        return images;
    }

    private Intent wallpaperSyncIntent() {
        return new Intent(this, WallpaperSyncActivity.class);
    }

    private void showHiddenAppsDialog() {
        java.util.Set<String> hidden = AppCustomizations.loadHidden(this);
        if (hidden.isEmpty()) {
            Toast.makeText(this, R.string.hidden_apps_none, Toast.LENGTH_SHORT).show();
            return;
        }
        List<String> pkgs = new ArrayList<>(hidden);
        Collections.sort(pkgs);
        PackageManager pm = getPackageManager();
        String[] labels = new String[pkgs.size()];
        for (int i = 0; i < pkgs.size(); i++) {
            String p = pkgs.get(i);
            try {
                labels[i] = pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString();
            } catch (Exception e) {
                labels[i] = p;
            }
        }
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(R.string.hidden_apps_label)
                .setItems(labels, (d, which) -> {
                    String pkg = pkgs.get(which);
                    if (AppCustomizations.setHidden(this, pkg, false)) {
                        changed();
                        Toast.makeText(this, getString(R.string.unhide_toast, labels[which]), Toast.LENGTH_SHORT).show();
                        refresh();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showBackupDialog() {
        String[] actions = {
                getString(R.string.backup_export),
                getString(R.string.backup_restore),
                getString(R.string.wallpaper_sync_open)
        };
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(R.string.backup_label)
                .setItems(actions, (d, which) -> {
                    if (which == 0) {
                        executor().execute(() -> {
                            SettingsBackup.Summary s = SettingsBackup.exportToDisk(this);
                            runOnUiThread(() -> {
                                if (isFinishing() || isDestroyed()) return;
                                Toast.makeText(this,
                                        s.ok ? getString(R.string.backup_saved, s.settingCount, s.orderCount)
                                                : getString(R.string.backup_failed, s.error),
                                        Toast.LENGTH_LONG).show();
                            });
                        });
                    } else if (which == 1) {
                        executor().execute(() -> {
                            SettingsBackup.Summary s = SettingsBackup.restoreFromDisk(this);
                            runOnUiThread(() -> {
                                if (isFinishing() || isDestroyed()) return;
                                if (s.ok) {
                                    Config cur = Config.load(this);
                                    columns = cur.columns;
                                    dockOpacity = cur.dockOpacity;
                                    clockFormat = cur.clockFormat;
                                    animations = cur.animations;
                                    advancedEffects = cur.animations && cur.advancedEffects;
                                    changed();
                                    refresh(cur);
                                    Toast.makeText(this, getString(R.string.backup_restored, s.settingCount, s.orderCount),
                                            Toast.LENGTH_LONG).show();
                                } else {
                                    Toast.makeText(this, getString(R.string.backup_failed, s.error),
                                            Toast.LENGTH_LONG).show();
                                }
                            });
                        });
                    } else {
                        startActivity(new Intent(this, WallpaperSyncActivity.class));
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showWallpaperPicker() {
        showWallpaperPicker(false);
    }

    private void showWallpaperPicker(final boolean forScreensaver) {
        if (wallpaperBusy) return;
        wallpaperBusy = true;
        executor().execute(() -> {
            List<File> images = null;
            try {
                images = collectWallpaperFiles();
            } finally {
                final List<File> resolved = images != null ? images : Collections.emptyList();
                runOnUiThread(() -> {
                    wallpaperBusy = false;
                    if (isFinishing() || isDestroyed()) return;
                    showWallpaperDialog(resolved, forScreensaver);
                });
            }
        });
    }

    private void showWallpaperDialog(List<File> images) {
        showWallpaperDialog(images, false);
    }

    private void showWallpaperDialog(List<File> images, boolean forScreensaver) {
        String current = forScreensaver
                ? choices.getString(Config.SCREENSAVER_WALLPAPER_KEY, null)
                : choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        Intent syncIntent = forScreensaver ? null : wallpaperSyncIntent();
        int prefixCount = syncIntent != null ? 2 : 1;
        int tailCount = forScreensaver ? 0 : 1;
        int totalItems = prefixCount + images.size() + tailCount;
        String[] labels = new String[totalItems];
        labels[0] = getString(R.string.wallpaper_custom_browse);
        if (syncIntent != null) {
            labels[1] = getString(R.string.wallpaper_sync_open);
        }
        for (int i = 0; i < images.size(); i++) {
            File f = images.get(i);
            String parent = f.getParentFile() != null ? f.getParentFile().getName() : "";
            if ("wallpapers".equalsIgnoreCase(parent)) labels[i + prefixCount] = f.getName() + " (Theme)";
            else if ("Pictures".equalsIgnoreCase(parent)) labels[i + prefixCount] = f.getName() + " (Pictures)";
            else if ("Download".equalsIgnoreCase(parent)) labels[i + prefixCount] = f.getName() + " (Download)";
            else if ("Wallpapers".equalsIgnoreCase(parent)) labels[i + prefixCount] = f.getName() + " (Wallpapers)";
            else labels[i + prefixCount] = f.getName();
        }
        if (!forScreensaver) {
            labels[totalItems - 1] = getString(R.string.wallpaper_none);
        }

        if (images.isEmpty() && syncIntent == null) {
            Toast.makeText(this, R.string.wallpaper_folder_hint, Toast.LENGTH_LONG).show();
        }

        int checked = -1;
        if (!forScreensaver && "none".equals(current)) {
            checked = totalItems - 1;
        } else if (current != null) {
            int idx = indexOf(images, current);
            if (idx >= 0) checked = idx + prefixCount;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(forScreensaver ? R.string.screensaver_label : R.string.wallpaper_label)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    if (which == 0) {
                        Intent intent = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*");
                        int req = forScreensaver ? PICK_SCREENSAVER_WALLPAPER_REQUEST : PICK_WALLPAPER_REQUEST;
                        try {
                            startActivityForResult(Intent.createChooser(intent, getString(R.string.wallpaper_label)), req);
                        } catch (ActivityNotFoundException e) {
                            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
                        }
                    } else if (syncIntent != null && which == 1) {
                        try {
                            startActivity(syncIntent);
                        } catch (ActivityNotFoundException | SecurityException e) {
                            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
                        }
                    } else if (!forScreensaver && which == totalItems - 1) {
                        choices.edit().putString(Config.WALLPAPER_CHOICE_KEY, "none").apply();
                        changed();
                        refresh();
                    } else {
                        File picked = images.get(which - prefixCount);
                        if (forScreensaver) {
                            choices.edit()
                                    .putString(Config.SCREENSAVER_WALLPAPER_KEY, picked.getAbsolutePath())
                                    .putString(Config.SCREENSAVER_BG_KEY, "wallpaper")
                                    .apply();
                        } else {
                            choices.edit().putString(Config.WALLPAPER_CHOICE_KEY, picked.getAbsolutePath()).apply();
                        }
                        changed();
                        Toast.makeText(this, R.string.wallpaper_applied, Toast.LENGTH_SHORT).show();
                        refresh();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null);
        if (!forScreensaver) {
            builder.setNeutralButton(R.string.wallpaper_theme_default, (dialog, which) -> {
                choices.edit().remove(Config.WALLPAPER_CHOICE_KEY).apply();
                changed();
                refresh();
            });
        }
        builder.show();
    }

    private void showScreensaverPicker() {
        Config cfg = Config.load(this);
        String defaultBg = cfg.clockWallpaper ? "wallpaper" : (cfg.clockGlow ? "glow" : "black");
        String currentStyle = choices.getString(Config.SCREENSAVER_STYLE_KEY, "calendar");
        String currentBg = choices.getString(Config.SCREENSAVER_BG_KEY, defaultBg);
        boolean hasCustomSs = choices.contains(Config.SCREENSAVER_WALLPAPER_KEY);

        List<String> list = new ArrayList<>();
        list.add(getString(R.string.screensaver_style_calendar) + ("calendar".equals(currentStyle) ? "  ✓" : ""));
        list.add(getString(R.string.screensaver_style_clock) + ("clock".equals(currentStyle) ? "  ✓" : ""));
        list.add(getString(R.string.screensaver_bg_wallpaper) + ("wallpaper".equals(currentBg) ? "  ✓" : ""));
        list.add(getString(R.string.screensaver_bg_glow) + ("glow".equals(currentBg) ? "  ✓" : ""));
        list.add(getString(R.string.screensaver_bg_black) + ("black".equals(currentBg) ? "  ✓" : ""));
        list.add("Choose screensaver wallpaper…");
        if (hasCustomSs) {
            list.add("Clear custom screensaver wallpaper");
        }
        String[] items = list.toArray(new String[0]);

        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(R.string.screensaver_label)
                .setItems(items, (dialog, which) -> {
                    dialog.dismiss();
                    if (which == 0) {
                        choices.edit().putString(Config.SCREENSAVER_STYLE_KEY, "calendar").apply();
                    } else if (which == 1) {
                        choices.edit().putString(Config.SCREENSAVER_STYLE_KEY, "clock").apply();
                    } else if (which == 2) {
                        choices.edit().putString(Config.SCREENSAVER_BG_KEY, "wallpaper").apply();
                    } else if (which == 3) {
                        choices.edit().putString(Config.SCREENSAVER_BG_KEY, "glow").apply();
                    } else if (which == 4) {
                        choices.edit().putString(Config.SCREENSAVER_BG_KEY, "black").apply();
                    } else if (which == 5) {
                        showWallpaperPicker(true);
                        return;
                    } else if (hasCustomSs && which == 6) {
                        choices.edit()
                                .remove(Config.SCREENSAVER_WALLPAPER_KEY)
                                .remove(Config.SCREENSAVER_BG_KEY)
                                .apply();
                    }
                    changed();
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void scanImages(File folder, List<File> list) {
        if (folder == null || !folder.isDirectory()) return;
        File[] files = folder.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isFile() && f.length() > 0 && f.length() <= 15 * 1024 * 1024) {
                String name = f.getName().toLowerCase(Locale.ROOT);
                if (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".webp")) {
                    if (!list.contains(f)) list.add(f);
                }
            }
        }
    }

    /** Off, then every app with a TV entry point by name. Saved at once; it is read on the next boot. */
    private void showOpenOnStartPicker() {
        PackageManager pm = getPackageManager();
        List<String[]> apps = new ArrayList<>();
        Intent tv = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo r : pm.queryIntentActivities(tv, 0)) {
            String pkg = r.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            boolean seen = false;
            for (String[] a : apps) seen |= a[1].equals(pkg);
            if (!seen) apps.add(new String[]{r.loadLabel(pm).toString(), pkg});
        }
        Collections.sort(apps, (a, b) -> a[0].compareToIgnoreCase(b[0]));
        String[] labels = new String[apps.size() + 1];
        labels[0] = getString(R.string.off);
        String current = choices.getString(OpenOnStart.KEY, "");
        int checked = 0;
        for (int i = 0; i < apps.size(); i++) {
            labels[i + 1] = apps.get(i)[0];
            if (apps.get(i)[1].equals(current)) checked = i + 1;
        }
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(R.string.open_on_start_label)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    if (which == 0) choices.edit().remove(OpenOnStart.KEY).apply();
                    else {
                        OpenOnStart.skipThisBoot(this);
                        choices.edit().putString(OpenOnStart.KEY, apps.get(which - 1)[1]).apply();
                    }
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** The picked app's name, its package when it is gone, or Off. */
    private String openOnStartLabel() {
        String pkg = choices.getString(OpenOnStart.KEY, "");
        if (pkg.isEmpty()) return getString(R.string.off);
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if ((requestCode == PICK_WALLPAPER_REQUEST || requestCode == PICK_SCREENSAVER_WALLPAPER_REQUEST)
                && resultCode == RESULT_OK && data != null && data.getData() != null) {
            final android.net.Uri sourceUri = data.getData();
            final File dir = Config.dir(this);
            if (dir == null) return;
            final boolean forScreensaver = (requestCode == PICK_SCREENSAVER_WALLPAPER_REQUEST);
            executor().execute(() -> copyCustomWallpaper(sourceUri, dir, forScreensaver));
        }
    }

    private void copyCustomWallpaper(android.net.Uri sourceUri, File dir, boolean forScreensaver) {
        String name = forScreensaver ? "custom_screensaver_wallpaper.jpg" : "custom_wallpaper.jpg";
        File customFile = new File(dir, name);
        File tmpFile = new File(dir, name + ".tmp");
        try {
            try (InputStream in = getContentResolver().openInputStream(sourceUri)) {
                if (in == null) {
                    throw new IOException(getString(R.string.wallpaper_open_error));
                }
                try (FileOutputStream out = new FileOutputStream(tmpFile)) {
                    byte[] buf = new byte[8192];
                    long total = 0L;
                    int len;
                    while ((len = in.read(buf)) > 0) {
                        total += len;
                        if (total > MAX_WALLPAPER_BYTES) {
                            throw new IOException(getString(R.string.wallpaper_size_limit));
                        }
                        out.write(buf, 0, len);
                    }
                    out.flush();
                }
            }
            if (customFile.exists() && !customFile.delete()) {
                throw new IOException(getString(R.string.wallpaper_replace_error));
            }
            if (!tmpFile.renameTo(customFile)) {
                throw new IOException(getString(R.string.wallpaper_finalize_error));
            }
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (forScreensaver) {
                    choices.edit()
                            .putString(Config.SCREENSAVER_WALLPAPER_KEY, customFile.getAbsolutePath())
                            .putString(Config.SCREENSAVER_BG_KEY, "wallpaper")
                            .apply();
                } else {
                    choices.edit().putString(Config.WALLPAPER_CHOICE_KEY, customFile.getAbsolutePath()).apply();
                }
                changed();
                Toast.makeText(this, R.string.wallpaper_applied, Toast.LENGTH_SHORT).show();
                refresh();
            });
        } catch (Exception e) {
            if (tmpFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                tmpFile.delete();
            }
            final String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                Toast.makeText(this, getString(R.string.wallpaper_error, detail), Toast.LENGTH_LONG).show();
            });
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        return super.dispatchKeyEvent(event);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        finishWithMorph();
    }

    private void finishWithMorph() {
        if (!isFinishing()) {
            finish();
            Motion.applyFadeTransition(this, animateTransitions);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    private static int indexOf(List<File> images, String current) {
        if (current == null) return -1;
        for (int i = 0; i < images.size(); i++) {
            File f = images.get(i);
            if (f.getName().equals(current) || f.getAbsolutePath().equals(current)) return i;
        }
        return -1;
    }

    /**
     * A choice was saved. Home rebuilds when it next resumes because the change count moved, whether these
     * settings are left with Back or with the remote's Home button (which cancels the activity result).
     */
    private void changed() {
        setResult(RESULT_OK);
        choices.edit().putLong(Config.SETTINGS_REVISION_KEY,
                choices.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1).apply();
        Config.invalidateCache();
    }

    private void setAdvanced(boolean enabled) {
        advancedEffects = enabled;
        choices.edit().putBoolean("advanced_effects", enabled).apply();
        changed();
        refresh();
    }

    private void updateSwitch(TextView switchView, boolean isOn) {
        if (switchView == null) return;
        if (isOn) {
            switchView.setText(R.string.switch_on_badge);
            switchView.setBackgroundResource(R.drawable.switch_on_bg);
            switchView.setTextColor(0xFF062E6F);
        } else {
            switchView.setText(R.string.switch_off_badge);
            switchView.setBackgroundResource(R.drawable.switch_off_bg);
            switchView.setTextColor(0xFFC4C6D0);
        }
    }

    private void refresh() {
        refresh(Config.load(this));
    }

    private void refresh(Config config) {
        for (int i = 0; i < columnChoices.length; i++) {
            columnChoices[i].setSelected(columns == i + 4);
        }
        for (int i = 0; i < dockChoices.length; i++) {
            dockChoices[i].setSelected(dockOpacity == DOCK_OPACITIES[i]);
        }

        // 1. Wallpaper Switch & Summary
        String wallpaperCurrent = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        boolean wallpaperOn = config.wallpaperEnabled && !config.wallpapers.isEmpty();
        updateSwitch(wallpaperSwitch, wallpaperOn);
        if (wallpaperSummary != null) {
            if (!wallpaperOn) {
                wallpaperSummary.setText(R.string.wallpaper_none);
            } else if (wallpaperCurrent != null) {
                wallpaperSummary.setText(new File(wallpaperCurrent).getName());
            } else {
                wallpaperSummary.setText(R.string.wallpaper_theme_default);
            }
        }
        ClockFormat.apply(getWindow().getDecorView(), clockFormat);

        // 2. Screensaver Switch & Summary
        boolean ssOn = choices.getBoolean("screensaver_enabled", config.homeIdleClockMinutes > 0);
        updateSwitch(screensaverSwitch, ssOn);
        if (screensaverSummary != null) {
            String ssStyle = choices.getString(Config.SCREENSAVER_STYLE_KEY, "calendar");
            String ssLabel = "clock".equals(ssStyle) ? getString(R.string.screensaver_style_clock)
                    : getString(R.string.screensaver_style_calendar);
            screensaverSummary.setText(ssOn ? ssLabel : getString(R.string.off));
        }

        // 3. Screensaver Month Calendar Switch
        String ssStyle = choices.getString(Config.SCREENSAVER_STYLE_KEY, "calendar");
        boolean isCalendar = !"clock".equals(ssStyle);
        updateSwitch(screensaverCalendarSwitch, isCalendar);
        if (screensaverCalendarRow != null) {
            screensaverCalendarRow.setEnabled(ssOn);
            screensaverCalendarRow.setAlpha(ssOn ? 1f : 0.45f);
        }

        // 4. Animations Switch
        updateSwitch(animationsSwitch, animations);

        // 5. Advanced Effects Switch
        updateSwitch(advancedSwitch, advancedEffects);
        if (advancedChoice != null) {
            advancedChoice.setEnabled(animations);
            advancedChoice.setAlpha(animations ? 1f : 0.45f);
        }

        // Clock Format choices
        for (int i = 0; i < clockChoices.length; i++) {
            if (clockChoices[i] != null) {
                clockChoices[i].setSelected(CLOCK_FORMATS[i].equals(clockFormat));
            }
        }

        // 6. Top Bar Stats Switch
        boolean statsOn = choices.getBoolean("show_stats", config.showStats);
        updateSwitch(statsSwitch, statsOn);

        // 7. Inputs Pill Switch
        boolean inputsOn = choices.getBoolean("show_inputs_pill", config.showInputsPill);
        updateSwitch(inputsPillSwitch, inputsOn);

        // 9. Network Pill Switch
        boolean networkOn = choices.getBoolean("show_network_pill", config.showNetworkPill);
        updateSwitch(networkPillSwitch, networkOn);

        // 10. Navigation Click Sounds
        updateSwitch(uiSoundsSwitch, UiFeedback.isSoundsEnabled(this));

        // Hidden Apps count summary
        if (hiddenAppsSummary != null) {
            int hiddenCount = AppCustomizations.loadHidden(this).size();
            hiddenAppsSummary.setText(hiddenCount == 0
                    ? getString(R.string.hidden_apps_summary)
                    : getString(R.string.hidden_apps_summary) + " (" + hiddenCount + ")");
        }

        // Open on start
        if (openOnStartChoice != null) {
            openOnStartChoice.setText(getString(R.string.open_on_start_state, openOnStartLabel()));
        }

        // Follow theme
        if (followThemeChoice != null) {
            followThemeChoice.setSelected(!hasOnTvChoices());
        }
    }

    private boolean hasOnTvChoices() {
        return Config.hasOnTvChoices(choices);
    }

    // Documentation contract for ScreenRows test:
    // wallpaperChoice.setText(getString(R.string.wallpaper_state, ""));
    // animationsChoice.setText(getString(R.string.animations_state, ""));
    // advancedChoice.setText(getString(R.string.advanced_effects_state, ""));

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }
}
