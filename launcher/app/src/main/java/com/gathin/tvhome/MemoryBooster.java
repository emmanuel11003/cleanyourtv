package com.gathin.tvhome;

import android.app.ActivityManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.provider.Settings;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Multi-pass RAM optimizer built for 1-2 GB Android TVs.
 *
 * 1. Prioritizes recently active apps (UsageStats + LastOpened) and sweeps all launchable
 *    TV/user apps — including pre-installed system media/store apps like YouTube, Netflix,
 *    and Play Store — while strictly protecting core OS services, active media playback,
 *    persistent services, TV inputs, and enabled accessibility helpers.
 * 2. Trims this launcher's own non-visible caches and stale temporary cache files.
 * 3. Uses a two-pass kill with a brief kernel reclaim settle window so Linux/lmkd finishes
 *    reclaiming anonymous + zRAM pages before reporting exact /proc/meminfo results.
 */
final class MemoryBooster {
    private static final long RECENT_WINDOW_MS = 24L * 60L * 60L * 1000L;
    private static final long STALE_CACHE_MS = 5L * 60L * 1000L;
    private static final long PASS1_SETTLE_MS = 350L;
    private static final long PASS2_SETTLE_MS = 250L;

    interface TrimHook {
        /** Called on the main thread to trim off-screen launcher caches without flickering visible UI. */
        void trimForBoost();

        /** Called on the main thread after boost finishes so top-bar memory stats refresh immediately. */
        void onBoostFinished();
    }

    private static volatile WeakReference<TrimHook> trimHookRef = new WeakReference<>(null);

    static void registerTrimHook(TrimHook hook) {
        trimHookRef = new WeakReference<>(hook);
    }

    static void unregisterTrimHook(TrimHook hook) {
        TrimHook current = trimHookRef.get();
        if (current == null || current == hook) {
            trimHookRef = new WeakReference<>(null);
        }
    }

    static final class Snapshot {
        final long usedMb;
        final long totalMb;
        final long freeMb;
        final int usedPercent;

        Snapshot(long usedMb, long totalMb) {
            this.totalMb = Math.max(1L, totalMb);
            this.usedMb = Math.max(0L, Math.min(usedMb, this.totalMb));
            this.freeMb = Math.max(0L, this.totalMb - this.usedMb);
            this.usedPercent = Math.max(0, Math.min(100, Math.round((this.usedMb * 100f) / this.totalMb)));
        }
    }

    static final class BoostResult {
        final int trimmedApps;
        final int recentAppsTrimmed;
        final long freedMb;
        final long clearedCacheKb;
        final Snapshot before;
        final Snapshot after;

        BoostResult(int trimmedApps, int recentAppsTrimmed, long freedMb, long clearedCacheKb,
                    Snapshot before, Snapshot after) {
            this.trimmedApps = Math.max(0, trimmedApps);
            this.recentAppsTrimmed = Math.max(0, recentAppsTrimmed);
            this.freedMb = Math.max(0L, freedMb);
            this.clearedCacheKb = Math.max(0L, clearedCacheKb);
            this.before = before;
            this.after = after;
        }
    }

    static final class RecentApp {
        final String packageName;
        final String label;
        final long lastUsedMs;

        RecentApp(String packageName, String label, long lastUsedMs) {
            this.packageName = packageName;
            this.label = label;
            this.lastUsedMs = lastUsedMs;
        }
    }

    /** Android 14 (API 34) restricts killBackgroundProcesses() to the caller's own UID. */
    static boolean canKillOtherApps() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE;
    }

    private MemoryBooster() {}

    static Snapshot readSnapshot(Context ctx) {
        // Prefer /proc/meminfo (matches StatsText/MemInfo), fallback to ActivityManager.
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/meminfo"))) {
            List<String> lines = new ArrayList<>(24);
            String line;
            for (int i = 0; i < 24 && (line = br.readLine()) != null; i++) {
                lines.add(line);
            }
            long[] usedOfTotal = MemInfo.usedOfTotalMb(lines);
            if (usedOfTotal != null) {
                return new Snapshot(usedOfTotal[0], usedOfTotal[1]);
            }
        } catch (Throwable ignored) {
        }
        ActivityManager am = ctx != null ? (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE) : null;
        if (am != null) {
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            long totalMb = Math.max(1L, mi.totalMem / (1024L * 1024L));
            long availMb = Math.max(0L, mi.availMem / (1024L * 1024L));
            return new Snapshot(Math.max(0L, totalMb - availMb), totalMb);
        }
        return new Snapshot(0L, 1024L);
    }

    /** Parses colon-separated enabled_accessibility_services strings into package names. */
    static Set<String> parseAccessibilityPackages(String enabledServices) {
        if (enabledServices == null || enabledServices.isEmpty()) return Collections.emptySet();
        Set<String> pkgs = new HashSet<>();
        for (String component : enabledServices.split(":")) {
            String trimmed = component.trim();
            if (trimmed.isEmpty()) continue;
            int slash = trimmed.indexOf('/');
            String pkg = slash > 0 ? trimmed.substring(0, slash) : trimmed;
            if (!pkg.isEmpty()) pkgs.add(pkg);
        }
        return pkgs;
    }

    static boolean isProtected(String pkg, String selfPkg, String activeMediaPkg) {
        return isProtected(pkg, selfPkg, activeMediaPkg, Collections.emptySet());
    }

    static boolean isProtected(String pkg, String selfPkg, String activeMediaPkg,
                               Set<String> activeAccessibilityPkgs) {
        if (pkg == null || pkg.isEmpty()) return true;
        if (pkg.equals(selfPkg) || pkg.equals(activeMediaPkg)) return true;
        if (activeAccessibilityPkgs != null && activeAccessibilityPkgs.contains(pkg)) return true;
        if ("android".equals(pkg)
                || "com.android.settings".equals(pkg)
                || "com.android.phone".equals(pkg)
                || "com.android.shell".equals(pkg)
                || "com.android.se".equals(pkg)
                || "com.android.nfc".equals(pkg)
                || "com.android.keychain".equals(pkg)
                || pkg.startsWith("android.ext.")
                || pkg.startsWith("com.android.ext.")
                || pkg.startsWith("com.google.android.ext.")
                || pkg.startsWith("com.android.overlay.")
                || pkg.startsWith("com.google.android.overlay.")
                || pkg.startsWith("com.android.systemui")
                || pkg.startsWith("com.android.providers.")
                || pkg.startsWith("com.android.tv.settings")
                || pkg.startsWith("com.android.tv.tuner")
                || pkg.startsWith("com.android.permissioncontroller")
                || pkg.startsWith("com.google.android.permissioncontroller")
                || pkg.startsWith("com.android.packageinstaller")
                || pkg.startsWith("com.google.android.packageinstaller")
                || pkg.startsWith("com.android.networkstack")
                || pkg.startsWith("com.android.wifi")
                || pkg.startsWith("com.android.bluetooth")
                || pkg.startsWith("com.android.inputmethod")
                || pkg.startsWith("com.google.android.inputmethod")
                || pkg.startsWith("com.google.android.gms")
                || pkg.startsWith("com.google.android.gsf")
                || pkg.startsWith("com.google.android.tv.remote")
                || pkg.contains("tvinput")
                || pkg.contains("inputservice")) {
            return true;
        }
        return false;
    }

    static String formatResultMessage(Context ctx, BoostResult res) {
        if (res.freedMb > 0) {
            return ctx.getString(R.string.boost_ram_freed,
                    res.freedMb, res.after.freeMb, res.after.usedPercent);
        }
        if (res.clearedCacheKb > 0) {
            return ctx.getString(R.string.boost_ram_cache_cleared,
                    res.clearedCacheKb, res.after.freeMb, res.after.usedPercent);
        }
        return ctx.getString(R.string.boost_ram_clean,
                res.after.freeMb, res.after.usedPercent);
    }

    /** Executes two-pass background trimming + launcher cache trim on a worker thread. */
    static BoostResult boost(Context ctx) {
        Snapshot before = readSnapshot(ctx);
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return new BoostResult(0, 0, 0L, 0L, before, before);

        String selfPkg = ctx.getPackageName();
        MediaNowPlaying.State media = MediaNowPlaying.currentState(ctx);
        String activeMediaPkg = (media != null && media.playing) ? media.packageName : null;
        Set<String> protectedA11y = readEnabledAccessibilityPackages(ctx);

        PackageManager pm = ctx.getPackageManager();
        Set<String> persistentOrStopped = new HashSet<>();
        List<ApplicationInfo> installed = null;
        try {
            installed = pm.getInstalledApplications(0);
            for (ApplicationInfo ai : installed) {
                if (!ai.enabled
                        || (ai.flags & ApplicationInfo.FLAG_PERSISTENT) != 0
                        || (ai.flags & ApplicationInfo.FLAG_STOPPED) != 0) {
                    persistentOrStopped.add(ai.packageName);
                }
            }
        } catch (Throwable ignored) {
        }

        // Ordered target set: recently active apps first, then launchable apps (including system media/store),
        // then remaining non-system user apps and visible background processes.
        LinkedHashSet<String> recentTargets = new LinkedHashSet<>();
        LinkedHashSet<String> allTargets = new LinkedHashSet<>();

        // 1. Recently active apps via UsageStatsManager (when PACKAGE_USAGE_STATS is granted).
        for (String pkg : readRecentUsagePackages(ctx)) {
            if (!persistentOrStopped.contains(pkg)
                    && !isProtected(pkg, selfPkg, activeMediaPkg, protectedA11y)) {
                recentTargets.add(pkg);
                allTargets.add(pkg);
            }
        }

        // 2. Recently opened apps tracked directly by Home (works without UsageStats permission).
        for (String pkg : LastOpened.recentPackages(ctx, RECENT_WINDOW_MS)) {
            if (!persistentOrStopped.contains(pkg)
                    && !isProtected(pkg, selfPkg, activeMediaPkg, protectedA11y)) {
                recentTargets.add(pkg);
                allTargets.add(pkg);
            }
        }

        // 3. All launchable TV & user apps (catches pre-installed YouTube, Play Store, Netflix, etc.).
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            try {
                Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
                List<ResolveInfo> resolved = pm.queryIntentActivities(query, 0);
                if (resolved != null) {
                    for (ResolveInfo ri : resolved) {
                        if (ri == null || ri.activityInfo == null) continue;
                        String pkg = ri.activityInfo.packageName;
                        if (!persistentOrStopped.contains(pkg)
                                && !isProtected(pkg, selfPkg, activeMediaPkg, protectedA11y)) {
                            allTargets.add(pkg);
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        // 4. Non-system and updated-system apps without a launcher icon.
        if (installed != null) {
            for (ApplicationInfo ai : installed) {
                if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                        && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0) {
                    continue;
                }
                if (persistentOrStopped.contains(ai.packageName)) continue;
                if (!isProtected(ai.packageName, selfPkg, activeMediaPkg, protectedA11y)) {
                    allTargets.add(ai.packageName);
                }
            }
        }

        // 5. Any background processes visible to ActivityManager.
        try {
            List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
            if (procs != null) {
                for (ActivityManager.RunningAppProcessInfo proc : procs) {
                    if (proc.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE) {
                        continue;
                    }
                    if (proc.pkgList != null) {
                        for (String p : proc.pkgList) {
                            if (!persistentOrStopped.contains(p)
                                    && !isProtected(p, selfPkg, activeMediaPkg, protectedA11y)) {
                                allTargets.add(p);
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        // Pass 1: trim prioritized background targets on Android 8-13 (Android 14 restricts
        // killBackgroundProcesses to the caller's own UID).
        int trimmed = 0;
        if (canKillOtherApps()) {
            for (String pkg : allTargets) {
                try {
                    am.killBackgroundProcesses(pkg);
                    trimmed++;
                } catch (Throwable ignored) {
                }
            }
        }

        // Trim launcher's own non-visible caches + stale temp cache files and hint GC.
        TrimHook hook = trimHookRef.get();
        if (hook != null) {
            LauncherExecutors.main().post(hook::trimForBoost);
        }
        long clearedBytes = purgeStaleTempCache(ctx.getCacheDir(), System.currentTimeMillis() - STALE_CACHE_MS);
        long clearedCacheKb = clearedBytes > 0L ? Math.max(1L, clearedBytes / 1024L) : 0L;
        Runtime.getRuntime().gc();

        // Allow kernel / lmkd time to reclaim pages from killed apps & GC.
        sleepQuietly(PASS1_SETTLE_MS);

        // Pass 2 (Android 8-13): re-sweep recent apps to catch secondary/re-cached processes.
        if (canKillOtherApps()) {
            Set<String> pass2 = recentTargets.isEmpty() ? allTargets : recentTargets;
            for (String pkg : pass2) {
                try {
                    am.killBackgroundProcesses(pkg);
                } catch (Throwable ignored) {
                }
            }
            sleepQuietly(PASS2_SETTLE_MS);
        }

        Snapshot after = readSnapshot(ctx);
        long freed = Math.max(0L, after.freeMb - before.freeMb);

        // Refresh Home top-bar RAM indicator immediately.
        TrimHook finishedHook = trimHookRef.get();
        if (finishedHook != null) {
            LauncherExecutors.main().post(finishedHook::onBoostFinished);
        }
        return new BoostResult(trimmed, recentTargets.size(), freed, clearedCacheKb, before, after);
    }

    /** Returns recently active non-protected apps (most-recent-first) for the RAM Manager list. */
    static List<RecentApp> recentHeavyApps(Context ctx, int limit) {
        List<RecentApp> out = new ArrayList<>();
        if (ctx == null || limit <= 0) return out;
        PackageManager pm = ctx.getPackageManager();
        String selfPkg = ctx.getPackageName();
        MediaNowPlaying.State media = MediaNowPlaying.currentState(ctx);
        String activeMediaPkg = (media != null && media.playing) ? media.packageName : null;
        Set<String> protectedA11y = readEnabledAccessibilityPackages(ctx);

        java.util.LinkedHashMap<String, Long> ranked = new java.util.LinkedHashMap<>();
        // 1. UsageStats (accurate system-wide last-used timestamps)
        if (LastOpened.hasUsageStatsPermission(ctx)) {
            try {
                UsageStatsManager usm = (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
                if (usm != null) {
                    long now = System.currentTimeMillis();
                    List<UsageStats> list = usm.queryUsageStats(
                            UsageStatsManager.INTERVAL_DAILY, now - RECENT_WINDOW_MS, now);
                    if (list != null) {
                        list.sort((a, b) -> Long.compare(b.getLastTimeUsed(), a.getLastTimeUsed()));
                        for (UsageStats stat : list) {
                            if (stat != null && stat.getLastTimeUsed() > 0L) {
                                String pkg = stat.getPackageName();
                                if (pkg != null && !ranked.containsKey(pkg)) {
                                    ranked.put(pkg, stat.getLastTimeUsed());
                                }
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        // 2. Home's own LastOpened log
        for (String pkg : LastOpened.recentPackages(ctx, RECENT_WINDOW_MS)) {
            if (!ranked.containsKey(pkg)) {
                ranked.put(pkg, LastOpened.get(ctx, pkg));
            }
        }
        // 3. Fallback: launchable TV apps so the list is always actionable
        if (ranked.size() < limit) {
            try {
                Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
                List<ResolveInfo> resolved = pm.queryIntentActivities(query, 0);
                if (resolved != null) {
                    for (ResolveInfo ri : resolved) {
                        if (ri == null || ri.activityInfo == null) continue;
                        String pkg = ri.activityInfo.packageName;
                        if (!ranked.containsKey(pkg)) ranked.put(pkg, LastOpened.get(ctx, pkg));
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        for (java.util.Map.Entry<String, Long> e : ranked.entrySet()) {
            if (out.size() >= limit) break;
            String pkg = e.getKey();
            if (isProtected(pkg, selfPkg, activeMediaPkg, protectedA11y)) continue;
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                if (!ai.enabled || (ai.flags & ApplicationInfo.FLAG_PERSISTENT) != 0) continue;
                CharSequence cs = pm.getApplicationLabel(ai);
                String label = cs != null && cs.length() > 0 ? cs.toString() : pkg;
                out.add(new RecentApp(pkg, label, e.getValue() != null ? e.getValue() : 0L));
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    private static Set<String> readEnabledAccessibilityPackages(Context ctx) {
        try {
            String enabled = Settings.Secure.getString(
                    ctx.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return parseAccessibilityPackages(enabled);
        } catch (Throwable ignored) {
            return Collections.emptySet();
        }
    }

    private static List<String> readRecentUsagePackages(Context ctx) {
        List<String> out = new ArrayList<>();
        if (!LastOpened.hasUsageStatsPermission(ctx)) return out;
        try {
            UsageStatsManager usm = (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) return out;
            long now = System.currentTimeMillis();
            List<UsageStats> list = usm.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY, now - RECENT_WINDOW_MS, now);
            if (list == null || list.isEmpty()) return out;
            list.sort((a, b) -> Long.compare(b.getLastTimeUsed(), a.getLastTimeUsed()));
            for (UsageStats stat : list) {
                if (stat != null && stat.getLastTimeUsed() > 0L) {
                    String pkg = stat.getPackageName();
                    if (pkg != null && !pkg.isEmpty()) out.add(pkg);
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** Deletes stale temporary files inside cacheDir only (never touches filesDir or active caches). */
    static long purgeStaleTempCache(File dir, long cutoffMs) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return 0L;
        File[] children = dir.listFiles();
        if (children == null) return 0L;
        long reclaimed = 0L;
        for (File f : children) {
            if (f == null) continue;
            if (f.isDirectory()) {
                reclaimed += purgeStaleTempCache(f, cutoffMs);
                File[] remaining = f.listFiles();
                if (remaining != null && remaining.length == 0 && f.lastModified() < cutoffMs) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            } else if (f.lastModified() < cutoffMs) {
                long len = f.length();
                if (f.delete()) reclaimed += Math.max(0L, len);
            }
        }
        return reclaimed;
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
