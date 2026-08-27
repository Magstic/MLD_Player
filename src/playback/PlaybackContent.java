package playback;

import audio.AudioPlaybackSource;
import midi.MidiSequenceEncoder;
import mld.semantic.LoopModel;
import mld.semantic.TimingModel;

/** Immutable media participants and native timing needed by one shared playback session. */
public final class PlaybackContent {
    public final MidiSequenceEncoder.EncodedSequence midi;
    public final AudioPlaybackSource audio;
    public final TimingModel timing;
    public final LoopModel loop;

    public PlaybackContent(
            MidiSequenceEncoder.EncodedSequence midi,
            AudioPlaybackSource audio,
            TimingModel timing,
            LoopModel loop) {
        if (midi == null && audio == null) {
            throw new IllegalArgumentException("Playback content requires MIDI or sampled audio.");
        }
        if (audio != null && (timing == null || loop == null)) {
            throw new IllegalArgumentException(
                    "Sampled-audio playback requires native timing and loop models.");
        }
        this.midi = midi;
        this.audio = audio;
        this.timing = timing;
        this.loop = loop;
    }

    public boolean hasMidi() {
        return midi != null;
    }

    public boolean hasPcm() {
        return audio != null;
    }
}
