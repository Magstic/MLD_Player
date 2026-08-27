package playback;

import java.util.function.LongSupplier;

/** Pause-aware monotonic microsecond clock used as the shared transport authority. */
final class MonotonicTransportClock {
    private final LongSupplier nanoTime;
    private long accumulatedNanos;
    private long startedAtNanos;
    private boolean running;

    MonotonicTransportClock() {
        this(System::nanoTime);
    }

    MonotonicTransportClock(LongSupplier nanoTime) {
        if (nanoTime == null) {
            throw new IllegalArgumentException("nanoTime == null");
        }
        this.nanoTime = nanoTime;
    }

    void start() {
        accumulatedNanos = 0L;
        startedAtNanos = nanoTime.getAsLong();
        running = true;
    }

    void pause() {
        if (!running) {
            return;
        }
        accumulatedNanos += Math.max(0L, nanoTime.getAsLong() - startedAtNanos);
        running = false;
    }

    void resume() {
        if (running) {
            return;
        }
        startedAtNanos = nanoTime.getAsLong();
        running = true;
    }

    long positionMicros() {
        long nanos = accumulatedNanos;
        if (running) {
            nanos += Math.max(0L, nanoTime.getAsLong() - startedAtNanos);
        }
        return nanos / 1000L;
    }

    boolean isRunning() {
        return running;
    }
}
