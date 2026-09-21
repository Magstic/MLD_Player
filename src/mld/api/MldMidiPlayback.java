package mld.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.Sequence;

import midi.MidiPlan;
import midi.MidiPlanSegmenter;
import midi.MidiSequenceEncoder;

/**
 * Stable build-time view of the MIDI files needed to preserve an MLD loop point.
 * Segments are played in order; after the last segment, playback resumes at
 * {@link #getLoopSegmentIndex()}, or completes when that value is {@code -1}.
 */
public final class MldMidiPlayback {
    private final List<MidiPlan> segments;
    private final int loopSegmentIndex;

    MldMidiPlayback(MidiPlan source) {
        if (source == null) throw new IllegalArgumentException("source == null");

        List<MidiPlan> result = new ArrayList<MidiPlan>();
        if (!hasUsableLoop(source)) {
            result.add(source);
            loopSegmentIndex = -1;
        } else {
            long loopStart = source.loopInfo.loopStartMidiTick;
            long loopEnd = source.loopInfo.loopEndMidiTick;
            MidiPlan loop = new MidiPlanSegmenter().slice(source, loopStart, loopEnd, true);
            if (hasAudibleIntro(source, loopStart)) {
                result.add(source);
                result.add(loop);
                loopSegmentIndex = 1;
            } else {
                result.add(loop);
                loopSegmentIndex = 0;
            }
        }
        segments = Collections.unmodifiableList(result);
    }

    public int getSegmentCount() {
        return segments.size();
    }

    public int getLoopSegmentIndex() {
        return loopSegmentIndex;
    }

    /** Returns a fresh mutable Java Sound sequence for the requested segment. */
    public Sequence createSegmentSequence(int index) throws InvalidMidiDataException {
        if (index < 0 || index >= segments.size()) {
            throw new IndexOutOfBoundsException("MIDI segment " + index);
        }
        return new MidiSequenceEncoder().encode(segments.get(index)).sequence;
    }

    private static boolean hasUsableLoop(MidiPlan source) {
        return source.loopInfo != null
                && source.loopInfo.hasLoop
                && source.loopInfo.loopStartMidiTick >= 0L
                && source.loopInfo.loopEndMidiTick > source.loopInfo.loopStartMidiTick;
    }

    private static boolean hasAudibleIntro(MidiPlan source, long loopStart) {
        for (MidiPlan.CompiledNote note : source.notes) {
            if (note.midiStartTick < loopStart && note.midiEndTick > 0L) return true;
        }
        return false;
    }
}
