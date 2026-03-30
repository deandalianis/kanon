# graph-neo4j

`graph-neo4j` projects Kanon into a dual knowledge graph.

## Inputs

- `EvidenceSnapshot`
- `SemanticSpecDocument`
- a run ID

## Graph Shape

- `EvidenceNode`
- `EvidenceRef`
- `SemanticNode`
- `KnowledgeRun`
- semantic-to-evidence `DERIVED_FROM` edges

## Main Entry Point

- `io.kanon.specctl.graph.neo4j.KnowledgeGraphService`

## Verify

```bash
./gradlew :tools:graph-neo4j:test
```
