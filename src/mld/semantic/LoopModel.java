package mld.semantic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Native DD loop execution summary after finite parser rewinds have been materialized. */
public final class LoopModel {
    public final List<Activation> activations;
    public final List<Rewind> rewinds;
    public final int finiteRewindCount;
    public final InfiniteRegion infiniteRegion;
    public final int sourceEndRawTick;
    public final List<String> warnings;

    LoopModel(
            List<Activation> activations,
            List<Rewind> rewinds,
            int finiteRewindCount,
            InfiniteRegion infiniteRegion,
            int sourceEndRawTick,
            List<String> warnings) {
        this.activations = Collections.unmodifiableList(new ArrayList<Activation>(activations));
        this.rewinds = Collections.unmodifiableList(new ArrayList<Rewind>(rewinds));
        this.finiteRewindCount = Math.max(0, finiteRewindCount);
        this.infiniteRegion = infiniteRegion;
        this.sourceEndRawTick = Math.max(0, sourceEndRawTick);
        this.warnings = Collections.unmodifiableList(new ArrayList<String>(warnings));
    }

    public boolean hasNativeLoops() {
        return !activations.isEmpty();
    }

    public boolean hasInfiniteLoop() {
        return infiniteRegion != null;
    }

    /**
     * Maps one monotonically increasing executed raw tick back onto the original
     * MLD source playhead. Each native DD rewind changes only this presentation
     * offset; semantic execution itself remains on the materialized timeline.
     */
    public int sourceRawTickAt(int executedRawTick) {
        int tick = Math.max(0, executedRawTick);
        long offset = 0L;
        for (Rewind rewind : rewinds) {
            if (rewind.executedRawTick > tick) break;
            offset = (long) rewind.sourceRawTick - rewind.executedRawTick;
        }
        long mapped = tick + offset;
        if (mapped <= 0L) return 0;
        if (sourceEndRawTick > 0 && mapped >= sourceEndRawTick) return sourceEndRawTick;
        return mapped > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) mapped;
    }

    /** One first-end activation of a DD slot. Finite count is additional repeats; -1 is infinite. */
    public static final class Activation {
        public final int slot;
        public final int repeatCount;
        public final int startRawTick;
        public final int firstEndRawTick;
        public final int sourceStartRawTick;
        public final int sourceEndRawTick;

        Activation(
                int slot,
                int repeatCount,
                int startRawTick,
                int firstEndRawTick,
                int sourceStartRawTick,
                int sourceEndRawTick) {
            this.slot = slot;
            this.repeatCount = repeatCount;
            this.startRawTick = startRawTick;
            this.firstEndRawTick = firstEndRawTick;
            this.sourceStartRawTick = sourceStartRawTick;
            this.sourceEndRawTick = sourceEndRawTick;
        }
    }

    /** One actual native parser rewind on the materialized execution timeline. */
    public static final class Rewind {
        public final int slot;
        public final int executedRawTick;
        public final int sourceRawTick;

        Rewind(int slot, int executedRawTick, int sourceRawTick) {
            this.slot = slot;
            this.executedRawTick = executedRawTick;
            this.sourceRawTick = sourceRawTick;
        }
    }

    /**
     * Transport cycle for native infinite execution. When the first infinite pass
     * is proven event-equivalent to the residual scheduler period, these bounds
     * point at that first pass so playback does not expose a duplicate probe pass.
     */
    public static final class InfiniteRegion {
        public final int slot;
        public final int loopStartRawTick;
        public final int loopEndRawTick;
        /** Executed bounds whose recurring parser state supplies the native source-event cycle. */
        public final int parserCycleStartRawTick;
        public final int parserCycleEndRawTick;
        public final int sourceStartRawTick;
        public final int sourceEndRawTick;

        InfiniteRegion(
                int slot,
                int loopStartRawTick,
                int loopEndRawTick,
                int parserCycleStartRawTick,
                int parserCycleEndRawTick,
                int sourceStartRawTick,
                int sourceEndRawTick) {
            this.slot = slot;
            this.loopStartRawTick = loopStartRawTick;
            this.loopEndRawTick = loopEndRawTick;
            this.parserCycleStartRawTick = parserCycleStartRawTick;
            this.parserCycleEndRawTick = parserCycleEndRawTick;
            this.sourceStartRawTick = sourceStartRawTick;
            this.sourceEndRawTick = sourceEndRawTick;
        }
    }
}
