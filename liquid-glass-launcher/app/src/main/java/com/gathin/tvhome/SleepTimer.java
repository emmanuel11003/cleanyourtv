package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;

/**
 * The Quick Settings sleep timer: each press of the tile steps Off, 15, 30, 45, 60, 90, 120 minutes, and when the
 * time is up the TV goes to standby. One AlarmManager alarm, no service and no polling: nothing runs until it fires.
 * Standby is GLOBAL_ACTION_LOCK_SCREEN through the launcher's own accessibility helper (HomeKeyService or
 * InputKeyService), as the remote's "sleep" action word does; Android has it from 9. Without a bound helper, or
 * below Android 9, the screensaver starts instead, so the TV's own sleep setting can take it from there.
 * A reboot clears the alarm, and the boot count stored with it makes the tile read Off again.
 */
final class SleepTimer extends BroadcastReceiver {

    /** The tile's steps, in minutes; 0 is Off. */
    static final int[] STEPS = {0, 15, 30, 45, 60, 90, 120};

    private static final String PREFS = "sleep_timer";
    private static final String KEY_END = "end_elapsed";
    private static final String KEY_MINUTES = "minutes";
    private static final String KEY_BOOT = "boot";

    /** The step after the one chosen last; after the longest, Off. */
    static int next(int chosen) {
        for (int i = 0; i < STEPS.length - 1; i++) if (STEPS[i] == chosen) return STEPS[i + 1];
        return chosen == STEPS[STEPS.length - 1] ? 0 : STEPS[1];
    }

    /** Whole minutes left, rounded up so the tile never reads 0 while the timer still runs; 0 once it has run out. */
    static int minutesLeft(long endElapsed, long nowElapsed) {
        long ms = endElapsed - nowElapsed;
        return ms <= 0 ? 0 : (int) ((ms + 59_999) / 60_000);
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static int bootCount(Context ctx) {
        return Settings.Global.getInt(ctx.getContentResolver(), Settings.Global.BOOT_COUNT, 0);
    }

    /** Minutes left on a running timer, or 0 when it is off (or was set before the last reboot). */
    static int minutesLeft(Context ctx) {
        SharedPreferences p = prefs(ctx);
        if (p.getInt(KEY_BOOT, -1) != bootCount(ctx)) return 0;
        return minutesLeft(p.getLong(KEY_END, 0), SystemClock.elapsedRealtime());
    }

    /** The step the timer was set to, or 0 when it is off. */
    static int chosen(Context ctx) {
        return minutesLeft(ctx) > 0 ? prefs(ctx).getInt(KEY_MINUTES, 0) : 0;
    }

    /** Starts the timer for this many minutes, replacing any running one; 0 turns it off. */
    static void set(Context ctx, int minutes) {
        AlarmManager alarms = ctx.getSystemService(AlarmManager.class);
        PendingIntent fire = PendingIntent.getBroadcast(ctx, 0, new Intent(ctx, SleepTimer.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        if (minutes <= 0) {
            if (alarms != null) alarms.cancel(fire);
            prefs(ctx).edit().clear().apply();
            return;
        }
        long end = SystemClock.elapsedRealtime() + minutes * 60_000L;
        // A one-minute window: on time without SCHEDULE_EXACT_ALARM, which Android 14 no longer grants by default.
        if (alarms != null) alarms.setWindow(AlarmManager.ELAPSED_REALTIME_WAKEUP, end, 60_000, fire);
        prefs(ctx).edit().putLong(KEY_END, end).putInt(KEY_MINUTES, minutes).putInt(KEY_BOOT, bootCount(ctx)).apply();
    }

    /** The bound accessibility helper that can put the TV in standby, or null. */
    static AccessibilityService helper() {
        if (Build.VERSION.SDK_INT < 28) return null;
        AccessibilityService s = HomeKeyService.bound();
        return s != null ? s : InputKeyService.bound();
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        prefs(ctx).edit().clear().apply();
        AccessibilityService s = helper();
        if (s != null && s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)) return;
        // No helper: the screensaver chosen in the TV's settings, or the launcher's clock (RemoteKeys "screensaver").
        Intent dream = new Intent(Intent.ACTION_MAIN)
                .setClassName("com.android.systemui", "com.android.systemui.Somnambulator");
        if (ctx.getPackageManager().resolveActivity(dream, 0) == null) dream = new Intent(ctx, ClockActivity.class);
        try {
            ctx.startActivity(dream.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException ignored) {
        }
    }
}
