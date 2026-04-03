package event;

public final class ResourceEvent extends TrackEvent {
    public ResourceEvent(int trackIndex, int eventIndex, int rawTick) {
        super(trackIndex, eventIndex, rawTick);
    }
}
