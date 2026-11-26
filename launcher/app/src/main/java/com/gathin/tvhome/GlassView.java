package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * A translucent glass slab: soft sheen fill, bright top specular, crisp rim and a smooth outer glow.
 * One instance floats over the grid and moves with spring physics between tiles.
 *
 * Pre-bakes the glow, sheen slab, and rim into compact ALPHA_8 masks per tile size so focus moves
 * and accent colour swaps draw 3 GPU-composited quads directly into the main framebuffer with ZERO
 * offscreen FBO switches, ZERO pipeline flushes, and ZERO per-frame round-rect tessellation.
 */
public class GlassView extends View {

    private final Paint glowPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint slabPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint rimPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final RectF fullRect = new RectF();
    private final float density;
    private float radius;
    /** Glow colour as 0xRRGGBB: white normally, a festival's accent or focused app colour when enabled. */
    private int glowRgb = 0xFFFFFF;
    /** Glow strength: the white glow stays faint, a coloured one reads as warm halo light. */
    private int glowAlpha = 0x30;
    private int rimColor = 0xCCFFFFFF;
    private int lastAccentArgb = Integer.MIN_VALUE;
    /** glass_style rim or halo: no sheen and no fill, only the crisp rim (and, for halo, a lighter outer glow). */
    private boolean flat;
    private boolean halo;

    private Bitmap glowMask;
    private Bitmap slabMask;
    private Bitmap rimMask;
    private int cachedW;
    private int cachedH;
    private float cachedRadius = -1f;
    private boolean cachedFlat;
    private boolean masksDirty = true;

    public GlassView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        radius = 20f * density;
        slabPaint.setColor(0xFFFFFFFF);
        rimPaint.setColor(rimColor);
        glowPaint.setColor((glowAlpha << 24) | glowRgb);
    }

    /** Tints the rim and the outer glow; 0 restores the plain white glass. Zero mask rebuilds. */
    void setAccent(int argb) {
        if (argb == lastAccentArgb) return;
        lastAccentArgb = argb;
        if (argb == 0) {
            glowRgb = 0xFFFFFF;
            glowAlpha = 0x30;
            rimColor = 0xCCFFFFFF;
        } else {
            glowRgb = argb & 0xFFFFFF;
            glowAlpha = 0x76;
            // Lift accent towards white so the edge reads as illuminated glass, not opaque paint.
            int r = (((glowRgb >> 16) & 0xFF) + 255 * 2) / 3;
            int g = (((glowRgb >> 8) & 0xFF) + 255 * 2) / 3;
            int b = ((glowRgb & 0xFF) + 255 * 2) / 3;
            rimColor = 0xE6000000 | (r << 16) | (g << 8) | b;
        }
        rimPaint.setColor(rimColor);
        int gAlpha = flat ? glowAlpha / 2 : glowAlpha;
        glowPaint.setColor((gAlpha << 24) | glowRgb);
        invalidate();
    }

    /** One of Config's GLASS_* styles. */
    void setStyle(String style) {
        boolean nextFlat = Config.GLASS_RIM.equals(style) || Config.GLASS_HALO.equals(style);
        boolean nextHalo = Config.GLASS_HALO.equals(style);
        if (nextFlat == flat && nextHalo == halo && !masksDirty) return;
        flat = nextFlat;
        halo = nextHalo;
        int gAlpha = flat ? glowAlpha / 2 : glowAlpha;
        glowPaint.setColor((gAlpha << 24) | glowRgb);
        masksDirty = true;
        invalidate();
    }

    void setRadius(float px) {
        if (Math.abs(px - radius) < 0.25f) return;
        radius = px;
        masksDirty = true;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w != oldw || h != oldh) {
            masksDirty = true;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        releaseMasks();
        masksDirty = true;
    }

    private void releaseMasks() {
        if (glowMask != null) {
            glowMask.recycle();
            glowMask = null;
        }
        if (slabMask != null) {
            slabMask.recycle();
            slabMask = null;
        }
        if (rimMask != null) {
            rimMask.recycle();
            rimMask = null;
        }
    }

    private void ensureMasks(int w, int h) {
        if (!masksDirty && w == cachedW && h == cachedH
                && Math.abs(radius - cachedRadius) < 0.25f && flat == cachedFlat
                && rimMask != null && !rimMask.isRecycled()) {
            return;
        }
        releaseMasks();
        cachedW = w;
        cachedH = h;
        cachedRadius = radius;
        cachedFlat = flat;
        masksDirty = false;
        if (w <= 0 || h <= 0) return;

        float inset = 6f * density;
        RectF r = new RectF(inset, inset, w - inset, h - inset);

        // 1. Outer glow ALPHA_8 mask: 16 smooth quadratic-falloff rings baked once per size.
        glowMask = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8);
        Canvas gc = new Canvas(glowMask);
        Paint gp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        gp.setStyle(Paint.Style.STROKE);
        int rings = 16;
        float step = inset / rings;
        gp.setStrokeWidth(step + 0.65f);
        for (int i = 0; i < rings; i++) {
            float o = step * (i + 0.5f);
            float t = 1f - (float) i / rings;
            // Smooth quadratic falloff produces a softer, band-free optical halo around the card.
            int a = Math.round(255f * (0.35f * t + 0.65f * t * t));
            gp.setColor((a << 24) | 0xFFFFFF);
            gc.drawRoundRect(r.left - o, r.top - o, r.right + o, r.bottom + o, radius + o, radius + o, gp);
        }

        // 2. Translucent glass slab + top 45% specular sheen ALPHA_8 mask.
        if (!flat) {
            slabMask = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8);
            Canvas sc = new Canvas(slabMask);
            Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
            fill.setShader(new LinearGradient(0f, r.top, 0f, r.bottom,
                    0x3DFFFFFF, 0x12FFFFFF, Shader.TileMode.CLAMP));
            sc.drawRoundRect(r, radius, radius, fill);

            Paint spec = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
            spec.setShader(new LinearGradient(0f, r.top, 0f, r.top + r.height() * 0.45f,
                    0x66FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            sc.save();
            sc.clipRect(r.left, r.top, r.right, r.top + r.height() * 0.45f);
            sc.drawRoundRect(r, radius, radius, spec);
            sc.restore();
        }

        // 3. Crisp anti-aliased rim ALPHA_8 mask.
        rimMask = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8);
        Canvas rc = new Canvas(rimMask);
        Paint rp = new Paint(Paint.ANTI_ALIAS_FLAG);
        rp.setStyle(Paint.Style.STROKE);
        rp.setStrokeWidth((flat ? 2f : 1.5f) * density);
        rp.setColor(0xFFFFFFFF);
        rc.drawRoundRect(r, radius, radius, rp);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        ensureMasks(w, h);
        fullRect.set(0f, 0f, w, h);

        if ((!flat || halo) && glowMask != null && !glowMask.isRecycled()) {
            canvas.drawBitmap(glowMask, null, fullRect, glowPaint);
        }
        if (!flat && slabMask != null && !slabMask.isRecycled()) {
            canvas.drawBitmap(slabMask, null, fullRect, slabPaint);
        }
        if (rimMask != null && !rimMask.isRecycled()) {
            canvas.drawBitmap(rimMask, null, fullRect, rimPaint);
        }
    }
}
