package mld.semantic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable result of native MLD semantic compilation.
 */
public final class NativeProgram {
    public final int sourceTrackCount;
    public final TimingModel timing;
    public final LoopModel loop;
    public final MelodyProgram melody;
    public final AudioProgram audio;
    public final NativeLoopPlan nativeLoop;
    public final int linearEndRawTick;
    public final int semanticEndRawTick;
    public final List<String> warnings;
    public final List<Diagnostic> diagnostics;

    public NativeProgram(
            int sourceTrackCount,
            TimingModel timing,
            LoopModel loop,
            MelodyProgram melody,
            AudioProgram audio,
            NativeLoopPlan nativeLoop,
            int linearEndRawTick,
            int semanticEndRawTick,
            List<String> warnings,
            List<Diagnostic> diagnostics) {
        this.sourceTrackCount = Math.max(0, sourceTrackCount);
        this.timing = timing;
        this.loop = loop;
        this.melody = melody;
        this.audio = audio;
        this.nativeLoop = nativeLoop;
        this.linearEndRawTick = Math.max(0, linearEndRawTick);
        this.semanticEndRawTick = Math.max(this.linearEndRawTick, semanticEndRawTick);
        this.warnings = Collections.unmodifiableList(new ArrayList<String>(warnings));
        this.diagnostics = Collections.unmodifiableList(new ArrayList<Diagnostic>(diagnostics));
    }
}
