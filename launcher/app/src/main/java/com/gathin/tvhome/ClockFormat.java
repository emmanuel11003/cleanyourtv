package com.gathin.tvhome;

import android.content.Context;
import android.text.format.DateFormat;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextClock;

/** clock_format: "12" or "24" makes the launcher's clocks use that format whatever the TV is set to; else the TV's. */
final class ClockFormat {
    private ClockFormat() {
    }

    /** Applies "12", "24", or restores original system-aware patterns ("system"/anything else). */
    static void apply(View root, String mode) {
        if (root instanceof TextClock) {
            TextClock c = (TextClock) root;
            if (!Boolean.TRUE.equals(c.getTag(R.id.tag_clock_format_saved))) {
                c.setTag(R.id.tag_clock_format_12, c.getFormat12Hour());
                c.setTag(R.id.tag_clock_format_24, c.getFormat24Hour());
                c.setTag(R.id.tag_clock_format_saved, Boolean.TRUE);
            }
            CharSequence orig12 = (CharSequence) c.getTag(R.id.tag_clock_format_12);
            CharSequence orig24 = (CharSequence) c.getTag(R.id.tag_clock_format_24);
            if ("12".equals(mode)) {
                c.setFormat12Hour(orig12);
                c.setFormat24Hour(orig12);
            } else if ("24".equals(mode)) {
                c.setFormat12Hour(orig24);
                c.setFormat24Hour(orig24);
            } else {
                c.setFormat12Hour(orig12);
                c.setFormat24Hour(orig24);
            }
        } else if (root instanceof LiveClockView) {
            ((LiveClockView) root).setClockFormat(mode);
        } else if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) apply(g.getChildAt(i), mode);
        }
    }

    static boolean is24(Context ctx, String mode) {
        return "24".equals(mode) || !"12".equals(mode) && DateFormat.is24HourFormat(ctx);
    }
}
