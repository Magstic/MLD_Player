package event;

public final class NoteEvent extends TrackEvent {
    public final int voice;
    public final int pitch;
    public final int gate;
    public final int velocity;
    public final int octaveShift;
    public final int noteExtraBytes;

    public NoteEvent(
            int trackIndex,
            int eventIndex,
            int rawTick,
            int voice,
            int pitch,
            int gate,
            int velocity,
            int octaveShift,
            int noteExtraBytes) {
        super(trackIndex, eventIndex, rawTick);
        this.voice = voice;
        this.pitch = pitch;
        this.gate = gate;
        this.velocity = velocity;
        this.octaveShift = octaveShift;
        this.noteExtraBytes = noteExtraBytes;
    }
}
