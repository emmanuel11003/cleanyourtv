package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Built-in translucent Google TV–styled Notification Center panel matching Quick Settings.
 * Reads notifications cached locally by {@link GuardRevival}.
 */
public final class NotificationPanelActivity extends Activity implements GuardRevival.Callback {

    private boolean animateTransitions;
    private boolean reduceTransparency;
    private ScrollView scrollView;
    private LinearLayout listLayout;
    private LinearLayout emptyView;
    private LinearLayout accessOffView;
    private TextView clearAllBtn;
    private View grantAccessCard;

    private PackageManager pm;
    private float density;
    private final Map<String, Drawable> iconCache = new HashMap<>();
    private final Map<String, CharSequence> labelCache = new HashMap<>();

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Config cfg = Config.load(this);
        animateTransitions = cfg.animations && !cfg.reducedMotion;
        reduceTransparency = cfg.reduceTransparency;
        Motion.applyFadeTransition(this, animateTransitions);
        density = getResources().getDisplayMetrics().density;
        pm = getPackageManager();

        FrameLayout scrim = new FrameLayout(this);
        scrim.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        scrim.setClipChildren(false);
        scrim.setClipToPadding(false);
        scrim.setOnClickListener(v -> closeWithMorph());
        scrim.setFocusable(false);

        View scrimBackdrop = new View(this);
        scrimBackdrop.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        scrimBackdrop.setBackgroundColor(reduceTransparency ? 0xCC06070A : 0x48000000);
        scrimBackdrop.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        scrim.addView(scrimBackdrop);

        FrameLayout panel = new FrameLayout(this);
        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(
                dp(290), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END | Gravity.CENTER_VERTICAL);
        panelLp.topMargin = dp(16);
        panelLp.bottomMargin = dp(16);
        panelLp.setMarginEnd(dp(24));
        panel.setLayoutParams(panelLp);
        panel.setBackgroundResource(AppleTvUi.panelBgRes(reduceTransparency));
        panel.setPaddingRelative(dp(14), dp(18), dp(14), dp(14));
        panel.setClipChildren(false);
        panel.setClipToPadding(false);
        panel.setElevation(0f);
        panel.setOnClickListener(v -> {});
        panel.setFocusable(false);
        scrim.addView(panel);

        LinearLayout content = new LinearLayout(this);
        content.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        panel.addView(content);

        content.addView(buildHeader());

        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(1, dp(14)));
        content.addView(spacer);

        // Center body area holding Access Off / Empty / List states
        FrameLayout body = new FrameLayout(this);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        body.setLayoutParams(bodyLp);
        body.setClipChildren(false);
        body.setClipToPadding(false);
        content.addView(body);

        accessOffView = buildAccessOffView();
        body.addView(accessOffView);

        emptyView = buildEmptyView();
        body.addView(emptyView);

        scrollView = new ScrollView(this);
        scrollView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.setClipChildren(false);
        scrollView.setClipToPadding(false);
        scrollView.setPadding(dp(2), dp(2), dp(2), dp(2));

        listLayout = new LinearLayout(this);
        listLayout.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        listLayout.setOrientation(LinearLayout.VERTICAL);
        listLayout.setClipChildren(false);
        listLayout.setClipToPadding(false);
        scrollView.addView(listLayout);
        body.addView(scrollView);

        content.addView(buildFooter());

        setContentView(scrim);
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        LinearLayout.LayoutParams headerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        headerLp.setMarginStart(dp(4));
        headerLp.setMarginEnd(dp(4));
        header.setLayoutParams(headerLp);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setClipChildren(false);
        header.setClipToPadding(false);

        TextView date = new TextView(this);
        date.setText(DateFormat.getDateInstance(DateFormat.FULL).format(new Date()).toUpperCase(Locale.getDefault()));
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        date.setTextColor(0xFFA8C7FA);
        date.setLetterSpacing(0.08f);
        date.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        date.setSingleLine(true);
        date.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(date, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        RelativeLayout titleRow = new RelativeLayout(this);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(4);
        titleRow.setLayoutParams(rowLp);
        titleRow.setClipChildren(false);
        titleRow.setClipToPadding(false);

        clearAllBtn = new TextView(this);
        clearAllBtn.setId(View.generateViewId());
        clearAllBtn.setText("Clear all");
        clearAllBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        clearAllBtn.setTextColor(getColorStateList(AppleTvUi.tileTextColorRes(reduceTransparency)));
        clearAllBtn.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        clearAllBtn.setBackgroundResource(AppleTvUi.tileBgRes(reduceTransparency));
        clearAllBtn.setPaddingRelative(dp(12), dp(6), dp(12), dp(6));
        clearAllBtn.setFocusable(true);
        clearAllBtn.setClickable(true);
        clearAllBtn.setVisibility(View.GONE);
        Motion.attachSmoothFocus(clearAllBtn, animateTransitions, 1.045f);
        clearAllBtn.setOnClickListener(v -> {
            GuardRevival.dismissAll();
            closeWithMorph();
        });
        RelativeLayout.LayoutParams btnLp = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnLp.addRule(RelativeLayout.ALIGN_PARENT_END);
        btnLp.addRule(RelativeLayout.CENTER_VERTICAL);
        titleRow.addView(clearAllBtn, btnLp);

        TextView title = new TextView(this);
        title.setText(R.string.tile_notifications);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f);
        title.setTextColor(0xFFE3E2E6);
        title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        RelativeLayout.LayoutParams tLp = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tLp.addRule(RelativeLayout.ALIGN_PARENT_START);
        tLp.addRule(RelativeLayout.START_OF, clearAllBtn.getId());
        tLp.addRule(RelativeLayout.CENTER_VERTICAL);
        tLp.setMarginEnd(dp(8));
        titleRow.addView(title, tLp);

        header.addView(titleRow);
        return header;
    }

    private LinearLayout buildAccessOffView() {
        LinearLayout box = new LinearLayout(this);
        box.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        box.setGravity(Gravity.CENTER);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);
        box.setClipToPadding(false);

        LinearLayout card = new LinearLayout(this);
        card.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(AppleTvUi.tileBgRes(reduceTransparency));
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.setFocusable(true);
        card.setClickable(true);
        Motion.attachSmoothFocus(card, animateTransitions, 1.03f);
        card.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException | SecurityException ignored) {
            }
            finish();
        });

        TextView head = new TextView(this);
        head.setText("Notification access is off");
        head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        head.setTextColor(getColorStateList(AppleTvUi.tileTextColorRes(reduceTransparency)));
        head.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        head.setDuplicateParentStateEnabled(true);
        card.addView(head);

        TextView sub = new TextView(this);
        sub.setText("Press OK to grant access in Settings");
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        sub.setTextColor(getColorStateList(AppleTvUi.tileSubColorRes(reduceTransparency)));
        sub.setDuplicateParentStateEnabled(true);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(4);
        card.addView(sub, subLp);

        grantAccessCard = card;
        box.addView(card);
        return box;
    }

    private LinearLayout buildEmptyView() {
        LinearLayout box = new LinearLayout(this);
        box.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        box.setGravity(Gravity.CENTER);
        box.setOrientation(LinearLayout.VERTICAL);

        ImageView bell = new ImageView(this);
        bell.setImageResource(R.drawable.ic_notifications);
        bell.setImageTintList(ColorStateList.valueOf(0xFFA8C7FA));
        LinearLayout.LayoutParams bellLp = new LinearLayout.LayoutParams(dp(36), dp(36));
        box.addView(bell, bellLp);

        TextView txt = new TextView(this);
        txt.setText("No notifications");
        txt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        txt.setTextColor(0xFFC4C6D0);
        LinearLayout.LayoutParams txtLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        txtLp.topMargin = dp(10);
        box.addView(txt, txtLp);
        return box;
    }

    private View buildFooter() {
        TextView footer = new TextView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        lp.setMarginStart(dp(4));
        footer.setLayoutParams(lp);
        footer.setText("Notification Center");
        footer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        footer.setTextColor(0xFF5F6368);
        footer.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        return footer;
    }

    @Override
    protected void onResume() {
        super.onResume();
        ensureNotificationAccess();
        GuardRevival.registerCallback(this);
        refreshUi();
    }

    @Override
    protected void onPause() {
        GuardRevival.unregisterCallback(this);
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        closeWithMorph();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        iconCache.clear();
        labelCache.clear();
    }

    @Override
    public void onNotificationsChanged() {
        if (!isFinishing() && !isDestroyed()) {
            runOnUiThread(this::refreshUi);
        }
    }

    private void closeWithMorph() {
        if (!isFinishing()) {
            finish();
            Motion.applyFadeTransition(this, animateTransitions);
        }
    }

    private void ensureNotificationAccess() {
        if (GuardRevival.isConnected()) return;
        ComponentName cn = new ComponentName(this, GuardRevival.class);
        String flat = cn.flattenToString();
        String shortFlat = cn.flattenToShortString();
        String current = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        if (hasEntry(current, flat, shortFlat, cn)) return;
        String next = (current == null || current.trim().isEmpty()) ? flat : current + ":" + flat;
        try {
            Settings.Secure.putString(getContentResolver(), "enabled_notification_listeners", next);
        } catch (SecurityException ignored) {
        }
    }

    private boolean isNotificationAccessGranted() {
        if (GuardRevival.isConnected()) return true;
        String current = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        ComponentName target = new ComponentName(this, GuardRevival.class);
        return hasEntry(current, target.flattenToString(), target.flattenToShortString(), target);
    }

    private static boolean hasEntry(String list, String flat, String shortFlat, ComponentName target) {
        if (list == null || list.isEmpty()) return false;
        for (String entry : list.split(":")) {
            String trimmed = entry.trim();
            if (flat.equals(trimmed) || shortFlat.equals(trimmed)) return true;
            ComponentName parsed = ComponentName.unflattenFromString(trimmed);
            if (target.equals(parsed)) return true;
        }
        return false;
    }

    private void refreshUi() {
        if (!isNotificationAccessGranted()) {
            accessOffView.setVisibility(View.VISIBLE);
            scrollView.setVisibility(View.GONE);
            emptyView.setVisibility(View.GONE);
            clearAllBtn.setVisibility(View.GONE);
            if (grantAccessCard != null) grantAccessCard.requestFocus();
            return;
        }

        accessOffView.setVisibility(View.GONE);
        List<GuardRevival.Item> items = GuardRevival.snapshot();
        if (items.isEmpty()) {
            emptyView.setVisibility(View.VISIBLE);
            scrollView.setVisibility(View.GONE);
            clearAllBtn.setVisibility(View.GONE);
            return;
        }

        emptyView.setVisibility(View.GONE);
        scrollView.setVisibility(View.VISIBLE);
        clearAllBtn.setVisibility(View.VISIBLE);

        listLayout.removeAllViews();
        View firstRow = null;
        long now = System.currentTimeMillis();
        for (int i = 0; i < items.size(); i++) {
            View row = buildRow(items.get(i), now);
            listLayout.addView(row);
            if (firstRow == null) firstRow = row;
        }
        if (firstRow != null) firstRow.requestFocus();
    }

    private View buildRow(GuardRevival.Item item, long now) {
        LinearLayout row = new LinearLayout(this);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.bottomMargin = dp(8);
        row.setLayoutParams(rowLp);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(AppleTvUi.tileBgRes(reduceTransparency));
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setFocusable(true);
        row.setClickable(true);
        Motion.attachSmoothFocus(row, animateTransitions, 1.03f);

        ImageView iconView = new ImageView(this);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(28), dp(28));
        iconLp.setMarginEnd(dp(10));
        iconView.setLayoutParams(iconLp);
        iconView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        iconView.setImageDrawable(loadIcon(item.packageName));
        row.addView(iconView);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setDuplicateParentStateEnabled(true);

        LinearLayout topLine = new LinearLayout(this);
        topLine.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        topLine.setOrientation(LinearLayout.HORIZONTAL);
        topLine.setGravity(Gravity.CENTER_VERTICAL);
        topLine.setDuplicateParentStateEnabled(true);

        TextView titleView = new TextView(this);
        titleView.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        titleView.setText((item.title != null && item.title.length() > 0) ? item.title : loadLabel(item.packageName));
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        titleView.setTextColor(getColorStateList(AppleTvUi.tileTextColorRes(reduceTransparency)));
        titleView.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        titleView.setMaxLines(1);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setDuplicateParentStateEnabled(true);
        topLine.addView(titleView);

        TextView timeView = new TextView(this);
        LinearLayout.LayoutParams timeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        timeLp.setMarginStart(dp(6));
        timeView.setLayoutParams(timeLp);
        long diff = Math.max(0L, now - item.postTime);
        timeView.setText(diff < 60_000L ? "Just now"
                : DateUtils.getRelativeTimeSpanString(item.postTime, now,
                        DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE));
        timeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        timeView.setTextColor(getColorStateList(AppleTvUi.tileSubColorRes(reduceTransparency)));
        timeView.setDuplicateParentStateEnabled(true);
        topLine.addView(timeView);
        textCol.addView(topLine);

        if (item.text != null && item.text.length() > 0) {
            TextView bodyView = new TextView(this);
            LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bodyLp.topMargin = dp(2);
            bodyView.setLayoutParams(bodyLp);
            bodyView.setText(item.text);
            bodyView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            bodyView.setTextColor(getColorStateList(AppleTvUi.tileSubColorRes(reduceTransparency)));
            bodyView.setMaxLines(2);
            bodyView.setEllipsize(TextUtils.TruncateAt.END);
            bodyView.setDuplicateParentStateEnabled(true);
            textCol.addView(bodyView);
        }

        row.addView(textCol);
        row.setOnClickListener(v -> launchNotification(item));
        row.setOnLongClickListener(v -> {
            GuardRevival.dismiss(item.key);
            return true;
        });
        return row;
    }

    private Drawable loadIcon(String pkg) {
        Drawable cached = iconCache.get(pkg);
        if (cached != null) return cached;
        try {
            Drawable icon = pm.getApplicationIcon(pkg);
            iconCache.put(pkg, icon);
            return icon;
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return getDrawable(R.drawable.ic_notifications);
        }
    }

    private CharSequence loadLabel(String pkg) {
        CharSequence cached = labelCache.get(pkg);
        if (cached != null) return cached;
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence label = pm.getApplicationLabel(ai);
            labelCache.put(pkg, label);
            return label;
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return pkg;
        }
    }

    private void launchNotification(GuardRevival.Item item) {
        if (item.contentIntent != null) {
            try {
                item.contentIntent.send();
                finish();
                return;
            } catch (android.app.PendingIntent.CanceledException ignored) {
            }
        }
        Intent launch = pm.getLeanbackLaunchIntentForPackage(item.packageName);
        if (launch == null) launch = pm.getLaunchIntentForPackage(item.packageName);
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(launch);
            } catch (ActivityNotFoundException | SecurityException ignored) {
            }
        }
        finish();
    }

    private int dp(int value) {
        return Math.round(value * density);
    }
}
