package com.gathin.tvhome;

import org.junit.Test;

import java.util.Calendar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class LiveClockTest {

    @Test
    public void formatClock12HourProminentWithSubscriptSeconds() {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR, 9);
        cal.set(Calendar.AM_PM, Calendar.PM);
        cal.set(Calendar.MINUTE, 42);
        cal.set(Calendar.SECOND, 15);

        LiveClockView.TimeComponents comp = LiveClockView.computeTime(cal, false);
        assertEquals("9:42:15", comp.timeString);
        assertEquals(9, comp.hour);
        assertEquals(42, comp.minute);
        assertEquals(15, comp.second);

        // Seconds start at index 4 (":15") and end at index 7
        assertEquals(4, comp.secStartIndex);
        assertEquals(7, comp.secEndIndex);
    }

    @Test
    public void formatClock24HourLeadingZero() {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 8);
        cal.set(Calendar.MINUTE, 5);
        cal.set(Calendar.SECOND, 9);

        LiveClockView.TimeComponents comp = LiveClockView.computeTime(cal, true);
        assertEquals("08:05:09", comp.timeString);
        assertEquals(8, comp.hour);
        assertEquals(5, comp.minute);
        assertEquals(9, comp.second);

        // Seconds start at index 5 (":09") and end at index 8
        assertEquals(5, comp.secStartIndex);
        assertEquals(8, comp.secEndIndex);
    }

    @Test
    public void formatClockMidnightIn12HourIs12() {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.HOUR, 0);
        cal.set(Calendar.AM_PM, Calendar.AM);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);

        LiveClockView.TimeComponents comp12 = LiveClockView.computeTime(cal, false);
        assertEquals("12:00:00", comp12.timeString);
        assertEquals(12, comp12.hour);

        LiveClockView.TimeComponents comp24 = LiveClockView.computeTime(cal, true);
        assertEquals("00:00:00", comp24.timeString);
        assertEquals(0, comp24.hour);
    }
}
