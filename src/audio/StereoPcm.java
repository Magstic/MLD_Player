package audio;

import java.util.Arrays;

/** Immutable interleaved signed PCM16 stereo render product. */
public final class StereoPcm {
    private final int sampleRate;
    private final short[] interleavedPcm16;

    StereoPcm(int sampleRate, short[] interleavedPcm16) {
        if (sampleRate <= 0) throw new IllegalArgumentException("sampleRate <= 0");
        if (interleavedPcm16 == null) throw new IllegalArgumentException("interleavedPcm16 == null");
        if ((interleavedPcm16.length & 1) != 0) {
            throw new IllegalArgumentException("stereo PCM length must be even");
        }
        this.sampleRate = sampleRate;
        this.interleavedPcm16 = Arrays.copyOf(interleavedPcm16, interleavedPcm16.length);
    }

    public int getSampleRate() {
        return sampleRate;
    }

    public int getFrameCount() {
        return interleavedPcm16.length / 2;
    }

    public short[] copyInterleavedPcm16() {
        return Arrays.copyOf(interleavedPcm16, interleavedPcm16.length);
    }
}
