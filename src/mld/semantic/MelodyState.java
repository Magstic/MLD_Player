package mld.semantic;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import mld.decode.NoteEvent;
import mld.decode.SystemEvent;

/**
 * Native ordinary-track state machine and gate scheduler.
 */
final class MelodyState {
    private static final int MAX_LOGICAL_CHANNELS = 64;
    private static final int DEFAULT_MASTER_VOLUME = 100;
    private static final int DEFAULT_LEVEL = 63;
    private static final int DEFAULT_PAN = 32;
    private static final int DEFAULT_PITCH_COARSE = 32;
    private static final int DEFAULT_PITCH_FINE = 32;
    private static final int DEFAULT_PITCH_RANGE = 2;
    private static final int DEFAULT_MODULATION = 0;
    private static final int ORDINARY_NATIVE_REPLACEMENT_OVERLAP_SAMPLES = 128;
    private static final int[] OCTAVE_TABLE = {0, 12, -24, -12};
    private static final String RULE = "selected-half updates follow the currently active native half; pending replacement writes target the replacement half";
    private static final String PATH_LEVEL_PAN = "level_pan";
    private static final String PATH_PITCH = "pitch";
    private static final String PATH_LOOKUP = "lookup";
    private static final String PATH_PATCH = "patch";
    private static final String PATH_GLOBAL = "global";
    private static final String PATH_VOICE = "voice_assignment";
    private final List<MelodyProgram.NativeNote> notes = new ArrayList<MelodyProgram.NativeNote>();
    private final List<MelodyProgram.NoteAction> noteActions = new ArrayList<MelodyProgram.NoteAction>();
    private final List<MelodyProgram.GateSchedule> gateSchedules = new ArrayList<MelodyProgram.GateSchedule>();
    private final List<MelodyProgram.NativeControl> controls = new ArrayList<MelodyProgram.NativeControl>();
    private final List<MelodyProgram.UnmappedControl> unmappedControls = new ArrayList<MelodyProgram.UnmappedControl>();
    private final Map<Integer, ActiveNote> activeNotes = new LinkedHashMap<Integer, ActiveNote>();
    private ChannelState[] channels = createChannelStates();
    private final int[] voiceMap;
    private final List<String> warnings;
    private final Set<String> warningKeys;
    private int masterVolume = DEFAULT_MASTER_VOLUME;

    MelodyState(int size, List<String> w, Set<String> k) {
        voiceMap = createIdentityVoiceMap(size);
        warnings = w;
        warningKeys = k;
    }

    int processNote(NoteEvent e, int order) {
        int lane = laneIndex(e.trackIndex, e.voice);
        int logical = resolveVoiceMap(lane);
        if (logical < 0 || logical >= MAX_LOGICAL_CHANNELS) {
            warn(
                    "note_channel_" + lane + "_" + logical,
                    "Skipping note mapped outside logical-channel range: track=" + e.trackIndex
                            + " voice=" + e.voice + " -> " + logical);
            return -1;
        }
        ChannelState ch = channels[logical];
        boolean sounding = ch.allowsOrdinaryNoteOn();
        if (!sounding) {
            warn(
                    "suppressed_note_on_" + logical + "_" + ch.mode,
                    "Native patch state suppresses note-on on logical channel " + logical
                            + "; preserving its silent gate state for retrigger semantics.");
        }
        int pitchOffset = e.pitch + octaveOffset(e.octaveShift);
        int nativeNote = baseForMode(ch.mode) + pitchOffset;
        int velocity = clamp(1, 127, e.hasExtraByte() ? e.velocity * 2 : 126);
        int rawEnd = e.rawTick + e.gate;
        Integer key = (logical << 8) | (nativeNote & 255);
        ActiveNote prev = activeNotes.remove(key);
        boolean scheduledSounding = prev != null ? prev.sounding : sounding;
        gateSchedules.add(new MelodyProgram.GateSchedule(e.trackIndex, e.voice, logical, nativeNote, e.rawTick, rawEnd, scheduledSounding));
        if (prev != null) {
            activeNotes.put(key, prev.refreshGate(rawEnd));
            return rawEnd;
        }
        activeNotes.put(
                key,
                new ActiveNote(
                        e.trackIndex,
                        e.voice,
                        logical,
                        nativeNote,
                        pitchOffset,
                        velocity,
                        e.rawTick,
                        rawEnd,
                        order,
                        snapshot(ch),
                        sounding));
        if (sounding) {
            noteActions.add(activeNotes.get(key).toAction(true, e.rawTick));
        }
        return rawEnd;
    }

    void processSystem(SystemEvent e, int order) {
        if (TimingState.isTempo(e)) return;
        boolean unmapped = false;
        switch (e.command) {
        case 0xB0:
            if (acceptTrackZero7Bit(e)) {
                masterVolume = e.value;
                record(e, -1, PATH_GLOBAL, order, null);
            }
            break;
        case 0xB1:
            if (acceptTrackZero7Bit(e)) record(e, -1, PATH_GLOBAL, order, null);
            break;
        case 0xB3:
            unmapped = true;
            break;
        case 0xD0:
        case 0xDC:
        case 0xDD:
        case 0xDE:
        case 0xDF:
            unmapped = true;
            break;
        case 0xBA:
            if (acceptTrackZero7Bit(e)) {
                int logical = (e.value >> 3) & 15;
                if (logical < channels.length) {
                    ChannelState ch = channels[logical];
                    ch.mode = e.value & 7;
                    if (ch.mode == 1) applyNativePatchHelperState(ch);
                    record(e, logical, PATH_PATCH, order, ch);
                }
            }
            unmapped = true;
            break;
        case 0xBC:
            break;
        case 0xBD:
            if (acceptTrackZero7Bit(e)) {
                masterVolume = clamp(0, 127, masterVolume + e.value - 0x40);
                record(e, -1, PATH_GLOBAL, order, null);
            }
            break;
        case 0xBE:
            if (e.trackIndex == 0) {
                if (e.value == 0) {
                    forceStopActiveNotes(e.rawTick);
                    record(e, -1, PATH_GLOBAL, order, null);
                } else warn("global_stop_nonzero_" + e.rawTick, "Ignoring nonzero global stop value " + e.value + " at raw tick " + e.rawTick + ".");
            }
            break;
        case 0xBF:
            if (e.trackIndex == 0) {
                forceStopActiveNotes(e.rawTick);
                record(e, -1, PATH_GLOBAL, order, null);
                resetChannelStates();
                masterVolume = DEFAULT_MASTER_VOLUME;
                resetVoiceMap();
            }
            break;
        case 0xE0:
            program(e, order);
            break;
        case 0xE1:
            bank(e, order);
            break;
        case 0xE2:
            absoluteLevel(e, order);
            break;
        case 0xE3:
            pan(e, order);
            break;
        case 0xE4:
            pitchCoarse(e, order);
            break;
        case 0xE5:
            voice(e, order);
            unmapped = true;
            break;
        case 0xE6:
            relativeLevel(e, order);
            break;
        case 0xE7:
            pitchRange(e, order);
            break;
        case 0xE8:
            pitchFine(e, order);
            break;
        case 0xE9:
            pitchFine(e, order);
            break;
        case 0xEA:
            modulation(e, order);
            break;
        default:
            unmapped = true;

        }
        if (unmapped) unmappedControls.add(new MelodyProgram.UnmappedControl(e.trackIndex, e.command, e.name, e.rawTick, e.value, e.part));
    }

    int flushExpired(int current) {
        int max = -1;
        Iterator<Map.Entry<Integer, ActiveNote>> it = activeNotes.entrySet().iterator();
        while (it.hasNext()) {
            ActiveNote a = it.next().getValue();
            if (a.rawEndTick > current) continue;
            max = Math.max(max, a.rawEndTick);
            if (a.sounding) {
                notes.add(a.toNativeNote(a.rawEndTick));
                noteActions.add(a.toAction(false, a.rawEndTick));
            }
            it.remove();
        }
        return max;
    }

    MelodyProgram finish() {
        return new MelodyProgram(
                new MelodyProgram.OrdinaryModel(true, 128, RULE, true, true, true, true),
                notes,
                noteActions,
                gateSchedules,
                controls,
                unmappedControls,
                voiceMap);
    }

    MelodyProgram drain() {
        MelodyProgram result = finish();
        notes.clear();
        noteActions.clear();
        gateSchedules.clear();
        controls.clear();
        unmappedControls.clear();
        return result;
    }

    int[] voiceMap() {
        return voiceMap;
    }

    String runtimeFingerprint(int rawOrigin) {
        StringBuilder out = new StringBuilder();
        out.append(masterVolume).append('|');
        for (int value : voiceMap) out.append(value).append(',');
        out.append('|');
        for (ChannelState channel : channels) {
            out.append(channel.mode).append(',')
                    .append(channel.bank).append(',')
                    .append(channel.program).append(',')
                    .append(channel.level).append(',')
                    .append(channel.pan).append(',')
                    .append(channel.pitchCoarse).append(',')
                    .append(channel.pitchFine).append(',')
                    .append(channel.pitchRange).append(',')
                    .append(channel.modulation).append(',')
                    .append(channel.noteOnSuppressed ? 1 : 0).append(';');
        }
        out.append('|');
        for (Map.Entry<Integer, ActiveNote> entry : activeNotes.entrySet()) {
            ActiveNote note = entry.getValue();
            out.append(entry.getKey()).append(':')
                    .append(note.sourceTrack).append(',')
                    .append(note.sourceVoice).append(',')
                    .append(note.logicalChannel).append(',')
                    .append(note.nativeNote).append(',')
                    .append(note.pitchOffset).append(',')
                    .append(note.rawStartTick - rawOrigin).append(',')
                    .append(note.rawEndTick - rawOrigin).append(',')
                    .append(note.velocity).append(',')
                    .append(note.sounding ? 1 : 0).append(',');
            appendSnapshot(out, note.channel).append(';');
        }
        return out.toString();
    }

    private static StringBuilder appendSnapshot(
            StringBuilder out, MelodyProgram.ChannelSnapshot channel) {
        return out.append(channel.mode).append(',')
                .append(channel.bank).append(',')
                .append(channel.program).append(',')
                .append(channel.level).append(',')
                .append(channel.pan).append(',')
                .append(channel.pitchCoarse).append(',')
                .append(channel.pitchFine).append(',')
                .append(channel.pitchRange).append(',')
                .append(channel.modulation).append(',')
                .append(channel.noteOnSuppressed ? 1 : 0).append(',')
                .append(channel.nativeKind).append(',')
                .append(channel.nativeSub).append(',')
                .append(channel.nativeValue);
    }

    private void program(SystemEvent e, int o) {
        int l = resolveMappedControlChannel(e);
        if (l < 0) return;
        ChannelState c = channels[l];
        c.program = e.value & 63;
        applyNativePatchHelperState(c);
        record(e, l, PATH_PATCH, o, c);
    }

    private void bank(SystemEvent e, int o) {
        int l = resolveMappedControlChannel(e);
        if (l < 0) return;
        ChannelState c = channels[l];
        c.bank = e.value & 63;
        if (c.mode == 1) applyNativePatchHelperState(c);
        record(e, l, PATH_PATCH, o, c);
    }

    private void absoluteLevel(SystemEvent e, int o) {
        int l = resolveMappedControlChannel(e);
        if (l < 0) return;
        ChannelState c = channels[l];
        c.level = e.value & 63;
        record(e, l, PATH_LEVEL_PAN, o, c);
    }

    private void relativeLevel(SystemEvent e, int o) {
        int l = resolveMappedControlChannel(e);
        if (l < 0) return;
        ChannelState c = channels[l];
        c.level = clamp(0, 63, c.level + ((e.value & 63) - 32));
        record(e, l, PATH_LEVEL_PAN, o, c);
    }

    private void pan(SystemEvent e, int o) {
        int l = resolveMappedControlChannel(e);
        if (l < 0) return;
        ChannelState c = channels[l];
        c.pan = e.value & 63;
        record(e, l, PATH_LEVEL_PAN, o, c);
    }

    private void pitchCoarse(SystemEvent e, int o) {
        int l = resolveMappedControlChannel(e);
        if (l < 0) return;
        ChannelState c = channels[l];
        c.pitchCoarse = e.value & 63;
        record(e, l, PATH_PITCH, o, c);
    }

    private void pitchFine(SystemEvent e, int o) {
        int l = resolveMappedControlChannel(e);
        if (l < 0) return;
        ChannelState c = channels[l];
        c.pitchFine = e.value & 63;
        record(e, l, PATH_PITCH, o, c);
    }

    private void pitchRange(SystemEvent e, int o) {
        int l = resolveMappedControlChannel(e);
        if (l < 0) return;
        int range = e.value & 63;
        if (range > 24) {
            warn(
                    "pitch_range_" + l + "_" + range,
                    "Ignoring pitch range " + range + " on logical channel " + l
                            + " because the verified parser only accepts values <= 24.");
            return;
        }
        ChannelState c = channels[l];
        c.pitchRange = range;
        record(e, l, PATH_PITCH, o, c);
    }

    private void modulation(SystemEvent e, int o) {
        int l = resolveMappedControlChannel(e);
        if (l < 0) return;
        ChannelState c = channels[l];
        c.modulation = e.value & 63;
        record(e, l, PATH_LOOKUP, o, c);
    }

    private void voice(SystemEvent e, int o) {
        if (e.part < 0) return;
        int lane = laneIndex(e.trackIndex, e.part);
        if (lane >= 0 && lane < voiceMap.length) {
            voiceMap[lane] = e.value & 63;
            record(e, voiceMap[lane], PATH_VOICE, o, null);
        }
    }

    private void forceStopActiveNotes(int raw) {
        Iterator<Map.Entry<Integer, ActiveNote>> it = activeNotes.entrySet().iterator();
        while (it.hasNext()) {
            ActiveNote a = it.next().getValue();
            int end = Math.max(a.rawStartTick, raw);
            if (a.sounding) {
                notes.add(a.toNativeNote(end));
                noteActions.add(a.toAction(false, end));
            }
            it.remove();
        }
    }

    private void record(SystemEvent e, int logical, String path, int order, ChannelState ch) {
        controls.add(new MelodyProgram.NativeControl(
                e.trackIndex,
                e.command,
                e.name,
                e.rawTick,
                logical,
                e.value,
                e.part,
                masterVolume,
                path,
                logical >= 0,
                logical >= 0,
                logical >= 0 ? 128 : 0,
                order,
                ch == null ? null : snapshot(ch)));
    }

    private int resolveMappedControlChannel(SystemEvent e) {
        if (e.part < 0) return -1;
        int lane = laneIndex(e.trackIndex, e.part);
        int l = resolveVoiceMap(lane);
        if (l < 0 || l >= channels.length) {
            warn(
                    "control_channel_" + e.trackIndex + "_" + e.part + "_" + l,
                    "Skipping control " + e.name + " mapped outside logical-channel range: track="
                            + e.trackIndex + " part=" + e.part + " -> " + l);
            return -1;
        }
        return l;
    }

    private int resolveVoiceMap(int lane) {
        return lane >= 0 && lane < voiceMap.length ? voiceMap[lane] : lane;
    }

    private MelodyProgram.ChannelSnapshot snapshot(ChannelState c) {
        NativePatchState p = resolveOfficialNativePatchState(c);
        return new MelodyProgram.ChannelSnapshot(
                c.mode,
                c.bank,
                c.program,
                c.level,
                c.pan,
                c.pitchCoarse,
                c.pitchFine,
                c.pitchRange,
                c.modulation,
                c.noteOnSuppressed,
                p.kind,
                p.sub,
                p.value);
    }

    private static NativePatchState resolveOfficialNativePatchState(ChannelState c) {
        int mode = c.mode & 7;
        int bank = c.bank & 63;
        int program = c.program & 63;
        if (mode == 0) {
            if (bank == 0) return new NativePatchState(125, 0, program);
            if (bank == 4 || bank == 5) return new NativePatchState(121, 1, selectorWithOddBankPage(program, bank));
            if (bank == 0x36) return new NativePatchState(17, 0, program);
            if (bank < 0x34) return new NativePatchState(121, 0, selectorWithOddBankPage(program, bank));
            return new NativePatchState(255, 0, 0);
        }
        if (mode == 1) {
            if (bank == 4 || bank == 5) return new NativePatchState(120, 1, 25);
            if (bank == 0x34) return new NativePatchState(20, 0, program);
            if (bank == 0x36) return new NativePatchState(16, 0, program);
            if (bank < 0x34) return new NativePatchState(120, 0, selectorWithOddBankPage(program, bank));
            return new NativePatchState(255, 0, 0);
        }
        return new NativePatchState(255, 0, 0);
    }

    private static void applyNativePatchHelperState(ChannelState c) {
        c.noteOnSuppressed = c.mode != 0 && c.mode != 1;
    }

    private static boolean acceptTrackZero7Bit(SystemEvent e) {
        return e.trackIndex == 0 && e.value >= 0 && e.value < 128;
    }

    private static int laneIndex(int t, int v) {
        return t * 4 + v;
    }

    private static int baseForMode(int mode) {
        return mode == 1 ? 35 : 45;
    }

    private static int octaveOffset(int x) {
        return x >= 0 && x < OCTAVE_TABLE.length ? OCTAVE_TABLE[x] : 0;
    }

    private static int selectorWithOddBankPage(int p, int b) {
        return clamp(0, 127, (p & 63) + (((b & 1) != 0) ? 64 : 0));
    }

    private static int clamp(int min, int max, int v) {
        return Math.max(min, Math.min(max, v));
    }

    private static ChannelState[] createChannelStates() {
        ChannelState[] s = new ChannelState[MAX_LOGICAL_CHANNELS];
        for (int i = 0; i < s.length; i++) s[i] = new ChannelState();
        return s;
    }

    private static int[] createIdentityVoiceMap(int n) {
        int[] m = new int[n];
        for (int i = 0; i < n; i++) m[i] = i;
        return m;
    }

    private void resetVoiceMap() {
        for (int i = 0; i < voiceMap.length; i++) voiceMap[i] = i;
    }

    private void resetChannelStates() {
        channels = createChannelStates();
    }

    private void warn(String key, String msg) {
        WarningSink.addOnce(warnings, warningKeys, key, msg);
    }

    private static final class ChannelState {
        int mode;
        int bank;
        int program;
        int level = DEFAULT_LEVEL;
        int pan = DEFAULT_PAN;
        int pitchCoarse = DEFAULT_PITCH_COARSE;
        int pitchFine = DEFAULT_PITCH_FINE;
        int pitchRange = DEFAULT_PITCH_RANGE;
        int modulation = DEFAULT_MODULATION;
        boolean noteOnSuppressed;

        boolean allowsOrdinaryNoteOn() {
            return !noteOnSuppressed;
        }
    }

    private static final class NativePatchState {
        final int kind;
        final int sub;
        final int value;

        NativePatchState(int a, int b, int c) {
            kind = a;
            sub = b;
            value = c;
        }
    }

    private static final class ActiveNote {
        final int sourceTrack;
        final int sourceVoice;
        final int logicalChannel;
        final int nativeNote;
        final int pitchOffset;
        final int velocity;
        final int rawStartTick;
        final int rawEndTick;
        final int order;
        final MelodyProgram.ChannelSnapshot channel;
        final boolean sounding;

        ActiveNote(int a, int b, int c, int d, int e, int f, int g, int h, int i, MelodyProgram.ChannelSnapshot j, boolean k) {
            sourceTrack = a;
            sourceVoice = b;
            logicalChannel = c;
            nativeNote = d;
            pitchOffset = e;
            velocity = f;
            rawStartTick = g;
            rawEndTick = h;
            order = i;
            channel = j;
            sounding = k;
        }

        ActiveNote refreshGate(int end) {
            return new ActiveNote(sourceTrack, sourceVoice, logicalChannel, nativeNote, pitchOffset, velocity, rawStartTick, end, order, channel, sounding);
        }

        MelodyProgram.NativeNote toNativeNote(int end) {
            return new MelodyProgram.NativeNote(sourceTrack, sourceVoice, logicalChannel, nativeNote, pitchOffset, velocity, rawStartTick, end, order, channel);
        }

        MelodyProgram.NoteAction toAction(boolean noteOn, int rawTick) {
            return new MelodyProgram.NoteAction(
                    noteOn,
                    sourceTrack,
                    sourceVoice,
                    logicalChannel,
                    pitchOffset,
                    velocity,
                    rawTick,
                    order,
                    channel);
        }
    }
}
