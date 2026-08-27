package audio;

/**
 * MFiAudio codec 0x8001 decoder for the 4-bit legacy sampled-audio path.
 *
 * <p>Ported verbatim from the verified MldEngine implementation supplied by the
 * project owner. That implementation was checked on 61 real legacy payloads against
 * FFmpeg g726le with byte-identical PCM. MFi packs 4-bit codewords low nibble first.</p>
 */
public final class MfiG726Decoder {
    private static final int[] IQUANT = {
        -32768, 4, 135, 213, 273, 323, 373, 425,
        425, 373, 323, 273, 213, 135, 4, -32768
    };
    private static final int[] W = {
        -12, 18, 41, 64, 112, 198, 355, 1122,
        1122, 355, 198, 112, 64, 41, 18, -12
    };
    private static final int[] F = {
        0, 0, 0, 1, 1, 1, 3, 7,
        7, 3, 1, 1, 1, 0, 0, 0
    };

    private MfiG726Decoder() {}

    /** Decodes one independent 32 kbit/s G.726 stream, low nibble first. */
    public static short[] decode4BitLittleEndian(byte[] encoded) {
        if (encoded == null) throw new IllegalArgumentException("encoded == null");
        Decoder decoder = new Decoder();
        short[] output = new short[encoded.length * 2];
        int out = 0;
        for (int i = 0; i < encoded.length; i++) {
            int value = encoded[i] & 0xff;
            output[out++] = decoder.decodeCode(value & 0x0f);
            output[out++] = decoder.decodeCode((value >>> 4) & 0x0f);
        }
        return output;
    }

    private static final class Float11 {
        int sign;
        int exponent;
        int mantissa = 32;

        void copyFrom(Float11 other) {
            sign = other.sign;
            exponent = other.exponent;
            mantissa = other.mantissa;
        }
    }

    private static final class Decoder {
        private final Float11[] sr = { new Float11(), new Float11() };
        private final Float11[] dq = {
            new Float11(), new Float11(), new Float11(),
            new Float11(), new Float11(), new Float11()
        };
        private final int[] a = new int[2];
        private final int[] b = new int[6];
        private final int[] pk = { 1, 1 };

        private int ap;
        private int yu = 544;
        private int yl = 34816;
        private int dms;
        private int dml;
        private int td;
        private int se;
        private int sez;
        private int y = 544;

        short decodeCode(int code) {
            code &= 0x0f;

            int dqValue = inverseQuantize(code);
            int ylInteger = yl >> 15;
            int ylFraction = (yl >> 10) & 31;
            int threshold2 = ylInteger > 9 ? (31 << 10) : (32 + ylFraction) << ylInteger;
            int transition = td == 1 && dqValue > ((3 * threshold2) >> 2) ? 1 : 0;

            int sign = code >> 3;
            if (sign != 0) dqValue = -dqValue;

            int reconstructed = se + dqValue;
            int pk0 = (sez + dqValue) != 0 ? signum(sez + dqValue) : 0;
            int dq0 = dqValue != 0 ? signum(dqValue) : 0;

            if (transition != 0) {
                a[0] = 0;
                a[1] = 0;
                for (int i = 0; i < b.length; i++) b[i] = 0;
            } else {
                int fa1 = clip((-a[0] * pk[0] * pk0) >> 5, -256, 255);
                a[1] += 128 * pk0 * pk[1] + fa1 - (a[1] >> 7);
                a[1] = clip(a[1], -12288, 12288);

                a[0] += 192 * pk0 * pk[0] - (a[0] >> 8);
                a[0] = clip(a[0], -(15360 - a[1]), 15360 - a[1]);

                for (int i = 0; i < b.length; i++) {
                    b[i] += 128 * dq0 * signum(-dq[i].sign) - (b[i] >> 8);
                }
            }

            pk[1] = pk[0];
            pk[0] = pk0 != 0 ? pk0 : 1;

            sr[1].copyFrom(sr[0]);
            intToFloat11(reconstructed, sr[0]);
            for (int i = dq.length - 1; i > 0; i--) dq[i].copyFrom(dq[i - 1]);
            intToFloat11(dqValue, dq[0]);
            dq[0].sign = sign;

            td = a[1] < -11776 ? 1 : 0;
            dms += (F[code] << 4) + ((-dms) >> 5);
            dml += (F[code] << 4) + ((-dml) >> 7);

            if (transition != 0) {
                ap = 256;
            } else {
                ap += (-ap) >> 4;
                if (y <= 1535 || td != 0 || Math.abs((dms << 2) - dml) >= (dml >> 3)) {
                    ap += 0x20;
                }
            }

            yu = clip(y + W[code] + ((-y) >> 5), 544, 5120);
            yl += yu + ((-yl) >> 6);
            int al = ap >= 256 ? 64 : ap >> 2;
            y = (yl + (yu - (yl >> 6)) * al) >> 6;

            Float11 coefficient = new Float11();
            se = 0;
            for (int i = 0; i < b.length; i++) {
                se += multiply(intToFloat11(b[i] >> 2, coefficient), dq[i]);
            }
            sez = se >> 1;
            for (int i = 0; i < a.length; i++) {
                se += multiply(intToFloat11(a[i] >> 2, coefficient), sr[i]);
            }
            se >>= 1;

            /* G.726 decoder output is a 14-bit reconstructed signal expanded to PCM16. */
            int pcm = clip(reconstructed << 2, -65535, 65535);
            return (short)pcm;
        }

        private int inverseQuantize(int code) {
            int dql = IQUANT[code] + (y >> 2);
            int exponent = (dql >> 7) & 15;
            int mantissa = 128 + (dql & 127);
            return dql < 0 ? 0 : ((mantissa << exponent) >> 7);
        }
    }

    private static Float11 intToFloat11(int value, Float11 result) {
        result.sign = value < 0 ? 1 : 0;
        if (result.sign != 0) value = -value;
        result.exponent = value == 0 ? 0 : floorLog2(value) + 1;
        result.mantissa = value != 0 ? (value << 6) >> result.exponent : 32;
        return result;
    }

    private static int multiply(Float11 left, Float11 right) {
        int exponent = left.exponent + right.exponent;
        int result = ((left.mantissa * right.mantissa) + 0x30) >> 4;
        result = exponent > 19 ? result << (exponent - 19) : result >> (19 - exponent);
        return (left.sign ^ right.sign) != 0 ? -result : result;
    }

    private static int floorLog2(int value) {
        return value <= 0 ? 0 : 31 - Integer.numberOfLeadingZeros(value);
    }

    private static int signum(int value) {
        return value < 0 ? -1 : 1;
    }

    private static int clip(int value, int min, int max) {
        return value < min ? min : (value > max ? max : value);
    }
}
