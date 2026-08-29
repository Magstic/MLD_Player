package mld.semantic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import mld.decode.MachineDependentEvent;
import mld.decode.MachineDescriptorMatcher;
import mld.decode.ResourceEvent;
import mld.decode.SystemEvent;
import mld.format.MldDocument;

/**
 * Single mutable owner for native sampled-audio state.
 *
 * Structural resource parsing stays in {@link AudioState}; machine payload interpretation stays
 * pure in {@link MachineAudioInterpreter}. This class owns cross-family channel/slot/config/route
 * state and freezes it into {@link AudioProgram}.
 */
final class AudioSemanticState {
    private static final int INITIAL_MASTER_VOLUME = 100;
    private static final int DEFAULT_RESOURCE_LEVEL = 127;
    private static final int DEFAULT_RESOURCE_PAN = 64;
    private static final int DEFAULT_GLOBAL_SAMPLED_LEVEL = 64;

    private final List<AudioProgram.ResourceCatalogEntry> catalog;
    private final List<AudioProgram.InitialChannelConfig> initialConfigs;
    private final List<AudioProgram.ResourceEventState> resourceEvents =
            new ArrayList<AudioProgram.ResourceEventState>();
    private final List<AudioProgram.AudioAction> actions =
            new ArrayList<AudioProgram.AudioAction>();
    private final List<Diagnostic> diagnostics = new ArrayList<Diagnostic>();
    private final Map<Integer, MutableChannelState> channels =
            new LinkedHashMap<Integer, MutableChannelState>();
    private final Map<Integer, MutableSlotState> slots =
            new LinkedHashMap<Integer, MutableSlotState>();
    private final Map<Integer, SharedSlotCache> sharedSlotCaches =
            new LinkedHashMap<Integer, SharedSlotCache>();
    private final Map<String, MutableConfigBinding> configs =
            new LinkedHashMap<String, MutableConfigBinding>();
    private final int[] routeFlags = filled(16, -1);
    private final int[] routeClasses = filled(16, -1);
    private final MachineDescriptorMatcher descriptorMatcher = new MachineDescriptorMatcher();
    private final List<String> warnings;
    private final Set<String> warningKeys;
    private int masterVolume = INITIAL_MASTER_VOLUME;
    private int resourceLevel = DEFAULT_RESOURCE_LEVEL;
    private int resourcePan = DEFAULT_RESOURCE_PAN;

    AudioSemanticState(MldDocument document, List<String> warnings, Set<String> warningKeys) {
        this.warnings = warnings;
        this.warningKeys = warningKeys;
        this.catalog = AudioState.buildCatalog(document, warnings, warningKeys);
        this.initialConfigs = AudioState.buildInitialChannelConfigs(document, warnings, warningKeys);
        for (AudioProgram.InitialChannelConfig config : initialConfigs) {
            configs.put(
                    configKey(config.target, config.logicalChannel),
                    new MutableConfigBinding(
                            config.target,
                            config.logicalChannel,
                            config.rawSubvalue,
                            false,
                            -1,
                            -1));
        }
    }

    void handleResource(
            ResourceEvent event,
            int order,
            int[] voiceMap,
            TimingState.Runtime timingState) {
        int before = resourceEvents.size();
        AudioState.handleEvent(
                event,
                catalog,
                resourceEvents,
                voiceMap,
                timingState,
                warnings,
                warningKeys);
        if (resourceEvents.size() == before) {
            return;
        }
        AudioProgram.ResourceEventState state = resourceEvents.get(resourceEvents.size() - 1);
        AudioProgram.AudioAction action = resourceAction(event, state, order);
        if (action == null) {
            return;
        }
        actions.add(action);
        apply(action);

        if (action.kind == AudioProgram.ActionKind.RESOURCE_START
                && action.rendererSupport != AudioProgram.RendererSupport.VERIFIED_8001_4BIT) {
            diagnostics.add(new Diagnostic(
                    "AUDIO_RENDERER_RESOURCE_ADAT_UNSUPPORTED",
                    Diagnostic.Severity.UNSUPPORTED,
                    event.trackIndex,
                    event.eventIndex,
                    event.rawTick,
                    "Top-level active-adat resource is recognized but is outside the verified 0x8001 4-bit renderer profile."));
        }
        if (action.kind == AudioProgram.ActionKind.CONFIG_SELECT) {
            diagnostics.add(new Diagnostic(
                    "AUDIO_CONFIG_TABLE_UNRESOLVED",
                    Diagnostic.Severity.INFO,
                    event.trackIndex,
                    event.eventIndex,
                    event.rawTick,
                    "Live 0x7F90 selector state is preserved; backend config-table contents are not present in the MLD file."));
        }
    }

    void handleMachine(MachineDependentEvent event, int order) {
        MachineDescriptorMatcher.Match match = descriptorMatcher.match(event);
        if (match == null) {
            return;
        }
        AudioProgram.AudioAction action =
                MachineAudioInterpreter.interpret(event, match, order, diagnostics);
        if (action == null) {
            return;
        }
        actions.add(action);
        apply(action);
    }

    void handleSystem(SystemEvent event, int order) {
        if (event.trackIndex != 0) {
            return;
        }
        if (event.command == 0xBF) {
            masterVolume = INITIAL_MASTER_VOLUME;
            return;
        }
        if (event.value < 0 || event.value >= 0x80) {
            return;
        }

        AudioProgram.ActionKind kind;
        int value;
        if (event.command == 0xB0) {
            masterVolume = event.value;
            resourceLevel = masterVolume;
            kind = AudioProgram.ActionKind.RESOURCE_LEVEL;
            value = resourceLevel;
        } else if (event.command == 0xBD) {
            masterVolume = clamp(0, 127, masterVolume + event.value - 0x40);
            resourceLevel = masterVolume;
            kind = AudioProgram.ActionKind.RESOURCE_LEVEL;
            value = resourceLevel;
        } else if (event.command == 0xB1) {
            resourcePan = event.value;
            kind = AudioProgram.ActionKind.RESOURCE_PAN;
            value = resourcePan;
        } else {
            return;
        }
        actions.add(systemControlAction(event, order, kind, value));
    }

    private static AudioProgram.AudioAction systemControlAction(
            SystemEvent event, int order, AudioProgram.ActionKind kind, int value) {
        return new AudioProgram.AudioAction(
                order,
                AudioProgram.SourceKind.SYSTEM,
                kind,
                event.trackIndex,
                event.eventIndex,
                event.rawTick,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                AudioProgram.AudioType.NONE,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1L,
                value,
                false,
                false,
                true,
                AudioProgram.RendererSupport.CONTROL_ONLY,
                AudioProgram.BranchEffect.BACKEND_ACTION,
                AudioProgram.BranchEffect.STATE_ONLY,
                null);
    }

    AudioProgram finish() {
        List<AudioProgram.ChannelState> channelSnapshots = new ArrayList<AudioProgram.ChannelState>();
        for (MutableChannelState state : channels.values()) {
            channelSnapshots.add(new AudioProgram.ChannelState(
                    state.logicalChannel,
                    state.levelKnown,
                    state.level,
                    state.levelPhaseGated,
                    state.panKnown,
                    state.pan,
                    state.panPhaseGated,
                    state.routeKnown,
                    state.route));
        }

        List<AudioProgram.SlotState> slotSnapshots = new ArrayList<AudioProgram.SlotState>();
        for (MutableSlotState state : slots.values()) {
            slotSnapshots.add(state.snapshot());
        }

        List<AudioProgram.ConfigBinding> configSnapshots = new ArrayList<AudioProgram.ConfigBinding>();
        for (MutableConfigBinding state : configs.values()) {
            configSnapshots.add(new AudioProgram.ConfigBinding(
                    state.target,
                    state.logicalChannel,
                    state.selector,
                    state.clearWhenOutOfRange,
                    state.sourceTrack,
                    state.rawTick));
        }

        return new AudioProgram(
                catalog,
                initialConfigs,
                resourceEvents,
                actions,
                channelSnapshots,
                slotSnapshots,
                configSnapshots,
                new AudioProgram.RouteState(routeFlags, routeClasses),
                resourceLevel,
                resourcePan,
                DEFAULT_GLOBAL_SAMPLED_LEVEL,
                diagnostics);
    }

    AudioProgram drain() {
        AudioProgram result = finish();
        resourceEvents.clear();
        actions.clear();
        diagnostics.clear();
        return result;
    }

    String runtimeFingerprint() {
        StringBuilder out = new StringBuilder();
        out.append(masterVolume).append(',')
                .append(resourceLevel).append(',')
                .append(resourcePan).append('|');
        for (Map.Entry<Integer, MutableChannelState> entry : channels.entrySet()) {
            MutableChannelState state = entry.getValue();
            out.append(entry.getKey()).append(':')
                    .append(state.levelKnown ? 1 : 0).append(',')
                    .append(state.level).append(',')
                    .append(state.levelPhaseGated ? 1 : 0).append(',')
                    .append(state.panKnown ? 1 : 0).append(',')
                    .append(state.pan).append(',')
                    .append(state.panPhaseGated ? 1 : 0).append(',')
                    .append(state.routeKnown ? 1 : 0).append(',')
                    .append(state.route).append(';');
        }
        out.append('|');
        for (Map.Entry<Integer, MutableSlotState> entry : slots.entrySet()) {
            MutableSlotState state = entry.getValue();
            AudioProgram.AudioAction action = state.action;
            out.append(entry.getKey()).append(':')
                    .append(action.kind).append(',')
                    .append(action.cacheAware ? 1 : 0).append(',')
                    .append(action.audioType).append(',')
                    .append(action.formatCode).append(',')
                    .append(action.sampleRate).append(',')
                    .append(action.codedBits).append(',')
                    .append(action.channelCount).append(',')
                    .append(action.controlFlag).append(',')
                    .append(action.durationByteCount).append(',')
                    .append(action.durationMs).append(',')
                    .append(java.util.Base64.getEncoder().encodeToString(state.encodedPayload))
                    .append(';');
        }
        out.append('|');
        for (Map.Entry<Integer, SharedSlotCache> entry : sharedSlotCaches.entrySet()) {
            SharedSlotCache state = entry.getValue();
            out.append(entry.getKey()).append(':')
                    .append(state.pending ? 1 : 0).append(',')
                    .append(state.operation).append(',')
                    .append(state.formatCode).append(',')
                    .append(state.logicalChannel).append(',')
                    .append(state.durationMs).append(';');
        }
        out.append('|');
        for (Map.Entry<String, MutableConfigBinding> entry : configs.entrySet()) {
            MutableConfigBinding state = entry.getValue();
            out.append(entry.getKey()).append(':')
                    .append(state.selector).append(',')
                    .append(state.clearWhenOutOfRange ? 1 : 0).append(';');
        }
        out.append('|');
        for (int value : routeFlags) out.append(value).append(',');
        out.append('|');
        for (int value : routeClasses) out.append(value).append(',');
        return out.toString();
    }

    private AudioProgram.AudioAction resourceAction(
            ResourceEvent event,
            AudioProgram.ResourceEventState state,
            int order) {
        AudioProgram.ActionKind kind;
        AudioProgram.RendererSupport support = AudioProgram.RendererSupport.CONTROL_ONLY;
        int value = -1;
        int slot = -1;
        AudioProgram.AudioType type = AudioProgram.AudioType.NONE;
        int sampleRate = -1;
        int codedBits = -1;
        int channelCount = -1;
        int durationByteCount = -1;
        long durationMs = -1L;
        switch (state.command) {
            case 0x00:
                kind = AudioProgram.ActionKind.RESOURCE_START;
                value = state.extraParam2x;
                type = AudioProgram.AudioType.RESOURCE_ADAT;
                support = AudioProgram.RendererSupport.RECOGNIZED_UNSUPPORTED;
                AudioProgram.ResourceCatalogEntry entry = catalogEntry(state.linkedCatalogIndex);
                if (entry != null && entry.sampledResource != null) {
                    AudioProgram.SampledResource resource = entry.sampledResource;
                    type = resource.audioType;
                    sampleRate = resource.sampleRate;
                    codedBits = resource.codedBits;
                    channelCount = resource.channelCount;
                    durationByteCount = resource.encodedPayloadLength();
                    if (sampleRate > 0 && codedBits > 0 && channelCount > 0) {
                        durationMs = ((long)durationByteCount * 8000L)
                                / ((long)sampleRate * codedBits * channelCount);
                    }
                    if (type == AudioProgram.AudioType.MFI_8001
                            && (resource.selectorFlags & 0x04) == 0
                            && codedBits == 4
                            && (sampleRate == 8000 || sampleRate == 16000 || sampleRate == 32000)
                            && (channelCount == 1 || channelCount == 2)) {
                        support = AudioProgram.RendererSupport.VERIFIED_8001_4BIT;
                    }
                }
                break;
            case 0x01:
                kind = AudioProgram.ActionKind.RESOURCE_STOP;
                slot = state.resourceIndex;
                break;
            case 0x80:
                kind = AudioProgram.ActionKind.CHANNEL_LEVEL;
                value = state.value2x;
                break;
            case 0x81:
                kind = AudioProgram.ActionKind.CHANNEL_PAN;
                value = state.value2x;
                break;
            case 0x90:
                if ("audio".equals(state.target)) {
                    kind = AudioProgram.ActionKind.CHANNEL_ROUTE;
                    value = state.clearWhenOutOfRange ? 0 : state.rawSubvalue + 2;
                } else {
                    kind = AudioProgram.ActionKind.CONFIG_SELECT;
                    value = state.rawSubvalue;
                }
                break;
            default:
                return null;
        }
        return new AudioProgram.AudioAction(
                order,
                AudioProgram.SourceKind.RESOURCE_7F,
                kind,
                event.trackIndex,
                event.eventIndex,
                event.rawTick,
                -1,
                -1,
                state.logicalChannel,
                slot,
                state.resourceIndex,
                state.linkedCatalogIndex,
                type,
                -1,
                -1,
                sampleRate,
                codedBits,
                channelCount,
                -1,
                durationByteCount,
                durationMs,
                value,
                false,
                false,
                state.channelDispatchEligible
                        || (kind != AudioProgram.ActionKind.CONFIG_SELECT
                        && kind != AudioProgram.ActionKind.CHANNEL_ROUTE),
                support,
                AudioProgram.BranchEffect.BACKEND_ACTION,
                monolithicResourceEffect(kind),
                null);
    }

    private static AudioProgram.BranchEffect monolithicResourceEffect(AudioProgram.ActionKind kind) {
        if (kind == AudioProgram.ActionKind.RESOURCE_START) {
            return AudioProgram.BranchEffect.BACKEND_ACTION;
        }
        if (kind == AudioProgram.ActionKind.CONFIG_SELECT
                || kind == AudioProgram.ActionKind.CHANNEL_ROUTE) {
            return AudioProgram.BranchEffect.STATE_ONLY;
        }
        return AudioProgram.BranchEffect.NO_ACTION;
    }

    private void apply(AudioProgram.AudioAction action) {
        if (applySharedCachedSlotState(action)) return;
        if (action.kind == AudioProgram.ActionKind.CHANNEL_LEVEL && action.logicalChannel >= 0) {
            MutableChannelState state = channel(action.logicalChannel);
            state.levelKnown = true;
            state.level = action.value;
            state.levelPhaseGated = action.phaseGated;
            return;
        }
        if (action.kind == AudioProgram.ActionKind.CHANNEL_PAN && action.logicalChannel >= 0) {
            MutableChannelState state = channel(action.logicalChannel);
            state.panKnown = true;
            state.pan = action.value;
            state.panPhaseGated = action.phaseGated;
            return;
        }
        if (action.kind == AudioProgram.ActionKind.CHANNEL_ROUTE && action.logicalChannel >= 0) {
            MutableChannelState state = channel(action.logicalChannel);
            state.routeKnown = true;
            state.route = action.value;
            return;
        }
        if (action.kind == AudioProgram.ActionKind.CONFIG_SELECT && action.logicalChannel >= 0) {
            applyConfig(action);
            return;
        }
        if (action.kind == AudioProgram.ActionKind.SLOT_LOAD
                || action.kind == AudioProgram.ActionKind.SLOT_LOAD_AND_START) {
            applySlotLoad(action);
            return;
        }
        if (action.kind == AudioProgram.ActionKind.ROUTE_UPDATE) {
            applyRoute(action.copyEncodedPayload());
        }
    }

    private void applyConfig(AudioProgram.AudioAction action) {
        String target = "audio";
        AudioProgram.ResourceEventState resource = findResourceEvent(action.sourceTrack, action.rawTick, action.order);
        if (resource != null && resource.target != null) {
            target = resource.target;
        }
        boolean conditionalClear = resource != null && resource.clearWhenOutOfRange;
        configs.put(
                configKey(target, action.logicalChannel),
                new MutableConfigBinding(
                        target,
                        action.logicalChannel,
                        action.value,
                        conditionalClear,
                        action.sourceTrack,
                        action.rawTick));
    }

    private AudioProgram.ResourceEventState findResourceEvent(int sourceTrack, int rawTick, int order) {
        // Resource events preserve stream order, and one state record is emitted per resource action.
        for (int i = resourceEvents.size() - 1; i >= 0; i--) {
            AudioProgram.ResourceEventState state = resourceEvents.get(i);
            if (state.sourceTrack == sourceTrack && state.rawTick == rawTick) {
                return state;
            }
        }
        return null;
    }

    private AudioProgram.ResourceCatalogEntry catalogEntry(int catalogIndex) {
        if (catalogIndex < 0) return null;
        for (AudioProgram.ResourceCatalogEntry entry : catalog) {
            if (entry.catalogIndex == catalogIndex) return entry;
        }
        return null;
    }

    private void applySlotLoad(AudioProgram.AudioAction action) {
        if (action.slot < 0 || action.kind == AudioProgram.ActionKind.NO_ACTION) {
            return;
        }
        Integer slot = Integer.valueOf(action.slot);
        if (isCompact8002Load(action)) {
            SharedSlotCache cache = sharedSlotCache(slot);
            cache.pending = true;
            cache.formatCode = action.formatCode;
            cache.logicalChannel = 0;
            cache.durationMs = action.durationMs;
            // Handler 0x400 keeps the raw byte as its plugin-cache index, but both
            // MFiAudio release/load entry points reject backend slots outside 0..63.
            if (action.slot < 64) {
                slots.put(slot, new MutableSlotState(action));
            }
            return;
        }
        slots.put(slot, new MutableSlotState(action));
    }

    private static boolean isCompact8002Load(AudioProgram.AudioAction action) {
        return action.kind == AudioProgram.ActionKind.SLOT_LOAD
                && action.audioType == AudioProgram.AudioType.MFI_8002;
    }

    private boolean applySharedCachedSlotState(AudioProgram.AudioAction action) {
        if (isCached8000Handler(action)) {
            applyCached8000State(action);
            return true;
        }
        return applyCached8001State(action);
    }

    private void applyCached8000State(AudioProgram.AudioAction action) {
        Integer slot = Integer.valueOf(action.slot);
        SharedSlotCache cache = sharedSlotCache(slot);
        if (cache.pending) {
            int effectiveOperation = action.controlFlag == 0 ? cache.operation : 0;
            if (action.controlFlag == 0) cache.pending = false;
            if (effectiveOperation == 2) return;

            int effectiveSampleRate = cached8000Rate(cache.formatCode);
            if (effectiveSampleRate <= 0 || effectiveOperation == 3) return;
            if (effectiveOperation == 0 || effectiveOperation == 1) {
                append8000WithoutRelease(
                        slot,
                        resolved8000Load(
                                action,
                                effectiveOperation,
                                cache.formatCode,
                                effectiveSampleRate,
                                cache.logicalChannel));
            }
            return;
        }

        if (action.controlFlag == 1 && action.operation != 2) {
            cache.operation = action.operation;
            cache.formatCode = action.formatCode;
            cache.logicalChannel = action.logicalChannel;
            cache.pending = true;
            // Cached 0x003 releases before format dispatch, then forces operation 0.
            slots.remove(slot);
            if (action.sampleRate > 0) {
                slots.put(slot, new MutableSlotState(
                        resolved8000Load(
                                action,
                                0,
                                action.formatCode,
                                action.sampleRate,
                                action.logicalChannel)));
            }
            return;
        }

        if (action.operation == 0 || action.operation == 1) {
            // The native wrapper releases before validating the cached 0x8000 format.
            slots.remove(slot);
            if (action.sampleRate > 0) {
                slots.put(slot, new MutableSlotState(action));
            }
        }
    }

    private boolean applyCached8001State(AudioProgram.AudioAction action) {
        if (!isCached8001Handler(action)) return false;
        if (action.sampleRate <= 0 || action.codedBits <= 0 || action.channelCount != 1) {
            // Both native handlers return during format dispatch without touching cache state.
            return true;
        }
        Integer slot = Integer.valueOf(action.slot);
        SharedSlotCache cache = sharedSlotCache(slot);
        if (cache.pending) {
            int effectiveOperation = action.controlFlag == 0
                    ? cache.operation : 0;
            if (action.controlFlag == 0) cache.pending = false;
            if (effectiveOperation == 0 || effectiveOperation == 1) {
                append8001WithoutRelease(slot, action);
            }
            return true;
        }
        if (action.controlFlag == 1 && action.operation != 2) {
            cache.operation = action.operation;
            cache.formatCode = action.formatCode;
            cache.logicalChannel = action.logicalChannel;
            cache.durationMs = action.durationMs;
            cache.pending = true;
            // This branch forces operation 0, releases the previous slot, and loads 0x8001.
            slots.put(slot, new MutableSlotState(action));
            return true;
        }
        if (action.operation == 0 || action.operation == 1) {
            slots.put(slot, new MutableSlotState(action));
        }
        return true;
    }

    private static boolean isCached8001Handler(AudioProgram.AudioAction action) {
        return action.sourceKind == AudioProgram.SourceKind.MACHINE_DEPENDENT
                && (action.handlerId == 0x109 || action.handlerId == 0x106)
                && action.audioType == AudioProgram.AudioType.MFI_8001
                && action.slot >= 0 && action.slot < 64;
    }

    private static boolean isCached8000Handler(AudioProgram.AudioAction action) {
        return action.sourceKind == AudioProgram.SourceKind.MACHINE_DEPENDENT
                && action.handlerId == 0x003
                && action.audioType == AudioProgram.AudioType.MFI_8000
                && action.slot >= 0 && action.slot < 64;
    }

    private SharedSlotCache sharedSlotCache(Integer slot) {
        SharedSlotCache cache = sharedSlotCaches.get(slot);
        if (cache == null) {
            cache = new SharedSlotCache();
            sharedSlotCaches.put(slot, cache);
        }
        return cache;
    }

    private static int cached8000Rate(int formatCode) {
        if (formatCode == 4) return 4000;
        if (formatCode == 5) return 8000;
        if (formatCode == 6) return 16000;
        return -1;
    }

    private static AudioProgram.AudioAction resolved8000Load(
            AudioProgram.AudioAction action,
            int operation,
            int formatCode,
            int sampleRate,
            int logicalChannel) {
        return new AudioProgram.AudioAction(
                action.order,
                action.sourceKind,
                operation == 1
                        ? AudioProgram.ActionKind.SLOT_LOAD_AND_START
                        : AudioProgram.ActionKind.SLOT_LOAD,
                action.sourceTrack,
                action.eventIndex,
                action.rawTick,
                action.descriptorIndex,
                action.handlerId,
                logicalChannel,
                action.slot,
                action.resourceIndex,
                action.linkedCatalogIndex,
                action.audioType,
                operation,
                formatCode,
                sampleRate,
                action.codedBits,
                action.channelCount,
                action.controlFlag,
                action.durationByteCount,
                action.durationMs,
                action.value,
                action.cacheAware,
                action.phaseGated,
                logicalChannel <= 1,
                action.rendererSupport,
                action.layeredEffect,
                action.monolithicEffect,
                action.copyEncodedPayload());
    }

    private void append8001WithoutRelease(
            Integer slot, AudioProgram.AudioAction action) {
        MutableSlotState current = slots.get(slot);
        if (current == null) {
            slots.put(slot, new MutableSlotState(action));
        } else if (current.action.audioType == AudioProgram.AudioType.MFI_8001) {
            current.append(action);
        }
        // A current non-0x8001 slot takes MFiAudio's type-mismatch return unchanged.
    }

    private void append8000WithoutRelease(
            Integer slot, AudioProgram.AudioAction action) {
        MutableSlotState current = slots.get(slot);
        if (current == null) {
            slots.put(slot, new MutableSlotState(action));
        } else if (current.action.audioType == AudioProgram.AudioType.MFI_8000) {
            current.append(action);
        }
        // A current non-0x8000 slot takes MFiAudio's type-mismatch return unchanged.
    }

    private void applyRoute(byte[] payload) {
        if (payload.length < 16) {
            return;
        }
        for (int lane = 0; lane < 16; lane++) {
            int value = payload[lane] & 0xFF;
            int classA = (value >> 4) & 0x03;
            int classB = (value >> 2) & 0x03;
            if (classA == 1) {
                routeFlags[lane] = 0;
            } else if (classA == 3) {
                routeFlags[lane] = 1;
            }
            routeClasses[lane] = classB;
        }
    }

    private MutableChannelState channel(int logicalChannel) {
        Integer key = Integer.valueOf(logicalChannel);
        MutableChannelState state = channels.get(key);
        if (state == null) {
            state = new MutableChannelState(logicalChannel);
            channels.put(key, state);
        }
        return state;
    }

    private static String configKey(String target, int logicalChannel) {
        return target + ':' + logicalChannel;
    }

    private static int[] filled(int count, int value) {
        int[] result = new int[count];
        for (int i = 0; i < result.length; i++) {
            result[i] = value;
        }
        return result;
    }

    private static int clamp(int min, int max, int value) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class MutableChannelState {
        final int logicalChannel;
        boolean levelKnown;
        int level;
        boolean levelPhaseGated;
        boolean panKnown;
        int pan;
        boolean panPhaseGated;
        boolean routeKnown;
        int route;

        MutableChannelState(int logicalChannel) {
            this.logicalChannel = logicalChannel;
        }
    }

    private static final class MutableSlotState {
        AudioProgram.AudioAction action;
        byte[] encodedPayload;

        MutableSlotState(AudioProgram.AudioAction action) {
            this.action = action;
            this.encodedPayload = action.copyEncodedPayload();
        }

        void append(AudioProgram.AudioAction appended) {
            byte[] suffix = appended.copyEncodedPayload();
            byte[] combined = new byte[encodedPayload.length + suffix.length];
            System.arraycopy(encodedPayload, 0, combined, 0, encodedPayload.length);
            System.arraycopy(suffix, 0, combined, encodedPayload.length, suffix.length);
            action = appended;
            encodedPayload = combined;
        }

        AudioProgram.SlotState snapshot() {
            return new AudioProgram.SlotState(
                    action.slot,
                    true,
                    action.cacheAware,
                    action.audioType,
                    action.formatCode,
                    action.sampleRate,
                    action.codedBits,
                    action.channelCount,
                    action.controlFlag,
                    action.durationByteCount,
                    action.durationMs,
                    action.sourceTrack,
                    action.rawTick,
                    encodedPayload);
        }
    }

    private static final class SharedSlotCache {
        boolean pending;
        int operation;
        int formatCode;
        int logicalChannel;
        long durationMs;
    }

    private static final class MutableConfigBinding {
        final String target;
        final int logicalChannel;
        final int selector;
        final boolean clearWhenOutOfRange;
        final int sourceTrack;
        final int rawTick;

        MutableConfigBinding(
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
}
