package midi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sound.midi.ShortMessage;

/** Creates a local-tick MidiPlan window for exported intro/loop segments. */
public final class MidiPlanSegmenter {
    public MidiPlan slice(MidiPlan source, long segmentStart, long segmentEnd, boolean primeState) {
        if (source == null) {
            throw new IllegalArgumentException("MIDI plan is required.");
        }
        if (segmentStart < 0L || segmentEnd < segmentStart) {
            throw new IllegalArgumentException("Invalid MIDI segment range.");
        }

        long segmentLength = Math.max(1L, segmentEnd - segmentStart);
        List<MidiPlan.TempoPoint> tempoPoints = sliceTempoPoints(source, segmentStart, segmentEnd);
        List<MidiPlan.MappedControlEvent> controls = new ArrayList<MidiPlan.MappedControlEvent>();
        if (primeState && segmentStart > 0L) {
            controls.addAll(primedControls(source, segmentStart));
        }
        controls.addAll(sliceControls(source, segmentStart, segmentEnd));
        List<MidiPlan.CompiledNote> notes = sliceNotes(source, segmentStart, segmentEnd);

        MidiPlan.LoopInfo noLoop = new MidiPlan.LoopInfo(
                false,
                -1,
                0,
                -1,
                -1,
                -1L,
                -1L,
                source.loopInfo.warnings);
        return new MidiPlan(
                tempoPoints,
                noLoop,
                source.channelAssignments,
                source.outputLanePlan,
                notes,
                controls,
                segmentLength,
                source.warnings);
    }

    private static List<MidiPlan.TempoPoint> sliceTempoPoints(
            MidiPlan source,
            long segmentStart,
            long segmentEnd) {
        MidiPlan.TempoPoint active = source.tempoPoints.get(0);
        for (MidiPlan.TempoPoint point : source.tempoPoints) {
            if (point.midiTick <= segmentStart) {
                active = point;
            } else {
                break;
            }
        }

        List<MidiPlan.TempoPoint> result = new ArrayList<MidiPlan.TempoPoint>();
        result.add(new MidiPlan.TempoPoint(
                active.rawTick,
                0L,
                active.timebase,
                active.tempo,
                active.mpqn,
                active.synthetic));
        for (MidiPlan.TempoPoint point : source.tempoPoints) {
            if (point.midiTick <= segmentStart || point.midiTick >= segmentEnd) {
                continue;
            }
            result.add(new MidiPlan.TempoPoint(
                    point.rawTick,
                    point.midiTick - segmentStart,
                    point.timebase,
                    point.tempo,
                    point.mpqn,
                    point.synthetic));
        }
        return result;
    }

    private static List<MidiPlan.MappedControlEvent> primedControls(MidiPlan source, long segmentStart) {
        Map<String, MidiPlan.MappedControlEvent> latest =
                new LinkedHashMap<String, MidiPlan.MappedControlEvent>();
        List<MidiPlan.MappedControlEvent> controls =
                new ArrayList<MidiPlan.MappedControlEvent>(source.mappedControls);
        Collections.sort(controls, MAPPED_CONTROL_COMPARATOR);
        for (MidiPlan.MappedControlEvent control : controls) {
            if (control.midiTick >= segmentStart) {
                break;
            }
            latest.put(controlKey(control), control);
        }

        List<MidiPlan.MappedControlEvent> primed =
                new ArrayList<MidiPlan.MappedControlEvent>(latest.values());
        Collections.sort(primed, PRIMED_CONTROL_COMPARATOR);
        List<MidiPlan.MappedControlEvent> result = new ArrayList<MidiPlan.MappedControlEvent>();
        for (int index = 0; index < primed.size(); index++) {
            MidiPlan.MappedControlEvent control = primed.get(index);
            result.add(MidiLoopMaterializer.copyControl(
                    control,
                    0L,
                    Integer.MIN_VALUE + index));
        }
        return result;
    }

    private static List<MidiPlan.MappedControlEvent> sliceControls(
            MidiPlan source,
            long segmentStart,
            long segmentEnd) {
        List<MidiPlan.MappedControlEvent> result = new ArrayList<MidiPlan.MappedControlEvent>();
        for (MidiPlan.MappedControlEvent control : source.mappedControls) {
            if (control.midiTick < segmentStart || control.midiTick >= segmentEnd) {
                continue;
            }
            result.add(MidiLoopMaterializer.copyControl(
                    control,
                    control.midiTick - segmentStart,
                    control.order));
        }
        return result;
    }

    private static List<MidiPlan.CompiledNote> sliceNotes(
            MidiPlan source,
            long segmentStart,
            long segmentEnd) {
        List<MidiPlan.CompiledNote> result = new ArrayList<MidiPlan.CompiledNote>();
        for (MidiPlan.CompiledNote note : source.notes) {
            if (note.midiEndTick <= segmentStart || note.midiStartTick >= segmentEnd) {
                continue;
            }
            long localStart = Math.max(note.midiStartTick, segmentStart) - segmentStart;
            long localEnd = Math.min(note.midiEndTick, segmentEnd) - segmentStart;
            if (localEnd <= localStart) {
                localEnd = localStart + 1L;
            }
            result.add(MidiLoopMaterializer.copyNote(note, localStart, localEnd));
        }
        return result;
    }

    private static String controlKey(MidiPlan.MappedControlEvent control) {
        if (control.status == ShortMessage.CONTROL_CHANGE) {
            return control.midiChannel + ":cc:" + control.data1;
        }
        if (control.status == ShortMessage.PROGRAM_CHANGE) {
            return control.midiChannel + ":program";
        }
        if (control.status == ShortMessage.PITCH_BEND) {
            return control.midiChannel + ":pitch";
        }
        return control.midiChannel + ":" + control.status;
    }

    private static int orderForControl(MidiPlan.MappedControlEvent control) {
        if (control.status == ShortMessage.CONTROL_CHANGE && control.data1 == 0) return 0;
        if (control.status == ShortMessage.CONTROL_CHANGE && control.data1 == 32) return 1;
        if (control.status == ShortMessage.PROGRAM_CHANGE) return 2;
        if (control.status == ShortMessage.CONTROL_CHANGE && control.data1 == 101) return 3;
        if (control.status == ShortMessage.CONTROL_CHANGE && control.data1 == 100) return 4;
        if (control.status == ShortMessage.CONTROL_CHANGE && control.data1 == 6) return 5;
        if (control.status == ShortMessage.CONTROL_CHANGE && control.data1 == 38) return 6;
        if (control.status == ShortMessage.PITCH_BEND) return 7;
        if (control.status == ShortMessage.CONTROL_CHANGE && control.data1 == 7) return 8;
        if (control.status == ShortMessage.CONTROL_CHANGE && control.data1 == 10) return 9;
        if (control.status == ShortMessage.CONTROL_CHANGE && control.data1 == 1) return 10;
        if (control.status == ShortMessage.CONTROL_CHANGE && control.data1 == 11) return 11;
        return 20;
    }

    private static final Comparator<MidiPlan.MappedControlEvent> MAPPED_CONTROL_COMPARATOR =
            new Comparator<MidiPlan.MappedControlEvent>() {
                @Override
                public int compare(MidiPlan.MappedControlEvent left, MidiPlan.MappedControlEvent right) {
                    int byTick = Long.compare(left.midiTick, right.midiTick);
                    if (byTick != 0) return byTick;
                    int byChannel = Integer.compare(left.midiChannel, right.midiChannel);
                    if (byChannel != 0) return byChannel;
                    int byOrder = Integer.compare(left.order, right.order);
                    if (byOrder != 0) return byOrder;
                    return Integer.compare(left.data1, right.data1);
                }
            };

    private static final Comparator<MidiPlan.MappedControlEvent> PRIMED_CONTROL_COMPARATOR =
            new Comparator<MidiPlan.MappedControlEvent>() {
                @Override
                public int compare(MidiPlan.MappedControlEvent left, MidiPlan.MappedControlEvent right) {
                    int byChannel = Integer.compare(left.midiChannel, right.midiChannel);
                    if (byChannel != 0) return byChannel;
                    int bySemantic = Integer.compare(orderForControl(left), orderForControl(right));
                    if (bySemantic != 0) return bySemantic;
                    return Integer.compare(left.order, right.order);
                }
            };
}
