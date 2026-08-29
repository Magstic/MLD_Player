package main;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import audio.AudioPlaybackSource;
import audio.AudioRenderer;
import midi.MidiPlan;
import midi.MidiProjector;
import mld.decode.DecodedTrack;
import mld.decode.TrackDecoder;
import mld.format.MldDocument;
import mld.format.MldReader;
import mld.semantic.NativeCompiler;
import mld.semantic.NativeProgram;
import playback.PlaybackContent;

/** Shared application workflow used by CLI and Swing composition layers. */
final class MldApplicationWorkflow {
    private final MldReader reader = new MldReader();
    private final TrackDecoder decoder = new TrackDecoder();
    private final NativeCompiler compiler = new NativeCompiler();
    private final MidiProjector midiProjector = new MidiProjector();
    private final AudioRenderer audioRenderer = new AudioRenderer();

    ApplicationTrack load(Path inputPath) throws IOException {
        if (inputPath == null) {
            throw new IllegalArgumentException("Input path is required.");
        }
        Path normalizedPath = inputPath.toAbsolutePath().normalize();
        MldDocument document = reader.read(normalizedPath);
        List<DecodedTrack> decodedTracks = decoder.decodeAll(document);
        NativeProgram program = compiler.compile(document, decodedTracks);
        MidiPlan midi = midiProjector.project(program);
        boolean renderableAudio = audioRenderer.hasRenderableAudio(program);
        long durationMillis = estimateDurationMillis(midi);
        if (renderableAudio) {
            durationMillis = Math.max(
                    durationMillis,
                    audioRenderer.estimateLinearDurationMillis(program));
        }
        return new ApplicationTrack(
                normalizedPath,
                document,
                decodedTracks,
                program,
                midi,
                cleanInfoText(document.lastInfoText("titl")),
                cleanInfoText(document.lastInfoText("copy")),
                durationMillis,
                renderableAudio);
    }

    PlaybackContent preparePlayback(ApplicationTrack track) {
        if (track == null) {
            throw new IllegalArgumentException("Loaded track is required.");
        }
        AudioPlaybackSource pcm = track.renderableAudio
                ? audioRenderer.preparePlayback(track.program)
                : null;
        return new PlaybackContent(
                track.hasMidi() ? track.midi : null,
                pcm,
                track.program);
    }

    static String cleanInfoText(String value) {
        if (value == null) {
            return "";
        }
        int nul = value.indexOf('\0');
        String cleaned = nul >= 0 ? value.substring(0, nul) : value;
        return cleaned.trim();
    }

    static long estimateDurationMillis(MidiPlan midi) {
        if (midi == null || midi.tempoPoints.isEmpty()) {
            return 0L;
        }
        long endTick = midi.loopInfo.hasLoop
                ? Math.max(1L, midi.loopInfo.loopEndMidiTick)
                : Math.max(1L, midi.totalMidiTicks);
        double seconds = 0.0;
        long previousTick = 0L;
        int currentMpqn = midi.tempoPoints.get(0).mpqn;
        for (MidiPlan.TempoPoint point : midi.tempoPoints) {
            if (point.midiTick <= 0L) {
                currentMpqn = point.mpqn;
                continue;
            }
            if (point.midiTick >= endTick) {
                break;
            }
            long deltaTicks = point.midiTick - previousTick;
            seconds += ticksToSeconds(deltaTicks, currentMpqn);
            previousTick = point.midiTick;
            currentMpqn = point.mpqn;
        }
        seconds += ticksToSeconds(endTick - previousTick, currentMpqn);
        return Math.max(0L, Math.round(seconds * 1000.0));
    }

    private static double ticksToSeconds(long deltaTicks, int mpqn) {
        return ((double) deltaTicks / (double) MidiPlan.MIDI_PPQ)
                * ((double) mpqn / 1000000.0);
    }
}
