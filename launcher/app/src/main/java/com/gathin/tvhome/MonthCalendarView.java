package com.gathin.tvhome;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.view.View;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.Locale;

/**
 * A sleek, lightweight full-month calendar view for the TV screensaver.
 * Renders the month/year title, weekday headers, and days grid with today highlighted.
 * Zero external dependencies, pure hardware-accelerated Canvas drawing with no allocations in onDraw().
 */
public final class MonthCalendarView extends View {

    private static final String[] DEFAULT_WEEKDAYS = {"S", "M", "T", "W", "T", "F", "S"};

    private final Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint headerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint weekendPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint todayBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint todayTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF todayRect = new RectF();

    private final String[] weekdayLabels = new String[7];
    private String titleText = "";
    private int daysInMonth;
    private int startColumn;
    private int todayDayOfMonth;

    private float density;
    private float titleY;
    private float headerY;
    private float gridTop;
    private float gridBottom;
    private float colWidth;
    private float rowHeight;
    private float todayPillRadius;

    public MonthCalendarView(Context context) {
        this(context, null);
    }

    public MonthCalendarView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public MonthCalendarView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        Resources res = getResources();
        DisplayMetrics dm = res.getDisplayMetrics();
        density = dm.density;

        titlePaint.setColor(0xFFFFFFFF);
        titlePaint.setTextSize(16f * density);
        titlePaint.setTextAlign(Paint.Align.CENTER);
        titlePaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        titlePaint.setLetterSpacing(0.08f);

        headerPaint.setColor(0x80FFFFFF); // 50% white
        headerPaint.setTextSize(12f * density);
        headerPaint.setTextAlign(Paint.Align.CENTER);
        headerPaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));

        dayPaint.setColor(0xCCFFFFFF); // 80% white
        dayPaint.setTextSize(13f * density);
        dayPaint.setTextAlign(Paint.Align.CENTER);
        dayPaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));

        weekendPaint.setColor(0x80FFFFFF); // 50% white for weekends
        weekendPaint.setTextSize(13f * density);
        weekendPaint.setTextAlign(Paint.Align.CENTER);
        weekendPaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));

        todayBgPaint.setColor(0x4D2B5CFF); // Semi-transparent brand accent blue
        todayBgPaint.setStyle(Paint.Style.FILL);

        todayTextPaint.setColor(0xFFFFFFFF); // 100% white bold
        todayTextPaint.setTextSize(13f * density);
        todayTextPaint.setTextAlign(Paint.Align.CENTER);
        todayTextPaint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));

        todayPillRadius = 14f * density;

        initWeekdayLabels();
        updateCalendarData();
    }

    private void initWeekdayLabels() {
        Locale locale = Locale.getDefault();
        DayOfWeek[] days = {
                DayOfWeek.SUNDAY,
                DayOfWeek.MONDAY,
                DayOfWeek.TUESDAY,
                DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY,
                DayOfWeek.FRIDAY,
                DayOfWeek.SATURDAY
        };
        for (int i = 0; i < 7; i++) {
            try {
                String name = days[i].getDisplayName(TextStyle.NARROW, locale);
                weekdayLabels[i] = (name != null && !name.isEmpty()) ? name.toUpperCase(locale) : DEFAULT_WEEKDAYS[i];
            } catch (Exception e) {
                weekdayLabels[i] = DEFAULT_WEEKDAYS[i];
            }
        }
    }

    /** Recomputes current month parameters (called on start and every tick). */
    public void updateCalendarData() {
        LocalDate today = LocalDate.now();
        int year = today.getYear();
        int month = today.getMonthValue();
        todayDayOfMonth = today.getDayOfMonth();

        Locale locale = Locale.getDefault();
        String monthName = today.getMonth().getDisplayName(TextStyle.FULL, locale).toUpperCase(locale);
        titleText = monthName + " " + year;

        LocalDate firstDay = LocalDate.of(year, month, 1);
        daysInMonth = firstDay.lengthOfMonth();
        // Sunday=0, Monday=1, ..., Saturday=6
        startColumn = firstDay.getDayOfWeek().getValue() % 7;

        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int desiredW = Math.round(280f * density);
        int desiredH = Math.round(220f * density);

        int w = resolveSize(desiredW, widthMeasureSpec);
        int h = resolveSize(desiredH, heightMeasureSpec);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        colWidth = w / 7f;

        // Vertical spacing:
        // Title at ~20dp
        // Headers at ~44dp
        // Grid starts at ~60dp down to h - 6dp
        titleY = 22f * density;
        headerY = 46f * density;
        gridTop = 56f * density;
        gridBottom = h - 6f * density;
        rowHeight = (gridBottom - gridTop) / 6f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        if (w <= 0 || daysInMonth <= 0) return;

        // 1. Draw Month & Year Title
        canvas.drawText(titleText, w / 2f, titleY, titlePaint);

        // 2. Draw Weekday Headers (S M T W T F S)
        for (int col = 0; col < 7; col++) {
            float cx = (col + 0.5f) * colWidth;
            canvas.drawText(weekdayLabels[col], cx, headerY, headerPaint);
        }

        // 3. Draw Days Grid
        Paint.FontMetrics fm = dayPaint.getFontMetrics();
        float verticalOffset = -(fm.ascent + fm.descent) / 2f;

        for (int day = 1; day <= daysInMonth; day++) {
            int index = startColumn + day - 1;
            int col = index % 7;
            int row = index / 7;

            float cx = (col + 0.5f) * colWidth;
            float cy = gridTop + (row + 0.5f) * rowHeight;

            if (day == todayDayOfMonth) {
                // Highlight today with an accent pill background
                float radius = Math.min(todayPillRadius, Math.min(colWidth, rowHeight) * 0.44f);
                todayRect.set(cx - radius, cy - radius, cx + radius, cy + radius);
                canvas.drawRoundRect(todayRect, radius, radius, todayBgPaint);

                canvas.drawText(Integer.toString(day), cx, cy + verticalOffset, todayTextPaint);
            } else {
                boolean isWeekend = (col == 0 || col == 6);
                canvas.drawText(Integer.toString(day), cx, cy + verticalOffset, isWeekend ? weekendPaint : dayPaint);
            }
        }
    }
}
