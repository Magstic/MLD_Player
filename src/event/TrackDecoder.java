package event;

import java.io.IOException;
import java.util.Vector;

import container.MldFile;
import container.TrackChunk;

public final class TrackDecoder {
    public TrackDecodeResult decode(MldFile file, TrackChunk track) throws IOException {
        Vector events = new Vector();
        byte[] payload = track.payload;
        int offset = 0;
        int rawTick = 0;
        int pendingExtendedDelta = 0;
        int eventIndex = 0;

        while (offset < payload.length) {
            int deltaLow;
            int delta;
            int status;

            if (offset + 2 > payload.length) {
                throw new IOException("Truncated event in track " + track.index + " at 0x" + Integer.toHexString(offset));
            }

            deltaLow = payload[offset] & 0xFF;
            delta = deltaLow + pendingExtendedDelta;
            pendingExtendedDelta = 0;
            status = payload[offset + 1] & 0xFF;
            offset += 2;
            rawTick += delta;

            if (status == 0x7F) {
                int command;
                boolean longForm;

                if (offset >= payload.length) {
                    throw new IOException("Truncated 7F resource event in track " + track.index);
                }

                command = payload[offset] & 0xFF;
                offset++;
                longForm = command >= 0xF0;

                if (longForm) {
                    int length;
                    if (offset + 2 > payload.length) {
                        throw new IOException("Truncated 7F long resource event in track " + track.index);
                    }
                    length = readBe16(payload, offset);
                    offset += 2;
                    if (offset + length > payload.length) {
                        throw new IOException("7F long resource payload overruns track " + track.index);
                    }
                    offset += length;
                } else {
                    int bodyLength = bodyLengthForResourceCommand(command, file.exstSize);
                    if (offset + bodyLength > payload.length) {
                        throw new IOException("Truncated 7F resource body in track " + track.index);
                    }
                    offset += bodyLength;
                }

                events.addElement(new ResourceEvent(track.index, eventIndex++, rawTick));
                continue;
            }

            if (status == 0xFF) {
                int command;
                if (offset >= payload.length) {
                    throw new IOException("Truncated FF event in track " + track.index);
                }
                command = payload[offset] & 0xFF;
                offset++;

                if (command >= 0xF0) {
                    int length;
                    if (offset + 2 > payload.length) {
                        throw new IOException("Truncated machine-dependent event in track " + track.index);
                    }

                    length = readBe16(payload, offset);
                    offset += 2;
                    if (offset + length > payload.length) {
                        throw new IOException("Machine-dependent payload overruns track " + track.index);
                    }

                    offset += length;
                    events.addElement(new MachineDependentEvent(track.index, eventIndex++, rawTick));
                    continue;
                }

                if (offset >= payload.length) {
                    throw new IOException("Truncated system event value in track " + track.index);
                }

                int value = payload[offset] & 0xFF;
                int part;
                int timebase;
                offset++;
                if (command == 0xDC) {
                    pendingExtendedDelta = value << 8;
                }
                part = (command >= 0xE0 && command <= 0xEF) ? ((value >> 6) & 0x03) : -1;
                timebase = (command >= 0xC0 && command <= 0xCF) ? timebaseForTempoCommand(command) : -1;
                events.addElement(new SystemEvent(
                        track.index,
                        eventIndex++,
                        rawTick,
                        command,
                        value,
                        commandName(command),
                        part,
                        timebase));
                continue;
            }

            if (offset >= payload.length) {
                throw new IOException("Truncated note gate in track " + track.index);
            }

            {
                int gate = payload[offset] & 0xFF;
                int velocity = 63;
                int octaveShift = 0;
                int noteExtraBytes = file.noteExtraBytes;
                offset++;

                if (noteExtraBytes > 0) {
                    if (offset >= payload.length) {
                        throw new IOException("Truncated note attr in track " + track.index);
                    }
                    int attr = payload[offset] & 0xFF;
                    offset++;
                    velocity = (attr >> 2) & 0x3F;
                    octaveShift = attr & 0x03;
                    if (noteExtraBytes > 1) {
                        int skip = noteExtraBytes - 1;
                        if (offset + skip > payload.length) {
                            throw new IOException("Truncated note extra bytes in track " + track.index);
                        }
                        offset += skip;
                    }
                }

                events.addElement(new NoteEvent(
                        track.index,
                        eventIndex++,
                        rawTick,
                        (status >> 6) & 0x03,
                        status & 0x3F,
                        gate,
                        velocity,
                        octaveShift,
                        noteExtraBytes));
            }
        }

        return new TrackDecodeResult(rawTick, events);
    }

    public static String commandName(int command) {
        switch (command) {
            case 0xB0:
                return "master_volume";
            case 0xBA:
                return "patch_mode";
            case 0xC0:
                return "tempo_tb_6";
            case 0xC1:
                return "tempo_tb_12";
            case 0xC2:
                return "tempo_tb_24";
            case 0xC3:
                return "tempo_tb_48";
            case 0xC4:
                return "tempo_tb_96";
            case 0xC5:
                return "tempo_tb_192";
            case 0xC6:
                return "tempo_tb_384";
            case 0xC8:
                return "tempo_tb_15";
            case 0xC9:
                return "tempo_tb_30";
            case 0xCA:
                return "tempo_tb_60";
            case 0xCB:
                return "tempo_tb_120";
            case 0xCC:
                return "tempo_tb_240";
            case 0xCD:
                return "tempo_tb_480";
            case 0xCE:
                return "tempo_tb_960";
            case 0xD0:
                return "cue_point";
            case 0xDC:
                return "extended_delta";
            case 0xDD:
                return "loop_point";
            case 0xDE:
                return "nop";
            case 0xDF:
                return "end_of_track";
            case 0xE0:
                return "program_change";
            case 0xE1:
                return "bank_change";
            case 0xE2:
                return "channel_volume";
            case 0xE3:
                return "pan";
            case 0xE4:
                return "pitch_bend";
            case 0xE5:
                return "channel_assign";
            case 0xE6:
                return "expression";
            case 0xE7:
                return "pitch_bend_range";
            case 0xE8:
                return "fine_pitch_or_pcm_volume";
            case 0xE9:
                return "fine_pitch_or_pcm_pan";
            case 0xEA:
                return "modulation_depth";
            case 0xFF:
                return "machine_dependent";
            default:
                return "cmd_" + hex2(command);
        }
    }

    private static int readBe16(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    private static int bodyLengthForResourceCommand(int command, int exstSize) {
        switch (command) {
            case 0x80:
            case 0x81:
            case 0x90:
                return 1;
            default:
                if (command < 0x80) {
                    return 1 + max(0, exstSize);
                }
                return 1;
        }
    }

    private static int timebaseForTempoCommand(int command) {
        switch (command & 0x0F) {
            case 0x0:
                return 6;
            case 0x1:
                return 12;
            case 0x2:
                return 24;
            case 0x3:
                return 48;
            case 0x4:
                return 96;
            case 0x5:
                return 192;
            case 0x6:
                return 384;
            case 0x8:
                return 15;
            case 0x9:
                return 30;
            case 0xA:
                return 60;
            case 0xB:
                return 120;
            case 0xC:
                return 240;
            case 0xD:
                return 480;
            case 0xE:
                return 960;
            default:
                return -1;
        }
    }

    private static int max(int left, int right) {
        return left > right ? left : right;
    }

    private static String hex2(int value) {
        String hex = Integer.toHexString(value & 0xFF).toUpperCase();
        return hex.length() < 2 ? "0" + hex : hex;
    }
}
