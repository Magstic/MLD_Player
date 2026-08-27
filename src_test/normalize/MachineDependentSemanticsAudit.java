package normalize;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import mld.decode.MachineDependentEvent;
import mld.decode.DecodedTrack;
import mld.decode.TrackEvent;

/** Standalone regression audit for native MFi5 FF-FF machine-dependent dispatch. */
public final class MachineDependentSemanticsAudit {
    public static void main(String[] args) {
        auditEffectiveDescriptorProfile();

        List<MachineDependentEvent> source = new ArrayList<MachineDependentEvent>();
        source.add(md(0, 0x71, 0x81, 0x42));
        source.add(md(1, 0x41, 0x81, 0x43));
        source.add(md(2, 0x71, 0x82, 0x44));
        source.add(md(3, 0x71, 0x83, 0x02, 0x04, 0x55, 0x66));
        source.add(md(4, 0x61, 0x83, 0x01, 0x04, 0x01, 0x11, 0x22, 0x33));
        source.add(md(5, 0x71, 0x84, 0x01, 0x04, 0x01, 0x00, 0x00, 0x00, 0x10, 0x11, 0x22, 0x33));
        source.add(md(6, 0x11, 0x01, 0xF0, 0x07, 0x02, 0x81, 0x1F, 0x40, 0x55));
        source.add(md(7, 0x31, 0x10, 0x07, 0x03, 0x80, 0x1F, 0x40, 0x66));
        source.add(md(8, 0x71, 0x92, 0x40, 0x10, 0x20, 0x30, 0x40));
        source.add(md(9, 0x71, 0x92, 0x93, 0x66));
        source.add(md(10, 0x71, 0x93, 0x10, 0x20, 0x30, 0x40));
        source.add(md(11, 0x11, 0x01, 0xF0, 0x04, 0x12, 0x34));
        source.add(md(12, 0x71, 0x12, 0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66));
        source.add(md(13, 0x71, 0x10, 0x55, 0x66));
        source.add(md(14, 0x31, 0x32, 0x22, 0x33));
        source.add(md(15, 0x31, 0x10, 0x10, 0x55));
        source.add(md(16, 0x71, 0xB0, 0x01));
        source.add(md(17,
                0x11, 0x01, 0xF2, 0x07,
                0x10, 0x24, 0x38, 0x4C, 0x10, 0x24, 0x38, 0x4C,
                0x10, 0x24, 0x38, 0x4C, 0x10, 0x24, 0x38, 0x4C));

        List<TrackEvent> events = new ArrayList<TrackEvent>(source);
        DecodedTrack track = new DecodedTrack(
                0, 0, 0, 0, 0, 0, source.size(), events, Collections.<String>emptyList());
        MdNormalizationResult result = new MdNormalizer().normalize(Collections.singletonList(track));

        eq("known count", 17, result.normalizedEvents.size());
        eq("unknown count", 1, result.unknownEvents.size());

        MdNormalizedEvent e0 = at(result, 0);
        eq("71 81 first-match descriptor", 18, intDetail(e0, "descriptorIndex"));
        eq("71 81 handler", 0x000, intDetail(e0, "handlerId"));
        text("71 81 family", "audio_channel_level", e0.family);
        eq("71 81 channel", 1, intDetail(e0, "channel"));
        eq("71 81 value2x", 4, intDetail(e0, "value2x"));

        MdNormalizedEvent e1 = at(result, 1);
        eq("41 81 earlier duplicate wins", 11, intDetail(e1, "descriptorIndex"));
        eq("41 81 handler", 0x000, intDetail(e1, "handlerId"));

        MdNormalizedEvent e2 = at(result, 2);
        eq("71 82 phase wrapper", 0x105, intDetail(e2, "handlerId"));
        text("71 82 pan family", "audio_channel_pan_phase_gated", e2.family);
        bool("71 82 phase1 gate", true, boolDetail(e2, "phase1Ignored"));

        MdNormalizedEvent e3 = at(result, 3);
        eq("71 83 handler", 0x002, intDetail(e3, "handlerId"));
        text("71 83 op0 enters start gate after load", "load_refresh_then_start_gate", textDetail(e3, "layeredOperation"));
        eq("71 83 format", 4, intDetail(e3, "formatCode"));
        eq("71 83 rate", 4000, intDetail(e3, "sampleRate"));

        MdNormalizedEvent e4 = at(result, 4);
        eq("61 83 handler", 0x109, intDetail(e4, "handlerId"));
        text("61 83 family", "audio_8001_inline_slot", e4.family);
        eq("61 83 inline duration count", 3, intDetail(e4, "durationByteCount"));
        eq("61 83 inline coded length", 3, intDetail(e4, "codedPayloadLength"));
        eq("61 83 native duration ms", 2, intDetail(e4, "durationMs"));

        MdNormalizedEvent e5 = at(result, 5);
        eq("71 84 handler", 0x106, intDetail(e5, "handlerId"));
        text("71 84 family", "audio_8001_duration_count_slot", e5.family);
        eq("71 84 BE32 duration count", 16, intDetail(e5, "durationByteCount"));
        eq("71 84 actual coded payload length", 3, intDetail(e5, "codedPayloadLength"));
        eq("71 84 native duration ms", 8, intDetail(e5, "durationMs"));

        MdNormalizedEvent e6 = at(result, 6);
        eq("compact 8002 handler", 0x400, intDetail(e6, "handlerId"));
        eq("compact 8002 rate field", 0x1F40, intDetail(e6, "rateField"));
        eq("compact 8002 coded bits", 4, intDetail(e6, "codedBits"));
        eq("compact 8002 channel count", 2, intDetail(e6, "channelCount"));
        eq("compact 8002 payload length", 1, intDetail(e6, "codedPayloadLength"));
        eq("compact 8002 native duration ms", 0, intDetail(e6, "durationMs"));

        MdNormalizedEvent e7 = at(result, 7);
        eq("31 10 handler", 0x402, intDetail(e7, "handlerId"));
        eq("31 10 dispatch code", 7, intDetail(e7, "dispatchCode"));
        eq("31 10 delegates 8002", 0x400, intDetail(e7, "delegateHandlerId"));
        eq("31 10 delegated slot", 3, intDetail(e7, "slot"));

        MdNormalizedEvent e8 = at(result, 8);
        eq("71 92 handler", 0x006, intDetail(e8, "handlerId"));
        text("71 92 body 40 reaches ft structured control", "ft_command_40_structured_control", textDetail(e8, "layeredFtSynthEffect"));
        text("71 92 body 40 normal inactive", "no_action_for_supplied_normal_synth", textDetail(e8, "layeredNormalSynthEffect"));

        MdNormalizedEvent e9 = at(result, 9);
        eq("71 92 body-forward handler", 0x006, intDetail(e9, "handlerId"));
        text("71 92 body 93 normal path", "subtype1_93_control", textDetail(e9, "layeredNormalSynthEffect"));
        text("71 92 body 93 ft inactive", "no_action_for_supplied_ft_synth", textDetail(e9, "layeredFtSynthEffect"));

        MdNormalizedEvent e10 = at(result, 10);
        eq("71 93 handler", 0x007, intDetail(e10, "handlerId"));
        text("71 93 normal path", "subtype1_93_control", textDetail(e10, "layeredNormalSynthEffect"));
        text("71 93 ft inactive", "no_action_for_supplied_ft_synth", textDetail(e10, "layeredFtSynthEffect"));

        MdNormalizedEvent e11 = at(result, 11);
        eq("11 01 F0 04 handler", 0x406, intDetail(e11, "handlerId"));
        eq("compact forwarded first", 0xF0, intDetail(e11, "forwardedFirstByte"));
        eq("compact forwarded subcommand", 0x04, intDetail(e11, "forwardedF0Subcommand"));
        text("compact normal F0/04", "subtype2_f0_control", textDetail(e11, "layeredNormalSynthEffect"));
        text("compact ft inactive", "no_action_for_supplied_ft_synth", textDetail(e11, "layeredFtSynthEffect"));

        MdNormalizedEvent e12 = at(result, 12);
        eq("71 12 handler", 0x102, intDetail(e12, "handlerId"));
        text("71 12 normal subtype0", "subtype0_12_32_control", textDetail(e12, "layeredNormalSynthEffect"));
        text("71 12 ft command12", "ft_command_12_subdispatch", textDetail(e12, "layeredFtSynthEffect"));

        MdNormalizedEvent e13 = at(result, 13);
        eq("71 10 handler", 0x100, intDetail(e13, "handlerId"));
        text("71 10 normal inactive", "no_action_for_supplied_normal_synth", textDetail(e13, "layeredNormalSynthEffect"));
        text("71 10 ft command10", "ft_command_10_mode0_passthrough", textDetail(e13, "layeredFtSynthEffect"));

        MdNormalizedEvent e14 = at(result, 14);
        eq("31 32 handler", 0x102, intDetail(e14, "handlerId"));
        text("31 32 normal subtype0", "subtype0_12_32_control", textDetail(e14, "layeredNormalSynthEffect"));
        text("31 32 ft inactive", "no_action_for_supplied_ft_synth", textDetail(e14, "layeredFtSynthEffect"));

        MdNormalizedEvent e15 = at(result, 15);
        eq("31 10 code16 handler", 0x402, intDetail(e15, "handlerId"));
        eq("31 10 code16 dispatch", 16, intDetail(e15, "dispatchCode"));
        text("31 10 code16 normal inactive", "no_action_for_supplied_normal_synth", textDetail(e15, "layeredNormalSynthEffect"));
        text("31 10 code16 lane0 ft command10", "ft_command_10_mode0_passthrough", textDetail(e15, "layeredFtSynthEffect"));

        MdNormalizedEvent unknown = result.unknownEvents.get(0);
        eq("71 B0 remains unknown", 0x71, unknown.prefix);
        eq("71 B0 selector", 0xB0, unknown.selector);

        MdNormalizedEvent e17 = at(result, 17);
        eq("route map handler", 0x407, intDetail(e17, "handlerId"));
        eq("route map lane count", 16, intDetail(e17, "laneCount"));

        System.out.println("MachineDependentSemanticsAudit: PASS");
    }

    private static void auditEffectiveDescriptorProfile() {
        descriptor(0, 0x104, 0x61, 0x81);
        descriptor(1, 0x105, 0x61, 0x82);
        descriptor(2, 0x109, 0x61, 0x83);
        descriptor(3, 0x106, 0x61, 0x84);
        descriptor(4, 0x000, 0x21, 0x81);
        descriptor(5, 0x001, 0x21, 0x82);
        descriptor(6, 0x002, 0x21, 0x83);
        descriptor(7, 0x003, 0x21, 0x86);
        descriptor(8, 0x400, 0x11, 0x01, 0xF0, 0x07);
        descriptor(9, 0x401, 0x11, 0x01, 0xF1);
        descriptor(10, 0x001, 0x41, 0x80);
        descriptor(11, 0x000, 0x41, 0x81);
        descriptor(13, 0x105, 0x41, 0x82);
        descriptor(14, 0x002, 0x41, 0x83);
        descriptor(15, 0x106, 0x41, 0x84);
        descriptor(16, 0x003, 0x41, 0x86);
        descriptor(17, 0x103, 0x41, 0x8F);
        descriptor(18, 0x000, 0x71, 0x81);
        descriptor(21, 0x105, 0x71, 0x82);
        descriptor(22, 0x002, 0x71, 0x83);
        descriptor(23, 0x106, 0x71, 0x84);
        descriptor(24, 0x003, 0x71, 0x86);
        descriptor(25, 0x103, 0x71, 0x8F);
        descriptor(26, 0x402, 0x31, 0x10);
        descriptor(27, 0x104, 0x31, 0x81);
        descriptor(28, 0x105, 0x31, 0x82);
        descriptor(29, 0x106, 0x31, 0x84);
        descriptor(30, 0x103, 0x31, 0x8F);
        descriptor(31, 0x100, 0x61, 0x10);
        descriptor(32, 0x101, 0x61, 0x11);
        descriptor(33, 0x102, 0x61, 0x12);
        descriptor(34, 0x004, 0x21, 0x90);
        descriptor(35, 0x005, 0x21, 0x91);
        descriptor(36, 0x006, 0x21, 0x92);
        descriptor(37, 0x007, 0x21, 0x93);
        descriptor(38, 0x403, 0x11, 0x01, 0xF0, 0x05);
        descriptor(39, 0x404, 0x11, 0x01, 0xF0, 0x06);
        descriptor(40, 0x100, 0x41, 0x10);
        descriptor(41, 0x101, 0x41, 0x11);
        descriptor(42, 0x102, 0x41, 0x12);
        descriptor(43, 0x004, 0x41, 0x90);
        descriptor(44, 0x005, 0x41, 0x91);
        descriptor(45, 0x006, 0x41, 0x92);
        descriptor(46, 0x007, 0x41, 0x93);
        descriptor(47, 0x100, 0x71, 0x10);
        descriptor(48, 0x101, 0x71, 0x11);
        descriptor(49, 0x102, 0x71, 0x12);
        descriptor(50, 0x004, 0x71, 0x90);
        descriptor(51, 0x005, 0x71, 0x91);
        descriptor(52, 0x006, 0x71, 0x92);
        descriptor(53, 0x007, 0x71, 0x93);
        descriptor(54, 0x100, 0x31, 0x30);
        descriptor(55, 0x101, 0x31, 0x31);
        descriptor(56, 0x102, 0x31, 0x32);
        descriptor(57, 0x100, 0x01, 0x10);
        descriptor(58, 0x101, 0x01, 0x11);
        descriptor(59, 0x102, 0x01, 0x12);
        descriptor(60, 0x405, 0x11, 0x01, 0xF0, 0x03);
        descriptor(61, 0x406, 0x11, 0x01, 0xF0, 0x04);
        descriptor(62, 0x407, 0x11, 0x01, 0xF2, 0x07);
    }

    private static void descriptor(int expectedIndex, int expectedHandler, int... bytes) {
        MachineDependentEvent source = md(0, bytes);
        List<TrackEvent> events = new ArrayList<TrackEvent>();
        events.add(source);
        DecodedTrack track = new DecodedTrack(
                0, 0, 0, 0, 0, 0, 1, events, Collections.<String>emptyList());
        MdNormalizationResult result = new MdNormalizer().normalize(Collections.singletonList(track));
        if (result.normalizedEvents.size() != 1 || !result.unknownEvents.isEmpty()) {
            fail("descriptor " + expectedIndex, "did not normalize as known");
        }
        MdNormalizedEvent event = result.normalizedEvents.get(0);
        eq("descriptor index " + expectedIndex, expectedIndex, intDetail(event, "descriptorIndex"));
        eq("descriptor handler " + expectedIndex, expectedHandler, intDetail(event, "handlerId"));
    }

    private static MachineDependentEvent md(int eventIndex, int... bytes) {
        byte[] payload = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) payload[i] = (byte) bytes[i];
        return new MachineDependentEvent(0, eventIndex, 0, eventIndex, 0xFF, payload);
    }

    private static MdNormalizedEvent at(MdNormalizationResult result, int eventIndex) {
        for (MdNormalizedEvent event : result.normalizedEvents) {
            if (event.eventIndex == eventIndex) return event;
        }
        fail("event " + eventIndex, "missing normalized event");
        return null;
    }

    private static int intDetail(MdNormalizedEvent event, String key) {
        Object value = event.details.get(key);
        if (!(value instanceof Number)) fail(key, "expected number, got " + value);
        return ((Number) value).intValue();
    }

    private static boolean boolDetail(MdNormalizedEvent event, String key) {
        Object value = event.details.get(key);
        if (!(value instanceof Boolean)) fail(key, "expected boolean, got " + value);
        return ((Boolean) value).booleanValue();
    }

    private static String textDetail(MdNormalizedEvent event, String key) {
        Object value = event.details.get(key);
        if (!(value instanceof String)) fail(key, "expected string, got " + value);
        return (String) value;
    }

    private static void eq(String label, int expected, int actual) {
        if (expected != actual) fail(label, "expected " + expected + ", got " + actual);
    }

    private static void bool(String label, boolean expected, boolean actual) {
        if (expected != actual) fail(label, "expected " + expected + ", got " + actual);
    }

    private static void text(String label, String expected, String actual) {
        if (!expected.equals(actual)) fail(label, "expected " + expected + ", got " + actual);
    }

    private static void fail(String label, String message) {
        throw new IllegalStateException(label + ": " + message);
    }
}
