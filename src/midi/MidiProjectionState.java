package midi;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import mld.semantic.MelodyProgram;
import mld.semantic.NativeProgram;

/**
 * Stateful host projection of native melody actions.
 */
final class MidiProjectionState {
    private static final int DEFAULT_LEVEL = 63;
    private static final int DEFAULT_PAN = 32;
    private static final int DEFAULT_PITCH_COARSE = 32;
    private static final int DEFAULT_PITCH_FINE = 32;
    private static final int DEFAULT_PITCH_RANGE = 2;
    private static final int DEFAULT_MODULATION = 0;
    private MidiTimingMapper timing;
    private final List<String> warnings;
    private final List<MidiPlan.CompiledNote> notes = new ArrayList<MidiPlan.CompiledNote>();
    private final List<MidiPlan.MappedControlEvent> controls = new ArrayList<MidiPlan.MappedControlEvent>();
    private final MidiLaneMapper.LaneTracker laneTracker = new MidiLaneMapper.LaneTracker();
    private final boolean[] pitchRangeDirty = new boolean[64];
    private final MidiControlEmitter emitter = new MidiControlEmitter(controls);

    MidiProjectionState(MidiTimingMapper t, List<String> w) {
        timing = t;
        warnings = w;
        emitInitialMidiDefaults(0, 0L);
    }

    void setTiming(MidiTimingMapper timing) {
        if (timing == null) throw new IllegalArgumentException("MIDI timing mapper is required.");
        this.timing = timing;
    }

    void discardProjectedOutput() {
        notes.clear();
        controls.clear();
    }

    List<MidiPlan.MappedControlEvent> drainProjectedControls() {
        List<MidiPlan.MappedControlEvent> result =
                new ArrayList<MidiPlan.MappedControlEvent>(controls);
        notes.clear();
        controls.clear();
        return result;
    }

    Result project(NativeProgram p) {
        List<ActionRef> a = new ArrayList<ActionRef>(p.melody.noteActions.size() + p.melody.controls.size());
        for (MelodyProgram.NoteAction n : p.melody.noteActions) {
            if (!n.noteOn) continue;
            a.add(ActionRef.note(n));
            // Classify percussion channels for the whole song before emitting patches.
            laneTracker.observeNote(n.logicalChannel, n.channel.percussion);
        }
        for (MelodyProgram.NativeControl c : p.melody.controls) a.add(ActionRef.control(c));
        Collections.sort(a, ACTION_ORDER);
        long total = timing.rawToMidiTick(p.linearEndRawTick);
        for (MelodyProgram.GateSchedule g : p.melody.gateSchedules) {
            if (!isHostChannel(g.logicalChannel)) {
                warnHostChannel(g.logicalChannel, "note");
                continue;
            }
            long end = timing.rawToMidiTick(g.rawEndTick);
            total = Math.max(total, end);
        }
        for (MelodyProgram.NativeNote n : p.melody.notes) total = Math.max(total, processNote(n));
        for (ActionRef x : a) {
            if (x.note != null) {
                MelodyProgram.NoteAction n = x.note;
                emitter.setSourceOrder(n.order);
                emitPatch(n.channel, n.logicalChannel, n.sourceTrack, -1,
                        "note_patch_sync", n.rawTick, timing.rawToMidiTick(n.rawTick));
            } else {
                emitter.setSourceOrder(x.control.order);
                processControl(x.control);
            }
        }
        return new Result(notes, controls, laneTracker, total);
    }

    private long processNote(MelodyProgram.NativeNote n) {
        int l = n.logicalChannel;
        if (!isHostChannel(l)) {
            warnHostChannel(l, "note");
            return -1;
        }
        boolean percussion = n.channel.percussion;
        int base = percussion ? 35 : 45;
        int midiNote = clamp(0, 127, base + n.pitchOffset);
        long start = timing.rawToMidiTick(n.rawStartTick);
        long end = timing.rawToMidiTick(n.rawEndTick);
        notes.add(new MidiPlan.CompiledNote(n.sourceTrack, n.sourceVoice, l, l, l + 1, midiNote, n.velocity, n.rawStartTick, n.rawEndTick, start, end, n.order, n.endOrder));
        return end;
    }

    private void processControl(MelodyProgram.NativeControl c) {
        long t = timing.rawToMidiTick(c.rawTick);
        switch (c.sourceCommand) {
        case 0xB0:
        case 0xBD:
            emitter.emitMasterVolume(c.sourceTrack, c.sourceCommand, c.sourceName, c.rawTick, t, c.masterVolume);
            return;

        case 0xB1:
            emitter.emitMasterPan(c.sourceTrack, c.sourceCommand, c.sourceName, c.rawTick, t, c.value);
            return;

        case 0xBE:
            emitter.emitAllSoundOff(c.sourceTrack, c.sourceCommand, c.sourceName, c.rawTick, t);
            return;

        case 0xBF:
            emitter.emitAllSoundOff(c.sourceTrack, c.sourceCommand, c.sourceName, c.rawTick, t);
            Arrays.fill(pitchRangeDirty, false);
            emitter.resetCaches();
            emitInitialMidiDefaults(c.rawTick, t);
            return;

        case 0xBA:
        case 0xE0:
        case 0xE1:
            patch(c, t);
            return;

        case 0xE2:
        case 0xE6:
            volume(c, t);
            return;

        case 0xE3:
            pan(c, t);
            return;

        case 0xE4:
        case 0xE8:
            pitchApply(c, t);
            return;

        case 0xE7:
            pitchRangeCache(c);
            return;

        case 0xE9:
            prepare(c);
            return;

        case 0xEA:
            modulation(c, t);
            return;

        default:
            return;

        }
    }

    private void patch(MelodyProgram.NativeControl c, long t) {
        int l = prepare(c);
        if (l < 0 || (c.sourceCommand == 0xBA && c.channel.mode != 1)) return;
        emitPatch(c.channel, l, c.sourceTrack, c.sourceCommand, c.sourceName, c.rawTick, t);
    }

    private void volume(MelodyProgram.NativeControl c, long t) {
        int l = prepare(c);
        if (l < 0 || !isHostChannel(l)) return;
        emitter.emitVolume(c.sourceTrack, c.sourceCommand, c.sourceName, c.rawTick, l, t, computeMidiVolume(c.channel));
    }

    private void pan(MelodyProgram.NativeControl c, long t) {
        int l = prepare(c);
        if (l < 0 || !isHostChannel(l)) return;
        emitter.emitPan(c.sourceTrack, c.sourceCommand, c.sourceName, c.rawTick, l, t, computeMidiPan(c.channel));
    }

    private void pitchApply(MelodyProgram.NativeControl c, long t) {
        int l = prepare(c);
        if (l < 0 || !isHostChannel(l)) return;
        if (pitchRangeDirty[l]) {
            emitter.emitPitchRange(c.sourceTrack, c.sourceCommand, c.sourceName, c.rawTick, l, t, c.channel.pitchRange);
            pitchRangeDirty[l] = false;
        }
        emitter.emitPitchBend(c.sourceTrack, c.sourceCommand, c.sourceName, c.rawTick, l, t, computePitchBend(c.channel));
    }

    private void pitchRangeCache(MelodyProgram.NativeControl c) {
        int l = prepare(c);
        if (l >= 0) pitchRangeDirty[l] = true;
    }

    private void modulation(MelodyProgram.NativeControl c, long t) {
        int l = prepare(c);
        if (l >= 0 && isHostChannel(l)) emitter.emitModulation(c.sourceTrack, c.sourceCommand, c.sourceName, c.rawTick, l, t, c.channel.modulation * 2);
    }

    private int prepare(MelodyProgram.NativeControl c) {
        int l = c.logicalChannel;
        if (!isProjectionChannel(l) || c.channel == null) return -1;
        laneTracker.observeActive(l);
        if (!isHostChannel(l)) warnHostChannel(l, "control " + c.sourceName);
        return l;
    }

    private void emitPatch(MelodyProgram.ChannelSnapshot channel,
            int l, int st, int sc, String sn, int raw, long t) {
        if (!isHostChannel(l) || channel == null) return;
        if (channel.mode != 0 && channel.mode != 1) return;
        emitter.emitPatch(st, sc, sn, raw, l, t,
                MidiPatchMapper.translate(channel, laneTracker.isAuthoritativeSpecial(l)));
    }

    private void emitInitialMidiDefaults(int raw, long t) {
        MelodyProgram.ChannelSnapshot d = defaultSnapshot();
        for (int ch = 0; ch < 16; ch++) {
            emitter.emitVolume(-1, -1, "default_level", raw, ch, t, computeMidiVolume(d));
            emitter.emitPan(-1, -1, "default_pan", raw, ch, t, computeMidiPan(d));
            emitter.emitPitchRange(-1, -1, "default_pitch_range", raw, ch, t, d.pitchRange);
            emitter.emitPitchBend(-1, -1, "default_pitch", raw, ch, t, computePitchBend(d));
            emitter.emitModulation(-1, -1, "default_modulation", raw, ch, t, d.modulation * 2);
        }
    }

    private void warnHostChannel(int l, String context) {
        String w = "Logical channel " + l
                + " is outside the host MIDI bridge\'s 16-channel surface"
                + (context == null || context.isEmpty() ? "" : " for " + context)
                + ".";
        if (!warnings.contains(w)) warnings.add(w);
    }

    private static int computeMidiVolume(MelodyProgram.ChannelSnapshot c) {
        return clamp(0, 127, c.level * 2);
    }

    private static int computeMidiPan(MelodyProgram.ChannelSnapshot c) {
        return clamp(0, 127, c.pan * 2);
    }

    private static int computePitchBend(MelodyProgram.ChannelSnapshot c) {
        return clamp(0, 16383, (8 * (c.pitchFine + 32 * c.pitchCoarse)) - 256);
    }

    private static boolean isProjectionChannel(int c) {
        return c >= 0 && c < 64;
    }

    private static boolean isHostChannel(int c) {
        return c >= 0 && c < 16;
    }

    private static int clamp(int a, int b, int v) {
        return Math.max(a, Math.min(b, v));
    }

    private static MelodyProgram.ChannelSnapshot defaultSnapshot() {
        return new MelodyProgram.ChannelSnapshot(
                0,
                false,
                0,
                0,
                DEFAULT_LEVEL,
                DEFAULT_PAN,
                DEFAULT_PITCH_COARSE,
                DEFAULT_PITCH_FINE,
                DEFAULT_PITCH_RANGE,
                DEFAULT_MODULATION,
                false,
                125,
                0,
                0);
    }

    static final class Result {
        final List<MidiPlan.CompiledNote> notes;
        final List<MidiPlan.MappedControlEvent> controls;
        final MidiLaneMapper.LaneTracker laneTracker;
        final long totalMidiTicks;

        Result(List<MidiPlan.CompiledNote> a, List<MidiPlan.MappedControlEvent> b, MidiLaneMapper.LaneTracker c, long d) {
            notes = a;
            controls = b;
            laneTracker = c;
            totalMidiTicks = d;
        }
    }

    private static final class ActionRef {
        final int order;
        final MelodyProgram.NoteAction note;
        final MelodyProgram.NativeControl control;

        private ActionRef(int o, MelodyProgram.NoteAction n, MelodyProgram.NativeControl c) {
            order = o;
            note = n;
            control = c;
        }

        static ActionRef note(MelodyProgram.NoteAction n) {
            return new ActionRef(n.order, n, null);
        }

        static ActionRef control(MelodyProgram.NativeControl c) {
            return new ActionRef(c.order, null, c);
        }
    }
    private static final Comparator<ActionRef> ACTION_ORDER = new Comparator<ActionRef>(){

        public int compare(ActionRef a, ActionRef b) {
            return Integer.compare(a.order, b.order);
        }
    };
}
