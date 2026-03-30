# codebase-model

`codebase-model` defines the deterministic side of Kanon.

## Primary Types

- `EvidenceSnapshot`
- `EvidenceNode`
- `EvidenceEdge`
- `EvidenceRef`
- `AdapterReport`
- `EvidenceConflictRecord`
- `EvidenceConfidence`
- `ExtractionSnapshot`

`CodebaseIr` is still present as a compatibility structure, but `EvidenceSnapshot` is now the primary extraction output.

## Verify

```bash
./gradlew :tools:codebase-model:test
```
