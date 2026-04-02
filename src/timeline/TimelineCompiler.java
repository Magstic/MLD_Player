package timeline;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sound.midi.ShortMessage;

import container.MldFile;
import container.TopLevelChunk;
import event.NoteEvent;
import event.ResourceEvent;
import event.SystemEvent;
import event.TrackDecodeResult;
import event.TrackEvent;
import normalize.MdNormalizationResult;

public final class TimelineCompiler {
    private static final int MIDI_CHANNEL_COUNT = 16;
    private static final int MAX_LOGICAL_CHANNELS = 64;
    private static final int DEFAULT_LEVEL = 63;
    private static final int DEFAULT_PAN = 32;
    private static final int DEFAULT_PITCH_COARSE = 32;
    private static final int DEFAULT_PITCH_FINE = 32;
    private static final int DEFAULT_PITCH_RANGE = 2;
    private static final int DEFAULT_MODULATION = 0;
    private static final int DEFAULT_VOLUME_CACHE = DEFAULT_LEVEL * 2;
    private static final int ORDINARY_NATIVE_REPLACEMENT_OVERLAP_SAMPLES = 128;
    private static final int ORDINARY_NATIVE_RENDER_OUTPUT_RATE = 32000;
    private static final int PSM_GLOBAL_LEVEL_SCALE = 100;
    private static final int PSM_CHANNEL_LEVEL_SCALE = 100;
    private static final int PSM_DEFAULT_MIDMONO = 0;
    private static final int PSM_DEFAULT_EFFECTIVE_PRESET_FAMILY = 2;
    private static final int PSM_LATE_PATCH_ENTRY_EMPTY = 0;
    private static final int PSM_LATE_PATCH_ENTRY_MAX = 0x80;
    private static final int PSM_LATE_PATCH_ENTRY_SIDEBAND_SENTINEL = 0x81;
    private static final boolean PSM_DEFAULT_USE_INSTRUMENT_SET = true;
    private static final boolean PSM_DEFAULT_LATE_TABLE_FALLBACK = false;
    private static final boolean PSM_DEFAULT_ENABLE_LATE_PATCH_REMAP = false;
    private static final boolean PSM_EMIT_SYNTHETIC_PATCH_SYNC_CONTROLS = false;
    private static final boolean PSM_APPLY_WRITER_EXPORT_OUTPUT_REMAP = true;
    private static final boolean HOST_PATCH_USE_OBSERVED_ORDINARY_SURFACE = true;
    private static final boolean PSM_DEFAULT_GSMODE = false;
    private static final boolean PSM_DEFAULT_DRAMBANKFLG = false;
    private static final boolean PSM_FORCE_PAN_LEFT_SYNC = false;
    private static final boolean HOST_APPROXIMATE_ORDINARY_LIVE_MIX = true;
    private static final int HOST_LIVE_MIX_CHASE_STEP_COUNT = 4;
    private static final int PSM_GM_DRUM_CHANNEL = 9;
    private static final int PSM_DEFAULT_AUTHORITATIVE_SPECIAL_MASK = 1 << PSM_GM_DRUM_CHANNEL;
    private static final int[] OCTAVE_TABLE = new int[] { 0, 12, -24, -12 };
    private static final String PATCH_SOURCE_DEFAULT_ZERO = "default_zero";
    private static final String PATCH_SOURCE_INSTRUMENT_OVERRIDE = "instrument_override";
    private static final String PATCH_SOURCE_PRESET_TABLE = "preset_table";
    private static final String PATCH_SOURCE_OBSERVED_ORDINARY = "observed_ordinary_first";
    private static final String PATCH_SOURCE_OBSERVED_ORDINARY_MISMATCH = "observed_ordinary_first_with_mismatch";
    private static final String PATCH_SOURCE_HOST_PROXY_PATCH12 = "host_proxy_patch12_from_bank_program";
    private static final String PATCH_SYNC_VOLUME_SOURCE = "patch_sync_volume";
    private static final String PATCH_SYNC_PAN_SOURCE = "patch_sync_pan_zero";
    private static final String ORDINARY_NATIVE_SELECTED_HALF_RULE =
            "slot[4]==0 -> active half; slot[4]!=0 -> replacement half";
    private static final String ORDINARY_NATIVE_PATH_LEVEL_PAN = "level_pan";
    private static final String ORDINARY_NATIVE_PATH_PITCH = "pitch";
    private static final String ORDINARY_NATIVE_PATH_LOOKUP = "lookup";
    private static final String HOST_MAPPING_CC7_VOLUME = "cc7_volume";
    private static final String HOST_MAPPING_CC10_PAN = "cc10_pan";
    private static final String HOST_MAPPING_CC7_VOLUME_CHASE = "cc7_volume_chase";
    private static final String HOST_MAPPING_CC10_PAN_CHASE = "cc10_pan_chase";
    private static final String HOST_MAPPING_PITCH_BEND = "pitch_bend";
    private static final String HOST_MAPPING_RPN_PITCH_RANGE = "rpn_pitch_range";
    private static final String HOST_MAPPING_CC1_MODULATION = "cc1_modulation";
    private static final String HOST_MAPPING_NONE = "none";
    private static final String HOST_LIVE_MIX_CHASE_SUFFIX = "_live_mix_chase";

    public PlaybackTimeline compile(
            MldFile file,
            List<TrackDecodeResult> decodedTracks,
            MdNormalizationResult mdNormalization) {
        List<String> warnings = new ArrayList<String>();
        Set<String> warningKeys = new LinkedHashSet<String>();
        emitFirstOnlyTopLevelWarnings(file, warnings, warningKeys);
        NarrativeSections narrativeSections = createNarrativeSections();
        List<PlaybackTimeline.ResourceCatalogEntry> resourceCatalog = buildResourceCatalog(file, warnings, warningKeys);
        List<PlaybackTimeline.InitialChannelConfig> initialChannelConfigs =
                buildInitialChannelConfigs(file, warnings, warningKeys);

        List<RawTempoPoint> tempoSeeds = collectTempoSeeds(decodedTracks);
        Collections.sort(tempoSeeds, RAW_TEMPO_COMPARATOR);
        List<PlaybackTimeline.TempoPoint> tempoPoints = buildTempoPoints(tempoSeeds, warnings);
        TempoMapper mapper = new TempoMapper(tempoPoints);
        PlaybackTimeline.LoopInfo loopInfo = determineLoopInfo(decodedTracks, mapper, warnings);

        List<TrackEvent> orderedEvents = collectOrderedEvents(decodedTracks);
        List<PlaybackTimeline.CompiledNote> notes = new ArrayList<PlaybackTimeline.CompiledNote>();
        List<PlaybackTimeline.ResourceEventState> resourceEvents = new ArrayList<PlaybackTimeline.ResourceEventState>();
        List<PlaybackTimeline.MappedControlEvent> mappedControls = new ArrayList<PlaybackTimeline.MappedControlEvent>();
        List<PlaybackTimeline.OrdinaryNativeControl> ordinaryNativeControls =
                new ArrayList<PlaybackTimeline.OrdinaryNativeControl>();
        List<PlaybackTimeline.UnmappedControlEvent> unmappedControls =
                new ArrayList<PlaybackTimeline.UnmappedControlEvent>();
        Map<Integer, ActiveNote> activeNotes = new LinkedHashMap<Integer, ActiveNote>();
        ChannelState[] channels = createChannelStates();
        OutputLaneTracker outputLaneTracker = OutputLaneTracker.seededFreshDefaultPlayPath();
        int[] voiceMap = createIdentityVoiceMap(Math.max(16, file.trackCount * 4));
        ControlCollector controlCollector = new ControlCollector(mappedControls);

        emitInitialMidiDefaults(controlCollector, channels);

        long totalMidiTicks = 0L;
        for (TrackEvent event : orderedEvents) {
            if (event instanceof NoteEvent) {
                totalMidiTicks = Math.max(totalMidiTicks, flushExpiredNotes(event.rawTick, activeNotes, notes));
                totalMidiTicks = Math.max(totalMidiTicks, handleNoteEvent(
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
                continue;
            }
            if (event instanceof ResourceEvent) {
                totalMidiTicks = Math.max(totalMidiTicks, flushExpiredNotes(event.rawTick, activeNotes, notes));
                ResourceEvent resourceEvent = (ResourceEvent) event;
                long midiTick = mapper.rawToMidiTick(resourceEvent.rawTick);
                totalMidiTicks = Math.max(totalMidiTicks, midiTick);
                handleResourceEvent(resourceEvent, midiTick, resourceCatalog, resourceEvents, voiceMap, warnings, warningKeys);
                continue;
            }
            if (event instanceof SystemEvent) {
                totalMidiTicks = Math.max(totalMidiTicks, flushExpiredNotes(event.rawTick, activeNotes, notes));
                SystemEvent systemEvent = (SystemEvent) event;
                long midiTick = mapper.rawToMidiTick(systemEvent.rawTick);
                totalMidiTicks = Math.max(totalMidiTicks, midiTick);
                handleSystemEvent(systemEvent, midiTick, controlCollector, unmappedControls, channels, outputLaneTracker,
                        voiceMap, ordinaryNativeControls, warnings, warningKeys);
            }
        }

        totalMidiTicks = Math.max(totalMidiTicks, flushExpiredNotes(Integer.MAX_VALUE, activeNotes, notes));
        for (TrackDecodeResult track : decodedTracks) {
            totalMidiTicks = Math.max(totalMidiTicks, mapper.rawToMidiTick(track.totalRawTicks));
        }
        if (loopInfo.hasLoop) {
            totalMidiTicks = Math.max(totalMidiTicks, loopInfo.loopEndMidiTick);
        }
        if (!resourceEvents.isEmpty()) {
            addWarningOnce(
                    warnings,
                    warningKeys,
                    "resource_playback_unimplemented",
                    "Non-melody events were parsed and preserved for export only; this build plays ordinary melody tracks only.");
        }

        mappedControls = applyOrdinaryLiveMixChaseApproximation(
                mappedControls,
                notes,
                tempoPoints,
                loopInfo,
                totalMidiTicks);
        totalMidiTicks = Math.max(totalMidiTicks, maxMappedControlTick(mappedControls));

        int[] outputChannelMap = buildHostOutputChannelMap(outputLaneTracker);
        List<PlaybackTimeline.OutputLaneAudit> outputLanePlan =
                createOutputLanePlan(outputLaneTracker, outputChannelMap);
        List<PlaybackTimeline.ChannelAssignment> channelAssignments =
                createChannelAssignments(file.trackCount, voiceMap, warnings, outputChannelMap);
        notes = remapCompiledNotes(notes, outputChannelMap);
        mappedControls = remapMappedControls(mappedControls, outputChannelMap);

        return new PlaybackTimeline(
                file,
                decodedTracks,
                mdNormalization,
                tempoPoints,
                loopInfo,
                channelAssignments,
                outputLanePlan,
                createOrdinaryNativeModel(),
                ordinaryNativeControls,
                resourceCatalog,
                initialChannelConfigs,
                resourceEvents,
                notes,
                mappedControls,
                unmappedControls,
                totalMidiTicks,
                warnings,
                narrativeSections.implementationFacts,
                narrativeSections.runtimePolicies,
                narrativeSections.knownLimitations);
    }

    private List<PlaybackTimeline.ChannelAssignment> createChannelAssignments(
            int trackCount,
            int[] voiceMap,
            List<String> warnings,
            int[] outputChannelMap) {
        List<PlaybackTimeline.ChannelAssignment> assignments = new ArrayList<PlaybackTimeline.ChannelAssignment>();
        for (int trackIndex = 0; trackIndex < trackCount; trackIndex++) {
            for (int voice = 0; voice < 4; voice++) {
                int logicalChannel = resolveVoiceMap(voiceMap, laneIndex(trackIndex, voice));
                if (logicalChannel >= MIDI_CHANNEL_COUNT) {
                    warnings.add("Track/voice mapping exceeds 16 MIDI channels: track=" + trackIndex + " voice=" + voice);
                    continue;
                }
                if (logicalChannel < 0) {
                    warnings.add("Track/voice mapping resolved outside logical-channel range: track="
                            + trackIndex + " voice=" + voice + " -> " + logicalChannel);
                    continue;
                }
                int midiChannel = remapMidiChannel(logicalChannel, outputChannelMap);
                assignments.add(new PlaybackTimeline.ChannelAssignment(
                        trackIndex,
                        voice,
                        logicalChannel,
                        midiChannel,
                        midiChannel + 1,
                        midiChannel != logicalChannel));
            }
        }
        return assignments;
    }

    private List<PlaybackTimeline.OutputLaneAudit> createOutputLanePlan(
            OutputLaneTracker outputLaneTracker,
            int[] outputChannelMap) {
        List<PlaybackTimeline.OutputLaneAudit> plan = new ArrayList<PlaybackTimeline.OutputLaneAudit>(MIDI_CHANNEL_COUNT);
        for (int logicalChannel = 0; logicalChannel < MIDI_CHANNEL_COUNT; logicalChannel++) {
            int midiChannel = remapMidiChannel(logicalChannel, outputChannelMap);
            plan.add(new PlaybackTimeline.OutputLaneAudit(
                    logicalChannel,
                    outputLaneTracker != null && outputLaneTracker.isActive(logicalChannel),
                    outputLaneTracker != null && outputLaneTracker.hasAuthoritativeMask(),
                    outputLaneTracker != null && outputLaneTracker.isAuthoritativeSpecial(logicalChannel),
                    midiChannel,
                    midiChannel != logicalChannel));
        }
        return plan;
    }

    private List<PlaybackTimeline.ResourceCatalogEntry> buildResourceCatalog(
            MldFile file,
            List<String> warnings,
            Set<String> warningKeys) {
        List<PlaybackTimeline.ResourceCatalogEntry> entries = new ArrayList<PlaybackTimeline.ResourceCatalogEntry>();
        TopLevelChunk firstAinf = file.firstTopLevelChunk("ainf");
        int ainfCount = file.countTopLevelChunks("ainf");

        int activeAdatCount = 0;
        boolean useAinfTable = false;
        if (firstAinf != null) {
            if (ainfCount > 1) {
                addWarningOnce(
                        warnings,
                        warningKeys,
                        "ainf_multiple",
                        "Multiple ainf chunks were present; only the first ainf chunk is used to build the active adat table.");
            }
            if (firstAinf.payload.length == 0) {
                addWarningOnce(
                        warnings,
                        warningKeys,
                        "ainf_empty",
                        "The first ainf chunk is empty and does not register any active adat entries.");
            } else if ((firstAinf.payload[0] & 0x40) != 0) {
                addWarningOnce(
                        warnings,
                        warningKeys,
                        "ainf_rejected_bit40",
                        "The first ainf chunk sets bit 0x40 in byte 0; the newer-parser active adat table is therefore rejected.");
            } else {
                activeAdatCount = Math.min(64, firstAinf.payload[0] & 0x3F);
                useAinfTable = true;
            }
        }

        int catalogIndex = 0;
        int adatIndex = 0;
        int activeAdatIndex = 0;
        for (TopLevelChunk chunk : file.topLevelChunks) {
            if (!"resource".equals(chunk.category)) {
                continue;
            }

            int currentAdatIndex = "adat".equals(chunk.id) ? adatIndex++ : -1;
            int currentActiveAdatIndex = -1;
            if ("adat".equals(chunk.id) && useAinfTable && activeAdatIndex < activeAdatCount) {
                currentActiveAdatIndex = activeAdatIndex++;
            }
            LegacySelectorSummary legacy = null;
            if ("adat".equals(chunk.id) || "adpm".equals(chunk.id)) {
                legacy = extractLegacySelectorSummary(chunk.payload);
                if (legacy == null && "adat".equals(chunk.id)) {
                    addWarningOnce(
                            warnings,
                            warningKeys,
                            "resource_no_legacy_" + chunk.offset,
                            "Top-level adat chunk at 0x" + Integer.toHexString(chunk.offset)
                                    + " did not match the current legacy selector summary heuristic.");
                }
            }

            entries.add(new PlaybackTimeline.ResourceCatalogEntry(
                    catalogIndex++,
                    chunk.id,
                    chunk.offset,
                    chunk.length,
                    chunk.lengthFieldBytes,
                    currentAdatIndex,
                    currentActiveAdatIndex,
                    legacy != null ? legacy.selectorHeaderLength : -1,
                    legacy != null ? legacy.selectorId : -1,
                    legacy != null ? legacy.selectorFlags : -1,
                    legacy != null ? legacy.trailingPayloadLength : -1,
                    legacy != null ? legacy.adpmByte0 : -1,
                    legacy != null ? legacy.adpmByte1 : -1,
                    legacy != null ? legacy.adpmByte2Low3 : -1,
                    legacy != null ? legacy.adpmByte2Bit3 : -1));
        }
        if (useAinfTable && activeAdatIndex < activeAdatCount) {
            addWarningOnce(
                    warnings,
                    warningKeys,
                    "ainf_missing_adat_entries",
                    "The active ainf table declares " + activeAdatCount + " adat entries, but only " + activeAdatIndex
                            + " top-level adat chunks were available to register.");
        }
        return entries;
    }

    private List<PlaybackTimeline.InitialChannelConfig> buildInitialChannelConfigs(
            MldFile file,
            List<String> warnings,
            Set<String> warningKeys) {
        List<PlaybackTimeline.InitialChannelConfig> entries = new ArrayList<PlaybackTimeline.InitialChannelConfig>();
        TopLevelChunk firstThrd = file.firstTopLevelChunk("thrd");
        int thrdCount = file.countTopLevelChunks("thrd");

        if (firstThrd == null) {
            return entries;
        }
        if (thrdCount > 1) {
            addWarningOnce(
                    warnings,
                    warningKeys,
                    "thrd_multiple",
                    "Multiple thrd chunks were present; only the first thrd chunk is applied to the initial channel-config surface.");
        }
        if (firstThrd.payload.length == 0) {
            addWarningOnce(
                    warnings,
                    warningKeys,
                    "thrd_empty",
                    "The first thrd chunk is empty and does not seed any initial channel config.");
            return entries;
        }

        int globalValue = firstThrd.payload[0] & 0xFF;
        int recordBytes = firstThrd.payload.length - 1;
        if ((recordBytes & 1) != 0) {
            addWarningOnce(
                    warnings,
                    warningKeys,
                    "thrd_odd_payload",
                    "The first thrd chunk has an odd trailing payload byte; the last byte is ignored by the current initial channel-config parser.");
        }

        boolean[] seenSynth = new boolean[16];
        boolean[] seenAudio = new boolean[16];
        int recordCount = Math.max(0, recordBytes / 2);
        for (int i = 0; i < recordCount; i++) {
            int recordOffset = 1 + (i * 2);
            int first = firstThrd.payload[recordOffset] & 0xFF;
            int second = firstThrd.payload[recordOffset + 1] & 0xFF;
            int logicalChannel = first & 0x0F;
            boolean audioTarget = (second & 0x20) != 0;
            boolean[] seen = audioTarget ? seenAudio : seenSynth;
            if (seen[logicalChannel]) {
                addWarningOnce(
                        warnings,
                        warningKeys,
                        "thrd_duplicate_" + (audioTarget ? "audio" : "synth") + "_" + logicalChannel,
                        "Duplicate thrd record for " + (audioTarget ? "audio" : "synth")
                                + " logical channel " + logicalChannel + " was ignored after the first entry.");
                continue;
            }
            seen[logicalChannel] = true;

            int rawSubvalue = second & 0x1F;
            entries.add(new PlaybackTimeline.InitialChannelConfig(
                    firstThrd.offset,
                    globalValue,
                    logicalChannel,
                    audioTarget ? "audio" : "synth",
                    rawSubvalue,
                    rawSubvalue + 1,
                    rawSubvalue + 2));
        }

        return entries;
    }

    private void emitFirstOnlyTopLevelWarnings(
            MldFile file,
            List<String> warnings,
            Set<String> warningKeys) {
        addFirstOnlyTopLevelWarning(file, warnings, warningKeys, "vers", "version window");
        addFirstOnlyTopLevelWarning(file, warnings, warningKeys, "sorc", "source/flags value");
        addFirstOnlyTopLevelWarning(file, warnings, warningKeys, "note", "note extra-byte count");
        addFirstOnlyTopLevelWarning(file, warnings, warningKeys, "exst", "resource extension count");
        addFirstOnlyTopLevelWarning(file, warnings, warningKeys, "date", "date text");
    }

    private void addFirstOnlyTopLevelWarning(
            MldFile file,
            List<String> warnings,
            Set<String> warningKeys,
            String chunkId,
            String meaning) {
        int count = file.countTopLevelChunks(chunkId);
        if (count <= 1) {
            return;
        }
        addWarningOnce(
                warnings,
                warningKeys,
                chunkId + "_multiple",
                "Multiple " + chunkId + " chunks were present; only the first accepted " + chunkId
                        + " chunk is used as the authoritative " + meaning + ".");
    }

    private List<TrackEvent> collectOrderedEvents(List<TrackDecodeResult> decodedTracks) {
        List<TrackEvent> events = new ArrayList<TrackEvent>();
        for (TrackDecodeResult track : decodedTracks) {
            events.addAll(track.events);
        }
        Collections.sort(events, TRACK_EVENT_COMPARATOR);
        return events;
    }

    private List<RawTempoPoint> collectTempoSeeds(List<TrackDecodeResult> decodedTracks) {
        List<RawTempoPoint> seeds = new ArrayList<RawTempoPoint>();
        for (TrackDecodeResult track : decodedTracks) {
            for (TrackEvent event : track.events) {
                if (event instanceof SystemEvent && isTempo((SystemEvent) event)) {
                    SystemEvent systemEvent = (SystemEvent) event;
                    seeds.add(new RawTempoPoint(
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

    private List<PlaybackTimeline.TempoPoint> buildTempoPoints(List<RawTempoPoint> seeds, List<String> warnings) {
        List<PlaybackTimeline.TempoPoint> points = new ArrayList<PlaybackTimeline.TempoPoint>();
        if (seeds.isEmpty()) {
            warnings.add("No tempo event observed; inserting synthetic 120 BPM / timebase 48 point.");
            seeds.add(new RawTempoPoint(0, 48, 120, -1, -1, true));
        } else if (seeds.get(0).rawTick > 0) {
            warnings.add("First tempo event does not start at tick 0; inserting synthetic point at origin.");
            RawTempoPoint first = seeds.get(0);
            seeds.add(0, new RawTempoPoint(0, first.timebase, first.tempo, -1, -1, true));
        }

        long midiTick = 0L;
        int lastRawTick = 0;
        int lastTimebase = seeds.get(0).timebase;
        for (int i = 0; i < seeds.size(); i++) {
            RawTempoPoint seed = seeds.get(i);
            int deltaRaw = seed.rawTick - lastRawTick;
            if (deltaRaw < 0) {
                deltaRaw = 0;
            }
            midiTick += ((long) deltaRaw * PlaybackTimeline.MIDI_PPQ) / lastTimebase;
            int mpqn = 60000000 / Math.max(1, seed.tempo);
            points.add(new PlaybackTimeline.TempoPoint(
                    seed.rawTick,
                    midiTick,
                    seed.timebase,
                    seed.tempo,
                    mpqn,
                    seed.synthetic));
            lastRawTick = seed.rawTick;
            lastTimebase = seed.timebase;
        }
        return points;
    }

    private PlaybackTimeline.LoopInfo determineLoopInfo(
            List<TrackDecodeResult> decodedTracks,
            TempoMapper mapper,
            List<String> warnings) {
        Integer[] loopStarts = new Integer[4];
        Integer[] loopEnds = new Integer[4];
        int[] repeatCounts = new int[] { 0, 0, 0, 0 };
        List<String> loopWarnings = new ArrayList<String>();

        for (TrackDecodeResult track : decodedTracks) {
            for (TrackEvent event : track.events) {
                if (!(event instanceof SystemEvent)) {
                    continue;
                }
                SystemEvent systemEvent = (SystemEvent) event;
                if (systemEvent.command != 0xDD) {
                    continue;
                }
                int slot = (systemEvent.value >> 6) & 0x03;
                int operation = systemEvent.value & 0x03;
                if (operation == 0x00) {
                    if (loopStarts[slot] == null || systemEvent.rawTick < loopStarts[slot].intValue()) {
                        loopStarts[slot] = Integer.valueOf(systemEvent.rawTick);
                    }
                } else if (operation == 0x01) {
                    if (loopEnds[slot] == null || systemEvent.rawTick < loopEnds[slot].intValue()) {
                        loopEnds[slot] = Integer.valueOf(systemEvent.rawTick);
                        int repeat = (systemEvent.value >> 2) & 0x0F;
                        repeatCounts[slot] = repeat == 0 ? -1 : repeat;
                    }
                }
            }
        }

        int chosenSlot = -1;
        for (int slot = 0; slot < 4; slot++) {
            if (loopStarts[slot] != null && loopEnds[slot] != null && loopEnds[slot].intValue() > loopStarts[slot].intValue()) {
                if (chosenSlot >= 0) {
                    loopWarnings.add("Multiple loop slots detected; using the lowest numbered complete slot.");
                    break;
                }
                chosenSlot = slot;
            } else if ((loopStarts[slot] == null) != (loopEnds[slot] == null)) {
                loopWarnings.add("Loop slot " + slot + " is incomplete and will be ignored.");
            }
        }

        if (chosenSlot < 0) {
            warnings.addAll(loopWarnings);
            return new PlaybackTimeline.LoopInfo(false, -1, 0, -1, -1, -1L, -1L, loopWarnings);
        }

        int loopStart = loopStarts[chosenSlot].intValue();
        int loopEnd = loopEnds[chosenSlot].intValue();
        if (loopEnd <= loopStart) {
            loopWarnings.add("Loop end does not fall after loop start; looping disabled.");
            warnings.addAll(loopWarnings);
            return new PlaybackTimeline.LoopInfo(false, -1, 0, -1, -1, -1L, -1L, loopWarnings);
        }

        warnings.addAll(loopWarnings);
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

    private long handleNoteEvent(
            NoteEvent noteEvent,
            TempoMapper mapper,
            ControlCollector controlCollector,
            List<PlaybackTimeline.CompiledNote> notes,
            Map<Integer, ActiveNote> activeNotes,
            ChannelState[] channels,
            OutputLaneTracker outputLaneTracker,
            int[] voiceMap,
            List<String> warnings,
            Set<String> warningKeys) {
        int localLane = laneIndex(noteEvent.trackIndex, noteEvent.voice);
        int logicalChannel = resolveVoiceMap(voiceMap, localLane);
        if (logicalChannel < 0 || logicalChannel >= MAX_LOGICAL_CHANNELS) {
            addWarningOnce(warnings, warningKeys, "note_channel_" + localLane + "_" + logicalChannel,
                    "Skipping note mapped outside logical-channel range: track="
                            + noteEvent.trackIndex + " voice=" + noteEvent.voice + " -> " + logicalChannel);
            return -1L;
        }

        ChannelState channel = channels[logicalChannel];
        if (!channel.allowsOrdinaryNotes()) {
            addWarningOnce(warnings, warningKeys, "suppressed_mode_" + logicalChannel + "_" + channel.mode,
                    "Suppressing ordinary notes on logical channel " + logicalChannel
                            + " because mode " + channel.mode + " is not a melodic ordinary mode.");
            return -1L;
        }
        observeOutputLaneActivity(outputLaneTracker, logicalChannel);
        if (logicalChannel >= MIDI_CHANNEL_COUNT) {
            addWarningOnce(warnings, warningKeys, "host_channel_" + logicalChannel,
                    "Skipping note mapped to logical channel " + logicalChannel
                            + " because the host MIDI bridge only exposes 16 channels.");
            return -1L;
        }

        long midiStartTick = mapper.rawToMidiTick(noteEvent.rawTick);
        emitPatchIfNeeded(controlCollector, channel, logicalChannel, outputLaneTracker, noteEvent.trackIndex, -1,
                "note_patch_sync",
                midiStartTick);

        int noteBase = (outputLaneTracker != null && outputLaneTracker.isAuthoritativeSpecial(logicalChannel))
                ? 35 : baseForMode(channel.mode);
        int midiNote = clamp(0, 127, noteBase + noteEvent.pitch + octaveOffset(noteEvent.octaveShift));
        int velocity = clamp(1, 127, noteEvent.hasExtraByte() ? noteEvent.velocity * 2 : 126);
        int rawEndTick = noteEvent.rawTick + noteEvent.gate;
        long midiEndTick = normalizeMidiEnd(midiStartTick, mapper.rawToMidiTick(rawEndTick));

        Integer activeKey = Integer.valueOf((logicalChannel << 7) | midiNote);
        ActiveNote previous = activeNotes.remove(activeKey);
        if (previous != null) {
            // The official live scheduler refreshes the pending gate for an active
            // (channel, note) pair instead of emitting a second note-on.
            activeNotes.put(activeKey, previous.refreshGate(rawEndTick, midiEndTick));
            return midiEndTick;
        }

        activeNotes.put(activeKey, new ActiveNote(
                noteEvent.trackIndex,
                noteEvent.voice,
                logicalChannel,
                logicalChannel + 1,
                midiNote,
                velocity,
                noteEvent.rawTick,
                rawEndTick,
                midiStartTick,
                midiEndTick));
        return midiEndTick;
    }

    private void handleResourceEvent(
            ResourceEvent resourceEvent,
            long midiTick,
            List<PlaybackTimeline.ResourceCatalogEntry> resourceCatalog,
            List<PlaybackTimeline.ResourceEventState> resourceEvents,
            int[] voiceMap,
            List<String> warnings,
            Set<String> warningKeys) {
        int lane = -1;
        int logicalChannel = -1;
        String target = "resource";
        int resourceIndex = -1;
        int linkedCatalogIndex = -1;
        int linkedChunkOffset = -1;
        int extraParamLow6 = -1;
        int extraParam2x = -1;
        int valueLow6 = -1;
        int value2x = -1;
        int rawSubvalue = -1;
        boolean clearsChannelConfig = false;
        int cachedConfigValue = -1;
        int backendConfigValue = -1;

        if (resourceEvent.body.length >= 1) {
            lane = (resourceEvent.body[0] >> 6) & 0x03;
        }

        switch (resourceEvent.command) {
            case 0x00:
            case 0x01:
                if (resourceEvent.body.length >= 1) {
                    resourceIndex = resourceEvent.body[0] & 0x3F;
                    logicalChannel = lane + (4 * resourceEvent.trackIndex);
                    if (resourceEvent.body.length >= 2) {
                        extraParamLow6 = resourceEvent.body[1] & 0x3F;
                        extraParam2x = 2 * extraParamLow6;
                    } else if (resourceEvent.command == 0x00) {
                        extraParam2x = 126;
                    }
                    PlaybackTimeline.ResourceCatalogEntry linked = findActiveAdatByIndex(resourceCatalog, resourceIndex);
                    if (linked != null) {
                        linkedCatalogIndex = linked.catalogIndex;
                        linkedChunkOffset = linked.offset;
                    } else {
                        addWarningOnce(
                                warnings,
                                warningKeys,
                                "adat_unlinked_" + resourceIndex,
                                "Resource event index " + resourceIndex
                                        + " could not be linked to any active ainf/adat table entry.");
                    }
                }
                break;
            case 0x80:
            case 0x81:
                if (resourceEvent.body.length >= 1) {
                    logicalChannel = lane + (4 * resourceEvent.trackIndex);
                    target = "audio";
                    valueLow6 = resourceEvent.body[0] & 0x3F;
                    value2x = 2 * valueLow6;
                }
                break;
            case 0x90:
                if (resourceEvent.body.length >= 1) {
                    int packed = resourceEvent.body[0] & 0xFF;
                    boolean audioTarget = (packed & 0x20) != 0;
                    target = audioTarget ? "audio" : "synth";
                    rawSubvalue = packed & 0x1F;
                    clearsChannelConfig = rawSubvalue == 31;
                    cachedConfigValue = clearsChannelConfig ? 0 : rawSubvalue + 1;
                    backendConfigValue = clearsChannelConfig ? 0 : rawSubvalue + 2;
                    if (lane >= 0) {
                        int localLane = lane + (4 * resourceEvent.trackIndex);
                        logicalChannel = audioTarget ? localLane : resolveVoiceMap(voiceMap, localLane);
                    }
                }
                break;
            default:
                break;
        }

        resourceEvents.add(new PlaybackTimeline.ResourceEventState(
                resourceEvent.trackIndex,
                resourceEvent.rawTick,
                midiTick,
                resourceEvent.command,
                resourceEvent.name,
                lane,
                logicalChannel,
                target,
                resourceIndex,
                linkedCatalogIndex,
                linkedChunkOffset,
                extraParamLow6,
                extraParam2x,
                valueLow6,
                value2x,
                rawSubvalue,
                clearsChannelConfig,
                cachedConfigValue,
                backendConfigValue));
    }

    private static PlaybackTimeline.ResourceCatalogEntry findActiveAdatByIndex(
            List<PlaybackTimeline.ResourceCatalogEntry> resourceCatalog,
            int adatIndex) {
        for (PlaybackTimeline.ResourceCatalogEntry entry : resourceCatalog) {
            if ("adat".equals(entry.chunkId) && entry.activeAdatIndex == adatIndex) {
                return entry;
            }
        }
        return null;
    }

    private static LegacySelectorSummary extractLegacySelectorSummary(byte[] payload) {
        if (payload.length < 4) {
            return null;
        }

        int selectorHeaderLength = readBe16(payload, 0);
        if (selectorHeaderLength < 2) {
            return null;
        }

        int selectorSectionEnd = 2 + selectorHeaderLength;
        if (selectorSectionEnd > payload.length) {
            return null;
        }

        int selectorId = payload[2] & 0xFF;
        int selectorFlags = payload[3] & 0xFF;
        int trailingPayloadLength = payload.length - selectorSectionEnd;
        int adpmByte0 = -1;
        int adpmByte1 = -1;
        int adpmByte2Low3 = -1;
        int adpmByte2Bit3 = -1;

        int offset = 4;
        while (offset + 6 <= selectorSectionEnd) {
            String id = new String(payload, offset, 4);
            int length = readBe16(payload, offset + 4);
            int payloadStart = offset + 6;
            int payloadEnd = payloadStart + length;
            if (payloadEnd > selectorSectionEnd) {
                return null;
            }
            if ("adpm".equals(id) && length >= 3) {
                adpmByte0 = payload[payloadStart] & 0xFF;
                adpmByte1 = payload[payloadStart + 1] & 0xFF;
                int byte2 = payload[payloadStart + 2] & 0xFF;
                adpmByte2Low3 = byte2 & 0x07;
                adpmByte2Bit3 = (byte2 >> 3) & 0x01;
                break;
            }
            offset = payloadEnd;
        }

        return new LegacySelectorSummary(
                selectorHeaderLength,
                selectorId,
                selectorFlags,
                trailingPayloadLength,
                adpmByte0,
                adpmByte1,
                adpmByte2Low3,
                adpmByte2Bit3);
    }

    private void handleSystemEvent(
            SystemEvent systemEvent,
            long midiTick,
            ControlCollector controlCollector,
            List<PlaybackTimeline.UnmappedControlEvent> unmappedControls,
            ChannelState[] channels,
            OutputLaneTracker outputLaneTracker,
            int[] voiceMap,
            List<PlaybackTimeline.OrdinaryNativeControl> ordinaryNativeControls,
            List<String> warnings,
            Set<String> warningKeys) {
        if (isTempo(systemEvent)) {
            return;
        }

        boolean reportAsUnmapped = false;
        switch (systemEvent.command) {
            case 0xB0:
                controlCollector.emitMasterVolume(systemEvent.trackIndex, systemEvent.command, systemEvent.name, midiTick,
                        clamp(0, 127, systemEvent.value));
                break;
            case 0xD0:
            case 0xDC:
            case 0xDD:
            case 0xDE:
            case 0xDF:
                reportAsUnmapped = true;
                break;
            case 0xBA:
                applyPatchModeChange(systemEvent, midiTick, controlCollector, channels, outputLaneTracker);
                reportAsUnmapped = true;
                break;
            case 0xE0:
                applyProgramChange(systemEvent, midiTick, controlCollector, channels, outputLaneTracker, voiceMap,
                        ordinaryNativeControls, warnings, warningKeys);
                break;
            case 0xE1:
                applyBankChange(systemEvent, midiTick, controlCollector, channels, outputLaneTracker, voiceMap, warnings,
                        warningKeys);
                break;
            case 0xE2:
                applyAbsoluteLevel(systemEvent, midiTick, controlCollector, channels, outputLaneTracker, voiceMap,
                        ordinaryNativeControls, warnings, warningKeys);
                break;
            case 0xE3:
                applyPan(systemEvent, midiTick, controlCollector, channels, outputLaneTracker, voiceMap,
                        ordinaryNativeControls, warnings, warningKeys);
                break;
            case 0xE4:
                applyPitchCoarse(systemEvent, midiTick, controlCollector, channels, outputLaneTracker, voiceMap,
                        ordinaryNativeControls, warnings, warningKeys);
                break;
            case 0xE5:
                applyVoiceAssignment(systemEvent, voiceMap);
                reportAsUnmapped = true;
                break;
            case 0xE6:
                applyRelativeLevel(systemEvent, midiTick, controlCollector, channels, outputLaneTracker, voiceMap,
                        ordinaryNativeControls, warnings, warningKeys);
                break;
            case 0xE7:
                applyPitchRange(systemEvent, midiTick, controlCollector, channels, outputLaneTracker, voiceMap,
                        ordinaryNativeControls, warnings, warningKeys);
                break;
            case 0xE8:
                applyPitchFine(systemEvent, midiTick, controlCollector, channels, outputLaneTracker, voiceMap,
                        ordinaryNativeControls, warnings, warningKeys);
                break;
            case 0xE9:
                applyFineCache(systemEvent, midiTick, controlCollector, channels, outputLaneTracker, voiceMap,
                        ordinaryNativeControls, warnings, warningKeys);
                break;
            case 0xEA:
                applyModulation(systemEvent, midiTick, controlCollector, channels, outputLaneTracker, voiceMap,
                        ordinaryNativeControls, warnings, warningKeys);
                break;
            default:
                reportAsUnmapped = true;
                break;
        }

        if (reportAsUnmapped) {
            unmappedControls.add(new PlaybackTimeline.UnmappedControlEvent(
                    systemEvent.trackIndex,
                    systemEvent.command,
                    systemEvent.name,
                    systemEvent.rawTick,
                    midiTick,
                    systemEvent.value,
                    systemEvent.part));
        }
    }

    private void applyProgramChange(
            SystemEvent systemEvent,
            long midiTick,
            ControlCollector controlCollector,
            ChannelState[] channels,
            OutputLaneTracker outputLaneTracker,
            int[] voiceMap,
            List<PlaybackTimeline.OrdinaryNativeControl> ordinaryNativeControls,
            List<String> warnings,
            Set<String> warningKeys) {
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
        emitPatchIfNeeded(controlCollector, channel, logicalChannel, outputLaneTracker, systemEvent.trackIndex,
                systemEvent.command, systemEvent.name, midiTick);
    }

    private void applyBankChange(
            SystemEvent systemEvent,
            long midiTick,
            ControlCollector controlCollector,
            ChannelState[] channels,
            OutputLaneTracker outputLaneTracker,
            int[] voiceMap,
            List<String> warnings,
            Set<String> warningKeys) {
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
        emitPatchIfNeeded(controlCollector, channel, logicalChannel, outputLaneTracker, systemEvent.trackIndex,
                systemEvent.command, systemEvent.name, midiTick);
    }

    private void applyAbsoluteLevel(
            SystemEvent systemEvent,
            long midiTick,
            ControlCollector controlCollector,
            ChannelState[] channels,
            OutputLaneTracker outputLaneTracker,
            int[] voiceMap,
            List<PlaybackTimeline.OrdinaryNativeControl> ordinaryNativeControls,
            List<String> warnings,
            Set<String> warningKeys) {
        int logicalChannel = resolveMappedControlChannel(systemEvent, channels, voiceMap, warnings, warningKeys);
        if (logicalChannel < 0) {
            return;
        }
        ChannelState channel = channels[logicalChannel];
        channel.level = systemEvent.value & 0x3F;
        channel.volumeCache = clamp(0, 127, channel.level * 2);
        observeOutputLaneActivity(outputLaneTracker, logicalChannel);
        recordOrdinaryNativeControl(systemEvent, midiTick, logicalChannel, ORDINARY_NATIVE_PATH_LEVEL_PAN,
                HOST_MAPPING_CC7_VOLUME, true, false, ordinaryNativeControls);
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
            List<PlaybackTimeline.OrdinaryNativeControl> ordinaryNativeControls,
            List<String> warnings,
            Set<String> warningKeys) {
        int logicalChannel = resolveMappedControlChannel(systemEvent, channels, voiceMap, warnings, warningKeys);
        if (logicalChannel < 0) {
            return;
        }
        ChannelState channel = channels[logicalChannel];
        int delta = (systemEvent.value & 0x3F) - 32;
        channel.level = clamp(0, 63, channel.level + delta);
        channel.volumeCache = clamp(0, 127, channel.level * 2);
        observeOutputLaneActivity(outputLaneTracker, logicalChannel);
        recordOrdinaryNativeControl(systemEvent, midiTick, logicalChannel, ORDINARY_NATIVE_PATH_LEVEL_PAN,
                HOST_MAPPING_CC7_VOLUME, true, false, ordinaryNativeControls);
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
            List<PlaybackTimeline.OrdinaryNativeControl> ordinaryNativeControls,
            List<String> warnings,
            Set<String> warningKeys) {
        int logicalChannel = resolveMappedControlChannel(systemEvent, channels, voiceMap, warnings, warningKeys);
        if (logicalChannel < 0) {
            return;
        }
        ChannelState channel = channels[logicalChannel];
        channel.pan = systemEvent.value & 0x3F;
        observeOutputLaneActivity(outputLaneTracker, logicalChannel);
        recordOrdinaryNativeControl(systemEvent, midiTick, logicalChannel, ORDINARY_NATIVE_PATH_LEVEL_PAN,
                HOST_MAPPING_CC10_PAN, true, false, ordinaryNativeControls);
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
            List<PlaybackTimeline.OrdinaryNativeControl> ordinaryNativeControls,
            List<String> warnings,
            Set<String> warningKeys) {
        int logicalChannel = resolveMappedControlChannel(systemEvent, channels, voiceMap, warnings, warningKeys);
        if (logicalChannel < 0) {
            return;
        }
        ChannelState channel = channels[logicalChannel];
        channel.pitchCoarse = systemEvent.value & 0x3F;
        observeOutputLaneActivity(outputLaneTracker, logicalChannel);
        recordOrdinaryNativeControl(systemEvent, midiTick, logicalChannel, ORDINARY_NATIVE_PATH_PITCH,
                HOST_MAPPING_PITCH_BEND, true, false, ordinaryNativeControls);
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
            List<PlaybackTimeline.OrdinaryNativeControl> ordinaryNativeControls,
            List<String> warnings,
            Set<String> warningKeys) {
        int logicalChannel = resolveMappedControlChannel(systemEvent, channels, voiceMap, warnings, warningKeys);
        if (logicalChannel < 0) {
            return;
        }
        ChannelState channel = channels[logicalChannel];
        channel.pitchFine = systemEvent.value & 0x3F;
        observeOutputLaneActivity(outputLaneTracker, logicalChannel);
        recordOrdinaryNativeControl(systemEvent, midiTick, logicalChannel, ORDINARY_NATIVE_PATH_PITCH,
                HOST_MAPPING_PITCH_BEND, true, false, ordinaryNativeControls);
        if (logicalChannel < MIDI_CHANNEL_COUNT) {
            controlCollector.emitPitchBend(systemEvent.trackIndex, systemEvent.command, systemEvent.name, logicalChannel, midiTick,
                    computePitchBend(channel));
        }
    }

    private void applyFineCache(
            SystemEvent systemEvent,
            long midiTick,
            ControlCollector controlCollector,
            ChannelState[] channels,
            OutputLaneTracker outputLaneTracker,
            int[] voiceMap,
            List<PlaybackTimeline.OrdinaryNativeControl> ordinaryNativeControls,
            List<String> warnings,
            Set<String> warningKeys) {
        int logicalChannel = resolveMappedControlChannel(systemEvent, channels, voiceMap, warnings, warningKeys);
        if (logicalChannel >= 0) {
            ChannelState channel = channels[logicalChannel];
            // The official lib ordinary parser updates the shared fine-byte cache
            // for E9 and returns through the common tail without an immediate backend
            // setter call. Host MIDI therefore keeps this cache-only behavior here.
            channel.pitchFine = systemEvent.value & 0x3F;
            observeOutputLaneActivity(outputLaneTracker, logicalChannel);
            recordOrdinaryNativeControl(systemEvent, midiTick, logicalChannel, ORDINARY_NATIVE_PATH_PITCH,
                    HOST_MAPPING_NONE, false, false, ordinaryNativeControls);
        }
    }

    private void applyPitchRange(
            SystemEvent systemEvent,
            long midiTick,
            ControlCollector controlCollector,
            ChannelState[] channels,
            OutputLaneTracker outputLaneTracker,
            int[] voiceMap,
            List<PlaybackTimeline.OrdinaryNativeControl> ordinaryNativeControls,
            List<String> warnings,
            Set<String> warningKeys) {
        int logicalChannel = resolveMappedControlChannel(systemEvent, channels, voiceMap, warnings, warningKeys);
        if (logicalChannel < 0) {
            return;
        }
        int range = systemEvent.value & 0x3F;
        if (range > 24) {
            addWarningOnce(warnings, warningKeys, "pitch_range_" + logicalChannel + "_" + range,
                    "Ignoring pitch range " + range + " on logical channel " + logicalChannel
                            + " because the verified parser only accepts values <= 24.");
            return;
        }
        ChannelState channel = channels[logicalChannel];
        channel.pitchRange = range;
        observeOutputLaneActivity(outputLaneTracker, logicalChannel);
        recordOrdinaryNativeControl(systemEvent, midiTick, logicalChannel, ORDINARY_NATIVE_PATH_PITCH,
                HOST_MAPPING_RPN_PITCH_RANGE, true, false, ordinaryNativeControls);
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
            List<PlaybackTimeline.OrdinaryNativeControl> ordinaryNativeControls,
            List<String> warnings,
            Set<String> warningKeys) {
        int logicalChannel = resolveMappedControlChannel(systemEvent, channels, voiceMap, warnings, warningKeys);
        if (logicalChannel < 0) {
            return;
        }
        ChannelState channel = channels[logicalChannel];
        channel.modulation = systemEvent.value & 0x3F;
        observeOutputLaneActivity(outputLaneTracker, logicalChannel);
        recordOrdinaryNativeControl(systemEvent, midiTick, logicalChannel, ORDINARY_NATIVE_PATH_LOOKUP,
                HOST_MAPPING_CC1_MODULATION, true, false, ordinaryNativeControls);
        if (logicalChannel < MIDI_CHANNEL_COUNT) {
            controlCollector.emitModulation(systemEvent.trackIndex, systemEvent.command, systemEvent.name, logicalChannel, midiTick,
                    channel.modulation * 2);
        }
    }

    private void recordOrdinaryNativeControl(
            SystemEvent systemEvent,
            long midiTick,
            int logicalChannel,
            String nativePath,
            String hostMapping,
            boolean hostEventEmitted,
            boolean hostMappingProxy,
            List<PlaybackTimeline.OrdinaryNativeControl> ordinaryNativeControls) {
        ordinaryNativeControls.add(new PlaybackTimeline.OrdinaryNativeControl(
                systemEvent.trackIndex,
                systemEvent.command,
                systemEvent.name,
                systemEvent.rawTick,
                midiTick,
                logicalChannel,
                nativePath,
                hostMapping,
                hostEventEmitted,
                hostMappingProxy,
                true,
                true,
                ORDINARY_NATIVE_REPLACEMENT_OVERLAP_SAMPLES));
    }

    private void applyVoiceAssignment(SystemEvent systemEvent, int[] voiceMap) {
        if (systemEvent.part < 0) {
            return;
        }
        int lane = laneIndex(systemEvent.trackIndex, systemEvent.part);
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
        if (logicalChannel < 0 || logicalChannel >= channels.length) {
            return;
        }
        ChannelState channel = channels[logicalChannel];
        channel.mode = systemEvent.value & 0x07;
        observePatchSnapshot(channel);
        observeOutputLaneActivity(outputLaneTracker, logicalChannel);
        channel.patchDirty = true;
        if (channel.mode == 1) {
            emitPatchIfNeeded(controlCollector, channel, logicalChannel, outputLaneTracker, systemEvent.trackIndex,
                    systemEvent.command, systemEvent.name, midiTick);
        }
    }

    private int resolveMappedControlChannel(
            SystemEvent systemEvent,
            ChannelState[] channels,
            int[] voiceMap,
            List<String> warnings,
            Set<String> warningKeys) {
        if (systemEvent.part < 0) {
            return -1;
        }
        int lane = laneIndex(systemEvent.trackIndex, systemEvent.part);
        int logicalChannel = resolveVoiceMap(voiceMap, lane);
        if (logicalChannel < 0 || logicalChannel >= channels.length) {
            addWarningOnce(warnings, warningKeys,
                    "control_channel_" + systemEvent.trackIndex + "_" + systemEvent.part + "_" + logicalChannel,
                    "Skipping control " + systemEvent.name + " mapped outside logical-channel range: track="
                            + systemEvent.trackIndex + " part=" + systemEvent.part + " -> " + logicalChannel);
            return -1;
        }
        if (logicalChannel >= MIDI_CHANNEL_COUNT) {
            addWarningOnce(warnings, warningKeys, "control_host_channel_" + logicalChannel,
                    "Logical channel " + logicalChannel + " is outside the host MIDI bridge's 16-channel surface.");
        }
        return logicalChannel;
    }

    private void emitInitialMidiDefaults(ControlCollector controlCollector, ChannelState[] channels) {
        for (int midiChannel = 0; midiChannel < MIDI_CHANNEL_COUNT; midiChannel++) {
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
            OutputLaneTracker outputLaneTracker,
            int sourceTrack,
            int sourceCommand,
            String sourceName,
            long midiTick) {
        if (logicalChannel < 0 || logicalChannel >= MIDI_CHANNEL_COUNT) {
            return;
        }
        HostPatch patch = translateHostPatch(channel);
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
        emitSyntheticPatchSyncControls(controlCollector, channel, logicalChannel, sourceTrack, sourceCommand, midiTick);
        channel.patchDirty = false;
        channel.lastPatch = patch;
    }

    private void emitSyntheticPatchSyncControls(
            ControlCollector controlCollector,
            ChannelState channel,
            int logicalChannel,
            int sourceTrack,
            int sourceCommand,
            long midiTick) {
        if (!PSM_EMIT_SYNTHETIC_PATCH_SYNC_CONTROLS || logicalChannel < 0 || logicalChannel >= MIDI_CHANNEL_COUNT) {
            return;
        }
        // Mirror the confirmed PSM export-side synthetic sync tail:
        // C0 -> B0 07 cached volume -> B0 0A value 0.
        controlCollector.emitVolume(
                sourceTrack,
                sourceCommand,
                PATCH_SYNC_VOLUME_SOURCE,
                logicalChannel,
                midiTick,
                computePsmVolumeSync(channel, logicalChannel));
        controlCollector.emitPan(
                sourceTrack,
                sourceCommand,
                PATCH_SYNC_PAN_SOURCE,
                logicalChannel,
                midiTick,
                0);
    }

    private HostPatch translateHostPatch(ChannelState channel) {
        int mode = channel.mode & 0x07;
        NativePatchState nativePatch = resolveOfficialNativePatchState(channel);
        // The official DLL tuple is exported as an authoritative comparison surface,
        // but it is not a drop-in GM mapping oracle. We keep the existing
        // PSM-derived host patch/drum proxy path here because native families such
        // as 120 do not land on accurate MIDI output by direct substitution alone.
        PatchSelection selection = resolveHostPatchSelection(channel);
        int patchWord = selection.patchWord;
        int program = patchWord & 0x7F;
        if (mode != 0 && mode != 1) {
            return new HostPatch(
                    true,
                    0,
                    0,
                    channel.rawPatchWord,
                    PSM_LATE_PATCH_ENTRY_EMPTY,
                    PATCH_SOURCE_DEFAULT_ZERO,
                    nativePatch.mode,
                    nativePatch.bank,
                    nativePatch.program,
                    nativePatch.kind,
                    nativePatch.sub,
                    nativePatch.value);
        }
        return new HostPatch(
                false,
                program,
                patchWord,
                channel.rawPatchWord,
                selection.lateEntry,
                selection.source,
                nativePatch.mode,
                nativePatch.bank,
                nativePatch.program,
                nativePatch.kind,
                nativePatch.sub,
                nativePatch.value);
    }

    private static void updateRawPatchWord(ChannelState channel) {
        channel.rawPatchWord = composeOrdinaryPatchWord(channel.bank, channel.program);
    }

    private static void observePatchSnapshot(ChannelState channel) {
        if (!channel.allowsOrdinaryNotes() || !channel.hasProgramEvent) {
            return;
        }
        if (hasSpecialSidebandFamily(channel.rawPatchWord)) {
            // sub_4143F5 stores the special-family sentinel 0x81 into word_441875
            // instead of the ordinary program_low7 + 1 projection, and that
            // sentinel then stays resident in the late table surface.
            channel.latePatchTableEntry = PSM_LATE_PATCH_ENTRY_SIDEBAND_SENTINEL;
            return;
        }
        int lateEntry = ordinaryLatePatchEntry(channel.program);
        // Model PSMPlayer's zero-filled/write-once word_441875 -> word_44F509 surface:
        // the first ordinary program sticks, later ordinary differences only mark
        // mismatch, and the special-family 0x81 sentinel overrides the ordinary entry.
        if (channel.latePatchTableEntry == PSM_LATE_PATCH_ENTRY_EMPTY) {
            channel.latePatchTableEntry = lateEntry;
            return;
        }
        if (channel.latePatchTableEntry != lateEntry) {
            channel.latePatchTableMismatch = true;
        }
    }

    private PatchSelection resolveHostPatchSelection(ChannelState channel) {
        PatchSelection authoritativeOrdinary = resolveAuthoritativeOrdinaryPatchSelection(channel);
        if (authoritativeOrdinary != null) {
            return authoritativeOrdinary;
        }
        PatchSelection selection = resolveVerifiedLatePatchSelection(channel);
        if (!selection.authoritative && HOST_PATCH_USE_OBSERVED_ORDINARY_SURFACE) {
            PatchSelection observed = resolveObservedOrdinaryPatchSelection(channel);
            if (observed != null) {
                selection = observed;
            }
        }
        int patchWord = selection.patchWord;
        if (PSM_DEFAULT_ENABLE_LATE_PATCH_REMAP) {
            patchWord = applyLatePatchRemap(patchWord, 1);
        } else {
            patchWord = clamp(0, 127, patchWord);
        }
        return selection.withPatchWord(patchWord);
    }

    private PatchSelection resolveAuthoritativeOrdinaryPatchSelection(ChannelState channel) {
        if (!channel.allowsOrdinaryNotes() || !channel.hasProgramEvent) {
            return null;
        }
        int patchWord = channel.rawPatchWord & 0x0FFF;
        int lateEntry = ordinaryLatePatchEntry(patchWord & 0x7F);
        return new PatchSelection(lateEntry, patchWord, PATCH_SOURCE_HOST_PROXY_PATCH12, true);
    }

    private static NativePatchState resolveOfficialNativePatchState(ChannelState channel) {
        int mode = channel.mode & 0x07;
        int bank = channel.bank & 0x3F;
        int program = channel.program & 0x3F;
        if (mode == 0) {
            if (bank == 0) {
                return new NativePatchState(mode, bank, program, 125, 0, program);
            }
            if (bank == 4 || bank == 5) {
                return new NativePatchState(mode, bank, program, 121, 1, selectorWithOddBankPage(program, bank));
            }
            if (bank == 0x36) {
                return new NativePatchState(mode, bank, program, 17, 0, program);
            }
            if (bank < 0x34) {
                return new NativePatchState(mode, bank, program, 121, 0, selectorWithOddBankPage(program, bank));
            }
            return new NativePatchState(mode, bank, program, 255, 0, 0);
        }
        if (mode == 1) {
            if (bank == 4 || bank == 5) {
                return new NativePatchState(mode, bank, program, 120, 1, 25);
            }
            if (bank == 0x34) {
                return new NativePatchState(mode, bank, program, 20, 0, program);
            }
            if (bank == 0x36) {
                return new NativePatchState(mode, bank, program, 16, 0, program);
            }
            if (bank < 0x34) {
                return new NativePatchState(mode, bank, program, 120, 0, selectorWithOddBankPage(program, bank));
            }
            return new NativePatchState(mode, bank, program, 255, 0, 0);
        }
        return new NativePatchState(mode, bank, program, 255, 0, 0);
    }

    private static int selectorWithOddBankPage(int program, int bank) {
        return clamp(0, 127, (program & 0x3F) + (((bank & 1) != 0) ? 64 : 0));
    }

    private PatchSelection resolveVerifiedLatePatchSelection(ChannelState channel) {
        if (PSM_DEFAULT_USE_INSTRUMENT_SET && isPlayableLatePatchEntry(channel.latePatchOverrideEntry)) {
            return PatchSelection.fromLateEntry(channel.latePatchOverrideEntry, PATCH_SOURCE_INSTRUMENT_OVERRIDE, true);
        }
        if (PSM_DEFAULT_LATE_TABLE_FALLBACK && isPlayableLatePatchEntry(channel.latePatchTableEntry)) {
            return PatchSelection.fromLateEntry(channel.latePatchTableEntry, PATCH_SOURCE_PRESET_TABLE, true);
        }
        return PatchSelection.fromLateEntry(PSM_LATE_PATCH_ENTRY_EMPTY, PATCH_SOURCE_DEFAULT_ZERO, false);
    }

    private PatchSelection resolveObservedOrdinaryPatchSelection(ChannelState channel) {
        if (!isPlayableLatePatchEntry(channel.latePatchTableEntry)) {
            return null;
        }
        return PatchSelection.fromLateEntry(
                channel.latePatchTableEntry,
                channel.latePatchTableMismatch ? PATCH_SOURCE_OBSERVED_ORDINARY_MISMATCH : PATCH_SOURCE_OBSERVED_ORDINARY,
                false);
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

    private long flushExpiredNotes(
            int currentRawTick,
            Map<Integer, ActiveNote> activeNotes,
            List<PlaybackTimeline.CompiledNote> notes) {
        long maxTick = -1L;
        Iterator<Map.Entry<Integer, ActiveNote>> iterator = activeNotes.entrySet().iterator();
        while (iterator.hasNext()) {
            ActiveNote active = iterator.next().getValue();
            if (active.rawEndTick > currentRawTick) {
                continue;
            }
            notes.add(active.toFinalCompiledNote());
            maxTick = Math.max(maxTick, active.midiEndTick);
            iterator.remove();
        }
        return maxTick;
    }

    private static int[] buildHostOutputChannelMap(OutputLaneTracker outputLaneTracker) {
        int[] outputChannelMap = createIdentityVoiceMap(MIDI_CHANNEL_COUNT);
        if (!PSM_APPLY_WRITER_EXPORT_OUTPUT_REMAP || outputLaneTracker == null || !outputLaneTracker.hasAuthoritativeMask()) {
            return outputChannelMap;
        }
        if (outputLaneTracker.usesIdentityMap()) {
            return outputChannelMap;
        }
        int nextMelodicChannel = 0;
        for (int logicalChannel = 0; logicalChannel < MIDI_CHANNEL_COUNT; logicalChannel++) {
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

    private static List<PlaybackTimeline.MappedControlEvent> applyOrdinaryLiveMixChaseApproximation(
            List<PlaybackTimeline.MappedControlEvent> mappedControls,
            List<PlaybackTimeline.CompiledNote> notes,
            List<PlaybackTimeline.TempoPoint> tempoPoints,
            PlaybackTimeline.LoopInfo loopInfo,
            long totalMidiTicks) {
        if (!HOST_APPROXIMATE_ORDINARY_LIVE_MIX || mappedControls.isEmpty() || notes.isEmpty()) {
            return mappedControls;
        }

        List<PlaybackTimeline.MappedControlEvent> ordered =
                new ArrayList<PlaybackTimeline.MappedControlEvent>(mappedControls);
        Collections.sort(ordered, MAPPED_CONTROL_COMPARATOR);

        Map<Integer, List<PlaybackTimeline.CompiledNote>> notesByChannel = groupNotesByMidiChannel(notes);
        Map<Integer, List<PlaybackTimeline.MappedControlEvent>> groupedStreams =
                new LinkedHashMap<Integer, List<PlaybackTimeline.MappedControlEvent>>();
        List<PlaybackTimeline.MappedControlEvent> passthrough =
                new ArrayList<PlaybackTimeline.MappedControlEvent>(ordered.size());

        for (PlaybackTimeline.MappedControlEvent control : ordered) {
            if (isVolumeOrPanControl(control)) {
                Integer key = Integer.valueOf((control.midiChannel << 8) | (control.data1 & 0x7F));
                List<PlaybackTimeline.MappedControlEvent> stream = groupedStreams.get(key);
                if (stream == null) {
                    stream = new ArrayList<PlaybackTimeline.MappedControlEvent>();
                    groupedStreams.put(key, stream);
                }
                stream.add(control);
            } else {
                passthrough.add(control);
            }
        }

        List<PlaybackTimeline.MappedControlEvent> rewritten =
                new ArrayList<PlaybackTimeline.MappedControlEvent>(mappedControls.size() + 64);
        rewritten.addAll(passthrough);

        int nextOrder = 0;
        for (List<PlaybackTimeline.MappedControlEvent> stream : groupedStreams.values()) {
            Integer previousValue = null;
            for (int i = 0; i < stream.size(); i++) {
                PlaybackTimeline.MappedControlEvent control = stream.get(i);
                int targetValue = clamp(0, 127, control.data2);
                long boundaryTick = computeMixChaseBoundaryTick(stream, i, loopInfo, totalMidiTicks);
                long chaseWindowTicks = Math.min(
                        computeNativeSmoothingWindowTicks(control.midiTick, tempoPoints),
                        Math.max(0L, boundaryTick - control.midiTick));

                if (previousValue != null
                        && isOrdinaryLiveMixProxyCandidate(control)
                        && chaseWindowTicks > 0L
                        && hasStrictlyActiveNote(notesByChannel.get(Integer.valueOf(control.midiChannel)), control.midiTick)) {
                    nextOrder = appendMixChasedControls(rewritten, control, previousValue.intValue(), targetValue,
                            chaseWindowTicks, nextOrder);
                } else {
                    rewritten.add(copyMappedControl(control, control.midiTick, targetValue, nextOrder++, control.sourceName));
                }
                previousValue = Integer.valueOf(targetValue);
            }
        }

        Collections.sort(rewritten, MAPPED_CONTROL_COMPARATOR);
        return rewritten;
    }

    private static Map<Integer, List<PlaybackTimeline.CompiledNote>> groupNotesByMidiChannel(
            List<PlaybackTimeline.CompiledNote> notes) {
        Map<Integer, List<PlaybackTimeline.CompiledNote>> grouped =
                new LinkedHashMap<Integer, List<PlaybackTimeline.CompiledNote>>();
        for (PlaybackTimeline.CompiledNote note : notes) {
            Integer key = Integer.valueOf(note.midiChannel);
            List<PlaybackTimeline.CompiledNote> channelNotes = grouped.get(key);
            if (channelNotes == null) {
                channelNotes = new ArrayList<PlaybackTimeline.CompiledNote>();
                grouped.put(key, channelNotes);
            }
            channelNotes.add(note);
        }
        return grouped;
    }

    private static boolean isVolumeOrPanControl(PlaybackTimeline.MappedControlEvent control) {
        return control != null
                && control.status == ShortMessage.CONTROL_CHANGE
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

    private static boolean hasStrictlyActiveNote(List<PlaybackTimeline.CompiledNote> notes, long midiTick) {
        if (notes == null || notes.isEmpty()) {
            return false;
        }
        for (PlaybackTimeline.CompiledNote note : notes) {
            if (note.midiStartTick < midiTick && note.midiEndTick > midiTick) {
                return true;
            }
        }
        return false;
    }

    private static long computeMixChaseBoundaryTick(
            List<PlaybackTimeline.MappedControlEvent> stream,
            int index,
            PlaybackTimeline.LoopInfo loopInfo,
            long totalMidiTicks) {
        PlaybackTimeline.MappedControlEvent control = stream.get(index);
        long boundary = Math.max(control.midiTick, totalMidiTicks);
        if (index + 1 < stream.size()) {
            boundary = Math.min(boundary, stream.get(index + 1).midiTick);
        }
        if (loopInfo != null && loopInfo.hasLoop && control.midiTick < loopInfo.loopEndMidiTick) {
            boundary = Math.min(boundary, loopInfo.loopEndMidiTick);
        }
        return Math.max(control.midiTick, boundary);
    }

    private static long computeNativeSmoothingWindowTicks(
            long midiTick,
            List<PlaybackTimeline.TempoPoint> tempoPoints) {
        PlaybackTimeline.TempoPoint point = tempoPointAtOrBefore(tempoPoints, midiTick);
        long mpqn = point != null ? point.mpqn : 500000L;
        long numerator = (long) ORDINARY_NATIVE_REPLACEMENT_OVERLAP_SAMPLES
                * PlaybackTimeline.MIDI_PPQ
                * 1000000L;
        long denominator = (long) ORDINARY_NATIVE_RENDER_OUTPUT_RATE * Math.max(1L, mpqn);
        return Math.max(1L, (numerator + (denominator / 2L)) / denominator);
    }

    private static PlaybackTimeline.TempoPoint tempoPointAtOrBefore(
            List<PlaybackTimeline.TempoPoint> tempoPoints,
            long midiTick) {
        if (tempoPoints == null || tempoPoints.isEmpty()) {
            return null;
        }
        PlaybackTimeline.TempoPoint current = tempoPoints.get(0);
        for (PlaybackTimeline.TempoPoint point : tempoPoints) {
            if (point.midiTick > midiTick) {
                break;
            }
            current = point;
        }
        return current;
    }

    private static int appendMixChasedControls(
            List<PlaybackTimeline.MappedControlEvent> rewritten,
            PlaybackTimeline.MappedControlEvent original,
            int previousValue,
            int targetValue,
            long chaseWindowTicks,
            int nextOrder) {
        int delta = targetValue - previousValue;
        if (delta == 0) {
            rewritten.add(copyMappedControl(original, original.midiTick, targetValue, nextOrder++, original.sourceName));
            return nextOrder;
        }

        int eventCount = (int) Math.max(2L, Math.min((long) HOST_LIVE_MIX_CHASE_STEP_COUNT, chaseWindowTicks + 1L));
        long lastTick = Long.MIN_VALUE;
        int lastValue = previousValue;
        String syntheticName = original.sourceName + HOST_LIVE_MIX_CHASE_SUFFIX;

        for (int step = 1; step <= eventCount; step++) {
            long offset = eventCount == 1
                    ? 0L
                    : (chaseWindowTicks * (step - 1L)) / (eventCount - 1L);
            long tick = original.midiTick + offset;
            int value = previousValue + (int) Math.round((double) delta * step / (double) eventCount);
            if (step == eventCount) {
                value = targetValue;
            }
            if (value == lastValue && step < eventCount) {
                continue;
            }
            if (tick == lastTick && value == lastValue) {
                continue;
            }
            rewritten.add(copyMappedControl(original, tick, value, nextOrder++, syntheticName));
            lastTick = tick;
            lastValue = value;
        }

        if (lastValue != targetValue) {
            rewritten.add(copyMappedControl(
                    original,
                    original.midiTick + chaseWindowTicks,
                    targetValue,
                    nextOrder++,
                    syntheticName));
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
                original.logicalChannel,
                original.midiTrackIndex,
                midiTick,
                original.status,
                original.data1,
                clamp(0, 127, data2),
                original.patchWord,
                original.rawPatchWord,
                original.latePatchEntry,
                original.patchSource,
                original.nativeMode,
                original.nativeBank,
                original.nativeProgram,
                original.nativeKind,
                original.nativeSub,
                original.nativeValue,
                order);
    }

    private static long maxMappedControlTick(List<PlaybackTimeline.MappedControlEvent> mappedControls) {
        long maxTick = 0L;
        for (PlaybackTimeline.MappedControlEvent control : mappedControls) {
            maxTick = Math.max(maxTick, control.midiTick);
        }
        return maxTick;
    }

    private static List<PlaybackTimeline.CompiledNote> remapCompiledNotes(
            List<PlaybackTimeline.CompiledNote> notes,
            int[] outputChannelMap) {
        List<PlaybackTimeline.CompiledNote> remapped = new ArrayList<PlaybackTimeline.CompiledNote>(notes.size());
        for (PlaybackTimeline.CompiledNote note : notes) {
            int midiChannel = remapMidiChannel(note.midiChannel, outputChannelMap);
            remapped.add(new PlaybackTimeline.CompiledNote(
                    note.sourceTrack,
                    note.sourceVoice,
                    midiChannel,
                    midiChannel + 1,
                    note.midiNote,
                    note.velocity,
                    note.rawStartTick,
                    note.rawEndTick,
                    note.midiStartTick,
                    note.midiEndTick));
        }
        return remapped;
    }

    private static List<PlaybackTimeline.MappedControlEvent> remapMappedControls(
            List<PlaybackTimeline.MappedControlEvent> mappedControls,
            int[] outputChannelMap) {
        List<PlaybackTimeline.MappedControlEvent> remapped =
                new ArrayList<PlaybackTimeline.MappedControlEvent>(mappedControls.size());
        for (PlaybackTimeline.MappedControlEvent control : mappedControls) {
            int midiChannel = remapMidiChannel(control.midiChannel, outputChannelMap);
            remapped.add(new PlaybackTimeline.MappedControlEvent(
                    control.sourceTrack,
                    control.sourceCommand,
                    control.sourceName,
                    midiChannel,
                    control.logicalChannel,
                    midiChannel + 1,
                    control.midiTick,
                    control.status,
                    control.data1,
                    control.data2,
                    control.patchWord,
                    control.rawPatchWord,
                    control.latePatchEntry,
                    control.patchSource,
                    control.nativeMode,
                    control.nativeBank,
                    control.nativeProgram,
                    control.nativeKind,
                    control.nativeSub,
                    control.nativeValue,
                    control.order));
        }
        return remapped;
    }

    private static ChannelState[] createChannelStates() {
        ChannelState[] channels = new ChannelState[MAX_LOGICAL_CHANNELS];
        for (int i = 0; i < channels.length; i++) {
            channels[i] = new ChannelState();
        }
        return channels;
    }

    private static int[] createIdentityVoiceMap(int count) {
        int[] map = new int[count];
        for (int i = 0; i < count; i++) {
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

    private static int clamp(int min, int max, int value) {
        return Math.max(min, Math.min(max, value));
    }

    private static int readBe16(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    private static void addWarningOnce(List<String> warnings, Set<String> warningKeys, String key, String warning) {
        if (warningKeys.add(key)) {
            warnings.add(warning);
        }
    }

    private static PlaybackTimeline.OrdinaryNativeModel createOrdinaryNativeModel() {
        return new PlaybackTimeline.OrdinaryNativeModel(
                true,
                ORDINARY_NATIVE_REPLACEMENT_OVERLAP_SAMPLES,
                ORDINARY_NATIVE_SELECTED_HALF_RULE,
                true,
                true,
                true,
                true);
    }

    private static NarrativeSections createNarrativeSections() {
        List<String> implementationFacts = new ArrayList<String>();
        implementationFacts.add("Runtime is pure Java.");
        implementationFacts.add("Ordinary-track playback follows the frozen note/state-machine spec for note=0001 samples.");
        implementationFacts.add("Bridge patch audit now exports the official plugin/native tuple reconstructed from lib003 sub_10010CA0(): (mode, bank, program) -> (kind, sub, value).");
        implementationFacts.add("Host Program Change remains a clean-room proxy over that native tuple; the official DLL uses synth-native family/sub/value routing rather than GM bank/program selection, so the native tuple is kept as an audit oracle rather than a direct MIDI mapping replacement.");
        implementationFacts.add("Host MIDI volume and pan remain channel-wide proxies, but ordinary E2/E3/E6 now add a short active-note chase over one native 128-sample window instead of stepping immediately.");
        implementationFacts.add("Verified normal-synth level/pan refresh now closes through stacked global/group/channel scalars, cached per-voice note-side weighting, descriptor-scaled target-gain rewrites, and a render-side 128-sample smoothing step rather than one flat channel-wide jump.");
        implementationFacts.add("The current host lane plan preserves the verified dedicated special lane model by reserving logical channel 9 as the special/percussion lane in the default ordinary bridge path.");
        implementationFacts.add("The verified normal-synth ordinary path keeps per-voice dual-half continuity with a one-block 128-sample replacement overlap, and bridge.json surfaces those native continuity constraints.");

        List<String> runtimePolicies = new ArrayList<String>();
        runtimePolicies.add("Machine-dependent and non-melody families are preserved and exported, but this build plays ordinary-track MIDI only.");
        runtimePolicies.add("Finite ordinary-loop playback now materializes intro plus repeated loop-body passes in-sequence, so cross-boundary note/control carry-over is preserved without a transport restart for those bounded passes.");

        List<String> knownLimitations = new ArrayList<String>();
        knownLimitations.add("The current host MIDI path still cannot realize the official half-selection handoff or per-voice replacement-half refresh directly.");
        knownLimitations.add("Native family/sub/value selection is now audited directly, but the final host patch choice is still a heuristic proxy rather than the official timbre engine.");
        knownLimitations.add("Representative drum output still requires a host-side correction layer beyond the raw official family tuple; the current MIDI bridge therefore preserves the existing PSM-derived drum mapping path instead of directly substituting the official family chain.");
        knownLimitations.add("Ordinary E2/E3/E6 still collapse to channel-wide CC7/CC10 host proxies; the new chase only approximates the official per-voice target rewrite and 128-sample render smoothing, and it still cannot preserve descriptor-weighted voice separation exactly.");
        knownLimitations.add("Infinite ordinary-loop playback still relies on the host sequencer's transport loop; the official parser-cursor rewind with unbounded live-state carry-over is therefore only matched for exported / bounded materialized passes, not for endless host playback.");
        return new NarrativeSections(implementationFacts, runtimePolicies, knownLimitations);
    }

    private static final class NarrativeSections {
        final List<String> implementationFacts;
        final List<String> runtimePolicies;
        final List<String> knownLimitations;

        NarrativeSections(
                List<String> implementationFacts,
                List<String> runtimePolicies,
                List<String> knownLimitations) {
            this.implementationFacts = implementationFacts;
            this.runtimePolicies = runtimePolicies;
            this.knownLimitations = knownLimitations;
        }
    }

    private static final Comparator<TrackEvent> TRACK_EVENT_COMPARATOR = new Comparator<TrackEvent>() {
        @Override
        public int compare(TrackEvent left, TrackEvent right) {
            int byTick = Integer.compare(left.rawTick, right.rawTick);
            if (byTick != 0) {
                return byTick;
            }
            int byTrack = Integer.compare(left.trackIndex, right.trackIndex);
            if (byTrack != 0) {
                return byTrack;
            }
            return Integer.compare(left.eventIndex, right.eventIndex);
        }
    };

    private static final Comparator<RawTempoPoint> RAW_TEMPO_COMPARATOR = new Comparator<RawTempoPoint>() {
        @Override
        public int compare(RawTempoPoint left, RawTempoPoint right) {
            int byTick = Integer.compare(left.rawTick, right.rawTick);
            if (byTick != 0) {
                return byTick;
            }
            int byTrack = Integer.compare(left.trackIndex, right.trackIndex);
            if (byTrack != 0) {
                return byTrack;
            }
            return Integer.compare(left.eventIndex, right.eventIndex);
        }
    };

    private static final Comparator<PlaybackTimeline.MappedControlEvent> MAPPED_CONTROL_COMPARATOR =
            new Comparator<PlaybackTimeline.MappedControlEvent>() {
                @Override
                public int compare(
                        PlaybackTimeline.MappedControlEvent left,
                        PlaybackTimeline.MappedControlEvent right) {
                    int byTick = Long.compare(left.midiTick, right.midiTick);
                    if (byTick != 0) {
                        return byTick;
                    }
                    int byChannel = Integer.compare(left.midiChannel, right.midiChannel);
                    if (byChannel != 0) {
                        return byChannel;
                    }
                    int byOrder = Integer.compare(left.order, right.order);
                    if (byOrder != 0) {
                        return byOrder;
                    }
                    return Integer.compare(left.data1, right.data1);
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

    private static final class LegacySelectorSummary {
        final int selectorHeaderLength;
        final int selectorId;
        final int selectorFlags;
        final int trailingPayloadLength;
        final int adpmByte0;
        final int adpmByte1;
        final int adpmByte2Low3;
        final int adpmByte2Bit3;

        LegacySelectorSummary(
                int selectorHeaderLength,
                int selectorId,
                int selectorFlags,
                int trailingPayloadLength,
                int adpmByte0,
                int adpmByte1,
                int adpmByte2Low3,
                int adpmByte2Bit3) {
            this.selectorHeaderLength = selectorHeaderLength;
            this.selectorId = selectorId;
            this.selectorFlags = selectorFlags;
            this.trailingPayloadLength = trailingPayloadLength;
            this.adpmByte0 = adpmByte0;
            this.adpmByte1 = adpmByte1;
            this.adpmByte2Low3 = adpmByte2Low3;
            this.adpmByte2Bit3 = adpmByte2Bit3;
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
        HostPatch lastPatch;

        boolean allowsOrdinaryNotes() {
            return mode == 0 || mode == 1;
        }
    }

    private static final class HostPatch {
        final boolean suppressed;
        final int program;
        final int patchWord;
        final int rawPatchWord;
        final int latePatchEntry;
        final String source;
        final int nativeMode;
        final int nativeBank;
        final int nativeProgram;
        final int nativeKind;
        final int nativeSub;
        final int nativeValue;

        HostPatch(
                boolean suppressed,
                int program,
                int patchWord,
                int rawPatchWord,
                int latePatchEntry,
                String source,
                int nativeMode,
                int nativeBank,
                int nativeProgram,
                int nativeKind,
                int nativeSub,
                int nativeValue) {
            this.suppressed = suppressed;
            this.program = clamp(0, 127, program);
            this.patchWord = patchWord & 0xFFFF;
            this.rawPatchWord = rawPatchWord & 0xFFFF;
            this.latePatchEntry = latePatchEntry & 0xFFFF;
            this.source = source;
            this.nativeMode = nativeMode;
            this.nativeBank = nativeBank;
            this.nativeProgram = nativeProgram;
            this.nativeKind = nativeKind;
            this.nativeSub = nativeSub;
            this.nativeValue = nativeValue;
        }

        boolean sameAs(HostPatch other) {
            return other != null
                    && suppressed == other.suppressed
                    && program == other.program
                    && patchWord == other.patchWord
                    && nativeMode == other.nativeMode
                    && nativeBank == other.nativeBank
                    && nativeProgram == other.nativeProgram
                    && nativeKind == other.nativeKind
                    && nativeSub == other.nativeSub
                    && nativeValue == other.nativeValue;
        }
    }

    private static final class NativePatchState {
        final int mode;
        final int bank;
        final int program;
        final int kind;
        final int sub;
        final int value;

        NativePatchState(int mode, int bank, int program, int kind, int sub, int value) {
            this.mode = mode & 0xFF;
            this.bank = bank & 0xFF;
            this.program = program & 0xFF;
            this.kind = kind & 0xFF;
            this.sub = sub & 0xFF;
            this.value = value & 0xFF;
        }
    }

    private static final class PatchSelection {
        final int lateEntry;
        final int patchWord;
        final String source;
        final boolean authoritative;

        PatchSelection(int lateEntry, int patchWord, String source, boolean authoritative) {
            this.lateEntry = lateEntry & 0xFFFF;
            this.patchWord = patchWord & 0xFFFF;
            this.source = source;
            this.authoritative = authoritative;
        }

        static PatchSelection fromLateEntry(int lateEntry, String source, boolean authoritative) {
            return new PatchSelection(lateEntry, patchWordFromLateEntry(lateEntry), source, authoritative);
        }

        PatchSelection withPatchWord(int patchWord) {
            return new PatchSelection(lateEntry, patchWord, source, authoritative);
        }
    }

    private static final class ActiveNote {
        final int sourceTrack;
        final int sourceVoice;
        final int midiChannel;
        final int midiTrackIndex;
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
                int midiTrackIndex,
                int midiNote,
                int velocity,
                int rawStartTick,
                int rawEndTick,
                long midiStartTick,
                long midiEndTick) {
            this.sourceTrack = sourceTrack;
            this.sourceVoice = sourceVoice;
            this.midiChannel = midiChannel;
            this.midiTrackIndex = midiTrackIndex;
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
                    midiTrackIndex,
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
                    midiTrackIndex,
                    midiNote,
                    velocity,
                    rawStartTick,
                    rawEndTick,
                    midiStartTick,
                    midiEndTick);
        }
    }

    private static final class ControlCollector {
        private final List<PlaybackTimeline.MappedControlEvent> mappedControls;
        private final Map<Integer, Integer> lastControlValues = new LinkedHashMap<Integer, Integer>();
        private final Map<Integer, Integer> lastPitchBendValues = new LinkedHashMap<Integer, Integer>();
        private int nextOrder = 0;

        ControlCollector(List<PlaybackTimeline.MappedControlEvent> mappedControls) {
            this.mappedControls = mappedControls;
        }

        void emitPatch(int sourceTrack, int sourceCommand, String sourceName, int midiChannel, long midiTick, HostPatch patch) {
            emit(sourceTrack, sourceCommand, sourceName, midiChannel, midiTick, ShortMessage.PROGRAM_CHANGE, patch.program, 0,
                    patch.patchWord,
                    patch.rawPatchWord,
                    patch.latePatchEntry,
                    patch.source,
                    patch.nativeMode,
                    patch.nativeBank,
                    patch.nativeProgram,
                    patch.nativeKind,
                    patch.nativeSub,
                    patch.nativeValue);
        }

        void emitVolume(int sourceTrack, int sourceCommand, String sourceName, int midiChannel, long midiTick, int value) {
            emitDedupedControl(sourceTrack, sourceCommand, sourceName, midiChannel, midiTick, 7, clamp(0, 127, value));
        }

        void emitPan(int sourceTrack, int sourceCommand, String sourceName, int midiChannel, long midiTick, int value) {
            emitDedupedControl(sourceTrack, sourceCommand, sourceName, midiChannel, midiTick, 10, clamp(0, 127, value));
        }

        void emitPitchRange(int sourceTrack, int sourceCommand, String sourceName, int midiChannel, long midiTick, int range) {
            emit(sourceTrack, sourceCommand, sourceName, midiChannel, midiTick, ShortMessage.CONTROL_CHANGE, 101, 0);
            emit(sourceTrack, sourceCommand, sourceName, midiChannel, midiTick, ShortMessage.CONTROL_CHANGE, 100, 0);
            emit(sourceTrack, sourceCommand, sourceName, midiChannel, midiTick, ShortMessage.CONTROL_CHANGE, 6,
                    clamp(0, 127, range));
            emit(sourceTrack, sourceCommand, sourceName, midiChannel, midiTick, ShortMessage.CONTROL_CHANGE, 38, 0);
        }

        void emitPitchBend(int sourceTrack, int sourceCommand, String sourceName, int midiChannel, long midiTick, int bendValue) {
            int clamped = clamp(0, 16383, bendValue);
            Integer key = Integer.valueOf(midiChannel);
            if (sameValue(lastPitchBendValues.get(key), clamped)) {
                return;
            }
            lastPitchBendValues.put(key, Integer.valueOf(clamped));
            emit(sourceTrack, sourceCommand, sourceName, midiChannel, midiTick, ShortMessage.PITCH_BEND,
                    clamped & 0x7F, (clamped >> 7) & 0x7F);
        }

        void emitModulation(int sourceTrack, int sourceCommand, String sourceName, int midiChannel, long midiTick, int value) {
            emitDedupedControl(sourceTrack, sourceCommand, sourceName, midiChannel, midiTick, 1, clamp(0, 127, value));
        }

        void emitMasterVolume(int sourceTrack, int sourceCommand, String sourceName, long midiTick, int value) {
            for (int midiChannel = 0; midiChannel < MIDI_CHANNEL_COUNT; midiChannel++) {
                emitDedupedControl(sourceTrack, sourceCommand, sourceName, midiChannel, midiTick, 7,
                        clamp(0, 127, value));
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
            Integer key = Integer.valueOf((midiChannel << 8) | (controller & 0x7F));
            if (sameValue(lastControlValues.get(key), value)) {
                return;
            }
            lastControlValues.put(key, Integer.valueOf(value));
            emit(sourceTrack, sourceCommand, sourceName, midiChannel, midiTick, ShortMessage.CONTROL_CHANGE, controller, value);
        }

        private boolean sameValue(Integer previous, int current) {
            return previous != null && previous.intValue() == current;
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
            emit(
                    sourceTrack,
                    sourceCommand,
                    sourceName,
                    midiChannel,
                    midiTick,
                    status,
                    data1,
                    data2,
                    -1,
                    -1,
                    -1,
                    null,
                    -1,
                    -1,
                    -1,
                    -1,
                    -1,
                    -1);
        }

        private void emit(
                int sourceTrack,
                int sourceCommand,
                String sourceName,
                int midiChannel,
                long midiTick,
                int status,
                int data1,
                int data2,
                int patchWord,
                int rawPatchWord,
                int latePatchEntry,
                String patchSource,
                int nativeMode,
                int nativeBank,
                int nativeProgram,
                int nativeKind,
                int nativeSub,
                int nativeValue) {
            mappedControls.add(new PlaybackTimeline.MappedControlEvent(
                    sourceTrack,
                    sourceCommand,
                    sourceName,
                    midiChannel,
                    midiChannel,
                    midiChannel + 1,
                    midiTick,
                    status,
                    data1,
                    data2,
                    patchWord,
                    rawPatchWord,
                    latePatchEntry,
                    patchSource,
                    nativeMode,
                    nativeBank,
                    nativeProgram,
                    nativeKind,
                    nativeSub,
                    nativeValue,
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
        private final List<PlaybackTimeline.TempoPoint> tempoPoints;

        TempoMapper(List<PlaybackTimeline.TempoPoint> tempoPoints) {
            this.tempoPoints = tempoPoints;
        }

        public long rawToMidiTick(int rawTick) {
            PlaybackTimeline.TempoPoint current = tempoPoints.get(0);
            for (int i = 1; i < tempoPoints.size(); i++) {
                PlaybackTimeline.TempoPoint next = tempoPoints.get(i);
                if (next.rawTick > rawTick) {
                    break;
                }
                current = next;
            }
            return current.midiTick + (((long) rawTick - current.rawTick) * PlaybackTimeline.MIDI_PPQ) / current.timebase;
        }
    }
}
