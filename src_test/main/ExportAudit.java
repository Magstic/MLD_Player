package main;

import java.nio.file.Path;
import java.nio.file.Paths;

import bridge.midi.MidiBridgeExporter;
import event.NoteEvent;
import event.TrackDecodeResult;
import event.TrackEvent;
import timeline.PlaybackTimeline;

/**
 * Archived one-off melody regression helper used to compare Java exports
 * against the authoritative official MIDI for MLD.
 */
public final class ExportAudit {
    public static void main(String[] args) throws Exception {
        Path input = Paths.get(args[0]).toAbsolutePath().normalize();
        Path output = Paths.get(args[1]).toAbsolutePath().normalize();
        PlaybackTimeline timeline = Cli.buildTimeline(input);
        new MidiBridgeExporter().export(timeline, input, output);
        System.out.println("notes=" + timeline.notes.size());
        System.out.println("controls=" + timeline.mappedControls.size());
        for (PlaybackTimeline.CompiledNote note : timeline.notes) {
            if (note.midiStartTick >= 190000L && note.midiStartTick <= 262000L) {
                System.out.println(
                        "FOCUS ch=" + note.midiChannel
                                + " note=" + note.midiNote
                                + " start=" + note.midiStartTick
                                + " end=" + note.midiEndTick
                                + " track=" + note.sourceTrack
                                + " voice=" + note.sourceVoice
                                + " rawStart=" + note.rawStartTick
                                + " rawEnd=" + note.rawEndTick
                                + " vel=" + note.velocity);
            }
        }
        for (TrackDecodeResult track : timeline.decodedTracks) {
            for (TrackEvent event : track.events) {
                if (!(event instanceof NoteEvent)) {
                    continue;
                }
                NoteEvent note = (NoteEvent) event;
                if (note.rawTick >= 4900 && note.rawTick <= 6600) {
                    System.out.println(
                            "RAW track=" + note.trackIndex
                                    + " voice=" + note.voice
                                    + " rawTick=" + note.rawTick
                                    + " delta=" + note.delta
                                    + " pitch=" + note.pitch
                                    + " gate=" + note.gate
                                    + " vel=" + note.velocity
                                    + " oct=" + note.octaveShift);
                }
            }
        }
    }
}
