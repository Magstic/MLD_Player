package mld.semantic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import mld.decode.TrackEvent;
import mld.format.MldDocument;

/** Immutable parser-period program used to open a continuing semantic loop runtime. */
public final class NativeLoopPlan {
    private final MldDocument document;
    private final List<TrackEvent> initialEvents;
    private final List<TrackEvent> repeatEvents;
    private final LoopModel loop;
    private final int initialEndRawTick;

    NativeLoopPlan(MldDocument document, NativeEventScheduler.Execution execution) {
        if (document == null || execution == null || !execution.loop.hasInfiniteLoop()) {
            throw new IllegalArgumentException("Native loop plan requires a proven parser cycle.");
        }
        this.document = document;
        this.initialEvents = immutable(execution.events);
        this.repeatEvents = immutable(execution.repeatEvents);
        this.loop = execution.loop;
        this.initialEndRawTick = execution.loop.infiniteRegion.parserCycleEndRawTick;
        if (repeatEvents.isEmpty()) {
            throw new IllegalArgumentException("Native loop parser cycle is empty.");
        }
    }

    public int initialEndRawTick() {
        return initialEndRawTick;
    }

    public NativeLoopRuntime openRuntime() {
        return new NativeLoopRuntime(document, initialEvents, repeatEvents, loop, initialEndRawTick);
    }

    /** True when the compiled proof-cycle PCM projection is safe to repeat unchanged. */
    public boolean isPcmTemplatePeriodic(NativeProgram program) {
        if (program == null || program.nativeLoop != this) return false;
        NativeLoopRuntime runtime = openRuntime();
        NativeLoopRuntime.Cycle next = runtime.nextCycle();
        if (!runtime.initialStateFingerprint().equals(next.stateFingerprint)) return false;

        LoopModel.InfiniteRegion region = loop.infiniteRegion;
        long proofDuration = program.timing.rawTickToMicros(region.parserCycleEndRawTick)
                - program.timing.rawTickToMicros(region.parserCycleStartRawTick);
        if (proofDuration != next.transportEndMicros - next.transportStartMicros) return false;

        List<String> proofActions = new ArrayList<String>();
        for (AudioProgram.AudioAction action : program.audio.actions) {
            if (action.rawTick >= region.parserCycleStartRawTick
                    && action.rawTick < region.parserCycleEndRawTick) {
                proofActions.add(actionSignature(action));
            }
        }
        List<String> nextActions = new ArrayList<String>();
        for (AudioProgram.AudioAction action : next.program.audio.actions) {
            nextActions.add(actionSignature(action));
        }
        return proofActions.equals(nextActions);
    }

    private static String actionSignature(AudioProgram.AudioAction action) {
        return action.sourceKind + "|" + action.kind + "|"
                + action.sourceTrack + "|" + action.eventIndex + "|"
                + action.descriptorIndex + "|" + action.handlerId + "|"
                + action.logicalChannel + "|" + action.slot + "|"
                + action.resourceIndex + "|" + action.linkedCatalogIndex + "|"
                + action.audioType + "|" + action.operation + "|"
                + action.formatCode + "|" + action.sampleRate + "|"
                + action.codedBits + "|" + action.channelCount + "|"
                + action.controlFlag + "|" + action.durationByteCount + "|"
                + action.durationMs + "|" + action.value + "|"
                + action.cacheAware + "|" + action.phaseGated + "|"
                + action.layeredStartEligible + "|" + action.rendererSupport + "|"
                + action.layeredEffect + "|" + action.monolithicEffect + "|"
                + java.util.Base64.getEncoder().encodeToString(action.copyEncodedPayload());
    }

    private static List<TrackEvent> immutable(List<TrackEvent> events) {
        return Collections.unmodifiableList(new ArrayList<TrackEvent>(events));
    }
}
