# Tree

```text
MLD Player/                      Project root
   ├─ app/                       Android app module
   │  ├─ src/main/               runtime sources and resources
   │  │  ├─ AndroidManifest.xml  app manifest
   │  │  ├─ cpp/                 native FluidSynth bridge
   │  │  ├─ java/com/magstic/mldplayer/
   │  │  │  ├─ Activity + fragments
   │  │  │  ├─ playback engines, controller, service
   │  │  │  ├─ adapters, models, repository
   │  │  │  └─ custom views and UI behaviors
   │  │  └─ res/                 layouts, drawables, strings, themes
   │  ├─ build.gradle            Android app build config
   │  └─ proguard-rules.pro      release shrinker rules
   ├─ core/                      shared MLD parsing and compilation module
   │  ├─ src/main/java/
   │  │  ├─ container/           MLD container parsing
   │  │  ├─ event/               per-track event decoding
   │  │  ├─ normalize/           machine-dependent event normalization
   │  │  ├─ playback/            MIDI encoding helpers used by Android
   │  │  └─ timeline/            timeline compilation and loop/tempo mapping
   │  └─ build.gradle            shared Java library build config
   ├─ docs/                      project-local specs and structure docs
   │  ├─ Tree.md                 this file
   │  ├─ build.md                Android build requirements and commands
   │  ├─ mld_canonical_spec.md   canonical MLD notes
   │  └─ mld_mapping_spec.md     mapping and playback notes
   ├─ LICENSES/                  bundled third-party license texts
   │  └─ LGPL-2.1.txt            FluidSynth license text
   ├─ gradle/wrapper/            Gradle wrapper files
   ├─ build.gradle               root Gradle build
   ├─ gradle.properties          shared Gradle properties
   ├─ gradlew / gradlew.bat      Gradle launchers
   ├─ README.md                  Android project overview
   ├─ settings.gradle            module declarations
   └─ LICENSE                    project license
```
