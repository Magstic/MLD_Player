package event;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import container.MldFile;
import container.TrackChunk;

public final class TrackDecoder {
    private static final Map<Integer, Integer> TIMEBASES = createTimebases();
    private static final Map<Integer, String> COMMAND_NAMES = createCommandNames();
    private static final Map<Integer, String> RESOURCE_NAMES = createResourceNames();

    public TrackDecodeResult decode(MldFile file, TrackChunk track) throws IOException {
        List<TrackEvent> events = new ArrayList<TrackEvent>();
        List<String> warnings = new ArrayList<String>();
        byte[] payload = track.payload;
        int offset = 0;
        int rawTick = 0;
        int pendingExtendedDelta = 0;
        int eventIndex = 0;
        int noteCount = 0;
        int resourceCount = 0;
        int systemCount = 0;
        int machineCount = 0;

        while (offset < payload.length) {
            if (offset + 2 > payload.length) {
                throw new IOException("Truncated event in track " + track.index + " at 0x" + Integer.toHexString(offset));
            }

            int deltaLow = payload[offset] & 0xFF;
            int delta = deltaLow + pendingExtendedDelta;
            pendingExtendedDelta = 0;
            int status = payload[offset + 1] & 0xFF;
            offset += 2;
            rawTick += delta;

            if (status == 0x7F) {
                if (offset >= payload.length) {
                    throw new IOException("Truncated 7F resource event in track " + track.index);
                }

                int command = payload[offset] & 0xFF;
                offset += 1;
                boolean longForm = command >= 0xF0;
                byte[] body;

                if (longForm) {
                    if (offset + 2 > payload.length) {
                        throw new IOException("Truncated 7F long resource event in track " + track.index);
                    }
                    int length = readBe16(payload, offset);
                    offset += 2;
                    if (offset + length > payload.length) {
                        throw new IOException("7F long resource payload overruns track " + track.index);
                    }
                    body = Arrays.copyOfRange(payload, offset, offset + length);
                    offset += length;
                } else {
                    int bodyLength = bodyLengthForResourceCommand(command, file.exstSize);
                    if (offset + bodyLength > payload.length) {
                        throw new IOException("Truncated 7F resource body in track " + track.index);
                    }
                    body = Arrays.copyOfRange(payload, offset, offset + bodyLength);
                    offset += bodyLength;
                }

                events.add(new ResourceEvent(
                        track.index,
                        eventIndex++,
                        delta,
                        rawTick,
                        command,
                        resourceName(command),
                        body,
                        longForm));
                resourceCount += 1;
                continue;
            }

            if (status == 0xFF) {
                if (offset >= payload.length) {
                    throw new IOException("Truncated FF event in track " + track.index);
                }

                int command = payload[offset] & 0xFF;
                offset += 1;

                if (command >= 0xF0) {
                    if (offset + 2 > payload.length) {
                        throw new IOException("Truncated machine-dependent event in track " + track.index);
                    }

                    int length = readBe16(payload, offset);
                    offset += 2;
                    if (offset + length > payload.length) {
                        throw new IOException("Machine-dependent payload overruns track " + track.index);
                    }

                    byte[] body = Arrays.copyOfRange(payload, offset, offset + length);
                    offset += length;
                    events.add(new MachineDependentEvent(track.index, eventIndex++, delta, rawTick, command, body));
                    machineCount += 1;
                    continue;
                }

                if (offset >= payload.length) {
                    throw new IOException("Truncated system event value in track " + track.index);
                }

                int value = payload[offset] & 0xFF;
                offset += 1;
                if (command == 0xDC) {
                    pendingExtendedDelta = value << 8;
                }
                int part = (command >= 0xE0 && command <= 0xEF) ? ((value >> 6) & 0x03) : -1;
                int timebase = TIMEBASES.containsKey(command & 0x0F) && command >= 0xC0 && command <= 0xCF
                        ? TIMEBASES.get(command & 0x0F)
                        : -1;
                events.add(new SystemEvent(
                        track.index,
                        eventIndex++,
                        delta,
                        rawTick,
                        command,
                        value,
                        commandName(command),
                        part,
                        timebase));
                systemCount += 1;
                continue;
            }

            if (offset >= payload.length) {
                throw new IOException("Truncated note gate in track " + track.index);
            }

            int gate = payload[offset] & 0xFF;
            offset += 1;
            int velocity = 63;
            int octaveShift = 0;
            int noteExtraBytes = file.noteExtraBytes;

            if (noteExtraBytes > 0) {
                // Parse the first extra byte for velocity and octave shift
                if (offset >= payload.length) {
                    throw new IOException("Truncated note attr in track " + track.index);
                }
                int attr = payload[offset] & 0xFF;
                offset += 1;
                velocity = (attr >> 2) & 0x3F;
                octaveShift = attr & 0x03;
                // Skip remaining reserved extra bytes (note > 1)
                if (noteExtraBytes > 1) {
                    int skip = noteExtraBytes - 1;
                    if (offset + skip > payload.length) {
                        throw new IOException("Truncated note extra bytes in track " + track.index);
                    }
                    offset += skip;
                }
            } else {
                warnings.add("Track " + track.index + " uses 3-byte note layout fallback");
            }

            events.add(new NoteEvent(
                    track.index,
                    eventIndex++,
                    delta,
                    rawTick,
                    status,
                    (status >> 6) & 0x03,
                    status & 0x3F,
                    gate,
                    velocity,
                    octaveShift,
                    noteExtraBytes));
            noteCount += 1;
        }

        return new TrackDecodeResult(
                track.index,
                payload.length,
                rawTick,
                noteCount,
                resourceCount,
                systemCount,
                machineCount,
                events,
                warnings);
    }

    public static String commandName(int command) {
        String name = COMMAND_NAMES.get(command);
        return name != null ? name : String.format("cmd_%02X", command);
    }

    public static String resourceName(int command) {
        String name = RESOURCE_NAMES.get(command);
        return name != null ? name : String.format("res_%02X", command);
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
                    return 1 + Math.max(0, exstSize);
                }
                return 1;
        }
    }

    private static Map<Integer, Integer> createTimebases() {
        Map<Integer, Integer> map = new HashMap<Integer, Integer>();
        map.put(0x0, 6);
        map.put(0x1, 12);
        map.put(0x2, 24);
        map.put(0x3, 48);
        map.put(0x4, 96);
        map.put(0x5, 192);
        map.put(0x6, 384);
        map.put(0x8, 15);
        map.put(0x9, 30);
        map.put(0xA, 60);
        map.put(0xB, 120);
        map.put(0xC, 240);
        map.put(0xD, 480);
        map.put(0xE, 960);
        return map;
    }

    private static Map<Integer, String> createCommandNames() {
        Map<Integer, String> map = new HashMap<Integer, String>();
        map.put(0xB0, "master_volume");
        map.put(0xB1, "master_balance");
        map.put(0xB3, "master_tuning");
        map.put(0xBA, "patch_mode");
        map.put(0xBC, "relative_tempo");
        map.put(0xBD, "master_volume");
        map.put(0xBE, "global_stop");
        map.put(0xBF, "session_reset");
        map.put(0xC0, "tempo_tb_6");
        map.put(0xC1, "tempo_tb_12");
        map.put(0xC2, "tempo_tb_24");
        map.put(0xC3, "tempo_tb_48");
        map.put(0xC4, "tempo_tb_96");
        map.put(0xC5, "tempo_tb_192");
        map.put(0xC6, "tempo_tb_384");
        map.put(0xC8, "tempo_tb_15");
        map.put(0xC9, "tempo_tb_30");
        map.put(0xCA, "tempo_tb_60");
        map.put(0xCB, "tempo_tb_120");
        map.put(0xCC, "tempo_tb_240");
        map.put(0xCD, "tempo_tb_480");
        map.put(0xCE, "tempo_tb_960");
        map.put(0xD0, "cue_point");
        map.put(0xDC, "extended_delta");
        map.put(0xDD, "loop_point");
        map.put(0xDE, "nop");
        map.put(0xDF, "end_of_track");
        map.put(0xE0, "program_change");
        map.put(0xE1, "bank_change");
        map.put(0xE2, "channel_volume");
        map.put(0xE3, "pan");
        map.put(0xE4, "pitch_bend");
        map.put(0xE5, "channel_assign");
        map.put(0xE6, "expression");
        map.put(0xE7, "pitch_bend_range");
        map.put(0xE8, "fine_pitch_or_pcm_volume");
        map.put(0xE9, "fine_pitch_or_pcm_pan");
        map.put(0xEA, "modulation_depth");
        map.put(0xFF, "machine_dependent");
        return map;
    }

    private static Map<Integer, String> createResourceNames() {
        Map<Integer, String> map = new HashMap<Integer, String>();
        map.put(0x00, "resource_start");
        map.put(0x01, "resource_stop");
        map.put(0x80, "resource_audio_level");
        map.put(0x81, "resource_audio_pan");
        map.put(0x90, "resource_channel_config");
        map.put(0xF0, "resource_auxiliary");
        return map;
    }
}
