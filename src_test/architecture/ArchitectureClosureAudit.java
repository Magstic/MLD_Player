package architecture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Final source-tree architecture gate. */
public final class ArchitectureClosureAudit {
    private static final Pattern PACKAGE = Pattern.compile("(?m)^package\\s+([\\w.]+);\\s*$");
    private static final Pattern IMPORT = Pattern.compile("(?m)^import\\s+(?:static\\s+)?([\\w.]+);\\s*$");

    private ArchitectureClosureAudit() {
    }

    public static void main(String[] args) throws Exception {
        Path sourceRoot = Paths.get("src");
        requireDirectory(sourceRoot);
        List<Path> sources = javaSources(sourceRoot);

        auditLegacyPaths(sourceRoot);
        auditPackagePaths(sourceRoot, sources);
        auditDependencies(sourceRoot, sources);
        auditMldFormatOwnership(sourceRoot);
        auditSemanticBoundary(sourceRoot);
        auditAudioOwnership(sourceRoot, sources);
        auditSingleMidiSerializer(sourceRoot, sources);
        auditExportOwnership(sourceRoot, sources);
        auditDeviceOwnership(sourceRoot, sources);
        auditSwingBoundary(sourceRoot);

        System.out.println("ArchitectureClosureAudit: PASS");
    }

    private static void auditLegacyPaths(Path sourceRoot) {
        String[] forbidden = {
            "container",
            "event",
            "timeline",
            "bridge/mld"
        };
        for (String relative : forbidden) {
            Path path = sourceRoot.resolve(relative);
            if (Files.exists(path)) {
                fail("legacy production path remains: " + unix(path));
            }
        }
    }

    private static void auditPackagePaths(Path sourceRoot, List<Path> sources) throws IOException {
        for (Path source : sources) {
            String text = read(source);
            Matcher matcher = PACKAGE.matcher(text);
            if (!matcher.find()) {
                fail("missing package declaration: " + unix(source));
            }
            String expected = unix(sourceRoot.relativize(source.getParent())).replace('/', '.');
            String actual = matcher.group(1);
            if (!expected.equals(actual)) {
                fail("package/path mismatch in " + unix(source) + ": expected " + expected + ", got " + actual);
            }
        }
    }

    private static void auditDependencies(Path sourceRoot, List<Path> sources) throws IOException {
        Map<String, Set<String>> allowed = new HashMap<String, Set<String>>();
        allowed.put("mld.format", set("java"));
        allowed.put("mld.decode", set("java", "mld.format"));
        allowed.put("mld.semantic", set("java", "mld.decode", "mld.format"));
        allowed.put("midi", set("java", "javax.sound.midi", "mld.semantic"));
        allowed.put("audio", set("java", "mld.semantic"));
        allowed.put("normalize", set("java", "mld.decode"));
        allowed.put("playback", set(
                "java", "javax.sound.midi", "javax.sound.sampled", "audio", "midi", "mld.semantic"));
        allowed.put("export", set(
                "java", "javax.sound.midi", "audio", "midi", "mld.decode", "mld.format", "mld.semantic"));
        allowed.put("main", set(
                "java", "javax.sound.midi", "javax.swing", "java.awt", "audio", "export", "midi",
                "mld.decode", "mld.format", "mld.semantic", "normalize", "playback"));

        for (Path source : sources) {
            String area = area(sourceRoot.relativize(source));
            Set<String> prefixes = allowed.get(area);
            if (prefixes == null) {
                fail("unclassified production package for dependency audit: " + unix(source));
            }
            Matcher imports = IMPORT.matcher(read(source));
            while (imports.find()) {
                String imported = imports.group(1);
                if (!matchesAny(imported, prefixes)) {
                    fail("forbidden dependency in " + unix(source) + ": " + imported);
                }
            }
        }
    }

    private static void auditSemanticBoundary(Path sourceRoot) throws IOException {
        Path semantic = sourceRoot.resolve("mld/semantic");
        String[] forbiddenText = {
            "javax.sound.midi",
            "javax.sound.sampled",
            "javax.swing",
            "java.awt",
            "Map<String, Object>",
            "MidiPlan",
            "AudioRenderer",
            "PlaybackSession"
        };
        for (Path source : javaSources(semantic)) {
            String text = read(source);
            for (String forbidden : forbiddenText) {
                if (text.contains(forbidden)) {
                    fail("semantic boundary violation in " + unix(source) + ": " + forbidden);
                }
            }
        }
    }

    private static void auditMldFormatOwnership(Path sourceRoot) throws IOException {
        Path scanner = sourceRoot.resolve("main/EmbeddedMldScanner.java");
        if (!Files.isRegularFile(scanner)) {
            return;
        }
        String text = read(scanner);
        if (text.contains("isMelo(") || text.contains("readBe32(")) {
            fail("EmbeddedMldScanner must delegate MLD container framing to mld.format.MldReader");
        }
    }

    private static void auditAudioOwnership(Path sourceRoot, List<Path> sources) throws IOException {
        assertExclusiveToken(
                sourceRoot, sources,
                "MfiG726Decoder.decode4BitLittleEndian",
                "audio/Mfi8001Decoder.java");
        assertExclusiveToken(
                sourceRoot, sources,
                "new DecodedSampledResource(",
                "audio/Mfi8001Decoder.java",
                "audio/Mfi8002Decoder.java");
        String renderer = read(sourceRoot.resolve("audio/AudioRenderer.java"));
        if (renderer.contains("MldDocument") || renderer.contains("rawBytes")) {
            fail("AudioRenderer must consume semantic sampled resources, not raw MLD container bytes");
        }
        String playback = read(sourceRoot.resolve("audio/AudioPlaybackSource.java"));
        if (playback.contains("short[] pcm16")) {
            fail("AudioPlaybackSource regressed to the obsolete mono PCM16 voice cache");
        }
    }

    private static void auditSingleMidiSerializer(Path sourceRoot, List<Path> sources) throws IOException {
        Path encoder = sourceRoot.resolve("midi/MidiSequenceEncoder.java").normalize();
        String[] serializerTokens = {
            "new Sequence(",
            ".createTrack(",
            "new MetaMessage(",
            "new MidiEvent("
        };
        for (Path source : sources) {
            if (source.normalize().equals(encoder)) {
                continue;
            }
            String text = read(source);
            for (String token : serializerTokens) {
                if (text.contains(token)) {
                    fail("duplicate MIDI sequence serialization in " + unix(source) + ": " + token);
                }
            }
        }
    }

    private static void auditExportOwnership(Path sourceRoot, List<Path> sources) throws IOException {
        Path legacyExporter = sourceRoot.resolve("bridge/midi/MidiBridgeExporter.java");
        if (Files.exists(legacyExporter)) {
            fail("legacy MIDI/bridge exporter remains: " + unix(legacyExporter));
        }
        Path service = sourceRoot.resolve("export/ExportService.java");
        if (!Files.isRegularFile(service)) {
            fail("ExportService is missing");
        }
        assertExclusiveToken(sourceRoot, sources, "MidiSystem.write", "export/ExportService.java");
    }

    private static void auditDeviceOwnership(Path sourceRoot, List<Path> sources) throws IOException {
        assertExclusiveToken(sourceRoot, sources, "ProcessBuilder", "playback/FluidSynthBackend.java");
        assertExclusiveToken(sourceRoot, sources, "MidiSystem.getMidiDeviceInfo", "playback/MidiOutputCatalog.java");
        assertExclusiveToken(
                sourceRoot, sources, "import javax.sound.sampled.SourceDataLine;", "playback/PcmOutputConnection.java");
        assertExclusiveToken(sourceRoot, sources, "AudioSystem.getLine", "playback/PcmOutputConnection.java");
    }

    private static void auditSwingBoundary(Path sourceRoot) throws IOException {
        String[] files = {
            "main/SwingPlayer.java",
            "main/SwingPlayerController.java",
            "main/SwingPlayerView.java",
            "main/SwingPlayerWidgets.java",
            "main/PlaylistState.java"
        };
        String[] forbiddenImports = {
            "mld.",
            "midi.",
            "audio.",
            "normalize."
        };
        for (String relative : files) {
            Path source = sourceRoot.resolve(relative);
            String text = read(source);
            if ("main/SwingPlayerView.java".equals(relative)
                    && text.contains("playback.MasterVolume")) {
                fail("Swing view owns playback master-volume state directly");
            }
            Matcher imports = IMPORT.matcher(text);
            while (imports.find()) {
                String imported = imports.group(1);
                for (String forbidden : forbiddenImports) {
                    if (imported.startsWith(forbidden)) {
                        fail("Swing composition bypasses application boundary in " + relative + ": " + imported);
                    }
                }
            }
        }
        String controller = read(sourceRoot.resolve("main/SwingPlayerController.java"));
        if (occurrences(controller, "workflow.load(") != 1) {
            fail("SwingPlayerController must centralize ApplicationTrack loading in one shared worker path");
        }
        if (read(sourceRoot.resolve("main/PlaylistState.java")).contains("SwingWorker")) {
            fail("PlaylistState must remain independent of Swing load orchestration");
        }
    }

    private static int occurrences(String text, String token) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(token, offset)) >= 0) {
            count++;
            offset += token.length();
        }
        return count;
    }

    private static void assertExclusiveToken(
            Path sourceRoot,
            List<Path> sources,
            String token,
            String... ownerRelatives) throws IOException {
        for (Path source : sources) {
            boolean owner = false;
            for (String ownerRelative : ownerRelatives) {
                if (source.normalize().equals(sourceRoot.resolve(ownerRelative).normalize())) {
                    owner = true;
                    break;
                }
            }
            if (!owner && read(source).contains(token)) {
                fail(token + " escaped native decoder ownership into " + unix(source));
            }
        }
    }

    private static String area(Path relative) {
        String path = unix(relative);
        if (path.startsWith("mld/format/")) return "mld.format";
        if (path.startsWith("mld/decode/")) return "mld.decode";
        if (path.startsWith("mld/semantic/")) return "mld.semantic";
        int slash = path.indexOf('/');
        return slash >= 0 ? path.substring(0, slash) : path;
    }

    private static boolean matchesAny(String imported, Set<String> prefixes) {
        for (String prefix : prefixes) {
            if (imported.equals(prefix) || imported.startsWith(prefix + ".")) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> set(String... values) {
        return Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(values)));
    }

    private static List<Path> javaSources(Path root) throws IOException {
        if (!Files.exists(root)) {
            return Collections.emptyList();
        }
        List<Path> paths = new ArrayList<Path>();
        java.util.stream.Stream<Path> stream = Files.walk(root);
        try {
            java.util.Iterator<Path> iterator = stream.iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                if (Files.isRegularFile(path) && path.getFileName().toString().endsWith(".java")) {
                    paths.add(path);
                }
            }
        } finally {
            stream.close();
        }
        Collections.sort(paths);
        return paths;
    }

    private static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static void requireDirectory(Path path) {
        if (!Files.isDirectory(path)) {
            fail("source tree not found: " + unix(path));
        }
    }

    private static String unix(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static void fail(String message) {
        throw new AssertionError(message);
    }
}
