package playback;

import javax.sound.midi.MidiSystem;
import javax.sound.midi.MidiDevice;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.Receiver;
import javax.sound.midi.Sequencer;
import javax.sound.midi.Synthesizer;
import javax.sound.midi.Transmitter;

public final class JavaMidiPlayer {
    private static final String BOX_BORDER = "************************************************************";

    public boolean play(PlaybackSequenceBuilder.BuiltSequence builtSequence, int loopCount)
            throws Exception {
        ConsoleProgressPrinter progressPrinter = new ConsoleProgressPrinter();
        return playInternal(builtSequence, loopCount, PlaybackMonitor.NONE, progressPrinter);
    }

    public boolean play(
            PlaybackSequenceBuilder.BuiltSequence builtSequence,
            int loopCount,
            PlaybackMonitor monitor)
            throws Exception {
        return playInternal(builtSequence, loopCount, monitor, null);
    }

    private boolean playInternal(
            PlaybackSequenceBuilder.BuiltSequence builtSequence,
            int loopCount,
            PlaybackMonitor monitor,
            ConsoleProgressPrinter consolePrinter)
            throws Exception {
        Sequencer sequencer = null;
        Synthesizer synthesizer = null;
        MidiDevice outputDevice = null;
        Transmitter transmitter = null;
        Receiver receiver = null;
        PlaybackMonitor safeMonitor = monitor == null ? PlaybackMonitor.NONE : monitor;
        boolean completedNaturally = true;
        boolean paused = false;

        try {
            DeviceSetup deviceSetup = openSequencer();
            sequencer = deviceSetup.sequencer;
            if (sequencer == null) {
                throw new MidiUnavailableException("No MIDI sequencer is available.");
            }

            if (deviceSetup.externalSynthRequired) {
                transmitter = sequencer.getTransmitter();
                OutputSetup outputSetup = openPreferredOutput();
                synthesizer = outputSetup.synthesizer;
                outputDevice = outputSetup.outputDevice;
                receiver = outputSetup.receiver;
                transmitter.setReceiver(receiver);
            }

            sequencer.setSequence(builtSequence.sequence);
            configureLooping(sequencer, builtSequence, loopCount);
            PlaybackMonitor.Descriptor descriptor = buildDescriptor(
                    builtSequence,
                    loopCount,
                    deviceSetup,
                    synthesizer,
                    outputDevice);
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

    private DeviceSetup openSequencer() throws MidiUnavailableException {
        try {
            Sequencer sequencer = MidiSystem.getSequencer(false);
            if (sequencer != null) {
                sequencer.open();
                return new DeviceSetup(sequencer, true);
            }
        } catch (MidiUnavailableException e) {
            // Fall through to the default sequencer path.
        }

        Sequencer sequencer = MidiSystem.getSequencer();
        if (sequencer != null) {
            sequencer.open();
        }
        return new DeviceSetup(sequencer, false);
    }

    private PlaybackMonitor.Descriptor buildDescriptor(
            PlaybackSequenceBuilder.BuiltSequence builtSequence,
            int loopCount,
            DeviceSetup deviceSetup,
            Synthesizer synthesizer,
            MidiDevice outputDevice) {
        return new PlaybackMonitor.Descriptor(
                "host MIDI",
                describeLoop(builtSequence, loopCount),
                describeSequencer(deviceSetup),
                describeOutput(synthesizer, outputDevice, deviceSetup.externalSynthRequired));
    }

    private void printPlaybackSection(PlaybackMonitor.Descriptor descriptor) {
        System.out.println("Playback mode: " + descriptor.mode);
        System.out.println("Loop: " + descriptor.loopDescription);
        System.out.println("MIDI backend: " + descriptor.backend);
        System.out.println("MIDI output: " + descriptor.output);
    }

    private String describeSequencer(DeviceSetup deviceSetup) {
        if (deviceSetup == null || deviceSetup.sequencer == null) {
            return "Java MIDI Sequencer";
        }
        String name = deviceSetup.sequencer.getDeviceInfo().getName();
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

    private String describeOutput(Synthesizer synthesizer, MidiDevice outputDevice, boolean externalSynthRequired) {
        if (outputDevice != null) {
            return outputDevice.getDeviceInfo().getName();
        }
        if (synthesizer != null) {
            return synthesizer.getDeviceInfo().getName();
        }
        return externalSynthRequired ? "Default Java MIDI output" : "Default Java MIDI path";
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

    private OutputSetup openPreferredOutput() throws MidiUnavailableException {
        OutputSetup setup = probePreferredOutput();
        if (setup.outputDevice != null) {
            try {
                setup.outputDevice.open();
                setup.receiver = setup.outputDevice.getReceiver();
                return setup;
            } catch (MidiUnavailableException e) {
                if (setup.outputDevice.isOpen()) {
                    setup.outputDevice.close();
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

    private MidiDevice findReceiverDevice(String nameFragment) {
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

    private static final class DeviceSetup {
        final Sequencer sequencer;
        final boolean externalSynthRequired;

        DeviceSetup(Sequencer sequencer, boolean externalSynthRequired) {
            this.sequencer = sequencer;
            this.externalSynthRequired = externalSynthRequired;
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
