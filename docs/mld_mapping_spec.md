# MLD to MIDI Mapping Spec

>The MLD (MFi) specification was obtained through static reverse engineering of the DoJa Soundlib(MFi Player) and completed by GPT 5.4.

>Since this project implements playback via MIDI mapping, the above specification may contain inconsistencies with the project’s code.

>**NOTE: AI-generated content may contain inaccuracies. I can only do my best to iteratively validate it by leveraging multiple AI systems, in order to minimize the likelihood of such errors.**

## Scope

This document defines MIDI mapping for `melo` ordinary-track playback.

- MIDI stream construction is the primary subject.
- Native `(mode, bank, program) -> (kind, sub, value)` data remains in decoded patch state.
- `0x7F` resource events and `0xFF` special envelopes remain in the decoded event model.
- Transport-loop policy applies after one compiled timeline pass.

## Time Mapping

- MIDI PPQ is fixed at `1920`.
- Raw events are processed in `(rawTick, trackIndex, eventIndex)` order.
- Tempo seeds come from ordinary timebase events:
  - `0xC0..0xC6`
  - `0xC8..0xCE`
- Tempo-state events also include:
  - `0xBC`: relative tempo adjustment by `unsigned(value) - 0x40`
  - `0xBF`: session reset with tempo restored to `125 BPM`; active timebase is preserved
- If no tempo seed exists, a synthetic tick-`0` seed of `125 BPM / timebase 48`
  is inserted.
- If the first tempo-state event starts after raw tick `0`, the native
  default `125 BPM / timebase 48` governs the interval from raw tick `0`.
- Raw-to-MIDI conversion is piecewise:
  - `midi_delta = raw_delta * 1920 / active_timebase`
- Conductor tempo meta events use:
  - `mpqn = 60000000 / tempo`
- Multiple tempo-state writes at the same raw tick are coalesced to the final state;
  zero-duration intermediate states are retained only in the raw decoded event list.
- `0xBC` keeps the active timebase and applies `unsigned(value) - 0x40` to
  tempo, clamped to `20..255`.
- `0xBF` starts a new tempo segment at the reset tick using `125 BPM` while
  preserving the active timebase.
- Scheduled note expiries at raw tick `T` are flushed before any other event at
  raw tick `T`.

## Lane Model

- Local lane = `trackIndex * 4 + voice`.
- Initial logical channel = local lane.
- `0xE5` rewrites the logical channel for one local lane:
  - source lane = `(trackIndex * 4) + part`
  - destination logical channel = `value & 0x3F`
- Logical channels `0..15` produce MIDI output.
- Logical channels `16..63` remain routing state and produce no MIDI output.

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

The MIDI bridge emits tick-`0` defaults on all `16` MIDI channels:

- `CC7 = 126`
- `CC10 = 64`
- `RPN 0 / 0`, `Data Entry MSB = 2`, `LSB = 0`
- `Pitch Bend = 8192`
- `CC1 = 0`

## Ordinary Note Mapping

Parser rules:

- ordinary discriminator: `(status & 0x3F) != 0x3F`
- special-envelope status classes: `0x3F`, `0xBF`, `0x7F`, `0xFF`
- ordinary pitch field: `0..62`
- `note = 0`: velocity field `63`, octave index `0`
- `note > 0`: first extra byte supplies velocity and octave index
- remaining extra bytes: consumed reserved bytes

Native scheduler pitch:

- mode `1`: base `35`
- modes `0,2..7`: base `45`
- native key: `(logicalChannel, base + pitch + octaveOffset)`

MIDI presentation pitch:

- ordinary lane: native mode base
- special lane: MIDI base `35`
- emitted note: `clamp(0,127,midiBase + pitch + octaveOffset)`

Gate refresh always uses the native key. MIDI lane pitch adaptation stays outside native scheduler identity.

Octave table:

- `0 -> 0`
- `1 -> +12`
- `2 -> -24`
- `3 -> -12`

Velocity:

- `note > 0`: `clamp(1,127,2*((attr>>2)&0x3F))`
- `note = 0`: `126`

Gate:

- native raw end: `rawStart + gate`
- same native key before expiry: refresh existing gate
- refresh retains original sounding state and velocity
- expiry at tick `T` runs before a track event at `T`
- native `gate=0`: same-tick expiry
- sounding MIDI serialization: `midiEnd=max(midiStart+1,rawToMidi(rawEnd))`
- the MIDI `+1` rule stays outside native gate state

Note-on suppression state:

- initial flag: clear
- `E0` runs the native patch helper
- `E1` runs the helper in mode `1`
- `BA` runs the helper when the new mode equals `1`
- helper in mode `0/1`: clear flag
- helper in mode `2..7`: set flag
- `BA` mode `0,2..7`: retain flag
- set flag: create silent active gate node
- clear flag: create sounding node and emit MIDI note-on

Patch sync for a sounding note runs before MIDI note-on.

## Patch Mapping

Native state fields:

- `E0`: `program=value&0x3F`; native patch helper runs
- `E1`: `bank=value&0x3F`; helper runs in mode `1`
- `BA`: channel=`(value>>3)&0x0F`, mode=`value&7`; helper runs for new mode `1`

MIDI patch word:

- `patch12 = (program & 0x3F) | ((bank & 0x3F) << 6)`

Low-bank table for `(bank & 0x3E) == 0`:

- `0 -> 0`
- `1 -> 9`
- `2 -> 16`
- `3 -> 24`
- `4 -> 13`
- `5 -> 74`

MIDI output:

- message: Program Change
- program: `patchWord & 0x7F`
- initial MIDI patch value: `0`
- mode `1` entry can mark the current MIDI patch dirty
- patch deduplication uses final MIDI patch state

Patch state records retain native mode, bank, program, kind, sub, and value.

## Ordinary Control Mapping

### `0xFF` framing

| Command range | Parsed form | MIDI action |
|---|---|---|
| `00..7F` | `1+exst` body bytes | preserve reserved envelope |
| `80..EF` | one value byte | ordinary-system dispatch |
| `F0..FE` | BE16 length + body | preserve reserved envelope |
| `FF` | BE16 length + body | preserve machine-dependent event |

### Global and timeline controls

| MLD | Native acceptance/state | MIDI bridge |
|---|---|---|
| `B0` | track0, value `<128`; absolute master volume | CC7 all 16 channels |
| `B1` | track0, value `<128`; master balance backend control | CC10 all 16 channels |
| `B2..B9` | empty handler | no MIDI output |
| `BA` | track0, value `<128`; mode update | patch/suppression state |
| `BB` | empty handler | no MIDI output |
| `BC` | track0, value `<128`; relative tempo | conductor tempo meta |
| `BD` | track0, value `<128`; relative master volume from cache default `100` | CC7 all 16 channels |
| `BE` | track0, value `0` | active-note stop + CC120 all channels |
| `BF` | track0, any value; session reset | active-note stop + CC120 + reset defaults |
| `C0..CF` | timing state | conductor tempo meta for valid table entries |
| `D0` | conditional native stop trigger | no MIDI output |
| `D1..DB` | empty handler | no MIDI output |
| `DC` | next-delta high byte | parser state |
| `DD` | four-slot native loop controller | one MIDI transport loop range |
| `DE` | consumed no-op | no MIDI output |
| `DF` | current track parser done | parser boundary |
| `EB..EF` | empty handler | no MIDI output |

Master-volume state:

- initial native cache: `100`
- `B0`: `masterVolume=value`
- `BD`: `masterVolume=clamp(0,127,masterVolume+value-0x40)`
- `BF`: `masterVolume=100`
- `B0/BD` MIDI mapping: all-channel CC7 broadcast
- per-channel `E2/E6` CC7 can later replace that channel's broadcast value

`D0` depends on the native decoder runtime field at `+0x2C` and has no MIDI mapping.

`DF` ends decoding of the current `trac` immediately. Trailing bytes in that track stay outside the decoded event list.

### Channel controls `E0..EA`

Source voice=`value>>6`. Payload=`value&0x3F`. The current voice map resolves the logical channel.

| MLD | Native state/action | MIDI bridge |
|---|---|---|
| `E0` | program cache + patch helper | MIDI patch update when eligible |
| `E1` | bank cache; helper in mode1 | MIDI patch update in mode1 |
| `E2` | absolute level | CC7 |
| `E3` | pan | CC10 |
| `E4` | coarse cache + pitch apply | pending pitch-range RPN, then pitch bend |
| `E5` | voice map destination `0..63` | lane routing state |
| `E6` | relative level | CC7 |
| `E7` | range cache for values `0..24` | arm pending RPN output |
| `E8` | fine cache + pitch apply | pending pitch-range RPN, then pitch bend |
| `E9` | fine cache | no immediate MIDI output |
| `EA` | backend control value `2*low6` | CC1 proxy |

Pitch bend:

- native value: `(32*pitchCoarse + pitchFine)*8 - 256`
- MIDI value: clamp to `0..16383`

Pitch range:

- `E7` stores a native range cache
- native `E4/E8` pitch-apply path reads that cache
- the MIDI bridge emits pending RPN `0/0` immediately before the next mapped `E4/E8` pitch bend
- MIDI RPN state persists after that write

Controller deduplication uses final `(midiChannel, controller, value)` state.

### `DD` loop mapping

Native loop slots: `0..3`.

- operation `0`: save start snapshot
- operation `1`: end/repeat
- repeat nibble `0`: infinite
- repeat nibble `1..15`: additional passes
- finite total passes: `N+1`
- operation `2/3`: no loop action
- a new start for one slot replaces that slot's saved start snapshot

MIDI transport exposes one loop range. The bridge selects the lowest numbered
complete slot in the linear pass. Additional complete slots remain in decoded
loop state.

Zero-duration DD behavior is compiled as a native track-event stop boundary.
Later track events, including higher-track events at the same raw tick, are
excluded. Active gate expiries retain their scheduled native timing under the
timing state established at that boundary.

## Live-Mix Chase Approximation

For ordinary `0xE2`, `0xE3`, and `0xE6`, the MIDI bridge can replace one
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

Bridge defaults:

- special-lane mask starts with logical channel `9`
- GM drum output channel is MIDI channel `10` (`index 9`)
- non-special active lanes are compressed left-to-right around that reserved
  drum lane

Final output mapping rule:

- special lane -> MIDI channel `10`
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

## Resource-State Mapping

`thrd`, `ainf`, `adat`, and live `0x7F` state remain outside the MIDI event stream.

Initial `thrd` state preserves:

- global config byte
- target (`synth` / `audio`)
- logical channel
- raw selector

The sound backend owns the config-table count and base. MLD conversion preserves the selector without resolving the external table entry.

Active resource catalog:

- active `adat` indices come from the contiguous resource-stage run beginning at `10 + header_length`
- `ainf` low6 defines the required active count
- each active entry preserves chunk offset, payload length, and active index

Live resource state preserves:

- `0x7F 00`: local lane, active `adat` index, linked active entry, live low6 parameter, doubled dispatch argument
- `0x7F 01`: local lane and direct stop slot/index
- `0x7F 80`: local audio lane, low6 level field, doubled dispatch argument
- `0x7F 81`: local audio lane, low6 pan field, doubled dispatch argument
- `0x7F 90`: local lane, target, raw selector, resolved logical channel, channel-dispatch eligibility, conditional out-of-range clear
- `0x7F F0`: 3D dispatch candidate, low5 helper argument, raw distance, transformed angle pair, raw duration, event-order native duration in milliseconds
- `0x7F F1..FF`: decoded long-form envelope state

`0x7F F0` has no MIDI lane field. Its MLD-side dispatch candidate requires track `0` and at least six body bytes. Distance scaling, config-table count, callback installation, and callback execution are backend-owned runtime state.

Layered families dispatch `0x7F 01/80/81` to audio backend vtable slots `+0x34/+0x38/+0x3C`. Monolithic `lib002/lib004` parse the same fields and reach fixed no-op stubs for these three commands. `0x7F 00`, `0x7F 90`, and eligible `0x7F F0` retain native backend/state effects in both family groups.

`0x7F 90` synth target uses the current voice assignment. Resolved channels `16..63` suppress native config dispatch.

Selector `31` carries a clear condition when the external config-table count is `31` or lower. A table containing index `31` selects that entry.

All resource-state entries produce zero MIDI messages.

## Non-Sounding Families

The following families produce no sounding MIDI:

- `0x7F` resource events
- `0xFF F0..FF` machine-dependent events
- cue / nop / end markers

These families remain in the parsed timeline and decoded event model.
They produce no MIDI output in this mapping layer.
