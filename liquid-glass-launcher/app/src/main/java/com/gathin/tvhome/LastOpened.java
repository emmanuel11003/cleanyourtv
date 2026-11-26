package com.gathin.tvhome;

import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.format.DateUtils;

import java.util.List;
import java.util.Locale;

/**
 * Tracks when an app was last used (using its exit / closed timestamp).
 *
 * 1. When Home or Recents launches an app, it notes the pending active package.
 * 2. When the user exits the app and returns to Home (onResume), the moment of return
 *    is recorded as that app's closed timestamp.
 * 3. When PACKAGE_USAGE_STATS is available, background synchronization reconciles
 *    system-level activity stop/exit times (UsageStats.getLastTimeUsed()), capturing
 *    launches from remote physical buttons, TV inputs, or power standby as well.
 */
final class LastOpened {
    private static final String PREFS = "last_opened";
    private static final String KEY_PENDING_PKG = "_pending_active_pkg";

    private LastOpened() {
    }

    /** Notes that an app has been launched from Home or Recents. */
    static void record(Context ctx, String pkg) {
        if (ctx == null || pkg == null || pkg.isEmpty()) return;
        SharedPreferences p = prefs(ctx);
        SharedPreferences.Editor ed = p.edit().putString(KEY_PENDING_PKG, pkg);
        // If app has never been opened before, set initial baseline timestamp
        if (!p.contains(pkg)) {
            ed.putLong(pkg, System.currentTimeMillis());
        }
        ed.apply();
    }

    /**
     * Called when Home returns to the foreground (onResume).
     * The app that was active before returning is now closed, so its close time is recorded as now.
     */
    static void recordClosed(Context ctx) {
        if (ctx == null) return;
        SharedPreferences p = prefs(ctx);
        String pending = p.getString(KEY_PENDING_PKG, null);
        if (pending != null && !pending.isEmpty()) {
            p.edit()
                    .putLong(pending, System.currentTimeMillis())
                    .remove(KEY_PENDING_PKG)
                    .apply();
        }
        syncFromUsageStatsAsync(ctx);
    }

    /** Records explicit close timestamp for a package. */
    static void recordClosed(Context ctx, String pkg, long timestamp) {
        if (ctx == null || pkg == null || pkg.isEmpty()) return;
        long time = timestamp > 0 ? timestamp : System.currentTimeMillis();
        prefs(ctx).edit().putLong(pkg, time).apply();
    }

    /** At most one usage-stats scan a minute: Home resumes on every Back from an app, and the scan reads 14 days. */
    private static final long SYNC_INTERVAL_MS = 60_000L;
    private static long lastSyncAt;
    private static volatile boolean syncing;

    /** Asynchronously syncs true app exit times from UsageStatsManager when permitted. */
    static void syncFromUsageStatsAsync(Context context) {
        if (context == null || syncing) return;
        long started = android.os.SystemClock.elapsedRealtime();
        if (lastSyncAt != 0 && started - lastSyncAt < SYNC_INTERVAL_MS) return;
        // The application context: the thread must not keep a finished Activity alive.
        final Context ctx = context.getApplicationContext();
        if (!RecentApps.hasUsageStatsPermission(ctx)) return;
        lastSyncAt = started;
        syncing = true;
        Thread t = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            try {
                UsageStatsManager usm = (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
                if (usm == null) return;
                long now = System.currentTimeMillis();
                long start = now - (14 * 24 * 60 * 60 * 1000L); // 14 days
                List<UsageStats> list = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, start, now);
                if (list == null || list.isEmpty()) return;
                SharedPreferences p = prefs(ctx);
                SharedPreferences.Editor editor = null;
                for (UsageStats stat : list) {
                    if (stat == null) continue;
                    String pkg = stat.getPackageName();
                    if (!RecentApps.isValidPackage(pkg, ctx.getPackageName())) continue;
                    long lastClosed = stat.getLastTimeUsed();
                    if (lastClosed > 0) {
                        long current = p.getLong(pkg, 0L);
                        if (lastClosed > current) {
                            if (editor == null) editor = p.edit();
                            editor.putLong(pkg, lastClosed);
                        }
                    }
                }
                if (editor != null) editor.apply();
            } catch (Exception ignored) {
            } finally {
                syncing = false;
            }
        }, "TvHome-UsageStatsSync");
        t.setDaemon(true);
        t.start();
    }

    /** Milliseconds since the epoch, or 0 when Home never opened the app. */
    static long get(Context ctx, String pkg) {
        return pkg == null ? 0L : prefs(ctx).getLong(pkg, 0L);
    }

    /**
     * "5 minutes ago" within the hour, then the day and the time: "today, 11:40 am", "yesterday, 9:40 pm",
     * "3 days ago, 9:40 pm", "Oct 12, 9:40 pm". The parts are Android's own wording in the TV's language.
     */
    static String describe(Context ctx, long when) {
        long now = System.currentTimeMillis();
        // Under a minute Android says "0 minutes ago"; one minute reads better.
        when = Math.min(when, now - DateUtils.MINUTE_IN_MILLIS);
        if (now - when < DateUtils.HOUR_IN_MILLIS) {
            return DateUtils.getRelativeTimeSpanString(when, now, DateUtils.MINUTE_IN_MILLIS).toString();
        }
        String day = DateUtils.getRelativeTimeSpanString(when, now, DateUtils.DAY_IN_MILLIS).toString();
        // It follows "last used" mid-sentence: "yesterday", not "Yesterday". Month names keep their capital.
        if (Locale.getDefault().getLanguage().equals("en") && (day.startsWith("Yesterday") || day.startsWith("Today"))) {
            day = Character.toLowerCase(day.charAt(0)) + day.substring(1);
        }
        return day + ", " + DateUtils.formatDateTime(ctx, when, DateUtils.FORMAT_SHOW_TIME);
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
