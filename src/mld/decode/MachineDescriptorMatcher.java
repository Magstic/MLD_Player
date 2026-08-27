package mld.decode;

/**
 * First-match descriptor profile reconstructed from the MFi5 machine-dependent dispatcher.
 *
 * This class only performs structural descriptor matching. Handler semantics belong to the
 * semantic/analysis consumers so decoding does not acquire runtime side effects.
 */
public final class MachineDescriptorMatcher {
    private static final Rule[] RULES = new Rule[] {
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

    public Match match(MachineDependentEvent event) {
        if (event == null) {
            return null;
        }
        return match(event.copyPayload());
    }

    public Match match(byte[] payload) {
        if (payload == null) {
            return null;
        }
        for (Rule rule : RULES) {
            int offset = 0;
            if (!matches(payload, offset, rule.pattern1)) {
                continue;
            }
            offset += rule.pattern1.length;
            if (!matches(payload, offset, rule.pattern2)) {
                continue;
            }
            offset += rule.pattern2.length;
            return new Match(rule.index, rule.handlerId, offset);
        }
        return null;
    }

    private static boolean matches(byte[] payload, int offset, byte[] pattern) {
        if (offset < 0 || offset + pattern.length > payload.length) {
            return false;
        }
        for (int i = 0; i < pattern.length; i++) {
            if (payload[offset + i] != pattern[i]) {
                return false;
            }
        }
        return true;
    }

    private static Rule rule(int index, int handlerId, byte[] pattern1, byte[] pattern2) {
        return new Rule(index, handlerId, pattern1, pattern2);
    }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = (byte) values[i];
        }
        return result;
    }

    public static final class Match {
        public final int descriptorIndex;
        public final int handlerId;
        public final int bodyOffset;

        Match(int descriptorIndex, int handlerId, int bodyOffset) {
            this.descriptorIndex = descriptorIndex;
            this.handlerId = handlerId;
            this.bodyOffset = bodyOffset;
        }
    }

    private static final class Rule {
        final int index;
        final int handlerId;
        final byte[] pattern1;
        final byte[] pattern2;

        Rule(int index, int handlerId, byte[] pattern1, byte[] pattern2) {
            this.index = index;
            this.handlerId = handlerId;
            this.pattern1 = pattern1;
            this.pattern2 = pattern2;
        }
    }
}
