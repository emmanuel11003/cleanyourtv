package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;

import androidx.annotation.NonNull;

/**
 * Floating modal/popup Liquid Glass panel background (vvccwang DialogContent pattern):
 * refracts the backdrop behind the panel over a dark-tinted optical veil with crisp directional specular rim.
 */
final class GlassPanelDrawable extends Drawable {

    private final View host;
    private final LiquidGlass glass;
    private final float radius;
    private final RectF rect = new RectF();
    private final int[] onScreen = new int[2];

    GlassPanelDrawable(View host, float density, float cornerDp) {
        this.host = host;
        this.glass = new LiquidGlass(density, 0xB010141E, 0xB810141E, false, true, 20f);
        this.radius = cornerDp * density;
    }

    void setFrost(Bitmap blurred, int screenWidth, int screenHeight) {
        glass.setFrost(blurred, screenWidth, screenHeight);
        invalidateSelf();
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        host.getLocationOnScreen(onScreen);
        rect.set(getBounds());
        glass.draw(canvas, rect, radius, onScreen[0], onScreen[1]);
    }

    @Override
    public void setAlpha(int alpha) {
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
