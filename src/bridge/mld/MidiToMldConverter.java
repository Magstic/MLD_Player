package bridge.mld;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class MidiToMldConverter {
    private static final int TRACK_COUNT = 4;
    private static final int TIMEBASE = 48;
    private static final int DRUM_CHANNEL = 9;
    private static final int DEFAULT_PROGRAM = 0;
    private static final int DEFAULT_BANK = 0;
    private static final int DEFAULT_CC7 = 127;
    private static final int DEFAULT_CC11 = 127;
    private static final int DEFAULT_PAN = 64;
    private static final int DEFAULT_PITCH_RANGE = 2;
    private static final int DEFAULT_PITCH_BEND = 8192;
    private static final int DEFAULT_MODULATION = 0;
    private static final int DEFAULT_MASTER_VOLUME = 127;
    private static final int DEFAULT_MODE = 0;
    private static final int MAX_GATE = 255;
    private static final int CONTINUATION_STEP = 254;
    private static final int[] OCTAVE_OFFSETS = new int[] { 0, 12, -24, -12 };
    private static final Comparator<TrackEventData> TRACK_EVENT_COMPARATOR = new Comparator<TrackEventData>() {
        @Override
        public int compare(TrackEventData left, TrackEventData right) {
            int byTick = Integer.compare(left.rawTick, right.rawTick);
            if (byTick != 0) {
                return byTick;
            }
            return Integer.compare(left.order, right.order);
        }
    };

    public GeneratedMldSong convert(ImportedMidiSong song) throws IOException {
        if (song.notes.isEmpty()) {
            throw new IllegalArgumentException("The MIDI file does not contain any convertible melody notes.");
        }

        List<String> warnings = new ArrayList<String>(song.warnings);
        Set<String> warningKeys = new LinkedHashSet<String>();
        ChannelState[] channelStates = createChannelStates();
        TrackBuilder[] tracks = createTracks();
        boolean[] activeChannels = detectActiveChannels(song);

        emitTrackPreamble(tracks[0], 0);
        for (int trackIndex = 1; trackIndex < TRACK_COUNT; trackIndex++) {
            tracks[trackIndex].addSystemEvent(0, trackIndex * 1000, 0xDE, 0x00);
        }

        int tempoCount = emitTempoTrack(song, tracks[0]);
        int cueCount = emitCueTrack(song, tracks[0]);
        int controlCount = emitInitialChannelState(song, tracks, channelStates, activeChannels, warnings, warningKeys);
        controlCount += emitRuntimeChannelEvents(song, tracks, channelStates, activeChannels, warnings, warningKeys);
        int noteCount = emitNotes(song, tracks, activeChannels, warnings, warningKeys);
        int finalRawTick = maxEndRawTick(tracks);

        List<GeneratedMldSong.GeneratedMldTrack> outputTracks = new ArrayList<GeneratedMldSong.GeneratedMldTrack>(TRACK_COUNT);
        for (int trackIndex = 0; trackIndex < TRACK_COUNT; trackIndex++) {
            TrackBuilder builder = tracks[trackIndex];
            builder.addSystemEvent(finalRawTick, 900000 + trackIndex, 0xDF, 0x00);
            outputTracks.add(new GeneratedMldSong.GeneratedMldTrack(
                    trackIndex,
                    trackIndex * 4,
                    trackIndex * 4,
                    trackIndex == 0,
                    builder.buildPayload()));
        }

        return new GeneratedMldSong(
                song.title,
                song.copyright,
                TIMEBASE,
                TRACK_COUNT,
                noteCount,
                controlCount + cueCount,
                tempoCount,
                outputTracks,
                warnings);
    }

    private static ChannelState[] createChannelStates() {
        ChannelState[] states = new ChannelState[16];
        for (int channel = 0; channel < states.length; channel++) {
            states[channel] = new ChannelState();
        }
        return states;
    }

    private static TrackBuilder[] createTracks() {
        TrackBuilder[] tracks = new TrackBuilder[TRACK_COUNT];
        for (int trackIndex = 0; trackIndex < TRACK_COUNT; trackIndex++) {
            tracks[trackIndex] = new TrackBuilder(trackIndex);
        }
        return tracks;
    }

    private static boolean[] detectActiveChannels(ImportedMidiSong song) {
        boolean[] active = new boolean[16];
        for (ImportedMidiSong.ImportedNote note : song.notes) {
            active[note.channel] = true;
        }
        for (ImportedMidiSong.ChannelEvent event : song.channelEvents) {
            active[event.channel] = true;
        }
        return active;
    }

    private static void emitTrackPreamble(TrackBuilder track, int orderBase) {
        track.addSystemEvent(0, orderBase + 1, 0xD0, 0x00);
        track.addSystemEvent(0, orderBase + 900, 0xB0, DEFAULT_MASTER_VOLUME);
    }

    private static int emitTempoTrack(ImportedMidiSong song, TrackBuilder track) {
        List<ImportedMidiSong.TempoPoint> tempos = new ArrayList<ImportedMidiSong.TempoPoint>(song.tempos);
        if (tempos.isEmpty()) {
            tempos.add(new ImportedMidiSong.TempoPoint(0L, 500000, -1));
        } else if (tempos.get(0).tick > 0L) {
            tempos.add(0, new ImportedMidiSong.TempoPoint(0L, 500000, -1));
        }

        int emittedTempo = -1;
        int count = 0;
        for (ImportedMidiSong.TempoPoint tempo : tempos) {
            int bpm = tempo.mpqn > 0 ? (int) Math.round(60000000.0 / tempo.mpqn) : 120;
            bpm = clamp(1, 255, bpm);
            int rawTick = rawPosition(tempo.tick, song.inputPpq);
            if (bpm == emittedTempo && rawTick > 0) {
                continue;
            }
            emittedTempo = bpm;
            track.addSystemEvent(rawTick, 100 + count, 0xC3, bpm);
            count += 1;
        }
        return count;
    }

    private static int maxEndRawTick(TrackBuilder[] tracks) {
        int max = 0;
        for (TrackBuilder track : tracks) {
            max = Math.max(max, track.endRawTick);
        }
        return max;
    }

    private static int emitCueTrack(ImportedMidiSong song, TrackBuilder track) {
        int emitted = 0;
        for (int index = 0; index < song.cuePoints.size(); index++) {
            ImportedMidiSong.CuePoint cuePoint = song.cuePoints.get(index);
            int rawTick = rawPosition(cuePoint.tick, song.inputPpq);
            int value = cueValue(cuePoint, index);
            if (rawTick == 0 && value == 0) {
                continue;
            }
            track.addSystemEvent(rawTick, 500 + index, 0xD0, value);
            emitted += 1;
        }
        return emitted;
    }

    private static int emitInitialChannelState(
            ImportedMidiSong song,
            TrackBuilder[] tracks,
            ChannelState[] channelStates,
            boolean[] activeChannels,
            List<String> warnings,
            Set<String> warningKeys) {
        int emitted = 0;
        for (int trackIndex = 0; trackIndex < TRACK_COUNT; trackIndex++) {
            int order = 2000 + (trackIndex * 2000);
            for (int part = 0; part < 4; part++) {
                int channel = (trackIndex * 4) + part;
                if (!activeChannels[channel]) {
                    continue;
                }
                List<ImportedMidiSong.ChannelEvent> initialEvents = collectChannelEvents(song.channelEvents, channel, 0L);
                ChannelState state = channelStates[channel];
                InitialPatch initialPatch = resolveInitialPatch(state, initialEvents);
                state.bankMsb = initialPatch.bankMsb;
                state.program = initialPatch.program;

                for (ImportedMidiSong.ChannelEvent event : initialEvents) {
                    switch (event.type) {
                        case ImportedMidiSong.ChannelEvent.TYPE_BANK:
                            break;
                        case ImportedMidiSong.ChannelEvent.TYPE_PROGRAM:
                            break;
                        case ImportedMidiSong.ChannelEvent.TYPE_VOLUME:
                            state.cc7 = event.value & 0x7F;
                            break;
                        case ImportedMidiSong.ChannelEvent.TYPE_PAN:
                            state.pan = event.value & 0x7F;
                            break;
                        case ImportedMidiSong.ChannelEvent.TYPE_EXPRESSION:
                            state.cc11 = event.value & 0x7F;
                            break;
                        case ImportedMidiSong.ChannelEvent.TYPE_PITCH_RANGE:
                            state.pitchRange = clamp(0, 24, event.value & 0x7F);
                            break;
                        case ImportedMidiSong.ChannelEvent.TYPE_PITCH_BEND:
                            state.pitchBend = clamp(0, 16383, event.value);
                            break;
                        case ImportedMidiSong.ChannelEvent.TYPE_MODULATION:
                            state.modulation = event.value & 0x7F;
                            break;
                        default:
                            break;
                    }
                }

                state.mode = defaultModeForChannel(channel);
                if (state.mode != DEFAULT_MODE) {
                    tracks[trackIndex].addSystemEvent(0, order++, 0xBA, ((channel & 0x0F) << 3) | (state.mode & 0x07));
                    emitted += 1;
                }

                emitted += emitProgramPair(
                        tracks[trackIndex],
                        0,
                        order,
                        part,
                        initialPatch.bankMsb,
                        initialPatch.program,
                        warnings,
                        warningKeys,
                        channel);
                order += 2;

                state.level = cc7ToLevel(state.cc7);
                tracks[trackIndex].addSystemEvent(0, order++, 0xE2, encodePartValue(part, state.level));
                emitted += 1;

                int targetLevel = state.effectiveLevel();
                if (targetLevel != state.level) {
                    emitted += emitRelativeOrAbsoluteLevel(tracks[trackIndex], 0, order++, part, state, targetLevel);
                }

                tracks[trackIndex].addSystemEvent(0, order++, 0xE3, encodePartValue(part, panValue(state.pan)));
                emitted += 1;

                tracks[trackIndex].addSystemEvent(0, order++, 0xE7, encodePartValue(part, clamp(0, 24, state.pitchRange)));
                emitted += 1;

                PitchPair pair = reversePitchBend(state.pitchBend);
                if (pair.coarse != 32) {
                    tracks[trackIndex].addSystemEvent(0, order++, 0xE4, encodePartValue(part, pair.coarse));
                    emitted += 1;
                }
                tracks[trackIndex].addSystemEvent(0, order++, 0xE8, encodePartValue(part, pair.fine));
                emitted += 1;
                state.pitchCoarse = pair.coarse;
                state.pitchFine = pair.fine;

                if (state.modulation != DEFAULT_MODULATION) {
                    tracks[trackIndex].addSystemEvent(0, order++, 0xEA, encodePartValue(part, modulationValue(state.modulation)));
                    emitted += 1;
                }
            }
        }
        return emitted;
    }

    private static int emitRuntimeChannelEvents(
            ImportedMidiSong song,
            TrackBuilder[] tracks,
            ChannelState[] channelStates,
            boolean[] activeChannels,
            List<String> warnings,
            Set<String> warningKeys) {
        int emitted = 0;
        for (ImportedMidiSong.ChannelEvent event : song.channelEvents) {
            if (event.tick <= 0L || !activeChannels[event.channel]) {
                continue;
            }
            int trackIndex = event.channel / 4;
            int part = event.channel % 4;
            int rawTick = rawPosition(event.tick, song.inputPpq);
            int order = 100000 + event.order * 10;
            ChannelState state = channelStates[event.channel];

            switch (event.type) {
                case ImportedMidiSong.ChannelEvent.TYPE_BANK:
                    state.bankMsb = event.value & 0x7F;
                    break;
                case ImportedMidiSong.ChannelEvent.TYPE_PROGRAM:
                    state.program = event.value & 0x7F;
                    emitted += emitProgramPair(
                            tracks[trackIndex],
                            rawTick,
                            order,
                            part,
                            state.bankMsb,
                            state.program,
                            warnings,
                            warningKeys,
                            event.channel);
                    break;
                case ImportedMidiSong.ChannelEvent.TYPE_VOLUME:
                    state.cc7 = event.value & 0x7F;
                    state.level = state.effectiveLevel();
                    tracks[trackIndex].addSystemEvent(rawTick, order, 0xE2, encodePartValue(part, state.level));
                    emitted += 1;
                    break;
                case ImportedMidiSong.ChannelEvent.TYPE_PAN:
                    state.pan = event.value & 0x7F;
                    tracks[trackIndex].addSystemEvent(rawTick, order, 0xE3, encodePartValue(part, panValue(state.pan)));
                    emitted += 1;
                    break;
                case ImportedMidiSong.ChannelEvent.TYPE_EXPRESSION:
                    state.cc11 = event.value & 0x7F;
                    emitted += emitRelativeOrAbsoluteLevel(
                            tracks[trackIndex],
                            rawTick,
                            order,
                            part,
                            state,
                            state.effectiveLevel());
                    break;
                case ImportedMidiSong.ChannelEvent.TYPE_PITCH_RANGE: {
                    int range = clamp(0, 24, event.value & 0x7F);
                    if ((event.value & 0x7F) > 24) {
                        warnOnce(
                                warnings,
                                warningKeys,
                                "pitch_range_runtime_" + event.channel,
                                "Some pitch range values exceed the supported MLD range and were clamped to 24.");
                    }
                    state.pitchRange = range;
                    tracks[trackIndex].addSystemEvent(rawTick, order, 0xE7, encodePartValue(part, range));
                    emitted += 1;
                    break;
                }
                case ImportedMidiSong.ChannelEvent.TYPE_PITCH_BEND: {
                    state.pitchBend = clamp(0, 16383, event.value);
                    PitchPair pair = reversePitchBend(state.pitchBend);
                    if (pair.coarse != state.pitchCoarse) {
                        tracks[trackIndex].addSystemEvent(rawTick, order++, 0xE4, encodePartValue(part, pair.coarse));
                        emitted += 1;
                    }
                    tracks[trackIndex].addSystemEvent(rawTick, order, 0xE8, encodePartValue(part, pair.fine));
                    emitted += 1;
                    state.pitchCoarse = pair.coarse;
                    state.pitchFine = pair.fine;
                    break;
                }
                case ImportedMidiSong.ChannelEvent.TYPE_MODULATION:
                    state.modulation = event.value & 0x7F;
                    tracks[trackIndex].addSystemEvent(rawTick, order, 0xEA, encodePartValue(part, modulationValue(state.modulation)));
                    emitted += 1;
                    break;
                default:
                    break;
            }
        }
        return emitted;
    }

    private static int emitNotes(
            ImportedMidiSong song,
            TrackBuilder[] tracks,
            boolean[] activeChannels,
            List<String> warnings,
            Set<String> warningKeys) {
        int emitted = 0;
        for (ImportedMidiSong.ImportedNote note : song.notes) {
            if (!activeChannels[note.channel]) {
                continue;
            }
            int trackIndex = note.channel / 4;
            int part = note.channel % 4;
            int rawStart = rawPosition(note.startTick, song.inputPpq);
            int rawEnd = Math.max(rawStart + 1, rawPosition(note.endTick, song.inputPpq));
            EncodedNote encoded = encodeNote(
                    note.midiNote,
                    note.velocity,
                    part,
                    noteBaseForMode(defaultModeForChannel(note.channel)),
                    warnings,
                    warningKeys);
            emitted += emitSplitNote(tracks[trackIndex], rawStart, rawEnd, encoded.status, encoded.attr, note.order);
        }
        return emitted;
    }

    private static int emitSplitNote(TrackBuilder track, int rawStart, int rawEnd, int status, int attr, int noteOrder) {
        int emitted = 0;
        int currentStart = rawStart;
        while (rawEnd - currentStart > MAX_GATE) {
            track.addNoteEvent(currentStart, 300000 + noteOrder * 10 + emitted, status, MAX_GATE, attr);
            currentStart += CONTINUATION_STEP;
            emitted += 1;
        }
        int gate = Math.max(1, rawEnd - currentStart);
        track.addNoteEvent(currentStart, 300000 + noteOrder * 10 + emitted, status, gate, attr);
        return emitted + 1;
    }

    private static List<ImportedMidiSong.ChannelEvent> collectChannelEvents(
            List<ImportedMidiSong.ChannelEvent> allEvents,
            int channel,
            long tick) {
        List<ImportedMidiSong.ChannelEvent> filtered = new ArrayList<ImportedMidiSong.ChannelEvent>();
        for (ImportedMidiSong.ChannelEvent event : allEvents) {
            if (event.channel == channel && event.tick == tick) {
                filtered.add(event);
            }
        }
        return filtered;
    }

    private static int emitProgramPair(
            TrackBuilder track,
            int rawTick,
            int order,
            int part,
            int bankMsb,
            int program,
            List<String> warnings,
            Set<String> warningKeys,
            int channel) {
        int packedBank = packBankValue(bankMsb, program, warnings, warningKeys, channel);
        track.addSystemEvent(rawTick, order, 0xE1, encodePartValue(part, packedBank));
        track.addSystemEvent(rawTick, order + 1, 0xE0, encodePartValue(part, program & 0x3F));
        return 2;
    }

    private static int packBankValue(
            int bankMsb,
            int program,
            List<String> warnings,
            Set<String> warningKeys,
            int channel) {
        int bankLow5 = bankMsb & 0x1F;
        if ((bankMsb & 0x7F) > 31) {
            warnOnce(
                    warnings,
                    warningKeys,
                    "bank_truncate_" + bankMsb,
                    "MIDI bank " + (bankMsb & 0x7F) + " exceeds the 5-bit reversible patch-bank window and will be truncated during strict patch packing.");
        }
        return ((bankLow5 << 1) | ((program >> 6) & 0x01)) & 0x3F;
    }

    private static EncodedNote encodeNote(
            int midiNote,
            int velocity,
            int part,
            int baseNote,
            List<String> warnings,
            Set<String> warningKeys) {
        int clampedMidiNote = midiNote;
        if (clampedMidiNote < 21 || clampedMidiNote > 120) {
            int original = clampedMidiNote;
            clampedMidiNote = clamp(21, 120, clampedMidiNote);
            warnOnce(
                    warnings,
                    warningKeys,
                    "note_range_" + original,
                    "MIDI note " + original + " is outside the safe MLD melodic range and will be clamped to " + clampedMidiNote + ".");
        }

        NoteSolution best = null;
        for (int octaveShift = 0; octaveShift < OCTAVE_OFFSETS.length; octaveShift++) {
            int pitch = clampedMidiNote - baseNote - OCTAVE_OFFSETS[octaveShift];
            if (pitch < 0 || pitch > 63) {
                continue;
            }
            NoteSolution candidate = new NoteSolution(pitch, octaveShift);
            if (best == null || candidate.isBetterThan(best)) {
                best = candidate;
            }
        }
        if (best == null) {
            best = new NoteSolution(clamp(0, 63, clampedMidiNote - baseNote), 0);
        }

        int velocity6 = clamp(0, 63, (int) Math.round(velocity / 2.0));
        int status = ((part & 0x03) << 6) | (best.pitch & 0x3F);
        int attr = ((velocity6 & 0x3F) << 2) | (best.octaveShift & 0x03);
        return new EncodedNote(status, attr);
    }

    private static PitchPair reversePitchBend(int bend) {
        int coarse = clamp(0, 63, (int) Math.round((bend + 256) / 256.0) - 1);
        int fine = clamp(0, 63, (int) Math.round(((bend + 256) - (coarse * 256)) / 8.0));
        return new PitchPair(coarse, fine);
    }

    private static int defaultModeForChannel(int channel) {
        return channel == DRUM_CHANNEL ? 1 : DEFAULT_MODE;
    }

    private static int noteBaseForMode(int mode) {
        return mode == 1 ? 35 : 45;
    }

    private static int rawPosition(long tick, int inputPpq) {
        long scaled = (tick * (long) TIMEBASE) + Math.max(1, inputPpq / 2);
        return (int) Math.max(0L, scaled / inputPpq);
    }

    private static int cc7ToLevel(int cc7) {
        return clamp(0, 63, (cc7 & 0x7F) / 2);
    }

    private static int effectiveLevel(int cc7, int cc11) {
        return clamp(0, 63, (cc7 * cc11) / 254);
    }

    private static int panValue(int pan) {
        return clamp(0, 63, (pan & 0x7F) / 2);
    }

    private static int modulationValue(int modulation) {
        return clamp(0, 63, (modulation & 0x7F) / 2);
    }

    private static int cueValue(ImportedMidiSong.CuePoint cuePoint, int index) {
        if (index <= 0) {
            return 0;
        }
        String text = cuePoint.text != null ? cuePoint.text.trim() : "";
        if ("Q00".equalsIgnoreCase(text)) {
            return 0;
        }
        return 1;
    }

    private static int emitRelativeOrAbsoluteLevel(
            TrackBuilder track,
            int rawTick,
            int order,
            int part,
            ChannelState state,
            int targetLevel) {
        int delta = targetLevel - state.level;
        if (delta >= -32 && delta <= 31) {
            track.addSystemEvent(rawTick, order, 0xE6, encodePartValue(part, delta + 32));
        } else {
            track.addSystemEvent(rawTick, order, 0xE2, encodePartValue(part, targetLevel));
        }
        state.level = targetLevel;
        return 1;
    }

    private static int encodePartValue(int part, int value) {
        return ((part & 0x03) << 6) | (value & 0x3F);
    }

    private static int clamp(int min, int max, int value) {
        return Math.max(min, Math.min(max, value));
    }

    private static void warnOnce(List<String> warnings, Set<String> warningKeys, String key, String warning) {
        if (warningKeys.add(key)) {
            warnings.add(warning);
        }
    }

    private static final class ChannelState {
        int mode = DEFAULT_MODE;
        int bankMsb = DEFAULT_BANK;
        int program = DEFAULT_PROGRAM;
        int cc7 = DEFAULT_CC7;
        int cc11 = DEFAULT_CC11;
        int level = 63;
        int pan = DEFAULT_PAN;
        int pitchRange = DEFAULT_PITCH_RANGE;
        int pitchBend = DEFAULT_PITCH_BEND;
        int modulation = DEFAULT_MODULATION;
        int pitchCoarse = 32;
        int pitchFine = 32;

        int effectiveLevel() {
            return MidiToMldConverter.effectiveLevel(cc7, cc11);
        }
    }

    private static InitialPatch resolveInitialPatch(ChannelState baseState, List<ImportedMidiSong.ChannelEvent> initialEvents) {
        int bankMsb = baseState.bankMsb;
        int program = baseState.program;
        for (ImportedMidiSong.ChannelEvent event : initialEvents) {
            if (event.type == ImportedMidiSong.ChannelEvent.TYPE_BANK) {
                bankMsb = event.value & 0x7F;
            } else if (event.type == ImportedMidiSong.ChannelEvent.TYPE_PROGRAM) {
                program = event.value & 0x7F;
            }
        }
        return new InitialPatch(bankMsb, program);
    }

    private static final class NoteSolution {
        final int pitch;
        final int octaveShift;

        NoteSolution(int pitch, int octaveShift) {
            this.pitch = pitch;
            this.octaveShift = octaveShift;
        }

        boolean isBetterThan(NoteSolution other) {
            int thisOffset = Math.abs(OCTAVE_OFFSETS[octaveShift]);
            int otherOffset = Math.abs(OCTAVE_OFFSETS[other.octaveShift]);
            if (thisOffset != otherOffset) {
                return thisOffset < otherOffset;
            }
            int thisCenterDistance = Math.abs(pitch - 31);
            int otherCenterDistance = Math.abs(other.pitch - 31);
            if (thisCenterDistance != otherCenterDistance) {
                return thisCenterDistance < otherCenterDistance;
            }
            return octaveShift < other.octaveShift;
        }
    }

    private static final class PitchPair {
        final int coarse;
        final int fine;

        PitchPair(int coarse, int fine) {
            this.coarse = coarse;
            this.fine = fine;
        }
    }

    private static final class InitialPatch {
        final int bankMsb;
        final int program;

        InitialPatch(int bankMsb, int program) {
            this.bankMsb = bankMsb;
            this.program = program;
        }
    }

    private static final class EncodedNote {
        final int status;
        final int attr;

        EncodedNote(int status, int attr) {
            this.status = status;
            this.attr = attr;
        }
    }

    private static final class TrackBuilder {
        final int trackIndex;
        final List<TrackEventData> events = new ArrayList<TrackEventData>();
        int endRawTick = 0;

        TrackBuilder(int trackIndex) {
            this.trackIndex = trackIndex;
        }

        void addNoteEvent(int rawTick, int order, int status, int gate, int attr) {
            events.add(TrackEventData.note(rawTick, order, status, gate, attr));
            endRawTick = Math.max(endRawTick, rawTick + gate);
        }

        void addSystemEvent(int rawTick, int order, int command, int value) {
            events.add(TrackEventData.system(rawTick, order, command, value));
            endRawTick = Math.max(endRawTick, rawTick);
        }

        byte[] buildPayload() throws IOException {
            Collections.sort(events, TRACK_EVENT_COMPARATOR);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            int previousTick = 0;
            for (TrackEventData event : events) {
                int delta = event.rawTick - previousTick;
                writeDelayedEvent(output, delta, event);
                previousTick = event.rawTick;
            }
            return output.toByteArray();
        }

        private static void writeDelayedEvent(ByteArrayOutputStream output, int delta, TrackEventData event) throws IOException {
            int remaining = delta;
            while (remaining > 0xFFFF) {
                writeExtendedSystemEvent(output, 0xFFFF, 0xDE, 0x00);
                remaining -= 0xFFFF;
            }
            if (remaining > 0xFF) {
                writeExtendedPrefix(output, remaining >>> 8);
                writeEvent(output, remaining & 0xFF, event);
                return;
            }
            writeEvent(output, remaining, event);
        }

        private static void writeExtendedSystemEvent(ByteArrayOutputStream output, int delta, int command, int value) throws IOException {
            writeExtendedPrefix(output, delta >>> 8);
            writeEvent(output, delta & 0xFF, TrackEventData.system(0, 0, command, value));
        }

        private static void writeExtendedPrefix(ByteArrayOutputStream output, int highByte) {
            output.write(0);
            output.write(0xFF);
            output.write(0xDC);
            output.write(highByte & 0xFF);
        }

        private static void writeEvent(ByteArrayOutputStream output, int delta, TrackEventData event) {
            output.write(delta & 0xFF);
            if (event.noteEvent) {
                output.write(event.statusOrCommand & 0xFF);
                output.write(event.value1 & 0xFF);
                output.write(event.value2 & 0xFF);
                return;
            }
            output.write(0xFF);
            output.write(event.statusOrCommand & 0xFF);
            output.write(event.value1 & 0xFF);
        }
    }

    private static final class TrackEventData {
        final int rawTick;
        final int order;
        final boolean noteEvent;
        final int statusOrCommand;
        final int value1;
        final int value2;

        private TrackEventData(int rawTick, int order, boolean noteEvent, int statusOrCommand, int value1, int value2) {
            this.rawTick = rawTick;
            this.order = order;
            this.noteEvent = noteEvent;
            this.statusOrCommand = statusOrCommand;
            this.value1 = value1;
            this.value2 = value2;
        }

        static TrackEventData note(int rawTick, int order, int status, int gate, int attr) {
            return new TrackEventData(rawTick, order, true, status, gate, attr);
        }

        static TrackEventData system(int rawTick, int order, int command, int value) {
            return new TrackEventData(rawTick, order, false, command, value, 0);
        }
    }
}
