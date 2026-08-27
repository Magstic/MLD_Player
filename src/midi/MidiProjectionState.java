package midi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import mld.semantic.MelodyProgram;
import mld.semantic.NativeProgram;

/**
 * Stateful host projection of native melody actions.
 */
final class MidiProjectionState {
    private static final int MIDI_CHANNEL_COUNT = 16;
    private static final int MAX_LOGICAL_CHANNELS = 64;
    private static final int DEFAULT_LEVEL = 63;
    private static final int DEFAULT_PAN = 32;
    private static final int DEFAULT_PITCH_COARSE = 32;
    private static final int DEFAULT_PITCH_FINE = 32;
    private static final int DEFAULT_PITCH_RANGE = 2;
    private static final int DEFAULT_MODULATION = 0;
    private static final int PSM_GLOBAL_LEVEL_SCALE = 100;
    private static final int PSM_CHANNEL_LEVEL_SCALE = 100;
    private static final boolean PSM_FORCE_PAN_LEFT_SYNC = false;
    private static final boolean PSM_EMIT_SYNTHETIC_PATCH_SYNC_CONTROLS = false;
    private static final String PATCH_SYNC_VOLUME_SOURCE = "patch_sync_volume";
    private static final String PATCH_SYNC_PAN_SOURCE = "patch_sync_pan_zero";
    private final MidiTimingMapper timing;
    private final List<String> warnings;
    private final List<MidiPlan.CompiledNote> notes = new ArrayList<MidiPlan.CompiledNote>();
    private final List<MidiPlan.MappedControlEvent> controls = new ArrayList<MidiPlan.MappedControlEvent>();
    private final MidiLaneMapper.LaneTracker laneTracker = MidiLaneMapper.newDefaultLaneTracker();
    private ProjectionChannel[] channels = createChannels();
    private final MidiControlEmitter emitter = new MidiControlEmitter(controls);

    MidiProjectionState(MidiTimingMapper t, List<String> w) {
        timing = t;
        warnings = w;
        emitInitialMidiDefaults(0);
    }

    Result project(NativeProgram p) {
        List<ActionRef> a = new ArrayList<ActionRef>(p.melody.notes.size() + p.melody.controls.size());
        for (MelodyProgram.NativeNote n : p.melody.notes) a.add(ActionRef.note(n));
        for (MelodyProgram.NativeControl c : p.melody.controls) a.add(ActionRef.control(c));
        Collections.sort(a, ACTION_ORDER);
        long total = timing.rawToMidiTick(p.linearEndRawTick);
        for (MelodyProgram.GateSchedule g : p.melody.gateSchedules) {
            if (!isHostChannel(g.logicalChannel)) {
                warnHostChannel(g.logicalChannel, "note");
                continue;
            }
            long start = timing.rawToMidiTick(g.rawStartTick);
            long end = timing.rawToMidiTick(g.rawEndTick);
            if (g.sounding) end = normalizeMidiEnd(start, end);
            total = Math.max(total, end);
        }
        for (ActionRef x : a) {
            if (x.note != null) {
                emitter.setSourceOrder(x.note.order);
                total = Math.max(total, processNote(x.note));
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
        laneTracker.observeActive(l);
        ProjectionChannel c = channels[l];
        c.copyNative(n.channel);
        emitPatchIfNeeded(c, l, n.sourceTrack, -1, "note_patch_sync", n.rawStartTick, timing.rawToMidiTick(n.rawStartTick));
        int base = laneTracker.isAuthoritativeSpecial(l) ? 35 : baseForMode(n.channel.mode);
        int midiNote = clamp(0, 127, base + n.pitchOffset);
        long start = timing.rawToMidiTick(n.rawStartTick);
        long end = normalizeMidiEnd(start, timing.rawToMidiTick(n.rawEndTick));
        notes.add(new MidiPlan.CompiledNote(n.sourceTrack, n.sourceVoice, l, l, l + 1, midiNote, n.velocity, n.rawStartTick, n.rawEndTick, start, end));
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
            resetChannels();
            emitter.resetCaches();
            emitInitialMidiDefaults(t);
            return;

        case 0xBA:
            mode(c, t);
            return;

        case 0xE0:
            program(c, t);
            return;

        case 0xE1:
            bank(c, t);
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

    private void mode(MelodyProgram.NativeControl c, long t) {
        int l = c.logicalChannel;
        if (!isProjectionChannel(l) || c.channel == null) return;
        ProjectionChannel ch = channels[l];
        ch.copyNative(c.channel);
        MidiPatchMapper.observeMode(ch.patch, c.channel);
        laneTracker.observeActive(l);
        if (c.channel.mode == 1) emitPatchIfNeeded(ch, l, c.sourceTrack, c.sourceCommand, c.sourceName, c.rawTick, t);
    }

    private void program(MelodyProgram.NativeControl c, long t) {
        int l = prepare(c);
        if (l < 0) return;
        ProjectionChannel ch = channels[l];
        MidiPatchMapper.observeProgram(ch.patch, c.channel);
        emitPatchIfNeeded(ch, l, c.sourceTrack, c.sourceCommand, c.sourceName, c.rawTick, t);
    }

    private void bank(MelodyProgram.NativeControl c, long t) {
        int l = prepare(c);
        if (l < 0) return;
        ProjectionChannel ch = channels[l];
        MidiPatchMapper.observeBank(ch.patch, c.channel);
        if (c.channel.mode != 1) return;
        if (!ch.patch.hasProgramEvent && ch.patch.latePatchOverrideEntry == 0) return;
        emitPatchIfNeeded(ch, l, c.sourceTrack, c.sourceCommand, c.sourceName, c.rawTick, t);
    }

    private void volume(MelodyProgram.NativeControl c, long t) {
        int l = prepare(c);
        if (l < 0 || !isHostChannel(l)) return;
        emitter.emitVolume(c.sourceTrack, c.sourceCommand, c.sourceName, c.rawTick, l, t, computePsmVolumeSync(c.channel, l));
    }

    private void pan(MelodyProgram.NativeControl c, long t) {
        int l = prepare(c);
        if (l < 0 || !isHostChannel(l)) return;
        emitter.emitPan(c.sourceTrack, c.sourceCommand, c.sourceName, c.rawTick, l, t, computePsmPanSync(c.channel));
    }

    private void pitchApply(MelodyProgram.NativeControl c, long t) {
        int l = prepare(c);
        if (l < 0 || !isHostChannel(l)) return;
        ProjectionChannel ch = channels[l];
        if (ch.pitchRangeDirty) {
            emitter.emitPitchRange(c.sourceTrack, c.sourceCommand, c.sourceName, c.rawTick, l, t, ch.nativeChannel.pitchRange);
            ch.pitchRangeDirty = false;
        }
        emitter.emitPitchBend(c.sourceTrack, c.sourceCommand, c.sourceName, c.rawTick, l, t, computePitchBend(c.channel));
    }

    private void pitchRangeCache(MelodyProgram.NativeControl c) {
        int l = prepare(c);
        if (l >= 0) channels[l].pitchRangeDirty = true;
    }

    private void modulation(MelodyProgram.NativeControl c, long t) {
        int l = prepare(c);
        if (l >= 0 && isHostChannel(l)) emitter.emitModulation(c.sourceTrack, c.sourceCommand, c.sourceName, c.rawTick, l, t, c.channel.modulation * 2);
    }

    private int prepare(MelodyProgram.NativeControl c) {
        int l = c.logicalChannel;
        if (!isProjectionChannel(l) || c.channel == null) return -1;
        channels[l].copyNative(c.channel);
        laneTracker.observeActive(l);
        if (!isHostChannel(l)) warnHostChannel(l, "control " + c.sourceName);
        return l;
    }

    private void emitPatchIfNeeded(ProjectionChannel ch, int l, int st, int sc, String sn, int raw, long t) {
        if (!isHostChannel(l) || ch.nativeChannel == null) return;
        MidiPatchMapper.HostPatch p = MidiPatchMapper.translate(ch.patch, ch.nativeChannel);
        if (p.suppressed) return;
        if (!ch.patch.patchDirty && ch.patch.lastPatch != null) return;
        if (ch.patch.lastPatch != null && ch.patch.lastPatch.sameAs(p)) {
            ch.patch.patchDirty = false;
            return;
        }
        emitter.emitPatch(st, sc, sn, raw, l, t, p);
        emitSyntheticPatchSyncControls(ch.nativeChannel, l, st, sc, raw, t);
        ch.patch.patchDirty = false;
        ch.patch.lastPatch = p;
    }

    private void emitSyntheticPatchSyncControls(MelodyProgram.ChannelSnapshot c, int l, int st, int sc, int raw, long t) {
        if (!PSM_EMIT_SYNTHETIC_PATCH_SYNC_CONTROLS || !isHostChannel(l)) return;
        emitter.emitVolume(st, sc, PATCH_SYNC_VOLUME_SOURCE, raw, l, t, computePsmVolumeSync(c, l));
        emitter.emitPan(st, sc, PATCH_SYNC_PAN_SOURCE, raw, l, t, 0);
    }

    private void emitInitialMidiDefaults(long t) {
        for (int ch = 0; ch < 16; ch++) {
            MelodyProgram.ChannelSnapshot d = defaultSnapshot();
            channels[ch].copyNative(d);
            emitter.emitVolume(-1, -1, "default_level", 0, ch, t, computePsmVolumeSync(d, ch));
            emitter.emitPan(-1, -1, "default_pan", 0, ch, t, computePsmPanSync(d));
            emitter.emitPitchRange(-1, -1, "default_pitch_range", 0, ch, t, d.pitchRange);
            emitter.emitPitchBend(-1, -1, "default_pitch", 0, ch, t, computePitchBend(d));
            emitter.emitModulation(-1, -1, "default_modulation", 0, ch, t, d.modulation * 2);
        }
    }

    private void resetChannels() {
        channels = createChannels();
    }

    private void warnHostChannel(int l, String context) {
        String w = "Logical channel " + l
                + " is outside the host MIDI bridge\'s 16-channel surface"
                + (context == null || context.isEmpty() ? "" : " for " + context)
                + ".";
        if (!warnings.contains(w)) warnings.add(w);
    }

    private static int computePsmVolumeSync(MelodyProgram.ChannelSnapshot c, int l) {
        int v = clamp(0, 127, c.level * 2);
        v = v * PSM_GLOBAL_LEVEL_SCALE / 100;
        v = v * PSM_CHANNEL_LEVEL_SCALE / 100;
        return clamp(0, 127, v);
    }

    private static int computePsmPanSync(MelodyProgram.ChannelSnapshot c) {
        return PSM_FORCE_PAN_LEFT_SYNC ? 0 : clamp(0, 127, c.pan * 2);
    }

    private static int computePitchBend(MelodyProgram.ChannelSnapshot c) {
        return clamp(0, 16383, (8 * (c.pitchFine + 32 * c.pitchCoarse)) - 256);
    }

    private static int baseForMode(int m) {
        return m == 1 ? 35 : 45;
    }

    private static long normalizeMidiEnd(long s, long e) {
        return e <= s ? s + 1 : e;
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

    private static ProjectionChannel[] createChannels() {
        ProjectionChannel[] r = new ProjectionChannel[64];
        for (int i = 0; i < r.length; i++) r[i] = new ProjectionChannel();
        return r;
    }

    private static MelodyProgram.ChannelSnapshot defaultSnapshot() {
        return new MelodyProgram.ChannelSnapshot(
                0,
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

    private static final class ProjectionChannel {
        final MidiPatchMapper.ChannelState patch = new MidiPatchMapper.ChannelState();
        MelodyProgram.ChannelSnapshot nativeChannel = defaultSnapshot();
        boolean pitchRangeDirty;

        void copyNative(MelodyProgram.ChannelSnapshot c) {
            if (c != null) {
                nativeChannel = c;
                patch.copyNative(c);
            }
        }
    }

    private static final class ActionRef {
        final int order;
        final MelodyProgram.NativeNote note;
        final MelodyProgram.NativeControl control;

        private ActionRef(int o, MelodyProgram.NativeNote n, MelodyProgram.NativeControl c) {
            order = o;
            note = n;
            control = c;
        }

        static ActionRef note(MelodyProgram.NativeNote n) {
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
