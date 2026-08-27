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

/**
 * Single coordinator for native event ordering and semantic state ownership.
 */
public final class NativeCompiler {

    public NativeProgram compile(MldDocument document, List<DecodedTrack> decodedTracks) {
        List<String> warnings = new ArrayList<String>();
        Set<String> keys = new LinkedHashSet<String>();
        ContainerRules.emitFirstOnlyWarnings(document, warnings, keys);
        TimingState.Analysis timing = TimingState.analyze(decodedTracks, warnings);
        TimingState.Runtime timingRuntime = TimingState.newRuntime();
        MelodyState melody = new MelodyState(Math.max(16, document.trackCount * 4), warnings, keys);
        AudioSemanticState audio = new AudioSemanticState(document, warnings, keys);
        int linearEnd = 0;
        int semanticEnd = 0;
        int order = 0;
        for (TrackEvent event : timing.orderedEvents) {
            if (timing.stopEvent != null && TimingState.EVENT_ORDER.compare(event, timing.stopEvent) > 0) break;
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
        for (DecodedTrack t : decodedTracks) {
            int end = timing.stopEvent == null ? t.totalRawTicks : Math.min(t.totalRawTicks, timing.stopEvent.rawTick);
            linearEnd = Math.max(linearEnd, end);
            semanticEnd = Math.max(semanticEnd, end);
        }
        if (timing.loop.hasLoop) {
            linearEnd = Math.max(linearEnd, timing.loop.loopEndRawTick);
            semanticEnd = Math.max(semanticEnd, timing.loop.loopEndRawTick);
        }
        AudioProgram audioProgram = audio.finish();
        return new NativeProgram(
                document.trackCount,
                timing.timing,
                timing.loop,
                melody.finish(),
                audioProgram,
                linearEnd,
                semanticEnd,
                warnings,
                audioProgram.diagnostics);
    }
}
