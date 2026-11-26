package com.gathin.tvhome;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages recently used applications for CleanYourTV's Recent Apps / App Switcher.
 *
 * Uses launcher-tracked launches as primary reliable source (no special permissions needed).
 * When PACKAGE_USAGE_STATS permission is granted by the user in Settings, optionally queries
 * UsageStatsManager to also capture external app launches (e.g. remote physical hotkeys).
 *
 * All operations are non-blocking, capped to MAX_RECENTS, and ignore the launcher package itself.
 */
public final class RecentApps {
    private static final String PREFS = "recent_apps_store";
    private static final String KEY_ITEMS = "recents_json";
    public static final int MAX_RECENTS = 15;

    public static final class Record {
        public final String pkg;
        public final String cmp;
        public final String lbl;
        public final long time;

        public Record(String pkg, String cmp, String lbl, long time) {
            this.pkg = pkg != null ? pkg : "";
            this.cmp = cmp != null ? cmp : "";
            this.lbl = lbl != null ? lbl : "";
            this.time = time;
        }
    }

    public static final class Item {
        public final String pkg;
        public final String label;
        public final String componentName;
        public final long timestamp;
        public final String relativeTime;
        public final Drawable icon;
        public final Drawable banner;

        public Item(String pkg, String label, String componentName, long timestamp,
                    String relativeTime, Drawable icon, Drawable banner) {
            this.pkg = pkg;
            this.label = label != null && !label.isEmpty() ? label : pkg;
            this.componentName = componentName;
            this.timestamp = timestamp;
            this.relativeTime = relativeTime;
            this.icon = icon;
            this.banner = banner;
        }
    }

    private RecentApps() {}

    /**
     * Checks if a package is valid for inclusion in recents (non-empty, not launcher itself,
     * and not core system packages that lack user-facing launchable UI).
     */
    public static boolean isValidPackage(String pkg, String selfPackage) {
        if (pkg == null) return false;
        String trimmed = pkg.trim();
        if (trimmed.isEmpty()) return false;
        if (trimmed.equals(selfPackage)) return false;
        if ("android".equals(trimmed) || "com.android.systemui".equals(trimmed)) return false;
        return true;
    }

    static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    static String unescape(String s) {
        if (s == null) return "";
        return s.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    static String extractField(String objStr, String key) {
        String pattern = "\"" + key + "\":\"";
        int start = objStr.indexOf(pattern);
        if (start == -1) return null;
        start += pattern.length();
        int end = -1;
        for (int i = start; i < objStr.length(); i++) {
            if (objStr.charAt(i) == '"' && objStr.charAt(i - 1) != '\\') {
                end = i;
                break;
            }
        }
        if (end == -1) return null;
        return unescape(objStr.substring(start, end));
    }

    static long extractLongField(String objStr, String key) {
        String pattern = "\"" + key + "\":";
        int start = objStr.indexOf(pattern);
        if (start == -1) return 0L;
        start += pattern.length();
        int end = start;
        while (end < objStr.length() && (Character.isDigit(objStr.charAt(end)) || objStr.charAt(end) == '-')) {
            end++;
        }
        if (end > start) {
            try {
                return Long.parseLong(objStr.substring(start, end));
            } catch (NumberFormatException ignored) {}
        }
        return 0L;
    }

    public static List<Record> decodeRecords(String text) {
        List<Record> list = new ArrayList<>();
        if (text == null || text.trim().isEmpty() || !text.contains("{")) {
            return list;
        }
        int idx = 0;
        while (idx < text.length()) {
            int start = text.indexOf('{', idx);
            if (start == -1) break;
            int end = text.indexOf('}', start);
            if (end == -1) break;
            String objStr = text.substring(start + 1, end);
            String pkg = extractField(objStr, "pkg");
            if (pkg != null && !pkg.isEmpty()) {
                String cmp = extractField(objStr, "cmp");
                String lbl = extractField(objStr, "lbl");
                long time = extractLongField(objStr, "time");
                list.add(new Record(pkg, cmp != null ? cmp : "", lbl != null ? lbl : "", time));
            }
            idx = end + 1;
        }
        return list;
    }

    public static String encodeRecords(List<Record> list) {
        if (list == null || list.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            Record r = list.get(i);
            if (i > 0) sb.append(",");
            sb.append("{\"pkg\":\"").append(escape(r.pkg)).append("\",")
              .append("\"cmp\":\"").append(escape(r.cmp)).append("\",")
              .append("\"lbl\":\"").append(escape(r.lbl)).append("\",")
              .append("\"time\":").append(r.time).append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * Pure logic to record/update an app in a JSON string array of recents.
     */
    public static String recordJson(String currentRawJson, String pkg, String componentStr, String label,
                                    long timestamp, String selfPkg) {
        if (!isValidPackage(pkg, selfPkg)) {
            return currentRawJson != null ? currentRawJson : "[]";
        }
        String cleanPkg = pkg.trim();
        List<Record> records = decodeRecords(currentRawJson);

        String cmp = componentStr != null ? componentStr.trim() : "";
        String lbl = label != null ? label.trim() : "";

        // Retain existing component or label if not provided in new record
        for (Record r : records) {
            if (cleanPkg.equals(r.pkg)) {
                if (cmp.isEmpty() && !r.cmp.isEmpty()) cmp = r.cmp;
                if (lbl.isEmpty() && !r.lbl.isEmpty()) lbl = r.lbl;
                break;
            }
        }

        List<Record> updated = new ArrayList<>();
        updated.add(new Record(cleanPkg, cmp, lbl, timestamp > 0 ? timestamp : System.currentTimeMillis()));

        for (Record r : records) {
            if (!cleanPkg.equals(r.pkg) && isValidPackage(r.pkg, selfPkg) && updated.size() < MAX_RECENTS) {
                updated.add(r);
            }
        }

        return encodeRecords(updated);
    }

    /**
     * Pure logic to remove a package from a JSON string array of recents.
     */
    public static String dismissJson(String currentRawJson, String pkg) {
        if (pkg == null || currentRawJson == null || currentRawJson.trim().isEmpty()) {
            return currentRawJson;
        }
        String cleanPkg = pkg.trim();
        List<Record> records = decodeRecords(currentRawJson);
        List<Record> updated = new ArrayList<>();
        boolean removed = false;

        for (Record r : records) {
            if (cleanPkg.equals(r.pkg)) {
                removed = true;
            } else {
                updated.add(r);
            }
        }

        return removed ? encodeRecords(updated) : currentRawJson;
    }

    /**
     * Pure logic to prune a list of packages from a JSON string array of recents.
     */
    public static String pruneJson(String currentRawJson, List<String> pkgsToRemove) {
        if (pkgsToRemove == null || pkgsToRemove.isEmpty() || currentRawJson == null || currentRawJson.trim().isEmpty()) {
            return currentRawJson;
        }
        List<Record> records = decodeRecords(currentRawJson);
        List<Record> updated = new ArrayList<>();
        boolean changed = false;

        for (Record r : records) {
            if (pkgsToRemove.contains(r.pkg)) {
                changed = true;
            } else {
                updated.add(r);
            }
        }

        return changed ? encodeRecords(updated) : currentRawJson;
    }

    /**
     * Pure logic to merge a UsageEvent record into an entries map, supplementing launcher history.
     */
    public static void mergeUsageEvent(Map<String, Record> entries, String pkg, String className,
                                       long timestamp, String selfPkg) {
        if (entries == null || !isValidPackage(pkg, selfPkg)) return;
        String cleanPkg = pkg.trim();
        Record existing = entries.get(cleanPkg);
        if (existing == null) {
            String cmp = (className != null && !className.isEmpty()) ? (cleanPkg + "/" + className) : "";
            entries.put(cleanPkg, new Record(cleanPkg, cmp, "", timestamp));
        } else if (existing.time < timestamp) {
            String cmp = (className != null && !className.isEmpty()) ? (cleanPkg + "/" + className) : existing.cmp;
            entries.put(cleanPkg, new Record(cleanPkg, cmp, existing.lbl, timestamp));
        }
    }

    /**
     * Checks whether PACKAGE_USAGE_STATS permission is granted to the launcher.
     */
    public static boolean hasUsageStatsPermission(Context ctx) {
        if (ctx == null) return false;
        try {
            AppOpsManager appOps = (AppOpsManager) ctx.getSystemService(Context.APP_OPS_SERVICE);
            if (appOps == null) return false;
            int mode = appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    ctx.getPackageName()
            );
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Records an app launch in the launcher's recent history.
     */
    public static synchronized void record(Context ctx, String pkg, ComponentName component, String label) {
        if (ctx == null || !isValidPackage(pkg, ctx.getPackageName())) {
            return;
        }
        try {
            SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String raw = prefs.getString(KEY_ITEMS, "[]");
            String componentStr = component != null ? component.flattenToShortString() : "";
            String updated = recordJson(raw, pkg, componentStr, label, System.currentTimeMillis(), ctx.getPackageName());
            prefs.edit().putString(KEY_ITEMS, updated).apply();
        } catch (Exception ignored) {}
    }

    /**
     * Dismisses/removes a package from the recent apps switcher list.
     */
    public static synchronized boolean dismiss(Context ctx, String pkg) {
        if (ctx == null || pkg == null || pkg.trim().isEmpty()) return false;
        try {
            SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String raw = prefs.getString(KEY_ITEMS, "[]");
            String updated = dismissJson(raw, pkg);
            if (updated != null && !updated.equals(raw)) {
                prefs.edit().putString(KEY_ITEMS, updated).apply();
                return true;
            }
            return false;
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Prunes multiple packages (e.g. uninstalled apps) from the persistent history.
     */
    public static synchronized void prune(Context ctx, List<String> pkgs) {
        if (ctx == null || pkgs == null || pkgs.isEmpty()) return;
        try {
            SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String raw = prefs.getString(KEY_ITEMS, "[]");
            String updated = pruneJson(raw, pkgs);
            if (updated != null && !updated.equals(raw)) {
                prefs.edit().putString(KEY_ITEMS, updated).apply();
            }
        } catch (Exception ignored) {}
    }

    /**
     * Clears all recent app entries.
     */
    public static synchronized void clear(Context ctx) {
        if (ctx == null) return;
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
    }

    /**
     * Loads the validated recent applications list.
     */
    public static List<Item> loadRecents(Context ctx) {
        if (ctx == null) return Collections.emptyList();
        PackageManager pm = ctx.getPackageManager();
        Map<String, Record> entriesByPkg = new HashMap<>();

        // 1. Read launcher-recorded apps
        try {
            SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String raw = prefs.getString(KEY_ITEMS, "[]");
            List<Record> saved = decodeRecords(raw);
            for (Record r : saved) {
                if (isValidPackage(r.pkg, ctx.getPackageName()) && !entriesByPkg.containsKey(r.pkg)) {
                    entriesByPkg.put(r.pkg, r);
                }
            }
        } catch (Exception ignored) {}

        // 2. Check UsageStatsManager if user granted permission
        if (hasUsageStatsPermission(ctx)) {
            try {
                UsageStatsManager usm = (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
                if (usm != null) {
                    long now = System.currentTimeMillis();
                    long start = now - (24 * 60 * 60 * 1000L); // 24 hours
                    UsageEvents events = usm.queryEvents(start, now);
                    if (events != null) {
                        UsageEvents.Event event = new UsageEvents.Event();
                        while (events.hasNextEvent()) {
                            events.getNextEvent(event);
                            if (event.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) {
                                mergeUsageEvent(entriesByPkg, event.getPackageName(),
                                        event.getClassName(), event.getTimeStamp(), ctx.getPackageName());
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        // 3. Sort packages by timestamp descending
        List<Record> sortedList = new ArrayList<>(entriesByPkg.values());
        Collections.sort(sortedList, (a, b) -> Long.compare(b.time, a.time));

        // 4. Resolve package info, icons, labels
        List<Item> items = new ArrayList<>();
        List<String> uninstalled = new ArrayList<>();

        for (Record r : sortedList) {
            if (items.size() >= MAX_RECENTS) break;
            String pkg = r.pkg;
            long time = r.time;
            String cmp = r.cmp;
            String savedLabel = r.lbl;

            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                if (!ai.enabled) continue;

                // Ensure it has a launchable intent (Leanback or phone)
                Intent launchIntent = pm.getLeanbackLaunchIntentForPackage(pkg);
                if (launchIntent == null) {
                    launchIntent = pm.getLaunchIntentForPackage(pkg);
                }
                if (launchIntent == null) {
                    // Check if explicit component is valid and exported
                    if (cmp != null && !cmp.isEmpty()) {
                        ComponentName cn = ComponentName.unflattenFromString(cmp);
                        if (cn == null) continue;
                        try {
                            ActivityInfo actInfo = pm.getActivityInfo(cn, 0);
                            if (actInfo == null || !actInfo.exported) continue;
                        } catch (Exception notAnActivity) {
                            continue;
                        }
                    } else {
                        continue;
                    }
                }

                CharSequence labelCs = ai.loadLabel(pm);
                String label = labelCs != null && labelCs.length() > 0 ? labelCs.toString()
                        : (!savedLabel.isEmpty() ? savedLabel : pkg);
                Drawable banner = ai.loadBanner(pm);
                Drawable icon = ai.loadIcon(pm);
                if (banner == null && icon == null) {
                    icon = pm.getDefaultActivityIcon();
                }
                String relativeTime = time > 0 ? LastOpened.describe(ctx, time) : "";

                items.add(new Item(pkg, label, cmp, time, relativeTime, icon, banner));
            } catch (PackageManager.NameNotFoundException ignored) {
                // Uninstalled package, omit from recents and mark for persistent pruning
                uninstalled.add(pkg);
            } catch (Exception ignored) {}
        }

        // Prune uninstalled packages from persistent store so they don't linger
        if (!uninstalled.isEmpty()) {
            prune(ctx, uninstalled);
        }

        return items;
    }

    /**
     * Builds an intent to resume the app's existing task if available, or launch it fresh.
     */
    public static Intent buildResumeIntent(Context ctx, String pkg, String componentString) {
        if (ctx == null || pkg == null || pkg.trim().isEmpty()) return null;
        String cleanPkg = pkg.trim();
        PackageManager pm = ctx.getPackageManager();
        Intent intent = null;

        // 1. Try Leanback launcher intent (standard on Android TV)
        try {
            intent = pm.getLeanbackLaunchIntentForPackage(cleanPkg);
        } catch (Exception ignored) {}

        // 2. Fall back to standard launch intent
        if (intent == null) {
            try {
                intent = pm.getLaunchIntentForPackage(cleanPkg);
            } catch (Exception ignored) {}
        }

        // 3. Fall back to explicit exported component if neither launcher intent exists
        if (intent == null && componentString != null && !componentString.isEmpty()) {
            ComponentName cn = ComponentName.unflattenFromString(componentString);
            if (cn != null && cleanPkg.equals(cn.getPackageName())) {
                try {
                    ActivityInfo ai = pm.getActivityInfo(cn, 0);
                    if (ai != null && ai.exported) {
                        intent = new Intent(Intent.ACTION_MAIN).setComponent(cn);
                    }
                } catch (Exception ignored) {}
            }
        }

        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        }
        return intent;
    }

    /**
     * Builds the package URI string for App Info settings.
     */
    public static String appInfoUri(String pkg) {
        if (pkg == null || pkg.trim().isEmpty()) return null;
        return "package:" + pkg.trim();
    }

    /**
     * Builds an intent to open Android's App Info settings for the given package.
     */
    public static Intent buildAppInfoIntent(String pkg) {
        String uriStr = appInfoUri(pkg);
        if (uriStr == null) return null;
        return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse(uriStr))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /**
     * Checks whether direct application termination is genuinely authorized on this device.
     * On standard Android 14 without elevated permissions (e.g. Shizuku service running with ADB shell UID,
     * or platform signature), standard third-party applications cannot force-stop other apps directly.
     *
     * In accordance with safety rules, we never fake process termination.
     */
    public static boolean canForceStop(Context ctx) {
        // Returns false on standard unprivileged Android 14.
        // If an authorized privileged bridge (e.g. Shizuku) is implemented and active,
        // this would query its binder connection.
        return false;
    }

    /**
     * Attempts to force stop an application if genuine authorization exists.
     * Returns true only if genuine termination was performed.
     */
    public static boolean forceStop(Context ctx, String pkg) {
        if (!canForceStop(ctx) || pkg == null || pkg.trim().isEmpty()) {
            return false;
        }
        return false;
    }
}
