# MLD (MFi) Player

MLD Player, plays MLD(MFi) melody via MIDI stream.

---

## Overview

MLD (MFi) is NTT Docomo's ringtone format.

MLD Player uses MIDI to play MLD melodies. Its final sound depends on the General MIDI sound set or SoundFont provided by the host environment.

MFi2MIDI conversion is inherently **lossy**. As a result, this project does not attempt to emulate the original sound chips used by mobile phones.

However, the wide range of available SF2 SoundFonts can still provide many different ways to render and enjoy MFi music.

This software supports MFi1 and MFi2 Melody.

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

Double-click the JAR!

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
- MLD Player will never support sound effects, but it still maintains a branch for testing sound effects: `src_test`.

---

## Thanks

**[L-Garden](https://magstic.art/2026/01/08/13/)**: Especially Mr. Scirocco, whose BGM compositions drove me to complete this project.

**[ChatGPT 5.4](https://chatgpt.com/)**: Carried out 100% of the reverse engineering and 99% of the code cleanup.

**[Keitai Archive](https://keitaiarchive.org/)**: Preserved a massive collection of Keitai games.

**[PSMPlayer](https://www.vector.co.jp/soft/win95/net/se211027.html)**: Provides a relatively accurate MLD → MIDI instrument mapping.

---

## About

A clean-room reimplementation based on reverse-engineering of the MFi SoundLib and PSMPlayer.

This project is AI-driven, so I cannot guarantee future updates.

Even so, it still has value... perhaps. If any part of this project inspires you, that is enough.

---

## License

MIT