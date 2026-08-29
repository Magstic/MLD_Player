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
        auditMfi8002ProfileAndRendererWiring();
        auditMfi8002ControlClosure();
        auditNativeVoicePoolLimit();
        Sfx8001FixtureVectors.audit();
        auditVerifiedMixerVectors();
        auditVerifiedLegacyRendererWiring();
        auditActiveAdatRendererWiring();
        auditActiveAdatResourceStop();
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

    private static void auditMfi8002ProfileAndRendererWiring() {
        eqShorts("AWC2 low-nibble-first predictor vector", new short[] {236, 274},
                Mfi8002Decoder.decodeAwc2Mono(new byte[] {0x07}));
        eqShorts("AWC2 stereo nibble ownership and native zero tail", new short[] {
                15, 236, -221, 198, 0, 0, 0, 0
        }, Mfi8002Decoder.decodeAwc2Stereo(new byte[] {0x70, (byte)0x8F}));
        eqShorts("AWC2 stereo odd-tail is capped at one frame per byte",
                new short[] {15, 236, 0, 0},
                Mfi8002Decoder.decodeAwc2Stereo(new byte[] {0x70}));

        byte[] nativeMonoProbe = nativeAwc2ProbeEncoded(81);
        eqPrefixShorts("AWC2 native mono export prefix", new short[] {
                236, 274, 308, 766, -333, -508, -350, -209,
                -2118, -6700, 4288, 6045, 7623, 28896, -17184, -20256
        }, Mfi8002Decoder.decodeAwc2Mono(nativeMonoProbe));
        byte[] nativeStereoProbe = nativeAwc2ProbeEncoded(163);
        short[] nativeStereoDecoded = Mfi8002Decoder.decodeAwc2Stereo(nativeStereoProbe);
        eqPrefixShorts("AWC2 native stereo export prefix", new short[] {
                236, 15, 274, 251, -237, 213, -156, 247,
                -1255, -211, 1381, -138, 1802, 848, -3879, 691
        }, nativeStereoDecoded);
        assertShortZeroRange("AWC2 native odd stereo zero tail",
                nativeStereoDecoded, 163 * 2, nativeStereoDecoded.length);

        int[] standardRates = {4000, 8000, 16000, 32000};
        for (int rate : standardRates) {
            if (!Mfi8002Decoder.supports(rate, 4, 1)
                    || !Mfi8002Decoder.supports(rate, 4, 2)) {
                fail("0x8002 standard profile support", "rate=" + rate);
            }
        }
        if (Mfi8002Decoder.supports(11025, 4, 1)) {
            fail("0x8002 non-standard rate", "unexpectedly supported");
        }
        assert8002DecodeRejected("0x8002 mono 80-byte native minimum", new byte[80], 1);
        assert8002DecodeRejected("0x8002 stereo 161-byte native minimum", new byte[161], 2);

        byte[] stereoMinimum = new byte[162];
        stereoMinimum[0] = 0x70;
        DecodedSampledResource stereo16kVector = Mfi8002Decoder.decode(
                stereoMinimum, 16000, 4, 2);
        eq("0x8002 16k stereo FIR frame count", 648, stereo16kVector.getFrameCount());
        eqPrefixInts("0x8002 16k stereo independent FIR vector", new int[] {
                0, 0, -4, -52, 0, 0, 36, 618
        }, stereo16kVector.copyInterleavedStereo());

        byte[] monoMinimum = new byte[81];
        DecodedSampledResource decoded4k = Mfi8002Decoder.decode(
                monoMinimum, 4000, 4, 1);
        eq("0x8002 4k FIR frame count", 1296, decoded4k.getFrameCount());
        eqPrefixInts("0x8002 native 4k-to-32k FIR vector", new int[] {
                0, 0, -1, -1, -1, -1, -2, -2, -4, -4, -5, -5, -6, -6, -4, -4,
                0, 0, 7, 7, 16, 16, 27, 27, 36, 36, 41, 41, 38, 38, 24, 24
        }, decoded4k.copyInterleavedStereo());

        DecodedSampledResource decoded8k = Mfi8002Decoder.decode(
                monoMinimum, 8000, 4, 1);
        eq("0x8002 8k FIR frame count", 648, decoded8k.getFrameCount());
        eqPrefixInts("0x8002 native 8k-to-32k FIR vector", new int[] {
                0, 0, -1, -1, -4, -4, -6, -6,
                0, 0, 16, 16, 36, 36, 38, 38
        }, decoded8k.copyInterleavedStereo());

        DecodedSampledResource nativeMono8k = Mfi8002Decoder.decode(
                nativeMonoProbe, 8000, 4, 1);
        eqPrefixInts("original MFiAudio mono AWC2/FIR cache prefix", new int[] {
                0, 0, -15, -15, -52, -52, -82, -82,
                0, 0, 270, 270, 618, 618, 669, 669,
                0, 0, -1423, -1423, -2853, -2853, -2860, -2860
        }, nativeMono8k.copyInterleavedStereo());

        DecodedSampledResource nativeStereo8k = Mfi8002Decoder.decode(
                nativeStereoProbe, 8000, 4, 2);
        eqPrefixInts("original MFiAudio stereo AWC2/FIR cache prefix", new int[] {
                0, 0, -15, -1, -52, -4, -82, -6,
                0, 0, 270, 2, 618, -12, 669, -38,
                0, 0, -1389, 182, -2734, 447, -2672, 507
        }, nativeStereo8k.copyInterleavedStereo());

        byte[] randomMonoProbe = nativeAwc2RandomProbeEncoded(81);
        byte[] randomStereoProbe = nativeAwc2RandomProbeEncoded(163);
        int[] nativeMonoHashes = {1360533651, 1045531234, 1827661998, -65197875};
        int[] nativeStereoHashes = {-575504625, -419919779, -1284633272, -1660909899};
        for (int i = 0; i < standardRates.length; i++) {
            int rate = standardRates[i];
            eq("original MFiAudio mono decoded-cache hash " + rate,
                    nativeMonoHashes[i], nativeCacheHash(
                            Mfi8002Decoder.decode(randomMonoProbe, rate, 4, 1), 1));
            eq("original MFiAudio stereo decoded-cache hash " + rate,
                    nativeStereoHashes[i], nativeCacheHash(
                            Mfi8002Decoder.decode(randomStereoProbe, rate, 4, 2), 2));
        }

        DecodedSampledResource decoded16k = Mfi8002Decoder.decode(
                monoMinimum, 16000, 4, 1);
        eq("0x8002 16k FIR frame count", 324, decoded16k.getFrameCount());
        eqPrefixInts("0x8002 native 16k-to-32k FIR vector", new int[] {
                0, 0, -4, -4, 0, 0, 36, 36
        }, decoded16k.copyInterleavedStereo());

        DecodedSampledResource decoded32k = Mfi8002Decoder.decode(
                monoMinimum, 32000, 4, 1);
        eq("0x8002 32k FIR frame count", 162, decoded32k.getFrameCount());
        eqPrefixInts("0x8002 native 32k FIR phase-0 vector", new int[] {
                0, 0, 0, 0, 0, 0, 0, 0,
                1920, 1920, 3840, 3840, 5760, 5760, 7680, 7680
        }, decoded32k.copyInterleavedStereo());

        byte[] encoded = repeatedEncoded(512);
        List<TrackEvent> events = new ArrayList<TrackEvent>();
        events.add(compact8002Load(0, 0, 0, 0x01, 8000, encoded));
        events.add(compact8002Control(1, 0, 0x03, 0, 127));
        events.add(compact8002Control(2, 1, 0x05, 0));
        events.add(compact8002Control(3, 2, 0x03, 0, 127));
        NativeProgram program = compile(events, 2);

        AudioProgram.AudioAction load = program.audio.actions.get(0);
        same("0x8002 renderer support",
                AudioProgram.RendererSupport.VERIFIED_8002_AWC2_4BIT,
                load.rendererSupport);
        same("0x8002 type", AudioProgram.AudioType.MFI_8002, load.audioType);
        eq("0x8002 source rate", 8000, load.sampleRate);
        eq("0x8002 coded bits", 4, load.codedBits);
        eq("0x8002 channels", 1, load.channelCount);
        eq("0x8002 encoded bytes", encoded.length, load.encodedPayloadLength());
        if (!load.phaseGated) fail("0x8002 phase gate", "0x400 lost its runtime phase gate");

        AudioRenderer renderer = new AudioRenderer();
        if (!renderer.hasRenderableAudio(program)) fail("0x8002 renderer", "not renderable");
        StereoPcm rendered = renderer.render(program);
        int stopFrame = controlFrame(program, 1);
        int restartFrame = controlFrame(program, 2);
        assertSignalInRange("0x8002 first start", rendered, 0, stopFrame);
        assertSilentRange("0x8002 slot stop", rendered, stopFrame, restartFrame);
        assertSignalInRange("0x8002 cached-slot restart", rendered,
                restartFrame, Math.min(rendered.getFrameCount(), restartFrame + 1024));

        List<TrackEvent> stereoEvents = new ArrayList<TrackEvent>();
        stereoEvents.add(compact8002Load(0, 0, 0, 0x81, 16000, encoded));
        stereoEvents.add(compact8002Control(1, 0, 0x03, 0, 127));
        NativeProgram stereo16k = compile(stereoEvents, 0);
        same("0x8002 16k stereo support",
                AudioProgram.RendererSupport.VERIFIED_8002_AWC2_4BIT,
                stereo16k.audio.actions.get(0).rendererSupport);
        eq("0x8002 16k stereo channels", 2, stereo16k.audio.actions.get(0).channelCount);
        StereoPcm renderedStereo = new AudioRenderer().render(stereo16k);
        eq("0x8002 16k stereo duration frame count", 2048, renderedStereo.getFrameCount());
        assertSignalInRange("0x8002 16k stereo signal", renderedStereo, 0, 1024);

        NativeProgram unsupported = compile(Collections.<TrackEvent>singletonList(
                compact8002Load(0, 0, 0, 0x01, 11025, encoded)), 0);
        same("0x8002 non-standard rate remains typed only",
                AudioProgram.RendererSupport.RECOGNIZED_UNSUPPORTED,
                unsupported.audio.actions.get(0).rendererSupport);
        assertDiagnostic("AUDIO_RENDERER_8002_PROFILE_UNSUPPORTED", unsupported);

        NativeProgram unloadedStart = compile(Collections.<TrackEvent>singletonList(
                compact8002Control(0, 0, 0x03, 0, 127)), 0);
        if (new AudioRenderer().hasRenderableAudio(unloadedStart)) {
            fail("0x8002 unloaded start", "renderer reported a missing cached slot as playable");
        }
        assertPrepareRejected("0x8002 unloaded start fail-closed", unloadedStart);
    }

    private static void auditMfi8002ControlClosure() {
        byte[] encoded = repeatedEncoded(512);

        List<List<TrackEvent>> crossTrack = emptyTrackEvents(3);
        crossTrack.get(0).add(compact8002LoadOnTrack(0, 0, 0, 0, 0x01, 8000, encoded));
        crossTrack.get(2).add(compact8002ControlOnTrack(2, 0, 1, 0x83, 0, 127));
        crossTrack.get(2).add(compact8002ControlOnTrack(2, 1, 2, 0x85, 0));
        NativeProgram crossTrackProgram = compileTracks(crossTrack, 2);
        AudioProgram.AudioAction crossTrackStart = crossTrackProgram.audio.actions.get(1);
        eq("compact channel comes from command high2", 2, crossTrackStart.logicalChannel);
        if (!new AudioRenderer().hasRenderableAudio(crossTrackProgram)) {
            fail("cross-track compact slot", "track-0 load was not visible to track-2 start");
        }
        StereoPcm crossTrackPcm = new AudioRenderer().render(crossTrackProgram);
        int crossTrackStop = controlFrame(crossTrackProgram, 2);
        assertSignalInRange("cross-track compact start", crossTrackPcm, 0, crossTrackStop);
        assertSilentRange("cross-track compact stop", crossTrackPcm, crossTrackStop, crossTrackPcm.getFrameCount());

        List<TrackEvent> reload = new ArrayList<TrackEvent>();
        reload.add(compact8002Load(0, 0, 0, 0x01, 8000, encoded));
        reload.add(compact8002Control(1, 0, 0x03, 0, 127));
        reload.add(compact8002Control(2, 0, 0x43, 0, 127));
        reload.add(compact8002Load(3, 1, 0, 0x01, 8000, encoded));
        reload.add(compact8002Control(4, 2, 0x03, 0, 127));
        NativeProgram reloadProgram = compile(reload, 3);
        StereoPcm reloadPcm = new AudioRenderer().render(reloadProgram);
        int reloadFrame = controlFrame(reloadProgram, 1);
        int restartFrame = controlFrame(reloadProgram, 2);
        assertSilentRange("0x8002 reload releases all slot voices", reloadPcm, reloadFrame, restartFrame);
        assertSignalInRange("0x8002 reload replacement restarts", reloadPcm, restartFrame, reloadPcm.getFrameCount());

        List<TrackEvent> mixed = new ArrayList<TrackEvent>();
        mixed.add(mixed8002Load(0, 0, 0, 0x01, 8000, encoded));
        mixed.add(mixed8002Control(1, 0, 0x49, 0, 127));
        mixed.add(mixed8002Control(2, 1, 0x4B, 0, 0));
        mixed.add(mixed8002Control(3, 2, 0x4A, 0));
        NativeProgram mixedProgram = compile(mixed, 3);
        if (!new AudioRenderer().hasRenderableAudio(mixedProgram)) {
            fail("0x402 aliases", "mixed-dispatch backend aliases were not renderable");
        }
        StereoPcm mixedPcm = new AudioRenderer().render(mixedProgram);
        int panFrame = controlFrame(mixedProgram, 1);
        int stopFrame = controlFrame(mixedProgram, 2);
        assertLeftSilentAfter("0x402 pan alias", mixedPcm, panFrame);
        assertSilentRange("0x402 stop alias", mixedPcm, stopFrame, mixedPcm.getFrameCount());

        NativeProgram emptyLoad = compile(Collections.<TrackEvent>singletonList(
                compact8002Load(0, 0, 0, 0x01, 8000, new byte[0])), 0);
        same("empty AWC2 load remains unsupported",
                AudioProgram.RendererSupport.RECOGNIZED_UNSUPPORTED,
                emptyLoad.audio.actions.get(0).rendererSupport);

        NativeProgram shortMonoLoad = compile(Collections.<TrackEvent>singletonList(
                compact8002Load(0, 0, 0, 0x01, 8000, new byte[80])), 0);
        same("80-byte mono AWC2 load remains unsupported",
                AudioProgram.RendererSupport.RECOGNIZED_UNSUPPORTED,
                shortMonoLoad.audio.actions.get(0).rendererSupport);

        NativeProgram shortStereoLoad = compile(Collections.<TrackEvent>singletonList(
                compact8002Load(0, 0, 0, 0x81, 8000, new byte[161])), 0);
        same("161-byte stereo AWC2 load remains unsupported",
                AudioProgram.RendererSupport.RECOGNIZED_UNSUPPORTED,
                shortStereoLoad.audio.actions.get(0).rendererSupport);

        NativeProgram outOfRangeSlot = compile(Collections.<TrackEvent>singletonList(
                compact8002Load(0, 0, 64, 0x01, 8000, encoded)), 0);
        same("0x8002 slot 64 remains unsupported",
                AudioProgram.RendererSupport.RECOGNIZED_UNSUPPORTED,
                outOfRangeSlot.audio.actions.get(0).rendererSupport);

        byte[] machineEncoded = repeatedEncoded(512);
        List<TrackEvent> machineThen8002Reload = new ArrayList<TrackEvent>();
        machineThen8002Reload.add(machineStart(0, 0, machineEncoded.length, machineEncoded));
        machineThen8002Reload.add(compact8002Load(1, 1, 0, 0x01, 8000, encoded));
        NativeProgram machineThen8002Program = compile(machineThen8002Reload, 3);
        StereoPcm machineThen8002Pcm = new AudioRenderer().render(machineThen8002Program);
        int crossCodecReloadFrame = controlFrame(machineThen8002Program, 1);
        assertSignalInRange("0x8001 voice before 0x8002 reload",
                machineThen8002Pcm, 0, crossCodecReloadFrame);
        assertSilentRange("0x8002 load releases active 0x8001 voice on the same slot",
                machineThen8002Pcm, crossCodecReloadFrame, machineThen8002Pcm.getFrameCount());

        List<List<TrackEvent>> machineThenCompactStop = emptyTrackEvents(3);
        machineThenCompactStop.get(2).add(machineStartOnTrack(
                2, 0, 0, machineEncoded.length, machineEncoded));
        machineThenCompactStop.get(0).add(compact8002ControlOnTrack(0, 0, 1, 0x05, 0));
        NativeProgram machineThenStopProgram = compileTracks(machineThenCompactStop, 3);
        AudioProgram.AudioAction crossTrackMachineStart = machineThenStopProgram.audio.actions.get(0);
        eq("0x106 channel comes from command high2", 0, crossTrackMachineStart.logicalChannel);
        if (!new AudioRenderer().hasRenderableAudio(machineThenStopProgram)) {
            fail("generic compact slot stop", "verified 0x8001 voice made compact stop fail-closed");
        }
        StereoPcm machineThenStopPcm = new AudioRenderer().render(machineThenStopProgram);
        int genericStopFrame = controlFrame(machineThenStopProgram, 1);
        assertSignalInRange("cross-track 0x8001 voice before compact stop",
                machineThenStopPcm, 0, genericStopFrame);
        assertSilentRange("compact stop uses codec-agnostic channel+slot identity",
                machineThenStopPcm, genericStopFrame, machineThenStopPcm.getFrameCount());

        List<TrackEvent> awcBaseline = new ArrayList<TrackEvent>();
        awcBaseline.add(compact8002Load(0, 0, 0, 0x01, 8000, encoded));
        awcBaseline.add(compact8002Control(1, 0, 0x43, 0, 127));
        StereoPcm awcBaselinePcm = new AudioRenderer().render(compile(awcBaseline, 4));

        List<TrackEvent> awcThenFirstMachine = new ArrayList<TrackEvent>(awcBaseline);
        awcThenFirstMachine.add(machineStart(2, 1, machineEncoded.length, machineEncoded));
        NativeProgram awcThenFirstMachineProgram = compile(awcThenFirstMachine, 4);
        StereoPcm awcThenFirstMachinePcm = new AudioRenderer().render(awcThenFirstMachineProgram);
        eqShorts("first raw-0 0x106 consumes 0x400 cached state without replacing 0x8002",
                awcBaselinePcm.copyInterleavedPcm16(),
                awcThenFirstMachinePcm.copyInterleavedPcm16());

        List<TrackEvent> awcWithGeneralPendingAttempts = new ArrayList<TrackEvent>(awcBaseline);
        awcWithGeneralPendingAttempts.add(inlineMachineWithControl(
                2, 1, 0x44, 0x01, machineEncoded));
        awcWithGeneralPendingAttempts.add(machineStartWithControl(
                3, 2, 0x85, 0x00, machineEncoded.length, machineEncoded));
        NativeProgram generalPendingProgram = compile(awcWithGeneralPendingAttempts, 4);
        if (!new AudioRenderer().hasRenderableAudio(generalPendingProgram)) {
            fail("general 0x106 pending override",
                    "native type-mismatch no-op was incorrectly treated as unsupported execution");
        }
        eqShorts("pending raw-1 2-bit and raw-0 op2 attempts preserve 0x8002 PCM",
                awcBaselinePcm.copyInterleavedPcm16(),
                new AudioRenderer().render(generalPendingProgram).copyInterleavedPcm16());

        List<TrackEvent> suppressedOnly = new ArrayList<TrackEvent>();
        suppressedOnly.add(compact8002Load(0, 0, 0, 0x01, 8000, encoded));
        suppressedOnly.add(machineStart(1, 1, machineEncoded.length, machineEncoded));
        NativeProgram suppressedOnlyProgram = compile(suppressedOnly, 2);
        if (new AudioRenderer().hasRenderableAudio(suppressedOnlyProgram)) {
            fail("0x400 cached-state machine suppression",
                    "first raw-0 0x106 was incorrectly reported as an audible start");
        }

        List<TrackEvent> awcThenSecondMachine = new ArrayList<TrackEvent>(awcThenFirstMachine);
        awcThenSecondMachine.add(machineStart(3, 2, machineEncoded.length, machineEncoded));
        NativeProgram awcThenSecondMachineProgram = compile(awcThenSecondMachine, 4);
        StereoPcm awcThenSecondMachinePcm = new AudioRenderer().render(awcThenSecondMachineProgram);

        List<TrackEvent> machineOnly = new ArrayList<TrackEvent>();
        machineOnly.add(machineStart(0, 2, machineEncoded.length, machineEncoded));
        NativeProgram machineOnlyProgram = compile(machineOnly, 4);
        StereoPcm machineOnlyPcm = new AudioRenderer().render(machineOnlyProgram);
        int secondMachineFrame = controlFrame(awcThenSecondMachineProgram, 2);
        assertPcmEqualsFrom("second raw-0 0x106 releases 0x8002 and starts 0x8001",
                machineOnlyPcm, awcThenSecondMachinePcm, secondMachineFrame);

        List<TrackEvent> compactRestart8001 = new ArrayList<TrackEvent>(awcThenSecondMachine);
        compactRestart8001.add(compact8002Control(4, 3, 0x05, 0));
        compactRestart8001.add(compact8002Control(5, 4, 0x03, 0, 127));
        NativeProgram compactRestart8001Program = compile(compactRestart8001, 6);
        if (!new AudioRenderer().hasRenderableAudio(compactRestart8001Program)) {
            fail("compact restart of cached 0x8001", "generic MFiAudio slot start was rejected");
        }
        StereoPcm compactRestart8001Pcm = new AudioRenderer().render(compactRestart8001Program);
        int compactStopFrame = controlFrame(compactRestart8001Program, 3);
        int compactRestartFrame = controlFrame(compactRestart8001Program, 4);
        assertSilentRange("compact stop halts cached 0x8001 before restart",
                compactRestart8001Pcm, compactStopFrame, compactRestartFrame);
        assertSignalInRange("compact start restarts cached 0x8001 with shared slot duration",
                compactRestart8001Pcm, compactRestartFrame,
                Math.min(compactRestart8001Pcm.getFrameCount(), compactRestartFrame + 1024));

    }

    private static void auditNativeVoicePoolLimit() {
        byte[] encoded = repeatedEncoded(512);
        List<TrackEvent> events = new ArrayList<TrackEvent>();
        events.add(compact8002Load(0, 0, 0, 0x01, 8000, encoded));
        for (int i = 0; i < 17; i++) {
            events.add(compact8002Control(
                    i + 1, 0, 0x03, 0, i == 0 ? 127 : 0));
        }
        StereoPcm rendered = new AudioRenderer().render(compile(events, 0));
        assertSilent(
                "MFiAudio seventeenth start steals the oldest of sixteen voices", rendered);
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

    private static void auditActiveAdatResourceStop() {
        byte[] encoded = repeatedEncoded(128);
        List<TrackEvent> baselineEvents = new ArrayList<TrackEvent>();
        baselineEvents.add(resource(0, 0, 0x80, 0x3C));
        baselineEvents.add(resource(1, 0, 0x00, 0x00, 0x3C));
        NativeProgram baselineProgram = compile(
                activeAdatDocument(0x08, 0x04, 0x01, encoded), baselineEvents, 2);
        StereoPcm baseline = new AudioRenderer().render(baselineProgram);

        List<TrackEvent> stoppedEvents = new ArrayList<TrackEvent>(baselineEvents);
        stoppedEvents.add(resource(2, 1, 0x01, 0x00));
        NativeProgram stoppedProgram = compile(
                activeAdatDocument(0x08, 0x04, 0x01, encoded), stoppedEvents, 2);
        AudioRenderer renderer = new AudioRenderer();
        if (!renderer.hasRenderableAudio(stoppedProgram)) {
            fail("active adat stop renderable", "false");
        }
        int stopFrame = controlFrame(stoppedProgram, 1);
        assertSilentAfter("7F:01 stops the matching active resource voice",
                baseline, renderer.render(stoppedProgram), stopFrame);
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

        List<List<TrackEvent>> crossTrackMachineLevel = emptyTrackEvents(3);
        crossTrackMachineLevel.get(0).add(machineStartOnTrack(
                0, 0, 0, 128, repeatedEncoded(128)));
        crossTrackMachineLevel.get(2).add(mdOnTrack(2, 0, 1, 0x71, 0x81, 0x00));
        NativeProgram crossTrackMachineLevelProgram = compileTracks(crossTrackMachineLevel, 2);
        AudioProgram.AudioAction crossTrackLevel = crossTrackMachineLevelProgram.audio.actions.get(1);
        eq("71:81 channel comes from packed high2", 0, crossTrackLevel.logicalChannel);
        StereoPcm crossTrackMachineLevelPcm = new AudioRenderer().render(crossTrackMachineLevelProgram);
        assertSilentRange("cross-track 71:81 updates native machine channel 0",
                crossTrackMachineLevelPcm, controlFrame(crossTrackMachineLevelProgram, 1),
                crossTrackMachineLevelPcm.getFrameCount());

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

        List<TrackEvent> validPan = new ArrayList<TrackEvent>();
        validPan.add(md(0, 0, 0x11, 0x01, 0xF1, 0x06, 0x00, 0x00));
        validPan.add(start);
        StereoPcm panned = new AudioRenderer().render(compile(validPan, 0));
        assertLeftSilentAfter("compact native pan applies to sampled voice", panned, 0);

        List<TrackEvent> invalidPan = new ArrayList<TrackEvent>();
        invalidPan.add(md(0, 0, 0x11, 0x01, 0xF1, 0x06, 0x00, 0x40));
        invalidPan.add(start);
        NativeProgram invalidProgram = compile(invalidPan, 0);
        eq("invalid compact pan is semantic no-op", 1, invalidProgram.audio.actions.size());
        eqShorts("invalid compact pan leaves PCM unchanged",
                baseline.copyInterleavedPcm16(),
                new AudioRenderer().render(invalidProgram).copyInterleavedPcm16());
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

        List<TrackEvent> forcedOp3Load = new ArrayList<TrackEvent>();
        forcedOp3Load.add(machineStartWithControl(
                0, 0, 0xC5, 0x01, 2, new byte[] {0x12, 0x34}));
        forcedOp3Load.add(compact8002Control(1, 1, 0x03, 0, 127));
        NativeProgram forcedOp3Program = compile(forcedOp3Load, 1);
        assertDiagnostic("AUDIO_RENDERER_8001_PROFILE_UNSUPPORTED", forcedOp3Program);
        assertPrepareRejected(
                "raw-1 incoming op3 forces unsupported 0x8001 load", forcedOp3Program);

        List<TrackEvent> unverifiedControl = new ArrayList<TrackEvent>();
        unverifiedControl.add(compact8002Load(
                0, 0, 0, 0x00, 8000, new byte[] {0x12, 0x34}));
        unverifiedControl.add(compact8002Control(1, 0, 0x03, 0x00, 0x7F));
        unverifiedControl.add(md(2, 1, 0x21, 0x82, 0x10));
        NativeProgram unverifiedPan = compile(unverifiedControl, 1);
        if (new AudioRenderer().hasRenderableAudio(unverifiedPan)) {
            fail("unverified machine pan", "legacy handler 0x001 was silently accepted");
        }
        assertPrepareRejected("unverified machine pan fail-closed", unverifiedPan);
    }


    private static MachineDependentEvent compact8002Load(
            int eventIndex, int rawTick, int slot, int control, int sampleRate, byte[] encoded) {
        return compact8002LoadOnTrack(0, eventIndex, rawTick, slot, control, sampleRate, encoded);
    }

    private static MachineDependentEvent compact8002LoadOnTrack(
            int trackIndex, int eventIndex, int rawTick, int slot, int control,
            int sampleRate, byte[] encoded) {
        int[] body = new int[8 + encoded.length];
        body[0] = 0x11;
        body[1] = 0x01;
        body[2] = 0xF0;
        body[3] = 0x07;
        body[4] = slot;
        body[5] = control;
        body[6] = (sampleRate >>> 8) & 0xFF;
        body[7] = sampleRate & 0xFF;
        for (int i = 0; i < encoded.length; i++) body[8 + i] = encoded[i] & 0xFF;
        return mdOnTrack(trackIndex, eventIndex, rawTick, body);
    }

    private static MachineDependentEvent compact8002Control(
            int eventIndex, int rawTick, int command, int... operands) {
        return compact8002ControlOnTrack(0, eventIndex, rawTick, command, operands);
    }

    private static MachineDependentEvent compact8002ControlOnTrack(
            int trackIndex, int eventIndex, int rawTick, int command, int... operands) {
        int[] body = new int[4 + operands.length];
        body[0] = 0x11;
        body[1] = 0x01;
        body[2] = 0xF1;
        body[3] = command;
        for (int i = 0; i < operands.length; i++) body[4 + i] = operands[i];
        return mdOnTrack(trackIndex, eventIndex, rawTick, body);
    }

    private static MachineDependentEvent mixed8002Load(
            int eventIndex, int rawTick, int slot, int control, int sampleRate, byte[] encoded) {
        int[] body = new int[7 + encoded.length];
        body[0] = 0x31;
        body[1] = 0x10;
        body[2] = 0x07;
        body[3] = slot;
        body[4] = control;
        body[5] = (sampleRate >>> 8) & 0xFF;
        body[6] = sampleRate & 0xFF;
        for (int i = 0; i < encoded.length; i++) body[7 + i] = encoded[i] & 0xFF;
        return md(eventIndex, rawTick, body);
    }

    private static MachineDependentEvent mixed8002Control(
            int eventIndex, int rawTick, int command, int... operands) {
        int[] body = new int[3 + operands.length];
        body[0] = 0x31;
        body[1] = 0x10;
        body[2] = command;
        for (int i = 0; i < operands.length; i++) body[3 + i] = operands[i];
        return md(eventIndex, rawTick, body);
    }

    private static MachineDependentEvent machineStart(
            int eventIndex, int rawTick, int durationByteCount, byte[] encoded) {
        return machineStartOnTrack(0, eventIndex, rawTick, durationByteCount, encoded);
    }

    private static MachineDependentEvent machineStartWithControl(
            int eventIndex,
            int rawTick,
            int opFormat,
            int control,
            int durationByteCount,
            byte[] encoded) {
        int[] body = new int[9 + encoded.length];
        body[0] = 0x71;
        body[1] = 0x84;
        body[2] = 0x00;
        body[3] = opFormat;
        body[4] = control;
        body[5] = (durationByteCount >>> 24) & 0xFF;
        body[6] = (durationByteCount >>> 16) & 0xFF;
        body[7] = (durationByteCount >>> 8) & 0xFF;
        body[8] = durationByteCount & 0xFF;
        for (int i = 0; i < encoded.length; i++) body[9 + i] = encoded[i] & 0xFF;
        return md(eventIndex, rawTick, body);
    }

    private static MachineDependentEvent inlineMachineWithControl(
            int eventIndex, int rawTick, int opFormat, int control, byte[] encoded) {
        int[] body = new int[5 + encoded.length];
        body[0] = 0x61;
        body[1] = 0x83;
        body[2] = 0x00;
        body[3] = opFormat;
        body[4] = control;
        for (int i = 0; i < encoded.length; i++) body[5 + i] = encoded[i] & 0xFF;
        return md(eventIndex, rawTick, body);
    }

    private static MachineDependentEvent machineStartOnTrack(
            int trackIndex, int eventIndex, int rawTick, int durationByteCount, byte[] encoded) {
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
        return mdOnTrack(trackIndex, eventIndex, rawTick, body);
    }

    private static byte[] repeatedEncoded(int length) {
        byte[] encoded = new byte[length];
        for (int i = 0; i < encoded.length; i++) {
            encoded[i] = (byte)((i & 1) == 0 ? 0x12 : 0x34);
        }
        return encoded;
    }

    private static byte[] nativeAwc2ProbeEncoded(int length) {
        byte[] seed = new byte[] {0x07, 0x70, (byte)0x8F, 0x00, (byte)0xFF};
        byte[] encoded = new byte[length];
        for (int i = 0; i < encoded.length; i++) encoded[i] = seed[i % seed.length];
        return encoded;
    }

    private static byte[] nativeAwc2RandomProbeEncoded(int length) {
        byte[] encoded = new byte[length];
        int state = 0x6D2B79F5;
        for (int i = 0; i < encoded.length; i++) {
            state ^= state << 13;
            state ^= state >>> 17;
            state ^= state << 5;
            encoded[i] = (byte)state;
        }
        return encoded;
    }

    private static int nativeCacheHash(DecodedSampledResource decoded, int channels) {
        int hash = 0x811C9DC5;
        int[] stereo = decoded.copyInterleavedStereo();
        for (int i = 0; i < stereo.length; i += channels == 1 ? 2 : 1) {
            hash = (hash ^ stereo[i]) * 16777619;
        }
        return hash;
    }

    private static int controlFrame(NativeProgram program, int rawTick) {
        return (int)AudioPlaybackSource.microsToFrameFloor(
                program.timing.rawTickToMicros(rawTick), AudioPlaybackSource.NATIVE_SAMPLE_RATE);
    }

    private static void assertPcmEqualsFrom(
            String name, StereoPcm expected, StereoPcm actual, int firstFrame) {
        short[] expectedSamples = expected.copyInterleavedPcm16();
        short[] actualSamples = actual.copyInterleavedPcm16();
        int endFrame = Math.min(expected.getFrameCount(), actual.getFrameCount());
        for (int frame = firstFrame; frame < endFrame; frame++) {
            int sample = frame * 2;
            if (expectedSamples[sample] != actualSamples[sample]
                    || expectedSamples[sample + 1] != actualSamples[sample + 1]) {
                fail(name, "PCM mismatch at frame " + frame);
            }
        }
    }

    private static void assertSignalInRange(
            String name, StereoPcm pcm, int firstFrame, int endFrame) {
        short[] samples = pcm.copyInterleavedPcm16();
        for (int frame = Math.max(0, firstFrame); frame < Math.min(endFrame, pcm.getFrameCount()); frame++) {
            int sample = frame * 2;
            if (samples[sample] != 0 || samples[sample + 1] != 0) return;
        }
        fail(name, "no nonzero sample in frame range");
    }

    private static void assertSilentRange(
            String name, StereoPcm pcm, int firstFrame, int endFrame) {
        short[] samples = pcm.copyInterleavedPcm16();
        for (int frame = Math.max(0, firstFrame); frame < Math.min(endFrame, pcm.getFrameCount()); frame++) {
            int sample = frame * 2;
            if (samples[sample] != 0 || samples[sample + 1] != 0) {
                fail(name, "nonzero sample at frame " + frame);
            }
        }
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

    private static NativeProgram compileTracks(
            List<List<TrackEvent>> trackEvents, int totalRawTicks) {
        MldDocument document = new MldDocument(
                new byte[0], "melo", 0, 0, 0, 0, trackEvents.size(), 0, 0,
                Collections.<Long>emptyList(), Collections.emptyList(), Collections.emptyList());
        List<DecodedTrack> tracks = new ArrayList<DecodedTrack>();
        for (int trackIndex = 0; trackIndex < trackEvents.size(); trackIndex++) {
            List<TrackEvent> events = trackEvents.get(trackIndex);
            int resourceCount = 0;
            int systemCount = 0;
            int machineCount = 0;
            for (TrackEvent event : events) {
                if (event instanceof ResourceEvent) resourceCount++;
                if (event instanceof SystemEvent) systemCount++;
                if (event instanceof MachineDependentEvent) machineCount++;
            }
            tracks.add(new DecodedTrack(
                    trackIndex, 0, totalRawTicks, 0, resourceCount, systemCount, machineCount,
                    events, Collections.<String>emptyList()));
        }
        return new NativeCompiler().compile(document, tracks);
    }

    private static List<List<TrackEvent>> emptyTrackEvents(int count) {
        List<List<TrackEvent>> tracks = new ArrayList<List<TrackEvent>>();
        for (int i = 0; i < count; i++) tracks.add(new ArrayList<TrackEvent>());
        return tracks;
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
        return mdOnTrack(0, eventIndex, rawTick, values);
    }

    private static MachineDependentEvent mdOnTrack(
            int trackIndex, int eventIndex, int rawTick, int... values) {
        byte[] payload = new byte[values.length];
        for (int i = 0; i < values.length; i++) payload[i] = (byte) values[i];
        return new MachineDependentEvent(trackIndex, eventIndex, 0, rawTick, 0xFF, payload);
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

    private static void eqPrefixShorts(String name, short[] expected, short[] actual) {
        if (actual.length < expected.length) {
            fail(name, "expected at least " + expected.length + " values, got " + actual.length);
        }
        for (int i = 0; i < expected.length; i++) {
            if (expected[i] != actual[i]) {
                fail(name, "mismatch at " + i + ": expected " + expected[i]
                        + ", got " + actual[i]);
            }
        }
    }

    private static void assertShortZeroRange(
            String name, short[] actual, int fromInclusive, int toExclusive) {
        for (int i = fromInclusive; i < toExclusive; i++) {
            if (actual[i] != 0) fail(name, "nonzero value " + actual[i] + " at " + i);
        }
    }

    private static void eqInts(String name, int[] expected, int[] actual) {
        if (!Arrays.equals(expected, actual)) {
            fail(name, "expected " + Arrays.toString(expected)
                    + ", got " + Arrays.toString(actual));
        }
    }

    private static void eqPrefixInts(String name, int[] expected, int[] actual) {
        if (actual.length < expected.length) {
            fail(name, "expected at least " + expected.length + " values, got " + actual.length);
        }
        for (int i = 0; i < expected.length; i++) {
            if (expected[i] != actual[i]) {
                fail(name, "mismatch at " + i + ": expected " + expected[i]
                        + ", got " + actual[i]);
            }
        }
    }

    private static void assert8002DecodeRejected(String name, byte[] encoded, int channels) {
        try {
            Mfi8002Decoder.decode(encoded, 8000, 4, channels);
            fail(name, "native AWC2 rejection was not preserved");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    private static void same(String name, Object expected, Object actual) {
        if (expected != actual) fail(name, "expected " + expected + ", got " + actual);
    }

    private static void fail(String name, String detail) {
        throw new AssertionError(name + ": " + detail);
    }
}
