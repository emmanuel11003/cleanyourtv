package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;

/**
 * Compact Apple TV-style frosted Quick Settings overlay opened by the remote Gear key.
 * Supports Edit Controls to show/hide optional tiles while preserving all tiles on upgrade
 * and keeping All Settings + Edit Controls always reachable even when all optional tiles are hidden.
 */
public final class QuickSettingsActivity extends Activity {

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));
    }

    private static volatile int lastAmbientColor = 0xFFFFFFFF;

    static void setLastAmbientColor(int color) {
        lastAmbientColor = color;
    }

    private Config cfg;
    private View layoutMainPanel;
    private View panel;
    private View scrimBackdrop;
    private ScrollView quickScroll;
    private LinearLayout quickTilesContainer;
    private View btnAllSettings;
    private View btnAllSettingsCircle;
    private ImageView btnAllSettingsIcon;
    private LinearLayout btnEditControls;
    private TextView btnEditControlsLabel;

    private boolean animateTransitions;
    private boolean reducedMotion;
    private boolean reduceTransparency;
    private int[] settingsKeycodes = new int[0];
    private boolean launchingChild;
    private boolean editMode;

    private MediaNowPlaying mediaNowPlaying;
    private View quickMediaCard;
    private View quickMediaOpen;
    private TextView quickMediaTitle, quickMediaArtist, quickMediaPlayPause, quickMediaNext;
    private TextView boostRamLabel, notificationsLabel, sleepTimerLabel;
    private volatile boolean boostingRam;
    private Toast sleepToast;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        cfg = Config.load(this);
        animateTransitions = cfg.animations && !cfg.reducedMotion;
        reducedMotion = cfg.reducedMotion;
        reduceTransparency = cfg.reduceTransparency;
        settingsKeycodes = cfg.settingsKeycodes;
        Motion.applyFadeTransition(this, animateTransitions);

        setContentView(R.layout.activity_quick_settings);
        ClockFormat.apply(getWindow().getDecorView(), cfg.clockFormat);

        layoutMainPanel = findViewById(R.id.layoutMainPanel);
        panel = findViewById(R.id.panel);
        scrimBackdrop = findViewById(R.id.scrimBackdrop);
        quickScroll = findViewById(R.id.quickScroll);
        quickTilesContainer = findViewById(R.id.quickTilesContainer);
        btnAllSettings = findViewById(R.id.btnAllSettings);
        btnAllSettingsCircle = findViewById(R.id.btnAllSettingsCircle);
        btnAllSettingsIcon = findViewById(R.id.btnAllSettingsIcon);
        btnEditControls = findViewById(R.id.btnEditControls);
        btnEditControlsLabel = findViewById(R.id.btnEditControlsLabel);

        View scrim = findViewById(R.id.scrim);
        scrim.setOnClickListener(v -> closeWithMorph());

        applyThemeSurface();
        updateHeaderClock();

        // Always reachable All Settings: opens Android TV System Settings (or long-press for Launcher Settings)
        btnAllSettings.setOnClickListener(v -> {
            UiFeedback.playClick(v);
            safeStart(SystemSettings.intent(this, Settings.ACTION_SETTINGS));
        });
        btnAllSettings.setOnLongClickListener(v -> {
            UiFeedback.playClick(v);
            safeStart(new Intent(this, LauncherSettingsActivity.class));
            return true;
        });
        AppleTvUi.attachRowFocus(btnAllSettings, animateTransitions, reducedMotion, null);

        btnEditControls.setOnClickListener(v -> {
            UiFeedback.playClick(v);
            editMode = !editMode;
            renderTileArea(null);
            if (!editMode) quickTilesContainer.post(this::focusFirstControl);
        });
        AppleTvUi.attachRowFocus(btnEditControls, animateTransitions, reducedMotion, null);

        setupMediaCard();
        renderTileArea(null);
        quickTilesContainer.post(this::focusFirstControl);
    }

    private void applyThemeSurface() {
        if (scrimBackdrop != null) {
            scrimBackdrop.setBackgroundColor(reduceTransparency ? 0xCC06070A : 0x52000000);
        }
        if (panel != null) {
            panel.setBackgroundResource(AppleTvUi.panelBgRes(reduceTransparency));
        }
        if (btnAllSettingsCircle != null) {
            btnAllSettingsCircle.setBackgroundResource(AppleTvUi.tileBgRes(reduceTransparency));
        }
        if (btnAllSettingsIcon != null) {
            btnAllSettingsIcon.setImageTintList(getColorStateList(AppleTvUi.tileIconColorRes(reduceTransparency)));
            btnAllSettingsIcon.setImageTintMode(PorterDuff.Mode.SRC_IN);
        }
        if (btnEditControls != null && btnEditControlsLabel != null) {
            AppleTvUi.applyRowVisual(this, btnEditControls, btnEditControlsLabel, null, null, reduceTransparency);
        }
    }

    private void setupMediaCard() {
        quickMediaCard = findViewById(R.id.quickMediaCard);
        quickMediaOpen = findViewById(R.id.quickMediaOpen);
        quickMediaTitle = findViewById(R.id.quickMediaTitle);
        quickMediaArtist = findViewById(R.id.quickMediaArtist);
        quickMediaPlayPause = findViewById(R.id.quickMediaPlayPause);
        quickMediaNext = findViewById(R.id.quickMediaNext);

        if (quickMediaCard != null) {
            quickMediaCard.setBackgroundResource(AppleTvUi.tileBgRes(reduceTransparency));
        }
        if (quickMediaTitle != null) {
            quickMediaTitle.setTextColor(getColorStateList(AppleTvUi.tileTextColorRes(reduceTransparency)));
        }
        if (quickMediaArtist != null) {
            quickMediaArtist.setTextColor(getColorStateList(AppleTvUi.tileSubColorRes(reduceTransparency)));
        }
        if (quickMediaPlayPause != null) {
            quickMediaPlayPause.setBackgroundResource(AppleTvUi.tileBgRes(reduceTransparency));
            quickMediaPlayPause.setTextColor(getColorStateList(AppleTvUi.tileTextColorRes(reduceTransparency)));
        }
        if (quickMediaNext != null) {
            quickMediaNext.setBackgroundResource(AppleTvUi.tileBgRes(reduceTransparency));
            quickMediaNext.setTextColor(getColorStateList(AppleTvUi.tileTextColorRes(reduceTransparency)));
        }

        if (!cfg.showNowPlayingQuickSettings) {
            if (quickMediaCard != null) quickMediaCard.setVisibility(View.GONE);
            mediaNowPlaying = null;
            return;
        }

        mediaNowPlaying = new MediaNowPlaying(this, this::renderMediaState);
        if (quickMediaOpen != null) {
            quickMediaOpen.setOnClickListener(v -> {
                Intent i = mediaNowPlaying.openAppIntent();
                if (i != null) safeStart(i);
            });
        }
        if (quickMediaPlayPause != null) {
            quickMediaPlayPause.setOnClickListener(v -> mediaNowPlaying.togglePlayPause());
            AppleTvUi.attachRowFocus(quickMediaPlayPause, animateTransitions, reducedMotion, null);
        }
        if (quickMediaNext != null) {
            quickMediaNext.setOnClickListener(v -> mediaNowPlaying.next());
            AppleTvUi.attachRowFocus(quickMediaNext, animateTransitions, reducedMotion, null);
        }
    }

    private void renderTileArea(String restoreKey) {
        quickTilesContainer.removeAllViews();
        boostRamLabel = null;
        notificationsLabel = null;
        sleepTimerLabel = null;

        if (btnEditControlsLabel != null) {
            btnEditControlsLabel.setText(editMode ? R.string.qs_done : R.string.qs_edit_controls);
        }

        if (editMode) {
            renderEditControlsList(restoreKey);
        } else {
            renderQuickTilesGrid();
        }
    }

    private void renderQuickTilesGrid() {
        Set<String> hidden = QuickSettingsPrefs.loadHiddenTiles(this);
        List<String> visible = new ArrayList<>();
        for (String id : QuickSettingsPrefs.ALL_TILES) {
            if (QuickSettingsPrefs.isTileVisible(hidden, id)) {
                visible.add(id);
            }
        }

        if (visible.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.qs_all_hidden_hint);
            empty.setTextColor(reduceTransparency ? 0xFFD4D8E2 : 0xFFA4AAB8);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(12), dp(28), dp(12), dp(28));
            quickTilesContainer.addView(empty);
            return;
        }

        for (int i = 0; i < visible.size(); i += 2) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setClipChildren(false);
            row.setClipToPadding(false);
            row.setBaselineAligned(false);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.bottomMargin = dp(8);
            row.setLayoutParams(rlp);

            View leftTile = buildQuickTile(visible.get(i));
            LinearLayout.LayoutParams lLp = new LinearLayout.LayoutParams(0, dp(54), 1f);
            lLp.setMarginEnd(dp(4));
            row.addView(leftTile, lLp);

            if (i + 1 < visible.size()) {
                View rightTile = buildQuickTile(visible.get(i + 1));
                LinearLayout.LayoutParams rrLp = new LinearLayout.LayoutParams(0, dp(54), 1f);
                rrLp.setMarginStart(dp(4));
                row.addView(rightTile, rrLp);
            } else {
                View spacer = new View(this);
                LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(0, dp(54), 1f);
                spLp.setMarginStart(dp(4));
                row.addView(spacer, spLp);
            }

            quickTilesContainer.addView(row);
        }
        showSleepTimer();
        refreshTileBadges();
    }

    private void renderEditControlsList(String restoreKey) {
        Set<String> hidden = QuickSettingsPrefs.loadHiddenTiles(this);
        View focusTarget = null;
        for (String id : QuickSettingsPrefs.ALL_TILES) {
            final String tileId = id;
            final boolean[] visible = {QuickSettingsPrefs.isTileVisible(hidden, tileId)};

            LinearLayout row = new LinearLayout(this);
            row.setId(View.generateViewId());
            row.setTag(tileId);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPaddingRelative(dp(12), dp(6), dp(12), dp(6));
            row.setFocusable(true);
            row.setClickable(true);
            row.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(4);
            row.setLayoutParams(lp);

            TextView title = new TextView(this);
            title.setText(tileLabelRes(tileId));
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
            title.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setDuplicateParentStateEnabled(true);
            row.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView state = new TextView(this);
            state.setText(visible[0] ? R.string.opt_on : R.string.opt_off);
            state.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            state.setDuplicateParentStateEnabled(true);
            LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sLp.setMarginStart(dp(8));
            row.addView(state, sLp);

            View pill = new View(this);
            LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(dp(18), dp(10));
            pLp.setMarginStart(dp(8));
            pill.setBackgroundResource(visible[0] ? R.drawable.appletv_switch_on : R.drawable.appletv_switch_off);
            row.addView(pill, pLp);

            AppleTvUi.applyRowVisual(this, row, title, state, null, reduceTransparency);
            AppleTvUi.attachRowFocus(row, animateTransitions, reducedMotion, null);
            row.setOnClickListener(v -> {
                UiFeedback.playClick(v);
                visible[0] = !visible[0];
                QuickSettingsPrefs.setTileVisible(this, tileId, visible[0]);
                state.setText(visible[0] ? R.string.opt_on : R.string.opt_off);
                pill.setBackgroundResource(visible[0] ? R.drawable.appletv_switch_on : R.drawable.appletv_switch_off);
            });

            quickTilesContainer.addView(row);
            if (tileId.equals(restoreKey) || (restoreKey == null && focusTarget == null)) {
                focusTarget = row;
            }
        }
        if (focusTarget != null) {
            final View ft = focusTarget;
            quickTilesContainer.post(ft::requestFocus);
        }
    }

    private View buildQuickTile(String id) {
        LinearLayout tile = new LinearLayout(this);
        tile.setId(viewIdForTile(id));
        tile.setOrientation(LinearLayout.HORIZONTAL);
        tile.setGravity(Gravity.CENTER_VERTICAL);
        tile.setPaddingRelative(dp(11), dp(8), dp(10), dp(8));
        tile.setBackgroundResource(AppleTvUi.tileBgRes(reduceTransparency));
        tile.setFocusable(true);
        tile.setClickable(true);
        tile.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);

        ImageView icon = new ImageView(this);
        icon.setImageResource(tileIconRes(id));
        icon.setImageTintList(getColorStateList(AppleTvUi.tileIconColorRes(reduceTransparency)));
        icon.setImageTintMode(PorterDuff.Mode.SRC_IN);
        icon.setDuplicateParentStateEnabled(true);
        tile.addView(icon, new LinearLayout.LayoutParams(dp(18), dp(18)));

        TextView label = new TextView(this);
        label.setText(tileLabelRes(id));
        label.setTextColor(getColorStateList(AppleTvUi.tileTextColorRes(reduceTransparency)));
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setDuplicateParentStateEnabled(true);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        llp.setMarginStart(dp(8));
        tile.addView(label, llp);

        switch (id) {
            case QuickSettingsPrefs.TILE_SCREENSAVER:
                tile.setOnClickListener(v -> openScreensaver());
                break;
            case QuickSettingsPrefs.TILE_INPUTS:
                tile.setOnClickListener(v -> openInputs());
                break;
            case QuickSettingsPrefs.TILE_DISPLAY:
                tile.setOnClickListener(v -> openDisplay());
                break;
            case QuickSettingsPrefs.TILE_SOUND:
                tile.setOnClickListener(v -> openSound());
                break;
            case QuickSettingsPrefs.TILE_WIFI:
                tile.setOnClickListener(v -> openWifi());
                break;
            case QuickSettingsPrefs.TILE_BLUETOOTH:
                tile.setOnClickListener(v -> openBluetooth());
                break;
            case QuickSettingsPrefs.TILE_SLEEP_TIMER:
                sleepTimerLabel = label;
                tile.setOnClickListener(v -> openSleepTimer());
                break;
            case QuickSettingsPrefs.TILE_REARRANGE:
                tile.setOnClickListener(v -> openRearrange());
                break;
            case QuickSettingsPrefs.TILE_NOTIFICATIONS:
                notificationsLabel = label;
                tile.setOnClickListener(v -> openNotifications());
                break;
            case QuickSettingsPrefs.TILE_BOOST_RAM:
                boostRamLabel = label;
                tile.setOnClickListener(v -> runRamBoost());
                tile.setOnLongClickListener(v -> {
                    RamManagerDialog.show(this, this::refreshTileBadges);
                    return true;
                });
                break;
        }
        AppleTvUi.attachRowFocus(tile, animateTransitions, reducedMotion, null);
        return tile;
    }

    private void focusFirstControl() {
        if (quickTilesContainer.getChildCount() > 0 && quickTilesContainer.getChildAt(0) instanceof ViewGroup) {
            ViewGroup firstRow = (ViewGroup) quickTilesContainer.getChildAt(0);
            if (firstRow.getChildCount() > 0 && firstRow.getChildAt(0).isFocusable()) {
                firstRow.getChildAt(0).requestFocus();
                return;
            }
        }
        if (btnAllSettings != null) btnAllSettings.requestFocus();
    }

    private int dp(int dp) {
        return AppleTvUi.dp(this, dp);
    }

    private static int viewIdForTile(String id) {
        switch (id) {
            case QuickSettingsPrefs.TILE_SCREENSAVER: return R.id.tileScreensaver;
            case QuickSettingsPrefs.TILE_INPUTS: return R.id.tileInputs;
            case QuickSettingsPrefs.TILE_DISPLAY: return R.id.tileDisplay;
            case QuickSettingsPrefs.TILE_SOUND: return R.id.tileSound;
            case QuickSettingsPrefs.TILE_WIFI: return R.id.tileWifi;
            case QuickSettingsPrefs.TILE_BLUETOOTH: return R.id.tileBluetooth;
            case QuickSettingsPrefs.TILE_SLEEP_TIMER: return R.id.tileSleepTimer;
            case QuickSettingsPrefs.TILE_REARRANGE: return R.id.tileRearrange;
            case QuickSettingsPrefs.TILE_NOTIFICATIONS: return R.id.tileNotifications;
            case QuickSettingsPrefs.TILE_BOOST_RAM: return R.id.tileBoostRam;
            default: return View.generateViewId();
        }
    }

    private static int tileIconRes(String id) {
        switch (id) {
            case QuickSettingsPrefs.TILE_SCREENSAVER: return R.drawable.ic_screensaver;
            case QuickSettingsPrefs.TILE_INPUTS: return R.drawable.ic_input;
            case QuickSettingsPrefs.TILE_DISPLAY: return R.drawable.ic_picture;
            case QuickSettingsPrefs.TILE_SOUND: return R.drawable.ic_sound;
            case QuickSettingsPrefs.TILE_WIFI: return R.drawable.ic_wifi;
            case QuickSettingsPrefs.TILE_BLUETOOTH: return R.drawable.ic_bluetooth;
            case QuickSettingsPrefs.TILE_SLEEP_TIMER: return R.drawable.ic_timer;
            case QuickSettingsPrefs.TILE_REARRANGE: return R.drawable.ic_swap_vert;
            case QuickSettingsPrefs.TILE_NOTIFICATIONS: return R.drawable.ic_notifications;
            case QuickSettingsPrefs.TILE_BOOST_RAM: return R.drawable.ic_boost;
            default: return R.drawable.ic_settings;
        }
    }

    private static int tileLabelRes(String id) {
        switch (id) {
            case QuickSettingsPrefs.TILE_SCREENSAVER: return R.string.tile_screensaver;
            case QuickSettingsPrefs.TILE_INPUTS: return R.string.tile_inputs;
            case QuickSettingsPrefs.TILE_DISPLAY: return R.string.tile_display;
            case QuickSettingsPrefs.TILE_SOUND: return R.string.tile_sound;
            case QuickSettingsPrefs.TILE_WIFI: return R.string.tile_wifi;
            case QuickSettingsPrefs.TILE_BLUETOOTH: return R.string.tile_bluetooth;
            case QuickSettingsPrefs.TILE_SLEEP_TIMER: return R.string.tile_sleep_timer;
            case QuickSettingsPrefs.TILE_REARRANGE: return R.string.reorder;
            case QuickSettingsPrefs.TILE_NOTIFICATIONS: return R.string.tile_notifications;
            case QuickSettingsPrefs.TILE_BOOST_RAM: return R.string.tile_boost_ram;
            default: return R.string.settings;
        }
    }

    private void updateHeaderClock() {
        TextView date = findViewById(R.id.quickDate);
        if (date != null) {
            date.setText(DateFormat.getDateInstance(DateFormat.FULL).format(new Date()));
        }
        View quickClock = findViewById(R.id.quickClock);
        if (quickClock instanceof LiveClockView) {
            ((LiveClockView) quickClock).updateTime();
            ((LiveClockView) quickClock).startTicking();
        }
    }

    private void openScreensaver() {
        launchingChild = true;
        Intent screensaver = new Intent(this, ClockActivity.class);
        try {
            startActivity(screensaver);
            finish();
        } catch (ActivityNotFoundException | SecurityException e) {
            launchingChild = false;
        }
    }

    private void openInputs() {
        Intent inputsIntent = new Intent("com.android.tv.action.VIEW_INPUTS");
        if (safeStart(inputsIntent)) return;

        Intent localInputs = new Intent(this, MainActivity.class)
                .setAction(MainActivity.ACTION_VIEW_INPUTS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        launchingChild = true;
        try {
            startActivity(localInputs);
            finish();
            applyOpenTransition();
        } catch (Exception ignored) {
            launchingChild = false;
        }
    }

    private void openDisplay() {
        if (safeStart(SystemSettings.displayIntent(this))) return;
        safeStart(SystemSettings.intent(this, Settings.ACTION_SETTINGS));
    }

    private void openSound() {
        Intent soundIntent = new Intent("com.android.tv.action.VIEW_SOUND_SETTINGS");
        if (safeStart(soundIntent)) return;
        if (safeStart(SystemSettings.intent(this, Settings.ACTION_SOUND_SETTINGS))) return;
        safeStart(SystemSettings.intent(this, Settings.ACTION_SETTINGS));
    }

    private void openWifi() {
        if (safeStart(SystemSettings.intent(this, Settings.ACTION_WIFI_SETTINGS))) return;
        safeStart(SystemSettings.intent(this, Settings.ACTION_WIRELESS_SETTINGS));
    }

    private void openBluetooth() {
        Intent sliceIntent = new Intent("android.settings.SLICE_SETTINGS")
                .addCategory(Intent.CATEGORY_DEFAULT)
                .putExtra("slice_uri", "content://com.android.tv.settings.accessories.sliceprovider/general");
        if (safeStart(sliceIntent)) return;

        Intent addAccessory = new Intent()
                .setClassName("com.android.tv.settings", "com.android.tv.settings.accessories.AddAccessoryActivity");
        if (safeStart(addAccessory)) return;

        Intent pairer = new Intent("com.google.android.tv.googletvremotepairer.action.BLUETOOTH_PAIRING");
        if (safeStart(pairer)) return;

        safeStart(SystemSettings.intent(this, Settings.ACTION_SETTINGS));
    }

    private void openSleepTimer() {
        int minutes = SleepTimer.next(SleepTimer.chosen(this));
        SleepTimer.set(this, minutes);
        showSleepTimer();
        String msg = minutes == 0 ? getString(R.string.sleep_timer_off)
                : getString(SleepTimer.helper() != null ? R.string.sleep_timer_set : R.string.sleep_timer_no_helper, minutes);
        if (sleepToast != null) sleepToast.cancel();
        sleepToast = Toast.makeText(this, msg, minutes != 0 && SleepTimer.helper() == null ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT);
        sleepToast.show();
    }

    private void showSleepTimer() {
        if (sleepTimerLabel == null) return;
        int left = SleepTimer.minutesLeft(this);
        sleepTimerLabel.setText(left == 0 ? getString(R.string.tile_sleep_timer) : getString(R.string.sleep_timer_left, left));
    }

    private void openNotifications() {
        Intent builtIn = new Intent(this, NotificationPanelActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        safeStart(builtIn);
    }

    private void runRamBoost() {
        if (boostingRam) return;
        boostingRam = true;
        if (boostRamLabel != null) {
            boostRamLabel.setText(R.string.boost_ram_running);
        }
        LauncherExecutors.io().execute(() -> {
            MemoryBooster.BoostResult res = MemoryBooster.boost(this);
            LauncherExecutors.main().post(() -> {
                boostingRam = false;
                if (isFinishing() || isDestroyed()) return;
                if (boostRamLabel != null) {
                    boostRamLabel.setText(getString(R.string.boost_ram_tile_fmt, res.after.freeMb));
                }
                Toast.makeText(this, MemoryBooster.formatResultMessage(this, res), Toast.LENGTH_LONG).show();
            });
        });
    }

    private void refreshTileBadges() {
        if (notificationsLabel != null) {
            int count = GuardRevival.snapshot().size();
            notificationsLabel.setText(count > 0
                    ? getString(R.string.tile_notifications) + " \u00B7 " + count
                    : getString(R.string.tile_notifications));
        }
        if (boostRamLabel != null) {
            LauncherExecutors.io().execute(() -> {
                MemoryBooster.Snapshot snap = MemoryBooster.readSnapshot(this);
                LauncherExecutors.main().post(() -> {
                    if (!isFinishing() && !isDestroyed() && boostRamLabel != null) {
                        boostRamLabel.setText(getString(R.string.boost_ram_tile_fmt, snap.freeMb));
                    }
                });
            });
        }
    }

    private void renderMediaState(MediaNowPlaying.State st) {
        if (quickMediaCard == null) return;
        if (cfg == null || !cfg.showNowPlayingQuickSettings || st == null) {
            quickMediaCard.setVisibility(View.GONE);
            return;
        }
        quickMediaCard.setVisibility(View.VISIBLE);
        if (quickMediaTitle != null) quickMediaTitle.setText(st.title);
        if (quickMediaArtist != null) {
            quickMediaArtist.setText(st.artist);
            quickMediaArtist.setVisibility(st.artist.isEmpty() ? View.GONE : View.VISIBLE);
        }
        if (quickMediaPlayPause != null) {
            quickMediaPlayPause.setText(st.playing ? "\u23F8" : "\u25B6");
        }
    }

    private void openRearrange() {
        Intent reorderIntent = new Intent(this, MainActivity.class)
                .setAction(MainActivity.ACTION_REORDER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        launchingChild = true;
        try {
            startActivity(reorderIntent);
            finish();
            applyOpenTransition();
        } catch (Exception ignored) {
            launchingChild = false;
        }
    }

    private boolean safeStart(Intent intent) {
        if (intent == null) return false;
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        launchingChild = true;
        try {
            startActivity(intent);
            finish();
            applyOpenTransition();
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            launchingChild = false;
            return false;
        }
    }

    private void applyOpenTransition() {
        if (animateTransitions) {
            overridePendingTransition(R.anim.app_open_enter, R.anim.app_open_exit);
        } else {
            overridePendingTransition(0, 0);
        }
    }

    private void applyCloseTransition() {
        Motion.applyFadeTransition(this, animateTransitions);
    }

    private boolean isSettingsCloseKey(int kc) {
        if (kc == KeyEvent.KEYCODE_SETTINGS || kc == KeyEvent.KEYCODE_NOTIFICATION
                || kc == KeyEvent.KEYCODE_FOCUS) {
            return true;
        }
        for (int code : settingsKeycodes) {
            if (kc == code) return true;
        }
        return false;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK
                && event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
            if (editMode) {
                editMode = false;
                renderTileArea(null);
                focusFirstControl();
                return true;
            }
            closeWithMorph();
            return true;
        }
        if (isSettingsCloseKey(event.getKeyCode())) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                closeWithMorph();
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onBackPressed() {
        if (editMode) {
            editMode = false;
            renderTileArea(null);
            focusFirstControl();
            return;
        }
        closeWithMorph();
    }

    private void closeWithMorph() {
        if (isFinishing()) return;
        finish();
        applyCloseTransition();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        closeWithMorph();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (mediaNowPlaying != null && layoutMainPanel != null) {
            layoutMainPanel.post(() -> {
                if (!isFinishing() && !isDestroyed() && mediaNowPlaying != null) {
                    mediaNowPlaying.start();
                }
            });
        }
    }

    @Override
    protected void onStop() {
        if (mediaNowPlaying != null) mediaNowPlaying.stop();
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        launchingChild = false;
        cfg = Config.load(this);
        animateTransitions = cfg.animations && !cfg.reducedMotion;
        reducedMotion = cfg.reducedMotion;
        reduceTransparency = cfg.reduceTransparency;
        applyThemeSurface();
        updateHeaderClock();
        if (!editMode) renderTileArea(null);
    }

    @Override
    protected void onPause() {
        super.onPause();
        View quickClock = findViewById(R.id.quickClock);
        if (quickClock instanceof LiveClockView) {
            ((LiveClockView) quickClock).stopTicking();
        }
        if (!isFinishing()) {
            finish();
            if (!launchingChild) {
                applyCloseTransition();
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (layoutMainPanel != null) {
            layoutMainPanel.animate().cancel();
        }
        super.onDestroy();
    }
}
