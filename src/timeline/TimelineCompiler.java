package timeline;

import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Vector;

import container.MldFile;
import event.MachineDependentEvent;
import event.NoteEvent;
import event.ResourceEvent;
import event.SystemEvent;
import event.TrackDecodeResult;
import event.TrackEvent;
import util.SortUtil;

public final class TimelineCompiler {
    private static final int MIDI_CHANNEL_COUNT = 16;
    private static final int MAX_LOGICAL_CHANNELS = 64;

    private static final int CONTROL_CHANGE = 0xB0;
    private static final int PROGRAM_CHANGE = 0xC0;
    private static final int PITCH_BEND = 0xE0;

    private static final int DEFAULT_LEVEL = 63;
    private static final int DEFAULT_PAN = 32;
    private static final int DEFAULT_PITCH_COARSE = 32;
    private static final int DEFAULT_PITCH_FINE = 32;
    private static final int DEFAULT_PITCH_RANGE = 2;
    private static final int DEFAULT_MODULATION = 0;
    private static final int DEFAULT_VOLUME_CACHE = DEFAULT_LEVEL * 2;

    private static final int ORDINARY_NATIVE_REPLACEMENT_OVERLAP_SAMPLES = 128;
    private static final int ORDINARY_NATIVE_RENDER_OUTPUT_RATE = 32000;
    private static final int HOST_LIVE_MIX_CHASE_STEP_COUNT = 4;

    private static final int PSM_GLOBAL_LEVEL_SCALE = 100;
    private static final int PSM_CHANNEL_LEVEL_SCALE = 100;
    private static final boolean PSM_FORCE_PAN_LEFT_SYNC = false;
    private static final boolean PSM_DEFAULT_USE_INSTRUMENT_SET = true;
    private static final boolean PSM_DEFAULT_LATE_TABLE_FALLBACK = false;
    private static final boolean HOST_PATCH_USE_OBSERVED_ORDINARY_SURFACE = true;
    private static final boolean PSM_DEFAULT_ENABLE_LATE_PATCH_REMAP = false;
    private static final boolean PSM_DEFAULT_GSMODE = false;
    private static final boolean PSM_DEFAULT_DRAMBANKFLG = false;
    private static final boolean PSM_APPLY_WRITER_EXPORT_OUTPUT_REMAP = true;
    private static final int PSM_LATE_PATCH_ENTRY_EMPTY = 0;
    private static final int PSM_LATE_PATCH_ENTRY_MAX = 0x80;
    private static final int PSM_LATE_PATCH_ENTRY_SIDEBAND_SENTINEL = 0x81;
    private static final int PSM_GM_DRUM_CHANNEL = 9;
    private static final int PSM_DEFAULT_AUTHORITATIVE_SPECIAL_MASK = 1 << PSM_GM_DRUM_CHANNEL;

    private static final int[] OCTAVE_TABLE = new int[] { 0, 12, -24, -12 };
    private static final String HOST_LIVE_MIX_CHASE_SUFFIX = "_live_mix_chase";

    public PlaybackTimeline compile(MldFile file, Vector decodedTracks) {
        Vector warnings = new Vector();
        Vector warningKeys = new Vector();
        Vector tempoSeeds = collectTempoSeeds(decodedTracks);
        Vector notes = new Vector();
        Vector mappedControls = new Vector();
        Hashtable activeNotes = new Hashtable();
        Vector tempoPoints;
        TempoMapper mapper;
        PlaybackTimeline.LoopInfo loopInfo;
        Vector orderedEvents;
        ChannelState[] channels = createChannelStates();
        OutputLaneTracker outputLaneTracker = OutputLaneTracker.seededFreshDefaultPlayPath();
        int[] voiceMap = createIdentityVoiceMap(max(16, file.tracks.size() * 4));
        ControlCollector controlCollector = new ControlCollector(mappedControls);
        long totalMidiTicks = 0L;
        int i;

        SortUtil.sort(tempoSeeds, RAW_TEMPO_COMPARATOR);
        tempoPoints = buildTempoPoints(tempoSeeds, warnings);
        mapper = new TempoMapper(tempoPoints);
        loopInfo = determineLoopInfo(decodedTracks, mapper, warnings);
        orderedEvents = collectOrderedEvents(decodedTracks);

        emitInitialMidiDefaults(controlCollector, channels);

        for (i = 0; i < orderedEvents.size(); i++) {
            TrackEvent event = (TrackEvent) orderedEvents.elementAt(i);
            totalMidiTicks = maxLong(totalMidiTicks, flushExpiredNotes(event.rawTick, activeNotes, notes));
            if (event instanceof NoteEvent) {
                totalMidiTicks = maxLong(totalMidiTicks, handleNoteEvent(
                        (NoteEvent) event,
                        mapper,
                        controlCollector,
                        notes,
                        activeNotes,
                        channels,
                        outputLaneTracker,
                        voiceMap,
                        warnings,
                        warningKeys));
            } else if (event instanceof SystemEvent) {
                long midiTick = mapper.rawToMidiTick(event.rawTick);
                totalMidiTicks = maxLong(totalMidiTicks, midiTick);
                handleSystemEvent(
                        (SystemEvent) event,
                        midiTick,
                        controlCollector,
                        channels,
                        outputLaneTracker,
                        voiceMap,
                        warnings,
                        warningKeys);
            } else if (event instanceof ResourceEvent) {
                totalMidiTicks = maxLong(totalMidiTicks, mapper.rawToMidiTick(event.rawTick));
                addWarningOnce(
                        warnings,
                        warningKeys,
                        "resource_playback_unimplemented",
                        "Resource events were parsed but are ignored by the J2ME melody player.");
            } else if (event instanceof MachineDependentEvent) {
                totalMidiTicks = maxLong(totalMidiTicks, mapper.rawToMidiTick(event.rawTick));
                addWarningOnce(
                        warnings,
                        warningKeys,
                        "machine_dependent_unimplemented",
                        "Machine-dependent events were parsed but are ignored by the J2ME melody player.");
            }
        }

        totalMidiTicks = maxLong(totalMidiTicks, flushExpiredNotes(2147483647, activeNotes, notes));
        for (i = 0; i < decodedTracks.size(); i++) {
            TrackDecodeResult track = (TrackDecodeResult) decodedTracks.elementAt(i);
            totalMidiTicks = maxLong(totalMidiTicks, mapper.rawToMidiTick(track.totalRawTicks));
        }
        if (loopInfo.hasLoop) {
            totalMidiTicks = maxLong(totalMidiTicks, loopInfo.loopEndMidiTick);
        }

        mappedControls = applyOrdinaryLiveMixChaseApproximation(
                mappedControls,
                notes,
                tempoPoints,
                loopInfo,
                totalMidiTicks);
        totalMidiTicks = maxLong(totalMidiTicks, maxMappedControlTick(mappedControls));

        {
            int[] outputChannelMap = buildHostOutputChannelMap(outputLaneTracker);
            notes = remapCompiledNotes(notes, outputChannelMap);
            mappedControls = remapMappedControls(mappedControls, outputChannelMap);
        }

        return new PlaybackTimeline(
                file,
                tempoPoints,
                loopInfo,
                notes,
                mappedControls,
                totalMidiTicks,
                warnings);
    }

    private Vector collectOrderedEvents(Vector decodedTracks) {
        Vector events = new Vector();
        int i;
        int j;
        for (i = 0; i < decodedTracks.size(); i++) {
            TrackDecodeResult track = (TrackDecodeResult) decodedTracks.elementAt(i);
            for (j = 0; j < track.events.size(); j++) {
                events.addElement(track.events.elementAt(j));
            }
        }
        SortUtil.sort(events, TRACK_EVENT_COMPARATOR);
        return events;
    }

    private Vector collectTempoSeeds(Vector decodedTracks) {
        Vector seeds = new Vector();
        int i;
        int j;
        for (i = 0; i < decodedTracks.size(); i++) {
            TrackDecodeResult track = (TrackDecodeResult) decodedTracks.elementAt(i);
            for (j = 0; j < track.events.size(); j++) {
                TrackEvent event = (TrackEvent) track.events.elementAt(j);
                if (event instanceof SystemEvent && isTempo((SystemEvent) event)) {
                    SystemEvent systemEvent = (SystemEvent) event;
                    seeds.addElement(new RawTempoPoint(
                            systemEvent.rawTick,
                            systemEvent.timebase > 0 ? systemEvent.timebase : 48,
                            systemEvent.value > 0 ? systemEvent.value : 120,
                            systemEvent.trackIndex,
                            systemEvent.eventIndex,
                            false));
                }
            }
        }
        return seeds;
    }

    private Vector buildTempoPoints(Vector seeds, Vector warnings) {
        Vector points = new Vector();
        long midiTick = 0L;
        int lastRawTick = 0;
        int lastTimebase;
        int i;

        if (seeds.size() == 0) {
            warnings.addElement("No tempo event observed; inserting synthetic 120 BPM / timebase 48 point.");
            seeds.addElement(new RawTempoPoint(0, 48, 120, -1, -1, true));
        } else if (((RawTempoPoint) seeds.elementAt(0)).rawTick > 0) {
            RawTempoPoint first = (RawTempoPoint) seeds.elementAt(0);
            warnings.addElement("First tempo event does not start at tick 0; inserting synthetic point at origin.");
            seeds.insertElementAt(new RawTempoPoint(0, first.timebase, first.tempo, -1, -1, true), 0);
        }

        lastTimebase = ((RawTempoPoint) seeds.elementAt(0)).timebase;
        for (i = 0; i < seeds.size(); i++) {
            RawTempoPoint seed = (RawTempoPoint) seeds.elementAt(i);
            int deltaRaw = seed.rawTick - lastRawTick;
            if (deltaRaw < 0) {
                deltaRaw = 0;
            }
            midiTick += (((long) deltaRaw) * PlaybackTimeline.MIDI_PPQ) / lastTimebase;
            points.addElement(new PlaybackTimeline.TempoPoint(
                    seed.rawTick,
                    midiTick,
                    seed.timebase,
                    seed.tempo,
                    60000000 / max(1, seed.tempo),
                    seed.synthetic));
            lastRawTick = seed.rawTick;
            lastTimebase = seed.timebase;
        }
        return points;
    }

    private PlaybackTimeline.LoopInfo determineLoopInfo(Vector decodedTracks, TempoMapper mapper, Vector warnings) {
        int[] loopStarts = new int[] { -1, -1, -1, -1 };
        int[] loopEnds = new int[] { -1, -1, -1, -1 };
        int[] repeatCounts = new int[] { 0, 0, 0, 0 };
        Vector loopWarnings = new Vector();
        int i;
        int j;
        int chosenSlot = -1;

        for (i = 0; i < decodedTracks.size(); i++) {
            TrackDecodeResult track = (TrackDecodeResult) decodedTracks.elementAt(i);
            for (j = 0; j < track.events.size(); j++) {
                TrackEvent event = (TrackEvent) track.events.elementAt(j);
                if (!(event instanceof SystemEvent)) {
                    continue;
                }
                SystemEvent systemEvent = (SystemEvent) event;
                if (systemEvent.command != 0xDD) {
                    continue;
                }
                {
                    int slot = (systemEvent.value >> 6) & 0x03;
                    int operation = systemEvent.value & 0x03;
                    if (operation == 0x00) {
                        if (loopStarts[slot] < 0 || systemEvent.rawTick < loopStarts[slot]) {
                            loopStarts[slot] = systemEvent.rawTick;
                        }
                    } else if (operation == 0x01) {
                        if (loopEnds[slot] < 0 || systemEvent.rawTick < loopEnds[slot]) {
                            int repeat = (systemEvent.value >> 2) & 0x0F;
                            loopEnds[slot] = systemEvent.rawTick;
                            repeatCounts[slot] = repeat == 0 ? -1 : repeat;
                        }
                    }
                }
            }
        }

        for (i = 0; i < 4; i++) {
            if (loopStarts[i] >= 0
                    && loopEnds[i] >= 0
                    && loopEnds[i] > loopStarts[i]) {
                if (chosenSlot >= 0) {
                    loopWarnings.addElement("Multiple loop slots detected; using the lowest numbered complete slot.");
                    break;
                }
                chosenSlot = i;
            } else if ((loopStarts[i] >= 0) != (loopEnds[i] >= 0)) {
                loopWarnings.addElement("Loop slot " + i + " is incomplete and will be ignored.");
            }
        }

        addAll(warnings, loopWarnings);
        if (chosenSlot < 0) {
            return new PlaybackTimeline.LoopInfo(false, -1, 0, -1, -1, -1L, -1L, loopWarnings);
        }

        {
            int loopStart = loopStarts[chosenSlot];
            int loopEnd = loopEnds[chosenSlot];
            if (loopEnd <= loopStart) {
                loopWarnings.addElement("Loop end does not fall after loop start; looping disabled.");
                addAll(warnings, loopWarnings);
                return new PlaybackTimeline.LoopInfo(false, -1, 0, -1, -1, -1L, -1L, loopWarnings);
            }
            return new PlaybackTimeline.LoopInfo(
                    true,
                    chosenSlot,
                    repeatCounts[chosenSlot],
                    loopStart,
                    loopEnd,
                    mapper.rawToMidiTick(loopStart),
                    mapper.rawToMidiTick(loopEnd),
                    loopWarnings);
        }
    }

    private long handleNoteEvent(
            NoteEvent noteEvent,
            TempoMapper mapper,
            ControlCollector controlCollector,
            Vector notes,
            Hashtable activeNotes,
            ChannelState[] channels,
            OutputLaneTracker outputLaneTracker,
            int[] voiceMap,
            Vector warnings,
            Vector warningKeys) {
        int localLane = laneIndex(noteEvent.trackIndex, noteEvent.voice);
        int logicalChannel = resolveVoiceMap(voiceMap, localLane);
        ChannelState channel;
        long midiStartTick;
        int noteBase;
        int midiNote;
        int velocity;
        int rawEndTick;
        long midiEndTick;
        IntKey activeKey;
        ActiveNote previous;

        if (logicalChannel < 0 || logicalChannel >= MAX_LOGICAL_CHANNELS) {
            addWarningOnce(
                    warnings,
                    warningKeys,
                    "note_channel_" + localLane + "_" + logicalChannel,
                    "Skipping note mapped outside logical-channel range: track="
                            + noteEvent.trackIndex + " voice=" + noteEvent.voice + " -> " + logicalChannel);
            return -1L;
        }

        channel = channels[logicalChannel];
        if (!channel.allowsOrdinaryNotes()) {
            addWarningOnce(
                    warnings,
                    warningKeys,
                    "suppressed_mode_" + logicalChannel + "_" + channel.mode,
                    "Suppressing ordinary notes on logical channel " + logicalChannel
                            + " because mode " + channel.mode + " is not a melodic ordinary mode.");
            return -1L;
        }
        observeOutputLaneActivity(outputLaneTracker, logicalChannel);
        if (logicalChannel >= MIDI_CHANNEL_COUNT) {
            addWarningOnce(
                    warnings,
                    warningKeys,
                    "host_channel_" + logicalChannel,
                    "Skipping note mapped to logical channel " + logicalChannel
                            + " because the host MIDI bridge only exposes 16 channels.");
            return -1L;
        }

        midiStartTick = mapper.rawToMidiTick(noteEvent.rawTick);
        emitPatchIfNeeded(controlCollector, channel, logicalChannel, noteEvent.trackIndex, -1, "note_patch_sync", midiStartTick);

        noteBase = outputLaneTracker.isAuthoritativeSpecial(logicalChannel) ? 35 : baseForMode(channel.mode);
        midiNote = clamp(0, 127, noteBase + noteEvent.pitch + octaveOffset(noteEvent.octaveShift));
        velocity = clamp(1, 127, noteEvent.noteExtraBytes > 0 ? noteEvent.velocity * 2 : 126);
        rawEndTick = noteEvent.rawTick + noteEvent.gate;
        midiEndTick = normalizeMidiEnd(midiStartTick, mapper.rawToMidiTick(rawEndTick));

        activeKey = new IntKey((logicalChannel << 7) | midiNote);
        previous = (ActiveNote) activeNotes.remove(activeKey);
        if (previous != null) {
            activeNotes.put(activeKey, previous.refreshGate(rawEndTick, midiEndTick));
            return midiEndTick;
        }

        activeNotes.put(activeKey, new ActiveNote(
                noteEvent.trackIndex,
                noteEvent.voice,
                logicalChannel,
                midiNote,
                velocity,
                noteEvent.rawTick,
                rawEndTick,
                midiStartTick,
                midiEndTick));
        return midiEndTick;
    }

    private void handleSystemEvent(
            SystemEvent systemEvent,
            long midiTick,
            ControlCollector controlCollector,
            ChannelState[] channels,
            OutputLaneTracker outputLaneTracker,
            int[] voiceMap,
            Vector warnings,
            Vector warningKeys) {
        if (isTempo(systemEvent)) {
            return;
        }

        switch (systemEvent.command) {
            case 0xB0:
                controlCollector.emitMasterVolume(systemEvent.trackIndex, systemEvent.command, systemEvent.name, midiTick,
                        clamp(0, 127, systemEvent.value));
                break;
            case 0xBA:
                applyPatchModeChange(systemEvent, midiTick, controlCollector, channels, outputLaneTracker);
                break;
            case 0xE0:
                applyProgramChange(systemEvent, midiTick, controlCollector, channels, outputLaneTracker, voiceMap, warnings, warningKeys);
                break;
            case 0xE1:
                applyBankChange(systemEvent, midiTick, controlCollector, channels, outputLaneTracker, voiceMap, warnings, warningKeys);
                break;
            case 0xE2:
                applyAbsoluteLevel(systemEvent, midiTick, controlCollector, channels, outputLaneTracker, voiceMap, warnings, warningKeys);
                break;
            case 0xE3:
                applyPan(systemEvent, midiTick, controlCollector, channels, outputLaneTracker, voiceMap, warnings, warningKeys);
                break;
            case 0xE4:
                applyPitchCoarse(systemEvent, midiTick, controlCollector, channels, outputLaneTracker, voiceMap, warnings, warningKeys);
                break;
            case 0xE5:
                applyVoiceAssignment(systemEvent, voiceMap);
                break;
            case 0xE6:
                applyRelativeLevel(systemEvent, midiTick, controlCollector, channels, outputLaneTracker, voiceMap, warnings, warningKeys);
                break;
            case 0xE7:
                applyPitchRange(systemEvent, midiTick, controlCollector, channels, outputLaneTracker, voiceMap, warnings, warningKeys);
                break;
            case 0xE8:
                applyPitchFine(systemEvent, midiTick, controlCollector, channels, outputLaneTracker, voiceMap, warnings, warningKeys);
                break;
            case 0xE9:
                applyFineCache(systemEvent, channels, outputLaneTracker, voiceMap, warnings, warningKeys);
                break;
            case 0xEA:
                applyModulation(systemEvent, midiTick, controlCollector, channels, outputLaneTracker, voiceMap, warnings, warningKeys);
                break;
            default:
                break;
        }
    }

    private void applyProgramChange(
            SystemEvent systemEvent,
            long midiTick,
            ControlCollector controlCollector,
            ChannelState[] channels,
            OutputLaneTracker outputLaneTracker,
            int[] voiceMap,
            Vector warnings,
            Vector warningKeys) {
        int logicalChannel = resolveMappedControlChannel(systemEvent, channels, voiceMap, warnings, warningKeys);
        if (logicalChannel < 0) {
            return;
        }
        ChannelState channel = channels[logicalChannel];
        channel.program = systemEvent.value & 0x3F;
        channel.hasProgramEvent = true;
        updateRawPatchWord(channel);
        observePatchSnapshot(channel);
        observeOutputLaneActivity(outputLaneTracker, logicalChannel);
        channel.patchDirty = true;
        emitPatchIfNeeded(controlCollector, channel, logicalChannel, systemEvent.trackIndex, systemEvent.command, systemEvent.name, midiTick);
    }

    private void applyBankChange(
            SystemEvent systemEvent,
            long midiTick,
            ControlCollector controlCollector,
            ChannelState[] channels,
            OutputLaneTracker outputLaneTracker,
            int[] voiceMap,
            Vector warnings,
            Vector warningKeys) {
        int logicalChannel = resolveMappedControlChannel(systemEvent, channels, voiceMap, warnings, warningKeys);
        if (logicalChannel < 0) {
            return;
        }
        ChannelState channel = channels[logicalChannel];
        channel.bank = systemEvent.value & 0x3F;
        channel.hasBankEvent = true;
        updateRawPatchWord(channel);
        observePatchSnapshot(channel);
        observeOutputLaneActivity(outputLaneTracker, logicalChannel);
        channel.patchDirty = true;
        if (!channel.hasProgramEvent && channel.latePatchOverrideEntry == PSM_LATE_PATCH_ENTRY_EMPTY) {
            return;
        }
        emitPatchIfNeeded(controlCollector, channel, logicalChannel, systemEvent.trackIndex, systemEvent.command, systemEvent.name, midiTick);
    }

    private void applyAbsoluteLevel(
            SystemEvent systemEvent,
            long midiTick,
            ControlCollector controlCollector,
            ChannelState[] channels,
            OutputLaneTracker outputLaneTracker,
            int[] voiceMap,
            Vector warnings,
            Vector warningKeys) {
        int logicalChannel = resolveMappedControlChannel(systemEvent, channels, voiceMap, warnings, warningKeys);
        if (logicalChannel < 0) {
            return;
        }
        ChannelState channel = channels[logicalChannel];
        channel.level = systemEvent.value & 0x3F;
        channel.volumeCache = clamp(0, 127, channel.level * 2);
        observeOutputLaneActivity(outputLaneTracker, logicalChannel);
        if (logicalChannel < MIDI_CHANNEL_COUNT) {
            controlCollector.emitVolume(systemEvent.trackIndex, systemEvent.command, systemEvent.name, logicalChannel, midiTick,
                    computePsmVolumeSync(channel, logicalChannel));
        }
    }

    private void applyRelativeLevel(
            SystemEvent systemEvent,
            long midiTick,
            ControlCollector controlCollector,
            ChannelState[] channels,
            OutputLaneTracker outputLaneTracker,
            int[] voiceMap,
            Vector warnings,
            Vector warningKeys) {
        int logicalChannel = resolveMappedControlChannel(systemEvent, channels, voiceMap, warnings, warningKeys);
        int delta;
        ChannelState channel;
        if (logicalChannel < 0) {
            return;
        }
        channel = channels[logicalChannel];
        delta = (systemEvent.value & 0x3F) - 32;
        channel.level = clamp(0, 63, channel.level + delta);
        channel.volumeCache = clamp(0, 127, channel.level * 2);
        observeOutputLaneActivity(outputLaneTracker, logicalChannel);
        if (logicalChannel < MIDI_CHANNEL_COUNT) {
            controlCollector.emitVolume(systemEvent.trackIndex, systemEvent.command, systemEvent.name, logicalChannel, midiTick,
                    computePsmVolumeSync(channel, logicalChannel));
        }
    }

    private void applyPan(
            SystemEvent systemEvent,
            long midiTick,
            ControlCollector controlCollector,
            ChannelState[] channels,
            OutputLaneTracker outputLaneTracker,
            int[] voiceMap,
            Vector warnings,
            Vector warningKeys) {
        int logicalChannel = resolveMappedControlChannel(systemEvent, channels, voiceMap, warnings, warningKeys);
        ChannelState channel;
        if (logicalChannel < 0) {
            return;
        }
        channel = channels[logicalChannel];
        channel.pan = systemEvent.value & 0x3F;
        observeOutputLaneActivity(outputLaneTracker, logicalChannel);
        if (logicalChannel < MIDI_CHANNEL_COUNT) {
            controlCollector.emitPan(systemEvent.trackIndex, systemEvent.command, systemEvent.name, logicalChannel, midiTick,
                    computePsmPanSync(channel));
        }
    }

    private void applyPitchCoarse(
            SystemEvent systemEvent,
            long midiTick,
            ControlCollector controlCollector,
            ChannelState[] channels,
            OutputLaneTracker outputLaneTracker,
            int[] voiceMap,
            Vector warnings,
            Vector warningKeys) {
        int logicalChannel = resolveMappedControlChannel(systemEvent, channels, voiceMap, warnings, warningKeys);
        ChannelState channel;
        if (logicalChannel < 0) {
            return;
        }
        channel = channels[logicalChannel];
        channel.pitchCoarse = systemEvent.value & 0x3F;
        observeOutputLaneActivity(outputLaneTracker, logicalChannel);
        if (logicalChannel < MIDI_CHANNEL_COUNT) {
            controlCollector.emitPitchBend(systemEvent.trackIndex, systemEvent.command, systemEvent.name, logicalChannel, midiTick,
                    computePitchBend(channel));
        }
    }

    private void applyPitchFine(
            SystemEvent systemEvent,
            long midiTick,
            ControlCollector controlCollector,
            ChannelState[] channels,
            OutputLaneTracker outputLaneTracker,
            int[] voiceMap,
            Vector warnings,
            Vector warningKeys) {
        int logicalChannel = resolveMappedControlChannel(systemEvent, channels, voiceMap, warnings, warningKeys);
        ChannelState channel;
        if (logicalChannel < 0) {
            return;
        }
        channel = channels[logicalChannel];
        channel.pitchFine = systemEvent.value & 0x3F;
        observeOutputLaneActivity(outputLaneTracker, logicalChannel);
        if (logicalChannel < MIDI_CHANNEL_COUNT) {
            controlCollector.emitPitchBend(systemEvent.trackIndex, systemEvent.command, systemEvent.name, logicalChannel, midiTick,
                    computePitchBend(channel));
        }
    }

    private void applyFineCache(
            SystemEvent systemEvent,
            ChannelState[] channels,
            OutputLaneTracker outputLaneTracker,
            int[] voiceMap,
            Vector warnings,
            Vector warningKeys) {
        int logicalChannel = resolveMappedControlChannel(systemEvent, channels, voiceMap, warnings, warningKeys);
        if (logicalChannel < 0) {
            return;
        }
        channels[logicalChannel].pitchFine = systemEvent.value & 0x3F;
        observeOutputLaneActivity(outputLaneTracker, logicalChannel);
    }

    private void applyPitchRange(
            SystemEvent systemEvent,
            long midiTick,
            ControlCollector controlCollector,
            ChannelState[] channels,
            OutputLaneTracker outputLaneTracker,
            int[] voiceMap,
            Vector warnings,
            Vector warningKeys) {
        int logicalChannel = resolveMappedControlChannel(systemEvent, channels, voiceMap, warnings, warningKeys);
        int range;
        ChannelState channel;
        if (logicalChannel < 0) {
            return;
        }
        range = systemEvent.value & 0x3F;
        if (range > 24) {
            addWarningOnce(
                    warnings,
                    warningKeys,
                    "pitch_range_" + logicalChannel + "_" + range,
                    "Ignoring pitch range " + range + " on logical channel " + logicalChannel
                            + " because the verified parser only accepts values <= 24.");
            return;
        }
        channel = channels[logicalChannel];
        channel.pitchRange = range;
        observeOutputLaneActivity(outputLaneTracker, logicalChannel);
        if (logicalChannel < MIDI_CHANNEL_COUNT) {
            controlCollector.emitPitchRange(systemEvent.trackIndex, systemEvent.command, systemEvent.name, logicalChannel, midiTick,
                    range);
        }
    }

    private void applyModulation(
            SystemEvent systemEvent,
            long midiTick,
            ControlCollector controlCollector,
            ChannelState[] channels,
            OutputLaneTracker outputLaneTracker,
            int[] voiceMap,
            Vector warnings,
            Vector warningKeys) {
        int logicalChannel = resolveMappedControlChannel(systemEvent, channels, voiceMap, warnings, warningKeys);
        ChannelState channel;
        if (logicalChannel < 0) {
            return;
        }
        channel = channels[logicalChannel];
        channel.modulation = systemEvent.value & 0x3F;
        observeOutputLaneActivity(outputLaneTracker, logicalChannel);
        if (logicalChannel < MIDI_CHANNEL_COUNT) {
            controlCollector.emitModulation(systemEvent.trackIndex, systemEvent.command, systemEvent.name, logicalChannel, midiTick,
                    channel.modulation * 2);
        }
    }

    private void applyVoiceAssignment(SystemEvent systemEvent, int[] voiceMap) {
        int lane;
        if (systemEvent.part < 0) {
            return;
        }
        lane = laneIndex(systemEvent.trackIndex, systemEvent.part);
        if (lane >= 0 && lane < voiceMap.length) {
            voiceMap[lane] = systemEvent.value & 0x3F;
        }
    }

    private void applyPatchModeChange(
            SystemEvent systemEvent,
            long midiTick,
            ControlCollector controlCollector,
            ChannelState[] channels,
            OutputLaneTracker outputLaneTracker) {
        int logicalChannel = (systemEvent.value >> 3) & 0x0F;
        ChannelState channel;
        if (logicalChannel < 0 || logicalChannel >= channels.length) {
            return;
        }
        channel = channels[logicalChannel];
        channel.mode = systemEvent.value & 0x07;
        observePatchSnapshot(channel);
        observeOutputLaneActivity(outputLaneTracker, logicalChannel);
        channel.patchDirty = true;
        if (channel.mode == 1) {
            emitPatchIfNeeded(controlCollector, channel, logicalChannel, systemEvent.trackIndex, systemEvent.command, systemEvent.name, midiTick);
        }
    }

    private int resolveMappedControlChannel(
            SystemEvent systemEvent,
            ChannelState[] channels,
            int[] voiceMap,
            Vector warnings,
            Vector warningKeys) {
        int lane;
        int logicalChannel;
        if (systemEvent.part < 0) {
            return -1;
        }
        lane = laneIndex(systemEvent.trackIndex, systemEvent.part);
        logicalChannel = resolveVoiceMap(voiceMap, lane);
        if (logicalChannel < 0 || logicalChannel >= channels.length) {
            addWarningOnce(
                    warnings,
                    warningKeys,
                    "control_channel_" + systemEvent.trackIndex + "_" + systemEvent.part + "_" + logicalChannel,
                    "Skipping control " + systemEvent.name + " mapped outside logical-channel range: track="
                            + systemEvent.trackIndex + " part=" + systemEvent.part + " -> " + logicalChannel);
            return -1;
        }
        if (logicalChannel >= MIDI_CHANNEL_COUNT) {
            addWarningOnce(
                    warnings,
                    warningKeys,
                    "control_host_channel_" + logicalChannel,
                    "Logical channel " + logicalChannel + " is outside the host MIDI bridge's 16-channel surface.");
        }
        return logicalChannel;
    }

    private void emitInitialMidiDefaults(ControlCollector controlCollector, ChannelState[] channels) {
        int midiChannel;
        for (midiChannel = 0; midiChannel < MIDI_CHANNEL_COUNT; midiChannel++) {
            ChannelState channel = channels[midiChannel];
            controlCollector.emitVolume(-1, -1, "default_level", midiChannel, 0L, computePsmVolumeSync(channel, midiChannel));
            controlCollector.emitPan(-1, -1, "default_pan", midiChannel, 0L, computePsmPanSync(channel));
            controlCollector.emitPitchRange(-1, -1, "default_pitch_range", midiChannel, 0L, channel.pitchRange);
            controlCollector.emitPitchBend(-1, -1, "default_pitch", midiChannel, 0L, computePitchBend(channel));
            controlCollector.emitModulation(-1, -1, "default_modulation", midiChannel, 0L, channel.modulation * 2);
        }
    }

    private void emitPatchIfNeeded(
            ControlCollector controlCollector,
            ChannelState channel,
            int logicalChannel,
            int sourceTrack,
            int sourceCommand,
            String sourceName,
            long midiTick) {
        HostPatch patch;
        if (logicalChannel < 0 || logicalChannel >= MIDI_CHANNEL_COUNT) {
            return;
        }
        patch = translateHostPatch(channel);
        if (patch.suppressed) {
            return;
        }
        if (!channel.patchDirty && channel.lastPatch != null && channel.lastPatch.sameAs(patch)) {
            return;
        }
        if (channel.lastPatch != null && channel.lastPatch.sameAs(patch)) {
            channel.patchDirty = false;
            return;
        }
        controlCollector.emitPatch(sourceTrack, sourceCommand, sourceName, logicalChannel, midiTick, patch);
        channel.patchDirty = false;
        channel.lastPatch = patch;
    }

    private HostPatch translateHostPatch(ChannelState channel) {
        int mode = channel.mode & 0x07;
        int patchWord;
        if (mode != 0 && mode != 1) {
            return new HostPatch(true, 0, 0);
        }
        patchWord = resolveHostPatchSelection(channel).patchWord;
        return new HostPatch(false, patchWord & 0x7F, patchWord);
    }

    private PatchSelection resolveHostPatchSelection(ChannelState channel) {
        PatchSelection authoritativeOrdinary = resolveAuthoritativeOrdinaryPatchSelection(channel);
        PatchSelection selection;
        int patchWord;

        if (authoritativeOrdinary != null) {
            return authoritativeOrdinary;
        }
        selection = resolveVerifiedLatePatchSelection(channel);
        if (!selection.authoritative && HOST_PATCH_USE_OBSERVED_ORDINARY_SURFACE) {
            PatchSelection observed = resolveObservedOrdinaryPatchSelection(channel);
            if (observed != null) {
                selection = observed;
            }
        }
        patchWord = selection.patchWord;
        if (PSM_DEFAULT_ENABLE_LATE_PATCH_REMAP) {
            patchWord = applyLatePatchRemap(patchWord, 1);
        } else {
            patchWord = clamp(0, 127, patchWord);
        }
        return selection.withPatchWord(patchWord);
    }

    private PatchSelection resolveAuthoritativeOrdinaryPatchSelection(ChannelState channel) {
        int patchWord;
        int lateEntry;
        if (!channel.allowsOrdinaryNotes() || !channel.hasProgramEvent) {
            return null;
        }
        patchWord = channel.rawPatchWord & 0x0FFF;
        lateEntry = ordinaryLatePatchEntry(patchWord & 0x7F);
        return new PatchSelection(lateEntry, patchWord, true);
    }

    private PatchSelection resolveVerifiedLatePatchSelection(ChannelState channel) {
        if (PSM_DEFAULT_USE_INSTRUMENT_SET && isPlayableLatePatchEntry(channel.latePatchOverrideEntry)) {
            return PatchSelection.fromLateEntry(channel.latePatchOverrideEntry, true);
        }
        if (PSM_DEFAULT_LATE_TABLE_FALLBACK && isPlayableLatePatchEntry(channel.latePatchTableEntry)) {
            return PatchSelection.fromLateEntry(channel.latePatchTableEntry, true);
        }
        return PatchSelection.fromLateEntry(PSM_LATE_PATCH_ENTRY_EMPTY, false);
    }

    private PatchSelection resolveObservedOrdinaryPatchSelection(ChannelState channel) {
        if (!isPlayableLatePatchEntry(channel.latePatchTableEntry)) {
            return null;
        }
        return PatchSelection.fromLateEntry(channel.latePatchTableEntry, false);
    }

    private static void updateRawPatchWord(ChannelState channel) {
        channel.rawPatchWord = composeOrdinaryPatchWord(channel.bank, channel.program);
    }

    private static void observePatchSnapshot(ChannelState channel) {
        int lateEntry;
        if (!channel.allowsOrdinaryNotes() || !channel.hasProgramEvent) {
            return;
        }
        if (hasSpecialSidebandFamily(channel.rawPatchWord)) {
            channel.latePatchTableEntry = PSM_LATE_PATCH_ENTRY_SIDEBAND_SENTINEL;
            return;
        }
        lateEntry = ordinaryLatePatchEntry(channel.program);
        if (channel.latePatchTableEntry == PSM_LATE_PATCH_ENTRY_EMPTY) {
            channel.latePatchTableEntry = lateEntry;
            return;
        }
        if (channel.latePatchTableEntry != lateEntry) {
            channel.latePatchTableMismatch = true;
        }
    }

    private static int ordinaryLatePatchEntry(int program) {
        return clamp(0, 127, program) + 1;
    }

    private static int composeOrdinaryPatchWord(int bank, int program) {
        int low6 = program & 0x3F;
        int high6 = bank & 0x3F;
        if ((high6 & 0x3E) == 0) {
            switch (low6) {
                case 0:
                    return 0;
                case 1:
                    return 9;
                case 2:
                    return 16;
                case 3:
                    return 24;
                case 4:
                    return 13;
                case 5:
                    return 74;
                default:
                    break;
            }
        }
        return low6 | (high6 << 6);
    }

    private static boolean hasSpecialSidebandFamily(int rawPatchWord) {
        return (rawPatchWord & 0x1F00) == 0x1B00;
    }

    private static boolean isPlayableLatePatchEntry(int lateEntry) {
        return lateEntry >= 1 && lateEntry <= PSM_LATE_PATCH_ENTRY_MAX;
    }

    private static int patchWordFromLateEntry(int lateEntry) {
        return isPlayableLatePatchEntry(lateEntry) ? (lateEntry - 1) : 0;
    }

    private static int applyLatePatchRemap(int value, int modeFlag) {
        int clamped = value > 0x17 ? 0 : clamp(0, 0x17, value);
        int group = clamped >> 3;
        if (modeFlag != 0) {
            switch (group) {
                case 0:
                    return 0;
                case 1:
                    return 9;
                case 2:
                    return 16;
                case 3:
                    return 24;
                case 4:
                    return 13;
                case 5:
                    return 74;
                default:
                    break;
            }
        }
        return 0x0100 | group;
    }

    private static int computePsmVolumeSync(ChannelState channel, int logicalChannel) {
        int scaled = channel.volumeCache;
        scaled = (scaled * PSM_GLOBAL_LEVEL_SCALE) / 100;
        scaled = (scaled * channelLevelScale(logicalChannel)) / 100;
        return clamp(0, 127, scaled);
    }

    private static int channelLevelScale(int logicalChannel) {
        return PSM_CHANNEL_LEVEL_SCALE;
    }

    private static int computePsmPanSync(ChannelState channel) {
        if (PSM_FORCE_PAN_LEFT_SYNC) {
            return 0;
        }
        return clamp(0, 127, channel.pan * 2);
    }

    private long flushExpiredNotes(int currentRawTick, Hashtable activeNotes, Vector notes) {
        Vector removeKeys = new Vector();
        Enumeration keys = activeNotes.keys();
        long maxTick = -1L;

        while (keys.hasMoreElements()) {
            Object key = keys.nextElement();
            ActiveNote active = (ActiveNote) activeNotes.get(key);
            if (active.rawEndTick > currentRawTick) {
                continue;
            }
            notes.addElement(active.toFinalCompiledNote());
            maxTick = maxLong(maxTick, active.midiEndTick);
            removeKeys.addElement(key);
        }

        {
            int i;
            for (i = 0; i < removeKeys.size(); i++) {
                activeNotes.remove(removeKeys.elementAt(i));
            }
        }
        return maxTick;
    }

    private static int[] buildHostOutputChannelMap(OutputLaneTracker outputLaneTracker) {
        int[] outputChannelMap = createIdentityVoiceMap(MIDI_CHANNEL_COUNT);
        int nextMelodicChannel = 0;
        int logicalChannel;

        if (!PSM_APPLY_WRITER_EXPORT_OUTPUT_REMAP || outputLaneTracker == null || !outputLaneTracker.hasAuthoritativeMask()) {
            return outputChannelMap;
        }
        if (outputLaneTracker.usesIdentityMap()) {
            return outputChannelMap;
        }
        for (logicalChannel = 0; logicalChannel < MIDI_CHANNEL_COUNT; logicalChannel++) {
            if (!outputLaneTracker.isActive(logicalChannel)) {
                continue;
            }
            if (outputLaneTracker.isAuthoritativeSpecial(logicalChannel)) {
                outputChannelMap[logicalChannel] = PSM_GM_DRUM_CHANNEL;
                continue;
            }
            outputChannelMap[logicalChannel] = nextMelodicChannel;
            nextMelodicChannel = nextSequentialOutputLane(nextMelodicChannel, outputLaneTracker.reservesDrumOutputLane());
        }
        return outputChannelMap;
    }

    private static Vector applyOrdinaryLiveMixChaseApproximation(
            Vector mappedControls,
            Vector notes,
            Vector tempoPoints,
            PlaybackTimeline.LoopInfo loopInfo,
            long totalMidiTicks) {
        Vector ordered;
        Hashtable notesByChannel;
        Hashtable groupedStreams;
        Vector groupedKeys;
        Vector passthrough;
        Vector rewritten;
        int i;
        int nextOrder = 0;

        if (mappedControls.size() == 0 || notes.size() == 0) {
            return mappedControls;
        }

        ordered = copyVector(mappedControls);
        SortUtil.sort(ordered, MAPPED_CONTROL_COMPARATOR);

        notesByChannel = groupNotesByMidiChannel(notes);
        groupedStreams = new Hashtable();
        groupedKeys = new Vector();
        passthrough = new Vector();

        for (i = 0; i < ordered.size(); i++) {
            PlaybackTimeline.MappedControlEvent control = (PlaybackTimeline.MappedControlEvent) ordered.elementAt(i);
            if (isVolumeOrPanControl(control)) {
                IntKey key = new IntKey((control.midiChannel << 8) | (control.data1 & 0x7F));
                Vector stream = (Vector) groupedStreams.get(key);
                if (stream == null) {
                    stream = new Vector();
                    groupedStreams.put(key, stream);
                    groupedKeys.addElement(key);
                }
                stream.addElement(control);
            } else {
                passthrough.addElement(control);
            }
        }

        rewritten = copyVector(passthrough);
        for (i = 0; i < groupedKeys.size(); i++) {
            IntKey key = (IntKey) groupedKeys.elementAt(i);
            Vector stream = (Vector) groupedStreams.get(key);
            boolean previousValueSet = false;
            int previousValue = 0;
            int j;
            for (j = 0; j < stream.size(); j++) {
                PlaybackTimeline.MappedControlEvent control = (PlaybackTimeline.MappedControlEvent) stream.elementAt(j);
                int targetValue = clamp(0, 127, control.data2);
                long boundaryTick = computeMixChaseBoundaryTick(stream, j, loopInfo, totalMidiTicks);
                long chaseWindowTicks = minLong(
                        computeNativeSmoothingWindowTicks(control.midiTick, tempoPoints),
                        maxLong(0L, boundaryTick - control.midiTick));

                if (previousValueSet
                        && isOrdinaryLiveMixProxyCandidate(control)
                        && chaseWindowTicks > 0L
                        && hasStrictlyActiveNote((Vector) notesByChannel.get(new IntKey(control.midiChannel)), control.midiTick)) {
                    nextOrder = appendMixChasedControls(
                            rewritten,
                            control,
                            previousValue,
                            targetValue,
                            chaseWindowTicks,
                            nextOrder);
                } else {
                    rewritten.addElement(copyMappedControl(control, control.midiTick, targetValue, nextOrder++, control.sourceName));
                }
                previousValue = targetValue;
                previousValueSet = true;
            }
        }

        SortUtil.sort(rewritten, MAPPED_CONTROL_COMPARATOR);
        return rewritten;
    }

    private static Hashtable groupNotesByMidiChannel(Vector notes) {
        Hashtable grouped = new Hashtable();
        int i;
        for (i = 0; i < notes.size(); i++) {
            PlaybackTimeline.CompiledNote note = (PlaybackTimeline.CompiledNote) notes.elementAt(i);
            IntKey key = new IntKey(note.midiChannel);
            Vector channelNotes = (Vector) grouped.get(key);
            if (channelNotes == null) {
                channelNotes = new Vector();
                grouped.put(key, channelNotes);
            }
            channelNotes.addElement(note);
        }
        return grouped;
    }

    private static boolean isVolumeOrPanControl(PlaybackTimeline.MappedControlEvent control) {
        return control != null
                && control.status == CONTROL_CHANGE
                && (control.data1 == 7 || control.data1 == 10);
    }

    private static boolean isOrdinaryLiveMixProxyCandidate(PlaybackTimeline.MappedControlEvent control) {
        if (!isVolumeOrPanControl(control)) {
            return false;
        }
        if (control.sourceCommand == 0xE3) {
            return control.data1 == 10;
        }
        return (control.sourceCommand == 0xE2 || control.sourceCommand == 0xE6) && control.data1 == 7;
    }

    private static boolean hasStrictlyActiveNote(Vector notes, long midiTick) {
        int i;
        if (notes == null || notes.size() == 0) {
            return false;
        }
        for (i = 0; i < notes.size(); i++) {
            PlaybackTimeline.CompiledNote note = (PlaybackTimeline.CompiledNote) notes.elementAt(i);
            if (note.midiStartTick < midiTick && note.midiEndTick > midiTick) {
                return true;
            }
        }
        return false;
    }

    private static long computeMixChaseBoundaryTick(
            Vector stream,
            int index,
            PlaybackTimeline.LoopInfo loopInfo,
            long totalMidiTicks) {
        PlaybackTimeline.MappedControlEvent control = (PlaybackTimeline.MappedControlEvent) stream.elementAt(index);
        long boundary = maxLong(control.midiTick, totalMidiTicks);
        if (index + 1 < stream.size()) {
            boundary = minLong(boundary, ((PlaybackTimeline.MappedControlEvent) stream.elementAt(index + 1)).midiTick);
        }
        if (loopInfo != null && loopInfo.hasLoop && control.midiTick < loopInfo.loopEndMidiTick) {
            boundary = minLong(boundary, loopInfo.loopEndMidiTick);
        }
        return maxLong(control.midiTick, boundary);
    }

    private static long computeNativeSmoothingWindowTicks(long midiTick, Vector tempoPoints) {
        PlaybackTimeline.TempoPoint point = tempoPointAtOrBefore(tempoPoints, midiTick);
        long mpqn = point != null ? point.mpqn : 500000L;
        long numerator = ((long) ORDINARY_NATIVE_REPLACEMENT_OVERLAP_SAMPLES)
                * PlaybackTimeline.MIDI_PPQ
                * 1000000L;
        long denominator = ((long) ORDINARY_NATIVE_RENDER_OUTPUT_RATE) * maxLong(1L, mpqn);
        return maxLong(1L, (numerator + (denominator / 2L)) / denominator);
    }

    private static PlaybackTimeline.TempoPoint tempoPointAtOrBefore(Vector tempoPoints, long midiTick) {
        PlaybackTimeline.TempoPoint current;
        int i;
        if (tempoPoints == null || tempoPoints.size() == 0) {
            return null;
        }
        current = (PlaybackTimeline.TempoPoint) tempoPoints.elementAt(0);
        for (i = 0; i < tempoPoints.size(); i++) {
            PlaybackTimeline.TempoPoint point = (PlaybackTimeline.TempoPoint) tempoPoints.elementAt(i);
            if (point.midiTick > midiTick) {
                break;
            }
            current = point;
        }
        return current;
    }

    private static int appendMixChasedControls(
            Vector rewritten,
            PlaybackTimeline.MappedControlEvent original,
            int previousValue,
            int targetValue,
            long chaseWindowTicks,
            int nextOrder) {
        int delta = targetValue - previousValue;
        int eventCount;
        long lastTick = Long.MIN_VALUE;
        int lastValue = previousValue;
        int step;
        String syntheticName = (original.sourceName == null ? "control" : original.sourceName) + HOST_LIVE_MIX_CHASE_SUFFIX;

        if (delta == 0) {
            rewritten.addElement(copyMappedControl(original, original.midiTick, targetValue, nextOrder++, original.sourceName));
            return nextOrder;
        }

        eventCount = (int) maxLong(2L, minLong((long) HOST_LIVE_MIX_CHASE_STEP_COUNT, chaseWindowTicks + 1L));
        for (step = 1; step <= eventCount; step++) {
            long offset = eventCount == 1 ? 0L : (chaseWindowTicks * (step - 1L)) / (eventCount - 1L);
            long tick = original.midiTick + offset;
            int value = previousValue + divideRoundNearest(delta * step, eventCount);
            if (step == eventCount) {
                value = targetValue;
            }
            if (value == lastValue && step < eventCount) {
                continue;
            }
            if (tick == lastTick && value == lastValue) {
                continue;
            }
            rewritten.addElement(copyMappedControl(original, tick, value, nextOrder++, syntheticName));
            lastTick = tick;
            lastValue = value;
        }

        if (lastValue != targetValue) {
            rewritten.addElement(copyMappedControl(original, original.midiTick + chaseWindowTicks, targetValue, nextOrder++, syntheticName));
        }
        return nextOrder;
    }

    private static PlaybackTimeline.MappedControlEvent copyMappedControl(
            PlaybackTimeline.MappedControlEvent original,
            long midiTick,
            int data2,
            int order,
            String sourceName) {
        return new PlaybackTimeline.MappedControlEvent(
                original.sourceTrack,
                original.sourceCommand,
                sourceName,
                original.midiChannel,
                midiTick,
                original.status,
                original.data1,
                clamp(0, 127, data2),
                order);
    }

    private static long maxMappedControlTick(Vector mappedControls) {
        long maxTick = 0L;
        int i;
        for (i = 0; i < mappedControls.size(); i++) {
            PlaybackTimeline.MappedControlEvent control = (PlaybackTimeline.MappedControlEvent) mappedControls.elementAt(i);
            maxTick = maxLong(maxTick, control.midiTick);
        }
        return maxTick;
    }

    private static Vector remapCompiledNotes(Vector notes, int[] outputChannelMap) {
        Vector remapped = new Vector();
        int i;
        for (i = 0; i < notes.size(); i++) {
            PlaybackTimeline.CompiledNote note = (PlaybackTimeline.CompiledNote) notes.elementAt(i);
            int midiChannel = remapMidiChannel(note.midiChannel, outputChannelMap);
            remapped.addElement(new PlaybackTimeline.CompiledNote(
                    note.sourceTrack,
                    note.sourceVoice,
                    midiChannel,
                    note.midiNote,
                    note.velocity,
                    note.rawStartTick,
                    note.rawEndTick,
                    note.midiStartTick,
                    note.midiEndTick));
        }
        return remapped;
    }

    private static Vector remapMappedControls(Vector mappedControls, int[] outputChannelMap) {
        Vector remapped = new Vector();
        int i;
        for (i = 0; i < mappedControls.size(); i++) {
            PlaybackTimeline.MappedControlEvent control = (PlaybackTimeline.MappedControlEvent) mappedControls.elementAt(i);
            int midiChannel = remapMidiChannel(control.midiChannel, outputChannelMap);
            remapped.addElement(new PlaybackTimeline.MappedControlEvent(
                    control.sourceTrack,
                    control.sourceCommand,
                    control.sourceName,
                    midiChannel,
                    control.midiTick,
                    control.status,
                    control.data1,
                    control.data2,
                    control.order));
        }
        return remapped;
    }

    private static ChannelState[] createChannelStates() {
        ChannelState[] channels = new ChannelState[MAX_LOGICAL_CHANNELS];
        int i;
        for (i = 0; i < channels.length; i++) {
            channels[i] = new ChannelState();
        }
        return channels;
    }

    private static int[] createIdentityVoiceMap(int count) {
        int[] map = new int[count];
        int i;
        for (i = 0; i < count; i++) {
            map[i] = i;
        }
        return map;
    }

    private static boolean isTempo(SystemEvent systemEvent) {
        return systemEvent.command >= 0xC0 && systemEvent.command <= 0xCF && systemEvent.timebase > 0;
    }

    private static int laneIndex(int trackIndex, int voice) {
        return (trackIndex * 4) + voice;
    }

    private static int resolveVoiceMap(int[] voiceMap, int lane) {
        return lane >= 0 && lane < voiceMap.length ? voiceMap[lane] : -1;
    }

    private static int remapMidiChannel(int logicalChannel, int[] outputChannelMap) {
        if (outputChannelMap == null || logicalChannel < 0 || logicalChannel >= outputChannelMap.length) {
            return logicalChannel;
        }
        return clamp(0, MIDI_CHANNEL_COUNT - 1, outputChannelMap[logicalChannel]);
    }

    private static void observeOutputLaneActivity(OutputLaneTracker outputLaneTracker, int logicalChannel) {
        if (outputLaneTracker != null) {
            outputLaneTracker.observeActive(logicalChannel);
        }
    }

    private static int nextSequentialOutputLane(int current, boolean reserveDrumOutputLane) {
        if (current >= MIDI_CHANNEL_COUNT - 1) {
            return MIDI_CHANNEL_COUNT - 1;
        }
        if (reserveDrumOutputLane && current == (PSM_GM_DRUM_CHANNEL - 1)) {
            return current + 2;
        }
        return current + 1;
    }

    private static int baseForMode(int mode) {
        return mode == 1 ? 35 : 45;
    }

    private static int octaveOffset(int octaveShift) {
        return octaveShift >= 0 && octaveShift < OCTAVE_TABLE.length ? OCTAVE_TABLE[octaveShift] : 0;
    }

    private static int computePitchBend(ChannelState channel) {
        return clamp(0, 16383, (8 * (channel.pitchFine + (32 * channel.pitchCoarse))) - 256);
    }

    private static long normalizeMidiEnd(long midiStartTick, long midiEndTick) {
        return midiEndTick <= midiStartTick ? (midiStartTick + 1L) : midiEndTick;
    }

    private static void addWarningOnce(Vector warnings, Vector warningKeys, String key, String warning) {
        if (!containsString(warningKeys, key)) {
            warningKeys.addElement(key);
            warnings.addElement(warning);
        }
    }

    private static boolean containsString(Vector values, String target) {
        int i;
        for (i = 0; i < values.size(); i++) {
            if (target.equals(values.elementAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static void addAll(Vector destination, Vector source) {
        int i;
        for (i = 0; i < source.size(); i++) {
            destination.addElement(source.elementAt(i));
        }
    }

    private static Vector copyVector(Vector source) {
        Vector copy = new Vector();
        addAll(copy, source);
        return copy;
    }

    private static int clamp(int min, int max, int value) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    private static int max(int left, int right) {
        return left > right ? left : right;
    }

    private static long maxLong(long left, long right) {
        return left > right ? left : right;
    }

    private static long minLong(long left, long right) {
        return left < right ? left : right;
    }

    private static int compareInt(int left, int right) {
        if (left < right) {
            return -1;
        }
        if (left > right) {
            return 1;
        }
        return 0;
    }

    private static int compareLong(long left, long right) {
        if (left < right) {
            return -1;
        }
        if (left > right) {
            return 1;
        }
        return 0;
    }

    private static int divideRoundNearest(int numerator, int denominator) {
        if (denominator <= 0) {
            return 0;
        }
        if (numerator >= 0) {
            return (numerator + (denominator / 2)) / denominator;
        }
        return -(((-numerator) + (denominator / 2)) / denominator);
    }

    private static final SortUtil.Comparator TRACK_EVENT_COMPARATOR = new SortUtil.Comparator() {
        public int compare(Object left, Object right) {
            TrackEvent a = (TrackEvent) left;
            TrackEvent b = (TrackEvent) right;
            int byTick = compareInt(a.rawTick, b.rawTick);
            if (byTick != 0) {
                return byTick;
            }
            int byTrack = compareInt(a.trackIndex, b.trackIndex);
            if (byTrack != 0) {
                return byTrack;
            }
            return compareInt(a.eventIndex, b.eventIndex);
        }
    };

    private static final SortUtil.Comparator RAW_TEMPO_COMPARATOR = new SortUtil.Comparator() {
        public int compare(Object left, Object right) {
            RawTempoPoint a = (RawTempoPoint) left;
            RawTempoPoint b = (RawTempoPoint) right;
            int byTick = compareInt(a.rawTick, b.rawTick);
            if (byTick != 0) {
                return byTick;
            }
            int byTrack = compareInt(a.trackIndex, b.trackIndex);
            if (byTrack != 0) {
                return byTrack;
            }
            return compareInt(a.eventIndex, b.eventIndex);
        }
    };

    private static final SortUtil.Comparator MAPPED_CONTROL_COMPARATOR = new SortUtil.Comparator() {
        public int compare(Object left, Object right) {
            PlaybackTimeline.MappedControlEvent a = (PlaybackTimeline.MappedControlEvent) left;
            PlaybackTimeline.MappedControlEvent b = (PlaybackTimeline.MappedControlEvent) right;
            int byTick = compareLong(a.midiTick, b.midiTick);
            if (byTick != 0) {
                return byTick;
            }
            int byChannel = compareInt(a.midiChannel, b.midiChannel);
            if (byChannel != 0) {
                return byChannel;
            }
            int byOrder = compareInt(a.order, b.order);
            if (byOrder != 0) {
                return byOrder;
            }
            return compareInt(a.data1, b.data1);
        }
    };

    private static final class RawTempoPoint {
        final int rawTick;
        final int timebase;
        final int tempo;
        final int trackIndex;
        final int eventIndex;
        final boolean synthetic;

        RawTempoPoint(int rawTick, int timebase, int tempo, int trackIndex, int eventIndex, boolean synthetic) {
            this.rawTick = rawTick;
            this.timebase = timebase;
            this.tempo = tempo;
            this.trackIndex = trackIndex;
            this.eventIndex = eventIndex;
            this.synthetic = synthetic;
        }
    }

    private static final class ChannelState {
        int mode = 0;
        int bank = 0;
        int program = 0;
        int rawPatchWord = 0;
        boolean hasProgramEvent = false;
        boolean hasBankEvent = false;
        int latePatchTableEntry = PSM_LATE_PATCH_ENTRY_EMPTY;
        boolean latePatchTableMismatch = false;
        int latePatchOverrideEntry = PSM_LATE_PATCH_ENTRY_EMPTY;
        int level = DEFAULT_LEVEL;
        int volumeCache = DEFAULT_VOLUME_CACHE;
        int pan = DEFAULT_PAN;
        int pitchCoarse = DEFAULT_PITCH_COARSE;
        int pitchFine = DEFAULT_PITCH_FINE;
        int pitchRange = DEFAULT_PITCH_RANGE;
        int modulation = DEFAULT_MODULATION;
        boolean patchDirty = true;
        HostPatch lastPatch = null;

        boolean allowsOrdinaryNotes() {
            return mode == 0 || mode == 1;
        }
    }

    private static final class HostPatch {
        final boolean suppressed;
        final int program;
        final int patchWord;

        HostPatch(boolean suppressed, int program, int patchWord) {
            this.suppressed = suppressed;
            this.program = clamp(0, 127, program);
            this.patchWord = patchWord & 0xFFFF;
        }

        boolean sameAs(HostPatch other) {
            return other != null
                    && suppressed == other.suppressed
                    && program == other.program
                    && patchWord == other.patchWord;
        }
    }

    private static final class PatchSelection {
        final int lateEntry;
        final int patchWord;
        final boolean authoritative;

        PatchSelection(int lateEntry, int patchWord, boolean authoritative) {
            this.lateEntry = lateEntry & 0xFFFF;
            this.patchWord = patchWord & 0xFFFF;
            this.authoritative = authoritative;
        }

        static PatchSelection fromLateEntry(int lateEntry, boolean authoritative) {
            return new PatchSelection(lateEntry, patchWordFromLateEntry(lateEntry), authoritative);
        }

        PatchSelection withPatchWord(int patchWord) {
            return new PatchSelection(lateEntry, patchWord, authoritative);
        }
    }

    private static final class IntKey {
        final int value;

        IntKey(int value) {
            this.value = value;
        }

        public int hashCode() {
            return value;
        }

        public boolean equals(Object other) {
            return other instanceof IntKey && ((IntKey) other).value == value;
        }
    }

    private static final class IntValue {
        final int value;

        IntValue(int value) {
            this.value = value;
        }
    }

    private static final class ActiveNote {
        final int sourceTrack;
        final int sourceVoice;
        final int midiChannel;
        final int midiNote;
        final int velocity;
        final int rawStartTick;
        final int rawEndTick;
        final long midiStartTick;
        final long midiEndTick;

        ActiveNote(
                int sourceTrack,
                int sourceVoice,
                int midiChannel,
                int midiNote,
                int velocity,
                int rawStartTick,
                int rawEndTick,
                long midiStartTick,
                long midiEndTick) {
            this.sourceTrack = sourceTrack;
            this.sourceVoice = sourceVoice;
            this.midiChannel = midiChannel;
            this.midiNote = midiNote;
            this.velocity = velocity;
            this.rawStartTick = rawStartTick;
            this.rawEndTick = rawEndTick;
            this.midiStartTick = midiStartTick;
            this.midiEndTick = midiEndTick;
        }

        ActiveNote refreshGate(int refreshedRawEndTick, long refreshedMidiEndTick) {
            return new ActiveNote(
                    sourceTrack,
                    sourceVoice,
                    midiChannel,
                    midiNote,
                    velocity,
                    rawStartTick,
                    refreshedRawEndTick,
                    midiStartTick,
                    normalizeMidiEnd(midiStartTick, refreshedMidiEndTick));
        }

        PlaybackTimeline.CompiledNote toFinalCompiledNote() {
            return new PlaybackTimeline.CompiledNote(
                    sourceTrack,
                    sourceVoice,
                    midiChannel,
                    midiNote,
                    velocity,
                    rawStartTick,
                    rawEndTick,
                    midiStartTick,
                    midiEndTick);
        }
    }

    private static final class ControlCollector {
        private final Vector mappedControls;
        private final Hashtable lastControlValues = new Hashtable();
        private final Hashtable lastPitchBendValues = new Hashtable();
        private int nextOrder = 0;

        ControlCollector(Vector mappedControls) {
            this.mappedControls = mappedControls;
        }

        void emitPatch(int sourceTrack, int sourceCommand, String sourceName, int midiChannel, long midiTick, HostPatch patch) {
            emit(sourceTrack, sourceCommand, sourceName, midiChannel, midiTick, PROGRAM_CHANGE, patch.program, 0);
        }

        void emitVolume(int sourceTrack, int sourceCommand, String sourceName, int midiChannel, long midiTick, int value) {
            emitDedupedControl(sourceTrack, sourceCommand, sourceName, midiChannel, midiTick, 7, clamp(0, 127, value));
        }

        void emitPan(int sourceTrack, int sourceCommand, String sourceName, int midiChannel, long midiTick, int value) {
            emitDedupedControl(sourceTrack, sourceCommand, sourceName, midiChannel, midiTick, 10, clamp(0, 127, value));
        }

        void emitPitchRange(int sourceTrack, int sourceCommand, String sourceName, int midiChannel, long midiTick, int range) {
            emit(sourceTrack, sourceCommand, sourceName, midiChannel, midiTick, CONTROL_CHANGE, 101, 0);
            emit(sourceTrack, sourceCommand, sourceName, midiChannel, midiTick, CONTROL_CHANGE, 100, 0);
            emit(sourceTrack, sourceCommand, sourceName, midiChannel, midiTick, CONTROL_CHANGE, 6, clamp(0, 127, range));
            emit(sourceTrack, sourceCommand, sourceName, midiChannel, midiTick, CONTROL_CHANGE, 38, 0);
        }

        void emitPitchBend(int sourceTrack, int sourceCommand, String sourceName, int midiChannel, long midiTick, int bendValue) {
            IntKey key = new IntKey(midiChannel);
            int clamped = clamp(0, 16383, bendValue);
            IntValue previous = (IntValue) lastPitchBendValues.get(key);
            if (previous != null && previous.value == clamped) {
                return;
            }
            lastPitchBendValues.put(key, new IntValue(clamped));
            emit(sourceTrack, sourceCommand, sourceName, midiChannel, midiTick, PITCH_BEND, clamped & 0x7F, (clamped >> 7) & 0x7F);
        }

        void emitModulation(int sourceTrack, int sourceCommand, String sourceName, int midiChannel, long midiTick, int value) {
            emitDedupedControl(sourceTrack, sourceCommand, sourceName, midiChannel, midiTick, 1, clamp(0, 127, value));
        }

        void emitMasterVolume(int sourceTrack, int sourceCommand, String sourceName, long midiTick, int value) {
            int midiChannel;
            for (midiChannel = 0; midiChannel < MIDI_CHANNEL_COUNT; midiChannel++) {
                emitDedupedControl(sourceTrack, sourceCommand, sourceName, midiChannel, midiTick, 7, clamp(0, 127, value));
            }
        }

        private void emitDedupedControl(
                int sourceTrack,
                int sourceCommand,
                String sourceName,
                int midiChannel,
                long midiTick,
                int controller,
                int value) {
            IntKey key = new IntKey((midiChannel << 8) | (controller & 0x7F));
            IntValue previous = (IntValue) lastControlValues.get(key);
            if (previous != null && previous.value == value) {
                return;
            }
            lastControlValues.put(key, new IntValue(value));
            emit(sourceTrack, sourceCommand, sourceName, midiChannel, midiTick, CONTROL_CHANGE, controller, value);
        }

        private void emit(
                int sourceTrack,
                int sourceCommand,
                String sourceName,
                int midiChannel,
                long midiTick,
                int status,
                int data1,
                int data2) {
            mappedControls.addElement(new PlaybackTimeline.MappedControlEvent(
                    sourceTrack,
                    sourceCommand,
                    sourceName,
                    midiChannel,
                    midiTick,
                    status,
                    data1,
                    data2,
                    nextOrder++));
        }
    }

    private static final class OutputLaneTracker {
        private final boolean authoritativeMaskKnown;
        private final boolean identityMap;
        private final boolean reserveDrumOutputLane;
        private int activeMask = 0;
        private int authoritativeSpecialMask = 0;

        private OutputLaneTracker(
                boolean authoritativeMaskKnown,
                boolean identityMap,
                boolean reserveDrumOutputLane,
                int authoritativeSpecialMask) {
            this.authoritativeMaskKnown = authoritativeMaskKnown;
            this.identityMap = identityMap;
            this.reserveDrumOutputLane = reserveDrumOutputLane;
            this.authoritativeSpecialMask = authoritativeSpecialMask;
        }

        static OutputLaneTracker seededFreshDefaultPlayPath() {
            return new OutputLaneTracker(
                    true,
                    PSM_DEFAULT_GSMODE,
                    !PSM_DEFAULT_DRAMBANKFLG,
                    PSM_DEFAULT_AUTHORITATIVE_SPECIAL_MASK);
        }

        void observeActive(int logicalChannel) {
            if (logicalChannel < 0 || logicalChannel >= MIDI_CHANNEL_COUNT) {
                return;
            }
            activeMask |= (1 << logicalChannel);
        }

        boolean isActive(int logicalChannel) {
            return logicalChannel >= 0
                    && logicalChannel < MIDI_CHANNEL_COUNT
                    && ((activeMask >>> logicalChannel) & 1) != 0;
        }

        boolean hasAuthoritativeMask() {
            return authoritativeMaskKnown;
        }

        boolean isAuthoritativeSpecial(int logicalChannel) {
            return logicalChannel >= 0
                    && logicalChannel < MIDI_CHANNEL_COUNT
                    && ((authoritativeSpecialMask >>> logicalChannel) & 1) != 0;
        }

        boolean usesIdentityMap() {
            return identityMap;
        }

        boolean reservesDrumOutputLane() {
            return reserveDrumOutputLane;
        }
    }

    public static final class TempoMapper {
        private final Vector tempoPoints;

        TempoMapper(Vector tempoPoints) {
            this.tempoPoints = tempoPoints;
        }

        public long rawToMidiTick(int rawTick) {
            PlaybackTimeline.TempoPoint current = (PlaybackTimeline.TempoPoint) tempoPoints.elementAt(0);
            int i;
            for (i = 1; i < tempoPoints.size(); i++) {
                PlaybackTimeline.TempoPoint next = (PlaybackTimeline.TempoPoint) tempoPoints.elementAt(i);
                if (next.rawTick > rawTick) {
                    break;
                }
                current = next;
            }
            return current.midiTick + ((((long) rawTick) - current.rawTick) * PlaybackTimeline.MIDI_PPQ) / current.timebase;
        }
    }
}
