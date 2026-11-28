package com.gathin.tvhome;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * Shared Apple TV-style dark frosted / opaque-accessibility row styling, focus lift, and dialogs.
 * Reuses static selector drawables with zero per-focus bitmap allocation.
 */
final class AppleTvUi {
    private static final DecelerateInterpolator FOCUS_EASE = new DecelerateInterpolator(1.6f);

    private AppleTvUi() {}

    static int dp(Context ctx, int dp) {
        return Math.round(dp * ctx.getResources().getDisplayMetrics().density);
    }

    static int panelBgRes(boolean reduceTransparency) {
        return reduceTransparency ? R.drawable.quick_panel_opaque_bg : R.drawable.quick_panel_frosted_bg;
    }

    static int rowBgRes(boolean reduceTransparency) {
        return reduceTransparency ? R.drawable.appletv_row_opaque : R.drawable.appletv_row_frosted;
    }

    static int tileBgRes(boolean reduceTransparency) {
        return reduceTransparency ? R.drawable.quick_tile_opaque_bg : R.drawable.quick_tile_frosted_bg;
    }

    static int rowTitleColorRes(boolean reduceTransparency) {
        return reduceTransparency ? R.color.appletv_row_opaque_title_color : R.color.appletv_row_title_color;
    }

    static int rowValueColorRes(boolean reduceTransparency) {
        return reduceTransparency ? R.color.appletv_row_opaque_value_color : R.color.appletv_row_value_color;
    }

    static int tileTextColorRes(boolean reduceTransparency) {
        return reduceTransparency ? R.color.quick_tile_opaque_text_color : R.color.quick_tile_text_color;
    }

    static int tileSubColorRes(boolean reduceTransparency) {
        return reduceTransparency ? R.color.quick_tile_opaque_sub_color : R.color.quick_tile_sub_color;
    }

    static int tileIconColorRes(boolean reduceTransparency) {
        return reduceTransparency ? R.color.quick_tile_opaque_icon_color : R.color.quick_tile_icon_color;
    }

    static void applyRowVisual(Context ctx, View row, TextView title, TextView value,
                               ImageView chevron, boolean reduceTransparency) {
        row.setBackgroundResource(rowBgRes(reduceTransparency));
        if (title != null) title.setTextColor(ctx.getColorStateList(rowTitleColorRes(reduceTransparency)));
        if (value != null) value.setTextColor(ctx.getColorStateList(rowValueColorRes(reduceTransparency)));
        if (chevron != null) {
            chevron.setImageTintList(ctx.getColorStateList(rowValueColorRes(reduceTransparency)));
            chevron.setImageTintMode(PorterDuff.Mode.SRC_IN);
        }
    }

    /**
     * Lightweight hardware-accelerated focus lift & sound cue without bitmap allocations.
     * Preserves any supplied focus callback.
     */
    static void attachRowFocus(View view, boolean animations, boolean reducedMotion,
                               View.OnFocusChangeListener onFocus) {
        final float liftScale = (animations && !reducedMotion) ? 1.018f : 1.0f;
        view.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) UiFeedback.playFocus(v.getContext());
            v.animate().cancel();
            if (animations && !reducedMotion && liftScale != 1.0f) {
                v.animate()
                        .scaleX(hasFocus ? liftScale : 1f)
                        .scaleY(hasFocus ? liftScale : 1f)
                        .setDuration(90)
                        .setInterpolator(FOCUS_EASE)
                        .start();
            } else {
                v.setScaleX(1f);
                v.setScaleY(1f);
            }
            if (onFocus != null) onFocus.onFocusChange(v, hasFocus);
        });
    }

    interface ItemClick {
        void onClick(int index);
    }

    static Dialog showListDialog(Activity activity, String titleText, String subtitleText,
                                 List<String> items, int selectedIndex, boolean reduceTransparency,
                                 boolean animations, boolean reducedMotion, ItemClick onPick) {
        Dialog dialog = baseDialog(activity, reduceTransparency);
        LinearLayout card = makeDialogCard(activity, reduceTransparency, 540);

        addDialogHeader(activity, card, titleText, subtitleText, reduceTransparency);

        ScrollView scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        int maxH = Math.round(activity.getResources().getDisplayMetrics().heightPixels * 0.56f);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setClipChildren(false);
        list.setClipToPadding(false);
        list.setPadding(dp(activity, 4), dp(activity, 4), dp(activity, 4), dp(activity, 4));
        scroll.addView(list);
        card.addView(scroll, scrollLp);
        scroll.post(() -> {
            if (scroll.getHeight() > maxH) {
                ViewGroup.LayoutParams lp = scroll.getLayoutParams();
                lp.height = maxH;
                scroll.setLayoutParams(lp);
            }
        });

        View initialFocus = null;
        for (int i = 0; i < items.size(); i++) {
            final int idx = i;
            View row = buildChoiceRow(activity, items.get(i), i == selectedIndex, reduceTransparency, animations, reducedMotion, v -> {
                UiFeedback.playClick(v);
                dialog.dismiss();
                if (onPick != null) onPick.onClick(idx);
            });
            list.addView(row);
            if (i == selectedIndex || (selectedIndex < 0 && i == 0)) initialFocus = row;
        }

        View cancel = buildChoiceRow(activity, activity.getString(android.R.string.cancel), false,
                reduceTransparency, animations, reducedMotion, v -> {
                    UiFeedback.playClick(v);
                    dialog.dismiss();
                });
        ((LinearLayout.LayoutParams) cancel.getLayoutParams()).topMargin = dp(activity, 6);
        list.addView(cancel);

        ((ViewGroup) dialog.getWindow().getDecorView().findViewById(android.R.id.content)).addView(
                wrapCentered(activity, card, dialog, reduceTransparency));
        dialog.show();
        if (initialFocus != null) initialFocus.requestFocus();
        return dialog;
    }

    static Dialog showConfirmDialog(Activity activity, String titleText, String messageText,
                                    String confirmLabel, boolean reduceTransparency,
                                    boolean animations, boolean reducedMotion, Runnable onConfirm) {
        Dialog dialog = baseDialog(activity, reduceTransparency);
        LinearLayout card = makeDialogCard(activity, reduceTransparency, 480);
        addDialogHeader(activity, card, titleText, messageText, reduceTransparency);

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setClipChildren(false);
        actions.setClipToPadding(false);
        actions.setPadding(dp(activity, 4), dp(activity, 4), dp(activity, 4), dp(activity, 4));
        card.addView(actions);

        View okBtn = buildChoiceRow(activity, confirmLabel, false, reduceTransparency, animations, reducedMotion, v -> {
            UiFeedback.playClick(v);
            dialog.dismiss();
            if (onConfirm != null) onConfirm.run();
        });
        actions.addView(okBtn);

        View cancelBtn = buildChoiceRow(activity, activity.getString(android.R.string.cancel), false,
                reduceTransparency, animations, reducedMotion, v -> {
                    UiFeedback.playClick(v);
                    dialog.dismiss();
                });
        actions.addView(cancelBtn);

        ((ViewGroup) dialog.getWindow().getDecorView().findViewById(android.R.id.content)).addView(
                wrapCentered(activity, card, dialog, reduceTransparency));
        dialog.show();
        cancelBtn.requestFocus();
        return dialog;
    }

    static Dialog showKeyCaptureDialog(Activity activity, String titleText, String messageText,
                                       boolean reduceTransparency, boolean animations, boolean reducedMotion,
                                       Dialog.OnKeyListener keyListener) {
        Dialog dialog = baseDialog(activity, reduceTransparency);
        LinearLayout card = makeDialogCard(activity, reduceTransparency, 480);
        addDialogHeader(activity, card, titleText, messageText, reduceTransparency);

        View cancelBtn = buildChoiceRow(activity, activity.getString(android.R.string.cancel), false,
                reduceTransparency, animations, reducedMotion, v -> {
                    UiFeedback.playClick(v);
                    dialog.dismiss();
                });
        card.addView(cancelBtn);
        dialog.setOnKeyListener(keyListener);

        ((ViewGroup) dialog.getWindow().getDecorView().findViewById(android.R.id.content)).addView(
                wrapCentered(activity, card, dialog, reduceTransparency));
        dialog.show();
        cancelBtn.requestFocus();
        return dialog;
    }

    private static Dialog baseDialog(Activity activity, boolean reduceTransparency) {
        Dialog dialog = new Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        Window win = dialog.getWindow();
        if (win != null) {
            win.setBackgroundDrawable(new ColorDrawable(reduceTransparency ? 0xE80A0B0F : 0xB80A0C12));
            win.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
        }
        return dialog;
    }

    private static View wrapCentered(Context ctx, LinearLayout card, Dialog dialog, boolean reduceTransparency) {
        LinearLayout outer = new LinearLayout(ctx);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setGravity(Gravity.CENTER);
        outer.setPadding(dp(ctx, 48), dp(ctx, 32), dp(ctx, 48), dp(ctx, 32));
        outer.setClipChildren(false);
        outer.setClipToPadding(false);
        outer.setOnClickListener(v -> dialog.dismiss());
        outer.addView(card);
        return outer;
    }

    private static LinearLayout makeDialogCard(Context ctx, boolean reduceTransparency, int widthDp) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(panelBgRes(reduceTransparency));
        card.setPadding(dp(ctx, 24), dp(ctx, 22), dp(ctx, 24), dp(ctx, 20));
        card.setClipChildren(false);
        card.setClipToPadding(false);
        card.setClickable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(ctx, widthDp), ViewGroup.LayoutParams.WRAP_CONTENT);
        card.setLayoutParams(lp);
        return card;
    }

    private static void addDialogHeader(Context ctx, LinearLayout card, String titleText,
                                        String subtitleText, boolean reduceTransparency) {
        TextView title = new TextView(ctx);
        title.setText(titleText);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f);
        title.setTextColor(0xFFF5F6FA);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setPadding(dp(ctx, 4), 0, dp(ctx, 4), dp(ctx, subtitleText == null ? 14 : 6));
        card.addView(title);
        if (subtitleText != null && !subtitleText.isEmpty()) {
            TextView sub = new TextView(ctx);
            sub.setText(subtitleText);
            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
            sub.setTextColor(reduceTransparency ? 0xFFD4D8E2 : 0xFFA4AAB8);
            sub.setPadding(dp(ctx, 4), 0, dp(ctx, 4), dp(ctx, 14));
            card.addView(sub);
        }
    }

    private static View buildChoiceRow(Context ctx, String label, boolean checked, boolean reduceTransparency,
                                       boolean animations, boolean reducedMotion, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPaddingRelative(dp(ctx, 16), dp(ctx, 11), dp(ctx, 16), dp(ctx, 11));
        row.setFocusable(true);
        row.setClickable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(ctx, 6);
        row.setLayoutParams(lp);

        TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        tv.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setDuplicateParentStateEnabled(true);
        row.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        ImageView check = null;
        if (checked) {
            check = new ImageView(ctx);
            check.setImageResource(R.drawable.ic_check);
            check.setDuplicateParentStateEnabled(true);
            int sz = dp(ctx, 18);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(sz, sz);
            clp.setMarginStart(dp(ctx, 10));
            row.addView(check, clp);
        }

        applyRowVisual(ctx, row, tv, null, check, reduceTransparency);
        attachRowFocus(row, animations, reducedMotion, null);
        row.setOnClickListener(click);
        return row;
    }
}
