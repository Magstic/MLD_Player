package mld.semantic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Native DD loop boundary expressed only in raw MLD ticks.
 */
public final class LoopModel {
    public final boolean hasLoop;
    public final int loopSlot;
    public final int repeatCount;
    public final int loopStartRawTick;
    public final int loopEndRawTick;
    public final List<String> warnings;

    public LoopModel(boolean hasLoop, int loopSlot, int repeatCount, int loopStartRawTick, int loopEndRawTick, List<String> warnings) {
        this.hasLoop = hasLoop;
        this.loopSlot = loopSlot;
        this.repeatCount = repeatCount;
        this.loopStartRawTick = loopStartRawTick;
        this.loopEndRawTick = loopEndRawTick;
        this.warnings = Collections.unmodifiableList(new ArrayList<String>(warnings));
    }
}
