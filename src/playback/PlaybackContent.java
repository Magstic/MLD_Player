package playback;

import audio.AudioPlaybackSource;
import midi.MidiPlan;
import mld.semantic.NativeProgram;

/** Immutable media participants and native timing needed by one shared playback session. */
public final class PlaybackContent {
    public final MidiPlan midi;
    public final AudioPlaybackSource audio;
    public final NativeProgram program;

    public PlaybackContent(
            MidiPlan midi,
            AudioPlaybackSource audio,
            NativeProgram program) {
        if (midi == null && audio == null) {
            throw new IllegalArgumentException("Playback content requires MIDI or sampled audio.");
        }
        if (program == null) {
            throw new IllegalArgumentException("Playback requires the native semantic program.");
        }
        this.midi = midi;
        this.audio = audio;
        this.program = program;
    }

    public boolean hasMidi() {
        return midi != null;
    }

    public boolean hasPcm() {
        return audio != null;
    }
}
