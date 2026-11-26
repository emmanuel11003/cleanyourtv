package com.gathin.tvhome;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * One-tap RAM optimization for 1-2 GB Android TVs.
 * Safely trims non-essential background app processes without touching system core,
 * TV input services, this launcher, or active media playback.
 */
final class MemoryBooster {
    static final class Snapshot {
        final long usedMb;
        final long totalMb;
        final long freeMb;

        Snapshot(long usedMb, long totalMb) {
            this.totalMb = Math.max(1L, totalMb);
            this.usedMb = Math.max(0L, Math.min(usedMb, this.totalMb));
            this.freeMb = Math.max(0L, this.totalMb - this.usedMb);
        }
    }

    static final class BoostResult {
        final int trimmedApps;
        final long freedMb;
        final Snapshot after;

        BoostResult(int trimmedApps, long freedMb, Snapshot after) {
            this.trimmedApps = trimmedApps;
            this.freedMb = Math.max(0L, freedMb);
            this.after = after;
        }
    }

    private MemoryBooster() {}

    static Snapshot readSnapshot(Context ctx) {
        // Prefer /proc/meminfo (matches StatsText/MemInfo), fallback to ActivityManager.
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/meminfo"))) {
            List<String> lines = new ArrayList<>(12);
            String line;
            for (int i = 0; i < 16 && (line = br.readLine()) != null; i++) {
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

    static boolean isProtected(String pkg, String selfPkg, String activeMediaPkg) {
        if (pkg == null || pkg.isEmpty()) return true;
        if (pkg.equals(selfPkg) || pkg.equals(activeMediaPkg)) return true;
        if ("android".equals(pkg)
                || pkg.startsWith("com.android.systemui")
                || pkg.startsWith("com.android.providers.")
                || pkg.startsWith("com.android.tv.settings")
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

    /** Executes background process trimming on a worker thread and returns reclaimed RAM metrics. */
    static BoostResult boost(Context ctx) {
        Snapshot before = readSnapshot(ctx);
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return new BoostResult(0, 0L, before);

        String selfPkg = ctx.getPackageName();
        MediaNowPlaying.State media = MediaNowPlaying.currentState(ctx);
        String activeMediaPkg = (media != null && media.playing) ? media.packageName : null;

        Set<String> targets = new HashSet<>();
        try {
            List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
            if (procs != null) {
                for (ActivityManager.RunningAppProcessInfo proc : procs) {
                    if (proc.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE) {
                        continue;
                    }
                    if (proc.pkgList != null) {
                        for (String p : proc.pkgList) {
                            if (!isProtected(p, selfPkg, activeMediaPkg)) targets.add(p);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        // On Android 8+, getRunningAppProcesses only lists our own process; also sweep non-system user apps.
        PackageManager pm = ctx.getPackageManager();
        try {
            List<ApplicationInfo> installed = pm.getInstalledApplications(0);
            for (ApplicationInfo ai : installed) {
                if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                        && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0) {
                    continue;
                }
                if ((ai.flags & ApplicationInfo.FLAG_STOPPED) != 0) continue;
                if (!isProtected(ai.packageName, selfPkg, activeMediaPkg)) {
                    targets.add(ai.packageName);
                }
            }
        } catch (Throwable ignored) {
        }

        int killed = 0;
        for (String pkg : targets) {
            try {
                am.killBackgroundProcesses(pkg);
                killed++;
            } catch (Throwable ignored) {
            }
        }

        try {
            Thread.sleep(220L);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        Snapshot after = readSnapshot(ctx);
        long freed = Math.max(0L, after.freeMb - before.freeMb);
        return new BoostResult(killed, freed, after);
    }
}
