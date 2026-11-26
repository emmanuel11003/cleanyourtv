package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * Clean 6.3-style frosted glass drawable matching {@link DockDecoration} and {@code pill_bg}:
 * light translucent top-to-bottom sheen (#2EFFFFFF -> #14FFFFFF) with a crisp 1dp #38FFFFFF border,
 * and solid #F5FFFFFF focus highlight for stateful pills.
 */
final class AdaptiveGlassDrawable extends Drawable {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final RectF rect = new RectF();
    private final float density;
    private final float radius;
    private final boolean oval;
    private final boolean stateful;
    private boolean focused;
    private LinearGradient unfocusedShader;
    private int shaderTop = Integer.MIN_VALUE;
    private int shaderBottom = Integer.MIN_VALUE;

    AdaptiveGlassDrawable(int initialAmbientColor, float density, float radiusPx, boolean oval, boolean stateful) {
        this.density = density;
        this.radius = radiusPx;
        this.oval = oval;
        this.stateful = stateful;
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(density);
        rim.setColor(0x38FFFFFF);
    }

    @Override
    public boolean isStateful() {
        return stateful;
    }

    @Override
    protected boolean onStateChange(int[] state) {
        if (!stateful) return false;
        boolean next = false;
        if (state != null) {
            for (int s : state) {
                if (s == android.R.attr.state_focused || s == android.R.attr.state_pressed) {
                    next = true;
                    break;
                }
            }
        }
        if (next == focused) return false;
        focused = next;
        rebuildShaders(getBounds());
        invalidateSelf();
        return true;
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);
        rect.set(bounds);
        float halfStroke = 0.5f * density;
        rect.inset(halfStroke, halfStroke);
        rebuildShaders(bounds);
    }

    private void rebuildShaders(Rect bounds) {
        if (bounds.width() <= 0 || bounds.height() <= 0) return;
        if (focused) {
            fill.setShader(null);
            fill.setColor(0xF5FFFFFF);
            rim.setColor(0xFFFFFFFF);
        } else {
            fill.setColor(0xFFFFFFFF);
            if (unfocusedShader == null || shaderTop != bounds.top || shaderBottom != bounds.bottom) {
                shaderTop = bounds.top;
                shaderBottom = bounds.bottom;
                unfocusedShader = new LinearGradient(bounds.left, bounds.top, bounds.left, bounds.bottom,
                        0x2EFFFFFF, 0x14FFFFFF, Shader.TileMode.CLAMP);
            }
            fill.setShader(unfocusedShader);
            rim.setColor(0x38FFFFFF);
        }
    }

    @Override
    public void draw(Canvas canvas) {
        float r = oval ? Math.min(rect.width(), rect.height()) * 0.5f : radius;
        canvas.drawRoundRect(rect, r, r, fill);
        canvas.drawRoundRect(rect, r, r, rim);
    }

    @Override
    public void setAlpha(int alpha) {
        fill.setAlpha(alpha);
        rim.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        fill.setColorFilter(cf);
        rim.setColorFilter(cf);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
