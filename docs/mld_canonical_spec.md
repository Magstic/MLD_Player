# MLD Canonical Spec

>The MLD (MFi) specification was obtained through static reverse engineering of the DoJa Soundlib(MFi Player) and completed by GPT 5.4.

>Since this project implements playback via MIDI mapping, the above specification may contain inconsistencies with the project’s code.

>**NOTE: AI-generated content may contain inaccuracies. I can only do my best to iteratively validate it by leveraging multiple AI systems, in order to minimize the likelihood of such errors.**

## Scope

This document defines the `melo` container grammar, ordinary track event model,
live `0x7F` resource family, and branch-specific machine-dependent families.

## Branches

Three behaviorally relevant branches exist:

- `monolithic`
  - used by `lib002` and `lib004`
  - owns raw selector families `71/01 10/11/12/40/41/B0/B1/B2`
- `layered-normal`
  - used by `lib003`
  - provides the layered parser, live resource model, and normal-synth subtype rules
- `layered-ft`
  - used by `lib003ft`
  - shares the same outer parser and rule blob as `layered-normal`
  - diverges at the synth backend protocol

Parser-valid machine-dependent families may have no runtime action in a branch.

## Container

The container signature is `melo`.

Fixed header:

- `0x00..0x03`: ASCII `melo`
- `0x04..0x07`: big-endian `file_size - 8`
- `0x08..0x09`: big-endian `header_length`
- `0x0A`: major type
- `0x0B`: minor type
- `0x0C`: track count

`header_length` is measured from the byte after the field itself.

## Top-Level Chunks

`layered-normal` and `layered-ft` use the following top-level framing:

- ordinary info chunks plus top-level `thrd` and `ainf` use:
  - `chunk_id[4]`
  - `payload_length_be16`
  - `payload`
- top-level `adat` uses:
  - `adat`
  - `payload_length_be32`
  - `payload`
- track chunks use:
  - `trac`
  - `payload_length_be32`
  - raw event stream

Recognized top-level chunk ids:

- `vers`
- `sorc`
- `prot`
- `auth`
- `titl`
- `copy`
- `date`
- `note`
- `exst`
- `cuep`
- `thrd`
- `ainf`
- `adat`
- `trac`

`supt` can appear on disk with the ordinary top-level `be16` framing.
The layered parser consumes its payload and stores no dedicated metadata object.

## Top-Level Chunk Semantics

- for `vers`, `sorc`, `note`, `exst`, `date`, `thrd`, and `ainf`, only the
  first accepted top-level chunk of each kind is kept
- later duplicates remain valid grammar; the parser skips them and preserves the
  first accepted state

### `vers`

- ASCII text
- accepted version windows:
  - `0201/0301` -> `2..3`
  - `0201/0301/0401` -> `2..4`
  - `0401` -> `4..4`
  - `0501` -> `5..5`
- `0601` is rejected

### `sorc`

- source / flags byte

### `note`

- big-endian extra-note-byte count
- top-level payload length is exactly `2`
- only the first accepted top-level `note` chunk is kept
- later duplicate `note` chunks are skipped without overwriting the first value
- `note = N` is the number of extra bytes following the ordinary note gate byte
- `note = 0` is valid and yields `delta, status, gate` with no attribute byte
- `note > 0` yields `delta, status, gate, extra[0..N-1]`
- only `extra[0]` is interpreted as the ordinary note attribute; any remaining
  extra bytes are reserved / skipped

### `exst`

- big-endian extension-count field for the live `0x7F` resource family
- top-level payload length is exactly `2`
- only the first accepted top-level `exst` chunk is kept
- later duplicate `exst` chunks are skipped without overwriting the first value
- `0x7F 00` consumes one packed selector byte plus `exst` extension bytes
- `0x7F 01` consumes `1 + exst` bytes total and uses the first packed selector byte

### `cuep`

- cue-point start table for track chunks
- uses the ordinary top-level `be16` framing
- payload contains one big-endian 32-bit start-point offset per declared track
- entry order follows the declared track order
- `0xFFFFFFFF` means that the corresponding track is inactive during cue-point
  playback
- cue-point offsets are byte positions inside track event streams
- ordinary full playback begins every track event stream at byte offset `0`

### `titl`, `copy`, `prot`, `auth`

- metadata / tool / provider strings
- copied into dedicated `0x100`-byte metadata buffers
- every occurrence copies into the same metadata buffer
- later duplicates overwrite the previously copied text

### `date`

- metadata date string
- only the first accepted top-level `date` chunk is kept
- later duplicates are skipped without overwriting the first value

### `supt`

- auxiliary tool string when present on disk
- uses the ordinary top-level `be16` framing
- the layered parser consumes the payload and stores no dedicated metadata object

### `thrd`

- initial per-channel config surface
- same semantic family as live `0x7F 90`
- only the first accepted top-level `thrd` chunk is applied
- byte `0` is a global side value
- remaining bytes are parsed as floor-divided 2-byte records
- per record:
  - first byte low 4 bits = logical channel `0..15`
  - second byte bit `5` = target table (`0` = synth, `1` = audio)
  - second byte low 5 bits = raw config subvalue
- duplicate records for the same `(target table, logical channel)` are ignored
  after the first write

### `ainf`

- top-level resource-count / index declaration for layered branches
- stays in the `be16` top-level group
- only the first accepted top-level `ainf` chunk is applied
- byte `0` bit `0x40` disables the active `adat` table
- otherwise the low 6 bits of byte `0` define the active top-level `adat`
  count

### `adat`

- top-level resource payload family for layered branches
- parsed later through its own `be32` chunk table

### `adpm`

- excluded from the layered top-level chunk table
- used as a selector-local subchunk inside legacy `adat` selector bodies

### Selector `0x81` / Type `0x8001`

On-disk contract:

- selector `0x81` feeds the audio-side `0x8001` family
- accepted input window:
  - sample rate `8000`, `16000`, or `32000`
  - coded bit depth `2` or `4`
  - channels `1` or `2`
- packed-code extraction is low-bit-first:
  - `2-bit`: four codes per byte
  - `4-bit`: two codes per byte
- mono decodes one compressed stream and duplicates the exported result to left
  and right
- stereo splits the blob into two contiguous half-streams, decodes them
  independently, then uses them as left and right
- backend-native working format is fixed `32000 Hz`, `32-bit`, `2-channel`

## Track Event Stream

Each `trac` payload is an event stream.

Every event begins with:

- `delta[1]`
- `status[1]`

Special status families:

- `0x7F`: live resource-control family
- `0xFF`: system / machine-dependent family

All remaining ordinary status values are note-family events.

Ordinary full-playback start model:

- each `trac` event stream starts at byte offset `0`
- each track's raw timeline starts at raw tick `0`
- the first event's delta advances from that origin
- tracks align by accumulated raw ticks
- `cuep` supplies alternate per-track start offsets for cue-point playback

## Delta and Timing

Default delta is one byte.

`0xFF 0xDC value` is an extended-delta prefix:

- it stores the high byte for the next event delta
- the following effective delta becomes `low + (value << 8)`

Tempo / timebase commands:

- `0xC0..0xC6` -> `6, 12, 24, 48, 96, 192, 384`
- `0xC8..0xCE` -> `15, 30, 60, 120, 240, 480, 960`
- `0xC7` and `0xCF` have zero table entries and are ignored by the native timing handler

Timing scale:

- `15360000 / timebase / tempo`

Live tempo and reset controls:

- the default tempo state is `125 BPM / timebase 48`
- valid ordinary timebase commands update the active timebase and set tempo to
  `max(unsigned(value), 20)`
- `0xBC` applies `unsigned(value) - 0x40` to the active tempo
- relative tempo is clamped to `20..255`
- `0xBC` preserves the active timebase
- `0xBF` restores tempo to `125 BPM` and preserves the active timebase

## Ordinary Note Events

Ordinary-note discriminator:

- `(status & 0x3F) != 0x3F`
- special-envelope low-six-bit sentinel: `0x3F`
- special-envelope status classes: `0x3F`, `0xBF`, `0x7F`, `0xFF`
- ordinary pitch field: `0..62`

For top-level `note = N`:

- layout: `delta, status, gate, extra[N]`
- `voice = status >> 6`
- `pitch = status & 0x3F`
- `gate = gate_byte`
- `N = 0`: velocity field `63`, octave index `0`
- `N > 0`: velocity field `extra[0] >> 2`, octave index `extra[0] & 3`
- `extra[1..N-1]`: reserved bytes, fully consumed

Octave table:

- `0 -> 0`
- `1 -> +12`
- `2 -> -24`
- `3 -> -12`

Native note state:

- mode `1` pitch base: `35`
- modes `0,2..7` pitch base: `45`
- native note: `base + pitch + octave_offset`
- backend velocity: `2 * velocity_field`
- active-note key: `(resolved_channel, native_note)`

Gate scheduler:

- raw expiry tick: `raw_start + gate`
- same-key event before expiry: gate refresh
- refresh retains original sounding state and velocity
- expiry at raw tick `T` precedes track-event dispatch at `T`
- `gate = 0` permits same-tick expiry

Patch-helper suppression state:

- initial `noteOnSuppressed = 0`
- helper result for mode `0/1`: `noteOnSuppressed = 0`
- helper result for mode `2..7`: `noteOnSuppressed = 1`
- `0xE0`: helper call on every accepted event
- `0xE1`: helper call when current mode equals `1`
- `0xBA`: helper call when new mode equals `1`
- `0xBA` with mode `0,2..7`: suppression flag retained
- active gate insertion precedes the suppression branch
- suppressed note start creates a silent active gate node

`lib001`, `lib002`, `lib003`, `lib003ft`, and `lib004` share these ordinary-note rules.

## Ordinary System Events

### `0xFF` framing

After `delta, 0xFF`, one command byte follows.

| Command range | Encoding after command | Dispatch |
|---|---|---|
| `00..7F` | `1 + exst` body bytes | short special envelope |
| `80..EF` | one value byte | ordinary-system dispatcher |
| `F0..FE` | `payload_length_be16, payload` | passive long envelope |
| `FF` | `payload_length_be16, payload` | machine-dependent dispatcher |

Every `80..EF` command consumes its value byte, including commands with an empty handler.

### Global and timeline commands

| Command | Native rule |
|---|---|
| `B0` | track `0`, value `< 0x80`; absolute master volume; initial cache `100` |
| `B1` | track `0`, value `< 0x80`; master balance backend control |
| `B2..B9` | empty handler |
| `BA` | track `0`, value `< 0x80`; channel=`(value>>3)&0x0F`, mode=`value&7` |
| `BB` | empty handler |
| `BC` | track `0`, value `< 0x80`; relative tempo; clamp `20..255` |
| `BD` | track `0`, value `< 0x80`; `masterVolume=clamp(0,127,masterVolume+value-0x40)` |
| `BE` | track `0`, value `0`; global forced-stop backend path |
| `BF` | track `0`, any value; session reset |
| `C0..CF` | track `0`; timing table handler |
| `D0` | track `0`, decoder runtime field `+0x2C == 1`, value `1`; sets global stop flags `+0x68/+0x9C` |
| `D1..DB` | empty handler |
| `DC` | stores `value<<8` as the next event's delta high byte |
| `DD` | track `0`; four-slot loop controller |
| `DE` | consumed no-op |
| `DF` | marks the current track parser context done and returns immediately |
| `EB..EF` | empty handler |

`BF` reset state:

- tempo: `125`
- active timebase: retained
- master volume: `100`
- voice map: identity
- channel mode: `0`
- bank: `0`
- program: `0`
- note-on suppression/helper flag: `0`
- pitch coarse: `32`
- pitch fine: `32`
- level: `63`
- active gate/list state: reset

### Channel commands `E0..EA`

Packed value fields:

- source voice: `value >> 6`
- payload: `value & 0x3F`
- source voice resolves through the current voice map
- resolved channels `0..15` enter backend control handlers
- `E5` accepts destination values `0..63`

| Command | Native state and backend action |
|---|---|
| `E0` | program=`low6`; patch helper runs |
| `E1` | bank=`low6`; patch helper runs for mode `1` |
| `E2` | level=`low6`; backend value=`2*level` |
| `E3` | pan=`low6`; backend value=`2*pan` |
| `E4` | pitch coarse=`low6`; immediate pitch apply using cached fine byte |
| `E5` | selected local voice maps to logical channel `low6` |
| `E6` | level=`clamp(0,63,level+low6-32)`; backend value=`2*level` |
| `E7` | accepts `low6<=24`; updates synth pitch-range cache |
| `E8` | pitch fine=`low6`; immediate pitch apply using cached coarse byte |
| `E9` | pitch fine=`low6`; cache update only |
| `EA` | backend control path with value `2*low6` |

Pitch apply formula for `E4/E8`:

- `bend = (32*pitchCoarse + pitchFine)*8 - 256`
- valid `E7` range is read by the pitch-apply wrapper on later `E4/E8` calls

Patch-helper call sites:

- `E0`: always
- `E1`: mode `1`
- `BA`: new mode `1`

The helper owns the ordinary note-on suppression flag described in the note section.

## Loop Semantics

`0xDD` value layout:

- slot: `(value >> 6) & 3`
- operation: `value & 3`
- repeat nibble on operation `1`: `(value >> 2) & 0x0F`

Operations:

- `0`: capture loop start time and per-track parser/scheduler cursor snapshots for slot `0..3`
- `1`: loop end
- `2,3`: consumed with no loop action

Repeat rule:

- nibble `0`: infinite loop
- nibble `1..15`: that many additional passes
- total body passes for finite loop: `repeatNibble + 1`

Loop rewind restores captured parser/scheduler cursors across all track contexts.
Four loop slots can remain live independently.

Zero-duration rule:

- loop-end raw time equal to saved loop-start raw time marks every track parser context done
- decoder returns immediately from the loop handler
- equal-time track selection uses ascending track context order; lower track index wins the tie
- track `0` zero-duration `DD` can terminate later same-tick events from higher tracks

## Live `0x7F` Resource-Control Family

Playback members:

- `0x7F 00`: trigger / start resource
- `0x7F 01`: stop resource
- `0x7F 80`: audio channel level
- `0x7F 81`: audio channel pan
- `0x7F 90`: persistent per-channel config

Persistent-config semantics:

- `thrd` and live `0x7F 90` are one semantic family
- `thrd` provides startup / reset defaults
- live `0x7F 90` updates session state
- live `0x7F 90` additionally exposes clear sentinel `31`

Short-form payload layouts:

- `0x7F 00`
  - first payload byte:
    - high 2 bits = audio bank lane
    - low 6 bits = `adat` entry index
  - total short-form body length = `1 + exst`
  - when `exst >= 1`, the second consumed byte low 6 bits become live
    parameter `2 * value`
  - when `exst == 0`, that live parameter defaults to `126`
- `0x7F 01`
  - first payload byte:
    - high 2 bits = audio bank lane
    - low 6 bits = `adat` entry index
  - total short-form body length = `1 + exst`
  - the first packed selector byte supplies the active selector
- `0x7F 80`
  - one-byte compact payload
  - high 2 bits = audio bank lane
  - low 6 bits = level value divided by `2`
  - effective forwarded level is `2 * low6`
- `0x7F 81`
  - one-byte compact payload
  - high 2 bits = audio bank lane
  - low 6 bits = pan value divided by `2`
  - effective forwarded pan is `2 * low6`
- `0x7F 90`
  - one-byte compact payload
  - high 2 bits = local lane / slot selector
  - bit `5` = target table (`0` = synth, `1` = audio)
  - low 5 bits = raw config subvalue
  - raw subvalue `31` is the explicit clear / zero case

Extended validity:

- other low-valued unhandled `0x7F` subcommands still consume `1 + exst` bytes
  total and remain valid grammar
- `0x7F F0` is a separate long-form auxiliary family
- `0x7F F1..FF` remain length-delimited valid grammar

Universal real-time control subfamily:

- `0x7F ?? 0x04 0x01`: master volume
- `0x7F ?? 0x04 0x02`: master balance / pan
- `0x7F ?? 0x04 0x03`: master fine tuning
- `0x7F ?? 0x04 0x04`: master coarse tuning
- `0x7F ?? 0x04 0x05`: constrained parameter pair

## Machine-Dependent Families

Machine-dependent payloads are branch-bounded.

### Layered Audio Families

- `71 81`: compact audio channel level
- `71 82`: compact audio channel pan
- `71 83`: legacy `0x8000` slot family
- `71 84`: `0x8001` slot family
- `71 86`: extended-rate `0x8000` slot family
- `71 8F`: valid grammar; no runtime action in `layered-normal`

Control model:

- `71 83`
  - mode `0`: load / refresh
  - mode `1`: same as mode `0`
  - mode `2`: start loaded slot
  - mode `3`: ignore
- `71 84`
  - mode `0`: load / refresh only
  - mode `1`: load / refresh then start
  - mode `2`: start without reload
  - mode `3`: ignore
- `71 86`
  - same control model as `71 84`

### Layered One-Byte Synth-Bridge Families

In `layered-normal`, live forms are:

- `71 12`
- `71 93`

In `layered-normal`, runtime-inactive forms are:

- `71 10`
- `71 11`
- `71 90`
- `71 91`
- `71 92`

Meanings:

- `71 12`: conditional rewrite-match installer / remover
- `71 93`: conditional mapping-rewrite installer

In `layered-ft`, raw bridge relations are:

- `71 10 / 71 11 / 71 12` -> internal `0x10 / 0x11 / 0x12`
- `.. 92 40 ...` / `.. 92 41 ...` -> internal `0x40 / 0x41`

### Layered Compact Mixed Families

Recognized raw families:

- `11 01 F0 07`
- `11 01 F1`
- `31 10`
- `11 01 F2 07`
- `11 01 F0 05`
- `11 01 F0 06`
- `11 01 F0 03`
- `11 01 F0 04`

Meanings:

- `11 01 F0 07`: compact audio-load family
- `11 01 F1`: compact slot-control family
- `31 10`: mixed second-stage dispatcher
- `11 01 F2 07`: compact 16-channel route / mode map family
- `11 01 F0 05` and `11 01 F0 04`: conditional mapping-rewrite installers
- `11 01 F0 06` and `11 01 F0 03`: valid grammar; no runtime action in the layered branch set

### Monolithic Selector Families

These belong to `monolithic`:

- `71/01 10`
- `71/01 11`
- `71/01 12`
- `71/01 40`
- `71/01 41`
- `71/01 B0`
- `71/01 B1`
- `71/01 B2`

Meanings:

- `71/01 10`: sibling programming surface over a `0..63` type-2 target bank
- `71/01 11`: structured batch-update language over a `16`-target control
  surface
- `71/01 12`: compact live form of the same `16`-target surface
- `71/01 40`: compact 4-lane binding-descriptor family
- `71/01 41`: companion note-relative 7-bit curve family
- `71/01 B0/B1/B2`: auxiliary `DSYNC` extension family
