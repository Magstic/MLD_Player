package mld.semantic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import mld.format.MldDocument;
import mld.format.TopLevelChunk;
import mld.decode.ResourceEvent;

/**
 * Native resource-table and live resource-event semantics.
 *
 * Owns ainf/thrd/adat catalog construction and 0x7F resource state. It does
 * not emit MIDI and does not mutate ordinary note/system state.
 */
final class AudioState {
    private AudioState() {
    }

    static List<AudioProgram.ResourceCatalogEntry> buildCatalog(
            MldDocument file,
            List<String> warnings,
            Set<String> warningKeys) {
        List<AudioProgram.ResourceCatalogEntry> entries = new ArrayList<AudioProgram.ResourceCatalogEntry>();
        TopLevelChunk firstAinf = firstHeaderChunk(file, "ainf");
        int ainfCount = countHeaderChunks(file, "ainf");

        int activeAdatCount = 0;
        boolean useAinfTable = false;
        if (firstAinf != null) {
            if (ainfCount > 1) {
                WarningSink.addOnce(
                        warnings,
                        warningKeys,
                        "ainf_multiple",
                        "Multiple header ainf chunks were present; only the first ainf chunk seeds the playback resource table.");
            }
            if (firstAinf.payloadLength() == 0) {
                WarningSink.addOnce(
                        warnings,
                        warningKeys,
                        "ainf_empty",
                        "The first header ainf chunk is empty and fails the playback resource-table header rule.");
            } else if ((firstAinf.payloadByte(0) & 0x40) != 0) {
                WarningSink.addOnce(
                        warnings,
                        warningKeys,
                        "ainf_rejected_bit40",
                        "The first header ainf chunk sets bit 0x40 in byte 0 and fails the playback resource-table header rule.");
            } else {
                activeAdatCount = firstAinf.payloadByte(0) & 0x3F;
                useAinfTable = true;
            }
        }

        Map<Integer, Integer> activeAdatIndexByOffset = new HashMap<Integer, Integer>();
        int resourceStageIndex = topLevelChunkIndexAtOffset(file, resourceStageOffset(file));
        int contiguousAdatCount = 0;
        if (resourceStageIndex >= 0) {
            for (int index = resourceStageIndex; index < file.topLevelChunks.size(); index++) {
                TopLevelChunk chunk = file.topLevelChunks.get(index);
                if (!"adat".equals(chunk.id)) {
                    break;
                }
                if (useAinfTable && contiguousAdatCount < activeAdatCount) {
                    activeAdatIndexByOffset.put(Integer.valueOf(chunk.offset), Integer.valueOf(contiguousAdatCount));
                }
                contiguousAdatCount += 1;
            }
        }
        if (useAinfTable && activeAdatCount > contiguousAdatCount) {
            WarningSink.addOnce(
                    warnings,
                    warningKeys,
                    "ainf_missing_adat_entries",
                    "The ainf table declares " + activeAdatCount
                            + " active adat entries; the contiguous adat run at the resource-stage boundary contains "
                            + contiguousAdatCount + ".");
        }

        int catalogIndex = 0;
        int adatIndex = 0;
        for (TopLevelChunk chunk : file.topLevelChunks) {
            if (!"resource".equals(chunk.category)) {
                continue;
            }

            int currentAdatIndex = "adat".equals(chunk.id) ? adatIndex++ : -1;
            Integer activeIndex = activeAdatIndexByOffset.get(Integer.valueOf(chunk.offset));
            int currentActiveAdatIndex = activeIndex != null ? activeIndex.intValue() : -1;
            LegacySelectorSummary legacy = null;
            if ("adat".equals(chunk.id) || "adpm".equals(chunk.id)) {
                legacy = extractLegacySelectorSummary(chunk.copyPayload());
                if (legacy == null && "adat".equals(chunk.id)) {
                    WarningSink.addOnce(
                            warnings,
                            warningKeys,
                            "resource_no_legacy_" + chunk.offset,
                            "Top-level adat chunk at 0x" + Integer.toHexString(chunk.offset)
                                    + " did not match the legacy selector summary heuristic.");
                }
            }

            entries.add(new AudioProgram.ResourceCatalogEntry(
                    catalogIndex++,
                    chunk.id,
                    chunk.offset,
                    chunk.length,
                    chunk.lengthFieldBytes,
                    currentAdatIndex,
                    currentActiveAdatIndex,
                    legacy != null ? legacy.selectorHeaderLength : -1,
                    legacy != null ? legacy.selectorId : -1,
                    legacy != null ? legacy.selectorFlags : -1,
                    legacy != null ? legacy.trailingPayloadLength : -1,
                    legacy != null ? legacy.adpmByte0 : -1,
                    legacy != null ? legacy.adpmByte1 : -1,
                    legacy != null ? legacy.adpmByte2Low3 : -1,
                    legacy != null ? legacy.adpmByte2Bit3 : -1));
        }
        return entries;
    }

    static List<AudioProgram.InitialChannelConfig> buildInitialChannelConfigs(
            MldDocument file,
            List<String> warnings,
            Set<String> warningKeys) {
        List<AudioProgram.InitialChannelConfig> entries = new ArrayList<AudioProgram.InitialChannelConfig>();
        TopLevelChunk firstThrd = firstHeaderChunk(file, "thrd");
        int thrdCount = countHeaderChunks(file, "thrd");

        if (firstThrd == null) {
            return entries;
        }
        if (thrdCount > 1) {
            WarningSink.addOnce(
                    warnings,
                    warningKeys,
                    "thrd_multiple",
                    "Multiple header thrd chunks were present; only the first thrd chunk seeds initial channel config.");
        }
        if (firstThrd.payloadLength() == 0) {
            WarningSink.addOnce(
                    warnings,
                    warningKeys,
                    "thrd_empty",
                    "The first header thrd chunk is empty and fails the initial channel-config header rule.");
            return entries;
        }

        int globalValue = firstThrd.payloadByte(0);
        int recordBytes = firstThrd.payloadLength() - 1;
        if ((recordBytes & 1) != 0) {
            WarningSink.addOnce(
                    warnings,
                    warningKeys,
                    "thrd_odd_payload",
                    "The first thrd chunk has one trailing record byte; native record count uses floor((length-1)/2).");
        }

        boolean[] seenSynth = new boolean[16];
        boolean[] seenAudio = new boolean[16];
        int recordCount = Math.max(0, recordBytes / 2);
        for (int i = 0; i < recordCount; i++) {
            int recordOffset = 1 + (i * 2);
            int first = firstThrd.payloadByte(recordOffset);
            int second = firstThrd.payloadByte(recordOffset + 1);
            int logicalChannel = first & 0x0F;
            boolean audioTarget = (second & 0x20) != 0;
            boolean[] seen = audioTarget ? seenAudio : seenSynth;
            if (seen[logicalChannel]) {
                WarningSink.addOnce(
                        warnings,
                        warningKeys,
                        "thrd_duplicate_" + (audioTarget ? "audio" : "synth") + "_" + logicalChannel,
                        "Duplicate thrd record for " + (audioTarget ? "audio" : "synth")
                                + " logical channel " + logicalChannel + " was ignored after the first entry.");
                continue;
            }
            seen[logicalChannel] = true;

            int rawSubvalue = second & 0x1F;
            entries.add(new AudioProgram.InitialChannelConfig(
                    firstThrd.offset,
                    globalValue,
                    logicalChannel,
                    audioTarget ? "audio" : "synth",
                    rawSubvalue));
        }
        return entries;
    }

    static void handleEvent(
            ResourceEvent resourceEvent,
            List<AudioProgram.ResourceCatalogEntry> resourceCatalog,
            List<AudioProgram.ResourceEventState> resourceEvents,
            int[] voiceMap,
            TimingState.Runtime nativeTimingState,
            List<String> warnings,
            Set<String> warningKeys) {
        int lane = -1;
        int logicalChannel = -1;
        String target = "resource";
        int resourceIndex = -1;
        int linkedCatalogIndex = -1;
        int linkedChunkOffset = -1;
        int extraParamLow6 = -1;
        int extraParam2x = -1;
        int valueLow6 = -1;
        int value2x = -1;
        int rawSubvalue = -1;
        boolean channelDispatchEligible = false;
        boolean clearWhenOutOfRange = false;
        boolean auxiliary3dDispatchCandidate = false;
        int auxiliary3dLow5 = -1;
        int auxiliary3dDistanceRaw = -1;
        int auxiliary3dAngleA = -1;
        int auxiliary3dAngleB = -1;
        int auxiliary3dDurationRaw = -1;
        int auxiliary3dDurationMs = -1;

        switch (resourceEvent.command) {
            case 0x00:
                if (resourceEvent.bodyLength() >= 1) {
                    lane = (resourceEvent.bodyByte(0) >> 6) & 0x03;
                    resourceIndex = resourceEvent.bodyByte(0) & 0x3F;
                    logicalChannel = lane + (4 * resourceEvent.trackIndex);
                    extraParamLow6 = resourceEvent.bodyLength() >= 2 ? resourceEvent.bodyByte(1) & 0x3F : 63;
                    extraParam2x = 2 * extraParamLow6;
                    AudioProgram.ResourceCatalogEntry linked = findActiveAdatByIndex(resourceCatalog, resourceIndex);
                    if (linked != null) {
                        linkedCatalogIndex = linked.catalogIndex;
                        linkedChunkOffset = linked.offset;
                    } else {
                        WarningSink.addOnce(
                                warnings,
                                warningKeys,
                                "adat_unlinked_" + resourceIndex,
                                "Resource-start index " + resourceIndex
                                        + " has no entry in the active ainf/adat playback table.");
                    }
                }
                break;
            case 0x01:
                if (resourceEvent.bodyLength() >= 1) {
                    lane = (resourceEvent.bodyByte(0) >> 6) & 0x03;
                    resourceIndex = resourceEvent.bodyByte(0) & 0x3F;
                    logicalChannel = lane + (4 * resourceEvent.trackIndex);
                }
                break;
            case 0x80:
            case 0x81:
                if (resourceEvent.bodyLength() >= 1) {
                    lane = (resourceEvent.bodyByte(0) >> 6) & 0x03;
                    logicalChannel = lane + (4 * resourceEvent.trackIndex);
                    target = "audio";
                    valueLow6 = resourceEvent.bodyByte(0) & 0x3F;
                    value2x = 2 * valueLow6;
                }
                break;
            case 0x90:
                if (resourceEvent.bodyLength() >= 1) {
                    int packed = resourceEvent.bodyByte(0);
                    lane = (packed >> 6) & 0x03;
                    boolean audioTarget = (packed & 0x20) != 0;
                    target = audioTarget ? "audio" : "synth";
                    rawSubvalue = packed & 0x1F;
                    clearWhenOutOfRange = rawSubvalue == 31;
                    int localLane = lane + (4 * resourceEvent.trackIndex);
                    if (audioTarget) {
                        logicalChannel = localLane;
                        channelDispatchEligible = true;
                    } else {
                        logicalChannel = resolveVoiceMap(voiceMap, localLane);
                        channelDispatchEligible = logicalChannel >= 0 && logicalChannel < 16;
                    }
                }
                break;
            case 0xF0:
                target = "3d";
                if (resourceEvent.trackIndex == 0 && resourceEvent.bodyLength() >= 6) {
                    auxiliary3dDispatchCandidate = true;
                    auxiliary3dLow5 = resourceEvent.bodyByte(0) & 0x1F;
                    auxiliary3dDistanceRaw = resourceEvent.bodyByte(1);
                    auxiliary3dAngleA = decodeAuxiliary3dAngleA(resourceEvent.bodyByte(2));
                    auxiliary3dAngleB = decodeAuxiliary3dAngleB(resourceEvent.bodyByte(3));
                    auxiliary3dDurationRaw = ((resourceEvent.bodyByte(4)) << 8)
                            | (resourceEvent.bodyByte(5));
                    auxiliary3dDurationMs = TimingState.durationMilliseconds(auxiliary3dDurationRaw, nativeTimingState);
                }
                break;
            default:
                break;
        }

        resourceEvents.add(new AudioProgram.ResourceEventState(
                resourceEvent.trackIndex,
                resourceEvent.rawTick,
                resourceEvent.command,
                resourceEvent.name,
                lane,
                logicalChannel,
                target,
                resourceIndex,
                linkedCatalogIndex,
                linkedChunkOffset,
                extraParamLow6,
                extraParam2x,
                valueLow6,
                value2x,
                rawSubvalue,
                channelDispatchEligible,
                clearWhenOutOfRange,
                auxiliary3dDispatchCandidate,
                auxiliary3dLow5,
                auxiliary3dDistanceRaw,
                auxiliary3dAngleA,
                auxiliary3dAngleB,
                auxiliary3dDurationRaw,
                auxiliary3dDurationMs));
    }

    private static int resourceStageOffset(MldDocument file) {
        return 10 + Math.max(0, file.headerLength);
    }

    private static TopLevelChunk firstHeaderChunk(MldDocument file, String id) {
        int headerEnd = resourceStageOffset(file);
        for (TopLevelChunk chunk : file.topLevelChunks) {
            int chunkEnd = chunk.offset + 4 + chunk.lengthFieldBytes + chunk.length;
            if (id.equals(chunk.id) && chunk.offset >= 13 && chunkEnd <= headerEnd) {
                return chunk;
            }
        }
        return null;
    }

    private static int countHeaderChunks(MldDocument file, String id) {
        int count = 0;
        int headerEnd = resourceStageOffset(file);
        for (TopLevelChunk chunk : file.topLevelChunks) {
            int chunkEnd = chunk.offset + 4 + chunk.lengthFieldBytes + chunk.length;
            if (id.equals(chunk.id) && chunk.offset >= 13 && chunkEnd <= headerEnd) {
                count += 1;
            }
        }
        return count;
    }

    private static int topLevelChunkIndexAtOffset(MldDocument file, int offset) {
        for (int index = 0; index < file.topLevelChunks.size(); index++) {
            if (file.topLevelChunks.get(index).offset == offset) {
                return index;
            }
        }
        return -1;
    }

    private static AudioProgram.ResourceCatalogEntry findActiveAdatByIndex(
            List<AudioProgram.ResourceCatalogEntry> resourceCatalog,
            int adatIndex) {
        for (AudioProgram.ResourceCatalogEntry entry : resourceCatalog) {
            if ("adat".equals(entry.chunkId) && entry.activeAdatIndex == adatIndex) {
                return entry;
            }
        }
        return null;
    }

    private static LegacySelectorSummary extractLegacySelectorSummary(byte[] payload) {
        if (payload.length < 4) {
            return null;
        }
        int selectorHeaderLength = readBe16(payload, 0);
        if (selectorHeaderLength < 2) {
            return null;
        }
        int selectorSectionEnd = 2 + selectorHeaderLength;
        if (selectorSectionEnd > payload.length) {
            return null;
        }

        int selectorId = payload[2] & 0xFF;
        int selectorFlags = payload[3] & 0xFF;
        int trailingPayloadLength = payload.length - selectorSectionEnd;
        int adpmByte0 = -1;
        int adpmByte1 = -1;
        int adpmByte2Low3 = -1;
        int adpmByte2Bit3 = -1;

        int offset = 4;
        while (offset + 6 <= selectorSectionEnd) {
            String id = new String(payload, offset, 4);
            int length = readBe16(payload, offset + 4);
            int payloadStart = offset + 6;
            int payloadEnd = payloadStart + length;
            if (payloadEnd > selectorSectionEnd) {
                return null;
            }
            if ("adpm".equals(id) && length >= 3) {
                adpmByte0 = payload[payloadStart] & 0xFF;
                adpmByte1 = payload[payloadStart + 1] & 0xFF;
                int byte2 = payload[payloadStart + 2] & 0xFF;
                adpmByte2Low3 = byte2 & 0x07;
                adpmByte2Bit3 = (byte2 >> 3) & 0x01;
                break;
            }
            offset = payloadEnd;
        }

        return new LegacySelectorSummary(
                selectorHeaderLength,
                selectorId,
                selectorFlags,
                trailingPayloadLength,
                adpmByte0,
                adpmByte1,
                adpmByte2Low3,
                adpmByte2Bit3);
    }

    private static int decodeAuxiliary3dAngleA(int raw) {
        int angle = (3 * raw) - 384;
        angle = clamp(-180, 180, angle);
        return angle < 0 ? angle + 360 : angle;
    }

    private static int decodeAuxiliary3dAngleB(int raw) {
        return clamp(-90, 90, (3 * raw) - 384);
    }

    private static int resolveVoiceMap(int[] voiceMap, int lane) {
        return lane >= 0 && lane < voiceMap.length ? voiceMap[lane] : lane;
    }

    private static int readBe16(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    private static int clamp(int min, int max, int value) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class LegacySelectorSummary {
        final int selectorHeaderLength;
        final int selectorId;
        final int selectorFlags;
        final int trailingPayloadLength;
        final int adpmByte0;
        final int adpmByte1;
        final int adpmByte2Low3;
        final int adpmByte2Bit3;

        LegacySelectorSummary(
                int selectorHeaderLength,
                int selectorId,
                int selectorFlags,
                int trailingPayloadLength,
                int adpmByte0,
                int adpmByte1,
                int adpmByte2Low3,
                int adpmByte2Bit3) {
            this.selectorHeaderLength = selectorHeaderLength;
            this.selectorId = selectorId;
            this.selectorFlags = selectorFlags;
            this.trailingPayloadLength = trailingPayloadLength;
            this.adpmByte0 = adpmByte0;
            this.adpmByte1 = adpmByte1;
            this.adpmByte2Low3 = adpmByte2Low3;
            this.adpmByte2Bit3 = adpmByte2Bit3;
        }
    }
}
