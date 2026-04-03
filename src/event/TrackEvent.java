package event;

public abstract class TrackEvent {
    public final int trackIndex;
    public final int eventIndex;
    public final int rawTick;

    protected TrackEvent(int trackIndex, int eventIndex, int rawTick) {
        this.trackIndex = trackIndex;
        this.eventIndex = eventIndex;
        this.rawTick = rawTick;
    }
}
