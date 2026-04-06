package event;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class TrackDecodeResult {
    public final int trackIndex;
    public final int rawLength;
    public final int totalRawTicks;
    public final int noteCount;
    public final int resourceCount;
    public final int systemCount;
    public final int machineCount;
    public final List<TrackEvent> events;
    public final List<String> warnings;

    public TrackDecodeResult(
            int trackIndex,
            int rawLength,
            int totalRawTicks,
            int noteCount,
            int resourceCount,
            int systemCount,
            int machineCount,
            List<TrackEvent> events,
            List<String> warnings) {
        this.trackIndex = trackIndex;
        this.rawLength = rawLength;
        this.totalRawTicks = totalRawTicks;
        this.noteCount = noteCount;
        this.resourceCount = resourceCount;
        this.systemCount = systemCount;
        this.machineCount = machineCount;
        this.events = Collections.unmodifiableList(new ArrayList<TrackEvent>(events));
        this.warnings = Collections.unmodifiableList(new ArrayList<String>(warnings));
    }
}
