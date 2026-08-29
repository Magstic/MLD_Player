package playback;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.Receiver;
import javax.sound.midi.ShortMessage;

import midi.MidiLiveProjector;
import midi.MidiPlan;
import mld.semantic.NativeLoopRuntime;
import mld.semantic.NativeProgram;
import mld.semantic.TimingModel;

/** Direct host-MIDI scheduler driven by the shared monotonic transport clock. */
final class MidiPlaybackParticipant implements AutoCloseable {
    private static final int MODE_LINEAR = 0;
    private static final int MODE_NATIVE_LOOP = 1;
    private static final long LATE_RECOVERY_MICROS = 250000L;
    private static final int MAX_IMMEDIATE_MESSAGES = 256;

    private final Receiver receiver;
    private final TransportTimeline timeline;
    private final int mode;
    private final List<Event> linearEvents;
    private final List<Event> nativeInitialEvents;
    private final NativeLoopRuntime nativeRuntime;
    private final MidiLiveProjector liveProjector;
    private final int[][] activeNotes = new int[16][128];
    private final int[][] noteVelocities = new int[16][128];
    private final int[][] controllers = filled(16, 128, -1);
    private final int[] programs = filled(16, -1);
    private final int[] channelPressure = filled(16, -1);
    private final int[] pitchBend = filled(16, -1);

    private int linearIndex;
    private long linearPass;
    private int nativeInitialIndex;
    private int nativeCycleIndex;
    private List<Event> nativeCycleEvents = Collections.emptyList();
    private boolean closed;

    private MidiPlaybackParticipant(
            Receiver receiver,
            TransportTimeline timeline,
            int mode,
            List<Event> linearEvents,
            List<Event> nativeInitialEvents,
            NativeLoopRuntime nativeRuntime,
            MidiLiveProjector liveProjector) {
        this.receiver = receiver;
        this.timeline = timeline;
        this.mode = mode;
        this.linearEvents = linearEvents;
        this.nativeInitialEvents = nativeInitialEvents;
        this.nativeRuntime = nativeRuntime;
        this.liveProjector = liveProjector;
    }

    static MidiPlaybackParticipant open(
            MidiPlan plan,
            NativeProgram program,
            TransportTimeline timeline,
            Receiver receiver) {
        if (plan == null || program == null || timeline == null || receiver == null) {
            throw new IllegalArgumentException(
                    "MIDI playback requires plan, native program, transport, and receiver.");
        }
        if (timeline.nativeLoop) {
            if (program.loop == null || program.loop.infiniteRegion == null
                    || program.nativeLoop == null) {
                throw new IllegalArgumentException(
                        "Native infinite transport requires a continuing semantic loop plan.");
            }
            return new MidiPlaybackParticipant(
                    receiver,
                    timeline,
                    MODE_NATIVE_LOOP,
                    Collections.<Event>emptyList(),
                    buildNativeInitialEvents(
                            plan,
                            program.timing,
                            program.nativeLoop.initialEndRawTick()),
                    program.nativeLoop.openRuntime(),
                    new MidiLiveProjector(plan, program));
        }
        return new MidiPlaybackParticipant(
                receiver,
                timeline,
                MODE_LINEAR,
                buildLinearEvents(plan, program.timing),
                Collections.<Event>emptyList(),
                null,
                null);
    }

    void sync(long transportMicros) {
        if (closed) return;
        long now = Math.max(0L, transportMicros);
        Scheduled first = nextScheduled();
        if (first != null && now - first.transportMicros > LATE_RECOVERY_MICROS) {
            fastForward(now);
            return;
        }

        List<ShortMessage> due = new ArrayList<ShortMessage>();
        while (true) {
            Scheduled scheduled = nextScheduled();
            if (scheduled == null || scheduled.transportMicros > now) break;
            if (scheduled.event.command >= 0) {
                ShortMessage message = message(scheduled.event);
                due.add(message);
                trackState(message);
            }
            advance();
            if (due.size() > MAX_IMMEDIATE_MESSAGES) {
                sendOutputReset();
                fastForwardRemainder(now);
                sendStateSnapshot();
                return;
            }
        }
        dispatch(due);
    }

    long microsUntilNextEvent(long transportMicros) {
        Scheduled scheduled = nextScheduled();
        if (scheduled == null) return Long.MAX_VALUE;
        return Math.max(0L, scheduled.transportMicros - Math.max(0L, transportMicros));
    }

    void pause() {
        if (!closed) sendOutputReset();
    }

    void resume() {
        if (!closed) sendStateSnapshot();
    }

    void stop() {
        if (!closed) releaseActiveNotes();
    }

    private Scheduled nextScheduled() {
        return mode == MODE_NATIVE_LOOP ? nextNativeScheduled() : nextLinearScheduled();
    }

    private Scheduled nextLinearScheduled() {
        if (linearEvents.isEmpty()) return null;
        if (!timeline.infinite && linearPass >= Math.max(1, timeline.totalPasses)) return null;
        if (linearIndex >= linearEvents.size()) {
            long nextPass = linearPass + 1L;
            if (!timeline.infinite && nextPass >= Math.max(1, timeline.totalPasses)) return null;
            linearPass = nextPass;
            linearIndex = 0;
        }
        Event event = linearEvents.get(linearIndex);
        long base = safeMultiply(linearPass, timeline.baseDurationMicros);
        return new Scheduled(safeAdd(base, event.micros), event);
    }

    private Scheduled nextNativeScheduled() {
        while (true) {
            if (nativeInitialIndex < nativeInitialEvents.size()) {
                Event event = nativeInitialEvents.get(nativeInitialIndex);
                return new Scheduled(event.micros, event);
            }
            if (nativeCycleIndex < nativeCycleEvents.size()) {
                Event event = nativeCycleEvents.get(nativeCycleIndex);
                return new Scheduled(event.micros, event);
            }

            NativeLoopRuntime.Cycle cycle = nativeRuntime.nextCycle();
            timeline.observeNativeCycle(cycle);
            List<MidiLiveProjector.Event> projected = liveProjector.project(cycle);
            List<Event> events = new ArrayList<Event>(Math.max(1, projected.size()));
            for (MidiLiveProjector.Event event : projected) {
                events.add(new Event(
                        event.transportMicros,
                        event.phase,
                        event.channel,
                        event.command,
                        event.data1,
                        event.data2,
                        event.order));
            }
            if (events.isEmpty()) {
                events.add(new Event(cycle.transportEndMicros, 3, 0, -1, 0, 0, 0));
            }
            nativeCycleEvents = Collections.unmodifiableList(events);
            nativeCycleIndex = 0;
        }
    }

    private void advance() {
        if (mode == MODE_NATIVE_LOOP) {
            if (nativeInitialIndex < nativeInitialEvents.size()) nativeInitialIndex++;
            else nativeCycleIndex++;
        } else {
            linearIndex++;
        }
    }

    private void fastForward(long now) {
        sendOutputReset();
        fastForwardRemainder(now);
        sendStateSnapshot();
    }

    private void fastForwardRemainder(long now) {
        while (true) {
            Scheduled scheduled = nextScheduled();
            if (scheduled == null || scheduled.transportMicros > now) return;
            if (scheduled.event.command >= 0) trackState(message(scheduled.event));
            advance();
        }
    }

    private ShortMessage message(Event event) {
        return shortMessage(event.command, event.channel, event.data1, event.data2);
    }

    private void trackState(ShortMessage message) {
        int command = message.getCommand();
        int channel = message.getChannel();
        if (command == ShortMessage.NOTE_ON) {
            int note = message.getData1();
            if (message.getData2() > 0) {
                if (activeNotes[channel][note] < Integer.MAX_VALUE) activeNotes[channel][note]++;
                noteVelocities[channel][note] = message.getData2();
            } else if (activeNotes[channel][note] > 0) {
                activeNotes[channel][note]--;
            }
        } else if (command == ShortMessage.NOTE_OFF) {
            int note = message.getData1();
            if (activeNotes[channel][note] > 0) activeNotes[channel][note]--;
        } else if (command == ShortMessage.CONTROL_CHANGE) {
            int controller = message.getData1();
            if (controller == 120 || controller == 123) {
                Arrays.fill(activeNotes[channel], 0);
            } else {
                controllers[channel][controller] = message.getData2();
            }
        } else if (command == ShortMessage.PROGRAM_CHANGE) {
            programs[channel] = message.getData1();
        } else if (command == ShortMessage.CHANNEL_PRESSURE) {
            channelPressure[channel] = message.getData1();
        } else if (command == ShortMessage.PITCH_BEND) {
            pitchBend[channel] = message.getData1() | (message.getData2() << 7);
        }
    }

    private void sendOutputReset() {
        List<ShortMessage> messages = new ArrayList<ShortMessage>(32);
        for (int channel = 0; channel < 16; channel++) {
            messages.add(shortMessage(ShortMessage.CONTROL_CHANGE, channel, 120, 0));
            messages.add(shortMessage(ShortMessage.CONTROL_CHANGE, channel, 64, 0));
        }
        dispatch(messages);
    }

    private void sendStateSnapshot() {
        List<ShortMessage> messages = new ArrayList<ShortMessage>();
        for (int channel = 0; channel < 16; channel++) {
            for (int controller = 0; controller < 128; controller++) {
                if (controllers[channel][controller] < 0
                        || controller == 120 || controller == 123) continue;
                messages.add(shortMessage(
                        ShortMessage.CONTROL_CHANGE,
                        channel,
                        controller,
                        controllers[channel][controller]));
            }
            if (programs[channel] >= 0) {
                messages.add(shortMessage(ShortMessage.PROGRAM_CHANGE, channel, programs[channel], 0));
            }
            if (channelPressure[channel] >= 0) {
                messages.add(shortMessage(
                        ShortMessage.CHANNEL_PRESSURE, channel, channelPressure[channel], 0));
            }
            if (pitchBend[channel] >= 0) {
                messages.add(shortMessage(
                        ShortMessage.PITCH_BEND,
                        channel,
                        pitchBend[channel] & 0x7F,
                        (pitchBend[channel] >>> 7) & 0x7F));
            }
            for (int note = 0; note < 128; note++) {
                if (activeNotes[channel][note] <= 0) continue;
                messages.add(shortMessage(
                        ShortMessage.NOTE_ON,
                        channel,
                        note,
                        Math.max(1, noteVelocities[channel][note])));
            }
        }
        dispatch(messages);
    }

    private void releaseActiveNotes() {
        for (int channel = 0; channel < 16; channel++) {
            for (int note = 0; note < 128; note++) {
                if (activeNotes[channel][note] > 0) {
                    sendCleanup(ShortMessage.NOTE_OFF, channel, note, 0);
                    activeNotes[channel][note] = 0;
                }
            }
            sendCleanup(ShortMessage.CONTROL_CHANGE, channel, 64, 0);
        }
    }

    private void sendCleanup(int command, int channel, int data1, int data2) {
        receiver.send(shortMessage(command, channel, data1, data2), -1L);
    }

    private void dispatch(List<ShortMessage> messages) {
        if (messages == null || messages.isEmpty()) return;
        for (ShortMessage message : messages) receiver.send(message, -1L);
    }

    private static ShortMessage shortMessage(
            int command, int channel, int data1, int data2) {
        ShortMessage message = new ShortMessage();
        try {
            message.setMessage(command, channel, data1, data2);
            return message;
        } catch (InvalidMidiDataException e) {
            throw new IllegalStateException("Invalid projected MIDI event.", e);
        }
    }

    @Override
    public void close() {
        if (closed) return;
        releaseActiveNotes();
        closed = true;
    }

    private static List<Event> buildNativeInitialEvents(
            MidiPlan plan, TimingModel timing, int endRawTick) {
        List<Event> events = new ArrayList<Event>();
        for (MidiPlan.MappedControlEvent control : plan.mappedControls) {
            if (control.rawTick < endRawTick) {
                events.add(controlEvent(timing.rawTickToMicros(control.rawTick), control));
            }
        }
        for (MidiPlan.CompiledNote note : plan.notes) {
            if (note.rawStartTick < endRawTick) {
                events.add(noteOnEvent(timing.rawTickToMicros(note.rawStartTick), note));
            }
            if (note.rawEndTick <= endRawTick) {
                events.add(noteOffEvent(timing.rawTickToMicros(note.rawEndTick), note));
            }
        }
        sort(events);
        return Collections.unmodifiableList(events);
    }

    private static List<Event> buildLinearEvents(MidiPlan plan, TimingModel timing) {
        List<Event> events = new ArrayList<Event>();
        for (MidiPlan.MappedControlEvent control : plan.mappedControls) {
            events.add(controlEvent(timing.rawTickToMicros(control.rawTick), control));
        }
        for (MidiPlan.CompiledNote note : plan.notes) {
            events.add(noteOnEvent(timing.rawTickToMicros(note.rawStartTick), note));
            events.add(noteOffEvent(timing.rawTickToMicros(note.rawEndTick), note));
        }
        sort(events);
        return Collections.unmodifiableList(events);
    }

    private static Event controlEvent(long micros, MidiPlan.MappedControlEvent control) {
        return new Event(
                micros,
                1,
                control.midiChannel,
                control.status,
                control.data1,
                control.data2,
                control.order);
    }

    private static Event noteOnEvent(long micros, MidiPlan.CompiledNote note) {
        return new Event(
                micros,
                2,
                note.midiChannel,
                ShortMessage.NOTE_ON,
                note.midiNote,
                note.velocity,
                noteOrder(note));
    }

    private static Event noteOffEvent(long micros, MidiPlan.CompiledNote note) {
        return new Event(
                micros,
                0,
                note.midiChannel,
                ShortMessage.NOTE_OFF,
                note.midiNote,
                0,
                noteOrder(note));
    }

    private static int noteOrder(MidiPlan.CompiledNote note) {
        return (note.sourceTrack * 16) + note.sourceVoice;
    }

    private static void sort(List<Event> events) {
        Collections.sort(events, EVENT_ORDER);
    }

    private static final Comparator<Event> EVENT_ORDER = new Comparator<Event>() {
        @Override
        public int compare(Event left, Event right) {
            int byTime = Long.compare(left.micros, right.micros);
            if (byTime != 0) return byTime;
            int byChannel = Integer.compare(left.channel, right.channel);
            if (byChannel != 0) return byChannel;
            int byPhase = Integer.compare(left.phase, right.phase);
            if (byPhase != 0) return byPhase;
            int byOrder = Integer.compare(left.order, right.order);
            if (byOrder != 0) return byOrder;
            int byData1 = Integer.compare(left.data1, right.data1);
            if (byData1 != 0) return byData1;
            return Integer.compare(left.data2, right.data2);
        }
    };

    private static int[] filled(int count, int value) {
        int[] values = new int[count];
        Arrays.fill(values, value);
        return values;
    }

    private static int[][] filled(int rows, int columns, int value) {
        int[][] values = new int[rows][columns];
        for (int row = 0; row < rows; row++) Arrays.fill(values[row], value);
        return values;
    }

    private static long safeMultiply(long left, long right) {
        if (left == 0L || right == 0L) return 0L;
        if (left > Long.MAX_VALUE / right) return Long.MAX_VALUE;
        return left * right;
    }

    private static long safeAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
    }

    private static final class Event {
        final long micros;
        final int phase;
        final int channel;
        final int command;
        final int data1;
        final int data2;
        final int order;

        Event(long micros, int phase, int channel, int command, int data1, int data2, int order) {
            this.micros = Math.max(0L, micros);
            this.phase = phase;
            this.channel = channel;
            this.command = command;
            this.data1 = data1;
            this.data2 = data2;
            this.order = order;
        }
    }

    private static final class Scheduled {
        final long transportMicros;
        final Event event;

        Scheduled(long transportMicros, Event event) {
            this.transportMicros = transportMicros;
            this.event = event;
        }
    }
}
