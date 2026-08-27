package audio;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import mld.semantic.AudioProgram;
import mld.semantic.NativeProgram;

/** Decoder/mixer coordinator for the strictly verified legacy MFiAudio 0x8001 path. */
public final class AudioRenderer {
    private static final int DEFAULT_TRACK_VOLUME = 127;
    private static final int DEFAULT_NOTE_LEVEL = 127;
    private static final int DEFAULT_PART_VOLUME = 127;
    private static final int DEFAULT_GLOBAL_VOLUME = 64;
    private static final int DEFAULT_TRACK_PAN = 64;
    private static final int DEFAULT_PART_PAN = 64;

    public boolean hasRenderableAudio(NativeProgram program) {
        if (program == null || hasBlockingUnsupportedSampledPath(program)) {
            return false;
        }
        for (AudioProgram.AudioAction action : program.audio.actions) {
            if (isVerifiedLegacyStart(action)) {
                return true;
            }
        }
        return false;
    }

    public StereoPcm render(NativeProgram program) {
        return preparePlayback(program).renderLinear();
    }

    /** Explicit output-rate conversion. Native rendering uses {@link #render(NativeProgram)}. */
    public StereoPcm render(NativeProgram program, int targetRate) {
        if (targetRate <= 0) {
            throw new IllegalArgumentException("targetRate <= 0");
        }
        return preparePlayback(program, targetRate).renderLinear();
    }

    public AudioPlaybackSource preparePlayback(NativeProgram program) {
        if (program == null) {
            throw new IllegalArgumentException("program == null");
        }
        return preparePlayback(program, nativeSampleRate(program));
    }

    public int nativeSampleRate(NativeProgram program) {
        requireVerifiedProgram(program);
        int rate = 0;
        for (AudioProgram.AudioAction action : program.audio.actions) {
            if (isVerifiedLegacyStart(action)) {
                rate = Math.max(rate, action.sampleRate);
            }
        }
        if (rate <= 0) {
            throw new IllegalArgumentException(
                    "Native program contains no verified 0x8001 sampled-audio start");
        }
        return rate;
    }

    /** Estimates the rendered linear duration without decoding or resampling PCM payloads. */
    public long estimateLinearDurationMillis(NativeProgram program) {
        int targetRate = nativeSampleRate(program);
        long semanticEndMicros = program.timing.rawTickToMicros(program.semanticEndRawTick);
        long linearEndFrame = Math.max(
                1L,
                AudioPlaybackSource.microsToFrameCeil(semanticEndMicros, targetRate));
        for (AudioProgram.AudioAction action : program.audio.actions) {
            if (!isVerifiedLegacyStart(action)) {
                continue;
            }
            long decodedFrames = AudioPlaybackSource.safeMultiply(action.durationByteCount, 2L);
            long renderedFrames = PcmResampler.resampledFrameCount(
                    decodedFrames, action.sampleRate, targetRate);
            long startMicros = program.timing.rawTickToMicros(action.rawTick);
            long startFrame = AudioPlaybackSource.microsToFrameFloor(startMicros, targetRate);
            linearEndFrame = Math.max(
                    linearEndFrame,
                    AudioPlaybackSource.safeAdd(startFrame, renderedFrames));
        }
        long durationMicros = AudioPlaybackSource.frameToMicrosCeil(linearEndFrame, targetRate);
        return durationMicros / 1000L + (durationMicros % 1000L == 0L ? 0L : 1L);
    }

    private AudioPlaybackSource preparePlayback(NativeProgram program, int targetRate) {
        requireVerifiedProgram(program);
        Map<Integer, Integer> partVolumes = new HashMap<Integer, Integer>();
        Map<Integer, Integer> partPans = new HashMap<Integer, Integer>();
        List<AudioPlaybackSource.Voice> voices = new ArrayList<AudioPlaybackSource.Voice>();
        int globalVolume = DEFAULT_GLOBAL_VOLUME;

        for (AudioProgram.AudioAction action : program.audio.actions) {
            if (isVerifiedPartVolume(action)) {
                partVolumes.put(Integer.valueOf(action.logicalChannel), Integer.valueOf(action.value));
                continue;
            }
            if (isVerifiedPartPan(action)) {
                partPans.put(Integer.valueOf(action.logicalChannel), Integer.valueOf(action.value));
                continue;
            }
            if (action.kind == AudioProgram.ActionKind.GLOBAL_LEVEL && action.value >= 0) {
                globalVolume = action.value;
                continue;
            }
            if (!isVerifiedLegacyStart(action)) {
                continue;
            }

            short[] decoded = MfiG726Decoder.decode4BitLittleEndian(action.copyEncodedPayload());
            short[] pcm = action.sampleRate == targetRate
                    ? decoded
                    : PcmResampler.resampleMono(decoded, action.sampleRate, targetRate);
            int partVolume = valueOrDefault(partVolumes, action.logicalChannel, DEFAULT_PART_VOLUME);
            int partPan = valueOrDefault(partPans, action.logicalChannel, DEFAULT_PART_PAN);
            int leftGain = MfiAudioMixer.leftGainCoefficient(
                    DEFAULT_TRACK_VOLUME,
                    DEFAULT_NOTE_LEVEL,
                    partVolume,
                    globalVolume,
                    DEFAULT_TRACK_PAN,
                    partPan);
            int rightGain = MfiAudioMixer.rightGainCoefficient(
                    DEFAULT_TRACK_VOLUME,
                    DEFAULT_NOTE_LEVEL,
                    partVolume,
                    globalVolume,
                    DEFAULT_TRACK_PAN,
                    partPan);
            long startMicros = program.timing.rawTickToMicros(action.rawTick);
            voices.add(new AudioPlaybackSource.Voice(
                    startMicros,
                    AudioPlaybackSource.microsToFrameFloor(startMicros, targetRate),
                    pcm,
                    leftGain,
                    rightGain));
        }

        long semanticEndMicros = program.timing.rawTickToMicros(program.semanticEndRawTick);
        long loopStartMicros = program.loop.hasLoop
                ? program.timing.rawTickToMicros(program.loop.loopStartRawTick)
                : 0L;
        long loopEndMicros = program.loop.hasLoop
                ? program.timing.rawTickToMicros(program.loop.loopEndRawTick)
                : semanticEndMicros;
        return new AudioPlaybackSource(
                targetRate,
                semanticEndMicros,
                program.loop,
                loopStartMicros,
                loopEndMicros,
                voices);
    }

    private static void requireVerifiedProgram(NativeProgram program) {
        if (program == null) {
            throw new IllegalArgumentException("program == null");
        }
        if (hasBlockingUnsupportedSampledPath(program)) {
            throw new IllegalArgumentException(
                    "Native program contains sampled-audio execution outside the verified 71:84 renderer profile");
        }
    }

    private static boolean hasBlockingUnsupportedSampledPath(NativeProgram program) {
        for (AudioProgram.AudioAction action : program.audio.actions) {
            if (action.kind == AudioProgram.ActionKind.RESOURCE_START) {
                return true;
            }
            if (action.sourceKind != AudioProgram.SourceKind.MACHINE_DEPENDENT) {
                continue;
            }
            if (action.kind == AudioProgram.ActionKind.SLOT_START
                    || action.kind == AudioProgram.ActionKind.SLOT_LOAD
                    || action.kind == AudioProgram.ActionKind.SLOT_LOAD_AND_START
                    || action.kind == AudioProgram.ActionKind.SLOT_CONTROL) {
                if (!isVerifiedLegacyStart(action)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isVerifiedPartVolume(AudioProgram.AudioAction action) {
        return action.sourceKind == AudioProgram.SourceKind.MACHINE_DEPENDENT
                && action.descriptorIndex == 18
                && action.handlerId == 0x000
                && action.kind == AudioProgram.ActionKind.CHANNEL_LEVEL
                && action.logicalChannel >= 0;
    }

    private static boolean isVerifiedPartPan(AudioProgram.AudioAction action) {
        return action.sourceKind == AudioProgram.SourceKind.MACHINE_DEPENDENT
                && action.descriptorIndex == 21
                && action.handlerId == 0x105
                && action.kind == AudioProgram.ActionKind.CHANNEL_PAN
                && action.logicalChannel >= 0;
    }

    private static boolean isVerifiedLegacyStart(AudioProgram.AudioAction action) {
        return action.rendererSupport == AudioProgram.RendererSupport.VERIFIED_8001_4BIT
                && action.sourceKind == AudioProgram.SourceKind.MACHINE_DEPENDENT
                && action.descriptorIndex == 23
                && action.handlerId == 0x106
                && action.kind == AudioProgram.ActionKind.SLOT_LOAD_AND_START
                && action.audioType == AudioProgram.AudioType.MFI_8001
                && action.operation == 1
                && action.controlFlag == 0
                && action.codedBits == 4
                && action.channelCount == 1
                && (action.formatCode == 5 || action.formatCode == 13 || action.formatCode == 21)
                && action.sampleRate > 0;
    }

    private static int valueOrDefault(Map<Integer, Integer> values, int key, int defaultValue) {
        Integer value = values.get(Integer.valueOf(key));
        return value == null ? defaultValue : value.intValue();
    }
}
