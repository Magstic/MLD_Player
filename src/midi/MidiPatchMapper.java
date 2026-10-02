package midi;

import mld.semantic.MelodyProgram;

/** Maps native bank/program state to MIDI patches. */
final class MidiPatchMapper {
    private static final int[] LOW_BANK_PROGRAMS = {0, 9, 16, 24, 13, 74};

    private MidiPatchMapper() {
    }

    static HostPatch translate(MelodyProgram.ChannelSnapshot channel, boolean percussionLane) {
        int program = channel.program & 63;
        int bank = channel.bank & 63;
        // Preserve the observed internal word for evidence; only its low 7 bits
        // select a melodic MIDI program. Its high bits are not MIDI Bank Select.
        int word = bank < 2
                ? (program < LOW_BANK_PROGRAMS.length ? LOW_BANK_PROGRAMS[program] : 0)
                : program | ((bank & 1) << 6) | ((bank & 62) << 7);
        // Channels containing percussion notes use program 0 throughout the song.
        return new HostPatch(percussionLane ? 0 : word & 127, word, channel);
    }

    static final class HostPatch {
        final int program;
        final int rawPatchWord;
        final MelodyProgram.ChannelSnapshot channel;

        HostPatch(int program, int rawPatchWord, MelodyProgram.ChannelSnapshot channel) {
            this.program = program;
            this.rawPatchWord = rawPatchWord;
            this.channel = channel;
        }
    }
}
