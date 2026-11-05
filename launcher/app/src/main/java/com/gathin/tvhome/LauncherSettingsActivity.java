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
import android.view.View;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Remote-friendly settings owned by the launcher; system settings stay one row away. */
public final class LauncherSettingsActivity extends Activity {
    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));   // locale
    }

    private static final int PICK_WALLPAPER_REQUEST = 43;
    static final String EXTRA_REORDER = "com.gathin.tvhome.extra.REORDER_FROM_SETTINGS";
    private SharedPreferences choices;
    private final TextView[] columnChoices = new TextView[4];
    private final TextView[] rowChoices = new TextView[4];
    private static final int[] DOCK_OPACITIES = {100, 70, 50, 30};
    private final TextView[] dockChoices = new TextView[DOCK_OPACITIES.length];
    private LinearLayout wallpaperRow;
    private TextView wallpaperSwitch;
    private TextView wallpaperSummary;
    private LinearLayout chooseWallpaperRow;
    private LinearLayout screensaverRow;
    private TextView screensaverSwitch;
    private TextView screensaverSummary;
    private LinearLayout screensaverCalendarRow;
    private TextView screensaverCalendarSwitch;
    private LinearLayout animationsRow;
    private TextView animationsSwitch;
    private LinearLayout advancedRow;
    private TextView advancedSwitch;
    private LinearLayout statsRow;
    private TextView statsSwitch;
    private LinearLayout inputsPillRow;
    private TextView inputsPillSwitch;
    private LinearLayout recentsPillRow;
    private TextView recentsPillSwitch;
    private LinearLayout networkPillRow;
    private TextView networkPillSwitch;
    private TextView followThemeChoice;
    private TextView openOnStartChoice;
    private TextView buttonMapperSummary;
    private int columns;
    private int rowsBelowDock;
    private int dockOpacity;
    private boolean animations;
    private boolean advancedEffects;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_launcher_settings);
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        Config config = Config.load(this);
        View reorderChoice = findViewById(R.id.reorderChoice);
        if (reorderChoice != null) {
            reorderChoice.setVisibility(View.VISIBLE);
            reorderChoice.setOnClickListener(v -> {
                setResult(RESULT_OK, new Intent().putExtra(EXTRA_REORDER, true));
                finish();
            });
        }
        View buttonMapperChoice = findViewById(R.id.buttonMapperChoice);
        buttonMapperSummary = findViewById(R.id.buttonMapperSummary);
        if (buttonMapperChoice != null) {
            buttonMapperChoice.setVisibility(View.VISIBLE);
            buttonMapperChoice.setOnClickListener(v -> ButtonMapper.showButtonMapperMenu(this, this::refresh));
        }
        columns = config.columns;
        rowsBelowDock = config.rowsBelowDock;
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
        int[] rowIds = {R.id.rows0, R.id.rows1, R.id.rows2, R.id.rows3};
        for (int i = 0; i < rowIds.length; i++) {
            final int value = i;
            rowChoices[i] = findViewById(rowIds[i]);
            rowChoices[i].setOnClickListener(v -> {
                if (rowsBelowDock == value) return;
                rowsBelowDock = value;
                choices.edit().putInt(Config.ROWS_BELOW_DOCK_KEY, value).apply();
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
        if (getResources().getBoolean(R.bool.rtl_app_grid)) {
            reverseRow(columnChoices);
            reverseRow(rowChoices);
            reverseRow(dockChoices);
        }
        // 1. Wallpaper Row & Switch (Click toggles ON/OFF; dedicated row or long-press browses)
        wallpaperRow = findViewById(R.id.wallpaperRow);
        wallpaperSwitch = findViewById(R.id.wallpaperSwitch);
        wallpaperSummary = findViewById(R.id.wallpaperSummary);
        chooseWallpaperRow = findViewById(R.id.chooseWallpaperRow);
        View.OnClickListener wallpaperToggle = v -> {
            String wp = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
            boolean isOn = !"none".equals(wp);
            if (isOn) {
                choices.edit().putString(Config.WALLPAPER_CHOICE_KEY, "none").apply();
            } else {
                choices.edit().remove(Config.WALLPAPER_CHOICE_KEY).apply();
            }
            changed();
            refresh();
        };
        if (wallpaperRow != null) {
            wallpaperRow.setOnClickListener(wallpaperToggle);
            wallpaperRow.setOnLongClickListener(v -> {
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
        animationsRow = findViewById(R.id.animationsRow);
        animationsSwitch = findViewById(R.id.animationsSwitch);
        View.OnClickListener animationsToggle = v -> {
            animations = !animations;
            if (!animations) advancedEffects = false;
            choices.edit().putBoolean("animations", animations)
                    .putBoolean("advanced_effects", advancedEffects).apply();
            changed();
            refresh();
        };
        if (animationsRow != null) {
            animationsRow.setOnClickListener(animationsToggle);
        }
        if (animationsSwitch != null) {
            animationsSwitch.setOnClickListener(animationsToggle);
        }

        // 5. Advanced Effects Row & Switch
        advancedRow = findViewById(R.id.advancedRow);
        advancedSwitch = findViewById(R.id.advancedSwitch);
        View.OnClickListener advancedToggle = v -> {
            if (!animations) {
                Toast.makeText(this, R.string.animations_summary, Toast.LENGTH_SHORT).show();
                return;
            }
            if (advancedEffects) {
                setAdvanced(false);
            } else {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.advanced_effects)
                        .setMessage(R.string.advanced_effects_warning)
                        .setPositiveButton(R.string.enable, (dialog, which) -> setAdvanced(true))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            }
        };
        if (advancedRow != null) {
            advancedRow.setOnClickListener(advancedToggle);
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

        // 8. Recent Apps Pill Row & Switch
        recentsPillRow = findViewById(R.id.recentsPillRow);
        recentsPillSwitch = findViewById(R.id.recentsPillSwitch);
        View.OnClickListener recentsToggle = v -> {
            boolean recentsOn = choices.getBoolean("show_recents_pill", config.showRecentsPill);
            choices.edit().putBoolean("show_recents_pill", !recentsOn).apply();
            changed();
            refresh();
        };
        if (recentsPillRow != null) {
            recentsPillRow.setOnClickListener(recentsToggle);
        }
        if (recentsPillSwitch != null) {
            recentsPillSwitch.setOnClickListener(recentsToggle);
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
        openOnStartChoice = findViewById(R.id.openOnStartChoice);
        if (config.openOnStartChoice) {
            openOnStartChoice.setVisibility(View.VISIBLE);
            openOnStartChoice.setOnClickListener(v -> showOpenOnStartPicker());
        }
        followThemeChoice = findViewById(R.id.followThemeChoice);
        followThemeChoice.setOnClickListener(v -> {
            if (!hasOnTvChoices()) return;
            choices.edit()
                    .remove("columns")
                    .remove(Config.ROWS_BELOW_DOCK_KEY)
                    .remove("animations")
                    .remove("advanced_effects")
                    .remove(Config.WALLPAPER_CHOICE_KEY)
                    .remove(Config.SCREENSAVER_STYLE_KEY)
                    .remove(Config.SCREENSAVER_BG_KEY)
                    .remove("screensaver_enabled")
                    .remove("show_stats")
                    .remove("show_inputs_pill")
                    .remove("show_recents_pill")
                    .remove("show_network_pill")
                    .remove(Config.DOCK_OPACITY_KEY)
                    .remove(OpenOnStart.KEY)
                    .commit();
            Config current = Config.load(this);
            columns = current.columns;
            rowsBelowDock = current.rowsBelowDock;
            dockOpacity = current.dockOpacity;
            animations = current.animations;
            advancedEffects = current.animations && current.advancedEffects;
            changed();
            refresh();
        });
        findViewById(R.id.defaultHomeChoice).setOnClickListener(v -> {
            Toast.makeText(this, R.string.home_settings_help, Toast.LENGTH_LONG).show();
            open(new Intent(Settings.ACTION_HOME_SETTINGS));
        });
        findViewById(R.id.tvSettingsChoice).setOnClickListener(v ->
                open(SystemSettings.intent(this, Settings.ACTION_SETTINGS)));
        refresh();
        if (config.launcherSettingsButton) reorderChoice.requestFocus();
        else columnChoices[Math.max(0, Math.min(columns - 4, 3))].requestFocus();
    }

    private void reverseRow(TextView[] views) {
        LinearLayout row = (LinearLayout) views[0].getParent();
        row.removeAllViews();
        for (int i = views.length - 1; i >= 0; i--) row.addView(views[i]);
    }

    private void showWallpaperPicker() {
        File dir = Config.dir(this);
        List<File> images = new java.util.ArrayList<>(WallpaperRotation.images(
                dir == null ? null : new File(dir, "wallpapers")));
        File single = dir == null ? null : new File(dir, Config.WALLPAPER_NAME);
        if (single != null && single.isFile()) images.add(0, single);

        File extWp = new File(Environment.getExternalStorageDirectory(), "Wallpapers");
        if (!extWp.exists()) extWp.mkdirs();

        scanImages(new File(Environment.getExternalStorageDirectory(), "Pictures"), images);
        scanImages(new File(Environment.getExternalStorageDirectory(), "Download"), images);
        scanImages(extWp, images);
        if (dir != null) scanImages(new File(dir, "custom"), images);

        String current = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        int totalItems = images.size() + 2;
        String[] labels = new String[totalItems];
        labels[0] = getString(R.string.wallpaper_custom_browse);
        for (int i = 0; i < images.size(); i++) {
            File f = images.get(i);
            String parent = f.getParentFile() != null ? f.getParentFile().getName() : "";
            if ("wallpapers".equalsIgnoreCase(parent)) labels[i + 1] = f.getName() + " (Theme)";
            else if ("Pictures".equalsIgnoreCase(parent)) labels[i + 1] = f.getName() + " (Pictures)";
            else if ("Download".equalsIgnoreCase(parent)) labels[i + 1] = f.getName() + " (Download)";
            else if ("Wallpapers".equalsIgnoreCase(parent)) labels[i + 1] = f.getName() + " (Wallpapers)";
            else labels[i + 1] = f.getName();
        }
        labels[totalItems - 1] = getString(R.string.wallpaper_none);

        if (images.isEmpty()) {
            Toast.makeText(this, "Add photos to TV's Wallpapers or Pictures folder", Toast.LENGTH_LONG).show();
        }

        int checked = -1;
        if ("none".equals(current)) {
            checked = totalItems - 1;
        } else if (current != null) {
            int idx = indexOf(images, current);
            if (idx >= 0) checked = idx + 1;
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.wallpaper_label)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    if (which == 0) {
                        Intent intent = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*");
                        try {
                            startActivityForResult(Intent.createChooser(intent, getString(R.string.wallpaper_label)), PICK_WALLPAPER_REQUEST);
                        } catch (ActivityNotFoundException e) {
                            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
                        }
                    } else if (which == totalItems - 1) {
                        choices.edit().putString(Config.WALLPAPER_CHOICE_KEY, "none").apply();
                        changed();
                        refresh();
                    } else {
                        File picked = images.get(which - 1);
                        choices.edit().putString(Config.WALLPAPER_CHOICE_KEY, picked.getAbsolutePath()).apply();
                        changed();
                        Toast.makeText(this, R.string.wallpaper_applied, Toast.LENGTH_SHORT).show();
                        refresh();
                    }
                })
                .setNeutralButton(R.string.wallpaper_theme_default, (dialog, which) -> {
                    choices.edit().remove(Config.WALLPAPER_CHOICE_KEY).apply();
                    changed();
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showScreensaverPicker() {
        String currentStyle = choices.getString(Config.SCREENSAVER_STYLE_KEY, "calendar");
        String currentBg = choices.getString(Config.SCREENSAVER_BG_KEY, "wallpaper");

        String[] items = {
                getString(R.string.screensaver_style_calendar) + ("calendar".equals(currentStyle) ? "  ✓" : ""),
                getString(R.string.screensaver_style_clock) + ("clock".equals(currentStyle) ? "  ✓" : ""),
                getString(R.string.screensaver_bg_wallpaper) + ("wallpaper".equals(currentBg) ? "  ✓" : ""),
                getString(R.string.screensaver_bg_glow) + ("glow".equals(currentBg) ? "  ✓" : ""),
                getString(R.string.screensaver_bg_black) + ("black".equals(currentBg) ? "  ✓" : "")
        };

        new AlertDialog.Builder(this)
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
        new AlertDialog.Builder(this)
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
        if (requestCode == PICK_WALLPAPER_REQUEST && resultCode == RESULT_OK && data != null && data.getData() != null) {
            try {
                File dir = Config.dir(this);
                if (dir != null) {
                    File customFile = new File(dir, "custom_wallpaper.jpg");
                    try (java.io.InputStream in = getContentResolver().openInputStream(data.getData());
                         java.io.FileOutputStream out = new java.io.FileOutputStream(customFile)) {
                        byte[] buf = new byte[8192];
                        int len;
                        while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
                    }
                    choices.edit().putString(Config.WALLPAPER_CHOICE_KEY, customFile.getAbsolutePath()).apply();
                    changed();
                    Toast.makeText(this, R.string.wallpaper_applied, Toast.LENGTH_SHORT).show();
                    refresh();
                }
            } catch (Exception e) {
                Toast.makeText(this, "Could not load image: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
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
            switchView.setTextColor(0xFFFFFFFF);
        } else {
            switchView.setText(R.string.switch_off_badge);
            switchView.setBackgroundResource(R.drawable.switch_off_bg);
            switchView.setTextColor(0xFF9CA3AF);
        }
    }

    private void refresh() {
        Config config = Config.load(this);
        for (int i = 0; i < columnChoices.length; i++) {
            columnChoices[i].setSelected(columns == i + 4);
        }
        for (int i = 0; i < rowChoices.length; i++) {
            rowChoices[i].setSelected(rowsBelowDock == i);
        }
        for (int i = 0; i < dockChoices.length; i++) {
            dockChoices[i].setSelected(dockOpacity == DOCK_OPACITIES[i]);
        }

        // 1. Wallpaper Switch & Summary
        String wallpaperCurrent = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        boolean wallpaperOn = !"none".equals(wallpaperCurrent);
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
        if (advancedRow != null) {
            advancedRow.setEnabled(animations);
            advancedRow.setAlpha(animations ? 1f : 0.45f);
        }

        // 6. Top Bar Stats Switch
        boolean statsOn = choices.getBoolean("show_stats", config.showStats);
        updateSwitch(statsSwitch, statsOn);

        // 7. Inputs Pill Switch
        boolean inputsOn = choices.getBoolean("show_inputs_pill", config.showInputsPill);
        updateSwitch(inputsPillSwitch, inputsOn);

        // 8. Recent Apps Pill Switch
        boolean recentsOn = choices.getBoolean("show_recents_pill", config.showRecentsPill);
        updateSwitch(recentsPillSwitch, recentsOn);

        // 9. Network Pill Switch
        boolean networkOn = choices.getBoolean("show_network_pill", config.showNetworkPill);
        updateSwitch(networkPillSwitch, networkOn);

        // Open on start
        if (openOnStartChoice != null) {
            openOnStartChoice.setText(getString(R.string.open_on_start_state, openOnStartLabel()));
        }

        // Follow theme
        if (followThemeChoice != null) {
            followThemeChoice.setSelected(!hasOnTvChoices());
        }

        // Button Mapper
        if (buttonMapperSummary != null) {
            int mappedCount = ButtonMapper.getMappedCount(this);
            if (mappedCount > 0) {
                buttonMapperSummary.setText(getString(R.string.button_mapper_mapped, mappedCount));
            } else {
                buttonMapperSummary.setText(R.string.button_mapper_summary);
            }
        }
    }

    private boolean hasOnTvChoices() {
        return choices.contains("columns") || choices.contains(Config.ROWS_BELOW_DOCK_KEY)
                || choices.contains("animations") || choices.contains("advanced_effects")
                || choices.contains(Config.WALLPAPER_CHOICE_KEY) || choices.contains(Config.DOCK_OPACITY_KEY)
                || choices.contains(Config.SCREENSAVER_STYLE_KEY) || choices.contains(Config.SCREENSAVER_BG_KEY)
                || choices.contains("screensaver_enabled") || choices.contains("show_stats")
                || choices.contains("show_inputs_pill") || choices.contains("show_recents_pill")
                || choices.contains("show_network_pill")
                || choices.contains(OpenOnStart.KEY);
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }
}
