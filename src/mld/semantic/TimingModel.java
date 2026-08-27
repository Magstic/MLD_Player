package mld.semantic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Native tempo/timebase model. No host MIDI units are stored here.
 */
public final class TimingModel {
    public static final int DEFAULT_TIMEBASE = 48;
    public static final int DEFAULT_TEMPO = 125;
    public final List<Point> points;

    TimingModel(List<Point> points) {
        this.points = Collections.unmodifiableList(new ArrayList<Point>(points));
    }

    public Point pointAt(int rawTick) {
        Point current = points.get(0);
        for (int i = 1; i < points.size(); i++) {
            Point next = points.get(i);
            if (next.rawTick > rawTick) break;
            current = next;
        }
        return current;
    }

    public long rawTickToMicros(int rawTick) {
        if (rawTick <= 0) return 0L;
        long micros = 0;
        Point current = points.get(0);
        int currentRaw = 0;
        for (int i = 1; i < points.size(); i++) {
            Point next = points.get(i);
            if (next.rawTick > rawTick) break;
            int delta = Math.max(0, next.rawTick - currentRaw);
            micros += rawDurationToMicros(delta, current.timebase, current.tempo);
            currentRaw = next.rawTick;
            current = next;
        }
        micros += rawDurationToMicros(Math.max(0, rawTick - currentRaw), current.timebase, current.tempo);
        return micros;
    }

    public static long rawDurationToMicros(int rawDuration, int timebase, int tempo) {
        if (rawDuration <= 0) return 0;
        return (60000000L * rawDuration) / ((long)Math.max(1, timebase) * Math.max(1, tempo));
    }

    public static int durationMilliseconds(int rawDuration, int timebase, int tempo) {
        int tq = 15360000 / Math.max(1, timebase);
        int whole = tq / Math.max(1, tempo);
        int fraction = ((tq % Math.max(1, tempo)) << 8) / Math.max(1, tempo);
        return (((fraction * rawDuration) >>> 8) + (whole * rawDuration)) >>> 8;
    }

    public static final class Point {
        public final int rawTick;
        public final int timebase;
        public final int tempo;
        public final boolean synthetic;

        public Point(int rawTick, int timebase, int tempo, boolean synthetic) {
            this.rawTick = rawTick;
            this.timebase = timebase;
            this.tempo = tempo;
            this.synthetic = synthetic;
        }
    }
}
