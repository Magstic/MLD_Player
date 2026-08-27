package playback;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.sound.midi.MidiDevice;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.Sequencer;

/** Discovers selectable host MIDI outputs without opening playback resources. */
public final class MidiOutputCatalog {
    private static final MidiOutput SYSTEM_GM = new MidiOutput(
            "System GM", MidiOutput.Backend.JAVA_SYNTH, null, null, null);
    private static final MidiOutput VIRTUAL_MIDI_SYNTH = new MidiOutput(
            "VirtualMIDISynth", MidiOutput.Backend.MIDI_DEVICE, "VirtualMIDISynth", null, null);
    private static final MidiOutput FLUID_SYNTH = FluidSynthBackend.discoverOutput();

    private MidiOutputCatalog() {
    }

    public static List<MidiOutput> availableOutputs() {
        List<MidiOutput> outputs = new ArrayList<MidiOutput>();
        outputs.add(SYSTEM_GM);
        if (findReceiverDevice(VIRTUAL_MIDI_SYNTH.deviceNameFragment) != null) {
            outputs.add(VIRTUAL_MIDI_SYNTH);
        }
        if (FLUID_SYNTH != null) {
            outputs.add(FLUID_SYNTH);
        }
        return Collections.unmodifiableList(outputs);
    }

    static MidiOutput preferredAutomaticOutput() {
        String preference = System.getProperty("mld.midi.device", "auto");
        if ("default".equalsIgnoreCase(preference) || "gervill".equalsIgnoreCase(preference)) {
            return SYSTEM_GM;
        }
        if (!"auto".equalsIgnoreCase(preference) && !"virtual".equalsIgnoreCase(preference)) {
            MidiDevice requested = findReceiverDevice(preference);
            if (requested != null) {
                return new MidiOutput(
                        requested.getDeviceInfo().getName(),
                        MidiOutput.Backend.MIDI_DEVICE,
                        preference,
                        null,
                        null);
            }
        }
        if (findReceiverDevice(VIRTUAL_MIDI_SYNTH.deviceNameFragment) != null) {
            return VIRTUAL_MIDI_SYNTH;
        }
        return SYSTEM_GM;
    }

    static MidiDevice findReceiverDevice(String nameFragment) {
        if (nameFragment == null || nameFragment.isEmpty()) {
            return null;
        }
        MidiDevice.Info[] infos = MidiSystem.getMidiDeviceInfo();
        for (MidiDevice.Info info : infos) {
            try {
                MidiDevice device = MidiSystem.getMidiDevice(info);
                if (device instanceof Sequencer || device.getMaxReceivers() == 0) {
                    continue;
                }
                String name = info.getName();
                if (name != null && name.toLowerCase().contains(nameFragment.toLowerCase())) {
                    return device;
                }
            } catch (MidiUnavailableException ignored) {
                // Unavailable devices are not selectable output candidates.
            }
        }
        return null;
    }
}
