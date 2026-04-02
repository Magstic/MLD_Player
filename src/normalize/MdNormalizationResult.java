package normalize;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class MdNormalizationResult {
    public final List<MdNormalizedEvent> normalizedEvents;
    public final List<MdNormalizedEvent> unknownEvents;
    public final List<String> warnings;

    public MdNormalizationResult(
            List<MdNormalizedEvent> normalizedEvents,
            List<MdNormalizedEvent> unknownEvents,
            List<String> warnings) {
        this.normalizedEvents = Collections.unmodifiableList(new ArrayList<MdNormalizedEvent>(normalizedEvents));
        this.unknownEvents = Collections.unmodifiableList(new ArrayList<MdNormalizedEvent>(unknownEvents));
        this.warnings = Collections.unmodifiableList(new ArrayList<String>(warnings));
    }
}
