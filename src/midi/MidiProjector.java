package midi;

import java.util.ArrayList;
import java.util.List;

import mld.semantic.NativeProgram;

/**
 * Projects authoritative native MLD semantics onto the current host MIDI approximation.
 */
public final class MidiProjector {

    public MidiPlan project(NativeProgram p) {
        if (p == null) throw new IllegalArgumentException("Native program is required.");
        List<String> w = new ArrayList<String>();
        MidiTimingMapper t = new MidiTimingMapper(p.timing);
        MidiPlan.LoopInfo loop = t.projectLoop(p.loop);
        MidiProjectionState.Result r = new MidiProjectionState(t, w).project(p);
        MidiLaneMapper.Result out = MidiLaneMapper.finalizeOutput(
                p.sourceTrackCount,
                p.melody.copyFinalVoiceMap(),
                r.laneTracker,
                r.notes,
                r.controls,
                t.tempoPoints(),
                loop,
                r.totalMidiTicks,
                w);
        return new MidiPlan(t.tempoPoints(), loop, out.channelAssignments, out.outputLanePlan, out.notes, out.mappedControls, out.totalMidiTicks, w);
    }
}
