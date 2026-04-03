package playback;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Vector;

import timeline.PlaybackTimeline;
import util.SortUtil;

public final class MidiBytesBuilder {
    private static final int STATUS_NOTE_OFF = 0x80;
    private static final int STATUS_NOTE_ON = 0x90;
    private static final int STATUS_CONTROL_CHANGE = 0xB0;
    private static final int STATUS_PROGRAM_CHANGE = 0xC0;
    private static final int STATUS_PITCH_BEND = 0xE0;

    private static final int PHASE_TEMPO = 0;
    private static final int PHASE_NOTE_OFF = 1;
    private static final int PHASE_CONTROL = 2;
    private static final int PHASE_NOTE_ON = 3;

    private static final int SNAPSHOT_BASE_ORDER = -32768;
    private static final int[] SNAPSHOT_CONTROLLER_ORDER = new int[] { 7, 10, 1, 101, 100, 6, 38 };

    public byte[] build(PlaybackTimeline timeline) throws IOException {
        return buildInternal(timeline, false);
    }

    public byte[] buildLoopBody(PlaybackTimeline timeline) throws IOException {
        if (timeline == null) {
            throw new IOException("Timeline is null");
        }
        if (timeline.loopInfo == null || !timeline.loopInfo.hasLoop) {
            return buildInternal(timeline, false);
        }
        return buildInternal(timeline, true);
    }

    private byte[] buildInternal(PlaybackTimeline timeline, boolean loopBodyOnly) throws IOException {
        long contentStartTick;
        long contentEndTick;
        long contentLength;
        Vector events = new Vector();

        if (timeline == null) {
            throw new IOException("Timeline is null");
        }
        if (timeline.tempoPoints == null || timeline.tempoPoints.size() == 0) {
            throw new IOException("Timeline has no tempo points");
        }

        if (loopBodyOnly && timeline.loopInfo != null && timeline.loopInfo.hasLoop) {
            contentStartTick = maxLong(0L, timeline.loopInfo.loopStartMidiTick);
            contentEndTick = maxLong(contentStartTick + 1L, timeline.loopInfo.loopEndMidiTick);
        } else if (timeline.loopInfo != null && timeline.loopInfo.hasLoop) {
            contentStartTick = 0L;
            contentEndTick = maxLong(1L, timeline.loopInfo.loopEndMidiTick);
        } else {
            contentStartTick = 0L;
            contentEndTick = maxLong(1L, timeline.totalMidiTicks);
        }
        contentLength = maxLong(1L, contentEndTick - contentStartTick);

        emitTempoEvents(events, timeline, contentStartTick, contentEndTick, loopBodyOnly);
        if (loopBodyOnly) {
            emitControlSnapshot(events, timeline, contentStartTick);
            emitCarriedNotes(events, timeline, contentStartTick, contentEndTick);
        }
        emitControlEvents(events, timeline, contentStartTick, contentEndTick, loopBodyOnly);
        emitNoteEvents(events, timeline, contentStartTick, contentEndTick, loopBodyOnly);

        SortUtil.sort(events, MIDI_EVENT_COMPARATOR);
        return buildSmf(serializeTrack(events, contentLength));
    }

    private void emitTempoEvents(
            Vector events,
            PlaybackTimeline timeline,
            long contentStartTick,
            long contentEndTick,
            boolean loopBodyOnly) {
        PlaybackTimeline.TempoPoint active = activeTempoPointAt(timeline.tempoPoints, contentStartTick);
        int i;

        events.addElement(MidiEventRecord.tempo(0L, active.mpqn));
        for (i = 0; i < timeline.tempoPoints.size(); i++) {
            PlaybackTimeline.TempoPoint point = (PlaybackTimeline.TempoPoint) timeline.tempoPoints.elementAt(i);
            if (point.midiTick <= contentStartTick) {
                continue;
            }
            if (loopBodyOnly) {
                if (point.midiTick >= contentEndTick) {
                    continue;
                }
            } else if (point.midiTick > contentEndTick) {
                continue;
            }
            events.addElement(MidiEventRecord.tempo(point.midiTick - contentStartTick, point.mpqn));
        }
    }

    private void emitControlSnapshot(Vector events, PlaybackTimeline timeline, long contentStartTick) {
        Vector controls = copyVector(timeline.mappedControls);
        boolean[] hasProgram = new boolean[16];
        int[] programValue = new int[16];
        boolean[] hasPitchBend = new boolean[16];
        int[] pitchBendValue = new int[16];
        boolean[][] hasControl = new boolean[16][128];
        int[][] controlValue = new int[16][128];
        int channel;
        int order = SNAPSHOT_BASE_ORDER;
        int i;
        int j;

        SortUtil.sort(controls, CONTROL_COMPARATOR);
        for (i = 0; i < controls.size(); i++) {
            PlaybackTimeline.MappedControlEvent control = (PlaybackTimeline.MappedControlEvent) controls.elementAt(i);
            channel = control.midiChannel;
            if (channel < 0 || channel >= 16) {
                continue;
            }
            if (control.midiTick >= contentStartTick) {
                break;
            }
            if (control.status == STATUS_PROGRAM_CHANGE) {
                hasProgram[channel] = true;
                programValue[channel] = control.data1 & 0x7F;
            } else if (control.status == STATUS_PITCH_BEND) {
                hasPitchBend[channel] = true;
                pitchBendValue[channel] = ((control.data2 & 0x7F) << 7) | (control.data1 & 0x7F);
            } else if (control.status == STATUS_CONTROL_CHANGE) {
                int controller = control.data1 & 0x7F;
                hasControl[channel][controller] = true;
                controlValue[channel][controller] = control.data2 & 0x7F;
            }
        }

        for (channel = 0; channel < 16; channel++) {
            if (hasProgram[channel]) {
                events.addElement(MidiEventRecord.shortMessage(
                        0L,
                        PHASE_CONTROL,
                        channel,
                        STATUS_PROGRAM_CHANGE,
                        programValue[channel],
                        0,
                        order++));
            }

            for (j = 0; j < SNAPSHOT_CONTROLLER_ORDER.length; j++) {
                int controller = SNAPSHOT_CONTROLLER_ORDER[j];
                if (hasControl[channel][controller]) {
                    events.addElement(MidiEventRecord.shortMessage(
                            0L,
                            PHASE_CONTROL,
                            channel,
                            STATUS_CONTROL_CHANGE,
                            controller,
                            controlValue[channel][controller],
                            order++));
                }
            }

            for (j = 0; j < 128; j++) {
                if (!hasControl[channel][j] || isKnownSnapshotController(j)) {
                    continue;
                }
                events.addElement(MidiEventRecord.shortMessage(
                        0L,
                        PHASE_CONTROL,
                        channel,
                        STATUS_CONTROL_CHANGE,
                        j,
                        controlValue[channel][j],
                        order++));
            }

            if (hasPitchBend[channel]) {
                events.addElement(MidiEventRecord.shortMessage(
                        0L,
                        PHASE_CONTROL,
                        channel,
                        STATUS_PITCH_BEND,
                        pitchBendValue[channel] & 0x7F,
                        (pitchBendValue[channel] >> 7) & 0x7F,
                        order++));
            }
        }
    }

    private void emitControlEvents(
            Vector events,
            PlaybackTimeline timeline,
            long contentStartTick,
            long contentEndTick,
            boolean loopBodyOnly) {
        int i;
        for (i = 0; i < timeline.mappedControls.size(); i++) {
            PlaybackTimeline.MappedControlEvent control = (PlaybackTimeline.MappedControlEvent) timeline.mappedControls.elementAt(i);
            long tick = control.midiTick;
            if (loopBodyOnly) {
                if (tick < contentStartTick || tick >= contentEndTick) {
                    continue;
                }
            } else if (tick < contentStartTick || tick > contentEndTick) {
                continue;
            }
            events.addElement(MidiEventRecord.shortMessage(
                    tick - contentStartTick,
                    PHASE_CONTROL,
                    control.midiChannel,
                    control.status,
                    control.data1,
                    control.data2,
                    control.order));
        }
    }

    private void emitCarriedNotes(
            Vector events,
            PlaybackTimeline timeline,
            long contentStartTick,
            long contentEndTick) {
        int i;
        for (i = 0; i < timeline.notes.size(); i++) {
            PlaybackTimeline.CompiledNote note = (PlaybackTimeline.CompiledNote) timeline.notes.elementAt(i);
            long shiftedEndTick;
            int order;
            if (note.midiStartTick >= contentStartTick || note.midiEndTick <= contentStartTick) {
                continue;
            }
            shiftedEndTick = minLong(note.midiEndTick, contentEndTick) - contentStartTick;
            order = noteOrder(note);
            if (shiftedEndTick <= 0L) {
                shiftedEndTick = 1L;
            }
            events.addElement(MidiEventRecord.noteOff(
                    shiftedEndTick,
                    note.midiChannel,
                    note.midiNote,
                    order));
            events.addElement(MidiEventRecord.noteOn(
                    0L,
                    note.midiChannel,
                    note.midiNote,
                    note.velocity,
                    order));
        }
    }

    private void emitNoteEvents(
            Vector events,
            PlaybackTimeline timeline,
            long contentStartTick,
            long contentEndTick,
            boolean loopBodyOnly) {
        int i;
        for (i = 0; i < timeline.notes.size(); i++) {
            PlaybackTimeline.CompiledNote note = (PlaybackTimeline.CompiledNote) timeline.notes.elementAt(i);
            long shiftedStartTick;
            long shiftedEndTick;
            int order;

            if (loopBodyOnly) {
                if (note.midiStartTick < contentStartTick || note.midiStartTick >= contentEndTick) {
                    continue;
                }
            } else if (note.midiStartTick < contentStartTick || note.midiStartTick > contentEndTick) {
                continue;
            }

            shiftedStartTick = note.midiStartTick - contentStartTick;
            shiftedEndTick = minLong(note.midiEndTick, contentEndTick) - contentStartTick;
            if (shiftedEndTick <= shiftedStartTick) {
                shiftedEndTick = shiftedStartTick + 1L;
            }
            order = noteOrder(note);
            events.addElement(MidiEventRecord.noteOff(
                    shiftedEndTick,
                    note.midiChannel,
                    note.midiNote,
                    order));
            events.addElement(MidiEventRecord.noteOn(
                    shiftedStartTick,
                    note.midiChannel,
                    note.midiNote,
                    note.velocity,
                    order));
        }
    }

    private byte[] serializeTrack(Vector events, long contentLength) throws IOException {
        ByteArrayOutputStream trackOut = new ByteArrayOutputStream();
        long lastTick = 0L;
        int i;

        for (i = 0; i < events.size(); i++) {
            MidiEventRecord event = (MidiEventRecord) events.elementAt(i);
            long delta = event.tick - lastTick;
            if (delta < 0L) {
                delta = 0L;
            }
            writeVariableLength(trackOut, delta);
            writeEvent(trackOut, event);
            lastTick = event.tick;
        }

        writeVariableLength(trackOut, maxLong(0L, (contentLength + 1L) - lastTick));
        trackOut.write(0xFF);
        trackOut.write(0x2F);
        trackOut.write(0x00);
        return trackOut.toByteArray();
    }

    private byte[] buildSmf(byte[] trackBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write('M');
        out.write('T');
        out.write('h');
        out.write('d');
        writeBe32(out, 6L);
        writeBe16(out, 0);
        writeBe16(out, 1);
        writeBe16(out, PlaybackTimeline.MIDI_PPQ);
        out.write('M');
        out.write('T');
        out.write('r');
        out.write('k');
        writeBe32(out, trackBytes.length);
        out.write(trackBytes);
        return out.toByteArray();
    }

    private void writeEvent(ByteArrayOutputStream out, MidiEventRecord event) throws IOException {
        if (event.metaType >= 0) {
            out.write(0xFF);
            out.write(event.metaType & 0x7F);
            writeVariableLength(out, event.metaData.length);
            out.write(event.metaData);
            return;
        }

        out.write((event.status & 0xF0) | (event.midiChannel & 0x0F));
        out.write(event.data1 & 0x7F);
        if (needsTwoDataBytes(event.status)) {
            out.write(event.data2 & 0x7F);
        }
    }

    private static boolean needsTwoDataBytes(int status) {
        int family = status & 0xF0;
        return family != STATUS_PROGRAM_CHANGE;
    }

    private static void writeVariableLength(ByteArrayOutputStream out, long value) {
        int[] buffer = new int[5];
        int count = 0;
        long remaining = value;

        if (remaining < 0L) {
            remaining = 0L;
        }

        buffer[count++] = (int) (remaining & 0x7F);
        while ((remaining >>= 7) > 0L) {
            buffer[count++] = ((int) (remaining & 0x7F)) | 0x80;
        }
        while (count > 0) {
            out.write(buffer[--count]);
        }
    }

    private static void writeBe16(ByteArrayOutputStream out, int value) {
        out.write((value >> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static void writeBe32(ByteArrayOutputStream out, long value) {
        out.write((int) ((value >> 24) & 0xFF));
        out.write((int) ((value >> 16) & 0xFF));
        out.write((int) ((value >> 8) & 0xFF));
        out.write((int) (value & 0xFF));
    }

    private static boolean isKnownSnapshotController(int controller) {
        int i;
        for (i = 0; i < SNAPSHOT_CONTROLLER_ORDER.length; i++) {
            if (SNAPSHOT_CONTROLLER_ORDER[i] == controller) {
                return true;
            }
        }
        return false;
    }

    private static PlaybackTimeline.TempoPoint activeTempoPointAt(Vector tempoPoints, long tick) {
        PlaybackTimeline.TempoPoint current = (PlaybackTimeline.TempoPoint) tempoPoints.elementAt(0);
        int i;
        for (i = 1; i < tempoPoints.size(); i++) {
            PlaybackTimeline.TempoPoint next = (PlaybackTimeline.TempoPoint) tempoPoints.elementAt(i);
            if (next.midiTick > tick) {
                break;
            }
            current = next;
        }
        return current;
    }

    private static int noteOrder(PlaybackTimeline.CompiledNote note) {
        return (note.sourceTrack * 16) + note.sourceVoice;
    }

    private static Vector copyVector(Vector source) {
        Vector copy = new Vector();
        int i;
        for (i = 0; i < source.size(); i++) {
            copy.addElement(source.elementAt(i));
        }
        return copy;
    }

    private static int compareInt(int left, int right) {
        if (left < right) {
            return -1;
        }
        if (left > right) {
            return 1;
        }
        return 0;
    }

    private static int compareLong(long left, long right) {
        if (left < right) {
            return -1;
        }
        if (left > right) {
            return 1;
        }
        return 0;
    }

    private static long maxLong(long left, long right) {
        return left > right ? left : right;
    }

    private static long minLong(long left, long right) {
        return left < right ? left : right;
    }

    private static final SortUtil.Comparator CONTROL_COMPARATOR = new SortUtil.Comparator() {
        public int compare(Object left, Object right) {
            PlaybackTimeline.MappedControlEvent a = (PlaybackTimeline.MappedControlEvent) left;
            PlaybackTimeline.MappedControlEvent b = (PlaybackTimeline.MappedControlEvent) right;
            int byTick = compareLong(a.midiTick, b.midiTick);
            if (byTick != 0) {
                return byTick;
            }
            int byChannel = compareInt(a.midiChannel, b.midiChannel);
            if (byChannel != 0) {
                return byChannel;
            }
            int byOrder = compareInt(a.order, b.order);
            if (byOrder != 0) {
                return byOrder;
            }
            return compareInt(a.data1, b.data1);
        }
    };

    private static final SortUtil.Comparator MIDI_EVENT_COMPARATOR = new SortUtil.Comparator() {
        public int compare(Object left, Object right) {
            MidiEventRecord a = (MidiEventRecord) left;
            MidiEventRecord b = (MidiEventRecord) right;
            int byTick = compareLong(a.tick, b.tick);
            if (byTick != 0) {
                return byTick;
            }
            int byPhase = compareInt(a.phase, b.phase);
            if (byPhase != 0) {
                return byPhase;
            }
            int byChannel = compareInt(a.midiChannel, b.midiChannel);
            if (byChannel != 0) {
                return byChannel;
            }
            int byOrder = compareInt(a.order, b.order);
            if (byOrder != 0) {
                return byOrder;
            }
            int byStatus = compareInt(a.status, b.status);
            if (byStatus != 0) {
                return byStatus;
            }
            int byData1 = compareInt(a.data1, b.data1);
            if (byData1 != 0) {
                return byData1;
            }
            return compareInt(a.data2, b.data2);
        }
    };

    private static final class MidiEventRecord {
        final long tick;
        final int phase;
        final int midiChannel;
        final int status;
        final int data1;
        final int data2;
        final int order;
        final int metaType;
        final byte[] metaData;

        private MidiEventRecord(
                long tick,
                int phase,
                int midiChannel,
                int status,
                int data1,
                int data2,
                int order,
                int metaType,
                byte[] metaData) {
            this.tick = tick;
            this.phase = phase;
            this.midiChannel = midiChannel;
            this.status = status;
            this.data1 = data1;
            this.data2 = data2;
            this.order = order;
            this.metaType = metaType;
            this.metaData = metaData;
        }

        static MidiEventRecord tempo(long tick, int mpqn) {
            byte[] data = new byte[] {
                    (byte) ((mpqn >> 16) & 0xFF),
                    (byte) ((mpqn >> 8) & 0xFF),
                    (byte) (mpqn & 0xFF)
            };
            return new MidiEventRecord(tick, PHASE_TEMPO, -1, 0, 0, 0, 0, 0x51, data);
        }

        static MidiEventRecord shortMessage(
                long tick,
                int phase,
                int midiChannel,
                int status,
                int data1,
                int data2,
                int order) {
            return new MidiEventRecord(tick, phase, midiChannel, status, data1, data2, order, -1, null);
        }

        static MidiEventRecord noteOff(long tick, int midiChannel, int midiNote, int order) {
            return shortMessage(tick, PHASE_NOTE_OFF, midiChannel, STATUS_NOTE_OFF, midiNote, 0, order);
        }

        static MidiEventRecord noteOn(long tick, int midiChannel, int midiNote, int velocity, int order) {
            return shortMessage(tick, PHASE_NOTE_ON, midiChannel, STATUS_NOTE_ON, midiNote, velocity, order);
        }
    }
}
