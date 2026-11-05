package com.gathin.tvhome;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.format.DateFormat;
import android.text.style.RelativeSizeSpan;
import android.text.style.SuperscriptSpan;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;

import java.util.Calendar;

/**
 * Ultra-responsive, zero-allocation live clock for Android TV.
 *
 * Renders prominent hours and minutes accompanied by smaller subscript seconds
 * via RelativeSizeSpan(0.6f) and SuperscriptSpan.
 *
 * Ticks precisely at wall-clock second boundaries without triggering full-window
 * layout recalculations or GC churn.
 */
public class LiveClockView extends TextView {

    public static final class TimeComponents {
        public final int hour;
        public final int minute;
        public final int second;
        public final int secStartIndex;
        public final int secEndIndex;
        public final String timeString;

        public TimeComponents(int hour, int minute, int second, int secStartIndex, int secEndIndex, String timeString) {
            this.hour = hour;
            this.minute = minute;
            this.second = second;
            this.secStartIndex = secStartIndex;
            this.secEndIndex = secEndIndex;
            this.timeString = timeString;
        }
    }

    public static TimeComponents computeTime(Calendar cal, boolean is24) {
        int hour = is24 ? cal.get(Calendar.HOUR_OF_DAY) : cal.get(Calendar.HOUR);
        if (!is24 && hour == 0) hour = 12;
        int min = cal.get(Calendar.MINUTE);
        int sec = cal.get(Calendar.SECOND);

        StringBuilder sb = new StringBuilder(10);
        if (is24 && hour < 10) {
            sb.append('0');
        }
        sb.append(hour);
        sb.append(':');
        sb.append((char) ('0' + (min / 10)));
        sb.append((char) ('0' + (min % 10)));

        int secStart = sb.length();
        sb.append(':');
        sb.append((char) ('0' + (sec / 10)));
        sb.append((char) ('0' + (sec % 10)));
        int secEnd = sb.length();

        return new TimeComponents(hour, min, sec, secStart, secEnd, sb.toString());
    }

    private final SpannableStringBuilder spanBuilder = new SpannableStringBuilder();
    private final RelativeSizeSpan sizeSpan = new RelativeSizeSpan(0.6f);
    private final SuperscriptSpan superSpan = new SuperscriptSpan();
    private final Calendar calendar = Calendar.getInstance();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean attached;
    private boolean suppressRelayout;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!attached || getVisibility() != View.VISIBLE) return;
            updateTime();
            long now = System.currentTimeMillis();
            long delay = 1000L - (now % 1000L);
            handler.postDelayed(this, delay);
        }
    };

    public LiveClockView(Context context) {
        this(context, null);
    }

    public LiveClockView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public LiveClockView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setFontFeatureSettings("tnum");
        updateTime();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        startTicking();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        attached = false;
        stopTicking();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == View.VISIBLE && isShown()) {
            startTicking();
        } else {
            stopTicking();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (hasWindowFocus) {
            startTicking();
        } else {
            stopTicking();
        }
    }

    private void startTicking() {
        stopTicking();
        if (attached && getVisibility() == View.VISIBLE) {
            updateTime();
            long now = System.currentTimeMillis();
            long delay = 1000L - (now % 1000L);
            handler.postDelayed(ticker, delay);
        }
    }

    private void stopTicking() {
        handler.removeCallbacks(ticker);
    }

    public void updateTime() {
        calendar.setTimeInMillis(System.currentTimeMillis());
        boolean is24 = DateFormat.is24HourFormat(getContext());
        int hour = is24 ? calendar.get(Calendar.HOUR_OF_DAY) : calendar.get(Calendar.HOUR);
        if (!is24 && hour == 0) hour = 12;
        int min = calendar.get(Calendar.MINUTE);
        int sec = calendar.get(Calendar.SECOND);

        spanBuilder.clear();
        spanBuilder.clearSpans();

        if (is24 && hour < 10) {
            spanBuilder.append('0');
        }
        spanBuilder.append(Integer.toString(hour));
        spanBuilder.append(':');
        spanBuilder.append((char) ('0' + (min / 10)));
        spanBuilder.append((char) ('0' + (min % 10)));

        int secStart = spanBuilder.length();
        spanBuilder.append(':');
        spanBuilder.append((char) ('0' + (sec / 10)));
        spanBuilder.append((char) ('0' + (sec % 10)));
        int secEnd = spanBuilder.length();

        spanBuilder.setSpan(sizeSpan, secStart, secEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        spanBuilder.setSpan(superSpan, secStart, secEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        suppressRelayout = (getWidth() > 0 && getHeight() > 0);
        try {
            setText(spanBuilder, BufferType.SPANNABLE);
        } finally {
            suppressRelayout = false;
        }
    }

    @Override
    public void requestLayout() {
        if (suppressRelayout) {
            // Prevent full-window layout recalculations on every second tick
            return;
        }
        super.requestLayout();
    }
}
