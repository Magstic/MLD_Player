package bridge.midi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;

import container.InfoChunk;
import container.MldFile;
import container.MldParser;
import container.TopLevelChunk;
import event.TrackDecodeResult;
import event.TrackDecoder;
import normalize.MdNormalizedEvent;
import normalize.MdNormalizationResult;
import normalize.MdNormalizer;
import playback.PlaybackSequenceBuilder;
import timeline.PlaybackTimeline;
import timeline.TimelineCompiler;
import util.JsonSink;

public final class MidiBridgeExporter {
    private final JsonSink jsonSink = new JsonSink();

    public BridgeArtifacts export(PlaybackTimeline timeline, Path inputPath, Path outputDir)
            throws IOException, InvalidMidiDataException {
        Files.createDirectories(outputDir);

        Path namedMidiPath = outputDir.resolve(midiFileName(inputPath));
        Path introPath = outputDir.resolve("intro.mid");
        Path loopPath = outputDir.resolve("loop.mid");
        Path fullMidiPath = outputDir.resolve("full.mid");
        Path bridgeJsonPath = outputDir.resolve("bridge.json");
        Path actualPrimaryMidiPath = null;
        Path actualLoopPath = null;
        Path actualFullMidiPath = null;

        if (hasExportableMidi(timeline)) {
            if (timeline.loopInfo.hasLoop) {
                PlaybackTimeline rebuiltTimeline = rebuildTimeline(inputPath);
                if (hasExportableMidi(rebuiltTimeline)) {
                    PlaybackSequenceBuilder.BuiltSequence fullSequence =
                            new PlaybackSequenceBuilder().build(rebuiltTimeline, 0);
                    MidiSystem.write(fullSequence.sequence, 1, fullMidiPath.toFile());
                    actualFullMidiPath = fullMidiPath;
                } else {
                    Files.deleteIfExists(fullMidiPath);
                }

                long introEnd = Math.max(1L, timeline.loopInfo.loopStartMidiTick);
                writeSegment(timeline, 0L, introEnd, false, "Intro", introPath);
                actualPrimaryMidiPath = introPath;

                long loopStart = timeline.loopInfo.loopStartMidiTick;
                long loopEnd = Math.max(loopStart + 1L, timeline.loopInfo.loopEndMidiTick);
                writeSegment(timeline, loopStart, loopEnd, true, "Loop", loopPath);
                actualLoopPath = loopPath;
                Files.deleteIfExists(namedMidiPath);
            } else {
                long wholeEnd = Math.max(1L, timeline.totalMidiTicks);
                writeSegment(timeline, 0L, wholeEnd, false, "Full", namedMidiPath);
                actualPrimaryMidiPath = namedMidiPath;
                Files.deleteIfExists(introPath);
                Files.deleteIfExists(loopPath);
                Files.deleteIfExists(fullMidiPath);
            }
        } else {
            Files.deleteIfExists(namedMidiPath);
            Files.deleteIfExists(introPath);
            Files.deleteIfExists(loopPath);
            Files.deleteIfExists(fullMidiPath);
        }

        Map<String, Object> bridgeJson = buildBridgeJson(
                timeline,
                inputPath,
                actualPrimaryMidiPath,
                actualLoopPath,
                actualFullMidiPath);
        jsonSink.write(bridgeJson, bridgeJsonPath);

        return new BridgeArtifacts(actualPrimaryMidiPath, actualLoopPath, actualFullMidiPath, bridgeJsonPath);
    }

    private boolean hasExportableMidi(PlaybackTimeline timeline) {
        return timeline != null && !timeline.notes.isEmpty();
    }

    private PlaybackTimeline rebuildTimeline(Path inputPath) throws IOException {
        MldParser parser = new MldParser();
        MldFile file = parser.parse(inputPath);

        TrackDecoder decoder = new TrackDecoder();
        List<TrackDecodeResult> decodedTracks = new ArrayList<TrackDecodeResult>();
        for (int i = 0; i < file.tracks.size(); i++) {
            decodedTracks.add(decoder.decode(file, file.tracks.get(i)));
        }

        MdNormalizer mdNormalizer = new MdNormalizer();
        MdNormalizationResult mdNormalization = mdNormalizer.normalize(decodedTracks);

        TimelineCompiler compiler = new TimelineCompiler();
        return compiler.compile(file, decodedTracks, mdNormalization);
    }

    private String midiFileName(Path inputPath) {
        String fileName = inputPath.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String stem = dot > 0 ? fileName.substring(0, dot) : fileName;
        return stem + ".mid";
    }

    private void writeSegment(
            PlaybackTimeline timeline,
            long segmentStart,
            long segmentEnd,
            boolean primeState,
            String segmentName,
            Path outputPath) throws IOException, InvalidMidiDataException {
        Sequence sequence = new Sequence(Sequence.PPQ, PlaybackTimeline.MIDI_PPQ);
        Track conductorTrack = sequence.createTrack();
        addTrackName(conductorTrack, segmentName + " Conductor");

        Track[] channelTracks = new Track[16];
        for (int midiChannel = 0; midiChannel < 16; midiChannel++) {
            channelTracks[midiChannel] = sequence.createTrack();
            addTrackName(channelTracks[midiChannel], channelName(midiChannel));
        }

        long segmentLength = Math.max(1L, segmentEnd - segmentStart);
        emitTempoTrack(timeline, conductorTrack, segmentStart, segmentEnd);

        if (primeState && segmentStart > 0) {
            emitPrimedControls(timeline, channelTracks, segmentStart);
        }

        emitChannelEvents(timeline, channelTracks, segmentStart, segmentEnd);

        addEndOfTrack(conductorTrack, segmentLength + 1L);
        for (Track track : channelTracks) {
            addEndOfTrack(track, segmentLength + 1L);
        }

        MidiSystem.write(sequence, 1, outputPath.toFile());
    }

    private void emitTempoTrack(
            PlaybackTimeline timeline,
            Track conductorTrack,
            long segmentStart,
            long segmentEnd) throws InvalidMidiDataException {
        PlaybackTimeline.TempoPoint active = timeline.tempoPoints.get(0);
        for (PlaybackTimeline.TempoPoint point : timeline.tempoPoints) {
            if (point.midiTick <= segmentStart) {
                active = point;
            } else {
                break;
            }
        }

        addTempoMeta(conductorTrack, active.mpqn, 0L);
        for (PlaybackTimeline.TempoPoint point : timeline.tempoPoints) {
            if (point.midiTick <= segmentStart || point.midiTick >= segmentEnd) {
                continue;
            }
            addTempoMeta(conductorTrack, point.mpqn, point.midiTick - segmentStart);
        }
    }

    private void emitPrimedControls(PlaybackTimeline timeline, Track[] channelTracks, long segmentStart)
            throws InvalidMidiDataException {
        Map<String, PlaybackTimeline.MappedControlEvent> latest = new LinkedHashMap<String, PlaybackTimeline.MappedControlEvent>();
        List<PlaybackTimeline.MappedControlEvent> controls = new ArrayList<PlaybackTimeline.MappedControlEvent>(timeline.mappedControls);
        Collections.sort(controls, MAPPED_CONTROL_COMPARATOR);

        for (PlaybackTimeline.MappedControlEvent control : controls) {
            if (control.midiTick > segmentStart) {
                break;
            }
            latest.put(controlKey(control), control);
        }

        List<PlaybackTimeline.MappedControlEvent> primed = new ArrayList<PlaybackTimeline.MappedControlEvent>(latest.values());
        Collections.sort(primed, PRIMED_CONTROL_COMPARATOR);
        for (PlaybackTimeline.MappedControlEvent control : primed) {
            addShortMessage(channelTracks[control.midiChannel], control.status, control.midiChannel, control.data1, control.data2, 0L);
        }
    }

    private void emitChannelEvents(
            PlaybackTimeline timeline,
            Track[] channelTracks,
            long segmentStart,
            long segmentEnd) throws InvalidMidiDataException {
        List<TrackMessageEvent> events = new ArrayList<TrackMessageEvent>();

        List<PlaybackTimeline.MappedControlEvent> mappedControls =
                new ArrayList<PlaybackTimeline.MappedControlEvent>(timeline.mappedControls);
        Collections.sort(mappedControls, MAPPED_CONTROL_COMPARATOR);
        for (PlaybackTimeline.MappedControlEvent control : mappedControls) {
            if (control.midiTick < segmentStart || control.midiTick >= segmentEnd) {
                continue;
            }
            events.add(TrackMessageEvent.control(
                    control.midiChannel,
                    control.midiTick - segmentStart,
                    control.status,
                    control.data1,
                    control.data2,
                    control.order));
        }

        List<PlaybackTimeline.CompiledNote> notes = new ArrayList<PlaybackTimeline.CompiledNote>(timeline.notes);
        Collections.sort(notes, NOTE_COMPARATOR);
        for (PlaybackTimeline.CompiledNote note : notes) {
            if (note.midiEndTick <= segmentStart || note.midiStartTick >= segmentEnd) {
                continue;
            }
            long localStart = Math.max(note.midiStartTick, segmentStart) - segmentStart;
            long localEnd = Math.min(note.midiEndTick, segmentEnd) - segmentStart;
            if (localEnd <= localStart) {
                localEnd = localStart + 1L;
            }
            int noteOrder = (note.sourceTrack * 16) + note.sourceVoice;
            events.add(TrackMessageEvent.noteOff(
                    note.midiChannel,
                    localEnd,
                    note.midiNote,
                    noteOrder));
            events.add(TrackMessageEvent.noteOn(
                    note.midiChannel,
                    localStart,
                    note.midiNote,
                    note.velocity,
                    noteOrder));
        }

        Collections.sort(events, TRACK_MESSAGE_COMPARATOR);
        for (TrackMessageEvent event : events) {
            addShortMessage(
                    channelTracks[event.midiChannel],
                    event.status,
                    event.midiChannel,
                    event.data1,
                    event.data2,
                    event.tick);
        }
    }

    private Map<String, Object> buildBridgeJson(
            PlaybackTimeline timeline,
            Path inputPath,
            Path actualPrimaryMidiPath,
            Path actualLoopPath,
            Path actualFullMidiPath) {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("tool", toolInfo());
        root.put("source", sourceInfo(timeline, inputPath));
        root.put("header", headerInfo(timeline));
        root.put("topLevelChunks", topLevelChunks(timeline));
        root.put("infoChunks", infoChunks(timeline));
        root.put("resourceSummary", resourceSummary(timeline));
        root.put("initialChannelConfigs", initialChannelConfigs(timeline));
        root.put("tracks", trackSummaries(timeline));
        root.put("resourceEvents", resourceEvents(timeline));
        root.put("tempoMap", tempoMap(timeline));
        root.put("loop", loopInfo(timeline));
        root.put("channelAssignment", channelAssignments(timeline));
        root.put("outputLanePlan", outputLanePlan(timeline));
        root.put("ordinaryNativeModel", ordinaryNativeModel(timeline));
        root.put("ordinaryNativeControls", ordinaryNativeControls(timeline));
        root.put("normalizedMachineDependent", normalizedMd(timeline.mdNormalization.normalizedEvents));
        root.put("unknownMachineDependent", normalizedMd(timeline.mdNormalization.unknownEvents));
        root.put("mappedPatchEvents", mappedPatchEvents(timeline));
        root.put("unmappedControls", unmappedControls(timeline));
        root.put("outputs", outputInfo(actualPrimaryMidiPath, actualLoopPath, actualFullMidiPath));
        root.put("implementationFacts", new ArrayList<String>(timeline.implementationFacts));
        root.put("runtimePolicies", new ArrayList<String>(timeline.runtimePolicies));
        root.put("knownLimitations", new ArrayList<String>(timeline.knownLimitations));
        root.put("assumptions", new ArrayList<String>(timeline.assumptions));
        root.put("warnings", mergedWarnings(timeline));
        return root;
    }

    private Map<String, Object> toolInfo() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("name", "java-cleanroom-mld-bridge");
        map.put("version", "0.3.0");
        return map;
    }

    private Map<String, Object> sourceInfo(PlaybackTimeline timeline, Path inputPath) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("path", inputPath.toAbsolutePath().toString());
        map.put("size", Integer.valueOf(timeline.file.rawBytes.length));
        map.put("sha256", sha256Hex(timeline.file.rawBytes));
        return map;
    }

    private Map<String, Object> headerInfo(PlaybackTimeline timeline) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("magic", timeline.file.magic);
        map.put("sizeField", Long.valueOf(timeline.file.sizeField));
        map.put("expectedSizeField", Integer.valueOf(timeline.file.rawBytes.length - 8));
        map.put("headerLength", Integer.valueOf(timeline.file.headerLength));
        map.put("majorType", Integer.valueOf(timeline.file.majorType));
        map.put("minorType", Integer.valueOf(timeline.file.minorType));
        map.put("trackCount", Integer.valueOf(timeline.file.trackCount));
        map.put("noteExtraBytes", Integer.valueOf(timeline.file.noteExtraBytes));
        map.put("exstSize", Integer.valueOf(timeline.file.exstSize));
        return map;
    }

    private List<Object> infoChunks(PlaybackTimeline timeline) {
        List<Object> list = new ArrayList<Object>();
        for (InfoChunk chunk : timeline.file.infoChunks) {
            Map<String, Object> entry = new LinkedHashMap<String, Object>();
            entry.put("id", chunk.id);
            entry.put("offset", Integer.valueOf(chunk.offset));
            entry.put("length", Integer.valueOf(chunk.length));
            entry.put("payloadHex", hex(chunk.payload));
            if (chunk.decodedText != null) {
                entry.put("text", chunk.decodedText);
            }
            list.add(entry);
        }
        return list;
    }

    private List<Object> topLevelChunks(PlaybackTimeline timeline) {
        List<Object> list = new ArrayList<Object>();
        for (TopLevelChunk chunk : timeline.file.topLevelChunks) {
            Map<String, Object> entry = new LinkedHashMap<String, Object>();
            entry.put("id", chunk.id);
            entry.put("category", chunk.category);
            entry.put("offset", Integer.valueOf(chunk.offset));
            entry.put("length", Integer.valueOf(chunk.length));
            entry.put("lengthFieldBytes", Integer.valueOf(chunk.lengthFieldBytes));
            entry.put("payloadPreviewHex", hexPreview(chunk.payload, 32));
            if (chunk.decodedText != null) {
                entry.put("text", chunk.decodedText);
            }
            list.add(entry);
        }
        return list;
    }

    private List<Object> resourceSummary(PlaybackTimeline timeline) {
        List<Object> list = new ArrayList<Object>();
        for (PlaybackTimeline.ResourceCatalogEntry catalog : timeline.resourceCatalog) {
            Map<String, Object> entry = new LinkedHashMap<String, Object>();
            entry.put("catalogIndex", Integer.valueOf(catalog.catalogIndex));
            entry.put("id", catalog.chunkId);
            entry.put("offset", Integer.valueOf(catalog.offset));
            entry.put("length", Integer.valueOf(catalog.length));
            entry.put("lengthFieldBytes", Integer.valueOf(catalog.lengthFieldBytes));
            if (catalog.adatIndex >= 0) {
                entry.put("adatIndex", Integer.valueOf(catalog.adatIndex));
            }
            if (catalog.activeAdatIndex >= 0) {
                entry.put("activeAdatIndex", Integer.valueOf(catalog.activeAdatIndex));
            }

            TopLevelChunk chunk = findTopLevelChunk(timeline.file.topLevelChunks, catalog.chunkId, catalog.offset);
            if (chunk != null) {
                entry.put("payloadPreviewHex", hexPreview(chunk.payload, 48));
            }

            if ("ainf".equals(catalog.chunkId)) {
                entry.put("kind", "resource_index");
                if (chunk != null) {
                    entry.put("payloadHex", hex(chunk.payload));
                    if (chunk.payload.length > 0) {
                        entry.put("declaredAdatCount", Integer.valueOf(chunk.payload[0] & 0x3F));
                        entry.put("newerParserAccepted", Boolean.valueOf((chunk.payload[0] & 0x40) == 0));
                    }
                }
            } else if ("thrd".equals(catalog.chunkId)) {
                entry.put("kind", "initial_channel_config");
                if (chunk != null) {
                    entry.put("payloadHex", hex(chunk.payload));
                }
            } else if ("adat".equals(catalog.chunkId) || "adpm".equals(catalog.chunkId)) {
                entry.put("kind", "resource_payload");
                entry.put("legacySelector", legacySelectorSummary(catalog));
            }

            list.add(entry);
        }
        return list;
    }

    private List<Object> trackSummaries(PlaybackTimeline timeline) {
        List<Object> list = new ArrayList<Object>();
        for (TrackDecodeResult track : timeline.decodedTracks) {
            Map<String, Object> entry = new LinkedHashMap<String, Object>();
            entry.put("index", Integer.valueOf(track.trackIndex));
            entry.put("rawLength", Integer.valueOf(track.rawLength));
            entry.put("totalRawTicks", Integer.valueOf(track.totalRawTicks));
            entry.put("noteCount", Integer.valueOf(track.noteCount));
            entry.put("resourceCount", Integer.valueOf(track.resourceCount));
            entry.put("systemCount", Integer.valueOf(track.systemCount));
            entry.put("machineCount", Integer.valueOf(track.machineCount));
            entry.put("warnings", new ArrayList<String>(track.warnings));
            list.add(entry);
        }
        return list;
    }

    private List<Object> initialChannelConfigs(PlaybackTimeline timeline) {
        List<Object> list = new ArrayList<Object>();
        for (PlaybackTimeline.InitialChannelConfig config : timeline.initialChannelConfigs) {
            Map<String, Object> entry = new LinkedHashMap<String, Object>();
            entry.put("chunkOffset", Integer.valueOf(config.chunkOffset));
            entry.put("globalValue", Integer.valueOf(config.globalValue));
            entry.put("logicalChannel", Integer.valueOf(config.logicalChannel));
            entry.put("target", config.target);
            entry.put("rawSubvalue", Integer.valueOf(config.rawSubvalue));
            entry.put("cachedValue", Integer.valueOf(config.cachedValue));
            entry.put("backendValue", Integer.valueOf(config.backendValue));
            list.add(entry);
        }
        return list;
    }

    private List<Object> resourceEvents(PlaybackTimeline timeline) {
        List<Object> list = new ArrayList<Object>();
        for (PlaybackTimeline.ResourceEventState resourceEvent : timeline.resourceEvents) {
            Map<String, Object> entry = new LinkedHashMap<String, Object>();
            entry.put("trackIndex", Integer.valueOf(resourceEvent.sourceTrack));
            entry.put("rawTick", Integer.valueOf(resourceEvent.rawTick));
            entry.put("midiTick", Long.valueOf(resourceEvent.midiTick));
            entry.put("command", String.format("0x%02X", resourceEvent.command));
            entry.put("name", resourceEvent.name);
            if (resourceEvent.lane >= 0) {
                entry.put("lane", Integer.valueOf(resourceEvent.lane));
            }
            if (resourceEvent.logicalChannel >= 0) {
                entry.put("logicalChannel", Integer.valueOf(resourceEvent.logicalChannel));
            }
            entry.put("target", resourceEvent.target);
            if (resourceEvent.resourceIndex >= 0) {
                entry.put("resourceIndex", Integer.valueOf(resourceEvent.resourceIndex));
            }
            if (resourceEvent.linkedCatalogIndex >= 0) {
                entry.put("linkedCatalogIndex", Integer.valueOf(resourceEvent.linkedCatalogIndex));
            }
            if (resourceEvent.linkedChunkOffset >= 0) {
                entry.put("linkedChunkOffset", Integer.valueOf(resourceEvent.linkedChunkOffset));
            }
            if (resourceEvent.extraParamLow6 >= 0) {
                entry.put("extraParamLow6", Integer.valueOf(resourceEvent.extraParamLow6));
            }
            if (resourceEvent.extraParam2x >= 0) {
                entry.put("extraParam2x", Integer.valueOf(resourceEvent.extraParam2x));
            }
            if (resourceEvent.valueLow6 >= 0) {
                entry.put("valueLow6", Integer.valueOf(resourceEvent.valueLow6));
            }
            if (resourceEvent.value2x >= 0) {
                entry.put("value2x", Integer.valueOf(resourceEvent.value2x));
            }
            if (resourceEvent.rawSubvalue >= 0) {
                entry.put("rawSubvalue", Integer.valueOf(resourceEvent.rawSubvalue));
            }
            if (resourceEvent.command == 0x90) {
                entry.put("clearsChannelConfig", Boolean.valueOf(resourceEvent.clearsChannelConfig));
                if (resourceEvent.cachedConfigValue >= 0) {
                    entry.put("cachedConfigValue", Integer.valueOf(resourceEvent.cachedConfigValue));
                }
                if (resourceEvent.backendConfigValue >= 0) {
                    entry.put("backendConfigValue", Integer.valueOf(resourceEvent.backendConfigValue));
                }
            }
            list.add(entry);
        }
        return list;
    }

    private List<Object> tempoMap(PlaybackTimeline timeline) {
        List<Object> list = new ArrayList<Object>();
        for (PlaybackTimeline.TempoPoint point : timeline.tempoPoints) {
            Map<String, Object> entry = new LinkedHashMap<String, Object>();
            entry.put("rawTick", Integer.valueOf(point.rawTick));
            entry.put("midiTick", Long.valueOf(point.midiTick));
            entry.put("timebase", Integer.valueOf(point.timebase));
            entry.put("tempo", Integer.valueOf(point.tempo));
            entry.put("mpqn", Integer.valueOf(point.mpqn));
            entry.put("synthetic", Boolean.valueOf(point.synthetic));
            list.add(entry);
        }
        return list;
    }

    private Map<String, Object> loopInfo(PlaybackTimeline timeline) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("hasLoop", Boolean.valueOf(timeline.loopInfo.hasLoop));
        map.put("loopSlot", Integer.valueOf(timeline.loopInfo.loopSlot));
        map.put("repeatCount", Integer.valueOf(timeline.loopInfo.repeatCount));
        map.put("loopStartRawTick", Integer.valueOf(timeline.loopInfo.loopStartRawTick));
        map.put("loopEndRawTick", Integer.valueOf(timeline.loopInfo.loopEndRawTick));
        map.put("loopStartMidiTick", Long.valueOf(timeline.loopInfo.loopStartMidiTick));
        map.put("loopEndMidiTick", Long.valueOf(timeline.loopInfo.loopEndMidiTick));
        map.put("warnings", new ArrayList<String>(timeline.loopInfo.warnings));
        return map;
    }

    private List<Object> channelAssignments(PlaybackTimeline timeline) {
        List<Object> list = new ArrayList<Object>();
        for (PlaybackTimeline.ChannelAssignment assignment : timeline.channelAssignments) {
            Map<String, Object> entry = new LinkedHashMap<String, Object>();
            entry.put("trackIndex", Integer.valueOf(assignment.trackIndex));
            entry.put("voice", Integer.valueOf(assignment.voice));
            entry.put("logicalChannel", Integer.valueOf(assignment.logicalChannel));
            entry.put("midiChannel", Integer.valueOf(assignment.midiChannel));
            entry.put("midiTrackIndex", Integer.valueOf(assignment.midiTrackIndex));
            entry.put("outputRemapped", Boolean.valueOf(assignment.outputRemapped));
            list.add(entry);
        }
        return list;
    }

    private List<Object> outputLanePlan(PlaybackTimeline timeline) {
        List<Object> list = new ArrayList<Object>();
        for (PlaybackTimeline.OutputLaneAudit lane : timeline.outputLanePlan) {
            Map<String, Object> entry = new LinkedHashMap<String, Object>();
            entry.put("logicalChannel", Integer.valueOf(lane.logicalChannel));
            entry.put("active", Boolean.valueOf(lane.active));
            entry.put("authoritativeMaskKnown", Boolean.valueOf(lane.authoritativeMaskKnown));
            entry.put("authoritativeSpecialLane", Boolean.valueOf(lane.authoritativeSpecialLane));
            entry.put("laneClass", lane.authoritativeSpecialLane ? "special" : "ordinary");
            entry.put("midiChannel", Integer.valueOf(lane.midiChannel));
            entry.put("outputRemapped", Boolean.valueOf(lane.outputRemapped));
            list.add(entry);
        }
        return list;
    }

    private Map<String, Object> ordinaryNativeModel(PlaybackTimeline timeline) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        PlaybackTimeline.OrdinaryNativeModel model = timeline.ordinaryNativeModel;
        map.put("dualHalfVoiceState", Boolean.valueOf(model.dualHalfVoiceState));
        map.put("replacementOverlapSamples", Integer.valueOf(model.replacementOverlapSamples));
        map.put("selectedHalfRule", model.selectedHalfRule);
        map.put("normalReleaseDistinctFromForcedStop", Boolean.valueOf(model.normalReleaseDistinctFromForcedStop));
        map.put("liveLevelPanRefresh", Boolean.valueOf(model.liveLevelPanRefresh));
        map.put("livePitchRefresh", Boolean.valueOf(model.livePitchRefresh));
        map.put("liveLookupRefresh", Boolean.valueOf(model.liveLookupRefresh));
        return map;
    }

    private List<Object> ordinaryNativeControls(PlaybackTimeline timeline) {
        List<Object> list = new ArrayList<Object>();
        for (PlaybackTimeline.OrdinaryNativeControl control : timeline.ordinaryNativeControls) {
            Map<String, Object> entry = new LinkedHashMap<String, Object>();
            entry.put("trackIndex", Integer.valueOf(control.sourceTrack));
            entry.put("command", String.format("0x%02X", control.sourceCommand & 0xFF));
            entry.put("name", control.sourceName);
            entry.put("rawTick", Integer.valueOf(control.rawTick));
            entry.put("midiTick", Long.valueOf(control.midiTick));
            entry.put("logicalChannel", Integer.valueOf(control.logicalChannel));
            entry.put("nativePath", control.nativePath);
            entry.put("hostMapping", control.hostMapping);
            entry.put("hostEventEmitted", Boolean.valueOf(control.hostEventEmitted));
            entry.put("hostMappingProxy", Boolean.valueOf(control.hostMappingProxy));
            entry.put("selectedHalfAware", Boolean.valueOf(control.selectedHalfAware));
            entry.put("writesReplacementHalfWhenPendingSwap",
                    Boolean.valueOf(control.writesReplacementHalfWhenPendingSwap));
            entry.put("continuityWindowSamples", Integer.valueOf(control.continuityWindowSamples));
            list.add(entry);
        }
        return list;
    }

    private List<Object> normalizedMd(List<MdNormalizedEvent> events) {
        List<Object> list = new ArrayList<Object>();
        for (MdNormalizedEvent event : events) {
            Map<String, Object> entry = new LinkedHashMap<String, Object>();
            entry.put("trackIndex", Integer.valueOf(event.trackIndex));
            entry.put("eventIndex", Integer.valueOf(event.eventIndex));
            entry.put("rawTick", Integer.valueOf(event.rawTick));
            entry.put("prefix", event.prefix >= 0 ? String.format("0x%02X", event.prefix) : null);
            entry.put("selector", event.selector >= 0 ? String.format("0x%02X", event.selector) : null);
            entry.put("family", event.family);
            entry.put("confidence", event.confidence);
            entry.put("rawHex", event.rawHex);
            entry.put("details", new LinkedHashMap<String, Object>(event.details));
            list.add(entry);
        }
        return list;
    }

    private List<Object> unmappedControls(PlaybackTimeline timeline) {
        List<Object> list = new ArrayList<Object>();
        for (PlaybackTimeline.UnmappedControlEvent event : timeline.unmappedControls) {
            Map<String, Object> entry = new LinkedHashMap<String, Object>();
            entry.put("trackIndex", Integer.valueOf(event.sourceTrack));
            entry.put("command", String.format("0x%02X", event.sourceCommand));
            entry.put("name", event.sourceName);
            entry.put("rawTick", Integer.valueOf(event.rawTick));
            entry.put("midiTick", Long.valueOf(event.midiTick));
            entry.put("value", Integer.valueOf(event.value));
            if (event.part >= 0) {
                entry.put("part", Integer.valueOf(event.part));
            }
            list.add(entry);
        }
        return list;
    }

    private List<Object> mappedPatchEvents(PlaybackTimeline timeline) {
        List<Object> list = new ArrayList<Object>();
        Map<Integer, PlaybackTimeline.OutputLaneAudit> lanePlanByLogicalChannel = lanePlanByLogicalChannel(timeline);
        for (PlaybackTimeline.MappedControlEvent event : timeline.mappedControls) {
            if (event.status != ShortMessage.PROGRAM_CHANGE) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<String, Object>();
            entry.put("sourceTrack", Integer.valueOf(event.sourceTrack));
            entry.put("sourceCommand", String.format("0x%02X", event.sourceCommand & 0xFF));
            entry.put("sourceName", event.sourceName);
            entry.put("logicalChannel", Integer.valueOf(event.logicalChannel));
            entry.put("midiChannel", Integer.valueOf(event.midiChannel));
            entry.put("midiTrackIndex", Integer.valueOf(event.midiTrackIndex));
            entry.put("midiTick", Long.valueOf(event.midiTick));
            entry.put("program", Integer.valueOf(event.data1));
            PlaybackTimeline.OutputLaneAudit laneAudit = lanePlanByLogicalChannel.get(Integer.valueOf(event.logicalChannel));
            if (laneAudit != null) {
                entry.put("authoritativeMaskKnown", Boolean.valueOf(laneAudit.authoritativeMaskKnown));
                entry.put("authoritativeSpecialLane", Boolean.valueOf(laneAudit.authoritativeSpecialLane));
                entry.put("laneClass", laneAudit.authoritativeSpecialLane ? "special" : "ordinary");
                entry.put("outputRemapped", Boolean.valueOf(event.midiChannel != event.logicalChannel));
            }
            if (event.patchWord >= 0) {
                entry.put("patchWord", String.format("0x%04X", event.patchWord & 0xFFFF));
            }
            if (event.rawPatchWord >= 0) {
                entry.put("rawPatchWord", String.format("0x%04X", event.rawPatchWord & 0xFFFF));
            }
            if (event.latePatchEntry >= 0) {
                entry.put("latePatchEntry", String.format("0x%04X", event.latePatchEntry & 0xFFFF));
            }
            if (event.patchSource != null) {
                entry.put("patchSource", event.patchSource);
            }
            if (event.nativeMode >= 0) {
                entry.put("nativeMode", Integer.valueOf(event.nativeMode));
            }
            if (event.nativeBank >= 0) {
                entry.put("nativeBank", Integer.valueOf(event.nativeBank));
            }
            if (event.nativeProgram >= 0) {
                entry.put("nativeProgram", Integer.valueOf(event.nativeProgram));
            }
            if (event.nativeKind >= 0) {
                entry.put("nativeKind", Integer.valueOf(event.nativeKind));
            }
            if (event.nativeSub >= 0) {
                entry.put("nativeSub", Integer.valueOf(event.nativeSub));
            }
            if (event.nativeValue >= 0) {
                entry.put("nativeValue", Integer.valueOf(event.nativeValue));
            }
            entry.put("order", Integer.valueOf(event.order));
            list.add(entry);
        }
        return list;
    }

    private Map<Integer, PlaybackTimeline.OutputLaneAudit> lanePlanByLogicalChannel(PlaybackTimeline timeline) {
        Map<Integer, PlaybackTimeline.OutputLaneAudit> map = new LinkedHashMap<Integer, PlaybackTimeline.OutputLaneAudit>();
        for (PlaybackTimeline.OutputLaneAudit lane : timeline.outputLanePlan) {
            map.put(Integer.valueOf(lane.logicalChannel), lane);
        }
        return map;
    }

    private Map<String, Object> outputInfo(Path actualPrimaryMidiPath, Path actualLoopPath, Path actualFullMidiPath) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("midi", actualPrimaryMidiPath != null ? actualPrimaryMidiPath.getFileName().toString() : null);
        map.put("introMid", actualLoopPath != null ? "intro.mid" : null);
        map.put("loopMid", actualLoopPath != null ? "loop.mid" : null);
        map.put("fullMid", actualFullMidiPath != null ? actualFullMidiPath.getFileName().toString() : null);
        map.put("bridgeJson", "bridge.json");
        return map;
    }

    private List<Object> mergedWarnings(PlaybackTimeline timeline) {
        List<Object> list = new ArrayList<Object>();
        list.addAll(timeline.warnings);
        list.addAll(timeline.mdNormalization.warnings);
        return list;
    }

    private static void addTrackName(Track track, String name) throws InvalidMidiDataException {
        MetaMessage metaMessage = new MetaMessage();
        byte[] data = name.getBytes();
        metaMessage.setMessage(0x03, data, data.length);
        track.add(new MidiEvent(metaMessage, 0L));
    }

    private static void addTempoMeta(Track track, int mpqn, long tick) throws InvalidMidiDataException {
        byte[] data = new byte[] {
                (byte) ((mpqn >>> 16) & 0xFF),
                (byte) ((mpqn >>> 8) & 0xFF),
                (byte) (mpqn & 0xFF)
        };
        MetaMessage metaMessage = new MetaMessage();
        metaMessage.setMessage(0x51, data, data.length);
        track.add(new MidiEvent(metaMessage, tick));
    }

    private static void addEndOfTrack(Track track, long tick) throws InvalidMidiDataException {
        MetaMessage metaMessage = new MetaMessage();
        metaMessage.setMessage(0x2F, new byte[0], 0);
        track.add(new MidiEvent(metaMessage, tick));
    }

    private static void addShortMessage(Track track, int command, int channel, int data1, int data2, long tick)
            throws InvalidMidiDataException {
        ShortMessage shortMessage = new ShortMessage();
        shortMessage.setMessage(command, channel, data1, data2);
        track.add(new MidiEvent(shortMessage, tick));
    }

    private static String channelName(int midiChannel) {
        return "MLD MIDI Channel " + midiChannel;
    }

    private static String controlKey(PlaybackTimeline.MappedControlEvent control) {
        if (control.status == ShortMessage.CONTROL_CHANGE) {
            return control.midiChannel + ":cc:" + control.data1;
        }
        if (control.status == ShortMessage.PROGRAM_CHANGE) {
            return control.midiChannel + ":program";
        }
        if (control.status == ShortMessage.PITCH_BEND) {
            return control.midiChannel + ":pitch";
        }
        return control.midiChannel + ":" + control.status;
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return hex(digest.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String hex(byte[] data) {
        StringBuilder builder = new StringBuilder(data.length * 2);
        for (int i = 0; i < data.length; i++) {
            builder.append(String.format("%02x", data[i] & 0xFF));
        }
        return builder.toString();
    }

    private static String hexPreview(byte[] data, int maxBytes) {
        int actual = Math.min(data.length, maxBytes);
        byte[] preview = new byte[actual];
        System.arraycopy(data, 0, preview, 0, actual);
        return actual < data.length ? (hex(preview) + "...") : hex(preview);
    }

    private static Map<String, Object> legacySelectorSummary(PlaybackTimeline.ResourceCatalogEntry catalog) {
        if (catalog.selectorHeaderLength < 0) {
            return null;
        }

        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("selectorHeaderLength", Integer.valueOf(catalog.selectorHeaderLength));
        map.put("selectorId", String.format("0x%02X", catalog.selectorId));
        map.put("selectorFlags", String.format("0x%02X", catalog.selectorFlags));
        map.put("trailingPayloadLength", Integer.valueOf(catalog.trailingPayloadLength));
        if (catalog.adpmByte0 >= 0) {
            Map<String, Object> adpmDescriptor = new LinkedHashMap<String, Object>();
            adpmDescriptor.put("byte0", Integer.valueOf(catalog.adpmByte0));
            adpmDescriptor.put("byte1", Integer.valueOf(catalog.adpmByte1));
            adpmDescriptor.put("byte2Low3", Integer.valueOf(catalog.adpmByte2Low3));
            adpmDescriptor.put("byte2Bit3", Integer.valueOf(catalog.adpmByte2Bit3));
            map.put("adpmDescriptor", adpmDescriptor);
        }
        return map;
    }

    private static TopLevelChunk findTopLevelChunk(List<TopLevelChunk> chunks, String id, int offset) {
        for (TopLevelChunk chunk : chunks) {
            if (chunk.offset == offset && chunk.id.equals(id)) {
                return chunk;
            }
        }
        return null;
    }

    private static final Comparator<PlaybackTimeline.CompiledNote> NOTE_COMPARATOR =
            new Comparator<PlaybackTimeline.CompiledNote>() {
                @Override
                public int compare(PlaybackTimeline.CompiledNote left, PlaybackTimeline.CompiledNote right) {
                    int byTick = Long.compare(left.midiStartTick, right.midiStartTick);
                    if (byTick != 0) {
                        return byTick;
                    }
                    int byChannel = Integer.compare(left.midiChannel, right.midiChannel);
                    if (byChannel != 0) {
                        return byChannel;
                    }
                    return Integer.compare(left.midiNote, right.midiNote);
                }
            };

    private static final Comparator<PlaybackTimeline.MappedControlEvent> MAPPED_CONTROL_COMPARATOR =
            new Comparator<PlaybackTimeline.MappedControlEvent>() {
                @Override
                public int compare(PlaybackTimeline.MappedControlEvent left, PlaybackTimeline.MappedControlEvent right) {
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

    private static final Comparator<PlaybackTimeline.MappedControlEvent> PRIMED_CONTROL_COMPARATOR =
            new Comparator<PlaybackTimeline.MappedControlEvent>() {
                @Override
                public int compare(PlaybackTimeline.MappedControlEvent left, PlaybackTimeline.MappedControlEvent right) {
                    int byChannel = Integer.compare(left.midiChannel, right.midiChannel);
                    if (byChannel != 0) {
                        return byChannel;
                    }
                    int bySemantic = Integer.compare(orderForControl(left), orderForControl(right));
                    if (bySemantic != 0) {
                        return bySemantic;
                    }
                    return Integer.compare(left.order, right.order);
                }
            };

    private static final Comparator<TrackMessageEvent> TRACK_MESSAGE_COMPARATOR =
            new Comparator<TrackMessageEvent>() {
                @Override
                public int compare(TrackMessageEvent left, TrackMessageEvent right) {
                    int byChannel = Integer.compare(left.midiChannel, right.midiChannel);
                    if (byChannel != 0) {
                        return byChannel;
                    }
                    int byTick = Long.compare(left.tick, right.tick);
                    if (byTick != 0) {
                        return byTick;
                    }
                    int byPhase = Integer.compare(left.phase, right.phase);
                    if (byPhase != 0) {
                        return byPhase;
                    }
                    int byOrder = Integer.compare(left.order, right.order);
                    if (byOrder != 0) {
                        return byOrder;
                    }
                    int byData1 = Integer.compare(left.data1, right.data1);
                    if (byData1 != 0) {
                        return byData1;
                    }
                    return Integer.compare(left.data2, right.data2);
                }
            };

    private static final class TrackMessageEvent {
        private static final int PHASE_NOTE_OFF = 0;
        private static final int PHASE_CONTROL = 1;
        private static final int PHASE_NOTE_ON = 2;

        final int midiChannel;
        final long tick;
        final int phase;
        final int status;
        final int data1;
        final int data2;
        final int order;

        private TrackMessageEvent(int midiChannel, long tick, int phase, int status, int data1, int data2, int order) {
            this.midiChannel = midiChannel;
            this.tick = tick;
            this.phase = phase;
            this.status = status;
            this.data1 = data1;
            this.data2 = data2;
            this.order = order;
        }

        static TrackMessageEvent control(int midiChannel, long tick, int status, int data1, int data2, int order) {
            return new TrackMessageEvent(midiChannel, tick, PHASE_CONTROL, status, data1, data2, order);
        }

        static TrackMessageEvent noteOff(int midiChannel, long tick, int midiNote, int order) {
            return new TrackMessageEvent(midiChannel, tick, PHASE_NOTE_OFF, ShortMessage.NOTE_OFF, midiNote, 0, order);
        }

        static TrackMessageEvent noteOn(int midiChannel, long tick, int midiNote, int velocity, int order) {
            return new TrackMessageEvent(midiChannel, tick, PHASE_NOTE_ON, ShortMessage.NOTE_ON, midiNote, velocity, order);
        }
    }

    private static int orderForControl(PlaybackTimeline.MappedControlEvent control) {
        if (control.status == ShortMessage.CONTROL_CHANGE && control.data1 == 0) {
            return 0;
        }
        if (control.status == ShortMessage.CONTROL_CHANGE && control.data1 == 32) {
            return 1;
        }
        if (control.status == ShortMessage.PROGRAM_CHANGE) {
            return 2;
        }
        if (control.status == ShortMessage.CONTROL_CHANGE && control.data1 == 101) {
            return 3;
        }
        if (control.status == ShortMessage.CONTROL_CHANGE && control.data1 == 100) {
            return 4;
        }
        if (control.status == ShortMessage.CONTROL_CHANGE && control.data1 == 6) {
            return 5;
        }
        if (control.status == ShortMessage.CONTROL_CHANGE && control.data1 == 38) {
            return 6;
        }
        if (control.status == ShortMessage.PITCH_BEND) {
            return 7;
        }
        if (control.status == ShortMessage.CONTROL_CHANGE && control.data1 == 7) {
            return 8;
        }
        if (control.status == ShortMessage.CONTROL_CHANGE && control.data1 == 10) {
            return 9;
        }
        if (control.status == ShortMessage.CONTROL_CHANGE && control.data1 == 1) {
            return 10;
        }
        if (control.status == ShortMessage.CONTROL_CHANGE && control.data1 == 11) {
            return 11;
        }
        return 20;
    }

    public static final class BridgeArtifacts {
        public final Path primaryMidiPath;
        public final Path loopMidiPath;
        public final Path fullMidiPath;
        public final Path bridgeJsonPath;

        public BridgeArtifacts(Path primaryMidiPath, Path loopMidiPath, Path fullMidiPath, Path bridgeJsonPath) {
            this.primaryMidiPath = primaryMidiPath;
            this.loopMidiPath = loopMidiPath;
            this.fullMidiPath = fullMidiPath;
            this.bridgeJsonPath = bridgeJsonPath;
        }
    }
}
