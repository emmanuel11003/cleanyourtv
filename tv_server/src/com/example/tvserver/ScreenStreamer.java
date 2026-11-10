package com.example.tvserver;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

public class ScreenStreamer {
    private final OutputStream clientOutputStream;
    private final String profile;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private Process screenRecordProcess;
    private Thread streamerThread;

    private int width = 1280;
    private int height = 720;
    private int fps = 60;
    private int bitrate = 5000000; // 5 Mbps

    public ScreenStreamer(OutputStream clientOutputStream, String profile) {
        this.clientOutputStream = clientOutputStream;
        this.profile = (profile != null) ? profile.toLowerCase() : "720p60";
        parseProfile();
    }

    private void parseProfile() {
        if (profile.contains("540p")) {
            width = 960;
            height = 540;
            fps = 60;
            bitrate = 3000000; // 3 Mbps
        } else if (profile.contains("1080p")) {
            width = 1920;
            height = 1080;
            fps = 60;
            bitrate = 9000000; // 9 Mbps
        } else {
            // Default 720p60 for Haier 55P7GT-P (MediaTek MT9676)
            width = 1280;
            height = 720;
            fps = 60;
            bitrate = 5000000; // 5 Mbps
        }
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public int getFps() {
        return fps;
    }

    public synchronized void start() {
        if (isRunning.get()) return;
        isRunning.set(true);

        streamerThread = new Thread(() -> {
            byte[] buffer = new byte[32 * 1024];

            while (isRunning.get()) {
                try {
                    String sizeArg = width + "x" + height;
                    String bitRateArg = String.valueOf(bitrate);

                    System.out.println("[ScreenStreamer] Launching hardware encoder: " + sizeArg + " @ " + (bitrate / 1000000) + " Mbps, target " + fps + " FPS...");
                    ProcessBuilder pb = new ProcessBuilder(
                            "/system/bin/screenrecord",
                            "--output-format=h264",
                            "--size", sizeArg,
                            "--bit-rate", bitRateArg,
                            "--time-limit", "0",
                            "-"
                    );

                    screenRecordProcess = pb.start();
                    InputStream screenInput = screenRecordProcess.getInputStream();

                    int bytesRead;
                    while (isRunning.get() && (bytesRead = screenInput.read(buffer)) != -1) {
                        clientOutputStream.write(buffer, 0, bytesRead);
                        clientOutputStream.flush();
                    }

                    if (screenRecordProcess != null) {
                        screenRecordProcess.waitFor();
                    }
                } catch (Exception e) {
                    if (isRunning.get()) {
                        System.out.println("[ScreenStreamer] Streamer error: " + e.getMessage());
                    }
                    break;
                }
            }

            stop();
        }, "ScreenStreamerThread");

        streamerThread.start();
    }

    public synchronized void stop() {
        isRunning.set(false);

        if (screenRecordProcess != null) {
            try {
                screenRecordProcess.destroyForcibly();
            } catch (Exception ignored) {
            }
            screenRecordProcess = null;
        }

        if (streamerThread != null) {
            streamerThread.interrupt();
            streamerThread = null;
        }
        System.out.println("[ScreenStreamer] Stopped. Hardware encoder released (0% idle CPU).");
    }
}
