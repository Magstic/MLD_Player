# MLD (MFi) Player
[![DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/Magstic/MLD_Player)

MLD Player, plays MLD(MFi) melody via MIDI stream. 

---

## Overview

MLD (MFi) is NTT Docomo's ringtone format.

MLD Player uses MIDI to play MLD melodies. Its final sound depends on the General MIDI sound set or SoundFont provided by the host environment.

MFi2MIDI conversion is inherently **lossy**. As a result, this project does not attempt to emulate the original sound chips used by mobile phones.

However, the wide range of available SF2 SoundFonts can still provide many different ways to render and enjoy MFi music.


## MFi Support

| MFi | Support |
|---|---|
| MFi 1 | `█████████▌` ~95% |
| MFi 2 | `█████████░` ~90% |
| MFi 3 | `███████▌░░` ~75% |
| MFi 4 | `██████▌░░░` ~65% |
| MFi 5 | `█████▌░░░░` ~55% |


## Play

Java 8, and Double-click the JAR!


### CLI

```
java -jar mld-player.jar <file.mld>
java -jar mld-player.jar <file.mld> --output <dir>       AUTO export to <dir>/MFiExport
java -jar mld-player.jar <file.mld> --loop [n|infinite]  override loop count
```


### SoundFonts

This software supports customizing SF2 using:
- [VirtualMIDISynth](https://coolsoft.altervista.org/en/virtualmidisynth)（Win）
- [FluidSynth](https://www.fluidsynth.org/)（Linux / Mac）

---

## Build

[Apache Ant](https://ant.apache.org/)

```bash
ant
```


## Tree

[docs/tree.md](docs/tree.md)

---

## Known Gaps

- Sampled-audio rendering is currently limited to the verified `0x8001` 4-bit mono `71:84` profile.
- `0x8000`, `0x8001` 2-bit, `0x8002`, `71:86`, and resource-triggered sampled audio remain unsupported.
- Proprietary MFi synthesizer backend requests are preserved as semantic evidence but are not rendered by the host MIDI bridge.

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
