package com.gathin.tvhome;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * Interactive TV RAM Cleaner & Memory Manager dialog.
 * Shows live /proc/meminfo usage bar, one-tap MemoryBooster action, and a list of
 * recently active apps with direct 1-click access to Android's App Info / Force Stop.
 */
final class RamManagerDialog {
    private RamManagerDialog() {}

    static void show(Context ctx, Runnable onMemoryChanged) {
        if (ctx == null) return;
        final float d = ctx.getResources().getDisplayMetrics().density;
        final Dialog dialog = new Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        Window win = dialog.getWindow();
        if (win != null) {
            win.setBackgroundDrawable(new ColorDrawable(0xCC070A10));
            win.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            win.setDimAmount(0.55f);
        }

        LinearLayout outer = new LinearLayout(ctx);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setGravity(Gravity.CENTER);
        outer.setPadding(dp(d, 48), dp(d, 32), dp(d, 48), dp(d, 32));
        outer.setOnClickListener(v -> dialog.dismiss());

        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(d, 28), dp(d, 24), dp(d, 28), dp(d, 24));
        card.setClickable(true);
        card.setBackground(roundRect(0xF2151924, dp(d, 20), 0x33FFFFFF, dp(d, 1)));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(dp(d, 640), LinearLayout.LayoutParams.WRAP_CONTENT);
        outer.addView(card, cardLp);

        // Header row
        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(header);

        TextView title = new TextView(ctx);
        title.setText(R.string.ram_manager_title);
        title.setTextSize(22f);
        title.setTextColor(0xFFFFFFFF);
        title.setTypeface(Typeface.SANS_SERIF, Typeface.NORMAL);
        header.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        final TextView pctBadge = new TextView(ctx);
        pctBadge.setTextSize(15f);
        pctBadge.setTextColor(0xFFFFFFFF);
        pctBadge.setTypeface(Typeface.SANS_SERIF, Typeface.NORMAL);
        header.addView(pctBadge);

        // Memory summary text
        final TextView summary = new TextView(ctx);
        summary.setTextSize(14f);
        summary.setTextColor(0xFFFFFFFF);
        summary.setTypeface(Typeface.SANS_SERIF, Typeface.NORMAL);
        LinearLayout.LayoutParams sumLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sumLp.topMargin = dp(d, 8);
        card.addView(summary, sumLp);

        // Visual RAM bar track + fill
        LinearLayout barTrack = new LinearLayout(ctx);
        barTrack.setOrientation(LinearLayout.HORIZONTAL);
        barTrack.setBackground(roundRect(0x28FFFFFF, dp(d, 6), 0, 0));
        LinearLayout.LayoutParams trackLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(d, 10));
        trackLp.topMargin = dp(d, 10);
        card.addView(barTrack, trackLp);

        final View barFill = new View(ctx);
        final View barRest = new View(ctx);
        barTrack.addView(barFill, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 50f));
        barTrack.addView(barRest, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 50f));

        // Status feedback line
        final TextView statusLine = new TextView(ctx);
        statusLine.setTextSize(13f);
        statusLine.setTextColor(0xFFA8C7FA);
        statusLine.setTypeface(Typeface.SANS_SERIF, Typeface.NORMAL);
        statusLine.setVisibility(View.GONE);
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        stLp.topMargin = dp(d, 10);
        card.addView(statusLine, stLp);

        // Primary Boost button
        final TextView boostBtn = new TextView(ctx);
        boostBtn.setText(R.string.ram_manager_boost_btn);
        boostBtn.setTextSize(15f);
        boostBtn.setTextColor(0xFFFFFFFF);
        boostBtn.setTypeface(Typeface.SANS_SERIF, Typeface.NORMAL);
        boostBtn.setGravity(Gravity.CENTER);
        boostBtn.setPadding(dp(d, 18), dp(d, 12), dp(d, 18), dp(d, 12));
        boostBtn.setFocusable(true);
        boostBtn.setFocusableInTouchMode(true);
        boostBtn.setBackground(pillSelector(d, 0xFF2855C5, 0xFF3A72F6));
        Motion.attachSmoothFocus(boostBtn, true, 1.0f);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnLp.topMargin = dp(d, 16);
        card.addView(boostBtn, btnLp);

        // Android 14+ informational note (transparent & honest)
        if (!MemoryBooster.canKillOtherApps()) {
            TextView note = new TextView(ctx);
            note.setText(R.string.ram_manager_api34_note);
            note.setTextSize(12f);
            note.setTextColor(0xFFFFFFFF);
            note.setTypeface(Typeface.SANS_SERIF, Typeface.NORMAL);
            LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            noteLp.topMargin = dp(d, 10);
            card.addView(note, noteLp);
        }

        // Section label for recent apps
        TextView secLabel = new TextView(ctx);
        secLabel.setText(R.string.ram_manager_recent_apps);
        secLabel.setTextSize(13f);
        secLabel.setTextColor(0xFFFFFFFF);
        secLabel.setTypeface(Typeface.SANS_SERIF, Typeface.NORMAL);
        LinearLayout.LayoutParams secLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        secLp.topMargin = dp(d, 16);
        secLp.bottomMargin = dp(d, 8);
        card.addView(secLabel, secLp);

        ScrollView scroll = new ScrollView(ctx);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(d, 220));
        card.addView(scroll, scrollLp);

        final LinearLayout listBox = new LinearLayout(ctx);
        listBox.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(listBox, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        final boolean[] busy = {false};
        boostBtn.setOnClickListener(v -> {
            if (busy[0]) return;
            busy[0] = true;
            boostBtn.setText(R.string.boost_ram_running);
            LauncherExecutors.io().execute(() -> {
                MemoryBooster.BoostResult res = MemoryBooster.boost(ctx);
                LauncherExecutors.main().post(() -> {
                    busy[0] = false;
                    if (!dialog.isShowing()) return;
                    boostBtn.setText(R.string.ram_manager_boost_btn);
                    applySnapshot(d, res.after, summary, pctBadge, barFill, barRest);
                    statusLine.setText(MemoryBooster.formatResultMessage(ctx, res));
                    statusLine.setVisibility(View.VISIBLE);
                    if (onMemoryChanged != null) onMemoryChanged.run();
                });
            });
        });

        // Populate recent / background apps asynchronously
        LauncherExecutors.io().execute(() -> {
            MemoryBooster.Snapshot snap = MemoryBooster.readSnapshot(ctx);
            List<MemoryBooster.RecentApp> apps = MemoryBooster.recentHeavyApps(ctx, 12);
            PackageManager pm = ctx.getPackageManager();
            LauncherExecutors.main().post(() -> {
                if (!dialog.isShowing()) return;
                applySnapshot(d, snap, summary, pctBadge, barFill, barRest);
                listBox.removeAllViews();
                if (apps.isEmpty()) {
                    TextView empty = new TextView(ctx);
                    empty.setText(R.string.ram_manager_no_recent);
                    empty.setTextSize(14f);
                    empty.setTextColor(0xFFFFFFFF);
                    empty.setPadding(dp(d, 8), dp(d, 12), dp(d, 8), dp(d, 12));
                    listBox.addView(empty);
                } else {
                    for (MemoryBooster.RecentApp app : apps) {
                        listBox.addView(buildAppRow(ctx, d, pm, app, dialog));
                    }
                }
            });
        });

        dialog.setContentView(outer);
        outer.setAlpha(0f);
        dialog.setOnShowListener(di -> {
            outer.animate().alpha(1f).setDuration(150).start();
            boostBtn.requestFocus();
        });
        dialog.show();
    }

    private static void applySnapshot(float d, MemoryBooster.Snapshot snap, TextView summary,
                                      TextView pctBadge, View barFill, View barRest) {
        summary.setText(snap.usedMb + " / " + snap.totalMb + " MB used  ·  " + snap.freeMb + " MB free");
        pctBadge.setText(snap.usedPercent + "% used");
        int barColor = snap.usedPercent >= 82 ? 0xFFFF5A5F
                : (snap.usedPercent >= 65 ? 0xFFF5B041 : 0xFF3DDC97);
        barFill.setBackground(roundRect(barColor, dp(d, 6), 0, 0));
        ((LinearLayout.LayoutParams) barFill.getLayoutParams()).weight = Math.max(1f, snap.usedPercent);
        ((LinearLayout.LayoutParams) barRest.getLayoutParams()).weight = Math.max(0.1f, 100f - snap.usedPercent);
        barFill.requestLayout();
        barRest.requestLayout();
    }

    private static View buildAppRow(Context ctx, float d, PackageManager pm,
                                    MemoryBooster.RecentApp app, Dialog dialog) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(d, 14), dp(d, 10), dp(d, 14), dp(d, 10));
        row.setFocusable(true);
        row.setFocusableInTouchMode(true);
        row.setBackground(pillSelector(d, 0x16FFFFFF, 0x33FFFFFF));
        Motion.attachSmoothFocus(row, true, 1.0f);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = dp(d, 6);
        row.setLayoutParams(rlp);

        ImageView icon = new ImageView(ctx);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        try {
            Drawable dr = pm.getApplicationIcon(app.packageName);
            icon.setImageDrawable(dr);
        } catch (Throwable ignored) {
        }
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(d, 28), dp(d, 28));
        ilp.rightMargin = dp(d, 12);
        row.addView(icon, ilp);

        LinearLayout textCol = new LinearLayout(ctx);
        textCol.setOrientation(LinearLayout.VERTICAL);
        row.addView(textCol, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView name = new TextView(ctx);
        name.setText(app.label);
        name.setTextSize(15f);
        name.setTextColor(0xFFFFFFFF);
        name.setTypeface(Typeface.SANS_SERIF, Typeface.NORMAL);
        name.setSingleLine(true);
        textCol.addView(name);

        if (app.lastUsedMs > 0L) {
            TextView sub = new TextView(ctx);
            sub.setText(LastOpened.describe(ctx, app.lastUsedMs));
            sub.setTextSize(12f);
            sub.setTextColor(0xFFFFFFFF);
            sub.setTypeface(Typeface.SANS_SERIF, Typeface.NORMAL);
            sub.setSingleLine(true);
            textCol.addView(sub);
        }

        TextView action = new TextView(ctx);
        action.setText(R.string.ram_manager_app_info);
        action.setTextSize(13f);
        action.setTextColor(0xFFA8C7FA);
        action.setTypeface(Typeface.SANS_SERIF, Typeface.NORMAL);
        row.addView(action);

        row.setOnClickListener(v -> {
            Intent info = SystemSettings.appInfo(ctx, app.packageName);
            if (info != null) {
                dialog.dismiss();
                try {
                    info.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(info);
                } catch (Throwable ignored) {
                    Toast.makeText(ctx, R.string.not_available, Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(ctx, R.string.not_available, Toast.LENGTH_SHORT).show();
            }
        });
        return row;
    }

    private static StateListDrawable pillSelector(float d, int normalColor, int focusedColor) {
        StateListDrawable sld = new StateListDrawable();
        sld.addState(new int[]{android.R.attr.state_focused},
                roundRect(focusedColor, dp(d, 12), 0xCCFFFFFF, dp(d, 2)));
        sld.addState(new int[]{}, roundRect(normalColor, dp(d, 12), Color.TRANSPARENT, 0));
        return sld;
    }

    private static GradientDrawable roundRect(int fillColor, int radiusPx, int strokeColor, int strokePx) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(fillColor);
        gd.setCornerRadius(radiusPx);
        if (strokePx > 0) gd.setStroke(strokePx, strokeColor);
        return gd;
    }

    private static int dp(float d, int v) {
        return Math.round(v * d);
    }
}
