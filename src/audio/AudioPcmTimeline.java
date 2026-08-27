package audio;

import java.util.List;

/** Immutable transport-configured PCM source with exact voice-instance loop overlap. */
public final class AudioPcmTimeline {
    private final int sampleRate;
    private final List<AudioPlaybackSource.Voice> voices;
    private final boolean nativeLoop;
    private final long loopStartMicros;
    private final long loopEndMicros;
    private final long loopBodyMicros;
    private final int requestedLoopCount;
    private final int totalPasses;
    private final long wholePassMicros;
    private final long linearFrameCount;
    private final boolean infinite;
    private final long totalFrames;

    AudioPcmTimeline(
            int sampleRate,
            List<AudioPlaybackSource.Voice> voices,
            boolean nativeLoop,
            long loopStartMicros,
            long loopEndMicros,
            int requestedLoopCount,
            long wholePassMicros,
            long linearFrameCount) {
        this.sampleRate = sampleRate;
        this.voices = voices;
        this.nativeLoop = nativeLoop;
        this.loopStartMicros = Math.max(0L, loopStartMicros);
        this.loopEndMicros = Math.max(this.loopStartMicros + 1L, loopEndMicros);
        this.loopBodyMicros = Math.max(1L, this.loopEndMicros - this.loopStartMicros);
        this.requestedLoopCount = requestedLoopCount;
        this.totalPasses = requestedLoopCount < 0 ? -1 : Math.max(1, requestedLoopCount + 1);
        this.wholePassMicros = Math.max(1L, wholePassMicros);
        this.linearFrameCount = Math.max(1L, linearFrameCount);
        this.infinite = requestedLoopCount < 0;
        this.totalFrames = infinite ? Long.MAX_VALUE : finiteTotalFrames();
    }

    public int getSampleRate() { return sampleRate; }
    public boolean isInfinite() { return infinite; }
    public long getTotalFrames() { return totalFrames; }

    public byte[] renderFrames(long transportStartFrame, int requestedFrames) {
        if (transportStartFrame < 0L || requestedFrames <= 0) return new byte[0];
        long remaining = totalFrames == Long.MAX_VALUE
                ? requestedFrames : Math.max(0L, totalFrames - transportStartFrame);
        int frames = (int) Math.min((long) requestedFrames, remaining);
        if (frames <= 0) return new byte[0];
        int[] left = new int[frames];
        int[] right = new int[frames];
        long chunkEnd = AudioPlaybackSource.safeAdd(transportStartFrame, frames);
        for (AudioPlaybackSource.Voice voice : voices) {
            mixVoiceInstances(left, right, transportStartFrame, chunkEnd, voice);
        }
        return AudioPlaybackSource.interleavedBytes(left, right);
    }

    private void mixVoiceInstances(
            int[] left, int[] right, long chunkStart, long chunkEnd, AudioPlaybackSource.Voice voice) {
        if (nativeLoop) {
            if (voice.startMicros < loopStartMicros) {
                AudioPlaybackSource.mixVoice(left, right, chunkStart, chunkEnd, voice, voice.startFrame);
                return;
            }
            if (voice.startMicros >= loopEndMicros) return;
            mixRepeated(left, right, chunkStart, chunkEnd, voice, loopBodyMicros, totalPasses);
            return;
        }
        if (requestedLoopCount == 0) {
            AudioPlaybackSource.mixVoice(left, right, chunkStart, chunkEnd, voice, voice.startFrame);
            return;
        }
        mixRepeated(left, right, chunkStart, chunkEnd, voice, wholePassMicros, totalPasses);
    }

    private void mixRepeated(
            int[] left, int[] right, long chunkStart, long chunkEnd,
            AudioPlaybackSource.Voice voice, long passMicros, int passes) {
        long voiceLength = voice.pcm16.length;
        long approximateShift = Math.max(1L, AudioPlaybackSource.microsToFrameFloor(passMicros, sampleRate));
        long firstPass = Math.max(0L, Math.floorDiv(chunkStart - voiceLength - voice.startFrame, approximateShift) + 1L);
        while (firstPass > 0L
                && AudioPlaybackSource.safeAdd(instanceStartFrame(voice, firstPass, passMicros), voiceLength) > chunkStart) {
            firstPass--;
        }
        while (AudioPlaybackSource.safeAdd(instanceStartFrame(voice, firstPass, passMicros), voiceLength) <= chunkStart) {
            firstPass++;
        }
        long lastPass = Math.max(0L, Math.floorDiv((chunkEnd - 1L) - voice.startFrame, approximateShift));
        while (lastPass > 0L && instanceStartFrame(voice, lastPass, passMicros) >= chunkEnd) lastPass--;
        while (lastPass < Long.MAX_VALUE - 1L && instanceStartFrame(voice, lastPass + 1L, passMicros) < chunkEnd) lastPass++;
        if (passes >= 0) lastPass = Math.min(lastPass, passes - 1L);
        if (firstPass > lastPass) return;
        for (long pass = firstPass; pass <= lastPass; pass++) {
            AudioPlaybackSource.mixVoice(
                    left, right, chunkStart, chunkEnd, voice, instanceStartFrame(voice, pass, passMicros));
            if (pass == Long.MAX_VALUE) break;
        }
    }

    long instanceStartFrameForAudit(long voiceStartMicros, long pass, long passMicros) {
        long absoluteMicros = AudioPlaybackSource.safeAdd(
                Math.max(0L, voiceStartMicros), AudioPlaybackSource.safeMultiply(Math.max(0L, pass), passMicros));
        return AudioPlaybackSource.microsToFrameFloor(absoluteMicros, sampleRate);
    }

    private long instanceStartFrame(AudioPlaybackSource.Voice voice, long pass, long passMicros) {
        if (pass <= 0L) return voice.startFrame;
        return instanceStartFrameForAudit(voice.startMicros, pass, passMicros);
    }

    private long finiteTotalFrames() {
        if (!nativeLoop) {
            if (requestedLoopCount == 0) return linearFrameCount;
            long nominalMicros = AudioPlaybackSource.safeMultiply(wholePassMicros, Math.max(1, totalPasses));
            long end = Math.max(1L, AudioPlaybackSource.microsToFrameCeil(nominalMicros, sampleRate));
            for (AudioPlaybackSource.Voice voice : voices) {
                long lastStart = instanceStartFrame(voice, Math.max(0, totalPasses - 1), wholePassMicros);
                end = Math.max(end, AudioPlaybackSource.safeAdd(lastStart, voice.pcm16.length));
            }
            return end;
        }
        long nominalMicros = AudioPlaybackSource.safeAdd(
                loopStartMicros, AudioPlaybackSource.safeMultiply(loopBodyMicros, Math.max(1, totalPasses)));
        long end = Math.max(1L, AudioPlaybackSource.microsToFrameCeil(nominalMicros, sampleRate));
        for (AudioPlaybackSource.Voice voice : voices) {
            if (voice.startMicros < loopStartMicros) {
                end = Math.max(end, AudioPlaybackSource.safeAdd(voice.startFrame, voice.pcm16.length));
            } else if (voice.startMicros < loopEndMicros) {
                long lastStart = instanceStartFrame(voice, Math.max(0, totalPasses - 1), loopBodyMicros);
                end = Math.max(end, AudioPlaybackSource.safeAdd(lastStart, voice.pcm16.length));
            }
        }
        return end;
    }
}
