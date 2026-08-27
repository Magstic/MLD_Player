package main;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import midi.MidiPlan;
import mld.decode.DecodedTrack;
import mld.format.MldDocument;
import mld.semantic.NativeProgram;

/** Immutable application-facing representation of one loaded MLD/MFi file. */
final class ApplicationTrack {
    final Path inputPath;
    final MldDocument document;
    final List<DecodedTrack> decodedTracks;
    final NativeProgram program;
    final MidiPlan midi;
    final String title;
    final String copyright;
    final long durationMillis;
    final boolean renderableAudio;

    ApplicationTrack(
            Path inputPath,
            MldDocument document,
            List<DecodedTrack> decodedTracks,
            NativeProgram program,
            MidiPlan midi,
            String title,
            String copyright,
            long durationMillis,
            boolean renderableAudio) {
        this.inputPath = inputPath;
        this.document = document;
        this.decodedTracks = Collections.unmodifiableList(new ArrayList<DecodedTrack>(decodedTracks));
        this.program = program;
        this.midi = midi;
        this.title = title == null ? "" : title;
        this.copyright = copyright == null ? "" : copyright;
        this.durationMillis = Math.max(0L, durationMillis);
        this.renderableAudio = renderableAudio;
    }

    boolean hasMidi() {
        return midi != null && !midi.notes.isEmpty();
    }

    boolean isPlayable() {
        return hasMidi() || renderableAudio;
    }

    String displayTitle() {
        return title.isEmpty() ? fileStem(inputPath) : title;
    }

    String displayCopyright() {
        if (!isPlayable()) {
            return copyright.isEmpty()
                    ? "No renderable melody or sampled audio"
                    : copyright + "  |  no renderable media";
        }
        return copyright.isEmpty() ? " " : copyright;
    }

    static String fileStem(Path path) {
        if (path == null || path.getFileName() == null) {
            return "(untitled)";
        }
        String fileName = path.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
