package io.github.emmanuel11003.tvnotifications;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Translucent right-side Google TV styled panel for the CleanYourTV launcher.
 * Displays notifications, allows launching apps, dismissing individual rows, or clearing all.
 */
public final class PanelActivity extends Activity implements Notifications.Callback {

    private ScrollView scrollView;
    private LinearLayout layoutNotificationsList;
    private View viewEmpty;
    private View viewAccessOff;
    private View btnClearAll;

    private PackageManager packageManager;
    private final Map<String, Drawable> iconCache = new HashMap<>();
    private final Map<String, CharSequence> labelCache = new HashMap<>();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        setContentView(R.layout.activity_panel);

        packageManager = getPackageManager();

        scrollView = findViewById(R.id.scrollView);
        layoutNotificationsList = findViewById(R.id.layoutNotificationsList);
        viewEmpty = findViewById(R.id.viewEmpty);
        viewAccessOff = findViewById(R.id.viewAccessOff);
        btnClearAll = findViewById(R.id.btnClearAll);

        // Scrim click closes the panel
        findViewById(R.id.scrim).setOnClickListener(v -> finish());
        findViewById(R.id.panel).setOnClickListener(v -> {});

        // Clear all button
        btnClearAll.setOnClickListener(v -> {
            Notifications.dismissAll();
            finish();
        });

        // Grant access card
        findViewById(R.id.cardGrantAccess).setOnClickListener(v -> {
            try {
                Intent settingsIntent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
                settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(settingsIntent);
            } catch (Exception ignored) {
            }
            finish();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        Notifications.registerCallback(this);
        refreshUi();
    }

    @Override
    protected void onPause() {
        Notifications.unregisterCallback(this);
        if (!isFinishing()) {
            finish();
        }
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        if (!isFinishing()) {
            finish();
        }
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    @Override
    public void onNotificationsChanged() {
        if (!isFinishing()) {
            runOnUiThread(this::refreshUi);
        }
    }

    private boolean isNotificationAccessGranted() {
        if (Notifications.isConnected()) {
            return true;
        }
        String enabled = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        if (enabled != null) {
            String pkg = getPackageName();
            return enabled.contains(pkg + "/" + Notifications.class.getName()) || enabled.contains(pkg);
        }
        return false;
    }

    private void refreshUi() {
        if (!isNotificationAccessGranted()) {
            viewAccessOff.setVisibility(View.VISIBLE);
            scrollView.setVisibility(View.GONE);
            viewEmpty.setVisibility(View.GONE);
            btnClearAll.setVisibility(View.GONE);
            View card = findViewById(R.id.cardGrantAccess);
            if (card != null) {
                card.requestFocus();
            }
            return;
        }

        viewAccessOff.setVisibility(View.GONE);
        List<NotificationItem> items = Notifications.snapshot();

        if (items.isEmpty()) {
            viewEmpty.setVisibility(View.VISIBLE);
            scrollView.setVisibility(View.GONE);
            btnClearAll.setVisibility(View.GONE);
            return;
        }

        viewEmpty.setVisibility(View.GONE);
        scrollView.setVisibility(View.VISIBLE);
        btnClearAll.setVisibility(View.VISIBLE);

        layoutNotificationsList.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        View firstView = null;

        for (int i = 0; i < items.size(); i++) {
            NotificationItem item = items.get(i);
            View row = inflater.inflate(R.layout.item_notification, layoutNotificationsList, false);

            ImageView imgIcon = row.findViewById(R.id.imgAppIcon);
            TextView txtTitle = row.findViewById(R.id.txtTitle);
            TextView txtTime = row.findViewById(R.id.txtTime);
            TextView txtBody = row.findViewById(R.id.txtBody);

            // App icon
            Drawable icon = iconCache.get(item.packageName);
            if (icon == null) {
                try {
                    icon = packageManager.getApplicationIcon(item.packageName);
                    iconCache.put(item.packageName, icon);
                } catch (Exception e) {
                    icon = getDrawable(R.drawable.ic_notifications);
                }
            }
            imgIcon.setImageDrawable(icon);

            // Title: notification title or app label
            CharSequence appLabel = labelCache.get(item.packageName);
            if (appLabel == null) {
                try {
                    ApplicationInfo ai = packageManager.getApplicationInfo(item.packageName, 0);
                    appLabel = packageManager.getApplicationLabel(ai);
                    labelCache.put(item.packageName, appLabel);
                } catch (Exception e) {
                    appLabel = item.packageName;
                }
            }
            CharSequence title = (item.title != null && item.title.length() > 0) ? item.title : appLabel;
            txtTitle.setText(title);

            // Relative time
            long now = System.currentTimeMillis();
            long diff = Math.max(0, now - item.postTime);
            if (diff < 60_000) {
                txtTime.setText(R.string.time_just_now);
            } else {
                txtTime.setText(DateUtils.getRelativeTimeSpanString(item.postTime, now,
                        DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE));
            }

            // Body text
            if (item.text != null && item.text.length() > 0) {
                txtBody.setText(item.text);
                txtBody.setVisibility(View.VISIBLE);
            } else {
                txtBody.setVisibility(View.GONE);
            }

            // D-pad Actions
            row.setOnClickListener(v -> launchNotification(item));
            row.setOnLongClickListener(v -> {
                Notifications.dismiss(item.key);
                return true;
            });

            layoutNotificationsList.addView(row);
            if (firstView == null) {
                firstView = row;
            }
        }

        if (firstView != null) {
            firstView.requestFocus();
        }
    }

    private void launchNotification(NotificationItem item) {
        if (item.contentIntent instanceof PendingIntent) {
            try {
                ((PendingIntent) item.contentIntent).send();
                finish();
                return;
            } catch (PendingIntent.CanceledException ignored) {
            }
        }

        // Fallback: launch application directly
        Intent launch = packageManager.getLeanbackLaunchIntentForPackage(item.packageName);
        if (launch == null) {
            launch = packageManager.getLaunchIntentForPackage(item.packageName);
        }
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(launch);
            } catch (Exception ignored) {
            }
        }
        finish();
    }
}
