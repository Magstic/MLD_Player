package mld.compile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import midi.MidiPlan;
import mld.decode.DecodedTrack;
import mld.format.MldDocument;
import mld.semantic.NativeProgram;

/** Immutable application-neutral result of the canonical MLD compilation pipeline. */
public final class MldCompilation {
    private final MldDocument document;
    private final List<DecodedTrack> decodedTracks;
    private final NativeProgram nativeProgram;
    private final MidiPlan midiPlan;

    MldCompilation(
            MldDocument document,
            List<DecodedTrack> decodedTracks,
            NativeProgram nativeProgram,
            MidiPlan midiPlan) {
        if (document == null) throw new IllegalArgumentException("document == null");
        if (decodedTracks == null) throw new IllegalArgumentException("decodedTracks == null");
        if (nativeProgram == null) throw new IllegalArgumentException("nativeProgram == null");
        if (midiPlan == null) throw new IllegalArgumentException("midiPlan == null");
        this.document = document;
        this.decodedTracks = Collections.unmodifiableList(new ArrayList<DecodedTrack>(decodedTracks));
        this.nativeProgram = nativeProgram;
        this.midiPlan = midiPlan;
    }

    public MldDocument getDocument() {
        return document;
    }

    public List<DecodedTrack> getDecodedTracks() {
        return decodedTracks;
    }

    public NativeProgram getNativeProgram() {
        return nativeProgram;
    }

    public MidiPlan getMidiPlan() {
        return midiPlan;
    }
}
