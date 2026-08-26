package normalize;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import event.MachineDependentEvent;
import event.TrackDecodeResult;
import event.TrackEvent;

public final class MdNormalizer {
    private static final DescriptorRule[] DESCRIPTORS = new DescriptorRule[] {
            rule(0, 0x104, bytes(0x61, 0x81), bytes()),
            rule(1, 0x105, bytes(0x61, 0x82), bytes()),
            rule(2, 0x109, bytes(0x61, 0x83), bytes()),
            rule(3, 0x106, bytes(0x61, 0x84), bytes()),
            rule(4, 0x000, bytes(0x21, 0x81), bytes()),
            rule(5, 0x001, bytes(0x21, 0x82), bytes()),
            rule(6, 0x002, bytes(0x21, 0x83), bytes()),
            rule(7, 0x003, bytes(0x21, 0x86), bytes()),
            rule(8, 0x400, bytes(0x11), bytes(0x01, 0xF0, 0x07)),
            rule(9, 0x401, bytes(0x11), bytes(0x01, 0xF1)),
            rule(10, 0x001, bytes(0x41, 0x80), bytes()),
            rule(11, 0x000, bytes(0x41, 0x81), bytes()),
            rule(12, 0x104, bytes(0x41, 0x81), bytes()),
            rule(13, 0x105, bytes(0x41, 0x82), bytes()),
            rule(14, 0x002, bytes(0x41, 0x83), bytes()),
            rule(15, 0x106, bytes(0x41, 0x84), bytes()),
            rule(16, 0x003, bytes(0x41, 0x86), bytes()),
            rule(17, 0x103, bytes(0x41, 0x8F), bytes()),
            rule(18, 0x000, bytes(0x71, 0x81), bytes()),
            rule(19, 0x104, bytes(0x71, 0x81), bytes()),
            rule(20, 0x001, bytes(0x21, 0x82), bytes()),
            rule(21, 0x105, bytes(0x71, 0x82), bytes()),
            rule(22, 0x002, bytes(0x71, 0x83), bytes()),
            rule(23, 0x106, bytes(0x71, 0x84), bytes()),
            rule(24, 0x003, bytes(0x71, 0x86), bytes()),
            rule(25, 0x103, bytes(0x71, 0x8F), bytes()),
            rule(26, 0x402, bytes(0x31, 0x10), bytes()),
            rule(27, 0x104, bytes(0x31, 0x81), bytes()),
            rule(28, 0x105, bytes(0x31, 0x82), bytes()),
            rule(29, 0x106, bytes(0x31, 0x84), bytes()),
            rule(30, 0x103, bytes(0x31, 0x8F), bytes()),
            rule(31, 0x100, bytes(0x61, 0x10), bytes()),
            rule(32, 0x101, bytes(0x61, 0x11), bytes()),
            rule(33, 0x102, bytes(0x61, 0x12), bytes()),
            rule(34, 0x004, bytes(0x21, 0x90), bytes()),
            rule(35, 0x005, bytes(0x21, 0x91), bytes()),
            rule(36, 0x006, bytes(0x21, 0x92), bytes()),
            rule(37, 0x007, bytes(0x21, 0x93), bytes()),
            rule(38, 0x403, bytes(0x11), bytes(0x01, 0xF0, 0x05)),
            rule(39, 0x404, bytes(0x11), bytes(0x01, 0xF0, 0x06)),
            rule(40, 0x100, bytes(0x41, 0x10), bytes()),
            rule(41, 0x101, bytes(0x41, 0x11), bytes()),
            rule(42, 0x102, bytes(0x41, 0x12), bytes()),
            rule(43, 0x004, bytes(0x41, 0x90), bytes()),
            rule(44, 0x005, bytes(0x41, 0x91), bytes()),
            rule(45, 0x006, bytes(0x41, 0x92), bytes()),
            rule(46, 0x007, bytes(0x41, 0x93), bytes()),
            rule(47, 0x100, bytes(0x71, 0x10), bytes()),
            rule(48, 0x101, bytes(0x71, 0x11), bytes()),
            rule(49, 0x102, bytes(0x71, 0x12), bytes()),
            rule(50, 0x004, bytes(0x71, 0x90), bytes()),
            rule(51, 0x005, bytes(0x71, 0x91), bytes()),
            rule(52, 0x006, bytes(0x71, 0x92), bytes()),
            rule(53, 0x007, bytes(0x71, 0x93), bytes()),
            rule(54, 0x100, bytes(0x31, 0x30), bytes()),
            rule(55, 0x101, bytes(0x31, 0x31), bytes()),
            rule(56, 0x102, bytes(0x31, 0x32), bytes()),
            rule(57, 0x100, bytes(0x01, 0x10), bytes()),
            rule(58, 0x101, bytes(0x01, 0x11), bytes()),
            rule(59, 0x102, bytes(0x01, 0x12), bytes()),
            rule(60, 0x405, bytes(0x11), bytes(0x01, 0xF0, 0x03)),
            rule(61, 0x406, bytes(0x11), bytes(0x01, 0xF0, 0x04)),
            rule(62, 0x407, bytes(0x11), bytes(0x01, 0xF2, 0x07))
    };

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

        DescriptorMatch match = firstMatch(payload);
        if (match == null) {
            return unknown(event, prefix, selector, rawHex, details);
        }

        details.put("descriptorIndex", Integer.valueOf(match.rule.index));
        details.put("handlerId", Integer.valueOf(match.rule.handlerId));
        details.put("matchedPrefixLength", Integer.valueOf(match.bodyOffset));
        details.put("bodyOffset", Integer.valueOf(match.bodyOffset));
        details.put("bodyLength", Integer.valueOf(payload.length - match.bodyOffset));
        details.put("matchedPrefix", toHex(payload, 0, match.bodyOffset));

        String family = populateHandlerDetails(match.rule.handlerId, payload, match.bodyOffset, details);
        return known(event, prefix, selector, family, "high", rawHex, details);
    }

    private static String populateHandlerDetails(
            int handlerId,
            byte[] payload,
            int bodyOffset,
            Map<String, Object> details) {
        switch (handlerId) {
            case 0x000:
                details.put("layeredNativeEffect", "audio_level_backend");
                details.put("monolithicNativeEffect", "no_persistent_side_effect");
                populatePackedChannelValue(payload, bodyOffset, details);
                return "audio_channel_level";
            case 0x001:
                details.put("layeredNativeEffect", "audio_pan_backend");
                details.put("monolithicNativeEffect", "no_persistent_side_effect");
                populatePackedChannelValue(payload, bodyOffset, details);
                return "audio_channel_pan";
            case 0x104:
                details.put("phase1Ignored", Boolean.TRUE);
                details.put("layeredNativeEffect", "audio_level_backend");
                details.put("monolithicNativeEffect", "no_action");
                populatePackedChannelValue(payload, bodyOffset, details);
                return "audio_channel_level_phase_gated";
            case 0x105:
                details.put("phase1Ignored", Boolean.TRUE);
                details.put("layeredNativeEffect", "audio_pan_backend");
                details.put("monolithicNativeEffect", "no_action");
                populatePackedChannelValue(payload, bodyOffset, details);
                return "audio_channel_pan_phase_gated";
            case 0x002:
                details.put("layeredNativeEffect", "audio_8000_load_and_start_gate");
                details.put("monolithicNativeEffect", "slot_presence_state");
                populate8000Slot(payload, bodyOffset, details, false);
                return "audio_8000_slot";
            case 0x003:
                details.put("layeredNativeEffect", "audio_8000_cached_load_and_start_gate");
                details.put("monolithicNativeEffect", "slot_cache_state");
                populate8000Slot(payload, bodyOffset, details, true);
                return "audio_8000_cached_slot";
            case 0x103:
                details.put("layeredNativeEffect", "no_action");
                details.put("monolithicNativeEffect", "no_action");
                return "machine_noop";
            case 0x109:
                details.put("layeredNativeEffect", "audio_8001_load_and_start_gate");
                details.put("monolithicNativeEffect", "slot_cache_state");
                populate8001Inline(payload, bodyOffset, details);
                return "audio_8001_inline_slot";
            case 0x106:
                details.put("layeredNativeEffect", "audio_8001_load_and_start_gate");
                details.put("monolithicNativeEffect", "slot_cache_state");
                populate8001DurationCount(payload, bodyOffset, details);
                return "audio_8001_duration_count_slot";
            case 0x004:
            case 0x005:
            case 0x007:
                details.put("monolithicNativeEffect", "no_action");
                populateSynthBridgeReinsertOne(payload, bodyOffset, details, 1);
                return "synth_request_subtype1_reinsert";
            case 0x006:
                details.put("monolithicNativeEffect", "no_action");
                populateSynthBridgeBody(payload, bodyOffset, details, 1);
                return "synth_request_subtype1_body";
            case 0x100:
            case 0x101:
            case 0x102:
                details.put("monolithicNativeEffect", "no_action");
                populateSynthBridgeReinsertOne(payload, bodyOffset, details, 0);
                return "synth_request_subtype0_reinsert";
            case 0x400:
                details.put("layeredNativeEffect", "audio_8002_load_state");
                details.put("monolithicNativeEffect", "audio_8002_state");
                populate8002(payload, bodyOffset, details);
                return "audio_8002_compact_load";
            case 0x401:
                details.put("layeredNativeEffect", "compact_audio_control");
                details.put("monolithicNativeEffect", "no_runtime_side_effect");
                populateCompactControl(payload, bodyOffset, details);
                return "compact_slot_control";
            case 0x402:
                details.put("layeredNativeEffect", "mixed_compact_dispatch");
                details.put("monolithicNativeEffect", "dispatch_7_8_state_only");
                populateMixedDispatch(payload, bodyOffset, details);
                return "mixed_compact_dispatch";
            case 0x403:
            case 0x404:
            case 0x405:
            case 0x406:
                details.put("monolithicNativeEffect", "no_action");
                populateSynthBridgeReinsertTwo(payload, bodyOffset, details, 2);
                return "synth_request_subtype2_reinsert";
            case 0x407:
                details.put("layeredNativeEffect", "route_state_and_conditional_backend");
                details.put("monolithicNativeEffect", "route_state");
                populateRouteMap(payload, bodyOffset, details);
                return "compact_route_map";
            default:
                details.put("layeredNativeEffect", "unclassified_handler");
                details.put("monolithicNativeEffect", "unclassified_handler");
                return "machine_handler";
        }
    }

    private static void populatePackedChannelValue(byte[] payload, int offset, Map<String, Object> details) {
        if (!has(payload, offset, 1)) return;
        int packed = u8(payload, offset);
        details.put("channel", Integer.valueOf((packed >> 6) & 0x03));
        details.put("valueLow6", Integer.valueOf(packed & 0x3F));
        details.put("value2x", Integer.valueOf((packed & 0x3F) * 2));
    }

    private static void populate8000Slot(
            byte[] payload,
            int offset,
            Map<String, Object> details,
            boolean cachedVariant) {
        if (!has(payload, offset, 2)) return;
        int channelSlot = u8(payload, offset);
        int opFormat = u8(payload, offset + 1);
        int operation = (opFormat >> 6) & 0x03;
        int formatCode = opFormat & 0x3F;
        details.put("channel", Integer.valueOf((channelSlot >> 6) & 0x03));
        details.put("slot", Integer.valueOf(channelSlot & 0x3F));
        details.put("operation", Integer.valueOf(operation));
        details.put("formatCode", Integer.valueOf(formatCode));
        details.put("audioTypeSelector", Integer.valueOf(0x8000));
        details.put("codedBits", Integer.valueOf(4));
        details.put("channelCount", Integer.valueOf(1));
        details.put("layeredStartChannelEligible", Boolean.valueOf(((channelSlot >> 6) & 0x03) <= 1));
        if (formatCode == 4) details.put("sampleRate", Integer.valueOf(4000));
        if (formatCode == 5) details.put("sampleRate", Integer.valueOf(8000));
        if (cachedVariant && formatCode == 6) details.put("sampleRate", Integer.valueOf(16000));
        int payloadOffset = offset + (cachedVariant ? 3 : 2);
        if (cachedVariant && has(payload, offset + 2, 1)) {
            details.put("controlFlag", Integer.valueOf(u8(payload, offset + 2) & 0x01));
        }
        if (payloadOffset <= payload.length) {
            details.put("codedPayloadLength", Integer.valueOf(payload.length - payloadOffset));
        }
        if (!cachedVariant) {
            details.put("layeredOperation", operationName8000(operation));
        }
    }

    private static String operationName8000(int operation) {
        switch (operation) {
            case 0:
            case 1:
                return "load_refresh_then_start_gate";
            case 2:
                return "start_existing";
            case 3:
                return "ignore";
            default:
                return "unknown";
        }
    }

    private static void populate8001Inline(byte[] payload, int offset, Map<String, Object> details) {
        if (!populate8001Header(payload, offset, details)) return;
        int codedPayloadOffset = offset + 3;
        int codedPayloadLength = payload.length - codedPayloadOffset;
        details.put("codedPayloadOffset", Integer.valueOf(codedPayloadOffset));
        details.put("codedPayloadLength", Integer.valueOf(codedPayloadLength));
        details.put("durationByteCount", Integer.valueOf(codedPayloadLength));
        put8001DurationMs(details, codedPayloadLength);
    }

    private static void populate8001DurationCount(byte[] payload, int offset, Map<String, Object> details) {
        if (!populate8001Header(payload, offset, details)) return;
        if (!has(payload, offset + 3, 4)) return;
        int durationByteCount = readBe32(payload, offset + 3);
        int codedPayloadOffset = offset + 7;
        details.put("durationByteCount", Integer.valueOf(durationByteCount));
        details.put("codedPayloadOffset", Integer.valueOf(codedPayloadOffset));
        details.put("codedPayloadLength", Integer.valueOf(payload.length - codedPayloadOffset));
        put8001DurationMs(details, durationByteCount);
    }


    private static void put8001DurationMs(Map<String, Object> details, int byteCount) {
        Object sampleRateValue = details.get("sampleRate");
        Object codedBitsValue = details.get("codedBits");
        if (!(sampleRateValue instanceof Number) || !(codedBitsValue instanceof Number)) return;
        int sampleRate = ((Number) sampleRateValue).intValue();
        int codedBits = ((Number) codedBitsValue).intValue();
        if (sampleRate <= 0 || codedBits <= 0 || 8 % codedBits != 0) return;
        long samplesPerByte = 8 / codedBits;
        long byteCountUnsigned = Integer.toUnsignedLong(byteCount);
        long sampleCount = (byteCountUnsigned * samplesPerByte) & 0xFFFFFFFFL;
        long durationMs = Math.round((sampleCount * 1000.0) / sampleRate);
        details.put("durationMs", Long.valueOf(durationMs));
    }

    private static boolean populate8001Header(byte[] payload, int offset, Map<String, Object> details) {
        if (!has(payload, offset, 3)) return false;
        int channelSlot = u8(payload, offset);
        int opFormat = u8(payload, offset + 1);
        int flagByte = u8(payload, offset + 2);
        int formatCode = opFormat & 0x3F;
        details.put("channel", Integer.valueOf((channelSlot >> 6) & 0x03));
        details.put("slot", Integer.valueOf(channelSlot & 0x3F));
        details.put("operation", Integer.valueOf((opFormat >> 6) & 0x03));
        details.put("formatCode", Integer.valueOf(formatCode));
        details.put("controlFlag", Integer.valueOf(flagByte & 0x01));
        details.put("audioTypeSelector", Integer.valueOf(0x8001));
        FormatCodeSummary summary = legacy8001FormatSummary(formatCode);
        if (summary != null) {
            details.put("sampleRate", Integer.valueOf(summary.sampleRate));
            details.put("codedBits", Integer.valueOf(summary.codedBits));
            details.put("channelCount", Integer.valueOf(summary.channelCount));
        }
        return true;
    }

    private static void populate8002(byte[] payload, int offset, Map<String, Object> details) {
        if (!has(payload, offset, 4)) return;
        int slot = u8(payload, offset);
        int control = u8(payload, offset + 1);
        int rateField = readBe16(payload, offset + 2);
        details.put("slot", Integer.valueOf(slot));
        details.put("formatLow2", Integer.valueOf(control & 0x03));
        details.put("flagBit7", Integer.valueOf((control >> 7) & 0x01));
        int codedPayloadLength = payload.length - (offset + 4);
        details.put("rateField", Integer.valueOf(rateField));
        details.put("codedBits", Integer.valueOf(4));
        details.put("channelCount", Integer.valueOf(((control >> 7) & 0x01) + 1));
        details.put("audioTypeSelector", Integer.valueOf(0x8002));
        details.put("codedPayloadOffset", Integer.valueOf(offset + 4));
        details.put("codedPayloadLength", Integer.valueOf(codedPayloadLength));
        if (rateField > 0) {
            long sampleCount = (((long) codedPayloadLength) * 2L) & 0xFFFFFFFFL;
            details.put("durationMs", Long.valueOf(Math.round((sampleCount * 1000.0) / rateField)));
        }
    }

    private static void populateCompactControl(byte[] payload, int offset, Map<String, Object> details) {
        if (!has(payload, offset, 1)) return;
        int command = u8(payload, offset);
        int opcode = command & 0x0F;
        details.put("channel", Integer.valueOf((command >> 6) & 0x03));
        details.put("opcode", Integer.valueOf(opcode));
        details.put("phase1Ignored", Boolean.TRUE);
        if ((opcode == 3 || opcode == 4) && has(payload, offset + 1, 2)) {
            details.put("slot", Integer.valueOf(u8(payload, offset + 1) & 0x1F));
            details.put("valueLow7", Integer.valueOf(u8(payload, offset + 2) & 0x7F));
        } else if (opcode == 5 && has(payload, offset + 1, 1)) {
            details.put("slot", Integer.valueOf(u8(payload, offset + 1) & 0x1F));
        } else if (opcode == 6 && has(payload, offset + 1, 2)) {
            details.put("reservedByte", Integer.valueOf(u8(payload, offset + 1)));
            details.put("value", Integer.valueOf(u8(payload, offset + 2)));
        } else if (opcode == 7 && has(payload, offset + 1, 1)) {
            details.put("value", Integer.valueOf(u8(payload, offset + 1)));
        }
    }

    private static void populateMixedDispatch(byte[] payload, int offset, Map<String, Object> details) {
        if (!has(payload, offset, 1)) return;
        int command = u8(payload, offset);
        int dispatchCode = command & 0x3F;
        details.put("channel", Integer.valueOf((command >> 6) & 0x03));
        details.put("dispatchCode", Integer.valueOf(dispatchCode));
        details.put("phase1Ignored", Boolean.TRUE);
        switch (dispatchCode) {
            case 7:
                details.put("delegateHandlerId", Integer.valueOf(0x400));
                populate8002(payload, offset + 1, details);
                break;
            case 8:
                details.put("delegateHandlerId", Integer.valueOf(0x407));
                populateRouteMap(payload, offset + 1, details);
                break;
            case 9:
            case 15:
                if (has(payload, offset + 1, 2)) {
                    details.put("slot", Integer.valueOf(u8(payload, offset + 1) & 0x1F));
                    details.put("valueLow7", Integer.valueOf(u8(payload, offset + 2) & 0x7F));
                }
                break;
            case 10:
                if (has(payload, offset + 1, 1)) {
                    details.put("slot", Integer.valueOf(u8(payload, offset + 1) & 0x1F));
                }
                break;
            case 11:
                if (has(payload, offset + 1, 2)) {
                    details.put("reservedByte", Integer.valueOf(u8(payload, offset + 1)));
                    details.put("value", Integer.valueOf(u8(payload, offset + 2)));
                }
                break;
            case 12:
                if (has(payload, offset + 1, 1)) {
                    details.put("value", Integer.valueOf(u8(payload, offset + 1)));
                }
                break;
            case 4:
            case 5:
            case 6:
            case 16:
                details.put("synthRequestType", Integer.valueOf(3));
                details.put("synthRequestSubtype", Integer.valueOf(2));
                details.put("forwardedPayloadOffset", Integer.valueOf(offset));
                details.put("forwardedPayloadLength", Integer.valueOf(payload.length - offset));
                populateSynthDownstream(command, 2, details);
                break;
            default:
                break;
        }
    }

    private static void populateRouteMap(byte[] payload, int offset, Map<String, Object> details) {
        if (!has(payload, offset, 16)) return;
        List<Integer> classA = new ArrayList<Integer>(16);
        List<Integer> classB = new ArrayList<Integer>(16);
        List<Integer> bit1 = new ArrayList<Integer>(16);
        List<Integer> bit0 = new ArrayList<Integer>(16);
        for (int i = 0; i < 16; i++) {
            int value = u8(payload, offset + i);
            classA.add(Integer.valueOf((value >> 4) & 0x03));
            classB.add(Integer.valueOf((value >> 2) & 0x03));
            bit1.add(Integer.valueOf((value >> 1) & 0x01));
            bit0.add(Integer.valueOf(value & 0x01));
        }
        details.put("laneCount", Integer.valueOf(16));
        details.put("classA", classA);
        details.put("classB", classB);
        details.put("bit1", bit1);
        details.put("bit0", bit0);
    }

    private static void populateSynthBridgeReinsertOne(
            byte[] payload,
            int bodyOffset,
            Map<String, Object> details,
            int subtype) {
        int forwardedOffset = Math.max(0, bodyOffset - 1);
        details.put("synthRequestType", Integer.valueOf(3));
        details.put("synthRequestSubtype", Integer.valueOf(subtype));
        details.put("forwardedPayloadOffset", Integer.valueOf(forwardedOffset));
        details.put("forwardedPayloadLength", Integer.valueOf(payload.length - forwardedOffset));
        int first = payload.length > forwardedOffset ? u8(payload, forwardedOffset) : -1;
        populateSynthDownstream(first, subtype, details);
    }

    private static void populateSynthBridgeBody(
            byte[] payload,
            int bodyOffset,
            Map<String, Object> details,
            int subtype) {
        details.put("synthRequestType", Integer.valueOf(3));
        details.put("synthRequestSubtype", Integer.valueOf(subtype));
        details.put("forwardedPayloadOffset", Integer.valueOf(bodyOffset));
        details.put("forwardedPayloadLength", Integer.valueOf(payload.length - bodyOffset));
        int first = payload.length > bodyOffset ? u8(payload, bodyOffset) : -1;
        populateSynthDownstream(first, subtype, details);
    }

    private static void populateSynthBridgeReinsertTwo(
            byte[] payload,
            int bodyOffset,
            Map<String, Object> details,
            int subtype) {
        int forwardedOffset = Math.max(0, bodyOffset - 2);
        details.put("synthRequestType", Integer.valueOf(3));
        details.put("synthRequestSubtype", Integer.valueOf(subtype));
        details.put("forwardedPayloadOffset", Integer.valueOf(forwardedOffset));
        details.put("forwardedPayloadLength", Integer.valueOf(payload.length - forwardedOffset));
        int first = payload.length > forwardedOffset ? u8(payload, forwardedOffset) : -1;
        int second = payload.length > forwardedOffset + 1 ? u8(payload, forwardedOffset + 1) : -1;
        populateSynthDownstream(first, subtype, details);
        if (first == 0xF0) {
            details.put("forwardedF0Subcommand", Integer.valueOf(second));
            if (second == 0x04 || second == 0x05) {
                details.put("layeredNormalSynthEffect", "subtype2_f0_control");
            } else {
                details.put("layeredNormalSynthEffect", "no_action_for_supplied_normal_synth");
            }
        }
    }

    private static void populateSynthDownstream(int first, int subtype, Map<String, Object> details) {
        if (first < 0) return;
        details.put("forwardedFirstByte", Integer.valueOf(first));
        String normal = "no_action_for_supplied_normal_synth";
        if (subtype == 0 && (first == 0x12 || first == 0x32)) {
            normal = "subtype0_12_32_control";
        } else if (subtype == 1 && first == 0x93) {
            normal = "subtype1_93_control";
        }
        details.put("layeredNormalSynthEffect", normal);

        String ft = "no_action_for_supplied_ft_synth";
        if (first == 0x10) {
            ft = "ft_command_10_mode0_passthrough";
        } else if (first == 0x11) {
            ft = "ft_command_11_mode1_passthrough";
        } else if (first == 0x12) {
            ft = "ft_command_12_subdispatch";
        } else if (first == 0x40) {
            ft = "ft_command_40_structured_control";
        } else if (first == 0x41) {
            ft = "ft_command_41_structured_control";
        }
        details.put("layeredFtSynthEffect", ft);
    }

    private static DescriptorMatch firstMatch(byte[] payload) {
        for (DescriptorRule rule : DESCRIPTORS) {
            int offset = 0;
            if (!matches(payload, offset, rule.pattern1)) continue;
            offset += rule.pattern1.length;
            if (!matches(payload, offset, rule.pattern2)) continue;
            offset += rule.pattern2.length;
            return new DescriptorMatch(rule, offset);
        }
        return null;
    }

    private static boolean matches(byte[] payload, int offset, byte[] pattern) {
        if (offset < 0 || offset + pattern.length > payload.length) return false;
        for (int i = 0; i < pattern.length; i++) {
            if (payload[offset + i] != pattern[i]) return false;
        }
        return true;
    }

    private static boolean has(byte[] payload, int offset, int count) {
        return offset >= 0 && count >= 0 && offset + count <= payload.length;
    }

    private static int u8(byte[] payload, int offset) {
        return payload[offset] & 0xFF;
    }

    private static int readBe16(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    private static int readBe32(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24)
                | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
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

    private static String toHex(byte[] data) {
        return toHex(data, 0, data.length);
    }

    private static String toHex(byte[] data, int offset, int length) {
        StringBuilder builder = new StringBuilder(length * 2);
        for (int i = 0; i < length; i++) {
            builder.append(String.format("%02x", data[offset + i] & 0xFF));
        }
        return builder.toString();
    }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int i = 0; i < values.length; i++) result[i] = (byte) values[i];
        return result;
    }

    private static DescriptorRule rule(int index, int handlerId, byte[] pattern1, byte[] pattern2) {
        return new DescriptorRule(index, handlerId, pattern1, pattern2);
    }

    private static final class DescriptorRule {
        final int index;
        final int handlerId;
        final byte[] pattern1;
        final byte[] pattern2;

        DescriptorRule(int index, int handlerId, byte[] pattern1, byte[] pattern2) {
            this.index = index;
            this.handlerId = handlerId;
            this.pattern1 = pattern1;
            this.pattern2 = pattern2;
        }
    }

    private static final class DescriptorMatch {
        final DescriptorRule rule;
        final int bodyOffset;

        DescriptorMatch(DescriptorRule rule, int bodyOffset) {
            this.rule = rule;
            this.bodyOffset = bodyOffset;
        }
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
