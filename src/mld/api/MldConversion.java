package mld.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.Sequence;

import audio.AudioRenderer;
import audio.StereoPcm;
import midi.MidiPlan;
import midi.MidiSequenceEncoder;
import mld.compile.MldCompilation;
import mld.semantic.NativeProgram;

/**
 * Stable build-time conversion view of one compiled MLD file.
 *
 * Internal semantic and projection models never cross this API boundary.
 */
public final class MldConversion {
    private final NativeProgram nativeProgram;
    private final MidiPlan midiPlan;
    private final boolean hasMidi;
    private final boolean hasRenderableSampledAudio;
    private final List<String> warnings;

    MldConversion(MldCompilation compilation) {
        if (compilation == null) throw new IllegalArgumentException("compilation == null");
        nativeProgram = compilation.getNativeProgram();
        midiPlan = compilation.getMidiPlan();
        hasMidi = !midiPlan.notes.isEmpty();
        hasRenderableSampledAudio = new AudioRenderer().hasRenderableAudio(nativeProgram);

        List<String> allWarnings = new ArrayList<String>();
        allWarnings.addAll(nativeProgram.warnings);
        allWarnings.addAll(midiPlan.warnings);
        warnings = Collections.unmodifiableList(allWarnings);
    }

    public boolean hasMidi() {
        return hasMidi;
    }

    public boolean hasRenderableSampledAudio() {
        return hasRenderableSampledAudio;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    /** Returns a new mutable Java Sound sequence; callers never receive the internal MIDI model. */
    public Sequence createMidiSequence() throws InvalidMidiDataException {
        if (!hasMidi) throw new IllegalStateException("MLD contains no renderable melody");
        return new MidiSequenceEncoder().encode(midiPlan).sequence;
    }

    /** Renders verified sampled audio at the player's native renderer rate. */
    public MldPcm16 renderSampledPcm16() {
        if (!hasRenderableSampledAudio) {
            throw new IllegalStateException("MLD contains no renderable sampled audio");
        }
        return fromStereo(new AudioRenderer().render(nativeProgram));
    }

    /** Renders verified sampled audio and explicitly converts it to the requested host rate. */
    public MldPcm16 renderSampledPcm16(int targetRate) {
        if (!hasRenderableSampledAudio) {
            throw new IllegalStateException("MLD contains no renderable sampled audio");
        }
        return fromStereo(new AudioRenderer().render(nativeProgram, targetRate));
    }

    private static MldPcm16 fromStereo(StereoPcm pcm) {
        return new MldPcm16(pcm.getSampleRate(), 2, pcm.copyInterleavedPcm16());
    }
}
