# MLD Player

MLD Player's J2ME branche, plays MLD melody via MIDI stream.

## Play

| Item | Recommended |  | Item | Recommended |
|------|------|------|------|------|
| Platform | *MIDP 2.0, CLDC 1.1* |  | CPU & RAM | *≥ 234 MHz*, *JVM ≥ 512 KB* |
| Screen | *240 x 320* |  |Optional | *JSR-75* *MMAPI*|

## Build

The build of this project does not require any IDE — the `build.xml` provided in the project root can complete 100% of the work.

[English](docs/build_en.md)

## Tree

```text
src/
  app/         PlaybackController       - MIDlet playback coordination
  container/   MldParser, MldFile       - melo container parsing
  event/       TrackDecoder, TrackEvent - per-track event decoding
  io/          FileConnectionLoader     - JSR-75 file loading
  main/        MainMidlet               - MIDlet entry point
  playback/    MidiBytesBuilder, J2meMidiPlayer - SMF building + MMAPI playback
  timeline/    TimelineCompiler         - melody compilation, channel routing, tempo
  util/        ByteArrayUtil, SortUtil  - J2ME-safe helpers
docs/          build guide + format specs
lib/           Antenna + ProGuard build dependencies
```

## Known Gaps

- Loop uses transport-level restart, not the official parser-cursor rewind with carry-over state. Works for most BGM but differs structurally.
- Some machine-dependent sub-families are parsed but not fully voiced.

## Thanks

**L-Garden**: Especially Mr. Scirocco, whose BGM compositions drove me to complete this project.

**ChatGPT**: Carried out 100% of the reverse engineering and 99% of the code cleanup.

**Keitai Archive**: Preserved a massive collection of Keitai games.

**PSMPlayer**: Provides a relatively accurate MLD → MIDI instrument mapping.

## About

A clean-room reimplementation based on reverse-engineering of the MFi SoundLib and PSMPlayer.

This project is AI-driven, so I cannot guarantee future updates.

Even so, it still has value... perhaps. If any part of this project inspires you, that is enough.

## License

MIT