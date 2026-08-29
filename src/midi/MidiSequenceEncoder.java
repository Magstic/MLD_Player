package midi;

import java.nio.charset.StandardCharsets;
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

/** The single authoritative MidiPlan -> javax.sound.midi.Sequence serializer. */
public final class MidiSequenceEncoder {
    private static final int MIDI_CHANNEL_COUNT = 16;

    public EncodedSequence encode(MidiPlan plan) throws InvalidMidiDataException {
        return encode(plan, TrackNames.playback());
    }

    public EncodedSequence encode(MidiPlan plan, TrackNames trackNames) throws InvalidMidiDataException {
        if (plan == null) {
            throw new IllegalArgumentException("MIDI plan is required.");
        }
        if (plan.tempoPoints.isEmpty()) {
            throw new IllegalArgumentException("MIDI plan requires at least one tempo point.");
        }
        TrackNames names = trackNames == null ? TrackNames.playback() : trackNames;

        Sequence sequence = new Sequence(Sequence.PPQ, MidiPlan.MIDI_PPQ);
        Track conductorTrack = sequence.createTrack();
        addTrackName(conductorTrack, names.conductorName);

        Track[] channelTracks = new Track[MIDI_CHANNEL_COUNT];
        for (int midiChannel = 0; midiChannel < MIDI_CHANNEL_COUNT; midiChannel++) {
            channelTracks[midiChannel] = sequence.createTrack();
            addTrackName(channelTracks[midiChannel], names.channelName(midiChannel));
        }

        long contentEndTick = contentEndTick(plan);
        emitTempoTrack(plan, conductorTrack, contentEndTick);
        emitChannelEvents(plan, channelTracks, contentEndTick);
        addEndOfTrack(conductorTrack, contentEndTick + 1L);
        for (Track track : channelTracks) {
            addEndOfTrack(track, contentEndTick + 1L);
        }

        return new EncodedSequence(sequence, plan, contentEndTick);
    }

    private static long contentEndTick(MidiPlan plan) {
        if (plan.loopInfo.hasLoop) {
            return Math.max(1L, plan.loopInfo.loopEndMidiTick);
        }
        return Math.max(1L, plan.totalMidiTicks);
    }

    private static void emitTempoTrack(MidiPlan plan, Track conductorTrack, long contentEndTick)
            throws InvalidMidiDataException {
        MidiPlan.TempoPoint active = plan.tempoPoints.get(0);
        addTempoMeta(conductorTrack, active.mpqn, 0L);
        for (MidiPlan.TempoPoint point : plan.tempoPoints) {
            if (point.midiTick <= 0L || point.midiTick > contentEndTick) {
                continue;
            }
            addTempoMeta(conductorTrack, point.mpqn, point.midiTick);
        }
    }

    private static void emitChannelEvents(MidiPlan plan, Track[] channelTracks, long contentEndTick)
            throws InvalidMidiDataException {
        List<TrackMessageEvent> events = new ArrayList<TrackMessageEvent>();
        for (MidiPlan.MappedControlEvent control : plan.mappedControls) {
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

        for (MidiPlan.CompiledNote note : plan.notes) {
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

    private static void addTrackName(Track track, String name) throws InvalidMidiDataException {
        MetaMessage message = new MetaMessage();
        byte[] data = name.getBytes(StandardCharsets.UTF_8);
        message.setMessage(0x03, data, data.length);
        track.add(new MidiEvent(message, 0L));
    }

    private static void addTempoMeta(Track track, int mpqn, long tick) throws InvalidMidiDataException {
        byte[] data = new byte[] {
                (byte) ((mpqn >>> 16) & 0xFF),
                (byte) ((mpqn >>> 8) & 0xFF),
                (byte) (mpqn & 0xFF)
        };
        MetaMessage message = new MetaMessage();
        message.setMessage(0x51, data, data.length);
        track.add(new MidiEvent(message, tick));
    }

    private static void addEndOfTrack(Track track, long tick) throws InvalidMidiDataException {
        MetaMessage message = new MetaMessage();
        message.setMessage(0x2F, new byte[0], 0);
        track.add(new MidiEvent(message, tick));
    }

    private static void addShortMessage(
            Track track,
            int command,
            int channel,
            int data1,
            int data2,
            long tick) throws InvalidMidiDataException {
        ShortMessage message = new ShortMessage();
        message.setMessage(command, channel, data1, data2);
        track.add(new MidiEvent(message, tick));
    }

    private static final Comparator<TrackMessageEvent> TRACK_MESSAGE_COMPARATOR =
            new Comparator<TrackMessageEvent>() {
                @Override
                public int compare(TrackMessageEvent left, TrackMessageEvent right) {
                    int byChannel = Integer.compare(left.midiChannel, right.midiChannel);
                    if (byChannel != 0) return byChannel;
                    int byTick = Long.compare(left.tick, right.tick);
                    if (byTick != 0) return byTick;
                    int byPhase = Integer.compare(left.phase, right.phase);
                    if (byPhase != 0) return byPhase;
                    int byOrder = Integer.compare(left.order, right.order);
                    if (byOrder != 0) return byOrder;
                    int byData1 = Integer.compare(left.data1, right.data1);
                    if (byData1 != 0) return byData1;
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

        private TrackMessageEvent(
                int midiChannel,
                long tick,
                int phase,
                int status,
                int data1,
                int data2,
                int order) {
            this.midiChannel = midiChannel;
            this.tick = tick;
            this.phase = phase;
            this.status = status;
            this.data1 = data1;
            this.data2 = data2;
            this.order = order;
        }

        static TrackMessageEvent control(
                int midiChannel,
                long tick,
                int status,
                int data1,
                int data2,
                int order) {
            return new TrackMessageEvent(midiChannel, tick, PHASE_CONTROL, status, data1, data2, order);
        }

        static TrackMessageEvent noteOff(int midiChannel, long tick, int midiNote, int order) {
            return new TrackMessageEvent(
                    midiChannel,
                    tick,
                    PHASE_NOTE_OFF,
                    ShortMessage.NOTE_OFF,
                    midiNote,
                    0,
                    order);
        }

        static TrackMessageEvent noteOn(int midiChannel, long tick, int midiNote, int velocity, int order) {
            return new TrackMessageEvent(
                    midiChannel,
                    tick,
                    PHASE_NOTE_ON,
                    ShortMessage.NOTE_ON,
                    midiNote,
                    velocity,
                    order);
        }
    }

    public static final class TrackNames {
        private final String conductorName;
        private final String channelPrefix;

        private TrackNames(String conductorName, String channelPrefix) {
            this.conductorName = conductorName;
            this.channelPrefix = channelPrefix;
        }

        public static TrackNames playback() {
            return new TrackNames("MLD Conductor", "MLD Channel ");
        }

        public static TrackNames exportSegment(String segmentName) {
            String safeName = segmentName == null ? "MLD" : segmentName;
            return new TrackNames(safeName + " Conductor", "MLD MIDI Channel ");
        }

        private String channelName(int midiChannel) {
            return channelPrefix + midiChannel;
        }
    }

    public static final class EncodedSequence {
        public final Sequence sequence;
        public final MidiPlan plan;
        public final boolean hasLoop;
        public final long loopStartTick;
        public final long loopEndTick;
        public final long loopBodyTickLength;
        public final long contentEndTick;

        private EncodedSequence(Sequence sequence, MidiPlan plan, long contentEndTick) {
            this.sequence = sequence;
            this.plan = plan;
            this.hasLoop = plan.loopInfo.hasLoop;
            this.loopStartTick = plan.loopInfo.loopStartMidiTick;
            this.loopEndTick = plan.loopInfo.loopEndMidiTick;
            this.loopBodyTickLength = plan.loopInfo.loopBodyTickLength();
            this.contentEndTick = contentEndTick;
        }
    }
}
