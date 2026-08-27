package playback;

import javax.sound.midi.MidiDevice;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.Receiver;
import javax.sound.midi.Synthesizer;

/** Owns one opened playback output and its receiver/volume lifecycle. */
final class MidiOutputConnection implements AutoCloseable {
    private final Synthesizer synthesizer;
    private final MidiDevice outputDevice;
    private final Receiver rawReceiver;
    private final Receiver receiver;
    private final MasterVolume masterVolume;
    private final MasterVolumeReceiver volumeReceiver;
    private final MidiOutput output;

    private MidiOutputConnection(
            Synthesizer synthesizer,
            MidiDevice outputDevice,
            Receiver rawReceiver,
            Receiver receiver,
            MasterVolume masterVolume,
            MasterVolumeReceiver volumeReceiver,
            MidiOutput output) {
        this.synthesizer = synthesizer;
        this.outputDevice = outputDevice;
        this.rawReceiver = rawReceiver;
        this.receiver = receiver;
        this.masterVolume = masterVolume;
        this.volumeReceiver = volumeReceiver;
        this.output = output;
    }

    static MidiOutputConnection open(MidiOutput requestedOutput, MasterVolume masterVolume)
            throws MidiUnavailableException {
        boolean explicit = requestedOutput != null;
        MidiOutput output = explicit ? requestedOutput : MidiOutputCatalog.preferredAutomaticOutput();
        OpenedOutput opened = openRaw(output, explicit);
        Receiver receiver = opened.receiver;
        MasterVolumeReceiver volumeReceiver = null;
        if (masterVolume != null) {
            volumeReceiver = new MasterVolumeReceiver(opened.receiver, masterVolume.getValue());
            masterVolume.attach(volumeReceiver);
            receiver = volumeReceiver;
        }
        return new MidiOutputConnection(
                opened.synthesizer,
                opened.outputDevice,
                opened.receiver,
                receiver,
                masterVolume,
                volumeReceiver,
                opened.output);
    }

    Receiver receiver() {
        return receiver;
    }

    String description() {
        if (output.backend == MidiOutput.Backend.FLUIDSYNTH) {
            return output.displayName;
        }
        if (outputDevice != null) {
            return outputDevice.getDeviceInfo().getName();
        }
        if (synthesizer != null) {
            return synthesizer.getDeviceInfo().getName();
        }
        return "Default Java MIDI output";
    }

    void checkHealth() throws MidiUnavailableException {
        if (rawReceiver instanceof PlaybackReceiverHealth) {
            ((PlaybackReceiverHealth) rawReceiver).checkHealth();
        }
    }

    @Override
    public void close() {
        if (masterVolume != null && volumeReceiver != null) {
            masterVolume.detach(volumeReceiver);
        }
        receiver.close();
        if (synthesizer != null && synthesizer.isOpen()) {
            synthesizer.close();
        }
        if (outputDevice != null && outputDevice.isOpen()) {
            outputDevice.close();
        }
    }

    private static OpenedOutput openRaw(MidiOutput output, boolean explicit) throws MidiUnavailableException {
        if (output.backend == MidiOutput.Backend.FLUIDSYNTH) {
            return new OpenedOutput(null, null, FluidSynthBackend.openReceiver(output), output);
        }
        if (output.backend == MidiOutput.Backend.MIDI_DEVICE) {
            MidiDevice device = MidiOutputCatalog.findReceiverDevice(output.deviceNameFragment);
            if (device == null) {
                if (explicit) {
                    throw new MidiUnavailableException(output.displayName + " is unavailable.");
                }
                return openJavaSynth();
            }
            try {
                device.open();
                return new OpenedOutput(null, device, device.getReceiver(), output);
            } catch (MidiUnavailableException e) {
                if (device.isOpen()) {
                    device.close();
                }
                if (explicit) {
                    throw e;
                }
                return openJavaSynth();
            }
        }
        return openJavaSynth();
    }

    private static OpenedOutput openJavaSynth() throws MidiUnavailableException {
        Synthesizer synthesizer = MidiSystem.getSynthesizer();
        synthesizer.open();
        MidiOutput system = new MidiOutput("System GM", MidiOutput.Backend.JAVA_SYNTH, null, null, null);
        return new OpenedOutput(synthesizer, null, synthesizer.getReceiver(), system);
    }

    private static final class OpenedOutput {
        final Synthesizer synthesizer;
        final MidiDevice outputDevice;
        final Receiver receiver;
        final MidiOutput output;

        OpenedOutput(Synthesizer synthesizer, MidiDevice outputDevice, Receiver receiver, MidiOutput output) {
            this.synthesizer = synthesizer;
            this.outputDevice = outputDevice;
            this.receiver = receiver;
            this.output = output;
        }
    }
}
