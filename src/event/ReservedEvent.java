package event;

/** Preserved special-envelope form with no ordinary playback action. */
public final class ReservedEvent extends TrackEvent {
    public final int status;
    public final int command;
    public final byte[] body;
    public final boolean longForm;

    public ReservedEvent(
            int trackIndex,
            int eventIndex,
            int delta,
            int rawTick,
            int status,
            int command,
            byte[] body,
            boolean longForm) {
        super(trackIndex, eventIndex, delta, rawTick);
        this.status = status & 0xFF;
        this.command = command & 0xFF;
        this.body = body.clone();
        this.longForm = longForm;
    }
}
