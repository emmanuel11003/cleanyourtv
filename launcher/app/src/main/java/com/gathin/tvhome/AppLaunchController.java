package com.gathin.tvhome;

import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;

/**
 * Coordinates animation-first app launches.
 *
 * Sequence:
 *   1. Remote OK -> immediate visual feedback & debounce protection.
 *   2. Lightweight hardware-accelerated tile scale animation (120ms).
 *   3. Launch request sent after tile animation completes cleanly.
 *
 * Prevents concurrent launcher animations during target app cold start,
 * and handles cancellation (Back key, pause, lifecycle) safely.
 */
public final class AppLaunchController {

    public static final int LAUNCH_ANIMATION_MS = 140;
    public static final float LAUNCH_SCALE_MULTIPLIER = 1.045f;
    public static final long LAUNCH_TIMEOUT_MS = 2000L;

    public interface LaunchCallback {
        void onLaunchReady(AppAdapter.Entry entry, boolean animated);
    }

    private boolean launching;
    private long launchStartTime;
    private boolean cancelled;
    private View activeView;
    private AppAdapter.Entry activeEntry;
    private float activeNormalScale = 1.0f;
    private int originalLayerType = View.LAYER_TYPE_NONE;

    public boolean isLaunching() {
        if (launching && (System.currentTimeMillis() - launchStartTime) > LAUNCH_TIMEOUT_MS) {
            onLaunchTimeout();
            return false;
        }
        return launching;
    }

    /**
     * Requests to launch an entry.
     * Returns true if the launch request was accepted, false if ignored (e.g. duplicate press).
     */
    public boolean requestLaunch(AppAdapter.Entry entry, View view, boolean animations,
                                 boolean reducedMotion, float normalScale, LaunchCallback callback) {
        if (isLaunching()) {
            return false;
        }

        launching = true;
        launchStartTime = System.currentTimeMillis();
        cancelled = false;
        activeEntry = entry;
        activeNormalScale = normalScale;
        originalLayerType = view != null ? view.getLayerType() : View.LAYER_TYPE_NONE;

        // If reduced motion is requested, headless, or animations are disabled, launch immediately without delay
        if (view == null || !animations || reducedMotion) {
            activeView = null;
            if (callback != null) {
                callback.onLaunchReady(entry, false);
            }
            return true;
        }

        activeView = view;
        float targetScale = normalScale * LAUNCH_SCALE_MULTIPLIER;

        view.animate().cancel();
        view.animate()
                .scaleX(targetScale)
                .scaleY(targetScale)
                .setDuration(LAUNCH_ANIMATION_MS)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .withLayer()
                .withEndAction(() -> {
                    if (cancelled) {
                        return;
                    }
                    if (callback != null) {
                        callback.onLaunchReady(entry, true);
                    }
                })
                .start();

        return true;
    }

    /**
     * Cancels an in-flight launch animation (e.g. Back key pressed during transition).
     * Returns true if an active launch was cancelled.
     */
    public boolean cancelLaunch() {
        if (!launching) {
            return false;
        }

        cancelled = true;
        launching = false;

        if (activeView != null) {
            activeView.animate().cancel();
            activeView.setLayerType(originalLayerType, null);
            activeView.animate()
                    .scaleX(activeNormalScale)
                    .scaleY(activeNormalScale)
                    .setDuration(70)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
            activeView = null;
        }
        activeEntry = null;
        return true;
    }

    /**
     * Watchdog recovery if the system failed to deliver an onPause or lifecycle event.
     */
    public void onLaunchTimeout() {
        launching = false;
        cancelled = true;
        if (activeView != null) {
            activeView.animate().cancel();
            activeView.setLayerType(originalLayerType, null);
            activeView.setScaleX(activeNormalScale);
            activeView.setScaleY(activeNormalScale);
            activeView = null;
        }
        activeEntry = null;
    }

    /**
     * Clears the launch lock and restores tile animation to normal scale immediately.
     * Used when an operation completes (such as TV input actions or internal actions)
     * without pausing the host activity.
     */
    public void reset() {
        launching = false;
        cancelled = false;
        if (activeView != null) {
            activeView.animate().cancel();
            activeView.setLayerType(originalLayerType, null);
            activeView.setScaleX(activeNormalScale);
            activeView.setScaleY(activeNormalScale);
            activeView = null;
        }
        activeEntry = null;
    }

    /**
     * Called when the activity is paused (e.g. after successful startActivity or home pressed).
     */
    public void onActivityPaused() {
        cancelled = true;
        launching = false;
        if (activeView != null) {
            activeView.animate().cancel();
            activeView.setLayerType(originalLayerType, null);
            activeView.setScaleX(activeNormalScale);
            activeView.setScaleY(activeNormalScale);
            activeView = null;
        }
        activeEntry = null;
    }

    /**
     * Called when the activity is resumed.
     */
    public void onActivityResumed() {
        cancelled = false;
        launching = false;
        if (activeView != null) {
            activeView.animate().cancel();
            activeView.setLayerType(originalLayerType, null);
            activeView.animate()
                    .scaleX(activeNormalScale)
                    .scaleY(activeNormalScale)
                    .setDuration(180)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
            activeView = null;
        }
        activeEntry = null;
    }

    /**
     * Called if startActivity threw an exception.
     */
    public void onLaunchFailed() {
        launching = false;
        cancelled = true;
        if (activeView != null) {
            activeView.animate().cancel();
            activeView.setLayerType(originalLayerType, null);
            activeView.setScaleX(activeNormalScale);
            activeView.setScaleY(activeNormalScale);
            activeView = null;
        }
        activeEntry = null;
    }
}
