package playback;

public final class MidiMessageConstants {
    public static final int NOTE_OFF = 0x80;
    public static final int NOTE_ON = 0x90;
    public static final int CONTROL_CHANGE = 0xB0;
    public static final int PROGRAM_CHANGE = 0xC0;
    public static final int CHANNEL_PRESSURE = 0xD0;
    public static final int PITCH_BEND = 0xE0;

    private MidiMessageConstants() {
    }

    public static int statusByte(int command, int channel) {
        return (command & 0xF0) | (channel & 0x0F);
    }

    public static boolean usesSingleDataByte(int command) {
        return command == PROGRAM_CHANGE || command == CHANNEL_PRESSURE;
    }
}
