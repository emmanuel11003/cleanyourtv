package com.companion.tvmirror.data;

import android.content.Context;
import android.content.SharedPreferences;

public class AppSettings {
    private static final String PREF_NAME = "tv_companion_prefs";

    public static final String KEY_TV_IP = "tv_ip";
    public static final String KEY_TV_PORT = "tv_port";
    public static final String KEY_TOKEN = "tv_token";
    public static final String KEY_PROFILE = "stream_profile";
    public static final String KEY_FPS_OVERLAY = "fps_overlay";
    public static final String KEY_HAPTIC = "haptic_enabled";
    public static final String KEY_SENSITIVITY = "touch_sensitivity";
    public static final String KEY_LONG_PRESS = "long_press_action";

    public static final String PROFILE_540P = "540p60";
    public static final String PROFILE_720P = "720p60";
    public static final String PROFILE_1080P = "1080p60";

    public static final String SENS_LOW = "low";
    public static final String SENS_NORMAL = "normal";
    public static final String SENS_HIGH = "high";

    public static final String ACTION_BACK = "back";
    public static final String ACTION_MENU = "menu";

    private final SharedPreferences prefs;

    public AppSettings(Context context) {
        this.prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public String getTvIp() {
        return prefs.getString(KEY_TV_IP, "");
    }

    public void setTvIp(String ip) {
        prefs.edit().putString(KEY_TV_IP, ip).apply();
    }

    public int getTvPort() {
        return prefs.getInt(KEY_TV_PORT, 55555);
    }

    public void setTvPort(int port) {
        prefs.edit().putInt(KEY_TV_PORT, port).apply();
    }

    public String getToken() {
        return prefs.getString(KEY_TOKEN, "123456");
    }

    public void setToken(String token) {
        prefs.edit().putString(KEY_TOKEN, token).apply();
    }

    public String getStreamProfile() {
        return prefs.getString(KEY_PROFILE, PROFILE_720P);
    }

    public void setStreamProfile(String profile) {
        prefs.edit().putString(KEY_PROFILE, profile).apply();
    }

    public boolean isFpsOverlayEnabled() {
        return prefs.getBoolean(KEY_FPS_OVERLAY, true);
    }

    public void setFpsOverlayEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_FPS_OVERLAY, enabled).apply();
    }

    public boolean isHapticEnabled() {
        return prefs.getBoolean(KEY_HAPTIC, true);
    }

    public void setHapticEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_HAPTIC, enabled).apply();
    }

    public String getSensitivity() {
        return prefs.getString(KEY_SENSITIVITY, SENS_NORMAL);
    }

    public void setSensitivity(String sensitivity) {
        prefs.edit().putString(KEY_SENSITIVITY, sensitivity).apply();
    }

    public String getLongPressAction() {
        return prefs.getString(KEY_LONG_PRESS, ACTION_BACK);
    }

    public void setLongPressAction(String action) {
        prefs.edit().putString(KEY_LONG_PRESS, action).apply();
    }
}
