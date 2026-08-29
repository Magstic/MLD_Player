package playback;

import audio.AudioPcmTimeline;

/** Shared MIDI/PCM transport session with one monotonic microsecond clock. */
public final class PlaybackSession {
    private static final long DEFAULT_POLL_MICROS = 20000L;
    private static final long MIN_SLEEP_MICROS = 200L;
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
        MidiOutputConnection midiConnection = null;
        MidiPlaybackParticipant midi = null;
        PcmPlaybackParticipant pcm = null;
        boolean completedNaturally = true;
        boolean paused = false;

        try {
            if (content.hasMidi()) {
                midiConnection = MidiOutputConnection.open(midiOutput, masterVolume);
                midiConnection.checkHealth();
                midi = MidiPlaybackParticipant.open(
                        content.midi,
                        content.program,
                        timeline,
                        midiConnection.receiver());
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
                    describeBackend(midi != null, pcm),
                    describeOutput(midiConnection, pcm));
            safeMonitor.onPlaybackPrepared(descriptor);
            safeMonitor.onPlaybackProgress(timeline.progress(0L, false));

            if (pcm != null) pcm.start();
            clock.start();
            if (midi != null) midi.sync(0L);

            while (true) {
                if (midiConnection != null) midiConnection.checkHealth();
                if (safeMonitor.isStopRequested()) {
                    completedNaturally = false;
                    break;
                }

                if (safeMonitor.isPauseRequested()) {
                    if (!paused) {
                        clock.pause();
                        if (midi != null) midi.pause();
                        if (pcm != null) pcm.pause();
                        paused = true;
                    }
                } else if (paused) {
                    if (pcm != null) pcm.resume();
                    if (midi != null) midi.resume();
                    clock.resume();
                    paused = false;
                }

                long transportMicros = clock.positionMicros();
                if (!paused) {
                    if (midi != null) midi.sync(transportMicros);
                    if (pcm != null) pcm.sync(transportMicros);
                }
                safeMonitor.onPlaybackProgress(timeline.progress(transportMicros, false));

                if (safeMonitor.isStopRequested()) {
                    completedNaturally = false;
                    break;
                }
                if (timeline.isComplete(transportMicros)) break;

                long sleepMicros = DEFAULT_POLL_MICROS;
                if (midi != null) {
                    sleepMicros = Math.min(
                            sleepMicros,
                            midi.microsUntilNextEvent(transportMicros));
                }
                long boundaryMillis = timeline.pollSleepMillis(
                        transportMicros, Math.max(1L, DEFAULT_POLL_MICROS / 1000L));
                sleepMicros = Math.min(sleepMicros, boundaryMillis * 1000L);
                sleepMicros(sleepMicros);
            }

            clock.pause();
            if (completedNaturally && midi != null && !timeline.infinite) {
                midi.sync(timeline.totalDurationMicros);
                Thread.sleep(MIDI_COMPLETION_GRACE_MILLIS);
            }
            if (pcm != null && completedNaturally) pcm.finish();
            if (midiConnection != null) midiConnection.checkHealth();

            long finalMicros = completedNaturally && !timeline.infinite
                    ? timeline.totalDurationMicros
                    : clock.positionMicros();
            PlaybackMonitor.Progress finalProgress = timeline.progress(finalMicros, completedNaturally);
            safeMonitor.onPlaybackProgress(finalProgress);
            safeMonitor.onPlaybackFinished(finalProgress, completedNaturally);
            return completedNaturally;
        } finally {
            if (midi != null) midi.close();
            if (pcm != null) pcm.close();
            if (midiConnection != null) midiConnection.close();
        }
    }

    private static void sleepMicros(long requestedMicros) throws InterruptedException {
        long micros = requestedMicros == Long.MAX_VALUE
                ? DEFAULT_POLL_MICROS
                : Math.max(MIN_SLEEP_MICROS, Math.min(DEFAULT_POLL_MICROS, requestedMicros));
        long millis = micros / 1000L;
        int nanos = (int) ((micros % 1000L) * 1000L);
        Thread.sleep(millis, nanos);
    }

    static String describeMode(PlaybackContent content) {
        if (content.hasMidi() && content.hasPcm()) return "mixed MIDI + PCM";
        return content.hasPcm() ? "sampled PCM" : "host MIDI";
    }

    private static String describeBackend(boolean midi, PcmPlaybackParticipant pcm) {
        String midiText = midi ? "Direct MIDI scheduler" : null;
        String audio = pcm == null ? null : pcm.backendDescription();
        if (midiText != null && audio != null) return midiText + " + " + audio;
        return midiText != null ? midiText : audio;
    }

    private static String describeOutput(MidiOutputConnection midi, PcmPlaybackParticipant pcm) {
        String midiText = midi == null ? null : "MIDI: " + midi.description();
        String pcmText = pcm == null ? null : "PCM: " + pcm.outputDescription();
        if (midiText != null && pcmText != null) return midiText + "; " + pcmText;
        return midiText != null ? midiText.substring(6) : pcmText.substring(5);
    }
}
