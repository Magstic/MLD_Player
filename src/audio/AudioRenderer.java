package audio;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import mld.semantic.AudioProgram;
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
                && hasEffectiveVerifiedStart(program);
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
            throw new IllegalArgumentException("Native program contains no verified sampled-audio start");
        }
        return AudioPlaybackSource.NATIVE_SAMPLE_RATE;
    }

    /** Estimates rendered linear duration from verified native sampled-audio frame arithmetic. */
    public long estimateLinearDurationMillis(NativeProgram program) {
        long durationMicros = preparePlayback(program).getLinearDurationMicros();
        return durationMicros / 1000L + (durationMicros % 1000L == 0L ? 0L : 1L);
    }

    private AudioPlaybackSource prepareNativePlayback(NativeProgram program) {
        Map<Integer, Integer> channelLevels = new HashMap<Integer, Integer>();
        Map<Integer, Integer> channelPans = new HashMap<Integer, Integer>();
        Map<Integer, Integer> resourceChannelRoutes = new HashMap<Integer, Integer>();
        Map<Integer, DecodedSampledResource> activeResourceCache =
                new HashMap<Integer, DecodedSampledResource>();
        Map<Integer, LoadedSlot> loadedSlots = new HashMap<Integer, LoadedSlot>();
        Set<Integer> compactCachedStateSlots = new HashSet<Integer>();
        List<MutableVoice> voiceBuilders = new ArrayList<MutableVoice>();
        int resourceLevel = DEFAULT_RESOURCE_LEVEL;
        int resourcePan = DEFAULT_RESOURCE_PAN;
        int globalSampledLevel = program.audio.globalSampledLevel;

        for (AudioProgram.AudioAction action : program.audio.actions) {
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
            if (isVerifiedChannelLevel(action)) {
                channelLevels.put(
                        Integer.valueOf(action.logicalChannel), Integer.valueOf(action.value));
                updateLiveVoices(
                        voiceBuilders, program, action, action.logicalChannel,
                        resourceLevel, resourcePan, channelLevels, channelPans);
                continue;
            }
            if (isVerifiedChannelPan(action)) {
                channelPans.put(
                        Integer.valueOf(action.logicalChannel), Integer.valueOf(action.value));
                updateLiveVoices(
                        voiceBuilders, program, action, action.logicalChannel,
                        resourceLevel, resourcePan, channelLevels, channelPans);
                continue;
            }
            if (action.kind == AudioProgram.ActionKind.CHANNEL_ROUTE && action.logicalChannel >= 0) {
                resourceChannelRoutes.put(
                        Integer.valueOf(action.logicalChannel), Integer.valueOf(action.value));
                continue;
            }
            if (isVerifiedResourceStop(action)) {
                stopResourceVoices(voiceBuilders, program, action);
                continue;
            }
            if (isVerified8002Load(action)) {
                releaseSlotVoices(voiceBuilders, program, action);
                DecodedSampledResource decoded = Mfi8002Decoder.decode(
                        action.copyEncodedPayload(), action.sampleRate,
                        action.codedBits, action.channelCount);
                Integer slot = Integer.valueOf(action.slot);
                loadedSlots.put(slot, new LoadedSlot(decoded, action.durationMs));
                compactCachedStateSlots.add(slot);
                continue;
            }
            if (isVerifiedCompactStop(action)) {
                stopSlotVoices(voiceBuilders, program, action);
                continue;
            }
            if (consumeCompactPendingAttempt(compactCachedStateSlots, action)) {
                // Pending 0x106 resolves to an unreleased type mismatch or cached-op-3 no-op.
                // Both leave the existing 0x8002 cache and voices unchanged.
                continue;
            }

            DecodedSampledResource decoded;
            int startLevel;
            int voiceFrameCount;
            boolean resourceStart = isVerifiedResourceStart(action);
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
            } else if (isVerifiedMachineStart(action)) {
                releaseSlotVoices(voiceBuilders, program, action);
                Integer slot = Integer.valueOf(action.slot);
                LoadedSlot previous = loadedSlots.get(slot);
                long compactDurationMs = previous == null ? 0L : previous.compactDurationMs;
                decoded = Mfi8001Decoder.decode(
                        action.copyEncodedPayload(), action.sampleRate,
                        action.codedBits, action.channelCount);
                loadedSlots.put(slot, new LoadedSlot(decoded, compactDurationMs));
                startLevel = DEFAULT_START_LEVEL;
                voiceFrameCount = durationLimitedFrameCount(decoded, action.durationMs);
            } else if (isVerifiedCompactStart(action)) {
                LoadedSlot loaded = loadedSlots.get(Integer.valueOf(action.slot));
                if (loaded == null) {
                    throw new IllegalArgumentException("verified compact start has no loaded slot");
                }
                decoded = loaded.resource;
                startLevel = action.value;
                voiceFrameCount = durationLimitedFrameCount(decoded, loaded.compactDurationMs);
            } else {
                continue;
            }

            int channelLevel = valueOrDefault(
                    channelLevels, action.logicalChannel, DEFAULT_CHANNEL_LEVEL);
            int channelPan = valueOrDefault(
                    channelPans, action.logicalChannel, DEFAULT_CHANNEL_PAN);
            int route = resourceStart
                    ? valueOrDefault(resourceChannelRoutes, action.logicalChannel, DEFAULT_ROUTE)
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
            addVoiceWithNativePool(voiceBuilders, new MutableVoice(
                    startMicros,
                    startFrame,
                    decoded,
                    voiceFrameCount,
                    action.logicalChannel,
                    resourceStart ? action.resourceIndex : -1,
                    (isVerifiedCompactStart(action) || isVerifiedMachineStart(action))
                            ? action.slot : -1,
                    startLevel,
                    globalSampledLevel,
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

    private static void stopResourceVoices(
            List<MutableVoice> voices, NativeProgram program, AudioProgram.AudioAction action) {
        long stopFrame = AudioPlaybackSource.microsToFrameFloor(
                program.timing.rawTickToMicros(action.rawTick),
                AudioPlaybackSource.NATIVE_SAMPLE_RATE);
        for (MutableVoice voice : voices) {
            if (voice.resourceIndex == action.resourceIndex
                    && voice.logicalChannel == action.logicalChannel) {
                voice.stopAt(stopFrame);
            }
        }
    }

    private static void releaseSlotVoices(
            List<MutableVoice> voices, NativeProgram program, AudioProgram.AudioAction action) {
        long stopFrame = AudioPlaybackSource.microsToFrameFloor(
                program.timing.rawTickToMicros(action.rawTick),
                AudioPlaybackSource.NATIVE_SAMPLE_RATE);
        for (MutableVoice voice : voices) {
            if (voice.slot == action.slot) voice.stopAt(stopFrame);
        }
    }

    private static void stopSlotVoices(
            List<MutableVoice> voices, NativeProgram program, AudioProgram.AudioAction action) {
        long stopFrame = AudioPlaybackSource.microsToFrameFloor(
                program.timing.rawTickToMicros(action.rawTick),
                AudioPlaybackSource.NATIVE_SAMPLE_RATE);
        for (MutableVoice voice : voices) {
            if (voice.slot == action.slot && voice.logicalChannel == action.logicalChannel) {
                voice.stopAt(stopFrame);
            }
        }
    }

    private static void addVoiceWithNativePool(
            List<MutableVoice> voices, MutableVoice started) {
        int activeCount = 0;
        MutableVoice oldest = null;
        for (MutableVoice voice : voices) {
            if (!voice.isAllocatedAt(started.startFrame)) continue;
            if (oldest == null) oldest = voice;
            activeCount++;
        }
        if (activeCount >= NATIVE_VOICE_COUNT) {
            // MFiAudio 0x10001000 reuses the active entry with the lowest start serial.
            oldest.stopAt(started.startFrame);
        }
        voices.add(started);
    }

    private static final class LoadedSlot {
        final DecodedSampledResource resource;
        final long compactDurationMs;

        LoadedSlot(DecodedSampledResource resource, long compactDurationMs) {
            this.resource = resource;
            this.compactDurationMs = compactDurationMs;
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

    private static final class MutableVoice {
        final long startMicros;
        final long startFrame;
        final DecodedSampledResource resource;
        int frameCount;
        final int logicalChannel;
        final int resourceIndex;
        final int slot;
        final int startLevel;
        final int globalSampledLevel;
        boolean explicitlyStopped;
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
            this.startMicros = startMicros;
            this.startFrame = startFrame;
            this.resource = resource;
            this.frameCount = frameCount;
            this.logicalChannel = logicalChannel;
            this.resourceIndex = resourceIndex;
            this.slot = slot;
            this.startLevel = startLevel;
            this.globalSampledLevel = globalSampledLevel;
            addGainSegment(0, leftGain, rightGain);
        }

        void stopAt(long stopFrame) {
            explicitlyStopped = true;
            long sourceFrame = stopFrame - startFrame;
            if (sourceFrame >= 0L && sourceFrame < frameCount) {
                frameCount = (int)sourceFrame;
            }
        }

        boolean isAllocatedAt(long frame) {
            if (explicitlyStopped || frame < startFrame) return false;
            if (frame == startFrame) return true;
            return frame - startFrame < frameCount;
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
                    startMicros, startFrame, resource, frameCount, gainSegments);
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
    }

    private static boolean hasBlockingUnsupportedSampledPath(NativeProgram program) {
        boolean hasResourceExecution = false;
        for (AudioProgram.AudioAction action : program.audio.actions) {
            if (action.kind == AudioProgram.ActionKind.RESOURCE_START) {
                hasResourceExecution = true;
                if (!isVerifiedResourceStart(action)) return true;
            }
        }
        Set<Integer> loadedSlots = new HashSet<Integer>();
        Set<Integer> compactCachedStateSlots = new HashSet<Integer>();
        for (AudioProgram.AudioAction action : program.audio.actions) {
            if (hasResourceExecution
                    && action.kind == AudioProgram.ActionKind.RESOURCE_STOP
                    && !isVerifiedResourceStop(action)) return true;
            if (hasResourceExecution
                    && action.kind == AudioProgram.ActionKind.CHANNEL_ROUTE
                    && action.value != 0) return true;
            if (action.sourceKind != AudioProgram.SourceKind.MACHINE_DEPENDENT) continue;
            if (consumeCompactPendingAttempt(compactCachedStateSlots, action)) continue;
            if (isForcedCached8001Load(action)) return true;
            if (action.kind == AudioProgram.ActionKind.CHANNEL_LEVEL
                    && !isVerifiedChannelLevel(action)) return true;
            if (action.kind == AudioProgram.ActionKind.CHANNEL_PAN
                    && !isVerifiedChannelPan(action)) return true;
            if (action.kind == AudioProgram.ActionKind.SLOT_LOAD) {
                if (!isVerified8002Load(action)) return true;
                Integer slot = Integer.valueOf(action.slot);
                loadedSlots.add(slot);
                compactCachedStateSlots.add(slot);
                continue;
            }
            if (action.kind == AudioProgram.ActionKind.SLOT_START) {
                if (!isVerifiedCompactStart(action)
                        || !loadedSlots.contains(Integer.valueOf(action.slot))) return true;
                continue;
            }
            if (action.kind == AudioProgram.ActionKind.SLOT_STOP) {
                if (!isVerifiedCompactStop(action)) return true;
                continue;
            }
            if (action.kind == AudioProgram.ActionKind.SLOT_LOAD_AND_START) {
                if (!isVerifiedMachineStart(action)) return true;
                Integer slot = Integer.valueOf(action.slot);
                if (!compactCachedStateSlots.remove(slot)) loadedSlots.add(slot);
            }
            if (action.kind == AudioProgram.ActionKind.SLOT_CONTROL) return true;
        }
        return false;
    }

    private static boolean hasAnyVerifiedStart(NativeProgram program) {
        return hasEffectiveVerifiedStart(program);
    }

    private static boolean hasEffectiveVerifiedStart(NativeProgram program) {
        Set<Integer> loadedSlots = new HashSet<Integer>();
        Set<Integer> compactCachedStateSlots = new HashSet<Integer>();
        for (AudioProgram.AudioAction action : program.audio.actions) {
            if (isVerifiedResourceStart(action)) return true;
            if (isVerified8002Load(action)) {
                Integer slot = Integer.valueOf(action.slot);
                loadedSlots.add(slot);
                compactCachedStateSlots.add(slot);
                continue;
            }
            if (consumeCompactPendingAttempt(compactCachedStateSlots, action)) continue;
            if (isVerifiedMachineStart(action)) {
                Integer slot = Integer.valueOf(action.slot);
                if (compactCachedStateSlots.remove(slot)) continue;
                loadedSlots.add(slot);
                return true;
            }
            if (isVerifiedCompactStart(action)
                    && loadedSlots.contains(Integer.valueOf(action.slot))) return true;
        }
        return false;
    }

    private static boolean consumeCompactPendingAttempt(
            Set<Integer> compactPendingSlots, AudioProgram.AudioAction action) {
        if (!isFormatValidCached8001(action)) return false;
        Integer slot = Integer.valueOf(action.slot);
        if (!compactPendingSlots.contains(slot)) return false;
        if (action.controlFlag == 0) compactPendingSlots.remove(slot);
        return true;
    }

    private static boolean isFormatValidCached8001(AudioProgram.AudioAction action) {
        return action.sourceKind == AudioProgram.SourceKind.MACHINE_DEPENDENT
                && (action.handlerId == 0x109 || action.handlerId == 0x106)
                && action.audioType == AudioProgram.AudioType.MFI_8001
                && action.slot >= 0 && action.slot < 64
                && action.sampleRate > 0
                && (action.codedBits == 2 || action.codedBits == 4)
                && action.channelCount == 1;
    }

    private static boolean isForcedCached8001Load(AudioProgram.AudioAction action) {
        return isFormatValidCached8001(action)
                && action.controlFlag == 1
                && action.operation != 2;
    }

    private static boolean isVerifiedChannelLevel(AudioProgram.AudioAction action) {
        return action.kind == AudioProgram.ActionKind.CHANNEL_LEVEL
                && action.logicalChannel >= 0
                && (action.sourceKind == AudioProgram.SourceKind.RESOURCE_7F
                || (action.sourceKind == AudioProgram.SourceKind.MACHINE_DEPENDENT
                && action.descriptorIndex == 18
                && action.handlerId == 0x000));
    }

    private static boolean isVerifiedChannelPan(AudioProgram.AudioAction action) {
        if (action.kind != AudioProgram.ActionKind.CHANNEL_PAN || action.logicalChannel < 0) {
            return false;
        }
        if (action.sourceKind == AudioProgram.SourceKind.RESOURCE_7F) return true;
        if (action.sourceKind != AudioProgram.SourceKind.MACHINE_DEPENDENT) return false;
        if (action.descriptorIndex == 21 && action.handlerId == 0x105) return true;
        return (action.descriptorIndex == 9 && action.handlerId == 0x401 && action.operation == 6)
                || (action.descriptorIndex == 26 && action.handlerId == 0x402
                        && action.operation == 11);
    }

    private static boolean isVerifiedResourceStop(AudioProgram.AudioAction action) {
        return action.sourceKind == AudioProgram.SourceKind.RESOURCE_7F
                && action.kind == AudioProgram.ActionKind.RESOURCE_STOP
                && action.logicalChannel >= 0
                && action.resourceIndex >= 0;
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
                && Mfi8001Decoder.supports(action.sampleRate, action.codedBits, action.channelCount);
    }

    private static boolean isVerified8002Load(AudioProgram.AudioAction action) {
        boolean nativeEntry = (action.descriptorIndex == 8 && action.handlerId == 0x400)
                || (action.descriptorIndex == 26 && action.handlerId == 0x402);
        return action.rendererSupport == AudioProgram.RendererSupport.VERIFIED_8002_AWC2_4BIT
                && action.sourceKind == AudioProgram.SourceKind.MACHINE_DEPENDENT
                && nativeEntry
                && action.kind == AudioProgram.ActionKind.SLOT_LOAD
                && action.audioType == AudioProgram.AudioType.MFI_8002
                && action.slot >= 0 && action.slot < 64
                && Mfi8002Decoder.supportsPayloadLength(
                        action.encodedPayloadLength(), action.channelCount)
                && Mfi8002Decoder.supports(
                        action.sampleRate, action.codedBits, action.channelCount);
    }

    private static boolean isVerifiedCompactStart(AudioProgram.AudioAction action) {
        boolean nativeEntry = (action.descriptorIndex == 9 && action.handlerId == 0x401
                        && (action.operation == 3 || action.operation == 4))
                || (action.descriptorIndex == 26 && action.handlerId == 0x402
                        && (action.operation == 9 || action.operation == 15));
        return action.sourceKind == AudioProgram.SourceKind.MACHINE_DEPENDENT
                && nativeEntry
                && action.kind == AudioProgram.ActionKind.SLOT_START
                && action.logicalChannel >= 0 && action.logicalChannel < 4
                && action.slot >= 0 && action.slot < 32
                && action.value >= 0;
    }

    private static boolean isVerifiedCompactStop(AudioProgram.AudioAction action) {
        boolean nativeEntry = (action.descriptorIndex == 9 && action.handlerId == 0x401
                        && action.operation == 5)
                || (action.descriptorIndex == 26 && action.handlerId == 0x402
                        && action.operation == 10);
        return action.sourceKind == AudioProgram.SourceKind.MACHINE_DEPENDENT
                && nativeEntry
                && action.kind == AudioProgram.ActionKind.SLOT_STOP
                && action.logicalChannel >= 0 && action.logicalChannel < 4
                && action.slot >= 0 && action.slot < 32;
    }

    private static int valueOrDefault(Map<Integer, Integer> values, int key, int defaultValue) {
        Integer value = values.get(Integer.valueOf(key));
        return value == null ? defaultValue : value.intValue();
    }
}
