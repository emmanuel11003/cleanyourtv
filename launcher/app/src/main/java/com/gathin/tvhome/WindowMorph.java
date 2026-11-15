package com.gathin.tvhome;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.graphics.Outline;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.view.animation.AnimationUtils;
import android.view.animation.PathInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Synchronized iOS-inspired bounds, corner radius, and opacity morph controller.
 *
 * Animates a window surface between an exact source icon/pill rect + corner radius and its
 * resting layout rect + corner radius using RenderNode scale/translation and {@link ViewOutlineProvider}
 * clipping so zero per-frame layout passes occur. Supports smooth mid-flight interruption and reversal.
 */
final class WindowMorph {
    static final long DURATION_MS = 340L;
    private static final float MIN_SCALE = 0.02f;

    private static final String EXTRA_LEFT = "com.gathin.tvhome.morph.LEFT";
    private static final String EXTRA_TOP = "com.gathin.tvhome.morph.TOP";
    private static final String EXTRA_RIGHT = "com.gathin.tvhome.morph.RIGHT";
    private static final String EXTRA_BOTTOM = "com.gathin.tvhome.morph.BOTTOM";
    private static final String EXTRA_RADIUS = "com.gathin.tvhome.morph.RADIUS";

    private final View surface;
    @Nullable private final View scrim;
    private final View[] contentViews;
    private final TimeInterpolator interpolator;

    private final Rect srcRect = new Rect();
    private final Rect dstRect = new Rect();
    private final Rect curRect = new Rect();
    private float srcRadius;
    private float dstRadius;
    private boolean hasExplicitSource;

    private ValueAnimator animator;
    private ViewTreeObserver.OnPreDrawListener preDrawListener;
    private boolean entering = true;
    private boolean exited;
    private float progress = 1f;
    @Nullable private Runnable pendingEnd;

    // Precomputed per-frame outline coordinates (in surface-local unscaled space) to avoid allocations.
    private int outlineLeft;
    private int outlineTop;
    private int outlineRight;
    private int outlineBottom;
    private float outlineRadius;

    WindowMorph(@NonNull View surface, @Nullable View scrim, View... contentViews) {
        this.surface = surface;
        this.scrim = scrim;
        this.contentViews = contentViews != null ? contentViews : new View[0];
        this.interpolator = loadAppleInterpolator(surface);
        this.surface.setPivotX(0f);
        this.surface.setPivotY(0f);
        this.surface.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                if (outlineRight <= outlineLeft || outlineBottom <= outlineTop) {
                    outline.setRect(0, 0, Math.max(1, view.getWidth()), Math.max(1, view.getHeight()));
                    return;
                }
                outline.setRoundRect(outlineLeft, outlineTop, outlineRight, outlineBottom, Math.max(0f, outlineRadius));
            }
        });
    }

    private static TimeInterpolator loadAppleInterpolator(View view) {
        try {
            return AnimationUtils.loadInterpolator(view.getContext(), R.interpolator.apple_decelerate);
        } catch (RuntimeException ignored) {
            return new PathInterpolator(0.16f, 1.0f, 0.30f, 1.0f);
        }
    }

    static void putSource(@Nullable Intent intent, @Nullable View anchor, float radiusPx) {
        if (intent == null || anchor == null || !anchor.isAttachedToWindow()) return;
        Rect rect = new Rect();
        if (!captureScreenRect(anchor, rect)) return;
        float r = radiusPx >= 0f ? radiusPx : Math.min(rect.width(), rect.height()) * 0.5f;
        putSource(intent, rect, r);
    }

    static void putSource(@Nullable Intent intent, @Nullable Rect screenRect, float radiusPx) {
        if (intent == null || screenRect == null || screenRect.isEmpty()) return;
        intent.putExtra(EXTRA_LEFT, screenRect.left);
        intent.putExtra(EXTRA_TOP, screenRect.top);
        intent.putExtra(EXTRA_RIGHT, screenRect.right);
        intent.putExtra(EXTRA_BOTTOM, screenRect.bottom);
        intent.putExtra(EXTRA_RADIUS, Math.max(0f, radiusPx));
    }

    static boolean captureScreenRect(@Nullable View view, @NonNull Rect out) {
        if (view == null || view.getWidth() <= 0 || view.getHeight() <= 0) return false;
        int[] loc = new int[2];
        view.getLocationOnScreen(loc);
        int w = Math.max(1, Math.round(view.getWidth() * Math.abs(view.getScaleX())));
        int h = Math.max(1, Math.round(view.getHeight() * Math.abs(view.getScaleY())));
        out.set(loc[0], loc[1], loc[0] + w, loc[1] + h);
        return true;
    }

    void attachSource(@Nullable Intent intent) {
        if (intent == null) {
            hasExplicitSource = false;
            return;
        }
        int l = intent.getIntExtra(EXTRA_LEFT, Integer.MIN_VALUE);
        int t = intent.getIntExtra(EXTRA_TOP, Integer.MIN_VALUE);
        int r = intent.getIntExtra(EXTRA_RIGHT, Integer.MIN_VALUE);
        int b = intent.getIntExtra(EXTRA_BOTTOM, Integer.MIN_VALUE);
        if (l != Integer.MIN_VALUE && t != Integer.MIN_VALUE && r > l && b > t) {
            srcRect.set(l, t, r, b);
            srcRadius = Math.max(0f, intent.getFloatExtra(EXTRA_RADIUS, Math.min(r - l, b - t) * 0.5f));
            hasExplicitSource = true;
        } else {
            hasExplicitSource = false;
        }
    }

    void setSource(@NonNull Rect sourceScreenRect, float sourceRadiusPx) {
        srcRect.set(sourceScreenRect);
        srcRadius = Math.max(0f, sourceRadiusPx);
        hasExplicitSource = !srcRect.isEmpty();
    }

    void setTargetCornerRadius(float dstRadiusPx) {
        this.dstRadius = Math.max(0f, dstRadiusPx);
    }

    void prepareInitialHiddenState(boolean animated) {
        if (!animated) return;
        surface.setAlpha(0f);
        if (scrim != null && scrim != surface) scrim.setAlpha(0f);
        for (View content : contentViews) {
            if (content != null) content.setAlpha(0f);
        }
    }

    boolean isExiting() {
        return exited || (animator != null && animator.isRunning() && !entering);
    }

    void playEnter(boolean animated, @Nullable Runnable onEnd) {
        clearPreDraw();
        exited = false;
        if (!animated) {
            cancelAnimatorOnly();
            progress = 1f;
            entering = true;
            surface.setVisibility(View.VISIBLE);
            if (scrim != null && scrim != surface) scrim.setAlpha(1f);
            surface.setScaleX(1f);
            surface.setScaleY(1f);
            surface.setTranslationX(0f);
            surface.setTranslationY(0f);
            surface.setAlpha(1f);
            for (View content : contentViews) {
                if (content != null) content.setAlpha(1f);
            }
            if (dstRadius > 0.5f) {
                surface.post(() -> {
                    if (surface.getWidth() > 0 && surface.getHeight() > 0) {
                        outlineLeft = 0;
                        outlineTop = 0;
                        outlineRight = surface.getWidth();
                        outlineBottom = surface.getHeight();
                        outlineRadius = dstRadius;
                        surface.setClipToOutline(true);
                        surface.invalidateOutline();
                    }
                });
            } else {
                surface.setClipToOutline(false);
            }
            if (onEnd != null) onEnd.run();
            return;
        }

        if (surface.isLaidOut() && surface.getWidth() > 0 && surface.getHeight() > 0 && !dstRect.isEmpty()) {
            ensureValidSourceRect();
            boolean reversingFromExit = animator != null && animator.isRunning() && !entering;
            if (!reversingFromExit) {
                progress = 0f;
                surface.setClipToOutline(true);
                applyProgress(0f, true);
            }
            pendingEnd = onEnd;
            animateTo(1f, true);
            return;
        }

        prepareInitialHiddenState(true);
        preDrawListener = new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                if (surface.getWidth() <= 0 || surface.getHeight() <= 0) return true;
                clearPreDraw();
                captureIdentityDestination();
                ensureValidSourceRect();
                progress = 0f;
                entering = true;
                surface.setClipToOutline(true);
                applyProgress(0f, true);
                pendingEnd = onEnd;
                animateTo(1f, true);
                return true;
            }
        };
        surface.getViewTreeObserver().addOnPreDrawListener(preDrawListener);
    }

    void playExit(boolean animated, @NonNull Runnable onEnd) {
        if (exited) return;
        clearPreDraw();
        if (!animated || !surface.isAttachedToWindow() || surface.getWidth() <= 0 || surface.getHeight() <= 0) {
            cancelAnimatorOnly();
            exited = true;
            onEnd.run();
            return;
        }
        if (animator != null && animator.isRunning() && !entering) {
            pendingEnd = onEnd;
            return;
        }
        if (dstRect.isEmpty()) {
            captureIdentityDestination();
        }
        ensureValidSourceRect();
        surface.setClipToOutline(true);
        pendingEnd = onEnd;
        animateTo(0f, false);
    }

    void cancelAndReset() {
        clearPreDraw();
        pendingEnd = null;
        cancelAnimatorOnly();
        progress = 1f;
        entering = true;
        exited = false;
        surface.setScaleX(1f);
        surface.setScaleY(1f);
        surface.setTranslationX(0f);
        surface.setTranslationY(0f);
        surface.setAlpha(1f);
        surface.setClipToOutline(dstRadius > 0.5f);
        if (dstRadius > 0.5f && surface.getWidth() > 0 && surface.getHeight() > 0) {
            outlineLeft = 0;
            outlineTop = 0;
            outlineRight = surface.getWidth();
            outlineBottom = surface.getHeight();
            outlineRadius = dstRadius;
            surface.invalidateOutline();
        }
        if (scrim != null && scrim != surface) scrim.setAlpha(1f);
        for (View content : contentViews) {
            if (content != null) content.setAlpha(1f);
        }
    }

    private void animateTo(float target, boolean enter) {
        cancelAnimatorOnly();
        entering = enter;
        float start = progress;
        float delta = Math.abs(target - start);
        if (delta < 0.002f) {
            progress = target;
            applyProgress(target, enter);
            finishAnimation(target);
            return;
        }
        long duration = Math.max(85L, Math.round(DURATION_MS * delta));
        ValueAnimator va = ValueAnimator.ofFloat(start, target);
        va.setDuration(duration);
        va.setInterpolator(interpolator);
        va.addUpdateListener(anim -> {
            progress = (float) anim.getAnimatedValue();
            applyProgress(progress, enter);
        });
        va.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (animator == va) animator = null;
                if (!cancelled) {
                    progress = target;
                    applyProgress(target, enter);
                    finishAnimation(target);
                }
            }
        });
        animator = va;
        va.start();
    }

    private void finishAnimation(float target) {
        if (target >= 0.999f && dstRadius <= 0.5f) {
            surface.setClipToOutline(false);
        }
        if (target <= 0.001f) {
            exited = true;
        }
        Runnable r = pendingEnd;
        pendingEnd = null;
        if (r != null) r.run();
    }

    private void applyProgress(float p, boolean enter) {
        float t = clamp01(p);
        int dstW = Math.max(1, dstRect.width());
        int dstH = Math.max(1, dstRect.height());

        interpolateRect(srcRect, dstRect, t, curRect);
        int curW = Math.max(1, curRect.width());
        int curH = Math.max(1, curRect.height());
        float curRadius = interpolateRadius(srcRadius, dstRadius, t);

        float scale = uniformCoverScale(curW, curH, dstW, dstH);
        float visLocalW = Math.min(dstW, curW / scale);
        float visLocalH = Math.min(dstH, curH / scale);
        float insetX = (dstW - visLocalW) * 0.5f;
        float insetY = (dstH - visLocalH) * 0.5f;

        outlineLeft = Math.round(insetX);
        outlineTop = Math.round(insetY);
        outlineRight = Math.max(outlineLeft + 1, Math.round(dstW - insetX));
        outlineBottom = Math.max(outlineTop + 1, Math.round(dstH - insetY));
        outlineRadius = curRadius / scale;

        surface.setScaleX(scale);
        surface.setScaleY(scale);
        surface.setTranslationX((curRect.left - dstRect.left) - insetX * scale);
        surface.setTranslationY((curRect.top - dstRect.top) - insetY * scale);
        surface.setAlpha(shellAlpha(t));
        surface.invalidateOutline();

        if (scrim != null && scrim != surface) {
            scrim.setAlpha(scrimAlpha(t));
        }
        if (contentViews.length > 0) {
            float ca = contentRevealAlpha(t, enter);
            for (View content : contentViews) {
                if (content != null) content.setAlpha(ca);
            }
        }
    }

    private void captureIdentityDestination() {
        float oldSx = surface.getScaleX();
        float oldSy = surface.getScaleY();
        float oldTx = surface.getTranslationX();
        float oldTy = surface.getTranslationY();
        surface.setScaleX(1f);
        surface.setScaleY(1f);
        surface.setTranslationX(0f);
        surface.setTranslationY(0f);
        int[] loc = new int[2];
        surface.getLocationOnScreen(loc);
        dstRect.set(loc[0], loc[1], loc[0] + surface.getWidth(), loc[1] + surface.getHeight());
        surface.setScaleX(oldSx);
        surface.setScaleY(oldSy);
        surface.setTranslationX(oldTx);
        surface.setTranslationY(oldTy);
    }

    private void ensureValidSourceRect() {
        if (hasExplicitSource && !srcRect.isEmpty()) return;
        float density = surface.getResources().getDisplayMetrics().density;
        fallbackSourceRect(dstRect, density, srcRect);
        srcRadius = Math.round(16f * density);
    }

    private void clearPreDraw() {
        if (preDrawListener != null) {
            ViewTreeObserver vto = surface.getViewTreeObserver();
            if (vto.isAlive()) vto.removeOnPreDrawListener(preDrawListener);
            preDrawListener = null;
        }
    }

    private void cancelAnimatorOnly() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    // ---- Pure math helpers (unit-tested on JVM) ----

    static float clamp01(float v) {
        return v < 0f ? 0f : Math.min(1f, v);
    }

    static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    static void interpolateRect(@NonNull Rect src, @NonNull Rect dst, float t, @NonNull Rect out) {
        int l = Math.round(lerp(src.left, dst.left, t));
        int top = Math.round(lerp(src.top, dst.top, t));
        int r = Math.max(l + 1, Math.round(lerp(src.right, dst.right, t)));
        int b = Math.max(top + 1, Math.round(lerp(src.bottom, dst.bottom, t)));
        out.set(l, top, r, b);
    }

    static void interpolateBounds(int sl, int st, int sr, int sb,
                                  int dl, int dt, int dr, int db, float t, int[] out) {
        int l = Math.round(lerp(sl, dl, t));
        int top = Math.round(lerp(st, dt, t));
        int r = Math.max(l + 1, Math.round(lerp(sr, dr, t)));
        int b = Math.max(top + 1, Math.round(lerp(sb, db, t)));
        out[0] = l;
        out[1] = top;
        out[2] = r;
        out[3] = b;
    }

    static float interpolateRadius(float srcRadius, float dstRadius, float t) {
        return Math.max(0f, lerp(srcRadius, dstRadius, t));
    }

    static float uniformCoverScale(int curW, int curH, int dstW, int dstH) {
        if (dstW <= 0 || dstH <= 0) return 1f;
        float sx = curW / (float) dstW;
        float sy = curH / (float) dstH;
        return Math.max(MIN_SCALE, Math.max(sx, sy));
    }

    static float shellAlpha(float progress) {
        return clamp01(progress / 0.16f);
    }

    static float scrimAlpha(float progress) {
        return clamp01(progress / 0.85f);
    }

    static float contentRevealAlpha(float progress, boolean entering) {
        if (entering) {
            return clamp01((progress - 0.10f) / 0.62f);
        }
        return clamp01((progress - 0.25f) / 0.60f);
    }

    static void fallbackSourceRect(@NonNull Rect dst, float density, @NonNull Rect out) {
        int[] b = new int[4];
        fallbackBounds(dst.left, dst.top, dst.right, dst.bottom, density, b);
        out.set(b[0], b[1], b[2], b[3]);
    }

    static void fallbackBounds(int dl, int dt, int dr, int db, float density, int[] out) {
        int dstW = Math.max(1, dr - dl);
        int dstH = Math.max(1, db - dt);
        int w = Math.min(Math.round(160f * density), Math.max(2, dstW / 4));
        int h = Math.min(Math.round(90f * density), Math.max(2, dstH / 4));
        int cx = (dl + dr) / 2;
        int cy = dt + Math.round(dstH * 0.68f);
        out[0] = cx - w / 2;
        out[1] = cy - h / 2;
        out[2] = out[0] + w;
        out[3] = out[1] + h;
    }
}
