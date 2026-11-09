# CleanYourTV Launcher: Refactoring Roadmap & Architecture Plan

> **Status:** Roadmap Proposal  
> **Target:** `com.gathin.tvhome.MainActivity` (4,142 LOC)  
> **Baseline:** Graphify Knowledge Graph (`14,311 nodes`, `33,300 edges`, `794 communities`)  
> **Rule Enforcement:** Zero external dependencies, pure Java (minSdk 26), APK size gate < 576 KB, strictly preserve remote navigation & UI timing.

---

## 1. Executive Summary & Objectives

`MainActivity` is currently the central **God Object** of the CleanYourTV launcher, with **171 direct edges** in the Graphify knowledge graph and **819 family connections**. It directly manages Android lifecycle events, app package cataloging, dynamic spring animations, dock reordering state machines, TV tuner input routing, Wi-Fi/VPN monitoring, memory trim handling, and multiple dropdown menu popups.

### Core Objectives
1. **Reduce `MainActivity` LOC by ~55%** (from 4,142 LOC to ~1,800 LOC) by extracting self-contained controllers.
2. **Eliminate Main-Thread Bottlenecks** (isolate synchronous file I/O, regex string formatting, and calendar calculations).
3. **Harden Memory Management on 1 GB RAM TVs** (replace unbounded in-memory bitmap caches with bounded LRU caches and proactive trim hooks).
4. **Preserve Exact TV Behavior**: Zero regression on D-pad navigation, spring overshoot, HDMI CEC switching, remote OK hold timing, or boot-to-app behavior.
5. **Zero Framework Overhead**: No Dagger, Hilt, RxJava, Jetpack Compose, or Architecture Components. Maintain pure Java 8/Android SDK primitives to strictly respect the **576 KB (589,824 B) APK size gate**.

---

## 2. Baseline Architecture & Dependency Analysis

Graphify identified `MainActivity` as the primary cross-community bridge (betweenness centrality: `0.037`) linking 5 major functional clusters:

```
                                  ┌──────────────────────────────┐
                                  │      System Events & OS      │
                                  │ (TvInputManager, Network, PM)│
                                  └──────────────┬───────────────┘
                                                 │
                                                 ▼
                  ┌──────────────────────────────────────────────────────────────┐
                  │                 MainActivity (4,142 LOC)                     │
                  │              Central Hub & State Coordinator                 │
                  └───┬──────────────┬──────────────┬──────────────┬─────────┬───┘
                      │              │              │              │         │
       ┌──────────────┴────┐  ┌──────┴───────┐ ┌────┴────────┐ ┌──┴─────────┴───┐
       │ App Grid & Dock   │  │ Focus Spring │ │ TV Inputs & │ │ Network, VPN   │
       │ (AppAdapter,      │  │ & Ambient    │ │ Source Menu │ │ & Status Pills │
       │  Reorder State)   │  │ (GlassView)  │ │ (TvInput)   │ │ (Connectivity) │
       └───────────────────┘  └──────────────┘ └─────────────┘ └────────────────┘
```

### Graph Connection Density by Subsystem
- **Launcher App Grid & Dock**: 63 connections (`AppAdapter`, `RecyclerView`, `TvInputCallback`, `.openInputsIfAsked()`, `.settingsRevision()`)
- **TV Launcher UI Lifecycle**: 11 connections (`View`, `TextView`, `Handler`, `FrameLayout`, `ImageView`, `ColorStateList`)
- **Bluetooth Device Linking & Remote Keys**: 2 connections (`BroadcastReceiver`, `IntentFilter`, `handleSettingsKey`)
- **Launcher Module Cards**: 1 direct connection (`Cards.Card`, user approval prompts, dropdown card manager)
- **Live Visual Views & Theming**: 3 connections (`ValueAnimator`, `Bitmap`, `Rect`, `SkyView`, `WallpaperPlayer`, `FestiveLamp`)

---

## 3. Strict Architectural Constraints

Every extraction must strictly obey these repository-wide constraints:
1. **Size Gate (< 576 KB)**: Release APK cannot exceed 589,824 bytes. New classes must be lean POJOs or package-private helper delegates.
2. **Memory Limit (1 GB RAM Target)**: Android TVs run under severe memory pressure. No static leaks, no background daemon threads, and all bitmap caches must be bounded.
3. **No Network Access**: The launcher package `com.gathin.tvhome` has **no `INTERNET` permission**. Connectivity listeners only observe local network availability (`NetworkCapabilities.NET_CAPABILITY_VALIDATED`).
4. **Single-Threaded Main Loop Safety**: Remote control D-pad latency must stay under 16ms (60 fps). Work moved off `MainActivity` must not introduce multithreaded race conditions on UI views.

---

## 4. Phased Extraction Roadmap & Dependency Matrix

The refactoring is structured into **4 sequential, risk-prioritized phases**. Each phase is independently testable and reversible.

```
 Phase 1: Zero-Risk Isolated Helpers
 ├── 1.1 HeroArtCache (LRU Bitmap Management)
 ├── 1.2 TvInputController (Hardware Tuner & HDMI CEC Sources)
 └── 1.3 NetworkStatusMonitor (Connectivity, Wi-Fi & VPN)
                       │
                       ▼
 Phase 2: Add-on & Top-Bar Decoupling
 ├── 2.1 TopBarWidgetCoordinator (Clocks, Lunisolar, Shamsi, Race/GameDay)
 └── 2.2 ModuleCardPromptManager (Consent Dialogs & Card Menu)
                       │
                       ▼
 Phase 3: Interaction & Physics Decoupling
 ├── 3.1 DockReorderManager (Reordering & Slot Editing State Machine)
 └── 3.2 FocusHighlightEngine (Spring Physics & Glass View Alignment)
                       │
                       ▼
 Phase 4: Lifecycle & Process State Consolidation
 └── 4.1 HomeStateHolder (Static State Cleanup & Focus Restoration)
```

---

## 5. Component Extraction Plans

### Phase 1: Isolated System Observers & Resource Caches (Low Risk)

#### 1.1 `HeroArtCache` (Bitmap Cache & Memory Management)
- **Goal**: Prevent out-of-memory errors on 1 GB RAM TVs by replacing unbounded HashMap with bounded LRU eviction.
- **Current Lines in `MainActivity.java`**:
  - Field: `Map<String, Bitmap> heroArtCache` ([L175](file:///D:/emmanuel/new%20tv%20app/launcher/app/src/main/java/com/gathin/tvhome/MainActivity.java#L175))
  - Logic: `heroArtCache` lookup, insertion, and memory trimming ([L2062–L2139](file:///D:/emmanuel/new%20tv%20app/launcher/app/src/main/java/com/gathin/tvhome/MainActivity.java#L2062-L2139), [L1393–L1405](file:///D:/emmanuel/new%20tv%20app/launcher/app/src/main/java/com/gathin/tvhome/MainActivity.java#L1393-L1405))
- **Proposed Extraction**:
  - Create package-private `HeroArtCache.java` with maximum 6–8 bitmap slots (approx. 40 KB total) and an explicit `evictAll()` on `onTrimMemory`.
- **Affected Files**:
  - `launcher/app/src/main/java/com/gathin/tvhome/HeroArtCache.java` (New)
  - `launcher/app/src/main/java/com/gathin/tvhome/MainActivity.java`
  - `launcher/app/src/test/java/com/gathin/tvhome/HeroArtCacheTest.java` (New)
- **Shared State & Lifecycle**:
  - Initialized in `onCreate()`. Evicted on `onTrimMemory(TRIM_MEMORY_RUNNING_CRITICAL)`.
- **Circular Dependencies**: None (pure data cache).
- **Risk Assessment**: **Very Low**. No UI layout impact.

---

#### 1.2 `TvInputController` (TV Tuner Inputs & HDMI Sources)
- **Goal**: Extract 480 LOC of `TvInputManager` callbacks, vendor source panel adapters (Coocaa), and input switcher popups.
- **Current Lines in `MainActivity.java`**:
  - Callback: `TvInputManager.TvInputCallback inputCallback` ([L393–L403](file:///D:/emmanuel/new%20tv%20app/launcher/app/src/main/java/com/gathin/tvhome/MainActivity.java#L393-L403))
  - Source handling: `updateInputsButton()`, `openLiveTvAfterBoot()`, `openInput()`, `showInputs()`, `addInputRow()` ([L3248–L3720](file:///D:/emmanuel/new%20tv%20app/launcher/app/src/main/java/com/gathin/tvhome/MainActivity.java#L3248-L3720))
- **Proposed Extraction**:
  - `TvInputController.java`: Manages `TvInputManager` registration, input list query, CEC label filtering, and input popup display.
  - Interface: `TvInputController.Listener` to inform `MainActivity` when an input is selected or Live TV launches.
- **Affected Files**:
  - `launcher/app/src/main/java/com/gathin/tvhome/TvInputController.java` (New)
  - `launcher/app/src/main/java/com/gathin/tvhome/MainActivity.java`
  - `launcher/app/src/test/java/com/gathin/tvhome/InputNamesTest.java` (Verify against existing)
  - `launcher/app/src/test/java/com/gathin/tvhome/InputSourcesTest.java` (Verify against existing)
- **Shared State & Lifecycle**:
  - Registered on `onStart()`, unregistered on `onStop()`.
  - Reads config keys: `inputs_connected_only`, `inputs_cec_state_only`, `live_tv_package`.
- **Circular Dependencies**: Prevented by passing a simple `Context` and callback interface.
- **Risk Assessment**: **Low**. Verified by `InputSourcesTest` and `InputFoldTest`.

---

#### 1.3 `NetworkStatusMonitor` (Connectivity, Wi-Fi & VPN)
- **Goal**: Isolate network capability callbacks, signal level icon resolution, VPN badge styling, and delayed boot app launch.
- **Current Lines in `MainActivity.java`**:
  - Callbacks: `networkCallback` ([L368–L391](file:///D:/emmanuel/new%20tv%20app/launcher/app/src/main/java/com/gathin/tvhome/MainActivity.java#L368-L391)), `vpnCallback` ([L405–L415](file:///D:/emmanuel/new%20tv%20app/launcher/app/src/main/java/com/gathin/tvhome/MainActivity.java#L405-L415))
  - Update logic: `updateWifiLabel()`, `updateVpnPill()`, `openWhenOnline` ([L2968–L3086](file:///D:/emmanuel/new%20tv%20app/launcher/app/src/main/java/com/gathin/tvhome/MainActivity.java#L2968-L3086), [L3178–L3240](file:///D:/emmanuel/new%20tv%20app/launcher/app/src/main/java/com/gathin/tvhome/MainActivity.java#L3178-L3240))
- **Proposed Extraction**:
  - `NetworkStatusMonitor.java`: Registers network request listeners with `ConnectivityManager`. Exposes state to `MainActivity` via `onNetworkStateChanged(NetworkState state)`.
- **Affected Files**:
  - `launcher/app/src/main/java/com/gathin/tvhome/NetworkStatusMonitor.java` (New)
  - `launcher/app/src/main/java/com/gathin/tvhome/MainActivity.java`
  - `launcher/app/src/test/java/com/gathin/tvhome/VpnPillTest.java` (Verify against existing)
  - `launcher/app/src/test/java/com/gathin/tvhome/OpenOnStartTest.java` (Verify against existing)
- **Shared State & Lifecycle**:
  - Starts on `onStart()`, stops on `onStop()`. Must post to main thread handler.
- **Circular Dependencies**: None.
- **Risk Assessment**: **Low**. Validated by existing `OpenOnStartTest` and `VpnPillTest`.

---

### Phase 2: Add-on & Widget Decoupling (Medium Risk)

#### 2.1 `TopBarWidgetCoordinator` (Clocks, Lunisolar, Shamsi, Race & GameDay)
- **Goal**: Decouple peripheral informational widgets from core launcher layout and activity lifecycle.
- **Current Lines in `MainActivity.java`**:
  - Minute timer: `minuteTick` ([L444–L455](file:///D:/emmanuel/new%20tv%20app/launcher/app/src/main/java/com/gathin/tvhome/MainActivity.java#L444-L455))
  - Logic: `updateGreeting()`, `updatePanel()`, `updateRace()`, `showPager()`, `readFirstLine("/proc/meminfo")`, `formatGb()` ([L2754–L2945](file:///D:/emmanuel/new%20tv%20app/launcher/app/src/main/java/com/gathin/tvhome/MainActivity.java#L2754-L2945), [L2454–L2470](file:///D:/emmanuel/new%20tv%20app/launcher/app/src/main/java/com/gathin/tvhome/MainActivity.java#L2454-L2470))
- **Proposed Extraction**:
  - `TopBarWidgetCoordinator.java`: Holds references to the top-bar text views (`greeting`, `stats`, `shamsiDate`, `lunarDate`, `racePanel`). Manages ticker timing and updates widgets on minute broadcast.
- **Affected Files**:
  - `launcher/app/src/main/java/com/gathin/tvhome/TopBarWidgetCoordinator.java` (New)
  - `launcher/app/src/main/java/com/gathin/tvhome/MainActivity.java`
  - Existing tests: `ClockFormatTest`, `LunarTest`, `ShamsiTest`, `MemInfoTest`, `RaceWeekendTest`, `GameDayTest`
- **Shared State & Lifecycle**:
  - Paused in `onPause()`, resumed in `onResume()`.
- **Circular Dependencies**: None. Coordinator only holds child View references passed in `onCreate()`.
- **Risk Assessment**: **Medium-Low**. Purely visual widgets; no navigation impact.

---

#### 2.2 `ModuleCardPromptManager` (Module Cards & Approval Dialogs)
- **Goal**: Separate card discovery prompt dialogs and popup menu generation from Home's main activity.
- **Current Lines in `MainActivity.java`**:
  - Fields: `cards`, `cardPrompt`, `promptsPaused` ([L314–L320](file:///D:/emmanuel/new%20tv%20app/launcher/app/src/main/java/com/gathin/tvhome/MainActivity.java#L314-L320))
  - Dialog & popups: `showCardPrompt()`, `showCardPromptNow()`, `decideCard()`, `showModuleCards()`, `showModuleCardsNow()` ([L3958–L4140](file:///D:/emmanuel/new%20tv%20app/launcher/app/src/main/java/com/gathin/tvhome/MainActivity.java#L3958-L4140))
- **Proposed Extraction**:
  - `ModuleCardPromptManager.java`: Coordinates [`Cards.java`](file:///D:/emmanuel/new%20tv%20app/launcher/app/src/main/java/com/gathin/tvhome/Cards.java), builds `cardPrompt` Dialog, and renders module card popup menus.
- **Affected Files**:
  - `launcher/app/src/main/java/com/gathin/tvhome/ModuleCardPromptManager.java` (New)
  - `launcher/app/src/main/java/com/gathin/tvhome/MainActivity.java`
  - `launcher/app/src/test/java/com/gathin/tvhome/CardFeatureTest.java` (Verify against existing)
  - `launcher/app/src/test/java/com/gathin/tvhome/CardsTest.java` (Verify against existing)
- **Shared State & Lifecycle**:
  - Dismisses dialogs on `onPause()` to prevent window leaks.
- **Circular Dependencies**: None.
- **Risk Assessment**: **Medium-Low**. Verified by `CardsTest` and `CardFeatureTest`.

---

### Phase 3: Grid Interaction & Physics Decoupling (High Caution)

#### 3.1 `DockReorderManager` (Reordering & Edit-Dock State Machine)
- **Goal**: Extract 320 LOC of complex state machine governing tile reordering, pick-up/drop animations, and dock slot substitution.
- **Current Lines in `MainActivity.java`**:
  - Fields: `reordering`, `movingKey`, `orderAtStart`, `dockInSession`, `dockEditing`, `dockEditSlot` ([L337–L359](file:///D:/emmanuel/new%20tv%20app/launcher/app/src/main/java/com/gathin/tvhome/MainActivity.java#L337-L359))
  - Logic: `toggleReorder()`, `beginReorder()`, `startDockEdit()`, `dispatchDockEdit()`, `swapIntoDock()`, `pickUp()`, `drop()`, `dimOthers()`, `endReorder()` ([L1544–L1790](file:///D:/emmanuel/new%20tv%20app/launcher/app/src/main/java/com/gathin/tvhome/MainActivity.java#L1544-L1790))
- **Proposed Extraction**:
  - `DockReorderManager.java`: Owns the reordering state machine. Intercepts D-pad events during reordering and mutates the item list through `AppAdapter`.
- **Affected Files**:
  - `launcher/app/src/main/java/com/gathin/tvhome/DockReorderManager.java` (New)
  - `launcher/app/src/main/java/com/gathin/tvhome/MainActivity.java`
  - `launcher/app/src/main/java/com/gathin/tvhome/AppAdapter.java`
  - `launcher/app/src/test/java/com/gathin/tvhome/OrderTest.java` (Verify against existing)
  - `launcher/app/src/test/java/com/gathin/tvhome/DockSlotsTest.java` (Verify against existing)
- **Shared State & Lifecycle**:
  - Cancels reorder session on `onPause()` / `onStop()` (restores `orderAtStart` if abandoned).
- **Circular Dependencies**:
  - Needs access to `AppAdapter` to query tile positions and notify item moves. Pass `AppAdapter` directly to `DockReorderManager`.
- **Risk Assessment**: **Medium-High**. Reordering involves D-pad remote navigation and visual tile alpha changes. Must be verified on real TV hardware.

---

#### 3.2 `FocusHighlightEngine` (Spring Physics & Glass Highlight)
- **Goal**: Extract AndroidX dynamic animation math, coordinate translation, and scroll synchronization.
- **Current Lines in `MainActivity.java`**:
  - Fields: `springX`, `springY`, `glassScrollShift`, `glassScrollShiftX`, `glassCardRect`, `tileRect`, `revealRect` ([L234–L250](file:///D:/emmanuel/new%20tv%20app/launcher/app/src/main/java/com/gathin/tvhome/MainActivity.java#L234-L250))
  - Listeners: `FloatPropertyCompat` definitions ([L794–L815](file:///D:/emmanuel/new%20tv%20app/launcher/app/src/main/java/com/gathin/tvhome/MainActivity.java#L794-L815)), `onTileFocused` ([L1845–L2060](file:///D:/emmanuel/new%20tv%20app/launcher/app/src/main/java/com/gathin/tvhome/MainActivity.java#L1845-L2060)), `ambientAnimator` ([L2140–L2250](file:///D:/emmanuel/new%20tv%20app/launcher/app/src/main/java/com/gathin/tvhome/MainActivity.java#L2140-L2250))
- **Proposed Extraction**:
  - `FocusHighlightEngine.java`: Encapsulates `SpringAnimation` setup, tile target calculation (`fitToTile`), scroll compensation (`onScrolled`), and ambient glow fading.
- **Affected Files**:
  - `launcher/app/src/main/java/com/gathin/tvhome/FocusHighlightEngine.java` (New)
  - `launcher/app/src/main/java/com/gathin/tvhome/MainActivity.java`
  - `launcher/app/src/test/java/com/gathin/tvhome/MotionTest.java` (Verify against existing)
  - `launcher/app/src/test/java/com/gathin/tvhome/TileFitTest.java` (Verify against existing)
  - `launcher/app/src/test/java/com/gathin/tvhome/GlassStyleTest.java` (Verify against existing)
- **Shared State & Lifecycle**:
  - Cancels springs on `onPause()` to prevent background animation ticking.
- **Circular Dependencies**: None.
- **Risk Assessment**: **High**. Focus glass jumping or misaligned bounding boxes directly breaks user TV immersion. Requires careful A/B testing and trace inspection.

---

### Phase 4: Lifecycle & Process State Consolidation (Medium Risk)

#### 4.1 `HomeStateHolder` (Static Persistence Cleanup)
- **Goal**: Consolidate scattered static variables into a structured state holder to prevent cross-process leaks and activity resurrection glitches.
- **Current Lines in `MainActivity.java`**:
  - `static int lastFocused` ([L253](file:///D:/emmanuel/new%20tv%20app/launcher/app/src/main/java/com/gathin/tvhome/MainActivity.java#L253))
  - `static String lastFocusedKey` ([L255](file:///D:/emmanuel/new%20tv%20app/launcher/app/src/main/java/com/gathin/tvhome/MainActivity.java#L255))
  - `static LocalDate lastPetalShower` ([L280](file:///D:/emmanuel/new%20tv%20app/launcher/app/src/main/java/com/gathin/tvhome/MainActivity.java#L280))
  - `static int COLUMNS` ([L115](file:///D:/emmanuel/new%20tv%20app/launcher/app/src/main/java/com/gathin/tvhome/MainActivity.java#L115))
- **Proposed Extraction**:
  - Package-private `HomeStateHolder.java` storing persistent session state, ensuring clean reset points when themes or configurations change.
- **Affected Files**:
  - `launcher/app/src/main/java/com/gathin/tvhome/HomeStateHolder.java` (New)
  - `launcher/app/src/main/java/com/gathin/tvhome/MainActivity.java`
  - `launcher/app/src/test/java/com/gathin/tvhome/HomeStateHolderTest.java` (New)
- **Risk Assessment**: **Medium-Low**.

---

## 6. Risk Assessment & Failure Modes Matrix

| Failure Mode | Impact | Affected Subsystem | Detection Mechanism | Mitigation Strategy |
|---|---|---|---|---|
| **Spring Glass Misalignment** | High | Focus & Navigation | D-pad navigation overshoots tile bounds; visual hitch. | Run `TileFitTest` & `MotionTest`. Verify on real TV at 1080p and 4K resolutions. |
| **Window Leak on Pause** | High | Inputs & Card Dialogs | Logcat `WindowManager$BadTokenException` when opening another app. | Ensure all `PopupWindow` and `Dialog` instances call `dismiss()` synchronously in `onPause()`. |
| **Main-Thread ANR / Stutter** | Critical | TopBar / MemInfo | Dropped frames (>16ms) during D-pad navigation. | Ensure `/proc/meminfo` reading and calendar calculations only trigger during low-frequency minute ticks or background worker. |
| **HDMI Input Missing on Boot** | High | TV Tuner Inputs | Inputs button missing or fails to show connected HDMI ports. | Retain `TvInputManager.TvInputCallback` lifecycle registration in `onStart()`. |
| **APK Size Gate Violation** | Blocker | CI / Gradle Build | Release APK exceeds 589,824 bytes. | Enforce zero external libraries. Run `./gradlew assembleRelease` and `stat` check after every phase. |

---

## 7. Verification Gates & Test Strategy

Before committing any refactored phase, the change must pass **three strict verification gates**:

### Gate 1: Local Unit Test Suite
Execute the entire local unit test suite (both Java unit tests and Python integration tests):
```powershell
# 1. Java Unit Tests & Lint
cd launcher
./gradlew --no-daemon testReleaseUnitTest lintRelease assembleRelease

# 2. Python Suite (CleanYourTV rules & contracts)
cd ..
python -m unittest discover -s tests -v
```
*Lint must report 0 errors.*

### Gate 2: APK Size Gate Verification
Check the exact byte count of the release APK:
```powershell
$bytes = (Get-Item launcher/app/build/outputs/apk/release/app-release.apk).Length
Write-Host "APK Size: $bytes bytes (Limit: 589824 bytes)"
if ($bytes -ge 589824) { throw "APK Size Gate Exceeded!" }
```

### Gate 3: Real TV Navigation & Trace Verification
Per repository rules, changes must be verified on hardware:
1. Sideload APK: `adb install -r launcher/app/build/outputs/apk/release/app-release.apk`
2. Validate D-pad navigation: Up, Down, Left, Right from all grid boundaries.
3. Test edge scenarios:
   - Hold OK on a tile: verify tile action menu opens without crash.
   - Press Reorder button: verify picking up, sliding, and dropping tiles in the dock.
   - Switch HDMI input via Input button.
   - Reboot TV: confirm Live TV auto-boot and Home restoration.

---

## 8. Rollback Strategy

Each extraction is isolated to one component per git commit:
- If a phase fails real TV verification, revert the atomic commit immediately using `git revert HEAD`.
- Because each extracted class is package-private and has a 1:1 delegate interface with `MainActivity`, rolling back involves no database migrations or shared preference incompatibilities.

---

## 9. Projected Architecture State Post-Refactoring

Following this roadmap will transform `MainActivity`:

```
Before Refactoring:
MainActivity.java: 4,142 LOC (God Object - 171 direct graph edges)

After Refactoring (Projected):
├── MainActivity.java: ~1,750 LOC (Clean Activity Lifecycle & View Orchestration)
├── FocusHighlightEngine.java: ~260 LOC (Spring physics & glass highlight)
├── DockReorderManager.java: ~320 LOC (Reorder & edit-dock state machine)
├── TvInputController.java: ~420 LOC (Tuner, HDMI CEC & vendor source panel)
├── TopBarWidgetCoordinator.java: ~270 LOC (Clock, Shamsi, Lunar & race panel)
├── NetworkStatusMonitor.java: ~240 LOC (Wi-Fi, VPN & online boot gating)
├── ModuleCardPromptManager.java: ~210 LOC (Module card prompts & popup)
├── HeroArtCache.java: ~85 LOC (LRU bounded bitmap cache)
└── HomeStateHolder.java: ~70 LOC (Safe persistent session state)
```

**Result:** Modularity improved, memory leaks mitigated, maintainability dramatically increased, zero UI regressions, and full compatibility preserved.
