package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;

/**
 * Downscales and blurs the current wallpaper into a small frosted copy for Liquid Glass surfaces
 * ({@link DockDecoration} and {@link GlassPillDrawable}). Runs once per wallpaper load.
 */
final class Frost {

    static final int WIDTH = 160;
    static final int HEIGHT = 90;
    /** Cached normalized BT.601 luminance (0..1) of the most recently frosted backdrop for adaptive glass. */
    static volatile float lastLuminance = 0.25f;

    private Frost() {
    }

    /** Produces a saturated, blurred 160x90 copy matching ImageView CENTER_CROP; returns null on failure. */
    static Bitmap of(Bitmap source) {
        if (source == null || source.isRecycled() || source.getWidth() <= 0 || source.getHeight() <= 0) return null;
        Bitmap sw = null;
        try {
            Bitmap src = source;
            if (src.getConfig() == Bitmap.Config.HARDWARE) {
                sw = src.copy(Bitmap.Config.ARGB_8888, false);
                if (sw == null) return null;
                src = sw;
            }
            Bitmap small = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(small);
            Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
            ColorMatrix saturate = new ColorMatrix();
            saturate.setSaturation(1.4f);   // blur greys colours out; tvOS material keeps them vivid
            paint.setColorFilter(new ColorMatrixColorFilter(saturate));
            // Centre-crop like the wallpaper's ImageView so frosted surfaces align with what is behind them.
            float scale = Math.max((float) WIDTH / src.getWidth(), (float) HEIGHT / src.getHeight());
            canvas.translate((WIDTH - src.getWidth() * scale) / 2f, (HEIGHT - src.getHeight() * scale) / 2f);
            canvas.scale(scale, scale);
            canvas.drawBitmap(src, 0, 0, paint);

            int[] px = new int[WIDTH * HEIGHT];
            int[] line = new int[WIDTH]; // WIDTH >= HEIGHT; reused across all 6 passes
            small.getPixels(px, 0, WIDTH, 0, 0, WIDTH, HEIGHT);
            long lumaSum = 0;
            int samples = 0;
            for (int i = 0; i < px.length; i += 4) {
                int c = px[i];
                lumaSum += ((c >> 16) & 0xFF) * 299L + ((c >> 8) & 0xFF) * 587L + (c & 0xFF) * 114L;
                samples++;
            }
            if (samples > 0) lastLuminance = lumaSum / (samples * 255000f);
            // Three box passes approximate a Gaussian; radius 3 at 1/12 scale is ~36 px blur at 1080p.
            for (int pass = 0; pass < 3; pass++) {
                boxBlur(px, line, WIDTH, HEIGHT, 3, true);
                boxBlur(px, line, WIDTH, HEIGHT, 3, false);
            }
            small.setPixels(px, 0, WIDTH, 0, 0, WIDTH, HEIGHT);
            return small;
        } catch (Exception | OutOfMemoryError failure) {
            return null;
        } finally {
            if (sw != null && !sw.isRecycled()) sw.recycle();
        }
    }

    /** One box pass along rows (horizontal) or columns, clamping at the edges. */
    private static void boxBlur(int[] px, int[] line, int w, int h, int radius, boolean horizontal) {
        int lines = horizontal ? h : w, length = horizontal ? w : h;
        int window = radius * 2 + 1;
        for (int l = 0; l < lines; l++) {
            for (int i = 0; i < length; i++) line[i] = px[horizontal ? l * w + i : i * w + l];
            int r = 0, g = 0, b = 0;
            for (int i = -radius; i <= radius; i++) {
                int c = line[Math.max(0, Math.min(length - 1, i))];
                r += (c >> 16) & 0xFF;
                g += (c >> 8) & 0xFF;
                b += c & 0xFF;
            }
            for (int i = 0; i < length; i++) {
                px[horizontal ? l * w + i : i * w + l] = 0xFF000000 | (r / window) << 16 | (g / window) << 8 | (b / window);
                int out = line[Math.max(0, i - radius)], in = line[Math.min(length - 1, i + radius + 1)];
                r += ((in >> 16) & 0xFF) - ((out >> 16) & 0xFF);
                g += ((in >> 8) & 0xFF) - ((out >> 8) & 0xFF);
                b += (in & 0xFF) - (out & 0xFF);
            }
        }
    }
}
