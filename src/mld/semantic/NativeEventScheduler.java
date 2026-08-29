package mld.semantic;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import mld.decode.DecodedTrack;
import mld.decode.MachineDependentEvent;
import mld.decode.NoteEvent;
import mld.decode.ReservedEvent;
import mld.decode.ResourceEvent;
import mld.decode.SystemEvent;
import mld.decode.TrackEvent;

/**
 * Replays the native per-track countdown scheduler and DD parser-bookmark controller.
 * Finite rewinds are materialized into monotonically increasing runtime raw ticks.
 */
final class NativeEventScheduler {
    private NativeEventScheduler() {
    }

    static Execution schedule(List<DecodedTrack> decodedTracks, List<String> warnings) {
        List<DecodedTrack> tracks = new ArrayList<DecodedTrack>(decodedTracks);
        Collections.sort(tracks, TRACK_ORDER);
        Cursor[] cursors = new Cursor[tracks.size()];
        for (int i = 0; i < tracks.size(); i++) {
            DecodedTrack track = tracks.get(i);
            cursors[i] = new Cursor(track.trackIndex, track.events);
        }

        SlotState[] slots = new SlotState[4];
        for (int i = 0; i < slots.length; i++) slots[i] = new SlotState();

        List<TrackEvent> executed = new ArrayList<TrackEvent>();
        List<LoopModel.Activation> activations = new ArrayList<LoopModel.Activation>();
        List<LoopModel.Rewind> rewinds = new ArrayList<LoopModel.Rewind>();
        List<String> loopWarnings = new ArrayList<String>();
        Map<StateKey, StateMark> seen = new LinkedHashMap<StateKey, StateMark>();
        long currentTick = 0L;
        int finiteRewinds = 0;
        int sourceEndRawTick = sourceEndRawTick(tracks);
        LoopModel.InfiniteRegion infiniteRegion = null;
        List<TrackEvent> repeatEvents = Collections.emptyList();

        while (true) {
            StateKey key = new StateKey(cursors, slots, currentTick);
            StateMark previous = seen.get(key);
            if (previous != null) {
                if (currentTick <= previous.rawTick) {
                    throw new IllegalStateException("Native DD scheduler repeated without advancing time.");
                }
                int slot = activeInfiniteSlot(slots);
                InfiniteCandidate candidate = slot < 0 ? null : slots[slot].infiniteCandidate;
                boolean firstPassIsPeriodic = candidate != null
                        && candidate.bodyEndRawTick - candidate.bodyStartRawTick
                                == currentTick - previous.rawTick
                        && equivalentCycle(
                                executed,
                                candidate.bodyStartEventIndex,
                                candidate.bodyEndEventIndex,
                                candidate.bodyStartRawTick,
                                previous.executedEventCount,
                                executed.size(),
                                previous.rawTick);
                long loopStart = firstPassIsPeriodic
                        ? candidate.bodyStartRawTick : previous.rawTick;
                long loopEnd = firstPassIsPeriodic
                        ? candidate.bodyEndRawTick : currentTick;
                int sourceStart = candidate == null ? 0 : candidate.sourceStartRawTick;
                int sourceEnd = candidate == null ? sourceEndRawTick : candidate.sourceEndRawTick;
                infiniteRegion = new LoopModel.InfiniteRegion(
                        slot,
                        checkedTick(loopStart),
                        checkedTick(loopEnd),
                        checkedTick(previous.rawTick),
                        checkedTick(currentTick),
                        sourceStart,
                        sourceEnd);
                repeatEvents = new ArrayList<TrackEvent>(
                        executed.subList(previous.executedEventCount, executed.size()));
                if (firstPassIsPeriodic) {
                    loopWarnings.add("Native DD first infinite pass matches the parser cycle at raw tick "
                            + loopStart + " to " + loopEnd + ".");
                } else {
                    loopWarnings.add("Native DD parser execution repeats from raw tick "
                            + previous.rawTick + " to " + currentTick
                            + "; the transient is retained once.");
                }
                break;
            }
            seen.put(key, new StateMark(currentTick, executed.size()));

            int nextCursor = nextCursor(cursors);
            if (nextCursor < 0) break;
            Cursor cursor = cursors[nextCursor];
            currentTick = cursor.dueTick;
            TrackEvent source = cursor.current();
            TrackEvent event = copyAt(source, checkedTick(currentTick));
            executed.add(event);

            if (event instanceof SystemEvent) {
                SystemEvent system = (SystemEvent) event;
                if (system.command == 0xDD && system.trackIndex == 0) {
                    int slot = (system.value >>> 6) & 3;
                    int operation = system.value & 3;
                    if (operation == 0) {
                        advance(cursor, currentTick);
                        slots[slot].capture(cursors, currentTick, source.rawTick, executed.size());
                        continue;
                    }
                    if (operation == 1 && slots[slot].valid) {
                        SlotState state = slots[slot];
                        if (state.startRawTick == currentTick) {
                            loopWarnings.add("Zero-duration DD loop end at raw tick "
                                    + currentTick + " marks every native track parser context done.");
                            for (Cursor c : cursors) c.finish();
                            break;
                        }

                        if (!state.active) {
                            int repeatNibble = (system.value >>> 2) & 15;
                            state.remaining = repeatNibble == 0 ? -1 : repeatNibble;
                            state.active = true;
                            activations.add(new LoopModel.Activation(
                                    slot,
                                    repeatNibble == 0 ? -1 : repeatNibble,
                                    checkedTick(state.startRawTick),
                                    checkedTick(currentTick),
                                    state.sourceStartRawTick,
                                    source.rawTick));
                            if (repeatNibble == 0) {
                                state.infiniteCandidate = new InfiniteCandidate(
                                        state.startRawTick,
                                        currentTick,
                                        state.sourceStartRawTick,
                                        source.rawTick,
                                        state.captureExecutedEventCount,
                                        executed.size());
                            }
                        } else if (state.remaining > 0) {
                            state.remaining--;
                        }

                        if (state.remaining == 0) {
                            state.active = false;
                            state.valid = false;
                            advance(cursor, currentTick);
                        } else {
                            if (state.remaining > 0) finiteRewinds++;
                            rewinds.add(new LoopModel.Rewind(
                                    slot,
                                    checkedTick(currentTick),
                                    state.sourceStartRawTick));
                            state.restore(cursors, currentTick);
                        }
                        continue;
                    }
                }
            }

            advance(cursor, currentTick);
        }

        warnings.addAll(loopWarnings);
        LoopModel loop = new LoopModel(
                activations,
                rewinds,
                finiteRewinds,
                infiniteRegion,
                sourceEndRawTick,
                loopWarnings);
        return new Execution(executed, repeatEvents, loop, checkedTick(currentTick));
    }

    private static int sourceEndRawTick(List<DecodedTrack> tracks) {
        int end = 0;
        for (DecodedTrack track : tracks) {
            if (track.events.isEmpty()) continue;
            end = Math.max(end, track.events.get(track.events.size() - 1).rawTick);
        }
        return end;
    }

    private static int nextCursor(Cursor[] cursors) {
        int selected = -1;
        for (int i = 0; i < cursors.length; i++) {
            Cursor c = cursors[i];
            if (c.done) continue;
            if (selected < 0
                    || c.dueTick < cursors[selected].dueTick
                    || (c.dueTick == cursors[selected].dueTick
                        && c.trackIndex < cursors[selected].trackIndex)) {
                selected = i;
            }
        }
        return selected;
    }

    private static void advance(Cursor cursor, long currentTick) {
        int previousIndex = cursor.index;
        cursor.index++;
        if (cursor.index >= cursor.events.size()) {
            cursor.finish();
            return;
        }
        TrackEvent previous = cursor.events.get(previousIndex);
        TrackEvent next = cursor.events.get(cursor.index);
        int sourceDelta = next.rawTick - previous.rawTick;
        if (sourceDelta < 0) {
            throw new IllegalArgumentException(
                    "Decoded track raw ticks are not monotonic at track " + cursor.trackIndex + ".");
        }
        // TrackDecoder rawTick is the cumulative form of the native next-event delta.
        // Using the difference here is equivalent for decoded MLD data and keeps the
        // scheduler anchored to the canonical decoded timeline rather than envelope fields.
        cursor.dueTick = safeAdd(currentTick, sourceDelta);
    }

    private static int activeInfiniteSlot(SlotState[] slots) {
        for (int i = 0; i < slots.length; i++) {
            if (slots[i].active && slots[i].remaining < 0) return i;
        }
        return -1;
    }

    private static boolean equivalentCycle(
            List<TrackEvent> events,
            int firstStartIndex,
            int firstEndIndex,
            long firstStartRawTick,
            int periodicStartIndex,
            int periodicEndIndex,
            long periodicStartRawTick) {
        int firstCount = firstEndIndex - firstStartIndex;
        int periodicCount = periodicEndIndex - periodicStartIndex;
        if (firstCount < 0 || firstCount != periodicCount) return false;
        for (int i = 0; i < firstCount; i++) {
            TrackEvent first = events.get(firstStartIndex + i);
            TrackEvent periodic = events.get(periodicStartIndex + i);
            if (first.getClass() != periodic.getClass()
                    || first.trackIndex != periodic.trackIndex
                    || first.eventIndex != periodic.eventIndex
                    || (long) first.rawTick - firstStartRawTick
                            != (long) periodic.rawTick - periodicStartRawTick) {
                return false;
            }
        }
        return true;
    }

    private static long safeAdd(long left, int right) {
        if (right < 0 || left > Integer.MAX_VALUE - (long) right) {
            throw new IllegalArgumentException("Native DD execution exceeds supported raw-tick range.");
        }
        return left + right;
    }

    private static int checkedTick(long value) {
        if (value < 0L || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Native DD execution exceeds supported raw-tick range.");
        }
        return (int) value;
    }

    static TrackEvent copyAt(TrackEvent source, int rawTick) {
        if (source instanceof NoteEvent) {
            NoteEvent e = (NoteEvent) source;
            return new NoteEvent(
                    e.trackIndex, e.eventIndex, e.delta, rawTick,
                    e.status, e.voice, e.pitch, e.gate, e.velocity,
                    e.octaveShift, e.noteExtraBytes);
        }
        if (source instanceof SystemEvent) {
            SystemEvent e = (SystemEvent) source;
            return new SystemEvent(
                    e.trackIndex, e.eventIndex, e.delta, rawTick,
                    e.command, e.value, e.name, e.part, e.timebase);
        }
        if (source instanceof ResourceEvent) {
            ResourceEvent e = (ResourceEvent) source;
            return new ResourceEvent(
                    e.trackIndex, e.eventIndex, e.delta, rawTick,
                    e.command, e.name, e.copyBody(), e.longForm);
        }
        if (source instanceof MachineDependentEvent) {
            MachineDependentEvent e = (MachineDependentEvent) source;
            return new MachineDependentEvent(
                    e.trackIndex, e.eventIndex, e.delta, rawTick,
                    e.command, e.copyPayload());
        }
        if (source instanceof ReservedEvent) {
            ReservedEvent e = (ReservedEvent) source;
            return new ReservedEvent(
                    e.trackIndex, e.eventIndex, e.delta, rawTick,
                    e.status, e.command, e.copyBody(), e.longForm);
        }
        throw new IllegalArgumentException("Unsupported decoded event type: " + source.getClass().getName());
    }

    static final class Execution {
        final List<TrackEvent> events;
        final List<TrackEvent> repeatEvents;
        final LoopModel loop;
        final int endRawTick;

        Execution(
                List<TrackEvent> events,
                List<TrackEvent> repeatEvents,
                LoopModel loop,
                int endRawTick) {
            this.events = Collections.unmodifiableList(new ArrayList<TrackEvent>(events));
            this.repeatEvents = Collections.unmodifiableList(new ArrayList<TrackEvent>(repeatEvents));
            this.loop = loop;
            this.endRawTick = endRawTick;
        }
    }

    private static final class Cursor {
        final int trackIndex;
        final List<TrackEvent> events;
        int index;
        long dueTick;
        boolean done;

        Cursor(int trackIndex, List<TrackEvent> events) {
            this.trackIndex = trackIndex;
            this.events = events;
            this.index = 0;
            this.done = events.isEmpty();
            this.dueTick = this.done ? Long.MAX_VALUE : events.get(0).rawTick;
        }

        TrackEvent current() {
            return events.get(index);
        }

        void finish() {
            done = true;
            dueTick = Long.MAX_VALUE;
            index = events.size();
        }
    }

    private static final class SlotState {
        boolean valid;
        boolean active;
        int remaining;
        long startRawTick;
        int sourceStartRawTick;
        int captureExecutedEventCount;
        Snapshot snapshot;
        InfiniteCandidate infiniteCandidate;

        void capture(
                Cursor[] cursors,
                long currentTick,
                int sourceRawTick,
                int executedEventCount) {
            valid = true;
            startRawTick = currentTick;
            sourceStartRawTick = sourceRawTick;
            captureExecutedEventCount = executedEventCount;
            infiniteCandidate = null;
            snapshot = new Snapshot(cursors, currentTick);
        }

        void restore(Cursor[] cursors, long currentTick) {
            if (!valid || snapshot == null) return;
            snapshot.restore(cursors, currentTick);
        }
    }

    private static final class Snapshot {
        final int[] indices;
        final long[] remainingTicks;
        final boolean[] done;

        Snapshot(Cursor[] cursors, long currentTick) {
            indices = new int[cursors.length];
            remainingTicks = new long[cursors.length];
            done = new boolean[cursors.length];
            for (int i = 0; i < cursors.length; i++) {
                Cursor c = cursors[i];
                indices[i] = c.index;
                done[i] = c.done;
                remainingTicks[i] = c.done ? Long.MAX_VALUE : Math.max(0L, c.dueTick - currentTick);
            }
        }

        void restore(Cursor[] cursors, long currentTick) {
            for (int i = 0; i < cursors.length; i++) {
                Cursor c = cursors[i];
                c.index = indices[i];
                c.done = done[i];
                c.dueTick = c.done ? Long.MAX_VALUE : currentTick + remainingTicks[i];
            }
        }
    }

    private static final class StateMark {
        final long rawTick;
        final int executedEventCount;

        StateMark(long rawTick, int executedEventCount) {
            this.rawTick = rawTick;
            this.executedEventCount = executedEventCount;
        }
    }

    private static final class InfiniteCandidate {
        final long bodyStartRawTick;
        final long bodyEndRawTick;
        final int sourceStartRawTick;
        final int sourceEndRawTick;
        final int bodyStartEventIndex;
        final int bodyEndEventIndex;

        InfiniteCandidate(
                long bodyStartRawTick,
                long bodyEndRawTick,
                int sourceStartRawTick,
                int sourceEndRawTick,
                int bodyStartEventIndex,
                int bodyEndEventIndex) {
            this.bodyStartRawTick = bodyStartRawTick;
            this.bodyEndRawTick = bodyEndRawTick;
            this.sourceStartRawTick = sourceStartRawTick;
            this.sourceEndRawTick = sourceEndRawTick;
            this.bodyStartEventIndex = bodyStartEventIndex;
            this.bodyEndEventIndex = bodyEndEventIndex;
        }
    }

    private static final class StateKey {
        final int[] cursorIndices;
        final long[] cursorRemaining;
        final boolean[] cursorDone;
        final SlotKey[] slots;

        StateKey(Cursor[] cursors, SlotState[] states, long currentTick) {
            cursorIndices = new int[cursors.length];
            cursorRemaining = new long[cursors.length];
            cursorDone = new boolean[cursors.length];
            for (int i = 0; i < cursors.length; i++) {
                Cursor c = cursors[i];
                cursorIndices[i] = c.index;
                cursorDone[i] = c.done;
                cursorRemaining[i] = c.done ? Long.MAX_VALUE : c.dueTick - currentTick;
            }
            slots = new SlotKey[states.length];
            for (int i = 0; i < states.length; i++) slots[i] = new SlotKey(states[i], currentTick);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof StateKey)) return false;
            StateKey that = (StateKey) other;
            return Arrays.equals(cursorIndices, that.cursorIndices)
                    && Arrays.equals(cursorRemaining, that.cursorRemaining)
                    && Arrays.equals(cursorDone, that.cursorDone)
                    && Arrays.equals(slots, that.slots);
        }

        @Override
        public int hashCode() {
            int result = Arrays.hashCode(cursorIndices);
            result = 31 * result + Arrays.hashCode(cursorRemaining);
            result = 31 * result + Arrays.hashCode(cursorDone);
            result = 31 * result + Arrays.hashCode(slots);
            return result;
        }
    }

    private static final class SlotKey {
        final boolean valid;
        final boolean active;
        final int remaining;
        final boolean startAtCurrentTick;
        final int[] snapshotIndices;
        final long[] snapshotRemaining;
        final boolean[] snapshotDone;

        SlotKey(SlotState state, long currentTick) {
            valid = state.valid;
            active = state.active;
            remaining = state.remaining < 0 ? -1 : state.remaining;
            startAtCurrentTick = state.valid && state.startRawTick == currentTick;
            if (state.snapshot == null) {
                snapshotIndices = new int[0];
                snapshotRemaining = new long[0];
                snapshotDone = new boolean[0];
            } else {
                snapshotIndices = state.snapshot.indices.clone();
                snapshotRemaining = state.snapshot.remainingTicks.clone();
                snapshotDone = state.snapshot.done.clone();
            }
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof SlotKey)) return false;
            SlotKey that = (SlotKey) other;
            return valid == that.valid
                    && active == that.active
                    && remaining == that.remaining
                    && startAtCurrentTick == that.startAtCurrentTick
                    && Arrays.equals(snapshotIndices, that.snapshotIndices)
                    && Arrays.equals(snapshotRemaining, that.snapshotRemaining)
                    && Arrays.equals(snapshotDone, that.snapshotDone);
        }

        @Override
        public int hashCode() {
            int result = valid ? 1 : 0;
            result = 31 * result + (active ? 1 : 0);
            result = 31 * result + remaining;
            result = 31 * result + (startAtCurrentTick ? 1 : 0);
            result = 31 * result + Arrays.hashCode(snapshotIndices);
            result = 31 * result + Arrays.hashCode(snapshotRemaining);
            result = 31 * result + Arrays.hashCode(snapshotDone);
            return result;
        }
    }

    private static final Comparator<DecodedTrack> TRACK_ORDER = new Comparator<DecodedTrack>() {
        @Override
        public int compare(DecodedTrack left, DecodedTrack right) {
            return Integer.compare(left.trackIndex, right.trackIndex);
        }
    };
}
