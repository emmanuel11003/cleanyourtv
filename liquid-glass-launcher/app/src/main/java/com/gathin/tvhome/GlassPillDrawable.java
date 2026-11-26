package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;

import androidx.annotation.NonNull;

/** A top bar pill's resting background as Liquid Glass; focused and activated states keep pill_bg. */
final class GlassPillDrawable extends Drawable {

    private final View host;
    private final LiquidGlass glass;
    private final float radius;
    private final RectF rect = new RectF();
    private final int[] onScreen = new int[2];

    GlassPillDrawable(View host, float density) {
        this.host = host;
        this.glass = new LiquidGlass(density, 0x20FFFFFF, 0x6610141E, true, false, 14f);
        this.radius = 21f * density;
    }

    void setFrost(Bitmap blurred, int screenWidth, int screenHeight) {
        glass.setFrost(blurred, screenWidth, screenHeight);
        invalidateSelf();
    }

    void setAmbientColor(int color) {
        glass.setAmbientColor(color, 0.36f);
        invalidateSelf();
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        host.getLocationOnScreen(onScreen);
        rect.set(getBounds());
        float r = Math.min(radius, rect.height() / 2f);
        glass.draw(canvas, rect, r, onScreen[0], onScreen[1]);
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
