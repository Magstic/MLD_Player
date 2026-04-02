This directory stores archived Java code that is intentionally excluded from the
main `MLD Player` build.

Current archived area:
- Sound effect host playback experiments
- ADPCM decoding and PCM output helpers
- Melody regression audit helpers such as `main.ExportAudit`

The Ant build compiles only `src`, so files kept under `src_test` remain
available for later research without affecting the melody-focused player.
