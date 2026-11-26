package com.gathin.wallpapersync;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

/** TV-first UI for Wallpaper Sync: displays the Wi-Fi URL, QR code, pairing PIN, and status. */
public class MainActivity extends Activity {
    private TextView statusBadge;
    private TextView urlText;
    private TextView pinText;
    private TextView bridgeWarning;
    private TextView homeStatus;
    private TextView ssStatus;
    private QrView qrView;
    private Button toggleBtn;
    private Button pinBtn;
    private Button clearHomeBtn;
    private Button clearSsBtn;

    private SyncServerService service;
    private boolean bound;

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((SyncServerService.LocalBinder) binder).service();
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
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildLayout());
        startForegroundService(new Intent(this, SyncServerService.class));
    }

    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(SyncServerService.ACTION_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(stateReceiver, filter);
        }
        bindService(new Intent(this, SyncServerService.class), conn, Context.BIND_AUTO_CREATE);
        refreshUi();
    }

    @Override
    protected void onStop() {
        super.onStop();
        unregisterReceiver(stateReceiver);
        if (bound) {
            unbindService(conn);
            bound = false;
        }
    }

    private View buildLayout() {
        float d = getResources().getDisplayMetrics().density;
        int pad = Math.round(36 * d);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(Color.parseColor("#0D1420"));
        root.setPadding(pad, pad, pad, pad);
        root.setGravity(Gravity.CENTER_VERTICAL);

        // Left column: info + controls
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.3f);
        leftLp.setMarginEnd(Math.round(32 * d));
        root.addView(left, leftLp);

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextColor(Color.parseColor("#EDF3F8"));
        title.setTextSize(30);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        left.addView(title);

        TextView sub = new TextView(this);
        sub.setText(R.string.subtitle);
        sub.setTextColor(Color.parseColor("#93A6BD"));
        sub.setTextSize(15);
        sub.setPadding(0, Math.round(4 * d), 0, Math.round(18 * d));
        left.addView(sub);

        statusBadge = new TextView(this);
        statusBadge.setTextSize(14);
        statusBadge.setTypeface(Typeface.DEFAULT_BOLD);
        statusBadge.setPadding(0, 0, 0, Math.round(8 * d));
        left.addView(statusBadge);

        TextView scanHint = new TextView(this);
        scanHint.setText(R.string.scan_hint);
        scanHint.setTextColor(Color.parseColor("#B6C5D6"));
        scanHint.setTextSize(14);
        left.addView(scanHint);

        urlText = new TextView(this);
        urlText.setTextColor(Color.parseColor("#5BA4CF"));
        urlText.setTextSize(24);
        urlText.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        urlText.setPadding(0, Math.round(6 * d), 0, Math.round(6 * d));
        left.addView(urlText);

        pinText = new TextView(this);
        pinText.setTextColor(Color.parseColor("#EDF3F8"));
        pinText.setTextSize(18);
        pinText.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        pinText.setPadding(0, 0, 0, Math.round(14 * d));
        left.addView(pinText);

        bridgeWarning = new TextView(this);
        bridgeWarning.setText(R.string.launcher_missing);
        bridgeWarning.setTextColor(Color.parseColor("#F5B0B0"));
        bridgeWarning.setTextSize(14);
        bridgeWarning.setPadding(0, 0, 0, Math.round(12 * d));
        bridgeWarning.setVisibility(View.GONE);
        left.addView(bridgeWarning);

        homeStatus = new TextView(this);
        homeStatus.setTextColor(Color.parseColor("#B6C5D6"));
        homeStatus.setTextSize(14);
        left.addView(homeStatus);

        ssStatus = new TextView(this);
        ssStatus.setTextColor(Color.parseColor("#B6C5D6"));
        ssStatus.setTextSize(14);
        ssStatus.setPadding(0, Math.round(4 * d), 0, Math.round(18 * d));
        left.addView(ssStatus);

        // Action buttons row 1
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        left.addView(row1);

        toggleBtn = makeButton(d, getString(R.string.btn_stop_server), v -> {
            if (service == null) return;
            if (service.isRunning()) {
                service.stopServer();
            } else {
                startForegroundService(new Intent(this, SyncServerService.class));
                service.startServer();
            }
            refreshUi();
        });
        row1.addView(toggleBtn, buttonLp(d, true));

        pinBtn = makeButton(d, getString(R.string.btn_new_pin), v -> {
            if (service != null) {
                service.rotatePin();
                refreshUi();
            }
        });
        row1.addView(pinBtn, buttonLp(d, false));

        // Action buttons row 2
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams row2Lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        row2Lp.topMargin = Math.round(10 * d);
        left.addView(row2, row2Lp);

        clearHomeBtn = makeButton(d, getString(R.string.btn_clear_home), v -> clearTarget(LauncherBridge.TARGET_HOME));
        row2.addView(clearHomeBtn, buttonLp(d, true));

        clearSsBtn = makeButton(d, getString(R.string.btn_clear_screensaver), v -> clearTarget(LauncherBridge.TARGET_SCREENSAVER));
        row2.addView(clearSsBtn, buttonLp(d, false));

        // Right column: QR code
        int qrSide = Math.round(250 * d);
        qrView = new QrView(this);
        LinearLayout.LayoutParams qrLp = new LinearLayout.LayoutParams(qrSide, qrSide);
        qrLp.gravity = Gravity.CENTER;
        root.addView(qrView, qrLp);

        toggleBtn.requestFocus();
        return root;
    }

    private Button makeButton(float d, String text, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.parseColor("#EDF3F8"));
        b.setBackgroundResource(R.drawable.btn_bg);
        b.setFocusable(true);
        b.setFocusableInTouchMode(false);
        b.setPadding(Math.round(18 * d), Math.round(10 * d), Math.round(18 * d), Math.round(10 * d));
        b.setOnClickListener(listener);
        SpringAnimation sx = new SpringAnimation(b, DynamicAnimation.SCALE_X, 1f);
        SpringAnimation sy = new SpringAnimation(b, DynamicAnimation.SCALE_Y, 1f);
        sx.setSpring(new SpringForce(1f).setStiffness(420f).setDampingRatio(0.72f));
        sy.setSpring(new SpringForce(1f).setStiffness(420f).setDampingRatio(0.72f));
        b.setOnFocusChangeListener((v, focused) -> {
            float target = focused ? 1.06f : 1f;
            float damping = focused ? 0.68f : 0.88f;
            sx.getSpring().setDampingRatio(damping);
            sy.getSpring().setDampingRatio(damping);
            sx.animateToFinalPosition(target);
            sy.animateToFinalPosition(target);
        });
        return b;
    }

    private static LinearLayout.LayoutParams buttonLp(float d, boolean withEndMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        if (withEndMargin) lp.setMarginEnd(Math.round(12 * d));
        return lp;
    }

    private void clearTarget(String target) {
        boolean ok = LauncherBridge.clear(this, target);
        Toast.makeText(this, ok ? "Reset " + target : getString(R.string.launcher_missing), Toast.LENGTH_SHORT).show();
        refreshUi();
    }

    private void refreshUi() {
        String ip = NetworkAddress.localIpv4(this);
        boolean running = service != null && service.isRunning();
        int port = service != null ? service.getPort() : 8765;
        String pin = service != null ? service.getPin() : SyncServerService.loadOrCreatePin(this);

        if (running && ip != null) {
            String url = "http://" + ip + ":" + port;
            statusBadge.setText(R.string.server_running);
            statusBadge.setTextColor(Color.parseColor("#59C18E"));
            urlText.setText(url);
            qrView.setVisibility(View.VISIBLE);
            qrView.setText(url);
        } else if (ip == null) {
            statusBadge.setText(R.string.no_wifi);
            statusBadge.setTextColor(Color.parseColor("#F5B0B0"));
            urlText.setText("—");
            qrView.setVisibility(View.INVISIBLE);
        } else {
            statusBadge.setText(R.string.server_stopped);
            statusBadge.setTextColor(Color.parseColor("#93A6BD"));
            urlText.setText("http://" + ip + ":" + port);
            qrView.setVisibility(View.INVISIBLE);
        }

        pinText.setText(getString(R.string.pin_label, pin));
        toggleBtn.setText(running ? R.string.btn_stop_server : R.string.btn_start_server);

        LauncherBridge.Status st = LauncherBridge.status(this);
        bridgeWarning.setVisibility(st.available ? View.GONE : View.VISIBLE);
        homeStatus.setText(st.homeSet ? R.string.home_status_set : R.string.home_status_default);
        ssStatus.setText(st.screensaverSet ? R.string.ss_status_set : R.string.ss_status_default);
        clearHomeBtn.setEnabled(st.available && st.homeSet);
        clearSsBtn.setEnabled(st.available && st.screensaverSet);
    }
}
