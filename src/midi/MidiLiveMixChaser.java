package midi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sound.midi.ShortMessage;

/** Host-only approximation of native ordinary live level/pan smoothing. */
final class MidiLiveMixChaser {
    private static final int ORDINARY_NATIVE_REPLACEMENT_OVERLAP_SAMPLES = 128;
    private static final int ORDINARY_NATIVE_RENDER_OUTPUT_RATE = 32000;
    private static final boolean HOST_APPROXIMATE_ORDINARY_LIVE_MIX = true;
    private static final int HOST_LIVE_MIX_CHASE_STEP_COUNT = 4;
    private static final String HOST_LIVE_MIX_CHASE_SUFFIX = "_live_mix_chase";

    private static final Comparator<MidiPlan.MappedControlEvent> CONTROL_ORDER =
            new Comparator<MidiPlan.MappedControlEvent>() {
                @Override
                public int compare(
                        MidiPlan.MappedControlEvent left,
                        MidiPlan.MappedControlEvent right) {
                    int byTick = Long.compare(left.midiTick, right.midiTick);
                    if (byTick != 0) {
                        return byTick;
                    }
                    int byChannel = Integer.compare(left.midiChannel, right.midiChannel);
                    if (byChannel != 0) {
                        return byChannel;
                    }
                    int byOrder = Integer.compare(left.order, right.order);
                    if (byOrder != 0) {
                        return byOrder;
                    }
                    return Integer.compare(left.data1, right.data1);
                }
            };

    private MidiLiveMixChaser() {
    }

    static List<MidiPlan.MappedControlEvent> apply(
            List<MidiPlan.MappedControlEvent> mappedControls,
            List<MidiPlan.CompiledNote> notes,
            List<MidiPlan.TempoPoint> tempoPoints,
            MidiPlan.LoopInfo loopInfo,
            long totalMidiTicks) {
        if (!HOST_APPROXIMATE_ORDINARY_LIVE_MIX || mappedControls.isEmpty() || notes.isEmpty()) {
            return mappedControls;
        }

        List<MidiPlan.MappedControlEvent> ordered =
                new ArrayList<MidiPlan.MappedControlEvent>(mappedControls);
        Collections.sort(ordered, CONTROL_ORDER);

        Map<Integer, List<MidiPlan.CompiledNote>> notesByChannel = groupNotesByMidiChannel(notes);
        Map<Integer, List<MidiPlan.MappedControlEvent>> groupedStreams =
                new LinkedHashMap<Integer, List<MidiPlan.MappedControlEvent>>();
        List<MidiPlan.MappedControlEvent> passthrough =
                new ArrayList<MidiPlan.MappedControlEvent>(ordered.size());

        for (MidiPlan.MappedControlEvent control : ordered) {
            if (isVolumeOrPanControl(control)) {
                Integer key = Integer.valueOf((control.midiChannel << 8) | (control.data1 & 0x7F));
                List<MidiPlan.MappedControlEvent> stream = groupedStreams.get(key);
                if (stream == null) {
                    stream = new ArrayList<MidiPlan.MappedControlEvent>();
                    groupedStreams.put(key, stream);
                }
                stream.add(control);
            } else {
                passthrough.add(control);
            }
        }

        List<MidiPlan.MappedControlEvent> rewritten =
                new ArrayList<MidiPlan.MappedControlEvent>(mappedControls.size() + 64);
        rewritten.addAll(passthrough);

        int nextOrder = 0;
        for (List<MidiPlan.MappedControlEvent> stream : groupedStreams.values()) {
            Integer previousValue = null;
            for (int i = 0; i < stream.size(); i++) {
                MidiPlan.MappedControlEvent control = stream.get(i);
                int targetValue = clamp(0, 127, control.data2);
                long boundaryTick = computeMixChaseBoundaryTick(stream, i, loopInfo, totalMidiTicks);
                long chaseWindowTicks = Math.min(
                        computeNativeSmoothingWindowTicks(control.midiTick, tempoPoints),
                        Math.max(0L, boundaryTick - control.midiTick));

                if (previousValue != null
                        && isOrdinaryLiveMixProxyCandidate(control)
                        && chaseWindowTicks > 0L
                        && hasStrictlyActiveNote(notesByChannel.get(Integer.valueOf(control.midiChannel)), control.midiTick)) {
                    nextOrder = appendMixChasedControls(
                            rewritten,
                            control,
                            previousValue.intValue(),
                            targetValue,
                            chaseWindowTicks,
                            nextOrder);
                } else {
                    rewritten.add(copyMappedControl(control, control.midiTick, targetValue, nextOrder++, control.sourceName));
                }
                previousValue = Integer.valueOf(targetValue);
            }
        }

        Collections.sort(rewritten, CONTROL_ORDER);
        return rewritten;
    }

    private static Map<Integer, List<MidiPlan.CompiledNote>> groupNotesByMidiChannel(
            List<MidiPlan.CompiledNote> notes) {
        Map<Integer, List<MidiPlan.CompiledNote>> grouped =
                new LinkedHashMap<Integer, List<MidiPlan.CompiledNote>>();
        for (MidiPlan.CompiledNote note : notes) {
            Integer key = Integer.valueOf(note.midiChannel);
            List<MidiPlan.CompiledNote> channelNotes = grouped.get(key);
            if (channelNotes == null) {
                channelNotes = new ArrayList<MidiPlan.CompiledNote>();
                grouped.put(key, channelNotes);
            }
            channelNotes.add(note);
        }
        return grouped;
    }

    private static boolean isVolumeOrPanControl(MidiPlan.MappedControlEvent control) {
        return control != null
                && control.status == ShortMessage.CONTROL_CHANGE
                && (control.data1 == 7 || control.data1 == 10);
    }

    private static boolean isOrdinaryLiveMixProxyCandidate(MidiPlan.MappedControlEvent control) {
        if (!isVolumeOrPanControl(control)) {
            return false;
        }
        if (control.sourceCommand == 0xE3) {
            return control.data1 == 10;
        }
        return (control.sourceCommand == 0xE2 || control.sourceCommand == 0xE6) && control.data1 == 7;
    }

    private static boolean hasStrictlyActiveNote(List<MidiPlan.CompiledNote> notes, long midiTick) {
        if (notes == null || notes.isEmpty()) {
            return false;
        }
        for (MidiPlan.CompiledNote note : notes) {
            if (note.midiStartTick < midiTick && note.midiEndTick > midiTick) {
                return true;
            }
        }
        return false;
    }

    private static long computeMixChaseBoundaryTick(
            List<MidiPlan.MappedControlEvent> stream,
            int index,
            MidiPlan.LoopInfo loopInfo,
            long totalMidiTicks) {
        MidiPlan.MappedControlEvent control = stream.get(index);
        long boundary = Math.max(control.midiTick, totalMidiTicks);
        if (index + 1 < stream.size()) {
            boundary = Math.min(boundary, stream.get(index + 1).midiTick);
        }
        if (loopInfo != null && loopInfo.hasLoop && control.midiTick < loopInfo.loopEndMidiTick) {
            boundary = Math.min(boundary, loopInfo.loopEndMidiTick);
        }
        return Math.max(control.midiTick, boundary);
    }

    private static long computeNativeSmoothingWindowTicks(
            long midiTick,
            List<MidiPlan.TempoPoint> tempoPoints) {
        MidiPlan.TempoPoint point = tempoPointAtOrBefore(tempoPoints, midiTick);
        long mpqn = point != null ? point.mpqn : 500000L;
        long numerator = (long) ORDINARY_NATIVE_REPLACEMENT_OVERLAP_SAMPLES
                * MidiPlan.MIDI_PPQ
                * 1000000L;
        long denominator = (long) ORDINARY_NATIVE_RENDER_OUTPUT_RATE * Math.max(1L, mpqn);
        return Math.max(1L, (numerator + (denominator / 2L)) / denominator);
    }

    private static MidiPlan.TempoPoint tempoPointAtOrBefore(
            List<MidiPlan.TempoPoint> tempoPoints,
            long midiTick) {
        if (tempoPoints == null || tempoPoints.isEmpty()) {
            return null;
        }
        MidiPlan.TempoPoint current = tempoPoints.get(0);
        for (MidiPlan.TempoPoint point : tempoPoints) {
            if (point.midiTick > midiTick) {
                break;
            }
            current = point;
        }
        return current;
    }

    private static int appendMixChasedControls(
            List<MidiPlan.MappedControlEvent> rewritten,
            MidiPlan.MappedControlEvent original,
            int previousValue,
            int targetValue,
            long chaseWindowTicks,
            int nextOrder) {
        int delta = targetValue - previousValue;
        if (delta == 0) {
            rewritten.add(copyMappedControl(original, original.midiTick, targetValue, nextOrder++, original.sourceName));
            return nextOrder;
        }

        int eventCount = (int) Math.max(2L, Math.min((long) HOST_LIVE_MIX_CHASE_STEP_COUNT, chaseWindowTicks + 1L));
        long lastTick = Long.MIN_VALUE;
        int lastValue = previousValue;
        String syntheticName = original.sourceName + HOST_LIVE_MIX_CHASE_SUFFIX;

        for (int step = 1; step <= eventCount; step++) {
            long offset = eventCount == 1
                    ? 0L
                    : (chaseWindowTicks * (step - 1L)) / (eventCount - 1L);
            long tick = original.midiTick + offset;
            int value = previousValue + (int) Math.round((double) delta * step / (double) eventCount);
            if (step == eventCount) {
                value = targetValue;
            }
            if (value == lastValue && step < eventCount) {
                continue;
            }
            if (tick == lastTick && value == lastValue) {
                continue;
            }
            rewritten.add(copyMappedControl(original, tick, value, nextOrder++, syntheticName));
            lastTick = tick;
            lastValue = value;
        }

        if (lastValue != targetValue) {
            rewritten.add(copyMappedControl(
                    original,
                    original.midiTick + chaseWindowTicks,
                    targetValue,
                    nextOrder++,
                    syntheticName));
        }
        return nextOrder;
    }

    private static MidiPlan.MappedControlEvent copyMappedControl(
            MidiPlan.MappedControlEvent original,
            long midiTick,
            int data2,
            int order,
            String sourceName) {
        return new MidiPlan.MappedControlEvent(
                original.sourceTrack,
                original.sourceCommand,
                sourceName,
                original.rawTick,
                original.midiChannel,
                original.logicalChannel,
                original.midiTrackIndex,
                midiTick,
                original.status,
                original.data1,
                clamp(0, 127, data2),
                original.patchWord,
                original.rawPatchWord,
                original.latePatchEntry,
                original.patchSource,
                original.nativeMode,
                original.nativeBank,
                original.nativeProgram,
                original.nativeKind,
                original.nativeSub,
                original.nativeValue,
                original.hostMapping,
                original.hostMappingProxy,
                original.sourceOrder,
                order);
    }

    private static int clamp(int min, int max, int value) {
        return Math.max(min, Math.min(max, value));
    }

}
