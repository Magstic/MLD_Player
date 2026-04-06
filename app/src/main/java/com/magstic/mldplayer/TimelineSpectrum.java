package com.magstic.mldplayer;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import timeline.PlaybackTimeline;

final class TimelineSpectrum {
    static final int BAND_COUNT = 20;
    private static final float MIN_LEVEL = 0.12f;
    private static final int MIN_MIDI_NOTE = 24;
    private static final int MAX_MIDI_NOTE = 108;

    private final SpectrumNote[] notes;
    private final int durationMs;

    private TimelineSpectrum(SpectrumNote[] notes, int durationMs) {
        this.notes = notes;
        this.durationMs = durationMs;
    }

    static TimelineSpectrum from(PlaybackTimeline timeline, int durationMs) {
        MidiTimeMapper mapper;
        ArrayList<SpectrumNote> spectrumNotes;
        int index;

        if (timeline == null || timeline.notes.isEmpty() || durationMs <= 0) {
            return new TimelineSpectrum(new SpectrumNote[0], durationMs);
        }
        mapper = new MidiTimeMapper(timeline.tempoPoints);
        spectrumNotes = new ArrayList<SpectrumNote>(timeline.notes.size());
        for (index = 0; index < timeline.notes.size(); index++) {
            PlaybackTimeline.CompiledNote note = timeline.notes.get(index);
            int startMs = mapper.toMillis(note.midiStartTick);
            int endMs = mapper.toMillis(note.midiEndTick);

            if (endMs <= startMs) {
                endMs = startMs + 32;
            }
            if (endMs <= 0 || startMs >= durationMs) {
                continue;
            }
            spectrumNotes.add(new SpectrumNote(
                    clamp(startMs, 0, durationMs),
                    clamp(endMs, 0, durationMs),
                    resolveBand(note.midiNote),
                    resolveEnergy(note.velocity)));
        }
        return new TimelineSpectrum(
                spectrumNotes.toArray(new SpectrumNote[spectrumNotes.size()]),
                durationMs);
    }

    @Nullable
    float[] sample(int positionMs) {
        float[] levels;
        float peak;
        int safePositionMs;
        int index;

        if (notes.length == 0 || durationMs <= 0) {
            return null;
        }
        levels = new float[BAND_COUNT];
        safePositionMs = clamp(positionMs, 0, durationMs);
        for (index = 0; index < notes.length; index++) {
            SpectrumNote note = notes[index];

            if (safePositionMs < note.startMs || safePositionMs >= note.endMs) {
                continue;
            }
            contribute(levels, note, safePositionMs);
        }
        peak = 0f;
        for (index = 0; index < BAND_COUNT; index++) {
            peak = Math.max(peak, levels[index]);
        }
        if (peak <= 0f) {
            return null;
        }
        for (index = 0; index < BAND_COUNT; index++) {
            float normalized = levels[index] / peak;

            levels[index] = MIN_LEVEL + ((float) Math.sqrt(Math.min(1f, normalized)) * (1f - MIN_LEVEL));
        }
        return levels;
    }

    private void contribute(float[] levels, SpectrumNote note, int positionMs) {
        float envelope = note.envelopeAt(positionMs);
        float energy = note.energy * envelope;

        add(levels, note.band, energy);
        add(levels, note.band - 1, energy * 0.45f);
        add(levels, note.band + 1, energy * 0.45f);
        add(levels, note.band - 2, energy * 0.18f);
        add(levels, note.band + 2, energy * 0.18f);
    }

    private void add(float[] levels, int band, float energy) {
        if (band < 0 || band >= levels.length || energy <= 0f) {
            return;
        }
        levels[band] += energy;
    }

    private static int resolveBand(int midiNote) {
        float normalized = (clamp(midiNote, MIN_MIDI_NOTE, MAX_MIDI_NOTE) - MIN_MIDI_NOTE)
                / (float) (MAX_MIDI_NOTE - MIN_MIDI_NOTE);

        return Math.min(BAND_COUNT - 1, (int) (Math.pow(normalized, 0.82d) * BAND_COUNT));
    }

    private static float resolveEnergy(int velocity) {
        float normalizedVelocity = clamp(velocity, 1, 127) / 127f;

        return 0.42f + (normalizedVelocity * normalizedVelocity * 0.9f);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class SpectrumNote {
        final int startMs;
        final int endMs;
        final int band;
        final float energy;

        SpectrumNote(int startMs, int endMs, int band, float energy) {
            this.startMs = startMs;
            this.endMs = Math.max(startMs + 1, endMs);
            this.band = band;
            this.energy = energy;
        }

        float envelopeAt(int positionMs) {
            int duration = endMs - startMs;
            int attackWindow = Math.min(96, Math.max(18, duration / 4));
            int releaseWindow = Math.min(144, Math.max(24, duration / 3));
            float attack = Math.min(1f, (positionMs - startMs + 1) / (float) attackWindow);
            float release = Math.min(1f, (endMs - positionMs) / (float) releaseWindow);

            return Math.min(attack, release);
        }
    }

    private static final class MidiTimeMapper {
        private final TempoSegment[] segments;

        MidiTimeMapper(List<PlaybackTimeline.TempoPoint> tempoPoints) {
            ArrayList<TempoSegment> builtSegments = new ArrayList<TempoSegment>();
            long accumulatedMicros = 0L;
            int index;

            if (tempoPoints == null || tempoPoints.isEmpty()) {
                builtSegments.add(new TempoSegment(0L, 500000, 0L));
            } else {
                for (index = 0; index < tempoPoints.size(); index++) {
                    PlaybackTimeline.TempoPoint point = tempoPoints.get(index);
                    long nextTick = index + 1 < tempoPoints.size()
                            ? tempoPoints.get(index + 1).midiTick
                            : Long.MAX_VALUE;

                    builtSegments.add(new TempoSegment(point.midiTick, point.mpqn, accumulatedMicros));
                    if (nextTick != Long.MAX_VALUE && nextTick > point.midiTick) {
                        accumulatedMicros += ((nextTick - point.midiTick) * point.mpqn) / PlaybackTimeline.MIDI_PPQ;
                    }
                }
            }
            segments = builtSegments.toArray(new TempoSegment[builtSegments.size()]);
        }

        int toMillis(long midiTick) {
            TempoSegment segment = segments[0];
            int index;
            long micros;

            for (index = 1; index < segments.length; index++) {
                if (segments[index].startTick > midiTick) {
                    break;
                }
                segment = segments[index];
            }
            micros = segment.accumulatedMicros
                    + (((midiTick - segment.startTick) * segment.mpqn) / PlaybackTimeline.MIDI_PPQ);
            return (int) (micros / 1000L);
        }
    }

    private static final class TempoSegment {
        final long startTick;
        final int mpqn;
        final long accumulatedMicros;

        TempoSegment(long startTick, int mpqn, long accumulatedMicros) {
            this.startTick = startTick;
            this.mpqn = mpqn;
            this.accumulatedMicros = accumulatedMicros;
        }
    }
}
