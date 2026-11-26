package com.gathin.tvhome;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import android.widget.ImageView;

import java.io.File;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * The clock screensaver's scene (layout dream_clock): the thin clock and date on black over a slow ambient
 * glow. Every minute the clock drifts to a new spot and the glow shifts colour, so nothing burns in; a
 * ClockRefresh keeps its TextClocks current where Android would not.
 * Shared by ClockDream (the system screensaver) and ClockActivity (Home left idle).
 */
final class ClockScene {

    private static final long MOVE_MS = 60_000L;
    /** The glow's colour change: 16 steps over 8 s. Animated per frame it redrew the full-screen gradient ~480 times. */
    private static final int GLOW_STEPS = 16;
    private static final long GLOW_STEP_MS = 500L;
    /** Deep, low-brightness glow colours; the glow is drawn at partial alpha on black. */
    private static final int[] PALETTE = {0xFF2B5CFF, 0xFF00A39A, 0xFF8A3FFC, 0xFFE0661B, 0xFFD6336C};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final View content;
    private final ClockRefresh clockRefresh;
    private final ImageView ambient;
    private final MonthCalendarView calendar;
    private final View calendarDivider;
    private final DitheredRadialGlowDrawable glow;
    private SpringAnimation enterSx, enterSy, enterTy;
    private boolean started;
    private final Runnable startEnter = new Runnable() {
        @Override
        public void run() {
            if (!started) return;
            placeClock();
            float targetY = content.getY();
            float density = content.getResources().getDisplayMetrics().density;
            cancelEnterSprings();
            content.setScaleX(0.95f);
            content.setScaleY(0.95f);
            content.setY(targetY + 18f * density);
            content.animate()
                    .alpha(1f)
                    .setDuration(240)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
            enterSx = new SpringAnimation(content, DynamicAnimation.SCALE_X, 1f)
                    .setSpring(new SpringForce(1f).setStiffness(420f).setDampingRatio(0.84f));
            enterSy = new SpringAnimation(content, DynamicAnimation.SCALE_Y, 1f)
                    .setSpring(new SpringForce(1f).setStiffness(420f).setDampingRatio(0.84f));
            enterTy = new SpringAnimation(content, DynamicAnimation.Y, targetY)
                    .setSpring(new SpringForce(targetY).setStiffness(420f).setDampingRatio(0.84f));
            enterSx.start();
            enterSy.start();
            enterTy.start();
        }
    };

    private void cancelEnterSprings() {
        if (enterSx != null) { enterSx.cancel(); enterSx = null; }
        if (enterSy != null) { enterSy.cancel(); enterSy = null; }
        if (enterTy != null) { enterTy.cancel(); enterTy = null; }
    }
    private int colorIndex;
    private int glowColor;
    /** clock_wallpaper: a picture replaced the coloured glow. */
    private boolean wallpaperShown;
    /** clock_glow false: plain black behind the clock, whether or not a picture shows. */
    private boolean glowOff;
    /** clock_wallpaper: the picture start() decodes, its darkening, and the resources to draw it with. */
    private File wallpaperFile;
    private int wallpaperShade;
    private Resources wallpaperRes;
    /** One number per decode request: a result whose number is no longer the current one is dropped. Main thread only. */
    private int wallpaperGeneration;
    /** clock_wallpaper with a collection: the pictures, how long each lasts, and how the next one is picked. */
    private List<File> rotation;
    private long rotationMs;
    private long shownMs;
    private String order;
    private Map<String, Set<String>> moods;
    private String latitude, longitude;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            moveClock();
            shiftGlow();
            advanceWallpaper();
            if (calendar != null && calendar.getVisibility() == View.VISIBLE) {
                calendar.updateCalendarData();
            }
            handler.postDelayed(this, MOVE_MS);
        }
    };

    /** root is the inflated dream_clock layout. */
    ClockScene(View root) {
        content = root.findViewById(R.id.dreamContent);
        clockRefresh = new ClockRefresh(content);
        ambient = root.findViewById(R.id.dreamAmbient);
        calendar = root.findViewById(R.id.dreamCalendar);
        calendarDivider = root.findViewById(R.id.dreamCalendarDivider);
        glow = new DitheredRadialGlowDrawable();
        ambient.setBackground(glow);
        colorIndex = random.nextInt(PALETTE.length);
        setGlow(PALETTE[colorIndex]);
    }

    void start() {
        started = true;
        content.setAlpha(0f);
        content.removeCallbacks(startEnter);
        content.post(startEnter);
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, MOVE_MS);
        clockRefresh.start();
        if (wallpaperFile != null && !wallpaperShown) loadWallpaper();
    }

    void stop() {
        started = false;
        content.removeCallbacks(startEnter);
        cancelEnterSprings();
        handler.removeCallbacksAndMessages(null);   // the tick and any glow steps still queued
        clockRefresh.stop();
        content.animate().cancel();
        wallpaperGeneration++;   // a decode still running must not attach its picture to a stopped scene
        glow.release();
    }

    /**
     * clock_wallpaper: put the picture Home is showing behind the clock, darkened by clock_wallpaper_dim, in
     * place of the glow, and carry a collection's rotation on while the screensaver is up. Decoded off the main
     * thread at screen size in RGB_565 (4 MB at 1080p), like Home's own; until then, or if it cannot be read,
     * the glow stays, unless clock_glow is false.
     * Call before start(), which starts the decode (again after a stop() that dropped an unfinished one).
     */
    void useHomeWallpaper(Context ctx) {
        android.content.SharedPreferences choices = ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        boolean showCalendar = "calendar".equals(choices.getString(Config.SCREENSAVER_STYLE_KEY, "calendar"));
        if (calendar != null) {
            calendar.setVisibility(showCalendar ? View.VISIBLE : View.GONE);
            if (showCalendar) calendar.updateCalendarData();
        }
        if (calendarDivider != null) {
            calendarDivider.setVisibility(showCalendar ? View.VISIBLE : View.GONE);
        }

        String bgChoice = choices.getString(Config.SCREENSAVER_BG_KEY, null);
        if ("black".equals(bgChoice)) {
            wallpaperShown = true;
            ambient.setBackground(null);
            ambient.setImageDrawable(null);
            return;
        } else if ("glow".equals(bgChoice)) {
            wallpaperShown = false;
            ambient.setImageDrawable(null);
            ambient.setBackground(glow);
            return;
        }

        final Context appCtx = ctx.getApplicationContext();
        final int generation = ++wallpaperGeneration;
        LauncherExecutors.io().execute(() -> {
            final Config cfg;
            try {
                cfg = Config.load(appCtx);
            } catch (RuntimeException e) {
                return;
            }
            boolean wantWallpaper = "wallpaper".equals(bgChoice) || (bgChoice == null && cfg.clockWallpaper);
            if (!wantWallpaper) {
                handler.post(() -> {
                    if (generation != wallpaperGeneration) return;
                    // clock_glow false: plain black behind the clock (OLED); the clock itself still moves every minute.
                    if (!cfg.clockGlow) {
                        wallpaperShown = true;   // also stops the glow's colour cycle
                        ambient.setBackground(null);
                    }
                });
                return;
            }
            String customScreensaver = choices.getString(Config.SCREENSAVER_WALLPAPER_KEY, null);
            if (customScreensaver != null && !customScreensaver.isEmpty()) {
                File ssFile = new File(customScreensaver);
                if (ssFile.isFile() && ssFile.length() > 0) {
                    handler.post(() -> {
                        if (generation != wallpaperGeneration) return;
                        rotation = java.util.Collections.emptyList();
                        wallpaperFile = ssFile;
                        rotationMs = 0L;
                        wallpaperShade = (cfg.clockWallpaperDim * 255 / 100) << 24;
                        wallpaperRes = appCtx.getResources();
                        ambient.setBackground(null);
                        if (started && !wallpaperShown) loadWallpaper();
                    });
                    return;
                }
            }
            List<File> availableWallpapers = cfg.wallpapers;
            if (availableWallpapers.isEmpty()) {
                availableWallpapers = new ArrayList<>();
                for (File root : new File[]{Config.dir(appCtx), appCtx.getFilesDir()}) {
                    if (root == null) continue;
                    File custom = new File(root, "custom_wallpaper.jpg");
                    if (custom.isFile() && custom.length() > 0) availableWallpapers.add(custom);
                    File single = new File(root, Config.WALLPAPER_NAME);
                    if (single.isFile() && single.length() > 0 && !availableWallpapers.contains(single)) {
                        availableWallpapers.add(single);
                    }
                    for (File img : WallpaperRotation.images(new File(root, "wallpapers"))) {
                        if (!availableWallpapers.contains(img)) availableWallpapers.add(img);
                    }
                }
            }
            final List<File> resolvedWallpapers = availableWallpapers;
            final String shown = appCtx.getSharedPreferences("wallpaper_rotation", Context.MODE_PRIVATE)
                    .getString("image", "");
            handler.post(() -> {
                if (generation != wallpaperGeneration) return;
                if (resolvedWallpapers.isEmpty()) {
                    wallpaperFile = null;
                } else {
                    rotation = new ArrayList<>(resolvedWallpapers);
                    File current = WallpaperRotation.find(rotation, shown);
                    wallpaperFile = current != null ? current : rotation.get(0);
                    rotationMs = cfg.wallpaperRotationMinutes * 60_000L;
                    order = cfg.wallpaperOrder;
                    moods = cfg.wallpaperMoods;
                    latitude = moods.isEmpty() ? "" : appCtx.getString(R.string.latitude);
                    longitude = moods.isEmpty() ? "" : appCtx.getString(R.string.longitude);
                }
                wallpaperShade = (cfg.clockWallpaperDim * 255 / 100) << 24;
                wallpaperRes = appCtx.getResources();
                if (started && !wallpaperShown && wallpaperFile != null) loadWallpaper();
            });
        });
    }

    private void loadWallpaper() {
        File file = wallpaperFile;
        int shade = wallpaperShade;
        Resources res = wallpaperRes;
        if (file == null || res == null) return;
        DisplayMetrics dm = res.getDisplayMetrics();
        int w = dm.widthPixels, h = dm.heightPixels;
        int generation = ++wallpaperGeneration;
        LauncherExecutors.io().execute(() -> {
            Bitmap bmp = decode(file, w, h, shade);
            if (bmp == null) return;
            handler.post(() -> {
                if (generation != wallpaperGeneration || wallpaperShown) {
                    if (bmp.getConfig() != Bitmap.Config.HARDWARE && !bmp.isRecycled()) {
                        bmp.recycle();   // stopped meanwhile (or already shown): this picture was never attached
                    }
                    return;
                }
                wallpaperShown = true;
                // Shade is baked into the GPU texture off-thread: single draw pass, zero overdraw.
                ambient.setBackground(null);
                ambient.setImageDrawable(new BitmapDrawable(res, bmp));
            });
        });
    }

    /** WallpaperPlayer's decode: sampled, shaded, and uploaded to GPU memory off-thread. */
    private static Bitmap decode(File file, int screenW, int screenH, int shade) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), options);
            int width = options.outWidth, height = options.outHeight;
            if (width <= 0 || height <= 0) return null;
            int sample = WallpaperSize.sampleSize(width, height, screenW, screenH);
            if (!WallpaperSize.accepted(width / sample, height / sample)) return null;
            options.inJustDecodeBounds = false;
            options.inSampleSize = sample;
            int[] scale = WallpaperSize.coverScale(width / sample, height / sample, screenW, screenH);
            if (scale != null) {
                options.inScaled = true;
                options.inDensity = scale[0];
                options.inTargetDensity = scale[1];
            }
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            options.inMutable = shade != 0;
            // sRGB at decode time, as WallpaperPlayer: a colour-profiled photo would be converted every frame.
            options.inPreferredColorSpace = android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB);
            Bitmap decoded = BitmapFactory.decodeFile(file.getPath(), options);
            if (decoded == null) return null;
            if (shade != 0) new Canvas(decoded).drawColor(shade);
            Bitmap gpu = decoded.copy(Bitmap.Config.HARDWARE, false);
            if (gpu != null) {
                decoded.recycle();
                return gpu;
            }
            return decoded;
        } catch (Exception | OutOfMemoryError failure) {
            return null;
        }
    }

    /** Step to the next picture once its turn is up; the tick already runs every minute. */
    private void advanceWallpaper() {
        if (rotation == null || rotationMs <= 0 || rotation.size() < 2) return;
        shownMs += MOVE_MS;
        if (shownMs < rotationMs) return;
        shownMs = 0;
        // The same pick as Home's, so a shuffled collection stays shuffled and wallpaper_moods still
        // decides what may show at this hour.
        File next = WallpaperRotation.pick(playable(), wallpaperFile.getName(), order, random);
        if (next == null || next.equals(wallpaperFile)) return;   // empty or a mood group of one
        wallpaperFile = next;
        wallpaperShown = false;
        loadWallpaper();
    }

    /** WallpaperPlayer's filter: the pictures wallpaper_moods allows at this hour, or all of them. */
    private List<File> playable() {
        if (moods.isEmpty()) return rotation;
        LocalDateTime now = LocalDateTime.now();
        int[] sun = WallpaperMood.sun(now.toLocalDate(), latitude, longitude, ZoneId.systemDefault());
        return WallpaperMood.select(rotation, moods,
                WallpaperMood.timeOfDay(now.getHour() * 60 + now.getMinute(), sun[0], sun[1]));
    }

    private void moveClock() {
        content.animate().alpha(0f).setDuration(900).withEndAction(() -> {
            if (!started) return;
            placeClock();
            content.animate().alpha(1f).setDuration(1200).start();
        }).start();
    }

    /** A random spot that keeps the whole clock on screen, with the glow centred behind it. */
    private void placeClock() {
        View parent = (View) content.getParent();
        int maxX = Math.max(0, parent.getWidth() - content.getWidth());
        int maxY = Math.max(0, parent.getHeight() - content.getHeight());
        float x = maxX * (0.1f + 0.8f * random.nextFloat());
        float y = maxY * (0.1f + 0.8f * random.nextFloat());
        content.setX(x);
        content.setY(y);
        if (parent.getWidth() > 0 && parent.getHeight() > 0) {
            glow.setCenter((x + content.getWidth() / 2f) / parent.getWidth(),
                    (y + content.getHeight() / 2f) / parent.getHeight());
        }
    }

    private void shiftGlow() {
        if (wallpaperShown || glowOff) return;
        int from = glowColor;
        colorIndex = (colorIndex + 1) % PALETTE.length;
        int to = PALETTE[colorIndex];
        for (int i = 1; i <= GLOW_STEPS; i++) {
            float f = (float) i / GLOW_STEPS;
            int stepColor = MainActivity.lerpColor(from, to, f);
            handler.postDelayed(() -> setGlow(stepColor), i * GLOW_STEP_MS);
        }
    }

    private void setGlow(int color) {
        glowColor = color;
        glow.setColor(color);
    }

    /**
     * 4x4 Bayer ordered-dithered radial ambient glow on pure black (240x135 opaque ARGB_8888 texture).
     * Eliminates 8-bit concentric banding rings visible in Skia's undithered dark GradientDrawable
     * while reducing full-screen GPU fragment cost to a single bilinear-sampled quad.
     */
    static final class DitheredRadialGlowDrawable extends Drawable {
        static final int TEX_W = 240;
        static final int TEX_H = 135;
        private static final float ASPECT = (float) TEX_H / TEX_W;
        private static final float INV_RADIUS = 1f / 0.55f;
        private static final int[] BAYER_4X4 = {
                 0,  8,  2, 10,
                12,  4, 14,  6,
                 3, 11,  1,  9,
                15,  7, 13,  5
        };

        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        private int[] pixels;
        private Bitmap bitmap;
        private int rgb = 0x2B5CFF;
        private float centerX = 0.5f;
        private float centerY = 0.5f;
        private boolean dirty = true;

        void setColor(int argb) {
            int next = argb & 0x00FFFFFF;
            if (next == rgb && bitmap != null && !dirty) return;
            rgb = next;
            dirty = true;
            invalidateSelf();
        }

        void setCenter(float cx, float cy) {
            if (Math.abs(cx - centerX) < 0.002f && Math.abs(cy - centerY) < 0.002f && !dirty) return;
            centerX = cx;
            centerY = cy;
            dirty = true;
            invalidateSelf();
        }

        void release() {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
            bitmap = null;
            pixels = null;
            dirty = true;
        }

        static void renderPixels(int[] out, int w, int h, int rgb, float cx, float cy) {
            float baseR = (rgb >> 16) & 0xFF;
            float baseG = (rgb >> 8) & 0xFF;
            float baseB = rgb & 0xFF;
            float invW = 1f / w;
            float invH = 1f / h;
            for (int y = 0; y < h; y++) {
                float dy = ((y + 0.5f) * invH - cy) * ASPECT;
                float dy2 = dy * dy;
                int rowOff = y * w;
                int bayerRow = (y & 3) << 2;
                for (int x = 0; x < w; x++) {
                    float dx = (x + 0.5f) * invW - cx;
                    float dist = (float) Math.sqrt(dx * dx + dy2) * INV_RADIUS;
                    if (dist >= 1f) {
                        out[rowOff + x] = 0xFF000000;
                        continue;
                    }
                    float u = 1f - dist;
                    float f = 0.255f * u * u * (1.35f - 0.35f * u);
                    float dither = (BAYER_4X4[bayerRow | (x & 3)] + 0.5f) * (1f / 16f) - 0.5f;
                    int r = clampChannel((int) (baseR * f + dither + 0.5f));
                    int g = clampChannel((int) (baseG * f + dither + 0.5f));
                    int b = clampChannel((int) (baseB * f + dither + 0.5f));
                    out[rowOff + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
                }
            }
        }

        private static int clampChannel(int v) {
            return v < 0 ? 0 : Math.min(255, v);
        }

        private void ensureBitmap() {
            if (!dirty && bitmap != null && !bitmap.isRecycled()) return;
            if (pixels == null) pixels = new int[TEX_W * TEX_H];
            if (bitmap == null || bitmap.isRecycled()) {
                bitmap = Bitmap.createBitmap(TEX_W, TEX_H, Bitmap.Config.ARGB_8888);
                bitmap.setHasAlpha(false);
            }
            renderPixels(pixels, TEX_W, TEX_H, rgb, centerX, centerY);
            bitmap.setPixels(pixels, 0, TEX_W, 0, 0, TEX_W, TEX_H);
            dirty = false;
        }

        @Override
        public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            if (bounds.isEmpty()) return;
            ensureBitmap();
            if (bitmap != null && !bitmap.isRecycled()) {
                canvas.drawBitmap(bitmap, null, bounds, paint);
            }
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.OPAQUE;
        }
    }
}
