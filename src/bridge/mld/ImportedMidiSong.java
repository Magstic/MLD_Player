package bridge.mld;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ImportedMidiSong {
    public final Path inputPath;
    public final int inputPpq;
    public final String title;
    public final String copyright;
    public final List<TempoPoint> tempos;
    public final List<CuePoint> cuePoints;
    public final List<ImportedNote> notes;
    public final List<ChannelEvent> channelEvents;
    public final long lastTick;
    public final List<String> warnings;

    public ImportedMidiSong(
            Path inputPath,
            int inputPpq,
            String title,
            String copyright,
            List<TempoPoint> tempos,
            List<CuePoint> cuePoints,
            List<ImportedNote> notes,
            List<ChannelEvent> channelEvents,
            long lastTick,
            List<String> warnings) {
        this.inputPath = inputPath;
        this.inputPpq = inputPpq;
        this.title = title;
        this.copyright = copyright;
        this.tempos = Collections.unmodifiableList(new ArrayList<TempoPoint>(tempos));
        this.cuePoints = Collections.unmodifiableList(new ArrayList<CuePoint>(cuePoints));
        this.notes = Collections.unmodifiableList(new ArrayList<ImportedNote>(notes));
        this.channelEvents = Collections.unmodifiableList(new ArrayList<ChannelEvent>(channelEvents));
        this.lastTick = lastTick;
        this.warnings = Collections.unmodifiableList(new ArrayList<String>(warnings));
    }

    public static final class TempoPoint {
        public final long tick;
        public final int mpqn;
        public final int order;

        public TempoPoint(long tick, int mpqn, int order) {
            this.tick = tick;
            this.mpqn = mpqn;
            this.order = order;
        }
    }

    public static final class CuePoint {
        public final long tick;
        public final String text;
        public final int order;

        public CuePoint(long tick, String text, int order) {
            this.tick = tick;
            this.text = text;
            this.order = order;
        }
    }

    public static final class ImportedNote {
        public final int channel;
        public final int midiNote;
        public final int velocity;
        public final long startTick;
        public final long endTick;
        public final int order;

        public ImportedNote(
                int channel,
                int midiNote,
                int velocity,
                long startTick,
                long endTick,
                int order) {
            this.channel = channel;
            this.midiNote = midiNote;
            this.velocity = velocity;
            this.startTick = startTick;
            this.endTick = endTick;
            this.order = order;
        }
    }

    public static final class ChannelEvent {
        public static final int TYPE_PROGRAM = 1;
        public static final int TYPE_BANK = 2;
        public static final int TYPE_VOLUME = 3;
        public static final int TYPE_EXPRESSION = 4;
        public static final int TYPE_PAN = 5;
        public static final int TYPE_MODULATION = 6;
        public static final int TYPE_PITCH_RANGE = 7;
        public static final int TYPE_PITCH_BEND = 8;

        public final int channel;
        public final int type;
        public final long tick;
        public final int value;
        public final int order;

        public ChannelEvent(int channel, int type, long tick, int value, int order) {
            this.channel = channel;
            this.type = type;
            this.tick = tick;
            this.value = value;
            this.order = order;
        }
    }
}
