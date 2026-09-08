package audio;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import mld.semantic.AudioProgram;
import mld.semantic.LoopModel;
import mld.semantic.NativeProgram;

/** Decoder/mixer coordinator for verified legacy MFiAudio sampled paths. */
public final class AudioRenderer {
    private static final int NATIVE_VOICE_COUNT = 16;
    private static final int DEFAULT_RESOURCE_LEVEL = 127;
    private static final int DEFAULT_START_LEVEL = 127;
    private static final int DEFAULT_CHANNEL_LEVEL = 127;
    private static final int DEFAULT_RESOURCE_PAN = 64;
    private static final int DEFAULT_CHANNEL_PAN = 64;
    private static final int DEFAULT_ROUTE = 0;

    public boolean hasRenderableAudio(NativeProgram program) {
        return program != null
                && !hasBlockingUnsupportedSampledPath(program)
                && hasPeriodicNativePcmTemplate(program)
                && hasResolvedStart(program);
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
        if (!hasResolvedStart(program)) {
            throw new IllegalArgumentException("Native program contains no sampled-audio voice");
        }
        return AudioPlaybackSource.NATIVE_SAMPLE_RATE;
    }

    /** Estimates linear duration from semantic start/stop actions without decoding sample payloads. */
    public long estimateLinearDurationMillis(NativeProgram program) {
        requireVerifiedProgram(program);
        long frames = estimateLinearFrameCount(program);
        long micros = AudioPlaybackSource.frameToMicrosCeil(
                frames, AudioPlaybackSource.NATIVE_SAMPLE_RATE);
        return micros / 1000L + (micros % 1000L == 0L ? 0L : 1L);
    }

    private static boolean hasResolvedStart(NativeProgram program) {
        for (AudioProgram.AudioAction action : program.audio.executionActions) {
            if (action.kind == AudioProgram.ActionKind.RESOURCE_START
                    || action.kind == AudioProgram.ActionKind.SLOT_START) {
                return true;
            }
        }
        return false;
    }

    private static long estimateLinearFrameCount(NativeProgram program) {
        List<VoiceLifetime> voices = new ArrayList<VoiceLifetime>();
        for (AudioProgram.AudioAction action : program.audio.executionActions) {
            long actionFrame = AudioPlaybackSource.microsToFrameFloor(
                    program.timing.rawTickToMicros(action.rawTick),
                    AudioPlaybackSource.NATIVE_SAMPLE_RATE);
            if (stopVoicesForAction(voices, action, actionFrame)) continue;
            if (action.kind != AudioProgram.ActionKind.RESOURCE_START
                    && action.kind != AudioProgram.ActionKind.SLOT_START) {
                continue;
            }

            long frameCount = resolvedFrameCount(program, action);
            if (action.kind == AudioProgram.ActionKind.SLOT_START) {
                frameCount = durationLimitedFrameCount(frameCount, action.durationMs);
            }
            VoiceLifetime started = new VoiceLifetime(
                    actionFrame,
                    frameCount,
                    action.logicalChannel,
                    action.kind == AudioProgram.ActionKind.RESOURCE_START
                            ? action.resourceIndex : -1,
                    action.kind == AudioProgram.ActionKind.SLOT_START ? action.slot : -1);
            stopOldestIfNativePoolFull(voices, started);
            voices.add(started);
        }

        long semanticEndMicros = program.timing.rawTickToMicros(program.semanticEndRawTick);
        long end = Math.max(1L, AudioPlaybackSource.microsToFrameCeil(
                semanticEndMicros, AudioPlaybackSource.NATIVE_SAMPLE_RATE));
        for (VoiceLifetime voice : voices) {
            end = Math.max(end, AudioPlaybackSource.safeAdd(voice.startFrame, voice.frameCount));
        }
        return end;
    }

    private static long resolvedFrameCount(
            NativeProgram program, AudioProgram.AudioAction action) {
        if (action.kind == AudioProgram.ActionKind.RESOURCE_START) {
            AudioProgram.ResourceCatalogEntry entry = resourceEntry(program, action.linkedCatalogIndex);
            if (entry == null || entry.sampledResource == null) {
                throw new IllegalArgumentException("verified resource start has no typed sampled resource");
            }
            AudioProgram.SampledResource resource = entry.sampledResource;
            return Mfi8001Decoder.decodedFrameCount(
                    resource.encodedPayloadLength(), resource.sampleRate,
                    resource.codedBits, resource.channelCount);
        }
        if (action.audioType == AudioProgram.AudioType.MFI_8001) {
            return Mfi8001Decoder.decodedFrameCount(
                    action.encodedPayloadLength(), action.sampleRate,
                    action.codedBits, action.channelCount);
        }
        if (action.audioType == AudioProgram.AudioType.MFI_8002) {
            return Mfi8002Decoder.decodedFrameCount(
                    action.encodedPayloadLength(), action.sampleRate,
                    action.codedBits, action.channelCount);
        }
        throw new IllegalArgumentException("resolved sampled slot has unsupported audio type");
    }


    private AudioPlaybackSource prepareNativePlayback(NativeProgram program) {
        Map<Integer, Integer> channelLevels = new HashMap<Integer, Integer>();
        Map<Integer, Integer> channelPans = new HashMap<Integer, Integer>();
        Map<Integer, Integer> resourceChannelRoutes = new HashMap<Integer, Integer>();
        Map<Integer, DecodedSampledResource> activeResourceCache =
                new HashMap<Integer, DecodedSampledResource>();
        List<MutableVoice> voiceBuilders = new ArrayList<MutableVoice>();
        int resourceLevel = DEFAULT_RESOURCE_LEVEL;
        int resourcePan = DEFAULT_RESOURCE_PAN;
        int globalSampledLevel = program.audio.globalSampledLevel;

        for (AudioProgram.AudioAction action : program.audio.executionActions) {
            if (action.kind == AudioProgram.ActionKind.RESOURCE_LEVEL && action.value >= 0) {
                resourceLevel = action.value;
                updateLiveVoices(
                        voiceBuilders, program, action, -1,
                        resourceLevel, resourcePan, channelLevels, channelPans);
                continue;
            }
            if (action.kind == AudioProgram.ActionKind.RESOURCE_PAN && action.value >= 0) {
                resourcePan = action.value;
                updateLiveVoices(
                        voiceBuilders, program, action, -1,
                        resourceLevel, resourcePan, channelLevels, channelPans);
                continue;
            }
            if (action.kind == AudioProgram.ActionKind.CHANNEL_LEVEL) {
                channelLevels.put(
                        Integer.valueOf(action.logicalChannel), Integer.valueOf(action.value));
                updateLiveVoices(
                        voiceBuilders, program, action, action.logicalChannel,
                        resourceLevel, resourcePan, channelLevels, channelPans);
                continue;
            }
            if (action.kind == AudioProgram.ActionKind.CHANNEL_PAN) {
                channelPans.put(
                        Integer.valueOf(action.logicalChannel), Integer.valueOf(action.value));
                updateLiveVoices(
                        voiceBuilders, program, action, action.logicalChannel,
                        resourceLevel, resourcePan, channelLevels, channelPans);
                continue;
            }
            if (action.kind == AudioProgram.ActionKind.CHANNEL_ROUTE) {
                resourceChannelRoutes.put(
                        Integer.valueOf(action.logicalChannel), Integer.valueOf(action.value));
                continue;
            }
            if (action.kind == AudioProgram.ActionKind.RESOURCE_STOP
                    || action.kind == AudioProgram.ActionKind.SLOT_RELEASE
                    || action.kind == AudioProgram.ActionKind.SLOT_STOP) {
                long actionFrame = AudioPlaybackSource.microsToFrameFloor(
                        program.timing.rawTickToMicros(action.rawTick),
                        AudioPlaybackSource.NATIVE_SAMPLE_RATE);
                stopVoicesForAction(voiceBuilders, action, actionFrame);
                continue;
            }

            boolean resourceStart = action.kind == AudioProgram.ActionKind.RESOURCE_START;
            boolean slotStart = action.kind == AudioProgram.ActionKind.SLOT_START;
            if (!resourceStart && !slotStart) continue;

            DecodedSampledResource decoded;
            int startLevel;
            int voiceFrameCount;
            int startLogicalChannel = action.logicalChannel;
            int startSlot = -1;
            if (resourceStart) {
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
                voiceFrameCount = decoded.getFrameCount();
            } else {
                decoded = decodeSlotStart(action);
                startLevel = action.value >= 0 ? action.value : DEFAULT_START_LEVEL;
                startSlot = action.slot;
                voiceFrameCount = durationLimitedFrameCount(decoded, action.durationMs);
            }

            int channelLevel = valueOrDefault(
                    channelLevels, startLogicalChannel, DEFAULT_CHANNEL_LEVEL);
            int channelPan = valueOrDefault(
                    channelPans, startLogicalChannel, DEFAULT_CHANNEL_PAN);
            int route = resourceStart
                    ? valueOrDefault(resourceChannelRoutes, startLogicalChannel, DEFAULT_ROUTE)
                    : DEFAULT_ROUTE;
            if (route != 0) {
                throw new IllegalArgumentException(
                        "Sampled route " + route + " is outside the enabled exact route-0 output path");
            }

            int leftGain = MfiAudioMixer.leftVoiceGain(
                    resourceLevel, startLevel, channelLevel, globalSampledLevel,
                    resourcePan, channelPan);
            int rightGain = MfiAudioMixer.rightVoiceGain(
                    resourceLevel, startLevel, channelLevel, globalSampledLevel,
                    resourcePan, channelPan);
            long startMicros = program.timing.rawTickToMicros(action.rawTick);
            long startFrame = AudioPlaybackSource.microsToFrameFloor(
                    startMicros, AudioPlaybackSource.NATIVE_SAMPLE_RATE);
            MutableVoice started = new MutableVoice(
                    startMicros,
                    startFrame,
                    decoded,
                    voiceFrameCount,
                    startLogicalChannel,
                    resourceStart ? action.resourceIndex : -1,
                    startSlot,
                    startLevel,
                    globalSampledLevel,
                    leftGain,
                    rightGain);
            stopOldestIfNativePoolFull(voiceBuilders, started);
            voiceBuilders.add(started);
        }

        List<AudioPlaybackSource.Voice> voices = new ArrayList<AudioPlaybackSource.Voice>();
        for (MutableVoice builder : voiceBuilders) voices.add(builder.freeze());

        long semanticEndMicros = program.timing.rawTickToMicros(program.semanticEndRawTick);
        LoopModel.InfiniteRegion infiniteLoop = program.loop.infiniteRegion;
        long loopStartMicros = infiniteLoop == null
                ? 0L : program.timing.rawTickToMicros(infiniteLoop.loopStartRawTick);
        long loopEndMicros = infiniteLoop == null
                ? semanticEndMicros : program.timing.rawTickToMicros(infiniteLoop.loopEndRawTick);
        return new AudioPlaybackSource(
                AudioPlaybackSource.NATIVE_SAMPLE_RATE,
                semanticEndMicros,
                program.loop,
                loopStartMicros,
                loopEndMicros,
                voices);
    }

    private static DecodedSampledResource decodeSlotStart(AudioProgram.AudioAction action) {
        byte[] encoded = action.copyEncodedPayload();
        if (action.audioType == AudioProgram.AudioType.MFI_8001) {
            return Mfi8001Decoder.decode(
                    encoded, action.sampleRate, action.codedBits, action.channelCount);
        }
        if (action.audioType == AudioProgram.AudioType.MFI_8002) {
            return Mfi8002Decoder.decode(
                    encoded, action.sampleRate, action.codedBits, action.channelCount);
        }
        throw new IllegalArgumentException("resolved sampled slot has unsupported audio type");
    }

    private static void updateLiveVoices(
            List<MutableVoice> voices,
            NativeProgram program,
            AudioProgram.AudioAction action,
            int logicalChannel,
            int resourceLevel,
            int resourcePan,
            Map<Integer, Integer> channelLevels,
            Map<Integer, Integer> channelPans) {
        long controlMicros = program.timing.rawTickToMicros(action.rawTick);
        long controlFrame = AudioPlaybackSource.microsToFrameFloor(
                controlMicros, AudioPlaybackSource.NATIVE_SAMPLE_RATE);
        for (MutableVoice voice : voices) {
            if (logicalChannel >= 0 && voice.logicalChannel != logicalChannel) continue;
            long sourceFrame = controlFrame - voice.startFrame;
            if (sourceFrame < 0L || sourceFrame >= voice.frameCount) continue;
            int channelLevel = valueOrDefault(
                    channelLevels, voice.logicalChannel, DEFAULT_CHANNEL_LEVEL);
            int channelPan = valueOrDefault(
                    channelPans, voice.logicalChannel, DEFAULT_CHANNEL_PAN);
            int leftGain = MfiAudioMixer.leftVoiceGain(
                    resourceLevel, voice.startLevel, channelLevel, voice.globalSampledLevel,
                    resourcePan, channelPan);
            int rightGain = MfiAudioMixer.rightVoiceGain(
                    resourceLevel, voice.startLevel, channelLevel, voice.globalSampledLevel,
                    resourcePan, channelPan);
            voice.addGainSegment((int)sourceFrame, leftGain, rightGain);
        }
    }

    private static boolean stopVoicesForAction(
            List<? extends VoiceLifetime> voices,
            AudioProgram.AudioAction action,
            long stopFrame) {
        boolean resourceStop = action.kind == AudioProgram.ActionKind.RESOURCE_STOP;
        boolean slotRelease = action.kind == AudioProgram.ActionKind.SLOT_RELEASE;
        boolean slotStop = action.kind == AudioProgram.ActionKind.SLOT_STOP;
        if (!resourceStop && !slotRelease && !slotStop) return false;

        for (VoiceLifetime voice : voices) {
            if (resourceStop
                    && voice.resourceIndex == action.resourceIndex
                    && voice.logicalChannel == action.logicalChannel) {
                voice.stopAt(stopFrame);
            } else if (slotRelease && voice.slot == action.slot) {
                voice.stopAt(stopFrame);
            } else if (slotStop
                    && voice.slot == action.slot
                    && voice.logicalChannel == action.logicalChannel) {
                voice.stopAt(stopFrame);
            }
        }
        return true;
    }

    private static void stopOldestIfNativePoolFull(
            List<? extends VoiceLifetime> voices, VoiceLifetime started) {
        int activeCount = 0;
        VoiceLifetime oldest = null;
        for (VoiceLifetime voice : voices) {
            if (!voice.isAllocatedAt(started.startFrame)) continue;
            if (oldest == null) oldest = voice;
            activeCount++;
        }
        if (activeCount >= NATIVE_VOICE_COUNT) {
            // MFiAudio 0x10001000 reuses the active entry with the lowest start serial.
            oldest.stopAt(started.startFrame);
        }
    }

    private static int durationLimitedFrameCount(DecodedSampledResource decoded, long durationMs) {
        return (int)durationLimitedFrameCount((long)decoded.getFrameCount(), durationMs);
    }

    private static long durationLimitedFrameCount(long decodedFrames, long durationMs) {
        if (durationMs < 0L) return decodedFrames;
        long durationFrames = AudioPlaybackSource.safeMultiply(
                durationMs, AudioPlaybackSource.NATIVE_SAMPLE_RATE / 1000L);
        return Math.min(decodedFrames, durationFrames);
    }

    private static class VoiceLifetime {
        final long startFrame;
        long frameCount;
        final int logicalChannel;
        final int resourceIndex;
        final int slot;
        boolean explicitlyStopped;

        VoiceLifetime(
                long startFrame,
                long frameCount,
                int logicalChannel,
                int resourceIndex,
                int slot) {
            this.startFrame = startFrame;
            this.frameCount = frameCount;
            this.logicalChannel = logicalChannel;
            this.resourceIndex = resourceIndex;
            this.slot = slot;
        }

        void stopAt(long stopFrame) {
            explicitlyStopped = true;
            long sourceFrame = stopFrame - startFrame;
            if (sourceFrame >= 0L && sourceFrame < frameCount) frameCount = sourceFrame;
        }

        boolean isAllocatedAt(long frame) {
            if (explicitlyStopped || frame < startFrame) return false;
            if (frame == startFrame) return true;
            return frame - startFrame < frameCount;
        }
    }

    private static final class MutableVoice extends VoiceLifetime {
        final long startMicros;
        final DecodedSampledResource resource;
        final int startLevel;
        final int globalSampledLevel;
        final List<AudioPlaybackSource.GainSegment> gainSegments =
                new ArrayList<AudioPlaybackSource.GainSegment>();

        MutableVoice(
                long startMicros,
                long startFrame,
                DecodedSampledResource resource,
                int frameCount,
                int logicalChannel,
                int resourceIndex,
                int slot,
                int startLevel,
                int globalSampledLevel,
                int leftGain,
                int rightGain) {
            super(startFrame, frameCount, logicalChannel, resourceIndex, slot);
            this.startMicros = startMicros;
            this.resource = resource;
            this.startLevel = startLevel;
            this.globalSampledLevel = globalSampledLevel;
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
                    startMicros, startFrame, resource, (int)frameCount, gainSegments);
        }
    }

    private static AudioProgram.ResourceCatalogEntry resourceEntry(
            NativeProgram program, int catalogIndex) {
        for (AudioProgram.ResourceCatalogEntry entry : program.audio.resourceCatalog) {
            if (entry.catalogIndex == catalogIndex) return entry;
        }
        return null;
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
                    "Native program contains sampled-audio execution outside the enabled exact route-0 profiles");
        }
        if (!hasPeriodicNativePcmTemplate(program)) {
            throw new IllegalArgumentException(
                    "Native infinite loop has evolving semantic state and cannot use a frozen PCM template");
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
            if (hasResourceExecution
                    && action.kind == AudioProgram.ActionKind.CHANNEL_ROUTE
                    && action.value != 0) {
                return true;
            }
            if (action.sourceKind == AudioProgram.SourceKind.MACHINE_DEPENDENT
                    && action.rendererSupport
                            == AudioProgram.RendererSupport.RECOGNIZED_UNSUPPORTED
                    && action.layeredEffect != AudioProgram.BranchEffect.NO_ACTION) {
                return true;
            }
        }
        return false;
    }

    private static boolean isVerifiedResourceStart(AudioProgram.AudioAction action) {
        return action.sourceKind == AudioProgram.SourceKind.RESOURCE_7F
                && action.kind == AudioProgram.ActionKind.RESOURCE_START
                && action.audioType == AudioProgram.AudioType.MFI_8001
                && action.linkedCatalogIndex >= 0
                && (action.rendererSupport == AudioProgram.RendererSupport.VERIFIED_8001_4BIT
                        || action.rendererSupport
                                == AudioProgram.RendererSupport.VERIFIED_8001_2BIT);
    }

    private static boolean hasPeriodicNativePcmTemplate(NativeProgram program) {
        return program == null || program.nativeLoop == null
                || program.nativeLoop.isPcmTemplatePeriodic(program);
    }

    private static int valueOrDefault(Map<Integer, Integer> values, int key, int defaultValue) {
        Integer value = values.get(Integer.valueOf(key));
        return value == null ? defaultValue : value.intValue();
    }
}
