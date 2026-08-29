package main;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.sound.midi.MidiSystem;

import midi.MidiPlan;
import midi.MidiProjector;
import midi.MidiSequenceEncoder;
import mld.decode.DecodedTrack;
import mld.decode.TrackDecoder;
import mld.format.MldDocument;
import mld.format.MldReader;
import mld.semantic.NativeCompiler;
import mld.semantic.NativeProgram;
import playback.PlaybackContent;

/** Regression coverage for the shared CLI/Swing application workflow and playlist model. */
public final class ApplicationCompositionAudit {
    private ApplicationCompositionAudit() {
    }

    public static void main(String[] args) throws Exception {
        auditSharedLoadAndPlaybackComposition();
        auditSampledAudioDisplayDuration();
        auditPlaylistState();
        auditPlaylistFilteringHelpers();
        auditEmbeddedMldScanner();
        System.out.println("ApplicationCompositionAudit: PASS");
    }

    private static void auditSharedLoadAndPlaybackComposition() throws Exception {
        Path input = Files.createTempFile("mld-round10-", ".mld");
        try {
            Files.write(input, fixture());
            MldApplicationWorkflow workflow = new MldApplicationWorkflow();
            ApplicationTrack track = workflow.load(input);

            eq("title", "Workflow Song", track.title);
            eq("copyright", "Round10", track.copyright);
            eq("decoded tracks", 1, track.decodedTracks.size());
            eq("native notes", 1, track.program.melody.notes.size());
            eq("MIDI notes", 1, track.midi.notes.size());
            yes("MIDI playable", track.hasMidi());
            no("sampled audio absent", track.renderableAudio);
            yes("track playable", track.isPlayable());
            eq("display title", "Workflow Song", track.displayTitle());
            eq("display copyright", "Round10", track.displayCopyright());
            eq("duration helper", MldApplicationWorkflow.estimateDurationMillis(track.midi), track.durationMillis);

            PlaybackContent content = workflow.preparePlayback(track);
            yes("playback MIDI participant", content.hasMidi());
            no("playback PCM participant", content.hasPcm());

            DirectResult direct = directCompile(input);
            byte[] workflowMidi = midiBytes(new MidiSequenceEncoder().encode(content.midi));
            byte[] directMidi = midiBytes(new MidiSequenceEncoder().encode(direct.midi));
            bytes("shared workflow MIDI serialization", directMidi, workflowMidi);
            eq("direct native notes", direct.program.melody.notes.size(), track.program.melody.notes.size());
        } finally {
            Files.deleteIfExists(input);
        }
    }

    private static void auditPlaylistState() throws Exception {
        PlaylistState playlist = new PlaylistState();
        Path base = Files.createTempDirectory("mld-round10-playlist-");
        try {
            Path firstPath = base.resolve("first.mld");
            Path secondPath = base.resolve("second.mfi");
            PlaylistState.Entry first = playlist.ensure(firstPath);
            PlaylistState.Entry duplicate = playlist.ensure(base.resolve(".").resolve("first.mld"));
            PlaylistState.Entry second = playlist.ensure(secondPath);

            same("normalized duplicate", first, duplicate);
            eq("playlist size", 2, playlist.size());
            eq("first index", 0, playlist.indexOf(first));
            eq("second index", 1, playlist.indexOf(second));
            eq("next from first", 1, playlist.nextIndex(first, -1));
            eq("wrap from second", 0, playlist.nextIndex(second, -1));
            eq("selected fallback", 1, playlist.nextIndex(null, 0));
            yes("accept mld", PlaylistState.accepts(firstPath));
            yes("accept mfi", PlaylistState.accepts(secondPath));
            no("reject midi", PlaylistState.accepts(base.resolve("x.mid")));
            eq("loop initial", PlaylistState.LoopMode.SINGLE_TRACK, playlist.loopMode());
            eq("loop toggled", PlaylistState.LoopMode.PLAYLIST, playlist.toggleLoopMode());
            eq("loop toggled back", PlaylistState.LoopMode.SINGLE_TRACK, playlist.toggleLoopMode());
            eq("input path count", 2, playlist.inputPaths().size());
            ApplicationTrack untitled = new ApplicationTrack(
                    firstPath,
                    null,
                    Collections.<DecodedTrack>emptyList(),
                    null,
                    null,
                    "",
                    "",
                    0L,
                    false);
            eq("missing copyright fallback", "Unknown Artist", untitled.displayCopyright());
        } finally {
            Files.deleteIfExists(base);
        }
    }

    private static void auditPlaylistFilteringHelpers() throws Exception {
        Path base = Files.createTempDirectory("mld-playlist-filter-");
        try {
            PlaylistState playlist = new PlaylistState();
            PlaylistState.Entry alpha = playlist.ensure(base.resolve("Alpha Song.mld"));
            alpha.displayTitle = "Alpha Song";
            PlaylistState.Entry beta = playlist.ensure(base.resolve("boss_theme.mfi"));
            beta.displayTitle = "Boss Theme";
            alpha.loadedTrack = new ApplicationTrack(
                    alpha.inputPath,
                    null,
                    Collections.<DecodedTrack>emptyList(),
                    null,
                    null,
                    "Alpha Song",
                    "Round10",
                    0L,
                    false);

            List<PlaylistState.Entry> snapshot = playlist.entries();
            eq("entries snapshot size", 2, snapshot.size());
            snapshot.clear();
            eq("entries snapshot does not mutate playlist", 2, playlist.size());
            yes("blank query matches", SwingPlayerController.matchesPlaylistFilter(alpha, "  "));
            yes("title query matches", SwingPlayerController.matchesPlaylistFilter(alpha, "ALPHA"));
            yes("filename query matches", SwingPlayerController.matchesPlaylistFilter(beta, "boss_theme"));
            yes("copyright query matches", SwingPlayerController.matchesPlaylistFilter(alpha, "round10"));
            no("unmatched query rejected", SwingPlayerController.matchesPlaylistFilter(beta, "alpha"));
        } finally {
            Files.deleteIfExists(base);
        }
    }

    private static void auditEmbeddedMldScanner() throws Exception {
        EmbeddedMldScanner scanner = new EmbeddedMldScanner();
        Path base = Files.createTempDirectory("mld-embedded-scan-");
        try {
            byte[] melody = fixture();
            byte[] audio = audioFixture();

            Path directSp = base.resolve("direct.sp");
            Files.write(directSp, concat(new byte[17], melody, new byte[9]));
            List<EmbeddedMldScanner.FoundMld> direct = scanner.scan(directSp);
            eq("SP direct MLD count", 1, direct.size());
            bytes("SP direct MLD bytes", melody, direct.get(0).copyData());
            yes("SP direct offset retained", direct.get(0).origin.endsWith("@0x11"));

            byte[] embeddedZip = zip("resources/music.payload", melody, false);
            Path zippedSp = base.resolve("zipped.sp");
            Files.write(zippedSp, concat(new byte[31], embeddedZip, new byte[7]));
            List<EmbeddedMldScanner.FoundMld> zipped = scanner.scan(zippedSp);
            eq("embedded ZIP MLD count", 1, zipped.size());
            bytes("embedded ZIP MLD bytes", melody, zipped.get(0).copyData());
            yes("embedded ZIP origin", zipped.get(0).origin.contains("!resources/music.payload"));

            byte[] innerZip = zip("deep/song.bin", melody, false);
            byte[] outerZip = zip("opaque/container.dat", innerZip, false);
            Path nestedJar = base.resolve("nested.jar");
            Files.write(nestedJar, outerZip);
            List<EmbeddedMldScanner.FoundMld> nested = scanner.scan(nestedJar);
            eq("nested ZIP MLD count", 1, nested.size());
            bytes("nested ZIP MLD bytes", melody, nested.get(0).copyData());
            yes("nested ZIP origin", nested.get(0).origin.contains("!opaque/container.dat!deep/song.bin"));

            Path gzipSp = base.resolve("gzip.sp");
            Files.write(gzipSp, concat(new byte[13], gzip(melody), new byte[5]));
            List<EmbeddedMldScanner.FoundMld> gzip = scanner.scan(gzipSp);
            eq("embedded GZIP MLD count", 1, gzip.size());
            bytes("embedded GZIP MLD bytes", melody, gzip.get(0).copyData());

            Path concatenated = base.resolve("blocks.sp");
            Files.write(concatenated, concat(new byte[3], melody, new byte[4], audio, new byte[2]));
            List<EmbeddedMldScanner.FoundMld> blocks = scanner.scan(concatenated);
            eq("concatenated MLD count", 2, blocks.size());
            bytes("concatenated first MLD", melody, blocks.get(0).copyData());
            bytes("concatenated second MLD", audio, blocks.get(1).copyData());

            Path storedJar = base.resolve("stored.jar");
            Files.write(storedJar, zip("stored/no-extension", melody, true));
            List<EmbeddedMldScanner.FoundMld> stored = scanner.scan(storedJar);
            eq("stored ZIP no duplicate", 1, stored.size());

            Path noise = base.resolve("noise.bin");
            Files.write(noise, new byte[] {
                    'm', 'e', 'l', 'o', 0x7f, 0x7f, 0x7f, 0x7f,
                    'P', 'K', 0x03, 0x04, 0x00, 0x00,
                    0x1f, (byte) 0x8b, 0x08, (byte) 0xe0
            });
            eq("invalid signatures rejected", 0, scanner.scan(noise).size());
        } finally {
            deleteTree(base);
        }
    }

    private static byte[] zip(String name, byte[] payload, boolean stored) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ZipOutputStream zip = new ZipOutputStream(bytes);
        ZipEntry entry = new ZipEntry(name);
        if (stored) {
            CRC32 crc = new CRC32();
            crc.update(payload);
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(payload.length);
            entry.setCompressedSize(payload.length);
            entry.setCrc(crc.getValue());
        }
        zip.putNextEntry(entry);
        zip.write(payload);
        zip.closeEntry();
        zip.close();
        return bytes.toByteArray();
    }

    private static byte[] gzip(byte[] payload) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        GZIPOutputStream gzip = new GZIPOutputStream(bytes);
        gzip.write(payload);
        gzip.close();
        return bytes.toByteArray();
    }

    private static byte[] concat(byte[]... parts) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            bytes.write(part);
        }
        return bytes.toByteArray();
    }

    private static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        List<Path> paths = new java.util.ArrayList<Path>();
        try (java.util.stream.Stream<Path> stream = java.nio.file.Files.walk(root)) {
            stream.forEach(paths::add);
        }
        Collections.sort(paths, Collections.reverseOrder());
        for (Path path : paths) {
            Files.deleteIfExists(path);
        }
    }

    private static void auditSampledAudioDisplayDuration() throws Exception {
        Path input = Files.createTempFile("mld-audio-duration-", ".mld");
        try {
            Files.write(input, audioFixture());
            ApplicationTrack track = new MldApplicationWorkflow().load(input);
            no("audio-only MIDI absent", track.hasMidi());
            yes("audio-only renderable", track.renderableAudio);
            long melodyDuration = MldApplicationWorkflow.estimateDurationMillis(track.midi);
            if (track.durationMillis <= melodyDuration) {
                fail(
                        "sampled-audio display duration",
                        "expected PCM tail beyond melody duration " + melodyDuration
                                + ", got " + track.durationMillis);
            }
        } finally {
            Files.deleteIfExists(input);
        }
    }

    private static DirectResult directCompile(Path input) throws Exception {
        MldDocument document = new MldReader().read(input);
        List<DecodedTrack> decoded = new TrackDecoder().decodeAll(document);
        NativeProgram program = new NativeCompiler().compile(document, decoded);
        MidiPlan midi = new MidiProjector().project(program);
        return new DirectResult(program, midi);
    }

    private static byte[] midiBytes(MidiSequenceEncoder.EncodedSequence encoded) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        MidiSystem.write(encoded.sequence, 1, out);
        return out.toByteArray();
    }

    private static byte[] fixture() throws Exception {
        ByteArrayOutputStream chunks = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(chunks);
        writeBe16Chunk(out, "titl", "Workflow Song".getBytes(StandardCharsets.US_ASCII));
        writeBe16Chunk(out, "copy", "Round10".getBytes(StandardCharsets.US_ASCII));
        writeBe32Chunk(out, "trac", new byte[] {
                0x00, 0x0A, 0x05,
                0x00, (byte) 0xFF, (byte) 0xDF, 0x00
        });
        out.flush();

        byte[] body = chunks.toByteArray();
        ByteArrayOutputStream fileBytes = new ByteArrayOutputStream();
        DataOutputStream file = new DataOutputStream(fileBytes);
        file.writeBytes("melo");
        file.writeInt(5 + body.length);
        file.writeShort(0);
        file.writeByte(1);
        file.writeByte(1);
        file.writeByte(1);
        file.write(body);
        file.flush();
        return fileBytes.toByteArray();
    }

    private static byte[] audioFixture() throws Exception {
        ByteArrayOutputStream chunks = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(chunks);
        writeBe32Chunk(out, "trac", new byte[] {
                0x00, (byte) 0xFF, (byte) 0xFF, 0x00, 0x0B,
                0x71, (byte) 0x84,
                0x00, 0x45, 0x00,
                0x00, 0x00, 0x00, 0x02,
                0x12, 0x34,
                0x00, (byte) 0xFF, (byte) 0xDF, 0x00
        });
        out.flush();

        byte[] body = chunks.toByteArray();
        ByteArrayOutputStream fileBytes = new ByteArrayOutputStream();
        DataOutputStream file = new DataOutputStream(fileBytes);
        file.writeBytes("melo");
        file.writeInt(5 + body.length);
        file.writeShort(0);
        file.writeByte(1);
        file.writeByte(1);
        file.writeByte(1);
        file.write(body);
        file.flush();
        return fileBytes.toByteArray();
    }

    private static void writeBe16Chunk(DataOutputStream out, String id, byte[] payload) throws Exception {
        out.writeBytes(id);
        out.writeShort(payload.length);
        out.write(payload);
    }

    private static void writeBe32Chunk(DataOutputStream out, String id, byte[] payload) throws Exception {
        out.writeBytes(id);
        out.writeInt(payload.length);
        out.write(payload);
    }

    private static final class DirectResult {
        final NativeProgram program;
        final MidiPlan midi;

        DirectResult(NativeProgram program, MidiPlan midi) {
            this.program = program;
            this.midi = midi;
        }
    }

    private static void eq(String label, int expected, int actual) {
        if (expected != actual) fail(label, "expected " + expected + ", got " + actual);
    }

    private static void eq(String label, long expected, long actual) {
        if (expected != actual) fail(label, "expected " + expected + ", got " + actual);
    }

    private static void eq(String label, String expected, String actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            fail(label, "expected " + expected + ", got " + actual);
        }
    }

    private static void eq(String label, PlaylistState.LoopMode expected, PlaylistState.LoopMode actual) {
        if (expected != actual) fail(label, "expected " + expected + ", got " + actual);
    }

    private static void same(String label, Object expected, Object actual) {
        if (expected != actual) fail(label, "expected same instance");
    }

    private static void yes(String label, boolean value) {
        if (!value) fail(label, "expected true");
    }

    private static void no(String label, boolean value) {
        if (value) fail(label, "expected false");
    }

    private static void bytes(String label, byte[] expected, byte[] actual) {
        if (expected.length != actual.length) {
            fail(label, "length expected " + expected.length + ", got " + actual.length);
        }
        for (int i = 0; i < expected.length; i++) {
            if (expected[i] != actual[i]) {
                fail(label, "byte mismatch at " + i);
            }
        }
    }

    private static void fail(String label, String detail) {
        throw new AssertionError(label + ": " + detail);
    }
}
