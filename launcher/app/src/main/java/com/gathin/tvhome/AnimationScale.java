package com.gathin.tvhome;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
import android.provider.Settings;

/**
 * Enforces system animation scales across TV reboots when WRITE_SECURE_SETTINGS is granted.
 */
final class AnimationScale {
    private AnimationScale() {
    }

    static void enforce(Context context, float targetScale) {
        if (targetScale <= 0f) return;
        if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                != PackageManager.PERMISSION_GRANTED) return;
        AsyncTask.THREAD_POOL_EXECUTOR.execute(() -> {
            try {
                ContentResolver cr = context.getContentResolver();
                float win = Settings.Global.getFloat(cr, Settings.Global.WINDOW_ANIMATION_SCALE, 1f);
                if (Math.abs(win - targetScale) > 0.01f) {
                    Settings.Global.putFloat(cr, Settings.Global.WINDOW_ANIMATION_SCALE, targetScale);
                }
                float trans = Settings.Global.getFloat(cr, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f);
                if (Math.abs(trans - targetScale) > 0.01f) {
                    Settings.Global.putFloat(cr, Settings.Global.TRANSITION_ANIMATION_SCALE, targetScale);
                }
                float anim = Settings.Global.getFloat(cr, Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
                if (Math.abs(anim - targetScale) > 0.01f) {
                    Settings.Global.putFloat(cr, Settings.Global.ANIMATOR_DURATION_SCALE, targetScale);
                }
            } catch (Exception ignored) {
            }
        });
    }
}
