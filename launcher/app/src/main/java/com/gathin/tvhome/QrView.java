package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import android.view.ViewOutlineProvider;

/** High-contrast QR code view scaled to whole pixels per module, coalesced into a single GPU path. */
final class QrView extends View {
    private static final int QUIET = 4;
    private static final int LIGHT = 0xFFFFFFFF;
    private static final int DARK = 0xFF111318;

    private final Paint paint = new Paint();
    private final Path modulePath = new Path();
    private boolean[][] modules;
    private boolean pathDirty = true;

    QrView(Context context) {
        super(context);
        paint.setColor(DARK);
        paint.setStyle(Paint.Style.FILL);
        paint.setAntiAlias(false);
        float radius = 16f * context.getResources().getDisplayMetrics().density;
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        setClipToOutline(true);
    }

    boolean setText(String text) {
        modules = Qr.encode(text);
        pathDirty = true;
        invalidate();
        return modules != null;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        pathDirty = true;
    }

    private void rebuildPath() {
        modulePath.reset();
        pathDirty = false;
        if (modules == null) return;
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        int n = modules.length + 2 * QUIET;
        int side = Math.min(w, h);
        int scale = Math.max(1, side / n);
        int left = (w - scale * n) / 2 + QUIET * scale;
        int top = (h - scale * n) / 2 + QUIET * scale;
        for (int y = 0; y < modules.length; y++) {
            float y0 = top + y * scale;
            float y1 = y0 + scale;
            for (int x = 0; x < modules.length; x++) {
                if (modules[y][x]) {
                    float x0 = left + x * scale;
                    modulePath.addRect(x0, y0, x0 + scale, y1, Path.Direction.CW);
                }
            }
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(LIGHT);
        if (modules == null) return;
        if (pathDirty) rebuildPath();
        canvas.drawPath(modulePath, paint);
    }
}
