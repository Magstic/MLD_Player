package midi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;

/** Regression audit for the single MIDI serializer and its plan transformations. */
public final class MidiSerializationAudit {
    private MidiSerializationAudit() {
    }

    public static void main(String[] args) throws Exception {
        auditSameTickOrdering();
        auditFiniteLoopMaterialization();
        auditSegmentPrimingAndClipping();
        System.out.println("MidiSerializationAudit: PASS");
    }

    private static void auditSameTickOrdering() throws Exception {
        List<MidiPlan.CompiledNote> notes = new ArrayList<MidiPlan.CompiledNote>();
        notes.add(note(60, 0L, 10L, 0, 0));
        notes.add(note(62, 10L, 20L, 0, 1));

        List<MidiPlan.MappedControlEvent> controls = new ArrayList<MidiPlan.MappedControlEvent>();
        controls.add(control(ShortMessage.CONTROL_CHANGE, 10, 64, 10L, 2));
        controls.add(control(ShortMessage.CONTROL_CHANGE, 7, 100, 10L, 1));

        MidiPlan plan = plan(noLoop(), tempos(tempo(0L, 500000)), notes, controls, 20L);
        Sequence sequence = new MidiSequenceEncoder().encode(plan).sequence;
        List<ShortMessage> messages = shortMessagesAt(sequence.getTracks()[1], 10L);

        eq("same-tick message count", 4, messages.size());
        eq("note-off phase", ShortMessage.NOTE_OFF, messages.get(0).getCommand());
        eq("first control phase", ShortMessage.CONTROL_CHANGE, messages.get(1).getCommand());
        eq("first control source order", 7, messages.get(1).getData1());
        eq("second control phase", ShortMessage.CONTROL_CHANGE, messages.get(2).getCommand());
        eq("second control source order", 10, messages.get(2).getData1());
        eq("note-on phase", ShortMessage.NOTE_ON, messages.get(3).getCommand());
    }

    private static void auditFiniteLoopMaterialization() throws Exception {
        MidiPlan.LoopInfo loop = new MidiPlan.LoopInfo(
                true, 0, 2, 10, 20, 100L, 200L, Collections.<String>emptyList());
        List<MidiPlan.TempoPoint> tempos = tempos(
                tempo(0L, 500000),
                tempo(50L, 480000),
                tempo(120L, 460000),
                tempo(180L, 440000),
                tempo(220L, 420000));
        List<MidiPlan.CompiledNote> notes = new ArrayList<MidiPlan.CompiledNote>();
        notes.add(note(60, 80L, 150L, 0, 0));
        notes.add(note(62, 150L, 220L, 0, 1));
        notes.add(note(64, 210L, 230L, 0, 2));
        List<MidiPlan.MappedControlEvent> controls = new ArrayList<MidiPlan.MappedControlEvent>();
        controls.add(control(ShortMessage.CONTROL_CHANGE, 7, 90, 50L, 0));
        controls.add(control(ShortMessage.CONTROL_CHANGE, 10, 64, 130L, 1));
        controls.add(control(ShortMessage.CONTROL_CHANGE, 1, 20, 210L, 2));

        MidiPlan source = plan(loop, tempos, notes, controls, 230L);
        MidiPlan materialized = new MidiLoopMaterializer().materializeFiniteLoop(source, 2);

        if (!materialized.loopInfo.materializedLoopPasses) {
            fail("materialized flag", "finite loop plan was not marked materialized");
        }
        eq("materialized passes", 3, materialized.loopInfo.totalLoopPasses);
        eq("materialized note count", 4, materialized.notes.size());
        eq("materialized control count", 4, materialized.mappedControls.size());
        eqLong("cross-boundary pass 1 end", 220L, materialized.notes.get(1).midiEndTick);
        eqLong("cross-boundary pass 2 end", 320L, materialized.notes.get(2).midiEndTick);
        eqLong("cross-boundary pass 3 end", 420L, materialized.notes.get(3).midiEndTick);
        eqLong("materialized content end", 420L, materialized.totalMidiTicks);

        MidiSequenceEncoder.EncodedSequence encoded = new MidiSequenceEncoder().encode(materialized);
        eqLong("encoded materialized end", 420L, encoded.contentEndTick);
        eqLong("encoded EOT", 421L, endOfTrackTick(encoded.sequence.getTracks()[0]));
    }


    private static void auditSegmentPrimingAndClipping() throws Exception {
        List<MidiPlan.TempoPoint> tempos = tempos(
                tempo(0L, 500000),
                tempo(80L, 480000),
                tempo(120L, 460000));
        List<MidiPlan.CompiledNote> notes = Collections.singletonList(note(60, 90L, 110L, 0, 0));
        List<MidiPlan.MappedControlEvent> controls = new ArrayList<MidiPlan.MappedControlEvent>();
        controls.add(control(ShortMessage.PROGRAM_CHANGE, 5, 0, 80L, 0));
        controls.add(control(ShortMessage.CONTROL_CHANGE, 7, 90, 100L, 1));

        MidiPlan source = plan(noLoop(), tempos, notes, controls, 150L);
        MidiPlan segment = new MidiPlanSegmenter().slice(source, 100L, 140L, true);

        eqLong("segment length", 40L, segment.totalMidiTicks);
        eq("segment tempo count", 2, segment.tempoPoints.size());
        eqLong("segment active tempo tick", 0L, segment.tempoPoints.get(0).midiTick);
        eqLong("segment later tempo tick", 20L, segment.tempoPoints.get(1).midiTick);
        eq("segment clipped note count", 1, segment.notes.size());
        eqLong("segment clipped note start", 0L, segment.notes.get(0).midiStartTick);
        eqLong("segment clipped note end", 10L, segment.notes.get(0).midiEndTick);

        Sequence sequence = new MidiSequenceEncoder().encode(
                segment,
                MidiSequenceEncoder.TrackNames.exportSegment("Loop")).sequence;
        List<ShortMessage> tickZero = shortMessagesAt(sequence.getTracks()[1], 0L);
        eq("primed + boundary control + note-on", 3, tickZero.size());
        eq("primed program first", ShortMessage.PROGRAM_CHANGE, tickZero.get(0).getCommand());
        eq("boundary volume second", 7, tickZero.get(1).getData1());
        eq("clipped note-on last", ShortMessage.NOTE_ON, tickZero.get(2).getCommand());
    }

    private static MidiPlan plan(
            MidiPlan.LoopInfo loop,
            List<MidiPlan.TempoPoint> tempos,
            List<MidiPlan.CompiledNote> notes,
            List<MidiPlan.MappedControlEvent> controls,
            long totalTicks) {
        return new MidiPlan(
                tempos,
                loop,
                Collections.<MidiPlan.ChannelAssignment>emptyList(),
                Collections.<MidiPlan.OutputLaneAudit>emptyList(),
                notes,
                controls,
                totalTicks,
                Collections.<String>emptyList());
    }

    private static MidiPlan.LoopInfo noLoop() {
        return new MidiPlan.LoopInfo(false, -1, 0, -1, -1, -1L, -1L, Collections.<String>emptyList());
    }

    private static MidiPlan.TempoPoint tempo(long midiTick, int mpqn) {
        return new MidiPlan.TempoPoint(0, midiTick, 48, 120, mpqn, false);
    }

    private static List<MidiPlan.TempoPoint> tempos(MidiPlan.TempoPoint... points) {
        List<MidiPlan.TempoPoint> result = new ArrayList<MidiPlan.TempoPoint>();
        Collections.addAll(result, points);
        return result;
    }

    private static MidiPlan.CompiledNote note(
            int midiNote,
            long startTick,
            long endTick,
            int sourceTrack,
            int sourceVoice) {
        return new MidiPlan.CompiledNote(
                sourceTrack,
                sourceVoice,
                0,
                0,
                1,
                midiNote,
                100,
                0,
                0,
                startTick,
                endTick);
    }

    private static MidiPlan.MappedControlEvent control(
            int status,
            int data1,
            int data2,
            long midiTick,
            int order) {
        return new MidiPlan.MappedControlEvent(
                0,
                0,
                "audit",
                0,
                0,
                0,
                1,
                midiTick,
                status,
                data1,
                data2,
                -1,
                -1,
                -1,
                null,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                "audit",
                true,
                order,
                order);
    }

    private static List<ShortMessage> shortMessagesAt(Track track, long tick) {
        List<ShortMessage> result = new ArrayList<ShortMessage>();
        for (int index = 0; index < track.size(); index++) {
            MidiEvent event = track.get(index);
            if (event.getTick() != tick) {
                continue;
            }
            MidiMessage message = event.getMessage();
            if (message instanceof ShortMessage) {
                result.add((ShortMessage) message);
            }
        }
        return result;
    }

    private static long endOfTrackTick(Track track) {
        for (int index = 0; index < track.size(); index++) {
            MidiEvent event = track.get(index);
            byte[] bytes = event.getMessage().getMessage();
            if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0x2F) {
                return event.getTick();
            }
        }
        return -1L;
    }

    private static void eq(String label, int expected, int actual) {
        if (expected != actual) {
            fail(label, "expected " + expected + ", got " + actual);
        }
    }

    private static void eqLong(String label, long expected, long actual) {
        if (expected != actual) {
            fail(label, "expected " + expected + ", got " + actual);
        }
    }

    private static void fail(String label, String detail) {
        throw new AssertionError(label + ": " + detail);
    }
}
