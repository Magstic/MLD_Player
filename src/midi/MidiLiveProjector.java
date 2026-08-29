package midi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import javax.sound.midi.ShortMessage;

import mld.semantic.MelodyProgram;
import mld.semantic.NativeLoopRuntime;
import mld.semantic.NativeProgram;

/** Projects one continuing native semantic cycle into direct-playback MIDI events. */
public final class MidiLiveProjector {
    private final int[] outputChannels = new int[16];
    private final boolean[] specialChannels = new boolean[16];
    private final List<String> warnings = new ArrayList<String>();
    private final MidiProjectionState state;

    public MidiLiveProjector(MidiPlan basePlan, NativeProgram baseProgram) {
        if (basePlan == null || baseProgram == null) {
            throw new IllegalArgumentException("Base MIDI and native programs are required.");
        }
        for (int channel = 0; channel < outputChannels.length; channel++) {
            outputChannels[channel] = channel;
        }
        for (MidiPlan.OutputLaneAudit lane : basePlan.outputLanePlan) {
            if (lane.logicalChannel < 0 || lane.logicalChannel >= outputChannels.length) continue;
            outputChannels[lane.logicalChannel] = lane.midiChannel;
            specialChannels[lane.logicalChannel] = lane.authoritativeSpecialLane;
        }
        state = new MidiProjectionState(new MidiTimingMapper(baseProgram.timing), warnings);
        state.project(baseProgram);
        state.discardProjectedOutput();
    }

    public List<Event> project(NativeLoopRuntime.Cycle cycle) {
        if (cycle == null) throw new IllegalArgumentException("Native semantic cycle is required.");
        List<Event> result = new ArrayList<Event>();
        state.setTiming(new MidiTimingMapper(cycle.localTiming));
        state.project(cycle.program);
        for (MidiPlan.MappedControlEvent control : state.drainProjectedControls()) {
            int channel = outputChannel(control.logicalChannel, control.midiChannel);
            result.add(new Event(
                    cycle.transportMicrosAtRawTick(cycle.rawStartTick + control.rawTick),
                    1,
                    channel,
                    control.status,
                    control.data1,
                    control.data2,
                    control.order));
        }
        for (MelodyProgram.NoteAction action
                : cycle.program.melody.noteActions) {
            int logical = action.logicalChannel;
            if (logical < 0 || logical >= 16) continue;
            int base = specialChannels[logical] || action.channel.mode == 1 ? 35 : 45;
            int note = clamp(0, 127, base + action.pitchOffset);
            result.add(new Event(
                    cycle.transportMicrosAtRawTick(cycle.rawStartTick + action.rawTick),
                    action.noteOn ? 2 : 0,
                    outputChannels[logical],
                    action.noteOn ? ShortMessage.NOTE_ON : ShortMessage.NOTE_OFF,
                    note,
                    action.noteOn ? clamp(1, 127, action.velocity) : 0,
                    action.order));
        }
        Collections.sort(result, EVENT_ORDER);
        return Collections.unmodifiableList(result);
    }

    private int outputChannel(int logicalChannel, int projectedChannel) {
        return logicalChannel >= 0 && logicalChannel < outputChannels.length
                ? outputChannels[logicalChannel] : projectedChannel;
    }

    private static int clamp(int min, int max, int value) {
        return Math.max(min, Math.min(max, value));
    }

    private static final Comparator<Event> EVENT_ORDER = new Comparator<Event>() {
        @Override
        public int compare(Event left, Event right) {
            int byTime = Long.compare(left.transportMicros, right.transportMicros);
            if (byTime != 0) return byTime;
            int byChannel = Integer.compare(left.channel, right.channel);
            if (byChannel != 0) return byChannel;
            int byPhase = Integer.compare(left.phase, right.phase);
            if (byPhase != 0) return byPhase;
            return Integer.compare(left.order, right.order);
        }
    };

    public static final class Event {
        public final long transportMicros;
        public final int phase;
        public final int channel;
        public final int command;
        public final int data1;
        public final int data2;
        public final int order;

        Event(
                long transportMicros,
                int phase,
                int channel,
                int command,
                int data1,
                int data2,
                int order) {
            this.transportMicros = transportMicros;
            this.phase = phase;
            this.channel = channel;
            this.command = command;
            this.data1 = data1;
            this.data2 = data2;
            this.order = order;
        }
    }
}
