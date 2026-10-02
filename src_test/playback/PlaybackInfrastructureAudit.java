package playback;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.sound.midi.MidiMessage;
import javax.sound.midi.Receiver;
import javax.sound.midi.ShortMessage;


/** Regression audit for pure MIDI playback infrastructure behavior. */
public final class PlaybackInfrastructureAudit {
    private PlaybackInfrastructureAudit() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length >= 2 && "--fake-fluid-synth".equals(args[0])) {
            runFakeFluidSynth(args);
            return;
        }
        auditMasterVolumeScaling();
        auditPcmMasterVolumeScaling();
        auditFluidSynthProtocol();
        auditFluidSynthProcessBuffers();
        System.out.println("PlaybackInfrastructureAudit: PASS");
    }

    private static void auditMasterVolumeScaling() throws Exception {
        RecordingReceiver raw = new RecordingReceiver();
        MasterVolume master = new MasterVolume(64);
        MasterVolumeReceiver receiver = new MasterVolumeReceiver(raw, master.getValue());
        master.attach(receiver);
        raw.clear();

        receiver.send(shortMessage(ShortMessage.CONTROL_CHANGE, 2, 7, 100), 77L);
        ShortMessage scaled = raw.lastShortMessage();
        eq("scaled command", ShortMessage.CONTROL_CHANGE, scaled.getCommand());
        eq("scaled channel", 2, scaled.getChannel());
        eq("scaled controller", 7, scaled.getData1());
        eq("scaled value", MasterVolume.scaleChannelVolume(100, 64), scaled.getData2());
        eqLong("scaled timestamp", 77L, raw.lastTimestamp);

        raw.clear();
        master.setValue(127);
        eq("master refresh channel count", 16, raw.messages.size());
        ShortMessage channelTwo = raw.shortMessageAt(2);
        eq("master refresh channel", 2, channelTwo.getChannel());
        eq("master refresh value", 100, channelTwo.getData2());

        master.detach(receiver);
        receiver.close();
    }


    private static void auditPcmMasterVolumeScaling() {
        if (!MasterVolumeTarget.class.isAssignableFrom(PcmPlaybackParticipant.class)) {
            throw new AssertionError("PCM participant is not attached to shared master-volume contract");
        }
        eq("PCM full positive", 12000, PcmPlaybackParticipant.scalePcmSample(12000, 127));
        eq("PCM full negative", -12000, PcmPlaybackParticipant.scalePcmSample(-12000, 127));
        eq("PCM mute positive", 0, PcmPlaybackParticipant.scalePcmSample(12000, 0));
        eq("PCM mute negative", 0, PcmPlaybackParticipant.scalePcmSample(-12000, 0));
        eq("PCM half positive", 6047, PcmPlaybackParticipant.scalePcmSample(12000, 64));
        eq("PCM half negative", -6047, PcmPlaybackParticipant.scalePcmSample(-12000, 64));
        eq("PCM minimum full", Short.MIN_VALUE,
                PcmPlaybackParticipant.scalePcmSample(Short.MIN_VALUE, 127));
        eq("PCM maximum full", Short.MAX_VALUE,
                PcmPlaybackParticipant.scalePcmSample(Short.MAX_VALUE, 127));

        byte[] stereo = pcm16le(12000, -12000, Short.MAX_VALUE, Short.MIN_VALUE);
        PcmPlaybackParticipant.applyMasterVolume(stereo, 0);
        for (int offset = 0; offset < stereo.length; offset += 2) {
            eq("PCM mute byte pair " + offset, 0, pcm16leAt(stereo, offset));
        }

        RecordingVolumeTarget target = new RecordingVolumeTarget();
        MasterVolume master = new MasterVolume(96);
        master.attach(target);
        eq("shared target initial volume", 96, target.value);
        master.setValue(31);
        eq("shared target live volume", 31, target.value);
        master.detach(target);
    }

    private static byte[] pcm16le(int... samples) {
        byte[] bytes = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            int sample = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, samples[i]));
            bytes[i * 2] = (byte) (sample & 0xFF);
            bytes[i * 2 + 1] = (byte) ((sample >>> 8) & 0xFF);
        }
        return bytes;
    }

    private static int pcm16leAt(byte[] bytes, int offset) {
        return (short) ((bytes[offset] & 0xFF) | (bytes[offset + 1] << 8));
    }

    private static final class RecordingVolumeTarget implements MasterVolumeTarget {
        int value = -1;

        @Override
        public void setMasterVolume(int value) {
            this.value = value;
        }
    }

    private static void auditFluidSynthProtocol() throws Exception {
        eqText("note on", "noteon 3 60 90",
                FluidSynthBackend.commandFor(shortMessage(ShortMessage.NOTE_ON, 3, 60, 90)));
        eqText("zero velocity note on", "noteoff 3 60",
                FluidSynthBackend.commandFor(shortMessage(ShortMessage.NOTE_ON, 3, 60, 0)));
        eqText("note off", "noteoff 4 61",
                FluidSynthBackend.commandFor(shortMessage(ShortMessage.NOTE_OFF, 4, 61, 12)));
        eqText("control", "cc 5 10 64",
                FluidSynthBackend.commandFor(shortMessage(ShortMessage.CONTROL_CHANGE, 5, 10, 64)));
        eqText("program", "prog 6 12",
                FluidSynthBackend.commandFor(shortMessage(ShortMessage.PROGRAM_CHANGE, 6, 12, 0)));
        eqText("bend", "pitch_bend 7 8193",
                FluidSynthBackend.commandFor(shortMessage(ShortMessage.PITCH_BEND, 7, 1, 64)));
        eqText("gain zero", "gain 0.0000", FluidSynthBackend.gainCommand(0));
        eqText("gain full", "gain 0.2000", FluidSynthBackend.gainCommand(127));
    }

    private static ShortMessage shortMessage(int command, int channel, int data1, int data2) throws Exception {
        ShortMessage message = new ShortMessage();
        message.setMessage(command, channel, data1, data2);
        return message;
    }

    private static void auditFluidSynthProcessBuffers() throws Exception {
        Path directory = Files.createTempDirectory("mld-fluid-buffers-");
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Path launcher = directory.resolve(windows ? "synth.cmd" : "synth.sh");
        Path record = directory.resolve("arguments.txt");
        Path soundFont = directory.resolve("Sound Font.sf2");
        String java = Paths.get(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java").toString();
        String classpath = System.getProperty("java.class.path");
        String script = windows
                ? "@echo off\r\n\"" + java + "\" -cp \"" + classpath + "\" playback.PlaybackInfrastructureAudit --fake-fluid-synth \"" + record + "\" %*\r\n"
                : "#!/bin/sh\nexec " + shellQuote(java) + " -cp " + shellQuote(classpath)
                        + " playback.PlaybackInfrastructureAudit --fake-fluid-synth " + shellQuote(record.toString()) + " \"$@\"\n";
        try {
            Files.write(launcher, script.getBytes(StandardCharsets.UTF_8));
            if (!windows && !launcher.toFile().setExecutable(true)) {
                throw new AssertionError("Cannot execute fake FluidSynth launcher");
            }
            Files.write(soundFont, new byte[0]);
            Receiver receiver = FluidSynthBackend.openReceiver(new MidiOutput(
                    "test", MidiOutput.Backend.FLUIDSYNTH, null, launcher.toString(), soundFont));
            try {
                receiver.send(shortMessage(ShortMessage.NOTE_ON, 0, 60, 90), -1L);
            } finally {
                receiver.close();
            }
            List<String> lines = Files.readAllLines(record, StandardCharsets.UTF_8);
            int periodSize = optionValue(lines, "-z", 64);
            int periods = optionValue(lines, "-c", 16);
            long periodMicros = periodSize * 1000000L / 44100L;
            long bufferMicros = periodMicros * periods;
            if (periodMicros < 10000L || bufferMicros < 40000L || bufferMicros > 200000L) {
                throw new AssertionError("FluidSynth output lacks scheduling margin: period="
                        + periodMicros + "us, buffer=" + bufferMicros + "us");
            }
            if (!lines.contains(soundFont.toString()) || !lines.contains("noteon 0 60 90")
                    || !lines.contains("quit")) {
                throw new AssertionError("FluidSynth launch/send/close lifecycle lost arguments or commands");
            }
        } finally {
            Files.deleteIfExists(record);
            Files.deleteIfExists(launcher);
            Files.deleteIfExists(soundFont);
            Files.deleteIfExists(directory);
        }
    }

    private static int optionValue(List<String> arguments, String option, int defaultValue) {
        int index = arguments.indexOf(option);
        return index < 0 ? defaultValue : Integer.parseInt(arguments.get(index + 1));
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static void runFakeFluidSynth(String[] args) throws Exception {
        try (PrintWriter record = new PrintWriter(Files.newBufferedWriter(Paths.get(args[1]), StandardCharsets.UTF_8));
                BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            for (int index = 2; index < args.length; index++) record.println(args[index]);
            String line;
            while ((line = input.readLine()) != null) {
                record.println(line);
                if ("quit".equals(line)) break;
            }
        }
    }

    private static void eq(String label, int expected, int actual) {
        if (expected != actual) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }

    private static void eqLong(String label, long expected, long actual) {
        if (expected != actual) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }

    private static void eqDouble(String label, double expected, double actual) {
        if (Math.abs(expected - actual) > 0.000001) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }

    private static void eqText(String label, String expected, String actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }

    private static final class RecordingReceiver implements Receiver {
        final List<MidiMessage> messages = new ArrayList<MidiMessage>();
        long lastTimestamp;

        @Override
        public void send(MidiMessage message, long timeStamp) {
            messages.add((MidiMessage) message.clone());
            lastTimestamp = timeStamp;
        }

        @Override
        public void close() {
        }

        void clear() {
            messages.clear();
            lastTimestamp = 0L;
        }

        ShortMessage lastShortMessage() {
            return shortMessageAt(messages.size() - 1);
        }

        ShortMessage shortMessageAt(int index) {
            return (ShortMessage) messages.get(index);
        }
    }
}
