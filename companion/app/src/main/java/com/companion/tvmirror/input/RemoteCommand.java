package com.companion.tvmirror.input;

import org.json.JSONException;
import org.json.JSONObject;

public class RemoteCommand {
    public static final int KEYCODE_HOME = 3;
    public static final int KEYCODE_BACK = 4;
    public static final int KEYCODE_DPAD_UP = 19;
    public static final int KEYCODE_DPAD_DOWN = 20;
    public static final int KEYCODE_DPAD_LEFT = 21;
    public static final int KEYCODE_DPAD_RIGHT = 22;
    public static final int KEYCODE_ENTER = 66;
    public static final int KEYCODE_VOLUME_UP = 24;
    public static final int KEYCODE_VOLUME_DOWN = 25;
    public static final int KEYCODE_VOLUME_MUTE = 164;
    public static final int KEYCODE_APP_SWITCH = 187;
    public static final int KEYCODE_MENU = 82;

    public static String createHello(String token, String profile, int targetFps) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("t", "hello");
            obj.put("token", token);
            obj.put("profile", profile);
            obj.put("fps", targetFps);
            return obj.toString();
        } catch (JSONException e) {
            return "{\"t\":\"hello\",\"token\":\"" + token + "\"}";
        }
    }

    public static String createKey(int keyCode) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("t", "key");
            obj.put("code", keyCode);
            return obj.toString();
        } catch (JSONException e) {
            return "{\"t\":\"key\",\"code\":" + keyCode + "}";
        }
    }

    public static String createTap() {
        return "{\"t\":\"tap\"}";
    }

    public static String createSwipe(String direction) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("t", "swipe");
            obj.put("dir", direction);
            return obj.toString();
        } catch (JSONException e) {
            return "{\"t\":\"swipe\",\"dir\":\"" + direction + "\"}";
        }
    }

    public static String createText(String value) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("t", "text");
            obj.put("value", value);
            return obj.toString();
        } catch (JSONException e) {
            return "{\"t\":\"text\",\"value\":\"" + value + "\"}";
        }
    }
}
