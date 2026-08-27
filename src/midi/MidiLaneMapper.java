package midi;

import java.util.ArrayList;
import java.util.List;


/**
 * Host MIDI output adaptation after native semantic compilation.
 *
 * Owns output-lane assignment and the final logical-channel to MIDI-channel
 * remap. Native event state is
 * complete before this layer runs.
 */
final class MidiLaneMapper {
    private static final int MIDI_CHANNEL_COUNT = 16;
    private static final boolean PSM_APPLY_WRITER_EXPORT_OUTPUT_REMAP = true;
    private static final int PSM_GM_DRUM_CHANNEL = 9;
    private static final boolean PSM_DEFAULT_GSMODE = false;
    private static final boolean PSM_DEFAULT_DRAMBANKFLG = false;
    private static final int PSM_DEFAULT_AUTHORITATIVE_SPECIAL_MASK = 1 << PSM_GM_DRUM_CHANNEL;

    private MidiLaneMapper() {
    }

    static Result finalizeOutput(
            int trackCount,
            int[] voiceMap,
            LaneTracker laneTracker,
            List<MidiPlan.CompiledNote> notes,
            List<MidiPlan.MappedControlEvent> mappedControls,
            List<MidiPlan.TempoPoint> tempoPoints,
            MidiPlan.LoopInfo loopInfo,
            long totalMidiTicks,
            List<String> warnings) {
        List<MidiPlan.MappedControlEvent> chased = MidiLiveMixChaser.apply(
                mappedControls, notes, tempoPoints, loopInfo, totalMidiTicks);
        long finalTicks = Math.max(totalMidiTicks, maxMappedControlTick(chased));
        int[] outputChannelMap = buildHostOutputChannelMap(laneTracker);
        List<MidiPlan.OutputLaneAudit> outputLanePlan = createOutputLanePlan(laneTracker, outputChannelMap);
        List<MidiPlan.ChannelAssignment> channelAssignments =
                createChannelAssignments(trackCount, voiceMap, warnings, outputChannelMap);
        return new Result(
                remapCompiledNotes(notes, outputChannelMap),
                remapMappedControls(chased, outputChannelMap),
                channelAssignments,
                outputLanePlan,
                finalTicks);
    }

    static LaneTracker newDefaultLaneTracker() {
        return LaneTracker.seededFreshDefaultPlayPath();
    }

    static int remapMidiChannel(int logicalChannel, int[] outputChannelMap) {
        if (outputChannelMap == null || logicalChannel < 0 || logicalChannel >= outputChannelMap.length) {
            return logicalChannel;
        }
        return clamp(0, MIDI_CHANNEL_COUNT - 1, outputChannelMap[logicalChannel]);
    }

    private static List<MidiPlan.ChannelAssignment> createChannelAssignments(
            int trackCount,
            int[] voiceMap,
            List<String> warnings,
            int[] outputChannelMap) {
        List<MidiPlan.ChannelAssignment> assignments = new ArrayList<MidiPlan.ChannelAssignment>();
        for (int trackIndex = 0; trackIndex < trackCount; trackIndex++) {
            for (int voice = 0; voice < 4; voice++) {
                int logicalChannel = resolveVoiceMap(voiceMap, laneIndex(trackIndex, voice));
                if (logicalChannel >= MIDI_CHANNEL_COUNT) {
                    warnings.add("Track/voice mapping exceeds 16 MIDI channels: track=" + trackIndex + " voice=" + voice);
                    continue;
                }
                if (logicalChannel < 0) {
                    warnings.add("Track/voice mapping resolved outside logical-channel range: track="
                            + trackIndex + " voice=" + voice + " -> " + logicalChannel);
                    continue;
                }
                int midiChannel = remapMidiChannel(logicalChannel, outputChannelMap);
                assignments.add(new MidiPlan.ChannelAssignment(
                        trackIndex,
                        voice,
                        logicalChannel,
                        midiChannel,
                        midiChannel + 1,
                        midiChannel != logicalChannel));
            }
        }
        return assignments;
    }

    private static List<MidiPlan.OutputLaneAudit> createOutputLanePlan(
            LaneTracker outputLaneTracker,
            int[] outputChannelMap) {
        List<MidiPlan.OutputLaneAudit> plan = new ArrayList<MidiPlan.OutputLaneAudit>(MIDI_CHANNEL_COUNT);
        for (int logicalChannel = 0; logicalChannel < MIDI_CHANNEL_COUNT; logicalChannel++) {
            int midiChannel = remapMidiChannel(logicalChannel, outputChannelMap);
            plan.add(new MidiPlan.OutputLaneAudit(
                    logicalChannel,
                    outputLaneTracker != null && outputLaneTracker.isActive(logicalChannel),
                    outputLaneTracker != null && outputLaneTracker.hasAuthoritativeMask(),
                    outputLaneTracker != null && outputLaneTracker.isAuthoritativeSpecial(logicalChannel),
                    midiChannel,
                    midiChannel != logicalChannel));
        }
        return plan;
    }

    private static int[] buildHostOutputChannelMap(LaneTracker outputLaneTracker) {
        int[] outputChannelMap = createIdentityMap(MIDI_CHANNEL_COUNT);
        if (!PSM_APPLY_WRITER_EXPORT_OUTPUT_REMAP || outputLaneTracker == null || !outputLaneTracker.hasAuthoritativeMask()) {
            return outputChannelMap;
        }
        if (outputLaneTracker.usesIdentityMap()) {
            return outputChannelMap;
        }
        int nextMelodicChannel = 0;
        for (int logicalChannel = 0; logicalChannel < MIDI_CHANNEL_COUNT; logicalChannel++) {
            if (!outputLaneTracker.isActive(logicalChannel)) {
                continue;
            }
            if (outputLaneTracker.isAuthoritativeSpecial(logicalChannel)) {
                outputChannelMap[logicalChannel] = PSM_GM_DRUM_CHANNEL;
                continue;
            }
            outputChannelMap[logicalChannel] = nextMelodicChannel;
            nextMelodicChannel = nextSequentialOutputLane(nextMelodicChannel, outputLaneTracker.reservesDrumOutputLane());
        }
        return outputChannelMap;
    }

    private static long maxMappedControlTick(List<MidiPlan.MappedControlEvent> mappedControls) {
        long maxTick = 0L;
        for (MidiPlan.MappedControlEvent control : mappedControls) {
            maxTick = Math.max(maxTick, control.midiTick);
        }
        return maxTick;
    }

    private static List<MidiPlan.CompiledNote> remapCompiledNotes(
            List<MidiPlan.CompiledNote> notes,
            int[] outputChannelMap) {
        List<MidiPlan.CompiledNote> remapped = new ArrayList<MidiPlan.CompiledNote>(notes.size());
        for (MidiPlan.CompiledNote note : notes) {
            int midiChannel = remapMidiChannel(note.midiChannel, outputChannelMap);
            remapped.add(new MidiPlan.CompiledNote(
                    note.sourceTrack,
                    note.sourceVoice,
                    note.logicalChannel,
                    midiChannel,
                    midiChannel + 1,
                    note.midiNote,
                    note.velocity,
                    note.rawStartTick,
                    note.rawEndTick,
                    note.midiStartTick,
                    note.midiEndTick));
        }
        return remapped;
    }

    private static List<MidiPlan.MappedControlEvent> remapMappedControls(
            List<MidiPlan.MappedControlEvent> mappedControls,
            int[] outputChannelMap) {
        List<MidiPlan.MappedControlEvent> remapped =
                new ArrayList<MidiPlan.MappedControlEvent>(mappedControls.size());
        for (MidiPlan.MappedControlEvent control : mappedControls) {
            int midiChannel = remapMidiChannel(control.midiChannel, outputChannelMap);
            remapped.add(new MidiPlan.MappedControlEvent(
                    control.sourceTrack,
                    control.sourceCommand,
                    control.sourceName,
                    control.rawTick,
                    midiChannel,
                    control.logicalChannel,
                    midiChannel + 1,
                    control.midiTick,
                    control.status,
                    control.data1,
                    control.data2,
                    control.patchWord,
                    control.rawPatchWord,
                    control.latePatchEntry,
                    control.patchSource,
                    control.nativeMode,
                    control.nativeBank,
                    control.nativeProgram,
                    control.nativeKind,
                    control.nativeSub,
                    control.nativeValue,
                    control.hostMapping,
                    control.hostMappingProxy,
                    control.sourceOrder,
                    control.order));
        }
        return remapped;
    }

    private static int nextSequentialOutputLane(int current, boolean reserveDrumOutputLane) {
        if (current >= MIDI_CHANNEL_COUNT - 1) {
            return MIDI_CHANNEL_COUNT - 1;
        }
        if (reserveDrumOutputLane && current == (PSM_GM_DRUM_CHANNEL - 1)) {
            return current + 2;
        }
        return current + 1;
    }

    private static int[] createIdentityMap(int count) {
        int[] map = new int[count];
        for (int i = 0; i < count; i++) {
            map[i] = i;
        }
        return map;
    }

    private static int laneIndex(int trackIndex, int voice) {
        return (trackIndex * 4) + voice;
    }

    private static int resolveVoiceMap(int[] voiceMap, int lane) {
        return lane >= 0 && lane < voiceMap.length ? voiceMap[lane] : -1;
    }

    private static int clamp(int min, int max, int value) {
        return Math.max(min, Math.min(max, value));
    }

    static final class Result {
        final List<MidiPlan.CompiledNote> notes;
        final List<MidiPlan.MappedControlEvent> mappedControls;
        final List<MidiPlan.ChannelAssignment> channelAssignments;
        final List<MidiPlan.OutputLaneAudit> outputLanePlan;
        final long totalMidiTicks;

        Result(
                List<MidiPlan.CompiledNote> notes,
                List<MidiPlan.MappedControlEvent> mappedControls,
                List<MidiPlan.ChannelAssignment> channelAssignments,
                List<MidiPlan.OutputLaneAudit> outputLanePlan,
                long totalMidiTicks) {
            this.notes = notes;
            this.mappedControls = mappedControls;
            this.channelAssignments = channelAssignments;
            this.outputLanePlan = outputLanePlan;
            this.totalMidiTicks = totalMidiTicks;
        }
    }

    static final class LaneTracker {
        private final boolean authoritativeMaskKnown;
        private final boolean identityMap;
        private final boolean reserveDrumOutputLane;
        private int activeMask = 0;
        private final int authoritativeSpecialMask;

        private LaneTracker(
                boolean authoritativeMaskKnown,
                boolean identityMap,
                boolean reserveDrumOutputLane,
                int authoritativeSpecialMask) {
            this.authoritativeMaskKnown = authoritativeMaskKnown;
            this.identityMap = identityMap;
            this.reserveDrumOutputLane = reserveDrumOutputLane;
            this.authoritativeSpecialMask = authoritativeSpecialMask;
        }

        static LaneTracker seededFreshDefaultPlayPath() {
            return new LaneTracker(
                    true,
                    PSM_DEFAULT_GSMODE,
                    !PSM_DEFAULT_DRAMBANKFLG,
                    PSM_DEFAULT_AUTHORITATIVE_SPECIAL_MASK);
        }

        void observeActive(int logicalChannel) {
            if (logicalChannel < 0 || logicalChannel >= MIDI_CHANNEL_COUNT) {
                return;
            }
            activeMask |= (1 << logicalChannel);
        }

        boolean isActive(int logicalChannel) {
            return logicalChannel >= 0
                    && logicalChannel < MIDI_CHANNEL_COUNT
                    && ((activeMask >>> logicalChannel) & 1) != 0;
        }

        boolean hasAuthoritativeMask() {
            return authoritativeMaskKnown;
        }

        boolean isAuthoritativeSpecial(int logicalChannel) {
            return logicalChannel >= 0
                    && logicalChannel < MIDI_CHANNEL_COUNT
                    && ((authoritativeSpecialMask >>> logicalChannel) & 1) != 0;
        }

        boolean usesIdentityMap() {
            return identityMap;
        }

        boolean reservesDrumOutputLane() {
            return reserveDrumOutputLane;
        }
    }
}
