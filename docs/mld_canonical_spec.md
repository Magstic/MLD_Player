# MLD Canonical Spec

>The MLD (MFi) specification was obtained through static reverse engineering of the DoJa Soundlib(MFi Player) and completed by GPT 5.4.

>Since this project implements playback via MIDI mapping, the above specification may contain inconsistencies with the project’s code.

>**NOTE: AI-generated content may contain inaccuracies. I can only do my best to iteratively validate it by leveraging multiple AI systems, in order to minimize the likelihood of such errors.**

## Scope

This document defines the `melo` container grammar, ordinary track event model,
live `0x7F` resource family, and machine-dependent descriptor families.

## Branches

Three behaviorally relevant branches exist:

- `monolithic`
  - used by `lib002` and `lib004`
  - uses the shared machine-dependent descriptor profile with a monolithic handler table
- `layered-normal`
  - uses `MFiPluginMFi5.dll` with `MFiSynth.dll`
  - uses the shared machine-dependent descriptor profile with layered handlers and normal-synth downstream rules
- `layered-ft`
  - uses `MFiPluginMFi5.dll` with `MFiSynth_ft.dll`
  - uses the shared machine-dependent descriptor profile with layered handlers and FT-synth downstream rules

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

- header-level startup channel-config state
- only the first accepted header `thrd` chunk is applied
- payload length is at least `1`
- byte `0` is the global config byte
- record count is `floor((payload_length - 1) / 2)`
- one trailing byte is consumed with no record action
- per record:
  - first byte low 4 bits = channel `0..15`
  - first byte high 4 bits = reserved
  - second byte bit `5` = target (`0` = synth, `1` = audio)
  - second byte low 5 bits = config selector `0..31`
  - second byte bits `6..7` = reserved
- duplicate `(target, channel)` records keep the first selector
- startup application tests `selector < config_table_count`
- accepted selectors use `config_table_base + selector`
- out-of-range selectors produce no startup config action
- `config_table_count` and `config_table_base` come from sound-backend construction state
- layered helper dispatch also encodes accepted selector as `selector + 2`
- monolithic helper writes the selected config-slot pointer directly

### `ainf`

- header-level active-resource declaration
- uses the ordinary `be16` top-level framing
- only the first accepted header `ainf` chunk is applied
- payload length is at least `1`
- byte `0` bit `0x40` is `0` for this playback resource path
- byte `0` low 6 bits define active `adat` count `0..63`
- bytes `1..` produce no resource-table state change in this playback path

### `adat`

- resource payload chunk with `be32` payload length
- resource-stage offset = `10 + header_length`
- the resource stage begins with a contiguous `adat` run
- the first `ainf.count` entries become active resource indices `0..count-1`
- each active entry stores payload position and payload length
- every required active entry must be present and structurally valid
- additional contiguous `adat` chunks are structurally validated and skipped
- the first following non-`adat` chunk ends this resource stage

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
- linear output clamps reconstructed G.726 to `[-8192,8191]`, then expands by `<<2`; predictor state uses the unclamped reconstruction
- mono decodes one compressed stream and duplicates the exported result to left
  and right
- stereo splits the blob into two contiguous half-streams, decodes them
  independently, then uses them as left and right
- backend-native working format is fixed `32000 Hz`, `32-bit`, `2-channel`

Production ordinary sampled-SFX profile:

- active top-level `adat` selected through `ainf`
- selector `0x81`
- selector flag `0x04` clear
- coded bit depth `4`
- sample rate `8000`, `16000`, or `32000`
- channel count `1` or `2`
- output route `0`
- final stream `32000 Hz`, PCM16, stereo

`7F:00` starts the typed resource. `7F:01` stops active voices matching the same logical sampled channel and resource index without unloading the resource. `7F:80` and `7F:81` update its logical-channel level and pan. `7F:90` selects its sampled route.

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
| `B0` | track `0`, value `< 0x80`; cache raw value; sampled `resourceLevel=floor(baseVolume*value/127)`, default `baseVolume=127` |
| `B1` | track `0`, value `< 0x80`; sampled `resourcePan=value`; synth balance is separate |
| `B2..B9` | empty handler |
| `BA` | track `0`, value `< 0x80`; channel=`(value>>3)&0x0F`, mode=`value&7` |
| `BB` | empty handler |
| `BC` | track `0`, value `< 0x80`; relative tempo; clamp `20..255` |
| `BD` | track `0`, value `< 0x80`; update cached B0 value relatively, clamp `0..127`, then apply the B0 backend |
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
| `E2` | level=`low6`; backend value=`2*level`; backend level `0` is zero gain |
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

- `0`: advance past the start command, then save the loop time and every track's position in slot `0..3`
- `1`: loop end
- `2,3`: consumed with no loop action

Each slot stores a track-position snapshot and repeat counter. A new operation `0` replaces that slot's snapshot. Rewind restores track positions, not the counters.

Repeat rule:

- nibble `0`: infinite loop
- nibble `1..15`: that many additional passes
- total body passes for finite loop: `repeatNibble + 1`

Because the snapshot is taken after the start command, rewind does not run that command again. After the last pass, execution continues after the end command.

The four slots may be sequential, nested, or crossing. Rewinding one slot may run another slot's commands again, so the result cannot be represented by one fixed source range.

- Every pass runs its timing, melody, control, and sampled-audio events again.
- Infinite playback starts when the parser state repeats. This identifies the recurring events but does not mean their results stay unchanged.
- Tempo, volume, melody, and audio changes carry into later passes.
- Sampled audio repeats only when the next pass has the same state, duration, and audio actions. Otherwise sampled-audio playback is rejected.
- After a long stall, MIDI skips expired messages and restores the current controllers and notes.

Zero-duration rule:

- loop-end raw time equal to saved loop-start raw time marks every track parser context done
- decoder returns immediately from the loop handler
- equal-time track selection uses ascending track context order; lower track index wins the tie
- track `0` zero-duration `DD` can terminate later same-tick events from higher tracks

## Live `0x7F` Resource-Control Family

Playback members:

- `0x7F 00`: trigger / start resource
- `0x7F 01`: resource stop slot; layered backend dispatch
- `0x7F 80`: audio channel level; layered backend dispatch
- `0x7F 81`: audio channel pan; layered backend dispatch
- `0x7F 90`: synth config / sampled route selection

Channel calculation:

- packed high 2 bits define local lane `0..3`
- audio channel = `trackIndex * 4 + localLane`

Short-form payload layouts:

- `0x7F 00`
  - total body length = `1 + exst`
  - first byte high 2 bits = local audio lane
  - first byte low 6 bits = active `adat` index
  - index must be below active `ainf` count for backend start action
  - active entry supplies resource payload position and length
  - `exst >= 1`: second body byte low 6 bits supply live parameter
  - `exst == 0`: live parameter low6 defaults to `63`
  - forwarded live parameter = `2 * low6`
  - remaining extension bytes are consumed
- `0x7F 01`
  - total body length = `1 + exst`
  - first byte high 2 bits = local audio lane
  - first byte low 6 bits = direct stop slot/index
  - layered families dispatch the lane and slot/index to audio backend vtable `+0x34`
  - monolithic `lib002/lib004` dispatch reaches a fixed no-op stub
  - extension bytes are consumed
- `0x7F 80`
  - one-byte compact payload
  - high 2 bits = local audio lane
  - low 6 bits = level field
  - dispatch argument = `2 * low6`
  - layered families dispatch to audio backend vtable `+0x38`
  - monolithic `lib002/lib004` dispatch reaches a fixed no-op stub
- `0x7F 81`
  - one-byte compact payload
  - high 2 bits = local audio lane
  - low 6 bits = pan field
  - dispatch argument = `2 * low6`
  - layered families dispatch to audio backend vtable `+0x3C`
  - monolithic `lib002/lib004` dispatch reaches a fixed no-op stub
- `0x7F 90`
  - one-byte compact payload
  - high 2 bits = local lane
  - bit `5` = target (`0` = synth, `1` = audio)
  - low 5 bits = selector `0..31`
  - synth target resolves the local lane through the current voice assignment
  - synth config dispatch requires resolved channel `< 16`
  - synth selector `< config_table_count`: select `config_table_base + selector`
  - layered synth dispatch encodes the accepted selector as `selector + 2`
  - monolithic synth dispatch writes the selected config-slot pointer
  - synth selector `31` clears an out-of-range target config slot
  - audio target uses `trackIndex * 4 + localLane`
  - audio selector `0..30` selects sampled route `selector + 2`
  - audio selector `31` restores sampled route `0`

Config and route relationship:

- `thrd` records seed synth/audio config-slot families
- live synth-target `0x7F 90` resolves through the external config table
- live audio-target `0x7F 90` updates sampled route state
- `thrd` out-of-range selectors produce no config action
- live synth selector `31` adds the out-of-range clear case

Extended validity:

- other unhandled commands below `0x80` consume `1 + exst` body bytes
- unhandled commands `0x80..0xEF` consume one body byte
- `0x7F F0..FF` use a BE16 body length

### `0x7F F0` 3D Auxiliary Event

Dispatch conditions:

- track index `0`
- body length at least `6`
- the handler reads the first six body bytes
- additional body bytes are consumed

Body fields:

| Offset | Field |
| --- | --- |
| `0` low5 | helper argument; unused by all five runtime families |
| `1` | raw 3D distance input |
| `2` | angle-A input |
| `3` | angle-B input |
| `4..5` | BE16 raw duration |

Distance processing:

- distance scale is backend-owned
- the layered plugin reads `3DDISTANCE`; its default value is `128`
- `scaledDistance = min(255, truncTowardZero(distanceScale * byte1 / 128))`
- callback dispatch requires `scaledDistance < config_table_count` and an installed 3D callback

Angle processing:

- `angleA0 = clamp(3 * byte2 - 384, -180, 180)`
- `angleA = angleA0 < 0 ? angleA0 + 360 : angleA0`
- `angleB = clamp(3 * byte3 - 384, -90, 90)`

Duration processing uses the active event-order tempo and timebase state:

- `q = floor(15360000 / timebase)`
- `whole = floor(q / tempo)`
- `fraction = floor(((q mod tempo) * 256) / tempo)`
- `durationMs = (((fraction * rawDuration) >>> 8) + whole * rawDuration) >>> 8`
- arithmetic follows 32-bit runtime integer behavior

The 3D callback receives a `0x28`-byte record:

| Offset | Value |
| --- | --- |
| `0x00` | `0x28` |
| `0x04` | `scaledDistance + 2` |
| `0x08` | backend timing/value query result |
| `0x0C` | `durationMs` |
| `0x10` | double `scaledDistance * 0.05` |
| `0x18` | double `angleA` |
| `0x20` | double `angleB` |

`0x7F F1..FF` consume their BE16-length bodies and have no runtime action in all five runtime families.

## Machine-Dependent Families

`0xFF 0xFF` carries a BE16-length machine-dependent payload.

### Descriptor Dispatch

The five MFi5 family profiles use the same ordered 64-entry descriptor profile.
Each descriptor contains two byte patterns and a handler ID. Matching is sequential and first-match.

Effective descriptor aliases:

| Handler | Raw prefix | Native family |
|---:|---|---|
| `0` | `21 81`, `41 81`, `71 81` | audio level |
| `1` | `21 82`, `41 80` | audio pan |
| `0x104` | `61 81`, `31 81` | phase-gated audio level |
| `0x105` | `61 82`, `41 82`, `71 82`, `31 82` | phase-gated audio pan |
| `2` | `21 83`, `41 83`, `71 83` | `0x8000` slot |
| `3` | `21 86`, `41 86`, `71 86` | cached `0x8000` slot |
| `0x109` | `61 83` | inline `0x8001` slot |
| `0x106` | `61 84`, `41 84`, `71 84`, `31 84` | counted-duration `0x8001` slot |
| `0x103` | `41 8F`, `71 8F`, `31 8F` | empty handler |
| `0x100` | `61 10`, `41 10`, `71 10`, `31 30`, `01 10` | synth request subtype 0 |
| `0x101` | `61 11`, `41 11`, `71 11`, `31 31`, `01 11` | synth request subtype 0 |
| `0x102` | `61 12`, `41 12`, `71 12`, `31 32`, `01 12` | synth request subtype 0 |
| `4` | `21 90`, `41 90`, `71 90` | synth request subtype 1 |
| `5` | `21 91`, `41 91`, `71 91` | synth request subtype 1 |
| `6` | `21 92`, `41 92`, `71 92` | synth request subtype 1 body-forward |
| `7` | `21 93`, `41 93`, `71 93` | synth request subtype 1 |
| `0x400` | `11 01 F0 07` | compact `0x8002` load state |
| `0x401` | `11 01 F1` | compact slot control |
| `0x402` | `31 10` | mixed second-stage dispatcher |
| `0x403` | `11 01 F0 05` | synth request subtype 2 |
| `0x404` | `11 01 F0 06` | synth request subtype 2 |
| `0x405` | `11 01 F0 03` | synth request subtype 2 |
| `0x406` | `11 01 F0 04` | synth request subtype 2 |
| `0x407` | `11 01 F2 07` | 16-lane route state |

Later duplicate descriptors for `41 81`, `71 81`, and `21 82` are unreachable under first-match dispatch.

### Audio Level and Pan

Handlers `0` and `1` consume one packed byte:

- channel = high 2 bits
- value = `2 * low6`
- handler `0` uses layered audio backend slot `+0x38`
- handler `1` uses layered audio backend slot `+0x3C`

Layered handlers `0` and `1` require `context+0x1C14 < 0x30340000` and `arg4 == 0`.

Handlers `0x104` and `0x105` use the same payload and backend actions. Runtime phase value `1` suppresses these wrappers.

`lib002/lib004` keep the descriptor grammar and produce no audio backend effect for these handler IDs.

### `0x8000` Slot Handlers

Handler `2` body:

- byte 0: channel high2, slot low6
- byte 1: operation high2, format low6
- remaining bytes: slot payload
- format `4`: `4000 Hz`
- format `5`: `8000 Hz`

Operations:

- `0`: load / refresh, then enter the start gate
- `1`: load / refresh, then enter the start gate
- `2`: enter the start gate with the existing slot
- `3`: ignore

The layered start gate accepts packed channel values `0/1` and applies additional live player-state gates.

Handler `3` body adds one control byte after byte 1:

- control bit 0 participates in active-slot cache reuse
- format `4`: `4000 Hz`
- format `5`: `8000 Hz`
- format `6`: `16000 Hz`
- operation `0`: load / refresh
- operation `1`: load / refresh, then enter the start gate
- operation `2`: enter the start gate with the existing slot
- operation `3`: ignore

The operation table above applies only to handler `3`'s non-pending path. Handler `3` shares its per-slot pending/channel/format/operation cache with `0x109/0x106/0x400`:

- pending + bit0 `0`: restore cached operation/format/channel and clear pending
- pending + bit0 `1`: force operation `0`, restore cached format/channel, and retain pending
- no pending + bit0 `1` + incoming operation other than `2`: cache operation/format/channel, set pending, release the old slot, and execute operation `0`; incoming operation `3` therefore loads

Pending operation `0/1` loads without release: the same MFiAudio type appends, while a different type is rejected without mutation. Ordinary operation `0/1` releases before format validation, so an invalid format leaves the slot empty.

Both handlers maintain slot state in the native player. Layered families also perform audio backend load/release/start calls. Monolithic families retain the internal slot-state mutations.

### `0x8001` Slot Handlers

Common body header for handlers `0x109` and `0x106`:

- byte 0: channel high2, slot low6
- byte 1: operation high2, format low6
- byte 2: only bit0 is used; bits7..1 are ignored

Format table:

| format | sample rate | coded bits |
|---:|---:|---:|
| `4` | `8000` | `2` |
| `5` | `8000` | `4` |
| `12` | `16000` | `2` |
| `13` | `16000` | `4` |
| `20` | `32000` | `2` |
| `21` | `32000` | `4` |

Operation model:

- `0`: load / refresh
- `1`: load / refresh, then enter the native start gate
- `2`: enter the native start gate with the existing slot
- `3`: ignore

Handler `0x109` uses every byte after the three-byte header as coded payload. `durationByteCount` equals the coded payload length.

Handler `0x106` reads BE32 `durationByteCount`; coded payload is the entire body remainder after that field. The BE32 value affects duration only.

Native duration:

- `samplesPerByte = 8 / codedBits`
- `sampleCount = (durationByteCount * samplesPerByte) mod 2^32`
- `durationMs = floor(sampleCount * 1000 / sampleRate + 0.5)`

Layered `0x109` and `0x106` both return before parsing when `context+0x1C14 >= 0x30340000` or `arg6 == 1`. When operation `1` reaches MFiAudio start, `startLevel=127` and the voice limit is `min(decodedFrames,durationMs*32)` at 32 kHz; the decoded cache remains complete. Monolithic families keep slot state without layered audio calls.

Current renderer coverage is descriptor `23` (`71 84`), operation `1`, raw control byte `0`, mono 4-bit format `5/13/21`. Raw-byte-zero is a fail-closed project boundary, not a `lib003` rule. Other machine `0x8001` forms remain unsupported until enabled separately. The ordinary active-`adat` profile is selector `0x81`.

### Compact `0x8002` Load State

`11 01 F0 07` uses handler `0x400`.

Body:

- byte 0: raw slot byte; the plugin caches all `0..255`, but layered MFiAudio `release/load` accepts only `0..63`, so `64..255` cannot become loaded sampled-audio slots
- byte 1 low2: persisted compact slot-state field; it is not passed to the `0x8002` codec format setter
- byte 1 bit7: channel-count selector; `0` = mono, `1` = stereo
- bytes 2..3: BE16 source sample rate
- remaining bytes: 4-bit AWC2 payload

The renderer supports only native-length-valid 4-bit AWC2 at `4000/8000/16000/32000 Hz`, mono or stereo. Native `awc2` requires at least 81 payload bytes for mono and 162 for stereo. Other source rates fail closed by project policy, not by native format validation.

Native duration is `round((2*codedPayloadLength)*1000/sampleRate)`, independent of channel count. In stereo, each coded byte maps its low nibble to channel 0 and high nibble to channel 1, with independent codec state. For `N` coded bytes, AWC2 fills the first `N` frames of MFiAudio's zero-initialized `2N`-frame source timeline; the second half remains zero. MFiAudio converts that PCM to its fixed 32-kHz cache with the native 8-tap polyphase FIR.

Within one playback program, the slot alone identifies an exposed resource. The layered backend's plugin-instance `resourceIndex` is not derived from the MLD source track. Reload stops voices on the slot, releases its old cache, and loads the replacement.

#### Shared Pending State

`0x400` sets the pending state shared by cached `0x003`, inline `0x109`, and counted `0x106`. It caches channel `0`, low2 format `0..3`, and duration, but preserves the cached operation. That operation starts at `0` and may hold `0/1/3` from an earlier non-pending, bit0-one handler call; this branch never caches `2`.

Pending execution never releases the existing slot first:

- `0x109/0x106` reuse cached channel/duration. Effective operation `0/1` tries to load `0x8001` over the existing `0x8002` slot and receives MFiAudio type-mismatch `-14`; cached operation `3` is a no-op.
- `0x003` reuses cached format/channel. Because `0x400` caches format `0..3` and handler `3` accepts only `4/5/6`, format validation preserves the `0x8002` slot.

Bit0 `0` restores the cached operation and clears pending before execution; after a validation return, the next call therefore follows the ordinary path. Bit0 `1` forces operation `0` and retains pending.

Without pending state, `0x109/0x106` with bit0 `1` and incoming operation other than `2` cache operation/format/channel/duration, set pending, release the old slot, and execute operation `0`. Incoming operation `3` therefore loads. A later pending operation `0/1` loads without release: MFiAudio appends to an existing `0x8001` slot, but rejects a different type without mutation and leaves its decoded cache allocated.

### Compact Slot Control

`11 01 F1` uses handler `0x401`. The first body byte supplies the native logical channel directly from high2 and opcode from low4; MLD track index is not added to the channel.

- `3/4`: low5 slot + low7 level; start the cached slot with its cached duration, independent of whether the current cache is verified `0x8002` or a later verified `0x8001` replacement
- `5`: low5 slot; stop the matching channel+slot voice without unloading the slot
- `6`: skip one byte, then pan; `0..63 -> 2*value`, `0x80/0xFF -> 64`; other raw values are no-op
- `7`: separate backend control and remains outside sampled rendering

`31 10` handler `0x402` is a second entry to the same backend: code `7` delegates to `0x400` load, `9/15` start, `10` stop, and `11` pan with the same channel/slot/value rules. Code `12` remains outside the verified sampled renderer.

Each MFiAudio backend instance has 16 active-voice entries. Start uses the first inactive entry or, when full, steals the oldest voice (the smallest start serial) at the new start time. Repeated starts of the same channel/slot remain separate voices until stopped, released, completed, or stolen.

Layered `0x109/0x106/0x400/0x401/0x402` execution is suppressed in runtime phase `1`; the semantic actions retain this phase gate.

### 16-Lane Route State

`11 01 F2 07` uses handler `0x407` and consumes 16 body bytes.

For lane `i`:

- class A = `(byte>>4)&3`
- class B = `(byte>>2)&3`
- bit1 and bit0 are parsed into temporary values
- class A `1` writes route flag `0`
- class A `3` writes route flag `1`
- class A `0/2` retains the route flag
- class B is stored in the per-lane route class table

Layered runtime phase `1` returns before parsing or route-state writes. Otherwise layered families can invoke backend slot `+0x44` under the native route gate; monolithic families retain the route-state writes.

### Mixed `31 10` Dispatcher

The first body byte supplies channel high2 and dispatch code low6.

| code | action |
|---:|---|
| `4,5,6` | layered synth request type `3`, subtype `2` |
| `7` | delegate handler `0x400` |
| `8` | delegate handler `0x407` |
| `9` | low5 slot + low7 value; layered audio start path |
| `10` | low5 slot; layered backend slot `+0x34` |
| `11` | skip one byte, consume value; layered backend slot `+0x3C` with doubled `0..63` or `0x40` for `0x80/0xFF` |
| `12` | one value byte; layered object slot `+0x48` |
| `13,14` | empty action |
| `15` | same audio-start path as code `9` |
| `16` | layered synth request type `3`, subtype `2` |

For layered synth dispatch, the complete first body byte is forwarded. Code `16` with channel `0` forwards `0x10` and reaches the FT command-10 path. Codes `4/5/6` have no accepted downstream synth action in the normal or FT synth modules. `lib002/lib004`: delegated codes `7/8` retain state effects; the other listed backend actions are inactive.

### Layered Synth Request Bridge

The plugin sends request type `3` through the synth object slot `+0x1C`.

Handler IDs `0x100..0x102`:

- request subtype `0`
- one byte immediately before the descriptor body is reinserted
- layered-normal accepts forwarded leading `0x12` and `0x32`
- layered-ft accepts forwarded leading `0x10`, `0x11`, and `0x12`

Handler IDs `4/5/7`:

- request subtype `1`
- one byte immediately before the descriptor body is reinserted
- forwarded leading values are `0x90`, `0x91`, or `0x93`
- layered-normal accepts `0x93`
- layered-ft has no action for these values

Handler ID `6`:

- request subtype `1`
- descriptor body is forwarded directly
- layered-normal accepts body leading `0x93`
- layered-ft accepts body leading `0x10`, `0x11`, `0x12`, `0x40`, and `0x41`

Handler IDs `0x403..0x406`:

- request subtype `2`
- the two bytes immediately before the descriptor body are reinserted
- forwarded payload begins `F0 05`, `F0 06`, `F0 03`, or `F0 04`
- layered-normal accepts `F0 04` and `F0 05`
- layered-ft has no action for leading `0xF0`

FT type-3 leading commands:

| command | downstream action |
|---:|---|
| `10` | backend passthrough mode `0` |
| `11` | backend passthrough mode `1` |
| `12` | advance payload and enter FT subtype-2 subdispatch |
| `40` | structured FT control |
| `41` | structured FT control |

Monolithic `lib002/lib004` map these synth-bridge handler IDs to no-op handlers.

### Family Handler Tables

The descriptor profile is shared across `lib001`, `lib002`, `lib003`, `lib003ft`, and `lib004`.

Layered families route handler IDs to plugin audio handlers and synth request wrappers.

Monolithic `lib002/lib004` retain native implementations for handlers `2`, `3`, `0x106`, `0x109`, `0x400`, `0x401`, `0x402`, and `0x407`. Their synth-bridge IDs and `0x103` resolve to no-op handlers. Handler IDs `0/1` parse their packed values without a persistent backend effect.
