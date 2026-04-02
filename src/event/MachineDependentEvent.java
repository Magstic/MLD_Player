package event;

import java.util.Arrays;

public final class MachineDependentEvent extends TrackEvent {
    public final int command;
    public final byte[] payload;

    public MachineDependentEvent(
            int trackIndex,
            int eventIndex,
            int delta,
            int rawTick,
            int command,
            byte[] payload) {
        super(trackIndex, eventIndex, delta, rawTick);
        this.command = command;
        this.payload = Arrays.copyOf(payload, payload.length);
    }
}
