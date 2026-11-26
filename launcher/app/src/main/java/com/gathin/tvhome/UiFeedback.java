package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.view.View;

/**
 * Lightweight tactile focus feedback: optional system navigation/click sound effects
 * (zero bundled audio assets) and focus tilt preference.
 */
final class UiFeedback {
    static final String KEY_UI_SOUNDS = "ui_focus_sounds";

    private static volatile boolean soundsEnabled;

    private UiFeedback() {}

    static void refresh(Context ctx) {
        if (ctx == null) return;
        SharedPreferences prefs = ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        soundsEnabled = prefs.getBoolean(KEY_UI_SOUNDS, false);
    }

    static boolean isSoundsEnabled(Context ctx) {
        if (ctx == null) return soundsEnabled;
        return ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_UI_SOUNDS, false);
    }

    static boolean isTiltEnabled(Context ctx) {
        return false;
    }

    static boolean isTiltEnabledCached() {
        return false;
    }

    static void playFocus(Context ctx) {
        if (!soundsEnabled || ctx == null) return;
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) am.playSoundEffect(AudioManager.FX_FOCUS_NAVIGATION_RIGHT, 0.35f);
        } catch (Throwable ignored) {
        }
    }

    static void playClick(View v) {
        if (!soundsEnabled || v == null) return;
        try {
            AudioManager am = (AudioManager) v.getContext().getSystemService(Context.AUDIO_SERVICE);
            if (am != null) am.playSoundEffect(AudioManager.FX_KEY_CLICK, 0.45f);
        } catch (Throwable ignored) {
        }
    }
}
