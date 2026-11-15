package com.gathin.tvhome;

import androidx.dynamicanimation.animation.SpringForce;
import androidx.dynamicanimation.animation.SpringFrames;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

/** Reduced motion's spring: no bounce, and settled within 5 frames at 60 Hz for any move on a 1080p screen. */
public class MotionTest {

    @Test
    public void reducedSpringSettlesWithinFiveFrames() {
        for (float px : new float[]{50, 150, 300, 600, 1000, 1920}) {
            int frames = SpringFrames.toSettle(Motion.REDUCED_STIFFNESS, SpringForce.DAMPING_RATIO_NO_BOUNCY,
                    Motion.REDUCED_MIN_CHANGE, px);
            assertTrue(px + " px took " + frames + " frames", frames <= 5);
        }
    }

    @Test
    public void reducedAnimationsLastAtMost100ms() {
        assertTrue(Motion.REDUCED_MS <= 100);
    }

    @Test
    public void clampAnimationScaleBoundsAndHandlesNonFinite() {
        org.junit.Assert.assertEquals(1.0f, Config.clampAnimationScale(Float.NaN, 1.0f), 0f);
        org.junit.Assert.assertEquals(1.0f, Config.clampAnimationScale(Float.POSITIVE_INFINITY, 1.0f), 0f);
        org.junit.Assert.assertEquals(0.5f, Config.clampAnimationScale(Float.NEGATIVE_INFINITY, 0.5f), 0f);
        org.junit.Assert.assertEquals(0.0f, Config.clampAnimationScale(-1.5f, 1.0f), 0f);
        org.junit.Assert.assertEquals(0.5f, Config.clampAnimationScale(0.5f, 1.0f), 0f);
        org.junit.Assert.assertEquals(5.0f, Config.clampAnimationScale(9.0f, 1.0f), 0f);
    }
}
