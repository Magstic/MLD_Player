package mld.api;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;

/** Verifies instrument mapping through the public API and serialized MIDI output. */
public final class InstrumentMappingAudit {
    private static final Path OUTPUT = Paths.get("build/instrument-mapping");

    public static void main(String[] args) throws Exception {
        Files.createDirectories(OUTPUT);
        StringBuilder report = new StringBuilder("fixture,notes,status\n");
        verifyPatchEvents();
        int fixtureNotes = verifyMappingCases(report);
        int[] lowBankPrograms = {0, 9, 16, 24, 13, 74};
        for (int mode = 0; mode <= 1; mode++) {
            ByteArrayOutputStream events = new ByteArrayOutputStream();
            command(events, 0, 0xBA, mode);
            List<Integer> expected = new ArrayList<Integer>();
            for (int bank = 0; bank < 64; bank++) {
                for (int program = 0; program < 64; program++) {
                    int melodic = bank < 2
                            ? (program < lowBankPrograms.length ? lowBankPrograms[program] : 0)
                            : program + (bank % 2) * 64;
                    for (int order = 0; order < 2; order++) {
                        command(events, 2, 0xE1, 0);
                        command(events, 0, 0xE0, 0);
                        if (order == 0) {
                            command(events, 0, 0xE1, bank);
                            command(events, 0, 0xE0, program);
                        } else {
                            command(events, 0, 0xE0, program);
                            command(events, 0, 0xE1, bank);
                        }
                        note(events);
                        expected.add(mode == 1 ? 0 : melodic);
                    }
                }
            }
            fixtureNotes += verify("matrix-mode" + mode, events, expected, mode == 1 ? 9 : 0,
                    mode == 1 ? 35 : 45, report);
        }
        ByteArrayOutputStream events = new ByteArrayOutputStream();
        List<Integer> expected = new ArrayList<Integer>();
        note(events); expected.add(0); // No patch commands.
        command(events, 2, 0xE1, 3);
        note(events); expected.add(64); // Bank alone must update the initial program.
        command(events, 2, 0xE0, 5);
        note(events); expected.add(69);
        command(events, 2, 0xE1, 0);
        note(events); expected.add(74); // E1 alone leaves the odd-bank page.
        command(events, 2, 0xE0, 6);
        note(events); expected.add(0); // Low-bank out-of-table value.
        command(events, 2, 0xE0, 1);
        command(events, 0, 0xE0, 1);
        note(events); expected.add(9);
        command(events, 2, 0xBF, 0);
        note(events); expected.add(0);
        fixtureNotes += verify("transitions", events, expected, 0, 45, report);
        Files.write(OUTPUT.resolve("summary.csv"), report.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("InstrumentMappingAudit: PASS (" + fixtureNotes
                + " notes; " + OUTPUT + ")");
    }

    private static int verifyMappingCases(StringBuilder report) throws Exception {
        int count = 0;
        for (int mode = 0; mode <= 1; mode++) {
            ByteArrayOutputStream events = new ByteArrayOutputStream();
            command(events, 0, 0xBA, mode);
            command(events, 0, 0xE1, 3);
            command(events, 0, 0xE0, 5);
            note(events);
            command(events, 2, 0xE1, 0);
            command(events, 0, 0xE0, 2);
            note(events);
            count += verifyNotes(mode == 0 ? "melodic" : "drum", events,
                    mode == 0 ? Arrays.asList("0:45:69", "0:45:16")
                            : Arrays.asList("9:35:0", "9:35:0"), report);
        }

        ByteArrayOutputStream events = new ByteArrayOutputStream();
        command(events, 0, 0xE1, 3);
        note(events);
        command(events, 2, 0xE1, 0);
        note(events);
        count += verifyNotes("bank-only", events, Arrays.asList("0:45:64", "0:45:0"), report);

        events = new ByteArrayOutputStream();
        command(events, 0, 0xE0, 5);
        note(events);
        command(events, 2, 0xBA, 1);
        note(events);
        command(events, 2, 0xBA, 0);
        note(events);
        count += verifyNotes("mode-roundtrip", events,
                Arrays.asList("9:45:0", "9:35:0", "9:45:0"), report);

        events = new ByteArrayOutputStream();
        command(events, 0, 0xBA, 1);
        command(events, 0, 0xE0, 5);
        command(events, 0, 0xBA, 0);
        note(events);
        count += verifyNotes("mode-without-drum-note", events, Arrays.asList("0:45:74"), report);

        events = new ByteArrayOutputStream();
        command(events, 0, 0xE0, 5);
        note(events);
        command(events, 2, 0xBA, 1);
        note(events);
        count += verifyNotes("patch-before-drum", events,
                Arrays.asList("9:45:0", "9:35:0"), report);

        events = new ByteArrayOutputStream();
        command(events, 0, 0xE5, 9);
        note(events);
        count += verifyNotes("default-channel9", events, Arrays.asList("9:35:0"), report);

        events = new ByteArrayOutputStream();
        command(events, 0, 0xBA, 1);
        note(events);
        command(events, 2, 0xE5, 1);
        command(events, 0, 0xBA, 9);
        command(events, 0, 0xE0, 5);
        note(events);
        count += verifyNotes("two-drums", events, Arrays.asList("9:35:0", "9:35:0"), report);
        return count;
    }

    private static void verifyPatchEvents() throws Exception {
        ByteArrayOutputStream events = new ByteArrayOutputStream();
        command(events, 0, 0xE1, 3); // E1 before E0 immediately selects program 64.
        command(events, 1, 0xE0, 5); // Program 69.
        command(events, 1, 0xE1, 5); // Different native bank, same final program.
        command(events, 0, 0xE0, 5); // Repeated program.
        command(events, 1, 0xE1, 0); // Program 74.
        command(events, 1, 0xBA, 2);
        command(events, 1, 0xE0, 1); // Unsupported mode suppresses MIDI patch output.
        command(events, 1, 0xE1, 3);
        command(events, 1, 0xBA, 0);
        command(events, 1, 0xE0, 1); // Returning to mode 0 selects program 65.
        command(events, 1, 0xBF, 0);
        command(events, 1, 0xE0, 1); // Reset bank 0 selects program 9.
        command(events, 0, 0xE0, 0x41); // Same program on another logical channel.
        command(events, 1, 0xE0, 1);
        command(events, 1, 0xBF, 0);
        command(events, 1, 0xE0, 1); // Reset followed by the same program.
        note(events);
        command(events, 1, 0xDF, 0);
        verifyPatchEvents(events, Arrays.asList("0:0:64", "0:40:69", "0:80:69", "0:80:69",
                "0:120:74", "0:320:65", "0:400:9", "0:440:9", "0:520:9", "0:520:9", "1:400:9"));

        events = new ByteArrayOutputStream();
        for (int i = 0; i < 3; i++) {
            note(events);
            command(events, 2, 0xDE, 0);
        }
        verifyPatchEvents(events, Arrays.asList("0:0:0", "0:80:0", "0:160:0"));

        events = new ByteArrayOutputStream();
        command(events, 0, 0xBA, 1);
        command(events, 0, 0xE0, 5);
        command(events, 0, 0xE1, 2);
        note(events);
        command(events, 2, 0xE1, 4);
        note(events);
        command(events, 2, 0xDF, 0);
        verifyPatchEvents(events, Arrays.asList("9:0:0", "9:0:0", "9:0:0", "9:0:0", "9:80:0", "9:80:0"));
    }

    private static void verifyPatchEvents(ByteArrayOutputStream events, List<String> expected) throws Exception {
        Sequence projected = MldConverter.convert(mldBytes(events)).createMidiSequence();
        ByteArrayOutputStream midi = new ByteArrayOutputStream();
        MidiSystem.write(projected, 1, midi);
        Sequence sequence = MidiSystem.getSequence(new ByteArrayInputStream(midi.toByteArray()));
        List<String> actual = new ArrayList<String>();
        for (Track track : sequence.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                MidiEvent event = track.get(i);
                if (!(event.getMessage() instanceof ShortMessage)) continue;
                ShortMessage message = (ShortMessage) event.getMessage();
                if (message.getCommand() == ShortMessage.PROGRAM_CHANGE) {
                    actual.add(message.getChannel() + ":" + event.getTick() + ":" + message.getData1());
                }
            }
        }
        if (!expected.equals(actual)) {
            throw new AssertionError("Projected Program Change events: expected " + expected + ", got " + actual);
        }
        notePrograms(sequence); // Also rejects Bank Select.
    }

    private static List<String> notePrograms(Sequence sequence) {
        List<MidiEvent> events = new ArrayList<MidiEvent>();
        for (Track track : sequence.getTracks()) {
            for (int i = 0; i < track.size(); i++) events.add(track.get(i));
        }
        Collections.sort(events, new Comparator<MidiEvent>() {
            public int compare(MidiEvent a, MidiEvent b) { return Long.compare(a.getTick(), b.getTick()); }
        });
        int[] programs = new int[16];
        List<String> result = new ArrayList<String>();
        for (MidiEvent event : events) {
            if (!(event.getMessage() instanceof ShortMessage)) continue;
            ShortMessage message = (ShortMessage) event.getMessage();
            int ch = message.getChannel();
            if (message.getCommand() == ShortMessage.PROGRAM_CHANGE) programs[ch] = message.getData1();
            if (message.getCommand() == ShortMessage.CONTROL_CHANGE
                    && (message.getData1() == 0 || message.getData1() == 32)) {
                throw new AssertionError("Unexpected MIDI bank select");
            }
            if (message.getCommand() == ShortMessage.NOTE_ON && message.getData2() > 0) {
                result.add(ch + ":" + message.getData1() + ":" + programs[ch]);
            }
        }
        return result;
    }

    private static void command(ByteArrayOutputStream out, int delta, int command, int value) {
        out.write(delta); out.write(0xFF); out.write(command); out.write(value);
    }

    private static void note(ByteArrayOutputStream out) {
        out.write(0); out.write(0); out.write(1);
    }

    private static int verify(String name, ByteArrayOutputStream events,
            List<Integer> expected, int channel, int pitch, StringBuilder report) throws Exception {
        List<String> notes = new ArrayList<String>();
        for (int program : expected) notes.add(channel + ":" + pitch + ":" + program);
        return verifyNotes(name, events, notes, report);
    }

    private static int verifyNotes(String name, ByteArrayOutputStream events,
            List<String> expected, StringBuilder report) throws Exception {
        command(events, 2, 0xDF, 0);
        byte[] mld = mldBytes(events);
        Files.write(OUTPUT.resolve(name + ".mld"), mld);
        Sequence sequence = MldConverter.convert(mld).createMidiSequence();
        ByteArrayOutputStream midi = new ByteArrayOutputStream();
        MidiSystem.write(sequence, 1, midi);
        Files.write(OUTPUT.resolve(name + ".mid"), midi.toByteArray());
        Sequence reread = MidiSystem.getSequence(new ByteArrayInputStream(midi.toByteArray()));
        List<String> actual = notePrograms(reread);
        if (!expected.equals(actual)) {
            throw new AssertionError(name + " instrument mapping: expected " + expected + ", got " + actual);
        }
        report.append(name).append(',').append(actual.size()).append(",PASS\n");
        return actual.size();
    }

    private static byte[] mldBytes(ByteArrayOutputStream events) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeBytes("melo");
        out.writeInt(21 + events.size());
        out.writeShort(0);
        out.write(new byte[] {1, 1, 1});
        out.writeBytes("note"); out.writeShort(2); out.writeShort(0);
        out.writeBytes("trac"); out.writeInt(events.size()); out.write(events.toByteArray());
        return bytes.toByteArray();
    }
}
