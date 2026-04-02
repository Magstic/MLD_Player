package normalize;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import event.MachineDependentEvent;
import event.TrackDecodeResult;
import event.TrackEvent;

public final class MdNormalizer {
    public MdNormalizationResult normalize(List<TrackDecodeResult> tracks) {
        List<MdNormalizedEvent> normalized = new ArrayList<MdNormalizedEvent>();
        List<MdNormalizedEvent> unknown = new ArrayList<MdNormalizedEvent>();
        List<String> warnings = new ArrayList<String>();

        for (TrackDecodeResult track : tracks) {
            for (TrackEvent event : track.events) {
                if (!(event instanceof MachineDependentEvent)) {
                    continue;
                }

                MachineDependentEvent machineEvent = (MachineDependentEvent) event;
                MdNormalizedEvent normalizedEvent = normalizeEvent(machineEvent);
                if (normalizedEvent.known) {
                    normalized.add(normalizedEvent);
                } else {
                    unknown.add(normalizedEvent);
                }
            }
        }

        return new MdNormalizationResult(normalized, unknown, warnings);
    }

    private MdNormalizedEvent normalizeEvent(MachineDependentEvent event) {
        byte[] payload = event.payload;
        String rawHex = toHex(payload);
        int prefix = payload.length >= 1 ? payload[0] & 0xFF : -1;
        int selector = payload.length >= 2 ? payload[1] & 0xFF : -1;
        Map<String, Object> details = new LinkedHashMap<String, Object>();
        details.put("payloadLength", Integer.valueOf(payload.length));

        if (prefix == 0x71 && selector == 0x81 && payload.length >= 3) {
            int packed = payload[2] & 0xFF;
            details.put("channel", Integer.valueOf((packed >> 6) & 0x03));
            details.put("value", Integer.valueOf((packed & 0x3F) * 2));
            return known(event, prefix, selector, "audio_channel_level", "high", rawHex, details);
        }

        if (prefix == 0x71 && selector == 0x82 && payload.length >= 3) {
            int packed = payload[2] & 0xFF;
            details.put("channel", Integer.valueOf((packed >> 6) & 0x03));
            details.put("value", Integer.valueOf((packed & 0x3F) * 2));
            return known(event, prefix, selector, "audio_channel_pan", "high", rawHex, details);
        }

        if (prefix == 0x71 && selector == 0x83) {
            return known(event, prefix, selector, "audio_slot_release", "high", rawHex, details);
        }

        if (prefix == 0x71 && selector == 0x84) {
            populateLegacySlotLoadDetails(payload, details);
            return known(event, prefix, selector, "audio_slot_load", "high", rawHex, details);
        }

        if (prefix == 0x71 && selector == 0x86) {
            return known(event, prefix, selector, "audio_slot_start", "high", rawHex, details);
        }

        if (prefix == 0x71 && selector == 0x8F) {
            return known(event, prefix, selector, "selector_71_8f_sample_family", "high", rawHex, details);
        }

        if (prefix == 0x71 && selector == 0x10) {
            return known(event, prefix, selector, "synth_req3_reinsert_10", "high", rawHex, details);
        }

        if (prefix == 0x71 && selector == 0x11) {
            return known(event, prefix, selector, "synth_req3_reinsert_11", "high", rawHex, details);
        }

        if (prefix == 0x71 && selector == 0x12) {
            return known(event, prefix, selector, "synth_req3_reinsert_12", "high", rawHex, details);
        }

        if (prefix == 0x71 && selector >= 0x90 && selector <= 0x93) {
            details.put("handlerCandidate", "shared_req3_family");
            return known(event, prefix, selector, "selector_71_9x_family", "medium", rawHex, details);
        }

        if ((prefix == 0x21 || prefix == 0x41 || prefix == 0x71)
                && selector == 0x92
                && payload.length >= 3
                && ((payload[2] & 0xFF) == 0x40 || (payload[2] & 0xFF) == 0x41)) {
            details.put("ftSubtypeCandidate", String.format("0x%02X", payload[2] & 0xFF));
            return known(event, prefix, selector, "ft_passthrough_candidate_xx92", "medium", rawHex, details);
        }

        if ((prefix == 0x01 || prefix == 0x71) && selector >= 0xB0 && selector <= 0xB2) {
            return known(event, prefix, selector, "monolithic_pcm_control_family", "medium", rawHex, details);
        }

        return unknown(event, prefix, selector, rawHex, details);
    }

    private static MdNormalizedEvent known(
            MachineDependentEvent event,
            int prefix,
            int selector,
            String family,
            String confidence,
            String rawHex,
            Map<String, Object> details) {
        return new MdNormalizedEvent(
                event.trackIndex,
                event.eventIndex,
                event.rawTick,
                prefix,
                selector,
                family,
                confidence,
                true,
                rawHex,
                event.payload,
                details);
    }

    private static MdNormalizedEvent unknown(
            MachineDependentEvent event,
            int prefix,
            int selector,
            String rawHex,
            Map<String, Object> details) {
        return new MdNormalizedEvent(
                event.trackIndex,
                event.eventIndex,
                event.rawTick,
                prefix,
                selector,
                "unknown",
                "low",
                false,
                rawHex,
                event.payload,
                details);
    }

    private static void populateLegacySlotLoadDetails(byte[] payload, Map<String, Object> details) {
        if (payload.length < 9) {
            return;
        }

        int channelSlot = payload[2] & 0xFF;
        int modeFormat = payload[3] & 0xFF;
        int byte2 = payload[4] & 0xFF;
        int embeddedLength = readBe32(payload, 5);
        int embeddedOffset = 9;
        int availableLength = Math.max(0, Math.min(embeddedLength, payload.length - embeddedOffset));

        details.put("channelSlot", Integer.valueOf(channelSlot));
        details.put("channel", Integer.valueOf((channelSlot >> 6) & 0x03));
        details.put("slot", Integer.valueOf(channelSlot & 0x3F));
        details.put("mode", Integer.valueOf((modeFormat >> 6) & 0x03));
        details.put("formatCode", Integer.valueOf(modeFormat & 0x3F));
        details.put("byte2", Integer.valueOf(byte2));
        details.put("byte2LowBit", Integer.valueOf(byte2 & 0x01));
        details.put("embeddedLength", Integer.valueOf(embeddedLength));
        details.put("embeddedPayloadLength", Integer.valueOf(availableLength));
        details.put("audioTypeSelector", Integer.valueOf(0x8001));

        FormatCodeSummary summary = legacy8001FormatSummary(modeFormat & 0x3F);
        if (summary != null) {
            details.put("sampleRate", Integer.valueOf(summary.sampleRate));
            details.put("codedBits", Integer.valueOf(summary.codedBits));
            details.put("channelCount", Integer.valueOf(summary.channelCount));
        }
        if (availableLength != embeddedLength) {
            details.put("embeddedLengthTruncated", Boolean.TRUE);
        }
    }

    private static FormatCodeSummary legacy8001FormatSummary(int formatCode) {
        switch (formatCode) {
            case 4:
                return new FormatCodeSummary(8000, 2, 1);
            case 5:
                return new FormatCodeSummary(8000, 4, 1);
            case 12:
                return new FormatCodeSummary(16000, 2, 1);
            case 13:
                return new FormatCodeSummary(16000, 4, 1);
            case 20:
                return new FormatCodeSummary(32000, 2, 1);
            case 21:
                return new FormatCodeSummary(32000, 4, 1);
            default:
                return null;
        }
    }

    private static int readBe32(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24)
                | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }

    private static String toHex(byte[] data) {
        StringBuilder builder = new StringBuilder(data.length * 2);
        for (int i = 0; i < data.length; i++) {
            builder.append(String.format("%02x", data[i] & 0xFF));
        }
        return builder.toString();
    }

    private static final class FormatCodeSummary {
        final int sampleRate;
        final int codedBits;
        final int channelCount;

        FormatCodeSummary(int sampleRate, int codedBits, int channelCount) {
            this.sampleRate = sampleRate;
            this.codedBits = codedBits;
            this.channelCount = channelCount;
        }
    }
}
