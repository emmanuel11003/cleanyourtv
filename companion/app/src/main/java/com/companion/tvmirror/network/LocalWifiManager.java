package com.companion.tvmirror.network;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.util.Log;

public class LocalWifiManager {
    private static final String TAG = "LocalWifiManager";

    public interface WifiStateListener {
        void onWifiAvailable(String info);
        void onWifiLost();
    }

    private final Context context;
    private final ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private WifiStateListener listener;
    private boolean isBoundToWifi = false;

    public LocalWifiManager(Context context) {
        this.context = context.getApplicationContext();
        this.connectivityManager = (ConnectivityManager) this.context.getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    public void setListener(WifiStateListener listener) {
        this.listener = listener;
    }

    /**
     * Binds the application process to the local Wi-Fi network.
     * Crucially does NOT request NET_CAPABILITY_INTERNET, ensuring local LAN communication
     * succeeds even when the Wi-Fi router or hotspot has no active WAN connection.
     */
    public void startMonitoring() {
        if (connectivityManager == null) {
            return;
        }

        try {
            NetworkRequest request = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    // Intentionally omitting NET_CAPABILITY_INTERNET so offline LAN is retained
                    .build();

            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    super.onAvailable(network);
                    Log.i(TAG, "Local Wi-Fi network available: " + network);

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        boolean bound = connectivityManager.bindProcessToNetwork(network);
                        isBoundToWifi = bound;
                        Log.i(TAG, "Process bound to local Wi-Fi: " + bound);
                    }

                    if (listener != null) {
                        listener.onWifiAvailable("Local Wi-Fi Active");
                    }
                }

                @Override
                public void onLost(Network network) {
                    super.onLost(network);
                    Log.w(TAG, "Local Wi-Fi network lost: " + network);

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        connectivityManager.bindProcessToNetwork(null);
                        isBoundToWifi = false;
                    }

                    if (listener != null) {
                        listener.onWifiLost();
                    }
                }

                @Override
                public void onUnavailable() {
                    super.onUnavailable();
                    Log.w(TAG, "Local Wi-Fi network unavailable");
                    if (listener != null) {
                        listener.onWifiLost();
                    }
                }
            };

            connectivityManager.requestNetwork(request, networkCallback);
        } catch (Exception e) {
            Log.e(TAG, "Failed to request local Wi-Fi network", e);
        }
    }

    public void stopMonitoring() {
        if (connectivityManager != null && networkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (Exception ignored) {
            }
            networkCallback = null;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && connectivityManager != null) {
            try {
                connectivityManager.bindProcessToNetwork(null);
            } catch (Exception ignored) {
            }
            isBoundToWifi = false;
        }
    }

    public boolean isBoundToWifi() {
        return isBoundToWifi;
    }
}
