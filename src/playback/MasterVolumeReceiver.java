package playback;

import java.util.Arrays;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.Receiver;
import javax.sound.midi.ShortMessage;

/** Applies GUI master volume while preserving per-channel CC7 state. */
final class MasterVolumeReceiver implements Receiver, MasterVolumeTarget {
    private final Receiver delegate;
    private final int[] channelVolumes = new int[16];
    private int masterVolume = -1;
    private boolean closed;

    MasterVolumeReceiver(Receiver delegate, int masterVolume) {
        this.delegate = delegate;
        Arrays.fill(channelVolumes, 100);
        setMasterVolume(masterVolume);
    }

    @Override
    public synchronized void send(MidiMessage message, long timeStamp) {
        if (closed) {
            return;
        }
        if (message instanceof ShortMessage) {
            ShortMessage source = (ShortMessage) message;
            if (source.getCommand() == ShortMessage.CONTROL_CHANGE && source.getData1() == 7) {
                int channel = source.getChannel();
                channelVolumes[channel] = source.getData2();
                if (delegate instanceof MasterVolumeTarget) {
                    delegate.send(message, timeStamp);
                } else {
                    sendChannelVolume(channel, timeStamp);
                }
                return;
            }
        }
        delegate.send(message, timeStamp);
    }

    @Override
    public synchronized void setMasterVolume(int value) {
        int clamped = MasterVolume.clamp(value);
        if (closed || clamped == masterVolume) {
            return;
        }
        masterVolume = clamped;
        if (delegate instanceof MasterVolumeTarget) {
            ((MasterVolumeTarget) delegate).setMasterVolume(clamped);
            return;
        }
        for (int channel = 0; channel < channelVolumes.length; channel++) {
            sendChannelVolume(channel, -1L);
        }
    }

    private void sendChannelVolume(int channel, long timeStamp) {
        ShortMessage volume = new ShortMessage();
        try {
            volume.setMessage(
                    ShortMessage.CONTROL_CHANGE,
                    channel,
                    7,
                    MasterVolume.scaleChannelVolume(channelVolumes[channel], masterVolume));
            delegate.send(volume, timeStamp);
        } catch (InvalidMidiDataException ignored) {
            // Values are clamped above; keep playback alive if a provider rejects the message.
        }
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            closed = true;
            delegate.close();
        }
    }
}
