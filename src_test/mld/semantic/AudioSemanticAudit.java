package mld.semantic;

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

/** Regression audit for the typed native audio semantic domain. */
public final class AudioSemanticAudit {
    public static void main(String[] args) {
        auditIntegratedResourceAndMachineState();
        auditConditionalFinalStateProvenance();
        auditSampledResourceDefaults();
        auditSampledResourceControls();
        auditUnsupportedRendererDiagnostics();
        auditStrictVerifiedRendererProfile();
        auditSharedCompactSlotState();
        System.out.println("AudioSemanticAudit: PASS");
    }

    private static void auditIntegratedResourceAndMachineState() {
        MldDocument document = document();
        List<TrackEvent> events = new ArrayList<TrackEvent>();
        events.add(md(0, 0, 0x71, 0x81, 0x42));
        events.add(md(1, 0, 0x71, 0x82, 0x43));
        events.add(resource(2, 1, 0x80, 0x05));
        events.add(system(3, 2, 0xB0, 90));
        events.add(md(4, 3,
                0x71, 0x84,
                0x41, 0x45, 0x00,
                0x00, 0x00, 0x00, 0x03,
                0x11, 0x22, 0x33));
        events.add(resource(5, 4, 0x00, 0x00));
        events.add(resource(6, 5, 0x90, 0x3F));
        events.add(md(7, 6,
                0x11, 0x01, 0xF2, 0x07,
                0x10, 0x30, 0x24, 0x38,
                0x10, 0x30, 0x24, 0x38,
                0x10, 0x30, 0x24, 0x38,
                0x10, 0x30, 0x24, 0x38));

        NativeProgram program = compile(document, events, 6);
        AudioProgram audio = program.audio;

        eq("active adat catalog index", 0, activeAdat(audio, 0).activeAdatIndex);
        eq("initial thrd config count", 1, audio.initialChannelConfigs.size());
        eqText("initial thrd target", "audio", audio.initialChannelConfigs.get(0).target);
        eq("initial thrd selector", 1, audio.initialChannelConfigs.get(0).rawSubvalue);

        AudioProgram.ChannelState channel0 = channel(audio, 0);
        isTrue("resource level known", channel0.levelKnown);
        eq("resource 7F80 level", 10, channel0.level);
        AudioProgram.ChannelState channel1 = channel(audio, 1);
        eq("machine 71:81 level", 4, channel1.level);
        isFalse("71:81 final level unconditional", channel1.levelPhaseGated);
        eq("machine 71:82 pan", 6, channel1.pan);
        isTrue("71:82 final pan phase-gated", channel1.panPhaseGated);

        AudioProgram.AudioAction pan = action(audio, 0x105, AudioProgram.ActionKind.CHANNEL_PAN);
        isTrue("71:82 phase gate retained", pan.phaseGated);
        eq("71:82 descriptor", 21, pan.descriptorIndex);

        AudioProgram.AudioAction sample = action(audio, 0x106, AudioProgram.ActionKind.SLOT_LOAD_AND_START);
        eq("0x8001 slot", 1, sample.slot);
        eq("0x8001 logical channel", 1, sample.logicalChannel);
        eq("0x8001 operation", 1, sample.operation);
        eq("0x8001 format", 5, sample.formatCode);
        eq("0x8001 rate", 8000, sample.sampleRate);
        eq("0x8001 coded bits", 4, sample.codedBits);
        eq("0x8001 duration byte count", 3, sample.durationByteCount);
        eqLong("0x8001 duration ms", 1L, sample.durationMs);
        isTrue("0x106 runtime phase gate retained", sample.phaseGated);
        eqBytes("0x8001 encoded payload", new byte[] {0x11, 0x22, 0x33}, sample.copyEncodedPayload());
        same("0x8001 renderer support",
                AudioProgram.RendererSupport.VERIFIED_8001_4BIT, sample.rendererSupport);

        AudioProgram.SlotState slot = slot(audio, 1);
        same("final slot type", AudioProgram.AudioType.MFI_8001, slot.audioType);
        eq("final slot control flag", 0, slot.controlFlag);
        eqBytes("final slot payload", new byte[] {0x11, 0x22, 0x33}, slot.copyEncodedPayload());

        AudioProgram.AudioAction resourceStart = action(
                audio, -1, AudioProgram.ActionKind.RESOURCE_START);
        eq("resource active index", 0, resourceStart.resourceIndex);
        eq("resource linked catalog", 0, resourceStart.linkedCatalogIndex);
        same("resource renderer verified",
                AudioProgram.RendererSupport.VERIFIED_8001_4BIT,
                resourceStart.rendererSupport);
        eq("resource start level", 126, resourceStart.value);
        eq("resource sample rate", 8000, resourceStart.sampleRate);
        eq("resource coded bits", 4, resourceStart.codedBits);
        eq("resource channels", 1, resourceStart.channelCount);
        AudioProgram.SampledResource sampledResource = activeAdat(audio, 0).sampledResource;
        isTrue("resource sampled payload typed", sampledResource != null);
        eq("resource sampled payload length", 2, sampledResource.encodedPayloadLength());
        eqBytes("resource sampled payload", new byte[] {0x12, 0x34},
                sampledResource.copyEncodedPayload());

        AudioProgram.ConfigBinding config = config(audio, "audio", 0);
        eq("initial config selector remains", 1, config.selector);
        AudioProgram.ChannelState routed = channel(audio, 0);
        isTrue("audio route known", routed.routeKnown);
        eq("selector31 clears route", 0, routed.route);

        AudioProgram.AudioAction route = action(
                audio, 0x407, AudioProgram.ActionKind.ROUTE_UPDATE);
        isTrue("0x407 runtime phase gate retained", route.phaseGated);
        same("0x407 layered effect",
                AudioProgram.BranchEffect.PHASE_GATED_BACKEND, route.layeredEffect);

        int[] routeFlags = audio.routeState.copyFlags();
        int[] routeClasses = audio.routeState.copyClasses();
        eq("route flag lane0 classA1", 0, routeFlags[0]);
        eq("route flag lane1 classA3", 1, routeFlags[1]);
        eq("route class lane2", 1, routeClasses[2]);
        eq("route class lane3", 2, routeClasses[3]);

        eq("B0 sampled resource level", 90, audio.resourceLevel);
        eq("sampled resource pan default", 64, audio.resourcePan);
        eq("sampled global level remains independent", 64, audio.globalSampledLevel);
        containsAction(audio, AudioProgram.SourceKind.SYSTEM, AudioProgram.ActionKind.RESOURCE_LEVEL);
    }

    private static void auditConditionalFinalStateProvenance() {
        List<TrackEvent> events = new ArrayList<TrackEvent>();
        events.add(md(0, 0, 0x71, 0x82, 0x43));
        events.add(resource(1, 1, 0x81, 0x45));
        NativeProgram program = compile(emptyDocument(), events, 1);
        AudioProgram.ChannelState state = channel(program.audio, 1);
        eq("unconditional resource pan overwrites value", 10, state.pan);
        isFalse("unconditional resource pan clears phase provenance", state.panPhaseGated);
    }

    private static void auditSampledResourceDefaults() {
        NativeProgram program = compile(
                emptyDocument(), Collections.<TrackEvent>emptyList(), 0);
        eq("sampled resource default level", 127, program.audio.resourceLevel);
        eq("sampled resource default pan", 64, program.audio.resourcePan);
        eq("sampled global default level", 64, program.audio.globalSampledLevel);
    }

    private static void auditSampledResourceControls() {
        List<TrackEvent> events = new ArrayList<TrackEvent>();
        events.add(system(0, 0, 0xB0, 90));
        events.add(system(1, 1, 0xB1, 23));
        events.add(system(2, 2, 0xBD, 0x20));
        NativeProgram program = compile(emptyDocument(), events, 2);
        eq("BD updates B0-backed resource level", 58, program.audio.resourceLevel);
        eq("B1 updates resource pan", 23, program.audio.resourcePan);
        eq("B0/B1 do not replace sampled global level", 64, program.audio.globalSampledLevel);
        same("B0 action kind", AudioProgram.ActionKind.RESOURCE_LEVEL, program.audio.actions.get(0).kind);
        same("B1 action kind", AudioProgram.ActionKind.RESOURCE_PAN, program.audio.actions.get(1).kind);
        same("BD action kind", AudioProgram.ActionKind.RESOURCE_LEVEL, program.audio.actions.get(2).kind);

        List<TrackEvent> reset = new ArrayList<TrackEvent>();
        reset.add(system(0, 0, 0xB0, 37));
        reset.add(system(1, 1, 0xBF, 0xFF));
        reset.add(system(2, 2, 0xBD, 0x40));
        NativeProgram resetProgram = compile(emptyDocument(), reset, 2);
        eq("BF restores B0 cache before relative BD", 100, resetProgram.audio.resourceLevel);
        eq("BF does not invent sampled-global action", 2, resetProgram.audio.actions.size());
    }

    private static void auditUnsupportedRendererDiagnostics() {
        List<TrackEvent> events = new ArrayList<TrackEvent>();
        events.add(md(0, 0,
                0x71, 0x86,
                0x02, 0x06, 0x01,
                0x55, 0x66));
        events.add(md(1, 1,
                0x71, 0x84,
                0x03, 0x44, 0x00,
                0x00, 0x00, 0x00, 0x01,
                0x7F));
        NativeProgram program = compile(emptyDocument(), events, 1);
        AudioProgram audio = program.audio;

        AudioProgram.AudioAction cached8000 = action(
                audio, 0x003, AudioProgram.ActionKind.SLOT_LOAD);
        isTrue("cached 8000 state", cached8000.cacheAware);
        eq("cached 8000 rate", 16000, cached8000.sampleRate);
        same("8000 renderer unsupported",
                AudioProgram.RendererSupport.RECOGNIZED_UNSUPPORTED,
                cached8000.rendererSupport);
        containsDiagnostic(audio, "AUDIO_RENDERER_8000_UNSUPPORTED");

        AudioProgram.AudioAction twoBit = action(
                audio, 0x106, AudioProgram.ActionKind.SLOT_LOAD_AND_START);
        eq("2-bit 8001 format", 4, twoBit.formatCode);
        eq("2-bit 8001 coded bits", 2, twoBit.codedBits);
        same("2-bit renderer unsupported",
                AudioProgram.RendererSupport.RECOGNIZED_UNSUPPORTED,
                twoBit.rendererSupport);
        containsDiagnostic(audio, "AUDIO_RENDERER_8001_2BIT_UNSUPPORTED");
    }

    private static void auditSharedCompactSlotState() {
        List<TrackEvent> first = new ArrayList<TrackEvent>();
        first.add(md(0, 0,
                0x11, 0x01, 0xF0, 0x07,
                0x00, 0x01, 0x1F, 0x40,
                0x00));
        first.add(md(1, 1,
                0x71, 0x84,
                0x00, 0x45, 0x00,
                0x00, 0x00, 0x00, 0x01,
                0x12));
        NativeProgram firstProgram = compile(emptyDocument(), first, 1);
        same("first raw-0 0x106 preserves compact slot type",
                AudioProgram.AudioType.MFI_8002, slot(firstProgram.audio, 0).audioType);

        List<TrackEvent> second = new ArrayList<TrackEvent>(first);
        second.add(md(2, 2,
                0x71, 0x84,
                0x00, 0x45, 0x00,
                0x00, 0x00, 0x00, 0x01,
                0x12));
        NativeProgram secondProgram = compile(emptyDocument(), second, 2);
        same("second raw-0 0x106 replaces compact slot type",
                AudioProgram.AudioType.MFI_8001, slot(secondProgram.audio, 0).audioType);

        List<TrackEvent> op2ThenOp1 = new ArrayList<TrackEvent>();
        op2ThenOp1.add(md(0, 0,
                0x11, 0x01, 0xF0, 0x07,
                0x00, 0x01, 0x1F, 0x40,
                0x00));
        op2ThenOp1.add(md(1, 1,
                0x71, 0x84,
                0x00, 0x85, 0x00,
                0x00, 0x00, 0x00, 0x01,
                0x12));
        op2ThenOp1.add(md(2, 2,
                0x71, 0x84,
                0x00, 0x45, 0x00,
                0x00, 0x00, 0x00, 0x01,
                0x12));
        NativeProgram op2ThenOp1Program = compile(emptyDocument(), op2ThenOp1, 2);
        same("raw-0 op2 consumes compact pending state before following op1",
                AudioProgram.AudioType.MFI_8001, slot(op2ThenOp1Program.audio, 0).audioType);

        List<TrackEvent> forcedOp0 = new ArrayList<TrackEvent>();
        forcedOp0.add(md(0, 0,
                0x11, 0x01, 0xF0, 0x07,
                0x00, 0x01, 0x1F, 0x40,
                0x00));
        forcedOp0.add(md(1, 1,
                0x71, 0x84,
                0x00, 0x45, 0x01,
                0x00, 0x00, 0x00, 0x01,
                0x12));
        forcedOp0.add(md(2, 2,
                0x71, 0x84,
                0x00, 0x45, 0x00,
                0x00, 0x00, 0x00, 0x01,
                0x12));
        NativeProgram forcedOp0Program = compile(emptyDocument(), forcedOp0, 2);
        same("raw-1 preserves compact cache and pending state for following raw-0",
                AudioProgram.AudioType.MFI_8002, slot(forcedOp0Program.audio, 0).audioType);

        forcedOp0.add(md(3, 3,
                0x71, 0x84,
                0x00, 0x45, 0x00,
                0x00, 0x00, 0x00, 0x01,
                0x12));
        NativeProgram afterPendingProgram = compile(emptyDocument(), forcedOp0, 3);
        same("raw-0 after pending consumption replaces compact cache",
                AudioProgram.AudioType.MFI_8001, slot(afterPendingProgram.audio, 0).audioType);

        List<TrackEvent> inlinePending = new ArrayList<TrackEvent>();
        inlinePending.add(md(0, 0,
                0x11, 0x01, 0xF0, 0x07,
                0x00, 0x01, 0x1F, 0x40,
                0x00));
        inlinePending.add(md(1, 1,
                0x61, 0x83,
                0x00, 0x45, 0x00,
                0x12));
        NativeProgram inlinePendingProgram = compile(emptyDocument(), inlinePending, 1);
        same("inline 0x109 consumes shared compact pending state",
                AudioProgram.AudioType.MFI_8002, slot(inlinePendingProgram.audio, 0).audioType);

        List<TrackEvent> cachedOp3 = new ArrayList<TrackEvent>();
        cachedOp3.add(md(0, 0,
                0x71, 0x84,
                0x00, 0xC5, 0x01,
                0x00, 0x00, 0x00, 0x02,
                0x12, 0x34));
        NativeProgram cachedOp3Load = compile(emptyDocument(), cachedOp3, 0);
        AudioProgram.AudioAction cachedOp3Action = cachedOp3Load.audio.actions.get(0);
        same("raw-1 op3 layered execution effect",
                AudioProgram.BranchEffect.PHASE_GATED_BACKEND, cachedOp3Action.layeredEffect);
        same("raw-1 op3 monolithic state effect",
                AudioProgram.BranchEffect.STATE_ONLY, cachedOp3Action.monolithicEffect);
        same("raw-1 incoming op3 is forced to an 0x8001 load",
                AudioProgram.AudioType.MFI_8001, slot(cachedOp3Load.audio, 0).audioType);
        eqBytes("raw-1 op3 forced-load payload retained",
                new byte[] {0x12, 0x34}, slot(cachedOp3Load.audio, 0).copyEncodedPayload());

        cachedOp3.add(md(1, 1,
                0x11, 0x01, 0xF0, 0x07,
                0x00, 0x01, 0x1F, 0x40,
                0x00));
        cachedOp3.add(md(2, 2,
                0x71, 0x84,
                0x00, 0x45, 0x00,
                0x00, 0x00, 0x00, 0x01,
                0x56));
        NativeProgram restoredOp3 = compile(emptyDocument(), cachedOp3, 2);
        same("0x400 retains cached op3 and pending raw-0 is no-op",
                AudioProgram.AudioType.MFI_8002, slot(restoredOp3.audio, 0).audioType);

        List<TrackEvent> append = new ArrayList<TrackEvent>();
        append.add(md(0, 0,
                0x71, 0x84,
                0x00, 0x05, 0x01,
                0x00, 0x00, 0x00, 0x01,
                0x12));
        append.add(md(1, 1,
                0x71, 0x84,
                0x00, 0xC5, 0x00,
                0x00, 0x00, 0x00, 0x01,
                0x34));
        NativeProgram appendedProgram = compile(emptyDocument(), append, 1);
        eqBytes("pending cached-op0 appends the unreleased 0x8001 payload",
                new byte[] {0x12, 0x34}, slot(appendedProgram.audio, 0).copyEncodedPayload());

        List<TrackEvent> cached8000Op3 = new ArrayList<TrackEvent>();
        cached8000Op3.add(md(0, 0,
                0x71, 0x86,
                0x00, 0xC6, 0x01,
                0x55, 0x66));
        NativeProgram cached8000Op3Program = compile(emptyDocument(), cached8000Op3, 0);
        same("cached 0x003 raw-1 op3 is forced to an 0x8000 load",
                AudioProgram.AudioType.MFI_8000, slot(cached8000Op3Program.audio, 0).audioType);
        eq("cached 0x003 forced-load rate", 16000,
                slot(cached8000Op3Program.audio, 0).sampleRate);
        eqBytes("cached 0x003 forced-load payload retained",
                new byte[] {0x55, 0x66},
                slot(cached8000Op3Program.audio, 0).copyEncodedPayload());

        List<TrackEvent> compactThen8000 = new ArrayList<TrackEvent>();
        compactThen8000.add(md(0, 0,
                0x11, 0x01, 0xF0, 0x07,
                0x00, 0x01, 0x1F, 0x40,
                0x00));
        compactThen8000.add(md(1, 1,
                0x71, 0x86,
                0x00, 0x06, 0x00,
                0x55));
        NativeProgram compactThen8000Program = compile(emptyDocument(), compactThen8000, 1);
        same("0x400 cached format is shared with 0x003 and rejects 0x8000 reload",
                AudioProgram.AudioType.MFI_8002, slot(compactThen8000Program.audio, 0).audioType);

        List<TrackEvent> invalidCompactSlot = new ArrayList<TrackEvent>();
        invalidCompactSlot.add(md(0, 0,
                0x11, 0x01, 0xF0, 0x07,
                0x40, 0x01, 0x1F, 0x40,
                0x00));
        NativeProgram invalidCompactSlotProgram = compile(emptyDocument(), invalidCompactSlot, 0);
        isFalse("0x400 raw slot 64 is rejected by MFiAudio and never becomes loaded",
                hasSlot(invalidCompactSlotProgram.audio, 64));

        compactThen8000.add(md(2, 2,
                0x71, 0x86,
                0x00, 0x06, 0x00,
                0x77));
        NativeProgram after8000PendingProgram = compile(emptyDocument(), compactThen8000, 2);
        same("0x003 raw-0 consumes shared pending before normal replacement",
                AudioProgram.AudioType.MFI_8000, slot(after8000PendingProgram.audio, 0).audioType);
        eqBytes("normal 0x003 replacement uses the following payload",
                new byte[] {0x77},
                slot(after8000PendingProgram.audio, 0).copyEncodedPayload());

        List<TrackEvent> cached8000OpShared = new ArrayList<TrackEvent>();
        cached8000OpShared.add(md(0, 0,
                0x71, 0x86,
                0x00, 0xC6, 0x01,
                0x11));
        cached8000OpShared.add(md(1, 1,
                0x11, 0x01, 0xF0, 0x07,
                0x00, 0x00, 0x1F, 0x40,
                0x00));
        cached8000OpShared.add(md(2, 2,
                0x71, 0x84,
                0x00, 0x45, 0x00,
                0x00, 0x00, 0x00, 0x01,
                0x22));
        NativeProgram cached8000OpSharedProgram = compile(emptyDocument(), cached8000OpShared, 2);
        same("0x003 cached operation is shared through 0x400 into 0x106",
                AudioProgram.AudioType.MFI_8002,
                slot(cached8000OpSharedProgram.audio, 0).audioType);
    }

    private static void auditStrictVerifiedRendererProfile() {
        NativeProgram flaggedProgram = compile(
                emptyDocument(),
                Collections.<TrackEvent>singletonList(md(0, 0,
                        0x71, 0x84,
                        0x00, 0x45, 0x01,
                        0x00, 0x00, 0x00, 0x01,
                        0x12)),
                0);
        AudioProgram.AudioAction flagged = flaggedProgram.audio.actions.get(0);
        same("nonzero 71:84 flags are not renderer-ready",
                AudioProgram.RendererSupport.RECOGNIZED_UNSUPPORTED,
                flagged.rendererSupport);
        containsDiagnostic(flaggedProgram.audio, "AUDIO_RENDERER_8001_PROFILE_UNSUPPORTED");

        NativeProgram inlineProgram = compile(
                emptyDocument(),
                Collections.<TrackEvent>singletonList(md(0, 0,
                        0x61, 0x83,
                        0x00, 0x45, 0x00,
                        0x12)),
                0);
        AudioProgram.AudioAction inline = inlineProgram.audio.actions.get(0);
        same("non-71:84 4-bit 8001 remains typed only",
                AudioProgram.RendererSupport.RECOGNIZED_UNSUPPORTED,
                inline.rendererSupport);
        isTrue("inline 0x109 retains native runtime phase gate", inline.phaseGated);
        same("inline 0x109 layered effect is phase-gated",
                AudioProgram.BranchEffect.PHASE_GATED_BACKEND,
                inline.layeredEffect);

        NativeProgram countedProgram = compile(
                emptyDocument(),
                Collections.<TrackEvent>singletonList(md(0, 0,
                        0x71, 0x84,
                        0x00, 0x4D, 0x00,
                        0x00, 0x00, 0x00, 0x01,
                        0x12, 0x34, 0x56)),
                0);
        AudioProgram.AudioAction counted = countedProgram.audio.actions.get(0);
        same("format13 verified renderer support",
                AudioProgram.RendererSupport.VERIFIED_8001_4BIT,
                counted.rendererSupport);
        eq("format13 sample rate", 16000, counted.sampleRate);
        eq("counted 71:84 duration count", 1, counted.durationByteCount);
        eqBytes("counted 71:84 loads the full body remainder",
                new byte[] {0x12, 0x34, 0x56}, counted.copyEncodedPayload());

        NativeProgram mismatchedCountProgram = compile(
                emptyDocument(),
                Collections.<TrackEvent>singletonList(md(0, 0,
                        0x71, 0x84,
                        0x00, 0x45, 0x00,
                        0x00, 0x00, 0x00, 0x03,
                        0x12)),
                0);
        AudioProgram.AudioAction mismatched = mismatchedCountProgram.audio.actions.get(0);
        same("duration count larger than payload remains renderer-ready",
                AudioProgram.RendererSupport.VERIFIED_8001_4BIT, mismatched.rendererSupport);
        eq("mismatched duration count retained", 3, mismatched.durationByteCount);
        eqBytes("mismatched load still uses available body remainder",
                new byte[] {0x12}, mismatched.copyEncodedPayload());

        NativeProgram ignoredUpperBitsProgram = compile(
                emptyDocument(),
                Collections.<TrackEvent>singletonList(md(0, 0,
                        0x71, 0x84,
                        0x00, 0x45, 0x80,
                        0x00, 0x00, 0x00, 0x01,
                        0x12)),
                0);
        AudioProgram.AudioAction ignoredUpperBits = ignoredUpperBitsProgram.audio.actions.get(0);
        eq("native control bit0 ignores upper bits", 0, ignoredUpperBits.controlFlag);
        same("raw-byte-zero renderer boundary remains fail-closed",
                AudioProgram.RendererSupport.RECOGNIZED_UNSUPPORTED,
                ignoredUpperBits.rendererSupport);

        NativeProgram channel3Program = compile(
                emptyDocument(),
                Collections.<TrackEvent>singletonList(md(0, 0,
                        0x71, 0x84,
                        0xC0, 0x45, 0x00,
                        0x00, 0x00, 0x00, 0x01,
                        0x12)),
                0);
        AudioProgram.AudioAction channel3 = channel3Program.audio.actions.get(0);
        eq("71:84 high2 channel 3", 3, channel3.logicalChannel);
        isTrue("71:84 channel 3 native start eligible", channel3.layeredStartEligible);
        same("71:84 channel 3 renderer verified",
                AudioProgram.RendererSupport.VERIFIED_8001_4BIT,
                channel3.rendererSupport);
    }

    private static NativeProgram compile(MldDocument document, List<TrackEvent> events, int totalRawTicks) {
        int resourceCount = 0;
        int systemCount = 0;
        int machineCount = 0;
        for (TrackEvent event : events) {
            if (event instanceof ResourceEvent) resourceCount++;
            if (event instanceof SystemEvent) systemCount++;
            if (event instanceof MachineDependentEvent) machineCount++;
        }
        DecodedTrack track = new DecodedTrack(
                0,
                0,
                totalRawTicks,
                0,
                resourceCount,
                systemCount,
                machineCount,
                events,
                Collections.<String>emptyList());
        return new NativeCompiler().compile(document, Collections.singletonList(track));
    }

    private static MldDocument document() {
        List<TopLevelChunk> chunks = new ArrayList<TopLevelChunk>();
        chunks.add(chunk("ainf", 13, 2, "header", new byte[] {0x01}));
        chunks.add(chunk("thrd", 20, 2, "header", new byte[] {0x02, 0x00, 0x21}));
        chunks.add(chunk("adat", 42, 4, "resource", new byte[] {
                0x00, 0x0B, (byte)0x81, 0x03,
                'a', 'd', 'p', 'm', 0x00, 0x03, 0x08, 0x04, 0x01,
                0x12, 0x34
        }));
        return new MldDocument(
                new byte[0],
                "melo",
                0,
                32,
                0,
                0,
                1,
                0,
                0,
                Collections.<Long>emptyList(),
                chunks,
                Collections.emptyList());
    }

    private static MldDocument emptyDocument() {
        return new MldDocument(
                new byte[0], "melo", 0, 0, 0, 0, 1, 0, 0,
                Collections.<Long>emptyList(), Collections.emptyList(), Collections.emptyList());
    }

    private static TopLevelChunk chunk(
            String id, int offset, int lengthFieldBytes, String category, byte[] payload) {
        return new TopLevelChunk(id, offset, payload.length, lengthFieldBytes, category, payload, null);
    }

    private static MachineDependentEvent md(int eventIndex, int rawTick, int... bytes) {
        byte[] payload = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) payload[i] = (byte) bytes[i];
        return new MachineDependentEvent(0, eventIndex, 0, rawTick, 0xFF, payload);
    }

    private static ResourceEvent resource(int eventIndex, int rawTick, int command, int... body) {
        byte[] bytes = new byte[body.length];
        for (int i = 0; i < body.length; i++) bytes[i] = (byte) body[i];
        return new ResourceEvent(
                0,
                eventIndex,
                0,
                rawTick,
                command,
                TrackDecoder.resourceName(command),
                bytes,
                command >= 0xF0);
    }

    private static SystemEvent system(int eventIndex, int rawTick, int command, int value) {
        return new SystemEvent(
                0,
                eventIndex,
                0,
                rawTick,
                command,
                value,
                TrackDecoder.commandName(command),
                -1,
                -1);
    }

    private static AudioProgram.ResourceCatalogEntry activeAdat(AudioProgram audio, int activeIndex) {
        for (AudioProgram.ResourceCatalogEntry entry : audio.resourceCatalog) {
            if (entry.activeAdatIndex == activeIndex) return entry;
        }
        fail("active adat", "missing active index " + activeIndex);
        return null;
    }

    private static AudioProgram.ChannelState channel(AudioProgram audio, int logicalChannel) {
        for (AudioProgram.ChannelState state : audio.channelStates) {
            if (state.logicalChannel == logicalChannel) return state;
        }
        fail("channel state", "missing logical channel " + logicalChannel);
        return null;
    }

    private static boolean hasSlot(AudioProgram audio, int slot) {
        for (AudioProgram.SlotState state : audio.slotStates) {
            if (state.slot == slot) return true;
        }
        return false;
    }

    private static AudioProgram.SlotState slot(AudioProgram audio, int slot) {
        for (AudioProgram.SlotState state : audio.slotStates) {
            if (state.slot == slot) return state;
        }
        fail("slot state", "missing slot " + slot);
        return null;
    }

    private static AudioProgram.ConfigBinding config(AudioProgram audio, String target, int channel) {
        for (AudioProgram.ConfigBinding state : audio.configBindings) {
            if (target.equals(state.target) && state.logicalChannel == channel) return state;
        }
        fail("config binding", "missing " + target + " channel " + channel);
        return null;
    }

    private static AudioProgram.AudioAction action(
            AudioProgram audio, int handlerId, AudioProgram.ActionKind kind) {
        for (AudioProgram.AudioAction action : audio.actions) {
            if (action.handlerId == handlerId && action.kind == kind) return action;
        }
        fail("audio action", "missing handler 0x" + Integer.toHexString(handlerId) + " / " + kind);
        return null;
    }

    private static void containsAction(
            AudioProgram audio, AudioProgram.SourceKind source, AudioProgram.ActionKind kind) {
        for (AudioProgram.AudioAction action : audio.actions) {
            if (action.sourceKind == source && action.kind == kind) return;
        }
        fail("audio action", "missing " + source + " / " + kind);
    }

    private static void containsDiagnostic(AudioProgram audio, String code) {
        for (Diagnostic diagnostic : audio.diagnostics) {
            if (code.equals(diagnostic.code)) return;
        }
        fail("diagnostic", "missing " + code);
    }

    private static void eqBytes(String name, byte[] expected, byte[] actual) {
        if (!Arrays.equals(expected, actual)) {
            fail(name, "expected " + Arrays.toString(expected) + ", got " + Arrays.toString(actual));
        }
    }

    private static void eq(String name, int expected, int actual) {
        if (expected != actual) fail(name, "expected " + expected + ", got " + actual);
    }

    private static void eqLong(String name, long expected, long actual) {
        if (expected != actual) fail(name, "expected " + expected + ", got " + actual);
    }

    private static void eqText(String name, String expected, String actual) {
        if (!expected.equals(actual)) fail(name, "expected " + expected + ", got " + actual);
    }

    private static void isFalse(String name, boolean value) {
        if (value) fail(name, "expected false");
    }

    private static void isTrue(String name, boolean value) {
        if (!value) fail(name, "expected true");
    }

    private static void same(String name, Object expected, Object actual) {
        if (expected != actual) fail(name, "expected " + expected + ", got " + actual);
    }

    private static void fail(String name, String detail) {
        throw new AssertionError(name + ": " + detail);
    }
}
