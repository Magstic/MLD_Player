package audio;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import mld.semantic.AudioProgram;
import mld.semantic.NativeProgram;

/** Decoder/mixer coordinator for the verified legacy MFiAudio 0x8001 sampled path. */
public final class AudioRenderer {
    private static final int DEFAULT_RESOURCE_LEVEL = 127;
    private static final int DEFAULT_START_LEVEL = 127;
    private static final int DEFAULT_CHANNEL_LEVEL = 127;
    private static final int DEFAULT_GLOBAL_LEVEL = 64;
    private static final int DEFAULT_RESOURCE_PAN = 64;
    private static final int DEFAULT_CHANNEL_PAN = 64;
    private static final int DEFAULT_ROUTE = 0;

    public boolean hasRenderableAudio(NativeProgram program) {
        if (program == null || hasBlockingUnsupportedSampledPath(program)) return false;
        for (AudioProgram.AudioAction action : program.audio.actions) {
            if (isVerifiedMachineStart(action) || isVerifiedResourceStart(action)) return true;
        }
        return false;
    }

    public StereoPcm render(NativeProgram program) {
        return preparePlayback(program).renderLinear();
    }

    /** Explicit host output-rate conversion after exact native 32-kHz legacy rendering. */
    public StereoPcm render(NativeProgram program, int targetRate) {
        if (targetRate <= 0) throw new IllegalArgumentException("targetRate <= 0");
        StereoPcm nativePcm = render(program);
        if (targetRate == nativePcm.getSampleRate()) return nativePcm;
        return resampleFinalPcm(nativePcm, targetRate);
    }

    public AudioPlaybackSource preparePlayback(NativeProgram program) {
        requireVerifiedProgram(program);
        return prepareNativePlayback(program);
    }

    public int nativeSampleRate(NativeProgram program) {
        requireVerifiedProgram(program);
        if (!hasAnyVerifiedStart(program)) {
            throw new IllegalArgumentException("Native program contains no verified 0x8001 sampled-audio start");
        }
        return AudioPlaybackSource.NATIVE_SAMPLE_RATE;
    }

    /** Estimates rendered linear duration from exact 0x8001 profile frame arithmetic. */
    public long estimateLinearDurationMillis(NativeProgram program) {
        int targetRate = nativeSampleRate(program);
        long semanticEndMicros = program.timing.rawTickToMicros(program.semanticEndRawTick);
        long linearEndFrame = Math.max(
                1L, AudioPlaybackSource.microsToFrameCeil(semanticEndMicros, targetRate));
        for (AudioProgram.AudioAction action : program.audio.actions) {
            if (!isVerifiedMachineStart(action) && !isVerifiedResourceStart(action)) continue;
            long renderedFrames = exactDecodedFrameCount(action.durationByteCount,
                    action.sampleRate, action.codedBits, action.channelCount);
            long startMicros = program.timing.rawTickToMicros(action.rawTick);
            long startFrame = AudioPlaybackSource.microsToFrameFloor(startMicros, targetRate);
            linearEndFrame = Math.max(linearEndFrame,
                    AudioPlaybackSource.safeAdd(startFrame, renderedFrames));
        }
        long durationMicros = AudioPlaybackSource.frameToMicrosCeil(linearEndFrame, targetRate);
        return durationMicros / 1000L + (durationMicros % 1000L == 0L ? 0L : 1L);
    }

    private AudioPlaybackSource prepareNativePlayback(NativeProgram program) {
        Map<Integer, Integer> resourceChannelLevels = new HashMap<Integer, Integer>();
        Map<Integer, Integer> resourceChannelPans = new HashMap<Integer, Integer>();
        Map<Integer, Integer> resourceChannelRoutes = new HashMap<Integer, Integer>();
        Map<Integer, Integer> machinePartLevels = new HashMap<Integer, Integer>();
        Map<Integer, Integer> machinePartPans = new HashMap<Integer, Integer>();
        Map<Integer, DecodedSampledResource> activeResourceCache =
                new HashMap<Integer, DecodedSampledResource>();
        List<MutableVoice> voiceBuilders = new ArrayList<MutableVoice>();
        int machineGlobalLevel = DEFAULT_GLOBAL_LEVEL;

        for (AudioProgram.AudioAction action : program.audio.actions) {
            if (action.kind == AudioProgram.ActionKind.CHANNEL_LEVEL && action.logicalChannel >= 0) {
                if (action.sourceKind == AudioProgram.SourceKind.RESOURCE_7F) {
                    resourceChannelLevels.put(
                            Integer.valueOf(action.logicalChannel), Integer.valueOf(action.value));
                    updateLiveResourceVoices(
                            voiceBuilders, program, action,
                            resourceChannelLevels, resourceChannelPans);
                } else if (isVerifiedPartVolume(action)) {
                    machinePartLevels.put(
                            Integer.valueOf(action.logicalChannel), Integer.valueOf(action.value));
                }
                continue;
            }
            if (action.kind == AudioProgram.ActionKind.CHANNEL_PAN && action.logicalChannel >= 0) {
                if (action.sourceKind == AudioProgram.SourceKind.RESOURCE_7F) {
                    resourceChannelPans.put(
                            Integer.valueOf(action.logicalChannel), Integer.valueOf(action.value));
                    updateLiveResourceVoices(
                            voiceBuilders, program, action,
                            resourceChannelLevels, resourceChannelPans);
                } else if (isVerifiedPartPan(action)) {
                    machinePartPans.put(
                            Integer.valueOf(action.logicalChannel), Integer.valueOf(action.value));
                }
                continue;
            }
            if (action.kind == AudioProgram.ActionKind.CHANNEL_ROUTE && action.logicalChannel >= 0) {
                resourceChannelRoutes.put(
                        Integer.valueOf(action.logicalChannel), Integer.valueOf(action.value));
                continue;
            }
            if (action.kind == AudioProgram.ActionKind.GLOBAL_LEVEL && action.value >= 0) {
                machineGlobalLevel = action.value;
                continue;
            }

            DecodedSampledResource decoded;
            int startLevel;
            int sampledGlobalLevel;
            if (isVerifiedResourceStart(action)) {
                AudioProgram.ResourceCatalogEntry entry = resourceEntry(program, action.linkedCatalogIndex);
                if (entry == null || entry.sampledResource == null) {
                    throw new IllegalArgumentException("verified resource start has no typed sampled resource");
                }
                Integer key = Integer.valueOf(entry.catalogIndex);
                decoded = activeResourceCache.get(key);
                if (decoded == null) {
                    AudioProgram.SampledResource resource = entry.sampledResource;
                    decoded = Mfi8001Decoder.decode(
                            resource.copyEncodedPayload(), resource.sampleRate,
                            resource.codedBits, resource.channelCount);
                    activeResourceCache.put(key, decoded);
                }
                startLevel = action.value >= 0 ? action.value : 126;
                sampledGlobalLevel = DEFAULT_GLOBAL_LEVEL;
            } else if (isVerifiedMachineStart(action)) {
                decoded = Mfi8001Decoder.decode(
                        action.copyEncodedPayload(), action.sampleRate,
                        action.codedBits, action.channelCount);
                startLevel = DEFAULT_START_LEVEL;
                sampledGlobalLevel = machineGlobalLevel;
            } else {
                continue;
            }

            boolean resourceStart = isVerifiedResourceStart(action);
            Map<Integer, Integer> levels = resourceStart
                    ? resourceChannelLevels : machinePartLevels;
            Map<Integer, Integer> pans = resourceStart
                    ? resourceChannelPans : machinePartPans;
            int channelLevel = valueOrDefault(
                    levels, action.logicalChannel, DEFAULT_CHANNEL_LEVEL);
            int channelPan = valueOrDefault(
                    pans, action.logicalChannel, DEFAULT_CHANNEL_PAN);
            int route = resourceStart
                    ? valueOrDefault(resourceChannelRoutes, action.logicalChannel, DEFAULT_ROUTE)
                    : DEFAULT_ROUTE;
            if (route != 0) {
                throw new IllegalArgumentException(
                        "Sampled route " + route + " is outside the enabled exact route-0 output path");
            }

            int leftGain = MfiAudioMixer.leftVoiceGain(
                    DEFAULT_RESOURCE_LEVEL, startLevel, channelLevel, sampledGlobalLevel,
                    DEFAULT_RESOURCE_PAN, channelPan);
            int rightGain = MfiAudioMixer.rightVoiceGain(
                    DEFAULT_RESOURCE_LEVEL, startLevel, channelLevel, sampledGlobalLevel,
                    DEFAULT_RESOURCE_PAN, channelPan);
            long startMicros = program.timing.rawTickToMicros(action.rawTick);
            long startFrame = AudioPlaybackSource.microsToFrameFloor(
                    startMicros, AudioPlaybackSource.NATIVE_SAMPLE_RATE);
            voiceBuilders.add(new MutableVoice(
                    startMicros,
                    startFrame,
                    decoded,
                    action.logicalChannel,
                    startLevel,
                    sampledGlobalLevel,
                    resourceStart,
                    leftGain,
                    rightGain));
        }

        List<AudioPlaybackSource.Voice> voices = new ArrayList<AudioPlaybackSource.Voice>();
        for (MutableVoice builder : voiceBuilders) voices.add(builder.freeze());

        long semanticEndMicros = program.timing.rawTickToMicros(program.semanticEndRawTick);
        long loopStartMicros = program.loop.hasLoop
                ? program.timing.rawTickToMicros(program.loop.loopStartRawTick) : 0L;
        long loopEndMicros = program.loop.hasLoop
                ? program.timing.rawTickToMicros(program.loop.loopEndRawTick) : semanticEndMicros;
        return new AudioPlaybackSource(
                AudioPlaybackSource.NATIVE_SAMPLE_RATE,
                semanticEndMicros,
                program.loop,
                loopStartMicros,
                loopEndMicros,
                voices);
    }

    private static void updateLiveResourceVoices(
            List<MutableVoice> voices,
            NativeProgram program,
            AudioProgram.AudioAction action,
            Map<Integer, Integer> channelLevels,
            Map<Integer, Integer> channelPans) {
        long controlMicros = program.timing.rawTickToMicros(action.rawTick);
        long controlFrame = AudioPlaybackSource.microsToFrameFloor(
                controlMicros, AudioPlaybackSource.NATIVE_SAMPLE_RATE);
        int channelLevel = valueOrDefault(
                channelLevels, action.logicalChannel, DEFAULT_CHANNEL_LEVEL);
        int channelPan = valueOrDefault(
                channelPans, action.logicalChannel, DEFAULT_CHANNEL_PAN);
        for (MutableVoice voice : voices) {
            if (!voice.liveResourceControls || voice.logicalChannel != action.logicalChannel) continue;
            long sourceFrame = controlFrame - voice.startFrame;
            if (sourceFrame < 0L || sourceFrame >= voice.resource.getFrameCount()) continue;
            int leftGain = MfiAudioMixer.leftVoiceGain(
                    DEFAULT_RESOURCE_LEVEL, voice.startLevel, channelLevel, voice.globalLevel,
                    DEFAULT_RESOURCE_PAN, channelPan);
            int rightGain = MfiAudioMixer.rightVoiceGain(
                    DEFAULT_RESOURCE_LEVEL, voice.startLevel, channelLevel, voice.globalLevel,
                    DEFAULT_RESOURCE_PAN, channelPan);
            voice.addGainSegment((int)sourceFrame, leftGain, rightGain);
        }
    }

    private static final class MutableVoice {
        final long startMicros;
        final long startFrame;
        final DecodedSampledResource resource;
        final int logicalChannel;
        final int startLevel;
        final int globalLevel;
        final boolean liveResourceControls;
        final List<AudioPlaybackSource.GainSegment> gainSegments =
                new ArrayList<AudioPlaybackSource.GainSegment>();

        MutableVoice(
                long startMicros,
                long startFrame,
                DecodedSampledResource resource,
                int logicalChannel,
                int startLevel,
                int globalLevel,
                boolean liveResourceControls,
                int leftGain,
                int rightGain) {
            this.startMicros = startMicros;
            this.startFrame = startFrame;
            this.resource = resource;
            this.logicalChannel = logicalChannel;
            this.startLevel = startLevel;
            this.globalLevel = globalLevel;
            this.liveResourceControls = liveResourceControls;
            addGainSegment(0, leftGain, rightGain);
        }

        void addGainSegment(int sourceFrame, int leftGain, int rightGain) {
            int last = gainSegments.size() - 1;
            if (last >= 0 && gainSegments.get(last).sourceFrame == sourceFrame) {
                gainSegments.set(last, new AudioPlaybackSource.GainSegment(
                        sourceFrame, leftGain, rightGain));
            } else {
                gainSegments.add(new AudioPlaybackSource.GainSegment(
                        sourceFrame, leftGain, rightGain));
            }
        }

        AudioPlaybackSource.Voice freeze() {
            return new AudioPlaybackSource.Voice(
                    startMicros, startFrame, resource, gainSegments);
        }
    }

    private static AudioProgram.ResourceCatalogEntry resourceEntry(
            NativeProgram program, int catalogIndex) {
        for (AudioProgram.ResourceCatalogEntry entry : program.audio.resourceCatalog) {
            if (entry.catalogIndex == catalogIndex) return entry;
        }
        return null;
    }

    private static long exactDecodedFrameCount(
            int compressedBytes, int sampleRate, int codedBits, int channels) {
        if (compressedBytes < 0 || sampleRate <= 0 || codedBits <= 0 || channels <= 0) return 0L;
        long bytesPerCompressedChannel = channels == 2
                ? (compressedBytes >> 1) : compressedBytes;
        long sourceSamplesPerChannel = (bytesPerCompressedChannel * 8L) / codedBits;
        return AudioPlaybackSource.safeMultiply(sourceSamplesPerChannel,
                AudioPlaybackSource.NATIVE_SAMPLE_RATE / sampleRate);
    }

    private static StereoPcm resampleFinalPcm(StereoPcm input, int targetRate) {
        short[] interleaved = input.copyInterleavedPcm16();
        int frames = interleaved.length / 2;
        short[] left = new short[frames];
        short[] right = new short[frames];
        for (int i = 0; i < frames; i++) {
            left[i] = interleaved[i * 2];
            right[i] = interleaved[i * 2 + 1];
        }
        short[] outLeft = PcmResampler.resampleMono(left, input.getSampleRate(), targetRate);
        short[] outRight = PcmResampler.resampleMono(right, input.getSampleRate(), targetRate);
        int outFrames = Math.min(outLeft.length, outRight.length);
        short[] output = new short[outFrames * 2];
        for (int i = 0; i < outFrames; i++) {
            output[i * 2] = outLeft[i];
            output[i * 2 + 1] = outRight[i];
        }
        return new StereoPcm(targetRate, output);
    }

    private static void requireVerifiedProgram(NativeProgram program) {
        if (program == null) throw new IllegalArgumentException("program == null");
        if (hasBlockingUnsupportedSampledPath(program)) {
            throw new IllegalArgumentException(
                    "Native program contains sampled-audio execution outside the enabled exact 0x8001 route-0 profile");
        }
    }

    private static boolean hasBlockingUnsupportedSampledPath(NativeProgram program) {
        boolean hasResourceExecution = false;
        for (AudioProgram.AudioAction action : program.audio.actions) {
            if (action.kind == AudioProgram.ActionKind.RESOURCE_START) {
                hasResourceExecution = true;
                if (!isVerifiedResourceStart(action)) return true;
            }
        }
        for (AudioProgram.AudioAction action : program.audio.actions) {
            if (hasResourceExecution && action.kind == AudioProgram.ActionKind.RESOURCE_STOP) return true;
            if (hasResourceExecution
                    && action.kind == AudioProgram.ActionKind.CHANNEL_ROUTE
                    && action.value != 0) return true;
            if (action.sourceKind != AudioProgram.SourceKind.MACHINE_DEPENDENT) continue;
            if (action.kind == AudioProgram.ActionKind.SLOT_START
                    || action.kind == AudioProgram.ActionKind.SLOT_LOAD
                    || action.kind == AudioProgram.ActionKind.SLOT_LOAD_AND_START
                    || action.kind == AudioProgram.ActionKind.SLOT_CONTROL) {
                if (!isVerifiedMachineStart(action)) return true;
            }
        }
        return false;
    }

    private static boolean hasAnyVerifiedStart(NativeProgram program) {
        for (AudioProgram.AudioAction action : program.audio.actions) {
            if (isVerifiedMachineStart(action) || isVerifiedResourceStart(action)) return true;
        }
        return false;
    }

    private static boolean isVerifiedPartVolume(AudioProgram.AudioAction action) {
        return action.sourceKind == AudioProgram.SourceKind.MACHINE_DEPENDENT
                && action.descriptorIndex == 18
                && action.handlerId == 0x000
                && action.kind == AudioProgram.ActionKind.CHANNEL_LEVEL;
    }

    private static boolean isVerifiedPartPan(AudioProgram.AudioAction action) {
        return action.sourceKind == AudioProgram.SourceKind.MACHINE_DEPENDENT
                && action.descriptorIndex == 21
                && action.handlerId == 0x105
                && action.kind == AudioProgram.ActionKind.CHANNEL_PAN;
    }

    private static boolean isVerifiedResourceStart(AudioProgram.AudioAction action) {
        return action.sourceKind == AudioProgram.SourceKind.RESOURCE_7F
                && action.kind == AudioProgram.ActionKind.RESOURCE_START
                && action.rendererSupport == AudioProgram.RendererSupport.VERIFIED_8001_4BIT
                && action.audioType == AudioProgram.AudioType.MFI_8001
                && Mfi8001Decoder.supports(action.sampleRate, action.codedBits, action.channelCount)
                && action.linkedCatalogIndex >= 0;
    }

    private static boolean isVerifiedMachineStart(AudioProgram.AudioAction action) {
        return action.rendererSupport == AudioProgram.RendererSupport.VERIFIED_8001_4BIT
                && action.sourceKind == AudioProgram.SourceKind.MACHINE_DEPENDENT
                && action.descriptorIndex == 23
                && action.handlerId == 0x106
                && action.kind == AudioProgram.ActionKind.SLOT_LOAD_AND_START
                && action.audioType == AudioProgram.AudioType.MFI_8001
                && action.operation == 1
                && action.controlFlag == 0
                && Mfi8001Decoder.supports(action.sampleRate, action.codedBits, action.channelCount);
    }

    private static int valueOrDefault(Map<Integer, Integer> values, int key, int defaultValue) {
        Integer value = values.get(Integer.valueOf(key));
        return value == null ? defaultValue : value.intValue();
    }
}
