package com.gathin.tvhome;

import android.app.Activity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

/** Reduced motion (config.xml reduced_motion): the one spring and the one time limit every animation uses then. */
final class Motion {

    /** The longest any reduced-motion animation runs, in milliseconds. */
    static final int REDUCED_MS = 90;
    /** Critically damped (no bounce) and stiff enough that any move, even across the screen, settles in 5 frames. */
    static final float REDUCED_STIFFNESS = 15000f;
    /** The spring stops once it is within this many pixels of its target. */
    static final float REDUCED_MIN_CHANGE = 2f;
    private static final DecelerateInterpolator SMOOTH_DECEL = new DecelerateInterpolator(1.5f);

    private Motion() {
    }

    static void reduce(SpringAnimation spring) {
        spring.setSpring(new SpringForce().setStiffness(REDUCED_STIFFNESS).setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY));
        spring.setMinimumVisibleChange(REDUCED_MIN_CHANGE);
    }

    /** Smooth fade focus transition that preserves any existing focus listener and keeps unfocused opacity at 100%. */
    static void attachSmoothFocus(View view, boolean animate, float focusedScale) {
        if (view == null || !animate) return;
        final View.OnFocusChangeListener prev = view.getOnFocusChangeListener();
        view.setOnFocusChangeListener((v, hasFocus) -> {
            if (prev != null) prev.onFocusChange(v, hasFocus);
            v.animate().cancel();
            if (hasFocus) {
                v.setAlpha(0.75f);
                v.animate()
                        .alpha(1f)
                        .setDuration(160)
                        .setInterpolator(SMOOTH_DECEL)
                        .start();
            } else {
                v.setAlpha(1f);
            }
        });
    }

    /** Standard system fade transition between launcher screens and overlays. */
    @SuppressWarnings("deprecation")
    static void applyFadeTransition(Activity activity, boolean animate) {
        if (activity == null) return;
        if (animate) {
            activity.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        } else {
            activity.overridePendingTransition(0, 0);
        }
    }
}
