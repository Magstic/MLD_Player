package midi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable host MIDI projection of a native MLD program. */
public final class MidiPlan {
    public static final int MIDI_PPQ = 1920;

    public final List<TempoPoint> tempoPoints;
    public final LoopInfo loopInfo;
    public final List<ChannelAssignment> channelAssignments;
    public final List<OutputLaneAudit> outputLanePlan;
    public final List<CompiledNote> notes;
    public final List<MappedControlEvent> mappedControls;
    public final long totalMidiTicks;
    public final List<String> warnings;

    public MidiPlan(
            List<TempoPoint> tempoPoints,
            LoopInfo loopInfo,
            List<ChannelAssignment> channelAssignments,
            List<OutputLaneAudit> outputLanePlan,
            List<CompiledNote> notes,
            List<MappedControlEvent> mappedControls,
            long totalMidiTicks,
            List<String> warnings) {
        this.tempoPoints = immutableCopy(tempoPoints);
        this.loopInfo = loopInfo;
        this.channelAssignments = immutableCopy(channelAssignments);
        this.outputLanePlan = immutableCopy(outputLanePlan);
        this.notes = immutableCopy(notes);
        this.mappedControls = immutableCopy(mappedControls);
        this.totalMidiTicks = Math.max(0L, totalMidiTicks);
        this.warnings = immutableCopy(warnings);
    }

    private static <T> List<T> immutableCopy(List<T> source) {
        return Collections.unmodifiableList(new ArrayList<T>(source));
    }

    public static final class TempoPoint {
        public final int rawTick;
        public final long midiTick;
        public final int timebase;
        public final int tempo;
        public final int mpqn;
        public final boolean synthetic;

        public TempoPoint(int rawTick, long midiTick, int timebase, int tempo, int mpqn, boolean synthetic) {
            this.rawTick = rawTick;
            this.midiTick = midiTick;
            this.timebase = timebase;
            this.tempo = tempo;
            this.mpqn = mpqn;
            this.synthetic = synthetic;
        }
    }

    /** Host-loop metadata. Materialized plans retain the source loop range for playback reporting. */
    public static final class LoopInfo {
        public final boolean hasLoop;
        public final int loopSlot;
        public final int repeatCount;
        public final int loopStartRawTick;
        public final int loopEndRawTick;
        public final long loopStartMidiTick;
        public final long loopEndMidiTick;
        public final boolean materializedLoopPasses;
        public final int totalLoopPasses;
        public final List<String> warnings;

        public LoopInfo(
                boolean hasLoop,
                int loopSlot,
                int repeatCount,
                int loopStartRawTick,
                int loopEndRawTick,
                long loopStartMidiTick,
                long loopEndMidiTick,
                List<String> warnings) {
            this(
                    hasLoop,
                    loopSlot,
                    repeatCount,
                    loopStartRawTick,
                    loopEndRawTick,
                    loopStartMidiTick,
                    loopEndMidiTick,
                    false,
                    -1,
                    warnings);
        }

        public LoopInfo(
                boolean hasLoop,
                int loopSlot,
                int repeatCount,
                int loopStartRawTick,
                int loopEndRawTick,
                long loopStartMidiTick,
                long loopEndMidiTick,
                boolean materializedLoopPasses,
                int totalLoopPasses,
                List<String> warnings) {
            this.hasLoop = hasLoop;
            this.loopSlot = loopSlot;
            this.repeatCount = repeatCount;
            this.loopStartRawTick = loopStartRawTick;
            this.loopEndRawTick = loopEndRawTick;
            this.loopStartMidiTick = loopStartMidiTick;
            this.loopEndMidiTick = loopEndMidiTick;
            this.materializedLoopPasses = materializedLoopPasses;
            this.totalLoopPasses = totalLoopPasses;
            this.warnings = immutableCopy(warnings);
        }

        public long loopBodyTickLength() {
            return Math.max(1L, loopEndMidiTick - loopStartMidiTick);
        }
    }

    public static final class ChannelAssignment {
        public final int trackIndex;
        public final int voice;
        public final int logicalChannel;
        public final int midiChannel;
        public final int midiTrackIndex;
        public final boolean outputRemapped;

        public ChannelAssignment(
                int trackIndex,
                int voice,
                int logicalChannel,
                int midiChannel,
                int midiTrackIndex,
                boolean outputRemapped) {
            this.trackIndex = trackIndex;
            this.voice = voice;
            this.logicalChannel = logicalChannel;
            this.midiChannel = midiChannel;
            this.midiTrackIndex = midiTrackIndex;
            this.outputRemapped = outputRemapped;
        }
    }

    public static final class OutputLaneAudit {
        public final int logicalChannel;
        public final boolean active;
        public final boolean authoritativeMaskKnown;
        public final boolean authoritativeSpecialLane;
        public final int midiChannel;
        public final boolean outputRemapped;

        public OutputLaneAudit(
                int logicalChannel,
                boolean active,
                boolean authoritativeMaskKnown,
                boolean authoritativeSpecialLane,
                int midiChannel,
                boolean outputRemapped) {
            this.logicalChannel = logicalChannel;
            this.active = active;
            this.authoritativeMaskKnown = authoritativeMaskKnown;
            this.authoritativeSpecialLane = authoritativeSpecialLane;
            this.midiChannel = midiChannel;
            this.outputRemapped = outputRemapped;
        }
    }

    public static final class CompiledNote {
        public final int sourceTrack;
        public final int sourceVoice;
        public final int logicalChannel;
        public final int midiChannel;
        public final int midiTrackIndex;
        public final int midiNote;
        public final int velocity;
        public final int rawStartTick;
        public final int rawEndTick;
        public final long midiStartTick;
        public final long midiEndTick;

        public CompiledNote(
                int sourceTrack,
                int sourceVoice,
                int logicalChannel,
                int midiChannel,
                int midiTrackIndex,
                int midiNote,
                int velocity,
                int rawStartTick,
                int rawEndTick,
                long midiStartTick,
                long midiEndTick) {
            this.sourceTrack = sourceTrack;
            this.sourceVoice = sourceVoice;
            this.logicalChannel = logicalChannel;
            this.midiChannel = midiChannel;
            this.midiTrackIndex = midiTrackIndex;
            this.midiNote = midiNote;
            this.velocity = velocity;
            this.rawStartTick = rawStartTick;
            this.rawEndTick = rawEndTick;
            this.midiStartTick = midiStartTick;
            this.midiEndTick = midiEndTick;
        }
    }

    public static final class MappedControlEvent {
        public final int sourceTrack;
        public final int sourceCommand;
        public final String sourceName;
        public final int rawTick;
        public final int midiChannel;
        public final int logicalChannel;
        public final int midiTrackIndex;
        public final long midiTick;
        public final int status;
        public final int data1;
        public final int data2;
        public final int patchWord;
        public final int rawPatchWord;
        public final int latePatchEntry;
        public final String patchSource;
        public final int nativeMode;
        public final int nativeBank;
        public final int nativeProgram;
        public final int nativeKind;
        public final int nativeSub;
        public final int nativeValue;
        public final String hostMapping;
        public final boolean hostMappingProxy;
        public final int sourceOrder;
        public final int order;

        public MappedControlEvent(
                int sourceTrack,
                int sourceCommand,
                String sourceName,
                int rawTick,
                int midiChannel,
                int logicalChannel,
                int midiTrackIndex,
                long midiTick,
                int status,
                int data1,
                int data2,
                int patchWord,
                int rawPatchWord,
                int latePatchEntry,
                String patchSource,
                int nativeMode,
                int nativeBank,
                int nativeProgram,
                int nativeKind,
                int nativeSub,
                int nativeValue,
                String hostMapping,
                boolean hostMappingProxy,
                int sourceOrder,
                int order) {
            this.sourceTrack = sourceTrack;
            this.sourceCommand = sourceCommand;
            this.sourceName = sourceName;
            this.rawTick = rawTick;
            this.midiChannel = midiChannel;
            this.logicalChannel = logicalChannel;
            this.midiTrackIndex = midiTrackIndex;
            this.midiTick = midiTick;
            this.status = status;
            this.data1 = data1;
            this.data2 = data2;
            this.patchWord = patchWord;
            this.rawPatchWord = rawPatchWord;
            this.latePatchEntry = latePatchEntry;
            this.patchSource = patchSource;
            this.nativeMode = nativeMode;
            this.nativeBank = nativeBank;
            this.nativeProgram = nativeProgram;
            this.nativeKind = nativeKind;
            this.nativeSub = nativeSub;
            this.nativeValue = nativeValue;
            this.hostMapping = hostMapping;
            this.hostMappingProxy = hostMappingProxy;
            this.sourceOrder = sourceOrder;
            this.order = order;
        }
    }
}
