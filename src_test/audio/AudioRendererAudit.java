package audio;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import mld.decode.DecodedTrack;
import mld.decode.MachineDependentEvent;
import mld.decode.ResourceEvent;
import mld.decode.SystemEvent;
import mld.decode.TrackDecoder;
import mld.decode.TrackEvent;
import mld.format.MldDocument;
import mld.format.TopLevelChunk;
import mld.semantic.AudioProgram;
import mld.semantic.NativeCompiler;
import mld.semantic.NativeProgram;

/** Deterministic code/mixer/integration vectors for the verified MldEngine 0x8001 subset. */
public final class AudioRendererAudit {
    public static void main(String[] args) {
        auditVerifiedDecoderVector();
        auditMfi8001ProfileFraming();
        Sfx8001FixtureVectors.audit();
        auditVerifiedMixerVectors();
        auditVerifiedLegacyRendererWiring();
        auditActiveAdatRendererWiring();
        auditActiveAdatResourceLevelOwnership();
        auditActiveAdatLiveChannelControls();
        auditSharedLogicalSampledChannelOwners();
        auditActiveAdatUnsupportedProfilesFailClosed();
        auditMachineDurationLimit();
        auditMachineLiveControls();
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
        eqShorts("G.726 predictor vector", expected,
                MfiG726Decoder.decode4BitLittleEndian(encoded));

        eqShorts("MFiAudio G.726 reconstruction saturation", new short[] {
                88, 104, 128, 192, 260, 416, 640, 1128, 2244, 5008, 14620, 32764
        }, MfiG726Decoder.decode4BitLittleEndian(new byte[] {
                0x77, 0x77, 0x77, 0x77, 0x77, 0x77
        }));
    }

    private static void auditMfi8001ProfileFraming() {
        byte[] leftEncoded = new byte[] {0x12, 0x34};
        byte[] rightEncoded = new byte[] {0x56, 0x78};
        byte[] stereoEncoded = new byte[] {0x12, 0x34, 0x56, 0x78};
        DecodedSampledResource left = Mfi8001Decoder.decode(leftEncoded, 8000, 4, 1);
        DecodedSampledResource right = Mfi8001Decoder.decode(rightEncoded, 8000, 4, 1);
        DecodedSampledResource stereo = Mfi8001Decoder.decode(stereoEncoded, 8000, 4, 2);
        eq("stereo split frames", left.getFrameCount(), stereo.getFrameCount());
        eq("stereo right frames", right.getFrameCount(), stereo.getFrameCount());
        int[] leftSamples = left.copyInterleavedStereo();
        int[] rightSamples = right.copyInterleavedStereo();
        int[] stereoSamples = stereo.copyInterleavedStereo();
        for (int frame = 0; frame < stereo.getFrameCount(); frame++) {
            eq("stereo planar left " + frame, leftSamples[frame * 2], stereoSamples[frame * 2]);
            eq("stereo planar right " + frame, rightSamples[frame * 2], stereoSamples[frame * 2 + 1]);
        }
        eq("16k profile frame count", 8,
                Mfi8001Decoder.decode(leftEncoded, 16000, 4, 1).getFrameCount());
        eq("32k profile frame count", 4,
                Mfi8001Decoder.decode(leftEncoded, 32000, 4, 1).getFrameCount());
        DecodedSampledResource oddStereo = Mfi8001Decoder.decode(
                new byte[] {0x12, 0x34, 0x56, 0x78, (byte)0x9A}, 8000, 4, 2);
        eq("odd stereo trailing byte ignored", 16, oddStereo.getFrameCount());
    }

    private static void auditVerifiedMixerVectors() {
        eq("default gain", 16641,
                MfiAudioMixer.combinedGain(127, 127, 127, 64));
        eq("default combined pan", 64, MfiAudioMixer.combinedPan(64, 64));
        eq("default left gain", 11693,
                MfiAudioMixer.leftVoiceGain(127, 127, 127, 64, 64, 64));
        eq("default right gain", 11693,
                MfiAudioMixer.rightVoiceGain(127, 127, 127, 64, 64, 64));
        eq("low part-level gain", 32,
                MfiAudioMixer.combinedGain(127, 127, 4, 90));
        eq("low part-level left", 1,
                MfiAudioMixer.leftVoiceGain(127, 127, 4, 90, 64, 6));
        eq("low part-level right", 31,
                MfiAudioMixer.rightVoiceGain(127, 127, 4, 90, 64, 6));
        eq("fixed-point sample multiply", 6269,
                MfiAudioMixer.applyVoiceGain(12345, 16641));
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
        eq("native sample rate", 32000, renderer.nativeSampleRate(program));
        StereoPcm rendered = renderer.render(program);
        eq("stereo rate", 32000, rendered.getSampleRate());
        eq("stereo frames", 16, rendered.getFrameCount());
        eqShorts("exact 0x8001 integration vector",
                new short[] {
                        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                        0, 0, 2, 2, 4, 4, 7, 7, 9, 9, 9, 9, 9, 9, 9, 9
                },
                rendered.copyInterleavedPcm16());
    }

    private static void auditActiveAdatRendererWiring() {
        byte[] encoded = new byte[] {0x12, 0x34};
        List<TrackEvent> events = new ArrayList<TrackEvent>();
        events.add(resource(0, 0, 0x80, 0x3C));
        events.add(resource(1, 0, 0x00, 0x00, 0x3C));
        NativeProgram program = compile(activeAdatDocument(0x08, 0x04, 0x01, encoded), events, 0);
        AudioProgram.AudioAction start = null;
        for (AudioProgram.AudioAction action : program.audio.actions) {
            if (action.kind == AudioProgram.ActionKind.RESOURCE_START) start = action;
        }
        if (start == null) fail("active adat start", "missing");
        same("active adat support", AudioProgram.RendererSupport.VERIFIED_8001_4BIT, start.rendererSupport);
        eq("active adat start level", 120, start.value);
        eq("active adat source rate", 8000, start.sampleRate);
        eq("active adat coded bits", 4, start.codedBits);
        eq("active adat channels", 1, start.channelCount);

        AudioRenderer renderer = new AudioRenderer();
        if (!renderer.hasRenderableAudio(program)) fail("active adat renderable", "false");
        StereoPcm rendered = renderer.render(program);
        eq("active adat native rate", 32000, rendered.getSampleRate());
        eq("active adat frames", 16, rendered.getFrameCount());
        eqShorts("active adat exact fixture vector", new short[] {
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 1, 1, 3, 3, 5, 5, 7, 7, 7, 7, 7, 7, 7, 7
        }, rendered.copyInterleavedPcm16());
    }

    private static void auditActiveAdatResourceLevelOwnership() {
        byte[] encoded = new byte[] {0x12, 0x34};
        List<TrackEvent> events = new ArrayList<TrackEvent>();
        events.add(system(0, 0, 0xB0, 108));
        events.add(resource(1, 0, 0x80, 0x3C));
        events.add(resource(2, 0, 0x00, 0x00, 0x3C));
        NativeProgram program = compile(
                activeAdatDocument(0x08, 0x04, 0x01, encoded), events, 0);
        eq("B0 sampled resource level", 108, program.audio.resourceLevel);
        eq("B0 leaves sampled global level independent", 64, program.audio.globalSampledLevel);
        eqShorts("active adat consumes B0 as resource level", new short[] {
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 1, 1, 2, 2, 4, 4, 5, 5, 5, 5, 5, 5, 5, 5
        }, new AudioRenderer().render(program).copyInterleavedPcm16());
    }

    private static void auditActiveAdatLiveChannelControls() {
        byte[] encoded = repeatedEncoded(128);

        List<TrackEvent> baselineEvents = new ArrayList<TrackEvent>();
        baselineEvents.add(resource(0, 0, 0x80, 0x3C));
        baselineEvents.add(resource(1, 0, 0x00, 0x00, 0x3C));
        StereoPcm baseline = new AudioRenderer().render(compile(
                activeAdatDocument(0x08, 0x04, 0x01, encoded), baselineEvents, 2));

        List<TrackEvent> mutedEvents = new ArrayList<TrackEvent>();
        mutedEvents.add(resource(0, 0, 0x80, 0x3C));
        mutedEvents.add(resource(1, 0, 0x00, 0x00, 0x3C));
        mutedEvents.add(resource(2, 1, 0x80, 0x00));
        StereoPcm muted = new AudioRenderer().render(compile(
                activeAdatDocument(0x08, 0x04, 0x01, encoded), mutedEvents, 2));

        int controlFrame = controlFrame(
                compile(activeAdatDocument(0x08, 0x04, 0x01, encoded), mutedEvents, 2), 1);
        short[] basePcm = baseline.copyInterleavedPcm16();
        short[] mutedPcm = muted.copyInterleavedPcm16();
        boolean baselineHasPostControlSignal = false;
        for (int frame = controlFrame; frame < Math.min(1024, baseline.getFrameCount()); frame++) {
            int sample = frame * 2;
            if (basePcm[sample] != 0 || basePcm[sample + 1] != 0) {
                baselineHasPostControlSignal = true;
            }
            if (mutedPcm[sample] != 0 || mutedPcm[sample + 1] != 0) {
                fail("live sampled channel level", "active voice ignored post-start 7F:80 at frame " + frame);
            }
        }
        if (!baselineHasPostControlSignal) {
            fail("live sampled channel level baseline", "fixture has no post-control signal");
        }

        List<TrackEvent> pannedEvents = new ArrayList<TrackEvent>();
        pannedEvents.add(resource(0, 0, 0x80, 0x3C));
        pannedEvents.add(resource(1, 0, 0x00, 0x00, 0x3C));
        pannedEvents.add(resource(2, 1, 0x81, 0x00));
        NativeProgram pannedProgram = compile(
                activeAdatDocument(0x08, 0x04, 0x01, encoded), pannedEvents, 2);
        assertLeftSilentAfter("7F:81 updates an active sampled voice",
                new AudioRenderer().render(pannedProgram), controlFrame(pannedProgram, 1));
    }

    private static void auditSharedLogicalSampledChannelOwners() {
        MachineDependentEvent machineStart = md(1, 0,
                0x71, 0x84, 0x00, 0x45, 0x00,
                0x00, 0x00, 0x00, 0x02, 0x12, 0x34);
        List<TrackEvent> resourceControlBeforeMachine = new ArrayList<TrackEvent>();
        resourceControlBeforeMachine.add(resource(0, 0, 0x80, 0x00));
        resourceControlBeforeMachine.add(machineStart);
        assertSilent("7F:80 owns machine logical sampled channel level",
                new AudioRenderer().render(compile(resourceControlBeforeMachine, 0)));

        List<TrackEvent> resourcePanBeforeMachine = new ArrayList<TrackEvent>();
        resourcePanBeforeMachine.add(resource(0, 0, 0x81, 0x00));
        resourcePanBeforeMachine.add(machineStart);
        assertLeftSilentAfter("7F:81 owns machine logical sampled channel pan",
                new AudioRenderer().render(compile(resourcePanBeforeMachine, 0)), 0);

        byte[] encoded = new byte[] {0x12, 0x34};
        List<TrackEvent> machineControlBeforeResource = new ArrayList<TrackEvent>();
        machineControlBeforeResource.add(md(0, 0, 0x71, 0x81, 0x00));
        machineControlBeforeResource.add(resource(1, 0, 0x00, 0x00, 0x3C));
        assertSilent("71:81 owns active-adat logical sampled channel level",
                new AudioRenderer().render(compile(
                        activeAdatDocument(0x08, 0x04, 0x01, encoded),
                        machineControlBeforeResource, 0)));

        List<TrackEvent> machinePanBeforeResource = new ArrayList<TrackEvent>();
        machinePanBeforeResource.add(md(0, 0, 0x71, 0x82, 0x00));
        machinePanBeforeResource.add(resource(1, 0, 0x00, 0x00, 0x3C));
        assertLeftSilentAfter("71:82 owns active-adat logical sampled channel pan",
                new AudioRenderer().render(compile(
                        activeAdatDocument(0x08, 0x04, 0x01, encoded),
                        machinePanBeforeResource, 0)), 0);
    }

    private static void auditActiveAdatUnsupportedProfilesFailClosed() {
        List<TrackEvent> start = Collections.<TrackEvent>singletonList(
                resource(0, 0, 0x00, 0x00, 0x3C));
        NativeProgram twoBit = compile(
                activeAdatDocument(0x08, 0x02, 0x01, new byte[] {0x12, 0x34}), start, 0);
        if (new AudioRenderer().hasRenderableAudio(twoBit)) {
            fail("2-bit active adat", "renderer must remain fail-closed");
        }
        assertPrepareRejected("2-bit active adat fail-closed", twoBit);

        List<TrackEvent> routedEvents = new ArrayList<TrackEvent>();
        routedEvents.add(resource(0, 0, 0x90, 0x20));
        routedEvents.add(resource(1, 0, 0x00, 0x00, 0x3C));
        NativeProgram routed = compile(
                activeAdatDocument(0x08, 0x04, 0x01, new byte[] {0x12, 0x34}), routedEvents, 0);
        if (new AudioRenderer().hasRenderableAudio(routed)) {
            fail("route2 active adat", "non-route0 exact output must remain fail-closed");
        }
        assertPrepareRejected("route2 active adat fail-closed", routed);
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
        eq("timed render frames", 656, rendered.getFrameCount());
        short[] pcm = rendered.copyInterleavedPcm16();
        for (int i = 0; i < 1280; i++) {
            if (pcm[i] != 0) fail("timed render silence", "nonzero sample before raw-tick start");
        }
        eqShorts("voice-start resource/channel/global state",
                new short[] {0, 1, 0, 1, 0, 2, 1, 2},
                Arrays.copyOfRange(pcm, 1300, 1308));
    }


    private static void auditMachineDurationLimit() {
        byte[] encoded = new byte[] {0x12, 0x34, 0x56, 0x78, (byte)0x9A};
        NativeProgram program = compile(Collections.<TrackEvent>singletonList(
                machineStart(0, 0, 3, encoded)), 0);
        AudioProgram.AudioAction action = program.audio.actions.get(0);
        eq("machine duration byte count", 3, action.durationByteCount);
        eq("machine load keeps full body remainder", 5, action.encodedPayloadLength());
        eqLong("machine rounded duration", 1L, action.durationMs);

        AudioRenderer renderer = new AudioRenderer();
        StereoPcm rendered = renderer.render(program);
        eq("machine voice duration cap", 32, rendered.getFrameCount());
        eqLong("machine capped linear duration", 1L,
                renderer.estimateLinearDurationMillis(program));
    }

    private static void auditMachineLiveControls() {
        byte[] encoded = repeatedEncoded(128);
        MachineDependentEvent start = machineStart(0, 0, encoded.length, encoded);
        StereoPcm baseline = new AudioRenderer().render(compile(
                Collections.<TrackEvent>singletonList(start), 2));

        List<TrackEvent> mutedByChannel = new ArrayList<TrackEvent>();
        mutedByChannel.add(start);
        mutedByChannel.add(md(1, 1, 0x71, 0x81, 0x00));
        NativeProgram channelProgram = compile(mutedByChannel, 2);
        assertSilentAfter("71:81 updates an active machine voice",
                baseline, new AudioRenderer().render(channelProgram),
                controlFrame(channelProgram, 1));

        List<TrackEvent> pannedByChannel = new ArrayList<TrackEvent>();
        pannedByChannel.add(start);
        pannedByChannel.add(md(1, 1, 0x71, 0x82, 0x00));
        NativeProgram channelPanProgram = compile(pannedByChannel, 2);
        assertLeftSilentAfter("71:82 updates an active machine voice",
                new AudioRenderer().render(channelPanProgram), controlFrame(channelPanProgram, 1));

        List<TrackEvent> mutedByResource = new ArrayList<TrackEvent>();
        mutedByResource.add(start);
        mutedByResource.add(system(1, 1, 0xB0, 0));
        NativeProgram resourceProgram = compile(mutedByResource, 2);
        assertSilentAfter("B0 updates an active machine voice",
                baseline, new AudioRenderer().render(resourceProgram),
                controlFrame(resourceProgram, 1));

        List<TrackEvent> pannedByResource = new ArrayList<TrackEvent>();
        pannedByResource.add(start);
        pannedByResource.add(system(1, 1, 0xB1, 127));
        NativeProgram panProgram = compile(pannedByResource, 2);
        StereoPcm panned = new AudioRenderer().render(panProgram);
        assertRightSilentAfter("B1 updates an active machine voice",
                panned, controlFrame(panProgram, 1));
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
        same("nonzero raw control byte typed but unsupported",
                AudioProgram.RendererSupport.RECOGNIZED_UNSUPPORTED,
                program.audio.actions.get(0).rendererSupport);
        try {
            new AudioRenderer().nativeSampleRate(program);
            fail("strict renderer boundary", "expected no verified audio start");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }


    private static MachineDependentEvent machineStart(
            int eventIndex, int rawTick, int durationByteCount, byte[] encoded) {
        int[] body = new int[9 + encoded.length];
        body[0] = 0x71;
        body[1] = 0x84;
        body[2] = 0x00;
        body[3] = 0x45;
        body[4] = 0x00;
        body[5] = (durationByteCount >>> 24) & 0xFF;
        body[6] = (durationByteCount >>> 16) & 0xFF;
        body[7] = (durationByteCount >>> 8) & 0xFF;
        body[8] = durationByteCount & 0xFF;
        for (int i = 0; i < encoded.length; i++) body[9 + i] = encoded[i] & 0xFF;
        return md(eventIndex, rawTick, body);
    }

    private static byte[] repeatedEncoded(int length) {
        byte[] encoded = new byte[length];
        for (int i = 0; i < encoded.length; i++) {
            encoded[i] = (byte)((i & 1) == 0 ? 0x12 : 0x34);
        }
        return encoded;
    }

    private static int controlFrame(NativeProgram program, int rawTick) {
        return (int)AudioPlaybackSource.microsToFrameFloor(
                program.timing.rawTickToMicros(rawTick), AudioPlaybackSource.NATIVE_SAMPLE_RATE);
    }

    private static void assertLeftSilentAfter(String name, StereoPcm pcm, int firstFrame) {
        short[] samples = pcm.copyInterleavedPcm16();
        boolean rightSignal = false;
        for (int frame = firstFrame; frame < pcm.getFrameCount(); frame++) {
            int sample = frame * 2;
            if (samples[sample] != 0) fail(name, "left channel audible at frame " + frame);
            if (samples[sample + 1] != 0) rightSignal = true;
        }
        if (!rightSignal) fail(name, "fixture has no right-channel signal");
    }

    private static void assertRightSilentAfter(String name, StereoPcm pcm, int firstFrame) {
        short[] samples = pcm.copyInterleavedPcm16();
        boolean leftSignal = false;
        for (int frame = firstFrame; frame < pcm.getFrameCount(); frame++) {
            int sample = frame * 2;
            if (samples[sample] != 0) leftSignal = true;
            if (samples[sample + 1] != 0) fail(name, "right channel audible at frame " + frame);
        }
        if (!leftSignal) fail(name, "fixture has no left-channel signal");
    }

    private static void assertSilent(String name, StereoPcm pcm) {
        short[] samples = pcm.copyInterleavedPcm16();
        for (int i = 0; i < samples.length; i++) {
            if (samples[i] != 0) fail(name, "nonzero sample at index " + i);
        }
    }

    private static void assertSilentAfter(
            String name, StereoPcm baseline, StereoPcm actual, int firstFrame) {
        short[] baselinePcm = baseline.copyInterleavedPcm16();
        short[] actualPcm = actual.copyInterleavedPcm16();
        boolean baselineSignal = false;
        int endFrame = Math.min(baseline.getFrameCount(), actual.getFrameCount());
        for (int frame = firstFrame; frame < endFrame; frame++) {
            int sample = frame * 2;
            if (baselinePcm[sample] != 0 || baselinePcm[sample + 1] != 0) {
                baselineSignal = true;
            }
            if (actualPcm[sample] != 0 || actualPcm[sample + 1] != 0) {
                fail(name, "nonzero sample at frame " + frame);
            }
        }
        if (!baselineSignal) fail(name + " baseline", "fixture has no post-control signal");
    }

    private static NativeProgram compile(List<TrackEvent> events, int totalRawTicks) {
        MldDocument document = new MldDocument(
                new byte[0], "melo", 0, 0, 0, 0, 1, 0, 0,
                Collections.<Long>emptyList(), Collections.emptyList(), Collections.emptyList());
        return compile(document, events, totalRawTicks);
    }

    private static NativeProgram compile(
            MldDocument document, List<TrackEvent> events, int totalRawTicks) {
        int resourceCount = 0;
        int systemCount = 0;
        int machineCount = 0;
        for (TrackEvent event : events) {
            if (event instanceof ResourceEvent) resourceCount++;
            if (event instanceof SystemEvent) systemCount++;
            if (event instanceof MachineDependentEvent) machineCount++;
        }
        DecodedTrack track = new DecodedTrack(
                0, 0, totalRawTicks, 0, resourceCount, systemCount, machineCount,
                events, Collections.<String>emptyList());
        return new NativeCompiler().compile(document, Collections.singletonList(track));
    }

    private static MldDocument activeAdatDocument(
            int rateKHz, int codedBits, int channels, byte[] encoded) {
        byte[] payload = new byte[13 + encoded.length];
        payload[0] = 0x00;
        payload[1] = 0x0B;
        payload[2] = (byte)0x81;
        payload[3] = 0x03;
        payload[4] = 'a';
        payload[5] = 'd';
        payload[6] = 'p';
        payload[7] = 'm';
        payload[8] = 0x00;
        payload[9] = 0x03;
        payload[10] = (byte)rateKHz;
        payload[11] = (byte)codedBits;
        payload[12] = (byte)channels;
        System.arraycopy(encoded, 0, payload, 13, encoded.length);
        List<TopLevelChunk> chunks = new ArrayList<TopLevelChunk>();
        chunks.add(new TopLevelChunk(
                "ainf", 13, 1, 2, "header", new byte[] {0x01}, null));
        chunks.add(new TopLevelChunk(
                "adat", 42, payload.length, 4, "resource", payload, null));
        return new MldDocument(
                new byte[0], "melo", 0, 32, 0, 0, 1, 0, 0,
                Collections.<Long>emptyList(), chunks, Collections.emptyList());
    }

    private static MachineDependentEvent md(int eventIndex, int rawTick, int... values) {
        byte[] payload = new byte[values.length];
        for (int i = 0; i < values.length; i++) payload[i] = (byte) values[i];
        return new MachineDependentEvent(0, eventIndex, 0, rawTick, 0xFF, payload);
    }


    private static ResourceEvent resource(
            int eventIndex, int rawTick, int command, int... values) {
        byte[] body = new byte[values.length];
        for (int i = 0; i < values.length; i++) body[i] = (byte)values[i];
        return new ResourceEvent(
                0, eventIndex, 0, rawTick, command,
                TrackDecoder.resourceName(command), body, command >= 0xF0);
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
