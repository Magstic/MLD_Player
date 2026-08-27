package mld.semantic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import mld.format.MldDocument;
import mld.format.TrackChunk;
import mld.decode.NoteEvent;
import mld.decode.ReservedEvent;
import mld.decode.SystemEvent;
import mld.decode.DecodedTrack;
import mld.decode.TrackDecoder;
import mld.decode.TrackEvent;

/** Standalone regression audit for ordinary-note rules established across the supplied MFi5 DLL families. */
public final class OrdinaryNoteSemanticsAudit {
    public static void main(String[] args) throws Exception {
        auditNoteLayouts();
        auditReservedStatusSentinel();
        auditPitchVelocityAndGate();
        auditGateRefresh();
        auditExpiryTie();
        auditZeroGateMidiBoundary();
        auditPatchModeHelperBoundary();
        auditSilentGateState();
        auditUnrepresentableNativeGateDoesNotExtendMidi();
        auditSoundingGateStateSurvivesSuppression();
        System.out.println("OrdinaryNoteSemanticsAudit: PASS");
    }

    private static void auditNoteLayouts() throws Exception {
        DecodedTrack noExtra = decode(0, 0, new byte[] {
                0x00, 0x0A, 0x05
        });
        NoteEvent note0 = requireNote(noExtra.events.get(0), "note=0");
        eq("note=0 velocity", 63, note0.velocity);
        eq("note=0 octave", 0, note0.octaveShift);
        if (!noExtra.warnings.isEmpty()) {
            fail("note=0", "native-valid zero-extra layout must not be reported as a fallback");
        }

        DecodedTrack extra3 = decode(3, 0, new byte[] {
                0x00, 0x4A, 0x05, 0x46, (byte) 0xAA, (byte) 0xBB,
                0x01, 0x0B, 0x06, 0x00, 0x11, 0x22
        });
        eq("note=3 event count", 2, extra3.events.size());
        NoteEvent first = requireNote(extra3.events.get(0), "note=3 first");
        eq("note=3 velocity", 17, first.velocity);
        eq("note=3 octave", 2, first.octaveShift);
        eq("note=3 first raw tick", 0, first.rawTick);
        NoteEvent second = requireNote(extra3.events.get(1), "note=3 second");
        eq("note=3 alignment", 1, second.rawTick);
        eq("note=3 reserved bytes consumed", 0x0B, second.pitch);
    }

    private static void auditReservedStatusSentinel() throws Exception {
        DecodedTrack decoded = decode(1, 2, new byte[] {
                // status 0x3F is not pitch 63: command<0x80 consumes 1+exst bytes.
                0x00, 0x3F, 0x10, 0x01, 0x02, 0x03,
                // A real note follows; successful alignment proves the reserved envelope length.
                0x01, 0x05, 0x04, 0x00
        });
        eq("reserved event count", 2, decoded.events.size());
        if (!(decoded.events.get(0) instanceof ReservedEvent)) {
            fail("reserved status", "0x3F was decoded as " + decoded.events.get(0).getClass().getSimpleName());
        }
        ReservedEvent reserved = (ReservedEvent) decoded.events.get(0);
        eq("reserved status byte", 0x3F, reserved.status);
        eq("reserved command", 0x10, reserved.command);
        eq("reserved short body", 3, reserved.bodyLength());
        NoteEvent following = requireNote(decoded.events.get(1), "reserved-following note");
        eq("reserved alignment raw tick", 1, following.rawTick);
        eq("reserved alignment pitch", 5, following.pitch);
    }

    private static void auditPitchVelocityAndGate() {
        List<TrackEvent> events = new ArrayList<TrackEvent>();
        events.add(note(0, 0, 0, 0, 10, 4, 17, 2, 1));
        SemanticTestSupport result = compile(events, 4);
        eq("mapped note count", 1, result.program.melody.notes.size());
        MelodyProgram.NativeNote note = result.program.melody.notes.get(0);
        eq("mode0 pitch + octave", 31, result.midi.notes.get(0).midiNote); // 45 + 10 - 24
        eq("velocity x2", 34, note.velocity);
        eq("gate raw end", 4, note.rawEndTick);
        eqLong("gate midi end", 160L, result.midi.notes.get(0).midiEndTick);

        SemanticTestSupport noExtra = compile(
                Collections.<TrackEvent>singletonList(note(0, 0, 0, 0, 10, 1, 63, 0, 0)), 1);
        eq("no-extra velocity", 126, noExtra.program.melody.notes.get(0).velocity);
    }

    private static void auditGateRefresh() {
        List<TrackEvent> events = new ArrayList<TrackEvent>();
        events.add(note(0, 0, 0, 0, 10, 10, 20, 0, 1));
        events.add(note(1, 5, 5, 0, 10, 20, 40, 0, 1));
        SemanticTestSupport result = compile(events, 25);
        eq("gate refresh note count", 1, result.program.melody.notes.size());
        MelodyProgram.NativeNote note = result.program.melody.notes.get(0);
        eq("gate refresh start", 0, note.rawStartTick);
        eq("gate refresh end", 25, note.rawEndTick);
        eq("gate refresh keeps first velocity", 40, note.velocity); // first attr velocity 20 -> MIDI 40
    }

    private static void auditExpiryTie() {
        List<TrackEvent> events = new ArrayList<TrackEvent>();
        events.add(note(0, 0, 0, 0, 10, 5, 20, 0, 1));
        events.add(note(1, 5, 5, 0, 10, 5, 30, 0, 1));
        SemanticTestSupport result = compile(events, 10);
        eq("expiry tie produces retrigger", 2, result.program.melody.notes.size());
        MelodyProgram.NativeNote first = result.program.melody.notes.get(0);
        MelodyProgram.NativeNote second = result.program.melody.notes.get(1);
        eq("expiry tie first end", 5, first.rawEndTick);
        eq("expiry tie second start", 5, second.rawStartTick);
    }

    private static void auditZeroGateMidiBoundary() {
        SemanticTestSupport result = compile(
                Collections.<TrackEvent>singletonList(note(0, 0, 0, 0, 10, 0, 20, 0, 1)), 0);
        eq("zero gate sounding note count", 1, result.program.melody.notes.size());
        MelodyProgram.NativeNote note = result.program.melody.notes.get(0);
        eq("zero gate raw end remains exact", 0, note.rawEndTick);
        eqLong("zero gate MIDI representation gets minimum duration", 1L, result.midi.notes.get(0).midiEndTick);
        eqLong("zero gate MIDI duration contributes to timeline end", 1L, result.midi.totalMidiTicks);
    }

    private static void auditPatchModeHelperBoundary() {
        List<TrackEvent> events = new ArrayList<TrackEvent>();
        events.add(system(0, 0, 0, 0xBA, 0x02)); // BA mode 2 updates mode only.
        events.add(note(1, 0, 0, 0, 10, 5, 20, 0, 1));
        SemanticTestSupport result = compile(events, 5);
        eq("BA mode2 alone keeps current suppression flag", 1, result.program.melody.notes.size());
        eq("mode2 ordinary pitch base", 55, result.midi.notes.get(0).midiNote);
    }

    private static void auditSilentGateState() {
        List<TrackEvent> events = new ArrayList<TrackEvent>();
        events.add(system(0, 0, 0, 0xBA, 0x02)); // mode cache -> 2
        events.add(system(1, 0, 0, 0xE0, 0x00)); // patch helper observes mode 2 -> suppression set
        events.add(note(2, 0, 0, 0, 10, 10, 20, 0, 1));
        events.add(system(3, 5, 5, 0xBA, 0x00)); // mode cache -> 0; helper is not called
        events.add(note(4, 0, 5, 0, 10, 10, 30, 0, 1));
        SemanticTestSupport result = compile(events, 15);
        eq("silent gate retrigger remains silent", 0, result.program.melody.notes.size());
        eq("silent gate schedule", 2, result.program.melody.gateSchedules.size());
        eqLong("silent gate still participates in scheduler duration", 600L, result.midi.totalMidiTicks);
    }

    private static void auditUnrepresentableNativeGateDoesNotExtendMidi() {
        List<TrackEvent> events = new ArrayList<TrackEvent>();
        events.add(system(0, 1, 1, 0xE5, 0x10));
        events.add(note(1, 0, 1, 0, 10, 20, 20, 0, 1));
        SemanticTestSupport result = compile(events, 1);
        eq("native-only gate schedule count", 1, result.program.melody.gateSchedules.size());
        eq("native-only sounding note count", 1, result.program.melody.notes.size());
        eq("native-only logical channel", 16, result.program.melody.gateSchedules.get(0).logicalChannel);
        eq("native linear end", 1, result.program.linearEndRawTick);
        eq("native semantic end includes gate", 21, result.program.semanticEndRawTick);
        eq("unrepresentable MIDI note count", 0, result.midi.notes.size());
        eqLong("unrepresentable gate does not extend MIDI end", 40L, result.midi.totalMidiTicks);
    }

    private static void auditSoundingGateStateSurvivesSuppression() {
        List<TrackEvent> events = new ArrayList<TrackEvent>();
        events.add(note(0, 0, 0, 0, 10, 10, 20, 0, 1));
        events.add(system(1, 5, 5, 0xBA, 0x02));
        events.add(system(2, 0, 5, 0xE0, 0x00)); // helper sets suppression for future note-on
        events.add(note(3, 0, 5, 0, 10, 10, 30, 0, 1));
        SemanticTestSupport result = compile(events, 15);
        eq("sounding gate remains one note after suppressed refresh", 1, result.program.melody.notes.size());
        MelodyProgram.NativeNote note = result.program.melody.notes.get(0);
        eq("sounding gate refreshed end", 15, note.rawEndTick);
        eq("sounding gate retains original velocity", 40, note.velocity);
    }

    private static DecodedTrack decode(int noteExtraBytes, int exstSize, byte[] payload) throws Exception {
        MldDocument file = file(noteExtraBytes, exstSize);
        return new TrackDecoder().decode(file, new TrackChunk(0, 0, payload.length, payload));
    }

    private static SemanticTestSupport compile(List<TrackEvent> events, int totalRawTicks) {
        int noteCount = 0;
        int systemCount = 0;
        for (TrackEvent event : events) {
            if (event instanceof NoteEvent) noteCount++;
            if (event instanceof SystemEvent) systemCount++;
        }
        DecodedTrack decoded = new DecodedTrack(
                0, 0, totalRawTicks, noteCount, 0, systemCount, 0,
                events, Collections.<String>emptyList());
        return SemanticTestSupport.compile(file(1, 0), decoded);
    }

    private static MldDocument file(int noteExtraBytes, int exstSize) {
        return new MldDocument(
                new byte[0], "melo", 0, 0, 0, 0, 1, noteExtraBytes, exstSize,
                Collections.<Long>emptyList(), Collections.emptyList(), Collections.emptyList());
    }

    private static NoteEvent note(
            int eventIndex,
            int delta,
            int rawTick,
            int voice,
            int pitch,
            int gate,
            int velocity,
            int octaveShift,
            int noteExtraBytes) {
        int status = ((voice & 0x03) << 6) | (pitch & 0x3F);
        return new NoteEvent(0, eventIndex, delta, rawTick, status, voice, pitch, gate, velocity, octaveShift, noteExtraBytes);
    }

    private static SystemEvent system(int eventIndex, int delta, int rawTick, int command, int value) {
        int part = command >= 0xE0 && command <= 0xEF ? ((value >> 6) & 0x03) : -1;
        return new SystemEvent(0, eventIndex, delta, rawTick, command, value, "audit", part, -1);
    }

    private static NoteEvent requireNote(TrackEvent event, String name) {
        if (!(event instanceof NoteEvent)) {
            fail(name, "expected NoteEvent, got " + event.getClass().getSimpleName());
        }
        return (NoteEvent) event;
    }

    private static void eq(String name, int expected, int actual) {
        if (expected != actual) fail(name, "expected " + expected + ", got " + actual);
    }

    private static void eqLong(String name, long expected, long actual) {
        if (expected != actual) fail(name, "expected " + expected + ", got " + actual);
    }

    private static void fail(String name, String message) {
        throw new AssertionError(name + ": " + message);
    }
}
