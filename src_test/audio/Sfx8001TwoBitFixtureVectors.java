package audio;

import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;

import mld.decode.DecodedTrack;
import mld.decode.TrackDecoder;
import mld.format.MldDocument;
import mld.format.MldReader;
import mld.semantic.AudioProgram;
import mld.semantic.Diagnostic;
import mld.semantic.NativeCompiler;
import mld.semantic.NativeProgram;

/** Real 16-kHz mono 2-bit active-adat fixtures from docs/dev/SAMPLE. */
final class Sfx8001TwoBitFixtureVectors {
    private static final String SE_00 =
                "bWVsbwAACGsAdwEBBHZlcnMABDA0MDBub3RlAAIAAWV4c3QAAgABc29yYwABAXN1cHQAFk1GaTRQbHVnSW5fU0ggMDEuMDAuMDVh"
                + "dXRoAABwcm90AABkYXRlAAgyMDA0MTExMGNvcHkAAHRpdGwAAGFpbmYAAgEAdGhyZAADAQAgYWRhdAAAB4cAC4EAYWRwbQADEAIB"
                + "lJZpqYXYtk6uzQI74Q3f1/O3A5QyTHi74yvwOw0UNwVDHQzr/sgX9QDtcJ7L/xoPx7jmDDEtA9J6cL+zPHQVXH++yP/CxAH9fXcx"
                + "MfrADRPsAQ/7/suis/4/3UwHBBT4NwwA4zi+DxJzw8sR3/XfouPERU/hv4/3PGTDw4Eeg93PzUDP/MHGAdLL8rv+KsJAzAyyCzMz"
                + "w8zwP3NWxRNjERKDMIaPxwLFAyTLwrGvTC6E8nB9sLvO+91PzsxbQ0fMgG4xYBF98Sm//j/6NwwADADUTrNwDfQiPHdH1TA3zDPz"
                + "zPADcLOi6nu4DHPqCA4J39IgEwEKD6cQhy0JTROM91QMFF9AYDMrPP/4Lr6P/yTBBHAHiXP8TMy/go4sljQl8Lc0Mq/wP83YZ70e"
                + "rxBAwcHH7DQz0j4rj9fzQRAA3DwsCO7+ojuyI4/YL0BDE8FwZsvMfyzTAyQRwTQs9Mw077QLSRMQMAnwz/O/+vgu8o8P8gsvSD8D"
                + "j8xlBNwtiBzybTAIADAHHPfTIvfeBDBAfxaQBzE58//zPK+jjOxtLqw8DkwnLLMuiQ/++MczMDNQMT89XDETAXQRXARMYecwL888"
                + "M8zqCPxy7wO+bCv8Lz/DC/jOKEMDT8O1sCAo2u+/9EAFQXTTkHD//MpP09MFwfXM8DP/28jwv5e8vO/7vi74zyPPM9PQyDBTsBGA"
                + "3CD9AKTQ5E0wVDPQDUNzEzM/vT68Mz+8LIjjKLK7L4r8/uTD+D/I8Px/HXTH0ZAxfdgNHNM0dMA10DTQDUzP8CK7P+I78rvzwo/A"
                + "wPxxL4/P7jj4jv3/948Mzzc0wVDHEHxDDD3ET8AHBxkADAD3NOwM76zLJrPM//zC/oL/v//jLCM8IQzMDM8PVt1AEB90D8AJCZ/w"
                + "8yEy3DlAPwnc8zBszA3/zA+CO/77Ir8LLyuL83v+7Mu8z7M8s/zBfAHcATR0AATAlDGEDTbz8fQQM3DC8+v/6v/r/Asj9snxMPHD"
                + "8TAPM+MBM0sAewFD0XcT2DBzCPI/7cPx8/EkA/A/84vPeAQTAX8zvvvu777sb//fDvLP3sLDnEzjTHhAEwQQEEA3AUnTw/zhzywP"
                + "z0z8A6zg+7j4+/vO+8KOzII8Iw/MA/R90DA1k5H0wPDzw8MQERAEAAECmQz/mXDCzD+Az8M7/DIryPIj/ywvzrIIyA/DHzPNSfMP"
                + "tAMwDNANEBME0MExAQE0YXzDDCAP+M+/jP+Pssw+yQz8PDgs8y0v+Iv8y8xMAwQHNAENNAA82T8CvwDz3NzDA4LM8vA/CC7y8vv/"
                + "PwBE0F1Dw8OvyCL/z88MwG3zPP+PIz/zAHQHEMQHcB82x8wBzDE9wD0twCAzL4/7vr6/8oPPzUw3zDE9QNMMxDPM/70w/Ed8QHAM"
                + "wzj7/u/8IDMgLOwIvzzwDAE8Dc+8D8rLDBMQAUEAEMzA88i/yMgi/zgLyA/wA0BDNwFwA8PwMPAD8Ofz7sj/8wjAQD0wyDgu+y/O"
                + "PH8MTTfQDNw/IDP8NMAQDDRAQ9M0NA88/u/+4nsy8/w9MoA8AMMEc0BPDNwAMz0MAwwBdNMAA4A8j7Lj7P/7L/u/+MjLwzLAQN0Q"
                + "99zw98A8cE8DAA0AxwOAAMizvP+yyD//3PMAwAQ9EAsADsPL/4u8vJggzwjCz8zLsjj7v/+yjDzzA0AHdDQBAU03A83cPdP8ACL/"
                + "yMvzwv88LA87y/v//vvzPvwzDRNAQ9NMMwB/DzwMcNwNAA0ADE0CwwzAQs8P/z8gQ/PBzUDDwLODv8/+vzgj8iz//+z8I8/z9zA0"
                + "0E03QDMNDHRP0zABc9ADNAPNAPAw/C/v/ysLIizPy/w/yD8y8v/z8wj8EwMExE1Dww1AD8AMwwM9NwAN3QAAAzwPL87L8i+//P+8"
                + "zPPPM/KzPDsLv9z8NMzAMPDwfQ8ABEDQAN0ANw0AADE0MM0AzPTAgAAjDCzz77wvr8wu8/v0LwzLw/8z+wMizA0TNxBD00ADfQMA"
                + "Aw0wM4AwDANwMz088/77IvK/vPz/8v8/j38jA88D88DPzCfD/MPw/DQARwMBEBDQMU0D0EADANDQDDQPzU/z3/Pz/4vsL/s/+5Mj"
                + "zDL3wv1Dzx/PxzAAwMQAwXQDDRPNdAPAwADPQ/IPv/zI/z/O8/MD/jfLDM7/y///89/DcwzcANwM9zzwP78gMzwcANE0NAANEzPc"
                + "zDczD8P/zP6/O7/PO8/LP78s/8v/Mr/MLMw8MAzEMQEcQAMAQwACgMw9T/MMPXMPD/POv/LsP+//OPLz/4AP8EgPD8wIMzLDP9A8"
                + "QDMAABMDDQwNAwzDDDMDAxAzxAAANMAAAzMPy/zy/+8jL8/L88vz//POjPw8P8/8Mz1PzBDQQAxNM9AD0zPAP/D/9PPwIPz8/C//"
                + "y//L//Py84LwP/PPPMPAAM0MAD8AzDAMwMzM8/D/PDLzDM/D/DM/P/PMAAMxQDAQMEAMA80w8C+//zvyP/8//D89M8AMDDDAADAA"
                + "MAAMADPADDPDzzA/8/zP/M/MDz/P//P//P/8PA8zAzDTwDTAAPQPdHJhYwAAAC8A/8NQAP+wfwB/8AAGABSNiQAGAH+APwF/AAA3"
                + "B3/wAAYAFoaMACq4f5A/AP/fAHRyYWMAAAAEwP/fAHRyYWMAAAAMAP/hQgD/4EDA/98AdHJhYwAAAATA/98A";
    private static final String SE_01 =
                "bWVsbwAAA3wAdwEBBHZlcnMABDA0MDBub3RlAAIAAWV4c3QAAgABc29yYwABAXN1cHQAFk1GaTRQbHVnSW5fU0ggMDEuMDAuMDVh"
                + "dXRoAABwcm90AABkYXRlAAgyMDA0MTExMGNvcHkAAHRpdGwAAGFpbmYAAgEAdGhyZAADAQAgYWRhdAAAApgAC4EAYWRwbQADEAIB"
                + "qFWlql5bU3vAejhLKBd7MSvhY7F8PeHoA2GxfzBI6wEAlQ/kLKEDC0Ap8zp8efw94RMuBM2z+jEwgcgtL+0whQC/7viXEPw9DAsr"
                + "fj9VLfpoMysccY3jf0zkNIM+LD5RBPy4bn8w/0GwrQw8YeONTLAMsBD7/b/Q0shO89PIwADOTjioCxBFuO8rhM00cOU/wwOwwr4x"
                + "NcPa8ZP/MnCEMM57LzzdEBA7689DQwsQe7/gwB4NyNwAzt4CEMIPDvOxA9dP6BTB/PrTQwwRonDO6A8NUSiB4/w6YQCofDDCfBC/"
                + "swyw0MdA+z9Pi08DDIcFKLMMDGyjEOwU8IGr0R/xL/Q/+0I3N/wsgDTP49TH49ME8L8MzfY7MJBfXy7y8xTe/I7xTrD9CxTLK3s0"
                + "EE8z/54/nxDSDiwQzDoIHM8I8fWg2CjRzkMfsNDyw/K1QsTAq9eBk7zHwAS6xdyiDwXXuP+w0zMPwJQc/gC7wxMTyuNwRwO/Uwfr"
                + "/kTRv/zF0b8s/eT/AwH9sIAgrwjGEJm888fD8wLN8/sH7eA3sfi/QfP0jIQ88NCAzMo3zINPBSsAQID+vzz/THQ0aLLMjQvNTPG/"
                + "+MMxMD/E8+1w9czuMzMPz3IBFC9PP/H7wDPNEO3D7/0s8UM8/PHr8/GD/LDgxzWjszDENLDDjP/LAABMMHvwCu9QAAIMz7s+0+08"
                + "DEX/D3/z/zMAy4sT8zHw8i8AM2tM8DwCCLHOM8TwNDPxM+zM4QCsAIDA+wPwwz7Tf/wDAtL//McwMDrwEEd/MDwvPywv/3AwDMEw"
                + "8D+9w8P0vxAw7jz833wPMAywzz9M78xPAMszXMA/zw/MHw9AzxwPDz4P/NA8M/PAM/AAdHJhYwAAAC8A/8NQAP+wfwB/8AAGAB+5"
                + "jgAAAH/wAAYAIE+MAA4Af4A/AX8AADe/f5A/AP/fAHRyYWMAAAAEwP/fAHRyYWMAAAAMAP/hQgD/4EDA/98AdHJhYwAAAATA/98A";

    private Sfx8001TwoBitFixtureVectors() {}

    static void audit() {
        verify(
                "se_00.mld",
                SE_00,
                "591c4cc3244bf25c7f59ac06ec15561f04c84dd55ea916a1b105397b0cfa2b11",
                1914,
                7656,
                15312,
                "e6aef68bcffaace5b1913ed06c2114c3d144b4934b9b1f59c486f88fef213e04");
        verify(
                "se_01.mld",
                SE_01,
                "144582dd79bd16c3a7735a2d253cb78f9b7ef53410ee9f259c024cc6155f2e7b",
                651,
                2604,
                5208,
                "a83f479758235f7dd73460d9d52bf2ffc217975d346a8be77e85fe517e0a5da4");
    }

    private static void verify(
            String name,
            String mldBase64,
            String expectedMldSha256,
            int encodedBytes,
            int sourceSamples,
            int decodedFrames,
            String expectedSourceSha256) {
        try {
            byte[] mld = Base64.getDecoder().decode(mldBase64);
            String mldHash = hex(MessageDigest.getInstance("SHA-256").digest(mld));
            if (!expectedMldSha256.equals(mldHash)) {
                fail(name + " MLD SHA-256", "expected " + expectedMldSha256 + ", got " + mldHash);
            }
            MldDocument document = new MldReader().read(mld);
            List<DecodedTrack> tracks = new TrackDecoder().decodeAll(document);
            NativeProgram program = new NativeCompiler().compile(document, tracks);
            AudioProgram.AudioAction start = resourceStart(program.audio);
            if (start.rendererSupport != AudioProgram.RendererSupport.VERIFIED_8001_2BIT) {
                fail(name + " support", "got " + start.rendererSupport);
            }
            eq(name + " rate", 16000, start.sampleRate);
            eq(name + " coded bits", 2, start.codedBits);
            eq(name + " channels", 1, start.channelCount);

            AudioProgram.ResourceCatalogEntry entry = catalog(program.audio, start.linkedCatalogIndex);
            if (entry.sampledResource == null) fail(name + " sampled resource", "missing");
            byte[] encoded = entry.sampledResource.copyEncodedPayload();
            eq(name + " encoded bytes", encodedBytes, encoded.length);

            short[] source = MfiG726Decoder.decode2BitLittleEndian(encoded);
            eq(name + " source samples", sourceSamples, source.length);
            String actualHash = hex(MessageDigest.getInstance("SHA-256").digest(short16Le(source)));
            if (!expectedSourceSha256.equals(actualHash)) {
                fail(name + " source SHA-256", "expected " + expectedSourceSha256 + ", got " + actualHash);
            }

            DecodedSampledResource decoded = Mfi8001Decoder.decode(encoded, 16000, 2, 1);
            eq(name + " decoded frames", decodedFrames, decoded.getFrameCount());

            AudioRenderer renderer = new AudioRenderer();
            if (!renderer.hasRenderableAudio(program)) fail(name + " renderable", "false");
            StereoPcm rendered = renderer.render(program);
            eq(name + " native rate", 32000, rendered.getSampleRate());
            if (!hasSignal(rendered.copyInterleavedPcm16())) {
                fail(name + " rendered signal", "all samples are zero");
            }
            noDiagnostic(program, "AUDIO_RENDERER_RESOURCE_ADAT_UNSUPPORTED");
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new AssertionError(name + ": " + ex, ex);
        }
    }

    private static AudioProgram.AudioAction resourceStart(AudioProgram audio) {
        for (AudioProgram.AudioAction action : audio.actions) {
            if (action.kind == AudioProgram.ActionKind.RESOURCE_START) return action;
        }
        fail("resource start", "missing");
        return null;
    }

    private static AudioProgram.ResourceCatalogEntry catalog(AudioProgram audio, int catalogIndex) {
        for (AudioProgram.ResourceCatalogEntry entry : audio.resourceCatalog) {
            if (entry.catalogIndex == catalogIndex) return entry;
        }
        fail("resource catalog", "missing catalog index " + catalogIndex);
        return null;
    }

    private static void noDiagnostic(NativeProgram program, String code) {
        for (Diagnostic diagnostic : program.diagnostics) {
            if (code.equals(diagnostic.code)) {
                fail("diagnostic " + code, diagnostic.message);
            }
        }
    }

    private static byte[] short16Le(short[] samples) {
        byte[] bytes = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            int value = samples[i] & 0xFFFF;
            bytes[i * 2] = (byte)value;
            bytes[i * 2 + 1] = (byte)(value >>> 8);
        }
        return bytes;
    }

    private static boolean hasSignal(short[] samples) {
        for (short sample : samples) {
            if (sample != 0) return true;
        }
        return false;
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) out.append(String.format("%02x", value & 0xFF));
        return out.toString();
    }

    private static void eq(String name, int expected, int actual) {
        if (expected != actual) fail(name, "expected " + expected + ", got " + actual);
    }

    private static void fail(String name, String detail) {
        throw new AssertionError(name + ": " + detail);
    }
}
