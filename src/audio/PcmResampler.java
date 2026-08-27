package audio;

/** Band-limited mono PCM16 resampler intended for build-time MLD rendering. */
public final class PcmResampler {
    private static final int RADIUS = 16; // 32-tap windowed-sinc kernel.
    private static final double NYQUIST_GUARD = 0.94;

    private PcmResampler() {}

    /**
     * Resamples a mono PCM16 signal with a guarded low-pass kernel. Equal-rate
     * input is returned as a clone without filtering; native sampled-audio output is not
     * modified unless the caller explicitly requests a different rate.
     */
    public static short[] resampleMono(short[] input, int inputRate, int outputRate) {
        if (input == null) throw new IllegalArgumentException("input == null");
        if (inputRate <= 0) throw new IllegalArgumentException("inputRate <= 0");
        if (outputRate <= 0) throw new IllegalArgumentException("outputRate <= 0");
        if (input.length == 0) return new short[0];
        if (inputRate == outputRate) return input.clone();

        long outputLengthLong = resampledFrameCount(input.length, inputRate, outputRate);
        if (outputLengthLong > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("resampled PCM is too large");
        }
        int outputLength = (int)Math.max(1L, outputLengthLong);
        short[] output = new short[outputLength];

        double rateRatio = (double)outputRate / (double)inputRate;
        double cutoff = 0.5 * Math.min(1.0, rateRatio) * NYQUIST_GUARD;

        for (int out = 0; out < outputLength; out++) {
            // Align sample centers instead of pinning the first output exactly
            // to input[0], which reduces phase bias during downsampling.
            double sourcePosition = ((out + 0.5) * inputRate / (double)outputRate) - 0.5;
            int center = (int)Math.floor(sourcePosition);
            double weighted = 0.0;
            double weightSum = 0.0;

            int first = center - RADIUS + 1;
            int last = center + RADIUS;
            for (int index = first; index <= last; index++) {
                if (index < 0 || index >= input.length) continue;
                double distance = sourcePosition - index;
                double weight = kernel(distance, cutoff);
                weighted += input[index] * weight;
                weightSum += weight;
            }

            int value;
            if (Math.abs(weightSum) < 1.0e-12) {
                int nearest = (int)Math.floor(sourcePosition + 0.5);
                if (nearest < 0) nearest = 0;
                else if (nearest >= input.length) nearest = input.length - 1;
                value = input[nearest];
            } else {
                value = (int)Math.round(weighted / weightSum);
            }
            output[out] = (short)clamp(value, -32768, 32767);
        }
        return output;
    }

    static long resampledFrameCount(long inputFrameCount, int inputRate, int outputRate) {
        if (inputFrameCount < 0L) throw new IllegalArgumentException("inputFrameCount < 0");
        if (inputRate <= 0) throw new IllegalArgumentException("inputRate <= 0");
        if (outputRate <= 0) throw new IllegalArgumentException("outputRate <= 0");
        if (inputFrameCount == 0L || inputRate == outputRate) return inputFrameCount;
        if (inputFrameCount > Long.MAX_VALUE / outputRate) return Long.MAX_VALUE;
        long scaled = inputFrameCount * outputRate;
        long rounding = inputRate - 1L;
        if (scaled > Long.MAX_VALUE - rounding) return Long.MAX_VALUE;
        return (scaled + rounding) / inputRate;
    }

    private static double kernel(double distance, double cutoff) {
        double absolute = Math.abs(distance);
        if (absolute >= RADIUS) return 0.0;

        double ideal;
        if (absolute < 1.0e-12) ideal = 2.0 * cutoff;
        else ideal = Math.sin(2.0 * Math.PI * cutoff * distance) / (Math.PI * distance);

        double phase = Math.PI * distance / RADIUS;
        double window = 0.42 + 0.5 * Math.cos(phase) + 0.08 * Math.cos(2.0 * phase);
        return ideal * window;
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : (value > max ? max : value);
    }
}
