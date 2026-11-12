package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SleepTimerTest {

    @Test
    public void stepsThroughEveryTimeThenOff() {
        int m = 0;
        int[] expected = {15, 30, 45, 60, 90, 120, 0};
        for (int e : expected) {
            m = SleepTimer.next(m);
            assertEquals(e, m);
        }
    }

    @Test
    public void unknownStepStartsAtTheShortest() {
        assertEquals(15, SleepTimer.next(7));
    }

    @Test
    public void minutesLeftRoundsUpAndStopsAtZero() {
        assertEquals(30, SleepTimer.minutesLeft(30 * 60_000L, 0));
        assertEquals(1, SleepTimer.minutesLeft(1_000, 0));
        assertEquals(30, SleepTimer.minutesLeft(30 * 60_000L, 1));
        assertEquals(0, SleepTimer.minutesLeft(5_000, 5_000));
        assertEquals(0, SleepTimer.minutesLeft(5_000, 9_000));
    }
}
