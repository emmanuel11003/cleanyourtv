package com.gathin.tvhome;

import android.app.Activity;
import android.os.Build;
import android.view.Display;
import android.view.Window;
import android.view.WindowManager;

/**
 * Locks every launcher window to a constant 60 Hz display refresh rate when supported by the TV.
 * Prevents Android TV / Google TV panels from lingering in 24/25/30/50 Hz video modes or fluctuating
 * refresh rates while navigating Home, Quick Settings, Settings, or overlays.
 */
final class FrameRateLock {
    static final float TARGET_FPS = 60.0f;

    private FrameRateLock() {
    }

    /**
     * Selects the best 60 Hz mode matching the active physical resolution (width x height).
     * Returns the index into {@code rates}, or -1 if no suitable mode exists.
     */
    static int pick60HzModeIndex(int activeW, int activeH, int[] widths, int[] heights, float[] rates) {
        if (widths == null || heights == null || rates == null) return -1;
        int n = Math.min(widths.length, Math.min(heights.length, rates.length));
        int bestExact = -1;
        float bestExactDelta = Float.MAX_VALUE;
        int bestFallback = -1;
        float bestFallbackRate = -1f;
        for (int i = 0; i < n; i++) {
            if (activeW > 0 && activeH > 0 && (widths[i] != activeW || heights[i] != activeH)) {
                continue;
            }
            float fps = rates[i];
            if (!Float.isFinite(fps) || fps <= 0f) continue;
            float delta = Math.abs(fps - TARGET_FPS);
            if (delta <= 1.2f) {
                if (delta < bestExactDelta) {
                    bestExactDelta = delta;
                    bestExact = i;
                }
            } else if (fps <= TARGET_FPS + 1.2f && fps > bestFallbackRate) {
                bestFallbackRate = fps;
                bestFallback = i;
            }
        }
        return bestExact >= 0 ? bestExact : bestFallback;
    }

    @SuppressWarnings("deprecation")
    static void apply(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        Window window = activity.getWindow();
        if (window == null) return;
        try {
            Display display = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    ? activity.getDisplay()
                    : activity.getWindowManager().getDefaultDisplay();
            WindowManager.LayoutParams lp = window.getAttributes();
            if (lp == null) return;

            int desiredModeId = lp.preferredDisplayModeId;
            if (display != null) {
                Display.Mode active = display.getMode();
                Display.Mode[] modes = display.getSupportedModes();
                if (active != null && modes != null && modes.length > 1) {
                    int[] ws = new int[modes.length];
                    int[] hs = new int[modes.length];
                    float[] rs = new float[modes.length];
                    for (int i = 0; i < modes.length; i++) {
                        ws[i] = modes[i].getPhysicalWidth();
                        hs[i] = modes[i].getPhysicalHeight();
                        rs[i] = modes[i].getRefreshRate();
                    }
                    int idx = pick60HzModeIndex(active.getPhysicalWidth(), active.getPhysicalHeight(), ws, hs, rs);
                    if (idx >= 0) {
                        desiredModeId = modes[idx].getModeId();
                    }
                }
            }

            boolean changed = false;
            if (desiredModeId > 0 && lp.preferredDisplayModeId != desiredModeId) {
                lp.preferredDisplayModeId = desiredModeId;
                changed = true;
            }
            if (Math.abs(lp.preferredRefreshRate - TARGET_FPS) > 0.01f) {
                lp.preferredRefreshRate = TARGET_FPS;
                changed = true;
            }
            if (changed) {
                window.setAttributes(lp);
            }
        } catch (Throwable ignored) {
        }
    }
}
