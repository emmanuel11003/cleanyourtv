package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.ActivityOptions;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ChangedPackages;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.SoundEffectConstants;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.animation.AnimationUtils;
import android.view.animation.LayoutAnimationController;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * tvOS style home: black, a pinned dock row, an app grid, and one glass highlight that springs between tiles.
 */
public class MainActivity extends Activity implements AppAdapter.TileListener {
    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));   // locale
    }


    static final int LAUNCHER_SETTINGS_REQUEST = 42;
    /** Sent by Quick Settings: toggle reorder mode. */
    public static final String ACTION_REORDER = "com.gathin.tvhome.action.REORDER";
    /** Sent by the "library" action word of app_keys: open the App library over Home (needs app_library). */
    static final String ACTION_APP_LIBRARY = "com.gathin.tvhome.action.APP_LIBRARY";
    /** Sent by the "search" action word of app_keys or Search key: open instant offline Spotlight Search. */
    static final String ACTION_SPOTLIGHT_SEARCH = "com.gathin.tvhome.action.SPOTLIGHT_SEARCH";
    /** The system input switcher, forwarded here by InputsRequestActivity while handle_view_inputs is on. */
    static final String ACTION_VIEW_INPUTS = "com.android.tv.action.VIEW_INPUTS";
    /** Tiles per row and dock slots (res/values/config.xml, columns). Set in onCreate. */
    static int COLUMNS = 4;
    private boolean rtlAppGrid;
    private static final long MINUTE_MS = 60_000L;
    private static final long STATS_MS = 10_000L;
    /**
     * 2025-01-01. TVs without a clock battery boot at the epoch (08:00 in China) and keep it until network
     * time arrives; the clock, date and greeting stay hidden before then.
     */
    private static final long TIME_VALID_AFTER_MS = 1_735_689_600_000L;
    String liveTvPkg;
    private boolean homeIgnoreChannelKeys;
    /** Launcher settings' change count this Home was built with (see onResume). */
    private long settingsRevision;
    /** Idle time on Home before ClockActivity; 0 is off (config.xml, home_idle_clock_minutes). */
    private long idleClockMs;
    /** Back on Home opens ClockActivity (config.xml, back_opens_clock). */
    private boolean backOpensClock;
    private final Runnable showIdleClock = this::openScreensaver;
    private final Runnable playPetalShowerTask = this::playPetalShower;
    String liveTvAction;
    String liveTvInputId;
    /** The app library is on (config.xml, app_library): only then can the tile menu take an app off Home. */
    private boolean appLibraryEnabled;
    /** The same opt-in menu is available from either hold-OK or the remote's Menu key. */
    private boolean tileMenuEnabled;
    String inputAction;
    List<String> inputSources;
    String inputUriScheme;
    String sourcePanelService;
    String sourcePanelAction;
    String sourcePanelExtra;
    boolean coocaaSources;

    private AppAdapter adapter;
    private RecyclerView grid;
    FrameLayout root;
    private WallpaperPlayer wallpaperPlayer;
    private GlassView glass;
    private View ambient;
    private TextView heroName;
    /** The top shelf: heroName, plus the cinematic shelf's "Last opened" line and dock position dots. */
    private View hero;
    private TextView heroMeta;
    private LinearLayout heroDots;
    /** Cinematic top shelf (config.xml, cinematic_home): the focused dock app's artwork behind a larger shelf. */
    private boolean cinematic;
    /** config.xml ambient_glow, overridable by a theme. Off: the glow view is gone and no fade or colour animator
     *  is started for it, so a focus move costs nothing extra on a launcher that runs all day. */
    private boolean ambientGlow;
    /** config.xml app_glow: the highlight takes the focused app's colour on every tile. */
    private boolean appGlow;
    /** config.xml glow_style: the shape setAmbient paints the glow mask in. */
    private String glowStyle;
    /** config.xml cinematic_white_focus: the cinematic shelf keeps the white highlight. */
    private boolean cinematicWhiteFocus;
    private ImageView heroArt;
    /** config.xml cinematic_dots: false leaves the dock position dots out of the cinematic shelf. */
    private boolean heroDotsShown = true;
    /** The artwork's opacity behind the cinematic shelf (config.xml cinematic_art); 0 leaves it out. */
    private float heroArtAlpha = 0.5f;
    /** Softened artwork per app (HeroArt, about 5 KB each), bounded tighter for lower RAM usage on 1-2 GB TVs. */
    private final LruCache<String, Bitmap> heroArtCache = new LruCache<String, Bitmap>(12) {
        @Override
        protected void entryRemoved(boolean evicted, String key, Bitmap oldValue, Bitmap newValue) {
            if (oldValue == currentHeroArt) currentHeroArt = null;
            if (oldValue != null && oldValue != newValue && !oldValue.isRecycled()
                    && oldValue.getConfig() != Bitmap.Config.HARDWARE) {
                oldValue.recycle();
            }
        }
    };
    /** The app whose artwork heroArt shows, so focusing it again only fades it back in. */
    private String heroArtKey;
    private int heroArtGen;
    private Runnable heroArtPending;
    private static final ForegroundColorSpan HERO_META_SPAN = new ForegroundColorSpan(0xFFFFFFFF);
    /** The focus glow's colour while the cinematic shelf tints it, 0 for plain white glass. */
    private int glassAccent;
    private int ambientColor = -1;
    private int lastAmbientTint = 0;
    private long lastFocusMoveMs;
    private ValueAnimator ambientAnimator;
    private static final int BACKDROP_W = 128, BACKDROP_H = 72;
    private static final Rect BACKDROP_RECT = new Rect(0, 0, BACKDROP_W, BACKDROP_H);
    private boolean singlePassBackdrop;
    private int backdropBaseColor;
    private Bitmap[] backdropBuffers;
    private Canvas[] backdropCanvases;
    private int backdropIndex;
    private BackdropDrawable backdropDrawable;
    private Bitmap ambientMaskBmp;
    private Bitmap scrimBmp;
    private int scrimAlpha;
    private Bitmap currentHeroArt;
    private boolean backdropAmbientVisible;
    private boolean backdropArtVisible;
    private final Paint backdropArtPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint backdropAmbientPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint backdropScrimPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private HeroDotsDrawable heroDotsDrawable;
    private DockDecoration dockDecoration;
    /** config.xml glow_snap: the glow takes a new colour in one frame instead of the dip-and-fade. */
    private boolean glowSnap;
    private boolean heroShown;
    private View topBarView;
    private LiveClockView liveClockView;
    private int lastGridW = -1, lastGridH = -1, lastGridTopPad = -1;
    /** Uptime ms when focus last landed on the dock row from a lower grid row. */
    private long dockLandedAtMs = -1000L;
    /** True while smooth-scrolling the dock back to its bottom resting line so glass glides directly there. */
    private boolean dockReturning;
    /** True between grid.setPadding(..., want, ...) and the posted requestLayout() pass finishing. */
    private boolean gridPaddingPending;
    private boolean packageReceiverRegistered, wakeReceiverRegistered, timeReceiverRegistered,
            wallpaperReceiverRegistered, networkCallbackRegistered, vpnCallbackRegistered, inputCallbackRegistered;
    private final android.view.animation.Interpolator scrollInterpolator = new android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f);
    boolean animations;
    private boolean advancedEffects;
    private TextView greeting;
    private View clockBlock;
    View btnInputs;
    private View btnSearch;
    private TextView btnNowPlaying;
    private MediaNowPlaying mediaNowPlaying;
    /** config show_inputs_pill: false keeps the Inputs pill hidden even when the TV has inputs. */
    boolean showInputsPill = true;
    TextView btnWifi;
    /** The network pill's icon as last set, so a refresh that changes nothing does not lay the top bar out again. */
    private int wifiIcon;
    /** The pills have been filled in once; later resumes refresh them after the first frame. */
    private boolean pillsShown;
    private TextView shamsiDate;
    /** lunar_date: the lunisolar date under the date line, and the time zone it is computed for. */
    private TextView lunarDate;
    private boolean lunar;
    private int lunarOffset;
    private TextView stats;
    private List<RaceWeekend.Race> raceCalendar;
    private View racePanel;
    private TextView raceKicker, raceHeadline, raceSessions;
    /** game_day: each team's next game from an add-on, rotating in the race panel; null when the key is off. */
    private GameDay gameDay;
    private View raceBar;
    private LinearLayout racePager;
    private long rotationStart;
    private long lastEndedRead;
    private final Runnable turnTick = this::updatePanel;
    /** config.xml show_stats, overridable by a theme. Off: the line is gone and nothing is read. */
    private boolean showStats;
    /** config.xml inputs_connected_only: the Inputs menu lists only connected inputs, the rest behind "More inputs". */
    boolean inputsConnectedOnly;
    /** config.xml inputs_cec_state_only: only CEC device rows show a connection state, bare HDMI ports none. */
    boolean inputsCecStateOnly;
    private boolean shamsi;
    /** greeting_name: said after the greeting, which is shown in capitals. */
    private String greetingName = "";
    /** Optional time-of-day backdrop (config.xml, sky_intensity); 0 keeps it hidden. */
    private SkyView sky;
    private String vpnApp;
    /** vpn_pill: the VPN shown as its own pill (btnVpn) instead of a badge in the network pill. Off with top_menu. */
    private boolean vpnPill;
    private TextView btnVpn;
    /** The VpnBadge pill state btnVpn shows, so a refresh that changes nothing does not restyle it. */
    private int vpnPillState = VpnBadge.PILL_HIDDEN;
    /** icon_only_vpn: the VPN pill is a round lock button; off, it keeps the plain glass look saved here. */
    private boolean iconOnlyVpn;
    private Drawable.ConstantState vpnPlainBackground;
    private ColorStateList vpnPlainInk;
    private SpringAnimation springX, springY;
    private float glassTargetX, glassTargetY;
    // How far the grid has scrolled the glass. springY works without it, in grid content space, so a
    // scroll moves the glass in lockstep with its tile instead of becoming a moving spring target.
    private float glassScrollShift;
    /** The same for springX, when single_row scrolls the grid sideways. */
    private float glassScrollShiftX;
    private int glassCardW;   // width of the tile the glass was last fitted to
    /**
     * The focused tile's rect in root coordinates as {@link #onTileFocused} last fitted the glass to it. The size
     * alone cannot say whether the glass is on its tile: a cold start can run onTileFocused once the tile has its
     * final width but before the grid has its top padding, which leaves the right size at the old Y.
     */
    private final Rect glassCardRect = new Rect();
    private boolean glassShown;
    private final Rect tileRect = new Rect();
    private final Rect revealRect = new Rect();
    // Static so they survive the activity being destroyed while another app is open (low-memory TVs,
    // or Developer options → "Don't keep activities"); they only reset when the process dies.
    private static int lastFocused = 0;
    /** App of the last focused tile, so focus follows it when the app list changes. */
    private static String lastFocusedKey;
    float density;
    /** Module cards (res/values/config.xml, module_cards): discovered on resume, shown only after the owner's Show. */
    boolean moduleCards;
    /** config settings_key_keycodes / settings_key_panel, so the Settings button works on Home without HomeKeyService. */
    private int[] settingsKeycodes = new int[0];
    private boolean settingsKeyPanel;
    private boolean restoreKeyServices;
    private boolean settingsKeyClearTask;
    /** Calmer motion for weak GPUs (config.xml, reduced_motion): stiff spring, short fades, no replay. */
    boolean reducedMotion;
    /** config.xml dock_row: the first row is a separate dock (band, parked low, top shelf) rather than plain grid. */
    boolean dockRow;
    /** config single_row: every app in the dock's row, scrolling sideways, no grid below. */
    private boolean singleRow;
    /** The highlight springs past its target (config.xml, focus_overshoot); false goes straight there. */
    private boolean focusOvershoot;
    private float animationScale;
    /** Festive skin from the theme (see Festival); applied while it is active today. */
    private Festival festival = Festival.NONE;
    private boolean festive;
    /** The network pill's festive light: the diya, or the jack-o'-lantern for Halloween. */
    private FestiveLamp lamp;
    private BellDrawable bell;
    private MarigoldDrawable marigold;
    private int greetingColor;
    /** The day the petal shower last played, so it greets the first Home of each day only. */
    private static LocalDate lastPetalShower;
    /** boot_to_live_tv: open the Live TV app on the first Home of each boot. */
    boolean bootToLiveTv;
    String startupDestination = "";
    boolean showNowPlayingHome = true;
    /** The input in the Live TV tile's launch_actions ("input:<id>"), opened at power-on instead of the Live TV app. */
    String bootInputId;
    /** open_on_start_choice: the app picked in Launcher settings may open on the first Home of each boot. */
    boolean openOnStartChoice;
    /** open_on_start_network_wait, in ms; the boot's app while it waits for the network, and until when. */
    long openOnStartWaitMs;
    /** open_on_start_on_wake: wakeReceiver is registered for this activity's life. */
    private boolean openOnStartOnWake;
    volatile Intent startPending;
    long startDeadline;
    /** Opens the boot's app once the network is validated (networkCallback posts this) or the wait is over. */
    final Runnable openWhenOnline = new Runnable() {
        @Override
        public void run() {
            if (startPending == null) return;
            if (!OpenOnStart.opensNow(OpenOnStart.online(MainActivity.this), SystemClock.elapsedRealtime(),
                    startDeadline)) return;
            handler.removeCallbacks(this);   // the deadline's run, when the network came first
            Intent i = startPending;
            startPending = null;
            open(i);
        }
    };
    private final BroadcastReceiver wakeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (openOnStartOnWake) TvInputRouter.openStartAppOnWake(MainActivity.this);
        }
    };
    /** hidden_apps as the adapter sees them (config.xml or a theme's config.json): a hidden app gets no card either. */
    private Set<String> hiddenApps = Collections.emptySet();
    /** library_excluded_apps: packages the App library catalogue never offers. */
    private Set<String> libraryExcludedApps = Collections.emptySet();
    List<Cards.Card> cards = Collections.emptyList();
    /** The grid has been laid out and focused since the last resume; the card prompt waits for this. */
    boolean tilesReady;
    private boolean settingsRecreationPending;
    final ModuleCardController moduleCardController = new ModuleCardController();
    /** Between onStop and onStart: Home was fully covered by an app, not just by the translucent quick panel. */
    private boolean stopped;
    /** Between onStart and onStop: Home is (at least partly) on screen. */
    private boolean started;
    /**
     * Between onResume and onPause: Home is in front. The idle clock only runs then; a key's ACTION_UP can still
     * reach Home after onPause, when that key opened another app, and must not start the clock over the app.
     */
    private boolean resumed;
    /** Rebuild the tiles on the way back to Home only when apps changed meanwhile (TileRefresh). */
    private final TileRefresh tileRefresh = new TileRefresh();
    /** PackageManager's change counter at the last rebuild, to catch a package broadcast that never arrived. */
    private int packageSequence;
    /** The Inputs or Module cards dropdown while one is open, so onPause can close it. */
    PopupWindow openPopup;
    /** Reorder mode is on (top-bar button). */
    boolean reordering;
    /** Key of the tile being carried in reorder mode, or null. */
    private String movingKey;
    /** Tile order when reorder mode started, to save only when something moved. */
    private List<String> orderAtStart;
    /**
     * fill_dock false: tiles in the dock when reorder mode started, plus those carried or swapped into it since
     * (#499). A grid app that only slid into a free slot, which reorder mode does not show, is not one of them.
     */
    private Set<String> dockInSession;
    /** fill_dock false: how many dock slots held an app when reorder mode started. */
    private int dockedAtStart;
    private View btnReorder;
    /** dock_apps as loaded (config.xml or a theme): a saved Order only applies while these are unchanged. */
    private List<String> dockApps;
    /** fill_dock false: the dock can have empty slots, which reorder mode leaves out and restores afterwards. */
    private boolean fillDockLeavesSlots;
    /** Edit-dock mode (Settings menu): a reorder session that swaps one chosen app into one dock place. */
    private boolean dockEditing;
    /** Dock place being changed in edit-dock mode, or -1 while the place is still being chosen. */
    private int dockEditSlot = -1;
    /** Tile menu's "Add to dock": the app waiting for a dock place in edit-dock mode, or null. */
    private String dockEditAppKey;
    private AppLibraryDialog appLibrary;
    private boolean appLibrarySelection;
    private boolean libraryOkOpens;
    private boolean libraryFocusPending;
    private RecyclerView.OnScrollListener pendingFocusScroll;

    final Handler handler = new Handler(Looper.getMainLooper());
    /** Refreshes the network pill when the connection changes: at boot Wi-Fi often connects after Home is shown. */
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }

        @Override
        public void onLost(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }

        /** wifi_status_warnings follows validation; signal-strength updates also land here, so skip those. */
        private boolean lastValidated;

        @Override
        public void onCapabilitiesChanged(@NonNull Network network, @NonNull NetworkCapabilities caps) {
            boolean validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            // open_on_start_network_wait: the boot's app opens on the first validated network.
            if (validated && startPending != null) handler.post(openWhenOnline);
            if (validated == lastValidated) return;
            lastValidated = validated;
            if (wifiNoInternetTint != null) handler.post(MainActivity.this::updateWifiLabel);
        }
    };
    /** Shows the Inputs pill once the TV registers an input: at boot HDMI inputs can appear after Home is shown. */
    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override
        public void onInputAdded(String inputId) {
            TvInputRouter.updateInputsButton(MainActivity.this);
        }

        @Override
        public void onInputRemoved(String inputId) {
            TvInputRouter.updateInputsButton(MainActivity.this);
        }
    };
    /** Listens for any VPN coming up or going down, even one this app's traffic is excluded from. */
    private final ConnectivityManager.NetworkCallback vpnCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }

        @Override
        public void onLost(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }
    };
    private final IntentFilter packageFilter = new IntentFilter();
    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Updates send REMOVED followed by ADDED; keep the tile until the new APK is ready.
            if (Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())
                    && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
            // In the background this only marks the tiles stale; onResume rebuilds them once.
            if (!tileRefresh.onPackagesChanged()) return;
            reloadApps(false);
            if (appLibrary != null) appLibrary.refresh();
        }
    };
    // Right after boot the clock is often wrong until network time arrives; don't wait a minute to catch up.
    private final IntentFilter timeFilter = new IntentFilter(Intent.ACTION_TIME_CHANGED);
    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateClockVisibility();
            clockRefresh.start();   // the wall clock was set: line the redraw up with the new minute
            if (liveClockView != null) liveClockView.startTicking();
        }
    };
    private final IntentFilter wallpaperFilter = new IntentFilter(WallpaperSyncService.ACTION_WALLPAPER_UPDATED);
    private final BroadcastReceiver wallpaperReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (resumed && !isFinishing() && !isDestroyed()) {
                Config.invalidateCache();
                recreate();
            }
        }
    };
    /** Keeps the Home clocks moving on Android before 12 when the firmware stops ACTION_TIME_TICK (see ClockRefresh). */
    private ClockRefresh clockRefresh;
    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            updateGreeting();
            updatePanel();
            if (liveClockView != null) liveClockView.startTicking();
            // The pill follows the network callbacks and each return to Home; only the festive light follows the
            // signal level, so only it needs this minute's binder calls (about 7 ms on the UI thread).
            if (festive) updateWifiLabel();
            sky.update();
            wallpaperPlayer.update();
            handler.postDelayed(this, MINUTE_MS);
        }
    };

    private java.util.Map<Integer, String> homeAppKeys = java.util.Collections.emptyMap();
    /** clock_format, for the race panel's session times (the TextClocks take it in onCreate). */
    private String clockFormat = "system";
    private java.util.Map<Integer, String> homeHoldAppKeys = java.util.Collections.emptyMap();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        density = getResources().getDisplayMetrics().density;
        topBarView = findViewById(R.id.topBar);
        View rawClock = findViewById(R.id.clock);
        if (rawClock instanceof LiveClockView) liveClockView = (LiveClockView) rawClock;
        // config.xml, overlaid by config.json from a pushed theme (see Config and tools/theme.sh).
        Config cfg = Config.load(this);
        homeAppKeys = RemoteKeys.parseAppKeys(cfg.appKeys);
        clockFormat = cfg.clockFormat;
        ClockFormat.apply(getWindow().getDecorView(), clockFormat);
        homeHoldAppKeys = RemoteKeys.parseHoldAppKeys(cfg.appKeys);
        appLibrarySelection = cfg.appLibrarySelection;
        libraryOkOpens = cfg.libraryOkOpens;
        // restore_key_services: after a force stop, put back the remote's accessibility helpers that Android took
        // off the list (needs WRITE_SECURE_SETTINGS, granted by build-and-install with RESTORE_KEY_SERVICES=1).
        restoreKeyServices = cfg.restoreKeyServices;
        if (restoreKeyServices) ServiceRepair.restore(this);
        settingsRevision = settingsRevision();
        applyViewInputsHandler(cfg.handleViewInputs);
        settingsKeycodes = cfg.settingsKeycodes;
        settingsKeyPanel = cfg.settingsKeyPanel;
        settingsKeyClearTask = cfg.settingsKeyClearTask;
        settingsKeyClearTask = cfg.settingsKeyClearTask;
        COLUMNS = cfg.columns;
        animations = cfg.animations;
        advancedEffects = cfg.animations && cfg.advancedEffects;
        reducedMotion = cfg.reducedMotion;
        dockRow = cfg.dockRow;
        singleRow = cfg.singleRow;
        focusOvershoot = cfg.focusOvershoot;
        festival = cfg.festival;
        festive = festival.active(LocalDate.now());
        liveTvPkg = cfg.liveTvPackage;
        homeIgnoreChannelKeys = cfg.homeIgnoreChannelKeys;
        idleClockMs = cfg.homeIdleClockMinutes * MINUTE_MS;
        backOpensClock = cfg.backOpensClock;
        bootToLiveTv = cfg.bootToLiveTv;
        startupDestination = cfg.startupDestination;
        showNowPlayingHome = cfg.showNowPlayingHome;
        bootInputId = LaunchActions.inputId(cfg.launchActions.get(cfg.liveTvPackage));
        openOnStartChoice = cfg.openOnStartChoice;
        openOnStartWaitMs = cfg.openOnStartNetworkWait * 1000L;
        openOnStartOnWake = OpenOnStart.onWake(cfg.openOnStartChoice, cfg.openOnStartOnWake);
        liveTvAction = cfg.liveTvAction;
        liveTvInputId = cfg.liveTvInputId;
        appLibraryEnabled = cfg.appLibrary;
        tileMenuEnabled = cfg.tileMenu;
        inputAction = cfg.inputAction;
        inputSources = cfg.inputSources;
        inputUriScheme = cfg.inputUriScheme;
        sourcePanelService = cfg.sourcePanelService;
        sourcePanelAction = cfg.sourcePanelAction;
        sourcePanelExtra = cfg.sourcePanelExtra;
        coocaaSources = cfg.coocaaSources;

        root = findViewById(R.id.root);
        UiFeedback.refresh(this);
        backdropBaseColor = cfg.backgroundColor;
        root.setBackgroundColor(cfg.backgroundColor);
        // root fills the window with an opaque colour (Config keeps background_color opaque), so the window's own
        // black under it is a second full-screen fill on every frame for nothing. The starting window still uses
        // the theme's background, so launch looks the same.
        getWindow().setFormat(android.graphics.PixelFormat.RGBA_8888);
        getWindow().setBackgroundDrawable(null);
        FrameRateLock.apply(this);
        MemoryBooster.registerTrimHook(ramBoostHook);
        wallpaperPlayer = new WallpaperPlayer(this, root, cfg);
        boolean hasWallpaper = cfg.wallpaperEnabled && !cfg.wallpapers.isEmpty();
        boolean customWallpaper = hasWallpaper
                && getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE).contains(Config.WALLPAPER_CHOICE_KEY);
        sky = findViewById(R.id.sky);
        sky.setIntensity(customWallpaper ? 0 : cfg.skyIntensity);
        singlePassBackdrop = !hasWallpaper && cfg.skyIntensity == 0;
        if (singlePassBackdrop) {
            initSinglePassBackdrop();
        }
        glass = findViewById(R.id.glass);
        glass.setStyle(cfg.glassStyle);
        if (festive) glass.setAccent(festival.accentColor());
        ambient = findViewById(R.id.ambient);
        // The glow is one bitmap, so its alpha can be applied while drawing it. Otherwise every frame of
        // a fade renders it into a full-screen offscreen buffer first.
        ambient.forceHasOverlappingRendering(false);
        ambientGlow = cfg.ambientGlow && !customWallpaper;
        glowSnap = cfg.glowSnap;
        appGlow = cfg.appGlow && !customWallpaper;
        if (!ambientGlow || singlePassBackdrop) ambient.setVisibility(View.GONE);
        heroName = findViewById(R.id.heroName);
        hero = findViewById(R.id.hero);
        // The hero is now a group (name, and the cinematic lines): without this every fade of the default
        // shelf would render it through an offscreen buffer first, as the glow above notes.
        hero.forceHasOverlappingRendering(false);
        heroMeta = findViewById(R.id.heroMeta);
        heroDots = findViewById(R.id.heroDots);
        heroArt = findViewById(R.id.heroArt);
        cinematic = cfg.cinematicHome;
        glowStyle = cfg.glowStyle;
        heroDotsShown = cfg.cinematicDots;
        cinematicWhiteFocus = cfg.cinematicWhiteFocus || customWallpaper;
        heroArtAlpha = customWallpaper ? 0f : (cfg.cinematicArt / 100f) * 0.42f;
        if (cinematic) setUpCinematicShelf(cfg.cinematicScrim);
        // dock_title false: the name above the dock (and the cinematic lines under it) never shows; the glow
        // still follows the dock.
        if (!cfg.dockTitle) hero.setVisibility(View.GONE);
        greeting = findViewById(R.id.greeting);
        clockBlock = findViewById(R.id.leftBlock);
        clockRefresh = new ClockRefresh(clockBlock);
        greetingColor = greeting.getCurrentTextColor();
        if (!cfg.showGreeting) greeting.setVisibility(View.GONE);
        // Keep the other header layouts as designed: their extra lines change the block's centre.
        if (!cfg.showGreeting && !cfg.dateBelowClock && !cfg.shamsiDate && !cfg.lunarDate
                && !cfg.topMenu && !getResources().getBoolean(R.bool.rtl_menu_bar)) {
            centerCompactHeader();
        }
        if (cfg.headerWhite) {
            // The clock's own white for the date and the line before it, instead of their dimmed whites.
            ((TextView) findViewById(R.id.date)).setTextColor(Color.WHITE);
            findViewById(R.id.clockDivider).setBackgroundColor(Color.WHITE);
        }
        if (getResources().getBoolean(R.bool.rtl_menu_bar)) {
            // Center Hebrew greetings over the numeric time, independent of a visible AM/PM label. The clock's row
            // is watched too: date_below_clock can move the whole row right under a wider date.
            View clock = findViewById(R.id.clock);
            View clockRow = (View) clock.getParent();
            View.OnLayoutChangeListener centerGreeting = (v, l, t, right, bottom, ol, ot, or, ob) -> {
                ViewGroup.LayoutParams params = greeting.getLayoutParams();
                if (params.width != clock.getWidth()) {
                    params.width = clock.getWidth();
                    greeting.setLayoutParams(params);
                }
                greeting.setTranslationX(clockRow.getLeft() + clock.getLeft());
            };
            clock.addOnLayoutChangeListener(centerGreeting);
            clockRow.addOnLayoutChangeListener(centerGreeting);
        }
        btnWifi = findViewById(R.id.btnWifi);
        shamsiDate = findViewById(R.id.shamsiDate);
        stats = findViewById(R.id.stats);
        raceCalendar = cfg.raceCalendar;
        racePanel = findViewById(R.id.racePanel);
        raceKicker = findViewById(R.id.raceKicker);
        raceHeadline = findViewById(R.id.raceHeadline);
        raceSessions = findViewById(R.id.raceSessions);
        raceBar = findViewById(R.id.raceBar);
        racePager = findViewById(R.id.racePager);
        gameDay = GameDay.create(this, cfg.gameDay, handler, this::updatePanel);
        // The rotation runs on the clock that never jumps: setting the TV's time must not stall or stretch a turn.
        rotationStart = SystemClock.elapsedRealtime();
        showStats = cfg.showStats;
        inputsConnectedOnly = cfg.inputsConnectedOnly;
        inputsCecStateOnly = cfg.inputsCecStateOnly;
        shamsi = cfg.shamsiDate;
        lunarDate = findViewById(R.id.lunarDate);
        lunar = cfg.lunarDate;
        lunarOffset = Lunar.offsetFor(cfg.lunarUtcOffset, Locale.getDefault());
        if (lunar) lunarDate.setVisibility(View.VISIBLE);
        greetingName = cfg.greetingName;
        if (cfg.dateBelowClock) moveDateBelowClock();
        if (cfg.dateBelowClock && cfg.iconOnlyButtons && !cfg.topMenu
                && !getResources().getBoolean(R.bool.rtl_menu_bar)) {
            alignDateBelowIconsWithClock();
        }
        if (shamsi) {
            shamsiDate.setVisibility(View.VISIBLE);
            TextView date = findViewById(R.id.date);
            date.setTextSize(11f);
            lunarDate.setTextSize(11f);
            date.setPadding(0, (int) (4 * density), 0, 0);
        }
        vpnApp = cfg.vpnApp;
        vpnPill = cfg.vpnPill && !cfg.topMenu;
        btnVpn = findViewById(R.id.btnVpn);
        moduleCards = cfg.moduleCards;
        hiddenApps = new HashSet<>(cfg.hiddenApps);
        libraryExcludedApps = new HashSet<>(cfg.libraryExcludedApps);

        grid = findViewById(R.id.grid);
        rtlAppGrid = getResources().getBoolean(R.bool.rtl_app_grid);
        // Mirror only the app grid; the header and other locales keep their own layouts.
        // single_row: one span laid out sideways, so the same adapter, glass and dock band serve a scrolling row.
        grid.setLayoutManager(new GridLayoutManager(this, singleRow ? 1 : COLUMNS,
                singleRow ? RecyclerView.HORIZONTAL : RecyclerView.VERTICAL, false) {
            @Override
            protected boolean isLayoutRTL() {
                return rtlAppGrid;
            }

            @Override
            protected void calculateExtraLayoutSpace(@NonNull RecyclerView.State state, @NonNull int[] extraLayoutSpace) {
                super.calculateExtraLayoutSpace(state, extraLayoutSpace);
                if (!singleRow) {
                    int rowRoom = Math.max(Math.round(220 * density), getHeight() / 2);
                    extraLayoutSpace[0] = Math.max(extraLayoutSpace[0], rowRoom / 2);
                    extraLayoutSpace[1] = Math.max(extraLayoutSpace[1], rowRoom);
                }
            }

            /**
             * Every "bring this tile on screen" scroll lands here, both RecyclerView's own on focus and
             * onTileFocused's. The stock version treats the grid's top padding as the visible top, but that
             * padding only pushes the dock down the screen: grid rows scroll up into it. With the stock rule,
             * Up into a row above the padding dragged that row down to the padding line, cut it off at the
             * bottom of the screen and could scroll the focused tile out entirely, dropping focus into the
             * top bar. Grid tiles now scroll just enough to show the rect between the grid's top edge and its
             * bottom; dock tiles are left to onTileFocused, which parks the dock row on the padding line.
             */
            @Override
            public boolean requestChildRectangleOnScreen(@NonNull RecyclerView parent, @NonNull View child,
                                                         @NonNull Rect rect, boolean immediate,
                                                         boolean focusedChildVisible) {
                if (singleRow) {
                    // Sideways: scroll just enough to bring the tile's cell inside the padding. Cells are a
                    // column wide, so the row moves a whole tile at a time and the tiles stay on the columns.
                    // The whole cell, not the card rect passed in: the card is narrower and centred in it.
                    int left = child.getLeft();
                    int right = child.getRight();
                    int dx;
                    if (left < getPaddingLeft()) dx = left - getPaddingLeft();
                    else if (right > getWidth() - getPaddingRight()) dx = right - (getWidth() - getPaddingRight());
                    else dx = 0;
                    if (dx == 0) return false;
                    if (immediate || reducedMotion || !animations) parent.scrollBy(dx, 0);
                    else parent.smoothScrollBy(dx, 0);
                    return true;
                }
                if (dockRow && getPosition(child) < COLUMNS) return false;
                int glassPad = Math.round(6 * density);
                // grid already sits below topBar (10dp gap in activity_main.xml), so grid-local y=0 is clear of
                // the header; keep TOP_ROOM_DP (12dp) for the focused tile's upward scale, lift and glass glow.
                int limitTop = Math.round(AppAdapter.TOP_ROOM_DP * density);
                int limitBottom = getHeight() - getPaddingBottom();
                int dy = GridNavigation.verticalRevealDy(
                        child.getTop(), child.getBottom(), rect.top, rect.height(), glassPad,
                        limitTop, limitBottom, getHeight(), Math.round(2 * density),
                        parent.canScrollVertically(-1), parent.canScrollVertically(1));
                if (dy == 0) return false;
                if (immediate || reducedMotion || !animations) parent.scrollBy(0, dy);
                else parent.smoothScrollBy(0, dy, scrollInterpolator, 280);
                return true;
            }
        });
        GridLayoutManager glm = (GridLayoutManager) grid.getLayoutManager();
        glm.setItemPrefetchEnabled(true);
        glm.setInitialPrefetchItemCount(COLUMNS);
        glm.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override public int getSpanSize(int position) { return !singleRow && adapter != null && adapter.isLibraryAt(position) ? COLUMNS : 1; }
        });
        grid.setHasFixedSize(true);
        grid.setItemAnimator(null);
        grid.setItemViewCacheSize(COLUMNS * 2);
        grid.getRecycledViewPool().setMaxRecycledViews(0, COLUMNS * 4);
        // Tiles that scroll out while one is carried come back from the view cache without a rebind; set their
        // dimming again whenever one attaches, so none stays dim after the drop.
        grid.addOnChildAttachStateChangeListener(new RecyclerView.OnChildAttachStateChangeListener() {
            @Override
            public void onChildViewAttachedToWindow(@NonNull View v) {
                v.setAlpha(tileAlpha(grid.getChildAdapterPosition(v)));
            }

            @Override
            public void onChildViewDetachedFromWindow(@NonNull View v) {
            }
        });
        if (dockRow) {
            dockDecoration = new DockDecoration(COLUMNS, density, cinematic, cfg.alwaysShowNames, cfg.dockOpacity,
                    cfg.dockGlass, cfg.dockEvenPadding, cfg.dockBand, singleRow, cfg.dockBandAll);
            grid.addItemDecoration(dockDecoration);
        }
        if (cfg.fadeRows) TopFade.attach(grid, 48 * density);
        // Reduced motion: the tiles are simply there, with no entrance (reloadApps skips it there too).
        if (animations && !reducedMotion) {
            grid.setLayoutAnimation(new LayoutAnimationController(
                    AnimationUtils.loadAnimation(this, R.anim.tile_enter), 0.06f));
        }
        adapter = new AppAdapter(this, this, COLUMNS, cfg);
        dockApps = cfg.dockApps;
        fillDockLeavesSlots = !cfg.fillDock;
        Order savedOrder = Order.load(this);
        adapter.setSavedOrder(Order.ranks(savedOrder, dockApps), Order.docked(savedOrder, dockApps));
        grid.setAdapter(adapter);

        // tvOS layout: push the dock row towards the bottom of the screen, leaving room for
        // rows_below_dock grid rows under it; the rest of the grid stays below until you press Down.
        int rowsBelowDock = cfg.rowsBelowDock;
        grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int w = r - l;
            int h = b - t;
            // Skip cascading relayout callbacks triggered by sibling views (e.g., clock ticks or dock dots)
            // when the grid's own dimensions and top padding have not changed.
            if (gridPaddingPending) {
                // The posted requestLayout() pass just completed with the updated top padding.
                gridPaddingPending = false;
                glassShown = false;
            } else if (w == lastGridW && h == lastGridH && grid.getPaddingTop() == lastGridTopPad && glassShown) {
                return;
            }
            lastGridW = w;
            lastGridH = h;
            // Item height follows the tile size, which follows the columns and the rows that must fit (AppAdapter.tileWidthPx).
            int inner = w - grid.getPaddingLeft() - grid.getPaddingRight();
            int tileW = AppAdapter.tileWidthPx(inner, h, COLUMNS, rowsBelowDock, cfg.dockTitle, cfg.dockRow, density);
            int itemH = AppAdapter.itemHeightPx(AppAdapter.tileHeightPx(tileW), density);
            int dockRow = itemH + (int) (AppAdapter.DOCK_EXTRA_DP * density);   // item height plus DockDecoration offsets
            int gridRows = itemH * rowsBelowDock;
            // Keep the hero below the header when the library is enabled on a short screen,
            // even when the theme requests an extra visible row below the dock.
            int shelfClearance = cfg.appLibrary ? hero.getMeasuredHeight() + (int) (40 * density) : 0;
            int bottomRoomPx = (MainActivity.this.dockRow && rowsBelowDock == 0)
                    ? (int) ((cfg.alwaysShowNames ? 20 : -9) * density)
                    : (int) (AppAdapter.BOTTOM_ROOM_DP * density);
            int want = Math.max(shelfClearance, h - dockRow - gridRows - bottomRoomPx);
            // Where the rows cannot fit even at the smallest tile (3 rows with the title on), the title keeps its
            // room above the dock and the last rows run off the bottom instead of the title drawing over the clock.
            if (MainActivity.this.dockRow && cfg.dockTitle && rowsBelowDock > 0) want = Math.max(want, (int) (AppAdapter.TITLE_ROOM_DP * density));
            // No dock to push down: the grid starts at the top, with room for the first row's zoom and glass.
            if (!MainActivity.this.dockRow) want = (int) (AppAdapter.TOP_ROOM_DP * density);
            lastGridTopPad = want;
            if (grid.getPaddingTop() != want) {
                gridPaddingPending = true;
                glassShown = false;
                glass.animate().cancel();
                glass.setAlpha(0f);
                grid.setPadding(grid.getPaddingLeft(), want, grid.getPaddingRight(), grid.getPaddingBottom());
                grid.post(() -> {
                    grid.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                        @Override
                        public void onGlobalLayout() {
                            grid.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                            gridPaddingPending = false;
                            refitGlass();
                        }
                    });
                    grid.requestLayout();
                });
                return;
            }
            // Cold start or column count > 4: the first focus can run before the grid has its final width or
            // padding, leaving the glass at the XML design size until a key is pressed.
            grid.post(this::refitGlass);
        });

        // The wallpaper caption sits on the screen, not in the grid: fade it while the grid is scrolled away from
        // the dock, so its text never lies over the tiles.
        final int captionScrollSlop = Math.round(12 * density);
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (wallpaperPlayer == null) return;
                wallpaperPlayer.scrolledAway(rv.canScrollVertically(-1)
                        && rv.computeVerticalScrollOffset() > captionScrollSlop);
            }
        });

        // Keep the glass glued to its tile while the grid scrolls.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    dockReturning = false;
                }
            }

            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (!glassShown) return;
                if (!dockReturning) {
                    glassTargetY -= dy;
                    glassScrollShift -= dy;
                    glass.setTranslationY(glass.getTranslationY() - dy);
                }
                // single_row scrolls sideways: the same bookkeeping on the other axis.
                if (dx != 0) {
                    glassTargetX -= dx;
                    glassScrollShiftX -= dx;
                    glass.setTranslationX(glass.getTranslationX() - dx);
                }
            }
        });

        // game_day: the panel above the dock rides up with the cards instead of being covered by them, and fades
        // out by the time it reaches the clock. The grid's own offset is read, so a jump (Home, a restored focus)
        // lands right too.
        if (gameDay != null) {
            View clockBlock = findViewById(R.id.leftBlock);
            grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override
                public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                    int shift = rv.canScrollVertically(-1) ? rv.computeVerticalScrollOffset() : 0;
                    racePanel.setTranslationY(-shift);
                    int room = Math.max(1, racePanel.getTop() - clockBlock.getBottom());
                    racePanel.setAlpha(Math.max(0f, 1f - shift / (float) room));
                }
            });
        }

        // Liquid feel: soft springs on the highlight's position.
        springX = new SpringAnimation(glass, new FloatPropertyCompat<View>("glassContentX") {
            @Override
            public float getValue(View v) {
                return v.getTranslationX() - glassScrollShiftX;
            }

            @Override
            public void setValue(View v, float value) {
                v.setTranslationX(value + glassScrollShiftX);
            }
        });
        springY = new SpringAnimation(glass, new FloatPropertyCompat<View>("glassContentY") {
            @Override
            public float getValue(View v) {
                return v.getTranslationY() - glassScrollShift;
            }

            @Override
            public void setValue(View v, float value) {
                v.setTranslationY(value + glassScrollShift);
            }
        });
        for (SpringAnimation s : new SpringAnimation[]{springX, springY}) {
            if (reducedMotion) {
                Motion.reduce(s);
            } else if (!focusOvershoot) {
                s.setSpring(new SpringForce().setStiffness(advancedEffects ? 520f : 440f).setDampingRatio(1f));
            } else {
                s.setSpring(new SpringForce().setStiffness(advancedEffects ? 320f : 260f)
                        .setDampingRatio(advancedEffects ? 0.76f : 0.58f));
            }
        }

        btnInputs = findViewById(R.id.btnInputs);
        View btnSettings = findViewById(R.id.btnSettings);
        btnInputs.setOnClickListener(v -> TvInputRouter.showInputs(this));
        btnWifi.setOnClickListener(v -> openNetworkPill());
        btnVpn.setOnClickListener(v -> openVpnPill());
        btnWifi.setOnLongClickListener(v -> {
            open(systemIntent(Settings.ACTION_WIFI_SETTINGS));
            return true;
        });
        launcherSettingsButton = cfg.launcherSettingsButton;
        if (launcherSettingsButton) {
            ((TextView) btnSettings).setText(R.string.launcher_settings);
            ((TextView) btnSettings).setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_home_settings_pill, 0, 0, 0);
        }
        btnSettings.setOnClickListener(v -> {
            if (launcherSettingsButton) {
                openOwnedActivity(new Intent(this, LauncherSettingsActivity.class), v, LAUNCHER_SETTINGS_REQUEST);
            } else {
                open(systemIntent(Settings.ACTION_SETTINGS));
            }
        });
        btnReorder = findViewById(R.id.btnReorder);
        btnReorder.setOnClickListener(v -> toggleReorder());
        btnReorder.setVisibility(View.GONE);
        if (!cfg.showNetworkPill) btnWifi.setVisibility(View.GONE);
        showInputsPill = cfg.showInputsPill;
        showInputsMenuRow = cfg.showInputsPill;
        btnLibraryTop = findViewById(R.id.btnLibraryTop);
        btnLibraryTop.setOnClickListener(v -> {
            onLibraryRequested();
            libraryFromTopBar = true;   // closing it goes back to this button, not to the pill after the last row
        });
        btnLibraryTop.setVisibility(cfg.libraryButton && cfg.appLibrary ? View.VISIBLE : View.GONE);
        btnSearch = findViewById(R.id.btnSearch);
        if (btnSearch != null) {
            btnSearch.setOnClickListener(v -> {
                UiFeedback.playClick(v);
                SpotlightDialog.show(this);
            });
        }
        btnNowPlaying = findViewById(R.id.btnNowPlaying);
        mediaNowPlaying = showNowPlayingHome ? new MediaNowPlaying(this, this::updateNowPlayingPill) : null;
        if (btnNowPlaying != null) {
            if (!showNowPlayingHome) btnNowPlaying.setVisibility(View.GONE);
            btnNowPlaying.setOnClickListener(v -> {
                UiFeedback.playClick(v);
                showNowPlayingMenu(v);
            });
            btnNowPlaying.setOnLongClickListener(v -> {
                if (mediaNowPlaying != null) mediaNowPlaying.togglePlayPause();
                return true;
            });
        }
        View topBar = findViewById(R.id.topBar);
        if (topBar != null) {
            topBar.setBackground(new AdaptiveGlassDrawable(0xFFFFFFFF, density, 24f * density, false, false));
        }
        for (View pill : new View[]{btnNowPlaying, btnSearch, btnInputs, btnWifi, btnVpn, btnLibraryTop, btnSettings}) {
            if (pill != null) {
                pill.setBackground(new AdaptiveGlassDrawable(0xFFFFFFFF, density, 19f * density, false, true));
            }
        }
        addTopBarApps(cfg.topBarApps, cfg.launchActions);
        iconOnlyButtons = cfg.iconOnlyButtons;
        topMenu = cfg.topMenu;
        wifiIconOnly = iconOnlyButtons && cfg.wifiLabelWidth <= 0;
        if (cfg.wifiStatusColor) {
            wifiPlainTint = btnWifi.getCompoundDrawableTintList();
            wifiOnlineTint = getColorStateList(R.color.pill_icon_online);
            if (cfg.wifiStatusWarnings) {
                wifiNoInternetTint = getColorStateList(R.color.pill_icon_no_internet);
                wifiOfflineTint = getColorStateList(R.color.pill_icon_offline);
            }
        }
        if (cfg.topMenu) {
            // One gear: Inputs and Wi-Fi move into its dropdown. The Wi-Fi pill stays hidden but keeps its
            // label up to date, which the menu's Wi-Fi row shows.
            showInputsPill = false;
            btnInputs.setVisibility(View.GONE);
            btnWifi.setVisibility(View.GONE);
            ((TextView) btnSettings).setText(R.string.settings);
            ((TextView) btnSettings).setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_settings, 0, 0, 0);
            if (!iconOnlyButtons) makeIconOnly((TextView) btnSettings);
            btnSettings.setOnClickListener(v -> HomeMenus.showTopMenu(this));
            // The lone gear sits at the header's middle, not on its bottom line like the row of pills.
            LinearLayout topButtons = findViewById(R.id.topButtons);
            RelativeLayout.LayoutParams tlp = (RelativeLayout.LayoutParams) topButtons.getLayoutParams();
            tlp.addRule(RelativeLayout.ALIGN_TOP, R.id.leftBlock);
            tlp.bottomMargin = 0;
            topButtons.setLayoutParams(tlp);
            topButtons.setGravity(Gravity.CENTER_VERTICAL);
        }
        if (iconOnlyButtons) {
            if (cfg.wifiLabelWidth > 0) btnWifi.setMaxWidth(Math.round(cfg.wifiLabelWidth * density));
            else makeIconOnly(btnWifi);
            makeIconOnly((TextView) btnSettings);
        }
        if (cfg.iconOnlyInputs) makeIconOnly((TextView) btnInputs);
        iconOnlyVpn = vpnPill && cfg.iconOnlyVpn;
        if (iconOnlyVpn) {
            vpnPlainBackground = btnVpn.getBackground().getConstantState();
            vpnPlainInk = btnVpn.getCompoundDrawableTintList();
            makeIconOnly(btnVpn);
        }
        btnSettings.setOnLongClickListener(v -> {
            HomeMenus.showSettingsMenu(this);
            return true;
        });

        // Keep focus on the top bar at its ends and at the top: Android's focus finder would otherwise
        // jump to a tile far below when there is nothing in that direction.
        boolean rtlMenuBar = getResources().getBoolean(R.bool.rtl_menu_bar);
        View.OnKeyListener pillKeys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                // Back to the dock tile Up came from, not wherever the focus finder lands.
                focusTile(singleRow ? lastFocused : lastFocused % COLUMNS);
                return true;
            }
            int firstButton = firstVisibleTopButton().getId();
            int leftButton = rtlMenuBar ? R.id.btnSettings : firstButton;
            int rightButton = rtlMenuBar ? firstButton : R.id.btnSettings;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return v.getId() == leftButton;
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == rightButton;
        };
        if (btnNowPlaying != null) btnNowPlaying.setOnKeyListener(pillKeys);
        if (btnSearch != null) btnSearch.setOnKeyListener(pillKeys);
        btnInputs.setOnKeyListener(pillKeys);
        btnWifi.setOnKeyListener(pillKeys);
        btnVpn.setOnKeyListener(pillKeys);
        btnSettings.setOnKeyListener(pillKeys);
        btnReorder.setOnKeyListener(pillKeys);
        btnLibraryTop.setOnKeyListener(pillKeys);
        for (View button : topAppButtons) button.setOnKeyListener(pillKeys);
        boolean smoothTop = animations && !reducedMotion;
        for (View pill : new View[]{btnNowPlaying, btnSearch, btnInputs, btnWifi, btnVpn, btnReorder, btnLibraryTop, btnSettings}) {
            Motion.attachSmoothFocus(pill, smoothTop, 1.05f);
        }
        for (View button : topAppButtons) {
            Motion.attachSmoothFocus(button, smoothTop, 1.05f);
        }
        if (festive) applyFestiveSkin((TextView) btnInputs, (TextView) btnSettings);

        // Hide the glass when focus leaves the tiles.
        // Taken off in onDestroy: recreate() keeps the window, and a listener left on it held every earlier Home.
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener(glassFocusListener);

        packageFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packageFilter.addDataScheme("package");
        timeFilter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        // Registered for the activity's whole life, not only while it is started: an app installed or updated while
        // another app is in front must still mark the tiles stale, or Home would have to rebuild on every return.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(packageReceiver, packageFilter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(packageReceiver, packageFilter);
            }
            packageReceiverRegistered = true;
        } catch (RuntimeException ignored) {
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(wakeReceiver, new IntentFilter(Intent.ACTION_SCREEN_ON), Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(wakeReceiver, new IntentFilter(Intent.ACTION_SCREEN_ON));
            }
            wakeReceiverRegistered = true;
        } catch (RuntimeException ignored) {
        }

        // From the application, not this activity: on Android 12 a ConnectivityManager keeps the context it was
        // made with (its static sInstance, and the callback Messenger the system holds), so every recreate() left
        // the previous Home and its bitmaps behind.
        ConnectivityManager cm = getApplicationContext().getSystemService(ConnectivityManager.class);
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) {
            try {
                tim.registerCallback(inputCallback, handler);
                inputCallbackRegistered = true;
            } catch (RuntimeException ignored) {
            }
        }
        if (cm != null) {
            try {
                cm.registerDefaultNetworkCallback(networkCallback);
                networkCallbackRegistered = true;
            } catch (RuntimeException ignored) {
            }
            try {
                cm.registerNetworkCallback(new NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                        .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                        .build(), vpnCallback);
                vpnCallbackRegistered = true;
            } catch (RuntimeException ignored) {
            }
        }
        // Not after recreate(): the launch intent survives it and would open the settings again.
        if (savedInstanceState == null && !openReorderIfAsked(getIntent())
                && !openSpotlightIfAsked(getIntent())
                && !openLibraryIfAsked(getIntent())) {
            openInputsIfAsked(getIntent());
        }
    }

    private boolean openSpotlightIfAsked(Intent intent) {
        if (intent == null || !ACTION_SPOTLIGHT_SEARCH.equals(intent.getAction())) return false;
        setIntent(new Intent(Intent.ACTION_MAIN));
        getWindow().getDecorView().post(() -> {
            if (!isFinishing() && !isDestroyed()) SpotlightDialog.show(this);
        });
        return true;
    }

    /**
     * handle_view_inputs: switch InputsRequestActivity, the VIEW_INPUTS handler, on or off. It is disabled in the
     * manifest, so a TV with its own input picker never sees it; only the sets that need it turn it on. Written
     * only when the state differs, so an ordinary start does not touch the package manager.
     */
    private void applyViewInputsHandler(boolean on) {
        ComponentName handler = new ComponentName(this, InputsRequestActivity.class);
        PackageManager pm = getPackageManager();
        int want = on ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
        if (pm.getComponentEnabledSetting(handler) != want) {
            pm.setComponentEnabledSetting(handler, want, PackageManager.DONT_KILL_APP);
        }
    }

    /**
     * VIEW_INPUTS, forwarded by InputsRequestActivity while handle_view_inputs is on: open the Inputs menu once
     * the pill is on screen. The intent is replaced so a later recreate() does not
     * open it again. Nothing to do when the TV has no inputs to list (the pill is hidden then).
     * top_menu hides the pill, so the list hangs under the gear there, as in showInputs().
     */
    private boolean openInputsIfAsked(Intent intent) {
        if (intent == null || !ACTION_VIEW_INPUTS.equals(intent.getAction())) return false;
        setIntent(new Intent(Intent.ACTION_MAIN));
        View anchor = topMenu ? findViewById(R.id.btnSettings) : btnInputs;
        if (anchor.getVisibility() != View.VISIBLE) return true;
        if (anchor.isLaidOut()) {
            // Android 9 delivers a new intent to a paused activity with a brief resume and pause around it, and
            // onPause() closes popups, so open the menu once that has run (HiSmartTV A4, Android TV 9).
            anchor.post(() -> {
                if (isFinishing() || anchor.getVisibility() != View.VISIBLE) return;
                anchor.requestFocus();
                TvInputRouter.showInputs(this);
            });
            return true;
        }
        // Cold start: the popup anchors to the pill (or the gear), so wait for the first layout.
        anchor.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                anchor.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                anchor.post(() -> {
                    if (isFinishing() || anchor.getVisibility() != View.VISIBLE) return;
                    anchor.requestFocus();
                    TvInputRouter.showInputs(MainActivity.this);
                });
            }
        });
        return true;
    }

    /**
     * ACTION_APP_LIBRARY, sent by the "library" action word: open the App library once Home is on screen. The intent
     * is replaced so a later recreate() does not open it again. With app_library off there is no library, so the
     * press only brings Home forward.
     */
    private boolean openLibraryIfAsked(Intent intent) {
        if (intent == null || !ACTION_APP_LIBRARY.equals(intent.getAction())) return false;
        setIntent(new Intent(Intent.ACTION_MAIN));
        if (!appLibraryEnabled) return true;
        getWindow().getDecorView().post(() -> {
            if (!isFinishing() && !isDestroyed()) onLibraryRequested();
        });
        return true;
    }

    private boolean openReorderIfAsked(Intent intent) {
        if (intent == null || !ACTION_REORDER.equals(intent.getAction())) return false;
        setIntent(new Intent(Intent.ACTION_MAIN));
        if (!tilesReady || adapter == null || adapter.movableCount() < 2) {
            getIntent().putExtra(LauncherSettingsActivity.EXTRA_REORDER, true);
        } else {
            toggleReorder();
        }
        return true;
    }

    /**
     * Home pressed while Home is already in front: singleTask delivers it here, then onResume runs.
     * Back to the top with the first dock tile focused, like the stock launcher. Back from an app
     * never comes this way, so returning to the app you left from is unchanged (#118).
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (openReorderIfAsked(intent)) return;
        if (openSpotlightIfAsked(intent)) return;
        if (openLibraryIfAsked(intent)) return;
        if (openInputsIfAsked(intent)) return;
        if (intent == null || !intent.hasCategory(Intent.CATEGORY_HOME)) return;
        if (gameDay != null && !stopped) gameDay.read();
        grid.scrollToPosition(0);
        lastFocused = 0;
        lastFocusedKey = adapter.getItemCount() > 0 ? adapter.keyAt(0) : null;
        if (stopped) {
            tileRefresh.onHomeAgain();
        } else {
            // Still on screen (the quick panel was over it, or Home was already in front): the tiles are current,
            // so only go back to the first one; rebuilding would replay the entrance animation for nothing.
            grid.post(() -> focusTile(0));
        }
    }

    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override
    protected void onStart() {
        super.onStart();
        stopped = false;
        started = true;
        if (!timeReceiverRegistered) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(timeReceiver, timeFilter, Context.RECEIVER_NOT_EXPORTED);
                } else {
                    registerReceiver(timeReceiver, timeFilter);
                }
                timeReceiverRegistered = true;
            } catch (RuntimeException ignored) {
            }
        }
        if (!wallpaperReceiverRegistered) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(wallpaperReceiver, wallpaperFilter, Context.RECEIVER_NOT_EXPORTED);
                } else {
                    registerReceiver(wallpaperReceiver, wallpaperFilter);
                }
                wallpaperReceiverRegistered = true;
            } catch (RuntimeException ignored) {
            }
        }
        if (liveClockView != null) liveClockView.startTicking();
        updateClockVisibility();
        clockRefresh.start();   // with timeReceiver: while Home is visible, paused or not
        tileRefresh.onStart();
        if (mediaNowPlaying != null) {
            // Defer MediaSession binder IPC until after Home's first frame draws.
            root.post(() -> {
                if (started && !isFinishing() && !isDestroyed() && mediaNowPlaying != null) {
                    mediaNowPlaying.start();
                }
            });
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        UiFeedback.refresh(this);
        resumed = true;
        // A festival that started or ended while Home was in the background: rebuild with or without it.
        if (festive != festival.active(LocalDate.now())) {
            recreate();
            return;
        }
        // Something changed in Launcher settings: rebuild with it, however the settings were left. Only Back
        // delivered their RESULT_OK; Home (the remote's button) clears them off this task and the result comes
        // back cancelled, so a new wallpaper or layout waited for the next process start.
        if (Config.settingsChanged(settingsRevision, settingsRevision())) {
            recreate();
            return;
        }
        restartIdleClock();
        FrameRateLock.apply(this);
        if (restoreKeyServices) ServiceRepair.restore(this);
        moduleCardController.resetPaused();
        if (grid.getAdapter() == null) grid.setAdapter(adapter);   // dropped by onTrimMemory
        switch (tileRefresh.onResume(packagesChangedSinceRebuild())) {
            case REBUILD:
                tilesReady = false;
                reloadApps(true);
                break;
            case REFOCUS:
                // The same tiles: only the focus goes back, as after a rebuild, without replaying the entrance.
                tilesReady = false;
                reloadApps(true, false, false);
                break;
            default:
                break;
        }
        if (TvInputRouter.openLiveTvAfterBoot(this)) return;
        updateGreeting();
        updatePanel();
        if (liveClockView != null) {
            liveClockView.updateTime();
            liveClockView.startTicking();
        }
        if (adapter != null) {
            adapter.launchController.onActivityResumed();
        }
        // Record closed time for any app that was active before Home resumed
        LastOpened.recordClosed(this);
        // Back from an app opened from its tile, focus is still on that tile, so the cinematic "last used" line kept
        // the time from before this launch ("2 days ago" right after opening it). Refresh it with the closed time.
        if (cinematic && heroMeta != null && lastFocused >= 0 && lastFocused < adapter.appCount()) updateHeroMeta(lastFocused);
        // The network and Inputs pills keep their state while Home is in the background, and callbacks update them
        // when the network or the inputs change, so this refresh only catches anything missed. Its binder calls
        // (about 7 ms on a TCL C755, most of it WifiManager.getConnectionInfo) would hold up the first frame back
        // on Home, so after the first resume it runs once that frame is drawn.
        if (pillsShown) {
            refreshPillsAfterNextDraw();
        } else {
            updateWifiLabel();
            TvInputRouter.updateInputsButton(this);
            pillsShown = true;
        }
        if (showStats) startStats();
        if (gameDay != null) gameDay.start();
        sky.update();
        wallpaperPlayer.start();
        handler.postDelayed(minuteTick, MINUTE_MS);
        if (festive && lamp != null) {
            lamp.start();
            LocalDate today = LocalDate.now();
            if (!reducedMotion && !today.equals(lastPetalShower)) {
                lastPetalShower = today;
                handler.removeCallbacks(playPetalShowerTask);
                handler.postDelayed(playPetalShowerTask, 450);
            }
        }
    }

    /** Launcher settings' change count (Config.SETTINGS_REVISION_KEY); onResume rebuilds Home when it moved. */
    private long settingsRevision() {
        return getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE).getLong(Config.SETTINGS_REVISION_KEY, 0);
    }

    /**
     * Only "Reorder tiles" in Launcher settings still answers with a result: Home rebuilds (the saved choices are
     * reloaded) and then enters reorder mode. Every other change is picked up in onResume through the change count.
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == LAUNCHER_SETTINGS_REQUEST && resultCode == RESULT_OK
                && data != null && data.getBooleanExtra(LauncherSettingsActivity.EXTRA_REORDER, false)) {
            getIntent().putExtra(LauncherSettingsActivity.EXTRA_REORDER, true);
            // The old activity must not consume the pending action, or rebuild a second time in onResume.
            settingsRecreationPending = true;
            settingsRevision = settingsRevision();
            recreate();
        }
    }

    private void startPendingSettingsReorder() {
        if (settingsRecreationPending || !tilesReady || !hasWindowFocus() || isFinishing()
                || !getIntent().getBooleanExtra(LauncherSettingsActivity.EXTRA_REORDER, false)) return;
        getIntent().removeExtra(LauncherSettingsActivity.EXTRA_REORDER);
        toggleReorder();
    }

    /** Rebuilds the tiles (and the module cards), then puts focus back where it was. */
    private void reloadApps(boolean animate) {
        reloadApps(animate, true);
    }

    /** @param rebuild false keeps the tiles as they are and only puts focus back (TileRefresh.Action.REFOCUS). */
    private void reloadApps(boolean animate, boolean rebuild) {
        reloadApps(animate, rebuild, true);
    }

    /**
     * @param entrance false puts focus back as an animated reload would, without replaying the tiles' entrance:
     *                 on a return to the same tiles it would only mark a change that didn't happen.
     */
    private void reloadApps(boolean animate, boolean rebuild, boolean entrance) {
        cancelPendingFocusScroll();
        RecyclerView.ViewHolder footer = grid.findViewHolderForAdapterPosition(adapter.appCount());
        final boolean restoreLibrary = adapter.isLibraryAt(adapter.appCount()) && footer != null
                && footer.itemView.findViewById(R.id.btnLibrary) != null
                && footer.itemView.findViewById(R.id.btnLibrary).hasFocus();
        // Capture the app before rebinding can move focus and overwrite its key.
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        final boolean restoreFocus = appLibrary == null && (animate || grid.hasFocus());
        if (restoreFocus) {
            glassShown = false;
            glass.setAlpha(0f);
            heroShown = false;
            hero.setAlpha(0f);
            if (singlePassBackdrop) {
                backdropAmbientVisible = false;
                backdropArtVisible = false;
                renderSinglePassBackdrop();
            } else {
                ambient.setAlpha(0f);
                heroArt.animate().cancel();
                heroArt.setAlpha(0f);
            }
        }
        if (rebuild) reloadTiles();
        if (animate && entrance && animations && !reducedMotion) {
            grid.scheduleLayoutAnimation();
            // A rebuild redraws the grid anyway; without one the entrance needs a draw to start.
            if (!rebuild) grid.invalidate();
        }

        if (restoreFocus) {
            // Keep a focused footer at the end even when the app count changes.
            if (restoreLibrary) grid.post(this::focusLibrary);
            else restoreFocus();
        } else {
            // A package change must not steal focus from a top button or the Inputs popup.
            int pos = adapter.positionOf(returnKey);
            lastFocused = Math.max(0, Math.min(pos >= 0 ? pos : returnSlot, adapter.appCount() - 1));
            lastFocusedKey = adapter.keyAt(lastFocused);
            if (pos < 0) hideHero();
            // A newly installed app may have brought a card with it.
            tilesReady = true;
            startPendingSettingsReorder();
            moduleCardController.showCardPrompt(this);
        }
    }

    /** True when PackageManager reports apps changed since the last rebuild (one binder call). */
    private boolean packagesChangedSinceRebuild() {
        ChangedPackages changed = getPackageManager().getChangedPackages(packageSequence);
        return changed != null && !changed.getPackageNames().isEmpty();
    }

    private int currentPackageSequence(int since) {
        ChangedPackages changed = getPackageManager().getChangedPackages(since);
        return changed != null ? changed.getSequenceNumber() : since;
    }

    /**
     * Rebuilds the tiles; the module cards the owner chose to show stand in for their app's tile.
     * Every step into the card code runs under CardFeature: if it fails, the tiles are the ordinary
     * ones and cards stay off for this process.
     */
    void reloadTiles() {
        if (reordering) endReorder();   // an app installed or removed while reordering: keep the order so far
        // Read these before reload(): rebinding can move focus to the first tile and overwrite them.
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        final List<Cards.Card> none = Collections.emptyList();
        // An updated app may bring new artwork; the backdrop is cheap to make again.
        currentHeroArt = null;
        backdropArtVisible = false;
        if (heroArt != null) {
            heroArt.animate().cancel();
            heroArt.setImageDrawable(null);
        }
        if (singlePassBackdrop) renderSinglePassBackdrop();
        heroArtCache.evictAll();
        heroArtKey = null;
        // One LEANBACK query for both the tiles and the card checks.
        final List<ResolveInfo> leanback = getPackageManager().queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0);
        cards = moduleCards ? CardFeature.run(() -> Cards.discover(this, hiddenApps,
                () -> Cards.load(Cards.storeFile(this)), leanback), none) : none;
        adapter.reload(cards.isEmpty() ? none : CardFeature.run(() -> Cards.shown(cards), none), leanback);
        lastFocusedKey = returnKey;
        lastFocused = returnSlot;
        packageSequence = currentPackageSequence(packageSequence);
        // Pre-warm the first dock app's HeroArt off-thread so initial focus renders at 60fps.
        if (cinematic && heroArtAlpha > 0f && adapter.appCount() > 0) {
            final int warmPos = Math.max(0, Math.min(returnSlot, adapter.appCount() - 1));
            final String warmKey = adapter.keyAt(warmPos);
            final Drawable warmArt = adapter.artworkAt(warmPos);
            final int gen = ++heroArtGen;
            if (warmKey != null && warmArt != null) {
                LauncherExecutors.io().execute(() -> {
                    Bitmap bmp = HeroArt.from(warmArt);
                    if (bmp == null) return;
                    handler.post(() -> {
                        if (gen != heroArtGen || isDestroyed()) {
                            if (bmp.getConfig() != Bitmap.Config.HARDWARE && !bmp.isRecycled()) bmp.recycle();
                            return;
                        }
                        if (heroArtCache.get(warmKey) == null) heroArtCache.put(warmKey, bmp);
                    });
                });
            }
        }
    }

    /**
     * Return to the app we left from, or the same slot if that app is gone. Always refocus: after
     * reload() the view that holds focus may no longer show the app it had.
     */
    void restoreFocus() {
        final boolean returnToLibrary = libraryFocusPending;
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        grid.post(() -> {
            int pos = adapter.positionOf(returnKey);
            if (returnToLibrary || libraryFocusPending) focusLibrary();
            else focusTile(pos >= 0 ? pos : returnSlot);
            tilesReady = true;
            startPendingSettingsReorder();
            moduleCardController.showCardPrompt(this);
        });
    }

    /** Any key on Home restarts the countdown to the idle clock. */
    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        restartIdleClock();
    }

    private void restartIdleClock() {
        root.removeCallbacks(showIdleClock);
        if (resumed && idleClockMs > 0) root.postDelayed(showIdleClock, idleClockMs);
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        // No UP reaches a paused Home, so a vendor-key hold it saw go down would still open its app later.
        // Only Home's own share is dropped: a key service that saw the same DOWN keeps its hold.
        RemoteKeys.release(this);
        cancelHoldOk();
        root.removeCallbacks(showIdleClock);
        endReorder();   // Home or another app while reordering keeps the order so far
        cancelHeroArtPending();
        wallpaperPlayer.stop();
        stopAmbientAnimation();
        hero.animate().cancel();
        ambient.animate().cancel();
        heroArt.animate().cancel();
        handler.removeCallbacks(minuteTick);
        handler.removeCallbacks(playPetalShowerTask);
        handler.removeCallbacks(openWhenOnline);
        startPending = null;   // leaving Home before the network came back: the boot's app stays closed
        stopStats();
        if (gameDay != null) gameDay.stop();
        handler.removeCallbacks(turnTick);
        if (lamp != null) lamp.stop();
        cancelPendingFocusScroll();
        // Nothing of ours stays on screen over another app; an undecided prompt simply returns next time.
        moduleCardController.dismissPrompt();
        if (appLibrary != null) appLibrary.closeImmediate();
        if (openPopup != null) openPopup.dismiss();
        if (adapter != null) {
            adapter.launchController.onActivityPaused();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        started = false;
        cancelHeroArtPending();
        if (timeReceiverRegistered) {
            try {
                unregisterReceiver(timeReceiver);
            } catch (RuntimeException ignored) {
            }
            timeReceiverRegistered = false;
        }
        if (wallpaperReceiverRegistered) {
            try {
                unregisterReceiver(wallpaperReceiver);
            } catch (RuntimeException ignored) {
            }
            wallpaperReceiverRegistered = false;
        }
        clockRefresh.stop();
        if (liveClockView != null) liveClockView.stopTicking();
        if (adapter != null) {
            adapter.launchController.onActivityPaused();
        }
        handler.removeCallbacks(clockCheck);
        stopped = true;
        if (root != null) {
            root.animate().cancel();
            root.setScaleX(1f);
            root.setScaleY(1f);
            root.setAlpha(1f);
        }
        if (mediaNowPlaying != null) mediaNowPlaying.stop();
        tileRefresh.onStop();
    }

    /**
     * Another app needs the memory while Home is in the background: let go of every tile's artwork, and
     * rebuild it when Home comes back. Not at UI_HIDDEN, the ordinary trip to an app, where keeping the
     * tiles is what makes the return instant.
     */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // RUNNING_LOW and CRITICAL also arrive while Home is visible when an accessibility service keeps the
        // process in the foreground; never blank the screen the owner is looking at.
        if (!MemoryTrimPolicy.shouldRelease(level, started)) return;
        adapter.clear();
        // Detach the adapter too: with Home stopped no layout pass runs, so the tile views (and the bitmaps
        // they hold) would stay attached until Home comes back. setAdapter(null) recycles them now.
        grid.setAdapter(null);
        grid.getRecycledViewPool().clear();
        currentHeroArt = null;
        backdropArtVisible = false;
        backdropAmbientVisible = false;
        if (heroArt != null) {
            heroArt.animate().cancel();
            heroArt.setImageDrawable(null);
        }
        if (singlePassBackdrop) renderSinglePassBackdrop();
        heroArtCache.evictAll();
        heroArtKey = null;
        wallpaperPlayer.releaseForMemoryPressure();
        if (sky != null) sky.release();
        tileRefresh.invalidate();
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        onTrimMemory(TRIM_MEMORY_RUNNING_CRITICAL);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            startPendingSettingsReorder();
            if (libraryFocusPending && appLibrary == null) focusLibrary();
            moduleCardController.showCardPrompt(this);
        } else {
            cancelHoldOk();   // a dialog or another window took the remote while OK was held
        }
    }

    /** Hides the glass when focus leaves the tiles (added in onCreate, removed in onDestroy). */
    private final ViewTreeObserver.OnGlobalFocusChangeListener glassFocusListener = (oldF, newF) -> {
        boolean onTile = newF != null && newF.getId() == R.id.card;
        if (!onTile) hideGlass();
    };

    private final MemoryBooster.TrimHook ramBoostHook = new MemoryBooster.TrimHook() {
        @Override
        public void trimForBoost() {
            if (isDestroyed()) return;
            String keepKey = heroArtKey;
            Bitmap keepBmp = keepKey != null ? heroArtCache.get(keepKey) : null;
            heroArtCache.evictAll();
            if (keepKey != null && keepBmp != null && !keepBmp.isRecycled()) {
                heroArtCache.put(keepKey, keepBmp);
            }
            if (grid != null) {
                grid.getRecycledViewPool().clear();
            }
        }

        @Override
        public void onBoostFinished() {
            if (isDestroyed() || !showStats || stats == null) return;
            LauncherExecutors.io().execute(() -> {
                String line = readStatsLine();
                if (line != null && !line.isEmpty()) {
                    handler.post(() -> {
                        if (!isDestroyed() && showStats && stats != null) {
                            stats.setText(line);
                        }
                    });
                }
            });
        }
    };

    @Override
    protected void onDestroy() {
        super.onDestroy();
        MemoryBooster.unregisterTrimHook(ramBoostHook);
        cancelHeroArtPending();
        if (springX != null) springX.cancel();
        if (springY != null) springY.cancel();
        stopAmbientAnimation();
        handler.removeCallbacksAndMessages(null);
        RemoteKeys.release(this);
        cancelHoldOk();
        if (gameDay != null) gameDay.shutdown();
        if (packageReceiverRegistered) {
            try {
                unregisterReceiver(packageReceiver);
            } catch (RuntimeException ignored) {
            }
            packageReceiverRegistered = false;
        }
        if (wakeReceiverRegistered) {
            try {
                unregisterReceiver(wakeReceiver);
            } catch (RuntimeException ignored) {
            }
            wakeReceiverRegistered = false;
        }
        wallpaperPlayer.close();
        releaseSinglePassBackdrop();
        ConnectivityManager cm = getApplicationContext().getSystemService(ConnectivityManager.class);
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null && inputCallbackRegistered) {
            try {
                tim.unregisterCallback(inputCallback);
            } catch (RuntimeException ignored) {
            }
            inputCallbackRegistered = false;
        }
        if (cm != null) {
            if (networkCallbackRegistered) {
                try {
                    cm.unregisterNetworkCallback(networkCallback);
                } catch (RuntimeException ignored) {
                }
                networkCallbackRegistered = false;
            }
            if (vpnCallbackRegistered) {
                try {
                    cm.unregisterNetworkCallback(vpnCallback);
                } catch (RuntimeException ignored) {
                }
                vpnCallbackRegistered = false;
            }
        }
    }

    /**
     * A Settings button sent as a keycode (settings_key_keycodes, e.g. 83 on TCL Google TV remotes) opens the same
     * thing HomeKeyService would. While that service runs it consumes the key first; without it (not enabled,
     * or switched off by a force-stop) the stock launcher that used to handle the key is gone, and the button did
     * nothing even on Home.
     */
    private boolean handleSettingsKey(KeyEvent event) {
        for (int code : settingsKeycodes) {
            if (event.getKeyCode() != code) continue;
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                if (settingsKeyPanel) {
                    openOwnedActivity(new Intent(this, QuickSettingsActivity.class),
                            findViewById(R.id.btnSettings), -1);
                } else {
                    Intent settings = systemIntent(Settings.ACTION_SETTINGS);
                    if (settingsKeyClearTask) settings.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    open(settings);
                }
            }
            return true;
        }
        return false;
    }

    // ---- reorder ---------------------------------------------------------------------------------

    /** Blue the glass turns while reorder mode is on. */
    private static final int REORDER_ACCENT = 0xFF6EC1FF;

    /**
     * Reorder mode (top-bar button). While it is on, every key comes here first:
     * - nothing carried: arrows move focus as usual; OK on a tile picks it up instead of opening the app;
     *   Back ends the mode and saves the order.
     * - a tile carried: arrows move it; Back (or OK) puts it down and the mode stays on for the next one.
     * Other keys (volume, Home) pass through; leaving the launcher ends the mode and saves.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // A real key, not onUserInteraction: that also runs when the firmware starts Live TV over Home at power-on.
        if (event.getAction() == KeyEvent.ACTION_DOWN) HomeKeyService.keyOnHome();
        if (homeIgnoreChannelKeys && RemoteKeys.isChannelKey(event.getKeyCode())) return true;
        // Vendor keys (TCL Netflix button, 4062) never reach the key services, only the focused window, so their
        // "hold:" entries run here too or nowhere. Only while resumed: onPause releases Home's holds, and a DOWN
        // taken after that would arm one nothing releases.
        if (resumed && event.getKeyCode() >= 1000 && RemoteKeys.handle(this, event, homeAppKeys.get(event.getKeyCode()),
                homeHoldAppKeys.get(event.getKeyCode()))) return true;
        if (handleSettingsKey(event)) return true;
        if (resumed && !reordering && openPopup == null && !moduleCardController.isPromptShowing() && appLibrary == null
                && ButtonMapper.handleKeyEvent(this, event)) return true;
        if (!reordering && openPopup == null && !moduleCardController.isPromptShowing() && appLibrary == null
                && event.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP && grid != null && grid.hasFocus()) {
            int pos = focusedTilePosition();
            boolean scrolling = grid.getScrollState() != RecyclerView.SCROLL_STATE_IDLE || pendingFocusScroll != null;
            boolean inDock = pos >= 0 && (singleRow || pos < COLUMNS);
            if ((scrolling && pos < 0) || GridNavigation.shouldHoldAtDockOnUp(
                    inDock, event.getRepeatCount() > 0, scrolling,
                    SystemClock.uptimeMillis() - dockLandedAtMs)) {
                return true;
            }
        }
        if (!reordering && openPopup == null && event.getKeyCode() == KeyEvent.KEYCODE_SEARCH) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                SpotlightDialog.show(this);
            }
            return true;
        }
        if (holdOk(event)) return true;
        if (tileMenuEnabled && !reordering && openPopup == null && !moduleCardController.isPromptShowing()
                && event.getKeyCode() == KeyEvent.KEYCODE_MENU) {
            int position = focusedTilePosition();
            if (position >= 0 && position < adapter.appCount()
                    && !getPackageName().equals(adapter.packageAt(position))) {
                if (RemoteKeys.menuReleased(event.getAction(), event.getFlags())) {
                    onTileLongPress(position, getCurrentFocus());
                }
                return true;
            }
        }
        if (!reordering) return super.dispatchKeyEvent(event);
        if (dockEditing) return dispatchDockEdit(event);
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        int code = event.getKeyCode();
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        boolean back = code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE;
        if (movingKey != null) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (down) moveBy(code);
                    return true;
                default:
                    if (ok || back) {
                        if (!down) drop();
                        return true;
                    }
                    return super.dispatchKeyEvent(event);
            }
        }
        if (back) {
            if (!down) endReorder();
            return true;
        }
        int pos = focusedTilePosition();
        View focus = getCurrentFocus();
        if (ok && focus != null && focus.getId() == R.id.btnLibrary) {
            // The App library pill sits in the grid but is no tile: OK ends the mode (saving the order) and opens it.
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                endReorder();
                onLibraryRequested();
            }
            return true;
        }
        if (ok && grid.hasFocus()) {
            // RecyclerView can briefly report NO_POSITION during a move or rebind. Still consume
            // OK on the grid: falling through would let the focused card open its app in reorder mode.
            if (!down && pos >= 0) pickUp(pos);
            return true;
        }
        return super.dispatchKeyEvent(event);   // arrows navigate; OK on a top-bar pill works as usual
    }

    /** Adapter position of the focused tile, or -1 when focus is not on a tile. */
    private int focusedTilePosition() {
        View f = getCurrentFocus();
        if (f == null || f.getId() != R.id.card || !(f.getParent() instanceof View)) return -1;
        return grid.getChildAdapterPosition((View) f.getParent());
    }

    /** The reorder button: turns the mode on (focus goes back to the last tile), or off again. */
    void toggleReorder() {
        if (reordering) {
            endReorder();
            return;
        }
        if (!beginReorder()) return;
        focusTile(Math.max(0, Math.min(lastFocused, adapter.movableCount() - 1)));
        Toast.makeText(this, R.string.reorder_hint, Toast.LENGTH_LONG).show();
    }

    /** The start shared by reorder and edit-dock mode. False when there is nothing to move. */
    private boolean beginReorder() {
        if (adapter.movableCount() < 2) return false;
        // Before the reload below: once it has dropped the empty slots, the dock row also shows grid apps.
        dockInSession = new HashSet<>();
        dockedAtStart = adapter.dockFilled();
        for (int i = 0; i < Math.min(dockedAtStart, adapter.movableCount()); i++) dockInSession.add(adapter.keyAt(i));
        adapter.setReordering(true);
        if (adapter.hasEmptySlots()) reloadTiles();   // every position must hold a tile that can move
        reordering = true;
        orderAtStart = adapter.movableKeys();
        glass.setAccent(REORDER_ACCENT);
        btnReorder.setActivated(true);
        return true;
    }

    /** Edit dock (Settings menu): focus goes to the first dock place, which OK then chooses. */
    void startDockEdit() {
        if (reordering) endReorder();
        if (!beginReorder()) return;
        dockEditing = true;
        dockEditSlot = -1;
        focusTile(0);
        Toast.makeText(this, R.string.dock_edit_hint, Toast.LENGTH_LONG).show();
    }

    /**
     * Keys in edit-dock mode. First OK on a dock tile chooses that place (the other dock tiles dim) and focus moves
     * to the grid; then OK on any tile swaps that app into the place, saves the order and ends the mode. Back goes
     * one step back: from choosing the app to choosing the place, and from there out of the mode.
     */
    private boolean dispatchDockEdit(KeyEvent event) {
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        int code = event.getKeyCode();
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        boolean back = code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE;
        if (back) {
            if (!down) {
                if (dockEditAppKey != null) {
                    endReorder();   // "Add to dock" cancelled before a place was chosen
                } else if (dockEditSlot >= 0) {
                    int slot = dockEditSlot;
                    dockEditSlot = -1;
                    dimOthers();
                    focusTile(slot);
                } else {
                    endReorder();
                }
            }
            return true;
        }
        int pos = focusedTilePosition();
        if (ok && pos >= 0) {
            if (!down) {
                int n = adapter.movableCount();
                if (dockEditAppKey != null) {
                    if (pos < Math.min(COLUMNS, n)) {
                        int from = adapter.positionOf(dockEditAppKey);
                        if (from >= 0) swapIntoDock(pos, from);
                        else endReorder();
                    }
                } else if (dockEditSlot < 0) {
                    if (pos < Math.min(COLUMNS, n)) {
                        dockEditSlot = pos;
                        dimOthers();
                        focusTile(Math.min(COLUMNS, n - 1));
                        Toast.makeText(this, R.string.dock_edit_pick_app, Toast.LENGTH_LONG).show();
                    }
                } else if (pos < n) {
                    swapIntoDock(dockEditSlot, pos);
                }
            }
            return true;   // never opens the app while editing
        }
        return super.dispatchKeyEvent(event);   // arrows navigate as usual
    }

    /**
     * Edit-dock mode started from the tile menu. A dock app: its place is already chosen, so focus goes to the grid
     * for the app to put there. A grid app: it is already chosen, so focus goes to the dock for the place.
     */
    private void startDockEditFor(String key, boolean inDock) {
        if (reordering) endReorder();
        if (!beginReorder()) return;
        dockEditing = true;
        int pos = adapter.positionOf(key);   // beginReorder can rebuild the tiles
        int n = adapter.movableCount();
        if (pos < 0) {
            endReorder();
        } else if (inDock) {
            dockEditSlot = pos;
            dimOthers();
            focusTile(Math.min(COLUMNS, n - 1));
            Toast.makeText(this, R.string.dock_edit_pick_app, Toast.LENGTH_LONG).show();
        } else {
            dockEditAppKey = key;
            focusTile(0);
            Toast.makeText(this, R.string.dock_edit_pick_place, Toast.LENGTH_LONG).show();
        }
    }

    /** Puts the app at pos into dock place slot, and the app that was there where the chosen one was; then saves. */
    private void swapIntoDock(int slot, int pos) {
        String slotKey = adapter.keyAt(slot);
        if (pos != slot) {
            keepScroll(() -> {
                adapter.move(pos, slot);
                adapter.move(adapter.positionOf(slotKey), pos);
            });
            if (pos >= COLUMNS) dockInSession.remove(slotKey);
        }
        // Outside the check: with no empty slots shown, the chosen app may already sit in the free place.
        dockInSession.add(adapter.keyAt(slot));
        endReorder();
        refocusAfterLayout(slot);
    }

    private void pickUp(int pos) {
        if (pos >= adapter.movableCount()) return;   // the "Default home" tile stays put
        movingKey = adapter.keyAt(pos);
        dimOthers();
    }

    /** Puts the carried tile down where it is; the mode stays on. */
    private void drop() {
        movingKey = null;
        dimOthers();
    }

    /** Applies tileAlpha to every attached tile (carried tile bright; the rest dim while reordering). */
    private void dimOthers() {
        for (int i = 0; i < grid.getChildCount(); i++) {
            View item = grid.getChildAt(i);
            item.setAlpha(tileAlpha(grid.getChildAdapterPosition(item)));
        }
    }

    /** A tile's alpha: the carried tile stays bright; every other tile is dim while reordering, including after a
     * drop, until Back ends the mode. In edit-dock mode, dock tiles other than the chosen place are dimmed. */
    private float tileAlpha(int pos) {
        if (movingKey != null) {
            return pos != RecyclerView.NO_POSITION && movingKey.equals(adapter.keyAt(pos)) ? 1f : 0.45f;
        }
        return GridNavigation.idleAlpha(pos, COLUMNS, dockEditing, dockEditSlot, reordering);
    }

    /** Ends the mode and saves the order if it changed since the mode started. */
    private void endReorder() {
        if (!reordering) return;
        dockEditing = false;
        dockEditSlot = -1;
        dockEditAppKey = null;
        drop();
        reordering = false;
        dimOthers();   // drop() ran while reordering was still on; restore full brightness now the mode is off
        adapter.setReordering(false);
        if (festive) {
            glassAccent = 0;
            glass.setAccent(festival.accentColor());
        } else {
            int pos = Math.max(0, Math.min(lastFocused, adapter.appCount() - 1));
            boolean coloured = adapter.appCount() > 0
                    && (appGlow || (cinematic && !cinematicWhiteFocus && (singleRow || pos < COLUMNS)));
            glassAccent = coloured ? adapter.ambientColorAt(pos) : 0;
            glass.setAccent(glassAccent);
        }
        btnReorder.setActivated(false);
        List<String> keys = adapter.movableKeys();
        List<String> before = orderAtStart;
        orderAtStart = null;
        // fill_dock false: the dock is what the owner put there, saved with its size, so the empty slots that
        // come back after the mode go after it (#499). With fill_dock true every dock slot holds an app anyway.
        int docked = -1;
        if (fillDockLeavesSlots) {
            docked = DockSlots.dockCount(keys, COLUMNS, dockInSession);
            keys = DockSlots.dockFirst(keys, COLUMNS, dockInSession);
        }
        dockInSession = null;
        if (!keys.equals(before) || (fillDockLeavesSlots && docked != dockedAtStart)) {
            Order order = new Order(Order.dockKey(dockApps), keys, docked);
            if (Order.save(Order.file(this), order)) {
                adapter.setSavedOrder(Order.ranks(order, dockApps), Order.docked(order, dockApps));
            } else {
                Toast.makeText(this, R.string.reorder_not_saved, Toast.LENGTH_SHORT).show();
            }
        }
        if (fillDockLeavesSlots) reloadTiles();   // the empty dock slots left out while reordering come back
    }

    /**
     * Moves the carried tile one step. Left and Right wrap across rows, like a list; Up and Down move a whole row,
     * and Down into a short last row lands on its last tile. Nothing moves past either end or up into the top bar.
     */
    private void moveBy(int keyCode) {
        int from = adapter.positionOf(movingKey);
        int n = adapter.movableCount();
        if (from < 0 || from >= n) {
            drop();
            return;
        }
        // A single row has no rows to move between: Up and Down leave the carried tile where it is.
        if (singleRow && (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN)) return;
        int to;
        // rtl_app_grid (Hebrew) mirrors the grid: position 0 is on the right, so Left moves towards the end.
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) to = rtlAppGrid ? from + 1 : from - 1;
        else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) to = rtlAppGrid ? from - 1 : from + 1;
        else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) to = from - COLUMNS;
        else if (from + COLUMNS < n) to = from + COLUMNS;
        else to = from / COLUMNS < (n - 1) / COLUMNS ? n - 1 : from;
        if (to < 0 || to >= n || to == from) return;
        keepScroll(() -> adapter.move(from, to));
        // The owner carries this tile: where it lands decides whether it is in the dock, not dock_apps (#499).
        if (to < COLUMNS) dockInSession.add(movingKey);
        else dockInSession.remove(movingKey);
        refocusAfterLayout(to);
    }

    /**
     * Applies an adapter change without the grid jumping. After a move the layout manager anchors on the focused
     * tile, keeping it at its old place on screen and shifting every other row; pinning the first visible row at its
     * current offset as the next layout's anchor keeps the scroll where it was. onTileFocused still scrolls
     * afterwards if the moved tile itself ends up off screen.
     */
    private void keepScroll(Runnable change) {
        GridLayoutManager lm = (GridLayoutManager) grid.getLayoutManager();
        int first = lm.findFirstVisibleItemPosition();
        View firstView = first != RecyclerView.NO_POSITION ? lm.findViewByPosition(first) : null;
        // single_row scrolls sideways, so its first visible tile is pinned by its left edge instead; mirrored by
        // rtl_app_grid, the row is laid out from the right, and the offset counts from the right edge.
        int offset = firstView == null ? 0
                : singleRow && rtlAppGrid ? grid.getWidth() - grid.getPaddingRight() - lm.getDecoratedRight(firstView)
                : singleRow ? lm.getDecoratedLeft(firstView) - grid.getPaddingLeft()
                : lm.getDecoratedTop(firstView) - grid.getPaddingTop();
        change.run();
        if (firstView != null) lm.scrollToPositionWithOffset(first, offset);
    }

    /**
     * Once the grid has applied the change, put the glass (and focus, if the move dropped it) on the tile at pos.
     * The grid has a fixed size, so it applies adapter changes on the next animation frame rather than in a layout
     * pass; until then it cannot find the tile by position. Wait frame by frame (at most 10) for that. No focus
     * change fires for a moved tile, so focusTile's already-focused path calls onTileFocused itself.
     */
    private void refocusAfterLayout(int pos) {
        grid.postOnAnimation(new Runnable() {
            int frames;

            @Override
            public void run() {
                if ((grid.hasPendingAdapterUpdates() || grid.isLayoutRequested()) && ++frames < 10) {
                    grid.postOnAnimation(this);
                    return;
                }
                focusTile(pos);
                dimOthers();   // views that came into view or were rebound during the move
            }
        });
    }

    private void openScreensaver() {
        startActivity(new Intent(this, ClockActivity.class));
        Motion.applyFadeTransition(this, animations && !reducedMotion);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        // Home screen: Back goes nowhere, unless back_opens_clock is on.
        if (backOpensClock) openScreensaver();
    }

    // ---- glass highlight -------------------------------------------------------------------------

    @Override
    public void onLibraryRequested() {
        if (appLibrary != null) appLibrary.closeImmediate();
        if (openPopup != null) openPopup.dismiss();
        View libraryAnchor = getCurrentFocus();
        if (libraryAnchor == null || libraryAnchor == grid || !libraryAnchor.isAttachedToWindow()) {
            libraryAnchor = btnLibraryTop;
        }
        libraryFocusPending = false;
        libraryFromTopBar = false;
        libraryFromVpnPill = false;
        appLibrary = new AppLibraryDialog(this, animations, reducedMotion, appLibrarySelection, libraryOkOpens, adapter::containsPackage, () -> reloadApps(false), () -> {
            appLibrary = null;
            openPopup = null;
            if (libraryFromTopBar) {
                libraryFromTopBar = false;
                btnLibraryTop.requestFocus();
                return;
            }
            boolean fromVpnPill = libraryFromVpnPill;
            libraryFromVpnPill = false;
            // The VPN pill can hide while the library is open (its VPN dropped): then focus goes as from the grid.
            if (fromVpnPill && btnVpn.getVisibility() == View.VISIBLE) {
                btnVpn.requestFocus();
                return;
            }
            libraryFocusPending = true;
            grid.post(() -> {
                if (libraryFocusPending && appLibrary == null && hasWindowFocus() && !isFinishing()) focusLibrary();
            });
        }, libraryExcludedApps);
        if (libraryAnchor != null && libraryAnchor.isAttachedToWindow()) {
            appLibrary.setMorphAnchor(libraryAnchor);
        }
        openPopup = appLibrary.popup;
        appLibrary.show();
    }

    @Override
    public void onTileFocused(int position, View card) {
        if (dockRow && !singleRow && position < COLUMNS && lastFocused >= COLUMNS) {
            dockLandedAtMs = SystemClock.uptimeMillis();
        }
        lastFocused = position;
        lastFocusedKey = adapter.keyAt(position);
        // Focus restored before the first layout or while the dock top padding is pending (cold start):
        // the tile has no size yet or still sits at pre-padding coordinates. Wait for layout to settle.
        if (card.getWidth() == 0 || gridPaddingPending || (dockRow && lastGridTopPad < 0)) {
            card.post(() -> {
                if (card.isFocused() && card.getWidth() > 0 && !gridPaddingPending
                        && (!dockRow || lastGridTopPad >= 0)) {
                    onTileFocused(position, card);
                }
            });
            return;
        }
        // Tile rect in root coordinates, using layout positions (ignores the scale animation).
        Rect r = tileRect;
        r.set(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, r);
        float pad = 6f * density;        // matches GlassView's glow inset
        float scale = adapter.focusScale();
        int targetW = Math.round(card.getWidth() * scale + 2 * pad);
        int targetH = Math.round(card.getHeight() * scale + 2 * pad);
        float x = r.exactCenterX() - targetW / 2f;
        // advanced_effects lifts a focused grid tile by 4dp and the glass follows it; dock tiles stay put (#2135).
        boolean lifted = advancedEffects && !DockSlots.inDockRow(dockRow, singleRow, position, COLUMNS);
        float y = r.exactCenterY() - targetH / 2f - (lifted ? 4f * density : 0f);

        View item = (View) card.getParent();
        boolean inDock = dockRow && (singleRow || position < COLUMNS);
        int dockDy = 0;
        boolean dockScroll = false;
        if (inDock) {
            // Dock: park it at the bottom of the screen and show the top shelf for this app. In a single row
            // every tile is in the dock's row, which never scrolls vertically.
            int decoratedTop = item.getTop() - (int) (6 * density);
            dockDy = decoratedTop - grid.getPaddingTop();
            // A 1px mismatch used to start a scroll on every dock move. Only scroll when the row is
            // actually off; reduced motion does it in one jump so it does not fight the highlight.
            dockScroll = !singleRow && Math.abs(dockDy) > (int) (8 * density);
        }
        float finalY = dockScroll ? y - dockDy : y;
        dockReturning = dockScroll && animations && !reducedMotion;

        ViewGroup.LayoutParams lp = glass.getLayoutParams();
        if (lp.width != targetW || lp.height != targetH || card.getWidth() != glassCardW) {
            lp.width = targetW;
            lp.height = targetH;
            glass.setLayoutParams(lp);
            // GlassView draws its slab inset by pad, i.e. exactly over the zoomed tile: same corners as the tile.
            glass.setRadius(AppAdapter.cornerRadius(card.getWidth(), density) * scale);
        }
        glassCardW = card.getWidth();
        if (dockScroll) {
            glassCardRect.set(r.left, r.top - dockDy, r.right, r.bottom - dockDy);
        } else {
            glassCardRect.set(r);
        }
        glassTargetX = x;
        glassTargetY = finalY;
        // Cinematic shelf: the highlight glows in the dock app's colour, plain white in the grid (and on the dock with
        // cinematic_white_focus). app_glow: every tile. In a single row every tile is the dock's kind.
        boolean coloured = (appGlow || cinematic && !cinematicWhiteFocus && (singleRow || position < COLUMNS)) && !festive;
        int accent = coloured ? adapter.ambientColorAt(position) : 0;
        if ((cinematic || appGlow) && !festive && accent != glassAccent) {
            glassAccent = accent;
            glass.setAccent(accent);
        }
        if (!glassShown) {
            springX.cancel();
            springY.cancel();
            glass.setTranslationX(x);
            glass.setTranslationY(finalY);
            if (animations && !reducedMotion) glass.animate().alpha(1f).setDuration(180).start();
            else glass.setAlpha(1f);
            glassShown = true;
        } else if (!animations) {
            springX.cancel();
            springY.cancel();
            glass.setTranslationX(x);
            glass.setTranslationY(finalY);
        } else {
            springX.animateToFinalPosition(x - glassScrollShiftX);
            springY.animateToFinalPosition(finalY - glassScrollShift);
        }
        if (inDock) {
            if (dockScroll) {
                if (!animations || reducedMotion) grid.scrollBy(0, dockDy);
                else {
                    int duration = Math.min(420, Math.max(300, Math.round(Math.abs(dockDy) / density * 1.1f)));
                    grid.smoothScrollBy(0, dockDy, scrollInterpolator, duration);
                }
            }
            showHero(position, dockScroll ? r.top - dockDy : r.top);
            lastFocusMoveMs = SystemClock.uptimeMillis();
        } else {
            // Below the dock (or in a plain grid) the glow follows the tile without the big name; letting it
            // fade out left the grid on plain black.
            showGlowOnly(position);
            lastFocusMoveMs = SystemClock.uptimeMillis();
            // Scroll just enough so the whole glass (not only the tile) is visible; skip if FocusFinder already started the scroll.
            if (grid.getScrollState() == RecyclerView.SCROLL_STATE_IDLE) {
                int padPx = Math.round(pad);
                revealRect.set(0, -padPx * 2, item.getWidth(), item.getHeight() + padPx);
                grid.requestChildRectangleOnScreen(item, revealRect, reducedMotion);
            }
        }
    }

    /** Refits the glass to the focused holder once the grid layout and tile dimensions settle. */
    private void refitGlass() {
        if (isDestroyed() || gridPaddingPending || adapter.appCount() == 0 || libraryFocusPending) return;
        int pos = Math.max(0, Math.min(lastFocused, adapter.appCount() - 1));
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
        View card = vh == null ? null : vh.itemView.findViewById(R.id.card);
        if (card == null || card.getWidth() <= 0 || !card.isFocused()) return;
        float pad = 6f * density;
        float scale = adapter.focusScale();
        int w = Math.round(card.getWidth() * scale + 2 * pad);
        int h = Math.round(card.getHeight() * scale + 2 * pad);
        // The tile's rect, not only its width: the grid's top padding lands after the tile has its width, so a
        // cold start can leave the glass at the right size and the wrong place. Comparing the rect catches that.
        Rect r = tileRect;
        r.set(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, r);
        boolean lifted = advancedEffects && !DockSlots.inDockRow(dockRow, singleRow, pos, COLUMNS);
        float expectedX = r.exactCenterX() - w / 2f;
        float expectedY = r.exactCenterY() - h / 2f - (lifted ? 4f * density : 0f);
        if (!glassShown || card.getWidth() != glassCardW || glass.getWidth() != w || glass.getHeight() != h
                || !glassCardRect.equals(r)
                || (!springX.isRunning() && Math.abs(glass.getTranslationX() - expectedX) > 1f)
                || (!springY.isRunning() && Math.abs(glass.getTranslationY() - expectedY) > 1f)) {
            onTileFocused(pos, card);
        }
    }

    // ---- top shelf -------------------------------------------------------------------------------

    private void showHero(int position, float dockTopInRoot) {
        String label = adapter.labelAt(position);
        int color = adapter.ambientColorAt(position);
        float y = dockTopInRoot - 14 * density - 26 * density - hero.getHeight();
        boolean changed = !label.contentEquals(heroName.getText());
        if (cinematic && heroArtAlpha > 0f) showHeroArt(position);

        if (!animations || reducedMotion) {
            // Snap the shelf instead of sliding and fading it: each of those frames repaints the
            // full-screen panel, and this switch exists for the GPUs that cannot afford it.
            hero.animate().cancel();
            if (!singlePassBackdrop) ambient.animate().cancel();
            fillHero(position, label);
            hero.setY(y);
            hero.setTranslationX(0f);
            hero.setAlpha(1f);
            if (ambientGlow) {
                setAmbient(color);
                if (!singlePassBackdrop) ambient.setAlpha(1f);
            }
            heroShown = true;
            return;
        }

        if (!heroShown) {
            stopAmbientAnimation();
            hero.animate().cancel();
            if (!singlePassBackdrop) ambient.animate().cancel();
            fillHero(position, label);
            hero.setY(y + 12 * density);
            hero.setTranslationX(0f);
            hero.animate().y(y).alpha(1f).setDuration(300).setInterpolator(scrollInterpolator).start();
            if (ambientGlow) {
                if (singlePassBackdrop) {
                    if (advancedEffects && ambientColor != -1 && backdropAmbientVisible) {
                        transitionAmbient(color);
                    } else {
                        setAmbient(color);
                    }
                } else if (advancedEffects && ambientColor != -1 && ambient.getBackground() != null) {
                    transitionAmbient(color);
                } else {
                    setAmbient(color);
                    ambient.animate().alpha(1f).setDuration(240).start();
                }
            }
            heroShown = true;
        } else if (changed) {
            hero.animate().cancel();
            fillHero(position, label);
            hero.setY(y);
            hero.setAlpha(0.65f);
            hero.setTranslationX(10 * density);
            hero.animate().alpha(1f).translationX(0f).setDuration(130).setInterpolator(scrollInterpolator).start();
            if (ambientGlow && advancedEffects) {
                transitionAmbient(color);
            } else if (ambientGlow && (color != ambientColor || (singlePassBackdrop && !backdropAmbientVisible))) {
                if (singlePassBackdrop || glowSnap) {
                    if (!singlePassBackdrop) ambient.animate().cancel();
                    setAmbient(color);
                    if (!singlePassBackdrop) ambient.setAlpha(1f);
                } else {
                    ambient.animate().alpha(0.45f).setDuration(100).withEndAction(() -> {
                        setAmbient(color);
                        ambient.animate().alpha(1f).setDuration(200).start();
                    }).start();
                }
            }
        } else {
            hero.setY(y);
            // onPause cancels the fade-ins, and a second Home during one lands on the same tile, which starts none:
            // finish them, or the shelf and the glow stay dim until focus moves to another app.
            if (hero.getAlpha() < 1f) hero.animate().alpha(1f).setDuration(160).start();
            if (ambientGlow) {
                if (singlePassBackdrop) {
                    if (!backdropAmbientVisible || color != ambientColor) setAmbient(color);
                } else if (color == ambientColor && ambient.getAlpha() < 1f) {
                    ambient.animate().alpha(1f).setDuration(220).start();
                }
            }
        }
    }

    /** The shelf's text for this dock app: its name and, on the cinematic shelf, "Last opened" and the dots. */
    private void fillHero(int position, String label) {
        heroName.setText(label);
        if (!cinematic) return;
        updateHeroMeta(position);
        updateHeroDots(position);
    }

    // ---- cinematic shelf -------------------------------------------------------------------------

    /**
     * The parts that never change: a larger name, the meta line and the dots, the artwork and the scrim, at
     * scrimPercent of its strength (cinematic_scrim). cinematic_art 0 leaves the artwork out, 0 percent the scrim.
     */
    private void setUpCinematicShelf(int scrimPercent) {
        heroName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 68f);
        heroName.setLetterSpacing(-0.025f);
        heroMeta.setVisibility(View.INVISIBLE);   // keeps its height, so the name does not jump when it fills in
        heroDots.setVisibility(heroDotsShown ? View.VISIBLE : View.GONE);
        heroArt.setVisibility(!singlePassBackdrop && heroArtAlpha > 0f ? View.VISIBLE : View.GONE);
        // One stretched bitmap: apply its alpha while drawing instead of through an offscreen buffer.
        heroArt.forceHasOverlappingRendering(false);
        View scrim = findViewById(R.id.heroScrim);
        if (scrimPercent <= 0) {
            if (scrim != null) scrim.setVisibility(View.GONE);
            return;
        }
        if (singlePassBackdrop) {
            scrimBmp = buildScrimBitmap();
            scrimAlpha = scrimPercent * 255 / 100;
            if (scrim != null) scrim.setVisibility(View.GONE);
            renderSinglePassBackdrop();
            return;
        }
        Drawable shade = scrimDrawable();
        // On the bitmap's paint, not the view: a view's alpha would draw the full screen through an offscreen buffer.
        shade.setAlpha(scrimPercent * 255 / 100);
        scrim.setBackground(shade);
        scrim.setVisibility(View.VISIBLE);
    }

    private Bitmap buildScrimBitmap() {
        int w = BACKDROP_W, h = BACKDROP_H;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        boolean rtl = getResources().getBoolean(R.bool.rtl_menu_bar);
        Paint paint = new Paint();
        paint.setShader(new LinearGradient(rtl ? w : 0, 0, rtl ? 0 : w, 0,
                new int[]{0xE8040406, 0x8A040406, 0x24040406}, new float[]{0f, 0.42f, 0.72f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);
        paint.setShader(new LinearGradient(0, 0, 0, h,
                new int[]{0x38040406, 0x52040406, 0xE6040406, 0xFF040406}, new float[]{0f, 0.28f, 0.58f, 0.78f},
                Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);
        return bmp;
    }

    /**
     * Darkens the side the shelf's text is on and the lower part of the screen, so the name, the dock and
     * the grid stay readable over the artwork. One small bitmap stretched over the screen, like the glow.
     */
    private Drawable scrimDrawable() {
        BitmapDrawable scrim = new BitmapDrawable(getResources(), buildScrimBitmap());
        scrim.setFilterBitmap(true);
        scrim.setGravity(Gravity.FILL);
        return scrim;
    }

    private void cancelHeroArtPending() {
        if (heroArtPending != null) {
            handler.removeCallbacks(heroArtPending);
            heroArtPending = null;
        }
    }

    /** Swaps the backdrop to this app's artwork asynchronously so UI frames stay locked at 60fps. */
    private void showHeroArt(int position) {
        String key = adapter.keyAt(position);
        if (key == null) return;
        cancelHeroArtPending();
        if (!singlePassBackdrop) heroArt.animate().cancel();
        if (key.equals(heroArtKey)) {
            if (singlePassBackdrop) {
                if (!backdropArtVisible && currentHeroArt != null && !currentHeroArt.isRecycled()) {
                    backdropArtVisible = true;
                    renderSinglePassBackdrop();
                }
            } else {
                fadeHeroArt(160);
            }
            return;
        }
        heroArtKey = key;
        Bitmap cached = heroArtCache.get(key);
        if (cached != null && !cached.isRecycled()) {
            if (singlePassBackdrop) {
                currentHeroArt = cached;
                backdropArtVisible = true;
                renderSinglePassBackdrop();
            } else {
                heroArt.setImageBitmap(cached);
                fadeHeroArt(150);
            }
            return;
        }
        final Drawable artwork = adapter.artworkAt(position);
        if (artwork == null) {
            if (singlePassBackdrop) {
                currentHeroArt = null;
                backdropArtVisible = false;
                renderSinglePassBackdrop();
            } else {
                heroArt.setImageBitmap(null);
                heroArt.setAlpha(0f);
            }
            return;
        }
        final int gen = ++heroArtGen;
        Runnable task = () -> {
            heroArtPending = null;
            if (isFinishing() || isDestroyed() || !key.equals(heroArtKey)) return;
            LauncherExecutors.io().execute(() -> {
                Bitmap art = HeroArt.from(artwork);
                handler.post(() -> {
                    if (art != null) {
                        if (gen != heroArtGen || isDestroyed()) {
                            if (art.getConfig() != Bitmap.Config.HARDWARE && !art.isRecycled()) art.recycle();
                            return;
                        }
                        heroArtCache.put(key, art);
                    }
                    if (!key.equals(heroArtKey) || isFinishing() || isDestroyed()) return;
                    Bitmap safeNext = art != null && !art.isRecycled() ? art : null;
                    if (singlePassBackdrop) {
                        currentHeroArt = safeNext;
                        backdropArtVisible = safeNext != null;
                        renderSinglePassBackdrop();
                    } else {
                        heroArt.setImageBitmap(safeNext);
                        fadeHeroArt(150);
                    }
                });
            });
        };
        heroArtPending = task;
        long debounceMs = (SystemClock.uptimeMillis() - lastFocusMoveMs) < 160L ? 60L : 0L;
        if (debounceMs > 0L) handler.postDelayed(task, debounceMs);
        else task.run();
    }

    private void fadeHeroArt(int ms) {
        if (!animations) heroArt.setAlpha(heroArtAlpha);
        else heroArt.animate().alpha(heroArtAlpha).setDuration(reducedMotion ? Math.min(ms, Motion.REDUCED_MS) : ms).start();
    }

    /** "Last opened yesterday, 9:40 PM", the time in white; nothing (but the same height) when it was never seen. */
    private void updateHeroMeta(int position) {
        long when = LastOpened.get(this, adapter.packageAt(position));
        if (when <= 0) {
            if (heroMeta.getVisibility() != View.INVISIBLE) heroMeta.setVisibility(View.INVISIBLE);
            return;
        }
        String rel = LastOpened.describe(this, when);
        String text = getString(R.string.last_opened, rel);
        SpannableString line = new SpannableString(text);
        int at = text.indexOf(rel);
        if (at >= 0) line.setSpan(HERO_META_SPAN, at, at + rel.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        heroMeta.setText(line);
        if (heroMeta.getVisibility() != View.VISIBLE) heroMeta.setVisibility(View.VISIBLE);
    }

    /** One dot per dock app, the focused one drawn long and white. Drawn in a single Drawable with zero relayouts. */
    private void updateHeroDots(int position) {
        if (!heroDotsShown) return;
        int count = Math.min(adapter.dockFilled(), COLUMNS);
        int wantVis = count > 1 ? View.VISIBLE : View.INVISIBLE;
        if (heroDots.getVisibility() != wantVis) heroDots.setVisibility(wantVis);
        if (heroDotsDrawable == null) {
            heroDotsDrawable = new HeroDotsDrawable(density);
            heroDots.setBackground(heroDotsDrawable);
        }
        heroDotsDrawable.update(count, position, rtlAppGrid);
    }

    private void initSinglePassBackdrop() {
        backdropBuffers = new Bitmap[]{
                Bitmap.createBitmap(BACKDROP_W, BACKDROP_H, Bitmap.Config.ARGB_8888),
                Bitmap.createBitmap(BACKDROP_W, BACKDROP_H, Bitmap.Config.ARGB_8888)
        };
        backdropBuffers[0].setHasAlpha(false);
        backdropBuffers[1].setHasAlpha(false);
        backdropCanvases = new Canvas[]{
                new Canvas(backdropBuffers[0]),
                new Canvas(backdropBuffers[1])
        };
        backdropDrawable = new BackdropDrawable();
        root.setBackground(backdropDrawable);
        renderSinglePassBackdrop();
    }

    private void ensureAmbientMask() {
        if (ambientMaskBmp != null && !ambientMaskBmp.isRecycled()) return;
        int w = BACKDROP_W, h = BACKDROP_H;
        Bitmap mask = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8);
        Canvas canvas = new Canvas(mask);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        if (cinematic) {
            boolean rtl = getResources().getBoolean(R.bool.rtl_menu_bar);
            paint.setShader(new RadialGradient(
                    w * (rtl ? 0.20f : 0.80f), h * 0.06f, w * 0.78f,
                    new int[]{0xA6FFFFFF, 0x47FFFFFF, 0x00FFFFFF},
                    new float[]{0f, 0.5f, 1f},
                    Shader.TileMode.CLAMP));
        } else if (Config.GLOW_HORIZON.equals(glowStyle)) {
            paint.setShader(new LinearGradient(0, 0, 0, h * 0.75f,
                    new int[]{0x66FFFFFF, 0x24FFFFFF, 0x00FFFFFF},
                    new float[]{0f, 0.4f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);
            paint.setShader(new RadialGradient(
                    w * 0.5f, -h * 0.25f, w * 0.55f,
                    new int[]{0x40FFFFFF, 0x00FFFFFF},
                    null,
                    Shader.TileMode.CLAMP));
        } else {
            paint.setShader(new RadialGradient(
                    w * 0.32f, h * 0.42f, w * 0.62f,
                    new int[]{0x5CFFFFFF, 0x26FFFFFF, 0x00FFFFFF},
                    new float[]{0f, 0.45f, 1f},
                    Shader.TileMode.CLAMP));
        }
        canvas.drawRect(0, 0, w, h, paint);
        // 4x4 Bayer micro-dither on ALPHA_8 mask eliminates subtle 8-bit contour steps on dark ambient gradients.
        byte[] alphaBuf = new byte[w * h];
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(alphaBuf);
        mask.copyPixelsToBuffer(bb);
        final int[] bayer = {0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5};
        for (int y = 0; y < h; y++) {
            int row = y * w;
            int brow = (y & 3) << 2;
            for (int x = 0; x < w; x++) {
                int a = alphaBuf[row + x] & 0xFF;
                if (a > 0 && a < 255) {
                    int delta = ((bayer[brow | (x & 3)] - 7) >> 2);
                    int next = a + delta;
                    alphaBuf[row + x] = (byte) (next < 0 ? 0 : Math.min(255, next));
                }
            }
        }
        bb.rewind();
        mask.copyPixelsFromBuffer(bb);
        ambientMaskBmp = mask;
    }

    /**
     * Composites backgroundColor + optional HeroArt + optional ambient glow + optional scrim into ONE
     * opaque 240x135 texture (PixelFormat.OPAQUE, GL_BLEND disabled), cutting full-screen overdraw 3x.
     */
    private void renderSinglePassBackdrop() {
        if (!singlePassBackdrop || backdropBuffers == null || backdropDrawable == null) return;
        int next = backdropIndex ^ 1;
        Canvas c = backdropCanvases[next];
        c.drawColor(backdropBaseColor);
        if (cinematic && heroArtAlpha > 0f && backdropArtVisible
                && currentHeroArt != null && !currentHeroArt.isRecycled()) {
            backdropArtPaint.setAlpha(Math.round(heroArtAlpha * 255f));
            c.drawBitmap(currentHeroArt, null, BACKDROP_RECT, backdropArtPaint);
        }
        if (ambientGlow && backdropAmbientVisible && ambientColor != -1) {
            ensureAmbientMask();
            if (ambientMaskBmp != null && !ambientMaskBmp.isRecycled()) {
                int dr = Math.round(((ambientColor >> 16) & 0xFF) * 0.40f);
                int dg = Math.round(((ambientColor >> 8) & 0xFF) * 0.40f);
                int db = Math.round((ambientColor & 0xFF) * 0.40f);
                backdropAmbientPaint.setColor(0xFF000000 | (dr << 16) | (dg << 8) | db);
                c.drawBitmap(ambientMaskBmp, null, BACKDROP_RECT, backdropAmbientPaint);
            }
        }
        if (cinematic && scrimBmp != null && !scrimBmp.isRecycled() && scrimAlpha > 0) {
            backdropScrimPaint.setAlpha(scrimAlpha);
            c.drawBitmap(scrimBmp, null, BACKDROP_RECT, backdropScrimPaint);
        }
        backdropIndex = next;
        backdropDrawable.swap(backdropBuffers[next]);
    }

    private void releaseSinglePassBackdrop() {
        if (backdropDrawable != null) backdropDrawable.swap(null);
        if (backdropBuffers != null) {
            for (int i = 0; i < backdropBuffers.length; i++) {
                if (backdropBuffers[i] != null && !backdropBuffers[i].isRecycled()) {
                    backdropBuffers[i].recycle();
                }
                backdropBuffers[i] = null;
            }
            backdropBuffers = null;
            backdropCanvases = null;
        }
        if (ambientMaskBmp != null && !ambientMaskBmp.isRecycled()) {
            ambientMaskBmp.recycle();
            ambientMaskBmp = null;
        }
        if (scrimBmp != null && !scrimBmp.isRecycled()) {
            scrimBmp.recycle();
            scrimBmp = null;
        }
        currentHeroArt = null;
    }

    private static final class BackdropDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Rect dst = new Rect();
        private Bitmap current;

        void swap(Bitmap bmp) {
            current = bmp;
            invalidateSelf();
        }

        @Override
        protected void onBoundsChange(Rect bounds) {
            super.onBoundsChange(bounds);
            dst.set(bounds);
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            if (current != null && !current.isRecycled()) {
                canvas.drawBitmap(current, null, dst, paint);
            }
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.OPAQUE;
        }
    }

    private static final class HeroDotsDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF rect = new android.graphics.RectF();
        private final float density;
        private int count;
        private int active = -1;
        private boolean rtl;

        HeroDotsDrawable(float density) {
            this.density = density;
        }

        void update(int count, int active, boolean rtl) {
            if (this.count == count && this.active == active && this.rtl == rtl) return;
            this.count = count;
            this.active = active;
            this.rtl = rtl;
            invalidateSelf();
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            if (count <= 1) return;
            float h = 6f * density;
            float r = 3f * density;
            float gap = 6f * density;
            float activeW = 20f * density;
            float dotW = 6f * density;
            float totalW = (count - 1) * (dotW + gap) + activeW;
            Rect b = getBounds();
            float x = rtl ? (b.right - totalW) : b.left;
            for (int i = 0; i < count; i++) {
                int idx = rtl ? (count - 1 - i) : i;
                boolean on = idx == active;
                float w = on ? activeW : dotW;
                paint.setColor(on ? 0xFFFFFFFF : 0x4DFFFFFF);
                rect.set(x, 0f, x + w, h);
                canvas.drawRoundRect(rect, r, r, paint);
                x += w + gap;
            }
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    /**
     * One small 240x135 bilinear-filtered glow bitmap stretched over the screen and tinted via ColorFilter.
     * In single-pass mode, paints directly into the unified opaque backdrop without extra full-screen layers.
     */
    private void setAmbient(int color) {
        if (singlePassBackdrop) {
            if (color == ambientColor && backdropAmbientVisible) return;
            ambientColor = color;
            backdropAmbientVisible = true;
            QuickSettingsActivity.setLastAmbientColor(color);
            renderSinglePassBackdrop();
            return;
        }
        Drawable existing = ambient.getBackground();
        if (color == ambientColor && existing instanceof BitmapDrawable) return;
        ambientColor = color;
        QuickSettingsActivity.setLastAmbientColor(color);
        BitmapDrawable glow = existing instanceof BitmapDrawable ? (BitmapDrawable) existing : null;
        if (glow == null) {
            int w = BACKDROP_W;
            int h = BACKDROP_H;
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
            if (cinematic) {
                boolean rtl = getResources().getBoolean(R.bool.rtl_menu_bar);
                paint.setShader(new RadialGradient(
                        w * (rtl ? 0.20f : 0.80f), h * 0.06f, w * 0.78f,
                        new int[]{0xA6FFFFFF, 0x47FFFFFF, 0x00FFFFFF},
                        new float[]{0f, 0.5f, 1f},
                        Shader.TileMode.CLAMP));
            } else if (Config.GLOW_HORIZON.equals(glowStyle)) {
                paint.setShader(new LinearGradient(0, 0, 0, h * 0.75f,
                        new int[]{0x66FFFFFF, 0x24FFFFFF, 0x00FFFFFF},
                        new float[]{0f, 0.4f, 1f},
                        Shader.TileMode.CLAMP));
                canvas.drawRect(0, 0, w, h, paint);
                paint.setShader(new RadialGradient(
                        w * 0.5f, -h * 0.25f, w * 0.55f,
                        new int[]{0x40FFFFFF, 0x00FFFFFF},
                        null,
                        Shader.TileMode.CLAMP));
            } else {
                paint.setShader(new RadialGradient(
                        w * 0.32f, h * 0.42f, w * 0.62f,
                        new int[]{0x5CFFFFFF, 0x26FFFFFF, 0x00FFFFFF},
                        new float[]{0f, 0.45f, 1f},
                        Shader.TileMode.CLAMP));
            }
            canvas.drawRect(0, 0, w, h, paint);
            glow = new BitmapDrawable(getResources(), bmp);
            glow.setFilterBitmap(true);
            glow.setGravity(Gravity.FILL);
            ambient.setBackground(glow);
            lastAmbientTint = 0;
        }
        int dr = Math.round(((color >> 16) & 0xFF) * 0.40f);
        int dg = Math.round(((color >> 8) & 0xFF) * 0.40f);
        int db = Math.round((color & 0xFF) * 0.40f);
        int tinted = 0xFF000000 | (dr << 16) | (dg << 8) | db;
        if (tinted != lastAmbientTint) {
            lastAmbientTint = tinted;
            glow.setTint(tinted);
        }
    }

    static int lerpColor(int from, int to, float t) {
        int a1 = (from >>> 24) & 0xFF, r1 = (from >> 16) & 0xFF, g1 = (from >> 8) & 0xFF, b1 = from & 0xFF;
        int a2 = (to >>> 24) & 0xFF, r2 = (to >> 16) & 0xFF, g2 = (to >> 8) & 0xFF, b2 = to & 0xFF;
        int a = a1 + Math.round((a2 - a1) * t);
        int r = r1 + Math.round((r2 - r1) * t);
        int g = g1 + Math.round((g2 - g1) * t);
        int b = b1 + Math.round((b2 - b1) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private void transitionAmbient(int color) {
        stopAmbientAnimation();
        if (!singlePassBackdrop) ambient.animate().cancel();
        if (singlePassBackdrop) {
            // One 36 KB opaque texture swap on focus change (zero multi-frame glTexImage2D upload storm),
            // leaving 100% of GPU bandwidth for 60fps GlassView + tile spring motion.
            setAmbient(color);
            return;
        }
        if (ambientColor == -1 || ambient.getBackground() == null) {
            setAmbient(color);
            ambient.animate().alpha(1f).setDuration(180).start();
            return;
        }
        if (color == ambientColor) {
            if (ambient.getAlpha() < 1f) ambient.animate().alpha(1f).setDuration(140).start();
            return;
        }
        // During rapid D-pad repeat, snap the tint in 1 frame so full-screen repaints never pile up.
        if (glowSnap || (SystemClock.uptimeMillis() - lastFocusMoveMs) < 140L) {
            setAmbient(color);
            ambient.setAlpha(1f);
            return;
        }
        ambient.setAlpha(1f);
        final int startColor = ambientColor;
        ambientAnimator = ValueAnimator.ofFloat(0f, 1f);
        ambientAnimator.setDuration(150);
        ambientAnimator.addUpdateListener(animation ->
                setAmbient(lerpColor(startColor, color, (float) animation.getAnimatedValue())));
        ambientAnimator.start();
    }

    private void stopAmbientAnimation() {
        if (ambientAnimator != null) {
            ambientAnimator.cancel();
            ambientAnimator = null;
        }
    }

    /**
     * Grid tiles (every tile with dock_row false): the glow behind the grid takes the focused tile's colour; the big
     * name above the dock does not show (every grid tile has its name underneath).
     */
    private void showGlowOnly(int position) {
        int color = adapter.ambientColorAt(position);
        hero.animate().cancel();
        if (animations && !reducedMotion && heroShown) {
            hero.animate().alpha(0f).setDuration(160).start();
        } else {
            hero.setAlpha(0f);
        }
        heroShown = false;
        if (cinematic && heroArtAlpha > 0f) {
            showHeroArt(position);
        } else if (singlePassBackdrop) {
            if (backdropArtVisible) {
                backdropArtVisible = false;
                renderSinglePassBackdrop();
            }
        } else {
            heroArt.animate().cancel();
            heroArt.setAlpha(0f);
        }
        if (!ambientGlow) return;
        if (!animations || reducedMotion) {
            stopAmbientAnimation();
            if (!singlePassBackdrop) ambient.animate().cancel();
            setAmbient(color);
            if (!singlePassBackdrop) ambient.setAlpha(1f);
            return;
        }
        if (singlePassBackdrop) {
            if (advancedEffects) transitionAmbient(color);
            else setAmbient(color);
            return;
        }
        if (color == ambientColor && ambient.getBackground() != null) {
            if (ambient.getAlpha() < 1f) ambient.animate().alpha(1f).setDuration(300).start();
            return;
        }
        if (advancedEffects) {
            transitionAmbient(color);
        } else if (glowSnap) {
            stopAmbientAnimation();
            ambient.animate().cancel();
            setAmbient(color);
            ambient.setAlpha(1f);
        } else {
            stopAmbientAnimation();
            ambient.animate().cancel();
            ambient.animate().alpha(0.35f).setDuration(ambient.getAlpha() > 0.35f ? 200 : 0).withEndAction(() -> {
                setAmbient(color);
                ambient.animate().alpha(1f).setDuration(500).start();
            }).start();
        }
    }

    private void hideHero() {
        if (!heroShown) return;
        stopAmbientAnimation();
        hero.animate().cancel();
        if (!singlePassBackdrop) {
            ambient.animate().cancel();
            heroArt.animate().cancel();
        }
        heroShown = false;
        if (singlePassBackdrop) {
            backdropAmbientVisible = false;
            backdropArtVisible = false;
            renderSinglePassBackdrop();
        }
        if (animations && !reducedMotion) {
            hero.animate().alpha(0f).setDuration(160).start();
            if (!singlePassBackdrop) {
                if (ambientGlow) ambient.animate().alpha(0f).setDuration(300).start();
                heroArt.animate().alpha(0f).setDuration(300).start();
            }
        } else {
            hero.setAlpha(0f);
            if (!singlePassBackdrop) {
                ambient.setAlpha(0f);
                heroArt.setAlpha(0f);
            }
        }
    }

    private void hideGlass() {
        if (!glassShown) return;
        glassShown = false;
        glass.animate().cancel();
        if (animations && !reducedMotion) glass.animate().alpha(0f).setDuration(160).start();
        else glass.setAlpha(0f);
    }

    // ---- optional system stats (config.xml show_stats) --------------------------------------------

    /** Background reader. Started only while Home is resumed and show_stats is on. */
    private HandlerThread statsThread;
    /** The current run's handler; a run whose handler is no longer this one stops (volatile: read off-thread). */
    private volatile Handler statsBg;

    private void startStats() {
        if (statsThread != null) return;
        stats.setVisibility(View.VISIBLE);
        cpuTotalPrev = -1;
        idlePrev = -1;
        procStatDead = false;
        statsThread = new HandlerThread("stats");
        statsThread.start();
        // Each start gets its own handler, held by its task: stopStats on the main thread can clear the field
        // at any moment without the reader tripping over it.
        Handler bg = new Handler(statsThread.getLooper());
        statsBg = bg;
        bg.post(new Runnable() {
            @Override
            public void run() {
                if (statsBg != bg) return;
                String line = readStatsLine();
                handler.post(() -> {
                    if (showStats && stats != null && statsBg == bg) {
                        stats.setText(line);
                    }
                });
                if (statsBg == bg) bg.postDelayed(this, STATS_MS);
            }
        });
    }

    private void stopStats() {
        if (statsThread == null) return;
        statsBg.removeCallbacksAndMessages(null);
        statsThread.quit();
        statsThread = null;
        statsBg = null;
        // Paused (the quick panel or an app on top): keep the last line on screen. Taking it away moved the grid up
        // under a glass highlight that stayed put.
        if (!showStats) stats.setVisibility(View.GONE);
    }

    /** One quiet line, e.g. "CPU 8%  ·  Memory 430 MB free  ·  Storage 3.1 GB free". Runs off the main thread. */
    private static void appendStat(StringBuilder line, String part) {
        if (line.length() > 0) line.append("  ·  ");
        line.append(part);
    }

    private String readStatsLine() {
        int cpu = cpuPercent();
        if (cpu < 0) {
            try {
                Thread.sleep(200);             // second sample, so the first line already has a CPU figure
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "";
            }
            cpu = cpuPercent();
        }
        // Android 8 and later hide /proc/stat from apps on many TVs: then the line leaves CPU out rather than
        // showing a dash that never fills in.
        StringBuilder line = new StringBuilder();
        if (cpu >= 0) line.append(getString(R.string.stats_cpu, cpu));
        long[] mem = memUsedOfTotalMb();
        if (mem != null) appendStat(line, getString(R.string.stats_memory, mem[0], mem[1]));
        String free = formatGb(getResources(), Environment.getDataDirectory().getFreeSpace());
        if (!free.isEmpty()) appendStat(line, getString(R.string.stats_storage, free));
        return StatsText.forLocale(line.toString(), getResources().getConfiguration().getLocales().get(0));
    }

    // -1: need another sample. -2: this source cannot be read (Android 9 hides /proc/stat from apps).
    private static final int CPU_UNREADABLE = -2;
    private long cpuTotalPrev = -1, cpuIdlePrev = -1;
    private boolean procStatDead;
    private long idlePrev = -1, wallPrev = -1;

    private int cpuPercent() {
        if (!procStatDead) {
            int fromStat = cpuFromProcStat();
            if (fromStat != CPU_UNREADABLE) return fromStat;
            procStatDead = true;
            idlePrev = -1;
        }
        return cpuFromIdle();
    }

    private int cpuFromProcStat() {
        String s = readFirstLine("/proc/stat");
        if (s == null || !s.startsWith("cpu")) return CPU_UNREADABLE;
        int len = s.length(), idx = 3, count = 0;
        long total = 0, idleAll = 0;
        while (idx < len && count < 7) {
            while (idx < len && s.charAt(idx) <= ' ') idx++;
            if (idx >= len) break;
            long val = 0;
            boolean anyDigit = false;
            while (idx < len) {
                char ch = s.charAt(idx);
                if (ch < '0' || ch > '9') break;
                val = val * 10L + (ch - '0');
                anyDigit = true;
                idx++;
            }
            if (!anyDigit) return CPU_UNREADABLE;
            total += val;
            if (count == 3 || count == 4) idleAll += val;
            count++;
        }
        if (count < 4) return CPU_UNREADABLE;
        if (cpuTotalPrev < 0) {
            cpuTotalPrev = total;
            cpuIdlePrev = idleAll;
            return -1;
        }
        long dt = total - cpuTotalPrev, di = idleAll - cpuIdlePrev;
        cpuTotalPrev = total;
        cpuIdlePrev = idleAll;
        if (dt <= 0) return -1;
        int busy = (int) Math.round((dt - di) * 100.0 / dt);
        return Math.max(0, Math.min(100, busy));
    }

    /** Busy percent from cpuidle time (microseconds) across the cores. -1 until the second sample. */
    private int cpuFromIdle() {
        long idle = 0;
        int cores = 0;
        for (int i = 0; i < 8; i++) {
            boolean any = false;
            for (int state = 0; state < 8; state++) {
                String t = readFirstLine("/sys/devices/system/cpu/cpu" + i
                        + "/cpuidle/state" + state + "/time");
                if (t == null) break;
                try {
                    idle += Long.parseLong(t.trim());
                    any = true;
                } catch (NumberFormatException e) {
                    break;
                }
            }
            if (!any) break;
            cores++;
        }
        if (cores == 0) return -1;
        long wall = SystemClock.elapsedRealtime() * 1000L;
        if (idlePrev < 0) {
            idlePrev = idle;
            wallPrev = wall;
            return -1;
        }
        long di = idle - idlePrev;
        long dw = (wall - wallPrev) * cores;
        idlePrev = idle;
        wallPrev = wall;
        if (dw <= 0) return -1;
        int busy = (int) Math.round((dw - di) * 100.0 / dw);
        return Math.max(0, Math.min(100, busy));
    }

    /** RAM usage as {used, total} in MB (MemInfo.usedOfTotalMb); null when /proc/meminfo cannot be read. */
    private long[] memUsedOfTotalMb() {
        java.util.List<String> lines = new java.util.ArrayList<>();
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader("/proc/meminfo"))) {
            for (String line; (line = r.readLine()) != null;) lines.add(line);
        } catch (IOException ignored) {
            return null;
        }
        return MemInfo.usedOfTotalMb(lines);
    }

    private static String readFirstLine(String path) {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(path))) {
            return r.readLine();
        } catch (IOException e) {
            return null;
        }
    }

    private static String formatGb(Resources res, long bytes) {
        if (bytes <= 0) return "";
        double gb = bytes / (1024.0 * 1024 * 1024);
        return gb >= 10 ? res.getString(R.string.storage_gb_whole, gb)
                : res.getString(R.string.storage_gb_decimal, gb);
    }

    // ---- navigation ------------------------------------------------------------------------------

    @Override
    public boolean onTileVerticalKey(int position, int keyCode) {
        int count = adapter.appCount();
        int target;
        if (singleRow) {
            // One row: Up leaves for the top bar, Down has nowhere to go, and Left/Right walk the whole row,
            // stopping at its two ends (the default focus search scrolls the row to the next tile).
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) return true;
            boolean forward = rtlAppGrid ? keyCode == KeyEvent.KEYCODE_DPAD_LEFT : keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
            int last = adapter.focusablePosition(count - 1);
            if (forward && position >= last) {
                if (adapter.isLibraryAt(count)) focusLibrary();
                return true;
            }
            return !forward && position == 0;
        }
        // Horizontal: swallow the key at a row's ends so focus never wraps to the next row or
        // escapes up into the top bar. Mid-row moves fall through to the default focus search.
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            boolean forward = rtlAppGrid ? keyCode == KeyEvent.KEYCODE_DPAD_LEFT : keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
            if (forward && position == adapter.focusablePosition(count - 1) && adapter.isLibraryAt(count)) {
                focusLibrary();
                return true;
            }
            // In the dock, the last app is the row's end: the empty slots after it take no focus.
            int rowCount = position < COLUMNS ? Math.min(count, adapter.dockFilled()) : count;
            return GridNavigation.atHorizontalEdge(position, rowCount, COLUMNS,
                    rtlAppGrid, keyCode == KeyEvent.KEYCODE_DPAD_LEFT);
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (position < COLUMNS) return false;          // dock row: let focus move to the top bar
            target = position - COLUMNS;
        } else {
            int lastRowStart = ((count - 1) / COLUMNS) * COLUMNS;
            if (position >= lastRowStart) {
                if (adapter.isLibraryAt(count)) focusLibrary();
                return true;
            }
            target = Math.min(position + COLUMNS, count - 1);
        }
        focusTile(target);
        // The default focus search clicks for Left/Right; Up/Down are handled here, so click for them too.
        grid.playSoundEffect(keyCode == KeyEvent.KEYCODE_DPAD_UP
                ? SoundEffectConstants.NAVIGATION_UP : SoundEffectConstants.NAVIGATION_DOWN);
        return true;
    }

    @Override public void onLibraryFocused() {
        libraryFocusPending = false;
        hideGlass();
        hideHero();
    }

    /**
     * date_below_clock: the date column moves to its own line under the clock, in the vertical LinearLayout that holds
     * the clock's row, and the line between them goes. In Hebrew (rtl_menu_bar) the date and the clock's row sit right.
     */
    private void moveDateBelowClock() {
        View column = (View) findViewById(R.id.date).getParent();
        View clockRow = (View) findViewById(R.id.clock).getParent();
        LinearLayout clockColumn = (LinearLayout) clockRow.getParent();
        ((ViewGroup) column.getParent()).removeView(column);
        findViewById(R.id.clockDivider).setVisibility(View.GONE);
        // Its own width, not the column's: in Hebrew the column is only as wide as the clock.
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round(4 * density);
        if (getResources().getBoolean(R.bool.rtl_menu_bar)) {
            // Date and clock flush right; the greeting stays left and follows the clock by translation.
            lp.gravity = Gravity.RIGHT;
            ((LinearLayout.LayoutParams) clockRow.getLayoutParams()).gravity = Gravity.RIGHT;
        }
        clockColumn.addView(column, clockColumn.indexOfChild(clockRow) + 1, lp);
        // The column now wraps its texts, so they must wrap too: a match_parent TextView does not ask for a new
        // layout when its text gets longer, and the column kept the width of the day before ("TUESDAY 27 OCTOB…").
        ViewGroup texts = (ViewGroup) column;
        for (int i = 0; i < texts.getChildCount(); i++) {
            View text = texts.getChildAt(i);
            LinearLayout.LayoutParams textLp = (LinearLayout.LayoutParams) text.getLayoutParams();
            textLp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            // Keep each line where its own gravity put it (right in Hebrew) now that it no longer fills the column.
            textLp.gravity = ((TextView) text).getGravity() & Gravity.HORIZONTAL_GRAVITY_MASK;
            text.setLayoutParams(textLp);
        }
    }

    /** Centre the one-row header when the greeting and extra date lines are absent (#2265). */
    private void centerCompactHeader() {
        LinearLayout clockRow = (LinearLayout) findViewById(R.id.clock).getParent();
        clockRow.setGravity(Gravity.CENTER_VERTICAL);
        clockRow.setBaselineAligned(false);
        View[] columns = {findViewById(R.id.clockDivider), (View) findViewById(R.id.date).getParent()};
        for (View column : columns) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) column.getLayoutParams();
            lp.gravity = Gravity.CENTER_VERTICAL;
            lp.bottomMargin = 0;
            column.setLayoutParams(lp);
        }
        LinearLayout buttons = findViewById(R.id.topButtons);
        RelativeLayout.LayoutParams lp = (RelativeLayout.LayoutParams) buttons.getLayoutParams();
        lp.addRule(RelativeLayout.ALIGN_TOP, R.id.leftBlock);
        lp.bottomMargin = 0;
        buttons.setLayoutParams(lp);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
    }

    /** With the date below, keep the icons beside the clock rather than beside the last date line. */
    private void alignDateBelowIconsWithClock() {
        View clock = findViewById(R.id.clock);
        View clockRow = (View) clock.getParent();
        LinearLayout buttons = findViewById(R.id.topButtons);
        RelativeLayout.LayoutParams lp = (RelativeLayout.LayoutParams) buttons.getLayoutParams();
        lp.addRule(RelativeLayout.ALIGN_TOP, R.id.leftBlock);
        lp.removeRule(RelativeLayout.ALIGN_BOTTOM);
        lp.topMargin = 0;
        lp.bottomMargin = 0;
        buttons.setLayoutParams(lp);
        // Either side can be laid out first; update after both without requesting another layout mid-pass.
        Runnable align = () -> buttons.setTranslationY(clockRow.getTop() + clock.getTop()
                + (clock.getHeight() - buttons.getHeight()) / 2f);
        clockRow.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> align.run());
        buttons.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> align.run());
    }

    /** Leftmost (in LTR) / first visible button in topButtons, including Now Playing and Search. */
    private View firstVisibleTopButton() {
        ViewGroup bar = findViewById(R.id.topButtons);
        boolean rtl = getResources().getBoolean(R.bool.rtl_menu_bar);
        int n = bar.getChildCount();
        for (int k = 0; k < n; k++) {
            View button = bar.getChildAt(rtl ? n - 1 - k : k);
            if (button.getVisibility() == View.VISIBLE) return button;
        }
        return findViewById(R.id.btnSettings);
    }

    private void updateNowPlayingPill(MediaNowPlaying.State state) {
        if (btnNowPlaying == null) return;
        if (!showNowPlayingHome || state == null) {
            btnNowPlaying.setVisibility(View.GONE);
            return;
        }
        String prefix = state.playing ? "\u25B6 " : "\u23F8 ";
        btnNowPlaying.setText(prefix + state.displayLine());
        btnNowPlaying.setVisibility(View.VISIBLE);
    }

    private void showNowPlayingMenu(View anchor) {
        if (mediaNowPlaying == null || openPopup != null) return;
        MediaNowPlaying.State st = mediaNowPlaying.state();
        if (st == null) return;
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow popup = HomeMenus.glassPopup(this, panel, (int) (220 * density));
        HomeMenus.addSettingsMenuRow(getLayoutInflater(), rows, popup, R.string.media_play_pause,
                () -> mediaNowPlaying.togglePlayPause());
        HomeMenus.addSettingsMenuRow(getLayoutInflater(), rows, popup, R.string.media_next,
                () -> mediaNowPlaying.next());
        HomeMenus.addSettingsMenuRow(getLayoutInflater(), rows, popup, R.string.media_prev,
                () -> mediaNowPlaying.previous());
        Intent openIntent = mediaNowPlaying.openAppIntent();
        if (openIntent != null) {
            HomeMenus.addSettingsMenuRow(getLayoutInflater(), rows, popup, R.string.media_open_app,
                    () -> open(openIntent));
        }
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setWidth(Math.max((int) (210 * density), panel.getMeasuredWidth()));
        HomeMenus.dropIn(popup, panel, anchor,
                getResources().getBoolean(R.bool.rtl_menu_bar) ? Gravity.START : Gravity.END,
                density, animations, reducedMotion);
        rows.getChildAt(0).requestFocus();
    }

    /** The first top-bar button after Inputs that is shown, in bar order (top_bar_apps, the network pill, ...); Settings is always there. */
    private View firstTopButton() {
        ViewGroup bar = findViewById(R.id.topButtons);
        // The Hebrew layout (layout-iw) lists the bar mirrored, Settings first and Inputs last, so bar order
        // runs from the end there. Walking it forwards returned Settings, and the pill key guard then treated
        // Settings as both ends of the bar: Right from Settings was swallowed and never reached Wi-Fi.
        boolean rtl = getResources().getBoolean(R.bool.rtl_menu_bar);
        int n = bar.getChildCount();
        for (int k = 0; k < n; k++) {
            View button = bar.getChildAt(rtl ? n - 1 - k : k);
            if (button != btnInputs && button.getVisibility() == View.VISIBLE) return button;
        }
        return findViewById(R.id.btnSettings);
    }

    /** top_bar_apps buttons, in bar order. */
    private final List<View> topAppButtons = new ArrayList<>();

    /**
     * Puts each top_bar_apps app in the top bar as a round pill with the app's own logo, left of the network pill.
     * The logo keeps its colours: the pill style's tint, which turns the other icons white, is cleared.
     */
    private void addTopBarApps(List<String> packages, Map<String, String> launchActions) {
        ViewGroup bar = findViewById(R.id.topButtons);
        PackageManager pm = getPackageManager();
        int size = Math.round(22 * density);
        for (String pkg : packages) {
            Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (launch == null) launch = pm.getLaunchIntentForPackage(pkg);
            if (launch == null) continue;   // not installed, or disabled
            // Use the same configured launch_actions as tiles; retain the normal entry if unavailable.
            launch = LaunchActions.select(pkg, launchActions, launch, (targetPackage, action) -> {
                Intent custom = LaunchActions.intent(targetPackage, action);
                return pm.resolveActivity(custom, 0) != null ? custom : null;
            });
            Drawable logo;
            CharSequence label;
            try {
                ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
                logo = info.loadIcon(pm);
                label = info.loadLabel(pm);
            } catch (PackageManager.NameNotFoundException e) {
                continue;
            }
            TextView button = (TextView) getLayoutInflater().inflate(R.layout.top_app_button, bar, false);
            // pillKeys finds the bar's ends by id: without one of its own, every app button would be an end.
            button.setId(View.generateViewId());
            logo.setBounds(0, 0, size, size);
            button.setCompoundDrawablesRelative(logo, null, null, null);
            button.setCompoundDrawableTintList(null);
            button.setContentDescription(label);
            Intent target = launch;
            // An input: action switches the TV input, as the tile for the same app does.
            String inputId = LaunchActions.inputId(launchActions.get(pkg));
            if (inputId != null) button.setOnClickListener(v -> TvInputRouter.openInput(this, inputId));
            else button.setOnClickListener(v -> open(new Intent(target)));
            // After the network pill and the VPN pill; the Hebrew bar runs the other way, so before both there.
            bar.addView(button, bar.indexOfChild(getResources().getBoolean(R.bool.rtl_menu_bar) ? btnVpn : btnWifi));
            topAppButtons.add(button);
        }
    }

    @Override public void onLibraryUp() {
        if (adapter.appCount() == 0) { firstTopButton().requestFocus(); return; }
        int lastRow = ((adapter.appCount() - 1) / COLUMNS) * COLUMNS;
        focusTile(Math.min(lastRow + lastFocused % COLUMNS, adapter.appCount() - 1));
    }

    private void focusLibrary() {
        cancelPendingFocusScroll();
        int position = adapter.appCount();
        if (!adapter.isLibraryAt(position)) return;
        RecyclerView.ViewHolder holder = grid.findViewHolderForAdapterPosition(position);
        if (holder != null) {
            View button = holder.itemView.findViewById(R.id.btnLibrary);
            Rect visible = new Rect();
            if (button.getGlobalVisibleRect(visible) && visible.height() >= button.getHeight()) {
                if (button.requestFocus()) libraryFocusPending = false;
                return;
            }
        }
        if (!animations) {
            grid.scrollToPosition(position);
            grid.post(() -> {
                if (appLibrary != null || !hasWindowFocus()) return;
                RecyclerView.ViewHolder footer = grid.findViewHolderForAdapterPosition(adapter.appCount());
                if (footer != null && adapter.isLibraryAt(adapter.appCount())
                        && footer.itemView.findViewById(R.id.btnLibrary).requestFocus()) libraryFocusPending = false;
            });
            return;
        }
        pendingFocusScroll = new RecyclerView.OnScrollListener() {
            @Override public void onScrollStateChanged(@NonNull RecyclerView rv, int state) {
                if (state != RecyclerView.SCROLL_STATE_IDLE) return;
                rv.removeOnScrollListener(this);
                if (pendingFocusScroll != this) return;
                pendingFocusScroll = null;
                RecyclerView.ViewHolder footer = rv.findViewHolderForAdapterPosition(position);
                if (footer != null && footer.itemView.findViewById(R.id.btnLibrary).requestFocus())
                    libraryFocusPending = false;
            }
        };
        grid.addOnScrollListener(pendingFocusScroll);
        grid.smoothScrollToPosition(position);
    }

    private void cancelPendingFocusScroll() {
        if (pendingFocusScroll != null) {
            grid.removeOnScrollListener(pendingFocusScroll);
            pendingFocusScroll = null;
            grid.stopScroll();
        }
    }

    private void focusTile(int position) {
        libraryFocusPending = false;
        cancelPendingFocusScroll();
        if (adapter.appCount() == 0) {
            if (adapter.isLibraryAt(0)) focusLibrary();
            else firstTopButton().requestFocus();
            return;
        }
        final int pos = adapter.focusablePosition(Math.max(0, Math.min(position, adapter.appCount() - 1)));
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
        if (vh != null) {
            View card = vh.itemView.findViewById(R.id.card);
            // Already focused (e.g. back from an app): no focus change fires, so restore the glass by hand.
            if (card.isFocused()) onTileFocused(pos, card);
            else card.requestFocus();
            return;
        }
        // Not laid out yet: scroll there, then focus once the scroll settles.
        if (!animations) {
            grid.scrollToPosition(pos);
            grid.post(() -> {
                RecyclerView.ViewHolder h = grid.findViewHolderForAdapterPosition(pos);
                if (h != null) h.itemView.findViewById(R.id.card).requestFocus();
            });
            return;
        }
        pendingFocusScroll = new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return;
                rv.removeOnScrollListener(this);
                if (pendingFocusScroll != this) return;
                pendingFocusScroll = null;
                RecyclerView.ViewHolder h = rv.findViewHolderForAdapterPosition(pos);
                View target = h != null ? h.itemView.findViewById(R.id.card) : rv.getChildAt(0);
                if (target != null) target.requestFocus();
            }
        };
        grid.addOnScrollListener(pendingFocusScroll);
        grid.smoothScrollToPosition(pos);
    }

    // ---- top bar ---------------------------------------------------------------------------------

    /** Some TVs (Coocaa OS) set the network time without ACTION_TIME_CHANGED, so keep checking until it is valid. */
    private final Runnable clockCheck = this::updateClockVisibility;

    private void updateClockVisibility() {
        boolean valid = System.currentTimeMillis() >= TIME_VALID_AFTER_MS;
        clockBlock.setVisibility(valid ? View.VISIBLE : View.INVISIBLE);
        handler.removeCallbacks(clockCheck);
        if (valid) {
            updateGreeting();
        } else {
            handler.postDelayed(clockCheck, 1_000L);
        }
    }

    private void updateGreeting() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int res;
        // Locale-specific day parts (app language on Android 13+).
        // Indonesian: Pagi 00-10, Siang 10-15, Sore 15-18, Malam 18-24.
        // Spain (es-ES) and Catalan: "Buenas tardes" into the evening; "Buenas noches" from 21.
        // Latin American Spanish (es-MX, es-AR, …) keeps the default hours: evening strings are also
        // «Buenas noches», and there «Buenas noches» from ~17 matches local use better than Spain's 21.
        Locale locale = getResources().getConfiguration().getLocales().get(0);
        String lang = locale.getLanguage();
        String country = locale.getCountry();
        if ("in".equals(lang) || "id".equals(lang)) {
            if (hour < 10) res = R.string.good_morning;
            else if (hour < 15) res = R.string.good_afternoon;
            else if (hour < 18) res = R.string.good_evening;
            else res = R.string.good_night;
        } else if (("es".equals(lang) && "ES".equals(country)) || "ca".equals(lang)) {
            if (hour < 6) res = R.string.good_night;
            else if (hour < 12) res = R.string.good_morning;
            else if (hour < 21) res = R.string.good_afternoon;
            else res = R.string.good_night;
        } else if ("da".equals(lang)) {
            // Danish day parts: "Godmorgen" until 11, then "God formiddag" to noon.
            if (hour < 5) res = R.string.good_night;
            else if (hour < 11) res = R.string.good_morning;
            else if (hour < 12) res = R.string.good_forenoon;
            else if (hour < 18) res = R.string.good_afternoon;
            else if (hour < 22) res = R.string.good_evening;
            else res = R.string.good_night;
        } else {
            if (hour < 5) res = R.string.good_night;
            else if (hour < 12) res = R.string.good_morning;
            else if (hour < 17) res = R.string.good_afternoon;
            else if (hour < 22) res = R.string.good_evening;
            else res = R.string.good_night;
        }
        String festiveGreeting = festive ? festival.greetingText(getResources()) : "";
        if (!festiveGreeting.isEmpty()) {
            greeting.setText(festiveGreeting);
            greeting.setTextColor(liftTowardsWhite(festival.accentColor(), 0.25f));
        } else {
            greeting.setText(greetingName.isEmpty() ? getString(res)
                    : getString(R.string.greeting_with_name, getString(res), greetingName));
            greeting.setTextColor(greetingColor);
        }
        if (shamsi) shamsiDate.setText(Shamsi.today(getResources()));
        if (lunar) lunarDate.setText(Lunar.today(getResources(), lunarOffset));
    }

    // ---- race-weekend panel (a theme's race_calendar) -----------------------------------------------

    private static final int RACE_RED = 0xFFFF3B2F;

    /**
     * Shows or hides the race-weekend panel for this minute. Text only, set once a minute with the clock:
     * nothing animates, so the panel costs the GPU nothing between ticks.
     */
    /**
     * The race panel's place under the clock. With game_day off it is exactly the F1 panel (updateRace). With it on,
     * each team's next game takes a turn there, and the theme's F1 weekend is one more turn drawn as today.
     */
    private void updatePanel() {
        if (gameDay == null) {
            updateRace();
            return;
        }
        handler.removeCallbacks(turnTick);
        long now = System.currentTimeMillis();
        List<GameDay.Turn> games = gameDay.turns();
        for (GameDay.Turn g : games) {
            if (g.endMs <= now && now - lastEndedRead > MINUTE_MS) {
                // A game is over: the add-on already has that team's next one.
                lastEndedRead = now;
                gameDay.read();
                break;
            }
        }
        RaceWeekend.State f1 = raceCalendar.isEmpty() ? null : RaceWeekend.at(raceCalendar, java.time.Instant.ofEpochMilli(now));
        List<GameDay.Turn> turns = GameDay.merge(games, f1, now);
        if (turns.isEmpty()) {
            racePanel.setVisibility(View.GONE);
            return;
        }
        int seconds = gameDay.turnSeconds();
        long since = SystemClock.elapsedRealtime() - rotationStart;
        int on = GameDay.current(turns.size(), since, seconds);
        GameDay.Turn t = turns.get(on);
        if (t.f1) {
            raceBar.setBackgroundResource(R.drawable.race_bar);
            raceSessions.setVisibility(View.VISIBLE);
            updateRace();
        } else {
            GradientDrawable bar = new GradientDrawable();
            bar.setCornerRadius(1.5f * density);
            bar.setColor(t.colour);
            raceBar.setBackground(bar);
            raceKicker.setText(t.topLine);
            if (GameDay.live(t, now)) {
                SpannableString live = new SpannableString("\u25CF  " + getString(R.string.game_live));
                live.setSpan(new ForegroundColorSpan(RACE_RED), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                raceHeadline.setText(live);
            } else {
                String in = RaceWeekend.countdown(getResources(), GameDay.until(t, now));
                raceHeadline.setText(t.word.isEmpty() ? in : getString(R.string.race_next, t.word, in));
            }
            // No session times for a game: the line takes no room, so the dots sit under the countdown.
            raceSessions.setVisibility(View.GONE);
            racePanel.setVisibility(View.VISIBLE);
        }
        showPager(turns.size(), on);
        if (turns.size() > 1 && !stopped) handler.postDelayed(turnTick, GameDay.delayToNextTurn(since, seconds));
    }

    /** One dot per turn, the one on screen bright; none for a single turn. */
    private void showPager(int count, int on) {
        if (count < 2) {
            racePager.setVisibility(View.GONE);
            return;
        }
        if (racePager.getChildCount() != count) {
            racePager.removeAllViews();
            int size = Math.round(5 * density);
            for (int k = 0; k < count; k++) {
                View dot = new View(this);
                GradientDrawable d = new GradientDrawable();
                d.setShape(GradientDrawable.OVAL);
                d.setColor(0xFFFFFFFF);
                dot.setBackground(d);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
                lp.setMarginEnd(size);
                racePager.addView(dot, lp);
            }
        }
        for (int k = 0; k < count; k++) racePager.getChildAt(k).setAlpha(k == on ? 0.9f : 0.3f);
        racePager.setVisibility(View.VISIBLE);
    }

    private void updateRace() {        // an empty calendar (a theme without race_calendar, or a reset) hides a panel the last theme showed
        RaceWeekend.State s = raceCalendar.isEmpty() ? null : RaceWeekend.at(raceCalendar, java.time.Instant.now());
        if (s == null) {
            racePanel.setVisibility(View.GONE);
            return;
        }
        RaceWeekend.Race race = s.race;
        raceKicker.setText(race.place.isEmpty()
                ? getString(R.string.race_kicker_no_place, race.round, race.shortName())
                : getString(R.string.race_kicker, race.round, race.shortName(), race.place));

        if (s.live != null) {
            SpannableString live = new SpannableString("\u25CF  " + getString(R.string.race_live, s.live.title(getResources())));
            live.setSpan(new android.text.style.ForegroundColorSpan(RACE_RED), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            raceHeadline.setText(live);
        } else if (s.next != null) {
            String in = RaceWeekend.countdown(getResources(), s.untilNext);
            raceHeadline.setText(s.next.isRace() ? getString(R.string.race_lights_out, in)
                    : getString(R.string.race_next, s.next.title(getResources()), in));
        } else {
            raceHeadline.setText(R.string.race_finished);
        }

        // FP1 FRI 11:00 PM   FP2 SAT 2:30 AM ...: done sessions fade, the live one is red, the next one bright.
        java.time.format.DateTimeFormatter when = java.time.format.DateTimeFormatter.ofPattern(
                ClockFormat.is24(this, clockFormat) ? "EEE HH:mm" : "EEE h:mm a", Locale.getDefault())
                .withZone(java.time.ZoneId.systemDefault());
        android.text.SpannableStringBuilder row = new android.text.SpannableStringBuilder();
        for (int i = 0; i < race.sessions.size(); i++) {
            RaceWeekend.Session session = race.sessions.get(i);
            if (i > 0) row.append("     ");
            int start = row.length();
            row.append(session.shortName(getResources())).append("  ").append(when.format(session.start).toUpperCase(Locale.getDefault()));
            int color;
            switch (s.statuses.get(i)) {
                case DONE: color = 0x47FFFFFF; break;
                case LIVE: color = RACE_RED; break;
                case NEXT: color = 0xFFFFFFFF; break;
                default: color = 0x99FFFFFF;
            }
            row.setSpan(new android.text.style.ForegroundColorSpan(color), start, row.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        raceSessions.setText(row);
        racePanel.setVisibility(View.VISIBLE);
    }

    private void refreshPillsAfterNextDraw() {
        root.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                root.getViewTreeObserver().removeOnPreDrawListener(this);
                // Posted from inside the frame's traversal, so it runs after that frame is drawn. A post straight
                // from onResume would run first: the frame is only scheduled after onResume returns.
                handler.post(() -> {
                    if (isDestroyed()) return;
                    updateWifiLabel();
                    TvInputRouter.updateInputsButton(MainActivity.this);
                });
                return true;
            }
        });
        root.invalidate();   // a resume that changes nothing on screen still draws once, so the refresh runs
    }

    /** Shows the Wi-Fi network name on the button when we can read it, otherwise the connection type. */
    @SuppressWarnings("deprecation")
    private void updateWifiLabel() {
        String text = getString(R.string.wifi);
        boolean vpn = false, online = false, validated = false;
        int icon = R.drawable.ic_wifi;
        // The festive light (diya flame or jack-o'-lantern candle): 0 is out (offline), 1 to 4 follow the Wi-Fi
        // signal, Ethernet burns steady at 4.
        int flame = 0;
        boolean steady = false;
        try {
            ConnectivityManager cm = getApplicationContext().getSystemService(ConnectivityManager.class);
            boolean wifi = false, ethernet = false;
            // All networks, not just the active one: with a VPN up the active network is the VPN itself.
            for (Network net : cm != null ? cm.getAllNetworks() : new Network[0]) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                if (caps == null) continue;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    vpn = true;
                    continue;
                }
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
                online = true;
                // Android reached the internet through it; a captive portal stays unvalidated until login.
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) validated = true;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) ethernet = true;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifi = true;
            }
            if (!online) {
                text = getString(R.string.offline);
                vpn = false;
            } else if (ethernet) {
                text = getString(R.string.ethernet);
                icon = R.drawable.ic_ethernet;
                flame = 4;
                steady = true;
            } else if (wifi) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
                String ssid = info != null ? info.getSSID() : null;
                flame = info != null ? Math.max(1, Math.min(4, WifiManager.calculateSignalLevel(info.getRssi(), 5))) : 3;
                if (ssid != null) {
                    ssid = ssid.replace("\"", "").trim();
                    if (!ssid.isEmpty() && !ssid.contains("unknown") && !ssid.equals("0x")) text = ssid;
                }
            }
        } catch (Exception ignored) {
        }
        // With a VPN app configured, a VPN that is down matters too: show a red "VPN off" capsule then.
        boolean vpnDown = !vpn && online && !vpnApp.isEmpty();
        if (vpnPill) updateVpnPill(vpn);
        if ((vpn || vpnDown) && !vpnPill) {
            // Network name, then the green lock-and-VPN (or red "VPN off") capsule drawn in place of the word.
            String badge = getString(vpn ? R.string.vpn : R.string.vpn_off);
            VpnBadge span = new VpnBadge(vpn ? getDrawable(R.drawable.ic_lock) : null, density);
            // The pill has a maximum width and ellipsizes at the end, which would cut the badge off after
            // a long network name, so shorten the name to whatever room the badge leaves. The Wi-Fi icon and its
            // gap are counted by hand: until the pill has a resolved layout direction (the first call, from
            // onResume, runs before that) getTotalPaddingStart/End leave the compound drawables out, which
            // left the name about 31dp too much room and the badge cut off.
            TextPaint paint = btnWifi.getPaint();
            Drawable[] compound = btnWifi.getCompoundDrawablesRelative();
            int iconRoom = compound[0] != null ? compound[0].getIntrinsicWidth() + btnWifi.getCompoundDrawablePadding() : 0;
            float room = btnWifi.getMaxWidth() - btnWifi.getPaddingStart() - btnWifi.getPaddingEnd() - iconRoom
                    - span.getSize(paint, badge, 0, badge.length(), null) - paint.measureText(" ") - 2 * density;
            String name = TextUtils.ellipsize(text, paint, Math.max(0, room), TextUtils.TruncateAt.END).toString();
            // A long badge ("VPN отключён") can leave room for a stub like "MT…", which says nothing and, after
            // rounding, can push the badge itself out of the pill. Show the badge alone then. On Ethernet the
            // icon already names the link, and "Ethernet" cut to "Ethe…" or not depended on the font size (#961),
            // so Ethernet always shows the icon and the badge alone.
            // The space stays even then: a line holding only the span gets no font metrics and collapses.
            boolean stub = name.length() < text.length() && name.length() < 5;
            text = stub || icon == R.drawable.ic_ethernet ? "" : name;
            SpannableString label = new SpannableString(text + " " + badge);
            label.setSpan(span, text.length() + 1, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (!TextUtils.equals(btnWifi.getText(), label)) btnWifi.setText(label);
        } else if (!TextUtils.equals(btnWifi.getText(), text)) {
            btnWifi.setText(text);
        }
        if (wifiIconOnly) {
            btnWifi.setContentDescription(btnWifi.getText());
            btnWifi.setText(null);
        }
        if (festive && lamp != null) {
            if (online && flame == 0) flame = 3;   // online some other way (cellular, USB tethering)
            lamp.setSteady(steady);
            lamp.setFlame(flame);
        } else {
            if (icon != wifiIcon) {
                wifiIcon = icon;
                btnWifi.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
            }
            ColorStateList tint = online ? wifiOnlineTint : wifiPlainTint;
            // wifi_status_warnings: orange while connected but not validated (a captive portal), red while offline.
            if (wifiNoInternetTint != null) tint = !online ? wifiOfflineTint : validated ? wifiOnlineTint : wifiNoInternetTint;
            if (wifiOnlineTint != null && btnWifi.getCompoundDrawableTintList() != tint) {
                btnWifi.setCompoundDrawableTintList(tint);
            }
        }
    }

    /** config.xml icon_only_buttons. */
    private boolean iconOnlyButtons;
    /** config.xml top_menu: Inputs, Wi-Fi and Settings folded into one gear. */
    boolean topMenu;
    /** show_inputs_pill as configured: top_menu clears showInputsPill, so its Inputs row reads this. */
    boolean showInputsMenuRow = true;
    boolean launcherSettingsButton;
    /** icon_only_buttons without wifi_label_width: the Wi-Fi pill shows no network name. */
    boolean wifiIconOnly;
    /** config.xml wifi_status_color: the Wi-Fi icon's green while online and its usual pill colours; null while off. */
    private ColorStateList wifiOnlineTint, wifiPlainTint;
    /** wifi_status_warnings (with wifi_status_color): orange without internet, red offline; null while off. */
    private ColorStateList wifiNoInternetTint, wifiOfflineTint;
    /** The app library button in the top bar (library_button). */
    private View btnLibraryTop;
    /** The open library came from that button: closing it returns there instead of scrolling to the grid's end. */
    private boolean libraryFromTopBar;
    /** The open library came from the VPN pill (vpn_pill, no VPN app): closing it returns there while the pill still shows. */
    private boolean libraryFromVpnPill;

    /** Turns a labelled top pill into the round TopButtonIcon shape: no text, the icon centred by even padding. */
    private void makeIconOnly(TextView pill) {
        pill.setContentDescription(pill.getText());
        pill.setText(null);
        pill.setMaxWidth(Integer.MAX_VALUE);
        int pad = Math.round(10 * density);
        pill.setPaddingRelative(pad, 0, pad, 0);
        pill.setCompoundDrawablePadding(0);
        pill.getLayoutParams().width = Math.round(42 * density);
    }

    // ---- festive skin ------------------------------------------------------------------------------

    /**
     * Swaps the pill icons for the festival's: a bell for Inputs that swings when focused, a diya for the
     * network whose flame follows the connection, and a marigold for Settings that turns when focused. Halloween
     * has its own: a bat for Inputs that flaps, a jack-o'-lantern whose face glows while online, and a ghost for
     * Settings that floats up.
     */
    private void applyFestiveSkin(TextView inputs, TextView settings) {
        if (festival.isHalloween()) {
            SpookyIconDrawable bat = new SpookyIconDrawable(getResources(), density, SpookyIconDrawable.BAT);
            SpookyIconDrawable ghost = new SpookyIconDrawable(getResources(), density, SpookyIconDrawable.GHOST);
            JackOLanternDrawable pumpkin = new JackOLanternDrawable(getResources(), density);
            bat.setReducedMotion(reducedMotion);
            ghost.setReducedMotion(reducedMotion);
            pumpkin.setReducedMotion(reducedMotion);
            lamp = pumpkin;
            setFestiveIcon(inputs, bat);
            setFestiveIcon(btnWifi, pumpkin);
            setFestiveIcon(settings, ghost);
            addFocusAction(inputs, bat::nudge);
            addFocusAction(settings, ghost::nudge);
            return;
        }
        bell = new BellDrawable(density);
        marigold = new MarigoldDrawable(density);
        DiyaDrawable diya = new DiyaDrawable(density);
        bell.setReducedMotion(reducedMotion);
        marigold.setReducedMotion(reducedMotion);
        diya.setReducedMotion(reducedMotion);
        lamp = diya;
        setFestiveIcon(inputs, bell);
        setFestiveIcon(btnWifi, diya);
        setFestiveIcon(settings, marigold);
        addFocusAction(inputs, bell::ring);
        addFocusAction(settings, marigold::turn);
    }

    private static void setFestiveIcon(TextView pill, Drawable icon) {
        // The pills tint their icons to follow focus; the festive ones keep their own colours.
        pill.setCompoundDrawableTintList(null);
        pill.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null);
    }

    /** Runs {@code action} when the view gains focus, keeping any focus listener it already had. */
    private static void addFocusAction(View view, Runnable action) {
        View.OnFocusChangeListener previous = view.getOnFocusChangeListener();
        view.setOnFocusChangeListener((v, focused) -> {
            if (previous != null) previous.onFocusChange(v, focused);
            if (focused) action.run();
        });
    }

    private void playPetalShower() {
        if (!resumed || isFinishing() || isDestroyed()) return;
        if (festival.isHalloween()) {
            BatFlightView bats = new BatFlightView(this);
            root.addView(bats, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            bats.play(16, 2800, () -> root.removeView(bats));
            return;
        }
        PetalShowerView shower = new PetalShowerView(this);
        root.addView(shower, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        shower.play(60, 2200, () -> root.removeView(shower));
    }

    private static int liftTowardsWhite(int argb, float amount) {
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        r += (int) ((255 - r) * amount);
        g += (int) ((255 - g) * amount);
        b += (int) ((255 - b) * amount);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /**
     * The network pill opens the VPN app when one is configured and installed, otherwise Wi-Fi settings. With
     * vpn_pill the VPN app has its own pill, so this one always opens Wi-Fi settings.
     */
    void openNetworkPill() {
        Intent i = vpnPill ? null : vpnAppIntent();
        open(i != null ? i : systemIntent(Settings.ACTION_WIFI_SETTINGS));
    }

    /** vpn_app's launch intent, or null when none is set or it is not installed. */
    private Intent vpnAppIntent() {
        if (vpnApp.isEmpty()) return null;
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(vpnApp);
        return i != null ? i : getPackageManager().getLaunchIntentForPackage(vpnApp);
    }

    /**
     * vpn_pill: the VPN app, where it is switched on and off. Without one installed, the App library to pick from, or
     * Android's VPN settings when the theme turned the library off.
     */
    private void openVpnPill() {
        Intent i = vpnAppIntent();
        if (i != null || !appLibraryEnabled) {
            open(i != null ? i : systemIntent(Settings.ACTION_VPN_SETTINGS));
            return;
        }
        onLibraryRequested();
        libraryFromVpnPill = true;
    }

    /** vpn_pill: the pill outlined in green with a lock while a VPN is up, outlined in red "VPN off" while it is not;
     *  solid with a white ring only while focused. */
    private void updateVpnPill(boolean vpn) {
        int state = VpnBadge.pillState(vpn, vpnAppIntent() != null);
        if (state == VpnBadge.PILL_HIDDEN) {
            if (btnVpn.getVisibility() == View.GONE) return;
            boolean focused = btnVpn.hasFocus();
            btnVpn.setVisibility(View.GONE);
            if (focused) (btnWifi.getVisibility() == View.VISIBLE ? btnWifi : firstTopButton()).requestFocus();
            return;
        }
        if (state != vpnPillState) {
            vpnPillState = state;
            boolean up = state == VpnBadge.PILL_UP;
            // The state colour at rest (the pill is an outline then), the contrasting ink on the solid focused fill.
            ColorStateList ink = new ColorStateList(
                    new int[][]{new int[]{android.R.attr.state_focused}, new int[0]},
                    new int[]{VpnBadge.pillInkColor(up, true), VpnBadge.pillInkColor(up, false)});
            if (iconOnlyVpn) {
                // An open lock on the plain glass button, or the closed lock outlined in green like the pill (solid
                // while focused); the words go to talkback.
                btnVpn.setContentDescription(getString(up ? R.string.vpn : R.string.vpn_off));
                btnVpn.setBackground(up ? VpnBadge.pillBackground(true, density) : vpnPlainBackground.newDrawable());
                btnVpn.setCompoundDrawablesRelativeWithIntrinsicBounds(VpnBadge.iconOnlyLock(up), 0, 0, 0);
                btnVpn.setCompoundDrawableTintList(up ? ink : vpnPlainInk);
                btnVpn.setVisibility(View.VISIBLE);
                return;
            }
            btnVpn.setText(up ? R.string.vpn : R.string.vpn_off);
            btnVpn.setTextColor(ink);
            btnVpn.setBackground(VpnBadge.pillBackground(up, density));
            btnVpn.setCompoundDrawablesRelativeWithIntrinsicBounds(up ? R.drawable.ic_lock : 0, 0, 0, 0);
            btnVpn.setCompoundDrawableTintList(ink);
        }
        btnVpn.setVisibility(View.VISIBLE);
    }


    /** Every settings entry shares the standard intent and the vendor fallback (SystemSettings). */
    Intent systemIntent(String standardAction) {
        return SystemSettings.intent(this, standardAction);
    }

    void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ActivityOptions opts = (animations && !reducedMotion)
                    ? ActivityOptions.makeCustomAnimation(this, R.anim.app_open_enter, R.anim.app_open_exit)
                    : ActivityOptions.makeCustomAnimation(this, 0, 0);
            startActivity(i, opts.toBundle());
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    void openOwnedActivity(Intent intent, View anchor, int requestCode) {
        try {
            if (requestCode >= 0) {
                startActivityForResult(intent, requestCode);
            } else {
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
            Motion.applyFadeTransition(this, animations && !reducedMotion);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onOpenInput(String inputId) {
        TvInputRouter.openInput(this, inputId);
    }

    @Override
    public void onAppLaunching(View card) {
        // Real app window expands directly from the clicked tile via ActivityOptions.makeScaleUpAnimation.
    }

    @Override
    public void onAppLaunchCancelled() {
        // Focus scale on the tile is restored directly by AppLaunchController.
    }

    /**
     * Hold OK on a tile: a glass menu next to it. Move starts reorder mode with this tile already picked up;
     * Remove from Home takes the shortcut off (only with the app library on, where one press adds it back);
     * App info opens the TV's page for the app, which is what holding OK used to do.
     */
    // ---- hold OK ---------------------------------------------------------------------------------
    // View's own long click misses on some remotes: an IR remote that sends its repeat frames as separate
    // presses, or a Bluetooth one whose repeats land after a tile rebind, never holds one DOWN long enough.
    // So the launcher times the hold itself: OK down on a tile, still down (or still repeating) after
    // HOLD_OK_MS opens the tile menu, and the UP that follows is eaten so it doesn't also open the app.

    private static final long HOLD_OK_MS = 550;
    private long holdOkDown = -1;
    private int holdOkPosition = -1;
    private String holdOkKey;    // the app under OK when it went down; a tile rebuild can move another app to that slot
    private boolean holdOkFired;
    private final Runnable holdOkTimer = this::fireHoldOk;

    private static boolean isOkKey(int code) {
        return code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_BUTTON_A;
    }

    /** True when the event belongs to a hold that opened the tile menu and must not reach the tile. */
    private boolean holdOk(KeyEvent event) {
        if (!tileMenuEnabled || !isOkKey(event.getKeyCode())) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) {
                holdOkFired = false;
                handler.removeCallbacks(holdOkTimer);
                holdOkPosition = reordering || openPopup != null || moduleCardController.isPromptShowing() ? -1 : focusedTilePosition();
                holdOkKey = holdOkPosition < 0 || holdOkPosition >= adapter.appCount() ? null : adapter.keyAt(holdOkPosition);
                if (holdOkKey == null) {
                    holdOkDown = -1;
                    return false;
                }
                holdOkDown = event.getDownTime();
                handler.postDelayed(holdOkTimer, HOLD_OK_MS);
                return false;
            }
            if (holdOkFired) return true;
            if (holdOkDown >= 0 && event.getEventTime() - holdOkDown >= HOLD_OK_MS) {
                fireHoldOk();
                return true;
            }
            return false;
        }
        // ACTION_UP
        handler.removeCallbacks(holdOkTimer);
        holdOkDown = -1;
        holdOkKey = null;
        if (holdOkFired) {
            holdOkFired = false;
            View f = getCurrentFocus();
            if (f != null) f.setPressed(false);
            return true;
        }
        return false;
    }

    /**
     * Stops timing a held OK when the launcher is paused, loses its window or goes away, so the menu can't open
     * behind another screen. An interrupted press still counts as handled: its UP, if it comes back here, is eaten
     * rather than opening the tile.
     */
    private void cancelHoldOk() {
        handler.removeCallbacks(holdOkTimer);
        if (holdOkDown >= 0) holdOkFired = true;
        holdOkDown = -1;
        holdOkPosition = -1;
        holdOkKey = null;
    }

    private void fireHoldOk() {
        handler.removeCallbacks(holdOkTimer);
        if (holdOkDown < 0 || holdOkFired) return;
        holdOkFired = true;
        if (openPopup != null || reordering) return;   // View's own long click got there first
        int position = holdOkPosition;
        if (position < 0 || position >= adapter.appCount() || position != focusedTilePosition()) return;
        // Same slot is not enough: tiles may have been rebuilt during the hold, so the app must match too.
        if (!holdOkKey.equals(adapter.keyAt(position))) return;
        View card = getCurrentFocus();
        if (card != null) card.setPressed(false);
        onTileLongPress(position, card);
    }

    @Override
    public void onTileLongPress(int position, View card) {
        if (reordering || openPopup != null || card == null) return;
        String key = adapter.keyAt(position);
        String pkg = adapter.packageAt(position);
        // Empty dock slots and the Default-home tile carry the launcher's own package: no menu for them.
        if (key == null || pkg == null || pkg.equals(getPackageName())) return;
        String label = adapter.labelAt(position);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow popup = HomeMenus.glassPopup(this, panel, (int) (220 * density));
        HomeMenus.addSettingsMenuRow(getLayoutInflater(), rows, popup, R.string.tile_open, () -> {
            Intent launch = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
            if (launch == null) launch = getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch != null) open(launch);
        });
        if (dockRow && position < adapter.movableCount()) {
            boolean inDock = position < COLUMNS;
            HomeMenus.addSettingsMenuRow(getLayoutInflater(), rows, popup,
                    inDock ? R.string.tile_replace_in_dock : R.string.tile_add_to_dock,
                    () -> startDockEditFor(key, inDock));
        }
        if (position < adapter.movableCount()) HomeMenus.addSettingsMenuRow(getLayoutInflater(), rows, popup, R.string.tile_move, () -> {
            if (!beginReorder()) return;
            int at = adapter.positionOf(key);   // beginReorder can rebuild the tiles
            if (at < 0) return;
            focusTile(at);
            pickUp(at);
            Toast.makeText(this, R.string.reorder_hint, Toast.LENGTH_LONG).show();
        });
        HomeMenus.addSettingsMenuRow(getLayoutInflater(), rows, popup, R.string.tile_rename,
                () -> showRenameAppDialog(pkg, label));
        HomeMenus.addSettingsMenuRow(getLayoutInflater(), rows, popup, R.string.tile_hide, () -> {
            if (AppCustomizations.setHidden(this, pkg, true)) {
                Toast.makeText(this, getString(R.string.tile_hidden_toast, label), Toast.LENGTH_LONG).show();
                settingsRevision = settingsRevision();
                recreate();
            }
        });
        if (appLibraryEnabled) HomeMenus.addSettingsMenuRow(getLayoutInflater(), rows, popup, R.string.library_remove_shortcut, () -> {
            if (LibraryApps.save(this, pkg, false)) reloadApps(false);
            else Toast.makeText(this, R.string.library_save_failed, Toast.LENGTH_LONG).show();
        });
        // Some firmware has no App info page at all; a row that only toasts "can't open" helps nobody.
        Intent info = SystemSettings.appInfo(this, pkg);
        if (info != null) HomeMenus.addSettingsMenuRow(getLayoutInflater(), rows, popup, R.string.library_app_info, () -> open(info));
        if (!SystemSettings.isSystemApp(this, pkg)) {
            Intent uninstall = SystemSettings.uninstall(this, pkg);
            if (uninstall != null) {
                HomeMenus.addSettingsMenuRow(getLayoutInflater(), rows, popup, R.string.tile_uninstall, () -> open(uninstall));
            }
        } else if (info != null) {
            HomeMenus.addSettingsMenuRow(getLayoutInflater(), rows, popup, R.string.tile_disable, () -> {
                Toast.makeText(this, R.string.tile_disable_hint, Toast.LENGTH_SHORT).show();
                open(info);
            });
        }
        if (rows.getChildCount() == 0) {
            if (openPopup == popup) openPopup = null;
            return;
        }
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int menuWidth = Math.max((int) (220 * density), panel.getMeasuredWidth());
        popup.setWidth(menuWidth);
        // Keep the hero shelf out of the menu's way while open so large title text never collides visually.
        final boolean dimmedHero = hero != null && heroShown;
        if (dimmedHero) {
            if (animations && !reducedMotion) hero.animate().alpha(0.14f).setDuration(120).start();
            else hero.setAlpha(0.14f);
            popup.setOnDismissListener(() -> {
                if (openPopup == popup) openPopup = null;
                if (hero != null && heroShown) {
                    if (animations && !reducedMotion) hero.animate().alpha(1f).setDuration(140).start();
                    else hero.setAlpha(1f);
                }
            });
        }
        // Smart non-overlapping anchor: prefer below tile, then cleanly above without colliding with topBar,
        // otherwise beside the tile (right of left-half tiles, left of right-half tiles) clamped within screen.
        int[] at = new int[2];
        card.getLocationInWindow(at);
        int gap = (int) (10 * density);
        int margin = (int) (12 * density);
        int height = panel.getMeasuredHeight();
        int minTop = topBarView != null ? topBarView.getBottom() + (int) (8 * density) : margin;
        int maxX = Math.max(margin, root.getWidth() - menuWidth - margin);
        int clampedX = Math.max(margin, Math.min(at[0], maxX));
        if (at[1] + card.getHeight() + gap + height <= root.getHeight() - margin) {
            HomeMenus.dropIn(popup, panel, card, Gravity.START, density, animations, reducedMotion);
        } else if (at[1] - gap - height >= minTop) {
            int y = at[1] - gap - height;
            if (!animations) {
                popup.showAtLocation(root, Gravity.NO_GRAVITY, clampedX, y);
            } else {
                panel.setAlpha(0f);
                panel.setPivotY(height);
                panel.setScaleY(0.92f);
                panel.setTranslationY(8 * density);
                popup.showAtLocation(root, Gravity.NO_GRAVITY, clampedX, y);
                HomeMenus.springIn(panel, reducedMotion);
            }
        } else {
            int rightX = at[0] + card.getWidth() + gap;
            int leftX = at[0] - gap - menuWidth;
            int sideX = (rightX + menuWidth <= root.getWidth() - margin)
                    ? rightX
                    : Math.max(margin, Math.min(leftX, maxX));
            int sideY = Math.max(minTop, Math.min(at[1] + card.getHeight() - height, root.getHeight() - margin - height));
            if (!animations) {
                popup.showAtLocation(root, Gravity.NO_GRAVITY, sideX, sideY);
            } else {
                panel.setAlpha(0f);
                panel.setPivotY(height * 0.5f);
                panel.setScaleY(0.94f);
                panel.setTranslationY(6 * density);
                popup.showAtLocation(root, Gravity.NO_GRAVITY, sideX, sideY);
                HomeMenus.springIn(panel, reducedMotion);
            }
        }
        rows.getChildAt(0).requestFocus();
    }

    private void showRenameAppDialog(String pkg, String currentLabel) {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setSingleLine(true);
        input.setHint(R.string.tile_rename_hint);
        input.setText(currentLabel);
        input.setSelection(currentLabel.length());
        int pad = Math.round(18 * density);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(pad, pad / 2, pad, pad / 2);
        box.addView(input);
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.tile_rename)
                .setView(box)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String next = input.getText() == null ? "" : input.getText().toString().trim();
                    if (AppCustomizations.setCustomLabel(this, pkg, next)) {
                        settingsRevision = settingsRevision();
                        recreate();
                    }
                })
                .setNeutralButton(R.string.tile_rename_reset, (d, w) -> {
                    if (AppCustomizations.setCustomLabel(this, pkg, "")) {
                        settingsRevision = settingsRevision();
                        recreate();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
