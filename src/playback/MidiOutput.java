package playback;

import java.nio.file.Files;
import java.nio.file.Path;

/** Immutable selectable MIDI output configuration. */
public final class MidiOutput {
    enum Backend {
        JAVA_SYNTH,
        MIDI_DEVICE,
        FLUIDSYNTH
    }

    final String displayName;
    final Backend backend;
    final String deviceNameFragment;
    final String command;
    final Path soundFont;

    MidiOutput(
            String displayName,
            Backend backend,
            String deviceNameFragment,
            String command,
            Path soundFont) {
        this.displayName = displayName;
        this.backend = backend;
        this.deviceNameFragment = deviceNameFragment;
        this.command = command;
        this.soundFont = soundFont;
    }

    public boolean needsSoundFont() {
        return backend == Backend.FLUIDSYNTH && soundFont == null;
    }

    public MidiOutput withSoundFont(Path path) {
        if (backend != Backend.FLUIDSYNTH) {
            throw new IllegalStateException("Only FluidSynth uses an external SoundFont.");
        }
        if (path == null) {
            throw new IllegalArgumentException("No SoundFont was selected.");
        }
        Path normalized = path.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized) || !FluidSynthBackend.isSoundFont(normalized)) {
            throw new IllegalArgumentException("Choose an existing SF2 or SF3 SoundFont.");
        }
        return new MidiOutput(
                "FluidSynth - " + normalized.getFileName(),
                backend,
                null,
                command,
                normalized);
    }

    @Override
    public String toString() {
        return displayName;
    }
}
