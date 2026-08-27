package mld.semantic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import mld.format.MldDocument;
import mld.format.TopLevelChunk;
import mld.format.TrackChunk;
import mld.decode.NoteEvent;
import mld.decode.ResourceEvent;
import mld.decode.SystemEvent;
import mld.decode.DecodedTrack;
import mld.decode.TrackDecoder;
import mld.decode.TrackEvent;

/** Standalone regression audit for the native MFi5 live-resource state model. */
public final class ResourceSemanticsAudit {
    public static void main(String[] args) throws Exception {
        auditResourceEnvelopeFraming();
        auditAinfAdatRegistrationBoundary();
        auditAinfBit40Gate();
        auditThrdInitialConfig();
        auditLiveResourceState();
        System.out.println("ResourceSemanticsAudit: PASS");
    }

    private static void auditResourceEnvelopeFraming() throws Exception {
        MldDocument file = emptyFile(0, 2);
        TrackChunk track = new TrackChunk(0, 0, 0, new byte[] {
                0x00, 0x7F, 0x00, 0x41, 0x22, 0x33,
                0x01, 0x7F, 0x01, 0x42, 0x44, 0x55,
                0x01, 0x7F, (byte) 0x80, 0x43,
                0x01, 0x7F, (byte) 0x90, 0x04,
                0x01, 0x05, 0x04
        });
        DecodedTrack decoded = new TrackDecoder().decode(file, track);
        eq("resource framing event count", 5, decoded.events.size());
        eq("7F00 body length", 3, requireResource(decoded.events.get(0), "7F00").bodyLength());
        eq("7F01 body length", 3, requireResource(decoded.events.get(1), "7F01").bodyLength());
        eq("7F80 body length", 1, requireResource(decoded.events.get(2), "7F80").bodyLength());
        eq("7F90 body length", 1, requireResource(decoded.events.get(3), "7F90").bodyLength());
        if (!(decoded.events.get(4) instanceof NoteEvent)) {
            fail("resource framing alignment", "expected trailing NoteEvent");
        }
        eq("resource framing trailing note tick", 4, decoded.events.get(4).rawTick);
    }

    private static void auditAinfAdatRegistrationBoundary() {
        List<TopLevelChunk> chunks = new ArrayList<TopLevelChunk>();
        chunks.add(chunk("ainf", 13, 2, "resource", new byte[] { 0x02, 0x55 }));
        chunks.add(chunk("thrd", 21, 2, "resource", new byte[] { 0x11, 0x22 }));
        // header_length=19 => resource stage starts at absolute offset 29.
        chunks.add(chunk("adat", 29, 4, "resource", new byte[] { 1, 2, 3, 4 }));
        chunks.add(chunk("adat", 41, 4, "resource", new byte[] { 5, 6, 7, 8 }));
        chunks.add(chunk("trac", 53, 4, "track", new byte[0]));
        chunks.add(chunk("adat", 61, 4, "resource", new byte[] { 9, 10, 11, 12 }));
        NativeProgram program = compile(file(19, 1, 0, chunks), Collections.<TrackEvent>emptyList(), 0);

        AudioProgram.ResourceCatalogEntry adat0 = catalogAt(program, 29);
        AudioProgram.ResourceCatalogEntry adat1 = catalogAt(program, 41);
        AudioProgram.ResourceCatalogEntry lateAdat = catalogAt(program, 61);
        eq("first contiguous adat active index", 0, adat0.activeAdatIndex);
        eq("second contiguous adat active index", 1, adat1.activeAdatIndex);
        eq("adat after non-adat inactive", -1, lateAdat.activeAdatIndex);
    }

    private static void auditAinfBit40Gate() {
        List<TopLevelChunk> chunks = new ArrayList<TopLevelChunk>();
        chunks.add(chunk("ainf", 13, 2, "resource", new byte[] { 0x41, 0x55 }));
        // header_length=11 => resource stage starts at 21.
        chunks.add(chunk("adat", 21, 4, "resource", new byte[] { 1, 2, 3, 4 }));
        NativeProgram program = compile(file(11, 1, 0, chunks), Collections.<TrackEvent>emptyList(), 0);
        eq("bit40 ainf leaves adat inactive", -1, catalogAt(program, 21).activeAdatIndex);
        contains("bit40 ainf warning", program.warnings, "bit 0x40");
    }

    private static void auditThrdInitialConfig() {
        byte[] thrd = new byte[] {
                0x55,
                (byte) 0xF2, 0x05,
                0x02, 0x07,
                (byte) 0xA2, 0x3F,
                0x66
        };
        List<TopLevelChunk> chunks = Collections.singletonList(chunk("thrd", 13, 2, "resource", thrd));
        // encoded thrd length = 4 + 2 + 8 = 14; header end = 27.
        NativeProgram program = compile(file(17, 1, 0, chunks), Collections.<TrackEvent>emptyList(), 0);
        eq("thrd config count", 2, program.audio.initialChannelConfigs.size());

        AudioProgram.InitialChannelConfig synth = program.audio.initialChannelConfigs.get(0);
        eq("thrd global byte", 0x55, synth.globalValue);
        eq("thrd channel low4", 2, synth.logicalChannel);
        eqText("thrd synth target", "synth", synth.target);
        eq("thrd synth raw selector", 5, synth.rawSubvalue);

        AudioProgram.InitialChannelConfig audio = program.audio.initialChannelConfigs.get(1);
        eqText("thrd audio target", "audio", audio.target);
        eq("thrd audio raw31 remains selector", 31, audio.rawSubvalue);
        contains("thrd odd trailing byte warning", program.warnings, "trailing record byte");
        contains("thrd duplicate first-wins warning", program.warnings, "Duplicate thrd record");
    }

    private static void auditLiveResourceState() {
        List<TopLevelChunk> chunks = new ArrayList<TopLevelChunk>();
        chunks.add(chunk("ainf", 13, 2, "resource", new byte[] { 0x02 }));
        // header end = 20.
        chunks.add(chunk("adat", 20, 4, "resource", new byte[] { 1, 2, 3, 4 }));
        chunks.add(chunk("adat", 32, 4, "resource", new byte[] { 5, 6, 7, 8 }));
        MldDocument file = file(10, 1, 0, chunks);

        List<TrackEvent> events = new ArrayList<TrackEvent>();
        events.add(resource(0, 0, 0, 0x00, new byte[] { 0x41 }));       // lane1, active adat index1
        events.add(resource(0, 1, 1, 0x01, new byte[] { 0x41, 0x22 })); // stop slot; extra byte ignored semantically
        events.add(resource(0, 2, 2, 0x80, new byte[] { 0x42 }));       // lane1, value2 -> 4
        events.add(system(0, 3, 3, 0xE5, 0x14));                        // voice0 -> channel20
        events.add(resource(0, 4, 4, 0x90, new byte[] { 0x04 }));       // synth lane0, raw4, dispatch gate fails
        events.add(resource(0, 5, 5, 0x90, new byte[] { (byte) 0xBF }));// audio lane2, raw31
        events.add(resource(0, 6, 6, 0x90, new byte[] { 0x5F }));       // synth lane1, raw31

        NativeProgram program = compile(file, events, 6);
        eq("live resource state count", 6, program.audio.resourceEvents.size());

        AudioProgram.ResourceEventState start = program.audio.resourceEvents.get(0);
        eq("7F00 resource index", 1, start.resourceIndex);
        eq("7F00 active link offset", 32, start.linkedChunkOffset);
        eq("7F00 default low6", 63, start.extraParamLow6);
        eq("7F00 default forwarded value", 126, start.extraParam2x);

        AudioProgram.ResourceEventState stop = program.audio.resourceEvents.get(1);
        eq("7F01 resource slot", 1, stop.resourceIndex);
        eq("7F01 has no adat link", -1, stop.linkedCatalogIndex);
        eq("7F01 ignores extension parameter", -1, stop.extraParamLow6);

        AudioProgram.ResourceEventState level = program.audio.resourceEvents.get(2);
        eqText("7F80 target", "audio", level.target);
        eq("7F80 audio channel", 1, level.logicalChannel);
        eq("7F80 forwarded level", 4, level.value2x);

        AudioProgram.ResourceEventState invalidSynth = program.audio.resourceEvents.get(3);
        eqText("7F90 synth target", "synth", invalidSynth.target);
        eq("7F90 synth mapped channel", 20, invalidSynth.logicalChannel);
        isFalse("7F90 synth channel gate", invalidSynth.channelDispatchEligible);
        isFalse("7F90 raw4 clear condition", invalidSynth.clearWhenOutOfRange);

        AudioProgram.ResourceEventState audio31 = program.audio.resourceEvents.get(4);
        eqText("7F90 audio target", "audio", audio31.target);
        eq("7F90 audio lane channel", 2, audio31.logicalChannel);
        isTrue("7F90 audio channel gate", audio31.channelDispatchEligible);
        isTrue("7F90 raw31 out-of-range clear", audio31.clearWhenOutOfRange);

        AudioProgram.ResourceEventState synth31 = program.audio.resourceEvents.get(5);
        eq("7F90 synth lane1 mapped channel", 1, synth31.logicalChannel);
        isTrue("7F90 synth lane1 channel gate", synth31.channelDispatchEligible);
        isTrue("7F90 synth raw31 conditional clear", synth31.clearWhenOutOfRange);
    }

    private static NativeProgram compile(MldDocument file, List<TrackEvent> events, int totalRawTicks) {
        int resourceCount = 0;
        int systemCount = 0;
        for (TrackEvent event : events) {
            if (event instanceof ResourceEvent) resourceCount++;
            if (event instanceof SystemEvent) systemCount++;
        }
        DecodedTrack decoded = new DecodedTrack(
                0, 0, totalRawTicks, 0, resourceCount, systemCount, 0,
                events, Collections.<String>emptyList());
        return new NativeCompiler().compile(file, Collections.singletonList(decoded));
    }

    private static MldDocument emptyFile(int noteExtraBytes, int exstSize) {
        return file(3, 1, exstSize, Collections.<TopLevelChunk>emptyList(), noteExtraBytes);
    }

    private static MldDocument file(int headerLength, int trackCount, int exstSize, List<TopLevelChunk> chunks) {
        return file(headerLength, trackCount, exstSize, chunks, 0);
    }

    private static MldDocument file(
            int headerLength,
            int trackCount,
            int exstSize,
            List<TopLevelChunk> chunks,
            int noteExtraBytes) {
        return new MldDocument(
                new byte[0], "melo", 0, headerLength, 1, 1, trackCount, noteExtraBytes, exstSize,
                Collections.<Long>emptyList(), chunks, Collections.emptyList());
    }

    private static TopLevelChunk chunk(String id, int offset, int lengthFieldBytes, String category, byte[] payload) {
        return new TopLevelChunk(id, offset, payload.length, lengthFieldBytes, category, payload, null);
    }

    private static ResourceEvent resource(
            int trackIndex,
            int eventIndex,
            int rawTick,
            int command,
            byte[] body) {
        return new ResourceEvent(
                trackIndex, eventIndex, 0, rawTick, command, TrackDecoder.resourceName(command), body, command >= 0xF0);
    }

    private static SystemEvent system(
            int trackIndex,
            int eventIndex,
            int rawTick,
            int command,
            int value) {
        int part = command >= 0xE0 && command <= 0xEF ? ((value >> 6) & 0x03) : -1;
        return new SystemEvent(trackIndex, eventIndex, 0, rawTick, command, value, TrackDecoder.commandName(command), part, -1);
    }

    private static AudioProgram.ResourceCatalogEntry catalogAt(NativeProgram program, int offset) {
        for (AudioProgram.ResourceCatalogEntry entry : program.audio.resourceCatalog) {
            if (entry.offset == offset) return entry;
        }
        fail("catalog lookup", "missing offset " + offset);
        return null;
    }

    private static ResourceEvent requireResource(TrackEvent event, String name) {
        if (!(event instanceof ResourceEvent)) {
            fail(name, "expected ResourceEvent, got " + event.getClass().getSimpleName());
        }
        return (ResourceEvent) event;
    }

    private static void contains(String name, List<String> values, String needle) {
        for (String value : values) {
            if (value.contains(needle)) return;
        }
        fail(name, "missing text: " + needle + " in " + values);
    }

    private static void eq(String name, int expected, int actual) {
        if (expected != actual) fail(name, "expected " + expected + ", got " + actual);
    }

    private static void eqText(String name, String expected, String actual) {
        if (!expected.equals(actual)) fail(name, "expected " + expected + ", got " + actual);
    }

    private static void isTrue(String name, boolean value) {
        if (!value) fail(name, "expected true");
    }

    private static void isFalse(String name, boolean value) {
        if (value) fail(name, "expected false");
    }

    private static void fail(String name, String detail) {
        throw new AssertionError(name + ": " + detail);
    }
}
