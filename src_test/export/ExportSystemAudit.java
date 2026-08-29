package export;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;

import audio.AudioRenderer;
import audio.StereoPcm;
import midi.MidiPlan;
import midi.MidiProjector;
import mld.decode.DecodedTrack;
import mld.decode.MachineDependentEvent;
import mld.decode.NoteEvent;
import mld.decode.SystemEvent;
import mld.decode.TrackDecoder;
import mld.decode.TrackEvent;
import mld.format.MldDocument;
import mld.semantic.NativeCompiler;
import mld.semantic.NativeProgram;

/** Regression audit for the simple AUTO/MIDI/WAV export contract. */
public final class ExportSystemAudit {
    private ExportSystemAudit() {
    }

    public static void main(String[] args) throws Exception {
        auditAutoNamingAndWavEncoding();
        auditExplicitModes();
        auditFiniteLoopMidiLinearExport();
        auditInfiniteLoopMidiSegmentExport();
        auditWavUsesSemanticFiniteLoopTimeline();
        System.out.println("ExportSystemAudit: PASS");
    }

    private static void auditAutoNamingAndWavEncoding() throws Exception {
        Path parent = Files.createTempDirectory("mld-export-auto-");
        try {
            ExportService service = new ExportService();

            Compiled melody = compile(Collections.<TrackEvent>singletonList(note(0, 0, 10)), 10);
            ExportService.ExportArtifacts melodyOut = service.exportPrepared(
                    melody.program, melody.midi, parent.resolve("melody.mld"), parent, ExportMode.AUTO);
            pathName("melody MIDI", "melody.mid", melodyOut.midiPath);
            isNull("melody WAV", melodyOut.wavPath);

            Compiled sample = compile(Collections.<TrackEvent>singletonList(audio(0, 0, 2, 0x45)), 0);
            ExportService.ExportArtifacts sampleOut = service.exportPrepared(
                    sample.program, sample.midi, parent.resolve("sample.mld"), parent, ExportMode.AUTO);
            isNull("sample MIDI", sampleOut.midiPath);
            pathName("sample WAV", "sample.wav", sampleOut.wavPath);
            auditWaveFile(sampleOut.wavPath, new AudioRenderer().render(sample.program));

            List<TrackEvent> mixedEvents = new ArrayList<TrackEvent>();
            mixedEvents.add(note(0, 0, 10));
            mixedEvents.add(audio(1, 0, 2, 0x45));
            Compiled mixed = compile(mixedEvents, 10);
            ExportService.ExportArtifacts mixedOut = service.exportPrepared(
                    mixed.program, mixed.midi, parent.resolve("mixed.mld"), parent, ExportMode.AUTO);
            pathName("mixed MIDI", "mixed.mid", mixedOut.midiPath);
            pathName("mixed WAV", "mixed.sfx.wav", mixedOut.wavPath);

            Path root = parent.resolve(ExportService.OUTPUT_DIRECTORY_NAME);
            yes("MFiExport root", Files.isDirectory(root));
            no("legacy bridge json", Files.exists(root.resolve("bridge.json")));
            no("legacy intro", Files.exists(root.resolve("intro.mid")));
            no("legacy loop", Files.exists(root.resolve("loop.mid")));
            no("legacy full", Files.exists(root.resolve("full.mid")));
        } finally {
            deleteTree(parent);
        }
    }


    private static void auditFiniteLoopMidiLinearExport() throws Exception {
        Path parent = Files.createTempDirectory("mld-export-midi-loop-");
        try {
            List<TrackEvent> events = new ArrayList<TrackEvent>();
            events.add(note(0, 0, 8));
            events.add(system(1, 10, 0xDD, 0x00));
            events.add(note(2, 12, 4));
            events.add(system(3, 20, 0xDD, 0x09)); // finite N=2
            events.add(note(4, 25, 3)); // suffix must remain reachable
            Compiled compiled = compile(events, 25);
            no("finite DD has no host loop projection", compiled.midi.loopInfo.hasLoop);
            eq("finite DD rewind count", 2, compiled.program.loop.finiteRewindCount);
            eq("finite DD projected note count", 5, compiled.midi.notes.size());

            ExportService.ExportArtifacts out = new ExportService().exportPrepared(
                    compiled.program, compiled.midi, parent.resolve("looped.mld"), parent, ExportMode.AUTO);

            Path root = parent.resolve(ExportService.OUTPUT_DIRECTORY_NAME);
            pathName("finite loop primary MIDI", "looped.mid", out.midiPath);
            isNull("finite loop intro segment", out.introMidiPath);
            isNull("finite loop body segment", out.loopMidiPath);
            isNull("finite loop melody WAV", out.wavPath);
            eqPath("finite loop flat parent", root, out.midiPath.getParent());
            no("finite loop does not create segment directory", Files.exists(root.resolve("looped")));

            Sequence primary = MidiSystem.getSequence(out.midiPath.toFile());
            eqLong("finite loop full linear end", compiled.midi.totalMidiTicks + 1L, primary.getTickLength());

            List<TrackEvent> mixedEvents = new ArrayList<TrackEvent>();
            mixedEvents.add(note(0, 0, 8));
            mixedEvents.add(system(1, 10, 0xDD, 0x00));
            mixedEvents.add(note(2, 12, 4));
            mixedEvents.add(audio(5, 14, 8, 0x45));
            mixedEvents.add(system(3, 20, 0xDD, 0x09));
            mixedEvents.add(note(4, 25, 3));
            Compiled mixed = compile(mixedEvents, 25);
            eq("mixed finite sampled action executions", 3, mixed.program.audio.actions.size());
            ExportService.ExportArtifacts mixedOut = new ExportService().exportPrepared(
                    mixed.program, mixed.midi, parent.resolve("mixed-loop.mld"), parent, ExportMode.AUTO);
            eqPath("mixed finite MIDI parent", root, mixedOut.midiPath.getParent());
            isNull("mixed finite intro segment", mixedOut.introMidiPath);
            isNull("mixed finite loop segment", mixedOut.loopMidiPath);
            pathName("mixed finite WAV", "mixed-loop.sfx.wav", mixedOut.wavPath);
            eqPath("mixed finite WAV parent", root, mixedOut.wavPath.getParent());
        } finally {
            deleteTree(parent);
        }
    }

    private static void auditInfiniteLoopMidiSegmentExport() throws Exception {
        Path parent = Files.createTempDirectory("mld-export-midi-infinite-");
        try {
            List<TrackEvent> events = new ArrayList<TrackEvent>();
            events.add(note(0, 0, 4));
            events.add(system(1, 10, 0xDD, 0x00));
            events.add(note(2, 12, 4));
            events.add(system(3, 20, 0xDD, 0x01)); // repeat nibble 0 -> native infinite
            events.add(note(4, 25, 3)); // unreachable suffix
            Compiled compiled = compile(events, 25);
            yes("native infinite has host loop projection", compiled.midi.loopInfo.hasLoop);

            ExportService.ExportArtifacts out = new ExportService().exportPrepared(
                    compiled.program, compiled.midi, parent.resolve("infinite.mld"), parent, ExportMode.AUTO);
            Path dir = parent.resolve(ExportService.OUTPUT_DIRECTORY_NAME).resolve("infinite");
            eqPath("infinite export segment directory", dir, out.midiPath.getParent());
            pathName("infinite full MIDI", "infinite.mid", out.midiPath);
            pathName("infinite intro MIDI", "infinite.intro.mid", out.introMidiPath);
            pathName("infinite cycle MIDI", "infinite.loop.mid", out.loopMidiPath);
            isNull("infinite melody WAV", out.wavPath);
            yes("infinite intro exists", Files.isRegularFile(out.introMidiPath));
            yes("infinite cycle exists", Files.isRegularFile(out.loopMidiPath));
        } finally {
            deleteTree(parent);
        }
    }

    private static void auditExplicitModes() throws Exception {
        Path parent = Files.createTempDirectory("mld-export-mode-");
        try {
            ExportService service = new ExportService();
            Compiled melody = compile(Collections.<TrackEvent>singletonList(note(0, 0, 10)), 10);
            Compiled sample = compile(Collections.<TrackEvent>singletonList(audio(0, 0, 2, 0x45)), 0);

            expectRejected("MIDI rejects sample-only", new CheckedAction() {
                @Override
                public void run() throws Exception {
                    service.exportPrepared(
                            sample.program, sample.midi, parent.resolve("sample.mld"), parent, ExportMode.MIDI);
                }
            });
            expectRejected("WAV rejects melody-only", new CheckedAction() {
                @Override
                public void run() throws Exception {
                    service.exportPrepared(
                            melody.program, melody.midi, parent.resolve("melody.mld"), parent, ExportMode.WAV);
                }
            });
        } finally {
            deleteTree(parent);
        }
    }

    private static void auditWavUsesSemanticFiniteLoopTimeline() throws Exception {
        Path parent = Files.createTempDirectory("mld-export-loop-");
        try {
            List<TrackEvent> events = new ArrayList<TrackEvent>();
            events.add(system(0, 10, 0xDD, 0x00));
            events.add(audio(1, 19, 8, 0x45));
            events.add(system(2, 20, 0xDD, 0x09));
            Compiled compiled = compile(events, 20);
            StereoPcm linear = new AudioRenderer().render(compiled.program);
            ExportService.ExportArtifacts out = new ExportService().exportPrepared(
                    compiled.program, compiled.midi, parent.resolve("looped.mld"), parent, ExportMode.WAV);
            pathName("looped WAV", "looped.wav", out.wavPath);
            int dataBytes = le32(Files.readAllBytes(out.wavPath), 40);
            eq("linear WAV data bytes", linear.getFrameCount() * 4, dataBytes);
        } finally {
            deleteTree(parent);
        }
    }

    private static void auditWaveFile(Path path, StereoPcm pcm) throws Exception {
        byte[] bytes = Files.readAllBytes(path);
        text("RIFF", bytes, 0, "RIFF");
        text("WAVE", bytes, 8, "WAVE");
        text("fmt", bytes, 12, "fmt ");
        eq("PCM format", 1, le16(bytes, 20));
        eq("stereo channels", 2, le16(bytes, 22));
        eq("sample rate", pcm.getSampleRate(), le32(bytes, 24));
        eq("bits", 16, le16(bytes, 34));
        text("data", bytes, 36, "data");
        eq("data size", pcm.getFrameCount() * 4, le32(bytes, 40));
        eq("file size", 44 + pcm.getFrameCount() * 4, bytes.length);
    }

    private static Compiled compile(List<TrackEvent> events, int totalRawTicks) {
        int noteCount = 0;
        int systemCount = 0;
        int machineCount = 0;
        for (TrackEvent event : events) {
            if (event instanceof NoteEvent) noteCount++;
            if (event instanceof SystemEvent) systemCount++;
            if (event instanceof MachineDependentEvent) machineCount++;
        }
        DecodedTrack decoded = new DecodedTrack(
                0, 0, totalRawTicks, noteCount, 0, systemCount, machineCount,
                events, Collections.<String>emptyList());
        MldDocument document = new MldDocument(
                new byte[0], "melo", 0, 0, 0, 0, 1, 0, 0,
                Collections.<Long>emptyList(), Collections.emptyList(), Collections.emptyList());
        NativeProgram program = new NativeCompiler().compile(document, Collections.singletonList(decoded));
        return new Compiled(program, new MidiProjector().project(program));
    }

    private static NoteEvent note(int eventIndex, int rawTick, int gate) {
        return new NoteEvent(0, eventIndex, 0, rawTick, 0x05, 0, 5, gate, 48, 0, 1);
    }

    private static SystemEvent system(int eventIndex, int rawTick, int command, int value) {
        return new SystemEvent(
                0, eventIndex, 0, rawTick, command, value,
                TrackDecoder.commandName(command), -1, -1);
    }

    private static MachineDependentEvent audio(
            int eventIndex, int rawTick, int encodedBytes, int formatByte) {
        byte[] payload = new byte[9 + encodedBytes];
        payload[0] = 0x71;
        payload[1] = (byte) 0x84;
        payload[2] = 0x00;
        payload[3] = (byte) formatByte;
        payload[4] = 0x00;
        payload[5] = 0x00;
        payload[6] = 0x00;
        payload[7] = 0x00;
        payload[8] = (byte) encodedBytes;
        for (int i = 0; i < encodedBytes; i++) {
            payload[9 + i] = (byte) (i * 13 + 7);
        }
        return new MachineDependentEvent(0, eventIndex, 0, rawTick, 0xFF, payload);
    }

    private static int le16(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8);
    }

    private static int le32(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF)
                | ((bytes[offset + 1] & 0xFF) << 8)
                | ((bytes[offset + 2] & 0xFF) << 16)
                | ((bytes[offset + 3] & 0xFF) << 24);
    }

    private static void text(String label, byte[] bytes, int offset, String expected) {
        for (int i = 0; i < expected.length(); i++) {
            if ((char) (bytes[offset + i] & 0xFF) != expected.charAt(i)) {
                fail(label, "unexpected text at byte " + (offset + i));
            }
        }
    }

    private static void pathName(String label, String expected, Path actual) {
        if (actual == null || !expected.equals(actual.getFileName().toString())) {
            fail(label, "expected " + expected + ", got " + actual);
        }
    }

    private static void expectRejected(String label, CheckedAction action) throws Exception {
        try {
            action.run();
            fail(label, "expected export rejection");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    private static void deleteTree(Path root) throws Exception {
        if (root == null || !Files.exists(root)) return;
        List<Path> paths = new ArrayList<Path>();
        java.util.stream.Stream<Path> stream = Files.walk(root);
        try {
            java.util.Iterator<Path> iterator = stream.iterator();
            while (iterator.hasNext()) paths.add(iterator.next());
        } finally {
            stream.close();
        }
        Collections.sort(paths, Collections.reverseOrder());
        for (Path path : paths) Files.deleteIfExists(path);
    }

    private static void eq(String label, int expected, int actual) {
        if (expected != actual) fail(label, "expected " + expected + ", got " + actual);
    }


    private static void eqLong(String label, long expected, long actual) {
        if (expected != actual) fail(label, "expected " + expected + ", got " + actual);
    }

    private static void eqPath(String label, Path expected, Path actual) {
        if (actual == null || !expected.equals(actual)) fail(label, "expected " + expected + ", got " + actual);
    }

    private static void yes(String label, boolean value) {
        if (!value) fail(label, "expected true");
    }

    private static void no(String label, boolean value) {
        if (value) fail(label, "expected false");
    }

    private static void isNull(String label, Object value) {
        if (value != null) fail(label, "expected null, got " + value);
    }

    private static void fail(String label, String detail) {
        throw new AssertionError(label + ": " + detail);
    }

    private interface CheckedAction {
        void run() throws Exception;
    }

    private static final class Compiled {
        final NativeProgram program;
        final MidiPlan midi;

        Compiled(NativeProgram program, MidiPlan midi) {
            this.program = program;
            this.midi = midi;
        }
    }
}
