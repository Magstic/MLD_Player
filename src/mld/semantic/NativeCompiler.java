package mld.semantic;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import mld.decode.DecodedTrack;
import mld.decode.MachineDependentEvent;
import mld.decode.NoteEvent;
import mld.decode.ResourceEvent;
import mld.decode.SystemEvent;
import mld.decode.TrackEvent;
import mld.format.MldDocument;

/** Coordinates semantic state compilation over the scheduled native execution timeline. */
public final class NativeCompiler {

    public NativeProgram compile(MldDocument document, List<DecodedTrack> decodedTracks) {
        List<String> warnings = new ArrayList<String>();
        Set<String> keys = new LinkedHashSet<String>();
        ContainerRules.emitFirstOnlyWarnings(document, warnings, keys);
        NativeEventScheduler.Execution execution = NativeEventScheduler.schedule(decodedTracks, warnings);
        TimingState.Analysis timing = TimingState.analyze(execution.events, warnings);
        TimingState.Runtime timingRuntime = TimingState.newRuntime();
        MelodyState melody = new MelodyState(Math.max(16, document.trackCount * 4), warnings, keys);
        AudioSemanticState audio = new AudioSemanticState(document, warnings, keys);
        int linearEnd = 0;
        int semanticEnd = 0;
        int order = 0;
        for (TrackEvent event : timing.events) {
            semanticEnd = Math.max(semanticEnd, melody.flushExpired(event.rawTick));
            linearEnd = Math.max(linearEnd, event.rawTick);
            semanticEnd = Math.max(semanticEnd, event.rawTick);
            if (event instanceof NoteEvent) {
                semanticEnd = Math.max(
                        semanticEnd, melody.processNote((NoteEvent) event, order++));
            } else if (event instanceof ResourceEvent) {
                audio.handleResource((ResourceEvent) event, order, melody.voiceMap(), timingRuntime);
                order++;
            } else if (event instanceof MachineDependentEvent) {
                audio.handleMachine((MachineDependentEvent) event, order++);
            } else if (event instanceof SystemEvent) {
                SystemEvent s = (SystemEvent) event;
                melody.processSystem(s, order);
                audio.handleSystem(s, order++);
                TimingState.apply(timingRuntime, s);
            } else {
                order++;
            }
        }
        semanticEnd = Math.max(semanticEnd, melody.flushExpired(Integer.MAX_VALUE));
        linearEnd = Math.max(linearEnd, execution.endRawTick);
        semanticEnd = Math.max(semanticEnd, execution.endRawTick);
        AudioProgram audioProgram = audio.finish();
        NativeLoopPlan nativeLoop = execution.loop.hasInfiniteLoop()
                ? new NativeLoopPlan(document, execution) : null;
        return new NativeProgram(
                document.trackCount,
                timing.timing,
                execution.loop,
                melody.finish(),
                audioProgram,
                nativeLoop,
                linearEnd,
                semanticEnd,
                warnings,
                audioProgram.diagnostics);
    }
}
