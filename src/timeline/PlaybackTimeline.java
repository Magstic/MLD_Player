package timeline;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import container.MldFile;
import event.TrackDecodeResult;
import normalize.MdNormalizationResult;

public final class PlaybackTimeline {
    public static final int MIDI_PPQ = 1920;

    public final MldFile file;
    public final List<TrackDecodeResult> decodedTracks;
    public final MdNormalizationResult mdNormalization;
    public final List<TempoPoint> tempoPoints;
    public final LoopInfo loopInfo;
    public final List<ChannelAssignment> channelAssignments;
    public final List<OutputLaneAudit> outputLanePlan;
    public final OrdinaryNativeModel ordinaryNativeModel;
    public final List<OrdinaryNativeControl> ordinaryNativeControls;
    public final List<ResourceCatalogEntry> resourceCatalog;
    public final List<InitialChannelConfig> initialChannelConfigs;
    public final List<ResourceEventState> resourceEvents;
    public final List<CompiledNote> notes;
    public final List<MappedControlEvent> mappedControls;
    public final List<UnmappedControlEvent> unmappedControls;
    public final long totalMidiTicks;
    public final List<String> warnings;
    public final List<String> implementationFacts;
    public final List<String> runtimePolicies;
    public final List<String> knownLimitations;
    public final List<String> assumptions;

    public PlaybackTimeline(
            MldFile file,
            List<TrackDecodeResult> decodedTracks,
            MdNormalizationResult mdNormalization,
            List<TempoPoint> tempoPoints,
            LoopInfo loopInfo,
            List<ChannelAssignment> channelAssignments,
            List<OutputLaneAudit> outputLanePlan,
            OrdinaryNativeModel ordinaryNativeModel,
            List<OrdinaryNativeControl> ordinaryNativeControls,
            List<ResourceCatalogEntry> resourceCatalog,
            List<InitialChannelConfig> initialChannelConfigs,
            List<ResourceEventState> resourceEvents,
            List<CompiledNote> notes,
            List<MappedControlEvent> mappedControls,
            List<UnmappedControlEvent> unmappedControls,
            long totalMidiTicks,
            List<String> warnings,
            List<String> implementationFacts,
            List<String> runtimePolicies,
            List<String> knownLimitations) {
        this.file = file;
        this.decodedTracks = Collections.unmodifiableList(new ArrayList<TrackDecodeResult>(decodedTracks));
        this.mdNormalization = mdNormalization;
        this.tempoPoints = Collections.unmodifiableList(new ArrayList<TempoPoint>(tempoPoints));
        this.loopInfo = loopInfo;
        this.channelAssignments = Collections.unmodifiableList(new ArrayList<ChannelAssignment>(channelAssignments));
        this.outputLanePlan = Collections.unmodifiableList(new ArrayList<OutputLaneAudit>(outputLanePlan));
        this.ordinaryNativeModel = ordinaryNativeModel;
        this.ordinaryNativeControls = Collections.unmodifiableList(new ArrayList<OrdinaryNativeControl>(ordinaryNativeControls));
        this.resourceCatalog = Collections.unmodifiableList(new ArrayList<ResourceCatalogEntry>(resourceCatalog));
        this.initialChannelConfigs = Collections.unmodifiableList(new ArrayList<InitialChannelConfig>(initialChannelConfigs));
        this.resourceEvents = Collections.unmodifiableList(new ArrayList<ResourceEventState>(resourceEvents));
        this.notes = Collections.unmodifiableList(new ArrayList<CompiledNote>(notes));
        this.mappedControls = Collections.unmodifiableList(new ArrayList<MappedControlEvent>(mappedControls));
        this.unmappedControls = Collections.unmodifiableList(new ArrayList<UnmappedControlEvent>(unmappedControls));
        this.totalMidiTicks = totalMidiTicks;
        this.warnings = Collections.unmodifiableList(new ArrayList<String>(warnings));
        this.implementationFacts = immutableCopy(implementationFacts);
        this.runtimePolicies = immutableCopy(runtimePolicies);
        this.knownLimitations = immutableCopy(knownLimitations);
        this.assumptions = mergeNarrativeSections(
                this.implementationFacts,
                this.runtimePolicies,
                this.knownLimitations);
    }

    private static List<String> immutableCopy(List<String> source) {
        return Collections.unmodifiableList(new ArrayList<String>(source));
    }

    private static List<String> mergeNarrativeSections(
            List<String> implementationFacts,
            List<String> runtimePolicies,
            List<String> knownLimitations) {
        List<String> merged = new ArrayList<String>(
                implementationFacts.size() + runtimePolicies.size() + knownLimitations.size());
        merged.addAll(implementationFacts);
        merged.addAll(runtimePolicies);
        merged.addAll(knownLimitations);
        return Collections.unmodifiableList(merged);
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

    public static final class LoopInfo {
        public final boolean hasLoop;
        public final int loopSlot;
        public final int repeatCount;
        public final int loopStartRawTick;
        public final int loopEndRawTick;
        public final long loopStartMidiTick;
        public final long loopEndMidiTick;
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
            this.hasLoop = hasLoop;
            this.loopSlot = loopSlot;
            this.repeatCount = repeatCount;
            this.loopStartRawTick = loopStartRawTick;
            this.loopEndRawTick = loopEndRawTick;
            this.loopStartMidiTick = loopStartMidiTick;
            this.loopEndMidiTick = loopEndMidiTick;
            this.warnings = Collections.unmodifiableList(new ArrayList<String>(warnings));
        }
    }

    public static final class ChannelAssignment {
        public final int trackIndex;
        public final int voice;
        public final int logicalChannel;
        public final int midiChannel;
        public final int midiTrackIndex;
        public final boolean outputRemapped;

        public ChannelAssignment(int trackIndex, int voice, int logicalChannel, int midiChannel, int midiTrackIndex, boolean outputRemapped) {
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

    public static final class OrdinaryNativeModel {
        public final boolean dualHalfVoiceState;
        public final int replacementOverlapSamples;
        public final String selectedHalfRule;
        public final boolean normalReleaseDistinctFromForcedStop;
        public final boolean liveLevelPanRefresh;
        public final boolean livePitchRefresh;
        public final boolean liveLookupRefresh;

        public OrdinaryNativeModel(
                boolean dualHalfVoiceState,
                int replacementOverlapSamples,
                String selectedHalfRule,
                boolean normalReleaseDistinctFromForcedStop,
                boolean liveLevelPanRefresh,
                boolean livePitchRefresh,
                boolean liveLookupRefresh) {
            this.dualHalfVoiceState = dualHalfVoiceState;
            this.replacementOverlapSamples = replacementOverlapSamples;
            this.selectedHalfRule = selectedHalfRule;
            this.normalReleaseDistinctFromForcedStop = normalReleaseDistinctFromForcedStop;
            this.liveLevelPanRefresh = liveLevelPanRefresh;
            this.livePitchRefresh = livePitchRefresh;
            this.liveLookupRefresh = liveLookupRefresh;
        }
    }

    public static final class OrdinaryNativeControl {
        public final int sourceTrack;
        public final int sourceCommand;
        public final String sourceName;
        public final int rawTick;
        public final long midiTick;
        public final int logicalChannel;
        public final String nativePath;
        public final String hostMapping;
        public final boolean hostEventEmitted;
        public final boolean hostMappingProxy;
        public final boolean selectedHalfAware;
        public final boolean writesReplacementHalfWhenPendingSwap;
        public final int continuityWindowSamples;

        public OrdinaryNativeControl(
                int sourceTrack,
                int sourceCommand,
                String sourceName,
                int rawTick,
                long midiTick,
                int logicalChannel,
                String nativePath,
                String hostMapping,
                boolean hostEventEmitted,
                boolean hostMappingProxy,
                boolean selectedHalfAware,
                boolean writesReplacementHalfWhenPendingSwap,
                int continuityWindowSamples) {
            this.sourceTrack = sourceTrack;
            this.sourceCommand = sourceCommand;
            this.sourceName = sourceName;
            this.rawTick = rawTick;
            this.midiTick = midiTick;
            this.logicalChannel = logicalChannel;
            this.nativePath = nativePath;
            this.hostMapping = hostMapping;
            this.hostEventEmitted = hostEventEmitted;
            this.hostMappingProxy = hostMappingProxy;
            this.selectedHalfAware = selectedHalfAware;
            this.writesReplacementHalfWhenPendingSwap = writesReplacementHalfWhenPendingSwap;
            this.continuityWindowSamples = continuityWindowSamples;
        }
    }

    public static final class ResourceCatalogEntry {
        public final int catalogIndex;
        public final String chunkId;
        public final int offset;
        public final int length;
        public final int lengthFieldBytes;
        public final int adatIndex;
        public final int activeAdatIndex;
        public final int selectorHeaderLength;
        public final int selectorId;
        public final int selectorFlags;
        public final int trailingPayloadLength;
        public final int adpmByte0;
        public final int adpmByte1;
        public final int adpmByte2Low3;
        public final int adpmByte2Bit3;

        public ResourceCatalogEntry(
                int catalogIndex,
                String chunkId,
                int offset,
                int length,
                int lengthFieldBytes,
                int adatIndex,
                int activeAdatIndex,
                int selectorHeaderLength,
                int selectorId,
                int selectorFlags,
                int trailingPayloadLength,
                int adpmByte0,
                int adpmByte1,
                int adpmByte2Low3,
                int adpmByte2Bit3) {
            this.catalogIndex = catalogIndex;
            this.chunkId = chunkId;
            this.offset = offset;
            this.length = length;
            this.lengthFieldBytes = lengthFieldBytes;
            this.adatIndex = adatIndex;
            this.activeAdatIndex = activeAdatIndex;
            this.selectorHeaderLength = selectorHeaderLength;
            this.selectorId = selectorId;
            this.selectorFlags = selectorFlags;
            this.trailingPayloadLength = trailingPayloadLength;
            this.adpmByte0 = adpmByte0;
            this.adpmByte1 = adpmByte1;
            this.adpmByte2Low3 = adpmByte2Low3;
            this.adpmByte2Bit3 = adpmByte2Bit3;
        }
    }

    public static final class InitialChannelConfig {
        public final int chunkOffset;
        public final int globalValue;
        public final int logicalChannel;
        public final String target;
        public final int rawSubvalue;
        public final int cachedValue;
        public final int backendValue;

        public InitialChannelConfig(
                int chunkOffset,
                int globalValue,
                int logicalChannel,
                String target,
                int rawSubvalue,
                int cachedValue,
                int backendValue) {
            this.chunkOffset = chunkOffset;
            this.globalValue = globalValue;
            this.logicalChannel = logicalChannel;
            this.target = target;
            this.rawSubvalue = rawSubvalue;
            this.cachedValue = cachedValue;
            this.backendValue = backendValue;
        }
    }

    public static final class ResourceEventState {
        public final int sourceTrack;
        public final int rawTick;
        public final long midiTick;
        public final int command;
        public final String name;
        public final int lane;
        public final int logicalChannel;
        public final String target;
        public final int resourceIndex;
        public final int linkedCatalogIndex;
        public final int linkedChunkOffset;
        public final int extraParamLow6;
        public final int extraParam2x;
        public final int valueLow6;
        public final int value2x;
        public final int rawSubvalue;
        public final boolean clearsChannelConfig;
        public final int cachedConfigValue;
        public final int backendConfigValue;

        public ResourceEventState(
                int sourceTrack,
                int rawTick,
                long midiTick,
                int command,
                String name,
                int lane,
                int logicalChannel,
                String target,
                int resourceIndex,
                int linkedCatalogIndex,
                int linkedChunkOffset,
                int extraParamLow6,
                int extraParam2x,
                int valueLow6,
                int value2x,
                int rawSubvalue,
                boolean clearsChannelConfig,
                int cachedConfigValue,
                int backendConfigValue) {
            this.sourceTrack = sourceTrack;
            this.rawTick = rawTick;
            this.midiTick = midiTick;
            this.command = command;
            this.name = name;
            this.lane = lane;
            this.logicalChannel = logicalChannel;
            this.target = target;
            this.resourceIndex = resourceIndex;
            this.linkedCatalogIndex = linkedCatalogIndex;
            this.linkedChunkOffset = linkedChunkOffset;
            this.extraParamLow6 = extraParamLow6;
            this.extraParam2x = extraParam2x;
            this.valueLow6 = valueLow6;
            this.value2x = value2x;
            this.rawSubvalue = rawSubvalue;
            this.clearsChannelConfig = clearsChannelConfig;
            this.cachedConfigValue = cachedConfigValue;
            this.backendConfigValue = backendConfigValue;
        }
    }

    public static final class CompiledNote {
        public final int sourceTrack;
        public final int sourceVoice;
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
        public final int order;

        public MappedControlEvent(
                int sourceTrack,
                int sourceCommand,
                String sourceName,
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
                int order) {
            this.sourceTrack = sourceTrack;
            this.sourceCommand = sourceCommand;
            this.sourceName = sourceName;
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
            this.order = order;
        }
    }

    public static final class UnmappedControlEvent {
        public final int sourceTrack;
        public final int sourceCommand;
        public final String sourceName;
        public final int rawTick;
        public final long midiTick;
        public final int value;
        public final int part;

        public UnmappedControlEvent(
                int sourceTrack,
                int sourceCommand,
                String sourceName,
                int rawTick,
                long midiTick,
                int value,
                int part) {
            this.sourceTrack = sourceTrack;
            this.sourceCommand = sourceCommand;
            this.sourceName = sourceName;
            this.rawTick = rawTick;
            this.midiTick = midiTick;
            this.value = value;
            this.part = part;
        }
    }
}
