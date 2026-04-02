package event;

import java.util.Arrays;

public final class ResourceEvent extends TrackEvent {
    public final int command;
    public final String name;
    public final byte[] body;
    public final boolean longForm;

    public ResourceEvent(
            int trackIndex,
            int eventIndex,
            int delta,
            int rawTick,
            int command,
            String name,
            byte[] body,
            boolean longForm) {
        super(trackIndex, eventIndex, delta, rawTick);
        this.command = command;
        this.name = name;
        this.body = Arrays.copyOf(body, body.length);
        this.longForm = longForm;
    }
}
