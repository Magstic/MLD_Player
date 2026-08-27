package export;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MidiSystem;

import audio.AudioRenderer;
import audio.StereoPcm;
import midi.MidiPlan;
import midi.MidiPlanSegmenter;
import midi.MidiProjector;
import midi.MidiSequenceEncoder;
import mld.decode.DecodedTrack;
import mld.decode.TrackDecoder;
import mld.format.MldDocument;
import mld.format.MldReader;
import mld.semantic.NativeCompiler;
import mld.semantic.NativeProgram;

/** Simple user-facing MLD export orchestration: AUTO, MIDI, or sampled-audio WAV. */
public final class ExportService {
    public static final String OUTPUT_DIRECTORY_NAME = "MFiExport";

    private final MldReader reader = new MldReader();
    private final TrackDecoder decoder = new TrackDecoder();
    private final NativeCompiler compiler = new NativeCompiler();
    private final MidiProjector midiProjector = new MidiProjector();
    private final MidiSequenceEncoder midiEncoder = new MidiSequenceEncoder();
    private final MidiPlanSegmenter midiSegmenter = new MidiPlanSegmenter();
    private final AudioRenderer audioRenderer = new AudioRenderer();
    private final WavEncoder wavEncoder = new WavEncoder();

    public ExportArtifacts exportPrepared(
            NativeProgram program,
            MidiPlan midi,
            Path inputPath,
            Path selectedDirectory,
            ExportMode mode) throws IOException, InvalidMidiDataException {
        if (program == null || midi == null) {
            throw new IllegalArgumentException("Compiled native/MIDI representations are required.");
        }
        if (inputPath == null || inputPath.getFileName() == null) {
            throw new IllegalArgumentException("Input path is required.");
        }
        Path outputDir = exportRoot(selectedDirectory);
        Files.createDirectories(outputDir);
        return exportPreparedToRoot(program, midi, inputPath, outputDir, fileStem(inputPath), normalizedMode(mode));
    }

    public BatchArtifacts exportBatch(
            List<Path> inputPaths,
            Path selectedDirectory,
            ExportMode mode) throws IOException {
        Path outputDir = exportRoot(selectedDirectory);
        Files.createDirectories(outputDir);
        ExportMode actualMode = normalizedMode(mode);
        List<String> failures = new ArrayList<String>();
        Set<String> usedStems = new HashSet<String>();
        int exportedCount = 0;

        List<Path> paths = inputPaths == null ? Collections.<Path>emptyList() : inputPaths;
        for (Path inputPath : paths) {
            if (inputPath == null || inputPath.getFileName() == null) {
                failures.add("(invalid path)");
                continue;
            }
            String stem = uniqueStem(fileStem(inputPath), outputDir, usedStems);
            try {
                MldDocument document = reader.read(inputPath);
                List<DecodedTrack> decodedTracks = decoder.decodeAll(document);
                NativeProgram program = compiler.compile(document, decodedTracks);
                MidiPlan midi = midiProjector.project(program);
                exportPreparedToRoot(program, midi, inputPath, outputDir, stem, actualMode);
                exportedCount++;
            } catch (IOException | InvalidMidiDataException | IllegalArgumentException e) {
                String message = e.getMessage();
                failures.add(inputPath.getFileName() + ": "
                        + (message == null || message.trim().isEmpty()
                                ? e.getClass().getSimpleName()
                                : message));
            }
        }
        return new BatchArtifacts(outputDir, exportedCount, failures);
    }

    private ExportArtifacts exportPreparedToRoot(
            NativeProgram program,
            MidiPlan midi,
            Path inputPath,
            Path outputDir,
            String stem,
            ExportMode mode) throws IOException, InvalidMidiDataException {
        boolean hasMidi = hasExportableMidi(midi);
        boolean hasAudio = audioRenderer.hasRenderableAudio(program);
        boolean writeMidi = mode == ExportMode.MIDI || (mode == ExportMode.AUTO && hasMidi);
        boolean writeWav = mode == ExportMode.WAV || (mode == ExportMode.AUTO && hasAudio);

        if (mode == ExportMode.MIDI && !hasMidi) {
            throw new IllegalArgumentException("no renderable melody");
        }
        if (mode == ExportMode.WAV && !hasAudio) {
            throw new IllegalArgumentException("no renderable sampled audio");
        }
        if (mode == ExportMode.AUTO && !hasMidi && !hasAudio) {
            throw new IllegalArgumentException("no renderable melody or sampled audio");
        }

        boolean splitLoopMidi = writeMidi && hasMidi && hasExportableLoop(midi);
        Path artifactDir = splitLoopMidi ? outputDir.resolve(stem) : outputDir;
        boolean artifactDirCreated = splitLoopMidi && !Files.exists(artifactDir);

        Path midiPath = null;
        Path introMidiPath = null;
        Path loopMidiPath = null;
        Path wavPath = null;
        List<Path> created = new ArrayList<Path>();
        try {
            if (splitLoopMidi) {
                Files.createDirectories(artifactDir);
            }
            if (writeMidi && hasMidi) {
                midiPath = artifactDir.resolve(stem + ".mid");
                created.add(midiPath);
                writeMidi(midi, midiPath, "Full");

                if (splitLoopMidi) {
                    long loopStart = midi.loopInfo.loopStartMidiTick;
                    long loopEnd = midi.loopInfo.loopEndMidiTick;
                    MidiPlan intro = midiSegmenter.slice(midi, 0L, loopStart, false);
                    MidiPlan loop = midiSegmenter.slice(midi, loopStart, loopEnd, true);

                    introMidiPath = artifactDir.resolve(stem + ".intro.mid");
                    created.add(introMidiPath);
                    writeMidi(intro, introMidiPath, "Intro");

                    loopMidiPath = artifactDir.resolve(stem + ".loop.mid");
                    created.add(loopMidiPath);
                    writeMidi(loop, loopMidiPath, "Loop");
                }
            }
            if (writeWav && hasAudio) {
                wavPath = artifactDir.resolve(stem + (hasMidi ? ".sfx.wav" : ".wav"));
                created.add(wavPath);
                StereoPcm pcm = audioRenderer.render(program);
                wavEncoder.write(pcm, wavPath);
            }
            return new ExportArtifacts(midiPath, introMidiPath, loopMidiPath, wavPath);
        } catch (IOException | InvalidMidiDataException | RuntimeException e) {
            for (Path path : created) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException cleanupFailure) {
                    e.addSuppressed(cleanupFailure);
                }
            }
            if (artifactDirCreated) {
                try {
                    Files.deleteIfExists(artifactDir);
                } catch (IOException cleanupFailure) {
                    e.addSuppressed(cleanupFailure);
                }
            }
            throw e;
        }
    }

    private static Path exportRoot(Path selectedDirectory) {
        if (selectedDirectory == null) {
            throw new IllegalArgumentException("Export directory is required.");
        }
        return selectedDirectory.toAbsolutePath().normalize().resolve(OUTPUT_DIRECTORY_NAME);
    }

    private static ExportMode normalizedMode(ExportMode mode) {
        return mode == null ? ExportMode.AUTO : mode;
    }

    private static boolean hasExportableMidi(MidiPlan midi) {
        return midi != null && !midi.notes.isEmpty();
    }

    private static boolean hasExportableLoop(MidiPlan midi) {
        return midi != null
                && midi.loopInfo != null
                && midi.loopInfo.hasLoop
                && midi.loopInfo.loopStartMidiTick >= 0L
                && midi.loopInfo.loopEndMidiTick > midi.loopInfo.loopStartMidiTick;
    }

    private void writeMidi(MidiPlan midi, Path path, String segmentName)
            throws IOException, InvalidMidiDataException {
        MidiSequenceEncoder.EncodedSequence encoded = midiEncoder.encode(
                midi,
                MidiSequenceEncoder.TrackNames.exportSegment(segmentName));
        MidiSystem.write(encoded.sequence, 1, path.toFile());
    }

    private static String uniqueStem(String baseStem, Path outputDir, Set<String> usedStems) {
        String stem = baseStem;
        int suffix = 2;
        while (usedStems.contains(stem.toLowerCase(Locale.ROOT)) || anyOutputExists(outputDir, stem)) {
            stem = baseStem + " (" + suffix++ + ")";
        }
        usedStems.add(stem.toLowerCase(Locale.ROOT));
        return stem;
    }

    private static boolean anyOutputExists(Path outputDir, String stem) {
        return Files.exists(outputDir.resolve(stem))
                || Files.exists(outputDir.resolve(stem + ".mid"))
                || Files.exists(outputDir.resolve(stem + ".wav"))
                || Files.exists(outputDir.resolve(stem + ".sfx.wav"));
    }

    private static String fileStem(Path inputPath) {
        String fileName = inputPath.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    public static final class ExportArtifacts {
        public final Path midiPath;
        public final Path introMidiPath;
        public final Path loopMidiPath;
        public final Path wavPath;

        ExportArtifacts(Path midiPath, Path introMidiPath, Path loopMidiPath, Path wavPath) {
            this.midiPath = midiPath;
            this.introMidiPath = introMidiPath;
            this.loopMidiPath = loopMidiPath;
            this.wavPath = wavPath;
        }
    }

    public static final class BatchArtifacts {
        public final Path outputDir;
        public final int exportedCount;
        public final List<String> failures;

        BatchArtifacts(Path outputDir, int exportedCount, List<String> failures) {
            this.outputDir = outputDir;
            this.exportedCount = exportedCount;
            this.failures = Collections.unmodifiableList(new ArrayList<String>(failures));
        }
    }
}
