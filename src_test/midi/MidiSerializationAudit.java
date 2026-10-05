package midi;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;

import mld.compile.MldCompilation;
import mld.compile.MldCompiler;
import mld.semantic.MelodyProgram;
import mld.semantic.NativeLoopRuntime;

/** Regression audit for the single MIDI serializer and its plan transformations. */
public final class MidiSerializationAudit {
    private MidiSerializationAudit() {
    }

    public static void main(String[] args) throws Exception {
        auditSameTickOrdering();
        auditCausalOrdering();
        auditResetControlOrdering();
        auditInfiniteLoopEncodingBoundary();
        auditSegmentPrimingAndClipping();
        auditControlProvenanceThroughTransforms();
        auditPatchEvidence(false);
        auditPatchEvidence(true);
        auditControlOnlyLaneIsolation(false);
        auditControlOnlyLaneIsolation(true);
        System.out.println("MidiSerializationAudit: PASS");
    }

    private static void auditSameTickOrdering() throws Exception {
        List<MidiPlan.CompiledNote> notes = new ArrayList<MidiPlan.CompiledNote>();
        notes.add(note(60, 0L, 10L, 0, 1));
        notes.add(note(62, 10L, 20L, 3, 4));

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

    private static void auditCausalOrdering() throws Exception {
        MldCompilation patch = compile(0, 255, 224, 1, 0, 0, 10,
                0, 255, 224, 2, 2, 255, 223, 0);
        List<ShortMessage> patchMessages = causalMessages(
                new MidiSequenceEncoder().encode(patch.getMidiPlan()).sequence, 0L);
        commands("patch follows earlier note", patchMessages,
                ShortMessage.PROGRAM_CHANGE, ShortMessage.PROGRAM_CHANGE,
                ShortMessage.NOTE_ON, ShortMessage.PROGRAM_CHANGE);
        eq("source patch", 9, patchMessages.get(0).getData1());
        eq("first note patch", 9, patchMessages.get(1).getData1());
        eq("later patch", 16, patchMessages.get(3).getData1());

        MldCompilation retrigger = compile(0, 0, 0, 0, 0, 2, 2, 255, 223, 0);
        Sequence retriggerMidi = new MidiSequenceEncoder().encode(retrigger.getMidiPlan()).sequence;
        commands("zero gate expires before retrigger", causalMessages(retriggerMidi, 0L),
                ShortMessage.PROGRAM_CHANGE, ShortMessage.NOTE_ON,
                ShortMessage.NOTE_OFF, ShortMessage.PROGRAM_CHANGE, ShortMessage.NOTE_ON);
        balanced(retriggerMidi);
        MldCompilation finalZero = compile(5, 0, 0);
        Sequence finalZeroMidi = new MidiSequenceEncoder().encode(finalZero.getMidiPlan()).sequence;
        commands("final zero gate is retained", causalMessages(finalZeroMidi, 200L),
                ShortMessage.PROGRAM_CHANGE, ShortMessage.NOTE_ON, ShortMessage.NOTE_OFF);
        balanced(finalZeroMidi);
        MldCompilation expiryAndStop = compile(0, 1, 10, 1, 4, 0,
                0, 255, 190, 0, 2, 255, 223, 0);
        Sequence expiryMidi = new MidiSequenceEncoder().encode(expiryAndStop.getMidiPlan()).sequence;
        List<ShortMessage> expiryMessages = causalMessages(expiryMidi, 40L);
        commands("expiry precedes forced release", expiryMessages, ShortMessage.PROGRAM_CHANGE,
                ShortMessage.NOTE_ON, ShortMessage.NOTE_OFF, ShortMessage.NOTE_OFF, ShortMessage.CONTROL_CHANGE);
        eq("expired pitch first", 49, expiryMessages.get(2).getData1());
        eq("forced pitch second", 46, expiryMessages.get(3).getData1());
        balanced(expiryMidi);

        for (int command : new int[] {0xBE, 0xBF}) {
            for (int gate : new int[] {0, 10}) {
                for (boolean restart : new boolean[] {false, true}) {
                    List<Integer> bytes = new ArrayList<Integer>();
                    Collections.addAll(bytes, 0, 0, gate, 0, 255, command, 0);
                    if (restart) Collections.addAll(bytes, 0, 0, 1);
                    Collections.addAll(bytes, 2, 255, 223, 0);
                    MldCompilation stopped = compile(ints(bytes));
                    MidiPlan plan = stopped.getMidiPlan();
                    Sequence midi = new MidiSequenceEncoder().encode(plan).sequence;
                    assertStopOrder("linear stop/reset", causalMessages(midi, 0L), restart);
                    balanced(midi);
                    MidiPlan segment = new MidiPlanSegmenter().slice(plan, 0L, 80L, false);
                    Sequence sliced = new MidiSequenceEncoder().encode(segment).sequence;
                    assertStopOrder("sliced stop/reset", causalMessages(sliced, 0L), restart);
                    balanced(sliced);

                    bytes.set(bytes.size() - 2, 221); // Replace DF with an infinite DD end.
                    bytes.set(bytes.size() - 1, 1);
                    bytes.add(0, 0); bytes.add(1, 255); bytes.add(2, 221); bytes.add(3, 0);
                    bytes.add(4, 1); bytes.add(4, 224); bytes.add(4, 255); bytes.add(4, 0);
                    MldCompilation loop = compile(ints(bytes));
                    Sequence loopMidi = new MidiSequenceEncoder().encode(loop.getMidiPlan()).sequence;
                    balanced(loopMidi);
                    int noteOns = 0;
                    for (Track track : loopMidi.getTracks()) {
                        for (int i = 0; i < track.size(); i++) {
                            MidiEvent event = track.get(i);
                            if (event.getMessage() instanceof ShortMessage
                                    && ((ShortMessage) event.getMessage()).getCommand() == ShortMessage.NOTE_ON) {
                                noteOns++;
                                if (event.getTick() >= loop.getMidiPlan().loopInfo.loopEndMidiTick)
                                    fail("loop boundary", "note-on belongs to the next pass");
                            }
                        }
                    }
                    eq("one encoded loop pass", restart ? 2 : 1, noteOns);
                    NativeLoopRuntime runtime = loop.getNativeProgram().nativeLoop.openRuntime();
                    MidiLiveProjector live = new MidiLiveProjector(loop.getMidiPlan(), loop.getNativeProgram());
                    int program = command == 0xBF && restart ? 0 : 9;
                    for (int pass = 0; pass < 3; pass++) {
                        List<ShortMessage> messages = new ArrayList<ShortMessage>();
                        int active = 0;
                        int noteOnsInCycle = 0;
                        int programEvents = 0;
                        for (MidiLiveProjector.Event event : live.project(runtime.nextCycle())) {
                            if (event.channel != 0) continue;
                            if (event.command == ShortMessage.PROGRAM_CHANGE) {
                                program = event.data1;
                                programEvents++;
                            }
                            if (event.command == ShortMessage.NOTE_ON) {
                                eq("live note patch after reset", command == 0xBF && noteOnsInCycle > 0 ? 0 : 9, program);
                                noteOnsInCycle++;
                                active++;
                            }
                            else if (event.command == ShortMessage.NOTE_OFF) active--;
                            else if (event.command == ShortMessage.CONTROL_CHANGE && event.data1 == 120)
                                eq("notes released before live all-off", 0, active);
                            if (event.command == ShortMessage.NOTE_ON || event.command == ShortMessage.NOTE_OFF
                                    || (event.command == ShortMessage.CONTROL_CHANGE && event.data1 == 120)) {
                                ShortMessage message = new ShortMessage();
                                message.setMessage(event.command, event.channel, event.data1, event.data2);
                                messages.add(message);
                            }
                        }
                        eq("live cycle retains each patch request", restart ? 3 : 2, programEvents);
                        eq("live cycle leaves no held note", 0, active);
                        commands("live stop/reset order", messages, restart
                                ? new int[] {ShortMessage.NOTE_ON, ShortMessage.NOTE_OFF,
                                    ShortMessage.CONTROL_CHANGE, ShortMessage.NOTE_ON, ShortMessage.NOTE_OFF}
                                : new int[] {ShortMessage.NOTE_ON, ShortMessage.NOTE_OFF, ShortMessage.CONTROL_CHANGE});
                    }
                }
            }
        }
    }

    private static void auditResetControlOrdering() throws Exception {
        for (boolean percussion : new boolean[] {false, true}) {
            MldCompilation reset = percussion
                    ? compile(0, 255, 186, 1, 0, 0, 1, 0, 255, 229, 1,
                            0, 255, 186, 9, 0, 0, 1, 2, 255, 191, 0, 2, 255, 223, 0)
                    : compile(0, 0, 1, 2, 255, 191, 0, 2, 255, 223, 0);
            Sequence sequence = new MidiSequenceEncoder().encode(reset.getMidiPlan()).sequence;
            int resetControls = 0;
            for (int channel = 0; channel < 16; channel++) {
                List<ShortMessage> messages = shortMessagesAt(sequence.getTracks()[channel + 1], 80L);
                resetControls += messages.size();
                if (messages.isEmpty()) continue;
                int allOffs = percussion && channel == 9 ? 2 : 1;
                eq("reset controls retained", allOffs * 9, messages.size());
                for (int i = 0; i < allOffs; i++) {
                    eq("reset all-off status", ShortMessage.CONTROL_CHANGE, messages.get(i).getCommand());
                    eq("reset releases sound before defaults", 120, messages.get(i).getData1());
                }
                eq("reset level follows all-off", 7, messages.get(allOffs).getData1());
                eq("reset pan follows level", 10, messages.get(allOffs + 1).getData1());
            }
            eq("reset controls cover all logical channels", 16 * 9, resetControls);
        }
    }

    private static void assertStopOrder(String label, List<ShortMessage> messages, boolean restart) {
        List<ShortMessage> transitions = new ArrayList<ShortMessage>();
        for (ShortMessage message : messages)
            if (message.getCommand() != ShortMessage.PROGRAM_CHANGE) transitions.add(message);
        commands(label, transitions, restart
                ? new int[] {ShortMessage.NOTE_ON, ShortMessage.NOTE_OFF,
                    ShortMessage.CONTROL_CHANGE, ShortMessage.NOTE_ON}
                : new int[] {ShortMessage.NOTE_ON, ShortMessage.NOTE_OFF, ShortMessage.CONTROL_CHANGE});
    }

    private static List<ShortMessage> causalMessages(Sequence sequence, long tick) {
        List<ShortMessage> result = new ArrayList<ShortMessage>();
        for (ShortMessage message : shortMessagesAt(sequence.getTracks()[1], tick)) {
            int command = message.getCommand();
            if (command == ShortMessage.NOTE_ON || command == ShortMessage.NOTE_OFF
                    || command == ShortMessage.PROGRAM_CHANGE
                    || (command == ShortMessage.CONTROL_CHANGE && message.getData1() == 120)) result.add(message);
        }
        return result;
    }

    private static void commands(String label, List<ShortMessage> messages, int... expected) {
        eq(label + " count", expected.length, messages.size());
        for (int i = 0; i < expected.length; i++) eq(label + " event " + i, expected[i], messages.get(i).getCommand());
    }

    private static void balanced(Sequence sequence) {
        for (Track track : sequence.getTracks()) {
            int[] active = new int[128];
            for (int i = 0; i < track.size(); i++) {
                if (!(track.get(i).getMessage() instanceof ShortMessage)) continue;
                ShortMessage message = (ShortMessage) track.get(i).getMessage();
                if (message.getCommand() == ShortMessage.NOTE_ON) active[message.getData1()]++;
                else if (message.getCommand() == ShortMessage.NOTE_OFF) {
                    if (--active[message.getData1()] < 0) fail("balanced MIDI", "note-off precedes its note-on");
                } else if (message.getCommand() == ShortMessage.CONTROL_CHANGE && message.getData1() == 120) {
                    for (int count : active) eq("notes released before all-off", 0, count);
                }
            }
            for (int count : active) eq("no held note at MIDI end", 0, count);
        }
    }

    private static int[] ints(List<Integer> values) {
        int[] result = new int[values.size()];
        for (int i = 0; i < result.length; i++) result[i] = values.get(i);
        return result;
    }

    private static MldCompilation compile(int... events) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeBytes("melo"); out.writeInt(21 + events.length); out.writeShort(0);
        out.write(new byte[] {1, 1, 1});
        out.writeBytes("note"); out.writeShort(2); out.writeShort(0);
        out.writeBytes("trac"); out.writeInt(events.length);
        for (int value : events) out.writeByte(value);
        return new MldCompiler().compile(bytes.toByteArray());
    }

    private static void auditInfiniteLoopEncodingBoundary() throws Exception {
        MidiPlan.LoopInfo loop = new MidiPlan.LoopInfo(
                true, 0, 10, 20, 100L, 200L, Collections.<String>emptyList());
        List<MidiPlan.TempoPoint> tempos = tempos(
                tempo(0L, 500000),
                tempo(120L, 460000),
                tempo(180L, 440000));
        List<MidiPlan.CompiledNote> notes = new ArrayList<MidiPlan.CompiledNote>();
        notes.add(note(60, 80L, 150L, 0, 0));
        notes.add(note(62, 150L, 220L, 0, 1));
        MidiPlan source = plan(loop, tempos, notes,
                Collections.<MidiPlan.MappedControlEvent>emptyList(), 230L);

        MidiSequenceEncoder.EncodedSequence encoded = new MidiSequenceEncoder().encode(source);
        eqLong("native infinite encoded boundary", 200L, encoded.contentEndTick);
        eqLong("native infinite EOT", 201L, endOfTrackTick(encoded.sequence.getTracks()[0]));
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
        eq("carried note restored before boundary control", ShortMessage.NOTE_ON, tickZero.get(1).getCommand());
        eq("boundary volume updates carried note", 7, tickZero.get(2).getData1());
    }

    private static void auditControlProvenanceThroughTransforms() {
        MidiPlan.MappedControlEvent source = new MidiPlan.MappedControlEvent(
                2, 0xE2, "level", 12, 4, 4, 5, 10L, ShortMessage.CONTROL_CHANGE, 7, 100,
                0x123, 0x456, "patch", 1, 2, 3, 4, 5, 6, "cc7_volume", true, 13, 14);
        List<MidiPlan.MappedControlEvent> controls = new ArrayList<MidiPlan.MappedControlEvent>();
        controls.add(new MidiPlan.MappedControlEvent(source, 4, 5, 0L, 20, "initial_level", 0));
        controls.add(source);
        List<MidiPlan.CompiledNote> notes = Collections.singletonList(new MidiPlan.CompiledNote(
                2, 0, 4, 4, 5, 60, 100, 0, 10, 0L, 100L, 1, 15));
        MidiLaneMapper.LaneTracker lanes = new MidiLaneMapper.LaneTracker();
        lanes.observeNote(4, false);
        List<MidiPlan.TempoPoint> tempos = tempos(tempo(0L, 500000));
        MidiLaneMapper.Result mapped = MidiLaneMapper.finalizeOutput(
                1, new int[] {4, 1, 2, 3}, lanes, notes, controls, tempos, noLoop(),
                100L, new ArrayList<String>());
        MidiPlan segment = new MidiPlanSegmenter().slice(
                plan(noLoop(), tempos, mapped.notes, mapped.mappedControls, 100L), 10L, 40L, false);
        eq("chased control count", 4, segment.mappedControls.size());
        eqLong("chase rebased start", 0L, segment.mappedControls.get(0).midiTick);
        eq("chase target value", 100, segment.mappedControls.get(3).data2);
        for (MidiPlan.MappedControlEvent transformed : segment.mappedControls) {
            eq("remapped channel", 0, transformed.midiChannel);
            eq("remapped track", 1, transformed.midiTrackIndex);
            eq("source track", source.sourceTrack, transformed.sourceTrack);
            eq("source command", source.sourceCommand, transformed.sourceCommand);
            eq("raw tick", source.rawTick, transformed.rawTick);
            eq("logical channel", source.logicalChannel, transformed.logicalChannel);
            eq("status", source.status, transformed.status);
            eq("controller", source.data1, transformed.data1);
            eq("patch word", source.patchWord, transformed.patchWord);
            eq("raw patch word", source.rawPatchWord, transformed.rawPatchWord);
            eq("native mode", source.nativeMode, transformed.nativeMode);
            eq("native bank", source.nativeBank, transformed.nativeBank);
            eq("native program", source.nativeProgram, transformed.nativeProgram);
            eq("native kind", source.nativeKind, transformed.nativeKind);
            eq("native sub", source.nativeSub, transformed.nativeSub);
            eq("native value", source.nativeValue, transformed.nativeValue);
            eq("source order", source.sourceOrder, transformed.sourceOrder);
            if (!source.patchSource.equals(transformed.patchSource)
                    || !source.hostMapping.equals(transformed.hostMapping)
                    || source.hostMappingProxy != transformed.hostMappingProxy) {
                fail("control provenance", "metadata lost during chase/remap/slice");
            }
        }
        eq("source channel unchanged", 4, source.midiChannel);
        eqLong("source tick unchanged", 10L, source.midiTick);
    }

    private static void auditPatchEvidence(boolean percussionLane) {
        MelodyProgram.ChannelSnapshot channel = new MelodyProgram.ChannelSnapshot(
                0, false, 0x36, 5, 63, 32, 32, 32, 2, 0, false, 17, 0, 5);
        List<MidiPlan.MappedControlEvent> controls = new ArrayList<MidiPlan.MappedControlEvent>();
        MidiControlEmitter emitter = new MidiControlEmitter(controls);
        emitter.emitPatch(0, 0xE0, "program", 0, 4, 0L,
                MidiPatchMapper.translate(channel, percussionLane));
        eq("patch evidence event count", 1, controls.size());
        MidiPlan.MappedControlEvent patch = controls.get(0);
        eq("patch MIDI program", percussionLane ? 0 : 5, patch.data1);
        eq("patch word is MIDI program", patch.data1, patch.patchWord);
        eq("patch internal evidence word", 0x1B05, patch.rawPatchWord);
        eq("patch native mode", channel.mode, patch.nativeMode);
        eq("patch native bank", channel.bank, patch.nativeBank);
        eq("patch native program", channel.program, patch.nativeProgram);
        eq("patch native kind", channel.nativeKind, patch.nativeKind);
        eq("patch native sub", channel.nativeSub, patch.nativeSub);
        eq("patch native value", channel.nativeValue, patch.nativeValue);
    }

    private static void auditControlOnlyLaneIsolation(boolean percussion) throws Exception {
        int soundingLane = percussion ? 6 : 4;
        int controlOnlyLane = percussion ? 9 : 0;
        MidiLaneMapper.LaneTracker lanes = new MidiLaneMapper.LaneTracker();
        lanes.observeNote(soundingLane, percussion);
        lanes.observeActive(controlOnlyLane);
        List<MidiPlan.CompiledNote> notes = Collections.singletonList(new MidiPlan.CompiledNote(
                0, 0, soundingLane, soundingLane, soundingLane + 1,
                60, 100, 0, 20, 0L, 20L, 0, 4));
        List<MidiPlan.MappedControlEvent> controls = new ArrayList<MidiPlan.MappedControlEvent>();
        controls.add(laneControl(soundingLane, ShortMessage.PROGRAM_CHANGE, percussion ? 0 : 74, 0, 0L, 0));
        // Patch, pitch and level changes on a control-only lane must stay isolated.
        controls.add(laneControl(controlOnlyLane, ShortMessage.PROGRAM_CHANGE, 127, 0, 10L, 1));
        controls.add(laneControl(controlOnlyLane, ShortMessage.PITCH_BEND, 127, 127, 10L, 2));
        controls.add(laneControl(controlOnlyLane, ShortMessage.CONTROL_CHANGE, 7, 0, 10L, 3));
        List<MidiPlan.TempoPoint> tempos = tempos(tempo(0L, 500000));
        MidiLaneMapper.Result mapped = MidiLaneMapper.finalizeOutput(
                1, new int[] {soundingLane, 1, 2, 3}, lanes, notes, controls, tempos,
                noLoop(), 20L, new ArrayList<String>());
        int output = mapped.notes.get(0).midiChannel;
        eq("sounding lane keeps compacted output", percussion ? 9 : 0, output);
        Sequence sequence = new MidiSequenceEncoder().encode(
                plan(noLoop(), tempos, mapped.notes, mapped.mappedControls, 20L)).sequence;
        eq("control-only lane cannot alter sounding output", 0,
                shortMessagesAt(sequence.getTracks()[output + 1], 10L).size());
        for (MidiPlan.MappedControlEvent event : mapped.mappedControls) {
            if (event.logicalChannel == controlOnlyLane && event.midiChannel == output) {
                fail("control-only lane isolation", "unused lane overwrote the sounding channel");
            }
        }
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
        return new MidiPlan.LoopInfo(false, -1, -1, -1, -1L, -1L, Collections.<String>emptyList());
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
            int startOrder,
            int endOrder) {
        return new MidiPlan.CompiledNote(
                0,
                0,
                0,
                0,
                1,
                midiNote,
                100,
                0,
                0,
                startTick,
                endTick,
                startOrder,
                endOrder);
    }

    private static MidiPlan.MappedControlEvent control(
            int status,
            int data1,
            int data2,
            long midiTick,
            int order) {
        return laneControl(0, status, data1, data2, midiTick, order);
    }

    private static MidiPlan.MappedControlEvent laneControl(
            int logicalChannel, int status, int data1, int data2, long midiTick, int order) {
        return new MidiPlan.MappedControlEvent(
                0,
                0,
                "audit",
                0,
                logicalChannel,
                logicalChannel,
                logicalChannel + 1,
                midiTick,
                status,
                data1,
                data2,
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
