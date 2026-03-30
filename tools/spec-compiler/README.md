# spec-compiler

`spec-compiler` now contains the semantic layer of Kanon.

## Primary Responsibilities

- synthesize `SemanticSpecDocument` from `EvidenceSnapshot`
- validate semantic specs and evidence citations
- build retrieval context for question-answer flows

## Compatibility

Legacy canonical compilation, migration, drift, and generation code still exists in this module, but it is no longer the primary runtime architecture. The current runtime path is evidence -> semantic spec -> graph/query.

## Main Entry Points

- `io.kanon.specctl.core.semantic.SemanticSpecSynthesisService`
- `io.kanon.specctl.core.semantic.SemanticSpecValidator`
- `io.kanon.specctl.core.semantic.SemanticQueryContextBuilder`

## Verify

```bash
./gradlew :tools:spec-compiler:test
```
