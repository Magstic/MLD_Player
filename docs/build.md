# Build

## Requirements
### General

1. **JDK 17 or newer**
   - This project has been verified with `Eclipse Adoptium JDK 21`
   - Other OpenJDK distributions should usually work as well

2. **Android SDK**
   - The current project points to the local SDK through `local.properties`
   - The current default path is:
     ```properties
     sdk.dir=D\:\\Android
     ```
   - At minimum, this directory should contain:
     - `cmdline-tools`
     - `platforms/android-34`
     - `build-tools`
     - `platform-tools`

3. **Android NDK + CMake**
   - This project contains the native library `libmldplayer_native.so`
   - Gradle builds it through `externalNativeBuild` with CMake
   - The related components are expected under the local SDK path:
     - `D:\Android\ndk`
     - `D:\Android\cmake`

4. **Gradle Wrapper**
   - The project already includes `gradlew` / `gradlew.bat`
   - On the first build, it will automatically download `Gradle 8.7` according to `gradle/wrapper/gradle-wrapper.properties`

### Signing

5. **Release signing file and configuration**
   - The project root supports a local `my-release-key.jks`
   - Signing parameters are loaded from `keystore.properties`

> NOTE 1: If signing passwords are not filled in, the project will fall back to debug signing for release builds.
> NOTE 2: If you prefer using Android Studio instead of a standalone local dependency setup, you may need some additional local configuration.

---

## Build

### Windows

| Command | Description |
|------|------|
| `.\gradlew.bat assembleRelease` | Build all release APKs |
| `.\gradlew.bat :core:build` | Build and verify the shared core module only |
| `.\gradlew.bat clean` | Clean Gradle build outputs |

### Linux

```bash
./gradlew assembleRelease
```

Build outputs are placed under `app/build/outputs/apk/release/`.

---

## Current Build Chain

The current Android build chain is roughly:

1. The `core` module compiles the MLD parser, event decoding, normalization, timeline, and MIDI encoding logic
2. The `app` module compiles the Android UI, playback controller, notification service, and SF2 streaming playback logic
3. CMake builds the native library `libmldplayer_native.so`
4. `fluidsynth-android` is linked through Prefab
5. Release builds perform:
   - R8 code shrinking
   - resource shrinking
   - native symbol stripping
   - ABI split packaging
   - APK signing

---

## Actual Verified Build Environment

- JDK: `Eclipse Adoptium 21`
- Gradle: `8.7` (downloaded automatically by the wrapper)
- Android Gradle Plugin: `8.5.2`
- SDK path: `D:\Android`
- Build command:
  ```powershell
  .\gradlew.bat assembleRelease
  ```
