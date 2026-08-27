package playback;

import audio.AudioPcmTimeline;
import midi.MidiSequenceEncoder;

import javax.sound.midi.MidiSystem;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.Sequencer;
import javax.sound.midi.Transmitter;

/** Shared MIDI/PCM transport session with one monotonic microsecond clock. */
public final class PlaybackSession {
    static final long MIDI_DRIFT_RESYNC_MICROS = 120000L;
    private static final long LOOP_POLL_MILLIS = 20L;
    private static final long MIDI_COMPLETION_GRACE_MILLIS = 250L;

    public boolean play(
            PlaybackContent content,
            int loopCount,
            PlaybackMonitor monitor,
            MidiOutput midiOutput,
            MasterVolume masterVolume)
            throws Exception {
        if (content == null) {
            throw new IllegalArgumentException("Playback content is required.");
        }
        PlaybackMonitor safeMonitor = monitor == null ? PlaybackMonitor.NONE : monitor;
        TransportTimeline timeline = TransportTimeline.from(content, loopCount);
        MonotonicTransportClock clock = new MonotonicTransportClock();
        Sequencer sequencer = null;
        Transmitter transmitter = null;
        MidiOutputConnection midiConnection = null;
        PcmPlaybackParticipant pcm = null;
        boolean completedNaturally = true;
        boolean paused = false;
        long midiWholeRepeatPass = 0L;

        try {
            if (content.hasMidi()) {
                sequencer = openSequencer();
                transmitter = sequencer.getTransmitter();
                midiConnection = MidiOutputConnection.open(midiOutput, masterVolume);
                transmitter.setReceiver(midiConnection.receiver());
                midiConnection.checkHealth();
                sequencer.setSequence(content.midi.sequence);
                configureMidiLooping(
                        sequencer, content.midi, loopCount, timeline.transportOwnsWholeMidiRepeat);
            }
            if (content.hasPcm()) {
                AudioPcmTimeline pcmTimeline = content.audio.forTransport(
                        loopCount, timeline.baseDurationMicros);
                pcm = PcmPlaybackParticipant.open(pcmTimeline, masterVolume);
                pcm.prime();
            }

            PlaybackMonitor.Descriptor descriptor = new PlaybackMonitor.Descriptor(
                    describeMode(content),
                    timeline.describeLoop(),
                    describeBackend(sequencer, pcm),
                    describeOutput(midiConnection, pcm));
            safeMonitor.onPlaybackPrepared(descriptor);
            safeMonitor.onPlaybackProgress(timeline.progress(0L, false));

            if (pcm != null) {
                pcm.start();
            }
            if (sequencer != null) {
                sequencer.start();
            }
            clock.start();

            while (true) {
                if (midiConnection != null) {
                    midiConnection.checkHealth();
                }
                if (safeMonitor.isPauseRequested()) {
                    if (!paused) {
                        clock.pause();
                        if (sequencer != null) {
                            sequencer.stop();
                        }
                        if (pcm != null) {
                            pcm.pause();
                        }
                        paused = true;
                    }
                } else if (paused) {
                    if (pcm != null) {
                        pcm.resume();
                    }
                    if (sequencer != null
                            && shouldMidiRun(timeline, clock.positionMicros())) {
                        sequencer.setMicrosecondPosition(Math.max(
                                0L, timeline.midiPositionMicros(clock.positionMicros())));
                        sequencer.start();
                    }
                    clock.resume();
                    paused = false;
                }

                long transportMicros = clock.positionMicros();
                if (!paused) {
                    midiWholeRepeatPass = syncMidi(
                            sequencer, content.midi, timeline, transportMicros,
                            content.hasPcm(), midiWholeRepeatPass);
                    if (pcm != null) {
                        pcm.sync(transportMicros);
                    }
                }
                safeMonitor.onPlaybackProgress(timeline.progress(transportMicros, false));

                if (safeMonitor.isStopRequested()) {
                    completedNaturally = false;
                    break;
                }
                if (timeline.isComplete(transportMicros)) {
                    break;
                }
                Thread.sleep(timeline.pollSleepMillis(transportMicros, LOOP_POLL_MILLIS));
            }

            clock.pause();
            if (completedNaturally && sequencer != null) {
                awaitMidiCompletion(sequencer);
            }
            if (sequencer != null) {
                sequencer.stop();
            }
            if (pcm != null && completedNaturally) {
                pcm.finish();
            }
            if (midiConnection != null) {
                midiConnection.checkHealth();
            }
            long finalMicros = completedNaturally && !timeline.infinite
                    ? timeline.totalDurationMicros
                    : clock.positionMicros();
            PlaybackMonitor.Progress finalProgress = timeline.progress(finalMicros, completedNaturally);
            safeMonitor.onPlaybackProgress(finalProgress);
            safeMonitor.onPlaybackFinished(finalProgress, completedNaturally);
            return completedNaturally;
        } finally {
            if (sequencer != null && sequencer.isOpen()) {
                sequencer.stop();
            }
            if (transmitter != null) {
                transmitter.close();
            }
            if (pcm != null) {
                pcm.close();
            }
            if (midiConnection != null) {
                midiConnection.close();
            }
            if (sequencer != null && sequencer.isOpen()) {
                sequencer.close();
            }
        }
    }

    static long syncMidi(
            Sequencer sequencer,
            MidiSequenceEncoder.EncodedSequence midi,
            TransportTimeline timeline,
            long transportMicros,
            boolean sharedPcm,
            long previousWholeRepeatPass) {
        if (sequencer == null || midi == null) return previousWholeRepeatPass;
        long currentPass = timeline.midiWholeRepeatPass(transportMicros);
        if (timeline.crossedMidiWholeRepeatBoundary(transportMicros, previousWholeRepeatPass)) {
            if (sequencer.isRunning()) sequencer.stop();
            sequencer.setMicrosecondPosition(0L);
            if (shouldMidiRun(timeline, transportMicros)) sequencer.start();
            return currentPass;
        }
        long desired = timeline.midiPositionMicros(transportMicros);
        if (desired < 0L) return currentPass;
        if (sharedPcm && desired >= timeline.midiDurationMicros) {
            if (sequencer.isRunning()) sequencer.stop();
            return currentPass;
        }
        if (!sequencer.isRunning() && shouldMidiRun(timeline, transportMicros)) {
            sequencer.setMicrosecondPosition(Math.max(0L, desired));
            sequencer.start();
        }
        if (!sequencer.isRunning()) return currentPass;
        long actual = Math.max(0L, sequencer.getMicrosecondPosition());
        long difference = actual >= desired ? actual - desired : desired - actual;
        if (transportMicros >= MIDI_DRIFT_RESYNC_MICROS && difference > MIDI_DRIFT_RESYNC_MICROS) {
            sequencer.setMicrosecondPosition(desired);
        }
        return currentPass;
    }


    private static boolean shouldMidiRun(TransportTimeline timeline, long transportMicros) {
        if (timeline == null || timeline.midiDurationMicros <= 0L || timeline.isComplete(transportMicros)) {
            return false;
        }
        long desired = timeline.midiPositionMicros(Math.max(0L, transportMicros));
        return desired >= 0L && desired < timeline.midiDurationMicros;
    }

    private static void awaitMidiCompletion(Sequencer sequencer) throws InterruptedException {
        long deadline = System.nanoTime() + (MIDI_COMPLETION_GRACE_MILLIS * 1000000L);
        while (sequencer.isRunning() && System.nanoTime() < deadline) {
            Thread.sleep(5L);
        }
    }

    static void configureMidiLooping(
            Sequencer sequencer,
            MidiSequenceEncoder.EncodedSequence encodedSequence,
            int loopCount) {
        configureMidiLooping(sequencer, encodedSequence, loopCount, false);
    }

    static void configureMidiLooping(
            Sequencer sequencer,
            MidiSequenceEncoder.EncodedSequence encodedSequence,
            int loopCount,
            boolean transportOwnsWholeRepeat) {
        if (sequencer == null || encodedSequence == null) {
            return;
        }
        if (loopCount == 0 || encodedSequence.materializedLoopPasses
                || (transportOwnsWholeRepeat && !encodedSequence.hasLoop)) {
            sequencer.setLoopCount(0);
            return;
        }
        if (encodedSequence.hasLoop) {
            sequencer.setLoopStartPoint(encodedSequence.loopStartTick);
            sequencer.setLoopEndPoint(Math.max(encodedSequence.loopStartTick, encodedSequence.loopEndTick));
        } else {
            sequencer.setLoopStartPoint(0L);
            sequencer.setLoopEndPoint(Math.max(0L, encodedSequence.contentEndTick));
        }
        sequencer.setLoopCount(loopCount < 0 ? Sequencer.LOOP_CONTINUOUSLY : loopCount);
    }

    static String describeMode(PlaybackContent content) {
        if (content.hasMidi() && content.hasPcm()) {
            return "mixed MIDI + PCM";
        }
        return content.hasPcm() ? "sampled PCM" : "host MIDI";
    }

    private static Sequencer openSequencer() throws MidiUnavailableException {
        Sequencer sequencer = MidiSystem.getSequencer(false);
        if (sequencer == null) {
            throw new MidiUnavailableException("No MIDI sequencer is available.");
        }
        sequencer.open();
        return sequencer;
    }

    private static String describeBackend(Sequencer sequencer, PcmPlaybackParticipant pcm) {
        String midi = sequencer == null ? null : describeSequencer(sequencer);
        String audio = pcm == null ? null : pcm.backendDescription();
        if (midi != null && audio != null) {
            return midi + " + " + audio;
        }
        return midi != null ? midi : audio;
    }

    private static String describeOutput(MidiOutputConnection midi, PcmPlaybackParticipant pcm) {
        String midiText = midi == null ? null : "MIDI: " + midi.description();
        String pcmText = pcm == null ? null : "PCM: " + pcm.outputDescription();
        if (midiText != null && pcmText != null) {
            return midiText + "; " + pcmText;
        }
        return midiText != null ? midiText.substring(6) : pcmText.substring(5);
    }

    private static String describeSequencer(Sequencer sequencer) {
        String name = sequencer.getDeviceInfo().getName();
        if (name == null || name.trim().isEmpty()) {
            return "Java MIDI Sequencer";
        }
        if ("Real Time Sequencer".equalsIgnoreCase(name.trim())) {
            return "Java Real Time Sequencer";
        }
        return name.startsWith("Java ") ? name : "Java " + name;
    }
}
