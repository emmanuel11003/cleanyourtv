package com.companion.tvmirror;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.companion.tvmirror.data.AppSettings;
import com.companion.tvmirror.input.RemoteCommand;
import com.companion.tvmirror.input.TouchpadController;
import com.companion.tvmirror.network.IpValidator;
import com.companion.tvmirror.network.LocalWifiManager;
import com.companion.tvmirror.network.TvConnection;
import com.companion.tvmirror.video.VideoDecoder;

public class MainActivity extends Activity implements SurfaceHolder.Callback,
        TvConnection.ConnectionListener, VideoDecoder.FpsListener {

    private enum Screen {
        CONNECT,
        MIRROR,
        SETTINGS,
        HELP
    }

    private Screen currentScreen = Screen.CONNECT;
    private Screen previousScreen = Screen.CONNECT;

    private AppSettings settings;
    private LocalWifiManager wifiManager;
    private VideoDecoder videoDecoder;
    private TvConnection tvConnection;
    private TouchpadController touchpadController;
    private Vibrator vibrator;

    private boolean isConnectionPending = false;

    // Root layouts
    private View layoutConnect;
    private View layoutMirror;
    private View layoutSettings;
    private View layoutHelp;

    // Connect Screen Views
    private EditText etTvIp;
    private EditText etPort;
    private EditText etToken;
    private RadioGroup rgProfile;
    private RadioButton rb540p;
    private RadioButton rb720p;
    private RadioButton rb1080p;
    private Button btnConnect;
    private TextView tvConnectStatus;
    private View viewStatusIndicator;
    private TextView tvWifiPill;

    // Mirror Screen Views
    private SurfaceView surfaceView;
    private View touchpadView;
    private View mirrorStatusDot;
    private TextView tvMirrorStatus;
    private TextView tvFpsOverlay;
    private TextView tvMirrorAlert;
    private View layoutRemoteOverlay;
    private boolean isRemoteVisible = true;

    // Settings Screen Views
    private RadioGroup rgSetProfile;
    private RadioButton rbSet540p;
    private RadioButton rbSet720p;
    private RadioButton rbSet1080p;
    private CheckBox cbFpsOverlay;
    private CheckBox cbHaptic;
    private RadioGroup rgSensitivity;
    private RadioGroup rgLongPress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Keep screen on while mirroring
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_main);

        settings = new AppSettings(this);
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);

        initViews();
        setupWifiManager();
        setupVideoAndConnection();
        loadSettingsIntoUi();
        setupListeners();

        showScreen(Screen.CONNECT);
    }

    private void initViews() {
        layoutConnect = findViewById(R.id.layout_connect);
        layoutMirror = findViewById(R.id.layout_mirror);
        layoutSettings = findViewById(R.id.layout_settings);
        layoutHelp = findViewById(R.id.layout_help);

        // Connect views
        etTvIp = findViewById(R.id.et_tv_ip);
        etPort = findViewById(R.id.et_port);
        etToken = findViewById(R.id.et_token);
        rgProfile = findViewById(R.id.rg_profile);
        rb540p = findViewById(R.id.rb_profile_540p);
        rb720p = findViewById(R.id.rb_profile_720p);
        rb1080p = findViewById(R.id.rb_profile_1080p);
        btnConnect = findViewById(R.id.btn_connect);
        tvConnectStatus = findViewById(R.id.tv_connect_status);
        viewStatusIndicator = findViewById(R.id.view_status_indicator);
        tvWifiPill = findViewById(R.id.tv_wifi_pill);

        // Mirror views
        surfaceView = findViewById(R.id.surface_view);
        surfaceView.setZOrderMediaOverlay(true);
        surfaceView.getHolder().addCallback(this);

        touchpadView = findViewById(R.id.touchpad_view);
        mirrorStatusDot = findViewById(R.id.mirror_status_dot);
        tvMirrorStatus = findViewById(R.id.tv_mirror_status);
        tvFpsOverlay = findViewById(R.id.tv_fps_overlay);
        tvMirrorAlert = findViewById(R.id.tv_mirror_alert);
        layoutRemoteOverlay = findViewById(R.id.layout_remote_overlay);

        // Settings views
        rgSetProfile = findViewById(R.id.rg_settings_profile);
        rbSet540p = findViewById(R.id.rb_set_540p);
        rbSet720p = findViewById(R.id.rb_set_720p);
        rbSet1080p = findViewById(R.id.rb_set_1080p);
        cbFpsOverlay = findViewById(R.id.cb_fps_overlay);
        cbHaptic = findViewById(R.id.cb_haptic);
        rgSensitivity = findViewById(R.id.rg_sensitivity);
        rgLongPress = findViewById(R.id.rg_long_press);
    }

    private void setupWifiManager() {
        wifiManager = new LocalWifiManager(this);
        wifiManager.setListener(new LocalWifiManager.WifiStateListener() {
            @Override
            public void onWifiAvailable(String info) {
                runOnUiThread(() -> {
                    tvWifiPill.setText("LAN OK");
                    tvWifiPill.setTextColor(getColor(R.color.status_connected));
                });
            }

            @Override
            public void onWifiLost() {
                runOnUiThread(() -> {
                    tvWifiPill.setText("NO LAN");
                    tvWifiPill.setTextColor(getColor(R.color.status_disconnected));
                });
            }
        });
        wifiManager.startMonitoring();
    }

    private void setupVideoAndConnection() {
        videoDecoder = new VideoDecoder(this);
        tvConnection = new TvConnection(this, videoDecoder);
        touchpadController = new TouchpadController(this, cmd -> tvConnection.sendCommand(cmd), settings);
        touchpadView.setOnTouchListener(touchpadController);
    }

    private void loadSettingsIntoUi() {
        etTvIp.setText(settings.getTvIp());
        etPort.setText(String.valueOf(settings.getTvPort()));
        etToken.setText(settings.getToken());

        String profile = settings.getStreamProfile();
        if (AppSettings.PROFILE_540P.equals(profile)) {
            rb540p.setChecked(true);
            rbSet540p.setChecked(true);
        } else if (AppSettings.PROFILE_1080P.equals(profile)) {
            rb1080p.setChecked(true);
            rbSet1080p.setChecked(true);
        } else {
            rb720p.setChecked(true);
            rbSet720p.setChecked(true);
        }

        cbFpsOverlay.setChecked(settings.isFpsOverlayEnabled());
        tvFpsOverlay.setVisibility(settings.isFpsOverlayEnabled() ? View.VISIBLE : View.GONE);
        cbHaptic.setChecked(settings.isHapticEnabled());

        String sens = settings.getSensitivity();
        if (AppSettings.SENS_LOW.equals(sens)) {
            findViewById(R.id.rb_sens_low).performClick();
        } else if (AppSettings.SENS_HIGH.equals(sens)) {
            findViewById(R.id.rb_sens_high).performClick();
        } else {
            findViewById(R.id.rb_sens_normal).performClick();
        }

        String lpAction = settings.getLongPressAction();
        if (AppSettings.ACTION_MENU.equals(lpAction)) {
            findViewById(R.id.rb_lp_menu).performClick();
        } else {
            findViewById(R.id.rb_lp_back).performClick();
        }
    }

    private void setupListeners() {
        // Connect button
        btnConnect.setOnClickListener(v -> initiateConnection());

        // Navigation to Help & Settings
        findViewById(R.id.btn_goto_help).setOnClickListener(v -> showScreen(Screen.HELP));
        findViewById(R.id.btn_goto_settings).setOnClickListener(v -> showScreen(Screen.SETTINGS));
        findViewById(R.id.btn_help_back).setOnClickListener(v -> showScreen(previousScreen));
        findViewById(R.id.btn_help_done).setOnClickListener(v -> showScreen(Screen.CONNECT));
        findViewById(R.id.btn_settings_back).setOnClickListener(v -> showScreen(previousScreen));

        // Save Settings
        findViewById(R.id.btn_save_settings).setOnClickListener(v -> {
            saveSettingsFromUi();
            Toast.makeText(this, "Settings Saved", Toast.LENGTH_SHORT).show();
            showScreen(previousScreen);
        });

        // Mirror Screen Top Bar controls
        findViewById(R.id.btn_mirror_back).setOnClickListener(v -> {
            tvConnection.disconnect();
            videoDecoder.stop();
            showScreen(Screen.CONNECT);
        });

        findViewById(R.id.btn_toggle_remote).setOnClickListener(v -> {
            isRemoteVisible = !isRemoteVisible;
            layoutRemoteOverlay.setVisibility(isRemoteVisible ? View.VISIBLE : View.GONE);
        });

        findViewById(R.id.btn_mirror_reconnect).setOnClickListener(v -> {
            tvConnection.disconnect();
            videoDecoder.stop();
            initiateConnection();
        });
        findViewById(R.id.btn_mirror_settings).setOnClickListener(v -> showScreen(Screen.SETTINGS));

        // Wire D-pad and Remote buttons
        bindRemoteButton(R.id.btn_dpad_up, RemoteCommand.KEYCODE_DPAD_UP);
        bindRemoteButton(R.id.btn_dpad_down, RemoteCommand.KEYCODE_DPAD_DOWN);
        bindRemoteButton(R.id.btn_dpad_left, RemoteCommand.KEYCODE_DPAD_LEFT);
        bindRemoteButton(R.id.btn_dpad_right, RemoteCommand.KEYCODE_DPAD_RIGHT);
        bindRemoteButton(R.id.btn_dpad_ok, RemoteCommand.KEYCODE_ENTER);

        bindRemoteButton(R.id.btn_nav_back, RemoteCommand.KEYCODE_BACK);
        bindRemoteButton(R.id.btn_nav_home, RemoteCommand.KEYCODE_HOME);
        bindRemoteButton(R.id.btn_nav_recents, RemoteCommand.KEYCODE_APP_SWITCH);

        bindRemoteButton(R.id.btn_vol_up, RemoteCommand.KEYCODE_VOLUME_UP);
        bindRemoteButton(R.id.btn_vol_down, RemoteCommand.KEYCODE_VOLUME_DOWN);
        bindRemoteButton(R.id.btn_vol_mute, RemoteCommand.KEYCODE_VOLUME_MUTE);
    }

    private void bindRemoteButton(int viewId, int keyCode) {
        View view = findViewById(viewId);
        if (view != null) {
            view.setOnClickListener(v -> {
                hapticClick();
                tvConnection.sendCommand(RemoteCommand.createKey(keyCode));
            });
        }
    }

    private void hapticClick() {
        if (!settings.isHapticEnabled() || vibrator == null) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(15, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(15);
            }
        } catch (Exception ignored) {
        }
    }

    private void initiateConnection() {
        String ip = etTvIp.getText().toString().trim();
        String portStr = etPort.getText().toString().trim();
        String token = etToken.getText().toString().trim();

        IpValidator.Result ipResult = IpValidator.validate(ip);
        if (ipResult == IpValidator.Result.INVALID_FORMAT) {
            tvConnectStatus.setText(R.string.error_invalid_ip);
            viewStatusIndicator.setBackgroundTintList(getColorStateList(R.color.status_disconnected));
            return;
        } else if (ipResult == IpValidator.Result.PUBLIC_IP_REJECTED) {
            tvConnectStatus.setText(R.string.error_public_ip);
            viewStatusIndicator.setBackgroundTintList(getColorStateList(R.color.status_disconnected));
            return;
        }

        int port = 55555;
        try {
            port = Integer.parseInt(portStr);
        } catch (NumberFormatException ignored) {
        }

        String profile = AppSettings.PROFILE_720P;
        if (rb540p.isChecked()) {
            profile = AppSettings.PROFILE_540P;
        } else if (rb1080p.isChecked()) {
            profile = AppSettings.PROFILE_1080P;
        }

        // Save preferences
        settings.setTvIp(ip);
        settings.setTvPort(port);
        settings.setToken(token);
        settings.setStreamProfile(profile);

        // Switch screen to MIRROR FIRST so Surface is created before incoming video
        showScreen(Screen.MIRROR);

        SurfaceHolder holder = surfaceView.getHolder();
        if (holder.getSurface() != null && holder.getSurface().isValid()) {
            videoDecoder.setSurface(holder.getSurface());
            videoDecoder.start();
            tvConnection.connect(ip, port, token, profile);
        } else {
            isConnectionPending = true;
        }
    }

    private void saveSettingsFromUi() {
        String profile = AppSettings.PROFILE_720P;
        if (rbSet540p.isChecked()) profile = AppSettings.PROFILE_540P;
        else if (rbSet1080p.isChecked()) profile = AppSettings.PROFILE_1080P;
        settings.setStreamProfile(profile);

        boolean fpsEnabled = cbFpsOverlay.isChecked();
        settings.setFpsOverlayEnabled(fpsEnabled);
        tvFpsOverlay.setVisibility(fpsEnabled ? View.VISIBLE : View.GONE);

        settings.setHapticEnabled(cbHaptic.isChecked());

        int sensId = rgSensitivity.getCheckedRadioButtonId();
        if (sensId == R.id.rb_sens_low) settings.setSensitivity(AppSettings.SENS_LOW);
        else if (sensId == R.id.rb_sens_high) settings.setSensitivity(AppSettings.SENS_HIGH);
        else settings.setSensitivity(AppSettings.SENS_NORMAL);
        touchpadController.updateSensitivity();

        int lpId = rgLongPress.getCheckedRadioButtonId();
        if (lpId == R.id.rb_lp_menu) settings.setLongPressAction(AppSettings.ACTION_MENU);
        else settings.setLongPressAction(AppSettings.ACTION_BACK);

        // Sync with connect screen radio buttons
        if (AppSettings.PROFILE_540P.equals(profile)) rb540p.setChecked(true);
        else if (AppSettings.PROFILE_1080P.equals(profile)) rb1080p.setChecked(true);
        else rb720p.setChecked(true);
    }

    private void showScreen(Screen screen) {
        previousScreen = currentScreen;
        currentScreen = screen;

        layoutConnect.setVisibility(screen == Screen.CONNECT ? View.VISIBLE : View.GONE);
        layoutMirror.setVisibility(screen == Screen.MIRROR ? View.VISIBLE : View.GONE);
        layoutSettings.setVisibility(screen == Screen.SETTINGS ? View.VISIBLE : View.GONE);
        layoutHelp.setVisibility(screen == Screen.HELP ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onBackPressed() {
        if (currentScreen == Screen.SETTINGS || currentScreen == Screen.HELP) {
            showScreen(previousScreen);
        } else if (currentScreen == Screen.MIRROR) {
            tvConnection.disconnect();
            videoDecoder.stop();
            showScreen(Screen.CONNECT);
        } else {
            super.onBackPressed();
        }
    }

    // --- TvConnection.ConnectionListener Callbacks ---

    @Override
    public void onConnecting() {
        tvConnectStatus.setText(R.string.status_connecting);
        viewStatusIndicator.setBackgroundTintList(getColorStateList(R.color.status_connecting));
        tvMirrorAlert.setVisibility(View.GONE);
    }

    @Override
    public void onConnected(int width, int height, int fps) {
        tvConnectStatus.setText(R.string.status_connected);
        viewStatusIndicator.setBackgroundTintList(getColorStateList(R.color.status_connected));
        mirrorStatusDot.setBackgroundTintList(getColorStateList(R.color.status_connected));
        tvMirrorStatus.setText(width + "x" + height + "@" + fps);
        tvMirrorAlert.setVisibility(View.GONE);

        videoDecoder.start();
    }

    @Override
    public void onDisconnected(String reason) {
        tvConnectStatus.setText("Disconnected: " + reason);
        viewStatusIndicator.setBackgroundTintList(getColorStateList(R.color.status_disconnected));
        mirrorStatusDot.setBackgroundTintList(getColorStateList(R.color.status_disconnected));
        tvMirrorAlert.setText("Disconnected: " + reason);
        tvMirrorAlert.setVisibility(View.VISIBLE);
    }

    @Override
    public void onError(String error) {
        tvConnectStatus.setText("Error: " + error);
        viewStatusIndicator.setBackgroundTintList(getColorStateList(R.color.status_disconnected));
        mirrorStatusDot.setBackgroundTintList(getColorStateList(R.color.status_disconnected));
        tvMirrorAlert.setText(error);
        tvMirrorAlert.setVisibility(View.VISIBLE);
    }

    // --- VideoDecoder.FpsListener Callback ---

    @Override
    public void onFpsCalculated(float fps, long avgLatencyMs) {
        runOnUiThread(() -> {
            String text = String.format("%.1f FPS • %dms", fps, avgLatencyMs);
            tvFpsOverlay.setText(text);
        });
    }

    // --- SurfaceHolder.Callback ---

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        videoDecoder.setSurface(holder.getSurface());
        if (isConnectionPending) {
            isConnectionPending = false;
            videoDecoder.start();
            tvConnection.connect(settings.getTvIp(), settings.getTvPort(), settings.getToken(), settings.getStreamProfile());
        } else if (tvConnection.isConnected()) {
            videoDecoder.start();
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        videoDecoder.stop();
        videoDecoder.setSurface(null);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (tvConnection != null) tvConnection.disconnect();
        if (videoDecoder != null) videoDecoder.stop();
        if (wifiManager != null) wifiManager.stopMonitoring();
    }
}
