package event;

/**
 * Preserved non-sounding 0x3F/0xBF control-envelope event.
 *
 * Native MFi5 decoding treats every status whose low six bits are 0x3F as a
 * control envelope. Status 0x7F and 0xFF are the live resource/system forms;
 * 0x3F and 0xBF use the same framing rules but do not enter ordinary note
 * playback.
 */
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
