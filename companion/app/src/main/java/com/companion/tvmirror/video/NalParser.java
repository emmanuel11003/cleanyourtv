package com.companion.tvmirror.video;

public class NalParser {

    public interface NalListener {
        void onNalUnit(byte[] data, int offset, int length, boolean isConfig, boolean isKeyframe);
    }

    private final NalListener listener;
    private byte[] buffer = new byte[256 * 1024]; // 256 KB buffer
    private int bufferLength = 0;

    public NalParser(NalListener listener) {
        this.listener = listener;
    }

    public void feedData(byte[] chunk, int offset, int length) {
        if (length <= 0) return;

        // Ensure buffer has enough space
        if (bufferLength + length > buffer.length) {
            int newCap = Math.max(buffer.length * 2, bufferLength + length + 65536);
            byte[] newBuf = new byte[newCap];
            System.arraycopy(buffer, 0, newBuf, 0, bufferLength);
            buffer = newBuf;
        }

        System.arraycopy(chunk, offset, buffer, bufferLength, length);
        bufferLength += length;

        // Parse NAL units delimited by start codes (00 00 01 or 00 00 00 01)
        int currentNalStart = findNextStartCode(0);
        if (currentNalStart == -1) {
            // Keep at most 4 bytes at end in case start code is split across chunks
            if (bufferLength > 4) {
                System.arraycopy(buffer, bufferLength - 4, buffer, 0, 4);
                bufferLength = 4;
            }
            return;
        }

        while (currentNalStart != -1) {
            int startCodeLen = (currentNalStart + 3 < bufferLength && buffer[currentNalStart] == 0 && buffer[currentNalStart + 1] == 0 && buffer[currentNalStart + 2] == 0 && buffer[currentNalStart + 3] == 1) ? 4 : 3;
            int nextNalStart = findNextStartCode(currentNalStart + startCodeLen);

            if (nextNalStart != -1) {
                // We have a complete NAL unit from currentNalStart to nextNalStart
                int nalLength = nextNalStart - currentNalStart;
                int nalTypeIndex = currentNalStart + startCodeLen;
                if (nalTypeIndex < bufferLength) {
                    int nalType = buffer[nalTypeIndex] & 0x1F;
                    boolean isConfig = (nalType == 7 || nalType == 8); // SPS or PPS
                    boolean isKeyframe = (nalType == 5); // IDR slice

                    listener.onNalUnit(buffer, currentNalStart, nalLength, isConfig, isKeyframe);
                }
                currentNalStart = nextNalStart;
            } else {
                // Partial NAL unit: compact buffer and wait for more data
                int remaining = bufferLength - currentNalStart;
                System.arraycopy(buffer, currentNalStart, buffer, 0, remaining);
                bufferLength = remaining;
                break;
            }
        }
    }

    private int findNextStartCode(int start) {
        int end = bufferLength - 3;
        for (int i = start; i < end; i++) {
            if (buffer[i] == 0 && buffer[i + 1] == 0) {
                if (buffer[i + 2] == 1) {
                    return i;
                }
                if (i + 3 < bufferLength && buffer[i + 2] == 0 && buffer[i + 3] == 1) {
                    return i;
                }
            }
        }
        return -1;
    }

    public void reset() {
        bufferLength = 0;
    }
}
