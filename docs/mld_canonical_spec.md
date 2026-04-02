# MLD Canonical Spec

>The MLD (MFi) specification was obtained through static reverse engineering of the DoJa Soundlib(MFi Player) and completed by GPT 5.4.

>Since this project implements playback via MIDI mapping, the above specification may contain inconsistencies with the project’s code.

>**NOTE: AI-generated content may contain inaccuracies. I can only do my best to iteratively validate it by leveraging multiple AI systems, in order to minimize the likelihood of such errors.**

## Scope

This document defines the established `melo` container grammar, ordinary track
event model, live `0x7F` resource family, and branch-bounded
machine-dependent families.

## Branches

Three behaviorally relevant branches exist:

- `monolithic`
  - used by `lib002` and `lib004`
  - owns raw selector families `71/01 10/11/12/40/41/B0/B1/B2`
- `layered-normal`
  - used by `lib003`
  - owns the newer parser, live resource model, and normal-synth subtype rules
- `layered-ft`
  - used by `lib003ft`
  - shares the same outer parser and rule blob as `layered-normal`
  - diverges at the synth backend protocol

Unsupported or branch-dead machine-dependent families remain valid grammar.

## Container

All established files use the `melo` container.

Fixed header:

- `0x00..0x03`: ASCII `melo`
- `0x04..0x07`: big-endian `file_size - 8`
- `0x08..0x09`: big-endian `header_length`
- `0x0A`: major type
- `0x0B`: minor type
- `0x0C`: track count

`header_length` is measured from the byte after the field itself.

## Top-Level Chunks

Verified top-level framing split in the newer desktop parser:

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
- `thrd`
- `ainf`
- `adat`
- `trac`

`supt` can appear on disk with the ordinary top-level `be16` framing, but the
verified newer desktop parser has no dedicated storage path for it.

## Top-Level Chunk Semantics

- for `vers`, `sorc`, `note`, `exst`, `date`, `thrd`, and `ainf`, only the
  first accepted top-level chunk of each kind is kept
- later duplicates remain valid grammar but are skipped without overwriting the
  first accepted state

### `vers`

- ASCII text
- accepted parser windows:
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
- `note = 0x0001` means ordinary note layout is:
  - `delta`
  - `status`
  - `gate`
  - `attr`
- if `note > 1`, the first extra byte is interpreted and the remaining bytes
  are reserved / skipped

### `exst`

- big-endian extension-count field for the live `0x7F` resource family
- top-level payload length is exactly `2`
- only the first accepted top-level `exst` chunk is kept
- later duplicate `exst` chunks are skipped without overwriting the first value
- it does not belong to the ordinary `0xFF` system-event family
- `0x7F 00` consumes one packed selector byte plus `exst` extension bytes
- `0x7F 01` skips `1 + exst` bytes total and currently uses only the first
  packed selector byte

### `titl`, `copy`, `prot`, `auth`

- metadata / tool / provider strings
- copied into dedicated backend-owned `0x100`-byte text buffers
- unlike `vers`, `sorc`, `note`, `exst`, `date`, `thrd`, and `ainf`, these
  chunks have no parser-side first-write guard
- later duplicates overwrite the previously copied text

### `date`

- metadata date string
- only the first accepted top-level `date` chunk is kept
- later duplicates are skipped without overwriting the first value

### `supt`

- auxiliary tool string when present on disk
- uses the ordinary top-level `be16` framing
- not copied into the newer desktop parser's dedicated metadata side object
- not decode-critical in the verified desktop-library line

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

- top-level resource-count / index declaration for the newer desktop parser
- stays in the `be16` top-level group
- only the first accepted top-level `ainf` chunk is applied
- if byte `0` has bit `0x40` set, the newer-parser active `adat` table is
  rejected
- otherwise the low 6 bits of byte `0` define the active top-level `adat`
  count

### `adat`

- top-level resource payload family for the newer desktop parser
- parsed later through its own `be32` chunk table

### `adpm`

- not a recognized top-level chunk in the newer desktop parser
- current verified usage is as a selector-local subchunk inside legacy `adat`
  selector bodies

### Selector `0x81` / Type `0x8001`

Established on-disk contract:

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

All other established ordinary events are note-family events.

## Delta and Timing

Default delta is one byte.

`0xFF 0xDC value` is an extended-delta prefix:

- it stores the high byte for the next event delta
- the following effective delta becomes `low + (value << 8)`

Tempo / timebase commands:

- `0xC0..0xC6` -> `6, 12, 24, 48, 96, 192, 384`
- `0xC8..0xCE` -> `15, 30, 60, 120, 240, 480, 960`

Timing scale:

- `15360000 / timebase / tempo`

## Ordinary Note Events

Under `note = 0x0001`, ordinary note layout is:

- `delta`
- `status`
- `gate`
- `attr`

Decoded fields:

- `voice = status >> 6`
- `pitch = status & 0x3F`
- `gate = gate_byte`
- `velocity = attr >> 2`
- `octave_shift = attr & 0x03`

Octave-shift table:

- `0 -> 0`
- `1 -> +12`
- `2 -> -24`
- `3 -> -12`

Gate semantics:

- `gate` is the live note-off scheduling delay
- repeated same-note events on the same resolved live channel refresh the
  pending gate instead of creating overlapping identical voices
- note-off queue expiry wins ties against same-tick track-event processing
- actual sounding pitch depends on channel mode:
  - mode `1` -> `35 + pitch + octave_table`
  - other ordinary modes -> `45 + pitch + octave_table`

## Ordinary System Events

For `0xFF`, the next byte is a command byte.

If `command < 0xF0`, the layout is:

- `delta`
- `0xFF`
- `command`
- `value`

If `command >= 0xF0`, the layout is:

- `delta`
- `0xFF`
- `command`
- `payload_length_be16`
- `payload`

Established ordinary-system commands:

- `0xB0`: master volume
- `0xBA`: patch / mode selector
- `0xC0..0xCE`: tempo / timebase
- `0xD0`: cue / section marker
- `0xDC`: extended delta for the next event
- `0xDD`: loop control
- `0xDE`: no-op
- `0xDF`: end of track
- `0xE0`: program selector update
- `0xE1`: bank / family selector update
- `0xE2`: absolute channel level
- `0xE3`: pan
- `0xE4`: coarse pitch component
- `0xE5`: voice-to-channel assignment
- `0xE6`: relative / expression-like level control
- `0xE7`: pitch-range scaler
- `0xE8`: fine pitch component
- `0xE9`: cached fine-control byte with no immediate backend call in the
  currently verified normal branch
- `0xEA`: modulation-like control

`0xFF` with `command >= 0xF0` is the machine-dependent envelope.

## Ordinary Channel State

Ordinary-track live state rules:

- `0xE5` assigns local voices to logical channels:
  - `voice_map[local_voice] = value & 0x3F`
- `0xE0`, `0xE1`, and `0xBA` define backend-facing `(mode, bank, program)`
  state
- `0xBA` values outside ordinary melodic modes can suppress ordinary note start
- `0xE2` and `0xE6` are one level-control family
- `0xE4`, `0xE7`, and `0xE8` are one coupled pitch-control cluster
- `0xE9` is a cached fine-control byte
- `0xEA` is table-driven

## Loop Semantics

`0xDD` is a multi-slot loop family.

Bit layout:

- top 2 bits: loop slot `0..3`
- low 2 bits: operation
  - `0` = loop start
  - `1` = loop end
- on loop end, bits `2..5`: repeat count

Repeat-count rule:

- repeat count `0` means infinite repeat

Example:

- `FF DD 00` = slot-0 loop start
- `FF DD 01` = slot-0 loop end with infinite repeat

## Live `0x7F` Resource-Control Family

Playback-relevant established members:

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
  - current lifted branch only uses that first packed selector byte
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

### Layered Newer-Parser Audio Families

- `71 81`: compact audio channel level
- `71 82`: compact audio channel pan
- `71 83`: legacy `0x8000` slot family
- `71 84`: `0x8001` slot family
- `71 86`: extended-rate `0x8000` slot family
- `71 8F`: valid grammar; stub / no-op in the shipped layered-normal build

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

In `layered-normal`, branch-dead forms are:

- `71 10`
- `71 11`
- `71 90`
- `71 91`
- `71 92`

Established meanings:

- `71 12`: conditional rewrite-match installer / remover
- `71 93`: conditional mapping-rewrite installer

In `layered-ft`, established raw bridge relations are:

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

Established meanings:

- `11 01 F0 07`: compact audio-load family
- `11 01 F1`: compact slot-control family
- `31 10`: mixed second-stage dispatcher
- `11 01 F2 07`: compact 16-channel route / mode map family
- `11 01 F0 05` and `11 01 F0 04`: conditional mapping-rewrite installers
- `11 01 F0 06` and `11 01 F0 03`: valid grammar, inactive in the shipped
  desktop set

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

Established meanings:

- `71/01 10`: sibling programming surface over a `0..63` type-2 target bank
- `71/01 11`: structured batch-update language over a `16`-target control
  surface
- `71/01 12`: compact live form of the same `16`-target surface
- `71/01 40`: compact 4-lane binding-descriptor family
- `71/01 41`: companion note-relative 7-bit curve family
- `71/01 B0/B1/B2`: auxiliary `DSYNC` extension family
