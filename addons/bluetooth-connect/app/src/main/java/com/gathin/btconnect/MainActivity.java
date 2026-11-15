package com.gathin.btconnect;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.HashSet;
import java.util.Set;

/**
 * The tile and module card open this: the paired speakers and headphones, each with its state, and OK on one
 * connects it or, when it is connected, disconnects it. The TV's own Bluetooth settings only offer Connect and Unpair.
 */
public class MainActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout list;
    private TextView status;
    private BluetoothAdapter adapter;
    private BluetoothA2dp a2dp;
    private boolean permissionRequested;
    private boolean permissionDenied;
    private boolean proxyConnecting;
    private boolean receiverRegistered;
    /** Addresses asked to change a moment ago, shown as "Working…" until the state change arrives. */
    private final Set<String> pending = new HashSet<>();
    private final BroadcastReceiver changed = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (device != null) pending.remove(device.getAddress());
            refresh();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        float dp = getResources().getDisplayMetrics().density;
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(0xFF0D1117);
        page.setPadding(Math.round(48 * dp), Math.round(32 * dp), Math.round(48 * dp), Math.round(24 * dp));
        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(28);
        title.setTextColor(0xFFFFFFFF);
        status = new TextView(this);
        status.setTextSize(15);
        status.setTextColor(0xFF8B949E);
        status.setPadding(0, 0, 0, Math.round(16 * dp));
        page.addView(title);
        page.addView(status);
        ScrollView scroll = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        page.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(page);
        if (savedInstanceState != null) {
            permissionRequested = savedInstanceState.getBoolean("perm_req", false);
            permissionDenied = savedInstanceState.getBoolean("perm_denied", false);
        }
        BluetoothManager bm = getSystemService(BluetoothManager.class);
        adapter = bm == null ? null : bm.getAdapter();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean("perm_req", permissionRequested);
        outState.putBoolean("perm_denied", permissionDenied);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                permissionRequested = false;
                permissionDenied = false;
            } else {
                if (!permissionRequested && !permissionDenied) {
                    permissionRequested = true;
                    requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 1);
                } else if (permissionDenied) {
                    status.setText("Needs the Nearby devices permission: run install.sh once, or allow it in the app's settings.");
                }
                return;
            }
        }
        start();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code != 1) return;
        permissionRequested = false;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            permissionDenied = false;
            start();
        } else {
            permissionDenied = true;
            status.setText("Needs the Nearby devices permission: run install.sh once, or allow it in the app's settings.");
        }
    }

    private void start() {
        if (adapter == null || !adapter.isEnabled()) {
            status.setText("Bluetooth is off. Turn it on in the TV's settings.");
            return;
        }
        if (!receiverRegistered) {
            registerReceiver(changed, new IntentFilter(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED));
            receiverRegistered = true;
        }
        if (a2dp != null) {
            refresh();
            return;
        }
        if (proxyConnecting) {
            return;
        }
        proxyConnecting = true;
        boolean queued = adapter.getProfileProxy(getApplicationContext(), new BluetoothProfile.ServiceListener() {
            @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
                proxyConnecting = false;
                if (isFinishing() || isDestroyed()) {
                    if (adapter != null) adapter.closeProfileProxy(BluetoothProfile.A2DP, proxy);
                    return;
                }
                if (a2dp != null && a2dp != proxy && adapter != null) {
                    adapter.closeProfileProxy(BluetoothProfile.A2DP, a2dp);
                }
                a2dp = (BluetoothA2dp) proxy;
                refresh();
            }
            @Override public void onServiceDisconnected(int profile) {
                proxyConnecting = false;
                a2dp = null;
            }
        }, BluetoothProfile.A2DP);
        if (!queued) {
            proxyConnecting = false;
        }
    }

    @Override
    protected void onPause() {
        if (receiverRegistered) {
            try {
                unregisterReceiver(changed);
            } catch (IllegalArgumentException notRegistered) {
            }
            receiverRegistered = false;
        }
        main.removeCallbacksAndMessages(null);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (a2dp != null && adapter != null) {
            adapter.closeProfileProxy(BluetoothProfile.A2DP, a2dp);
            a2dp = null;
        }
        super.onDestroy();
    }

    /** Only runs after onResume has checked Nearby devices on Android 12+. */
    @SuppressLint("MissingPermission")
    private void refresh() {
        if (a2dp == null || adapter == null || isFinishing() || isDestroyed()) return;
        int focus = 0;
        for (int i = 0; i < list.getChildCount(); i++) if (list.getChildAt(i).isFocused()) focus = i;
        list.removeAllViews();
        Set<String> on = new HashSet<>();
        java.util.List<BluetoothDevice> connectedDevices = a2dp.getConnectedDevices();
        if (connectedDevices != null) {
            for (BluetoothDevice d : connectedDevices) on.add(d.getAddress());
        }
        Set<BluetoothDevice> bonded = adapter.getBondedDevices();
        if (bonded != null) {
            for (BluetoothDevice device : bonded) {
                if (!Link.isAudio(device.getBluetoothClass())) continue;
                String address = device.getAddress();
                boolean connected = on.contains(address);
                Row row = new Row(this, device.getName() == null ? address : device.getName(),
                        pending.contains(address) ? "Working…" : connected ? "Connected" : "Not connected",
                        () -> toggle(device, connected));
                row.setValue(connected ? "Disconnect" : "Connect");
                list.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
            }
        }
        status.setText(list.getChildCount() == 0
                ? "No paired speakers. Pair one in the TV's Bluetooth settings first."
                : "OK connects or disconnects a speaker.");
        if (list.getChildCount() > 0) list.getChildAt(Math.min(focus, list.getChildCount() - 1)).requestFocus();
    }

    private void toggle(BluetoothDevice device, boolean connected) {
        if (a2dp == null || pending.contains(device.getAddress())) return;
        if (!Link.set(a2dp, device, !connected)) {
            status.setText("Android refused. Use the TV's Bluetooth settings: "
                    + Settings.ACTION_BLUETOOTH_SETTINGS.substring(Settings.ACTION_BLUETOOTH_SETTINGS.lastIndexOf('.') + 1));
            return;
        }
        pending.add(device.getAddress());
        refresh();
        // A state change normally clears it; if none arrives (a speaker that is off), stop saying Working.
        main.postDelayed(() -> {
            pending.remove(device.getAddress());
            refresh();
        }, 12000);
    }
}
