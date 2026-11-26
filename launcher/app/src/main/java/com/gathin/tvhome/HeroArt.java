package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/**
 * The cinematic shelf's backdrop: an app's artwork reduced to 48×27 pixels, saturated and smoothed,
 * then stretched over the screen by its ImageView. Bilinear filtering of an image that small is a
 * blur that costs one tiny texture; a real blur would mean RenderScript or a shader pass per frame,
 * which a 1 GB TV cannot spare.
 */
final class HeroArt {
    static final int W = 48, H = 27;
    private static final float SATURATION = 1.7f;
    private static final Object LOCK = new Object();
    private static final int[] PX = new int[W * H];
    private static final int[] TEMP = new int[W * H];

    private HeroArt() {
    }

    /** The softened artwork, about 5 KB; null when there is nothing to draw. Safe to call off-thread. */
    static Bitmap from(Drawable live) {
        if (live == null) return null;
        Bitmap big = null, half = null, small = null;
        try {
            // Draw a private copy: changing the bounds of the drawable a tile shows would blank the tile.
            Drawable d = live.getConstantState() != null ? live.getConstantState().newDrawable().mutate() : live;
            Rect saved = new Rect(d.getBounds());
            big = Bitmap.createBitmap(W * 4, H * 4, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(big);
            c.drawColor(Color.BLACK);
            d.setBounds(0, 0, big.getWidth(), big.getHeight());
            d.draw(c);
            d.setBounds(saved);
            // Halve twice: each filtered halving averages 2x2 pixels, where one 4x step would skip most of them.
            half = Bitmap.createScaledBitmap(big, W * 2, H * 2, true);
            big.recycle();
            big = null;
            small = Bitmap.createScaledBitmap(half, W, H, true);
            half.recycle();
            half = null;

            Bitmap out = small.isMutable() ? small : small.copy(Bitmap.Config.ARGB_8888, true);
            if (out != small) {
                small.recycle();
                small = null;
            } else {
                small = null;
            }
            synchronized (LOCK) {
                out.getPixels(PX, 0, W, 0, 0, W, H);
                saturate(PX);
                // Three O(1) sliding-window box passes approximate a gaussian with zero heap churn.
                for (int pass = 0; pass < 3; pass++) {
                    boxBlur(PX, TEMP, W, H, 2, true);
                    boxBlur(TEMP, PX, W, H, 2, false);
                }
                out.setPixels(PX, 0, W, 0, 0, W, H);
            }
            out.setHasAlpha(false);
            return out;
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (big != null && !big.isRecycled()) big.recycle();
            if (half != null && !half.isRecycled()) half.recycle();
            if (small != null && !small.isRecycled()) small.recycle();
        }
    }

    private static void saturate(int[] px) {
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
            float lum = 0.2126f * r + 0.7152f * g + 0.0722f * b;
            px[i] = 0xFF000000
                    | (clamp(lum + (r - lum) * SATURATION) << 16)
                    | (clamp(lum + (g - lum) * SATURATION) << 8)
                    | clamp(lum + (b - lum) * SATURATION);
        }
    }

    private static int clamp(float v) {
        return v < 0 ? 0 : v > 255 ? 255 : Math.round(v);
    }

    /** O(1) sliding-window box blur pass along rows or columns, edges clamped. Zero allocations. */
    static void boxBlur(int[] src, int[] dst, int w, int h, int radius, boolean horizontal) {
        int lines = horizontal ? h : w, length = horizontal ? w : h, span = 2 * radius + 1;
        int last = length - 1;
        for (int line = 0; line < lines; line++) {
            int r = 0, g = 0, b = 0;
            int base = horizontal ? line * w : line;
            int step = horizontal ? 1 : w;
            int firstPx = src[base];
            r = ((firstPx >> 16) & 0xFF) * (radius + 1);
            g = ((firstPx >> 8) & 0xFF) * (radius + 1);
            b = (firstPx & 0xFF) * (radius + 1);
            for (int k = 1; k <= radius; k++) {
                int p = src[base + Math.min(last, k) * step];
                r += (p >> 16) & 0xFF;
                g += (p >> 8) & 0xFF;
                b += p & 0xFF;
            }
            for (int i = 0; i < length; i++) {
                dst[base + i * step] = 0xFF000000 | ((r / span) << 16) | ((g / span) << 8) | (b / span);
                int outIdx = Math.max(0, i - radius);
                int inIdx = Math.min(last, i + radius + 1);
                int pOut = src[base + outIdx * step];
                int pIn = src[base + inIdx * step];
                r += ((pIn >> 16) & 0xFF) - ((pOut >> 16) & 0xFF);
                g += ((pIn >> 8) & 0xFF) - ((pOut >> 8) & 0xFF);
                b += (pIn & 0xFF) - (pOut & 0xFF);
            }
        }
    }
}
