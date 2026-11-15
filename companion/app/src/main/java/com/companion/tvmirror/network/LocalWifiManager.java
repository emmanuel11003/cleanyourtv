package com.companion.tvmirror.network;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.util.Log;

public class LocalWifiManager {
    private static final String TAG = "LocalWifiManager";

    public interface WifiStateListener {
        void onWifiAvailable(String info);
        void onWifiLost();
    }

    private final ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private WifiStateListener listener;
    private volatile boolean isBoundToWifi = false;

    public LocalWifiManager(Context context) {
        Context appContext = context.getApplicationContext();
        this.connectivityManager = (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    public void setListener(WifiStateListener listener) {
        this.listener = listener;
    }

    /**
     * Binds the application process to the local Wi-Fi network.
     * Crucially does NOT request NET_CAPABILITY_INTERNET, ensuring local LAN communication
     * succeeds even when the Wi-Fi router or hotspot has no active WAN connection.
     */
    public synchronized void startMonitoring() {
        if (connectivityManager == null || networkCallback != null) {
            return;
        }

        try {
            NetworkRequest request = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    // Intentionally omitting NET_CAPABILITY_INTERNET so offline LAN is retained
                    .build();

            final ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    super.onAvailable(network);
                    Log.i(TAG, "Local Wi-Fi network available: " + network);

                    boolean bound = connectivityManager.bindProcessToNetwork(network);
                    isBoundToWifi = bound;
                    Log.i(TAG, "Process bound to local Wi-Fi: " + bound);

                    WifiStateListener currentListener = listener;
                    if (currentListener != null) {
                        currentListener.onWifiAvailable("Local Wi-Fi Active");
                    }
                }

                @Override
                public void onLost(Network network) {
                    super.onLost(network);
                    Log.w(TAG, "Local Wi-Fi network lost: " + network);

                    connectivityManager.bindProcessToNetwork(null);
                    isBoundToWifi = false;

                    WifiStateListener currentListener = listener;
                    if (currentListener != null) {
                        currentListener.onWifiLost();
                    }
                }

                @Override
                public void onUnavailable() {
                    super.onUnavailable();
                    Log.w(TAG, "Local Wi-Fi network unavailable");
                    WifiStateListener currentListener = listener;
                    if (currentListener != null) {
                        currentListener.onWifiLost();
                    }
                }
            };

            networkCallback = callback;
            connectivityManager.requestNetwork(request, callback);
        } catch (Exception e) {
            networkCallback = null;
            Log.e(TAG, "Failed to request local Wi-Fi network", e);
        }
    }

    public synchronized void stopMonitoring() {
        if (connectivityManager != null && networkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (Exception ignored) {
            }
            networkCallback = null;
        }
        if (connectivityManager != null) {
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
