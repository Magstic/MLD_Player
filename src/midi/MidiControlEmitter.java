package midi;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sound.midi.ShortMessage;

/**
 * Emits and de-duplicates concrete host MIDI controls.
 */
final class MidiControlEmitter {
    private static final int MIDI_CHANNEL_COUNT = 16;
    private final List<MidiPlan.MappedControlEvent> out;
    private final Map<Integer, Integer> lastControls = new LinkedHashMap<Integer, Integer>();
    private final Map<Integer, Integer> lastBends = new LinkedHashMap<Integer, Integer>();
    private int sourceOrder = -1;
    private int nextOrder;

    MidiControlEmitter(List<MidiPlan.MappedControlEvent> o) {
        out = o;
    }

    void setSourceOrder(int o) {
        sourceOrder = o;
    }

    void emitPatch(int st, int sc, String sn, int raw, int ch, long tick, MidiPatchMapper.HostPatch p) {
        emit(
                st,
                sc,
                sn,
                raw,
                ch,
                tick,
                ShortMessage.PROGRAM_CHANGE,
                p.program,
                0,
                p.patchWord,
                p.rawPatchWord,
                p.latePatchEntry,
                p.source,
                p.nativeMode,
                p.nativeBank,
                p.nativeProgram,
                p.nativeKind,
                p.nativeSub,
                p.nativeValue,
                "program_change",
                true);
    }

    void emitVolume(int a, int b, String c, int raw, int ch, long t, int v) {
        emitDedup(a, b, c, raw, ch, t, 7, clamp(v), "cc7_volume", true);
    }

    void emitPan(int a, int b, String c, int raw, int ch, long t, int v) {
        emitDedup(a, b, c, raw, ch, t, 10, clamp(v), "cc10_pan", true);
    }

    void emitModulation(int a, int b, String c, int raw, int ch, long t, int v) {
        emitDedup(a, b, c, raw, ch, t, 1, clamp(v), "cc1_modulation", true);
    }

    void emitPitchRange(int a, int b, String c, int raw, int ch, long t, int r) {
        emit(a, b, c, raw, ch, t, ShortMessage.CONTROL_CHANGE, 101, 0, "rpn_pitch_range", true);
        emit(a, b, c, raw, ch, t, ShortMessage.CONTROL_CHANGE, 100, 0, "rpn_pitch_range", true);
        emit(a, b, c, raw, ch, t, ShortMessage.CONTROL_CHANGE, 6, clamp(r), "rpn_pitch_range", true);
        emit(a, b, c, raw, ch, t, ShortMessage.CONTROL_CHANGE, 38, 0, "rpn_pitch_range", true);
    }

    void emitPitchBend(int a, int b, String c, int raw, int ch, long t, int v) {
        int x = Math.max(0, Math.min(16383, v));
        Integer k = ch;
        if (same(lastBends.get(k), x)) return;
        lastBends.put(k, x);
        emit(a, b, c, raw, ch, t, ShortMessage.PITCH_BEND, x & 127, (x >> 7) & 127, "pitch_bend", true);
    }

    void emitMasterVolume(int a, int b, String c, int raw, long t, int v) {
        for (int ch = 0; ch < 16; ch++) emitDedup(a, b, c, raw, ch, t, 7, clamp(v), "cc7_volume", true);
    }

    void emitMasterPan(int a, int b, String c, int raw, long t, int v) {
        for (int ch = 0; ch < 16; ch++) emitDedup(a, b, c, raw, ch, t, 10, clamp(v), "cc10_pan", true);
    }

    void emitAllSoundOff(int a, int b, String c, int raw, long t) {
        for (int ch = 0; ch < 16; ch++) emit(a, b, c, raw, ch, t, ShortMessage.CONTROL_CHANGE, 120, 0, "all_sound_off", false);
    }

    void resetCaches() {
        lastControls.clear();
        lastBends.clear();
    }

    private void emitDedup(int a, int b, String c, int raw, int ch, long t, int ctrl, int v, String map, boolean proxy) {
        Integer k = (ch << 8) | (ctrl & 127);
        if (same(lastControls.get(k), v)) return;
        lastControls.put(k, v);
        emit(a, b, c, raw, ch, t, ShortMessage.CONTROL_CHANGE, ctrl, v, map, proxy);
    }

    private void emit(int a, int b, String c, int raw, int ch, long t, int status, int d1, int d2, String map, boolean proxy) {
        emit(a, b, c, raw, ch, t, status, d1, d2, -1, -1, -1, null, -1, -1, -1, -1, -1, -1, map, proxy);
    }

    private void emit(
            int sourceTrack,
            int sourceCommand,
            String sourceName,
            int rawTick,
            int midiChannel,
            long midiTick,
            int status,
            int data1,
            int data2,
            int patchWord,
            int rawPatchWord,
            int latePatchEntry,
            String patchSource,
            int nativeMode,
            int nativeBank,
            int nativeProgram,
            int nativeKind,
            int nativeSub,
            int nativeValue,
            String hostMapping,
            boolean hostMappingProxy) {
        out.add(new MidiPlan.MappedControlEvent(
                sourceTrack,
                sourceCommand,
                sourceName,
                rawTick,
                midiChannel,
                midiChannel,
                midiChannel + 1,
                midiTick,
                status,
                data1,
                data2,
                patchWord,
                rawPatchWord,
                latePatchEntry,
                patchSource,
                nativeMode,
                nativeBank,
                nativeProgram,
                nativeKind,
                nativeSub,
                nativeValue,
                hostMapping,
                hostMappingProxy,
                sourceOrder,
                nextOrder++));
    }

    private static boolean same(Integer a, int b) {
        return a != null && a == b;
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(127, v));
    }
}
