package mld.semantic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import mld.decode.MachineDependentEvent;
import mld.decode.NoteEvent;
import mld.decode.ResourceEvent;
import mld.decode.SystemEvent;
import mld.decode.TrackEvent;
import mld.format.MldDocument;

/**
 * Continuing native semantic executor for one proven parser cycle.
 * Parser periodicity selects source events; semantic state is never assumed periodic.
 */
public final class NativeLoopRuntime {
    private final int sourceTrackCount;
    private final List<TrackEvent> repeatEvents;
    private final LoopModel loop;
    private final int parserCycleStartRawTick;
    private final int cycleRawTicks;
    private final List<String> warnings = new ArrayList<String>();
    private final Set<String> warningKeys = new LinkedHashSet<String>();
    private final TimingState.Runtime timing = TimingState.newRuntime();
    private final MelodyState melody;
    private final AudioSemanticState audio;
    private int nextRawTick;
    private long nextTransportMicros;
    private int order;
    private final String initialStateFingerprint;

    NativeLoopRuntime(
            MldDocument document,
            List<TrackEvent> initialEvents,
            List<TrackEvent> repeatEvents,
            LoopModel loop,
            int initialEndRawTick) {
        sourceTrackCount = document.trackCount;
        this.repeatEvents = repeatEvents;
        this.loop = loop;
        parserCycleStartRawTick = loop.infiniteRegion.parserCycleStartRawTick;
        cycleRawTicks = loop.infiniteRegion.parserCycleEndRawTick - parserCycleStartRawTick;
        if (cycleRawTicks <= 0) {
            throw new IllegalArgumentException("Native parser cycle must advance raw time.");
        }
        melody = new MelodyState(Math.max(16, document.trackCount * 4), warnings, warningKeys);
        audio = new AudioSemanticState(document, warnings, warningKeys);
        int previousRaw = 0;
        for (TrackEvent event : initialEvents) {
            nextTransportMicros = safeAdd(
                    nextTransportMicros,
                    TimingModel.rawDurationToMicros(
                            Math.max(0, event.rawTick - previousRaw), timing.timebase, timing.tempo));
            process(event);
            previousRaw = event.rawTick;
        }
        nextTransportMicros = safeAdd(
                nextTransportMicros,
                TimingModel.rawDurationToMicros(
                        Math.max(0, initialEndRawTick - previousRaw), timing.timebase, timing.tempo));
        nextRawTick = initialEndRawTick;
        initialStateFingerprint = stateFingerprint(initialEndRawTick);
        melody.drain();
        audio.drain();
        warnings.clear();
    }

    public Cycle nextCycle() {
        if (nextRawTick > Integer.MAX_VALUE - cycleRawTicks) {
            throw new IllegalStateException("Native loop runtime exceeds supported raw-tick range.");
        }
        int cycleStart = nextRawTick;
        long transportStart = nextTransportMicros;
        int startTimebase = timing.timebase;
        int startTempo = timing.tempo;
        List<TimingModel.Point> points = new ArrayList<TimingModel.Point>();
        points.add(new TimingModel.Point(0, startTimebase, startTempo, true));
        int previousPhase = 0;

        for (TrackEvent template : repeatEvents) {
            int phase = template.rawTick - parserCycleStartRawTick;
            if (phase < 0 || phase > cycleRawTicks) {
                throw new IllegalStateException("Native parser-cycle event lies outside its cycle bounds.");
            }
            nextTransportMicros = safeAdd(
                    nextTransportMicros,
                    TimingModel.rawDurationToMicros(
                            phase - previousPhase, timing.timebase, timing.tempo));
            TrackEvent event = NativeEventScheduler.copyAt(template, cycleStart + phase);
            int beforeTimebase = timing.timebase;
            int beforeTempo = timing.tempo;
            process(event);
            if (timing.timebase != beforeTimebase || timing.tempo != beforeTempo) {
                points.add(new TimingModel.Point(phase, timing.timebase, timing.tempo, false));
            }
            previousPhase = phase;
        }

        nextTransportMicros = safeAdd(
                nextTransportMicros,
                TimingModel.rawDurationToMicros(
                        cycleRawTicks - previousPhase, timing.timebase, timing.tempo));
        int cycleEnd = cycleStart + cycleRawTicks;
        melody.flushExpired(cycleEnd);
        MelodyProgram melodyDelta = melody.drain();
        AudioProgram audioDelta = audio.drain();
        TimingModel localTiming = new TimingModel(points);
        NativeProgram cycleProgram = cycleProgram(
                sourceTrackCount, cycleStart, cycleRawTicks, localTiming, melodyDelta, audioDelta);
        Cycle cycle = new Cycle(
                cycleStart,
                cycleEnd,
                transportStart,
                nextTransportMicros,
                localTiming,
                cycleProgram,
                stateFingerprint(cycleEnd));
        warnings.clear();
        nextRawTick = cycleEnd;
        return cycle;
    }

    private void process(TrackEvent event) {
        melody.flushExpired(event.rawTick);
        if (event instanceof NoteEvent) {
            melody.processNote((NoteEvent) event, order++);
        } else if (event instanceof ResourceEvent) {
            audio.handleResource((ResourceEvent) event, order, melody.voiceMap(), timing);
            order++;
        } else if (event instanceof MachineDependentEvent) {
            audio.handleMachine((MachineDependentEvent) event, order++);
        } else if (event instanceof SystemEvent) {
            SystemEvent system = (SystemEvent) event;
            melody.processSystem(system, order);
            audio.handleSystem(system, order++);
            TimingState.apply(timing, system);
        } else {
            order++;
        }
    }

    private static long safeAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
    }

    String initialStateFingerprint() {
        return initialStateFingerprint;
    }

    private String stateFingerprint(int rawOrigin) {
        return timing.timebase + ":" + timing.tempo + "|"
                + melody.runtimeFingerprint(rawOrigin) + "|"
                + audio.runtimeFingerprint();
    }

    private static NativeProgram cycleProgram(
            int sourceTrackCount,
            int rawStart,
            int rawLength,
            TimingModel timing,
            MelodyProgram source,
            AudioProgram audio) {
        List<MelodyProgram.NoteAction> noteActions = new ArrayList<MelodyProgram.NoteAction>();
        for (MelodyProgram.NoteAction action : source.noteActions) {
            noteActions.add(new MelodyProgram.NoteAction(
                    action.noteOn,
                    action.sourceTrack,
                    action.sourceVoice,
                    action.logicalChannel,
                    action.pitchOffset,
                    action.velocity,
                    action.rawTick - rawStart,
                    action.order,
                    action.channel));
        }
        List<MelodyProgram.NativeControl> controls = new ArrayList<MelodyProgram.NativeControl>();
        for (MelodyProgram.NativeControl control : source.controls) {
            controls.add(new MelodyProgram.NativeControl(
                    control.sourceTrack,
                    control.sourceCommand,
                    control.sourceName,
                    control.rawTick - rawStart,
                    control.logicalChannel,
                    control.value,
                    control.part,
                    control.masterVolume,
                    control.nativePath,
                    control.selectedHalfAware,
                    control.writesReplacementHalfWhenPendingSwap,
                    control.continuityWindowSamples,
                    control.order,
                    control.channel));
        }
        MelodyProgram melody = new MelodyProgram(
                source.ordinaryModel,
                Collections.<MelodyProgram.NativeNote>emptyList(),
                noteActions,
                Collections.<MelodyProgram.GateSchedule>emptyList(),
                controls,
                Collections.<MelodyProgram.UnmappedControl>emptyList(),
                source.copyFinalVoiceMap());
        LoopModel noLoop = new LoopModel(
                Collections.<LoopModel.Activation>emptyList(),
                Collections.<LoopModel.Rewind>emptyList(),
                0,
                null,
                rawLength,
                Collections.<String>emptyList());
        List<AudioProgram.AudioAction> actions = new ArrayList<AudioProgram.AudioAction>();
        for (AudioProgram.AudioAction action : audio.actions) {
            actions.add(new AudioProgram.AudioAction(
                    action.order,
                    action.sourceKind,
                    action.kind,
                    action.sourceTrack,
                    action.eventIndex,
                    action.rawTick - rawStart,
                    action.descriptorIndex,
                    action.handlerId,
                    action.logicalChannel,
                    action.slot,
                    action.resourceIndex,
                    action.linkedCatalogIndex,
                    action.audioType,
                    action.operation,
                    action.formatCode,
                    action.sampleRate,
                    action.codedBits,
                    action.channelCount,
                    action.controlFlag,
                    action.durationByteCount,
                    action.durationMs,
                    action.value,
                    action.cacheAware,
                    action.phaseGated,
                    action.layeredStartEligible,
                    action.rendererSupport,
                    action.layeredEffect,
                    action.monolithicEffect,
                    action.copyEncodedPayload()));
        }
        AudioProgram normalizedAudio = new AudioProgram(
                audio.resourceCatalog,
                audio.initialChannelConfigs,
                audio.resourceEvents,
                actions,
                audio.channelStates,
                audio.slotStates,
                audio.configBindings,
                audio.routeState,
                audio.resourceLevel,
                audio.resourcePan,
                audio.globalSampledLevel,
                audio.diagnostics);
        return new NativeProgram(
                sourceTrackCount,
                timing,
                noLoop,
                melody,
                normalizedAudio,
                null,
                rawLength,
                rawLength,
                Collections.<String>emptyList(),
                Collections.<Diagnostic>emptyList());
    }

    /** One semantically executed cycle with absolute transport timing. */
    public static final class Cycle {
        public final int rawStartTick;
        public final int rawEndTick;
        public final long transportStartMicros;
        public final long transportEndMicros;
        public final TimingModel localTiming;
        public final NativeProgram program;
        final String stateFingerprint;

        Cycle(
                int rawStartTick,
                int rawEndTick,
                long transportStartMicros,
                long transportEndMicros,
                TimingModel localTiming,
                NativeProgram program,
                String stateFingerprint) {
            this.rawStartTick = rawStartTick;
            this.rawEndTick = rawEndTick;
            this.transportStartMicros = transportStartMicros;
            this.transportEndMicros = transportEndMicros;
            this.localTiming = localTiming;
            this.program = program;
            this.stateFingerprint = stateFingerprint;
        }

        public long transportMicrosAtRawTick(int rawTick) {
            int phase = Math.max(0, Math.min(rawEndTick - rawStartTick, rawTick - rawStartTick));
            return safeAdd(transportStartMicros, localTiming.rawTickToMicros(phase));
        }
    }
}
