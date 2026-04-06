package event;

public final class NoteEvent extends TrackEvent {
    public final int status;
    public final int voice;
    public final int pitch;
    public final int gate;
    public final int velocity;
    public final int octaveShift;
    public final int noteExtraBytes;

    /** Convenience: true when the note chunk declared at least one extra byte. */
    public boolean hasExtraByte() { return noteExtraBytes > 0; }

    public NoteEvent(
            int trackIndex,
            int eventIndex,
            int delta,
            int rawTick,
            int status,
            int voice,
            int pitch,
            int gate,
            int velocity,
            int octaveShift,
            int noteExtraBytes) {
        super(trackIndex, eventIndex, delta, rawTick);
        this.status = status;
        this.voice = voice;
        this.pitch = pitch;
        this.gate = gate;
        this.velocity = velocity;
        this.octaveShift = octaveShift;
        this.noteExtraBytes = noteExtraBytes;
    }
}
