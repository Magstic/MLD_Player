package mld.api;

import java.util.Arrays;

/** Immutable interleaved signed PCM16 result exposed by the stable conversion API. */
public final class MldPcm16 {
    private final int sampleRate;
    private final int channels;
    private final short[] interleavedSamples;

    MldPcm16(int sampleRate, int channels, short[] interleavedSamples) {
        if (sampleRate <= 0) throw new IllegalArgumentException("sampleRate <= 0");
        if (channels <= 0) throw new IllegalArgumentException("channels <= 0");
        if (interleavedSamples == null) throw new IllegalArgumentException("interleavedSamples == null");
        if (interleavedSamples.length % channels != 0) {
            throw new IllegalArgumentException("PCM sample count is not aligned to channels");
        }
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.interleavedSamples = Arrays.copyOf(interleavedSamples, interleavedSamples.length);
    }

    public int getSampleRate() {
        return sampleRate;
    }

    public int getChannels() {
        return channels;
    }

    public int getFrameCount() {
        return interleavedSamples.length / channels;
    }

    public short[] copyInterleavedSamples() {
        return Arrays.copyOf(interleavedSamples, interleavedSamples.length);
    }
}
