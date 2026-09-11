# mld-player Conversion API

`mld-player.jar` exposes `mld.api` as its stable Java conversion API.

## Types

- `MldConverter` compiles MLD bytes.
- `MldConversion` reports available outputs and creates MIDI or PCM results.
- `MldPcm16` contains immutable interleaved signed PCM16 samples.

## Usage

```java
import javax.sound.midi.Sequence;
import mld.api.MldConversion;
import mld.api.MldConverter;
import mld.api.MldPcm16;

MldConversion conversion = MldConverter.convert(mldBytes);

if (conversion.hasMidi()) {
    Sequence sequence = conversion.createMidiSequence();
}

if (conversion.hasRenderableSampledAudio()) {
    MldPcm16 pcm = conversion.renderSampledPcm16();
    int sampleRate = pcm.getSampleRate();
    int channels = pcm.getChannels();
    short[] samples = pcm.copyInterleavedSamples();
}
```

`renderSampledPcm16(int targetRate)` renders PCM at an explicit output rate.

## API boundary

External code imports `mld.api`.

`mld.compile`, `mld.format`, `mld.decode`, `mld.semantic`, `midi`, and `audio` are implementation packages.

`PublicApiAudit` checks public signatures during `ant jar`.

## Release dependency

Use an exact GitHub release tag and the `mld-player.jar` release asset:

```text
https://github.com/Magstic/MLD_Player/releases/download/<release>/mld-player.jar
```

Pin the release tag and SHA-256 in downstream builds. Cache the downloaded JAR in the downstream dependency directory.
