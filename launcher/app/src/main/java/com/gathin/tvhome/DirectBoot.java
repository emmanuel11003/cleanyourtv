package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.UserManager;

/**
 * Shared direct-boot unlock helper for services that may bind before credential-encrypted storage is ready.
 */
final class DirectBoot {
    private DirectBoot() {}

    static BroadcastReceiver runWhenUnlocked(Context ctx, Runnable onUnlocked) {
        UserManager users = ctx.getSystemService(UserManager.class);
        if (users == null || users.isUserUnlocked()) {
            onUnlocked.run();
            return null;
        }
        final BroadcastReceiver[] ref = new BroadcastReceiver[1];
        ref[0] = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                cancel(ctx, ref[0]);
                ref[0] = null;
                onUnlocked.run();
            }
        };
        ctx.registerReceiver(ref[0], new IntentFilter(Intent.ACTION_USER_UNLOCKED));
        if (users.isUserUnlocked() && ref[0] != null) {
            cancel(ctx, ref[0]);
            ref[0] = null;
            onUnlocked.run();
        }
        return ref[0];
    }

    static void cancel(Context ctx, BroadcastReceiver receiver) {
        if (receiver == null) return;
        try {
            ctx.unregisterReceiver(receiver);
        } catch (RuntimeException ignored) {
        }
    }
}
