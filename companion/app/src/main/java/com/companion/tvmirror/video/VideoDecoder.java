package com.companion.tvmirror.video;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class VideoDecoder {
    private static final String TAG = "VideoDecoder";
    private static final String MIME_TYPE = "video/avc";
    private static final int MAX_TIMING_ENTRIES = 64;

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

    private final Object timingLock = new Object();
    private final LinkedHashMap<Long, Long> enqueueTimesNs = new LinkedHashMap<Long, Long>(MAX_TIMING_ENTRIES, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Long> eldest) {
            return size() > MAX_TIMING_ENTRIES;
        }
    };
    private long decodeLatencySumNs = 0;
    private long decodeLatencySamples = 0;

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
        this.cachedSps = null;
        this.cachedPps = null;
        this.configFedToCodec = false;
    }

    public boolean start() {
        final Surface targetSurface;
        final int w;
        final int h;
        final int fps;
        final byte[] initSps;
        final byte[] initPps;
        synchronized (this) {
            if (surface == null || !surface.isValid()) {
                Log.e(TAG, "Cannot start decoder: Surface is null or invalid");
                return false;
            }
            targetSurface = surface;
            w = width;
            h = height;
            fps = targetFps;
            initSps = cachedSps;
            initPps = cachedPps;
        }

        // Preserve any SPS/PPS already received before stopInternal clears active codec state
        stopInternal(false);

        MediaCodec newCodec = null;
        try {
            newCodec = MediaCodec.createDecoderByType(MIME_TYPE);
            MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, w, h);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
            }

            // If we already have SPS/PPS cached, supply them as csd-0 and csd-1 for instant startup
            if (initSps != null) {
                format.setByteBuffer("csd-0", ByteBuffer.wrap(initSps));
            }
            if (initPps != null) {
                format.setByteBuffer("csd-1", ByteBuffer.wrap(initPps));
            }

            newCodec.configure(format, targetSurface, null, 0);
            newCodec.start();

            synchronized (timingLock) {
                enqueueTimesNs.clear();
                decodeLatencySumNs = 0;
                decodeLatencySamples = 0;
            }

            synchronized (this) {
                this.codec = newCodec;
                this.cachedSps = initSps;
                this.cachedPps = initPps;
                this.configFedToCodec = (initSps != null && initPps != null);
                this.frameCount = 0;
                this.lastFpsCalcTime = SystemClock.elapsedRealtime();
                this.lastPresentationTimeUs = 0;
                isRunning.set(true);
                startOutputThread(newCodec);
            }

            Log.i(TAG, "MediaCodec decoder started successfully (" + w + "x" + h + "@" + fps + "fps)");
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Failed to create decoder for " + MIME_TYPE, e);
            releaseCodecQuietly(newCodec);
            stopInternal(false);
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Decoder configuration error", e);
            releaseCodecQuietly(newCodec);
            stopInternal(false);
            return false;
        }
    }

    private void startOutputThread(final MediaCodec activeCodec) {
        outputThread = new Thread(() -> {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (isRunning.get()) {
                try {
                    int outputBufferIndex = activeCodec.dequeueOutputBuffer(info, 10000); // 10ms timeout
                    if (outputBufferIndex >= 0) {
                        long renderNowNs = SystemClock.elapsedRealtimeNanos();
                        Long enqueueNs;
                        synchronized (timingLock) {
                            enqueueNs = enqueueTimesNs.remove(info.presentationTimeUs);
                            if (enqueueNs != null) {
                                decodeLatencySumNs += Math.max(0L, renderNowNs - enqueueNs);
                                decodeLatencySamples++;
                            }
                        }

                        // Render immediately to Surface for lowest latency
                        activeCodec.releaseOutputBuffer(outputBufferIndex, true);

                        frameCount++;
                        long now = SystemClock.elapsedRealtime();
                        long elapsed = now - lastFpsCalcTime;
                        if (elapsed >= 1000) {
                            float calculatedFps = (frameCount * 1000.0f) / elapsed;
                            long avgLatencyMs;
                            synchronized (timingLock) {
                                avgLatencyMs = (decodeLatencySamples > 0)
                                        ? (decodeLatencySumNs / (decodeLatencySamples * 1_000_000L))
                                        : Math.max(1L, elapsed / frameCount);
                                decodeLatencySumNs = 0;
                                decodeLatencySamples = 0;
                            }
                            if (fpsListener != null) {
                                fpsListener.onFpsCalculated(calculatedFps, avgLatencyMs);
                            }
                            frameCount = 0;
                            lastFpsCalcTime = now;
                        }
                    } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat newFormat = activeCodec.getOutputFormat();
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

    public void feedNalUnit(byte[] data, int offset, int length, boolean isConfig, boolean isKeyframe) {
        if (data == null || offset < 0 || length <= 0 || offset + length > data.length) {
            return;
        }

        final MediaCodec activeCodec;
        byte[] spsToFeed = null;
        byte[] ppsToFeed = null;
        boolean skipConfigForward = false;

        synchronized (this) {
            boolean configChanged = false;
            // Cache SPS and PPS regardless of whether decoder is running yet
            if (isConfig) {
                int startCodeLen = 0;
                if (length >= 4 && data[offset] == 0 && data[offset + 1] == 0
                        && data[offset + 2] == 0 && data[offset + 3] == 1) {
                    startCodeLen = 4;
                } else if (length >= 3 && data[offset] == 0 && data[offset + 1] == 0
                        && data[offset + 2] == 1) {
                    startCodeLen = 3;
                }
                int headerIndex = offset + startCodeLen;
                if (headerIndex >= offset + length) {
                    return;
                }
                int nalType = data[headerIndex] & 0x1F;
                byte[] configCopy = new byte[length];
                System.arraycopy(data, offset, configCopy, 0, length);
                if (nalType == 7) {
                    configChanged = !Arrays.equals(cachedSps, configCopy);
                    cachedSps = configCopy;
                    Log.d(TAG, "Cached SPS (" + length + " bytes)");
                } else if (nalType == 8) {
                    configChanged = !Arrays.equals(cachedPps, configCopy);
                    cachedPps = configCopy;
                    Log.d(TAG, "Cached PPS (" + length + " bytes)");
                }
            }

            if (!isRunning.get() || codec == null) {
                return;
            }
            activeCodec = codec;

            if (!configFedToCodec && cachedSps != null && cachedPps != null) {
                spsToFeed = cachedSps;
                ppsToFeed = cachedPps;
            } else if (isConfig && configFedToCodec && !configChanged) {
                // Already configured with identical SPS/PPS; do not re-feed before periodic IDRs
                skipConfigForward = true;
            }
        }

        if (spsToFeed != null && ppsToFeed != null) {
            boolean spsOk = queueBufferToCodec(activeCodec, spsToFeed, 0, spsToFeed.length, MediaCodec.BUFFER_FLAG_CODEC_CONFIG);
            boolean ppsOk = spsOk && queueBufferToCodec(activeCodec, ppsToFeed, 0, ppsToFeed.length, MediaCodec.BUFFER_FLAG_CODEC_CONFIG);
            synchronized (this) {
                if (this.codec == activeCodec) {
                    configFedToCodec = ppsOk;
                }
            }
            if (!ppsOk || isConfig) {
                return;
            }
        } else if (skipConfigForward) {
            return;
        }

        int flags = 0;
        if (isConfig) {
            flags |= MediaCodec.BUFFER_FLAG_CODEC_CONFIG;
        }
        if (isKeyframe) {
            flags |= MediaCodec.BUFFER_FLAG_KEY_FRAME;
        }

        queueBufferToCodec(activeCodec, data, offset, length, flags);
    }

    private boolean queueBufferToCodec(MediaCodec activeCodec, byte[] data, int offset, int length, int flags) {
        if (activeCodec == null || !isRunning.get()) return false;
        try {
            int inputBufferIndex = activeCodec.dequeueInputBuffer(10000); // 10ms timeout (no lock held)
            if (inputBufferIndex < 0) {
                return false;
            }
            ByteBuffer inputBuffer = activeCodec.getInputBuffer(inputBufferIndex);
            if (inputBuffer == null) {
                return false;
            }
            inputBuffer.clear();
            if (length > inputBuffer.remaining()) {
                Log.w(TAG, "NAL unit (" + length + " B) exceeds codec input buffer capacity (" + inputBuffer.remaining() + " B)");
                return false;
            }
            inputBuffer.put(data, offset, length);

            long nowNs = SystemClock.elapsedRealtimeNanos();
            long ptsUs = nowNs / 1000L;
            synchronized (timingLock) {
                if (ptsUs <= lastPresentationTimeUs) {
                    ptsUs = lastPresentationTimeUs + 1L;
                }
                lastPresentationTimeUs = ptsUs;
                if ((flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    enqueueTimesNs.put(ptsUs, nowNs);
                }
            }

            activeCodec.queueInputBuffer(inputBufferIndex, 0, length, ptsUs, flags);
            return true;
        } catch (IllegalStateException e) {
            Log.w(TAG, "Codec input queue failed: " + e.getMessage());
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Error queuing input buffer", e);
            return false;
        }
    }

    public void stop() {
        stopInternal(true);
    }

    private void stopInternal(boolean clearConfigCache) {
        Thread threadToJoin;
        MediaCodec codecToRelease;
        synchronized (this) {
            isRunning.set(false);
            configFedToCodec = false;
            if (clearConfigCache) {
                cachedSps = null;
                cachedPps = null;
            }
            threadToJoin = outputThread;
            outputThread = null;
            codecToRelease = codec;
            codec = null;
        }

        if (threadToJoin != null && threadToJoin != Thread.currentThread()) {
            threadToJoin.interrupt();
            try {
                threadToJoin.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        releaseCodecQuietly(codecToRelease);
    }

    private static void releaseCodecQuietly(MediaCodec target) {
        if (target == null) return;
        try {
            target.stop();
        } catch (Exception ignored) {
        }
        try {
            target.release();
        } catch (Exception ignored) {
        }
    }

    public boolean isRunning() {
        return isRunning.get();
    }
}
