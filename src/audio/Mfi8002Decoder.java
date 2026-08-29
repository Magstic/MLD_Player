package audio;

/** Exact MFiAudio/AWC2 4-bit path for the verified 4/8/16/32-kHz mono/stereo profiles. */
public final class Mfi8002Decoder {
    private static final int OUTPUT_RATE = 32000;
    private static final int[] STEP_FACTOR = {230, 230, 230, 230, 307, 409, 512, 614};

    // MFiAudio.dll 0x10002120, exact eighth-phase subset used by 4/8/16/32 kHz.
    private static final int[][] FIR = {
        {0, 0, 0, 8192, 0, 0, 0, 0},
        {-17, 139, -642, 7952, 939, -210, 32, -1},
        {-22, 207, -987, 7260, 2132, -471, 78, -4},
        {-20, 216, -1075, 6201, 3492, -744, 132, -8},
        {-14, 184, -969, 4897, 4897, -969, 184, -14},
        {-8, 132, -744, 3492, 6201, -1075, 216, -20},
        {-4, 78, -471, 2132, 7260, -987, 207, -22},
        {-1, 32, -210, 939, 7952, -642, 139, -17}
    };

    private Mfi8002Decoder() {}

    public static boolean supports(int sampleRate, int codedBits, int channels) {
        return codedBits == 4
                && (sampleRate == 4000 || sampleRate == 8000
                        || sampleRate == 16000 || sampleRate == 32000)
                && (channels == 1 || channels == 2);
    }

    static boolean supportsPayloadLength(int encodedBytes, int channels) {
        return (channels == 1 || channels == 2) && encodedBytes >= 81 * channels;
    }

    static long decodedFrameCount(int encodedBytes, int sampleRate) {
        if (encodedBytes < 0 || sampleRate <= 0 || OUTPUT_RATE % sampleRate != 0) return 0L;
        return encodedBytes * 2L * (OUTPUT_RATE / sampleRate);
    }

    public static DecodedSampledResource decode(
            byte[] encoded, int sampleRate, int codedBits, int channels) {
        if (encoded == null) throw new IllegalArgumentException("encoded == null");
        if (!supports(sampleRate, codedBits, channels)) {
            throw new IllegalArgumentException("unsupported MFiAudio 0x8002 profile");
        }
        if (!supportsPayloadLength(encoded.length, channels)) {
            throw new IllegalArgumentException("MFiAudio 0x8002 payload is below the native AWC2 minimum");
        }

        short[] sourceStereo = channels == 1
                ? duplicateMono(decodeAwc2Mono(encoded))
                : decodeAwc2Stereo(encoded);
        return new DecodedSampledResource(resampleTo32k(sourceStereo, sampleRate));
    }

    static short[] decodeAwc2Mono(byte[] encoded) {
        short[] output = new short[encoded.length * 2];
        AwcState state = new AwcState();
        int out = 0;
        for (byte value : encoded) {
            int packed = value & 0xFF;
            output[out++] = state.decode(packed & 0x0F);
            output[out++] = state.decode((packed >>> 4) & 0x0F);
        }
        return output;
    }

    static short[] decodeAwc2Stereo(byte[] encoded) {
        int sourceFrames = encoded.length * 2;
        short[] output = new short[sourceFrames * 2];
        AwcState left = new AwcState();
        AwcState right = new AwcState();
        int frame = 0;
        for (byte value : encoded) {
            int packed = value & 0xFF;
            output[frame * 2] = left.decode(packed & 0x0F);
            output[frame * 2 + 1] = right.decode((packed >>> 4) & 0x0F);
            frame++;
        }
        // Native AWC2 repacks byte pairs, but caps interleaving at one frame per input byte.
        // The second half of MFiAudio's caller-sized stereo timeline stays zero-initialized.
        return output;
    }

    private static short[] duplicateMono(short[] mono) {
        short[] stereo = new short[mono.length * 2];
        for (int i = 0; i < mono.length; i++) {
            stereo[i * 2] = mono[i];
            stereo[i * 2 + 1] = mono[i];
        }
        return stereo;
    }

    private static int[] resampleTo32k(short[] sourceStereo, int sampleRate) {
        int factor = OUTPUT_RATE / sampleRate;
        int phaseStep = FIR.length / factor;
        int sourceFrames = sourceStereo.length / 2;
        int[] output = new int[sourceFrames * factor * 2];
        int[][] state = new int[2][8];
        int outFrame = 0;
        for (int sourceFrame = 0; sourceFrame < sourceFrames; sourceFrame++) {
            for (int channel = 0; channel < 2; channel++) {
                for (int i = 0; i < 7; i++) state[channel][i] = state[channel][i + 1];
                // AWC2 PCM16 is promoted << 8, then the common MFiAudio FIR consumes it >> 6.
                state[channel][7] = sourceStereo[sourceFrame * 2 + channel] << 2;
            }
            for (int phase = 0; phase < FIR.length; phase += phaseStep) {
                output[outFrame * 2] = fir(state[0], FIR[phase]);
                output[outFrame * 2 + 1] = fir(state[1], FIR[phase]);
                outFrame++;
            }
        }
        return output;
    }

    private static int fir(int[] state, int[] coefficients) {
        int sum = 0;
        for (int i = 0; i < 8; i++) {
            // Deliberately keep x86 signed-int32 wrap semantics before the arithmetic shift.
            sum += state[i] * coefficients[i];
        }
        return sum >> 8;
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : (value > max ? max : value);
    }

    private static final class AwcState {
        int predictor;
        int step = 127;

        short decode(int nibble) {
            int delta = step >> 3;
            if ((nibble & 0x04) != 0) delta += step;
            if ((nibble & 0x02) != 0) delta += step >> 1;
            if ((nibble & 0x01) != 0) delta += step >> 2;
            predictor += (nibble & 0x08) == 0 ? delta : -delta;
            predictor = clamp(predictor, -32768, 32767);
            step = clamp((step * STEP_FACTOR[nibble & 0x07]) >> 8, 127, 24576);
            return (short)predictor;
        }
    }
}
