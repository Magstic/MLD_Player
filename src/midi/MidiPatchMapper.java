package midi;

import mld.semantic.MelodyProgram;

/**
 * PSM-derived host patch proxy kept separate from authoritative native patch tuples.
 */
final class MidiPatchMapper {
    static final int LATE_ENTRY_EMPTY = 0;
    private static final int LATE_ENTRY_MAX = 0x80;
    private static final int LATE_ENTRY_SIDEBAND_SENTINEL = 0x81;
    private static final boolean USE_INSTRUMENT_SET = true;
    private static final boolean LATE_TABLE_FALLBACK = false;
    private static final boolean ENABLE_LATE_PATCH_REMAP = false;
    private static final boolean USE_OBSERVED_ORDINARY_SURFACE = true;
    private static final String SOURCE_DEFAULT_ZERO = "default_zero";
    private static final String SOURCE_INSTRUMENT_OVERRIDE = "instrument_override";
    private static final String SOURCE_PRESET_TABLE = "preset_table";
    private static final String SOURCE_OBSERVED_ORDINARY = "observed_ordinary_first";
    private static final String SOURCE_OBSERVED_MISMATCH = "observed_ordinary_first_with_mismatch";
    private static final String SOURCE_HOST_PROXY_PATCH12 = "host_proxy_patch12_from_bank_program";

    private MidiPatchMapper() {
    }

    static void observeProgram(ChannelState s, MelodyProgram.ChannelSnapshot c) {
        s.copyNative(c);
        s.hasProgramEvent = true;
        updateRawPatchWord(s);
        observePatchSnapshot(s);
        s.patchDirty = true;
    }

    static void observeBank(ChannelState s, MelodyProgram.ChannelSnapshot c) {
        s.copyNative(c);
        s.hasBankEvent = true;
        updateRawPatchWord(s);
        observePatchSnapshot(s);
        if (s.mode == 1) s.patchDirty = true;
    }

    static void observeMode(ChannelState s, MelodyProgram.ChannelSnapshot c) {
        s.copyNative(c);
        observePatchSnapshot(s);
        if (s.mode == 1) s.patchDirty = true;
    }

    static HostPatch translate(ChannelState s, MelodyProgram.ChannelSnapshot c) {
        s.copyNative(c);
        PatchSelection sel = resolveHostPatchSelection(s);
        int pw = sel.patchWord;
        int program = pw & 127;
        boolean suppressed = s.mode != 0 && s.mode != 1;
        return new HostPatch(
                suppressed,
                suppressed ? 0 : program,
                suppressed ? 0 : pw,
                s.rawPatchWord,
                suppressed ? 0 : sel.lateEntry,
                suppressed ? SOURCE_DEFAULT_ZERO : sel.source,
                c.mode,
                c.bank,
                c.program,
                c.nativeKind,
                c.nativeSub,
                c.nativeValue);
    }

    private static PatchSelection resolveHostPatchSelection(ChannelState s) {
        PatchSelection a = resolveAuthoritativeOrdinaryPatchSelection(s);
        if (a != null) return a;
        PatchSelection sel = resolveVerifiedLatePatchSelection(s);
        if (!sel.authoritative && USE_OBSERVED_ORDINARY_SURFACE) {
            PatchSelection o = resolveObservedOrdinaryPatchSelection(s);
            if (o != null) sel = o;
        }
        int pw = ENABLE_LATE_PATCH_REMAP ? applyLatePatchRemap(sel.patchWord, 1) : clamp(0, 127, sel.patchWord);
        return sel.withPatchWord(pw);
    }

    private static PatchSelection resolveAuthoritativeOrdinaryPatchSelection(ChannelState s) {
        if (!s.isOrdinaryPatchMode() || !s.hasProgramEvent) return null;
        int pw = s.rawPatchWord & 0x0FFF;
        return new PatchSelection(ordinaryLatePatchEntry(pw & 127), pw, SOURCE_HOST_PROXY_PATCH12, true);
    }

    private static PatchSelection resolveVerifiedLatePatchSelection(ChannelState s) {
        if (USE_INSTRUMENT_SET && isPlayableLatePatchEntry(s.latePatchOverrideEntry)) {
            return PatchSelection.fromLateEntry(
                    s.latePatchOverrideEntry, SOURCE_INSTRUMENT_OVERRIDE, true);
        }
        if (LATE_TABLE_FALLBACK && isPlayableLatePatchEntry(s.latePatchTableEntry)) {
            return PatchSelection.fromLateEntry(
                    s.latePatchTableEntry, SOURCE_PRESET_TABLE, true);
        }
        return PatchSelection.fromLateEntry(0, SOURCE_DEFAULT_ZERO, false);
    }

    private static PatchSelection resolveObservedOrdinaryPatchSelection(ChannelState s) {
        if (!isPlayableLatePatchEntry(s.latePatchTableEntry)) return null;
        return PatchSelection.fromLateEntry(s.latePatchTableEntry, s.latePatchTableMismatch ? SOURCE_OBSERVED_MISMATCH : SOURCE_OBSERVED_ORDINARY, false);
    }

    private static void updateRawPatchWord(ChannelState s) {
        s.rawPatchWord = composeOrdinaryPatchWord(s.bank, s.program);
    }

    private static void observePatchSnapshot(ChannelState s) {
        if (!s.isOrdinaryPatchMode() || !s.hasProgramEvent) return;
        if (hasSpecialSidebandFamily(s.rawPatchWord)) {
            s.latePatchTableEntry = LATE_ENTRY_SIDEBAND_SENTINEL;
            return;
        }
        int late = ordinaryLatePatchEntry(s.program);
        if (s.latePatchTableEntry == 0) s.latePatchTableEntry = late; else if (s.latePatchTableEntry != late) s.latePatchTableMismatch = true;
    }

    private static int ordinaryLatePatchEntry(int p) {
        return clamp(0, 127, p) + 1;
    }

    private static int composeOrdinaryPatchWord(int bank, int program) {
        int low = program & 63;
        int high = bank & 63;
        if ((high & 0x3E) == 0) {
            switch (low) {
            case 0:
                return 0;

            case 1:
                return 9;

            case 2:
                return 16;

            case 3:
                return 24;

            case 4:
                return 13;

            case 5:
                return 74;

            default:

            }
        }
        return low | (high << 6);
    }

    private static boolean hasSpecialSidebandFamily(int x) {
        return (x & 0x1F00) == 0x1B00;
    }

    private static boolean isPlayableLatePatchEntry(int x) {
        return x >= 1 && x <= LATE_ENTRY_MAX;
    }

    private static int patchWordFromLateEntry(int x) {
        return isPlayableLatePatchEntry(x) ? x - 1 : 0;
    }

    private static int applyLatePatchRemap(int v, int mode) {
        int c = v > 0x17 ? 0 : clamp(0, 0x17, v);
        int g = c >> 3;
        if (mode != 0) switch (g) {
        case 0:
            return 0;

        case 1:
            return 9;

        case 2:
            return 16;

        case 3:
            return 24;

        case 4:
            return 13;

        case 5:
            return 74;

        default:

        }
        return 0x100 | g;
    }

    private static int clamp(int a, int b, int v) {
        return Math.max(a, Math.min(b, v));
    }

    static final class ChannelState {
        int mode;
        int bank;
        int program;
        int rawPatchWord;
        boolean hasProgramEvent;
        boolean hasBankEvent;
        int latePatchTableEntry;
        int latePatchOverrideEntry;
        boolean latePatchTableMismatch;
        boolean patchDirty = true;
        HostPatch lastPatch;

        void copyNative(MelodyProgram.ChannelSnapshot c) {
            if (c != null) {
                mode = c.mode;
                bank = c.bank;
                program = c.program;
            }
        }

        boolean isOrdinaryPatchMode() {
            return mode == 0 || mode == 1;
        }
    }

    static final class HostPatch {
        final boolean suppressed;
        final int program;
        final int patchWord;
        final int rawPatchWord;
        final int latePatchEntry;
        final String source;
        final int nativeMode;
        final int nativeBank;
        final int nativeProgram;
        final int nativeKind;
        final int nativeSub;
        final int nativeValue;

        HostPatch(boolean a, int b, int c, int d, int e, String f, int g, int h, int i, int j, int k, int l) {
            suppressed = a;
            program = clamp(0, 127, b);
            patchWord = c & 0xFFFF;
            rawPatchWord = d & 0xFFFF;
            latePatchEntry = e & 0xFFFF;
            source = f;
            nativeMode = g;
            nativeBank = h;
            nativeProgram = i;
            nativeKind = j;
            nativeSub = k;
            nativeValue = l;
        }

        boolean sameAs(HostPatch o) {
            return o != null
                    && suppressed == o.suppressed
                    && program == o.program
                    && patchWord == o.patchWord
                    && nativeMode == o.nativeMode
                    && nativeBank == o.nativeBank
                    && nativeProgram == o.nativeProgram
                    && nativeKind == o.nativeKind
                    && nativeSub == o.nativeSub
                    && nativeValue == o.nativeValue;
        }
    }

    private static final class PatchSelection {
        final int lateEntry;
        final int patchWord;
        final String source;
        final boolean authoritative;

        PatchSelection(int a, int b, String c, boolean d) {
            lateEntry = a & 0xFFFF;
            patchWord = b & 0xFFFF;
            source = c;
            authoritative = d;
        }

        static PatchSelection fromLateEntry(int e, String s, boolean a) {
            return new PatchSelection(e, patchWordFromLateEntry(e), s, a);
        }

        PatchSelection withPatchWord(int p) {
            return new PatchSelection(lateEntry, p, source, authoritative);
        }
    }
}
