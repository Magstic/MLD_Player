# MLD to MIDI Mapping Spec

>The MLD (MFi) specification was obtained through static reverse engineering of the DoJa Soundlib(MFi Player) and completed by GPT 5.4.

>Since this project implements playback via MIDI mapping, the above specification may contain inconsistencies with the project’s code.

>**NOTE: AI-generated content may contain inaccuracies. I can only do my best to iteratively validate it by leveraging multiple AI systems, in order to minimize the likelihood of such errors.**

## Scope

This document defines the current host-MIDI bridge for `melo` ordinary-track
playback.

- It describes the emitted MIDI stream, not the full native synth runtime.
- The native ordinary tuple `(mode, bank, program) -> (kind, sub, value)` is
  preserved as audit metadata on patch events, but it is not itself the GM
  mapping rule.
- `0x7F` resource events and `0xFF F0..FF` machine-dependent blocks are parsed
  and preserved, but they do not emit sounding MIDI in the current bridge.
- Transport-loop policy is out of scope here. This document covers one timeline
  pass before any host playback-loop policy is applied.

## Time Mapping

- MIDI PPQ is fixed at `1920`.
- Raw events are processed in `(rawTick, trackIndex, eventIndex)` order.
- Tempo seeds come from ordinary timebase events:
  - `0xC0..0xC6`
  - `0xC8..0xCE`
- If no tempo seed exists, a synthetic tick-`0` seed of `120 BPM / timebase 48`
  is inserted.
- If the first observed tempo seed starts after raw tick `0`, a synthetic
  origin seed with that first seed's `(timebase, tempo)` is inserted at raw
  tick `0`.
- Raw-to-MIDI conversion is piecewise:
  - `midi_delta = raw_delta * 1920 / active_timebase`
- Conductor tempo meta events use:
  - `mpqn = 60000000 / tempo`
- Scheduled note expiries at raw tick `T` are flushed before any other event at
  raw tick `T`.

## Lane Model

- Local lane = `trackIndex * 4 + voice`.
- Initial logical channel = local lane.
- `0xE5` rewrites the logical channel for one local lane:
  - source lane = `(trackIndex * 4) + part`
  - destination logical channel = `value & 0x3F`
- Only logical channels `0..15` become sounding host MIDI.
- Logical channels outside `0..15` remain preserved in audit state but do not
  emit sounding MIDI.

## Channel State Defaults

Each logical channel starts with:

- `mode = 0`
- `bank = 0`
- `program = 0`
- `rawPatchWord = 0`
- `level = 63`
- `pan = 32`
- `pitchCoarse = 32`
- `pitchFine = 32`
- `pitchRange = 2`
- `modulation = 0`

The host bridge emits tick-`0` defaults on all `16` MIDI channels:

- `CC7 = 126`
- `CC10 = 64`
- `RPN 0 / 0`, `Data Entry MSB = 2`, `LSB = 0`
- `Pitch Bend = 8192`
- `CC1 = 0`

## Ordinary Note Mapping

Ordinary notes are emitted only when the current channel mode is `0` or `1`.

- `0xBA` updates one logical channel's mode:
  - logical channel = `(value >> 3) & 0x0F`
  - mode = `value & 0x07`
- Modes other than `0` and `1` suppress ordinary note start.

Pitch mapping:

- ordinary melodic base note:
  - mode `0` -> base `45`
  - mode `1` -> base `35`
- authoritative special logical lane -> base `35`
- representative percussion therefore uses a `-10` GM-note correction relative
  to the naive ordinary melodic base `45`
- octave shift table:
  - `0 -> 0`
  - `1 -> +12`
  - `2 -> -24`
  - `3 -> -12`
- emitted MIDI note:
  - `clamp(0, 127, base + pitch + octaveOffset)`

Velocity mapping:

- with at least one extra note byte present (`note > 0`):
  - decoded note velocity field = `(attr >> 2) & 0x3F`
  - emitted MIDI velocity = `clamp(1, 127, 2 * ((attr >> 2) & 0x3F))`
- without an extra note byte:
  - emitted MIDI velocity = `126`

Gate mapping:

- raw end tick = `rawStartTick + gate`
- MIDI end tick = `max(midiStartTick + 1, rawToMidi(rawEndTick))`

Live gate-refresh rule:

- active-note key = `(logicalChannel, midiNote)`
- when the same key is triggered again before expiry:
  - no second MIDI note-on is emitted
  - the existing note's end tick is refreshed to the new gate target

Patch ordering:

- before a note-on is emitted, any pending patch change for that logical
  channel is emitted first.

## Patch Mapping

Patch state is built from ordinary `0xE0` and `0xE1`.

- `0xE0` stores:
  - `program = value & 0x3F`
- `0xE1` stores:
  - `bank = value & 0x3F`

Authoritative ordinary host patch word:

- `patch12 = (program & 0x3F) | ((bank & 0x3F) << 6)`

Verified low-bank special table:

- when `(bank & 0x3E) == 0`, programs `0..5` map as:
  - `0 -> 0`
  - `1 -> 9`
  - `2 -> 16`
  - `3 -> 24`
  - `4 -> 13`
  - `5 -> 74`

Host MIDI patch emission:

- emitted MIDI message = `Program Change`
- emitted MIDI program = `patchWord & 0x7F`
- if no authoritative ordinary patch is available yet, the host falls back to
  patch `0`
- the first ordinary note on an untouched channel can therefore emit
  `Program Change 0` before the note-on
- entering mode `1` through `0xBA` marks the channel patch dirty and can emit
  the current patch immediately, subject to normal patch deduplication
- modes other than `0` and `1` suppress patch emission

Native audit tuple:

- every emitted patch event also carries:
  - native `mode`
  - native `bank`
  - native `program`
  - native `kind`
  - native `sub`
  - native `value`

The emitted MIDI patch path intentionally remains the current PSM-derived host
proxy. The native tuple is kept as an audit oracle, not substituted directly as
GM output.

## Ordinary Control Mapping

Tempo events:

- `0xC0..0xCE` emit conductor-track MIDI tempo meta only

State-only events:

- `0xDC` extends the next raw delta and emits no MIDI message
- `0xDD` updates loop metadata and emits no MIDI message
- `0xE5` rewrites the lane-to-logical-channel map and emits no MIDI message
- `0xBA` rewrites mode and emits no direct MIDI message

Mapped controls:

| MLD event | State update | Emitted MIDI |
|---|---|---|
| `0xB0` | no per-channel cache update | `CC7` on all `16` MIDI channels, value `clamp(0,127,value)` |
| `0xE2` | `level = value & 0x3F` | `CC7 = computePsmVolumeSync(level)`, currently `2 * level` |
| `0xE6` | `level += (value & 0x3F) - 32`, clamp `0..63` | `CC7 = computePsmVolumeSync(level)`, currently `2 * level` |
| `0xE3` | `pan = value & 0x3F` | `CC10 = 2 * pan` |
| `0xE4` | `pitchCoarse = value & 0x3F` | pitch bend |
| `0xE7` | `pitchRange = value & 0x3F` if `<= 24` | `RPN 0/0`, `Data Entry MSB = range`, `LSB = 0` |
| `0xE8` | `pitchFine = value & 0x3F` | pitch bend |
| `0xE9` | `pitchFine = value & 0x3F` | no MIDI message |
| `0xEA` | `modulation = value & 0x3F` | `CC1 = 2 * modulation` |

Pitch-bend formula:

- `bend = clamp(0, 16383, (8 * (pitchFine + (32 * pitchCoarse))) - 256)`

Pitch-range rule:

- `0xE7` values above `24` are ignored

CC7 interaction rule:

- `0xB0` and `0xE2/E6` all emit host `CC7`
- `0xB0` is an immediate all-channel broadcast and does not rewrite any
  per-channel `level` cache
- `0xE2/E6` rewrite one logical channel's `level` cache, then emit that
  channel's current synchronized host volume
- later `0xE2/E6` on one channel can therefore overwrite an earlier `0xB0`
  broadcast on that channel only
- deduplication is applied on final emitted `(midiChannel, controller, value)`
  only; identical consecutive `CC7` writes coalesce even if their MLD sources
  differ

Deduplication:

- repeated identical consecutive `CC7`, `CC10`, `CC1`, and pitch-bend values on
  the same MIDI channel are not re-emitted
- repeated identical consecutive host patch states are not re-emitted

## Live-Mix Chase Approximation

For ordinary `0xE2`, `0xE3`, and `0xE6`, the host bridge can replace one
immediate control step with a short chase when a note is already active on that
MIDI channel.

- eligible MIDI proxies:
  - `0xE2` -> `CC7`
  - `0xE6` -> `CC7`
  - `0xE3` -> `CC10`
- chase window = one native `128`-sample overlap window, converted to MIDI
  ticks at the current tempo
- chase length is truncated by the next same-stream control boundary
- up to `4` chased control steps are emitted
- the final chased value always lands on the original target value

## Output-Lane Remap

After note/control compilation, logical channels are remapped to final MIDI
channels.

Current bridge defaults:

- authoritative special-lane mask is seeded with logical channel `9`
- GM drum output channel is MIDI channel `10` (`index 9`)
- non-special active lanes are compressed left-to-right around that reserved
  drum lane

Final output mapping rule:

- authoritative special lane -> MIDI channel `10`
- remaining active ordinary lanes -> next sequential MIDI channels
- when the drum lane is reserved, ordinary lanes skip over MIDI channel `10`

If no authoritative output-lane mask is available, the bridge falls back to the
identity logical-channel -> MIDI-channel map.

## MIDI Stream Construction

The built sequence contains:

- `1` conductor track
- `16` channel tracks

Same-channel ordering at the same MIDI tick is:

1. note-off
2. control / program events
3. note-on

Within the same phase and tick:

- controls keep their collected source order
- note ordering is stable by `(sourceTrack, sourceVoice)`

## Non-Sounding Families

The current host-MIDI bridge does not emit sounding MIDI for:

- `0x7F` resource events
- `0xFF F0..FF` machine-dependent events
- cue / nop / end markers

These families remain part of the parsed timeline and export audit surface, but
they are outside the sounding ordinary-track MIDI bridge defined here.
