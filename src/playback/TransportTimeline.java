package playback;

import midi.MidiPlan;
import mld.semantic.LoopModel;
import mld.semantic.NativeLoopRuntime;
import mld.semantic.TimingModel;

/** Pure shared-transport policy in microseconds for MIDI and PCM participants. */
final class TransportTimeline {
    final boolean infinite;
    final boolean hasLoopRegion;
    final boolean nativeLoop;
    final long loopStartMicros;
    final long loopEndMicros;
    final long loopBodyMicros;
    final long baseDurationMicros;
    final long midiDurationMicros;
    final long totalDurationMicros;
    final int totalPasses;
    private final TimingModel timing;
    private final LoopModel loop;
    private volatile NativeLoopRuntime.Cycle observedNativeCycle;
    private volatile long observedNativeCycleNumber = 2L;

    private TransportTimeline(
            boolean infinite,
            boolean hasLoopRegion,
            boolean nativeLoop,
            long loopStartMicros,
            long loopEndMicros,
            long baseDurationMicros,
            long midiDurationMicros,
            long totalDurationMicros,
            int totalPasses,
            TimingModel timing,
            LoopModel loop) {
        this.infinite = infinite;
        this.hasLoopRegion = hasLoopRegion;
        this.nativeLoop = nativeLoop;
        this.loopStartMicros = Math.max(0L, loopStartMicros);
        this.loopEndMicros = Math.max(this.loopStartMicros, loopEndMicros);
        this.loopBodyMicros = Math.max(1L, this.loopEndMicros - this.loopStartMicros);
        this.baseDurationMicros = Math.max(1L, baseDurationMicros);
        this.midiDurationMicros = Math.max(0L, midiDurationMicros);
        this.totalDurationMicros = infinite ? Long.MAX_VALUE : Math.max(1L, totalDurationMicros);
        this.totalPasses = totalPasses;
        this.timing = timing;
        this.loop = loop;
    }

    static TransportTimeline from(PlaybackContent content, int requestedLoopCount) {
        long midiDuration = content.midi == null ? 0L : midiContentMicros(content.midi);
        long audioDuration = content.audio == null ? 0L : content.audio.getLinearDurationMicros();
        long baseDuration = Math.max(1L, Math.max(midiDuration, audioDuration));

        TimingModel timing = content.program.timing;
        LoopModel loop = content.program.loop;
        if (loop != null && loop.hasInfiniteLoop() && timing != null) {
            LoopModel.InfiniteRegion region = loop.infiniteRegion;
            long start = timing.rawTickToMicros(region.loopStartRawTick);
            long end = timing.rawTickToMicros(region.loopEndRawTick);
            if (end <= start) end = start + 1L;
            return new TransportTimeline(
                    true,
                    true,
                    true,
                    start,
                    end,
                    Math.max(baseDuration, end),
                    midiDuration,
                    Long.MAX_VALUE,
                    -1,
                    timing,
                    loop);
        }

        if (requestedLoopCount < 0) {
            return new TransportTimeline(
                    true,
                    true,
                    false,
                    0L,
                    baseDuration,
                    baseDuration,
                    midiDuration,
                    Long.MAX_VALUE,
                    -1,
                    timing,
                    loop);
        }
        if (requestedLoopCount > 0) {
            int passes = requestedLoopCount + 1;
            long total = safeMultiply(baseDuration, passes);
            if (content.audio != null) {
                total = Math.max(total, content.audio.transportDurationMicros(requestedLoopCount, baseDuration));
            }
            return new TransportTimeline(
                    false,
                    true,
                    false,
                    0L,
                    baseDuration,
                    baseDuration,
                    midiDuration,
                    total,
                    passes,
                    timing,
                    loop);
        }
        return new TransportTimeline(
                false,
                false,
                false,
                0L,
                baseDuration,
                baseDuration,
                midiDuration,
                baseDuration,
                1,
                timing,
                loop);
    }

    long midiPositionMicros(long transportMicros) {
        long position = Math.max(0L, transportMicros);
        return hasLoopRegion ? sourcePositionMicros(position) : position;
    }

    long pollSleepMillis(long transportMicros, long defaultMillis) {
        if (!hasLoopRegion || defaultMillis <= 1L) return Math.max(1L, defaultMillis);
        long position = Math.max(0L, transportMicros);
        long nextBoundary;
        if (nativeLoop) {
            NativeLoopRuntime.Cycle cycle = observedNativeCycle;
            if (cycle != null && position >= cycle.transportStartMicros
                    && position < cycle.transportEndMicros) {
                nextBoundary = cycle.transportEndMicros;
            } else {
                long pass = position < loopStartMicros
                        ? -1L : (position - loopStartMicros) / loopBodyMicros;
                nextBoundary = pass < 0L
                        ? loopStartMicros
                        : safeAdd(loopStartMicros, safeMultiply(pass + 1L, loopBodyMicros));
            }
        } else {
            long pass = position / baseDurationMicros;
            nextBoundary = safeMultiply(pass + 1L, baseDurationMicros);
        }
        long remainingMicros = Math.max(0L, nextBoundary - position);
        long boundaryMillis = Math.max(1L, (remainingMicros + 999L) / 1000L);
        return Math.max(1L, Math.min(defaultMillis, boundaryMillis));
    }

    PlaybackMonitor.Progress progress(long transportMicros, boolean finalFrame) {
        long position = Math.max(0L, transportMicros);
        if (!infinite) {
            position = Math.min(position, totalDurationMicros);
            if (finalFrame) position = totalDurationMicros;
        }
        long displayTotal = infinite && nativeLoop ? loopEndMicros
                : (infinite ? baseDurationMicros : totalDurationMicros);
        long displayPosition;
        double fraction;
        if (loopHasSourcePlayhead()) {
            long executedMicros = hasLoopRegion ? sourcePositionMicros(position) : position;
            int executedRaw = timing.microsToRawTick(executedMicros);
            int sourceRaw = loop.sourceRawTickAt(executedRaw);
            displayPosition = executedMicros;
            int sourceDisplayEnd = loop.hasInfiniteLoop()
                    ? loop.infiniteRegion.sourceEndRawTick
                    : loop.sourceEndRawTick;
            fraction = (double) sourceRaw / (double) Math.max(1, sourceDisplayEnd);
        } else if (infinite) {
            long source = sourcePositionMicros(position);
            displayPosition = Math.max(0L, source);
            fraction = (double) Math.min(displayPosition, displayTotal) / (double) displayTotal;
        } else {
            displayPosition = position;
            fraction = (double) position / (double) Math.max(1L, totalDurationMicros);
        }
        fraction = Math.max(0.0, Math.min(1.0, fraction));
        return new PlaybackMonitor.Progress(
                displayPosition,
                Math.max(1L, displayTotal),
                fraction,
                label(position, finalFrame));
    }

    private boolean loopHasSourcePlayhead() {
        return loop != null && loop.hasNativeLoops() && loop.sourceEndRawTick > 0;
    }

    boolean isComplete(long transportMicros) {
        return !infinite && transportMicros >= totalDurationMicros;
    }

    void observeNativeCycle(NativeLoopRuntime.Cycle cycle) {
        if (!nativeLoop || cycle == null) return;
        NativeLoopRuntime.Cycle previous = observedNativeCycle;
        if (previous == null || cycle.transportStartMicros > previous.transportStartMicros) {
            observedNativeCycleNumber++;
        }
        observedNativeCycle = cycle;
    }

    String describeLoop() {
        if (!hasLoopRegion) return "once";
        if (infinite) return "infinite";
        return totalPasses <= 1 ? "once" : totalPasses + " passes";
    }

    private long sourcePositionMicros(long position) {
        if (!hasLoopRegion) return position;
        if (nativeLoop) {
            if (position < loopStartMicros) return position;
            NativeLoopRuntime.Cycle cycle = observedNativeCycle;
            if (cycle != null && position >= cycle.transportStartMicros
                    && position < cycle.transportEndMicros) {
                long duration = Math.max(1L, cycle.transportEndMicros - cycle.transportStartMicros);
                long phase = position - cycle.transportStartMicros;
                return loopStartMicros + (phase * loopBodyMicros) / duration;
            }
            return loopStartMicros + ((position - loopStartMicros) % loopBodyMicros);
        }
        if (!infinite && position >= totalDurationMicros) return -1L;
        return position % baseDurationMicros;
    }

    private String label(long position, boolean finalFrame) {
        if (!hasLoopRegion) return "play";
        if (nativeLoop) {
            if (position < loopStartMicros && !finalFrame) return "intro";
            NativeLoopRuntime.Cycle cycle = observedNativeCycle;
            if (cycle != null && position >= cycle.transportStartMicros
                    && position < cycle.transportEndMicros) {
                return "loop " + Math.min(Integer.MAX_VALUE, observedNativeCycleNumber) + "/inf";
            }
            long relative = Math.max(0L, position - loopStartMicros);
            long pass = (relative / loopBodyMicros) + 1L;
            return "loop " + Math.min(Integer.MAX_VALUE, pass) + "/inf";
        }
        int pass = (int) Math.min(Integer.MAX_VALUE, position / baseDurationMicros) + 1;
        if (!infinite) pass = Math.min(pass, Math.max(1, totalPasses));
        return "pass " + pass + "/" + (infinite ? "inf" : String.valueOf(Math.max(1, totalPasses)));
    }

    private static long midiContentMicros(MidiPlan plan) {
        long contentEndTick = plan.loopInfo.hasLoop
                ? Math.max(1L, plan.loopInfo.loopEndMidiTick)
                : Math.max(1L, plan.totalMidiTicks);
        return midiMicrosAtTick(plan, contentEndTick);
    }

    private static long midiMicrosAtTick(MidiPlan plan, long targetTick) {
        MidiPlan.TempoPoint active = plan.tempoPoints.get(0);
        long previousTick = 0L;
        long micros = 0L;
        int mpqn = active.mpqn;
        for (MidiPlan.TempoPoint point : plan.tempoPoints) {
            if (point.midiTick <= 0L) {
                mpqn = point.mpqn;
                continue;
            }
            if (point.midiTick > targetTick) break;
            micros = safeAdd(micros, ticksToMicros(point.midiTick - previousTick, mpqn));
            previousTick = point.midiTick;
            mpqn = point.mpqn;
        }
        micros = safeAdd(micros, ticksToMicros(targetTick - previousTick, mpqn));
        return Math.max(1L, micros);
    }

    private static long ticksToMicros(long ticks, int mpqn) {
        if (ticks <= 0L) return 0L;
        long whole = ticks / MidiPlan.MIDI_PPQ;
        long remainder = ticks % MidiPlan.MIDI_PPQ;
        return safeAdd(safeMultiply(whole, mpqn), (remainder * (long) mpqn) / MidiPlan.MIDI_PPQ);
    }

    private static long safeMultiply(long left, long right) {
        if (left == 0L || right == 0L) return 0L;
        if (left > Long.MAX_VALUE / right) return Long.MAX_VALUE;
        return left * right;
    }

    private static long safeAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
    }
}
