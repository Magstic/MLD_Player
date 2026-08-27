package audio;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import mld.semantic.LoopModel;

/** Immutable decoded sampled-audio voice schedule used by offline and transport rendering. */
public final class AudioPlaybackSource {
    private final int sampleRate;
    private final long semanticEndFrame;
    private final LoopModel loop;
    private final long loopStartMicros;
    private final long loopEndMicros;
    private final long loopStartFrame;
    private final long loopEndFrame;
    private final List<Voice> voices;
    private final long linearFrameCount;

    AudioPlaybackSource(
            int sampleRate,
            long semanticEndMicros,
            LoopModel loop,
            long loopStartMicros,
            long loopEndMicros,
            List<Voice> voices) {
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate <= 0");
        }
        this.sampleRate = sampleRate;
        this.semanticEndFrame = microsToFrameCeil(semanticEndMicros, sampleRate);
        this.loop = loop;
        this.loopStartMicros = Math.max(0L, loopStartMicros);
        this.loopEndMicros = Math.max(this.loopStartMicros + 1L, loopEndMicros);
        this.loopStartFrame = microsToFrameFloor(this.loopStartMicros, sampleRate);
        this.loopEndFrame = Math.max(
                this.loopStartFrame + 1L,
                microsToFrameFloor(this.loopEndMicros, sampleRate));
        this.voices = Collections.unmodifiableList(new ArrayList<Voice>(voices));
        long end = Math.max(1L, semanticEndFrame);
        for (Voice voice : voices) {
            end = Math.max(end, safeAdd(voice.startFrame, voice.pcm16.length));
        }
        this.linearFrameCount = end;
    }

    public int getSampleRate() {
        return sampleRate;
    }

    public long getLinearFrameCount() {
        return linearFrameCount;
    }

    public long getLinearDurationMicros() {
        return frameToMicrosCeil(linearFrameCount, sampleRate);
    }

    public StereoPcm renderLinear() {
        if (linearFrameCount > (Integer.MAX_VALUE / 2L) - 8L) {
            throw new IllegalArgumentException("rendered MLD PCM is too large");
        }
        int frames = (int) Math.max(1L, linearFrameCount);
        int[] left = new int[frames];
        int[] right = new int[frames];
        for (Voice voice : voices) {
            mixVoice(left, right, 0L, frames, voice, voice.startFrame);
        }
        return stereo(left, right);
    }

    public AudioPcmTimeline forTransport(int requestedLoopCount, long wholePassMicros) {
        return new AudioPcmTimeline(
                sampleRate,
                voices,
                loop != null && loop.hasLoop,
                loopStartMicros,
                loopEndMicros,
                requestedLoopCount,
                Math.max(1L, wholePassMicros),
                linearFrameCount);
    }

    public long transportDurationMicros(int requestedLoopCount, long wholePassMicros) {
        AudioPcmTimeline timeline = forTransport(requestedLoopCount, wholePassMicros);
        return timeline.isInfinite()
                ? Long.MAX_VALUE
                : frameToMicrosCeil(timeline.getTotalFrames(), sampleRate);
    }

    static final class Voice {
        final long startMicros;
        final long startFrame;
        final short[] pcm16;
        final int leftGain;
        final int rightGain;

        Voice(long startMicros, long startFrame, short[] pcm16, int leftGain, int rightGain) {
            this.startMicros = Math.max(0L, startMicros);
            this.startFrame = Math.max(0L, startFrame);
            this.pcm16 = pcm16;
            this.leftGain = leftGain;
            this.rightGain = rightGain;
        }
    }

    static void mixVoice(
            int[] left,
            int[] right,
            long chunkStartFrame,
            long chunkEndFrame,
            Voice voice,
            long instanceStartFrame) {
        long voiceEnd = safeAdd(instanceStartFrame, voice.pcm16.length);
        long overlapStart = Math.max(chunkStartFrame, instanceStartFrame);
        long overlapEnd = Math.min(chunkEndFrame, voiceEnd);
        if (overlapStart >= overlapEnd) {
            return;
        }
        int sourceIndex = (int) (overlapStart - instanceStartFrame);
        int targetIndex = (int) (overlapStart - chunkStartFrame);
        int count = (int) (overlapEnd - overlapStart);
        for (int i = 0; i < count; i++) {
            int sample = voice.pcm16[sourceIndex + i];
            left[targetIndex + i] = saturatingAdd(
                    left[targetIndex + i], MfiAudioMixer.applySample(sample, voice.leftGain));
            right[targetIndex + i] = saturatingAdd(
                    right[targetIndex + i], MfiAudioMixer.applySample(sample, voice.rightGain));
        }
    }

    static byte[] interleavedBytes(int[] left, int[] right) {
        byte[] bytes = new byte[left.length * 4];
        for (int frame = 0; frame < left.length; frame++) {
            short l = (short) clamp(left[frame], -32768, 32767);
            short r = (short) clamp(right[frame], -32768, 32767);
            int offset = frame * 4;
            bytes[offset] = (byte) (l & 0xFF);
            bytes[offset + 1] = (byte) ((l >>> 8) & 0xFF);
            bytes[offset + 2] = (byte) (r & 0xFF);
            bytes[offset + 3] = (byte) ((r >>> 8) & 0xFF);
        }
        return bytes;
    }

    static long microsToFrameFloor(long micros, int sampleRate) {
        if (micros <= 0L) return 0L;
        long whole = micros / 1000000L;
        long remainder = micros % 1000000L;
        if (whole > Long.MAX_VALUE / sampleRate) return Long.MAX_VALUE;
        return whole * sampleRate + (remainder * sampleRate) / 1000000L;
    }

    static long microsToFrameCeil(long micros, int sampleRate) {
        if (micros <= 0L) return 0L;
        long whole = micros / 1000000L;
        long remainder = micros % 1000000L;
        if (whole > Long.MAX_VALUE / sampleRate) return Long.MAX_VALUE;
        long frames = whole * sampleRate;
        long fractional = remainder * sampleRate;
        return frames + (fractional + 999999L) / 1000000L;
    }

    static long frameToMicrosFloor(long frame, int sampleRate) {
        if (frame <= 0L) return 0L;
        long whole = frame / sampleRate;
        long remainder = frame % sampleRate;
        if (whole > Long.MAX_VALUE / 1000000L) return Long.MAX_VALUE;
        return whole * 1000000L + (remainder * 1000000L) / sampleRate;
    }

    static long frameToMicrosCeil(long frame, int sampleRate) {
        if (frame <= 0L) return 0L;
        long whole = frame / sampleRate;
        long remainder = frame % sampleRate;
        if (whole > Long.MAX_VALUE / 1000000L) return Long.MAX_VALUE;
        long micros = whole * 1000000L;
        long fractional = remainder * 1000000L;
        return micros + (fractional + sampleRate - 1L) / sampleRate;
    }

    private StereoPcm stereo(int[] left, int[] right) {
        short[] interleaved = new short[left.length * 2];
        for (int frame = 0; frame < left.length; frame++) {
            interleaved[frame * 2] = (short) clamp(left[frame], -32768, 32767);
            interleaved[frame * 2 + 1] = (short) clamp(right[frame], -32768, 32767);
        }
        return new StereoPcm(sampleRate, interleaved);
    }

    static int saturatingAdd(int left, int right) {
        long value = (long) left + right;
        if (value > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        if (value < Integer.MIN_VALUE) return Integer.MIN_VALUE;
        return (int) value;
    }

    static int clamp(int value, int min, int max) {
        return value < min ? min : (value > max ? max : value);
    }

    static long safeMultiply(long left, long right) {
        if (left <= 0L || right <= 0L) return 0L;
        if (left > Long.MAX_VALUE / right) return Long.MAX_VALUE;
        return left * right;
    }

    static long safeAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
    }
}
