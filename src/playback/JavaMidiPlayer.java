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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.MidiDevice;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.Receiver;
import javax.sound.midi.Sequencer;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Synthesizer;
import javax.sound.midi.Transmitter;

public final class JavaMidiPlayer {
    private static final String BOX_BORDER = "************************************************************";
    private static final MidiOutput SYSTEM_GM = new MidiOutput(
            "System GM", OutputBackend.JAVA_SYNTH, null, null, null);
    private static final MidiOutput VIRTUAL_MIDI_SYNTH = new MidiOutput(
            "VirtualMIDISynth", OutputBackend.MIDI_DEVICE, "VirtualMIDISynth", null, null);
    private static final MidiOutput FLUID_SYNTH = createFluidSynthOutput();
    private static final MidiOutput[] MIDI_OUTPUTS = {SYSTEM_GM, VIRTUAL_MIDI_SYNTH, FLUID_SYNTH};

    public static List<MidiOutput> availableMidiOutputs() {
        List<MidiOutput> outputs = new ArrayList<MidiOutput>();
        for (MidiOutput output : MIDI_OUTPUTS) {
            if (output != null
                    && (output.backend != OutputBackend.MIDI_DEVICE
                            || findReceiverDevice(output.deviceNameFragment) != null)) {
                outputs.add(output);
            }
        }
        return Collections.unmodifiableList(outputs);
    }

    public boolean play(PlaybackSequenceBuilder.BuiltSequence builtSequence, int loopCount)
            throws Exception {
        ConsoleProgressPrinter progressPrinter = new ConsoleProgressPrinter();
        return playInternal(builtSequence, loopCount, PlaybackMonitor.NONE, progressPrinter, null, null);
    }

    public boolean play(
            PlaybackSequenceBuilder.BuiltSequence builtSequence,
            int loopCount,
            PlaybackMonitor monitor)
            throws Exception {
        return playInternal(builtSequence, loopCount, monitor, null, null, null);
    }

    public boolean play(
            PlaybackSequenceBuilder.BuiltSequence builtSequence,
            int loopCount,
            PlaybackMonitor monitor,
            MidiOutput midiOutput,
            MasterVolume masterVolume)
            throws Exception {
        return playInternal(builtSequence, loopCount, monitor, null, midiOutput, masterVolume);
    }

    private boolean playInternal(
            PlaybackSequenceBuilder.BuiltSequence builtSequence,
            int loopCount,
            PlaybackMonitor monitor,
            ConsoleProgressPrinter consolePrinter,
            MidiOutput midiOutput,
            MasterVolume masterVolume)
            throws Exception {
        Sequencer sequencer = null;
        Synthesizer synthesizer = null;
        MidiDevice outputDevice = null;
        Transmitter transmitter = null;
        Receiver receiver = null;
        VolumeReceiver volumeReceiver = null;
        PlaybackMonitor safeMonitor = monitor == null ? PlaybackMonitor.NONE : monitor;
        boolean completedNaturally = true;
        boolean paused = false;

        try {
            sequencer = openSequencer();
            transmitter = sequencer.getTransmitter();
            OutputSetup outputSetup = openOutput(midiOutput);
            synthesizer = outputSetup.synthesizer;
            outputDevice = outputSetup.outputDevice;
            FluidSynthReceiver fluidSynthReceiver = outputSetup.receiver instanceof FluidSynthReceiver
                    ? (FluidSynthReceiver) outputSetup.receiver
                    : null;
            if (masterVolume == null) {
                receiver = outputSetup.receiver;
            } else {
                volumeReceiver = new VolumeReceiver(outputSetup.receiver, masterVolume.getValue());
                masterVolume.attach(volumeReceiver);
                receiver = volumeReceiver;
            }
            transmitter.setReceiver(receiver);
            if (fluidSynthReceiver != null) {
                fluidSynthReceiver.throwIfFailed();
            }

            sequencer.setSequence(builtSequence.sequence);
            configureLooping(sequencer, builtSequence, loopCount);
            PlaybackMonitor.Descriptor descriptor = buildDescriptor(
                    builtSequence,
                    loopCount,
                    sequencer,
                    synthesizer,
                    outputDevice,
                    midiOutput);
            if (consolePrinter != null) {
                printPlaybackSection(descriptor);
            }
            safeMonitor.onPlaybackPrepared(descriptor);

            ProgressTracker progressTracker = new ProgressTracker(builtSequence, loopCount);
            PlaybackMonitor.Progress initialProgress = progressTracker.snapshot(0L, false);
            if (consolePrinter != null) {
                consolePrinter.update(initialProgress);
            }
            safeMonitor.onPlaybackProgress(initialProgress);
            sequencer.start();

            while (sequencer.isRunning() || paused) {
                if (fluidSynthReceiver != null) {
                    fluidSynthReceiver.throwIfFailed();
                }
                if (safeMonitor.isPauseRequested()) {
                    if (!paused) {
                        sequencer.stop();
                        paused = true;
                    }
                } else if (paused) {
                    sequencer.start();
                    paused = false;
                }

                long tickPosition = sequencer.getTickPosition();
                PlaybackMonitor.Progress progress = progressTracker.snapshot(tickPosition, false);
                if (consolePrinter != null) {
                    consolePrinter.update(progress);
                }
                safeMonitor.onPlaybackProgress(progress);
                if (safeMonitor.isStopRequested()) {
                    completedNaturally = false;
                    sequencer.stop();
                    break;
                }
                if (!paused && !sequencer.isRunning()) {
                    break;
                }
                Thread.sleep(50L);
            }
            if (fluidSynthReceiver != null) {
                fluidSynthReceiver.throwIfFailed();
            }
            long finalTick = completedNaturally ? finalTickPosition(sequencer, builtSequence) : Math.max(0L, sequencer.getTickPosition());
            PlaybackMonitor.Progress finalProgress = progressTracker.snapshot(finalTick, completedNaturally);
            if (consolePrinter != null) {
                consolePrinter.finish(finalProgress, completedNaturally);
            }
            safeMonitor.onPlaybackProgress(finalProgress);
            return completedNaturally;
        } finally {
            if (sequencer != null && sequencer.isOpen()) {
                sequencer.stop();
            }
            if (transmitter != null) {
                transmitter.close();
            }
            if (masterVolume != null && volumeReceiver != null) {
                masterVolume.detach(volumeReceiver);
            }
            if (receiver != null) {
                receiver.close();
            }
            if (synthesizer != null && synthesizer.isOpen()) {
                synthesizer.close();
            }
            if (outputDevice != null && outputDevice.isOpen()) {
                outputDevice.close();
            }
            if (sequencer != null && sequencer.isOpen()) {
                sequencer.close();
            }
        }
    }

    private Sequencer openSequencer() throws MidiUnavailableException {
        Sequencer sequencer = MidiSystem.getSequencer(false);
        if (sequencer == null) {
            throw new MidiUnavailableException("No MIDI sequencer is available.");
        }
        sequencer.open();
        return sequencer;
    }

    private PlaybackMonitor.Descriptor buildDescriptor(
            PlaybackSequenceBuilder.BuiltSequence builtSequence,
            int loopCount,
            Sequencer sequencer,
            Synthesizer synthesizer,
            MidiDevice outputDevice,
            MidiOutput midiOutput) {
        return new PlaybackMonitor.Descriptor(
                "host MIDI",
                describeLoop(builtSequence, loopCount),
                describeSequencer(sequencer),
                describeOutput(synthesizer, outputDevice, midiOutput));
    }

    private void printPlaybackSection(PlaybackMonitor.Descriptor descriptor) {
        System.out.println("Playback mode: " + descriptor.mode);
        System.out.println("Loop: " + descriptor.loopDescription);
        System.out.println("MIDI backend: " + descriptor.backend);
        System.out.println("MIDI output: " + descriptor.output);
    }

    private String describeSequencer(Sequencer sequencer) {
        if (sequencer == null) {
            return "Java MIDI Sequencer";
        }
        String name = sequencer.getDeviceInfo().getName();
        if (name == null || name.trim().isEmpty()) {
            return "Java MIDI Sequencer";
        }
        if ("Real Time Sequencer".equalsIgnoreCase(name.trim())) {
            return "Java Real Time Sequencer";
        }
        if (name.startsWith("Java ")) {
            return name;
        }
        return "Java " + name;
    }

    private String describeOutput(Synthesizer synthesizer, MidiDevice outputDevice, MidiOutput midiOutput) {
        if (midiOutput != null && midiOutput.backend == OutputBackend.FLUIDSYNTH) {
            return midiOutput.displayName;
        }
        if (outputDevice != null) {
            return outputDevice.getDeviceInfo().getName();
        }
        if (synthesizer != null) {
            return synthesizer.getDeviceInfo().getName();
        }
        return "Default Java MIDI output";
    }

    private String describeLoop(PlaybackSequenceBuilder.BuiltSequence builtSequence, int loopCount) {
        if (builtSequence == null) {
            return "none";
        }
        if (builtSequence.hasLoop && builtSequence.materializedLoopPasses) {
            if (builtSequence.totalLoopPasses <= 1) {
                return "once";
            }
            return builtSequence.totalLoopPasses + " passes";
        }
        if (!builtSequence.hasLoop) {
            if (loopCount < 0) {
                return "infinite";
            }
            if (loopCount == 0) {
                return "once";
            }
            return (loopCount + 1) + " passes";
        }
        if (loopCount >= 0) {
            if (loopCount == 0) {
                return "once";
            }
            return (loopCount + 1) + " passes";
        } else {
            return "infinite";
        }
    }

    private OutputSetup openOutput(MidiOutput midiOutput) throws MidiUnavailableException {
        OutputSetup setup;
        if (midiOutput == null) {
            setup = probePreferredOutput();
        } else if (midiOutput.backend == OutputBackend.JAVA_SYNTH) {
            setup = new OutputSetup(null, null, null);
        } else if (midiOutput.backend == OutputBackend.MIDI_DEVICE) {
            MidiDevice requested = findReceiverDevice(midiOutput.deviceNameFragment);
            if (requested == null) {
                throw new MidiUnavailableException(midiOutput.displayName + " is unavailable.");
            }
            setup = new OutputSetup(null, requested, null);
        } else {
            if (midiOutput.soundFont == null || !Files.isRegularFile(midiOutput.soundFont)) {
                throw new MidiUnavailableException("Choose an SF2 or SF3 SoundFont for FluidSynth.");
            }
            try {
                return new OutputSetup(
                        null,
                        null,
                        new FluidSynthReceiver(midiOutput.command, midiOutput.soundFont));
            } catch (IOException e) {
                MidiUnavailableException unavailable = new MidiUnavailableException(
                        "FluidSynth could not start: " + e.getMessage());
                unavailable.initCause(e);
                throw unavailable;
            }
        }
        if (setup.outputDevice != null) {
            try {
                setup.outputDevice.open();
                setup.receiver = setup.outputDevice.getReceiver();
                return setup;
            } catch (MidiUnavailableException e) {
                if (setup.outputDevice.isOpen()) {
                    setup.outputDevice.close();
                }
                if (midiOutput != null) {
                    throw e;
                }
            }
        }

        Synthesizer synthesizer = MidiSystem.getSynthesizer();
        synthesizer.open();
        return new OutputSetup(synthesizer, null, synthesizer.getReceiver());
    }

    private OutputSetup probePreferredOutput() {
        String devicePreference = System.getProperty("mld.midi.device", "auto");
        if ("default".equalsIgnoreCase(devicePreference) || "gervill".equalsIgnoreCase(devicePreference)) {
            return new OutputSetup(null, null, null);
        }

        if (!"auto".equalsIgnoreCase(devicePreference) && !"virtual".equalsIgnoreCase(devicePreference)) {
            MidiDevice requested = findReceiverDevice(devicePreference);
            if (requested != null) {
                return new OutputSetup(null, requested, null);
            }
        }

        MidiDevice virtualMidiSynth = findReceiverDevice("VirtualMIDISynth");
        if (virtualMidiSynth != null) {
            return new OutputSetup(null, virtualMidiSynth, null);
        }

        return new OutputSetup(null, null, null);
    }

    private static MidiOutput createFluidSynthOutput() {
        String configuredCommand = System.getProperty("mld.fluidsynth.command");
        if (configuredCommand == null || configuredCommand.trim().isEmpty()) {
            configuredCommand = System.getenv("FLUIDSYNTH");
        }

        List<String> candidates = new ArrayList<String>();
        if (configuredCommand != null && !configuredCommand.trim().isEmpty()) {
            candidates.add(configuredCommand.trim());
        }
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            candidates.add("fluidsynth.exe");
        }
        candidates.add("fluidsynth");

        String command = null;
        for (String candidate : candidates) {
            Process probe = null;
            try {
                probe = new ProcessBuilder(candidate, "--version").redirectErrorStream(true).start();
                if (probe.waitFor(2L, TimeUnit.SECONDS) && probe.exitValue() == 0) {
                    command = candidate;
                    break;
                }
            } catch (IOException ignored) {
                // Try the next configured or platform command.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } finally {
                if (probe != null) {
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
            }
        }
        if (command == null) {
            return null;
        }

        Path soundFont = null;
        String[] configuredSoundFonts = {
            System.getProperty("mld.fluidsynth.soundfont"),
            System.getenv("FLUIDSYNTH_SOUNDFONT"),
            System.getenv("SOUNDFONT")
        };
        for (String configuredSoundFont : configuredSoundFonts) {
            if (configuredSoundFont == null || configuredSoundFont.trim().isEmpty()) {
                continue;
            }
            try {
                Path candidate = Paths.get(configuredSoundFont.trim()).toAbsolutePath().normalize();
                if (Files.isRegularFile(candidate) && isSoundFont(candidate)) {
                    soundFont = candidate;
                    break;
                }
            } catch (RuntimeException ignored) {
                // Ignore malformed optional configuration and let the GUI ask for a file.
            }
        }
        String displayName = soundFont == null
                ? "FluidSynth..."
                : "FluidSynth - " + soundFont.getFileName();
        return new MidiOutput(displayName, OutputBackend.FLUIDSYNTH, null, command, soundFont);
    }

    private static MidiDevice findReceiverDevice(String nameFragment) {
        if (nameFragment == null || nameFragment.isEmpty()) {
            return null;
        }
        MidiDevice.Info[] infos = MidiSystem.getMidiDeviceInfo();
        for (MidiDevice.Info info : infos) {
            try {
                MidiDevice device = MidiSystem.getMidiDevice(info);
                if (device instanceof Sequencer) {
                    continue;
                }
                if (device.getMaxReceivers() == 0) {
                    continue;
                }
                String name = info.getName();
                if (name != null && name.toLowerCase().contains(nameFragment.toLowerCase())) {
                    return device;
                }
            } catch (MidiUnavailableException e) {
                // Ignore unavailable devices while scanning.
            }
        }
        return null;
    }

    private static boolean isSoundFont(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".sf2") || name.endsWith(".sf3");
    }

    private long finalTickPosition(Sequencer sequencer, PlaybackSequenceBuilder.BuiltSequence builtSequence) {
        if (sequencer == null) {
            return builtSequence.contentEndTick;
        }
        return Math.max(sequencer.getTickPosition(), builtSequence.contentEndTick);
    }

    private void configureLooping(Sequencer sequencer, PlaybackSequenceBuilder.BuiltSequence builtSequence, int loopCount) {
        if (loopCount == 0 || builtSequence.materializedLoopPasses) {
            sequencer.setLoopCount(0);
            return;
        }

        if (builtSequence.hasLoop) {
            sequencer.setLoopStartPoint(builtSequence.loopStartTick);
            sequencer.setLoopEndPoint(Math.max(builtSequence.loopStartTick, builtSequence.loopEndTick));
        } else {
            sequencer.setLoopStartPoint(0L);
            sequencer.setLoopEndPoint(Math.max(0L, builtSequence.contentEndTick));
        }
        sequencer.setLoopCount(loopCount < 0 ? Sequencer.LOOP_CONTINUOUSLY : loopCount);
    }

    public static final class MidiOutput {
        private final String displayName;
        private final OutputBackend backend;
        private final String deviceNameFragment;
        private final String command;
        private final Path soundFont;

        private MidiOutput(
                String displayName,
                OutputBackend backend,
                String deviceNameFragment,
                String command,
                Path soundFont) {
            this.displayName = displayName;
            this.backend = backend;
            this.deviceNameFragment = deviceNameFragment;
            this.command = command;
            this.soundFont = soundFont;
        }

        public boolean needsSoundFont() {
            return backend == OutputBackend.FLUIDSYNTH && soundFont == null;
        }

        public MidiOutput withSoundFont(Path path) {
            if (backend != OutputBackend.FLUIDSYNTH) {
                throw new IllegalStateException("Only FluidSynth uses an external SoundFont.");
            }
            if (path == null) {
                throw new IllegalArgumentException("No SoundFont was selected.");
            }
            Path normalized = path.toAbsolutePath().normalize();
            if (!Files.isRegularFile(normalized) || !isSoundFont(normalized)) {
                throw new IllegalArgumentException("Choose an existing SF2 or SF3 SoundFont.");
            }
            return new MidiOutput(
                    "FluidSynth - " + normalized.getFileName(),
                    backend,
                    null,
                    command,
                    normalized);
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    private enum OutputBackend {
        JAVA_SYNTH,
        MIDI_DEVICE,
        FLUIDSYNTH
    }

    public static final class MasterVolume {
        private int value;
        private final List<VolumeReceiver> receivers = new ArrayList<VolumeReceiver>();

        public MasterVolume(int value) {
            this.value = clampVolume(value);
        }

        public synchronized int getValue() {
            return value;
        }

        public synchronized void setValue(int value) {
            this.value = clampVolume(value);
            for (VolumeReceiver receiver : receivers) {
                receiver.setMasterVolume(this.value);
            }
        }

        private synchronized void attach(VolumeReceiver receiver) {
            if (!receivers.contains(receiver)) {
                receivers.add(receiver);
            }
            receiver.setMasterVolume(value);
        }

        private synchronized void detach(VolumeReceiver receiver) {
            receivers.remove(receiver);
        }
    }

    static int scaleVolume(int channelVolume, int masterVolume) {
        int source = clampVolume(channelVolume);
        int master = clampVolume(masterVolume);
        return (source * master + 63) / 127;
    }

    private static int clampVolume(int value) {
        return Math.max(0, Math.min(127, value));
    }

    private static final class VolumeReceiver implements Receiver {
        private final Receiver delegate;
        private final int[] channelVolumes = new int[16];
        private int masterVolume = -1;
        private boolean closed;

        VolumeReceiver(Receiver delegate, int masterVolume) {
            this.delegate = delegate;
            Arrays.fill(channelVolumes, 100);
            setMasterVolume(masterVolume);
        }

        @Override
        public synchronized void send(MidiMessage message, long timeStamp) {
            if (closed) {
                return;
            }
            if (message instanceof ShortMessage) {
                ShortMessage source = (ShortMessage) message;
                if (source.getCommand() == ShortMessage.CONTROL_CHANGE && source.getData1() == 7) {
                    int channel = source.getChannel();
                    channelVolumes[channel] = source.getData2();
                    if (delegate instanceof FluidSynthReceiver) {
                        delegate.send(message, timeStamp);
                    } else {
                        sendChannelVolume(channel, timeStamp);
                    }
                    return;
                }
            }
            delegate.send(message, timeStamp);
        }

        synchronized void setMasterVolume(int value) {
            int clamped = clampVolume(value);
            if (closed || clamped == masterVolume) {
                return;
            }
            masterVolume = clamped;
            if (delegate instanceof FluidSynthReceiver) {
                ((FluidSynthReceiver) delegate).setMasterVolume(clamped);
                return;
            }
            for (int channel = 0; channel < channelVolumes.length; channel++) {
                sendChannelVolume(channel, -1L);
            }
        }

        private void sendChannelVolume(int channel, long timeStamp) {
            ShortMessage volume = new ShortMessage();
            try {
                volume.setMessage(
                        ShortMessage.CONTROL_CHANGE,
                        channel,
                        7,
                        scaleVolume(channelVolumes[channel], masterVolume));
                delegate.send(volume, timeStamp);
            } catch (InvalidMidiDataException ignored) {
                // Values are clamped above; keep playback alive if a provider rejects the message.
            }
        }

        @Override
        public synchronized void close() {
            if (!closed) {
                closed = true;
                delegate.close();
            }
        }
    }

    static String fluidSynthCommand(MidiMessage message) {
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

    static String fluidSynthGainCommand(int masterVolume) {
        double gain = clampVolume(masterVolume) * 0.2 / 127.0;
        return String.format(Locale.ROOT, "gain %.4f", Double.valueOf(gain));
    }

    private static final class FluidSynthReceiver implements Receiver {
        private final Process process;
        private final BufferedWriter writer;
        private volatile IOException failure;
        private volatile String diagnostic;
        private volatile boolean closed;

        FluidSynthReceiver(String command, Path soundFont) throws IOException {
            ProcessBuilder processBuilder = new ProcessBuilder(
                    command,
                    "-n",
                    "-q",
                    soundFont.toAbsolutePath().normalize().toString());
            processBuilder.redirectErrorStream(true);
            process = processBuilder.start();
            writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));

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
            String command = fluidSynthCommand(message);
            if (command == null) {
                return;
            }
            writeCommand(command);
        }

        synchronized void setMasterVolume(int masterVolume) {
            if (!closed && failure == null) {
                writeCommand(fluidSynthGainCommand(masterVolume));
            }
        }

        void throwIfFailed() throws MidiUnavailableException {
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

    private static final class ProgressTracker {
        private final PlaybackSequenceBuilder.BuiltSequence builtSequence;
        private final int requestedLoopCount;
        private long lastTick = -1L;
        private int wrappedLoopCount = 0;

        ProgressTracker(PlaybackSequenceBuilder.BuiltSequence builtSequence, int requestedLoopCount) {
            this.builtSequence = builtSequence;
            this.requestedLoopCount = requestedLoopCount;
        }

        PlaybackMonitor.Progress snapshot(long tickPosition, boolean finalFrame) {
            observeWrap(tickPosition);

            long totalLength = Math.max(1L, builtSequence.contentEndTick);
            long absoluteTick = clampLong(0L, totalLength, tickPosition);
            if (finalFrame) {
                absoluteTick = totalLength;
            }

            double fraction = totalLength <= 0L ? 1.0 : (double) absoluteTick / (double) totalLength;
            if (fraction < 0.0) {
                fraction = 0.0;
            } else if (fraction > 1.0) {
                fraction = 1.0;
            }

            return new PlaybackMonitor.Progress(
                    absoluteTick,
                    totalLength,
                    fraction,
                    currentLabel(tickPosition, finalFrame));
        }

        private void observeWrap(long tickPosition) {
            if (lastTick >= 0L
                    && tickPosition < lastTick
                    && !builtSequence.materializedLoopPasses
                    && (builtSequence.hasLoop || requestedLoopCount != 0)) {
                wrappedLoopCount++;
            }
            lastTick = tickPosition;
        }

        private String currentLabel(long tickPosition, boolean finalFrame) {
            if (builtSequence.hasLoop && builtSequence.materializedLoopPasses) {
                long loopStart = Math.max(0L, builtSequence.loopStartTick);
                if (!finalFrame && tickPosition < loopStart) {
                    return "intro";
                }
                long relativeTick = Math.max(0L, tickPosition - loopStart);
                long loopBodyTicks = Math.max(1L, builtSequence.loopBodyTickLength);
                int loopPass = (int) (relativeTick / loopBodyTicks) + 1;
                if (builtSequence.totalLoopPasses > 0) {
                    loopPass = Math.min(loopPass, builtSequence.totalLoopPasses);
                }
                String totalLoops = builtSequence.totalLoopPasses > 0
                        ? String.valueOf(builtSequence.totalLoopPasses)
                        : "inf";
                return "loop " + loopPass + "/" + totalLoops;
            }
            String s = requestedLoopCount < 0 ? "inf" : String.valueOf(Math.max(1, requestedLoopCount + 1));
            if (!builtSequence.hasLoop) {
                if (requestedLoopCount == 0) {
                    return "play";
                }
                int pass = wrappedLoopCount + 1;
                String totalPasses = s;
                return "pass " + pass + "/" + totalPasses;
            }

            long loopStart = Math.max(0L, builtSequence.loopStartTick);
            if (!finalFrame && wrappedLoopCount == 0 && tickPosition < loopStart) {
                return "intro";
            }

            int loopPass = wrappedLoopCount + 1;
            String totalLoops = s;
            return "loop " + loopPass + "/" + totalLoops;
        }
    }

    private static final class ConsoleProgressPrinter {
        private static final int BAR_WIDTH = 28;
        private boolean renderedOnce = false;

        ConsoleProgressPrinter() {
        }

        void update(PlaybackMonitor.Progress progress) {
            render(progress);
        }

        void finish(PlaybackMonitor.Progress progress, boolean completedNaturally) {
            if (completedNaturally) {
                render(progress);
            }
            System.out.println();
            System.out.println(BOX_BORDER);
        }

        private void render(PlaybackMonitor.Progress progress) {
            int filled = (int) Math.round(progress.fraction * BAR_WIDTH);
            if (filled < 0) {
                filled = 0;
            } else if (filled > BAR_WIDTH) {
                filled = BAR_WIDTH;
            }

            StringBuilder bar = new StringBuilder(BAR_WIDTH);
            for (int i = 0; i < BAR_WIDTH; i++) {
                bar.append(i < filled ? '#' : '-');
            }

            String infoLine = String.format(
                    "tick %d/%d  %5.1f%%  %s",
                    Long.valueOf(progress.position),
                    Long.valueOf(progress.total),
                    Double.valueOf(progress.fraction * 100.0),
                    progress.label);
            String barLine = "[" + bar.toString() + "]";

            if (renderedOnce) {
                System.out.print("\r\u001B[1A\u001B[2K" + infoLine + System.lineSeparator() + "\u001B[2K" + barLine);
            } else {
                System.out.print(infoLine + System.lineSeparator() + barLine);
                renderedOnce = true;
            }
            System.out.flush();
        }
    }

    private static long clampLong(long min, long max, long value) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class OutputSetup {
        final Synthesizer synthesizer;
        final MidiDevice outputDevice;
        Receiver receiver;

        OutputSetup(Synthesizer synthesizer, MidiDevice outputDevice, Receiver receiver) {
            this.synthesizer = synthesizer;
            this.outputDevice = outputDevice;
            this.receiver = receiver;
        }
    }
}
