package mld.semantic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.sound.midi.ShortMessage;

import mld.format.MldDocument;
import mld.format.TrackChunk;
import mld.decode.MachineDependentEvent;
import mld.decode.NoteEvent;
import mld.decode.ReservedEvent;
import mld.decode.SystemEvent;
import mld.decode.DecodedTrack;
import mld.decode.TrackDecoder;
import mld.decode.TrackEvent;
import midi.MidiPlan;

/** Standalone regression audit for ordinary 0xFF system-event rules established across the supplied MFi5 DLL families. */
public final class SystemEventSemanticsAudit {
    public static void main(String[] args) throws Exception {
        auditSystemEnvelopeFraming();
        auditDfTerminatesTrackDecode();
        auditCommandNames();
        auditMasterVolumeForms();
        auditGlobalStopGate();
        auditSessionResetValueGate();
        auditB3NoOp();
        auditPatchHelperCallSites();
        auditPitchRangeDeferredApply();
        auditFinePitchCache();
        auditFiniteMiddleLoopContinuesSuffix();
        auditDecodedFiniteMiddleLoop();
        auditLoopReplaysTimingState();
        auditLoopMetadataTrackGate();
        auditLoopStartOverwrite();
        auditSequentialLoopSlots();
        auditNestedLoopSlots();
        auditCrossTrackLoopBookmark();
        auditCrossingLoopSlots();
        auditLoopNoOpForms();
        auditInfiniteLoopCycle();
        auditInfiniteLoopKeepsExecutingSemanticState();
        auditSuppliedOuterInfiniteWithFiniteSubloops();
        auditZeroDurationLoopTrackStop();
        System.out.println("SystemEventSemanticsAudit: PASS");
    }

    private static void auditSystemEnvelopeFraming() throws Exception {
        DecodedTrack decoded = decode(0, 2, new byte[] {
                // FF command<80: command plus 1+exst body bytes.
                0x00, (byte) 0xFF, 0x10, 0x11, 0x22, 0x33,
                // FF 80..EF: exactly one ordinary-system value byte.
                0x01, (byte) 0xFF, (byte) 0xB2, 0x55,
                // FF F0..FE: BE16 body length and passive long envelope.
                0x01, (byte) 0xFF, (byte) 0xF1, 0x00, 0x02, (byte) 0xAA, (byte) 0xBB,
                // FF FF: BE16 body length and machine-dependent event.
                0x01, (byte) 0xFF, (byte) 0xFF, 0x00, 0x02, 0x66, 0x77,
                // Ordinary note proves final alignment.
                0x01, 0x05, 0x04
        });
        eq("framing event count", 5, decoded.events.size());

        ReservedEvent shortEnvelope = requireReserved(decoded.events.get(0), "short FF envelope");
        eq("short command", 0x10, shortEnvelope.command);
        eq("short body length", 3, shortEnvelope.bodyLength());
        isFalse("short longForm", shortEnvelope.longForm);

        SystemEvent ordinary = requireSystem(decoded.events.get(1), "ordinary FF command");
        eq("ordinary command", 0xB2, ordinary.command);
        eq("ordinary value", 0x55, ordinary.value);
        eq("ordinary raw tick", 1, ordinary.rawTick);

        ReservedEvent longEnvelope = requireReserved(decoded.events.get(2), "long FF envelope");
        eq("long command", 0xF1, longEnvelope.command);
        eq("long body length", 2, longEnvelope.bodyLength());
        isTrue("long longForm", longEnvelope.longForm);

        if (!(decoded.events.get(3) instanceof MachineDependentEvent)) {
            fail("FF FF", "expected MachineDependentEvent, got " + decoded.events.get(3).getClass().getSimpleName());
        }
        MachineDependentEvent machine = (MachineDependentEvent) decoded.events.get(3);
        eq("machine payload length", 2, machine.payloadLength());

        if (!(decoded.events.get(4) instanceof NoteEvent)) {
            fail("framing alignment", "expected trailing NoteEvent");
        }
        eq("framing trailing note tick", 4, decoded.events.get(4).rawTick);
    }

    private static void auditDfTerminatesTrackDecode() throws Exception {
        DecodedTrack decoded = decode(0, 0, new byte[] {
                0x00, (byte) 0xFF, (byte) 0xDF, 0x7E,
                // Native DF marks the current parser context done. This truncated tail must stay unread.
                0x01
        });
        eq("DF decoded event count", 1, decoded.events.size());
        SystemEvent df = requireSystem(decoded.events.get(0), "DF");
        eq("DF command", 0xDF, df.command);
        eq("DF value remains consumed", 0x7E, df.value);
    }

    private static void auditCommandNames() throws Exception {
        DecodedTrack decoded = decode(0, 0, new byte[] {
                0x00, (byte) 0xFF, (byte) 0xB3, 0x40,
                0x00, (byte) 0xFF, (byte) 0xD0, 0x01,
                0x00, (byte) 0xFF, (byte) 0xE4, 0x20,
                0x00, (byte) 0xFF, (byte) 0xE8, 0x20,
                0x00, (byte) 0xFF, (byte) 0xE9, 0x20,
                0x00, (byte) 0xFF, (byte) 0xEA, 0x20
        });
        eqText("B3 generic name", "cmd_B3", requireSystem(decoded.events.get(0), "B3").name);
        eqText("D0 name", "conditional_stop", requireSystem(decoded.events.get(1), "D0").name);
        eqText("E4 name", "pitch_coarse_apply", requireSystem(decoded.events.get(2), "E4").name);
        eqText("E8 name", "pitch_fine_apply", requireSystem(decoded.events.get(3), "E8").name);
        eqText("E9 name", "pitch_fine_cache", requireSystem(decoded.events.get(4), "E9").name);
        eqText("EA conservative name", "backend_control_ea", requireSystem(decoded.events.get(5), "EA").name);
    }

    private static void auditMasterVolumeForms() {
        List<TrackEvent> events = new ArrayList<TrackEvent>();
        events.add(system(0, 0, 0, 0, 0xB0, 80));
        events.add(system(0, 1, 0, 0, 0xBD, 0x41));
        events.add(system(0, 2, 0, 0, 0xBD, 0x00)); // 81 - 64 -> 17
        events.add(system(1, 3, 0, 0, 0xB0, 70)); // track gate rejects
        events.add(system(0, 4, 0, 0, 0xB0, 0x80)); // 7-bit gate rejects
        SemanticTestSupport result = compile(events, 0);
        eq("B0 mapped CC7 count", 16, countCc(result, 0xB0, 7));
        eq("BD mapped CC7 count", 32, countCc(result, 0xBD, 7));
        eq("BD first relative value", 81, firstCcValue(result, 0xBD, 7));
        eq("BD final relative value", 17, lastCcValue(result, 0xBD, 7));
    }

    private static void auditGlobalStopGate() {
        List<TrackEvent> ignored = new ArrayList<TrackEvent>();
        ignored.add(note(0, 0, 0, 20));
        ignored.add(system(0, 1, 5, 5, 0xBE, 1));
        SemanticTestSupport ignoredTimeline = compile(ignored, 20);
        eq("BE nonzero note count", 1, ignoredTimeline.program.melody.notes.size());
        eq("BE nonzero note end", 20, ignoredTimeline.program.melody.notes.get(0).rawEndTick);
        eq("BE nonzero CC120", 0, countCc(ignoredTimeline, 0xBE, 120));

        List<TrackEvent> active = new ArrayList<TrackEvent>();
        active.add(note(0, 0, 0, 20));
        active.add(system(0, 1, 5, 5, 0xBE, 0));
        SemanticTestSupport activeTimeline = compile(active, 20);
        eq("BE zero note count", 1, activeTimeline.program.melody.notes.size());
        eq("BE zero note end", 5, activeTimeline.program.melody.notes.get(0).rawEndTick);
        eq("BE zero CC120", 16, countCc(activeTimeline, 0xBE, 120));
    }

    private static void auditSessionResetValueGate() {
        List<TrackEvent> events = new ArrayList<TrackEvent>();
        events.add(note(0, 0, 0, 20));
        events.add(system(0, 1, 5, 5, 0xBF, 0xFF));
        events.add(system(0, 2, 5, 10, 0xBD, 0x41));
        SemanticTestSupport result = compile(events, 20);
        eq("BF arbitrary value stops active note", 5, result.program.melody.notes.get(0).rawEndTick);
        eq("BF arbitrary value CC120", 16, countCc(result, 0xBF, 120));
        eq("BF master-volume reset feeds BD", 101, firstCcValue(result, 0xBD, 7));
    }

    private static void auditB3NoOp() {
        SemanticTestSupport result = compile(Collections.<TrackEvent>singletonList(
                system(0, 0, 0, 0, 0xB3, 0x40)), 0);
        eq("B3 mapped event count", 0, countSource(result, 0xB3));
        eq("B3 unmapped audit count", 1, countUnmapped(result, 0xB3));
    }

    private static void auditPatchHelperCallSites() {
        List<TrackEvent> events = new ArrayList<TrackEvent>();
        events.add(system(0, 0, 0, 0, 0xBA, 0x02)); // mode2; BA leaves helper state unchanged
        events.add(note(0, 1, 1, 0));                // sounds with initial helper state
        events.add(system(0, 2, 1, 2, 0xE0, 0x05)); // E0 runs helper; mode2 suppresses
        events.add(note(0, 3, 3, 0));                // silent
        events.add(system(0, 4, 1, 4, 0xBA, 0x00)); // mode0; BA leaves helper state unchanged
        events.add(note(0, 5, 5, 0));                // still silent
        events.add(system(0, 6, 1, 6, 0xE0, 0x06)); // E0 runs helper; mode0 clears suppression
        events.add(note(0, 7, 7, 0));                // sounds
        SemanticTestSupport result = compile(events, 7);
        eq("patch helper call-site sounding notes", 2, result.program.melody.notes.size());
        eq("mode2 pre-helper first note start", 1, result.program.melody.notes.get(0).rawStartTick);
        eq("mode0 E0 clears suppression", 7, result.program.melody.notes.get(1).rawStartTick);

        List<TrackEvent> bankMode0 = new ArrayList<TrackEvent>();
        bankMode0.add(system(0, 0, 0, 0, 0xE0, 0x05));
        bankMode0.add(system(0, 1, 5, 5, 0xE1, 0x01));
        bankMode0.add(note(0, 2, 6, 0)); // note path must not commit the bank-only cache change
        bankMode0.add(system(0, 3, 4, 10, 0xE0, 0x06));
        SemanticTestSupport bankTimeline = compile(bankMode0, 10);
        eq("mode0 E1 host patch event count", 0,
                countStatus(bankTimeline, 0xE1, ShortMessage.PROGRAM_CHANGE));
        eq("mode0 note host patch event count", 0,
                countStatus(bankTimeline, -1, ShortMessage.PROGRAM_CHANGE));
        eq("E0 host patch event count", 2,
                countStatus(bankTimeline, 0xE0, ShortMessage.PROGRAM_CHANGE));
    }

    private static void auditPitchRangeDeferredApply() {
        SemanticTestSupport cacheOnly = compile(Collections.<TrackEvent>singletonList(
                system(0, 0, 0, 0, 0xE7, 0x0C)), 0);
        eq("E7 cache emits no MIDI", 0, countSource(cacheOnly, 0xE7));

        List<TrackEvent> apply = new ArrayList<TrackEvent>();
        apply.add(system(0, 0, 0, 0, 0xE7, 0x0C));
        apply.add(system(0, 1, 5, 5, 0xE4, 0x21));
        SemanticTestSupport applied = compile(apply, 5);
        eq("E4 carries deferred RPN controls", 4, countControllerSource(applied, 0xE4));
        eq("E4 carries pitch bend", 1, countStatus(applied, 0xE4, ShortMessage.PITCH_BEND));
        eq("deferred E7 remains MIDI-silent", 0, countSource(applied, 0xE7));

        List<TrackEvent> invalid = new ArrayList<TrackEvent>();
        invalid.add(system(0, 0, 0, 0, 0xE7, 0x19)); // low6=25, rejected by native range gate
        invalid.add(system(0, 1, 5, 5, 0xE4, 0x21));
        SemanticTestSupport invalidTimeline = compile(invalid, 5);
        eq("invalid E7 does not arm RPN", 0, countControllerSource(invalidTimeline, 0xE4));
        eq("invalid E7 still permits E4 bend", 1, countStatus(invalidTimeline, 0xE4, ShortMessage.PITCH_BEND));
    }

    private static void auditFinePitchCache() {
        List<TrackEvent> events = new ArrayList<TrackEvent>();
        events.add(system(0, 0, 0, 0, 0xE9, 40));
        events.add(system(0, 1, 5, 5, 0xE4, 32));
        SemanticTestSupport result = compile(events, 5);
        eq("E9 emits no host control", 0, countSource(result, 0xE9));
        MidiPlan.MappedControlEvent bend = firstStatus(result, 0xE4, ShortMessage.PITCH_BEND);
        int bendValue = (bend.data2 << 7) | bend.data1;
        eq("E9 fine cache feeds E4", 8256, bendValue);
    }

    private static void auditFiniteMiddleLoopContinuesSuffix() {
        List<TrackEvent> events = new ArrayList<TrackEvent>();
        events.add(system(0, 0, 10, 10, 0xDD, 0x00)); // slot0 start
        events.add(system(0, 1, 10, 20, 0xB3, 0x11)); // loop body
        events.add(system(0, 2, 10, 30, 0xDD, 0x05)); // slot0 end, N=1
        events.add(system(0, 3, 10, 40, 0xB3, 0x22)); // suffix
        SemanticTestSupport result = compile(events, 40);

        isTrue("finite middle DD activation", result.program.loop.hasNativeLoops());
        isFalse("finite middle DD has no transport cycle", result.program.loop.hasInfiniteLoop());
        eq("finite middle activation count", 1, result.program.loop.activations.size());
        LoopModel.Activation loop = result.program.loop.activations.get(0);
        eq("finite middle slot", 0, loop.slot);
        eq("finite middle repeat count", 1, loop.repeatCount);
        eq("finite middle start", 10, loop.startRawTick);
        eq("finite middle first end", 30, loop.firstEndRawTick);
        eq("finite middle rewind count", 1, result.program.loop.finiteRewindCount);
        eq("finite middle body executions", 2, countUnmappedValue(result, 0xB3, 0x11));
        eq("finite middle suffix executions", 1, countUnmappedValue(result, 0xB3, 0x22));
        eqInts("finite middle execution ticks", new int[] {20, 40, 60}, unmappedTicks(result, 0xB3));
        eq("finite middle linear end includes suffix", 60, result.program.linearEndRawTick);
    }

    private static void auditDecodedFiniteMiddleLoop() throws Exception {
        DecodedTrack decoded = decode(0, 0, new byte[] {
                0x0A, (byte) 0xFF, (byte) 0xDD, 0x00, // slot0 start @10
                0x0A, (byte) 0xFF, (byte) 0xB3, 0x11, // body @20
                0x0A, (byte) 0xFF, (byte) 0xDD, 0x05, // end N=1 @30
                0x0A, (byte) 0xFF, (byte) 0xB3, 0x22  // suffix @40
        });
        SemanticTestSupport result = SemanticTestSupport.compile(file(0, 0), decoded);

        eq("decoded finite DD rewind count", 1, result.program.loop.finiteRewindCount);
        eq("decoded finite DD body executions", 2, countUnmappedValue(result, 0xB3, 0x11));
        eq("decoded finite DD suffix executions", 1, countUnmappedValue(result, 0xB3, 0x22));
        eqInts("decoded finite DD execution ticks", new int[] {20, 40, 60}, unmappedTicks(result, 0xB3));
        eq("decoded finite DD reaches suffix end", 60, result.program.linearEndRawTick);
    }

    private static void auditLoopReplaysTimingState() {
        List<TrackEvent> events = new ArrayList<TrackEvent>();
        events.add(system(0, 0, 10, 10, 0xDD, 0x00));
        events.add(system(0, 1, 10, 20, 0xBC, 0x41)); // +1 tempo per executed pass
        events.add(system(0, 2, 10, 30, 0xDD, 0x05)); // N=1
        events.add(system(0, 3, 10, 40, 0xB3, 0x22));
        SemanticTestSupport result = compile(events, 40);

        eq("loop timing point count", 3, result.program.timing.points.size());
        eq("loop first relative tempo", 126, result.program.timing.points.get(1).tempo);
        eq("loop second relative tempo", 127, result.program.timing.points.get(2).tempo);
        eq("loop second relative tempo tick", 40, result.program.timing.points.get(2).rawTick);
        eq("loop timing suffix executes once", 1, countUnmappedValue(result, 0xB3, 0x22));
    }

    private static void auditLoopMetadataTrackGate() {
        List<TrackEvent> events = new ArrayList<TrackEvent>();
        events.add(system(1, 0, 10, 10, 0xDD, 0x00));
        events.add(system(1, 1, 10, 20, 0xDD, 0x09));
        events.add(system(0, 0, 30, 30, 0xDD, 0x00));
        events.add(system(0, 1, 10, 40, 0xDD, 0x09)); // slot0, operation1, N=2
        SemanticTestSupport result = compileTracks(events);
        eq("track0 DD activation count", 1, result.program.loop.activations.size());
        LoopModel.Activation loop = result.program.loop.activations.get(0);
        eq("DD loop slot", 0, loop.slot);
        eq("DD repeat nibble", 2, loop.repeatCount);
        eq("DD start", 30, loop.startRawTick);
        eq("DD first end", 40, loop.firstEndRawTick);
        eq("DD N=2 finite rewinds", 2, result.program.loop.finiteRewindCount);
    }

    private static void auditLoopStartOverwrite() {
        List<TrackEvent> events = new ArrayList<TrackEvent>();
        events.add(system(0, 0, 10, 10, 0xDD, 0x00));
        events.add(system(0, 1, 10, 20, 0xDD, 0x00));
        events.add(system(0, 2, 10, 30, 0xB3, 0x11));
        events.add(system(0, 3, 10, 40, 0xDD, 0x05)); // slot0 end, N=1
        events.add(system(0, 4, 10, 50, 0xB3, 0x22));
        SemanticTestSupport result = compile(events, 50);
        eq("DD overwritten activation count", 1, result.program.loop.activations.size());
        LoopModel.Activation loop = result.program.loop.activations.get(0);
        eq("DD overwritten start uses latest snapshot", 20, loop.startRawTick);
        eq("DD overwritten first end", 40, loop.firstEndRawTick);
        eq("DD N=1 additional repeat", 1, loop.repeatCount);
        eqInts("DD overwritten execution ticks", new int[] {30, 50, 70}, unmappedTicks(result, 0xB3));
        eq("DD overwritten suffix end", 70, result.program.linearEndRawTick);
    }

    private static void auditSequentialLoopSlots() {
        List<TrackEvent> events = new ArrayList<TrackEvent>();
        events.add(system(0, 0, 10, 10, 0xDD, 0x00));
        events.add(system(0, 1, 10, 20, 0xB3, 0x10));
        events.add(system(0, 2, 10, 30, 0xDD, 0x05));
        events.add(system(0, 3, 10, 40, 0xDD, 0x40)); // slot1 start
        events.add(system(0, 4, 10, 50, 0xB3, 0x20));
        events.add(system(0, 5, 10, 60, 0xDD, 0x45)); // slot1 end N=1
        events.add(system(0, 6, 10, 70, 0xB3, 0x30));
        SemanticTestSupport result = compile(events, 70);

        eq("sequential DD activation count", 2, result.program.loop.activations.size());
        eq("sequential DD finite rewinds", 2, result.program.loop.finiteRewindCount);
        eq("sequential first slot", 0, result.program.loop.activations.get(0).slot);
        eq("sequential second slot", 1, result.program.loop.activations.get(1).slot);
        eqInts("sequential DD body/suffix ticks",
                new int[] {20, 40, 70, 90, 110}, unmappedTicks(result, 0xB3));
        eq("sequential DD suffix end", 110, result.program.linearEndRawTick);
    }

    private static void auditNestedLoopSlots() {
        List<TrackEvent> events = new ArrayList<TrackEvent>();
        events.add(system(0, 0, 10, 10, 0xDD, 0x00)); // outer slot0
        events.add(system(0, 1, 10, 20, 0xDD, 0x40)); // inner slot1
        events.add(system(0, 2, 10, 30, 0xB3, 0x11));
        events.add(system(0, 3, 10, 40, 0xDD, 0x45)); // inner end N=1
        events.add(system(0, 4, 10, 50, 0xDD, 0x05)); // outer end N=1
        events.add(system(0, 5, 10, 60, 0xB3, 0x22));
        SemanticTestSupport result = compile(events, 60);

        eq("nested DD activation count", 3, result.program.loop.activations.size());
        eq("nested DD activation0 slot", 1, result.program.loop.activations.get(0).slot);
        eq("nested DD activation1 slot", 0, result.program.loop.activations.get(1).slot);
        eq("nested DD activation2 slot", 1, result.program.loop.activations.get(2).slot);
        eq("nested DD finite rewinds", 3, result.program.loop.finiteRewindCount);
        eqInts("nested DD body/suffix ticks",
                new int[] {30, 50, 90, 110, 140}, unmappedTicks(result, 0xB3));
        eq("nested DD suffix end", 140, result.program.linearEndRawTick);
    }

    private static void auditCrossTrackLoopBookmark() {
        List<TrackEvent> events = new ArrayList<TrackEvent>();
        events.add(system(0, 0, 10, 10, 0xDD, 0x00));
        events.add(system(0, 1, 20, 30, 0xDD, 0x05));
        events.add(system(0, 2, 10, 40, 0xB3, 0x22));
        events.add(system(1, 0, 25, 25, 0xB3, 0x11));
        SemanticTestSupport result = compileTracks(events);

        eq("cross-track DD rewind count", 1, result.program.loop.finiteRewindCount);
        eq("cross-track body executions", 2, countUnmappedValue(result, 0xB3, 0x11));
        eq("cross-track suffix executions", 1, countUnmappedValue(result, 0xB3, 0x22));
        eqInts("cross-track bookmark preserves remaining countdown",
                new int[] {25, 45, 60}, unmappedTicks(result, 0xB3));
        eq("cross-track DD suffix end", 60, result.program.linearEndRawTick);
    }

    private static void auditCrossingLoopSlots() {
        List<TrackEvent> events = new ArrayList<TrackEvent>();
        events.add(system(0, 0, 10, 10, 0xDD, 0x00)); // A start
        events.add(system(0, 1, 10, 20, 0xDD, 0x40)); // B start
        events.add(system(0, 2, 5, 25, 0xB3, 0x11));
        events.add(system(0, 3, 5, 30, 0xDD, 0x05)); // A closes before B
        events.add(system(0, 4, 10, 40, 0xDD, 0x45));
        events.add(system(0, 5, 10, 50, 0xB3, 0x22));
        SemanticTestSupport result = compile(events, 50);

        eq("crossing DD activation count", 2, result.program.loop.activations.size());
        eq("crossing DD first activation slot", 0, result.program.loop.activations.get(0).slot);
        eq("crossing DD second activation slot", 1, result.program.loop.activations.get(1).slot);
        eq("crossing DD finite rewinds", 2, result.program.loop.finiteRewindCount);
        eqInts("crossing DD execution ticks",
                new int[] {25, 45, 65, 90}, unmappedTicks(result, 0xB3));
        eq("crossing DD suffix end", 90, result.program.linearEndRawTick);
    }

    private static void auditLoopNoOpForms() {
        List<TrackEvent> events = new ArrayList<TrackEvent>();
        events.add(system(0, 0, 10, 10, 0xDD, 0x05)); // end without a saved start
        events.add(system(0, 1, 10, 20, 0xDD, 0x02)); // operation 2
        events.add(system(0, 2, 10, 30, 0xDD, 0x03)); // operation 3
        events.add(system(0, 3, 10, 40, 0xB3, 0x44));
        SemanticTestSupport result = compile(events, 40);

        isFalse("unmatched/op2/op3 DD has no activation", result.program.loop.hasNativeLoops());
        eq("unmatched/op2/op3 DD has no rewind", 0, result.program.loop.finiteRewindCount);
        eq("unmatched/op2/op3 DD suffix executes", 1, countUnmappedValue(result, 0xB3, 0x44));
        eqInts("unmatched/op2/op3 DD preserves timeline", new int[] {40}, unmappedTicks(result, 0xB3));
    }

    private static void auditInfiniteLoopCycle() {
        List<TrackEvent> events = new ArrayList<TrackEvent>();
        events.add(system(0, 0, 10, 10, 0xDD, 0x00));
        events.add(system(0, 1, 10, 20, 0xB3, 0x11));
        events.add(system(0, 2, 10, 30, 0xDD, 0x01)); // N=0 -> infinite
        events.add(system(0, 3, 10, 40, 0xB3, 0x22)); // unreachable suffix
        SemanticTestSupport result = compile(events, 40);

        isTrue("infinite DD detected", result.program.loop.hasInfiniteLoop());
        eq("infinite DD activation count", 1, result.program.loop.activations.size());
        eq("infinite DD activation repeat", -1, result.program.loop.activations.get(0).repeatCount);
        LoopModel.InfiniteRegion region = result.program.loop.infiniteRegion;
        eq("infinite DD effective cycle slot", 0, region.slot);
        eq("infinite DD transport cycle uses first proven pass start", 10, region.loopStartRawTick);
        eq("infinite DD transport cycle uses first proven pass end", 30, region.loopEndRawTick);
        eq("infinite DD source cycle start", 10, region.sourceStartRawTick);
        eq("infinite DD source cycle end", 30, region.sourceEndRawTick);
        eq("infinite DD body materialized intro+cycle", 2, countUnmappedValue(result, 0xB3, 0x11));
        eq("infinite DD suffix remains unreachable", 0, countUnmappedValue(result, 0xB3, 0x22));
        eqInts("infinite DD executed body ticks", new int[] {20, 40}, unmappedTicks(result, 0xB3));
    }

    private static void auditInfiniteLoopKeepsExecutingSemanticState() {
        List<TrackEvent> events = new ArrayList<TrackEvent>();
        events.add(system(0, 0, 10, 10, 0xDD, 0x00));
        events.add(system(0, 1, 10, 20, 0xBC, 0x41));
        events.add(system(0, 2, 0, 20, 0xBD, 0x41));
        events.add(system(0, 3, 0, 20, 0xE6, 0x1F));
        events.add(system(0, 4, 10, 30, 0xDD, 0x01));
        SemanticTestSupport result = compile(events, 30);

        eq("infinite relative-tempo proof pass", 127,
                result.program.timing.points.get(result.program.timing.points.size() - 1).tempo);
        NativeLoopRuntime runtime = result.program.nativeLoop.openRuntime();
        NativeLoopRuntime.Cycle third = runtime.nextCycle();
        NativeLoopRuntime.Cycle fourth = runtime.nextCycle();
        eq("infinite relative-tempo third pass", 128,
                third.localTiming.points.get(third.localTiming.points.size() - 1).tempo);
        eq("infinite relative-tempo fourth pass", 129,
                fourth.localTiming.points.get(fourth.localTiming.points.size() - 1).tempo);
        eq("infinite relative master-volume third pass", 103,
                lastNativeControl(third.program, 0xBD).masterVolume);
        eq("infinite relative channel-level third pass", 60,
                lastNativeControl(third.program, 0xE6).channel.level);
        eq("infinite relative sampled level third pass", 103,
                third.program.audio.resourceLevel);
        isTrue("infinite relative-tempo cycle duration keeps changing",
                fourth.transportEndMicros - fourth.transportStartMicros
                        < third.transportEndMicros - third.transportStartMicros);
    }

    private static MelodyProgram.NativeControl lastNativeControl(
            NativeProgram program, int sourceCommand) {
        MelodyProgram.NativeControl result = null;
        for (MelodyProgram.NativeControl control : program.melody.controls) {
            if (control.sourceCommand == sourceCommand) result = control;
        }
        if (result == null) fail("native control", "missing source " + hex(sourceCommand));
        return result;
    }

    private static void auditSuppliedOuterInfiniteWithFiniteSubloops() {
        // DD topology extracted from supplied 7.mld (title: stage04):
        // slot2 infinite surrounds slot0 N=1 and slot1 N=3 finite subloops.
        List<TrackEvent> events = new ArrayList<TrackEvent>();
        events.add(system(0, 0, 24, 24, 0xDD, 0x80));       // slot2 outer start
        events.add(system(0, 1, 3072, 3096, 0xDD, 0x00));   // slot0 start
        events.add(system(0, 2, 404, 3500, 0xB3, 0x10));    // slot0 body evidence
        events.add(system(0, 3, 1132, 4632, 0xDD, 0x05));   // slot0 end N=1
        events.add(system(0, 4, 368, 5000, 0xB3, 0x11));    // must continue after slot0
        events.add(system(0, 5, 2704, 7704, 0xDD, 0x40));   // slot1 start
        events.add(system(0, 6, 296, 8000, 0xB3, 0x20));    // slot1 body evidence
        events.add(system(0, 7, 1240, 9240, 0xDD, 0x4D));   // slot1 end N=3
        events.add(system(0, 8, 60, 9300, 0xB3, 0x21));     // must continue after slot1
        events.add(system(0, 9, 132, 9432, 0xDD, 0x81));    // slot2 end N=0 -> infinite
        events.add(system(0, 10, 168, 9600, 0xB3, 0x30));   // unreachable outer suffix
        SemanticTestSupport result = compile(events, 9600);

        isTrue("supplied topology has native infinite cycle", result.program.loop.hasInfiniteLoop());
        eq("supplied topology materialized activation count", 5, result.program.loop.activations.size());
        eq("supplied topology finite rewinds during transient+cycle probe",
                8, result.program.loop.finiteRewindCount);
        eq("supplied topology slot0 body executes twice per outer pass",
                4, countUnmappedValue(result, 0xB3, 0x10));
        eq("supplied topology slot0 suffix continues once per outer pass",
                2, countUnmappedValue(result, 0xB3, 0x11));
        eq("supplied topology slot1 body executes four times per outer pass",
                8, countUnmappedValue(result, 0xB3, 0x20));
        eq("supplied topology slot1 suffix continues once per outer pass",
                2, countUnmappedValue(result, 0xB3, 0x21));
        eq("supplied topology outer suffix remains unreachable",
                0, countUnmappedValue(result, 0xB3, 0x30));

        LoopModel.InfiniteRegion region = result.program.loop.infiniteRegion;
        eq("supplied topology residual infinite slot", 2, region.slot);
        eq("supplied topology transport cycle starts at first outer start", 24, region.loopStartRawTick);
        eq("supplied topology transport cycle ends after first expanded outer pass", 15576, region.loopEndRawTick);
        eq("supplied topology source cycle start", 24, region.sourceStartRawTick);
        eq("supplied topology source cycle end", 9432, region.sourceEndRawTick);
    }

    private static void auditZeroDurationLoopTrackStop() {
        List<TrackEvent> events = new ArrayList<TrackEvent>();
        events.add(system(0, 0, 10, 10, 0xDD, 0x00));
        events.add(system(0, 1, 0, 10, 0xDD, 0x05));
        events.add(system(0, 2, 0, 10, 0xB3, 70));
        events.add(system(1, 0, 10, 10, 0xB3, 60));
        events.add(system(0, 3, 10, 20, 0xC3, 200));
        SemanticTestSupport result = compileTracks(events);
        isFalse("zero-duration DD has no activation", result.program.loop.hasNativeLoops());
        eq("zero-duration DD suppresses later same-tick track0 event", 0, countUnmapped(result, 0xB3));
        eq("zero-duration DD truncates later tempo state", 125,
                result.program.timing.points.get(result.program.timing.points.size() - 1).tempo);
        eq("zero-duration DD native end", 10, result.program.linearEndRawTick);
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
        return SemanticTestSupport.compile(file(0, 0), decoded);
    }

    private static SemanticTestSupport compileTracks(List<TrackEvent> events) {
        int maxTrack = 0;
        for (TrackEvent event : events) maxTrack = Math.max(maxTrack, event.trackIndex);
        List<DecodedTrack> tracks = new ArrayList<DecodedTrack>();
        for (int trackIndex = 0; trackIndex <= maxTrack; trackIndex++) {
            List<TrackEvent> trackEvents = new ArrayList<TrackEvent>();
            int noteCount = 0;
            int systemCount = 0;
            int totalRawTicks = 0;
            for (TrackEvent event : events) {
                if (event.trackIndex != trackIndex) continue;
                trackEvents.add(event);
                totalRawTicks = Math.max(totalRawTicks, event.rawTick);
                if (event instanceof NoteEvent) noteCount++;
                if (event instanceof SystemEvent) systemCount++;
            }
            tracks.add(new DecodedTrack(
                    trackIndex, 0, totalRawTicks, noteCount, 0, systemCount, 0,
                    trackEvents, Collections.<String>emptyList()));
        }
        return SemanticTestSupport.compile(file(0, 0), tracks);
    }

    private static MldDocument file(int noteExtraBytes, int exstSize) {
        return new MldDocument(
                new byte[0], "melo", 0, 0, 0, 0, 1, noteExtraBytes, exstSize,
                Collections.<Long>emptyList(), Collections.emptyList(), Collections.emptyList());
    }

    private static NoteEvent note(int trackIndex, int eventIndex, int rawTick, int gate) {
        return new NoteEvent(trackIndex, eventIndex, 0, rawTick, 0x05, 0, 5, gate, 20, 0, 0);
    }

    private static SystemEvent system(int trackIndex, int eventIndex, int delta, int rawTick, int command, int value) {
        int part = command >= 0xE0 && command <= 0xEF ? ((value >> 6) & 0x03) : -1;
        int timebase = -1;
        if (command >= 0xC0 && command <= 0xCF) {
            int[] table = new int[] { 6, 12, 24, 48, 96, 192, 384, 0, 15, 30, 60, 120, 240, 480, 960, 0 };
            timebase = table[command & 0x0F];
        }
        return new SystemEvent(trackIndex, eventIndex, delta, rawTick, command, value, "audit", part, timebase);
    }

    private static int countSource(SemanticTestSupport result, int sourceCommand) {
        int count = 0;
        for (MidiPlan.MappedControlEvent event : result.midi.mappedControls) {
            if (event.sourceCommand == sourceCommand) count++;
        }
        return count;
    }

    private static int countUnmappedValue(SemanticTestSupport result, int sourceCommand, int value) {
        int count = 0;
        for (MelodyProgram.UnmappedControl event : result.program.melody.unmappedControls) {
            if (event.sourceCommand == sourceCommand && event.value == value) count++;
        }
        return count;
    }

    private static int[] unmappedTicks(SemanticTestSupport result, int sourceCommand) {
        List<Integer> ticks = new ArrayList<Integer>();
        for (MelodyProgram.UnmappedControl event : result.program.melody.unmappedControls) {
            if (event.sourceCommand == sourceCommand) ticks.add(Integer.valueOf(event.rawTick));
        }
        int[] values = new int[ticks.size()];
        for (int i = 0; i < ticks.size(); i++) values[i] = ticks.get(i).intValue();
        return values;
    }

    private static int countUnmapped(SemanticTestSupport result, int sourceCommand) {
        int count = 0;
        for (MelodyProgram.UnmappedControl event : result.program.melody.unmappedControls) {
            if (event.sourceCommand == sourceCommand) count++;
        }
        return count;
    }

    private static int countCc(SemanticTestSupport result, int sourceCommand, int controller) {
        int count = 0;
        for (MidiPlan.MappedControlEvent event : result.midi.mappedControls) {
            if (event.sourceCommand == sourceCommand
                    && event.status == ShortMessage.CONTROL_CHANGE
                    && event.data1 == controller) count++;
        }
        return count;
    }

    private static int firstCcValue(SemanticTestSupport result, int sourceCommand, int controller) {
        for (MidiPlan.MappedControlEvent event : result.midi.mappedControls) {
            if (event.sourceCommand == sourceCommand
                    && event.status == ShortMessage.CONTROL_CHANGE
                    && event.data1 == controller) return event.data2;
        }
        fail("first CC", "missing source " + hex(sourceCommand) + " controller " + controller);
        return -1;
    }

    private static int lastCcValue(SemanticTestSupport result, int sourceCommand, int controller) {
        int value = -1;
        for (MidiPlan.MappedControlEvent event : result.midi.mappedControls) {
            if (event.sourceCommand == sourceCommand
                    && event.status == ShortMessage.CONTROL_CHANGE
                    && event.data1 == controller) value = event.data2;
        }
        if (value < 0) fail("last CC", "missing source " + hex(sourceCommand) + " controller " + controller);
        return value;
    }

    private static int countControllerSource(SemanticTestSupport result, int sourceCommand) {
        int count = 0;
        for (MidiPlan.MappedControlEvent event : result.midi.mappedControls) {
            if (event.sourceCommand == sourceCommand && event.status == ShortMessage.CONTROL_CHANGE) count++;
        }
        return count;
    }

    private static int countStatus(SemanticTestSupport result, int sourceCommand, int status) {
        int count = 0;
        for (MidiPlan.MappedControlEvent event : result.midi.mappedControls) {
            if (event.sourceCommand == sourceCommand && event.status == status) count++;
        }
        return count;
    }

    private static MidiPlan.MappedControlEvent firstStatus(
            SemanticTestSupport result, int sourceCommand, int status) {
        for (MidiPlan.MappedControlEvent event : result.midi.mappedControls) {
            if (event.sourceCommand == sourceCommand && event.status == status) return event;
        }
        fail("mapped status", "missing source " + hex(sourceCommand) + " status " + status);
        return null;
    }

    private static ReservedEvent requireReserved(TrackEvent event, String name) {
        if (!(event instanceof ReservedEvent)) fail(name, "expected ReservedEvent, got " + event.getClass().getSimpleName());
        return (ReservedEvent) event;
    }

    private static SystemEvent requireSystem(TrackEvent event, String name) {
        if (!(event instanceof SystemEvent)) fail(name, "expected SystemEvent, got " + event.getClass().getSimpleName());
        return (SystemEvent) event;
    }

    private static void eq(String name, int expected, int actual) {
        if (expected != actual) fail(name, "expected " + expected + ", got " + actual);
    }

    private static void eqLong(String name, long expected, long actual) {
        if (expected != actual) fail(name, "expected " + expected + ", got " + actual);
    }

    private static void eqInts(String name, int[] expected, int[] actual) {
        if (!java.util.Arrays.equals(expected, actual)) {
            fail(name, "expected " + java.util.Arrays.toString(expected)
                    + ", got " + java.util.Arrays.toString(actual));
        }
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

    private static String hex(int value) {
        return String.format("0x%02X", value & 0xFF);
    }

    private static void fail(String name, String message) {
        throw new AssertionError(name + ": " + message);
    }
}
