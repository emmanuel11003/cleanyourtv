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

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

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
    private static volatile int lastAmbientColor = 0xFFFFFFFF;

    static void setLastAmbientColor(int color) {
        lastAmbientColor = color;
    }

    private View layoutMainPanel;
    private boolean animateTransitions;
    private int[] settingsKeycodes = new int[0];
    private boolean launchingChild;
    private MediaNowPlaying mediaNowPlaying;
    private View quickMediaCard;
    private TextView quickMediaTitle, quickMediaArtist, quickMediaPlayPause;
    private TextView boostRamLabel, notificationsLabel;
    private volatile boolean boostingRam;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Config cfg = Config.load(this);
        animateTransitions = cfg.animations && !cfg.reducedMotion;
        Motion.applyFadeTransition(this, animateTransitions);
        settingsKeycodes = cfg.settingsKeycodes;
        setContentView(R.layout.activity_quick_settings);
        ClockFormat.apply(getWindow().getDecorView(), cfg.clockFormat);

        layoutMainPanel = findViewById(R.id.layoutMainPanel);
        View scrim = findViewById(R.id.scrim);

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
                safeStart(SystemSettings.intent(this, Settings.ACTION_SETTINGS)));

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

        // 9. Notifications -> Opens built-in Notification Center
        View tileNotif = findViewById(R.id.tileNotifications);
        notificationsLabel = findViewById(R.id.notificationsLabel);
        if (tileNotif != null) {
            tileNotif.setOnClickListener(v -> openNotifications());
        }

        // 10. Free RAM Boost -> One-tap background RAM trim
        View tileBoost = findViewById(R.id.tileBoostRam);
        boostRamLabel = findViewById(R.id.boostRamLabel);
        if (tileBoost != null) {
            tileBoost.setOnClickListener(v -> runRamBoost());
        }
        refreshTileBadges();

        // Live Media Now-Playing Bar (Feature #2)
        quickMediaCard = findViewById(R.id.quickMediaCard);
        quickMediaTitle = findViewById(R.id.quickMediaTitle);
        quickMediaArtist = findViewById(R.id.quickMediaArtist);
        quickMediaPlayPause = findViewById(R.id.quickMediaPlayPause);
        View mediaOpen = findViewById(R.id.quickMediaOpen);
        View mediaNext = findViewById(R.id.quickMediaNext);
        mediaNowPlaying = new MediaNowPlaying(this, this::renderMediaState);
        if (mediaOpen != null) {
            mediaOpen.setOnClickListener(v -> {
                Intent i = mediaNowPlaying.openAppIntent();
                if (i != null) safeStart(i);
            });
        }
        if (quickMediaPlayPause != null) {
            quickMediaPlayPause.setOnClickListener(v -> mediaNowPlaying.togglePlayPause());
            Motion.attachSmoothFocus(quickMediaPlayPause, animateTransitions, 1.06f);
        }
        if (mediaNext != null) {
            mediaNext.setOnClickListener(v -> mediaNowPlaying.next());
            Motion.attachSmoothFocus(mediaNext, animateTransitions, 1.06f);
        }
        for (int id : new int[]{
                R.id.btnAllSettings, R.id.tileScreensaver, R.id.tileInputs, R.id.tileDisplay,
                R.id.tileSound, R.id.tileWifi, R.id.tileBluetooth, R.id.tileSleepTimer,
                R.id.tileRearrange, R.id.tileNotifications, R.id.tileBoostRam
        }) {
            Motion.attachSmoothFocus(findViewById(id), animateTransitions, 1.045f);
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

    /** 3. Display: Native TV Picture Settings (MediaTek PictureActivity / Xiaomi / AOSP). */
    private void openDisplay() {
        if (safeStart(SystemSettings.displayIntent(this))) return;
        safeStart(SystemSettings.intent(this, Settings.ACTION_SETTINGS));
    }

    /** 4. Sound: com.android.tv.action.VIEW_SOUND_SETTINGS (MediaTek SoundActivity). */
    private void openSound() {
        Intent soundIntent = new Intent("com.android.tv.action.VIEW_SOUND_SETTINGS");
        if (safeStart(soundIntent)) return;

        if (safeStart(SystemSettings.intent(this, Settings.ACTION_SOUND_SETTINGS))) return;

        safeStart(SystemSettings.intent(this, Settings.ACTION_SETTINGS));
    }

    /** 5. Wi-Fi: Native TV NetworkActivity. */
    private void openWifi() {
        if (safeStart(SystemSettings.intent(this, Settings.ACTION_WIFI_SETTINGS))) return;

        safeStart(SystemSettings.intent(this, Settings.ACTION_WIRELESS_SETTINGS));
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
        safeStart(SystemSettings.intent(this, Settings.ACTION_SETTINGS));
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

    /** 9. Notifications: opens the built-in Notification Center panel directly. */
    private void openNotifications() {
        Intent builtIn = new Intent(this, NotificationPanelActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        safeStart(builtIn);
    }

    /** 10. One-tap RAM Boost: trims background processes and updates free memory live. */
    private void runRamBoost() {
        if (boostingRam) return;
        boostingRam = true;
        LauncherExecutors.io().execute(() -> {
            MemoryBooster.BoostResult res = MemoryBooster.boost(this);
            LauncherExecutors.main().post(() -> {
                boostingRam = false;
                if (isFinishing() || isDestroyed()) return;
                if (boostRamLabel != null) {
                    boostRamLabel.setText(getString(R.string.boost_ram_tile_fmt, res.after.freeMb));
                }
                String msg = res.freedMb > 0
                        ? getString(R.string.boost_ram_freed, res.freedMb, res.after.freeMb)
                        : getString(R.string.boost_ram_clean, res.after.freeMb);
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
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
        if (st == null) {
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
        if (isSettingsCloseKey(event.getKeyCode())) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                closeWithMorph();
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void closeWithMorph() {
        if (isFinishing()) return;
        finish();
        applyCloseTransition();
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
        refreshTileBadges();
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
        }
        if (!launchingChild) {
            applyCloseTransition();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

}
