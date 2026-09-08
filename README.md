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
| MFi 1 | `██████████` ~99% |
| MFi 2 | `█████████▊` ~98% |
| MFi 3 | `█████████▎` ~93% |
| MFi 4 | `████████▌░` ~85% |
| MFi 5 | `███████▌░░` ~75% |


## Play

Java 8, and Double-click the JAR!


### CLI

```
java -jar mld-player.jar <file.mld>
java -jar mld-player.jar <file.mld> --output <dir>       AUTO export to <dir>/MFiExport
java -jar mld-player.jar <file.mld> --loop [n|infinite]  repeat the completed track
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

- Support: `0x8001` G.726 at 2/4-bit, 8/16/32 kHz, mono/stereo active resources; machine `0x106/0x109` 2/4-bit mono; `0x8002` 4-bit AWC2 at 4/8/16/32 kHz, mono/stereo.
- Unsupported: `0x8000`, other `0x8002` sample rates, `0x8003`, `71:86`, and routes 2 through 9.
- Proprietary MFi synthesizer audio is handled separately and is not processed by the standard MIDI player.

---

## Thanks

**[L-Garden](https://magstic.art/2026/01/08/13/)**: Especially Mr. Scirocco, whose BGM compositions drove me to complete this project.

**[ChatGPT](https://chatgpt.com/)**: Reverse engineering. 5.4: 80% Melody /  5.6 Sol: 20% Melody, 90% Sampling / 6 Astra: 10% Sampling.

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
