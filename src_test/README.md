# Test source tree

`src_test` is exclusively the project's Java test source root.

It contains deterministic regression, integration, and architecture tests plus test-only fixtures/support code.

The complete `src_test` tree is compiled and executed automatically by the normal Ant build, if any audit fails, packaging fails.

Current required tests:

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
