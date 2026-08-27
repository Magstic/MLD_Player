package playback;

import audio.AudioPcmTimeline;

/** Buffered PCM participant that follows absolute shared-transport time. */
final class PcmPlaybackParticipant implements AutoCloseable, MasterVolumeTarget {
    static final int PUMP_CHUNK_FRAMES = 512;
    static final int DRIFT_RESYNC_MILLIS = 120;
    private static final int WARMUP_MILLIS = 250;

    private final AudioPcmTimeline source;
    private final PcmOutputConnection output;
    private final MasterVolume masterVolume;
    private final long leadFrames;
    private final long driftFrames;
    private final long warmupFrames;

    private long lineFrameOrigin;
    private long transportFrameOrigin;
    private long nextWriteTransportFrame;
    private volatile int masterVolumeValue = 127;
    private boolean started;
    private boolean paused;

    private PcmPlaybackParticipant(
            AudioPcmTimeline source,
            PcmOutputConnection output,
            MasterVolume masterVolume) {
        this.source = source;
        this.output = output;
        this.masterVolume = masterVolume;
        int rate = source.getSampleRate();
        this.leadFrames = Math.max(PUMP_CHUNK_FRAMES,
                (long) rate * PcmOutputConnection.TARGET_BUFFER_MILLIS / 1000L);
        this.driftFrames = Math.max(1L, (long) rate * DRIFT_RESYNC_MILLIS / 1000L);
        this.warmupFrames = Math.max(1L, (long) rate * WARMUP_MILLIS / 1000L);
    }

    static PcmPlaybackParticipant open(AudioPcmTimeline source, MasterVolume masterVolume) throws Exception {
        if (source == null) {
            throw new IllegalArgumentException("PCM timeline is required.");
        }
        PcmPlaybackParticipant participant = new PcmPlaybackParticipant(
                source,
                PcmOutputConnection.open(source.getSampleRate()),
                masterVolume);
        if (masterVolume != null) {
            masterVolume.attach(participant);
        }
        return participant;
    }

    @Override
    public void setMasterVolume(int value) {
        masterVolumeValue = MasterVolume.clamp(value);
    }

    void prime() {
        lineFrameOrigin = output.framePosition();
        transportFrameOrigin = 0L;
        nextWriteTransportFrame = 0L;
        fillAhead(0L);
    }

    void start() {
        output.start();
        started = true;
        paused = false;
    }

    void pause() {
        if (!started || paused) return;
        output.stop();
        paused = true;
    }

    void resume() {
        if (!started || !paused) return;
        output.start();
        paused = false;
    }

    void sync(long transportMicros) {
        if (!started || paused) return;
        long targetFrame = microsToFrameFloor(Math.max(0L, transportMicros), source.getSampleRate());
        long actualFrame = transportFramePosition();
        if (targetFrame >= warmupFrames && absoluteDifference(actualFrame, targetFrame) > driftFrames) {
            seek(targetFrame);
        }
        fillAhead(targetFrame);
    }

    void finish() {
        if (started) output.drain();
    }

    long positionMicros() {
        return frameToMicros(transportFramePosition(), source.getSampleRate());
    }

    String backendDescription() {
        return "Java Sound SourceDataLine";
    }

    String outputDescription() {
        return output.description();
    }

    private long transportFramePosition() {
        long rendered = Math.max(0L, output.framePosition() - lineFrameOrigin);
        return transportFrameOrigin + rendered;
    }

    private void seek(long targetFrame) {
        boolean resumeAfterSeek = started && !paused;
        output.stop();
        output.flush();
        lineFrameOrigin = output.framePosition();
        transportFrameOrigin = Math.max(0L, targetFrame);
        nextWriteTransportFrame = transportFrameOrigin;
        fillAhead(transportFrameOrigin);
        if (resumeAfterSeek) output.start();
    }

    private void fillAhead(long targetFrame) {
        long totalFrames = source.getTotalFrames();
        long desiredEnd = targetFrame > Long.MAX_VALUE - leadFrames
                ? Long.MAX_VALUE
                : targetFrame + leadFrames;
        if (totalFrames != Long.MAX_VALUE) desiredEnd = Math.min(desiredEnd, totalFrames);

        while (nextWriteTransportFrame < desiredEnd) {
            int availableFrames = output.availableBytes() / 4;
            if (availableFrames <= 0) break;
            int frameCount = (int) Math.min(
                    Math.min((long) PUMP_CHUNK_FRAMES, (long) availableFrames),
                    desiredEnd - nextWriteTransportFrame);
            if (frameCount <= 0) break;
            byte[] bytes = source.renderFrames(nextWriteTransportFrame, frameCount);
            applyMasterVolume(bytes, masterVolumeValue);
            if (bytes.length == 0) {
                nextWriteTransportFrame = desiredEnd;
                break;
            }
            int written = output.write(bytes, 0, bytes.length);
            int writtenFrames = written / 4;
            if (writtenFrames <= 0) break;
            nextWriteTransportFrame += writtenFrames;
            if (written < bytes.length) break;
        }
    }

    static void applyMasterVolume(byte[] pcmBytes, int masterVolume) {
        if (pcmBytes == null || pcmBytes.length == 0) return;
        int volume = MasterVolume.clamp(masterVolume);
        if (volume == 127) return;
        int limit = pcmBytes.length - (pcmBytes.length % 2);
        for (int offset = 0; offset < limit; offset += 2) {
            int raw = (pcmBytes[offset] & 0xFF) | (pcmBytes[offset + 1] << 8);
            int scaled = scalePcmSample((short) raw, volume);
            pcmBytes[offset] = (byte) (scaled & 0xFF);
            pcmBytes[offset + 1] = (byte) ((scaled >>> 8) & 0xFF);
        }
    }

    static int scalePcmSample(int sample, int masterVolume) {
        int volume = MasterVolume.clamp(masterVolume);
        int clampedSample = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, sample));
        if (volume == 127) return clampedSample;
        if (volume == 0 || clampedSample == 0) return 0;
        int product = clampedSample * volume;
        return product > 0 ? (product + 63) / 127 : (product - 63) / 127;
    }

    private static long microsToFrameFloor(long micros, int sampleRate) {
        if (micros <= 0L) return 0L;
        long whole = micros / 1000000L;
        long remainder = micros % 1000000L;
        if (whole > Long.MAX_VALUE / sampleRate) return Long.MAX_VALUE;
        return whole * sampleRate + (remainder * sampleRate) / 1000000L;
    }

    private static long frameToMicros(long frame, int sampleRate) {
        if (frame <= 0L) return 0L;
        long whole = frame / sampleRate;
        long remainder = frame % sampleRate;
        if (whole > Long.MAX_VALUE / 1000000L) return Long.MAX_VALUE;
        return whole * 1000000L + (remainder * 1000000L) / sampleRate;
    }

    private static long absoluteDifference(long left, long right) {
        return left >= right ? left - right : right - left;
    }

    @Override
    public void close() {
        if (masterVolume != null) {
            masterVolume.detach(this);
        }
        output.close();
    }
}
