package playback;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.Receiver;
import javax.sound.midi.ShortMessage;

/** FluidSynth command discovery and process-backed MIDI receiver integration. */
final class FluidSynthBackend {
    private FluidSynthBackend() {
    }

    static MidiOutput discoverOutput() {
        String command = discoverCommand();
        if (command == null) {
            return null;
        }
        Path soundFont = configuredSoundFont();
        String displayName = soundFont == null
                ? "FluidSynth..."
                : "FluidSynth - " + soundFont.getFileName();
        return new MidiOutput(displayName, MidiOutput.Backend.FLUIDSYNTH, null, command, soundFont);
    }

    static Receiver openReceiver(MidiOutput output) throws MidiUnavailableException {
        if (output.soundFont == null || !Files.isRegularFile(output.soundFont)) {
            throw new MidiUnavailableException("Choose an SF2 or SF3 SoundFont for FluidSynth.");
        }
        try {
            return new FluidSynthReceiver(output.command, output.soundFont);
        } catch (IOException e) {
            MidiUnavailableException unavailable = new MidiUnavailableException(
                    "FluidSynth could not start: " + e.getMessage());
            unavailable.initCause(e);
            throw unavailable;
        }
    }

    static boolean isSoundFont(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".sf2") || name.endsWith(".sf3");
    }

    static String commandFor(MidiMessage message) {
        if (!(message instanceof ShortMessage)) {
            return null;
        }
        ShortMessage source = (ShortMessage) message;
        int channel = source.getChannel();
        switch (source.getCommand()) {
            case ShortMessage.NOTE_ON:
                if (source.getData2() == 0) {
                    return "noteoff " + channel + " " + source.getData1();
                }
                return "noteon " + channel + " " + source.getData1() + " " + source.getData2();
            case ShortMessage.NOTE_OFF:
                return "noteoff " + channel + " " + source.getData1();
            case ShortMessage.CONTROL_CHANGE:
                return "cc " + channel + " " + source.getData1() + " " + source.getData2();
            case ShortMessage.PROGRAM_CHANGE:
                return "prog " + channel + " " + source.getData1();
            case ShortMessage.PITCH_BEND:
                int bend = (source.getData2() << 7) | source.getData1();
                return "pitch_bend " + channel + " " + bend;
            default:
                return null;
        }
    }

    static String gainCommand(int masterVolume) {
        double gain = MasterVolume.clamp(masterVolume) * 0.2 / 127.0;
        return String.format(Locale.ROOT, "gain %.4f", Double.valueOf(gain));
    }

    private static String discoverCommand() {
        String configured = System.getProperty("mld.fluidsynth.command");
        if (configured == null || configured.trim().isEmpty()) {
            configured = System.getenv("FLUIDSYNTH");
        }
        List<String> candidates = new ArrayList<String>();
        if (configured != null && !configured.trim().isEmpty()) {
            candidates.add(configured.trim());
        }
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            candidates.add("fluidsynth.exe");
        }
        candidates.add("fluidsynth");

        for (String candidate : candidates) {
            if (probeCommand(candidate)) {
                return candidate;
            }
            if (Thread.currentThread().isInterrupted()) {
                return null;
            }
        }
        return null;
    }

    private static boolean probeCommand(String candidate) {
        Process probe = null;
        try {
            probe = new ProcessBuilder(candidate, "--version").redirectErrorStream(true).start();
            return probe.waitFor(2L, TimeUnit.SECONDS) && probe.exitValue() == 0;
        } catch (IOException ignored) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            closeProbe(probe);
        }
    }

    private static void closeProbe(Process probe) {
        if (probe == null) {
            return;
        }
        if (probe.isAlive()) {
            probe.destroyForcibly();
            try {
                probe.waitFor(1L, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        try {
            probe.getInputStream().close();
            probe.getErrorStream().close();
            probe.getOutputStream().close();
        } catch (IOException ignored) {
            // The short-lived probe is already stopped.
        }
    }

    private static Path configuredSoundFont() {
        String[] configured = {
            System.getProperty("mld.fluidsynth.soundfont"),
            System.getenv("FLUIDSYNTH_SOUNDFONT"),
            System.getenv("SOUNDFONT")
        };
        for (String value : configured) {
            if (value == null || value.trim().isEmpty()) {
                continue;
            }
            try {
                Path candidate = Paths.get(value.trim()).toAbsolutePath().normalize();
                if (Files.isRegularFile(candidate) && isSoundFont(candidate)) {
                    return candidate;
                }
            } catch (RuntimeException ignored) {
                // Optional configuration may be malformed; the GUI can request a file later.
            }
        }
        return null;
    }

    private static final class FluidSynthReceiver
            implements Receiver, MasterVolumeTarget, PlaybackReceiverHealth {
        private final Process process;
        private final BufferedWriter writer;
        private volatile IOException failure;
        private volatile String diagnostic;
        private volatile boolean closed;

        FluidSynthReceiver(String command, Path soundFont) throws IOException {
            ProcessBuilder builder = new ProcessBuilder(
                    command,
                    "-n",
                    "-q",
                    soundFont.toAbsolutePath().normalize().toString());
            builder.redirectErrorStream(true);
            process = builder.start();
            writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            startDiagnosticDrainer();
        }

        private void startDiagnosticDrainer() {
            Thread outputDrainer = new Thread(new Runnable() {
                @Override
                public void run() {
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (!line.trim().isEmpty()) {
                                diagnostic = line.trim();
                            }
                        }
                    } catch (IOException ignored) {
                        // Closing the backend also closes its diagnostic stream.
                    }
                }
            }, "FluidSynth diagnostics");
            outputDrainer.setDaemon(true);
            outputDrainer.start();
        }

        @Override
        public synchronized void send(MidiMessage message, long timeStamp) {
            if (closed || failure != null) {
                return;
            }
            String command = commandFor(message);
            if (command != null) {
                writeCommand(command);
            }
        }

        @Override
        public synchronized void setMasterVolume(int masterVolume) {
            if (!closed && failure == null) {
                writeCommand(gainCommand(masterVolume));
            }
        }

        @Override
        public void checkHealth() throws MidiUnavailableException {
            IOException problem = failure;
            if (problem == null && !closed && !process.isAlive()) {
                String detail = diagnostic == null ? "" : ": " + diagnostic;
                problem = new IOException("process exited with code " + process.exitValue() + detail);
                failure = problem;
            }
            if (problem != null) {
                MidiUnavailableException unavailable = new MidiUnavailableException(
                        "FluidSynth backend failed: " + problem.getMessage());
                unavailable.initCause(problem);
                throw unavailable;
            }
        }

        private void writeCommand(String command) {
            try {
                writer.write(command);
                writer.newLine();
                writer.flush();
            } catch (IOException e) {
                failure = e;
                process.destroy();
            }
        }

        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                writer.write("reset");
                writer.newLine();
                writer.write("quit");
                writer.newLine();
                writer.flush();
            } catch (IOException ignored) {
                // The process may already have exited after an audio-driver failure.
            } finally {
                try {
                    writer.close();
                } catch (IOException ignored) {
                    // The process owns the other end of the pipe.
                }
            }
            stopProcess();
        }

        private void stopProcess() {
            try {
                if (!process.waitFor(2L, TimeUnit.SECONDS)) {
                    process.destroy();
                    if (!process.waitFor(1L, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }
}
