package mld.compile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import midi.MidiPlan;
import midi.MidiProjector;
import mld.decode.DecodedTrack;
import mld.decode.TrackDecoder;
import mld.format.MldDocument;
import mld.format.MldReader;
import mld.semantic.NativeCompiler;
import mld.semantic.NativeProgram;

/** Single owner of the application-neutral read -> decode -> native compile -> MIDI projection chain. */
public final class MldCompiler {
    private final MldReader reader = new MldReader();
    private final TrackDecoder decoder = new TrackDecoder();
    private final NativeCompiler nativeCompiler = new NativeCompiler();
    private final MidiProjector midiProjector = new MidiProjector();

    public MldCompilation compile(Path path) throws IOException {
        if (path == null) throw new IllegalArgumentException("path == null");
        return compileDocument(reader.read(path));
    }

    public MldCompilation compile(byte[] bytes) throws IOException {
        if (bytes == null) throw new IllegalArgumentException("bytes == null");
        return compileDocument(reader.read(bytes));
    }

    private MldCompilation compileDocument(MldDocument document) throws IOException {
        List<DecodedTrack> decodedTracks = decoder.decodeAll(document);
        NativeProgram nativeProgram = nativeCompiler.compile(document, decodedTracks);
        MidiPlan midiPlan = midiProjector.project(nativeProgram);
        return new MldCompilation(document, decodedTracks, nativeProgram, midiPlan);
    }
}
