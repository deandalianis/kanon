# specctl

`specctl` is the CLI entry point for the evidence + semantic pipeline.

## Commands

- `extract`
- `validate`
- `synthesize`
- `approve`
- `graph rebuild`
- `ask`

## What It Does

- runs deterministic extraction
- writes extraction manifests and evidence artifacts
- validates semantic specs with evidence-citation checks
- synthesizes semantic drafts from extracted evidence
- approves validated drafts into an explicit baseline
- rebuilds the Neo4j knowledge graph
- assembles retrieval context for `ask`

## Verify

```bash
./gradlew :apps:specctl:compileJava
./gradlew :apps:specctl:run --args="extract --project /path/to/service --out-dir /tmp/kanon-runs"
```
