# MLD Player

[JavaSE](https://github.com/Magstic/MLD_Player) | [J2ME](https://github.com/Magstic/MLD_Player/tree/j2me) | [Android](https://github.com/Magstic/MLD_Player/tree/android)

MLD Player's Android branch, plays MLD melody via MIDI stream.

## Play

Android 8-12

## Build

See [docs/build.md].

## Tree

See [docs/tree.md]

## Known Gaps

- Loop uses transport-level restart, not the official parser-cursor rewind with carry-over state. Works for most BGM but differs structurally.
- Some machine-dependent sub-families are parsed but not fully voiced.

## Thanks

**L-Garden**: Especially Mr. Scirocco, whose BGM compositions drove me to complete this project.

**ChatGPT**: Carried out 100% of the reverse engineering and 99% of the code cleanup.

**Keitai Archive**: Preserved a massive collection of Keitai games.

**PSMPlayer**: Provides a relatively accurate MLD → MIDI instrument mapping.

**FluidSynth**: Fully Android SF2 synthesizer.

## About

A clean-room reimplementation based on reverse-engineering of the MFi SoundLib and PSMPlayer.

This project is AI-driven, so I cannot guarantee future updates.

Even so, it still has value... perhaps. If any part of this project inspires you, that is enough.

## License

MLD Player: MIT

FluidSynth: LGPL 2.1