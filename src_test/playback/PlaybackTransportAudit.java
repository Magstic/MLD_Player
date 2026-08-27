package playback;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import javax.sound.midi.Sequencer;

import audio.AudioPcmTimeline;
import audio.AudioPlaybackSource;
import audio.AudioRenderer;
import audio.StereoPcm;
import midi.MidiLoopMaterializer;
import midi.MidiPlan;
import midi.MidiProjector;
import midi.MidiSequenceEncoder;
import mld.decode.DecodedTrack;
import mld.decode.MachineDependentEvent;
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
        auditMaterializedNativeLoopTimeline();
        auditInfiniteNativeLoopTimeline();
        auditWholeProgramRepeatTimeline();
        auditPcmLoopFrameMapping();
        auditPreLoopVoiceTailIsNotRepeated();
        auditLoopVoiceTailCrossesPassBoundary();
        auditPcmOnlyFiniteLoopIncludesTail();
        auditMixedWholeRepeatBoundaryRestart();
        auditPlaybackModes();
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

    private static void auditMaterializedNativeLoopTimeline() throws Exception {
        NativeProgram program = loopProgram(false);
        MidiPlan original = new MidiProjector().project(program);
        MidiPlan materialized = new MidiLoopMaterializer().materializeFiniteLoop(original, 2);
        MidiSequenceEncoder.EncodedSequence midi = new MidiSequenceEncoder().encode(materialized);
        TransportTimeline timeline = TransportTimeline.from(
                new PlaybackContent(midi, null, program.timing, program.loop), 2);

        eqBool("materialized finite", false, timeline.infinite);
        eqBool("materialized native loop", true, timeline.materializedNativeLoop);
        eqLong("native loop start us", 100000L, timeline.loopStartMicros);
        eqLong("native loop end us", 200000L, timeline.loopEndMicros);
        eq("native loop passes", 3, timeline.totalPasses);
        eqText("materialized intro", "intro", timeline.progress(50000L, false).label);
        eqText("materialized pass 1", "loop 1/3", timeline.progress(100000L, false).label);
        eqText("materialized pass 2", "loop 2/3", timeline.progress(210000L, false).label);
        eqText("materialized description", "3 passes", timeline.describeLoop());
        eqLong("materialized MIDI is linear", 210000L, timeline.midiPositionMicros(210000L));
    }

    private static void auditInfiniteNativeLoopTimeline() throws Exception {
        NativeProgram program = loopProgram(false);
        MidiSequenceEncoder.EncodedSequence midi = new MidiSequenceEncoder().encode(
                new MidiProjector().project(program));
        TransportTimeline timeline = TransportTimeline.from(
                new PlaybackContent(midi, null, program.timing, program.loop), -1);

        eqBool("native infinite", true, timeline.infinite);
        eqText("native infinite description", "infinite", timeline.describeLoop());
        eqLong("native infinite source pass 1", 150000L, timeline.midiPositionMicros(150000L));
        eqLong("native infinite source pass 2", 150000L, timeline.midiPositionMicros(250000L));
        eqText("native infinite label", "loop 2/inf", timeline.progress(250000L, false).label);
    }

    private static void auditWholeProgramRepeatTimeline() throws Exception {
        NativeProgram program = plainProgram();
        MidiSequenceEncoder.EncodedSequence midi = new MidiSequenceEncoder().encode(
                new MidiProjector().project(program));
        PlaybackContent content = new PlaybackContent(midi, null, program.timing, program.loop);
        TransportTimeline finite = TransportTimeline.from(content, 2);
        long base = finite.baseDurationMicros;
        eq("whole repeat passes", 3, finite.totalPasses);
        eqLong("whole repeat total", base * 3L, finite.totalDurationMicros);
        eqLong("whole repeat source", base / 2L, finite.midiPositionMicros(base + (base / 2L)));
        eqText("whole repeat label", "pass 2/3", finite.progress(base + 1L, false).label);

        TransportTimeline infinite = TransportTimeline.from(content, -1);
        eqBool("whole infinite", true, infinite.infinite);
        eqLong("whole infinite source", 7L, infinite.midiPositionMicros(base + 7L));
    }

    private static void auditPcmLoopFrameMapping() throws Exception {
        NativeProgram program = loopProgram(true);
        AudioRenderer renderer = new AudioRenderer();
        AudioPlaybackSource source = renderer.preparePlayback(program);
        StereoPcm linear = source.renderLinear();
        MidiPlan materialized = new MidiLoopMaterializer().materializeFiniteLoop(
                new MidiProjector().project(program), 1);
        MidiSequenceEncoder.EncodedSequence midi = new MidiSequenceEncoder().encode(materialized);
        TransportTimeline timeline = TransportTimeline.from(
                new PlaybackContent(midi, source, program.timing, program.loop), 1);
        AudioPcmTimeline pcm = source.forTransport(1, timeline.baseDurationMicros);

        long loopStartFrame = (timeline.loopStartMicros * linear.getSampleRate()) / 1000000L;
        long loopBodyFrames = (timeline.loopBodyMicros * linear.getSampleRate()) / 1000000L;
        byte[] first = pcm.renderFrames(loopStartFrame, 8);
        byte[] repeated = pcm.renderFrames(loopStartFrame + loopBodyFrames, 8);
        eqBytes("16k PCM mapping preserves source frames",
                pcmBytes(linear.copyInterleavedPcm16(), (int) loopStartFrame, 8), first);
        eqBytes("PCM loop body repeats from native boundary", first, repeated);
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


    private static void auditPcmOnlyFiniteLoopIncludesTail() throws Exception {
        List<TrackEvent> events = new ArrayList<TrackEvent>();
        events.add(system(0, 10, 0xDD, 0x00));
        events.add(audio(1, 19, 240, 0x4D));
        events.add(system(2, 20, 0xDD, 0x09));
        NativeProgram program = compile(events, 20);
        AudioPlaybackSource source = new AudioRenderer().preparePlayback(program);
        PlaybackContent content = new PlaybackContent(null, source, program.timing, program.loop);
        TransportTimeline timeline = TransportTimeline.from(content, 1);
        long audioDuration = source.transportDurationMicros(1, timeline.baseDurationMicros);
        eqLong("PCM-only finite loop tail duration", audioDuration, timeline.totalDurationMicros);
        long nominal = timeline.loopStartMicros + timeline.loopBodyMicros * 2L;
        if (timeline.totalDurationMicros <= nominal) {
            fail("PCM-only finite loop tail", "transport ended at nominal loop boundary");
        }
    }

    private static void auditMixedWholeRepeatBoundaryRestart() throws Exception {
        List<TrackEvent> midiEvents = new ArrayList<TrackEvent>();
        midiEvents.add(system(0, 1, 0xE2, 0x20));
        NativeProgram midiProgram = compile(midiEvents, 1);
        MidiSequenceEncoder.EncodedSequence midi = new MidiSequenceEncoder().encode(
                new MidiProjector().project(midiProgram));

        List<TrackEvent> audioEvents = new ArrayList<TrackEvent>();
        audioEvents.add(audio(0, 0, 2400, 0x45));
        NativeProgram audioProgram = compile(audioEvents, 0);
        AudioPlaybackSource audio = new AudioRenderer().preparePlayback(audioProgram);
        PlaybackContent content = new PlaybackContent(midi, audio, midiProgram.timing, midiProgram.loop);
        TransportTimeline timeline = TransportTimeline.from(content, 2);
        if (!timeline.transportOwnsWholeMidiRepeat) {
            fail("mixed whole-repeat ownership", "longer PCM did not own repeat boundary; midi="
                    + timeline.midiDurationMicros + ", base=" + timeline.baseDurationMicros
                    + ", audio=" + audio.getLinearDurationMicros());
        }
        long boundary = timeline.baseDurationMicros;
        eqLong("mixed pass index before boundary", 0L, timeline.midiWholeRepeatPass(boundary - 1L));
        eqLong("mixed pass index at boundary", 1L, timeline.midiWholeRepeatPass(boundary));
        if (!timeline.crossedMidiWholeRepeatBoundary(boundary + 1000L, 0L)) {
            fail("mixed boundary transition", "pass transition was not detected");
        }

        FakeSequencer handler = new FakeSequencer();
        Sequencer sequencer = (Sequencer) Proxy.newProxyInstance(
                PlaybackTransportAudit.class.getClassLoader(),
                new Class<?>[] { Sequencer.class },
                handler);
        handler.running = false;
        handler.position = Math.max(0L, timeline.midiDurationMicros - 1L);
        long pass = PlaybackSession.syncMidi(
                sequencer, midi, timeline, boundary + 1000L, true, 0L);
        eqLong("mixed boundary pass update", 1L, pass);
        eqLong("mixed boundary MIDI restart position", 0L, handler.lastSetPosition);
        eqBool("mixed boundary MIDI restarted", true, handler.started);
    }

    private static final class FakeSequencer implements InvocationHandler {
        boolean running;
        boolean started;
        long position;
        long lastSetPosition = -1L;

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            if ("isRunning".equals(name)) return Boolean.valueOf(running);
            if ("start".equals(name)) { running = true; started = true; return null; }
            if ("stop".equals(name)) { running = false; return null; }
            if ("setMicrosecondPosition".equals(name)) {
                lastSetPosition = ((Long) args[0]).longValue();
                position = lastSetPosition;
                return null;
            }
            if ("getMicrosecondPosition".equals(name)) return Long.valueOf(position);
            Class<?> type = method.getReturnType();
            if (type == Boolean.TYPE) return Boolean.FALSE;
            if (type == Integer.TYPE) return Integer.valueOf(0);
            if (type == Long.TYPE) return Long.valueOf(0L);
            if (type == Float.TYPE) return Float.valueOf(0.0f);
            if (type == Double.TYPE) return Double.valueOf(0.0);
            return null;
        }
    }

    private static void auditPlaybackModes() throws Exception {
        NativeProgram plain = plainProgram();
        MidiSequenceEncoder.EncodedSequence midi = new MidiSequenceEncoder().encode(
                new MidiProjector().project(plain));
        eqText("MIDI mode", "host MIDI", PlaybackSession.describeMode(
                new PlaybackContent(midi, null, plain.timing, plain.loop)));

        NativeProgram audioProgram = loopProgram(true);
        AudioPlaybackSource pcm = new AudioRenderer().preparePlayback(audioProgram);
        eqText("PCM mode", "sampled PCM", PlaybackSession.describeMode(
                new PlaybackContent(null, pcm, audioProgram.timing, audioProgram.loop)));
        eqText("mixed mode", "mixed MIDI + PCM", PlaybackSession.describeMode(
                new PlaybackContent(midi, pcm, audioProgram.timing, audioProgram.loop)));
    }

    private static NativeProgram loopProgram(boolean withAudio) {
        List<TrackEvent> events = new ArrayList<TrackEvent>();
        events.add(system(0, 10, 0xDD, 0x00));
        if (withAudio) {
            events.add(audio(1, 10, 50, 0x4D));
        }
        events.add(system(withAudio ? 2 : 1, 20, 0xDD, 0x09));
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
        return new SystemEvent(
                0, eventIndex, 0, rawTick, command, value,
                TrackDecoder.commandName(command), -1, -1);
    }

    private static MachineDependentEvent audio(
            int eventIndex, int rawTick, int encodedBytes, int formatByte) {
        byte[] payload = new byte[11 + encodedBytes];
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
        byte[] exact = new byte[9 + encodedBytes];
        System.arraycopy(payload, 0, exact, 0, exact.length);
        return new MachineDependentEvent(0, eventIndex, 0, rawTick, 0xFF, exact);
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

    private static void eqText(String label, String expected, String actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            fail(label, "expected " + expected + ", got " + actual);
        }
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
