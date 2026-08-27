package audio;

import java.util.Arrays;

/** Exact legacy MFiAudio codec 0x8001 wrapper for the verified 4-bit G.726LE profiles. */
public final class Mfi8001Decoder {
    private static final int OUTPUT_RATE = 32000;
    private static final int Q12_UNITY_MINUS_ONE = 4095;

    private static final double[][] FILTER_8K = {
        coeffs(0x3fd8000000000000L, 0xbf70000000000000L, 0x3fd8000000000000L,
                0x3ff6a40000000000L, 0xbfe6000000000000L),
        coeffs(0x3fa6000000000000L, 0x3fb0c00000000000L, 0x3fa6000000000000L,
                0x3ff7080000000000L, 0xbfe1600000000000L),
        coeffs(0x3fd3a00000000000L, 0xbfc6000000000000L, 0x3fd3a00000000000L,
                0x3ff7240000000000L, 0xbfec780000000000L)
    };

    private static final double[][] FILTER_16K = {
        coeffs(0x3fca95a6c5d206c8L, 0x3fd760956c0d6f54L, 0x3fca95a6c5d206c8L,
                0x3fec8b912dba4d6eL, 0xbfd21240746455ebL),
        coeffs(0x3fd6bd9018e75793L, 0x3fd284db9c7368adL, 0x3fd6bd9018e75793L,
                0x3fe2d65a14488c61L, 0xbfe2d65a14488c61L),
        coeffs(0x3fe437c5692b3cc5L, 0x3fcd09aed56b0100L, 0x3fe437c5692b3cc5L,
                0x3fd8ab14ec204f2bL, 0xbfec0780fdc1615fL)
    };

    private Mfi8001Decoder() {}

    public static boolean supports(int sampleRate, int codedBits, int channels) {
        return codedBits == 4
                && (sampleRate == 8000 || sampleRate == 16000 || sampleRate == 32000)
                && (channels == 1 || channels == 2);
    }

    public static DecodedSampledResource decode(
            byte[] encoded, int sampleRate, int codedBits, int channels) {
        if (encoded == null) throw new IllegalArgumentException("encoded == null");
        if (!supports(sampleRate, codedBits, channels)) {
            throw new IllegalArgumentException("unsupported MFiAudio 0x8001 profile");
        }

        int[] codecStereo;
        if (channels == 1) {
            short[] source = MfiG726Decoder.decode4BitLittleEndian(encoded);
            int[] primary = normalizeChannel(source, sampleRate);
            codecStereo = new int[primary.length * 2];
            for (int i = 0; i < primary.length; i++) {
                codecStereo[i * 2] = primary[i];
                codecStereo[i * 2 + 1] = primary[i];
            }
        } else {
            int half = encoded.length >> 1;
            byte[] leftEncoded = Arrays.copyOfRange(encoded, 0, half);
            byte[] rightEncoded = Arrays.copyOfRange(encoded, half, half + half);
            int[] left = normalizeChannel(MfiG726Decoder.decode4BitLittleEndian(leftEncoded), sampleRate);
            int[] right = normalizeChannel(MfiG726Decoder.decode4BitLittleEndian(rightEncoded), sampleRate);
            int frames = Math.min(left.length, right.length);
            codecStereo = new int[frames * 2];
            for (int i = 0; i < frames; i++) {
                codecStereo[i * 2] = left[i];
                codecStereo[i * 2 + 1] = right[i];
            }
        }
        return new DecodedSampledResource(postCodecFir(codecStereo));
    }

    private static int[] normalizeChannel(short[] reconstructed, int sampleRate) {
        int[] filtered;
        if (sampleRate == OUTPUT_RATE) {
            filtered = new int[reconstructed.length];
            for (int i = 0; i < reconstructed.length; i++) filtered[i] = reconstructed[i];
        } else {
            int factor = OUTPUT_RATE / sampleRate;
            double[][] bank = sampleRate == 8000 ? FILTER_8K : FILTER_16K;
            IirSection first = new IirSection(bank[0]);
            IirSection second = new IirSection(bank[1]);
            IirSection third = new IirSection(bank[2]);
            filtered = new int[reconstructed.length * factor];
            double current = 0.0;
            int sourceIndex = 0;
            for (int out = 0; out < filtered.length; out++) {
                filtered[out] = clamp16(truncateTowardZero(current));
                double input = (out % factor) == 0 && sourceIndex < reconstructed.length
                        ? reconstructed[sourceIndex++] : 0.0;
                current = third.process(second.process(first.process(input)));
            }
        }

        int[] normalized = new int[filtered.length];
        for (int i = 0; i < filtered.length; i++) {
            int sample16 = sampleRate == OUTPUT_RATE
                    ? clamp16(filtered[i]) : filtered[i];
            int levelled = q12TruncateTowardZero(sample16 * Q12_UNITY_MINUS_ONE);
            int primary = q12TruncateTowardZero(levelled * Q12_UNITY_MINUS_ONE);
            normalized[i] = primary << 8;
        }
        return normalized;
    }

    private static int[] postCodecFir(int[] stereo) {
        int frames = stereo.length / 2;
        int[] output = new int[stereo.length];
        int[][] state = new int[2][8];
        for (int frame = 0; frame < frames; frame++) {
            for (int ch = 0; ch < 2; ch++) {
                for (int i = 0; i < 7; i++) state[ch][i] = state[ch][i + 1];
                state[ch][7] = stereo[frame * 2 + ch] >> 6;
                output[frame * 2 + ch] = (int)(((long)state[ch][3] * 8192L) >> 8);
            }
        }
        return output;
    }

    private static int q12TruncateTowardZero(int product) {
        return (product + (product < 0 ? 4095 : 0)) >> 12;
    }

    private static int truncateTowardZero(double value) {
        if (value >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
        if (value <= Integer.MIN_VALUE) return Integer.MIN_VALUE;
        return (int)value;
    }

    private static int clamp16(int value) {
        return value < -32768 ? -32768 : (value > 32767 ? 32767 : value);
    }

    private static double[] coeffs(long a0, long a1, long a2, long a3, long a4) {
        return new double[] {
            Double.longBitsToDouble(a0),
            Double.longBitsToDouble(a1),
            Double.longBitsToDouble(a2),
            Double.longBitsToDouble(a3),
            Double.longBitsToDouble(a4)
        };
    }

    private static final class IirSection {
        private final double[] c;
        private double x1;
        private double x2;
        private double y1;
        private double y2;

        IirSection(double[] c) {
            this.c = c;
        }

        double process(double x) {
            double y = c[0] * x + c[1] * x1 + c[2] * x2 + c[3] * y1 + c[4] * y2;
            x2 = x1;
            x1 = x;
            y2 = y1;
            y1 = y;
            return y;
        }
    }
}
