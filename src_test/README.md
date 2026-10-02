# Test source tree

`src_test` is exclusively the project's Java test source root.

It contains deterministic regression, integration, and architecture tests plus test-only fixtures/support code.

Assertions cover decoded values, state transitions, MIDI/PCM output and ownership
boundaries. Display wording and private debug names are not test contracts; file
format markers, external protocol commands, structured classifications and input
metadata remain functional data and are checked.

The complete `src_test` tree is compiled and executed automatically by the normal Ant build, if any audit fails, packaging fails.

Current required tests:

- `mld.api.InstrumentMappingAudit` — full MLD → serialized MID mapping matrix
  and 8 generated fixtures; covers bank/program combinations, command order,
  resets and whole-song percussion classification; also checks exact Program
  Change ticks, per-channel deduplication and reset invalidation. Recreates inputs,
  outputs and `summary.csv` in `build/instrument-mapping`.
- `mld.format.FormatDecodeFoundationAudit`
- `architecture.ArchitectureClosureAudit`
- `main.ApplicationCompositionAudit`
- `export.ExportSystemAudit`
- `midi.MidiSerializationAudit`
- `playback.PlaybackInfrastructureAudit`
- `playback.PlaybackTransportAudit`
- `mld.semantic.TimingSemanticsAudit`
- `mld.semantic.OrdinaryNoteSemanticsAudit`
- `mld.semantic.SystemEventSemanticsAudit`
- `mld.semantic.ResourceSemanticsAudit`
- `mld.semantic.ResourceLongFormSemanticsAudit`
- `mld.semantic.AudioSemanticAudit`
- `audio.AudioRendererAudit`
- `normalize.MachineDependentSemanticsAudit`
