package playback;

import javax.sound.midi.MidiUnavailableException;

interface PlaybackReceiverHealth {
    void checkHealth() throws MidiUnavailableException;
}
