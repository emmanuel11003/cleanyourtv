package com.gathin.tvhome;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Built-in TV-first Wi-Fi Wallpaper & Backup Sync screen (consolidated from addons/wallpaper-sync).
 * Styled to match CleanYourTV's existing Material dark settings surface (#111318 / settings_choice_bg).
 */
public final class WallpaperSyncActivity extends Activity {
    private TextView statusBadge;
    private TextView urlText;
    private TextView pinText;
    private TextView homeStatus;
    private TextView ssStatus;
    private QrView qrView;
    private TextView toggleBtn;
    private boolean animateTransitions;

    private WallpaperSyncService service;
    private boolean bound;

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((WallpaperSyncService.LocalBinder) binder).service();
            bound = true;
            refreshUi();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bound = false;
            service = null;
        }
    };

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshUi();
        }
    };

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Config cfg = Config.load(this);
        animateTransitions = cfg.animations && !cfg.reducedMotion;
        Motion.applyFadeTransition(this, animateTransitions);
        View root = buildLayout();
        setContentView(root);
        try {
            startForegroundService(new Intent(this, WallpaperSyncService.class));
        } catch (Throwable ignored) {
        }
    }

    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(WallpaperSyncService.ACTION_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(stateReceiver, filter);
        }
        bindService(new Intent(this, WallpaperSyncService.class), conn, Context.BIND_AUTO_CREATE);
        refreshUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    @Override
    protected void onStop() {
        super.onStop();
        try {
            unregisterReceiver(stateReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        if (bound) {
            unbindService(conn);
            bound = false;
        }
    }

    private View buildLayout() {
        float d = getResources().getDisplayMetrics().density;
        int padH = Math.round(64 * d);
        int padV = Math.round(36 * d);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(0xFF111318);
        root.setPadding(padH, padV, padH, padV);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setClipChildren(false);
        root.setClipToPadding(false);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setClipChildren(false);
        left.setClipToPadding(false);
        LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.35f);
        leftLp.setMarginEnd(Math.round(36 * d));
        root.addView(left, leftLp);

        TextView title = new TextView(this);
        title.setText(R.string.wallpaper_sync_title);
        title.setTextColor(0xFFE3E2E6);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f);
        left.addView(title);

        TextView sub = new TextView(this);
        sub.setText(R.string.wallpaper_sync_subtitle);
        sub.setTextColor(0xFFC4C6D0);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
        sub.setPadding(0, Math.round(4 * d), 0, Math.round(16 * d));
        left.addView(sub);

        statusBadge = new TextView(this);
        statusBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        statusBadge.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        statusBadge.setPadding(0, 0, 0, Math.round(8 * d));
        left.addView(statusBadge);

        urlText = new TextView(this);
        urlText.setTextColor(0xFFA8C7FA);
        urlText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f);
        urlText.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
        urlText.setPadding(0, Math.round(4 * d), 0, Math.round(6 * d));
        left.addView(urlText);

        pinText = new TextView(this);
        pinText.setTextColor(0xFFE3E2E6);
        pinText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        pinText.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
        pinText.setPadding(0, 0, 0, Math.round(14 * d));
        left.addView(pinText);

        homeStatus = new TextView(this);
        homeStatus.setTextColor(0xFFC4C6D0);
        homeStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        left.addView(homeStatus);

        ssStatus = new TextView(this);
        ssStatus.setTextColor(0xFFC4C6D0);
        ssStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        ssStatus.setPadding(0, Math.round(4 * d), 0, Math.round(18 * d));
        left.addView(ssStatus);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setClipChildren(false);
        row1.setClipToPadding(false);
        left.addView(row1);

        toggleBtn = makeActionBtn(d, getString(R.string.sync_stop), v -> {
            if (service == null) return;
            if (service.isRunning()) {
                service.stopServer();
            } else {
                try {
                    startForegroundService(new Intent(this, WallpaperSyncService.class));
                } catch (Throwable ignored) {
                }
                service.startServer();
            }
            refreshUi();
        });
        row1.addView(toggleBtn, btnLp(d, true));

        TextView pinBtn = makeActionBtn(d, getString(R.string.sync_new_pin), v -> {
            if (service != null) {
                service.rotatePin();
                refreshUi();
            }
        });
        row1.addView(pinBtn, btnLp(d, false));

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setClipChildren(false);
        row2.setClipToPadding(false);
        LinearLayout.LayoutParams r2Lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        r2Lp.topMargin = Math.round(10 * d);
        left.addView(row2, r2Lp);

        row2.addView(makeActionBtn(d, getString(R.string.sync_clear_home), v -> {
            WallpaperSyncService.directClear(this, "home");
            Toast.makeText(this, R.string.sync_cleared, Toast.LENGTH_SHORT).show();
            refreshUi();
        }), btnLp(d, true));

        row2.addView(makeActionBtn(d, getString(R.string.sync_clear_ss), v -> {
            WallpaperSyncService.directClear(this, "screensaver");
            Toast.makeText(this, R.string.sync_cleared, Toast.LENGTH_SHORT).show();
            refreshUi();
        }), btnLp(d, false));

        int qrSide = Math.round(240 * d);
        qrView = new QrView(this);
        LinearLayout.LayoutParams qrLp = new LinearLayout.LayoutParams(qrSide, qrSide);
        qrLp.gravity = Gravity.CENTER;
        root.addView(qrView, qrLp);

        toggleBtn.requestFocus();
        return root;
    }

    private TextView makeActionBtn(float d, String text, View.OnClickListener listener) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        b.setTextColor(getColorStateList(R.color.quick_tile_text_color));
        b.setBackgroundResource(R.drawable.quick_settings_tile_bg);
        b.setGravity(Gravity.CENTER);
        b.setFocusable(true);
        b.setClickable(true);
        b.setPadding(Math.round(18 * d), Math.round(11 * d), Math.round(18 * d), Math.round(11 * d));
        b.setOnClickListener(listener);
        Motion.attachSmoothFocus(b, animateTransitions, 1.045f);
        return b;
    }

    private static LinearLayout.LayoutParams btnLp(float d, boolean endMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (endMargin) lp.setMarginEnd(Math.round(12 * d));
        return lp;
    }

    private void refreshUi() {
        boolean running = service != null && service.isRunning();
        int port = service != null ? service.getPort() : 8765;
        String pin = service != null ? service.getPin() : WallpaperSyncService.loadOrCreatePin(this);
        String ip = NetworkAddress.localIpv4(this);

        if (running && ip != null) {
            String url = "http://" + ip + ":" + port;
            statusBadge.setText(R.string.sync_running);
            statusBadge.setTextColor(0xFF81C995);
            urlText.setText(url);
            qrView.setText(url);
            qrView.setVisibility(View.VISIBLE);
        } else if (running) {
            statusBadge.setText(R.string.sync_no_network);
            statusBadge.setTextColor(0xFFF28B82);
            urlText.setText("http://<tv-ip>:" + port);
            qrView.setVisibility(View.INVISIBLE);
        } else {
            String err = service != null ? service.getLastError() : null;
            statusBadge.setText(err != null ? err : getString(R.string.sync_stopped));
            statusBadge.setTextColor(0xFFF28B82);
            urlText.setText("—");
            qrView.setVisibility(View.INVISIBLE);
        }

        pinText.setText(getString(R.string.sync_pin_fmt, pin));
        toggleBtn.setText(running ? R.string.sync_stop : R.string.sync_start);

        boolean[] st = WallpaperSyncService.directStatus(this);
        homeStatus.setText(getString(R.string.sync_home_status,
                getString(st[0] ? R.string.sync_custom : R.string.wallpaper_theme_default)));
        ssStatus.setText(getString(R.string.sync_ss_status,
                getString(st[1] ? R.string.sync_custom : R.string.sync_follows_home)));
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        super.onBackPressed();
        Motion.applyFadeTransition(this, animateTransitions);
    }
}
