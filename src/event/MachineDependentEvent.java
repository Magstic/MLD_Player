package event;

public final class MachineDependentEvent extends TrackEvent {
    public MachineDependentEvent(int trackIndex, int eventIndex, int rawTick) {
        super(trackIndex, eventIndex, rawTick);
    }
}
