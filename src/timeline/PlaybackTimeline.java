package timeline;

import java.util.Vector;

import container.MldFile;

public final class PlaybackTimeline {
    public static final int MIDI_PPQ = 1920;

    public final MldFile file;
    public final Vector tempoPoints;
    public final LoopInfo loopInfo;
    public final Vector notes;
    public final Vector mappedControls;
    public final long totalMidiTicks;
    public final Vector warnings;

    public PlaybackTimeline(
            MldFile file,
            Vector tempoPoints,
            LoopInfo loopInfo,
            Vector notes,
            Vector mappedControls,
            long totalMidiTicks,
            Vector warnings) {
        this.file = file;
        this.tempoPoints = copyVector(tempoPoints);
        this.loopInfo = loopInfo;
        this.notes = copyVector(notes);
        this.mappedControls = copyVector(mappedControls);
        this.totalMidiTicks = totalMidiTicks;
        this.warnings = copyVector(warnings);
    }

    private static Vector copyVector(Vector source) {
        Vector copy = new Vector();
        int i;
        for (i = 0; i < source.size(); i++) {
            copy.addElement(source.elementAt(i));
        }
        return copy;
    }

    public static final class TempoPoint {
        public final int rawTick;
        public final long midiTick;
        public final int timebase;
        public final int tempo;
        public final int mpqn;
        public final boolean synthetic;

        public TempoPoint(int rawTick, long midiTick, int timebase, int tempo, int mpqn, boolean synthetic) {
            this.rawTick = rawTick;
            this.midiTick = midiTick;
            this.timebase = timebase;
            this.tempo = tempo;
            this.mpqn = mpqn;
            this.synthetic = synthetic;
        }
    }

    public static final class LoopInfo {
        public final boolean hasLoop;
        public final int loopSlot;
        public final int repeatCount;
        public final int loopStartRawTick;
        public final int loopEndRawTick;
        public final long loopStartMidiTick;
        public final long loopEndMidiTick;
        public final Vector warnings;

        public LoopInfo(
                boolean hasLoop,
                int loopSlot,
                int repeatCount,
                int loopStartRawTick,
                int loopEndRawTick,
                long loopStartMidiTick,
                long loopEndMidiTick,
                Vector warnings) {
            this.hasLoop = hasLoop;
            this.loopSlot = loopSlot;
            this.repeatCount = repeatCount;
            this.loopStartRawTick = loopStartRawTick;
            this.loopEndRawTick = loopEndRawTick;
            this.loopStartMidiTick = loopStartMidiTick;
            this.loopEndMidiTick = loopEndMidiTick;
            this.warnings = copyVector(warnings);
        }
    }

    public static final class CompiledNote {
        public final int sourceTrack;
        public final int sourceVoice;
        public final int midiChannel;
        public final int midiNote;
        public final int velocity;
        public final int rawStartTick;
        public final int rawEndTick;
        public final long midiStartTick;
        public final long midiEndTick;

        public CompiledNote(
                int sourceTrack,
                int sourceVoice,
                int midiChannel,
                int midiNote,
                int velocity,
                int rawStartTick,
                int rawEndTick,
                long midiStartTick,
                long midiEndTick) {
            this.sourceTrack = sourceTrack;
            this.sourceVoice = sourceVoice;
            this.midiChannel = midiChannel;
            this.midiNote = midiNote;
            this.velocity = velocity;
            this.rawStartTick = rawStartTick;
            this.rawEndTick = rawEndTick;
            this.midiStartTick = midiStartTick;
            this.midiEndTick = midiEndTick;
        }
    }

    public static final class MappedControlEvent {
        public final int sourceTrack;
        public final int sourceCommand;
        public final String sourceName;
        public final int midiChannel;
        public final long midiTick;
        public final int status;
        public final int data1;
        public final int data2;
        public final int order;

        public MappedControlEvent(
                int sourceTrack,
                int sourceCommand,
                String sourceName,
                int midiChannel,
                long midiTick,
                int status,
                int data1,
                int data2,
                int order) {
            this.sourceTrack = sourceTrack;
            this.sourceCommand = sourceCommand;
            this.sourceName = sourceName;
            this.midiChannel = midiChannel;
            this.midiTick = midiTick;
            this.status = status;
            this.data1 = data1;
            this.data2 = data2;
            this.order = order;
        }
    }
}
