package playback;

import java.util.ArrayList;
import java.util.List;

/** Shared live master-volume state for active MIDI and PCM playback targets. */
public final class MasterVolume {
    private int value;
    private final List<MasterVolumeTarget> targets = new ArrayList<MasterVolumeTarget>();

    public MasterVolume(int value) {
        this.value = clamp(value);
    }

    public synchronized int getValue() {
        return value;
    }

    public synchronized void setValue(int value) {
        this.value = clamp(value);
        for (MasterVolumeTarget target : targets) {
            target.setMasterVolume(this.value);
        }
    }

    synchronized void attach(MasterVolumeTarget target) {
        if (!targets.contains(target)) {
            targets.add(target);
        }
        target.setMasterVolume(value);
    }

    synchronized void detach(MasterVolumeTarget target) {
        targets.remove(target);
    }

    static int scaleChannelVolume(int channelVolume, int masterVolume) {
        int source = clamp(channelVolume);
        int master = clamp(masterVolume);
        return (source * master + 63) / 127;
    }

    static int clamp(int value) {
        return Math.max(0, Math.min(127, value));
    }
}
