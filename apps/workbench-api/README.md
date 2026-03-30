# workbench-api

`workbench-api` is the orchestration layer for the new Kanon workflow.

## Workflow Stages

- extract deterministic evidence
- synthesize a semantic spec draft
- validate and approve the semantic spec
- rebuild graph lineage
- ask grounded questions with semantic + evidence context

## Main Services

- `WorkbenchController`
- `WorkbenchWorkflowService`
- `WorkspaceService`
- `RunService`
- `LlmProviderRouter`

## Verify

```bash
./gradlew :apps:workbench-api:test
./gradlew :apps:workbench-api:bootRun
```
