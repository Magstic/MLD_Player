package audio;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import mld.semantic.LoopModel;

/** Immutable exact sampled-audio voice schedule used by offline and transport rendering. */
public final class AudioPlaybackSource {
    public static final int NATIVE_SAMPLE_RATE = 32000;

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
        if (sampleRate != NATIVE_SAMPLE_RATE) {
            throw new IllegalArgumentException("legacy sampled mix rate must be 32000 Hz");
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
            end = Math.max(end, safeAdd(voice.startFrame, voice.frameCount));
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
        int frames = (int)Math.max(1L, linearFrameCount);
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
        final DecodedSampledResource resource;
        final int frameCount;
        final GainSegment[] gainSegments;

        Voice(
                long startMicros,
                long startFrame,
                DecodedSampledResource resource,
                int frameCount,
                List<GainSegment> gainSegments) {
            if (resource == null) throw new IllegalArgumentException("resource == null");
            if (gainSegments == null || gainSegments.isEmpty()) {
                throw new IllegalArgumentException("gainSegments is empty");
            }
            if (frameCount < 0 || frameCount > resource.getFrameCount()) {
                throw new IllegalArgumentException("invalid voice frame count");
            }
            this.startMicros = Math.max(0L, startMicros);
            this.startFrame = Math.max(0L, startFrame);
            this.resource = resource;
            this.frameCount = frameCount;
            this.gainSegments = gainSegments.toArray(new GainSegment[gainSegments.size()]);
            if (this.gainSegments[0].sourceFrame != 0) {
                throw new IllegalArgumentException("first gain segment must start at source frame 0");
            }
        }
    }

    static final class GainSegment {
        final int sourceFrame;
        final int leftGain;
        final int rightGain;

        GainSegment(int sourceFrame, int leftGain, int rightGain) {
            if (sourceFrame < 0) throw new IllegalArgumentException("sourceFrame < 0");
            this.sourceFrame = sourceFrame;
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
        long voiceEnd = safeAdd(instanceStartFrame, voice.frameCount);
        long overlapStart = Math.max(chunkStartFrame, instanceStartFrame);
        long overlapEnd = Math.min(chunkEndFrame, voiceEnd);
        if (overlapStart >= overlapEnd) return;

        int sourceIndex = (int)(overlapStart - instanceStartFrame);
        int targetIndex = (int)(overlapStart - chunkStartFrame);
        int count = (int)(overlapEnd - overlapStart);
        int segmentIndex = gainSegmentAt(voice.gainSegments, sourceIndex);
        GainSegment segment = voice.gainSegments[segmentIndex];
        for (int i = 0; i < count; i++) {
            int sourceFrame = sourceIndex + i;
            while (segmentIndex + 1 < voice.gainSegments.length
                    && voice.gainSegments[segmentIndex + 1].sourceFrame <= sourceFrame) {
                segment = voice.gainSegments[++segmentIndex];
            }
            int mixedLeft = MfiAudioMixer.applyVoiceGain(
                    voice.resource.leftAt(sourceFrame), segment.leftGain);
            int mixedRight = MfiAudioMixer.applyVoiceGain(
                    voice.resource.rightAt(sourceFrame), segment.rightGain);
            left[targetIndex + i] += mixedLeft;
            right[targetIndex + i] += mixedRight;
        }
    }

    private static int gainSegmentAt(GainSegment[] segments, int sourceFrame) {
        int low = 0;
        int high = segments.length - 1;
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (segments[mid].sourceFrame <= sourceFrame) low = mid;
            else high = mid - 1;
        }
        return low;
    }

    static byte[] interleavedBytes(int[] routeLeft, int[] routeRight) {
        byte[] bytes = new byte[routeLeft.length * 4];
        for (int frame = 0; frame < routeLeft.length; frame++) {
            short l = quantizeRoute0(routeLeft[frame]);
            short r = quantizeRoute0(routeRight[frame]);
            int offset = frame * 4;
            bytes[offset] = (byte)(l & 0xFF);
            bytes[offset + 1] = (byte)((l >>> 8) & 0xFF);
            bytes[offset + 2] = (byte)(r & 0xFF);
            bytes[offset + 3] = (byte)((r >>> 8) & 0xFF);
        }
        return bytes;
    }

    private StereoPcm stereo(int[] routeLeft, int[] routeRight) {
        short[] interleaved = new short[routeLeft.length * 2];
        for (int frame = 0; frame < routeLeft.length; frame++) {
            interleaved[frame * 2] = quantizeRoute0(routeLeft[frame]);
            interleaved[frame * 2 + 1] = quantizeRoute0(routeRight[frame]);
        }
        return new StereoPcm(sampleRate, interleaved);
    }

    /** plugin route0 <<2, SoundLib arithmetic >>8, then legacy +/-32767 clamp. */
    static short quantizeRoute0(int routeSample) {
        int bus = routeSample << 2;
        int shifted = bus >> 8;
        return (short)clamp(shifted, -32767, 32767);
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
