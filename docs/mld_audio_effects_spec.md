# MLD Audio and Effects Spec

>The MLD (MFi) specification was obtained through static reverse engineering of the DoJa Soundlib(MFi Player) and completed by GPT 5.6 Sol & GPT6 Astra.

>Since this project implements playback via MIDI mapping, the above specification may contain inconsistencies with the project’s code.

>**NOTE: AI-generated content may contain inaccuracies. I can only do my best to iteratively validate it by leveraging multiple AI systems, in order to minimize the likelihood of such errors.**

## Scope

This document defines sampled-audio codecs, sampled-slot state, mixer controls, routes, and 3D auxiliary events.

MLD grammar and event ordering are defined in `mld_canonical_spec.md`. MIDI projection is defined in `mld_mapping_spec.md`.

## Active Sampled Resources

Top-level `ainf` selects the active contiguous `adat` resource set.

Selector `0x81` defines MFiAudio type `0x8001`.

Accepted source profiles:

| coded bits | sample rate | channels |
|---:|---:|---:|
| `2` | `8000`, `16000`, `32000` Hz | `1`, `2` |
| `4` | `8000`, `16000`, `32000` Hz | `1`, `2` |

Selector-local `adpm` fields:

- byte `0`: source rate in kHz
- byte `1`: coded bit depth
- byte `2` low3: channel count

The coded payload follows the selector section.

Live resource controls:

- `0x7F 00`: start
- `0x7F 01`: stop matching logical-channel/resource voices
- `0x7F 80`: sampled logical-channel level
- `0x7F 81`: sampled logical-channel pan
- `0x7F 90`: sampled route/config selection

Repeated starts allocate independent voices. Resource data remains cached across voice completion.

## MFiAudio `0x8001` G.726

### Packing

4-bit codes are stored low nibble first:

```text
code0 = byte & 0x0F
code1 = (byte >> 4) & 0x0F
```

2-bit codes are stored low bits first:

```text
code0 = byte & 0x03
code1 = (byte >> 2) & 0x03
code2 = (byte >> 4) & 0x03
code3 = (byte >> 6) & 0x03
```

2-bit codec tables:

```text
inverse quantizer = [116, 365, 365, 116]
scale adaptation  = [-22, 439, 439, -22]
speed control     = [0, 7, 7, 0]
sign bit          = bit 1
```

The predictor/adaptation state machine is shared with the 4-bit family.

Linear PCM export uses:

```text
predictor state    = reconstructed
linear output      = clamp(reconstructed, -8192, 8191)
PCM16-domain value = linear output << 2
```

### Mono and Stereo

Mono contains one compressed stream. Its decoded output is duplicated to left and right.

Stereo contains two contiguous compressed half-streams:

```text
half  = compressedLength >> 1
left  = payload[0 .. half)
right = payload[half .. half + half)
```

Each channel starts with independent predictor state. An odd final compressed byte is outside both halves.

### Source-Rate Conversion

The sampled working domain is `32000 Hz`, stereo.

- `8000 Hz`: 4x conversion through three IIR sections
- `16000 Hz`: 2x conversion through three IIR sections
- `32000 Hz`: source-rate conversion bypass

The decoded cache includes codec normalization and the fixed post-codec processing chain.

## Machine-Dependent `0x8001`

Handlers `0x109` and `0x106` address one of 64 sampled slots.

Common control header:

- byte `0`: logical channel high2, slot low6
- byte `1`: operation high2, format low6
- byte `2`: control bit0

Format table:

| format | sample rate | coded bits | channels |
|---:|---:|---:|---:|
| `4` | `8000` | `2` | `1` |
| `5` | `8000` | `4` | `1` |
| `12` | `16000` | `2` | `1` |
| `13` | `16000` | `4` | `1` |
| `20` | `32000` | `2` | `1` |
| `21` | `32000` | `4` | `1` |

Operations:

- `0`: load/refresh
- `1`: load/refresh, then start
- `2`: start existing slot
- `3`: no action

Handler `0x109`:

```text
control[3]
coded payload
```

`durationByteCount` equals coded-payload length.

Handler `0x106`:

```text
control[3]
durationByteCount_be32
coded payload
```

The BE32 count controls duration. The complete body remainder after the count is loaded as coded payload.

Duration calculation:

```text
samplesPerByte = 8 / codedBits
sampleCount    = (durationByteCount * samplesPerByte) mod 2^32
durationMs     = floor(sampleCount * 1000 / sampleRate + 0.5)
```

Start level is `127`. Voice length is capped at:

```text
min(decodedFrames, durationMs * 32)
```

The decoded slot cache remains complete.

### Shared Pending State

Cached `0x8000`, `0x109`, `0x106`, and compact `0x400` share per-slot pending state.

State fields include:

- pending flag
- cached operation
- cached logical channel
- cached duration

For `0x109/0x106`:

- pending + control `0`: restore cached operation/channel/duration and clear pending
- pending + control `1`: force operation `0`, restore cached channel/duration, retain pending
- no pending + control `1` + incoming operation other than `2`: cache operation/channel/duration, set pending, release the old slot, execute operation `0`
- ordinary operation `0/1`: release the old slot before load
- pending operation `0/1`: load without release

Load without release appends coded bytes when the current slot type is `0x8001`. A different current type returns a type-mismatch result without slot mutation.

An append updates the encoded bytes and current format fields. An already-created decoded cache remains allocated.

## Compact `0x8002`

Handler `0x400` body:

- byte `0`: slot
- byte `1` low2: compact state field
- byte `1` bit7: channel count (`0` mono, `1` stereo)
- bytes `2..3`: BE16 source sample rate
- remaining bytes: 4-bit AWC2 payload

Native duration:

```text
durationMs = round((2 * codedPayloadLength) * 1000 / sampleRate)
```

Stereo AWC2 uses low nibble for channel 0 and high nibble for channel 1 with independent codec state.

Handler `0x400` sets shared pending state, logical channel `0`, and cached duration. It preserves the cached operation.

Handler `0x401` compact controls:

- `3/4`: start cached slot with low7 level
- `5`: stop matching logical-channel/slot voice
- `6`: pan control
- `7`: backend control

Handler `0x402` delegates code `7` to `0x400`, code `9/15` to start, code `10` to stop, code `11` to pan, and code `8` to route-state handler `0x407`.

## Sampled Voice Pool

The layered MFiAudio backend maintains 16 active sampled voices.

A start uses the first inactive voice entry. A full pool reuses the active entry with the oldest start serial.

Repeated starts of the same channel/slot remain independent voices until stop, release, completion, or pool reuse.

## Sampled Level and Pan

Global sampled controls:

- `B0`: sampled resource level
- `BD`: relative update of the cached `B0` value
- `B1`: sampled resource pan

Machine-dependent sampled logical-channel controls update active sampled voices through the same mixer state.

The sampled gain chain contains resource level, start level, logical-channel level, and sampled-global level. Pan combines resource pan and logical-channel pan through the native tables.

## Routes

`0x7F 90` updates sampled route/config selection.

Handler `0x407` consumes 16 route bytes. For each lane:

```text
classA = (byte >> 4) & 3
classB = (byte >> 2) & 3
```

- classA `1`: route flag `0`
- classA `3`: route flag `1`
- classA `0/2`: retain route flag
- classB: stored route class

Route `0` is the standard stereo sampled output path.

## 3D Auxiliary Events

`0x7F F0` uses track `0` and a body of at least six bytes.

First six body bytes:

- byte `0` low5: helper argument
- byte `1`: raw distance
- byte `2`: angle-A input
- byte `3`: angle-B input
- bytes `4..5`: BE16 raw duration

Angles:

```text
angleA0 = clamp(3 * byte2 - 384, -180, 180)
angleA  = angleA0 < 0 ? angleA0 + 360 : angleA0
angleB  = clamp(3 * byte3 - 384, -90, 90)
```

Distance scaling:

```text
scaledDistance = min(255, truncTowardZero(distanceScale * byte1 / 128))
```

The standard layered `3DDISTANCE` default is `128`.

Duration uses the active event-order tempo/timebase state.

`0x7F F1..FF` consume their BE16-length bodies and perform no runtime action in the five examined families.

## MIDI Boundary

Sampled-audio resources, machine audio state, sampled routes, and 3D auxiliary events produce no direct MIDI messages.
