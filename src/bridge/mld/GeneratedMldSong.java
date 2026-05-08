package bridge.mld;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class GeneratedMldSong {
    public final String title;
    public final String copyright;
    public final int timebase;
    public final int trackCount;
    public final int noteCount;
    public final int controlCount;
    public final int tempoCount;
    public final List<GeneratedMldTrack> tracks;
    public final List<String> warnings;

    public GeneratedMldSong(
            String title,
            String copyright,
            int timebase,
            int trackCount,
            int noteCount,
            int controlCount,
            int tempoCount,
            List<GeneratedMldTrack> tracks,
            List<String> warnings) {
        this.title = title;
        this.copyright = copyright;
        this.timebase = timebase;
        this.trackCount = trackCount;
        this.noteCount = noteCount;
        this.controlCount = controlCount;
        this.tempoCount = tempoCount;
        this.tracks = Collections.unmodifiableList(new ArrayList<GeneratedMldTrack>(tracks));
        this.warnings = Collections.unmodifiableList(new ArrayList<String>(warnings));
    }

    public static final class GeneratedMldTrack {
        public final int index;
        public final int logicalChannel;
        public final int midiChannel;
        public final boolean controlTrack;
        public final byte[] payload;

        public GeneratedMldTrack(int index, int logicalChannel, int midiChannel, boolean controlTrack, byte[] payload) {
            this.index = index;
            this.logicalChannel = logicalChannel;
            this.midiChannel = midiChannel;
            this.controlTrack = controlTrack;
            this.payload = payload.clone();
        }
    }
}
