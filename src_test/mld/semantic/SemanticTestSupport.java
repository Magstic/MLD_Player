package mld.semantic;

import java.util.Collections;

import midi.MidiPlan;
import midi.MidiProjector;
import mld.decode.DecodedTrack;
import mld.format.MldDocument;

/** Shared compile/project fixture for semantic regression tests. */
final class SemanticTestSupport {
    final NativeProgram program;
    final MidiPlan midi;

    private SemanticTestSupport(NativeProgram program, MidiPlan midi) {
        this.program = program;
        this.midi = midi;
    }

    static SemanticTestSupport compile(MldDocument document, DecodedTrack track) {
        NativeProgram program = new NativeCompiler().compile(document, Collections.singletonList(track));
        return new SemanticTestSupport(program, new MidiProjector().project(program));
    }
}
