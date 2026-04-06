package playback;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import timeline.PlaybackTimeline;

public final class MidiFileEncoder {
    private static final int FORMAT_TYPE_1 = 1;
    private static final int TRACK_COUNT = 17;
    private static final int CHANNEL_TRACK_OFFSET = 1;
    private static final int META_TRACK_NAME = 0x03;
    private static final int META_TEMPO = 0x51;
    private static final int META_END_OF_TRACK = 0x2F;

    public byte[] encode(PlaybackTimeline timeline) {
        long contentEndTick = timeline.loopInfo.hasLoop
                ? Math.max(1L, timeline.loopInfo.loopEndMidiTick)
                : Math.max(1L, timeline.totalMidiTicks);
        return encodeTransportSequence(timeline, contentEndTick);
    }

    public byte[] encode(PlaybackTimeline timeline, int requestedLoopCount) {
        if (timeline != null && timeline.loopInfo != null && timeline.loopInfo.hasLoop && requestedLoopCount >= 0) {
            return encodeExpandedFiniteLoopSequence(timeline, requestedLoopCount);
        }
        return encode(timeline);
    }

    public byte[] encodeSegment(
            PlaybackTimeline timeline,
            long segmentStartTick,
            long segmentEndTick,
            boolean primeState,
            String segmentName) {
        long safeSegmentStart = Math.max(0L, segmentStartTick);
        long safeSegmentEnd = Math.max(safeSegmentStart + 1L, segmentEndTick);
        long segmentLength = Math.max(1L, safeSegmentEnd - safeSegmentStart);
        List<TrackBuffer> tracks = createTrackBuffers(segmentName == null ? "MLD" : segmentName);

        emitTempoTrackSegment(timeline, tracks.get(0), safeSegmentStart, safeSegmentEnd);
        if (primeState && safeSegmentStart > 0L) {
            emitPrimedControls(timeline, tracks, safeSegmentStart);
        }
        emitChannelEventsSegment(
                timeline,
                tracks,
                safeSegmentStart,
                safeSegmentEnd,
                primeState && safeSegmentStart > 0L);
        finishTracks(tracks, segmentLength + 1L);
        return buildSmf(tracks);
    }

    public byte[] encodeRepeatedSegment(
            PlaybackTimeline timeline,
            long segmentStartTick,
            long segmentEndTick,
            boolean primeState,
            String segmentName,
            int repeatCount) {
        long safeSegmentStart = Math.max(0L, segmentStartTick);
        long safeSegmentEnd = Math.max(safeSegmentStart + 1L, segmentEndTick);
        long segmentLength = Math.max(1L, safeSegmentEnd - safeSegmentStart);
        int safeRepeatCount = Math.max(1, repeatCount);
        List<TrackBuffer> tracks = createTrackBuffers(segmentName == null ? "MLD" : segmentName);

        emitRepeatedTempoTrackSegment(
                timeline,
                tracks.get(0),
                safeSegmentStart,
                safeSegmentEnd,
                segmentLength,
                safeRepeatCount);
        if (primeState && safeSegmentStart > 0L) {
            emitPrimedControls(timeline, tracks, safeSegmentStart);
        }
        emitRepeatedChannelEventsSegment(
                timeline,
                tracks,
                safeSegmentStart,
                safeSegmentEnd,
                segmentLength,
                safeRepeatCount);
        finishTracks(tracks, (segmentLength * safeRepeatCount) + 1L);
        return buildSmf(tracks);
    }

    private byte[] encodeTransportSequence(PlaybackTimeline timeline, long contentEndTick) {
        List<TrackBuffer> tracks = createTrackBuffers("MLD");

        emitTempoTrackTransport(timeline, tracks.get(0), contentEndTick);
        emitChannelEventsTransport(timeline, tracks, contentEndTick);
        finishTracks(tracks, contentEndTick + 1L);
        return buildSmf(tracks);
    }

    private byte[] encodeExpandedFiniteLoopSequence(PlaybackTimeline timeline, int requestedLoopCount) {
        long loopStartTick = Math.max(0L, timeline.loopInfo.loopStartMidiTick);
        long loopEndTick = Math.max(loopStartTick + 1L, timeline.loopInfo.loopEndMidiTick);
        long loopBodyTickLength = Math.max(1L, loopEndTick - loopStartTick);
        int loopPassCount = Math.max(1, requestedLoopCount + 1);
        List<TrackBuffer> tracks = createTrackBuffers("MLD");
        long contentEndTick;

        emitExpandedTempoTrack(timeline, tracks.get(0), loopStartTick, loopEndTick, loopBodyTickLength, loopPassCount);
        contentEndTick = emitExpandedChannelEvents(
                timeline,
                tracks,
                loopStartTick,
                loopEndTick,
                loopBodyTickLength,
                loopPassCount);
        contentEndTick = Math.max(contentEndTick, loopStartTick + (loopBodyTickLength * loopPassCount));
        contentEndTick = Math.max(1L, contentEndTick);

        finishTracks(tracks, contentEndTick + 1L);
        return buildSmf(tracks);
    }

    private List<TrackBuffer> createTrackBuffers(String baseName) {
        List<TrackBuffer> tracks = new ArrayList<TrackBuffer>(TRACK_COUNT);
        int midiChannel;

        tracks.add(new TrackBuffer(baseName + " Conductor"));
        for (midiChannel = 0; midiChannel < 16; midiChannel++) {
            tracks.add(new TrackBuffer(channelName(midiChannel)));
        }
        return tracks;
    }

    private void finishTracks(List<TrackBuffer> tracks, long endTick) {
        int index;

        for (index = 0; index < tracks.size(); index++) {
            tracks.get(index).writeEndOfTrack(endTick);
        }
    }

    private void emitTempoTrackTransport(PlaybackTimeline timeline, TrackBuffer conductorTrack, long contentEndTick) {
        PlaybackTimeline.TempoPoint active = timeline.tempoPoints.get(0);
        conductorTrack.writeTempo(active.mpqn, 0L);
        for (PlaybackTimeline.TempoPoint point : timeline.tempoPoints) {
            if (point.midiTick <= 0L || point.midiTick > contentEndTick) {
                continue;
            }
            conductorTrack.writeTempo(point.mpqn, point.midiTick);
        }
    }

    private void emitChannelEventsTransport(PlaybackTimeline timeline, List<TrackBuffer> tracks, long contentEndTick) {
        List<TrackMessageEvent> events = new ArrayList<TrackMessageEvent>();
        List<PlaybackTimeline.MappedControlEvent> controls =
                new ArrayList<PlaybackTimeline.MappedControlEvent>(timeline.mappedControls);
        List<PlaybackTimeline.CompiledNote> notes =
                new ArrayList<PlaybackTimeline.CompiledNote>(timeline.notes);

        Collections.sort(controls, CONTROL_COMPARATOR);
        for (PlaybackTimeline.MappedControlEvent control : controls) {
            if (control.midiTick < 0L || control.midiTick > contentEndTick) {
                continue;
            }
            events.add(TrackMessageEvent.control(
                    control.midiChannel,
                    control.midiTick,
                    control.status,
                    control.data1,
                    control.data2,
                    control.order));
        }

        Collections.sort(notes, NOTE_COMPARATOR);
        for (PlaybackTimeline.CompiledNote note : notes) {
            long noteOffTick;
            int noteOrder;

            if (note.midiStartTick > contentEndTick) {
                continue;
            }
            noteOffTick = Math.min(note.midiEndTick, contentEndTick);
            noteOrder = (note.sourceTrack * 16) + note.sourceVoice;
            events.add(TrackMessageEvent.noteOff(note.midiChannel, noteOffTick, note.midiNote, noteOrder));
            events.add(TrackMessageEvent.noteOn(note.midiChannel, note.midiStartTick, note.midiNote, note.velocity, noteOrder));
        }

        writeTrackEvents(tracks, events);
    }

    private void emitExpandedTempoTrack(
            PlaybackTimeline timeline,
            TrackBuffer conductorTrack,
            long loopStartTick,
            long loopEndTick,
            long loopBodyTickLength,
            int loopPassCount) {
        PlaybackTimeline.TempoPoint active = timeline.tempoPoints.get(0);
        conductorTrack.writeTempo(active.mpqn, 0L);
        for (PlaybackTimeline.TempoPoint point : timeline.tempoPoints) {
            int passIndex;

            if (point.midiTick <= 0L) {
                continue;
            }
            if (point.midiTick < loopStartTick) {
                conductorTrack.writeTempo(point.mpqn, point.midiTick);
                continue;
            }
            if (point.midiTick >= loopEndTick) {
                continue;
            }
            for (passIndex = 0; passIndex < loopPassCount; passIndex++) {
                long shiftedTick = point.midiTick + (loopBodyTickLength * passIndex);
                conductorTrack.writeTempo(point.mpqn, shiftedTick);
            }
        }
    }

    private long emitExpandedChannelEvents(
            PlaybackTimeline timeline,
            List<TrackBuffer> tracks,
            long loopStartTick,
            long loopEndTick,
            long loopBodyTickLength,
            int loopPassCount) {
        List<TrackMessageEvent> events = new ArrayList<TrackMessageEvent>();
        List<PlaybackTimeline.MappedControlEvent> controls =
                new ArrayList<PlaybackTimeline.MappedControlEvent>(timeline.mappedControls);
        List<PlaybackTimeline.CompiledNote> notes =
                new ArrayList<PlaybackTimeline.CompiledNote>(timeline.notes);
        long maxTick = loopEndTick;

        Collections.sort(controls, CONTROL_COMPARATOR);
        for (PlaybackTimeline.MappedControlEvent control : controls) {
            int passIndex;

            if (control.midiTick < 0L) {
                continue;
            }
            if (control.midiTick < loopStartTick) {
                events.add(TrackMessageEvent.control(
                        control.midiChannel,
                        control.midiTick,
                        control.status,
                        control.data1,
                        control.data2,
                        control.order));
                maxTick = Math.max(maxTick, control.midiTick);
                continue;
            }
            if (control.midiTick >= loopEndTick) {
                continue;
            }
            for (passIndex = 0; passIndex < loopPassCount; passIndex++) {
                long shiftedTick = control.midiTick + (loopBodyTickLength * passIndex);
                events.add(TrackMessageEvent.control(
                        control.midiChannel,
                        shiftedTick,
                        control.status,
                        control.data1,
                        control.data2,
                        control.order));
                maxTick = Math.max(maxTick, shiftedTick);
            }
        }

        Collections.sort(notes, NOTE_COMPARATOR);
        for (PlaybackTimeline.CompiledNote note : notes) {
            int noteOrder = (note.sourceTrack * 16) + note.sourceVoice;

            if (note.midiStartTick < loopStartTick) {
                events.add(TrackMessageEvent.noteOff(note.midiChannel, note.midiEndTick, note.midiNote, noteOrder));
                events.add(TrackMessageEvent.noteOn(note.midiChannel, note.midiStartTick, note.midiNote, note.velocity, noteOrder));
                maxTick = Math.max(maxTick, note.midiEndTick);
                continue;
            }
            if (note.midiStartTick >= loopEndTick) {
                continue;
            }
            for (int passIndex = 0; passIndex < loopPassCount; passIndex++) {
                long shiftedStartTick = note.midiStartTick + (loopBodyTickLength * passIndex);
                long shiftedEndTick = note.midiEndTick + (loopBodyTickLength * passIndex);
                events.add(TrackMessageEvent.noteOff(note.midiChannel, shiftedEndTick, note.midiNote, noteOrder));
                events.add(TrackMessageEvent.noteOn(note.midiChannel, shiftedStartTick, note.midiNote, note.velocity, noteOrder));
                maxTick = Math.max(maxTick, shiftedEndTick);
            }
        }

        writeTrackEvents(tracks, events);
        return maxTick;
    }

    private void emitTempoTrackSegment(
            PlaybackTimeline timeline,
            TrackBuffer conductorTrack,
            long segmentStartTick,
            long segmentEndTick) {
        PlaybackTimeline.TempoPoint active = timeline.tempoPoints.get(0);

        for (PlaybackTimeline.TempoPoint point : timeline.tempoPoints) {
            if (point.midiTick <= segmentStartTick) {
                active = point;
            } else {
                break;
            }
        }

        conductorTrack.writeTempo(active.mpqn, 0L);
        for (PlaybackTimeline.TempoPoint point : timeline.tempoPoints) {
            if (point.midiTick <= segmentStartTick || point.midiTick >= segmentEndTick) {
                continue;
            }
            conductorTrack.writeTempo(point.mpqn, point.midiTick - segmentStartTick);
        }
    }

    private void emitRepeatedTempoTrackSegment(
            PlaybackTimeline timeline,
            TrackBuffer conductorTrack,
            long segmentStartTick,
            long segmentEndTick,
            long segmentLength,
            int repeatCount) {
        PlaybackTimeline.TempoPoint active = timeline.tempoPoints.get(0);

        for (PlaybackTimeline.TempoPoint point : timeline.tempoPoints) {
            if (point.midiTick <= segmentStartTick) {
                active = point;
            } else {
                break;
            }
        }

        conductorTrack.writeTempo(active.mpqn, 0L);
        for (PlaybackTimeline.TempoPoint point : timeline.tempoPoints) {
            if (point.midiTick <= segmentStartTick || point.midiTick >= segmentEndTick) {
                continue;
            }

            long localTick = point.midiTick - segmentStartTick;
            for (int passIndex = 0; passIndex < repeatCount; passIndex++) {
                conductorTrack.writeTempo(point.mpqn, localTick + (segmentLength * passIndex));
            }
        }
    }

    private void emitPrimedControls(PlaybackTimeline timeline, List<TrackBuffer> tracks, long segmentStartTick) {
        Map<String, PlaybackTimeline.MappedControlEvent> latest =
                new LinkedHashMap<String, PlaybackTimeline.MappedControlEvent>();
        List<PlaybackTimeline.MappedControlEvent> controls =
                new ArrayList<PlaybackTimeline.MappedControlEvent>(timeline.mappedControls);

        Collections.sort(controls, CONTROL_COMPARATOR);
        for (PlaybackTimeline.MappedControlEvent control : controls) {
            if (control.midiTick > segmentStartTick) {
                break;
            }
            latest.put(controlKey(control), control);
        }

        List<PlaybackTimeline.MappedControlEvent> primed =
                new ArrayList<PlaybackTimeline.MappedControlEvent>(latest.values());
        Collections.sort(primed, PRIMED_CONTROL_COMPARATOR);
        for (PlaybackTimeline.MappedControlEvent control : primed) {
            trackForChannel(tracks, control.midiChannel).writeShortMessage(
                    control.status,
                    control.midiChannel,
                    control.data1,
                    control.data2,
                    0L);
        }
    }

    private void emitChannelEventsSegment(
            PlaybackTimeline timeline,
            List<TrackBuffer> tracks,
            long segmentStartTick,
            long segmentEndTick,
            boolean includeBoundaryCarryNotes) {
        List<TrackMessageEvent> events = new ArrayList<TrackMessageEvent>();
        List<PlaybackTimeline.MappedControlEvent> controls =
                new ArrayList<PlaybackTimeline.MappedControlEvent>(timeline.mappedControls);
        List<PlaybackTimeline.CompiledNote> notes =
                new ArrayList<PlaybackTimeline.CompiledNote>(timeline.notes);

        Collections.sort(controls, CONTROL_COMPARATOR);
        for (PlaybackTimeline.MappedControlEvent control : controls) {
            if (control.midiTick < segmentStartTick || control.midiTick >= segmentEndTick) {
                continue;
            }
            events.add(TrackMessageEvent.control(
                    control.midiChannel,
                    control.midiTick - segmentStartTick,
                    control.status,
                    control.data1,
                    control.data2,
                    control.order));
        }

        Collections.sort(notes, NOTE_COMPARATOR);
        if (includeBoundaryCarryNotes) {
            for (PlaybackTimeline.CompiledNote note : notes) {
                long localEnd;
                int noteOrder;

                if (note.midiStartTick >= segmentStartTick || note.midiEndTick <= segmentStartTick) {
                    continue;
                }
                localEnd = Math.min(note.midiEndTick, segmentEndTick) - segmentStartTick;
                if (localEnd <= 0L) {
                    localEnd = 1L;
                }
                noteOrder = (note.sourceTrack * 16) + note.sourceVoice;
                events.add(TrackMessageEvent.noteOff(note.midiChannel, localEnd, note.midiNote, noteOrder));
                events.add(TrackMessageEvent.noteOn(note.midiChannel, 0L, note.midiNote, note.velocity, noteOrder));
            }
        }
        for (PlaybackTimeline.CompiledNote note : notes) {
            long localStart;
            long localEnd;
            int noteOrder;

            if (includeBoundaryCarryNotes && note.midiStartTick < segmentStartTick && note.midiEndTick > segmentStartTick) {
                continue;
            }
            if (note.midiEndTick <= segmentStartTick || note.midiStartTick >= segmentEndTick) {
                continue;
            }
            localStart = Math.max(note.midiStartTick, segmentStartTick) - segmentStartTick;
            localEnd = Math.min(note.midiEndTick, segmentEndTick) - segmentStartTick;
            if (localEnd <= localStart) {
                localEnd = localStart + 1L;
            }
            noteOrder = (note.sourceTrack * 16) + note.sourceVoice;
            events.add(TrackMessageEvent.noteOff(note.midiChannel, localEnd, note.midiNote, noteOrder));
            events.add(TrackMessageEvent.noteOn(note.midiChannel, localStart, note.midiNote, note.velocity, noteOrder));
        }

        writeTrackEvents(tracks, events);
    }

    private void emitRepeatedChannelEventsSegment(
            PlaybackTimeline timeline,
            List<TrackBuffer> tracks,
            long segmentStartTick,
            long segmentEndTick,
            long segmentLength,
            int repeatCount) {
        List<TrackMessageEvent> events = new ArrayList<TrackMessageEvent>();
        List<PlaybackTimeline.MappedControlEvent> controls =
                new ArrayList<PlaybackTimeline.MappedControlEvent>(timeline.mappedControls);
        List<PlaybackTimeline.CompiledNote> notes =
                new ArrayList<PlaybackTimeline.CompiledNote>(timeline.notes);

        Collections.sort(controls, CONTROL_COMPARATOR);
        for (PlaybackTimeline.MappedControlEvent control : controls) {
            if (control.midiTick < segmentStartTick || control.midiTick >= segmentEndTick) {
                continue;
            }

            long localTick = control.midiTick - segmentStartTick;
            for (int passIndex = 0; passIndex < repeatCount; passIndex++) {
                events.add(TrackMessageEvent.control(
                        control.midiChannel,
                        localTick + (segmentLength * passIndex),
                        control.status,
                        control.data1,
                        control.data2,
                        control.order));
            }
        }

        Collections.sort(notes, NOTE_COMPARATOR);
        for (PlaybackTimeline.CompiledNote note : notes) {
            int noteOrder = (note.sourceTrack * 16) + note.sourceVoice;
            boolean crossesSegmentStart = note.midiStartTick < segmentStartTick && note.midiEndTick > segmentStartTick;

            if (crossesSegmentStart) {
                long localEnd = Math.min(note.midiEndTick, segmentEndTick) - segmentStartTick;
                if (localEnd <= 0L) {
                    localEnd = 1L;
                }
                for (int passIndex = 0; passIndex < repeatCount; passIndex++) {
                    long passOffset = segmentLength * passIndex;
                    events.add(TrackMessageEvent.noteOff(
                            note.midiChannel,
                            passOffset + localEnd,
                            note.midiNote,
                            noteOrder));
                    events.add(TrackMessageEvent.noteOn(
                            note.midiChannel,
                            passOffset,
                            note.midiNote,
                            note.velocity,
                            noteOrder));
                }
            }

            if (crossesSegmentStart) {
                continue;
            }
            if (note.midiEndTick <= segmentStartTick || note.midiStartTick >= segmentEndTick) {
                continue;
            }

            long localStart = Math.max(note.midiStartTick, segmentStartTick) - segmentStartTick;
            long localEnd = Math.min(note.midiEndTick, segmentEndTick) - segmentStartTick;
            if (localEnd <= localStart) {
                localEnd = localStart + 1L;
            }
            for (int passIndex = 0; passIndex < repeatCount; passIndex++) {
                long passOffset = segmentLength * passIndex;
                events.add(TrackMessageEvent.noteOff(
                        note.midiChannel,
                        passOffset + localEnd,
                        note.midiNote,
                        noteOrder));
                events.add(TrackMessageEvent.noteOn(
                        note.midiChannel,
                        passOffset + localStart,
                        note.midiNote,
                        note.velocity,
                        noteOrder));
            }
        }

        writeTrackEvents(tracks, events);
    }

    private void writeTrackEvents(List<TrackBuffer> tracks, List<TrackMessageEvent> events) {
        Collections.sort(events, TRACK_MESSAGE_COMPARATOR);
        for (TrackMessageEvent event : events) {
            trackForChannel(tracks, event.midiChannel).writeShortMessage(
                    event.status,
                    event.midiChannel,
                    event.data1,
                    event.data2,
                    event.tick);
        }
    }

    private TrackBuffer trackForChannel(List<TrackBuffer> tracks, int midiChannel) {
        return tracks.get(CHANNEL_TRACK_OFFSET + midiChannel);
    }

    private byte[] buildSmf(List<TrackBuffer> tracks) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        writeAscii(output, "MThd");
        writeBe32(output, 6);
        writeBe16(output, FORMAT_TYPE_1);
        writeBe16(output, tracks.size());
        writeBe16(output, PlaybackTimeline.MIDI_PPQ);
        for (TrackBuffer track : tracks) {
            byte[] body = track.toByteArray();
            writeAscii(output, "MTrk");
            writeBe32(output, body.length);
            output.write(body, 0, body.length);
        }
        return output.toByteArray();
    }

    private static void writeAscii(ByteArrayOutputStream output, String value) {
        byte[] data = value.getBytes(StandardCharsets.US_ASCII);
        output.write(data, 0, data.length);
    }

    private static void writeBe16(ByteArrayOutputStream output, long value) {
        output.write((int) ((value >>> 8) & 0xFF));
        output.write((int) (value & 0xFF));
    }

    private static void writeBe32(ByteArrayOutputStream output, long value) {
        output.write((int) ((value >>> 24) & 0xFF));
        output.write((int) ((value >>> 16) & 0xFF));
        output.write((int) ((value >>> 8) & 0xFF));
        output.write((int) (value & 0xFF));
    }

    private static void writeVarLen(ByteArrayOutputStream output, long value) {
        long buffer = value & 0x7F;

        while ((value >>>= 7) != 0L) {
            buffer <<= 8;
            buffer |= ((value & 0x7F) | 0x80);
        }

        while (true) {
            output.write((int) (buffer & 0xFF));
            if ((buffer & 0x80) == 0L) {
                return;
            }
            buffer >>>= 8;
        }
    }

    private static String channelName(int midiChannel) {
        return "MLD Channel " + midiChannel;
    }

    private static String controlKey(PlaybackTimeline.MappedControlEvent control) {
        if (control.status == MidiMessageConstants.CONTROL_CHANGE) {
            return control.midiChannel + ":cc:" + control.data1;
        }
        if (control.status == MidiMessageConstants.PROGRAM_CHANGE) {
            return control.midiChannel + ":program";
        }
        if (control.status == MidiMessageConstants.PITCH_BEND) {
            return control.midiChannel + ":pitch";
        }
        return control.midiChannel + ":" + control.status;
    }

    private static int orderForControl(PlaybackTimeline.MappedControlEvent control) {
        if (control.status == MidiMessageConstants.CONTROL_CHANGE && control.data1 == 0) {
            return 0;
        }
        if (control.status == MidiMessageConstants.CONTROL_CHANGE && control.data1 == 32) {
            return 1;
        }
        if (control.status == MidiMessageConstants.PROGRAM_CHANGE) {
            return 2;
        }
        if (control.status == MidiMessageConstants.CONTROL_CHANGE && control.data1 == 101) {
            return 3;
        }
        if (control.status == MidiMessageConstants.CONTROL_CHANGE && control.data1 == 100) {
            return 4;
        }
        if (control.status == MidiMessageConstants.CONTROL_CHANGE && control.data1 == 6) {
            return 5;
        }
        if (control.status == MidiMessageConstants.CONTROL_CHANGE && control.data1 == 38) {
            return 6;
        }
        if (control.status == MidiMessageConstants.PITCH_BEND) {
            return 7;
        }
        if (control.status == MidiMessageConstants.CONTROL_CHANGE && control.data1 == 7) {
            return 8;
        }
        if (control.status == MidiMessageConstants.CONTROL_CHANGE && control.data1 == 10) {
            return 9;
        }
        if (control.status == MidiMessageConstants.CONTROL_CHANGE && control.data1 == 1) {
            return 10;
        }
        if (control.status == MidiMessageConstants.CONTROL_CHANGE && control.data1 == 11) {
            return 11;
        }
        return 20;
    }

    private static final Comparator<PlaybackTimeline.CompiledNote> NOTE_COMPARATOR =
            new Comparator<PlaybackTimeline.CompiledNote>() {
                @Override
                public int compare(PlaybackTimeline.CompiledNote left, PlaybackTimeline.CompiledNote right) {
                    int byTick = Long.compare(left.midiStartTick, right.midiStartTick);
                    if (byTick != 0) {
                        return byTick;
                    }
                    int byChannel = Integer.compare(left.midiChannel, right.midiChannel);
                    if (byChannel != 0) {
                        return byChannel;
                    }
                    return Integer.compare(left.midiNote, right.midiNote);
                }
            };

    private static final Comparator<PlaybackTimeline.MappedControlEvent> CONTROL_COMPARATOR =
            new Comparator<PlaybackTimeline.MappedControlEvent>() {
                @Override
                public int compare(PlaybackTimeline.MappedControlEvent left, PlaybackTimeline.MappedControlEvent right) {
                    int byTick = Long.compare(left.midiTick, right.midiTick);
                    if (byTick != 0) {
                        return byTick;
                    }
                    int byChannel = Integer.compare(left.midiChannel, right.midiChannel);
                    if (byChannel != 0) {
                        return byChannel;
                    }
                    int byOrder = Integer.compare(left.order, right.order);
                    if (byOrder != 0) {
                        return byOrder;
                    }
                    return Integer.compare(left.data1, right.data1);
                }
            };

    private static final Comparator<PlaybackTimeline.MappedControlEvent> PRIMED_CONTROL_COMPARATOR =
            new Comparator<PlaybackTimeline.MappedControlEvent>() {
                @Override
                public int compare(PlaybackTimeline.MappedControlEvent left, PlaybackTimeline.MappedControlEvent right) {
                    int byChannel = Integer.compare(left.midiChannel, right.midiChannel);
                    if (byChannel != 0) {
                        return byChannel;
                    }
                    int bySemantic = Integer.compare(orderForControl(left), orderForControl(right));
                    if (bySemantic != 0) {
                        return bySemantic;
                    }
                    return Integer.compare(left.order, right.order);
                }
            };

    private static final Comparator<TrackMessageEvent> TRACK_MESSAGE_COMPARATOR =
            new Comparator<TrackMessageEvent>() {
                @Override
                public int compare(TrackMessageEvent left, TrackMessageEvent right) {
                    int byChannel = Integer.compare(left.midiChannel, right.midiChannel);
                    if (byChannel != 0) {
                        return byChannel;
                    }
                    int byTick = Long.compare(left.tick, right.tick);
                    if (byTick != 0) {
                        return byTick;
                    }
                    int byPhase = Integer.compare(left.phase, right.phase);
                    if (byPhase != 0) {
                        return byPhase;
                    }
                    int byOrder = Integer.compare(left.order, right.order);
                    if (byOrder != 0) {
                        return byOrder;
                    }
                    int byData1 = Integer.compare(left.data1, right.data1);
                    if (byData1 != 0) {
                        return byData1;
                    }
                    return Integer.compare(left.data2, right.data2);
                }
            };

    private static final class TrackMessageEvent {
        private static final int PHASE_NOTE_OFF = 0;
        private static final int PHASE_CONTROL = 1;
        private static final int PHASE_NOTE_ON = 2;

        final int midiChannel;
        final long tick;
        final int phase;
        final int status;
        final int data1;
        final int data2;
        final int order;

        private TrackMessageEvent(int midiChannel, long tick, int phase, int status, int data1, int data2, int order) {
            this.midiChannel = midiChannel;
            this.tick = tick;
            this.phase = phase;
            this.status = status;
            this.data1 = data1;
            this.data2 = data2;
            this.order = order;
        }

        static TrackMessageEvent control(int midiChannel, long tick, int status, int data1, int data2, int order) {
            return new TrackMessageEvent(midiChannel, tick, PHASE_CONTROL, status, data1, data2, order);
        }

        static TrackMessageEvent noteOff(int midiChannel, long tick, int midiNote, int order) {
            return new TrackMessageEvent(midiChannel, tick, PHASE_NOTE_OFF, MidiMessageConstants.NOTE_OFF, midiNote, 0, order);
        }

        static TrackMessageEvent noteOn(int midiChannel, long tick, int midiNote, int velocity, int order) {
            return new TrackMessageEvent(midiChannel, tick, PHASE_NOTE_ON, MidiMessageConstants.NOTE_ON, midiNote, velocity, order);
        }
    }

    private static final class TrackBuffer {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private long lastTick;

        TrackBuffer(String trackName) {
            writeMeta(META_TRACK_NAME, trackName.getBytes(StandardCharsets.UTF_8), 0L);
        }

        byte[] toByteArray() {
            return output.toByteArray();
        }

        void writeTempo(int mpqn, long tick) {
            byte[] data = new byte[] {
                    (byte) ((mpqn >>> 16) & 0xFF),
                    (byte) ((mpqn >>> 8) & 0xFF),
                    (byte) (mpqn & 0xFF)
            };
            writeMeta(META_TEMPO, data, tick);
        }

        void writeEndOfTrack(long tick) {
            writeMeta(META_END_OF_TRACK, new byte[0], tick);
        }

        void writeShortMessage(int command, int channel, int data1, int data2, long tick) {
            writeDelta(tick);
            output.write(MidiMessageConstants.statusByte(command, channel));
            output.write(data1 & 0x7F);
            if (!MidiMessageConstants.usesSingleDataByte(command)) {
                output.write(data2 & 0x7F);
            }
        }

        private void writeMeta(int type, byte[] data, long tick) {
            writeDelta(tick);
            output.write(0xFF);
            output.write(type & 0xFF);
            writeVarLen(output, data.length);
            output.write(data, 0, data.length);
        }

        private void writeDelta(long tick) {
            long delta = Math.max(0L, tick - lastTick);
            writeVarLen(output, delta);
            lastTick = tick;
        }
    }
}
