package event;

import java.util.Vector;

public final class TrackDecodeResult {
    public final int totalRawTicks;
    public final Vector events;

    public TrackDecodeResult(int totalRawTicks, Vector events) {
        this.totalRawTicks = totalRawTicks;
        this.events = copyVector(events);
    }

    private static Vector copyVector(Vector source) {
        Vector copy = new Vector();
        int i;
        for (i = 0; i < source.size(); i++) {
            copy.addElement(source.elementAt(i));
        }
        return copy;
    }
}
