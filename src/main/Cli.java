package main;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import export.ExportMode;
import export.ExportService;
import mld.format.MldDocument;
import mld.semantic.Diagnostic;
import mld.semantic.NativeProgram;
import midi.MidiPlan;
import normalize.MdNormalizationResult;
import normalize.MdNormalizer;
import playback.PlaybackContent;
import playback.PlaybackSession;

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
        MldApplicationWorkflow workflow = new MldApplicationWorkflow();
        ApplicationTrack track = workflow.load(inputPath);

        if (outputDir != null) {
            new ExportService().exportPrepared(
                    track.program,
                    track.midi,
                    inputPath,
                    outputDir,
                    ExportMode.AUTO);
        }

        MdNormalizationResult md = new MdNormalizer().normalize(track.decodedTracks);
        List<String> combinedWarnings = collectWarnings(track.program, track.midi, md);
        System.out.print(renderSummary(track.document, track.program, track.midi, md));

        play(workflow, track, resolvePlaybackLoopCount(arguments));

        if (!combinedWarnings.isEmpty()) {
            System.out.print(renderWarnings(combinedWarnings));
        }
    }

    static void play(MldApplicationWorkflow workflow, ApplicationTrack track, int loopCount) throws Exception {
        if (workflow == null || track == null) {
            throw new IllegalArgumentException("Playback workflow and track are required.");
        }
        if (!track.isPlayable()) {
            System.out.println("Playback mode: unavailable (no renderable melody or sampled audio)");
            System.out.println("Loop: n/a");
            System.out.println("Backend: n/a");
            System.out.println("Output: n/a");
            System.out.println(BOX_BORDER);
            return;
        }

        PlaybackContent content = workflow.preparePlayback(track);
        new PlaybackSession().play(
                content,
                loopCount,
                new ConsolePlaybackMonitor(),
                null,
                null);
    }

    static String renderSummary(MldDocument file, NativeProgram program, MidiPlan midi, MdNormalizationResult md) {
        MetadataSummary metadata = MetadataSummary.from(file);
        StringBuilder builder = new StringBuilder();

        builder.append(BOX_BORDER).append(System.lineSeparator());
        builder.append("Title: ").append(metadata.title).append(System.lineSeparator());
        builder.append("Copyright: ").append(metadata.copyright).append(System.lineSeparator());
        builder.append(SECTION_DIVIDER).append(System.lineSeparator());
        builder.append("Tracks: ").append(file.tracks.size()).append(System.lineSeparator());
        builder.append("Tempo points: ").append(midi.tempoPoints.size()).append(System.lineSeparator());
        builder.append("Compiled notes: ").append(midi.notes.size()).append(System.lineSeparator());
        builder.append("Mapped controls: ").append(midi.mappedControls.size()).append(System.lineSeparator());
        builder.append("Unknown events: ").append(md.unknownEvents.size()).append(System.lineSeparator());
        if (program.loop.hasNativeLoops()) {
            builder.append("Native DD loops: ")
                    .append(program.loop.activations.size())
                    .append(" activation(s), finite rewinds=")
                    .append(program.loop.finiteRewindCount);
            if (program.loop.hasInfiniteLoop()) {
                builder.append(", infinite cycle raw=")
                        .append(program.loop.infiniteRegion.loopStartRawTick)
                        .append(" -> ")
                        .append(program.loop.infiniteRegion.loopEndRawTick);
            }
            builder.append(System.lineSeparator());
        } else {
            builder.append("Native DD loops: no").append(System.lineSeparator());
        }
        builder.append(SECTION_DIVIDER).append(System.lineSeparator());
        return builder.toString();
    }

    static List<String> collectWarnings(NativeProgram program, MidiPlan midi, MdNormalizationResult md) {
        List<String> combinedWarnings = new ArrayList<String>();
        combinedWarnings.addAll(program.warnings);
        combinedWarnings.addAll(midi.warnings);
        combinedWarnings.addAll(md.warnings);
        for (Diagnostic diagnostic : program.diagnostics) {
            if (diagnostic.severity == Diagnostic.Severity.INFO) {
                continue;
            }
            combinedWarnings.add("[" + diagnostic.code + "] " + diagnostic.message);
        }
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

    private static int resolvePlaybackLoopCount(Arguments arguments) {
        return arguments.loopSpecified ? arguments.loopCount : 0;
    }

    private static void printUsage() {
        System.out.println("Usage:");
        System.out.println("  java -jar mld-player.jar <file.mld> [--output <dir>] [--loop [<n|infinite>]]");
        System.out.println("Launcher:");
        System.out.println("  java -jar mld-player.jar            open the Swing player");
        System.out.println("  java -jar mld-player.jar --gui      force the Swing player");
        System.out.println("Behavior:");
        System.out.println("  passing a file path starts CLI playback directly");
        System.out.println("  native finite DD loops execute their encoded repeat counts and continue");
        System.out.println("  native infinite DD loops remain infinite; --loop repeats completed tracks");
        System.out.println("Options:");
        System.out.println("  --output <path>        optional parent directory for AUTO export into MFiExport");
        System.out.println("  --loop [<value>]       repeat the completed track; use without a value for infinite");
        System.out.println("  --help                 show this message");
    }

    private static final class MetadataSummary {
        final String title;
        final String copyright;

        MetadataSummary(String title, String copyright) {
            this.title = title;
            this.copyright = copyright;
        }

        static MetadataSummary from(MldDocument file) {
            String title = MldApplicationWorkflow.cleanInfoText(file.lastInfoText("titl"));
            String copy = MldApplicationWorkflow.cleanInfoText(file.lastInfoText("copy"));
            return new MetadataSummary(title, copy);
        }

    }

    private static final class Arguments {
        final Path inputPath;
        final Path outputDir;
        final int loopCount;
        final boolean loopSpecified;
        final boolean showHelp;

        Arguments(Path inputPath, Path outputDir, int loopCount, boolean loopSpecified, boolean showHelp) {
            this.inputPath = inputPath;
            this.outputDir = outputDir;
            this.loopCount = loopCount;
            this.loopSpecified = loopSpecified;
            this.showHelp = showHelp;
        }

        static Arguments parse(String[] args) {
            Path input = null;
            Path output = null;
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

            return new Arguments(input, output, loopCount, loopSpecified, showHelp);
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
