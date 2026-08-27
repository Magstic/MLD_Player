package main;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import javax.sound.midi.MidiSystem;

import midi.MidiLoopMaterializer;
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
            eq("duration helper", MldApplicationWorkflow.estimateDurationMillis(track.midi), track.durationMillis);

            PlaybackContent content = workflow.preparePlayback(track, 0);
            yes("playback MIDI participant", content.hasMidi());
            no("playback PCM participant", content.hasPcm());

            DirectResult direct = directCompile(input);
            byte[] workflowMidi = midiBytes(content.midi);
            byte[] directMidi = midiBytes(new MidiSequenceEncoder().encode(
                    new MidiLoopMaterializer().materializeFiniteLoop(direct.midi, 0)));
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
        } finally {
            Files.deleteIfExists(base);
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
