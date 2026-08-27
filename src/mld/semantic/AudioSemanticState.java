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
    private static final int INITIAL_GLOBAL_LEVEL = 64;
    private static final int RESET_GLOBAL_LEVEL = 100;

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
    private final Map<String, MutableConfigBinding> configs =
            new LinkedHashMap<String, MutableConfigBinding>();
    private final int[] routeFlags = filled(16, -1);
    private final int[] routeClasses = filled(16, -1);
    private final MachineDescriptorMatcher descriptorMatcher = new MachineDescriptorMatcher();
    private final List<String> warnings;
    private final Set<String> warningKeys;
    private int globalLevel = INITIAL_GLOBAL_LEVEL;

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

        if (action.kind == AudioProgram.ActionKind.RESOURCE_START) {
            diagnostics.add(new Diagnostic(
                    "AUDIO_RENDERER_RESOURCE_ADAT_UNSUPPORTED",
                    Diagnostic.Severity.UNSUPPORTED,
                    event.trackIndex,
                    event.eventIndex,
                    event.rawTick,
                    "Top-level active-adat resource semantics are recognized; production renderer support is not implemented."));
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
            globalLevel = RESET_GLOBAL_LEVEL;
        } else if (event.command == 0xB0 && event.value >= 0 && event.value < 0x80) {
            globalLevel = event.value;
        } else if (event.command == 0xBD && event.value >= 0 && event.value < 0x80) {
            globalLevel = clamp(0, 127, globalLevel + event.value - 0x40);
        } else {
            return;
        }
        actions.add(new AudioProgram.AudioAction(
                order,
                AudioProgram.SourceKind.SYSTEM,
                AudioProgram.ActionKind.GLOBAL_LEVEL,
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
                globalLevel,
                false,
                false,
                true,
                AudioProgram.RendererSupport.CONTROL_ONLY,
                AudioProgram.BranchEffect.BACKEND_ACTION,
                AudioProgram.BranchEffect.STATE_ONLY,
                null));
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
                    state.panPhaseGated));
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
                globalLevel,
                diagnostics);
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
        switch (state.command) {
            case 0x00:
                kind = AudioProgram.ActionKind.RESOURCE_START;
                type = AudioProgram.AudioType.RESOURCE_ADAT;
                support = AudioProgram.RendererSupport.RECOGNIZED_UNSUPPORTED;
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
                kind = AudioProgram.ActionKind.CONFIG_SELECT;
                value = state.rawSubvalue;
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
                -1,
                -1,
                -1,
                -1,
                -1,
                -1L,
                value,
                false,
                false,
                state.channelDispatchEligible || kind != AudioProgram.ActionKind.CONFIG_SELECT,
                support,
                AudioProgram.BranchEffect.BACKEND_ACTION,
                monolithicResourceEffect(kind),
                null);
    }

    private static AudioProgram.BranchEffect monolithicResourceEffect(AudioProgram.ActionKind kind) {
        if (kind == AudioProgram.ActionKind.RESOURCE_START) {
            return AudioProgram.BranchEffect.BACKEND_ACTION;
        }
        if (kind == AudioProgram.ActionKind.CONFIG_SELECT) {
            return AudioProgram.BranchEffect.STATE_ONLY;
        }
        return AudioProgram.BranchEffect.NO_ACTION;
    }

    private void apply(AudioProgram.AudioAction action) {
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

    private void applySlotLoad(AudioProgram.AudioAction action) {
        if (action.slot < 0 || action.kind == AudioProgram.ActionKind.NO_ACTION) {
            return;
        }
        slots.put(Integer.valueOf(action.slot), new MutableSlotState(action));
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

        MutableChannelState(int logicalChannel) {
            this.logicalChannel = logicalChannel;
        }
    }

    private static final class MutableSlotState {
        final AudioProgram.AudioAction action;

        MutableSlotState(AudioProgram.AudioAction action) {
            this.action = action;
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
                    action.copyEncodedPayload());
        }
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
