package midi;

import java.util.ArrayList;
import java.util.List;

import mld.semantic.LoopModel;
import mld.semantic.TimingModel;

/**
 * Host PPQ projection of native raw-tick timing.
 */
final class MidiTimingMapper {
    private final List<MidiPlan.TempoPoint> points;

    MidiTimingMapper(TimingModel t) {
        points = build(t);
    }

    List<MidiPlan.TempoPoint> tempoPoints() {
        return points;
    }

    long rawToMidiTick(int raw) {
        MidiPlan.TempoPoint c = points.get(0);
        for (int i = 1; i < points.size(); i++) {
            MidiPlan.TempoPoint n = points.get(i);
            if (n.rawTick > raw) break;
            c = n;
        }
        return c.midiTick + (((long)raw - c.rawTick) * MidiPlan.MIDI_PPQ) / c.timebase;
    }

    MidiPlan.LoopInfo projectLoop(LoopModel l) {
        if (l == null || !l.hasInfiniteLoop()) {
            return new MidiPlan.LoopInfo(false, -1, -1, -1, -1L, -1L,
                    l == null ? java.util.Collections.<String>emptyList() : l.warnings);
        }
        LoopModel.InfiniteRegion r = l.infiniteRegion;
        return new MidiPlan.LoopInfo(
                true,
                r.slot,
                r.loopStartRawTick,
                r.loopEndRawTick,
                rawToMidiTick(r.loopStartRawTick),
                rawToMidiTick(r.loopEndRawTick),
                l.warnings);
    }

    private static List<MidiPlan.TempoPoint> build(TimingModel t) {
        List<MidiPlan.TempoPoint> r = new ArrayList<MidiPlan.TempoPoint>();
        long mt = 0;
        int lastRaw = 0;
        int lastTb = t.points.get(0).timebase;
        for (TimingModel.Point p : t.points) {
            mt += ((long)Math.max(0, p.rawTick - lastRaw) * MidiPlan.MIDI_PPQ) / lastTb;
            r.add(new MidiPlan.TempoPoint(p.rawTick, mt, p.timebase, p.tempo, 60000000 / Math.max(1, p.tempo), p.synthetic));
            lastRaw = p.rawTick;
            lastTb = p.timebase;
        }
        return r;
    }
}
