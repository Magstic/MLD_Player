package mld.api;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;

import javax.sound.midi.Sequence;

/** Regression coverage for the public conversion API. */
public final class PublicApiAudit {
    private PublicApiAudit() {
    }

    public static void main(String[] args) throws Exception {
        stablePublicSurface();
        melodyConversion();
        loopedMelodyConversion();
        sampledConversion();
        System.out.println("PublicApiAudit: PASS");
    }


    private static void stablePublicSurface() {
        Class<?>[] apiTypes = {
                MldConverter.class, MldConversion.class, MldMidiPlayback.class, MldPcm16.class
        };
        for (Class<?> type : apiTypes) {
            for (java.lang.reflect.Method method : type.getMethods()) {
                if (method.getDeclaringClass() == Object.class) continue;
                assertNoInternalType(type, method.getName(), method.getReturnType());
                for (Class<?> parameter : method.getParameterTypes()) {
                    assertNoInternalType(type, method.getName(), parameter);
                }
            }
        }
    }

    private static void assertNoInternalType(Class<?> owner, String method, Class<?> type) {
        Class<?> component = type;
        while (component.isArray()) component = component.getComponentType();
        Package pkg = component.getPackage();
        String name = pkg == null ? "" : pkg.getName();
        if (name.equals("audio") || name.equals("midi") || name.startsWith("mld.compile")
                || name.startsWith("mld.decode") || name.startsWith("mld.format")
                || name.startsWith("mld.semantic")) {
            throw new AssertionError(owner.getName() + "." + method
                    + " leaks internal type " + component.getName());
        }
    }

    private static void melodyConversion() throws Exception {
        MldConversion conversion = MldConverter.convert(melodyFixture());
        yes("melody present", conversion.hasMidi());
        no("sampled absent", conversion.hasRenderableSampledAudio());
        Sequence sequence = conversion.createMidiSequence();
        yes("fresh sequence", sequence != null && sequence.getTracks().length > 0);
        MldMidiPlayback playback = conversion.createMidiPlayback();
        eq("linear MIDI segment count", 1, playback.getSegmentCount());
        eq("linear MIDI loop segment", -1, playback.getLoopSegmentIndex());
        expectIllegalState("melody sampled render", new Action() {
            public void run() {
                conversion.renderSampledPcm16();
            }
        });
    }

    private static void loopedMelodyConversion() throws Exception {
        MldMidiPlayback audible = MldConverter.convert(loopFixture(true)).createMidiPlayback();
        eq("audible intro segment count", 2, audible.getSegmentCount());
        eq("audible intro loop segment", 1, audible.getLoopSegmentIndex());
        yes("audible intro sequence", audible.createSegmentSequence(0).getTickLength() > 1L);
        yes("audible loop sequence", audible.createSegmentSequence(1).getTickLength() > 1L);

        MldMidiPlayback silent = MldConverter.convert(loopFixture(false)).createMidiPlayback();
        eq("silent intro folded segment count", 1, silent.getSegmentCount());
        eq("silent intro folded loop segment", 0, silent.getLoopSegmentIndex());
    }

    private static void sampledConversion() throws Exception {
        byte[] bytes = Files.readAllBytes(Paths.get("docs/dev/SAMPLE/se_00.mld"));
        MldConversion conversion = MldConverter.convert(bytes);
        yes("sampled renderable", conversion.hasRenderableSampledAudio());
        MldPcm16 pcm = conversion.renderSampledPcm16();
        eq("sampled channels", 2, pcm.getChannels());
        eq("sampled native rate", 32000, pcm.getSampleRate());
        yes("sampled frames", pcm.getFrameCount() > 0);
        short[] copy = pcm.copyInterleavedSamples();
        if (copy.length > 0) {
            short original = copy[0];
            copy[0] = (short)(original ^ 0x55AA);
            eq("pcm copy isolation", original, pcm.copyInterleavedSamples()[0]);
        }
        MldPcm16 resampled = conversion.renderSampledPcm16(16000);
        eq("explicit output rate", 16000, resampled.getSampleRate());
    }

    private static byte[] melodyFixture() throws Exception {
        ByteArrayOutputStream chunks = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(chunks);
        writeBe16Chunk(out, "note", new byte[] {0, 0});
        writeBe16Chunk(out, "cuep", new byte[] {0, 0, 0, 0});
        writeBe32Chunk(out, "trac", new byte[] {
                0, 0x05, 24,
                24, (byte)0xFF, (byte)0xDF, 0
        });
        out.flush();

        byte[] body = chunks.toByteArray();
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(file);
        data.writeBytes("melo");
        data.writeInt(5 + body.length);
        data.writeShort(0);
        data.writeByte(1);
        data.writeByte(1);
        data.writeByte(1);
        data.write(body);
        data.flush();
        return file.toByteArray();
    }

    private static byte[] loopFixture(boolean audibleIntro) throws Exception {
        ByteArrayOutputStream track = new ByteArrayOutputStream();
        DataOutputStream events = new DataOutputStream(track);
        if (audibleIntro) events.write(new byte[] {0, 0x05, 4});
        events.write(new byte[] {10, (byte)0xFF, (byte)0xDD, 0x00});
        events.write(new byte[] {2, 0x09, 4});
        events.write(new byte[] {8, (byte)0xFF, (byte)0xDD, 0x01});
        events.flush();

        ByteArrayOutputStream chunks = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(chunks);
        writeBe16Chunk(out, "note", new byte[] {0, 0});
        writeBe16Chunk(out, "cuep", new byte[] {0, 0, 0, 0});
        writeBe32Chunk(out, "trac", track.toByteArray());
        out.flush();

        byte[] body = chunks.toByteArray();
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(file);
        data.writeBytes("melo");
        data.writeInt(5 + body.length);
        data.writeShort(0);
        data.writeByte(1);
        data.writeByte(1);
        data.writeByte(1);
        data.write(body);
        data.flush();
        return file.toByteArray();
    }

    private static void writeBe16Chunk(DataOutputStream out, String id, byte[] payload) throws Exception {
        out.writeBytes(id);
        out.writeShort(payload.length);
        out.write(payload);
    }

    private static void writeBe32Chunk(DataOutputStream out, String id, byte[] payload) throws Exception {
        out.writeBytes(id);
        out.writeInt(payload.length);
        out.write(payload);
    }

    private static void expectIllegalState(String label, Action action) throws Exception {
        try {
            action.run();
            throw new AssertionError(label + ": expected IllegalStateException");
        } catch (IllegalStateException expected) {
        }
    }

    private static void yes(String label, boolean value) {
        if (!value) throw new AssertionError(label + ": expected true");
    }

    private static void no(String label, boolean value) {
        if (value) throw new AssertionError(label + ": expected false");
    }

    private static void eq(String label, int expected, int actual) {
        if (expected != actual) throw new AssertionError(label + ": expected " + expected + ", got " + actual);
    }

    private static void eq(String label, short expected, short actual) {
        if (expected != actual) throw new AssertionError(label + ": expected " + expected + ", got " + actual);
    }

    private interface Action {
        void run() throws Exception;
    }
}
