package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

public class AppLaunchControllerTest {

    @Test
    public void immediateLaunchWhenViewNull() {
        AppLaunchController controller = new AppLaunchController();
        boolean[] launched = {false};
        boolean[] wasAnimated = {true};

        boolean accepted = controller.requestLaunch(null, null, true, false, 1.0f,
                (entry, animated) -> {
                    launched[0] = true;
                    wasAnimated[0] = animated;
                });

        assertTrue("Launch should be accepted", accepted);
        assertTrue("Callback should be invoked immediately", launched[0]);
        assertFalse("Should not be animated when view is null", wasAnimated[0]);
        assertTrue("Controller should remain in launching state until pause or failure", controller.isLaunching());
    }

    @Test
    public void immediateLaunchWhenReducedMotionActive() {
        AppLaunchController controller = new AppLaunchController();
        boolean[] launched = {false};
        boolean[] wasAnimated = {true};

        boolean accepted = controller.requestLaunch(null, null, true, true, 1.0f,
                (entry, animated) -> {
                    launched[0] = true;
                    wasAnimated[0] = animated;
                });

        assertTrue("Launch should be accepted", accepted);
        assertTrue("Callback should be invoked immediately", launched[0]);
        assertFalse("Should bypass animation when reduced motion is requested", wasAnimated[0]);
    }

    @Test
    public void debounceProtectionPreventsDuplicateLaunches() {
        AppLaunchController controller = new AppLaunchController();
        int[] launchCount = {0};

        boolean first = controller.requestLaunch(null, null, false, false, 1.0f,
                (entry, animated) -> launchCount[0]++);
        boolean second = controller.requestLaunch(null, null, false, false, 1.0f,
                (entry, animated) -> launchCount[0]++);
        boolean third = controller.requestLaunch(null, null, false, false, 1.0f,
                (entry, animated) -> launchCount[0]++);

        assertTrue("First request should be accepted", first);
        assertFalse("Second request should be rejected by debounce guard", second);
        assertFalse("Third request should be rejected by debounce guard", third);
        assertEquals("Callback should only execute exactly once", 1, launchCount[0]);
    }

    @Test
    public void cancelLaunchResetsStateAndAllowsNewLaunch() {
        AppLaunchController controller = new AppLaunchController();

        controller.requestLaunch(null, null, false, false, 1.0f, (entry, animated) -> {});
        assertTrue(controller.isLaunching());

        boolean cancelled = controller.cancelLaunch();
        assertTrue("Cancel should succeed on active launch", cancelled);
        assertFalse("Controller should no longer be launching", controller.isLaunching());

        boolean secondCancelled = controller.cancelLaunch();
        assertFalse("Second cancel should return false when no launch active", secondCancelled);

        int[] count = {0};
        boolean accepted = controller.requestLaunch(null, null, false, false, 1.0f,
                (entry, animated) -> count[0]++);
        assertTrue("New launch should be accepted after cancellation", accepted);
        assertEquals(1, count[0]);
    }

    @Test
    public void activityPauseResetsActiveState() {
        AppLaunchController controller = new AppLaunchController();

        controller.requestLaunch(null, null, false, false, 1.0f, (entry, animated) -> {});
        assertTrue(controller.isLaunching());

        controller.onActivityPaused();
        assertFalse("onActivityPaused should reset launching state", controller.isLaunching());

        int[] count = {0};
        boolean accepted = controller.requestLaunch(null, null, false, false, 1.0f,
                (entry, animated) -> count[0]++);
        assertTrue("Launch should be accepted after resume", accepted);
        assertEquals(1, count[0]);
    }

    @Test
    public void launchFailureRecoversState() {
        AppLaunchController controller = new AppLaunchController();

        controller.requestLaunch(null, null, false, false, 1.0f, (entry, animated) -> {});
        assertTrue(controller.isLaunching());

        controller.onLaunchFailed();
        assertFalse("onLaunchFailed should reset launching state", controller.isLaunching());

        int[] count = {0};
        boolean accepted = controller.requestLaunch(null, null, false, false, 1.0f,
                (entry, animated) -> count[0]++);
        assertTrue("Launch should be accepted after failure recovery", accepted);
        assertEquals(1, count[0]);
    }

    @Test
    public void animationScaleMultiplierCalculation() {
        float normalFocused = 1.10f;
        float target = normalFocused * AppLaunchController.LAUNCH_SCALE_MULTIPLIER;
        assertEquals(1.1495f, target, 0.001f);

        float advancedFocused = 1.12f;
        float targetAdv = advancedFocused * AppLaunchController.LAUNCH_SCALE_MULTIPLIER;
        assertEquals(1.1704f, targetAdv, 0.001f);
    }

    @Test
    public void watchdogRecoversWhenLaunchTimesOut() {
        AppLaunchController controller = new AppLaunchController();

        boolean accepted = controller.requestLaunch(null, null, false, false, 1.0f, (entry, animated) -> {});
        assertTrue("Initial launch should be accepted", accepted);
        assertTrue("Controller should report launching", controller.isLaunching());

        // Explicit invocation of timeout handler
        controller.onLaunchTimeout();
        assertFalse("Controller should no longer report launching after timeout recovery", controller.isLaunching());

        int[] count = {0};
        boolean secondAccepted = controller.requestLaunch(null, null, false, false, 1.0f,
                (entry, animated) -> count[0]++);
        assertTrue("Subsequent launch must succeed after timeout recovery", secondAccepted);
        assertEquals(1, count[0]);
    }

    @Test
    public void activityResumeResetsActiveState() {
        AppLaunchController controller = new AppLaunchController();

        controller.requestLaunch(null, null, false, false, 1.0f, (entry, animated) -> {});
        assertTrue(controller.isLaunching());

        controller.onActivityResumed();
        assertFalse("onActivityResumed should reset launching state", controller.isLaunching());

        int[] count = {0};
        boolean accepted = controller.requestLaunch(null, null, false, false, 1.0f,
                (entry, animated) -> count[0]++);
        assertTrue("Launch should be accepted after resume", accepted);
        assertEquals(1, count[0]);
    }

    @Test
    public void repeatedFailuresCleanUpAndAllowRetry() {
        AppLaunchController controller = new AppLaunchController();
        int[] attempts = {0};

        for (int i = 0; i < 3; i++) {
            boolean accepted = controller.requestLaunch(null, null, false, false, 1.0f,
                    (entry, animated) -> attempts[0]++);
            assertTrue("Attempt " + i + " should be accepted", accepted);
            assertTrue("Should be launching during attempt", controller.isLaunching());
            controller.onLaunchFailed();
            assertFalse("Debounce guard must clear after failure", controller.isLaunching());
        }
        assertEquals("Each attempted launch should have executed its callback", 3, attempts[0]);
    }

    @Test
    public void resetClearsLaunchingAndRestoresStateImmediately() {
        AppLaunchController controller = new AppLaunchController();
        boolean[] launched = {false};

        boolean accepted = controller.requestLaunch(null, null, false, false, 1.0f,
                (entry, animated) -> launched[0] = true);
        assertTrue(accepted);
        assertTrue(launched[0]);
        assertTrue("Controller should report launching", controller.isLaunching());

        // Input action or synchronous completion resets the controller
        controller.reset();
        assertFalse("Controller must no longer report launching after reset", controller.isLaunching());

        // Verify a subsequent tile launch succeeds immediately
        boolean[] secondLaunched = {false};
        boolean secondAccepted = controller.requestLaunch(null, null, false, false, 1.0f,
                (entry, animated) -> secondLaunched[0] = true);
        assertTrue("Subsequent launch must succeed after reset", secondAccepted);
        assertTrue("Subsequent callback must execute", secondLaunched[0]);
    }

    @Test
    public void cancelLaunchAllowsImmediateSubsequentTileOpen() {
        AppLaunchController controller = new AppLaunchController();

        boolean first = controller.requestLaunch(null, null, false, false, 1.0f, (entry, animated) -> {});
        assertTrue(first);
        assertTrue(controller.isLaunching());

        boolean cancelled = controller.cancelLaunch();
        assertTrue("Launch should be cancelled", cancelled);
        assertFalse("Controller must not be launching after cancel", controller.isLaunching());

        int[] count = {0};
        boolean second = controller.requestLaunch(null, null, false, false, 1.0f,
                (entry, animated) -> count[0]++);
        assertTrue("New tile launch must be accepted immediately after cancellation", second);
        assertEquals(1, count[0]);
    }
}
