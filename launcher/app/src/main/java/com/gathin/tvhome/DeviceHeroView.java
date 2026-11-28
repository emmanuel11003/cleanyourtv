package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

/**
 * Lightweight, zero-allocation-per-draw vector illustration of the CleanYourTV streaming hardware box,
 * matching the Apple TV Settings left-pane hardware showcase.
 */
public final class DeviceHeroView extends View {
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint skirtPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint topPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF shadowRect = new RectF();
    private final RectF skirtRect = new RectF();
    private final RectF topRect = new RectF();
    private float cornerRadius;
    private float textX, textY;

    public DeviceHeroView(Context context) {
        this(context, null);
    }

    public DeviceHeroView(Context context, AttributeSet attrs) {
        super(context, attrs);
        rimPaint.setStyle(Paint.Style.STROKE);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setColor(0xFF9095A1);
        textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        textPaint.setLetterSpacing(0.015f);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        // Cache vector box on a GPU texture so D-pad focus moves never re-shade the gradients on mt5896.
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w <= 0 || h <= 0) return;
        float side = Math.min(w * 0.68f, h * 0.68f);
        float left = (w - side) * 0.5f;
        float top = (h - side) * 0.48f;
        cornerRadius = side * 0.22f;

        topRect.set(left, top, left + side, top + side);
        float depth = side * 0.08f;
        skirtRect.set(left + side * 0.01f, top + depth, left + side * 0.99f, top + side + depth);

        float shadowSpread = side * 0.14f;
        shadowRect.set(left - shadowSpread, top + side * 0.18f,
                left + side + shadowSpread, top + side + depth + shadowSpread * 1.1f);

        shadowPaint.setShader(new RadialGradient(
                w * 0.5f, top + side * 0.64f, side * 0.72f,
                new int[]{0xC0050608, 0x6007080B, 0x0007080B},
                new float[]{0.35f, 0.72f, 1.0f},
                Shader.TileMode.CLAMP));

        skirtPaint.setShader(new LinearGradient(
                skirtRect.left, skirtRect.top, skirtRect.right, skirtRect.bottom,
                new int[]{0xFF0A0B0E, 0xFF16181E, 0xFF090A0D},
                new float[]{0f, 0.5f, 1f},
                Shader.TileMode.CLAMP));

        topPaint.setShader(new LinearGradient(
                topRect.left, topRect.top, topRect.right, topRect.bottom,
                new int[]{0xFF33363F, 0xFF23262D, 0xFF191B21},
                new float[]{0f, 0.55f, 1f},
                Shader.TileMode.CLAMP));

        float d = getResources().getDisplayMetrics().density;
        rimPaint.setStrokeWidth(Math.max(1f, 1.25f * d));
        rimPaint.setShader(new LinearGradient(
                topRect.left, topRect.top, topRect.right, topRect.bottom,
                new int[]{0x66FFFFFF, 0x22FFFFFF, 0x10000000},
                new float[]{0f, 0.5f, 1f},
                Shader.TileMode.CLAMP));

        textPaint.setTextSize(side * 0.105f);
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        textX = topRect.centerX();
        textY = topRect.centerY() - (fm.ascent + fm.descent) * 0.5f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (topRect.isEmpty()) return;
        canvas.drawOval(shadowRect, shadowPaint);
        canvas.drawRoundRect(skirtRect, cornerRadius, cornerRadius, skirtPaint);
        canvas.drawRoundRect(topRect, cornerRadius, cornerRadius, topPaint);
        canvas.drawRoundRect(topRect, cornerRadius, cornerRadius, rimPaint);
        canvas.drawText("CleanYourTV", textX, textY, textPaint);
    }
}
