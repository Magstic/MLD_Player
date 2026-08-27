## Tree

```
src/
  mld/format/   MldReader, MldDocument   — melo container parsing
  mld/decode/   TrackDecoder, MachineDescriptorMatcher — event decoding + shared MD descriptor matching
  mld/semantic/ NativeCompiler           — native MLD semantic compilation
                TimingModel              — native timing + DD loop state
                MelodyProgram            — note/system native state
                AudioProgram             — typed resource/machine audio semantics
                Diagnostic               — structured unsupported/unresolved evidence
  midi/         MidiProjector, MidiPlan  — host MIDI projection
                MidiLoopMaterializer      — bounded playback loop plan expansion
                MidiSequenceEncoder       — sole MidiPlan -> Sequence serializer
  audio/        MfiG726Decoder            — verified 4-bit G.721/G.726 core
                Mfi8001Decoder            — exact 0x8001 codec/output pipeline
                DecodedSampledResource    — immutable 32-kHz int32 stereo cache
                MfiAudioMixer             — official gain/pan fixed-point rules
                AudioRenderer, StereoPcm  — verified offline stereo PCM output
                AudioPlaybackSource       — decoded voice schedule for transport
                AudioPcmTimeline          — loop-aware PCM overlap-add source
  normalize/    MdNormalizer             — machine-dependent forensic normalization
  playback/     PlaybackSession          — shared MIDI/PCM transport lifecycle
                PlaybackContent          — immutable session participants + native timing
                TransportTimeline        — shared microsecond clock/loop policy
                PcmPlaybackParticipant   — PCM transport follower
                PcmOutputConnection      — Java Sound output + latency policy
                MidiOutputCatalog        — host MIDI output discovery
                MidiOutputConnection     — opened MIDI output/device lifecycle
                FluidSynthBackend        — FluidSynth process/protocol integration
                MasterVolume             — shared MIDI/PCM live master-volume state
  export/       ExportService            — AUTO/MIDI/WAV user export orchestration
                WavEncoder               — deterministic PCM16 stereo RIFF/WAVE
  main/         MldApplicationWorkflow         — shared load/playback composition
                ApplicationTrack               — immutable loaded application model
                Cli, PlayerLauncher             — thin entry / interaction layers
                SwingPlayerController/View      — GUI orchestration / widget presentation
                PlaylistState                   — GUI playlist state
src_test/       test-only regression / integration / architecture sources
docs/           canonical specs
tools/          probe script
```
