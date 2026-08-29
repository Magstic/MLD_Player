package mld.semantic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import mld.decode.SystemEvent;
import mld.decode.TrackEvent;

/** Tempo/timebase state for the already scheduled native execution timeline. */
final class TimingState {
    private static final int MIN_TEMPO = 20;
    private static final int MAX_TEMPO = 255;
    private static final Comparator<RawTimingPoint> TIMING_ORDER = new Comparator<RawTimingPoint>(){

        public int compare(RawTimingPoint a, RawTimingPoint b) {
            int x = Integer.compare(a.rawTick, b.rawTick);
            if (x != 0) return x;
            x = Integer.compare(a.trackIndex, b.trackIndex);
            return x != 0 ? x : Integer.compare(a.eventIndex, b.eventIndex);
        }
    };

    private TimingState() {
    }

    static Analysis analyze(List<TrackEvent> events, List<String> warnings) {
        List<RawTimingPoint> seeds = collectTimingSeeds(events);
        Collections.sort(seeds, TIMING_ORDER);
        TimingModel timing = new TimingModel(buildTimingPoints(seeds, warnings));
        return new Analysis(events, timing);
    }

    static Runtime newRuntime() {
        return new Runtime(TimingModel.DEFAULT_TIMEBASE, TimingModel.DEFAULT_TEMPO);
    }

    static void apply(Runtime s, SystemEvent e) {
        if (!isEffectiveTimingEvent(e)) return;
        if (isTempo(e)) {
            s.timebase = e.timebase;
            s.tempo = clamp(MIN_TEMPO, MAX_TEMPO, e.value);
        } else if (e.command == 0xBC) {
            s.tempo = clamp(MIN_TEMPO, MAX_TEMPO, s.tempo + (e.value & 0xFF) - 0x40);
        } else if (e.command == 0xBF) {
            s.tempo = TimingModel.DEFAULT_TEMPO;
        }
    }

    static int durationMilliseconds(int raw, Runtime s) {
        return TimingModel.durationMilliseconds(raw, s.timebase, s.tempo);
    }

    static boolean isTempo(SystemEvent e) {
        return e.command >= 0xC0 && e.command <= 0xCF && e.timebase > 0;
    }

    private static boolean isEffectiveTimingEvent(SystemEvent e) {
        if (e.trackIndex != 0) return false;
        if (isTempo(e)) return true;
        if (e.command == 0xBC) return e.value < 0x80;
        return e.command == 0xBF;
    }

    private static List<RawTimingPoint> collectTimingSeeds(List<TrackEvent> events) {
        List<RawTimingPoint> r = new ArrayList<RawTimingPoint>();
        int tb = 48;
        int tp = 125;
        for (TrackEvent e : events) {
            if (!(e instanceof SystemEvent)) continue;
            SystemEvent s = (SystemEvent)e;
            if (!isEffectiveTimingEvent(s)) continue;
            if (r.isEmpty() && s.rawTick > 0) r.add(new RawTimingPoint(0, tb, tp, -1, -1, true));
            if (isTempo(s)) {
                tb = s.timebase;
                tp = clamp(MIN_TEMPO, MAX_TEMPO, s.value);
            } else if (s.command == 0xBC) tp = clamp(MIN_TEMPO, MAX_TEMPO, tp + (s.value & 255) - 0x40); else if (s.command == 0xBF) tp = 125;
            r.add(new RawTimingPoint(s.rawTick, tb, tp, s.trackIndex, s.eventIndex, false));
        }
        return r;
    }

    private static List<TimingModel.Point> buildTimingPoints(List<RawTimingPoint> seeds, List<String> w) {
        if (seeds.isEmpty()) {
            w.add("No tempo event observed; inserting native default 125 BPM / timebase 48 point.");
            seeds.add(new RawTimingPoint(0, 48, 125, -1, -1, true));
        } else if (seeds.get(0).rawTick > 0) {
            w.add("First tempo event does not start at tick 0; inserting native default point at origin.");
            seeds.add(0, new RawTimingPoint(0, 48, 125, -1, -1, true));
        }
        List<TimingModel.Point> r = new ArrayList<TimingModel.Point>();
        for (RawTimingPoint s : seeds) {
            TimingModel.Point p = new TimingModel.Point(s.rawTick, s.timebase, s.tempo, s.synthetic);
            if (!r.isEmpty() && r.get(r.size() - 1).rawTick == s.rawTick) r.set(r.size() - 1, p); else r.add(p);
        }
        return r;
    }

    private static int clamp(int min, int max, int v) {
        return Math.max(min, Math.min(max, v));
    }

    static final class Analysis {
        final List<TrackEvent> events;
        final TimingModel timing;

        Analysis(List<TrackEvent> events, TimingModel timing) {
            this.events = events;
            this.timing = timing;
        }
    }

    static final class Runtime {
        int timebase;
        int tempo;

        Runtime(int tb, int t) {
            timebase = tb;
            tempo = t;
        }
    }

    private static final class RawTimingPoint {
        final int rawTick;
        final int timebase;
        final int tempo;
        final int trackIndex;
        final int eventIndex;
        final boolean synthetic;

        RawTimingPoint(int r, int tb, int tp, int tr, int e, boolean s) {
            rawTick = r;
            timebase = tb;
            tempo = tp;
            trackIndex = tr;
            eventIndex = e;
            synthetic = s;
        }
    }
}
