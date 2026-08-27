package audio;

import java.util.Arrays;

/** Immutable MFiAudio decoded sampled-resource cache in the legacy mixer domain. */
public final class DecodedSampledResource {
    public static final int SAMPLE_RATE = 32000;

    private final int[] interleavedStereo;

    DecodedSampledResource(int[] interleavedStereo) {
        if (interleavedStereo == null) throw new IllegalArgumentException("interleavedStereo == null");
        if ((interleavedStereo.length & 1) != 0) {
            throw new IllegalArgumentException("stereo sample count must be even");
        }
        this.interleavedStereo = Arrays.copyOf(interleavedStereo, interleavedStereo.length);
    }

    public int getSampleRate() {
        return SAMPLE_RATE;
    }

    public int getFrameCount() {
        return interleavedStereo.length / 2;
    }

    int leftAt(int frame) {
        return interleavedStereo[frame * 2];
    }

    int rightAt(int frame) {
        return interleavedStereo[frame * 2 + 1];
    }

    public int[] copyInterleavedStereo() {
        return Arrays.copyOf(interleavedStereo, interleavedStereo.length);
    }
}
