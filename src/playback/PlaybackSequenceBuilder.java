package playback;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;

import timeline.PlaybackTimeline;

public final class PlaybackSequenceBuilder {
    public BuiltSequence build(PlaybackTimeline timeline) throws InvalidMidiDataException {
        return buildTransportSequence(timeline);
    }

    public BuiltSequence build(PlaybackTimeline timeline, int requestedLoopCount) throws InvalidMidiDataException {
        if (timeline != null && timeline.loopInfo.hasLoop && requestedLoopCount >= 0) {
            return buildExpandedFiniteLoopSequence(timeline, requestedLoopCount);
        }
        return buildTransportSequence(timeline);
    }

    private BuiltSequence buildTransportSequence(PlaybackTimeline timeline) throws InvalidMidiDataException {
        Sequence sequence = new Sequence(Sequence.PPQ, PlaybackTimeline.MIDI_PPQ);
        Track conductorTrack = sequence.createTrack();
        addTrackName(conductorTrack, "MLD Conductor");

        Track[] channelTracks = new Track[16];
        for (int midiChannel = 0; midiChannel < 16; midiChannel++) {
            channelTracks[midiChannel] = sequence.createTrack();
            addTrackName(channelTracks[midiChannel], "MLD Channel " + midiChannel);
        }

        long contentEndTick = timeline.loopInfo.hasLoop
                ? Math.max(1L, timeline.loopInfo.loopEndMidiTick)
                : Math.max(1L, timeline.totalMidiTicks);

        emitTempoTrack(timeline, conductorTrack, contentEndTick);
        emitChannelEvents(timeline, channelTracks, contentEndTick);

        addEndOfTrack(conductorTrack, contentEndTick + 1L);
        for (Track track : channelTracks) {
            addEndOfTrack(track, contentEndTick + 1L);
        }

        return new BuiltSequence(
                sequence,
                timeline.loopInfo.hasLoop,
                false,
                timeline.loopInfo.loopStartMidiTick,
                timeline.loopInfo.loopEndMidiTick,
                Math.max(1L, timeline.loopInfo.loopEndMidiTick - timeline.loopInfo.loopStartMidiTick),
                -1,
                contentEndTick);
    }

    private BuiltSequence buildExpandedFiniteLoopSequence(PlaybackTimeline timeline, int requestedLoopCount)
            throws InvalidMidiDataException {
        Sequence sequence = new Sequence(Sequence.PPQ, PlaybackTimeline.MIDI_PPQ);
        Track conductorTrack = sequence.createTrack();
        addTrackName(conductorTrack, "MLD Conductor");

        Track[] channelTracks = new Track[16];
        for (int midiChannel = 0; midiChannel < 16; midiChannel++) {
            channelTracks[midiChannel] = sequence.createTrack();
            addTrackName(channelTracks[midiChannel], "MLD Channel " + midiChannel);
        }

        long loopStartTick = Math.max(0L, timeline.loopInfo.loopStartMidiTick);
        long loopEndTick = Math.max(loopStartTick + 1L, timeline.loopInfo.loopEndMidiTick);
        long loopBodyTickLength = Math.max(1L, loopEndTick - loopStartTick);
        int loopPassCount = Math.max(1, requestedLoopCount + 1);

        emitExpandedTempoTrack(timeline, conductorTrack, loopStartTick, loopEndTick, loopBodyTickLength, loopPassCount);
        long contentEndTick = emitExpandedChannelEvents(
                timeline,
                channelTracks,
                loopStartTick,
                loopEndTick,
                loopBodyTickLength,
                loopPassCount);
        contentEndTick = Math.max(contentEndTick, loopStartTick + (loopBodyTickLength * loopPassCount));
        contentEndTick = Math.max(1L, contentEndTick);

        addEndOfTrack(conductorTrack, contentEndTick + 1L);
        for (Track track : channelTracks) {
            addEndOfTrack(track, contentEndTick + 1L);
        }

        return new BuiltSequence(
                sequence,
                true,
                true,
                loopStartTick,
                loopEndTick,
                loopBodyTickLength,
                loopPassCount,
                contentEndTick);
    }

    private void emitTempoTrack(PlaybackTimeline timeline, Track conductorTrack, long contentEndTick)
            throws InvalidMidiDataException {
        PlaybackTimeline.TempoPoint active = timeline.tempoPoints.get(0);
        addTempoMeta(conductorTrack, active.mpqn, 0L);
        for (PlaybackTimeline.TempoPoint point : timeline.tempoPoints) {
            if (point.midiTick <= 0L || point.midiTick > contentEndTick) {
                continue;
            }
            addTempoMeta(conductorTrack, point.mpqn, point.midiTick);
        }
    }

    private void emitChannelEvents(PlaybackTimeline timeline, Track[] channelTracks, long contentEndTick)
            throws InvalidMidiDataException {
        List<TrackMessageEvent> events = new ArrayList<TrackMessageEvent>();

        List<PlaybackTimeline.MappedControlEvent> controls =
                new ArrayList<PlaybackTimeline.MappedControlEvent>(timeline.mappedControls);
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

        List<PlaybackTimeline.CompiledNote> notes = new ArrayList<PlaybackTimeline.CompiledNote>(timeline.notes);
        Collections.sort(notes, NOTE_COMPARATOR);
        for (PlaybackTimeline.CompiledNote note : notes) {
            if (note.midiStartTick > contentEndTick) {
                continue;
            }
            long noteOffTick = Math.min(note.midiEndTick, contentEndTick);
            int noteOrder = (note.sourceTrack * 16) + note.sourceVoice;
            events.add(TrackMessageEvent.noteOff(
                    note.midiChannel,
                    noteOffTick,
                    note.midiNote,
                    noteOrder));
            events.add(TrackMessageEvent.noteOn(
                    note.midiChannel,
                    note.midiStartTick,
                    note.midiNote,
                    note.velocity,
                    noteOrder));
        }

        Collections.sort(events, TRACK_MESSAGE_COMPARATOR);
        for (TrackMessageEvent event : events) {
            addShortMessage(
                    channelTracks[event.midiChannel],
                    event.status,
                    event.midiChannel,
                    event.data1,
                    event.data2,
                    event.tick);
        }
    }

    private void emitExpandedTempoTrack(
            PlaybackTimeline timeline,
            Track conductorTrack,
            long loopStartTick,
            long loopEndTick,
            long loopBodyTickLength,
            int loopPassCount)
            throws InvalidMidiDataException {
        PlaybackTimeline.TempoPoint active = timeline.tempoPoints.get(0);
        addTempoMeta(conductorTrack, active.mpqn, 0L);
        for (PlaybackTimeline.TempoPoint point : timeline.tempoPoints) {
            if (point.midiTick <= 0L) {
                continue;
            }
            if (point.midiTick < loopStartTick) {
                addTempoMeta(conductorTrack, point.mpqn, point.midiTick);
                continue;
            }
            if (point.midiTick >= loopEndTick) {
                continue;
            }
            for (int passIndex = 0; passIndex < loopPassCount; passIndex++) {
                long shiftedTick = point.midiTick + (loopBodyTickLength * passIndex);
                addTempoMeta(conductorTrack, point.mpqn, shiftedTick);
            }
        }
    }

    private long emitExpandedChannelEvents(
            PlaybackTimeline timeline,
            Track[] channelTracks,
            long loopStartTick,
            long loopEndTick,
            long loopBodyTickLength,
            int loopPassCount)
            throws InvalidMidiDataException {
        List<TrackMessageEvent> events = new ArrayList<TrackMessageEvent>();
        long maxTick = loopEndTick;

        List<PlaybackTimeline.MappedControlEvent> controls =
                new ArrayList<PlaybackTimeline.MappedControlEvent>(timeline.mappedControls);
        Collections.sort(controls, CONTROL_COMPARATOR);
        for (PlaybackTimeline.MappedControlEvent control : controls) {
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
            for (int passIndex = 0; passIndex < loopPassCount; passIndex++) {
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

        List<PlaybackTimeline.CompiledNote> notes = new ArrayList<PlaybackTimeline.CompiledNote>(timeline.notes);
        Collections.sort(notes, NOTE_COMPARATOR);
        for (PlaybackTimeline.CompiledNote note : notes) {
            int noteOrder = (note.sourceTrack * 16) + note.sourceVoice;
            if (note.midiStartTick < loopStartTick) {
                events.add(TrackMessageEvent.noteOff(
                        note.midiChannel,
                        note.midiEndTick,
                        note.midiNote,
                        noteOrder));
                events.add(TrackMessageEvent.noteOn(
                        note.midiChannel,
                        note.midiStartTick,
                        note.midiNote,
                        note.velocity,
                        noteOrder));
                maxTick = Math.max(maxTick, note.midiEndTick);
                continue;
            }
            if (note.midiStartTick >= loopEndTick) {
                continue;
            }
            for (int passIndex = 0; passIndex < loopPassCount; passIndex++) {
                long shiftedStartTick = note.midiStartTick + (loopBodyTickLength * passIndex);
                long shiftedEndTick = note.midiEndTick + (loopBodyTickLength * passIndex);
                events.add(TrackMessageEvent.noteOff(
                        note.midiChannel,
                        shiftedEndTick,
                        note.midiNote,
                        noteOrder));
                events.add(TrackMessageEvent.noteOn(
                        note.midiChannel,
                        shiftedStartTick,
                        note.midiNote,
                        note.velocity,
                        noteOrder));
                maxTick = Math.max(maxTick, shiftedEndTick);
            }
        }

        Collections.sort(events, TRACK_MESSAGE_COMPARATOR);
        for (TrackMessageEvent event : events) {
            addShortMessage(
                    channelTracks[event.midiChannel],
                    event.status,
                    event.midiChannel,
                    event.data1,
                    event.data2,
                    event.tick);
        }
        return maxTick;
    }

    private static void addTrackName(Track track, String name) throws InvalidMidiDataException {
        MetaMessage metaMessage = new MetaMessage();
        byte[] data = name.getBytes();
        metaMessage.setMessage(0x03, data, data.length);
        track.add(new MidiEvent(metaMessage, 0L));
    }

    private static void addTempoMeta(Track track, int mpqn, long tick) throws InvalidMidiDataException {
        byte[] data = new byte[] {
                (byte) ((mpqn >>> 16) & 0xFF),
                (byte) ((mpqn >>> 8) & 0xFF),
                (byte) (mpqn & 0xFF)
        };
        MetaMessage metaMessage = new MetaMessage();
        metaMessage.setMessage(0x51, data, data.length);
        track.add(new MidiEvent(metaMessage, tick));
    }

    private static void addEndOfTrack(Track track, long tick) throws InvalidMidiDataException {
        MetaMessage metaMessage = new MetaMessage();
        metaMessage.setMessage(0x2F, new byte[0], 0);
        track.add(new MidiEvent(metaMessage, tick));
    }

    private static void addShortMessage(Track track, int command, int channel, int data1, int data2, long tick)
            throws InvalidMidiDataException {
        ShortMessage shortMessage = new ShortMessage();
        shortMessage.setMessage(command, channel, data1, data2);
        track.add(new MidiEvent(shortMessage, tick));
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
            return new TrackMessageEvent(midiChannel, tick, PHASE_NOTE_OFF, ShortMessage.NOTE_OFF, midiNote, 0, order);
        }

        static TrackMessageEvent noteOn(int midiChannel, long tick, int midiNote, int velocity, int order) {
            return new TrackMessageEvent(midiChannel, tick, PHASE_NOTE_ON, ShortMessage.NOTE_ON, midiNote, velocity, order);
        }
    }

    public static final class BuiltSequence {
        public final Sequence sequence;
        public final boolean hasLoop;
        public final boolean materializedLoopPasses;
        public final long loopStartTick;
        public final long loopEndTick;
        public final long loopBodyTickLength;
        public final int totalLoopPasses;
        public final long contentEndTick;

        BuiltSequence(
                Sequence sequence,
                boolean hasLoop,
                boolean materializedLoopPasses,
                long loopStartTick,
                long loopEndTick,
                long loopBodyTickLength,
                int totalLoopPasses,
                long contentEndTick) {
            this.sequence = sequence;
            this.hasLoop = hasLoop;
            this.materializedLoopPasses = materializedLoopPasses;
            this.loopStartTick = loopStartTick;
            this.loopEndTick = loopEndTick;
            this.loopBodyTickLength = loopBodyTickLength;
            this.totalLoopPasses = totalLoopPasses;
            this.contentEndTick = contentEndTick;
        }
    }
}
