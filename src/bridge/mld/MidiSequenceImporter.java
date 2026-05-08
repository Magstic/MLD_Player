package bridge.mld;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;

public final class MidiSequenceImporter {
    private static final Charset MIDI_TEXT = StandardCharsets.ISO_8859_1;
    private static final Comparator<MidiEventRef> EVENT_COMPARATOR = new Comparator<MidiEventRef>() {
        @Override
        public int compare(MidiEventRef left, MidiEventRef right) {
            int byTick = Long.compare(left.tick, right.tick);
            if (byTick != 0) {
                return byTick;
            }
            int byTrack = Integer.compare(left.trackIndex, right.trackIndex);
            if (byTrack != 0) {
                return byTrack;
            }
            return Integer.compare(left.eventIndex, right.eventIndex);
        }
    };

    public ImportedMidiSong importSequence(Path inputPath) throws IOException, InvalidMidiDataException {
        Sequence sequence = MidiSystem.getSequence(inputPath.toFile());
        if (sequence.getDivisionType() != Sequence.PPQ) {
            throw new IllegalArgumentException("Only PPQ MIDI files are supported for MLD conversion.");
        }
        if (sequence.getResolution() <= 0) {
            throw new IllegalArgumentException("Invalid MIDI PPQ resolution: " + sequence.getResolution());
        }

        List<MidiEventRef> refs = collectEvents(sequence);
        List<ImportedMidiSong.TempoPoint> tempos = new ArrayList<ImportedMidiSong.TempoPoint>();
        List<ImportedMidiSong.CuePoint> cuePoints = new ArrayList<ImportedMidiSong.CuePoint>();
        List<ImportedMidiSong.ImportedNote> notes = new ArrayList<ImportedMidiSong.ImportedNote>();
        List<ImportedMidiSong.ChannelEvent> channelEvents = new ArrayList<ImportedMidiSong.ChannelEvent>();
        List<String> warnings = new ArrayList<String>();
        Set<String> warningKeys = new HashSet<String>();
        @SuppressWarnings("unchecked")
        Deque<ActiveNote>[] activeNotes = new Deque[16 * 128];
        int[] currentRpnMsb = new int[16];
        int[] currentRpnLsb = new int[16];
        Arrays.fill(currentRpnMsb, 127);
        Arrays.fill(currentRpnLsb, 127);

        String title = null;
        String copyright = null;
        long lastTick = 0L;
        int nextOrder = 0;

        for (MidiEventRef ref : refs) {
            lastTick = Math.max(lastTick, ref.tick);
            MidiMessage message = ref.message;
            if (message instanceof MetaMessage) {
                MetaMessage meta = (MetaMessage) message;
                int type = meta.getType();
                byte[] data = meta.getData();
                if (type == 0x51 && data.length == 3) {
                    int mpqn = ((data[0] & 0xFF) << 16) | ((data[1] & 0xFF) << 8) | (data[2] & 0xFF);
                    tempos.add(new ImportedMidiSong.TempoPoint(ref.tick, mpqn, nextOrder++));
                    continue;
                }
                if (type == 0x03) {
                    String text = decodeMidiText(data);
                    if (text != null && !text.isEmpty() && (title == null || title.isEmpty())) {
                        title = text;
                    }
                    continue;
                }
                if (type == 0x06) {
                    cuePoints.add(new ImportedMidiSong.CuePoint(ref.tick, decodeMidiText(data), nextOrder++));
                    continue;
                }
                if (type == 0x02) {
                    String text = decodeMidiText(data);
                    if (text != null && !text.isEmpty() && (copyright == null || copyright.isEmpty())) {
                        copyright = text;
                    }
                    continue;
                }
                continue;
            }
            if (!(message instanceof ShortMessage)) {
                warnOnce(warnings, warningKeys, "unsupported_message_" + message.getClass().getName(),
                        "Skipping unsupported MIDI message class: " + message.getClass().getSimpleName());
                continue;
            }

            ShortMessage shortMessage = (ShortMessage) message;
            int channel = shortMessage.getChannel();
            int command = shortMessage.getCommand();
            int data1 = shortMessage.getData1();
            int data2 = shortMessage.getData2();

            switch (command) {
                case ShortMessage.NOTE_ON:
                    if (data2 == 0) {
                        closeNote(activeNotes, channel, data1, ref.tick, notes, warnings, warningKeys);
                    } else {
                        openNote(activeNotes, channel, data1, data2, ref.tick, nextOrder++);
                    }
                    break;
                case ShortMessage.NOTE_OFF:
                    closeNote(activeNotes, channel, data1, ref.tick, notes, warnings, warningKeys);
                    break;
                case ShortMessage.PROGRAM_CHANGE:
                    channelEvents.add(new ImportedMidiSong.ChannelEvent(
                            channel,
                            ImportedMidiSong.ChannelEvent.TYPE_PROGRAM,
                            ref.tick,
                            data1 & 0x7F,
                            nextOrder++));
                    break;
                case ShortMessage.CONTROL_CHANGE:
                    handleControlChange(
                            channel,
                            data1,
                            data2,
                            ref.tick,
                            nextOrder++,
                            currentRpnMsb,
                            currentRpnLsb,
                            channelEvents);
                    break;
                case ShortMessage.PITCH_BEND:
                    channelEvents.add(new ImportedMidiSong.ChannelEvent(
                            channel,
                            ImportedMidiSong.ChannelEvent.TYPE_PITCH_BEND,
                            ref.tick,
                            ((data2 & 0x7F) << 7) | (data1 & 0x7F),
                            nextOrder++));
                    break;
                default:
                    warnOnce(
                            warnings,
                            warningKeys,
                            "unsupported_short_" + command,
                            String.format("Skipping unsupported MIDI command 0x%02X.", command));
                    break;
            }
        }

        closeDanglingNotes(activeNotes, Math.max(1L, lastTick + 1L), notes, warnings, warningKeys, nextOrder);
        if (title == null || title.trim().isEmpty()) {
            title = fileStem(inputPath);
        }
        if (copyright == null) {
            copyright = "";
        }

        Collections.sort(tempos, new Comparator<ImportedMidiSong.TempoPoint>() {
            @Override
            public int compare(ImportedMidiSong.TempoPoint left, ImportedMidiSong.TempoPoint right) {
                int byTick = Long.compare(left.tick, right.tick);
                if (byTick != 0) {
                    return byTick;
                }
                return Integer.compare(left.order, right.order);
            }
        });
        Collections.sort(notes, new Comparator<ImportedMidiSong.ImportedNote>() {
            @Override
            public int compare(ImportedMidiSong.ImportedNote left, ImportedMidiSong.ImportedNote right) {
                int byStart = Long.compare(left.startTick, right.startTick);
                if (byStart != 0) {
                    return byStart;
                }
                int byEnd = Long.compare(left.endTick, right.endTick);
                if (byEnd != 0) {
                    return byEnd;
                }
                int byChannel = Integer.compare(left.channel, right.channel);
                if (byChannel != 0) {
                    return byChannel;
                }
                return Integer.compare(left.order, right.order);
            }
        });
        Collections.sort(channelEvents, new Comparator<ImportedMidiSong.ChannelEvent>() {
            @Override
            public int compare(ImportedMidiSong.ChannelEvent left, ImportedMidiSong.ChannelEvent right) {
                int byTick = Long.compare(left.tick, right.tick);
                if (byTick != 0) {
                    return byTick;
                }
                int byChannel = Integer.compare(left.channel, right.channel);
                if (byChannel != 0) {
                    return byChannel;
                }
                return Integer.compare(left.order, right.order);
            }
        });
        Collections.sort(cuePoints, new Comparator<ImportedMidiSong.CuePoint>() {
            @Override
            public int compare(ImportedMidiSong.CuePoint left, ImportedMidiSong.CuePoint right) {
                int byTick = Long.compare(left.tick, right.tick);
                if (byTick != 0) {
                    return byTick;
                }
                return Integer.compare(left.order, right.order);
            }
        });

        return new ImportedMidiSong(
                inputPath,
                sequence.getResolution(),
                title.trim(),
                copyright.trim(),
                tempos,
                cuePoints,
                notes,
                channelEvents,
                lastTick,
                warnings);
    }

    private static List<MidiEventRef> collectEvents(Sequence sequence) {
        List<MidiEventRef> refs = new ArrayList<MidiEventRef>();
        Track[] tracks = sequence.getTracks();
        for (int trackIndex = 0; trackIndex < tracks.length; trackIndex++) {
            Track track = tracks[trackIndex];
            for (int eventIndex = 0; eventIndex < track.size(); eventIndex++) {
                MidiEvent event = track.get(eventIndex);
                refs.add(new MidiEventRef(trackIndex, eventIndex, event.getTick(), event.getMessage()));
            }
        }
        Collections.sort(refs, EVENT_COMPARATOR);
        return refs;
    }

    private static void handleControlChange(
            int channel,
            int controller,
            int value,
            long tick,
            int order,
            int[] currentRpnMsb,
            int[] currentRpnLsb,
            List<ImportedMidiSong.ChannelEvent> channelEvents) {
        switch (controller) {
            case 0:
                channelEvents.add(new ImportedMidiSong.ChannelEvent(
                        channel,
                        ImportedMidiSong.ChannelEvent.TYPE_BANK,
                        tick,
                        value & 0x7F,
                        order));
                return;
            case 1:
                channelEvents.add(new ImportedMidiSong.ChannelEvent(
                        channel,
                        ImportedMidiSong.ChannelEvent.TYPE_MODULATION,
                        tick,
                        value & 0x7F,
                        order));
                return;
            case 7:
                channelEvents.add(new ImportedMidiSong.ChannelEvent(
                        channel,
                        ImportedMidiSong.ChannelEvent.TYPE_VOLUME,
                        tick,
                        value & 0x7F,
                        order));
                return;
            case 10:
                channelEvents.add(new ImportedMidiSong.ChannelEvent(
                        channel,
                        ImportedMidiSong.ChannelEvent.TYPE_PAN,
                        tick,
                        value & 0x7F,
                        order));
                return;
            case 11:
                channelEvents.add(new ImportedMidiSong.ChannelEvent(
                        channel,
                        ImportedMidiSong.ChannelEvent.TYPE_EXPRESSION,
                        tick,
                        value & 0x7F,
                        order));
                return;
            case 100:
                currentRpnLsb[channel] = value & 0x7F;
                return;
            case 101:
                currentRpnMsb[channel] = value & 0x7F;
                return;
            case 6:
                if (currentRpnMsb[channel] == 0 && currentRpnLsb[channel] == 0) {
                    channelEvents.add(new ImportedMidiSong.ChannelEvent(
                            channel,
                            ImportedMidiSong.ChannelEvent.TYPE_PITCH_RANGE,
                            tick,
                            value & 0x7F,
                            order));
                }
                return;
            default:
                return;
        }
    }

    private static void openNote(Deque<ActiveNote>[] activeNotes, int channel, int midiNote, int velocity, long tick, int order) {
        int index = noteIndex(channel, midiNote);
        Deque<ActiveNote> queue = activeNotes[index];
        if (queue == null) {
            queue = new ArrayDeque<ActiveNote>();
            activeNotes[index] = queue;
        }
        queue.addLast(new ActiveNote(channel, midiNote, velocity, tick, order));
    }

    private static void closeNote(
            Deque<ActiveNote>[] activeNotes,
            int channel,
            int midiNote,
            long tick,
            List<ImportedMidiSong.ImportedNote> notes,
            List<String> warnings,
            Set<String> warningKeys) {
        int index = noteIndex(channel, midiNote);
        Deque<ActiveNote> queue = activeNotes[index];
        if (queue == null || queue.isEmpty()) {
            warnOnce(
                    warnings,
                    warningKeys,
                    "orphan_note_off_" + channel + "_" + midiNote,
                    "Encountered NOTE_OFF without a matching NOTE_ON on channel " + channel + ", note " + midiNote + ".");
            return;
        }
        ActiveNote active = queue.removeFirst();
        long endTick = Math.max(active.startTick + 1L, tick);
        notes.add(new ImportedMidiSong.ImportedNote(
                channel,
                midiNote,
                active.velocity,
                active.startTick,
                endTick,
                active.order));
    }

    private static void closeDanglingNotes(
            Deque<ActiveNote>[] activeNotes,
            long fallbackEndTick,
            List<ImportedMidiSong.ImportedNote> notes,
            List<String> warnings,
            Set<String> warningKeys,
            int nextOrder) {
        for (Deque<ActiveNote> queue : activeNotes) {
            if (queue == null || queue.isEmpty()) {
                continue;
            }
            while (!queue.isEmpty()) {
                ActiveNote active = queue.removeFirst();
                warnOnce(
                        warnings,
                        warningKeys,
                        "dangling_note_" + active.channel + "_" + active.midiNote,
                        "Closing dangling NOTE_ON at end of file on channel " + active.channel
                                + ", note " + active.midiNote + ".");
                long endTick = Math.max(active.startTick + 1L, fallbackEndTick);
                notes.add(new ImportedMidiSong.ImportedNote(
                        active.channel,
                        active.midiNote,
                        active.velocity,
                        active.startTick,
                        endTick,
                        active.order > 0 ? active.order : nextOrder++));
            }
        }
    }

    private static int noteIndex(int channel, int midiNote) {
        return (channel * 128) + midiNote;
    }

    private static String decodeMidiText(byte[] data) {
        if (data == null || data.length == 0) {
            return "";
        }
        return new String(data, MIDI_TEXT).trim();
    }

    private static String fileStem(Path inputPath) {
        String name = inputPath.getFileName() != null ? inputPath.getFileName().toString() : "untitled";
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static void warnOnce(List<String> warnings, Set<String> warningKeys, String key, String warning) {
        if (warningKeys.add(key)) {
            warnings.add(warning);
        }
    }

    private static final class MidiEventRef {
        final int trackIndex;
        final int eventIndex;
        final long tick;
        final MidiMessage message;

        MidiEventRef(int trackIndex, int eventIndex, long tick, MidiMessage message) {
            this.trackIndex = trackIndex;
            this.eventIndex = eventIndex;
            this.tick = tick;
            this.message = message;
        }
    }

    private static final class ActiveNote {
        final int channel;
        final int midiNote;
        final int velocity;
        final long startTick;
        final int order;

        ActiveNote(int channel, int midiNote, int velocity, long startTick, int order) {
            this.channel = channel;
            this.midiNote = midiNote;
            this.velocity = velocity;
            this.startTick = startTick;
            this.order = order;
        }
    }
}
