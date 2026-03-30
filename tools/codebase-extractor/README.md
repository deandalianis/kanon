# codebase-extractor

`codebase-extractor` produces deterministic evidence for Java/Gradle services.

## What It Does

- resolves the build
- extracts source and bytecode facts
- optionally captures runtime witness facts
- inventories resource files
- runs deterministic evidence adapters
- writes `ExtractionSnapshot` artifacts, including `EvidenceSnapshot`

## Adapter Direction

Adapters are evidence producers only. They should emit exact facts for HTTP, persistence, migrations, scheduling, contracts, messaging, configs, docs, and integrations without making semantic claims about aggregates, commands, or business intent.

## Verify

```bash
./gradlew :tools:codebase-extractor:test
```
