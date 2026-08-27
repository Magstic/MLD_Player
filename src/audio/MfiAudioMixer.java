package audio;

/**
 * Integer gain and pan stage reconstructed from the official DoJa SDK
 * MFiAudio.dll.
 *
 * The lookup tables below are ported verbatim from the verified MldEngine copy
 * reconstructed from the SDK DLL. Volume controls are not linear: each 0..127 value is mapped through the official
 * squared gain table, then the four stages are multiplied with a >>16 after
 * every stage. Pan uses the DLL's 128-entry equal-power table.
 */
public final class MfiAudioMixer {
    private static final int[] GAIN = {
        0, 4, 16, 36, 65, 101, 146, 199,
        260, 329, 406, 491, 585, 686, 796, 914,
        1040, 1174, 1316, 1466, 1625, 1791, 1966, 2149,
        2340, 2539, 2746, 2962, 3185, 3417, 3656, 3904,
        4160, 4424, 4697, 4977, 5265, 5562, 5867, 6180,
        6501, 6830, 7167, 7512, 7866, 8227, 8597, 8975,
        9361, 9755, 10157, 10568, 10986, 11413, 11848, 12291,
        12742, 13201, 13668, 14143, 14627, 15119, 15618, 16126,
        16642, 17166, 17699, 18239, 18788, 19344, 19909, 20482,
        21063, 21652, 22249, 22855, 23468, 24090, 24720, 25358,
        26004, 26658, 27320, 27991, 28669, 29356, 30051, 30754,
        31465, 32184, 32911, 33647, 34390, 35142, 35902, 36670,
        37446, 38230, 39022, 39823, 40631, 41448, 42273, 43106,
        43947, 44796, 45653, 46519, 47392, 48274, 49164, 50062,
        50968, 51882, 52805, 53735, 54674, 55620, 56575, 57538,
        58509, 59488, 60476, 61471, 62475, 63487, 64507, 65535
    };

    private static final int[] RIGHT_PAN = {
        65535, 65529, 65514, 65489, 65454, 65409, 65354, 65289,
        65214, 65129, 65034, 64929, 64814, 64689, 64554, 64410,
        64255, 64091, 63917, 63733, 63540, 63336, 63123, 62901,
        62668, 62426, 62175, 61914, 61644, 61364, 61075, 60776,
        60468, 60151, 59825, 59489, 59145, 58791, 58428, 58057,
        57676, 57287, 56889, 56482, 56067, 55643, 55211, 54770,
        54320, 53863, 53397, 52923, 52441, 51951, 51453, 50947,
        50433, 49912, 49383, 48846, 48302, 47750, 47191, 46625,
        46052, 45472, 44885, 44291, 43690, 43083, 42469, 41848,
        41221, 40588, 39948, 39303, 38651, 37994, 37330, 36661,
        35986, 35306, 34621, 33930, 33234, 32533, 31827, 31116,
        30400, 29680, 28955, 28225, 27492, 26754, 26012, 25266,
        24516, 23762, 23005, 22244, 21480, 20713, 19942, 19169,
        18392, 17613, 16831, 16046, 15259, 14469, 13678, 12884,
        12088, 11291, 10492, 9691, 8888, 8085, 7280, 6473,
        5666, 4858, 4050, 3240, 2431, 1620, 810, 0
    };

    private MfiAudioMixer() {}

    public static int gainCoefficient(int trackVolume, int noteLevel,
            int partVolume, int globalVolume) {
        int gain = GAIN[clamp7(trackVolume)];
        gain = multiplyQ16(gain, GAIN[clamp7(noteLevel)]);
        gain = multiplyQ16(gain, GAIN[clamp7(partVolume)]);
        gain = multiplyQ16(gain, GAIN[clamp7(globalVolume)]);
        return gain;
    }

    /**
     * Official MFiAudio combines track and part pan as track+part-64.
     * 0 is hard right, 64 is center and 127 is hard left in the SDK table.
     */
    public static int combinedPan(int trackPan, int partPan) {
        return clamp(clamp7(trackPan) + clamp7(partPan) - 64, 0, 127);
    }

    public static int rightPanCoefficient(int pan) {
        return RIGHT_PAN[clamp7(pan)];
    }

    public static int leftPanCoefficient(int pan) {
        pan = clamp7(pan);
        if (pan == 0) return 0;
        return RIGHT_PAN[128 - pan];
    }

    public static int leftGainCoefficient(int trackVolume, int noteLevel,
            int partVolume, int globalVolume, int trackPan, int partPan) {
        int total = gainCoefficient(trackVolume, noteLevel, partVolume, globalVolume);
        return multiplyQ16(total, leftPanCoefficient(combinedPan(trackPan, partPan)));
    }

    public static int rightGainCoefficient(int trackVolume, int noteLevel,
            int partVolume, int globalVolume, int trackPan, int partPan) {
        int total = gainCoefficient(trackVolume, noteLevel, partVolume, globalVolume);
        return multiplyQ16(total, rightPanCoefficient(combinedPan(trackPan, partPan)));
    }

    /** Applies an already-combined official mixer gain to one PCM sample. */
    public static int applySample(int sample, int gain) {
        return (int)(((long)sample * (long)gain) >> 15);
    }

    private static int multiplyQ16(int left, int right) {
        return (int)(((long)left * (long)right) >> 16);
    }

    private static int clamp7(int value) {
        return clamp(value, 0, 127);
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : (value > max ? max : value);
    }
}
