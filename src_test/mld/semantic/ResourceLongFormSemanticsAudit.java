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

/** Standalone regression audit for the native MFi5 0x7F F0..FF long-form resource family. */
public final class ResourceLongFormSemanticsAudit {
    public static void main(String[] args) throws Exception {
        auditLongFormFraming();
        auditF0ThreeDimensionalState();
        auditF0TrackAndLengthGate();
        auditSameTickNativeTimingOrder();
        System.out.println("ResourceLongFormSemanticsAudit: PASS");
    }

    private static void auditLongFormFraming() throws Exception {
        MldDocument file = emptyFile();
        TrackChunk track = new TrackChunk(0, 0, 0, new byte[] {
                0x00, 0x7F, (byte) 0xF0, 0x00, 0x06, 0x05, 0x40, 0x45, (byte) 0x80, 0x00, 0x64,
                0x01, 0x7F, (byte) 0xF1, 0x00, 0x03, 0x11, 0x22, 0x33,
                0x01, 0x05, 0x04
        });
        DecodedTrack decoded = new TrackDecoder().decode(file, track);
        eq("long-form event count", 3, decoded.events.size());
        ResourceEvent f0 = requireResource(decoded.events.get(0), "7FF0");
        eq("7FF0 body length", 6, f0.bodyLength());
        eqText("7FF0 name", "resource_3d_auxiliary", f0.name);
        ResourceEvent f1 = requireResource(decoded.events.get(1), "7FF1");
        eq("7FF1 body length", 3, f1.bodyLength());
        if (!(decoded.events.get(2) instanceof NoteEvent)) {
            fail("long-form framing alignment", "expected trailing NoteEvent");
        }
        eq("long-form trailing note tick", 2, decoded.events.get(2).rawTick);
    }

    private static void auditF0ThreeDimensionalState() {
        List<TrackEvent> events = Collections.<TrackEvent>singletonList(
                resource(0, 0, 0, 0xF0, new byte[] { (byte) 0xE5, 0x40, 0x45, (byte) 0x80, 0x00, 0x64 }));
        NativeProgram program = compile(events, 0);
        AudioProgram.ResourceEventState state = program.audio.resourceEvents.get(0);
        eqText("7FF0 target", "3d", state.target);
        eq("7FF0 lane is not decoded", -1, state.lane);
        isTrue("7FF0 MLD dispatch candidate", state.auxiliary3dDispatchCandidate);
        eq("7FF0 low5 helper argument", 5, state.auxiliary3dLow5);
        eq("7FF0 raw distance", 64, state.auxiliary3dDistanceRaw);
        eq("7FF0 angle A", 183, state.auxiliary3dAngleA);
        eq("7FF0 angle B", 0, state.auxiliary3dAngleB);
        eq("7FF0 raw duration", 100, state.auxiliary3dDurationRaw);
        eq("7FF0 default timing duration ms", 1000, state.auxiliary3dDurationMs);
    }

    private static void auditF0TrackAndLengthGate() {
        List<TrackEvent> events = new ArrayList<TrackEvent>();
        events.add(resource(1, 0, 0, 0xF0, new byte[] { 0x01, 0x02, 0x03, 0x04, 0x00, 0x01 }));
        events.add(resource(0, 1, 1, 0xF0, new byte[] { 0x01, 0x02, 0x03, 0x04, 0x00 }));
        NativeProgram program = compile(events, 1);
        isFalse("7FF0 track1 dispatch gate", program.audio.resourceEvents.get(0).auxiliary3dDispatchCandidate);
        isFalse("7FF0 short payload dispatch gate", program.audio.resourceEvents.get(1).auxiliary3dDispatchCandidate);
        eq("7FF0 gated track has no semantic low5", -1, program.audio.resourceEvents.get(0).auxiliary3dLow5);
        eq("7FF0 gated short payload has no duration", -1, program.audio.resourceEvents.get(1).auxiliary3dDurationRaw);
    }

    private static void auditSameTickNativeTimingOrder() {
        List<TrackEvent> events = new ArrayList<TrackEvent>();
        byte[] body = new byte[] { 0x00, 0x01, (byte) 0x80, (byte) 0x80, 0x00, 0x64 };
        events.add(resource(0, 0, 0, 0xF0, body));
        events.add(tempo(0, 1, 0, 0xC3, 100, 48));
        events.add(resource(0, 2, 0, 0xF0, body));
        events.add(tempo(0, 3, 0, 0xC4, 100, 96));
        events.add(resource(0, 4, 0, 0xF0, body));
        NativeProgram program = compile(events, 0);
        eq("7FF0 before same-tick tempo", 1000, program.audio.resourceEvents.get(0).auxiliary3dDurationMs);
        eq("7FF0 after same-tick tempo", 1250, program.audio.resourceEvents.get(1).auxiliary3dDurationMs);
        eq("7FF0 after same-tick timebase", 625, program.audio.resourceEvents.get(2).auxiliary3dDurationMs);
    }

    private static NativeProgram compile(List<TrackEvent> events, int totalRawTicks) {
        int resourceCount = 0;
        int systemCount = 0;
        for (TrackEvent event : events) {
            if (event instanceof ResourceEvent) resourceCount++;
            if (event instanceof SystemEvent) systemCount++;
        }
        DecodedTrack decoded = new DecodedTrack(
                0, 0, totalRawTicks, 0, resourceCount, systemCount, 0,
                events, Collections.<String>emptyList());
        return new NativeCompiler().compile(emptyFile(), Collections.singletonList(decoded));
    }

    private static MldDocument emptyFile() {
        return new MldDocument(
                new byte[0], "melo", 0, 3, 1, 1, 1, 0, 0,
                Collections.<Long>emptyList(), Collections.<TopLevelChunk>emptyList(),
                Collections.emptyList());
    }

    private static ResourceEvent resource(int trackIndex, int eventIndex, int rawTick, int command, byte[] body) {
        return new ResourceEvent(
                trackIndex, eventIndex, 0, rawTick, command, TrackDecoder.resourceName(command), body, command >= 0xF0);
    }

    private static SystemEvent tempo(
            int trackIndex, int eventIndex, int rawTick, int command, int value, int timebase) {
        return new SystemEvent(
                trackIndex, eventIndex, 0, rawTick, command, value, TrackDecoder.commandName(command), -1, timebase);
    }

    private static ResourceEvent requireResource(TrackEvent event, String label) {
        if (!(event instanceof ResourceEvent)) {
            fail(label, "expected ResourceEvent");
        }
        return (ResourceEvent) event;
    }

    private static void eq(String label, int expected, int actual) {
        if (expected != actual) fail(label, "expected " + expected + ", got " + actual);
    }

    private static void eqText(String label, String expected, String actual) {
        if (!expected.equals(actual)) fail(label, "expected " + expected + ", got " + actual);
    }

    private static void isTrue(String label, boolean value) {
        if (!value) fail(label, "expected true");
    }

    private static void isFalse(String label, boolean value) {
        if (value) fail(label, "expected false");
    }

    private static void fail(String label, String detail) {
        throw new AssertionError(label + ": " + detail);
    }
}
