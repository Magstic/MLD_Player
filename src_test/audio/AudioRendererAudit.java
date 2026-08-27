package audio;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import mld.decode.DecodedTrack;
import mld.decode.MachineDependentEvent;
import mld.decode.SystemEvent;
import mld.decode.TrackDecoder;
import mld.decode.TrackEvent;
import mld.format.MldDocument;
import mld.semantic.AudioProgram;
import mld.semantic.NativeCompiler;
import mld.semantic.NativeProgram;

/** Deterministic code/mixer/integration vectors for the verified MldEngine 0x8001 subset. */
public final class AudioRendererAudit {
    public static void main(String[] args) {
        auditVerifiedDecoderVector();
        auditVerifiedMixerVectors();
        auditVerifiedLegacyRendererWiring();
        auditLightweightLinearDuration();
        auditVoiceStartStateAndTiming();
        auditUnverifiedControlDoesNotPolluteVerifiedMix();
        auditAudioProfileMetadataDoesNotGateRenderer();
        auditMixedUnsupportedProgramFailsClosed();
        auditFractionalLoopFramePhase();
        auditStrictRendererBoundary();
        System.out.println("AudioRendererAudit: PASS");
    }

    private static void auditVerifiedDecoderVector() {
        byte[] encoded = new byte[] {
                0x00, 0x12, 0x34, 0x56, 0x78,
                (byte) 0x9A, (byte) 0xBC, (byte) 0xDE, (byte) 0xF0
        };
        short[] expected = new short[] {
                0, 0, 16, 8, 36, 28, 68, 76, -132,
                468, -920, -1500, -1060, -1428, -620, -836, -332, -244
        };
        eqShorts("MldEngine G.726 vector", expected,
                MfiG726Decoder.decode4BitLittleEndian(encoded));
    }

    private static void auditVerifiedMixerVectors() {
        eq("default gain", 16641,
                MfiAudioMixer.gainCoefficient(127, 127, 127, 64));
        eq("default combined pan", 64, MfiAudioMixer.combinedPan(64, 64));
        eq("default left gain", 11693,
                MfiAudioMixer.leftGainCoefficient(127, 127, 127, 64, 64, 64));
        eq("default right gain", 11693,
                MfiAudioMixer.rightGainCoefficient(127, 127, 127, 64, 64, 64));
        eq("low part-level gain", 32,
                MfiAudioMixer.gainCoefficient(127, 127, 4, 90));
        eq("low part-level left", 1,
                MfiAudioMixer.leftGainCoefficient(127, 127, 4, 90, 64, 6));
        eq("low part-level right", 31,
                MfiAudioMixer.rightGainCoefficient(127, 127, 4, 90, 64, 6));
        eq("fixed-point sample multiply", 6269,
                MfiAudioMixer.applySample(12345, 16641));
    }

    private static void auditVerifiedLegacyRendererWiring() {
        NativeProgram program = compile(Collections.<TrackEvent>singletonList(md(
                0, 0,
                0x71, 0x84,
                0x00, 0x45, 0x00,
                0x00, 0x00, 0x00, 0x02,
                0x12, 0x34)), 0);
        AudioProgram.AudioAction action = program.audio.actions.get(0);
        same("verified support", AudioProgram.RendererSupport.VERIFIED_8001_4BIT,
                action.rendererSupport);

        for (String warning : program.warnings) {
            if (warning.contains("renderer round")) {
                fail("stale renderer warning", warning);
            }
        }
        AudioRenderer renderer = new AudioRenderer();
        eq("native sample rate", 8000, renderer.nativeSampleRate(program));
        StereoPcm rendered = renderer.render(program);
        eq("stereo rate", 8000, rendered.getSampleRate());
        eq("stereo frames", 4, rendered.getFrameCount());
        eqShorts("stereo integration vector",
                new short[] {5, 5, 2, 2, 12, 12, 9, 9},
                rendered.copyInterleavedPcm16());
    }

    private static void auditVoiceStartStateAndTiming() {
        List<TrackEvent> events = new ArrayList<TrackEvent>();
        events.add(md(0, 0, 0x71, 0x81, 0x28)); // part volume = 80
        events.add(md(1, 0, 0x71, 0x82, 0x10)); // part pan = 32
        events.add(system(2, 0, 0xB0, 90));
        events.add(md(
                3, 2,
                0x71, 0x84,
                0x00, 0x45, 0x00,
                0x00, 0x00, 0x00, 0x02,
                0x12, 0x34));
        StereoPcm rendered = new AudioRenderer().render(compile(events, 2));
        eq("timed render frames", 164, rendered.getFrameCount());
        short[] pcm = rendered.copyInterleavedPcm16();
        for (int i = 0; i < 320; i++) {
            if (pcm[i] != 0) fail("timed render silence", "nonzero sample before raw-tick start");
        }
        eqShorts("voice-start level/pan/global state",
                new short[] {2, 5, 1, 2, 5, 13, 4, 10},
                Arrays.copyOfRange(pcm, 320, 328));
    }

    private static void auditLightweightLinearDuration() {
        List<TrackEvent> events = new ArrayList<TrackEvent>();
        events.add(md(
                0, 0,
                0x71, 0x84,
                0x00, 0x45, 0x00,
                0x00, 0x00, 0x00, 0x03,
                0x12, 0x34, 0x56));
        events.add(md(
                1, 1,
                0x71, 0x84,
                0x01, 0x4D, 0x00,
                0x00, 0x00, 0x00, 0x02,
                0x12, 0x34));
        NativeProgram program = compile(events, 1);
        AudioRenderer renderer = new AudioRenderer();
        StereoPcm rendered = renderer.render(program);
        long expectedMillis = ((long) rendered.getFrameCount() * 1000L
                + rendered.getSampleRate() - 1L) / rendered.getSampleRate();
        eqLong(
                "lightweight linear duration matches rendered frames",
                expectedMillis,
                renderer.estimateLinearDurationMillis(program));
    }


    private static void auditUnverifiedControlDoesNotPolluteVerifiedMix() {
        MachineDependentEvent start = md(1, 0,
                0x71, 0x84, 0x00, 0x45, 0x00,
                0x00, 0x00, 0x00, 0x02, 0x12, 0x34);
        StereoPcm baseline = new AudioRenderer().render(compile(
                Collections.<TrackEvent>singletonList(start), 0));

        List<TrackEvent> events = new ArrayList<TrackEvent>();
        events.add(md(0, 0, 0x11, 0x01, 0xF1, 0x06, 0x00, 0x00));
        events.add(start);
        StereoPcm withCompactPan = new AudioRenderer().render(compile(events, 0));
        eqShorts("unverified compact pan ignored by verified renderer",
                baseline.copyInterleavedPcm16(), withCompactPan.copyInterleavedPcm16());
    }

    private static void auditAudioProfileMetadataDoesNotGateRenderer() {
        MachineDependentEvent start = md(1, 0,
                0x71, 0x84, 0x00, 0x45, 0x00,
                0x00, 0x00, 0x00, 0x02, 0x12, 0x34);
        NativeProgram baselineProgram = compile(
                Collections.<TrackEvent>singletonList(start), 0);
        StereoPcm baseline = new AudioRenderer().render(baselineProgram);

        List<TrackEvent> realWorldMixedProfile = new ArrayList<TrackEvent>();
        realWorldMixedProfile.add(md(0, 0,
                0x71, 0x8F,
                0x81, 0x01, 0x01,
                0x89, 0x01, 0x01,
                0x83, 0x01, 0x02,
                0x8B, 0x01, 0x02));
        realWorldMixedProfile.add(start);
        NativeProgram mixed = compile(realWorldMixedProfile, 0);
        if (!new AudioRenderer().hasRenderableAudio(mixed)) {
            fail("mixed 71:8F metadata", "Player no-op metadata blocked verified 71:84 audio");
        }
        eqShorts("mixed 71:8F metadata leaves PCM unchanged",
                baseline.copyInterleavedPcm16(),
                new AudioRenderer().render(mixed).copyInterleavedPcm16());

        List<TrackEvent> malformedMetadata = new ArrayList<TrackEvent>();
        malformedMetadata.add(md(0, 0, 0x71, 0x8F, 0x81, 0x01, 0x05));
        malformedMetadata.add(start);
        NativeProgram malformed = compile(malformedMetadata, 0);
        if (!new AudioRenderer().hasRenderableAudio(malformed)) {
            fail("unmodeled 71:8F metadata", "no-op metadata blocked verified 71:84 audio");
        }
        eqShorts("unmodeled 71:8F metadata leaves PCM unchanged",
                baseline.copyInterleavedPcm16(),
                new AudioRenderer().render(malformed).copyInterleavedPcm16());
        assertDiagnostic("AUDIO_718F_METADATA_UNMODELED", malformed);
    }

    private static void auditMixedUnsupportedProgramFailsClosed() {
        List<TrackEvent> events = new ArrayList<TrackEvent>();
        events.add(md(0, 0,
                0x71, 0x84, 0x00, 0x45, 0x00,
                0x00, 0x00, 0x00, 0x02, 0x12, 0x34));
        events.add(md(1, 0, 0x71, 0x86, 0x00, 0x45, 0x00, 0x12));
        NativeProgram program = compile(events, 0);
        if (new AudioRenderer().hasRenderableAudio(program)) {
            fail("mixed unsupported sampled path", "partial renderer output remained enabled");
        }
        assertPrepareRejected("mixed unsupported sampled path fail-closed", program);
    }

    private static void auditFractionalLoopFramePhase() {
        AudioPcmTimeline timeline = new AudioPcmTimeline(
                16000,
                Collections.<AudioPlaybackSource.Voice>emptyList(),
                true,
                0L,
                78740L,
                -1,
                78740L,
                1L);
        long expected = (78740L * 1000L * 16000L) / 1000000L;
        eqLong("fractional frame pass 1000", expected,
                timeline.instanceStartFrameForAudit(0L, 1000L, 78740L));
    }


    private static void assertDiagnostic(String code, NativeProgram program) {
        for (mld.semantic.Diagnostic diagnostic : program.audio.diagnostics) {
            if (code.equals(diagnostic.code)) return;
        }
        fail("diagnostic " + code, "missing");
    }

    private static void assertPrepareRejected(String name, NativeProgram program) {
        try {
            new AudioRenderer().preparePlayback(program);
            fail(name, "expected strict renderer rejection");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    private static void auditStrictRendererBoundary() {
        List<TrackEvent> events = new ArrayList<TrackEvent>();
        events.add(md(
                0, 0,
                0x71, 0x84,
                0x00, 0x45, 0x01,
                0x00, 0x00, 0x00, 0x01,
                0x12));
        NativeProgram program = compile(events, 0);
        same("flags=1 typed but unsupported",
                AudioProgram.RendererSupport.RECOGNIZED_UNSUPPORTED,
                program.audio.actions.get(0).rendererSupport);
        try {
            new AudioRenderer().nativeSampleRate(program);
            fail("strict renderer boundary", "expected no verified audio start");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    private static NativeProgram compile(List<TrackEvent> events, int totalRawTicks) {
        int systemCount = 0;
        int machineCount = 0;
        for (TrackEvent event : events) {
            if (event instanceof SystemEvent) systemCount++;
            if (event instanceof MachineDependentEvent) machineCount++;
        }
        DecodedTrack track = new DecodedTrack(
                0, 0, totalRawTicks, 0, 0, systemCount, machineCount,
                events, Collections.<String>emptyList());
        MldDocument document = new MldDocument(
                new byte[0], "melo", 0, 0, 0, 0, 1, 0, 0,
                Collections.<Long>emptyList(), Collections.emptyList(), Collections.emptyList());
        return new NativeCompiler().compile(document, Collections.singletonList(track));
    }

    private static MachineDependentEvent md(int eventIndex, int rawTick, int... values) {
        byte[] payload = new byte[values.length];
        for (int i = 0; i < values.length; i++) payload[i] = (byte) values[i];
        return new MachineDependentEvent(0, eventIndex, 0, rawTick, 0xFF, payload);
    }


    private static SystemEvent system(int eventIndex, int rawTick, int command, int value) {
        return new SystemEvent(
                0, eventIndex, 0, rawTick, command, value,
                TrackDecoder.commandName(command), -1, -1);
    }

    private static void eq(String name, int expected, int actual) {
        if (expected != actual) fail(name, "expected " + expected + ", got " + actual);
    }

    private static void eqLong(String name, long expected, long actual) {
        if (expected != actual) fail(name, "expected " + expected + ", got " + actual);
    }

    private static void eqShorts(String name, short[] expected, short[] actual) {
        if (!Arrays.equals(expected, actual)) {
            fail(name, "expected " + Arrays.toString(expected)
                    + ", got " + Arrays.toString(actual));
        }
    }

    private static void same(String name, Object expected, Object actual) {
        if (expected != actual) fail(name, "expected " + expected + ", got " + actual);
    }

    private static void fail(String name, String detail) {
        throw new AssertionError(name + ": " + detail);
    }
}
