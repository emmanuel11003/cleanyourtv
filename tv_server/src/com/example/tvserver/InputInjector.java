package com.example.tvserver;

import java.lang.reflect.Method;

public class InputInjector {
    private Object inputManagerInstance;
    private Method injectInputEventMethod;
    private boolean reflectionInitialized = false;

    public InputInjector() {
        initReflection();
    }

    private void initReflection() {
        try {
            Class<?> inputManagerClass = Class.forName("android.hardware.input.InputManager");
            Method getInstanceMethod = inputManagerClass.getMethod("getInstance");
            inputManagerInstance = getInstanceMethod.invoke(null);

            Class<?> inputEventClass = Class.forName("android.view.InputEvent");
            injectInputEventMethod = inputManagerClass.getMethod("injectInputEvent", inputEventClass, int.class);
            reflectionInitialized = (inputManagerInstance != null && injectInputEventMethod != null);
            if (reflectionInitialized) {
                System.out.println("[InputInjector] In-memory event reflection initialized.");
            }
        } catch (Throwable t) {
            System.out.println("[InputInjector] Note: Reflection unavailable (" + t.getMessage() + "), using shell input dispatcher fallback.");
            reflectionInitialized = false;
        }
    }

    public void injectKey(int keyCode) {
        if (reflectionInitialized) {
            try {
                long now = System.currentTimeMillis();
                Class<?> keyEventClass = Class.forName("android.view.KeyEvent");
                Object downEvent = keyEventClass.getConstructor(long.class, long.class, int.class, int.class, int.class)
                        .newInstance(now, now, 0 /* ACTION_DOWN */, keyCode, 0);
                Object upEvent = keyEventClass.getConstructor(long.class, long.class, int.class, int.class, int.class)
                        .newInstance(now, now, 1 /* ACTION_UP */, keyCode, 0);

                injectInputEventMethod.invoke(inputManagerInstance, downEvent, 0 /* INJECT_INPUT_EVENT_MODE_ASYNC */);
                injectInputEventMethod.invoke(inputManagerInstance, upEvent, 0);
                return;
            } catch (Throwable ignored) {
            }
        }

        // Fallback to shell command
        runShellAsync("/system/bin/input keyevent " + keyCode);
    }

    public void injectTap() {
        // Center tap on Android TV defaults to KEYCODE_ENTER (66) / DPAD_CENTER (23)
        injectKey(66);
    }

    public void injectSwipe(String direction) {
        if (direction == null) return;
        switch (direction.toLowerCase()) {
            case "up":
                injectKey(19); // KEYCODE_DPAD_UP
                break;
            case "down":
                injectKey(20); // KEYCODE_DPAD_DOWN
                break;
            case "left":
                injectKey(21); // KEYCODE_DPAD_LEFT
                break;
            case "right":
                injectKey(22); // KEYCODE_DPAD_RIGHT
                break;
        }
    }

    public void injectText(String text) {
        if (text == null || text.isEmpty()) return;
        String sanitized = text.replace(" ", "%s").replace("\"", "\\\"");
        runShellAsync("/system/bin/input text \"" + sanitized + "\"");
    }

    private void runShellAsync(final String cmd) {
        new Thread(() -> {
            try {
                Process p = Runtime.getRuntime().exec(cmd);
                p.waitFor();
            } catch (Exception ignored) {
            }
        }).start();
    }
}
