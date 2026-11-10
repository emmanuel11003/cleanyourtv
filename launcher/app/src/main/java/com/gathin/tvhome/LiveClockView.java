package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.format.DateFormat;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.util.AttributeSet;
import android.view.Display;
import android.view.View;
import android.widget.TextView;

import java.util.Calendar;

/**
 * Ultra-responsive, zero-allocation live clock for Android TV.
 *
 * Renders prominent hours and minutes accompanied by clean baseline-aligned seconds
 * via RelativeSizeSpan(0.62f) and ForegroundColorSpan.
 *
 * Ticks precisely at wall-clock second boundaries without triggering full-window
 * layout shifts, ghosting, or freezing across sleep/standby cycles.
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
    private final RelativeSizeSpan sizeSpan = new RelativeSizeSpan(0.62f);
    private final StyleSpan boldSpan = new StyleSpan(Typeface.BOLD);
    private final StyleSpan normalSpan = new StyleSpan(Typeface.NORMAL);
    private ForegroundColorSpan colorSpan;
    private int cachedColor;
    private final Calendar calendar = Calendar.getInstance();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean attached;
    private boolean receiverRegistered;
    private DisplayManager displayManager;
    private Boolean cachedIs24 = null;
    private String cachedClockFormat = null;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!attached || getWindowVisibility() != View.VISIBLE) {
                return;
            }
            if (getVisibility() == View.VISIBLE) {
                updateTime();
            }
            long now = System.currentTimeMillis();
            long delay = 1000L - (now % 1000L);
            if (delay <= 0) delay = 1000L;
            handler.postDelayed(this, delay);
        }
    };

    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            cachedIs24 = null;
            if (attached && getWindowVisibility() == View.VISIBLE) {
                updateTime();
                startTicking();
            }
        }
    };

    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayChanged(int displayId) {
            Display d = getDisplay();
            if (d != null && d.getDisplayId() == displayId && d.getState() == Display.STATE_ON) {
                if (attached && getWindowVisibility() == View.VISIBLE) {
                    updateTime();
                    startTicking();
                }
            }
        }

        @Override public void onDisplayAdded(int displayId) {}
        @Override public void onDisplayRemoved(int displayId) {}
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
        registerReceivers();
        startTicking();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        attached = false;
        unregisterReceivers();
        stopTicking();
    }

    private void registerReceivers() {
        if (!receiverRegistered) {
            try {
                IntentFilter filter = new IntentFilter();
                filter.addAction(Intent.ACTION_TIME_TICK);
                filter.addAction(Intent.ACTION_TIME_CHANGED);
                filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
                filter.addAction(Intent.ACTION_SCREEN_ON);
                filter.addAction(Intent.ACTION_USER_PRESENT);
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    getContext().registerReceiver(timeReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
                } else {
                    getContext().registerReceiver(timeReceiver, filter);
                }
                receiverRegistered = true;
            } catch (Exception ignored) {}
        }
        if (displayManager == null) {
            displayManager = (DisplayManager) getContext().getSystemService(Context.DISPLAY_SERVICE);
        }
        if (displayManager != null) {
            try {
                displayManager.registerDisplayListener(displayListener, handler);
            } catch (Exception ignored) {}
        }
    }

    private void unregisterReceivers() {
        if (receiverRegistered) {
            try {
                getContext().unregisterReceiver(timeReceiver);
            } catch (Exception ignored) {}
            receiverRegistered = false;
        }
        if (displayManager != null) {
            try {
                displayManager.unregisterDisplayListener(displayListener);
            } catch (Exception ignored) {}
        }
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (changedView == this) {
            if (visibility == View.VISIBLE && getWindowVisibility() == View.VISIBLE) {
                updateTime();
                startTicking();
            } else {
                stopTicking();
            }
        }
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == View.VISIBLE && getVisibility() == View.VISIBLE) {
            updateTime();
            startTicking();
        } else {
            stopTicking();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (hasWindowFocus && getVisibility() == View.VISIBLE && getWindowVisibility() == View.VISIBLE) {
            updateTime();
            startTicking();
        }
    }

    public void setClockFormat(String mode) {
        this.cachedClockFormat = mode;
        this.cachedIs24 = ClockFormat.is24(getContext(), mode);
        if (attached && getWindowVisibility() == View.VISIBLE) {
            updateTime();
        }
    }

    public void startTicking() {
        handler.removeCallbacks(ticker);
        if (!attached || getWindowVisibility() != View.VISIBLE) {
            return;
        }
        if (getVisibility() == View.VISIBLE) {
            updateTime();
        }
        long now = System.currentTimeMillis();
        long delay = 1000L - (now % 1000L);
        if (delay <= 0) delay = 1000L;
        handler.postDelayed(ticker, delay);
    }

    public void stopTicking() {
        handler.removeCallbacks(ticker);
    }

    public void updateTime() {
        calendar.setTimeInMillis(System.currentTimeMillis());
        if (cachedIs24 == null) {
            try {
                if (cachedClockFormat == null) {
                    cachedClockFormat = Config.load(getContext()).clockFormat;
                }
                cachedIs24 = ClockFormat.is24(getContext(), cachedClockFormat);
            } catch (Exception e) {
                cachedIs24 = DateFormat.is24HourFormat(getContext());
            }
        }
        boolean is24 = cachedIs24 != null ? cachedIs24 : DateFormat.is24HourFormat(getContext());

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

        // 72% opacity matching secondary am/pm styling
        int textColor = getCurrentTextColor();
        if (colorSpan == null || cachedColor != textColor) {
            cachedColor = textColor;
            int alpha = Color.alpha(textColor);
            int subAlpha = Math.round(alpha * 0.72f);
            int subColor = Color.argb(subAlpha, Color.red(textColor), Color.green(textColor), Color.blue(textColor));
            colorSpan = new ForegroundColorSpan(subColor);
        }

        spanBuilder.setSpan(boldSpan, 0, secStart, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        spanBuilder.setSpan(normalSpan, secStart, secEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        spanBuilder.setSpan(sizeSpan, secStart, secEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        spanBuilder.setSpan(colorSpan, secStart, secEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        setText(spanBuilder, BufferType.SPANNABLE);
    }
}
