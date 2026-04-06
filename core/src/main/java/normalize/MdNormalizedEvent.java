package normalize;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Arrays;

public final class MdNormalizedEvent {
    public final int trackIndex;
    public final int eventIndex;
    public final int rawTick;
    public final int prefix;
    public final int selector;
    public final String family;
    public final String confidence;
    public final boolean known;
    public final String rawHex;
    public final byte[] rawBytes;
    public final Map<String, Object> details;

    public MdNormalizedEvent(
            int trackIndex,
            int eventIndex,
            int rawTick,
            int prefix,
            int selector,
            String family,
            String confidence,
            boolean known,
            String rawHex,
            byte[] rawBytes,
            Map<String, Object> details) {
        this.trackIndex = trackIndex;
        this.eventIndex = eventIndex;
        this.rawTick = rawTick;
        this.prefix = prefix;
        this.selector = selector;
        this.family = family;
        this.confidence = confidence;
        this.known = known;
        this.rawHex = rawHex;
        this.rawBytes = Arrays.copyOf(rawBytes, rawBytes.length);
        this.details = Collections.unmodifiableMap(new LinkedHashMap<String, Object>(details));
    }
}
