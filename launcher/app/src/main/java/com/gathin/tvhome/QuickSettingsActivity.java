package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.Date;

/**
 * Google TV-style Quick Settings Panel:
 * A right-side panel matching Google TV stock launcher's quick toggles, layout,
 * and exact native target intents for Haier MediaTek Google TV.
 */
public final class QuickSettingsActivity extends Activity {

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));
    }

    private static final String PREFS_NAME = "quick_settings";

    private View layoutMainPanel;
    private WindowMorph morph;
    private boolean animateTransitions;
    private boolean launchingChild;
    private boolean closingWithMorph;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        overridePendingTransition(0, 0);
        Config cfg = Config.load(this);
        animateTransitions = cfg.animations && !cfg.reducedMotion;
        setContentView(R.layout.activity_quick_settings);
        ClockFormat.apply(getWindow().getDecorView(), cfg.clockFormat);

        layoutMainPanel = findViewById(R.id.layoutMainPanel);
        View scrim = findViewById(R.id.scrim);
        View panel = findViewById(R.id.panel);
        float density = getResources().getDisplayMetrics().density;
        morph = new WindowMorph(panel, scrim, layoutMainPanel);
        morph.setTargetCornerRadius(26f * density);
        morph.attachSource(getIntent());
        morph.playEnter(animateTransitions, null);

        // Scrim click closes the panel
        scrim.setOnClickListener(v -> closeWithMorph());

        // Update Date and start ticking quickClock
        TextView date = findViewById(R.id.quickDate);
        if (date != null) {
            date.setText(DateFormat.getDateInstance(DateFormat.FULL).format(new Date()));
        }
        View quickClock = findViewById(R.id.quickClock);
        if (quickClock instanceof LiveClockView) {
            ((LiveClockView) quickClock).updateTime();
            ((LiveClockView) quickClock).startTicking();
        }

        // Top Header: "All settings" -> Native TV Settings (MainSettings)
        findViewById(R.id.btnAllSettings).setOnClickListener(v ->
                safeStart(new Intent(Settings.ACTION_SETTINGS)));

        // --- Eight Quick Settings Tiles ---

        // 1. Screensaver -> Start Ambient Mode / DreamX, fallback to DREAM_SETTINGS
        findViewById(R.id.tileScreensaver).setOnClickListener(v -> openScreensaver());

        // 2. Inputs -> Native TV Input switcher (SelectInputActivity), fallback to local inputs
        findViewById(R.id.tileInputs).setOnClickListener(v -> openInputs());

        // 3. Display -> Native TV Picture Settings (MediaTek PictureActivity)
        findViewById(R.id.tileDisplay).setOnClickListener(v -> openDisplay());

        // 4. Sound -> Native TV Sound Settings (MediaTek SoundActivity)
        findViewById(R.id.tileSound).setOnClickListener(v -> openSound());

        // 5. Wi-Fi -> Native TV Network side panel (NetworkActivity)
        findViewById(R.id.tileWifi).setOnClickListener(v -> openWifi());

        // 6. Bluetooth -> Native Remotes & Accessories (AddAccessoryActivity / DevicePicker)
        findViewById(R.id.tileBluetooth).setOnClickListener(v -> openBluetooth());

        // 7. Sleep timer -> built in: each press steps to the next time
        findViewById(R.id.tileSleepTimer).setOnClickListener(v -> openSleepTimer());
        showSleepTimer();

        // 8. Reorder apps -> Triggers CleanYourTV reorder mode directly
        findViewById(R.id.tileRearrange).setOnClickListener(v -> openRearrange());

        // 9. Notifications -> Opens the Notification Center add-on
        View tileNotif = findViewById(R.id.tileNotifications);
        if (tileNotif != null) {
            tileNotif.setOnClickListener(v -> openNotifications());
        }

        // Initial focus on first tile for immediate D-pad readiness
        View firstTile = findViewById(R.id.tileScreensaver);
        if (firstTile != null) firstTile.requestFocus();
    }

    /** 1. Screensaver: Launch CleanYourTV's new Month Calendar & Clock Screensaver. */
    private void openScreensaver() {
        launchingChild = true;
        Intent screensaver = new Intent(this, ClockActivity.class);
        try {
            startActivity(screensaver);
            finish();
            if (animateTransitions) {
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
            } else {
                overridePendingTransition(0, 0);
            }
        } catch (ActivityNotFoundException | SecurityException e) {
            launchingChild = false;
        }
    }

    /** 2. Inputs: com.android.tv.action.VIEW_INPUTS (SelectInputActivity) on Google TV. */
    private void openInputs() {
        Intent inputsIntent = new Intent("com.android.tv.action.VIEW_INPUTS");
        if (safeStart(inputsIntent)) return;

        // Fallback to CleanYourTV inputs dropdown
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

    /** 3. Display: Native TV Picture Settings (MediaTek PictureActivity). */
    private void openDisplay() {
        Intent displayIntent = new Intent(Settings.ACTION_DISPLAY_SETTINGS);
        if (safeStart(displayIntent)) return;
        safeStart(new Intent(Settings.ACTION_SETTINGS));
    }

    /** 4. Sound: com.android.tv.action.VIEW_SOUND_SETTINGS (MediaTek SoundActivity). */
    private void openSound() {
        Intent soundIntent = new Intent("com.android.tv.action.VIEW_SOUND_SETTINGS");
        if (safeStart(soundIntent)) return;

        Intent genericSound = new Intent(Settings.ACTION_SOUND_SETTINGS);
        if (safeStart(genericSound)) return;

        safeStart(new Intent(Settings.ACTION_SETTINGS));
    }

    /** 5. Wi-Fi: Native TV NetworkActivity. */
    private void openWifi() {
        Intent wifiIntent = new Intent(Settings.ACTION_WIFI_SETTINGS);
        if (safeStart(wifiIntent)) return;

        safeStart(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
    }

    /** 6. Bluetooth: Remotes & Accessories (Google TV Slice / AddAccessoryActivity / Device Picker). */
    private void openBluetooth() {
        // 1. Google TV Remotes & Accessories Slice: opens paired devices, audio/headphones & pair accessory
        Intent sliceIntent = new Intent("android.settings.SLICE_SETTINGS")
                .addCategory(Intent.CATEGORY_DEFAULT)
                .putExtra("slice_uri", "content://com.android.tv.settings.accessories.sliceprovider/general");
        if (safeStart(sliceIntent)) return;

        // 2. Android TV Settings AddAccessoryActivity (Universal Bluetooth Scanner)
        Intent addAccessory = new Intent()
                .setClassName("com.android.tv.settings", "com.android.tv.settings.accessories.AddAccessoryActivity");
        if (safeStart(addAccessory)) return;

        // 3. Fallback: Google TV Remote Pairer
        Intent pairer = new Intent("com.google.android.tv.googletvremotepairer.action.BLUETOOTH_PAIRING");
        if (safeStart(pairer)) return;

        // 4. Fallback: General Settings
        safeStart(new Intent(Settings.ACTION_SETTINGS));
    }

    /**
     * 7. Sleep timer, built in: each press steps Off, 15, 30, 45, 60, 90, 120 minutes (SleepTimer). The panel stays
     * open so OK can be pressed again, and the tile shows the minutes left.
     */
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

    private Toast sleepToast;

    /** The tile's label: "Sleep timer" while off, else the minutes left. */
    private void showSleepTimer() {
        TextView label = findViewById(R.id.sleepTimerLabel);
        if (label == null) return;
        int left = SleepTimer.minutesLeft(this);
        label.setText(left == 0 ? getString(R.string.tile_sleep_timer) : getString(R.string.sleep_timer_left, left));
    }

    /** 9. Notifications: Directs user to the official Notification Center add-on. */
    private void openNotifications() {
        Intent panelIntent = new Intent()
                .setComponent(new ComponentName("io.github.emmanuel11003.tvnotifications",
                        "io.github.emmanuel11003.tvnotifications.PanelActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (safeStart(panelIntent)) {
            return;
        }

        Intent actionIntent = new Intent("io.github.emmanuel11003.tvnotifications.action.PANEL")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (safeStart(actionIntent)) {
            return;
        }

        Toast.makeText(this, R.string.notifications_addon_help, Toast.LENGTH_LONG).show();
    }

    /** 8. Reorder apps: triggers CleanYourTV's native app rearranging mode. */
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
        if (animateTransitions) {
            overridePendingTransition(R.anim.app_close_enter, R.anim.app_close_exit);
        } else {
            overridePendingTransition(0, 0);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (closingWithMorph) return true;
        int kc = event.getKeyCode();
        if (kc == KeyEvent.KEYCODE_SETTINGS || kc == KeyEvent.KEYCODE_NOTIFICATION
                || kc == KeyEvent.KEYCODE_FOCUS) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                closeWithMorph();
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void closeWithMorph() {
        if (closingWithMorph || isFinishing()) return;
        closingWithMorph = true;
        if (morph != null) {
            morph.playExit(animateTransitions && !launchingChild, () -> {
                if (!isFinishing()) finish();
                overridePendingTransition(0, 0);
            });
        } else {
            finish();
            applyCloseTransition();
        }
    }

    @Override
    public void onBackPressed() {
        closeWithMorph();
    }

    /** Settings key pressed again while panel is up closes it. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        closeWithMorph();
    }

    @Override
    protected void onResume() {
        super.onResume();
        launchingChild = false;
        closingWithMorph = false;
        View quickClock = findViewById(R.id.quickClock);
        if (quickClock instanceof LiveClockView) {
            ((LiveClockView) quickClock).updateTime();
            ((LiveClockView) quickClock).startTicking();
        }
        TextView date = findViewById(R.id.quickDate);
        if (date != null) {
            date.setText(DateFormat.getDateInstance(DateFormat.FULL).format(new Date()));
        }
        showSleepTimer();
    }

    @Override
    protected void onPause() {
        super.onPause();
        View quickClock = findViewById(R.id.quickClock);
        if (quickClock instanceof LiveClockView) {
            ((LiveClockView) quickClock).stopTicking();
        }
        if (!closingWithMorph) {
            if (morph != null) morph.cancelAndReset();
            if (!isFinishing()) {
                finish();
            }
            if (!launchingChild) {
                applyCloseTransition();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (morph != null) morph.cancelAndReset();
    }
}
