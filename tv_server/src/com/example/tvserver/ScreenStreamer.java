package com.example.tvserver;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

public class ScreenStreamer {
    private final OutputStream clientOutputStream;
    private final String profile;
    private final Runnable onStreamEnded;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicBoolean endedNotified = new AtomicBoolean(false);
    private Process screenRecordProcess;
    private Thread streamerThread;
    private Thread stderrThread;

    private int width = 1280;
    private int height = 720;
    private int fps = 60;
    private int bitrate = 5000000; // 5 Mbps

    public ScreenStreamer(OutputStream clientOutputStream, String profile) {
        this(clientOutputStream, profile, null);
    }

    public ScreenStreamer(OutputStream clientOutputStream, String profile, Runnable onStreamEnded) {
        this.clientOutputStream = clientOutputStream;
        this.profile = (profile != null) ? profile.toLowerCase() : "720p60";
        this.onStreamEnded = onStreamEnded;
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
        endedNotified.set(false);

        streamerThread = new Thread(() -> {
            byte[] buffer = new byte[32 * 1024];

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

                Process proc = pb.start();
                synchronized (this) {
                    if (!isRunning.get()) {
                        proc.destroyForcibly();
                        return;
                    }
                    screenRecordProcess = proc;
                }

                // Drain stderr so screenrecord never blocks on a full error buffer
                final InputStream errStream = proc.getErrorStream();
                stderrThread = new Thread(() -> {
                    try (BufferedReader errReader = new BufferedReader(new InputStreamReader(errStream, StandardCharsets.UTF_8))) {
                        String errLine;
                        while ((errLine = errReader.readLine()) != null) {
                            System.err.println("[screenrecord] " + errLine);
                        }
                    } catch (Exception ignored) {
                    }
                }, "ScreenStreamerStderr");
                stderrThread.setDaemon(true);
                stderrThread.start();

                InputStream screenInput = proc.getInputStream();
                int bytesRead;
                while (isRunning.get() && (bytesRead = screenInput.read(buffer)) != -1) {
                    clientOutputStream.write(buffer, 0, bytesRead);
                    clientOutputStream.flush();
                }

                int exitCode = proc.waitFor();
                System.out.println("[ScreenStreamer] screenrecord subprocess exited with code " + exitCode);
            } catch (Exception e) {
                if (isRunning.get()) {
                    System.out.println("[ScreenStreamer] Streamer error: " + e.getMessage());
                }
            } finally {
                stop();
                notifyStreamEnded();
            }
        }, "ScreenStreamerThread");

        streamerThread.start();
    }

    private void notifyStreamEnded() {
        if (onStreamEnded != null && endedNotified.compareAndSet(false, true)) {
            try {
                onStreamEnded.run();
            } catch (Exception ignored) {
            }
        }
    }

    public synchronized void stop() {
        boolean wasRunning = isRunning.getAndSet(false);

        if (screenRecordProcess != null) {
            try {
                screenRecordProcess.destroyForcibly();
            } catch (Exception ignored) {
            }
            screenRecordProcess = null;
        }

        if (stderrThread != null && stderrThread != Thread.currentThread()) {
            stderrThread.interrupt();
            stderrThread = null;
        }

        if (streamerThread != null && streamerThread != Thread.currentThread()) {
            streamerThread.interrupt();
        }
        streamerThread = null;

        if (wasRunning) {
            System.out.println("[ScreenStreamer] Stopped. Hardware encoder released (0% idle CPU).");
        }
    }
}
