package playback;

import java.util.ArrayList;
import java.util.List;

import javax.sound.midi.MidiMessage;
import javax.sound.midi.Receiver;
import javax.sound.midi.ShortMessage;


/** Regression audit for pure MIDI playback infrastructure behavior. */
public final class PlaybackInfrastructureAudit {
    private PlaybackInfrastructureAudit() {
    }

    public static void main(String[] args) throws Exception {
        auditMasterVolumeScaling();
        auditPcmMasterVolumeScaling();
        auditFluidSynthProtocol();
        System.out.println("PlaybackInfrastructureAudit: PASS");
    }

    private static void auditMasterVolumeScaling() throws Exception {
        RecordingReceiver raw = new RecordingReceiver();
        MasterVolume master = new MasterVolume(64);
        MasterVolumeReceiver receiver = new MasterVolumeReceiver(raw, master.getValue());
        master.attach(receiver);
        raw.clear();

        receiver.send(shortMessage(ShortMessage.CONTROL_CHANGE, 2, 7, 100), 77L);
        ShortMessage scaled = raw.lastShortMessage();
        eq("scaled command", ShortMessage.CONTROL_CHANGE, scaled.getCommand());
        eq("scaled channel", 2, scaled.getChannel());
        eq("scaled controller", 7, scaled.getData1());
        eq("scaled value", MasterVolume.scaleChannelVolume(100, 64), scaled.getData2());
        eqLong("scaled timestamp", 77L, raw.lastTimestamp);

        raw.clear();
        master.setValue(127);
        eq("master refresh channel count", 16, raw.messages.size());
        ShortMessage channelTwo = raw.shortMessageAt(2);
        eq("master refresh channel", 2, channelTwo.getChannel());
        eq("master refresh value", 100, channelTwo.getData2());

        master.detach(receiver);
        receiver.close();
    }


    private static void auditPcmMasterVolumeScaling() {
        if (!MasterVolumeTarget.class.isAssignableFrom(PcmPlaybackParticipant.class)) {
            throw new AssertionError("PCM participant is not attached to shared master-volume contract");
        }
        eq("PCM full positive", 12000, PcmPlaybackParticipant.scalePcmSample(12000, 127));
        eq("PCM full negative", -12000, PcmPlaybackParticipant.scalePcmSample(-12000, 127));
        eq("PCM mute positive", 0, PcmPlaybackParticipant.scalePcmSample(12000, 0));
        eq("PCM mute negative", 0, PcmPlaybackParticipant.scalePcmSample(-12000, 0));
        eq("PCM half positive", 6047, PcmPlaybackParticipant.scalePcmSample(12000, 64));
        eq("PCM half negative", -6047, PcmPlaybackParticipant.scalePcmSample(-12000, 64));
        eq("PCM minimum full", Short.MIN_VALUE,
                PcmPlaybackParticipant.scalePcmSample(Short.MIN_VALUE, 127));
        eq("PCM maximum full", Short.MAX_VALUE,
                PcmPlaybackParticipant.scalePcmSample(Short.MAX_VALUE, 127));

        byte[] stereo = pcm16le(12000, -12000, Short.MAX_VALUE, Short.MIN_VALUE);
        PcmPlaybackParticipant.applyMasterVolume(stereo, 0);
        for (int offset = 0; offset < stereo.length; offset += 2) {
            eq("PCM mute byte pair " + offset, 0, pcm16leAt(stereo, offset));
        }

        RecordingVolumeTarget target = new RecordingVolumeTarget();
        MasterVolume master = new MasterVolume(96);
        master.attach(target);
        eq("shared target initial volume", 96, target.value);
        master.setValue(31);
        eq("shared target live volume", 31, target.value);
        master.detach(target);
    }

    private static byte[] pcm16le(int... samples) {
        byte[] bytes = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            int sample = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, samples[i]));
            bytes[i * 2] = (byte) (sample & 0xFF);
            bytes[i * 2 + 1] = (byte) ((sample >>> 8) & 0xFF);
        }
        return bytes;
    }

    private static int pcm16leAt(byte[] bytes, int offset) {
        return (short) ((bytes[offset] & 0xFF) | (bytes[offset + 1] << 8));
    }

    private static final class RecordingVolumeTarget implements MasterVolumeTarget {
        int value = -1;

        @Override
        public void setMasterVolume(int value) {
            this.value = value;
        }
    }

    private static void auditFluidSynthProtocol() throws Exception {
        eqText("note on", "noteon 3 60 90",
                FluidSynthBackend.commandFor(shortMessage(ShortMessage.NOTE_ON, 3, 60, 90)));
        eqText("zero velocity note on", "noteoff 3 60",
                FluidSynthBackend.commandFor(shortMessage(ShortMessage.NOTE_ON, 3, 60, 0)));
        eqText("note off", "noteoff 4 61",
                FluidSynthBackend.commandFor(shortMessage(ShortMessage.NOTE_OFF, 4, 61, 12)));
        eqText("control", "cc 5 10 64",
                FluidSynthBackend.commandFor(shortMessage(ShortMessage.CONTROL_CHANGE, 5, 10, 64)));
        eqText("program", "prog 6 12",
                FluidSynthBackend.commandFor(shortMessage(ShortMessage.PROGRAM_CHANGE, 6, 12, 0)));
        eqText("bend", "pitch_bend 7 8193",
                FluidSynthBackend.commandFor(shortMessage(ShortMessage.PITCH_BEND, 7, 1, 64)));
        eqText("gain zero", "gain 0.0000", FluidSynthBackend.gainCommand(0));
        eqText("gain full", "gain 0.2000", FluidSynthBackend.gainCommand(127));
    }

    private static ShortMessage shortMessage(int command, int channel, int data1, int data2) throws Exception {
        ShortMessage message = new ShortMessage();
        message.setMessage(command, channel, data1, data2);
        return message;
    }

    private static void eq(String label, int expected, int actual) {
        if (expected != actual) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }

    private static void eqLong(String label, long expected, long actual) {
        if (expected != actual) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }

    private static void eqDouble(String label, double expected, double actual) {
        if (Math.abs(expected - actual) > 0.000001) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }

    private static void eqText(String label, String expected, String actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }

    private static final class RecordingReceiver implements Receiver {
        final List<MidiMessage> messages = new ArrayList<MidiMessage>();
        long lastTimestamp;

        @Override
        public void send(MidiMessage message, long timeStamp) {
            messages.add((MidiMessage) message.clone());
            lastTimestamp = timeStamp;
        }

        @Override
        public void close() {
        }

        void clear() {
            messages.clear();
            lastTimestamp = 0L;
        }

        ShortMessage lastShortMessage() {
            return shortMessageAt(messages.size() - 1);
        }

        ShortMessage shortMessageAt(int index) {
            return (ShortMessage) messages.get(index);
        }
    }
}
