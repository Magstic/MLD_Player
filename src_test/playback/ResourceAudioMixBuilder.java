package playback;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import container.TopLevelChunk;
import normalize.MdNormalizedEvent;
import timeline.PlaybackTimeline;

public final class ResourceAudioMixBuilder {
    public static final int OUTPUT_SAMPLE_RATE = 32000;
    public static final int OUTPUT_CHANNELS = 2;

    private static final int DEFAULT_LEVEL = 127;
    private static final int DEFAULT_PAN = 64;
    private static final int LEGACY_DEFAULT_LEVEL = 127;
    private static final int LEGACY_DEFAULT_PAN = 64;
    private static final int DEFAULT_BANK_LEVEL = 127;
    private static final int DEFAULT_BANK_PAN = 64;
    private static final int DEFAULT_GLOBAL_LEVEL = 64;
    private static final int EDGE_FADE_FRAMES = 96;
    private static final int PLUGIN_EXPORT_GAIN_SHIFT = 2;

    private static final int[] NATIVE_LEVEL_Q16 = new int[] {
        0, 4, 16, 36, 65, 101, 146, 199,
        260, 329, 406, 491, 585, 686, 796, 914,
        1040, 1174, 1316, 1466, 1625, 1791, 1966, 2149,
        2340, 2539, 2746, 2962, 3185, 3417, 3656, 3904,
        4160, 4424, 4697, 4977, 5265, 5562, 5867, 6180,
        6501, 6830, 7167, 7512, 7866, 8227, 8597, 8975,
        9361, 9755, 10157, 10568, 10986, 11413, 11848, 12291,
        12742, 13201, 13668, 14143, 14627, 15119, 15618, 16126,
        16642, 17166, 17699, 18239, 18788, 19344, 19909, 20482,
        21063, 21652, 22249, 22855, 23468, 24090, 24720, 25358,
        26004, 26658, 27320, 27991, 28669, 29356, 30051, 30754,
        31465, 32184, 32911, 33647, 34390, 35142, 35902, 36670,
        37446, 38230, 39022, 39823, 40631, 41448, 42273, 43106,
        43947, 44796, 45653, 46519, 47392, 48274, 49164, 50062,
        50968, 51882, 52805, 53735, 54674, 55620, 56575, 57538,
        58509, 59488, 60476, 61471, 62475, 63487, 64507, 65535
    };

    private static final int[] NATIVE_PAN_LEFT_Q16 = new int[] {
        65535, 65529, 65514, 65489, 65454, 65409, 65354, 65289,
        65214, 65129, 65034, 64929, 64814, 64689, 64554, 64410,
        64255, 64091, 63917, 63733, 63540, 63336, 63123, 62901,
        62668, 62426, 62175, 61914, 61644, 61364, 61075, 60776,
        60468, 60151, 59825, 59489, 59145, 58791, 58428, 58057,
        57676, 57287, 56889, 56482, 56067, 55643, 55211, 54770,
        54320, 53863, 53397, 52923, 52441, 51951, 51453, 50947,
        50433, 49912, 49383, 48846, 48302, 47750, 47191, 46625,
        46052, 45472, 44885, 44291, 43690, 43083, 42469, 41848,
        41221, 40588, 39948, 39303, 38651, 37994, 37330, 36661,
        35986, 35306, 34621, 33930, 33234, 32533, 31827, 31116,
        30400, 29680, 28955, 28225, 27492, 26754, 26012, 25266,
        24516, 23762, 23005, 22244, 21480, 20713, 19942, 19169,
        18392, 17613, 16831, 16046, 15259, 14469, 13678, 12884,
        12088, 11291, 10492, 9691, 8888, 8085, 7280, 6473,
        5666, 4858, 4050, 3240, 2431, 1620, 810, 0
    };

    private static final int[] NATIVE_PAN_RIGHT_Q16 = new int[] {
        0, 0, 810, 1620, 2431, 3240, 4050, 4858,
        5666, 6473, 7280, 8085, 8888, 9691, 10492, 11291,
        12088, 12884, 13678, 14469, 15259, 16046, 16831, 17613,
        18392, 19169, 19942, 20713, 21480, 22244, 23005, 23762,
        24516, 25266, 26012, 26754, 27492, 28225, 28955, 29680,
        30400, 31116, 31827, 32533, 33234, 33930, 34621, 35306,
        35986, 36661, 37330, 37994, 38651, 39303, 39948, 40588,
        41221, 41848, 42469, 43083, 43690, 44291, 44885, 45472,
        46052, 46625, 47191, 47750, 48302, 48846, 49383, 49912,
        50433, 50947, 51453, 51951, 52441, 52923, 53397, 53863,
        54320, 54770, 55211, 55643, 56067, 56482, 56889, 57287,
        57676, 58057, 58428, 58791, 59145, 59489, 59825, 60151,
        60468, 60776, 61075, 61364, 61644, 61914, 62175, 62426,
        62668, 62901, 63123, 63336, 63540, 63733, 63917, 64091,
        64255, 64410, 64554, 64689, 64814, 64929, 65034, 65129,
        65214, 65289, 65354, 65409, 65454, 65489, 65514, 65529
    };

    private static final Comparator<MdNormalizedEvent> LEGACY_MD_COMPARATOR = new Comparator<MdNormalizedEvent>() {
        @Override
        public int compare(MdNormalizedEvent left, MdNormalizedEvent right) {
            if (left.rawTick != right.rawTick) {
                return Integer.compare(left.rawTick, right.rawTick);
            }
            if (left.trackIndex != right.trackIndex) {
                return Integer.compare(left.trackIndex, right.trackIndex);
            }
            return Integer.compare(left.eventIndex, right.eventIndex);
        }
    };

    public RenderResult render(PlaybackTimeline timeline) {
        List<String> warnings = new ArrayList<String>();
        List<String> assumptions = new ArrayList<String>();

        if (!timeline.resourceEvents.isEmpty()) {
            assumptions.add("Resource host playback currently targets the verified SE2 line: selector 0x81 with top-level adat/adpm payloads.");
            assumptions.add("Default selector 0x81 waveform decoding follows the reversed MFiAudio.dll live path for the current reachable mono 0x8001 subset.");
            assumptions.add("Resource host playback currently matches the reversed MFiAudio.dll live wrapper for the current subset: 32-bit lane-0 staging, same-rate slot conversion, exact native Q16 level/pan tables, plugin-export x4 plane gain, then a clean-room stereo collapse.");
            assumptions.add("The later MFiSoundLibMFi5.dll outer-output contract is still deferred: delayed primary ring planes, current-block auxiliary planes, and the final shared fold are not yet reproduced exactly.");
            return renderTopLevelResourceTimeline(timeline, warnings, assumptions);
        }

        if (hasLegacyMdAudioEvents(timeline)) {
            assumptions.add("Legacy SE host playback currently targets the verified machine-dependent subset: 71 84 mode=1 with embedded 0x8001 payloads.");
            assumptions.add("Legacy MD playback currently treats 71 81/71 82 as channel level/pan state, ignores 71 8F, and renders the current SE samples as MD-only PCM.");
            assumptions.add("Legacy host playback currently matches the reversed MFiAudio.dll live wrapper for the current subset: 32-bit lane-0 staging, same-rate slot conversion, exact native Q16 level/pan tables, plugin-export x4 plane gain, then a clean-room stereo collapse.");
            assumptions.add("The later MFiSoundLibMFi5.dll outer-output contract is still deferred: delayed primary ring planes, current-block auxiliary planes, and the final shared fold are not yet reproduced exactly.");
            return renderLegacyMdTimeline(timeline, warnings, assumptions);
        }

        return RenderResult.unsupported("No resource/audio events were present in the compiled timeline.", warnings, assumptions);
    }

    public static boolean hasHostRenderableAudio(PlaybackTimeline timeline) {
        return !timeline.resourceEvents.isEmpty() || hasLegacyMdAudioEvents(timeline);
    }

    private RenderResult renderTopLevelResourceTimeline(
            PlaybackTimeline timeline,
            List<String> warnings,
            List<String> assumptions) {
        ChannelState[] channelStates = createChannelStates();
        applyInitialChannelConfigs(timeline, channelStates);
        Map<Integer, DecodedResourceAudio> decodedCache = new LinkedHashMap<Integer, DecodedResourceAudio>();
        Map<Integer, List<ScheduledClip>> activeByChannel = new LinkedHashMap<Integer, List<ScheduledClip>>();
        List<ScheduledClip> scheduledClips = new ArrayList<ScheduledClip>();

        long maxFrame = Math.max(1L, midiTickToOutputFrame(timeline, timeline.totalMidiTicks));
        for (PlaybackTimeline.ResourceEventState event : timeline.resourceEvents) {
            int logicalChannel = event.logicalChannel >= 0 ? clamp(0, channelStates.length - 1, event.logicalChannel) : 0;
            switch (event.command) {
                case 0x00:
                    if (event.linkedCatalogIndex < 0) {
                        warnings.add("Skipping resource start at tick " + event.midiTick + " because no linked adat catalog entry was resolved.");
                        break;
                    }
                    DecodedResourceAudio decoded = decodedCache.get(Integer.valueOf(event.linkedCatalogIndex));
                    if (decoded == null) {
                        decoded = decodeCatalogEntry(timeline, event.linkedCatalogIndex, warnings);
                        if (decoded != null) {
                            decodedCache.put(Integer.valueOf(event.linkedCatalogIndex), decoded);
                        }
                    }
                    if (decoded == null) {
                        break;
                    }

                    long startFrame = midiTickToOutputFrame(timeline, event.midiTick);
                    int startLevel = clamp(0, 127, valueOrDefault(event.extraParam2x, DEFAULT_LEVEL));
                    if (channelStates[logicalChannel].route != 0) {
                        warnings.add("Resource start at tick " + event.midiTick
                                + " uses audio route " + channelStates[logicalChannel].route
                                + "; current host render still folds to the primary stereo output while route-to-bus export mapping remains unresolved.");
                    }
                    StereoGain gain = computeStereoGain(channelStates[logicalChannel], startLevel);
                    ScheduledClip clip = new ScheduledClip(
                            logicalChannel,
                            event.resourceIndex,
                            event.linkedCatalogIndex,
                            startFrame,
                            -1L,
                            decoded,
                            gain.leftQ16,
                            gain.rightQ16);
                    scheduledClips.add(clip);
                    listFor(activeByChannel, logicalChannel).add(clip);
                    maxFrame = Math.max(maxFrame, startFrame + decoded.frameCount);
                    break;

                case 0x01:
                    long stopFrame = midiTickToOutputFrame(timeline, event.midiTick);
                    ScheduledClip open = findOpenClip(activeByChannel.get(Integer.valueOf(logicalChannel)), event.resourceIndex);
                    if (open != null) {
                        open.stopFrame = Math.max(open.startFrame, stopFrame);
                        maxFrame = Math.max(maxFrame, open.stopFrame);
                    }
                    break;

                case 0x80:
                    channelStates[logicalChannel].level = clamp(0, 127, valueOrDefault(event.value2x, DEFAULT_LEVEL));
                    break;

                case 0x81:
                    channelStates[logicalChannel].pan = clamp(0, 127, valueOrDefault(event.value2x, DEFAULT_PAN));
                    break;

                case 0x90:
                    if ("audio".equals(event.target)) {
                        channelStates[logicalChannel].route = Math.max(0, event.backendConfigValue);
                    }
                    break;

                default:
                    break;
            }
        }

        if (scheduledClips.isEmpty()) {
            return RenderResult.unsupported(
                    "Resource events were parsed, but none matched the current host-renderable selector family.",
                    warnings,
                    assumptions);
        }
        return mixScheduledClips(scheduledClips, maxFrame, warnings, assumptions);
    }

    private RenderResult renderLegacyMdTimeline(
            PlaybackTimeline timeline,
            List<String> warnings,
            List<String> assumptions) {
        ChannelState[] channelStates = createChannelStates();
        Map<Integer, DecodedResourceAudio> loadedBySlot = new LinkedHashMap<Integer, DecodedResourceAudio>();
        List<ScheduledClip> scheduledClips = new ArrayList<ScheduledClip>();
        List<MdNormalizedEvent> events = new ArrayList<MdNormalizedEvent>(timeline.mdNormalization.normalizedEvents);
        events.sort(LEGACY_MD_COMPARATOR);

        long maxFrame = Math.max(1L, midiTickToOutputFrame(timeline, timeline.totalMidiTicks));
        boolean sawSlotControl = false;

        for (MdNormalizedEvent event : events) {
            if ("audio_channel_level".equals(event.family)) {
                int channel = intDetail(event, "channel", -1);
                if (channel >= 0 && channel < channelStates.length) {
                    channelStates[channel].legacyLevel = clamp(0, 127, intDetail(event, "value", LEGACY_DEFAULT_LEVEL));
                }
                continue;
            }
            if ("audio_channel_pan".equals(event.family)) {
                int channel = intDetail(event, "channel", -1);
                if (channel >= 0 && channel < channelStates.length) {
                    channelStates[channel].legacyPan = clamp(0, 127, intDetail(event, "value", LEGACY_DEFAULT_PAN));
                }
                continue;
            }
            if (!"audio_slot_load".equals(event.family)) {
                continue;
            }

            sawSlotControl = true;
            int channel = intDetail(event, "channel", -1);
            int slot = intDetail(event, "slot", -1);
            int mode = intDetail(event, "mode", -1);
            int formatCode = intDetail(event, "formatCode", -1);
            int sampleRate = intDetail(event, "sampleRate", -1);
            int codedBits = intDetail(event, "codedBits", -1);
            int channelCount = intDetail(event, "channelCount", -1);
            int slotKey = slotKey(channel, slot);

            if (mode == 3) {
                continue;
            }

            if (mode == 0 || mode == 1) {
                byte[] embeddedPayload = extractLegacyEmbeddedPayload(event);
                if (embeddedPayload.length == 0) {
                    warnings.add("Legacy audio slot load at rawTick=" + event.rawTick + " has no embedded payload bytes.");
                    continue;
                }
                if (!ImaAdpcm4Decoder.supportsLivePath(sampleRate, codedBits, channelCount)) {
                    warnings.add("Legacy audio slot load at rawTick=" + event.rawTick
                            + " uses unsupported 0x8001 shape: formatCode=" + formatCode
                            + " (" + sampleRate + " Hz / " + codedBits + "-bit / " + channelCount + " ch).");
                    continue;
                }
                int[] decoded = ImaAdpcm4Decoder.decodeLiveMonoNativeLane0(sampleRate, codedBits, embeddedPayload);
                loadedBySlot.put(Integer.valueOf(slotKey), new DecodedResourceAudio(-1, convertSlotFrames(decoded)));
            }

            if (mode == 1 || mode == 2) {
                DecodedResourceAudio loaded = loadedBySlot.get(Integer.valueOf(slotKey));
                if (loaded == null) {
                    warnings.add("Legacy audio slot start at rawTick=" + event.rawTick + " had no previously loaded slot data.");
                    continue;
                }
                long startFrame = midiTickToOutputFrame(timeline, midiTickForRawTick(timeline, event.rawTick));
                StereoGain gain = computeLegacyStereoGain(channelStates[clamp(0, channelStates.length - 1, channel)]);
                ScheduledClip clip = new ScheduledClip(
                        channel,
                        slot,
                        -1,
                        startFrame,
                        -1L,
                        loaded,
                        gain.leftQ16,
                        gain.rightQ16);
                scheduledClips.add(clip);
                maxFrame = Math.max(maxFrame, startFrame + loaded.frameCount);
            }
        }

        if (scheduledClips.isEmpty()) {
            String reason = sawSlotControl
                    ? "Legacy machine-dependent audio events were parsed, but none matched the current host-renderable SE subset."
                    : "No renderable legacy machine-dependent audio events were present in the compiled timeline.";
            return RenderResult.unsupported(reason, warnings, assumptions);
        }
        return mixScheduledClips(scheduledClips, maxFrame, warnings, assumptions);
    }

    private RenderResult mixScheduledClips(
            List<ScheduledClip> scheduledClips,
            long maxFrame,
            List<String> warnings,
            List<String> assumptions) {
        int totalFrames = (int) Math.max(1L, maxFrame);
        int[] mix = new int[totalFrames * OUTPUT_CHANNELS];
        for (ScheduledClip clip : scheduledClips) {
            mixClip(mix, clip);
        }

        return RenderResult.supported(
                OUTPUT_SAMPLE_RATE,
                OUTPUT_CHANNELS,
                totalFrames,
                packPcm16Le(mix),
                warnings,
                assumptions);
    }

    private static boolean hasLegacyMdAudioEvents(PlaybackTimeline timeline) {
        for (MdNormalizedEvent event : timeline.mdNormalization.normalizedEvents) {
            if ("audio_slot_load".equals(event.family)) {
                return true;
            }
        }
        return false;
    }

    private static ChannelState[] createChannelStates() {
        ChannelState[] states = new ChannelState[64];
        for (int i = 0; i < states.length; i++) {
            states[i] = new ChannelState();
        }
        return states;
    }

    private static void applyInitialChannelConfigs(PlaybackTimeline timeline, ChannelState[] channelStates) {
        for (PlaybackTimeline.InitialChannelConfig config : timeline.initialChannelConfigs) {
            if (!"audio".equals(config.target)) {
                continue;
            }
            int logicalChannel = clamp(0, channelStates.length - 1, config.logicalChannel);
            channelStates[logicalChannel].route = Math.max(0, config.backendValue);
        }
    }

    private static StereoGain computeStereoGain(ChannelState state, int startLevel) {
        int combinedQ16 = combineNativeLevelQ16(startLevel, state.level, DEFAULT_BANK_LEVEL, DEFAULT_GLOBAL_LEVEL);
        int panIndex = clamp(0, 127, DEFAULT_BANK_PAN + state.pan - DEFAULT_PAN);
        return stereoGainFromNativeLaw(combinedQ16, panIndex);
    }

    private static StereoGain computeLegacyStereoGain(ChannelState state) {
        int combinedQ16 = combineNativeLevelQ16(
                LEGACY_DEFAULT_LEVEL,
                state.legacyLevel,
                DEFAULT_BANK_LEVEL,
                DEFAULT_GLOBAL_LEVEL);
        int panIndex = clamp(0, 127, DEFAULT_BANK_PAN + state.legacyPan - LEGACY_DEFAULT_PAN);
        return stereoGainFromNativeLaw(combinedQ16, panIndex);
    }

    private static StereoGain stereoGainFromNativeLaw(int combinedQ16, int panIndex) {
        int clampedPan = clamp(0, 127, panIndex);
        int leftQ16 = combineQ16(combinedQ16, NATIVE_PAN_LEFT_Q16[clampedPan]);
        int rightQ16 = combineQ16(combinedQ16, NATIVE_PAN_RIGHT_Q16[clampedPan]);
        return new StereoGain(leftQ16, rightQ16);
    }

    private static int combineNativeLevelQ16(int slotLevel, int channelLevel, int bankLevel, int globalLevel) {
        int channelBankQ16 = combineQ16(nativeLevelQ16(channelLevel), nativeLevelQ16(bankLevel));
        int slotChannelBankQ16 = combineQ16(nativeLevelQ16(slotLevel), channelBankQ16);
        return combineQ16(nativeLevelQ16(globalLevel), slotChannelBankQ16);
    }

    private static int combineQ16(int leftQ16, int rightQ16) {
        return (leftQ16 * rightQ16) >>> 16;
    }

    private static int nativeLevelQ16(int value) {
        return NATIVE_LEVEL_Q16[clamp(0, 127, value)];
    }

    private static List<ScheduledClip> listFor(Map<Integer, List<ScheduledClip>> map, int logicalChannel) {
        Integer key = Integer.valueOf(logicalChannel);
        List<ScheduledClip> clips = map.get(key);
        if (clips == null) {
            clips = new ArrayList<ScheduledClip>();
            map.put(key, clips);
        }
        return clips;
    }

    private static ScheduledClip findOpenClip(List<ScheduledClip> clips, int resourceIndex) {
        if (clips == null) {
            return null;
        }
        for (int i = clips.size() - 1; i >= 0; i--) {
            ScheduledClip clip = clips.get(i);
            if (clip.stopFrame >= 0) {
                continue;
            }
            if (resourceIndex < 0 || clip.resourceIndex < 0 || clip.resourceIndex == resourceIndex) {
                return clip;
            }
        }
        return null;
    }

    private static void mixClip(int[] mix, ScheduledClip clip) {
        int availableFrames = clip.audio.frameCount;
        if (clip.stopFrame >= 0) {
            long clippedLength = clip.stopFrame - clip.startFrame;
            availableFrames = (int) Math.max(0L, Math.min((long) availableFrames, clippedLength));
        }

        for (int frame = 0; frame < availableFrames; frame++) {
            int outputFrame = (int) (clip.startFrame + frame);
            if (outputFrame < 0 || outputFrame * OUTPUT_CHANNELS + 1 >= mix.length) {
                break;
            }
            int sample = clip.audio.convertedFrames[frame];
            int base = outputFrame * OUTPUT_CHANNELS;
            mix[base] += scaleMixedSample(sample, clip.leftGainQ16);
            mix[base + 1] += scaleMixedSample(sample, clip.rightGainQ16);
        }
    }

    private static int scaleMixedSample(int sample, int gainQ16) {
        // Native plugin export scales mixed bus planes by 4 before the
        // soundlib-side >>8 collapse. Applying the shift here preserves that
        // current default-path headroom/precision contract without yet
        // re-implementing the full delayed-primary/current-aux ring fold.
        return (int) ((((long) sample) * gainQ16) >> (15 - PLUGIN_EXPORT_GAIN_SHIFT));
    }

    private static byte[] packPcm16Le(int[] mix) {
        byte[] pcm = new byte[mix.length * 2];
        for (int i = 0; i < mix.length; i++) {
            int sample = mix[i] >> 8;
            if (sample < Short.MIN_VALUE) {
                sample = Short.MIN_VALUE;
            } else if (sample > Short.MAX_VALUE) {
                sample = Short.MAX_VALUE;
            }
            pcm[i * 2] = (byte) (sample & 0xFF);
            pcm[i * 2 + 1] = (byte) ((sample >>> 8) & 0xFF);
        }
        return pcm;
    }

    private static DecodedResourceAudio decodeCatalogEntry(
            PlaybackTimeline timeline,
            int catalogIndex,
            List<String> warnings) {
        PlaybackTimeline.ResourceCatalogEntry entry = findCatalogEntry(timeline.resourceCatalog, catalogIndex);
        if (entry == null) {
            warnings.add("Missing resource catalog entry for catalogIndex=" + catalogIndex + ".");
            return null;
        }
        if (!"adat".equals(entry.chunkId)) {
            warnings.add("Catalog entry " + catalogIndex + " is " + entry.chunkId + ", not adat; host resource render skipped.");
            return null;
        }

        TopLevelChunk chunk = findTopLevelChunk(timeline.file.topLevelChunks, entry.offset, entry.chunkId);
        if (chunk == null) {
            warnings.add("Could not locate top-level adat chunk for catalogIndex=" + catalogIndex + ".");
            return null;
        }

        ParsedAdatResource parsed = parseAdatResource(chunk.payload);
        if (parsed == null) {
            warnings.add("Top-level adat chunk at 0x" + Integer.toHexString(chunk.offset) + " did not match the current selector-header heuristic.");
            return null;
        }
        if (parsed.selectorId != 0x81) {
            warnings.add("Selector 0x" + toHex(parsed.selectorId) + " is not yet host-renderable; current clean-room audio path only supports selector 0x81.");
            return null;
        }
        if (parsed.adpmByte0 <= 0 || parsed.adpmByte1 <= 0 || parsed.adpmByte2Low3 <= 0) {
            warnings.add("Selector 0x81 adat chunk is missing a usable adpm descriptor.");
            return null;
        }
        if (parsed.adpmByte1 != 4) {
            warnings.add("Selector 0x81 adat chunk uses adpm bit-depth " + parsed.adpmByte1 + "; the current host decoder only supports 4-bit payloads.");
            return null;
        }
        if (parsed.adpmByte2Low3 != 1) {
            warnings.add("Selector 0x81 adat chunk uses channel count " + parsed.adpmByte2Low3 + "; the current host decoder only supports mono payloads.");
            return null;
        }
        if (parsed.adpmByte2Bit3 != 0) {
            warnings.add("Selector 0x81 adat chunk sets adpm byte2 bit3=" + parsed.adpmByte2Bit3 + "; that variant is outside the current host decoder.");
            return null;
        }

        int inputSampleRate = parsed.adpmByte0 * 1000;
        String decoder = System.getProperty("mld.adpcmDecoder", "live");
        boolean legacyProbe = "a".equalsIgnoreCase(decoder)
                || "b".equalsIgnoreCase(decoder)
                || "z".equalsIgnoreCase(decoder);

        if (!legacyProbe) {
            if (!ImaAdpcm4Decoder.supportsLivePath(inputSampleRate, parsed.adpmByte1, parsed.adpmByte2Low3)) {
                warnings.add("Selector 0x81 adat chunk uses "
                        + inputSampleRate + " Hz / "
                        + parsed.adpmByte1 + "-bit / "
                        + parsed.adpmByte2Low3 + " channel data; the current clean-room live path only covers 8000/16000 Hz / 4-bit / mono.");
                return null;
            }
            int[] decoded = ImaAdpcm4Decoder.decodeLiveMonoNativeLane0(inputSampleRate, parsed.adpmByte1, parsed.trailingPayload);
            return new DecodedResourceAudio(catalogIndex, convertSlotFrames(decoded));
        }

        if (inputSampleRate != 8000 && inputSampleRate != 16000 && inputSampleRate != 32000) {
            warnings.add("Selector 0x81 adat chunk uses sample rate " + inputSampleRate + " Hz; the legacy probe decoders only support 8000/16000/32000 Hz.");
            return null;
        }

        warnings.add("Selector 0x81 decoder override '" + decoder + "' is active; this bypasses the default clean-room live path and should only be used for listening probes.");
        short[] decoded = decodeSelector81Payload(parsed.trailingPayload, decoder);
        short[] resampled = inputSampleRate == OUTPUT_SAMPLE_RATE
                ? Arrays.copyOf(decoded, decoded.length)
                : resampleLinear(decoded, inputSampleRate, OUTPUT_SAMPLE_RATE);
        short[] cleaned = applyHostCleanup(resampled);
        return new DecodedResourceAudio(catalogIndex, toNativeMixFrames(cleaned));
    }

    private static ParsedAdatResource parseAdatResource(byte[] payload) {
        if (payload.length < 4) {
            return null;
        }

        int selectorHeaderLength = readBe16(payload, 0);
        int selectorHeaderEnd = 2 + selectorHeaderLength;
        if (selectorHeaderLength < 2 || selectorHeaderEnd > payload.length) {
            return null;
        }

        int selectorId = payload[2] & 0xFF;
        int selectorFlags = payload[3] & 0xFF;
        int adpmByte0 = -1;
        int adpmByte1 = -1;
        int adpmByte2Low3 = -1;
        int adpmByte2Bit3 = -1;

        int offset = 4;
        while (offset + 6 <= selectorHeaderEnd) {
            String id = new String(payload, offset, 4);
            int length = readBe16(payload, offset + 4);
            int bodyStart = offset + 6;
            int bodyEnd = bodyStart + length;
            if (bodyEnd > selectorHeaderEnd) {
                return null;
            }
            if (adpmByte0 < 0 && "adpm".equals(id) && length >= 3) {
                adpmByte0 = payload[bodyStart] & 0xFF;
                adpmByte1 = payload[bodyStart + 1] & 0xFF;
                int byte2 = payload[bodyStart + 2] & 0xFF;
                adpmByte2Low3 = byte2 & 0x07;
                adpmByte2Bit3 = (byte2 >>> 3) & 0x01;
            }
            offset = bodyEnd;
        }

        return new ParsedAdatResource(
                selectorId,
                selectorFlags,
                adpmByte0,
                adpmByte1,
                adpmByte2Low3,
                adpmByte2Bit3,
                Arrays.copyOfRange(payload, selectorHeaderEnd, payload.length));
    }

    private static short[] decodeSelector81Payload(byte[] payload, String decoder) {
        if ("b".equalsIgnoreCase(decoder)) {
            return decodeAdpcmB(payload);
        }
        if ("z".equalsIgnoreCase(decoder)) {
            return decodeAdpcmZ(payload);
        }
        return decodeAdpcmA(payload);
    }

    private static short[] decodeAdpcmA(byte[] payload) {
        short[] samples = new short[payload.length * 2];
        int history = 0;
        int stepIndex = 0;
        int output = 0;
        for (int i = 0; i < payload.length; i++) {
            int packed = payload[i] & 0xFF;
            history = decodeAdpcmAStep(packed & 0x0F, history, stepIndex, samples, output++);
            stepIndex = nextAdpcmAStepIndex(packed & 0x0F, stepIndex);
            history = decodeAdpcmAStep((packed >>> 4) & 0x0F, history, stepIndex, samples, output++);
            stepIndex = nextAdpcmAStepIndex((packed >>> 4) & 0x0F, stepIndex);
        }
        return samples;
    }

    private static short[] decodeAdpcmB(byte[] payload) {
        short[] samples = new short[payload.length * 2];
        int history = 0;
        int step = 127;
        int output = 0;
        for (int i = 0; i < payload.length; i++) {
            int packed = payload[i] & 0xFF;
            history = decodeAdpcmBStep(packed & 0x0F, history, step, samples, output++);
            step = nextAdpcmBStepSize(packed & 0x0F, step);
            history = decodeAdpcmBStep((packed >>> 4) & 0x0F, history, step, samples, output++);
            step = nextAdpcmBStepSize((packed >>> 4) & 0x0F, step);
        }
        return samples;
    }

    private static short[] decodeAdpcmZ(byte[] payload) {
        short[] samples = new short[payload.length * 2];
        int history = 0;
        int step = 127;
        int output = 0;
        for (int i = 0; i < payload.length; i++) {
            int packed = payload[i] & 0xFF;
            history = decodeAdpcmZStep(packed & 0x0F, history, step, samples, output++);
            step = nextAdpcmZStepSize(packed & 0x0F, step);
            history = decodeAdpcmZStep((packed >>> 4) & 0x0F, history, step, samples, output++);
            step = nextAdpcmZStepSize((packed >>> 4) & 0x0F, step);
        }
        return samples;
    }

    private static int decodeAdpcmBStep(int nibble, int history, int stepSize, short[] samples, int outputIndex) {
        int sign = nibble & 0x08;
        int delta = nibble & 0x07;
        int diff = ((1 + (delta << 1)) * stepSize) >> 3;
        int next = history + (sign != 0 ? -diff : diff);
        if (next < Short.MIN_VALUE) {
            next = Short.MIN_VALUE;
        } else if (next > Short.MAX_VALUE) {
            next = Short.MAX_VALUE;
        }
        samples[outputIndex] = (short) next;
        return next;
    }

    private static int nextAdpcmBStepSize(int nibble, int stepSize) {
        int delta = nibble & 0x07;
        int next = (ADPCM_B_STEP_TABLE[delta] * stepSize) >> 6;
        return clamp(1280, 32767, next);
    }

    private static int decodeAdpcmZStep(int nibble, int history, int stepSize, short[] samples, int outputIndex) {
        int sign = nibble & 0x08;
        int delta = nibble & 0x07;
        int diff = ((1 + (delta << 1)) * stepSize) >> 3;
        int next = history + (sign != 0 ? -diff : diff);
        if (next < Short.MIN_VALUE) {
            next = Short.MIN_VALUE;
        } else if (next > Short.MAX_VALUE) {
            next = Short.MAX_VALUE;
        }
        samples[outputIndex] = (short) next;
        return next;
    }

    private static int nextAdpcmZStepSize(int nibble, int stepSize) {
        int delta = nibble & 0x07;
        int next = (ADPCM_Z_STEP_TABLE[delta] * stepSize) >> 8;
        return clamp(1280, 32767, next);
    }

    private static int decodeAdpcmAStep(int nibble, int history, int stepIndex, short[] samples, int outputIndex) {
        int step = ADPCM_A_STEP_TABLE[stepIndex];
        int delta = (ADPCM_A_DELTA_TABLE[nibble & 0x0F] * step) >> 3;
        int next = (history + delta) & 0x0FFF;
        if ((next & 0x0800) != 0) {
            next -= 0x1000;
        }
        samples[outputIndex] = (short) clamp(Short.MIN_VALUE, Short.MAX_VALUE, next << 4);
        return next;
    }

    private static int nextAdpcmAStepIndex(int nibble, int stepIndex) {
        return clamp(0, 48, stepIndex + ADPCM_A_ADJUST_TABLE[nibble & 0x07]);
    }

    private static short[] resampleLinear(short[] input, int inputRate, int outputRate) {
        if (input.length == 0) {
            return new short[0];
        }
        if (inputRate == outputRate) {
            return Arrays.copyOf(input, input.length);
        }

        int outputLength = Math.max(1, (int) (((long) input.length * outputRate) / inputRate));
        short[] output = new short[outputLength];
        for (int i = 0; i < outputLength; i++) {
            double sourcePosition = ((double) i * inputRate) / outputRate;
            int sourceIndex = (int) sourcePosition;
            double fraction = sourcePosition - sourceIndex;
            int left = input[Math.min(input.length - 1, sourceIndex)];
            int right = input[Math.min(input.length - 1, sourceIndex + 1)];
            int interpolated = (int) Math.round(left + ((right - left) * fraction));
            output[i] = (short) clamp(Short.MIN_VALUE, Short.MAX_VALUE, interpolated);
        }
        return output;
    }

    private static short[] applyHostCleanup(short[] input) {
        if (input.length == 0) {
            return input;
        }

        short[] cleaned = Arrays.copyOf(input, input.length);
        applyEdgeFade(cleaned, Math.min(EDGE_FADE_FRAMES, cleaned.length / 2));
        return cleaned;
    }

    private static int[] convertSlotFrames(int[] decodedFrames) {
        if (decodedFrames.length == 0) {
            return new int[0];
        }
        int[] converted = new int[decodedFrames.length];
        for (int i = 4; i < decodedFrames.length; i++) {
            converted[i] = decodedFrames[i - 4] >> 1;
        }
        return converted;
    }

    private static int[] toNativeMixFrames(short[] samples) {
        int[] converted = new int[samples.length];
        for (int i = 0; i < samples.length; i++) {
            converted[i] = samples[i] << 8;
        }
        return converted;
    }

    private static void applyEdgeFade(short[] samples, int fadeFrames) {
        if (fadeFrames <= 0) {
            return;
        }
        int length = samples.length;
        for (int i = 0; i < fadeFrames; i++) {
            double gain = (double) i / fadeFrames;
            samples[i] = (short) Math.round(samples[i] * gain);
            int tailIndex = length - 1 - i;
            samples[tailIndex] = (short) Math.round(samples[tailIndex] * gain);
        }
    }

    private static long midiTickToOutputFrame(PlaybackTimeline timeline, long midiTick) {
        long micros = midiTickToMicros(timeline, midiTick);
        return (micros * OUTPUT_SAMPLE_RATE + 500000L) / 1000000L;
    }

    private static long midiTickToMicros(PlaybackTimeline timeline, long midiTick) {
        long accumulatedMicros = 0L;
        PlaybackTimeline.TempoPoint active = timeline.tempoPoints.get(0);
        long segmentStartTick = active.midiTick;
        for (int i = 1; i < timeline.tempoPoints.size(); i++) {
            PlaybackTimeline.TempoPoint point = timeline.tempoPoints.get(i);
            if (point.midiTick > midiTick) {
                break;
            }
            accumulatedMicros += ticksToMicros(point.midiTick - segmentStartTick, active.mpqn);
            active = point;
            segmentStartTick = point.midiTick;
        }
        accumulatedMicros += ticksToMicros(midiTick - segmentStartTick, active.mpqn);
        return accumulatedMicros;
    }

    private static long midiTickForRawTick(PlaybackTimeline timeline, int rawTick) {
        if (timeline.tempoPoints.isEmpty()) {
            return 0L;
        }

        long midiTick = 0L;
        PlaybackTimeline.TempoPoint active = timeline.tempoPoints.get(0);
        for (int i = 1; i < timeline.tempoPoints.size(); i++) {
            PlaybackTimeline.TempoPoint point = timeline.tempoPoints.get(i);
            if (point.rawTick > rawTick) {
                break;
            }
            int segmentRaw = Math.max(0, point.rawTick - active.rawTick);
            midiTick += ((long) segmentRaw * PlaybackTimeline.MIDI_PPQ) / active.timebase;
            active = point;
        }

        int tailRaw = Math.max(0, rawTick - active.rawTick);
        midiTick += ((long) tailRaw * PlaybackTimeline.MIDI_PPQ) / active.timebase;
        return midiTick;
    }

    private static long ticksToMicros(long ticks, int mpqn) {
        return (ticks * mpqn + (PlaybackTimeline.MIDI_PPQ / 2)) / PlaybackTimeline.MIDI_PPQ;
    }

    private static PlaybackTimeline.ResourceCatalogEntry findCatalogEntry(
            List<PlaybackTimeline.ResourceCatalogEntry> entries,
            int catalogIndex) {
        for (PlaybackTimeline.ResourceCatalogEntry entry : entries) {
            if (entry.catalogIndex == catalogIndex) {
                return entry;
            }
        }
        return null;
    }

    private static TopLevelChunk findTopLevelChunk(List<TopLevelChunk> chunks, int offset, String id) {
        for (TopLevelChunk chunk : chunks) {
            if (chunk.offset == offset && chunk.id.equals(id)) {
                return chunk;
            }
        }
        return null;
    }

    private static byte[] extractLegacyEmbeddedPayload(MdNormalizedEvent event) {
        int embeddedLength = intDetail(event, "embeddedLength", 0);
        if (event.rawBytes.length <= 9 || embeddedLength <= 0) {
            return new byte[0];
        }
        int availableLength = Math.min(embeddedLength, event.rawBytes.length - 9);
        return Arrays.copyOfRange(event.rawBytes, 9, 9 + availableLength);
    }

    private static int slotKey(int channel, int slot) {
        return ((channel & 0xFF) << 8) | (slot & 0xFF);
    }

    private static int intDetail(MdNormalizedEvent event, String key, int fallback) {
        Object value = event.details.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return fallback;
    }

    private static int readBe16(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    private static int valueOrDefault(int value, int fallback) {
        return value >= 0 ? value : fallback;
    }

    private static int clamp(int min, int max, int value) {
        return Math.max(min, Math.min(max, value));
    }

    private static String toHex(int value) {
        return String.format("%02X", Integer.valueOf(value & 0xFF));
    }

    private static final int[] ADPCM_B_STEP_TABLE = new int[] { 57, 57, 57, 57, 77, 102, 128, 153 };
    private static final int[] ADPCM_Z_STEP_TABLE = new int[] { 230, 230, 230, 230, 307, 409, 512, 614 };
    private static final int[] ADPCM_A_STEP_TABLE = new int[] {
            16, 17, 19, 21, 23, 25, 28, 31,
            34, 37, 41, 45, 50, 55, 60, 66,
            73, 80, 88, 97, 107, 118, 130, 143,
            157, 173, 190, 209, 230, 253, 279, 307,
            337, 371, 408, 449, 494, 544, 598, 658,
            724, 796, 876, 963, 1060, 1166, 1282, 1411, 1552
    };
    private static final int[] ADPCM_A_DELTA_TABLE = new int[] {
            1, 3, 5, 7, 9, 11, 13, 15, -1, -3, -5, -7, -9, -11, -13, -15
    };
    private static final int[] ADPCM_A_ADJUST_TABLE = new int[] { -1, -1, -1, -1, 2, 5, 7, 9 };

    private static final class ChannelState {
        int level = DEFAULT_LEVEL;
        int pan = DEFAULT_PAN;
        int route = 0;
        int legacyLevel = LEGACY_DEFAULT_LEVEL;
        int legacyPan = LEGACY_DEFAULT_PAN;
    }

    private static final class StereoGain {
        final int leftQ16;
        final int rightQ16;

        StereoGain(int leftQ16, int rightQ16) {
            this.leftQ16 = leftQ16;
            this.rightQ16 = rightQ16;
        }
    }

    private static final class ParsedAdatResource {
        final int selectorId;
        final int selectorFlags;
        final int adpmByte0;
        final int adpmByte1;
        final int adpmByte2Low3;
        final int adpmByte2Bit3;
        final byte[] trailingPayload;

        ParsedAdatResource(
                int selectorId,
                int selectorFlags,
                int adpmByte0,
                int adpmByte1,
                int adpmByte2Low3,
                int adpmByte2Bit3,
                byte[] trailingPayload) {
            this.selectorId = selectorId;
            this.selectorFlags = selectorFlags;
            this.adpmByte0 = adpmByte0;
            this.adpmByte1 = adpmByte1;
            this.adpmByte2Low3 = adpmByte2Low3;
            this.adpmByte2Bit3 = adpmByte2Bit3;
            this.trailingPayload = trailingPayload;
        }
    }

    private static final class DecodedResourceAudio {
        final int catalogIndex;
        final int[] convertedFrames;
        final int frameCount;

        DecodedResourceAudio(int catalogIndex, int[] convertedFrames) {
            this.catalogIndex = catalogIndex;
            this.convertedFrames = convertedFrames;
            this.frameCount = convertedFrames.length;
        }
    }

    private static final class ScheduledClip {
        final int logicalChannel;
        final int resourceIndex;
        final int catalogIndex;
        final long startFrame;
        long stopFrame;
        final DecodedResourceAudio audio;
        final int leftGainQ16;
        final int rightGainQ16;

        ScheduledClip(
                int logicalChannel,
                int resourceIndex,
                int catalogIndex,
                long startFrame,
                long stopFrame,
                DecodedResourceAudio audio,
                int leftGainQ16,
                int rightGainQ16) {
            this.logicalChannel = logicalChannel;
            this.resourceIndex = resourceIndex;
            this.catalogIndex = catalogIndex;
            this.startFrame = startFrame;
            this.stopFrame = stopFrame;
            this.audio = audio;
            this.leftGainQ16 = leftGainQ16;
            this.rightGainQ16 = rightGainQ16;
        }
    }

    public static final class RenderResult {
        public final boolean supported;
        public final String reason;
        public final int sampleRate;
        public final int channels;
        public final int frameCount;
        public final byte[] pcm16le;
        public final List<String> warnings;
        public final List<String> assumptions;

        private RenderResult(
                boolean supported,
                String reason,
                int sampleRate,
                int channels,
                int frameCount,
                byte[] pcm16le,
                List<String> warnings,
                List<String> assumptions) {
            this.supported = supported;
            this.reason = reason;
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.frameCount = frameCount;
            this.pcm16le = pcm16le;
            this.warnings = warnings;
            this.assumptions = assumptions;
        }

        static RenderResult supported(
                int sampleRate,
                int channels,
                int frameCount,
                byte[] pcm16le,
                List<String> warnings,
                List<String> assumptions) {
            return new RenderResult(
                    true,
                    "ok",
                    sampleRate,
                    channels,
                    frameCount,
                    Arrays.copyOf(pcm16le, pcm16le.length),
                    new ArrayList<String>(warnings),
                    new ArrayList<String>(assumptions));
        }

        static RenderResult unsupported(String reason, List<String> warnings, List<String> assumptions) {
            return new RenderResult(
                    false,
                    reason,
                    0,
                    0,
                    0,
                    new byte[0],
                    new ArrayList<String>(warnings),
                    new ArrayList<String>(assumptions));
        }
    }
}
