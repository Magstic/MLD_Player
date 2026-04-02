# MLD (MFi) Player

## Info

MLD Player, plays MLD melody via MIDI stream.

---

## Item

Currently supports MLD melody playback only — sound effects are not supported.

If you want to add SFX support, see `src_test`, where the abandoned SFX branch lives.

| Item | Description |
|------|-------------|
| Parse | `melo` container — all top-level chunks, per-track event streams |
| Decode | Full ordinary-track state machine, resource commands, loop, meta-events |
| Normalize | MD event normalization — machine-dependent sub-family classification |
| Compile | 64 logical channels, voice-map routing, PSMPlayer patch projection, tempo mapping |
| Export | SMF Type 1 MIDI (intro / loop / full), bridge JSON for PSMPlayer handoff |
| Playback | Real-time via Java MIDI, Swing GUI + CLI |

---

## Play

Java 8

### CLI

```
java -jar mld-player.jar <file.mld>
java -jar mld-player.jar <file.mld> --output <dir>       export MIDI
java -jar mld-player.jar <file.mld> --loop [n|infinite]  override loop count
java -jar mld-player.jar                                 open Swing player
```

### GUI

Double-click the JAR, or launch without arguments. Drag-and-drop MLD files onto the window.

---

## Build

Apache Ant

```bash
ant
```

---

## Tree

```
src/
  container/   MldParser, MldFile       — melo container parsing
  event/       TrackDecoder, TrackEvent — per-track event decoding
  normalize/   MdNormalizer             — MD event normalization
  timeline/    TimelineCompiler         — compilation, channel routing, tempo
  playback/    JavaMidiPlayer           — real-time MIDI playback
  bridge/midi/ MidiBridgeExporter       — MIDI segment export + bridge JSON
  main/        Cli, SwingPlayer, PlayerLauncher — entry points
  util/        helpers
src_test/      archived experiments (SFX, ADPCM, audit)
docs/          spec reference
tools/         probe script
```

---

## Known Gaps

- Loop uses transport-level restart, not the official parser-cursor rewind with carry-over state. Works for most BGM but differs structurally.
- Some machine-dependent sub-families are parsed but not fully voiced.

---

## Thanks

**L-Garden**: Especially Mr. Scirocco, whose BGM compositions drove me to complete this project.

**ChatGPT**: Carried out 100% of the reverse engineering and 99% of the code cleanup.

**Keitai Archive**: Preserved a massive collection of Keitai games.

**PSMPlayer**: Provides a relatively accurate MLD → MIDI instrument mapping.

---

## About

A clean-room reimplementation based on reverse-engineering of the MFi SoundLib and PSMPlayer.

This project is AI-driven, so I cannot guarantee future updates.

Even so, it still has value... perhaps. If any part of this project inspires you, that is enough.

---

## License

MIT