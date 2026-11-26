package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;

/**
 * Optical Liquid Glass renderer inspired by vvccwang/android-liquid-glass (Kyant Backdrop effect stack):
 *   1) Saturated vibrancy sampling from the 160x90 frosted backdrop.
 *   2) Rounded-rect SDF meniscus lens refraction + subtle edge chromatic dispersion via AGSL RuntimeShader (API 33+).
 *   3) Adaptive luminance veil (dark backdrop -> luminous light glass; bright backdrop -> smoky dark glass).
 *   4) Directional / balanced specular rim highlight (no clipPath, zero per-frame allocations).
 *
 * Gracefully degrades to hardware-accelerated BitmapShader + LinearGradient rim on API 26-32 or drivers without AGSL.
 */
final class LiquidGlass {

    private static final String AGSL_GLASS = ""
            + "uniform shader uBackdrop;\n"
            + "uniform float4 uRect;\n"
            + "uniform float2 uOrigin;\n"
            + "uniform float2 uScale;\n"
            + "uniform float uRadius;\n"
            + "uniform float uRefract;\n"
            + "uniform float uCircular;\n"
            + "uniform float uDarkMode;\n"
            + "uniform float uVeil;\n"
            + "uniform float uVibrancy;\n"
            + "uniform float uStroke;\n"
            + "uniform float4 uAmbient;\n"
            + "float3 sampleVivid(float2 screenPt) {\n"
            + "    half4 s = uBackdrop.eval(screenPt / uScale);\n"
            + "    float3 rgb = mix(float3(s.rgb), uAmbient.rgb, uAmbient.a);\n"
            + "    float l = dot(rgb, float3(0.299, 0.587, 0.114));\n"
            + "    return clamp(mix(float3(l), rgb, uVibrancy), 0.0, 1.0);\n"
            + "}\n"
            + "half4 main(float2 fragCoord) {\n"
            + "    float2 center = 0.5 * (uRect.xy + uRect.zw);\n"
            + "    float2 halfSize = 0.5 * (uRect.zw - uRect.xy);\n"
            + "    float rad = min(uRadius, min(halfSize.x, halfSize.y));\n"
            + "    float2 p = fragCoord - center;\n"
            + "    float2 q = abs(p) - (halfSize - rad);\n"
            + "    float sd = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - rad;\n"
            + "    float cov = clamp(0.5 - sd, 0.0, 1.0);\n"
            + "    if (cov <= 0.001) return half4(0.0);\n"
            + "    float2 sgn = float2(p.x >= 0.0 ? 1.0 : -1.0, p.y >= 0.0 ? 1.0 : -1.0);\n"
            + "    float2 gradDir;\n"
            + "    if (q.x > 0.0 && q.y > 0.0) {\n"
            + "        gradDir = normalize(max(q, 0.0)) * sgn;\n"
            + "    } else if (abs(q.x) > abs(q.y)) {\n"
            + "        gradDir = float2(sgn.x, 0.0);\n"
            + "    } else {\n"
            + "        gradDir = float2(0.0, sgn.y);\n"
            + "    }\n"
            + "    float rimZone = clamp(1.0 + sd / max(uRefract, 1.0), 0.0, 1.0);\n"
            + "    float curve = rimZone * rimZone;\n"
            + "    float bend = curve * uRefract * 1.35;\n"
            + "    float disp = curve * 1.35;\n"
            + "    float2 refrUv = (fragCoord + uOrigin) - gradDir * bend;\n"
            + "    float r = sampleVivid(refrUv - gradDir * disp).r;\n"
            + "    float g = sampleVivid(refrUv).g;\n"
            + "    float b = sampleVivid(refrUv + gradDir * disp).b;\n"
            + "    float3 bg = float3(r, g, b);\n"
            + "    float topDepth = clamp(0.5 - p.y / max(halfSize.y * 2.0, 1.0), 0.0, 1.0);\n"
            + "    float3 tint = mix(float3(0.96, 0.98, 1.0), float3(0.07, 0.09, 0.14), uDarkMode);\n"
            + "    float tintAlpha = clamp(uVeil + topDepth * 0.05 * (1.0 - uDarkMode), 0.0, 0.94);\n"
            + "    float3 col = mix(bg, tint, tintAlpha);\n"
            + "    col += float3(0.09) * curve * (1.0 - 0.55 * uDarkMode);\n"
            + "    float rimMask = smoothstep(-uStroke * 1.45, -uStroke * 0.08, sd);\n"
            + "    float keyLight = max(0.0, dot(gradDir, normalize(float2(-0.707, -0.707))));\n"
            + "    float bounce = max(0.0, dot(gradDir, normalize(float2(0.707, 0.707)))) * 0.42;\n"
            + "    float dirSpec = clamp(0.18 + 0.82 * (keyLight + bounce), 0.0, 1.0);\n"
            + "    float spec = mix(dirSpec, 0.60 + 0.40 * keyLight, uCircular);\n"
            + "    float specAlpha = (0.78 * spec) * rimMask;\n"
            + "    col = mix(col, float3(1.0), specAlpha);\n"
            + "    return half4(half3(col * cov), half(cov));\n"
            + "}\n";

    private static boolean agslSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU;

    private final float density;
    private final int lightVeilColor;
    private final int darkVeilColor;
    private final boolean circular;
    private final boolean forceDarkVeil;
    private final float refractPx;
    private final float veilFraction;

    private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint veil = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint agslPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix bodyMatrix = new Matrix();
    private final RectF band = new RectF();
    private final RectF lit = new RectF(Float.NaN, 0, 0, 0);

    private BitmapShader bodyShader;
    private Object runtimeShader; // RuntimeShader on API 33+, kept as Object to avoid verify issues on API 26-32
    private int screenW = 1920, screenH = 1080;
    private float ambientR, ambientG, ambientB, ambientMix;

    LiquidGlass(float density, int lightVeilColor) {
        this(density, lightVeilColor, 0x9910141E, false, false, 20f);
    }

    /** Mixes live cinematic focused-app glow subtly into AGSL backdrop refraction (zero allocations). */
    void setAmbientColor(int color, float mixStrength) {
        ambientR = ((color >> 16) & 0xFF) / 255f;
        ambientG = ((color >> 8) & 0xFF) / 255f;
        ambientB = (color & 0xFF) / 255f;
        ambientMix = Math.max(0f, Math.min(1f, mixStrength));
    }

    LiquidGlass(float density, int lightVeilColor, int darkVeilColor, boolean circular, boolean forceDarkVeil, float refractDp) {
        this.density = density;
        this.lightVeilColor = lightVeilColor;
        this.darkVeilColor = darkVeilColor;
        this.circular = circular;
        this.forceDarkVeil = forceDarkVeil;
        this.refractPx = refractDp * density;
        this.veilFraction = ((lightVeilColor >>> 24) & 0xFF) / 255f;
        veil.setColor(forceDarkVeil ? darkVeilColor : lightVeilColor);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(1.4f * density);
        if (agslSupported && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                runtimeShader = new RuntimeShader(AGSL_GLASS);
            } catch (Throwable ignored) {
                agslSupported = false;
                runtimeShader = null;
            }
        }
    }

    /** The blurred wallpaper copy and screen dimensions; null falls back to adaptive tint + specular rim. */
    void setFrost(Bitmap blurred, int screenWidth, int screenHeight) {
        screenW = Math.max(1, screenWidth);
        screenH = Math.max(1, screenHeight);
        bodyShader = (blurred == null || blurred.isRecycled())
                ? null
                : new BitmapShader(blurred, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        body.setShader(bodyShader);
        if (agslSupported && runtimeShader != null && bodyShader != null && Build.VERSION.SDK_INT >= 33) {
            try {
                RuntimeShader shader = (RuntimeShader) runtimeShader;
                shader.setInputShader("uBackdrop", bodyShader);
                agslPaint.setShader(shader);
            } catch (Throwable ignored) {
                agslSupported = false;
                runtimeShader = null;
                agslPaint.setShader(null);
            }
        }
        updateVeilColor();
    }

    static boolean shouldUseDarkVeil(boolean forceDark, float luminance) {
        return forceDark || luminance > 0.52f;
    }

    static float adaptiveVeilFraction(boolean forceDark, float luminance, float lightVeil) {
        return forceDark ? 0.68f : (luminance > 0.52f ? 0.42f : lightVeil);
    }

    private boolean isDarkSurface() {
        return shouldUseDarkVeil(forceDarkVeil, Frost.lastLuminance);
    }

    private void updateVeilColor() {
        if (bodyShader == null) {
            veil.setColor(forceDarkVeil ? 0xD8111520 : 0x59141822);
        } else {
            veil.setColor(isDarkSurface() ? darkVeilColor : lightVeilColor);
        }
    }

    boolean hasFrost() {
        return bodyShader != null;
    }

    /**
     * Draws optical Liquid Glass into {@code rect}. {@code originX/Y} maps the canvas origin to screen coordinates
     * so screen-space refraction aligns cleanly with the wallpaper behind the component.
     */
    void draw(Canvas c, RectF rect, float radius, float originX, float originY) {
        if (rect.width() <= 1f || rect.height() <= 1f) return;

        if (agslSupported && runtimeShader != null && bodyShader != null && Build.VERSION.SDK_INT >= 33) {
            try {
                RuntimeShader shader = (RuntimeShader) runtimeShader;
                boolean dark = isDarkSurface();
                shader.setFloatUniform("uRect", rect.left, rect.top, rect.right, rect.bottom);
                shader.setFloatUniform("uOrigin", originX, originY);
                shader.setFloatUniform("uScale", (float) screenW / Frost.WIDTH, (float) screenH / Frost.HEIGHT);
                shader.setFloatUniform("uRadius", radius);
                shader.setFloatUniform("uRefract", refractPx);
                shader.setFloatUniform("uCircular", circular ? 1f : 0f);
                shader.setFloatUniform("uDarkMode", dark ? 1f : 0f);
                shader.setFloatUniform("uVeil", adaptiveVeilFraction(forceDarkVeil, Frost.lastLuminance, veilFraction));
                shader.setFloatUniform("uVibrancy", 1.24f);
                shader.setFloatUniform("uStroke", 1.4f * density);
                shader.setFloatUniform("uAmbient", ambientR, ambientG, ambientB, ambientMix);
                c.drawRoundRect(rect, radius, radius, agslPaint);
                return;
            } catch (Throwable ignored) {
                agslSupported = false;
                runtimeShader = null;
            }
        }

        // Fallback path (API 26-32 or drivers without RuntimeShader)
        updateVeilColor();
        if (bodyShader != null) {
            float sx = (float) screenW / Frost.WIDTH;
            float sy = (float) screenH / Frost.HEIGHT;
            bodyMatrix.setScale(sx, sy);
            bodyMatrix.postTranslate(-originX, -originY);
            bodyShader.setLocalMatrix(bodyMatrix);
            c.drawRoundRect(rect, radius, radius, body);
        }
        c.drawRoundRect(rect, radius, radius, veil);

        if (rect.left != lit.left || rect.top != lit.top || rect.right != lit.right || rect.bottom != lit.bottom) {
            lit.set(rect);
            int topSpec = circular ? 0xA6FFFFFF : 0x99FFFFFF;
            int midSpec = circular ? 0x52FFFFFF : 0x30FFFFFF;
            int endSpec = circular ? 0x66FFFFFF : 0x3DFFFFFF;
            rim.setShader(new LinearGradient(
                    rect.left, rect.top,
                    rect.left + rect.height() * 0.6f + rect.width() * 0.15f, rect.bottom,
                    new int[]{topSpec, midSpec, 0x1FFFFFFF, endSpec},
                    new float[]{0f, 0.35f, 0.7f, 1f},
                    Shader.TileMode.CLAMP));
        }
        float half = rim.getStrokeWidth() / 2f;
        band.set(rect);
        band.inset(half, half);
        c.drawRoundRect(band, Math.max(0f, radius - half), Math.max(0f, radius - half), rim);
    }
}
