package com.companion.tvmirror.network;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.companion.tvmirror.input.RemoteCommand;
import com.companion.tvmirror.video.NalParser;
import com.companion.tvmirror.video.VideoDecoder;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class TvConnection {
    private static final String TAG = "TvConnection";
    private static final int CONNECT_TIMEOUT_MS = 6000;

    public interface ConnectionListener {
        void onConnecting();
        void onConnected(int width, int height, int fps);
        void onDisconnected(String reason);
        void onError(String error);
    }

    private final ConnectionListener listener;
    private final VideoDecoder videoDecoder;
    private final NalParser nalParser;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private Socket socket;
    private OutputStream outputStream;
    private final AtomicBoolean isConnected = new AtomicBoolean(false);
    private final AtomicBoolean isConnecting = new AtomicBoolean(false);
    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();

    public TvConnection(ConnectionListener listener, VideoDecoder videoDecoder) {
        this.listener = listener;
        this.videoDecoder = videoDecoder;
        this.nalParser = new NalParser((data, offset, length, isConfig, isKeyframe) -> {
            videoDecoder.feedNalUnit(data, offset, length, isConfig, isKeyframe);
        });
    }

    public void connect(String ip, int port, String token, String profile) {
        if (isConnecting.get() || isConnected.get()) {
            return;
        }

        isConnecting.set(true);
        postConnecting();

        networkExecutor.execute(() -> {
            try {
                Log.i(TAG, "Connecting to TV at " + ip + ":" + port + "...");
                socket = new Socket();
                socket.setTcpNoDelay(true); // Disable Nagle's algorithm for minimum latency
                socket.setKeepAlive(true);
                socket.connect(new InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS);

                outputStream = socket.getOutputStream();
                InputStream inputStream = socket.getInputStream();

                // 1. Send handshake
                int targetFps = 60;
                String handshake = RemoteCommand.createHello(token, profile, targetFps) + "\n";
                outputStream.write(handshake.getBytes(StandardCharsets.UTF_8));
                outputStream.flush();

                // 2. Read handshake response line
                BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
                String responseLine = reader.readLine();

                if (responseLine == null || responseLine.trim().isEmpty()) {
                    throw new Exception("TV server closed connection during handshake");
                }

                JSONObject resp = new JSONObject(responseLine);
                boolean ok = resp.optBoolean("ok", false);
                boolean tokenOk = resp.optBoolean("token_ok", true);

                if (!tokenOk) {
                    throw new Exception("Authentication rejected: Invalid security token");
                }

                if (!ok) {
                    String err = resp.optString("error", "Server handshake failed");
                    throw new Exception(err);
                }

                int width = resp.optInt("width", 1280);
                int height = resp.optInt("height", 720);
                int fps = resp.optInt("fps", 60);

                Log.i(TAG, "Handshake successful! Format: " + width + "x" + height + "@" + fps + "fps");
                isConnected.set(true);
                isConnecting.set(false);

                // Configure video decoder
                videoDecoder.configure(width, height, fps);
                postConnected(width, height, fps);

                // 3. Stream reader loop
                byte[] chunk = new byte[32 * 1024];
                nalParser.reset();

                while (isConnected.get() && !socket.isClosed()) {
                    int bytesRead = inputStream.read(chunk);
                    if (bytesRead == -1) {
                        Log.i(TAG, "End of stream reached from TV server");
                        break;
                    }
                    if (bytesRead > 0) {
                        nalParser.feedData(chunk, 0, bytesRead);
                    }
                }

                postDisconnected("Connection closed by TV server");
            } catch (Exception e) {
                Log.e(TAG, "Connection error: " + e.getMessage(), e);
                postError(e.getMessage() != null ? e.getMessage() : "Failed to connect to TV");
                postDisconnected("Disconnected");
            } finally {
                cleanup();
            }
        });
    }

    public void sendCommand(String jsonCommand) {
        if (!isConnected.get() || outputStream == null) {
            return;
        }

        // Run send on background thread to keep UI completely responsive
        networkExecutor.execute(() -> {
            try {
                synchronized (this) {
                    if (outputStream != null) {
                        String payload = jsonCommand + "\n";
                        outputStream.write(payload.getBytes(StandardCharsets.UTF_8));
                        outputStream.flush();
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to send command to TV: " + e.getMessage());
            }
        });
    }

    public void disconnect() {
        isConnected.set(false);
        isConnecting.set(false);
        networkExecutor.execute(this::cleanup);
    }

    private synchronized void cleanup() {
        isConnected.set(false);
        isConnecting.set(false);

        try {
            if (outputStream != null) {
                outputStream.close();
            }
        } catch (Exception ignored) {
        }
        outputStream = null;

        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (Exception ignored) {
        }
        socket = null;
    }

    private void postConnecting() {
        mainHandler.post(() -> {
            if (listener != null) listener.onConnecting();
        });
    }

    private void postConnected(int w, int h, int fps) {
        mainHandler.post(() -> {
            if (listener != null) listener.onConnected(w, h, fps);
        });
    }

    private void postDisconnected(String reason) {
        mainHandler.post(() -> {
            if (listener != null) listener.onDisconnected(reason);
        });
    }

    private void postError(String error) {
        mainHandler.post(() -> {
            if (listener != null) listener.onError(error);
        });
    }

    public boolean isConnected() {
        return isConnected.get();
    }
}
