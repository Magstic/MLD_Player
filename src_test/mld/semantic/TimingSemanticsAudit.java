package mld.semantic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import mld.format.MldDocument;
import mld.decode.SystemEvent;
import mld.decode.DecodedTrack;
import mld.decode.TrackEvent;

/** Regression audit for timing rules established from MFiPluginMFi5.dll. */
public final class TimingSemanticsAudit {
    public static void main(String[] args) {
        assertPoint("native default", compile(Collections.<TrackEvent>emptyList(), 48), 0, 0, 48, 125);

        List<TrackEvent> sameTick = new ArrayList<TrackEvent>();
        sameTick.add(system(0, 0, 0, 0xC3, 240, 48));
        sameTick.add(system(1, 0, 0, 0xC3, 97, 48));
        SemanticTestSupport sameTickTimeline = compile(sameTick, 48);
        if (sameTickTimeline.program.timing.points.size() != 1) {
            fail("same-tick C3 coalescing", "expected exactly one effective tempo point, got "
                    + sameTickTimeline.program.timing.points.size());
        }
        assertPoint("same-tick C3 final state", sameTickTimeline, 0, 0, 48, 97);

        List<TrackEvent> lowTempo = new ArrayList<TrackEvent>();
        lowTempo.add(system(0, 0, 0, 0xC4, 0, 96));
        assertPoint("Cx low tempo clamp", compile(lowTempo, 48), 0, 0, 96, 20);

        List<TrackEvent> relativeAndReset = new ArrayList<TrackEvent>();
        relativeAndReset.add(system(0, 0, 0, 0xC4, 20, 96));
        relativeAndReset.add(system(1, 0, 0, 0xBC, 0x7F, -1));
        relativeAndReset.add(system(2, 0, 0, 0xBC, 0x80, -1)); // rejected by native 7-bit gate
        relativeAndReset.add(system(3, 48, 48, 0xBF, 0, -1));
        SemanticTestSupport relativeTimeline = compile(relativeAndReset, 96);
        if (relativeTimeline.program.timing.points.size() != 2) {
            fail("BC/BF point count", "expected two effective points, got "
                    + relativeTimeline.program.timing.points.size());
        }
        assertPoint("BC 7-bit relative delta", relativeTimeline, 0, 0, 96, 83);
        assertPoint("BF preserves timebase", relativeTimeline, 1, 48, 96, 125);
        if (relativeTimeline.midi.tempoPoints.get(1).midiTick != 960L) {
            fail("BF midi tick", "expected 960, got " + relativeTimeline.midi.tempoPoints.get(1).midiTick);
        }

        List<TrackEvent> reservedCx = new ArrayList<TrackEvent>();
        reservedCx.add(system(0, 0, 0, 0xC7, 200, -1));
        assertPoint("C7 ignored", compile(reservedCx, 48), 0, 0, 48, 125);

        System.out.println("TimingSemanticsAudit: PASS");
    }

    private static SemanticTestSupport compile(List<TrackEvent> events, int totalRawTicks) {
        DecodedTrack decoded = new DecodedTrack(
                0, 0, totalRawTicks, 0, 0, events.size(), 0, events, Collections.<String>emptyList());
        MldDocument file = new MldDocument(
                new byte[0], "melo", 0, 0, 0, 0, 1, 1, 0,
                Collections.<Long>emptyList(), Collections.emptyList(), Collections.emptyList());
        return SemanticTestSupport.compile(file, decoded);
    }

    private static SystemEvent system(int eventIndex, int delta, int rawTick, int command, int value, int timebase) {
        return new SystemEvent(0, eventIndex, delta, rawTick, command, value, "audit", -1, timebase);
    }

    private static void assertPoint(
            String name, SemanticTestSupport result, int pointIndex, int rawTick, int timebase, int tempo) {
        if (result.program.timing.points.size() <= pointIndex) {
            fail(name, "missing tempo point " + pointIndex);
        }
        TimingModel.Point point = result.program.timing.points.get(pointIndex);
        if (point.rawTick != rawTick || point.timebase != timebase || point.tempo != tempo) {
            fail(name, "expected raw=" + rawTick + " tb=" + timebase + " tempo=" + tempo
                    + ", got raw=" + point.rawTick + " tb=" + point.timebase + " tempo=" + point.tempo);
        }
    }

    private static void fail(String name, String message) {
        throw new AssertionError(name + ": " + message);
    }
}
