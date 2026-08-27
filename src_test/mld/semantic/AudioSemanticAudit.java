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
        auditGlobalLevelDefaults();
        auditGlobalLevelReset();
        auditUnsupportedRendererDiagnostics();
        auditStrictVerifiedRendererProfile();
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
        same("resource renderer unsupported",
                AudioProgram.RendererSupport.RECOGNIZED_UNSUPPORTED,
                resourceStart.rendererSupport);

        AudioProgram.ConfigBinding config = config(audio, "audio", 0);
        eq("live config selector", 31, config.selector);
        isTrue("selector31 conditional clear retained", config.clearWhenOutOfRange);
        containsDiagnostic(audio, "AUDIO_CONFIG_TABLE_UNRESOLVED");
        containsDiagnostic(audio, "AUDIO_RENDERER_RESOURCE_ADAT_UNSUPPORTED");

        int[] routeFlags = audio.routeState.copyFlags();
        int[] routeClasses = audio.routeState.copyClasses();
        eq("route flag lane0 classA1", 0, routeFlags[0]);
        eq("route flag lane1 classA3", 1, routeFlags[1]);
        eq("route class lane2", 1, routeClasses[2]);
        eq("route class lane3", 2, routeClasses[3]);

        eq("global B0 level", 90, audio.globalLevel);
        containsAction(audio, AudioProgram.SourceKind.SYSTEM, AudioProgram.ActionKind.GLOBAL_LEVEL);
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

    private static void auditGlobalLevelDefaults() {
        NativeProgram program = compile(
                emptyDocument(), Collections.<TrackEvent>emptyList(), 0);
        eq("sampled-audio initial global level", 64, program.audio.globalLevel);
    }

    private static void auditGlobalLevelReset() {
        List<TrackEvent> events = new ArrayList<TrackEvent>();
        events.add(system(0, 0, 0xB0, 37));
        events.add(system(1, 1, 0xBF, 0));
        NativeProgram program = compile(emptyDocument(), events, 1);
        eq("BF restores native audio global level", 100, program.audio.globalLevel);
        AudioProgram.AudioAction last = program.audio.actions.get(program.audio.actions.size() - 1);
        same("BF audio action kind", AudioProgram.ActionKind.GLOBAL_LEVEL, last.kind);
        eq("BF audio action value", 100, last.value);
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
        eqBytes("counted 71:84 uses declared embedded length",
                new byte[] {0x12}, counted.copyEncodedPayload());

        NativeProgram invalidLengthProgram = compile(
                emptyDocument(),
                Collections.<TrackEvent>singletonList(md(0, 0,
                        0x71, 0x84,
                        0x00, 0x45, 0x00,
                        0x00, 0x00, 0x00, 0x03,
                        0x12)),
                0);
        same("overrun count is not renderer-ready",
                AudioProgram.RendererSupport.RECOGNIZED_UNSUPPORTED,
                invalidLengthProgram.audio.actions.get(0).rendererSupport);
        containsDiagnostic(invalidLengthProgram.audio, "AUDIO_RENDERER_8001_LENGTH_INVALID");
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
        chunks.add(chunk("adat", 42, 4, "resource", new byte[] {0x00, 0x02, (byte) 0x81, 0x00}));
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
