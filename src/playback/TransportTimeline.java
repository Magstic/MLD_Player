package playback;

import midi.MidiPlan;
import midi.MidiSequenceEncoder;
import mld.semantic.LoopModel;
import mld.semantic.TimingModel;

/** Pure shared-transport policy in microseconds for MIDI and PCM participants. */
final class TransportTimeline {
    final boolean infinite;
    final boolean hasLoopRegion;
    final boolean nativeLoop;
    final boolean materializedNativeLoop;
    final boolean transportOwnsWholeMidiRepeat;
    final long loopStartMicros;
    final long loopEndMicros;
    final long loopBodyMicros;
    final long baseDurationMicros;
    final long midiDurationMicros;
    final long totalDurationMicros;
    final int totalPasses;

    private TransportTimeline(
            boolean infinite,
            boolean hasLoopRegion,
            boolean nativeLoop,
            boolean materializedNativeLoop,
            boolean transportOwnsWholeMidiRepeat,
            long loopStartMicros,
            long loopEndMicros,
            long baseDurationMicros,
            long midiDurationMicros,
            long totalDurationMicros,
            int totalPasses) {
        this.infinite = infinite;
        this.hasLoopRegion = hasLoopRegion;
        this.nativeLoop = nativeLoop;
        this.materializedNativeLoop = materializedNativeLoop;
        this.transportOwnsWholeMidiRepeat = transportOwnsWholeMidiRepeat;
        this.loopStartMicros = Math.max(0L, loopStartMicros);
        this.loopEndMicros = Math.max(this.loopStartMicros, loopEndMicros);
        this.loopBodyMicros = Math.max(1L, this.loopEndMicros - this.loopStartMicros);
        this.baseDurationMicros = Math.max(1L, baseDurationMicros);
        this.midiDurationMicros = Math.max(0L, midiDurationMicros);
        this.totalDurationMicros = infinite ? Long.MAX_VALUE : Math.max(1L, totalDurationMicros);
        this.totalPasses = totalPasses;
    }

    static TransportTimeline from(PlaybackContent content, int requestedLoopCount) {
        long midiDuration = content.midi == null ? 0L : midiContentMicros(content.midi);
        long audioDuration = content.audio == null ? 0L : content.audio.getLinearDurationMicros();
        long baseDuration = Math.max(1L, Math.max(midiDuration, audioDuration));

        TimingModel timing = content.timing;
        LoopModel loop = content.loop;
        if (loop != null && loop.hasLoop && timing != null) {
            long start = timing.rawTickToMicros(loop.loopStartRawTick);
            long end = timing.rawTickToMicros(loop.loopEndRawTick);
            if (end <= start) {
                end = start + 1L;
            }
            boolean materialized = content.midi != null && content.midi.materializedLoopPasses;
            if (materialized) {
                int passes = Math.max(1, content.midi.totalLoopPasses);
                long nominalEnd = safeAdd(start, safeMultiply(end - start, passes));
                long audioTransport = content.audio == null
                        ? 0L
                        : content.audio.transportDurationMicros(requestedLoopCount, baseDuration);
                long total = Math.max(Math.max(nominalEnd, midiDuration), audioTransport);
                return new TransportTimeline(
                        false, true, true, true, false, start, end,
                        Math.max(baseDuration, end), midiDuration, total, passes);
            }
            if (requestedLoopCount < 0) {
                return new TransportTimeline(
                        true, true, true, false, false, start, end,
                        Math.max(baseDuration, end), midiDuration, Long.MAX_VALUE, -1);
            }
            int passes = Math.max(1, requestedLoopCount + 1);
            long nominalEnd = safeAdd(start, safeMultiply(end - start, passes));
            long audioTransport = content.audio == null
                    ? 0L
                    : content.audio.transportDurationMicros(requestedLoopCount, baseDuration);
            long total = Math.max(Math.max(nominalEnd, midiDuration), audioTransport);
            return new TransportTimeline(
                    false, true, true, false, false, start, end,
                    Math.max(baseDuration, end), midiDuration, total, passes);
        }

        boolean transportOwnsWholeRepeat = content.hasMidi()
                && content.hasPcm()
                && audioDuration > midiDuration;
        if (requestedLoopCount < 0) {
            return new TransportTimeline(
                    true, true, false, false, transportOwnsWholeRepeat, 0L, baseDuration,
                    baseDuration, midiDuration, Long.MAX_VALUE, -1);
        }
        if (requestedLoopCount > 0) {
            int passes = requestedLoopCount + 1;
            return new TransportTimeline(
                    false, true, false, false, transportOwnsWholeRepeat, 0L, baseDuration,
                    baseDuration, midiDuration, safeMultiply(baseDuration, passes), passes);
        }
        return new TransportTimeline(
                false, false, false, false, false, 0L, baseDuration,
                baseDuration, midiDuration, baseDuration, 1);
    }

    long midiPositionMicros(long transportMicros) {
        long position = Math.max(0L, transportMicros);
        if (materializedNativeLoop || !hasLoopRegion) {
            return position;
        }
        return sourcePositionMicros(position);
    }

    long midiWholeRepeatPass(long transportMicros) {
        if (!transportOwnsWholeMidiRepeat || baseDurationMicros <= 0L) return 0L;
        return Math.max(0L, transportMicros) / baseDurationMicros;
    }

    boolean crossedMidiWholeRepeatBoundary(long transportMicros, long previousPass) {
        return transportOwnsWholeMidiRepeat
                && midiWholeRepeatPass(transportMicros) > Math.max(0L, previousPass);
    }

    long pollSleepMillis(long transportMicros, long defaultMillis) {
        if (!transportOwnsWholeMidiRepeat || defaultMillis <= 1L) return Math.max(1L, defaultMillis);
        long nextBoundary = safeMultiply(midiWholeRepeatPass(transportMicros) + 1L, baseDurationMicros);
        long remainingMicros = Math.max(0L, nextBoundary - Math.max(0L, transportMicros));
        long boundaryMillis = Math.max(1L, (remainingMicros + 999L) / 1000L);
        return Math.max(1L, Math.min(defaultMillis, boundaryMillis));
    }

    PlaybackMonitor.Progress progress(long transportMicros, boolean finalFrame) {
        long position = Math.max(0L, transportMicros);
        if (!infinite) {
            position = Math.min(position, totalDurationMicros);
            if (finalFrame) {
                position = totalDurationMicros;
            }
        }
        long displayTotal = infinite ? baseDurationMicros : totalDurationMicros;
        long displayPosition;
        double fraction;
        if (infinite) {
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

    boolean isComplete(long transportMicros) {
        return !infinite && transportMicros >= totalDurationMicros;
    }

    String describeLoop() {
        if (!hasLoopRegion) {
            return "once";
        }
        if (infinite) {
            return "infinite";
        }
        if (totalPasses <= 1) {
            return "once";
        }
        return totalPasses + " passes";
    }

    private long sourcePositionMicros(long position) {
        if (!hasLoopRegion) {
            return position;
        }
        if (position < loopStartMicros) {
            return position;
        }
        if (!infinite && position >= loopStopTransportMicros()) {
            return -1L;
        }
        long relative = position - loopStartMicros;
        return loopStartMicros + (relative % loopBodyMicros);
    }

    private long loopStopTransportMicros() {
        if (infinite) {
            return Long.MAX_VALUE;
        }
        if (nativeLoop) {
            return safeAdd(loopStartMicros, safeMultiply(loopBodyMicros, Math.max(1, totalPasses)));
        }
        return safeMultiply(baseDurationMicros, Math.max(1, totalPasses));
    }

    private String label(long position, boolean finalFrame) {
        if (!hasLoopRegion) {
            return "play";
        }
        if (nativeLoop && position < loopStartMicros && !finalFrame) {
            return "intro";
        }
        int pass;
        if (nativeLoop) {
            long relative = Math.max(0L, position - loopStartMicros);
            pass = (int) Math.min(Integer.MAX_VALUE, relative / loopBodyMicros) + 1;
        } else {
            pass = (int) Math.min(Integer.MAX_VALUE, position / baseDurationMicros) + 1;
        }
        if (!infinite) {
            pass = Math.min(pass, Math.max(1, totalPasses));
        }
        return (nativeLoop ? "loop " : "pass ")
                + pass + "/" + (infinite ? "inf" : String.valueOf(Math.max(1, totalPasses)));
    }

    private static long midiContentMicros(MidiSequenceEncoder.EncodedSequence encoded) {
        MidiPlan plan = encoded.plan;
        long targetTick = Math.max(0L, encoded.contentEndTick);
        MidiPlan.TempoPoint active = plan.tempoPoints.get(0);
        long previousTick = 0L;
        long micros = 0L;
        int mpqn = active.mpqn;
        for (MidiPlan.TempoPoint point : plan.tempoPoints) {
            if (point.midiTick <= 0L) {
                mpqn = point.mpqn;
                continue;
            }
            if (point.midiTick > targetTick) {
                break;
            }
            micros = safeAdd(micros, ticksToMicros(point.midiTick - previousTick, mpqn));
            previousTick = point.midiTick;
            mpqn = point.mpqn;
        }
        micros = safeAdd(micros, ticksToMicros(targetTick - previousTick, mpqn));
        return Math.max(1L, micros);
    }

    private static long ticksToMicros(long ticks, int mpqn) {
        if (ticks <= 0L) {
            return 0L;
        }
        long whole = ticks / MidiPlan.MIDI_PPQ;
        long remainder = ticks % MidiPlan.MIDI_PPQ;
        return safeAdd(safeMultiply(whole, mpqn), (remainder * (long) mpqn) / MidiPlan.MIDI_PPQ);
    }

    private static long safeMultiply(long left, long right) {
        if (left == 0L || right == 0L) {
            return 0L;
        }
        if (left > Long.MAX_VALUE / right) {
            return Long.MAX_VALUE;
        }
        return left * right;
    }

    private static long safeAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }
}
