package midi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Expands one finite host-loop request into a linear MidiPlan. */
public final class MidiLoopMaterializer {
    public MidiPlan materializeFiniteLoop(MidiPlan source, int requestedLoopCount) {
        if (source == null) {
            throw new IllegalArgumentException("MIDI plan is required.");
        }
        if (!source.loopInfo.hasLoop || requestedLoopCount < 0) {
            return source;
        }

        long loopStartTick = Math.max(0L, source.loopInfo.loopStartMidiTick);
        long loopEndTick = Math.max(loopStartTick + 1L, source.loopInfo.loopEndMidiTick);
        long loopBodyTickLength = Math.max(1L, loopEndTick - loopStartTick);
        int loopPassCount = Math.max(1, requestedLoopCount + 1);

        List<MidiPlan.TempoPoint> tempoPoints = materializeTempoPoints(
                source,
                loopStartTick,
                loopEndTick,
                loopBodyTickLength,
                loopPassCount);
        List<MidiPlan.MappedControlEvent> controls = materializeControls(
                source,
                loopStartTick,
                loopEndTick,
                loopBodyTickLength,
                loopPassCount);
        List<MidiPlan.CompiledNote> notes = materializeNotes(
                source,
                loopStartTick,
                loopEndTick,
                loopBodyTickLength,
                loopPassCount);

        long contentEndTick = loopStartTick + (loopBodyTickLength * loopPassCount);
        for (MidiPlan.MappedControlEvent control : controls) {
            contentEndTick = Math.max(contentEndTick, control.midiTick);
        }
        for (MidiPlan.CompiledNote note : notes) {
            contentEndTick = Math.max(contentEndTick, note.midiEndTick);
        }
        contentEndTick = Math.max(1L, contentEndTick);

        MidiPlan.LoopInfo materializedLoop = new MidiPlan.LoopInfo(
                source.loopInfo.hasLoop,
                source.loopInfo.loopSlot,
                source.loopInfo.repeatCount,
                source.loopInfo.loopStartRawTick,
                source.loopInfo.loopEndRawTick,
                source.loopInfo.loopStartMidiTick,
                source.loopInfo.loopEndMidiTick,
                true,
                loopPassCount,
                source.loopInfo.warnings);

        return new MidiPlan(
                tempoPoints,
                materializedLoop,
                source.channelAssignments,
                source.outputLanePlan,
                notes,
                controls,
                contentEndTick,
                source.warnings);
    }

    private static List<MidiPlan.TempoPoint> materializeTempoPoints(
            MidiPlan source,
            long loopStartTick,
            long loopEndTick,
            long loopBodyTickLength,
            int loopPassCount) {
        List<MidiPlan.TempoPoint> result = new ArrayList<MidiPlan.TempoPoint>();
        MidiPlan.TempoPoint active = source.tempoPoints.get(0);
        result.add(new MidiPlan.TempoPoint(
                active.rawTick,
                0L,
                active.timebase,
                active.tempo,
                active.mpqn,
                active.synthetic));

        for (MidiPlan.TempoPoint point : source.tempoPoints) {
            if (point.midiTick <= 0L) {
                continue;
            }
            if (point.midiTick < loopStartTick) {
                result.add(point);
                continue;
            }
            if (point.midiTick >= loopEndTick) {
                continue;
            }
            for (int passIndex = 0; passIndex < loopPassCount; passIndex++) {
                result.add(new MidiPlan.TempoPoint(
                        point.rawTick,
                        point.midiTick + (loopBodyTickLength * passIndex),
                        point.timebase,
                        point.tempo,
                        point.mpqn,
                        point.synthetic));
            }
        }
        Collections.sort(result, TEMPO_COMPARATOR);
        return result;
    }

    private static List<MidiPlan.MappedControlEvent> materializeControls(
            MidiPlan source,
            long loopStartTick,
            long loopEndTick,
            long loopBodyTickLength,
            int loopPassCount) {
        List<MidiPlan.MappedControlEvent> result = new ArrayList<MidiPlan.MappedControlEvent>();
        for (MidiPlan.MappedControlEvent control : source.mappedControls) {
            if (control.midiTick < 0L) {
                continue;
            }
            if (control.midiTick < loopStartTick) {
                result.add(control);
                continue;
            }
            if (control.midiTick >= loopEndTick) {
                continue;
            }
            for (int passIndex = 0; passIndex < loopPassCount; passIndex++) {
                result.add(copyControl(
                        control,
                        control.midiTick + (loopBodyTickLength * passIndex),
                        control.order));
            }
        }
        return result;
    }

    private static List<MidiPlan.CompiledNote> materializeNotes(
            MidiPlan source,
            long loopStartTick,
            long loopEndTick,
            long loopBodyTickLength,
            int loopPassCount) {
        List<MidiPlan.CompiledNote> result = new ArrayList<MidiPlan.CompiledNote>();
        for (MidiPlan.CompiledNote note : source.notes) {
            if (note.midiStartTick < loopStartTick) {
                result.add(note);
                continue;
            }
            if (note.midiStartTick >= loopEndTick) {
                continue;
            }
            for (int passIndex = 0; passIndex < loopPassCount; passIndex++) {
                long shift = loopBodyTickLength * passIndex;
                result.add(copyNote(note, note.midiStartTick + shift, note.midiEndTick + shift));
            }
        }
        return result;
    }

    static MidiPlan.MappedControlEvent copyControl(
            MidiPlan.MappedControlEvent source,
            long midiTick,
            int order) {
        return new MidiPlan.MappedControlEvent(
                source.sourceTrack,
                source.sourceCommand,
                source.sourceName,
                source.rawTick,
                source.midiChannel,
                source.logicalChannel,
                source.midiTrackIndex,
                midiTick,
                source.status,
                source.data1,
                source.data2,
                source.patchWord,
                source.rawPatchWord,
                source.latePatchEntry,
                source.patchSource,
                source.nativeMode,
                source.nativeBank,
                source.nativeProgram,
                source.nativeKind,
                source.nativeSub,
                source.nativeValue,
                source.hostMapping,
                source.hostMappingProxy,
                source.sourceOrder,
                order);
    }

    static MidiPlan.CompiledNote copyNote(MidiPlan.CompiledNote source, long midiStartTick, long midiEndTick) {
        return new MidiPlan.CompiledNote(
                source.sourceTrack,
                source.sourceVoice,
                source.logicalChannel,
                source.midiChannel,
                source.midiTrackIndex,
                source.midiNote,
                source.velocity,
                source.rawStartTick,
                source.rawEndTick,
                midiStartTick,
                midiEndTick);
    }

    private static final Comparator<MidiPlan.TempoPoint> TEMPO_COMPARATOR =
            new Comparator<MidiPlan.TempoPoint>() {
                @Override
                public int compare(MidiPlan.TempoPoint left, MidiPlan.TempoPoint right) {
                    return Long.compare(left.midiTick, right.midiTick);
                }
            };
}
