package event;

public abstract class TrackEvent {
    public final int trackIndex;
    public final int eventIndex;
    public final int delta;
    public final int rawTick;

    protected TrackEvent(int trackIndex, int eventIndex, int delta, int rawTick) {
        this.trackIndex = trackIndex;
        this.eventIndex = eventIndex;
        this.delta = delta;
        this.rawTick = rawTick;
    }
}
