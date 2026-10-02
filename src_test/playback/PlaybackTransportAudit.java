package playback;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import javax.sound.midi.MidiMessage;
import javax.sound.midi.Receiver;
import javax.sound.midi.ShortMessage;

import audio.AudioPcmTimeline;
import audio.AudioPlaybackSource;
import audio.AudioRenderer;
import audio.StereoPcm;
import midi.MidiPlan;
import midi.MidiProjector;
import mld.decode.DecodedTrack;
import mld.decode.MachineDependentEvent;
import mld.decode.NoteEvent;
import mld.decode.SystemEvent;
import mld.decode.TrackDecoder;
import mld.decode.TrackEvent;
import mld.format.MldDocument;
import mld.semantic.NativeCompiler;
import mld.semantic.NativeProgram;

/** Deterministic shared-clock, loop, and PCM mapping audit without hardware output. */
public final class PlaybackTransportAudit {
    public static void main(String[] args) throws Exception {
        auditMonotonicPauseClock();
        auditFiniteNativeLoopTimeline();
        auditInfiniteNativeLoopTimeline();
        auditNativeLoopProgressRewinds();
        auditWholeProgramRepeatTimeline();
        auditPcmLoopFrameMapping();
        auditEvolvingNativeLoopPcmFailsClosed();
        auditPreLoopVoiceTailIsNotRepeated();
        auditLoopVoiceTailCrossesPassBoundary();
        auditPcmOnlyWholeRepeatIncludesTail();
        auditMixedWholeRepeatBoundaryRestart();
        auditPauseResumeRestoresHeldNotes();
        auditNativeInfiniteDirectRepeat();
        auditLateNativeLoopRebuildsCurrentState();
        System.out.println("PlaybackTransportAudit: PASS");
    }

    private static void auditMonotonicPauseClock() {
        AtomicLong nanos = new AtomicLong(1000000L);
        MonotonicTransportClock clock = new MonotonicTransportClock(nanos::get);
        clock.start();
        nanos.addAndGet(2500000L);
        eqLong("clock running", 2500L, clock.positionMicros());
        clock.pause();
        nanos.addAndGet(9000000L);
        eqLong("clock paused", 2500L, clock.positionMicros());
        clock.resume();
        nanos.addAndGet(1500000L);
        eqLong("clock resumed", 4000L, clock.positionMicros());
    }

    private static void auditFiniteNativeLoopTimeline() throws Exception {
        NativeProgram program = finiteLoopProgram();
        MidiPlan midi = new MidiProjector().project(program);
        TransportTimeline timeline = TransportTimeline.from(
                new PlaybackContent(midi, null, program), 0);

        eqBool("finite DD is semantically materialized", false, program.loop.hasInfiniteLoop());
        eqBool("finite DD transport is linear", false, timeline.hasLoopRegion);
        eq("finite DD native rewinds", 2, program.loop.finiteRewindCount);
        eqLong("finite DD MIDI stays linear", timeline.baseDurationMicros / 2L,
                timeline.midiPositionMicros(timeline.baseDurationMicros / 2L));
    }

    private static void auditInfiniteNativeLoopTimeline() throws Exception {
        NativeProgram program = infiniteLoopProgram(false);
        MidiPlan midi = new MidiProjector().project(program);
        TransportTimeline timeline = TransportTimeline.from(
                new PlaybackContent(midi, null, program), 0);

        eqBool("native infinite", true, timeline.infinite);
        long halfLoop = timeline.loopBodyMicros / 2L;
        eqLong("native infinite intro source", timeline.loopStartMicros - 1L,
                timeline.midiPositionMicros(timeline.loopStartMicros - 1L));
        eqLong("native infinite cycle pass 1", timeline.loopStartMicros + halfLoop,
                timeline.midiPositionMicros(timeline.loopStartMicros + halfLoop));
        eqLong("native infinite cycle pass 2", timeline.loopStartMicros + halfLoop,
                timeline.midiPositionMicros(timeline.loopEndMicros + halfLoop));
    }

    private static void auditNativeLoopProgressRewinds() throws Exception {
        List<TrackEvent> finiteEvents = new ArrayList<TrackEvent>();
        finiteEvents.add(system(0, 10, 0xDD, 0x00));
        finiteEvents.add(system(1, 15, 0xB3, 0x11));
        finiteEvents.add(system(2, 20, 0xDD, 0x05));
        finiteEvents.add(system(3, 30, 0xB3, 0x22));
        NativeProgram finiteProgram = compile(finiteEvents, 30);
        MidiPlan finiteMidi = new MidiProjector().project(finiteProgram);
        TransportTimeline finite = TransportTimeline.from(
                new PlaybackContent(finiteMidi, null, finiteProgram), 0);
        double finiteBefore = finite.progress(
                finiteProgram.timing.rawTickToMicros(19), false).fraction;
        double finiteAfter = finite.progress(
                finiteProgram.timing.rawTickToMicros(20), false).fraction;
        if (!(finiteAfter < finiteBefore)) {
            fail("finite DD progress rewind", "progress did not jump back at parser rewind");
        }

        NativeProgram infiniteProgram = infiniteLoopProgram(false);
        MidiPlan infiniteMidi = new MidiProjector().project(infiniteProgram);
        TransportTimeline infinite = TransportTimeline.from(
                new PlaybackContent(infiniteMidi, null, infiniteProgram), 0);
        double infiniteBefore = infinite.progress(infinite.loopEndMicros - 1L, false).fraction;
        double infiniteAfter = infinite.progress(infinite.loopEndMicros, false).fraction;
        if (!(infiniteBefore > 0.8 && infiniteAfter < infiniteBefore)) {
            fail("infinite DD progress rewind", "progress did not reach the loop end then jump back");
        }
    }

    private static void auditWholeProgramRepeatTimeline() throws Exception {
        NativeProgram program = plainProgram();
        MidiPlan midi = new MidiProjector().project(program);
        PlaybackContent content = new PlaybackContent(midi, null, program);
        TransportTimeline finite = TransportTimeline.from(content, 2);
        long base = finite.baseDurationMicros;
        eq("whole repeat passes", 3, finite.totalPasses);
        eqLong("whole repeat total", base * 3L, finite.totalDurationMicros);
        eqLong("whole repeat source", base / 2L, finite.midiPositionMicros(base + (base / 2L)));

        TransportTimeline infinite = TransportTimeline.from(content, -1);
        eqBool("whole infinite", true, infinite.infinite);
        eqLong("whole infinite source", 7L, infinite.midiPositionMicros(base + 7L));
    }

    private static void auditPcmLoopFrameMapping() throws Exception {
        NativeProgram program = infiniteLoopProgram(true);
        AudioRenderer renderer = new AudioRenderer();
        AudioPlaybackSource source = renderer.preparePlayback(program);
        StereoPcm linear = source.renderLinear();
        MidiPlan midi = new MidiProjector().project(program);
        TransportTimeline timeline = TransportTimeline.from(
                new PlaybackContent(midi, source, program), 0);
        AudioPcmTimeline pcm = source.forTransport(0, timeline.baseDurationMicros);

        long loopStartFrame = (timeline.loopStartMicros * linear.getSampleRate()) / 1000000L;
        long loopBodyFrames = (timeline.loopBodyMicros * linear.getSampleRate()) / 1000000L;
        byte[] first = pcm.renderFrames(loopStartFrame, 8);
        byte[] repeated = pcm.renderFrames(loopStartFrame + loopBodyFrames, 8);
        eqBytes("16k PCM mapping preserves source frames",
                pcmBytes(linear.copyInterleavedPcm16(), (int) loopStartFrame, 8), first);
        eqBytes("PCM loop body repeats from native boundary", first, repeated);
    }

    private static void auditEvolvingNativeLoopPcmFailsClosed() throws Exception {
        List<TrackEvent> events = new ArrayList<TrackEvent>();
        events.add(system(0, 10, 0xDD, 0x00));
        events.add(audio(1, 10, 50, 0x4D));
        events.add(system(2, 15, 0xBC, 0x41));
        events.add(system(3, 20, 0xDD, 0x01));
        NativeProgram program = compile(events, 20);
        AudioRenderer renderer = new AudioRenderer();
        eqBool("evolving native PCM template fails closed",
                false, renderer.hasRenderableAudio(program));
        try {
            renderer.preparePlayback(program);
            fail("evolving native PCM preparation", "expected fail-closed rejection");
        } catch (IllegalArgumentException expected) {
            // Rejection is the contract; diagnostic wording is not.
        }
    }


    private static void auditPreLoopVoiceTailIsNotRepeated() throws Exception {
        List<TrackEvent> events = new ArrayList<TrackEvent>();
        events.add(audio(0, 9, 240, 0x4D));
        events.add(system(1, 10, 0xDD, 0x00));
        events.add(system(2, 20, 0xDD, 0x09));
        NativeProgram program = compile(events, 20);
        AudioPlaybackSource source = new AudioRenderer().preparePlayback(program);
        long passMicros = program.timing.rawTickToMicros(20);
        AudioPcmTimeline pcm = source.forTransport(1, passMicros);
        int rate = source.getSampleRate();
        long firstEarlyLoop = (program.timing.rawTickToMicros(10) * rate) / 1000000L;
        long secondEarlyLoop = (program.timing.rawTickToMicros(20) * rate) / 1000000L;
        byte[] first = pcm.renderFrames(firstEarlyLoop, 80);
        byte[] second = pcm.renderFrames(secondEarlyLoop, 80);
        if (!hasNonZero(first)) {
            fail("pre-loop tail fixture", "expected first-pass tail at loop entry");
        }
        if (hasNonZero(second)) {
            fail("pre-loop tail repeat", "pre-loop voice tail repeated into later pass");
        }
    }

    private static void auditLoopVoiceTailCrossesPassBoundary() throws Exception {
        List<TrackEvent> events = new ArrayList<TrackEvent>();
        events.add(system(0, 10, 0xDD, 0x00));
        events.add(audio(1, 19, 240, 0x4D));
        events.add(system(2, 20, 0xDD, 0x09));
        NativeProgram program = compile(events, 20);
        AudioPlaybackSource source = new AudioRenderer().preparePlayback(program);
        long passMicros = program.timing.rawTickToMicros(20);
        AudioPcmTimeline pcm = source.forTransport(1, passMicros);
        int rate = source.getSampleRate();
        long secondPassStart = (program.timing.rawTickToMicros(20) * rate) / 1000000L;
        byte[] boundaryTail = pcm.renderFrames(secondPassStart, 160);
        if (!hasNonZero(boundaryTail)) {
            fail("loop voice tail carry", "loop-local voice tail was clipped at pass boundary");
        }
    }


    private static void auditPcmOnlyWholeRepeatIncludesTail() throws Exception {
        List<TrackEvent> events = new ArrayList<TrackEvent>();
        events.add(system(0, 10, 0xDD, 0x00));
        events.add(audio(1, 19, 240, 0x4D));
        events.add(system(2, 20, 0xDD, 0x09));
        NativeProgram program = compile(events, 20);
        AudioPlaybackSource source = new AudioRenderer().preparePlayback(program);
        PlaybackContent content = new PlaybackContent(null, source, program);
        TransportTimeline timeline = TransportTimeline.from(content, 1);
        long audioDuration = source.transportDurationMicros(1, timeline.baseDurationMicros);
        eqLong("PCM-only whole-repeat duration", audioDuration, timeline.totalDurationMicros);
        eqLong("PCM-only whole-repeat uses completed semantic pass",
                timeline.baseDurationMicros * 2L, timeline.totalDurationMicros);
    }

    private static void auditMixedWholeRepeatBoundaryRestart() throws Exception {
        List<TrackEvent> midiEvents = new ArrayList<TrackEvent>();
        midiEvents.add(system(0, 1, 0xE2, 0x20));
        NativeProgram midiProgram = compile(midiEvents, 1);
        MidiPlan midi = new MidiProjector().project(midiProgram);

        List<TrackEvent> audioEvents = new ArrayList<TrackEvent>();
        audioEvents.add(audio(0, 0, 2400, 0x45));
        NativeProgram audioProgram = compile(audioEvents, 0);
        AudioPlaybackSource audio = new AudioRenderer().preparePlayback(audioProgram);
        PlaybackContent content = new PlaybackContent(midi, audio, midiProgram);
        TransportTimeline timeline = TransportTimeline.from(content, 2);
        long boundary = timeline.baseDurationMicros;

        RecordingReceiver receiver = new RecordingReceiver();
        MidiPlaybackParticipant participant = MidiPlaybackParticipant.open(
                midi, midiProgram, timeline, receiver);
        long eventMicros = midiProgram.timing.rawTickToMicros(1);
        participant.sync(eventMicros);
        int firstPassMessages = receiver.messages.size();
        if (firstPassMessages <= 0) {
            fail("mixed direct MIDI fixture", "first pass produced no MIDI event");
        }
        participant.sync(boundary + eventMicros);
        eq("mixed direct MIDI restarts at transport pass boundary",
                firstPassMessages * 2, receiver.messages.size());
        participant.close();
    }

    private static void auditNativeInfiniteDirectRepeat() throws Exception {
        List<TrackEvent> events = new ArrayList<TrackEvent>();
        events.add(system(0, 0, 0xE2, 10));
        events.add(note(1, 5, 5, 2));
        events.add(system(2, 10, 0xDD, 0x00));
        events.add(system(3, 12, 0xE6, 0x21)); // Level increases by one on each pass.
        events.add(note(4, 15, 5, 5));
        events.add(system(5, 20, 0xDD, 0x01));
        NativeProgram program = compile(events, 20);
        MidiPlan midi = new MidiProjector().project(program);
        int introMidiNote = midiNoteAt(midi, 5);
        int loopMidiNote = midiNoteAt(midi, 15);
        TransportTimeline timeline = TransportTimeline.from(
                new PlaybackContent(midi, null, program), 0);

        eqLong("native parser cycle starts at proof pass",
                20L, program.loop.infiniteRegion.parserCycleStartRawTick);
        eqLong("native parser cycle ends at proof pass",
                30L, program.loop.infiniteRegion.parserCycleEndRawTick);

        RecordingReceiver receiver = new RecordingReceiver();
        MidiPlaybackParticipant participant = MidiPlaybackParticipant.open(
                midi, program, timeline, receiver);
        long loopNotePhase = program.timing.rawTickToMicros(15)
                - program.timing.rawTickToMicros(10);

        participant.sync(timeline.loopStartMicros);
        eq("intro note release occurs once at native loop entry",
                1, receiver.count(ShortMessage.NOTE_OFF, introMidiNote));

        participant.sync(timeline.loopStartMicros + loopNotePhase);
        eq("first native loop note-on", 1, receiver.count(ShortMessage.NOTE_ON, loopMidiNote));
        eq("first native pass carries level", 22, receiver.lastControlValue(0, 7));
        participant.sync(timeline.loopEndMicros);
        eq("first native loop note-off at exact boundary", 1,
                receiver.count(ShortMessage.NOTE_OFF, loopMidiNote));

        participant.sync(timeline.loopEndMicros + loopNotePhase);
        eq("second native loop note-on is directly scheduled", 2,
                receiver.count(ShortMessage.NOTE_ON, loopMidiNote));
        eq("second native pass carries level", 24, receiver.lastControlValue(0, 7));
        eq("intro carry-over release is not replayed", 1,
                receiver.count(ShortMessage.NOTE_OFF, introMidiNote));
        eq("native boundary injects no all-notes-off", 0,
                receiver.countControl(123));

        participant.sync(timeline.loopEndMicros + timeline.loopBodyMicros);
        eq("second native loop note-off at exact boundary", 2,
                receiver.count(ShortMessage.NOTE_OFF, loopMidiNote));
        participant.sync(timeline.loopEndMicros + timeline.loopBodyMicros + loopNotePhase);
        eq("third native loop starts without seek/chase state", 3,
                receiver.count(ShortMessage.NOTE_ON, loopMidiNote));
        eq("live cycle executes relative control instead of replaying a template", 26,
                receiver.lastControlValue(0, 7));
        participant.close();
    }

    private static void auditPauseResumeRestoresHeldNotes() throws Exception {
        List<TrackEvent> events = new ArrayList<TrackEvent>();
        events.add(note(0, 1, 1, 20));
        NativeProgram program = compile(events, 30);
        MidiPlan midi = new MidiProjector().project(program);
        int midiNote = midi.notes.get(0).midiNote;
        TransportTimeline timeline = TransportTimeline.from(
                new PlaybackContent(midi, null, program), 0);
        RecordingReceiver receiver = new RecordingReceiver();
        MidiPlaybackParticipant participant = MidiPlaybackParticipant.open(
                midi, program, timeline, receiver);

        participant.sync(program.timing.rawTickToMicros(1));
        eq("held note starts", 1, receiver.count(ShortMessage.NOTE_ON, midiNote));
        participant.pause();
        participant.resume();
        eq("held note is restored after resume", 2,
                receiver.count(ShortMessage.NOTE_ON, midiNote));
        participant.close();
    }

    private static void auditLateNativeLoopRebuildsCurrentState() throws Exception {
        List<TrackEvent> events = new ArrayList<TrackEvent>();
        events.add(system(0, 10, 0xDD, 0x00));
        events.add(note(1, 12, 1, 3));
        events.add(system(2, 20, 0xDD, 0x01));
        NativeProgram program = compile(events, 20);
        MidiPlan midi = new MidiProjector().project(program);
        TransportTimeline timeline = TransportTimeline.from(
                new PlaybackContent(midi, null, program), 0);
        RecordingReceiver receiver = new RecordingReceiver();
        MidiPlaybackParticipant participant = MidiPlaybackParticipant.open(
                midi, program, timeline, receiver);

        participant.sync(60000000L);
        if (receiver.messages.size() > 256) {
            fail("late native recovery output",
                    "historical native-loop events were burst to the receiver: "
                            + receiver.messages.size());
        }
        participant.close();
    }

    private static final class RecordingReceiver implements Receiver {
        final List<ShortMessage> messages = new ArrayList<ShortMessage>();

        @Override
        public void send(MidiMessage message, long timeStamp) {
            if (message instanceof ShortMessage) {
                messages.add((ShortMessage) message.clone());
            }
        }

        @Override
        public void close() {
        }

        int count(int command, int note) {
            int count = 0;
            for (ShortMessage message : messages) {
                if (message.getCommand() == command && message.getData1() == note) count++;
            }
            return count;
        }

        int countControl(int controller) {
            int count = 0;
            for (ShortMessage message : messages) {
                if (message.getCommand() == ShortMessage.CONTROL_CHANGE
                        && message.getData1() == controller) count++;
            }
            return count;
        }

        int lastControlValue(int channel, int controller) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                ShortMessage message = messages.get(i);
                if (message.getCommand() == ShortMessage.CONTROL_CHANGE
                        && message.getChannel() == channel && message.getData1() == controller) {
                    return message.getData2();
                }
            }
            fail("controller output", "missing channel=" + channel + " controller=" + controller);
            return -1;
        }
    }

    private static NativeProgram finiteLoopProgram() {
        List<TrackEvent> events = new ArrayList<TrackEvent>();
        events.add(system(0, 10, 0xDD, 0x00));
        events.add(system(1, 20, 0xDD, 0x09));
        return compile(events, 20);
    }

    private static NativeProgram infiniteLoopProgram(boolean withAudio) {
        List<TrackEvent> events = new ArrayList<TrackEvent>();
        events.add(system(0, 10, 0xDD, 0x00));
        if (withAudio) events.add(audio(1, 10, 50, 0x4D));
        events.add(system(withAudio ? 2 : 1, 20, 0xDD, 0x01));
        return compile(events, 20);
    }

    private static NativeProgram plainProgram() {
        List<TrackEvent> events = new ArrayList<TrackEvent>();
        events.add(system(0, 10, 0xE2, 0x20));
        return compile(events, 20);
    }

    private static NativeProgram compile(List<TrackEvent> events, int totalRawTicks) {
        int systemCount = 0;
        int machineCount = 0;
        for (TrackEvent event : events) {
            if (event instanceof SystemEvent) systemCount++;
            if (event instanceof MachineDependentEvent) machineCount++;
        }
        DecodedTrack decoded = new DecodedTrack(
                0, 0, totalRawTicks, 0, 0, systemCount, machineCount,
                events, Collections.<String>emptyList());
        MldDocument document = new MldDocument(
                new byte[0], "melo", 0, 0, 0, 0, 1, 0, 0,
                Collections.<Long>emptyList(), Collections.emptyList(), Collections.emptyList());
        return new NativeCompiler().compile(document, Collections.singletonList(decoded));
    }

    private static SystemEvent system(int eventIndex, int rawTick, int command, int value) {
        int part = command >= 0xE0 && command <= 0xEF ? (value >> 6) & 3 : -1;
        return new SystemEvent(
                0, eventIndex, 0, rawTick, command, value,
                TrackDecoder.commandName(command), part, -1);
    }

    private static NoteEvent note(int eventIndex, int rawTick, int gate, int pitch) {
        return new NoteEvent(0, eventIndex, 0, rawTick, 0x05, 0, pitch, gate, 48, 0, 0);
    }

    private static int midiNoteAt(MidiPlan plan, int rawStartTick) {
        for (MidiPlan.CompiledNote note : plan.notes) {
            if (note.rawStartTick == rawStartTick) return note.midiNote;
        }
        fail("MIDI note fixture", "missing compiled note at raw tick " + rawStartTick);
        return -1;
    }

    private static MachineDependentEvent audio(
            int eventIndex, int rawTick, int encodedBytes, int formatByte) {
        byte[] payload = new byte[9 + encodedBytes];
        payload[0] = 0x71;
        payload[1] = (byte) 0x84;
        payload[2] = 0x00;
        payload[3] = (byte) formatByte;
        payload[4] = 0x00;
        payload[5] = 0x00;
        payload[6] = 0x00;
        payload[7] = 0x00;
        payload[8] = (byte) encodedBytes;
        for (int i = 0; i < encodedBytes; i++) {
            payload[9 + i] = (byte) (i * 13 + 7);
        }
        return new MachineDependentEvent(0, eventIndex, 0, rawTick, 0xFF, payload);
    }



    private static boolean hasNonZero(byte[] bytes) {
        for (byte value : bytes) {
            if (value != 0) return true;
        }
        return false;
    }

    private static byte[] pcmBytes(short[] source, int startFrame, int frameCount) {
        byte[] bytes = new byte[frameCount * 4];
        for (int i = 0; i < frameCount; i++) {
            int sourceIndex = (startFrame + i) * 2;
            short left = sourceIndex < source.length ? source[sourceIndex] : 0;
            short right = sourceIndex + 1 < source.length ? source[sourceIndex + 1] : 0;
            int offset = i * 4;
            bytes[offset] = (byte) (left & 0xFF);
            bytes[offset + 1] = (byte) ((left >>> 8) & 0xFF);
            bytes[offset + 2] = (byte) (right & 0xFF);
            bytes[offset + 3] = (byte) ((right >>> 8) & 0xFF);
        }
        return bytes;
    }

    private static void eq(String label, int expected, int actual) {
        if (expected != actual) fail(label, "expected " + expected + ", got " + actual);
    }

    private static void eqLong(String label, long expected, long actual) {
        if (expected != actual) fail(label, "expected " + expected + ", got " + actual);
    }

    private static void eqBool(String label, boolean expected, boolean actual) {
        if (expected != actual) fail(label, "expected " + expected + ", got " + actual);
    }

    private static void eqBytes(String label, byte[] expected, byte[] actual) {
        if (expected.length != actual.length) {
            fail(label, "length mismatch " + expected.length + " vs " + actual.length);
        }
        for (int i = 0; i < expected.length; i++) {
            if (expected[i] != actual[i]) {
                fail(label, "byte " + i + " differs");
            }
        }
    }

    private static void fail(String label, String detail) {
        throw new AssertionError(label + ": " + detail);
    }
}
