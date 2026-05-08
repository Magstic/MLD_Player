package main;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import bridge.mld.GeneratedMldSong;
import bridge.mld.ImportedMidiSong;
import bridge.mld.MidiSequenceImporter;
import bridge.mld.MidiToMldConverter;
import bridge.mld.MldContainerWriter;
import bridge.midi.MidiBridgeExporter;
import container.MldFile;
import container.MldParser;
import event.TrackDecodeResult;
import event.TrackDecoder;
import normalize.MdNormalizationResult;
import normalize.MdNormalizer;
import playback.JavaMidiPlayer;
import playback.PlaybackSequenceBuilder;
import timeline.PlaybackTimeline;
import timeline.TimelineCompiler;

public final class Cli {
    private static final String BOX_BORDER = "************************************************************";
    private static final String SECTION_DIVIDER = "------------------------------------------------------------";

    public static void main(String[] args) throws Exception {
        Arguments arguments = Arguments.parse(args);
        if (arguments.showHelp) {
            printUsage();
            return;
        }
        if (arguments.inputPath == null) {
            printUsage();
            System.exit(2);
            return;
        }

        new Cli().run(arguments);
    }

    private void run(Arguments arguments) throws Exception {
        Path inputPath = arguments.inputPath.toAbsolutePath().normalize();
        Path outputDir = arguments.outputDir != null ? arguments.outputDir.toAbsolutePath().normalize() : null;
        Path toMldPath = arguments.toMldPath != null ? arguments.toMldPath.toAbsolutePath().normalize() : null;

        if (toMldPath != null) {
            convertMidiToMld(inputPath, toMldPath);
            return;
        }

        PlaybackTimeline timeline = buildTimeline(inputPath);

        if (outputDir != null) {
            MidiBridgeExporter exporter = new MidiBridgeExporter();
            exporter.export(timeline, inputPath, outputDir);
        }

        List<String> combinedWarnings = collectWarnings(timeline);
        System.out.print(renderSummary(timeline.file, timeline));

        playTimeline(timeline, resolvePlaybackLoopCount(timeline, arguments));

        if (!combinedWarnings.isEmpty()) {
            System.out.print(renderWarnings(combinedWarnings));
        }
    }

    private void convertMidiToMld(Path inputPath, Path outputPath) throws Exception {
        MidiSequenceImporter importer = new MidiSequenceImporter();
        ImportedMidiSong imported = importer.importSequence(inputPath);
        GeneratedMldSong generated = new MidiToMldConverter().convert(imported);
        new MldContainerWriter().writeToPath(generated, outputPath);

        PlaybackTimeline validatedTimeline = buildTimeline(outputPath);
        List<String> warnings = new ArrayList<String>();
        warnings.addAll(imported.warnings);
        warnings.addAll(generated.warnings);
        warnings.addAll(collectWarnings(validatedTimeline));

        System.out.println("MIDI -> MLD");
        System.out.println(SECTION_DIVIDER);
        System.out.println("Input: " + inputPath);
        System.out.println("Output: " + outputPath);
        System.out.println("Title: " + generated.title);
        System.out.println("Copyright: " + generated.copyright);
        System.out.println("Input PPQ: " + imported.inputPpq);
        System.out.println("Selected timebase: " + generated.timebase);
        System.out.println("Generated tracks: " + generated.trackCount);
        System.out.println("Generated notes: " + generated.noteCount);
        System.out.println("Generated controls: " + generated.controlCount);
        System.out.println("Generated tempos: " + generated.tempoCount);
        System.out.println("Validated compiled notes: " + validatedTimeline.notes.size());
        System.out.println("Validated tempo points: " + validatedTimeline.tempoPoints.size());
        System.out.println("Validated mapped controls: " + validatedTimeline.mappedControls.size());
        System.out.println(BOX_BORDER);

        if (!warnings.isEmpty()) {
            System.out.print(renderWarnings(warnings));
        }
    }

    static PlaybackTimeline buildTimeline(Path inputPath) throws Exception {
        MldParser parser = new MldParser();
        MldFile file = parser.parse(inputPath);

        TrackDecoder decoder = new TrackDecoder();
        List<TrackDecodeResult> decodedTracks = new ArrayList<TrackDecodeResult>();
        for (int i = 0; i < file.tracks.size(); i++) {
            decodedTracks.add(decoder.decode(file, file.tracks.get(i)));
        }

        MdNormalizer mdNormalizer = new MdNormalizer();
        MdNormalizationResult mdNormalization = mdNormalizer.normalize(decodedTracks);

        TimelineCompiler compiler = new TimelineCompiler();
        return compiler.compile(file, decodedTracks, mdNormalization);
    }

    static void playTimeline(PlaybackTimeline timeline, int loopCount) throws Exception {
        if (timeline.notes.isEmpty()) {
            System.out.println("Playback mode: unavailable (no ordinary notes)");
            System.out.println("Loop: n/a");
            System.out.println("MIDI backend: n/a");
            System.out.println("MIDI output: n/a");
            System.out.println(BOX_BORDER);
            return;
        }

        PlaybackSequenceBuilder sequenceBuilder = new PlaybackSequenceBuilder();
        JavaMidiPlayer player = new JavaMidiPlayer();
        player.play(sequenceBuilder.build(timeline, loopCount), loopCount);
    }

    static String renderSummary(MldFile file, PlaybackTimeline timeline) {
        MetadataSummary metadata = MetadataSummary.from(file);
        StringBuilder builder = new StringBuilder();

        builder.append(BOX_BORDER).append(System.lineSeparator());
        builder.append("Title: ").append(metadata.title).append(System.lineSeparator());
        builder.append("Copyright: ").append(metadata.copyright).append(System.lineSeparator());
        builder.append(SECTION_DIVIDER).append(System.lineSeparator());
        builder.append("Tracks: ").append(file.tracks.size()).append(System.lineSeparator());
        builder.append("Tempo points: ").append(timeline.tempoPoints.size()).append(System.lineSeparator());
        builder.append("Compiled notes: ").append(timeline.notes.size()).append(System.lineSeparator());
        builder.append("Mapped controls: ").append(timeline.mappedControls.size()).append(System.lineSeparator());
        builder.append("Unknown events: ").append(timeline.mdNormalization.unknownEvents.size()).append(System.lineSeparator());
        if (timeline.loopInfo.hasLoop) {
            builder.append("Loop: yes (slot=")
                    .append(timeline.loopInfo.loopSlot)
                    .append(", raw=")
                    .append(timeline.loopInfo.loopStartRawTick)
                    .append(" -> ")
                    .append(timeline.loopInfo.loopEndRawTick)
                    .append(", repeat=")
                    .append(formatLoopCount(timeline.loopInfo.repeatCount))
                    .append(")")
                    .append(System.lineSeparator());
        } else {
            builder.append("Loop: no").append(System.lineSeparator());
        }
        builder.append(SECTION_DIVIDER).append(System.lineSeparator());
        return builder.toString();
    }

    static List<String> collectWarnings(PlaybackTimeline timeline) {
        List<String> combinedWarnings = new ArrayList<String>();
        combinedWarnings.addAll(timeline.warnings);
        combinedWarnings.addAll(timeline.mdNormalization.warnings);
        return combinedWarnings;
    }

    static String renderWarnings(List<String> combinedWarnings) {
        StringBuilder builder = new StringBuilder();
        builder.append("Warnings:").append(System.lineSeparator());
        for (String warning : combinedWarnings) {
            builder.append("  - ").append(warning).append(System.lineSeparator());
        }
        return builder.toString();
    }

    private static int resolvePlaybackLoopCount(PlaybackTimeline timeline, Arguments arguments) {
        boolean hasLoop = timeline != null && timeline.loopInfo != null && timeline.loopInfo.hasLoop;
        if (!hasLoop) {
            return 0;
        }
        if (arguments.loopSpecified) {
            return arguments.loopCount;
        }
        return -1;
    }

    private static String formatLoopCount(int loopCount) {
        return loopCount < 0 ? "infinite" : String.valueOf(loopCount);
    }

    private static void printUsage() {
        System.out.println("Usage:");
        System.out.println("  java -jar mld-player.jar <file.mld> [--output <dir>] [--loop [<n|infinite>]]");
        System.out.println("  java -jar mld-player.jar <file.mid> --to-mld <file.mld>");
        System.out.println("Launcher:");
        System.out.println("  java -jar mld-player.jar            open the Swing player");
        System.out.println("  java -jar mld-player.jar --gui      force the Swing player");
        System.out.println("Behavior:");
        System.out.println("  passing a file path starts CLI playback directly");
        System.out.println("  looped files repeat infinitely by default");
        System.out.println("Options:");
        System.out.println("  --output <path>        optional output directory for MIDI / bridge.json");
        System.out.println("  --to-mld <path>        convert a PPQ MIDI file into an MLD file");
        System.out.println("  --loop [<value>]       override playback loop count; use without a value for infinite");
        System.out.println("  --help                 show this message");
    }

    private static final class MetadataSummary {
        final String title;
        final String copyright;

        MetadataSummary(String title, String copyright) {
            this.title = title;
            this.copyright = copyright;
        }

        static MetadataSummary from(MldFile file) {
            String title = cleanInfoText(file.lastInfoText("titl"));
            String copy = cleanInfoText(file.lastInfoText("copy"));
            return new MetadataSummary(title, copy);
        }

        private static String cleanInfoText(String value) {
            if (value == null) {
                return "";
            }
            int nul = value.indexOf('\0');
            String cleaned = nul >= 0 ? value.substring(0, nul) : value;
            return cleaned.trim();
        }
    }

    private static final class Arguments {
        final Path inputPath;
        final Path outputDir;
        final Path toMldPath;
        final int loopCount;
        final boolean loopSpecified;
        final boolean showHelp;

        Arguments(Path inputPath, Path outputDir, Path toMldPath, int loopCount, boolean loopSpecified, boolean showHelp) {
            this.inputPath = inputPath;
            this.outputDir = outputDir;
            this.toMldPath = toMldPath;
            this.loopCount = loopCount;
            this.loopSpecified = loopSpecified;
            this.showHelp = showHelp;
        }

        static Arguments parse(String[] args) {
            Path input = null;
            Path output = null;
            Path toMld = null;
            int loopCount = 0;
            boolean loopSpecified = false;
            boolean showHelp = false;

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if ("--help".equals(arg) || "-h".equals(arg)) {
                    showHelp = true;
                    continue;
                }
                if ("--output".equals(arg) && i + 1 < args.length) {
                    output = Paths.get(args[++i]);
                    continue;
                }
                if ("--to-mld".equals(arg) && i + 1 < args.length) {
                    toMld = Paths.get(args[++i]);
                    continue;
                }
                if ("--loop".equals(arg)) {
                    loopSpecified = true;
                    if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                        loopCount = parseLoopCount(args[++i]);
                    } else {
                        loopCount = -1;
                    }
                    continue;
                }
                if (arg.startsWith("--")) {
                    throw new IllegalArgumentException("Unknown or incomplete argument: " + arg);
                }
                if (input == null) {
                    input = Paths.get(arg);
                    continue;
                }
                throw new IllegalArgumentException("Unknown or incomplete argument: " + arg);
            }

            if (toMld != null && output != null) {
                throw new IllegalArgumentException("--output cannot be used together with --to-mld.");
            }
            if (toMld != null && loopSpecified) {
                throw new IllegalArgumentException("--loop cannot be used together with --to-mld.");
            }

            return new Arguments(input, output, toMld, loopCount, loopSpecified, showHelp);
        }

        private static int parseLoopCount(String value) {
            if ("infinite".equalsIgnoreCase(value) || "inf".equalsIgnoreCase(value)) {
                return -1;
            }
            int parsed = Integer.parseInt(value);
            if (parsed < 0) {
                throw new IllegalArgumentException("Loop count must be >= 0 or 'infinite'.");
            }
            return parsed;
        }
    }
}
