package com.companion.tvmirror.network;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.companion.tvmirror.input.RemoteCommand;
import com.companion.tvmirror.video.NalParser;
import com.companion.tvmirror.video.VideoDecoder;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class TvConnection {
    private static final String TAG = "TvConnection";
    private static final int CONNECT_TIMEOUT_MS = 6000;
    private static final int MAX_HANDSHAKE_BYTES = 16 * 1024;

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

    private final Object socketLock = new Object();
    private final Object writeLock = new Object();
    private Socket socket;
    private OutputStream outputStream;
    private final AtomicBoolean isConnected = new AtomicBoolean(false);
    private final AtomicBoolean isConnecting = new AtomicBoolean(false);
    private final AtomicBoolean disconnectRequested = new AtomicBoolean(false);
    private final AtomicLong connectionGen = new AtomicLong(0);

    private final ExecutorService streamExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService commandExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService closeExecutor = Executors.newSingleThreadExecutor();

    public TvConnection(ConnectionListener listener, VideoDecoder videoDecoder) {
        this.listener = listener;
        this.videoDecoder = videoDecoder;
        this.nalParser = new NalParser((data, offset, length, isConfig, isKeyframe) -> {
            videoDecoder.feedNalUnit(data, offset, length, isConfig, isKeyframe);
        });
    }

    public void connect(String ip, int port, String token, String profile) {
        if (isConnecting.get() || isConnected.get() || streamExecutor.isShutdown()) {
            return;
        }

        final long myGen = connectionGen.incrementAndGet();
        disconnectRequested.set(false);
        isConnecting.set(true);
        postConnecting(myGen);

        streamExecutor.execute(() -> {
            Socket localSocket = null;
            OutputStream localOutput = null;
            InputStream localInput = null;
            try {
                Log.i(TAG, "Connecting to TV at " + ip + ":" + port + "...");
                localSocket = new Socket();
                localSocket.setTcpNoDelay(true); // Disable Nagle's algorithm for minimum latency
                localSocket.setKeepAlive(true);
                localSocket.connect(new InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS);

                if (disconnectRequested.get() || connectionGen.get() != myGen) {
                    closeQuietly(localSocket, null);
                    return;
                }

                localOutput = localSocket.getOutputStream();
                localInput = localSocket.getInputStream();

                synchronized (socketLock) {
                    socket = localSocket;
                    outputStream = localOutput;
                }

                // 1. Send handshake
                int targetFps = 60;
                String handshake = RemoteCommand.createHello(token, profile, targetFps) + "\n";
                synchronized (writeLock) {
                    localOutput.write(handshake.getBytes(StandardCharsets.UTF_8));
                    localOutput.flush();
                }

                // 2. Read handshake response line byte-by-byte without buffering raw video bytes
                String responseLine = readHandshakeLine(localInput);
                if (responseLine == null || responseLine.trim().isEmpty()) {
                    throw new Exception("TV server closed connection during handshake");
                }

                int width = 1280;
                int height = 720;
                int fps = targetFps;
                String trimmed = responseLine.trim();
                if (!"START_OK".equals(trimmed)) {
                    JSONObject resp = new JSONObject(trimmed);
                    boolean ok = resp.optBoolean("ok", false);
                    boolean tokenOk = resp.optBoolean("token_ok", true);

                    if (!tokenOk) {
                        throw new Exception("Authentication rejected: Invalid security token");
                    }

                    if (!ok) {
                        String err = resp.optString("error", "Server handshake failed");
                        throw new Exception(err);
                    }

                    width = resp.optInt("width", 1280);
                    height = resp.optInt("height", 720);
                    fps = resp.optInt("fps", 60);
                }

                Log.i(TAG, "Handshake successful! Format: " + width + "x" + height + "@" + fps + "fps");
                isConnected.set(true);
                isConnecting.set(false);

                // Configure video decoder
                videoDecoder.configure(width, height, fps);
                postConnected(myGen, width, height, fps);

                // 3. Blocking stream reader loop
                readVideoStream(localSocket, localInput, myGen);

                if (disconnectRequested.get()) {
                    postDisconnected(myGen, "Disconnected");
                } else {
                    postDisconnected(myGen, "Connection closed by TV server");
                }
            } catch (Exception e) {
                if (disconnectRequested.get() && connectionGen.get() == myGen) {
                    postDisconnected(myGen, "Disconnected");
                } else if (connectionGen.get() == myGen && !disconnectRequested.get()) {
                    Log.e(TAG, "Connection error: " + e.getMessage(), e);
                    postError(myGen, e.getMessage() != null ? e.getMessage() : "Failed to connect to TV");
                }
            } finally {
                cleanupConnection(myGen, localSocket, localOutput);
            }
        });
    }

    private String readHandshakeLine(InputStream inputStream) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(128);
        int b;
        while ((b = inputStream.read()) != -1) {
            if (b == '\n') {
                break;
            }
            if (b != '\r') {
                baos.write(b);
                if (baos.size() > MAX_HANDSHAKE_BYTES) {
                    throw new Exception("Handshake response exceeded maximum size");
                }
            }
        }
        if (b == -1 && baos.size() == 0) {
            return null;
        }
        return baos.toString(StandardCharsets.UTF_8.name());
    }

    private void readVideoStream(Socket activeSocket, InputStream inputStream, long myGen) throws Exception {
        byte[] chunk = new byte[32 * 1024];
        nalParser.reset();

        while (isConnected.get() && connectionGen.get() == myGen && !activeSocket.isClosed()) {
            int bytesRead = inputStream.read(chunk);
            if (bytesRead == -1) {
                Log.i(TAG, "End of stream reached from TV server");
                break;
            }
            if (bytesRead > 0) {
                nalParser.feedData(chunk, 0, bytesRead);
            }
        }
    }

    public void sendCommand(String jsonCommand) {
        if (!isConnected.get() || commandExecutor.isShutdown()) {
            return;
        }

        commandExecutor.execute(() -> {
            final OutputStream os;
            synchronized (socketLock) {
                os = outputStream;
            }
            if (os == null || !isConnected.get()) {
                return;
            }
            try {
                synchronized (writeLock) {
                    String payload = jsonCommand + "\n";
                    os.write(payload.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to send command to TV: " + e.getMessage());
            }
        });
    }

    public void disconnect() {
        disconnectRequested.set(true);
        isConnected.set(false);
        isConnecting.set(false);

        final Socket s;
        final OutputStream os;
        synchronized (socketLock) {
            s = socket;
            os = outputStream;
            socket = null;
            outputStream = null;
        }

        if (s != null || os != null) {
            if (!closeExecutor.isShutdown()) {
                closeExecutor.execute(() -> closeQuietly(s, os));
            } else {
                closeQuietly(s, os);
            }
        }
    }

    public void shutdown() {
        disconnect();
        commandExecutor.shutdownNow();
        streamExecutor.shutdownNow();
        closeExecutor.shutdown();
    }

    private void cleanupConnection(long myGen, Socket localSocket, OutputStream localOutput) {
        if (connectionGen.get() == myGen) {
            isConnected.set(false);
            isConnecting.set(false);
        }
        synchronized (socketLock) {
            if (socket == localSocket) {
                socket = null;
            }
            if (outputStream == localOutput) {
                outputStream = null;
            }
        }
        closeQuietly(localSocket, localOutput);
    }

    private static void closeQuietly(Socket s, OutputStream os) {
        try {
            if (os != null) {
                os.close();
            }
        } catch (Exception ignored) {
        }
        try {
            if (s != null && !s.isClosed()) {
                s.close();
            }
        } catch (Exception ignored) {
        }
    }

    private void postConnecting(long gen) {
        mainHandler.post(() -> {
            if (connectionGen.get() == gen && listener != null) {
                listener.onConnecting();
            }
        });
    }

    private void postConnected(long gen, int w, int h, int fps) {
        mainHandler.post(() -> {
            if (connectionGen.get() == gen && listener != null) {
                listener.onConnected(w, h, fps);
            }
        });
    }

    private void postDisconnected(long gen, String reason) {
        mainHandler.post(() -> {
            if (connectionGen.get() == gen && listener != null) {
                listener.onDisconnected(reason);
            }
        });
    }

    private void postError(long gen, String error) {
        mainHandler.post(() -> {
            if (connectionGen.get() == gen && listener != null) {
                listener.onError(error);
            }
        });
    }

    public boolean isConnected() {
        return isConnected.get();
    }
}
