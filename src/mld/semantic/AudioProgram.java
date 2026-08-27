package mld.semantic;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Immutable native resource and sampled-audio semantic program. */
public final class AudioProgram {
    public final List<ResourceCatalogEntry> resourceCatalog;
    public final List<InitialChannelConfig> initialChannelConfigs;
    public final List<ResourceEventState> resourceEvents;
    public final List<AudioAction> actions;
    public final List<ChannelState> channelStates;
    public final List<SlotState> slotStates;
    public final List<ConfigBinding> configBindings;
    public final RouteState routeState;
    public final int globalLevel;
    public final List<Diagnostic> diagnostics;

    AudioProgram(
            List<ResourceCatalogEntry> resourceCatalog,
            List<InitialChannelConfig> initialChannelConfigs,
            List<ResourceEventState> resourceEvents,
            List<AudioAction> actions,
            List<ChannelState> channelStates,
            List<SlotState> slotStates,
            List<ConfigBinding> configBindings,
            RouteState routeState,
            int globalLevel,
            List<Diagnostic> diagnostics) {
        this.resourceCatalog = immutable(resourceCatalog);
        this.initialChannelConfigs = immutable(initialChannelConfigs);
        this.resourceEvents = immutable(resourceEvents);
        this.actions = immutable(actions);
        this.channelStates = immutable(channelStates);
        this.slotStates = immutable(slotStates);
        this.configBindings = immutable(configBindings);
        this.routeState = routeState;
        this.globalLevel = globalLevel;
        this.diagnostics = immutable(diagnostics);
    }

    private static <T> List<T> immutable(List<T> source) {
        return Collections.unmodifiableList(new ArrayList<T>(source));
    }

    public enum SourceKind {
        HEADER,
        RESOURCE_7F,
        MACHINE_DEPENDENT,
        SYSTEM
    }

    public enum ActionKind {
        RESOURCE_START,
        RESOURCE_STOP,
        CHANNEL_LEVEL,
        CHANNEL_PAN,
        CHANNEL_ROUTE,
        CONFIG_SELECT,
        SLOT_LOAD,
        SLOT_START,
        SLOT_LOAD_AND_START,
        SLOT_STOP,
        SLOT_CONTROL,
        ROUTE_UPDATE,
        GLOBAL_LEVEL,
        NO_ACTION
    }

    public enum AudioType {
        NONE(0),
        RESOURCE_ADAT(-1),
        MFI_8000(0x8000),
        MFI_8001(0x8001),
        MFI_8002(0x8002);

        public final int nativeSelector;

        AudioType(int nativeSelector) {
            this.nativeSelector = nativeSelector;
        }
    }

    public enum RendererSupport {
        CONTROL_ONLY,
        VERIFIED_8001_4BIT,
        RECOGNIZED_UNSUPPORTED
    }

    public enum BranchEffect {
        BACKEND_ACTION,
        STATE_ONLY,
        NO_ACTION,
        PHASE_GATED_BACKEND,
        UNRESOLVED
    }

    /** One ordered native audio action with typed renderer-relevant parameters. */
    public static final class AudioAction {
        public final int order;
        public final SourceKind sourceKind;
        public final ActionKind kind;
        public final int sourceTrack;
        public final int eventIndex;
        public final int rawTick;
        public final int descriptorIndex;
        public final int handlerId;
        public final int logicalChannel;
        public final int slot;
        public final int resourceIndex;
        public final int linkedCatalogIndex;
        public final AudioType audioType;
        public final int operation;
        public final int formatCode;
        public final int sampleRate;
        public final int codedBits;
        public final int channelCount;
        public final int controlFlag;
        public final int durationByteCount;
        public final long durationMs;
        public final int value;
        public final boolean cacheAware;
        public final boolean phaseGated;
        public final boolean layeredStartEligible;
        public final RendererSupport rendererSupport;
        public final BranchEffect layeredEffect;
        public final BranchEffect monolithicEffect;
        private final byte[] encodedPayload;

        AudioAction(
                int order,
                SourceKind sourceKind,
                ActionKind kind,
                int sourceTrack,
                int eventIndex,
                int rawTick,
                int descriptorIndex,
                int handlerId,
                int logicalChannel,
                int slot,
                int resourceIndex,
                int linkedCatalogIndex,
                AudioType audioType,
                int operation,
                int formatCode,
                int sampleRate,
                int codedBits,
                int channelCount,
                int controlFlag,
                int durationByteCount,
                long durationMs,
                int value,
                boolean cacheAware,
                boolean phaseGated,
                boolean layeredStartEligible,
                RendererSupport rendererSupport,
                BranchEffect layeredEffect,
                BranchEffect monolithicEffect,
                byte[] encodedPayload) {
            this.order = order;
            this.sourceKind = sourceKind;
            this.kind = kind;
            this.sourceTrack = sourceTrack;
            this.eventIndex = eventIndex;
            this.rawTick = rawTick;
            this.descriptorIndex = descriptorIndex;
            this.handlerId = handlerId;
            this.logicalChannel = logicalChannel;
            this.slot = slot;
            this.resourceIndex = resourceIndex;
            this.linkedCatalogIndex = linkedCatalogIndex;
            this.audioType = audioType;
            this.operation = operation;
            this.formatCode = formatCode;
            this.sampleRate = sampleRate;
            this.codedBits = codedBits;
            this.channelCount = channelCount;
            this.controlFlag = controlFlag;
            this.durationByteCount = durationByteCount;
            this.durationMs = durationMs;
            this.value = value;
            this.cacheAware = cacheAware;
            this.phaseGated = phaseGated;
            this.layeredStartEligible = layeredStartEligible;
            this.rendererSupport = rendererSupport;
            this.layeredEffect = layeredEffect;
            this.monolithicEffect = monolithicEffect;
            this.encodedPayload = encodedPayload == null
                    ? new byte[0]
                    : Arrays.copyOf(encodedPayload, encodedPayload.length);
        }

        public byte[] copyEncodedPayload() {
            return Arrays.copyOf(encodedPayload, encodedPayload.length);
        }

    }

    /** Final native audio channel state after the linear semantic pass. */
    public static final class ChannelState {
        public final int logicalChannel;
        public final boolean levelKnown;
        public final int level;
        public final boolean levelPhaseGated;
        public final boolean panKnown;
        public final int pan;
        public final boolean panPhaseGated;
        public final boolean routeKnown;
        public final int route;

        ChannelState(
                int logicalChannel,
                boolean levelKnown,
                int level,
                boolean levelPhaseGated,
                boolean panKnown,
                int pan,
                boolean panPhaseGated,
                boolean routeKnown,
                int route) {
            this.logicalChannel = logicalChannel;
            this.levelKnown = levelKnown;
            this.level = level;
            this.levelPhaseGated = levelPhaseGated;
            this.panKnown = panKnown;
            this.pan = pan;
            this.panPhaseGated = panPhaseGated;
            this.routeKnown = routeKnown;
            this.route = route;
        }
    }

    /** Final cache/load state for one native sampled-audio slot. */
    public static final class SlotState {
        public final int slot;
        public final boolean loaded;
        public final boolean cacheAware;
        public final AudioType audioType;
        public final int formatCode;
        public final int sampleRate;
        public final int codedBits;
        public final int channelCount;
        public final int controlFlag;
        public final int durationByteCount;
        public final long durationMs;
        public final int sourceTrack;
        public final int rawTick;
        private final byte[] encodedPayload;

        SlotState(
                int slot,
                boolean loaded,
                boolean cacheAware,
                AudioType audioType,
                int formatCode,
                int sampleRate,
                int codedBits,
                int channelCount,
                int controlFlag,
                int durationByteCount,
                long durationMs,
                int sourceTrack,
                int rawTick,
                byte[] encodedPayload) {
            this.slot = slot;
            this.loaded = loaded;
            this.cacheAware = cacheAware;
            this.audioType = audioType;
            this.formatCode = formatCode;
            this.sampleRate = sampleRate;
            this.codedBits = codedBits;
            this.channelCount = channelCount;
            this.controlFlag = controlFlag;
            this.durationByteCount = durationByteCount;
            this.durationMs = durationMs;
            this.sourceTrack = sourceTrack;
            this.rawTick = rawTick;
            this.encodedPayload = encodedPayload == null
                    ? new byte[0]
                    : Arrays.copyOf(encodedPayload, encodedPayload.length);
        }

        public byte[] copyEncodedPayload() {
            return Arrays.copyOf(encodedPayload, encodedPayload.length);
        }
    }

    /** Final selector binding for thrd/live-7F90 config state. */
    public static final class ConfigBinding {
        public final String target;
        public final int logicalChannel;
        public final int selector;
        public final boolean clearWhenOutOfRange;
        public final int sourceTrack;
        public final int rawTick;

        ConfigBinding(
                String target,
                int logicalChannel,
                int selector,
                boolean clearWhenOutOfRange,
                int sourceTrack,
                int rawTick) {
            this.target = target;
            this.logicalChannel = logicalChannel;
            this.selector = selector;
            this.clearWhenOutOfRange = clearWhenOutOfRange;
            this.sourceTrack = sourceTrack;
            this.rawTick = rawTick;
        }
    }

    /** Final verified 16-lane route state from handler 0x407. */
    public static final class RouteState {
        private final int[] flags;
        private final int[] classes;

        RouteState(int[] flags, int[] classes) {
            this.flags = Arrays.copyOf(flags, flags.length);
            this.classes = Arrays.copyOf(classes, classes.length);
        }

        public int[] copyFlags() {
            return Arrays.copyOf(flags, flags.length);
        }

        public int[] copyClasses() {
            return Arrays.copyOf(classes, classes.length);
        }
    }

    public boolean hasSampledAudioContent() {
        for (AudioAction action : actions) {
            if (action.kind == ActionKind.NO_ACTION) {
                continue;
            }
            if (action.kind == ActionKind.RESOURCE_START
                    || action.audioType == AudioType.MFI_8000
                    || action.audioType == AudioType.MFI_8001
                    || action.audioType == AudioType.MFI_8002) {
                return true;
            }
        }
        return false;
    }

    /** Immutable typed sampled resource promoted from a top-level active adat entry. */
    public static final class SampledResource {
        public final AudioType audioType;
        public final int selectorId;
        public final int selectorFlags;
        public final int sampleRate;
        public final int codedBits;
        public final int channelCount;
        private final byte[] encodedPayload;

        public SampledResource(
                AudioType audioType,
                int selectorId,
                int selectorFlags,
                int sampleRate,
                int codedBits,
                int channelCount,
                byte[] encodedPayload) {
            this.audioType = audioType;
            this.selectorId = selectorId;
            this.selectorFlags = selectorFlags;
            this.sampleRate = sampleRate;
            this.codedBits = codedBits;
            this.channelCount = channelCount;
            this.encodedPayload = encodedPayload == null
                    ? new byte[0]
                    : Arrays.copyOf(encodedPayload, encodedPayload.length);
        }

        public byte[] copyEncodedPayload() {
            return Arrays.copyOf(encodedPayload, encodedPayload.length);
        }

        public int encodedPayloadLength() {
            return encodedPayload.length;
        }
    }

    public static final class ResourceCatalogEntry {
        public final int catalogIndex;
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
        public final String chunkId;
        public final SampledResource sampledResource;

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
                int adpmByte2Bit3,
                SampledResource sampledResource) {
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
            this.sampledResource = sampledResource;
        }
    }

    public static final class InitialChannelConfig {
        public final int chunkOffset;
        public final int globalValue;
        public final int logicalChannel;
        public final int rawSubvalue;
        public final String target;

        public InitialChannelConfig(
                int chunkOffset,
                int globalValue,
                int logicalChannel,
                String target,
                int rawSubvalue) {
            this.chunkOffset = chunkOffset;
            this.globalValue = globalValue;
            this.logicalChannel = logicalChannel;
            this.target = target;
            this.rawSubvalue = rawSubvalue;
        }
    }

    public static final class ResourceEventState {
        public final int sourceTrack;
        public final int rawTick;
        public final int command;
        public final int lane;
        public final int logicalChannel;
        public final int resourceIndex;
        public final int linkedCatalogIndex;
        public final int linkedChunkOffset;
        public final int extraParamLow6;
        public final int extraParam2x;
        public final int valueLow6;
        public final int value2x;
        public final int rawSubvalue;
        public final int auxiliary3dLow5;
        public final int auxiliary3dDistanceRaw;
        public final int auxiliary3dAngleA;
        public final int auxiliary3dAngleB;
        public final int auxiliary3dDurationRaw;
        public final int auxiliary3dDurationMs;
        public final String name;
        public final String target;
        public final boolean channelDispatchEligible;
        public final boolean clearWhenOutOfRange;
        public final boolean auxiliary3dDispatchCandidate;

        public ResourceEventState(
                int sourceTrack,
                int rawTick,
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
                boolean channelDispatchEligible,
                boolean clearWhenOutOfRange,
                boolean auxiliary3dDispatchCandidate,
                int auxiliary3dLow5,
                int auxiliary3dDistanceRaw,
                int auxiliary3dAngleA,
                int auxiliary3dAngleB,
                int auxiliary3dDurationRaw,
                int auxiliary3dDurationMs) {
            this.sourceTrack = sourceTrack;
            this.rawTick = rawTick;
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
            this.channelDispatchEligible = channelDispatchEligible;
            this.clearWhenOutOfRange = clearWhenOutOfRange;
            this.auxiliary3dDispatchCandidate = auxiliary3dDispatchCandidate;
            this.auxiliary3dLow5 = auxiliary3dLow5;
            this.auxiliary3dDistanceRaw = auxiliary3dDistanceRaw;
            this.auxiliary3dAngleA = auxiliary3dAngleA;
            this.auxiliary3dAngleB = auxiliary3dAngleB;
            this.auxiliary3dDurationRaw = auxiliary3dDurationRaw;
            this.auxiliary3dDurationMs = auxiliary3dDurationMs;
        }
    }
}
