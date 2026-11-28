package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Apple TV-inspired Launcher Settings screen.
 * Preserves all CleanYourTV v8 preferences, device compatibility switches, backup/restore,
 * and Wi-Fi transfer routing with single-stop D-pad focus and exact focus/scroll restoration.
 */
public final class LauncherSettingsActivity extends Activity {
    static final String EXTRA_REORDER = "reorder";
    static final int[] DOCK_OPACITIES = {100, 70, 50, 30};

    private static final int PICK_WALLPAPER_REQUEST = 1042;
    private static final int PICK_SCREENSAVER_WALLPAPER_REQUEST = 1043;
    private static final int MAX_WALLPAPER_BYTES = 15 * 1024 * 1024;
    private static final String PREV_WALLPAPER_KEY = "prev_wallpaper_choice";

    private static final String STATE_SCREEN_IDS = "nav_ids";
    private static final String STATE_FOCUSED_KEYS = "nav_focus";
    private static final String STATE_SCROLL_YS = "nav_scrolls";
    private static final String STATE_PENDING_KEY = "pending_focus";
    private static final String STATE_PENDING_SCROLL = "pending_scroll";

    private static final String SCR_ROOT = "root";
    private static final String SCR_GENERAL = "general";
    private static final String SCR_APPEARANCE = "appearance";
    private static final String SCR_WALLPAPER_PICKER = "wallpaper_picker";
    private static final String SCR_WALLPAPER_DIM = "wallpaper_dim";
    private static final String SCR_GLASS_GLOW = "glass_glow";
    private static final String SCR_CLOCK_HEADER = "clock_header";
    private static final String SCR_CLOCK_FORMAT = "clock_format";
    private static final String SCR_ACCESSIBILITY = "accessibility";
    private static final String SCR_ABOUT = "about";
    private static final String SCR_HOME_SCREEN = "home_screen";
    private static final String SCR_HOME_LAYOUT = "home_layout";
    private static final String SCR_COLUMNS = "columns";
    private static final String SCR_ROWS_BELOW = "rows_below";
    private static final String SCR_DOCK_OPACITY = "dock_opacity";
    private static final String SCR_CINEMATIC = "cinematic";
    private static final String SCR_CINEMATIC_ART = "cinematic_art";
    private static final String SCR_CINEMATIC_SCRIM = "cinematic_scrim";
    private static final String SCR_SKY_INTENSITY = "sky_intensity";
    private static final String SCR_LIBRARY_OPTIONS = "library_options";
    private static final String SCR_TILE_INPUT_OPTIONS = "tile_input_options";
    private static final String SCR_VIDEO_AUDIO = "video_audio";
    private static final String SCR_NOW_PLAYING = "now_playing";
    private static final String SCR_EDIT_QS_CONTROLS = "edit_qs_controls";
    private static final String SCR_SCREENSAVER = "screensaver";
    private static final String SCR_SS_IDLE_TIMEOUT = "ss_idle_timeout";
    private static final String SCR_SS_BACKGROUND = "ss_background";
    private static final String SCR_SS_WALLPAPER_PICKER = "ss_wallpaper_picker";
    private static final String SCR_SS_DIM = "ss_dim";
    private static final String SCR_NOTIFICATIONS = "notifications";
    private static final String SCR_REMOTES_DEVICES = "remotes_devices";
    private static final String SCR_APPS = "apps";
    private static final String SCR_HIDDEN_APPS = "hidden_apps";
    private static final String SCR_NETWORK = "network";
    private static final String SCR_NETWORK_APPEARANCE = "network_appearance";
    private static final String SCR_TRANSFER_BACKUP = "transfer_backup";
    private static final String SCR_SYSTEM = "system";
    private static final String SCR_SLEEP_TIMER = "sleep_timer";
    private static final String SCR_STARTUP_DEST = "startup_dest";
    private static final String SCR_STARTUP_APP_PICKER = "startup_app_picker";
    private static final String SCR_ADVANCED = "advanced";
    private static final String SCR_STARTUP_NET_WAIT = "startup_net_wait";
    private static final String SCR_DEVICE_COMPAT = "device_compat";

    private static final class NavFrame {
        final String id;
        String focusedKey;
        int scrollY;

        NavFrame(String id, String focusedKey, int scrollY) {
            this.id = id;
            this.focusedKey = focusedKey;
            this.scrollY = scrollY;
        }
    }

    private SharedPreferences choices;
    private Config config;
    private boolean animateTransitions;
    private boolean anyChange;

    private View settingsRoot;
    private DeviceHeroView deviceHero;
    private View submenuHeroBox;
    private ImageView heroIcon;
    private TextView heroHeading;
    private TextView heroSummary;
    private View wallpaperPreviewBox;
    private ImageView wallpaperPreviewImg;
    private TextView wallpaperPreviewCaption;
    private TextView settingsBreadcrumb;
    private TextView settingsTitle;
    private ScrollView settingsScroll;
    private LinearLayout settingsRows;

    private final ArrayList<NavFrame> navStack = new ArrayList<>();
    private final List<View> renderedRows = new ArrayList<>();
    private String pendingFocusKey;
    private int pendingScrollY = -1;
    private int previewLoadGeneration;
    private Bitmap currentPreviewBitmap;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        config = Config.load(this);
        animateTransitions = config.animations && !config.reducedMotion;
        Motion.applyFadeTransition(this, animateTransitions);
        setContentView(R.layout.activity_launcher_settings);
        getWindow().setBackgroundDrawable(null);

        settingsRoot = findViewById(R.id.settingsRoot);
        deviceHero = findViewById(R.id.deviceHero);
        submenuHeroBox = findViewById(R.id.submenuHeroBox);
        heroIcon = findViewById(R.id.heroIcon);
        heroHeading = findViewById(R.id.heroHeading);
        heroSummary = findViewById(R.id.heroSummary);
        wallpaperPreviewBox = findViewById(R.id.wallpaperPreviewBox);
        wallpaperPreviewImg = findViewById(R.id.wallpaperPreviewImg);
        wallpaperPreviewCaption = findViewById(R.id.wallpaperPreviewCaption);
        settingsBreadcrumb = findViewById(R.id.settingsBreadcrumb);
        settingsTitle = findViewById(R.id.settingsTitle);
        settingsScroll = findViewById(R.id.settingsScroll);
        settingsRows = findViewById(R.id.settingsRows);

        View previewFrame = findViewById(R.id.wallpaperPreviewFrame);
        if (previewFrame != null) {
            final float r = AppleTvUi.dp(this, 14);
            previewFrame.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
                }
            });
            previewFrame.setClipToOutline(true);
        }

        if (savedInstanceState != null) {
            ArrayList<String> ids = savedInstanceState.getStringArrayList(STATE_SCREEN_IDS);
            ArrayList<String> keys = savedInstanceState.getStringArrayList(STATE_FOCUSED_KEYS);
            int[] scrolls = savedInstanceState.getIntArray(STATE_SCROLL_YS);
            if (ids != null && !ids.isEmpty()) {
                for (int i = 0; i < ids.size(); i++) {
                    String k = (keys != null && i < keys.size()) ? keys.get(i) : null;
                    int sy = (scrolls != null && i < scrolls.length) ? scrolls[i] : 0;
                    navStack.add(new NavFrame(ids.get(i), k, sy));
                }
            }
            pendingFocusKey = savedInstanceState.getString(STATE_PENDING_KEY, null);
            pendingScrollY = savedInstanceState.getInt(STATE_PENDING_SCROLL, -1);
        }
        if (navStack.isEmpty()) {
            navStack.add(new NavFrame(SCR_ROOT, "cat_general", 0));
        }

        applyRootBackdrop();
        renderCurrentScreen(pendingFocusKey, pendingScrollY);
        pendingFocusKey = null;
        pendingScrollY = -1;
    }

    @Override
    protected void onResume() {
        super.onResume();
        UiFeedback.refresh(this);
        config = Config.load(this);
        animateTransitions = config.animations && !config.reducedMotion;
        applyRootBackdrop();
        if (pendingFocusKey != null || pendingScrollY >= 0) {
            renderCurrentScreen(pendingFocusKey, pendingScrollY);
            pendingFocusKey = null;
            pendingScrollY = -1;
        } else if (!navStack.isEmpty()) {
            String curKey = currentFocusedKey();
            int curScroll = settingsScroll != null ? settingsScroll.getScrollY() : 0;
            renderCurrentScreen(curKey, curScroll);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        captureCurrentFocusToTop();
        ArrayList<String> ids = new ArrayList<>(navStack.size());
        ArrayList<String> keys = new ArrayList<>(navStack.size());
        int[] scrolls = new int[navStack.size()];
        for (int i = 0; i < navStack.size(); i++) {
            ids.add(navStack.get(i).id);
            keys.add(navStack.get(i).focusedKey);
            scrolls[i] = navStack.get(i).scrollY;
        }
        outState.putStringArrayList(STATE_SCREEN_IDS, ids);
        outState.putStringArrayList(STATE_FOCUSED_KEYS, keys);
        outState.putIntArray(STATE_SCROLL_YS, scrolls);
        outState.putString(STATE_PENDING_KEY, pendingFocusKey);
        outState.putInt(STATE_PENDING_SCROLL, pendingScrollY);
    }

    @Override
    protected void onDestroy() {
        releaseWallpaperPreviewBitmap();
        super.onDestroy();
    }

    @Override
    public void finish() {
        if (anyChange) setResult(RESULT_OK);
        super.finish();
        Motion.applyFadeTransition(this, animateTransitions);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_UP && event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (popScreen()) return true;
            finish();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void applyRootBackdrop() {
        if (settingsRoot == null) return;
        if (config.reduceTransparency) {
            settingsRoot.setBackgroundColor(0xFF0E1015);
        } else {
            settingsRoot.setBackgroundResource(R.drawable.appletv_screen_bg);
        }
    }

    private NavFrame currentFrame() {
        return navStack.get(navStack.size() - 1);
    }

    private void captureCurrentFocusToTop() {
        if (navStack.isEmpty()) return;
        NavFrame top = currentFrame();
        String k = currentFocusedKey();
        if (k != null) top.focusedKey = k;
        if (settingsScroll != null) top.scrollY = settingsScroll.getScrollY();
    }

    private String currentFocusedKey() {
        View focused = getCurrentFocus();
        if (focused != null && focused.getTag() instanceof String) {
            return (String) focused.getTag();
        }
        for (View row : renderedRows) {
            if (row.hasFocus() && row.getTag() instanceof String) {
                return (String) row.getTag();
            }
        }
        return currentFrame().focusedKey;
    }

    private void pushScreen(String screenId) {
        captureCurrentFocusToTop();
        navStack.add(new NavFrame(screenId, null, 0));
        renderCurrentScreen(null, 0);
    }

    private boolean popScreen() {
        if (navStack.size() <= 1) return false;
        navStack.remove(navStack.size() - 1);
        NavFrame target = currentFrame();
        renderCurrentScreen(target.focusedKey, target.scrollY);
        return true;
    }

    private void renderCurrentScreen(String restoreFocusKey, int restoreScrollY) {
        config = Config.load(this);
        animateTransitions = config.animations && !config.reducedMotion;
        applyRootBackdrop();

        String screenId = currentFrame().id;
        renderedRows.clear();
        settingsRows.removeAllViews();

        updateHeaderAndLeftPane(screenId);
        populateScreen(screenId);
        wireFocusChain();

        final View targetFocus = findRowByKey(restoreFocusKey);
        final View focusTarget = targetFocus != null ? targetFocus
                : (renderedRows.isEmpty() ? null : renderedRows.get(0));
        final int targetScroll = Math.max(0, restoreScrollY);
        settingsScroll.post(() -> {
            settingsScroll.scrollTo(0, targetScroll);
            if (focusTarget != null) {
                focusTarget.requestFocus();
            }
        });
    }

    private View findRowByKey(String key) {
        if (key == null) return null;
        for (View v : renderedRows) {
            if (key.equals(v.getTag())) return v;
        }
        return null;
    }

    private void wireFocusChain() {
        int count = renderedRows.size();
        for (int i = 0; i < count; i++) {
            View row = renderedRows.get(i);
            int id = row.getId();
            row.setNextFocusLeftId(id);
            row.setNextFocusRightId(id);
            row.setNextFocusUpId(i > 0 ? renderedRows.get(i - 1).getId() : id);
            row.setNextFocusDownId(i + 1 < count ? renderedRows.get(i + 1).getId() : id);
        }
    }

    private void updateHeaderAndLeftPane(String screenId) {
        boolean isRoot = SCR_ROOT.equals(screenId);
        boolean isWallpaperPicker = SCR_WALLPAPER_PICKER.equals(screenId)
                || SCR_SS_WALLPAPER_PICKER.equals(screenId);

        settingsTitle.setText(titleForScreen(screenId));
        if (isRoot || navStack.size() <= 1) {
            settingsBreadcrumb.setVisibility(View.GONE);
        } else {
            boolean rtl = getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            String sep = rtl ? " \u2039 " : " \u203A ";
            StringBuilder crumb = new StringBuilder();
            for (int i = 0; i < navStack.size() - 1; i++) {
                if (i > 0) crumb.append(sep);
                crumb.append(titleForScreen(navStack.get(i).id));
            }
            settingsBreadcrumb.setText(crumb.toString());
            settingsBreadcrumb.setTextColor(config.reduceTransparency ? 0xFFD4D8E2 : 0xFF8D93A3);
            settingsBreadcrumb.setVisibility(View.VISIBLE);
        }

        if (isRoot) {
            releaseWallpaperPreviewBitmap();
            deviceHero.setVisibility(View.VISIBLE);
            submenuHeroBox.setVisibility(View.GONE);
            wallpaperPreviewBox.setVisibility(View.GONE);
        } else if (isWallpaperPicker) {
            deviceHero.setVisibility(View.GONE);
            submenuHeroBox.setVisibility(View.GONE);
            wallpaperPreviewBox.setVisibility(View.VISIBLE);
        } else {
            releaseWallpaperPreviewBitmap();
            deviceHero.setVisibility(View.GONE);
            wallpaperPreviewBox.setVisibility(View.GONE);
            submenuHeroBox.setVisibility(View.VISIBLE);
            heroIcon.setImageResource(iconForScreen(screenId));
            heroHeading.setText(titleForScreen(screenId));
            heroSummary.setText(defaultSummaryForScreen(screenId));
            heroSummary.setTextColor(config.reduceTransparency ? 0xFFD4D8E2 : 0xFFA4AAB8);
        }
    }

    private String titleForScreen(String id) {
        switch (id) {
            case SCR_ROOT: return getString(R.string.settings_root_title);
            case SCR_GENERAL: return getString(R.string.cat_general);
            case SCR_APPEARANCE: return getString(R.string.sec_appearance);
            case SCR_WALLPAPER_PICKER: return getString(R.string.wallpaper_label);
            case SCR_WALLPAPER_DIM: return "Wallpaper Dimming";
            case SCR_GLASS_GLOW: return "Glass & Glow Effects";
            case SCR_CLOCK_HEADER: return getString(R.string.sec_clock_header);
            case SCR_CLOCK_FORMAT: return "Clock Format";
            case SCR_ACCESSIBILITY: return getString(R.string.sec_accessibility);
            case SCR_ABOUT: return getString(R.string.sec_about);
            case SCR_HOME_SCREEN: return getString(R.string.cat_home_screen);
            case SCR_HOME_LAYOUT: return getString(R.string.sec_home_layout);
            case SCR_COLUMNS: return getString(R.string.columns_label);
            case SCR_ROWS_BELOW: return getString(R.string.rows_below_dock_label);
            case SCR_DOCK_OPACITY: return getString(R.string.dock_opacity_label);
            case SCR_CINEMATIC: return getString(R.string.sec_cinematic_shelf);
            case SCR_CINEMATIC_ART: return "Shelf Artwork Opacity";
            case SCR_CINEMATIC_SCRIM: return "Shelf Scrim Strength";
            case SCR_SKY_INTENSITY: return "Time-of-Day Sky Glow";
            case SCR_LIBRARY_OPTIONS: return getString(R.string.sec_library_options);
            case SCR_TILE_INPUT_OPTIONS: return getString(R.string.sec_tile_input_options);
            case SCR_VIDEO_AUDIO: return getString(R.string.cat_video_audio);
            case SCR_NOW_PLAYING: return getString(R.string.sec_now_playing);
            case SCR_EDIT_QS_CONTROLS: return getString(R.string.qs_edit_controls);
            case SCR_SCREENSAVER: return getString(R.string.cat_screensaver);
            case SCR_SS_IDLE_TIMEOUT: return "Idle Timeout";
            case SCR_SS_BACKGROUND: return getString(R.string.set_ss_background);
            case SCR_SS_WALLPAPER_PICKER: return "Screensaver Wallpaper";
            case SCR_SS_DIM: return "Screensaver Dimming";
            case SCR_NOTIFICATIONS: return getString(R.string.cat_notifications);
            case SCR_REMOTES_DEVICES: return getString(R.string.cat_remotes_devices);
            case SCR_APPS: return getString(R.string.cat_apps);
            case SCR_HIDDEN_APPS: return getString(R.string.hidden_apps_label);
            case SCR_NETWORK: return getString(R.string.cat_network);
            case SCR_NETWORK_APPEARANCE: return "Top Bar Network Options";
            case SCR_TRANSFER_BACKUP: return getString(R.string.cat_transfer_backup);
            case SCR_SYSTEM: return getString(R.string.cat_system);
            case SCR_SLEEP_TIMER: return getString(R.string.tile_sleep_timer);
            case SCR_STARTUP_DEST: return getString(R.string.set_startup_dest);
            case SCR_STARTUP_APP_PICKER: return getString(R.string.opt_selected_app);
            case SCR_ADVANCED: return getString(R.string.sec_advanced);
            case SCR_STARTUP_NET_WAIT: return "Startup Network Wait";
            case SCR_DEVICE_COMPAT: return getString(R.string.sec_device_compatibility);
            default: return getString(R.string.settings_root_title);
        }
    }

    private int iconForScreen(String id) {
        switch (id) {
            case SCR_APPEARANCE:
            case SCR_WALLPAPER_DIM:
            case SCR_GLASS_GLOW:
            case SCR_SKY_INTENSITY:
                return R.drawable.ic_picture;
            case SCR_VIDEO_AUDIO:
            case SCR_NOW_PLAYING:
                return R.drawable.ic_music;
            case SCR_SCREENSAVER:
            case SCR_SS_IDLE_TIMEOUT:
            case SCR_SS_BACKGROUND:
            case SCR_SS_DIM:
                return R.drawable.ic_screensaver;
            case SCR_NOTIFICATIONS:
                return R.drawable.ic_notifications;
            case SCR_REMOTES_DEVICES:
            case SCR_DEVICE_COMPAT:
                return R.drawable.ic_remote;
            case SCR_APPS:
            case SCR_HIDDEN_APPS:
            case SCR_HOME_SCREEN:
            case SCR_HOME_LAYOUT:
            case SCR_COLUMNS:
            case SCR_ROWS_BELOW:
            case SCR_DOCK_OPACITY:
            case SCR_CINEMATIC:
            case SCR_CINEMATIC_ART:
            case SCR_CINEMATIC_SCRIM:
            case SCR_LIBRARY_OPTIONS:
            case SCR_TILE_INPUT_OPTIONS:
                return R.drawable.ic_apps;
            case SCR_NETWORK:
            case SCR_NETWORK_APPEARANCE:
                return R.drawable.ic_wifi;
            case SCR_TRANSFER_BACKUP:
                return R.drawable.ic_sync;
            case SCR_SLEEP_TIMER:
                return R.drawable.ic_timer;
            default:
                return R.drawable.ic_settings;
        }
    }

    private String defaultSummaryForScreen(String id) {
        switch (id) {
            case SCR_GENERAL: return "Appearance, clock display, accessibility, language and device details.";
            case SCR_APPEARANCE: return "Configure Home wallpaper, visual effects, motion and interface sounds.";
            case SCR_ACCESSIBILITY: return getString(R.string.set_reduce_transparency_desc);
            case SCR_HOME_SCREEN: return "Customize top bar buttons, stats readout, grid layout and App Library.";
            case SCR_VIDEO_AUDIO: return "Now Playing visibility on Home and Quick Settings, plus Android display and audio.";
            case SCR_NOW_PLAYING: return "Toggle Now Playing independently on Home and Quick Settings. Media keeps playing when hidden.";
            case SCR_SCREENSAVER: return "Configure idle clock activation, monthly calendar, background style and dimming.";
            case SCR_NOTIFICATIONS: return "View cached notifications or configure notification access in Android.";
            case SCR_REMOTES_DEVICES: return "Remap remote keys, edit Quick Settings tiles, or pair Bluetooth accessories.";
            case SCR_APPS: return "Manage hidden Home apps and open Android application management.";
            case SCR_NETWORK: return "View live connection status and open Android network and VPN settings.";
            case SCR_TRANSFER_BACKUP: return "Start local Wi-Fi transfer, manage pairing PIN, or back up and restore settings.";
            case SCR_SYSTEM: return "Sleep timer, startup behavior, memory optimization, and TV system settings.";
            case SCR_DEVICE_COMPAT: return "Hardware-specific remote key filtering, volume handling, and power-on behavior.";
            default: return "";
        }
    }

    private void populateScreen(String id) {
        switch (id) {
            case SCR_ROOT: buildRootScreen(); break;
            case SCR_GENERAL: buildGeneralScreen(); break;
            case SCR_APPEARANCE: buildAppearanceScreen(); break;
            case SCR_WALLPAPER_PICKER: buildWallpaperPickerScreen(); break;
            case SCR_WALLPAPER_DIM: buildPercentPickerScreen("wallpaper_dim", config.wallpaperDim, 0, 90, 10, false); break;
            case SCR_GLASS_GLOW: buildGlassGlowScreen(); break;
            case SCR_CLOCK_HEADER: buildClockHeaderScreen(); break;
            case SCR_CLOCK_FORMAT: buildClockFormatScreen(); break;
            case SCR_ACCESSIBILITY: buildAccessibilityScreen(); break;
            case SCR_ABOUT: buildAboutScreen(); break;
            case SCR_HOME_SCREEN: buildHomeScreen(); break;
            case SCR_HOME_LAYOUT: buildHomeLayoutScreen(); break;
            case SCR_COLUMNS: buildIntChoiceScreen("columns", config.columns, new int[]{4, 5, 6, 7}, "%d apps per row"); break;
            case SCR_ROWS_BELOW: buildIntChoiceScreen(Config.ROWS_BELOW_DOCK_KEY, config.rowsBelowDock, new int[]{0, 1, 2, 3}, "%d rows"); break;
            case SCR_DOCK_OPACITY: buildDockOpacityScreen(); break;
            case SCR_CINEMATIC: buildCinematicScreen(); break;
            case SCR_CINEMATIC_ART: buildPercentPickerScreen("cinematic_art", config.cinematicArt, 0, 100, 10, false); break;
            case SCR_CINEMATIC_SCRIM: buildPercentPickerScreen("cinematic_scrim", config.cinematicScrim, 0, 100, 10, false); break;
            case SCR_SKY_INTENSITY: buildPercentPickerScreen("sky_intensity", config.skyIntensity, 0, 100, 10, false); break;
            case SCR_LIBRARY_OPTIONS: buildLibraryOptionsScreen(); break;
            case SCR_TILE_INPUT_OPTIONS: buildTileInputOptionsScreen(); break;
            case SCR_VIDEO_AUDIO: buildVideoAudioScreen(); break;
            case SCR_NOW_PLAYING: buildNowPlayingScreen(); break;
            case SCR_EDIT_QS_CONTROLS: buildEditQsControlsScreen(); break;
            case SCR_SCREENSAVER: buildScreensaverScreen(); break;
            case SCR_SS_IDLE_TIMEOUT: buildIdleTimeoutScreen(); break;
            case SCR_SS_BACKGROUND: buildScreensaverBgScreen(); break;
            case SCR_SS_WALLPAPER_PICKER: buildScreensaverWallpaperPickerScreen(); break;
            case SCR_SS_DIM: buildPercentPickerScreen("clock_wallpaper_dim", config.clockWallpaperDim, 0, 90, 10, false); break;
            case SCR_NOTIFICATIONS: buildNotificationsScreen(); break;
            case SCR_REMOTES_DEVICES: buildRemotesDevicesScreen(); break;
            case SCR_APPS: buildAppsScreen(); break;
            case SCR_HIDDEN_APPS: buildHiddenAppsScreen(); break;
            case SCR_NETWORK: buildNetworkScreen(); break;
            case SCR_NETWORK_APPEARANCE: buildNetworkAppearanceScreen(); break;
            case SCR_TRANSFER_BACKUP: buildTransferBackupScreen(); break;
            case SCR_SYSTEM: buildSystemScreen(); break;
            case SCR_SLEEP_TIMER: buildSleepTimerScreen(); break;
            case SCR_STARTUP_DEST: buildStartupDestScreen(); break;
            case SCR_STARTUP_APP_PICKER: buildStartupAppPickerScreen(); break;
            case SCR_ADVANCED: buildAdvancedScreen(); break;
            case SCR_STARTUP_NET_WAIT: buildStartupNetWaitScreen(); break;
            case SCR_DEVICE_COMPAT: buildDeviceCompatScreen(); break;
        }
    }

    private void buildRootScreen() {
        addNavRow("cat_general", R.string.cat_general, null, () -> pushScreen(SCR_GENERAL));
        addNavRow("cat_home_screen", R.string.cat_home_screen, null, () -> pushScreen(SCR_HOME_SCREEN));
        addNavRow("cat_video_audio", R.string.cat_video_audio, null, () -> pushScreen(SCR_VIDEO_AUDIO));
        addNavRow("cat_screensaver", R.string.cat_screensaver, null, () -> pushScreen(SCR_SCREENSAVER));
        addNavRow("cat_notifications", R.string.cat_notifications, null, () -> pushScreen(SCR_NOTIFICATIONS));
        addNavRow("cat_remotes_devices", R.string.cat_remotes_devices, null, () -> pushScreen(SCR_REMOTES_DEVICES));
        addNavRow("cat_apps", R.string.cat_apps, null, () -> pushScreen(SCR_APPS));
        addNavRow("cat_network", R.string.cat_network, null, () -> pushScreen(SCR_NETWORK));
        addNavRow("cat_transfer_backup", R.string.cat_transfer_backup, null, () -> pushScreen(SCR_TRANSFER_BACKUP));
        addNavRow("cat_system", R.string.cat_system, null, () -> pushScreen(SCR_SYSTEM));
    }

    private void buildGeneralScreen() {
        addNavRow("sec_appearance", getString(R.string.sec_appearance), null,
                "Wallpaper, animations, advanced effects, and interface sounds.", () -> pushScreen(SCR_APPEARANCE));
        addNavRow("sec_clock_header", getString(R.string.sec_clock_header), clockFormatLabel(config.clockFormat),
                "Clock 12/24-hour format and top header date styling.", () -> pushScreen(SCR_CLOCK_HEADER));
        addNavRow("sec_accessibility", getString(R.string.sec_accessibility),
                config.reduceTransparency ? "Reduce Transparency On" : null,
                getString(R.string.set_reduce_transparency_desc), () -> pushScreen(SCR_ACCESSIBILITY));
        addNavRow("sys_language", "Language (Android Settings)", Locale.getDefault().getDisplayName(),
                "Opens Android language preferences.", () -> launchAndroidSettings(Settings.ACTION_LOCALE_SETTINGS));
        addNavRow("sec_about", getString(R.string.sec_about), "v8.0",
                "Launcher version and TV hardware details.", () -> pushScreen(SCR_ABOUT));
    }

    private void buildAppearanceScreen() {
        boolean wpOn = isHomeWallpaperOn();
        addSwitchRow("sw_home_wallpaper", getString(R.string.set_home_wallpaper), wpOn,
                "Show wallpaper behind the Home screen grid.", v -> {
                    if (v) {
                        String prev = choices.getString(PREV_WALLPAPER_KEY, null);
                        if (prev != null && !"none".equals(prev)) {
                            choices.edit().putString(Config.WALLPAPER_CHOICE_KEY, prev).apply();
                        } else {
                            choices.edit().remove(Config.WALLPAPER_CHOICE_KEY).apply();
                        }
                    } else {
                        String cur = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
                        if (cur != null && !"none".equals(cur)) {
                            choices.edit().putString(PREV_WALLPAPER_KEY, cur).apply();
                        }
                        choices.edit().putString(Config.WALLPAPER_CHOICE_KEY, "none").apply();
                    }
                    markChanged();
                });

        addNavRow("pick_wallpaper", getString(R.string.wallpaper_label), currentWallpaperLabel(),
                "Choose a wallpaper, pick an image from storage, or upload from your phone.",
                () -> pushScreen(SCR_WALLPAPER_PICKER));

        addNavRow("pick_wallpaper_dim", "Wallpaper Dimming", config.wallpaperDim + "%",
                "Darken the Home wallpaper to improve app icon legibility.", () -> pushScreen(SCR_WALLPAPER_DIM));

        addSwitchRow("sw_animations", getString(R.string.animations), config.animations,
                getString(R.string.animations_summary), v -> {
                    choices.edit().putBoolean("animations", v).apply();
                    markChanged();
                });

        addSwitchRow("sw_advanced_effects", getString(R.string.advanced_effects), config.advancedEffects,
                getString(R.string.advanced_effects_summary), v -> {
                    choices.edit().putBoolean("advanced_effects", v).apply();
                    markChanged();
                });

        addSwitchRow("sw_interface_sounds", getString(R.string.set_interface_sounds),
                UiFeedback.isSoundsEnabled(this),
                getString(R.string.ui_sounds_summary), v -> {
                    choices.edit().putBoolean(UiFeedback.KEY_UI_SOUNDS, v).apply();
                    UiFeedback.refresh(this);
                    markChanged();
                });

        addNavRow("sec_glass_glow", "Glass & Glow Effects", null,
                "Focus ring style, dock glow color, and time-of-day sky.", () -> pushScreen(SCR_GLASS_GLOW));
    }

    private void buildWallpaperPickerScreen() {
        String chosen = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        File dir = Config.dir(this);
        List<File> collection = dir == null ? Collections.emptyList()
                : WallpaperRotation.images(new File(dir, "wallpapers"));
        File customFile = findCustomWallpaperFile();

        addChoiceRow("wp_default", getString(R.string.wallpaper_theme_default), chosen == null,
                () -> updatePreview(findThemeDefaultFile(dir), getString(R.string.wallpaper_theme_default)), () -> {
                    choices.edit().remove(Config.WALLPAPER_CHOICE_KEY).apply();
                    markChanged();
                    popScreen();
                });

        addChoiceRow("wp_none", getString(R.string.wallpaper_none), "none".equals(chosen),
                () -> updatePreview(null, getString(R.string.wallpaper_none)), () -> {
                    choices.edit().putString(Config.WALLPAPER_CHOICE_KEY, "none").apply();
                    markChanged();
                    popScreen();
                });

        if (customFile != null) {
            final String path = customFile.getAbsolutePath();
            boolean isCustomSelected = path.equals(chosen)
                    || customFile.getName().equals(chosen)
                    || config.wallpapers.contains(customFile);
            addChoiceRow("wp_custom_saved", "Uploaded / Custom Image", isCustomSelected,
                    () -> updatePreview(customFile, "Custom Wallpaper"), () -> {
                        choices.edit().putString(Config.WALLPAPER_CHOICE_KEY, path).apply();
                        markChanged();
                        popScreen();
                    });
        }

        for (File f : collection) {
            final String name = f.getName();
            addChoiceRow("wp_file_" + name, name, name.equals(chosen),
                    () -> updatePreview(f, name), () -> {
                        choices.edit().putString(Config.WALLPAPER_CHOICE_KEY, name).apply();
                        markChanged();
                        popScreen();
                    });
        }

        addActionRow("wp_pick_storage", getString(R.string.wallpaper_custom_browse), null,
                "Open Android document picker to import a JPG or PNG image.",
                () -> launchDocumentImagePicker(PICK_WALLPAPER_REQUEST));

        addNavRow("wp_upload_phone", getString(R.string.set_upload_from_phone), syncServerStatusLabel(),
                "Start local Wi-Fi server to upload wallpapers from your phone.",
                () -> launchOwnedExternal(new Intent(this, WallpaperSyncActivity.class)));
    }

    private void buildGlassGlowScreen() {
        addNavRow("glass_style_cycle", "Focus Highlight Style", config.glassStyle,
                "Cycle between glass, rim, or halo highlight.", () -> {
                    String next = Config.GLASS_DEFAULT.equals(config.glassStyle) ? Config.GLASS_RIM
                            : (Config.GLASS_RIM.equals(config.glassStyle) ? Config.GLASS_HALO : Config.GLASS_DEFAULT);
                    choices.edit().putString("glass_style", next).apply();
                    markChangedAndRefresh();
                });
        addNavRow("glow_style_cycle", "Ambient Glow Shape", config.glowStyle,
                "Switch between radial and horizon glow shapes.", () -> {
                    String next = Config.GLOW_RADIAL.equals(config.glowStyle) ? Config.GLOW_HORIZON : Config.GLOW_RADIAL;
                    choices.edit().putString("glow_style", next).apply();
                    markChangedAndRefresh();
                });
        addSwitchRow("sw_ambient_glow", "Dock Ambient Glow", config.ambientGlow,
                "Tint the background with the focused dock app's accent color.", v -> {
                    choices.edit().putBoolean("ambient_glow", v).apply();
                    markChanged();
                });
        addSwitchRow("sw_app_glow", "Tile Focus Glow", config.appGlow,
                "Match each focused app tile's highlight to its icon color.", v -> {
                    choices.edit().putBoolean("app_glow", v).apply();
                    markChanged();
                });
        addSwitchRow("sw_glow_snap", "Instant Glow Transitions", config.glowSnap,
                "Snap ambient glow color immediately instead of cross-fading.", v -> {
                    choices.edit().putBoolean("glow_snap", v).apply();
                    markChanged();
                });
        addSwitchRow("sw_wp_true_color", "32-bit True Color Wallpaper", config.wallpaperTrueColor,
                "Higher gradient fidelity on 4K TVs (uses more memory).", v -> {
                    choices.edit().putBoolean("wallpaper_true_color", v).apply();
                    markChanged();
                });
        addSwitchRow("sw_wp_captions", "Wallpaper Captions", config.wallpaperCaptions,
                "Display caption names for theme wallpaper collections.", v -> {
                    choices.edit().putBoolean("wallpaper_captions", v).apply();
                    markChanged();
                });
        addNavRow("pick_sky", "Time-of-Day Sky Glow", config.skyIntensity + "%",
                "Subtle ambient sky gradient when no wallpaper is shown.", () -> pushScreen(SCR_SKY_INTENSITY));
    }

    private void buildClockHeaderScreen() {
        addNavRow("pick_clock_format", "Clock Format", clockFormatLabel(config.clockFormat),
                "Choose System, 12-hour, or 24-hour clock format.", () -> pushScreen(SCR_CLOCK_FORMAT));
        addSwitchRow("sw_show_greeting", "Show Greeting", config.showGreeting,
                "Show time-of-day greeting above the clock.", v -> {
                    choices.edit().putBoolean("show_greeting", v).apply();
                    markChanged();
                });
        addSwitchRow("sw_date_below", "Date Below Clock", config.dateBelowClock,
                "Place the date on its own line under the clock.", v -> {
                    choices.edit().putBoolean("date_below_clock", v).apply();
                    markChanged();
                });
        addSwitchRow("sw_header_white", "Full-White Date & Divider", config.headerWhite,
                "Render the date in full bright white for extra contrast.", v -> {
                    choices.edit().putBoolean("header_white", v).apply();
                    markChanged();
                });
    }

    private void buildClockFormatScreen() {
        String cur = config.clockFormat;
        addChoiceRow("cf_system", getString(R.string.clock_format_system), !"12".equals(cur) && !"24".equals(cur),
                null, () -> {
                    choices.edit().putString(Config.CLOCK_FORMAT_KEY, "system").apply();
                    markChanged();
                    popScreen();
                });
        addChoiceRow("cf_12", getString(R.string.clock_format_12), "12".equals(cur), null, () -> {
            choices.edit().putString(Config.CLOCK_FORMAT_KEY, "12").apply();
            markChanged();
            popScreen();
        });
        addChoiceRow("cf_24", getString(R.string.clock_format_24), "24".equals(cur), null, () -> {
            choices.edit().putString(Config.CLOCK_FORMAT_KEY, "24").apply();
            markChanged();
            popScreen();
        });
    }

    private void buildAccessibilityScreen() {
        addSwitchRow("sw_reduce_transparency", getString(R.string.set_reduce_transparency), config.reduceTransparency,
                getString(R.string.set_reduce_transparency_desc), v -> {
                    choices.edit().putBoolean(Config.REDUCE_TRANSPARENCY_KEY, v).apply();
                    markChangedAndRefresh();
                });
        addSwitchRow("sw_reduced_motion", "Reduced Motion", config.reducedMotion,
                "Replace spring bounce animations with short, calm transitions.", v -> {
                    choices.edit().putBoolean("reduced_motion", v).apply();
                    markChanged();
                });
        addSwitchRow("sw_focus_overshoot", "Focus Spring Overshoot", config.focusOvershoot,
                "Allow focus highlights to slightly overshoot before settling.", v -> {
                    choices.edit().putBoolean("focus_overshoot", v).apply();
                    markChanged();
                });
        addSwitchRow("sw_always_names", "Always Show App Names", config.alwaysShowNames,
                "Keep labels visible beneath every tile instead of only when focused.", v -> {
                    choices.edit().putBoolean("always_show_names", v).apply();
                    markChanged();
                });
    }

    private void buildAboutScreen() {
        addInfoRow("about_app", "Launcher", "CleanYourTV v8.0");
        addInfoRow("about_device", "Device", Build.MANUFACTURER + " " + Build.MODEL);
        addInfoRow("about_android", "Android Version", "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        addInfoRow("about_theme", "Theme Overlay", config.overlayApplied ? "Active (config.json)" : "Built-in defaults");
    }

    private void buildHomeScreen() {
        addSwitchRow("sw_inputs_btn", getString(R.string.set_show_inputs_btn), config.showInputsPill,
                "Show or hide the Inputs button on Home. Remote Input button keeps working.", v -> {
                    choices.edit().putBoolean("show_inputs_pill", v).apply();
                    markChanged();
                });
        addSwitchRow("sw_network_btn", getString(R.string.set_show_network_btn), config.showNetworkPill,
                "Show or hide the Network button on Home.", v -> {
                    choices.edit().putBoolean("show_network_pill", v).apply();
                    markChanged();
                });
        addSwitchRow("sw_stats", getString(R.string.set_show_memory_storage), config.showStats,
                getString(R.string.stats_summary), v -> {
                    choices.edit().putBoolean("show_stats", v).apply();
                    markChanged();
                });
        addSwitchRow("sw_app_library", getString(R.string.set_enable_app_library), config.appLibrary,
                "Enable the full-catalog App Library drawer on Home.", v -> {
                    choices.edit().putBoolean("app_library", v).apply();
                    markChanged();
                });
        addActionRow("act_reorder", getString(R.string.reorder_apps), null,
                getString(R.string.reorder_apps_summary), () -> {
                    setResult(RESULT_OK, new Intent().putExtra(EXTRA_REORDER, true));
                    finish();
                });
        addNavRow("sec_home_layout", getString(R.string.sec_home_layout),
                config.columns + " col · " + config.dockOpacity + "%",
                "Grid columns, rows below dock, dock band opacity, and top bar appearance.",
                () -> pushScreen(SCR_HOME_LAYOUT));
        addNavRow("sec_cinematic", getString(R.string.sec_cinematic_shelf),
                config.cinematicHome ? getString(R.string.opt_on) : getString(R.string.opt_off),
                "Focused dock artwork banner and scrim opacity.", () -> pushScreen(SCR_CINEMATIC));
        addNavRow("sec_library_opts", getString(R.string.sec_library_options), null,
                "Top bar library shortcut and OK click behavior.", () -> pushScreen(SCR_LIBRARY_OPTIONS));
        addNavRow("sec_tile_input_opts", getString(R.string.sec_tile_input_options), null,
                "Hold-OK tile menu, uniform tiles, module cards, and input list filtering.",
                () -> pushScreen(SCR_TILE_INPUT_OPTIONS));
    }

    private void buildHomeLayoutScreen() {
        addNavRow("pick_columns", getString(R.string.columns_label), String.valueOf(config.columns),
                "Number of app tiles shown per row (4 to 7).", () -> pushScreen(SCR_COLUMNS));
        addNavRow("pick_rows_below", getString(R.string.rows_below_dock_label), String.valueOf(config.rowsBelowDock),
                "Visible grid rows beneath the dock band (0 to 3).", () -> pushScreen(SCR_ROWS_BELOW));
        addNavRow("pick_dock_opacity", getString(R.string.dock_opacity_label), config.dockOpacity + "%",
                "Dock band transparency level.", () -> pushScreen(SCR_DOCK_OPACITY));
        addSwitchRow("sw_dock_row", "Separate Dock Row", config.dockRow,
                "Keep the dock in its own dedicated row above the grid.", v -> {
                    choices.edit().putBoolean("dock_row", v).apply();
                    markChanged();
                });
        addSwitchRow("sw_dock_title", "Show Focused App Title", config.dockTitle,
                "Show large title above the focused dock app.", v -> {
                    choices.edit().putBoolean("dock_title", v).apply();
                    markChanged();
                });
        addSwitchRow("sw_dock_glass", "Frosted Glass Dock Band", config.dockGlass,
                "Use translucent glass styling on the dock band.", v -> {
                    choices.edit().putBoolean("dock_glass", v).apply();
                    markChanged();
                });
        addSwitchRow("sw_dock_band", "Draw Dock Background Band", config.dockBand,
                "Show a subtle backing band behind dock tiles.", v -> {
                    choices.edit().putBoolean("dock_band", v).apply();
                    markChanged();
                });
        addSwitchRow("sw_dock_even", "Even Dock Padding", config.dockEvenPadding,
                "Match horizontal dock padding to tile spacing.", v -> {
                    choices.edit().putBoolean("dock_even_padding", v).apply();
                    markChanged();
                });
        addSwitchRow("sw_single_row", "Single-Row Horizontal Dock", config.singleRow,
                "Place all apps into one sideways-scrolling dock row.", v -> {
                    choices.edit().putBoolean("single_row", v).apply();
                    markChanged();
                });
        addSwitchRow("sw_fill_dock", "Fill Empty Dock Slots", config.fillDock,
                "Automatically fill unused dock slots with grid apps.", v -> {
                    choices.edit().putBoolean("fill_dock", v).apply();
                    markChanged();
                });
        addSwitchRow("sw_fade_rows", "Fade Rows Under Clock", config.fadeRows,
                "Softly fade grid rows as they scroll up under the header.", v -> {
                    choices.edit().putBoolean("fade_rows", v).apply();
                    markChanged();
                });
        addSwitchRow("sw_top_menu", "Combine Top Bar into Gear Menu", config.topMenu,
                "Fold Inputs, Network, and Settings into a single gear dropdown.", v -> {
                    choices.edit().putBoolean("top_menu", v).apply();
                    markChanged();
                });
        addSwitchRow("sw_launcher_btn", "Top Bar Opens Launcher Settings", config.launcherSettingsButton,
                "Make clicking top-bar Settings open Launcher Settings directly.", v -> {
                    choices.edit().putBoolean("launcher_settings_button", v).apply();
                    markChanged();
                });
        addSwitchRow("sw_reorder_btn", "Show Reorder Top Button", config.reorderButton,
                "Place an explicit Reorder button in the top bar.", v -> {
                    choices.edit().putBoolean("reorder_button", v).apply();
                    markChanged();
                });
        addSwitchRow("sw_icon_only_btns", "Icon-Only Top Buttons", config.iconOnlyButtons,
                "Render top-bar Network and Settings pills as compact round icons.", v -> {
                    choices.edit().putBoolean("icon_only_buttons", v).apply();
                    markChanged();
                });
        addSwitchRow("sw_icon_only_inputs", "Icon-Only Inputs Button", config.iconOnlyInputs,
                "Render the Inputs pill as a compact round icon.", v -> {
                    choices.edit().putBoolean("icon_only_inputs", v).apply();
                    markChanged();
                });
    }

    private void buildDockOpacityScreen() {
        for (int op : DOCK_OPACITIES) {
            final int val = op;
            addChoiceRow("dock_op_" + op, op + "%", config.dockOpacity == op, null, () -> {
                choices.edit().putInt(Config.DOCK_OPACITY_KEY, val).apply();
                markChanged();
                popScreen();
            });
        }
    }

    private void buildCinematicScreen() {
        addSwitchRow("sw_cinematic_home", "Cinematic Top Shelf", config.cinematicHome,
                "Display large artwork backdrop behind the dock shelf.", v -> {
                    choices.edit().putBoolean("cinematic_home", v).apply();
                    markChanged();
                });
        addSwitchRow("sw_cinematic_dots", "Show Dock Position Dots", config.cinematicDots,
                "Display position dots under the focused shelf app title.", v -> {
                    choices.edit().putBoolean("cinematic_dots", v).apply();
                    markChanged();
                });
        addSwitchRow("sw_cinematic_white", "White Shelf Focus Ring", config.cinematicWhiteFocus,
                "Keep focus border crisp white instead of app accent color.", v -> {
                    choices.edit().putBoolean("cinematic_white_focus", v).apply();
                    markChanged();
                });
        addNavRow("pick_cinematic_art", "Shelf Artwork Opacity", config.cinematicArt + "%",
                "Opacity of focused app artwork on the shelf.", () -> pushScreen(SCR_CINEMATIC_ART));
        addNavRow("pick_cinematic_scrim", "Shelf Dark Scrim", config.cinematicScrim + "%",
                "Strength of the dark readability scrim behind shelf text.", () -> pushScreen(SCR_CINEMATIC_SCRIM));
    }

    private void buildLibraryOptionsScreen() {
        addSwitchRow("sw_lib_btn", "Top Bar App Library Button", config.libraryButton,
                "Show quick App Library button in the top bar when library is enabled.", v -> {
                    choices.edit().putBoolean("library_button", v).apply();
                    markChanged();
                });
        addSwitchRow("sw_lib_pill", "Grid Bottom Library Pill", config.showLibraryPill,
                "Show App Library button below the last app row.", v -> {
                    choices.edit().putBoolean("show_library_pill", v).apply();
                    markChanged();
                });
        addSwitchRow("sw_lib_ok_opens", "OK Opens Apps Directly", config.libraryOkOpens,
                "Launch apps on OK press inside App Library instead of toggling Home.", v -> {
                    choices.edit().putBoolean("library_ok_opens", v).apply();
                    markChanged();
                });
        addSwitchRow("sw_lib_selection", "Quick Toggle Home Membership", config.appLibrarySelection,
                "Allow adding/removing Home apps directly from App Library.", v -> {
                    choices.edit().putBoolean("app_library_selection", v).apply();
                    markChanged();
                });
    }

    private void buildTileInputOptionsScreen() {
        addSwitchRow("sw_tile_menu", "Hold OK Tile Options Menu", config.tileMenu,
                "Long-press OK on any tile for Move, Rename, Hide, and App Info.", v -> {
                    choices.edit().putBoolean("tile_menu", v).apply();
                    markChanged();
                });
        addSwitchRow("sw_uniform_tiles", "Uniform Tinted Tiles", config.uniformTiles,
                "Render app icons on uniform tinted cards instead of banners.", v -> {
                    choices.edit().putBoolean("uniform_tiles", v).apply();
                    markChanged();
                });
        addSwitchRow("sw_module_cards", "Addon Module Cards", config.moduleCards,
                "Allow companion CleanYourTV addons to display cards on Home.", v -> {
                    choices.edit().putBoolean("module_cards", v).apply();
                    markChanged();
                });
        addSwitchRow("sw_home_picker_tile", "Show Default Home Shortcut", config.showHomeSettingsShortcut,
                "Include a Default Home picker shortcut inside top-bar menu.", v -> {
                    choices.edit().putBoolean("show_home_settings_shortcut", v).apply();
                    markChanged();
                });
        addSwitchRow("sw_inputs_conn_only", "Show Connected Inputs Only", config.inputsConnectedOnly,
                "Fold disconnected HDMI/AV ports under More inputs.", v -> {
                    choices.edit().putBoolean("inputs_connected_only", v).apply();
                    markChanged();
                });
        addSwitchRow("sw_inputs_cec_only", "CEC State Only in Inputs", config.inputsCecStateOnly,
                "Show connected status only on active CEC devices.", v -> {
                    choices.edit().putBoolean("inputs_cec_state_only", v).apply();
                    markChanged();
                });
    }

    private void buildVideoAudioScreen() {
        addNavRow("sec_now_playing", getString(R.string.sec_now_playing),
                (config.showNowPlayingHome || config.showNowPlayingQuickSettings) ? getString(R.string.opt_on) : getString(R.string.opt_off),
                "Control Now Playing visibility on Home and Quick Settings independently.",
                () -> pushScreen(SCR_NOW_PLAYING));
        addNavRow("android_display", "Display Settings (Android)", null,
                "Opens Android TV picture mode, resolution, and HDR settings.",
                () -> launchAndroidSettings(Settings.ACTION_DISPLAY_SETTINGS));
        addNavRow("android_sound", "Sound Settings (Android)", null,
                "Opens Android TV audio output, surround, and speaker settings.",
                () -> launchAndroidSettings(Settings.ACTION_SOUND_SETTINGS));
        addNavRow("open_qs", getString(R.string.quick_settings_title), null,
                "Open the Quick Settings overlay.",
                () -> launchOwnedExternal(new Intent(this, QuickSettingsActivity.class)));
        addNavRow("edit_qs", getString(R.string.qs_edit_controls), null,
                "Choose which optional control tiles appear in Quick Settings.",
                () -> pushScreen(SCR_EDIT_QS_CONTROLS));
    }

    private void buildNowPlayingScreen() {
        addSwitchRow("sw_np_home", getString(R.string.set_show_now_playing_home), config.showNowPlayingHome,
                "Display live track pill on Home when media is active. Playback continues when hidden.", v -> {
                    choices.edit().putBoolean(Config.SHOW_NOW_PLAYING_HOME_KEY, v).apply();
                    markChanged();
                });
        addSwitchRow("sw_np_qs", getString(R.string.set_show_now_playing_qs), config.showNowPlayingQuickSettings,
                "Display Now Playing playback card in Quick Settings. Playback continues when hidden.", v -> {
                    choices.edit().putBoolean(Config.SHOW_NOW_PLAYING_QS_KEY, v).apply();
                    markChanged();
                });
    }

    private void buildEditQsControlsScreen() {
        Set<String> hidden = QuickSettingsPrefs.loadHiddenTiles(this);
        for (String tileId : QuickSettingsPrefs.ALL_TILES) {
            final String id = tileId;
            boolean vis = QuickSettingsPrefs.isTileVisible(hidden, id);
            addSwitchRow("qs_tile_" + id, labelForQuickTile(id), vis,
                    "All Settings remains available in Quick Settings even if all tiles are hidden.", v -> {
                        QuickSettingsPrefs.setTileVisible(this, id, v);
                        anyChange = true;
                        refreshTopHeaderHint();
                    });
        }
    }

    private void buildScreensaverScreen() {
        boolean idleOn = config.homeIdleClockMinutes > 0;
        addSwitchRow("sw_ss_idle", getString(R.string.set_ss_start_idle), idleOn,
                getString(R.string.screensaver_summary), v -> {
                    int mins = v ? (config.homeIdleClockMinutes > 0 ? config.homeIdleClockMinutes : 5) : 0;
                    choices.edit()
                            .putBoolean("screensaver_enabled", v)
                            .putInt("home_idle_clock_minutes", mins)
                            .apply();
                    markChanged();
                });

        boolean showCalendar = "calendar".equals(choices.getString(Config.SCREENSAVER_STYLE_KEY, "calendar"));
        addSwitchRow("sw_ss_calendar", getString(R.string.set_ss_show_calendar), showCalendar,
                getString(R.string.screensaver_calendar_summary), v -> {
                    choices.edit().putString(Config.SCREENSAVER_STYLE_KEY, v ? "calendar" : "clock").apply();
                    markChanged();
                });

        addNavRow("pick_ss_timeout", "Idle Timeout",
                config.homeIdleClockMinutes <= 0 ? getString(R.string.opt_off) : config.homeIdleClockMinutes + " min",
                "Minutes of inactivity on Home before Screen Saver starts.", () -> pushScreen(SCR_SS_IDLE_TIMEOUT));

        addNavRow("pick_ss_bg", getString(R.string.set_ss_background), currentScreensaverBgLabel(),
                "Choose between Black, Glow, or Wallpaper behind the Screen Saver clock.",
                () -> pushScreen(SCR_SS_BACKGROUND));

        addNavRow("pick_ss_wp", "Screensaver Wallpaper", currentScreensaverWallpaperLabel(),
                "Choose custom screensaver artwork or upload from your phone.",
                () -> pushScreen(SCR_SS_WALLPAPER_PICKER));

        addNavRow("pick_ss_dim", "Screensaver Dimming", config.clockWallpaperDim + "%",
                "Darken wallpaper behind the Screen Saver clock.", () -> pushScreen(SCR_SS_DIM));

        addSwitchRow("sw_ss_glow", "Ambient Color Glow", config.clockGlow,
                "Slowly shifting ambient glow when using Glow mode.", v -> {
                    choices.edit().putBoolean("clock_glow", v).apply();
                    markChanged();
                });

        addSwitchRow("sw_ss_back_clock", "Back on Home Opens Screen Saver", config.backOpensClock,
                "Pressing Back on the Home screen opens the Screen Saver.", v -> {
                    choices.edit().putBoolean("back_opens_clock", v).apply();
                    markChanged();
                });

        addActionRow("act_ss_now", "Start Screen Saver Now", null,
                "Preview CleanYourTV Clock Screen Saver immediately.",
                () -> launchOwnedExternal(new Intent(this, ClockActivity.class)));

        addNavRow("android_dream", "Screen Saver Settings (Android)", null,
                "Select the system Daydream / Screen Saver in Android TV settings.",
                () -> launchAndroidSettings(Settings.ACTION_DREAM_SETTINGS));
    }

    private void buildIdleTimeoutScreen() {
        int[] steps = {0, 1, 2, 5, 10, 15, 30};
        for (int mins : steps) {
            final int val = mins;
            String label = mins == 0 ? getString(R.string.opt_off) : mins + " minutes";
            addChoiceRow("ss_to_" + mins, label, config.homeIdleClockMinutes == mins, null, () -> {
                choices.edit()
                        .putBoolean("screensaver_enabled", val > 0)
                        .putInt("home_idle_clock_minutes", val)
                        .apply();
                markChanged();
                popScreen();
            });
        }
    }

    private void buildScreensaverBgScreen() {
        String bg = effectiveScreensaverBg();
        addChoiceRow("ss_bg_black", getString(R.string.set_ss_bg_black), "black".equals(bg), null, () -> {
            choices.edit().putString(Config.SCREENSAVER_BG_KEY, "black").apply();
            markChanged();
            popScreen();
        });
        addChoiceRow("ss_bg_glow", getString(R.string.set_ss_bg_glow), "glow".equals(bg), null, () -> {
            choices.edit().putString(Config.SCREENSAVER_BG_KEY, "glow").apply();
            markChanged();
            popScreen();
        });
        addChoiceRow("ss_bg_wallpaper", getString(R.string.set_ss_bg_wallpaper), "wallpaper".equals(bg), null, () -> {
            choices.edit().putString(Config.SCREENSAVER_BG_KEY, "wallpaper").apply();
            markChanged();
            popScreen();
        });
    }

    private void buildScreensaverWallpaperPickerScreen() {
        String path = choices.getString(Config.SCREENSAVER_WALLPAPER_KEY, null);
        File curFile = (path != null && !path.isEmpty()) ? new File(path) : null;
        File dir = Config.dir(this);
        File fallbackHome = findCustomWallpaperFile() != null ? findCustomWallpaperFile() : findThemeDefaultFile(dir);

        addChoiceRow("sswp_follow_home", getString(R.string.sync_follows_home), curFile == null || !curFile.isFile(),
                () -> updatePreview(fallbackHome, getString(R.string.sync_follows_home)), () -> {
                    choices.edit().remove(Config.SCREENSAVER_WALLPAPER_KEY).apply();
                    markChanged();
                    popScreen();
                });

        if (curFile != null && curFile.isFile()) {
            addChoiceRow("sswp_custom", "Custom Screensaver Image", true,
                    () -> updatePreview(curFile, curFile.getName()), () -> {
                        choices.edit().putString(Config.SCREENSAVER_WALLPAPER_KEY, curFile.getAbsolutePath())
                                .putString(Config.SCREENSAVER_BG_KEY, "wallpaper").apply();
                        markChanged();
                        popScreen();
                    });
        }

        addActionRow("sswp_pick_storage", getString(R.string.wallpaper_custom_browse), null,
                "Choose a dedicated screensaver image from storage.",
                () -> launchDocumentImagePicker(PICK_SCREENSAVER_WALLPAPER_REQUEST));

        addNavRow("sswp_upload_phone", getString(R.string.set_upload_from_phone), syncServerStatusLabel(),
                "Upload a screensaver image over Wi-Fi from your phone.",
                () -> launchOwnedExternal(new Intent(this, WallpaperSyncActivity.class)));
    }

    private void buildNotificationsScreen() {
        addNavRow("notif_open_panel", "Open Notification Center", null,
                "View active TV notifications cached by CleanYourTV.",
                () -> launchOwnedExternal(new Intent(this, NotificationPanelActivity.class)));
        addNavRow("notif_listener_perm", "Notification Access (Android)",
                GuardRevival.isConnected() ? "Allowed" : "Off",
                "Allow CleanYourTV to read notifications and display Now Playing metadata.",
                () -> launchAndroidSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        addNavRow("notif_android_apps", "App Notifications (Android)", null,
                "Manage Android system notification permissions per app.",
                () -> launchAndroidSettings(Settings.ACTION_APPLICATION_SETTINGS));
    }

    private void buildRemotesDevicesScreen() {
        int mapped = ButtonMapper.getMappedCount(this);
        addNavRow("rem_button_mapper", getString(R.string.button_mapper),
                mapped > 0 ? getString(R.string.button_mapper_mapped, mapped) : getString(R.string.button_mapper_reset),
                getString(R.string.button_mapper_summary), () -> {
                    pendingFocusKey = "rem_button_mapper";
                    pendingScrollY = settingsScroll.getScrollY();
                    ButtonMapper.showButtonMapperMenu(this, () -> renderCurrentScreen("rem_button_mapper", pendingScrollY));
                });
        addNavRow("rem_edit_qs", getString(R.string.qs_edit_controls), null,
                "Choose which optional tiles appear in Gear Quick Settings.",
                () -> pushScreen(SCR_EDIT_QS_CONTROLS));
        addNavRow("rem_bluetooth", "Remotes & Accessories (Android)", null,
                "Pair or configure Bluetooth remotes, controllers, and audio devices.",
                () -> launchAndroidSettings(Settings.ACTION_BLUETOOTH_SETTINGS));
    }

    private void buildAppsScreen() {
        Set<String> hidden = AppCustomizations.loadHidden(this);
        addNavRow("apps_hidden", getString(R.string.hidden_apps_label),
                hidden.isEmpty() ? "None" : hidden.size() + " hidden",
                getString(R.string.hidden_apps_summary), () -> pushScreen(SCR_HIDDEN_APPS));
        addNavRow("apps_android_manage", "Apps & Permissions (Android)", null,
                "Open Android TV installed applications list.",
                () -> launchAndroidSettings(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS));
        addNavRow("apps_default_home", getString(R.string.home_settings), null,
                getString(R.string.home_settings_help),
                () -> launchAndroidSettings(Settings.ACTION_HOME_SETTINGS));
    }

    private void buildHiddenAppsScreen() {
        Set<String> hidden = AppCustomizations.loadHidden(this);
        if (hidden.isEmpty()) {
            addInfoRow("hidden_none", getString(R.string.hidden_apps_none), "");
            return;
        }
        PackageManager pm = getPackageManager();
        List<String> pkgs = new ArrayList<>(hidden);
        Collections.sort(pkgs);
        for (String pkg : pkgs) {
            String label = pkg;
            try {
                label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
            } catch (Exception ignored) {
            }
            final String appLabel = label;
            addActionRow("unhide_" + pkg, label, "Restore",
                    "Tap OK to show " + label + " on the Home screen again.", () -> {
                        AppCustomizations.setHidden(this, pkg, false);
                        Toast.makeText(this, getString(R.string.unhide_toast, appLabel), Toast.LENGTH_SHORT).show();
                        markChangedAndRefresh();
                    });
        }
    }

    private void buildNetworkScreen() {
        String ip = NetworkAddress.localIpv4(this);
        boolean online = OpenOnStart.online(this);
        String status = online
                ? (ip != null ? "Connected · " + ip : "Connected")
                : (ip != null ? "No Internet · " + ip : "Disconnected");
        addInfoRow("net_status", "Connection Status", status);
        addNavRow("net_wifi_settings", "Wi-Fi & Internet (Android)", null,
                "Open Android TV network and Wi-Fi connection settings.",
                () -> launchAndroidSettings(Settings.ACTION_WIFI_SETTINGS));
        addNavRow("net_vpn", "VPN Settings (Android)",
                config.vpnApp.isEmpty() ? null : config.vpnApp,
                "Open configured VPN app or Android VPN settings.", () -> {
                    if (!config.vpnApp.isEmpty()) {
                        Intent i = OpenOnStart.intent(getPackageManager(), config.vpnApp);
                        if (i != null) {
                            launchOwnedExternal(i);
                            return;
                        }
                    }
                    launchAndroidSettings("android.settings.VPN_SETTINGS");
                });
        addNavRow("net_appearance", "Top Bar Network Options", null,
                "Control Home top-bar network pill and status color warnings.",
                () -> pushScreen(SCR_NETWORK_APPEARANCE));
    }

    private void buildNetworkAppearanceScreen() {
        addSwitchRow("sw_net_pill", getString(R.string.set_show_network_btn), config.showNetworkPill,
                "Show or hide the Network button on Home.", v -> {
                    choices.edit().putBoolean("show_network_pill", v).apply();
                    markChanged();
                });
        addSwitchRow("sw_net_color", "Color Icon by Status", config.wifiStatusColor,
                "Tint the Home network icon green while online.", v -> {
                    choices.edit().putBoolean("wifi_status_color", v).apply();
                    markChanged();
                });
        addSwitchRow("sw_net_warn", "Warn When Offline or Captive", config.wifiStatusWarnings,
                "Tint orange without internet and red while disconnected.", v -> {
                    choices.edit().putBoolean("wifi_status_warnings", v).apply();
                    markChanged();
                });
        addSwitchRow("sw_vpn_pill", "Dedicated VPN Pill", config.vpnPill,
                "Show VPN lock indicator as its own top-bar button.", v -> {
                    choices.edit().putBoolean("vpn_pill", v).apply();
                    markChanged();
                });
    }

    private void buildTransferBackupScreen() {
        addNavRow("tb_wifi_transfer", getString(R.string.set_wifi_transfer), syncServerStatusLabel(),
                "Open QR pairing and browser transfer portal on local Wi-Fi.",
                () -> launchOwnedExternal(new Intent(this, WallpaperSyncActivity.class)));

        String curPin = WallpaperSyncService.currentPinNow(this);
        addActionRow("tb_change_pin", getString(R.string.set_change_pin), "PIN " + curPin,
                "Generate a fresh 4-digit pairing PIN for browser uploads.", () -> {
                    String newPin = WallpaperSyncService.rotatePinNow(this);
                    Toast.makeText(this, getString(R.string.pin_rotated_fmt, newPin), Toast.LENGTH_SHORT).show();
                    renderCurrentScreen("tb_change_pin", settingsScroll.getScrollY());
                });

        addActionRow("tb_export", getString(R.string.backup_export), null,
                "Save settings, layout, hidden apps, and button mappings to JSON.", () -> {
                    SettingsBackup.Summary res = SettingsBackup.exportToDisk(this);
                    if (res.ok) {
                        Toast.makeText(this, getString(R.string.backup_saved, res.settingCount, res.orderCount), Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(this, getString(R.string.backup_failed, res.error), Toast.LENGTH_LONG).show();
                    }
                });

        addActionRow("tb_restore", getString(R.string.backup_restore), null,
                "Restore layout, button mappings, and settings from backup file.", () -> {
                    AppleTvUi.showConfirmDialog(this, getString(R.string.backup_restore),
                            getString(R.string.backup_summary), getString(R.string.backup_restore),
                            config.reduceTransparency, animateTransitions, config.reducedMotion, () -> {
                                SettingsBackup.Summary res = SettingsBackup.restoreFromDisk(this);
                                if (res.ok) {
                                    Toast.makeText(this, getString(R.string.backup_restored, res.settingCount, res.orderCount), Toast.LENGTH_LONG).show();
                                    markChangedAndRefresh();
                                } else {
                                    Toast.makeText(this, getString(R.string.backup_failed, res.error), Toast.LENGTH_LONG).show();
                                }
                            });
                });

        addActionRow("tb_reset_theme", getString(R.string.set_reset_theme_defaults), null,
                getString(R.string.follow_theme_hint), () -> {
                    AppleTvUi.showConfirmDialog(this, getString(R.string.set_reset_theme_defaults),
                            getString(R.string.set_reset_confirm_msg), getString(R.string.set_reset_theme_defaults),
                            config.reduceTransparency, animateTransitions, config.reducedMotion, () -> {
                                SharedPreferences.Editor ed = Config.clearOnTvChoices(choices.edit());
                                long rev = choices.getLong(Config.SETTINGS_REVISION_KEY, 0L) + 1L;
                                ed.putLong(Config.SETTINGS_REVISION_KEY, rev).apply();
                                Config.invalidateCache();
                                UiFeedback.refresh(this);
                                Toast.makeText(this, getString(R.string.set_reset_theme_defaults), Toast.LENGTH_SHORT).show();
                                anyChange = true;
                                renderCurrentScreen("tb_reset_theme", settingsScroll.getScrollY());
                            });
                });
    }

    private void buildSystemScreen() {
        int sleepMin = SleepTimer.minutesLeft(this);
        addNavRow("sys_sleep_timer", getString(R.string.tile_sleep_timer),
                sleepMin > 0 ? sleepMin + " min" : getString(R.string.opt_off),
                "Automatically turn off or open Screen Saver after the selected duration.",
                () -> pushScreen(SCR_SLEEP_TIMER));

        addNavRow("sys_startup_dest", getString(R.string.set_startup_dest), startupDestLabel(),
                "Choose where CleanYourTV lands on the first launch after boot.",
                () -> pushScreen(SCR_STARTUP_DEST));

        addSwitchRow("sw_open_on_wake", getString(R.string.set_open_on_wake), config.openOnStartOnWake,
                "Open the selected app or destination when the TV wakes from standby (independent of Startup Destination).",
                v -> {
                    choices.edit().putBoolean("open_on_start_on_wake", v).apply();
                    markChanged();
                });

        addNavRow("sys_ram", "Memory & RAM Cleaner", null,
                "View memory usage and stop background apps to free RAM.", () -> {
                    pendingFocusKey = "sys_ram";
                    pendingScrollY = settingsScroll.getScrollY();
                    RamManagerDialog.show(this, null);
                });

        boolean helperOn = HomeKeyService.bound() != null || InputKeyService.bound() != null;
        addNavRow("sys_a11y_svc", "Accessibility Key Helpers (Android)",
                helperOn ? "Running" : "Off",
                "Manage Home and Input remote button accessibility services in Android.",
                () -> launchAndroidSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS));

        addActionRow("sys_restart", getString(R.string.power_menu), null,
                "Open the Android TV power / restart dialog.", () -> {
                    HomeKeyService svc = HomeKeyService.bound();
                    if (svc != null && svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)) {
                        return;
                    }
                    Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
                });

        addNavRow("sys_android_settings", getString(R.string.tv_settings), null,
                "Open Android TV's built-in System Settings.",
                () -> launchAndroidSettings(Settings.ACTION_SETTINGS));

        addNavRow("sys_advanced", getString(R.string.sec_advanced), null,
                "Startup network wait and Device Compatibility overrides.",
                () -> pushScreen(SCR_ADVANCED));
    }

    private void buildSleepTimerScreen() {
        int cur = SleepTimer.chosen(this);
        for (int min : SleepTimer.STEPS) {
            final int val = min;
            String label = min == 0 ? getString(R.string.opt_off) : min + " minutes";
            addChoiceRow("sleep_" + min, label, cur == min, null, () -> {
                SleepTimer.set(this, val);
                if (val == 0) {
                    Toast.makeText(this, R.string.sleep_timer_off, Toast.LENGTH_SHORT).show();
                } else {
                    int msgRes = HomeKeyService.bound() != null ? R.string.sleep_timer_set : R.string.sleep_timer_no_helper;
                    Toast.makeText(this, getString(msgRes, val), Toast.LENGTH_LONG).show();
                }
                popScreen();
            });
        }
    }

    private void buildStartupDestScreen() {
        String dest = effectiveStartupDest();
        addChoiceRow("sd_home", getString(R.string.opt_home), Config.STARTUP_HOME.equals(dest), null, () -> {
            choices.edit()
                    .putString(Config.STARTUP_DEST_KEY, Config.STARTUP_HOME)
                    .putBoolean("boot_to_live_tv", false)
                    .apply();
            markChanged();
            popScreen();
        });
        addChoiceRow("sd_livetv", getString(R.string.opt_live_tv), Config.STARTUP_LIVE_TV.equals(dest), null, () -> {
            choices.edit()
                    .putString(Config.STARTUP_DEST_KEY, Config.STARTUP_LIVE_TV)
                    .putBoolean("boot_to_live_tv", true)
                    .apply();
            markChanged();
            popScreen();
        });
        String appPkg = choices.getString(OpenOnStart.KEY, "");
        String appLabel = appPkg.isEmpty() ? getString(R.string.opt_selected_app)
                : getString(R.string.opt_selected_app) + " (" + resolveAppLabel(appPkg) + ")";
        addChoiceRow("sd_app", appLabel, Config.STARTUP_APP.equals(dest), null,
                () -> pushScreen(SCR_STARTUP_APP_PICKER));
    }

    private void buildStartupAppPickerScreen() {
        PackageManager pm = getPackageManager();
        List<ResolveInfo> apps = queryLaunchableApps(pm);
        String current = choices.getString(OpenOnStart.KEY, "");
        for (ResolveInfo ri : apps) {
            final String pkg = ri.activityInfo.packageName;
            final String label = ri.loadLabel(pm).toString();
            addChoiceRow("sa_" + pkg, label, pkg.equals(current), null, () -> {
                choices.edit()
                        .putString(OpenOnStart.KEY, pkg)
                        .putBoolean("open_on_start_choice", true)
                        .putBoolean("boot_to_live_tv", false)
                        .putString(Config.STARTUP_DEST_KEY, Config.STARTUP_APP)
                        .apply();
                OpenOnStart.skipThisBoot(this);
                markChanged();
                popScreen();
                popScreen();
            });
        }
    }

    private void buildAdvancedScreen() {
        addNavRow("adv_net_wait", "Startup Network Wait", config.openOnStartNetworkWait + "s",
                "Seconds to wait for network validation before launching startup app.",
                () -> pushScreen(SCR_STARTUP_NET_WAIT));
        addNavRow("adv_compat", getString(R.string.sec_device_compatibility), null,
                "Hardware-specific key filtering, volume handling, service repair, and power-on routing.",
                () -> pushScreen(SCR_DEVICE_COMPAT));
    }

    private void buildStartupNetWaitScreen() {
        int[] waits = {0, 15, 30, 60, 120};
        for (int sec : waits) {
            final int val = sec;
            addChoiceRow("nw_" + sec, sec == 0 ? "No wait (0s)" : sec + " seconds",
                    config.openOnStartNetworkWait == sec, null, () -> {
                        choices.edit().putInt("open_on_start_network_wait", val).apply();
                        markChanged();
                        popScreen();
                    });
        }
    }

    private void buildDeviceCompatScreen() {
        addSwitchRow("compat_key_filter", "Remote Key Filtering", config.keyFilter,
                "Intercept remote shortcuts via Accessibility key filter.", v -> {
                    choices.edit().putBoolean("key_filter", v).apply();
                    markChangedAndSyncHelpers();
                });
        addSwitchRow("compat_gamepad", "Gamepad Passthrough", config.gamepadPassthrough,
                "Pause key filtering automatically while a game controller is connected.", v -> {
                    choices.edit().putBoolean("gamepad_passthrough", v).apply();
                    markChangedAndSyncHelpers();
                });
        addSwitchRow("compat_consume_vol", "Consume Volume Keys", config.consumeVolumeKeys,
                "Handle Volume Up/Down in launcher helpers for consistent auto-repeat.", v -> {
                    choices.edit().putBoolean("consume_volume_keys", v).apply();
                    markChangedAndSyncHelpers();
                });
        addSwitchRow("compat_vol_pause", "Pause Key Filter on Volume", config.volumePausesFilter,
                "Briefly pause key filtering after volume presses.", v -> {
                    choices.edit().putBoolean("volume_pauses_filter", v).apply();
                    markChangedAndSyncHelpers();
                });
        addSwitchRow("compat_ch_ignore", "Ignore Channel Keys on Home", config.homeIgnoreChannelKeys,
                "Prevent Ch+/Ch- from launching Live TV while on Home.", v -> {
                    choices.edit().putBoolean("home_ignore_channel_keys", v).apply();
                    markChanged();
                });
        addSwitchRow("compat_repair", "Auto-Repair Key Services", config.restoreKeyServices,
                "Re-enable CleanYourTV Accessibility helpers after firmware force-stop.", v -> {
                    choices.edit().putBoolean("restore_key_services", v).apply();
                    markChangedAndSyncHelpers();
                });
        addSwitchRow("compat_power_home", "Power-On Return to Home", config.powerOnHome,
                "Return to CleanYourTV when firmware auto-launches Live TV at power-on.", v -> {
                    choices.edit().putBoolean("power_on_home", v).apply();
                    markChangedAndSyncHelpers();
                });
        addSwitchRow("compat_power_always", "Force Home on Every Wake", config.powerOnHomeAlways,
                "Always bring CleanYourTV forward when powering TV back on.", v -> {
                    choices.edit().putBoolean("power_on_home_always", v).apply();
                    markChangedAndSyncHelpers();
                });
        addSwitchRow("compat_home_hold", "Long-Press Home Opens Launcher", config.homeLongPressHome,
                "Keep long-pressing Home opening CleanYourTV instead of system ALL_APPS.", v -> {
                    choices.edit().putBoolean("home_long_press_home", v).apply();
                    markChangedAndSyncHelpers();
                });
        addSwitchRow("compat_gear_qs", "Gear Key Opens Quick Settings", config.settingsKeyPanel,
                "Route remote Gear button to CleanYourTV Quick Settings instead of TV settings.", v -> {
                    choices.edit().putBoolean("settings_key_panel", v).apply();
                    markChangedAndSyncHelpers();
                });
        addSwitchRow("compat_clear_task", "Clear Settings Task on Gear", config.settingsKeyClearTask,
                "Clear system Settings back stack when Gear opens system Settings.", v -> {
                    choices.edit().putBoolean("settings_key_clear_task", v).apply();
                    markChangedAndSyncHelpers();
                });
        addSwitchRow("compat_view_inputs", "Handle System VIEW_INPUTS", config.handleViewInputs,
                "Forward Android VIEW_INPUTS intent to CleanYourTV source picker (disable for stock Google TV selector).",
                v -> {
                    choices.edit().putBoolean("handle_view_inputs", v).apply();
                    markChangedAndSyncHelpers();
                });
    }

    private void buildIntChoiceScreen(String key, int currentVal, int[] options, String fmt) {
        for (int opt : options) {
            final int val = opt;
            addChoiceRow(key + "_" + opt, String.format(Locale.getDefault(), fmt, opt),
                    currentVal == opt, null, () -> {
                        choices.edit().putInt(key, val).apply();
                        markChanged();
                        popScreen();
                    });
        }
    }

    private void buildPercentPickerScreen(String key, int currentVal, int min, int max, int step, boolean popTwo) {
        for (int pct = min; pct <= max; pct += step) {
            final int val = pct;
            addChoiceRow(key + "_" + pct, pct + "%", Math.abs(currentVal - pct) < step, null, () -> {
                choices.edit().putInt(key, val).apply();
                markChanged();
                popScreen();
                if (popTwo) popScreen();
            });
        }
    }

    // ---- Row Factory ----

    private View addNavRow(String key, int titleRes, String value, Runnable onClick) {
        return addNavRow(key, getString(titleRes), value, null, onClick);
    }

    private View addNavRow(String key, String title, String value, String desc, Runnable onClick) {
        return createRow(key, title, value, desc, RowKind.CHEVRON, false, onClick, null);
    }

    private View addActionRow(String key, String title, String value, String desc, Runnable onClick) {
        return createRow(key, title, value, desc, RowKind.ACTION, false, onClick, null);
    }

    private View addInfoRow(String key, String title, String value) {
        return createRow(key, title, value, null, RowKind.ACTION, false, null, null);
    }

    private View addChoiceRow(String key, String title, boolean checked, Runnable onFocused, Runnable onClick) {
        return createRow(key, title, null, null, checked ? RowKind.CHECKED : RowKind.ACTION, false, onClick, onFocused);
    }

    private interface BoolCallback {
        void onToggle(boolean nextValue);
    }

    private void addSwitchRow(String key, String title, boolean initialValue, String desc, BoolCallback onToggle) {
        final boolean[] state = {initialValue};
        LinearLayout row = makeBaseRowLayout(key);

        TextView titleView = makeTitleView(title);
        row.addView(titleView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView valueView = makeValueView(getString(state[0] ? R.string.opt_on : R.string.opt_off));
        row.addView(valueView);

        View indicator = new View(this);
        int w = AppleTvUi.dp(this, 22);
        int h = AppleTvUi.dp(this, 12);
        LinearLayout.LayoutParams indLp = new LinearLayout.LayoutParams(w, h);
        indLp.setMarginStart(AppleTvUi.dp(this, 10));
        indicator.setBackgroundResource(state[0] ? R.drawable.appletv_switch_on : R.drawable.appletv_switch_off);
        row.addView(indicator, indLp);

        AppleTvUi.applyRowVisual(this, row, titleView, valueView, null, config.reduceTransparency);
        AppleTvUi.attachRowFocus(row, config.animations, config.reducedMotion, (v, hasFocus) -> {
            if (hasFocus && desc != null && heroSummary != null && submenuHeroBox.getVisibility() == View.VISIBLE) {
                heroSummary.setText(desc);
            }
        });

        row.setOnClickListener(v -> {
            UiFeedback.playClick(v);
            state[0] = !state[0];
            valueView.setText(state[0] ? R.string.opt_on : R.string.opt_off);
            indicator.setBackgroundResource(state[0] ? R.drawable.appletv_switch_on : R.drawable.appletv_switch_off);
            onToggle.onToggle(state[0]);
        });

        settingsRows.addView(row);
        renderedRows.add(row);
    }

    private enum RowKind { CHEVRON, CHECKED, ACTION }

    private View createRow(String key, String title, String value, String desc, RowKind kind,
                           boolean checked, Runnable onClick, Runnable onFocused) {
        LinearLayout row = makeBaseRowLayout(key);

        TextView titleView = makeTitleView(title);
        row.addView(titleView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView valueView = null;
        if (value != null && !value.isEmpty()) {
            valueView = makeValueView(value);
            row.addView(valueView);
        }

        ImageView trailing = null;
        if (kind == RowKind.CHEVRON || kind == RowKind.CHECKED) {
            trailing = new ImageView(this);
            trailing.setImageResource(kind == RowKind.CHECKED ? R.drawable.ic_check : R.drawable.ic_chevron_right);
            trailing.setDuplicateParentStateEnabled(true);
            int sz = AppleTvUi.dp(this, 16);
            LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(sz, sz);
            tLp.setMarginStart(AppleTvUi.dp(this, 8));
            row.addView(trailing, tLp);
        }

        AppleTvUi.applyRowVisual(this, row, titleView, valueView, trailing, config.reduceTransparency);
        AppleTvUi.attachRowFocus(row, config.animations, config.reducedMotion, (v, hasFocus) -> {
            if (hasFocus) {
                if (desc != null && heroSummary != null && submenuHeroBox.getVisibility() == View.VISIBLE) {
                    heroSummary.setText(desc);
                }
                if (onFocused != null) onFocused.run();
            }
        });

        row.setOnClickListener(v -> {
            if (onClick != null) {
                UiFeedback.playClick(v);
                onClick.run();
            }
        });

        settingsRows.addView(row);
        renderedRows.add(row);
        return row;
    }

    private LinearLayout makeBaseRowLayout(String key) {
        LinearLayout row = new LinearLayout(this);
        row.setId(View.generateViewId());
        row.setTag(key);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(AppleTvUi.dp(this, 38));
        row.setPaddingRelative(AppleTvUi.dp(this, 16), AppleTvUi.dp(this, 7),
                AppleTvUi.dp(this, 14), AppleTvUi.dp(this, 7));
        row.setFocusable(true);
        row.setClickable(true);
        row.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = AppleTvUi.dp(this, 5);
        row.setLayoutParams(lp);
        return row;
    }

    private TextView makeTitleView(String title) {
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f);
        tv.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setDuplicateParentStateEnabled(true);
        return tv;
    }

    private TextView makeValueView(String value) {
        TextView tv = new TextView(this);
        tv.setText(value);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        tv.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setMaxWidth(AppleTvUi.dp(this, 180));
        tv.setDuplicateParentStateEnabled(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(AppleTvUi.dp(this, 12));
        tv.setLayoutParams(lp);
        return tv;
    }

    // ---- Helpers & Persistence ----

    private void markChanged() {
        anyChange = true;
        long rev = choices.getLong(Config.SETTINGS_REVISION_KEY, 0L) + 1L;
        choices.edit().putLong(Config.SETTINGS_REVISION_KEY, rev).apply();
        Config.invalidateCache();
        config = Config.load(this);
        animateTransitions = config.animations && !config.reducedMotion;
    }

    private void markChangedAndRefresh() {
        String key = currentFocusedKey();
        int sy = settingsScroll != null ? settingsScroll.getScrollY() : 0;
        markChanged();
        renderCurrentScreen(key, sy);
    }

    private void markChangedAndSyncHelpers() {
        markChanged();
        HomeKeyService.reloadConfigFromHost();
        InputKeyService.reloadConfigFromHost();
    }

    private void refreshTopHeaderHint() {
        if (heroSummary != null && submenuHeroBox.getVisibility() == View.VISIBLE) {
            int count = QuickSettingsPrefs.visibleTileCount(QuickSettingsPrefs.loadHiddenTiles(this));
            heroSummary.setText(count == 0 ? getString(R.string.qs_all_hidden_hint)
                    : count + " of " + QuickSettingsPrefs.ALL_TILES.size() + " optional controls shown in Quick Settings.");
        }
    }

    private void launchAndroidSettings(String action) {
        captureCurrentFocusToTop();
        pendingFocusKey = currentFocusedKey();
        pendingScrollY = settingsScroll != null ? settingsScroll.getScrollY() : 0;
        Intent intent = SystemSettings.intent(this, action);
        if (intent == null) {
            intent = new Intent(action);
        }
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    private void launchOwnedExternal(Intent intent) {
        captureCurrentFocusToTop();
        pendingFocusKey = currentFocusedKey();
        pendingScrollY = settingsScroll != null ? settingsScroll.getScrollY() : 0;
        try {
            startActivity(intent);
            Motion.applyFadeTransition(this, animateTransitions);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    private void launchDocumentImagePicker(int requestCode) {
        captureCurrentFocusToTop();
        pendingFocusKey = currentFocusedKey();
        pendingScrollY = settingsScroll != null ? settingsScroll.getScrollY() : 0;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("image/*");
        try {
            startActivityForResult(i, requestCode);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        if (requestCode == PICK_WALLPAPER_REQUEST) {
            copyPickedWallpaper(data.getData(), Config.WALLPAPER_CHOICE_KEY, "custom_wallpaper.jpg", false);
        } else if (requestCode == PICK_SCREENSAVER_WALLPAPER_REQUEST) {
            copyPickedWallpaper(data.getData(), Config.SCREENSAVER_WALLPAPER_KEY, "custom_screensaver_wallpaper.jpg", true);
        }
    }

    private void copyPickedWallpaper(Uri uri, String prefKey, String fileName, boolean isScreensaver) {
        File dir = Config.dir(this);
        File targetDir = dir != null && (dir.isDirectory() || dir.mkdirs()) ? dir : getFilesDir();
        File dst = new File(targetDir, fileName);
        File tmp = new File(targetDir, fileName + ".tmp");
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) {
                Toast.makeText(this, R.string.wallpaper_open_error, Toast.LENGTH_SHORT).show();
                return;
            }
            byte[] buf = new byte[16384];
            int total = 0, read;
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                while ((read = in.read(buf)) > 0) {
                    total += read;
                    if (total > MAX_WALLPAPER_BYTES) {
                        tmp.delete();
                        Toast.makeText(this, R.string.wallpaper_size_limit, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    out.write(buf, 0, read);
                }
            }
            if (dst.exists() && !dst.delete()) {
                tmp.delete();
                Toast.makeText(this, R.string.wallpaper_replace_error, Toast.LENGTH_SHORT).show();
                return;
            }
            if (!tmp.renameTo(dst)) {
                tmp.delete();
                Toast.makeText(this, R.string.wallpaper_finalize_error, Toast.LENGTH_SHORT).show();
                return;
            }
            SharedPreferences.Editor ed = choices.edit();
            if (isScreensaver) {
                ed.putString(prefKey, dst.getAbsolutePath())
                        .putString(Config.SCREENSAVER_BG_KEY, "wallpaper");
            } else {
                ed.putString(prefKey, dst.getAbsolutePath());
            }
            ed.apply();
            Toast.makeText(this, R.string.wallpaper_applied, Toast.LENGTH_SHORT).show();
            markChangedAndRefresh();
        } catch (Exception e) {
            tmp.delete();
            Toast.makeText(this, getString(R.string.wallpaper_error, e.getMessage()), Toast.LENGTH_SHORT).show();
        }
    }

    private void updatePreview(File file, String caption) {
        if (wallpaperPreviewCaption != null) {
            wallpaperPreviewCaption.setText(caption != null ? caption : "");
        }
        final int gen = ++previewLoadGeneration;
        if (file == null || !file.isFile() || file.length() == 0) {
            wallpaperPreviewImg.setImageDrawable(null);
            wallpaperPreviewImg.setBackgroundColor(0xFF0A0B0F);
            releaseWallpaperPreviewBitmap();
            return;
        }
        LauncherExecutors.io().execute(() -> {
            Bitmap bmp = decodeSampledBitmap(file, 480, 270);
            LauncherExecutors.main().post(() -> {
                if (isDestroyed() || isFinishing() || gen != previewLoadGeneration) {
                    if (bmp != null) bmp.recycle();
                    return;
                }
                Bitmap old = currentPreviewBitmap;
                currentPreviewBitmap = bmp;
                wallpaperPreviewImg.setImageBitmap(bmp);
                if (old != null && old != bmp) old.recycle();
            });
        });
    }

    private void releaseWallpaperPreviewBitmap() {
        previewLoadGeneration++;
        if (wallpaperPreviewImg != null) wallpaperPreviewImg.setImageDrawable(null);
        if (currentPreviewBitmap != null) {
            currentPreviewBitmap.recycle();
            currentPreviewBitmap = null;
        }
    }

    private static Bitmap decodeSampledBitmap(File file, int reqW, int reqH) {
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
            int sample = 1;
            while ((opts.outWidth / sample) > reqW * 2 || (opts.outHeight / sample) > reqH * 2) {
                sample *= 2;
            }
            opts.inJustDecodeBounds = false;
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean isHomeWallpaperOn() {
        String c = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        return !"none".equals(c) && (config.wallpaperEnabled || (c != null && !c.isEmpty()));
    }

    private String currentWallpaperLabel() {
        String c = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        if ("none".equals(c)) return getString(R.string.opt_off);
        if (c == null) return config.wallpaperEnabled ? getString(R.string.wallpaper_theme_default) : getString(R.string.opt_off);
        if (c.contains("/") || "home_wallpaper.jpg".equals(c) || "custom_wallpaper.jpg".equals(c)) {
            return getString(R.string.sync_custom);
        }
        return c;
    }

    private String effectiveScreensaverBg() {
        String saved = choices.getString(Config.SCREENSAVER_BG_KEY, null);
        if (saved != null && !saved.isEmpty()) return saved;
        if (config.clockWallpaper) return "wallpaper";
        return config.clockGlow ? "glow" : "black";
    }

    private String currentScreensaverBgLabel() {
        String bg = effectiveScreensaverBg();
        if ("black".equals(bg)) return getString(R.string.set_ss_bg_black);
        if ("wallpaper".equals(bg)) return getString(R.string.set_ss_bg_wallpaper);
        return getString(R.string.set_ss_bg_glow);
    }

    private String currentScreensaverWallpaperLabel() {
        String path = choices.getString(Config.SCREENSAVER_WALLPAPER_KEY, null);
        if (path != null && !path.isEmpty() && new File(path).isFile()) return getString(R.string.sync_custom);
        return getString(R.string.sync_follows_home);
    }

    private String clockFormatLabel(String mode) {
        if ("12".equals(mode)) return getString(R.string.clock_format_12);
        if ("24".equals(mode)) return getString(R.string.clock_format_24);
        return getString(R.string.clock_format_system);
    }

    private String syncServerStatusLabel() {
        if (WallpaperSyncService.isRunningNow()) {
            String ip = NetworkAddress.localIpv4(this);
            return ip != null ? "Running (" + ip + ")" : "Running";
        }
        return "Stopped";
    }

    private String effectiveStartupDest() {
        if (!config.startupDestination.isEmpty()) return config.startupDestination;
        if (config.bootToLiveTv) return Config.STARTUP_LIVE_TV;
        String pkg = choices.getString(OpenOnStart.KEY, "");
        return (config.openOnStartChoice && !pkg.isEmpty()) ? Config.STARTUP_APP : Config.STARTUP_HOME;
    }

    private String startupDestLabel() {
        String dest = effectiveStartupDest();
        if (Config.STARTUP_LIVE_TV.equals(dest)) return getString(R.string.opt_live_tv);
        if (Config.STARTUP_APP.equals(dest)) {
            String pkg = choices.getString(OpenOnStart.KEY, "");
            return pkg.isEmpty() ? getString(R.string.opt_selected_app) : resolveAppLabel(pkg);
        }
        return getString(R.string.opt_home);
    }

    private String resolveAppLabel(String pkg) {
        if (pkg == null || pkg.isEmpty()) return "";
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    private File findCustomWallpaperFile() {
        String saved = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        if (saved != null && !saved.isEmpty() && !"none".equals(saved)) {
            File direct = new File(saved);
            if (direct.isFile() && direct.length() > 0) return direct;
        }
        File dir = Config.dir(this);
        for (File root : new File[]{dir, getFilesDir()}) {
            if (root == null) continue;
            for (String name : new String[]{"home_wallpaper.jpg", "custom_wallpaper.jpg"}) {
                File f = new File(root, name);
                if (f.isFile() && f.length() > 0) return f;
            }
        }
        return null;
    }

    private File findThemeDefaultFile(File dir) {
        if (dir == null) return null;
        File w = new File(dir, Config.WALLPAPER_NAME);
        if (w.isFile() && w.length() > 0) return w;
        List<File> list = WallpaperRotation.images(new File(dir, "wallpapers"));
        return list.isEmpty() ? null : list.get(0);
    }

    private List<ResolveInfo> queryLaunchableApps(PackageManager pm) {
        List<ResolveInfo> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Intent query : new Intent[]{
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER),
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        }) {
            for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (!pkg.equals(getPackageName()) && seen.add(pkg)) out.add(ri);
            }
        }
        Collections.sort(out, (a, b) -> a.loadLabel(pm).toString().compareToIgnoreCase(b.loadLabel(pm).toString()));
        return out;
    }

    private String labelForQuickTile(String id) {
        switch (id) {
            case QuickSettingsPrefs.TILE_SCREENSAVER: return getString(R.string.tile_screensaver);
            case QuickSettingsPrefs.TILE_INPUTS: return getString(R.string.tile_inputs);
            case QuickSettingsPrefs.TILE_DISPLAY: return getString(R.string.tile_display);
            case QuickSettingsPrefs.TILE_SOUND: return getString(R.string.tile_sound);
            case QuickSettingsPrefs.TILE_WIFI: return getString(R.string.tile_wifi);
            case QuickSettingsPrefs.TILE_BLUETOOTH: return getString(R.string.tile_bluetooth);
            case QuickSettingsPrefs.TILE_SLEEP_TIMER: return getString(R.string.tile_sleep_timer);
            case QuickSettingsPrefs.TILE_REARRANGE: return getString(R.string.reorder);
            case QuickSettingsPrefs.TILE_NOTIFICATIONS: return getString(R.string.tile_notifications);
            case QuickSettingsPrefs.TILE_BOOST_RAM: return getString(R.string.tile_boost_ram);
            default: return id;
        }
    }
}
