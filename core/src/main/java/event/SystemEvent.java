package event;

public final class SystemEvent extends TrackEvent {
    public final int command;
    public final int value;
    public final String name;
    public final int part;
    public final int timebase;

    public SystemEvent(
            int trackIndex,
            int eventIndex,
            int delta,
            int rawTick,
            int command,
            int value,
            String name,
            int part,
            int timebase) {
        super(trackIndex, eventIndex, delta, rawTick);
        this.command = command;
        this.value = value;
        this.name = name;
        this.part = part;
        this.timebase = timebase;
    }
}
