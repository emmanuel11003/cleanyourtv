package com.companion.tvmirror.video;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

public class VideoDecoder {
    private static final String TAG = "VideoDecoder";
    private static final String MIME_TYPE = "video/avc";

    public interface FpsListener {
        void onFpsCalculated(float fps, long avgLatencyMs);
    }

    private final FpsListener fpsListener;
    private MediaCodec codec;
    private Surface surface;
    private int width = 1280;
    private int height = 720;
    private int targetFps = 60;

    // Cache SPS and PPS config NALs so decoder can initialize even if connected mid-stream
    private byte[] cachedSps = null;
    private byte[] cachedPps = null;
    private boolean configFedToCodec = false;

    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private Thread outputThread;

    private long frameCount = 0;
    private long lastFpsCalcTime = 0;
    private long lastPresentationTimeUs = 0;

    public VideoDecoder(FpsListener fpsListener) {
        this.fpsListener = fpsListener;
    }

    public synchronized void setSurface(Surface surface) {
        this.surface = surface;
    }

    public synchronized void configure(int width, int height, int targetFps) {
        this.width = width > 0 ? width : 1280;
        this.height = height > 0 ? height : 720;
        this.targetFps = targetFps > 0 ? targetFps : 60;
    }

    public synchronized boolean start() {
        if (surface == null || !surface.isValid()) {
            Log.e(TAG, "Cannot start decoder: Surface is null or invalid");
            return false;
        }

        stop();

        try {
            codec = MediaCodec.createDecoderByType(MIME_TYPE);
            MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, width, height);

            format.setInteger(MediaFormat.KEY_FRAME_RATE, targetFps);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
            }

            // If we already have SPS/PPS cached, supply them as csd-0 and csd-1 for instant startup
            if (cachedSps != null) {
                format.setByteBuffer("csd-0", ByteBuffer.wrap(cachedSps));
            }
            if (cachedPps != null) {
                format.setByteBuffer("csd-1", ByteBuffer.wrap(cachedPps));
            }

            codec.configure(format, surface, null, 0);
            codec.start();

            isRunning.set(true);
            configFedToCodec = (cachedSps != null && cachedPps != null);
            frameCount = 0;
            lastFpsCalcTime = SystemClock.elapsedRealtime();
            lastPresentationTimeUs = 0;

            startOutputThread();
            Log.i(TAG, "MediaCodec decoder started successfully (" + width + "x" + height + "@" + targetFps + "fps)");
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Failed to create decoder for " + MIME_TYPE, e);
            stop();
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Decoder configuration error", e);
            stop();
            return false;
        }
    }

    private void startOutputThread() {
        outputThread = new Thread(() -> {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (isRunning.get()) {
                try {
                    MediaCodec currentCodec;
                    synchronized (VideoDecoder.this) {
                        currentCodec = codec;
                    }
                    if (currentCodec == null) break;

                    int outputBufferIndex = currentCodec.dequeueOutputBuffer(info, 10000); // 10ms timeout
                    if (outputBufferIndex >= 0) {
                        // Render immediately to Surface for lowest latency
                        currentCodec.releaseOutputBuffer(outputBufferIndex, true);

                        frameCount++;
                        long now = SystemClock.elapsedRealtime();
                        long elapsed = now - lastFpsCalcTime;
                        if (elapsed >= 1000) {
                            float calculatedFps = (frameCount * 1000.0f) / elapsed;
                            long latencyMs = targetFps > 0 ? (1000 / targetFps) : 16;
                            if (fpsListener != null) {
                                fpsListener.onFpsCalculated(calculatedFps, latencyMs);
                            }
                            frameCount = 0;
                            lastFpsCalcTime = now;
                        }
                    } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat newFormat = currentCodec.getOutputFormat();
                        Log.i(TAG, "Decoder output format changed: " + newFormat);
                    }
                } catch (IllegalStateException e) {
                    if (isRunning.get()) {
                        Log.w(TAG, "Decoder output thread error: " + e.getMessage());
                    }
                    break;
                } catch (Exception e) {
                    Log.e(TAG, "Unexpected error in output thread", e);
                    break;
                }
            }
        }, "TvMirrorOutputDrainer");
        outputThread.start();
    }

    public synchronized void feedNalUnit(byte[] data, int offset, int length, boolean isConfig, boolean isKeyframe) {
        // Cache SPS and PPS regardless of whether decoder is running yet
        if (isConfig) {
            int nalType = (offset + 4 < data.length) ? (data[offset + 4] & 0x1F) : (data[offset + 3] & 0x1F);
            byte[] configCopy = new byte[length];
            System.arraycopy(data, offset, configCopy, 0, length);
            if (nalType == 7) {
                cachedSps = configCopy;
                Log.d(TAG, "Cached SPS (" + length + " bytes)");
            } else if (nalType == 8) {
                cachedPps = configCopy;
                Log.d(TAG, "Cached PPS (" + length + " bytes)");
            }
        }

        if (!isRunning.get() || codec == null) {
            return;
        }

        // If codec started without SPS/PPS, feed them as codec config first
        if (!configFedToCodec && cachedSps != null && cachedPps != null) {
            queueBufferToCodec(cachedSps, 0, cachedSps.length, MediaCodec.BUFFER_FLAG_CODEC_CONFIG);
            queueBufferToCodec(cachedPps, 0, cachedPps.length, MediaCodec.BUFFER_FLAG_CODEC_CONFIG);
            configFedToCodec = true;
        }

        int flags = 0;
        if (isConfig) {
            flags |= MediaCodec.BUFFER_FLAG_CODEC_CONFIG;
        }
        if (isKeyframe) {
            flags |= MediaCodec.BUFFER_FLAG_KEY_FRAME;
        }

        queueBufferToCodec(data, offset, length, flags);
    }

    private void queueBufferToCodec(byte[] data, int offset, int length, int flags) {
        if (codec == null) return;
        try {
            int inputBufferIndex = codec.dequeueInputBuffer(10000); // 10ms timeout
            if (inputBufferIndex >= 0) {
                ByteBuffer inputBuffer = codec.getInputBuffer(inputBufferIndex);
                if (inputBuffer != null) {
                    inputBuffer.clear();
                    inputBuffer.put(data, offset, length);

                    long ptsUs = SystemClock.elapsedRealtimeNanos() / 1000;
                    if (ptsUs <= lastPresentationTimeUs) {
                        ptsUs = lastPresentationTimeUs + (1000000 / targetFps);
                    }
                    lastPresentationTimeUs = ptsUs;

                    codec.queueInputBuffer(inputBufferIndex, 0, length, ptsUs, flags);
                }
            }
        } catch (IllegalStateException e) {
            Log.w(TAG, "Codec input queue failed: " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "Error queuing input buffer", e);
        }
    }

    public synchronized void stop() {
        isRunning.set(false);
        configFedToCodec = false;
        if (outputThread != null) {
            outputThread.interrupt();
            outputThread = null;
        }

        if (codec != null) {
            try {
                codec.stop();
            } catch (Exception ignored) {
            }
            try {
                codec.release();
            } catch (Exception ignored) {
            }
            codec = null;
        }
    }

    public boolean isRunning() {
        return isRunning.get();
    }
}
