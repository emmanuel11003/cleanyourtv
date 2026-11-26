package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

/**
 * Package-private popup and header menu builder for {@link MainActivity}: glass dropdowns,
 * top gear menu, and settings long-press menu.
 */
final class HomeMenus {
    private static final DecelerateInterpolator DROP_INTERPOLATOR = new DecelerateInterpolator(1.6f);

    private HomeMenus() {
    }

    static PopupWindow glassPopup(MainActivity host, View panel, int width) {
        final PopupWindow popup = new PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT)); // needed for Back to dismiss
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);
        popup.setOnDismissListener(() -> {
            if (host.openPopup == popup) host.openPopup = null;
        });
        host.openPopup = popup;
        return popup;
    }

    /** Drop in: fade and unfold from the top edge, hanging under the anchor pill. */
    static void dropIn(PopupWindow popup, View panel, View anchor, int gravity,
                       float density, boolean animations, boolean reducedMotion) {
        if (!animations) {
            popup.showAsDropDown(anchor, 0, (int) (10 * density), gravity);
            return;
        }
        panel.setAlpha(0f);
        panel.setPivotY(0f);
        panel.setScaleY(0.92f);
        panel.setTranslationY(-8 * density);
        popup.showAsDropDown(anchor, 0, (int) (10 * density), gravity);
        springIn(panel, reducedMotion);
    }

    /** Smoothly springs a popup panel into place (scaleY + translationY) with alpha fade. */
    static void springIn(View panel, boolean reducedMotion) {
        if (reducedMotion) {
            panel.animate().alpha(1f).scaleY(1f).translationY(0f)
                    .setDuration(Motion.REDUCED_MS)
                    .setInterpolator(DROP_INTERPOLATOR).start();
            return;
        }
        panel.animate().alpha(1f).setDuration(120).setInterpolator(DROP_INTERPOLATOR).start();
        SpringAnimation sy = new SpringAnimation(panel, DynamicAnimation.SCALE_Y, 1f);
        SpringAnimation ty = new SpringAnimation(panel, DynamicAnimation.TRANSLATION_Y, 0f);
        sy.setSpring(new SpringForce(1f).setStiffness(620f).setDampingRatio(0.86f));
        ty.setSpring(new SpringForce(0f).setStiffness(620f).setDampingRatio(0.86f));
        sy.start();
        ty.start();
    }

    static View addSeparator(Context ctx, LinearLayout rows, float density) {
        View line = new View(ctx);
        line.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) density);
        int m = (int) (6 * density), h = (int) (12 * density);
        lp.setMargins(h, m, h, m);
        rows.addView(line, lp);
        return line;
    }

    static View addInputRow(LayoutInflater inflater, LinearLayout rows, PopupWindow popup,
                            int iconRes, String title, int state, Runnable action) {
        View row = inflater.inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        TextView status = row.findViewById(R.id.status);
        View dot = row.findViewById(R.id.dot);
        if (state == android.media.tv.TvInputManager.INPUT_STATE_CONNECTED) {
            status.setText(R.string.connected);
            dot.setVisibility(View.VISIBLE);
        } else if (state == android.media.tv.TvInputManager.INPUT_STATE_DISCONNECTED) {
            status.setText(R.string.not_connected);
            dot.setVisibility(View.GONE);
        } else {
            status.setVisibility(View.GONE);
            dot.setVisibility(View.GONE);
        }
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        rows.addView(row);
        return row;
    }

    static void addSettingsMenuRow(LayoutInflater inflater, LinearLayout rows, PopupWindow popup,
                                   int title, Runnable action) {
        View row = inflater.inflate(R.layout.item_input, rows, false);
        row.findViewById(R.id.icon).setVisibility(View.GONE);
        row.findViewById(R.id.dot).setVisibility(View.GONE);
        row.findViewById(R.id.status).setVisibility(View.GONE);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        // The OK-up after opening the menu must not activate its first row.
        final boolean[] pressed = {false};
        row.setOnKeyListener((v, code, event) -> {
            if (code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) pressed[0] = true;
                return true;
            }
            boolean activate = pressed[0];
            pressed[0] = false;
            if (activate) {
                popup.dismiss();
                action.run();
            }
            return true;
        });
        rows.addView(row);
    }

    /** top_menu: the gear's dropdown, standing in for the Inputs, Wi-Fi and Settings pills. */
    static void showTopMenu(MainActivity host) {
        View anchor = host.findViewById(R.id.btnSettings);
        LayoutInflater inflater = host.getLayoutInflater();
        View panel = inflater.inflate(R.layout.popup_inputs, host.root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow popup = glassPopup(host, panel, (int) (220 * host.density));
        if (host.showInputsMenuRow) {
            addInputRow(inflater, rows, popup, R.drawable.ic_input, host.getString(R.string.inputs), -1,
                    () -> host.root.post(() -> TvInputRouter.showInputs(host)));
        }
        addInputRow(inflater, rows, popup, R.drawable.ic_wifi, host.getString(R.string.quick_network), -1,
                host::openNetworkPill);
        CharSequence network = host.wifiIconOnly ? host.btnWifi.getContentDescription() : host.btnWifi.getText();
        if (!TextUtils.isEmpty(network)) {
            TextView status = rows.getChildAt(rows.getChildCount() - 1).findViewById(R.id.status);
            status.setText(network);
            status.setVisibility(View.VISIBLE);
        }
        addInputRow(inflater, rows, popup, R.drawable.ic_swap_vert, host.getString(R.string.reorder), -1,
                host::toggleReorder);
        if (host.launcherSettingsButton) {
            addInputRow(inflater, rows, popup, R.drawable.ic_home_settings_pill,
                    host.getString(R.string.launcher_settings), -1,
                    () -> host.openOwnedActivity(new Intent(host, LauncherSettingsActivity.class),
                            anchor, MainActivity.LAUNCHER_SETTINGS_REQUEST));
        } else {
            addInputRow(inflater, rows, popup, R.drawable.ic_settings, host.getString(R.string.settings), -1,
                    () -> host.open(host.systemIntent(Settings.ACTION_SETTINGS)));
        }
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setWidth(Math.max((int) (220 * host.density),
                Math.min(panel.getMeasuredWidth(), (int) (360 * host.density))));
        dropIn(popup, panel, anchor,
                host.getResources().getBoolean(R.bool.rtl_menu_bar) ? Gravity.START : Gravity.END,
                host.density, host.animations, host.reducedMotion);
        rows.getChildAt(0).requestFocus();
    }

    /** The header opens launcher settings directly; hold OK keeps the extra home actions available. */
    static void showSettingsMenu(MainActivity host) {
        View anchor = host.findViewById(R.id.btnSettings);
        LayoutInflater inflater = host.getLayoutInflater();
        View panel = inflater.inflate(R.layout.popup_inputs, host.root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow popup = glassPopup(host, panel, (int) (200 * host.density));
        if (!host.launcherSettingsButton) {
            addSettingsMenuRow(inflater, rows, popup, R.string.launcher_settings,
                    () -> host.openOwnedActivity(new Intent(host, LauncherSettingsActivity.class),
                            anchor, MainActivity.LAUNCHER_SETTINGS_REQUEST));
        }
        Intent vendor = SystemSettings.vendorIntent(host);
        if (vendor != null) {
            addSettingsMenuRow(inflater, rows, popup, R.string.native_settings, () -> host.open(vendor));
        }
        if (host.moduleCards) {
            addSettingsMenuRow(inflater, rows, popup, R.string.module_cards,
                    () -> host.moduleCardController.showModuleCards(host));
        }
        if (host.dockRow) {
            addSettingsMenuRow(inflater, rows, popup, R.string.dock_edit, host::startDockEdit);
        }
        addSettingsMenuRow(inflater, rows, popup, R.string.search, () -> SpotlightDialog.show(host));
        addSettingsMenuRow(inflater, rows, popup, R.string.wallpaper_sync_title,
                () -> host.openOwnedActivity(new Intent(host, WallpaperSyncActivity.class), anchor, -1));
        addSettingsMenuRow(inflater, rows, popup, R.string.reorder, host::toggleReorder);
        if (HomeKeyService.canOpenPowerMenu()) {
            addSettingsMenuRow(inflater, rows, popup, R.string.power_menu, HomeKeyService::openPowerMenu);
        }
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setWidth(Math.max((int) (200 * host.density), panel.getMeasuredWidth()));
        dropIn(popup, panel, anchor,
                host.getResources().getBoolean(R.bool.rtl_menu_bar) ? Gravity.START : Gravity.END,
                host.density, host.animations, host.reducedMotion);
        rows.getChildAt(0).requestFocus();
    }
}
